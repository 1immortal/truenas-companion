package app.truenascompanion.screenshots

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.truenascompanion.data.model.AuthMethod
import app.truenascompanion.data.model.Route
import app.truenascompanion.data.model.ServerConfig
import app.truenascompanion.data.model.VpnMode
import app.truenascompanion.data.store.ThemeMode
import app.truenascompanion.data.vpn.TunnelHolder
import app.truenascompanion.data.vpn.TunnelStatus
import app.truenascompanion.data.vpn.WgSummary
import app.truenascompanion.ui.components.RouteChip
import app.truenascompanion.ui.theme.TrueNasTheme
import app.truenascompanion.ui.vpn.CheckResult
import app.truenascompanion.ui.vpn.SetupActions
import app.truenascompanion.ui.vpn.SetupStep
import app.truenascompanion.ui.vpn.SetupUi
import app.truenascompanion.ui.vpn.TailscaleUi
import app.truenascompanion.ui.vpn.VpnActions
import app.truenascompanion.ui.vpn.VpnContent
import app.truenascompanion.ui.vpn.VpnSetupContent
import app.truenascompanion.ui.vpn.VpnUiState
import com.github.takahirom.roborazzi.captureRoboImage
import com.wireguard.android.backend.Tunnel
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** v0.6.0 previews: built-in WireGuard, Tailscale and the guided VPN setup. Example data only. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h2000dp-xxhdpi", application = android.app.Application::class)
class V06ScreenshotTest {
    @get:Rule val rule = createComposeRule()
    private val dir = File(System.getProperty("screenshot.dir") ?: "build/screenshots").apply { mkdirs() }
    private fun out(name: String) = File(dir, "$name.png").absolutePath

    @Composable
    private fun Frame(dark: Boolean, content: @Composable () -> Unit) {
        val d = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(d.density, 1f)) {
            TrueNasTheme(themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT, dynamicColor = false) {
                Surface(color = MaterialTheme.colorScheme.background) { content() }
            }
        }
    }

    private fun shot(name: String, dark: Boolean, height: Int? = null, content: @Composable () -> Unit) {
        rule.mainClock.autoAdvance = false
        rule.setContent { Frame(dark) { Box(if (height != null) Modifier.fillMaxWidth().height(height.dp) else Modifier.fillMaxWidth()) { content() } } }
        rule.mainClock.advanceTimeBy(1500)
        rule.onRoot().captureRoboImage(out(name))
    }

    private val base = ServerConfig(
        "s1", "homenas", "https://homenas.example.org", username = "admin", authMethod = AuthMethod.PASSWORD,
        localUrl = "https://192.168.1.10",
    )
    private val configured = base.copy(
        wireGuardConfigured = true, vpnMode = VpnMode.AUTO, tailscaleUrl = "https://truenas.tail1234.ts.net",
    )
    private val summary = WgSummary(
        endpoint = "homenas.example.org:51820", addresses = listOf("10.8.0.2/24"), allowedIps = listOf("0.0.0.0/0", "::/0"),
        dns = listOf("1.1.1.1"), peerPublicKey = "bXlFeGFtcGxlUHVibGljS2V5Rm9yUHJldmlld3MwMDA=", peers = 1,
    )
    private val up = TunnelStatus(
        serverId = "s1", state = Tunnel.State.UP, lastHandshakeMs = System.currentTimeMillis() - 18_000,
        rxBytes = 184_320, txBytes = 61_440, leases = mapOf(TunnelHolder.APP to 1),
    )

    @Test fun vpnNotConfigured() = shot("v06_vpn_empty", dark = true, height = 1500) {
        VpnContent(VpnUiState(server = base), TunnelStatus(), Route.REMOTE, tip = false, ts = TailscaleUi(), actions = VpnActions())
    }

    @Test fun vpnConfigured() = shot("v06_vpn_up", dark = true) {
        VpnContent(
            VpnUiState(server = configured, summary = summary, onMobile = true, test = CheckResult(true, "Handshake OK, the NAS answered through the tunnel.")),
            up, Route.VPN, tip = true,
            ts = TailscaleUi(input = "https://truenas.tail1234.ts.net", saved = "https://truenas.tail1234.ts.net"),
            actions = VpnActions(), tailscaleInstalled = false,
        )
    }

    @Test fun vpnConfiguredLight() = shot("v06_vpn_up_light", dark = false) {
        VpnContent(
            VpnUiState(server = configured.copy(vpnMode = VpnMode.ALWAYS), summary = summary, foreignVpn = true),
            TunnelStatus(), Route.TAILSCALE, tip = false,
            ts = TailscaleUi(input = "https://truenas.tail1234.ts.net", saved = "https://truenas.tail1234.ts.net"),
            actions = VpnActions(), tailscaleInstalled = true,
        )
    }

    private fun setup(name: String, ui: SetupUi, test: CheckResult? = null, tip: Boolean = false, ts: TailscaleUi = TailscaleUi(), dark: Boolean = true) =
        shot(name, dark, height = 1500) { VpnSetupContent(ui, ts, testing = false, test = test, tip = tip, a = SetupActions()) }

    @Test fun setupChoose() = setup("v06_setup_choose", SetupUi(server = base))

    @Test fun setupWgForm() = setup(
        "v06_setup_wg_form",
        SetupUi(step = SetupStep.WG_FORM, server = base, publicHost = "homenas.example.org", password = "Tq7-mZ4k-Rw2x-Lp9a-Hc3v"),
    )

    @Test fun setupWorking() = setup(
        "v06_setup_working", SetupUi(step = SetupStep.WORKING, server = base, busy = true, progress = "Installing wg-easy…", percent = 45),
    )

    @Test fun setupWgDone() = setup(
        "v06_setup_wg_done",
        SetupUi(step = SetupStep.WG_DONE, server = configured, publicHost = "homenas.example.org", password = "Tq7-mZ4k-Rw2x-Lp9a-Hc3v"),
        test = CheckResult(true, "Handshake OK, the NAS answered through the tunnel on mobile data."), tip = true,
    )

    @Test fun setupWgManual() = setup(
        "v06_setup_wg_manual",
        SetupUi(step = SetupStep.WG_MANUAL, server = base, manualReason = "wg-easy was already set up with another password."),
        dark = false,
    )

    @Test fun setupTsForm() = setup(
        "v06_setup_ts_form",
        SetupUi(step = SetupStep.TS_FORM, server = base, authKey = "tskey-auth-kExample-EXAMPLEKEY", advertise = true, subnet = "192.168.1.0/24"),
    )

    @Test fun setupTsDone() = setup(
        "v06_setup_ts_done", SetupUi(step = SetupStep.TS_DONE, server = base),
        ts = TailscaleUi(input = "https://truenas.tail1234.ts.net"), dark = false,
    )

    @Test fun routeChips() = shot("v06_route_chips", dark = true, height = 60) {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RouteChip(Route.LOCAL); RouteChip(Route.TAILSCALE); RouteChip(Route.VPN); RouteChip(Route.REMOTE)
        }
    }
}
