package app.truenascompanion.ui.iscsi

import app.truenascompanion.ui.components.GlowButton
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Lan
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SettingsInputComponent
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Link
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
import androidx.compose.material3.PrimaryScrollableTabRow
import app.truenascompanion.ui.components.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.truenascompanion.AppContainer
import app.truenascompanion.data.api.IscsiApi
import app.truenascompanion.data.api.userMessage
import app.truenascompanion.data.iscsi.ExtentType
import app.truenascompanion.data.iscsi.IscsiAuth
import app.truenascompanion.data.iscsi.IscsiData
import app.truenascompanion.data.iscsi.IscsiExtent
import app.truenascompanion.data.iscsi.IscsiInitiatorGroup
import app.truenascompanion.data.iscsi.IscsiLogic
import app.truenascompanion.data.iscsi.IscsiLun
import app.truenascompanion.data.iscsi.IscsiPortal
import app.truenascompanion.data.iscsi.IscsiTarget
import app.truenascompanion.data.iscsi.LunForm
import app.truenascompanion.data.model.Health
import app.truenascompanion.ui.appViewModel
import app.truenascompanion.ui.components.ConfirmDialog
import app.truenascompanion.ui.components.ElevatedSection
import app.truenascompanion.ui.components.EmptyState
import app.truenascompanion.ui.components.IconBadge
import app.truenascompanion.ui.components.InfoBanner
import app.truenascompanion.ui.components.PickField
import app.truenascompanion.ui.components.ScrollableErrorState
import app.truenascompanion.ui.components.SectionTitle
import app.truenascompanion.ui.components.SkeletonList
import app.truenascompanion.ui.components.StatusChip
import app.truenascompanion.ui.components.UiState
import app.truenascompanion.ui.components.isLoginRequired
import app.truenascompanion.util.Format
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/** What a delete confirmation is about. */
sealed interface IscsiDelete {
    data class Portal(val p: IscsiPortal) : IscsiDelete
    data class Initiator(val g: IscsiInitiatorGroup) : IscsiDelete
    data class Auth(val a: IscsiAuth) : IscsiDelete
    data class Target(val t: IscsiTarget) : IscsiDelete
    data class Extent(val e: IscsiExtent) : IscsiDelete
    data class Lun(val l: IscsiLun) : IscsiDelete
}

/** Options picked in a delete dialog. */
data class DeleteOptions(val force: Boolean = false, val deleteExtents: Boolean = false, val removeFile: Boolean = false)

/** How a delete dialog should look: blocked (explain only) or a confirmation with options. */
data class DeletePlan(
    val title: String,
    val text: String,
    val blocked: Boolean = false,
    /** Sessions connected: the user must tick "disconnect" (force) to continue. */
    val needsForce: String? = null,
    val deleteExtentsOption: String? = null,
    val removeFileOption: String? = null,
)

object IscsiDeletes {
    fun plan(x: IscsiDelete, d: IscsiData): DeletePlan = when (x) {
        is IscsiDelete.Portal -> {
            val users = IscsiLogic.targetsUsingPortal(x.p, d)
            val orphans = users.filter { t -> t.groups.all { it.portal == x.p.id } }
            DeletePlan("Delete ${x.p.label}?", buildString {
                append("Initiators can no longer connect through ${x.p.addresses}.")
                if (users.isNotEmpty()) append("\n\nTrueNAS also removes this portal from ${users.joinToString { it.name }}.")
                if (orphans.isNotEmpty()) append(" ${orphans.joinToString { it.name }} will then be unreachable until you add another portal.")
            })
        }
        is IscsiDelete.Initiator -> {
            val users = IscsiLogic.targetsUsingInitiator(x.g, d)
            if (users.isNotEmpty()) DeletePlan("${x.g.label} is in use", "Used by ${users.joinToString { it.name }}. Pick other initiators for those targets first.", blocked = true)
            else DeletePlan("Delete ${x.g.label}?", "This initiator group isn't used by any target.")
        }
        is IscsiDelete.Auth -> {
            val users = IscsiLogic.targetsBlockingAuthDelete(x.a, d)
            if (users.isNotEmpty()) DeletePlan("${x.a.user} is in use",
                "${x.a.user} is the last user of CHAP group ${x.a.tag}, which ${users.joinToString { it.name }} require. Change their authentication first.", blocked = true)
            else DeletePlan("Delete CHAP user ${x.a.user}?", "Initiators that log in as ${x.a.user} will be refused.")
        }
        is IscsiDelete.Target -> {
            val luns = d.lunsOf(x.t)
            val sessions = d.sessionsFor(x.t)
            DeletePlan("Delete target ${x.t.name}?", buildString {
                append("Initiators lose access to ${d.iqn(x.t)}.")
                if (luns.isNotEmpty()) append("\n\nIts ${luns.size} LUN${if (luns.size == 1) "" else "s"} (${luns.joinToString { "${it.lunid}: ${d.extent(it.extent)?.name ?: "?"}" }}) are unmapped.")
            },
                needsForce = sessions.takeIf { it.isNotEmpty() }?.let { "${it.size} initiator${if (it.size == 1) " is" else "s are"} connected (${it.joinToString { s -> s.initiatorAddr.ifBlank { s.initiator } }}). Disconnect and delete anyway" },
                deleteExtentsOption = luns.takeIf { it.isNotEmpty() }?.let { "Also delete its extents. The zvols and files themselves are kept." },
            )
        }
        is IscsiDelete.Extent -> {
            val lun = d.lunOf(x.e)
            val t = lun?.let { d.target(it.target) }
            val sessions = IscsiLogic.sessionsForExtent(x.e, d)
            DeletePlan("Delete extent ${x.e.name}?", buildString {
                append(if (t != null) "It's removed from ${t.name} (LUN ${lun.lunid}); initiators lose this disk." else "It isn't shared through any target.")
                if (x.e.type == ExtentType.DISK) append("\n\nThe zvol ${x.e.zvol} and its data are kept. Delete the zvol under Datasets if you no longer need it.")
                else append("\n\nThe file is kept unless you tick the box below.")
            },
                needsForce = sessions.takeIf { it.isNotEmpty() }?.let { "${it.size} initiator${if (it.size == 1) " is" else "s are"} connected to ${t?.name}. Disconnect and delete anyway" },
                removeFileOption = x.e.path?.takeIf { x.e.type == ExtentType.FILE }?.let { "Also delete the file $it and all data in it" },
            )
        }
        is IscsiDelete.Lun -> {
            val t = d.target(x.l.target); val e = d.extent(x.l.extent)
            val sessions = t?.let { d.sessionsFor(it) }.orEmpty()
            DeletePlan("Remove LUN ${x.l.lunid} from ${t?.name}?", "Initiators lose the disk ${e?.name ?: ""}. The extent and its data are kept.",
                needsForce = sessions.takeIf { it.isNotEmpty() }?.let { "${it.size} initiator${if (it.size == 1) " is" else "s are"} connected. Disconnect and remove anyway" })
        }
    }
}

class IscsiViewModel(private val c: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow<UiState<IscsiData>>(UiState.Loading)
    val state = _state.asStateFlow()
    private val _refreshing = MutableStateFlow(false)
    val refreshing = _refreshing.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    private val _lunError = MutableStateFlow<Map<String, String>>(emptyMap())
    val lunError = _lunError.asStateFlow()
    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()
    private var shown = false

    init { refresh() }

    /** Called each time the screen is shown; reloads after returning from an editor. */
    fun onShown() { if (shown) refresh(silent = true) else shown = true }

    fun refresh(silent: Boolean = false) = viewModelScope.launch {
        if (!silent && _state.value is UiState.Success) _refreshing.value = true
        try {
            _state.value = UiState.Success(c.repository.call { IscsiApi(it).load() })
        } catch (e: Throwable) {
            if (_state.value !is UiState.Success || !silent) _state.value = UiState.Error(e.userMessage(), e)
        } finally { _refreshing.value = false }
    }

    private fun act(done: String, block: suspend (IscsiApi) -> Unit) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            try {
                c.repository.call { block(IscsiApi(it)) }
                _messages.trySend(done)
                refresh(silent = true).join()
            } catch (e: Throwable) {
                _messages.trySend(e.userMessage())
            } finally { _busy.value = false }
        }
    }

    fun delete(x: IscsiDelete, o: DeleteOptions) = when (x) {
        is IscsiDelete.Portal -> act("Portal deleted") { it.deletePortal(x.p.id) }
        is IscsiDelete.Initiator -> act("Initiator group deleted") { it.deleteInitiator(x.g.id) }
        is IscsiDelete.Auth -> act("CHAP user deleted") { it.deleteAuth(x.a.id) }
        is IscsiDelete.Target -> act("Target deleted") { it.deleteTarget(x.t.id, o.force, o.deleteExtents) }
        is IscsiDelete.Extent -> act(if (o.removeFile) "Extent and file deleted" else "Extent deleted") { it.deleteExtent(x.e.id, o.removeFile && x.e.type == ExtentType.FILE, o.force) }
        is IscsiDelete.Lun -> act("LUN removed") { it.deleteLun(x.l.id, o.force) }
    }

    fun startService() = act("iSCSI service started") { it.startService() }

    fun clearLunError() { _lunError.value = emptyMap() }

    fun addLun(f: LunForm, onDone: () -> Unit) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            try {
                c.repository.call { IscsiApi(it).save("iscsi.targetextent", null, IscsiLogic.lunJson(f)) }
                _lunError.value = emptyMap(); onDone()
                _messages.trySend("LUN added")
                refresh(silent = true).join()
            } catch (e: Throwable) {
                val (mine, general) = app.truenascompanion.ui.tasks.splitFieldErrors(e, setOf("target", "extent", "lunid"), emptyMap())
                _lunError.value = mine + (general?.let { mapOf("general" to it) } ?: emptyMap())
            } finally { _busy.value = false }
        }
    }
}

enum class IscsiTab(val title: String) { TARGETS("Targets"), EXTENTS("Extents"), ACCESS("Access"), SESSIONS("Sessions") }

/** Callbacks from the iSCSI screens to navigation. */
data class IscsiNav(
    val onEdit: (IscsiKind, Int?) -> Unit = { _, _ -> },
    val onWizard: () -> Unit = {},
    val onSettings: () -> Unit = {},
    val onServices: () -> Unit = {},
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IscsiScreen(onBack: () -> Unit, nav: IscsiNav) {
    val vm = appViewModel("iscsi") { IscsiViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val lunError by vm.lunError.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(vm) { vm.messages.collect { snackbar.showSnackbar(it) } }
    LaunchedEffect(Unit) { vm.onShown() }
    var tab by rememberSaveable { mutableStateOf(IscsiTab.TARGETS) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("iSCSI") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
                actions = {
                    IconButton(onClick = nav.onWizard) { Icon(Icons.Rounded.AutoAwesome, "Share a block device") }
                    IconButton(onClick = nav.onSettings) { Icon(Icons.Rounded.Settings, "iSCSI settings") }
                    IconButton(onClick = { vm.refresh() }) { Icon(Icons.Rounded.Refresh, "Refresh") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            when (val s = state) {
                UiState.Loading -> SkeletonList(4, 120.dp)
                is UiState.Error -> ScrollableErrorState(s.message, s.isLoginRequired) { vm.refresh() }
                is UiState.Success -> PullToRefreshBox(isRefreshing = refreshing, onRefresh = { vm.refresh() }) {
                    IscsiContent(s.data, tab, { tab = it }, busy, nav, lunError,
                        onDelete = vm::delete, onStart = vm::startService, onAddLun = vm::addLun, onLunDialogClosed = vm::clearLunError)
                }
            }
        }
    }
}

/** The tabbed iSCSI content (stateless apart from dialog state; used by previews). */
@Composable
fun IscsiContent(
    d: IscsiData, tab: IscsiTab, onTab: (IscsiTab) -> Unit, busy: Boolean, nav: IscsiNav,
    lunError: Map<String, String> = emptyMap(),
    onDelete: (IscsiDelete, DeleteOptions) -> Unit = { _, _ -> },
    onStart: () -> Unit = {},
    onAddLun: (LunForm, () -> Unit) -> Unit = { _, _ -> },
    onLunDialogClosed: () -> Unit = {},
    initialDelete: IscsiDelete? = null,
) {
    var deleting by remember { mutableStateOf(initialDelete) }
    var lunFor by remember { mutableStateOf<IscsiTarget?>(null) }
    var confirmStart by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        PrimaryScrollableTabRow(selectedTabIndex = tab.ordinal, edgePadding = 8.dp) {
            IscsiTab.entries.forEach { t ->
                val n = when (t) {
                    IscsiTab.TARGETS -> d.targets.size; IscsiTab.EXTENTS -> d.extents.size
                    IscsiTab.ACCESS -> null; IscsiTab.SESSIONS -> d.sessions?.size
                }
                Tab(selected = tab == t, onClick = { onTab(t) }, text = { Text(if (n != null) "${t.title} ($n)" else t.title, maxLines = 1) })
            }
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).testTag("iscsi-list"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (d.serviceRunning == false) ServiceBanner(d, busy, onStart = { confirmStart = true }, onServices = nav.onServices)
            when (tab) {
                IscsiTab.TARGETS -> TargetsTab(d, busy, nav, onDelete = { deleting = it }, onAddLun = { lunFor = it })
                IscsiTab.EXTENTS -> ExtentsTab(d, busy, nav) { deleting = it }
                IscsiTab.ACCESS -> AccessTab(d, busy, nav) { deleting = it }
                IscsiTab.SESSIONS -> SessionsTab(d)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
    deleting?.let { x -> DeleteDialog(IscsiDeletes.plan(x, d), onDismiss = { deleting = null }) { o -> deleting = null; onDelete(x, o) } }
    lunFor?.let { t -> LunDialog(t, d, lunError, busy, onDismiss = { lunFor = null; onLunDialogClosed() }) { f -> onAddLun(f) { lunFor = null } } }
    if (confirmStart) StartServiceDialog(onDismiss = { confirmStart = false }) { confirmStart = false; onStart() }
}

@Composable
internal fun StartServiceDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) = ConfirmDialog(
    title = "Start the iSCSI service?", text = "TrueNAS starts the iSCSI service now and at every boot. Initiators can then connect to the enabled targets.",
    confirmLabel = "Start service", icon = Icons.Rounded.PlayArrow, onConfirm = onConfirm, onDismiss = onDismiss,
)

@Composable
private fun ServiceBanner(d: IscsiData, busy: Boolean, onStart: () -> Unit, onServices: () -> Unit) {
    ElevatedSection(contentPadding = 14.dp, modifier = Modifier.testTag("service-banner")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("The iSCSI service is stopped", style = MaterialTheme.typography.titleSmall)
                Text("Initiators can't connect until it runs.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            ServiceChip(d.serviceRunning)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onStart, enabled = !busy) { Icon(Icons.Rounded.PlayArrow, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Start") }
            TextButton(onClick = onServices) { Text("Open Services") }
        }
    }
}

@Composable
private fun RowActions(busy: Boolean, what: String, onEdit: () -> Unit, onDelete: () -> Unit) {
    IconButton(onClick = onEdit, enabled = !busy) { Icon(Icons.Rounded.Edit, "Edit $what") }
    IconButton(onClick = onDelete, enabled = !busy) { Icon(Icons.Rounded.Delete, "Delete $what") }
}

@Composable
private fun Mono(text: String, modifier: Modifier = Modifier) =
    Text(text, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = modifier)

@Composable
private fun Sub(text: String) = Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TargetsTab(d: IscsiData, busy: Boolean, nav: IscsiNav, onDelete: (IscsiDelete) -> Unit, onAddLun: (IscsiTarget) -> Unit) {
    @Suppress("DEPRECATION") val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    if (d.targets.isEmpty()) {
        EmptyState(Icons.Rounded.Storage, "No targets", "A target is what an initiator (a PC, hypervisor or server) connects to. The wizard sets everything up in one go.") {
            Button(onClick = nav.onWizard) { Text("Share a block device") }
        }
    }
    SectionTitle("Targets") {
        TextButton(onClick = { nav.onEdit(IscsiKind.TARGET, null) }, enabled = !busy) { Icon(Icons.Rounded.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Add target") }
    }
    val freeExtents = d.extents.filter { d.lunOf(it) == null }
    d.targets.sortedBy { it.name }.forEach { t ->
        val sessions = d.sessionsFor(t)
        ElevatedSection(contentPadding = 14.dp, modifier = Modifier.testTag("target-${t.name}")) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(Icons.Rounded.Storage)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(t.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    t.alias?.takeIf { it.isNotBlank() }?.let { Sub(it) }
                }
                if (sessions.isNotEmpty()) StatusChip(Health.HEALTHY, "${sessions.size} connected", showIcon = false)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Mono(d.iqn(t), Modifier.weight(1f))
                IconButton(onClick = { clipboard.setText(AnnotatedString(d.iqn(t))) }) { Icon(Icons.Rounded.ContentCopy, "Copy IQN", Modifier.size(18.dp)) }
            }
            if (t.groups.isEmpty()) Text("No portal group: unreachable", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            t.groups.forEach { g -> Sub(IscsiLogic.groupSummary(g, d) + (d.portal(g.portal)?.let { " · ${it.addresses}" } ?: "")) }
            if (t.authNetworks.isNotEmpty()) Sub("Networks: " + t.authNetworks.joinToString())
            HorizontalDivider(Modifier.padding(vertical = 6.dp))
            val luns = d.lunsOf(t)
            if (luns.isEmpty()) Sub("No LUNs yet: add an extent so initiators see a disk.")
            luns.forEach { l ->
                val e = d.extent(l.extent)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.testTag("lun-${t.name}-${l.lunid}")) {
                    Text("LUN ${l.lunid}", style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(56.dp))
                    Column(Modifier.weight(1f)) {
                        Text(e?.name ?: "Extent ${l.extent}", style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        e?.let { Sub(listOfNotNull(it.type.label, IscsiLogic.extentSize(it, d)?.let(Format::bytes), "ro".takeIf { _ -> it.ro }).joinToString(" · ")) }
                    }
                    IconButton(onClick = { onDelete(IscsiDelete.Lun(l)) }, enabled = !busy) { Icon(Icons.Rounded.Close, "Remove LUN ${l.lunid}") }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { onAddLun(t) }, enabled = !busy && freeExtents.isNotEmpty()) { Icon(Icons.Rounded.Link, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Add LUN") }
                Spacer(Modifier.weight(1f))
                RowActions(busy, t.name, { nav.onEdit(IscsiKind.TARGET, t.id) }, { onDelete(IscsiDelete.Target(t)) })
            }
        }
    }
}

@Composable
private fun ExtentsTab(d: IscsiData, busy: Boolean, nav: IscsiNav, onDelete: (IscsiDelete) -> Unit) {
    SectionTitle("Extents") {
        TextButton(onClick = { nav.onEdit(IscsiKind.EXTENT, null) }, enabled = !busy) { Icon(Icons.Rounded.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Add extent") }
    }
    if (d.extents.isEmpty()) Sub("An extent is the disk a target offers: a zvol or a file. Add one, then map it to a target as a LUN.")
    d.extents.sortedBy { it.name }.forEach { e ->
        val lun = d.lunOf(e)
        ElevatedSection(contentPadding = 14.dp, modifier = Modifier.testTag("extent-${e.name}")) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(if (e.type == ExtentType.DISK) Icons.Rounded.SettingsInputComponent else Icons.Rounded.Storage)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(e.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Mono(e.location)
                }
                if (!e.enabled) StatusChip(Health.WARNING, "Off", showIcon = false)
                else if (e.locked == true) StatusChip(Health.WARNING, "Locked", showIcon = false)
            }
            Sub(listOfNotNull(e.type.label, IscsiLogic.extentSize(e, d)?.let(Format::bytes), "${e.blocksize} B blocks", if (e.rpm == "SSD") "SSD" else if (e.rpm == "UNKNOWN") null else "${e.rpm} RPM",
                "read-only".takeIf { e.ro }, "Xen".takeIf { e.xen }).joinToString(" · "))
            Sub(lun?.let { "${d.target(it.target)?.name ?: "Target ${it.target}"} · LUN ${it.lunid}" } ?: "Not mapped to a target")
            Row { Spacer(Modifier.weight(1f)); RowActions(busy, e.name, { nav.onEdit(IscsiKind.EXTENT, e.id) }, { onDelete(IscsiDelete.Extent(e)) }) }
        }
    }
}

@Composable
private fun AccessTab(d: IscsiData, busy: Boolean, nav: IscsiNav, onDelete: (IscsiDelete) -> Unit) {
    @Composable fun AddButton(kind: IscsiKind) = TextButton(onClick = { nav.onEdit(kind, null) }, enabled = !busy, modifier = Modifier.testTag("add-${kind.name}")) {
        Icon(Icons.Rounded.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Add")
    }
    SectionTitle("Portals") { AddButton(IscsiKind.PORTAL) }
    if (d.portals.isEmpty()) Sub("A portal is the address and port initiators connect to.")
    d.portals.sortedBy { it.tag }.forEach { p ->
        val users = IscsiLogic.targetsUsingPortal(p, d)
        AccessRow(Icons.Rounded.Lan, p.label, p.addresses, if (users.isEmpty()) "Not used" else "Used by " + users.joinToString { it.name }, busy,
            { nav.onEdit(IscsiKind.PORTAL, p.id) }, { onDelete(IscsiDelete.Portal(p)) })
    }
    SectionTitle("Initiator groups") { AddButton(IscsiKind.INITIATOR) }
    if (d.initiators.isEmpty()) Sub("Initiator groups limit which computers may connect.")
    d.initiators.forEach { g ->
        val users = IscsiLogic.targetsUsingInitiator(g, d)
        AccessRow(Icons.Rounded.Groups, g.label, if (g.allowsAll) "Any initiator" else g.initiators.joinToString("\n"),
            if (users.isEmpty()) "Not used" else "Used by " + users.joinToString { it.name }, busy, { nav.onEdit(IscsiKind.INITIATOR, g.id) }, { onDelete(IscsiDelete.Initiator(g)) })
    }
    SectionTitle("CHAP users") { AddButton(IscsiKind.AUTH) }
    if (d.auths.isEmpty()) Sub("CHAP users make initiators log in with a secret. Targets choose them by group number.")
    d.auths.sortedWith(compareBy({ it.tag }, { it.user })).forEach { a ->
        val users = d.targets.filter { t -> t.groups.any { it.auth == a.tag && it.authmethod != app.truenascompanion.data.iscsi.IscsiAuthMethod.NONE } }
        AccessRow(Icons.Rounded.Key, a.user, "Group ${a.tag}" + (if (a.mutual) " · mutual (peer ${a.peeruser})" else "") +
            (if (a.discoveryAuth != "NONE") " · discovery ${a.discoveryAuth.replace('_', ' ').lowercase()}" else ""),
            if (users.isEmpty()) "Not used" else "Used by " + users.joinToString { it.name }, busy, { nav.onEdit(IscsiKind.AUTH, a.id) }, { onDelete(IscsiDelete.Auth(a)) })
    }
}

@Composable
private fun AccessRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, detail: String, usage: String, busy: Boolean, onEdit: () -> Unit, onDelete: () -> Unit) {
    ElevatedSection(contentPadding = 12.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(icon, size = 36.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Mono(detail)
                Sub(usage)
            }
            RowActions(busy, title, onEdit, onDelete)
        }
    }
}

@Composable
private fun SessionsTab(d: IscsiData) {
    val sessions = d.sessions
    when {
        sessions == null -> InfoBanner("Connected sessions couldn't be read from this TrueNAS.")
        sessions.isEmpty() -> EmptyState(Icons.Rounded.Lan, "No initiators connected", if (d.serviceRunning == false) "The iSCSI service is stopped." else "Connected computers show up here.")
        else -> sessions.groupBy { it.target }.forEach { (target, list) ->
            SectionTitle(d.targets.firstOrNull { d.iqn(it) == target }?.name ?: target.substringAfterLast(':'))
            list.forEach { s ->
                ElevatedSection(contentPadding = 12.dp) {
                    Text(s.initiator, style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace))
                    Sub(listOfNotNull(s.initiatorAddr.ifBlank { null }, "iSER".takeIf { s.iser }).joinToString(" · ").ifBlank { "Address unknown" })
                }
            }
        }
    }
}

/** Delete confirmation built from a [DeletePlan]; blocked plans only explain. */
@Composable
fun DeleteDialog(plan: DeletePlan, onDismiss: () -> Unit, onConfirm: (DeleteOptions) -> Unit) {
    if (plan.blocked) {
        AlertDialog(onDismissRequest = onDismiss, title = { Text(plan.title) }, text = { Text(plan.text) }, confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } })
        return
    }
    var o by remember { mutableStateOf(DeleteOptions()) }
    val guard = app.truenascompanion.ui.lock.LocalDangerGuard.current
    val canConfirm = plan.needsForce == null || o.force
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Delete, null) },
        title = { Text(plan.title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(plan.text)
                plan.deleteExtentsOption?.let { CheckRow(it, o.deleteExtents, "opt-extents") { v -> o = o.copy(deleteExtents = v) } }
                plan.removeFileOption?.let { CheckRow(it, o.removeFile, "opt-file", danger = true) { v -> o = o.copy(removeFile = v) } }
                plan.needsForce?.let { CheckRow(it, o.force, "opt-force", danger = true) { v -> o = o.copy(force = v) } }
            }
        },
        confirmButton = {
            Button(
                onClick = { guard.guard(plan.title) { onConfirm(o) } }, enabled = canConfirm, modifier = Modifier.testTag("confirm-delete"),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
            ) { Text(if (plan.title.startsWith("Remove")) "Remove" else "Delete") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun CheckRow(text: String, checked: Boolean, tag: String, danger: Boolean = false, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp).toggleable(checked, role = Role.Checkbox, onValueChange = onChange).testTag(tag)) {
        Checkbox(checked, null)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = if (danger && checked) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
    }
}

/** Maps a free extent to [target] as a LUN (number optional: TrueNAS picks the next free one). */
@Composable
private fun LunDialog(target: IscsiTarget, d: IscsiData, serverErrors: Map<String, String>, busy: Boolean, onDismiss: () -> Unit, onSave: (LunForm) -> Unit) {
    var f by remember { mutableStateOf(LunForm(target = target.id, extent = d.extents.firstOrNull { d.lunOf(it) == null }?.id)) }
    val errors = IscsiLogic.lunErrors(f, d, null)
    val free = d.extents.filter { d.lunOf(it) == null }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add a LUN to ${target.name}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                serverErrors["general"]?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                PickField("Extent", d.extent(f.extent ?: -1), free, { "${it.name} · ${it.type.label}" }, onPick = { f = f.copy(extent = it.id) },
                    isError = (errors["extent"] ?: serverErrors["extent"]) != null, supporting = errors["extent"] ?: serverErrors["extent"])
                val used = d.lunsOf(target).map { it.lunid }
                Field(f.lunid, { v -> f = f.copy(lunid = v.filter(Char::isDigit).take(5)) }, "LUN number", errors["lunid"] ?: serverErrors["lunid"],
                    "Empty: next free" + if (used.isNotEmpty()) " (taken: ${used.joinToString()})" else "", number = true)
            }
        },
        confirmButton = { GlowButton(onClick = { onSave(f) }, enabled = errors.isEmpty() && !busy) { Text("Add") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// ---------------- Storage › Shares › iSCSI ----------------

/** Compact iSCSI summary shown as the third Shares sub-tab. */
@Composable
fun IscsiSharesTab(onOpen: () -> Unit, onWizard: () -> Unit, onServices: () -> Unit) {
    val vm = appViewModel("iscsi-shares") { IscsiViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.onShown() }
    when (val s = state) {
        UiState.Loading -> SkeletonList(2, 120.dp)
        is UiState.Error -> ScrollableErrorState(s.message, s.isLoginRequired) { vm.refresh() }
        is UiState.Success -> IscsiSummary(s.data, busy, onStart = vm::startService, onOpen = onOpen, onWizard = onWizard, onServices = onServices)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun IscsiSummary(d: IscsiData, busy: Boolean, onStart: () -> Unit, onOpen: () -> Unit, onWizard: () -> Unit, onServices: () -> Unit) {
    var confirmStart by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).testTag("iscsi-summary"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ElevatedSection(contentPadding = 16.dp, onClick = onOpen) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(Icons.Rounded.SettingsInputComponent)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("iSCSI block shares", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Sub("${d.targets.size} target${if (d.targets.size == 1) "" else "s"} · ${d.extents.size} extent${if (d.extents.size == 1) "" else "s"}" +
                        (d.sessions?.let { " · ${it.size} connected" } ?: ""))
                }
                ServiceChip(d.serviceRunning)
            }
            if (d.serviceRunning == false) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                Text("Initiators can't connect while the service is stopped.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                TextButton(onClick = { confirmStart = true }, enabled = !busy, modifier = Modifier.testTag("start-iscsi")) { Text("Start") }
            }
            Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onOpen, modifier = Modifier.weight(1f)) { Text("Open iSCSI") }
                OutlinedButton(onClick = onWizard, modifier = Modifier.weight(1f)) { Text("Share a disk", maxLines = 1) }
            }
            TextButton(onClick = onServices, modifier = Modifier.testTag("open-services")) { Text("Service settings in Services") }
        }
        if (d.targets.isNotEmpty()) {
            SectionTitle("Targets")
            d.targets.sortedBy { it.name }.forEach { t ->
                val n = d.sessionsFor(t).size
                ElevatedSection(contentPadding = 12.dp, onClick = onOpen) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(t.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Sub(d.lunsOf(t).joinToString { l -> d.extent(l.extent)?.let { e -> e.name + (IscsiLogic.extentSize(e, d)?.let { " (${Format.bytes(it)})" } ?: "") } ?: "?" }.ifBlank { "No LUNs" })
                        }
                        if (n > 0) StatusChip(Health.HEALTHY, "$n connected", showIcon = false)
                    }
                }
            }
        } else Box(Modifier.fillMaxWidth()) {
            Sub("Share a zvol or a file as a disk that a PC, hypervisor or server mounts over the network. \"Share a disk\" sets up the extent, portal, access and target in one go.")
        }
    }
    if (confirmStart) StartServiceDialog(onDismiss = { confirmStart = false }) { confirmStart = false; onStart() }
}
