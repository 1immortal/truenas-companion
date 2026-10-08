package app.truenascompanion.ui.services

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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.truenascompanion.AppContainer
import app.truenascompanion.data.api.ServicesApi
import app.truenascompanion.data.api.fieldErrors
import app.truenascompanion.data.api.userMessage
import app.truenascompanion.data.model.Health
import app.truenascompanion.data.model.ServiceInfo
import app.truenascompanion.data.services.Draft
import app.truenascompanion.data.services.FieldKind
import app.truenascompanion.data.services.Option
import app.truenascompanion.data.services.ServiceField
import app.truenascompanion.data.services.ServiceForms
import app.truenascompanion.data.services.ServiceForms.list
import app.truenascompanion.data.services.ServiceForms.on
import app.truenascompanion.data.services.ServiceForms.text
import app.truenascompanion.data.services.ServiceKind
import app.truenascompanion.ui.appViewModel
import app.truenascompanion.ui.components.ConfirmDialog
import app.truenascompanion.ui.components.ElevatedSection
import app.truenascompanion.ui.components.IconBadge
import app.truenascompanion.ui.components.InfoBanner
import app.truenascompanion.ui.components.PickField
import app.truenascompanion.ui.components.ScrollableErrorState
import app.truenascompanion.ui.components.SecretTextField
import app.truenascompanion.ui.components.SkeletonList
import app.truenascompanion.ui.components.StatusChip
import app.truenascompanion.ui.components.UiState
import app.truenascompanion.ui.components.isLoginRequired
import app.truenascompanion.ui.protection.SwitchRow
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** What the settings editor loaded: the saved config, picker options from the NAS and the service's state. */
data class ServiceSettingsData(val original: JsonObject, val choices: Map<String, List<Option>>, val service: ServiceInfo?)

class ServiceSettingsViewModel(private val c: AppContainer, val kind: ServiceKind) : ViewModel() {
    val fields = ServiceForms.fields(kind)
    private val _state = MutableStateFlow<UiState<ServiceSettingsData>>(UiState.Loading)
    val state = _state.asStateFlow()
    private val _draft = MutableStateFlow<Draft>(emptyMap())
    val draft = _draft.asStateFlow()
    /** Validation messages from TrueNAS after a failed save (field -> message). */
    private val _serverErrors = MutableStateFlow<Map<String, String>>(emptyMap())
    val serverErrors = _serverErrors.asStateFlow()
    /** A save error that doesn't belong to a shown field. */
    private val _generalError = MutableStateFlow<String?>(null)
    val generalError = _generalError.asStateFlow()
    private val _saving = MutableStateFlow(false)
    val saving = _saving.asStateFlow()
    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    init { load() }

    fun load() = viewModelScope.launch {
        _state.value = UiState.Loading
        try {
            val data = c.repository.call { api ->
                coroutineScope {
                    val a = ServicesApi(api)
                    val choices = async { a.choices(kind) }
                    val svc = async { runCatching { a.services().firstOrNull { it.service == kind.service } }.getOrNull() }
                    ServiceSettingsData(a.config(kind), choices.await(), svc.await())
                }
            }
            _draft.value = ServiceForms.toDraft(fields, data.original)
            _serverErrors.value = emptyMap(); _generalError.value = null
            _state.value = UiState.Success(data)
        } catch (e: Throwable) {
            _state.value = UiState.Error(e.userMessage(), e)
        }
    }

    fun edit(key: String, value: JsonElement) {
        _draft.update { it + (key to value) }
        if (key in _serverErrors.value) _serverErrors.update { it - key }
    }

    fun save() {
        val data = (_state.value as? UiState.Success)?.data ?: return
        if (_saving.value) return
        val d = _draft.value
        if (ServiceForms.validate(fields, d).isNotEmpty()) { _messages.trySend("Fix the highlighted fields first"); return }
        val changes = ServiceForms.changes(fields, data.original, d)
        if (changes.isEmpty()) { _messages.trySend("Nothing changed"); return }
        _saving.value = true
        _generalError.value = null
        viewModelScope.launch {
            try {
                val saved = c.repository.call { ServicesApi(it).update(kind, changes) }
                _state.value = UiState.Success(data.copy(original = saved))
                _draft.value = ServiceForms.toDraft(fields, saved)
                _serverErrors.value = emptyMap()
                _messages.trySend("${kind.title} settings saved")
            } catch (e: Throwable) {
                val errs = e.fieldErrors()
                val shown = fields.filter { it.visible(d) }.map { it.key }.toSet()
                _serverErrors.value = errs.filterKeys { it in shown }
                val other = errs.filterKeys { it !in shown }
                _generalError.value = when {
                    errs.isEmpty() -> e.userMessage()
                    other.isNotEmpty() -> other.entries.joinToString("\n") { (k, v) -> "$k: $v" }
                    else -> null
                }
                _messages.trySend(if (errs.isNotEmpty()) "TrueNAS didn't accept some settings" else "Couldn't save: ${e.userMessage()}")
            } finally {
                _saving.value = false
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServiceSettingsScreen(kind: ServiceKind, onBack: () -> Unit) {
    val vm = appViewModel(key = "service-settings:${kind.name}") { ServiceSettingsViewModel(it, kind) }
    val state by vm.state.collectAsStateWithLifecycle()
    val draft by vm.draft.collectAsStateWithLifecycle()
    val serverErrors by vm.serverErrors.collectAsStateWithLifecycle()
    val generalError by vm.generalError.collectAsStateWithLifecycle()
    val saving by vm.saving.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(vm) { vm.messages.collect { snackbar.showSnackbar(it) } }
    val data = (state as? UiState.Success)?.data
    val dirty = data != null && ServiceForms.dirty(vm.fields, data.original, draft)
    var confirmDiscard by remember { mutableStateOf(false) }
    val back = { if (dirty) confirmDiscard = true else onBack() }
    BackHandler(enabled = dirty) { confirmDiscard = true }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("${kind.title} settings") },
                navigationIcon = { IconButton(onClick = back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
                actions = {
                    if (saving) CircularProgressIndicator(Modifier.size(22.dp).padding(end = 4.dp), strokeWidth = 2.dp)
                    TextButton(onClick = vm::save, enabled = dirty && !saving) { Text("Save") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            when (val s = state) {
                UiState.Loading -> SkeletonList(4, 120.dp)
                is UiState.Error -> ScrollableErrorState(s.message, s.isLoginRequired) { vm.load() }
                is UiState.Success -> ServiceSettingsContent(
                    kind, vm.fields, draft, s.data,
                    errors = ServiceForms.validate(vm.fields, draft) + serverErrors,
                    generalError = generalError,
                    onChange = vm::edit,
                )
            }
        }
    }
    if (confirmDiscard) ConfirmDialog(
        title = "Discard changes?", text = "Your changes to the ${kind.title} settings haven't been saved.",
        confirmLabel = "Discard", destructive = true, requireAuth = false,
        onConfirm = { confirmDiscard = false; onBack() }, onDismiss = { confirmDiscard = false },
    )
}

/** Stateless editor body (also used by the screenshot previews). */
@Composable
fun ServiceSettingsContent(
    kind: ServiceKind,
    fields: List<ServiceField>,
    draft: Draft,
    data: ServiceSettingsData,
    errors: Map<String, String>,
    generalError: String? = null,
    initiallyAdvanced: Boolean = false,
    onChange: (String, JsonElement) -> Unit,
) {
    var advanced by rememberSaveable { mutableStateOf(initiallyAdvanced || fields.any { it.advanced && it.key in errors && it.visible(draft) }) }
    val visible = fields.filter { it.visible(draft) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ElevatedSection(contentPadding = 14.dp) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(serviceIcon(kind.service))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(kind.title, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Saving applies the changes right away. TrueNAS reloads ${kind.title} if it is running.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                data.service?.let { s -> Spacer(Modifier.width(8.dp)); if (s.running) StatusChip(Health.HEALTHY, "Running") else StatusChip(Health.UNKNOWN, "Stopped", showIcon = false) }
            }
        }
        generalError?.let { InfoBanner(it, health = Health.CRITICAL) }
        visible.filter { !it.advanced }.groupBy { it.section }.forEach { (section, list) -> FieldSection(section, list, draft, data, errors, onChange) }
        val adv = visible.filter { it.advanced }
        if (adv.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().clickable { advanced = !advanced }.padding(vertical = 6.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Tune, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Text(if (advanced) "Hide advanced settings" else "Show advanced settings", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.primary)
                val n = adv.count { it.key in errors }
                if (n > 0 && !advanced) Text("$n to fix", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
                Icon(if (advanced) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null)
            }
            if (advanced) adv.groupBy { it.section }.forEach { (section, list) -> FieldSection(section, list, draft, data, errors, onChange) }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun FieldSection(title: String, fields: List<ServiceField>, draft: Draft, data: ServiceSettingsData, errors: Map<String, String>, onChange: (String, JsonElement) -> Unit) {
    ElevatedSection {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(8.dp))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            fields.forEach { f -> FieldEditor(f, draft, data.original, data.choices[f.choicesKey].orEmpty(), errors[f.key]) { onChange(f.key, it) } }
        }
    }
}

private const val OTHER = "\u0000other"

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FieldEditor(f: ServiceField, draft: Draft, original: JsonObject, loaded: List<Option>, error: String?, onChange: (JsonElement) -> Unit) {
    val support = error ?: f.help
    when (val k = f.kind) {
        FieldKind.Toggle -> {
            SwitchRow(f.label, draft.on(f.key), f.help) { onChange(JsonPrimitive(it)) }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
        is FieldKind.Text -> if (k.secret) {
            val redacted = (original[f.key] as? JsonPrimitive)?.content == ServiceForms.REDACTED
            val v = draft.text(f.key)
            SecretTextField(
                value = if (v == ServiceForms.REDACTED) "" else v,
                onValueChange = { onChange(JsonPrimitive(it.ifEmpty { if (redacted) ServiceForms.REDACTED else "" })) },
                label = f.label, isError = error != null, supporting = support, singleLine = !k.multiline,
                hiddenByServer = redacted && v == ServiceForms.REDACTED,
            )
        } else OutlinedTextField(
            value = draft.text(f.key), onValueChange = { onChange(JsonPrimitive(it)) },
            label = { Text(f.label, maxLines = 1) }, isError = error != null, singleLine = !k.multiline,
            supportingText = support?.let { { Text(it) } },
            textStyle = if (k.multiline) MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace) else MaterialTheme.typography.bodyLarge,
            modifier = Modifier.fillMaxWidth().then(if (k.multiline) Modifier.heightIn(min = 96.dp) else Modifier),
        )
        is FieldKind.Number -> OutlinedTextField(
            value = draft.text(f.key), onValueChange = { v -> onChange(JsonPrimitive(v.filter { it.isDigit() }.take(10))) },
            label = { Text(f.label, maxLines = 1) }, isError = error != null, singleLine = true,
            placeholder = k.emptyLabel?.let { { Text(it) } },
            supportingText = support?.let { { Text(it) } },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        is FieldKind.Pick -> {
            val current = draft[f.key]?.takeUnless { it is JsonNull }?.let { (it as? JsonPrimitive)?.content }
            val base = k.options.ifEmpty { loaded }
            val unlisted = current != null && current.isNotEmpty() && base.none { it.value == current }
            if (k.custom) {
                var typing by rememberSaveable(f.key) { mutableStateOf(unlisted) }
                val other = Option(OTHER, "Other…")
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    PickField(
                        f.label, if (typing) other else base.firstOrNull { it.value == current }, base + other, { it.label },
                        onPick = { o -> if (o.value == OTHER) typing = true else { typing = false; onChange(o.value?.let { JsonPrimitive(it) } ?: JsonNull) } },
                        isError = error != null && !typing, supporting = if (typing) null else support,
                    )
                    if (typing) OutlinedTextField(
                        value = current.orEmpty(), onValueChange = { onChange(JsonPrimitive(it.trim())) }, singleLine = true,
                        label = { Text("${f.label} (type it)") }, isError = error != null, supportingText = support?.let { { Text(it) } },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                return
            }
            val options = if (unlisted) base + Option(current!!, current) else base
            PickField(
                f.label, options.firstOrNull { it.value == current }, options, { it.label },
                onPick = { o -> onChange(o.value?.let { JsonPrimitive(it) } ?: JsonNull) },
                isError = error != null, supporting = support,
                emptyText = if (options.isEmpty()) "No choices available" else "—",
            )
        }
        is FieldKind.MultiPick -> {
            val selected = draft.list(f.key)
            val base = k.options.ifEmpty { loaded }
            val options = base + selected.filter { s -> base.none { it.value == s } }.map { Option(it, it) }
            Column {
                Text(f.label, style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(4.dp))
                if (options.isEmpty()) Text("No choices available from the NAS", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    options.forEach { o ->
                        val on = o.value in selected
                        FilterChip(selected = on, onClick = {
                            val next = if (on) selected - o.value!! else selected + o.value!!
                            onChange(JsonArray(next.map { JsonPrimitive(it) }))
                        }, label = { Text(o.label, maxLines = 1) })
                    }
                }
                support?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
        FieldKind.TextList -> OutlinedTextField(
            value = draft.text(f.key), onValueChange = { onChange(JsonPrimitive(it)) },
            label = { Text(f.label, maxLines = 1) }, isError = error != null, singleLine = true,
            supportingText = support?.let { { Text(it) } }, modifier = Modifier.fillMaxWidth(),
        )
    }
}
