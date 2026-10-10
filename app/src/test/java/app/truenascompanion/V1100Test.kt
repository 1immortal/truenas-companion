package app.truenascompanion

import app.truenascompanion.data.api.Parsers
import app.truenascompanion.data.model.AppInfo
import app.truenascompanion.data.model.AppState
import app.truenascompanion.data.model.BackupKind
import app.truenascompanion.data.model.BackupTask
import app.truenascompanion.data.model.DashboardDensity
import app.truenascompanion.data.model.DashboardLayout
import app.truenascompanion.data.model.Health
import app.truenascompanion.data.model.JobState
import app.truenascompanion.data.model.LastJob
import app.truenascompanion.data.model.NasCertificate
import app.truenascompanion.data.model.Pool
import app.truenascompanion.data.model.ReportData
import app.truenascompanion.data.model.ReportRange
import app.truenascompanion.data.model.ReportSeries
import app.truenascompanion.data.model.ServiceInfo
import app.truenascompanion.data.model.WidgetConfig
import app.truenascompanion.data.model.WidgetType
import app.truenascompanion.data.runway.Runway
import app.truenascompanion.data.runway.RunwayForecast
import app.truenascompanion.data.runway.RunwaySamples
import app.truenascompanion.data.runway.UsageSample
import app.truenascompanion.notify.AlertFilter
import app.truenascompanion.notify.AlertLevel
import app.truenascompanion.notify.DeepLink
import app.truenascompanion.notify.ProgressKind
import app.truenascompanion.notify.ProgressTracker
import app.truenascompanion.notify.QuietHours
import app.truenascompanion.notify.RunningJob
import app.truenascompanion.notify.rules.AlertRule
import app.truenascompanion.notify.rules.BackupTarget
import app.truenascompanion.notify.rules.Finding
import app.truenascompanion.notify.rules.RuleData
import app.truenascompanion.notify.rules.RuleEngine
import app.truenascompanion.notify.rules.RuleFindings
import app.truenascompanion.notify.rules.RuleKind
import app.truenascompanion.notify.rules.RuleSeverity
import app.truenascompanion.notify.rules.RuleState
import app.truenascompanion.widget.WidgetModel
import app.truenascompanion.widget.WidgetPool
import app.truenascompanion.widget.WidgetSnapshot
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.10.0: alert rules, quiet hours days, progress, storage runway, ARC hit ratio, dashboard layout and widgets. */
class V1100Test {
    private val now = ProtectionSamples.NOW
    private val min = 60_000L

    private fun pool(name: String, usedPct: Int, fn: String? = "SCRUB", state: String? = "FINISHED", end: Long? = now - 3 * ProtectionSamples.D, pct: Double? = 100.0) =
        Pool(1, name, "ONLINE", true, false, null, 1000L shl 30, (10L * usedPct) shl 30, (1000L - 10L * usedPct) shl 30, null,
            fn, state, pct, 0, listOf("sda"), end?.minus(ProtectionSamples.H), end)

    private class FakeData(
        val pools: List<Pool>? = null,
        val apps: List<AppInfo>? = null,
        val services: List<ServiceInfo>? = null,
        val backups: List<BackupTask>? = null,
        val certs: List<NasCertificate>? = null,
        val cpu: Double? = null,
        val ram: Double? = null,
        val temps: Map<String, Double>? = null,
    ) : RuleData {
        override suspend fun pools() = pools
        override suspend fun apps() = apps
        override suspend fun services() = services
        override suspend fun backups() = backups
        override suspend fun certificates() = certs
        override suspend fun cpuAverage(minutes: Int) = cpu
        override suspend fun ramAverage(minutes: Int) = ram
        override suspend fun diskTempLowest(minutes: Int) = temps
    }

    private fun eval(rule: AlertRule, data: RuleData?, unreachableSince: Long? = null) = runBlocking { RuleFindings.evaluate(rule, data, unreachableSince, now) }

    // ---- rule engine ----

    private val poolRule = AlertRule(id = "r1", kind = RuleKind.POOL_USAGE, threshold = 80.0)

    @Test fun notifiesOnTransitionsOnly() {
        val hit = mapOf("r1" to listOf(Finding("tank", "tank is 85% full")))
        val s1 = RuleEngine.step(listOf(poolRule), hit, RuleState(), now)
        assertEquals(listOf("r1|tank"), s1.fire.map { it.key })
        val shown = RuleEngine.markNotified(s1.state, s1.fire.map { it.key })
        // Still true: no second notification.
        val s2 = RuleEngine.step(listOf(poolRule), hit, shown, now + 15 * min)
        assertTrue(s2.fire.isEmpty()); assertTrue(s2.recovered.isEmpty())
        // Ended: one "recovered".
        val s3 = RuleEngine.step(listOf(poolRule), mapOf("r1" to emptyList()), s2.state, now + 30 * min)
        assertEquals(listOf("r1|tank"), s3.recovered.map { it.key })
        assertTrue(s3.state.firing.isEmpty())
        val s4 = RuleEngine.step(listOf(poolRule), mapOf("r1" to emptyList()), s3.state, now + 45 * min)
        assertTrue(s4.recovered.isEmpty())
    }

    @Test fun missingDataKeepsStateAndQuietHoursDeferTheNotification() {
        val hit = mapOf("r1" to listOf(Finding("tank", "x")))
        val s1 = RuleEngine.step(listOf(poolRule), hit, RuleState(), now)
        // Not marked notified (quiet hours): it fires again next time, without a recovered in between.
        val s2 = RuleEngine.step(listOf(poolRule), mapOf("r1" to null), s1.state, now + min)
        assertEquals(listOf("r1|tank"), s2.fire.map { it.key })
        assertEquals(now, s2.state.firing.getValue("r1|tank").since)
        // Never shown: ending it gives no "recovered".
        val s3 = RuleEngine.step(listOf(poolRule), mapOf("r1" to emptyList()), s2.state, now + 2 * min)
        assertTrue(s3.recovered.isEmpty())
    }

    @Test fun cooldownSuppressesFlapping() {
        val rule = poolRule.copy(cooldownMinutes = 60)
        val hit = mapOf("r1" to listOf(Finding("tank", "x")))
        var st = RuleEngine.markNotified(RuleEngine.step(listOf(rule), hit, RuleState(), now).state, listOf("r1|tank"))
        st = RuleEngine.step(listOf(rule), mapOf("r1" to emptyList()), st, now + 10 * min).state
        assertTrue(RuleEngine.step(listOf(rule), hit, st, now + 20 * min).fire.isEmpty())
        assertEquals(1, RuleEngine.step(listOf(rule), hit, st, now + 75 * min).fire.size)
    }

    @Test fun disabledOrDeletedRulesAreDroppedWithoutRecovered() {
        val hit = mapOf("r1" to listOf(Finding("tank", "x")))
        val st = RuleEngine.markNotified(RuleEngine.step(listOf(poolRule), hit, RuleState(), now).state, listOf("r1|tank"))
        val off = RuleEngine.step(listOf(poolRule.copy(enabled = false)), hit, st, now + min)
        assertEquals(setOf("r1|tank"), off.dropped)
        assertTrue(off.recovered.isEmpty() && off.fire.isEmpty() && off.state.firing.isEmpty())
        assertEquals(setOf("r1|tank"), RuleEngine.step(emptyList(), emptyMap(), st, now + min).dropped)
    }

    @Test fun stateSurvivesSerialization() {
        val st = RuleEngine.step(listOf(poolRule), mapOf("r1" to listOf(Finding("tank", "x"))), RuleState(unreachableSince = now), now).state
        val json = Json.encodeToString(RuleState.serializer(), st)
        assertEquals(st, Json.decodeFromString(RuleState.serializer(), json))
        val rules = AlertRule.suggested()
        assertEquals(rules, Json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(AlertRule.serializer()), Json.encodeToString(kotlinx.serialization.builtins.ListSerializer(AlertRule.serializer()), rules)))
    }

    @Test fun suggestedPackIsValidAndValidationCatchesBadInput() {
        val pack = AlertRule.suggested()
        assertEquals(6, pack.size)
        assertTrue(pack.all { AlertRule.problem(it) == null && it.enabled })
        assertEquals(pack.size, pack.map { it.id }.toSet().size)
        assertNotNull(AlertRule.problem(AlertRule(kind = RuleKind.POOL_USAGE, threshold = 120.0)))
        assertEquals("Pick an app", AlertRule.problem(AlertRule(kind = RuleKind.APP_NOT_RUNNING)))
        assertNotNull(AlertRule.problem(AlertRule(kind = RuleKind.CPU_HIGH, minutes = 0)))
        assertNotNull(AlertRule.problem(AlertRule(kind = RuleKind.SCRUB_AGE, cooldownMinutes = 8 * 24 * 60)))
        assertEquals(AlertLevel.CRITICAL, RuleSeverity.CRITICAL.level)
    }

    // ---- findings ----

    @Test fun poolUsageAndScrubAge() {
        val data = FakeData(pools = listOf(pool("tank", 86), pool("fast", 40, end = now - 50 * ProtectionSamples.D), pool("new", 1, fn = null, state = null, end = null)))
        assertEquals(listOf("tank"), eval(poolRule, data)!!.map { it.subject })
        val scrub = eval(AlertRule(kind = RuleKind.SCRUB_AGE, threshold = 35.0), data)!!
        assertEquals(listOf("fast", "new"), scrub.map { it.subject })
        assertTrue(scrub[0].text.contains("50 days"))
        // A running scrub is never "overdue".
        assertTrue(eval(AlertRule(kind = RuleKind.SCRUB_AGE, threshold = 1.0), FakeData(pools = listOf(pool("tank", 10, state = "SCANNING"))))!!.isEmpty())
        // No connection: no answer (state is kept).
        assertNull(eval(poolRule, null))
    }

    @Test fun appsServicesCpuRamAndTemps() {
        val apps = listOf(
            AppInfo("nextcloud", AppState.CRASHED, "1", false, false, null, null, 1),
            AppInfo("jellyfin", AppState.RUNNING, "1", false, false, null, null, 1),
        )
        assertEquals("App nextcloud is crashed.", eval(AlertRule(kind = RuleKind.APP_NOT_RUNNING, target = "nextcloud"), FakeData(apps = apps))!!.single().text)
        assertTrue(eval(AlertRule(kind = RuleKind.APP_NOT_RUNNING, target = "jellyfin"), FakeData(apps = apps))!!.isEmpty())
        val services = listOf(ServiceInfo(1, "cifs", false, true), ServiceInfo(2, "nfs", false, true, unknown = true))
        assertEquals("Service SMB is stopped.", eval(AlertRule(kind = RuleKind.SERVICE_STOPPED, target = "cifs"), FakeData(services = services))!!.single().text)
        assertTrue(eval(AlertRule(kind = RuleKind.SERVICE_STOPPED, target = "nfs"), FakeData(services = services))!!.isEmpty())
        assertEquals(1, eval(AlertRule(kind = RuleKind.CPU_HIGH, threshold = 80.0, minutes = 10), FakeData(cpu = 93.0))!!.size)
        assertTrue(eval(AlertRule(kind = RuleKind.RAM_HIGH, threshold = 80.0, minutes = 10), FakeData(ram = 60.0))!!.isEmpty())
        val t = eval(AlertRule(kind = RuleKind.DISK_TEMP, threshold = 50.0, minutes = 10), FakeData(temps = mapOf("sda" to 52.0, "sdb" to 41.0)))!!
        assertEquals(listOf("sda"), t.map { it.subject })
    }

    @Test fun backupsFailedAndStale() {
        fun task(kind: BackupKind, id: Int, state: JobState, finished: Long?) = BackupTask(kind, id, "task$id", "", true, null,
            LastJob(state, finished?.minus(ProtectionSamples.H), finished, if (state == JobState.FAILED) "connection refused\nmore" else null, null, null, null), null)
        val backups = listOf(
            task(BackupKind.REPLICATION, 1, JobState.FAILED, now - ProtectionSamples.H),
            task(BackupKind.CLOUD_SYNC, 2, JobState.SUCCESS, now - 72 * ProtectionSamples.H),
            task(BackupKind.RSYNC, 3, JobState.FAILED, now),
        )
        val failed = eval(AlertRule(kind = RuleKind.BACKUP_FAILED, target = BackupTarget.ANY), FakeData(backups = backups))!!
        assertEquals(listOf("REPLICATION:1"), failed.map { it.subject })
        assertEquals("Replication \"task1\" failed: connection refused", failed.single().text)
        val stale = eval(AlertRule(kind = RuleKind.BACKUP_STALE, threshold = 48.0, target = BackupTarget.CLOUD_SYNC), FakeData(backups = backups))!!
        assertEquals(listOf("CLOUD_SYNC:2"), stale.map { it.subject })
        assertTrue(stale.single().text.contains("3 days ago"))
    }

    @Test fun unreachableCountsFromTheFirstFailedCheck() {
        val rule = AlertRule(kind = RuleKind.UNREACHABLE, minutes = 30)
        assertTrue(eval(rule, null, unreachableSince = null)!!.isEmpty())
        assertTrue(eval(rule, null, unreachableSince = now - 20 * min)!!.isEmpty())
        assertEquals(1, eval(rule, null, unreachableSince = now - 31 * min)!!.size)
        assertFalse(RuleFindings.needsConnection(listOf(rule)))
        assertTrue(RuleFindings.needsConnection(listOf(rule, poolRule)))
    }

    @Test fun ramExcludesArcAndCpuUsesTheTotalColumn() {
        // 64 GiB, 8 GiB available, 40 GiB ARC: services use 16 GiB = 25%.
        assertEquals(25.0, RuleFindings.ramPercent(64.0, 8.0, 40.0)!!, 0.01)
        assertNull(RuleFindings.ramPercent(0.0, 0.0, 0.0))
        val d = ReportData("cpu", null, 0, 1, longArrayOf(0, 1), listOf(ReportSeries("cpu0", floatArrayOf(90f, 90f)), ReportSeries("cpu", floatArrayOf(40f, 60f))))
        assertEquals(50.0, RuleFindings.cpuMean(d)!!, 0.01)
        assertEquals("sda", RuleFindings.diskName("sda | Type: HDD | Serial: X"))
    }

    // ---- quiet hours ----

    @Test fun quietHoursDaysAndWrapAround() {
        // 22:00–07:00 on Friday and Saturday nights (ISO 5, 6).
        val q = QuietHours(enabled = true, startMinute = 22 * 60, endMinute = 7 * 60, days = setOf(5, 6))
        assertTrue(q.contains(23 * 60, 5))
        assertTrue(q.contains(3 * 60, 6))   // Saturday early morning belongs to Friday night
        assertTrue(q.contains(3 * 60, 7))   // Sunday early morning belongs to Saturday night
        assertFalse(q.contains(3 * 60, 5))  // Friday early morning belongs to Thursday night
        assertFalse(q.contains(23 * 60, 7))
        assertTrue(q.contains(3 * 60, 1).not())
        // Same-day window.
        val day = QuietHours(enabled = true, startMinute = 9 * 60, endMinute = 17 * 60, days = setOf(1))
        assertTrue(day.contains(10 * 60, 1)); assertFalse(day.contains(10 * 60, 2))
        assertFalse(QuietHours(enabled = true, days = emptySet()).contains(23 * 60, 1))
        // Day unknown: days are ignored.
        assertTrue(q.contains(23 * 60, 0))
    }

    @Test fun criticalBreaksThroughIsOptional() {
        val q = QuietHours(enabled = true, startMinute = 22 * 60, endMinute = 7 * 60)
        assertTrue(AlertFilter(quietHours = q).allows(AlertLevel.CRITICAL, 23 * 60, 3))
        assertFalse(AlertFilter(quietHours = q).allows(AlertLevel.WARNING, 23 * 60, 3))
        val strict = AlertFilter(quietHours = q.copy(criticalBreaksThrough = false))
        assertFalse(strict.allows(AlertLevel.CRITICAL, 23 * 60, 3))
        assertTrue(strict.allows(AlertLevel.CRITICAL, 12 * 60, 3))
    }

    // ---- progress ----

    @Test fun progressFromPoolsAndJobs() {
        val pools = listOf(pool("tank", 50, state = "SCANNING", pct = 41.7), pool("fast", 10, fn = "RESILVER", state = "SCANNING", pct = 3.0), pool("idle", 1))
        val jobs = listOf(
            RunningJob(7, "replication.run", "Replication task tank/data → backup", 55, "Sending tank/data@auto-2026-10-09", "3"),
            RunningJob(8, "update.download", null, 12, "Downloading", null),
            RunningJob(9, "pool.dataset.delete", null, 1, null, null),
        )
        val items = ProgressTracker.items(pools, jobs)
        assertEquals(listOf("scrub:tank", "resilver:fast", "job:7", "job:8"), items.map { it.key })
        assertEquals(41, items[0].percent)
        assertEquals(DeepLink.DEST_POOL, items[0].destination); assertEquals("tank", items[0].arg)
        assertEquals(ProgressKind.UPDATE, items[3].kind)
        assertEquals("Downloading TrueNAS update", items[3].title)
        assertEquals(DeepLink.DEST_UPDATE, items[3].destination)
    }

    @Test fun parsesCoreGetJobsEntries() {
        val o = Json.parseToJsonElement("""{"id":42,"method":"cloudsync.sync","arguments":[3],"description":null,"state":"RUNNING",
            "progress":{"percent":37.5,"description":"Transferred 1.2 GiB","extra":null}}""").jsonObject
        val j = ProgressTracker.parseJob(o)!!
        assertEquals(RunningJob(42, "cloudsync.sync", null, 37, "Transferred 1.2 GiB", "3"), j)
        assertNull(ProgressTracker.parseJob(Json.parseToJsonElement("""{"method":"x"}""").jsonObject))
        assertTrue("update.run" in ProgressTracker.JOB_METHODS && "replication.run_onetime" in ProgressTracker.JOB_METHODS)
    }

    // ---- runway ----

    private fun series(days: Int, startUsed: Long, perDay: Long, size: Long = 10_000L shl 30, from: Long = 20_000) =
        (0 until days).map { UsageSample(from + it, startUsed + it * perDay, size) }

    @Test fun runwayIsHonestWithLittleData() {
        assertEquals(RunwayForecast.Collecting(0), Runway.forecast(emptyList()))
        assertEquals(RunwayForecast.Collecting(5), Runway.forecast(series(5, 1L shl 40, 10L shl 30)))
        assertEquals("Collecting data: 5 of 7 days so far", Runway.text(RunwayForecast.Collecting(5)))
    }

    @Test fun runwayForecastsMonthsUntilFull() {
        // 5 TiB used of ~9.8 TiB, +20 GiB/day: (10000 - 5120) / 20 = 244 days ≈ 8 months.
        val f = Runway.forecast(series(30, 5120L shl 30, 20L shl 30)) as RunwayForecast.Full
        assertEquals(244.0 - 29, f.daysLeft.toDouble(), 2.0)
        assertEquals("About 7 months until full", Runway.text(f))
        // One outlier (a big temporary file) barely moves the robust trend.
        val noisy = series(30, 5120L shl 30, 20L shl 30).toMutableList().also { it[10] = it[10].copy(used = it[10].used + (2000L shl 30)) }
        assertEquals(f.daysLeft.toDouble(), (Runway.forecast(noisy) as RunwayForecast.Full).daysLeft.toDouble(), 3.0)
        assertEquals(RunwayForecast.NotGrowing, Runway.forecast(series(30, 5120L shl 30, -(1L shl 30))))
        assertEquals(RunwayForecast.AlreadyFull, Runway.forecast(series(10, 10_000L shl 30, 0)))
        assertEquals("More than 10 years until full", Runway.text(RunwayForecast.Full(5000, 1.0)))
        assertEquals("About 12 days until full", Runway.text(RunwayForecast.Full(12, 1.0)))
    }

    @Test fun runwaySamplesOnePerDayAndPruned() {
        val today = 20_800L
        var s = RunwaySamples()
        s = s.record(listOf(pool("tank", 40)), today)
        s = s.record(listOf(pool("tank", 41)), today) // same day: replaced
        assertEquals(1, s.pools.getValue("tank").size)
        assertEquals((410L) shl 30, s.pools.getValue("tank").single().used)
        val old = RunwaySamples(mapOf("tank" to listOf(UsageSample(today - 800, 1, 2), UsageSample(today - 10, 1, 2)), "gone" to listOf(UsageSample(today - 731, 1, 2))))
        val pruned = old.record(listOf(pool("tank", 40)), today)
        assertEquals(listOf(today - 10, today), pruned.pools.getValue("tank").map { it.day })
        assertFalse("gone" in pruned.pools)
        assertEquals(today, pruned.lastDay)
    }

    // ---- ARC, dashboard, reports ----

    @Test fun arcHitRatioFromRealtime() {
        val zfs = Json.parseToJsonElement("""{"demand_accesses_per_second":200,"demand_data_hits_per_second":150,"demand_metadata_hits_per_second":40}""").jsonObject
        assertEquals(95.0, Parsers.arcHitPercent(zfs)!!, 0.01)
        assertNull(Parsers.arcHitPercent(Json.parseToJsonElement("""{"demand_accesses_per_second":0}""").jsonObject))
        assertNull(Parsers.arcHitPercent(null))
    }

    @Test fun dashboardMoveByDensityAndNewCards() {
        val layout = DashboardLayout(listOf(WidgetConfig(WidgetType.SYSTEM), WidgetConfig(WidgetType.CPU)), DashboardDensity.COMPACT).normalized()
        assertEquals(DashboardDensity.COMPACT, layout.density)
        assertTrue(WidgetType.RUNWAY in layout.widgets.map { it.type } && WidgetType.ARC in layout.widgets.map { it.type })
        val moved = layout.moveBy(WidgetType.CPU, -1)
        assertEquals(listOf(WidgetType.CPU, WidgetType.SYSTEM), moved.widgets.take(2).map { it.type })
        assertEquals(moved, moved.moveBy(WidgetType.CPU, -1)) // already first
        val last = layout.widgets.last().type
        assertEquals(layout, layout.moveBy(last, 1))
        assertTrue(DashboardLayout(listOf(WidgetConfig(WidgetType.ARC))).needsLiveStats)
    }

    @Test fun reportsOfferAYear() {
        assertTrue(ReportRange.YEAR in ReportRange.entries)
        assertEquals(ReportRange.entries.last(), ReportRange.YEAR)
    }

    // ---- widgets ----

    @Test fun widgetModel() {
        val ok = WidgetSnapshot(serverName = "Home NAS", poolHealth = Health.HEALTHY, poolLabel = "2 healthy", alertCount = 0,
            pools = listOf(WidgetPool("tank", 82, Health.HEALTHY), WidgetPool("fast", 41, Health.HEALTHY)), appsRunning = 7, appsTotal = 8)
        assertEquals(Health.HEALTHY, WidgetModel.tone(ok))
        assertEquals(Health.WARNING, WidgetModel.tone(ok.copy(alertCount = 2)))
        assertEquals(Health.CRITICAL, WidgetModel.tone(ok.copy(error = "Can't reach the NAS right now")))
        assertEquals(Health.UNKNOWN, WidgetModel.tone(WidgetSnapshot()))
        assertEquals("tank", WidgetModel.fullest(ok)!!.name)
        assertEquals(Health.WARNING, WidgetModel.usageTone(82))
        assertEquals(Health.CRITICAL, WidgetModel.usageTone(95))
        assertEquals("7 of 8 apps running", WidgetModel.apps(ok))
        assertNull(WidgetModel.apps(ok.copy(appsTotal = null)))
        assertEquals("Home NAS: pools 2 healthy, no open alerts", WidgetModel.dotDescription(ok))
    }
}
