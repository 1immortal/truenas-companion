package app.truenascompanion.screenshots

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import app.truenascompanion.data.api.TrueNasException
import app.truenascompanion.data.model.AlertItem
import app.truenascompanion.data.store.ThemeMode
import app.truenascompanion.ui.AppNavBar
import app.truenascompanion.ui.alerts.AlertCard
import app.truenascompanion.ui.components.EmptyContent
import app.truenascompanion.ui.components.GlowButton
import app.truenascompanion.ui.components.ScreenScaffold
import app.truenascompanion.ui.components.UiState
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

/** 1.8.1 previews, example data only: the Undo snackbar, a shared empty state and a shared error state. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xxhdpi", application = android.app.Application::class)
@OptIn(ExperimentalMaterial3Api::class)
class V181ScreenshotTest {
    @get:Rule val rule = createComposeRule()
    private val dir = File(System.getProperty("screenshot.dir") ?: "build/screenshots").apply { mkdirs() }
    private fun out(name: String) = File(dir, "v181_$name.png").absolutePath
    private val now = System.currentTimeMillis()

    private fun settle() { repeat(8) { rule.mainClock.advanceTimeBy(250); shadowOf(android.os.Looper.getMainLooper()).idle() } }

    private fun shot(name: String, dark: Boolean, content: @Composable () -> Unit) {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            TrueNasTheme(themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT, dynamicColor = false) {
                Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) { content() }
            }
        }
        settle()
        rule.onRoot().captureRoboImage(out(name))
    }

    private fun alert(uuid: String, level: String, text: String, minsAgo: Long) = AlertItem(uuid, level, text, null, now - minsAgo * 60_000L, false, false)

    @Composable private fun AlertsWithUndo() {
        val snackbar = remember { SnackbarHostState() }
        LaunchedEffect(Unit) { snackbar.showSnackbar("Alert dismissed", actionLabel = "Undo", withDismissAction = true, duration = SnackbarDuration.Indefinite) }
        Column {
            Box(Modifier.weight(1f)) {
                Scaffold(topBar = { TopAppBar(title = { Text("Alerts") }) }, snackbarHost = { SnackbarHost(snackbar) }) { p ->
                    LazyColumn(Modifier.padding(p), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        item {
                            Text("2 active", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp))
                        }
                        items(listOf(
                            alert("2", "WARNING", "SMART test on sdb (S/N EX4MPL3) failed: Short offline test failed at 10% remaining.", 38),
                            alert("3", "INFO", "An update is available for \"nextcloud\" application.", 300),
                        ), key = { it.uuid }) { AlertCard(it, onDismiss = {}) }
                    }
                }
            }
            AppNavBar("alerts") {}
        }
    }

    @Test fun undoSnackbarDark() = shot("undo_snackbar_dark", true) { AlertsWithUndo() }
    @Test fun undoSnackbarLight() = shot("undo_snackbar_light", false) { AlertsWithUndo() }

    @Test fun emptyAppsDark() = shot("empty_state_dark", true) {
        ScreenScaffold(
            title = "Apps", state = UiState.Success(emptyList<String>()), onRetry = {},
            empty = EmptyContent(Icons.Rounded.Apps, "No apps installed", "Apps you install from the TrueNAS catalog will appear here.") {
                GlowButton(onClick = {}) { Text("Browse the catalog") }
            },
        ) { }
    }

    @Test fun errorDark() = shot("error_state_dark", true) {
        ScreenScaffold<List<String>>(
            title = "Snapshots", onBack = {}, onRetry = {},
            state = UiState.Error("The NAS didn't answer at nas.example.com. Check that you're on the right network, then try again.",
                TrueNasException.Unreachable("unreachable")),
        ) { }
    }

    @Test fun errorLight() = shot("error_state_light", false) {
        ScreenScaffold<List<String>>(
            title = "Certificates", onBack = {}, onRetry = {},
            state = UiState.Error("Your session has expired.", TrueNasException.LoginRequired()),
        ) { }
    }
}
