package app.truenascompanion

import app.truenascompanion.data.api.TrueNasApi
import app.truenascompanion.data.api.TrueNasException
import app.truenascompanion.data.model.AuthMethod
import app.truenascompanion.data.model.Route
import app.truenascompanion.data.model.RouteMode
import app.truenascompanion.data.model.ServerConfig
import app.truenascompanion.data.model.VpnMode
import app.truenascompanion.data.net.NetState
import app.truenascompanion.data.net.RouteOptions
import app.truenascompanion.data.net.RouteResolver
import app.truenascompanion.data.vpn.VpnSetup
import app.truenascompanion.data.vpn.WgConf
import app.truenascompanion.util.UrlUtils
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.lang.reflect.Proxy

class VpnTest {

    // Keys from the WireGuard documentation examples (not real secrets).
    private val wgEasyConf = """
        [Interface]
        PrivateKey = yAnz5TF+lXXJte14tji3zlMNq+hd2rYUIgJBgB3fBmk=
        Address = 10.8.0.2/24, fdcc:ad94:bacf:61a4::cafe:2/112
        DNS = 1.1.1.1, 2606:4700:4700::1111
        MTU = 1420

        [Peer]
        PublicKey = xTIBA5rboUvnH4htodjb6e697QjLERt1NAB4mZqp8Dg=
        PresharedKey = HIgo9xNzJMWLKASShiTqIybxZ0U3wGLiUeJ1PKf8ykw=
        AllowedIPs = 0.0.0.0/0, ::/0
        PersistentKeepalive = 0
        Endpoint = homenas.example.org:51820
    """.trimIndent()

    // --- config parsing / split tunnel ---

    @Test fun validatesWgEasyConfig() {
        val s = WgConf.validate(wgEasyConf)
        assertEquals("homenas.example.org:51820", s.endpoint)
        assertEquals(listOf("10.8.0.2/24", "fdcc:ad94:bacf:61a4::cafe:2/112"), s.addresses)
        assertTrue(s.fullTunnel)
        assertEquals(2, s.dns.size)
        assertTrue(WgConf.looksLikeConfig(wgEasyConf))
    }

    @Test fun rejectsBrokenConfigsWithReadableMessages() {
        fun msg(text: String) = try { WgConf.validate(text); null } catch (e: WgConf.Invalid) { e.message }
        assertTrue(msg("hello")!!.contains("doesn't look like"))
        assertTrue(msg(wgEasyConf.replace(Regex("PrivateKey = .*"), "PrivateKey = nope"))!!.contains("PrivateKey"))
        assertTrue(msg(wgEasyConf.replace(Regex("Endpoint = .*"), ""))!!.contains("Endpoint"))
        assertTrue(msg(wgEasyConf.replace(":51820", ""))!!.contains("port"))
        assertTrue(msg(wgEasyConf.substringBefore("[Peer]"))!!.contains("[Peer]"))
        assertTrue(msg(wgEasyConf + "\n[Foo]\nA = b")!!.contains("[Foo]"))
    }

    @Test fun forAppRoutesOnlyTheNasAndOnlyThisApp() {
        val out = WgConf.forApp(wgEasyConf, "192.168.1.10", "app.truenascompanion")
        assertTrue(out.contains("IncludedApplications = app.truenascompanion"))
        assertTrue(out.contains("AllowedIPs = 192.168.1.10/32"))
        assertFalse("full tunnel removed", out.contains("0.0.0.0/0"))
        assertFalse("DNS dropped (the app connects by IP)", out.contains("DNS"))
        assertTrue(out.contains("PresharedKey = HIgo9xNzJMWLKASShiTqIybxZ0U3wGLiUeJ1PKf8ykw="))
        assertTrue(out.contains("Endpoint = homenas.example.org:51820"))
        assertTrue(out.contains("MTU = 1420"))
        // The result is itself a valid config.
        assertEquals(listOf("192.168.1.10/32"), WgConf.validate(out).allowedIps)
    }

    @Test fun forAppRefusesWhenTheTunnelDoesNotReachTheNas() {
        val narrow = wgEasyConf.replace("AllowedIPs = 0.0.0.0/0, ::/0", "AllowedIPs = 10.8.0.0/24")
        try {
            WgConf.forApp(narrow, "192.168.1.10", "x"); fail()
        } catch (e: WgConf.Invalid) {
            assertTrue(e.message!!.contains("192.168.1.10"))
        }
        val subnet = wgEasyConf.replace("AllowedIPs = 0.0.0.0/0, ::/0", "AllowedIPs = 10.8.0.0/24, 192.168.1.0/24")
        assertTrue(WgConf.forApp(subnet, "192.168.1.10", "x").contains("AllowedIPs = 192.168.1.10/32"))
    }

    @Test fun cidrHelpers() {
        assertTrue(WgConf.covers("0.0.0.0/0", "192.168.1.10"))
        assertTrue(WgConf.covers("192.168.1.0/24", "192.168.1.255"))
        assertFalse(WgConf.covers("192.168.1.0/24", "192.168.2.1"))
        assertTrue(WgConf.covers("192.168.1.10/32", "192.168.1.10"))
        assertFalse(WgConf.covers("::/0", "192.168.1.10"))
        assertFalse("no DNS lookups", WgConf.covers("nas.example.org/32", "192.168.1.10"))
        assertEquals("192.168.1.0/24", WgConf.networkOf("192.168.1.10/24"))
        assertEquals("10.20.0.0/16", WgConf.networkOf("10.20.30.40/16"))
        assertEquals(51820, WgConf.endpointPort("homenas.example.org:51820"))
        assertEquals(51820, WgConf.endpointPort("[2001:db8::1]:51820"))
        assertNull(WgConf.endpointPort("homenas.example.org"))
    }

    // --- route order ---

    private val all = RouteOptions(RouteMode.AUTO, localUsable = true, tailscaleUsable = true, vpnMode = VpnMode.AUTO, wireGuardUsable = true)

    private fun decide(
        o: RouteOptions = all, net: NetState.Kind? = NetState.Kind.LAN, foreign: Boolean = false, skip: Set<Route> = emptySet(),
        local: Boolean = false, ts: Boolean = false,
    ) = runBlocking { RouteResolver.decide(o, net, foreign, skip, { local }, { ts }) }

    @Test fun autoOrderIsLocalTailscaleWireGuardRemote() {
        assertEquals(Route.LOCAL, decide(local = true, ts = true))
        // Away from home with Tailscale connected (a foreign VPN is the default network).
        assertEquals(Route.TAILSCALE, decide(net = NetState.Kind.VPN, foreign = true, ts = true))
        // Tailscale configured but its VPN isn't connected: the built-in tunnel.
        assertEquals(Route.VPN, decide(net = NetState.Kind.MOBILE, ts = true))
        assertEquals(Route.REMOTE, decide(o = all.copy(wireGuardUsable = false), net = NetState.Kind.MOBILE))
    }

    @Test fun anotherVpnBlocksTheBuiltInTunnel() {
        // Starting ours would disconnect the other VPN: fall back to remote instead.
        assertEquals(Route.REMOTE, decide(net = NetState.Kind.VPN, foreign = true, ts = false))
        assertEquals(Route.REMOTE, decide(o = all.copy(vpnMode = VpnMode.ALWAYS), net = NetState.Kind.VPN, foreign = true))
    }

    @Test fun alwaysOnTunnelComesFirstAndOffNeverUsesIt() {
        assertEquals(Route.VPN, decide(o = all.copy(vpnMode = VpnMode.ALWAYS), local = true))
        assertEquals(Route.REMOTE, decide(o = all.copy(vpnMode = VpnMode.OFF), net = NetState.Kind.MOBILE))
        assertEquals(Route.LOCAL, decide(o = all.copy(vpnMode = VpnMode.ALWAYS), skip = setOf(Route.VPN), local = true))
    }

    @Test fun failedRoutesAreSkippedAndModesAreRespected() {
        assertEquals(Route.VPN, decide(skip = setOf(Route.LOCAL), local = true))
        assertEquals(Route.REMOTE, decide(net = NetState.Kind.MOBILE, skip = setOf(Route.VPN)))
        assertEquals(Route.REMOTE, decide(o = all.copy(mode = RouteMode.REMOTE), local = true))
        assertEquals(Route.LOCAL, decide(o = all.copy(mode = RouteMode.LOCAL), net = NetState.Kind.MOBILE))
        var probed = false
        runBlocking { RouteResolver.decide(all, NetState.Kind.MOBILE, false, emptySet(), { probed = true; true }, { false }) }
        assertFalse("no local probe on mobile data", probed)
    }

    // --- model ---

    private val server = ServerConfig(
        id = "s1", name = "homenas", url = "https://homenas.example.org", pinnedCertSha256 = "AA",
        authMethod = AuthMethod.API_KEY, localUrl = "https://192.168.1.10", localPinnedCertSha256 = "BB",
        tailscaleUrl = "https://100.101.102.103", vpnMode = VpnMode.AUTO, wireGuardConfigured = true,
    )

    @Test fun tunnelRouteUsesTheLanAddressAndItsPin() {
        val vpn = server.forRoute(Route.VPN)
        assertEquals("https://192.168.1.10", vpn.url)
        assertEquals("BB", vpn.pinnedCertSha256)
        assertEquals(Route.VPN, vpn.activeRoute)
        assertEquals("192.168.1.10", server.lanIp)
        assertTrue(server.wireGuardUsable)
        assertTrue(server.hasAlternativeRoutes)
    }

    @Test fun tailscaleRouteFallsBackToTheLocalPinAndNeverSendsKeysOverHttp() {
        val ts = server.forRoute(Route.TAILSCALE)
        assertEquals("https://100.101.102.103", ts.url)
        assertEquals("BB", ts.pinnedCertSha256)
        assertEquals("CC", server.copy(tailscalePinnedCertSha256 = "CC").forRoute(Route.TAILSCALE).pinnedCertSha256)
        val http = server.copy(tailscaleUrl = "http://100.101.102.103")
        assertFalse(http.tailscaleUsable)
        assertEquals(Route.REMOTE, http.forRoute(Route.TAILSCALE).activeRoute)
        // 1.7.1 (security C-1): an http Tailscale address is off for password sign-in too.
        assertFalse(http.copy(authMethod = AuthMethod.PASSWORD).tailscaleUsable)
    }

    @Test fun wireGuardNeedsAnIpLocalAddress() {
        assertNull(server.copy(localUrl = "https://truenas.lan").lanIp)
        assertFalse(server.copy(localUrl = "https://truenas.lan").wireGuardUsable)
        assertFalse(server.copy(vpnMode = VpnMode.OFF).wireGuardUsable)
        assertEquals("homenas.example.org", UrlUtils.hostOf("https://homenas.example.org:8443/ui/"))
        assertEquals("192.168.1.10", UrlUtils.ipLiteralHost("https://192.168.1.10:444"))
        assertNull(UrlUtils.ipLiteralHost("https://300.1.1.1"))
    }

    // --- setup helpers ---

    @Test fun installValuesMatchTheCatalogQuestions() {
        assertEquals("""{"wg_easy":{"insecure":true}}""", VpnSetup.wgEasyValues().toString())
        assertEquals(
            """{"tailscale":{"auth_key":"tskey-auth-abc","hostname":"truenas","advertise_routes":["192.168.1.0/24"]}}""",
            VpnSetup.tailscaleValues(" tskey-auth-abc ", "truenas", listOf("192.168.1.0/24")).toString(),
        )
        assertEquals("homenas.example.org", VpnSetup.publicHost("https://homenas.example.org/some/path"))
        assertTrue(VpnSetup.validPublicHost("homenas.example.org"))
        assertTrue(VpnSetup.validPublicHost("203.0.113.7"))
        assertFalse(VpnSetup.validPublicHost("https://homenas.example.org"))
        assertTrue(VpnSetup.validHostname("truenas-01"))
        assertFalse(VpnSetup.validHostname("true nas"))
        assertEquals("YTN Pixel 8", VpnSetup.clientName("Pixel 8"))
    }

    @Test fun generatedPasswordsAreLongAndUnambiguous() {
        val pws = (1..50).map { VpnSetup.generatePassword() }
        assertTrue(pws.all { it.length == 20 && it.none { c -> c in "0O1lI" } })
        assertEquals(50, pws.toSet().size)
    }

    @Test fun lanSubnetComesFromTheNasInterfaces() {
        val summary = Json.parseToJsonElement(
            """{"ips":{"enp1s0":{"IPV4":["192.168.1.10/24"],"IPV6":["fe80::1/64"]},"br0":{"IPV4":["10.0.0.2/16"]}}}""",
        ).jsonObject
        assertEquals("192.168.1.0/24", VpnSetup.lanSubnet(summary, "192.168.1.10"))
        assertEquals("10.0.0.0/16", VpnSetup.lanSubnet(summary, "10.0.0.2"))
        assertEquals("172.16.5.0/24", VpnSetup.lanSubnet(null, "172.16.5.9"))
    }

    private fun fakeApi(respond: (String, List<JsonElement>) -> JsonElement): TrueNasApi =
        Proxy.newProxyInstance(TrueNasApi::class.java.classLoader, arrayOf(TrueNasApi::class.java)) { proxy, m, args ->
            when (m.name) {
                "rpc" -> @Suppress("UNCHECKED_CAST") respond(args[0] as String, (args[1] as Array<JsonElement>).toList())
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.get(0)
                "toString" -> "fake"
                else -> null
            }
        } as TrueNasApi

    @Test fun awaitJobReportsProgressAndFailures() = runTest {
        var polls = 0
        val progress = mutableListOf<Int?>()
        val api = fakeApi { method, _ ->
            assertEquals("core.get_jobs", method)
            polls++
            Json.parseToJsonElement(
                if (polls < 3) """[{"id":7,"state":"RUNNING","progress":{"percent":${polls * 30},"description":"Pulling image"}}]"""
                else """[{"id":7,"state":"SUCCESS"}]""",
            )
        }
        VpnSetup.awaitJob(api, 7) { p, _ -> progress += p }
        assertEquals(listOf(30, 60), progress)

        val failing = fakeApi { _, _ -> Json.parseToJsonElement("""[{"id":8,"state":"FAILED","error":"[EFAULT] Failed 'up' action\nTraceback"}]""") }
        try {
            VpnSetup.awaitJob(failing, 8); fail()
        } catch (e: TrueNasException.JobFailed) {
            assertEquals("[EFAULT] Failed 'up' action", e.message)
        }
    }

    @Test fun appExistsQueriesByName() = runBlocking {
        var filter: JsonElement? = null
        val api = fakeApi { m, args -> assertEquals("app.query", m); filter = args[0]; JsonArray(listOf(JsonObject(emptyMap()))) }
        assertTrue(VpnSetup.appExists(api, "wg-easy"))
        assertEquals("""[["name","=","wg-easy"]]""", filter.toString())
    }
}
