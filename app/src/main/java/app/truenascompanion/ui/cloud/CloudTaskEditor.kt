package app.truenascompanion.ui.cloud

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
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import app.truenascompanion.ui.components.Scaffold
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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.truenascompanion.AppContainer
import app.truenascompanion.data.api.CloudSyncApi
import app.truenascompanion.data.api.RemoteEntry
import app.truenascompanion.data.api.userMessage
import app.truenascompanion.data.cloud.BwRow
import app.truenascompanion.data.cloud.CloudCredential
import app.truenascompanion.data.cloud.CloudDirection
import app.truenascompanion.data.cloud.CloudProvider
import app.truenascompanion.data.cloud.CloudProviders
import app.truenascompanion.data.cloud.CloudSyncForm
import app.truenascompanion.data.cloud.CloudSyncLogic
import app.truenascompanion.data.cloud.TransferMode
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
import app.truenascompanion.ui.tasks.CronSchedulePicker
import app.truenascompanion.ui.tasks.splitFieldErrors
import app.truenascompanion.util.Format
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The bucket list or a folder listing on the remote, shown in a dialog while choosing where the data goes. */
data class RemoteBrowser(
    val buckets: Boolean,
    val bucket: String? = null,
    val folder: String = "",
    val entries: List<RemoteEntry> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
) {
    val title: String get() = if (buckets) "Choose" else CloudSyncLogic.remoteText(bucket, folder)
}

data class TaskEditorRefs(val credentials: List<CloudCredential> = emptyList(), val providers: List<CloudProvider> = emptyList()) {
    fun credential(id: Int?) = credentials.firstOrNull { it.id == id }
    fun provider(credentialId: Int?): CloudProvider? = credential(credentialId)?.let { c -> providers.firstOrNull { it.name == c.type } }
}

class CloudTaskEditorViewModel(private val c: AppContainer, private val id: Int?) : ViewModel() {
    private val _state = MutableStateFlow<UiState<CloudSyncForm>>(UiState.Loading)
    val state = _state.asStateFlow()
    private val _form = MutableStateFlow(CloudSyncForm())
    val form = _form.asStateFlow()
    private val _refs = MutableStateFlow(TaskEditorRefs())
    val refs = _refs.asStateFlow()
    private val _serverErrors = MutableStateFlow<Map<String, String>>(emptyMap())
    val serverErrors = _serverErrors.asStateFlow()
    private val _generalError = MutableStateFlow<String?>(null)
    val generalError = _generalError.asStateFlow()
    private val _saving = MutableStateFlow(false)
    val saving = _saving.asStateFlow()
    private val _saved = Channel<String>(Channel.BUFFERED)
    val saved = _saved.receiveAsFlow()
    private val _browser = MutableStateFlow<RemoteBrowser?>(null)
    val browser = _browser.asStateFlow()

    init { load() }

    fun load() = viewModelScope.launch {
        _state.value = UiState.Loading
        try {
            val (refs, task) = c.repository.call { api ->
                val a = CloudSyncApi(api)
                TaskEditorRefs(a.credentials(), runCatching { a.providers() }.getOrDefault(emptyList())) to
                    id?.let { a.task(it) ?: throw IllegalStateException("This cloud sync task no longer exists") }
            }
            _refs.value = refs
            val f = task?.let(CloudSyncLogic::form) ?: CloudSyncForm(credentialId = refs.credentials.singleOrNull()?.id)
            _form.value = f
            _state.value = UiState.Success(f)
        } catch (e: Throwable) {
            _state.value = UiState.Error(e.userMessage(), e)
        }
    }

    /** Reloads the credential list (back from adding one) without touching the form. */
    fun reloadCredentials() = viewModelScope.launch {
        runCatching { c.repository.call { CloudSyncApi(it).credentials() } }.getOrNull()?.let { list ->
            val before = _refs.value.credentials.map { it.id }.toSet()
            _refs.update { it.copy(credentials = list) }
            val added = list.firstOrNull { it.id !in before }
            if (added != null && _form.value.credentialId == null) edit { it.copy(credentialId = added.id) }
        }
    }

    fun edit(f: (CloudSyncForm) -> CloudSyncForm) {
        val before = _form.value
        var after = f(before)
        if (after.credentialId != before.credentialId) {
            val oldType = _refs.value.credential(before.credentialId)?.type
            val newType = _refs.value.credential(after.credentialId)?.type
            if (oldType != newType) after = after.copy(bucket = "", folder = "", originalAttributes = kotlinx.serialization.json.JsonObject(emptyMap()), chunkSize = "", region = "", storageClass = "", s3Encryption = false)
        }
        _form.value = after
        val touched = buildSet {
            if (before.path != after.path) add("path"); if (before.credentialId != after.credentialId) add("credentials")
            if (before.bucket != after.bucket) add("bucket"); if (before.folder != after.folder || before.direction != after.direction) add("folder")
            if (before.schedule != after.schedule) add("schedule"); if (before.transfers != after.transfers) add("transfers")
            if (before.bwlimit != after.bwlimit) add("bwlimit"); if (before.encryptionPassword != after.encryptionPassword || before.encryption != after.encryption) add("encryption_password")
            if (before.snapshot != after.snapshot || before.direction != after.direction || before.mode != after.mode) add("snapshot")
            if (before.chunkSize != after.chunkSize) add("chunk_size"); if (before.description != after.description) add("description")
            if (before.direction != after.direction) add("direction"); if (before.preScript != after.preScript) add("pre_script"); if (before.postScript != after.postScript) add("post_script")
        }
        if (touched.isNotEmpty()) _serverErrors.value = _serverErrors.value - touched
        _generalError.value = null
    }

    fun save() {
        val f = _form.value
        val p = _refs.value.provider(f.credentialId)
        if (CloudSyncLogic.errors(f, p).isNotEmpty() || _saving.value) return
        _saving.value = true
        viewModelScope.launch {
            try {
                val body = CloudSyncLogic.taskJson(f, p, update = id != null)
                c.repository.call { api -> CloudSyncApi(api).let { if (id == null) it.createTask(body) else it.updateTask(id, body) } }
                _saved.trySend(if (id == null) "Cloud sync task created" else "Cloud sync task saved")
            } catch (e: Throwable) {
                val (mine, general) = splitFieldErrors(e, CloudSyncLogic.FIELDS, CloudSyncLogic.FIELD_ALIASES)
                _serverErrors.value = mine
                _generalError.value = general
            } finally {
                _saving.value = false
            }
        }
    }

    // --- remote browser ---

    fun browseBuckets() {
        val cred = _form.value.credentialId ?: return
        _browser.value = RemoteBrowser(buckets = true)
        viewModelScope.launch {
            val r = try { RemoteBrowser(buckets = true, entries = c.repository.call { CloudSyncApi(it).listBuckets(cred) }, loading = false) }
            catch (e: Throwable) { RemoteBrowser(buckets = true, loading = false, error = e.userMessage()) }
            _browser.update { b -> if (b?.buckets == true) r else b }
        }
    }

    fun browseFolder(folder: String = _form.value.folder.trim().trim('/')) {
        val f = _form.value
        val cred = f.credentialId ?: return
        val p = _refs.value.provider(cred)
        val bucket = f.bucket.trim().takeIf { p?.buckets == true || (p == null && it.isNotEmpty()) }
        _browser.value = RemoteBrowser(buckets = false, bucket = bucket, folder = folder)
        viewModelScope.launch {
            val r = try { RemoteBrowser(false, bucket, folder, c.repository.call { CloudSyncApi(it).listDirectory(cred, bucket, folder) }, loading = false) }
            catch (e: Throwable) { RemoteBrowser(false, bucket, folder, loading = false, error = e.userMessage()) }
            _browser.update { b -> if (b != null && !b.buckets && b.folder == folder) r else b }
        }
    }

    fun openEntry(e: RemoteEntry) {
        val b = _browser.value ?: return
        if (b.buckets) { closeBrowser(); edit { it.copy(bucket = e.name) }; return }
        if (e.isDir) browseFolder(listOf(b.folder.trim('/'), e.name).filter { it.isNotEmpty() }.joinToString("/"))
    }

    fun browseUp() {
        val b = _browser.value ?: return
        if (!b.buckets && b.folder.isNotEmpty()) browseFolder(b.folder.substringBeforeLast('/', ""))
    }

    fun chooseFolder() {
        val b = _browser.value ?: return
        closeBrowser()
        edit { it.copy(folder = if (b.folder.isEmpty()) "/" else "/" + b.folder) }
    }

    fun closeBrowser() { _browser.value = null }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CloudTaskEditorScreen(
    id: Int?,
    pickedPath: String?,
    onPickConsumed: () -> Unit,
    onBrowseLocal: (String?) -> Unit,
    onAddCredential: () -> Unit,
    onBack: () -> Unit,
) {
    val vm = appViewModel(key = "cloud-task:${id ?: "new"}") { CloudTaskEditorViewModel(it, id) }
    val state by vm.state.collectAsStateWithLifecycle()
    val form by vm.form.collectAsStateWithLifecycle()
    val refs by vm.refs.collectAsStateWithLifecycle()
    val serverErrors by vm.serverErrors.collectAsStateWithLifecycle()
    val generalError by vm.generalError.collectAsStateWithLifecycle()
    val saving by vm.saving.collectAsStateWithLifecycle()
    val browser by vm.browser.collectAsStateWithLifecycle()
    LaunchedEffect(vm) { vm.saved.collect { onBack() } }
    LaunchedEffect(pickedPath) { if (pickedPath != null) { vm.edit { it.copy(path = pickedPath) }; onPickConsumed() } }
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) { if (state is UiState.Success) vm.reloadCredentials() }
    val original = (state as? UiState.Success)?.data
    val dirty = original != null && original != form
    val provider = refs.provider(form.credentialId)
    val local = CloudSyncLogic.errors(form, provider)
    var confirmDiscard by remember { mutableStateOf(false) }
    BackHandler(enabled = dirty) { confirmDiscard = true }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (id == null) "New cloud sync task" else "Edit cloud sync task") },
                navigationIcon = { IconButton(onClick = { if (dirty) confirmDiscard = true else onBack() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
                actions = {
                    if (saving) CircularProgressIndicator(Modifier.size(22.dp).padding(end = 4.dp), strokeWidth = 2.dp)
                    TextButton(onClick = vm::save, enabled = (dirty || id == null) && !saving && local.isEmpty()) { Text("Save") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            when (val s = state) {
                UiState.Loading -> SkeletonList(4, 150.dp)
                is UiState.Error -> ScrollableErrorState(s.message, s.isLoginRequired) { vm.load() }
                is UiState.Success -> CloudTaskEditorContent(
                    form, refs, local + serverErrors, generalError, showErrors = dirty || serverErrors.isNotEmpty(),
                    onChange = vm::edit,
                    onBrowseLocal = { onBrowseLocal(form.path.takeIf { it.startsWith("/mnt/") }) },
                    onBrowseBuckets = vm::browseBuckets,
                    onBrowseFolder = { vm.browseFolder() },
                    onAddCredential = onAddCredential,
                )
            }
        }
    }
    browser?.let { b ->
        RemoteBrowserDialog(b, provider?.bucketTitle ?: "Bucket", onOpen = vm::openEntry, onUp = vm::browseUp, onChoose = vm::chooseFolder, onDismiss = vm::closeBrowser,
            onRetry = { if (b.buckets) vm.browseBuckets() else vm.browseFolder(b.folder) })
    }
    if (confirmDiscard) ConfirmDialog(
        title = "Discard changes?", text = "Your changes to this cloud sync task haven't been saved.",
        confirmLabel = "Discard", destructive = true, requireAuth = false,
        onConfirm = { confirmDiscard = false; onBack() }, onDismiss = { confirmDiscard = false },
    )
}

@Composable
fun RemoteBrowserDialog(
    b: RemoteBrowser,
    bucketTitle: String,
    onOpen: (RemoteEntry) -> Unit,
    onUp: () -> Unit,
    onChoose: () -> Unit,
    onDismiss: () -> Unit,
    onRetry: () -> Unit = {},
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (b.buckets) "Choose a ${bucketTitle.lowercase()}" else "Choose a folder", maxLines = 1) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (!b.buckets) Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onUp, enabled = b.folder.isNotEmpty()) { Icon(Icons.Rounded.ArrowUpward, "Up one folder") }
                    Text(b.title, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                when {
                    b.loading -> Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp); Spacer(Modifier.width(12.dp)); Text("Asking the provider…")
                    }
                    b.error != null -> Column {
                        Text(b.error.lineSequence().filter { it.isNotBlank() }.take(4).joinToString("\n"), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = onRetry) { Text("Try again") }
                    }
                    b.entries.isEmpty() -> Text(if (b.buckets) "No ${bucketTitle.lowercase()}s yet. Create one at the provider first." else "This folder is empty.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else -> LazyColumn(Modifier.heightIn(max = 360.dp)) {
                        items(b.entries, key = { it.path }) { e ->
                            Row(
                                Modifier.fillMaxWidth().clickable(enabled = b.buckets || e.isDir) { onOpen(e) }.padding(vertical = 10.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(if (b.buckets) Icons.Rounded.Inventory2 else if (e.isDir) Icons.Rounded.Folder else Icons.AutoMirrored.Rounded.InsertDriveFile, null,
                                    tint = if (b.buckets || e.isDir) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(e.name, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        color = if (b.buckets || e.isDir) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                                    if (!e.isDir && e.size != null) Text(Format.bytes(e.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { if (!b.buckets) GlowButton(onClick = onChoose, enabled = !b.loading && b.error == null) { Text("Use this folder") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Stateless cloud sync task form (also used by the screenshot previews). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CloudTaskEditorContent(
    form: CloudSyncForm,
    refs: TaskEditorRefs,
    errors: Map<String, String>,
    generalError: String?,
    showErrors: Boolean = true,
    startAdvanced: Boolean = false,
    onChange: ((CloudSyncForm) -> CloudSyncForm) -> Unit,
    onBrowseLocal: () -> Unit = {},
    onBrowseBuckets: () -> Unit = {},
    onBrowseFolder: () -> Unit = {},
    onAddCredential: () -> Unit = {},
) {
    fun err(k: String) = errors[k]?.takeIf { showErrors || k == "description" || k == "snapshot" }
    val cred = refs.credential(form.credentialId)
    val p = refs.provider(form.credentialId)
    var scripts by rememberSaveable { mutableStateOf(startAdvanced || form.preScript.isNotBlank() || form.postScript.isNotBlank()) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(16.dp).testTag("page"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        generalError?.let { InfoBanner(it, health = Health.CRITICAL) }
        SectionTitle("What to do")
        ElevatedSection(contentPadding = 14.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    form.description, { v -> onChange { it.copy(description = v) } }, label = { Text("Description") }, singleLine = true,
                    placeholder = { Text("e.g. Photos to Backblaze") }, isError = err("description") != null,
                    supportingText = err("description")?.let { { Text(it) } }, modifier = Modifier.fillMaxWidth(),
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CloudDirection.entries.forEach { d ->
                        FilterChip(selected = form.direction == d, onClick = { onChange { it.copy(direction = d) } },
                            label = { Text(if (d == CloudDirection.PUSH) "Push (NAS → cloud)" else "Pull (cloud → NAS)") },
                            leadingIcon = { Icon(if (d == CloudDirection.PUSH) Icons.Rounded.CloudUpload else Icons.Rounded.CloudDownload, null, Modifier.size(18.dp)) })
                    }
                }
                err("direction")?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TransferMode.entries.forEach { m -> FilterChip(selected = form.mode == m, onClick = { onChange { it.copy(mode = m) } }, label = { Text(m.label) }) }
                }
                Text(form.mode.describe(form.direction), style = MaterialTheme.typography.bodySmall,
                    color = if (form.mode == TransferMode.COPY) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
            }
        }
        SectionTitle("On the NAS")
        ElevatedSection(contentPadding = 14.dp) {
            OutlinedTextField(
                form.path, { v -> onChange { it.copy(path = v) } }, label = { Text("Folder *") }, singleLine = true,
                placeholder = { Text("/mnt/tank/photos") }, textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                isError = err("path") != null,
                supportingText = { Text(err("path") ?: if (form.direction == CloudDirection.PUSH) "What gets uploaded" else "Where the files are downloaded to") },
                trailingIcon = { IconButton(onClick = onBrowseLocal) { Icon(Icons.Rounded.FolderOpen, "Browse the NAS") } },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        SectionTitle("In the cloud")
        ElevatedSection(contentPadding = 14.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (refs.credentials.isEmpty()) {
                    Text("There are no cloud credentials yet. Add one with the keys or sign-in token of your cloud account.", style = MaterialTheme.typography.bodyMedium)
                    OutlinedButton(onClick = onAddCredential) { Icon(Icons.Rounded.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Add credential") }
                } else {
                    PickField(
                        "Credential *", cred, refs.credentials, { c -> "${c.name} · ${refs.providers.firstOrNull { it.name == c.type }?.title ?: CloudProviders.title(c.type)}" },
                        onPick = { c -> onChange { it.copy(credentialId = c.id) } }, isError = err("credentials") != null,
                        supporting = err("credentials"), emptyText = "Choose a credential",
                    )
                    TextButton(onClick = onAddCredential) { Text("Add another credential") }
                }
                if (cred != null) {
                    if (p?.buckets == true || (p == null && form.bucket.isNotEmpty())) OutlinedTextField(
                        form.bucket, { v -> onChange { it.copy(bucket = v.trim()) } }, label = { Text("${p?.bucketTitle ?: "Bucket"} *") }, singleLine = true,
                        isError = err("bucket") != null, supportingText = err("bucket")?.let { { Text(it) } },
                        trailingIcon = { TextButton(onClick = onBrowseBuckets) { Text("Browse") } }, modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        form.folder, { v -> onChange { it.copy(folder = v) } }, label = { Text("Folder") }, singleLine = true,
                        placeholder = { Text("/backups/nas") }, textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                        isError = err("folder") != null,
                        supportingText = { Text(err("folder") ?: "Empty means the top level" + if (p?.buckets == true) " of the ${p.bucketTitle.lowercase()}" else "") },
                        trailingIcon = {
                            TextButton(onClick = onBrowseFolder, enabled = p?.buckets != true || form.bucket.isNotBlank()) { Text("Browse") }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    ProviderAttributes(form, p, ::err, onChange)
                }
            }
        }
        SectionTitle("Schedule")
        ElevatedSection(contentPadding = 14.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CronSchedulePicker(form.schedule, { s -> onChange { it.copy(schedule = s) } }, error = err("schedule")?.takeIf { it != "Fix the schedule" })
                SwitchRow("Enabled", form.enabled, "Turned off tasks only run when you start them") { v -> onChange { it.copy(enabled = v) } }
            }
        }
        SectionTitle("Options")
        ElevatedSection(contentPadding = 14.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (form.direction == CloudDirection.PUSH || form.snapshot) {
                    SwitchRow("Take a snapshot first", form.snapshot, err("snapshot") ?: "Uploads from a snapshot, so files don't change mid-upload. Not with Move or datasets that have child datasets.") { v -> onChange { it.copy(snapshot = v) } }
                }
                SwitchRow("Follow symlinks", form.followSymlinks, "Copy the files links point to") { v -> onChange { it.copy(followSymlinks = v) } }
                SwitchRow("Create empty folders", form.createEmptySrcDirs, "Also copy folders that have no files") { v -> onChange { it.copy(createEmptySrcDirs = v) } }
                OutlinedTextField(
                    form.transfers, { v -> onChange { it.copy(transfers = v.filter(Char::isDigit).take(3)) } }, label = { Text("Parallel transfers") }, singleLine = true,
                    placeholder = { Text("Default (4)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    isError = err("transfers") != null, supportingText = { Text(err("transfers") ?: "More is faster on fast links but uses more memory") },
                    modifier = Modifier.fillMaxWidth(),
                )
                BandwidthRows(form.bwlimit, err("bwlimit")) { rows -> onChange { it.copy(bwlimit = rows) } }
                OutlinedTextField(
                    form.exclude, { v -> onChange { it.copy(exclude = v) } }, label = { Text("Exclude") }, minLines = 2, maxLines = 6,
                    placeholder = { Text("*.tmp\n/cache/**") }, textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    isError = err("exclude") != null, supportingText = { Text(err("exclude") ?: "One rclone pattern per line") }, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    form.include, { v -> onChange { it.copy(include = v) } }, label = { Text("Include only") }, minLines = 2, maxLines = 6,
                    placeholder = { Text("/Documents/**") }, textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    isError = err("include") != null, supportingText = { Text(err("include") ?: "When set, everything else is skipped") }, modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        SectionTitle("Encryption")
        ElevatedSection(contentPadding = 14.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SwitchRow("Encrypt in the cloud", form.encryption, "rclone encrypts files before they leave the NAS") { v -> onChange { it.copy(encryption = v) } }
                if (form.encryption) {
                    SecretTextField(form.encryptionPassword, { v -> onChange { it.copy(encryptionPassword = v) } }, "Encryption password *",
                        isError = err("encryption_password") != null, hiddenByServer = form.secretsHidden && form.encryptionPassword.isEmpty(),
                        supporting = err("encryption_password") ?: "Without it the data can't be restored. Keep a copy somewhere safe.")
                    SecretTextField(form.encryptionSalt, { v -> onChange { it.copy(encryptionSalt = v) } }, "Salt (optional)",
                        hiddenByServer = form.secretsHidden && form.encryptionSalt.isEmpty(), supporting = "A second password, recommended")
                    SwitchRow("Encrypt file names", form.filenameEncryption, null) { v -> onChange { it.copy(filenameEncryption = v) } }
                }
            }
        }
        TextButton(onClick = { scripts = !scripts }) {
            Icon(if (scripts) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null); Spacer(Modifier.width(6.dp))
            Text(if (scripts) "Hide scripts" else "Scripts before and after")
        }
        if (scripts || err("pre_script") != null || err("post_script") != null) ElevatedSection(contentPadding = 14.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Shell commands that run as root on the NAS around each run. Only full admins can change them.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(
                    form.preScript, { v -> onChange { it.copy(preScript = v) } }, label = { Text("Before the run") }, minLines = 2, maxLines = 8,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace), isError = err("pre_script") != null,
                    supportingText = err("pre_script")?.let { { Text(it) } }, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    form.postScript, { v -> onChange { it.copy(postScript = v) } }, label = { Text("After a successful run") }, minLines = 2, maxLines = 8,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace), isError = err("post_script") != null,
                    supportingText = err("post_script")?.let { { Text(it) } }, modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun ProviderAttributes(form: CloudSyncForm, p: CloudProvider?, err: (String) -> String?, onChange: ((CloudSyncForm) -> CloudSyncForm) -> Unit) {
    p ?: return
    if (p.has("fast_list")) SwitchRow("Fast list", form.fastList, "Fewer requests on big folders; uses more memory") { v -> onChange { it.copy(fastList = v) } }
    if (p.has("region")) OutlinedTextField(
        form.region, { v -> onChange { it.copy(region = v.trim()) } }, label = { Text("Region") }, singleLine = true,
        supportingText = { Text(err("region") ?: "Leave empty to use the credential's region") }, isError = err("region") != null, modifier = Modifier.fillMaxWidth(),
    )
    if (p.has("encryption")) SwitchRow("Server-side encryption (AES-256)", form.s3Encryption, "Asks S3 to encrypt the stored objects") { v -> onChange { it.copy(s3Encryption = v) } }
    if (p.has("storage_class")) PickField(
        "Storage class", form.storageClass, CloudSyncLogic.STORAGE_CLASSES, { if (it.isEmpty()) "Default" else it.replace('_', ' ').lowercase().replaceFirstChar { c -> c.uppercase() } },
        onPick = { s -> onChange { it.copy(storageClass = s) } }, isError = err("storage_class") != null, supporting = err("storage_class"),
    )
    CloudSyncLogic.chunkLabel(p)?.let { label ->
        OutlinedTextField(
            form.chunkSize, { v -> onChange { it.copy(chunkSize = v.filter(Char::isDigit).take(4)) } }, label = { Text(label) }, singleLine = true,
            placeholder = { Text("Default (${CloudSyncLogic.defaultChunk(p)})") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            isError = err("chunk_size") != null, supportingText = err("chunk_size")?.let { { Text(it) } }, modifier = Modifier.fillMaxWidth(),
        )
    }
    if (p.has("acknowledge_abuse")) SwitchRow("Download flagged files", form.acknowledgeAbuse, "Allow files Google marked as malware or spam") { v -> onChange { it.copy(acknowledgeAbuse = v) } }
    if (p.has("bucket_policy_only")) SwitchRow("Bucket policy only", form.bucketPolicyOnly, "For buckets with uniform bucket-level access") { v -> onChange { it.copy(bucketPolicyOnly = v) } }
}

@Composable
private fun BandwidthRows(rows: List<BwRow>, error: String?, onChange: (List<BwRow>) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Bandwidth limit", style = MaterialTheme.typography.bodyLarge)
        Text(if (rows.isEmpty()) "Unlimited. Add a time to limit the speed from then on (e.g. 08:00 at 512 KiB/s, 23:00 unlimited)."
            else "Each limit applies from its time until the next one.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        rows.forEachIndexed { i, r ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(r.time, { v -> onChange(rows.toMutableList().also { it[i] = r.copy(time = v.take(5)) }) }, label = { Text("From") }, singleLine = true,
                    placeholder = { Text("08:00") }, modifier = Modifier.weight(1f))
                OutlinedTextField(r.limit, { v -> onChange(rows.toMutableList().also { it[i] = r.copy(limit = v.filter(Char::isDigit).take(9)) }) }, label = { Text("KiB/s") }, singleLine = true,
                    placeholder = { Text("Unlimited") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1.2f))
                IconButton(onClick = { onChange(rows.filterIndexed { j, _ -> j != i }) }) { Icon(Icons.Rounded.Close, "Remove limit") }
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        TextButton(onClick = { onChange(rows + BwRow()) }) { Icon(Icons.Rounded.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Add a limit") }
    }
}
