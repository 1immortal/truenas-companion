package app.truenascompanion

import app.truenascompanion.data.api.NasSystemApi
import app.truenascompanion.data.api.TrueNasApi
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

class NasSystemApiTest {
    private val calls = mutableListOf<Pair<String, List<JsonElement>>>()

    private fun respond(method: String): JsonElement = when (method) {
        "update.status" -> Json.parseToJsonElement(
            """{"code":"NORMAL","status":{"current_version":{"train":"TrueNAS-SCALE-Goldeye-Nightly","profile":"GENERAL","matches_profile":true},
               "new_version":{"version":"25.10.5","release_notes":"Fixes","release_notes_url":"https://example/notes",
               "manifest":{"changelog":"Line 1\nLine 2"}}},"error":null,"update_download_progress":null}"""
        )
        "update.run" -> Json.parseToJsonElement("42")
        "boot.environment.query" -> Json.parseToJsonElement(
            """[{"id":"25.10.4","dataset":"boot-pool/ROOT/25.10.4","active":true,"activated":true,"created":"2024-01-15T12:00:00Z",
               "used_bytes":1200000000,"used":"1.12G","keep":true,"can_activate":true},
              {"id":"25.10.3","dataset":"boot-pool/ROOT/25.10.3","active":false,"activated":false,"created":"2023-11-01T12:00:00Z",
               "used_bytes":1100000000,"used":"1.02G","keep":false,"can_activate":true}]"""
        )
        else -> JsonNull
    }

    @Suppress("UNCHECKED_CAST")
    private val api = Proxy.newProxyInstance(TrueNasApi::class.java.classLoader, arrayOf(TrueNasApi::class.java)) { proxy, m, args ->
        when (m.name) {
            "rpc" -> {
                val method = args!![0] as String
                val params = (args[1] as Array<JsonElement>).toList()
                calls += method to params
                respond(method)
            }
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === args?.get(0)
            "toString" -> "fake"
            else -> null
        }
    } as TrueNasApi

    private val n = NasSystemApi(api)

    @Test fun updateStatusParses() = runBlocking {
        val s = n.updateStatus()
        assertEquals("update.status", calls[0].first)
        assertTrue(calls[0].second.isEmpty())
        assertTrue(s.updateAvailable)
        assertEquals("25.10.5", s.newVersion)
        assertEquals("Fixes", s.releaseNotes)
        assertEquals("Line 1\nLine 2", s.changelog)
        assertEquals("TrueNAS-SCALE-Goldeye-Nightly", s.currentTrain)
    }

    @Test fun startUpdateReturnsJobId() = runBlocking {
        assertEquals(42L, n.startUpdate(reboot = true))
        assertEquals("update.run", calls[0].first)
        assertEquals("""{"reboot":true}""", calls[0].second[0].toString())
    }

    @Test fun bootEnvCrudShapes() = runBlocking {
        val list = n.bootEnvironments()
        assertEquals(2, list.size)
        assertEquals("25.10.4", list[0].id)
        assertTrue(list[0].active && list[0].keep)
        n.activateBootEnv("25.10.3")
        n.keepBootEnv("25.10.3", true)
        n.cloneBootEnv("25.10.3", "25.10.3-copy")
        n.destroyBootEnv("25.10.3-copy")
        assertEquals("""{"id":"25.10.3"}""", calls[1].second[0].toString())
        assertEquals("""{"id":"25.10.3","value":true}""", calls[2].second[0].toString())
        assertEquals("""{"id":"25.10.3","target":"25.10.3-copy"}""", calls[3].second[0].toString())
        assertEquals("""{"id":"25.10.3-copy"}""", calls[4].second[0].toString())
        assertEquals(listOf("boot.environment.query", "boot.environment.activate", "boot.environment.keep", "boot.environment.clone", "boot.environment.destroy"), calls.map { it.first })
    }
}
