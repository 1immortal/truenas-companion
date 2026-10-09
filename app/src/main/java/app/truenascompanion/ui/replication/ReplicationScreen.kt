package app.truenascompanion.ui.replication

import androidx.compose.material.icons.rounded.Delete
import app.truenascompanion.ui.components.GlowButton
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Cable
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.SyncAlt
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import app.truenascompanion.ui.components.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import app.truenascompanion.ui.components.TopAppBar
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
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import app.truenascompanion.AppContainer
import app.truenascompanion.data.api.ReplicationApi
import app.truenascompanion.data.api.userMessage
import app.truenascompanion.data.cloud.CloudRunWatch
import app.truenascompanion.data.model.Health
import app.truenascompanion.data.replication.KeychainUse
import app.truenascompanion.data.replication.ReplDirection
import app.truenascompanion.data.replication.ReplTransport
import app.truenascompanion.data.replication.ReplicationLogic
import app.truenascompanion.data.replication.ReplicationTask
import app.truenascompanion.data.replication.SshConnection
import app.truenascompanion.data.replication.SshKeyPair
import app.truenascompanion.notify.CloudSyncWatcher
import app.truenascompanion.ui.appViewModel
import app.truenascompanion.ui.components.ConfirmDialog
import app.truenascompanion.ui.components.ElevatedSection
import app.truenascompanion.ui.components.EmptyState
import app.truenascompanion.ui.components.IconBadge
import app.truenascompanion.ui.components.InfoBanner
import app.truenascompanion.ui.components.ScrollableErrorState
import app.truenascompanion.ui.components.SectionTitle
import app.truenascompanion.ui.components.SkeletonList
import app.truenascompanion.ui.components.UiState
import app.truenascompanion.ui.components.isLoginRequired
import app.truenascompanion.ui.tasks.CommandBox
import app.truenascompanion.ui.theme.LocalStatusColors
import app.truenascompanion.util.Format
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ReplicationData(
    val tasks: List<ReplicationTask>,
    val connections: List<SshConnection>,
    val keyPairs: List<SshKeyPair>,
) {
    val anyRunning: Boolean get() = tasks.any { it.running }
    fun keyName(id: Int?) = keyPairs.firstOrNull { it.id == id }?.name
}

/** Restore dialog draft: a new pull/push task in the other direction, into [target]. */
data class ReplRestoreDraft(val task: ReplicationTask, val name: String, val target: String = "")

/** A keychain credential the user wants to delete, with what still uses it (from `keychaincredential.used_by`). */
data class KeychainDelete(val id: Int, val name: String, val isKey: Boolean, val usedBy: List<KeychainUse>)

class ReplicationViewModel(private val c: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow<UiState<ReplicationData>>(UiState.Loading)
    val state = _state.asStateFlow()
    private val _busy = MutableStateFlow<Set<String>>(emptySet())
    val busy = _busy.asStateFlow()
    private val _refreshing = MutableStateFlow(false)
    val refreshing = _refreshing.asStateFlow()
    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()
    private val _restore = MutableStateFlow<ReplRestoreDraft?>(null)
    val restore = _restore.asStateFlow()
    private val _delete = MutableStateFlow<KeychainDelete?>(null)
    val delete = _delete.asStateFlow()
    val canNotify: Boolean get() = c.notifier.canPost()

    init {
        viewModelScope.launch { c.repository.reloadKey.collect { if (it != null) load() } }
    }

    fun refresh() = viewModelScope.launch { _refreshing.value = true; load(); _refreshing.value = false }
    fun refreshQuiet() { if (_state.value is UiState.Success) viewModelScope.launch { load() } }

    suspend fun poll() {
        try {
            val tasks = c.repository.call { ReplicationApi(it).tasks() }
            patch { it.copy(tasks = tasks) }
        } catch (_: Throwable) {
        }
    }

    private suspend fun load() {
        try {
            val data = c.repository.call { api ->
                val a = ReplicationApi(api)
                ReplicationData(a.tasks(), runCatching { a.connections() }.getOrDefault(emptyList()), runCatching { a.keyPairs() }.getOrDefault(emptyList()))
            }
            _state.value = UiState.Success(data)
        } catch (e: Throwable) {
            if (_state.value !is UiState.Success) _state.value = UiState.Error(e.userMessage(), e) else _messages.trySend(e.userMessage())
        }
    }

    private fun patch(f: (ReplicationData) -> ReplicationData) = _state.update { s -> (s as? UiState.Success)?.let { UiState.Success(f(it.data)) } ?: s }

    private fun busyOp(key: String, done: String?, block: suspend (ReplicationApi) -> Unit) {
        if (key in _busy.value) return
        _busy.update { it + key }
        viewModelScope.launch {
            try {
                c.repository.call { block(ReplicationApi(it)) }
                done?.let { _messages.trySend(it) }
            } catch (e: Throwable) {
                _messages.trySend(e.userMessage())
            } finally {
                _busy.update { it - key }
                load()
            }
        }
    }

    fun setEnabled(t: ReplicationTask, on: Boolean) {
        patch { d -> d.copy(tasks = d.tasks.map { if (it.id == t.id) it.copy(enabled = on) else it }) }
        busyOp("task:${t.id}", null) { it.setEnabled(t.id, on) }
    }

    fun run(t: ReplicationTask, notify: Boolean) = busyOp("task:${t.id}", "Replication started") { api ->
        val jobId = api.run(t.id)
        if (notify) watch(t.id, t.name, jobId)
    }

    private suspend fun watch(taskId: Int, name: String, jobId: Long) {
        c.settings.activeServerId.first()?.let { server ->
            CloudSyncWatcher.add(c.context, CloudRunWatch(server, taskId, name, jobId, false, System.currentTimeMillis(), CloudRunWatch.KIND_REPLICATION))
        }
    }

    fun deleteTask(t: ReplicationTask) = busyOp("task:${t.id}", "Replication task deleted") { it.delete(t.id) }

    fun startRestore(t: ReplicationTask) { _restore.value = ReplRestoreDraft(t, "Restore of ${t.name}") }
    fun editRestore(f: (ReplRestoreDraft) -> ReplRestoreDraft) = _restore.update { it?.let(f) }
    fun cancelRestore() { _restore.value = null }
    fun confirmRestore() {
        val d = _restore.value ?: return
        if (ReplicationLogic.restoreErrors(d.task, d.name, d.target).isNotEmpty()) return
        _restore.value = null
        busyOp("task:${d.task.id}", "Restore task created. It's turned off: run it when you're ready.") { it.restore(d.task.id, d.name, d.target) }
    }

    /** Asks TrueNAS what uses the credential first: in-use credentials can't be deleted. */
    fun askDelete(id: Int, name: String, isKey: Boolean) {
        val key = "cred:$id"
        if (key in _busy.value) return
        _busy.update { it + key }
        viewModelScope.launch {
            try {
                _delete.value = KeychainDelete(id, name, isKey, c.repository.call { ReplicationApi(it).usedBy(id) })
            } catch (e: Throwable) {
                _messages.trySend(e.userMessage())
            } finally {
                _busy.update { it - key }
            }
        }
    }

    fun closeDelete() { _delete.value = null }

    fun confirmDelete() {
        val d = _delete.value ?: return
        _delete.value = null
        if (d.usedBy.isNotEmpty()) return
        busyOp("cred:${d.id}", if (d.isKey) "Key pair deleted" else "SSH connection deleted") { it.deleteCredential(d.id) }
    }
}

private sealed interface ReplConfirm {
    data class Run(val task: ReplicationTask) : ReplConfirm
    data class Delete(val task: ReplicationTask) : ReplConfirm
    data class Log(val task: ReplicationTask) : ReplConfirm
}

/** Everything the replication lists can ask for (overridden by the screen; previews use the no-op defaults). */
open class ReplicationActions {
    open fun toggle(t: ReplicationTask, on: Boolean) {}
    open fun run(t: ReplicationTask) {}
    open fun edit(t: ReplicationTask) {}
    open fun restore(t: ReplicationTask) {}
    open fun delete(t: ReplicationTask) {}
    open fun log(t: ReplicationTask) {}
    open fun editConnection(c: SshConnection) {}
    open fun deleteConnection(c: SshConnection) {}
    open fun editKeyPair(k: SshKeyPair) {}
    open fun deleteKeyPair(k: SshKeyPair) {}
    open fun addTask() {}
    open fun addConnection() {}
    open fun addKeyPair() {}
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReplicationScreen(
    onBack: () -> Unit,
    onEditTask: (Int?) -> Unit,
    onEditConnection: (Int?) -> Unit,
    onEditKeyPair: (Int?) -> Unit,
) {
    val vm = appViewModel { ReplicationViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val restore by vm.restore.collectAsStateWithLifecycle()
    val delete by vm.delete.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(vm) { vm.messages.collect { snackbar.showSnackbar(it) } }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refreshQuiet() }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var confirm by remember { mutableStateOf<ReplConfirm?>(null) }
    val data = (state as? UiState.Success)?.data
    val running = data?.anyRunning == true
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(running) {
        if (running) lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { while (true) { delay(3_000); vm.poll() } }
    }
    val actions = remember(vm) {
        object : ReplicationActions() {
            override fun toggle(t: ReplicationTask, on: Boolean) = vm.setEnabled(t, on)
            override fun run(t: ReplicationTask) { confirm = ReplConfirm.Run(t) }
            override fun edit(t: ReplicationTask) = onEditTask(t.id)
            override fun restore(t: ReplicationTask) = vm.startRestore(t)
            override fun delete(t: ReplicationTask) { confirm = ReplConfirm.Delete(t) }
            override fun log(t: ReplicationTask) { confirm = ReplConfirm.Log(t) }
            override fun editConnection(c: SshConnection) = onEditConnection(c.id)
            override fun deleteConnection(c: SshConnection) = vm.askDelete(c.id, c.name, false)
            override fun editKeyPair(k: SshKeyPair) = onEditKeyPair(k.id)
            override fun deleteKeyPair(k: SshKeyPair) = vm.askDelete(k.id, k.name, true)
            override fun addTask() = onEditTask(null)
            override fun addConnection() = onEditConnection(null)
            override fun addKeyPair() = onEditKeyPair(null)
        }
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(title = { Text("Replication") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } })
                ReplicationTabs(tab) { tab = it }
            }
        },
        floatingActionButton = {
            if (data != null) ExtendedFloatingActionButton(
                onClick = { if (tab == 0) actions.addTask() else actions.addConnection() },
                icon = { Icon(Icons.Rounded.Add, null) },
                text = { Text(if (tab == 0) "New task" else "New connection") },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        PullToRefreshBox(isRefreshing = refreshing, onRefresh = { vm.refresh() }, modifier = Modifier.padding(padding).fillMaxSize()) {
            when (val s = state) {
                UiState.Loading -> SkeletonList(4, 170.dp)
                is UiState.Error -> ScrollableErrorState(s.message, s.isLoginRequired) { vm.refresh() }
                is UiState.Success -> if (tab == 0) ReplicationTasksContent(s.data, busy, System.currentTimeMillis(), actions)
                else ConnectionsContent(s.data, busy, actions)
            }
        }
    }

    val close = { confirm = null }
    when (val d = confirm) {
        null -> Unit
        is ReplConfirm.Run -> RunReplicationDialog(d.task, vm.canNotify, onDismiss = close) { notify -> close(); vm.run(d.task, notify) }
        is ReplConfirm.Delete -> ConfirmDialog(
            "Delete replication task?",
            "“${d.task.name}” will be removed and won't run again. Snapshots already replicated stay on the target, and nothing is deleted on either side.",
            "Delete", destructive = true, onConfirm = { close(); vm.deleteTask(d.task) }, onDismiss = close,
        )
        is ReplConfirm.Log -> ReplicationLogDialog(d.task, close)
    }
    restore?.let { r -> ReplRestoreDialog(r, onChange = vm::editRestore, onDismiss = vm::cancelRestore, onConfirm = vm::confirmRestore) }
    delete?.let { d -> KeychainDeleteDialog(d, onDismiss = vm::closeDelete, onConfirm = vm::confirmDelete) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReplicationTabs(tab: Int, onTab: (Int) -> Unit) {
    PrimaryTabRow(selectedTabIndex = tab) {
        Tab(selected = tab == 0, onClick = { onTab(0) }, text = { Text("Tasks", maxLines = 1) }, icon = { Icon(Icons.Rounded.SyncAlt, null) })
        Tab(selected = tab == 1, onClick = { onTab(1) }, text = { Text("SSH connections", maxLines = 1) }, icon = { Icon(Icons.Rounded.Cable, null) })
    }
}

/** Run now confirmation with the optional finish notification. */
@Composable
fun RunReplicationDialog(task: ReplicationTask, canNotify: Boolean, onDismiss: () -> Unit, onConfirm: (notify: Boolean) -> Unit) {
    var notify by rememberSaveable { mutableStateOf(canNotify) }
    val text = buildString {
        append("Sends new snapshots for “${task.name}” now")
        if (!task.enabled) append(". The task is turned off, so TrueNAS won't run it: turn it on first")
        append(".")
        if (task.direction == ReplDirection.PULL) append(" Snapshots are pulled from the other system.")
    }
    ConfirmDialog(
        title = "Run ${task.name} now?",
        text = text,
        confirmLabel = "Run now",
        icon = Icons.Rounded.PlayArrow,
        requireAuth = false,
        onConfirm = { onConfirm(notify && canNotify) }, onDismiss = onDismiss,
    ) {
        Spacer(Modifier.height(8.dp))
        CommandBox(ReplicationLogic.route(task), maxLines = 4)
        if (canNotify) Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = notify, onCheckedChange = { notify = it }, modifier = Modifier.testTag("notify-check"))
            Text("Notify me when it finishes", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun ReplicationLogDialog(task: ReplicationTask, onDismiss: () -> Unit) {
    val job = task.job
    val st = task.state
    val text = listOfNotNull(st?.error ?: job?.error, st?.warnings?.takeIf { it.isNotEmpty() }?.joinToString("\n"), job?.logExcerpt?.trim()?.takeIf { it.isNotEmpty() })
        .distinct().joinToString("\n\n").ifBlank { "No log output for the last run." }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(task.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                ReplicationLogic.lastState(task)?.let {
                    Text("Last run: ${it.state.lowercase()} · ${Format.relativeTime(it.atMillis)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(4.dp))
                }
                st?.lastSnapshot?.let {
                    Text("Last snapshot sent: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(4.dp))
                }
                Spacer(Modifier.height(4.dp))
                Text(text, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

/** `replication.restore`: a new, turned-off task in the other direction (retention "keep all") into [ReplRestoreDraft.target]. */
@Composable
fun ReplRestoreDialog(draft: ReplRestoreDraft, onChange: ((ReplRestoreDraft) -> ReplRestoreDraft) -> Unit, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val errors = ReplicationLogic.restoreErrors(draft.task, draft.name, draft.target)
    val back = if (draft.task.direction == ReplDirection.PUSH) "from ${draft.task.targetDataset} back to this NAS" else "from this NAS back to the other system"
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Restore, null) },
        title = { Text("Restore snapshots") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Creates a task that copies the snapshots $back. It starts turned off and deletes nothing, so you can check it before running it.",
                    style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(draft.name, { v -> onChange { it.copy(name = v) } }, label = { Text("Task name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    draft.target, { v -> onChange { it.copy(target = v.trim()) } }, label = { Text("Restore into dataset") }, singleLine = true,
                    placeholder = { Text("tank/restored") }, isError = draft.target.isNotEmpty() && errors["target_dataset"] != null,
                    supportingText = { Text(errors["target_dataset"]?.takeIf { draft.target.isNotEmpty() } ?: "A new dataset is safest; it is created on the first run") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { GlowButton(onClick = onConfirm, enabled = errors.isEmpty()) { Text("Create task") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun KeychainDeleteDialog(d: KeychainDelete, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val what = if (d.isKey) "key pair" else "SSH connection"
    if (d.usedBy.isNotEmpty()) AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${what.replaceFirstChar { it.uppercase() }} in use") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("“${d.name}” is still used by:")
                Spacer(Modifier.height(6.dp))
                d.usedBy.forEach { Text("• ${it.title}", style = MaterialTheme.typography.bodyMedium) }
                Spacer(Modifier.height(8.dp))
                Text("Delete those or switch them to another ${if (d.isKey) "key" else "connection"} first.", style = MaterialTheme.typography.bodyMedium)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
    ) else ConfirmDialog(
        "Delete $what?",
        if (d.isKey) "“${d.name}” is removed from the NAS. Systems that trust this key keep it in their authorized keys until you remove it there."
        else "“${d.name}” is removed. The other system and its data are not touched.",
        "Delete", destructive = true, onConfirm = onConfirm, onDismiss = onDismiss,
    )
}

// --- lists ---

@Composable
fun ReplicationTasksContent(data: ReplicationData, busy: Set<String>, now: Long, actions: ReplicationActions, modifier: Modifier = Modifier) {
    LazyColumn(modifier.fillMaxSize().testTag("page"), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (data.connections.isEmpty()) item {
            InfoBanner("To replicate to another system, add an SSH connection first (SSH connections tab). Copies to another pool on this NAS don't need one.", health = Health.UNKNOWN)
        }
        if (data.tasks.isEmpty()) item {
            EmptyState(Icons.Rounded.SyncAlt, "No replication tasks", "Copy ZFS snapshots of your datasets to another TrueNAS, another pool, or pull them from one. Tap New task to start.")
        }
        items(data.tasks, key = { "task-${it.id}" }) { t -> ReplicationTaskCard(t, "task:${t.id}" in busy, now, actions) }
    }
}

private val BUTTON_PADDING = PaddingValues(horizontal = 12.dp, vertical = 8.dp)

@Composable
private fun ReplicationTaskCard(t: ReplicationTask, busy: Boolean, now: Long, actions: ReplicationActions) {
    ElevatedSection(onClick = { actions.edit(t) }, contentPadding = 16.dp, modifier = Modifier.testTag("repl-task-${t.id}")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(if (t.direction == ReplDirection.PUSH) Icons.Rounded.Upload else Icons.Rounded.Download, tint = when {
                t.failed -> MaterialTheme.colorScheme.error
                !t.enabled -> MaterialTheme.colorScheme.onSurfaceVariant
                else -> MaterialTheme.colorScheme.primary
            })
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(t.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(listOfNotNull(t.direction.label, t.transport.label, if (t.recursive) "with children" else null).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Switch(checked = t.enabled, onCheckedChange = { actions.toggle(t, it) }, enabled = !busy)
        }
        Spacer(Modifier.height(8.dp))
        Text(ReplicationLogic.route(t), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, maxLines = 4, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Schedule, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(6.dp))
            Text(ReplicationLogic.whenText(t), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(6.dp))
        if (t.running) {
            Text(ReplicationLogic.progressText(t) ?: "Running…", style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(6.dp))
            val pct = ReplicationLogic.percent(t)
            if (pct != null && pct > 0) LinearProgressIndicator(progress = { (pct / 100).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            else LinearProgressIndicator(Modifier.fillMaxWidth())
        } else ReplStateLine(t, now)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            FilledTonalButton(onClick = { actions.run(t) }, enabled = !busy && !t.running && t.enabled, modifier = Modifier.weight(1f), contentPadding = BUTTON_PADDING) {
                Icon(Icons.Rounded.PlayArrow, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(if (t.running) "Running" else "Run now", maxLines = 1)
            }
            OutlinedButton(onClick = { actions.log(t) }, enabled = t.job != null || t.state?.error != null || t.state?.lastSnapshot != null, modifier = Modifier.weight(1f), contentPadding = BUTTON_PADDING) {
                Text("Last log", maxLines = 1)
            }
            TaskMenu(t, busy, actions)
        }
    }
}

@Composable
private fun ReplStateLine(t: ReplicationTask, now: Long) {
    val s = ReplicationLogic.lastState(t)
    val at = Format.relativeTime(s?.atMillis, now)
    val (health, text) = when (s?.state) {
        null, "PENDING" -> Health.UNKNOWN to "Hasn't run yet"
        "FINISHED" -> Health.HEALTHY to ("Last run $at" + if (s.warnings.isNotEmpty()) " · ${s.warnings.size} warning${if (s.warnings.size == 1) "" else "s"}" else "")
        "ERROR" -> Health.CRITICAL to "Failed $at: ${s.error?.lineSequence()?.firstOrNull { it.isNotBlank() } ?: "see the log"}"
        "WAITING" -> Health.UNKNOWN to ("Waiting" + (s.reason?.let { ": $it" } ?: " for another task"))
        "HOLD" -> Health.WARNING to "On hold: ${s.reason ?: s.error ?: "the pool isn't available"}"
        else -> Health.UNKNOWN to s.state.lowercase().replaceFirstChar { it.uppercase() }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).background(LocalStatusColors.current.of(health), CircleShape))
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis,
            color = if (health == Health.CRITICAL) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun TaskMenu(t: ReplicationTask, busy: Boolean, actions: ReplicationActions) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, enabled = !busy) { Icon(Icons.Rounded.MoreVert, "More actions for ${t.name}") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("Edit") }, onClick = { open = false; actions.edit(t) })
            DropdownMenuItem(text = { Text("Restore…") }, onClick = { open = false; actions.restore(t) })
            app.truenascompanion.ui.components.DestructiveMenuItem("Delete", Icons.Rounded.Delete) { open = false; actions.delete(t) }
        }
    }
}

@Composable
fun ConnectionsContent(data: ReplicationData, busy: Set<String>, actions: ReplicationActions, modifier: Modifier = Modifier) {
    LazyColumn(modifier.fillMaxSize().testTag("page"), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { SectionTitle("SSH connections") }
        if (data.connections.isEmpty()) item {
            EmptyState(Icons.Rounded.Computer, "No SSH connections", "A connection lets this NAS sign in to another system over SSH with a key. Tap New connection; for another TrueNAS the app can set it up for you.")
        }
        items(data.connections, key = { "conn-${it.id}" }) { c ->
            ElevatedSection(onClick = { actions.editConnection(c) }, contentPadding = 16.dp, modifier = Modifier.testTag("conn-${c.id}")) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconBadge(Icons.Rounded.Computer)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(c.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(c.address, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        data.keyName(c.privateKeyId)?.let { Text("Key: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    }
                    TextButton(onClick = { actions.deleteConnection(c) }, enabled = "cred:${c.id}" !in busy) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                }
            }
        }
        item {
            SectionTitle("SSH key pairs") {
                TextButton(onClick = actions::addKeyPair, modifier = Modifier.testTag("add-keypair")) {
                    Icon(Icons.Rounded.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Add")
                }
            }
        }
        if (data.keyPairs.isEmpty()) item {
            Text("No key pairs yet. New connections can generate one for you.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp))
        }
        items(data.keyPairs, key = { "key-${it.id}" }) { k ->
            val used = data.connections.count { it.privateKeyId == k.id }
            ElevatedSection(onClick = { actions.editKeyPair(k) }, contentPadding = 16.dp, modifier = Modifier.testTag("key-${k.id}")) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconBadge(Icons.Rounded.Key)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(k.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(k.shortPublicKey ?: "Keys hidden by TrueNAS", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(if (used == 0) "Not used by a connection" else "Used by $used connection${if (used == 1) "" else "s"}",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = { actions.deleteKeyPair(k) }, enabled = "cred:${k.id}" !in busy) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                }
            }
        }
    }
}

