package app.truenascompanion.ui.network

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.truenascompanion.AppContainer
import app.truenascompanion.data.api.NetworkApi
import app.truenascompanion.data.api.userMessage
import app.truenascompanion.data.model.InterfaceRate
import app.truenascompanion.data.model.IpmiLan
import app.truenascompanion.data.model.NetworkOverview
import app.truenascompanion.ui.components.UiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 1.9.0: Network settings, view only. Loads through [NetworkApi] (read-only methods only). */
class NetworkViewModel(private val c: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow<UiState<NetworkOverview>>(UiState.Loading)
    val state = _state.asStateFlow()
    private val _ipmi = MutableStateFlow<List<IpmiLan>>(emptyList())
    val ipmi = _ipmi.asStateFlow()
    private val _refreshing = MutableStateFlow(false)
    val refreshing = _refreshing.asStateFlow()
    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    /** Live traffic per interface from `reporting.realtime`, only while the page is on screen. */
    val rates: StateFlow<Map<String, InterfaceRate>> = c.repository.realtime()
        .map { it.interfaces }
        .catch { emit(emptyMap()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    init {
        viewModelScope.launch { c.repository.reloadKey.collect { if (it != null) load() } }
    }

    fun refresh() = viewModelScope.launch { _refreshing.value = true; load(); _refreshing.value = false }

    private suspend fun load() {
        try {
            _state.value = UiState.Success(c.repository.call { NetworkApi(it).overview() })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            if (_state.value !is UiState.Success) _state.value = UiState.Error(e.userMessage(), e) else _messages.trySend(e.userMessage())
            return
        }
        // IPMI runs a tool on the NAS and can take a few seconds, so it fills in afterwards.
        _ipmi.value = runCatching { c.repository.call { NetworkApi(it).ipmi() } }.getOrDefault(_ipmi.value)
    }
}
