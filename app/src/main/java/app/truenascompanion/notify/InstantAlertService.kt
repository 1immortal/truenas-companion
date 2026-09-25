package app.truenascompanion.notify

import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import app.truenascompanion.TrueNasApp
import app.truenascompanion.data.api.TrueNasApi
import app.truenascompanion.data.api.TrueNasException
import app.truenascompanion.data.model.ServerConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * "Instant alerts": a foreground service that keeps a WebSocket to each watched server and subscribes to the
 * `alert.list` event (ADDED / CHANGED / REMOVED). Every event triggers the same diff as the periodic check, so a
 * missed event or a reconnect never loses or duplicates a notification. Reconnects with exponential backoff
 * (5 s … 5 min) and retries early when the phone gets a network again.
 *
 * Type `specialUse`: `dataSync` is limited to 6 h/day on Android 15+ and can't be started at boot, which doesn't fit
 * a long-lived monitoring connection.
 */
class InstantAlertService : Service() {
    companion object {
        const val ACTION_STOP = "app.truenascompanion.action.STOP_INSTANT"
        private const val TAG = "InstantAlerts"
        private const val MIN_BACKOFF_MS = 5_000L
        private const val MAX_BACKOFF_MS = 5 * 60_000L
        private const val SIGN_IN_RETRY_MS = 30 * 60_000L
        private const val REST_POLL_MS = 2 * 60_000L

        @Volatile
        var running = false
            private set
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val watchers = HashMap<String, Pair<ServerConfig, Job>>()
    private val status = ConcurrentHashMap<String, String>()
    private val networkBack = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private val container get() = (application as TrueNasApp).container

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        goForeground("Connecting…")
        val cm = getSystemService(ConnectivityManager::class.java)
        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { networkBack.tryEmit(Unit) }
        }.also { cb -> runCatching { cm?.registerDefaultNetworkCallback(cb) } }

        scope.launch {
            combine(container.settings.notificationPrefs, container.settings.servers) { prefs, servers ->
                if (!prefs.instant) emptyList() else servers.filter { it.id in prefs.enabledServers }
            }.collect { reconcile(it) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            scope.launch { container.settings.updateNotificationPrefs { it.copy(instant = false) } }
            stopSelf()
            return START_NOT_STICKY
        }
        goForeground(statusText())
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        networkCallback?.let { cb -> runCatching { getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb) } }
        scope.cancel()
        synchronized(watchers) { watchers.clear() }
        super.onDestroy()
    }

    private fun reconcile(servers: List<ServerConfig>) {
        if (servers.isEmpty()) {
            stopSelf()
            return
        }
        synchronized(watchers) {
            val wanted = servers.associateBy { it.id }
            (watchers.keys - wanted.keys).forEach { id -> watchers.remove(id)?.second?.cancel(); status.remove(id) }
            wanted.values.forEach { server ->
                val existing = watchers[server.id]
                if (existing == null || existing.first != server) {
                    existing?.second?.cancel()
                    watchers[server.id] = server to scope.launch { watch(server) }
                }
            }
        }
        updateNotification()
    }

    private suspend fun watch(server: ServerConfig) {
        val checker = container.alertChecker
        var attempt = 0
        while (currentCoroutineContext().isActive) {
            var api: TrueNasApi? = null
            try {
                setStatus(server, "connecting…")
                api = container.backgroundConnector.connect(server)
                checker.onConnected(server)
                attempt = 0
                setStatus(server, "connected")
                checker.check(server, api)
                try {
                    // Bursts of events (e.g. several alerts at once) collapse into one check.
                    api.alertEvents().conflate().collect {
                        delay(1_500)
                        checker.check(server, api)
                    }
                } catch (e: TrueNasException.Unsupported) {
                    // Legacy REST API: no events, poll instead.
                    while (currentCoroutineContext().isActive) {
                        delay(REST_POLL_MS)
                        if (checker.check(server, api) == CheckOutcome.NETWORK_ERROR) throw TrueNasException.NotConnected()
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: TrueNasException.LoginRequired) {
                checker.onSignInNeeded(server)
                setStatus(server, "paused, sign in needed")
                withTimeoutOrNull(SIGN_IN_RETRY_MS) { checker.signedIn.filter { it == server.id }.first() }
                continue
            } catch (e: Throwable) {
                Log.i(TAG, "${server.name}: ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                api?.close()
            }
            attempt++
            val backoff = (MIN_BACKOFF_MS shl (attempt - 1).coerceAtMost(6)).coerceAtMost(MAX_BACKOFF_MS)
            setStatus(server, "reconnecting…")
            withTimeoutOrNull(backoff) { merge(networkBack, checker.signedIn.filter { it == server.id }.map { }).first() }
        }
    }

    private fun setStatus(server: ServerConfig, text: String) {
        if (status.put(server.id, "${server.name}: $text") != "${server.name}: $text") updateNotification()
    }

    private fun statusText(): String {
        val values = status.values.sorted()
        return when {
            values.isEmpty() -> "Connecting…"
            values.size == 1 -> values.first().replace(": connected", " · connected")
            values.all { it.endsWith(": connected") } -> "Watching ${values.size} servers"
            else -> values.joinToString(" · ")
        }
    }

    private fun stopIntent(): PendingIntent = PendingIntent.getService(
        this, 0, Intent(this, InstantAlertService::class.java).setAction(ACTION_STOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun goForeground(text: String) {
        val notification = container.notifier.buildService(text, stopIntent())
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, AlertNotifier.ID_SERVICE, notification, type)
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun updateNotification() {
        if (!running || !container.notifier.canPost()) return
        NotificationManagerCompat.from(this).notify(AlertNotifier.ID_SERVICE, container.notifier.buildService(statusText(), stopIntent()))
    }
}
