package app.truenascompanion.screenshots

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import app.truenascompanion.ui.connection.ConnectionConnectingPanel
import app.truenascompanion.ui.connection.ConnectionFailurePanel
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
class V101ScreenshotTest {
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

    private fun shot(name: String, dark: Boolean = true, content: @Composable () -> Unit) {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            Frame(dark) {
                Box(Modifier.fillMaxSize()) {
                    // Fake dashboard chrome under the overlay (example data only).
                    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.TopStart) {
                        Text("Home NAS", style = MaterialTheme.typography.headlineSmall)
                        Text(
                            "Live · example data behind the blur",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 36.dp),
                        )
                    }
                    content()
                }
            }
        }
        repeat(6) { rule.mainClock.advanceTimeBy(300); shadowOf(android.os.Looper.getMainLooper()).idle() }
        rule.onRoot().captureRoboImage(out(name))
    }

    @Test fun failureDark() = shot("v101_connection_failure") {
        ConnectionFailurePanel(
            serverName = "Home NAS",
            detail = "Make sure the NAS is online and that your phone can reach it — on Wi‑Fi at home, or through your VPN.",
            onQuit = {},
            onCheckConfig = {},
            onRetry = {},
        )
    }

    @Test fun failureLight() = shot("v101_connection_failure_light", dark = false) {
        ConnectionFailurePanel(
            serverName = "Home NAS",
            detail = "Make sure the NAS is online and that your phone can reach it — on Wi‑Fi at home, or through your VPN.",
            onQuit = {},
            onCheckConfig = {},
            onRetry = {},
        )
    }

    @Test fun connectingDark() = shot("v101_connecting") {
        ConnectionConnectingPanel(serverName = "Home NAS")
    }
}
