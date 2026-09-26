package app.truenascompanion

import app.truenascompanion.ProtectionSamples.D
import app.truenascompanion.ProtectionSamples.H
import app.truenascompanion.ProtectionSamples.NOW
import app.truenascompanion.data.api.ProtectionApi
import app.truenascompanion.data.api.ProtectionParsers
import app.truenascompanion.data.model.BackupKind
import app.truenascompanion.data.model.CronSchedule
import app.truenascompanion.data.model.JobState
import app.truenascompanion.data.model.SmartTestType
import app.truenascompanion.data.protection.ProtectionInputs
import app.truenascompanion.data.protection.ProtectionStatus
import app.truenascompanion.data.protection.ProtectionSummarizer
import app.truenascompanion.data.protection.SchedulePreset
import app.truenascompanion.data.protection.Schedules
import app.truenascompanion.ui.protection.SnapshotSort
import app.truenascompanion.ui.protection.SnapshotTaskForm
import app.truenascompanion.ui.protection.newerThan
import app.truenascompanion.ui.protection.sortSnapshots
import app.truenascompanion.ui.protection.validDatasetPath
import app.truenascompanion.ui.protection.validSnapshotName
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtectionTest {
    private fun obj(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject

    // --- Schedules ---

    @Test fun presets() {
        assertEquals(SchedulePreset.HOURLY, Schedules.presetOf(CronSchedule(minute = "0", hour = "*")))
        assertEquals(SchedulePreset.DAILY, Schedules.presetOf(CronSchedule(minute = "30", hour = "3")))
        assertEquals(SchedulePreset.WEEKLY, Schedules.presetOf(CronSchedule(minute = "00", hour = "00", dow = "7")))
        assertEquals(SchedulePreset.WEEKLY, Schedules.presetOf(CronSchedule(minute = "0", hour = "0", dow = "sun")))
        assertEquals(SchedulePreset.MONTHLY, Schedules.presetOf(CronSchedule(minute = "0", hour = "0", dom = "1")))
        assertEquals(SchedulePreset.CUSTOM, Schedules.presetOf(CronSchedule(minute = "*/15", hour = "*")))
        assertEquals(SchedulePreset.CUSTOM, Schedules.presetOf(CronSchedule(minute = "0", hour = "0", dow = "mon,thu")))
        assertEquals(SchedulePreset.CUSTOM, Schedules.presetOf(CronSchedule(minute = "0", hour = "0", month = "1")))
    }

    @Test fun describe() {
        assertEquals("Every hour", Schedules.describe(CronSchedule(minute = "0", hour = "*")))
        assertEquals("Every hour at :15", Schedules.describe(CronSchedule(minute = "15", hour = "*")))
        assertEquals("Every 6 hours", Schedules.describe(CronSchedule(minute = "0", hour = "*/6")))
        assertEquals("Daily at 03:00", Schedules.describe(CronSchedule(minute = "0", hour = "3")))
        assertEquals("Sundays at 00:00", Schedules.describe(CronSchedule(minute = "00", hour = "00", dow = "7")))
        assertEquals("Mondays at 02:30", Schedules.describe(CronSchedule(minute = "30", hour = "2", dow = "mon")))
        assertEquals("Monthly on day 1 at 00:00", Schedules.describe(CronSchedule(minute = "0", hour = "0", dom = "1")))
        assertEquals("Custom (0 0 1 1 *)", Schedules.describe(CronSchedule(minute = "0", hour = "0", dom = "1", month = "1")))
    }

    @Test fun parse() {
        val base = CronSchedule(begin = "08:00", end = "18:00")
        val p = Schedules.parse("  15 */2 * * mon-fri ", base)!!
        assertEquals("15", p.minute); assertEquals("*/2", p.hour); assertEquals("mon-fri", p.dow); assertEquals("08:00", p.begin)
        assertNull(Schedules.parse("0 3 * *"))
        assertNull(Schedules.parse("0 3 * * * *"))
        assertNull(Schedules.parse("0 3 * * sun; rm"))
    }

    @Test fun intervals() {
        assertEquals(H, Schedules.approxIntervalMillis(CronSchedule(minute = "0", hour = "*")))
        assertEquals(6 * H, Schedules.approxIntervalMillis(CronSchedule(minute = "0", hour = "*/6")))
        assertEquals(D, Schedules.approxIntervalMillis(CronSchedule(minute = "0", hour = "3")))
        assertEquals(D / 2, Schedules.approxIntervalMillis(CronSchedule(minute = "0", hour = "3,15")))
        assertEquals(7 * D, Schedules.approxIntervalMillis(CronSchedule(minute = "0", hour = "0", dow = "sun")))
        assertEquals(31 * D, Schedules.approxIntervalMillis(CronSchedule(minute = "0", hour = "0", dom = "1")))
    }

    // --- Summary ---

    private fun summary(i: ProtectionInputs) = ProtectionSummarizer.summarize(i, NOW)
    private val healthy = ProtectionInputs(
        listOf(ProtectionSamples.snapTask()), listOf(ProtectionSamples.pool()), listOf(ProtectionSamples.scrubTask),
        listOf(ProtectionSamples.smart()), emptyList(), listOf(ProtectionSamples.backup()),
    )

    @Test fun allGood() {
        val s = summary(healthy)
        assertEquals(listOf(ProtectionStatus.OK, ProtectionStatus.OK, ProtectionStatus.OK, ProtectionStatus.OK), s.items.map { it.status })
        assertEquals("Last snapshot 20 min ago", s.snapshots.headline)
        assertEquals(0, s.problems)
        assertEquals(ProtectionStatus.OK, s.worst)
    }

    @Test fun snapshotStates() {
        val none = ProtectionSummarizer.snapshots(listOf(ProtectionSamples.snapTask(enabled = false)), NOW)
        assertEquals(ProtectionStatus.NONE, none.status)
        val failed = ProtectionSummarizer.snapshots(listOf(ProtectionSamples.snapTask(state = "ERROR", error = "dataset is busy")), NOW)
        assertEquals(ProtectionStatus.FAILED, failed.status); assertTrue(failed.detail!!.contains("dataset is busy"))
        // Hourly task: 3h ago is overdue (> 2 × 1h + 1h grace); 2h55m is not.
        assertEquals(ProtectionStatus.OVERDUE, ProtectionSummarizer.snapshots(listOf(ProtectionSamples.snapTask(at = NOW - 3 * H - 1)), NOW).status)
        assertEquals(ProtectionStatus.OK, ProtectionSummarizer.snapshots(listOf(ProtectionSamples.snapTask(at = NOW - 2 * H - 55 * 60_000)), NOW).status)
        assertEquals(ProtectionStatus.NONE, ProtectionSummarizer.snapshots(null, NOW).status)
    }

    @Test fun scrubStates() {
        val tasks = listOf(ProtectionSamples.scrubTask)
        assertEquals(ProtectionStatus.RUNNING, ProtectionSummarizer.scrub(listOf(ProtectionSamples.pool(state = "SCANNING", pct = 41.0)), tasks, NOW).status)
        assertEquals(ProtectionStatus.FAILED, ProtectionSummarizer.scrub(listOf(ProtectionSamples.pool(errors = 3)), tasks, NOW).status)
        // threshold 35 + 7 days grace
        assertEquals(ProtectionStatus.OK, ProtectionSummarizer.scrub(listOf(ProtectionSamples.pool(end = NOW - 41 * D)), tasks, NOW).status)
        assertEquals(ProtectionStatus.OVERDUE, ProtectionSummarizer.scrub(listOf(ProtectionSamples.pool(end = NOW - 43 * D)), tasks, NOW).status)
        val never = ProtectionSummarizer.scrub(listOf(ProtectionSamples.pool(), ProtectionSamples.pool("fast", fn = null, state = null, end = null, id = 2)), tasks, NOW)
        assertEquals(ProtectionStatus.OVERDUE, never.status); assertEquals("fast was never scrubbed", never.headline)
        // A resilver isn't a scrub.
        assertEquals(ProtectionStatus.OVERDUE, ProtectionSummarizer.scrub(listOf(ProtectionSamples.pool(fn = "RESILVER")), emptyList(), NOW).status)
    }

    @Test fun smartStates() {
        assertEquals(ProtectionStatus.NONE, ProtectionSummarizer.smart(emptyList(), emptyList()).status)
        // One-off jobs created by "Run now" don't count as a schedule.
        assertEquals(ProtectionStatus.NONE, ProtectionSummarizer.smart(listOf(ProtectionSamples.smart(description = ProtectionApi.ONE_OFF_DESCRIPTION)), emptyList()).status)
        val ok = ProtectionSummarizer.smart(listOf(ProtectionSamples.smart()), emptyList())
        assertEquals(ProtectionStatus.OK, ok.status); assertEquals("Short: Sundays at 03:00", ok.detail)
        val failed = ProtectionSummarizer.smart(listOf(ProtectionSamples.smart()), listOf(ProtectionSamples.alert("SMARTFailedSelftest", "Device /dev/sdb: self-test failed")))
        assertEquals(ProtectionStatus.FAILED, failed.status)
        // Failures show even without schedules (or if cron jobs couldn't be read).
        assertEquals(ProtectionStatus.FAILED, ProtectionSummarizer.smart(null, listOf(ProtectionSamples.alert("SMART", "x"))).status)
    }

    @Test fun backupStates() {
        assertEquals(ProtectionStatus.NONE, ProtectionSummarizer.backup(emptyList(), NOW).status)
        val failed = ProtectionSummarizer.backup(listOf(ProtectionSamples.backup(job = ProtectionSamples.job(JobState.FAILED, error = "Bucket not found\nmore"))), NOW)
        assertEquals(ProtectionStatus.FAILED, failed.status); assertEquals("Photos to B2: Bucket not found", failed.detail)
        assertEquals(ProtectionStatus.RUNNING, ProtectionSummarizer.backup(listOf(ProtectionSamples.backup(job = ProtectionSamples.job(JobState.RUNNING, finished = null, pct = 42.0))), NOW).status)
        assertEquals(ProtectionStatus.OVERDUE, ProtectionSummarizer.backup(listOf(ProtectionSamples.backup(job = ProtectionSamples.job(finished = NOW - 3 * D))), NOW).status)
        // Manual-only task (no schedule) is never overdue.
        assertEquals(ProtectionStatus.OK, ProtectionSummarizer.backup(listOf(ProtectionSamples.backup(schedule = null, job = ProtectionSamples.job(finished = NOW - 90 * D))), NOW).status)
        val never = ProtectionSummarizer.backup(listOf(ProtectionSamples.backup(job = null)), NOW)
        assertEquals(ProtectionStatus.OK, never.status); assertEquals("Backups set up", never.headline)
    }

    // --- Parsers (shapes from the 25.10.3 middleware) ---

    @Test fun parsesSnapshot() {
        val s = ProtectionParsers.snapshot(obj("""{"id":"tank/photos@auto-2026-09-26_13-00","name":"tank/photos@auto-2026-09-26_13-00","dataset":"tank/photos",
            "snapshot_name":"auto-2026-09-26_13-00","holds":{"truenas":1},
            "properties":{"used":{"value":"17.5M","rawvalue":"18350080"},"referenced":{"rawvalue":"412000000000"},"creation":{"rawvalue":"1790000000"}}}"""))!!
        assertEquals("auto-2026-09-26_13-00", s.name); assertEquals(1_790_000_000_000L, s.createdMillis); assertEquals(18_350_080L, s.usedBytes)
        assertTrue(s.held)
        val bare = ProtectionParsers.snapshot(obj("""{"id":"tank@x","holds":{}}"""))!!
        assertEquals("tank", bare.dataset); assertEquals("x", bare.name); assertFalse(bare.held); assertNull(bare.createdMillis)
    }

    @Test fun parsesSnapshotTask() {
        val t = ProtectionParsers.snapshotTask(obj("""{"id":3,"dataset":"tank/photos","recursive":true,"exclude":["tank/photos/tmp"],"lifetime_value":2,
            "lifetime_unit":"WEEK","naming_schema":"auto-%Y-%m-%d_%H-%M","enabled":true,"allow_empty":false,
            "schedule":{"minute":"0","hour":"*","dom":"*","month":"*","dow":"*","begin":"08:00","end":"20:00"},
            "state":{"state":"FINISHED","datetime":{"${'$'}date":1789998800000}}}"""))!!
        assertEquals(listOf("tank/photos/tmp"), t.exclude); assertEquals("08:00", t.schedule.begin); assertFalse(t.allowEmpty)
        assertEquals("FINISHED", t.state!!.state); assertEquals(1_789_998_800_000L, t.state!!.atMillis)
        val err = ProtectionParsers.taskState(Json.parseToJsonElement("""{"state":"ERROR","datetime":"2026-09-26T10:00:00Z","error":"  cannot create snapshot  "}"""))!!
        assertEquals("cannot create snapshot", err.error); assertEquals(1_790_416_800_000L, err.atMillis)
    }

    @Test fun parsesSmartCronJobs() {
        // Exactly what the drop_smart migration writes.
        val all = ProtectionParsers.smartSchedule(obj("""{"id":7,"command":"midclt call disk.smart_test SHORT '[\"*\"]'","description":"S.M.A.R.T. Test",
            "enabled":true,"user":"root","schedule":{"minute":"00","hour":"3","dom":"*","month":"*","dow":"sun"}}"""))!!
        assertEquals(SmartTestType.SHORT, all.type); assertTrue(all.allDisks); assertEquals("sun", all.schedule.dow)
        val some = ProtectionParsers.smartSchedule(obj("""{"id":8,"command":"midclt call disk.smart_test LONG '[\"{serial_lunid}5000cca0000001\", \"{serial}S5GX\"]'",
            "description":"S.M.A.R.T. Test: monthly","enabled":false,"schedule":{"minute":"0","hour":"1","dom":"1","month":"*","dow":"*"}}"""))!!
        assertEquals(listOf("{serial_lunid}5000cca0000001", "{serial}S5GX"), some.disks); assertFalse(some.enabled)
        assertNull(ProtectionParsers.smartSchedule(obj("""{"id":9,"command":"/usr/local/bin/backup.sh","schedule":{}}""")))
        assertNull(ProtectionParsers.smartSchedule(obj("""{"id":9,"command":"midclt call disk.smart_test BOGUS '[\"*\"]'"}""")))
    }

    @Test fun smartCommandQuotingIsSafe() {
        assertEquals("""midclt call disk.smart_test SHORT '["*"]'""", ProtectionApi.smartCommand(SmartTestType.SHORT, listOf("*")))
        assertEquals("""midclt call disk.smart_test LONG '["{serial_lunid}5000cca0000001","{uuid}a-b"]'""",
            ProtectionApi.smartCommand(SmartTestType.LONG, listOf("{serial_lunid}5000cca0000001", "{uuid}a-b")))
        listOf("sda'; reboot; '", "a b", "\$(id)", "x\"y").forEach { bad ->
            assertTrue(runCatching { ProtectionApi.smartCommand(SmartTestType.SHORT, listOf(bad)) }.isFailure)
        }
        assertTrue(runCatching { ProtectionApi.smartCommand(SmartTestType.SHORT, emptyList()) }.isFailure)
        // And it round-trips through the parser.
        val cmd = ProtectionApi.smartCommand(SmartTestType.LONG, listOf("{serial}ABC"))
        val parsed = ProtectionParsers.smartSchedule(obj("""{"id":1,"command":${Json.encodeToString(kotlinx.serialization.json.JsonPrimitive(cmd))}}"""))!!
        assertEquals(listOf("{serial}ABC"), parsed.disks); assertEquals(SmartTestType.LONG, parsed.type)
    }

    @Test fun parsesBackupTasks() {
        val rep = ProtectionParsers.replication(obj("""{"id":1,"name":"tank/photos - backup/photos","direction":"PUSH","transport":"SSH",
            "source_datasets":["tank/photos"],"target_dataset":"backup/photos","enabled":true,
            "schedule":{"minute":"0","hour":"1","dom":"*","month":"*","dow":"*"},
            "state":{"state":"ERROR","datetime":{"${'$'}date":1789990000000},"error":"No route to host"},
            "job":{"id":55,"state":"FAILED","error":"No route to host","time_started":{"${'$'}date":1789989000000},"time_finished":{"${'$'}date":1789990000000},"logs_excerpt":"[2026] error"}}"""))!!
        assertEquals(BackupKind.REPLICATION, rep.kind); assertEquals("tank/photos → backup/photos", rep.detail); assertTrue(rep.failed)
        assertEquals("[2026] error", rep.lastJob!!.logExcerpt)
        val cs = ProtectionParsers.cloudSync(obj("""{"id":2,"description":"Photos to B2","direction":"PUSH","path":"/mnt/tank/photos","transfer_mode":"SYNC",
            "credentials":{"id":1,"name":"b2","provider":{"type":"B2"}},"enabled":true,"locked":false,
            "job":{"state":"RUNNING","progress":{"percent":42.5,"description":"Transferred 1.2 GiB"}}}"""))!!
        assertEquals("Push from /mnt/tank/photos · Backblaze B2 · sync", cs.detail); assertTrue(cs.running); assertEquals(42.5, cs.lastJob!!.percent!!, 0.001)
        val rs = ProtectionParsers.rsync(obj("""{"id":3,"desc":"","path":"/mnt/tank/docs","remotehost":"backup.lan","mode":"MODULE","remotemodule":"docs","direction":"PUSH","enabled":false,"job":null}"""))!!
        assertEquals("Rsync 3", rs.name); assertEquals("/mnt/tank/docs → backup.lan::docs", rs.detail); assertNull(rs.lastJob); assertFalse(rs.enabled)
    }

    @Test fun snapshotTaskPayload() {
        val f = SnapshotTaskForm(dataset = "tank/photos", recursive = true, exclude = "tank/photos/tmp, tank/photos/cache", lifetimeValue = "14", lifetimeUnit = "DAY")
        assertTrue(f.valid)
        val j = ProtectionParsers.snapshotTaskJson(f.toInput())
        assertEquals("""["tank/photos/tmp","tank/photos/cache"]""", j["exclude"].toString())
        assertEquals("14", j["lifetime_value"].toString())
        assertEquals("\"00:00\"", j["schedule"]!!.jsonObject["begin"].toString())
        // Exclusions only make sense with recursion.
        assertEquals("[]", ProtectionParsers.snapshotTaskJson(f.copy(recursive = false).toInput())["exclude"].toString())
        assertFalse(f.copy(namingSchema = "auto-%Y-%m").valid)
        assertFalse(f.copy(lifetimeValue = "0").valid)
        assertFalse(f.copy(dataset = "").valid)
    }

    @Test fun smartCronPayload() {
        val j = ProtectionApi.smartCronJson(SmartTestType.SHORT, listOf("*"), CronSchedule(minute = "0", hour = "3", dow = "sun"), "", enabled = true)
        assertEquals("\"root\"", j["user"].toString())
        assertEquals("\"S.M.A.R.T. Test\"", j["description"].toString())
        assertEquals("\"S.M.A.R.T. Test: weekly\"", ProtectionApi.smartCronJson(SmartTestType.SHORT, listOf("*"), CronSchedule(), "weekly", true)["description"].toString())
    }

    // --- Snapshots screen helpers ---

    @Test fun snapshotListHelpers() {
        val all = ProtectionSamples.snapshots
        assertEquals("manual-2026-09-20_18-42", sortSnapshots(all, "", SnapshotSort.LARGEST).first().name)
        assertEquals("auto-2026-09-19_00-00", sortSnapshots(all, "", SnapshotSort.OLDEST).first().name)
        assertEquals(1, sortSnapshots(all, "MANUAL", SnapshotSort.NEWEST).size)
        assertEquals(2, newerThan(all, all[2]).size)
        assertEquals(0, newerThan(all, all[0]).size)
        assertTrue(validSnapshotName("manual-2026-09-26_13-05")); assertFalse(validSnapshotName("a b")); assertFalse(validSnapshotName("a@b"))
        assertTrue(validDatasetPath("tank/restore")); assertTrue(validDatasetPath("tank/a/b-c"))
        assertFalse(validDatasetPath("tank")); assertFalse(validDatasetPath("tank//x")); assertFalse(validDatasetPath("tank/x/"))
    }
}
