package app.truenascompanion.ui.connection

import android.view.accessibility.AccessibilityManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.truenascompanion.data.model.Route
import app.truenascompanion.data.repository.ConnectionState
import kotlinx.coroutines.delay

/**
 * 1.8.1 (accessibility): spoken connection updates for TalkBack: "Connecting…", "Connected via home address",
 * "Connection lost" and the reason a connection failed.
 *
 * Not noisy by design:
 * - a state is announced only once it has lasted a moment ([settleMs]), so a quick connect or a short blip says nothing;
 * - only changes are announced (the same sentence is never repeated back to back);
 * - a plain start-up (connected quickly) and going to the background (the app disconnects on purpose) stay silent.
 */
class ConnectionAnnouncer {
    enum class Phase { IDLE, CONNECTING, CONNECTED, FAILED }

    private var last: Phase = Phase.IDLE
    private var lastRoute: Route? = null
    private var lastSpoken: String? = null
    /** The failure already spoken; retries that fail the same way stay quiet until a connection succeeds. */
    private var lastFailure: String? = null

    /** Called when [phase] has lasted [settleMs]; returns what to say, or null. */
    fun settle(phase: Phase, route: Route?, serverName: String?, error: String?): String? {
        val previous = last
        val text = when (phase) {
            Phase.IDLE -> null
            Phase.CONNECTING -> when {
                previous == Phase.CONNECTED -> "Connection lost. Reconnecting…"
                lastFailure != null -> null // automatic retries after a failure aren't news
                else -> "Connecting…"
            }
            Phase.CONNECTED -> when {
                previous == Phase.CONNECTING || previous == Phase.FAILED -> connected(route, serverName)
                previous == Phase.CONNECTED && route != lastRoute && route != null -> connected(route, serverName)
                else -> null
            }
            Phase.FAILED -> if (previous == Phase.CONNECTED) "Connection lost" + (error?.let { ". $it" } ?: "")
                else "Can't connect" + (error?.let { ". $it" } ?: "")
        }
        last = phase
        lastRoute = route
        if (phase == Phase.IDLE || phase == Phase.CONNECTED) { lastSpoken = null; lastFailure = null }
        if (phase == Phase.FAILED) {
            if (text == lastFailure) return null
            lastFailure = text
        }
        if (text == null || text == lastSpoken) return null
        lastSpoken = text
        return text
    }

    companion object {
        fun phaseOf(state: ConnectionState): Phase = when (state) {
            ConnectionState.Connecting -> Phase.CONNECTING
            is ConnectionState.Connected -> Phase.CONNECTED
            is ConnectionState.Failed -> Phase.FAILED
            ConnectionState.Idle, ConnectionState.NoServer -> Phase.IDLE
        }

        /** How long a phase must last before it's spoken. */
        fun settleMs(phase: Phase): Long = when (phase) {
            Phase.CONNECTING -> 1_500
            Phase.CONNECTED, Phase.FAILED -> 400
            Phase.IDLE -> 1_500 // a brief Idle between a drop and the reconnect is not "going to the background"
        }

        fun routeLabel(route: Route): String = when (route) {
            Route.LOCAL -> "home address"
            Route.REMOTE -> "remote address"
            Route.TAILSCALE -> "Tailscale"
            Route.VPN -> "VPN"
        }

        private fun connected(route: Route?, serverName: String?): String {
            val to = serverName?.let { " to $it" } ?: ""
            return "Connected$to" + (route?.let { " via ${routeLabel(it)}" } ?: "")
        }
    }
}

/** Speaks [ConnectionAnnouncer]'s sentences through TalkBack (only when an accessibility service is on). */
@Composable
fun ConnectionAnnouncements(container: app.truenascompanion.AppContainer) {
    val state by container.repository.state.collectAsStateWithLifecycle()
    val route by container.repository.route.collectAsStateWithLifecycle()
    val server by container.repository.activeServer.collectAsStateWithLifecycle()
    val view = LocalView.current
    val announcer = remember { ConnectionAnnouncer() }
    val phase = ConnectionAnnouncer.phaseOf(state)
    val error = (state as? ConnectionState.Failed)?.message
    LaunchedEffect(phase, route, error) {
        delay(ConnectionAnnouncer.settleMs(phase))
        val text = announcer.settle(phase, route, server?.name, error) ?: return@LaunchedEffect
        val am = view.context.getSystemService(AccessibilityManager::class.java)
        if (am?.isEnabled == true) {
            @Suppress("DEPRECATION") // Compose has no announce API; live regions need a visible node.
            view.announceForAccessibility(text)
        }
    }
}
