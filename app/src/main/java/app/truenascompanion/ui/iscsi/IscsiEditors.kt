package app.truenascompanion.ui.iscsi

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.truenascompanion.AppContainer
import app.truenascompanion.data.api.IscsiApi
import app.truenascompanion.data.api.userMessage
import app.truenascompanion.data.iscsi.AuthForm
import app.truenascompanion.data.iscsi.ExtentForm
import app.truenascompanion.data.iscsi.ExtentType
import app.truenascompanion.data.iscsi.GlobalForm
import app.truenascompanion.data.iscsi.InitiatorForm
import app.truenascompanion.data.iscsi.IscsiAuthMethod
import app.truenascompanion.data.iscsi.IscsiData
import app.truenascompanion.data.iscsi.IscsiGroup
import app.truenascompanion.data.iscsi.IscsiLogic
import app.truenascompanion.data.iscsi.PortalForm
import app.truenascompanion.data.iscsi.SizeUnit
import app.truenascompanion.data.iscsi.TargetForm
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
import app.truenascompanion.ui.components.StatusChip
import app.truenascompanion.ui.components.UiState
import app.truenascompanion.ui.components.isLoginRequired
import app.truenascompanion.ui.protection.SwitchRow
import app.truenascompanion.ui.tasks.splitFieldErrors
import app.truenascompanion.util.Format
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/** The kinds of iSCSI objects with an editor screen (route `iscsi_edit/{kind}?id=`). */
enum class IscsiKind(val title: String) { TARGET("target"), EXTENT("extent"), PORTAL("portal"), INITIATOR("initiator group"), AUTH("CHAP user") }

/**
 * One editor view model for every iSCSI form: loads [IscsiData], builds the form with [init] (throws when the object is
 * gone), checks it with [errorsOf] and saves with [saveBlock]. Server field errors land under their field.
 */
class IscsiFormViewModel<F : Any>(
    private val c: AppContainer,
    private val init: (IscsiData) -> F,
    val errorsOf: (F, IscsiData) -> Map<String, String>,
    private val fields: Set<String>,
    private val aliases: Map<String, String>,
    private val saveBlock: suspend (IscsiApi, F, IscsiData) -> Unit,
) : ViewModel() {
    private val _state = MutableStateFlow<UiState<Pair<IscsiData, F>>>(UiState.Loading)
    val state = _state.asStateFlow()
    private val _form = MutableStateFlow<F?>(null)
    val form = _form.asStateFlow()
    private val _serverErrors = MutableStateFlow<Map<String, String>>(emptyMap())
    val serverErrors = _serverErrors.asStateFlow()
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
            val d = c.repository.call { IscsiApi(it).load() }
            val f = init(d)
            _form.value = f
            _state.value = UiState.Success(d to f)
        } catch (e: Throwable) {
            _state.value = UiState.Error(e.userMessage(), e)
        }
    }

    fun edit(change: (F) -> F) {
        _form.value = _form.value?.let(change)
        _serverErrors.value = emptyMap(); _general.value = null
    }

    fun save() {
        val f = _form.value ?: return
        val d = (_state.value as? UiState.Success)?.data?.first ?: return
        if (errorsOf(f, d).isNotEmpty() || _saving.value) return
        _saving.value = true
        viewModelScope.launch {
            try {
                c.repository.call { saveBlock(IscsiApi(it), f, d) }
                _saved.trySend(Unit)
            } catch (e: Throwable) {
                val (mine, general) = splitFieldErrors(e, fields, aliases)
                _serverErrors.value = mine; _general.value = general
            } finally {
                _saving.value = false
            }
        }
    }
}

/** Shared scaffold: title, Save, discard confirmation, loading and error states. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <F : Any> IscsiFormScreen(
    title: String,
    isNew: Boolean,
    vm: IscsiFormViewModel<F>,
    onBack: () -> Unit,
    content: @Composable (data: IscsiData, form: F, errors: Map<String, String>, general: String?, showErrors: Boolean, onChange: ((F) -> F) -> Unit) -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val form by vm.form.collectAsStateWithLifecycle()
    val serverErrors by vm.serverErrors.collectAsStateWithLifecycle()
    val general by vm.general.collectAsStateWithLifecycle()
    val saving by vm.saving.collectAsStateWithLifecycle()
    LaunchedEffect(vm) { vm.saved.collect { onBack() } }
    val loaded = (state as? UiState.Success)?.data
    val dirty = loaded != null && loaded.second != form
    val local = if (loaded != null && form != null) vm.errorsOf(form!!, loaded.first) else emptyMap()
    var confirmDiscard by remember { mutableStateOf(false) }
    BackHandler(enabled = dirty) { confirmDiscard = true }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = { IconButton(onClick = { if (dirty) confirmDiscard = true else onBack() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
                actions = {
                    if (saving) CircularProgressIndicator(Modifier.size(22.dp).padding(end = 4.dp), strokeWidth = 2.dp)
                    TextButton(onClick = vm::save, enabled = (dirty || isNew) && !saving && local.isEmpty() && loaded != null) { Text("Save") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            when (val s = state) {
                UiState.Loading -> SkeletonList(3, 140.dp)
                is UiState.Error -> ScrollableErrorState(s.message, s.isLoginRequired) { vm.load() }
                is UiState.Success -> form?.let { f -> content(s.data.first, f, local + serverErrors, general, dirty || serverErrors.isNotEmpty(), vm::edit) }
            }
        }
    }
    if (confirmDiscard) ConfirmDialog(
        title = "Discard changes?", text = "Your changes haven't been saved.", confirmLabel = "Discard", destructive = true, requireAuth = false,
        onConfirm = { confirmDiscard = false; onBack() }, onDismiss = { confirmDiscard = false },
    )
}

private fun missing(what: String): Nothing = throw IllegalStateException("This $what no longer exists")

/** Editor for one iSCSI object ([id] null: new). */
@Composable
fun IscsiEditorScreen(kind: IscsiKind, id: Int?, onBack: () -> Unit) {
    val key = "iscsi-${kind.name}:${id ?: "new"}"
    val title = (if (id == null) "New " else "Edit ") + kind.title
    when (kind) {
        IscsiKind.PORTAL -> {
            val vm = appViewModel(key) { c ->
                IscsiFormViewModel(c, { d -> id?.let { i -> IscsiLogic.portalForm(d.portals.firstOrNull { it.id == i } ?: missing("portal")) } ?: PortalForm() },
                    { f, d -> IscsiLogic.portalErrors(f, d, id) }, IscsiLogic.PORTAL_FIELDS, emptyMap(),
                    { a, f, _ -> a.save("iscsi.portal", id, IscsiLogic.portalJson(f)) })
            }
            IscsiFormScreen(title, id == null, vm, onBack) { d, f, e, g, show, on -> PortalContent(f, d, e, g, show, on) }
        }
        IscsiKind.INITIATOR -> {
            val vm = appViewModel(key) { c ->
                IscsiFormViewModel(c, { d -> id?.let { i -> IscsiLogic.initiatorForm(d.initiators.firstOrNull { it.id == i } ?: missing("initiator group")) } ?: InitiatorForm() },
                    { f, _ -> IscsiLogic.initiatorErrors(f) }, IscsiLogic.INITIATOR_FIELDS, emptyMap(),
                    { a, f, _ -> a.save("iscsi.initiator", id, IscsiLogic.initiatorJson(f)) })
            }
            IscsiFormScreen(title, id == null, vm, onBack) { _, f, e, g, show, on -> InitiatorContent(f, e, g, show, on) }
        }
        IscsiKind.AUTH -> {
            val vm = appViewModel(key) { c ->
                IscsiFormViewModel(c, { d -> id?.let { i -> IscsiLogic.authForm(d.auths.firstOrNull { it.id == i } ?: missing("CHAP user")) } ?: AuthForm(tag = IscsiLogic.nextAuthTag(d).toString()) },
                    { f, d -> IscsiLogic.authErrors(f, d, id) }, IscsiLogic.AUTH_FIELDS, emptyMap(),
                    { a, f, _ -> a.save("iscsi.auth", id, IscsiLogic.authJson(f, editing = id != null)) })
            }
            IscsiFormScreen(title, id == null, vm, onBack) { d, f, e, g, show, on -> AuthContent(f, d, id != null, e, g, show, on) }
        }
        IscsiKind.TARGET -> {
            val vm = appViewModel(key) { c ->
                IscsiFormViewModel(c, { d ->
                    id?.let { i -> IscsiLogic.targetForm(d.target(i) ?: missing("target")) } ?: TargetForm(groups = listOf(IscsiGroup(d.portals.singleOrNull()?.id)))
                }, { f, d -> IscsiLogic.targetErrors(f, d, id) }, IscsiLogic.TARGET_FIELDS, emptyMap(),
                    { a, f, _ -> a.save("iscsi.target", id, IscsiLogic.targetJson(f)) })
            }
            IscsiFormScreen(title, id == null, vm, onBack) { d, f, e, g, show, on -> TargetContent(f, d, e, g, show, on) }
        }
        IscsiKind.EXTENT -> {
            val vm = appViewModel(key) { c ->
                IscsiFormViewModel(c, { d -> id?.let { i -> IscsiLogic.extentForm(d.extent(i) ?: missing("extent")) } ?: ExtentForm() },
                    { f, d -> IscsiLogic.extentErrors(f, d, id) }, IscsiLogic.EXTENT_FIELDS, IscsiLogic.EXTENT_ALIASES,
                    { a, f, _ -> a.save("iscsi.extent", id, IscsiLogic.extentJson(f, editing = id != null)) })
            }
            IscsiFormScreen(title, id == null, vm, onBack) { d, f, e, g, show, on -> ExtentContent(f, d, id, e, g, show, on) }
        }
    }
}

@Composable
fun IscsiSettingsScreen(onBack: () -> Unit, onServices: () -> Unit) {
    val vm = appViewModel("iscsi-settings") { c ->
        IscsiFormViewModel(c, { d -> IscsiLogic.globalForm(d.global) }, { f, _ -> IscsiLogic.globalErrors(f) }, IscsiLogic.GLOBAL_FIELDS, emptyMap(),
            { a, f, d -> a.updateGlobal(IscsiLogic.globalJson(f, d.global, d.haLicensed)) })
    }
    IscsiFormScreen("iSCSI settings", false, vm, onBack) { d, f, e, g, show, on -> IscsiSettingsContent(f, d, e, g, show, on, onServices) }
}

// ---------------- stateless forms ----------------

@Composable
private fun FormColumn(general: String?, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(16.dp).testTag("page"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        general?.let { InfoBanner(it, health = Health.CRITICAL) }
        content()
    }
}

/** Outlined text field used by the iSCSI forms; full width unless [modifier] says otherwise (e.g. a weighted size field). */
@Suppress("ModifierParameter")
@Composable
internal fun Field(
    value: String, onValue: (String) -> Unit, label: String, error: String?, help: String? = null, placeholder: String? = null,
    number: Boolean = false, mono: Boolean = false, lines: Int = 1, modifier: Modifier = Modifier.fillMaxWidth(),
) = OutlinedTextField(
    value, onValue, label = { Text(label, maxLines = 1) }, singleLine = lines == 1, minLines = lines, maxLines = if (lines == 1) 1 else 8,
    isError = error != null, placeholder = placeholder?.let { { Text(it) } },
    supportingText = (error ?: help)?.let { { Text(it) } },
    keyboardOptions = if (number) KeyboardOptions(keyboardType = KeyboardType.Number) else KeyboardOptions.Default,
    textStyle = if (mono) MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace) else MaterialTheme.typography.bodyLarge,
    modifier = modifier,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun IscsiSettingsContent(
    form: GlobalForm, data: IscsiData, errors: Map<String, String>, general: String?, showErrors: Boolean,
    onChange: ((GlobalForm) -> GlobalForm) -> Unit, onServices: () -> Unit,
) {
    fun err(k: String) = errors[k]?.takeIf { showErrors }
    FormColumn(general) {
        ElevatedSection(contentPadding = 14.dp) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("iSCSI service", style = MaterialTheme.typography.titleSmall)
                    Text(if (data.serviceOnBoot == true) "Starts at boot" else "Doesn't start at boot", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                ServiceChip(data.serviceRunning)
            }
            TextButton(onClick = onServices, modifier = Modifier.testTag("open-services")) { Text("Start, stop or restart in Services") }
        }
        SectionTitle("Target global configuration")
        ElevatedSection(contentPadding = 14.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Field(form.basename, { v -> onChange { it.copy(basename = v.trim()) } }, "Base name *", err("basename"),
                    "Targets are named <base name>:<target>, e.g. ${form.basename.ifBlank { "iqn.2005-10.org.freenas.ctl" }}:vm-disks", mono = true)
                Field(form.listenPort, { v -> onChange { it.copy(listenPort = v.filter(Char::isDigit).take(5)) } }, "Listen port", err("listen_port"),
                    "Default 3260. Used by every portal.", number = true)
                Field(form.threshold, { v -> onChange { it.copy(threshold = v.filter(Char::isDigit).take(2)) } }, "Pool space alert (%)", err("pool_avail_threshold"),
                    "Alert when a pool with extents has less free space than this. Empty: no alert.", number = true)
                Field(form.isns, { v -> onChange { it.copy(isns = v) } }, "iSNS servers", err("isns_servers"), "One IP or IP:port per line (port 3205 by default)", mono = true, lines = 2)
                if (data.haLicensed) SwitchRow("ALUA", form.alua, "Asymmetric logical unit access for HA systems. Configure it on the initiators too.") { v -> onChange { it.copy(alua = v) } }
                else Text("ALUA and iSER are only available on TrueNAS Enterprise high-availability systems.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
internal fun ServiceChip(running: Boolean?) = StatusChip(
    when (running) { true -> Health.HEALTHY; false -> Health.WARNING; null -> Health.UNKNOWN },
    when (running) { true -> "Running"; false -> "Stopped"; null -> "Unknown" }, showIcon = false,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PortalContent(form: PortalForm, data: IscsiData, errors: Map<String, String>, general: String?, showErrors: Boolean, onChange: ((PortalForm) -> PortalForm) -> Unit) {
    fun err(k: String) = errors[k]?.takeIf { showErrors }
    FormColumn(general) {
        ElevatedSection(contentPadding = 14.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Field(form.comment, { v -> onChange { it.copy(comment = v) } }, "Description", null, "Shown instead of \"Portal n\"")
            }
        }
        SectionTitle("Listen on")
        ElevatedSection(contentPadding = 14.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Port ${data.global.listenPort} (change it in iSCSI settings). 0.0.0.0 listens on every IPv4 address.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("ip-choices")) {
                    (data.listenChoices + form.ips).distinct().forEach { ip ->
                        FilterChip(selected = ip in form.ips, onClick = { onChange { f -> f.copy(ips = if (ip in f.ips) f.ips - ip else f.ips + ip) } }, label = { Text(ip) })
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Field(form.newIp, { v -> onChange { it.copy(newIp = v.trim()) } }, "Other address", err("new_ip"), modifier = Modifier.weight(1f))
                    TextButton(onClick = { onChange { f -> f.copy(ips = (f.ips + f.newIp.trim()).distinct(), newIp = "") } }, enabled = IscsiLogic.isIp(form.newIp.trim())) { Text("Add") }
                }
                err("listen")?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

@Composable
fun InitiatorContent(form: InitiatorForm, errors: Map<String, String>, general: String?, showErrors: Boolean, onChange: ((InitiatorForm) -> InitiatorForm) -> Unit) {
    fun err(k: String) = errors[k]?.takeIf { showErrors }
    FormColumn(general) {
        ElevatedSection(contentPadding = 14.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Field(form.comment, { v -> onChange { it.copy(comment = v) } }, "Description", null)
                SwitchRow("Allow any initiator", form.allowAll, "Any computer that can reach the portal may connect (CHAP can still be required)") { v -> onChange { it.copy(allowAll = v) } }
                if (!form.allowAll) Field(form.initiators, { v -> onChange { it.copy(initiators = v) } }, "Allowed initiators *", err("initiators"),
                    "One IQN (iqn.1991-05.com.microsoft:pc1) or address per line", mono = true, lines = 3)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AuthContent(form: AuthForm, data: IscsiData, editing: Boolean, errors: Map<String, String>, general: String?, showErrors: Boolean, onChange: ((AuthForm) -> AuthForm) -> Unit) {
    fun err(k: String) = errors[k]?.takeIf { showErrors }
    FormColumn(general) {
        ElevatedSection(contentPadding = 14.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Field(form.tag, { v -> onChange { it.copy(tag = v.filter(Char::isDigit).take(5)) } }, "Group number *", err("tag"),
                    "Targets pick CHAP users by this number; users with the same number form one group", number = true)
                Field(form.user, { v -> onChange { it.copy(user = v.trim()) } }, "User *", err("user"))
                SecretTextField(form.secret, { v -> onChange { it.copy(secret = v) } }, "Secret *", isError = err("secret") != null,
                    supporting = err("secret") ?: "12–16 characters, no #", hiddenByServer = editing && form.secretHidden)
            }
        }
        SectionTitle("Mutual CHAP")
        ElevatedSection(contentPadding = 14.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SwitchRow("The target proves itself too", form.mutual, "The initiator checks the NAS with a second user and secret") { v -> onChange { it.copy(mutual = v) } }
                if (form.mutual) {
                    Field(form.peeruser, { v -> onChange { it.copy(peeruser = v.trim()) } }, "Peer user *", err("peeruser"))
                    SecretTextField(form.peersecret, { v -> onChange { it.copy(peersecret = v) } }, "Peer secret *", isError = err("peersecret") != null,
                        supporting = err("peersecret") ?: "12–16 characters, different from the secret", hiddenByServer = editing && form.peerSecretHidden)
                }
                Text("Discovery authentication", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IscsiAuthMethod.entries.forEach { m ->
                        FilterChip(selected = form.discoveryAuth == m, onClick = { onChange { it.copy(discoveryAuth = m) } }, label = { Text(m.label) })
                    }
                }
                Text(err("discovery_auth") ?: "Requires CHAP before initiators can even list the targets. Mutual CHAP discovery is allowed for one entry only.",
                    style = MaterialTheme.typography.bodySmall, color = if (err("discovery_auth") != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        @Suppress("UNUSED_VARIABLE") val unused = data
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TargetContent(form: TargetForm, data: IscsiData, errors: Map<String, String>, general: String?, showErrors: Boolean, onChange: ((TargetForm) -> TargetForm) -> Unit) {
    fun err(k: String) = errors[k]?.takeIf { showErrors }
    FormColumn(general) {
        ElevatedSection(contentPadding = 14.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Field(form.name, { v -> onChange { it.copy(name = v.lowercase().trim()) } }, "Name *", err("name"),
                    "Initiators see ${IscsiLogic.iqn(data.global.basename, form.name.ifBlank { "name" })}", mono = true)
                Field(form.alias, { v -> onChange { it.copy(alias = v) } }, "Alias", err("alias"), "A friendly name some initiators show")
                if (form.mode != "ISCSI") Text("Mode: ${form.mode} (Fibre Channel, kept as is)", style = MaterialTheme.typography.bodySmall)
            }
        }
        SectionTitle("Portal groups") {
            TextButton(onClick = { onChange { it.copy(groups = it.groups + IscsiGroup(null)) } }, modifier = Modifier.testTag("add-group")) {
                Icon(Icons.Rounded.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Add")
            }
        }
        if (form.groups.isEmpty()) InfoBanner("Without a portal group, no initiator can reach this target.")
        form.groups.forEachIndexed { i, g ->
            fun set(ng: IscsiGroup) = onChange { f -> f.copy(groups = f.groups.toMutableList().also { it[i] = ng }) }
            ElevatedSection(contentPadding = 14.dp, modifier = Modifier.testTag("group-$i")) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Group ${i + 1}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        IconButton(onClick = { onChange { f -> f.copy(groups = f.groups.filterIndexed { j, _ -> j != i }) } }) { Icon(Icons.Rounded.Delete, "Remove group ${i + 1}") }
                    }
                    PickField("Portal *", data.portal(g.portal), data.portals, { "${it.label} · ${it.addresses}" }, onPick = { set(g.copy(portal = it.id)) },
                        isError = err("portal") != null && (g.portal == null || form.groups.count { it.portal == g.portal } > 1), supporting = err("portal")?.takeIf { g.portal == null || form.groups.count { it.portal == g.portal } > 1 },
                        emptyText = if (data.portals.isEmpty()) "Add a portal first (Access tab)" else "Choose a portal")
                    PickField("Initiators", g.initiator, listOf<Int?>(null) + data.initiators.map { it.id }, { id -> id?.let { data.initiator(it)?.label } ?: "Any initiator" },
                        onPick = { set(g.copy(initiator = it)) }, emptyText = "Any initiator")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        IscsiAuthMethod.entries.forEach { m -> FilterChip(selected = g.authmethod == m, onClick = { set(g.copy(authmethod = m)) }, label = { Text(m.label) }) }
                    }
                    if (g.authmethod != IscsiAuthMethod.NONE) PickField("CHAP group *", g.auth, data.authTags, { t -> "Group $t · " + data.authsWithTag(t).joinToString { it.user } },
                        onPick = { set(g.copy(auth = it)) }, isError = err("auth") != null, supporting = err("auth"),
                        emptyText = if (data.auths.isEmpty()) "Add a CHAP user first (Access tab)" else "Choose a CHAP group")
                }
            }
        }
        SectionTitle("Allowed networks")
        ElevatedSection(contentPadding = 14.dp) {
            Field(form.authNetworks, { v -> onChange { it.copy(authNetworks = v) } }, "Networks", err("auth_networks"),
                "One network per line, e.g. 192.168.1.0/24. Empty: any network.", mono = true, lines = 2)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ExtentContent(form: ExtentForm, data: IscsiData, editing: Int?, errors: Map<String, String>, general: String?, showErrors: Boolean, onChange: ((ExtentForm) -> ExtentForm) -> Unit) {
    fun err(k: String) = errors[k]?.takeIf { showErrors }
    var advanced by rememberSaveable { mutableStateOf(false) }
    FormColumn(general) {
        ElevatedSection(contentPadding = 14.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Field(form.name, { v -> onChange { it.copy(name = v) } }, "Name *", err("name"))
                if (editing == null) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ExtentType.entries.forEach { t -> FilterChip(selected = form.type == t, onClick = { onChange { it.copy(type = t) } }, label = { Text(t.label) }) }
                }
                if (form.type == ExtentType.DISK) {
                    val zvols = data.freeZvols(except = editing?.let { data.extent(it)?.zvol })
                    PickField("Zvol *", zvols.firstOrNull { it.id == form.zvol }, zvols, { z -> z.id + (z.volsize?.let { " (${Format.bytes(it)})" } ?: "") },
                        onPick = { z -> onChange { it.copy(zvol = z.id, name = it.name.ifBlank { z.shortName }) } },
                        isError = err("disk") != null, supporting = err("disk") ?: "Zvols not used by another extent. Create one in Datasets.",
                        emptyText = if (zvols.isEmpty()) "No free zvols" else "Choose a zvol")
                } else {
                    Field(form.path, { v -> onChange { it.copy(path = v.trim()) } }, "File *", err("path"), "Created if it doesn't exist", placeholder = "/mnt/tank/iscsi/disk1.img", mono = true)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                        Field(form.size, { v -> onChange { it.copy(size = v.filter { c -> c.isDigit() || c == '.' }.take(10)) } }, "Size", err("filesize"),
                            if (form.originalSize != null) "Can only grow" else "0: use the existing file's size", number = true, modifier = Modifier.weight(1f))
                        PickField("Unit", form.unit, SizeUnit.entries, { it.name }, onPick = { u -> onChange { it.copy(unit = u) } }, modifier = Modifier.width(110.dp))
                    }
                }
            }
        }
        SectionTitle("Device")
        ElevatedSection(contentPadding = 14.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Logical block size", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IscsiLogic.BLOCKSIZES.forEach { b -> FilterChip(selected = form.blocksize == b, onClick = { onChange { it.copy(blocksize = b) } }, label = { Text("$b") }) }
                }
                Text("512 suits most systems; changing it on a disk in use can make its data unreadable.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                SwitchRow("Hide the physical block size", form.pblocksize, "Turn on for initiators that don't cope with 16 KiB physical blocks (older VMware)") { v -> onChange { it.copy(pblocksize = v) } }
                PickField("Reported speed", form.rpm, IscsiLogic.RPMS, { if (it == "SSD" || it == "UNKNOWN") it.lowercase().replaceFirstChar(Char::uppercase).let { s -> if (s == "Ssd") "SSD" else s } else "$it RPM" },
                    onPick = { r -> onChange { it.copy(rpm = r) } })
                SwitchRow("Read-only", form.ro, err("ro") ?: "Initiators can't write to this disk") { v -> onChange { it.copy(ro = v) } }
                SwitchRow("Enabled", form.enabled, "Turned-off extents aren't offered to initiators") { v -> onChange { it.copy(enabled = v) } }
                SwitchRow("Xen compatibility", form.xen, "Only when Xen is the initiator") { v -> onChange { it.copy(xen = v) } }
                SwitchRow("Allow copies between targets (insecure TPC)", form.insecureTpc, "Lets initiators offload copies (XCOPY), e.g. VMware; bypasses access control for those copies") { v -> onChange { it.copy(insecureTpc = v) } }
            }
        }
        TextButton(onClick = { advanced = !advanced }, modifier = Modifier.testTag("extent-advanced")) { Text(if (advanced) "Hide advanced options" else "Advanced options") }
        if (advanced) ElevatedSection(contentPadding = 14.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Field(form.serial, { v -> onChange { it.copy(serial = v.trim()) } }, "Serial", err("serial"), if (editing == null) "Empty: TrueNAS picks one" else null, mono = true)
                Field(form.productId, { v -> onChange { it.copy(productId = v) } }, "Product ID", err("product_id"), "Empty: \"iSCSI Disk\"")
                Field(form.availThreshold, { v -> onChange { it.copy(availThreshold = v.filter(Char::isDigit).take(2)) } }, "Space alert (%)", err("avail_threshold"),
                    "Alert when the dataset or pool has less free space than this", number = true)
                Field(form.comment, { v -> onChange { it.copy(comment = v) } }, "Comment", err("comment"))
            }
        }
    }
}

