package app.truenascompanion.ui.protection

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.MonitorHeart
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.truenascompanion.data.model.BackupKind
import app.truenascompanion.data.model.BackupTask
import app.truenascompanion.data.model.Health
import app.truenascompanion.data.model.JobState
import app.truenascompanion.data.model.Pool
import app.truenascompanion.data.model.ScrubTask
import app.truenascompanion.data.model.SmartSchedule
import app.truenascompanion.data.model.SnapshotTask
import app.truenascompanion.data.protection.ProtectionItem
import app.truenascompanion.data.protection.ProtectionStatus
import app.truenascompanion.data.protection.ProtectionSummary
import app.truenascompanion.data.protection.Schedules
import app.truenascompanion.ui.components.ElevatedSection
import app.truenascompanion.ui.components.IconBadge
import app.truenascompanion.ui.components.InfoBanner
import app.truenascompanion.ui.components.SectionTitle
import app.truenascompanion.ui.components.StatusChip
import app.truenascompanion.ui.theme.LocalStatusColors
import app.truenascompanion.util.Format

/** Callbacks of the Protection overview (no-ops by default, for previews). */
open class ProtectionActions {
    open fun startScrub(pool: Pool) {}
    open fun stopScrub(pool: Pool) {}
    open fun pauseScrub(pool: Pool) {}
    open fun editScrubSchedule(pool: Pool, task: ScrubTask?) {}
    open fun addSnapshotTask() {}
    open fun editSnapshotTask(t: SnapshotTask) {}
    open fun toggleSnapshotTask(t: SnapshotTask, on: Boolean) {}
    open fun runSnapshotTask(t: SnapshotTask) {}
    open fun deleteSnapshotTask(t: SnapshotTask) {}
    open fun runSmartNow() {}
    open fun addSmartSchedule() {}
    open fun editSmartSchedule(s: SmartSchedule) {}
    open fun toggleSmart(s: SmartSchedule, on: Boolean) {}
    open fun runSmartSchedule(s: SmartSchedule) {}
    open fun deleteSmartSchedule(s: SmartSchedule) {}
    open fun toggleBackup(t: BackupTask, on: Boolean) {}
    open fun runBackup(t: BackupTask) {}
    open fun showLog(t: BackupTask) {}
}

fun ProtectionStatus.label(): String = when (this) {
    ProtectionStatus.OK -> "OK"; ProtectionStatus.RUNNING -> "Running"; ProtectionStatus.NONE -> "Not set up"
    ProtectionStatus.OVERDUE -> "Overdue"; ProtectionStatus.FAILED -> "Failed"
}

/** The four summary lines, shared by the dashboard card and the Protection overview. */
@Composable
fun ProtectionRows(summary: ProtectionSummary, compact: Boolean = false) {
    val icons = listOf(Icons.Rounded.PhotoCamera, Icons.Rounded.CleaningServices, Icons.Rounded.MonitorHeart, Icons.Rounded.Backup)
    Column(verticalArrangement = Arrangement.spacedBy(if (compact) 10.dp else 14.dp)) {
        summary.items.forEachIndexed { i, item -> ProtectionRow(icons[i], item, compact) }
    }
}

@Composable
private fun ProtectionRow(icon: ImageVector, item: ProtectionItem, compact: Boolean) {
    val color = LocalStatusColors.current.of(item.status.health)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(contentAlignment = Alignment.BottomEnd) {
            IconBadge(icon, tint = if (item.status == ProtectionStatus.NONE) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary, size = if (compact) 34.dp else 40.dp)
            Box(Modifier.size(10.dp).background(color, CircleShape))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(item.headline, style = if (compact) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            item.detail?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        if (item.status == ProtectionStatus.FAILED || item.status == ProtectionStatus.OVERDUE) {
            Spacer(Modifier.width(8.dp))
            StatusChip(item.status.health, item.status.label(), showIcon = false)
        }
    }
}

@Composable
fun ProtectionContent(data: ProtectionData, busy: Set<String>, actions: ProtectionActions, modifier: Modifier = Modifier) {
    val scrubTasks = data.scrubTasks.orEmpty()
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { ElevatedSection { ProtectionRows(data.summary) } }

        item { SectionTitle("Scrubs") }
        items(data.pools, key = { "pool-${it.id}" }) { p ->
            ScrubCard(p, scrubTasks.firstOrNull { it.poolName == p.name }, "scrub:${p.name}" in busy, data.now, actions)
        }

        item {
            SectionTitle("Snapshot tasks") {
                TextButton(onClick = actions::addSnapshotTask) { Icon(Icons.Rounded.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Add") }
            }
        }
        val tasks = data.snapshotTasks
        when {
            tasks == null -> item { InfoBanner("Couldn't load snapshot tasks.") }
            tasks.isEmpty() -> item { EmptyCard("No periodic snapshot tasks", "Snapshots let you undo mistakes and recover deleted files. Add a task to take them automatically.") }
            else -> items(tasks, key = { "snaptask-${it.id}" }) { t -> SnapshotTaskCard(t, "snaptask:${t.id}" in busy, data.now, actions) }
        }

        item {
            SectionTitle("SMART tests") {
                TextButton(onClick = actions::addSmartSchedule) { Icon(Icons.Rounded.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Schedule") }
            }
        }
        item {
            ElevatedSection {
                Text(
                    "TrueNAS 25.10 no longer shows SMART test results or progress. Tests run in the background on the disks, and TrueNAS raises an alert if one fails.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                data.smartAlerts.forEach { a ->
                    Spacer(Modifier.height(10.dp))
                    InfoBanner(a.text.lineSequence().first(), health = a.health)
                }
                Spacer(Modifier.height(12.dp))
                FilledTonalButton(onClick = actions::runSmartNow, enabled = "smart:now" !in busy, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.PlayArrow, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Run a SMART test now", maxLines = 1)
                }
            }
        }
        data.visibleSmart?.let { list ->
            items(list, key = { "smart-${it.cronId}" }) { s -> SmartCard(s, data, "smart:${s.cronId}" in busy, actions) }
        }

        item { SectionTitle("Backup tasks") }
        val backups = data.backups
        when {
            backups == null -> item { InfoBanner("Couldn't load backup tasks.") }
            backups.isEmpty() -> item { EmptyCard("No backup tasks", "Create replication, cloud sync or rsync tasks in the TrueNAS web UI (Data Protection). They show up here with their status.") }
            else -> items(backups, key = { "backup-${it.kind}-${it.id}" }) { t -> BackupCard(t, "backup:${t.kind}:${t.id}" in busy, data.now, actions) }
        }
        item {
            Text(
                "Create or fully edit replication, cloud sync and rsync tasks in the TrueNAS web UI.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun EmptyCard(title: String, text: String) {
    ElevatedSection {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(4.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Overflow(items: List<Pair<String, () -> Unit>>, enabled: Boolean = true) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, enabled = enabled) { Icon(Icons.Rounded.MoreVert, "More actions") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            items.forEach { (label, act) -> DropdownMenuItem(text = { Text(label) }, onClick = { open = false; act() }) }
        }
    }
}

@Composable
private fun ScrubCard(p: Pool, task: ScrubTask?, busy: Boolean, now: Long, actions: ProtectionActions) {
    ElevatedSection {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(Icons.Rounded.CleaningServices, tint = LocalStatusColors.current.of(p.health))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(p.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val last = when {
                    p.scrubRunning -> "Scrubbing now" + (p.scanStartMillis?.let { " · started ${Format.relativeTime(it, now)}" } ?: "")
                    p.scanFunction.equals("SCRUB", true) && p.scanEndMillis != null ->
                        "Last scrub ${Format.relativeTime(p.scanEndMillis, now)}" + (p.scanErrors?.let { " · $it errors" } ?: "")
                    p.scanFunction.equals("RESILVER", true) && p.scanState.equals("SCANNING", true) -> "Resilvering"
                    else -> "Never scrubbed"
                }
                Text(last, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if ((p.scanErrors ?: 0) > 0 && p.scanFunction.equals("SCRUB", true)) StatusChip(Health.CRITICAL, "Errors", showIcon = false)
        }
        if (p.scrubRunning) {
            Spacer(Modifier.height(12.dp))
            LinearProgressIndicator(progress = { ((p.scanPercent ?: 0.0) / 100.0).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(8.dp))
            Spacer(Modifier.height(4.dp))
            Text(Format.percent(p.scanPercent) + " done", style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Schedule, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(6.dp))
            Text(
                task?.let { (if (it.enabled) Schedules.describe(it.schedule) else "Schedule off") + " · every ${it.threshold}+ days" } ?: "No schedule",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f),
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            TextButton(onClick = { actions.editScrubSchedule(p, task) }) { Text(if (task == null) "Add" else "Edit", maxLines = 1) }
        }
        Spacer(Modifier.height(4.dp))
        if (p.scrubRunning) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { actions.pauseScrub(p) }, enabled = !busy, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Rounded.Pause, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Pause", maxLines = 1)
                }
                OutlinedButton(onClick = { actions.stopScrub(p) }, enabled = !busy, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Rounded.Stop, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Stop", maxLines = 1)
                }
            }
        } else {
            FilledTonalButton(onClick = { actions.startScrub(p) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.PlayArrow, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Start scrub", maxLines = 1)
            }
        }
    }
}

fun lifetimeText(value: Int, unit: String): String {
    val u = unit.lowercase().replaceFirstChar { it.uppercase() }.let { if (value == 1) it else it + "s" }
    return "$value ${u.lowercase()}"
}

@Composable
private fun TaskStateLine(state: String?, atMillis: Long?, error: String?, now: Long, prefix: String) {
    val (health, text) = when (state) {
        "FINISHED" -> Health.HEALTHY to "$prefix ${Format.relativeTime(atMillis, now)}"
        "RUNNING" -> Health.HEALTHY to "Running now"
        "ERROR" -> Health.CRITICAL to (error?.lineSequence()?.firstOrNull() ?: "Failed")
        "HOLD" -> Health.WARNING to "On hold" + (error?.let { ": $it" } ?: "")
        "WAITING" -> Health.UNKNOWN to "Waiting"
        null, "PENDING" -> Health.UNKNOWN to "Hasn't run yet"
        else -> Health.UNKNOWN to state.lowercase().replaceFirstChar { it.uppercase() }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).background(LocalStatusColors.current.of(health), CircleShape))
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = if (health == Health.CRITICAL) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SnapshotTaskCard(t: SnapshotTask, busy: Boolean, now: Long, actions: ProtectionActions) {
    ElevatedSection(onClick = { actions.editSnapshotTask(t) }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(Icons.Rounded.PhotoCamera)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(t.dataset, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("${Schedules.describe(t.schedule)} · keep ${lifetimeText(t.lifetimeValue, t.lifetimeUnit)}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = t.enabled, onCheckedChange = { actions.toggleSnapshotTask(t, it) }, enabled = !busy)
            Overflow(listOf("Edit" to { actions.editSnapshotTask(t) }, "Run now" to { actions.runSnapshotTask(t) }, "Delete" to { actions.deleteSnapshotTask(t) }), enabled = !busy)
        }
        if (t.recursive || t.exclude.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(listOfNotNull("Includes child datasets".takeIf { t.recursive }, t.exclude.takeIf { it.isNotEmpty() }?.let { "excludes ${it.size}" }).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(8.dp))
        TaskStateLine(t.state?.state, t.state?.atMillis, t.state?.error, now, "Last snapshot")
    }
}

@Composable
private fun SmartCard(s: SmartSchedule, data: ProtectionData, busy: Boolean, actions: ProtectionActions) {
    val diskText = if (s.allDisks) "All disks" else s.disks.map { id -> data.disks.firstOrNull { it.identifier == id }?.name ?: id }.joinToString(", ")
    ElevatedSection {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(Icons.Rounded.MonitorHeart, tint = if (s.enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("${s.type.label} test · ${Schedules.describe(s.schedule)}", style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(diskText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Switch(checked = s.enabled, onCheckedChange = { actions.toggleSmart(s, it) }, enabled = !busy)
            Overflow(listOf("Edit" to { actions.editSmartSchedule(s) }, "Run now" to { actions.runSmartSchedule(s) }, "Delete" to { actions.deleteSmartSchedule(s) }), enabled = !busy)
        }
    }
}

private fun BackupKind.icon(): ImageVector = when (this) {
    BackupKind.REPLICATION -> Icons.Rounded.Backup
    BackupKind.CLOUD_SYNC -> Icons.Rounded.Backup
    BackupKind.RSYNC -> Icons.Rounded.Backup
}

@Composable
private fun BackupCard(t: BackupTask, busy: Boolean, now: Long, actions: ProtectionActions) {
    ElevatedSection {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(t.kind.icon(), tint = when {
                t.failed -> MaterialTheme.colorScheme.error
                !t.enabled -> MaterialTheme.colorScheme.onSurfaceVariant
                else -> MaterialTheme.colorScheme.primary
            })
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(t.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${t.kind.label} · ${t.detail}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Switch(checked = t.enabled, onCheckedChange = { actions.toggleBackup(t, it) }, enabled = !busy && !t.locked)
        }
        Spacer(Modifier.height(8.dp))
        val job = t.lastJob
        when {
            t.running -> {
                Text(job?.progressText ?: "Running…", style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(6.dp))
                val pct = job?.percent
                if (pct != null) LinearProgressIndicator(progress = { (pct / 100).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                else LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            job != null -> TaskStateLine(
                when (job.state) { JobState.SUCCESS -> "FINISHED"; JobState.FAILED, JobState.ABORTED -> "ERROR"; else -> job.state.name },
                job.finishedMillis ?: job.startedMillis, job.error ?: if (job.state == JobState.ABORTED) "Aborted" else null, now, "Last run",
            )
            else -> TaskStateLine(t.state?.state, t.state?.atMillis, t.state?.error, now, "Last run")
        }
        if (t.locked) {
            Spacer(Modifier.height(6.dp))
            Text("The dataset is locked; unlock it in the TrueNAS web UI.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = { actions.runBackup(t) }, enabled = !busy && !t.running && t.enabled && !t.locked, modifier = Modifier.weight(1f)) {
                Icon(Icons.Rounded.PlayArrow, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Run now", maxLines = 1)
            }
            OutlinedButton(onClick = { actions.showLog(t) }, enabled = job != null || t.state?.error != null, modifier = Modifier.weight(1f)) {
                Icon(Icons.Rounded.Description, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Last log", maxLines = 1)
            }
        }
    }
}
