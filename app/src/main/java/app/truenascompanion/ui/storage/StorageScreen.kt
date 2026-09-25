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
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.truenascompanion.AppContainer
import app.truenascompanion.data.api.userMessage
import app.truenascompanion.data.model.Dataset
import app.truenascompanion.data.model.Disk
import app.truenascompanion.data.model.Health
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
import app.truenascompanion.ui.dashboard.diskTempHealth
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

data class StorageData(val pools: List<Pool>, val disks: List<Disk>, val datasets: List<Dataset>)

class StorageViewModel(private val c: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow<UiState<StorageData>>(UiState.Loading)
    val state: StateFlow<UiState<StorageData>> = _state.asStateFlow()
    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    init {
        viewModelScope.launch {
            c.repository.reloadKey.collect { if (it != null) { _state.value = UiState.Loading; load() } }
        }
    }

    fun refresh() = viewModelScope.launch { _refreshing.value = true; load(); _refreshing.value = false }

    private suspend fun load() {
        try {
            coroutineScope {
                val pools = async { c.repository.call { it.pools() } }
                val disks = async { c.repository.call { it.disks() } }
                val datasets = async { runCatching { c.repository.call { it.datasets() } }.getOrDefault(emptyList()) }
                val diskList = disks.await()
                val temps = runCatching { c.repository.call { it.diskTemperatures(diskList.map { d -> d.name }) } }.getOrDefault(emptyMap())
                _state.value = UiState.Success(
                    StorageData(pools.await(), diskList.map { it.copy(temperatureC = temps[it.name]) }, datasets.await())
                )
            }
        } catch (e: Throwable) {
            if (_state.value !is UiState.Success) _state.value = UiState.Error(e.userMessage(), e)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorageScreen() {
    val vm = appViewModel { StorageViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val tabs = listOf("Pools", "Disks", "Datasets")

    Scaffold(topBar = { TopAppBar(title = { Text("Storage") }) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            PrimaryTabRow(selectedTabIndex = tab) {
                tabs.forEachIndexed { i, t -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) }) }
            }
            PullToRefreshBox(isRefreshing = refreshing, onRefresh = { vm.refresh() }, modifier = Modifier.fillMaxSize()) {
                when (val s = state) {
                    UiState.Loading -> SkeletonList(4, 110.dp)
                    is UiState.Error -> ScrollableErrorState(s.message, s.isLoginRequired) { vm.refresh() }
                    is UiState.Success -> when (tab) {
                        0 -> PoolsList(s.data.pools)
                        1 -> DisksList(s.data.disks)
                        else -> DatasetsList(s.data.datasets)
                    }
                }
            }
        }
    }
}

private val listPadding = PaddingValues(16.dp)

@Composable
private fun PoolsList(pools: List<Pool>) {
    if (pools.isEmpty()) {
        LazyColumn(Modifier.fillMaxSize()) { item { EmptyState(Icons.Rounded.Storage, "No pools", "Create a storage pool in the TrueNAS web UI to see it here.") } }
        return
    }
    LazyColumn(contentPadding = listPadding, verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxSize()) {
        items(pools, key = { it.id }) { PoolCard(it) }
    }
}

@Composable
private fun PoolCard(p: Pool) {
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
    }
}

@Composable
private fun DisksList(disks: List<Disk>) {
    if (disks.isEmpty()) {
        LazyColumn(Modifier.fillMaxSize()) { item { EmptyState(Icons.Rounded.Album, "No disks", "No disks were reported by the server.") } }
        return
    }
    LazyColumn(contentPadding = listPadding, verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxSize()) {
        items(disks, key = { it.name }) { d ->
            ElevatedSection(contentPadding = 14.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val ssd = d.type.equals("SSD", true) || d.name.startsWith("nvme")
                    IconBadge(if (ssd) Icons.Rounded.Memory else Icons.Rounded.Album)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(d.name, style = MaterialTheme.typography.titleMedium)
                        Text(
                            listOfNotNull(d.model, Format.bytes(d.size), d.pool?.let { "pool $it" } ?: "unassigned").joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    if (d.temperatureC != null) StatusChip(diskTempHealth(d.temperatureC), Format.temp(d.temperatureC))
                    else StatusChip(Health.UNKNOWN, "—", showIcon = false)
                }
            }
        }
    }
}

@Composable
private fun DatasetsList(all: List<Dataset>) {
    var showSystem by rememberSaveable { mutableStateOf(false) }
    val list = if (showSystem) all else all.filterNot { it.isSystem }
    LazyColumn(contentPadding = listPadding, verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxSize()) {
        item {
            FilterChip(selected = showSystem, onClick = { showSystem = !showSystem }, label = { Text("Show system datasets") })
        }
        if (list.isEmpty()) item { EmptyState(Icons.Rounded.Folder, "No datasets", "Datasets you create in TrueNAS show up here.") }
        items(list, key = { it.id }) { d ->
            ElevatedSection(contentPadding = 14.dp, modifier = Modifier.padding(start = (d.depth.coerceAtMost(4) * 12).dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (d.locked) Icons.Rounded.Lock else Icons.Rounded.Folder, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(if (d.depth == 0) d.id else d.shortName, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(d.id, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (d.encrypted) StatusChip(if (d.locked) Health.WARNING else Health.HEALTHY, if (d.locked) "Locked" else "Encrypted", showIcon = false)
                }
                Spacer(Modifier.height(10.dp))
                CapacityBar(d.usedFraction, height = 8.dp)
                Spacer(Modifier.height(4.dp))
                Text("${Format.bytes(d.used)} used · ${Format.bytes(d.available)} available", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
