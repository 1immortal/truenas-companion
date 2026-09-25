package app.truenascompanion.notify

import android.util.Log
import app.truenascompanion.data.api.Credentials
import app.truenascompanion.data.api.LoginStep
import app.truenascompanion.data.api.TrueNasApi
import app.truenascompanion.data.api.TrueNasConnector
import app.truenascompanion.data.api.TrueNasException
import app.truenascompanion.data.api.WebSocketAuth
import app.truenascompanion.data.model.AuthMethod
import app.truenascompanion.data.model.ServerConfig
import app.truenascompanion.data.repository.sessionTtlSeconds
import app.truenascompanion.data.store.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.LocalTime
import java.util.concurrent.ConcurrentHashMap

enum class CheckOutcome { OK, SIGN_IN_NEEDED, NETWORK_ERROR, FAILED }

/**
 * Non-interactive connection for background work. Never shows UI: uses the saved session token (which is refreshed
 * with `auth.generate_token` on every successful login, so background checks also keep the session alive), then the
 * remembered password if the user opted in. Throws [TrueNasException.LoginRequired] when a human is needed (2FA code,
 * no credentials, rejected API key).
 */
class BackgroundConnector(private val settings: SettingsStore) {
    suspend fun connect(server: ServerConfig): TrueNasApi = withContext(Dispatchers.IO) {
        when (server.authMethod) {
            AuthMethod.API_KEY -> {
                val key = settings.apiKey(server.id) ?: throw TrueNasException.LoginRequired()
                try {
                    TrueNasConnector.connect(server, key)
                } catch (e: TrueNasException.AuthFailed) {
                    throw TrueNasException.LoginRequired()
                }
            }
            AuthMethod.PASSWORD -> connectPassword(server)
        }
    }

    private suspend fun connectPassword(server: ServerConfig): TrueNasApi {
        val ttl = server.sessionTtlSeconds()
        settings.sessionToken(server.id)?.let { saved ->
            if (!saved.isExpired) {
                try {
                    val step = WebSocketAuth.login(server, Credentials.Token(saved.token), ttl) as LoginStep.Success
                    step.token?.let { settings.saveSessionToken(server.id, it) } // sliding expiry
                    return step.api
                } catch (e: TrueNasException.TokenRejected) {
                    settings.clearSessionToken(server.id)
                }
            } else {
                settings.clearSessionToken(server.id)
            }
        }
        val remembered = settings.password(server.id)
        if (remembered != null && server.username.isNotBlank()) {
            try {
                when (val step = WebSocketAuth.login(server, Credentials.Password(server.username, remembered), ttl)) {
                    is LoginStep.Success -> {
                        step.token?.let { settings.saveSessionToken(server.id, it) }
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
            val conn = try {
                api ?: connector.connect(server)
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
                if (api == null) conn.close()
            }
        }
    }

    private suspend fun process(server: ServerConfig, api: TrueNasApi) {
        val alerts = api.alerts()
        val prefs = settings.notificationPrefs.first()
        if (server.id !in prefs.enabledServers) return
        val titles = classTitles[server.id] ?: api.alertClassTitles().also { if (it.isNotEmpty()) classTitles[server.id] = it }
        val now = LocalTime.now()
        val result = AlertDiff.compute(
            previous = settings.seenAlerts(server.id),
            current = alerts,
            filter = prefs.filter,
            minuteOfDay = now.hour * 60 + now.minute,
            titleOf = { AlertDiff.title(it, titles) },
        )
        settings.saveSeenAlerts(server.id, result.seen)
        val byUuid = result.seen.associate { it.uuid to it.title }
        notifier.withdraw(server, result.withdrawn)
        notifier.postAlerts(server, result.toNotify, byUuid)
        notifier.postCleared(server, result.cleared)
        Log.i(TAG, "check ${server.name}: ${alerts.size} alerts, ${result.toNotify.size} new, baseline=${result.isBaseline}")
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
        val api = try {
            connector.connect(server)
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
            api.close()
        }
    }

    private companion object {
        const val TAG = "AlertChecker"
    }
}

internal fun Throwable.isNetwork() =
    this is TrueNasException.Unreachable || this is TrueNasException.Timeout || this is TrueNasException.NotConnected
