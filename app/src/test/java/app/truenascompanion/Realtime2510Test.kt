package app.truenascompanion

import app.truenascompanion.data.api.Parsers
import app.truenascompanion.util.Format
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Payload shaped exactly like middlewared 25.10 `reporting.realtime` (plugins/reporting/realtime_reporting). */
class Realtime2510Test {
    private val gib = 1024L * 1024 * 1024
    private val fields = Json.parseToJsonElement(
        """{
          "zfs": {"demand_data_hit_percentage": 99},
          "memory": {"arc_size": ${100 * gib}, "arc_free_memory": ${2 * gib}, "arc_available_memory": ${3 * gib},
                     "physical_memory_total": ${125 * gib}, "physical_memory_available": ${9 * gib}},
          "cpu": {"cpu": {"usage": 0, "temp": 46.5},
                  "cpu0": {"usage": 3, "temp": 46}, "cpu1": {"usage": 0, "temp": 46}, "cpu2": {"usage": 1, "temp": null}, "cpu3": {"usage": 0, "temp": null}},
          "disks": {"read_ops": 0, "read_bytes": 0, "write_ops": 0, "write_bytes": 0, "busy": 0},
          "interfaces": {"enp3s0": {"link_state": "LINK_STATE_UP", "speed": 1000, "received_bytes_rate": 7200, "sent_bytes_rate": 1100},
                         "eno2": {"link_state": "LINK_STATE_DOWN", "speed": 0, "received_bytes": 0, "sent_bytes": 0, "received_bytes_rate": 0, "sent_bytes_rate": 0}},
          "pools": {}
        }""",
    ).jsonObject

    @Test
    fun cpuUsesPerThreadAverageWhenIntegerAggregateIsZero() {
        val s = Parsers.realtime(fields)
        assertEquals(1.0, s.cpuPercent!!, 1e-9) // (3+0+1+0)/4
        assertEquals(listOf(3.0, 0.0, 1.0, 0.0), s.cpuCores)
        assertEquals(46.5, s.cpuTempC!!, 1e-9)
    }

    @Test
    fun memorySplitsServicesCacheFree() {
        val b = Parsers.realtime(fields).memoryBreakdown!!
        assertEquals(125 * gib, b.total)
        assertEquals(100 * gib, b.arc)
        assertEquals(16 * gib, b.services) // 125 - 9 available - 100 ARC
        assertEquals(9 * gib, b.free)
    }

    @Test
    fun networkSumsUpInterfacesOnly() {
        val s = Parsers.realtime(fields)
        assertEquals(7200.0, s.netRxBytesPerSec!!, 1e-9)
        assertEquals(1100.0, s.netTxBytesPerSec!!, 1e-9)
    }

    @Test
    fun formats() {
        assertEquals("7.2 KB/s", Format.rate(7200.0))
        assertEquals("850 B/s", Format.rate(850.0))
        assertEquals("118 MB/s", Format.rate(118_000_000.0))
        assertEquals("<1%", Format.cpuPercent(0.0))
        assertEquals("3%", Format.cpuPercent(3.0))
        assertEquals("2.5%", Format.cpuPercent(2.5))
        assertEquals("42%", Format.cpuPercent(42.4))
        assertNull(null)
    }
}
