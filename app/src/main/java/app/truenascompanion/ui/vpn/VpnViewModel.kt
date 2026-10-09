package app.truenascompanion.ui.vpn

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.truenascompanion.AppContainer
import app.truenascompanion.data.api.TrueNasException
import app.truenascompanion.data.api.userMessage
import app.truenascompanion.data.model.Route
import app.truenascompanion.data.model.ServerConfig
import app.truenascompanion.data.model.VpnMode
import app.truenascompanion.data.net.CertificateInfo
import app.truenascompanion.data.net.LocalCheck
import app.truenascompanion.data.net.NetState
import app.truenascompanion.data.vpn.TunnelHolder
import app.truenascompanion.data.vpn.TunnelStatus
import app.truenascompanion.data.vpn.WgConf
import app.truenascompanion.data.vpn.WgSummary
import app.truenascompanion.util.UrlUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Result of "Test VPN" / the Tailscale address check. */
data class CheckResult(val ok: Boolean, val text: String)

/** Tailscale address editing, shared by the VPN screen and the setup wizard. */
data class TailscaleUi(
    val input: String = "",
    val saved: String? = null,
    val checking: Boolean = false,
    val result: CheckResult? = null,
    val pendingCert: CertificateInfo? = null,
    val pin: String? = null,
)

class TailscaleAddressController(private val c: AppContainer, private val serverId: String, private val scope: CoroutineScope) {
    private val _ui = MutableStateFlow(TailscaleUi())
    val ui: StateFlow<TailscaleUi> = _ui.asStateFlow()

    fun load(server: ServerConfig, suggestion: String? = null) {
        _ui.value = TailscaleUi(input = server.tailscaleUrl ?: suggestion.orEmpty(), saved = server.tailscaleUrl, pin = server.tailscalePinnedCertSha256)
    }

    fun setInput(v: String) = _ui.update { it.copy(input = v, result = null) }

    /**
     * Checks the address (certificate + same NAS via `/api/boot_id`, no credentials) and saves it when it answers.
     * The NAS normally serves the same self-signed certificate on every address, so a certificate that matches the
     * already trusted local/remote one is accepted without asking again.
     */
    fun check() {
        val url = UrlUtils.normalize(_ui.value.input) ?: run { _ui.update { it.copy(result = CheckResult(false, "Enter an address like https://100.101.102.103 or https://homenas.")) }; return }
        _ui.update { it.copy(checking = true, result = null, input = url) }
        scope.launch {
            val server = c.settings.servers.first().firstOrNull { it.id == serverId } ?: return@launch
            val pin = _ui.value.pin ?: server.tailscalePinnedCertSha256
            val cfg = server.copy(tailscaleUrl = url, tailscalePinnedCertSha256 = pin).forRoute(Route.TAILSCALE)
            if (cfg.activeRoute != Route.TAILSCALE) {
                _ui.update { it.copy(checking = false, result = CheckResult(false, "Use an https:// address. The app only signs in over HTTPS (a self-signed certificate is fine).")) }
                return@launch
            }
            try {
                val same = LocalCheck.check(cfg, server.forRoute(Route.REMOTE))
                if (same == false) {
                    _ui.update { it.copy(checking = false, result = CheckResult(false, "A different TrueNAS answered at $url. Check the address.")) }
                    return@launch
                }
                c.settings.updateServer(serverId) { it.copy(tailscaleUrl = url, tailscalePinnedCertSha256 = pin) }
                c.routes.reset(serverId)
                _ui.update {
                    it.copy(
                        checking = false, saved = url, pin = pin,
                        result = CheckResult(true, if (same == true) "Reachable and confirmed it's the same NAS. Saved." else "Reachable. Saved."),
                    )
                }
            } catch (e: TrueNasException.UntrustedCertificate) {
                val cert = e.certificate
                if (cert != null && (cert.sha256 == server.localPinnedCertSha256 || cert.sha256 == server.pinnedCertSha256)) {
                    _ui.update { it.copy(pin = cert.sha256) }
                    check()
                } else {
                    _ui.update { it.copy(checking = false, pendingCert = cert, result = CheckResult(false, if (cert != null) "Review the NAS's certificate to continue." else e.userMessage())) }
                }
            } catch (e: Throwable) {
                val vpnOn = c.tunnels.foreignVpnActive()
                _ui.update {
                    it.copy(
                        checking = false,
                        result = CheckResult(false, e.userMessage() + if (!vpnOn) " Is the Tailscale app connected on this phone?" else ""),
                    )
                }
            }
        }
    }

    fun trustCert() {
        val cert = _ui.value.pendingCert ?: return
        _ui.update { it.copy(pin = cert.sha256, pendingCert = null) }
        check()
    }

    fun dismissCert() = _ui.update { it.copy(pendingCert = null) }

    fun remove() {
        scope.launch {
            c.settings.updateServer(serverId) { it.copy(tailscaleUrl = null, tailscalePinnedCertSha256 = null) }
            c.routes.reset(serverId)
            _ui.update { TailscaleUi() }
        }
    }
}

data class VpnUiState(
    val server: ServerConfig? = null,
    val summary: WgSummary? = null,
    /** The saved config can't be used as it is (e.g. the local address changed). */
    val configProblem: String? = null,
    val needsConsent: Boolean = false,
    val foreignVpn: Boolean = false,
    val onMobile: Boolean = false,
    /** A validated config waiting for the user's OK. */
    val pendingImport: String? = null,
    val pendingSummary: WgSummary? = null,
    val importError: String? = null,
    val testing: Boolean = false,
    val test: CheckResult? = null,
    val message: String? = null,
)

class VpnViewModel(private val c: AppContainer, val serverId: String) : ViewModel() {
    private val _state = MutableStateFlow(VpnUiState())
    val state: StateFlow<VpnUiState> = _state.asStateFlow()
    val tunnel: StateFlow<TunnelStatus> = c.tunnels.status
    val routes = c.routes.lastRoute
    val tip = c.settings.vpnTip(serverId)
    val tailscale = TailscaleAddressController(c, serverId, viewModelScope)

    /** One-shot: the UI should launch Android's VPN consent dialog. */
    private val _consent = MutableStateFlow(false)
    val consentRequest: StateFlow<Boolean> = _consent.asStateFlow()

    init {
        viewModelScope.launch {
            c.settings.servers.collect { list ->
                val s = list.firstOrNull { it.id == serverId } ?: return@collect
                val first = _state.value.server == null
                _state.update { it.copy(server = s) }
                if (first) tailscale.load(s)
                reloadSummary(s)
            }
        }
        refresh()
    }

    private suspend fun reloadSummary(s: ServerConfig) {
        val conf = if (s.wireGuardConfigured) c.settings.wireGuard(s.id) else null
        val summary = conf?.let { runCatching { WgConf.validate(it) }.getOrNull() }
        val problem = when {
            conf == null -> null
            s.lanIp == null -> "The tunnel connects to the NAS's local IP. Set the local address (Home network, e.g. https://192.168.1.10) in the server settings."
            !s.localUsable -> "The local address is plain http://, which the app doesn't use. Set its https:// address in the server settings (Home network › Auto-detect)."
            else -> runCatching { WgConf.forApp(conf, s.lanIp!!, "x"); null }.getOrElse { it.message }
        }
        _state.update { it.copy(summary = summary, configProblem = problem) }
    }

    /** Re-reads things that change outside the app (VPN consent, other VPNs, network). Called on resume. */
    fun refresh() {
        _state.update {
            it.copy(
                needsConsent = c.tunnels.needsConsent(),
                foreignVpn = c.tunnels.foreignVpnActive(),
                onMobile = c.routes.network.value?.kind == NetState.Kind.MOBILE,
            )
        }
        viewModelScope.launch { c.tunnels.refreshStats() }
    }

    // --- import ---

    fun offerImport(text: String) {
        val t = text.trim()
        val s = _state.value.server ?: return
        try {
            val summary = WgConf.validate(t)
            s.lanIp?.let { WgConf.forApp(t, it, "x") }
            _state.update { it.copy(pendingImport = t, pendingSummary = summary, importError = null) }
        } catch (e: WgConf.Invalid) {
            _state.update { it.copy(importError = e.message) }
        }
    }

    fun cancelImport() = _state.update { it.copy(pendingImport = null, pendingSummary = null) }
    fun clearImportError() = _state.update { it.copy(importError = null) }

    fun confirmImport() {
        val text = _state.value.pendingImport ?: return
        val mode = _state.value.server?.vpnMode?.takeIf { it != VpnMode.OFF } ?: VpnMode.AUTO
        viewModelScope.launch {
            if (c.tunnels.isUpFor(serverId)) c.tunnels.stop()
            c.settings.saveWireGuard(serverId, text, mode)
            c.routes.reset(serverId)
            _state.update { it.copy(pendingImport = null, pendingSummary = null, message = "WireGuard config saved (encrypted).") }
            if (c.tunnels.needsConsent()) _consent.value = true
        }
    }

    fun setMode(mode: VpnMode) {
        viewModelScope.launch {
            c.settings.updateServer(serverId) { it.copy(vpnMode = mode) }
            c.routes.reset(serverId)
            if (mode == VpnMode.OFF && c.tunnels.isUpFor(serverId)) c.tunnels.stop()
            if (mode != VpnMode.OFF && c.tunnels.needsConsent()) _consent.value = true
        }
    }

    fun removeWireGuard() {
        viewModelScope.launch {
            if (c.tunnels.isUpFor(serverId)) c.tunnels.stop()
            c.settings.clearWireGuard(serverId)
            c.routes.reset(serverId)
            _state.update { it.copy(test = null, message = "WireGuard config removed from this phone.") }
        }
    }

    fun requestConsent() { _consent.value = true }
    fun consentLaunched() { _consent.value = false }

    fun onConsentResult(granted: Boolean) {
        refresh()
        c.routes.reset(serverId)
        if (!granted && c.tunnels.needsConsent()) _state.update {
            it.copy(
                message = "VPN permission not given. If another app is set as Always-on VPN (Settings › Network › VPN), Android won't let a second app start a VPN: " +
                    "turn that off, or leave the tunnel off and use Tailscale / the remote address.",
            )
        }
    }

    fun clearMessage() = _state.update { it.copy(message = null) }

    // --- test ---

    /**
     * Brings the tunnel up (unless it is already), waits for the WireGuard handshake and asks the NAS for its boot id
     * through it: proves the router forward, the keys and that the right NAS answers. Takes up to ~12 s.
     */
    fun test() {
        val s = _state.value.server ?: return
        if (_state.value.testing) return
        refresh()
        if (c.tunnels.needsConsent()) { _consent.value = true; return }
        _state.update { it.copy(testing = true, test = null) }
        viewModelScope.launch {
            val onWifi = c.routes.network.value?.kind == NetState.Kind.LAN
            val note = if (onWifi) "\n\nYou're on Wi-Fi. For a real test from outside, turn Wi-Fi off (mobile data) and test again." else ""
            val failure = c.tunnels.acquire(s, TunnelHolder.TEST, handshakeTimeoutMs = 10_000)
            val result = if (failure != null) {
                CheckResult(false, failure.message + note)
            } else try {
                c.tunnels.refreshStats()
                val same = LocalCheck.check(s.forRoute(Route.VPN), s.forRoute(Route.REMOTE))
                c.settings.markVpnWorked(serverId)
                CheckResult(true, "Handshake OK and the NAS answered through the tunnel" + (if (same == true) " (confirmed it's the same NAS)." else ".") + note)
            } catch (e: Throwable) {
                CheckResult(false, "The handshake worked, but the NAS didn't answer at ${s.lanIp} through the tunnel: ${e.userMessage()}$note")
            } finally {
                c.tunnels.release(s.id, TunnelHolder.TEST)
            }
            _state.update { it.copy(testing = false, test = result) }
        }
    }

    fun dismissTip() = viewModelScope.launch { c.settings.dismissVpnTip(serverId) }
}
