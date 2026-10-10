package app.truenascompanion.ui.reports

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.ZoomOutMap
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import app.truenascompanion.ui.components.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import app.truenascompanion.ui.components.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.truenascompanion.AppContainer
import app.truenascompanion.data.api.ReportingApi
import app.truenascompanion.data.api.userMessage
import app.truenascompanion.data.model.ReportData
import app.truenascompanion.data.model.ReportGraph
import app.truenascompanion.data.model.ReportKind
import app.truenascompanion.data.model.ReportRange
import app.truenascompanion.data.model.ReportSeries
import app.truenascompanion.data.model.ReportUnit
import app.truenascompanion.ui.appViewModel
import app.truenascompanion.ui.components.ChartLine
import app.truenascompanion.ui.components.ChartViewport
import app.truenascompanion.ui.components.ElevatedSection
import app.truenascompanion.ui.components.IconBadge
import app.truenascompanion.ui.components.ScrollableErrorState
import app.truenascompanion.ui.components.SkeletonCard
import app.truenascompanion.ui.components.TimeSeriesChart
import app.truenascompanion.ui.components.UiState
import app.truenascompanion.ui.components.isLoginRequired
import app.truenascompanion.ui.theme.LocalBrandColors
import app.truenascompanion.util.Format
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** What the Reports screen shows: one chart per available [ReportKind]. */
data class ReportsUi(
    val range: ReportRange = ReportRange.HOUR,
    val graphs: List<ReportGraph> = emptyList(),
    val iface: String? = null,
    val disk: String? = null,
    val charts: Map<ReportKind, ReportData> = emptyMap(),
    /** 1.10.0: one disk on the temperature chart (null = up to 8 disks, one line each). */
    val tempDisk: String? = null,
    /** 1.10.0: when the requested range started (epoch s), to tell the user when the NAS keeps less history. */
    val requestedStart: Long = 0,
) {
    /** Oldest data point across the charts (epoch s), or null without data. */
    val historyStart: Long? get() = charts.values.mapNotNull { d -> d.times.indices.firstOrNull { i -> d.series.any { !it.values[i].isNaN() } }?.let { d.times[it] } }.minOrNull()

    /** True when the NAS returned noticeably less history than the range asks for. */
    val historyShorter: Boolean get() = range.seconds >= ReportRange.WEEK.seconds && historyStart?.let { it > requestedStart + 2 * 86_400 } == true
}

class ReportsViewModel(private val c: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow<UiState<ReportsUi>>(UiState.Loading)
    val state = _state.asStateFlow()
    private val _refreshing = MutableStateFlow(false)
    val refreshing = _refreshing.asStateFlow()
    private var ui = ReportsUi()

    init {
        viewModelScope.launch { c.repository.reloadKey.collect { if (it != null) load() } }
    }

    fun setRange(r: ReportRange) { if (r != ui.range) { ui = ui.copy(range = r); _state.value = UiState.Loading; viewModelScope.launch { load() } } }
    fun setIface(i: String) { ui = ui.copy(iface = i); viewModelScope.launch { load() } }
    fun setDisk(d: String) { ui = ui.copy(disk = d); viewModelScope.launch { load() } }
    fun setTempDisk(d: String?) { ui = ui.copy(tempDisk = d); viewModelScope.launch { load() } }
    fun refresh() = viewModelScope.launch { _refreshing.value = true; load(); _refreshing.value = false }

    private suspend fun load() {
        try {
            ui = c.repository.call { api -> fetch(ReportingApi(api), ui) }
            _state.value = UiState.Success(ui)
        } catch (e: Throwable) {
            if (_state.value !is UiState.Success) _state.value = UiState.Error(e.userMessage(), e)
        }
    }

    companion object {
        /** Loads every chart in one `reporting.netdata_get_data` call (graphs list is fetched once). */
        suspend fun fetch(r: ReportingApi, cur: ReportsUi): ReportsUi {
            val graphs = cur.graphs.ifEmpty { r.graphs() }
            val byName = graphs.associateBy { it.name }
            val iface = cur.iface?.takeIf { byName["interface"]?.identifiers?.contains(it) == true } ?: byName["interface"]?.identifiers?.firstOrNull()
            val disk = cur.disk?.takeIf { byName["disk"]?.identifiers?.contains(it) == true } ?: byName["disk"]?.identifiers?.firstOrNull()
            val allTemps = byName["disktemp"]?.identifiers.orEmpty()
            val tempDisk = cur.tempDisk?.takeIf { it in allTemps }
            val diskTemps = if (tempDisk != null) listOf(tempDisk) else allTemps.take(8)
            val req = buildList {
                ReportKind.entries.forEach { k ->
                    if (k.graph !in byName) return@forEach
                    when (k) {
                        ReportKind.NETWORK -> iface?.let { add(k.graph to it) }
                        ReportKind.DISK -> disk?.let { add(k.graph to it) }
                        ReportKind.DISK_TEMP -> diskTemps.forEach { add(k.graph to it) }
                        else -> add(k.graph to null)
                    }
                }
            }
            val end = System.currentTimeMillis() / 1000
            val results = r.data(req, cur.range, endSec = end)
            val charts = LinkedHashMap<ReportKind, ReportData>()
            ReportKind.entries.forEach { k ->
                val rs = results.filter { it.name == k.graph }
                if (rs.isEmpty()) return@forEach
                val d = if (k == ReportKind.DISK_TEMP) mergeByIdentifier(rs) else rs.first()
                charts[k] = ReportingApi.downsample(pickSeries(k, d))
            }
            return cur.copy(graphs = graphs, iface = iface, disk = disk, charts = charts, tempDisk = tempDisk, requestedStart = end - cur.range.seconds)
        }

        /** CPU reports every core plus the total: show the total only when present. */
        fun pickSeries(k: ReportKind, d: ReportData): ReportData = when (k) {
            ReportKind.CPU -> d.series.firstOrNull { it.label.equals("cpu", true) }?.let { d.copy(series = listOf(it.copy(label = "CPU"))) } ?: d.copy(series = d.series.take(4))
            ReportKind.CPU_TEMP -> d.copy(series = d.series.take(6))
            else -> d
        }

        /** Disk temperatures arrive as one result per disk: one line each, aligned on the first disk's timestamps. */
        fun mergeByIdentifier(rs: List<ReportData>): ReportData {
            val base = rs.first()
            val series = rs.mapNotNull { d ->
                val s = d.series.firstOrNull() ?: return@mapNotNull null
                if (d.times.isEmpty()) return@mapNotNull null
                val values = FloatArray(base.times.size) { i ->
                    val t = base.times[i]
                    var j = d.times.binarySearch(t)
                    if (j < 0) j = (-j - 1).coerceIn(0, d.times.size - 1)
                    if (kotlin.math.abs(d.times[j] - t) > 600) Float.NaN else s.values[j]
                }
                ReportSeries(d.identifier?.substringBefore(" | ")?.trim() ?: s.label, values, s.min, s.mean, s.max)
            }
            return base.copy(identifier = null, series = series)
        }

        fun formatValue(unit: ReportUnit, v: Float): String = when (unit) {
            ReportUnit.PERCENT -> String.format(Locale.US, "%.0f%%", v)
            ReportUnit.CELSIUS -> String.format(Locale.US, "%.0f°C", v)
            ReportUnit.BYTES -> Format.bytes(v.toLong())
            ReportUnit.KILOBITS -> bits(v * 1000.0)
            ReportUnit.KIBIBYTES -> Format.bytes((v * 1024).toLong()) + "/s"
            ReportUnit.PLAIN -> String.format(Locale.US, "%.2f", v)
        }

        private fun bits(b: Double): String {
            val u = arrayOf("b/s", "Kb/s", "Mb/s", "Gb/s")
            var v = b.coerceAtLeast(0.0); var i = 0
            while (v >= 999.5 && i < u.size - 1) { v /= 1000; i++ }
            return String.format(Locale.US, if (v >= 9.95 || i == 0) "%.0f %s" else "%.1f %s", v, u[i])
        }

        fun timeFormatter(range: ReportRange, zone: ZoneId = ZoneId.systemDefault()): (Long) -> String {
            val f = DateTimeFormatter.ofPattern(when (range) { ReportRange.HOUR, ReportRange.DAY -> "HH:mm"; ReportRange.WEEK -> "EEE HH:mm"; ReportRange.MONTH -> "d MMM"; ReportRange.YEAR -> "MMM yy" }, Locale.getDefault())
            return { t -> f.format(Instant.ofEpochSecond(t).atZone(zone)) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportsScreen(onBack: () -> Unit) {
    val vm = appViewModel { ReportsViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val range = (state as? UiState.Success)?.data?.range ?: ReportRange.HOUR
    Scaffold(topBar = {
        TopAppBar(title = { Text("Reports") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } })
    }) { padding ->
        PullToRefreshBox(isRefreshing = refreshing, onRefresh = { vm.refresh() }, modifier = Modifier.padding(padding).fillMaxSize()) {
            when (val s = state) {
                UiState.Loading -> Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    RangePicker(range, vm::setRange)
                    repeat(3) { SkeletonCard(height = 230.dp) }
                }
                is UiState.Error -> ScrollableErrorState(s.message, s.isLoginRequired) { vm.refresh() }
                is UiState.Success -> ReportsContent(s.data, vm::setRange, vm::setIface, vm::setDisk, vm::setTempDisk)
            }
        }
    }
}

@Composable
private fun RangePicker(range: ReportRange, onRange: (ReportRange) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        ReportRange.entries.forEachIndexed { i, r ->
            SegmentedButton(selected = r == range, onClick = { onRange(r) }, shape = SegmentedButtonDefaults.itemShape(i, ReportRange.entries.size), icon = {}) {
                Text(r.label, maxLines = 1)
            }
        }
    }
}

/** Stateless content (also rendered by the screenshot tests with example data). */
@Composable
fun ReportsContent(ui: ReportsUi, onRange: (ReportRange) -> Unit, onIface: (String) -> Unit, onDisk: (String) -> Unit, onTempDisk: (String?) -> Unit = {}) {
    val viewports = remember { mutableStateMapOf<ReportKind, ChartViewport>() }
    val fmtTime = remember(ui.range) { ReportsViewModel.timeFormatter(ui.range) }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxSize()) {
        item { RangePicker(ui.range) { viewports.clear(); onRange(it) } }
        item {
            Text("Pinch to zoom, drag with two fingers to pan, touch for values. Double-tap resets.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp))
        }
        if (ui.historyShorter) item {
            app.truenascompanion.ui.components.InfoBanner(
                "This NAS keeps history from ${historyDate(ui.historyStart!!)}. TrueNAS stores 30 days by default; you can keep more in the web UI under Reporting settings.",
                health = app.truenascompanion.data.model.Health.INFO,
            )
        }
        if (ui.charts.isEmpty()) item {
            ElevatedSection { Text("No reporting data yet. TrueNAS collects history with netdata; check System › Advanced › Reporting on the NAS.", style = MaterialTheme.typography.bodyMedium) }
        }
        items(ui.charts.entries.toList(), key = { it.key.name }) { (kind, data) ->
            val choices = when (kind) {
                ReportKind.NETWORK -> ui.graphs.firstOrNull { it.name == "interface" }?.identifiers.orEmpty() to ui.iface
                ReportKind.DISK -> ui.graphs.firstOrNull { it.name == "disk" }?.identifiers.orEmpty() to ui.disk
                // 1.10.0: per-disk temperature history ("All" = up to 8 disks, one line each)
                ReportKind.DISK_TEMP -> ui.graphs.firstOrNull { it.name == "disktemp" }?.identifiers.orEmpty().let { ids -> if (ids.size > 1) listOf(ALL_DISKS) + ids else ids } to (ui.tempDisk ?: ALL_DISKS)
                else -> emptyList<String>() to null
            }
            ChartCard(
                kind = kind, data = data, formatTime = fmtTime,
                viewport = viewports[kind] ?: ChartViewport(), onViewport = { viewports[kind] = it },
                choices = choices.first, selected = choices.second,
                onChoose = { when (kind) { ReportKind.NETWORK -> onIface(it); ReportKind.DISK_TEMP -> onTempDisk(it.takeIf { c -> c != ALL_DISKS }); else -> onDisk(it) } },
            )
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

private const val ALL_DISKS = "All disks"

private fun historyDate(sec: Long): String =
    DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault()).format(Instant.ofEpochSecond(sec).atZone(ZoneId.systemDefault()))

@Composable
fun reportPalette(): List<Color> {
    val b = LocalBrandColors.current
    val s = MaterialTheme.colorScheme
    return listOf(b.chartRx, b.chartTx, b.chartArc, s.primary, Color(0xFFF59E0B), Color(0xFFEC4899), Color(0xFF84CC16), Color(0xFF94A3B8))
}

@Composable
fun ChartCard(
    kind: ReportKind,
    data: ReportData,
    formatTime: (Long) -> String,
    viewport: ChartViewport,
    onViewport: (ChartViewport) -> Unit,
    choices: List<String> = emptyList(),
    selected: String? = null,
    onChoose: (String) -> Unit = {},
) {
    val palette = reportPalette()
    val lines = data.series.mapIndexed { i, s -> ChartLine(s.label.replaceFirstChar { it.uppercase() }, s.values, palette[i % palette.size]) }
    ElevatedSection {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(Icons.Rounded.Insights, size = 30.dp)
            Spacer(Modifier.width(8.dp))
            Text(kind.title + (data.identifier?.let { " · $it" } ?: ""), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            if (viewport.zoomed) IconButton(onClick = { onViewport(ChartViewport()) }) { Icon(Icons.Rounded.ZoomOutMap, "Reset zoom") }
        }
        if (choices.size > 1) {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                choices.forEach { c -> FilterChip(selected = c == selected, onClick = { onChoose(c) }, label = { Text(c.substringBefore(" | ").trim(), maxLines = 1) }) }
            }
        }
        Spacer(Modifier.height(8.dp))
        TimeSeriesChart(
            times = data.times, lines = lines,
            formatValue = { ReportsViewModel.formatValue(kind.unit, it) }, formatTime = formatTime,
            maxY = if (kind.unit == ReportUnit.PERCENT) 100f else null,
            minY = if (kind.unit == ReportUnit.CELSIUS) null else 0f,
            viewport = viewport, onViewportChange = onViewport,
            description = "${kind.title} chart",
        )
        Spacer(Modifier.height(8.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            data.series.forEachIndexed { i, s ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.foundation.Canvas(Modifier.size(8.dp)) { drawCircle(palette[i % palette.size]) }
                    Spacer(Modifier.width(4.dp))
                    val stats = listOfNotNull(s.mean?.let { "avg ${ReportsViewModel.formatValue(kind.unit, it.toFloat())}" }, s.max?.let { "max ${ReportsViewModel.formatValue(kind.unit, it.toFloat())}" })
                    Text(s.label.replaceFirstChar { it.uppercase() } + if (stats.isEmpty()) "" else " · " + stats.joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
            }
        }
    }
}
