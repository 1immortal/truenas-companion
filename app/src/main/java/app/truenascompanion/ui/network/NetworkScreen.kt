package app.truenascompanion.ui.network

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.CallMerge
import androidx.compose.material.icons.rounded.Cable
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.Lan
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.SettingsEthernet
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.truenascompanion.data.model.Health
import app.truenascompanion.data.model.InterfaceRate
import app.truenascompanion.data.model.IpmiLan
import app.truenascompanion.data.model.LinkState
import app.truenascompanion.data.model.NetInterface
import app.truenascompanion.data.model.NetInterfaceType
import app.truenascompanion.data.model.NetworkGlobal
import app.truenascompanion.data.model.NetworkOverview
import app.truenascompanion.data.model.PendingNetworkChanges
import app.truenascompanion.data.model.StaticRoute
import app.truenascompanion.ui.appViewModel
import app.truenascompanion.ui.components.ElevatedSection
import app.truenascompanion.ui.components.IconBadge
import app.truenascompanion.ui.components.ScreenScaffold
import app.truenascompanion.ui.components.SectionTitle
import app.truenascompanion.ui.components.StatusChip
import app.truenascompanion.ui.components.Tag
import app.truenascompanion.ui.components.UiState
import app.truenascompanion.ui.theme.LocalStatusColors
import app.truenascompanion.util.Format
import kotlinx.coroutines.launch

/*
 * 1.9.0: System › Network, VIEW ONLY. Nothing on these pages changes the NAS: there are no edit buttons, switches or
 * text fields, and the data comes from read-only API methods ([app.truenascompanion.data.api.NetworkApi]). Values can
 * be copied with a long press.
 */

const val NETWORK_WARNING_TITLE = "View only"
const val NETWORK_WARNING_TEXT = "Changing network settings from your phone is dangerous: one wrong value can cut the NAS " +
    "off from your network and from this app. Make changes with direct access to the server (its web UI on your local " +
    "network, or its console)."
const val NETWORK_WARNING_TAG = "network_view_only_warning"

/** Long press on a value copies it; provided by the screen (clipboard + "Copied" snackbar). */
val LocalCopyValue = staticCompositionLocalOf<(label: String, value: String) -> Unit> { { _, _ -> } }

@Composable
fun NetworkScreen(onBack: () -> Unit, onReports: () -> Unit) {
    val vm = appViewModel { NetworkViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    val ipmi by vm.ipmi.collectAsStateWithLifecycle()
    val rates by vm.rates.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }
    var openName by rememberSaveable { mutableStateOf<String?>(null) }
    @Suppress("DEPRECATION") val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val copy: (String, String) -> Unit = { label, value ->
        clipboard.setText(AnnotatedString(value))
        scope.launch { snackbar.currentSnackbarData?.dismiss(); snackbar.showSnackbar("Copied $label") }
    }
    val open = openName?.let { n -> (state as? UiState.Success)?.data?.interfaces?.firstOrNull { it.name == n } }
    CompositionLocalProvider(LocalCopyValue provides copy) {
        if (open != null) {
            BackHandler { openName = null }
            InterfaceDetailContent(open, rates[open.name], onBack = { openName = null }, onReports = onReports, snackbar = snackbar)
        } else {
            NetworkContent(
                state, ipmi, rates, onRetry = { vm.refresh() }, onBack = onBack, onOpenInterface = { openName = it.name },
                onReports = onReports, refreshing = refreshing, onRefresh = { vm.refresh() }, snackbar = snackbar,
            )
        }
    }
}

/** The always-visible, non-dismissible warning at the top of every Network page. TalkBack reads it first. */
@Composable
fun NetworkViewOnlyWarning(modifier: Modifier = Modifier) {
    val c = LocalStatusColors.current
    Surface(
        color = c.warningContainer, shape = MaterialTheme.shapes.medium, border = BorderStroke(1.5.dp, c.warning),
        modifier = modifier.fillMaxWidth().testTag(NETWORK_WARNING_TAG)
            .semantics(mergeDescendants = true) {
                contentDescription = "Warning. $NETWORK_WARNING_TITLE. $NETWORK_WARNING_TEXT"
                traversalIndex = -1f
            },
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
            Icon(Icons.Rounded.Warning, null, tint = c.warning, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(12.dp))
            Column {
                Text(NETWORK_WARNING_TITLE, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.height(2.dp))
                Text(NETWORK_WARNING_TEXT, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

/** What the pending-changes banner says, or null when there's nothing to warn about (unit tested). */
fun pendingWarning(p: PendingNetworkChanges): Pair<String, String>? = when {
    p.waitingForCheckin -> "Network changes are being tested" to
        "TrueNAS is trying out new network settings and will undo them automatically in about ${p.checkinSecondsLeft} s " +
        "unless someone confirms them. Confirm or roll them back in the TrueNAS web UI or on the console, not from this phone."
    p.hasPending == true -> "Unfinished network changes" to
        "Someone changed network settings on this NAS but hasn't applied or discarded them yet. Finish them (Test " +
        "Changes, then Save) or roll them back in the TrueNAS web UI or on the console, on your local network."
    else -> null
}

@Composable
fun PendingChangesBanner(p: PendingNetworkChanges, modifier: Modifier = Modifier) {
    val (title, text) = pendingWarning(p) ?: return
    val c = LocalStatusColors.current
    Surface(
        color = c.criticalContainer, shape = MaterialTheme.shapes.medium, border = BorderStroke(1.5.dp, c.critical),
        modifier = modifier.fillMaxWidth().testTag("network_pending").semantics(mergeDescendants = true) { },
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
            Icon(Icons.Rounded.Error, null, tint = c.critical, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(12.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.height(2.dp))
                Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

/** Stateless Network page (also rendered by tests and previews with example data). */
@Composable
fun NetworkContent(
    state: UiState<NetworkOverview>,
    ipmi: List<IpmiLan>,
    rates: Map<String, InterfaceRate>,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    onOpenInterface: (NetInterface) -> Unit,
    onReports: () -> Unit,
    refreshing: Boolean = false,
    onRefresh: (() -> Unit)? = null,
    snackbar: SnackbarHostState = remember { SnackbarHostState() },
) {
    ScreenScaffold(
        title = "Network", state = state, onRetry = onRetry, onBack = onBack, snackbar = snackbar,
        refreshing = refreshing, onRefresh = onRefresh, skeletonHeight = 80.dp,
        header = { NetworkViewOnlyWarning(Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 8.dp)) },
    ) { data ->
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize().testTag("network_list"),
        ) {
            if (data.pending.needsAttention) item(key = "pending") { PendingChangesBanner(data.pending) }
            // Interfaces first: what people look for most (addresses, link, speed). Tap one for its details.
            item(key = "if_title") { SectionTitle("Interfaces") }
            if (data.interfaces.isEmpty()) item(key = "if_none") { MutedText("The NAS reported no network interfaces.") }
            items(data.interfaces, key = { "if_" + it.name }) { InterfaceCard(it, rates[it.name]) { onOpenInterface(it) } }
            item(key = "dns") { GatewaysDnsSection(data.global, data.resolvers) }
            item(key = "general") { GeneralSection(data.global) }
            item(key = "other") { OtherSection(data.global) }
            item(key = "routes") { RoutesSection(data.staticRoutes) }
            if (ipmi.isNotEmpty()) item(key = "ipmi") { IpmiSection(ipmi) }
            if (data.pending.unknown) item(key = "pending_unknown") {
                MutedText("This account can't check for unfinished network changes (TrueNAS needs network write permission just to ask). If in doubt, look in the TrueNAS web UI.")
            }
            item(key = "reports") { ReportsLink(onReports) }
        }
    }
}

@Composable
private fun MutedText(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier.padding(horizontal = 4.dp))
}

@Composable
private fun ReportsLink(onReports: () -> Unit) {
    OutlinedButton(onClick = onReports, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Icon(Icons.Rounded.Insights, null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text("Traffic charts in Reports")
    }
}

/**
 * A label and a read-only value. Long press copies [copy] (the value by default); TalkBack offers it as the
 * "Copy" long-press action. There's no tap action, so nothing looks editable.
 */
@Composable
fun ValueRow(label: String, value: String, modifier: Modifier = Modifier, copy: String? = value.takeIf { it.isNotBlank() && it != NOT_SET }) {
    val onCopy = LocalCopyValue.current
    val m = if (copy == null) modifier else modifier
        .pointerInput(copy) { detectTapGestures(onLongPress = { onCopy(label, copy) }) }
        .semantics { onLongClick(label = "Copy") { onCopy(label, copy); true } }
    // One TalkBack item per row: "Hostname, truenas.home.example.com", with "Copy" as its long-press action.
    Column(m.semantics(mergeDescendants = true) { }.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value.ifBlank { NOT_SET }, style = MaterialTheme.typography.bodyLarge, color = if (value.isBlank() || value == NOT_SET) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
    }
}

const val NOT_SET = "Not set"

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    ElevatedSection(modifier = Modifier.fillMaxWidth()) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        Spacer(Modifier.height(4.dp))
        content()
    }
}

/** "Configured" vs "in use": shows what the NAS uses now when it differs (e.g. a gateway from DHCP). */
fun gatewayText(configured: String, current: String, automatic: String = "from DHCP"): String = when {
    configured.isBlank() && current.isBlank() -> NOT_SET
    configured.isBlank() -> "$current ($automatic)"
    current.isBlank() || current == configured -> configured
    else -> "$configured (in use: $current)"
}

fun announcementText(g: NetworkGlobal): String {
    fun on(b: Boolean?) = when (b) { true -> "on"; false -> "off"; null -> "?" }
    return "mDNS ${on(g.mdns)} · WS-Discovery ${on(g.wsd)} · NetBIOS ${on(g.netbios)}"
}

@Composable
private fun GeneralSection(g: NetworkGlobal) = Section("General") {
    ValueRow("Hostname", g.fqdn)
    g.virtualHostname?.let { ValueRow("Virtual hostname", it) }
    ValueRow("Additional domains", g.additionalDomains.joinToString("\n").ifBlank { "None" }, copy = g.additionalDomains.joinToString("\n").ifBlank { null })
}

@Composable
private fun GatewaysDnsSection(g: NetworkGlobal, resolvers: List<String>?) = Section("Gateways & DNS") {
    ValueRow("IPv4 default gateway", gatewayText(g.ipv4Gateway, g.currentIpv4Gateway), copy = g.currentIpv4Gateway.ifBlank { g.ipv4Gateway }.ifBlank { null })
    ValueRow("IPv6 default gateway", gatewayText(g.ipv6Gateway, g.currentIpv6Gateway, automatic = "automatic"), copy = g.currentIpv6Gateway.ifBlank { g.ipv6Gateway }.ifBlank { null })
    val configured = g.nameservers.mapIndexed { i, s -> "${i + 1}. $s" }.joinToString("\n")
    ValueRow("Name servers", configured.ifBlank { "From DHCP" }, copy = g.nameservers.joinToString("\n").ifBlank { null })
    val inUse = (resolvers ?: g.currentNameservers)
    if (inUse.isNotEmpty() && inUse != g.nameservers) ValueRow("Name servers in use", inUse.joinToString("\n"))
}

@Composable
private fun OtherSection(g: NetworkGlobal) = Section("Other settings") {
    ValueRow("HTTP proxy", g.httpProxy.ifBlank { "None" }, copy = g.httpProxy.ifBlank { null })
    ValueRow("Host name entries", g.hosts.joinToString("\n").ifBlank { "None" }, copy = g.hosts.joinToString("\n").ifBlank { null })
    ValueRow("Service announcement", announcementText(g), copy = null)
}

fun typeIcon(t: NetInterfaceType): ImageVector = when (t) {
    NetInterfaceType.PHYSICAL -> Icons.Rounded.SettingsEthernet
    NetInterfaceType.LINK_AGGREGATION -> Icons.AutoMirrored.Rounded.CallMerge
    NetInterfaceType.BRIDGE -> Icons.Rounded.Lan
    NetInterfaceType.VLAN -> Icons.Rounded.Layers
    NetInterfaceType.UNKNOWN -> Icons.Rounded.Cable
}

fun linkChip(i: NetInterface): Pair<Health, String> = when {
    i.notPresent -> Health.UNKNOWN to "Not applied"
    i.link == LinkState.UP -> Health.HEALTHY to "Up"
    i.link == LinkState.DOWN -> Health.WARNING to "Down"
    else -> Health.UNKNOWN to "Unknown"
}

fun trafficText(r: InterfaceRate?): String? = r?.takeIf { it.linkUp }?.let { "↓ ${Format.rate(it.rxBytesPerSec)}  ↑ ${Format.rate(it.txBytesPerSec)}" }

@Composable
fun InterfaceCard(i: NetInterface, rate: InterfaceRate?, onOpen: () -> Unit) {
    ElevatedSection(onClick = onOpen, contentPadding = 14.dp, modifier = Modifier.fillMaxWidth().testTag("network_if_${i.name}")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(typeIcon(i.type), size = 40.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(i.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (i.description.isNotBlank()) Text(i.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            val (h, label) = linkChip(i)
            StatusChip(h, label)
        }
        Spacer(Modifier.height(8.dp))
        val addrs = i.currentAddresses.ifEmpty { i.configuredAddresses }
        Text(
            if (addrs.isEmpty()) "No IP address" else addrs.take(2).joinToString("\n") + if (addrs.size > 2) "\n+${addrs.size - 2} more" else "",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Tag(i.type.label)
            i.speed?.let { Tag(it) }
            if (i.dhcp) Tag("DHCP")
            i.vlanTag?.let { Tag("Tag $it") }
            trafficText(rate)?.let { Tag(it) }
        }
    }
}

@Composable
private fun RoutesSection(routes: List<StaticRoute>?) = Section("Static routes") {
    when {
        routes == null -> MutedText("Couldn't load static routes.")
        routes.isEmpty() -> MutedText("No static routes. The NAS uses its default gateway for everything else.", Modifier.padding(vertical = 4.dp))
        else -> routes.forEach { r ->
            ValueRow(r.description.ifBlank { "Route" }, "${r.destination} via ${r.gateway}")
        }
    }
}

@Composable
private fun IpmiSection(list: List<IpmiLan>) = Section("IPMI (remote management)") {
    list.forEach { l ->
        val prefix = if (list.size > 1) "Channel ${l.channel} · " else ""
        ValueRow("${prefix}Address", listOf(l.ipAddress, l.subnetMask).filter { it.isNotBlank() }.joinToString(" / ").ifBlank { NOT_SET }, copy = l.ipAddress.ifBlank { null })
        ValueRow("${prefix}Address source", l.ipSource.ifBlank { NOT_SET }, copy = null)
        ValueRow("${prefix}Gateway", l.gateway.ifBlank { NOT_SET })
        ValueRow("${prefix}MAC address", l.mac.ifBlank { NOT_SET })
        l.vlanId?.let { ValueRow("${prefix}VLAN", it.toString()) }
    }
}

// --- Interface detail ---

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun InterfaceDetailContent(
    i: NetInterface,
    rate: InterfaceRate?,
    onBack: () -> Unit,
    onReports: () -> Unit,
    snackbar: SnackbarHostState = remember { SnackbarHostState() },
) {
    app.truenascompanion.ui.components.Scaffold(
        topBar = {
            app.truenascompanion.ui.components.TopAppBar(
                title = { Text(i.name) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            NetworkViewOnlyWarning(Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 8.dp))
            LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f).fillMaxSize().testTag("network_detail"),
            ) {
                item(key = "status") {
                    Section("Status") {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val (h, label) = linkChip(i)
                            StatusChip(h, "Link: $label")
                            Spacer(Modifier.width(8.dp))
                            Tag(i.type.label)
                        }
                        Spacer(Modifier.height(4.dp))
                        if (i.description.isNotBlank()) ValueRow("Description", i.description)
                        ValueRow("Speed", i.speed ?: rate?.speedMbps?.let { NetworkSpeed.fromMbps(it) } ?: "Unknown", copy = null)
                        trafficText(rate)?.let { ValueRow("Traffic now", it, copy = null) }
                        ValueRow("MTU", mtuText(i), copy = i.mtu?.toString())
                        if (i.mac.isNotBlank()) ValueRow("MAC address", i.mac)
                    }
                }
                item(key = "addresses") {
                    Section("Addresses") {
                        ValueRow("In use now", i.currentAddresses.joinToString("\n").ifBlank { "None" }, copy = i.currentAddresses.joinToString("\n").ifBlank { null })
                        ValueRow("IPv4 DHCP", if (i.dhcp) "On" else "Off", copy = null)
                        ValueRow("IPv6 autoconfiguration", if (i.ipv6Auto) "On" else "Off", copy = null)
                        ValueRow("Static addresses (saved)", i.configuredAddresses.joinToString("\n").ifBlank { "None" }, copy = i.configuredAddresses.joinToString("\n").ifBlank { null })
                    }
                }
                when (i.type) {
                    NetInterfaceType.LINK_AGGREGATION -> item(key = "lag") {
                        Section("Link aggregation") {
                            ValueRow("Protocol", i.lagProtocol ?: "Unknown", copy = null)
                            i.xmitHashPolicy?.let { ValueRow("Transmit hash policy", it, copy = null) }
                            i.lacpduRate?.let { ValueRow("LACPDU rate", it, copy = null) }
                            ValueRow(
                                "Members",
                                i.lagPorts.joinToString("\n") { p -> if (p.flags.isEmpty()) p.name else "${p.name} (${p.flags.joinToString(", ") { it.lowercase() }})" }.ifBlank { "None" },
                                copy = i.lagPorts.joinToString("\n") { it.name }.ifBlank { null },
                            )
                        }
                    }
                    NetInterfaceType.VLAN -> item(key = "vlan") {
                        Section("VLAN") {
                            ValueRow("Parent interface", i.vlanParent ?: "Unknown")
                            ValueRow("VLAN tag", i.vlanTag?.toString() ?: "Unknown")
                            i.vlanPcp?.let { ValueRow("Priority (PCP)", it.toString(), copy = null) }
                        }
                    }
                    NetInterfaceType.BRIDGE -> item(key = "bridge") {
                        Section("Bridge") {
                            ValueRow("Members", i.bridgeMembers.joinToString("\n").ifBlank { "None" }, copy = i.bridgeMembers.joinToString("\n").ifBlank { null })
                        }
                    }
                    else -> {}
                }
                item(key = "reports") { ReportsLink(onReports) }
            }
        }
    }
}

fun mtuText(i: NetInterface): String {
    val now = i.mtu
    val saved = i.configuredMtu
    return when {
        now == null && saved == null -> "Default (1500)"
        now == null -> "$saved (saved)"
        saved == null || saved == now -> now.toString()
        else -> "$now (saved: $saved)"
    }
}

object NetworkSpeed {
    fun fromMbps(mbps: Double): String = if (mbps >= 1000) {
        val g = mbps / 1000
        if (g == Math.floor(g)) "${g.toLong()} Gb/s" else String.format(java.util.Locale.US, "%.1f Gb/s", g)
    } else "${mbps.toLong()} Mb/s"
}

