package app.truenascompanion.data.repository

import app.truenascompanion.data.api.TrueNasApi
import app.truenascompanion.data.api.TrueNasConnector
import app.truenascompanion.data.api.TrueNasException
import app.truenascompanion.data.api.userMessage
import app.truenascompanion.data.model.ApiFlavor
import app.truenascompanion.data.model.RealtimeStats
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

/**
 * Owns the connection to the active server and hands out a live [TrueNasApi].
 * Reconnects lazily when the WebSocket dropped (phone slept, Wi-Fi changed, ...).
 */
class TrueNasRepository(private val settings: SettingsStore, private val scope: CoroutineScope) {

    val activeServer: StateFlow<ServerConfig?> = combine(settings.servers, settings.activeServerId) { servers, id ->
        servers.firstOrNull { it.id == id } ?: servers.firstOrNull()
    }.distinctUntilChanged().stateIn(scope, SharingStarted.Eagerly, null)

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val mutex = Mutex()
    private var api: TrueNasApi? = null
    private var apiFor: ServerConfig? = null

    init {
        activeServer.onEach { server ->
            mutex.withLock {
                if (server != apiFor) {
                    api?.close()
                    api = null
                    apiFor = null
                }
            }
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
                val key = settings.apiKey(server.id) ?: throw TrueNasException.AuthFailed("No API key saved for this server.")
                val newApi = TrueNasConnector.connect(server, key)
                val info = runCatching { newApi.systemInfo() }.getOrNull()
                api = newApi
                apiFor = server
                _state.value = ConnectionState.Connected(newApi.flavor, info)
                newApi
            } catch (e: Throwable) {
                _state.value = ConnectionState.Failed(e.userMessage(), e)
                throw e
            }
        }
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

    /** Drops the current connection; the next call reconnects with the latest settings/API key. */
    suspend fun disconnect() {
        mutex.withLock {
            api?.close()
            api = null
            apiFor = null
        }
        _state.value = if (activeServer.value == null) ConnectionState.NoServer else ConnectionState.Idle
    }

    /** One-off connection used by the setup screen; never touches the active connection. */
    suspend fun test(server: ServerConfig, apiKey: String): TestResult = withContext(Dispatchers.IO) {
        val a = TrueNasConnector.connect(server, apiKey)
        try {
            TestResult(a.flavor, a.systemInfo())
        } finally {
            a.close()
        }
    }
}
