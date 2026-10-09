package app.truenascompanion.screenshots

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.truenascompanion.data.model.*
import app.truenascompanion.data.store.ConnectionTimeoutPrefs
import app.truenascompanion.data.store.ThemeMode
import app.truenascompanion.ui.AppNavBar
import app.truenascompanion.ui.alerts.AlertCard
import app.truenascompanion.ui.connection.ConnectionFailurePanel
import app.truenascompanion.ui.dashboard.*
import app.truenascompanion.ui.files.FileBrowserContent
import app.truenascompanion.ui.files.FileBrowserUi
import app.truenascompanion.ui.servers.LocalAddressSection
import app.truenascompanion.ui.servers.LocalStatus
import app.truenascompanion.ui.servers.ServerEditState
import app.truenascompanion.ui.system.*
import app.truenascompanion.ui.theme.TrueNasTheme
import app.truenascompanion.ui.servers.HttpsNotice
import app.truenascompanion.ui.servers.HttpsTrustDialog
import app.truenascompanion.ui.storage.DatasetDeleteBody
import app.truenascompanion.ui.storage.DatasetDeleteImpact
import app.truenascompanion.data.net.CertificateInfo
import app.truenascompanion.data.net.HttpsTrustRequest
import app.truenascompanion.data.net.HttpsUpgrade
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.sin

/** 1.7.1 previews (example data only): RTL, 200 % font / landscape failure card, HTTPS migration, dataset delete. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xxhdpi", application = android.app.Application::class)
class V171ScreenshotTest {
    @get:Rule val rule = createComposeRule()
    private val dir = File(System.getProperty("screenshot.dir") ?: "build/screenshots").apply { mkdirs() }
    private fun out(name: String) = File(dir, "v171_$name.png").absolutePath
    private val now = System.currentTimeMillis()
    private val gib = 1024L * 1024 * 1024

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable private fun Frame(dark: Boolean = true, font: Float = 1f, rtl: Boolean = false, title: String? = null, content: @Composable () -> Unit) {
        val d = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(d.density, font), LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
            TrueNasTheme(themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT, dynamicColor = false) {
                Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
                    if (title == null) content()
                    else Scaffold(topBar = { TopAppBar(title = { Text(title) }, navigationIcon = { IconButton(onClick = {}) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } }) }) { p ->
                        Box(Modifier.padding(p).fillMaxSize()) { content() }
                    }
                }
            }
        }
    }

    private fun settle() { repeat(8) { rule.mainClock.advanceTimeBy(250); shadowOf(android.os.Looper.getMainLooper()).idle() } }

    private fun shot(name: String, dark: Boolean = true, font: Float = 1f, rtl: Boolean = false, title: String? = null, scanIt: Boolean = false, content: @Composable () -> Unit) {
        rule.mainClock.autoAdvance = false
        rule.setContent { Frame(dark, font, rtl, title, content) }
        settle()
        rule.onRoot().captureRoboImage(out(name))
    }

    // ---------- example data ----------
    private val system = SystemInfo(
        hostname = "homenas", version = "TrueNAS-25.10.3", uptimeSeconds = 12 * 86400L + 5 * 3600, uptimeText = null,
        cpuModel = "Intel(R) Core(TM) i7-10700 CPU @ 2.90GHz", cores = 16, physicalMemory = (125.5 * gib).toLong(),
        loadAverage = listOf(0.42, 0.37, 0.31), systemProduct = null, eccMemory = false,
    )
    private val pools = listOf(
        Pool(1, "tank", "ONLINE", true, false, null, 32_000_000_000_000, 19_500_000_000_000, null, null, "SCRUB", "FINISHED", 100.0, 0, listOf("sda", "sdb")),
        Pool(2, "fast", "ONLINE", true, false, null, 2_000_000_000_000, 1_760_000_000_000, null, null, null, null, null, null, listOf("nvme0n1")),
    )
    private val live: LiveStats = run {
        var l = LiveStats()
        repeat(40) { i ->
            l = l.add(RealtimeStats(cpuPercent = 2.2 + 1.8 * sin(i / 3.0), cpuTempC = 46.0, memoryTotal = (125.5 * gib).toLong(), memoryAvailable = (8.7 * gib).toLong(),
                arcSize = (100.2 * gib).toLong(), netRxBytesPerSec = 7200.0 + 5000 * sin(i / 4.0).coerceAtLeast(0.0), netTxBytesPerSec = 1100.0, cpuCores = List(16) { 2.0 }))
        }
        l
    }
    private val data = DashboardData(
        loading = false, system = system, pools = pools,
        apps = AppsSummary(total = 14, running = 12, updates = 3, problems = 0),
        alerts = AlertsSummary(active = 1, worst = Health.WARNING, latest = AlertItem("1", "WARNING", "Pool fast is 88% full.", null, null, false, false)),
        hottestDisk = "sda" to 38.0, protection = app.truenascompanion.ProtectionSamples.healthySummary,
    )

    @Composable private fun Dashboard(navBar: Boolean = true) {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).dashboardHeaderGlow()) {
            Box(Modifier.padding(start = 16.dp, top = 20.dp, bottom = 8.dp)) {
                CompositionLocalProvider(LocalTextStyle provides MaterialTheme.typography.titleLarge) { DashboardTitle("homenas", "Live", Health.HEALTHY, live = true, route = Route.LOCAL) }
            }
            Box(Modifier.weight(1f)) { WidgetGrid(WidgetType.entries.map { WidgetConfig(it) }, data, live, ApiFlavor.WEBSOCKET, onOpen = {}, onEdit = {}) }
            if (navBar) AppNavBar("dashboard") {}
        }
    }

    private val header = HubHeader("homenas", "nas.example.com", "25.10.4", 1_036_800, online = true)
    private val summary = HubSummary(connected = true, runningJobs = 1, serverCount = 2, servicesRunning = 3, cronJobs = 2, initScripts = 1,
        certsExpiring = 1, certsExpired = 0, alertsMode = HubSummary.AlertsMode.PERIODIC, alertsIntervalMinutes = 15, lockOn = true,
        giveUpMs = ConnectionTimeoutPrefs.DEFAULT_MS, themeMode = ThemeMode.SYSTEM, appVersion = "1.7.0")
    @Composable private fun Hub() = Column {
        Box(Modifier.weight(1f)) { SystemHubContent(header, hubSubtitles(summary), onOpen = {}, onSwitchServer = {}) }
        AppNavBar("system") {}
    }

    private fun alert(uuid: String, level: String, text: String, minsAgo: Long) = AlertItem(uuid, level, text, null, now - minsAgo * 60_000L, false, false)
    private val alerts = listOf(
        alert("1", "CRITICAL", "Pool tank state is DEGRADED: One or more devices are faulted in response to persistent errors.", 4),
        alert("2", "WARNING", "SMART test on sdb (S/N EX4MPL3) failed: Short offline test failed at 10% remaining.", 38),
        alert("3", "INFO", "An update is available for \"nextcloud\" application.", 300),
    )
    @Composable private fun Alerts() = LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(alerts, key = { it.uuid }) { a -> AlertCard(a, onDismiss = {}, snoozedUntil = if (a.uuid == "3") now + 8 * 3_600_000L else null) }
    }

    @Composable private fun Failure() = Box(Modifier.fillMaxSize()) {
        Text("Home NAS", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(24.dp))
        ConnectionFailurePanel("Home NAS", "Make sure the NAS is online and that your phone can reach it — on Wi‑Fi at home, or through your VPN.", {}, {}, {})
    }

    private fun e(name: String, type: String = "FILE", size: Long = 0) =
        FileEntry(name, "/mnt/tank/media/photos/2026/$name", type, size, if (type == "DIRECTORY") 0x41ED else 0x81A4, 3000, 3000, false, false, null, now - 86_400_000L)
    private val files = FileBrowserUi(path = "/mnt/tank/media/photos/2026", entries = listOf(e("Summer", "DIRECTORY", 4), e("IMG_0042.jpg", size = 4_200_000), e("notes.txt", size = 2_100)),
        total = 3, loading = false, pools = listOf("tank"), route = Route.LOCAL)

    private val edit = ServerEditState(name = "Home NAS", url = "https://nas.example.com", authMethod = AuthMethod.PASSWORD, username = "truenas_admin",
        localUrl = "https://192.168.1.10:444", routeMode = RouteMode.AUTO, localStatus = LocalStatus.Reachable(true))


    @Composable private fun FailureSwitch() = Box(Modifier.fillMaxSize()) {
        Text("Home NAS", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(24.dp))
        ConnectionFailurePanel(
            "Home NAS", "Make sure the NAS is online and that your phone can reach it — on Wi‑Fi at home, or through your VPN.",
            onQuit = {}, onCheckConfig = {}, onRetry = {}, onSwitchServer = {},
        )
    }

    // ---------- RTL (phone set to Hebrew): numbers and units read correctly, layout still mirrors ----------
    @Test fun rtlDashboard() = shot("rtl_dashboard", rtl = true) { Dashboard() }
    @Test fun rtlFiles() = shot("rtl_files", rtl = true, title = "Files") { FileBrowserContent(files) }
    @Test fun rtlHub() = shot("rtl_system_hub", rtl = true) { Hub() }

    // ---------- Connection failure card ----------
    @Test fun failureDark() = shot("connection_failure") { FailureSwitch() }
    @Test fun failureLight() = shot("connection_failure_light", dark = false) { FailureSwitch() }
    @Test fun failureFont200() = shot("connection_failure_font200", font = 2f) { FailureSwitch() }
    @Config(qualifiers = "w800dp-h360dp-land-xxhdpi")
    @Test fun failureLandscape() = shot("connection_failure_landscape") { FailureSwitch() }

    // ---------- HTTPS migration ----------
    private val cert = CertificateInfo(
        "3F:A1:9C:42:7B:D0:5E:11:8A:6C:E2:04:B9:73:1D:F5:0C:68:A4:2E:97:B3:5D:C1:40:7A:E8:16:2B:9F:D3:55",
        "CN=truenas, O=iXsystems", "CN=truenas, O=iXsystems", "Jan 4, 2026", "Feb 5, 2027", selfSigned = true,
    )
    private val trust = HttpsTrustRequest("s1", "Home NAS", HttpsUpgrade.Outcome.NeedsTrust(Route.LOCAL, "http://192.168.1.50", "https://192.168.1.50", cert, true))

    @Test fun httpsTrustDialog() {
        rule.mainClock.autoAdvance = false
        rule.setContent { Frame { Dashboard(); HttpsTrustDialog(trust, {}, {}) } }
        settle()
        rule.onNode(isDialog()).captureRoboImage(out("https_trust_dialog"))
    }

    private val httpServer = ServerConfig(id = "s1", name = "Home NAS", url = "https://nas.example.com", localUrl = "http://192.168.1.50", authMethod = AuthMethod.PASSWORD)
    @Test fun httpsBanner() = shot("https_banner") {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).dashboardHeaderGlow()) {
            Box(Modifier.padding(start = 16.dp, top = 20.dp, bottom = 8.dp)) {
                CompositionLocalProvider(LocalTextStyle provides MaterialTheme.typography.titleLarge) { DashboardTitle("homenas", "Live", Health.HEALTHY, live = true, route = Route.REMOTE) }
            }
            HttpsNotice(httpServer, note = "No HTTPS answered at 192.168.1.50 just now (are you on your home Wi-Fi?).", busy = false, onSetUp = {}, onDismiss = {})
            Box(Modifier.weight(1f)) { WidgetGrid(WidgetType.entries.map { WidgetConfig(it) }, data, live, ApiFlavor.WEBSOCKET, onOpen = {}, onEdit = {}) }
            AppNavBar("dashboard") {}
        }
    }
    @Test fun httpsBannerLight() = shot("https_banner_light", dark = false) {
        Column(Modifier.padding(top = 24.dp)) {
            HttpsNotice(httpServer, note = null, busy = true, onSetUp = {}, onDismiss = {})
        }
    }

    // ---------- Safer dataset delete ----------
    private val media = Dataset("tank/media", "tank", "FILESYSTEM", (1.82 * 1024 * gib).toLong(), 9L * 1024 * gib, false, false, "/mnt/tank/media")
    @Test fun datasetDelete() = shot("dataset_delete") {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = androidx.compose.ui.Alignment.Center) {
            Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Column(Modifier.padding(24.dp)) {
                    Text("Delete dataset media?", style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(16.dp))
                    DatasetDeleteBody(
                        media,
                        DatasetDeleteImpact(media.used, 3, listOf("/mnt/tank/media"), snapshots = 42, attachments = listOf("SMB Share: media", "Apps: jellyfin")),
                        recursive = true, onRecursive = {}, force = false, onForce = {}, typed = "medi", onTyped = {},
                    )
                    Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = {}) { Text("Cancel") }
                        Button(onClick = {}, enabled = false) { Text("Delete") }
                    }
                }
            }
        }
    }
}
