package app.truenascompanion.ui.certs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.truenascompanion.AppContainer
import app.truenascompanion.data.api.CertificatesApi
import app.truenascompanion.data.api.userMessage
import app.truenascompanion.data.model.AcmeAuthenticator
import app.truenascompanion.data.model.AcmeRequest
import app.truenascompanion.data.model.CertImport
import app.truenascompanion.data.model.NasCertificate
import app.truenascompanion.data.model.ServerConfig
import app.truenascompanion.ui.components.UiState
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

data class CertsData(
    val certs: List<NasCertificate>,
    /** Certificate the web UI uses now. */
    val uiCertId: Int?,
    /** Certificates TrueNAS allows for the web UI. */
    val uiChoices: Map<Int, String>,
)

data class AcmeOptions(val directories: Map<String, String>, val authenticators: List<AcmeAuthenticator>)

/** One-shot results the screen reacts to. */
sealed interface CertEvent {
    data class Message(val text: String) : CertEvent
    /** The web UI certificate changed: the phone must review and trust the new certificate within the rollback window. */
    data class ReviewNeeded(val serverId: String, val serverName: String) : CertEvent
}

class CertificatesViewModel(private val c: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow<UiState<CertsData>>(UiState.Loading)
    val state = _state.asStateFlow()
    private val _busy = MutableStateFlow<String?>(null)
    /** What is running ("Requesting certificate…"), or null. */
    val busy = _busy.asStateFlow()
    private val _refreshing = MutableStateFlow(false)
    val refreshing = _refreshing.asStateFlow()
    private val _events = Channel<CertEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()
    private val _acme = MutableStateFlow<UiState<AcmeOptions>?>(null)
    val acme = _acme.asStateFlow()
    private val _csrDomains = MutableStateFlow<Map<Int, List<String>>>(emptyMap())
    val csrDomains = _csrDomains.asStateFlow()
    val warnDays = c.settings.notificationPrefs.map { it.certWarnDays }

    init {
        viewModelScope.launch { c.repository.reloadKey.collect { if (it != null) load() } }
    }

    fun refresh() = viewModelScope.launch { _refreshing.value = true; load(); _refreshing.value = false }

    private suspend fun load() {
        try {
            _state.value = UiState.Success(c.repository.call { api ->
                val a = CertificatesApi(api)
                CertsData(a.certificates(), runCatching { a.uiCertificateId() }.getOrNull(), runCatching { a.uiCertificateChoices() }.getOrDefault(emptyMap()))
            })
        } catch (e: Throwable) {
            if (_state.value !is UiState.Success) _state.value = UiState.Error(e.userMessage(), e) else _events.trySend(CertEvent.Message(e.userMessage()))
        }
    }

    private fun run(progress: String, done: String, block: suspend (CertificatesApi) -> Unit) {
        if (_busy.value != null) return
        _busy.value = progress
        viewModelScope.launch {
            try {
                c.repository.call { block(CertificatesApi(it)) }
                _events.trySend(CertEvent.Message(done))
            } catch (e: Throwable) {
                _events.trySend(CertEvent.Message(e.userMessage()))
            } finally {
                _busy.value = null
                load()
            }
        }
    }

    fun import(input: CertImport) = run("Importing ${input.name}…", "Imported ${input.name}") { it.import(input) }

    fun loadAcmeOptions() {
        if (_acme.value is UiState.Success) return
        _acme.value = UiState.Loading
        viewModelScope.launch {
            _acme.value = try {
                UiState.Success(c.repository.call { api -> val a = CertificatesApi(api); AcmeOptions(a.acmeServers(), a.dnsAuthenticators()) })
            } catch (e: Throwable) {
                UiState.Error(e.userMessage(), e)
            }
        }
    }

    fun loadCsrDomains(csrId: Int) {
        if (csrId in _csrDomains.value) return
        viewModelScope.launch {
            runCatching { c.repository.call { CertificatesApi(it).domainNames(csrId) } }
                .onSuccess { d -> _csrDomains.value = _csrDomains.value + (csrId to d) }
                .onFailure { _events.trySend(CertEvent.Message(it.userMessage())) }
        }
    }

    fun createAcme(req: AcmeRequest) = run("Requesting ${req.name} (this can take a few minutes)…", "Certificate ${req.name} issued") { it.createAcme(req) }

    fun setRenewDays(cert: NasCertificate, days: Int) = run("Saving…", "${cert.name} renews $days days before expiry") { it.setRenewDays(cert.id, days) }

    /**
     * Switches the web UI certificate (TrueNAS rolls back after 10 minutes unless the phone checks in). The saved
     * certificate pins of the active server are cleared and the trust step is required again before reconnecting;
     * after the user trusts the new certificate the app confirms the change with `system.general.checkin`.
     */
    fun useForWebUi(cert: NasCertificate) {
        if (_busy.value != null) return
        _busy.value = "Switching the web UI certificate…"
        viewModelScope.launch {
            try {
                val server = c.repository.activeServer.first { true } ?: return@launch
                c.repository.call { CertificatesApi(it).setUiCertificate(cert.id) }
                c.settings.updateServer(server.id) { afterUiCertChange(it) }
                c.routes.invalidate(server.id)
                c.repository.disconnect()
                _events.trySend(CertEvent.ReviewNeeded(server.id, server.name))
            } catch (e: Throwable) {
                _events.trySend(CertEvent.Message(e.userMessage()))
            } finally {
                _busy.value = null
            }
        }
    }

    companion object {
        /** Forget the pins (they belong to the old certificate) and ask for the trust step again on any HTTPS address. */
        fun afterUiCertChange(s: ServerConfig): ServerConfig {
            val https = s.isHttps || s.isLocalHttps || s.tailscaleUrl?.startsWith("https://", ignoreCase = true) == true
            return s.copy(
                pinnedCertSha256 = null,
                localPinnedCertSha256 = null,
                tailscalePinnedCertSha256 = null,
                certReviewRequired = https,
                uiCertCheckinPending = true,
            )
        }
    }
}
