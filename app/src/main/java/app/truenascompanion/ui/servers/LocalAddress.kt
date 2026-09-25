package app.truenascompanion.ui.servers

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Radar
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.truenascompanion.data.model.AuthMethod
import app.truenascompanion.data.model.Route
import app.truenascompanion.data.model.RouteMode
import app.truenascompanion.data.net.CertificateInfo
import app.truenascompanion.data.net.LocalDetector
import app.truenascompanion.ui.components.ElevatedSection
import app.truenascompanion.ui.components.GlowButton
import app.truenascompanion.data.model.Health
import app.truenascompanion.ui.components.InfoBanner
import app.truenascompanion.ui.components.RouteChip
import app.truenascompanion.ui.theme.LocalStatusColors

/** "Home network" part of the server form (stateless; also rendered by the screenshot tests). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LocalAddressSection(
    s: ServerEditState,
    currentRoute: Route?,
    onLocalUrl: (String) -> Unit,
    onDetect: () -> Unit,
    onCheck: () -> Unit,
    onMode: (RouteMode) -> Unit,
    onForgetCert: () -> Unit,
) {
    ElevatedSection {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Home, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Text("Home network", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (currentRoute != null && s.normalizedLocalUrl != null) RouteChip(currentRoute)
            }
            Text(
                "Optional. On your home Wi-Fi the app connects straight to the NAS; elsewhere it uses the server address above. The same sign-in works on both.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = s.localUrl, onValueChange = onLocalUrl,
                label = { Text("Local address") }, placeholder = { Text("https://192.168.1.10") },
                leadingIcon = { Icon(if (s.localIsHttp) Icons.Rounded.LockOpen else Icons.Rounded.Lock, null) },
                singleLine = true,
                supportingText = { Text("IP or hostname. Don't know the port? Use Auto-detect.") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onDetect, enabled = !s.detecting && s.localUrl.isNotBlank(), modifier = Modifier.heightIn(min = 44.dp)) {
                    if (s.detecting) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) else Icon(Icons.Rounded.Radar, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (s.detecting) "Detecting…" else "Auto-detect", maxLines = 1)
                }
                OutlinedButton(
                    onClick = onCheck,
                    enabled = s.normalizedLocalUrl != null && s.normalizedUrl != null && s.localStatus != LocalStatus.Checking,
                    modifier = Modifier.heightIn(min = 44.dp),
                ) { Text("Check", maxLines = 1) }
            }
            LocalStatusLine(s.localStatus)

            AnimatedVisibility(s.localIsHttp) {
                if (s.localBlocked) InfoBanner(
                    "TrueNAS rejects (and revokes) API keys sent over plain http, so this local address won't be used with an API key. " +
                        "Turn on HTTPS in TrueNAS (System › General › GUI), or switch to password sign-in.",
                    health = Health.CRITICAL,
                ) else InfoBanner(
                    "Plain http: your password would cross your home network unencrypted. Turning on HTTPS in TrueNAS " +
                        "(System › General › GUI) is recommended.",
                    health = Health.WARNING,
                )
            }

            if (s.normalizedLocalUrl != null) {
                Text("Which address to use", style = MaterialTheme.typography.labelLarge)
                androidx.compose.material3.SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    RouteMode.entries.forEachIndexed { i, m ->
                        SegmentedButton(
                            selected = s.routeMode == m, onClick = { onMode(m) },
                            shape = androidx.compose.material3.SegmentedButtonDefaults.itemShape(i, RouteMode.entries.size),
                            icon = {},
                            label = { Text(when (m) { RouteMode.AUTO -> "Auto"; RouteMode.LOCAL -> "Local"; RouteMode.REMOTE -> "Remote" }, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) },
                        )
                    }
                }
                Text(
                    when (s.routeMode) {
                        RouteMode.AUTO -> "Local when it answers on Wi-Fi, Ethernet or VPN (checked once per network), otherwise remote."
                        RouteMode.LOCAL -> "Always the local address, even on mobile data."
                        RouteMode.REMOTE -> "Always the server address above."
                    },
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (s.localPinnedCert != null && s.normalizedLocalUrl != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Shield, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Trusted local certificate", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = onForgetCert) { Text("Forget") }
                }
                Text(s.localPinnedCert, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

@Composable
private fun LocalStatusLine(status: LocalStatus?) {
    val colors = LocalStatusColors.current
    when (status) {
        null -> Unit
        LocalStatus.Checking -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
            Text("Checking the local address…", style = MaterialTheme.typography.bodySmall)
        }
        is LocalStatus.Reachable -> StatusText(
            if (status.sameNas == false) Icons.Rounded.ErrorOutline else Icons.Rounded.CheckCircle,
            if (status.sameNas == false) colors.warning else colors.healthy,
            when (status.sameNas) {
                true -> "Reachable. Confirmed it's the same NAS as the server address."
                false -> "Reachable, but it looks like a different TrueNAS than the server address. Check the IP."
                null -> "Reachable."
            },
        )
        is LocalStatus.Failed -> StatusText(Icons.Rounded.ErrorOutline, colors.critical, status.message)
    }
}

@Composable
private fun StatusText(icon: androidx.compose.ui.graphics.vector.ImageVector, color: androidx.compose.ui.graphics.Color, text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(icon, null, tint = color, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodySmall)
    }
}

/** Confirms the auto-detected address before it's used. */
@Composable
fun DetectedAddressDialog(found: LocalDetector.Found, authMethod: AuthMethod, onUse: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(if (found.https) Icons.Rounded.Lock else Icons.Rounded.LockOpen, null) },
        title = { Text("Found your TrueNAS") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f), modifier = Modifier.fillMaxWidth()) {
                    Text(found.url, style = MaterialTheme.typography.titleMedium, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(12.dp))
                }
                if (found.https) {
                    Text("The web UI answers over HTTPS. If it uses a self-signed certificate, you'll be asked to confirm its fingerprint next.")
                } else if (authMethod == AuthMethod.API_KEY) {
                    Text(
                        "Only plain http was found. TrueNAS rejects API keys over http, so the app won't use this address with an API key. " +
                            "Turn on HTTPS in TrueNAS (System › General › GUI) and detect again, or use password sign-in.",
                        color = MaterialTheme.colorScheme.error,
                    )
                } else {
                    Text(
                        "Only plain http was found, so your password would cross your home network unencrypted. " +
                            "Turning on HTTPS in TrueNAS (System › General › GUI) is recommended. You can still use it.",
                    )
                }
            }
        },
        confirmButton = { GlowButton(onClick = onUse) { Text("Use this address", maxLines = 1) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Fingerprint confirmation for a self-signed certificate (server address or local address). */
@Composable
fun CertificateTrustDialog(cert: CertificateInfo, local: Boolean, onTrust: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Shield, null) },
        title = { Text(if (local) "Trust the local address?" else "Trust this server?") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (cert.selfSigned) "This server uses a self-signed certificate (normal for a home NAS). Only trust it if you are sure this is your server."
                    else "This certificate is not trusted by your phone or doesn't match the address. Only trust it if you are sure this is your server."
                )
                Text("Subject", style = MaterialTheme.typography.labelMedium)
                Text(cert.subject, style = MaterialTheme.typography.bodySmall)
                Text("Valid", style = MaterialTheme.typography.labelMedium)
                Text("${cert.validFrom} – ${cert.validUntil}", style = MaterialTheme.typography.bodySmall)
                Text("SHA-256 fingerprint", style = MaterialTheme.typography.labelMedium)
                Text(cert.sha256, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                Text(
                    if (local) "The app will trust exactly this certificate for the local address and warn if it ever changes."
                    else "The app will trust exactly this certificate for this server and warn if it ever changes.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { GlowButton(onClick = onTrust) { Text("Trust") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
