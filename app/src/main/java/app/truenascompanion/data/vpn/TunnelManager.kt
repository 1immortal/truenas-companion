package app.truenascompanion.data.vpn

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import app.truenascompanion.data.model.ServerConfig
import com.wireguard.android.backend.BackendException
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Why the built-in tunnel couldn't be used. The app then falls back to the next address (Tailscale / remote). */
sealed class TunnelFailure(val message: String) {
    /** The user hasn't allowed the app to create a VPN yet (Android asks once). */
    data object NeedsConsent : TunnelFailure("Allow YTN to set up its VPN connection first (VPN settings of this server).")
    /** Another VPN app (Tailscale, a work VPN, …) is connected. Starting ours would disconnect it, so we don't. */
    data object ForeignVpn : TunnelFailure("Another VPN app is connected on this phone, so the built-in tunnel stays off (Android allows one VPN at a time). Using the remote address.")
    /** Android refused to create the tunnel: typically another app is set as Always-on VPN, or the permission was revoked. */
    data object Refused : TunnelFailure("Android refused to start the tunnel. If another app is set as Always-on VPN (Settings › Network › VPN), turn that off or use its VPN instead.")
    /** The WireGuard server didn't answer (router port forward missing, UDP blocked, or the NAS is offline). */
    data object NoHandshake : TunnelFailure("No answer from your WireGuard server. Check the router's UDP port forward and that the NAS is on.")
    data class DnsFailed(val host: String) : TunnelFailure("Couldn't look up $host (the tunnel's endpoint).")
    /** A tunnel for another server is in use right now. */
    data object Busy : TunnelFailure("The tunnel is in use for another server right now.")
    data class Error(val detail: String) : TunnelFailure(detail)
}

enum class TunnelHolder {
    /** The app's own connection while it's open (released after the 30 s background grace). */
    APP,
    /** Instant alerts (foreground service) keep the tunnel up while enabled. */
    INSTANT,
    /** A periodic background alert check: up for a few seconds only. */
    CHECK,
    /** The "Test VPN" button. */
    TEST,
}

data class TunnelStatus(
    val serverId: String? = null,
    val state: Tunnel.State = Tunnel.State.DOWN,
    val connecting: Boolean = false,
    val lastHandshakeMs: Long = 0,
    val rxBytes: Long = 0,
    val txBytes: Long = 0,
    val lastFailure: TunnelFailure? = null,
    val failedServerId: String? = null,
    /** 1.7.1 (review P1-2): leases per holder, so two background checks holding it at once don't drop it for each other. */
    val leases: Map<TunnelHolder, Int> = emptyMap(),
) {
    val holders: Set<TunnelHolder> get() = leases.filterValues { it > 0 }.keys
}

/**
 * The app's single WireGuard tunnel (wireguard-android's userspace [GoBackend] on Android's [VpnService]).
 *
 * Reference counted: each [TunnelHolder] that needs the NAS through the tunnel [acquire]s it and [release]s it when
 * its connection closes; the tunnel goes down when nobody holds it. Nothing polls: the only periodic work is the
 * WireGuard handshake itself, and only while the tunnel is up.
 *
 * Background use (alert checks): Android blocks `startService` for apps in the background, which GoBackend would use
 * to create its VpnService. We bind to the service first (binding is allowed from the background), which creates it,
 * so GoBackend skips `startService`. Once the tunnel is established the system itself binds the VPN service with
 * foreground importance, so no foreground service (and none of Android 14's FGS-type rules) is involved.
 */
class TunnelManager(
    private val context: Context,
    /** Loads the saved (encrypted) config for a server. */
    private val configFor: suspend (ServerConfig) -> String?,
) {
    private val backend by lazy { GoBackend(context.applicationContext) }
    private val mutex = Mutex()
    private val _status = MutableStateFlow(TunnelStatus())
    val status: StateFlow<TunnelStatus> = _status.asStateFlow()

    @Volatile private var ownActiveSince = 0L
    @Volatile private var ownDownAt = 0L
    private var bound: ServiceConnection? = null
    private var tunnel: AppTunnel? = null

    private class AppTunnel(val serverId: String) : Tunnel {
        override fun getName() = "truenas" // [a-zA-Z0-9_=+.-]{1,15}
        override fun onStateChange(newState: Tunnel.State) = Unit
    }

    /** Our tunnel is up or being brought up (its VPN network is then not "another VPN"). */
    val ownTunnelActive: Boolean get() = ownActiveSince != 0L || SystemClock.elapsedRealtime() - ownDownAt < 3_000

    fun needsConsent(): Boolean = runCatching { VpnService.prepare(context) != null }.getOrDefault(true)

    /** The system consent dialog, or null when already allowed. */
    fun consentIntent(): Intent? = runCatching { VpnService.prepare(context) }.getOrNull()

    /** A VPN network exists that isn't ours (Tailscale, a work VPN, another WireGuard app). */
    fun foreignVpnActive(): Boolean {
        if (ownTunnelActive) return false
        val cm = ContextCompat.getSystemService(context, ConnectivityManager::class.java) ?: return false
        @Suppress("DEPRECATION")
        return runCatching { cm.allNetworks.any { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true } }
            .getOrDefault(false)
    }

    /**
     * 1.7.1: true only if a connected VPN looks like the Tailscale app's: one of its addresses is in Tailscale's ranges
     * (100.64.0.0/10 or fd7a:115c:a1e0::/48). Any other VPN (a work VPN, an ad blocker…) no longer counts as Tailscale.
     * The Tailscale route additionally needs HTTPS with a pinned or valid certificate, so a look-alike VPN still can't
     * receive credentials.
     */
    fun tailscaleVpnActive(): Boolean {
        val cm = ContextCompat.getSystemService(context, ConnectivityManager::class.java) ?: return false
        @Suppress("DEPRECATION")
        return runCatching {
            cm.allNetworks.any { n ->
                cm.getNetworkCapabilities(n)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true &&
                    cm.getLinkProperties(n)?.linkAddresses.orEmpty().any { isTailscaleAddress(it.address) }
            }
        }.getOrDefault(false)
    }

    /** True if the tunnel is up for [serverId]. */
    fun isUpFor(serverId: String): Boolean = _status.value.let { it.serverId == serverId && it.state == Tunnel.State.UP }

    /**
     * Brings the tunnel up for [server] (or joins it when already up) and waits for the first handshake.
     * @return null on success, otherwise why it can't be used.
     */
    suspend fun acquire(server: ServerConfig, holder: TunnelHolder, handshakeTimeoutMs: Long = 8_000): TunnelFailure? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val st = _status.value
            if (st.state == Tunnel.State.UP && st.serverId == server.id) {
                _status.update { it.copy(leases = it.leases + (holder to (it.leases[holder] ?: 0) + 1)) }
                return@withLock null
            }
            if (st.state == Tunnel.State.UP && st.holders.isNotEmpty()) return@withLock TunnelFailure.Busy
            val failure = try {
                bringUp(server, handshakeTimeoutMs)
            } catch (e: kotlinx.coroutines.CancellationException) {
                // 1.7.1 (review P1-3): a cancelled bring-up must not leave a half-up tunnel nobody holds.
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { bringDown() }
                throw e
            }
            if (failure == null) _status.update { it.copy(leases = mapOf(holder to 1), lastFailure = null, failedServerId = null) }
            else _status.update { it.copy(lastFailure = failure, failedServerId = server.id) }
            failure
        }
    }

    suspend fun release(serverId: String, holder: TunnelHolder) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val st = _status.value
            if (st.serverId != serverId || holder !in st.holders) return@withLock
            val n = (st.leases[holder] ?: 0) - 1
            val left = if (n > 0) st.leases + (holder to n) else st.leases - holder
            _status.update { it.copy(leases = left) }
            if (left.isEmpty()) bringDown()
        }
    }

    /** Drops the tunnel regardless of holders (config removed / changed, server deleted). */
    suspend fun stop() = withContext(Dispatchers.IO) { mutex.withLock { bringDown() } }

    /** Current transfer counters and handshake time (for the VPN screen and "Test VPN"). */
    suspend fun refreshStats() = withContext(Dispatchers.IO) {
        val t = tunnel ?: return@withContext
        runCatching {
            val stats = backend.getStatistics(t)
            val hs = stats.peers().maxOfOrNull { stats.peer(it)?.latestHandshakeEpochMillis ?: 0L } ?: 0L
            _status.update { it.copy(lastHandshakeMs = hs, rxBytes = stats.totalRx(), txBytes = stats.totalTx()) }
        }
    }

    private suspend fun bringUp(server: ServerConfig, handshakeTimeoutMs: Long): TunnelFailure? {
        if (foreignVpnActive()) return TunnelFailure.ForeignVpn
        if (needsConsent()) return TunnelFailure.NeedsConsent
        val nasIp = server.lanIp ?: return TunnelFailure.Error("The built-in tunnel needs the NAS's local IP address (Home network).")
        val raw = configFor(server) ?: return TunnelFailure.Error("No WireGuard config saved for ${server.name}.")
        val config = try {
            Config.parse(WgConf.forApp(raw, nasIp, context.packageName).byteInputStream())
        } catch (e: WgConf.Invalid) {
            return TunnelFailure.Error(e.message ?: "Invalid WireGuard config.")
        } catch (e: Exception) {
            return TunnelFailure.Error("Invalid WireGuard config: ${e.message}")
        }
        if (tunnel != null) bringDown()
        ownActiveSince = SystemClock.elapsedRealtime()
        _status.update { it.copy(serverId = server.id, connecting = true) }
        bindService()
        val t = AppTunnel(server.id)
        try {
            backend.setState(t, Tunnel.State.UP, config)
        } catch (e: BackendException) {
            Log.i(TAG, "tunnel up failed: ${e.reason}")
            cleanupAfterFailure()
            return when (e.reason) {
                BackendException.Reason.VPN_NOT_AUTHORIZED -> TunnelFailure.NeedsConsent
                BackendException.Reason.TUN_CREATION_ERROR, BackendException.Reason.UNABLE_TO_START_VPN -> TunnelFailure.Refused
                BackendException.Reason.DNS_RESOLUTION_FAILURE -> TunnelFailure.DnsFailed(e.format.firstOrNull()?.toString() ?: "the endpoint")
                else -> TunnelFailure.Error("The tunnel couldn't start (${e.reason}).")
            }
        } catch (e: Exception) {
            Log.i(TAG, "tunnel up failed", e)
            cleanupAfterFailure()
            return TunnelFailure.Error("The tunnel couldn't start: ${e.message ?: e.javaClass.simpleName}")
        }
        tunnel = t
        // A fresh tunnel reports handshake time 0 until the server answered. WireGuard only starts the handshake once
        // there is something to send, so poke the NAS address (one tiny UDP datagram to the discard port, routed through
        // the tunnel because only this app and only the NAS /32 use it) about once a second until the handshake is done.
        var lastPoke = 0L
        val handshake = withTimeoutOrNull(handshakeTimeoutMs) {
            while (true) {
                if (SystemClock.elapsedRealtime() - lastPoke >= 1_000) {
                    lastPoke = SystemClock.elapsedRealtime()
                    poke(nasIp)
                }
                val stats = runCatching { backend.getStatistics(t) }.getOrNull()
                val hs = stats?.peers()?.maxOfOrNull { stats.peer(it)?.latestHandshakeEpochMillis ?: 0L } ?: 0L
                if (hs > 0) return@withTimeoutOrNull hs
                delay(250)
            }
            @Suppress("UNREACHABLE_CODE") 0L
        }
        if (handshake == null) {
            bringDown()
            return TunnelFailure.NoHandshake
        }
        _status.update { it.copy(serverId = server.id, state = Tunnel.State.UP, connecting = false, lastHandshakeMs = handshake) }
        return null
    }

    private fun poke(ip: String) {
        runCatching {
            java.net.DatagramSocket().use { socket ->
                socket.send(java.net.DatagramPacket(byteArrayOf(0), 1, java.net.InetAddress.getByName(ip), 9))
            }
        }
    }

    private fun cleanupAfterFailure() {
        tunnel = null
        unbindService()
        markDown()
    }

    private fun bringDown() {
        val t = tunnel
        tunnel = null
        if (t != null) runCatching { backend.setState(t, Tunnel.State.DOWN, null) }.onFailure { Log.w(TAG, "tunnel down failed", it) }
        unbindService()
        markDown()
    }

    private fun markDown() {
        if (ownActiveSince != 0L) ownDownAt = SystemClock.elapsedRealtime()
        ownActiveSince = 0L
        _status.update { it.copy(state = Tunnel.State.DOWN, connecting = false, leases = emptyMap(), rxBytes = 0, txBytes = 0) }
    }

    /**
     * Binds GoBackend's VpnService so it is created (startService is blocked in the background) and waits until it is
     * up. 1.7.1 (review P0-1): readiness comes from the binding callback (onServiceConnected / onNullBinding run after the
     * service's onCreate, which is where GoBackend publishes it), not from reflection on a private field that R8 renames
     * in release builds. The field is also kept by a proguard rule, and still read as a second signal.
     */
    private fun bindService() {
        if (bound != null) return
        val ready = java.util.concurrent.CountDownLatch(1)
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) = ready.countDown()
            override fun onServiceDisconnected(name: ComponentName?) = Unit
            override fun onNullBinding(name: ComponentName?) = ready.countDown()
        }
        val ok = runCatching {
            context.applicationContext.bindService(Intent(context, GoBackend.VpnService::class.java), conn, Context.BIND_AUTO_CREATE)
        }.getOrDefault(false)
        if (!ok) return
        bound = conn
        val deadline = SystemClock.elapsedRealtime() + 1_500
        runCatching { ready.await(1_500, java.util.concurrent.TimeUnit.MILLISECONDS) }
        while (SystemClock.elapsedRealtime() < deadline && serviceReady() == false) Thread.sleep(20)
    }

    /** null = can't tell (field missing), true/false = GoBackend's service future state. */
    private fun serviceReady(): Boolean? = runCatching {
        val f = GoBackend::class.java.getDeclaredField("vpnService").apply { isAccessible = true }
        (f.get(null) as java.util.concurrent.CompletableFuture<*>).isDone
    }.getOrNull()

    private fun unbindService() {
        val conn = bound ?: return
        bound = null
        runCatching { context.applicationContext.unbindService(conn) }
    }

    private companion object { const val TAG = "TunnelManager" }
}

/** Tailscale assigns node addresses from 100.64.0.0/10 (CGNAT) and fd7a:115c:a1e0::/48. Pure, unit tested. */
fun isTailscaleAddress(a: java.net.InetAddress): Boolean {
    val b = a.address
    return when (b.size) {
        4 -> (b[0].toInt() and 0xFF) == 100 && (b[1].toInt() and 0xC0) == 0x40
        16 -> (b[0].toInt() and 0xFF) == 0xFD && (b[1].toInt() and 0xFF) == 0x7A && (b[2].toInt() and 0xFF) == 0x11 &&
            (b[3].toInt() and 0xFF) == 0x5C && (b[4].toInt() and 0xFF) == 0xA1 && (b[5].toInt() and 0xFF) == 0xE0
        else -> false
    }
}
