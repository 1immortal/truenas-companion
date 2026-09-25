package app.truenascompanion.data.repository

import app.truenascompanion.data.api.Credentials
import app.truenascompanion.data.api.LoginStep
import app.truenascompanion.data.api.PendingOtp
import app.truenascompanion.data.api.TrueNasApi
import app.truenascompanion.data.api.TrueNasConnector
import app.truenascompanion.data.api.TrueNasException
import app.truenascompanion.data.api.WebSocketAuth
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
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

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
class TrueNasRepository(private val settings: SettingsStore, private val scope: CoroutineScope) {

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
    private var apiFor: ServerConfig? = null

    private var pendingOtp: PendingOtp? = null
    private var pendingPassword: String? = null
    private var pendingRemember: Boolean? = null

    init {
        activeServer.onEach { server ->
            mutex.withLock {
                if (server != apiFor) {
                    api?.close()
                    api = null
                    apiFor = null
                }
            }
            if (_prompt.value != null && _prompt.value?.server?.id != server?.id) cancelPrompt()
            _state.value = if (server == null) ConnectionState.NoServer else ConnectionState.Idle
        }.launchIn(scope)
    }

    suspend fun api(): TrueNasApi = withContext(Dispatchers.IO) {
        mutex.withLock {
            val server = activeServer.value ?: throw TrueNasException.NoServer()
            api?.takeIf { it.isAlive && apiFor == server }?.let { return@withLock it }
            api?.close()
            api = null
            _state.value = ConnectionState.Connecting
            try {
                val newApi = when (server.authMethod) {
                    AuthMethod.API_KEY -> {
                        val key = settings.apiKey(server.id) ?: throw TrueNasException.AuthFailed("No API key saved for this server.")
                        TrueNasConnector.connect(server, key)
                    }
                    AuthMethod.PASSWORD -> connectWithSession(server)
                }
                install(server, newApi)
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
            api?.close()
            install(server, step.api)
        }
        pendingOtp = null
        pendingPassword = null
        pendingRemember = null
        _prompt.value = null
        sessionEpoch.value++
    }

    /** Runs [block] against the API, retrying once with a fresh connection if the socket had dropped. */
    suspend fun <T> call(block: suspend (TrueNasApi) -> T): T = withContext(Dispatchers.IO) {
        try {
            block(api())
        } catch (e: TrueNasException.NotConnected) {
            block(api())
        }
    }

    /** Live stats (WebSocket only). Re-subscribes after connection loss with a small backoff. */
    fun realtime(): Flow<RealtimeStats> = flow {
        val a = api()
        if (!a.supportsRealtime) return@flow
        emitAll(a.realtimeStats())
        throw TrueNasException.NotConnected() // subscription ended -> retry
    }.retryWhen { cause, attempt ->
        val retry = cause is TrueNasException.NotConnected || cause is TrueNasException.Timeout || cause is TrueNasException.Unreachable
        if (retry) delay((2000L * (attempt + 1)).coerceAtMost(15_000))
        retry
    }.flowOn(Dispatchers.IO)

    /** Live middleware jobs; reconnects after connection loss. */
    fun jobs(): Flow<List<JobInfo>> = flow {
        emitAll(api().jobs())
        throw TrueNasException.NotConnected()
    }.retryWhen { cause, attempt ->
        val retry = cause is TrueNasException.NotConnected || cause is TrueNasException.Timeout || cause is TrueNasException.Unreachable
        if (retry) delay((2000L * (attempt + 1)).coerceAtMost(15_000))
        retry
    }.flowOn(Dispatchers.IO)

    /** Drops the current connection; the next call reconnects with the latest settings/credentials. */
    suspend fun disconnect() {
        mutex.withLock {
            api?.close()
            api = null
            apiFor = null
        }
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
