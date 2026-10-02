package app.truenascompanion.data.shell

import app.truenascompanion.data.api.TrueNasException
import app.truenascompanion.data.api.userMessage
import app.truenascompanion.data.model.Route

/** What the shell screen shows when a session couldn't start or has ended. */
data class ShellMessage(val title: String, val detail: String, val hint: String? = null, val canReconnect: Boolean = true)

object ShellMessages {
    const val PROXY_HINT = "If you reach your NAS through a reverse proxy such as Nginx Proxy Manager, edit its proxy host and turn on " +
        "\"Websockets Support\". At home (local address) or over the VPN the shell connects to the NAS directly."

    fun describe(end: ShellEnd, route: Route?): ShellMessage = when (end) {
        is ShellEnd.Closed -> ShellMessage("Session closed", end.reason ?: "You disconnected the shell. Anything that was still running in it was stopped.")
        ShellEnd.Exited -> ShellMessage("Session ended", "The shell exited or the NAS closed it.")
        is ShellEnd.Rejected -> ShellMessage(
            "The NAS refused the shell",
            "${end.reason.removeSuffix(".")}.",
            "Your TrueNAS account needs the web shell privilege (full administrators have it). Reconnect to try again with a new one-time token.",
        )
        is ShellEnd.ClosedEarly -> if (end.code == 1008) ShellMessage(
            "Not allowed from this address",
            "The NAS only accepts the web UI and shell from certain IP addresses.",
            "Check System › General › UI allowlist in the TrueNAS web UI.",
        ) else ShellMessage(
            "The shell didn't start",
            "The NAS closed the connection before the shell started.",
            "The container may have stopped, or the command may not exist in it (try /bin/sh).",
        )
        is ShellEnd.UpgradeFailed -> ShellMessage(
            "Couldn't open the shell connection",
            "The server answered HTTP ${end.httpCode} instead of opening a WebSocket for /websocket/shell.",
            if (route == Route.REMOTE || route == null) PROXY_HINT else "Check that the address points at the TrueNAS web UI.",
        )
        ShellEnd.Timeout -> ShellMessage(
            "The shell didn't start in time",
            "The connection opened, but the NAS didn't confirm the shell within 15 seconds.",
            if (route == Route.REMOTE || route == null) PROXY_HINT else null,
        )
        is ShellEnd.Failed -> ShellMessage(
            if (end.wasConnected) "Connection lost" else "Couldn't connect",
            end.error.userMessage(),
            when {
                end.error is TrueNasException.UntrustedCertificate -> "The certificate doesn't match the one you trusted for this address."
                end.wasConnected -> "Commands that were running in the shell have stopped."
                else -> null
            },
        )
    }

    /** Errors before the shell socket opens (sign-in, token, legacy API). */
    fun beforeConnect(e: Throwable): ShellMessage = when (e) {
        is TrueNasException.Unsupported -> ShellMessage("Shell not available", e.userMessage(), canReconnect = false)
        is TrueNasException.MethodNotFound -> ShellMessage("Shell not available", "This TrueNAS version doesn't support the web shell API.", canReconnect = false)
        is TrueNasException.Rpc -> ShellMessage(
            "The NAS refused the shell",
            e.userMessage(),
            "Your account may not be allowed to create sign-in tokens, e.g. after a one-time password login.",
        )
        else -> ShellMessage("Couldn't connect", e.userMessage())
    }
}
