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

/** Everything about a server that matters for picking its route (pure data, see [RouteResolver.decide]). */
data class RouteOptions(
    val mode: RouteMode,
    val localUsable: Boolean,
    val tailscaleUsable: Boolean = false,
    val vpnMode: app.truenascompanion.data.model.VpnMode = app.truenascompanion.data.model.VpnMode.OFF,
    val wireGuardUsable: Boolean = false,
) {
    companion object {
        fun of(s: ServerConfig) = RouteOptions(s.routeMode, s.localUsable, s.tailscaleUsable, s.vpnMode, s.wireGuardUsable)
    }
}

/**
 * Picks how to reach each server: local address, Tailscale, the built-in WireGuard tunnel, or the remote address.
 *
 * Battery/latency: the decision is cached per (network, server) and only re-made when the default network changes
 * (a passive `registerDefaultNetworkCallback`, no polling). A probe is one short HTTPS request (1.5 s connect
 * timeout). The WireGuard tunnel isn't brought up here: callers [acquire] it only when they really connect.
 */
class RouteResolver(
    context: Context?,
    /** The built-in tunnel (null in tests / when unavailable). */
    var tunnels: app.truenascompanion.data.vpn.TunnelManager? = null,
    private val probe: suspend (ServerConfig) -> Boolean = ::probeLocal,
) {
    private val _network = MutableStateFlow<NetState?>(null)
    val network: StateFlow<NetState?> = _network.asStateFlow()

    /** Emits after the default network changed (not on registration). */
    val networkChanges: Flow<NetState?> = network.map { it?.id to it?.kind }.distinctUntilChanged().drop(1).map { network.value }

    private val cache = ConcurrentHashMap<String, Route>()
    private val locks = ConcurrentHashMap<String, Mutex>()
    /** Routes that failed on the current network, per server ("$serverId"), so the next decision skips them. */
    private val failed = ConcurrentHashMap<String, Set<Route>>()

    private val _lastRoute = MutableStateFlow<Map<String, Route>>(emptyMap())
    /** Last decided route per server id (for the route chip). */
    val lastRoute: StateFlow<Map<String, Route>> = _lastRoute.asStateFlow()

    /** Id of the last non-VPN default network, kept while our own tunnel is the default. */
    @Volatile private var underlyingId: String? = null

    init {
        context?.let { register(it) }
    }

    private fun register(context: Context) {
        val cm = ContextCompat.getSystemService(context, ConnectivityManager::class.java) ?: return
        runCatching {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    val own = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) && tunnels?.ownTunnelActive == true
                    val next = if (own) {
                        // Our own tunnel became the default for this app: the phone is still on the same Wi-Fi /
                        // mobile network underneath, so keep the network identity (no reconnect churn), but follow the
                        // underlying transport (VPN capabilities carry it) so leaving Wi-Fi is still noticed.
                        val kind = underlyingKindOf(caps) ?: _network.value?.kind ?: NetState.Kind.MOBILE
                        val id = underlyingId?.takeIf { _network.value?.kind == kind || _network.value == null } ?: "own:$kind:${network}"
                        NetState(id, kind)
                    } else {
                        NetState(network.toString(), kindOf(caps)).also { if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) underlyingId = it.id }
                    }
                    if (_network.value != next) {
                        _network.value = next
                        cache.clear() // only the current network's decisions are ever useful
                        failed.clear()
                    }
                }
                override fun onLost(network: Network) {
                    if (_network.value?.id == network.toString()) { _network.value = null; cache.clear(); failed.clear() }
                }
            })
        }
    }

    /** The configuration to connect with right now (decision only; see [acquire] for the tunnel). */
    suspend fun resolve(server: ServerConfig): ServerConfig = server.forRoute(route(server))

    suspend fun route(server: ServerConfig): Route {
        val net = _network.value
        val foreignVpn = tunnels?.foreignVpnActive() == true
        val skip = failed[server.id].orEmpty()
        val key = "${net?.id}|${server.id}|${server.localUrl}|${server.localPinnedCertSha256}|${server.routeMode}|${server.authMethod}|" +
            "${server.tailscaleUrl}|${server.tailscalePinnedCertSha256}|${server.vpnMode}|${server.wireGuardConfigured}|$foreignVpn|$skip"
        cache[key]?.let { return it.also { remember(server.id, it) } }
        val mutex = locks.getOrPut(server.id) { Mutex() }
        return mutex.withLock {
            cache[key] ?: decide(RouteOptions.of(server), net?.kind, foreignVpn, skip,
                probeLocal = { probe(server.forRoute(Route.LOCAL)) },
                probeTailscale = { probe(server.forRoute(Route.TAILSCALE)) },
            ).also { r -> cache[key] = r }
        }.also { remember(server.id, it) }
    }

    /**
     * Resolves [server] and, when the answer is the WireGuard tunnel, brings it up for [holder]. If the tunnel can't be
     * used (no consent, another VPN, no handshake…) the VPN route is skipped on this network and the next one is used.
     */
    suspend fun acquire(server: ServerConfig, holder: app.truenascompanion.data.vpn.TunnelHolder): ServerConfig {
        var target = resolve(server)
        if (target.activeRoute == Route.VPN) {
            val t = tunnels
            // acquire() returns null on success, so don't use ?: here.
            val failure = if (t == null) app.truenascompanion.data.vpn.TunnelFailure.Error("Tunnel unavailable") else t.acquire(server, holder)
            if (failure != null) {
                fail(server.id, Route.VPN)
                target = resolve(server)
            }
        }
        return target
    }

    /** Counterpart of [acquire] once the connection opened with [target] is closed. */
    suspend fun release(target: ServerConfig?, holder: app.truenascompanion.data.vpn.TunnelHolder) {
        if (target?.activeRoute == Route.VPN) tunnels?.release(target.id, holder)
    }

    /** [route] didn't work on this network (e.g. the tunnel came up but the NAS didn't answer): skip it until the network changes. */
    fun fail(serverId: String, route: Route) {
        failed.compute(serverId) { _, old -> old.orEmpty() + route }
        invalidate(serverId)
    }

    /** Forget cached decisions (e.g. after the local address failed although it was reachable a moment ago). */
    fun invalidate(serverId: String) {
        cache.keys.removeAll { it.split('|').getOrNull(1) == serverId }
    }

    /** Forget failures too (settings changed, user asked to retry). */
    fun reset(serverId: String) {
        failed.remove(serverId)
        invalidate(serverId)
    }

    private fun remember(id: String, r: Route) = _lastRoute.update { if (it[id] == r) it else it + (id to r) }

    companion object {
        fun kindOf(caps: NetworkCapabilities): NetState.Kind = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> NetState.Kind.VPN
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetState.Kind.LAN
            else -> NetState.Kind.MOBILE
        }

        /** The transport under a VPN (Android adds the underlying network's transports to the VPN's capabilities). */
        fun underlyingKindOf(caps: NetworkCapabilities): NetState.Kind? = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetState.Kind.LAN
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetState.Kind.MOBILE
            else -> null
        }

        /** 0.4 rule, kept for callers/tests without Tailscale or WireGuard. */
        suspend fun decide(mode: RouteMode, localUsable: Boolean, network: NetState.Kind?, probe: suspend () -> Boolean): Route =
            decide(RouteOptions(mode, localUsable), network, foreignVpn = false, skip = emptySet(), probeLocal = probe, probeTailscale = { false })

        /**
         * Pure routing rule (unit tested).
         * - "Always remote": remote. "Always local": local (if usable).
         * - Auto, in this order:
         *   1. WireGuard "Always on": the tunnel.
         *   2. Local, when on Wi-Fi/Ethernet/VPN (or unknown) and the local address answered.
         *   3. Tailscale, when another VPN is connected (the Tailscale app) and the Tailscale address answered.
         *   4. WireGuard "Auto": the tunnel, unless another VPN is connected (starting ours would disconnect it).
         *   5. Remote.
         * Routes in [skip] failed on this network and are passed over.
         */
        suspend fun decide(
            o: RouteOptions,
            network: NetState.Kind?,
            foreignVpn: Boolean,
            skip: Set<Route>,
            probeLocal: suspend () -> Boolean,
            probeTailscale: suspend () -> Boolean,
        ): Route {
            if (o.mode == RouteMode.REMOTE) return Route.REMOTE
            if (o.mode == RouteMode.LOCAL) return if (o.localUsable) Route.LOCAL else Route.REMOTE
            val wireGuard = o.wireGuardUsable && o.vpnMode != app.truenascompanion.data.model.VpnMode.OFF && !foreignVpn && Route.VPN !in skip
            if (wireGuard && o.vpnMode == app.truenascompanion.data.model.VpnMode.ALWAYS) return Route.VPN
            if (o.localUsable && Route.LOCAL !in skip && network != NetState.Kind.MOBILE && probeLocal()) return Route.LOCAL
            if (o.tailscaleUsable && Route.TAILSCALE !in skip && (foreignVpn || network == NetState.Kind.VPN) && probeTailscale()) return Route.TAILSCALE
            if (wireGuard) return Route.VPN
            return Route.REMOTE
        }

        /** Any HTTP answer over a trusted (or pinned) connection means the address is usable. */
        suspend fun probeLocal(server: ServerConfig): Boolean = withContext(Dispatchers.IO) {
            if (server.activeRoute == Route.REMOTE) return@withContext false
            val (client, _) = HttpClients.create(server, Keepalive.NONE)
            val quick = client.newBuilder()
                .connectTimeout(1500, TimeUnit.MILLISECONDS)
                .readTimeout(2500, TimeUnit.MILLISECONDS)
                .callTimeout(3500, TimeUnit.MILLISECONDS)
                .retryOnConnectionFailure(false)
                .build()
            runCatching {
                quick.newCall(Request.Builder().url(server.url.trimEnd('/') + "/api/versions").head().build()).execute().use { true }
            }.getOrDefault(false)
        }
    }
}
