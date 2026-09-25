package app.truenascompanion.notify

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationChannelGroup
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.truenascompanion.MainActivity
import app.truenascompanion.R
import app.truenascompanion.data.model.AlertItem
import app.truenascompanion.data.model.ServerConfig

/** Where a notification tap should take the user. Carried as MainActivity intent extras. */
object DeepLink {
    const val EXTRA_SERVER_ID = "app.truenascompanion.extra.SERVER_ID"
    const val EXTRA_DESTINATION = "app.truenascompanion.extra.DESTINATION"
    const val DEST_ALERTS = "alerts"
    const val DEST_SIGN_IN = "sign_in"
    const val DEST_SETTINGS = "notification_settings"
}

/** Builds and posts all app notifications (alerts, sign-in reminder, test, instant-mode service). */
class AlertNotifier(private val context: Context) {
    private val nm = NotificationManagerCompat.from(context)

    companion object {
        const val CH_CRITICAL = "alerts_critical"
        const val CH_WARNING = "alerts_warning"
        const val CH_INFO = "alerts_info"
        const val CH_CLEARED = "alerts_cleared"
        const val CH_ACCOUNT = "account"
        const val CH_SERVICE = "instant_service"
        private const val GROUP_ALERTS = "alerts"
        private const val GROUP_APP = "app"

        const val ID_ALERT = 1
        const val ID_SUMMARY = 2
        const val ID_SIGN_IN = 3
        const val ID_CLEARED = 4
        const val ID_TEST = 5
        const val ID_SERVICE = 1001

        /** Individual notifications per check; the rest are only listed in the group summary. */
        const val MAX_INDIVIDUAL = 8

        val BRAND_COLOR = 0xFF2F5BEA.toInt()

        fun alertTag(serverId: String, uuid: String) = "alert/$serverId/$uuid"
        fun groupKey(serverId: String) = "app.truenascompanion.alerts.$serverId"

        fun channelFor(level: AlertLevel) = when (level.group) {
            SeverityGroup.CRITICAL -> CH_CRITICAL
            SeverityGroup.WARNING -> CH_WARNING
            SeverityGroup.INFO -> CH_INFO
        }
    }

    fun createChannels() {
        val m = context.getSystemService(NotificationManager::class.java) ?: return
        m.createNotificationChannelGroups(listOf(
            NotificationChannelGroup(GROUP_ALERTS, "TrueNAS alerts"),
            NotificationChannelGroup(GROUP_APP, "App"),
        ))
        fun ch(id: String, name: String, importance: Int, desc: String, group: String) =
            NotificationChannel(id, name, importance).apply {
                description = desc
                this.group = group
                lightColor = BRAND_COLOR
                enableLights(importance >= NotificationManager.IMPORTANCE_DEFAULT)
            }
        m.createNotificationChannels(listOf(
            ch(CH_CRITICAL, "Critical & errors", NotificationManager.IMPORTANCE_HIGH, "Error, Critical, Alert and Emergency alerts", GROUP_ALERTS),
            ch(CH_WARNING, "Warnings", NotificationManager.IMPORTANCE_DEFAULT, "Warning alerts", GROUP_ALERTS),
            ch(CH_INFO, "Info & notices", NotificationManager.IMPORTANCE_LOW, "Info and Notice alerts", GROUP_ALERTS),
            ch(CH_CLEARED, "Cleared alerts", NotificationManager.IMPORTANCE_LOW, "An alert went away on the NAS", GROUP_ALERTS),
            ch(CH_ACCOUNT, "Sign-in reminders", NotificationManager.IMPORTANCE_DEFAULT, "The saved session expired and alerts are paused", GROUP_APP),
            ch(CH_SERVICE, "Instant alerts connection", NotificationManager.IMPORTANCE_MIN, "Silent notification shown while instant alerts keep a live connection", GROUP_APP),
        ))
    }

    fun canPost(): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false
        return nm.areNotificationsEnabled()
    }

    // --- intents ---

    fun openAppIntent(serverId: String?, destination: String, requestKey: String): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(DeepLink.EXTRA_DESTINATION, destination)
            serverId?.let { putExtra(DeepLink.EXTRA_SERVER_ID, it) }
        }
        return PendingIntent.getActivity(context, requestKey.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun dismissIntent(serverId: String, uuid: String): PendingIntent {
        val intent = Intent(context, AlertActionReceiver::class.java).apply {
            action = AlertActionReceiver.ACTION_DISMISS
            putExtra(AlertActionReceiver.EXTRA_SERVER_ID, serverId)
            putExtra(AlertActionReceiver.EXTRA_UUID, uuid)
        }
        return PendingIntent.getBroadcast(context, "dismiss/$serverId/$uuid".hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun base(channel: String) = NotificationCompat.Builder(context, channel)
        .setSmallIcon(R.drawable.ic_stat_notify)
        .setColor(BRAND_COLOR)
        .setAutoCancel(true)

    // --- alerts ---

    fun buildAlert(server: ServerConfig, alert: AlertItem, title: String, silent: Boolean = false): NotificationCompat.Builder {
        val level = AlertLevel.parse(alert.level)
        val tag = alertTag(server.id, alert.uuid)
        val open = openAppIntent(server.id, DeepLink.DEST_ALERTS, tag)
        return base(channelFor(level))
            .setContentTitle(title)
            .setContentText(alert.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(alert.text))
            .setSubText("${server.name} · ${level.label}")
            .setCategory(if (level.group == SeverityGroup.CRITICAL) NotificationCompat.CATEGORY_ERROR else NotificationCompat.CATEGORY_STATUS)
            .setPriority(when (level.group) {
                SeverityGroup.CRITICAL -> NotificationCompat.PRIORITY_HIGH
                SeverityGroup.WARNING -> NotificationCompat.PRIORITY_DEFAULT
                SeverityGroup.INFO -> NotificationCompat.PRIORITY_LOW
            })
            .apply { alert.datetimeMillis?.let { setWhen(it); setShowWhen(true) } }
            .setGroup(groupKey(server.id))
            .setContentIntent(open)
            .setSilent(silent)
            .addAction(0, "Dismiss", dismissIntent(server.id, alert.uuid))
            .addAction(0, "Open", open)
    }

    @SuppressLint("MissingPermission") // canPost() checks POST_NOTIFICATIONS
    fun postAlerts(server: ServerConfig, alerts: List<AlertItem>, titles: Map<String, String>) {
        if (alerts.isEmpty() || !canPost()) return
        alerts.take(MAX_INDIVIDUAL).forEachIndexed { i, a ->
            // Only the first (most severe) one makes a sound; the rest arrive quietly.
            nm.notify(alertTag(server.id, a.uuid), ID_ALERT, buildAlert(server, a, titles.getValue(a.uuid), silent = i > 0).build())
        }
        updateSummary(server, extra = alerts.drop(MAX_INDIVIDUAL).map { titles.getValue(it.uuid) })
    }

    @SuppressLint("MissingPermission")
    fun postCleared(server: ServerConfig, cleared: List<SeenAlert>) {
        if (cleared.isEmpty() || !canPost()) return
        val title = if (cleared.size == 1) "Cleared: ${cleared[0].title}" else "${cleared.size} alerts cleared"
        val text = if (cleared.size == 1) "This alert is no longer active on ${server.name}." else cleared.joinToString(", ") { it.title }
        val n = base(CH_CLEARED)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSubText(server.name)
            .setContentIntent(openAppIntent(server.id, DeepLink.DEST_ALERTS, "cleared/${server.id}"))
            .build()
        nm.notify("cleared/${server.id}", ID_CLEARED, n)
    }

    /** Removes notifications for alerts that are gone or were dismissed. */
    fun withdraw(server: ServerConfig, uuids: Collection<String>) {
        if (uuids.isEmpty()) return
        uuids.forEach { nm.cancel(alertTag(server.id, it), ID_ALERT) }
        updateSummary(server)
    }

    fun cancelAlert(serverId: String, uuid: String) = nm.cancel(alertTag(serverId, uuid), ID_ALERT)

    /** InboxStyle summary so several alerts collapse into one expandable group. */
    @SuppressLint("MissingPermission")
    fun updateSummary(server: ServerConfig, extra: List<String> = emptyList()) {
        val prefix = "alert/${server.id}/"
        val active = runCatching { nm.activeNotifications }.getOrDefault(emptyList())
            .filter { it.tag?.startsWith(prefix) == true }
        val lines = active.mapNotNull { it.notification.extras.getCharSequence(NotificationCompat.EXTRA_TITLE)?.toString() } + extra
        val summaryTag = "summary/${server.id}"
        if (lines.size < 2 || !canPost()) {
            nm.cancel(summaryTag, ID_SUMMARY)
            return
        }
        val channel = active.map { it.notification.channelId }.let { ids ->
            listOf(CH_CRITICAL, CH_WARNING, CH_INFO).firstOrNull { it in ids } ?: CH_WARNING
        }
        val style = NotificationCompat.InboxStyle()
            .setBigContentTitle("${lines.size} TrueNAS alerts")
            .setSummaryText(server.name)
        lines.take(6).forEach { style.addLine(it) }
        if (lines.size > 6) style.addLine("+${lines.size - 6} more")
        val n = base(channel)
            .setContentTitle("${lines.size} TrueNAS alerts")
            .setContentText(lines.joinToString(", "))
            .setSubText(server.name)
            .setStyle(style)
            .setGroup(groupKey(server.id))
            .setGroupSummary(true)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .setContentIntent(openAppIntent(server.id, DeepLink.DEST_ALERTS, summaryTag))
            .build()
        nm.notify(summaryTag, ID_SUMMARY, n)
    }

    // --- sign-in reminder ---

    @SuppressLint("MissingPermission")
    fun postSignIn(server: ServerConfig, apiKey: Boolean) {
        if (!canPost()) return
        val text = if (apiKey) "The API key for ${server.name} was rejected. Open the app to update it."
        else "Your session with ${server.name} expired. Open the app and sign in to keep receiving alerts."
        val n = base(CH_ACCOUNT)
            .setContentTitle("Sign in to keep receiving alerts")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSubText(server.name)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent(server.id, DeepLink.DEST_SIGN_IN, "signin/${server.id}"))
            .addAction(0, "Sign in", openAppIntent(server.id, DeepLink.DEST_SIGN_IN, "signin-action/${server.id}"))
            .build()
        nm.notify("signin/${server.id}", ID_SIGN_IN, n)
    }

    fun cancelSignIn(serverId: String) = nm.cancel("signin/$serverId", ID_SIGN_IN)

    // --- test ---

    @SuppressLint("MissingPermission")
    fun postTest(server: ServerConfig?): Boolean {
        if (!canPost()) return false
        val name = server?.name ?: "your NAS"
        val text = "Notifications work. New TrueNAS alerts from $name will look like this."
        val n = base(CH_WARNING)
            .setContentTitle("Test notification")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSubText(server?.let { "${it.name} · Test" } ?: "Test")
            .setContentIntent(openAppIntent(server?.id, DeepLink.DEST_ALERTS, "test"))
            .build()
        nm.notify("test", ID_TEST, n)
        return true
    }

    // --- instant mode foreground service ---

    fun buildService(status: String, stopIntent: PendingIntent) = base(CH_SERVICE)
        .setContentTitle("Instant alerts on")
        .setContentText(status)
        .setOngoing(true)
        .setAutoCancel(false)
        .setSilent(true)
        .setShowWhen(false)
        .setPriority(NotificationCompat.PRIORITY_MIN)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_DEFERRED)
        .setContentIntent(openAppIntent(null, DeepLink.DEST_SETTINGS, "service"))
        .addAction(0, "Turn off", stopIntent)
        .build()
}
