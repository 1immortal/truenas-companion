package app.truenascompanion.ui.replication

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.truenascompanion.AppContainer
import app.truenascompanion.data.api.ReplicationApi
import app.truenascompanion.data.api.SshSetup
import app.truenascompanion.data.api.userMessage
import app.truenascompanion.data.model.Health
import app.truenascompanion.data.replication.ReplicationLogic
import app.truenascompanion.data.replication.SshConnection
import app.truenascompanion.data.replication.SshKeyPair
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
import app.truenascompanion.ui.tasks.CommandBox
import app.truenascompanion.ui.tasks.splitFieldErrors
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** New or edited SSH connection. New ones go through `keychaincredential.setup_ssh_connection`. */
data class SshConnForm(
    val name: String = "",
    val semiAutomatic: Boolean = true,
    val generateKey: Boolean = true,
    val newKeyName: String = "",
    val existingKeyId: Int? = null,
    val url: String = "",
    val verifySsl: Boolean = true,
    val adminUsername: String = "root",
    val password: String = "",
    val otp: String = "",
    val useToken: Boolean = false,
    val token: String = "",
    val username: String = "root",
    val sudo: Boolean = false,
    val host: String = "",
    val port: String = "22",
    val remoteHostKey: String = "",
    val connectTimeout: String = "10",
)

object SshForms {
    private val URL = Regex("^https?://[^\\s/:]+(:\\d{1,5})?(/.*)?$", RegexOption.IGNORE_CASE)

    fun of(c: SshConnection) = SshConnForm(
        name = c.name, semiAutomatic = false, generateKey = false, existingKeyId = c.privateKeyId, host = c.host, port = c.port.toString(),
        username = c.username, remoteHostKey = c.remoteHostKey, connectTimeout = c.connectTimeout.toString(),
    )

    /** Field → message; [editing]: an existing connection (always manual fields). */
    fun errors(f: SshConnForm, editing: Boolean, keyNames: Set<String>): Map<String, String> = buildMap {
        if (f.name.isBlank()) put("connection_name", "Give the connection a name")
        if (!editing) {
            if (f.generateKey) {
                if (f.newKeyName.isBlank()) put("key_name", "Name the new key pair")
                else if (f.newKeyName.trim() in keyNames) put("key_name", "A key pair with this name exists")
            } else if (f.existingKeyId == null) put("existing_key_id", "Choose a key pair")
        } else if (f.existingKeyId == null) put("existing_key_id", "Choose a key pair")
        if (f.username.isBlank()) put("username", "Required")
        if ((f.connectTimeout.trim().toIntOrNull() ?: 0) < 1) put("connect_timeout", "Seconds, 1 or more")
        if (!editing && f.semiAutomatic) {
            if (!URL.matches(f.url.trim())) put("url", "Like https://nas2.example.com")
            if (f.useToken) { if (f.token.isBlank()) put("token", "Paste the token") }
            else {
                if (f.adminUsername.isBlank()) put("admin_username", "Required")
                if (f.password.isEmpty()) put("password", "Required")
            }
        } else {
            if (f.host.isBlank()) put("host", "Required")
            if ((f.port.trim().toIntOrNull() ?: 0) !in 1..65535) put("port", "1–65535")
            if (f.remoteHostKey.isBlank()) put("remote_host_key", "Tap Discover or paste the host key")
        }
    }

    fun setup(f: SshConnForm) = SshSetup(
        connectionName = f.name, existingKeyId = if (f.generateKey) null else f.existingKeyId, newKeyName = f.newKeyName,
        semiAutomatic = f.semiAutomatic, url = f.url, verifySsl = f.verifySsl, adminUsername = f.adminUsername,
        password = if (f.useToken) "" else f.password, otp = if (f.useToken) "" else f.otp, token = if (f.useToken) f.token else "", sudo = f.sudo,
        host = f.host, port = f.port.trim().toIntOrNull() ?: 22, remoteHostKey = f.remoteHostKey,
        username = f.username, connectTimeout = f.connectTimeout.trim().toIntOrNull() ?: 10,
    )

    /** Middleware field names (after the prefix is dropped) → form fields. */
    val FIELD_ALIASES = mapOf("name" to "key_name", "private_key" to "existing_key_id", "otp_token" to "password")
    val FIELDS = setOf("connection_name", "key_name", "existing_key_id", "url", "token", "admin_username", "password", "username", "host", "port", "remote_host_key", "connect_timeout")
}

data class SshEditorData(val keyPairs: List<SshKeyPair>, val form: SshConnForm)

class SshConnectionEditorViewModel(private val c: AppContainer, private val id: Int?) : ViewModel() {
    private val _state = MutableStateFlow<UiState<SshEditorData>>(UiState.Loading)
    val state = _state.asStateFlow()
    private val _form = MutableStateFlow(SshConnForm())
    val form = _form.asStateFlow()
    private val _serverErrors = MutableStateFlow<Map<String, String>>(emptyMap())
    val serverErrors = _serverErrors.asStateFlow()
    private val _generalError = MutableStateFlow<String?>(null)
    val generalError = _generalError.asStateFlow()
    private val _saving = MutableStateFlow(false)
    val saving = _saving.asStateFlow()
    private val _scanning = MutableStateFlow(false)
    val scanning = _scanning.asStateFlow()
    /** After a manual setup: the public key to add on the other system. */
    private val _publicKey = MutableStateFlow<String?>(null)
    val publicKey = _publicKey.asStateFlow()
    private val _saved = Channel<Unit>(Channel.BUFFERED)
    val saved = _saved.receiveAsFlow()

    init { load() }

    fun load() = viewModelScope.launch {
        _state.value = UiState.Loading
        try {
            val (keys, conn) = c.repository.call { api ->
                val a = ReplicationApi(api)
                a.keyPairs() to id?.let { i -> a.connections().firstOrNull { it.id == i } ?: throw IllegalStateException("This SSH connection no longer exists") }
            }
            val f = conn?.let(SshForms::of) ?: SshConnForm(newKeyName = "replication-key", existingKeyId = keys.firstOrNull()?.id, generateKey = true)
            _form.value = f
            _state.value = UiState.Success(SshEditorData(keys, f))
            if (conn?.redacted == true) _generalError.value = "TrueNAS didn't send this connection's details to your account, so it can't be edited here."
        } catch (e: Throwable) {
            _state.value = UiState.Error(e.userMessage(), e)
        }
    }

    fun edit(f: (SshConnForm) -> SshConnForm) {
        val before = _form.value
        val after = f(before)
        _form.value = after
        _serverErrors.value = _serverErrors.value.filterKeys { k -> FIELD_OF[k]?.let { it(before) == it(after) } ?: true }
        if (_state.value is UiState.Success && (_state.value as UiState.Success).data.form != after) _generalError.value = null
    }

    fun scan() {
        val f = _form.value
        if (f.host.isBlank() || _scanning.value) return
        _scanning.value = true
        viewModelScope.launch {
            try {
                val key = c.repository.call { ReplicationApi(it).scanHostKey(f.host, f.port.trim().toIntOrNull() ?: 22, f.connectTimeout.trim().toIntOrNull() ?: 10) }
                edit { it.copy(remoteHostKey = key) }
            } catch (e: Throwable) {
                _serverErrors.update { it + ("remote_host_key" to e.userMessage()) }
            } finally {
                _scanning.value = false
            }
        }
    }

    fun save() {
        val f = _form.value
        val data = (_state.value as? UiState.Success)?.data ?: return
        if (SshForms.errors(f, id != null, data.keyPairs.map { it.name }.toSet()).isNotEmpty() || _saving.value) return
        _saving.value = true
        viewModelScope.launch {
            try {
                if (id != null) {
                    c.repository.call {
                        ReplicationApi(it).updateConnection(SshConnection(id, f.name, f.host, f.port.trim().toInt(), f.username, f.existingKeyId, f.remoteHostKey, f.connectTimeout.trim().toInt()))
                    }
                    _saved.trySend(Unit)
                } else {
                    val (_, pub) = c.repository.call { api ->
                        val a = ReplicationApi(api)
                        val conn = a.setupConnection(SshForms.setup(f))
                        conn to if (f.semiAutomatic) null else a.keyPairs().firstOrNull { it.id == conn?.privateKeyId }?.publicKey
                    }
                    if (pub != null) _publicKey.value = pub else _saved.trySend(Unit)
                }
            } catch (e: Throwable) {
                val (mine, general) = splitFieldErrors(e, SshForms.FIELDS, SshForms.FIELD_ALIASES)
                _serverErrors.value = mine
                _generalError.value = general
            } finally {
                _saving.value = false
            }
        }
    }

    fun donePublicKey() { _publicKey.value = null; _saved.trySend(Unit) }

    companion object {
        private val FIELD_OF: Map<String, (SshConnForm) -> Any?> = mapOf(
            "connection_name" to { f -> f.name }, "key_name" to { f -> f.newKeyName to f.generateKey }, "existing_key_id" to { f -> f.existingKeyId to f.generateKey },
            "url" to { f -> f.url }, "token" to { f -> f.token }, "admin_username" to { f -> f.adminUsername }, "password" to { f -> f.password to f.otp },
            "username" to { f -> f.username }, "host" to { f -> f.host }, "port" to { f -> f.port }, "remote_host_key" to { f -> f.remoteHostKey },
            "connect_timeout" to { f -> f.connectTimeout },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SshConnectionEditorScreen(id: Int?, onBack: () -> Unit) {
    app.truenascompanion.ui.components.SecureWindowEffect() // 1.7.1 (M-4): private keys, passwords
    val vm = appViewModel(key = "ssh-conn:${id ?: "new"}") { SshConnectionEditorViewModel(it, id) }
    val state by vm.state.collectAsStateWithLifecycle()
    val form by vm.form.collectAsStateWithLifecycle()
    val serverErrors by vm.serverErrors.collectAsStateWithLifecycle()
    val generalError by vm.generalError.collectAsStateWithLifecycle()
    val saving by vm.saving.collectAsStateWithLifecycle()
    val scanning by vm.scanning.collectAsStateWithLifecycle()
    val publicKey by vm.publicKey.collectAsStateWithLifecycle()
    LaunchedEffect(vm) { vm.saved.collect { onBack() } }
    val data = (state as? UiState.Success)?.data
    val dirty = data != null && data.form != form
    val local = data?.let { SshForms.errors(form, id != null, it.keyPairs.map { k -> k.name }.toSet()) }.orEmpty()
    var confirmDiscard by remember { mutableStateOf(false) }
    BackHandler(enabled = dirty) { confirmDiscard = true }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (id == null) "New SSH connection" else "Edit SSH connection") },
                navigationIcon = { IconButton(onClick = { if (dirty) confirmDiscard = true else onBack() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
                actions = {
                    if (saving) CircularProgressIndicator(Modifier.size(22.dp).padding(end = 4.dp), strokeWidth = 2.dp)
                    TextButton(onClick = vm::save, enabled = (dirty || id == null) && !saving && local.isEmpty()) { Text(if (id == null && form.semiAutomatic) "Connect" else "Save") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            when (val s = state) {
                UiState.Loading -> SkeletonList(3, 150.dp)
                is UiState.Error -> ScrollableErrorState(s.message, s.isLoginRequired) { vm.load() }
                is UiState.Success -> SshConnectionContent(form, s.data.keyPairs, id != null, local + serverErrors, generalError, dirty || serverErrors.isNotEmpty(), scanning, vm::edit, vm::scan)
            }
        }
    }
    publicKey?.let { PublicKeyDialog(it, form.username, vm::donePublicKey) }
    if (confirmDiscard) ConfirmDialog(
        title = "Discard changes?", text = "This SSH connection hasn't been saved.", confirmLabel = "Discard", destructive = true, requireAuth = false,
        onConfirm = { confirmDiscard = false; onBack() }, onDismiss = { confirmDiscard = false },
    )
}

@Composable
private fun PublicKeyDialog(key: String, user: String, onDone: () -> Unit) {
    @Suppress("DEPRECATION") val clipboard = LocalClipboardManager.current
    AlertDialog(
        onDismissRequest = {},
        icon = { Icon(Icons.Rounded.Key, null) },
        title = { Text("Trust this key on the other system") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("The connection is saved. Add this public key to the authorized keys of “$user” on the other system, or replication can't sign in.")
                CommandBox(key.trim(), maxLines = 8)
                TextButton(onClick = { clipboard.setText(AnnotatedString(key.trim())) }) {
                    Icon(Icons.Rounded.ContentCopy, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Copy public key")
                }
            }
        },
        confirmButton = { TextButton(onClick = onDone) { Text("Done") } },
    )
}

/** Stateless SSH connection form (also used by the screenshot previews). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SshConnectionContent(
    form: SshConnForm,
    keyPairs: List<SshKeyPair>,
    editing: Boolean,
    errors: Map<String, String>,
    generalError: String?,
    showErrors: Boolean,
    scanning: Boolean,
    onChange: ((SshConnForm) -> SshConnForm) -> Unit,
    onScan: () -> Unit,
) {
    fun err(k: String) = errors[k]?.takeIf { showErrors }
    val semi = !editing && form.semiAutomatic
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(16.dp).testTag("page"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        generalError?.let { InfoBanner(it, health = Health.CRITICAL) }
        ElevatedSection(contentPadding = 14.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(form.name, { v -> onChange { it.copy(name = v) } }, label = { Text("Name *") }, singleLine = true,
                    placeholder = { Text("e.g. Backup NAS") }, isError = err("connection_name") != null,
                    supportingText = err("connection_name")?.let { { Text(it) } }, modifier = Modifier.fillMaxWidth())
                if (!editing) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = form.semiAutomatic, onClick = { onChange { it.copy(semiAutomatic = true) } }, label = { Text("Another TrueNAS") })
                        FilterChip(selected = !form.semiAutomatic, onClick = { onChange { it.copy(semiAutomatic = false) } }, label = { Text("Manual") })
                    }
                    Text(if (form.semiAutomatic) "This NAS signs in to the other TrueNAS once, installs its key there and turns on SSH. Your admin password is only used for that."
                        else "For any SSH server: enter the host, fetch its host key and add this NAS's public key there yourself.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        SectionTitle("Key")
        ElevatedSection(contentPadding = 14.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!editing) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = form.generateKey, onClick = { onChange { it.copy(generateKey = true) } }, label = { Text("Generate a new key") })
                    FilterChip(selected = !form.generateKey, onClick = { onChange { it.copy(generateKey = false) } }, enabled = keyPairs.isNotEmpty(), label = { Text("Use an existing key") })
                }
                if (!editing && form.generateKey) OutlinedTextField(form.newKeyName, { v -> onChange { it.copy(newKeyName = v) } }, label = { Text("New key pair name *") }, singleLine = true,
                    isError = err("key_name") != null, supportingText = { Text(err("key_name") ?: "TrueNAS creates the key; the private key never leaves the NAS") }, modifier = Modifier.fillMaxWidth())
                else PickField("Key pair *", keyPairs.firstOrNull { it.id == form.existingKeyId }, keyPairs, { it.name }, onPick = { k -> onChange { it.copy(existingKeyId = k.id) } },
                    isError = err("existing_key_id") != null, supporting = err("existing_key_id"), emptyText = "Choose a key pair")
            }
        }
        SectionTitle(if (semi) "Other TrueNAS" else "Server")
        ElevatedSection(contentPadding = 14.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (semi) {
                    OutlinedTextField(form.url, { v -> onChange { it.copy(url = v.trim()) } }, label = { Text("Address *") }, singleLine = true,
                        placeholder = { Text("https://nas2.example.com") }, isError = err("url") != null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        supportingText = { Text(err("url") ?: "The other TrueNAS's web address") }, modifier = Modifier.fillMaxWidth())
                    SwitchRow("Check its certificate", form.verifySsl, "Turn off only for a self-signed certificate you trust") { v -> onChange { it.copy(verifySsl = v) } }
                    SwitchRow("Sign in with a one-time token", form.useToken, "Instead of an admin password") { v -> onChange { it.copy(useToken = v) } }
                    if (form.useToken) SecretTextField(form.token, { v -> onChange { it.copy(token = v) } }, "Token *", isError = err("token") != null, supporting = err("token"))
                    else {
                        OutlinedTextField(form.adminUsername, { v -> onChange { it.copy(adminUsername = v.trim()) } }, label = { Text("Admin user *") }, singleLine = true,
                            isError = err("admin_username") != null, supportingText = err("admin_username")?.let { { Text(it) } }, modifier = Modifier.fillMaxWidth())
                        SecretTextField(form.password, { v -> onChange { it.copy(password = v) } }, "Admin password *", isError = err("password") != null,
                            supporting = err("password") ?: "Used once to set up the connection; not stored")
                        OutlinedTextField(form.otp, { v -> onChange { it.copy(otp = v.filter(Char::isDigit).take(8)) } }, label = { Text("Two-factor code") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), supportingText = { Text("Only if that account uses two-factor sign-in") },
                            modifier = Modifier.fillMaxWidth())
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(form.host, { v -> onChange { it.copy(host = v.trim()) } }, label = { Text("Host *") }, singleLine = true,
                            placeholder = { Text("nas2.example.com") }, isError = err("host") != null, supportingText = err("host")?.let { { Text(it) } }, modifier = Modifier.weight(2f))
                        OutlinedTextField(form.port, { v -> onChange { it.copy(port = v.filter(Char::isDigit).take(5)) } }, label = { Text("Port") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), isError = err("port") != null,
                            supportingText = err("port")?.let { { Text(it) } }, modifier = Modifier.weight(1f))
                    }
                    OutlinedTextField(form.remoteHostKey, { v -> onChange { it.copy(remoteHostKey = v) } }, label = { Text("Host key *") }, minLines = 2, maxLines = 5,
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), isError = err("remote_host_key") != null,
                        supportingText = { Text(err("remote_host_key") ?: "Identifies the server, so nobody can pose as it") }, modifier = Modifier.fillMaxWidth())
                    OutlinedButton(onClick = onScan, enabled = form.host.isNotBlank() && !scanning) {
                        if (scanning) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Icon(Icons.Rounded.AutoAwesome, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp)); Text("Discover host key")
                    }
                    if (form.remoteHostKey.isNotBlank()) Text("Check that this matches the server's key before saving.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                OutlinedTextField(form.username, { v -> onChange { it.copy(username = v.trim()) } }, label = { Text("SSH user *") }, singleLine = true,
                    isError = err("username") != null, supportingText = { Text(err("username") ?: "The user replication signs in as") }, modifier = Modifier.fillMaxWidth())
                if (semi) SwitchRow("Allow sudo for zfs", form.sudo, "For a non-root user: lets it run zfs with sudo on the other TrueNAS") { v -> onChange { it.copy(sudo = v) } }
                OutlinedTextField(form.connectTimeout, { v -> onChange { it.copy(connectTimeout = v.filter(Char::isDigit).take(4)) } }, label = { Text("Connect timeout (s)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), isError = err("connect_timeout") != null,
                    supportingText = err("connect_timeout")?.let { { Text(it) } }, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

// --- key pairs ---

data class KeyPairForm(val name: String = "", val privateKey: String = "", val publicKey: String = "")

class KeyPairEditorViewModel(private val c: AppContainer, private val id: Int?) : ViewModel() {
    private val _state = MutableStateFlow<UiState<KeyPairForm>>(UiState.Loading)
    val state = _state.asStateFlow()
    private val _form = MutableStateFlow(KeyPairForm())
    val form = _form.asStateFlow()
    private val _existing = MutableStateFlow<SshKeyPair?>(null)
    val existing = _existing.asStateFlow()
    private val _error = MutableStateFlow<Map<String, String>>(emptyMap())
    val errors = _error.asStateFlow()
    private val _general = MutableStateFlow<String?>(null)
    val general = _general.asStateFlow()
    private val _saving = MutableStateFlow(false)
    val saving = _saving.asStateFlow()
    private val _saved = Channel<Unit>(Channel.BUFFERED)
    val saved = _saved.receiveAsFlow()

    init { load() }

    fun load() = viewModelScope.launch {
        _state.value = UiState.Loading
        try {
            val k = id?.let { i -> c.repository.call { api -> ReplicationApi(api).keyPairs().firstOrNull { it.id == i } } ?: throw IllegalStateException("This key pair no longer exists") }
            _existing.value = k
            val f = KeyPairForm(name = k?.name.orEmpty(), privateKey = k?.privateKey.orEmpty(), publicKey = k?.publicKey.orEmpty())
            _form.value = f
            _state.value = UiState.Success(f)
        } catch (e: Throwable) {
            _state.value = UiState.Error(e.userMessage(), e)
        }
    }

    fun edit(f: (KeyPairForm) -> KeyPairForm) { _form.value = f(_form.value); _error.value = emptyMap(); _general.value = null }

    fun generate() {
        if (_saving.value) return
        _saving.value = true
        viewModelScope.launch {
            try {
                val (priv, pub) = c.repository.call { ReplicationApi(it).generateKeyPair() }
                edit { it.copy(privateKey = priv, publicKey = pub) }
            } catch (e: Throwable) {
                _general.value = e.userMessage()
            } finally {
                _saving.value = false
            }
        }
    }

    fun save() {
        val f = _form.value
        if (keyPairErrors(f, id != null).isNotEmpty() || _saving.value) return
        _saving.value = true
        viewModelScope.launch {
            try {
                c.repository.call { api ->
                    val a = ReplicationApi(api)
                    if (id == null) a.createKeyPair(f.name, f.privateKey, f.publicKey) else a.renameCredential(id, f.name)
                }
                _saved.trySend(Unit)
            } catch (e: Throwable) {
                val (mine, general) = splitFieldErrors(e, setOf("name", "private_key", "public_key"))
                _error.value = mine; _general.value = general
            } finally {
                _saving.value = false
            }
        }
    }
}

fun keyPairErrors(f: KeyPairForm, editing: Boolean): Map<String, String> = buildMap {
    if (f.name.isBlank()) put("name", "Give the key pair a name")
    if (!editing) {
        ReplicationLogic.privateKeyError(f.privateKey, f.publicKey)?.let { put("private_key", it) }
        ReplicationLogic.publicKeyError(f.publicKey)?.let { put("public_key", it) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeyPairEditorScreen(id: Int?, onBack: () -> Unit) {
    app.truenascompanion.ui.components.SecureWindowEffect() // 1.7.1 (M-4): private keys, passwords
    val vm = appViewModel(key = "ssh-key:${id ?: "new"}") { KeyPairEditorViewModel(it, id) }
    val state by vm.state.collectAsStateWithLifecycle()
    val form by vm.form.collectAsStateWithLifecycle()
    val existing by vm.existing.collectAsStateWithLifecycle()
    val serverErrors by vm.errors.collectAsStateWithLifecycle()
    val general by vm.general.collectAsStateWithLifecycle()
    val saving by vm.saving.collectAsStateWithLifecycle()
    LaunchedEffect(vm) { vm.saved.collect { onBack() } }
    val original = (state as? UiState.Success)?.data
    val dirty = original != null && original != form
    val local = keyPairErrors(form, id != null)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (id == null) "New key pair" else "Key pair") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
                actions = {
                    if (saving) CircularProgressIndicator(Modifier.size(22.dp).padding(end = 4.dp), strokeWidth = 2.dp)
                    TextButton(onClick = vm::save, enabled = (dirty || id == null) && !saving && local.isEmpty()) { Text("Save") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            when (val s = state) {
                UiState.Loading -> SkeletonList(2, 150.dp)
                is UiState.Error -> ScrollableErrorState(s.message, s.isLoginRequired) { vm.load() }
                is UiState.Success -> KeyPairContent(form, existing, local + serverErrors, general, showErrors = dirty || serverErrors.isNotEmpty(), saving, vm::edit, vm::generate)
            }
        }
    }
}

/** Stateless key pair form: new pairs take a pasted or generated key; existing ones can be renamed, the keys are read-only. */
@Composable
fun KeyPairContent(
    form: KeyPairForm,
    existing: SshKeyPair?,
    errors: Map<String, String>,
    general: String?,
    showErrors: Boolean,
    busy: Boolean,
    onChange: ((KeyPairForm) -> KeyPairForm) -> Unit,
    onGenerate: () -> Unit,
) {
    fun err(k: String) = errors[k]?.takeIf { showErrors }
    @Suppress("DEPRECATION") val clipboard = LocalClipboardManager.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(16.dp).testTag("page"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        general?.let { InfoBanner(it, health = Health.CRITICAL) }
        ElevatedSection(contentPadding = 14.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(form.name, { v -> onChange { it.copy(name = v) } }, label = { Text("Name *") }, singleLine = true,
                    isError = err("name") != null, supportingText = err("name")?.let { { Text(it) } }, modifier = Modifier.fillMaxWidth())
                if (existing == null) {
                    FilledTonalButton(onClick = onGenerate, enabled = !busy) {
                        Icon(Icons.Rounded.AutoAwesome, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Generate a new key pair")
                    }
                    Text("Or paste an existing key without a passphrase. The public key is filled in from the private key if you leave it empty.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        SectionTitle("Private key")
        ElevatedSection(contentPadding = 14.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (existing?.redacted == true) Text("TrueNAS didn't send the keys to your account.", style = MaterialTheme.typography.bodyMedium)
                else SecretTextField(form.privateKey, { v -> if (existing == null) onChange { it.copy(privateKey = v) } }, "Private key", singleLine = false,
                    isError = err("private_key") != null,
                    supporting = err("private_key") ?: if (existing == null) "Stays on the NAS. Never share it." else "Stored on the NAS. It can't be changed; add a new key pair instead.")
            }
        }
        SectionTitle("Public key")
        ElevatedSection(contentPadding = 14.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (existing == null) OutlinedTextField(form.publicKey, { v -> onChange { it.copy(publicKey = v) } }, label = { Text("Public key") }, minLines = 2, maxLines = 6,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), isError = err("public_key") != null,
                    supportingText = { Text(err("public_key") ?: "ssh-ed25519 AAAA… or ssh-rsa AAAA…") }, modifier = Modifier.fillMaxWidth())
                else form.publicKey.takeIf { it.isNotBlank() }?.let { pub ->
                    CommandBox(pub.trim(), maxLines = 8)
                    TextButton(onClick = { clipboard.setText(AnnotatedString(pub.trim())) }) {
                        Icon(Icons.Rounded.ContentCopy, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Copy public key")
                    }
                    Text("Add it to ~/.ssh/authorized_keys of the user on the other system.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(2.dp))
            }
        }
    }
}

