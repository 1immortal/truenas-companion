package app.truenascompanion

import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.truenascompanion.data.api.TrueNasException
import app.truenascompanion.data.api.userMessage
import app.truenascompanion.data.model.Health
import app.truenascompanion.data.model.Pool
import app.truenascompanion.data.model.SystemInfo
import app.truenascompanion.data.store.ThemeMode
import app.truenascompanion.ui.components.CheckRow
import app.truenascompanion.ui.components.FullScreenEditorBody
import app.truenascompanion.ui.dashboard.AlertsSummary
import app.truenascompanion.ui.dashboard.AppsSummary
import app.truenascompanion.ui.dashboard.DashboardData
import app.truenascompanion.ui.dashboard.DashboardSummary
import app.truenascompanion.ui.system.SettingRow
import app.truenascompanion.ui.theme.TrueNasTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Palette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 1.8.0: editors, friendly errors, Home summary and TalkBack rows. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h740dp-xxhdpi")
class V180UxTest {
    @get:Rule val rule = createComposeRule()

    @Test fun smbPurposesUseWebUiWording() {
        org.junit.Assert.assertEquals("Time Machine", app.truenascompanion.ui.storage.smbPurposeLabel("TIMEMACHINE_SHARE"))
        org.junit.Assert.assertEquals("Multiprotocol", app.truenascompanion.ui.storage.smbPurposeLabel("MULTIPROTOCOL_SHARE"))
        org.junit.Assert.assertEquals("Private datasets", app.truenascompanion.ui.storage.smbPurposeLabel("PRIVATE_DATASETS_SHARE"))
    }

    @Test fun middlewareErrorsReadLikeSentences() {
        assertEquals("Port is in use", TrueNasException.Rpc(22, "EINVAL", "[EINVAL] ssh_update.tcpport: Port is in use").userMessage())
        assertEquals("Failed 'up' action", TrueNasException.JobFailed("[EFAULT] Failed 'up' action\nTraceback (most recent call last):").userMessage())
        assertEquals("It no longer exists on the NAS. Refresh and try again.", TrueNasException.Rpc(2, "ENOENT", "").userMessage())
        assertEquals("TrueNAS couldn't do that (error 99).", TrueNasException.Rpc(99, null, "  ").userMessage())
        // Plain text and paths stay as they are.
        assertEquals("/mnt/tank/x: path does not exist", TrueNasException.Rpc(2, "ENOENT", "[ENOENT] /mnt/tank/x: path does not exist").userMessage())
    }

    @Test fun lowLevelErrorsAreFriendly() {
        assertTrue(java.net.UnknownHostException("nas.example.com").userMessage().startsWith("Host not found"))
        assertTrue(kotlinx.serialization.SerializationException("x").userMessage().contains("doesn't understand"))
        assertEquals("Something went wrong (IllegalStateException).", IllegalStateException().userMessage())
    }

    private val sys = SystemInfo("nas", "TrueNAS 25.10.1", 3600, null, "Example CPU", 8, 32L shl 30, emptyList(), null, null)
    private fun pool(name: String, healthy: Boolean) =
        Pool(1, name, if (healthy) "ONLINE" else "DEGRADED", healthy, false, null, 8L shl 40, 3L shl 40, 5L shl 40, null, null, null, null, null, emptyList())

    @Test fun homeSummaryAnswersIsMyNasOk() {
        val ok = DashboardData(loading = false, system = sys, pools = listOf(pool("tank", true), pool("fast", true)),
            alerts = AlertsSummary(0, Health.HEALTHY, null), apps = AppsSummary(5, 5, 0, 0))
        assertEquals(listOf(Health.HEALTHY to "2 pools healthy", Health.HEALTHY to "No alerts", Health.HEALTHY to "5 of 5 apps running"), DashboardSummary.items(ok))
        val bad = ok.copy(pools = listOf(pool("tank", false)), alerts = AlertsSummary(2, Health.CRITICAL, null), apps = AppsSummary(5, 4, 0, 1))
        assertEquals(listOf(Health.WARNING to "1 pool needs attention", Health.CRITICAL to "2 alerts", Health.WARNING to "1 of 5 apps need attention"), DashboardSummary.items(bad))
        assertEquals(emptyList<Pair<Health, String>>(), DashboardSummary.items(DashboardData()))
    }

    @Test fun editorAsksBeforeDiscardingChanges() {
        var closed = 0
        rule.setContent {
            TrueNasTheme(themeMode = ThemeMode.LIGHT, dynamicColor = false) {
                var name by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
                FullScreenEditorBody("New SMB share", "Create", canSave = name.isNotBlank(), dirty = name.isNotEmpty(), onSave = {}, onDismiss = { closed++ }) {
                    androidx.compose.material3.Button(onClick = { name = "media" }) { Text("Type") }
                }
            }
        }
        rule.onNodeWithText("Type").performClick()
        rule.onNode(hasContentDescription("Close")).performClick()
        rule.onNodeWithText("Discard changes?").assertExists()
        assertEquals(0, closed)
        rule.onNodeWithText("Discard").performClick()
        assertEquals(1, closed)
    }

    @Test fun cleanEditorClosesStraightAway() {
        var closed = 0
        rule.setContent {
            TrueNasTheme(themeMode = ThemeMode.LIGHT, dynamicColor = false) {
                FullScreenEditorBody("New NFS share", "Create", canSave = false, dirty = false, onSave = {}, onDismiss = { closed++ }) { Text("form") }
            }
        }
        rule.onNode(hasContentDescription("Close")).performClick()
        assertEquals(1, closed)
        assertEquals(0, rule.onAllNodes(hasText("Discard changes?")).fetchSemanticsNodes().size)
    }

    @Test fun checkAndSwitchRowsAreSingleControls() {
        var ro by mutableStateOf(false)
        var dyn by mutableStateOf(false)
        rule.setContent {
            TrueNasTheme(themeMode = ThemeMode.DARK, dynamicColor = false) {
                androidx.compose.foundation.layout.Column {
                    CheckRow("Read-only", ro, { ro = it })
                    SettingRow(Icons.Rounded.Palette, "Dynamic color", "Use wallpaper colours", checked = dyn, onCheckedChange = { dyn = it })
                }
            }
        }
        val checkbox = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox) and hasText("Read-only")
        rule.onNode(checkbox).assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.Off)).performClick()
        assertTrue(ro)
        val switch = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch) and hasText("Dynamic color")
        rule.onNode(switch).performClick()
        assertTrue(dyn)
    }

    @Test fun hubGroupLabelsAreHeadings() {
        rule.setContent {
            TrueNasTheme(themeMode = ThemeMode.DARK, dynamicColor = false) {
                app.truenascompanion.ui.system.SystemHubContent(
                    app.truenascompanion.ui.system.HubHeader("homenas", "nas.example.com", "25.10.4", 1_036_800, online = true),
                    emptyMap(), onOpen = {}, onSwitchServer = {},
                )
            }
        }
        rule.onNodeWithText("SERVER").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
    }
}
