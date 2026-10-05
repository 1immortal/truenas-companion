package app.truenascompanion

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Switch
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import app.truenascompanion.data.api.TrueNasException
import app.truenascompanion.data.repository.ConnectionState
import app.truenascompanion.data.store.ConnectionTimeoutPrefs
import app.truenascompanion.data.store.ThemeMode
import app.truenascompanion.ui.connection.CONNECTION_OVERLAY_GRACE_MS
import app.truenascompanion.ui.connection.ConnectionFailurePanel
import app.truenascompanion.ui.connection.ConnectionModalBarrier
import app.truenascompanion.ui.connection.ConnectionOverlayDecision
import app.truenascompanion.ui.connection.ConnectionOverlayUi
import app.truenascompanion.ui.theme.TrueNasTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

class ConnectionOverlayTest {

    private fun decide(
        connection: ConnectionState,
        elapsed: Long = CONNECTION_OVERLAY_GRACE_MS,
        hasSaved: Boolean = true,
        name: String? = "Home NAS",
        onEdit: Boolean = false,
        graceMs: Long = CONNECTION_OVERLAY_GRACE_MS,
    ) = ConnectionOverlayDecision.decide(hasSaved, name, connection, elapsed, onEdit, graceMs)

    @Test fun noOverlayWithoutSavedServer() {
        assertEquals(ConnectionOverlayUi.None, decide(ConnectionState.Failed("x", TrueNasException.Unreachable("x")), hasSaved = false))
        assertEquals(ConnectionOverlayUi.None, decide(ConnectionState.Failed("x", TrueNasException.Unreachable("x")), name = null))
        assertEquals(ConnectionOverlayUi.None, decide(ConnectionState.Connecting, hasSaved = false))
    }

    @Test fun noOverlayOnServerEditScreen() {
        assertEquals(
            ConnectionOverlayUi.None,
            decide(ConnectionState.Failed("x", TrueNasException.Unreachable("x")), onEdit = true),
        )
    }

    @Test fun loginRequiredUsesAuthDialogNotOverlay() {
        assertEquals(
            ConnectionOverlayUi.None,
            decide(ConnectionState.Failed("Sign in", TrueNasException.LoginRequired())),
        )
    }

    @Test fun connectingShowsConnectingChrome() {
        val ui = decide(ConnectionState.Connecting, elapsed = 0)
        assertEquals(ConnectionOverlayUi.Connecting("Home NAS"), ui)
    }

    @Test fun failedWithinGraceStillShowsConnecting() {
        val ui = decide(
            ConnectionState.Failed("down", TrueNasException.Unreachable("down")),
            elapsed = 500,
        )
        assertEquals(ConnectionOverlayUi.Connecting("Home NAS"), ui)
    }

    @Test fun failedAfterGraceShowsFailure() {
        val ui = decide(
            ConnectionState.Failed("down", TrueNasException.Timeout()),
            elapsed = CONNECTION_OVERLAY_GRACE_MS,
        )
        assertTrue(ui is ConnectionOverlayUi.Failed)
        ui as ConnectionOverlayUi.Failed
        assertEquals("Home NAS", ui.serverName)
        assertTrue(ui.detail.contains("online") || ui.detail.contains("answer"))
    }

    @Test fun customGiveUpWindowHonoured() {
        val failed = ConnectionState.Failed("down", TrueNasException.Unreachable("down"))
        assertEquals(
            ConnectionOverlayUi.Connecting("Home NAS"),
            decide(failed, elapsed = 29_000, graceMs = 30_000),
        )
        assertTrue(decide(failed, elapsed = 30_000, graceMs = 30_000) is ConnectionOverlayUi.Failed)
        assertTrue(decide(failed, elapsed = 120_000, graceMs = 120_000) is ConnectionOverlayUi.Failed)
    }

    @Test fun defaultGraceMatchesTimeoutPref() {
        assertEquals(ConnectionTimeoutPrefs.DEFAULT_MS, CONNECTION_OVERLAY_GRACE_MS)
        assertTrue(ConnectionTimeoutPrefs.DEFAULT_MS in ConnectionTimeoutPrefs.PRESETS_MS)
    }

    @Test fun connectedAndIdleHideOverlay() {
        assertEquals(ConnectionOverlayUi.None, decide(ConnectionState.Idle))
        assertEquals(ConnectionOverlayUi.None, decide(ConnectionState.NoServer))
        assertEquals(
            ConnectionOverlayUi.None,
            decide(ConnectionState.Connected(app.truenascompanion.data.model.ApiFlavor.WEBSOCKET, null)),
        )
    }

    @Test fun friendlyDetailAvoidsTechnicalDump() {
        val cert = ConnectionOverlayDecision.friendlyDetail(
            ConnectionState.Failed("cert", TrueNasException.UntrustedCertificate(null, null)),
        )
        assertTrue(cert.contains("certificate", ignoreCase = true))
        assertTrue(!cert.contains("SSLPeer") && !cert.contains("stack"))

        val unreachable = ConnectionOverlayDecision.friendlyDetail(
            ConnectionState.Failed("x", TrueNasException.Unreachable("Could not connect to 192.168.1.10:443")),
        )
        assertTrue(unreachable.contains("VPN") || unreachable.contains("Wi"))
        assertTrue(!unreachable.contains("192.168"))
    }
}

/**
 * Compose UI: the modal barrier must stop pointer events reaching toggles/buttons under the blur.
 * Uses real touch injection (not semantics performClick, which can bypass z-order).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xxhdpi", application = android.app.Application::class)
class ConnectionOverlayBlockingTest {
    @get:Rule val rule = createComposeRule()

    @Test fun touchesOnScrimDoNotToggleUnderlaySwitch() {
        var underChecked by mutableStateOf(false)
        var underClicks = 0
        rule.setContent {
            TrueNasTheme(themeMode = ThemeMode.DARK, dynamicColor = false) {
                Box(Modifier.fillMaxSize()) {
                    Switch(
                        checked = underChecked,
                        onCheckedChange = { underChecked = it; underClicks++ },
                        modifier = Modifier.testTag("under_switch"),
                    )
                    ConnectionModalBarrier {
                        ConnectionFailurePanel(
                            serverName = "Home NAS",
                            detail = "Make sure the NAS is online.",
                            onQuit = {},
                            onCheckConfig = {},
                            onRetry = {},
                        )
                    }
                }
            }
        }
        // Semantics click can pierce overlays; inject a real touch at the switch's centre.
        val switchCenter = rule.onNodeWithTag("under_switch").fetchSemanticsNode().boundsInRoot.let {
            Offset(it.left + it.width / 2f, it.top + it.height / 2f)
        }
        rule.onRoot().performTouchInput { click(switchCenter) }
        rule.waitForIdle()
        assertFalse("underlay Switch must stay unchecked when overlay is up", underChecked)
        assertEquals("underlay Switch must not receive clicks through the barrier", 0, underClicks)
    }

    @Test fun overlayActionsStillReceivable() {
        var check = 0
        var quit = 0
        var retry = 0
        rule.setContent {
            TrueNasTheme(themeMode = ThemeMode.DARK, dynamicColor = false) {
                ConnectionModalBarrier {
                    ConnectionFailurePanel(
                        serverName = "Home NAS",
                        detail = "Make sure the NAS is online.",
                        onQuit = { quit++ },
                        onCheckConfig = { check++ },
                        onRetry = { retry++ },
                    )
                }
            }
        }
        rule.onNodeWithTag("connection_check_settings").performClick()
        rule.onNodeWithTag("connection_quit").performClick()
        rule.onNodeWithTag("connection_try_again").performClick()
        assertEquals(1, check)
        assertEquals(1, quit)
        assertEquals(1, retry)
    }
}
