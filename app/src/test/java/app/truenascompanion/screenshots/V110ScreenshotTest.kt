package app.truenascompanion.screenshots

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import app.truenascompanion.data.api.AuditApi
import app.truenascompanion.data.model.AuditEntry
import app.truenascompanion.data.model.AuditFilter
import app.truenascompanion.data.model.AuditQuick
import app.truenascompanion.data.model.AuditTimeRange
import app.truenascompanion.data.model.NasGroup
import app.truenascompanion.data.model.NasUser
import app.truenascompanion.data.model.ReportData
import app.truenascompanion.data.model.ReportGraph
import app.truenascompanion.data.model.ReportKind
import app.truenascompanion.data.model.ReportRange
import app.truenascompanion.data.model.ReportSeries
import app.truenascompanion.data.store.ThemeMode
import app.truenascompanion.ui.accounts.AccountsContent
import app.truenascompanion.ui.accounts.AccountsData
import app.truenascompanion.ui.accounts.UserEditorDialog
import app.truenascompanion.ui.audit.AuditContent
import app.truenascompanion.ui.audit.AuditDetailContent
import app.truenascompanion.ui.audit.AuditUi
import app.truenascompanion.ui.reports.ReportsContent
import app.truenascompanion.ui.reports.ReportsUi
import app.truenascompanion.ui.theme.TrueNasTheme
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.PI
import kotlin.math.sin

/** 1.1.0 previews with example data only (users/groups, reports, audit log). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xxhdpi", application = android.app.Application::class)
class V110ScreenshotTest {
    @get:Rule val rule = createComposeRule()
    private val dir = File(System.getProperty("screenshot.dir") ?: "build/screenshots").apply { mkdirs() }
    private fun out(name: String) = File(dir, "$name.png").absolutePath

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

    private fun user(id: Int, name: String, full: String, groups: List<Int> = emptyList(), roles: List<String> = emptyList(), locked: Boolean = false,
                     keys: String? = null, builtin: Boolean = false, smb: Boolean = true, twoFactor: Boolean = false) = NasUser(
        id = id, uid = if (builtin) id else 3000 + id, username = name, fullName = full, email = null, home = if (builtin) "/var/empty" else "/mnt/tank/home/$name",
        shell = "/usr/bin/zsh", groupId = 100 + id, groupName = name, groups = groups, smb = smb, passwordDisabled = false, sshPasswordEnabled = false,
        sshPubKey = keys, locked = locked, builtin = builtin, immutable = builtin, local = true, twoFactor = twoFactor, roles = roles,
    )

    private val accounts = AccountsData(
        users = listOf(
            user(1, "admin", "Administrator", listOf(40), listOf("FULL_ADMIN"), keys = "ssh-ed25519 AAAA admin@example", twoFactor = true),
            user(2, "alex", "Alex Example", listOf(41, 42), keys = "ssh-ed25519 AAAA alex@example\nssh-rsa AAAA alex@laptop"),
            user(3, "jordan", "Jordan Sample", listOf(41)),
            user(4, "backup", "Backup service", smb = false, keys = "ssh-ed25519 AAAA backup@example"),
            user(5, "guest", "Guest", locked = true),
            user(0, "root", "root", builtin = true, smb = false),
        ),
        groups = listOf(
            NasGroup(40, 544, "builtin_administrators", builtin = true, immutable = true, local = true, smb = false, users = listOf(1), roles = listOf("FULL_ADMIN")),
            NasGroup(41, 3100, "family", builtin = false, immutable = false, local = true, smb = true, users = listOf(2, 3), roles = emptyList()),
            NasGroup(42, 3101, "media", builtin = false, immutable = false, local = true, smb = true, users = listOf(2), roles = emptyList()),
            NasGroup(43, 3102, "backup", builtin = false, immutable = false, local = true, smb = false, users = listOf(4), roles = emptyList()),
        ),
        shells = mapOf("/usr/bin/zsh" to "zsh", "/usr/bin/bash" to "bash", "/usr/sbin/nologin" to "nologin"),
    )

    @Test fun accountsUsers() {
        rule.mainClock.autoAdvance = false
        rule.setContent { Frame(true, "Users & groups") { AccountsContent(accounts, tab = 0, onTab = {}, onAction = {}) } }
        settle(); rule.onRoot().captureRoboImage(out("v110_accounts_users"))
    }

    @Test fun accountsGroupsLight() {
        rule.mainClock.autoAdvance = false
        rule.setContent { Frame(false, "Users & groups") { AccountsContent(accounts, tab = 1, onTab = {}, onAction = {}) } }
        settle(); rule.onRoot().captureRoboImage(out("v110_accounts_groups_light"))
    }

    @Test fun userEditor() {
        rule.mainClock.autoAdvance = false
        rule.setContent { Frame(true) { UserEditorDialog(accounts.users[1], accounts, onDismiss = {}, onSave = {}, inline = true) } }
        settle(); rule.onRoot().captureRoboImage(out("v110_user_editor"))
    }

    // ---- Reports ----
    private val now = 1_760_000_000L
    private fun series(n: Int, step: Long, f: (Int) -> Float) = LongArray(n) { now - (n - 1 - it) * step } to FloatArray(n) { f(it) }
    private fun data(name: String, id: String?, labels: List<String>, n: Int, step: Long, vararg fs: (Int) -> Float): ReportData {
        val times = LongArray(n) { now - (n - 1 - it) * step }
        val ss = labels.mapIndexed { k, l ->
            val v = FloatArray(n) { fs[k](it) }
            ReportSeries(l, v, v.min().toDouble(), v.average(), v.max().toDouble())
        }
        return ReportData(name, id, times.first(), times.last(), times, ss)
    }
    private fun wave(i: Int, period: Float, amp: Float, base: Float, phase: Float = 0f) = base + amp * sin(2 * PI.toFloat() * i / period + phase) + amp * 0.25f * sin(i / 3.1f)

    private val reports = run {
        val n = 360; val step = 10L
        ReportsUi(
            range = ReportRange.HOUR,
            graphs = listOf(ReportGraph("interface", "Interface Traffic", "Kilobits/s", listOf("eno1", "eno2", "br0")), ReportGraph("disk", "Disk I/O", "KiB/s", listOf("sda", "sdb", "nvme0n1"))),
            iface = "eno1", disk = "nvme0n1",
            charts = linkedMapOf(
                ReportKind.CPU to data("cpu", null, listOf("CPU"), n, step, { wave(it, 90f, 9f, 18f).coerceAtLeast(1f) }),
                ReportKind.MEMORY to data("memory", null, listOf("available"), n, step, { (wave(it, 200f, 1.2f, 21f) * 1e9f) }),
                ReportKind.NETWORK to data("interface", "eno1", listOf("received", "sent"), n, step, { wave(it, 60f, 18_000f, 26_000f).coerceAtLeast(0f) }, { wave(it, 75f, 4_000f, 6_000f, 1f).coerceAtLeast(0f) }),
                ReportKind.CPU_TEMP to data("cputemp", null, listOf("cpu0", "cpu1"), n, step, { wave(it, 120f, 4f, 48f) }, { wave(it, 120f, 4f, 51f, 0.4f) }),
                ReportKind.DISK_TEMP to data("disktemp", null, listOf("sda", "sdb", "nvme0n1"), n, step, { 34f + it / 120f }, { 36f + it / 150f }, { wave(it, 160f, 2f, 44f) }),
            ),
        )
    }

    @Test fun reportsDark() {
        rule.mainClock.autoAdvance = false
        rule.setContent { Frame(true, "Reports") { ReportsContent(reports, {}, {}, {}) } }
        settle()
        rule.onNodeWithContentDescription("CPU chart").performTouchInput { down(center.copy(x = width * 0.72f)); up() }
        settle(); rule.onRoot().captureRoboImage(out("v110_reports"))
    }

    @Test fun reportsLight() {
        rule.mainClock.autoAdvance = false
        rule.setContent { Frame(false, "Reports") { ReportsContent(reports.copy(range = ReportRange.DAY), {}, {}, {}) } }
        settle()
        rule.onNodeWithContentDescription("Memory available chart").performTouchInput { down(center.copy(x = width * 0.4f)); up() }
        settle(); rule.onRoot().captureRoboImage(out("v110_reports_light"))
    }

    // ---- Audit ----
    private fun entry(i: Int, event: String, user: String, addr: String, json: String, ok: Boolean = true): AuditEntry {
        val o = Json.parseToJsonElement("""{"audit_id":"id-$i","message_timestamp":${now - i * 420},"address":"$addr","username":"$user","session":"s$i","service":"MIDDLEWARE",$json,"event":"$event","success":$ok}""").jsonObject
        return AuditApi.parse(o)!!
    }
    private val rest = """"service_data":{"vers":{"major":0,"minor":1},"origin":"203.0.113.7","protocol":"LEGACY_REST","credentials":null}"""
    private val ws = """"service_data":{"vers":{"major":0,"minor":1},"origin":"192.0.2.10","protocol":"WEBSOCKET","credentials":{"credentials":"USERNAME_PASSWORD"}}"""
    private val entries = listOf(
        entry(1, "AUTHENTICATION", "admin", "203.0.113.7", """$rest,"event_data":{"credentials":{"credentials":"API_KEY","credentials_data":{"api_key":{"id":3,"name":"old-script"}}},"error":null}"""),
        entry(2, "AUTHENTICATION", "admin", "203.0.113.7", """$rest,"event_data":{"credentials":{"credentials":"API_KEY","credentials_data":{}},"error":null}"""),
        entry(3, "AUTHENTICATION", "alex", "198.51.100.23", """$rest,"event_data":{"credentials":{"credentials":"LOGIN_PASSWORD","credentials_data":{}},"error":"Bad username or password"}""", ok = false),
        entry(4, "AUTHENTICATION", "admin", "203.0.113.7", """$rest,"event_data":{"credentials":{"credentials":"API_KEY","credentials_data":{}},"error":null}"""),
        entry(5, "AUTHENTICATION", "backup", "203.0.113.50", """$rest,"event_data":{"credentials":{"credentials":"API_KEY","credentials_data":{}},"error":null}"""),
        entry(6, "AUTHENTICATION", "admin", "203.0.113.7", """$rest,"event_data":{"credentials":{"credentials":"API_KEY","credentials_data":{}},"error":null}"""),
    )

    @Test fun auditRestLogins() {
        rule.mainClock.autoAdvance = false
        val ui = AuditUi(filter = AuditFilter(quick = setOf(AuditQuick.AUTHENTICATION, AuditQuick.LEGACY_REST), time = AuditTimeRange.WEEK), entries = entries, total = 6, loading = false, endReached = true)
        rule.setContent { Frame(true, "Audit log") { AuditContent(ui, {}, {}, {}, {}, {}) } }
        settle(); rule.onRoot().captureRoboImage(out("v110_audit"))
    }

    @Test fun auditMiddlewareLight() {
        rule.mainClock.autoAdvance = false
        val calls = listOf(
            entry(1, "METHOD_CALL", "admin", "192.0.2.10", """$ws,"event_data":{"method":"user.update","params":[72,{"locked":true}],"description":"Update user guest","authenticated":true,"authorized":true}"""),
            entry(2, "METHOD_CALL", "admin", "192.0.2.10", """$ws,"event_data":{"method":"pool.scrub.scrub","params":["tank","START"],"description":"Scrub pool tank","authenticated":true,"authorized":true}"""),
            entry(3, "AUTHENTICATION", "admin", "192.0.2.10", """$ws,"event_data":{"credentials":{"credentials":"LOGIN_PASSWORD","credentials_data":{}},"error":null}"""),
            entry(4, "METHOD_CALL", "jordan", "192.0.2.44", """$ws,"event_data":{"method":"sharing.smb.delete","params":[3],"description":"Delete SMB share","authenticated":true,"authorized":false}""", ok = false),
            entry(5, "LOGOUT", "admin", "192.0.2.10", """$ws,"event_data":{}"""),
        ) + entries.take(2)
        val ui = AuditUi(entries = calls, total = 1284, loading = false)
        rule.setContent { Frame(false, "Audit log") { AuditContent(ui, {}, {}, {}, {}, {}) } }
        settle(); rule.onRoot().captureRoboImage(out("v110_audit_light"))
    }

    @Test fun auditDetail() {
        rule.mainClock.autoAdvance = false
        val e = entries[0]
        rule.setContent { Frame(true) { AuditDetailContent(e, AuditApi.prettyJson(e.raw), onCopy = {}) } }
        settle(); rule.onRoot().captureRoboImage(out("v110_audit_detail"))
    }
}
