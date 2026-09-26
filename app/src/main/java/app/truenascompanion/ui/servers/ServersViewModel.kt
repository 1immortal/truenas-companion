package app.truenascompanion.ui.servers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.truenascompanion.AppContainer
import app.truenascompanion.data.api.Credentials
import app.truenascompanion.data.api.SessionTokens
import app.truenascompanion.data.api.LoginStep
import app.truenascompanion.data.api.PendingOtp
import app.truenascompanion.data.api.TrueNasException
import app.truenascompanion.data.api.WebSocketAuth
import app.truenascompanion.data.model.AuthMethod
import app.truenascompanion.data.repository.sessionTtlSeconds
import app.truenascompanion.data.api.userMessage
import app.truenascompanion.data.model.ServerConfig
import app.truenascompanion.data.model.RouteMode
import app.truenascompanion.data.net.LocalCheck
import app.truenascompanion.data.net.LocalDetector
import app.truenascompanion.data.net.CertificateInfo
import app.truenascompanion.data.repository.TestResult
import app.truenascompanion.util.UrlUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

class ServerListViewModel(private val c: AppContainer) : ViewModel() {
    val servers = c.settings.servers.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val activeId = c.settings.activeServerId.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun select(id: String) = viewModelScope.launch { c.settings.setActiveServer(id) }
    fun delete(id: String) = viewModelScope.launch { c.settings.deleteServer(id) }
}

sealed interface TestOutcome {
    data class Success(val result: TestResult) : TestOutcome
    data class Failure(val message: String) : TestOutcome
}

data class OtpUi(val username: String, val error: String? = null, val busy: Boolean = false)

data class ServerEditState(
    val id: String? = null,
    val name: String = "",
    val url: String = "",
    val authMethod: AuthMethod = AuthMethod.API_KEY,
    val apiKey: String = "",
    val username: String = "",
    val password: String = "",
    val rememberPassword: Boolean = false,
    val sessionDays: Int = 7,
    val forceRest: Boolean = false,
    val pinnedCert: String? = null,
    val hasSavedKey: Boolean = false,
    val hasSavedPassword: Boolean = false,
    val testing: Boolean = false,
    val outcome: TestOutcome? = null,
    val otp: OtpUi? = null,
    val pendingCertificate: CertificateInfo? = null,
    val urlError: String? = null,
    val saved: Boolean = false,
    // --- home network (local address) ---
    val localUrl: String = "",
    val localPinnedCert: String? = null,
    val routeMode: RouteMode = RouteMode.AUTO,
    val localStatus: LocalStatus? = null,
    val detecting: Boolean = false,
    val detected: LocalDetector.Found? = null,
    val pendingLocalCertificate: CertificateInfo? = null,
) {
    val normalizedLocalUrl: String? get() = localUrl.takeIf { it.isNotBlank() }?.let { UrlUtils.normalize(it) }
    val localIsHttp: Boolean get() = normalizedLocalUrl?.startsWith("http://") == true
    /** TrueNAS revokes API keys sent over http, so an http local address is never used with an API key. */
    val localBlocked: Boolean get() = localIsHttp && authMethod == AuthMethod.API_KEY
    val normalizedUrl: String? get() = UrlUtils.normalize(url)
    val isHttp: Boolean get() = normalizedUrl?.startsWith("http://") == true
    val canTest: Boolean
        get() = normalizedUrl != null && when (authMethod) {
            AuthMethod.API_KEY -> apiKey.isNotBlank() || hasSavedKey
            AuthMethod.PASSWORD -> username.isNotBlank() && (password.isNotEmpty() || hasSavedPassword)
        }
    val canSave: Boolean
        get() = normalizedUrl != null && when (authMethod) {
            AuthMethod.API_KEY -> apiKey.isNotBlank() || hasSavedKey
            AuthMethod.PASSWORD -> username.isNotBlank()
        }
}

sealed interface LocalStatus {
    data object Checking : LocalStatus
    /** [sameNas]: true = confirmed same NAS as the remote address, false = a different machine, null = unknown. */
    data class Reachable(val sameNas: Boolean?) : LocalStatus
    data class Failed(val message: String) : LocalStatus
}

class ServerEditViewModel(private val c: AppContainer, serverId: String?) : ViewModel() {
    private val _state = MutableStateFlow(ServerEditState())
    val state: StateFlow<ServerEditState> = _state.asStateFlow()

    private var pendingOtp: PendingOtp? = null
    /** Session token obtained by a successful password test; saved with the server so no second 2FA prompt is needed. */
    private var testedToken: SessionTokens? = null
    private var testedTokenFor: String? = null

    init {
        if (serverId != null) viewModelScope.launch {
            val s = c.settings.servers.first().firstOrNull { it.id == serverId } ?: return@launch
            val hasPw = c.settings.hasPassword(s.id)
            _state.value = ServerEditState(
                id = s.id, name = s.name, url = s.url, username = s.username, forceRest = s.forceRest,
                authMethod = s.authMethod, sessionDays = s.sessionDays,
                pinnedCert = s.pinnedCertSha256, hasSavedKey = c.settings.apiKey(s.id) != null,
                hasSavedPassword = hasPw, rememberPassword = hasPw,
                localUrl = s.localUrl.orEmpty(), localPinnedCert = s.localPinnedCertSha256, routeMode = s.routeMode,
            )
        }
    }

    fun update(transform: (ServerEditState) -> ServerEditState) =
        _state.update { transform(it).copy(outcome = null, urlError = null) }

    private suspend fun keyToUse(s: ServerEditState): String? =
        s.apiKey.trim().ifBlank { null } ?: s.id?.let { c.settings.apiKey(it) }

    private suspend fun passwordToUse(s: ServerEditState): String? =
        s.password.ifEmpty { null } ?: s.id?.let { c.settings.password(it) }

    private fun buildConfig(s: ServerEditState, url: String) = ServerConfig(
        id = s.id ?: UUID.randomUUID().toString(),
        name = s.name.trim().ifBlank { url.substringAfter("://") },
        url = url,
        username = s.username.trim(),
        pinnedCertSha256 = s.pinnedCert,
        forceRest = s.forceRest,
        authMethod = s.authMethod,
        sessionDays = s.sessionDays,
        localUrl = s.normalizedLocalUrl,
        localPinnedCertSha256 = s.localPinnedCert.takeIf { s.normalizedLocalUrl != null },
        routeMode = s.routeMode,
    )

    // --- home network ---

    fun updateLocal(transform: (ServerEditState) -> ServerEditState) =
        _state.update { transform(it).copy(localStatus = null) }

    /** Probes the usual TrueNAS ports on the typed host and offers the one it finds (https preferred). */
    fun detectLocal() {
        val host = LocalDetector.hostOf(_state.value.localUrl) ?: run {
            _state.update { it.copy(localStatus = LocalStatus.Failed("Enter the NAS's IP address or hostname first, e.g. 192.168.1.10")) }
            return
        }
        _state.update { it.copy(detecting = true, localStatus = null, detected = null) }
        viewModelScope.launch {
            val found = runCatching { LocalDetector.detect(host) }.getOrNull()
            _state.update {
                if (found != null) it.copy(detecting = false, detected = found)
                else it.copy(
                    detecting = false,
                    localStatus = LocalStatus.Failed(
                        "No TrueNAS found on $host. Tried https on ports 443, 444, 8443 and 9443 and http on 80, 81, 8080 and 8000. " +
                            "Check that your phone is on the home Wi-Fi, or type the full address.",
                    ),
                )
            }
        }
    }

    fun confirmDetected() {
        val found = _state.value.detected ?: return
        _state.update { it.copy(localUrl = found.url, detected = null, localPinnedCert = null) }
        checkLocal()
    }

    fun dismissDetected() = _state.update { it.copy(detected = null) }

    /** Certificate + same-NAS check of the local address (no credentials are sent). */
    fun checkLocal() {
        val s = _state.value
        val local = s.normalizedLocalUrl ?: return
        val remoteUrl = s.normalizedUrl ?: return
        _state.update { it.copy(localStatus = LocalStatus.Checking) }
        viewModelScope.launch {
            val remote = buildConfig(s, remoteUrl)
            val localCfg = remote.copy(url = local, pinnedCertSha256 = s.localPinnedCert)
            try {
                val same = LocalCheck.check(localCfg, remote)
                c.routes.invalidate(remote.id)
                _state.update { it.copy(localStatus = LocalStatus.Reachable(same)) }
            } catch (e: TrueNasException.UntrustedCertificate) {
                _state.update {
                    it.copy(
                        pendingLocalCertificate = e.certificate,
                        localStatus = LocalStatus.Failed(if (e.certificate != null) "The local address uses a certificate your phone doesn't trust. Review it to continue." else e.userMessage()),
                    )
                }
            } catch (e: Throwable) {
                _state.update { it.copy(localStatus = LocalStatus.Failed(e.userMessage())) }
            }
        }
    }

    fun trustLocalCertificate() {
        val cert = _state.value.pendingLocalCertificate ?: return
        _state.update { it.copy(localPinnedCert = cert.sha256, pendingLocalCertificate = null) }
        checkLocal()
    }

    fun dismissLocalCertificate() = _state.update { it.copy(pendingLocalCertificate = null) }

    fun forgetLocalCertificate() = updateLocal { it.copy(localPinnedCert = null) }

    private fun fingerprint(s: ServerEditState, url: String) = "$url|${s.username.trim()}|${s.pinnedCert}"

    fun test() {
        val s = _state.value
        val url = s.normalizedUrl ?: run { _state.update { it.copy(urlError = "Enter a valid address, e.g. https://truenas.local") }; return }
        if (s.id == null) _state.update { it.copy(id = UUID.randomUUID().toString()) } // stable id for the tested config
        _state.update { it.copy(testing = true, outcome = null) }
        viewModelScope.launch {
            val st = _state.value
            val config = buildConfig(st, url)
            try {
                when (st.authMethod) {
                    AuthMethod.API_KEY -> {
                        val key = keyToUse(st) ?: throw TrueNasException.AuthFailed("Enter an API key.")
                        val result = c.repository.test(config, key)
                        _state.update { it.copy(testing = false, outcome = TestOutcome.Success(result)) }
                    }
                    AuthMethod.PASSWORD -> {
                        val pw = passwordToUse(st) ?: throw TrueNasException.AuthFailed("Enter your password.")
                        handleStep(WebSocketAuth.login(config, Credentials.Password(config.username, pw), config.sessionTtlSeconds()), fingerprint(st, url))
                    }
                }
            } catch (e: Throwable) {
                onTestError(e)
            }
        }
    }

    private suspend fun handleStep(step: LoginStep, fp: String) {
        when (step) {
            is LoginStep.Success -> {
                val info = try { step.api.systemInfo() } finally { step.api.close() }
                testedToken = step.tokens.takeUnless { it.isEmpty }
                testedTokenFor = fp
                pendingOtp = null
                _state.update { it.copy(testing = false, otp = null, outcome = TestOutcome.Success(TestResult(step.api.flavor, info))) }
            }
            is LoginStep.OtpRequired -> {
                pendingOtp = step.pending
                _state.update { it.copy(testing = true, otp = OtpUi(step.username)) }
            }
        }
    }

    fun submitOtp(code: String) {
        val pending = pendingOtp ?: return
        val s = _state.value
        val url = s.normalizedUrl ?: return
        _state.update { it.copy(otp = it.otp?.copy(busy = true, error = null)) }
        viewModelScope.launch {
            try {
                when (val step = pending.submit(code)) {
                    is LoginStep.Success -> handleStep(step, fingerprint(s, url))
                    is LoginStep.OtpRequired -> _state.update {
                        it.copy(otp = it.otp?.copy(busy = false, error = "That code didn't work. Check your authenticator app and try again."))
                    }
                }
            } catch (e: Throwable) {
                pendingOtp = null
                _state.update { it.copy(otp = null) }
                onTestError(e)
            }
        }
    }

    fun cancelOtp() {
        pendingOtp?.cancel()
        pendingOtp = null
        _state.update { it.copy(otp = null, testing = false) }
    }

    private fun onTestError(e: Throwable) {
        when (e) {
            is TrueNasException.UntrustedCertificate -> _state.update {
                it.copy(
                    testing = false,
                    pendingCertificate = e.certificate,
                    outcome = TestOutcome.Failure(
                        if (e.certificate != null) "The server uses a certificate your phone doesn't trust. Review it to continue."
                        else e.userMessage()
                    ),
                )
            }
            else -> _state.update { it.copy(testing = false, outcome = TestOutcome.Failure(e.userMessage())) }
        }
    }

    fun trustPendingCertificate() {
        val cert = _state.value.pendingCertificate ?: return
        _state.update { it.copy(pinnedCert = cert.sha256, pendingCertificate = null) }
        test()
    }

    fun dismissCertificate() = _state.update { it.copy(pendingCertificate = null) }

    fun forgetPinnedCertificate() = update { it.copy(pinnedCert = null) }

    fun save() {
        val s = _state.value
        val url = s.normalizedUrl ?: run { _state.update { it.copy(urlError = "Enter a valid address") }; return }
        viewModelScope.launch {
            val built = buildConfig(s, url)
            val previous = c.settings.servers.first().firstOrNull { it.id == built.id }
            // VPN settings live on their own screen: keep them as they are.
            val config = previous?.let {
                built.copy(
                    tailscaleUrl = it.tailscaleUrl, tailscalePinnedCertSha256 = it.tailscalePinnedCertSha256,
                    vpnMode = it.vpnMode, wireGuardConfigured = it.wireGuardConfigured,
                )
            } ?: built
            c.settings.saveServer(config, if (s.authMethod == AuthMethod.API_KEY) s.apiKey.trim().ifBlank { null } else null)
            if (s.authMethod == AuthMethod.PASSWORD) {
                when {
                    !s.rememberPassword -> c.settings.clearPassword(config.id)
                    s.password.isNotEmpty() -> c.settings.savePassword(config.id, s.password)
                }
                val token = testedToken
                if (token != null && testedTokenFor == fingerprint(s, url)) {
                    c.sessions.replace(config.id, token)
                } else if (previous != null && (previous.url != config.url || previous.username != config.username || previous.authMethod != config.authMethod)) {
                    c.sessions.clear(config.id)
                }
            } else {
                c.sessions.clear(config.id)
                c.settings.clearPassword(config.id)
            }
            c.settings.setActiveServer(config.id)
            c.routes.invalidate(config.id)
            c.repository.disconnect()
            _state.update { it.copy(saved = true) }
        }
    }

    override fun onCleared() {
        pendingOtp?.cancel()
    }
}
