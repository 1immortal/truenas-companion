package app.truenascompanion.ui.system

import androidx.compose.foundation.layout.Box
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.ButtonDefaults
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.Policy
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.ListAlt
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.MiscellaneousServices
import androidx.compose.material.icons.rounded.EventRepeat
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import app.truenascompanion.data.model.Health
import app.truenascompanion.data.repository.ConnectionState
import app.truenascompanion.data.store.AppearanceSettings
import app.truenascompanion.data.store.ConnectionTimeoutPrefs
import app.truenascompanion.data.store.ThemeMode
import app.truenascompanion.ui.appViewModel
import app.truenascompanion.ui.components.ConfirmDialog
import app.truenascompanion.ui.components.ElevatedSection
import app.truenascompanion.ui.components.IconBadge
import app.truenascompanion.ui.components.SectionTitle
import app.truenascompanion.ui.components.StatusChip
import app.truenascompanion.ui.components.UiState
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
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
) {
    val vm = appViewModel { SystemViewModel(it) }
    val server by vm.server.collectAsStateWithLifecycle()
    val connection by vm.connection.collectAsStateWithLifecycle()
    val appearance by vm.appearance.collectAsStateWithLifecycle()
    val giveUpMs by vm.connectionGiveUpMs.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var confirm by remember { mutableStateOf<String?>(null) }
    var confirmShell by remember { mutableStateOf(false) }
    val lockGuard = app.truenascompanion.ui.lock.LocalLockGuard.current
    val context = LocalContext.current
    val uiScope = androidx.compose.runtime.rememberCoroutineScope()
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }

    Scaffold(topBar = { TopAppBar(title = { Text("System") }) }, snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxSize()) {
                item {
                    ElevatedSection(onClick = onServers) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconBadge(Icons.Rounded.Dns)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(server?.name ?: "No server", style = MaterialTheme.typography.titleMedium)
                                Text(server?.displayHost ?: "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                val c = connection
                                if (c is ConnectionState.Connected) {
                                    Spacer(Modifier.height(6.dp))
                                    StatusChip(Health.HEALTHY, c.info?.version ?: "Connected")
                                } else if (c is ConnectionState.Failed) {
                                    Spacer(Modifier.height(6.dp))
                                    StatusChip(Health.CRITICAL, "Offline")
                                }
                            }
                            Text("Switch", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                            Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }

                item {
                    ElevatedSection(onClick = onOverview) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconBadge(Icons.Rounded.Dns)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text("All servers", style = MaterialTheme.typography.titleMedium)
                                Text("Status and alerts for every saved NAS", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }

                item {
                    ElevatedSection(onClick = onJobs) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconBadge(Icons.AutoMirrored.Rounded.ListAlt)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text("Tasks", style = MaterialTheme.typography.titleMedium)
                                Text("Running and recent jobs with live progress", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }

                item { ShellEntry(enabled = connection is ConnectionState.Connected) { confirmShell = true } }

                item { SectionTitle("Manage") }
                item {
                    ElevatedSection(contentPadding = 6.dp) {
                        ManageRow(Icons.Rounded.Group, "Users & groups", "Accounts, passwords, SSH keys and groups", onAccounts)
                        ManageDivider()
                        ManageRow(Icons.Rounded.Insights, "Reports", "CPU, memory, network, disks and temperatures over time", onReports)
                        ManageDivider()
                        ManageRow(Icons.Rounded.Policy, "Audit log", "Who signed in and what changed", onAudit)
                        ManageDivider()
                        ManageRow(Icons.Rounded.VerifiedUser, "Certificates", "Expiry, ACME, imports and the web UI certificate", onCertificates)
                        ManageDivider()
                        ManageRow(Icons.Rounded.MiscellaneousServices, "Services", "Start, stop and configure SSH, SMB, NFS, UPS, SNMP, FTP…", onServices)
                        ManageDivider()
                        ManageRow(Icons.Rounded.EventRepeat, "Scheduled tasks", "Cron jobs and init/shutdown scripts", onScheduledTasks)
                    }
                }

                item { SectionTitle("TrueNAS update") }
                item { NasUpdateSection() }

                item { SectionTitle("Boot environments") }
                item { BootEnvSection() }

                item { SectionTitle("Power") }
                item {
                    ElevatedSection {
                        Text("Restart or turn off the NAS. Apps, shares and VMs will be unavailable meanwhile.",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(12.dp))
                        PowerButtons(
                            enabled = connection is ConnectionState.Connected,
                            onReboot = { confirm = "reboot" }, onShutdown = { confirm = "shutdown" },
                        )
                    }
                }

                item { SectionTitle("Connection") }
                item {
                    ConnectionTimeoutSection(
                        giveUpMs = giveUpMs,
                        onSelect = vm::setConnectionGiveUp,
                    )
                }

                item { SectionTitle("Security") }
                item {
                    app.truenascompanion.ui.lock.SecuritySettings { msg -> uiScope.launch { snackbar.showSnackbar(msg) } }
                }
                item { SectionTitle("Phone alerts") }
                item {
                    app.truenascompanion.ui.notifications.PhoneAlertsSettings(server) { msg -> uiScope.launch { snackbar.showSnackbar(msg) } }
                }

                item { SectionTitle("Appearance") }
                item {
                    ElevatedSection {
                        SettingRow(Icons.Rounded.DarkMode, "Theme")
                        Spacer(Modifier.height(10.dp))
                        val modes = ThemeMode.entries
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                            modes.forEachIndexed { i, m ->
                                SegmentedButton(
                                    selected = appearance.themeMode == m, onClick = { vm.setTheme(m) },
                                    shape = SegmentedButtonDefaults.itemShape(i, modes.size),
                                    icon = {},
                                ) { Text(m.name.lowercase().replaceFirstChar { it.uppercase() }, maxLines = 1, softWrap = false) }
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        SettingRow(Icons.Rounded.Palette, "Dynamic color", "Use your wallpaper colors instead of the TrueNAS Companion theme (Android 12+)") {
                            Switch(checked = appearance.dynamicColor, onCheckedChange = vm::setDynamic)
                        }
                    }
                }

                item { SectionTitle("About") }
                item {
                    ElevatedSection {
                        SettingRow(Icons.Rounded.Info, "TrueNAS Companion ${BuildConfigInfo.versionName(context)}", "Free & open source. No ads, no analytics, no tracking.")
                        Spacer(Modifier.height(8.dp))
                        UpdateSection()
                        server?.let { s ->
                            Spacer(Modifier.height(8.dp))
                            OutlinedButton(
                                onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, s.url.toUri())) } },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Open TrueNAS web UI", maxLines = 1)
                            }
                        }
                    }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }

    if (confirmShell) ConfirmDialog(
        title = "Open a shell on ${server?.name ?: "the NAS"}?",
        text = "You get a terminal on the NAS with your account's rights (like System › Shell in the web UI). Commands can change or delete anything. " +
            "The shell closes when you leave it or the app stays in the background for 30 s, and running commands stop with it.",
        confirmLabel = "Open shell", icon = Icons.Rounded.Terminal, requireAuth = false,
        onConfirm = { confirmShell = false; lockGuard.guard("Open a shell on the NAS", onShell) }, onDismiss = { confirmShell = false },
    )

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

@Composable
private fun ManageRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 12.dp),
    ) {
        IconBadge(icon, size = 36.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, tint = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun ManageDivider() = HorizontalDivider(Modifier.padding(horizontal = 12.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
