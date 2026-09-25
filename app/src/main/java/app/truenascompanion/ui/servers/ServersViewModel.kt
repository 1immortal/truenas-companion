package app.truenascompanion.ui.servers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.truenascompanion.AppContainer
import app.truenascompanion.data.api.TrueNasException
import app.truenascompanion.data.api.userMessage
import app.truenascompanion.data.model.ServerConfig
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

data class ServerEditState(
    val id: String? = null,
    val name: String = "",
    val url: String = "",
    val apiKey: String = "",
    val username: String = "",
    val forceRest: Boolean = false,
    val pinnedCert: String? = null,
    val hasSavedKey: Boolean = false,
    val testing: Boolean = false,
    val outcome: TestOutcome? = null,
    val pendingCertificate: CertificateInfo? = null,
    val urlError: String? = null,
    val saved: Boolean = false,
) {
    val normalizedUrl: String? get() = UrlUtils.normalize(url)
    val isHttp: Boolean get() = normalizedUrl?.startsWith("http://") == true
    val canSubmit: Boolean get() = normalizedUrl != null && (apiKey.isNotBlank() || hasSavedKey)
}

class ServerEditViewModel(private val c: AppContainer, serverId: String?) : ViewModel() {
    private val _state = MutableStateFlow(ServerEditState())
    val state: StateFlow<ServerEditState> = _state.asStateFlow()

    init {
        if (serverId != null) viewModelScope.launch {
            val s = c.settings.servers.first().firstOrNull { it.id == serverId } ?: return@launch
            _state.value = ServerEditState(
                id = s.id, name = s.name, url = s.url, username = s.username, forceRest = s.forceRest,
                pinnedCert = s.pinnedCertSha256, hasSavedKey = c.settings.apiKey(s.id) != null,
            )
        }
    }

    fun update(transform: (ServerEditState) -> ServerEditState) =
        _state.update { transform(it).copy(outcome = null, urlError = null) }

    private suspend fun keyToUse(s: ServerEditState): String? =
        s.apiKey.trim().ifBlank { null } ?: s.id?.let { c.settings.apiKey(it) }

    private fun buildConfig(s: ServerEditState, url: String) = ServerConfig(
        id = s.id ?: UUID.randomUUID().toString(),
        name = s.name.trim().ifBlank { url.substringAfter("://") },
        url = url,
        username = s.username.trim(),
        pinnedCertSha256 = s.pinnedCert,
        forceRest = s.forceRest,
    )

    fun test() {
        val s = _state.value
        val url = s.normalizedUrl ?: run { _state.update { it.copy(urlError = "Enter a valid address, e.g. https://truenas.local") }; return }
        _state.update { it.copy(testing = true, outcome = null) }
        viewModelScope.launch {
            val key = keyToUse(s)
            if (key == null) {
                _state.update { it.copy(testing = false, outcome = TestOutcome.Failure("Enter an API key.")) }
                return@launch
            }
            try {
                val result = c.repository.test(buildConfig(s, url), key)
                _state.update { it.copy(testing = false, outcome = TestOutcome.Success(result)) }
            } catch (e: TrueNasException.UntrustedCertificate) {
                _state.update {
                    it.copy(
                        testing = false,
                        pendingCertificate = e.certificate,
                        outcome = TestOutcome.Failure(
                            if (e.certificate != null) "The server uses a certificate your phone doesn't trust. Review it to continue."
                            else e.userMessage()
                        ),
                    )
                }
            } catch (e: Throwable) {
                _state.update { it.copy(testing = false, outcome = TestOutcome.Failure(e.userMessage())) }
            }
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
            val config = buildConfig(s, url)
            c.settings.saveServer(config, s.apiKey.trim().ifBlank { null })
            c.settings.setActiveServer(config.id)
            c.repository.disconnect()
            _state.update { it.copy(saved = true) }
        }
    }
}
