package app.truenascompanion

import app.truenascompanion.data.api.CertificatesApi
import app.truenascompanion.data.api.Parsers
import app.truenascompanion.data.api.TrueNasApi
import app.truenascompanion.data.model.AcmeRequest
import app.truenascompanion.data.model.AlertItem
import app.truenascompanion.data.model.CertImport
import app.truenascompanion.data.model.CertKind
import app.truenascompanion.data.model.CertStatus
import app.truenascompanion.data.model.Health
import app.truenascompanion.data.model.NasCertificate
import app.truenascompanion.data.model.ServerConfig
import app.truenascompanion.data.net.PinningTrustManager
import app.truenascompanion.data.net.sha256Fingerprint
import app.truenascompanion.notify.AlertGrouping
import app.truenascompanion.notify.AlertTarget
import app.truenascompanion.notify.CertCheckState
import app.truenascompanion.notify.CertExpiry
import app.truenascompanion.notify.DeepLink
import app.truenascompanion.notify.Snooze
import app.truenascompanion.quick.QuickAction
import app.truenascompanion.quick.StatusTileText
import app.truenascompanion.ui.certs.CertForms
import app.truenascompanion.ui.certs.CertificatesViewModel
import app.truenascompanion.ui.servers.ServerEditState
import app.truenascompanion.ui.servers.ServerEditViewModel
import app.truenascompanion.widget.WidgetSnapshot
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.lang.reflect.Proxy
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.time.Instant

/** 1.2.0: certificates, quick actions and better alerts (TrueNAS 25.10 middleware shapes, example data only). */
class V120FeaturesTest {
    private val calls = mutableListOf<Pair<String, List<JsonElement>>>()
    private var responder: (String, List<JsonElement>) -> JsonElement = { _, _ -> JsonNull }

    @Suppress("UNCHECKED_CAST")
    private val api = Proxy.newProxyInstance(TrueNasApi::class.java.classLoader, arrayOf(TrueNasApi::class.java)) { proxy, m, args ->
        when (m.name) {
            "rpc" -> {
                val method = args!![0] as String
                val params = (args[1] as Array<JsonElement>).toList()
                calls += method to params
                responder(method, params)
            }
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === args?.get(0)
            "toString" -> "fake"
            else -> null
        }
    } as TrueNasApi

    private fun j(s: String) = Json.parseToJsonElement(s)
    private val day = 86_400_000L
    private val now = Instant.parse("2026-10-06T12:00:00Z").toEpochMilli()

    private fun cert(id: Int, days: Long?, kind: CertKind = CertKind.CERTIFICATE, acme: Boolean = false, renew: Int? = null, expired: Boolean = false, parsed: Boolean = true) =
        NasCertificate(id, "cert$id", kind, "nas.example.com", listOf("DNS:nas.example.com"), "Example CA", false, now - 30 * day,
            days?.let { now + it * day + 3_600_000L }, expired, "EC", 256, "SHA256", null, acme, null, renew, false, parsed, null)

    // ---- Certificates API ----

    @Test fun parsesMiddlewareDates() {
        assertEquals(Instant.parse("2026-10-06T10:00:00Z").toEpochMilli(), CertificatesApi.parseCertDate("Tue Oct  6 10:00:00 2026"))
        assertEquals(Instant.parse("2027-01-15T08:05:09Z").toEpochMilli(), CertificatesApi.parseCertDate("Fri Jan 15 08:05:09 2027"))
        assertNull(CertificatesApi.parseCertDate("soon"))
        assertNull(CertificatesApi.parseCertDate(null))
    }

    @Test fun readsDistinguishedNames() {
        assertEquals("R11", CertificatesApi.rdn("CN=R11,O=Let's Encrypt,C=US", "CN"))
        assertEquals("Example, Inc", CertificatesApi.rdn("CN=Example CA,O=Example\\, Inc,C=US", "O"))
        assertNull(CertificatesApi.rdn("O=Example Org", "CN"))
    }

    @Test fun parsesCertificateWithIssuerFromPem() {
        val o = j("""{"id":3,"name":"nas_example","certificate":${Json.encodeToString(String.serializer(), EXAMPLE_PEM)},"cert_type_CA":false,"cert_type_CSR":false,
            "common":"nas.example.com","san":["DNS:nas.example.com","DNS:files.example.com"],"from":"Tue Oct  6 16:40:16 2026","until":"Fri Oct  3 16:40:16 2036",
            "expired":false,"key_type":"EC","key_length":256,"digest_algorithm":"SHA256","acme":null,"acme_uri":null,"renew_days":null,"parsed":true,
            "fingerprint":"AA:BB","DN":"/CN=nas.example.com/O=Example Org","add_to_trusted_store":false}""").jsonObject
        val c = CertificatesApi.parse(o)!!
        assertEquals(CertKind.CERTIFICATE, c.kind)
        assertEquals("nas.example.com", c.issuer)
        assertTrue(c.selfSigned)
        assertFalse(c.acme)
        assertEquals(listOf("nas.example.com", "files.example.com"), c.domains)
        assertEquals(3649L, c.daysLeft(Instant.parse("2026-10-07T00:00:00Z").toEpochMilli()))
        val csr = CertificatesApi.parse(j("""{"id":4,"name":"x_csr","cert_type_CSR":true,"common":"a.example.com","san":[]}""").jsonObject)!!
        assertEquals(CertKind.CSR, csr.kind)
        assertEquals(listOf("a.example.com"), csr.domains)
        val acme = CertificatesApi.parse(j("""{"id":5,"name":"le","acme":{"id":1},"acme_uri":"https://acme-v02.api.letsencrypt.org/directory","renew_days":10}""").jsonObject)!!
        assertTrue(acme.acme); assertEquals(10, acme.renewDays); assertEquals("ACME", acme.issuer)
    }

    @Test fun listsCertificatesSortedByKind() = runBlocking {
        responder = { m, _ -> if (m == "certificate.query") j("""[{"id":2,"name":"zeta_ca","cert_type_CA":true},{"id":1,"name":"web"},{"id":9,"name":"req","cert_type_CSR":true}]""") else JsonNull }
        val list = CertificatesApi(api).certificates()
        assertEquals(listOf("web", "zeta_ca", "req"), list.map { it.name })
        assertEquals("certificate.query", calls.single().first)
        assertTrue(calls.single().second.isEmpty())
    }

    @Test fun importPayload() {
        val o = CertificatesApi.importJson(CertImport(" web ", "-----BEGIN CERTIFICATE-----\nAA\n-----END CERTIFICATE-----\n", "KEY", passphrase = "", addToTrustedStore = true))
        assertEquals("web", o["name"]!!.jsonPrimitive.content)
        assertEquals("CERTIFICATE_CREATE_IMPORTED", o["create_type"]!!.jsonPrimitive.content)
        assertEquals("KEY", o["privatekey"]!!.jsonPrimitive.content)
        assertNull(o["passphrase"])
        assertTrue(o["add_to_trusted_store"]!!.jsonPrimitive.boolean)
    }

    @Test fun acmeCreatesCsrFirstAndWaitsForJobs() = runBlocking {
        var job = 100L
        responder = { m, p ->
            when (m) {
                "certificate.create" -> JsonPrimitive(++job)
                "core.get_jobs" -> {
                    val id = p[0].toString().substringAfterLast(',').trim(']', ' ').toLong()
                    j(if (id == 101L) """[{"id":101,"state":"SUCCESS","result":{"id":42}}]""" else """[{"id":$id,"state":"SUCCESS","result":{"id":43}}]""")
                }
                else -> JsonNull
            }
        }
        CertificatesApi(api).createAcme(AcmeRequest("web_le", null, "nas.example.com", listOf("*.example.com"),
            "https://acme-v02.api.letsencrypt.org/directory", mapOf("nas.example.com" to 1, "*.example.com" to 2), renewDays = 45))
        val creates = calls.filter { it.first == "certificate.create" }.map { it.second[0].jsonObject }
        assertEquals("CERTIFICATE_CREATE_CSR", creates[0]["create_type"]!!.jsonPrimitive.content)
        assertEquals("web_le_csr", creates[0]["name"]!!.jsonPrimitive.content)
        assertEquals("""["nas.example.com","*.example.com"]""", creates[0]["san"].toString())
        assertEquals("RSA", creates[0]["key_type"]!!.jsonPrimitive.content)
        val acme = creates[1]
        assertEquals("CERTIFICATE_CREATE_ACME", acme["create_type"]!!.jsonPrimitive.content)
        assertEquals(42, acme["csr_id"]!!.jsonPrimitive.int)
        assertTrue(acme["tos"]!!.jsonPrimitive.boolean)
        assertEquals(30, acme["renew_days"]!!.jsonPrimitive.int) // clamped to the middleware's 1..30
        assertEquals("""{"nas.example.com":1,"*.example.com":2}""", acme["dns_mapping"].toString())
    }

    @Test fun renewDaysAndWebUiCertificateCalls() = runBlocking {
        responder = { m, _ -> when (m) {
            "system.general.config" -> j("""{"ui_certificate":{"id":7,"name":"web"}}""")
            "system.general.ui_certificate_choices" -> j("""{"1":"truenas_default","7":"web"}""")
            else -> JsonNull
        } }
        val a = CertificatesApi(api)
        assertEquals(7, a.uiCertificateId())
        assertEquals(mapOf(1 to "truenas_default", 7 to "web"), a.uiCertificateChoices())
        a.setRenewDays(7, 0)
        assertEquals("certificate.update", calls.last().first)
        assertEquals(7, calls.last().second[0].jsonPrimitive.int)
        assertEquals("""{"renew_days":1}""", calls.last().second[1].toString())
        a.setUiCertificate(7)
        assertEquals("system.general.update", calls.last().first)
        assertEquals("""{"ui_certificate":7,"ui_restart_delay":3,"rollback_timeout":600}""", calls.last().second[0].toString())
        a.checkin()
        assertEquals("system.general.checkin", calls.last().first)
    }

    // ---- Status / expiry warnings ----

    @Test fun certificateStatus() {
        assertEquals(CertStatus.OK, cert(1, 40).status(now, 14))
        assertEquals(CertStatus.EXPIRING, cert(1, 14).status(now, 14))
        assertEquals(CertStatus.EXPIRED, cert(1, -2, expired = true).status(now, 14))
        assertEquals(CertStatus.NOT_APPLICABLE, cert(1, 5, kind = CertKind.CSR).status(now, 14))
        assertEquals(-1L, NasCertificate(1, "x", CertKind.CERTIFICATE, null, emptyList(), null, false, null, now - 3_600_000L, true, null, null, null, null, false, null, null, false, true, null).daysLeft(now))
    }

    @Test fun expiryWarningsRespectAcmeRenewal() {
        val certs = listOf(
            cert(1, 10), cert(2, 40), cert(3, -1, expired = true), cert(4, 3, kind = CertKind.CSR),
            cert(5, 12, acme = true, renew = 10), cert(6, 8, acme = true, renew = 10), cert(7, 5, parsed = false),
        )
        val w = CertExpiry.evaluate(certs, 14, now)
        assertEquals(listOf(1, 3, 6), w.map { it.cert.id })
        assertTrue(w.first { it.cert.id == 3 }.expired)
    }

    @Test fun expiryWarningsNotifyOncePerState() {
        val w1 = CertExpiry.evaluate(listOf(cert(1, 10), cert(2, 5)), 14, now)
        val (fresh1, kept1) = CertExpiry.diff(w1, CertCheckState())
        assertEquals(2, fresh1.size)
        val state = CertCheckState(now, kept1 + fresh1.associate { it.cert.id.toString() to it.state })
        // Same warnings again: nothing new. Cert 2 expired in the meantime: notified again. Cert 1 renewed: dropped.
        val w2 = CertExpiry.evaluate(listOf(cert(1, 90), cert(2, -1, expired = true)), 14, now)
        val (fresh2, kept2) = CertExpiry.diff(w2, state)
        assertEquals(listOf(2), fresh2.map { it.cert.id })
        assertEquals(mapOf("2" to "EXPIRING"), kept2)
        assertTrue(CertExpiry.due(CertCheckState(), now))
        assertFalse(CertExpiry.due(CertCheckState(now - 3_600_000L), now))
        assertTrue(CertExpiry.due(CertCheckState(now - CertExpiry.INTERVAL_MS), now))
        assertEquals("Certificate expires tomorrow", CertExpiry.text(CertExpiry.Warning(cert(1, 1), 1, false)).first)
    }

    // ---- Snooze / grouping / deep links ----

    @Test fun snoozePlan() {
        val snoozes = mapOf("a" to now + 1000, "b" to now - 1000, "c" to now - 1000, "gone" to now + 5000)
        val p = Snooze.plan(snoozes, setOf("a", "b", "c"), now) { it != "c" }
        assertEquals(mapOf("a" to now + 1000, "c" to now - 1000), p.next) // c waits for the end of quiet hours
        assertEquals(setOf("b"), p.wake)
        assertTrue(Snooze.isSnoozed(snoozes, "a", now))
        assertFalse(Snooze.isSnoozed(snoozes, "b", now))
        assertEquals(listOf(3_600_000L, 28_800_000L, 86_400_000L, 604_800_000L), Snooze.OPTIONS.map { it.millis })
    }

    @Test fun groupsRepeatedAlertsByClass() {
        val first = AlertGrouping.plan(listOf("u1" to "SMARTTestFailed", "u2" to "VolumeStatus"), emptyList())
        assertEquals(2, first.size)
        assertFalse(first.any { it.grouped })
        val shown = listOf(AlertGrouping.Shown("alert/s/u1", "SMARTTestFailed", listOf("u1")))
        val next = AlertGrouping.plan(listOf("u3" to "SMARTTestFailed", "u4" to null, "u5" to null), shown).associateBy { it.uuids.first() }
        val smart = next.getValue("u1")
        assertTrue(smart.grouped)
        assertEquals(listOf("u1", "u3"), smart.uuids)
        assertEquals(listOf("alert/s/u1"), smart.replaces)
        assertFalse(next.getValue("u4").grouped)
        assertEquals("alert/s/k:SMARTTestFailed", AlertGrouping.groupTag("s", "SMARTTestFailed"))
    }

    @Test fun alertTargetsFromClassAndArgs() {
        assertEquals(AlertTarget.Pool("tank"), AlertTarget.of("VolumeStatus", j("""{"volume":"tank","state":"DEGRADED","status":"x"}""")))
        assertEquals(AlertTarget.Pool("tank"), AlertTarget.of("ScrubPaused", JsonPrimitive("tank")))
        assertEquals(AlertTarget.Pool("tank"), AlertTarget.of("ZpoolCapacityWarning", j("""{"volume":"tank","capacity":85}""")))
        assertEquals(AlertTarget.Alerts, AlertTarget.of("VolumeStatus", null))
        assertEquals(AlertTarget.Disk("sda"), AlertTarget.of("DiskTemperatureTooHot", j("""{"device":"/dev/sda","serial":"S1"}""")))
        assertEquals(AlertTarget.Disk("sdb"), AlertTarget.of("SMARTTestFailed", j("""{"name":"sdb","serial":"S2"}""")))
        assertEquals(AlertTarget.App("nextcloud"), AlertTarget.of("AppUpdate", j("""{"count":1,"plural":"","apps":"nextcloud"}""")))
        assertEquals(AlertTarget.Apps, AlertTarget.of("AppUpdate", j("""{"count":2,"plural":"s","apps":"nextcloud, jellyfin"}""")))
        assertEquals(AlertTarget.Snapshots("tank/media"), AlertTarget.of("SnapshotCount", j("""{"dataset":"tank/media","snapshots":5000,"max":2000}""")))
        assertEquals(AlertTarget.Dataset("tank/home"), AlertTarget.of("QuotaWarning", j("""{"name":"Quota","dataset":"tank/home","used_fraction":0.9}""")))
        assertEquals(AlertTarget.Update, AlertTarget.of("HasUpdate", null))
        assertEquals(AlertTarget.Certificate("web"), AlertTarget.of("CertificateIsExpiringSoon", j("""{"name":"web","days":3}""")))
        assertEquals(AlertTarget.Alerts, AlertTarget.of("NTPHealthCheck", j("{}")))
        // Round trip through notification intent extras.
        listOf(AlertTarget.Pool("tank"), AlertTarget.Disk(null), AlertTarget.App("immich"), AlertTarget.Apps, AlertTarget.Snapshots("tank/a"),
            AlertTarget.Dataset("tank/b"), AlertTarget.Update, AlertTarget.Certificate("web"), AlertTarget.Alerts).forEach {
            assertEquals(it, AlertTarget.decode(it.destination, it.arg))
        }
    }

    @Test fun alertParserKeepsArgs() {
        val a = Parsers.alert(j("""{"uuid":"u1","level":"CRITICAL","klass":"VolumeStatus","args":{"volume":"tank"},"formatted":"Pool tank is DEGRADED"}""").jsonObject)
        assertEquals(AlertTarget.Pool("tank"), AlertTarget.of(a))
        assertNull(Parsers.alert(j("""{"uuid":"u2","args":null}""").jsonObject).args)
    }

    // ---- Certificate review after a web UI certificate change ----

    private fun pem(s: String) = CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(s.toByteArray())) as X509Certificate

    @Test fun requirePinRejectsEvenUnpinnedChainsUntilReviewed() {
        val x = pem(EXAMPLE_PEM)
        val strict = PinningTrustManager(null, requirePin = true)
        assertTrue(runCatching { strict.checkServerTrusted(arrayOf(x), "ECDHE_ECDSA") }.exceptionOrNull() is CertificateException)
        assertEquals(x, strict.lastChain?.first()) // shown in the trust dialog
        PinningTrustManager(x.sha256Fingerprint(), requirePin = true).checkServerTrusted(arrayOf(x), "ECDHE_ECDSA") // trusted after review
    }

    @Test fun webUiCertificateChangeClearsPinsAndRequiresReview() {
        val s = ServerConfig(id = "s1", name = "NAS", url = "https://nas.example.com", pinnedCertSha256 = "AA", localUrl = "https://192.0.2.10", localPinnedCertSha256 = "BB")
        val after = CertificatesViewModel.afterUiCertChange(s)
        assertNull(after.pinnedCertSha256); assertNull(after.localPinnedCertSha256)
        assertTrue(after.certReviewRequired); assertTrue(after.uiCertCheckinPending)
        assertFalse(CertificatesViewModel.afterUiCertChange(s.copy(url = "http://nas.example.com", localUrl = null)).certReviewRequired)
        val edit = ServerEditState(url = "https://nas.example.com", certReviewRequired = true)
        assertTrue(ServerEditViewModel.reviewStillRequired(edit))
        assertFalse(ServerEditViewModel.reviewStillRequired(edit.copy(pinnedCert = "CC")))
    }

    // ---- Forms ----

    @Test fun certificateForms() {
        assertNull(CertForms.nameError("web_le-2026"))
        assertEquals("Only letters, digits, - and _", CertForms.nameError("web cert"))
        val (c, k) = CertForms.splitPem("junk\n$EXAMPLE_PEM\n-----BEGIN PRIVATE KEY-----\nAAA\n-----END PRIVATE KEY-----\n")
        assertEquals(EXAMPLE_PEM.trim(), c)
        assertTrue(k!!.startsWith("-----BEGIN PRIVATE KEY-----"))
        assertEquals(listOf("nas.example.com", "*.example.com"), CertForms.domains("NAS.example.com, *.example.com\nnas.example.com"))
        assertEquals("Accept the ACME terms of service", CertForms.acmeError("le", listOf("nas.example.com"), mapOf("nas.example.com" to 1), false, 10))
        assertEquals("Choose a DNS authenticator for every domain", CertForms.acmeError("le", listOf("nas.example.com"), emptyMap(), true, 10))
        assertEquals("\"bad_domain\" isn't a valid domain name", CertForms.acmeError("le", listOf("bad_domain"), mapOf("bad_domain" to 1), true, 10))
        assertNull(CertForms.acmeError("le", listOf("*.example.com"), mapOf("*.example.com" to 1), true, 10))
        assertEquals("https://acme-v02.api.letsencrypt.org/directory", CertForms.defaultDirectory(mapOf(
            "https://acme-staging-v02.api.letsencrypt.org/directory" to "Let's Encrypt Staging Directory",
            "https://acme-v02.api.letsencrypt.org/directory" to "Let's Encrypt Production Directory")))
    }

    // ---- Quick actions ----

    @Test fun statusTileText() {
        assertEquals(StatusTileText.Display("NAS", "Healthy", true), StatusTileText.of(WidgetSnapshot("NAS", Health.HEALTHY, "2 healthy", 0, updatedAt = 1)))
        assertEquals("Degraded · 2 alerts", StatusTileText.of(WidgetSnapshot("NAS", Health.CRITICAL, "tank: DEGRADED", 2, updatedAt = 1)).subtitle)
        assertEquals("Offline", StatusTileText.of(WidgetSnapshot("NAS", error = "Can't reach the NAS right now")).subtitle)
        assertFalse(StatusTileText.of(WidgetSnapshot(error = "Add a server in the app.")).active)
    }

    @Test fun staticShortcutsMatchDeepLinks() {
        val dests = setOf(DeepLink.DEST_SHELL, DeepLink.DEST_ALERTS, DeepLink.DEST_RESTART_APP, DeepLink.DEST_SCRUB_POOL)
        assertEquals(dests, QuickAction.entries.map { it.destination }.toSet())
        listOf("src/main/res/xml/shortcuts.xml" to "app.truenascompanion", "src/debug/res/xml/shortcuts.xml" to "app.truenascompanion.debug").forEach { (path, pkg) ->
            val xml = File(path).readText()
            dests.forEach { assertTrue("$path: $it", xml.contains("android:value=\"$it\"")) }
            assertEquals(4, Regex("android:targetPackage=\"$pkg\"").findAll(xml).count())
            assertTrue(xml.contains(DeepLink.EXTRA_DESTINATION))
        }
        assertTrue(File("src/main/AndroidManifest.xml").readText().contains("android.permission.BIND_QUICK_SETTINGS_TILE"))
    }

    @Test fun alertItemDefaultsHaveNoArgs() {
        assertNull(AlertItem("u", "INFO", "t", null, null, false, false).args)
    }

    companion object {
        /** Self-signed example certificate (CN=nas.example.com, O=Example Org), generated for tests only. */
        val EXAMPLE_PEM = """
-----BEGIN CERTIFICATE-----
MIIB5zCCAYygAwIBAgIUWTaI0Y13RDW29b924TAOGW60ve0wCgYIKoZIzj0EAwIw
MDEYMBYGA1UEAwwPbmFzLmV4YW1wbGUuY29tMRQwEgYDVQQKDAtFeGFtcGxlIE9y
ZzAeFw0yNjEwMDYxNjQwMTZaFw0zNjEwMDMxNjQwMTZaMDAxGDAWBgNVBAMMD25h
cy5leGFtcGxlLmNvbTEUMBIGA1UECgwLRXhhbXBsZSBPcmcwWTATBgcqhkjOPQIB
BggqhkjOPQMBBwNCAAQXbawpbMkSFBUThOTMMsm8MJ3lE1jlG6X21mmdIPzdDSHx
ZIm9WG3fiBAtHMFwBfpXpaRBw5e3PJEamxQz0UQuo4GDMIGAMB0GA1UdDgQWBBS7
YhZRlR9ggk3V1q/yOh0q2b84kzAfBgNVHSMEGDAWgBS7YhZRlR9ggk3V1q/yOh0q
2b84kzAPBgNVHRMBAf8EBTADAQH/MC0GA1UdEQQmMCSCD25hcy5leGFtcGxlLmNv
bYIRZmlsZXMuZXhhbXBsZS5jb20wCgYIKoZIzj0EAwIDSQAwRgIhALZPnETzUya7
2AvsmacpbIP13lnxFNVUdwQ2uUbaZELxAiEAsRg5jFrJqCFgQFtyoqfSTVTdUtH0
Orkixdw/d/J8IFM=
-----END CERTIFICATE-----
""".trimStart()
    }
}
