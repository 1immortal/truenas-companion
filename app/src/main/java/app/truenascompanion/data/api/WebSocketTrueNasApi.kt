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
import app.truenascompanion.util.UrlUtils
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient

private fun params(vararg items: JsonElement) = JsonArray(items.toList())
private fun p(s: String) = JsonPrimitive(s)

/** TrueNAS 25.04+ JSON-RPC 2.0 WebSocket API (`wss://host/api/current`). */
class WebSocketTrueNasApi private constructor(private val rpc: JsonRpcClient) : TrueNasApi {

    override val flavor = ApiFlavor.WEBSOCKET
    override val supportsRealtime = true
    override val isAlive: Boolean get() = rpc.isOpen

    @Volatile
    private var cachedPhysmem: Long? = null

    companion object {
        suspend fun connect(client: OkHttpClient, tm: PinningTrustManager, server: ServerConfig, apiKey: String): WebSocketTrueNasApi {
            val rpc = JsonRpcClient(client, UrlUtils.webSocketUrl(server.url), tm)
            rpc.open()
            try {
                login(rpc, server, apiKey)
            } catch (e: Throwable) {
                rpc.close()
                throw e
            }
            return WebSocketTrueNasApi(rpc)
        }

        private suspend fun login(rpc: JsonRpcClient, server: ServerConfig, apiKey: String) {
            // 25.04 / 25.10: auth.login_with_api_key(api_key) -> Boolean. Deprecated, removed in v27.
            val ok = try {
                rpc.call("auth.login_with_api_key", params(p(apiKey))).prim()?.booleanOrNull ?: false
            } catch (e: Throwable) {
                if (!e.isMethodMissing()) throw e
                // v27+: auth.login_ex({"mechanism": "API_KEY_PLAIN", "username": ..., "api_key": ...})
                val res = rpc.call("auth.login_ex", params(buildJsonObject {
                    put("mechanism", "API_KEY_PLAIN")
                    put("username", server.username.ifBlank { "truenas_admin" })
                    put("api_key", apiKey)
                })).obj()
                when (res?.str("response_type")) {
                    "SUCCESS" -> true
                    "OTP_REQUIRED" -> throw TrueNasException.AuthFailed("This account requires a one-time password, which API key login does not support.")
                    "EXPIRED" -> throw TrueNasException.AuthFailed("This API key has expired. Create a new one in TrueNAS.")
                    else -> false
                }
            }
            if (!ok) throw TrueNasException.AuthFailed(
                "API key rejected. Check that it was copied completely and has not been revoked " +
                    "(TrueNAS automatically revokes keys that were sent over plain HTTP)."
            )
        }
    }

    private suspend fun call(method: String, vararg args: JsonElement): JsonElement = rpc.call(method, params(*args))

    /** Calls a middleware "job" method and waits for it to finish via core.get_jobs polling. */
    private suspend fun callJob(method: String, vararg args: JsonElement, timeoutMs: Long = 180_000): JsonElement? {
        val first = call(method, *args)
        val jobId = first.prim()?.takeUnless { it.isString }?.longOrNull ?: return first
        return withTimeout(timeoutMs) {
            var result: JsonElement? = null
            var done = false
            while (!done) {
                val filter = buildJsonArray { add(buildJsonArray { add(p("id")); add(p("=")); add(JsonPrimitive(jobId)) }) }
                val job = call("core.get_jobs", filter).arr()?.firstOrNull().obj()
                when (job?.str("state")) {
                    "SUCCESS" -> { result = job["result"]; done = true }
                    "FAILED", "ABORTED" -> throw TrueNasException.JobFailed(
                        job.str("error")?.lineSequence()?.firstOrNull { it.isNotBlank() } ?: "$method failed"
                    )
                    else -> delay(1000)
                }
            }
            result
        }
    }

    override suspend fun systemInfo(): SystemInfo =
        Parsers.systemInfo(call("system.info").obj() ?: JsonObject(emptyMap())).also { cachedPhysmem = it.physicalMemory }

    override fun realtimeStats(): Flow<RealtimeStats> = channelFlow {
        launch(start = CoroutineStart.UNDISPATCHED) {
            rpc.events.collect { ev ->
                if (ev.str("collection")?.startsWith("reporting.realtime") == true) {
                    ev["fields"].obj()?.let { send(Parsers.realtime(it, cachedPhysmem)) }
                }
            }
        }
        val subId = call("core.subscribe", p("reporting.realtime"))
        awaitClose { runCatching { rpc.notify("core.unsubscribe", params(subId)) } }
    }

    override suspend fun pools(): List<Pool> = call("pool.query").arr()?.mapNotNull { it.obj()?.let(Parsers::pool) } ?: emptyList()

    override suspend fun disks(): List<Disk> {
        val poolByDisk = runCatching { pools() }.getOrDefault(emptyList())
            .flatMap { pool -> pool.diskNames.map { it to pool.name } }.toMap()
        return call("disk.query").arr()?.mapNotNull { it.obj()?.let { o -> Parsers.disk(o, poolByDisk) } }
            ?.sortedBy { it.name } ?: emptyList()
    }

    override suspend fun diskTemperatures(names: List<String>): Map<String, Double> {
        if (names.isEmpty()) return emptyMap()
        val list = JsonArray(names.map { p(it) })
        return call("disk.temperatures", list).obj()?.let(Parsers::diskTemperatures) ?: emptyMap()
    }

    override suspend fun datasets(): List<Dataset> {
        val options = buildJsonObject {
            put("extra", buildJsonObject { put("flat", true); put("retrieve_children", false) })
        }
        return call("pool.dataset.query", JsonArray(emptyList()), options).arr()
            ?.mapNotNull { it.obj()?.let(Parsers::dataset) }?.sortedBy { it.id } ?: emptyList()
    }

    @Volatile
    private var legacyApps: Boolean? = null

    override suspend fun apps(): List<AppInfo> {
        if (legacyApps != true) {
            try {
                return call("app.query").arr()?.mapNotNull { it.obj()?.let(Parsers::app) }?.sortedBy { it.name.lowercase() }
                    .also { legacyApps = false } ?: emptyList()
            } catch (e: Throwable) {
                if (!e.isMethodMissing()) throw e
                legacyApps = true
            }
        }
        return call("chart.release.query").arr()?.mapNotNull { it.obj()?.let(Parsers::chartRelease) }
            ?.sortedBy { it.name.lowercase() } ?: emptyList()
    }

    override suspend fun appAction(app: AppInfo, action: AppAction) {
        if (app.legacyChart) {
            fun scale(replicas: Int) = buildJsonObject { put("replica_count", replicas) }
            when (action) {
                AppAction.START -> callJob("chart.release.scale", p(app.name), scale(1))
                AppAction.STOP -> callJob("chart.release.scale", p(app.name), scale(0))
                AppAction.RESTART -> { callJob("chart.release.scale", p(app.name), scale(0)); callJob("chart.release.scale", p(app.name), scale(1)) }
                AppAction.REDEPLOY -> callJob("chart.release.redeploy", p(app.name))
            }
            return
        }
        when (action) {
            AppAction.START -> callJob("app.start", p(app.name))
            AppAction.STOP -> callJob("app.stop", p(app.name))
            AppAction.RESTART -> { callJob("app.stop", p(app.name)); callJob("app.start", p(app.name)) }
            AppAction.REDEPLOY -> callJob("app.redeploy", p(app.name))
        }
    }

    override suspend fun alerts(): List<AlertItem> =
        call("alert.list").arr()?.mapNotNull { it.obj()?.let(Parsers::alert) }
            ?.sortedByDescending { it.datetimeMillis ?: 0 } ?: emptyList()

    override suspend fun dismissAlert(uuid: String) {
        call("alert.dismiss", p(uuid))
    }

    override suspend fun services(): List<ServiceInfo> =
        call("service.query").arr()?.mapNotNull { it.obj()?.let(Parsers::service) }?.sortedBy { it.displayName } ?: emptyList()

    override suspend fun serviceAction(service: String, start: Boolean) {
        val result = try {
            // 25.04+: service.control(verb, service, options) is a job.
            callJob("service.control", p(if (start) "START" else "STOP"), p(service), buildJsonObject { put("silent", false) })
        } catch (e: Throwable) {
            if (!e.isMethodMissing()) throw e
            // Older releases: service.start / service.stop (deprecated in 25.x, removed in 26).
            call(if (start) "service.start" else "service.stop", p(service))
        }
        if (result?.prim()?.booleanOrNull == false) {
            throw TrueNasException.JobFailed("TrueNAS could not ${if (start) "start" else "stop"} $service. Check its configuration in the web UI.")
        }
    }

    private suspend fun power(method: String, reason: String) {
        // 25.04+: system.reboot/shutdown(reason, options) are jobs; we don't wait since the server goes away.
        try {
            call(method, p(reason))
        } catch (e: Throwable) {
            if (e is TrueNasException.Rpc && e.code != -32001) call(method) else throw e
        }
    }

    override suspend fun reboot() = power("system.reboot", "Reboot requested from TrueNAS Companion (Android)")
    override suspend fun shutdown() = power("system.shutdown", "Shutdown requested from TrueNAS Companion (Android)")

    override fun close() = rpc.close()
}
