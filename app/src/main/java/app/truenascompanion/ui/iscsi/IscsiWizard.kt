package app.truenascompanion.ui.iscsi

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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.truenascompanion.AppContainer
import app.truenascompanion.data.api.IscsiApi
import app.truenascompanion.data.api.StepState
import app.truenascompanion.data.api.WizardResult
import app.truenascompanion.data.api.WizardStep
import app.truenascompanion.data.api.userMessage
import app.truenascompanion.data.iscsi.IscsiData
import app.truenascompanion.data.iscsi.IscsiLogic
import app.truenascompanion.data.iscsi.SharingPlatform
import app.truenascompanion.data.iscsi.SizeUnit
import app.truenascompanion.data.iscsi.WizardChap
import app.truenascompanion.data.iscsi.WizardExtent
import app.truenascompanion.data.iscsi.WizardForm
import app.truenascompanion.data.iscsi.WizardInitiators
import app.truenascompanion.data.model.Health
import app.truenascompanion.ui.appViewModel
import app.truenascompanion.ui.components.ElevatedSection
import app.truenascompanion.ui.components.InfoBanner
import app.truenascompanion.ui.theme.LocalStatusColors
import app.truenascompanion.ui.components.PickField
import app.truenascompanion.ui.components.ScrollableErrorState
import app.truenascompanion.ui.components.SecretTextField
import app.truenascompanion.ui.components.SectionTitle
import app.truenascompanion.ui.components.SkeletonList
import app.truenascompanion.ui.components.UiState
import app.truenascompanion.ui.components.isLoginRequired
import app.truenascompanion.ui.protection.SwitchRow
import app.truenascompanion.util.Format
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Progress of a wizard run: the steps that apply, their state and the outcome. */
data class WizardRun(val steps: Map<WizardStep, StepState>, val result: WizardResult? = null) {
    val running get() = result == null
}

object WizardPlan {
    /** Steps that will actually run for [f], in order (the rest are skipped). */
    fun steps(f: WizardForm): List<WizardStep> = WizardStep.entries.filter { s ->
        when (s) {
            WizardStep.ZVOL -> f.extent == WizardExtent.NEW_ZVOL
            WizardStep.PORTAL -> f.newPortal
            WizardStep.INITIATORS -> f.initiators == WizardInitiators.LIST
            WizardStep.CHAP -> f.chap == WizardChap.NEW
            else -> true
        }
    }

    fun initial(d: IscsiData) = WizardForm(
        parent = d.datasets.firstOrNull { !it.isVolume && !it.isSystem && '/' !in it.id }?.id.orEmpty(),
        newPortal = d.portals.isEmpty(),
        portalId = d.portals.firstOrNull()?.id,
        portalIps = if (d.portals.any { "0.0.0.0" in it.ips }) emptyList() else listOf("0.0.0.0"),
    )
}

class IscsiWizardViewModel(private val c: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow<UiState<IscsiData>>(UiState.Loading)
    val state = _state.asStateFlow()
    private val _form = MutableStateFlow(WizardForm())
    val form = _form.asStateFlow()
    private val _run = MutableStateFlow<WizardRun?>(null)
    val run = _run.asStateFlow()
    private val _touched = MutableStateFlow(false)
    val touched = _touched.asStateFlow()
    private val _service = MutableStateFlow<String?>(null)
    val service = _service.asStateFlow()

    init { load() }

    fun load() = viewModelScope.launch {
        _state.value = UiState.Loading
        try {
            val d = c.repository.call { IscsiApi(it).load() }
            _form.value = WizardPlan.initial(d)
            _state.value = UiState.Success(d)
        } catch (e: Throwable) { _state.value = UiState.Error(e.userMessage(), e) }
    }

    fun edit(change: (WizardForm) -> WizardForm) { _form.value = change(_form.value); _touched.value = true }

    fun create() {
        val d = (_state.value as? UiState.Success)?.data ?: return
        val f = _form.value
        if (IscsiLogic.wizardErrors(f, d).isNotEmpty()) { _touched.value = true; return }
        if (_run.value?.running == true) return
        _run.value = WizardRun(WizardPlan.steps(f).associateWith { StepState.PENDING })
        viewModelScope.launch {
            // Never leave half a setup behind because the screen went away mid-run.
            val result = withContext(NonCancellable) {
                try {
                    c.repository.call { api -> IscsiApi(api).runWizard(f, d) { s, st -> _run.value = _run.value?.let { r -> if (st == StepState.SKIPPED) r else r.copy(steps = r.steps + (s to st)) } } }
                } catch (e: Throwable) { WizardResult(false, error = e.userMessage()) }
            }
            _run.value = _run.value?.copy(result = result)
            if (result.ok) runCatching { c.repository.call { IscsiApi(it).load() } }.getOrNull()?.let { _state.value = UiState.Success(it) }
        }
    }

    fun backToForm() { if (_run.value?.running == false) _run.value = null }

    fun startService() = viewModelScope.launch {
        _service.value = "Starting…"
        _service.value = try { c.repository.call { IscsiApi(it).startService() }; "The iSCSI service is running" } catch (e: Throwable) { e.userMessage() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IscsiWizardScreen(onBack: () -> Unit, onDone: () -> Unit) {
    val vm = appViewModel("iscsi-wizard") { IscsiWizardViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    val form by vm.form.collectAsStateWithLifecycle()
    val run by vm.run.collectAsStateWithLifecycle()
    val touched by vm.touched.collectAsStateWithLifecycle()
    val service by vm.service.collectAsStateWithLifecycle()
    var confirmDiscard by remember { mutableStateOf(false) }
    val running = run?.running == true
    val leave = { if (run?.result?.ok == true) onDone() else if (touched && run == null) confirmDiscard = true else onBack() }
    BackHandler(enabled = true) { if (!running) leave() }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Share a block device") },
                navigationIcon = { IconButton(onClick = { if (!running) leave() }, enabled = !running) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            when (val s = state) {
                UiState.Loading -> SkeletonList(3, 140.dp)
                is UiState.Error -> ScrollableErrorState(s.message, s.isLoginRequired) { vm.load() }
                is UiState.Success -> {
                    val r = run
                    if (r == null) WizardFormContent(form, s.data, IscsiLogic.wizardErrors(form, s.data), touched, vm::edit, vm::create)
                    else WizardProgress(r, form, s.data, service, onStart = { vm.startService() }, onDone = onDone, onBackToForm = vm::backToForm)
                }
            }
        }
    }
    if (confirmDiscard) app.truenascompanion.ui.components.ConfirmDialog(
        title = "Discard this setup?", text = "Nothing has been created yet.", confirmLabel = "Discard", destructive = true, requireAuth = false,
        onConfirm = { confirmDiscard = false; onBack() }, onDismiss = { confirmDiscard = false },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WizardFormContent(f: WizardForm, d: IscsiData, errors: Map<String, String>, showErrors: Boolean, onChange: ((WizardForm) -> WizardForm) -> Unit, onCreate: () -> Unit) {
    fun err(k: String) = errors[k]?.takeIf { showErrors }
    @Composable fun Help(t: String) = Text(t, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(16.dp).testTag("page"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Help("Creates the disk, the access rules and the target in one go. If a step fails, everything created so far is removed again.")
        SectionTitle("1 · The disk")
        ElevatedSection(contentPadding = 14.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Field(f.name, { v -> onChange { it.copy(name = v.lowercase().trim()) } }, "Name *", err("name"),
                    "Lowercase letters, digits, - . : — target ${IscsiLogic.iqn(d.global.basename, f.name.ifBlank { "name" })}", mono = true)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    WizardExtent.entries.forEach { x -> FilterChip(selected = f.extent == x, onClick = { onChange { it.copy(extent = x) } }, label = { Text(x.label) }) }
                }
                when (f.extent) {
                    WizardExtent.NEW_ZVOL -> {
                        val parents = d.datasets.filter { !it.isVolume && !it.isSystem && it.locked != true }.map { it.id }
                        PickField("Create in *", f.parent.ifBlank { null }, parents, { it }, onPick = { p -> onChange { it.copy(parent = p) } },
                            isError = err("parent") != null, supporting = err("parent") ?: "New zvol: ${IscsiLogic.wizardZvolName(f.copy(name = f.name.ifBlank { "name" }))}")
                        SizeRow(f, err("size"), onChange)
                        SwitchRow("Thin provisioned (sparse)", f.sparse, "Space is only used as data is written; the pool can run out under the initiator") { v -> onChange { it.copy(sparse = v) } }
                    }
                    WizardExtent.EXISTING_ZVOL -> {
                        val zvols = d.freeZvols()
                        PickField("Zvol *", zvols.firstOrNull { it.id == f.zvol }, zvols, { z -> z.id + (z.volsize?.let { " (${Format.bytes(it)})" } ?: "") },
                            onPick = { z -> onChange { it.copy(zvol = z.id) } }, isError = err("zvol") != null, supporting = err("zvol"),
                            emptyText = if (zvols.isEmpty()) "No free zvols" else "Choose a zvol")
                    }
                    WizardExtent.FILE -> {
                        Field(f.filePath, { v -> onChange { it.copy(filePath = v.trim()) } }, "New file *", err("path"), placeholder = "/mnt/tank/iscsi/disk1.img", mono = true)
                        SizeRow(f, err("size"), onChange)
                    }
                }
                Text("Used by", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SharingPlatform.entries.forEach { p -> FilterChip(selected = f.platform == p, onClick = { onChange { it.copy(platform = p) } }, label = { Text(p.label) }) }
                }
                Help(f.platform.help)
            }
        }
        SectionTitle("2 · Where initiators connect")
        ElevatedSection(contentPadding = 14.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = f.newPortal, onClick = { onChange { it.copy(newPortal = true) } }, label = { Text("New portal") })
                    FilterChip(selected = !f.newPortal, onClick = { onChange { it.copy(newPortal = false) } }, label = { Text("Existing portal") }, enabled = d.portals.isNotEmpty())
                }
                if (f.newPortal) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("wizard-ips")) {
                        d.listenChoices.forEach { ip ->
                            FilterChip(selected = ip in f.portalIps, onClick = { onChange { w -> w.copy(portalIps = if (ip in w.portalIps) w.portalIps - ip else w.portalIps + ip) } }, label = { Text(ip) })
                        }
                    }
                    Text(err("portal") ?: "Port ${d.global.listenPort}. 0.0.0.0 listens on every IPv4 address.", style = MaterialTheme.typography.bodySmall,
                        color = if (err("portal") != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                } else PickField("Portal *", d.portal(f.portalId), d.portals, { "${it.label} · ${it.addresses}" }, onPick = { p -> onChange { it.copy(portalId = p.id) } },
                    isError = err("portal") != null, supporting = err("portal"))
            }
        }
        SectionTitle("3 · Who may connect")
        ElevatedSection(contentPadding = 14.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    WizardInitiators.entries.forEach { x ->
                        FilterChip(selected = f.initiators == x, onClick = { onChange { it.copy(initiators = x) } }, label = { Text(x.label) }, enabled = x != WizardInitiators.EXISTING || d.initiators.isNotEmpty())
                    }
                }
                when (f.initiators) {
                    WizardInitiators.ALL -> Help("Any initiator that reaches the portal. Consider CHAP below.")
                    WizardInitiators.LIST -> Field(f.initiatorList, { v -> onChange { it.copy(initiatorList = v) } }, "Initiator IQNs or addresses *", err("initiators"),
                        "One per line, e.g. iqn.1991-05.com.microsoft:pc1", mono = true, lines = 3)
                    WizardInitiators.EXISTING -> PickField("Initiator group *", d.initiator(f.initiatorGroupId), d.initiators, { it.label },
                        onPick = { g -> onChange { it.copy(initiatorGroupId = g.id) } }, isError = err("initiators") != null, supporting = err("initiators"))
                }
                Text("CHAP authentication", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    WizardChap.entries.forEach { x ->
                        FilterChip(selected = f.chap == x, onClick = { onChange { it.copy(chap = x) } }, label = { Text(x.label) }, enabled = x != WizardChap.EXISTING || d.auths.isNotEmpty())
                    }
                }
                when (f.chap) {
                    WizardChap.NONE -> Unit
                    WizardChap.EXISTING -> PickField("CHAP group *", f.authTag, d.authTags, { t -> "Group $t · " + d.authsWithTag(t).joinToString { it.user } },
                        onPick = { t -> onChange { it.copy(authTag = t) } }, isError = err("auth") != null, supporting = err("auth"))
                    WizardChap.NEW -> {
                        Field(f.chapUser, { v -> onChange { it.copy(chapUser = v.trim()) } }, "CHAP user *", err("chap_user"))
                        SecretTextField(f.chapSecret, { v -> onChange { it.copy(chapSecret = v) } }, "Secret *", isError = err("chap_secret") != null,
                            supporting = err("chap_secret") ?: "12–16 characters, no #")
                    }
                }
                if (f.chap != WizardChap.NONE) SwitchRow("Mutual CHAP", f.mutual, "The initiator also checks the NAS") { v -> onChange { it.copy(mutual = v) } }
                if (f.chap == WizardChap.NEW && f.mutual) {
                    Field(f.peerUser, { v -> onChange { it.copy(peerUser = v.trim()) } }, "Peer user *", err("peer_user"))
                    SecretTextField(f.peerSecret, { v -> onChange { it.copy(peerSecret = v) } }, "Peer secret *", isError = err("peer_secret") != null,
                        supporting = err("peer_secret") ?: "12–16 characters, different from the secret")
                }
            }
        }
        SectionTitle("Will create")
        ElevatedSection(contentPadding = 14.dp, modifier = Modifier.testTag("wizard-plan")) {
            Column(Modifier.fillMaxWidth()) { WizardPlan.steps(f).forEach { s -> Text("• " + s.label, style = MaterialTheme.typography.bodyMedium) } }
        }
        if (showErrors && errors.isNotEmpty()) InfoBanner("Fix the highlighted fields first.", health = Health.WARNING)
        Button(onClick = onCreate, enabled = !showErrors || errors.isEmpty(), modifier = Modifier.fillMaxWidth().testTag("wizard-create")) { Text("Create") }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun SizeRow(f: WizardForm, error: String?, onChange: ((WizardForm) -> WizardForm) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
        Field(f.size, { v -> onChange { it.copy(size = v.filter { c -> c.isDigit() || c == '.' }.take(10)) } }, "Size *", error, number = true, modifier = Modifier.weight(1f))
        PickField("Unit", f.unit, SizeUnit.entries, { it.name }, onPick = { u -> onChange { it.copy(unit = u) } }, modifier = Modifier.width(110.dp))
    }
}

@Composable
fun WizardProgress(run: WizardRun, f: WizardForm, d: IscsiData, service: String?, onStart: () -> Unit, onDone: () -> Unit, onBackToForm: () -> Unit) {
    @Suppress("DEPRECATION") val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val colors = LocalStatusColors.current
    var confirmStart by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).testTag("wizard-progress"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val r = run.result
        when {
            r == null -> InfoBanner("Setting up ${f.name}… Keep the app open.")
            r.ok -> InfoBanner("${f.name} is ready.", health = Health.HEALTHY)
            r.undoErrors.isEmpty() -> InfoBanner("${r.error}\n\nEverything created so far was removed again.", health = Health.CRITICAL)
            else -> InfoBanner("${r.error}\n\nSome steps couldn't be undone; remove these by hand:\n" + r.undoErrors.joinToString("\n") { "• $it" }, health = Health.CRITICAL)
        }
        ElevatedSection(contentPadding = 14.dp, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth()) { run.steps.forEach { (s, st) ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp).testTag("step-${s.name}")) {
                    when (st) {
                        StepState.RUNNING -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        StepState.DONE -> Icon(Icons.Rounded.CheckCircle, null, tint = colors.of(Health.HEALTHY), modifier = Modifier.size(20.dp))
                        StepState.FAILED -> Icon(Icons.Rounded.Error, null, tint = colors.of(Health.CRITICAL), modifier = Modifier.size(20.dp))
                        StepState.UNDONE -> Icon(Icons.AutoMirrored.Rounded.Undo, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                        StepState.UNDO_FAILED -> Icon(Icons.Rounded.Warning, null, tint = colors.of(Health.WARNING), modifier = Modifier.size(20.dp))
                        else -> Icon(Icons.Rounded.RadioButtonUnchecked, null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(20.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(s.label + when (st) { StepState.UNDONE -> " (undone)"; StepState.UNDO_FAILED -> " (undo failed)"; StepState.FAILED -> " (failed)"; else -> "" },
                        style = MaterialTheme.typography.bodyMedium, color = if (st == StepState.UNDONE) MaterialTheme.colorScheme.onSurfaceVariant else Color.Unspecified)
                }
            } }
        }
        if (r?.ok == true) {
            SectionTitle("Connect from the initiator")
            ElevatedSection(contentPadding = 14.dp) {
                Text("Target", style = MaterialTheme.typography.labelLarge)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(r.iqn.orEmpty(), style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace), modifier = Modifier.weight(1f))
                    IconButton(onClick = { clipboard.setText(AnnotatedString(r.iqn.orEmpty())) }) { Icon(Icons.Rounded.ContentCopy, "Copy IQN", Modifier.size(18.dp)) }
                }
                Text("Portal", style = MaterialTheme.typography.labelLarge)
                val addresses = if (f.newPortal) f.portalIps.joinToString { if (':' in it) "[$it]:${d.global.listenPort}" else "$it:${d.global.listenPort}" } else d.portal(f.portalId)?.addresses.orEmpty()
                Text(addresses + if (addresses.contains("0.0.0.0") || addresses.contains("[::]")) "\n(any of the NAS's addresses)" else "", style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace))
                if (f.chap != WizardChap.NONE) Text("CHAP is required: enter the user and secret in the initiator.", style = MaterialTheme.typography.bodySmall)
            }
            if (d.serviceRunning != true && service == null) {
                ElevatedSection(contentPadding = 14.dp, modifier = Modifier.testTag("wizard-start")) {
                    Text("The iSCSI service isn't running, so initiators can't connect yet.", style = MaterialTheme.typography.bodyMedium)
                    Button(onClick = { confirmStart = true }) { Icon(Icons.Rounded.PlayArrow, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Start iSCSI service") }
                }
            }
            service?.let { InfoBanner(it) }
            Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("Done") }
        } else if (r != null) {
            OutlinedButton(onClick = onBackToForm, modifier = Modifier.fillMaxWidth().testTag("wizard-back")) { Text("Back to the form") }
        }

    }
    if (confirmStart) StartServiceDialog(onDismiss = { confirmStart = false }) { confirmStart = false; onStart() }
}
