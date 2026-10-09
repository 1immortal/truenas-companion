package app.truenascompanion.ui.disks

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import app.truenascompanion.ui.components.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import app.truenascompanion.ui.components.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.truenascompanion.AppContainer
import app.truenascompanion.data.api.TrueNasException
import app.truenascompanion.data.api.userMessage
import app.truenascompanion.data.disks.DiskLogic
import app.truenascompanion.data.disks.DisksApi
import app.truenascompanion.data.model.DiskInfo
import app.truenascompanion.data.model.Health
import app.truenascompanion.data.model.PoolLayout
import app.truenascompanion.data.model.PoolMember
import app.truenascompanion.data.model.ReplacementCandidate
import app.truenascompanion.data.model.ResilverWatch
import app.truenascompanion.notify.ResilverWatcher
import app.truenascompanion.ui.appViewModel
import app.truenascompanion.ui.components.ConfirmDialog
import app.truenascompanion.ui.components.ElevatedSection
import app.truenascompanion.ui.components.GlowButton
import app.truenascompanion.ui.components.IconBadge
import app.truenascompanion.ui.components.InfoBanner
import app.truenascompanion.ui.components.StatusChip
import app.truenascompanion.ui.theme.LocalStatusColors
import app.truenascompanion.util.Format
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class ReplaceStep(val title: String) {
    INTRO("Before you start"), CHOOSE("Disk to replace"), OFFLINE("Take it offline"), SCAN("Insert the new disk"),
    PICK("Choose the new disk"), RUNNING("Replacing"), RESILVER("Resilvering"), DONE("Done"),
}

data class ReplaceUi(
    val step: ReplaceStep = ReplaceStep.INTRO,
    val loading: Boolean = true,
    val busy: Boolean = false,
    val error: String? = null,
    val pool: PoolLayout? = null,
    val disks: List<DiskInfo> = emptyList(),
    val member: PoolMember? = null,
    val candidates: List<ReplacementCandidate>? = null,
    val selected: ReplacementCandidate? = null,
    val serialConfirmed: Boolean = false,
    val force: Boolean = false,
    /** TrueNAS said the new disk isn't clean (partitions or an exported pool): Force is needed. */
    val forceRequired: Boolean = false,
    val startedAt: Long = 0,
) {
    val members: List<PoolMember> get() = pool?.let { DiskLogic.members(it).filter(DiskLogic::canReplace) }.orEmpty()
    val oldSize: Long? get() = member?.let { DiskLogic.memberSize(it, disks.mapNotNull { d -> d.size?.let { s -> d.name to s } }.toMap()) }
    val oldSerial: String? get() = member?.let { m -> m.node.disk?.let { n -> disks.firstOrNull { it.name == n }?.serial } ?: m.node.unavailDisk?.serial }
    val oldModel: String? get() = member?.let { m -> m.node.disk?.let { n -> disks.firstOrNull { it.name == n }?.model } ?: m.node.unavailDisk?.model }
    /** Name shown for the member being replaced (a missing disk keeps TrueNAS's record of its old name). */
    val oldName: String get() = member?.let { it.node.disk ?: it.node.unavailDisk?.name } ?: "the disk"
    /** The picked "new" disk reports the same serial as the one being replaced: probably the old disk itself. */
    val sameSerialAsOld: Boolean get() = selected?.serial != null && selected.serial.equals(oldSerial, ignoreCase = true)
    val canReplace: Boolean get() = selected != null && serialConfirmed && DiskLogic.sizeCheck(oldSize, selected.size) !is DiskLogic.SizeCheck.TooSmall &&
        (!(DiskLogic.needsForce(selected) || forceRequired) || force)
}

/**
 * Guided disk replacement (1.3.0): choose member → optional `pool.offline` → `disk.details` scan → pick with size
 * check and serial confirmation → `pool.replace` job → resilver progress (pool.query every 5 s while on screen), then a
 * background [ResilverWatcher] notifies when it finishes.
 */
class ReplaceViewModel(private val c: AppContainer, private val poolName: String, private val guid: String?, private val diskName: String?) : ViewModel() {
    private val _ui = MutableStateFlow(ReplaceUi())
    val ui: StateFlow<ReplaceUi> = _ui.asStateFlow()
    private var pollJob: Job? = null
    private var visible = true

    init { load() }

    fun load() = viewModelScope.launch {
        _ui.update { it.copy(loading = true, error = null) }
        try {
            val pools = c.repository.call { DisksApi(it).pools() }
            val disks = c.repository.call { DisksApi(it).disks() }
            val pool = pools.firstOrNull { it.name == poolName } ?: throw TrueNasException.Rpc(0, "ENOENT", "Pool $poolName not found")
            val member = _ui.value.member?.let { DiskLogic.memberByGuid(pool, it.node.guid ?: "") }
                ?: guid?.let { DiskLogic.memberByGuid(pool, it) }
                ?: DiskLogic.preselect(pool, diskName)
            _ui.update { it.copy(loading = false, pool = pool, disks = disks, member = member) }
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            _ui.update { it.copy(loading = false, error = e.userMessage()) }
        }
    }

    fun go(step: ReplaceStep) {
        _ui.update { it.copy(step = step, error = null) }
        if (step == ReplaceStep.SCAN && _ui.value.candidates == null) scan()
    }

    fun choose(m: PoolMember) = _ui.update { it.copy(member = m, selected = null, serialConfirmed = false) }

    fun offline() {
        val m = _ui.value.member ?: return
        busy {
            c.repository.call { DisksApi(it).offline(m.poolId, m.node.guid!!) }
            refreshPool()
            go(ReplaceStep.SCAN)
        }
    }

    fun scan() = busy {
        val list = c.repository.call { DisksApi(it).candidates() }
        _ui.update { s -> s.copy(candidates = DiskLogic.candidates(list, s.oldSize), selected = s.selected?.takeIf { sel -> list.any { it.identifier == sel.identifier } }) }
    }

    fun select(cand: ReplacementCandidate) = _ui.update {
        // "Force required" came from TrueNAS about the previously picked disk; a different disk starts clean.
        val same = it.selected?.identifier == cand.identifier
        it.copy(selected = cand, serialConfirmed = false, force = false, forceRequired = it.forceRequired && same)
    }
    fun setSerialConfirmed(v: Boolean) = _ui.update { it.copy(serialConfirmed = v) }
    fun setForce(v: Boolean) = _ui.update { it.copy(force = v) }

    fun replace() {
        val s = _ui.value
        val m = s.member ?: return
        val cand = s.selected ?: return
        if (!s.canReplace) return
        val pool = s.pool ?: return
        viewModelScope.launch {
            val started = System.currentTimeMillis()
            _ui.update { it.copy(step = ReplaceStep.RUNNING, error = null, startedAt = started) }
            try {
                val serverId = c.settings.activeServerId.first()
                val jobId = c.repository.call { DisksApi(it).startReplace(m.poolId, m.node.guid!!, cand.identifier, s.force) }
                if (serverId != null) ResilverWatcher.add(c.context, ResilverWatch(serverId, pool.id, pool.name, started))
                if (jobId != null) c.repository.call { DisksApi(it).awaitJob(jobId) }
                _ui.update { it.copy(step = ReplaceStep.RESILVER) }
                startPolling()
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                val text = e.userMessage()
                val needForce = text.contains("Force must be specified", true)
                c.settings.activeServerId.first()?.let { ResilverWatcher.remove(c.context, it, pool.id) }
                _ui.update {
                    it.copy(
                        step = ReplaceStep.PICK, forceRequired = it.forceRequired || needForce,
                        error = if (needForce) "TrueNAS found old partitions or pool data on the new disk. Check it's the right disk, then turn on \"Wipe and use anyway\"." else text,
                    )
                }
            }
        }
    }

    fun setVisible(v: Boolean) {
        visible = v
        if (v && _ui.value.step == ReplaceStep.RESILVER) startPolling() else if (!v) pollJob?.cancel()
    }

    private fun startPolling() {
        if (pollJob?.isActive == true || !visible) return
        pollJob = viewModelScope.launch {
            while (true) {
                val p = runCatching { refreshPool() }.getOrNull()
                if (p != null && ResilverWatcher.verdict(ResilverWatch("", p.id, p.name, _ui.value.startedAt), p, System.currentTimeMillis()) == ResilverWatcher.Verdict.DONE) {
                    _ui.update { it.copy(step = ReplaceStep.DONE) }
                    c.settings.activeServerId.first()?.let { ResilverWatcher.remove(c.context, it, p.id) }
                    break
                }
                delay(5_000)
            }
        }
    }

    private suspend fun refreshPool(): PoolLayout? {
        val id = _ui.value.pool?.id ?: return null
        val p = c.repository.call { DisksApi(it).pool(id) } ?: return null
        _ui.update { s -> s.copy(pool = p, member = s.member?.node?.guid?.let { g -> DiskLogic.memberByGuid(p, g) } ?: s.member) }
        return p
    }

    private fun busy(block: suspend () -> Unit) {
        if (_ui.value.busy) return
        viewModelScope.launch {
            _ui.update { it.copy(busy = true, error = null) }
            try { block() } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _ui.update { it.copy(error = e.userMessage()) }
            } finally { _ui.update { it.copy(busy = false) } }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReplaceWizardScreen(poolName: String, guid: String?, diskName: String?, onBack: () -> Unit, onOpenPool: (String) -> Unit) {
    val vm = appViewModel(key = "replace:$poolName:$guid:$diskName") { ReplaceViewModel(it, poolName, guid, diskName) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_START) vm.setVisible(true) else if (e == Lifecycle.Event.ON_STOP) vm.setVisible(false)
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs); vm.setVisible(false) }
    }
    Scaffold(topBar = {
        TopAppBar(
            title = { Column { Text("Replace disk"); Text("$poolName · ${ui.step.title}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) } },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
        )
    }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            ReplaceWizardContent(ui, ReplaceActions(
                go = vm::go, choose = vm::choose, offline = vm::offline, scan = { vm.scan() }, select = vm::select,
                serial = vm::setSerialConfirmed, force = vm::setForce, replace = vm::replace, retry = { vm.load() },
                close = onBack, openPool = { onOpenPool(poolName) },
            ))
        }
    }
}

class ReplaceActions(
    val go: (ReplaceStep) -> Unit = {},
    val choose: (PoolMember) -> Unit = {},
    val offline: () -> Unit = {},
    val scan: () -> Unit = {},
    val select: (ReplacementCandidate) -> Unit = {},
    val serial: (Boolean) -> Unit = {},
    val force: (Boolean) -> Unit = {},
    val replace: () -> Unit = {},
    val retry: () -> Unit = {},
    val close: () -> Unit = {},
    val openPool: () -> Unit = {},
)

@Composable
fun ReplaceWizardContent(ui: ReplaceUi, a: ReplaceActions) {
    var confirm by remember { mutableStateOf(false) }
    var confirmOffline by remember { mutableStateOf(false) }
    val steps = ReplaceStep.entries
    LinearProgressIndicator(progress = { (steps.indexOf(ui.step) + 1f) / steps.size }, modifier = Modifier.fillMaxWidth())
    if (ui.loading) { LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp)); return }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxSize()) {
        ui.error?.let { e -> item { InfoBanner(e, health = Health.CRITICAL) } }
        when (ui.step) {
            ReplaceStep.INTRO -> {
                item {
                    StepCard("Replacing a disk", Icons.Rounded.SwapHoriz) {
                        Bullet("Back up anything important first. The pool has less redundancy until the resilver finishes.")
                        Bullet("Don't pull the wrong disk: in a degraded pool that can make the pool unavailable.")
                        Bullet("Check the serial number on the disk's label before removing it. The app shows the serial at every step.")
                        Bullet("The new disk must be at least as large as the old one.")
                    }
                }
                if (ui.pool == null && ui.error != null) item {
                    OutlinedButton(onClick = a.retry) { Text("Try again") }
                }
                item { NavRow(next = "Start", onNext = { a.go(ReplaceStep.CHOOSE) }, back = "Cancel", onBack = a.close, nextEnabled = ui.pool != null) }
            }
            ReplaceStep.CHOOSE -> {
                item { Text("Which disk is being replaced?", style = MaterialTheme.typography.titleMedium) }
                items(ui.members, key = { it.node.guid ?: it.node.name }) { m ->
                    MemberChoice(m, ui.disks, selected = ui.member?.node?.guid == m.node.guid) { a.choose(m) }
                }
                item {
                    val m = ui.member
                    NavRow(
                        next = "Next", nextEnabled = m != null,
                        onNext = { if (m != null && DiskLogic.canOffline(m)) a.go(ReplaceStep.OFFLINE) else a.go(ReplaceStep.SCAN) },
                        back = "Back", onBack = { a.go(ReplaceStep.INTRO) },
                    )
                }
            }
            ReplaceStep.OFFLINE -> {
                val m = ui.member
                item {
                    StepCard("Take ${ui.oldName} offline first? (optional)", Icons.Rounded.Warning) {
                        Text(
                            "If the old disk still works, taking it offline lets ZFS stop using it cleanly before you pull it. " +
                                "If you have a free bay, you can skip this and replace the disk while it's still in the pool (safer: redundancy stays).",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.height(10.dp))
                        SerialLine(ui.oldModel, ui.oldSerial)
                        if (m != null && !DiskLogic.offlineIsSafe(m)) {
                            Spacer(Modifier.height(10.dp))
                            InfoBanner("This vdev may have no redundancy left. Taking this disk offline could make the pool unavailable.", health = Health.CRITICAL)
                        }
                    }
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { a.go(ReplaceStep.CHOOSE) }) { Text("Back") }
                        Spacer(Modifier.weight(1f))
                        OutlinedButton(onClick = { a.go(ReplaceStep.SCAN) }, enabled = !ui.busy) { Text("Skip") }
                        GlowButton(onClick = { confirmOffline = true }, enabled = !ui.busy) { Text("Take offline") }
                    }
                }
                if (ui.busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            }
            ReplaceStep.SCAN, ReplaceStep.PICK -> {
                item {
                    StepCard("Insert the new disk", Icons.Rounded.Search) {
                        Text("Put the new disk in a free bay (or swap it for the old one now, after checking the serial), then scan.", style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(8.dp))
                        SerialLine(ui.oldModel, ui.oldSerial, prefix = "Old disk")
                        Spacer(Modifier.height(10.dp))
                        OutlinedButton(onClick = a.scan, enabled = !ui.busy) { Text(if (ui.candidates == null) "Scan for new disks" else "Scan again") }
                        if (ui.busy) { Spacer(Modifier.height(8.dp)); LinearProgressIndicator(Modifier.fillMaxWidth()) }
                    }
                }
                val list = ui.candidates
                if (list != null && list.isEmpty()) item {
                    InfoBanner("No unused disk found. Make sure the new disk is inserted and detected, then scan again.")
                }
                if (!list.isNullOrEmpty()) {
                    item { Text("Choose the new disk", style = MaterialTheme.typography.titleMedium) }
                    items(list, key = { it.identifier }) { cnd -> CandidateRow(cnd, ui.oldSize, ui.selected?.identifier == cnd.identifier) { a.select(cnd); a.go(ReplaceStep.PICK) } }
                }
                val sel = ui.selected
                if (sel != null) item {
                    ElevatedSection {
                        Text("Confirm the new disk", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(6.dp))
                        SerialLine(sel.model, sel.serial, prefix = sel.name)
                        if (ui.sameSerialAsOld) {
                            Spacer(Modifier.height(8.dp))
                            InfoBanner("This disk reports the same serial as the disk being replaced (${sel.serial}). It is probably the old disk. Pick the new one.", health = Health.CRITICAL)
                        }
                        if (sel.serial == null) {
                            Spacer(Modifier.height(8.dp))
                            InfoBanner("This disk doesn't report a serial number. Check the model, size and bay before you continue.")
                        }
                        if (sel.duplicateSerial.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            InfoBanner("Other disks report the same serial (${sel.duplicateSerial.joinToString()}), common with USB enclosures. Make extra sure this is the right disk.")
                        }
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { a.serial(!ui.serialConfirmed) }, verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(ui.serialConfirmed, onCheckedChange = a.serial)
                            Text("The model and serial match the disk I just inserted", style = MaterialTheme.typography.bodyMedium)
                        }
                        if (DiskLogic.needsForce(sel) || ui.forceRequired) {
                            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { a.force(!ui.force) }, verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(ui.force, onCheckedChange = a.force)
                                Text(
                                    sel.exportedZpool?.let { "Wipe and use anyway (destroys exported pool \"$it\" on this disk)" } ?: "Wipe and use anyway (old partitions are erased)",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                    }
                }
                item { NavRow(next = "Replace…", nextEnabled = ui.canReplace && !ui.busy, onNext = { confirm = true }, back = "Back", onBack = { a.go(ReplaceStep.CHOOSE) }) }
            }
            ReplaceStep.RUNNING -> item {
                StepCard("Starting the replacement…", Icons.Rounded.SwapHoriz) {
                    Text("TrueNAS is partitioning the new disk and attaching it to the pool.", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(10.dp))
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
            ReplaceStep.RESILVER, ReplaceStep.DONE -> {
                val scan = ui.pool?.scan
                item {
                    if (ui.step == ReplaceStep.DONE) StepCard("Replacement finished", Icons.Rounded.CheckCircle) {
                        Text(
                            if (ui.pool?.healthy == true) "${ui.pool.name} is healthy again. You can now remove the old disk (check its serial: ${ui.oldSerial ?: "see label"})."
                            else "The resilver finished, but ${ui.pool?.name} isn't fully healthy yet. Check the pool layout.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    } else StepCard("Resilvering", Icons.Rounded.SwapHoriz) {
                        Text("ZFS is copying data onto the new disk. This can take hours for large disks. You can leave this screen; the app will notify you when it's done.", style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(12.dp))
                        val pct = scan?.percent?.takeIf { scan.function == "RESILVER" }
                        Text(
                            listOfNotNull(Format.percent(pct), DiskLogic.eta(scan?.secondsLeft)?.let { "$it left" }).joinToString(" · "),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Spacer(Modifier.height(6.dp))
                        if (pct != null) LinearProgressIndicator(progress = { (pct / 100).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                        else LinearProgressIndicator(Modifier.fillMaxWidth())
                        scan?.bytesToProcess?.let { total ->
                            Spacer(Modifier.height(8.dp))
                            Text("${Format.bytes(scan.bytesIssued ?: scan.bytesProcessed)} of ${Format.bytes(total)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                item { NavRow(next = "Pool layout", onNext = a.openPool, back = "Close", onBack = a.close) }
            }
        }
    }
    val sel = ui.selected
    val m = ui.member
    if (confirm && sel != null && m != null) ConfirmDialog(
        title = "Replace ${ui.oldName} with ${sel.name}?",
        text = "Old disk: ${listOfNotNull(ui.oldModel, "SN ${ui.oldSerial ?: "unknown"}").joinToString(" · ")}.\n" +
            "New disk: ${sel.name} (${listOfNotNull(sel.model, Format.bytes(sel.size), sel.serial?.let { "SN $it" }).joinToString(" · ")}).\n\n" +
            "TrueNAS erases the new disk and starts resilvering ${ui.pool?.name}. All data on the new disk is lost." +
            (if (ui.force) " Force is on: existing partitions or pool data on it are wiped." else "") +
            " Keep the old disk in place until the resilver has finished, if you can.",
        confirmLabel = "Replace", destructive = true, strongAuth = true, icon = Icons.Rounded.SwapHoriz,
        onConfirm = { confirm = false; a.replace() }, onDismiss = { confirm = false },
    )
    if (confirmOffline && m != null) ConfirmDialog(
        title = "Take ${ui.oldName} offline?",
        text = "ZFS stops using this disk (SN ${ui.oldSerial ?: "unknown"}) in ${m.poolName}. The pool loses redundancy until the new disk has resilvered." +
            if (!DiskLogic.offlineIsSafe(m)) "\n\nWarning: this vdev may have no redundancy left. Taking the disk offline could make the pool unavailable." else "",
        confirmLabel = "Take offline", destructive = true, strongAuth = true,
        onConfirm = { confirmOffline = false; a.offline() }, onDismiss = { confirmOffline = false },
    )
}

@Composable
private fun StepCard(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, content: @Composable () -> Unit) {
    ElevatedSection {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(icon, size = 36.dp)
            Spacer(Modifier.width(12.dp))
            Text(title, style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.height(12.dp))
        content()
    }
}

@Composable
private fun Bullet(text: String) {
    Row(Modifier.padding(vertical = 3.dp)) {
        Text("•", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun SerialLine(model: String?, serial: String?, prefix: String? = null) {
    Column {
        if (prefix != null) Text(prefix, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(model ?: "Unknown model", style = MaterialTheme.typography.bodyMedium)
        Text("Serial: ${serial ?: "unknown"}", style = MaterialTheme.typography.titleSmall, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun NavRow(next: String, onNext: () -> Unit, back: String, onBack: () -> Unit, nextEnabled: Boolean = true) {
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onBack) { Text(back) }
        Spacer(Modifier.weight(1f))
        GlowButton(onClick = onNext, enabled = nextEnabled) { Text(next) }
    }
}

@Composable
private fun MemberChoice(m: PoolMember, disks: List<DiskInfo>, selected: Boolean, onClick: () -> Unit) {
    val d = m.node.disk?.let { n -> disks.firstOrNull { it.name == n } }
    ElevatedSection(contentPadding = 12.dp, onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = selected, onClick = onClick)
            Spacer(Modifier.width(6.dp))
            Column(Modifier.weight(1f)) {
                Text(m.node.disk ?: m.node.unavailDisk?.name ?: "Missing disk", style = MaterialTheme.typography.titleSmall)
                Text(
                    listOfNotNull(d?.model ?: m.node.unavailDisk?.model, (d?.serial ?: m.node.unavailDisk?.serial)?.let { "SN $it" }, m.parent?.name).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
            StatusChip(memberHealth(m.node.status), statusLabel(m.node.status), showIcon = false)
        }
    }
}

@Composable
private fun CandidateRow(c: ReplacementCandidate, oldSize: Long?, selected: Boolean, onClick: () -> Unit) {
    val check = DiskLogic.sizeCheck(oldSize, c.size)
    val tooSmall = check is DiskLogic.SizeCheck.TooSmall
    ElevatedSection(contentPadding = 12.dp, onClick = if (tooSmall) null else onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = selected, onClick = onClick, enabled = !tooSmall)
            Spacer(Modifier.width(6.dp))
            Column(Modifier.weight(1f)) {
                Text("${c.name} · ${Format.bytes(c.size)}", style = MaterialTheme.typography.titleSmall)
                Text(listOfNotNull(c.model, c.serial?.let { "SN $it" }).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                val (msg, h) = when (check) {
                    is DiskLogic.SizeCheck.TooSmall -> "Too small: ${Format.bytes(check.missing)} smaller than the old disk" to Health.CRITICAL
                    DiskLogic.SizeCheck.Ok -> "Size OK" to Health.HEALTHY
                    DiskLogic.SizeCheck.Unknown -> "Old disk size unknown; TrueNAS checks it" to Health.WARNING
                }
                Text(msg, style = MaterialTheme.typography.labelMedium, color = LocalStatusColors.current.of(h))
                c.exportedZpool?.let { Text("Holds exported pool \"$it\"", style = MaterialTheme.typography.labelMedium, color = LocalStatusColors.current.of(Health.WARNING)) }
            }
        }
    }
}
