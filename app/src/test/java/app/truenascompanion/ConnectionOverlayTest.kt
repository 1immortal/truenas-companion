package app.truenascompanion

import app.truenascompanion.data.api.TrueNasException
import app.truenascompanion.data.repository.ConnectionState
import app.truenascompanion.ui.connection.CONNECTION_OVERLAY_GRACE_MS
import app.truenascompanion.ui.connection.ConnectionOverlayDecision
import app.truenascompanion.ui.connection.ConnectionOverlayUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionOverlayTest {

    private fun decide(
        connection: ConnectionState,
        elapsed: Long = CONNECTION_OVERLAY_GRACE_MS,
        hasSaved: Boolean = true,
        name: String? = "Home NAS",
        onEdit: Boolean = false,
    ) = ConnectionOverlayDecision.decide(hasSaved, name, connection, elapsed, onEdit)

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
