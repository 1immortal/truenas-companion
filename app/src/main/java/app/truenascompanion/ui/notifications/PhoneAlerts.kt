package app.truenascompanion.ui.notifications

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryAlert
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Downloading
import androidx.compose.material.icons.rounded.Rule
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.foundation.clickable
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.NotificationsOff
import androidx.compose.material.icons.rounded.PriorityHigh
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.truenascompanion.AppContainer
import app.truenascompanion.TrueNasApp
import app.truenascompanion.data.model.ServerConfig
import app.truenascompanion.data.store.NotificationPrefs
import app.truenascompanion.notify.AlertLevel
import app.truenascompanion.notify.AlertScheduler
import app.truenascompanion.notify.SeverityGroup
import app.truenascompanion.ui.components.ConfirmDialog
import app.truenascompanion.ui.components.ElevatedSection
import app.truenascompanion.ui.components.GlowButton
import app.truenascompanion.ui.components.IconBadge
import app.truenascompanion.ui.system.SettingRow
import app.truenascompanion.ui.theme.LocalBrandColors
import app.truenascompanion.ui.theme.LocalStatusColors
import kotlinx.coroutines.launch
import java.util.Calendar

// ---------------------------------------------------------------------------------------------------------------------
// System state helpers
// ---------------------------------------------------------------------------------------------------------------------

object PhoneAlerts {
    fun hasPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun canNotify(context: Context): Boolean =
        hasPermission(context) && androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()

    fun ignoresBatteryOptimizations(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) ?: true

    fun openNotificationSettings(context: Context) {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        runCatching { context.startActivity(intent) }.onFailure { openAppDetails(context) }
    }

    @SuppressLint("BatteryLife") // user-initiated, explained in the UI; the app is not distributed on Google Play
    fun openBatterySettings(context: Context) {
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, "package:${context.packageName}".toUri())
        runCatching { context.startActivity(direct) }
            .recoverCatching { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
            .onFailure { openAppDetails(context) }
    }

    private fun openAppDetails(context: Context) {
        runCatching { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri())) }
    }

    /** Turns alerts on for [serverId]; the next check sets a fresh baseline (no flood of already-known alerts). */
    suspend fun enable(container: AppContainer, context: Context, serverId: String) {
        container.settings.clearSeenAlerts(serverId)
        container.settings.updateNotificationPrefs { it.copy(enabledServers = it.enabledServers + serverId, promptDismissed = true) }
        AlertScheduler.checkNow(context.applicationContext)
    }

    suspend fun disable(container: AppContainer, serverId: String) {
        container.settings.updateNotificationPrefs { it.copy(enabledServers = it.enabledServers - serverId) }
        container.settings.clearSeenAlerts(serverId)
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * Returns a function that makes sure the app may post notifications, then calls [onReady].
 * Android 13+: a friendly explainer first, then the system permission prompt. If notifications are blocked
 * permanently, offers the app's notification settings instead.
 */
@Composable
fun rememberNotificationAccess(onReady: () -> Unit): () -> Unit {
    val context = LocalContext.current
    var explain by remember { mutableStateOf(false) }
    var blocked by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) onReady()
        else if (Build.VERSION.SDK_INT >= 33 &&
            context.findActivity()?.shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) == false
        ) blocked = true
    }
    if (explain) {
        ConfirmDialog(
            title = "Get alerts on your phone",
            text = "YTN checks your NAS in the background and notifies you when a new alert appears.\n\n" +
                "Your phone talks directly to your server: no cloud service, no ads, no tracking. " +
                "Android will ask you to allow notifications next.",
            confirmLabel = "Continue",
            icon = Icons.Rounded.NotificationsActive,
            onConfirm = {
                explain = false
                if (Build.VERSION.SDK_INT >= 33) launcher.launch(Manifest.permission.POST_NOTIFICATIONS) else onReady()
            },
            onDismiss = { explain = false },
        )
    }
    if (blocked) {
        ConfirmDialog(
            title = "Notifications are off",
            text = "Notifications are turned off for YTN. Turn them on in Android settings to receive alerts.",
            confirmLabel = "Open settings",
            icon = Icons.Rounded.NotificationsOff,
            onConfirm = { blocked = false; PhoneAlerts.openNotificationSettings(context) },
            onDismiss = { blocked = false },
        )
    }
    return {
        when {
            PhoneAlerts.canNotify(context) -> onReady()
            !PhoneAlerts.hasPermission(context) -> explain = true
            else -> blocked = true // permission granted but the app's notifications are switched off
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------------
// System screen section
// ---------------------------------------------------------------------------------------------------------------------

/** Stateful "Phone alerts" section for the System screen. */
@Composable
fun PhoneAlertsSettings(server: ServerConfig?, onMessage: (String) -> Unit, onRules: () -> Unit = {}) {
    val context = LocalContext.current
    val container = (context.applicationContext as TrueNasApp).container
    val prefs by container.settings.notificationPrefs.collectAsStateWithLifecycle(initialValue = null)
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) { tick++; onPauseOrDispose { } }
    val canNotify = remember(tick) { PhoneAlerts.canNotify(context) }
    val batteryOk = remember(tick) { PhoneAlerts.ignoresBatteryOptimizations(context) }
    val p = prefs ?: return

    val enable = rememberNotificationAccess {
        tick++
        server?.let { s -> scope.launch { PhoneAlerts.enable(container, context, s.id) } }
    }
    val allow = rememberNotificationAccess { tick++ }

    PhoneAlertsSection(
        server = server,
        prefs = p,
        canNotify = canNotify,
        batteryOk = batteryOk,
        onToggle = { on ->
            val s = server ?: return@PhoneAlertsSection
            if (on) enable() else scope.launch { PhoneAlerts.disable(container, s.id) }
        },
        onUpdate = { f -> scope.launch { container.settings.updateNotificationPrefs(f) } },
        onAllowNotifications = allow,
        onBattery = { PhoneAlerts.openBatterySettings(context) },
        onChannels = { PhoneAlerts.openNotificationSettings(context) },
        onTest = {
            if (container.notifier.postTest(server)) onMessage("Test notification sent")
            else allow()
        },
        onRules = onRules,
    )
}

/** Stateless section (also rendered by the screenshot tests). */
@Composable
fun PhoneAlertsSection(
    server: ServerConfig?,
    prefs: NotificationPrefs,
    canNotify: Boolean,
    batteryOk: Boolean,
    onToggle: (Boolean) -> Unit,
    onUpdate: ((NotificationPrefs) -> NotificationPrefs) -> Unit,
    onAllowNotifications: () -> Unit,
    onBattery: () -> Unit,
    onChannels: () -> Unit,
    onTest: () -> Unit,
    onRules: () -> Unit = {},
) {
    val enabled = prefs.isEnabled(server?.id)
    var pickTime by remember { mutableStateOf<String?>(null) }
    ElevatedSection {
        SettingRow(
            Icons.Rounded.NotificationsActive,
            "Alerts on this phone",
            when {
                server == null -> "Add a server first"
                enabled -> "On for ${server.name}. You'll be notified about new alerts."
                else -> "Get a notification when ${server.name} raises an alert"
            }, checked = enabled, onCheckedChange = onToggle, enabled = server != null)

        if (enabled && !canNotify) {
            Spacer(Modifier.height(12.dp))
            Hint(
                icon = Icons.Rounded.NotificationsOff,
                color = LocalStatusColors.current.critical,
                text = "Notifications are blocked for this app, so alerts can't be shown.",
                action = "Allow", onAction = onAllowNotifications,
            )
        }

        AnimatedVisibility(visible = enabled) {
            Column {
                Divider()
                SettingRow(Icons.Rounded.PriorityHigh, "Minimum severity", "${prefs.minLevel.label} and more severe") {
                    LevelPicker(prefs.minLevel) { lvl -> onUpdate { it.copy(minLevel = lvl) } }
                }
                Divider()
                SettingRow(
                    Icons.Rounded.Schedule, "Check every",
                    if (prefs.instant) "Also runs as a safety net while instant alerts are on" else "Needs a network connection. Android may delay checks a little to save battery.",
                )
                Spacer(Modifier.height(10.dp))
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    NotificationPrefs.INTERVALS.forEachIndexed { i, m ->
                        SegmentedButton(
                            selected = prefs.intervalMinutes == m,
                            onClick = { onUpdate { it.copy(intervalMinutes = m) } },
                            shape = SegmentedButtonDefaults.itemShape(i, NotificationPrefs.INTERVALS.size),
                            icon = {},
                        ) { Text(intervalLabel(m), maxLines = 1, softWrap = false) }
                    }
                }
                Divider()
                SettingRow(
                    Icons.Rounded.Bolt, "Instant alerts",
                    if (prefs.instant) "Live connection is open. Uses more battery; a silent notification shows while it's on."
                    else "Get alerts within seconds instead of every ${intervalLabel(prefs.intervalMinutes)}. Keeps a live connection open, which uses more battery.", checked = prefs.instant, onCheckedChange = { on -> onUpdate { it.copy(instant = on) } })
                if (!batteryOk) {
                    Spacer(Modifier.height(12.dp))
                    Hint(
                        icon = Icons.Rounded.BatteryAlert,
                        color = LocalStatusColors.current.warning,
                        text = if (prefs.instant) "Battery optimization can cut the live connection. Allow background activity to keep instant alerts reliable (optional)."
                        else "Android may postpone checks to save battery. Allowing background activity makes alerts more reliable (optional).",
                        action = "Allow", onAction = onBattery,
                    )
                }
                Divider()
                SettingRow(Icons.Rounded.CheckCircle, "Notify when an alert clears", checked = prefs.notifyOnClear, onCheckedChange = { on -> onUpdate { it.copy(notifyOnClear = on) } })
                Divider()
                // 1.10.0: phone alert rules and progress notifications
                androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth().clickable(onClickLabel = "Open alert rules", onClick = onRules)) {
                    SettingRow(Icons.Rounded.Rule, "Alert rules", "Your own rules, checked on this phone: pool usage, disk temperature, backups, scrubs and more") {
                        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Divider()
                SettingRow(
                    Icons.Rounded.Downloading, "Progress notifications",
                    "Shows running scrubs, resilvers, replications, cloud syncs and TrueNAS updates. Refreshed during the checks, every minute with instant alerts.",
                    checked = prefs.progressEnabled, onCheckedChange = { on -> onUpdate { it.copy(progressEnabled = on) } },
                )
                Divider()
                SettingRow(
                    Icons.Rounded.VerifiedUser, "Certificate expiry",
                    if (prefs.certWarnEnabled) "Warns ${prefs.certWarnDays} days before a certificate expires. Checked during the alert checks, at most twice a day."
                    else "Off", checked = prefs.certWarnEnabled, onCheckedChange = { on -> onUpdate { it.copy(certWarnEnabled = on) } })
                AnimatedVisibility(visible = prefs.certWarnEnabled) {
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 10.dp)) {
                        NotificationPrefs.CERT_WARN_DAYS.forEachIndexed { i, d ->
                            SegmentedButton(
                                selected = prefs.certWarnDays == d,
                                onClick = { onUpdate { it.copy(certWarnDays = d) } },
                                shape = SegmentedButtonDefaults.itemShape(i, NotificationPrefs.CERT_WARN_DAYS.size),
                                icon = {},
                            ) { Text("$d days", maxLines = 1, softWrap = false) }
                        }
                    }
                }
                Divider()
                SettingRow(
                    Icons.Rounded.Bedtime, "Quiet hours",
                    if (prefs.quietCriticalBreaksThrough) "Only Critical and more severe alerts come through" else "Nothing comes through; alerts wait on the Alerts tab",
                    checked = prefs.quietEnabled, onCheckedChange = { on -> onUpdate { it.copy(quietEnabled = on) } },
                )
                AnimatedVisibility(visible = prefs.quietEnabled) {
                    QuietHoursDetails(prefs, onUpdate, onPick = { pickTime = it })
                }
                Divider()
                ActionTileSetting()
                Divider()
                OutlinedButton(onClick = onTest, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Icon(Icons.Rounded.Send, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Send test notification", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                TextButton(onClick = onChannels, modifier = Modifier.fillMaxWidth()) {
                    Text("Sounds & categories", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }

    pickTime?.let { which ->
        QuietTimeDialog(
            title = if (which == "start") "Quiet hours start" else "Quiet hours end",
            minuteOfDay = if (which == "start") prefs.quietStart else prefs.quietEnd,
            onDismiss = { pickTime = null },
            onPick = { m ->
                pickTime = null
                onUpdate { if (which == "start") it.copy(quietStart = m) else it.copy(quietEnd = m) }
            },
        )
    }
}

/** 1.10.0: times, days and whether Critical breaks through. */
@Composable
fun QuietHoursDetails(prefs: NotificationPrefs, onUpdate: ((NotificationPrefs) -> NotificationPrefs) -> Unit, onPick: (String) -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TimeButton("From", prefs.quietStart, Modifier.weight(1f)) { onPick("start") }
            TimeButton("Until", prefs.quietEnd, Modifier.weight(1f)) { onPick("end") }
        }
        Text("On", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            val names = java.time.format.TextStyle.NARROW
            val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
            (1..7).forEach { d ->
                val day = java.time.DayOfWeek.of(d)
                val on = d in prefs.quietDays
                androidx.compose.material3.FilterChip(
                    selected = on,
                    onClick = { onUpdate { p -> p.copy(quietDays = if (on) p.quietDays - d else p.quietDays + d) } },
                    label = { Text(day.getDisplayName(names, locale), maxLines = 1) },
                    modifier = Modifier.weight(1f).semantics { contentDescription = day.getDisplayName(java.time.format.TextStyle.FULL, locale) },
                )
            }
        }
        if (prefs.quietDays.isEmpty()) Text("Pick at least one day, or quiet hours never start.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(4.dp))
        SettingRow(
            Icons.Rounded.PriorityHigh, "Critical breaks through",
            "Error, Critical and more severe alerts still notify during quiet hours",
            checked = prefs.quietCriticalBreaksThrough, onCheckedChange = { on -> onUpdate { it.copy(quietCriticalBreaksThrough = on) } },
        )
    }
}

fun intervalLabel(minutes: Int) = if (minutes % 60 == 0) "${minutes / 60} hour" + (if (minutes > 60) "s" else "") else "$minutes min"

/** Which quick action the Quick Settings "TrueNAS action" tile opens (1.2.0). */
@Composable
private fun ActionTileSetting() {
    val context = androidx.compose.ui.platform.LocalContext.current
    var action by remember { mutableStateOf(app.truenascompanion.quick.QuickActions.tileAction(context)) }
    var open by remember { mutableStateOf(false) }
    SettingRow(Icons.Rounded.TouchApp, "Quick Settings tile", "Add \"TrueNAS action\" to Quick Settings. It opens the app and always asks before doing anything.") {
        Box {
            OutlinedButton(onClick = { open = true }, contentPadding = PaddingValues(start = 12.dp, end = 6.dp)) {
                Text(action.shortLabel, maxLines = 1, softWrap = false)
                Icon(Icons.Rounded.ExpandMore, null, Modifier.size(20.dp))
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                app.truenascompanion.quick.QuickAction.entries.forEach { a ->
                    DropdownMenuItem(text = { Text(a.longLabel) }, onClick = {
                        open = false; action = a
                        app.truenascompanion.quick.QuickActions.setTileAction(context, a)
                    })
                }
            }
        }
    }
}

@Composable
private fun Divider() {
    HorizontalDivider(Modifier.padding(vertical = 14.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
}

@Composable
fun levelColor(level: AlertLevel): Color = when (level.group) {
    SeverityGroup.CRITICAL -> LocalStatusColors.current.critical
    SeverityGroup.WARNING -> LocalStatusColors.current.warning
    SeverityGroup.INFO -> MaterialTheme.colorScheme.primary
}

@Composable
private fun LevelPicker(selected: AlertLevel, onPick: (AlertLevel) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }, contentPadding = PaddingValues(start = 12.dp, end = 6.dp)) {
            Box(Modifier.size(8.dp).background(levelColor(selected), CircleShape))
            Spacer(Modifier.width(8.dp))
            Text(selected.label, maxLines = 1, softWrap = false)
            Icon(Icons.Rounded.ExpandMore, null, Modifier.size(20.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            AlertLevel.entries.forEach { lvl ->
                DropdownMenuItem(
                    text = { Text(lvl.label, maxLines = 1) },
                    leadingIcon = { Box(Modifier.size(10.dp).background(levelColor(lvl), CircleShape)) },
                    trailingIcon = if (lvl == selected) ({ Icon(Icons.Rounded.CheckCircle, null, tint = MaterialTheme.colorScheme.primary) }) else null,
                    onClick = { open = false; onPick(lvl) },
                )
            }
        }
    }
}

@Composable
private fun Hint(icon: androidx.compose.ui.graphics.vector.ImageVector, color: Color, text: String, action: String, onAction: () -> Unit) {
    Surface(color = color.copy(alpha = 0.12f), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 14.dp, end = 8.dp, top = 12.dp, bottom = 4.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Icon(icon, null, tint = color, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f).padding(end = 6.dp))
            }
            TextButton(onClick = onAction, modifier = Modifier.align(Alignment.End)) { Text(action, maxLines = 1) }
        }
    }
}

@Composable
private fun TimeButton(label: String, minuteOfDay: Int, modifier: Modifier, onClick: () -> Unit) {
    val context = LocalContext.current
    OutlinedButton(onClick = onClick, modifier = modifier.heightIn(min = 56.dp), shape = RoundedCornerShape(16.dp), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            Text(formatTime(context, minuteOfDay), style = MaterialTheme.typography.titleMedium, maxLines = 1, softWrap = false)
        }
    }
}

fun formatTime(context: Context, minuteOfDay: Int): String {
    val cal = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, minuteOfDay / 60)
        set(Calendar.MINUTE, minuteOfDay % 60)
    }
    return android.text.format.DateFormat.getTimeFormat(context).format(cal.time)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QuietTimeDialog(title: String, minuteOfDay: Int, onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    val context = LocalContext.current
    val state = rememberTimePickerState(minuteOfDay / 60, minuteOfDay % 60, android.text.format.DateFormat.is24HourFormat(context))
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Bedtime, null) },
        title = { Text(title) },
        text = { Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { TimeInput(state) } },
        confirmButton = { GlowButton(onClick = { onPick(state.hour * 60 + state.minute) }) { Text("Set") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// ---------------------------------------------------------------------------------------------------------------------
// Alerts screen first-run card
// ---------------------------------------------------------------------------------------------------------------------

@Composable
fun PhoneAlertsPromptCard(serverName: String, onEnable: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val brand = LocalBrandColors.current
    ElevatedSection(modifier) {
        Row(verticalAlignment = Alignment.Top) {
            IconBadge(Icons.Rounded.NotificationsActive, tint = if (brand.dark) brand.accent else MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Get alerts on your phone", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Get a notification when $serverName raises a warning or critical alert. " +
                        "Your phone checks the NAS directly: no cloud service involved.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onDismiss) { Text("Not now", maxLines = 1) }
            Spacer(Modifier.width(8.dp))
            GlowButton(onClick = onEnable) { Text("Turn on", maxLines = 1) }
        }
    }
}
