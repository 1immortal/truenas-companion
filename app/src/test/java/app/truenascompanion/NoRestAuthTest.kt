package app.truenascompanion

import app.truenascompanion.data.api.Credentials
import app.truenascompanion.data.api.TrueNasConnector
import app.truenascompanion.data.api.TrueNasException
import app.truenascompanion.data.api.WEBSOCKET_API_REQUIRED_MESSAGE
import app.truenascompanion.data.api.WebSocketAuth
import app.truenascompanion.data.model.AuthMethod
import app.truenascompanion.data.model.Route
import app.truenascompanion.data.model.ServerConfig
import app.truenascompanion.data.net.Keepalive
import app.truenascompanion.data.net.LocalCheck
import app.truenascompanion.data.net.LocalDetector
import app.truenascompanion.data.net.RouteResolver
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.Collections

/**
 * TrueNAS 25.04+ raises "Deprecated REST API usage" for every successful AUTHENTICATION audit event with protocol
 * LEGACY_REST, i.e. every `/api/v2.0/...` request that carries credentials (`Authorization: Bearer|Basic|Token` or
 * `?auth_token=`); see middlewared/alert/source/rest.py and restful.py. These tests prove the app never sends one:
 * no REST fallback exists, and the unauthenticated probes carry no credentials.
 */
class NoRestAuthTest {
    private lateinit var server: MockWebServer
    private val requests = Collections.synchronizedList(mutableListOf<RecordedRequest>())

    private fun serve(answer: (RecordedRequest) -> MockResponse) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = answer(request).also { requests += request }
        }
    }

    private fun config(method: AuthMethod = AuthMethod.API_KEY, route: Route = Route.REMOTE) = ServerConfig(
        id = "s1", name = "nas", url = server.url("/").toString().trimEnd('/'), username = "admin", authMethod = method,
        activeRoute = route,
    )

    private fun assertNoRestAuth() {
        val snapshot = synchronized(requests) { requests.toList() }
        for (r in snapshot) {
            val path = r.path.orEmpty()
            assertFalse("legacy REST request: $path", path.startsWith("/api/v2.0"))
            assertNull("credentials sent with $path", r.getHeader("Authorization"))
            assertFalse("token in query of $path", path.contains("auth_token"))
        }
    }

    @Before fun setUp() { server = MockWebServer().apply { start() } }

    @After fun tearDown() { server.shutdown() }

    @Test fun apiKeyOnServerWithoutWebSocketApiFailsClearlyAndNeverTriesRest() = runBlocking {
        serve { MockResponse().setResponseCode(404) } // TrueNAS 24.10 and older: no /api/current; REST would answer
        try {
            TrueNasConnector.connect(config(), "1-secretkey", Keepalive.NONE)
            fail("expected an error")
        } catch (e: TrueNasException.Unsupported) {
            assertEquals(WEBSOCKET_API_REQUIRED_MESSAGE, e.message)
            assertTrue(e.message!!.contains("25.04"))
        }
        assertEquals(listOf("/api/current"), requests.map { it.path })
        assertNoRestAuth()
    }

    @Test fun serverErrorDuringHandshakeIsTemporaryAndNeverFallsBackToRest() = runBlocking {
        serve { MockResponse().setResponseCode(502) } // nginx while middlewared restarts
        try {
            TrueNasConnector.connect(config(), "1-secretkey", Keepalive.NONE)
            fail("expected an error")
        } catch (e: TrueNasException.Unreachable) {
            assertTrue(e.message!!.contains("502"))
        }
        assertTrue(requests.all { it.path == "/api/current" })
        assertNoRestAuth()
    }

    @Test fun passwordSignInOnServerWithoutWebSocketApiNeverTriesRest() = runBlocking {
        serve { MockResponse().setResponseCode(404) }
        try {
            WebSocketAuth.login(config(AuthMethod.PASSWORD), Credentials.Password("admin", "pw"), 3600, Keepalive.NONE)
            fail("expected an error")
        } catch (e: TrueNasException.Unsupported) {
            assertEquals(WEBSOCKET_API_REQUIRED_MESSAGE, e.message)
        }
        assertNoRestAuth()
    }

    @Test fun tokenSignInNeverTriesRest() = runBlocking {
        serve { MockResponse().setResponseCode(400) }
        try {
            WebSocketAuth.tokenConnection(config(AuthMethod.PASSWORD), "session-token", Keepalive.NONE)
            fail("expected an error")
        } catch (e: TrueNasException) {
            // any error is fine; what matters is what went over the wire
        }
        assertNoRestAuth()
    }

    @Test fun unauthenticatedProbesCarryNoCredentials() = runBlocking {
        serve { r ->
            when (r.path) {
                "/api/boot_id" -> MockResponse().setBody("\"boot-1\"")
                "/api/versions" -> MockResponse().setBody("[\"v25.04.2\",\"v25.10.2\"]")
                else -> MockResponse().setBody("<title>TrueNAS</title>")
            }
        }
        assertEquals(true, LocalCheck.check(config(route = Route.LOCAL), config()))
        assertTrue(RouteResolver.probeLocal(config(route = Route.LOCAL)))
        val found = LocalDetector.detect(server.hostName, LocalDetector.detectionClient(), listOf(LocalDetector.Candidate("http", server.port)))
        assertTrue(found != null)
        assertTrue(requests.isNotEmpty())
        assertTrue(requests.all { it.path in setOf("/api/boot_id", "/api/versions", "/") })
        assertNoRestAuth()
    }

    /** Guards against a REST client creeping back in: no source talks to `/api/v2.0` or sends credentials over HTTP. */
    @Test fun noRestClientInSources() {
        val root = listOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }
        val sources = root.walkTopDown().filter { it.extension == "kt" }.toList()
        assertTrue(sources.size > 50)
        assertFalse(sources.any { it.name == "RestTrueNasApi.kt" })
        for (f in sources) {
            val code = f.readLines().filterNot { it.trimStart().startsWith("*") || it.trimStart().startsWith("//") }.joinToString("\n")
            assertFalse("${f.name} talks to /api/v2.0", code.contains("/api/v2.0") || code.contains("api/v2"))
            assertFalse("${f.name} sends a Bearer token", code.contains("Bearer "))
            assertFalse("${f.name} sends auth_token", code.contains("auth_token"))
            // wg-easy (a separate app on its own port, not the TrueNAS middleware) is the only HTTP API with credentials.
            // 1.3.0: the file browser's upload goes to TrueNAS's file application (`/_upload`, audited as "REST", not
            // LEGACY_REST) with a single-use `Token` from auth.generate_token; nothing else, no Basic/Bearer.
            if (f.name == "FileTransfers.kt") {
                assertTrue(code.contains("\"Token \$token\""))
                assertTrue(code.contains("/_upload"))
                assertFalse(code.contains("Basic "))
                assertFalse(code.contains("/api/"))
            } else if (f.name != "WgEasyClient.kt") {
                assertFalse("${f.name} sets an Authorization header", code.contains("\"Authorization\""))
            }
        }
        assertTrue(sources.any { it.name == "FileTransfers.kt" })
    }
}
