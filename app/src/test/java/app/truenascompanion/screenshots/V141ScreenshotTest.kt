package app.truenascompanion.screenshots

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.ListAlt
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.EventRepeat
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.MiscellaneousServices
import androidx.compose.material.icons.rounded.Policy
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.truenascompanion.data.model.AuthMethod
import app.truenascompanion.data.model.BootEnvironment
import app.truenascompanion.data.model.Health
import app.truenascompanion.data.model.NasUpdateStatus
import app.truenascompanion.data.model.ServerConfig
import app.truenascompanion.data.security.LockSettings
import app.truenascompanion.data.security.RelockDelay
import app.truenascompanion.data.store.AppearanceSettings
import app.truenascompanion.data.store.ConnectionTimeoutPrefs
import app.truenascompanion.data.store.NotificationPrefs
import app.truenascompanion.data.store.ThemeMode
import app.truenascompanion.data.update.UpdateChannel
import app.truenascompanion.data.update.UpdateResult
import app.truenascompanion.ui.components.ElevatedSection
import app.truenascompanion.ui.components.IconBadge
import app.truenascompanion.ui.components.SectionTitle
import app.truenascompanion.ui.components.StatusChip
import app.truenascompanion.ui.components.UiState
import app.truenascompanion.ui.lock.Biometrics
import app.truenascompanion.ui.lock.SecuritySection
import app.truenascompanion.ui.notifications.PhoneAlertsSection
import app.truenascompanion.ui.system.AboutSection
import app.truenascompanion.ui.system.AppUpdateContent
import app.truenascompanion.ui.system.AppearanceSection
import app.truenascompanion.ui.system.BootEnvCard
import app.truenascompanion.ui.system.ConnectionTimeoutSection
import app.truenascompanion.ui.system.HubHeader
import app.truenascompanion.ui.system.HubSummary
import app.truenascompanion.ui.system.NasUpdateCard
import app.truenascompanion.ui.system.PowerSection
import app.truenascompanion.ui.system.ShellEntry
import app.truenascompanion.ui.system.SystemHubContent
import app.truenascompanion.ui.system.hubSubtitles
import app.truenascompanion.ui.system.PowerButtons
import app.truenascompanion.ui.theme.TrueNasTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * 1.4.1 previews (example data only): the old System tab rebuilt from the real section composables
 * ("before"), the new compact hub ("after") and a few of its pages. Heights are written to v141_heights.txt.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xxhdpi", application = android.app.Application::class)
class V141ScreenshotTest {
    @get:Rule val rule = createComposeRule()
    private val dir = File(System.getProperty("screenshot.dir") ?: "build/screenshots").apply { mkdirs() }
    private fun out(name: String) = File(dir, "$name.png").absolutePath

    @Composable private fun Frame(dark: Boolean, content: @Composable () -> Unit) {
        val d = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(d.density, 1f)) {
            TrueNasTheme(themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT, dynamicColor = false) {
                Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) { content() }
            }
        }
    }

    private fun settle() {
        repeat(8) { rule.mainClock.advanceTimeBy(250); shadowOf(android.os.Looper.getMainLooper()).idle() }
    }

    private fun shot(name: String, dark: Boolean = true, content: @Composable () -> Unit) {
        rule.mainClock.autoAdvance = false
        rule.setContent { Frame(dark, content) }
        settle(); rule.onRoot().captureRoboImage(out(name))
    }

    /** Full-length capture of the tagged column; records its height in dp. */
    private fun longShot(name: String, dark: Boolean = true, content: @Composable () -> Unit) {
        rule.mainClock.autoAdvance = false
        rule.setContent { Frame(dark) { Column(Modifier.fillMaxWidth().wrapContentHeight().testTag("page")) { content() } } }
        settle()
        val node = rule.onNodeWithTag("page")
        val px = node.fetchSemanticsNode().size.height
        val density = rule.density.density
        File(dir, "v141_heights.txt").appendText("$name ${px}px ${"%.0f".format(px / density)}dp\n")
        node.captureRoboImage(out(name))
    }

    // ---------- example data ----------

    private val server = ServerConfig("s1", "homenas", "https://nas.example.com", authMethod = AuthMethod.PASSWORD)
    private val update = NasUpdateStatus(
        code = "NORMAL", currentTrain = "TrueNAS-SCALE-Goldeye", currentProfile = "GENERAL",
        newVersion = "25.10.5", releaseNotes = "Security fixes and SMB improvements.", releaseNotesUrl = null,
        changelog = "- Fix SMB share reconnect\n- Better scrub progress", errorReason = null,
        downloadPercent = null, downloadDescription = null,
    )
    private val boots = listOf(
        BootEnvironment("25.10.4", "boot-pool/ROOT/25.10.4", true, true, 1_720_000_000_000, 1_200_000_000, "1.12G", true, true),
        BootEnvironment("25.10.3", "boot-pool/ROOT/25.10.3", false, false, 1_700_000_000_000, 1_100_000_000, "1.02G", false, true),
        BootEnvironment("25.10.2", "boot-pool/ROOT/25.10.2", false, false, 1_680_000_000_000, 1_050_000_000, "1.00G", false, true),
    )
    private val prefs = NotificationPrefs(enabledServers = setOf("s1"), intervalMinutes = 15)
    private val lock = LockSettings(enabled = true, relock = RelockDelay.ONE_MINUTE)
    private val header = HubHeader("homenas", "nas.example.com", "25.10.4", 1_036_800, online = true)
    private val summary = HubSummary(
        connected = true, nasUpdate = update, runningJobs = 1, serverCount = 2, servicesRunning = 3, cronJobs = 2, initScripts = 1,
        certsExpiring = 0, certsExpired = 0, alertsMode = HubSummary.AlertsMode.PERIODIC, alertsIntervalMinutes = 15, lockOn = true,
        giveUpMs = ConnectionTimeoutPrefs.DEFAULT_MS, themeMode = ThemeMode.SYSTEM, appVersion = "1.4.1",
        appUpdate = UpdateResult.UpToDate("1.4.1"),
    )

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable private fun Bar(title: String, back: Boolean = false, search: Boolean = false) = TopAppBar(
        title = { Text(title) },
        navigationIcon = { if (back) IconButton(onClick = {}) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, null) } },
        actions = { if (search) IconButton(onClick = {}) { Icon(Icons.Rounded.Search, null) } },
    )

    // ---------- before: the 1.4.0 System tab, same order and components ----------

    @Composable private fun LinkCard(icon: ImageVector, title: String, subtitle: String) = ElevatedSection(onClick = {}) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(icon); Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, tint = MaterialTheme.colorScheme.primary)
        }
    }

    @Composable private fun ManageRow(icon: ImageVector, title: String, subtitle: String) = Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).clickable {}.padding(horizontal = 12.dp, vertical = 12.dp),
    ) {
        IconBadge(icon, size = 36.dp); Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, tint = MaterialTheme.colorScheme.primary)
    }

    @Composable private fun ManageDivider() = HorizontalDivider(Modifier.padding(horizontal = 12.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

    @Composable private fun LegacySystemTab() {
        Bar("System")
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ElevatedSection(onClick = {}) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconBadge(Icons.Rounded.Dns); Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("homenas", style = MaterialTheme.typography.titleMedium)
                        Text("nas.example.com", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(6.dp)); StatusChip(Health.HEALTHY, "25.10.4")
                    }
                    Text("Switch", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, tint = MaterialTheme.colorScheme.primary)
                }
            }
            LinkCard(Icons.Rounded.Dns, "All servers", "Status and alerts for every saved NAS")
            LinkCard(Icons.AutoMirrored.Rounded.ListAlt, "Tasks", "Running and recent jobs with live progress")
            ShellEntry(enabled = true) {}
            SectionTitle("Manage")
            ElevatedSection(contentPadding = 6.dp) {
                ManageRow(Icons.Rounded.Group, "Users & groups", "Accounts, passwords, SSH keys and groups"); ManageDivider()
                ManageRow(Icons.Rounded.Insights, "Reports", "CPU, memory, network, disks and temperatures over time"); ManageDivider()
                ManageRow(Icons.Rounded.Policy, "Audit log", "Who signed in and what changed"); ManageDivider()
                ManageRow(Icons.Rounded.VerifiedUser, "Certificates", "Expiry, ACME, imports and the web UI certificate"); ManageDivider()
                ManageRow(Icons.Rounded.MiscellaneousServices, "Services", "Start, stop and configure SSH, SMB, NFS, UPS, SNMP, FTP…"); ManageDivider()
                ManageRow(Icons.Rounded.EventRepeat, "Scheduled tasks", "Cron jobs and init/shutdown scripts")
            }
            SectionTitle("TrueNAS update")
            NasUpdateCard(UiState.Success(update), checking = false, job = null, message = null, onCheck = {}, onApply = {}, onNotes = {})
            SectionTitle("Boot environments")
            BootEnvCard(UiState.Success(boots), emptySet(), null, {}, {}, {}, {})
            SectionTitle("Power")
            ElevatedSection {
                Text("Restart or turn off the NAS. Apps, shares and VMs will be unavailable meanwhile.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                PowerButtons(enabled = true, onReboot = {}, onShutdown = {})
            }
            SectionTitle("Connection")
            ConnectionTimeoutSection(ConnectionTimeoutPrefs.DEFAULT_MS) {}
            SectionTitle("Security")
            SecuritySection(lock, Biometrics.Availability.READY, {}, {}, {}, {}, {})
            SectionTitle("Phone alerts")
            PhoneAlertsSection(server, prefs, canNotify = true, batteryOk = true, onToggle = {}, onUpdate = {}, onAllowNotifications = {}, onBattery = {}, onChannels = {}, onTest = {})
            SectionTitle("Appearance")
            AppearanceSection(AppearanceSettings(), {}, {})
            SectionTitle("About")
            AboutCard()
            Spacer(Modifier.height(24.dp))
        }
    }

    @Composable private fun AboutCard() = AboutSection("1.4.1", onOpenWebUi = {}) {
        AppUpdateContent(UpdateResult.UpToDate("1.4.1"), checking = false, auto = true, channel = UpdateChannel.RELEASE, onCheck = {}, onView = {}, onChannel = {}, onAutoCheck = {})
    }

    @Test @Config(qualifiers = "w360dp-h5000dp-xxhdpi")
    fun before() = longShot("v141_system_before") { LegacySystemTab() }

    // ---------- after: the hub ----------

    @Composable private fun Hub(searchOpen: Boolean = false, query: String = "") {
        Bar("System", search = !searchOpen)
        SystemHubContent(header, hubSubtitles(summary), onOpen = {}, onSwitchServer = {}, searchOpen = searchOpen, query = query)
    }

    @Test @Config(qualifiers = "w360dp-h2000dp-xxhdpi")
    fun after() = longShot("v141_system_after") { Hub() }

    @Test @Config(qualifiers = "w360dp-h2000dp-xxhdpi")
    fun afterLight() = longShot("v141_system_after_light", dark = false) { Hub() }

    /** The hub on a regular 360×800 dp phone with the bottom tabs, i.e. what you see without scrolling. */
    @Test fun afterOnPhone() = shot("v141_system_after_phone") {
        Column(Modifier.fillMaxSize()) {
            Bar("System", search = true)
            Box(Modifier.weight(1f)) { SystemHubContent(header, hubSubtitles(summary), onOpen = {}, onSwitchServer = {}) }
            app.truenascompanion.ui.AppNavBar("system") {}
        }
    }

    @Test fun search() = shot("v141_system_search") { Column { Hub(searchOpen = true, query = "theme") } }

    // ---------- pages ----------

    @Test @Config(qualifiers = "w360dp-h1400dp-xxhdpi")
    fun updatesPage() = longShot("v141_page_updates") {
        Bar("Updates", back = true)
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionTitle("TrueNAS update")
            NasUpdateCard(UiState.Success(update), checking = false, job = null, message = null, onCheck = {}, onApply = {}, onNotes = {})
            SectionTitle("Boot environments")
            BootEnvCard(UiState.Success(boots), emptySet(), null, {}, {}, {}, {})
        }
    }

    @Test fun powerPage() = shot("v141_page_power") {
        Column {
            Bar("Power", back = true)
            Column(Modifier.padding(16.dp)) { PowerSection("homenas", enabled = true, onReboot = {}, onShutdown = {}) }
        }
    }

    @Test fun aboutPage() = shot("v141_page_about") {
        Column {
            Bar("About", back = true)
            Column(Modifier.padding(16.dp)) { AboutCard() }
        }
    }

    @Test fun appearancePage() = shot("v141_page_appearance", dark = false) {
        Column {
            Bar("Appearance", back = true)
            Column(Modifier.padding(16.dp)) { AppearanceSection(AppearanceSettings(ThemeMode.LIGHT), {}, {}) }
        }
    }

    @Test @Config(qualifiers = "w360dp-h2000dp-xxhdpi")
    fun hubLargeFont() {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            val d = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density, 1.3f)) {
                TrueNasTheme(themeMode = ThemeMode.DARK, dynamicColor = false) {
                    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
                        Column(Modifier.fillMaxWidth().wrapContentHeight().testTag("page")) { Hub() }
                    }
                }
            }
        }
        settle(); rule.onNodeWithTag("page").captureRoboImage(out("v141_system_after_font130"))
    }
}
