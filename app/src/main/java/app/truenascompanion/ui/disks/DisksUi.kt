package app.truenascompanion.ui.disks

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.FlashlightOn
import androidx.compose.material.icons.rounded.HealthAndSafety
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.PauseCircle
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.SdStorage
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.truenascompanion.AppContainer
import app.truenascompanion.data.api.userMessage
import app.truenascompanion.data.disks.DiskLogic
import app.truenascompanion.data.disks.DisksApi
import app.truenascompanion.data.model.DiskAlert
import app.truenascompanion.data.model.DiskInfo
import app.truenascompanion.data.model.DiskKind
import app.truenascompanion.data.model.EnclosureSlot
import app.truenascompanion.data.model.Health
import app.truenascompanion.data.model.PoolLayout
import app.truenascompanion.data.model.PoolMember
import app.truenascompanion.data.model.ScanInfo
import app.truenascompanion.data.model.VdevGroup
import app.truenascompanion.data.model.VdevNode
import app.truenascompanion.ui.appViewModel
import app.truenascompanion.ui.components.CapacityBar
import app.truenascompanion.ui.components.ConfirmDialog
import app.truenascompanion.ui.components.ElevatedSection
import app.truenascompanion.ui.components.EmptyState
import app.truenascompanion.ui.components.IconBadge
import app.truenascompanion.ui.components.InfoBanner
import app.truenascompanion.ui.components.LabeledValue
import app.truenascompanion.ui.components.ScrollableErrorState
import app.truenascompanion.ui.components.SectionTitle
import app.truenascompanion.ui.components.SkeletonList
import app.truenascompanion.ui.components.StatusChip
import app.truenascompanion.ui.components.UiState
import app.truenascompanion.ui.components.isLoginRequired
import app.truenascompanion.ui.dashboard.diskTempHealth
import app.truenascompanion.ui.theme.LocalStatusColors
import app.truenascompanion.util.Format
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/** Pools (with vdev trees), disks and drive bays: everything the disk screens show (1.3.0). */
data class DisksData(
    val pools: List<PoolLayout>,
    val disks: List<DiskInfo>,
    val slots: List<EnclosureSlot> = emptyList(),
    val alerts: List<DiskAlert> = emptyList(),
) {
    fun member(name: String): PoolMember? = DiskLogic.memberFor(pools, name)
    fun disk(name: String): DiskInfo? = disks.firstOrNull { it.name == name }
    fun slot(name: String): EnclosureSlot? = slots.firstOrNull { it.dev == name && it.supportsIdentify }
    val sizes: Map<String, Long> get() = disks.mapNotNull { d -> d.size?.let { d.name to it } }.toMap()
}

class DisksViewModel(private val c: AppContainer, private val focusDisk: String? = null) : ViewModel() {
    private val _state = MutableStateFlow<UiState<DisksData>>(UiState.Loading)
    val state: StateFlow<UiState<DisksData>> = _state.asStateFlow()
    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()
    val messages = MutableSharedFlow<String>(extraBufferCapacity = 4)

    init { viewModelScope.launch { load() } }

    fun refresh() = viewModelScope.launch { _refreshing.value = true; load(); _refreshing.value = false }

    private suspend fun load() {
        try {
            coroutineScope {
                val pools = async { c.repository.call { DisksApi(it).pools() } }
                val disks = async { c.repository.call { DisksApi(it).disks() } }
                val slots = async { runCatching { c.repository.call { DisksApi(it).enclosureSlots() } }.getOrDefault(emptyList()) }
                var list = disks.await()
                var alerts = emptyList<DiskAlert>()
                if (focusDisk != null) {
                    val agg = c.repository.call { DisksApi(it).temperatureAgg(listOf(focusDisk)) }[focusDisk]
                    if (agg != null) list = list.map { d -> if (d.name == focusDisk) d.copy(tempMin = agg.first, tempMax = agg.second, tempAvg = agg.third) else d }
                    val serial = list.firstOrNull { it.name == focusDisk }?.serial
                    alerts = runCatching { c.repository.call { DisksApi(it).alertsFor(focusDisk, serial) } }.getOrDefault(emptyList())
                }
                _state.value = UiState.Success(DisksData(pools.await(), list, slots.await(), alerts))
            }
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            if (_state.value !is UiState.Success) _state.value = UiState.Error(e.userMessage(), e)
            else messages.tryEmit(e.userMessage())
        }
    }

    private fun action(done: String, block: suspend (DisksApi) -> Unit) {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            try {
                c.repository.call { block(DisksApi(it)) }
                messages.tryEmit(done)
                load()
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                messages.tryEmit(e.userMessage())
            } finally {
                _busy.value = false
            }
        }
    }

    fun offline(m: PoolMember) = action("Disk taken offline") { it.offline(m.poolId, m.node.guid!!) }
    fun online(m: PoolMember) = action("Disk brought online") { it.online(m.poolId, m.node.guid!!) }
    fun detach(m: PoolMember) = action("Disk detached from ${m.poolName}") { it.detach(m.poolId, m.node.guid!!) }
    fun identify(slot: EnclosureSlot, on: Boolean) = action(if (on) "Identify light on" else "Identify light off") { it.setIdentify(slot, on) }
}

// ---------- shared bits ----------

fun memberHealth(status: String): Health = when (status.uppercase()) {
    "ONLINE" -> Health.HEALTHY
    "DEGRADED", "OFFLINE" -> Health.WARNING
    "FAULTED", "UNAVAIL", "REMOVED" -> Health.CRITICAL
    else -> Health.UNKNOWN
}

fun statusLabel(status: String) = status.lowercase().replaceFirstChar { it.uppercase() }

fun kindIcon(kind: DiskKind): ImageVector = when (kind) {
    DiskKind.NVME -> Icons.Rounded.Memory
    DiskKind.SSD -> Icons.Rounded.SdStorage
    else -> Icons.Rounded.Album
}

fun vdevTitle(n: VdevNode): String = when {
    n.isLeaf -> "Stripe"
    n.type.startsWith("RAIDZ") -> "RAIDZ" + n.type.removePrefix("RAIDZ")
    n.type.startsWith("DRAID") -> "dRAID"
    else -> n.type.lowercase().replaceFirstChar { it.uppercase() }
}

private fun dateTime(ms: Long?) = ms?.let { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it)) } ?: "—"

@Composable
private fun ErrorCounts(n: VdevNode) {
    val c = LocalStatusColors.current
    val color = if (n.errors > 0) c.of(Health.CRITICAL) else MaterialTheme.colorScheme.onSurfaceVariant
    Text("R ${n.readErrors} · W ${n.writeErrors} · C ${n.checksumErrors}", style = MaterialTheme.typography.labelMedium, color = color, fontFamily = FontFamily.Monospace)
}

// ---------- Disks tab ----------

/** Storage → Disks: one card per disk with kind, model, serial, pool/vdev, status, temperature and errors. */
@Composable
fun DisksPane(data: DisksData, onOpenDisk: (String) -> Unit) {
    if (data.disks.isEmpty()) {
        LazyColumn(Modifier.fillMaxSize()) { item { EmptyState(Icons.Rounded.Album, "No disks", "No disks were reported by the server.") } }
        return
    }
    val missing = data.pools.flatMap { p -> DiskLogic.members(p).filter { it.node.disk == null && it.node.status != "ONLINE" } }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxSize()) {
        items(missing, key = { "missing:" + (it.node.guid ?: it.node.name) }) { m -> MissingDiskCard(m, onOpenDisk) }
        items(data.disks, key = { it.name }) { d -> DiskCard(d, data.member(d.name)) { onOpenDisk(d.name) } }
    }
}

@Composable
private fun MissingDiskCard(m: PoolMember, onOpenDisk: (String) -> Unit) {
    val un = m.node.unavailDisk
    ElevatedSection(contentPadding = 14.dp, onClick = { onOpenDisk(un?.name ?: m.node.guid ?: m.node.name) }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(Icons.Rounded.Album, tint = LocalStatusColors.current.of(Health.CRITICAL))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(un?.name ?: "Missing disk", style = MaterialTheme.typography.titleMedium)
                Text(
                    listOfNotNull(un?.model, un?.serial?.let { "SN $it" }, "pool ${m.poolName}").joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
            StatusChip(memberHealth(m.node.status), statusLabel(m.node.status))
        }
    }
}

@Composable
fun DiskCard(d: DiskInfo, m: PoolMember?, onClick: () -> Unit) {
    ElevatedSection(contentPadding = 14.dp, onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(kindIcon(d.kind), tint = if (m != null && m.node.status != "ONLINE") LocalStatusColors.current.of(memberHealth(m.node.status)) else MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(d.name, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.width(8.dp))
                    KindTag(d.kind)
                }
                Text(
                    listOfNotNull(d.model, Format.bytes(d.size)).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            StatusChip(diskTempHealth(d.temperatureC), Format.temp(d.temperatureC), showIcon = false)
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    if (m != null) "${m.poolName} · ${m.parent?.let { vdevTitle(it) + " " + it.name.substringAfterLast('-', "") }?.trim() ?: "stripe"}" + if (m.category != "data") " · ${VdevGroup.categoryLabel(m.category)}" else ""
                    else d.pool?.let { "pool $it" } ?: "Not in a pool",
                    style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(d.serial?.let { "SN $it" } ?: "No serial", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontFamily = FontFamily.Monospace, maxLines = 1)
            }
            if (m != null) Column(horizontalAlignment = Alignment.End) {
                StatusChip(memberHealth(m.node.status), statusLabel(m.node.status))
                Spacer(Modifier.height(4.dp))
                ErrorCounts(m.node)
            }
        }
    }
}

@Composable
private fun KindTag(kind: DiskKind) {
    Surface(color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f), shape = RoundedCornerShape(50)) {
        Text(kind.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
    }
}

// ---------- Pool layout ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PoolLayoutScreen(poolName: String, onBack: () -> Unit, onOpenDisk: (String) -> Unit, onReplace: (String?) -> Unit) {
    val vm = appViewModel(key = "pool_layout:$poolName") { DisksViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(vm) { vm.messages.collect { snackbar.showSnackbar(it) } }
    Scaffold(
        topBar = { TopAppBar(title = { Text(poolName) }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } }) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        PullToRefreshBox(isRefreshing = refreshing, onRefresh = { vm.refresh() }, modifier = Modifier.padding(padding).fillMaxSize()) {
            when (val s = state) {
                UiState.Loading -> SkeletonList(4, 120.dp)
                is UiState.Error -> ScrollableErrorState(s.message, s.isLoginRequired) { vm.refresh() }
                is UiState.Success -> {
                    val pool = s.data.pools.firstOrNull { it.name == poolName }
                    if (pool == null) EmptyState(Icons.Rounded.Storage, "Pool not found", "TrueNAS doesn't report a pool named $poolName.")
                    else PoolLayoutContent(pool, s.data, onOpenDisk, onReplace)
                }
            }
        }
    }
}

@Composable
fun PoolLayoutContent(pool: PoolLayout, data: DisksData, onOpenDisk: (String) -> Unit, onReplace: (String?) -> Unit) {
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxSize()) {
        item { PoolHeader(pool, onReplace) }
        pool.groups.forEach { g ->
            item(key = "g:${g.category}") { SectionTitle(g.label) }
            items(g.vdevs, key = { "v:${g.category}:${it.guid ?: it.name}" }) { v -> VdevCard(v, data, onOpenDisk, onReplace) }
        }
    }
}

@Composable
private fun PoolHeader(pool: PoolLayout, onReplace: (String?) -> Unit) {
    val health = when { pool.healthy -> Health.HEALTHY; pool.status == "ONLINE" -> Health.WARNING; else -> memberHealth(pool.status) }
    ElevatedSection {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(Icons.Rounded.Storage, tint = LocalStatusColors.current.of(health))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(pool.name, style = MaterialTheme.typography.titleLarge)
                val members = DiskLogic.members(pool)
                Text("${members.size} disks · ${pool.groups.sumOf { it.vdevs.size }} vdevs", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            StatusChip(health, statusLabel(pool.status))
        }
        val size = pool.size
        if (size != null && size > 0) {
            Spacer(Modifier.height(12.dp))
            CapacityBar(((pool.allocated ?: 0).toFloat() / size), height = 10.dp)
            Spacer(Modifier.height(4.dp))
            Text("${Format.bytes(pool.allocated)} used of ${Format.bytes(size)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        pool.statusDetail?.takeIf { !pool.healthy }?.let { Spacer(Modifier.height(10.dp)); Text(it, style = MaterialTheme.typography.bodySmall) }
        pool.scan?.let { Spacer(Modifier.height(12.dp)); ScanBlock(it) }
        if (!pool.healthy) {
            Spacer(Modifier.height(12.dp))
            FilledTonalButton(onClick = { onReplace(null) }) { Icon(Icons.Rounded.SwapHoriz, null); Spacer(Modifier.width(8.dp)); Text("Replace a disk") }
        }
    }
}

@Composable
fun ScanBlock(s: ScanInfo) {
    val what = if (s.function == "RESILVER") "Resilver" else "Scrub"
    when {
        s.state == "SCANNING" -> {
            Text(
                "$what running · ${Format.percent(s.percent)}" + (DiskLogic.eta(s.secondsLeft)?.let { " · $it left" } ?: "") + if (s.paused) " · paused" else "",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(progress = { ((s.percent ?: 0.0) / 100).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
        }
        else -> Text(
            "Last $what: ${if (s.state == "FINISHED") "finished" else s.state?.lowercase() ?: "—"} ${dateTime(s.endMillis ?: s.startMillis)}" + (s.errors?.let { " · $it errors" } ?: ""),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun VdevCard(v: VdevNode, data: DisksData, onOpenDisk: (String) -> Unit, onReplace: (String?) -> Unit) {
    ElevatedSection(contentPadding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(if (v.isLeaf) "Single disk" else "${vdevTitle(v)} · ${v.name}", style = MaterialTheme.typography.titleSmall)
                if (!v.isLeaf) Text("${v.children.size} disks" + (v.size?.let { " · ${Format.bytes(it)}" } ?: ""), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            StatusChip(memberHealth(v.status), statusLabel(v.status))
        }
        Spacer(Modifier.height(8.dp))
        val leaves = if (v.isLeaf) listOf(v) else v.children
        leaves.forEachIndexed { i, n ->
            if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            if (n.isLeaf) MemberRow(n, data, onOpenDisk, onReplace)
            else Column(Modifier.padding(start = 12.dp)) {
                Text("${vdevTitle(n)} · ${statusLabel(n.status)}", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 6.dp))
                n.children.forEach { MemberRow(it, data, onOpenDisk, onReplace) }
            }
        }
    }
}

@Composable
private fun MemberRow(n: VdevNode, data: DisksData, onOpenDisk: (String) -> Unit, onReplace: (String?) -> Unit) {
    val disk = n.disk?.let { data.disk(it) }
    val name = n.disk ?: n.unavailDisk?.name
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { if (n.disk != null) onOpenDisk(n.disk) else onReplace(n.guid) }.padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(kindIcon(disk?.kind ?: DiskKind.UNKNOWN), null, tint = LocalStatusColors.current.of(memberHealth(n.status)), modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(name ?: "Missing disk (${n.guid?.takeLast(6) ?: "?"})", style = MaterialTheme.typography.bodyLarge, maxLines = 1)
            val sn = disk?.serial ?: n.unavailDisk?.serial
            Text(listOfNotNull(disk?.model ?: n.unavailDisk?.model, sn?.let { "SN $it" }, Format.temp(disk?.temperatureC).takeIf { disk?.temperatureC != null }).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Column(horizontalAlignment = Alignment.End) {
            StatusChip(memberHealth(n.status), statusLabel(n.status), showIcon = false)
            Spacer(Modifier.height(2.dp))
            ErrorCounts(n)
        }
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ---------- Disk detail ----------

private sealed interface DiskDialog {
    data class Offline(val m: PoolMember) : DiskDialog
    data class Online(val m: PoolMember) : DiskDialog
    data class Detach(val m: PoolMember) : DiskDialog
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiskDetailScreen(name: String, onBack: () -> Unit, onReplace: (pool: String, guid: String?, disk: String?) -> Unit, onOpenPool: (String) -> Unit) {
    val vm = appViewModel(key = "disk:$name") { DisksViewModel(it, focusDisk = name) }
    val state by vm.state.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(vm) { vm.messages.collect { snackbar.showSnackbar(it) } }
    var dialog by remember { mutableStateOf<DiskDialog?>(null) }
    Scaffold(
        topBar = { TopAppBar(title = { Text(name) }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } }) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        PullToRefreshBox(isRefreshing = refreshing, onRefresh = { vm.refresh() }, modifier = Modifier.padding(padding).fillMaxSize()) {
            when (val s = state) {
                UiState.Loading -> SkeletonList(4, 120.dp)
                is UiState.Error -> ScrollableErrorState(s.message, s.isLoginRequired) { vm.refresh() }
                is UiState.Success -> DiskDetailContent(
                    name, s.data, busy,
                    onReplace = { m -> onReplace(m.poolName, m.node.guid, m.node.disk) },
                    onOffline = { dialog = DiskDialog.Offline(it) },
                    onOnline = { dialog = DiskDialog.Online(it) },
                    onDetach = { dialog = DiskDialog.Detach(it) },
                    onIdentify = vm::identify,
                    onOpenPool = onOpenPool,
                )
            }
        }
    }
    when (val d = dialog) {
        is DiskDialog.Offline -> ConfirmDialog(
            title = "Take $name offline?",
            text = "ZFS stops using this disk in ${d.m.poolName}. " +
                (if (DiskLogic.offlineIsSafe(d.m)) "The pool stays available but loses redundancy until the disk is back or replaced."
                else "Warning: this vdev may have no redundancy left. Taking the disk offline could make the pool unavailable.") +
                " Use this before pulling a disk you are about to replace.",
            confirmLabel = "Take offline", destructive = true, strongAuth = true, icon = Icons.Rounded.PauseCircle,
            onConfirm = { dialog = null; vm.offline(d.m) }, onDismiss = { dialog = null },
        )
        is DiskDialog.Online -> ConfirmDialog(
            title = "Bring $name online?",
            text = "ZFS starts using this disk in ${d.m.poolName} again and resilvers any changes it missed.",
            confirmLabel = "Bring online", strongAuth = true, icon = Icons.Rounded.PlayCircle,
            onConfirm = { dialog = null; vm.online(d.m) }, onDismiss = { dialog = null },
        )
        is DiskDialog.Detach -> ConfirmDialog(
            title = "Detach $name?",
            text = "The disk is removed from ${d.m.parent?.let { vdevTitle(it).lowercase() + " " + it.name } ?: "its vdev"} in ${d.m.poolName}. " +
                "Its data is no longer part of the pool, and the vdev has one copy fewer. This can't be undone from the app.",
            confirmLabel = "Detach", destructive = true, strongAuth = true, icon = Icons.Rounded.LinkOff,
            onConfirm = { dialog = null; vm.detach(d.m) }, onDismiss = { dialog = null },
        )
        null -> Unit
    }
}

@Composable
fun DiskDetailContent(
    name: String,
    data: DisksData,
    busy: Boolean,
    onReplace: (PoolMember) -> Unit = {},
    onOffline: (PoolMember) -> Unit = {},
    onOnline: (PoolMember) -> Unit = {},
    onDetach: (PoolMember) -> Unit = {},
    onIdentify: (EnclosureSlot, Boolean) -> Unit = { _, _ -> },
    onOpenPool: (String) -> Unit = {},
) {
    val d = data.disk(name)
    val m = data.member(name) ?: data.pools.asSequence().flatMap { DiskLogic.members(it).asSequence() }.firstOrNull { it.node.guid == name }
    val pool = m?.let { mm -> data.pools.firstOrNull { it.id == mm.poolId } }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxSize()) {
        item {
            ElevatedSection {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconBadge(kindIcon(d?.kind ?: DiskKind.UNKNOWN), size = 48.dp, tint = m?.let { LocalStatusColors.current.of(memberHealth(it.node.status)) } ?: MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(d?.model ?: m?.node?.unavailDisk?.model ?: "Unknown model", style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text("${(d?.kind ?: DiskKind.UNKNOWN).label} · ${Format.bytes(d?.size ?: m?.node?.unavailDisk?.size)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (m != null) StatusChip(memberHealth(m.node.status), statusLabel(m.node.status))
                }
                Spacer(Modifier.height(14.dp))
                Row(Modifier.fillMaxWidth()) {
                    LabeledValue("Serial", d?.serial ?: m?.node?.unavailDisk?.serial ?: "—", Modifier.weight(1.4f))
                    LabeledValue("Temperature", Format.temp(d?.temperatureC), Modifier.weight(1f))
                }
                if (d != null && (d.tempMax != null || d.tempAvg != null)) {
                    Spacer(Modifier.height(10.dp))
                    Row(Modifier.fillMaxWidth()) {
                        LabeledValue("7-day min", Format.temp(d.tempMin), Modifier.weight(1f))
                        LabeledValue("avg", Format.temp(d.tempAvg), Modifier.weight(1f))
                        LabeledValue("max", Format.temp(d.tempMax), Modifier.weight(1f))
                    }
                }
                if (d != null) {
                    Spacer(Modifier.height(10.dp))
                    Row(Modifier.fillMaxWidth()) {
                        LabeledValue("Bus", d.bus ?: "—", Modifier.weight(1f))
                        LabeledValue("Rotation", d.rotationRate?.let { "$it rpm" } ?: if (d.kind == DiskKind.HDD) "—" else "Solid state", Modifier.weight(1f))
                    }
                    d.description?.let { Spacer(Modifier.height(8.dp)); Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
        item {
            ElevatedSection {
                SectionHeader(Icons.Rounded.Storage, "Pool")
                if (m == null) {
                    Text(d?.pool?.let { "Part of $it" } ?: "This disk isn't part of any pool.", style = MaterialTheme.typography.bodyMedium)
                } else {
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { onOpenPool(m.poolName) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(m.poolName, style = MaterialTheme.typography.titleMedium)
                            Text(
                                listOfNotNull(VdevGroup.categoryLabel(m.category), m.parent?.let { "${vdevTitle(it)} ${it.name}" } ?: "stripe").joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null)
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(Modifier.fillMaxWidth()) {
                        LabeledValue("Read errors", "${m.node.readErrors}", Modifier.weight(1f))
                        LabeledValue("Write errors", "${m.node.writeErrors}", Modifier.weight(1f))
                        LabeledValue("Checksum", "${m.node.checksumErrors}", Modifier.weight(1f))
                    }
                    pool?.scan?.let { Spacer(Modifier.height(12.dp)); ScanBlock(it) }
                }
            }
        }
        item {
            ElevatedSection {
                SectionHeader(Icons.Rounded.HealthAndSafety, "SMART & alerts")
                if (data.alerts.isEmpty()) {
                    Text("No SMART or temperature alerts for this disk.", style = MaterialTheme.typography.bodyMedium)
                } else {
                    data.alerts.forEach { a ->
                        val h = when (a.level.uppercase()) { "INFO", "NOTICE" -> Health.INFO; "WARNING" -> Health.WARNING; else -> Health.CRITICAL }
                        InfoBanner(a.text, health = h)
                        Spacer(Modifier.height(8.dp))
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "TrueNAS 25.10 doesn't share SMART test results through its API; it checks SMART itself and raises an alert when a disk reports problems. Schedule or run SMART tests in Storage › Protection.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        val slot = data.slot(name)
        if (slot != null) item {
            ElevatedSection {
                SectionHeader(Icons.Rounded.FlashlightOn, "Identify (bay ${slot.slot})")
                Text("Blink this drive bay's LED to find the disk in the enclosure.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = { onIdentify(slot, true) }, enabled = !busy) { Text("Light on") }
                    OutlinedButton(onClick = { onIdentify(slot, false) }, enabled = !busy) { Text("Light off") }
                }
            }
        }
        if (m != null) item {
            ElevatedSection {
                SectionHeader(Icons.Rounded.SwapHoriz, "Actions")
                ActionsBlock(m, busy, onReplace, onOffline, onOnline, onDetach)
            }
        }
    }
}

@Composable
private fun SectionHeader(icon: ImageVector, title: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 10.dp)) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(title, style = MaterialTheme.typography.titleSmall)
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ColumnScope.ActionsBlock(
    m: PoolMember, busy: Boolean,
    onReplace: (PoolMember) -> Unit, onOffline: (PoolMember) -> Unit, onOnline: (PoolMember) -> Unit, onDetach: (PoolMember) -> Unit,
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (DiskLogic.canReplace(m)) FilledTonalButton(onClick = { onReplace(m) }, enabled = !busy) { Text("Replace…") }
        if (DiskLogic.canOffline(m)) OutlinedButton(onClick = { onOffline(m) }, enabled = !busy) { Text("Offline") }
        if (DiskLogic.canOnline(m)) OutlinedButton(onClick = { onOnline(m) }, enabled = !busy) { Text("Online") }
        if (DiskLogic.canDetach(m)) OutlinedButton(onClick = { onDetach(m) }, enabled = !busy) { Text("Detach") }
    }
    if (busy) { Spacer(Modifier.height(10.dp)); LinearProgressIndicator(Modifier.fillMaxWidth()) }
}
