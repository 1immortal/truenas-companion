package app.truenascompanion.ui.vpn

import androidx.core.net.toUri
import android.app.Activity
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PersistableBundle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.truenascompanion.data.model.Health
import app.truenascompanion.data.vpn.TailscaleApp
import app.truenascompanion.data.vpn.VpnSetup
import app.truenascompanion.ui.appViewModel
import app.truenascompanion.ui.components.ConfirmDialog
import app.truenascompanion.ui.components.ElevatedSection
import app.truenascompanion.ui.components.GlowButton
import app.truenascompanion.ui.components.InfoBanner
import app.truenascompanion.ui.servers.CertificateTrustDialog

/** Callbacks of the wizard (stateless content is rendered by screenshot tests). */
class SetupActions(
    val update: ((SetupUi) -> SetupUi) -> Unit = {},
    val chooseWireGuard: () -> Unit = {},
    val chooseTailscale: () -> Unit = {},
    val installWireGuard: () -> Unit = {},
    val useExisting: () -> Unit = {},
    val installTailscale: () -> Unit = {},
    val copy: (String, Boolean) -> Unit = { _, _ -> },
    val openUrl: (String) -> Unit = {},
    val scan: () -> Unit = {},
    val test: () -> Unit = {},
    val openTailscale: () -> Unit = {},
    val tsInput: (String) -> Unit = {},
    val tsCheck: () -> Unit = {},
    val dismissTip: () -> Unit = {},
    val done: () -> Unit = {},
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VpnSetupScreen(serverId: String, onBack: () -> Unit) {
    val vm = appViewModel(key = "vpn-setup-$serverId") { VpnSetupViewModel(it, serverId) }
    val vpn = appViewModel(key = "vpn-setup-tunnel-$serverId") { VpnViewModel(it, serverId) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val vs by vpn.state.collectAsStateWithLifecycle()
    val ts by vm.tailscale.ui.collectAsStateWithLifecycle()
    val tip by vpn.tip.collectAsStateWithLifecycle(initialValue = false)
    val consent by vpn.consentRequest.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var scanning by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<String?>(null) }
    val snackbar = remember { SnackbarHostState() }

    BackHandler { if (!vm.back()) onBack() }
    val consentLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r -> vpn.onConsentResult(r.resultCode == Activity.RESULT_OK) }
    LaunchedEffect(consent) {
        if (consent) {
            vpn.consentLaunched()
            val intent = android.net.VpnService.prepare(context)
            if (intent != null) runCatching { consentLauncher.launch(intent) }.onFailure { vpn.onConsentResult(false) } else vpn.onConsentResult(true)
        }
    }
    LaunchedEffect(vs.message) { vs.message?.let { snackbar.showSnackbar(it, withDismissAction = true); vpn.clearMessage() } }
    // A config imported by scanning (manual fallback) finishes the WireGuard path.
    LaunchedEffect(vs.server?.wireGuardConfigured, ui.step) {
        if (ui.step == SetupStep.WG_MANUAL && vs.server?.wireGuardConfigured == true) vm.update { it.copy(step = SetupStep.WG_DONE) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Set up VPN") },
                navigationIcon = { IconButton(onClick = { if (!vm.back()) onBack() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.padding(padding)) {
            VpnSetupContent(
                ui, ts, vs.testing, vs.test, tip,
                SetupActions(
                    update = vm::update,
                    chooseWireGuard = vm::chooseWireGuard,
                    chooseTailscale = vm::chooseTailscale,
                    installWireGuard = { confirm = "wg" },
                    useExisting = vm::useExisting,
                    installTailscale = { confirm = "ts" },
                    copy = { text, sensitive -> copy(context, text, sensitive) },
                    openUrl = { url -> runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) } },
                    scan = { scanning = true },
                    test = vpn::test,
                    openTailscale = { runCatching { TailscaleApp.open(context) } },
                    tsInput = vm.tailscale::setInput,
                    tsCheck = vm.tailscale::check,
                    dismissTip = { vpn.dismissTip() },
                    done = onBack,
                ),
                tailscaleInstalled = remember(ui.step) { TailscaleApp.isInstalled(context) },
            )
        }
    }

    if (scanning) QrScanDialog(onResult = { scanning = false; vpn.offerImport(it) }, onDismiss = { scanning = false })
    vs.pendingSummary?.let { ImportConfirmDialog(it, vs.server?.lanIp, onConfirm = vpn::confirmImport, onDismiss = vpn::cancelImport) }
    vs.importError?.let { err ->
        AlertDialog(
            onDismissRequest = vpn::clearImportError, title = { Text("Can't use this config") }, text = { Text(err) },
            confirmButton = { TextButton(onClick = vpn::clearImportError) { Text("OK") } },
        )
    }
    ts.pendingCert?.let { CertificateTrustDialog(it, local = true, onTrust = vm.tailscale::trustCert, onDismiss = vm.tailscale::dismissCert) }
    when (confirm) {
        "wg" -> ConfirmDialog(
            title = "Install wg-easy on ${ui.server?.name ?: "the NAS"}?",
            text = "Installs the wg-easy app from the TrueNAS catalog (stable train) with host networking: WireGuard on UDP port ${VpnSetup.WG_LISTEN_PORT}, " +
                "its web UI on http://${ui.lanIp}:${VpnSetup.WG_WEB_PORT} (home network only). Then the app creates the admin account and a VPN client for this phone.",
            confirmLabel = "Install", requireAuth = true, icon = Icons.Rounded.VpnKey,
            onConfirm = { confirm = null; vm.installWireGuard() }, onDismiss = { confirm = null },
        )
        "ts" -> ConfirmDialog(
            title = "Install Tailscale on ${ui.server?.name ?: "the NAS"}?",
            text = "Installs the Tailscale app from the TrueNAS catalog (community train) with host networking, named \"${ui.hostname.trim()}\"" +
                (if (ui.advertise && ui.subnet != null) ", sharing your home network ${ui.subnet}" else "") + ". The auth key is stored in the app's settings on the NAS, not on this phone.",
            confirmLabel = "Install", requireAuth = true, icon = Icons.Rounded.Hub,
            onConfirm = { confirm = null; vm.installTailscale() }, onDismiss = { confirm = null },
        )
    }
}

private fun copy(context: Context, text: String, sensitive: Boolean) {
    val cm = context.getSystemService(ClipboardManager::class.java) ?: return
    val clip = ClipData.newPlainText("TrueNAS Companion", text)
    if (sensitive) {
        clip.description.extras = PersistableBundle().apply {
            putBoolean(if (Build.VERSION.SDK_INT >= 33) ClipDescription.EXTRA_IS_SENSITIVE else "android.content.extra.IS_SENSITIVE", true)
        }
    }
    cm.setPrimaryClip(clip)
}

/** Stateless wizard body. */
@Composable
fun VpnSetupContent(
    ui: SetupUi,
    ts: TailscaleUi,
    testing: Boolean,
    test: CheckResult?,
    tip: Boolean,
    a: SetupActions,
    tailscaleInstalled: Boolean = false,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        ui.error?.let { InfoBanner(it, health = Health.CRITICAL) }
        when (ui.step) {
            SetupStep.CHOOSE -> Choose(ui, a)
            SetupStep.WG_FORM -> WgForm(ui, a)
            SetupStep.WG_EXISTING -> WgExisting(ui, a)
            SetupStep.WORKING -> Working(ui)
            SetupStep.WG_DONE -> WgDone(ui, testing, test, tip, a)
            SetupStep.WG_MANUAL -> WgManual(ui, a)
            SetupStep.TS_FORM -> TsForm(ui, a)
            SetupStep.TS_DONE -> TsDone(ui, ts, tailscaleInstalled, a)
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Choose(ui: SetupUi, a: SetupActions) {
    Text("Reach your NAS safely from anywhere, without opening TrueNAS itself to the internet. Do this once, at home on your Wi-Fi.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    ChoiceCard(
        Icons.Rounded.VpnKey, "WireGuard", "Recommended",
        "Built into this app: no extra VPN app on the phone. The app installs wg-easy on your NAS and sets this phone up. " +
            "You forward one UDP port on your router.",
        ui.busy, a.chooseWireGuard,
    )
    ChoiceCard(
        Icons.Rounded.Hub, "Tailscale", null,
        "No router changes. Needs a free Tailscale account and the Tailscale app on this phone (the app opens it for you).",
        ui.busy, a.chooseTailscale,
    )
    if (ui.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
}

@Composable
private fun ChoiceCard(icon: ImageVector, title: String, badge: String?, text: String, busy: Boolean, onClick: () -> Unit) {
    ElevatedSection(onClick = if (busy) null else onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(10.dp))
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            badge?.let {
                Surface(color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f), shape = MaterialTheme.shapes.small) {
                    Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun WgForm(ui: SetupUi, a: SetupActions) {
    Header(Icons.Rounded.VpnKey, "WireGuard with wg-easy")
    OutlinedTextField(
        value = ui.publicHost, onValueChange = { v -> a.update { it.copy(publicHost = v.trim().removePrefix("https://").removePrefix("http://").substringBefore('/')) } },
        label = { Text("Public address of your home") }, placeholder = { Text("myhome.example.org") }, singleLine = true,
        leadingIcon = { Icon(Icons.Rounded.Public, null) },
        supportingText = { Text("Where the phone finds your home from outside: your dynamic DNS name (no https://).") },
        isError = ui.publicHost.isNotBlank() && !VpnSetup.validPublicHost(ui.publicHost.trim()),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = ui.port, onValueChange = { v -> a.update { it.copy(port = v.filter(Char::isDigit).take(5)) } },
        label = { Text("UDP port on your router") }, singleLine = true,
        leadingIcon = { Icon(Icons.Rounded.SettingsEthernet, null) },
        supportingText = { Text("51820 unless your router needs another one. The NAS listens on ${VpnSetup.WG_LISTEN_PORT}.") },
        isError = ui.portNumber == null,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
        modifier = Modifier.fillMaxWidth(),
    )
    PasswordCard(ui.password, a)
    ElevatedSection {
        Text("What happens", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(6.dp))
        Steps(
            "Installs wg-easy from the TrueNAS catalog (takes a minute or two).",
            "You create the wg-easy admin account in its web page with the password above, and add a client for this phone.",
            "You scan that client's QR code here, and the app imports it.",
            "Shows you the one router setting you need to add.",
        )
    }
    GlowButton(onClick = a.installWireGuard, enabled = ui.wgFormValid, modifier = Modifier.fillMaxWidth()) { Text("Install wg-easy") }
}

@Composable
private fun PasswordCard(password: String, a: SetupActions) {
    if (password.isEmpty()) return
    ElevatedSection {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Password, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(10.dp))
            Text("wg-easy admin password", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            IconButton(onClick = { a.copy(password, true) }) { Icon(Icons.Rounded.ContentCopy, "Copy password") }
        }
        Text("${VpnSetup.WG_ADMIN} / $password", style = MaterialTheme.typography.titleMedium, fontFamily = FontFamily.Monospace)
        Spacer(Modifier.height(6.dp))
        Text(
            "Generated for you and shown only here: save it in your password manager. The app doesn't store it anywhere. You need it to add more devices in wg-easy's web page.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun WgExisting(ui: SetupUi, a: SetupActions) {
    Header(Icons.Rounded.VpnKey, "wg-easy is already installed")
    Text("Sign in with your wg-easy account and the app creates a client for this phone. The login is only used for this and not saved.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    OutlinedTextField(
        value = ui.existingUser, onValueChange = { v -> a.update { it.copy(existingUser = v) } },
        label = { Text("wg-easy username") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = ui.existingPassword, onValueChange = { v -> a.update { it.copy(existingPassword = v) } },
        label = { Text("wg-easy password") }, singleLine = true, visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth(),
    )
    PasswordCard(ui.password, a)
    GlowButton(onClick = a.useExisting, enabled = ui.existingUser.isNotBlank() && ui.existingPassword.isNotEmpty(), modifier = Modifier.fillMaxWidth()) { Text("Create this phone's client") }
    Text("Two-factor sign-in on in wg-easy? Then use the web page instead:", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    ManualButtons(ui, a)
}

@Composable
private fun Working(ui: SetupUi) {
    ElevatedSection {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp)
            Spacer(Modifier.width(14.dp))
            Text(ui.progress, style = MaterialTheme.typography.bodyLarge)
        }
        Spacer(Modifier.height(12.dp))
        ui.percent?.let { LinearProgressIndicator(progress = { it / 100f }, modifier = Modifier.fillMaxWidth()) } ?: LinearProgressIndicator(Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        Text("Keep the app open. Pulling the app image can take a few minutes on a slow connection.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun WgDone(ui: SetupUi, testing: Boolean, test: CheckResult?, tip: Boolean, a: SetupActions) {
    Header(Icons.Rounded.CheckCircle, "This phone's VPN is ready")
    Text("The tunnel is saved in the app (encrypted) and set to Auto: it's used only when your home network can't be reached directly.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    PortForwardCard(ui)
    PasswordCard(ui.password, a)
    ElevatedSection {
        Text("Test it", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(6.dp))
        Text("After adding the port forward: turn off Wi-Fi so the phone uses mobile data, then tap Test VPN. It checks the WireGuard handshake and that your NAS answers through the tunnel.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(10.dp))
        GlowButton(onClick = a.test, enabled = !testing) {
            if (testing) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) else Icon(Icons.Rounded.NetworkCheck, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp)); Text(if (testing) "Testing…" else "Test VPN")
        }
        test?.let { Spacer(Modifier.height(10.dp)); ResultLine(it) }
    }
    if (tip) SecurityTipCard(a.dismissTip)
    OutlinedButton(onClick = a.done, modifier = Modifier.fillMaxWidth()) { Text("Done") }
}

@Composable
private fun PortForwardCard(ui: SetupUi) {
    ElevatedSection {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Router, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(10.dp))
            Text("One step the app can't do for you", style = MaterialTheme.typography.titleSmall)
        }
        Spacer(Modifier.height(8.dp))
        Text("In your router's settings (Port forwarding / Virtual server), add:", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(8.dp))
        Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text("Protocol: UDP", fontFamily = FontFamily.Monospace)
                Text("External port: ${ui.portNumber ?: VpnSetup.WG_LISTEN_PORT}", fontFamily = FontFamily.Monospace)
                Text("To: ${ui.lanIp ?: "your NAS"}, port ${VpnSetup.WG_LISTEN_PORT}", fontFamily = FontFamily.Monospace)
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Nginx Proxy Manager can't do this: it forwards web (TCP) traffic, and WireGuard uses UDP. It has to be a router port forward.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun WgManual(ui: SetupUi, a: SetupActions) {
    Header(Icons.Rounded.QrCode2, "Finish in wg-easy's web page")
    ui.manualReason?.let { InfoBanner(it, health = Health.WARNING) }
    ElevatedSection {
        Steps(
            "Open wg-easy (home Wi-Fi only): ${ui.wgWebUrl ?: "http://<NAS IP>:${VpnSetup.WG_WEB_PORT}"}",
            if (ui.password.isNotEmpty()) "Sign in, or create the admin account if it asks, with the password below." else "Sign in with your wg-easy account.",
            "Tap \"+ New\", name it (e.g. \"Phone\"), then tap the QR code icon of the new client.",
            "Tap \"Scan QR code\" here and point the camera at it.",
        )
        Spacer(Modifier.height(10.dp))
        ManualButtons(ui, a)
    }
    PasswordCard(ui.password, a)
    PortForwardCard(ui)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ManualButtons(ui: SetupUi, a: SetupActions) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ui.wgWebUrl?.let { url ->
            OutlinedButton(onClick = { a.openUrl(url) }) { Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Open wg-easy") }
        }
        GlowButton(onClick = a.scan) { Icon(Icons.Rounded.QrCodeScanner, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Scan QR code") }
    }
}

@Composable
private fun TsForm(ui: SetupUi, a: SetupActions) {
    Header(Icons.Rounded.Hub, "Tailscale on your NAS")
    ElevatedSection {
        Text("1. Get an auth key", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(6.dp))
        Text("In the Tailscale admin console: Settings › Keys › Generate auth key. A one-time key is fine: it only adds the NAS to your tailnet once.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = { a.openUrl(VpnSetup.TS_KEYS_URL) }) { Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Open the admin console") }
    }
    OutlinedTextField(
        value = ui.authKey, onValueChange = { v -> a.update { it.copy(authKey = v.trim()) } },
        label = { Text("Auth key") }, placeholder = { Text("tskey-auth-…") }, singleLine = true,
        visualTransformation = PasswordVisualTransformation(), leadingIcon = { Icon(Icons.Rounded.Key, null) },
        isError = ui.authKey.isNotBlank() && !ui.authKey.startsWith("tskey-"),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false), modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = ui.hostname, onValueChange = { v -> a.update { it.copy(hostname = v.trim()) } },
        label = { Text("Name in your tailnet") }, singleLine = true, leadingIcon = { Icon(Icons.Rounded.Dns, null) },
        supportingText = { Text("Also its MagicDNS name, e.g. https://${ui.hostname.ifBlank { "truenas" }}") },
        isError = !VpnSetup.validHostname(ui.hostname.trim()), modifier = Modifier.fillMaxWidth(),
    )
    ElevatedSection {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Also share my home network", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Advertises ${ui.subnet ?: "your LAN"} so your Tailscale devices reach other things at home too (approve it in the admin console › Machines). " +
                        "Not needed for this app: it uses the NAS's own Tailscale address.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(8.dp))
            Switch(checked = ui.advertise, onCheckedChange = { v -> a.update { it.copy(advertise = v) } }, enabled = ui.subnet != null)
        }
    }
    GlowButton(onClick = a.installTailscale, enabled = ui.tsFormValid, modifier = Modifier.fillMaxWidth()) { Text("Install Tailscale") }
}

@Composable
private fun TsDone(ui: SetupUi, ts: TailscaleUi, installed: Boolean, a: SetupActions) {
    Header(Icons.Rounded.CheckCircle, "Tailscale runs on your NAS")
    ElevatedSection {
        Text("2. Tailscale on this phone", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(6.dp))
        Text("Install the Tailscale app, sign in with the same account and connect. TrueNAS Companion uses it whenever it's connected.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        GlowButton(onClick = a.openTailscale) { Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(if (installed) "Open Tailscale" else "Get Tailscale") }
        if (ui.advertise) {
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = { a.openUrl(VpnSetup.TS_MACHINES_URL) }) { Text("Approve the home network route") }
        }
    }
    ElevatedSection {
        Text("3. The NAS's Tailscale address", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(6.dp))
        Text("The TrueNAS app doesn't show it, so it's prefilled with the MagicDNS name. You can also use the 100.x address from the Tailscale app's machine list.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(10.dp))
        TailscaleAddressField(ts, a.tsInput, a.tsCheck)
    }
    OutlinedButton(onClick = a.done, modifier = Modifier.fillMaxWidth()) { Text("Done") }
}

@Composable
private fun Header(icon: ImageVector, title: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(10.dp))
        Text(title, style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
private fun Steps(vararg steps: String) {
    steps.forEachIndexed { i, s ->
        Row(Modifier.padding(vertical = 3.dp)) {
            Text("${i + 1}.", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(22.dp))
            Text(s, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
