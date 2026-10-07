package app.truenascompanion.ui.protection

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AddAPhoto
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.truenascompanion.AppContainer
import app.truenascompanion.data.api.ProtectionApi
import app.truenascompanion.data.api.userMessage
import app.truenascompanion.data.model.Health
import app.truenascompanion.data.model.Snapshot
import app.truenascompanion.ui.appViewModel
import app.truenascompanion.ui.components.ElevatedSection
import app.truenascompanion.ui.components.EmptyState
import app.truenascompanion.ui.components.ConfirmDialog
import app.truenascompanion.ui.components.ScrollableErrorState
import app.truenascompanion.ui.components.SkeletonList
import app.truenascompanion.ui.components.StatusChip
import app.truenascompanion.ui.components.UiState
import app.truenascompanion.ui.components.isLoginRequired
import app.truenascompanion.ui.lock.LocalDangerGuard
import app.truenascompanion.util.Format
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class SnapshotSort(val label: String) { NEWEST("Newest"), OLDEST("Oldest"), LARGEST("Largest") }

fun sortSnapshots(list: List<Snapshot>, query: String, sort: SnapshotSort): List<Snapshot> {
    val q = query.trim()
    val filtered = if (q.isEmpty()) list else list.filter { it.name.contains(q, ignoreCase = true) }
    return when (sort) {
        SnapshotSort.NEWEST -> filtered.sortedByDescending { it.createdMillis ?: 0L }
        SnapshotSort.OLDEST -> filtered.sortedBy { it.createdMillis ?: Long.MAX_VALUE }
        SnapshotSort.LARGEST -> filtered.sortedByDescending { it.usedBytes ?: 0L }
    }
}

/** Snapshots newer than [s] on the same dataset (a rollback destroys them). */
fun newerThan(all: List<Snapshot>, s: Snapshot): List<Snapshot> {
    val t = s.createdMillis ?: return emptyList()
    return all.filter { it.id != s.id && (it.createdMillis ?: 0L) > t }
}

fun defaultSnapshotName(now: Long = System.currentTimeMillis()): String =
    "manual-" + SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.US).format(Date(now))

private val snapNameRegex = Regex("^[A-Za-z0-9_.:\\-]+$")
fun validSnapshotName(n: String) = n.isNotBlank() && n.length <= 200 && snapNameRegex.matches(n)
private val datasetRegex = Regex("^[A-Za-z0-9_.:\\- ]+(/[A-Za-z0-9_.:\\- ]+)+$")
fun validDatasetPath(n: String) = datasetRegex.matches(n) && !n.contains("//") && n.split('/').none { it.isBlank() || it.startsWith(" ") || it.endsWith(" ") }

class SnapshotsViewModel(private val c: AppContainer, val dataset: String) : ViewModel() {
    private val _state = MutableStateFlow<UiState<List<Snapshot>>>(UiState.Loading)
    val state: StateFlow<UiState<List<Snapshot>>> = _state.asStateFlow()
    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()
    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    init { viewModelScope.launch { load() } }

    fun refresh() = viewModelScope.launch { _refreshing.value = true; load(); _refreshing.value = false }

    private suspend fun load() {
        try {
            _state.value = UiState.Success(c.repository.call { ProtectionApi(it).snapshots(dataset) })
        } catch (e: Throwable) {
            if (_state.value !is UiState.Success) _state.value = UiState.Error(e.userMessage(), e) else _messages.send(e.userMessage())
        }
    }

    private fun action(success: String, block: suspend (ProtectionApi) -> Unit) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            try {
                c.repository.call { block(ProtectionApi(it)) }
                _messages.send(success)
            } catch (e: Throwable) {
                _messages.send(e.userMessage())
            } finally {
                load()
                _busy.value = false
            }
        }
    }

    fun take(name: String, recursive: Boolean) = action("Snapshot $name taken") { it.createSnapshot(dataset, name, recursive) }
    fun delete(snaps: List<Snapshot>) = action(if (snaps.size == 1) "Snapshot deleted" else "${snaps.size} snapshots deleted") { p ->
        snaps.forEach { p.deleteSnapshot(it.id) }
    }
    fun rollback(s: Snapshot, destroyNewer: Boolean) = action("$dataset rolled back to ${s.name}") { it.rollback(s.id, destroyNewer) }
    fun clone(s: Snapshot, target: String) = action("Cloned to $target") { it.clone(s.id, target) }
    fun setHold(s: Snapshot, hold: Boolean) = action(if (hold) "Snapshot held; it won't be deleted automatically" else "Hold released") {
        if (hold) it.hold(s.id) else it.release(s.id)
    }
}

private sealed interface SDialog {
    data class Actions(val s: Snapshot) : SDialog
    data class Rollback(val s: Snapshot) : SDialog
    data class Clone(val s: Snapshot) : SDialog
    data class Delete(val list: List<Snapshot>) : SDialog
    data object Take : SDialog
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SnapshotsScreen(dataset: String, onBack: () -> Unit, onBrowse: ((String) -> Unit)? = null) {
    val vm = appViewModel(key = "snapshots:$dataset") { SnapshotsViewModel(it, dataset) }
    val state by vm.state.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(vm) { vm.messages.collect { snackbar.showSnackbar(it) } }
    var query by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableStateOf(SnapshotSort.NEWEST) }
    var selected by rememberSaveable { mutableStateOf(setOf<String>()) }
    var dialog by remember { mutableStateOf<SDialog?>(null) }
    val all = (state as? UiState.Success)?.data.orEmpty()
    val selecting = selected.isNotEmpty()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (selecting) Text("${selected.size} selected")
                    else Column {
                        Text("Snapshots", maxLines = 1)
                        Text(dataset, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
                navigationIcon = {
                    if (selecting) IconButton(onClick = { selected = emptySet() }) { Icon(Icons.Rounded.Close, "Clear selection") }
                    else IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
                },
                actions = {
                    if (!selecting && onBrowse != null) IconButton(onClick = { onBrowse(app.truenascompanion.data.api.StorageApi.pathForDataset(dataset)) }) {
                        Icon(Icons.Rounded.FolderOpen, "Browse files")
                    }
                    if (selecting) {
                        IconButton(onClick = { selected = sortSnapshots(all, query, sort).map { it.id }.toSet() }) { Icon(Icons.Rounded.SelectAll, "Select all") }
                        IconButton(onClick = { dialog = SDialog.Delete(all.filter { it.id in selected }) }, enabled = !busy) { Icon(Icons.Rounded.Delete, "Delete selected") }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            if (!selecting && state is UiState.Success) {
                ExtendedFloatingActionButton(onClick = { dialog = SDialog.Take }, icon = { Icon(Icons.Rounded.AddAPhoto, null) }, text = { Text("Take snapshot") })
            }
        },
    ) { padding ->
        PullToRefreshBox(isRefreshing = refreshing, onRefresh = { vm.refresh() }, modifier = Modifier.padding(padding).fillMaxSize()) {
            when (val s = state) {
                UiState.Loading -> SkeletonList(6, 72.dp)
                is UiState.Error -> ScrollableErrorState(s.message, s.isLoginRequired) { vm.refresh() }
                is UiState.Success -> SnapshotsContent(
                    s.data, query, { query = it }, sort, { sort = it }, selected,
                    onTap = { snap -> if (selecting) selected = selected.toggle(snap.id) else dialog = SDialog.Actions(snap) },
                    onLongPress = { snap -> selected = selected.toggle(snap.id) },
                )
            }
        }
    }

    val close = { dialog = null }
    val guard = LocalDangerGuard.current
    when (val d = dialog) {
        null -> Unit
        is SDialog.Actions -> SnapshotActionsSheet(d.s, close,
            onRollback = { dialog = SDialog.Rollback(d.s) }, onClone = { dialog = SDialog.Clone(d.s) },
            onHold = { close(); vm.setHold(d.s, !d.s.held) }, onDelete = { dialog = SDialog.Delete(listOf(d.s)) })
        is SDialog.Rollback -> RollbackDialog(dataset, d.s, newerThan(all, d.s), close) { destroy ->
            close(); guard.guard("Roll back $dataset") { vm.rollback(d.s, destroy) }
        }
        is SDialog.Clone -> CloneDialog(dataset, d.s, close) { target -> close(); vm.clone(d.s, target) }
        is SDialog.Delete -> {
            val held = d.list.count { it.held }
            ConfirmDialog(
                if (d.list.size == 1) "Delete snapshot?" else "Delete ${d.list.size} snapshots?",
                (if (d.list.size == 1) "${d.list[0].name} is deleted permanently. You can't restore files from it afterwards."
                else "These snapshots are deleted permanently. You can't restore files from them afterwards.") +
                    if (held > 0) "\n\n$held held snapshot${if (held == 1) " is" else "s are"} protected and will fail to delete until released." else "",
                "Delete", destructive = true, onConfirm = { close(); selected = emptySet(); vm.delete(d.list) }, onDismiss = close,
            )
        }
        SDialog.Take -> TakeSnapshotDialog(dataset, close) { name, rec -> close(); vm.take(name, rec) }
    }
}

private fun Set<String>.toggle(id: String) = if (id in this) this - id else this + id

fun snapshotDate(millis: Long?): String = millis?.let { SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault()).format(Date(it)) } ?: "—"

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SnapshotsContent(
    all: List<Snapshot>, query: String, onQuery: (String) -> Unit, sort: SnapshotSort, onSort: (SnapshotSort) -> Unit,
    selected: Set<String>, onTap: (Snapshot) -> Unit, onLongPress: (Snapshot) -> Unit, now: Long = System.currentTimeMillis(),
) {
    val list = sortSnapshots(all, query, sort)
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            if (all.size > 6) {
                OutlinedTextField(
                    value = query, onValueChange = onQuery, singleLine = true, leadingIcon = { Icon(Icons.Rounded.Search, null) },
                    placeholder = { Text("Search snapshots") }, modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                SnapshotSort.entries.forEach { s -> FilterChip(selected = sort == s, onClick = { onSort(s) }, label = { Text(s.label) }) }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "${all.size} snapshot${if (all.size == 1) "" else "s"} · ${Format.bytes(all.sumOf { it.usedBytes ?: 0L })} used by snapshots" +
                    if (all.isNotEmpty() && selected.isEmpty()) " · long-press to select" else "",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (all.isEmpty()) item { EmptyState(Icons.Rounded.PhotoCamera, "No snapshots yet", "Take one now, or add a periodic snapshot task under Storage › Protection.") }
        else if (list.isEmpty()) item { Text("No snapshots match \"$query\".", modifier = Modifier.padding(16.dp)) }
        items(list, key = { it.id }) { s ->
            val isSel = s.id in selected
            ElevatedSection(
                contentPadding = 14.dp,
                modifier = Modifier.combinedClickable(onClick = { onTap(s) }, onLongClick = { onLongPress(s) }),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (selected.isNotEmpty()) { Checkbox(checked = isSel, onCheckedChange = null); Spacer(Modifier.width(12.dp)) }
                    Column(Modifier.weight(1f)) {
                        Text(s.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text("${snapshotDate(s.createdMillis)} · ${Format.relativeTime(s.createdMillis, now)}",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${Format.bytes(s.usedBytes)} unique · ${Format.bytes(s.referencedBytes)} referenced",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (s.held) { Spacer(Modifier.width(8.dp)); StatusChip(Health.HEALTHY, "Held", showIcon = false) }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SnapshotActionsSheet(s: Snapshot, onDismiss: () -> Unit, onRollback: () -> Unit, onClone: () -> Unit, onHold: () -> Unit, onDelete: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(bottom = 24.dp)) {
            Text(s.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 24.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(snapshotDate(s.createdMillis), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 24.dp))
            Spacer(Modifier.height(8.dp))
            SheetItem(Icons.Rounded.History, "Roll back to this snapshot", "Restore the dataset to this point", onRollback)
            SheetItem(Icons.Rounded.ContentCopy, "Clone to a new dataset", "Browse or copy files without touching the original", onClone)
            SheetItem(if (s.held) Icons.Rounded.LockOpen else Icons.Rounded.Lock, if (s.held) "Release hold" else "Hold",
                if (s.held) "Allow it to be deleted again" else "Protect it from deletion and automatic cleanup", onHold)
            SheetItem(Icons.Rounded.Delete, "Delete", null, onDelete, danger = true)
        }
    }
}

@Composable
private fun SheetItem(icon: ImageVector, title: String, supporting: String?, onClick: () -> Unit, danger: Boolean = false) {
    val color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    ListItem(
        headlineContent = { Text(title, color = color) },
        supportingContent = supporting?.let { { Text(it) } },
        leadingContent = { Icon(icon, null, tint = if (danger) color else MaterialTheme.colorScheme.primary) },
        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
        modifier = Modifier.combinedClickable(onClick = onClick),
    )
}

/** Rollback warning (no text fields, so it renders in previews). ZFS only rolls back past newer snapshots by destroying them. */
@Composable
fun RollbackDialog(dataset: String, s: Snapshot, newer: List<Snapshot>, onDismiss: () -> Unit, onConfirm: (destroyNewer: Boolean) -> Unit) {
    var agree by rememberSaveable { mutableStateOf(false) }
    val needsDestroy = newer.isNotEmpty()
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.History, null) },
        title = { Text("Roll back $dataset?") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("$dataset goes back to how it was at ${snapshotDate(s.createdMillis)}. Every change made since then is lost. This can't be undone.")
                if (needsDestroy) {
                    Spacer(Modifier.height(12.dp))
                    Text("${newer.size} newer snapshot${if (newer.size == 1) "" else "s"} will be deleted too" +
                        " (newest: ${newer.maxBy { it.createdMillis ?: 0L }.name}).", color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(4.dp))
                    Row(Modifier.fillMaxWidth().combinedClickable(onClick = { agree = !agree }), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = agree, onCheckedChange = { agree = it })
                        Text("Delete the newer snapshots and roll back", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text("Tip: to recover only a few files, clone the snapshot instead.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(needsDestroy) }, enabled = !needsDestroy || agree,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
            ) { Text("Roll back", maxLines = 1) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun CloneDialog(dataset: String, s: Snapshot, onDismiss: () -> Unit, onClone: (String) -> Unit) {
    var target by rememberSaveable { mutableStateOf("$dataset-${s.name}".replace(Regex("[^A-Za-z0-9_.:/\\-]"), "-")) }
    val ok = validDatasetPath(target) && target != dataset
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Clone snapshot") },
        text = {
            Column {
                Text("Creates a new writable dataset from ${s.name}. The original dataset isn't changed.", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = target, onValueChange = { target = it.trim() }, singleLine = true, isError = !ok,
                    label = { Text("New dataset") }, supportingText = { Text("Full path, e.g. tank/restore") }, modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onClone(target) }, enabled = ok) { Text("Clone") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun TakeSnapshotDialog(dataset: String, onDismiss: () -> Unit, onTake: (String, Boolean) -> Unit) {
    var name by rememberSaveable { mutableStateOf(defaultSnapshotName()) }
    var recursive by rememberSaveable { mutableStateOf(false) }
    val ok = validSnapshotName(name)
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.AddAPhoto, null) },
        title = { Text("Take snapshot") },
        text = {
            Column {
                Text(dataset, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = name, onValueChange = { name = it.trim() }, singleLine = true, isError = !ok, label = { Text("Name") },
                    supportingText = { Text(if (ok) "Letters, digits, - _ . and :" else "Use only letters, digits, - _ . and :") }, modifier = Modifier.fillMaxWidth(),
                )
                SwitchRow("Include child datasets", recursive) { recursive = it }
            }
        },
        confirmButton = { TextButton(onClick = { onTake(name, recursive) }, enabled = ok) { Text("Take") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

