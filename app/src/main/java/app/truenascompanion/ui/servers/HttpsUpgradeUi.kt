package app.truenascompanion.ui.servers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.truenascompanion.TrueNasApp
import app.truenascompanion.data.model.Health
import app.truenascompanion.data.model.Route
import app.truenascompanion.data.model.ServerConfig
import app.truenascompanion.data.net.HttpsTrustRequest
import app.truenascompanion.data.net.HttpsUpgradeManager
import app.truenascompanion.ui.components.GlowButton
import app.truenascompanion.ui.theme.LocalStatusColors
import kotlinx.coroutines.launch

/**
 * 1.7.1: runs the one-time HTTPS upgrade of saved http:// addresses and shows the certificate prompt it may need.
 * Placed next to the sign-in prompt host, above the connection overlay, so it can be answered even while the overlay
 * says the server can't be reached (an http:// remote address).
 */
@Composable
fun HttpsUpgradeHost() {
    val container = (LocalContext.current.applicationContext as TrueNasApp).container
    val m = container.httpsUpgrade
    LaunchedEffect(Unit) { container.appScope.launch { runCatching { m.runAutomatic() } } }
    val requests by m.requests.collectAsStateWithLifecycle()
    val first = requests.firstOrNull() ?: return
    HttpsTrustDialog(
        first,
        onTrust = { container.appScope.launch { m.trust(first) } },
        onNotNow = { m.notNow(first) },
    )
}

private fun routeName(r: Route) = when (r) {
    Route.REMOTE -> "server address"
    Route.LOCAL -> "home address"
    Route.TAILSCALE -> "Tailscale address"
    Route.VPN -> "VPN address"
}

/** The one-time "use HTTPS" prompt for a self-signed certificate (stateless; also used by the screenshot tests). */
@Composable
fun HttpsTrustDialog(request: HttpsTrustRequest, onTrust: () -> Unit, onNotNow: () -> Unit) {
    val o = request.outcome
    val cert = o.cert
    AlertDialog(
        onDismissRequest = onNotNow,
        icon = { Icon(Icons.Rounded.Shield, null) },
        title = { Text("Use HTTPS for your ${routeName(o.route)}?") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "To keep your password and sessions safe, the app no longer signs in over unencrypted http:// " +
                        "(${o.from.substringAfter("://")}). ${request.serverName} also answers securely at:",
                )
                Text(o.url.substringAfter("://"), style = MaterialTheme.typography.titleMedium)
                when (o.sameNas) {
                    true -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.CheckCircle, null, tint = LocalStatusColors.current.of(Health.HEALTHY), modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Confirmed: it's the same NAS as your server address.", style = MaterialTheme.typography.bodyMedium)
                    }
                    else -> Text(
                        "It uses its own certificate (normal for a home NAS). Trust it only if this is your NAS.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Text("Certificate", style = MaterialTheme.typography.labelMedium)
                Text(cert.subject, style = MaterialTheme.typography.bodySmall)
                Text("Valid ${cert.validFrom} – ${cert.validUntil}", style = MaterialTheme.typography.bodySmall)
                Text("SHA-256 fingerprint", style = MaterialTheme.typography.labelMedium)
                Text(cert.sha256, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                Text(
                    "The app will accept exactly this certificate at that address and ask again if it ever changes. " +
                        "Not now keeps the ${routeName(o.route)} off" +
                        (if (o.route == Route.REMOTE) "." else "; the server address keeps working."),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { GlowButton(onClick = onTrust) { Text("Trust and use HTTPS") } },
        dismissButton = { TextButton(onClick = onNotNow) { Text("Not now") } },
    )
}

/** Dashboard notice while a saved address is still http:// (and so turned off). */
@Composable
fun HttpsNoticeHost(server: ServerConfig) {
    if (server.insecureAddresses.isEmpty() || server.httpsBannerDismissed) return
    val container = (LocalContext.current.applicationContext as TrueNasApp).container
    val m = container.httpsUpgrade
    val busy by m.busy.collectAsStateWithLifecycle()
    val notes by m.notes.collectAsStateWithLifecycle()
    HttpsNotice(
        server, note = notes[server.id], busy = server.id in busy,
        onSetUp = { container.appScope.launch { m.retry(server.id) } },
        onDismiss = { container.appScope.launch { m.dismissBanner(server.id) } },
    )
}

/** Stateless notice (screenshot tests). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HttpsNotice(server: ServerConfig, note: String?, busy: Boolean, onSetUp: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val text = HttpsUpgradeManager.bannerText(server) ?: return
    val c = LocalStatusColors.current
    val health = if (Route.REMOTE in server.insecureAddresses) Health.CRITICAL else Health.WARNING
    Surface(
        color = c.containerOf(health), shape = MaterialTheme.shapes.medium,
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Icon(Icons.Rounded.LockOpen, null, tint = c.of(health))
                Spacer(Modifier.width(12.dp))
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Unencrypted address turned off", style = MaterialTheme.typography.titleSmall)
                    Text(text, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Tap Set up HTTPS while on your home Wi-Fi: the app finds the NAS's HTTPS address and asks you to trust its certificate once.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    note?.let {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
                            Icon(Icons.Rounded.Info, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(6.dp))
                            Text(it, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = onDismiss) { Text("Dismiss") }
                FilledTonalButton(onClick = onSetUp, enabled = !busy) {
                    if (busy) { CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)) }
                    Text(if (busy) "Checking…" else "Set up HTTPS")
                }
            }
        }
    }
}
