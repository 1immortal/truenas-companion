package app.truenascompanion.data.vpn

import app.truenascompanion.data.api.TrueNasApi
import app.truenascompanion.data.api.TrueNasException
import app.truenascompanion.util.UrlUtils
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.security.SecureRandom

/**
 * Guided VPN setup on the NAS (TrueNAS 25.10 apps catalog, github.com/truenas/apps):
 * - **wg-easy** (train `stable`, app version 15.x): host networking by default, so WireGuard listens on the NAS's own
 *   UDP port 51820 and the web UI on TCP 30058. The app installs it with `insecure = true` (the web UI is only used on
 *   the home network, over http) and nothing else, then finishes wg-easy's first-run setup through its API.
 * - **Tailscale** (train `community`): host networking by default, so the NAS's Tailscale address (100.x) reaches the
 *   TrueNAS web UI directly. Installed with an auth key, a hostname and optionally the LAN subnet route.
 */
object VpnSetup {
    const val WG_APP = "wg-easy"
    const val WG_TRAIN = "stable"
    /** Default `network.web_port.port_number` of the catalog app. */
    const val WG_WEB_PORT = 30058
    /** WireGuard's listen port on the NAS (host network). */
    const val WG_LISTEN_PORT = 51820
    const val WG_ADMIN = "admin"
    const val TS_APP = "tailscale"
    const val TS_TRAIN = "community"
    const val TS_KEYS_URL = "https://login.tailscale.com/admin/settings/keys"
    const val TS_MACHINES_URL = "https://login.tailscale.com/admin/machines"
    const val TS_PACKAGE = "com.tailscale.ipn"

    /** `app.create` values for wg-easy: everything else stays at the catalog defaults. */
    fun wgEasyValues(): JsonObject = buildJsonObject {
        putJsonObject("wg_easy") { put("insecure", true) }
    }

    /** `app.create` values for Tailscale. */
    fun tailscaleValues(authKey: String, hostname: String, routes: List<String>): JsonObject = buildJsonObject {
        putJsonObject("tailscale") {
            put("auth_key", authKey.trim())
            put("hostname", hostname.trim())
            putJsonArray("advertise_routes") { routes.forEach { add(JsonPrimitive(it)) } }
        }
    }

    /** The public host clients connect to: the server's remote address without scheme, port or path. */
    fun publicHost(remoteUrl: String): String? = UrlUtils.hostOf(remoteUrl)

    /** Valid Tailscale hostname: letters, digits and dashes. */
    fun validHostname(h: String): Boolean = h.matches(Regex("^[a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?$"))

    /** Valid public host for WireGuard (DNS name or IPv4, no scheme). */
    fun validPublicHost(h: String): Boolean =
        UrlUtils.isIpv4(h) || h.matches(Regex("^(?=.{1,253}$)([a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?\\.)+[a-zA-Z]{2,63}$"))

    /** 20 characters without look-alikes (~117 bits); wg-easy requires at least 12. */
    fun generatePassword(random: SecureRandom = SecureRandom()): String {
        val alphabet = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        return (1..20).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")
    }

    /** Client name shown in wg-easy, e.g. "YTN Pixel 8". */
    fun clientName(model: String): String =
        ("YTN " + model.replace(Regex("[^A-Za-z0-9 ._-]"), "").trim()).trim().take(40)

    /** The NAS subnet that contains [ip], from `network.general.summary` (e.g. `192.168.1.0/24`). */
    fun lanSubnet(summary: JsonObject?, ip: String): String? {
        val ips = summary?.get("ips") as? JsonObject
        val cidrs = ips?.values?.flatMap { iface -> ((iface as? JsonObject)?.get("IPV4") as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull } }.orEmpty()
        cidrs.firstOrNull { it.substringBefore('/') == ip }?.let { return WgConf.networkOf(it) }
        return WgConf.networkOf("$ip/24")
    }

    /**
     * 1.7.1: waits until something listens on [host]:[port] with a bare TCP connect (nothing is sent; wg-easy's page is
     * plain http, which the app no longer talks to). Polls every 2 s up to [timeoutMs].
     */
    suspend fun waitForPort(host: String, port: Int, timeoutMs: Long, pollMs: Long = 2_000): Boolean =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val end = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < end) {
                val open = runCatching {
                    java.net.Socket().use { it.connect(java.net.InetSocketAddress(host, port), 2_000) }
                    true
                }.getOrDefault(false)
                if (open) return@withContext true
                kotlinx.coroutines.delay(pollMs)
            }
            false
        }

    suspend fun appExists(api: TrueNasApi, name: String): Boolean {
        val filter = buildJsonArray { add(buildJsonArray { add(JsonPrimitive("name")); add(JsonPrimitive("=")); add(JsonPrimitive(name)) }) }
        return (api.rpc("app.query", filter) as? JsonArray)?.isNotEmpty() == true
    }

    suspend fun networkSummary(api: TrueNasApi): JsonObject? = runCatching { api.rpc("network.general.summary") as? JsonObject }.getOrNull()

    /** Waits for a middleware job (polling `core.get_jobs` every 2 s) and reports its progress text. */
    suspend fun awaitJob(api: TrueNasApi, id: Long, timeoutMs: Long = 15 * 60_000L, onProgress: (Int?, String?) -> Unit = { _, _ -> }) {
        // 1.7.1 (review P1-5): withTimeoutOrNull, then a plain Timeout error (not a cancellation).
        val finished = withTimeoutOrNull(timeoutMs) {
            while (true) {
                val filter = buildJsonArray { add(buildJsonArray { add(JsonPrimitive("id")); add(JsonPrimitive("=")); add(JsonPrimitive(id)) }) }
                val job = (api.rpc("core.get_jobs", filter) as? JsonArray)?.firstOrNull() as? JsonObject
                    ?: throw TrueNasException.JobFailed("The install job disappeared.")
                when (job.str("state")) {
                    "SUCCESS" -> return@withTimeoutOrNull true
                    "FAILED", "ABORTED" -> throw TrueNasException.JobFailed(
                        job.str("error")?.lineSequence()?.firstOrNull { it.isNotBlank() } ?: "The install failed.",
                    )
                    else -> {
                        val p = job["progress"] as? JsonObject
                        onProgress((p?.get("percent") as? JsonPrimitive)?.doubleOrNull?.toInt(), p?.str("description"))
                        delay(2_000)
                    }
                }
            }
            @Suppress("UNREACHABLE_CODE") true
        }
        if (finished == null) throw TrueNasException.Timeout("The install is still running on the NAS. Check TrueNAS › Apps, then try again.")
    }

    private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.contentOrNull?.takeIf { it != "null" }

    /** A friendly hint for common `app.create` failures. */
    fun installHint(message: String, train: String): String? = when {
        message.contains("train", true) || message.contains("not found", true) ->
            "Check that the \"$train\" train is enabled in TrueNAS: Apps › Discover Apps › ⋮ › Manage Catalogs › Edit › Preferred Trains."
        message.contains("pool", true) -> "Apps need a pool: open Apps in TrueNAS once and choose a pool for applications."
        else -> null
    }
}
