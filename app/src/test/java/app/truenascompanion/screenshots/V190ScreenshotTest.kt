package app.truenascompanion.screenshots

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import app.truenascompanion.NetworkSamples
import app.truenascompanion.data.model.PendingNetworkChanges
import app.truenascompanion.data.store.ThemeMode
import app.truenascompanion.ui.components.UiState
import app.truenascompanion.ui.network.InterfaceDetailContent
import app.truenascompanion.ui.network.NetworkContent
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

/** 1.9.0 previews, example data only: the view-only Network page, unfinished changes, and an interface's details. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xxhdpi", application = android.app.Application::class)
class V190ScreenshotTest {
    @get:Rule val rule = createComposeRule()
    private val dir = File(System.getProperty("screenshot.dir") ?: "build/screenshots").apply { mkdirs() }
    private fun out(name: String) = File(dir, "v190_$name.png").absolutePath

    private fun shot(name: String, dark: Boolean, content: @Composable () -> Unit) {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            TrueNasTheme(themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT, dynamicColor = false) {
                Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) { content() }
            }
        }
        repeat(8) { rule.mainClock.advanceTimeBy(250); shadowOf(android.os.Looper.getMainLooper()).idle() }
        rule.onRoot().captureRoboImage(out(name))
    }

    @Test fun networkDark() = shot("network_dark", true) {
        NetworkContent(UiState.Success(NetworkSamples.OVERVIEW), NetworkSamples.IPMI_LIST, NetworkSamples.RATES, onRetry = {}, onBack = {}, onOpenInterface = {}, onReports = {})
    }

    @Test fun networkPendingLight() = shot("network_pending_light", false) {
        NetworkContent(
            UiState.Success(NetworkSamples.OVERVIEW.copy(pending = PendingNetworkChanges(true, null))),
            emptyList(), NetworkSamples.RATES, onRetry = {}, onBack = {}, onOpenInterface = {}, onReports = {},
        )
    }

    @Test fun interfaceDetailDark() = shot("interface_detail_dark", true) {
        InterfaceDetailContent(NetworkSamples.BOND, NetworkSamples.RATES["bond0"], onBack = {}, onReports = {})
    }
}
