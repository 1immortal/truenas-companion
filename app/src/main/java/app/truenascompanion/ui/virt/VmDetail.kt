package app.truenascompanion.ui.virt

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DesktopWindows
import androidx.compose.material.icons.rounded.DeveloperBoard
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Lan
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Usb
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import app.truenascompanion.ui.components.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import app.truenascompanion.ui.components.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.truenascompanion.TrueNasApp
import app.truenascompanion.data.api.userMessage
import app.truenascompanion.data.model.VmDevice
import app.truenascompanion.data.model.VmInfo
import app.truenascompanion.data.model.VmState
import app.truenascompanion.ui.appViewModel
import app.truenascompanion.ui.components.ConfirmDialog
import app.truenascompanion.ui.components.ElevatedSection
import app.truenascompanion.ui.components.GlowButton
import app.truenascompanion.ui.components.ScrollableErrorState
import app.truenascompanion.ui.components.SkeletonList
import app.truenascompanion.ui.components.StatusChip
import app.truenascompanion.ui.components.UiState
import app.truenascompanion.ui.components.isLoginRequired
import app.truenascompanion.ui.lock.LocalDangerGuard
import app.truenascompanion.util.Format
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VmDetailScreen(id: Int, onBack: () -> Unit) {
    val vm = appViewModel(key = "vm-$id") { VirtViewModel(it, withContainers = false) }
    val state by vm.vms.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    vm.jobsLive.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val container = (context.applicationContext as TrueNasApp).container
    val scope = rememberCoroutineScope()
    var confirm by remember { mutableStateOf<VmAction?>(null) }
    var editing by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }
    val current = (state as? UiState.Success)?.data?.firstOrNull { it.id == id }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(current?.name ?: "Virtual machine", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (val s = state) {
                UiState.Loading -> SkeletonList(3, 120.dp)
                is UiState.Error -> ScrollableErrorState(s.message, s.isLoginRequired) { vm.reloadVms() }
                is UiState.Success -> if (current == null) ScrollableErrorState("This VM no longer exists.", false) { onBack() }
                else VmDetailContent(
                    current, busy["vm:${current.id}"],
                    onAction = { a -> if (a == VmAction.START) vm.act(current, a) else confirm = a },
                    onEdit = { editing = true },
                    onDelete = { deleting = true },
                    onDisplay = {
                        scope.launch {
                            try {
                                val base = container.repository.connectedBaseUrl?.toHttpUrlOrNull()
                                val host = base?.let { if (it.port == okhttp3.HttpUrl.defaultPort(it.scheme)) it.host else "${it.host}:${it.port}" } ?: ""
                                val uri = container.repository.call { it.vmDisplayUri(current.id, host, base?.isHttps != false) }
                                if (uri != null) context.startActivity(Intent(Intent.ACTION_VIEW, uri.toUri()))
                            } catch (e: Throwable) { snackbar.showSnackbar(e.userMessage()) }
                        }
                    },
                )
            }
        }
    }
    val v = current
    confirm?.let { a ->
        if (v != null) ConfirmDialog(
            title = "${a.label} ${v.name}?",
            text = when (a) {
                VmAction.STOP -> "Asks the guest OS to shut down cleanly (ACPI). If the guest ignores it, use Power off."
                VmAction.RESTART -> "Shuts the guest down cleanly (forcing it after the timeout), then starts it again."
                VmAction.POWER_OFF -> "Cuts power to the VM immediately, like pulling the plug. Unsaved data in the guest is lost."
                VmAction.START -> ""
            },
            confirmLabel = a.label, destructive = a == VmAction.POWER_OFF,
            onConfirm = { vm.act(v, a); confirm = null }, onDismiss = { confirm = null },
        )
    }
    if (editing && v != null) EditResourcesDialog(v, onDismiss = { editing = false }) { cpus, cores, threads, mem, autostart, desc ->
        scope.launch {
            try {
                container.repository.call { it.vmUpdateResources(v.id, cpus, cores, threads, mem, autostart, desc) }
                editing = false; vm.reloadVms(); snackbar.showSnackbar(if (v.state == VmState.RUNNING) "Saved. Changes apply after the VM restarts." else "Saved")
            } catch (e: Throwable) { snackbar.showSnackbar(e.userMessage()) }
        }
    }
    if (deleting && v != null) DeleteVmDialog(v, onDismiss = { deleting = false }) { zvols -> deleting = false; vm.deleteVm(v, zvols, after = onBack) }
}

/** Stateless VM details (also rendered by the screenshot tests). */
@Composable
fun VmDetailContent(vm: VmInfo, busy: String?, onAction: (VmAction) -> Unit, onEdit: () -> Unit, onDelete: () -> Unit, onDisplay: () -> Unit) {
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxSize()) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconTile(Icons.Rounded.Computer)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(vm.name, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (vm.description.isNotBlank()) Text(vm.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                StatusChip(vm.state.health(), vm.state.pretty(), showIcon = vm.state != VmState.STOPPED)
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (vm.state == VmState.RUNNING && vm.hasWebDisplay && busy == null) {
                    GlowButton(onClick = onDisplay, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Open display", maxLines = 1)
                    }
                }
                ActionRow(busy) {
                    when (vm.state) {
                        VmState.RUNNING -> {
                            PrimaryAction(Icons.Rounded.Stop, "Shut down") { onAction(VmAction.STOP) }
                            SecondaryAction(Icons.Rounded.RestartAlt, "Restart") { onAction(VmAction.RESTART) }
                            SecondaryAction(Icons.Rounded.PowerSettingsNew, "Power off") { onAction(VmAction.POWER_OFF) }
                        }
                        VmState.SUSPENDED -> SecondaryAction(Icons.Rounded.PowerSettingsNew, "Power off") { onAction(VmAction.POWER_OFF) }
                        else -> PrimaryAction(Icons.Rounded.PlayArrow, "Start") { onAction(VmAction.START) }
                    }
                }
            }
        }
        item {
            ElevatedSection(contentPadding = 14.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Resources", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = onEdit) { Icon(Icons.Rounded.Edit, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("Edit") }
                }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Detail("CPU", "${plural(vm.totalCpus, "vCPU")}\n${plural(vm.vcpus, "socket")} × ${plural(vm.cores, "core")} × ${plural(vm.threads, "thread")}")
                    Detail("Memory", Format.bytes(vm.memoryMb * 1024 * 1024))
                    Detail("Boot", when (vm.bootloader) { "UEFI" -> "UEFI"; "UEFI_CSM" -> "Legacy BIOS"; else -> vm.bootloader ?: "—" })
                    Detail("Autostart", if (vm.autostart) "On" else "Off")
                }
            }
        }
        item {
            ElevatedSection(contentPadding = 14.dp) {
                Text("Devices", style = MaterialTheme.typography.titleMedium)
                if (vm.devices.isEmpty()) Text("No devices", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                vm.devices.forEachIndexed { i, d ->
                    if (i > 0) HorizontalDivider(Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                    DeviceRow(d)
                }
            }
        }
        item {
            OutlinedButton(
                onClick = onDelete, enabled = vm.state != VmState.RUNNING && busy == null,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
            ) { Icon(Icons.Rounded.Delete, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(if (vm.state == VmState.RUNNING) "Stop the VM to delete it" else "Delete VM…", maxLines = 1) }
        }
    }
}

@Composable
private fun DeviceRow(d: VmDevice) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(
            when (d.kind) {
                "DISK", "RAW" -> Icons.Rounded.Storage
                "CDROM" -> Icons.Rounded.Album
                "NIC" -> Icons.Rounded.Lan
                "DISPLAY" -> Icons.Rounded.DesktopWindows
                "USB" -> Icons.Rounded.Usb
                else -> Icons.Rounded.DeveloperBoard
            }, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(d.title, style = MaterialTheme.typography.bodyLarge)
            d.detail?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
    }
}

@Composable
fun EditResourcesDialog(vm: VmInfo, onDismiss: () -> Unit, onSave: (Int, Int, Int, Long, Boolean, String) -> Unit) {
    var cpus by remember { mutableStateOf(vm.vcpus.toString()) }
    var cores by remember { mutableStateOf(vm.cores.toString()) }
    var threads by remember { mutableStateOf(vm.threads.toString()) }
    var memory by remember { mutableStateOf(VmForm.gibText(vm.memoryMb)) }
    var autostart by remember { mutableStateOf(vm.autostart) }
    var description by remember { mutableStateOf(vm.description) }
    val memMb = VmForm.parseGiB(memory)
    val c = cpus.toIntOrNull(); val co = cores.toIntOrNull(); val t = threads.toIntOrNull()
    val valid = c != null && c >= 1 && co != null && co >= 1 && t != null && t >= 1 && memMb != null
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Resources") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField("vCPUs", cpus, Modifier.weight(1f)) { cpus = it }
                    NumberField("Cores", cores, Modifier.weight(1f)) { cores = it }
                    NumberField("Threads", threads, Modifier.weight(1f)) { threads = it }
                }
                OutlinedTextField(memory, { memory = it }, label = { Text("Memory (GiB)") }, singleLine = true, isError = memMb == null,
                    supportingText = { Text(if (memMb == null) "At least 0.02 GiB (20 MiB)" else "${memMb} MiB") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(description, { description = it }, label = { Text("Description") }, modifier = Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth().clickable { autostart = !autostart }, verticalAlignment = Alignment.CenterVertically) {
                    Text("Start when the NAS boots", modifier = Modifier.weight(1f))
                    Switch(checked = autostart, onCheckedChange = { autostart = it })
                }
                if (vm.state == VmState.RUNNING) Text("Changes apply after the VM restarts.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { GlowButton(onClick = { onSave(c!!, co!!, t!!, memMb!!, autostart, description) }, enabled = valid) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
internal fun NumberField(label: String, value: String, modifier: Modifier = Modifier, onChange: (String) -> Unit) {
    OutlinedTextField(value, { onChange(it.filter(Char::isDigit).take(3)) }, label = { Text(label, maxLines = 1) }, singleLine = true,
        isError = (value.toIntOrNull() ?: 0) < 1, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = modifier)
}

@Composable
fun DeleteVmDialog(vm: VmInfo, onDismiss: () -> Unit, onDelete: (Boolean) -> Unit) {
    var zvols by remember { mutableStateOf(false) }
    val guard = LocalDangerGuard.current
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Delete, null) },
        title = { Text("Delete ${vm.name}?") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("The VM's configuration and devices are removed.")
                Row(Modifier.fillMaxWidth().clickable { zvols = !zvols }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = zvols, onCheckedChange = { zvols = it })
                    Text("Also delete its disks (zvols). This can't be undone.", style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            Button(onClick = { guard.guard("Delete ${vm.name}") { onDelete(zvols) } },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError)) { Text("Delete") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

