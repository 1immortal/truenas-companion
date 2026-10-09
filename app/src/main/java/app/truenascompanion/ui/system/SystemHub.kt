package app.truenascompanion.ui.system

import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.ListAlt
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.EventRepeat
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.Lan
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MiscellaneousServices
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Policy
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SettingsEthernet
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.SystemUpdateAlt
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.truenascompanion.data.model.Health
import app.truenascompanion.data.model.NasUpdateStatus
import app.truenascompanion.data.store.ConnectionTimeoutPrefs
import app.truenascompanion.data.store.ThemeMode
import app.truenascompanion.data.update.UpdateResult
import app.truenascompanion.ui.components.ElevatedSection
import app.truenascompanion.ui.components.IconBadge
import app.truenascompanion.ui.components.StatusChip
import app.truenascompanion.ui.theme.LocalStatusColors
import app.truenascompanion.util.Format

/**
 * System tab hub (1.4.1): a short header plus grouped tiles. Long settings live on their own pages ([SystemPage]);
 * NAS management screens (services, tasks, users…) keep their own routes. Every entry is one tap from the hub.
 */
enum class HubItem(val title: String, val keywords: String) {
    UPDATES("Updates", "truenas update upgrade version release notes boot environments boot pool activate clone train"),
    POWER("Power", "reboot restart shutdown shut down power off turn off"),
    TASKS("Running jobs", "tasks jobs running progress activity"),
    ALL_SERVERS("All servers", "servers overview saved nas switch status alerts multiple"),
    NETWORK("Network", "network interfaces ip address gateway dns name server nameserver hostname domain routes static vlan bridge lag bond aggregation mtu dhcp mac ipmi proxy"),
    SERVICES("Services", "ssh smb nfs ups snmp ftp iscsi cifs start stop autostart"),
    SCHEDULED("Scheduled tasks", "cron jobs init shutdown scripts startup schedule"),
    ACCOUNTS("Users & groups", "users accounts groups passwords ssh keys"),
    CERTIFICATES("Certificates", "tls ssl https acme let's encrypt expiry csr ca"),
    AUDIT("Audit log", "audit sign-ins logins changes history who"),
    SHELL("Shell", "terminal console command line ssh root"),
    REPORTS("Reports", "reporting charts cpu memory network disks temperature graphs history"),
    ALERTS("Phone alerts", "notifications alerts instant severity quiet hours battery certificate expiry"),
    SECURITY("App lock & privacy", "security privacy lock fingerprint biometric pin recents confirm dangerous"),
    CONNECTION("Connection", "timeout retry give up overlay offline network address local remote auto-switch vpn wireguard tailscale https certificate"),
    APPEARANCE("Appearance", "theme dark light dynamic color colour wallpaper"),
    ABOUT("About", "version app update channel release debug github check daily web ui open source"),
    ;

    val icon: ImageVector get() = when (this) {
        UPDATES -> Icons.Rounded.SystemUpdateAlt
        POWER -> Icons.Rounded.PowerSettingsNew
        TASKS -> Icons.AutoMirrored.Rounded.ListAlt
        ALL_SERVERS -> Icons.Rounded.Storage
        NETWORK -> Icons.Rounded.Lan
        SERVICES -> Icons.Rounded.MiscellaneousServices
        SCHEDULED -> Icons.Rounded.EventRepeat
        ACCOUNTS -> Icons.Rounded.Group
        CERTIFICATES -> Icons.Rounded.VerifiedUser
        AUDIT -> Icons.Rounded.Policy
        SHELL -> Icons.Rounded.Terminal
        REPORTS -> Icons.Rounded.Insights
        ALERTS -> Icons.Rounded.NotificationsActive
        SECURITY -> Icons.Rounded.Lock
        CONNECTION -> Icons.Rounded.SettingsEthernet
        APPEARANCE -> Icons.Rounded.DarkMode
        ABOUT -> Icons.Rounded.Info
    }

    /** The in-tab page this item opens, or null when it has its own screen. */
    val page: SystemPage? get() = when (this) {
        UPDATES -> SystemPage.UPDATES
        POWER -> SystemPage.POWER
        ALERTS -> SystemPage.ALERTS
        SECURITY -> SystemPage.SECURITY
        CONNECTION -> SystemPage.CONNECTION
        APPEARANCE -> SystemPage.APPEARANCE
        ABOUT -> SystemPage.ABOUT
        else -> null
    }

    fun matches(query: String): Boolean {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return true
        val hay = "${title.lowercase()} $keywords"
        return q.split(Regex("\\s+")).all { hay.contains(it) }
    }
}

/** Where each hub tile goes. The shell action asks for confirmation (and the app lock) before opening. */
data class HubActions(
    val onPage: (SystemPage) -> Unit = {},
    val onJobs: () -> Unit = {},
    val onOverview: () -> Unit = {},
    val onServices: () -> Unit = {},
    val onScheduledTasks: () -> Unit = {},
    val onAccounts: () -> Unit = {},
    val onCertificates: () -> Unit = {},
    val onAudit: () -> Unit = {},
    val onReports: () -> Unit = {},
    val onShell: () -> Unit = {},
    val onNetwork: () -> Unit = {},
)

fun HubItem.open(a: HubActions) {
    page?.let { a.onPage(it); return }
    when (this) {
        HubItem.TASKS -> a.onJobs()
        HubItem.ALL_SERVERS -> a.onOverview()
        HubItem.SERVICES -> a.onServices()
        HubItem.SCHEDULED -> a.onScheduledTasks()
        HubItem.ACCOUNTS -> a.onAccounts()
        HubItem.CERTIFICATES -> a.onCertificates()
        HubItem.AUDIT -> a.onAudit()
        HubItem.REPORTS -> a.onReports()
        HubItem.SHELL -> a.onShell()
        HubItem.NETWORK -> a.onNetwork()
        else -> error("$this has a page")
    }
}

enum class HubGroup(val title: String, val items: List<HubItem>) {
    SERVER("Server", listOf(HubItem.UPDATES, HubItem.POWER, HubItem.NETWORK, HubItem.TASKS, HubItem.ALL_SERVERS)),
    SERVICES("Services & tasks", listOf(HubItem.SERVICES, HubItem.SCHEDULED)),
    SECURITY("Security & access", listOf(HubItem.ACCOUNTS, HubItem.CERTIFICATES, HubItem.AUDIT, HubItem.SHELL)),
    MONITORING("Monitoring", listOf(HubItem.REPORTS, HubItem.ALERTS)),
    APP("App", listOf(HubItem.SECURITY, HubItem.CONNECTION, HubItem.APPEARANCE, HubItem.ABOUT)),
}

/** Settings pages opened from the hub (route `system_page/{page}`). */
enum class SystemPage(val title: String) {
    UPDATES("Updates"),
    POWER("Power"),
    ALERTS("Phone alerts"),
    SECURITY("App lock & privacy"),
    CONNECTION("Connection"),
    APPEARANCE("Appearance"),
    ABOUT("About"),
    ;

    companion object {
        fun parse(value: String?): SystemPage? = entries.firstOrNull { it.name.equals(value, ignoreCase = true) }

        /** Extra argument on the notification-settings deep link (instant-alerts notification and app updates). */
        const val ARG_ALERTS = "service"
        const val ARG_APP_UPDATE = "app_update"

        /** Page a [app.truenascompanion.notify.DeepLink.DEST_SETTINGS] link opens; null keeps the hub. */
        fun forSettingsLink(arg: String?): SystemPage? = when (arg) {
            ARG_ALERTS, "alerts" -> ALERTS
            ARG_APP_UPDATE -> ABOUT
            else -> null
        }
    }
}

/** Header facts: the active server and the NAS's own report. */
data class HubHeader(
    val name: String? = null,
    val host: String? = null,
    val version: String? = null,
    val uptimeSeconds: Long? = null,
    val online: Boolean? = null,
)

/** One-line live text under a tile; [attention] highlights it (update ready, certificate expiring…). */
data class HubSubtitle(val text: String, val attention: Boolean = false)

/** Live facts behind the tile subtitles. Nulls mean "not known yet", which falls back to a short description. */
data class HubSummary(
    val connected: Boolean = false,
    val nasUpdate: NasUpdateStatus? = null,
    val runningJobs: Int? = null,
    val serverCount: Int = 0,
    val servicesRunning: Int? = null,
    val cronJobs: Int? = null,
    val initScripts: Int? = null,
    val certsExpiring: Int? = null,
    val certsExpired: Int? = null,
    val alertsMode: AlertsMode? = null,
    val lockOn: Boolean? = null,
    val giveUpMs: Long = ConnectionTimeoutPrefs.DEFAULT_MS,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = false,
    val appVersion: String = "",
    val appUpdate: UpdateResult? = null,
    val alertsIntervalMinutes: Int = 15,
) {
    enum class AlertsMode { OFF, INSTANT, PERIODIC, NO_SERVER }
}

private fun plural(n: Int, one: String, many: String = one + "s") = "$n " + if (n == 1) one else many

/** Pure mapping from facts to tile subtitles (unit tested). */
fun hubSubtitles(s: HubSummary): Map<HubItem, HubSubtitle> = HubItem.entries.associateWith { item ->
    when (item) {
        HubItem.UPDATES -> {
            val u = s.nasUpdate
            when {
                u == null -> HubSubtitle("Updates & boot")
                u.updateAvailable -> HubSubtitle("Update: ${u.newVersion}", attention = true)
                u.rebootRequired -> HubSubtitle("Reboot to finish", attention = true)
                u.upToDate -> HubSubtitle("Up to date")
                else -> HubSubtitle("Updates & boot")
            }
        }
        HubItem.POWER -> HubSubtitle("Reboot, turn off")
        HubItem.TASKS -> s.runningJobs?.let { HubSubtitle(if (it == 0) "Nothing running" else "$it running") } ?: HubSubtitle("Jobs & progress")
        HubItem.ALL_SERVERS -> HubSubtitle(if (s.serverCount > 0) "${s.serverCount} saved" else "Every saved NAS")
        HubItem.NETWORK -> HubSubtitle("View only")
        HubItem.SERVICES -> s.servicesRunning?.let { HubSubtitle("$it running") } ?: HubSubtitle("SSH, SMB, NFS…")
        HubItem.SCHEDULED -> {
            val c = s.cronJobs; val i = s.initScripts
            when {
                c == null && i == null -> HubSubtitle("Cron & scripts")
                (c ?: 0) == 0 && (i ?: 0) == 0 -> HubSubtitle("None yet")
                else -> HubSubtitle(listOfNotNull(c?.let { plural(it, "job") }, i?.let { plural(it, "script") }).joinToString(" · "))
            }
        }
        HubItem.ACCOUNTS -> HubSubtitle("Accounts & keys")
        HubItem.CERTIFICATES -> when {
            (s.certsExpired ?: 0) > 0 -> HubSubtitle("${s.certsExpired} expired", attention = true)
            (s.certsExpiring ?: 0) > 0 -> HubSubtitle("${s.certsExpiring} expiring soon", attention = true)
            s.certsExpired != null -> HubSubtitle("All valid")
            else -> HubSubtitle("TLS & ACME")
        }
        HubItem.AUDIT -> HubSubtitle("Who did what")
        HubItem.SHELL -> HubSubtitle(if (s.connected) "NAS terminal" else "Connect first")
        HubItem.REPORTS -> HubSubtitle("Usage history")
        HubItem.ALERTS -> when (s.alertsMode) {
            HubSummary.AlertsMode.INSTANT -> HubSubtitle("Instant")
            HubSummary.AlertsMode.PERIODIC -> HubSubtitle("Every ${intervalText(s.alertsIntervalMinutes)}")
            HubSummary.AlertsMode.OFF -> HubSubtitle("Off")
            HubSummary.AlertsMode.NO_SERVER -> HubSubtitle("Add a server first")
            null -> HubSubtitle("Notifications")
        }
        HubItem.SECURITY -> when (s.lockOn) {
            true -> HubSubtitle("On")
            false -> HubSubtitle("Off")
            null -> HubSubtitle("Lock & privacy")
        }
        HubItem.CONNECTION -> HubSubtitle("Retry for ${ConnectionTimeoutPrefs.label(s.giveUpMs)}")
        HubItem.APPEARANCE -> {
            val mode = s.themeMode.name.lowercase().replaceFirstChar { it.uppercase() }
            HubSubtitle(if (s.dynamicColor) "$mode · wallpaper" else "$mode theme")
        }
        HubItem.ABOUT -> when (val u = s.appUpdate) {
            is UpdateResult.Available -> HubSubtitle("Update: ${u.release.version}", attention = true)
            else -> HubSubtitle(if (s.appVersion.isBlank()) "Version & updates" else "Version ${s.appVersion}")
        }
    }
}

private fun intervalText(minutes: Int) = if (minutes % 60 == 0) "${minutes / 60} h" else "$minutes min"

/** Groups with only the items matching [query] (empty groups dropped). */
fun filterHub(query: String): List<Pair<HubGroup, List<HubItem>>> =
    HubGroup.entries.map { g -> g to g.items.filter { it.matches(query) } }.filter { it.second.isNotEmpty() }

/** 2 columns unless the per-tile text room (width scaled down by the font size) gets too small; 3 on tablets. */
fun hubColumns(screenWidthDp: Int, fontScale: Float): Int {
    val room = (screenWidthDp - 32) / fontScale.coerceAtLeast(0.5f)
    return when {
        room >= 640 -> 3
        room >= 280 -> 2
        else -> 1
    }
}

const val HUB_LIST_TAG = "system_hub_list"
fun hubTileTag(item: HubItem) = "hub_${item.name.lowercase()}"

/** Stateless hub (also rendered by tests and screenshots). */
@Composable
fun SystemHubContent(
    header: HubHeader,
    subtitles: Map<HubItem, HubSubtitle>,
    onOpen: (HubItem) -> Unit,
    onSwitchServer: () -> Unit,
    modifier: Modifier = Modifier,
    searchOpen: Boolean = false,
    query: String = "",
    onQuery: (String) -> Unit = {},
    onCloseSearch: () -> Unit = {},
    listState: LazyListState = rememberLazyListState(),
    contentPadding: PaddingValues = PaddingValues(16.dp),
) {
    val groups = filterHub(if (searchOpen) query else "")
    // Two tiles per row on phones; one per row when large fonts would squeeze the titles.
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(modifier) {
    val columns = hubColumns(maxWidth.value.toInt(), fontScale)
    LazyColumn(
        state = listState,
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.testTag(HUB_LIST_TAG),
    ) {
        if (searchOpen) item(key = "search") {
            OutlinedTextField(
                value = query, onValueChange = onQuery,
                modifier = Modifier.fillMaxWidth().testTag("hub_search"),
                singleLine = true,
                placeholder = { Text("Search System") },
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                trailingIcon = { IconButton(onClick = onCloseSearch) { Icon(Icons.Rounded.Close, "Close search") } },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                shape = MaterialTheme.shapes.large,
            )
        }
        if (!searchOpen || query.isBlank()) item(key = "header") { HubHeaderCard(header, onSwitchServer) }
        if (groups.isEmpty()) item(key = "empty") {
            Text(
                "Nothing matches “${query.trim()}”.", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(8.dp),
            )
        }
        groups.forEach { (group, items) ->
            item(key = "g_${group.name}") {
                Text(
                    group.title.uppercase(), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    // 1.8.0 (a11y): group labels are headings, so TalkBack users can jump between groups.
                    modifier = Modifier.padding(start = 4.dp, top = 8.dp).semantics { heading() },
                )
            }
            items.chunked(columns).forEachIndexed { row, pair ->
                item(key = "r_${group.name}_$row") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().height(androidx.compose.foundation.layout.IntrinsicSize.Min)) {
                        pair.forEach { item ->
                            HubTile(item, subtitles[item] ?: HubSubtitle(""), Modifier.weight(1f).fillMaxWidth()) { onOpen(item) }
                        }
                        repeat(columns - pair.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
        item(key = "bottom") { Spacer(Modifier.height(8.dp)) }
    }
    }
}

@Composable
fun HubHeaderCard(header: HubHeader, onSwitchServer: () -> Unit) {
    ElevatedSection(onClick = onSwitchServer, contentPadding = 14.dp, modifier = Modifier.testTag("hub_header")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(Icons.Rounded.Dns, size = 44.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(header.name ?: "No server", style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val line = listOfNotNull(header.host?.takeIf { it.isNotBlank() }, header.uptimeSeconds?.let { "up ${Format.uptime(it)}" }).joinToString(" · ")
                if (line.isNotEmpty()) Text(line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                when (header.online) {
                    true -> { Spacer(Modifier.height(6.dp)); StatusChip(Health.HEALTHY, header.version?.let { "TrueNAS $it" } ?: "Connected") }
                    false -> { Spacer(Modifier.height(6.dp)); StatusChip(Health.CRITICAL, "Offline") }
                    null -> {}
                }
            }
            Text("Switch", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
fun HubTile(item: HubItem, subtitle: HubSubtitle, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val warn = LocalStatusColors.current.warning
    // Titles and subtitles may wrap to a second line (long names, large fonts); the row keeps both tiles the same height.
    ElevatedSection(onClick = onClick, contentPadding = 10.dp, modifier = modifier.heightIn(min = 64.dp).testTag(hubTileTag(item))) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
            if (subtitle.attention) IconBadge(item.icon, tint = warn, size = 32.dp) else IconBadge(item.icon, size = 32.dp)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(item.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (subtitle.text.isNotEmpty()) Text(
                    subtitle.text, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    color = if (subtitle.attention) warn else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
