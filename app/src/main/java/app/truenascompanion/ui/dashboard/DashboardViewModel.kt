package app.truenascompanion.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.truenascompanion.AppContainer
import app.truenascompanion.data.api.userMessage
import app.truenascompanion.data.model.AlertItem
import app.truenascompanion.data.model.AppState
import app.truenascompanion.data.model.DashboardLayout
import app.truenascompanion.data.model.Health
import app.truenascompanion.data.model.Pool
import app.truenascompanion.data.model.RealtimeStats
import app.truenascompanion.data.model.SystemInfo
import app.truenascompanion.data.model.WidgetSize
import app.truenascompanion.data.model.WidgetType
import app.truenascompanion.data.repository.ConnectionState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AppsSummary(val total: Int, val running: Int, val updates: Int, val problems: Int)
data class AlertsSummary(val active: Int, val worst: Health, val latest: AlertItem?)

data class DashboardData(
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val error: String? = null,
    val system: SystemInfo? = null,
    val pools: List<Pool>? = null,
    val apps: AppsSummary? = null,
    val alerts: AlertsSummary? = null,
    val hottestDisk: Pair<String, Double>? = null,
)

/** Rolling window of live samples for sparklines. */
data class LiveStats(
    val latest: RealtimeStats? = null,
    val cpu: List<Float> = emptyList(),
    val rx: List<Float> = emptyList(),
    val tx: List<Float> = emptyList(),
) {
    fun add(s: RealtimeStats, max: Int = 40) = LiveStats(
        latest = s,
        cpu = (cpu + (s.cpuPercent?.toFloat() ?: 0f)).takeLast(max),
        rx = (rx + (s.netRxBytesPerSec?.toFloat() ?: 0f)).takeLast(max),
        tx = (tx + (s.netTxBytesPerSec?.toFloat() ?: 0f)).takeLast(max),
    )
}

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class DashboardViewModel(private val c: AppContainer) : ViewModel() {
    private val repo = c.repository

    val server = repo.activeServer
    val connection: StateFlow<ConnectionState> = repo.state

    private val _data = MutableStateFlow(DashboardData())
    val data: StateFlow<DashboardData> = _data.asStateFlow()

    /** Live stats only flow while the dashboard is on screen (collectAsStateWithLifecycle + WhileSubscribed). */
    val live: StateFlow<LiveStats> = server.flatMapLatest { s ->
        if (s == null) emptyFlow() else repo.realtime().catch { }
    }.scan(LiveStats()) { acc, s -> acc.add(s) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(3000), LiveStats())

    private val savedLayout: StateFlow<DashboardLayout> = server.filterNotNull().map { it.id }.distinctUntilChanged()
        .flatMapLatest { c.settings.dashboardLayout(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, DashboardLayout.DEFAULT)

    private val _editing = MutableStateFlow(false)
    val editing: StateFlow<Boolean> = _editing.asStateFlow()

    /** Local copy while editing so drag & drop is instant; persisted (debounced) per server in DataStore. */
    private val draft = MutableStateFlow<DashboardLayout?>(null)

    val layout: StateFlow<DashboardLayout> = kotlinx.coroutines.flow.combine(savedLayout, draft) { saved, d -> d ?: saved }
        .stateIn(viewModelScope, SharingStarted.Eagerly, DashboardLayout.DEFAULT)

    init {
        viewModelScope.launch {
            draft.debounce(400).filterNotNull().collect { l -> server.value?.id?.let { c.settings.saveDashboardLayout(it, l) } }
        }
        viewModelScope.launch {
            server.map { it?.id }.distinctUntilChanged().collect { id ->
                draft.value = null
                _editing.value = false
                if (id != null) { _data.value = DashboardData(); load() }
            }
        }
    }

    fun refresh() = viewModelScope.launch {
        _data.update { it.copy(refreshing = true) }
        load()
    }

    private suspend fun load() {
        try {
            coroutineScope {
                val sys = async { repo.call { it.systemInfo() } }
                val pools = async { runCatching { repo.call { it.pools() } }.getOrNull() }
                val apps = async { runCatching { repo.call { it.apps() } }.getOrNull() }
                val alerts = async { runCatching { repo.call { it.alerts() } }.getOrNull() }
                val temps = async {
                    runCatching {
                        repo.call { api -> api.diskTemperatures(api.disks().map { it.name }) }
                    }.getOrNull()
                }
                val system = sys.await()
                val appList = apps.await()
                val alertList = alerts.await()?.filter { !it.dismissed }
                _data.value = DashboardData(
                    loading = false,
                    system = system,
                    pools = pools.await(),
                    apps = appList?.let { l ->
                        AppsSummary(
                            total = l.size,
                            running = l.count { it.state == AppState.RUNNING },
                            updates = l.count { it.upgradeAvailable || it.imageUpdatesAvailable },
                            problems = l.count { it.state == AppState.CRASHED },
                        )
                    },
                    alerts = alertList?.let { l ->
                        AlertsSummary(
                            active = l.size,
                            worst = when {
                                l.any { it.health == Health.CRITICAL } -> Health.CRITICAL
                                l.any { it.health == Health.WARNING } -> Health.WARNING
                                else -> Health.HEALTHY
                            },
                            latest = l.sortedWith(
                                compareByDescending<AlertItem> { a -> when (a.health) { Health.CRITICAL -> 2; Health.WARNING -> 1; else -> 0 } }
                                    .thenByDescending { a -> a.datetimeMillis ?: 0L }
                            ).firstOrNull(),
                        )
                    },
                    hottestDisk = temps.await()?.maxByOrNull { it.value }?.toPair(),
                )
            }
        } catch (e: Throwable) {
            _data.update { it.copy(loading = false, refreshing = false, error = e.userMessage()) }
        }
    }

    // --- layout editing ---
    fun setEditing(on: Boolean) {
        if (on) draft.value = layout.value
        _editing.value = on
    }

    fun moveByKey(fromKey: String?, toKey: String?) = draft.update {
        val current = it ?: layout.value
        val from = current.widgets.indexOfFirst { w -> w.type.name == fromKey }
        val to = current.widgets.indexOfFirst { w -> w.type.name == toKey }
        current.move(from, to)
    }
    fun toggleVisible(type: WidgetType) = draft.update { (it ?: layout.value).update(type) { w -> w.copy(visible = !w.visible) } }
    fun toggleSize(type: WidgetType) = draft.update {
        (it ?: layout.value).update(type) { w -> w.copy(size = if (w.size == WidgetSize.FULL) WidgetSize.HALF else WidgetSize.FULL) }
    }

    fun resetLayout() {
        draft.value = DashboardLayout.DEFAULT
    }
}
