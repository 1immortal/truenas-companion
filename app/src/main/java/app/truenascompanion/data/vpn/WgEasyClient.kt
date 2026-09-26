package app.truenascompanion.data.vpn

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Minimal client for the wg-easy **v15** HTTP API (the TrueNAS catalog ships wg-easy 15.x), used once by the guided
 * setup on the home network:
 * - first-run setup: `POST /api/setup/2` (admin user) and `POST /api/setup/4` (public host + port), done by the app
 *   right after install so the admin password never goes into the TrueNAS app configuration;
 * - `POST /api/client` + `GET /api/client/{id}/configuration` with HTTP Basic auth to create this phone's client.
 * Basic auth works over plain http on the LAN; it doesn't work if the admin enabled 2FA in wg-easy.
 */
class WgEasyClient(
    baseUrl: String,
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).callTimeout(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false).build(),
) {
    val base = baseUrl.trimEnd('/')

    sealed class Failure(message: String) : IOException(message) {
        class AlreadySetUp : Failure("wg-easy is already set up (it has an admin account).")
        class AuthFailed : Failure("wg-easy rejected the username or password (or two-factor authentication is on for that account).")
        class Http(val code: Int, detail: String) : Failure("wg-easy answered $code: $detail")
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val jsonType = "application/json".toMediaType()

    /** True once the web server answers anything (it redirects to /setup/1 before setup). */
    suspend fun isUp(): Boolean = withContext(Dispatchers.IO) {
        runCatching { http.newCall(Request.Builder().url("$base/").get().build()).execute().use { true } }.getOrDefault(false)
    }

    /** Polls [isUp] every 2 s. */
    suspend fun waitUntilUp(timeoutMs: Long, pollMs: Long = 2_000): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (isUp()) return true
            delay(pollMs)
        }
        return false
    }

    /** First-run step 2: creates the admin account. */
    suspend fun setupAdmin(username: String, password: String) {
        post("/api/setup/2", buildJsonObject { put("username", username); put("password", password); put("confirmPassword", password) }, auth = null, setup = true)
    }

    /** First-run step 4: the public host (e.g. your DuckDNS name) and port clients connect to. Finishes setup. */
    suspend fun setupHost(host: String, port: Int) {
        post("/api/setup/4", buildJsonObject { put("host", host); put("port", port) }, auth = null, setup = true)
    }

    /** Creates a client (peer) and returns its id. */
    suspend fun createClient(username: String, password: String, name: String): String {
        val body = post("/api/client", buildJsonObject { put("name", name); put("expiresAt", JsonNull) }, auth = Credentials.basic(username, password))
        val o = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: throw Failure.Http(200, "unexpected answer")
        return (o["clientId"] as? JsonPrimitive)?.content ?: throw Failure.Http(200, "no client id in the answer")
    }

    /** The client's WireGuard config (the same text the web UI offers as download / QR code). */
    suspend fun clientConfig(username: String, password: String, clientId: String): String = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("$base/api/client/$clientId/configuration").header("Authorization", Credentials.basic(username, password)).get().build()
        http.newCall(req).execute().use { r ->
            if (r.code == 401 || r.code == 403) throw Failure.AuthFailed()
            if (!r.isSuccessful) throw Failure.Http(r.code, r.message)
            r.body.string()
        }
    }

    private suspend fun post(path: String, payload: JsonObject, auth: String?, setup: Boolean = false): String = withContext(Dispatchers.IO) {
        val b = Request.Builder().url(base + path).post(payload.toString().toRequestBody(jsonType))
        auth?.let { b.header("Authorization", it) }
        http.newCall(b.build()).execute().use { r ->
            val text = r.body.string()
            when {
                r.isSuccessful -> text
                setup && r.code == 400 && text.contains("Invalid state") -> throw Failure.AlreadySetUp()
                r.code == 401 || r.code == 403 -> throw Failure.AuthFailed()
                else -> throw Failure.Http(r.code, messageOf(text) ?: r.message)
            }
        }
    }

    private fun messageOf(text: String): String? = runCatching {
        val o = json.parseToJsonElement(text).jsonObject
        (o["message"] ?: o["statusMessage"])?.jsonPrimitive?.content
    }.getOrNull()
}
