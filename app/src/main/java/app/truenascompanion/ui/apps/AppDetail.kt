package app.truenascompanion.ui.apps

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.automirrored.rounded.Notes
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.SettingsEthernet
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.ViewInAr
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.truenascompanion.AppContainer
import app.truenascompanion.data.api.userMessage
import app.truenascompanion.data.model.AppInfo
import app.truenascompanion.data.model.AppState
import app.truenascompanion.data.model.AppStats
import app.truenascompanion.ui.appViewModel
import app.truenascompanion.ui.components.AppIcon
import app.truenascompanion.ui.components.ElevatedSection
import app.truenascompanion.ui.components.GlowButton
import app.truenascompanion.ui.components.ScrollableErrorState
import app.truenascompanion.ui.components.SkeletonList
import app.truenascompanion.ui.components.StatusChip
import app.truenascompanion.ui.components.UiState
import app.truenascompanion.ui.components.isLoginRequired
import app.truenascompanion.ui.lock.LocalDangerGuard
import app.truenascompanion.util.Format
import app.truenascompanion.util.PortalUrls
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

class AppDetailViewModel(private val c: AppContainer, val name: String) : ViewModel() {
    private val _state = MutableStateFlow<UiState<AppInfo>>(UiState.Loading)
    val state: StateFlow<UiState<AppInfo>> = _state.asStateFlow()
    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()
    private val _rollback = MutableStateFlow<List<String>?>(null)
    val rollbackVersions = _rollback.asStateFlow()
    private val _finished = MutableStateFlow<String?>(null)
    /** Set after delete/rollback started: the screen returns to the Apps list, which shows the job's progress. */
    val finished = _finished.asStateFlow()

    /** Live stats for this app, only while the screen is visible (WhileSubscribed + lifecycle collection). */
    val stats: StateFlow<AppStats?> = c.repository.appStats()
        .map { list -> list.firstOrNull { it.app == name } }
        .catch { emit(null) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(3_000), null)

    val portalBase: String? get() = c.repository.connectedBaseUrl

    init { load() }

    fun load() = viewModelScope.launch {
        _state.value = try {
            val app = c.repository.call { it.apps() }.firstOrNull { it.name == name } ?: throw IllegalStateException("$name isn't installed any more")
            UiState.Success(app)
        } catch (e: Throwable) { UiState.Error(e.userMessage(), e) }
    }

    fun loadRollbackVersions() = viewModelScope.launch {
        try { _rollback.value = c.repository.call { it.appRollbackVersions(name) } }
        catch (e: Throwable) { _messages.trySend(e.userMessage()) }
    }

    fun dismissRollback() { _rollback.value = null }

    fun rollback(version: String, snapshot: Boolean) = viewModelScope.launch {
        _rollback.value = null
        try { c.repository.call { it.startAppRollback(name, version, snapshot) }; _finished.value = "Rolling $name back to $version…" }
        catch (e: Throwable) { _messages.trySend(e.userMessage()) }
    }

    fun delete(removeVolumes: Boolean) = viewModelScope.launch {
        try { c.repository.call { it.startAppDelete(name, removeVolumes) }; _finished.value = "Deleting $name…" }
        catch (e: Throwable) { _messages.trySend(e.userMessage()) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppDetailScreen(name: String, onBack: () -> Unit, onEdit: () -> Unit, onLogs: (String?) -> Unit, onFinished: (String) -> Unit) {
    val vm = appViewModel(key = "app-$name") { AppDetailViewModel(it, name) }
    val state by vm.state.collectAsStateWithLifecycle()
    val stats by vm.stats.collectAsStateWithLifecycle()
    val versions by vm.rollbackVersions.collectAsStateWithLifecycle()
    val finished by vm.finished.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }
    LaunchedEffect(finished) { finished?.let(onFinished) }
    val app = (state as? UiState.Success)?.data

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
                actions = {
                    if (app != null) Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "More") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            if (!app.customApp) DropdownMenuItem(text = { Text("Roll back…") }, leadingIcon = { Icon(Icons.Rounded.History, null) },
                                onClick = { menu = false; vm.loadRollbackVersions() })
                            DropdownMenuItem(text = { Text("Delete…") }, leadingIcon = { Icon(Icons.Rounded.Delete, null, tint = MaterialTheme.colorScheme.error) },
                                onClick = { menu = false; confirmDelete = true })
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (val s = state) {
                UiState.Loading -> SkeletonList(3, 120.dp)
                is UiState.Error -> ScrollableErrorState(s.message, s.isLoginRequired) { vm.load() }
                is UiState.Success -> {
                    val context = LocalContext.current
                    AppDetailContent(
                        app = s.data, stats = stats,
                        onOpenPortal = { url -> runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, PortalUrls.rewrite(url, vm.portalBase).toUri())) } },
                        onEdit = onEdit, onLogs = onLogs,
                    )
                }
            }
        }
    }
    if (confirmDelete && app != null) DeleteAppDialog(app.name, onDelete = { vols -> confirmDelete = false; vm.delete(vols) }, onDismiss = { confirmDelete = false })
    versions?.let { v -> RollbackDialog(name, app?.version, v, onRollback = vm::rollback, onDismiss = vm::dismissRollback) }
}

/** Stateless detail body (also rendered by the screenshot tests). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AppDetailContent(app: AppInfo, stats: AppStats?, onOpenPortal: (String) -> Unit, onEdit: () -> Unit, onLogs: (String?) -> Unit) {
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxSize()) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppIcon(app.iconUrl, app.name, 56.dp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(app.name, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(listOfNotNull(app.version, app.catalogName?.takeIf { it != app.name }, if (app.customApp) "Custom app" else null).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                StatusChip(app.state.health(), app.state.name.lowercase().replaceFirstChar { it.uppercase() })
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                app.portalUrl?.let { url ->
                    GlowButton(onClick = { onOpenPortal(url) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Open web UI", maxLines = 1)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = onEdit, modifier = Modifier.weight(1f).heightIn(min = 44.dp)) {
                        Icon(Icons.Rounded.Edit, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Edit", maxLines = 1)
                    }
                    OutlinedButton(onClick = { onLogs(null) }, enabled = app.containerDetails.isNotEmpty(), modifier = Modifier.weight(1f).heightIn(min = 44.dp)) {
                        Icon(Icons.AutoMirrored.Rounded.Notes, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Logs", maxLines = 1)
                    }
                }
            }
        }
        if (app.state == AppState.RUNNING) item {
            ElevatedSection(contentPadding = 14.dp) {
                Text("Resources", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(10.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp), maxItemsInEachRow = 2) {
                    StatTile(Icons.Rounded.Speed, "CPU", stats?.let { "${it.cpuPercent}%" }, Modifier.weight(1f))
                    StatTile(Icons.Rounded.Memory, "Memory", stats?.let { Format.bytes(it.memoryBytes) }, Modifier.weight(1f))
                    StatTile(Icons.Rounded.SettingsEthernet, "Network", stats?.let { "↓ ${Format.rate(it.rxBytesPerSec.toDouble())}\n↑ ${Format.rate(it.txBytesPerSec.toDouble())}" }, Modifier.weight(1f))
                    StatTile(Icons.Rounded.ViewInAr, "Disk I/O total", stats?.let { "Read ${Format.bytes(it.blkReadBytes)}\nWrite ${Format.bytes(it.blkWriteBytes)}" }, Modifier.weight(1f))
                }
            }
        }
        if (app.containerDetails.isNotEmpty()) item {
            ElevatedSection(contentPadding = 14.dp) {
                Text("Containers", style = MaterialTheme.typography.titleMedium)
                app.containerDetails.forEachIndexed { i, ctr ->
                    if (i > 0) HorizontalDivider(Modifier.padding(vertical = 6.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                    Row(Modifier.fillMaxWidth().clickable { onLogs(ctr.id) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(ctr.service, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            ctr.image?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        }
                        ctr.state?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary) }
                        Icon(Icons.AutoMirrored.Rounded.Notes, "Logs", Modifier.padding(start = 10.dp).size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        app.notes?.let { notes ->
            item {
                ElevatedSection(contentPadding = 14.dp) {
                    Text("Notes", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Text(notes.take(2000).replace(Regex("[#*`]"), "").lines().joinToString("\n") { it.trim() }.trim(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun StatTile(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, value: String?, modifier: Modifier) {
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(6.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(value ?: "—", style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun DeleteAppDialog(name: String, onDelete: (removeVolumes: Boolean) -> Unit, onDismiss: () -> Unit) {
    var volumes by remember { mutableStateOf(false) }
    val guard = LocalDangerGuard.current
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Delete, null) },
        title = { Text("Delete $name?") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("The app and its containers are removed. Data in host paths (your own datasets) is never touched.")
                Row(Modifier.fillMaxWidth().clickable { volumes = !volumes }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = volumes, onCheckedChange = { volumes = it })
                    Text("Also delete the app's data stored in ixVolumes. This can't be undone.", style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { guard.guard("Delete $name") { onDelete(volumes) } },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
            ) { Text("Delete", maxLines = 1) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun RollbackDialog(name: String, current: String?, versions: List<String>, onRollback: (String, Boolean) -> Unit, onDismiss: () -> Unit) {
    var pick by remember { mutableStateOf(versions.firstOrNull()) }
    var snapshot by remember { mutableStateOf(true) }
    val guard = LocalDangerGuard.current
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.History, null) },
        title = { Text("Roll back $name") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (versions.isEmpty()) Text("There's no earlier version to roll back to.")
                else {
                    current?.let { Text("Installed: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    versions.forEach { v ->
                        Row(Modifier.fillMaxWidth().selectable(selected = pick == v, onClick = { pick = v }).padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = pick == v, onClick = { pick = v })
                            Text(v, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Snapshot app data first", modifier = Modifier.weight(1f))
                        Switch(checked = snapshot, onCheckedChange = { snapshot = it })
                    }
                }
            }
        },
        confirmButton = {
            if (versions.isNotEmpty()) GlowButton(onClick = { pick?.let { v -> guard.guard("Roll back $name to $v") { onRollback(v, snapshot) } } }, enabled = pick != null) { Text("Roll back", maxLines = 1) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(if (versions.isEmpty()) "Close" else "Cancel") } },
    )
}

