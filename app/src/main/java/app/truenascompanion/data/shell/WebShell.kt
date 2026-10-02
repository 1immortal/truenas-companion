package app.truenascompanion.data.shell

import app.truenascompanion.data.api.TrueNasException
import app.truenascompanion.data.api.mapNetworkError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString

/** What the shell runs on the NAS (the `options` object of the web shell handshake). */
sealed interface ShellTarget {
    val title: String

    /** The NAS itself: `login -p -f <user>` (System › Shell in the web UI). */
    data object Host : ShellTarget { override val title = "System shell" }

    /** `docker exec -it <container> <command>` for an app's container. */
    data class App(val appName: String, val containerId: String, val containerName: String, val command: String) : ShellTarget {
        override val title get() = "$appName · $containerName"
    }

    /** `incus exec <instance> <command>` for an Incus container; no command = the instance's default shell. */
    data class Instance(val id: String, val command: String?) : ShellTarget {
        override val title get() = id
    }
}

/**
 * The TrueNAS web shell protocol (`/websocket/shell`, middleware `apps/webshell_app.py`, 25.04–25.10):
 * 1. open a WebSocket to `/websocket/shell/` on the same host as the API;
 * 2. send one text frame `{"token": <auth.generate_token>, "options": {...}}`;
 * 3. the server answers `{"msg": "connected", "id": <session id>}` (or `{"msg": "failed", ...}` for a bad token);
 * 4. from then on every frame is raw terminal data: binary frames from the server, and our input as binary frames
 *    (the server writes each frame straight to the pty, so text frames are not used);
 * 5. the terminal size is set with the normal API call `core.resize_shell(id, cols, rows)`;
 * 6. the server closes the socket when the shell exits.
 */
object WebShellProtocol {
    const val PATH = "/websocket/shell/"
    const val DEFAULT_COMMAND = "/bin/sh"
    val COMMANDS = listOf("/bin/sh", "/bin/bash")

    private val json = Json { ignoreUnknownKeys = true }

    /** `https://nas.example.org/sub` -> `wss://nas.example.org/sub/websocket/shell/`. */
    fun url(base: String): String {
        val trimmed = base.trimEnd('/')
        val scheme = if (trimmed.startsWith("https://", true)) "wss" else "ws"
        return "$scheme://${trimmed.substringAfter("://")}$PATH"
    }

    fun options(target: ShellTarget): JsonObject = when (target) {
        ShellTarget.Host -> JsonObject(emptyMap())
        is ShellTarget.App -> buildJsonObject {
            put("app_name", target.appName)
            put("container_id", target.containerId)
            put("command", target.command)
        }
        is ShellTarget.Instance -> buildJsonObject {
            put("virt_instance_id", target.id)
            put("use_console", false)
            target.command?.let { put("command", it) }
        }
    }

    fun hello(token: String, target: ShellTarget): String =
        buildJsonObject { put("token", token); put("options", options(target)) }.toString()

    sealed interface Control {
        data class Connected(val id: String?) : Control
        data class Failed(val reason: String) : Control
    }

    fun parseControl(text: String): Control? {
        val o = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return null
        return when ((o["msg"] as? JsonPrimitive)?.contentOrNull) {
            "connected" -> Control.Connected((o["id"] as? JsonPrimitive)?.contentOrNull)
            "failed" -> {
                val err = o["error"] as? JsonObject
                Control.Failed((err?.get("reason") as? JsonPrimitive)?.contentOrNull ?: "The NAS refused the shell.")
            }
            else -> null
        }
    }

    /**
     * The server runs the command as a single program path (`docker exec -it <id> <command>`), so arguments or
     * shell syntax don't work: allow one word without spaces or control characters.
     */
    fun validCommand(cmd: String): Boolean =
        cmd.isNotEmpty() && cmd.length <= 200 && cmd.none { it.isWhitespace() || it.isISOControl() || it in "'\"`;&|<>$\\" }
}

/** Why a shell session ended. */
sealed interface ShellEnd {
    /** We closed it (left the screen, pressed disconnect, app in the background). */
    data class Closed(val reason: String? = null) : ShellEnd
    /** The shell on the NAS exited or the NAS closed the connection after it was running. */
    data object Exited : ShellEnd
    /** `{"msg": "failed"}`: token rejected (expired, or the account lacks the web shell privilege). */
    data class Rejected(val reason: String) : ShellEnd
    /** The NAS closed the socket before "connected" (invalid container, IP not allowed, internal error). */
    data class ClosedEarly(val code: Int, val reason: String) : ShellEnd
    /** The HTTP upgrade to WebSocket failed: usually a reverse proxy without WebSocket support. */
    data class UpgradeFailed(val httpCode: Int) : ShellEnd
    /** No "connected" in time. */
    data object Timeout : ShellEnd
    /** Network / TLS / certificate problem while connecting or running. */
    data class Failed(val error: TrueNasException, val wasConnected: Boolean) : ShellEnd
}

interface ShellListener {
    fun onConnected(id: String?)
    fun onOutput(data: ByteArray)
    fun onEnd(end: ShellEnd)
}

/**
 * One web shell connection. Callbacks arrive on OkHttp's thread; [onEnd] is called exactly once.
 * Neither the token nor any terminal data is ever logged.
 */
class WebShellConnection(
    private val client: OkHttpClient,
    private val url: String,
    private val scope: CoroutineScope,
    private val listener: ShellListener,
    private val handshakeTimeoutMs: Long = 15_000,
    private val certificate: () -> app.truenascompanion.data.net.CertificateInfo? = { null },
) {
    @Volatile private var socket: WebSocket? = null
    @Volatile var connected = false
        private set
    @Volatile private var ended = false
    private var timeout: Job? = null
    /**
     * Middleware starts the shell thread before it sends "connected", so the first output (or the sudo warning) can
     * arrive ahead of it. Keep that and hand it over right after [ShellListener.onConnected].
     */
    private val early = java.io.ByteArrayOutputStream()

    fun open(token: String, target: ShellTarget) {
        // close() can win the race before the socket exists (reconnect, leaving the screen). Don't open then:
        // a socket created after finish() would otherwise stay up with nobody listening.
        if (ended) return
        val hello = WebShellProtocol.hello(token, target)
        timeout = scope.launch {
            delay(handshakeTimeoutMs)
            if (!connected) finish(ShellEnd.Timeout, close = true)
        }
        if (ended) { timeout?.cancel(); return }
        socket = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (ended) { webSocket.close(1000, null); return }
                webSocket.send(hello)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (ended) return
                val control = if (text.startsWith("{")) WebShellProtocol.parseControl(text) else null
                when {
                    control is WebShellProtocol.Control.Connected && !connected -> {
                        timeout?.cancel()
                        val pending = synchronized(early) { connected = true; early.toByteArray().also { early.reset() } }
                        listener.onConnected(control.id)
                        if (pending.isNotEmpty()) listener.onOutput(pending)
                    }
                    control is WebShellProtocol.Control.Failed && !connected -> finish(ShellEnd.Rejected(control.reason), close = true)
                    connected -> listener.onOutput(text.encodeToByteArray())
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                if (ended) return
                if (!connected) synchronized(early) {
                    if (!connected) { if (early.size() < 256 * 1024) early.write(bytes.toByteArray()); return }
                }
                listener.onOutput(bytes.toByteArray())
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                finish(if (connected) ShellEnd.Exited else ShellEnd.ClosedEarly(code, reason), close = false)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                finish(if (connected) ShellEnd.Exited else ShellEnd.ClosedEarly(code, reason), close = false)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val code = response?.code
                val end = when {
                    code != null && code != 101 -> ShellEnd.UpgradeFailed(code)
                    else -> ShellEnd.Failed(mapNetworkError(t, certificate), connected)
                }
                response?.close()
                finish(end, close = false)
            }
        })
    }

    /** Terminal input (keys, paste). */
    fun send(data: ByteArray, offset: Int = 0, count: Int = data.size) {
        if (!connected || ended) return
        socket?.send(data.toByteString(offset, count))
    }

    fun close(reason: String? = null) = finish(ShellEnd.Closed(reason), close = true)

    private fun finish(end: ShellEnd, close: Boolean) {
        synchronized(this) {
            if (ended) return
            ended = true
        }
        timeout?.cancel()
        if (close) socket?.let { if (!it.close(1000, null)) it.cancel() }
        listener.onEnd(end)
    }
}
