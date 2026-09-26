package app.truenascompanion

import app.truenascompanion.data.api.Credentials
import app.truenascompanion.data.api.LoginStep
import app.truenascompanion.data.api.SessionTokenManager
import app.truenascompanion.data.net.Keepalive
import app.truenascompanion.data.api.TrueNasException
import app.truenascompanion.data.api.WebSocketAuth
import app.truenascompanion.data.model.AuthMethod
import app.truenascompanion.data.model.ServerConfig
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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.Collections

/**
 * Drives the password / 2FA / session-token sign-in against a fake JSON-RPC server that follows the documented
 * TrueNAS 25.10 contract (auth.login_ex, auth.login_ex_continue, auth.generate_token).
 */
class WebSocketAuthTest {
    private lateinit var server: MockWebServer
    private val calls = Collections.synchronizedList(mutableListOf<Pair<String, JsonArray>>())
    private val validTokens = Collections.synchronizedSet(mutableSetOf<String>())
    private val issued = java.util.concurrent.atomic.AtomicInteger()

    /** Per-connection state: like middleware, the token a connection signed in with is destroyed when it closes. */
    private class Conn { var usedToken: String? = null }

    private fun respond(conn: Conn, method: String, params: JsonArray): JsonElement = when (method) {
        "auth.login_ex" -> {
            val data = params[0].jsonObject
            when (data["mechanism"]!!.jsonPrimitive.content) {
                "PASSWORD_PLAIN" ->
                    if (data["password"]!!.jsonPrimitive.content == "secret")
                        buildJsonObject { put("response_type", "OTP_REQUIRED"); put("username", data["username"]!!.jsonPrimitive.content) }
                    else buildJsonObject { put("response_type", "AUTH_ERR") }
                "TOKEN_PLAIN" ->
                    if (data["token"]!!.jsonPrimitive.content in validTokens) {
                        conn.usedToken = data["token"]!!.jsonPrimitive.content
                        buildJsonObject { put("response_type", "SUCCESS") }
                    }
                    else buildJsonObject { put("response_type", "AUTH_ERR") }
                else -> buildJsonObject { put("response_type", "AUTH_ERR") }
            }
        }
        "auth.login_ex_continue" ->
            if (params[0].jsonObject["otp_token"]!!.jsonPrimitive.content == "123456") buildJsonObject { put("response_type", "SUCCESS") }
            else buildJsonObject { put("response_type", "OTP_REQUIRED"); put("username", "admin") }
        "auth.generate_token" -> JsonPrimitive("tok-${issued.incrementAndGet()}").also { validTokens += it.content }
        else -> JsonPrimitive(true)
    }

    @Before
    fun setUp() {
        server = MockWebServer()
        repeat(8) {
            val conn = Conn()
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) = Unit
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    conn.usedToken?.let { validTokens.remove(it) } // TokenSessionManagerCredentials.logout()
                    webSocket.close(1000, null)
                }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    conn.usedToken?.let { validTokens.remove(it) }
                }
                override fun onMessage(webSocket: WebSocket, text: String) {
                    val req = Json.parseToJsonElement(text).jsonObject
                    val method = req["method"]!!.jsonPrimitive.content
                    val params = req["params"]?.jsonArray ?: JsonArray(emptyList())
                    calls += method to params
                    webSocket.send(JsonObject(mapOf("jsonrpc" to JsonPrimitive("2.0"), "id" to req["id"]!!, "result" to respond(conn, method, params))).toString())
                }
            }))
        }
        server.start()
    }

    @After
    fun tearDown() = runCatching { server.shutdown() }.let { }

    private fun config() = ServerConfig(
        id = "t", name = "test", url = "http://127.0.0.1:${server.port}", username = "admin",
        authMethod = AuthMethod.PASSWORD, sessionDays = 7,
    )

    @Test
    fun password_then_otp_then_token_reuse() = runBlocking {
        val first = WebSocketAuth.login(config(), Credentials.Password("admin", "secret"), 604800)
        assertTrue(first is LoginStep.OtpRequired)
        val pending = (first as LoginStep.OtpRequired).pending
        assertEquals("admin", first.username)

        // A wrong code can be retried on the same connection.
        assertTrue(pending.submit("000000") is LoginStep.OtpRequired)
        val ok = pending.submit("123456")
        assertTrue(ok is LoginStep.Success)
        val token = (ok as LoginStep.Success).token!!
        ok.api.close()

        val gen = calls.first { it.first == "auth.generate_token" }.second
        assertEquals(JsonPrimitive(604800L), gen[0]) // ttl
        assertEquals(JsonPrimitive(false), gen[2]) // match_origin
        assertEquals(JsonPrimitive(false), gen[3]) // single_use

        assertTrue(ok.spare != null && ok.spare!!.token != token.token) // independent fallback

        // Reconnect with the token through the shared manager: no password, no OTP; the spent token is rotated.
        val store = MemoryStore().apply { data["t"] = ok.tokens }
        val sessions = SessionTokenManager(store)
        repeat(3) { round ->
            val before = store.data["t"]!!
            val api = sessions.connect("t", 604800) { WebSocketAuth.tokenConnection(config(), it, Keepalive.NONE) }!!
            api.close()
            val after = store.data["t"]!!
            assertNotEquals("round $round rotated", before.primary!!.token, after.primary!!.token)
            assertEquals("spare untouched while fresh", ok.spare!!.token, after.spare!!.token)
            // The server destroys the spent token once that connection closes; the new one is still valid.
            waitUntil { before.primary!!.token !in validTokens }
            assertTrue(after.primary!!.token in validTokens)
        }
    }

    private fun waitUntil(check: () -> Boolean) {
        val end = System.currentTimeMillis() + 5_000
        while (!check() && System.currentTimeMillis() < end) Thread.sleep(10)
        assertTrue(check())
    }

    /** Before v0.4.2 background checks signed in with the token without rotating: the next sign-in failed. */
    @Test
    fun token_is_spent_when_its_connection_closes() = runBlocking {
        validTokens += "t1"
        WebSocketAuth.tokenConnection(config(), "t1", Keepalive.NONE).close()
        waitUntil { "t1" !in validTokens }
        try {
            WebSocketAuth.tokenConnection(config(), "t1", Keepalive.NONE)
            fail("expected TokenRejected")
        } catch (e: TrueNasException.TokenRejected) { /* ok */ }
    }

    @Test
    fun wrong_password_and_bad_token() = runBlocking {
        try {
            WebSocketAuth.login(config(), Credentials.Password("admin", "nope"), 60)
            fail("expected PasswordRejected")
        } catch (e: TrueNasException.PasswordRejected) { /* ok */ }
        try {
            WebSocketAuth.tokenConnection(config(), "expired", Keepalive.NONE)
            fail("expected TokenRejected")
        } catch (e: TrueNasException.TokenRejected) { /* ok */ }
    }
}
