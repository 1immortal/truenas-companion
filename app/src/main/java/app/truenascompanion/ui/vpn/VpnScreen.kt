package app.truenascompanion.ui.vpn

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.CallSplit
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.truenascompanion.data.model.Health
import app.truenascompanion.data.model.Route
import app.truenascompanion.data.model.ServerConfig
import app.truenascompanion.data.model.VpnMode
import app.truenascompanion.data.vpn.TailscaleApp
import app.truenascompanion.data.vpn.TunnelStatus
import app.truenascompanion.data.vpn.WgSummary
import app.truenascompanion.ui.appViewModel
import app.truenascompanion.ui.components.ConfirmDialog
import app.truenascompanion.ui.components.ElevatedSection
import app.truenascompanion.ui.components.GlowButton
import app.truenascompanion.ui.components.InfoBanner
import app.truenascompanion.ui.components.RouteChip
import app.truenascompanion.ui.servers.CertificateTrustDialog
import app.truenascompanion.ui.theme.LocalStatusColors
import com.wireguard.android.backend.Tunnel

/** Callbacks of the VPN screen (kept together so the stateless content can be rendered by screenshot tests). */
class VpnActions(
    val onMode: (VpnMode) -> Unit = {},
    val onScan: () -> Unit = {},
    val onFile: () -> Unit = {},
    val onPaste: () -> Unit = {},
    val onTest: () -> Unit = {},
    val onRemove: () -> Unit = {},
    val onConsent: () -> Unit = {},
    val onSetup: () -> Unit = {},
    val onTsInput: (String) -> Unit = {},
    val onTsCheck: () -> Unit = {},
    val onTsRemove: () -> Unit = {},
    val onOpenTailscale: () -> Unit = {},
    val onDismissTip: () -> Unit = {},
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VpnScreen(serverId: String, onBack: () -> Unit, onSetup: () -> Unit) {
    app.truenascompanion.ui.components.SecureWindowEffect() // 1.7.1 (M-4)
    val vm = appViewModel(key = "vpn-$serverId") { VpnViewModel(it, serverId) }
    val s by vm.state.collectAsStateWithLifecycle()
    val tunnel by vm.tunnel.collectAsStateWithLifecycle()
    val routes by vm.routes.collectAsStateWithLifecycle()
    val tip by vm.tip.collectAsStateWithLifecycle(initialValue = false)
    val ts by vm.tailscale.ui.collectAsStateWithLifecycle()
    val consent by vm.consentRequest.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var scanning by remember { mutableStateOf(false) }
    var pasting by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refresh() }
    val consentLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        vm.onConsentResult(r.resultCode == Activity.RESULT_OK)
    }
    LaunchedEffect(consent) {
        if (consent) {
            vm.consentLaunched()
            val intent = android.net.VpnService.prepare(context)
            if (intent != null) runCatching { consentLauncher.launch(intent) }.onFailure { vm.onConsentResult(false) } else vm.onConsentResult(true)
        }
    }
    val fileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val text = runCatching {
                context.contentResolver.openInputStream(uri)?.use { String(it.readBounded(32_768), Charsets.UTF_8) }
            }.getOrNull()
            if (text != null) vm.offerImport(text) else vm.offerImport("")
        }
    }
    LaunchedEffect(s.message) { s.message?.let { snackbar.showSnackbar(it, withDismissAction = true); vm.clearMessage() } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("VPN & remote access") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.padding(padding)) {
            VpnContent(
                s, tunnel, s.server?.let { routes[it.id] }, tip, ts,
                VpnActions(
                    onMode = vm::setMode,
                    onScan = { scanning = true },
                    onFile = { fileLauncher.launch(arrayOf("*/*")) },
                    onPaste = { pasting = true },
                    onTest = vm::test,
                    onRemove = { confirmRemove = true },
                    onConsent = vm::requestConsent,
                    onSetup = onSetup,
                    onTsInput = vm.tailscale::setInput,
                    onTsCheck = vm.tailscale::check,
                    onTsRemove = vm.tailscale::remove,
                    onOpenTailscale = { runCatching { TailscaleApp.open(context) } },
                    onDismissTip = { vm.dismissTip() },
                ),
                tailscaleInstalled = remember(s.foreignVpn) { TailscaleApp.isInstalled(context) },
            )
        }
    }

    if (scanning) QrScanDialog(onResult = { scanning = false; vm.offerImport(it) }, onDismiss = { scanning = false })
    if (pasting) PasteConfigDialog(onImport = { pasting = false; vm.offerImport(it) }, onDismiss = { pasting = false })
    s.pendingSummary?.let { sum ->
        ImportConfirmDialog(sum, s.server?.lanIp, onConfirm = vm::confirmImport, onDismiss = vm::cancelImport)
    }
    s.importError?.let { err ->
        AlertDialog(
            onDismissRequest = vm::clearImportError,
            icon = { Icon(Icons.Rounded.ErrorOutline, null) },
            title = { Text("Can't use this config") },
            text = { Text(err) },
            confirmButton = { TextButton(onClick = vm::clearImportError) { Text("OK") } },
        )
    }
    if (confirmRemove) ConfirmDialog(
        title = "Remove the WireGuard config?",
        text = "The tunnel config is deleted from this phone. The client stays in wg-easy on your NAS (delete it there if you don't need it anymore).",
        confirmLabel = "Remove", destructive = true,
        onConfirm = { confirmRemove = false; vm.removeWireGuard() }, onDismiss = { confirmRemove = false },
    )
    ts.pendingCert?.let { CertificateTrustDialog(it, local = true, onTrust = vm.tailscale::trustCert, onDismiss = vm.tailscale::dismissCert) }
}

/** Stateless VPN screen body. */
@Composable
fun VpnContent(
    s: VpnUiState,
    tunnel: TunnelStatus,
    route: Route?,
    tip: Boolean,
    ts: TailscaleUi,
    actions: VpnActions,
    tailscaleInstalled: Boolean = false,
) {
    val server = s.server ?: return
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        RouteOrderCard(server, route)
        if (tip) SecurityTipCard(actions.onDismissTip)
        if (!server.wireGuardConfigured && !server.hasTailscale) {
            ElevatedSection {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.AutoFixHigh, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(10.dp))
                    Text("No VPN on your NAS yet?", style = MaterialTheme.typography.titleMedium)
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "The app can install WireGuard (wg-easy) or Tailscale on your TrueNAS for you and set this phone up. Do it once at home, on your Wi-Fi.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                GlowButton(onClick = actions.onSetup) { Icon(Icons.Rounded.AutoFixHigh, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Set up VPN") }
            }
        }
        WireGuardCard(s, tunnel, actions)
        TailscaleCard(server, ts, s.foreignVpn, tailscaleInstalled, actions)
        if (server.wireGuardConfigured || server.hasTailscale) {
            TextButton(onClick = actions.onSetup) { Icon(Icons.Rounded.AutoFixHigh, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Set up VPN on the NAS again") }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun RouteOrderCard(server: ServerConfig, route: Route?) {
    ElevatedSection {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Route, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(10.dp))
            Text("How the app reaches ${server.name}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
            route?.let { RouteChip(it) }
        }
        Spacer(Modifier.height(10.dp))
        Text("Tried in this order (Auto), checked once per network:", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        val wgAlways = server.wireGuardUsable && server.vpnMode == VpnMode.ALWAYS
        if (wgAlways) OrderRow(Icons.Rounded.VpnKey, "WireGuard (always on)", server.lanIp ?: "", true, route == Route.VPN)
        OrderRow(Icons.Rounded.Home, "Local", server.localUrl?.substringAfter("://") ?: "not set", server.localUsable, route == Route.LOCAL)
        OrderRow(Icons.Rounded.Hub, "Tailscale", server.tailscaleUrl?.substringAfter("://") ?: "not set", server.tailscaleUsable, route == Route.TAILSCALE)
        if (!wgAlways) OrderRow(
            Icons.Rounded.VpnKey, "WireGuard",
            when {
                !server.wireGuardConfigured -> "not set"
                server.vpnMode == VpnMode.OFF -> "off"
                else -> "to ${server.lanIp ?: "?"}"
            },
            server.wireGuardUsable, route == Route.VPN,
        )
        OrderRow(Icons.Rounded.Public, "Remote", server.displayHost, true, route == Route.REMOTE)
        if (server.routeMode != app.truenascompanion.data.model.RouteMode.AUTO) {
            Spacer(Modifier.height(6.dp))
            Text("\"Which address to use\" is set to ${server.routeMode.label} in the server settings, so only that one is used.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun OrderRow(icon: ImageVector, label: String, detail: String, enabled: Boolean, active: Boolean) {
    val color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = if (active) MaterialTheme.colorScheme.primary else color, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, color = color, modifier = Modifier.width(96.dp))
        Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (active) Icon(Icons.Rounded.CheckCircle, "In use", tint = LocalStatusColors.current.healthy, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun WireGuardCard(s: VpnUiState, tunnel: TunnelStatus, a: VpnActions) {
    val server = s.server ?: return
    ElevatedSection {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.VpnKey, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(10.dp))
            Text("Built-in WireGuard", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            if (tunnel.serverId == server.id && tunnel.state == Tunnel.State.UP) StatusDot("Up")
        }
        Spacer(Modifier.height(8.dp))
        if (!server.wireGuardConfigured) {
            Text(
                "Reach your NAS through your own WireGuard server when you're away, without a separate VPN app. Import this phone's config from wg-easy (or any WireGuard server):",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            ImportButtons(a)
            return@ElevatedSection
        }
        s.summary?.let { sum ->
            KeyValue("Server", sum.endpoint)
            KeyValue("Tunnel address", sum.addresses.joinToString())
        }
        Spacer(Modifier.height(10.dp))
        Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)) {
            Row(Modifier.padding(12.dp)) {
                Icon(Icons.AutoMirrored.Rounded.CallSplit, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
                Text(
                    "Split tunnel: only YTN's connections to ${server.lanIp ?: "your NAS"} go through it. Other apps and everything else on the phone are not affected" +
                        (if (s.summary?.fullTunnel == true) ", even though the config says AllowedIPs = 0.0.0.0/0." else "."),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Text("Use the tunnel", style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(6.dp))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            VpnMode.entries.forEachIndexed { i, m ->
                SegmentedButton(
                    selected = server.vpnMode == m, onClick = { a.onMode(m) },
                    shape = SegmentedButtonDefaults.itemShape(i, VpnMode.entries.size), icon = {},
                ) { Text(m.label, maxLines = 1) }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            when (server.vpnMode) {
                VpnMode.OFF -> "Off: the app never starts the tunnel."
                VpnMode.AUTO -> "Auto: only when the local address and Tailscale don't reach the NAS (e.g. on mobile data). Down 30 s after you leave the app. Background alert checks bring it up for a few seconds."
                VpnMode.ALWAYS -> "Always on: up whenever the app is open, also at home (your router must loop the connection back; if the test fails at home, use Auto). Down 30 s after you leave the app."
            },
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (server.vpnMode != VpnMode.OFF) {
            Spacer(Modifier.height(4.dp))
            Text(
                "Battery: WireGuard is quiet while nothing is sent. With instant alerts on, the tunnel stays up in the background too, so their keepalive runs through it (a little extra battery).",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TunnelStatusLine(server, tunnel)
        val warnings = buildList {
            s.configProblem?.let { add(it to Health.CRITICAL) }
            if (s.needsConsent && server.vpnMode != VpnMode.OFF) add("Android needs your OK once before the app can start its VPN." to Health.WARNING)
            if (s.foreignVpn && server.vpnMode != VpnMode.OFF) add("Another VPN app is connected, so the built-in tunnel stays off (Android allows one VPN at a time). If that's Tailscale, the Tailscale address is used; otherwise the remote address." to Health.WARNING)
            if (tunnel.failedServerId == server.id && tunnel.lastFailure != null && !(s.foreignVpn || s.needsConsent)) add("Last try: ${tunnel.lastFailure.message}" to Health.WARNING)
        }
        warnings.forEach { (t, h) -> Spacer(Modifier.height(8.dp)); InfoBanner(t, health = h) }
        if (s.needsConsent && server.vpnMode != VpnMode.OFF) {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = a.onConsent) { Text("Allow VPN") }
        }
        Spacer(Modifier.height(12.dp))
        @OptIn(ExperimentalLayoutApi::class)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            GlowButton(onClick = a.onTest, enabled = !s.testing && s.configProblem == null) {
                if (s.testing) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) else Icon(Icons.Rounded.NetworkCheck, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (s.testing) "Testing…" else "Test VPN")
            }
            TextButton(onClick = a.onRemove) { Text("Remove") }
        }
        s.test?.let { Spacer(Modifier.height(10.dp)); ResultLine(it) }
        Spacer(Modifier.height(12.dp))
        Text("Replace the config", style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(6.dp))
        ImportButtons(a)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ImportButtons(a: VpnActions) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = a.onScan) { Icon(Icons.Rounded.QrCodeScanner, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Scan QR", maxLines = 1) }
        OutlinedButton(onClick = a.onFile) { Icon(Icons.Rounded.FileOpen, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(".conf file", maxLines = 1) }
        OutlinedButton(onClick = a.onPaste) { Icon(Icons.Rounded.ContentPaste, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Paste", maxLines = 1) }
    }
}

@Composable
private fun TunnelStatusLine(server: ServerConfig, t: TunnelStatus) {
    Spacer(Modifier.height(10.dp))
    val mine = t.serverId == server.id
    val text = when {
        mine && t.connecting -> "Tunnel: connecting…"
        mine && t.state == Tunnel.State.UP -> buildString {
            append("Tunnel: up")
            if (t.lastHandshakeMs > 0) append(" · handshake ${ago(t.lastHandshakeMs)}")
            if (t.rxBytes + t.txBytes > 0) append(" · ↓${app.truenascompanion.util.Format.bytes(t.rxBytes)} ↑${app.truenascompanion.util.Format.bytes(t.txBytes)}")
        }
        server.vpnMode == VpnMode.OFF -> "Tunnel: off"
        else -> "Tunnel: down (comes up when needed)"
    }
    Text(text, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
}

private fun ago(ms: Long): String {
    val s = ((System.currentTimeMillis() - ms) / 1000).coerceAtLeast(0)
    return when { s < 60 -> "${s}s ago"; s < 3600 -> "${s / 60} min ago"; else -> "${s / 3600} h ago" }
}

@Composable
private fun TailscaleCard(server: ServerConfig, ts: TailscaleUi, vpnConnected: Boolean, installed: Boolean, a: VpnActions) {
    ElevatedSection {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Hub, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(10.dp))
            Text("Tailscale", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            if (vpnConnected) StatusDot("VPN on")
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Already use Tailscale? Enter the NAS's Tailscale address. It's used whenever the Tailscale app is connected on this phone and the address answers (the app doesn't include Tailscale itself).",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
        TailscaleAddressField(ts, a.onTsInput, a.onTsCheck)
        if (ts.saved != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Saved: ${ts.saved.substringAfter("://")}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                TextButton(onClick = a.onTsRemove) { Text("Remove") }
            }
        }
        Spacer(Modifier.height(6.dp))
        OutlinedButton(onClick = a.onOpenTailscale) {
            Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
            Text(if (installed) "Open Tailscale" else "Get Tailscale")
        }
    }
}

/** Address field + check, shared with the setup wizard. */
@Composable
fun TailscaleAddressField(ts: TailscaleUi, onInput: (String) -> Unit, onCheck: () -> Unit) {
    OutlinedTextField(
        value = ts.input, onValueChange = onInput,
        label = { Text("Tailscale address") }, placeholder = { Text("https://100.101.102.103") },
        leadingIcon = { Icon(Icons.Rounded.Hub, null) }, singleLine = true,
        supportingText = { Text("The NAS's 100.x address or MagicDNS name (Tailscale app › Machines).") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(6.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = onCheck, enabled = ts.input.isNotBlank() && !ts.checking) {
            if (ts.checking) { CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)) }
            Text(if (ts.checking) "Checking…" else "Check & save")
        }
    }
    ts.result?.let { Spacer(Modifier.height(8.dp)); ResultLine(it) }
}

@Composable
fun ResultLine(r: CheckResult) {
    val colors = LocalStatusColors.current
    Row(verticalAlignment = Alignment.Top) {
        Icon(if (r.ok) Icons.Rounded.CheckCircle else Icons.Rounded.ErrorOutline, null, tint = if (r.ok) colors.healthy else colors.critical, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(r.text, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun StatusDot(label: String) {
    val c = LocalStatusColors.current.healthy
    Surface(color = c.copy(alpha = 0.15f), shape = MaterialTheme.shapes.small) {
        Text(label, color = c, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
    }
}

@Composable
private fun KeyValue(k: String, v: String) {
    Row(Modifier.padding(vertical = 2.dp)) {
        Text(k, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(110.dp))
        Text(v, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
    }
}

@Composable
fun SecurityTipCard(onDismiss: () -> Unit) {
    ElevatedSection {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Lightbulb, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(10.dp))
            Text("Tip: VPN works", style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Now that you can reach your NAS through the VPN, TrueNAS doesn't have to be open to the internet anymore. " +
                "You could remove its public proxy host or port forward, and keep the dynamic DNS address in the app as a backup for now. " +
                "Nothing is changed automatically.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onDismiss) { Text("Got it") }
    }
}

@Composable
private fun PasteConfigDialog(onImport: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.ContentPaste, null) },
        title = { Text("Paste WireGuard config") },
        text = {
            OutlinedTextField(
                value = text, onValueChange = { text = it },
                placeholder = { Text("[Interface]\nPrivateKey = …") },
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                minLines = 6, maxLines = 12, modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { GlowButton(onClick = { onImport(text) }, enabled = text.isNotBlank()) { Text("Import") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun ImportConfirmDialog(sum: WgSummary, lanIp: String?, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.VpnKey, null) },
        title = { Text("Use this tunnel?") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                KeyValue("Server", sum.endpoint)
                KeyValue("Tunnel address", sum.addresses.joinToString())
                KeyValue("Allowed IPs", sum.allowedIps.joinToString())
                if (sum.dns.isNotEmpty()) KeyValue("DNS", sum.dns.joinToString())
                Spacer(Modifier.height(4.dp))
                Text(
                    if (lanIp != null) "Only this app's connections to $lanIp will use the tunnel; the other Allowed IPs and the DNS servers are ignored. The config (it contains a private key) is stored encrypted on this phone."
                    else "Set the NAS's local IP address (Home network) in the server settings, then the app routes only that address through the tunnel.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = { GlowButton(onClick = onConfirm) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Reads at most [max] bytes (InputStream.readNBytes needs API 33). */
private fun java.io.InputStream.readBounded(max: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(8192)
    while (out.size() < max) {
        val n = read(buf, 0, minOf(buf.size, max - out.size()))
        if (n < 0) break
        out.write(buf, 0, n)
    }
    return out.toByteArray()
}
