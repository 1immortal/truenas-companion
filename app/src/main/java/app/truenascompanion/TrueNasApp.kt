package app.truenascompanion

import app.truenascompanion.data.net.RouteResolver
import app.truenascompanion.data.vpn.TunnelManager
import app.truenascompanion.data.security.AppLock
import android.app.Application
import app.truenascompanion.data.api.CertificatesApi
import app.truenascompanion.data.api.SharedConnections
import app.truenascompanion.data.repository.ConnectionState
import app.truenascompanion.quick.QuickActions
import app.truenascompanion.data.api.SessionTokenManager
import app.truenascompanion.data.repository.TrueNasRepository
import app.truenascompanion.data.security.SecretCipher
import app.truenascompanion.data.store.SettingsStore
import app.truenascompanion.notify.AlertChecker
import app.truenascompanion.notify.AlertNotifier
import app.truenascompanion.notify.AlertScheduler
import app.truenascompanion.notify.BackgroundConnector
import app.truenascompanion.notify.SessionKeepAliveWorker
import app.truenascompanion.data.update.UpdateCenter
import app.truenascompanion.data.update.UpdateCheckWorker
import app.truenascompanion.widget.WidgetRefreshWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** A notification tap, app shortcut or tile asking the UI to show [destination] (with optional [arg]) for [serverId]. */
data class PendingDeepLink(val serverId: String?, val destination: String, val nonce: Long = System.nanoTime(), val arg: String? = null)

/** Tiny manual DI container — no DI framework needed for an app this size. */
class AppContainer(app: Application) {
    /** Application context (1.3.0: file browser cache, content resolver for downloads/uploads). */
    val context: android.content.Context = app
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val settings = SettingsStore(app, SecretCipher())
    val notifier = AlertNotifier(app)
    /** One signed-in socket per server, shared by the UI, the periodic check and instant alerts where possible. */
    val sharedConnections = SharedConnections()
    /** One owner of the session-token chain for the whole process (UI, workers, instant-alerts service). */
    val sessions = SessionTokenManager(settings.tokenStore)
    val backgroundConnector = BackgroundConnector(settings, sessions)
    /** Local vs remote address per network, shared by the UI, periodic checks and instant alerts. */
    val routes = RouteResolver(app)
    /** The built-in WireGuard tunnel (0.6), shared by the app, background checks and instant alerts. */
    val tunnels = TunnelManager(app) { s -> settings.wireGuard(s.id) }.also { routes.tunnels = it }
    val alertChecker = AlertChecker(settings, backgroundConnector, notifier, sharedConnections, routes)
    val repository = TrueNasRepository(settings, appScope, onSignedIn = { alertChecker.onSignedIn(it) }, shared = sharedConnections, resolver = routes, sessions = sessions)
    val deepLinks = MutableStateFlow<PendingDeepLink?>(null)
    val appLock = AppLock()
    /** In-app update check state (GitHub Releases of BuildConfig.UPDATE_REPO). */
    val updates = UpdateCenter()
    /** One-shot request for the Storage tab to show a segment (e.g. Protection from the dashboard card). */
    val storageTabRequest = MutableStateFlow<Int?>(null)
}

class TrueNasApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.notifier.createChannels()
        WidgetRefreshWorker.sync(this)
        WidgetRefreshWorker.refreshNow(this)
        UpdateCheckWorker.createChannel(this)
        container.appScope.launch {
            container.settings.autoUpdateCheck.collect { UpdateCheckWorker.sync(this@TrueNasApp, it) }
        }
        container.appScope.launch { container.settings.lockSettings.collect { container.appLock.onSettingsLoaded(it) } }
        // Keep WorkManager / the instant-alerts service in sync with the settings for the life of the process.
        container.appScope.launch {
            combine(container.settings.notificationPrefs, container.settings.servers) { p, s -> p to s }
                .distinctUntilChanged()
                .collect { (prefs, servers) ->
                    AlertScheduler.sync(this@TrueNasApp, prefs, servers)
                    SessionKeepAliveWorker.sync(this@TrueNasApp, servers)
                }
        }
        // 1.2.0: after switching the web UI certificate, confirm it (system.general.checkin) once the app is connected
        // again with the newly trusted certificate; otherwise TrueNAS rolls the change back by itself.
        container.appScope.launch {
            container.repository.state.collect { st ->
                if (st !is ConnectionState.Connected) return@collect
                val server = container.repository.activeServer.value ?: return@collect
                if (!server.uiCertCheckinPending || server.certReviewRequired) return@collect
                runCatching { container.repository.call { CertificatesApi(it).checkin() } }
                    .onSuccess { container.settings.updateServer(server.id) { it.copy(uiCertCheckinPending = false) } }
            }
        }
        container.appScope.launch {
            container.settings.servers.collect { list -> QuickActions.pruneShortcuts(this@TrueNasApp, list.map { it.id }.toSet()) }
        }
    }
}
