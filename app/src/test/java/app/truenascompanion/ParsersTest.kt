package app.truenascompanion

import app.truenascompanion.data.api.Parsers
import app.truenascompanion.data.model.AppState
import app.truenascompanion.data.model.DashboardLayout
import app.truenascompanion.data.model.Health
import app.truenascompanion.data.model.WidgetConfig
import app.truenascompanion.data.model.WidgetSize
import app.truenascompanion.data.model.WidgetType
import app.truenascompanion.util.UrlUtils
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ParsersTest {
    private fun obj(s: String) = Json.parseToJsonElement(s).jsonObject

    @Test
    fun realtime_25x_schema() {
        val fields = obj(
            """
            {"cpu": {"cpu": {"usage": 12.5, "temp": null}, "cpu0": {"usage": 10.0, "temp": 41.0}, "cpu1": {"usage": 15.0, "temp": 44.0}},
             "disks": {"busy": 0, "read_bytes": 0, "write_bytes": 0, "read_ops": 0, "write_ops": 0},
             "interfaces": {"eno1": {"link_state": "LINK_STATE_UP", "speed": 1000, "received_bytes_rate": 1000.0, "sent_bytes_rate": 500.0},
                            "eno2": {"link_state": "LINK_STATE_DOWN", "speed": 0, "received_bytes_rate": 99.0, "sent_bytes_rate": 99.0}},
             "memory": {"arc_size": 100, "arc_free_memory": 1, "arc_available_memory": 1, "physical_memory_total": 1000, "physical_memory_available": 250},
             "zfs": {}}
            """
        )
        val s = Parsers.realtime(fields)
        assertEquals(12.5, s.cpuPercent!!, 0.001)
        assertEquals(44.0, s.cpuTempC!!, 0.001)
        assertEquals(750L, s.memoryUsed)
        assertEquals(1000.0, s.netRxBytesPerSec!!, 0.001)
        assertEquals(500.0, s.netTxBytesPerSec!!, 0.001)
    }

    @Test
    fun pool_collects_disks_and_health() {
        val p = Parsers.pool(obj(
            """{"id": 1, "name": "tank", "status": "ONLINE", "healthy": true, "warning": false, "size": 100, "allocated": 25, "free": 75,
               "topology": {"data": [{"type": "MIRROR", "disk": null, "children": [{"disk": "sda"}, {"disk": "sdb"}]}], "cache": []},
               "scan": {"function": "SCRUB", "state": "FINISHED", "percentage": 100.0, "errors": 0}}"""
        ))
        assertEquals(listOf("sda", "sdb"), p.diskNames)
        assertEquals(Health.HEALTHY, p.health)
        assertEquals(0.25f, p.usedFraction, 0.001f)
    }

    @Test
    fun app_and_alert_parsing() {
        val a = Parsers.app(obj("""{"name": "plex", "id": "plex", "state": "RUNNING", "upgrade_available": true, "human_version": "1.2_3", "portals": {"Web UI": "http://nas:32400/web"}}"""))
        assertEquals(AppState.RUNNING, a.state)
        assertTrue(a.upgradeAvailable)
        assertEquals("http://nas:32400/web", a.portalUrl)

        val al = Parsers.alert(obj("""{"uuid": "u1", "level": "CRITICAL", "formatted": "Pool <b>tank</b> is DEGRADED", "dismissed": false, "datetime": {"${'$'}date": 1700000000000}}"""))
        assertEquals("Pool tank is DEGRADED", al.text)
        assertEquals(Health.CRITICAL, al.health)
        assertEquals(1700000000000L, al.datetimeMillis)
    }

    @Test
    fun disk_temperatures_both_shapes() {
        assertEquals(mapOf("sda" to 34.0, "sdb" to 40.0), Parsers.diskTemperatures(obj("""{"sda": 34, "sdb": {"temperature": 40}, "sdc": null}""")))
    }

    @Test
    fun url_normalization() {
        assertEquals("https://nas.local", UrlUtils.normalize("nas.local"))
        assertEquals("http://192.168.1.5:8080", UrlUtils.normalize(" http://192.168.1.5:8080/ui/dashboard "))
        assertNull(UrlUtils.normalize("ftp://x"))
        assertEquals("wss://nas.local/api/current", UrlUtils.webSocketUrl("https://nas.local"))
        assertEquals("ws://10.0.0.2:81/api/current", UrlUtils.webSocketUrl("http://10.0.0.2:81"))
    }

    @Test
    fun layout_normalize_and_move() {
        val partial = DashboardLayout(listOf(WidgetConfig(WidgetType.ALERTS, visible = false, size = WidgetSize.FULL), WidgetConfig(WidgetType.ALERTS)))
        val n = partial.normalized()
        assertEquals(WidgetType.entries.size, n.widgets.size)
        assertEquals(WidgetType.ALERTS, n.widgets.first().type)
        val moved = DashboardLayout.DEFAULT.move(0, 2)
        assertEquals(WidgetType.SYSTEM, moved.widgets[2].type)
        assertEquals(WidgetType.CPU, moved.widgets[0].type)
    }
}
