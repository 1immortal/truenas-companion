package app.truenascompanion.ui.tasks

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.truenascompanion.AppContainer
import app.truenascompanion.data.api.TasksApi
import app.truenascompanion.data.api.fieldErrors
import app.truenascompanion.data.api.userMessage
import app.truenascompanion.data.model.CronJobInput
import app.truenascompanion.data.model.Health
import app.truenascompanion.data.model.InitScriptInput
import app.truenascompanion.data.model.InitScriptType
import app.truenascompanion.data.model.InitScriptWhen
import app.truenascompanion.data.tasks.CronText
import app.truenascompanion.ui.appViewModel
import app.truenascompanion.ui.components.ConfirmDialog
import app.truenascompanion.ui.components.ElevatedSection
import app.truenascompanion.ui.components.InfoBanner
import app.truenascompanion.ui.components.PickField
import app.truenascompanion.ui.components.ScrollableErrorState
import app.truenascompanion.ui.components.SectionTitle
import app.truenascompanion.ui.components.SkeletonList
import app.truenascompanion.ui.components.UiState
import app.truenascompanion.ui.components.isLoginRequired
import app.truenascompanion.ui.protection.SwitchRow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/**
 * Splits middleware validation errors into the editor's own fields ([aliases] maps e.g. `minute` → `schedule`)
 * and a general message for the rest.
 */
internal fun splitFieldErrors(e: Throwable, known: Set<String>, aliases: Map<String, String> = emptyMap()): Pair<Map<String, String>, String?> {
    val byField = e.fieldErrors()
    if (byField.isEmpty()) return emptyMap<String, String>() to e.userMessage()
    val mine = mutableMapOf<String, String>()
    val other = mutableListOf<String>()
    byField.forEach { (k, v) ->
        val f = aliases[k] ?: k
        if (f in known) mine[f] = listOfNotNull(mine[f], v).joinToString("\n") else other += "$k: $v"
    }
    return mine to other.takeIf { it.isNotEmpty() }?.joinToString("\n")
}

class CronJobEditorViewModel(private val c: AppContainer, private val id: Int?) : ViewModel() {
    private val _state = MutableStateFlow<UiState<CronJobInput>>(UiState.Loading)
    val state = _state.asStateFlow()
    private val _form = MutableStateFlow(NEW)
    val form = _form.asStateFlow()
    private val _users = MutableStateFlow(listOf("root"))
    val users = _users.asStateFlow()
    private val _serverErrors = MutableStateFlow<Map<String, String>>(emptyMap())
    val serverErrors = _serverErrors.asStateFlow()
    private val _generalError = MutableStateFlow<String?>(null)
    val generalError = _generalError.asStateFlow()
    private val _saving = MutableStateFlow(false)
    val saving = _saving.asStateFlow()
    private val _saved = Channel<String>(Channel.BUFFERED)
    val saved = _saved.receiveAsFlow()
    var smartTest = false; private set

    init { load() }

    fun load() = viewModelScope.launch {
        _state.value = UiState.Loading
        try {
            val (users, job) = c.repository.call { api ->
                val t = TasksApi(api)
                (runCatching { t.usernames() }.getOrNull() ?: emptyList()) to id?.let { want -> t.cronJobs().firstOrNull { it.id == want } }
            }
            if (users.isNotEmpty()) _users.value = users
            val input = if (id == null) NEW else {
                job ?: throw IllegalStateException("This cron job no longer exists")
                smartTest = job.isSmartTest
                CronJobInput(job.description, job.command, job.user, job.schedule, job.enabled, job.hideStdout, job.hideStderr)
            }
            if (input.user !in _users.value) _users.value = listOf(input.user) + _users.value
            _form.value = input
            _state.value = UiState.Success(input)
        } catch (e: Throwable) {
            _state.value = UiState.Error(e.userMessage(), e)
        }
    }

    fun edit(f: (CronJobInput) -> CronJobInput) {
        val before = _form.value
        val after = f(before)
        _form.value = after
        val touched = buildSet {
            if (before.command != after.command) add("command"); if (before.user != after.user) add("user")
            if (before.schedule != after.schedule) add("schedule"); if (before.description != after.description) add("description")
        }
        if (touched.isNotEmpty()) _serverErrors.value = _serverErrors.value - touched
        _generalError.value = null
    }

    fun save() {
        val input = _form.value
        if (TasksApi.cronErrors(input).isNotEmpty() || _saving.value) return
        _saving.value = true
        viewModelScope.launch {
            try {
                c.repository.call { api -> TasksApi(api).let { if (id == null) it.createCronJob(input) else it.updateCronJob(id, input) } }
                _saved.trySend(if (id == null) "Cron job created" else "Cron job saved")
            } catch (e: Throwable) {
                val (mine, general) = splitFieldErrors(e, FIELDS, SCHEDULE_ALIASES)
                _serverErrors.value = mine
                _generalError.value = general
            } finally {
                _saving.value = false
            }
        }
    }

    companion object {
        val NEW = CronJobInput(description = "", command = "", user = "root", schedule = CronText.DEFAULT.copy(hour = "3"))
        val FIELDS = setOf("description", "command", "user", "schedule")
        val SCHEDULE_ALIASES = mapOf("minute" to "schedule", "hour" to "schedule", "dom" to "schedule", "month" to "schedule", "dow" to "schedule")
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CronJobEditorScreen(id: Int?, onBack: () -> Unit) {
    val vm = appViewModel(key = "cron-editor:${id ?: "new"}") { CronJobEditorViewModel(it, id) }
    val state by vm.state.collectAsStateWithLifecycle()
    val form by vm.form.collectAsStateWithLifecycle()
    val users by vm.users.collectAsStateWithLifecycle()
    val serverErrors by vm.serverErrors.collectAsStateWithLifecycle()
    val generalError by vm.generalError.collectAsStateWithLifecycle()
    val saving by vm.saving.collectAsStateWithLifecycle()
    LaunchedEffect(vm) { vm.saved.collect { onBack() } }
    val original = (state as? UiState.Success)?.data
    val dirty = original != null && original != form
    val errors = TasksApi.cronErrors(form) + serverErrors
    var confirmDiscard by remember { mutableStateOf(false) }
    BackHandler(enabled = dirty) { confirmDiscard = true }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (id == null) "New cron job" else "Edit cron job") },
                navigationIcon = { IconButton(onClick = { if (dirty) confirmDiscard = true else onBack() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
                actions = {
                    if (saving) CircularProgressIndicator(Modifier.size(22.dp).padding(end = 4.dp), strokeWidth = 2.dp)
                    TextButton(onClick = vm::save, enabled = (dirty || id == null) && !saving && TasksApi.cronErrors(form).isEmpty()) { Text("Save") }
                },
            )
        },
        snackbarHost = { SnackbarHost(remember { SnackbarHostState() }) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            when (val s = state) {
                UiState.Loading -> SkeletonList(3, 160.dp)
                is UiState.Error -> ScrollableErrorState(s.message, s.isLoginRequired) { vm.load() }
                is UiState.Success -> CronJobEditorContent(form, users, errors, generalError, vm.smartTest, showErrors = dirty || serverErrors.isNotEmpty(), onChange = vm::edit)
            }
        }
    }
    if (confirmDiscard) ConfirmDialog(
        title = "Discard changes?", text = "Your changes to this cron job haven't been saved.",
        confirmLabel = "Discard", destructive = true, requireAuth = false,
        onConfirm = { confirmDiscard = false; onBack() }, onDismiss = { confirmDiscard = false },
    )
}

/** Stateless cron job form (also used by the screenshot previews). */
@Composable
fun CronJobEditorContent(
    form: CronJobInput,
    users: List<String>,
    errors: Map<String, String>,
    generalError: String?,
    smartTest: Boolean = false,
    showErrors: Boolean = true,
    startCustom: Boolean = false,
    onChange: ((CronJobInput) -> CronJobInput) -> Unit,
) {
    fun err(k: String) = errors[k]?.takeIf { showErrors || k == "description" }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        generalError?.let { InfoBanner(it, health = Health.CRITICAL) }
        if (smartTest) InfoBanner("This job runs a S.M.A.R.T. test. It's easier to change it in Storage › Protection, which understands the test settings.", health = Health.HEALTHY)
        SectionTitle("Task")
        ElevatedSection(contentPadding = 14.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    form.description, { v -> onChange { it.copy(description = v) } }, label = { Text("Description") },
                    placeholder = { Text("e.g. Clean old downloads") }, singleLine = true, isError = err("description") != null,
                    supportingText = err("description")?.let { { Text(it) } }, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    form.command, { v -> onChange { it.copy(command = v) } }, label = { Text("Command") },
                    placeholder = { Text("e.g. find /mnt/tank/downloads -mtime +30 -delete") }, minLines = 2, maxLines = 6,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    isError = err("command") != null, supportingText = { Text(err("command") ?: "Runs in a shell on the NAS") },
                    modifier = Modifier.fillMaxWidth(),
                )
                PickField(
                    "Run as user", form.user, users, { it }, onPick = { u -> onChange { it.copy(user = u) } },
                    isError = err("user") != null, supporting = err("user") ?: "The command runs with this user's permissions",
                )
            }
        }
        SectionTitle("Schedule")
        ElevatedSection(contentPadding = 14.dp) {
            CronSchedulePicker(form.schedule, { s -> onChange { it.copy(schedule = s) } }, error = errors["schedule"]?.takeIf { it != "Fix the schedule" }, startCustom = startCustom)
        }
        SectionTitle("Options")
        ElevatedSection(contentPadding = 14.dp) {
            SwitchRow("Enabled", form.enabled, "Turned off jobs don't run on schedule (you can still run them now)") { v -> onChange { it.copy(enabled = v) } }
            SwitchRow("Hide standard output", form.hideStdout, "Otherwise TrueNAS emails the output to the user") { v -> onChange { it.copy(hideStdout = v) } }
            SwitchRow("Hide error output", form.hideStderr, "Otherwise TrueNAS emails any errors to the user") { v -> onChange { it.copy(hideStderr = v) } }
        }
    }
}

class InitScriptEditorViewModel(private val c: AppContainer, private val id: Int?) : ViewModel() {
    private val _state = MutableStateFlow<UiState<InitForm>>(UiState.Loading)
    val state = _state.asStateFlow()
    private val _form = MutableStateFlow(InitForm())
    val form = _form.asStateFlow()
    private val _serverErrors = MutableStateFlow<Map<String, String>>(emptyMap())
    val serverErrors = _serverErrors.asStateFlow()
    private val _generalError = MutableStateFlow<String?>(null)
    val generalError = _generalError.asStateFlow()
    private val _saving = MutableStateFlow(false)
    val saving = _saving.asStateFlow()
    private val _saved = Channel<String>(Channel.BUFFERED)
    val saved = _saved.receiveAsFlow()

    init { load() }

    fun load() = viewModelScope.launch {
        if (id == null) { _state.value = UiState.Success(_form.value); return@launch }
        _state.value = UiState.Loading
        try {
            val s = c.repository.call { api -> TasksApi(api).initScripts().firstOrNull { it.id == id } }
                ?: throw IllegalStateException("This script no longer exists")
            val f = InitForm(s.type, s.command, s.script, s.whenRun, s.enabled, s.timeout.toString(), s.comment)
            _form.value = f
            _state.value = UiState.Success(f)
        } catch (e: Throwable) {
            _state.value = UiState.Error(e.userMessage(), e)
        }
    }

    fun edit(f: (InitForm) -> InitForm) {
        val before = _form.value
        val after = f(before)
        _form.value = after
        val touched = buildSet {
            if (before.command != after.command || before.type != after.type) add("command")
            if (before.script != after.script || before.type != after.type) add("script")
            if (before.timeout != after.timeout) add("timeout"); if (before.comment != after.comment) add("comment")
        }
        if (touched.isNotEmpty()) _serverErrors.value = _serverErrors.value - touched
        _generalError.value = null
    }

    fun save() {
        val f = _form.value
        if (f.errors().isNotEmpty() || _saving.value) return
        _saving.value = true
        viewModelScope.launch {
            try {
                c.repository.call { api -> TasksApi(api).let { if (id == null) it.createInitScript(f.input()) else it.updateInitScript(id, f.input()) } }
                _saved.trySend(if (id == null) "Script added" else "Script saved")
            } catch (e: Throwable) {
                val (mine, general) = splitFieldErrors(e, setOf("command", "script", "timeout", "comment", "when", "type"))
                _serverErrors.value = mine
                _generalError.value = general
            } finally {
                _saving.value = false
            }
        }
    }
}

/** Editable init/shutdown script; the timeout stays text while typing. */
data class InitForm(
    val type: InitScriptType = InitScriptType.COMMAND,
    val command: String = "",
    val script: String = "",
    val whenRun: InitScriptWhen = InitScriptWhen.POSTINIT,
    val enabled: Boolean = true,
    val timeout: String = "10",
    val comment: String = "",
) {
    fun input() = InitScriptInput(type, command, script, whenRun, enabled, timeout.trim().toIntOrNull() ?: -1, comment)
    fun errors(): Map<String, String> = TasksApi.initErrors(input()) +
        (if (timeout.trim().toIntOrNull() == null) mapOf("timeout" to "Enter the number of seconds") else emptyMap())
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InitScriptEditorScreen(id: Int?, pickedScript: String?, onPickConsumed: () -> Unit, onBrowse: (String?) -> Unit, onBack: () -> Unit) {
    val vm = appViewModel(key = "init-editor:${id ?: "new"}") { InitScriptEditorViewModel(it, id) }
    val state by vm.state.collectAsStateWithLifecycle()
    val form by vm.form.collectAsStateWithLifecycle()
    val serverErrors by vm.serverErrors.collectAsStateWithLifecycle()
    val generalError by vm.generalError.collectAsStateWithLifecycle()
    val saving by vm.saving.collectAsStateWithLifecycle()
    LaunchedEffect(vm) { vm.saved.collect { onBack() } }
    LaunchedEffect(pickedScript) {
        if (pickedScript != null) { vm.edit { it.copy(type = InitScriptType.SCRIPT, script = pickedScript) }; onPickConsumed() }
    }
    val original = (state as? UiState.Success)?.data
    val dirty = original != null && original != form
    var confirmDiscard by remember { mutableStateOf(false) }
    BackHandler(enabled = dirty) { confirmDiscard = true }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (id == null) "New init/shutdown script" else "Edit script") },
                navigationIcon = { IconButton(onClick = { if (dirty) confirmDiscard = true else onBack() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
                actions = {
                    if (saving) CircularProgressIndicator(Modifier.size(22.dp).padding(end = 4.dp), strokeWidth = 2.dp)
                    TextButton(onClick = vm::save, enabled = (dirty || id == null) && !saving && form.errors().isEmpty()) { Text("Save") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            when (val s = state) {
                UiState.Loading -> SkeletonList(3, 140.dp)
                is UiState.Error -> ScrollableErrorState(s.message, s.isLoginRequired) { vm.load() }
                is UiState.Success -> InitScriptEditorContent(
                    form, form.errors() + serverErrors, generalError, showErrors = dirty || serverErrors.isNotEmpty(),
                    onBrowse = { onBrowse(form.script.substringBeforeLast('/', "").ifBlank { null }) }, onChange = vm::edit,
                )
            }
        }
    }
    if (confirmDiscard) ConfirmDialog(
        title = "Discard changes?", text = "Your changes to this script haven't been saved.",
        confirmLabel = "Discard", destructive = true, requireAuth = false,
        onConfirm = { confirmDiscard = false; onBack() }, onDismiss = { confirmDiscard = false },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun InitScriptEditorContent(
    form: InitForm,
    errors: Map<String, String>,
    generalError: String?,
    showErrors: Boolean = true,
    onBrowse: () -> Unit,
    onChange: ((InitForm) -> InitForm) -> Unit,
) {
    fun err(k: String) = errors[k]?.takeIf { showErrors || k == "comment" }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        generalError?.let { InfoBanner(it, health = Health.CRITICAL) }
        SectionTitle("What to run")
        ElevatedSection(contentPadding = 14.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    InitScriptType.entries.forEach { t ->
                        FilterChip(selected = form.type == t, onClick = { onChange { it.copy(type = t) } }, label = { Text(t.label) })
                    }
                }
                if (form.type == InitScriptType.COMMAND) {
                    OutlinedTextField(
                        form.command, { v -> onChange { it.copy(command = v) } }, label = { Text("Command") },
                        placeholder = { Text("e.g. echo started >> /mnt/tank/logs/boot.log") }, minLines = 2, maxLines = 6,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                        isError = err("command") != null, supportingText = err("command")?.let { { Text(it) } }, modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    OutlinedTextField(
                        form.script, { v -> onChange { it.copy(script = v) } }, label = { Text("Script file") },
                        placeholder = { Text("/mnt/tank/scripts/start.sh") }, singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                        isError = err("script") != null,
                        supportingText = { Text(err("script") ?: "Must be an executable file on the NAS") },
                        trailingIcon = { IconButton(onClick = onBrowse) { Icon(Icons.Rounded.FolderOpen, "Browse") } },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
        SectionTitle("When")
        ElevatedSection(contentPadding = 14.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    InitScriptWhen.entries.forEach { w ->
                        FilterChip(selected = form.whenRun == w, onClick = { onChange { it.copy(whenRun = w) } }, label = { Text(w.label) },
                            leadingIcon = { Icon(whenIcon(w), null, Modifier.size(18.dp)) })
                    }
                }
                Text(form.whenRun.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(
                    form.timeout, { v -> onChange { it.copy(timeout = v.filter(Char::isDigit).take(6)) } }, label = { Text("Timeout (seconds)") },
                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    isError = err("timeout") != null, supportingText = { Text(err("timeout") ?: "TrueNAS stops waiting for it after this long") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        SectionTitle("Details")
        ElevatedSection(contentPadding = 14.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    form.comment, { v -> onChange { it.copy(comment = v) } }, label = { Text("Comment") }, singleLine = true,
                    isError = err("comment") != null, supportingText = err("comment")?.let { { Text(it) } }, modifier = Modifier.fillMaxWidth(),
                )
                SwitchRow("Enabled", form.enabled, "Turned off scripts are skipped") { v -> onChange { it.copy(enabled = v) } }
            }
        }
    }
}
