package app.truenascompanion.ui.apps

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.ListAlt
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import app.truenascompanion.ui.jobs.JobProgress
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.truenascompanion.AppContainer
import app.truenascompanion.data.api.userMessage
import app.truenascompanion.data.model.AppAction
import app.truenascompanion.data.model.AppInfo
import app.truenascompanion.data.model.AppState
import app.truenascompanion.data.model.AppUpgradeSummary
import app.truenascompanion.data.model.JobInfo
import app.truenascompanion.data.model.JobState
import app.truenascompanion.data.model.Health
import app.truenascompanion.ui.appViewModel
import app.truenascompanion.ui.components.ConfirmDialog
import app.truenascompanion.ui.components.ElevatedSection
import app.truenascompanion.ui.components.EmptyState
import app.truenascompanion.ui.components.LetterAvatar
import app.truenascompanion.ui.components.ScrollableErrorState
import app.truenascompanion.ui.components.isLoginRequired
import app.truenascompanion.ui.components.SkeletonList
import app.truenascompanion.ui.components.StatusChip
import app.truenascompanion.ui.components.UiState
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class UpgradeDialog(val app: AppInfo, val summary: AppUpgradeSummary? = null, val loading: Boolean = true, val error: String? = null)

class AppsViewModel(private val c: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow<UiState<List<AppInfo>>>(UiState.Loading)
    val state: StateFlow<UiState<List<AppInfo>>> = _state.asStateFlow()
    private val _refreshing = MutableStateFlow(false)
    val refreshing = _refreshing.asStateFlow()
    private val _busy = MutableStateFlow<Map<String, AppAction>>(emptyMap())
    val busy = _busy.asStateFlow()
    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    /** Active `app.upgrade` jobs keyed by app name (also those started from the web UI). */
    private val _upgradeJobs = MutableStateFlow<Map<String, JobInfo>>(emptyMap())
    val upgradeJobs = _upgradeJobs.asStateFlow()
    /** Apps whose upgrade was requested but whose job hasn't shown up yet. */
    private val _pendingUpgrades = MutableStateFlow<Set<String>>(emptySet())
    val pendingUpgrades = _pendingUpgrades.asStateFlow()
    private val _catalogSync = MutableStateFlow<JobInfo?>(null)
    val catalogSync = _catalogSync.asStateFlow()
    private val _syncStarting = MutableStateFlow(false)
    val syncStarting = _syncStarting.asStateFlow()
    private val _dialog = MutableStateFlow<UpgradeDialog?>(null)
    val dialog = _dialog.asStateFlow()
    private val _activeJobs = MutableStateFlow(0)
    val activeJobs = _activeJobs.asStateFlow()

    private var jobsCollector: Job? = null
    private val watched = mutableMapOf<Long, JobInfo>() // active jobs we report on when they finish
    private var syncJobId: Long? = null

    init {
        viewModelScope.launch {
            c.repository.reloadKey.collect {
                if (it != null) { _state.value = UiState.Loading; load(); watchJobs() }
            }
        }
    }

    fun refresh() = viewModelScope.launch { _refreshing.value = true; load(); _refreshing.value = false }

    private suspend fun load() {
        try {
            _state.value = UiState.Success(c.repository.call { it.apps() })
        } catch (e: Throwable) {
            if (_state.value !is UiState.Success) _state.value = UiState.Error(e.userMessage(), e) else _messages.trySend(e.userMessage())
        }
    }

    private fun watchJobs() {
        jobsCollector?.cancel()
        watched.clear()
        jobsCollector = viewModelScope.launch {
            c.repository.jobs().catch { /* REST API / no permission: upgrade progress just isn't live */ }.collect { jobs -> onJobs(jobs) }
        }
    }

    private suspend fun onJobs(jobs: List<JobInfo>) {
        _activeJobs.value = jobs.count { it.state.active }
        val upgrades = jobs.filter { it.method == "app.upgrade" && it.state.active && it.firstArgument != null }
        _upgradeJobs.value = upgrades.associateBy { it.firstArgument!! }
        _pendingUpgrades.update { pending -> pending - upgrades.mapNotNull { it.firstArgument }.toSet() }
        _catalogSync.value = jobs.firstOrNull { it.method == "catalog.sync" && it.state.active }
        if (_catalogSync.value != null) _syncStarting.value = false

        var reload = false
        for (job in jobs) {
            if (job.state.active && (job.method == "app.upgrade" || job.id == syncJobId)) watched[job.id] = job
            else if (watched.remove(job.id) != null) {
                reload = true
                val msg = when {
                    job.method == "app.upgrade" && job.state == JobState.SUCCESS -> "${job.firstArgument} upgraded successfully"
                    job.method == "app.upgrade" -> "Upgrade of ${job.firstArgument} ${job.state.name.lowercase()}" + (job.error?.let { ": $it" } ?: "")
                    job.state == JobState.SUCCESS -> null // catalog sync: summarised after reload
                    else -> "Catalog sync ${job.state.name.lowercase()}" + (job.error?.let { ": $it" } ?: "")
                }
                msg?.let { _messages.trySend(it) }
            }
        }
        if (reload) {
            load()
            val finishedSync = syncJobId?.let { id -> jobs.firstOrNull { it.id == id && it.state == JobState.SUCCESS } }
            if (finishedSync != null) {
                syncJobId = null
                val n = (_state.value as? UiState.Success)?.data?.count { it.upgradeAvailable } ?: 0
                _messages.trySend(if (n == 0) "Catalog synced · all apps are up to date" else "Catalog synced · $n app update${if (n == 1) "" else "s"} available")
            }
        }
    }

    fun act(app: AppInfo, action: AppAction) {
        if (app.name in _busy.value) return
        _busy.update { it + (app.name to action) }
        viewModelScope.launch {
            try {
                c.repository.call { it.appAction(app, action) }
                _messages.trySend("${app.name}: ${action.label.lowercase()} done")
            } catch (e: Throwable) {
                _messages.trySend("${app.name}: ${e.userMessage()}")
            } finally {
                _busy.update { it - app.name }
                load()
            }
        }
    }

    /** Opens the upgrade confirmation and loads `app.upgrade_summary` (target version + changelog). */
    fun requestUpgrade(app: AppInfo) {
        _dialog.value = UpgradeDialog(app)
        viewModelScope.launch {
            val result = runCatching { c.repository.call { it.appUpgradeSummary(app) } }
            _dialog.update { d ->
                if (d?.app?.name != app.name) d
                else result.fold({ d.copy(summary = it, loading = false) }, { d.copy(loading = false, error = it.userMessage()) })
            }
        }
    }

    fun dismissDialog() { _dialog.value = null }

    fun upgrade(apps: List<AppInfo>, snapshotHostPaths: Boolean) {
        _dialog.value = null
        val names = apps.map { it.name }.filterNot { it in _upgradeJobs.value || it in _pendingUpgrades.value }
        if (names.isEmpty()) return
        _pendingUpgrades.update { it + names }
        viewModelScope.launch {
            // Start one job per app (the documented `app.upgrade` job); TrueNAS runs/queues them server-side.
            for (app in apps.filter { it.name in names }) {
                try {
                    val id = c.repository.call { it.startAppUpgrade(app, snapshotHostPaths) }
                    watched.putIfAbsent(id, placeholderJob(id, app.name))
                } catch (e: Throwable) {
                    _pendingUpgrades.update { it - app.name }
                    _messages.trySend("${app.name}: ${e.userMessage()}")
                }
            }
            if (jobsCollector?.isActive != true) { _pendingUpgrades.value = emptySet(); load() }
        }
    }

    fun checkForUpdates() {
        if (_syncStarting.value || _catalogSync.value != null) return
        _syncStarting.value = true
        viewModelScope.launch {
            try {
                val id = c.repository.call { it.startCatalogSync() }
                syncJobId = id
                watched[id] = placeholderJob(id, null, "catalog.sync")
                _messages.trySend("Checking the catalog for app updates…")
                if (jobsCollector?.isActive != true) { _syncStarting.value = false; load() }
            } catch (e: Throwable) {
                _syncStarting.value = false
                _messages.trySend(e.userMessage())
            }
        }
    }

    private fun placeholderJob(id: Long, app: String?, method: String = "app.upgrade") = JobInfo(
        id = id, method = method, firstArgument = app, description = null, state = JobState.WAITING, percent = null,
        progressText = null, error = null, abortable = false, startedMillis = null, finishedMillis = null,
    )
}

fun AppState.health(): Health = when (this) {
    AppState.RUNNING -> Health.HEALTHY
    AppState.DEPLOYING, AppState.STOPPING -> Health.WARNING
    AppState.CRASHED -> Health.CRITICAL
    AppState.STOPPED, AppState.UNKNOWN -> Health.UNKNOWN
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppsScreen(onJobs: () -> Unit = {}) {
    val vm = appViewModel { AppsViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val upgradeJobs by vm.upgradeJobs.collectAsStateWithLifecycle()
    val pending by vm.pendingUpgrades.collectAsStateWithLifecycle()
    val catalogSync by vm.catalogSync.collectAsStateWithLifecycle()
    val syncStarting by vm.syncStarting.collectAsStateWithLifecycle()
    val dialog by vm.dialog.collectAsStateWithLifecycle()
    val activeJobs by vm.activeJobs.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var confirm by remember { mutableStateOf<Pair<AppInfo, AppAction>?>(null) }
    var confirmAll by remember { mutableStateOf<List<AppInfo>?>(null) }
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }
    val syncing = syncStarting || catalogSync != null

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Apps") },
                actions = {
                    IconButton(onClick = { vm.checkForUpdates() }, enabled = !syncing) {
                        if (syncing) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Rounded.Refresh, "Check for app updates")
                    }
                    IconButton(onClick = onJobs) {
                        BadgedBox(badge = { if (activeJobs > 0) Badge { Text("$activeJobs") } }) {
                            Icon(Icons.AutoMirrored.Rounded.ListAlt, "Tasks")
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        PullToRefreshBox(isRefreshing = refreshing, onRefresh = { vm.refresh() }, modifier = Modifier.padding(padding).fillMaxSize()) {
            when (val s = state) {
                UiState.Loading -> SkeletonList(6, 88.dp)
                is UiState.Error -> ScrollableErrorState(s.message, s.isLoginRequired) { vm.refresh() }
                is UiState.Success -> {
                    val apps = s.data
                    if (apps.isEmpty()) {
                        LazyColumn(Modifier.fillMaxSize()) {
                            item { EmptyState(Icons.Rounded.Apps, "No apps installed", "Apps you install from the TrueNAS catalog will appear here.") }
                        }
                    } else {
                        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxSize()) {
                            item {
                                val running = apps.count { it.state == AppState.RUNNING }
                                Text(
                                    "${apps.size} apps · $running running",
                                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
                                )
                            }
                            catalogSync?.let { job ->
                                item(key = "catalog-sync") {
                                    ElevatedSection(contentPadding = 14.dp) {
                                        Text("Checking the catalog for updates…", style = MaterialTheme.typography.titleSmall)
                                        Spacer(Modifier.height(8.dp))
                                        JobProgress(job)
                                    }
                                }
                            }
                            val upgradable = apps.filter { it.upgradeAvailable && !it.legacyChart }
                            if (upgradable.isNotEmpty()) {
                                item(key = "updates-banner") {
                                    UpdatesBanner(
                                        count = upgradable.size,
                                        allBusy = upgradable.all { it.name in upgradeJobs || it.name in pending },
                                        onUpgradeAll = { confirmAll = upgradable },
                                    )
                                }
                            }
                            items(apps, key = { it.name }) { app ->
                                AppCard(
                                    app, busy[app.name],
                                    upgradeJob = upgradeJobs[app.name],
                                    upgradePending = app.name in pending,
                                    onAction = { action -> if (action == AppAction.START) vm.act(app, action) else confirm = app to action },
                                    onUpgrade = { vm.requestUpgrade(app) },
                                    modifier = Modifier.animateItem(),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    confirm?.let { (app, action) ->
        ConfirmDialog(
            title = "${action.label} ${app.name}?",
            text = when (action) {
                AppAction.STOP -> "The app's containers will be stopped until you start it again."
                AppAction.RESTART -> "The app will be stopped and started again. It will be briefly unavailable."
                AppAction.REDEPLOY -> "TrueNAS will stop the app, pull the latest images for its current version and start it again."
                AppAction.START -> ""
            },
            confirmLabel = action.label, destructive = action == AppAction.STOP,
            onConfirm = { vm.act(app, action); confirm = null }, onDismiss = { confirm = null },
        )
    }
    dialog?.let { d -> UpgradeConfirmDialog(d, onConfirm = { snap -> vm.upgrade(listOf(d.app), snap) }, onDismiss = { vm.dismissDialog() }) }
    confirmAll?.let { list ->
        UpgradeAllDialog(list, onConfirm = { snap -> vm.upgrade(list, snap); confirmAll = null }, onDismiss = { confirmAll = null })
    }
}

@Composable
private fun UpdatesBanner(count: Int, allBusy: Boolean, onUpgradeAll: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        shape = MaterialTheme.shapes.large,
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.SystemUpdate, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "$count app update${if (count == 1) "" else "s"} available",
                    style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text("New versions from the TrueNAS catalog", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f))
            }
            if (count > 1) Button(onClick = onUpgradeAll, enabled = !allBusy) { Text("Upgrade all") }
        }
    }
}

@Composable
private fun SnapshotOption(checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).toggleable(checked, onValueChange = onChange, role = Role.Checkbox).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Spacer(Modifier.width(8.dp))
        Column {
            Text("Snapshot host paths first", style = MaterialTheme.typography.bodyMedium)
            Text("Creates ZFS snapshots of host-path volumes so you can roll back data.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun UpgradeConfirmDialog(d: UpgradeDialog, onConfirm: (Boolean) -> Unit, onDismiss: () -> Unit) {
    var snapshot by rememberSaveable { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.SystemUpdate, null) },
        title = { Text("Upgrade ${d.app.name}?") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                val target = d.summary?.targetVersion ?: d.app.latestVersion
                VersionLine(d.app.version, target)
                Spacer(Modifier.height(12.dp))
                when {
                    d.loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("Loading release notes…", style = MaterialTheme.typography.bodySmall)
                    }
                    d.error != null -> Text("Couldn't load release notes: ${d.error}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    d.summary?.changelog != null -> {
                        Text("What's new", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(4.dp))
                        Text(d.summary.changelog, style = MaterialTheme.typography.bodySmall, maxLines = 30, overflow = TextOverflow.Ellipsis)
                    }
                    else -> Text("No release notes provided.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(12.dp))
                Text("The app will restart during the upgrade and may be unavailable for a moment. Progress shows up in Tasks.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                SnapshotOption(snapshot) { snapshot = it }
            }
        },
        confirmButton = { Button(onClick = { onConfirm(snapshot) }) { Text("Upgrade") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun UpgradeAllDialog(apps: List<AppInfo>, onConfirm: (Boolean) -> Unit, onDismiss: () -> Unit) {
    var snapshot by rememberSaveable { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.SystemUpdate, null) },
        title = { Text("Upgrade ${apps.size} apps?") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                apps.forEach { app ->
                    Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(app.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            listOfNotNull(app.version, app.latestVersion).joinToString(" → "),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text("Each app is upgraded to its latest catalog version as a separate task and restarts during its upgrade.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                SnapshotOption(snapshot) { snapshot = it }
            }
        },
        confirmButton = { Button(onClick = { onConfirm(snapshot) }) { Text("Upgrade all") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun VersionLine(current: String?, target: String?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest, shape = MaterialTheme.shapes.small) {
            Text(current ?: "current", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
        }
        Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, Modifier.padding(horizontal = 8.dp).size(18.dp))
        Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.small) {
            Text(target ?: "latest", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
        }
    }
}

@Composable
private fun AppCard(
    app: AppInfo,
    busyAction: AppAction?,
    upgradeJob: JobInfo?,
    upgradePending: Boolean,
    onAction: (AppAction) -> Unit,
    onUpgrade: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    val upgrading = upgradeJob != null || upgradePending
    ElevatedSection(modifier = modifier.animateContentSize(), contentPadding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LetterAvatar(app.name)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(app.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val versionText = if (app.upgradeAvailable && app.latestVersion != null) "${app.version ?: "?"} → ${app.latestVersion}" else app.version ?: ""
                Text(versionText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            StatusChip(app.state.health(), app.state.name.lowercase().replaceFirstChar { it.uppercase() })
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "More actions") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (app.upgradeAvailable && !app.legacyChart) {
                        DropdownMenuItem(text = { Text("Upgrade…") }, leadingIcon = { Icon(Icons.Rounded.SystemUpdate, null) },
                            enabled = !upgrading && busyAction == null, onClick = { menu = false; onUpgrade() })
                    }
                    DropdownMenuItem(text = { Text("Restart") }, leadingIcon = { Icon(Icons.Rounded.RestartAlt, null) },
                        enabled = busyAction == null && !upgrading && app.state == AppState.RUNNING, onClick = { menu = false; onAction(AppAction.RESTART) })
                    DropdownMenuItem(text = { Text("Redeploy (pull images)") }, leadingIcon = { Icon(Icons.Rounded.CloudSync, null) },
                        enabled = busyAction == null && !upgrading, onClick = { menu = false; onAction(AppAction.REDEPLOY) })
                    app.portalUrl?.let { url ->
                        DropdownMenuItem(text = { Text("Open web UI") }, leadingIcon = { Icon(Icons.AutoMirrored.Rounded.OpenInNew, null) },
                            onClick = {
                                menu = false
                                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) }
                            })
                    }
                }
            }
        }
        AnimatedVisibility(!upgrading && (app.upgradeAvailable || app.imageUpdatesAvailable)) {
            Row(Modifier.padding(top = 10.dp)) {
                StatusChip(Health.WARNING, if (app.upgradeAvailable) "Update available" else "New image available · use Redeploy")
            }
        }
        if (upgrading) {
            Spacer(Modifier.height(12.dp))
            Text(
                if (upgradeJob == null) "Starting upgrade…" else "Upgrading" + (app.latestVersion?.let { " to $it" } ?: "") + "…",
                style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(6.dp))
            if (upgradeJob != null) JobProgress(upgradeJob) else LinearProgressIndicator(Modifier.fillMaxWidth())
            return@ElevatedSection
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (busyAction != null) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Text("${busyAction.label}ing…".replace("Stoping", "Stopping"), style = MaterialTheme.typography.bodyMedium)
            } else if (app.state == AppState.RUNNING || app.state == AppState.DEPLOYING) {
                OutlinedButton(onClick = { onAction(AppAction.STOP) }) { Icon(Icons.Rounded.Stop, null); Spacer(Modifier.width(6.dp)); Text("Stop") }
                FilledTonalButton(onClick = { onAction(AppAction.RESTART) }, enabled = app.state == AppState.RUNNING) {
                    Icon(Icons.Rounded.RestartAlt, null); Spacer(Modifier.width(6.dp)); Text("Restart")
                }
            } else {
                FilledTonalButton(onClick = { onAction(AppAction.START) }) { Icon(Icons.Rounded.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text("Start") }
            }
            if (busyAction == null && app.upgradeAvailable && !app.legacyChart) {
                Spacer(Modifier.weight(1f))
                Button(onClick = onUpgrade, contentPadding = PaddingValues(horizontal = 14.dp)) {
                    Icon(Icons.Rounded.SystemUpdate, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Upgrade")
                }
            }
        }
    }
}
