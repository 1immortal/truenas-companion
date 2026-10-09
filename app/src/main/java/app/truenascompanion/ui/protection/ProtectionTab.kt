package app.truenascompanion.ui.protection

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import app.truenascompanion.data.model.BackupTask
import app.truenascompanion.data.model.Pool
import app.truenascompanion.data.model.ScrubTask
import app.truenascompanion.data.model.SmartSchedule
import app.truenascompanion.data.model.SnapshotTask
import app.truenascompanion.ui.components.ConfirmDialog
import kotlinx.coroutines.delay

private sealed interface PDialog {
    data class Scrub(val pool: Pool, val task: ScrubTask?) : PDialog
    data class StopScrub(val pool: Pool) : PDialog
    data class DeleteSnapTask(val task: SnapshotTask) : PDialog
    data object RunSmart : PDialog
    data class SmartEdit(val existing: SmartSchedule?) : PDialog
    data class DeleteSmart(val s: SmartSchedule) : PDialog
    data class Log(val task: BackupTask) : PDialog
    data class DisableBackup(val task: BackupTask) : PDialog
}

/** The Protection tab of Storage: state, dialogs and polling around [ProtectionContent]. */
@Composable
fun ProtectionTab(data: ProtectionData, vm: ProtectionViewModel, onAddTask: () -> Unit, onEditTask: (Int) -> Unit, onCloudSync: () -> Unit = {}) {
    val busy by vm.busy.collectAsStateWithLifecycle()
    var dialog by remember { mutableStateOf<PDialog?>(null) }
    val running = data.anythingRunning
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(running) {
        if (running) lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { while (true) { delay(5_000); vm.poll() } }
    }

    val actions = remember(vm) {
        object : ProtectionActions() {
            override fun startScrub(pool: Pool) { vm.startScrub(pool.name) }
            override fun stopScrub(pool: Pool) { dialog = PDialog.StopScrub(pool) }
            override fun pauseScrub(pool: Pool) { vm.pauseScrub(pool.name) }
            override fun editScrubSchedule(pool: Pool, task: ScrubTask?) { dialog = PDialog.Scrub(pool, task) }
            override fun addSnapshotTask() = onAddTask()
            override fun editSnapshotTask(t: SnapshotTask) = onEditTask(t.id)
            override fun toggleSnapshotTask(t: SnapshotTask, on: Boolean) { vm.setSnapshotTaskEnabled(t, on) }
            override fun runSnapshotTask(t: SnapshotTask) { vm.runSnapshotTask(t) }
            override fun deleteSnapshotTask(t: SnapshotTask) { dialog = PDialog.DeleteSnapTask(t) }
            override fun runSmartNow() { dialog = PDialog.RunSmart }
            override fun addSmartSchedule() { dialog = PDialog.SmartEdit(null) }
            override fun editSmartSchedule(s: SmartSchedule) { dialog = PDialog.SmartEdit(s) }
            override fun toggleSmart(s: SmartSchedule, on: Boolean) { vm.setSmartEnabled(s, on) }
            override fun runSmartSchedule(s: SmartSchedule) { vm.runSmartSchedule(s) }
            override fun deleteSmartSchedule(s: SmartSchedule) { dialog = PDialog.DeleteSmart(s) }
            override fun toggleBackup(t: BackupTask, on: Boolean) { if (on) vm.setBackupEnabled(t, true) else dialog = PDialog.DisableBackup(t) }
            override fun runBackup(t: BackupTask) { vm.runBackup(t) }
            override fun showLog(t: BackupTask) { dialog = PDialog.Log(t) }
            override fun openCloudSync() = onCloudSync()
        }
    }
    ProtectionContent(data, busy, actions)

    val close = { dialog = null }
    when (val d = dialog) {
        null -> Unit
        is PDialog.Scrub -> ScrubScheduleDialog(d.pool, d.task, close) { s, th, en -> close(); vm.saveScrubSchedule(d.pool, d.task, s, th, en) }
        is PDialog.StopScrub -> ConfirmDialog("Stop the scrub?", "The scrub on ${d.pool.name} stops and has to start over next time. Pause instead to resume later.",
            "Stop scrub", onConfirm = { close(); vm.stopScrub(d.pool.name) }, onDismiss = close)
        is PDialog.DeleteSnapTask -> ConfirmDialog("Delete snapshot task?",
            "Automatic snapshots of ${d.task.dataset} stop. Snapshots it already took are kept and are no longer removed automatically.",
            "Delete", destructive = true, onConfirm = { close(); vm.deleteSnapshotTask(d.task) }, onDismiss = close)
        PDialog.RunSmart -> SmartRunDialog(data.disks, close) { type, disks -> close(); vm.runSmartNow(type, disks) }
        is PDialog.SmartEdit -> SmartScheduleDialog(d.existing, data.disks, close) { type, disks, s, en -> close(); vm.saveSmartSchedule(d.existing, type, disks, s, en) }
        is PDialog.DeleteSmart -> ConfirmDialog("Delete SMART schedule?", "The ${d.s.type.label.lowercase()} test schedule is removed. Disks are no longer tested automatically on it.",
            "Delete", destructive = true, onConfirm = { close(); vm.deleteSmartSchedule(d.s) }, onDismiss = close)
        is PDialog.Log -> JobLogDialog(d.task, close)
        is PDialog.DisableBackup -> ConfirmDialog("Pause ${d.task.name}?", "The task stops running on its schedule until you turn it back on.",
            "Pause task", onConfirm = { close(); vm.setBackupEnabled(d.task, false) }, onDismiss = close)
    }
}
