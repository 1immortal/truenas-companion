package app.truenascompanion.ui.services

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.DriveFolderUpload
import androidx.compose.material.icons.rounded.FolderShared
import androidx.compose.material.icons.rounded.Lan
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.MiscellaneousServices
import androidx.compose.material.icons.rounded.MonitorHeart
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.truenascompanion.AppContainer
import app.truenascompanion.data.api.ServiceVerb
import app.truenascompanion.data.api.ServicesApi
import app.truenascompanion.data.api.userMessage
import app.truenascompanion.data.model.Health
import app.truenascompanion.data.model.ServiceInfo
import app.truenascompanion.data.services.ServiceKind
import app.truenascompanion.ui.appViewModel
import app.truenascompanion.ui.components.ConfirmDialog
import app.truenascompanion.ui.components.ElevatedSection
import app.truenascompanion.ui.components.IconBadge
import app.truenascompanion.ui.components.ScrollableErrorState
import app.truenascompanion.ui.components.SkeletonList
import app.truenascompanion.ui.components.StatusChip
import app.truenascompanion.ui.components.UiState
import app.truenascompanion.ui.components.isLoginRequired
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What a service row is busy with: a running `service.control` job or a start-on-boot change. */
data class ServiceBusy(val verb: ServiceVerb? = null, val autostart: Boolean = false, val jobId: Long? = null)

class ServicesViewModel(private val c: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow<UiState<List<ServiceInfo>>>(UiState.Loading)
    val state = _state.asStateFlow()
    private val _busy = MutableStateFlow<Map<String, ServiceBusy>>(emptyMap())
    val busy = _busy.asStateFlow()
    private val _refreshing = MutableStateFlow(false)
    val refreshing = _refreshing.asStateFlow()
    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    init {
        viewModelScope.launch { c.repository.reloadKey.collect { if (it != null) load() } }
    }

    fun refresh() = viewModelScope.launch { _refreshing.value = true; load(); _refreshing.value = false }

    private suspend fun load() {
        try {
            _state.value = UiState.Success(c.repository.call { ServicesApi(it).services() })
        } catch (e: Throwable) {
            if (_state.value !is UiState.Success) _state.value = UiState.Error(e.userMessage(), e) else _messages.trySend(e.userMessage())
        }
    }

    /** Start / stop / restart as a `service.control` job; the row shows progress until the job ends. */
    fun control(s: ServiceInfo, verb: ServiceVerb) {
        if (_busy.value[s.service] != null) return
        _busy.update { it + (s.service to ServiceBusy(verb = verb)) }
        viewModelScope.launch {
            try {
                c.repository.call { api -> ServicesApi(api).control(s.service, verb) { id -> _busy.update { b -> b + (s.service to ServiceBusy(verb, jobId = id)) } } }
                _messages.trySend("${s.displayName} ${verb.done}")
            } catch (e: Throwable) {
                _messages.trySend("${s.displayName}: ${e.userMessage()}")
            } finally {
                _busy.update { it - s.service }
                load()
            }
        }
    }

    fun setAutostart(s: ServiceInfo, enable: Boolean) {
        if (_busy.value[s.service] != null) return
        _busy.update { it + (s.service to ServiceBusy(autostart = true)) }
        // Optimistic: flip the switch right away, reload afterwards.
        _state.update { st -> (st as? UiState.Success)?.let { UiState.Success(it.data.map { x -> if (x.service == s.service) x.copy(enabledOnBoot = enable) else x }) } ?: st }
        viewModelScope.launch {
            try {
                c.repository.call { ServicesApi(it).setAutostart(s.service, enable) }
                _messages.trySend(if (enable) "${s.displayName} starts on boot" else "${s.displayName} no longer starts on boot")
            } catch (e: Throwable) {
                _messages.trySend("${s.displayName}: ${e.userMessage()}")
            } finally {
                _busy.update { it - s.service }
                load()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServicesScreen(onBack: () -> Unit, onOpenSettings: (ServiceKind) -> Unit) {
    val vm = appViewModel { ServicesViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(vm) { vm.messages.collect { snackbar.showSnackbar(it) } }
    var confirm by remember { mutableStateOf<Pair<ServiceInfo, ServiceVerb>?>(null) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Services") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } }) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        PullToRefreshBox(isRefreshing = refreshing, onRefresh = { vm.refresh() }, modifier = Modifier.padding(padding).fillMaxSize()) {
            when (val s = state) {
                UiState.Loading -> SkeletonList(6, 120.dp)
                is UiState.Error -> ScrollableErrorState(s.message, s.isLoginRequired) { vm.refresh() }
                is UiState.Success -> ServicesContent(
                    s.data, busy,
                    onAction = { svc, verb -> if (verb == ServiceVerb.START) vm.control(svc, verb) else confirm = svc to verb },
                    onAutostart = vm::setAutostart,
                    onOpen = onOpenSettings,
                )
            }
        }
    }
    confirm?.let { (svc, verb) ->
        ServiceActionDialog(svc, verb, onConfirm = { confirm = null; vm.control(svc, verb) }, onDismiss = { confirm = null })
    }
}

/** Confirmation before stopping or restarting a service; access services (SSH, SMB, NFS, iSCSI…) get a stronger warning. */
@Composable
fun ServiceActionDialog(s: ServiceInfo, verb: ServiceVerb, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val access = s.service in ServicesApi.ACCESS_SERVICES
    ConfirmDialog(
        title = "${verb.label} ${s.displayName}?",
        text = (if (access && verb == ServiceVerb.STOP) "This may cut off access to the NAS. " else "") + ServicesApi.confirmText(s, verb),
        confirmLabel = verb.label,
        destructive = true,
        requireAuth = access,
        icon = if (access) Icons.Rounded.Warning else if (verb == ServiceVerb.STOP) Icons.Rounded.Stop else Icons.Rounded.RestartAlt,
        onConfirm = onConfirm, onDismiss = onDismiss,
    )
}

fun serviceIcon(service: String): ImageVector = when (service) {
    "ssh" -> Icons.Rounded.Terminal
    "cifs" -> Icons.Rounded.FolderShared
    "nfs" -> Icons.Rounded.Lan
    "ups" -> Icons.Rounded.BatteryChargingFull
    "snmp" -> Icons.Rounded.MonitorHeart
    "ftp" -> Icons.Rounded.DriveFolderUpload
    "iscsitarget" -> Icons.Rounded.Storage
    "nvmet" -> Icons.Rounded.Memory
    else -> Icons.Rounded.MiscellaneousServices
}

@Composable
fun ServicesContent(
    services: List<ServiceInfo>,
    busy: Map<String, ServiceBusy>,
    onAction: (ServiceInfo, ServiceVerb) -> Unit,
    onAutostart: (ServiceInfo, Boolean) -> Unit,
    onOpen: (ServiceKind) -> Unit,
) {
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxSize()) {
        item {
            Text(
                "Start, stop and configure the services that share your data. Tap a service with settings to change them.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        items(services, key = { it.service }) { s -> ServiceCard(s, busy[s.service], onAction, onAutostart, onOpen) }
    }
}

@Composable
private fun ServiceCard(
    s: ServiceInfo,
    busy: ServiceBusy?,
    onAction: (ServiceInfo, ServiceVerb) -> Unit,
    onAutostart: (ServiceInfo, Boolean) -> Unit,
    onOpen: (ServiceKind) -> Unit,
) {
    val kind = ServiceKind.of(s.service)
    ElevatedSection(contentPadding = 14.dp, onClick = kind?.let { { onOpen(it) } }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(serviceIcon(s.service))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(s.displayName, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f, fill = false))
                    Spacer(Modifier.width(8.dp))
                    when {
                        s.unknown -> StatusChip(Health.UNKNOWN, "Unknown")
                        s.running -> StatusChip(Health.HEALTHY, "Running")
                        else -> StatusChip(Health.UNKNOWN, "Stopped", showIcon = false)
                    }
                }
                kind?.let { Text(it.summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            if (kind != null) Icon(Icons.Rounded.ChevronRight, "${s.displayName} settings", tint = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.height(10.dp))
        if (busy?.verb != null) {
            Text("${busy.verb.doing} ${s.displayName}…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(Modifier.fillMaxWidth())
        } else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (s.running) {
                OutlinedButton(onClick = { onAction(s, ServiceVerb.STOP) }, enabled = busy == null, contentPadding = PaddingValues(horizontal = 12.dp)) {
                    Icon(Icons.Rounded.Stop, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Stop", maxLines = 1)
                }
                OutlinedButton(onClick = { onAction(s, ServiceVerb.RESTART) }, enabled = busy == null, contentPadding = PaddingValues(horizontal = 12.dp)) {
                    Icon(Icons.Rounded.RestartAlt, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Restart", maxLines = 1)
                }
            } else {
                FilledTonalButton(onClick = { onAction(s, ServiceVerb.START) }, enabled = busy == null, contentPadding = PaddingValues(horizontal = 12.dp)) {
                    Icon(Icons.Rounded.PlayArrow, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Start", maxLines = 1)
                }
            }
            Spacer(Modifier.weight(1f))
            Text("On boot", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Switch(checked = s.enabledOnBoot, onCheckedChange = { onAutostart(s, it) }, enabled = busy == null)
        }
    }
}
