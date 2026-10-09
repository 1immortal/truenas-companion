package app.truenascompanion.ui.vpn

import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.truenascompanion.AppContainer
import app.truenascompanion.data.api.userMessage
import app.truenascompanion.data.model.ServerConfig
import app.truenascompanion.data.model.VpnMode
import app.truenascompanion.data.vpn.VpnSetup
import app.truenascompanion.data.vpn.WgConf
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class SetupStep { CHOOSE, WG_FORM, WG_EXISTING, WORKING, WG_DONE, WG_MANUAL, TS_FORM, TS_DONE }

data class SetupUi(
    val step: SetupStep = SetupStep.CHOOSE,
    val server: ServerConfig? = null,
    val busy: Boolean = false,
    val error: String? = null,
    // WireGuard
    val publicHost: String = "",
    val port: String = VpnSetup.WG_LISTEN_PORT.toString(),
    /** Generated admin password: only in memory, shown on this screen, never saved by the app. */
    val password: String = "",
    val existingUser: String = VpnSetup.WG_ADMIN,
    val existingPassword: String = "",
    val manualReason: String? = null,
    // Tailscale
    val authKey: String = "",
    val hostname: String = "truenas",
    val advertise: Boolean = false,
    val subnet: String? = null,
    // progress
    val progress: String = "",
    val percent: Int? = null,
) {
    val lanIp: String? get() = server?.lanIp
    val wgWebUrl: String? get() = lanIp?.let { "http://$it:${VpnSetup.WG_WEB_PORT}" }
    val portNumber: Int? get() = port.toIntOrNull()?.takeIf { it in 1..65535 }
    val wgFormValid: Boolean get() = VpnSetup.validPublicHost(publicHost.trim()) && portNumber != null && lanIp != null
    val tsFormValid: Boolean get() = authKey.trim().startsWith("tskey-") && VpnSetup.validHostname(hostname.trim())
}

/**
 * The guided "Set up VPN" flow. Every install goes through a confirmation (with fingerprint when "Confirm dangerous
 * actions" is on). Runs against the active server's connection, so the server is made active first.
 */
class VpnSetupViewModel(private val c: AppContainer, private val serverId: String) : ViewModel() {
    private val _ui = MutableStateFlow(SetupUi())
    val ui: StateFlow<SetupUi> = _ui.asStateFlow()
    val tailscale = TailscaleAddressController(c, serverId, viewModelScope)
    private var work: Job? = null

    init {
        viewModelScope.launch {
            val s = c.settings.servers.first().firstOrNull { it.id == serverId } ?: return@launch
            if (c.repository.activeServer.value?.id != serverId) c.settings.setActiveServer(serverId)
            _ui.update { it.copy(server = s, publicHost = VpnSetup.publicHost(s.url).orEmpty()) }
        }
    }

    fun update(f: (SetupUi) -> SetupUi) = _ui.update { f(it).copy(error = null) }

    fun back(): Boolean {
        val st = _ui.value.step
        if (st == SetupStep.WORKING) return true
        if (st == SetupStep.CHOOSE) return false
        _ui.update { it.copy(step = SetupStep.CHOOSE, error = null) }
        return true
    }

    // --- WireGuard ---

    fun chooseWireGuard() {
        val s = _ui.value.server ?: return
        if (s.lanIp == null) {
            _ui.update { it.copy(error = "First set the NAS's local IP address in the server settings (Home network, e.g. https://192.168.1.10). The tunnel connects to that address, and the setup talks to wg-easy on it.") }
            return
        }
        _ui.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                val exists = c.repository.call { VpnSetup.appExists(it, VpnSetup.WG_APP) }
                _ui.update {
                    it.copy(
                        busy = false,
                        step = if (exists) SetupStep.WG_MANUAL else SetupStep.WG_FORM,
                        manualReason = if (exists) "wg-easy is already installed. Sign in to its web page, add a client for this phone, then scan its QR code." else it.manualReason,
                        password = if (exists) "" else it.password.ifEmpty { VpnSetup.generatePassword() },
                    )
                }
            } catch (e: Throwable) {
                _ui.update { it.copy(busy = false, error = e.userMessage()) }
            }
        }
    }

    /** After the user confirmed (and passed the fingerprint check). */
    fun installWireGuard() {
        val u = _ui.value
        if (!u.wgFormValid) return
        val host = u.publicHost.trim()
        val port = u.portNumber!!
        val lanIp = u.lanIp!!
        work = viewModelScope.launch {
            _ui.update { it.copy(step = SetupStep.WORKING, progress = "Installing wg-easy from the TrueNAS catalog…", percent = null) }
            try {
                val job = c.repository.call { it.startAppInstall(VpnSetup.WG_APP, VpnSetup.WG_APP, VpnSetup.WG_TRAIN, "latest", VpnSetup.wgEasyValues()) }
                c.repository.call { api ->
                    VpnSetup.awaitJob(api, job) { pct, text -> _ui.update { it.copy(percent = pct, progress = "Installing wg-easy: ${text ?: "working"}…") } }
                }
            } catch (e: Throwable) {
                val msg = e.userMessage()
                _ui.update { it.copy(step = SetupStep.WG_FORM, error = msg + (VpnSetup.installHint(msg, VpnSetup.WG_TRAIN)?.let { h -> "\n\n$h" } ?: "")) }
                return@launch
            }
            _ui.update { it.copy(progress = "Waiting for wg-easy to start…", percent = null) }
            // 1.7.1 (security H-2): wg-easy's web page is plain http, so the app no longer sends a wg-easy password to it.
            // It only waits until the port answers (a bare TCP connect, nothing sent); the admin account and this phone's
            // client are created in wg-easy's page, and its QR code is scanned here.
            val up = VpnSetup.waitForPort(lanIp, VpnSetup.WG_WEB_PORT, 180_000)
            manual(
                if (up) "wg-easy is installed and running. Create its admin account with the password below, add a client for this phone, then scan its QR code."
                else "wg-easy is installed, but it didn't answer at http://$lanIp:${VpnSetup.WG_WEB_PORT} yet. Is this phone on your home Wi-Fi? Is the app running in TrueNAS › Apps?",
            )
        }
    }

    /** wg-easy was installed before: add this phone in its web page and scan the QR code (1.7.1: no password over http). */
    fun useExisting() = manual("wg-easy is already installed. Sign in to its web page, add a client for this phone, then scan its QR code.")

    private fun manual(reason: String) = _ui.update { it.copy(step = SetupStep.WG_MANUAL, manualReason = reason, progress = "") }

    // --- Tailscale ---

    fun chooseTailscale() {
        _ui.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                val exists = c.repository.call { VpnSetup.appExists(it, VpnSetup.TS_APP) }
                val summary = c.repository.call { VpnSetup.networkSummary(it) }
                val subnet = _ui.value.lanIp?.let { VpnSetup.lanSubnet(summary, it) }
                _ui.value.server?.let { tailscale.load(it, "https://${_ui.value.hostname}") }
                _ui.update { it.copy(busy = false, subnet = subnet, step = if (exists) SetupStep.TS_DONE else SetupStep.TS_FORM) }
            } catch (e: Throwable) {
                _ui.update { it.copy(busy = false, error = e.userMessage()) }
            }
        }
    }

    fun installTailscale() {
        val u = _ui.value
        if (!u.tsFormValid) return
        val routes = if (u.advertise && u.subnet != null) listOf(u.subnet) else emptyList()
        work = viewModelScope.launch {
            _ui.update { it.copy(step = SetupStep.WORKING, progress = "Installing Tailscale from the TrueNAS catalog…", percent = null) }
            try {
                val job = c.repository.call { it.startAppInstall(VpnSetup.TS_APP, VpnSetup.TS_APP, VpnSetup.TS_TRAIN, "latest", VpnSetup.tailscaleValues(u.authKey, u.hostname, routes)) }
                c.repository.call { api ->
                    VpnSetup.awaitJob(api, job) { pct, text -> _ui.update { it.copy(percent = pct, progress = "Installing Tailscale: ${text ?: "working"}…") } }
                }
                u.server?.let { tailscale.load(it, "https://${u.hostname.trim()}") }
                _ui.update { it.copy(step = SetupStep.TS_DONE, authKey = "", progress = "") }
            } catch (e: Throwable) {
                val msg = e.userMessage()
                _ui.update { it.copy(step = SetupStep.TS_FORM, error = msg + (VpnSetup.installHint(msg, VpnSetup.TS_TRAIN)?.let { h -> "\n\n$h" } ?: "")) }
            }
        }
    }

    override fun onCleared() {
        work?.cancel()
    }
}
