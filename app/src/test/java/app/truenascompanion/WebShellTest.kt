package app.truenascompanion

import app.truenascompanion.data.api.Credentials
import app.truenascompanion.data.api.LoginStep
import app.truenascompanion.data.api.TrueNasException
import app.truenascompanion.data.api.WebSocketAuth
import app.truenascompanion.data.model.AuthMethod
import app.truenascompanion.data.model.Route
import app.truenascompanion.data.model.ServerConfig
import app.truenascompanion.data.shell.ShellEnd
import app.truenascompanion.data.shell.ShellListener
import app.truenascompanion.data.shell.ShellMessages
import app.truenascompanion.data.shell.ShellTarget
import app.truenascompanion.data.shell.WebShellConnection
import app.truenascompanion.data.shell.WebShellProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString
import okio.ByteString.Companion.encodeUtf8
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The web shell client against a fake that follows middleware `apps/webshell_app.py` (25.10): first text frame
 * `{token, options}`, reply `{"msg":"connected","id":...}` or `{"msg":"failed",...}` (socket stays open), then raw
 * binary frames both ways, and the server closes the socket when the shell exits.
 */
class WebShellTest {
    // ---------- protocol ----------
    @Test
    fun urlKeepsSchemeHostAndSubpath() {
        assertEquals("wss://nas.example.org/websocket/shell/", WebShellProtocol.url("https://nas.example.org"))
        assertEquals("wss://nas.example.org/truenas/websocket/shell/", WebShellProtocol.url("https://nas.example.org/truenas/"))
        assertEquals("ws://192.168.1.10:8080/websocket/shell/", WebShellProtocol.url("http://192.168.1.10:8080"))
        assertEquals("wss://homenas:444/websocket/shell/", WebShellProtocol.url("HTTPS://homenas:444/"))
    }

    @Test
    fun optionsMatchTheWebUi() {
        assertEquals("{}", WebShellProtocol.options(ShellTarget.Host).toString())
        val app = WebShellProtocol.options(ShellTarget.App("plex", "abc123", "plex", "/bin/sh"))
        assertEquals("plex", app["app_name"]!!.jsonPrimitive.content)
        assertEquals("abc123", app["container_id"]!!.jsonPrimitive.content)
        assertEquals("/bin/sh", app["command"]!!.jsonPrimitive.content)
        val inst = WebShellProtocol.options(ShellTarget.Instance("debian", null))
        assertEquals("debian", inst["virt_instance_id"]!!.jsonPrimitive.content)
        assertEquals("false", inst["use_console"]!!.jsonPrimitive.content)
        assertNull(inst["command"])
        assertEquals("/bin/bash", WebShellProtocol.options(ShellTarget.Instance("debian", "/bin/bash"))["command"]!!.jsonPrimitive.content)
        val hello = Json.parseToJsonElement(WebShellProtocol.hello("t0k", ShellTarget.Host)).jsonObject
        assertEquals("t0k", hello["token"]!!.jsonPrimitive.content)
        assertEquals(JsonObject(emptyMap()), hello["options"])
    }

    @Test
    fun parsesControlFrames() {
        assertEquals(WebShellProtocol.Control.Connected("42"), WebShellProtocol.parseControl("""{"msg":"connected","id":"42"}"""))
        assertEquals(WebShellProtocol.Control.Failed("Invalid token"),
            WebShellProtocol.parseControl("""{"msg":"failed","error":{"error":207,"reason":"Invalid token"}}"""))
        assertNull(WebShellProtocol.parseControl("""{"msg":"other"}"""))
        assertNull(WebShellProtocol.parseControl("root@nas:~# "))
    }

    @Test
    fun commandIsOneProgram() {
        assertTrue(WebShellProtocol.validCommand("/bin/sh"))
        assertTrue(WebShellProtocol.validCommand("/usr/bin/fish"))
        assertTrue(WebShellProtocol.validCommand("bash"))
        assertFalse(WebShellProtocol.validCommand(""))
        assertFalse(WebShellProtocol.validCommand("/bin/sh -l"))
        assertFalse(WebShellProtocol.validCommand("sh;reboot"))
        assertFalse(WebShellProtocol.validCommand("\$SHELL"))
        assertFalse(WebShellProtocol.validCommand("a|b"))
    }

    @Test
    fun messagesPointAtProxyOnlyOnRemote() {
        assertEquals(ShellMessages.PROXY_HINT, ShellMessages.describe(ShellEnd.UpgradeFailed(400), Route.REMOTE).hint)
        assertTrue(ShellMessages.PROXY_HINT.contains("Websockets Support"))
        assertTrue(ShellMessages.describe(ShellEnd.UpgradeFailed(400), Route.REMOTE).detail.contains("400"))
        assertFalse(ShellMessages.describe(ShellEnd.UpgradeFailed(404), Route.LOCAL).hint!!.contains("Websockets"))
        assertEquals(ShellMessages.PROXY_HINT, ShellMessages.describe(ShellEnd.Timeout, Route.REMOTE).hint)
        assertNull(ShellMessages.describe(ShellEnd.Timeout, Route.LOCAL).hint)
        assertTrue(ShellMessages.describe(ShellEnd.ClosedEarly(1008, ""), Route.LOCAL).hint!!.contains("allowlist"))
        assertTrue(ShellMessages.describe(ShellEnd.Rejected("Invalid token"), Route.LOCAL).hint!!.contains("web shell privilege"))
        assertFalse(ShellMessages.beforeConnect(TrueNasException.MethodNotFound("auth.generate_token")).canReconnect)
    }

    // ---------- fake middleware ----------
    private lateinit var server: MockWebServer
    private val hellos = Collections.synchronizedList(mutableListOf<JsonObject>())
    private val received = ByteArrayOutputStream()

    private enum class Mode { NORMAL, OUTPUT_FIRST, CLOSE_BEFORE_CONNECTED, SILENT }

    private fun shellHandler(mode: Mode) = MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
        var authed = false
        override fun onMessage(webSocket: WebSocket, text: String) {
            if (authed) return // like middleware: after auth, text frames aren't expected (the client sends binary)
            val hello = Json.parseToJsonElement(text).jsonObject
            hellos += hello
            when {
                mode == Mode.SILENT -> Unit
                mode == Mode.CLOSE_BEFORE_CONNECTED -> webSocket.close(1000, null) // e.g. invalid container: closed without a message
                hello["token"]!!.jsonPrimitive.content != "good" ->
                    webSocket.send("""{"msg":"failed","error":{"error":207,"reason":"Invalid token"}}""")
                else -> {
                    authed = true
                    // middleware starts the shell thread before sending "connected", so output can come first
                    if (mode == Mode.OUTPUT_FIRST) webSocket.send("WARNING: Your user does not have sudo privileges\r\n".encodeUtf8())
                    webSocket.send("""{"msg":"connected","id":"sess-1"}""")
                    webSocket.send("\u001b[1;36mroot@homenas\u001b[0m:~# ".encodeUtf8())
                }
            }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            if (!authed) return
            synchronized(received) { received.write(bytes.toByteArray()) }
            webSocket.send(bytes) // pty echo
            if (bytes.utf8().endsWith("exit\r")) webSocket.close(1000, null) // the shell exited
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null) }
    })

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val client = OkHttpClient()

    @Before fun setUp() { server = MockWebServer() }

    @After fun tearDown() {
        scope.cancel()
        runCatching { server.shutdown() }
    }

    private class Recorder : ShellListener {
        val connected = CountDownLatch(1)
        val ended = CountDownLatch(1)
        @Volatile var id: String? = null
        @Volatile var end: ShellEnd? = null
        val ends = java.util.concurrent.atomic.AtomicInteger()
        val output = StringBuffer()
        override fun onConnected(id: String?) { this.id = id; connected.countDown() }
        override fun onOutput(data: ByteArray) { output.append(data.decodeToString()) }
        override fun onEnd(end: ShellEnd) { this.end = end; ends.incrementAndGet(); ended.countDown() }
        fun awaitOutput(s: String) { repeat(100) { if (output.contains(s)) return; Thread.sleep(20) }; throw AssertionError("no '$s' in output") }
    }

    private fun connect(mode: Mode, token: String, target: ShellTarget, timeoutMs: Long = 5_000): Pair<WebShellConnection, Recorder> {
        server.enqueue(shellHandler(mode))
        server.start()
        val rec = Recorder()
        val url = WebShellProtocol.url("http://127.0.0.1:${server.port}")
        return WebShellConnection(client, url, scope, rec, timeoutMs).also { it.open(token, target) } to rec
    }

    @Test
    fun appShellHandshakeStreamAndExit() {
        val (conn, rec) = connect(Mode.NORMAL, "good", ShellTarget.App("plex", "abc123", "plex", "/bin/bash"))
        assertTrue(rec.connected.await(5, TimeUnit.SECONDS))
        assertEquals("/websocket/shell/", server.takeRequest().path)
        assertEquals("sess-1", rec.id)
        val hello = hellos.single()
        assertEquals("good", hello["token"]!!.jsonPrimitive.content)
        assertEquals("abc123", hello["options"]!!.jsonObject["container_id"]!!.jsonPrimitive.content)
        assertEquals("/bin/bash", hello["options"]!!.jsonObject["command"]!!.jsonPrimitive.content)
        rec.awaitOutput("root@homenas")
        assertFalse("control frame must not reach the terminal", rec.output.contains("connected"))
        conn.send("ls -la\r".encodeToByteArray())
        rec.awaitOutput("ls -la")
        conn.send("xexit\r".encodeToByteArray(), 1, 5)
        assertTrue(rec.ended.await(5, TimeUnit.SECONDS))
        assertEquals(ShellEnd.Exited, rec.end)
        assertEquals("ls -la\rexit\r", synchronized(received) { received.toString(Charsets.UTF_8) })
        conn.close()
        Thread.sleep(100)
        assertEquals(1, rec.ends.get())
    }

    @Test
    fun outputBeforeConnectedIsKept() {
        val (conn, rec) = connect(Mode.OUTPUT_FIRST, "good", ShellTarget.Host)
        assertTrue(rec.connected.await(5, TimeUnit.SECONDS))
        rec.awaitOutput("root@homenas")
        assertTrue(rec.output.toString().startsWith("WARNING: Your user does not have sudo privileges"))
        conn.close()
    }

    @Test
    fun hostShellClosedByUser() {
        val (conn, rec) = connect(Mode.NORMAL, "good", ShellTarget.Host)
        assertTrue(rec.connected.await(5, TimeUnit.SECONDS))
        assertEquals(JsonObject(emptyMap()), hellos.single()["options"])
        conn.close("bye")
        assertTrue(rec.ended.await(5, TimeUnit.SECONDS))
        assertEquals(ShellEnd.Closed("bye"), rec.end)
        conn.send("ignored".encodeToByteArray()) // no crash after close
    }

    @Test
    fun badTokenIsRejected() {
        val (_, rec) = connect(Mode.NORMAL, "expired", ShellTarget.Instance("debian", null))
        assertTrue(rec.ended.await(5, TimeUnit.SECONDS))
        assertEquals(ShellEnd.Rejected("Invalid token"), rec.end)
        assertNull(hellos.single()["options"]!!.jsonObject["command"])
    }

    @Test
    fun closedBeforeConnected() {
        val (_, rec) = connect(Mode.CLOSE_BEFORE_CONNECTED, "good", ShellTarget.App("plex", "gone", "plex", "/bin/sh"))
        assertTrue(rec.ended.await(5, TimeUnit.SECONDS))
        val end = rec.end
        assertTrue("$end", end is ShellEnd.ClosedEarly && end.code == 1000)
    }

    @Test
    fun proxyWithoutWebSocketSupport() {
        server.enqueue(MockResponse().setResponseCode(400).setBody("Bad Request"))
        server.start()
        val rec = Recorder()
        WebShellConnection(client, WebShellProtocol.url("http://127.0.0.1:${server.port}"), scope, rec).open("good", ShellTarget.Host)
        assertTrue(rec.ended.await(5, TimeUnit.SECONDS))
        assertEquals(ShellEnd.UpgradeFailed(400), rec.end)
    }

    @Test
    fun closeBeforeOpenDoesNotConnect() {
        server.start()
        val rec = Recorder()
        val conn = WebShellConnection(client, WebShellProtocol.url("http://127.0.0.1:${server.port}"), scope, rec, 500)
        conn.close("left")
        conn.open("secret-token", ShellTarget.Host)
        assertEquals(ShellEnd.Closed("left"), rec.end)
        Thread.sleep(400)
        assertEquals("socket must not be opened after close", 0, server.requestCount)
        assertEquals(1, rec.ends.get())
        assertFalse(rec.output.contains("secret-token"))
    }

    @Test
    fun noConnectedFrameTimesOut() {
        val (_, rec) = connect(Mode.SILENT, "good", ShellTarget.Host, timeoutMs = 500)
        assertTrue(rec.ended.await(5, TimeUnit.SECONDS))
        assertEquals(ShellEnd.Timeout, rec.end)
    }

    // ---------- API calls ----------
    private val calls = Collections.synchronizedList(mutableListOf<Pair<String, JsonArray>>())

    private fun rpc(method: String, params: JsonArray): JsonElement = when (method) {
        "auth.login_ex" -> buildJsonObject { put("response_type", "SUCCESS") }
        "auth.generate_token" -> JsonPrimitive("tok-${calls.size}")
        "app.container_console_choices" -> buildJsonObject {
            put("f00d", buildJsonObject { put("service_name", "web"); put("image", "nginx:1") })
            put("beef", buildJsonObject { put("service_name", "db") })
        }
        else -> JsonPrimitive(true)
    }

    @Test
    fun apiCallsMatchTheWebUi() = runBlocking {
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) = Unit
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null) }
            override fun onMessage(webSocket: WebSocket, text: String) {
                val req = Json.parseToJsonElement(text).jsonObject
                val method = req["method"]!!.jsonPrimitive.content
                val params = req["params"]?.jsonArray ?: JsonArray(emptyList())
                calls += method to params
                webSocket.send(JsonObject(mapOf("jsonrpc" to JsonPrimitive("2.0"), "id" to req["id"]!!, "result" to rpc(method, params))).toString())
            }
        }))
        server.start()
        val cfg = ServerConfig(id = "t", name = "t", url = "http://127.0.0.1:${server.port}", username = "admin", authMethod = AuthMethod.PASSWORD, sessionDays = 7)
        val api = (WebSocketAuth.login(cfg, Credentials.Token("tok-1"), 604800) as LoginStep.Success).api
        val token = api.shellToken()
        assertNotNull(token)
        api.resizeShell("sess-1", 120, 40)
        val choices = api.appShellContainers("plex")
        api.close()
        assertEquals("[300,{},true,true]", calls.last { it.first == "auth.generate_token" }.second.toString())
        assertEquals("[\"sess-1\",120,40]", calls.single { it.first == "core.resize_shell" }.second.toString())
        assertEquals(mapOf("f00d" to "web", "beef" to "db"), choices)
    }
}
