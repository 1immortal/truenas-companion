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
import app.truenascompanion.data.model.Health
import app.truenascompanion.ui.appViewModel
import app.truenascompanion.ui.components.ConfirmDialog
import app.truenascompanion.ui.components.ElevatedSection
import app.truenascompanion.ui.components.EmptyState
import app.truenascompanion.ui.components.LetterAvatar
import app.truenascompanion.ui.components.ScrollableErrorState
import app.truenascompanion.ui.components.SkeletonList
import app.truenascompanion.ui.components.StatusChip
import app.truenascompanion.ui.components.UiState
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class AppsViewModel(private val c: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow<UiState<List<AppInfo>>>(UiState.Loading)
    val state: StateFlow<UiState<List<AppInfo>>> = _state.asStateFlow()
    private val _refreshing = MutableStateFlow(false)
    val refreshing = _refreshing.asStateFlow()
    private val _busy = MutableStateFlow<Map<String, AppAction>>(emptyMap())
    val busy = _busy.asStateFlow()
    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    init {
        viewModelScope.launch {
            c.repository.activeServer.map { it?.id }.distinctUntilChanged().collect { if (it != null) { _state.value = UiState.Loading; load() } }
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
}

fun AppState.health(): Health = when (this) {
    AppState.RUNNING -> Health.HEALTHY
    AppState.DEPLOYING, AppState.STOPPING -> Health.WARNING
    AppState.CRASHED -> Health.CRITICAL
    AppState.STOPPED, AppState.UNKNOWN -> Health.UNKNOWN
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppsScreen() {
    val vm = appViewModel { AppsViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var confirm by remember { mutableStateOf<Pair<AppInfo, AppAction>?>(null) }
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }

    Scaffold(topBar = { TopAppBar(title = { Text("Apps") }) }, snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        PullToRefreshBox(isRefreshing = refreshing, onRefresh = { vm.refresh() }, modifier = Modifier.padding(padding).fillMaxSize()) {
            when (val s = state) {
                UiState.Loading -> SkeletonList(6, 88.dp)
                is UiState.Error -> ScrollableErrorState(s.message) { vm.refresh() }
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
                                val updates = apps.count { it.upgradeAvailable || it.imageUpdatesAvailable }
                                Text(
                                    "${apps.size} apps · $running running" + if (updates > 0) " · $updates with updates" else "",
                                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
                                )
                            }
                            items(apps, key = { it.name }) { app ->
                                AppCard(app, busy[app.name], onAction = { action ->
                                    if (action == AppAction.START) vm.act(app, action) else confirm = app to action
                                }, modifier = Modifier.animateItem())
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
}

@Composable
private fun AppCard(app: AppInfo, busyAction: AppAction?, onAction: (AppAction) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    ElevatedSection(modifier = modifier.animateContentSize(), contentPadding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LetterAvatar(app.name)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(app.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(app.version ?: "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            StatusChip(app.state.health(), app.state.name.lowercase().replaceFirstChar { it.uppercase() })
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "More actions") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Restart") }, leadingIcon = { Icon(Icons.Rounded.RestartAlt, null) },
                        enabled = busyAction == null && app.state == AppState.RUNNING, onClick = { menu = false; onAction(AppAction.RESTART) })
                    DropdownMenuItem(text = { Text("Redeploy (pull images)") }, leadingIcon = { Icon(Icons.Rounded.CloudSync, null) },
                        enabled = busyAction == null, onClick = { menu = false; onAction(AppAction.REDEPLOY) })
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
        AnimatedVisibility(app.upgradeAvailable || app.imageUpdatesAvailable) {
            Row(Modifier.padding(top = 10.dp)) {
                StatusChip(Health.WARNING, if (app.upgradeAvailable) "Update available" else "New image available")
            }
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
            if (app.upgradeAvailable) {
                Spacer(Modifier.weight(1f))
                Icon(Icons.Rounded.SystemUpdate, "Upgrade in the TrueNAS web UI", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
