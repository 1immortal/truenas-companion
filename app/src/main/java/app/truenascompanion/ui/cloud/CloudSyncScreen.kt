package app.truenascompanion.ui.cloud

import app.truenascompanion.ui.components.StateContent

import androidx.compose.material.icons.rounded.Delete
import app.truenascompanion.ui.components.GlowButton
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
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
import app.truenascompanion.data.api.CloudSyncApi
import app.truenascompanion.data.api.VerifyResult
import app.truenascompanion.data.api.userMessage
import app.truenascompanion.data.cloud.CloudCredential
import app.truenascompanion.data.cloud.CloudDirection
import app.truenascompanion.data.cloud.CloudProvider
import app.truenascompanion.data.cloud.CloudProviders
import app.truenascompanion.data.cloud.CloudRunWatch
import app.truenascompanion.data.cloud.CloudSyncLogic
import app.truenascompanion.data.cloud.CloudSyncTask
import app.truenascompanion.data.cloud.TransferMode
import app.truenascompanion.data.model.Health
import app.truenascompanion.data.model.JobState
import app.truenascompanion.data.tasks.CronText
import app.truenascompanion.notify.CloudSyncWatcher
import app.truenascompanion.ui.appViewModel
import app.truenascompanion.ui.components.ConfirmDialog
import app.truenascompanion.ui.components.ElevatedSection
import app.truenascompanion.ui.components.EmptyState
import app.truenascompanion.ui.components.IconBadge
import app.truenascompanion.ui.components.InfoBanner
import app.truenascompanion.ui.components.ScrollableErrorState
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

data class CloudSyncData(
    val tasks: List<CloudSyncTask>,
    val credentials: List<CloudCredential>,
    val providers: List<CloudProvider>,
) {
    fun provider(type: String): CloudProvider? = providers.firstOrNull { it.name == type }
    fun providerTitle(type: String): String = provider(type)?.title ?: CloudProviders.title(type)
    /** Tasks that use [c] (TrueNAS won't delete a credential that is still in use). */
    fun usedBy(c: CloudCredential): List<CloudSyncTask> = tasks.filter { it.credentialId == c.id }
    val anyRunning: Boolean get() = tasks.any { it.running }
}

/** The Restore dialog's draft; kept in the ViewModel so it survives picking a folder. */
data class RestoreDraft(val task: CloudSyncTask, val description: String, val mode: TransferMode = TransferMode.COPY, val path: String = "")

/** Result of "Verify" on a saved credential. */
data class VerifyOutcome(val credential: String, val result: VerifyResult?, val error: String?)

class CloudSyncViewModel(private val c: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow<UiState<CloudSyncData>>(UiState.Loading)
    val state = _state.asStateFlow()
    private val _busy = MutableStateFlow<Set<String>>(emptySet())
    val busy = _busy.asStateFlow()
    private val _refreshing = MutableStateFlow(false)
    val refreshing = _refreshing.asStateFlow()
    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()
    private val _restore = MutableStateFlow<RestoreDraft?>(null)
    val restore = _restore.asStateFlow()
    private val _verify = MutableStateFlow<VerifyOutcome?>(null)
    val verify = _verify.asStateFlow()
    val canNotify: Boolean get() = c.notifier.canPost()

    init {
        viewModelScope.launch { c.repository.reloadKey.collect { if (it != null) load() } }
    }

    fun refresh() = viewModelScope.launch { _refreshing.value = true; load(); _refreshing.value = false }

    fun refreshQuiet() { if (_state.value is UiState.Success) viewModelScope.launch { load() } }

    /** Progress refresh while something runs (the task list carries each task's current job). */
    suspend fun poll() {
        try {
            val tasks = c.repository.call { CloudSyncApi(it).tasks() }
            patch { it.copy(tasks = tasks) }
        } catch (_: Throwable) {
        }
    }

    private suspend fun load() {
        try {
            val data = c.repository.call { api ->
                val a = CloudSyncApi(api)
                CloudSyncData(a.tasks(), a.credentials(), runCatching { a.providers() }.getOrDefault(emptyList()))
            }
            _state.value = UiState.Success(data)
        } catch (e: Throwable) {
            if (_state.value !is UiState.Success) _state.value = UiState.Error(e.userMessage(), e) else _messages.trySend(e.userMessage())
        }
    }

    private fun patch(f: (CloudSyncData) -> CloudSyncData) = _state.update { s -> (s as? UiState.Success)?.let { UiState.Success(f(it.data)) } ?: s }

    private fun busyOp(key: String, done: String?, block: suspend (CloudSyncApi) -> Unit) {
        if (key in _busy.value) return
        _busy.update { it + key }
        viewModelScope.launch {
            try {
                c.repository.call { block(CloudSyncApi(it)) }
                done?.let { _messages.trySend(it) }
            } catch (e: Throwable) {
                _messages.trySend(e.userMessage())
            } finally {
                _busy.update { it - key }
                load()
            }
        }
    }

    fun setEnabled(t: CloudSyncTask, on: Boolean) {
        patch { d -> d.copy(tasks = d.tasks.map { if (it.id == t.id) it.copy(enabled = on) else it }) }
        busyOp("task:${t.id}", null) { it.setEnabled(t.id, on) }
    }

    fun run(t: CloudSyncTask, dryRun: Boolean, notify: Boolean) = busyOp("task:${t.id}", if (dryRun) "Dry run started" else "Cloud sync started") { api ->
        val jobId = api.sync(t.id, dryRun)
        if (notify) {
            c.settings.activeServerId.first()?.let { server ->
                CloudSyncWatcher.add(c.context, CloudRunWatch(server, t.id, t.name, jobId, dryRun, System.currentTimeMillis()))
            }
        }
    }

    fun abort(t: CloudSyncTask) = busyOp("task:${t.id}", null) { api ->
        _messages.trySend(if (api.abort(t.id)) "Stopping ${t.name}…" else "${t.name} wasn't running")
    }

    fun delete(t: CloudSyncTask) = busyOp("task:${t.id}", "Cloud sync task deleted") { it.deleteTask(t.id) }

    fun startRestore(t: CloudSyncTask) { _restore.value = RestoreDraft(t, "Restore of ${t.name}") }
    fun editRestore(f: (RestoreDraft) -> RestoreDraft) = _restore.update { it?.let(f) }
    fun cancelRestore() { _restore.value = null }

    fun confirmRestore() {
        val d = _restore.value ?: return
        _restore.value = null
        busyOp("task:${d.task.id}", "Restore task created. It's turned off: run it when you're ready.") { it.restore(d.task.id, d.description, d.mode, d.path) }
    }

    fun deleteCredential(cred: CloudCredential) = busyOp("cred:${cred.id}", "Credential deleted") { it.deleteCredential(cred.id) }

    fun verifyCredential(cred: CloudCredential) {
        val key = "cred:${cred.id}"
        if (key in _busy.value) return
        _busy.update { it + key }
        viewModelScope.launch {
            _verify.value = try {
                VerifyOutcome(cred.name, c.repository.call { CloudSyncApi(it).verify(CloudProviders.providerJson(cred.type, CloudProviders.valuesOf(cred.type, cred.provider))) }, null)
            } catch (e: Throwable) {
                VerifyOutcome(cred.name, null, e.userMessage())
            } finally {
                _busy.update { it - key }
            }
        }
    }

    fun closeVerify() { _verify.value = null }
}

private sealed interface CloudConfirm {
    data class Run(val task: CloudSyncTask, val dryRun: Boolean) : CloudConfirm
    data class Abort(val task: CloudSyncTask) : CloudConfirm
    data class Delete(val task: CloudSyncTask) : CloudConfirm
    data class Log(val task: CloudSyncTask) : CloudConfirm
    data class DeleteCredential(val credential: CloudCredential, val usedBy: List<CloudSyncTask>) : CloudConfirm
}

/** Everything the cloud sync list can ask for (overridden by the screen; previews use the no-op defaults). */
open class CloudSyncActions {
    open fun toggle(t: CloudSyncTask, on: Boolean) {}
    open fun run(t: CloudSyncTask) {}
    open fun dryRun(t: CloudSyncTask) {}
    open fun abort(t: CloudSyncTask) {}
    open fun edit(t: CloudSyncTask) {}
    open fun restore(t: CloudSyncTask) {}
    open fun delete(t: CloudSyncTask) {}
    open fun log(t: CloudSyncTask) {}
    open fun editCredential(c: CloudCredential) {}
    open fun verifyCredential(c: CloudCredential) {}
    open fun deleteCredential(c: CloudCredential) {}
    open fun addTask() {}
    open fun addCredential() {}
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CloudSyncScreen(
    onBack: () -> Unit,
    onEditTask: (Int?) -> Unit,
    onEditCredential: (Int?) -> Unit,
    onPickFolder: (String?) -> Unit,
    pickedPath: String?,
    onPickConsumed: () -> Unit,
) {
    val vm = appViewModel { CloudSyncViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val restore by vm.restore.collectAsStateWithLifecycle()
    val verify by vm.verify.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(vm) { vm.messages.collect { snackbar.showSnackbar(it) } }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refreshQuiet() }
    LaunchedEffect(pickedPath) { if (pickedPath != null) { vm.editRestore { it.copy(path = pickedPath) }; onPickConsumed() } }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var confirm by remember { mutableStateOf<CloudConfirm?>(null) }
    val data = (state as? UiState.Success)?.data
    val running = data?.anyRunning == true
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(running) {
        if (running) lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { while (true) { delay(3_000); vm.poll() } }
    }

    val actions = remember(vm) {
        object : CloudSyncActions() {
            override fun toggle(t: CloudSyncTask, on: Boolean) = vm.setEnabled(t, on)
            override fun run(t: CloudSyncTask) { confirm = CloudConfirm.Run(t, false) }
            override fun dryRun(t: CloudSyncTask) { confirm = CloudConfirm.Run(t, true) }
            override fun abort(t: CloudSyncTask) { confirm = CloudConfirm.Abort(t) }
            override fun edit(t: CloudSyncTask) = onEditTask(t.id)
            override fun restore(t: CloudSyncTask) = vm.startRestore(t)
            override fun delete(t: CloudSyncTask) { confirm = CloudConfirm.Delete(t) }
            override fun log(t: CloudSyncTask) { confirm = CloudConfirm.Log(t) }
            override fun editCredential(c: CloudCredential) = onEditCredential(c.id)
            override fun verifyCredential(c: CloudCredential) = vm.verifyCredential(c)
            override fun deleteCredential(c: CloudCredential) {
                confirm = CloudConfirm.DeleteCredential(c, (vm.state.value as? UiState.Success)?.data?.usedBy(c).orEmpty())
            }
            override fun addTask() = onEditTask(null)
            override fun addCredential() = onEditCredential(null)
        }
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(title = { Text("Cloud sync") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } })
                CloudTabs(tab) { tab = it }
            }
        },
        floatingActionButton = {
            if (data != null) ExtendedFloatingActionButton(
                onClick = { if (tab == 0) actions.addTask() else actions.addCredential() },
                icon = { Icon(Icons.Rounded.Add, null) },
                text = { Text(if (tab == 0) "New task" else "New credential") },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        PullToRefreshBox(isRefreshing = refreshing, onRefresh = { vm.refresh() }, modifier = Modifier.padding(padding).fillMaxSize()) {
            StateContent(state, onRetry = { vm.refresh() }, skeletonCount = 4, skeletonHeight = 170.dp) { data ->
                    if (tab == 0) CloudTasksContent(data, busy, System.currentTimeMillis(), actions)
                else CloudCredentialsContent(data, busy, actions)
            }
        }
    }

    val close = { confirm = null }
    when (val d = confirm) {
        null -> Unit
        is CloudConfirm.Run -> RunCloudSyncDialog(d.task, d.dryRun, vm.canNotify, onDismiss = close) { notify -> close(); vm.run(d.task, d.dryRun, notify) }
        is CloudConfirm.Abort -> ConfirmDialog(
            "Stop ${d.task.name}?", "The transfer stops now. Files already copied stay where they are; the next run picks up the rest.",
            "Stop", destructive = true, icon = Icons.Rounded.Stop, requireAuth = false, onConfirm = { close(); vm.abort(d.task) }, onDismiss = close,
        )
        is CloudConfirm.Delete -> ConfirmDialog(
            "Delete cloud sync task?",
            "“${d.task.name}” will be removed and won't run again" + (if (d.task.running) ", and the run in progress is stopped" else "") +
                ". Files on the NAS and in the cloud are not touched.",
            "Delete", destructive = true, onConfirm = { close(); vm.delete(d.task) }, onDismiss = close,
        )
        is CloudConfirm.Log -> CloudLogDialog(d.task, close)
        is CloudConfirm.DeleteCredential -> if (d.usedBy.isNotEmpty()) AlertDialog(
            onDismissRequest = close,
            title = { Text("Credential in use") },
            text = { Text("“${d.credential.name}” is used by ${d.usedBy.joinToString { "“${it.name}”" }}. Delete those tasks or switch them to another credential first.") },
            confirmButton = { TextButton(onClick = close) { Text("OK") } },
        ) else ConfirmDialog(
            "Delete credential?", "“${d.credential.name}” and the keys saved in it are removed from the NAS. The account at the provider is not touched.",
            "Delete", destructive = true, onConfirm = { close(); vm.deleteCredential(d.credential) }, onDismiss = close,
        )
    }
    restore?.let { r ->
        RestoreDialog(r, onChange = vm::editRestore, onBrowse = { onPickFolder(r.path.ifBlank { r.task.path }) }, onDismiss = vm::cancelRestore, onConfirm = vm::confirmRestore)
    }
    verify?.let { v -> VerifyDialog(v.credential, v.result, v.error, vm::closeVerify) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CloudTabs(tab: Int, onTab: (Int) -> Unit) {
    PrimaryTabRow(selectedTabIndex = tab) {
        Tab(selected = tab == 0, onClick = { onTab(0) }, text = { Text("Tasks", maxLines = 1) }, icon = { Icon(Icons.Rounded.Cloud, null) })
        Tab(selected = tab == 1, onClick = { onTab(1) }, text = { Text("Credentials", maxLines = 1) }, icon = { Icon(Icons.Rounded.Key, null) })
    }
}

/** Run / dry run confirmation, spelling out what Sync and Move delete. */
@Composable
fun RunCloudSyncDialog(task: CloudSyncTask, dryRun: Boolean, canNotify: Boolean, onDismiss: () -> Unit, onConfirm: (notify: Boolean) -> Unit) {
    var notify by rememberSaveable { mutableStateOf(canNotify) }
    val text = if (dryRun) {
        "Checks what “${task.name}” would transfer${if (task.mode != TransferMode.COPY) " or delete" else ""}, without changing anything. The result is in the task's log."
    } else {
        "Starts “${task.name}” now" + (if (!task.enabled) ", even though it's turned off" else "") + ". " + task.mode.describe(task.direction)
    }
    ConfirmDialog(
        title = if (dryRun) "Dry run ${task.name}?" else "Run ${task.name} now?",
        text = text,
        confirmLabel = if (dryRun) "Dry run" else "Run now",
        icon = if (dryRun) Icons.Rounded.Science else Icons.Rounded.PlayArrow,
        // Sync and Move delete files: ask for the fingerprint like other destructive actions.
        requireAuth = !dryRun && task.mode != TransferMode.COPY,
        onConfirm = { onConfirm(notify && canNotify) }, onDismiss = onDismiss,
    ) {
        Spacer(Modifier.height(8.dp))
        CommandBox(CloudSyncLogic.routeText(task, task.credentialName.ifBlank { CloudProviders.title(task.providerType) }), maxLines = 3)
        if (canNotify) Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = notify, onCheckedChange = { notify = it }, modifier = Modifier.testTag("notify-check"))
            Text("Notify me when it finishes", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun CloudLogDialog(task: CloudSyncTask, onDismiss: () -> Unit) {
    val job = task.job
    val text = listOfNotNull(job?.error, job?.logExcerpt?.trim()?.takeIf { it.isNotEmpty() }).joinToString("\n\n").ifBlank { "No log output for the last run." }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(task.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                job?.let {
                    Text("Last run: ${it.state.name.lowercase()} · ${Format.relativeTime(it.finishedMillis ?: it.startedMillis)}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                }
                Text(text, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

/** `cloudsync.restore`: a new, disabled task in the other direction that copies the cloud data into a NAS folder. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RestoreDialog(draft: RestoreDraft, onChange: ((RestoreDraft) -> RestoreDraft) -> Unit, onBrowse: () -> Unit, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val pathError = CloudSyncLogic.restorePathError(draft.path)
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Restore, null) },
        title = { Text("Restore from the cloud") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Creates a pull task that brings ${draft.task.remote} back to a NAS folder. It starts turned off, so nothing happens until you run it.",
                    style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(draft.description, { v -> onChange { it.copy(description = v) } }, label = { Text("Description") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(TransferMode.COPY, TransferMode.SYNC).forEach { m ->
                        FilterChip(selected = draft.mode == m, onClick = { onChange { it.copy(mode = m) } }, label = { Text(m.label) })
                    }
                }
                Text(draft.mode.describe(CloudDirection.PULL), style = MaterialTheme.typography.bodySmall,
                    color = if (draft.mode == TransferMode.SYNC) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(
                    draft.path, { v -> onChange { it.copy(path = v) } }, label = { Text("Restore into") }, singleLine = true,
                    placeholder = { Text("/mnt/tank/restore") }, isError = draft.path.isNotEmpty() && pathError != null,
                    supportingText = { Text(if (draft.path.isNotEmpty()) pathError ?: "An empty folder is safest" else "An empty folder is safest") },
                    trailingIcon = { TextButton(onClick = onBrowse) { Text("Browse") } },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { GlowButton(onClick = onConfirm, enabled = pathError == null) { Text("Create task") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun VerifyDialog(name: String, result: VerifyResult?, error: String?, onDismiss: () -> Unit) {
    val ok = result?.valid == true
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.VerifiedUser, null, tint = if (ok) LocalStatusColors.current.of(Health.HEALTHY) else MaterialTheme.colorScheme.error) },
        title = { Text(if (ok) "Credential works" else "Couldn't connect") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(if (ok) "TrueNAS signed in to the provider with “$name”." else "TrueNAS couldn't use “$name”:")
                (error ?: result?.message)?.takeIf { !ok }?.let { Spacer(Modifier.height(8.dp)); CommandBox(it, maxLines = 12) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
    )
}

// --- lists ---

@Composable
fun CloudTasksContent(data: CloudSyncData, busy: Set<String>, now: Long, actions: CloudSyncActions, modifier: Modifier = Modifier) {
    LazyColumn(modifier.fillMaxSize().testTag("page"), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (data.credentials.isEmpty()) item {
            InfoBanner("Add a cloud credential first (Credentials tab): it holds the keys or sign-in token for your cloud account.", health = Health.UNKNOWN)
        }
        if (data.tasks.isEmpty()) item {
            EmptyState(Icons.Rounded.Cloud, "No cloud sync tasks", "Back up a dataset to S3, Backblaze B2, Google Drive and others, or pull files from the cloud. Tap New task to start.")
        }
        items(data.tasks, key = { "task-${it.id}" }) { t -> CloudTaskCard(t, data.providerTitle(t.providerType), "task:${t.id}" in busy, now, actions) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CloudTaskCard(t: CloudSyncTask, providerTitle: String, busy: Boolean, now: Long, actions: CloudSyncActions) {
    val failed = !t.running && (t.job?.state == JobState.FAILED)
    ElevatedSection(onClick = { actions.edit(t) }, contentPadding = 16.dp, modifier = Modifier.testTag("cloud-task-${t.id}")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(if (t.direction == CloudDirection.PUSH) Icons.Rounded.CloudUpload else Icons.Rounded.CloudDownload, tint = when {
                failed -> MaterialTheme.colorScheme.error
                !t.enabled -> MaterialTheme.colorScheme.onSurfaceVariant
                else -> MaterialTheme.colorScheme.primary
            })
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(t.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${t.direction.label} · ${t.mode.label} · $providerTitle", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Switch(checked = t.enabled, onCheckedChange = { actions.toggle(t, it) }, enabled = !busy && !t.locked)
        }
        Spacer(Modifier.height(8.dp))
        val arrow = if (t.direction == CloudDirection.PUSH) "→" else "←"
        Text("${t.path}\n$arrow ${t.credentialName.ifBlank { providerTitle }}: ${t.remote}", style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace, maxLines = 3, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Schedule, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(6.dp))
            Text(if (t.enabled) CronText.describe(t.schedule) else "Off · runs only when started here", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(6.dp))
        val job = t.job
        when {
            t.running -> {
                Text(job?.progressText ?: "Running…", style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(6.dp))
                val pct = job?.percent
                if (pct != null && pct > 0) LinearProgressIndicator(progress = { (pct / 100).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                else LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            else -> LastRunLine(t, now)
        }
        if (t.locked) {
            Spacer(Modifier.height(6.dp))
            Text("The dataset is locked; unlock it in the TrueNAS web UI.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (t.running) {
                OutlinedButton(onClick = { actions.abort(t) }, enabled = !busy, modifier = Modifier.weight(1f), contentPadding = BUTTON_PADDING) {
                    Icon(Icons.Rounded.Stop, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Abort", maxLines = 1)
                }
            } else {
                FilledTonalButton(onClick = { actions.run(t) }, enabled = !busy && !t.locked, modifier = Modifier.weight(1f), contentPadding = BUTTON_PADDING) {
                    Icon(Icons.Rounded.PlayArrow, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Run now", maxLines = 1)
                }
                OutlinedButton(onClick = { actions.dryRun(t) }, enabled = !busy && !t.locked, modifier = Modifier.weight(1f), contentPadding = BUTTON_PADDING) {
                    Icon(Icons.Rounded.Science, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Dry run", maxLines = 1)
                }
            }
            TaskMenu(t, busy, actions)
        }
    }
}

private val BUTTON_PADDING = PaddingValues(horizontal = 12.dp, vertical = 8.dp)

@Composable
private fun LastRunLine(t: CloudSyncTask, now: Long) {
    val job = t.job
    val (health, text) = when (job?.state) {
        null -> Health.UNKNOWN to "Hasn't run yet"
        JobState.SUCCESS -> Health.HEALTHY to "Last run ${Format.relativeTime(job.finishedMillis ?: job.startedMillis, now)}"
        JobState.ABORTED -> Health.WARNING to "Stopped ${Format.relativeTime(job.finishedMillis ?: job.startedMillis, now)}"
        JobState.FAILED -> Health.CRITICAL to ("Failed ${Format.relativeTime(job.finishedMillis ?: job.startedMillis, now)}: " +
            (job.error?.lineSequence()?.firstOrNull { it.isNotBlank() } ?: "see the log"))
        else -> Health.UNKNOWN to job.state.name.lowercase().replaceFirstChar { it.uppercase() }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).background(LocalStatusColors.current.of(health), CircleShape))
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis,
            color = if (health == Health.CRITICAL) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun TaskMenu(t: CloudSyncTask, busy: Boolean, actions: CloudSyncActions) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, enabled = !busy) { Icon(Icons.Rounded.MoreVert, "More actions for ${t.name}") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("Edit") }, onClick = { open = false; actions.edit(t) })
            DropdownMenuItem(text = { Text("Last log") }, enabled = t.job != null, onClick = { open = false; actions.log(t) })
            if (t.direction == CloudDirection.PUSH) DropdownMenuItem(text = { Text("Restore…") }, onClick = { open = false; actions.restore(t) })
            app.truenascompanion.ui.components.DestructiveMenuItem("Delete", Icons.Rounded.Delete) { open = false; actions.delete(t) }
        }
    }
}

@Composable
fun CloudCredentialsContent(data: CloudSyncData, busy: Set<String>, actions: CloudSyncActions, modifier: Modifier = Modifier) {
    LazyColumn(modifier.fillMaxSize().testTag("page"), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (data.credentials.isEmpty()) item {
            EmptyState(Icons.Rounded.Key, "No cloud credentials", "A credential holds the keys or sign-in token for one cloud account. Add one, then use it in cloud sync tasks.")
        }
        items(data.credentials, key = { "cred-${it.id}" }) { c ->
            val used = data.usedBy(c).size
            ElevatedSection(onClick = { actions.editCredential(c) }, contentPadding = 16.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconBadge(Icons.Rounded.Key)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(c.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(listOfNotNull(data.providerTitle(c.type), CloudProviders.summary(c.type, c.provider)).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(if (used == 0) "Not used by any task" else "Used by $used task${if (used == 1) "" else "s"}",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = { actions.verifyCredential(c) }, enabled = "cred:${c.id}" !in busy, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Rounded.VerifiedUser, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                        Text(if ("cred:${c.id}" in busy) "Checking…" else "Verify", maxLines = 1)
                    }
                    OutlinedButton(onClick = { actions.deleteCredential(c) }, enabled = "cred:${c.id}" !in busy, modifier = Modifier.weight(1f)) {
                        Text("Delete", maxLines = 1, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}
