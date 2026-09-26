package app.truenascompanion.data.protection

import app.truenascompanion.data.model.AlertItem
import app.truenascompanion.data.model.BackupTask
import app.truenascompanion.data.model.Health
import app.truenascompanion.data.model.Pool
import app.truenascompanion.data.model.ScrubTask
import app.truenascompanion.data.model.SmartSchedule
import app.truenascompanion.data.model.SnapshotTask
import app.truenascompanion.util.Format

enum class ProtectionStatus(val health: Health, val rank: Int) {
    OK(Health.HEALTHY, 1), RUNNING(Health.HEALTHY, 2), NONE(Health.UNKNOWN, 0), OVERDUE(Health.WARNING, 3), FAILED(Health.CRITICAL, 4)
}

data class ProtectionItem(val status: ProtectionStatus, val headline: String, val detail: String?, val lastMillis: Long? = null)

data class ProtectionSummary(
    val snapshots: ProtectionItem,
    val scrub: ProtectionItem,
    val smart: ProtectionItem,
    val backup: ProtectionItem,
) {
    val items: List<ProtectionItem> get() = listOf(snapshots, scrub, smart, backup)
    val worst: ProtectionStatus get() = items.maxBy { it.status.rank }.status
    val problems: Int get() = items.count { it.status == ProtectionStatus.FAILED || it.status == ProtectionStatus.OVERDUE }
}

/** Everything the summary needs; each list is null when it couldn't be loaded (e.g. missing permission). */
data class ProtectionInputs(
    val snapshotTasks: List<SnapshotTask>?,
    val pools: List<Pool>?,
    val scrubTasks: List<ScrubTask>?,
    val smartSchedules: List<SmartSchedule>?,
    val alerts: List<AlertItem>?,
    val backups: List<BackupTask>?,
)

/** Pure, unit tested: turns task/pool state into the four lines of the Protection card. */
object ProtectionSummarizer {
    private const val HOUR = 3_600_000L
    private const val DAY = 24 * HOUR
    /** Grace on top of the expected interval before a task counts as overdue. */
    private fun overdue(last: Long, intervalMs: Long, now: Long) = now - last > 2 * intervalMs + HOUR

    fun summarize(i: ProtectionInputs, now: Long = System.currentTimeMillis()): ProtectionSummary =
        ProtectionSummary(snapshots(i.snapshotTasks, now), scrub(i.pools, i.scrubTasks, now), smart(i.smartSchedules, i.alerts), backup(i.backups, now))

    private fun ago(ms: Long?, now: Long) = ms?.let { Format.relativeTime(it, now) } ?: "never"

    fun snapshots(tasks: List<SnapshotTask>?, now: Long): ProtectionItem {
        tasks ?: return ProtectionItem(ProtectionStatus.NONE, "Snapshots", "Couldn't load snapshot tasks")
        val enabled = tasks.filter { it.enabled }
        if (enabled.isEmpty()) return ProtectionItem(ProtectionStatus.NONE, "No periodic snapshots", "Set up a snapshot task to protect your data")
        val last = enabled.mapNotNull { t -> t.state?.takeIf { it.state == "FINISHED" }?.atMillis }.maxOrNull()
        enabled.firstOrNull { it.state?.state == "ERROR" }?.let { t ->
            return ProtectionItem(ProtectionStatus.FAILED, "Snapshot task failed", "${t.dataset}: ${t.state?.error ?: "error"}", last)
        }
        if (enabled.any { it.state?.state == "RUNNING" }) return ProtectionItem(ProtectionStatus.RUNNING, "Taking snapshots…", null, last)
        val late = enabled.firstOrNull { t ->
            val at = t.state?.takeIf { it.state == "FINISHED" }?.atMillis
            at != null && overdue(at, Schedules.approxIntervalMillis(t.schedule), now)
        }
        if (late != null) return ProtectionItem(ProtectionStatus.OVERDUE, "Snapshots overdue", "${late.dataset}: last ${ago(late.state?.atMillis, now)}", last)
        return ProtectionItem(ProtectionStatus.OK, "Last snapshot ${ago(last, now)}", "${enabled.size} task" + if (enabled.size == 1) "" else "s", last)
    }

    fun scrub(pools: List<Pool>?, tasks: List<ScrubTask>?, now: Long): ProtectionItem {
        pools ?: return ProtectionItem(ProtectionStatus.NONE, "Scrubs", "Couldn't load pools")
        if (pools.isEmpty()) return ProtectionItem(ProtectionStatus.NONE, "No pools", null)
        pools.firstOrNull { it.scrubRunning }?.let { p ->
            return ProtectionItem(ProtectionStatus.RUNNING, "Scrub running on ${p.name}", Format.percent(p.scanPercent), p.scanStartMillis)
        }
        val scrubbed = pools.filter { it.scanFunction.equals("SCRUB", true) }
        scrubbed.firstOrNull { (it.scanErrors ?: 0) > 0 }?.let { p ->
            return ProtectionItem(ProtectionStatus.FAILED, "Scrub found errors", "${p.name}: ${p.scanErrors} errors", p.scanEndMillis)
        }
        // The pool scrubbed longest ago decides; never-scrubbed pools are overdue.
        val thresholdDays = { p: Pool -> (tasks?.firstOrNull { it.poolName == p.name && it.enabled }?.threshold ?: 35).coerceAtLeast(1) }
        val oldest = pools.minByOrNull { p -> scrubbed.firstOrNull { it.name == p.name }?.scanEndMillis ?: Long.MIN_VALUE }!!
        val oldestEnd = scrubbed.firstOrNull { it.name == oldest.name }?.scanEndMillis
        val lastAny = scrubbed.mapNotNull { it.scanEndMillis }.maxOrNull()
        val hasSchedule = tasks?.any { it.enabled } == true
        return when {
            oldestEnd == null && !hasSchedule -> ProtectionItem(ProtectionStatus.OVERDUE, "${oldest.name} was never scrubbed", "No scrub schedule", lastAny)
            oldestEnd == null -> ProtectionItem(ProtectionStatus.OVERDUE, "${oldest.name} was never scrubbed", null, lastAny)
            now - oldestEnd > (thresholdDays(oldest) + 7) * DAY -> ProtectionItem(ProtectionStatus.OVERDUE, "Scrub overdue", "${oldest.name}: last ${ago(oldestEnd, now)}", oldestEnd)
            else -> ProtectionItem(ProtectionStatus.OK, "Last scrub ${ago(oldestEnd, now)}", if (pools.size > 1) "Oldest of ${pools.size} pools" else oldest.name, oldestEnd)
        }
    }

    fun smart(schedules: List<SmartSchedule>?, alerts: List<AlertItem>?): ProtectionItem {
        val smartAlerts = alerts.orEmpty().filter { !it.dismissed && it.klass?.startsWith("SMART", ignoreCase = true) == true }
        if (smartAlerts.isNotEmpty()) {
            val worst = smartAlerts.maxBy { if (it.health == Health.CRITICAL) 2 else 1 }
            return ProtectionItem(ProtectionStatus.FAILED, "SMART problem reported", worst.text.lineSequence().first().take(120))
        }
        schedules ?: return ProtectionItem(ProtectionStatus.NONE, "SMART tests", "Couldn't load schedules")
        val enabled = schedules.filter { it.enabled && it.description != app.truenascompanion.data.api.ProtectionApi.ONE_OFF_DESCRIPTION }
        if (enabled.isEmpty()) return ProtectionItem(ProtectionStatus.NONE, "No SMART test schedule", "TrueNAS reports failed tests as alerts")
        val kinds = enabled.map { "${it.type.label}: ${Schedules.describe(it.schedule)}" }.distinct()
        return ProtectionItem(ProtectionStatus.OK, "No SMART problems reported", kinds.take(2).joinToString(" · ") + if (kinds.size > 2) " · +${kinds.size - 2}" else "")
    }

    fun backup(tasks: List<BackupTask>?, now: Long): ProtectionItem {
        tasks ?: return ProtectionItem(ProtectionStatus.NONE, "Backups", "Couldn't load backup tasks")
        val enabled = tasks.filter { it.enabled }
        if (enabled.isEmpty()) return ProtectionItem(ProtectionStatus.NONE, "No backup tasks", "Replication, cloud sync or rsync")
        val last = enabled.mapNotNull { it.lastSuccessMillis }.maxOrNull()
        enabled.firstOrNull { it.failed }?.let { t ->
            return ProtectionItem(ProtectionStatus.FAILED, "Backup failed", "${t.name}: ${(t.lastJob?.error ?: t.state?.error ?: "error").lineSequence().first().take(100)}", last)
        }
        enabled.firstOrNull { it.running }?.let { t -> return ProtectionItem(ProtectionStatus.RUNNING, "Backing up: ${t.name}", t.lastJob?.percent?.let { Format.percent(it) }, last) }
        val late = enabled.firstOrNull { t ->
            val s = t.schedule ?: return@firstOrNull false
            val at = t.lastSuccessMillis ?: return@firstOrNull false
            overdue(at, Schedules.approxIntervalMillis(s), now)
        }
        if (late != null) return ProtectionItem(ProtectionStatus.OVERDUE, "Backup overdue", "${late.name}: last ${ago(late.lastSuccessMillis, now)}", last)
        return ProtectionItem(
            ProtectionStatus.OK,
            if (last != null) "Last backup ${ago(last, now)}" else "Backups set up",
            "${enabled.size} task" + (if (enabled.size == 1) "" else "s") + if (last == null) " · no run yet" else "",
            last,
        )
    }
}
