package app.truenascompanion

import app.truenascompanion.data.api.ProtectionApi
import app.truenascompanion.data.api.TrueNasApi
import app.truenascompanion.data.model.CronSchedule
import app.truenascompanion.data.model.SmartTestType
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

/** Checks the exact RPC calls ProtectionApi makes (method names/argument shapes from the 25.10.3 middleware). */
class ProtectionApiTest {
    private val calls = mutableListOf<Pair<String, List<JsonElement>>>()
    private var runFails = false

    private fun respond(method: String): JsonElement = when (method) {
        "cronjob.create" -> Json.parseToJsonElement("""{"id":42}""")
        "cronjob.run" -> if (runFails) throw IllegalStateException("boom") else Json.parseToJsonElement("99")
        "pool.scrub.scrub", "replication.run", "cloudsync.sync", "rsynctask.run" -> Json.parseToJsonElement("7")
        else -> JsonNull
    }

    @Suppress("UNCHECKED_CAST")
    private val api = Proxy.newProxyInstance(TrueNasApi::class.java.classLoader, arrayOf(TrueNasApi::class.java)) { proxy, m, args ->
        when (m.name) {
            "rpc" -> {
                val method = args[0] as String
                calls += method to (args[1] as Array<JsonElement>).toList()
                respond(method)
            }
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === args?.get(0)
            "toString" -> "fake"
            else -> null
        }
    } as TrueNasApi
    private val p = ProtectionApi(api)

    @Test fun oneOffSmartTestCreatesRunsAndDeletes() = runBlocking {
        val awaited = mutableListOf<Long>()
        p.runSmartTestNow(SmartTestType.LONG, listOf("{serial}ABC")) { awaited += it }
        assertEquals(listOf("cronjob.create", "cronjob.run", "cronjob.delete"), calls.map { it.first })
        val create = calls[0].second[0].jsonObject
        assertEquals("false", create["enabled"].toString())
        assertEquals("\"midclt call disk.smart_test LONG '[\\\"{serial}ABC\\\"]'\"", create["command"].toString())
        assertEquals("\"${ProtectionApi.ONE_OFF_DESCRIPTION}\"", create["description"].toString())
        assertEquals(listOf("42", "false"), calls[1].second.map { it.toString() }) // skip_disabled = false
        assertEquals(listOf(99L), awaited)
        assertEquals("42", calls[2].second[0].toString())
    }

    @Test fun oneOffIsDeletedEvenIfRunFails() = runBlocking {
        runFails = true
        assertTrue(runCatching { p.runSmartTestNow(SmartTestType.SHORT, listOf("*")) {} }.isFailure)
        assertEquals("cronjob.delete", calls.last().first)
    }

    @Test fun snapshotCalls() = runBlocking {
        p.rollback("tank/a@s1", destroyNewer = true)
        p.clone("tank/a@s1", "tank/restore")
        p.deleteSnapshot("tank/a@s1")
        p.snapshots("tank/a")
        assertEquals("pool.snapshot.rollback", calls[0].first)
        assertEquals("""{"recursive":true,"recursive_clones":false,"force":false}""", calls[0].second[1].toString())
        assertEquals("""{"snapshot":"tank/a@s1","dataset_dst":"tank/restore"}""", calls[1].second[0].toString())
        assertEquals("""{"recursive":false}""", calls[2].second[1].toString())
        assertEquals("""[["dataset","=","tank/a"]]""", calls[3].second[0].toString())
        assertEquals("""{"extra":{"properties":["used","referenced","creation"],"holds":true}}""", calls[3].second[1].toString())
    }

    @Test fun scrubAndTaskCalls() = runBlocking {
        assertEquals(7L, p.startScrub("tank"))
        p.pauseScrub("tank")
        p.createScrubTask(1, 35, CronSchedule(minute = "00", hour = "00", dow = "7"))
        p.deleteSnapshotTask(3)
        p.setSmartScheduleEnabled(5, false)
        p.smartSchedules()
        assertEquals(listOf("\"tank\"", "\"START\""), calls[0].second.map { it.toString() })
        assertEquals("\"PAUSE\"", calls[1].second[1].toString())
        assertEquals("""{"pool":1,"threshold":35,"enabled":true,"schedule":{"minute":"00","hour":"00","dom":"*","month":"*","dow":"7"}}""", calls[2].second[0].toString())
        assertEquals("""{"fixate_removal_date":true}""", calls[3].second[1].toString())
        assertEquals("""{"enabled":false}""", calls[4].second[1].toString())
        assertEquals("""[["command","^","midclt call disk.smart_test"]]""", calls[5].second[0].toString())
    }

    @Test fun backupCalls() = runBlocking {
        val t = ProtectionSamples.backup()
        assertEquals(7L, p.runBackup(t))
        p.setBackupEnabled(t.copy(kind = app.truenascompanion.data.model.BackupKind.RSYNC), false)
        assertEquals("cloudsync.sync", calls[0].first); assertEquals("""{"dry_run":false}""", calls[0].second[1].toString())
        assertEquals("rsynctask.update", calls[1].first); assertEquals("""{"enabled":false,"validate_rpath":false}""", calls[1].second[1].toString())
    }
}
