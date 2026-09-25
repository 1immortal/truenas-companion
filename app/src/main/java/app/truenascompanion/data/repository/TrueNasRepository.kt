package app.truenascompanion.data.repository

import app.truenascompanion.data.api.Credentials
import app.truenascompanion.data.api.LoginStep
import app.truenascompanion.data.api.PendingOtp
import app.truenascompanion.data.api.TrueNasApi
import app.truenascompanion.data.api.TrueNasConnector
import app.truenascompanion.data.api.TrueNasException
import app.truenascompanion.data.api.WebSocketAuth
import app.truenascompanion.data.api.SharedConnections
import app.truenascompanion.data.api.userMessage
import app.truenascompanion.data.model.ApiFlavor
import app.truenascompanion.data.model.AuthMethod
import app.truenascompanion.data.model.RealtimeStats
import app.truenascompanion.data.model.JobInfo
import app.truenascompanion.data.model.ServerConfig
import app.truenascompanion.data.model.SystemInfo
import app.truenascompanion.data.store.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

sealed interface ConnectionState {
    data object NoServer : ConnectionState
    data object Idle : ConnectionState
    data object Connecting : ConnectionState
    data class Connected(val flavor: ApiFlavor, val info: SystemInfo?) : ConnectionState
    data class Failed(val message: String, val error: Throwable) : ConnectionState
}

data class TestResult(val flavor: ApiFlavor, val info: SystemInfo)

/** Interactive sign-in requested by the repository; rendered as a dialog on top of the app. */
sealed interface AuthPrompt {
    val server: ServerConfig
    val error: String?
    val busy: Boolean

    data class Password(
        override val server: ServerConfig,
        val rememberDefault: Boolean,
        override val error: String? = null,
        override val busy: Boolean = false,
    ) : AuthPrompt

    data class Otp(
        override val server: ServerConfig,
        val username: String,
        override val error: String? = null,
        override val busy: Boolean = false,
    ) : AuthPrompt
}

fun ServerConfig.sessionTtlSeconds(): Long = sessionDays.coerceIn(1, 90) * 24L * 3600L

/**
 * Owns the connection to the active server and hands out a live [TrueNasApi].
 * Reconnects lazily when the WebSocket dropped (phone slept, Wi-Fi changed, ...).
 *
 * Password sign-in: after the first password (+2FA) login a reusable session token is stored (encrypted) and used for
 * reconnects. When it is missing/expired, the saved password (if the user opted in) is tried; otherwise, or when a 2FA
 * code is needed, an [AuthPrompt] is published and calls fail with [TrueNasException.LoginRequired] until the user signs in.
 */
class TrueNasRepository(
    private val settings: SettingsStore,
    private val scope: CoroutineScope,
    /** Called after every successful interactive sign-in (clears the "sign in to keep receiving alerts" reminder). */
    private val onSignedIn: suspend (serverId: String) -> Unit = {},
    private val shared: SharedConnections = SharedConnections(),
    /** Local vs remote address per network (see [RouteResolver]). */
    private val resolver: app.truenascompanion.data.net.RouteResolver = app.truenascompanion.data.net.RouteResolver(null),
) {

    val activeServer: StateFlow<ServerConfig?> = combine(settings.servers, settings.activeServerId) { servers, id ->
        servers.firstOrNull { it.id == id } ?: servers.firstOrNull()
    }.distinctUntilChanged().stateIn(scope, SharingStarted.Eagerly, null)

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _prompt = MutableStateFlow<AuthPrompt?>(null)
    val prompt: StateFlow<AuthPrompt?> = _prompt.asStateFlow()

    /** Incremented after every interactive sign-in so screens can reload. */
    private val sessionEpoch = MutableStateFlow(0)

    /** Emits a new value whenever screens should (re)load data: server switched or user just signed in. */
    val reloadKey: Flow<String?> = combine(activeServer.map { it?.id }.distinctUntilChanged(), sessionEpoch) { id, epoch ->
        id?.let { "$it#$epoch" }
    }.distinctUntilChanged()

    private val mutex = Mutex()
    private var api: TrueNasApi? = null
    /** The resolved configuration (local or remote address) the connection was opened with. */
    private var apiFor: ServerConfig? = null
    /** The stored configuration behind [apiFor], to notice when the user edits the server. */
    private var apiSource: ServerConfig? = null

    private val _route = MutableStateFlow<app.truenascompanion.data.model.Route?>(null)
    /** Address in use for the active server; null when it has no local address configured. */
    val route: StateFlow<app.truenascompanion.data.model.Route?> = _route.asStateFlow()
    /** True when [api] belongs to someone else (the instant-alerts service): never close it, just drop it. */
    private var borrowed = false

    /** Calls in flight; the background auto-disconnect waits for them (e.g. a long image pull job). */
    private val activeCalls = java.util.concurrent.atomic.AtomicInteger(0)
    private var backgroundJob: kotlinx.coroutines.Job? = null

    /** Must hold [mutex]. Closes our own connection, or just forgets a borrowed one. */
    private fun releaseApi() {
        val a = api ?: return
        val s = apiFor
        if (!borrowed) {
            s?.let { shared.withdraw(it.id, a) }
            a.close()
        }
        api = null
        apiFor = null
        apiSource = null
        borrowed = false
    }

    private var pendingOtp: PendingOtp? = null
    private var pendingPassword: String? = null
    private var pendingRemember: Boolean? = null

    init {
        activeServer.onEach { server ->
            mutex.withLock {
                if (server != apiSource) releaseApi()
            }
            _route.value = null
            if (_prompt.value != null && _prompt.value?.server?.id != server?.id) cancelPrompt()
            _state.value = if (server == null) ConnectionState.NoServer else ConnectionState.Idle
        }.launchIn(scope)
        // Network changed (e.g. left home Wi-Fi): if the right address is now a different one, drop the socket.
        // Open screens reconnect through the new address via their reconnect loops; nothing polls.
        resolver.networkChanges.onEach {
            val raw = activeServer.value ?: return@onEach
            if (!raw.hasLocal || apiFor == null) return@onEach
            val target = resolver.resolve(raw)
            mutex.withLock { if (apiFor != null && apiSource == raw && apiFor != target) releaseApi() }
        }.launchIn(scope)
    }

    suspend fun api(): TrueNasApi = withContext(Dispatchers.IO) {
        mutex.withLock {
            val raw = activeServer.value ?: throw TrueNasException.NoServer()
            val server = resolver.resolve(raw)
            api?.takeIf { it.isAlive && apiFor == server }?.let { return@withLock it }
            releaseApi()
            // Instant alerts already hold a signed-in socket to this server: reuse it instead of opening a second one.
            shared.borrow(server, excludeOwner = SharedConnections.OWNER_APP)?.let { live ->
                install(server, live)
                borrowed = true
                return@withLock live
            }
            _state.value = ConnectionState.Connecting
            suspend fun open(target: ServerConfig): TrueNasApi = when (target.authMethod) {
                AuthMethod.API_KEY -> {
                    val key = settings.apiKey(target.id) ?: throw TrueNasException.AuthFailed("No API key saved for this server.")
                    TrueNasConnector.connect(target, key)
                }
                AuthMethod.PASSWORD -> connectWithSession(target)
            }
            try {
                var target = server
                val newApi = try {
                    open(target)
                } catch (e: TrueNasException) {
                    // The local address answered the probe but not the connection (e.g. just left Wi-Fi): in Auto mode
                    // fall back to the remote address right away instead of failing.
                    val unreachable = e is TrueNasException.Unreachable || e is TrueNasException.Timeout
                    if (!unreachable || target.activeRoute != app.truenascompanion.data.model.Route.LOCAL ||
                        raw.routeMode != app.truenascompanion.data.model.RouteMode.AUTO) throw e
                    resolver.invalidate(raw.id)
                    target = raw.forRoute(app.truenascompanion.data.model.Route.REMOTE)
                    open(target)
                }
                install(target, newApi)
                shared.publish(target, newApi, SharedConnections.OWNER_APP)
                newApi
            } catch (e: Throwable) {
                _state.value = ConnectionState.Failed(e.userMessage(), e)
                throw e
            }
        }
    }

    private suspend fun install(server: ServerConfig, newApi: TrueNasApi) {
        val info = runCatching { newApi.systemInfo() }.getOrNull()
        api = newApi
        apiFor = server
        apiSource = activeServer.value?.takeIf { it.id == server.id }
        _route.value = server.activeRoute.takeIf { apiSource?.hasLocal == true }
        borrowed = false
        _state.value = ConnectionState.Connected(newApi.flavor, info)
    }

    /** Non-interactive part of password sign-in: session token, then remembered password. */
    private suspend fun connectWithSession(server: ServerConfig): TrueNasApi {
        if (_prompt.value?.server?.id == server.id) throw TrueNasException.LoginRequired()
        val ttl = server.sessionTtlSeconds()

        settings.sessionToken(server.id)?.let { saved ->
            if (saved.isExpired) {
                settings.clearSessionToken(server.id)
            } else {
                try {
                    val step = WebSocketAuth.login(server, Credentials.Token(saved.token), ttl) as LoginStep.Success
                    step.token?.let { settings.saveSessionToken(server.id, it) } // sliding expiry
                    return step.api
                } catch (e: TrueNasException.TokenRejected) {
                    settings.clearSessionToken(server.id) // expired, server rebooted, or revoked -> fall through
                }
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
                        pendingOtp?.cancel()
                        pendingOtp = step.pending
                        pendingPassword = null
                        pendingRemember = null
                        _prompt.value = AuthPrompt.Otp(server, step.username)
                        throw TrueNasException.LoginRequired()
                    }
                }
            } catch (e: TrueNasException.PasswordRejected) {
                settings.clearPassword(server.id)
                _prompt.value = AuthPrompt.Password(server, rememberDefault = true, error = "The saved password was rejected. Please sign in again.")
                throw TrueNasException.LoginRequired()
            }
        }

        _prompt.value = AuthPrompt.Password(server, rememberDefault = false)
        throw TrueNasException.LoginRequired()
    }

    /** Re-opens the sign-in dialog (e.g. from a "Sign in" button after the user cancelled it). */
    suspend fun requestSignIn() {
        val server = activeServer.value ?: return
        if (server.authMethod != AuthMethod.PASSWORD || _prompt.value != null) return
        runCatching { api() }
    }

    suspend fun submitPassword(password: String, remember: Boolean) = withContext(Dispatchers.IO) {
        val p = _prompt.value as? AuthPrompt.Password ?: return@withContext
        val server = p.server
        _prompt.value = p.copy(busy = true, error = null)
        try {
            when (val step = WebSocketAuth.login(server, Credentials.Password(server.username, password), server.sessionTtlSeconds())) {
                is LoginStep.Success -> finish(server, step, password, remember)
                is LoginStep.OtpRequired -> {
                    pendingOtp?.cancel()
                    pendingOtp = step.pending
                    pendingPassword = password
                    pendingRemember = remember
                    _prompt.value = AuthPrompt.Otp(server, step.username)
                }
            }
        } catch (e: Throwable) {
            _prompt.value = p.copy(busy = false, error = e.userMessage())
        }
    }

    suspend fun submitOtp(code: String) = withContext(Dispatchers.IO) {
        val p = _prompt.value as? AuthPrompt.Otp ?: return@withContext
        val server = p.server
        _prompt.value = p.copy(busy = true, error = null)
        try {
            var pending = pendingOtp
            if (pending == null || !pending.isAlive) {
                // The half-open connection timed out while the user was fetching the code: redo the password step.
                val pw = pendingPassword ?: settings.password(server.id)
                if (pw == null) {
                    _prompt.value = AuthPrompt.Password(server, rememberDefault = false, error = "The sign-in timed out. Please enter your password again.")
                    return@withContext
                }
                when (val step = WebSocketAuth.login(server, Credentials.Password(server.username, pw), server.sessionTtlSeconds())) {
                    is LoginStep.Success -> { finish(server, step, pendingPassword, pendingRemember ?: false); return@withContext }
                    is LoginStep.OtpRequired -> { pending = step.pending; pendingOtp = pending }
                }
            }
            when (val step = pending.submit(code)) {
                is LoginStep.Success -> finish(server, step, pendingPassword, pendingRemember ?: false)
                is LoginStep.OtpRequired -> _prompt.value = p.copy(busy = false, error = "That code didn't work. Check your authenticator app and try again.")
            }
        } catch (e: TrueNasException.OtpLockout) {
            pendingOtp = null
            _prompt.value = AuthPrompt.Password(server, rememberDefault = pendingRemember ?: false, error = e.userMessage())
        } catch (e: Throwable) {
            _prompt.value = p.copy(busy = false, error = e.userMessage())
        }
    }

    fun cancelPrompt() {
        pendingOtp?.cancel()
        pendingOtp = null
        pendingPassword = null
        pendingRemember = null
        if (_prompt.value != null) {
            _prompt.value = null
            _state.value = ConnectionState.Failed("Sign-in required", TrueNasException.LoginRequired())
        }
    }

    /** [password] is null when it came from storage (nothing to change); otherwise it is saved or forgotten per [remember]. */
    private suspend fun finish(server: ServerConfig, step: LoginStep.Success, password: String?, remember: Boolean) {
        step.token?.let { settings.saveSessionToken(server.id, it) }
        if (password != null) {
            if (remember) settings.savePassword(server.id, password) else settings.clearPassword(server.id)
        }
        mutex.withLock {
            releaseApi()
            install(server, step.api)
            shared.publish(server, step.api, SharedConnections.OWNER_APP)
        }
        pendingOtp = null
        pendingPassword = null
        pendingRemember = null
        _prompt.value = null
        sessionEpoch.value++
        runCatching { onSignedIn(server.id) }
    }

    /** Runs [block] against the API, retrying once with a fresh connection if the socket had dropped. */
    suspend fun <T> call(block: suspend (TrueNasApi) -> T): T = withContext(Dispatchers.IO) {
        activeCalls.incrementAndGet()
        try {
            try {
                block(api())
            } catch (e: TrueNasException.NotConnected) {
                block(api())
            }
        } finally {
            activeCalls.decrementAndGet()
        }
    }

    /** Live stats (WebSocket only). Re-subscribes after connection loss with exponential backoff. */
    fun realtime(): Flow<RealtimeStats> = reconnecting { a -> if (a.supportsRealtime) a.realtimeStats() else null }

    /** Live middleware jobs; reconnects after connection loss. */
    fun jobs(): Flow<List<JobInfo>> = reconnecting { a -> a.jobs() }

    /**
     * Collects [open]'s flow and transparently reconnects when the socket drops. The backoff (2 s … 60 s) resets as
     * soon as data flows again, so a long session with the odd Wi-Fi hiccup doesn't end up waiting a minute.
     * [open] returning null means "not supported here": the flow just completes.
     */
    private fun <T> reconnecting(open: (TrueNasApi) -> Flow<T>?): Flow<T> = flow {
        var attempt = 0L
        while (true) {
            try {
                val source = open(api()) ?: return@flow
                source.collect { attempt = 0; emit(it) }
                throw TrueNasException.NotConnected() // subscription ended -> reconnect
            } catch (e: TrueNasException) {
                val retry = e is TrueNasException.NotConnected || e is TrueNasException.Timeout || e is TrueNasException.Unreachable
                if (!retry) throw e
            }
            delay(reconnectDelayMs(attempt++))
        }
    }.flowOn(Dispatchers.IO)

    /**
     * App visibility (MainActivity onStart/onStop). The foreground socket pings the server every 30 s, which keeps
     * the phone's radio busy; once the app has been in the background for [BACKGROUND_GRACE_MS] and nothing is in
     * flight, it is closed. The next screen that needs data reconnects (with the saved session) transparently.
     */
    fun setForeground(foreground: Boolean) {
        backgroundJob?.cancel()
        backgroundJob = null
        if (foreground) return
        backgroundJob = scope.launch {
            delay(BACKGROUND_GRACE_MS)
            while (activeCalls.get() > 0) delay(BACKGROUND_GRACE_MS) // e.g. an image pull job is still being awaited
            mutex.withLock { releaseApi() }
        }
    }

    companion object {
        const val BACKGROUND_GRACE_MS = 30_000L

        /** 2 s, 4 s, 8 s … capped at 60 s: a dead server doesn't get hammered while a screen is open. */
        fun reconnectDelayMs(attempt: Long): Long = (2_000L shl attempt.coerceIn(0, 5).toInt()).coerceAtMost(60_000L)
    }

    /** Drops the current connection; the next call reconnects with the latest settings/credentials. */
    suspend fun disconnect() {
        mutex.withLock { releaseApi() }
        _state.value = if (activeServer.value == null) ConnectionState.NoServer else ConnectionState.Idle
    }

    /** One-off API-key connection used by the setup screen; never touches the active connection. */
    suspend fun test(server: ServerConfig, apiKey: String): TestResult = withContext(Dispatchers.IO) {
        val a = TrueNasConnector.connect(server, apiKey)
        try {
            TestResult(a.flavor, a.systemInfo())
        } finally {
            a.close()
        }
    }
}
