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
import app.truenascompanion.data.vpn.WgEasyClient
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
                        step = if (exists) SetupStep.WG_EXISTING else SetupStep.WG_FORM,
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
            val client = WgEasyClient("http://$lanIp:${VpnSetup.WG_WEB_PORT}")
            _ui.update { it.copy(progress = "Waiting for wg-easy to start…", percent = null) }
            if (!client.waitUntilUp(180_000)) {
                manual("wg-easy is installed, but the app couldn't reach it at http://$lanIp:${VpnSetup.WG_WEB_PORT}. Is this phone on your home Wi-Fi? Is the app running in TrueNAS › Apps?")
                return@launch
            }
            try {
                _ui.update { it.copy(progress = "Creating the wg-easy admin account…") }
                client.setupAdmin(VpnSetup.WG_ADMIN, u.password)
                client.setupHost(host, port)
            } catch (e: WgEasyClient.Failure.AlreadySetUp) {
                _ui.update { it.copy(step = SetupStep.WG_EXISTING, error = "wg-easy already has an admin account. Sign in with it to create this phone's client.") }
                return@launch
            } catch (e: Throwable) {
                manual("Couldn't finish wg-easy's first-run setup: ${e.message ?: e.javaClass.simpleName}")
                return@launch
            }
            createAndImport(client, VpnSetup.WG_ADMIN, u.password)
        }
    }

    /** wg-easy was installed before: create the client with the user's wg-easy login (kept in memory only). */
    fun useExisting() {
        val u = _ui.value
        val lanIp = u.lanIp ?: return
        if (u.existingUser.isBlank() || u.existingPassword.isEmpty()) return
        work = viewModelScope.launch {
            _ui.update { it.copy(step = SetupStep.WORKING, progress = "Connecting to wg-easy…", percent = null) }
            val client = WgEasyClient("http://$lanIp:${VpnSetup.WG_WEB_PORT}")
            if (!client.isUp()) {
                manual("Couldn't reach wg-easy at http://$lanIp:${VpnSetup.WG_WEB_PORT}. If it uses another port, open its web UI and scan the QR code instead.")
                return@launch
            }
            createAndImport(client, u.existingUser.trim(), u.existingPassword)
        }
    }

    private suspend fun createAndImport(client: WgEasyClient, user: String, password: String) {
        val s = _ui.value.server ?: return
        try {
            _ui.update { it.copy(progress = "Creating a VPN client for this phone…") }
            val id = client.createClient(user, password, VpnSetup.clientName(Build.MODEL ?: "phone"))
            val conf = client.clientConfig(user, password, id)
            WgConf.forApp(conf, s.lanIp!!, "check") // validates and checks the NAS IP is covered
            c.settings.saveWireGuard(serverId, conf.trim(), VpnMode.AUTO)
            c.routes.reset(serverId)
            _ui.update { it.copy(step = SetupStep.WG_DONE, existingPassword = "", progress = "") }
        } catch (e: WgEasyClient.Failure.AuthFailed) {
            _ui.update { it.copy(step = SetupStep.WG_EXISTING, existingPassword = "", error = e.message) }
        } catch (e: Throwable) {
            manual("Couldn't create the client automatically: ${e.message ?: e.javaClass.simpleName}")
        }
    }

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
