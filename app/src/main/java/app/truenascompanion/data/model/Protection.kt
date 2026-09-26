package app.truenascompanion.data.model

/** A ZFS snapshot (`pool.snapshot.query`). */
data class Snapshot(
    val id: String,
    val dataset: String,
    val name: String,
    val createdMillis: Long?,
    val usedBytes: Long?,
    val referencedBytes: Long?,
    val held: Boolean,
)

/** TrueNAS cron schedule (`CronModel`: minute, hour, dom, month, dow; snapshot tasks add begin/end). */
data class CronSchedule(
    val minute: String = "00",
    val hour: String = "*",
    val dom: String = "*",
    val month: String = "*",
    val dow: String = "*",
    val begin: String? = null,
    val end: String? = null,
) {
    val expression: String get() = "$minute $hour $dom $month $dow"
}

/** zettarepl task state (`{"state": "FINISHED", "datetime": …, "error": …}`) of snapshot and replication tasks. */
data class TaskRunState(val state: String, val atMillis: Long?, val error: String?)

data class SnapshotTask(
    val id: Int,
    val dataset: String,
    val recursive: Boolean,
    val exclude: List<String>,
    val lifetimeValue: Int,
    val lifetimeUnit: String,
    val namingSchema: String,
    val schedule: CronSchedule,
    val enabled: Boolean,
    val allowEmpty: Boolean,
    val state: TaskRunState?,
)

/** Scrub schedule (`pool.scrub.query`). */
data class ScrubTask(
    val id: Int,
    val poolId: Long,
    val poolName: String,
    val threshold: Int,
    val description: String,
    val schedule: CronSchedule,
    val enabled: Boolean,
)

enum class SmartTestType(val label: String) { SHORT("Short"), LONG("Long"), CONVEYANCE("Conveyance"), OFFLINE("Offline") }

/**
 * TrueNAS 25.10 removed S.M.A.R.T. from its UI and API. Existing test schedules were migrated to cron jobs running
 * `midclt call disk.smart_test <TYPE> '<disks JSON>'` (alembic `drop_smart`). This is one of those cron jobs.
 * [disks] is `["*"]` for all disks or a list of disk identifiers (`disk.query` `identifier`).
 */
data class SmartSchedule(
    val cronId: Int,
    val type: SmartTestType,
    val disks: List<String>,
    val schedule: CronSchedule,
    val enabled: Boolean,
    val description: String,
) {
    val allDisks: Boolean get() = "*" in disks
}

enum class BackupKind(val label: String) { REPLICATION("Replication"), CLOUD_SYNC("Cloud sync"), RSYNC("Rsync") }

/** The last run of a task (`job` field of the task, a `core.get_jobs` entry). */
data class LastJob(
    val state: JobState,
    val startedMillis: Long?,
    val finishedMillis: Long?,
    val error: String?,
    val logExcerpt: String?,
    val percent: Double?,
    val progressText: String?,
)

data class BackupTask(
    val kind: BackupKind,
    val id: Int,
    val name: String,
    val detail: String,
    val enabled: Boolean,
    val schedule: CronSchedule?,
    val lastJob: LastJob?,
    /** Replication only (zettarepl state). */
    val state: TaskRunState?,
    val locked: Boolean = false,
) {
    val running: Boolean get() = lastJob?.state == JobState.RUNNING || lastJob?.state == JobState.WAITING || state?.state == "RUNNING"
    val failed: Boolean get() = !running && (lastJob?.state == JobState.FAILED || (lastJob == null && state?.state == "ERROR") || (state?.state == "ERROR" && lastJob?.state != JobState.SUCCESS))
    /** Time of the last successful run, if known. */
    val lastSuccessMillis: Long? get() = when {
        lastJob?.state == JobState.SUCCESS -> lastJob.finishedMillis ?: lastJob.startedMillis
        state?.state == "FINISHED" -> state.atMillis
        else -> null
    }
}
