package app.truenascompanion.ui.tasks

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.EventRepeat
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.RocketLaunch
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.WbTwilight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.truenascompanion.AppContainer
import app.truenascompanion.data.api.TasksApi
import app.truenascompanion.data.api.userMessage
import app.truenascompanion.data.model.CronJob
import app.truenascompanion.data.model.Health
import app.truenascompanion.data.model.InitScript
import app.truenascompanion.data.model.InitScriptType
import app.truenascompanion.data.model.InitScriptWhen
import app.truenascompanion.data.model.JobState
import app.truenascompanion.data.model.LastJob
import app.truenascompanion.data.tasks.CronText
import app.truenascompanion.ui.appViewModel
import app.truenascompanion.ui.components.ConfirmDialog
import app.truenascompanion.ui.components.ElevatedSection
import app.truenascompanion.ui.components.EmptyState
import app.truenascompanion.ui.components.IconBadge
import app.truenascompanion.ui.components.ScrollableErrorState
import app.truenascompanion.ui.components.SectionTitle
import app.truenascompanion.ui.components.SkeletonList
import app.truenascompanion.ui.components.StatusChip
import app.truenascompanion.ui.components.UiState
import app.truenascompanion.ui.components.isLoginRequired
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TasksData(val cron: List<CronJob>, val scripts: List<InitScript>)

/** A "Run now" of a cron job, followed through `core.get_jobs`. */
data class CronRun(val job: CronJob, val last: LastJob? = null, val error: String? = null, val hidden: Boolean = false) {
    val done: Boolean get() = error != null || (last != null && !last.state.active && last.state != JobState.UNKNOWN)
    val succeeded: Boolean get() = error == null && last?.state == JobState.SUCCESS
}

class ScheduledTasksViewModel(private val c: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow<UiState<TasksData>>(UiState.Loading)
    val state = _state.asStateFlow()
    private val _busy = MutableStateFlow<Set<String>>(emptySet())
    val busy = _busy.asStateFlow()
    private val _refreshing = MutableStateFlow(false)
    val refreshing = _refreshing.asStateFlow()
    private val _run = MutableStateFlow<CronRun?>(null)
    val run = _run.asStateFlow()
    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    init {
        viewModelScope.launch { c.repository.reloadKey.collect { if (it != null) load() } }
    }

    fun refresh() = viewModelScope.launch { _refreshing.value = true; load(); _refreshing.value = false }

    /** Back from an editor: reload quietly if the list is already showing. */
    fun refreshQuiet() { if (_state.value is UiState.Success) viewModelScope.launch { load() } }

    private suspend fun load() {
        try {
            val data = c.repository.call { api -> TasksApi(api).let { TasksData(it.cronJobs(), it.initScripts()) } }
            _state.value = UiState.Success(data)
        } catch (e: Throwable) {
            if (_state.value !is UiState.Success) _state.value = UiState.Error(e.userMessage(), e) else _messages.trySend(e.userMessage())
        }
    }

    private fun busyOp(key: String, done: String?, block: suspend (TasksApi) -> Unit) {
        if (key in _busy.value) return
        _busy.update { it + key }
        viewModelScope.launch {
            try {
                c.repository.call { block(TasksApi(it)) }
                done?.let { _messages.trySend(it) }
            } catch (e: Throwable) {
                _messages.trySend(e.userMessage())
            } finally {
                _busy.update { it - key }
                load()
            }
        }
    }

    private fun patch(f: (TasksData) -> TasksData) = _state.update { s -> (s as? UiState.Success)?.let { UiState.Success(f(it.data)) } ?: s }

    fun setCronEnabled(j: CronJob, on: Boolean) {
        patch { d -> d.copy(cron = d.cron.map { if (it.id == j.id) it.copy(enabled = on) else it }) }
        busyOp("cron:${j.id}", null) { it.setCronEnabled(j.id, on) }
    }

    fun deleteCron(j: CronJob) = busyOp("cron:${j.id}", "Cron job deleted") { it.deleteCronJob(j.id) }

    fun setScriptEnabled(s: InitScript, on: Boolean) {
        patch { d -> d.copy(scripts = d.scripts.map { if (it.id == s.id) it.copy(enabled = on) else it }) }
        busyOp("init:${s.id}", null) { it.setInitScriptEnabled(s.id, on) }
    }

    fun deleteScript(s: InitScript) = busyOp("init:${s.id}", "Script deleted") { it.deleteInitScript(s.id) }

    fun runCron(j: CronJob) {
        if (_run.value?.done == false) { _messages.trySend("Another cron job is still running"); return }
        _run.value = CronRun(j)
        viewModelScope.launch {
            try {
                val id = c.repository.call { TasksApi(it).runCronJob(j.id) }
                val last = c.repository.call { api -> TasksApi(api).followJob(id) { l -> _run.update { r -> r?.copy(last = l) } } }
                _run.update { it?.copy(last = last) }
            } catch (e: Throwable) {
                _run.update { it?.copy(error = e.userMessage()) }
            }
            val r = _run.value
            if (r != null && r.hidden) {
                _messages.trySend(if (r.succeeded) "“${j.title}” finished" else "“${j.title}” failed: ${r.error ?: r.last?.error ?: "see the TrueNAS job log"}")
                _run.value = null
            }
        }
    }

    /** Closes the run dialog; a job that's still running keeps going and reports via a snackbar. */
    fun closeRun() = _run.update { r -> if (r == null || r.done) null else r.copy(hidden = true) }
}

private sealed interface TaskConfirm {
    data class RunCron(val job: CronJob) : TaskConfirm
    data class DeleteCron(val job: CronJob) : TaskConfirm
    data class DeleteScript(val script: InitScript) : TaskConfirm
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduledTasksScreen(
    onBack: () -> Unit,
    onEditCron: (Int?) -> Unit,
    onEditScript: (Int?) -> Unit,
) {
    val vm = appViewModel { ScheduledTasksViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val run by vm.run.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(vm) { vm.messages.collect { snackbar.showSnackbar(it) } }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refreshQuiet() }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var confirm by remember { mutableStateOf<TaskConfirm?>(null) }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(title = { Text("Scheduled tasks") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } })
                TasksTabs(tab) { tab = it }
            }
        },
        floatingActionButton = {
            if (state is UiState.Success) ExtendedFloatingActionButton(
                onClick = { if (tab == 0) onEditCron(null) else onEditScript(null) },
                icon = { Icon(Icons.Rounded.Add, null) },
                text = { Text(if (tab == 0) "New cron job" else "New script") },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        PullToRefreshBox(isRefreshing = refreshing, onRefresh = { vm.refresh() }, modifier = Modifier.padding(padding).fillMaxSize()) {
            when (val s = state) {
                UiState.Loading -> SkeletonList(5, 130.dp)
                is UiState.Error -> ScrollableErrorState(s.message, s.isLoginRequired) { vm.refresh() }
                is UiState.Success -> if (tab == 0) CronJobsContent(
                    s.data.cron, busy,
                    onToggle = vm::setCronEnabled,
                    onRun = { confirm = TaskConfirm.RunCron(it) },
                    onEdit = { onEditCron(it.id) },
                    onDelete = { confirm = TaskConfirm.DeleteCron(it) },
                ) else InitScriptsContent(
                    s.data.scripts, busy,
                    onToggle = vm::setScriptEnabled,
                    onEdit = { onEditScript(it.id) },
                    onDelete = { confirm = TaskConfirm.DeleteScript(it) },
                )
            }
        }
    }
    when (val c = confirm) {
        is TaskConfirm.RunCron -> RunCronConfirmDialog(c.job, onConfirm = { confirm = null; vm.runCron(c.job) }, onDismiss = { confirm = null })
        is TaskConfirm.DeleteCron -> DeleteTaskDialog("Delete cron job?", c.job.title, onConfirm = { confirm = null; vm.deleteCron(c.job) }, onDismiss = { confirm = null })
        is TaskConfirm.DeleteScript -> DeleteTaskDialog("Delete script?", c.script.title, onConfirm = { confirm = null; vm.deleteScript(c.script) }, onDismiss = { confirm = null })
        null -> Unit
    }
    run?.takeIf { !it.hidden }?.let { CronRunDialog(it, onClose = vm::closeRun) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TasksTabs(tab: Int, onTab: (Int) -> Unit) {
    PrimaryTabRow(selectedTabIndex = tab) {
        Tab(selected = tab == 0, onClick = { onTab(0) }, text = { Text("Cron jobs", maxLines = 1) }, icon = { Icon(Icons.Rounded.EventRepeat, null) })
        Tab(selected = tab == 1, onClick = { onTab(1) }, text = { Text("Init/shutdown scripts", maxLines = 1, overflow = TextOverflow.Ellipsis) }, icon = { Icon(Icons.Rounded.PowerSettingsNew, null) })
    }
}

/** "Run now" confirmation: shows exactly what will run and as whom. */
@Composable
fun RunCronConfirmDialog(job: CronJob, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    ConfirmDialog(
        title = "Run “${job.title}” now?",
        text = "This runs the command right away as ${job.user}" + (if (!job.enabled) ", even though the job is turned off" else "") + ":",
        confirmLabel = "Run now",
        icon = Icons.Rounded.PlayArrow,
        requireAuth = true,
        onConfirm = onConfirm, onDismiss = onDismiss,
    ) {
        Spacer(Modifier.height(10.dp))
        CommandBox(job.command, maxLines = 6)
    }
}

@Composable
fun DeleteTaskDialog(title: String, name: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    ConfirmDialog(
        title = title,
        text = "“$name” will be removed from the NAS and won't run again. This can't be undone.",
        confirmLabel = "Delete", destructive = true, icon = Icons.Rounded.DeleteOutline,
        onConfirm = onConfirm, onDismiss = onDismiss,
    )
}

/** Progress and result of a "Run now", with the job's output (TrueNAS keeps the last part of it). */
@Composable
fun CronRunDialog(run: CronRun, onClose: () -> Unit) {
    val last = run.last
    AlertDialog(
        onDismissRequest = onClose,
        icon = {
            when {
                !run.done -> Icon(Icons.Rounded.PlayArrow, null)
                run.succeeded -> Icon(Icons.Rounded.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                else -> Icon(Icons.Rounded.Error, null, tint = MaterialTheme.colorScheme.error)
            }
        },
        title = { Text(when { !run.done -> "Running “${run.job.title}”"; run.succeeded -> "Finished"; else -> "Didn't finish" }) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (!run.done) {
                    val pct = last?.percent?.takeIf { it > 0 }
                    if (pct != null) LinearProgressIndicator(progress = { (pct / 100).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                    else LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(last?.progressText?.takeIf { it.isNotBlank() } ?: "Waiting for the command to finish…",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else if (!run.succeeded) {
                    Text(run.error ?: last?.error ?: "The job ended with state ${last?.state?.name?.lowercase()}.", color = MaterialTheme.colorScheme.error)
                } else {
                    Text("The command ran successfully.")
                }
                last?.logExcerpt?.takeIf { it.isNotBlank() }?.let {
                    Text("Output", style = MaterialTheme.typography.labelLarge)
                    CommandBox(it.trimEnd(), maxLines = 14)
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text(if (run.done) "Close" else "Hide") } },
    )
}

@Composable
fun CommandBox(text: String, modifier: Modifier = Modifier, maxLines: Int = 3) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest, shape = RoundedCornerShape(10.dp), modifier = modifier.fillMaxWidth()) {
        Text(
            text, maxLines = maxLines, overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
        )
    }
}

@Composable
fun CronJobsContent(
    jobs: List<CronJob>,
    busy: Set<String>,
    onToggle: (CronJob, Boolean) -> Unit,
    onRun: (CronJob) -> Unit,
    onEdit: (CronJob) -> Unit,
    onDelete: (CronJob) -> Unit,
) {
    if (jobs.isEmpty()) {
        Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            EmptyState(Icons.Rounded.EventRepeat, "No cron jobs yet", "Run a command on a schedule: cleanups, reports, custom backups… Tap “New cron job” to add one.")
        }
        return
    }
    LazyColumn(contentPadding = PaddingValues(16.dp, 16.dp, 16.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxSize()) {
        items(jobs, key = { it.id }) { j -> CronJobCard(j, "cron:${j.id}" in busy, onToggle, onRun, onEdit, onDelete) }
    }
}

@Composable
private fun CronJobCard(
    j: CronJob,
    busy: Boolean,
    onToggle: (CronJob, Boolean) -> Unit,
    onRun: (CronJob) -> Unit,
    onEdit: (CronJob) -> Unit,
    onDelete: (CronJob) -> Unit,
) {
    ElevatedSection(contentPadding = 14.dp, onClick = { onEdit(j) }, modifier = Modifier.testTag("cron:${j.id}")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(if (j.isSmartTest) Icons.Rounded.WbTwilight else Icons.Rounded.Schedule, tint = if (j.enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(j.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    if (CronText.isValid(j.schedule)) CronText.describe(j.schedule) else j.schedule.expression,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (j.enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = j.enabled, onCheckedChange = { onToggle(j, it) }, enabled = !busy)
        }
        Spacer(Modifier.height(10.dp))
        CommandBox(j.command)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Runs as ${j.user}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(j.mailText, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = { onRun(j) }, enabled = !busy) {
                Icon(Icons.Rounded.PlayArrow, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Run now", maxLines = 1)
            }
            IconButton(onClick = { onDelete(j) }, enabled = !busy) { Icon(Icons.Rounded.DeleteOutline, "Delete") }
        }
        if (j.isSmartTest) {
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusChip(Health.HEALTHY, "S.M.A.R.T. test", showIcon = false)
                Spacer(Modifier.width(8.dp))
                Text("Easier to manage in Storage › Protection", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

fun whenIcon(w: InitScriptWhen): ImageVector = when (w) {
    InitScriptWhen.PREINIT -> Icons.Rounded.RocketLaunch
    InitScriptWhen.POSTINIT -> Icons.Rounded.PlayArrow
    InitScriptWhen.SHUTDOWN -> Icons.Rounded.PowerSettingsNew
}

@Composable
fun InitScriptsContent(
    scripts: List<InitScript>,
    busy: Set<String>,
    onToggle: (InitScript, Boolean) -> Unit,
    onEdit: (InitScript) -> Unit,
    onDelete: (InitScript) -> Unit,
) {
    LazyColumn(contentPadding = PaddingValues(16.dp, 16.dp, 16.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxSize()) {
        item {
            Text(
                "TrueNAS runs these by itself while starting up or shutting down. They can't be started on demand from the app; " +
                    "TrueNAS 25.10 doesn't offer a public way to do that.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (scripts.isEmpty()) item {
            EmptyState(Icons.Rounded.PowerSettingsNew, "No init or shutdown scripts", "Run a command or script when the NAS boots or shuts down. Tap “New script” to add one.")
        }
        InitScriptWhen.entries.forEach { w ->
            val group = scripts.filter { it.whenRun == w }
            if (group.isNotEmpty()) {
                item(key = "h:${w.name}") {
                    Column {
                        SectionTitle(w.label)
                        Text(w.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp))
                    }
                }
                items(group, key = { it.id }) { s -> InitScriptCard(s, "init:${s.id}" in busy, onToggle, onEdit, onDelete) }
            }
        }
    }
}

@Composable
private fun InitScriptCard(s: InitScript, busy: Boolean, onToggle: (InitScript, Boolean) -> Unit, onEdit: (InitScript) -> Unit, onDelete: (InitScript) -> Unit) {
    ElevatedSection(contentPadding = 14.dp, onClick = { onEdit(s) }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(if (s.type == InitScriptType.SCRIPT) Icons.Rounded.Terminal else whenIcon(s.whenRun),
                tint = if (s.enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(s.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("${s.type.label} · ${s.whenRun.label} · ${s.timeout} s timeout", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = s.enabled, onCheckedChange = { onToggle(s, it) }, enabled = !busy)
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            CommandBox(s.target, maxLines = 3, modifier = Modifier.weight(1f))
            IconButton(onClick = { onDelete(s) }, enabled = !busy) { Icon(Icons.Rounded.DeleteOutline, "Delete") }
        }
    }
}
