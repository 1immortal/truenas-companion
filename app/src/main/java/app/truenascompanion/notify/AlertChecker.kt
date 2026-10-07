package app.truenascompanion.notify

import android.util.Log
import app.truenascompanion.data.api.CertificatesApi
import app.truenascompanion.data.api.Credentials
import app.truenascompanion.data.api.LoginStep
import app.truenascompanion.data.api.TrueNasApi
import app.truenascompanion.data.api.TrueNasConnector
import app.truenascompanion.data.api.TrueNasException
import app.truenascompanion.data.api.WebSocketAuth
import app.truenascompanion.data.api.SharedConnections
import app.truenascompanion.data.api.SessionTokenManager
import app.truenascompanion.data.net.Keepalive
import app.truenascompanion.data.model.AuthMethod
import app.truenascompanion.data.model.ServerConfig
import app.truenascompanion.data.repository.sessionTtlSeconds
import app.truenascompanion.data.store.NotificationPrefs
import app.truenascompanion.data.store.SettingsStore
import app.truenascompanion.data.vpn.TunnelHolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.LocalTime
import java.util.concurrent.ConcurrentHashMap

enum class CheckOutcome { OK, SIGN_IN_NEEDED, NETWORK_ERROR, FAILED }

/**
 * Non-interactive connection for background work. Never shows UI: uses the saved session tokens through the shared
 * [SessionTokenManager] (which rotates them on every sign-in, because TrueNAS destroys a token when the connection
 * that used it closes), then the remembered password if the user opted in. Throws [TrueNasException.LoginRequired]
 * when a human is needed (2FA code, no credentials, rejected API key). Network errors propagate and keep the tokens.
 */
class BackgroundConnector(
    private val settings: SettingsStore,
    private val sessions: SessionTokenManager = SessionTokenManager(settings.tokenStore),
) {
    /** [keepalive]: [Keepalive.NONE] for one-shot checks, [Keepalive.LONG_LIVED] for the instant-alerts socket. */
    suspend fun connect(server: ServerConfig, keepalive: Keepalive = Keepalive.NONE): TrueNasApi = withContext(Dispatchers.IO) {
        when (server.authMethod) {
            AuthMethod.API_KEY -> {
                val key = settings.apiKey(server.id) ?: throw TrueNasException.LoginRequired()
                try {
                    TrueNasConnector.connect(server, key, keepalive)
                } catch (e: TrueNasException.AuthFailed) {
                    throw TrueNasException.LoginRequired()
                }
            }
            AuthMethod.PASSWORD -> connectPassword(server, keepalive)
        }
    }

    /**
     * Keep-alive: renews the session tokens if a renewal is due (see [SessionTokenManager.renewalDue]) by signing in
     * once and closing again. Returns false if nothing was due or no token is saved.
     */
    suspend fun renewSession(server: ServerConfig): Boolean = withContext(Dispatchers.IO) {
        if (server.authMethod != AuthMethod.PASSWORD) return@withContext false
        if (!sessions.renewalDue(server.id, server.sessionTtlSeconds())) return@withContext false
        val api = sessions.connect(server.id, server.sessionTtlSeconds()) { token ->
            WebSocketAuth.tokenConnection(server, token, Keepalive.NONE)
        } ?: return@withContext false
        api.close()
        true
    }

    private suspend fun connectPassword(server: ServerConfig, keepalive: Keepalive): TrueNasApi {
        val ttl = server.sessionTtlSeconds()
        sessions.connect(server.id, ttl) { token -> WebSocketAuth.tokenConnection(server, token, keepalive) }
            ?.let { return it }
        val remembered = settings.password(server.id)
        if (remembered != null && server.username.isNotBlank()) {
            try {
                when (val step = WebSocketAuth.login(server, Credentials.Password(server.username, remembered), ttl, keepalive)) {
                    is LoginStep.Success -> {
                        if (step.token != null) sessions.replace(server.id, step.tokens)
                        return step.api
                    }
                    is LoginStep.OtpRequired -> {
                        step.pending.cancel() // 2FA needs the user
                        throw TrueNasException.LoginRequired()
                    }
                }
            } catch (e: TrueNasException.PasswordRejected) {
                throw TrueNasException.LoginRequired()
            }
        }
        throw TrueNasException.LoginRequired()
    }
}

/** Fetches `alert.list`, diffs it against the last-seen state and posts notifications. Shared by the worker and the service. */
class AlertChecker(
    private val settings: SettingsStore,
    private val connector: BackgroundConnector,
    private val notifier: AlertNotifier,
    private val shared: SharedConnections = SharedConnections(),
    private val resolver: app.truenascompanion.data.net.RouteResolver = app.truenascompanion.data.net.RouteResolver(null),
) {
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val classTitles = ConcurrentHashMap<String, Map<String, String>>()

    /** Checks every server with notifications enabled. */
    suspend fun checkAll(): List<CheckOutcome> {
        val prefs = settings.notificationPrefs.first()
        val servers = settings.servers.first().filter { it.id in prefs.enabledServers }
        return servers.map { check(it) }
    }

    /** Opens a connection unless [api] is given (instant mode passes its live one). */
    suspend fun check(server: ServerConfig, api: TrueNasApi? = null): CheckOutcome = withContext(Dispatchers.IO) {
        locks.getOrPut(server.id) { Mutex() }.withLock {
            // Reuse an open socket (instant alerts or the app on screen) before signing in on a new one.
            // Local or remote address, whichever is right on the current network (cached per network).
            val decided = if (api != null) server else resolver.resolve(server)
            val borrowed = api ?: shared.borrow(decided)
            // Not borrowing: open our own connection, bringing the WireGuard tunnel up briefly if that's the route.
            var target = decided
            val conn = try {
                borrowed ?: connectVia(server) { target = it }
            } catch (e: TrueNasException.LoginRequired) {
                onSignInNeeded(server)
                return@withLock CheckOutcome.SIGN_IN_NEEDED
            } catch (e: Throwable) {
                Log.i(TAG, "check ${server.name}: connect failed: ${e.message}")
                return@withLock if (e.isNetwork()) CheckOutcome.NETWORK_ERROR else CheckOutcome.FAILED
            }
            try {
                onConnected(server)
                process(server, conn)
                CheckOutcome.OK
            } catch (e: Throwable) {
                Log.i(TAG, "check ${server.name}: ${e.message}")
                if (e.isNetwork()) CheckOutcome.NETWORK_ERROR else CheckOutcome.FAILED
            } finally {
                if (borrowed == null) {
                    conn.close()
                    resolver.release(target, TunnelHolder.CHECK)
                }
            }
        }
    }

    private suspend fun process(server: ServerConfig, api: TrueNasApi) {
        val alerts = api.alerts()
        val prefs = settings.notificationPrefs.first()
        if (server.id !in prefs.enabledServers) return
        val previous = settings.seenAlerts(server.id)
        val now = LocalTime.now()
        val minute = now.hour * 60 + now.minute
        // Cheap titles first (remembered or derived from the class name): most checks find nothing new, and then the
        // check is a single `alert.list` call. Class titles are fetched (once per process) only when notifying.
        val known = previous.orEmpty().associate { it.uuid to it.title }
        var result = AlertDiff.compute(previous, alerts, prefs.filter, minute) { known[it.uuid] ?: AlertDiff.title(it, emptyMap()) }
        if (result.toNotify.isNotEmpty()) {
            val titles = classTitles[server.id] ?: api.alertClassTitles().also { if (it.isNotEmpty()) classTitles[server.id] = it }
            if (titles.isNotEmpty()) {
                result = AlertDiff.compute(previous, alerts, prefs.filter, minute) { known[it.uuid] ?: AlertDiff.title(it, titles) }
            }
        }
        if (result.seen != previous) settings.saveSeenAlerts(server.id, result.seen)
        val byUuid = result.seen.associate { it.uuid to it.title }
        // Local snoozes (1.2.0): hold snoozed alerts back, notify again once the snooze ran out (respecting quiet hours).
        val snoozes = settings.snoozes(server.id)
        var toNotify = result.toNotify
        if (snoozes.isNotEmpty()) {
            val nowMs = System.currentTimeMillis()
            val active = alerts.filter { !it.dismissed }.associateBy { it.uuid }
            val plan = Snooze.plan(snoozes, active.keys, nowMs) { uuid ->
                active[uuid]?.let { prefs.filter.allows(AlertLevel.parse(it.level), minute) } == true
            }
            toNotify = toNotify.filter { !Snooze.isSnoozed(snoozes, it.uuid, nowMs) } +
                plan.wake.mapNotNull { active[it] }.filter { w -> toNotify.none { it.uuid == w.uuid } }
            if (plan.next != snoozes) settings.updateSnoozes(server.id) { plan.next }
        }
        notifier.withdraw(server, result.withdrawn)
        notifier.postAlerts(server, toNotify, byUuid)
        notifier.postCleared(server, result.cleared)
        runCatching { checkCertificates(server, api, prefs, minute) }.onFailure { Log.i(TAG, "cert check ${server.name}: ${it.message}") }
        Log.d(TAG, "check ${server.name}: ${alerts.size} alerts, ${toNotify.size} new, baseline=${result.isBaseline}")
    }

    /**
     * Certificate expiry warnings (1.2.0), folded into a check that runs anyway: at most every 12 hours one
     * `certificate.query` on the same connection, no extra wakeups. Expiring certificates wait for the end of quiet hours.
     */
    private suspend fun checkCertificates(server: ServerConfig, api: TrueNasApi, prefs: NotificationPrefs, minute: Int) {
        if (!prefs.certWarnEnabled) return
        val state = settings.certCheck(server.id)
        val nowMs = System.currentTimeMillis()
        if (!CertExpiry.due(state, nowMs)) return
        val warnings = CertExpiry.evaluate(CertificatesApi(api).certificates(), prefs.certWarnDays, nowMs)
        val (fresh, kept) = CertExpiry.diff(warnings, state)
        val quiet = prefs.filter.quietHours.contains(minute)
        val (post, deferred) = fresh.partition { it.expired || !quiet }
        post.forEach { notifier.postCertificate(server, it) }
        val notified = kept + post.associate { it.cert.id.toString() to it.state }
        // Deferred warnings keep the old timestamp so the next check (after quiet hours) posts them.
        settings.saveCertCheck(server.id, CertCheckState(if (deferred.isEmpty()) nowMs else state.lastCheck, notified))
    }

    /** Emits a server id after the user signed in interactively (lets instant mode retry right away). */
    val signedIn = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 4)

    suspend fun onSignInNeeded(server: ServerConfig) {
        // One reminder per expiry; the flag resets after the next successful connection.
        if (settings.setSignInNotified(server.id, true)) notifier.postSignIn(server, apiKey = server.authMethod == AuthMethod.API_KEY)
    }

    suspend fun onConnected(server: ServerConfig) {
        if (settings.setSignInNotified(server.id, false)) notifier.cancelSignIn(server.id)
    }

    /** Called after an interactive sign-in in the app. */
    suspend fun onSignedIn(serverId: String) {
        settings.setSignInNotified(serverId, false)
        notifier.cancelSignIn(serverId)
        signedIn.tryEmit(serverId)
    }

    /** Notification action: dismiss on the NAS without opening the app. */
    suspend fun dismiss(serverId: String, uuid: String): CheckOutcome = withContext(Dispatchers.IO) {
        val server = settings.servers.first().firstOrNull { it.id == serverId } ?: return@withContext CheckOutcome.FAILED
        val borrowed = shared.borrow(resolver.resolve(server))
        var target: ServerConfig? = null
        val api = try {
            borrowed ?: connectVia(server) { target = it }
        } catch (e: TrueNasException.LoginRequired) {
            onSignInNeeded(server)
            return@withContext CheckOutcome.SIGN_IN_NEEDED
        } catch (e: Throwable) {
            return@withContext if (e.isNetwork()) CheckOutcome.NETWORK_ERROR else CheckOutcome.FAILED
        }
        try {
            onConnected(server)
            api.dismissAlert(uuid)
            locks.getOrPut(server.id) { Mutex() }.withLock {
                settings.seenAlerts(server.id)?.let { seen ->
                    settings.saveSeenAlerts(server.id, seen.map { if (it.uuid == uuid) it.copy(dismissed = true) else it })
                }
            }
            notifier.updateSummary(server)
            CheckOutcome.OK
        } catch (e: Throwable) {
            if (e.isNetwork()) CheckOutcome.NETWORK_ERROR else CheckOutcome.FAILED
        } finally {
            if (borrowed == null) {
                api.close()
                resolver.release(target, TunnelHolder.CHECK)
            }
        }
    }

    /**
     * 1.3.0: runs [block] on a connection to [server] for background work (resilver watch): borrows the app's shared
     * connection when it is open, else connects through the right route and closes again afterwards.
     */
    suspend fun <T> withConnection(server: ServerConfig, block: suspend (TrueNasApi) -> T): T = withContext(Dispatchers.IO) {
        val borrowed = shared.borrow(resolver.resolve(server))
        var target: ServerConfig? = null
        val api = borrowed ?: connectVia(server) { target = it }
        try {
            onConnected(server)
            block(api)
        } finally {
            if (borrowed == null) {
                api.close()
                resolver.release(target, TunnelHolder.CHECK)
            }
        }
    }

    /**
     * Connects through the right route for a background check. The tunnel (if that's the route) is held with
     * [TunnelHolder.CHECK] and must be released by the caller with the target passed to [onTarget]. An unreachable
     * local/Tailscale/tunnel route is skipped once, like in the app.
     */
    private suspend fun connectVia(server: ServerConfig, onTarget: (ServerConfig) -> Unit): TrueNasApi {
        var target = resolver.acquire(server, TunnelHolder.CHECK)
        var attempts = 0
        while (true) {
            onTarget(target)
            try {
                return connector.connect(target)
            } catch (e: Throwable) {
                resolver.release(target, TunnelHolder.CHECK)
                onTarget(server.forRoute(app.truenascompanion.data.model.Route.REMOTE))
                val retry = (e is TrueNasException.Unreachable || e is TrueNasException.Timeout) &&
                    target.activeRoute != app.truenascompanion.data.model.Route.REMOTE &&
                    server.routeMode == app.truenascompanion.data.model.RouteMode.AUTO && ++attempts <= 3
                if (!retry) throw e
                resolver.fail(server.id, target.activeRoute)
                target = resolver.acquire(server, TunnelHolder.CHECK)
            }
        }
    }

    private companion object {
        const val TAG = "AlertChecker"
    }
}

internal fun Throwable.isNetwork() =
    this is TrueNasException.Unreachable || this is TrueNasException.Timeout || this is TrueNasException.NotConnected
