package app.truenascompanion.ui.apps

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.rounded.DataObject
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import app.truenascompanion.ui.components.Scaffold
import androidx.compose.material3.Text
import app.truenascompanion.ui.components.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.truenascompanion.AppContainer
import app.truenascompanion.data.api.userMessage
import app.truenascompanion.data.model.Health
import app.truenascompanion.ui.appViewModel
import app.truenascompanion.ui.apps.form.AppForm
import app.truenascompanion.ui.apps.form.AppFormGroups
import app.truenascompanion.ui.apps.form.FormGroup
import app.truenascompanion.ui.apps.form.ValuePath
import app.truenascompanion.ui.apps.form.display
import app.truenascompanion.ui.components.GlowButton
import app.truenascompanion.ui.components.InfoBanner
import app.truenascompanion.ui.components.ScrollableErrorState
import app.truenascompanion.ui.components.SkeletonList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

sealed interface AppFormMode {
    data class Install(val catalogApp: String, val train: String) : AppFormMode
    data class Edit(val appName: String) : AppFormMode
}

data class AppFormUi(
    /** 1.8.0: the user changed something (unsaved-changes warning on back). */
    val touched: Boolean = false,
    val loading: Boolean = true,
    val loadError: String? = null,
    val title: String = "",
    val iconUrl: String? = null,
    val version: String? = null,
    val groups: List<FormGroup> = emptyList(),
    val values: JsonObject = JsonObject(emptyMap()),
    val appName: String = "",
    val appNameError: String? = null,
    /** Custom (compose) apps have no questions: only the JSON editor. */
    val jsonOnly: Boolean = false,
    val jsonMode: Boolean = false,
    val jsonText: String = "",
    val jsonError: String? = null,
    val issues: Map<String, String> = emptyMap(),
    val unsupported: List<String> = emptyList(),
    val submitting: Boolean = false,
    val submitError: String? = null,
    val done: String? = null,
)

class AppFormViewModel(private val c: AppContainer, val mode: AppFormMode) : ViewModel() {
    private val _ui = MutableStateFlow(AppFormUi())
    val ui: StateFlow<AppFormUi> = _ui.asStateFlow()
    private var version: String = "latest"
    private var customApp = false

    init { load() }

    fun load() = viewModelScope.launch {
        _ui.update { it.copy(loading = true, loadError = null) }
        try {
            when (mode) {
                is AppFormMode.Install -> {
                    val d = c.repository.call { it.catalogAppDetails(mode.catalogApp, mode.train) }
                    version = d.version
                    val questions = AppForm.parseQuestions(d.schema)
                    val installed = runCatching { c.repository.call { it.apps() } }.getOrDefault(emptyList()).map { it.name }.toSet()
                    val name = generateSequence(1) { it + 1 }.map { if (it == 1) mode.catalogApp else "${mode.catalogApp}-$it" }.first { it !in installed }
                    _ui.update {
                        it.copy(
                            loading = false, title = d.app.title, iconUrl = d.app.iconUrl, version = d.appVersion,
                            groups = AppForm.groups(d.schema), values = AppForm.withDefaults(questions, d.defaults),
                            appName = name.take(40), unsupported = AppForm.unsupported(questions).map { q -> q.label },
                        )
                    }
                }
                is AppFormMode.Edit -> {
                    val e = c.repository.call { it.appEditData(mode.appName) }
                    customApp = e.customApp
                    val questions = AppForm.parseQuestions(e.schema)
                    val values = if (customApp) e.values else AppForm.withDefaults(questions, e.values)
                    _ui.update {
                        it.copy(
                            loading = false, title = mode.appName, appName = mode.appName,
                            groups = if (customApp) emptyList() else AppForm.groups(e.schema), values = values,
                            jsonOnly = customApp || questions.isEmpty(), jsonMode = customApp || questions.isEmpty(),
                            jsonText = if (customApp || questions.isEmpty()) pretty.encodeToString(JsonElement.serializer(), values) else "",
                            unsupported = AppForm.unsupported(questions).map { q -> q.label },
                        )
                    }
                }
            }
        } catch (e: Throwable) {
            _ui.update { it.copy(loading = false, loadError = e.userMessage()) }
        }
    }

    fun change(path: ValuePath, value: JsonElement) = _ui.update {
        val v = AppForm.set(it.values, path, value) as JsonObject
        it.copy(values = v, issues = it.issues - path.display(), submitError = null, touched = true)
    }

    fun remove(path: ValuePath, index: Int) = _ui.update { it.copy(values = AppForm.removeAt(it.values, path, index) as JsonObject, touched = true) }

    fun appName(name: String) = _ui.update { it.copy(appName = name.lowercase().trim(), appNameError = null, touched = true) }

    fun jsonText(text: String) = _ui.update { it.copy(jsonText = text, jsonError = null, touched = true) }

    fun toggleJson() = _ui.update { s ->
        if (s.jsonOnly) return@update s
        if (!s.jsonMode) s.copy(jsonMode = true, jsonText = pretty.encodeToString(JsonElement.serializer(), s.values), jsonError = null)
        else parse(s.jsonText)?.let { s.copy(jsonMode = false, values = it) } ?: s.copy(jsonError = "Not valid JSON (an object is expected)")
    }

    private fun parse(text: String): JsonObject? = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull()

    fun submit() {
        val s = _ui.value
        val values = if (s.jsonMode) parse(s.jsonText) ?: run { _ui.update { it.copy(jsonError = "Not valid JSON (an object is expected)") }; return } else s.values
        if (mode is AppFormMode.Install) {
            AppForm.appNameError(s.appName)?.let { err -> _ui.update { it.copy(appNameError = err) }; return }
        }
        if (!s.jsonMode) {
            val issues = s.groups.flatMap { g -> AppForm.validate(g.questions, values) }.associate { it.path.display() to it.message }
            if (issues.isNotEmpty()) { _ui.update { it.copy(issues = issues, submitError = "Check the highlighted fields") }; return }
        }
        _ui.update { it.copy(submitting = true, submitError = null) }
        viewModelScope.launch {
            try {
                when (mode) {
                    is AppFormMode.Install -> c.repository.call { it.startAppInstall(mode.catalogApp, s.appName, mode.train, version, values) }
                    is AppFormMode.Edit -> c.repository.call { it.startAppUpdate(mode.appName, values, customApp) }
                }
                _ui.update { it.copy(submitting = false, done = if (mode is AppFormMode.Install) "Installing ${s.appName}…" else "Saving ${s.appName}…") }
            } catch (e: Throwable) {
                _ui.update { it.copy(submitting = false, submitError = e.userMessage()) }
            }
        }
    }

    companion object {
        private val pretty = Json { prettyPrint = true }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppFormScreen(mode: AppFormMode, onBack: () -> Unit, onDone: (String) -> Unit) {
    val key = when (mode) { is AppFormMode.Install -> "install-${mode.train}-${mode.catalogApp}"; is AppFormMode.Edit -> "edit-${mode.appName}" }
    val vm = appViewModel(key = key) { AppFormViewModel(it, mode) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    LaunchedEffect(ui.done) { ui.done?.let(onDone) }
    val back = app.truenascompanion.ui.components.rememberDiscardGuard(ui.touched && ui.done == null && !ui.submitting, "your changes to this app", onBack)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (mode is AppFormMode.Install) "Install ${ui.title}" else "Edit ${ui.title}", maxLines = 1) },
                navigationIcon = { IconButton(onClick = back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
                actions = {
                    if (!ui.loading && ui.loadError == null && !ui.jsonOnly) {
                        IconButton(onClick = vm::toggleJson) {
                            Icon(if (ui.jsonMode) Icons.Rounded.Tune else Icons.Rounded.DataObject, if (ui.jsonMode) "Back to the form" else "Edit as JSON")
                        }
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                ui.loading -> SkeletonList(5, 120.dp)
                ui.loadError != null -> ScrollableErrorState(ui.loadError ?: "") { vm.load() }
                else -> AppFormContent(
                    ui, install = mode is AppFormMode.Install,
                    onAppName = vm::appName, onChange = vm::change, onRemove = vm::remove, onJson = vm::jsonText, onSubmit = vm::submit,
                )
            }
        }
    }
}

/** Stateless form body (also rendered by the screenshot tests). */
@Composable
fun AppFormContent(
    ui: AppFormUi,
    install: Boolean,
    onAppName: (String) -> Unit,
    onChange: (ValuePath, JsonElement) -> Unit,
    onRemove: (ValuePath, Int) -> Unit,
    onJson: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (install) {
            OutlinedTextField(
                value = ui.appName, onValueChange = onAppName, singleLine = true,
                label = { Text("App name *") }, isError = ui.appNameError != null,
                supportingText = { Text(ui.appNameError ?: "Lowercase letters, digits and hyphens. Can't be changed later.") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                modifier = Modifier.fillMaxWidth(),
            )
            ui.version?.let { Text("Installs version $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        if (ui.jsonMode) {
            Text(
                if (ui.jsonOnly && !install) "This app is configured with a Docker Compose file. Edit its configuration as JSON."
                else "All settings as JSON. Anything the form can't show can be changed here.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = ui.jsonText, onValueChange = onJson, minLines = 12,
                isError = ui.jsonError != null, supportingText = ui.jsonError?.let { { Text(it) } },
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            if (ui.unsupported.isNotEmpty()) {
                InfoBanner(
                    "${ui.unsupported.size} setting${if (ui.unsupported.size == 1) "" else "s"} can't be shown in this form and keep their " +
                        (if (install) "defaults" else "current values") + ": ${ui.unsupported.take(4).joinToString()}. Use Edit as JSON (top right) to change them.",
                    health = Health.UNKNOWN,
                )
            }
            AppFormGroups(ui.groups, ui.values, editing = !install, issues = ui.issues, onChange = onChange, onRemove = onRemove)
        }
        AnimatedVisibility(ui.submitError != null) { InfoBanner(ui.submitError ?: "", health = Health.CRITICAL) }
        GlowButton(onClick = onSubmit, enabled = !ui.submitting, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            if (ui.submitting) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)) }
            Text(if (install) "Install" else "Save", maxLines = 1)
        }
        Spacer(Modifier.height(24.dp))
    }
}

