package app.truenascompanion

import app.truenascompanion.data.model.AuthMethod
import app.truenascompanion.data.model.Route
import app.truenascompanion.data.model.RouteMode
import app.truenascompanion.data.model.ServerConfig
import app.truenascompanion.data.net.LocalDetector
import app.truenascompanion.data.net.NetState
import app.truenascompanion.data.net.RouteResolver
import app.truenascompanion.data.security.AppLock
import app.truenascompanion.data.security.LockSettings
import app.truenascompanion.data.security.RelockDelay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LockAndRouteTest {

    // --- app lock ---

    private class Clock(var t: Long = 1_000_000L)

    @Test fun coldStartLocksOnlyWhenEnabled() {
        val off = AppLock { 0L }.apply { onSettingsLoaded(LockSettings(enabled = false)) }
        assertFalse(off.locked.value)
        val on = AppLock { 0L }.apply { onSettingsLoaded(LockSettings(enabled = true)) }
        assertTrue(on.locked.value)
    }

    @Test fun relocksAfterDelayOnly() {
        val clock = Clock()
        val lock = AppLock { clock.t }
        lock.onSettingsLoaded(LockSettings(enabled = true, relock = RelockDelay.ONE_MINUTE))
        lock.endAuthentication(success = true)
        assertFalse(lock.locked.value)
        lock.onBackground(); clock.t += 59_000; lock.onForeground()
        assertFalse("under a minute: stays unlocked", lock.locked.value)
        lock.onBackground(); clock.t += 60_000; lock.onForeground()
        assertTrue(lock.locked.value)
    }

    @Test fun immediateRelockAndAuthScreenDoesNotRelock() {
        val clock = Clock()
        val lock = AppLock { clock.t }
        lock.onSettingsLoaded(LockSettings(enabled = true, relock = RelockDelay.IMMEDIATELY))
        // The PIN screen stops our activity while authenticating: that must not count as "left the app".
        lock.beginAuthentication()
        lock.onBackground(); clock.t += 5_000; lock.onForeground()
        lock.endAuthentication(success = true)
        assertFalse(lock.locked.value)
        lock.onBackground(); lock.onForeground()
        assertTrue(lock.locked.value)
    }

    @Test fun enablingFromSettingsDoesNotLockButDisablingUnlocks() {
        val lock = AppLock { 0L }
        lock.onSettingsLoaded(LockSettings(enabled = false))
        lock.onSettingsLoaded(LockSettings(enabled = true))
        assertFalse(lock.locked.value)
        lock.onBackground(); lock.onForeground() // default 1 min not elapsed
        assertFalse(lock.locked.value)
        val locked = AppLock { 0L }.apply { onSettingsLoaded(LockSettings(enabled = true)) }
        locked.onSettingsLoaded(LockSettings(enabled = false))
        assertFalse(locked.locked.value)
    }

    @Test fun dangerousGuardOnlyWithLock() {
        assertFalse(LockSettings(enabled = false, confirmDangerous = true).guardsDangerousActions)
        assertTrue(LockSettings(enabled = true).guardsDangerousActions)
        assertFalse(LockSettings(enabled = true, confirmDangerous = false).guardsDangerousActions)
    }

    // --- routes ---

    private val nas = ServerConfig(
        id = "n", name = "NAS", url = "https://nas.example.org", pinnedCertSha256 = null,
        localUrl = "https://192.168.1.10", localPinnedCertSha256 = "AA:BB",
    )

    @Test fun forRouteSwapsAddressAndPin() {
        val local = nas.forRoute(Route.LOCAL)
        assertEquals("https://192.168.1.10", local.url)
        assertEquals("AA:BB", local.pinnedCertSha256)
        assertEquals(Route.LOCAL, local.activeRoute)
        assertEquals(nas.copy(activeRoute = Route.REMOTE), nas.forRoute(Route.REMOTE))
    }

    @Test fun httpLocalNeverUsed() {
        val http = nas.copy(localUrl = "http://192.168.1.10")
        assertFalse(http.localUsable)
        assertEquals(Route.REMOTE, http.forRoute(Route.LOCAL).activeRoute)
        // 1.7.1 (security C-1): not with a password either; credentials never go over cleartext.
        assertFalse(http.copy(authMethod = AuthMethod.PASSWORD).localUsable)
    }

    @Test fun routingRules() = runBlocking {
        var probes = 0
        val yes: suspend () -> Boolean = { probes++; true }
        val no: suspend () -> Boolean = { probes++; false }
        assertEquals(Route.REMOTE, RouteResolver.decide(RouteMode.AUTO, false, NetState.Kind.LAN, yes))
        assertEquals(Route.REMOTE, RouteResolver.decide(RouteMode.REMOTE, true, NetState.Kind.LAN, yes))
        assertEquals(Route.LOCAL, RouteResolver.decide(RouteMode.LOCAL, true, NetState.Kind.MOBILE, no))
        assertEquals(0, probes)
        assertEquals(Route.REMOTE, RouteResolver.decide(RouteMode.AUTO, true, NetState.Kind.MOBILE, yes))
        assertEquals("no probe on mobile data", 0, probes)
        assertEquals(Route.LOCAL, RouteResolver.decide(RouteMode.AUTO, true, NetState.Kind.LAN, yes))
        assertEquals(Route.LOCAL, RouteResolver.decide(RouteMode.AUTO, true, NetState.Kind.VPN, yes))
        assertEquals(Route.REMOTE, RouteResolver.decide(RouteMode.AUTO, true, NetState.Kind.LAN, no))
        assertEquals(3, probes)
    }

    @Test fun resolverCachesPerNetwork() = runBlocking {
        var probes = 0
        val r = RouteResolver(null) { probes++; true }
        assertEquals(Route.LOCAL, r.route(nas))
        assertEquals(Route.LOCAL, r.route(nas))
        assertEquals(1, probes)
        assertEquals(Route.LOCAL, r.lastRoute.value["n"])
        r.invalidate("n")
        r.route(nas)
        assertEquals(2, probes)
        // Changing the local address is a different decision.
        r.route(nas.copy(localUrl = "https://192.168.1.11"))
        assertEquals(3, probes)
    }

    // --- auto-detect ---

    @Test fun hostParsing() {
        assertEquals("192.168.1.10", LocalDetector.hostOf("192.168.1.10"))
        assertEquals("nas.lan", LocalDetector.hostOf("https://nas.lan:8443/ui/"))
        assertEquals("192.168.1.10", LocalDetector.hostOf(" http://192.168.1.10:81 "))
        assertEquals("[fd00::10]", LocalDetector.hostOf("https://[fd00::10]:444"))
        assertNull(LocalDetector.hostOf("   "))
    }

    @Test fun candidateUrls() {
        assertEquals("https://192.168.1.10", LocalDetector.Candidate("https", 443).url("192.168.1.10"))
        assertEquals("https://192.168.1.10:9443", LocalDetector.Candidate("https", 9443).url("192.168.1.10"))
        assertEquals("http://nas.lan:8080", LocalDetector.Candidate("http", 8080).url("nas.lan"))
    }

    @Test fun recognisesTrueNas() {
        assertTrue(LocalDetector.looksLikeTrueNas("/api/versions", """["v25.04.0","v25.04.2","v25.10.0"]"""))
        assertFalse(LocalDetector.looksLikeTrueNas("/api/versions", """{"error":"not found"}"""))
        assertFalse(LocalDetector.looksLikeTrueNas("/api/versions", "<html>Congratulations! Nginx Proxy Manager</html>"))
        assertTrue(LocalDetector.looksLikeTrueNas("/", "<html><title>TrueNAS - 192.168.1.10</title></html>"))
        assertFalse(LocalDetector.looksLikeTrueNas("/", "<html><title>Nginx Proxy Manager</title> TrueNAS proxy host</html>"))
    }

    @Test fun prefersHttpsThenPortOrder() {
        val c = LocalDetector.CANDIDATES
        assertEquals(LocalDetector.Candidate("https", 444), LocalDetector.pick(listOf(LocalDetector.Candidate("http", 80), LocalDetector.Candidate("https", 444))))
        assertEquals(LocalDetector.Candidate("https", 8443), LocalDetector.pick(listOf(LocalDetector.Candidate("https", 9443), LocalDetector.Candidate("https", 8443))))
        assertNull(LocalDetector.pick(listOf(LocalDetector.Candidate("http", 80))))
        assertNull(LocalDetector.pick(emptyList()))
        // 1.7.1: HTTPS ports only (443, 444, 8443, 9443).
        assertEquals(4, c.size)
        assertTrue(c.all { it.scheme == "https" })
    }

    @Test fun detectsAgainstRealHttp() = runBlocking {
        val truenas = okhttp3.mockwebserver.MockWebServer().apply {
            dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest) = when (request.path) {
                    "/api/versions" -> okhttp3.mockwebserver.MockResponse().setBody("""["v25.10.0"]""")
                    else -> okhttp3.mockwebserver.MockResponse().setResponseCode(404)
                }
            }
            start()
        }
        val npm = okhttp3.mockwebserver.MockWebServer().apply {
            dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest) =
                    okhttp3.mockwebserver.MockResponse().setBody("<html>Congratulations! Nginx Proxy Manager</html>")
            }
            start()
        }
        try {
            val candidates = listOf(LocalDetector.Candidate("http", npm.port), LocalDetector.Candidate("http", truenas.port), LocalDetector.Candidate("http", 1))
            val found = LocalDetector.detect("127.0.0.1", candidates = candidates)
            assertEquals("http://127.0.0.1:${truenas.port}", found?.url)
            assertFalse(found!!.https)
            assertNull(LocalDetector.detect("127.0.0.1", candidates = listOf(LocalDetector.Candidate("http", npm.port))))
        } finally { truenas.shutdown(); npm.shutdown() }
    }
}
