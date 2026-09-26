package app.truenascompanion

import app.truenascompanion.data.model.AlertItem
import app.truenascompanion.data.model.BackupKind
import app.truenascompanion.data.model.BackupTask
import app.truenascompanion.data.model.CronSchedule
import app.truenascompanion.data.model.Disk
import app.truenascompanion.data.model.JobState
import app.truenascompanion.data.model.LastJob
import app.truenascompanion.data.model.Pool
import app.truenascompanion.data.model.ScrubTask
import app.truenascompanion.data.model.SmartSchedule
import app.truenascompanion.data.model.SmartTestType
import app.truenascompanion.data.model.Snapshot
import app.truenascompanion.data.model.SnapshotTask
import app.truenascompanion.data.model.TaskRunState

/** Example data for protection tests and previews (fictional). */
object ProtectionSamples {
    /** 2026-09-26 10:20 UTC (13:20 in Jerusalem), matching the sample snapshot names. */
    const val NOW = 1_790_418_000_000L
    const val H = 3_600_000L
    const val D = 24 * H

    fun pool(name: String = "tank", fn: String? = "SCRUB", state: String? = "FINISHED", pct: Double? = 100.0, errors: Long? = 0,
             end: Long? = NOW - 9 * D, start: Long? = end?.minus(5 * H), id: Long = 1) =
        Pool(id, name, "ONLINE", true, false, null, 16_000_000_000_000, 9_300_000_000_000, 6_700_000_000_000, "3%",
            fn, state, pct, errors, listOf("sda", "sdb"), start, end)

    val daily3 = CronSchedule(minute = "0", hour = "3")
    val hourly = CronSchedule(minute = "0", hour = "*", begin = "00:00", end = "23:59")

    fun snapTask(id: Int = 1, dataset: String = "tank/photos", state: String? = "FINISHED", at: Long? = NOW - 20 * 60_000, enabled: Boolean = true,
                 schedule: CronSchedule = hourly, error: String? = null) =
        SnapshotTask(id, dataset, false, emptyList(), 2, "WEEK", "auto-%Y-%m-%d_%H-%M", schedule, enabled, true,
            state?.let { TaskRunState(it, at, error) })

    fun job(state: JobState = JobState.SUCCESS, finished: Long? = NOW - 7 * H, error: String? = null, pct: Double? = 100.0, log: String? = null) =
        LastJob(state, finished?.minus(20 * 60_000), finished, error, log, pct, null)

    fun backup(kind: BackupKind = BackupKind.CLOUD_SYNC, id: Int = 1, name: String = "Photos to B2", job: LastJob? = job(),
               enabled: Boolean = true, schedule: CronSchedule? = daily3) =
        BackupTask(kind, id, name, "Push from /mnt/tank/photos · Backblaze B2 · sync", enabled, schedule, job, null)

    fun smart(id: Int = 10, type: SmartTestType = SmartTestType.SHORT, disks: List<String> = listOf("*"), enabled: Boolean = true,
              schedule: CronSchedule = CronSchedule(minute = "0", hour = "3", dow = "sun"), description: String = "S.M.A.R.T. Test") =
        SmartSchedule(id, type, disks, schedule, enabled, description)

    fun alert(klass: String, text: String, level: String = "CRITICAL") = AlertItem("u-$klass", level, text, klass, NOW - H, false, false)

    /** An all-good summary for dashboard previews. */
    val healthySummary get() = app.truenascompanion.data.protection.ProtectionSummarizer.summarize(
        app.truenascompanion.data.protection.ProtectionInputs(
            listOf(snapTask(), snapTask(2, "fast/apps", at = NOW - 9 * H, schedule = daily3)), listOf(pool(), pool("fast", end = NOW - 12 * D, id = 2)),
            listOf(scrubTask), listOf(smart()), emptyList(), listOf(backup(), backup(BackupKind.REPLICATION, 2, "tank → backup-nas", job(finished = NOW - 22 * H))),
        ), NOW,
    )

    val scrubTask = ScrubTask(1, 1, "tank", 35, "", CronSchedule(minute = "00", hour = "00", dow = "7"), true)

    val disks = listOf(
        Disk(name = "sda", model = "WDC WD80EFZZ", serial = "S1", size = 8_001_563_222_016, type = "HDD", rotationRate = 7200, pool = "tank", temperatureC = null, identifier = "{serial_lunid}5000cca0000001"),
        Disk(name = "sdb", model = "WDC WD80EFZZ", serial = "S2", size = 8_001_563_222_016, type = "HDD", rotationRate = 7200, pool = "tank", temperatureC = null, identifier = "{serial_lunid}5000cca0000002"),
        Disk(name = "nvme0n1", model = "Samsung 980 PRO", serial = "S3", size = 1_000_204_886_016, type = "SSD", rotationRate = null, pool = "fast", temperatureC = null, identifier = "{serial}S5GXNX0T000000"),
    )

    val snapshots = listOf(
        Snapshot("tank/photos@auto-2026-09-26_13-00", "tank/photos", "auto-2026-09-26_13-00", NOW - 20 * 60_000, 18_400_000, 412_000_000_000, false),
        Snapshot("tank/photos@auto-2026-09-26_12-00", "tank/photos", "auto-2026-09-26_12-00", NOW - 80 * 60_000, 2_100_000, 411_900_000_000, false),
        Snapshot("tank/photos@manual-2026-09-20_18-42", "tank/photos", "manual-2026-09-20_18-42", NOW - 5 * D - 18 * H - 38 * 60_000, 1_240_000_000, 409_000_000_000, true),
        Snapshot("tank/photos@auto-2026-09-19_00-00", "tank/photos", "auto-2026-09-19_00-00", NOW - 7 * D - 13 * H - 20 * 60_000, 356_000_000, 405_500_000_000, false),
    )
}
