package app.truenascompanion.ui.jobs

import androidx.compose.animation.animateContentSize
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
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.TaskAlt
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.truenascompanion.AppContainer
import app.truenascompanion.data.api.userMessage
import app.truenascompanion.data.model.Health
import app.truenascompanion.data.model.JobInfo
import app.truenascompanion.data.model.JobState
import app.truenascompanion.ui.appViewModel
import app.truenascompanion.ui.components.ConfirmDialog
import app.truenascompanion.ui.components.ElevatedSection
import app.truenascompanion.ui.components.EmptyState
import app.truenascompanion.ui.components.IconBadge
import app.truenascompanion.ui.components.ScrollableErrorState
import app.truenascompanion.ui.components.SkeletonList
import app.truenascompanion.ui.components.StatusChip
import app.truenascompanion.ui.components.UiState
import app.truenascompanion.ui.components.isLoginRequired
import app.truenascompanion.util.Format
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import java.util.Locale

/** Friendly title for a middleware job. */
fun JobInfo.title(): String {
    val arg = firstArgument
    return when (method) {
        "app.upgrade" -> "Upgrade ${arg ?: "app"}"
        "app.start" -> "Start ${arg ?: "app"}"
        "app.stop" -> "Stop ${arg ?: "app"}"
        "app.redeploy" -> "Redeploy ${arg ?: "app"}"
        "app.create" -> "Install app"
        "app.delete" -> "Delete ${arg ?: "app"}"
        "app.update" -> "Update ${arg ?: "app"} settings"
        "app.pull_images" -> "Pull images for ${arg ?: "app"}"
        "catalog.sync" -> "Sync app catalog"
        "pool.scrub.scrub", "pool.scrub.run" -> "Scrub ${arg ?: "pool"}"
        "system.reboot" -> "Reboot"
        "system.shutdown" -> "Shut down"
        "service.control" -> "Service ${arg?.lowercase() ?: "control"}"
        "update.download" -> "Download system update"
        "update.run", "update.update" -> "Apply system update"
        "replication.run" -> "Replication"
        "cloudsync.sync" -> "Cloud sync"
        "rsynctask.run" -> "Rsync task"
        "pool.snapshottask.run" -> "Snapshot task"
        "core.bulk" -> description ?: "Bulk operation"
        else -> description?.takeIf { it.isNotBlank() } ?: method
    }
}

fun JobState.health(): Health = when (this) {
    JobState.SUCCESS -> Health.HEALTHY
    JobState.WAITING, JobState.RUNNING -> Health.WARNING
    JobState.FAILED -> Health.CRITICAL
    JobState.ABORTED, JobState.UNKNOWN -> Health.UNKNOWN
}

class JobsViewModel(private val c: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow<UiState<List<JobInfo>>>(UiState.Loading)
    val state: StateFlow<UiState<List<JobInfo>>> = _state.asStateFlow()
    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()
    private var collector: Job? = null

    init {
        viewModelScope.launch { c.repository.reloadKey.collect { if (it != null) start() } }
    }

    fun start() {
        collector?.cancel()
        _state.value = UiState.Loading
        collector = viewModelScope.launch {
            c.repository.jobs()
                .catch { e -> _state.value = UiState.Error(e.userMessage(), e) }
                .collect { _state.value = UiState.Success(it) }
        }
    }

    fun abort(job: JobInfo) = viewModelScope.launch {
        try {
            c.repository.call { it.abortJob(job.id) }
            _messages.trySend("Abort requested for “${job.title()}”")
        } catch (e: Throwable) {
            _messages.trySend(e.userMessage())
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JobsScreen(onBack: () -> Unit) {
    val vm = appViewModel { JobsViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var activeOnly by rememberSaveable { mutableStateOf(false) }
    var confirmAbort by remember { mutableStateOf<JobInfo?>(null) }
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Tasks") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            when (val s = state) {
                UiState.Loading -> SkeletonList(6, 84.dp)
                is UiState.Error -> ScrollableErrorState(s.message, s.isLoginRequired) { vm.start() }
                is UiState.Success -> {
                    val active = s.data.count { it.state.active }
                    val shown = if (activeOnly) s.data.filter { it.state.active } else s.data
                    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxSize()) {
                        item {
                            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                                SegmentedButton(selected = !activeOnly, onClick = { activeOnly = false }, shape = SegmentedButtonDefaults.itemShape(0, 2), icon = {}) { Text("Recent", maxLines = 1) }
                                SegmentedButton(selected = activeOnly, onClick = { activeOnly = true }, shape = SegmentedButtonDefaults.itemShape(1, 2), icon = {}) {
                                    Text(if (active > 0) "Running ($active)" else "Running")
                                }
                            }
                        }
                        item {
                            Text(
                                "Live view of long-running TrueNAS jobs: app upgrades, scrubs, catalog syncs, updates and more.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 4.dp),
                            )
                        }
                        if (shown.isEmpty()) {
                            item {
                                EmptyState(Icons.Rounded.TaskAlt, if (activeOnly) "Nothing running" else "No recent tasks",
                                    "Jobs started on the server (from this app or the web UI) show up here with live progress.")
                            }
                        }
                        items(shown, key = { it.id }) { job ->
                            JobCard(job, onAbort = { confirmAbort = job }, modifier = Modifier.animateItem())
                        }
                    }
                }
            }
        }
    }
    confirmAbort?.let { job ->
        ConfirmDialog(
            title = "Abort “${job.title()}”?",
            text = "TrueNAS will try to stop this task. Work already done may not be rolled back.",
            confirmLabel = "Abort", destructive = true,
            onConfirm = { vm.abort(job); confirmAbort = null }, onDismiss = { confirmAbort = null },
        )
    }
}

@Composable
fun JobProgress(job: JobInfo, modifier: Modifier = Modifier) {
    Column(modifier) {
        val pct = job.percent
        if (pct != null && job.state == JobState.RUNNING && pct > 0) {
            LinearProgressIndicator(progress = { (pct / 100.0).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
        } else {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        val label = listOfNotNull(
            job.progressText,
            pct?.takeIf { it > 0 }?.let { String.format(Locale.US, "%.0f%%", it) },
        ).joinToString(" · ")
        if (label.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun JobCard(job: JobInfo, onAbort: () -> Unit, modifier: Modifier = Modifier) {
    ElevatedSection(modifier = modifier.animateContentSize(), contentPadding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val (icon, tint) = when (job.state) {
                JobState.SUCCESS -> Icons.Rounded.CheckCircle to MaterialTheme.colorScheme.primary
                JobState.FAILED -> Icons.Rounded.ErrorOutline to MaterialTheme.colorScheme.error
                JobState.RUNNING -> Icons.Rounded.Sync to MaterialTheme.colorScheme.tertiary
                JobState.WAITING -> Icons.Rounded.HourglassTop to MaterialTheme.colorScheme.tertiary
                else -> Icons.Rounded.Close to MaterialTheme.colorScheme.onSurfaceVariant
            }
            IconBadge(icon, tint = tint)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(job.title(), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val time = job.finishedMillis ?: job.startedMillis
                Text(
                    listOfNotNull("#${job.id}", job.method, time?.let { Format.relativeTime(it) }).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            StatusChip(job.state.health(), job.state.name.lowercase().replaceFirstChar { it.uppercase() }, showIcon = false)
        }
        if (job.state.active) {
            Spacer(Modifier.height(10.dp))
            JobProgress(job)
            if (job.abortable) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onAbort) { Text("Abort") }
                }
            }
        } else if (job.state == JobState.FAILED && job.error != null) {
            Spacer(Modifier.height(8.dp))
            Text(job.error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, maxLines = 4, overflow = TextOverflow.Ellipsis)
        }
    }
}
