package app.truenascompanion.ui.virt

import app.truenascompanion.util.runCatchingCancellable

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ListAlt
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import app.truenascompanion.ui.components.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import app.truenascompanion.ui.components.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.truenascompanion.AppContainer
import app.truenascompanion.data.api.TrueNasException
import app.truenascompanion.data.api.userMessage
import app.truenascompanion.data.model.Health
import app.truenascompanion.data.model.InstanceStatus
import app.truenascompanion.data.model.JobInfo
import app.truenascompanion.data.model.VirtInstance
import app.truenascompanion.data.model.VmInfo
import app.truenascompanion.data.model.VmState
import app.truenascompanion.ui.apps.AppsScreen
import app.truenascompanion.ui.appViewModel
import app.truenascompanion.ui.components.ConfirmDialog
import app.truenascompanion.ui.components.ElevatedSection
import app.truenascompanion.ui.components.EmptyState
import app.truenascompanion.ui.components.GlowButton
import app.truenascompanion.ui.components.ScrollableErrorState
import app.truenascompanion.ui.components.SkeletonList
import app.truenascompanion.ui.components.StatusChip
import app.truenascompanion.ui.components.UiState
import app.truenascompanion.ui.components.isLoginRequired
import app.truenascompanion.util.Format
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Segments of the Apps tab (keeps the bottom bar at five tabs). */
enum class Workload(val label: String) { APPS("Apps"), VMS("VMs"), CONTAINERS("Containers") }

enum class VmAction(val label: String) { START("Start"), STOP("Shut down"), RESTART("Restart"), POWER_OFF("Power off") }
enum class InstanceAction(val label: String) { START("Start"), STOP("Stop"), RESTART("Restart") }

fun VmState.health(): Health = when (this) {
    VmState.RUNNING -> Health.HEALTHY
    VmState.SUSPENDED -> Health.WARNING
    VmState.STOPPED, VmState.UNKNOWN -> Health.UNKNOWN
}

fun InstanceStatus.health(): Health = when (this) {
    InstanceStatus.RUNNING -> Health.HEALTHY
    InstanceStatus.STARTING, InstanceStatus.STOPPING, InstanceStatus.FROZEN -> Health.WARNING
    InstanceStatus.ERROR -> Health.CRITICAL
    InstanceStatus.STOPPED, InstanceStatus.UNKNOWN -> Health.UNKNOWN
}

fun plural(n: Int, word: String) = "$n $word" + if (n == 1) "" else "s"

fun Enum<*>.pretty(): String = name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }

/** Containers state as shown when the list can't be used. Pure, unit tested. */
fun containersUnavailableMessage(state: String?): String? = when (state?.uppercase()) {
    null, "INITIALIZED" -> null
    "NO_POOL" -> "Containers aren't set up yet. Choose a pool for them in the TrueNAS web UI (Containers › Configuration)."
    "LOCKED" -> "The containers pool is locked. Unlock it in the TrueNAS web UI."
    "INITIALIZING" -> "Containers are starting up on the NAS. Pull to refresh in a moment."
    else -> "Containers aren't available right now (state: ${state.lowercase()})."
}

data class ContainersUi(val list: UiState<List<VirtInstance>> = UiState.Loading, val unavailable: String? = null)

@OptIn(ExperimentalCoroutinesApi::class)
class VirtViewModel(private val c: AppContainer, private val withContainers: Boolean = true) : ViewModel() {
    private val _vms = MutableStateFlow<UiState<List<VmInfo>>>(UiState.Loading)
    val vms: StateFlow<UiState<List<VmInfo>>> = _vms.asStateFlow()
    private val _containers = MutableStateFlow(ContainersUi())
    val containers: StateFlow<ContainersUi> = _containers.asStateFlow()
    private val _refreshing = MutableStateFlow(false)
    val refreshing = _refreshing.asStateFlow()
    /** Key (vm id or instance id) -> action label while something is running for it. */
    private val _busy = MutableStateFlow<Map<String, String>>(emptyMap())
    val busy = _busy.asStateFlow()
    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()
    private val _activeJobs = MutableStateFlow(0)
    val activeJobs = _activeJobs.asStateFlow()
    private data class Pending(val key: String, val done: String, val failed: String, val vm: Boolean)
    private val pending = mutableMapOf<Long, Pending>()

    /** Live job list, only while a VM/containers screen is visible; used to finish busy states and refresh. */
    val jobsLive: StateFlow<Int> = c.repository.reloadKey
        .flatMapLatest { key -> if (key == null) emptyFlow() else c.repository.jobs().onEach(::onJobs).map { it.size }.catch { } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    init {
        viewModelScope.launch {
            c.repository.reloadKey.collect { if (it != null) { _vms.value = UiState.Loading; _containers.value = ContainersUi(); loadVms(); if (withContainers) loadContainers() } }
        }
    }

    fun refresh(workload: Workload) = viewModelScope.launch {
        _refreshing.value = true
        if (workload == Workload.CONTAINERS) loadContainers() else loadVms()
        _refreshing.value = false
    }

    private suspend fun loadVms() {
        try { _vms.value = UiState.Success(c.repository.call { it.vms() }) }
        catch (e: Throwable) {
            if (_vms.value !is UiState.Success) _vms.value = UiState.Error(friendly(e, "Virtual machines"), e) else _messages.trySend(e.userMessage())
        }
    }

    private suspend fun loadContainers() {
        try {
            val state = runCatchingCancellable { c.repository.call { it.containersState() } }.getOrNull()
            val msg = containersUnavailableMessage(state)
            _containers.value = if (msg != null) ContainersUi(UiState.Success(emptyList()), msg)
            else ContainersUi(UiState.Success(c.repository.call { it.virtInstances() }))
        } catch (e: Throwable) {
            if (_containers.value.list !is UiState.Success) _containers.value = ContainersUi(UiState.Error(friendly(e, "Containers"), e))
            else _messages.trySend(e.userMessage())
        }
    }

    private fun friendly(e: Throwable, what: String) =
        if (e is TrueNasException.MethodNotFound) "$what aren't available through the API of this TrueNAS version." else e.userMessage()

    private fun setBusy(key: String, label: String?) {
        _busy.value = if (label == null) _busy.value - key else _busy.value + (key to label)
    }

    fun act(vm: VmInfo, action: VmAction) = viewModelScope.launch {
        val key = "vm:${vm.id}"
        setBusy(key, action.label)
        try {
            when (action) {
                VmAction.START -> { c.repository.call { it.vmStart(vm.id) }; _messages.trySend("${vm.name} started"); finish(key, true) }
                VmAction.POWER_OFF -> { c.repository.call { it.vmPowerOff(vm.id) }; _messages.trySend("${vm.name} powered off"); finish(key, true) }
                VmAction.STOP -> watch(c.repository.call { it.startVmStop(vm.id) }, Pending(key, "${vm.name} shut down", "Shutting down ${vm.name} failed", true))
                VmAction.RESTART -> watch(c.repository.call { it.startVmRestart(vm.id) }, Pending(key, "${vm.name} restarted", "Restarting ${vm.name} failed", true))
            }
        } catch (e: Throwable) {
            val msg = e.userMessage()
            _messages.trySend(if (action == VmAction.START && msg.contains("memory", ignoreCase = true)) "$msg Free some memory or lower the VM's memory, then try again." else msg)
            finish(key, true)
        }
    }

    fun deleteVm(vm: VmInfo, zvols: Boolean, after: () -> Unit = {}) = viewModelScope.launch {
        val key = "vm:${vm.id}"
        setBusy(key, "Deleting")
        try { c.repository.call { it.vmDelete(vm.id, zvols) }; _messages.trySend("${vm.name} deleted"); after() }
        catch (e: Throwable) { _messages.trySend(e.userMessage()) }
        finish(key, true)
    }

    fun act(inst: VirtInstance, action: InstanceAction, force: Boolean = false) = viewModelScope.launch {
        val key = "ct:${inst.id}"
        setBusy(key, action.label)
        try {
            val id = c.repository.call {
                when (action) {
                    InstanceAction.START -> it.startVirtStart(inst.id)
                    InstanceAction.STOP -> it.startVirtStop(inst.id, force)
                    InstanceAction.RESTART -> it.startVirtRestart(inst.id)
                }
            }
            watch(id, Pending(key, "${inst.name}: ${action.label.lowercase()} done", "${action.label} ${inst.name} failed", false))
        } catch (e: Throwable) { _messages.trySend(e.userMessage()); finish(key, false) }
    }

    fun deleteInstance(inst: VirtInstance) = viewModelScope.launch {
        val key = "ct:${inst.id}"
        setBusy(key, "Deleting")
        try { watch(c.repository.call { it.startVirtDelete(inst.id) }, Pending(key, "${inst.name} deleted", "Deleting ${inst.name} failed", false)) }
        catch (e: Throwable) { _messages.trySend(e.userMessage()); finish(key, false) }
    }

    private fun watch(jobId: Long, p: Pending) { pending[jobId] = p }

    private suspend fun finish(key: String, vm: Boolean) {
        setBusy(key, null)
        if (vm) loadVms() else loadContainers()
    }

    private suspend fun onJobs(jobs: List<JobInfo>) {
        _activeJobs.value = jobs.count { it.state.active }
        val byId = jobs.associateBy { it.id }
        for ((id, p) in pending.toMap()) {
            val job = byId[id] ?: continue
            if (job.state.active) continue
            pending.remove(id)
            _messages.trySend(if (job.state == app.truenascompanion.data.model.JobState.SUCCESS) p.done else p.failed + (job.error?.let { ": $it" } ?: ""))
            finish(p.key, p.vm)
        }
    }

    fun vm(id: Int): VmInfo? = (vms.value as? UiState.Success)?.data?.firstOrNull { it.id == id }
    fun reloadVms() = viewModelScope.launch { loadVms() }
}

@Composable
fun WorkloadSwitcher(selected: Workload, onSelect: (Workload) -> Unit, modifier: Modifier = Modifier) {
    SingleChoiceSegmentedButtonRow(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Workload.entries.forEachIndexed { i, w ->
            SegmentedButton(
                selected = selected == w, onClick = { onSelect(w) },
                shape = SegmentedButtonDefaults.itemShape(i, Workload.entries.size), icon = {},
                label = { Text(w.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            )
        }
    }
}

/** The Apps tab: installed apps, VMs and containers behind one segmented control. */
@Composable
fun WorkloadsScreen(
    onJobs: () -> Unit, onCatalog: () -> Unit, onOpenApp: (String) -> Unit,
    onOpenVm: (Int) -> Unit, onCreateVm: () -> Unit,
    onShell: (app.truenascompanion.data.shell.ShellTarget) -> Unit = {},
) {
    var workload by rememberSaveable { mutableStateOf(Workload.APPS) }
    val header: @Composable () -> Unit = { WorkloadSwitcher(workload, { workload = it }) }
    when (workload) {
        Workload.APPS -> AppsScreen(onJobs = onJobs, onCatalog = onCatalog, onOpenApp = onOpenApp, header = header)
        else -> VirtScreen(workload, header, onJobs, onOpenVm, onCreateVm, onShell)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VirtScreen(
    workload: Workload, header: @Composable () -> Unit, onJobs: () -> Unit, onOpenVm: (Int) -> Unit, onCreateVm: () -> Unit,
    onShell: (app.truenascompanion.data.shell.ShellTarget) -> Unit,
) {
    val vm = appViewModel { VirtViewModel(it) }
    val vms by vm.vms.collectAsStateWithLifecycle()
    val containers by vm.containers.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val activeJobs by vm.activeJobs.collectAsStateWithLifecycle()
    vm.jobsLive.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var confirmVm by remember { mutableStateOf<Pair<VmInfo, VmAction>?>(null) }
    var confirmCt by remember { mutableStateOf<Pair<VirtInstance, InstanceAction?>?>(null) }
    var shellFor by remember { mutableStateOf<VirtInstance?>(null) }
    val lockGuard = app.truenascompanion.ui.lock.LocalLockGuard.current
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (workload == Workload.VMS) "Virtual machines" else "Containers") },
                actions = {
                    if (workload == Workload.VMS) IconButton(onClick = onCreateVm) { Icon(Icons.Rounded.Add, "New virtual machine") }
                    IconButton(onClick = onJobs) {
                        BadgedBox(badge = { if (activeJobs > 0) Badge { Text("$activeJobs") } }) { Icon(Icons.AutoMirrored.Rounded.ListAlt, "Running jobs") }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            header()
            PullToRefreshBox(isRefreshing = refreshing, onRefresh = { vm.refresh(workload) }, modifier = Modifier.fillMaxSize()) {
                if (workload == Workload.VMS) VmList(vms, busy, onRetry = { vm.refresh(workload) }, onOpen = { onOpenVm(it.id) }, onCreate = onCreateVm,
                    onAction = { v, a -> if (a == VmAction.START) vm.act(v, a) else confirmVm = v to a })
                else ContainerList(containers, busy, onRetry = { vm.refresh(workload) },
                    onAction = { i, a -> if (a == InstanceAction.START) vm.act(i, a) else confirmCt = i to a }, onDelete = { confirmCt = it to null }, onShell = { shellFor = it })
            }
        }
    }
    confirmVm?.let { (v, a) ->
        ConfirmDialog(
            title = "${a.label} ${v.name}?",
            text = when (a) {
                VmAction.STOP -> "Asks the guest OS to shut down cleanly (ACPI). If the guest ignores it, use Power off."
                VmAction.RESTART -> "Shuts the guest down cleanly (forcing it after the timeout), then starts it again."
                VmAction.POWER_OFF -> "Cuts power to the VM immediately, like pulling the plug. Unsaved data in the guest is lost."
                VmAction.START -> ""
            },
            confirmLabel = a.label, destructive = a == VmAction.POWER_OFF, requireAuth = a == VmAction.POWER_OFF,
            icon = if (a == VmAction.POWER_OFF) Icons.Rounded.PowerSettingsNew else null,
            onConfirm = { vm.act(v, a); confirmVm = null }, onDismiss = { confirmVm = null },
        )
    }
    shellFor?.let { i ->
        app.truenascompanion.ui.shell.ShellStartDialog(
            title = "Shell in ${i.name}", containers = emptyList(), defaultLabel = "Default shell",
            onOpen = { _, cmd ->
                shellFor = null
                lockGuard.guard("Open a shell in ${i.name}") { onShell(app.truenascompanion.data.shell.ShellTarget.Instance(i.id, cmd)) }
            },
            onDismiss = { shellFor = null },
        )
    }
    confirmCt?.let { (i, a) ->
        ConfirmDialog(
            title = if (a == null) "Delete ${i.name}?" else "${a.label} ${i.name}?",
            text = when (a) {
                null -> "The container and its root disk are deleted. This can't be undone."
                InstanceAction.STOP -> "Stops the container (waits up to 60 s for a clean shutdown)."
                InstanceAction.RESTART -> "Stops and starts the container again."
                InstanceAction.START -> ""
            },
            confirmLabel = a?.label ?: "Delete", destructive = a == null, icon = if (a == null) Icons.Rounded.Delete else null,
            onConfirm = { if (a == null) vm.deleteInstance(i) else vm.act(i, a); confirmCt = null }, onDismiss = { confirmCt = null },
        )
    }
}

@Composable
fun VmList(state: UiState<List<VmInfo>>, busy: Map<String, String>, onRetry: () -> Unit, onOpen: (VmInfo) -> Unit, onCreate: () -> Unit, onAction: (VmInfo, VmAction) -> Unit) {
    when (state) {
        UiState.Loading -> SkeletonList(4, 96.dp)
        is UiState.Error -> ScrollableErrorState(state.message, state.isLoginRequired, onRetry)
        is UiState.Success -> if (state.data.isEmpty()) LazyColumn(Modifier.fillMaxSize()) {
            item { EmptyState(Icons.Rounded.Computer, "No virtual machines", "Create a VM here or in the TrueNAS web UI.") { GlowButton(onClick = onCreate) { Text("New VM") } } }
        } else LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxSize()) {
            item {
                Text("${state.data.size} VMs · ${state.data.count { it.state == VmState.RUNNING }} running", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp, bottom = 4.dp))
            }
            items(state.data, key = { it.id }) { v -> VmCard(v, busy["vm:${v.id}"], onOpen = { onOpen(v) }, onAction = { onAction(v, it) }) }
        }
    }
}

@Composable
fun VmCard(vm: VmInfo, busy: String?, onOpen: () -> Unit, onAction: (VmAction) -> Unit) {
    ElevatedSection(onClick = onOpen, contentPadding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconTile(Icons.Rounded.Computer)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(vm.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${vm.totalCpus} vCPU · ${Format.bytes(vm.memoryMb * 1024 * 1024)}${if (vm.autostart) " · autostart" else ""}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            StatusChip(vm.state.health(), vm.state.pretty(), showIcon = vm.state != VmState.STOPPED)
        }
        if (vm.description.isNotBlank()) {
            Spacer(Modifier.padding(top = 6.dp))
            Text(vm.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.padding(top = 10.dp))
        CardActions(busy) {
            when (vm.state) {
                VmState.RUNNING -> {
                    PrimaryAction(Icons.Rounded.Stop, "Shut down") { onAction(VmAction.STOP) }
                    Spacer(Modifier.weight(1f))
                    Overflow(listOf("Restart" to { onAction(VmAction.RESTART) }, "Power off" to { onAction(VmAction.POWER_OFF) }))
                }
                VmState.SUSPENDED -> SecondaryAction(Icons.Rounded.PowerSettingsNew, "Power off") { onAction(VmAction.POWER_OFF) }
                else -> PrimaryAction(Icons.Rounded.PlayArrow, "Start") { onAction(VmAction.START) }
            }
        }
    }
}

@Composable
fun ContainerList(
    ui: ContainersUi, busy: Map<String, String>, onRetry: () -> Unit, onAction: (VirtInstance, InstanceAction) -> Unit, onDelete: (VirtInstance) -> Unit,
    onShell: (VirtInstance) -> Unit = {},
) {
    when (val s = ui.list) {
        UiState.Loading -> SkeletonList(4, 96.dp)
        is UiState.Error -> ScrollableErrorState(s.message, s.isLoginRequired, onRetry)
        is UiState.Success -> if (s.data.isEmpty()) LazyColumn(Modifier.fillMaxSize()) {
            item { EmptyState(Icons.Rounded.Inventory2, if (ui.unavailable != null) "Containers not set up" else "No containers", ui.unavailable ?: "Create Linux containers in the TrueNAS web UI (Containers). They'll show up here.") }
        } else LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxSize()) {
            item {
                Text("${s.data.size} containers · ${s.data.count { it.status == InstanceStatus.RUNNING }} running", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp, bottom = 4.dp))
            }
            items(s.data, key = { it.id }) { i -> ContainerCard(i, busy["ct:${i.id}"], onAction = { onAction(i, it) }, onDelete = { onDelete(i) }, onShell = { onShell(i) }) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ContainerCard(inst: VirtInstance, busy: String?, onAction: (InstanceAction) -> Unit, onDelete: () -> Unit, startExpanded: Boolean = false, onShell: () -> Unit = {}) {
    var expanded by rememberSaveable(inst.id) { mutableStateOf(startExpanded) }
    ElevatedSection(onClick = { expanded = !expanded }, contentPadding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconTile(Icons.Rounded.Inventory2)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(inst.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(inst.image ?: inst.type.lowercase(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            StatusChip(inst.status.health(), inst.status.pretty(), showIcon = inst.status != InstanceStatus.STOPPED)
        }
        AnimatedVisibility(expanded) {
            Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Detail("Type", if (inst.type == "VM") "VM (Incus)" else "Container")
                Detail("CPU", inst.cpu?.let { "$it cores" } ?: "No limit")
                Detail("Memory", inst.memoryBytes?.let { Format.bytes(it) } ?: "No limit")
                Detail("Autostart", if (inst.autostart) "On" else "Off")
                inst.storagePool?.let { Detail("Pool", it) }
                if (inst.addresses.isNotEmpty()) Detail("Addresses", inst.addresses.joinToString("\n"))
                if (inst.type.equals("VM", ignoreCase = true)) Text("Incus VMs use a console, open it in the TrueNAS web UI.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.padding(top = 10.dp))
        CardActions(busy) {
            when (inst.status) {
                InstanceStatus.RUNNING -> {
                    PrimaryAction(Icons.Rounded.Stop, "Stop") { onAction(InstanceAction.STOP) }
                    if (!inst.type.equals("VM", ignoreCase = true)) SecondaryAction(Icons.Rounded.Terminal, "Shell", onShell)
                    Spacer(Modifier.weight(1f))
                    Overflow(listOf("Restart" to { onAction(InstanceAction.RESTART) }))
                }
                InstanceStatus.STOPPED, InstanceStatus.ERROR, InstanceStatus.UNKNOWN -> {
                    PrimaryAction(Icons.Rounded.PlayArrow, "Start") { onAction(InstanceAction.START) }
                    Spacer(Modifier.weight(1f))
                    Overflow(listOf("Delete…" to onDelete))
                }
                else -> Unit
            }
        }
    }
}

@Composable
internal fun Detail(label: String, value: String) {
    Row {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(96.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}

@Composable
internal fun IconTile(icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(14.dp), modifier = Modifier.size(44.dp)) {
        Box(contentAlignment = Alignment.Center) { Icon(icon, null, tint = MaterialTheme.colorScheme.onPrimaryContainer) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ActionRow(busy: String?, content: @Composable () -> Unit) {
    if (busy != null) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = 40.dp)) {
        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(10.dp))
        Text("$busy…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically) { content() }
}

/** One-line action bar for cards: primary action on the left, overflow on the right. */
@Composable
internal fun CardActions(busy: String?, content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    if (busy != null) ActionRow(busy) {}
    else Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { content() }
}

@Composable
internal fun PrimaryAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    FilledTonalButton(onClick = onClick, modifier = Modifier.heightIn(min = 40.dp)) {
        Icon(icon, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(label, maxLines = 1)
    }
}

@Composable
internal fun SecondaryAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.heightIn(min = 40.dp)) {
        Icon(icon, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(label, maxLines = 1)
    }
}

@Composable
internal fun Overflow(items: List<Pair<String, () -> Unit>>) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Rounded.MoreVert, "More actions") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            items.forEach { (label, action) -> DropdownMenuItem(text = { Text(label) }, onClick = { open = false; action() }) }
        }
    }
}
