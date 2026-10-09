package app.truenascompanion

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import app.truenascompanion.data.model.Dataset
import app.truenascompanion.data.store.ThemeMode
import app.truenascompanion.ui.connection.ConnectionFailurePanel
import app.truenascompanion.ui.connection.ConnectionModalBarrier
import app.truenascompanion.ui.connection.connectionUnderlay
import app.truenascompanion.ui.storage.DatasetDeleteBody
import app.truenascompanion.ui.storage.DatasetDeleteImpact
import app.truenascompanion.ui.theme.TrueNasTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 1.7.1: accessibility fixes from the UX review. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class V171ComposeTest {
    @get:Rule val rule = createComposeRule()

    @Test fun underlayIsHiddenFromAccessibilityWhileOverlayIsUp() {
        var fired = 0
        rule.setContent {
            TrueNasTheme(themeMode = ThemeMode.DARK, dynamicColor = false) {
                Box(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize().connectionUnderlay(true)) {
                        Button(onClick = { fired++ }) { Text("Shut down NAS") }
                    }
                    ConnectionModalBarrier { ConnectionFailurePanel("Home NAS", "detail", {}, {}, {}) }
                }
            }
        }
        rule.waitForIdle()
        // Merged tree = what accessibility services see (clearAndSetSemantics drops the subtree there).
        assertEquals(0, rule.onAllNodes(hasText("Shut down NAS")).fetchSemanticsNodes().size)
        assertEquals(0, fired)
        // The overlay announces itself as a pane.
        assertTrue(
            rule.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "Connection")).fetchSemanticsNodes().isNotEmpty()
        )
    }

    @Test fun underlayIsReachableWhenOverlayIsDown() {
        rule.setContent {
            TrueNasTheme(themeMode = ThemeMode.DARK, dynamicColor = false) {
                Box(Modifier.fillMaxSize().connectionUnderlay(false)) { Button(onClick = {}) { Text("Shut down NAS") } }
            }
        }
        assertEquals(1, rule.onAllNodes(hasText("Shut down NAS")).fetchSemanticsNodes().size)
    }

    @Test @Config(qualifiers = "w891dp-h411dp-land-xxhdpi") fun failureCardScrollsInLandscapeAtDoubleFont() {
        var retry = 0; var switch = 0
        rule.setContent {
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(3f, fontScale = 2f),
            ) {
                TrueNasTheme(themeMode = ThemeMode.DARK, dynamicColor = false) {
                    ConnectionModalBarrier {
                        ConnectionFailurePanel(
                            "Home NAS", "Make sure the NAS is online and that your phone can reach it.",
                            onQuit = {}, onCheckConfig = {}, onRetry = { retry++ }, onSwitchServer = { switch++ },
                        )
                    }
                }
            }
        }
        rule.onNodeWithTag("connection_try_again").performClick()
        rule.onNodeWithTag("connection_switch_server").performScrollTo().performClick()
        rule.onNodeWithTag("connection_quit").performScrollTo()
        assertEquals(1, retry)
        assertEquals(1, switch)
    }

    @Test fun switchServerOnlyWithMultipleServers() {
        rule.setContent {
            TrueNasTheme(themeMode = ThemeMode.DARK, dynamicColor = false) {
                ConnectionFailurePanel("Home NAS", "detail", onQuit = {}, onCheckConfig = {}, onRetry = {})
            }
        }
        assertEquals(0, rule.onAllNodes(androidx.compose.ui.test.hasTestTag("connection_switch_server")).fetchSemanticsNodes().size)
    }

    @Test fun datasetDeleteCheckboxRowsAreLabelledToggles() {
        var recursive = false
        val d = Dataset("tank/media", "tank", "FILESYSTEM", 5L shl 30, 1L shl 40, false, false, "/mnt/tank/media")
        rule.setContent {
            TrueNasTheme(themeMode = ThemeMode.DARK, dynamicColor = false) {
                DatasetDeleteBody(
                    d, DatasetDeleteImpact(d.used, 2, listOf("/mnt/tank/media"), snapshots = 14),
                    recursive, { recursive = it }, false, {}, "", {},
                )
            }
        }
        rule.onNodeWithTag("dataset_delete_recursive").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.ToggleableState))
        rule.onNodeWithTag("dataset_delete_recursive").performClick()
        assertTrue(recursive)
        assertEquals(1, rule.onAllNodes(hasText("14")).fetchSemanticsNodes().size)
        assertEquals(1, rule.onAllNodes(hasText("Also delete 2 child dataset(s) (required)")).fetchSemanticsNodes().size)
    }
}
