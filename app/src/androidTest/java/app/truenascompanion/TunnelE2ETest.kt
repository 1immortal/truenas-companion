package app.truenascompanion

import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.truenascompanion.data.model.AuthMethod
import app.truenascompanion.data.model.Route
import app.truenascompanion.data.model.ServerConfig
import app.truenascompanion.data.model.VpnMode
import app.truenascompanion.data.net.RouteResolver
import app.truenascompanion.data.vpn.TunnelFailure
import app.truenascompanion.data.vpn.TunnelHolder
import app.truenascompanion.data.vpn.TunnelManager
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/**
 * End-to-end check of the built-in tunnel on an emulator against a real WireGuard peer (see docs/TECHNICAL.md,
 * "Testing the tunnel"). The peer's "NAS" HTTP server answers with the caller's source address, which proves whether a
 * request went through the tunnel (source = tunnel address) or directly (source = anything else).
 *
 * Arguments (instrumentation `-e`): `wgConf` (base64 client config), `nasIp`, `otherIp`, `port`, `tunnelIp`,
 * `badConf` (base64 config whose endpoint doesn't answer).
 */
@RunWith(AndroidJUnit4::class)
class TunnelE2ETest {
    private val args = InstrumentationRegistry.getArguments()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private fun arg(k: String) = args.getString(k)
    private fun conf(k: String) = arg(k)?.let { String(Base64.decode(it, Base64.DEFAULT)) }

    private val http = OkHttpClient.Builder().connectTimeout(4, TimeUnit.SECONDS).readTimeout(4, TimeUnit.SECONDS).retryOnConnectionFailure(false).build()
    private fun whoAmI(ip: String): String? {
        http.connectionPool.evictAll()
        return runCatching {
            http.newCall(Request.Builder().url("http://$ip:${arg("port")}/whoami").header("Connection", "close").build()).execute().use { it.body.string().trim() }
        }.onFailure { android.util.Log.w("TunnelE2E", "whoami $ip failed", it) }.getOrNull()
    }

    private fun server(nasIp: String) = ServerConfig(
        id = "e2e", name = "homenas", url = "https://remote.invalid", authMethod = AuthMethod.PASSWORD, username = "u",
        localUrl = "http://$nasIp:${arg("port")}", vpnMode = VpnMode.AUTO, wireGuardConfigured = true,
    )

    private val managers = mutableListOf<TunnelManager>()
    private fun manager(conf: String?) = TunnelManager(context) { conf }.also { managers += it }

    @After fun stopTunnels() = runBlocking { managers.forEach { it.stop() }; Thread.sleep(1000) }

    @Test fun splitTunnelCarriesOnlyTheNasAddress() = runBlocking {
        val wg = conf("wgConf"); val nas = arg("nasIp"); val other = arg("otherIp"); val tunnelIp = arg("tunnelIp")
        assumeTrue("needs -e wgConf/nasIp/otherIp/tunnelIp", wg != null && nas != null && other != null && tunnelIp != null)
        val tunnels = manager(wg)
        assertFalse("VPN consent must be pre-granted (appops ACTIVATE_VPN allow)", tunnels.needsConsent())
        assertFalse("before: direct", whoAmI(nas!!) == tunnelIp)

        val failure = tunnels.acquire(server(nas), TunnelHolder.APP)
        assertNull("tunnel should come up: $failure", failure)
        assertTrue(tunnels.isUpFor("e2e"))
        assertTrue("handshake time recorded", tunnels.status.value.lastHandshakeMs > 0)
        assertEquals("NAS request goes through the tunnel", tunnelIp, whoAmI(nas))
        val direct = whoAmI(other!!)
        assertTrue("other destinations are not routed through the tunnel (got $direct)", direct != null && direct != tunnelIp)

        tunnels.release("e2e", TunnelHolder.APP)
        assertFalse(tunnels.isUpFor("e2e"))
        Thread.sleep(1500)
        assertFalse("after release: direct again", whoAmI(nas) == tunnelIp)
    }

    @Test fun resolverPicksTheTunnelWhenLocalDoesNotAnswerAndRefCounts() = runBlocking {
        val wg = conf("wgConf"); val tunnelIp = arg("tunnelIp")
        assumeTrue(wg != null && tunnelIp != null && arg("nasIp") != null)
        val tunnels = manager(wg)
        // Local probe fails (as on mobile data), no Tailscale: Auto picks the built-in tunnel.
        val resolver = RouteResolver(null, tunnels) { false }
        val s = server(arg("nasIp")!!)
        val target = resolver.acquire(s, TunnelHolder.CHECK)
        assertEquals("status ${tunnels.status.value}", Route.VPN, target.activeRoute)
        assertEquals(s.localUrl, target.url)
        assertEquals(tunnelIp, whoAmI(arg("nasIp")!!))
        // A second holder joins; the tunnel stays up until both released it.
        assertNull(tunnels.acquire(s, TunnelHolder.INSTANT))
        resolver.release(target, TunnelHolder.CHECK)
        assertTrue(tunnels.isUpFor("e2e"))
        tunnels.release("e2e", TunnelHolder.INSTANT)
        assertFalse(tunnels.isUpFor("e2e"))
    }

    @Test fun deadEndpointFailsCleanlyAndFallsBackToRemote() = runBlocking {
        val bad = conf("badConf")
        assumeTrue(bad != null && arg("nasIp") != null)
        val tunnels = manager(bad)
        val resolver = RouteResolver(null, tunnels) { false }
        val started = System.currentTimeMillis()
        val target = resolver.acquire(server(arg("nasIp")!!), TunnelHolder.APP)
        assertEquals("no handshake -> next route; ${tunnels.status.value}", Route.REMOTE, target.activeRoute)
        assertTrue("${tunnels.status.value}", tunnels.status.value.lastFailure is TunnelFailure.NoHandshake)
        assertFalse(tunnels.isUpFor("e2e"))
        val took = System.currentTimeMillis() - started
        android.util.Log.i("TunnelE2E", "dead endpoint gave up after $took ms")
        // 8 s handshake wait plus bring-up/tear-down (a few seconds on a software-emulated x86 emulator).
        assertTrue("gives up in time (took $took ms)", took < 20_000)
    }
}
