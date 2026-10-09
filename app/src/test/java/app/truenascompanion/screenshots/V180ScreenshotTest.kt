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
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.VisibilityOff
import app.truenascompanion.ui.storage.PoolsList
import app.truenascompanion.ui.storage.StorageHeader
import app.truenascompanion.ui.storage.SmbShareDialog

/** 1.8.0 (YTN) previews, example data only: Home, System hub, Storage, Alerts, editor and Connection page, dark and light. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xxhdpi", application = android.app.Application::class)
@OptIn(ExperimentalMaterial3Api::class)
class V180ScreenshotTest {
    @get:Rule val rule = createComposeRule()
    private val dir = File(System.getProperty("screenshot.dir") ?: "build/screenshots").apply { mkdirs() }
    private fun out(name: String) = File(dir, "v180_$name.png").absolutePath
    private val now = System.currentTimeMillis()
    private val gib = 1024L * 1024 * 1024

    @Composable private fun Frame(dark: Boolean, content: @Composable () -> Unit) {
        TrueNasTheme(themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT, dynamicColor = false) {
            Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) { content() }
        }
    }

    private fun settle() { repeat(8) { rule.mainClock.advanceTimeBy(250); shadowOf(android.os.Looper.getMainLooper()).idle() } }

    private fun shot(name: String, dark: Boolean, content: @Composable () -> Unit) {
        rule.mainClock.autoAdvance = false
        rule.setContent { Frame(dark, content) }
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
        Pool(1, "tank", "ONLINE", true, false, null, 32_000_000_000_000, 19_500_000_000_000, 12_500_000_000_000, null, "SCRUB", "FINISHED", 100.0, 0, listOf("sda", "sdb")),
        Pool(2, "fast", "ONLINE", true, false, null, 2_000_000_000_000, 1_760_000_000_000, 240_000_000_000, null, null, null, null, null, listOf("nvme0n1")),
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
        giveUpMs = ConnectionTimeoutPrefs.DEFAULT_MS, themeMode = ThemeMode.SYSTEM, appVersion = "1.8.0")
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

    @Composable private fun Alerts() = Column {
        Box(Modifier.weight(1f)) {
            app.truenascompanion.ui.components.Scaffold(topBar = {
                app.truenascompanion.ui.components.TopAppBar(title = { Text("Alerts") }, actions = {
                    IconButton(onClick = {}) { Icon(androidx.compose.material.icons.Icons.Rounded.NotificationsActive, "Phone alert settings") }
                })
            }) { p ->
                LazyColumn(Modifier.padding(p), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(alerts, key = { it.uuid }) { a -> AlertCard(a, onDismiss = {}, snoozedUntil = if (a.uuid == "3") now + 8 * 3_600_000L else null) }
                }
            }
        }
        AppNavBar("alerts", alertCount = 2) {}
    }

    @Composable private fun Storage() = Column {
        Box(Modifier.weight(1f)) {
            app.truenascompanion.ui.components.Scaffold(topBar = { app.truenascompanion.ui.components.TopAppBar(title = { Text("Storage") }) }) { p ->
                Column(Modifier.padding(p)) {
                    StorageHeader(0) {}
                    PoolsList(pools, {}, {})
                }
            }
        }
        AppNavBar("storage") {}
    }

    private val server = ServerConfig(id = "s1", name = "homenas", url = "https://nas.example.com", localUrl = "https://192.168.1.50")
    @Composable private fun Connection() = app.truenascompanion.ui.components.Scaffold(topBar = {
        app.truenascompanion.ui.components.TopAppBar(title = { Text("Connection") }, navigationIcon = { IconButton(onClick = {}) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } })
    }) { p ->
        Column(Modifier.padding(p).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            app.truenascompanion.ui.components.SectionTitle("This server")
            ServerConnectionCard(server, Route.LOCAL, {}, {})
            app.truenascompanion.ui.components.SectionTitle("Retry timeout")
            ConnectionTimeoutSection(ConnectionTimeoutPrefs.DEFAULT_MS) {}
        }
    }

    @Test fun homeDark() = shot("home_dark", true) { Dashboard() }
    @Test fun homeLight() = shot("home_light", false) { Dashboard() }
    @Test fun hubDark() = shot("system_hub_dark", true) { Hub() }
    @Test fun hubLight() = shot("system_hub_light", false) { Hub() }
    @Test fun storageDark() = shot("storage_dark", true) { Storage() }
    @Test fun storageLight() = shot("storage_light", false) { Storage() }
    @Test fun alertsDark() = shot("alerts_dark", true) { Alerts() }
    @Test fun alertsLight() = shot("alerts_light", false) { Alerts() }
    @Test fun connectionDark() = shot("connection_dark", true) { Connection() }
    @Test fun connectionLight() = shot("connection_light", false) { Connection() }

    @Test fun smbEditor() {
        rule.mainClock.autoAdvance = false
        rule.setContent { Frame(true) { Box(Modifier.fillMaxSize()) { SmbShareDialog(null, listOf("tank/media", "tank/photos", "fast/apps"), {}, {}) } } }
        settle()
        rule.onNode(isDialog()).captureRoboImage(out("smb_editor_dark"))
    }
}
