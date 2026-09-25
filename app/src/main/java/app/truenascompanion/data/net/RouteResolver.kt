package app.truenascompanion.data.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.core.content.ContextCompat
import app.truenascompanion.data.model.Route
import app.truenascompanion.data.model.RouteMode
import app.truenascompanion.data.model.ServerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** The phone's current default network, as far as routing is concerned. */
data class NetState(val id: String, val kind: Kind) {
    enum class Kind {
        /** Wi-Fi or Ethernet: the home network may be reachable. */
        LAN,
        /** VPN (e.g. WireGuard back home): the local address may be reachable, so it's probed too. */
        VPN,
        /** Mobile data only: the local address can't be reachable, no probe. */
        MOBILE,
    }
}

/**
 * Picks the local or remote address per server.
 *
 * Battery/latency: the decision is cached per (network, server) and only re-made when the default network changes
 * (a passive `registerDefaultNetworkCallback`, no polling). A probe is one short HTTPS request to the local address
 * (1.5 s connect timeout), done only on Wi-Fi/Ethernet/VPN.
 */
class RouteResolver(
    context: Context?,
    private val probe: suspend (ServerConfig) -> Boolean = ::probeLocal,
) {
    private val _network = MutableStateFlow<NetState?>(null)
    val network: StateFlow<NetState?> = _network.asStateFlow()

    /** Emits after the default network changed (not on registration). */
    val networkChanges: Flow<NetState?> = network.map { it?.id to it?.kind }.distinctUntilChanged().drop(1).map { network.value }

    private val cache = ConcurrentHashMap<String, Route>()
    private val locks = ConcurrentHashMap<String, Mutex>()

    private val _lastRoute = MutableStateFlow<Map<String, Route>>(emptyMap())
    /** Last decided route per server id (for the "Local"/"Remote" chip). */
    val lastRoute: StateFlow<Map<String, Route>> = _lastRoute.asStateFlow()

    init {
        context?.let { register(it) }
    }

    private fun register(context: Context) {
        val cm = ContextCompat.getSystemService(context, ConnectivityManager::class.java) ?: return
        runCatching {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    val next = NetState(network.toString(), kindOf(caps))
                    if (_network.value != next) {
                        _network.value = next
                        cache.clear() // only the current network's decisions are ever useful
                    }
                }
                override fun onLost(network: Network) {
                    if (_network.value?.id == network.toString()) { _network.value = null; cache.clear() }
                }
            })
        }
    }

    /** The configuration to connect with right now. */
    suspend fun resolve(server: ServerConfig): ServerConfig = server.forRoute(route(server))

    suspend fun route(server: ServerConfig): Route {
        val net = _network.value
        val key = "${net?.id}|${server.id}|${server.localUrl}|${server.localPinnedCertSha256}|${server.routeMode}|${server.authMethod}"
        cache[key]?.let { return it.also { remember(server.id, it) } }
        val mutex = locks.getOrPut(server.id) { Mutex() }
        return mutex.withLock {
            cache[key] ?: decide(server.routeMode, server.localUsable, net?.kind) { probe(server) }
                .also { r -> cache[key] = r }
        }.also { remember(server.id, it) }
    }

    /** Forget cached decisions (e.g. after the local address failed although it was reachable a moment ago). */
    fun invalidate(serverId: String) {
        cache.keys.removeAll { it.split('|').getOrNull(1) == serverId }
    }

    private fun remember(id: String, r: Route) = _lastRoute.update { if (it[id] == r) it else it + (id to r) }

    companion object {
        fun kindOf(caps: NetworkCapabilities): NetState.Kind = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> NetState.Kind.VPN
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetState.Kind.LAN
            else -> NetState.Kind.MOBILE
        }

        /**
         * Pure routing rule (unit tested).
         * - No usable local address, or "Always remote": remote.
         * - "Always local": local.
         * - Auto: local only when on Wi-Fi/Ethernet/VPN (or unknown) and the local address answered; otherwise remote.
         */
        suspend fun decide(mode: RouteMode, localUsable: Boolean, network: NetState.Kind?, probe: suspend () -> Boolean): Route = when {
            !localUsable || mode == RouteMode.REMOTE -> Route.REMOTE
            mode == RouteMode.LOCAL -> Route.LOCAL
            network == NetState.Kind.MOBILE -> Route.REMOTE
            probe() -> Route.LOCAL
            else -> Route.REMOTE
        }

        /** Any HTTP answer over a trusted (or pinned) connection means the local address is usable. */
        suspend fun probeLocal(server: ServerConfig): Boolean = withContext(Dispatchers.IO) {
            val local = server.forRoute(Route.LOCAL)
            if (local.activeRoute != Route.LOCAL) return@withContext false
            val (client, _) = HttpClients.create(local, Keepalive.NONE)
            val quick = client.newBuilder()
                .connectTimeout(1500, TimeUnit.MILLISECONDS)
                .readTimeout(2500, TimeUnit.MILLISECONDS)
                .callTimeout(3500, TimeUnit.MILLISECONDS)
                .retryOnConnectionFailure(false)
                .build()
            runCatching {
                quick.newCall(Request.Builder().url(local.url.trimEnd('/') + "/api/versions").head().build()).execute().use { true }
            }.getOrDefault(false)
        }
    }
}
