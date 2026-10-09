package app.truenascompanion.ui.system

import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.IconButton
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.ButtonDefaults
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Info
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import app.truenascompanion.ui.components.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import app.truenascompanion.ui.components.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.truenascompanion.AppContainer
import app.truenascompanion.BuildConfigInfo
import app.truenascompanion.data.api.userMessage
import app.truenascompanion.data.repository.ConnectionState
import app.truenascompanion.data.store.AppearanceSettings
import app.truenascompanion.data.store.ConnectionTimeoutPrefs
import app.truenascompanion.data.store.ThemeMode
import app.truenascompanion.ui.appViewModel
import app.truenascompanion.ui.components.ConfirmDialog
import app.truenascompanion.ui.components.ElevatedSection
import app.truenascompanion.ui.components.IconBadge
import app.truenascompanion.ui.components.SectionTitle
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SystemViewModel(private val c: AppContainer) : ViewModel() {
    val server = c.repository.activeServer
    val connection = c.repository.state
    val appearance = c.settings.appearance.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppearanceSettings())
    val connectionGiveUpMs = c.settings.connectionGiveUpMs.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ConnectionTimeoutPrefs.DEFAULT_MS)

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    fun reboot() = power(true)
    fun shutdown() = power(false)

    private fun power(reboot: Boolean) = viewModelScope.launch {
        try {
            c.repository.call { if (reboot) it.reboot() else it.shutdown() }
            _messages.trySend(if (reboot) "Reboot started. The server will be back in a few minutes." else "Shutdown started.")
            c.repository.disconnect()
        } catch (e: Throwable) {
            _messages.trySend("Failed: ${e.userMessage()}")
        }
    }

    fun setTheme(mode: ThemeMode) = viewModelScope.launch { c.settings.setThemeMode(mode) }
    fun setDynamic(on: Boolean) = viewModelScope.launch { c.settings.setDynamicColor(on) }
    fun setConnectionGiveUp(ms: Long) = viewModelScope.launch { c.settings.setConnectionGiveUpMs(ms) }
}

/** Live facts for the hub's tile subtitles. Every remote lookup is optional: a failure just keeps the plain text. */
class SystemHubViewModel(private val c: AppContainer) : ViewModel() {
    data class Remote(
        val info: app.truenascompanion.data.model.SystemInfo? = null,
        val nasUpdate: app.truenascompanion.data.model.NasUpdateStatus? = null,
        val runningJobs: Int? = null,
        val servicesRunning: Int? = null,
        val cronJobs: Int? = null,
        val initScripts: Int? = null,
        val certs: List<app.truenascompanion.data.model.NasCertificate>? = null,
    )

    private val remote = MutableStateFlow(Remote())
    private val appVersion = BuildConfigInfo.versionName(c.context)
    val server = c.repository.activeServer
    val connection = c.repository.state

    private data class Local(
        val servers: Int,
        val prefs: app.truenascompanion.data.store.NotificationPrefs?,
        val lock: app.truenascompanion.data.security.LockSettings?,
        val giveUpMs: Long,
        val appearance: AppearanceSettings,
    )

    private val local = combine(
        c.settings.servers.map { it.size },
        c.settings.notificationPrefs.map<app.truenascompanion.data.store.NotificationPrefs, app.truenascompanion.data.store.NotificationPrefs?> { it },
        c.settings.lockSettings.map<app.truenascompanion.data.security.LockSettings, app.truenascompanion.data.security.LockSettings?> { it },
        c.settings.connectionGiveUpMs,
        c.settings.appearance,
    ) { n, p, l, g, a -> Local(n, p, l, g, a) }

    val summary: StateFlow<HubSummary> = combine(local, remote, c.repository.state, c.repository.activeServer, c.updates.latest) { l, r, conn, server, appUpdate ->
        val now = System.currentTimeMillis()
        val warn = l.prefs?.certWarnDays ?: 14
        val statuses = r.certs?.map { it.status(now, warn) }
        HubSummary(
            connected = conn is ConnectionState.Connected,
            nasUpdate = r.nasUpdate,
            runningJobs = r.runningJobs,
            serverCount = l.servers,
            servicesRunning = r.servicesRunning,
            cronJobs = r.cronJobs,
            initScripts = r.initScripts,
            certsExpiring = statuses?.count { it == app.truenascompanion.data.model.CertStatus.EXPIRING },
            certsExpired = statuses?.count { it == app.truenascompanion.data.model.CertStatus.EXPIRED },
            alertsMode = l.prefs?.let { p ->
                when {
                    server == null -> HubSummary.AlertsMode.NO_SERVER
                    !p.isEnabled(server.id) -> HubSummary.AlertsMode.OFF
                    p.instant -> HubSummary.AlertsMode.INSTANT
                    else -> HubSummary.AlertsMode.PERIODIC
                }
            },
            alertsIntervalMinutes = l.prefs?.intervalMinutes ?: 15,
            lockOn = l.lock?.enabled,
            giveUpMs = l.giveUpMs,
            themeMode = l.appearance.themeMode,
            dynamicColor = l.appearance.dynamicColor,
            appVersion = appVersion,
            appUpdate = appUpdate,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), HubSummary())

    val header: StateFlow<HubHeader> = combine(c.repository.activeServer, c.repository.state, remote) { s, conn, r ->
        val info = r.info ?: (conn as? ConnectionState.Connected)?.info
        HubHeader(
            name = s?.name,
            host = s?.displayHost,
            version = info?.version,
            uptimeSeconds = if (conn is ConnectionState.Connected) info?.uptimeSeconds else null,
            online = when (conn) { is ConnectionState.Connected -> true; is ConnectionState.Failed -> false; else -> null },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), HubHeader())

    private var loading: kotlinx.coroutines.Job? = null
    private var lastRefresh = 0L

    init {
        // A new server or session starts from scratch so another NAS's numbers never show up.
        viewModelScope.launch {
            c.repository.reloadKey.distinctUntilChanged().collect {
                loading?.cancel(); remote.value = Remote(); lastRefresh = 0L; refresh()
            }
        }
        // Fill in the hints as soon as the connection is up (the tab may already be open).
        viewModelScope.launch {
            c.repository.state.map { it is ConnectionState.Connected }.distinctUntilChanged().collect { if (it) refresh() }
        }
    }

    /** Fetches the live facts in parallel (on resume, when connected, and when the session changes; at most every 30 s). */
    fun refresh() {
        if (c.repository.state.value !is ConnectionState.Connected) return
        if (loading?.isActive == true) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (lastRefresh != 0L && now - lastRefresh < 30_000) return
        lastRefresh = now
        loading = viewModelScope.launch {
            suspend fun <T> soft(block: suspend () -> T): T? =
                runCatching { kotlinx.coroutines.withTimeoutOrNull(20_000) { block() } }.getOrNull()
            kotlinx.coroutines.coroutineScope {
                launch { soft { c.repository.call { it.systemInfo() } }?.let { v -> remote.update { it.copy(info = v) } } }
                launch { soft { c.repository.call { app.truenascompanion.data.api.NasSystemApi(it).updateStatus() } }?.let { v -> remote.update { it.copy(nasUpdate = v) } } }
                launch { soft { c.repository.call { app.truenascompanion.data.api.ServicesApi(it).services() } }?.let { v -> remote.update { it.copy(servicesRunning = v.count { s -> s.running }) } } }
                launch { soft { c.repository.call { app.truenascompanion.data.api.TasksApi(it).cronJobs() } }?.let { v -> remote.update { it.copy(cronJobs = v.size) } } }
                launch { soft { c.repository.call { app.truenascompanion.data.api.TasksApi(it).initScripts() } }?.let { v -> remote.update { it.copy(initScripts = v.size) } } }
                launch { soft { c.repository.call { app.truenascompanion.data.api.CertificatesApi(it).certificates() } }?.let { v -> remote.update { it.copy(certs = v) } } }
                launch { soft { c.repository.jobs().first() }?.let { v -> remote.update { it.copy(runningJobs = v.count { j -> j.state.active }) } } }
            }
        }
    }
}

/**
 * System tab (1.4.1): a compact hub. A header with the active server, then five groups of tiles with a live
 * one-line status each. Settings that used to sit inline open as pages ([SystemPage]); everything is one tap away.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SystemScreen(
    onServers: () -> Unit,
    onOverview: () -> Unit = {},
    onJobs: () -> Unit = {},
    onShell: () -> Unit = {},
    onAccounts: () -> Unit = {},
    onReports: () -> Unit = {},
    onAudit: () -> Unit = {},
    onCertificates: () -> Unit = {},
    onServices: () -> Unit = {},
    onScheduledTasks: () -> Unit = {},
    onPage: (SystemPage) -> Unit = {},
) {
    val vm = appViewModel { SystemHubViewModel(it) }
    val server by vm.server.collectAsStateWithLifecycle()
    val connection by vm.connection.collectAsStateWithLifecycle()
    val summary by vm.summary.collectAsStateWithLifecycle()
    val header by vm.header.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var confirmShell by remember { mutableStateOf(false) }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val lockGuard = app.truenascompanion.ui.lock.LocalLockGuard.current
    val uiScope = rememberCoroutineScope()
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) { vm.refresh(); onPauseOrDispose { } }

    val actions = HubActions(
        onPage = onPage, onJobs = onJobs, onOverview = onOverview, onServices = onServices, onScheduledTasks = onScheduledTasks,
        onAccounts = onAccounts, onCertificates = onCertificates, onAudit = onAudit, onReports = onReports,
        onShell = {
            if (connection is ConnectionState.Connected) confirmShell = true
            else uiScope.launch { snackbar.showSnackbar("Connect to the NAS to open a shell") }
        },
    )
    val open: (HubItem) -> Unit = { it.open(actions) }

    BackHandler(enabled = searchOpen) { searchOpen = false; query = "" }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("System") },
                actions = {
                    if (!searchOpen) IconButton(onClick = { searchOpen = true }) { Icon(Icons.Rounded.Search, "Search System") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        SystemHubContent(
            header = header,
            subtitles = hubSubtitles(summary),
            onOpen = open,
            onSwitchServer = onServers,
            modifier = Modifier.padding(padding).fillMaxSize(),
            searchOpen = searchOpen,
            query = query,
            onQuery = { query = it },
            onCloseSearch = { searchOpen = false; query = "" },
        )
    }

    if (confirmShell) ConfirmDialog(
        title = "Open a shell on ${server?.name ?: "the NAS"}?",
        text = "You get a terminal on the NAS with your account's rights (like System › Shell in the web UI). Commands can change or delete anything. " +
            "The shell closes when you leave it or the app stays in the background for 30 s, and running commands stop with it.",
        confirmLabel = "Open shell", icon = Icons.Rounded.Terminal, requireAuth = false,
        onConfirm = { confirmShell = false; lockGuard.guard("Open a shell on the NAS", onShell) }, onDismiss = { confirmShell = false },
    )
}

/** A settings page opened from the hub. Pushed on top of the System tab, so the connection overlay still covers it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SystemPageScreen(page: SystemPage, onBack: () -> Unit) {
    val vm = appViewModel { SystemViewModel(it) }
    val server by vm.server.collectAsStateWithLifecycle()
    val connection by vm.connection.collectAsStateWithLifecycle()
    val appearance by vm.appearance.collectAsStateWithLifecycle()
    val giveUpMs by vm.connectionGiveUpMs.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var confirm by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val uiScope = rememberCoroutineScope()
    val say: (String) -> Unit = { msg -> uiScope.launch { snackbar.showSnackbar(msg) } }
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(page.title) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            when (page) {
                SystemPage.UPDATES -> {
                    item { SectionTitle("TrueNAS update") }
                    item { NasUpdateSection() }
                    item { SectionTitle("Boot environments") }
                    item { BootEnvSection() }
                }
                SystemPage.POWER -> item {
                    PowerSection(
                        serverName = server?.name,
                        enabled = connection is ConnectionState.Connected,
                        onReboot = { confirm = "reboot" }, onShutdown = { confirm = "shutdown" },
                    )
                }
                SystemPage.ALERTS -> item { app.truenascompanion.ui.notifications.PhoneAlertsSettings(server, say) }
                SystemPage.SECURITY -> item { app.truenascompanion.ui.lock.SecuritySettings(say) }
                SystemPage.CONNECTION -> item { ConnectionTimeoutSection(giveUpMs = giveUpMs, onSelect = vm::setConnectionGiveUp) }
                SystemPage.APPEARANCE -> item { AppearanceSection(appearance, onTheme = vm::setTheme, onDynamic = vm::setDynamic) }
                SystemPage.ABOUT -> item {
                    AboutSection(
                        versionName = BuildConfigInfo.versionName(context),
                        onOpenWebUi = server?.let { s -> { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, s.url.toUri())) } } },
                    ) { UpdateSection() }
                }
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }

    when (confirm) {
        "reboot" -> ConfirmDialog(
            title = "Reboot ${server?.name ?: "server"}?",
            text = "The NAS will restart now. Apps, shares and VMs will be unavailable for a few minutes.",
            confirmLabel = "Reboot", destructive = true, icon = Icons.Rounded.RestartAlt,
            onConfirm = { confirm = null; vm.reboot() }, onDismiss = { confirm = null },
        )
        "shutdown" -> ConfirmDialog(
            title = "Shut down ${server?.name ?: "server"}?",
            text = "The NAS will power off. You will need physical access (or IPMI / Wake-on-LAN) to turn it back on.",
            confirmLabel = "Shut down", destructive = true, icon = Icons.Rounded.PowerSettingsNew,
            onConfirm = { confirm = null; vm.shutdown() }, onDismiss = { confirm = null },
        )
    }
}

/** Power page body: what happens, then the two buttons (each asks for confirmation). */
@Composable
fun PowerSection(serverName: String?, enabled: Boolean, onReboot: () -> Unit, onShutdown: () -> Unit) {
    ElevatedSection {
        SettingRow(Icons.Rounded.PowerSettingsNew, serverName ?: "Server", if (enabled) "Connected" else "Connect to the NAS to use these")
        Spacer(Modifier.height(12.dp))
        Text(
            "Restart or turn off the NAS. Apps, shares and VMs will be unavailable meanwhile. You'll be asked to confirm first.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        PowerButtons(enabled = enabled, onReboot = onReboot, onShutdown = onShutdown)
    }
}

/** Theme and dynamic color. */
@Composable
fun AppearanceSection(appearance: AppearanceSettings, onTheme: (ThemeMode) -> Unit, onDynamic: (Boolean) -> Unit) {
    ElevatedSection {
        SettingRow(Icons.Rounded.DarkMode, "Theme")
        Spacer(Modifier.height(10.dp))
        val modes = ThemeMode.entries
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            modes.forEachIndexed { i, m ->
                SegmentedButton(
                    selected = appearance.themeMode == m, onClick = { onTheme(m) },
                    shape = SegmentedButtonDefaults.itemShape(i, modes.size),
                    icon = {},
                ) { Text(m.name.lowercase().replaceFirstChar { it.uppercase() }, maxLines = 1, softWrap = false) }
            }
        }
        Spacer(Modifier.height(12.dp))
        SettingRow(Icons.Rounded.Palette, "Dynamic color", "Use your wallpaper colors instead of the YTN theme (Android 12+)") {
            Switch(checked = appearance.dynamicColor, onCheckedChange = onDynamic)
        }
    }
}

const val APP_TAGLINE = "Your TrueNAS companion"
const val TRADEMARK_NOTE = "YTN is an independent project, not affiliated with or endorsed by iXsystems. TrueNAS is a trademark of iXsystems, Inc."

/** App version, the app-update block ([updates]) and a shortcut to the NAS web UI. */
@Composable
fun AboutSection(versionName: String, onOpenWebUi: (() -> Unit)?, updates: @Composable () -> Unit) {
    ElevatedSection {
        SettingRow(Icons.Rounded.Info, "YTN $versionName", "$APP_TAGLINE. Free & open source. No ads, no analytics, no tracking.")
        Spacer(Modifier.height(8.dp))
        updates()
        Spacer(Modifier.height(12.dp))
        Text(
            TRADEMARK_NOTE,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        onOpenWebUi?.let { open ->
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = open, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Open TrueNAS web UI", maxLines = 1)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ConnectionTimeoutSection(giveUpMs: Long, onSelect: (Long) -> Unit) {
    ElevatedSection {
        SettingRow(
            Icons.Rounded.Schedule,
            "Give up after",
            "How long to keep trying (and auto-retrying) before the connection-failure overlay. Default matches a single connect attempt (10 s).",
        )
        Spacer(Modifier.height(10.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ConnectionTimeoutPrefs.PRESETS_MS.forEach { ms ->
                FilterChip(
                    selected = giveUpMs == ms,
                    onClick = { onSelect(ms) },
                    label = { Text(ConnectionTimeoutPrefs.label(ms), maxLines = 1) },
                )
            }
        }
    }
}

/** Icon + title (+ subtitle) row with the icon centered on the first line of text, optional trailing control. */
@Composable
fun SettingRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String? = null, trailing: (@Composable () -> Unit)? = null) {
    // Multi-line rows align the icon with the title line instead of floating in the middle of the text block.
    Row(verticalAlignment = if (subtitle == null) Alignment.CenterVertically else Alignment.Top) {
        IconBadge(icon, size = 36.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f).padding(top = if (subtitle == null) 0.dp else 6.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        trailing?.let { Spacer(Modifier.width(12.dp)); Box(Modifier.align(Alignment.CenterVertically)) { it() } }
    }
}

/** Two equal-size, single-line power buttons. */
@Composable
fun PowerButtons(enabled: Boolean, onReboot: () -> Unit, onShutdown: () -> Unit) {
    val danger = MaterialTheme.colorScheme.error
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth().height(androidx.compose.foundation.layout.IntrinsicSize.Min)) {
        OutlinedButton(onClick = onReboot, enabled = enabled, modifier = Modifier.weight(1f).fillMaxHeight().heightIn(min = 48.dp),
            contentPadding = PaddingValues(horizontal = 12.dp)) {
            Icon(Icons.Rounded.RestartAlt, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
            Text("Reboot", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
        }
        OutlinedButton(onClick = onShutdown, enabled = enabled, modifier = Modifier.weight(1f).fillMaxHeight().heightIn(min = 48.dp),
            contentPadding = PaddingValues(horizontal = 12.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = danger),
            border = androidx.compose.foundation.BorderStroke(1.dp, if (enabled) danger.copy(alpha = 0.6f) else MaterialTheme.colorScheme.outlineVariant)) {
            Icon(Icons.Rounded.PowerSettingsNew, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
            Text("Shut down", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** "Shell" row: a root terminal on the NAS, like System › Shell in the web UI. */
@Composable
fun ShellEntry(enabled: Boolean, onClick: () -> Unit) {
    ElevatedSection(onClick = if (enabled) onClick else null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(Icons.Rounded.Terminal)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Shell", style = MaterialTheme.typography.titleMedium)
                Text(if (enabled) "Terminal on the NAS, like System › Shell in the web UI" else "Connect to the NAS to open a shell",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, tint = MaterialTheme.colorScheme.primary)
        }
    }
}
