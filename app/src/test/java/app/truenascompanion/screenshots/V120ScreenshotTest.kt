package app.truenascompanion.screenshots

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.GppMaybe
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.truenascompanion.R
import app.truenascompanion.data.model.AlertItem
import app.truenascompanion.data.model.AcmeAuthenticator
import app.truenascompanion.data.model.CertKind
import app.truenascompanion.data.model.Health
import app.truenascompanion.data.model.NasCertificate
import app.truenascompanion.data.store.ThemeMode
import app.truenascompanion.notify.SnoozeDialog
import app.truenascompanion.quick.StatusTileText
import app.truenascompanion.ui.alerts.AlertCard
import app.truenascompanion.ui.certs.AcmeCertForm
import app.truenascompanion.ui.certs.AcmeOptions
import app.truenascompanion.ui.certs.CertDetailContent
import app.truenascompanion.ui.certs.CertificatesContent
import app.truenascompanion.ui.certs.CertsData
import app.truenascompanion.ui.certs.ImportCertForm
import app.truenascompanion.ui.components.ConfirmDialog
import app.truenascompanion.ui.quick.PickItem
import app.truenascompanion.ui.quick.QuickPickDialog
import app.truenascompanion.ui.theme.TrueNasTheme
import app.truenascompanion.widget.WidgetSnapshot
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.serialization.json.Json
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** 1.2.0 previews with example data only (certificates, snooze / deep-link alerts, quick actions). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xxhdpi", application = android.app.Application::class)
class V120ScreenshotTest {
    @get:Rule val rule = createComposeRule()
    private val dir = File(System.getProperty("screenshot.dir") ?: "build/screenshots").apply { mkdirs() }
    private fun out(name: String) = File(dir, "$name.png").absolutePath
    private val now = System.currentTimeMillis()
    private val day = 86_400_000L

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable private fun Frame(dark: Boolean, title: String? = null, content: @Composable () -> Unit) {
        val d = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(d.density, 1f)) {
            TrueNasTheme(themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT, dynamicColor = false) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    if (title == null) content()
                    else Scaffold(topBar = {
                        TopAppBar(title = { Text(title) }, navigationIcon = { IconButton(onClick = {}) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, null) } })
                    }) { p -> Box(Modifier.padding(p).fillMaxSize()) { content() } }
                }
            }
        }
    }

    private fun settle() {
        repeat(8) { rule.mainClock.advanceTimeBy(250); shadowOf(android.os.Looper.getMainLooper()).idle() }
    }

    private fun cert(id: Int, name: String, days: Long, sans: List<String>, issuer: String, kind: CertKind = CertKind.CERTIFICATE, acme: Boolean = false,
                     self: Boolean = false) = NasCertificate(
        id, name, kind, sans.firstOrNull(), sans.map { "DNS:$it" }, issuer, self, now - 60 * day, if (kind == CertKind.CSR) null else now + days * day + 3_600_000L,
        days < 0, if (acme) "RSA" else "EC", if (acme) 2048 else 256, "SHA256", "3A:9F:12:C4:7B:0E:55:81:D2:6A:9C:44:10:EF:2B:73",
        acme, if (acme) "https://acme-v02.api.letsencrypt.org/directory" else null, if (acme) 10 else null, false, true, "/CN=${sans.firstOrNull()}",
    )

    private val certs = CertsData(
        certs = listOf(
            cert(1, "nas_letsencrypt", 52, listOf("nas.example.com", "files.example.com", "photos.example.com"), "R11", acme = true),
            cert(2, "truenas_default", 9, listOf("localhost"), "localhost", self = true),
            cert(3, "proxy_internal", -3, listOf("proxy.home.example"), "Example Home CA"),
            cert(4, "backup_target", 300, listOf("backup.example.net"), "Example Home CA"),
            cert(5, "Example_Home_CA", 2900, listOf("Example Home CA"), "Example Home CA", kind = CertKind.CA, self = true),
            cert(6, "nas_letsencrypt_csr", 0, listOf("nas.example.com"), "", kind = CertKind.CSR),
        ),
        uiCertId = 1,
        uiChoices = mapOf(1 to "nas_letsencrypt", 2 to "truenas_default", 3 to "proxy_internal", 4 to "backup_target"),
    )

    @Test fun certificatesDark() {
        rule.mainClock.autoAdvance = false
        rule.setContent { Frame(true, "Certificates") { CertificatesContent(certs, now, 14, tab = 0, onTab = {}, onAction = {}) } }
        settle(); rule.onRoot().captureRoboImage(out("v120_certificates"))
    }

    @Test fun certificatesLight() {
        rule.mainClock.autoAdvance = false
        rule.setContent { Frame(false, "Certificates") { CertificatesContent(certs, now, 14, tab = 0, onTab = {}, highlight = "truenas_default", onAction = {}) } }
        settle(); rule.onRoot().captureRoboImage(out("v120_certificates_light"))
    }

    @Test fun certificateDetail() {
        rule.mainClock.autoAdvance = false
        rule.setContent { Frame(true) { CertDetailContent(certs.certs[0], now, 14, isWebUi = true) } }
        settle(); rule.onRoot().captureRoboImage(out("v120_cert_detail"))
    }

    @Test fun importForm() {
        rule.mainClock.autoAdvance = false
        rule.setContent { Frame(true) { Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp)) { ImportCertForm(onCancel = {}, onImport = {}) } } }
        settle(); rule.onRoot().captureRoboImage(out("v120_cert_import"))
    }

    @Test fun acmeForm() {
        rule.mainClock.autoAdvance = false
        val options = AcmeOptions(
            mapOf("https://acme-staging-v02.api.letsencrypt.org/directory" to "Let's Encrypt Staging", "https://acme-v02.api.letsencrypt.org/directory" to "Let's Encrypt Production"),
            listOf(AcmeAuthenticator(1, "cloudflare-example", "cloudflare"), AcmeAuthenticator(2, "route53-example", "route53")),
        )
        rule.setContent {
            Frame(false) {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp)) {
                    AcmeCertForm(options, csrs = listOf(certs.certs[5]), csrDomains = mapOf(6 to listOf("nas.example.com", "files.example.com")), onCsrPicked = {}, onCancel = {}, onRequest = {})
                }
            }
        }
        settle(); rule.onRoot().captureRoboImage(out("v120_cert_acme_light"))
    }

    @Test fun webUiWarning() {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            Frame(true) {
                ConfirmDialog(
                    title = "Use proxy_internal for the web UI?",
                    text = "TrueNAS restarts its web server with this certificate and every open session (browser and this app) is disconnected. " +
                        "Browsers that don't trust it will show security warnings, and API clients that pin the old certificate stop working.\n\n" +
                        "Next, this app asks you to check and trust the new certificate. If that doesn't happen within 10 minutes, TrueNAS switches back to the previous certificate by itself.",
                    confirmLabel = "Switch certificate", destructive = true, strongAuth = true, icon = Icons.Rounded.GppMaybe, onConfirm = {}, onDismiss = {},
                )
            }
        }
        settle(); rule.onNode(isDialog()).captureRoboImage(out("v120_cert_webui_warning"))
    }

    private fun alert(uuid: String, level: String, klass: String, text: String, args: String?, minsAgo: Long) =
        AlertItem(uuid, level, text, klass, now - minsAgo * 60_000L, false, false, args = args?.let { Json.parseToJsonElement(it) })

    @Test fun alertsWithTargetsAndSnooze() {
        rule.mainClock.autoAdvance = false
        val list = listOf(
            alert("1", "CRITICAL", "VolumeStatus", "Pool tank state is DEGRADED: One or more devices are faulted in response to persistent errors.", """{"volume":"tank"}""", 4),
            alert("2", "WARNING", "SMARTTestFailed", "SMART test on sdb (S/N EX4MPL3) failed: Short offline test failed at 10% remaining.", """{"name":"sdb","serial":"EX4MPL3"}""", 38),
            alert("3", "WARNING", "CertificateIsExpiringSoon", "Certificate \"truenas_default\" is expiring within 9 days.", """{"name":"truenas_default","days":9}""", 120),
            alert("4", "INFO", "AppUpdate", "An update is available for \"nextcloud\" application.", """{"count":1,"plural":"","apps":"nextcloud"}""", 300),
        )
        rule.setContent {
            Frame(true, "Alerts") {
                LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(list, key = { it.uuid }) { a -> AlertCard(a, onDismiss = {}, snoozedUntil = if (a.uuid == "4") now + 8 * 3_600_000L else null) }
                }
            }
        }
        settle(); rule.onRoot().captureRoboImage(out("v120_alerts"))
    }

    @Test fun snoozePicker() {
        rule.mainClock.autoAdvance = false
        rule.setContent { Frame(false) { SnoozeDialog(count = 2, onPick = {}, onDismiss = {}) } }
        settle(); rule.onNode(isDialog()).captureRoboImage(out("v120_snooze_light"))
    }

    @Test fun quickRestartPicker() {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            Frame(true) {
                QuickPickDialog(
                    title = "Restart an app", text = "The app's containers stop and start again on Example NAS; it's unavailable for a moment.",
                    confirmLabel = "Restart", icon = Icons.Rounded.RestartAlt,
                    items = listOf(PickItem("nextcloud", "nextcloud", "Running"), PickItem("jellyfin", "jellyfin", "Running"), PickItem("immich", "immich", "Crashed"),
                        PickItem("homepage", "homepage", "Stopped"), PickItem("syncthing", "syncthing", "Deploying", enabled = false)),
                    error = null, selected = "nextcloud", onSelect = {}, onDismiss = {}, onConfirm = {},
                )
            }
        }
        settle(); rule.onNode(isDialog()).captureRoboImage(out("v120_quick_restart"))
    }

    /** Approximation of the shade: grouped alert with a count, certificate warning and both Quick Settings tiles. */
    @Test fun notificationsAndTiles() {
        rule.mainClock.autoAdvance = false
        rule.setContent { Frame(true) { ShadeMock(StatusTileText.of(WidgetSnapshot("Example NAS", Health.CRITICAL, "tank: DEGRADED", 3, updatedAt = now))) } }
        settle(); rule.onRoot().captureRoboImage(out("v120_notifications"))
    }

    @Composable private fun ShadeMock(tile: StatusTileText.Display) {
        val shade = Color(0xFF101418); val card = Color(0xFF232A33); val onCard = Color(0xFFE6E9EF); val sub = Color(0xFFAAB2BF)
        val brand = Color(0xFF2F5BEA); val action = Color(0xFF9DB4FF)
        Column(Modifier.fillMaxSize().background(shade).padding(horizontal = 12.dp, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                @Composable fun Tile(icon: Int, title: String, subtitle: String, active: Boolean, m: Modifier) {
                    Row(m.clip(RoundedCornerShape(28.dp)).background(if (active) Color(0xFFB8C8FF) else Color(0xFF2E3640)).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Image(painterResource(icon), null, Modifier.size(22.dp), colorFilter = ColorFilter.tint(if (active) Color(0xFF0B1A4A) else onCard))
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(title, color = if (active) Color(0xFF0B1A4A) else onCard, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                            Text(subtitle, color = if (active) Color(0xFF243466) else sub, fontSize = 12.sp, maxLines = 1)
                        }
                    }
                }
                Tile(R.drawable.ic_tile_status, tile.label, tile.subtitle, tile.active, Modifier.weight(1f))
                Tile(R.drawable.ic_tile_scrub, "Scrub a pool", "TrueNAS", false, Modifier.weight(1f))
            }
            Spacer(Modifier.height(18.dp))
            @Composable fun Notif(top: Boolean, bottom: Boolean, meta: String, title: String, lines: List<String>, actions: List<String>) {
                val shape = RoundedCornerShape(topStart = if (top) 24.dp else 6.dp, topEnd = if (top) 24.dp else 6.dp, bottomStart = if (bottom) 24.dp else 6.dp, bottomEnd = if (bottom) 24.dp else 6.dp)
                Column(Modifier.fillMaxWidth().clip(shape).background(card).padding(16.dp)) {
                    Row(verticalAlignment = Alignment.Top) {
                        Box(Modifier.size(36.dp).clip(CircleShape).background(brand), contentAlignment = Alignment.Center) {
                            Image(painterResource(R.drawable.ic_stat_notify), null, Modifier.size(20.dp), colorFilter = ColorFilter.tint(Color.White))
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(meta, color = sub, fontSize = 12.sp, maxLines = 1)
                            Text(title, color = onCard, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                            lines.forEach { Text(it, color = sub, fontSize = 14.sp, maxLines = 2) }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(Modifier.padding(start = 48.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) { actions.forEach { Text(it, color = action, fontSize = 14.sp, fontWeight = FontWeight.Medium) } }
                }
            }
            Notif(true, false, "TrueNAS Companion · Example NAS · Warning · now", "SMART test failed · 3",
                listOf("SMART test on sdb failed", "SMART test on sdc failed", "SMART test on sdd failed"), listOf("Dismiss all", "Snooze", "Open"))
            Notif(false, true, "TrueNAS Companion · Example NAS · Certificates · 1h", "Certificate expires in 9 days",
                listOf("truenas_default (localhost) expires in 9 days."), emptyList())
            Spacer(Modifier.height(8.dp))
            Text("Preview mock: the system draws the real shade and tiles.", color = sub.copy(alpha = 0.7f), fontSize = 11.sp, modifier = Modifier.padding(start = 8.dp))
        }
    }
}
