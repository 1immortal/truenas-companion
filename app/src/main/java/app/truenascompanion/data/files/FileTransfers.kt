package app.truenascompanion.data.files

import app.truenascompanion.data.api.TrueNasException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.BufferedSink
import okio.source
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit

/**
 * The two HTTP requests of the file browser (1.3.0). Both go to TrueNAS's file application, never to the REST API:
 *
 * - **Download**: `GET <base>/_download/<job>?…`, exactly the URL that `core.download` returned over the WebSocket.
 *   The one-time credential is already part of that URL, so no header is added. TrueNAS's middleware does not write an
 *   audit entry for `/_download`; the `core.download` call itself is logged as a normal WebSocket call.
 * - **Upload**: `POST <base>/_upload` (multipart: `data` = `{"method":"filesystem.put","params":[path,{…}]}`, then
 *   `file`), authorized with a single-use token from `auth.generate_token` in the `Token` scheme. The middleware logs it
 *   with protocol `REST` (FileApplication), not `LEGACY_REST`.
 *
 * [client] is built by HttpClients for the route that is in use, so certificate pinning and the route
 * (local, Tailscale, VPN, remote) are the same as for the WebSocket.
 */
class FileTransfers(client: OkHttpClient, baseUrl: String) {
    private val base = baseUrl.trimEnd('/')

    /** Long transfers: no overall deadline, generous per-read/write timeouts (a stalled proxy still fails). */
    private val http = client.newBuilder()
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    /**
     * Streams the download at [downloadUrl] (a `/_download/...` path from `core.download`) into [out].
     * [onProgress] gets (bytes so far, expected size or -1). TrueNAS answers chunked, so the expected size comes from
     * the listing ([expectedSize]). Cancelling the coroutine cancels the HTTP call. Returns the number of bytes written.
     */
    suspend fun download(downloadUrl: String, out: OutputStream, expectedSize: Long, onProgress: (Long, Long) -> Unit): Long {
        require(downloadUrl.startsWith("/_download/")) { "Not a TrueNAS download link" }
        val request = Request.Builder().url(base + downloadUrl).get().build()
        return http.newCall(request).executeCancellable { response ->
            if (!response.isSuccessful) throw httpError(response)
            val body = response.body ?: throw TrueNasException.Http(response.code, "Empty download")
            val total = body.contentLength().takeIf { it > 0 } ?: expectedSize
            var done = 0L
            var lastReport = 0L
            body.byteStream().use { input ->
                val buf = ByteArray(BUFFER)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    if (done - lastReport >= REPORT_EVERY || done == total) { lastReport = done; onProgress(done, total) }
                }
            }
            out.flush()
            onProgress(done, total)
            done
        }
    }

    /**
     * Uploads [size] bytes from [open] to [path] with `filesystem.put` (overwrites an existing file; the caller asks
     * first). Returns the id of the `filesystem.put` job, which the caller then waits for over the WebSocket.
     */
    suspend fun upload(path: String, token: String, fileName: String, size: Long, open: () -> InputStream, onProgress: (Long, Long) -> Unit): Long {
        val request = uploadRequest(path, token, fileName, size, open, onProgress)
        return http.newCall(request).executeCancellable { response ->
            if (!response.isSuccessful) throw httpError(response)
            val text = response.body?.string().orEmpty()
            runCatching { Json.parseToJsonElement(text).jsonObject["job_id"]?.jsonPrimitive?.longOrNull }.getOrNull()
                ?: throw TrueNasException.Http(response.code, "TrueNAS did not start the upload job.")
        }
    }

    internal fun uploadRequest(path: String, token: String, fileName: String, size: Long, open: () -> InputStream, onProgress: (Long, Long) -> Unit): Request {
        val data = buildJsonObject {
            put("method", "filesystem.put")
            put("params", JsonArray(listOf(JsonPrimitive(path), buildJsonObject { put("append", false); put("mode", JsonNull) })))
        }.toString()
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("data", data)
            .addFormDataPart("file", fileName.ifBlank { "upload" }, CountingBody(size, open, onProgress))
            .build()
        return Request.Builder().url("$base/_upload").header(AUTH_HEADER, "Token $token").post(body).build()
    }

    private class CountingBody(private val size: Long, private val open: () -> InputStream, private val onProgress: (Long, Long) -> Unit) : RequestBody() {
        override fun contentType() = "application/octet-stream".toMediaType()
        override fun contentLength() = if (size >= 0) size else -1
        override fun isOneShot() = true
        override fun writeTo(sink: BufferedSink) {
            var done = 0L
            var lastReport = 0L
            open().use { input ->
                input.source().use { src ->
                    val buffer = okio.Buffer()
                    while (true) {
                        val n = src.read(buffer, BUFFER.toLong())
                        if (n < 0) break
                        sink.write(buffer, n)
                        done += n
                        if (done - lastReport >= REPORT_EVERY) { lastReport = done; onProgress(done, size) }
                    }
                }
            }
            sink.flush()
            onProgress(done, size)
        }
    }

    private fun httpError(response: Response): TrueNasException {
        val text = runCatching { response.body?.string()?.take(300) }.getOrNull().orEmpty()
        val msg = when (response.code) {
            401 -> "TrueNAS refused the transfer (the one-time link expired or isn't allowed)."
            403 -> "Permission denied."
            404 -> "The download link expired. Try again."
            410 -> "The download link expired. Try again."
            412 -> text.ifBlank { "TrueNAS couldn't write the file." }
            413 -> "The file is too big for a reverse proxy between you and TrueNAS."
            502, 503, 504 -> "A proxy between you and TrueNAS stopped the transfer (HTTP ${response.code})."
            else -> text.ifBlank { "HTTP ${response.code}" }
        }
        return TrueNasException.Http(response.code, msg)
    }

    companion object {
        private const val BUFFER = 64 * 1024
        private const val REPORT_EVERY = 256L * 1024
        /** TrueNAS's FileApplication accepts `Token <single-use token>` for uploads. */
        private const val AUTH_HEADER = "Authorization"
    }
}

/** Runs the call on the IO dispatcher; cancelling the calling coroutine cancels the socket immediately. */
internal suspend fun <T> Call.executeCancellable(block: (Response) -> T): T = coroutineScope {
    val call = this@executeCancellable
    val guard = launch(start = CoroutineStart.UNDISPATCHED) {
        try { awaitCancellation() } finally { call.cancel() }
    }
    try {
        withContext(Dispatchers.IO) {
            try {
                call.execute().use { r -> block(r) }
            } catch (e: java.io.IOException) {
                currentCoroutineContext().ensureActive()
                throw TrueNasException.Unreachable(e.message ?: "The transfer was interrupted.", e)
            }
        }
    } finally {
        guard.cancel()
    }
}
