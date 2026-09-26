package app.truenascompanion.ui.protection

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.truenascompanion.data.model.BackupTask
import app.truenascompanion.data.model.CronSchedule
import app.truenascompanion.data.model.Disk
import app.truenascompanion.data.model.Pool
import app.truenascompanion.data.model.ScrubTask
import app.truenascompanion.data.model.SmartSchedule
import app.truenascompanion.data.model.SmartTestType
import app.truenascompanion.util.Format

private val DefaultScrubSchedule = CronSchedule(minute = "00", hour = "00", dom = "*", month = "*", dow = "7")

@Composable
fun ScrubScheduleDialog(pool: Pool, task: ScrubTask?, onDismiss: () -> Unit, onSave: (CronSchedule, Int, Boolean) -> Unit) {
    var schedule by rememberSaveableSchedule(task?.schedule ?: DefaultScrubSchedule)
    var threshold by rememberSaveable { mutableStateOf((task?.threshold ?: 35).toString()) }
    var enabled by rememberSaveable { mutableStateOf(task?.enabled ?: true) }
    val th = threshold.toIntOrNull()?.takeIf { it in 0..365 }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Scrub schedule · ${pool.name}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("A scrub reads all data to find and repair silent corruption. TrueNAS checks on this schedule and only scrubs when the last scrub is older than the threshold.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                ScheduleEditor(schedule, { schedule = it }, allowHourly = false)
                OutlinedTextField(
                    value = threshold, onValueChange = { threshold = it.filter(Char::isDigit).take(3) },
                    label = { Text("Threshold (days)") }, singleLine = true, isError = th == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth(),
                    supportingText = { Text("Skip if the last scrub was less than this many days ago") },
                )
                if (task != null) SwitchRow("Enabled", enabled) { enabled = it }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(schedule, th ?: 35, enabled) }, enabled = th != null) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** CronSchedule isn't Parcelable; keep it across rotation as its fields. */
@Composable
private fun rememberSaveableSchedule(initial: CronSchedule) = rememberSaveable(
    saver = androidx.compose.runtime.saveable.Saver<androidx.compose.runtime.MutableState<CronSchedule>, List<String?>>(
        save = { s -> s.value.let { listOf(it.minute, it.hour, it.dom, it.month, it.dow, it.begin, it.end) } },
        restore = { l -> mutableStateOf(CronSchedule(l[0]!!, l[1]!!, l[2]!!, l[3]!!, l[4]!!, l[5], l[6])) },
    ),
) { mutableStateOf(initial) }

@Composable
internal fun rememberSchedule(initial: CronSchedule) = rememberSaveableSchedule(initial)

/** Picks all disks or specific ones (by `identifier`, which is what `disk.smart_test` takes). */
@Composable
private fun DiskPicker(disks: List<Disk>, selected: List<String>, onChange: (List<String>) -> Unit) {
    val all = "*" in selected
    Column {
        Row(Modifier.fillMaxWidth().selectable(all, onClick = { onChange(listOf("*")) }), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = all, onClick = { onChange(listOf("*")) })
            Text("All disks", style = MaterialTheme.typography.bodyLarge)
        }
        Row(Modifier.fillMaxWidth().selectable(!all, onClick = { if (all) onChange(emptyList()) }), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = !all, onClick = { if (all) onChange(emptyList()) })
            Text("Choose disks", style = MaterialTheme.typography.bodyLarge)
        }
        if (!all) {
            disks.forEach { d ->
                val id = d.identifier
                val checked = id != null && id in selected
                Row(
                    Modifier.fillMaxWidth().padding(start = 24.dp).clickable(enabled = id != null) { id?.let { onChange(if (checked) selected - it else selected + it) } },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = checked, onCheckedChange = null, enabled = id != null, modifier = Modifier.padding(12.dp))
                    Column {
                        Text(d.name, style = MaterialTheme.typography.bodyLarge)
                        Text(listOfNotNull(d.model, Format.bytes(d.size)).joinToString(" · "), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
private fun TypePicker(types: List<SmartTestType>, value: SmartTestType, onPick: (SmartTestType) -> Unit) {
    Column {
        types.forEach { t ->
            Row(Modifier.fillMaxWidth().selectable(value == t, onClick = { onPick(t) }), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = value == t, onClick = { onPick(t) })
                Column {
                    Text("${t.label} test", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        when (t) {
                            SmartTestType.SHORT -> "About 2 minutes. Checks the basics."
                            SmartTestType.LONG -> "Several hours. Reads the whole disk surface."
                            SmartTestType.CONVEYANCE -> "A few minutes. Checks for transport damage (HDD only)."
                            SmartTestType.OFFLINE -> "Offline data collection."
                        },
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** Run a SMART test now (no text fields, so it also renders in previews). */
@Composable
fun SmartRunDialog(disks: List<Disk>, onDismiss: () -> Unit, onRun: (SmartTestType, List<String>) -> Unit) {
    var type by rememberSaveable { mutableStateOf(SmartTestType.SHORT) }
    var selected by rememberSaveable { mutableStateOf(listOf("*")) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Run a SMART test") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                TypePicker(listOf(SmartTestType.SHORT, SmartTestType.LONG), type) { type = it }
                Spacer(Modifier.height(8.dp))
                DiskPicker(disks, selected) { selected = it }
                Spacer(Modifier.height(8.dp))
                Text("TrueNAS 25.10 doesn't report results or progress. If a test fails, you get a TrueNAS alert (and a notification, if enabled).",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = { onRun(type, selected) }, enabled = selected.isNotEmpty()) { Text("Start test") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun SmartScheduleDialog(existing: SmartSchedule?, disks: List<Disk>, onDismiss: () -> Unit, onSave: (SmartTestType, List<String>, CronSchedule, Boolean) -> Unit) {
    var type by rememberSaveable { mutableStateOf(existing?.type ?: SmartTestType.SHORT) }
    var selected by rememberSaveable { mutableStateOf(existing?.disks ?: listOf("*")) }
    var schedule by rememberSaveableSchedule(existing?.schedule ?: CronSchedule(minute = "0", hour = "3", dom = "*", month = "*", dow = "sun"))
    var enabled by rememberSaveable { mutableStateOf(existing?.enabled ?: true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "Schedule SMART tests" else "Edit SMART schedule") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TypePicker(SmartTestType.entries.filter { it != SmartTestType.OFFLINE || existing?.type == SmartTestType.OFFLINE }, type) { type = it }
                ScheduleEditor(schedule, { schedule = it }, allowHourly = false)
                DiskPicker(disks, selected) { selected = it }
                if (existing != null) SwitchRow("Enabled", enabled) { enabled = it }
                Text("Saved as a TrueNAS cron job, which is how TrueNAS 25.10 runs scheduled SMART tests. You'll also see it under System › Advanced › Cron Jobs.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = { onSave(type, selected, schedule, enabled) }, enabled = selected.isNotEmpty()) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun JobLogDialog(task: BackupTask, onDismiss: () -> Unit) {
    val job = task.lastJob
    val text = listOfNotNull(job?.error ?: task.state?.error, job?.logExcerpt?.trim()?.takeIf { it.isNotEmpty() }).joinToString("\n\n")
        .ifBlank { "No log output for the last run." }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(task.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                job?.let {
                    Text("Last run: ${it.state.name.lowercase()} · ${Format.relativeTime(it.finishedMillis ?: it.startedMillis)}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                }
                Text(text, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

