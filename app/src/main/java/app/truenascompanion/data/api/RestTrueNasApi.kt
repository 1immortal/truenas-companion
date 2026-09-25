package app.truenascompanion.data.api

import app.truenascompanion.data.model.AlertItem
import app.truenascompanion.data.model.ApiFlavor
import app.truenascompanion.data.model.AppAction
import app.truenascompanion.data.model.AppInfo
import app.truenascompanion.data.model.Dataset
import app.truenascompanion.data.model.Disk
import app.truenascompanion.data.model.Pool
import app.truenascompanion.data.model.RealtimeStats
import app.truenascompanion.data.model.ServerConfig
import app.truenascompanion.data.model.ServiceInfo
import app.truenascompanion.data.model.SystemInfo
import app.truenascompanion.data.net.PinningTrustManager
import app.truenascompanion.data.net.toInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Legacy REST API v2.0 (`/api/v2.0/...`, `Authorization: Bearer <key>`), for SCALE releases before 25.04.
 * Deprecated by iXsystems; no live stats (there is no push channel), everything is polled.
 */
class RestTrueNasApi private constructor(
    private val client: OkHttpClient,
    private val base: String,
    private val apiKey: String,
    private val tm: PinningTrustManager,
) : TrueNasApi {

    override val flavor = ApiFlavor.REST
    override val supportsRealtime = false
    override val isAlive = true

    private val json = Json { ignoreUnknownKeys = true }
    private val jsonType = "application/json".toMediaType()

    companion object {
        suspend fun connect(client: OkHttpClient, tm: PinningTrustManager, server: ServerConfig, apiKey: String): RestTrueNasApi {
            val api = RestTrueNasApi(client, server.url.trimEnd('/') + "/api/v2.0", apiKey, tm)
            api.systemInfo() // validates reachability + key
            return api
        }
    }

    private suspend fun request(method: String, path: String, body: JsonElement? = null): JsonElement {
        val req = Request.Builder()
            .url(base + path)
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", "application/json")
            .method(method, if (method == "GET") null else (body ?: JsonNull).toString().toRequestBody(jsonType))
            .build()
        val response = suspendCancellableCoroutine { cont ->
            val call = client.newCall(req)
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) =
                    cont.resumeWithException(mapNetworkError(e) { tm.lastChain?.firstOrNull()?.toInfo() })

                override fun onResponse(call: Call, response: Response) = cont.resume(response)
            })
        }
        response.use { r ->
            val text = r.body.string()
            when {
                r.code == 401 -> throw TrueNasException.AuthFailed(API_KEY_REJECTED_MESSAGE)
                r.code == 403 -> throw TrueNasException.Forbidden()
                r.code == 404 -> throw TrueNasException.MethodNotFound(path)
                !r.isSuccessful -> throw TrueNasException.Http(r.code, "HTTP ${r.code}: ${text.take(200)}")
            }
            if (text.isBlank()) return JsonNull
            return runCatching { json.parseToJsonElement(text) }.getOrElse {
                throw TrueNasException.Http(r.code, "Unexpected response from server (is this a TrueNAS?)")
            }
        }
    }

    private suspend fun get(path: String) = request("GET", path)
    private suspend fun post(path: String, body: JsonElement? = null) = request("POST", path, body)

    override suspend fun systemInfo(): SystemInfo = Parsers.systemInfo(get("/system/info").obj() ?: JsonObject(emptyMap()))

    override fun realtimeStats(): Flow<RealtimeStats> = emptyFlow()

    override suspend fun pools(): List<Pool> = get("/pool").arr()?.mapNotNull { it.obj()?.let(Parsers::pool) } ?: emptyList()

    override suspend fun disks(): List<Disk> {
        val poolByDisk = runCatching { pools() }.getOrDefault(emptyList())
            .flatMap { pool -> pool.diskNames.map { it to pool.name } }.toMap()
        return get("/disk").arr()?.mapNotNull { it.obj()?.let { o -> Parsers.disk(o, poolByDisk) } }?.sortedBy { it.name } ?: emptyList()
    }

    override suspend fun diskTemperatures(names: List<String>): Map<String, Double> {
        if (names.isEmpty()) return emptyMap()
        val body = buildJsonObject { put("names", JsonArray(names.map { JsonPrimitive(it) })) }
        return post("/disk/temperatures", body).obj()?.let(Parsers::diskTemperatures) ?: emptyMap()
    }

    override suspend fun datasets(): List<Dataset> =
        get("/pool/dataset?extra.flat=true&extra.retrieve_children=false").arr()
            ?.mapNotNull { it.obj()?.let(Parsers::dataset) }?.sortedBy { it.id } ?: emptyList()

    @Volatile
    private var legacyApps: Boolean? = null

    override suspend fun apps(): List<AppInfo> {
        if (legacyApps != true) {
            try {
                return get("/app").arr()?.mapNotNull { it.obj()?.let(Parsers::app) }?.sortedBy { it.name.lowercase() }
                    .also { legacyApps = false } ?: emptyList()
            } catch (e: TrueNasException.MethodNotFound) {
                legacyApps = true
            }
        }
        return get("/chart/release").arr()?.mapNotNull { it.obj()?.let(Parsers::chartRelease) }
            ?.sortedBy { it.name.lowercase() } ?: emptyList()
    }

    override suspend fun appAction(app: AppInfo, action: AppAction) {
        val name = JsonPrimitive(app.name)
        if (app.legacyChart) {
            fun scale(n: Int) = buildJsonObject {
                put("release_name", app.name)
                put("scale_options", buildJsonObject { put("replica_count", n) })
            }
            when (action) {
                AppAction.START -> post("/chart/release/scale", scale(1))
                AppAction.STOP -> post("/chart/release/scale", scale(0))
                AppAction.RESTART -> { post("/chart/release/scale", scale(0)); post("/chart/release/scale", scale(1)) }
                AppAction.REDEPLOY -> post("/chart/release/redeploy", name)
                AppAction.PULL_REDEPLOY -> post("/chart/release/pull_container_images", buildJsonObject {
                    put("release_name", app.name)
                    put("pull_container_images_options", buildJsonObject { put("redeploy", true) })
                })
            }
            return
        }
        when (action) {
            AppAction.START -> post("/app/start", name)
            AppAction.STOP -> post("/app/stop", name)
            AppAction.RESTART -> post("/app/redeploy", name)
            AppAction.REDEPLOY -> post("/app/redeploy", name)
            AppAction.PULL_REDEPLOY -> post("/app/pull_images", buildJsonObject {
                put("app_name", app.name)
                put("options", buildJsonObject { put("redeploy", true) })
            })
        }
    }

    override suspend fun alerts(): List<AlertItem> =
        get("/alert/list").arr()?.mapNotNull { it.obj()?.let(Parsers::alert) }?.sortedByDescending { it.datetimeMillis ?: 0 } ?: emptyList()

    override suspend fun dismissAlert(uuid: String) {
        post("/alert/dismiss", JsonPrimitive(uuid))
    }

    override suspend fun services(): List<ServiceInfo> =
        get("/service").arr()?.mapNotNull { it.obj()?.let(Parsers::service) }?.sortedBy { it.displayName } ?: emptyList()

    override suspend fun serviceAction(service: String, start: Boolean) {
        post(if (start) "/service/start" else "/service/stop", buildJsonObject { put("service", service) })
    }

    private suspend fun power(path: String, reason: String) {
        try {
            post(path, buildJsonObject { put("reason", reason) })
        } catch (e: TrueNasException.Http) {
            if (e.code == 422 || e.code == 400) post(path, buildJsonObject { }) else throw e
        }
    }

    override suspend fun reboot() = power("/system/reboot", "Reboot requested from TrueNAS Companion (Android)")
    override suspend fun shutdown() = power("/system/shutdown", "Shutdown requested from TrueNAS Companion (Android)")

    override fun close() = Unit
}
