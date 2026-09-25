package app.truenascompanion

import app.truenascompanion.data.api.Parsers
import app.truenascompanion.data.model.AlertItem
import app.truenascompanion.data.store.NotificationPrefs
import app.truenascompanion.notify.AlertDiff
import app.truenascompanion.notify.AlertFilter
import app.truenascompanion.notify.AlertLevel
import app.truenascompanion.notify.QuietHours
import app.truenascompanion.notify.SeenAlert
import app.truenascompanion.notify.SeverityGroup
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertDiffTest {
    private fun alert(uuid: String, level: String, dismissed: Boolean = false, klass: String = "Test") =
        AlertItem(uuid, level, "text $uuid", klass, 0L, dismissed, false)

    private fun seen(vararg a: AlertItem) = a.map { SeenAlert(it.uuid, it.level, "t", it.dismissed) }
    private val noon = 12 * 60
    private val title: (AlertItem) -> String = { it.uuid }

    @Test
    fun firstCheckIsSilentBaseline() {
        val r = AlertDiff.compute(null, listOf(alert("a", "CRITICAL")), AlertFilter(), noon, title)
        assertTrue(r.isBaseline)
        assertTrue(r.toNotify.isEmpty())
        assertEquals(listOf("a"), r.seen.map { it.uuid })
    }

    @Test
    fun notifiesOnlyNewAlertsAtOrAboveMinimum() {
        val old = alert("old", "CRITICAL")
        val current = listOf(old, alert("w", "WARNING"), alert("i", "INFO"), alert("n", "NOTICE"), alert("e", "ERROR"))
        val r = AlertDiff.compute(seen(old), current, AlertFilter(minLevel = AlertLevel.WARNING), noon, title)
        assertEquals(listOf("e", "w"), r.toNotify.map { it.uuid }) // most severe first
        assertFalse(r.isBaseline)
    }

    @Test
    fun infoMinimumIncludesEverything() {
        val r = AlertDiff.compute(emptyList(), listOf(alert("i", "INFO"), alert("n", "NOTICE")), AlertFilter(minLevel = AlertLevel.INFO), noon, title)
        assertEquals(2, r.toNotify.size)
    }

    @Test
    fun dismissedNewAlertsAreNotNotified() {
        val r = AlertDiff.compute(emptyList(), listOf(alert("d", "CRITICAL", dismissed = true)), AlertFilter(), noon, title)
        assertTrue(r.toNotify.isEmpty())
        assertEquals(1, r.seen.size) // still remembered, so restoring it later doesn't re-notify
    }

    @Test
    fun alreadySeenAlertsAreNotRepeated() {
        val a = alert("a", "WARNING")
        val r1 = AlertDiff.compute(emptyList(), listOf(a), AlertFilter(), noon, title)
        assertEquals(1, r1.toNotify.size)
        val r2 = AlertDiff.compute(r1.seen, listOf(a), AlertFilter(), noon, title)
        assertTrue(r2.toNotify.isEmpty())
    }

    @Test
    fun clearedOnlyWhenEnabledAndAboveMinimum() {
        val prev = seen(alert("w", "WARNING"), alert("i", "INFO"), alert("dis", "CRITICAL", dismissed = true))
        val off = AlertDiff.compute(prev, emptyList(), AlertFilter(notifyOnClear = false), noon, title)
        assertTrue(off.cleared.isEmpty())
        assertEquals(setOf("w", "i", "dis"), off.withdrawn)
        val on = AlertDiff.compute(prev, emptyList(), AlertFilter(notifyOnClear = true), noon, title)
        assertEquals(listOf("w"), on.cleared.map { it.uuid })
    }

    @Test
    fun dismissedOnNasWithdrawsNotification() {
        val prev = seen(alert("a", "WARNING"))
        val r = AlertDiff.compute(prev, listOf(alert("a", "WARNING", dismissed = true)), AlertFilter(), noon, title)
        assertEquals(setOf("a"), r.withdrawn)
        assertTrue(r.toNotify.isEmpty())
    }

    @Test
    fun quietHoursLetOnlyCriticalThrough() {
        val quiet = AlertFilter(minLevel = AlertLevel.INFO, quietHours = QuietHours(true, 22 * 60, 7 * 60))
        val current = listOf(alert("w", "WARNING"), alert("e", "ERROR"), alert("c", "CRITICAL"), alert("x", "EMERGENCY"))
        val night = AlertDiff.compute(emptyList(), current, quiet, 23 * 60 + 30, title)
        assertEquals(setOf("c", "x"), night.toNotify.map { it.uuid }.toSet())
        assertEquals(2, night.suppressed)
        val day = AlertDiff.compute(emptyList(), current, quiet, noon, title)
        assertEquals(4, day.toNotify.size)
    }

    @Test
    fun quietHoursWindowWrapsMidnight() {
        val q = QuietHours(true, 22 * 60, 7 * 60)
        assertTrue(q.contains(22 * 60))
        assertTrue(q.contains(3 * 60))
        assertFalse(q.contains(7 * 60))
        assertFalse(q.contains(noon))
        val sameDay = QuietHours(true, 13 * 60, 15 * 60)
        assertTrue(sameDay.contains(14 * 60))
        assertFalse(sameDay.contains(16 * 60))
        assertFalse(QuietHours(false).contains(23 * 60))
        assertFalse(QuietHours(true, 60, 60).contains(60))
    }

    @Test
    fun levelsParseAndGroup() {
        assertEquals(AlertLevel.CRITICAL, AlertLevel.parse("critical"))
        assertEquals(AlertLevel.INFO, AlertLevel.parse("bogus"))
        assertEquals(AlertLevel.INFO, AlertLevel.parse(null))
        assertEquals(SeverityGroup.CRITICAL, AlertLevel.ERROR.group)
        assertEquals(SeverityGroup.CRITICAL, AlertLevel.EMERGENCY.group)
        assertEquals(SeverityGroup.WARNING, AlertLevel.WARNING.group)
        assertEquals(SeverityGroup.INFO, AlertLevel.NOTICE.group)
        assertEquals(AlertLevel.WARNING, NotificationPrefs().minLevel) // default
        assertEquals(15, NotificationPrefs().intervalMinutes)
        assertFalse(NotificationPrefs().instant)
        assertFalse(NotificationPrefs().notifyOnClear)
    }

    @Test
    fun titles() {
        val a = alert("a", "WARNING", klass = "ZpoolCapacityWarning")
        assertEquals("Pool Space Usage Is Above 80%", AlertDiff.title(a, mapOf("ZpoolCapacityWarning" to "Pool Space Usage Is Above 80%")))
        assertEquals("Zpool capacity warning", AlertDiff.title(a, emptyMap()))
        assertEquals("SMART error", AlertDiff.humanize("SMARTError"))
        assertEquals("Warning alert", AlertDiff.title(AlertItem("b", "WARNING", "x", null, null, false, false), emptyMap()))
    }

    /** `alert.list` item as serialized by middlewared 25.10 (AlertSerializer.serialize). */
    @Test
    fun parsesMiddlewareAlertAndDiffs() {
        val o = Json.parseToJsonElement(
            """{"uuid":"a1b2","source":"","klass":"PoolStatus","args":{"volume":"tank","state":"DEGRADED"},"node":"Controller A",
               "key":"[\"tank\"]","datetime":{"${'$'}date":1790000000000},"last_occurrence":{"${'$'}date":1790000000000},
               "dismissed":false,"mail":null,"text":"Pool %(volume)s state is %(state)s","id":"a1b2","level":"CRITICAL",
               "formatted":"Pool tank state is DEGRADED: <br>One or more devices are faulted.","one_shot":false}""",
        ).jsonObject
        val a = Parsers.alert(o)
        assertEquals("a1b2", a.uuid)
        assertEquals(AlertLevel.CRITICAL, AlertLevel.parse(a.level))
        assertFalse(a.text.contains("<br>"))
        val r = AlertDiff.compute(emptyList(), listOf(a), AlertFilter(), noon) { AlertDiff.title(it, mapOf("PoolStatus" to "Pool Status Is Not Healthy")) }
        assertEquals(listOf("a1b2"), r.toNotify.map { it.uuid })
        assertEquals("Pool Status Is Not Healthy", r.seen.single().title)
    }

    @Test
    fun prefsRoundTripThroughJson() {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val p = NotificationPrefs(enabledServers = setOf("s1"), minLevel = AlertLevel.ERROR, quietEnabled = true)
        assertEquals(p, json.decodeFromString<NotificationPrefs>(json.encodeToString(p)))
        assertEquals(NotificationPrefs(), json.decodeFromString<NotificationPrefs>("{}"))
    }
}
