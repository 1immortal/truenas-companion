package app.truenascompanion.ui.replication

import app.truenascompanion.ui.components.GlowButton
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import app.truenascompanion.ui.components.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import app.truenascompanion.ui.components.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.truenascompanion.AppContainer
import app.truenascompanion.data.api.ReplicationApi
import app.truenascompanion.data.api.userMessage
import app.truenascompanion.data.cloud.CloudRunWatch
import app.truenascompanion.data.model.Health
import app.truenascompanion.data.replication.ReadonlyPolicy
import app.truenascompanion.data.replication.ReplDirection
import app.truenascompanion.data.replication.ReplTiming
import app.truenascompanion.data.replication.ReplTransport
import app.truenascompanion.data.replication.ReplicationForm
import app.truenascompanion.data.replication.ReplicationLogic
import app.truenascompanion.data.replication.Retention
import app.truenascompanion.data.replication.SnapshotTaskRef
import app.truenascompanion.data.replication.SshConnection
import app.truenascompanion.notify.CloudSyncWatcher
import app.truenascompanion.ui.appViewModel
import app.truenascompanion.ui.components.ConfirmDialog
import app.truenascompanion.ui.components.ElevatedSection
import app.truenascompanion.ui.components.InfoBanner
import app.truenascompanion.ui.components.PickField
import app.truenascompanion.ui.components.ScrollableErrorState
import app.truenascompanion.ui.components.SecretTextField
import app.truenascompanion.ui.components.SectionTitle
import app.truenascompanion.ui.components.SkeletonList
import app.truenascompanion.ui.components.UiState
import app.truenascompanion.ui.components.isLoginRequired
import app.truenascompanion.ui.protection.SwitchRow
import app.truenascompanion.ui.tasks.CronSchedulePicker
import app.truenascompanion.ui.tasks.splitFieldErrors
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The dataset list shown while choosing sources or the target; [remote]: listed over SSH by zettarepl. */
data class DatasetBrowser(
    val forSource: Boolean,
    val remote: Boolean,
    val datasets: List<String> = emptyList(),
    val selected: Set<String> = emptySet(),
    val loading: Boolean = true,
    val error: String? = null,
)

data class ReplEditorRefs(val connections: List<SshConnection> = emptyList(), val snapshotTasks: List<SnapshotTaskRef> = emptyList(), val namingSchemas: List<String> = emptyList()) {
    fun connection(id: Int?) = connections.firstOrNull { it.id == id }
}

class ReplicationTaskEditorViewModel(private val c: AppContainer, private val id: Int?) : ViewModel() {
    private val _state = MutableStateFlow<UiState<ReplicationForm>>(UiState.Loading)
    val state = _state.asStateFlow()
    private val _form = MutableStateFlow(ReplicationForm())
    val form = _form.asStateFlow()
    private val _refs = MutableStateFlow(ReplEditorRefs())
    val refs = _refs.asStateFlow()
    private val _serverErrors = MutableStateFlow<Map<String, String>>(emptyMap())
    val serverErrors = _serverErrors.asStateFlow()
    private val _generalError = MutableStateFlow<String?>(null)
    val generalError = _generalError.asStateFlow()
    private val _saving = MutableStateFlow(false)
    val saving = _saving.asStateFlow()
    private val _saved = Channel<String>(Channel.BUFFERED)
    val saved = _saved.receiveAsFlow()
    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()
    private val _browser = MutableStateFlow<DatasetBrowser?>(null)
    val browser = _browser.asStateFlow()
    val canNotify: Boolean get() = c.notifier.canPost()

    init { load() }

    fun load() = viewModelScope.launch {
        _state.value = UiState.Loading
        try {
            val (refs, task) = c.repository.call { api ->
                val a = ReplicationApi(api)
                ReplEditorRefs(runCatching { a.connections() }.getOrDefault(emptyList()), runCatching { a.snapshotTasks() }.getOrDefault(emptyList()), a.namingSchemas()) to
                    id?.let { a.task(it) ?: throw IllegalStateException("This replication task no longer exists") }
            }
            _refs.value = refs
            val f = task?.let(ReplicationLogic::form) ?: ReplicationForm(
                sshCredentialsId = refs.connections.singleOrNull()?.id,
                transport = if (refs.connections.isEmpty()) ReplTransport.LOCAL else ReplTransport.SSH,
            )
            _form.value = f
            _state.value = UiState.Success(f)
        } catch (e: Throwable) {
            _state.value = UiState.Error(e.userMessage(), e)
        }
    }

    /** Back from adding a connection: pick it up without touching the rest of the form. */
    fun reloadConnections() = viewModelScope.launch {
        runCatching { c.repository.call { ReplicationApi(it).connections() } }.getOrNull()?.let { list ->
            val before = _refs.value.connections.map { it.id }.toSet()
            _refs.update { it.copy(connections = list) }
            val added = list.firstOrNull { it.id !in before }
            if (added != null && _form.value.sshCredentialsId == null) edit { it.copy(sshCredentialsId = added.id) }
        }
    }

    fun edit(f: (ReplicationForm) -> ReplicationForm) {
        val before = _form.value
        var after = f(before)
        // Switching sides means the dataset names belong to another system.
        if (after.direction != before.direction || (after.transport == ReplTransport.LOCAL) != (before.transport == ReplTransport.LOCAL)) {
            if (after.direction != before.direction) after = after.copy(sourceDatasets = emptyList(), targetDataset = "", exclude = "", snapshotTaskIds = emptySet())
        }
        _form.value = after
        _serverErrors.value = _serverErrors.value.filterKeys { k -> FIELD_OF[k]?.let { it(before) == it(after) } ?: true }
        _generalError.value = null
    }

    fun save() {
        val f = _form.value
        if (ReplicationLogic.errors(f, _refs.value.snapshotTasks).isNotEmpty() || _saving.value) return
        _saving.value = true
        viewModelScope.launch {
            try {
                val body = ReplicationLogic.taskJson(f)
                c.repository.call { api -> ReplicationApi(api).let { if (id == null) it.create(body) else it.update(id, body) } }
                _saved.trySend(if (id == null) "Replication task created" else "Replication task saved")
            } catch (e: Throwable) {
                val (mine, general) = splitFieldErrors(e, ReplicationLogic.FIELDS, ReplicationLogic.FIELD_ALIASES)
                _serverErrors.value = mine
                _generalError.value = general
            } finally {
                _saving.value = false
            }
        }
    }

    /** `replication.run_onetime`: replicate now with these settings, without saving a task. */
    fun runOnce(notify: Boolean) {
        val f = _form.value
        if (ReplicationLogic.errors(f, _refs.value.snapshotTasks, onetime = true).isNotEmpty() || _saving.value) return
        _saving.value = true
        viewModelScope.launch {
            try {
                val jobId = c.repository.call { ReplicationApi(it).runOnetime(ReplicationLogic.taskJson(f, onetime = true)) }
                if (notify) c.settings.activeServerId.first()?.let { server ->
                    CloudSyncWatcher.add(c.context, CloudRunWatch(server, -1, f.name.ifBlank { "One-time replication" }, jobId, false, System.currentTimeMillis(), CloudRunWatch.KIND_REPLICATION))
                }
                _messages.trySend("Replication started. Follow it in System › Tasks.")
            } catch (e: Throwable) {
                val (mine, general) = splitFieldErrors(e, ReplicationLogic.FIELDS, ReplicationLogic.FIELD_ALIASES)
                _serverErrors.value = mine
                _generalError.value = general
            } finally {
                _saving.value = false
            }
        }
    }

    // --- dataset browser ---

    private fun sideIsRemote(forSource: Boolean): Boolean {
        val f = _form.value
        if (f.transport == ReplTransport.LOCAL) return false
        return if (forSource) f.direction == ReplDirection.PULL else f.direction == ReplDirection.PUSH
    }

    fun browse(forSource: Boolean) {
        val f = _form.value
        val remote = sideIsRemote(forSource)
        if (remote && f.sshCredentialsId == null) { _messages.trySend("Choose an SSH connection first"); return }
        val selected = if (forSource) f.sourceDatasets.toSet() else setOfNotNull(f.targetDataset.trim().trim('/').ifEmpty { null })
        _browser.value = DatasetBrowser(forSource, remote, selected = selected)
        viewModelScope.launch {
            val r = try {
                val list = c.repository.call { api ->
                    if (remote) ReplicationApi(api).listDatasets(f.transport, f.sshCredentialsId)
                    else api.datasets().filterNot { it.isSystem }.map { it.id }.sorted()
                }
                DatasetBrowser(forSource, remote, list, selected, loading = false)
            } catch (e: Throwable) {
                DatasetBrowser(forSource, remote, selected = selected, loading = false, error = e.userMessage())
            }
            _browser.update { b -> if (b?.forSource == forSource) r else b }
        }
    }

    fun toggleDataset(name: String) = _browser.update { b ->
        b?.let { if (it.forSource) it.copy(selected = if (name in it.selected) it.selected - name else it.selected + name) else it.copy(selected = setOf(name)) }
    }

    fun chooseDatasets() {
        val b = _browser.value ?: return
        _browser.value = null
        if (b.forSource) edit { it.copy(sourceDatasets = b.selected.sorted()) }
        else b.selected.firstOrNull()?.let { t -> edit { it.copy(targetDataset = t) } }
    }

    fun closeBrowser() { _browser.value = null }

    companion object {
        /** Which form value each server error belongs to (an error clears when its value changes). */
        private val FIELD_OF: Map<String, (ReplicationForm) -> Any?> = mapOf(
            "name" to { f -> f.name }, "ssh_credentials" to { f -> f.sshCredentialsId to f.transport }, "source_datasets" to { f -> f.sourceDatasets },
            "target_dataset" to { f -> f.targetDataset }, "exclude" to { f -> f.exclude to f.recursive }, "recursive" to { f -> f.recursive to f.replicate },
            "properties" to { f -> f.properties to f.replicate }, "properties_override" to { f -> f.propertiesOverride },
            "retention_policy" to { f -> f.retention to f.replicate to f.useRegex }, "lifetime_value" to { f -> f.lifetimeValue to f.lifetimeUnit },
            "encryption_key" to { f -> f.encryptionKey to f.encryptionKeyFormat to f.encryptionInherit to f.encryption },
            "encryption_key_location" to { f -> f.encryptionKeyLocation to f.encryptionKeyInTrueNas },
            "periodic_snapshot_tasks" to { f -> f.snapshotTaskIds to f.namingSchemas to f.useRegex }, "naming_schema" to { f -> f.namingSchemas to f.useRegex },
            "name_regex" to { f -> f.nameRegex to f.useRegex }, "schedule" to { f -> f.schedule to f.timing }, "auto" to { f -> f.timing to f.snapshotTaskIds },
            "netcat_active_side_port_min" to { f -> f.netcatPortMin to f.netcatActiveSide to f.netcatListenAddress to f.netcatConnectAddress },
            "netcat_active_side_port_max" to { f -> f.netcatPortMax }, "speed_limit" to { f -> f.speedLimit }, "compression" to { f -> f.compression to f.transport },
            "retries" to { f -> f.retries }, "direction" to { f -> f.direction }, "transport" to { f -> f.transport },
            "hold_pending_snapshots" to { f -> f.holdPendingSnapshots to f.direction },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReplicationTaskEditorScreen(id: Int?, onAddConnection: () -> Unit, onBack: () -> Unit) {
    val vm = appViewModel(key = "repl-task:${id ?: "new"}") { ReplicationTaskEditorViewModel(it, id) }
    val state by vm.state.collectAsStateWithLifecycle()
    val form by vm.form.collectAsStateWithLifecycle()
    val refs by vm.refs.collectAsStateWithLifecycle()
    val serverErrors by vm.serverErrors.collectAsStateWithLifecycle()
    val generalError by vm.generalError.collectAsStateWithLifecycle()
    val saving by vm.saving.collectAsStateWithLifecycle()
    val browser by vm.browser.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(vm) { vm.saved.collect { onBack() } }
    LaunchedEffect(vm) { vm.messages.collect { snackbar.showSnackbar(it) } }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { if (state is UiState.Success) vm.reloadConnections() }
    val original = (state as? UiState.Success)?.data
    val dirty = original != null && original != form
    val local = ReplicationLogic.errors(form, refs.snapshotTasks)
    var confirmDiscard by remember { mutableStateOf(false) }
    var confirmScratch by remember { mutableStateOf(false) }
    var confirmOnce by remember { mutableStateOf(false) }
    BackHandler(enabled = dirty) { confirmDiscard = true }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (id == null) "New replication task" else "Edit replication task") },
                navigationIcon = { IconButton(onClick = { if (dirty) confirmDiscard = true else onBack() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
                actions = {
                    if (saving) CircularProgressIndicator(Modifier.size(22.dp).padding(end = 4.dp), strokeWidth = 2.dp)
                    TextButton(onClick = vm::save, enabled = (dirty || id == null) && !saving && local.isEmpty()) { Text("Save") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            when (val s = state) {
                UiState.Loading -> SkeletonList(4, 150.dp)
                is UiState.Error -> ScrollableErrorState(s.message, s.isLoginRequired) { vm.load() }
                is UiState.Success -> ReplicationEditorContent(
                    form, refs, local + serverErrors, generalError, showErrors = dirty || serverErrors.isNotEmpty(),
                    onChange = { change ->
                        val next = change(form)
                        if (next.allowFromScratch && !form.allowFromScratch) confirmScratch = true else vm.edit(change)
                    },
                    onBrowse = vm::browse,
                    onAddConnection = onAddConnection,
                    onRunOnce = { confirmOnce = true },
                    runOnceEnabled = !saving && ReplicationLogic.errors(form, refs.snapshotTasks, onetime = true).isEmpty(),
                )
            }
        }
    }
    browser?.let { b -> DatasetPickerDialog(b, onToggle = vm::toggleDataset, onChoose = vm::chooseDatasets, onDismiss = vm::closeBrowser, onRetry = { vm.browse(b.forSource) }) }
    if (confirmScratch) ConfirmDialog(
        title = "Allow replicating from scratch?",
        text = "If none of the snapshots on the target match the source, TrueNAS will DESTROY every snapshot in ${form.targetDataset.ifBlank { "the target dataset" }} and its children " +
            "and send everything again. Data that only exists in those snapshots is lost. Only turn this on if the target holds nothing you need.",
        confirmLabel = "Allow", destructive = true, icon = Icons.Rounded.Warning,
        onConfirm = { confirmScratch = false; vm.edit { it.copy(allowFromScratch = true) } }, onDismiss = { confirmScratch = false },
    )
    if (confirmOnce) RunOnceDialog(form, vm.canNotify, onDismiss = { confirmOnce = false }) { notify -> confirmOnce = false; vm.runOnce(notify) }
    if (confirmDiscard) ConfirmDialog(
        title = "Discard changes?", text = "Your changes to this replication task haven't been saved.",
        confirmLabel = "Discard", destructive = true, requireAuth = false,
        onConfirm = { confirmDiscard = false; onBack() }, onDismiss = { confirmDiscard = false },
    )
}

@Composable
fun RunOnceDialog(form: ReplicationForm, canNotify: Boolean, onDismiss: () -> Unit, onConfirm: (Boolean) -> Unit) {
    var notify by rememberSaveable { mutableStateOf(canNotify) }
    ConfirmDialog(
        title = "Replicate once now?",
        text = "Sends the snapshots with these settings right now, without saving a task. " +
            (if (form.allowFromScratch) "Allow from scratch is on: snapshots on the target may be destroyed. " else "") +
            "The run shows up in System › Tasks.",
        confirmLabel = "Run once", icon = Icons.Rounded.PlayArrow, requireAuth = form.allowFromScratch,
        onConfirm = { onConfirm(notify && canNotify) }, onDismiss = onDismiss,
    ) {
        if (canNotify) Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = notify, onCheckedChange = { notify = it }, modifier = Modifier.testTag("notify-check"))
            Text("Notify me when it finishes", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** Datasets as an indented tree; several can be ticked for sources, one for the target. */
@Composable
fun DatasetPickerDialog(b: DatasetBrowser, onToggle: (String) -> Unit, onChoose: () -> Unit, onDismiss: () -> Unit, onRetry: () -> Unit = {}) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (b.forSource) "Source datasets" else "Target dataset", maxLines = 1) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(if (b.remote) "On the other system" else "On this NAS", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                when {
                    b.loading -> Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp); Spacer(Modifier.width(12.dp))
                        Text(if (b.remote) "Asking the other system…" else "Loading datasets…")
                    }
                    b.error != null -> Column {
                        Text(b.error.lineSequence().filter { it.isNotBlank() }.take(4).joinToString("\n"), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = onRetry) { Text("Try again") }
                    }
                    b.datasets.isEmpty() -> Text("No datasets found.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else -> LazyColumn(Modifier.heightIn(max = 380.dp).testTag("dataset-list")) {
                        items(b.datasets, key = { it }) { ds ->
                            val depth = ds.count { it == '/' }
                            Row(
                                Modifier.fillMaxWidth().clickable { onToggle(ds) }.padding(start = (depth.coerceAtMost(5) * 14).dp, top = 2.dp, bottom = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(checked = ds in b.selected, onCheckedChange = { onToggle(ds) })
                                Icon(Icons.Rounded.Storage, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(8.dp))
                                Text(if (depth == 0) ds else ds.substringAfterLast('/'), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
                if (!b.forSource && !b.loading) Text("You can also type a new dataset name in the field; it is created on the first run.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { GlowButton(onClick = onChoose, enabled = !b.loading && b.error == null && b.selected.isNotEmpty()) { Text(if (b.forSource) "Use ${b.selected.size} selected" else "Use this dataset") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Stateless replication task form (also used by the screenshot previews). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReplicationEditorContent(
    form: ReplicationForm,
    refs: ReplEditorRefs,
    errors: Map<String, String>,
    generalError: String?,
    showErrors: Boolean = true,
    startAdvanced: Boolean = false,
    onChange: ((ReplicationForm) -> ReplicationForm) -> Unit,
    onBrowse: (forSource: Boolean) -> Unit = {},
    onAddConnection: () -> Unit = {},
    onRunOnce: () -> Unit = {},
    runOnceEnabled: Boolean = false,
) {
    fun err(k: String) = errors[k]?.takeIf { showErrors || k in ALWAYS }
    val push = form.direction == ReplDirection.PUSH
    val localTransport = form.transport == ReplTransport.LOCAL
    var advanced by rememberSaveable { mutableStateOf(startAdvanced) }
    val mono = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(16.dp).testTag("page"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        generalError?.let { InfoBanner(it, health = Health.CRITICAL) }
        SectionTitle("What to do")
        ElevatedSection(contentPadding = 14.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    form.name, { v -> onChange { it.copy(name = v) } }, label = { Text("Name *") }, singleLine = true,
                    placeholder = { Text("e.g. tank to backup NAS") }, isError = err("name") != null,
                    supportingText = err("name")?.let { { Text(it) } }, modifier = Modifier.fillMaxWidth(),
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ReplDirection.entries.forEach { d ->
                        FilterChip(selected = form.direction == d, onClick = { onChange { it.copy(direction = d) } },
                            label = { Text(if (d == ReplDirection.PUSH) "Push (send from here)" else "Pull (fetch to here)") },
                            leadingIcon = { Icon(if (d == ReplDirection.PUSH) Icons.Rounded.Upload else Icons.Rounded.Download, null, Modifier.size(18.dp)) })
                    }
                }
                err("direction")?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ReplTransport.entries.forEach { t -> FilterChip(selected = form.transport == t, onClick = { onChange { it.copy(transport = t) } }, label = { Text(t.label) }) }
                }
                Text(err("transport") ?: form.transport.help, style = MaterialTheme.typography.bodySmall,
                    color = if (err("transport") != null || form.transport == ReplTransport.NETCAT) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                if (!localTransport) {
                    if (refs.connections.isEmpty()) {
                        Text("There are no SSH connections yet.", style = MaterialTheme.typography.bodyMedium)
                        OutlinedButton(onClick = onAddConnection) { Icon(Icons.Rounded.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Add SSH connection") }
                    } else {
                        PickField("SSH connection *", refs.connection(form.sshCredentialsId), refs.connections, { "${it.name} · ${it.address}" },
                            onPick = { c -> onChange { it.copy(sshCredentialsId = c.id) } }, isError = err("ssh_credentials") != null,
                            supporting = err("ssh_credentials"), emptyText = "Choose a connection")
                        TextButton(onClick = onAddConnection) { Text("Add another connection") }
                    }
                    SwitchRow("Use sudo for zfs", form.sudo, "When the remote user isn't root and may run zfs with sudo") { v -> onChange { it.copy(sudo = v) } }
                }
                if (form.transport == ReplTransport.NETCAT) NetcatFields(form, ::err, onChange)
            }
        }
        SectionTitle("Source")
        ElevatedSection(contentPadding = 14.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (push || localTransport) "Datasets on this NAS" else "Datasets on the other system", style = MaterialTheme.typography.bodyMedium)
                if (form.sourceDatasets.isEmpty()) Text(err("source_datasets") ?: "None chosen yet", style = MaterialTheme.typography.bodySmall,
                    color = if (err("source_datasets") != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                else FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.testTag("source-chips")) {
                    form.sourceDatasets.forEach { ds ->
                        InputChip(selected = false, onClick = {}, label = { Text(ds, fontFamily = FontFamily.Monospace) },
                            trailingIcon = { Icon(Icons.Rounded.Close, "Remove $ds", Modifier.size(16.dp).clickable { onChange { f -> f.copy(sourceDatasets = f.sourceDatasets - ds) } }) })
                    }
                }
                if (form.sourceDatasets.isNotEmpty()) err("source_datasets")?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                OutlinedButton(onClick = { onBrowse(true) }, modifier = Modifier.testTag("pick-source")) { Text("Choose datasets") }
                SwitchRow("Include child datasets", form.recursive, err("recursive") ?: "Replicate every dataset below the source too") { v -> onChange { it.copy(recursive = v) } }
                if (form.recursive && !form.replicate) OutlinedTextField(
                    form.exclude, { v -> onChange { it.copy(exclude = v) } }, label = { Text("Skip these child datasets") }, minLines = 1, maxLines = 5,
                    placeholder = { Text("${form.sourceDatasets.firstOrNull() ?: "tank/data"}/scratch") }, textStyle = mono,
                    isError = err("exclude") != null, supportingText = { Text(err("exclude") ?: "One dataset per line") }, modifier = Modifier.fillMaxWidth(),
                )
                SwitchRow("Full filesystem replication", form.replicate, "Send the whole dataset tree with properties, clones and snapshot holds (zfs send -R). Needs child datasets, keeps snapshots like the source.") { v ->
                    onChange { it.copy(replicate = v, recursive = if (v) true else it.recursive, properties = if (v) true else it.properties, retention = if (v) Retention.SOURCE else it.retention) }
                }
            }
        }
        SectionTitle("Destination")
        ElevatedSection(contentPadding = 14.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    form.targetDataset, { v -> onChange { it.copy(targetDataset = v.trim()) } }, label = { Text("Target dataset *") }, singleLine = true,
                    placeholder = { Text("backup/tank") }, textStyle = mono, isError = err("target_dataset") != null,
                    supportingText = { Text(err("target_dataset") ?: if (push && !localTransport) "On the other system; created if it doesn't exist" else "On this NAS; created if it doesn't exist") },
                    trailingIcon = { TextButton(onClick = { onBrowse(false) }) { Text("Browse") } }, modifier = Modifier.fillMaxWidth(),
                )
                Text("Read-only target", style = MaterialTheme.typography.bodyMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ReadonlyPolicy.entries.forEach { r -> FilterChip(selected = form.readonly == r, onClick = { onChange { it.copy(readonly = r) } }, label = { Text(r.label) }) }
                }
                Text(form.readonly.help, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                SwitchRow("Encrypt the target", form.encryption, "Store the replicated datasets encrypted on the target") { v -> onChange { it.copy(encryption = v) } }
                if (form.encryption) EncryptionFields(form, ::err, onChange)
                SwitchRow("Allow from scratch", form.allowFromScratch,
                    if (form.allowFromScratch) "DANGER: if no snapshots match, every snapshot on the target is destroyed and replicated again."
                    else "Off: replication stops instead of destroying snapshots on the target") { v -> onChange { it.copy(allowFromScratch = v) } }
                if (form.allowFromScratch) InfoBanner("Allow from scratch can destroy all snapshots on the target dataset.", health = Health.CRITICAL)
            }
        }
        SectionTitle("Snapshots")
        ElevatedSection(contentPadding = 14.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { SnapshotFields(form, refs, ::err, onChange) }
        }
        SectionTitle("When")
        ElevatedSection(contentPadding = 14.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val shown = if (!push && form.timing == ReplTiming.AFTER_SNAPSHOTS) ReplTiming.SCHEDULE else form.timing
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ReplTiming.entries.filter { push || it != ReplTiming.AFTER_SNAPSHOTS }.forEach { t ->
                        FilterChip(selected = shown == t, onClick = { onChange { it.copy(timing = t) } }, label = { Text(t.label) })
                    }
                }
                err("auto")?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                when (shown) {
                    ReplTiming.AFTER_SNAPSHOTS -> Text("Runs each time a chosen periodic snapshot task has taken its snapshots.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    ReplTiming.MANUAL -> Text("Runs only when you start it (Run now).", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    ReplTiming.SCHEDULE -> {
                        CronSchedulePicker(form.schedule, { s -> onChange { it.copy(schedule = s) } }, error = err("schedule")?.takeIf { it != "Fix the schedule" })
                        SwitchRow("Only snapshots that match the schedule", form.onlyMatchingSchedule, "Skip snapshots taken at other times") { v -> onChange { it.copy(onlyMatchingSchedule = v) } }
                    }
                }
                SwitchRow("Enabled", form.enabled, "Turned off tasks don't run, not even with Run now") { v -> onChange { it.copy(enabled = v) } }
            }
        }
        TextButton(onClick = { advanced = !advanced }) {
            Icon(if (advanced) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null); Spacer(Modifier.width(6.dp))
            Text(if (advanced) "Hide transfer options" else "Transfer options")
        }
        if (advanced || TRANSFER_KEYS.any { err(it) != null }) ElevatedSection(contentPadding = 14.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { TransferFields(form, ::err, onChange) }
        }
        OutlinedButton(onClick = onRunOnce, enabled = runOnceEnabled, modifier = Modifier.fillMaxWidth().testTag("run-once")) {
            Icon(Icons.Rounded.PlayArrow, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Replicate once without saving")
        }
    }
}

private val ALWAYS = setOf("name", "recursive", "properties", "retention_policy", "auto")
private val TRANSFER_KEYS = listOf("speed_limit", "compression", "retries", "properties", "properties_override")

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NetcatFields(form: ReplicationForm, err: (String) -> String?, onChange: ((ReplicationForm) -> ReplicationForm) -> Unit) {
    Text("Which side opens the port", style = MaterialTheme.typography.bodyMedium)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("LOCAL" to "This NAS", "REMOTE" to "Other system").forEach { (v, l) -> FilterChip(selected = form.netcatActiveSide == v, onClick = { onChange { it.copy(netcatActiveSide = v) } }, label = { Text(l) }) }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(form.netcatPortMin, { v -> onChange { it.copy(netcatPortMin = v.filter(Char::isDigit).take(5)) } }, label = { Text("Lowest port") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), isError = err("netcat_active_side_port_min") != null, modifier = Modifier.weight(1f))
        OutlinedTextField(form.netcatPortMax, { v -> onChange { it.copy(netcatPortMax = v.filter(Char::isDigit).take(5)) } }, label = { Text("Highest port") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), isError = err("netcat_active_side_port_max") != null, modifier = Modifier.weight(1f))
    }
    (err("netcat_active_side_port_min") ?: err("netcat_active_side_port_max"))?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    OutlinedTextField(form.netcatListenAddress, { v -> onChange { it.copy(netcatListenAddress = v.trim()) } }, label = { Text("Listen on address") }, singleLine = true,
        placeholder = { Text("Any") }, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(form.netcatConnectAddress, { v -> onChange { it.copy(netcatConnectAddress = v.trim()) } }, label = { Text("Connect to address") }, singleLine = true,
        placeholder = { Text("The SSH host") }, modifier = Modifier.fillMaxWidth())
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EncryptionFields(form: ReplicationForm, err: (String) -> String?, onChange: ((ReplicationForm) -> ReplicationForm) -> Unit) {
    SwitchRow("Inherit encryption", form.encryptionInherit, "Use the encryption of the target's parent dataset") { v -> onChange { it.copy(encryptionInherit = v) } }
    if (form.encryptionInherit) return
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("PASSPHRASE" to "Passphrase", "HEX" to "Hex key").forEach { (v, l) -> FilterChip(selected = form.encryptionKeyFormat == v, onClick = { onChange { it.copy(encryptionKeyFormat = v) } }, label = { Text(l) }) }
    }
    SecretTextField(form.encryptionKey, { v -> onChange { it.copy(encryptionKey = v) } }, if (form.encryptionKeyFormat == "HEX") "Hex key *" else "Passphrase *",
        isError = err("encryption_key") != null, supporting = err("encryption_key") ?: "Without it the replicated data can't be unlocked. Keep a copy somewhere safe.")
    SwitchRow("Store the key in the TrueNAS database", form.encryptionKeyInTrueNas, "On the target TrueNAS, so it can unlock the datasets itself") { v -> onChange { it.copy(encryptionKeyInTrueNas = v) } }
    if (!form.encryptionKeyInTrueNas) OutlinedTextField(
        form.encryptionKeyLocation, { v -> onChange { it.copy(encryptionKeyLocation = v.trim()) } }, label = { Text("Key file on the target *") }, singleLine = true,
        placeholder = { Text("/root/replication.key") }, isError = err("encryption_key_location") != null,
        supportingText = err("encryption_key_location")?.let { { Text(it) } }, modifier = Modifier.fillMaxWidth(),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SnapshotFields(form: ReplicationForm, refs: ReplEditorRefs, err: (String) -> String?, onChange: ((ReplicationForm) -> ReplicationForm) -> Unit) {
    val push = form.direction == ReplDirection.PUSH
    val mono = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
    SwitchRow("Match names with a regular expression", form.useRegex, "Instead of snapshot tasks and naming schemas") { v -> onChange { it.copy(useRegex = v) } }
    if (form.useRegex) {
        OutlinedTextField(form.nameRegex, { v -> onChange { it.copy(nameRegex = v) } }, label = { Text("Snapshot name regex *") }, singleLine = true,
            placeholder = { Text("auto-.*") }, textStyle = mono, isError = err("name_regex") != null,
            supportingText = { Text(err("name_regex") ?: "Every snapshot whose name matches is replicated") }, modifier = Modifier.fillMaxWidth())
    } else {
        if (push) {
            Text("Periodic snapshot tasks", style = MaterialTheme.typography.bodyMedium)
            val candidates = refs.snapshotTasks.filter { t -> form.sourceDatasets.isEmpty() || form.sourceDatasets.any { it == t.dataset || it.startsWith(t.dataset + "/") || t.dataset.startsWith("$it/") } || t.id in form.snapshotTaskIds }
            if (candidates.isEmpty()) Text("No periodic snapshot task covers the source. Add one in Storage › Protection, or enter a naming schema below.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            candidates.forEach { t ->
                Row(Modifier.fillMaxWidth().clickable { onChange { f -> f.copy(snapshotTaskIds = if (t.id in f.snapshotTaskIds) f.snapshotTaskIds - t.id else f.snapshotTaskIds + t.id) } },
                    verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = t.id in form.snapshotTaskIds, onCheckedChange = { on -> onChange { f -> f.copy(snapshotTaskIds = if (on) f.snapshotTaskIds + t.id else f.snapshotTaskIds - t.id) } })
                    Column(Modifier.weight(1f)) {
                        Text(t.label, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(t.namingSchema + if (!t.enabled) " · turned off" else "", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            err("periodic_snapshot_tasks")?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
        OutlinedTextField(
            form.namingSchemas, { v -> onChange { it.copy(namingSchemas = v) } },
            label = { Text(if (push) "Also include snapshots named" else "Naming schema *") }, minLines = 1, maxLines = 4,
            placeholder = { Text("auto-%Y-%m-%d_%H-%M") }, textStyle = mono, isError = err("naming_schema") != null,
            supportingText = { Text(err("naming_schema") ?: "One schema per line with %Y %m %d %H %M" + (refs.namingSchemas.firstOrNull()?.let { ", e.g. $it" } ?: "")) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
    Text("Keep snapshots on the target", style = MaterialTheme.typography.bodyMedium)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Retention.entries.forEach { r -> FilterChip(selected = form.retention == r, onClick = { onChange { it.copy(retention = r) } }, label = { Text(r.label) }) }
    }
    Text(err("retention_policy") ?: form.retention.help, style = MaterialTheme.typography.bodySmall,
        color = if (err("retention_policy") != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
    if (form.retention == Retention.CUSTOM) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(form.lifetimeValue, { v -> onChange { it.copy(lifetimeValue = v.filter(Char::isDigit).take(5)) } }, label = { Text("Keep for") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), isError = err("lifetime_value") != null,
            supportingText = err("lifetime_value")?.let { { Text(it) } }, modifier = Modifier.weight(1f))
        PickField("Unit", form.lifetimeUnit, ReplicationLogic.LIFETIME_UNITS, { it.lowercase() + "s" }, onPick = { u -> onChange { it.copy(lifetimeUnit = u) } }, modifier = Modifier.weight(1.2f))
    }
    if (push) SwitchRow("Hold pending snapshots", form.holdPendingSnapshots, "Don't let the source's retention delete snapshots that haven't been sent yet") { v -> onChange { it.copy(holdPendingSnapshots = v) } }
}

@Composable
private fun TransferFields(form: ReplicationForm, err: (String) -> String?, onChange: ((ReplicationForm) -> ReplicationForm) -> Unit) {
    if (form.transport == ReplTransport.SSH) {
        PickField("Stream compression", form.compression, ReplicationLogic.COMPRESSION, { if (it.isEmpty()) "Off" else it.lowercase() },
            onPick = { c -> onChange { it.copy(compression = c) } }, isError = err("compression") != null, supporting = err("compression") ?: "Helps on slow links; costs CPU on both sides")
        OutlinedTextField(form.speedLimit, { v -> onChange { it.copy(speedLimit = v.filter(Char::isDigit).take(9)) } }, label = { Text("Speed limit (KiB/s)") }, singleLine = true,
            placeholder = { Text("Unlimited") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), isError = err("speed_limit") != null,
            supportingText = err("speed_limit")?.let { { Text(it) } }, modifier = Modifier.fillMaxWidth())
    } else Text("Compression and speed limits are only available with SSH.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    OutlinedTextField(form.retries, { v -> onChange { it.copy(retries = v.filter(Char::isDigit).take(3)) } }, label = { Text("Retries") }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), isError = err("retries") != null,
        supportingText = { Text(err("retries") ?: "Attempts before the run counts as failed") }, modifier = Modifier.fillMaxWidth())
    SwitchRow("Large blocks", form.largeBlock, "Recommended; must match earlier runs") { v -> onChange { it.copy(largeBlock = v) } }
    SwitchRow("Compressed stream", form.compressed, "Send blocks as they are compressed on disk") { v -> onChange { it.copy(compressed = v) } }
    SwitchRow("Include dataset properties", form.properties, err("properties") ?: "Copy settings such as compression and quotas") { v -> onChange { it.copy(properties = v) } }
    if (form.properties) {
        OutlinedTextField(form.propertiesExclude, { v -> onChange { it.copy(propertiesExclude = v) } }, label = { Text("Don't copy these properties") }, minLines = 1, maxLines = 4,
            placeholder = { Text("mountpoint") }, supportingText = { Text("One property per line") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(form.propertiesOverride, { v -> onChange { it.copy(propertiesOverride = v) } }, label = { Text("Override on the target") }, minLines = 1, maxLines = 4,
            placeholder = { Text("compression=zstd") }, isError = err("properties_override") != null,
            supportingText = { Text(err("properties_override") ?: "property=value, one per line") }, modifier = Modifier.fillMaxWidth())
    }
}
