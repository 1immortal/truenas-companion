package app.truenascompanion.screenshots

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.truenascompanion.data.store.ThemeMode
import app.truenascompanion.data.update.UpdateChannel
import app.truenascompanion.ui.AppNavBar
import app.truenascompanion.ui.connection.ConnectionFailurePanel
import app.truenascompanion.ui.connection.ConnectionModalBarrier
import app.truenascompanion.ui.servers.MaskedCertificateFingerprint
import app.truenascompanion.ui.system.UpdateChannelRow
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

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xxhdpi", application = android.app.Application::class)
class V103ScreenshotTest {
    @get:Rule val rule = createComposeRule()
    private val dir = File(System.getProperty("screenshot.dir") ?: "build/screenshots").apply { mkdirs() }
    private fun out(name: String) = File(dir, "$name.png").absolutePath

    @Composable private fun Frame(dark: Boolean, content: @Composable () -> Unit) {
        val d = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(d.density, 1f)) {
            TrueNasTheme(themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT, dynamicColor = false) {
                Surface(color = MaterialTheme.colorScheme.background) { content() }
            }
        }
    }

    private fun advance() {
        repeat(6) { rule.mainClock.advanceTimeBy(300); shadowOf(android.os.Looper.getMainLooper()).idle() }
    }

    @Test fun overlayGrayedNav() {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            Frame(true) {
                Scaffold(
                    bottomBar = { AppNavBar(route = "dashboard", enabled = false) {} },
                ) { padding ->
                    Box(Modifier.fillMaxSize().padding(padding)) {
                        LazyColumn(Modifier.fillMaxSize().padding(16.dp)) {
                            item {
                                Text("Home NAS", style = MaterialTheme.typography.headlineSmall)
                                Text("Menus under the blur — tabs below are grayed out", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            items(listOf("Alerts", "Apps", "Storage")) { label ->
                                ListItem(headlineContent = { Text(label) }, trailingContent = { Switch(checked = label == "Alerts", onCheckedChange = {}) })
                            }
                        }
                        ConnectionModalBarrier {
                            ConnectionFailurePanel(
                                serverName = "Home NAS",
                                detail = "Make sure the NAS is online and that your phone can reach it — on Wi‑Fi at home, or through your VPN.",
                                onQuit = {},
                                onCheckConfig = {},
                                onRetry = {},
                            )
                        }
                    }
                }
            }
        }
        advance()
        rule.onRoot().captureRoboImage(out("v103_overlay_grayed_nav"))
    }

    @Test fun maskedCertificate() {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            Frame(true) {
                Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.TopCenter) {
                    Column {
                        Text("Trusted self-signed certificate", style = MaterialTheme.typography.titleSmall)
                        Text("Fingerprint is hidden until you tap Show. Forget still clears the pin.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        MaskedCertificateFingerprint("92fb06a9b958c4a08f0db43e1e4b9b2c6ee0a9cdf4a91ffd2421a11277f5e608")
                    }
                }
            }
        }
        advance()
        rule.onRoot().captureRoboImage(out("v103_masked_certificate"))
    }

    @Test fun updateChannelSetting() {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            Frame(true) {
                Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.TopCenter) {
                    UpdateChannelRow(channel = UpdateChannel.RELEASE, onSelect = {})
                }
            }
        }
        advance()
        rule.onRoot().captureRoboImage(out("v103_update_channel"))
    }
}
