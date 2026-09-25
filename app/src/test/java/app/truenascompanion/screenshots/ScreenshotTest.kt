package app.truenascompanion.screenshots

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.truenascompanion.data.model.AlertItem
import app.truenascompanion.data.model.ApiFlavor
import app.truenascompanion.data.model.Health
import app.truenascompanion.data.model.Pool
import app.truenascompanion.data.model.RealtimeStats
import app.truenascompanion.data.model.SystemInfo
import app.truenascompanion.data.model.WidgetConfig
import app.truenascompanion.data.model.WidgetType
import app.truenascompanion.data.store.ThemeMode
import app.truenascompanion.ui.AppNavBar
import app.truenascompanion.ui.auth.OtpDialog
import app.truenascompanion.ui.components.ConfirmDialog
import app.truenascompanion.ui.components.ElevatedSection
import app.truenascompanion.ui.components.SectionTitle
import app.truenascompanion.ui.dashboard.AlertsSummary
import app.truenascompanion.ui.dashboard.AppsSummary
import app.truenascompanion.ui.dashboard.DashboardData
import app.truenascompanion.ui.dashboard.DashboardTitle
import app.truenascompanion.ui.dashboard.LiveStats
import app.truenascompanion.ui.dashboard.WidgetGrid
import app.truenascompanion.ui.dashboard.dashboardHeaderGlow
import app.truenascompanion.ui.system.PowerButtons
import app.truenascompanion.ui.system.SettingRow
import app.truenascompanion.ui.theme.TrueNasTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.sin

/**
 * Renders key screens at 360dp width (worst-case phone) in light/dark and at font scale 1.3.
 * Run: ./gradlew testDebugUnitTest -Pscreenshots  → PNGs in ./screenshots/
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h1700dp-xxhdpi")
class ScreenshotTest {
    @get:Rule val rule = createComposeRule()

    private val dir = File(System.getProperty("screenshot.dir") ?: "build/screenshots").apply { mkdirs() }
    private fun out(name: String) = File(dir, "$name.png").absolutePath

    private val gib = 1024L * 1024 * 1024
    private val system = SystemInfo(
        hostname = "homenas", version = "TrueNAS-25.10.3", uptimeSeconds = 12 * 86400L + 5 * 3600, uptimeText = null,
        cpuModel = "Intel(R) Core(TM) i7-10700 CPU @ 2.90GHz", cores = 16, physicalMemory = (125.5 * gib).toLong(),
        loadAverage = listOf(0.42, 0.37, 0.31), systemProduct = null, eccMemory = false,
    )
    private val pools = listOf(
        Pool(1, "tank", "ONLINE", true, false, null, 32_000_000_000_000, 19_500_000_000_000, null, null, "SCRUB", "FINISHED", 100.0, 0, listOf("sda", "sdb")),
        Pool(2, "fast", "ONLINE", true, false, null, 2_000_000_000_000, 1_760_000_000_000, null, null, null, null, null, null, listOf("nvme0n1")),
    )
    private val cores = listOf(3.0, 1.0, 0.0, 6.0, 2.0, 0.0, 1.0, 12.0, 0.0, 2.0, 1.0, 0.0, 4.0, 0.0, 1.0, 2.0)
    private val live: LiveStats = run {
        var l = LiveStats()
        repeat(40) { i ->
            l = l.add(RealtimeStats(
                cpuPercent = 2.2 + 1.8 * sin(i / 3.0) + (if (i % 7 == 0) 4 else 0), cpuTempC = 46.0,
                memoryTotal = (125.5 * gib).toLong(), memoryAvailable = (8.7 * gib).toLong(), arcSize = (100.2 * gib).toLong(),
                netRxBytesPerSec = 7200.0 + 5000 * sin(i / 4.0).coerceAtLeast(0.0), netTxBytesPerSec = 1100.0 + 800 * sin(i / 5.0 + 1).coerceAtLeast(0.0),
                cpuCores = cores,
            ))
        }
        l
    }
    private val data = DashboardData(
        loading = false, system = system, pools = pools,
        apps = AppsSummary(total = 14, running = 12, updates = 3, problems = 0),
        alerts = AlertsSummary(active = 1, worst = Health.WARNING, latest = AlertItem("1", "WARNING", "Pool fast is 88% full.", null, null, false, false)),
        hottestDisk = "sda" to 38.0,
    )

    @Composable
    private fun Frame(dark: Boolean, fontScale: Float = 1f, content: @Composable () -> Unit) {
        val d = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(d.density, fontScale)) {
            TrueNasTheme(themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT, dynamicColor = false) {
                Surface(color = MaterialTheme.colorScheme.background) { content() }
            }
        }
    }

    @Composable
    private fun Dashboard() {
        Column(Modifier.fillMaxWidth().height(1640.dp).background(MaterialTheme.colorScheme.background).dashboardHeaderGlow()) {
            Box(Modifier.padding(start = 16.dp, top = 20.dp, bottom = 8.dp)) {
                CompositionLocalProvider(androidx.compose.material3.LocalTextStyle provides MaterialTheme.typography.titleLarge) {
                    DashboardTitle("homenas", "Live", Health.HEALTHY, live = true)
                }
            }
            Box(Modifier.weight(1f)) {
                WidgetGrid(WidgetType.entries.map { WidgetConfig(it) }, data, live, ApiFlavor.WEBSOCKET, onOpen = {}, onEdit = {})
            }
            AppNavBar("dashboard") {}
        }
    }

    private fun shot(name: String, content: @Composable () -> Unit) {
        rule.mainClock.autoAdvance = false
        rule.setContent(content)
        rule.mainClock.advanceTimeBy(1500)
        rule.onRoot().captureRoboImage(out(name))
    }

    @Test fun dashboardLight() = shot("preview-dashboard-light") { Frame(false) { Dashboard() } }
    @Test fun dashboardDark() = shot("preview-dashboard-dark") { Frame(true) { Dashboard() } }
    @Test fun dashboardDarkLargeFont() = shot("audit-dashboard-dark-font130") { Frame(true, 1.3f) { Dashboard() } }
    @Test fun dashboardLightLargeFont() = shot("audit-dashboard-light-font130") { Frame(false, 1.3f) { Dashboard() } }

    @Composable
    private fun SystemBits() {
        Column(Modifier.padding(16.dp)) {
            SectionTitle("Power")
            ElevatedSection {
                Text("Restart or turn off the NAS. Apps, shares and VMs will be unavailable meanwhile.", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                PowerButtons(enabled = true, onReboot = {}, onShutdown = {})
            }
            Spacer(Modifier.height(12.dp))
            SectionTitle("Appearance")
            ElevatedSection {
                SettingRow(Icons.Rounded.DarkMode, "Theme")
                Spacer(Modifier.height(12.dp))
                SettingRow(Icons.Rounded.Palette, "Dynamic color", "Use your wallpaper colors instead of the TrueNAS Companion theme (Android 12+)") { Switch(false, {}) }
            }
            Spacer(Modifier.height(12.dp))
            SectionTitle("About")
            ElevatedSection { SettingRow(Icons.Rounded.Info, "TrueNAS Companion 0.2.1", "Free & open source. No ads, no analytics, no tracking.") }
        }
    }

    @Test fun systemLight() = shot("preview-system-light") { Frame(false) { SystemBits() } }
    @Test fun systemDarkLargeFont() = shot("audit-system-dark-font130") { Frame(true, 1.3f) { SystemBits() } }

    private fun dialogShot(name: String, dark: Boolean, font: Float, content: @Composable () -> Unit) {
        rule.mainClock.autoAdvance = false
        rule.setContent { Frame(dark, font) { Box(Modifier.fillMaxSize()) { content() } } }
        rule.mainClock.advanceTimeBy(1000)
        rule.onNode(isDialog()).captureRoboImage(out(name))
    }

    @Test fun otpDialog() = dialogShot("audit-otp-dialog-font130", true, 1.3f) {
        OtpDialog(username = "truenas_admin", error = null, busy = false, onSubmit = {}, onCancel = {})
    }
    @Test fun otpDialogLight() = dialogShot("preview-otp-dialog-light", false, 1f) {
        OtpDialog(username = "truenas_admin", error = "Wrong code, try again.", busy = false, onSubmit = {}, onCancel = {})
    }
    @Test fun confirmDialog() = dialogShot("audit-confirm-dialog-font130", true, 1.3f) {
        ConfirmDialog("Shut down homenas?", "The NAS will power off. You will need physical access (or IPMI / Wake-on-LAN) to turn it back on.",
            "Shut down", destructive = true, icon = Icons.Rounded.RestartAlt, onConfirm = {}, onDismiss = {})
    }
}
