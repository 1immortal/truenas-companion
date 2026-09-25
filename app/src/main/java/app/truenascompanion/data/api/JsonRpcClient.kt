package app.truenascompanion.data.api

import app.truenascompanion.data.net.PinningTrustManager
import app.truenascompanion.data.net.toInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Minimal JSON-RPC 2.0 client over an OkHttp WebSocket, as used by the TrueNAS `/api/current` endpoint.
 * Requests are matched to responses by id; server notifications (`collection_update`, `notify_unsubscribed`)
 * are exposed via [events].
 */
class JsonRpcClient(
    private val client: OkHttpClient,
    private val url: String,
    private val trustManager: PinningTrustManager,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JsonElement>>()
    private val ids = AtomicLong(1)
    private val _events = MutableSharedFlow<JsonObject>(extraBufferCapacity = 64)
    val events: SharedFlow<JsonObject> = _events

    @Volatile
    private var socket: WebSocket? = null

    @Volatile
    var isOpen: Boolean = false
        private set

    /** Completes when the socket is closed or fails (lets long-lived subscribers notice a dead connection). */
    val closed = CompletableDeferred<Unit>()

    suspend fun open() = suspendCancellableCoroutine { cont ->
        val request = Request.Builder().url(url).build()
        val ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                isOpen = true
                if (cont.isActive) cont.resume(Unit)
            }

            override fun onMessage(webSocket: WebSocket, text: String) = handle(text)

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                isOpen = false
                closed.complete(Unit)
                failAll(TrueNasException.NotConnected())
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                isOpen = false
                closed.complete(Unit)
                val error = when {
                    response != null && (response.code == 401 || response.code == 403) -> TrueNasException.AuthFailed()
                    response != null && response.code in 400..599 ->
                        TrueNasException.EndpointNotFound("WebSocket API not available (HTTP ${response.code})")
                    else -> mapNetworkError(t) { trustManager.lastChain?.firstOrNull()?.toInfo() }
                }
                if (cont.isActive) cont.resumeWithException(error)
                failAll(error)
            }
        })
        socket = ws
        cont.invokeOnCancellation { ws.cancel() }
    }

    suspend fun call(method: String, params: JsonArray = JsonArray(emptyList()), timeoutMs: Long = 30_000): JsonElement {
        val ws = socket
        if (!isOpen || ws == null) throw TrueNasException.NotConnected()
        val id = ids.getAndIncrement()
        val deferred = CompletableDeferred<JsonElement>()
        pending[id.toString()] = deferred
        val msg = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", method)
            put("params", params)
        }
        try {
            if (!ws.send(msg.toString())) throw TrueNasException.NotConnected()
            return withTimeout(timeoutMs) { deferred.await() }
        } catch (e: TimeoutCancellationException) {
            throw TrueNasException.Timeout("No answer from the server for $method.")
        } finally {
            pending.remove(id.toString())
        }
    }

    /** Fire-and-forget request (used for unsubscribe on teardown). */
    fun notify(method: String, params: JsonArray) {
        val msg = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", ids.getAndIncrement())
            put("method", method)
            put("params", params)
        }
        socket?.send(msg.toString())
    }

    fun close() {
        isOpen = false
        closed.complete(Unit)
        socket?.close(1000, "bye")
        socket = null
        failAll(TrueNasException.NotConnected())
    }

    private fun handle(text: String) {
        val obj = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return
        val id = obj["id"].prim()?.contentOrNull
        if (id != null && (obj.containsKey("result") || obj.containsKey("error"))) {
            val deferred = pending[id] ?: return
            val error = obj["error"].obj()
            if (error != null) deferred.completeExceptionally(parseError(error))
            else deferred.complete(obj["result"] ?: JsonNull)
            return
        }
        when (obj.str("method")) {
            "collection_update", "notify_unsubscribed" -> obj["params"].obj()?.let { _events.tryEmit(it) }
        }
    }

    private fun parseError(error: JsonObject): TrueNasException {
        val code = error.long("code")?.toInt() ?: 0
        val data = error["data"].obj()
        val errname = data?.str("errname")
        val reason = data?.str("reason") ?: error.str("message") ?: "Unknown error"
        return when {
            code == -32601 -> TrueNasException.MethodNotFound(reason)
            errname == "EACCES" || errname == "EPERM" || reason.contains("Not authorized", true) ->
                TrueNasException.Forbidden(reason.trim())
            else -> TrueNasException.Rpc(code, errname, reason.trim())
        }
    }

    private fun failAll(e: Throwable) {
        pending.values.forEach { it.completeExceptionally(e) }
        pending.clear()
    }
}
