package app.truenascompanion.ui.virt

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.truenascompanion.AppContainer
import app.truenascompanion.data.api.userMessage
import app.truenascompanion.data.model.FsEntry
import app.truenascompanion.data.model.VmCreateRequest
import app.truenascompanion.ui.appViewModel
import app.truenascompanion.ui.components.ElevatedSection
import app.truenascompanion.ui.components.GlowButton
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale

/** "New VM" form state and pure validation (unit tested). */
data class VmFormState(
    val name: String = "",
    val description: String = "",
    val vcpus: String = "2",
    val memoryGiB: String = "4",
    val bootloader: String = "UEFI",
    val autostart: Boolean = true,
    val createDisk: Boolean = true,
    val diskParent: String? = null,
    val diskSizeGiB: String = "32",
    val isoPath: String = "",
    val nicAttach: String? = null,
    val display: Boolean = true,
    val displayPassword: String = "",
)

object VmForm {
    private val namePattern = Regex("^[A-Za-z0-9_]+$")

    fun parseGiB(text: String): Long? {
        val g = text.trim().replace(',', '.').toDoubleOrNull() ?: return null
        val mb = Math.round(g * 1024)
        return mb.takeIf { it >= 20 }
    }

    fun gibText(mb: Long): String = if (mb % 1024 == 0L) (mb / 1024).toString() else String.format(Locale.US, "%.2f", mb / 1024.0).trimEnd('0').trimEnd('.')

    fun errors(s: VmFormState, existingNames: Collection<String> = emptyList()): Map<String, String> = buildMap {
        when {
            s.name.isBlank() -> put("name", "Enter a name")
            !namePattern.matches(s.name) -> put("name", "Letters, digits and underscores only (TrueNAS rule)")
            existingNames.any { it.equals(s.name, ignoreCase = true) } -> put("name", "A VM with this name already exists")
        }
        if ((s.vcpus.toIntOrNull() ?: 0) < 1) put("vcpus", "At least 1")
        if (parseGiB(s.memoryGiB) == null) put("memory", "At least 0.02 GiB (20 MiB)")
        if (s.createDisk) {
            if (s.diskParent.isNullOrBlank()) put("diskParent", "Choose where to create the disk")
            if ((s.diskSizeGiB.toIntOrNull() ?: 0) < 1) put("diskSize", "At least 1 GiB")
        }
        if (s.isoPath.isNotBlank() && !s.isoPath.startsWith("/mnt/")) put("iso", "Pick an image under /mnt")
        if (s.display && s.displayPassword.isBlank()) put("displayPassword", "TrueNAS requires a display password")
    }

    fun request(s: VmFormState) = VmCreateRequest(
        name = s.name.trim(), description = s.description.trim(), vcpus = s.vcpus.toInt(), cores = 1, threads = 1,
        memoryMb = parseGiB(s.memoryGiB)!!, bootloader = s.bootloader, autostart = s.autostart,
        diskParent = if (s.createDisk) s.diskParent else null, diskSizeGiB = s.diskSizeGiB.toIntOrNull() ?: 0,
        isoPath = s.isoPath.trim().ifBlank { null }, nicAttach = s.nicAttach, displayPassword = if (s.display) s.displayPassword else null,
    )

    fun isImage(name: String) = name.lowercase().let { it.endsWith(".iso") || it.endsWith(".img") }
}

data class VmCreateUi(
    val form: VmFormState = VmFormState(),
    val parents: List<String> = emptyList(),
    val nics: List<String> = emptyList(),
    val existing: List<String> = emptyList(),
    val loading: Boolean = true,
    val submitting: Boolean = false,
    val showErrors: Boolean = false,
    val browsePath: String? = null,
    val browseEntries: List<FsEntry>? = null,
    val browseError: String? = null,
)

class VmCreateViewModel(private val c: AppContainer) : ViewModel() {
    private val _ui = MutableStateFlow(VmCreateUi())
    val ui = _ui.asStateFlow()
    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()
    private val _created = MutableStateFlow<Int?>(null)
    val created = _created.asStateFlow()

    init {
        viewModelScope.launch {
            val parents = runCatching { c.repository.call { it.zvolParents() } }.getOrElse { emptyList() }
            val nics = runCatching { c.repository.call { it.nicAttachChoices() } }.getOrElse { emptyList() }
            val existing = runCatching { c.repository.call { it.vms() }.map { it.name } }.getOrElse { emptyList() }
            _ui.update { u -> u.copy(parents = parents, nics = nics, existing = existing, loading = false,
                form = u.form.copy(diskParent = u.form.diskParent ?: parents.firstOrNull(), nicAttach = u.form.nicAttach ?: nics.firstOrNull { !it.startsWith("lo") })) }
        }
    }

    fun change(f: (VmFormState) -> VmFormState) = _ui.update { it.copy(form = f(it.form)) }

    fun browse(path: String) = viewModelScope.launch {
        _ui.update { it.copy(browsePath = path, browseEntries = null, browseError = null) }
        try {
            val list = c.repository.call { it.listDir(path) }.filter { it.isDirectory || VmForm.isImage(it.name) }
                .sortedWith(compareBy<FsEntry>({ !it.isDirectory }, { it.name.lowercase() }))
            _ui.update { it.copy(browseEntries = list) }
        } catch (e: Throwable) { _ui.update { it.copy(browseEntries = emptyList(), browseError = e.userMessage()) } }
    }

    fun closeBrowser() = _ui.update { it.copy(browsePath = null, browseEntries = null, browseError = null) }

    fun submit() {
        val u = _ui.value
        if (VmForm.errors(u.form, u.existing).isNotEmpty()) { _ui.update { it.copy(showErrors = true) }; return }
        viewModelScope.launch {
            _ui.update { it.copy(submitting = true) }
            try { _created.value = c.repository.call { it.vmCreate(VmForm.request(u.form)) } }
            catch (e: Throwable) { _messages.trySend(e.userMessage()) }
            _ui.update { it.copy(submitting = false) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VmCreateScreen(onBack: () -> Unit, onCreated: (Int) -> Unit) {
    val vm = appViewModel { VmCreateViewModel(it) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val created by vm.created.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }
    LaunchedEffect(created) { created?.let(onCreated) }
    Scaffold(
        topBar = { TopAppBar(title = { Text("New virtual machine") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } }) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        VmCreateContent(ui, onChange = vm::change, onBrowse = { vm.browse(ui.form.isoPath.substringBeforeLast('/').ifBlank { "/mnt" }.takeIf { it.startsWith("/mnt") } ?: "/mnt") },
            onSubmit = vm::submit, modifier = Modifier.padding(padding))
    }
    if (ui.browsePath != null) IsoBrowserDialog(ui, onOpen = vm::browse, onPick = { p -> vm.change { it.copy(isoPath = p) }; vm.closeBrowser() }, onDismiss = vm::closeBrowser)
}

/** Stateless form (also rendered by the screenshot tests). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VmCreateContent(ui: VmCreateUi, onChange: ((VmFormState) -> VmFormState) -> Unit, onBrowse: () -> Unit, onSubmit: () -> Unit, modifier: Modifier = Modifier) {
    val f = ui.form
    val errors = if (ui.showErrors) VmForm.errors(f, ui.existing) else emptyMap()
    Column(modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        ElevatedSection(contentPadding = 14.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Basics", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(f.name, { v -> onChange { it.copy(name = v) } }, label = { Text("Name *") }, singleLine = true, isError = "name" in errors,
                    supportingText = { Text(errors["name"] ?: "Letters, digits and underscores") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(f.description, { v -> onChange { it.copy(description = v) } }, label = { Text("Description") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(f.vcpus, { v -> onChange { it.copy(vcpus = v.filter(Char::isDigit).take(3)) } }, label = { Text("vCPUs") }, singleLine = true,
                        isError = "vcpus" in errors, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
                    OutlinedTextField(f.memoryGiB, { v -> onChange { it.copy(memoryGiB = v) } }, label = { Text("Memory (GiB)") }, singleLine = true,
                        isError = "memory" in errors, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f))
                }
                errors["memory"]?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                Text("Boot", style = MaterialTheme.typography.labelLarge)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    listOf("UEFI" to "UEFI", "UEFI_CSM" to "Legacy BIOS").forEachIndexed { i, (k, label) ->
                        SegmentedButton(selected = f.bootloader == k, onClick = { onChange { it.copy(bootloader = k) } }, shape = SegmentedButtonDefaults.itemShape(i, 2), icon = {}, label = { Text(label, maxLines = 1) })
                    }
                }
                SwitchRow("Start when the NAS boots", f.autostart) { v -> onChange { it.copy(autostart = v) } }
            }
        }
        ElevatedSection(contentPadding = 14.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Storage", style = MaterialTheme.typography.titleMedium)
                SwitchRow("Create a new disk (zvol)", f.createDisk) { v -> onChange { it.copy(createDisk = v) } }
                if (f.createDisk) {
                    Choice("Create it in", f.diskParent, ui.parents, errors["diskParent"], empty = if (ui.loading) "Loading…" else "No datasets found") { v -> onChange { it.copy(diskParent = v) } }
                    OutlinedTextField(f.diskSizeGiB, { v -> onChange { it.copy(diskSizeGiB = v.filter(Char::isDigit).take(6)) } }, label = { Text("Size (GiB)") }, singleLine = true,
                        isError = "diskSize" in errors, supportingText = { Text(errors["diskSize"] ?: f.diskParent?.let { p -> "Creates $p/${f.name.ifBlank { "name" }}-disk0 (VirtIO)" } ?: "") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                }
                OutlinedTextField(f.isoPath, { v -> onChange { it.copy(isoPath = v) } }, label = { Text("Installer image (ISO)") }, singleLine = true,
                    isError = "iso" in errors, supportingText = { Text(errors["iso"] ?: "Optional. Attached as a CD-ROM") },
                    leadingIcon = { Icon(Icons.Rounded.Album, null) },
                    trailingIcon = { IconButton(onClick = onBrowse) { Icon(Icons.Rounded.FolderOpen, "Browse") } },
                    modifier = Modifier.fillMaxWidth())
            }
        }
        ElevatedSection(contentPadding = 14.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Network & display", style = MaterialTheme.typography.titleMedium)
                Choice("Network interface", f.nicAttach, listOf("") + ui.nics, null, label = { if (it.isBlank()) "No network" else it }) { v -> onChange { it.copy(nicAttach = v.ifBlank { null }) } }
                SwitchRow("Web display (SPICE)", f.display) { v -> onChange { it.copy(display = v) } }
                if (f.display) {
                    var reveal by remember { mutableStateOf(false) }
                    OutlinedTextField(f.displayPassword, { v -> onChange { it.copy(displayPassword = v) } }, label = { Text("Display password *") }, singleLine = true,
                        isError = "displayPassword" in errors, supportingText = { Text(errors["displayPassword"] ?: "Asked when you open the display") },
                        visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = { IconButton(onClick = { reveal = !reveal }) { Icon(if (reveal) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, if (reveal) "Hide" else "Show") } },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
                }
            }
        }
        GlowButton(onClick = onSubmit, enabled = !ui.submitting && !ui.loading, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
            if (ui.submitting) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(10.dp)) }
            Text(if (ui.submitting) "Creating…" else "Create VM", maxLines = 1)
        }
        Text("The VM is created stopped. Start it from its page; more devices (PCI/USB passthrough) can be added in the TrueNAS web UI.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }, verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Choice(title: String, value: String?, options: List<String>, error: String?, empty: String = "—", label: (String) -> String = { it }, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = open, onExpandedChange = { open = it && options.isNotEmpty() }) {
        OutlinedTextField(
            value = value?.let(label) ?: if (options.isEmpty()) empty else label(""), onValueChange = {}, readOnly = true, singleLine = true,
            label = { Text(title) }, isError = error != null, supportingText = error?.let { { Text(it) } },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(open) },
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
        )
        androidx.compose.material3.DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { o -> DropdownMenuItem(text = { Text(label(o), maxLines = 1, overflow = TextOverflow.Ellipsis) }, onClick = { open = false; onPick(o) }) }
        }
    }
}

@Composable
fun IsoBrowserDialog(ui: VmCreateUi, onOpen: (String) -> Unit, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val path = ui.browsePath ?: "/mnt"
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose an image", maxLines = 1) },
        text = {
            Column {
                Text(path, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.padding(top = 6.dp))
                val entries = ui.browseEntries
                when {
                    entries == null -> CircularProgressIndicator(Modifier.padding(16.dp).size(24.dp))
                    else -> LazyColumn(Modifier.heightIn(max = 360.dp)) {
                        if (path != "/mnt") item { BrowserRow(Icons.Rounded.Folder, "..") { onOpen(path.substringBeforeLast('/').ifBlank { "/mnt" }) } }
                        items(entries, key = { it.path }) { e ->
                            BrowserRow(if (e.isDirectory) Icons.Rounded.Folder else Icons.Rounded.Album, e.name) { if (e.isDirectory) onOpen(e.path) else onPick(e.path) }
                        }
                        if (entries.isEmpty()) item {
                            Text(ui.browseError ?: "No folders or .iso/.img files here", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 12.dp))
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun BrowserRow(icon: androidx.compose.ui.graphics.vector.ImageVector, name: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 44.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(12.dp))
        Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
