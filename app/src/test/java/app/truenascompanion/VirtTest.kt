package app.truenascompanion

import app.truenascompanion.TestTls.https
import app.truenascompanion.data.api.Credentials
import app.truenascompanion.data.api.LoginStep
import app.truenascompanion.data.api.Parsers
import app.truenascompanion.data.api.TrueNasApi
import app.truenascompanion.data.api.WebSocketAuth
import app.truenascompanion.data.model.AuthMethod
import app.truenascompanion.data.model.InstanceStatus
import app.truenascompanion.data.model.ServerConfig
import app.truenascompanion.data.model.VmState
import app.truenascompanion.ui.virt.VmForm
import app.truenascompanion.ui.virt.VmFormState
import app.truenascompanion.ui.virt.containersUnavailableMessage
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.Collections

/** VM / container parsing, the New-VM form rules, and the exact `vm.create` + `vm.device.create` payloads (25.10.3 API). */
class VirtTest {
    private val vmJson = Json.parseToJsonElement(
        """{"id": 3, "name": "ubuntu", "description": "Dev box", "vcpus": 2, "cores": 2, "threads": 1, "memory": 8192,
            "autostart": true, "bootloader": "UEFI", "display_available": true,
            "status": {"state": "RUNNING", "pid": 1234, "domain_state": "RUNNING"},
            "devices": [
              {"id": 12, "vm": 3, "order": 1003, "attributes": {"dtype": "NIC", "type": "VIRTIO", "nic_attach": "br0", "mac": "00:a0:98:12:34:56"}},
              {"id": 13, "vm": 3, "order": 1002, "attributes": {"dtype": "DISPLAY", "type": "SPICE", "port": 5900, "web_port": 5901, "bind": "0.0.0.0", "web": true, "password": "x"}},
              {"id": 10, "vm": 3, "order": 1001, "attributes": {"dtype": "DISK", "path": "/dev/zvol/tank/vms/ubuntu-disk0", "type": "VIRTIO"}},
              {"id": 11, "vm": 3, "order": 1000, "attributes": {"dtype": "CDROM", "path": "/mnt/tank/iso/ubuntu-24.04.iso"}}
            ]}""",
    ).jsonObject

    @Test
    fun parsesVmAndSortsDevices() {
        val vm = Parsers.vm(vmJson)!!
        assertEquals(VmState.RUNNING, vm.state)
        assertEquals(4, vm.totalCpus)
        assertEquals(8192L, vm.memoryMb)
        assertEquals(listOf("DISK", "CDROM", "NIC", "DISPLAY"), vm.devices.map { it.kind })
        assertEquals("tank/vms/ubuntu-disk0 · VIRTIO", vm.devices[0].detail)
        assertEquals("ubuntu-24.04.iso", vm.devices[1].detail)
        assertEquals("VIRTIO · br0 · 00:a0:98:12:34:56", vm.devices[2].detail)
        assertTrue(vm.hasWebDisplay)
    }

    @Test
    fun parsesContainer() {
        val o = Json.parseToJsonElement(
            """{"id": "web", "name": "web", "type": "CONTAINER", "status": "RUNNING", "cpu": "2", "memory": 2147483648, "autostart": true,
                "image": {"architecture": "amd64", "description": "Debian bookworm amd64 (20250101_05:24)", "os": "Debian", "release": "bookworm"},
                "aliases": [{"type": "INET", "address": "10.0.3.12", "netmask": 24}], "storage_pool": "tank"}""",
        ).jsonObject
        val c = Parsers.virtInstance(o)!!
        assertEquals(InstanceStatus.RUNNING, c.status)
        assertEquals("Debian bookworm amd64 (20250101_05:24)", c.image)
        assertEquals(listOf("10.0.3.12"), c.addresses)
        assertEquals(2147483648L, c.memoryBytes)
    }

    @Test
    fun containersState() {
        assertNull(containersUnavailableMessage("INITIALIZED"))
        assertNull(containersUnavailableMessage(null))
        assertTrue(containersUnavailableMessage("NO_POOL")!!.contains("Choose a pool"))
    }

    @Test
    fun vmFormRules() {
        val ok = VmFormState(name = "ubuntu_2", diskParent = "tank/vms", displayPassword = "pw")
        assertTrue(VmForm.errors(ok).isEmpty())
        assertEquals(setOf("name"), VmForm.errors(ok.copy(name = "my-vm")).keys) // TrueNAS: alphanumeric + underscore
        assertEquals(setOf("name"), VmForm.errors(ok, listOf("UBUNTU_2")).keys)
        assertEquals(setOf("displayPassword"), VmForm.errors(ok.copy(displayPassword = "")).keys)
        assertTrue(VmForm.errors(ok.copy(display = false, displayPassword = "")).isEmpty())
        assertEquals(setOf("diskParent"), VmForm.errors(ok.copy(diskParent = null)).keys)
        assertTrue(VmForm.errors(ok.copy(createDisk = false, diskParent = null)).isEmpty())
        assertEquals(setOf("iso"), VmForm.errors(ok.copy(isoPath = "/root/x.iso")).keys)
        assertEquals(512L, VmForm.parseGiB("0,5"))
        assertEquals(4096L, VmForm.parseGiB("4"))
        assertNull(VmForm.parseGiB("0.01"))
        assertEquals("1.5", VmForm.gibText(1536))
        assertEquals("8", VmForm.gibText(8192))
        assertFalse(VmForm.isImage("notes.txt"))
        assertTrue(VmForm.isImage("TrueNAS.ISO"))
    }

    // --- payloads against a fake middleware ---
    private lateinit var server: MockWebServer
    private val calls = Collections.synchronizedList(mutableListOf<Pair<String, JsonArray>>())
    @Volatile private var failNic = false

    private fun respond(method: String, params: JsonArray): JsonElement? = when (method) {
        "auth.login_ex" -> buildJsonObject { put("response_type", "SUCCESS") }
        "auth.generate_token" -> JsonPrimitive("tok-2")
        "vm.create" -> buildJsonObject { put("id", 7); put("name", params[0].jsonObject["name"]!!.jsonPrimitive.content) }
        "vm.device.create" -> if (failNic && params[0].jsonObject["attributes"]!!.jsonObject["dtype"]!!.jsonPrimitive.content == "NIC") null
            else buildJsonObject { put("id", calls.size) }
        else -> JsonPrimitive(true)
    }

    @Before
    fun setUp() {
        server = MockWebServer().https()
        repeat(3) {
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) = Unit
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null) }
                override fun onMessage(webSocket: WebSocket, text: String) {
                    val req = Json.parseToJsonElement(text).jsonObject
                    val method = req["method"]!!.jsonPrimitive.content
                    val params = req["params"]?.jsonArray ?: JsonArray(emptyList())
                    calls += method to params
                    val result = respond(method, params)
                    val body = if (result != null) mapOf("jsonrpc" to JsonPrimitive("2.0"), "id" to req["id"]!!, "result" to result)
                    else mapOf("jsonrpc" to JsonPrimitive("2.0"), "id" to req["id"]!!, "error" to buildJsonObject {
                        put("code", -32001); put("message", "Method call error")
                        put("data", buildJsonObject { put("error", 22); put("errname", "EINVAL"); put("reason", "[EINVAL] vm_device_create.attributes.nic_attach: Not a valid choice") })
                    })
                    webSocket.send(JsonObject(body).toString())
                }
            }))
        }
        server.start()
    }

    @After
    fun tearDown() = runCatching { server.shutdown() }.let { }

    private fun api(): TrueNasApi = runBlocking {
        val cfg = ServerConfig(id = "t", name = "t", url = "https://127.0.0.1:${server.port}", pinnedCertSha256 = TestTls.pin, username = "admin", authMethod = AuthMethod.PASSWORD, sessionDays = 7)
        (WebSocketAuth.login(cfg, Credentials.Token("tok-1"), 604800) as LoginStep.Success).api
    }

    @Test
    fun createSendsVmThenDevices() = runBlocking {
        val a = api()
        val id = a.vmCreate(VmForm.request(VmFormState(name = "ubuntu", vcpus = "2", memoryGiB = "4", diskParent = "tank/vms", diskSizeGiB = "32",
            isoPath = "/mnt/tank/iso/ubuntu.iso", nicAttach = "br0", displayPassword = "pw")))
        a.close()
        assertEquals(7, id)
        val create = calls.first { it.first == "vm.create" }.second[0].jsonObject
        assertEquals(4096, create["memory"]!!.jsonPrimitive.content.toInt())
        assertEquals("UEFI", create["bootloader"]!!.jsonPrimitive.content)
        val devices = calls.filter { it.first == "vm.device.create" }.map { it.second[0].jsonObject }
        assertEquals(listOf("CDROM", "DISK", "NIC", "DISPLAY"), devices.map { it["attributes"]!!.jsonObject["dtype"]!!.jsonPrimitive.content })
        assertTrue(devices.all { it["vm"]!!.jsonPrimitive.content == "7" })
        val disk = devices[1]["attributes"]!!.jsonObject
        assertEquals("tank/vms/ubuntu-disk0", disk["zvol_name"]!!.jsonPrimitive.content)
        assertEquals(32L * 1024 * 1024 * 1024, disk["zvol_volsize"]!!.jsonPrimitive.content.toLong())
        assertEquals("true", disk["create_zvol"]!!.jsonPrimitive.content)
        val display = devices[3]["attributes"]!!.jsonObject
        assertEquals("SPICE", display["type"]!!.jsonPrimitive.content)
        assertEquals("true", display["web"]!!.jsonPrimitive.content)
        assertEquals("0.0.0.0", display["bind"]!!.jsonPrimitive.content)
    }

    @Test
    fun deviceFailureStillReportsTheCreatedVm() = runBlocking {
        failNic = true
        val a = api()
        try {
            a.vmCreate(VmForm.request(VmFormState(name = "win", diskParent = "tank", nicAttach = "bogus", display = false)))
            fail("expected an error")
        } catch (e: Exception) {
            assertTrue(e.message!!, e.message!!.contains("VM win was created") && e.message!!.contains("nic"))
        } finally { a.close() }
        // disk was still created after the NIC failed? order is CDROM?, DISK, NIC -> NIC is after DISK
        assertEquals(2, calls.count { it.first == "vm.device.create" })
    }

    @Test
    fun lifecycleCallShapes() = runBlocking {
        val a = api()
        a.vmStart(3)
        a.vmPowerOff(3)
        a.vmDelete(3, deleteZvols = true)
        a.close()
        val start = calls.first { it.first == "vm.start" }.second
        assertEquals(JsonPrimitive(3), start[0])
        assertEquals("false", start[1].jsonObject["overcommit"]!!.jsonPrimitive.content)
        val del = calls.first { it.first == "vm.delete" }.second
        assertEquals("true", del[1].jsonObject["zvols"]!!.jsonPrimitive.content)
        assertEquals("false", del[1].jsonObject["force"]!!.jsonPrimitive.content)
    }
}
