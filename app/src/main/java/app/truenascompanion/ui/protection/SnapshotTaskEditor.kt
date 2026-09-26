package app.truenascompanion.ui.protection

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.truenascompanion.AppContainer
import app.truenascompanion.data.api.ProtectionApi
import app.truenascompanion.data.api.SnapshotTaskInput
import app.truenascompanion.data.api.userMessage
import app.truenascompanion.data.model.CronSchedule
import app.truenascompanion.data.model.Dataset
import app.truenascompanion.data.protection.Schedules
import app.truenascompanion.ui.appViewModel
import app.truenascompanion.ui.components.ElevatedSection
import app.truenascompanion.ui.components.ScrollableErrorState
import app.truenascompanion.ui.components.SkeletonList
import app.truenascompanion.ui.components.UiState
import app.truenascompanion.ui.components.isLoginRequired
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Editable form state of a periodic snapshot task. */
data class SnapshotTaskForm(
    val dataset: String = "",
    val recursive: Boolean = false,
    val exclude: String = "",
    val schedule: CronSchedule = CronSchedule(minute = "0", hour = "*", dom = "*", month = "*", dow = "*", begin = "00:00", end = "23:59"),
    val lifetimeValue: String = "2",
    val lifetimeUnit: String = "WEEK",
    val namingSchema: String = "auto-%Y-%m-%d_%H-%M",
    val allowEmpty: Boolean = true,
    val enabled: Boolean = true,
) {
    val lifetime: Int? get() = lifetimeValue.toIntOrNull()?.takeIf { it in 1..9999 }
    /** The naming schema must contain %Y %m %d %H %M (middleware validation). */
    val schemaOk: Boolean get() = listOf("%Y", "%m", "%d", "%H", "%M").all { it in namingSchema } && !namingSchema.contains('/') && namingSchema.isNotBlank()
    val excludeList: List<String> get() = exclude.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }
    val valid: Boolean get() = dataset.isNotBlank() && lifetime != null && schemaOk
    fun toInput() = SnapshotTaskInput(dataset, recursive, if (recursive) excludeList else emptyList(), lifetime ?: 2, lifetimeUnit, namingSchema.trim(), schedule, enabled, allowEmpty)
}

data class TaskEditorData(val datasets: List<Dataset>, val initial: SnapshotTaskForm)

class SnapshotTaskEditorViewModel(private val c: AppContainer, private val id: Int?) : ViewModel() {
    private val _state = MutableStateFlow<UiState<TaskEditorData>>(UiState.Loading)
    val state: StateFlow<UiState<TaskEditorData>> = _state.asStateFlow()
    private val _form = MutableStateFlow(SnapshotTaskForm())
    val form: StateFlow<SnapshotTaskForm> = _form.asStateFlow()
    private val _saving = MutableStateFlow(false)
    val saving: StateFlow<Boolean> = _saving.asStateFlow()
    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()
    private val _done = Channel<Unit>(Channel.CONFLATED)
    val done = _done.receiveAsFlow()

    init { load() }

    fun load() = viewModelScope.launch {
        try {
            coroutineScope {
                val ds = async { c.repository.call { it.datasets() }.filter { it.type.equals("FILESYSTEM", true) || it.type.equals("VOLUME", true) } }
                val task = if (id != null) c.repository.call { ProtectionApi(it).snapshotTasks() }.firstOrNull { it.id == id }
                    ?: throw IllegalStateException("This snapshot task no longer exists.") else null
                val initial = task?.let {
                    SnapshotTaskForm(it.dataset, it.recursive, it.exclude.joinToString(", "), it.schedule, it.lifetimeValue.toString(), it.lifetimeUnit.uppercase(), it.namingSchema, it.allowEmpty, it.enabled)
                } ?: SnapshotTaskForm()
                val datasets = ds.await()
                val form = if (initial.dataset.isEmpty()) initial.copy(dataset = datasets.firstOrNull { !it.isSystem }?.id.orEmpty()) else initial
                _form.value = form
                _state.value = UiState.Success(TaskEditorData(datasets, form))
            }
        } catch (e: Throwable) {
            _state.value = UiState.Error(e.message?.takeIf { e is IllegalStateException } ?: e.userMessage(), e)
        }
    }

    fun edit(f: (SnapshotTaskForm) -> SnapshotTaskForm) = _form.update(f)

    fun save() {
        val f = _form.value
        if (!f.valid || _saving.value) return
        _saving.value = true
        viewModelScope.launch {
            try {
                c.repository.call { api -> ProtectionApi(api).let { if (id == null) it.createSnapshotTask(f.toInput()) else it.updateSnapshotTask(id, f.toInput()) } }
                _done.send(Unit)
            } catch (e: Throwable) {
                _messages.send(e.userMessage())
            } finally {
                _saving.value = false
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SnapshotTaskEditorScreen(id: Int?, onBack: () -> Unit) {
    val vm = appViewModel(key = "snaptask:${id ?: "new"}") { SnapshotTaskEditorViewModel(it, id) }
    val state by vm.state.collectAsStateWithLifecycle()
    val form by vm.form.collectAsStateWithLifecycle()
    val saving by vm.saving.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(vm) { vm.messages.collect { snackbar.showSnackbar(it) } }
    LaunchedEffect(vm) { vm.done.collect { onBack() } }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (id == null) "New snapshot task" else "Edit snapshot task") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
                actions = { TextButton(onClick = vm::save, enabled = form.valid && !saving && state is UiState.Success) { Text("Save") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            when (val s = state) {
                UiState.Loading -> SkeletonList(4, 90.dp)
                is UiState.Error -> ScrollableErrorState(s.message, s.isLoginRequired) { vm.load() }
                is UiState.Success -> SnapshotTaskForm(s.data.datasets, form, vm::edit)
            }
        }
    }
}

private val units = listOf("HOUR", "DAY", "WEEK", "MONTH", "YEAR")
private val windowHours = (0..23).map { "%02d:00".format(it) } + "23:59"

@Composable
fun SnapshotTaskForm(datasets: List<Dataset>, form: SnapshotTaskForm, edit: ((SnapshotTaskForm) -> SnapshotTaskForm) -> Unit) {
    val options = datasets.filterNot { it.isSystem }.map { it.id }.let { if (form.dataset.isNotEmpty() && form.dataset !in it) listOf(form.dataset) + it else it }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ElevatedSection {
            Text("What", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(10.dp))
            Choice("Dataset", form.dataset.ifEmpty { null }, options, Modifier.fillMaxWidth()) { v -> edit { it.copy(dataset = v) } }
            Spacer(Modifier.height(6.dp))
            SwitchRow("Include child datasets", form.recursive, "Snapshot every dataset below it too") { v -> edit { it.copy(recursive = v) } }
            if (form.recursive) {
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = form.exclude, onValueChange = { v -> edit { it.copy(exclude = v) } }, label = { Text("Exclude (optional)") },
                    supportingText = { Text("Child datasets to skip, comma separated, e.g. ${form.dataset.ifEmpty { "tank" }}/scratch") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        ElevatedSection {
            Text("When", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(10.dp))
            ScheduleEditor(form.schedule, { v -> edit { it.copy(schedule = v) } })
            if (Schedules.presetOf(form.schedule) == app.truenascompanion.data.protection.SchedulePreset.HOURLY || form.schedule.hour.contains('*') || form.schedule.hour.contains('/')) {
                Text("Only between", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Choice("From", form.schedule.begin ?: "00:00", windowHours.dropLast(1), Modifier.weight(1f)) { v -> edit { it.copy(schedule = it.schedule.copy(begin = v)) } }
                    Choice("Until", form.schedule.end ?: "23:59", windowHours.drop(1), Modifier.weight(1f)) { v -> edit { it.copy(schedule = it.schedule.copy(end = v)) } }
                }
            }
        }
        ElevatedSection {
            Text("Keep for", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = form.lifetimeValue, onValueChange = { v -> edit { it.copy(lifetimeValue = v.filter(Char::isDigit).take(4)) } },
                    label = { Text("Amount") }, singleLine = true, isError = form.lifetime == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f),
                )
                Choice("Unit", form.lifetimeUnit, units, Modifier.weight(1f), label = { u -> u.lowercase().replaceFirstChar { it.uppercase() } + "s" }) { v -> edit { it.copy(lifetimeUnit = v) } }
            }
            Spacer(Modifier.height(4.dp))
            Text("Older snapshots from this task are deleted automatically.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        ElevatedSection {
            Text("Options", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = form.namingSchema, onValueChange = { v -> edit { it.copy(namingSchema = v.trim()) } }, singleLine = true,
                label = { Text("Naming schema") }, isError = !form.schemaOk,
                supportingText = { Text(if (form.schemaOk) "strftime pattern; keep it unique per task" else "Must include %Y %m %d %H and %M") },
                modifier = Modifier.fillMaxWidth(),
            )
            SwitchRow("Take empty snapshots", form.allowEmpty, "Also snapshot when nothing changed") { v -> edit { it.copy(allowEmpty = v) } }
            SwitchRow("Enabled", form.enabled) { v -> edit { it.copy(enabled = v) } }
        }
        Spacer(Modifier.height(24.dp))
    }
}
