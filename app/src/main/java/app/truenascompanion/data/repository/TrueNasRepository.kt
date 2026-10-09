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
import app.truenascompanion.data.net.Keepalive
import app.truenascompanion.data.model.ApiFlavor
import app.truenascompanion.data.model.AuthMethod
import app.truenascompanion.data.model.RealtimeStats
import app.truenascompanion.data.model.JobInfo
import app.truenascompanion.data.model.ServerConfig
import app.truenascompanion.data.model.SystemInfo
import app.truenascompanion.data.store.SettingsStore
import app.truenascompanion.data.vpn.TunnelHolder
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

/** 1.7.1 (security M-5): at most 30 days (the longest choice in the app; TrueNAS also caps the original login at 30). */
fun ServerConfig.sessionTtlSeconds(): Long = sessionDays.coerceIn(1, 30) * 24L * 3600L

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
    /** Shared with background work so token use and rotation never race (see [SessionTokenManager]). */
    private val sessions: app.truenascompanion.data.api.SessionTokenManager = app.truenascompanion.data.api.SessionTokenManager(settings.tokenStore),
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
    private suspend fun releaseApi() {
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
        // Our connection went through the built-in tunnel: let it go down (unless instant alerts still hold it).
        tunnelTarget?.let { resolver.release(it, TunnelHolder.APP) }
        tunnelTarget = null
    }

    /** The resolved config we acquired the tunnel for (VPN route), released with the connection. */
    private var tunnelTarget: ServerConfig? = null
    /** Tunnel kept up while the sign-in dialog is open for a VPN-route server; handed over on success. */
    @Volatile private var promptTunnel: ServerConfig? = null

    private suspend fun dropPromptTunnel() {
        promptTunnel?.let { resolver.release(it, TunnelHolder.APP) }
        promptTunnel = null
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
            if (!raw.hasAlternativeRoutes || apiFor == null) return@onEach
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
                // Brings the WireGuard tunnel up when that's the route (falls back to the next route if it can't).
                var target = resolver.acquire(raw, TunnelHolder.APP)
                var newApi: TrueNasApi? = null
                var attempts = 0
                /** A non-remote route that needs the password: asked there if the remote address doesn't answer either. */
                var promptOn: ServerConfig? = null
                while (newApi == null) {
                    try {
                        newApi = open(target)
                    } catch (e: TrueNasException) {
                        if (e is TrueNasException.SessionNotOnThisRoute && promptOn == null &&
                            target.activeRoute != app.truenascompanion.data.model.Route.VPN) promptOn = target
                        val p = promptOn
                        if (p != null && target.activeRoute == app.truenascompanion.data.model.Route.REMOTE &&
                            (e is TrueNasException.Unreachable || e is TrueNasException.Timeout)) {
                            resolver.release(target, TunnelHolder.APP)
                            _prompt.value = AuthPrompt.Password(p, rememberDefault = false)
                            throw TrueNasException.LoginRequired()
                        }
                        if (e is TrueNasException.LoginRequired && target.activeRoute == app.truenascompanion.data.model.Route.VPN) {
                            // The sign-in dialog will talk to the NAS through the tunnel: keep it up for the prompt.
                            if (promptTunnel != null && promptTunnel != target) resolver.release(promptTunnel, TunnelHolder.APP)
                            promptTunnel = target
                            throw e
                        }
                        // The local address / Tailscale / tunnel answered the decision but not the connection (e.g. just
                        // left Wi-Fi): in Auto mode move on to the next route right away instead of failing.
                        resolver.release(target, TunnelHolder.APP)
                        val unreachable = e is TrueNasException.Unreachable || e is TrueNasException.Timeout ||
                            e is TrueNasException.SessionNotOnThisRoute
                        if (!unreachable || target.activeRoute == app.truenascompanion.data.model.Route.REMOTE ||
                            raw.routeMode != app.truenascompanion.data.model.RouteMode.AUTO || ++attempts > 3) throw e
                        resolver.fail(raw.id, target.activeRoute)
                        target = resolver.acquire(raw, TunnelHolder.APP)
                    }
                }
                if (target.activeRoute == app.truenascompanion.data.model.Route.VPN) tunnelTarget = target
                if (target.activeRoute == app.truenascompanion.data.model.Route.VPN || target.activeRoute == app.truenascompanion.data.model.Route.TAILSCALE) {
                    runCatching { settings.markVpnWorked(raw.id) }
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
        _route.value = server.activeRoute.takeIf { apiSource?.hasAlternativeRoutes == true }
        borrowed = false
        _state.value = ConnectionState.Connected(newApi.flavor, info)
    }

    /** Non-interactive part of password sign-in: session token, then remembered password. */
    private suspend fun connectWithSession(server: ServerConfig): TrueNasApi {
        if (_prompt.value?.server?.id == server.id) throw TrueNasException.LoginRequired()
        val ttl = server.sessionTtlSeconds()

        // Saved token (rotated on every use; spare as fallback). Network errors propagate and keep the tokens.
        sessions.connect(server.id, ttl) { token -> WebSocketAuth.tokenConnection(server, token, Keepalive.FOREGROUND) }
            ?.let { return it }

        // 1.7.1 (security C-1): the remembered password is only ever sent to the remote address. On the local /
        // Tailscale / VPN address, Auto mode moves on to the next route; the fixed modes ask the user.
        val nonRemote = server.activeRoute != app.truenascompanion.data.model.Route.REMOTE
        if (nonRemote && server.routeMode == app.truenascompanion.data.model.RouteMode.AUTO) throw TrueNasException.SessionNotOnThisRoute()
        val remembered = if (nonRemote) null else settings.password(server.id)
        if (remembered != null && server.username.isNotBlank()) {
            try {
                when (val step = WebSocketAuth.login(server, Credentials.Password(server.username, remembered), ttl)) {
                    is LoginStep.Success -> {
                        if (step.token != null) sessions.replace(server.id, step.tokens)
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
        scope.launch { dropPromptTunnel() }
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
        if (step.token != null) sessions.replace(server.id, step.tokens)
        if (password != null) {
            if (remember) settings.savePassword(server.id, password) else settings.clearPassword(server.id)
        }
        mutex.withLock {
            releaseApi()
            install(server, step.api)
            if (promptTunnel != null && promptTunnel == server) { tunnelTarget = server; promptTunnel = null }
            shared.publish(server, step.api, SharedConnections.OWNER_APP)
        }
        dropPromptTunnel()
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

    /**
     * 1.3.0 file transfers: runs [block] with the connected API and the resolved address (route, certificate pin) so
     * an HTTP transfer goes to the same place as the WebSocket. Counts as an active call, so the 30 s background
     * disconnect waits until the transfer is done.
     */
    suspend fun <T> withEndpoint(block: suspend (TrueNasApi, ServerConfig) -> T): T = withContext(Dispatchers.IO) {
        activeCalls.incrementAndGet()
        try {
            val (a, target) = try { shellEndpoint() } catch (e: TrueNasException.NotConnected) { shellEndpoint() }
            block(a, target)
        } finally {
            activeCalls.decrementAndGet()
        }
    }

    /** Live stats (WebSocket only). Re-subscribes after connection loss with exponential backoff. */
    fun realtime(): Flow<RealtimeStats> = reconnecting { a -> if (a.supportsRealtime) a.realtimeStats() else null }

    /** Per-app CPU / memory / network (`app.stats`), only while a screen collects it; reconnects after loss. */
    fun appStats(): Flow<List<app.truenascompanion.data.model.AppStats>> = reconnecting { a -> a.appStats(3) }

    /**
     * Follows a container's log. Not auto-reconnected: a reconnect would replay the tail and duplicate lines, so the
     * logs screen offers "Resume" instead.
     */
    fun appLogs(appName: String, containerId: String, tail: Int = 500): Flow<app.truenascompanion.data.model.LogLine> = flow {
        emitAll(api().appLogs(appName, containerId, tail))
    }.flowOn(Dispatchers.IO)

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
    private val _foreground = MutableStateFlow(true)
    /** App visible (for the web shell, which follows the same 30 s background rule). */
    val foreground: StateFlow<Boolean> = _foreground.asStateFlow()

    /**
     * The signed-in API and the resolved address it uses (local / Tailscale / VPN / remote, with that route's pinned
     * certificate). The web shell opens its own socket to the same address, so the one-time token (which is bound to
     * the caller's address) and the certificate pin match the API connection.
     */
    suspend fun shellEndpoint(): Pair<TrueNasApi, ServerConfig> {
        val a = api()
        val target = mutex.withLock { apiFor?.takeIf { api === a } } ?: throw TrueNasException.NotConnected()
        return a to target
    }

    fun setForeground(foreground: Boolean) {
        _foreground.value = foreground
        backgroundJob?.cancel()
        backgroundJob = null
        if (foreground) return
        backgroundJob = scope.launch {
            delay(BACKGROUND_GRACE_MS)
            while (activeCalls.get() > 0) delay(BACKGROUND_GRACE_MS) // e.g. an image pull job is still being awaited
            mutex.withLock { releaseApi() }
            if (_prompt.value == null) dropPromptTunnel()
        }
    }

    companion object {
        const val BACKGROUND_GRACE_MS = 30_000L
        const val CATALOG_TTL_MS = 10 * 60_000L

        /** 2 s, 4 s, 8 s … capped at 60 s: a dead server doesn't get hammered while a screen is open. */
        fun reconnectDelayMs(attempt: Long): Long = (2_000L shl attempt.coerceIn(0, 5).toInt()).coerceAtMost(60_000L)
    }

    /** Base URL of the address currently in use (local or remote), for rewriting app portal links. */
    val connectedBaseUrl: String? get() = apiFor?.url ?: activeServer.value?.url

    private var catalogCache: Triple<String, Long, List<app.truenascompanion.data.model.CatalogApp>>? = null

    /** Catalog apps, cached for 10 minutes per server (one `app.available` call instead of one per visit). */
    suspend fun catalogApps(force: Boolean = false): List<app.truenascompanion.data.model.CatalogApp> {
        val id = activeServer.value?.id ?: throw TrueNasException.NoServer()
        catalogCache?.let { (sid, at, list) ->
            if (!force && sid == id && System.currentTimeMillis() - at < CATALOG_TTL_MS) return list
        }
        return call { it.catalogApps() }.also { catalogCache = Triple(id, System.currentTimeMillis(), it) }
    }

    /** Drops the current connection; the next call reconnects with the latest settings/credentials. */
    suspend fun disconnect() {
        mutex.withLock { releaseApi() }
        _state.value = if (activeServer.value == null) ConnectionState.NoServer else ConnectionState.Idle
    }

    /**
     * Clears a failed/idle socket and bumps [reloadKey] so open screens reconnect.
     * Used by the connection-failure overlay’s “Try again” action.
     */
    fun retryConnection() {
        scope.launch {
            disconnect()
            sessionEpoch.value++
        }
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
