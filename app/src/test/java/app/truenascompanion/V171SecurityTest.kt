package app.truenascompanion

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import app.truenascompanion.data.api.DatasetDeleteRemote
import app.truenascompanion.data.api.RequestTracker
import app.truenascompanion.data.api.StorageApi
import app.truenascompanion.data.api.TrueNasException
import app.truenascompanion.data.model.AuthMethod
import app.truenascompanion.data.model.Dataset
import app.truenascompanion.data.model.Route
import app.truenascompanion.data.model.RouteMode
import app.truenascompanion.data.model.ServerConfig
import app.truenascompanion.data.net.CertificateInfo
import app.truenascompanion.data.net.HttpClients
import app.truenascompanion.data.net.HttpsUpgrade
import app.truenascompanion.data.net.HttpsUpgradeManager
import app.truenascompanion.data.net.NetState
import app.truenascompanion.data.net.RouteOptions
import app.truenascompanion.data.net.RouteResolver
import app.truenascompanion.data.vpn.isTailscaleAddress
import app.truenascompanion.notify.AlertLevel
import app.truenascompanion.notify.AlertNotifier
import app.truenascompanion.notify.DeepLink
import app.truenascompanion.notify.DeepLinkGuard
import app.truenascompanion.ui.connection.ConnectionOverlayDecision
import app.truenascompanion.ui.storage.DatasetDeleteImpact
import app.truenascompanion.ui.theme.DarkColors
import app.truenascompanion.ui.theme.LightColors
import app.truenascompanion.util.Format
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

/** 1.7.1: security and correctness fixes from the expert review (pure logic, no device). */
class V171SecurityTest {

    private val base = ServerConfig(
        id = "s1", name = "Home NAS", url = "https://nas.example.com", username = "admin", authMethod = AuthMethod.PASSWORD,
    )

    // --- C-1 / H-2: HTTPS only ---

    @Test fun httpLocalAndTailscaleAddressesAreNeverUsable() {
        val s = base.copy(localUrl = "http://192.168.1.50", tailscaleUrl = "http://100.100.1.2")
        assertFalse(s.localUsable)
        assertFalse(s.tailscaleUsable)
        assertEquals(listOf(Route.LOCAL, Route.TAILSCALE), s.insecureAddresses)
        val ok = base.copy(localUrl = "https://192.168.1.50", tailscaleUrl = "https://100.100.1.2")
        assertTrue(ok.localUsable)
        assertTrue(ok.tailscaleUsable)
        assertTrue(ok.insecureAddresses.isEmpty())
        assertEquals(listOf(Route.REMOTE), base.copy(url = "http://nas.example.com").insecureAddresses)
    }

    @Test fun clientRefusesHttpAddresses() {
        val e = runCatching { HttpClients.create(base.copy(url = "http://192.168.1.50")) }.exceptionOrNull()
        assertTrue(e is TrueNasException.InsecureAddress)
    }

    @Test fun httpsOnlyInterceptorRejectsCleartext() {
        val client = okhttp3.OkHttpClient.Builder().addNetworkInterceptor(app.truenascompanion.data.net.HttpsOnlyInterceptor()).build()
        val server = okhttp3.mockwebserver.MockWebServer()
        server.enqueue(okhttp3.mockwebserver.MockResponse().setBody("x"))
        server.start()
        try {
            val e = runCatching { client.newCall(okhttp3.Request.Builder().url(server.url("/")).build()).execute() }.exceptionOrNull()
            assertTrue(e is java.io.IOException)
            assertTrue(e!!.message!!.contains("cleartext"))
        } finally { server.shutdown() }
    }

    @Test fun pinIsExclusiveOffRemoteAndAdditiveOnRemote() {
        val pinned = base.copy(pinnedCertSha256 = "AA:BB")
        assertFalse(HttpClients.requirePin(pinned.copy(activeRoute = Route.REMOTE)))
        assertTrue(HttpClients.requirePin(pinned.copy(activeRoute = Route.LOCAL)))
        assertTrue(HttpClients.requirePin(pinned.copy(activeRoute = Route.TAILSCALE)))
        assertFalse(HttpClients.requirePin(base.copy(activeRoute = Route.LOCAL)))
        assertTrue(HttpClients.requirePin(base.copy(certReviewRequired = true)))
    }

    @Test fun tailscaleAddressRanges() {
        assertTrue(isTailscaleAddress(InetAddress.getByName("100.64.0.1")))
        assertTrue(isTailscaleAddress(InetAddress.getByName("100.127.255.254")))
        assertFalse(isTailscaleAddress(InetAddress.getByName("100.128.0.1")))
        assertFalse(isTailscaleAddress(InetAddress.getByName("10.8.0.2")))
        assertTrue(isTailscaleAddress(InetAddress.getByName("fd7a:115c:a1e0::1")))
        assertFalse(isTailscaleAddress(InetAddress.getByName("fd00::1")))
    }

    @Test fun otherVpnIsNotTreatedAsTailscale() = runBlocking {
        val s = base.copy(tailscaleUrl = "https://100.100.1.2")
        var probed = false
        val r = RouteResolver.decide(
            RouteOptions.of(s), NetState.Kind.VPN, foreignVpn = true, skip = emptySet(),
            probeLocal = { false }, probeTailscale = { probed = true; true }, tailscaleVpn = false,
        )
        assertEquals(Route.REMOTE, r)
        assertFalse(probed)
        val t = RouteResolver.decide(
            RouteOptions.of(s), NetState.Kind.VPN, foreignVpn = true, skip = emptySet(),
            probeLocal = { false }, probeTailscale = { true }, tailscaleVpn = true,
        )
        assertEquals(Route.TAILSCALE, t)
    }

    // --- Migration of http addresses ---

    private val cert = CertificateInfo("AB:CD", "CN=truenas", "CN=truenas", "2026-01-01", "2027-01-01", selfSigned = true)

    @Test fun upgradeFindsSelfSignedHttpsAndAsksOnce() = runBlocking {
        val s = base.copy(localUrl = "http://192.168.1.50")
        val o = HttpsUpgrade.probe(s, Route.LOCAL, detect = { "https://$it" }, check = { c, _ ->
            if (c.pinnedCertSha256 == null) throw TrueNasException.UntrustedCertificate(cert, null) else true
        })
        o as HttpsUpgrade.Outcome.NeedsTrust
        assertEquals("https://192.168.1.50", o.url)
        assertEquals(true, o.sameNas)
        val applied = HttpsUpgrade.apply(s, Route.LOCAL, o.url, o.cert.sha256)
        assertEquals("https://192.168.1.50", applied.localUrl)
        assertEquals("AB:CD", applied.localPinnedCertSha256)
        assertTrue(applied.localUsable)
    }

    @Test fun upgradeWithoutHttpsOrOtherMachineLeavesAddressOff() = runBlocking {
        val s = base.copy(localUrl = "http://192.168.1.50")
        assertTrue(HttpsUpgrade.probe(s, Route.LOCAL, detect = { null }, check = { _, _ -> true }) is HttpsUpgrade.Outcome.NotAvailable)
        val other = HttpsUpgrade.probe(s, Route.LOCAL, detect = { "https://$it" }, check = { _, _ -> false })
        assertTrue((other as HttpsUpgrade.Outcome.NotAvailable).differentNas)
        assertNull(HttpsUpgrade.probe(base, Route.REMOTE, detect = { "x" }, check = { _, _ -> true }))
    }

    @Test fun managerAppliesCaTrustedQueuesSelfSignedAndRunsOnce() = runBlocking {
        var saved = listOf(
            base.copy(id = "a", localUrl = "http://192.168.1.50"),
            base.copy(id = "b", localUrl = "http://192.168.1.60"),
        )
        val changed = mutableListOf<String>()
        var probes = 0
        val m = HttpsUpgradeManager(
            servers = { saved },
            update = { id, f -> saved = saved.map { if (it.id == id) f(it) else it } },
            onChanged = { id, _ -> changed += id },
            probe = { s, r ->
                probes++
                if (s.id == "a") HttpsUpgrade.Outcome.Upgraded(r, "https://192.168.1.50")
                else HttpsUpgrade.Outcome.NeedsTrust(r, s.localUrl!!, "https://192.168.1.60", cert, true)
            },
        )
        m.runAutomatic()
        assertEquals("https://192.168.1.50", saved[0].localUrl)
        assertEquals(listOf("a"), changed)
        assertEquals(1, m.requests.value.size)
        assertTrue(saved.all { it.httpsUpgradeTried })
        m.trust(m.requests.value.first())
        assertEquals("AB:CD", saved[1].localPinnedCertSha256)
        assertTrue(m.requests.value.isEmpty())
        m.runAutomatic()
        assertEquals(2, probes)
    }

    @Test fun bannerExplainsWhatStillWorks() {
        val local = HttpsUpgradeManager.bannerText(base.copy(localUrl = "http://192.168.1.50"))!!
        assertTrue(local.contains("home address"))
        assertTrue(local.contains("nas.example.com"))
        val remote = HttpsUpgradeManager.bannerText(base.copy(url = "http://nas.example.com"))!!
        assertTrue(remote.contains("can't sign in"))
        assertNull(HttpsUpgradeManager.bannerText(base))
    }

    // --- M-2: deep links ---

    @Test fun unsignedIntentsCannotSwitchServerOrCarryArguments() {
        assertNull(DeepLinkGuard.decide(DeepLink.DEST_DASHBOARD, "s2", null, signed = false)?.serverId)
        assertNull(DeepLinkGuard.decide(DeepLink.DEST_DASHBOARD, null, "x", signed = false)?.arg)
        assertNotNull(DeepLinkGuard.decide(DeepLink.DEST_ALERTS, null, null, signed = false))
        assertNull(DeepLinkGuard.decide("app_restart_confirm", "s2", "x", signed = false))
        val signed = DeepLinkGuard.decide("anything", "s2", "arg", signed = true)!!
        assertEquals("s2", signed.serverId)
        assertNull(DeepLinkGuard.decide("x".repeat(65), null, null, signed = true))
    }

    @Test fun macCoversEveryField() {
        val k = ByteArray(32) { it.toByte() }
        val m = DeepLinkGuard.mac(k, "alerts", "s1", null)
        assertEquals(m, DeepLinkGuard.mac(k, "alerts", "s1", null))
        assertFalse(m == DeepLinkGuard.mac(k, "alerts", "s2", null))
        assertFalse(m == DeepLinkGuard.mac(k, "alerts", "s1", "a"))
        assertFalse(m == DeepLinkGuard.mac(ByteArray(32), "alerts", "s1", null))
    }

    // --- M-3: lock-screen notifications ---

    @Test fun publicAlertTitleHidesDetails() {
        assertEquals("TrueNAS: 1 critical alert", AlertNotifier.publicAlertTitle(AlertLevel.CRITICAL, 1))
        assertEquals("TrueNAS: 3 warning alerts", AlertNotifier.publicAlertTitle(AlertLevel.WARNING, 3))
    }

    // --- Code P0: no blind retries of writes ---

    @Test fun requestTrackerOnlyTreatsReadsAsRetryable() {
        listOf("pool.query", "system.info", "app.query", "core.ping", "disk.get_used")
            .forEach { assertTrue(it, RequestTracker.isRead(it)) }
        listOf("pool.dataset.delete", "service.stop", "app.redeploy", "vm.start", "some.unknown_method")
            .forEach { assertFalse(it, RequestTracker.isRead(it)) }
        val t = RequestTracker()
        t.onSend("pool.query"); assertFalse(t.writeSent)
        t.onSend("pool.dataset.delete"); assertTrue(t.writeSent)
    }

    // --- RTL / contrast ---

    @Test fun valuesWithUnitsAreBidiIsolated() {
        val b = Format.bytes(1_536L * 1024 * 1024)
        assertTrue(b.startsWith("\u2066") && b.endsWith("\u2069"))
        assertEquals(b.removePrefix("\u2066").removeSuffix("\u2069"), Format.plain(b))
    }

    private fun contrast(a: Color, b: Color): Double {
        val l1 = a.luminance() + 0.05; val l2 = b.luminance() + 0.05
        return maxOf(l1, l2) / minOf(l1, l2).toDouble()
    }

    @Test fun filledButtonTextMeetsAaInBothThemes() {
        assertTrue(contrast(DarkColors.primary, DarkColors.onPrimary) >= 4.5)
        assertTrue(contrast(LightColors.primary, LightColors.onPrimary) >= 4.5)
        assertTrue(contrast(DarkColors.error, DarkColors.onError) >= 4.5)
    }

    // --- Overlay ---

    @Test fun insecureAddressGetsFriendlyFailureText() {
        val t = ConnectionOverlayDecision.friendlyDetail(
            app.truenascompanion.data.repository.ConnectionState.Failed("x", TrueNasException.InsecureAddress("http://192.168.1.50"))
        )
        assertTrue(t.contains("https://"))
    }

    // --- UX P0-4: dataset delete ---

    private fun ds(id: String, used: Long? = 1L shl 30) = Dataset(id, "tank", "FILESYSTEM", used, 1L shl 40, false, false, "/mnt/$id")

    @Test fun deleteImpactCountsChildrenAndShares() {
        val all = listOf(ds("tank/media"), ds("tank/media/movies"), ds("tank/media/tv"), ds("tank/mediaold"))
        val i = DatasetDeleteImpact.local(all[0], all, listOf("/mnt/tank/media", "/mnt/tank/media/tv", "/mnt/tank/mediaold"))
        assertEquals(2, i.children)
        assertEquals(listOf("/mnt/tank/media", "/mnt/tank/media/tv"), i.shares)
        assertTrue(i.needsRecursive)
    }

    @Test fun deleteNeedsExactNameAndRecursiveForChildren() {
        val d = ds("tank/media")
        val withKids = DatasetDeleteImpact(1, children = 2, shares = emptyList())
        val leaf = DatasetDeleteImpact(1, children = 0, shares = emptyList())
        assertFalse(DatasetDeleteImpact.canDelete(d, "", false, leaf))
        assertFalse(DatasetDeleteImpact.canDelete(d, "Media", false, leaf))
        assertTrue(DatasetDeleteImpact.canDelete(d, "media", false, leaf))
        assertFalse(DatasetDeleteImpact.canDelete(d, "media", false, withKids))
        assertTrue(DatasetDeleteImpact.canDelete(d, "media ", true, withKids))
    }

    @Test fun attachmentsParsed() {
        val e: JsonElement = Json.parseToJsonElement(
            """[{"type":"SMB Share","service":"cifs","attachments":["media","photos"]},{"type":"Apps","service":null,"attachments":[]}]"""
        )
        assertEquals(listOf("SMB Share: media, photos", "Apps"), StorageApi.parseAttachments(e))
        assertEquals(DatasetDeleteRemote(null, null), DatasetDeleteRemote(null, null))
    }
}
