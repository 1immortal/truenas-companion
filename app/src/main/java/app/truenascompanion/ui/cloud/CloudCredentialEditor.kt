package app.truenascompanion.ui.cloud

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.truenascompanion.AppContainer
import app.truenascompanion.data.api.CloudSyncApi
import app.truenascompanion.data.api.KeyPair
import app.truenascompanion.data.api.OneDriveDrive
import app.truenascompanion.data.api.VerifyResult
import app.truenascompanion.data.api.userMessage
import app.truenascompanion.data.cloud.CloudProviders
import app.truenascompanion.data.cloud.CredField
import app.truenascompanion.data.cloud.FieldKind
import app.truenascompanion.data.model.Health
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
import app.truenascompanion.ui.tasks.splitFieldErrors
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/** A provider in the picker: from `cloudsync.providers`, limited to the ones this app has fields for. */
data class ProviderOption(val type: String, val title: String)

data class CredentialForm(val name: String = "", val type: String = "S3", val values: Map<String, String> = CloudProviders.defaults("S3")) {
    fun value(k: String) = values[k].orEmpty()
    val redacted: Boolean get() = CloudProviders.redacted(values)
    fun errors(): Map<String, String> = CloudProviders.errors(type, values) + (if (name.isBlank()) mapOf("name" to "Give it a name") else emptyMap())
}

class CloudCredentialEditorViewModel(private val c: AppContainer, private val id: Int?) : ViewModel() {
    private val _state = MutableStateFlow<UiState<CredentialForm>>(UiState.Loading)
    val state = _state.asStateFlow()
    private val _form = MutableStateFlow(CredentialForm())
    val form = _form.asStateFlow()
    private val _providers = MutableStateFlow(CloudProviders.SPECS.map { ProviderOption(it.type, it.title) })
    val providers = _providers.asStateFlow()
    private val _keyPairs = MutableStateFlow<List<KeyPair>>(emptyList())
    val keyPairs = _keyPairs.asStateFlow()
    private val _serverErrors = MutableStateFlow<Map<String, String>>(emptyMap())
    val serverErrors = _serverErrors.asStateFlow()
    private val _generalError = MutableStateFlow<String?>(null)
    val generalError = _generalError.asStateFlow()
    private val _saving = MutableStateFlow(false)
    val saving = _saving.asStateFlow()
    private val _saved = Channel<String>(Channel.BUFFERED)
    val saved = _saved.receiveAsFlow()
    private val _verifying = MutableStateFlow(false)
    val verifying = _verifying.asStateFlow()
    private val _verify = MutableStateFlow<VerifyOutcome?>(null)
    val verify = _verify.asStateFlow()
    private val _drives = MutableStateFlow<DrivesState?>(null)
    val drives = _drives.asStateFlow()

    init { load() }

    fun load() = viewModelScope.launch {
        _state.value = UiState.Loading
        try {
            val (providers, keys, existing) = c.repository.call { api ->
                val a = CloudSyncApi(api)
                Triple(
                    runCatching { a.providers() }.getOrNull(),
                    runCatching { a.keyPairs() }.getOrDefault(emptyList()),
                    id?.let { want -> a.credentials().firstOrNull { it.id == want } ?: throw IllegalStateException("This credential no longer exists") },
                )
            }
            providers?.filter { CloudProviders.spec(it.name) != null }?.takeIf { it.isNotEmpty() }?.let { list ->
                _providers.value = list.sortedBy { CloudProviders.sortKey(it.name) }.map { ProviderOption(it.name, CloudProviders.spec(it.name)?.title ?: it.title) }
            }
            _keyPairs.value = keys
            val f = existing?.let { CredentialForm(it.name, it.type, CloudProviders.valuesOf(it.type, it.provider)) } ?: CredentialForm()
            _form.value = f
            _state.value = UiState.Success(f)
        } catch (e: Throwable) {
            _state.value = UiState.Error(e.userMessage(), e)
        }
    }

    fun edit(f: (CredentialForm) -> CredentialForm) {
        val before = _form.value
        var after = f(before)
        if (after.type != before.type) after = after.copy(values = CloudProviders.defaults(after.type))
        _form.value = after
        val touched = (after.values.keys + before.values.keys).filter { after.values[it] != before.values[it] }.toMutableSet()
        if (after.name != before.name) touched += "name"
        if (after.type != before.type) { _serverErrors.value = emptyMap(); _drives.value = null }
        else if (touched.isNotEmpty()) _serverErrors.value = _serverErrors.value - touched
        _generalError.value = null
    }

    fun save() {
        val f = _form.value
        if (f.errors().isNotEmpty() || f.redacted || _saving.value) return
        _saving.value = true
        viewModelScope.launch {
            try {
                val provider = CloudProviders.providerJson(f.type, f.values)
                c.repository.call { api -> CloudSyncApi(api).let { if (id == null) it.createCredential(f.name, provider) else it.updateCredential(id, f.name, provider) } }
                _saved.trySend(if (id == null) "Credential added" else "Credential saved")
            } catch (e: Throwable) {
                val known = (CloudProviders.spec(f.type)?.fields?.map { it.key }.orEmpty() + "name").toSet()
                val (mine, general) = splitFieldErrors(e, known)
                _serverErrors.value = mine
                _generalError.value = general
            } finally {
                _saving.value = false
            }
        }
    }

    fun verify() {
        val f = _form.value
        if (_verifying.value || CloudProviders.errors(f.type, f.values).isNotEmpty() || f.redacted) return
        _verifying.value = true
        viewModelScope.launch {
            _verify.value = try {
                VerifyOutcome(f.name.ifBlank { "this credential" }, c.repository.call { CloudSyncApi(it).verify(CloudProviders.providerJson(f.type, f.values)) }, null)
            } catch (e: Throwable) {
                VerifyOutcome(f.name.ifBlank { "this credential" }, null, e.userMessage())
            } finally {
                _verifying.value = false
            }
        }
    }

    fun closeVerify() { _verify.value = null }

    /** OneDrive: list the drives the pasted token can see (`cloudsync.onedrive_list_drives`). */
    fun findDrives() {
        val f = _form.value
        if (CloudProviders.tokenError(f.value("token")) != null) return
        _drives.value = DrivesState(loading = true)
        viewModelScope.launch {
            _drives.value = try {
                DrivesState(drives = c.repository.call { CloudSyncApi(it).oneDriveDrives(f.value("client_id"), f.value("client_secret"), f.value("token")) })
            } catch (e: Throwable) {
                DrivesState(error = e.userMessage())
            }
        }
    }

    fun closeDrives() { _drives.value = null }
}

data class DrivesState(val loading: Boolean = false, val drives: List<OneDriveDrive> = emptyList(), val error: String? = null)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CloudCredentialEditorScreen(id: Int?, onBack: () -> Unit) {
    val vm = appViewModel(key = "cloud-cred:${id ?: "new"}") { CloudCredentialEditorViewModel(it, id) }
    val state by vm.state.collectAsStateWithLifecycle()
    val form by vm.form.collectAsStateWithLifecycle()
    val providers by vm.providers.collectAsStateWithLifecycle()
    val keyPairs by vm.keyPairs.collectAsStateWithLifecycle()
    val serverErrors by vm.serverErrors.collectAsStateWithLifecycle()
    val generalError by vm.generalError.collectAsStateWithLifecycle()
    val saving by vm.saving.collectAsStateWithLifecycle()
    val verifying by vm.verifying.collectAsStateWithLifecycle()
    val verify by vm.verify.collectAsStateWithLifecycle()
    val drives by vm.drives.collectAsStateWithLifecycle()
    LaunchedEffect(vm) { vm.saved.collect { onBack() } }
    val original = (state as? UiState.Success)?.data
    val dirty = original != null && original != form
    var confirmDiscard by remember { mutableStateOf(false) }
    BackHandler(enabled = dirty) { confirmDiscard = true }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (id == null) "New cloud credential" else "Edit cloud credential") },
                navigationIcon = { IconButton(onClick = { if (dirty) confirmDiscard = true else onBack() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
                actions = {
                    if (saving) CircularProgressIndicator(Modifier.size(22.dp).padding(end = 4.dp), strokeWidth = 2.dp)
                    TextButton(onClick = vm::save, enabled = (dirty || id == null) && !saving && form.errors().isEmpty() && !form.redacted) { Text("Save") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            when (val s = state) {
                UiState.Loading -> SkeletonList(3, 140.dp)
                is UiState.Error -> ScrollableErrorState(s.message, s.isLoginRequired) { vm.load() }
                is UiState.Success -> CredentialEditorContent(
                    form, providers, keyPairs, form.errors() + serverErrors, generalError, isNew = id == null,
                    showErrors = dirty || serverErrors.isNotEmpty(), verifying = verifying,
                    onChange = vm::edit, onVerify = vm::verify, onFindDrives = vm::findDrives,
                )
            }
        }
    }
    verify?.let { VerifyDialog(it.credential, it.result, it.error, vm::closeVerify) }
    drives?.let { d ->
        DrivesDialog(d, onDismiss = vm::closeDrives) { drive ->
            vm.closeDrives()
            vm.edit { f -> f.copy(values = f.values + mapOf("drive_id" to drive.id, "drive_type" to drive.type.ifBlank { f.value("drive_type") })) }
        }
    }
    if (confirmDiscard) ConfirmDialog(
        title = "Discard changes?", text = "Your changes to this credential haven't been saved.",
        confirmLabel = "Discard", destructive = true, requireAuth = false,
        onConfirm = { confirmDiscard = false; onBack() }, onDismiss = { confirmDiscard = false },
    )
}

@Composable
private fun DrivesDialog(d: DrivesState, onDismiss: () -> Unit, onPick: (OneDriveDrive) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("OneDrive drives") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                when {
                    d.loading -> Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp); Spacer(Modifier.width(12.dp)); Text("Asking Microsoft…") }
                    d.error != null -> Text(d.error, color = MaterialTheme.colorScheme.error)
                    d.drives.isEmpty() -> Text("The token can't see any drives.")
                    else -> d.drives.forEach { drive ->
                        TextButton(onClick = { onPick(drive) }, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.fillMaxWidth()) {
                                Text(drive.name.ifBlank { drive.id }, style = MaterialTheme.typography.bodyLarge)
                                Text(listOf(drive.type.lowercase().replace('_', ' '), drive.description).filter { it.isNotBlank() }.joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

/** Stateless credential form (also used by the screenshot previews). */
@Composable
fun CredentialEditorContent(
    form: CredentialForm,
    providers: List<ProviderOption>,
    keyPairs: List<KeyPair>,
    errors: Map<String, String>,
    generalError: String?,
    isNew: Boolean,
    showErrors: Boolean = true,
    verifying: Boolean = false,
    startAdvanced: Boolean = false,
    onChange: ((CredentialForm) -> CredentialForm) -> Unit,
    onVerify: () -> Unit = {},
    onFindDrives: () -> Unit = {},
) {
    fun err(k: String) = errors[k]?.takeIf { showErrors }
    val spec = CloudProviders.spec(form.type)
    var advanced by rememberSaveable { mutableStateOf(startAdvanced) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(16.dp).testTag("page"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        generalError?.let { InfoBanner(it, health = Health.CRITICAL) }
        if (form.redacted) InfoBanner("TrueNAS doesn't show this credential's keys to your account, so it can't be changed or tested here. Sign in as a full admin to edit it.")
        ElevatedSection(contentPadding = 14.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    form.name, { v -> onChange { it.copy(name = v) } }, label = { Text("Name") }, singleLine = true,
                    placeholder = { Text("e.g. Backblaze photos") }, isError = err("name") != null,
                    supportingText = err("name")?.let { { Text(it) } }, modifier = Modifier.fillMaxWidth(),
                )
                val current = providers.firstOrNull { it.type == form.type } ?: ProviderOption(form.type, CloudProviders.title(form.type))
                PickField(
                    "Provider", current, if (isNew) providers else listOf(current), { it.title },
                    onPick = { p -> onChange { it.copy(type = p.type) } },
                    supporting = if (isNew) null else "The provider can't be changed; add a new credential instead",
                )
            }
        }
        if (spec == null) {
            InfoBanner("The app can't edit ${CloudProviders.title(form.type)} credentials. Use the TrueNAS web UI for this one.")
            return@Column
        }
        if (spec.oauth) OAuthHelp(spec.title, spec.rcloneName)
        SectionTitle("Connection")
        ElevatedSection(contentPadding = 14.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                spec.fields.filter { !it.advanced }.forEach { f -> CredFieldInput(f, form, keyPairs, err(f.key), onChange) }
                if (form.type == "ONEDRIVE") OutlinedButton(onClick = onFindDrives, enabled = CloudProviders.tokenError(form.value("token")) == null, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.Search, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Find drives")
                }
            }
        }
        val adv = spec.fields.filter { it.advanced }
        if (adv.isNotEmpty()) {
            TextButton(onClick = { advanced = !advanced }) {
                Icon(if (advanced) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null); Spacer(Modifier.width(6.dp))
                Text(if (advanced) "Hide advanced options" else "Advanced options")
            }
            if (advanced || adv.any { err(it.key) != null }) ElevatedSection(contentPadding = 14.dp) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { adv.forEach { f -> CredFieldInput(f, form, keyPairs, err(f.key), onChange) } }
            }
        }
        FilledTonalButton(
            onClick = onVerify, enabled = !verifying && !form.redacted && CloudProviders.errors(form.type, form.values).isEmpty(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (verifying) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Icon(Icons.Rounded.VerifiedUser, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp)); Text(if (verifying) "Checking…" else "Verify connection")
        }
        Text("Verify asks TrueNAS to sign in to the provider with these settings. Nothing is saved until you tap Save.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Why there's no "Log in" button: the TrueNAS OAuth helper only hands its token to the web UI window. */
@Composable
fun OAuthHelp(title: String, rcloneName: String?) {
    ElevatedSection(contentPadding = 14.dp) {
        Text("Signing in to $title", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(4.dp))
        Text(
            "The web UI's \"Log In To Provider\" button opens a TrueNAS pop-up that hands the token back to the web page, which an app can't receive. Paste the token instead:",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            buildString {
                if (rcloneName != null) append("• On a computer with rclone, run  rclone authorize \"$rcloneName\"  and paste the JSON it prints ({\"access_token\":…}).\n")
                append("• Or add the credential in the TrueNAS web UI once. It then shows up here, and you can verify, edit and use it in tasks.")
            },
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun CredFieldInput(f: CredField, form: CredentialForm, keyPairs: List<KeyPair>, error: String?, onChange: ((CredentialForm) -> CredentialForm) -> Unit) {
    val value = form.value(f.key)
    val set: (String) -> Unit = { v -> onChange { it.copy(values = it.values + (f.key to v)) } }
    val label = f.label + if (f.required && f.kind != FieldKind.BOOL) " *" else ""
    when (f.kind) {
        FieldKind.SECRET -> SecretTextField(value, set, label, isError = error != null, supporting = error ?: f.help)
        FieldKind.TOKEN, FieldKind.JSON -> SecretTextField(value, set, label, isError = error != null, singleLine = false,
            supporting = error ?: f.help ?: if (f.kind == FieldKind.TOKEN) "JSON with access_token, refresh_token and expiry" else null)
        FieldKind.BOOL -> SwitchRow(f.label, value == "true", f.help) { on -> set(on.toString()) }
        FieldKind.CHOICE -> PickField(label, f.choices.firstOrNull { it.first == value } ?: f.choices.firstOrNull(), f.choices, { it.second },
            onPick = { set(it.first) }, isError = error != null, supporting = error ?: f.help)
        FieldKind.KEYPAIR -> {
            val options = listOf<KeyPair?>(null) + keyPairs
            PickField(f.label, keyPairs.firstOrNull { it.id.toString() == value }, options, { it?.name ?: "None (use the password)" },
                onPick = { set(it?.id?.toString().orEmpty()) }, isError = error != null,
                supporting = error ?: if (keyPairs.isEmpty()) "No SSH key pairs on the NAS yet (System › Keychain in the web UI)" else f.help, emptyText = "None (use the password)")
        }
        else -> OutlinedTextField(
            value, set, label = { Text(label) }, singleLine = true, isError = error != null,
            supportingText = (error ?: f.help)?.let { { Text(it) } },
            keyboardOptions = KeyboardOptions(keyboardType = when (f.kind) { FieldKind.INT -> KeyboardType.Number; FieldKind.URL -> KeyboardType.Uri; else -> KeyboardType.Text }, autoCorrectEnabled = false),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
