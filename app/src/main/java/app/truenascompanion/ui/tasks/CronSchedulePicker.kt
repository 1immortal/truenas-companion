package app.truenascompanion.ui.tasks

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccessTime
import androidx.compose.material.icons.rounded.EventRepeat
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.truenascompanion.data.model.CronSchedule
import app.truenascompanion.data.tasks.CronField
import app.truenascompanion.data.tasks.CronPreset
import app.truenascompanion.data.tasks.CronText
import app.truenascompanion.ui.components.PickField

/**
 * Friendly cron schedule picker (1.4.0): presets (hourly, daily, weekly, monthly) with a time picker, or custom
 * minute/hour/day/month/weekday fields with per-field checks. Always shows the schedule in words.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CronSchedulePicker(schedule: CronSchedule, onChange: (CronSchedule) -> Unit, error: String? = null, startCustom: Boolean = false) {
    var custom by rememberSaveable { mutableStateOf(startCustom || CronText.presetOf(schedule) == CronPreset.CUSTOM) }
    val preset = if (custom) CronPreset.CUSTOM else CronText.presetOf(schedule)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CronPreset.entries.forEach { p ->
                FilterChip(selected = preset == p, onClick = {
                    custom = p == CronPreset.CUSTOM
                    if (p != CronPreset.CUSTOM) onChange(CronText.applyPreset(schedule, p))
                }, label = { Text(p.label) })
            }
        }
        val minute = schedule.minute.trim().toIntOrNull() ?: 0
        val hour = schedule.hour.trim().toIntOrNull() ?: 0
        when (preset) {
            CronPreset.HOURLY -> PickField(
                "Minutes past the hour", minute, ((0..55 step 5) + minute).distinct().sorted(), { ":%02d".format(it) },
                onPick = { onChange(schedule.copy(minute = "$it")) },
            )
            CronPreset.DAILY -> TimeField(hour, minute) { h, m -> onChange(schedule.copy(hour = "$h", minute = "$m")) }
            CronPreset.WEEKLY -> {
                val day = CronText.dayIndex(schedule.dow) ?: 0
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    CronText.DAY_NAMES.forEachIndexed { i, name ->
                        FilterChip(selected = day == i, onClick = { onChange(schedule.copy(dow = "$i")) }, label = { Text(name.take(3)) })
                    }
                }
                TimeField(hour, minute) { h, m -> onChange(schedule.copy(hour = "$h", minute = "$m")) }
            }
            CronPreset.MONTHLY -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.weight(1f)) {
                    PickField("Day of month", schedule.dom.trim().toIntOrNull() ?: 1, (1..31).toList(), { CronText.ordinal(it) },
                        onPick = { onChange(schedule.copy(dom = "$it")) },
                        supporting = if ((schedule.dom.trim().toIntOrNull() ?: 1) > 28) "Skipped in shorter months" else null)
                }
                Box(Modifier.weight(1f)) { TimeField(hour, minute) { h, m -> onChange(schedule.copy(hour = "$h", minute = "$m")) } }
            }
            CronPreset.CUSTOM -> CustomFields(schedule, onChange)
        }
        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f), modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.EventRepeat, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    val valid = CronText.isValid(schedule)
                    Text(if (valid) CronText.describe(schedule) else "Incomplete schedule", style = MaterialTheme.typography.titleSmall,
                        color = if (valid) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error)
                    Text(schedule.expression, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun CustomFields(schedule: CronSchedule, onChange: (CronSchedule) -> Unit) {
    @Composable
    fun field(f: CronField, modifier: Modifier) {
        val v = CronText.get(schedule, f)
        val err = CronText.fieldError(f, v)
        OutlinedTextField(
            value = v, onValueChange = { onChange(CronText.set(schedule, f, it.replace(" ", ""))) },
            label = { Text(f.label, maxLines = 1) }, singleLine = true, isError = err != null,
            supportingText = { Text(err ?: f.hint, maxLines = 2) },
            textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
            modifier = modifier,
        )
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { field(CronField.MINUTE, Modifier.weight(1f)); field(CronField.HOUR, Modifier.weight(1f)) }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { field(CronField.DOM, Modifier.weight(1f)); field(CronField.MONTH, Modifier.weight(1f)) }
        field(CronField.DOW, Modifier.fillMaxWidth())
        Text("Use * for every, */15 for every 15, 1-5 for a range and commas for lists.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Read-only time field ("03:00") that opens the Material time picker. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimeField(hour: Int, minute: Int, onPick: (Int, Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = "%02d:%02d".format(hour, minute), onValueChange = {}, readOnly = true, singleLine = true,
            label = { Text("Time") }, trailingIcon = { Icon(Icons.Rounded.AccessTime, null) }, modifier = Modifier.fillMaxWidth(),
        )
        Box(Modifier.matchParentSize().clickable { open = true })
    }
    if (open) {
        val state = rememberTimePickerState(initialHour = hour, initialMinute = minute, is24Hour = true)
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text("Run at") },
            text = { TimePicker(state = state) },
            confirmButton = { TextButton(onClick = { open = false; onPick(state.hour, state.minute) }) { Text("OK") } },
            dismissButton = { TextButton(onClick = { open = false }) { Text("Cancel") } },
        )
    }
}
