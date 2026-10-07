package app.truenascompanion.ui.storage

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import app.truenascompanion.TrueNasApp
import app.truenascompanion.ui.protection.ProtectionTab
import app.truenascompanion.ui.protection.ProtectionViewModel
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.truenascompanion.AppContainer
import app.truenascompanion.data.api.userMessage
import app.truenascompanion.data.model.SmbShareInput
import app.truenascompanion.data.model.NfsShareInput
import app.truenascompanion.data.model.DatasetCreateRequest
import app.truenascompanion.data.api.StorageApi
import app.truenascompanion.data.model.Dataset
import app.truenascompanion.data.model.DiskInfo
import app.truenascompanion.data.model.PoolLayout
import app.truenascompanion.data.disks.DisksApi
import app.truenascompanion.data.api.Parsers
import app.truenascompanion.data.api.arr
import app.truenascompanion.data.api.obj
import app.truenascompanion.data.model.Pool
import app.truenascompanion.ui.appViewModel
import app.truenascompanion.ui.components.CapacityBar
import app.truenascompanion.ui.components.ElevatedSection
import app.truenascompanion.ui.components.EmptyState
import app.truenascompanion.ui.components.Expandable
import app.truenascompanion.ui.components.IconBadge
import app.truenascompanion.ui.components.LabeledValue
import app.truenascompanion.ui.components.ScrollableErrorState
import app.truenascompanion.ui.components.isLoginRequired
import app.truenascompanion.ui.components.SkeletonList
import app.truenascompanion.ui.components.StatusChip
import app.truenascompanion.ui.components.UiState
import app.truenascompanion.ui.theme.LocalStatusColors
import app.truenascompanion.util.Format
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

data class StorageData(
    val pools: List<Pool>,
    val disks: List<DiskInfo>,
    val datasets: List<Dataset>,
    val shares: SharesData = SharesData(emptyList(), emptyList()),
    /** 1.3.0: vdev trees of the same pools (Disks tab: pool/vdev, status and errors per disk). */
    val layouts: List<PoolLayout> = emptyList(),
)

/** Storage segments (1.3.0 adds Files). Deep links and the dashboard use these indexes. */
object StorageTabs {
    const val POOLS = 0
    const val DISKS = 1
    const val DATASETS = 2
    const val FILES = 3
    const val SHARES = 4
    const val PROTECTION = 5
    val labels = listOf("Pools", "Disks", "Datasets", "Files", "Shares", "Protection")
}

class StorageViewModel(private val c: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow<UiState<StorageData>>(UiState.Loading)
    val state: StateFlow<UiState<StorageData>> = _state.asStateFlow()
    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()
    private val _busy = MutableStateFlow<Set<String>>(emptySet())
    val busy: StateFlow<Set<String>> = _busy.asStateFlow()
    private val _messages = MutableStateFlow<String?>(null)
    val messages: StateFlow<String?> = _messages.asStateFlow()

    init {
        viewModelScope.launch {
            c.repository.reloadKey.collect { if (it != null) { _state.value = UiState.Loading; load() } }
        }
    }

    fun refresh() = viewModelScope.launch { _refreshing.value = true; load(); _refreshing.value = false }
    fun consumeMessage() { _messages.value = null }

    private suspend fun load() {
        try {
            coroutineScope {
                // One pool.query for both the pool cards and the vdev trees.
                val rawPools = async { c.repository.call { it.rpc("pool.query") } }
                val disks = async { c.repository.call { DisksApi(it).disks() } }
                val datasets = async { runCatching { c.repository.call { it.datasets() } }.getOrDefault(emptyList()) }
                val smb = async { runCatching { c.repository.call { StorageApi(it).smbShares() } }.getOrDefault(emptyList()) }
                val nfs = async { runCatching { c.repository.call { StorageApi(it).nfsShares() } }.getOrDefault(emptyList()) }
                val poolObjs = rawPools.await().arr().orEmpty().mapNotNull { it.obj() }
                _state.value = UiState.Success(
                    StorageData(
                        poolObjs.map(Parsers::pool),
                        disks.await(),
                        datasets.await(),
                        SharesData(smb.await(), nfs.await()),
                        poolObjs.map(DisksApi::pool),
                    )
                )
            }
        } catch (e: Throwable) {
            if (_state.value !is UiState.Success) _state.value = UiState.Error(e.userMessage(), e)
        }
    }

    private fun action(key: String, success: String, block: suspend (StorageApi) -> Unit) {
        if (key in _busy.value) return
        viewModelScope.launch {
            _busy.value = _busy.value + key
            try {
                c.repository.call { block(StorageApi(it)) }
                _messages.value = success
                load()
            } catch (e: Throwable) {
                _messages.value = e.userMessage()
            } finally {
                _busy.value = _busy.value - key
            }
        }
    }

    fun createDataset(req: DatasetCreateRequest) = action("ds:${req.name}", "Dataset created") { it.createDataset(req) }
    fun renameDataset(id: String, newName: String) = action("ds:$id", "Dataset renamed") { it.renameDataset(id, newName) }
    fun deleteDataset(id: String, recursive: Boolean, force: Boolean) =
        action("ds:$id", "Dataset deleted") { it.deleteDataset(id, recursive, force) }
    fun createSmb(input: SmbShareInput) = action("smb:new", "SMB share created") { it.createSmbShare(input) }
    fun updateSmb(id: Int, input: SmbShareInput) = action("smb:$id", "SMB share saved") { it.updateSmbShare(id, input) }
    fun deleteSmb(id: Int) = action("smb:$id", "SMB share deleted") { it.deleteSmbShare(id) }
    fun createNfs(input: NfsShareInput) = action("nfs:new", "NFS share created") { it.createNfsShare(input) }
    fun updateNfs(id: Int, input: NfsShareInput) = action("nfs:$id", "NFS share saved") { it.updateNfsShare(id, input) }
    fun deleteNfs(id: Int) = action("nfs:$id", "NFS share deleted") { it.deleteNfsShare(id) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorageScreen(
    onOpenSnapshots: (String) -> Unit = {},
    onSnapshotTask: (Int?) -> Unit = {},
    onBrowse: (String) -> Unit = {},
    onOpenPool: (String) -> Unit = {},
    onOpenDisk: (String) -> Unit = {},
    onReplace: (String) -> Unit = {},
) {
    val vm = appViewModel { StorageViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val message by vm.messages.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val tabs = StorageTabs.labels
    val container = (LocalContext.current.applicationContext as TrueNasApp).container
    val request by container.storageTabRequest.collectAsStateWithLifecycle()
    LaunchedEffect(request) { request?.let { tab = it; container.storageTabRequest.value = null } }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); vm.consumeMessage() } }

    Scaffold(topBar = { TopAppBar(title = { Text("Storage") }) }, snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            PrimaryScrollableTabRow(selectedTabIndex = tab, edgePadding = 8.dp) {
                tabs.forEachIndexed { i, t -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t, maxLines = 1) }) }
            }
            if (tab == StorageTabs.PROTECTION) {
                ProtectionPane(snackbar, onSnapshotTask)
                return@Column
            }
            PullToRefreshBox(isRefreshing = refreshing, onRefresh = { vm.refresh() }, modifier = Modifier.fillMaxSize()) {
                when (val s = state) {
                    UiState.Loading -> SkeletonList(4, 110.dp)
                    is UiState.Error -> ScrollableErrorState(s.message, s.isLoginRequired) { vm.refresh() }
                    is UiState.Success -> when (tab) {
                        StorageTabs.POOLS -> PoolsList(s.data.pools, onOpenPool, onReplace)
                        StorageTabs.DISKS -> app.truenascompanion.ui.disks.DisksPane(app.truenascompanion.ui.disks.DisksData(s.data.layouts, s.data.disks), onOpenDisk)
                        StorageTabs.FILES -> app.truenascompanion.ui.files.FilesLauncher(s.data.pools, s.data.datasets, onBrowse)
                        StorageTabs.DATASETS -> DatasetsPane(
                            datasets = s.data.datasets,
                            pools = s.data.pools,
                            busy = busy,
                            onOpenSnapshots = onOpenSnapshots,
                            onCreate = vm::createDataset,
                            onRename = vm::renameDataset,
                            onDelete = vm::deleteDataset,
                            onBrowse = onBrowse,
                        )
                        else -> SharesPane(
                            data = s.data.shares,
                            datasets = s.data.datasets,
                            busy = busy,
                            onCreateSmb = vm::createSmb,
                            onUpdateSmb = vm::updateSmb,
                            onDeleteSmb = vm::deleteSmb,
                            onCreateNfs = vm::createNfs,
                            onUpdateNfs = vm::updateNfs,
                            onDeleteNfs = vm::deleteNfs,
                            onBrowse = onBrowse,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProtectionPane(snackbar: SnackbarHostState, onSnapshotTask: (Int?) -> Unit) {
    val vm = appViewModel { ProtectionViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    LaunchedEffect(vm) { vm.messages.collect { snackbar.showSnackbar(it) } }
    // Coming back from the snapshot task editor (or the app): reload quietly.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(vm) {
        var first = true
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { if (!first) vm.reload(); first = false }
    }
    PullToRefreshBox(isRefreshing = refreshing, onRefresh = { vm.refresh() }, modifier = Modifier.fillMaxSize()) {
        when (val s = state) {
            UiState.Loading -> SkeletonList(4, 110.dp)
            is UiState.Error -> ScrollableErrorState(s.message, s.isLoginRequired) { vm.refresh() }
            is UiState.Success -> ProtectionTab(s.data, vm, onAddTask = { onSnapshotTask(null) }, onEditTask = { onSnapshotTask(it) })
        }
    }
}

private val listPadding = PaddingValues(16.dp)

@Composable
private fun PoolsList(pools: List<Pool>, onOpenPool: (String) -> Unit, onReplace: (String) -> Unit) {
    if (pools.isEmpty()) {
        LazyColumn(Modifier.fillMaxSize()) { item { EmptyState(Icons.Rounded.Storage, "No pools", "Create a storage pool in the TrueNAS web UI to see it here.") } }
        return
    }
    LazyColumn(contentPadding = listPadding, verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxSize()) {
        items(pools, key = { it.id }) { PoolCard(it, onOpenPool, onReplace) }
    }
}

@Composable
private fun PoolCard(p: Pool, onOpenPool: (String) -> Unit, onReplace: (String) -> Unit) {
    var expanded by rememberSaveable(p.id) { mutableStateOf(false) }
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, label = "chevron")
    ElevatedSection(onClick = { expanded = !expanded }, modifier = Modifier.animateContentSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(Icons.Rounded.Storage, tint = LocalStatusColors.current.of(p.health))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(p.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${p.diskNames.size} disks", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(8.dp))
            StatusChip(p.health, p.status.lowercase().replaceFirstChar { it.uppercase() })
            Icon(Icons.Rounded.ExpandMore, null, Modifier.rotate(rotation))
        }
        Spacer(Modifier.height(14.dp))
        CapacityBar(p.usedFraction, height = 12.dp)
        Spacer(Modifier.height(6.dp))
        Row {
            Text("${Format.bytes(p.allocated)} used", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 1)
            Spacer(Modifier.width(8.dp))
            Text("${Format.bytes(p.free)} free", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        Expandable(expanded) {
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth()) {
                LabeledValue("Total", Format.bytes(p.size), Modifier.weight(1f))
                LabeledValue("Used", "%.0f%%".format(p.usedFraction * 100), Modifier.weight(1f))
                LabeledValue("Fragmented", p.fragmentation?.let { "$it%" } ?: "—", Modifier.weight(1f))
            }
            p.statusDetail?.let { Spacer(Modifier.height(10.dp)); Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (p.scanFunction != null) {
                Spacer(Modifier.height(10.dp))
                val func = p.scanFunction.lowercase().replaceFirstChar { it.uppercase() }
                val state = p.scanState?.lowercase() ?: ""
                val pct = p.scanPercent?.let { " · %.0f%%".format(it) } ?: ""
                Text("Last $func: $state$pct" + (p.scanErrors?.let { " · $it errors" } ?: ""), style = MaterialTheme.typography.bodySmall)
            }
            if (p.diskNames.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text("Disks: " + p.diskNames.joinToString(", "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            androidx.compose.material3.FilledTonalButton(onClick = { onOpenPool(p.name) }) { Text("Layout & disks") }
            if (!p.healthy) androidx.compose.material3.OutlinedButton(onClick = { onReplace(p.name) }) { Text("Replace a disk") }
        }
    }
}
