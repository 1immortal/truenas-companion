package app.truenascompanion.ui.protection

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.truenascompanion.data.model.CronSchedule
import app.truenascompanion.data.protection.SchedulePreset
import app.truenascompanion.data.protection.Schedules

@Composable
internal fun SwitchRow(label: String, checked: Boolean, supporting: String? = null, onChange: (Boolean) -> Unit) =
    app.truenascompanion.ui.components.SwitchRow(label, checked, onChange, supporting = supporting)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun <T> Choice(title: String, value: T?, options: List<T>, modifier: Modifier = Modifier, label: (T) -> String = { it.toString() }, onPick: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = open, onExpandedChange = { open = it && options.isNotEmpty() }, modifier = modifier) {
        OutlinedTextField(
            value = value?.let(label) ?: "—", onValueChange = {}, readOnly = true, singleLine = true,
            label = { Text(title, maxLines = 1) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(open) },
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
        )
        androidx.compose.material3.DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { o -> DropdownMenuItem(text = { Text(label(o), maxLines = 1, overflow = TextOverflow.Ellipsis) }, onClick = { open = false; onPick(o) }) }
        }
    }
}

/** Preset chips (hourly/daily/weekly/monthly with a time and day) plus a custom cron field. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ScheduleEditor(schedule: CronSchedule, onChange: (CronSchedule) -> Unit, allowHourly: Boolean = true) {
    var custom by rememberSaveable { mutableStateOf(Schedules.presetOf(schedule) == SchedulePreset.CUSTOM) }
    val preset = if (custom) SchedulePreset.CUSTOM else Schedules.presetOf(schedule)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SchedulePreset.entries.filter { allowHourly || it != SchedulePreset.HOURLY }.forEach { p ->
                FilterChip(selected = preset == p, onClick = {
                    if (p == SchedulePreset.CUSTOM) custom = true
                    else {
                        custom = false
                        val base = p.schedule!!
                        // Keep the chosen time when switching between daily/weekly/monthly.
                        val keepTime = preset in setOf(SchedulePreset.DAILY, SchedulePreset.WEEKLY, SchedulePreset.MONTHLY) && p != SchedulePreset.HOURLY
                        onChange(schedule.copy(
                            minute = if (keepTime) schedule.minute else base.minute,
                            hour = if (keepTime) schedule.hour else base.hour,
                            dom = base.dom, month = base.month, dow = base.dow,
                        ))
                    }
                }, label = { Text(p.label) })
            }
        }
        when (preset) {
            SchedulePreset.DAILY, SchedulePreset.WEEKLY, SchedulePreset.MONTHLY -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val hours = (0..23).toList()
                Choice("Time", schedule.hour.trim().toIntOrNull(), hours, Modifier.weight(1f), label = { "%02d:00".format(it) }) {
                    onChange(schedule.copy(hour = it.toString(), minute = "0"))
                }
                if (preset == SchedulePreset.WEEKLY) {
                    Choice("Day", Schedules.dayIndex(schedule.dow), (0..6).toList(), Modifier.weight(1f), label = { Schedules.dayNames[it] }) {
                        onChange(schedule.copy(dow = Schedules.dayKeys[it]))
                    }
                }
                if (preset == SchedulePreset.MONTHLY) {
                    Choice("Day of month", schedule.dom.trim().toIntOrNull(), (1..28).toList(), Modifier.weight(1f)) { onChange(schedule.copy(dom = it.toString())) }
                }
            }
            SchedulePreset.CUSTOM -> {
                var text by rememberSaveable { mutableStateOf(schedule.expression) }
                val parsed = Schedules.parse(text, schedule)
                OutlinedTextField(
                    value = text, onValueChange = { text = it; Schedules.parse(it, schedule)?.let(onChange) },
                    label = { Text("Cron: minute hour day month weekday") }, singleLine = true, isError = parsed == null,
                    supportingText = { Text(if (parsed == null) "Five fields, e.g. 0 3 * * sun" else Schedules.describe(parsed)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            SchedulePreset.HOURLY -> Unit
        }
        if (preset != SchedulePreset.CUSTOM) {
            Text(Schedules.describe(schedule), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(2.dp))
    }
}
