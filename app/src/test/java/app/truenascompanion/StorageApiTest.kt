package app.truenascompanion

import app.truenascompanion.data.api.Parsers
import app.truenascompanion.data.api.StorageApi
import app.truenascompanion.data.api.TrueNasApi
import app.truenascompanion.data.model.DatasetCreateRequest
import app.truenascompanion.data.model.NfsShareInput
import app.truenascompanion.data.model.SmbShareInput
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

/** Exact RPC shapes for datasets/shares against TrueNAS 25.10.5 middleware. */
class StorageApiTest {
    private val calls = mutableListOf<Pair<String, List<JsonElement>>>()

    private fun respond(method: String, args: List<JsonElement>): JsonElement = when (method) {
        "pool.dataset.create" -> Json.parseToJsonElement(
            """{"id":"tank/media","name":"tank/media","pool":"tank","type":"FILESYSTEM","encrypted":false,"locked":false,"mountpoint":"/mnt/tank/media","used":{"parsed":0},"available":{"parsed":1000},"compression":{"parsed":"LZ4","value":"LZ4"}}"""
        )
        "sharing.smb.create", "sharing.smb.update" -> Json.parseToJsonElement(
            """{"id":1,"name":"media","path":"/mnt/tank/media","purpose":"DEFAULT_SHARE","enabled":true,"comment":"","readonly":false,"browsable":true,"locked":false}"""
        )
        "sharing.nfs.create", "sharing.nfs.update" -> Json.parseToJsonElement(
            """{"id":2,"path":"/mnt/tank/media","comment":"","enabled":true,"ro":false,"networks":["192.168.1.0/24"],"hosts":[],"locked":false}"""
        )
        "sharing.smb.query" -> Json.parseToJsonElement(
            """[{"id":1,"name":"media","path":"/mnt/tank/media","purpose":"DEFAULT_SHARE","enabled":true,"comment":"hi","readonly":false,"browsable":true,"locked":false}]"""
        )
        "sharing.nfs.query" -> Json.parseToJsonElement(
            """[{"id":2,"path":"/mnt/tank/media","comment":"","enabled":true,"ro":true,"networks":[],"hosts":["client.local"],"locked":null}]"""
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
                respond(method, params)
            }
            "datasets" -> emptyList<Any>()
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === args?.get(0)
            "toString" -> "fake"
            else -> null
        }
    } as TrueNasApi

    private val s = StorageApi(api)

    @Test fun createFilesystemDataset() = runBlocking {
        val d = s.createDataset(DatasetCreateRequest("tank/media", shareType = "SMB", compression = "LZ4", comments = "films"))
        assertEquals("pool.dataset.create", calls[0].first)
        val body = calls[0].second[0].jsonObject
        assertEquals("\"tank/media\"", body["name"].toString())
        assertEquals("\"FILESYSTEM\"", body["type"].toString())
        assertEquals("\"SMB\"", body["share_type"].toString())
        assertEquals("\"LZ4\"", body["compression"].toString())
        assertEquals("tank/media", d?.id)
        assertEquals("LZ4", d?.compression)
    }

    @Test fun createZvol() = runBlocking {
        s.createDataset(DatasetCreateRequest("tank/vm-disk", type = "VOLUME", volsize = 10L shl 30, sparse = true))
        val body = calls.last().second[0].jsonObject
        assertEquals("\"VOLUME\"", body["type"].toString())
        assertEquals((10L shl 30).toString(), body["volsize"].toString())
        assertEquals("true", body["sparse"].toString())
        assertFalse(body.containsKey("share_type"))
    }

    @Test fun renameAndDelete() = runBlocking {
        s.renameDataset("tank/a", "tank/b", recursive = true)
        s.deleteDataset("tank/b", recursive = true, force = true)
        assertEquals("pool.dataset.rename", calls[0].first)
        assertEquals("\"tank/a\"", calls[0].second[0].toString())
        assertEquals("""{"new_name":"tank/b","recursive":true,"force":false}""", calls[0].second[1].toString())
        assertEquals("pool.dataset.delete", calls[1].first)
        assertEquals("""{"recursive":true,"force":true}""", calls[1].second[1].toString())
    }

    @Test fun smbShareCrud() = runBlocking {
        val created = s.createSmbShare(SmbShareInput("media", "/mnt/tank/media", purpose = "DEFAULT_SHARE", comment = "x"))
        assertEquals("media", created?.name)
        assertEquals("tank/media", created?.datasetId)
        val body = calls[0].second[0].jsonObject
        assertEquals("\"DEFAULT_SHARE\"", body["purpose"].toString())
        assertEquals("\"/mnt/tank/media\"", body["path"].toString())
        s.updateSmbShare(1, SmbShareInput("media", "/mnt/tank/media", readonly = true))
        assertEquals("sharing.smb.update", calls[1].first)
        assertEquals("1", calls[1].second[0].toString())
        s.deleteSmbShare(1)
        assertEquals("sharing.smb.delete", calls[2].first)
        val listed = s.smbShares()
        assertEquals(1, listed.size)
        assertEquals("hi", listed[0].comment)
    }

    @Test fun nfsShareCrud() = runBlocking {
        val created = s.createNfsShare(NfsShareInput("/mnt/tank/media", networks = listOf("192.168.1.0/24"), readonly = false))
        assertEquals("tank/media", created?.datasetId)
        val body = calls[0].second[0].jsonObject
        assertEquals("false", body["ro"].toString())
        assertEquals("""["192.168.1.0/24"]""", body["networks"].toString())
        s.deleteNfsShare(2)
        val listed = s.nfsShares()
        assertTrue(listed[0].readonly)
        assertEquals(listOf("client.local"), listed[0].hosts)
    }

    @Test fun datasetParserExtras() {
        val o = Json.parseToJsonElement(
            """{"id":"tank/x","pool":"tank","type":"VOLUME","encrypted":false,"locked":false,"mountpoint":null,
               "used":{"parsed":100},"available":{"parsed":900},"compression":{"value":"ZSTD"},"compressratio":{"value":"1.50x"},
               "comments":{"value":"note"},"volsize":{"parsed":10737418240},"readonly":{"value":"off"}}"""
        ).jsonObject
        val d = Parsers.dataset(o)
        assertEquals(true, d.isVolume)
        assertEquals("ZSTD", d.compression)
        assertEquals("1.50x", d.compressratio)
        assertEquals("note", d.comments)
        assertEquals(10737418240L, d.volsize)
    }
}
