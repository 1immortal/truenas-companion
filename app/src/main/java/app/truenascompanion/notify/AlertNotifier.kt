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
    /** 1.2.0: optional argument of the destination (pool, disk, app, dataset or certificate name). */
    const val EXTRA_ARG = "app.truenascompanion.extra.ARG"
    // Alert deep links (1.2.0)
    const val DEST_POOL = "pool"
    const val DEST_DISK = "disk"
    const val DEST_APP = "app"
    const val DEST_APPS = "apps"
    const val DEST_DATASET = "dataset"
    const val DEST_SNAPSHOTS = "snapshots"
    const val DEST_UPDATE = "update"
    const val DEST_CERTIFICATE = "certificate"
    // Quick actions (1.2.0): app shortcuts and the Quick Settings action tile
    const val DEST_SHELL = "quick_shell"
    const val DEST_RESTART_APP = "quick_restart_app"
    const val DEST_SCRUB_POOL = "quick_scrub_pool"
    const val DEST_DASHBOARD = "dashboard"
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
        const val ID_CERT = 6
        const val ID_SERVICE = 1001

        /** Individual notifications per check; the rest are only listed in the group summary. */
        const val MAX_INDIVIDUAL = 8

        val BRAND_COLOR = 0xFF2F5BEA.toInt()

        // Notification extras describing the alerts a notification covers (grouping / withdrawal, 1.2.0).
        private const val X_SERVER_NAME = "app.truenascompanion.server_name"
        private const val X_TITLE = "app.truenascompanion.title"
        private const val X_KLASS = "app.truenascompanion.klass"
        private const val X_LEVEL = "app.truenascompanion.level"
        private const val X_UUIDS = "app.truenascompanion.uuids"
        private const val X_TEXTS = "app.truenascompanion.texts"
        private const val X_LEVELS = "app.truenascompanion.levels"

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

    fun openAppIntent(serverId: String?, destination: String, requestKey: String, arg: String? = null): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(DeepLink.EXTRA_DESTINATION, destination)
            serverId?.let { putExtra(DeepLink.EXTRA_SERVER_ID, it) }
            arg?.let { putExtra(DeepLink.EXTRA_ARG, it) }
        }
        return PendingIntent.getActivity(context, "$requestKey/$destination".hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun dismissIntent(serverId: String, uuids: List<String>): PendingIntent {
        val intent = Intent(context, AlertActionReceiver::class.java).apply {
            action = AlertActionReceiver.ACTION_DISMISS
            putExtra(AlertActionReceiver.EXTRA_SERVER_ID, serverId)
            putExtra(AlertActionReceiver.EXTRA_UUID, uuids.first())
            putExtra(AlertActionReceiver.EXTRA_UUIDS, uuids.toTypedArray())
        }
        return PendingIntent.getBroadcast(context, "dismiss/$serverId/${uuids.joinToString(",")}".hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /** Opens the small snooze picker (1 h / 8 h / 1 day / 1 week); snoozes are kept on the phone. */
    private fun snoozeIntent(serverId: String, serverName: String, uuids: List<String>, tag: String): PendingIntent {
        val intent = Intent(context, SnoozeActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_HISTORY or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
            putExtra(SnoozeActivity.EXTRA_SERVER_ID, serverId)
            putExtra(SnoozeActivity.EXTRA_UUIDS, uuids.toTypedArray())
            putExtra(SnoozeActivity.EXTRA_TAG, tag)
            putExtra(SnoozeActivity.EXTRA_SERVER_NAME, serverName)
        }
        return PendingIntent.getActivity(context, "snooze/$tag".hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun base(channel: String) = NotificationCompat.Builder(context, channel)
        .setSmallIcon(R.drawable.ic_stat_notify)
        .setColor(BRAND_COLOR)
        .setAutoCancel(true)

    // --- alerts ---

    fun buildAlert(server: ServerConfig, alert: AlertItem, title: String, silent: Boolean = false): NotificationCompat.Builder =
        buildGroup(server.id, server.name, listOf(alert), title, alertTag(server.id, alert.uuid), silent)

    /**
     * One notification for [items] of the same alert class (1.2.0): a single alert, or repeated ones with a count.
     * The alert data is kept in the notification extras so later checks can merge into it or withdraw single alerts.
     */
    fun buildGroup(serverId: String, serverName: String, items: List<AlertItem>, title: String, tag: String, silent: Boolean = false): NotificationCompat.Builder {
        val top = items.maxBy { AlertLevel.parse(it.level).rank }
        val level = AlertLevel.parse(top.level)
        val target = groupTarget(items)
        val open = openAppIntent(serverId, target.destination, tag, target.arg)
        val count = items.size
        val uuids = items.map { it.uuid }
        val b = base(channelFor(level))
            .setContentTitle(if (count > 1) "$title · $count" else title)
            .setContentText(if (count > 1) items.first().text else top.text)
            .setSubText("$serverName · ${level.label}")
            .setCategory(if (level.group == SeverityGroup.CRITICAL) NotificationCompat.CATEGORY_ERROR else NotificationCompat.CATEGORY_STATUS)
            .setPriority(when (level.group) {
                SeverityGroup.CRITICAL -> NotificationCompat.PRIORITY_HIGH
                SeverityGroup.WARNING -> NotificationCompat.PRIORITY_DEFAULT
                SeverityGroup.INFO -> NotificationCompat.PRIORITY_LOW
            })
            .setGroup(groupKey(serverId))
            .setContentIntent(open)
            .setSilent(silent)
            .setOnlyAlertOnce(true)
            .setNumber(count)
            .addAction(0, if (count > 1) "Dismiss all" else "Dismiss", dismissIntent(serverId, uuids))
            .addAction(0, "Snooze", snoozeIntent(serverId, serverName, uuids, tag))
            .addAction(0, "Open", open)
            .addExtras(android.os.Bundle().apply {
                putString(X_SERVER_NAME, serverName)
                putString(X_TITLE, title)
                putString(X_KLASS, top.klass)
                putString(X_LEVEL, top.level)
                putStringArray(X_UUIDS, uuids.toTypedArray())
                putStringArray(X_TEXTS, items.map { it.text }.toTypedArray())
                putStringArray(X_LEVELS, items.map { it.level }.toTypedArray())
            })
        if (count > 1) {
            val style = NotificationCompat.InboxStyle().setBigContentTitle("$title · $count").setSummaryText(serverName)
            items.take(6).forEach { style.addLine(it.text) }
            if (count > 6) style.addLine("+${count - 6} more")
            b.setStyle(style)
        } else {
            b.setStyle(NotificationCompat.BigTextStyle().bigText(top.text))
            top.datetimeMillis?.let { b.setWhen(it); b.setShowWhen(true) }
        }
        return b
    }

    /** Same target for every alert in the group, or the common screen (disks, apps…), else Alerts. */
    private fun groupTarget(items: List<AlertItem>): AlertTarget {
        val targets = items.map { AlertTarget.of(it) }.distinct()
        return when {
            targets.size == 1 -> targets[0]
            targets.all { it is AlertTarget.Disk } -> AlertTarget.Disk(null)
            targets.all { it is AlertTarget.App || it is AlertTarget.Apps } -> AlertTarget.Apps
            targets.all { it is AlertTarget.Dataset || it is AlertTarget.Snapshots } -> AlertTarget.Dataset(null)
            targets.all { it is AlertTarget.Certificate } -> AlertTarget.Certificate(null)
            else -> AlertTarget.Alerts
        }
    }

    private data class ShownGroup(val tag: String, val title: String, val serverName: String, val klass: String?, val items: List<AlertItem>)

    /** Alert notifications of [serverId] currently in the shade, with the alerts they cover. */
    private fun shown(serverId: String): List<ShownGroup> {
        val prefix = "alert/$serverId/"
        return runCatching { nm.activeNotifications }.getOrDefault(emptyList()).mapNotNull { sbn ->
            val tag = sbn.tag ?: return@mapNotNull null
            if (!tag.startsWith(prefix) || sbn.id != ID_ALERT) return@mapNotNull null
            val x = sbn.notification.extras
            val uuids = x.getStringArray(X_UUIDS) ?: return@mapNotNull null
            val texts = x.getStringArray(X_TEXTS) ?: emptyArray()
            val levels = x.getStringArray(X_LEVELS) ?: emptyArray()
            val klass = x.getString(X_KLASS)
            ShownGroup(
                tag, x.getString(X_TITLE) ?: "TrueNAS alert", x.getString(X_SERVER_NAME) ?: "TrueNAS", klass,
                uuids.mapIndexed { i, u -> AlertItem(u, levels.getOrNull(i) ?: "WARNING", texts.getOrNull(i) ?: "", klass, null, false, false) },
            )
        }
    }

    @SuppressLint("MissingPermission") // canPost() checks POST_NOTIFICATIONS
    fun postAlerts(server: ServerConfig, alerts: List<AlertItem>, titles: Map<String, String>) {
        if (alerts.isEmpty() || !canPost()) return
        val shown = shown(server.id)
        val known = shown.flatMap { it.items }.associateBy { it.uuid } + alerts.associateBy { it.uuid }
        val posts = AlertGrouping.plan(alerts.map { it.uuid to it.klass }, shown.map { AlertGrouping.Shown(it.tag, it.klass, it.items.map { i -> i.uuid }) })
            .sortedByDescending { p -> p.uuids.maxOf { AlertLevel.parse(known[it]?.level).rank } }
        posts.take(MAX_INDIVIDUAL).forEachIndexed { i, p ->
            val items = p.uuids.mapNotNull { known[it] }
            if (items.isEmpty()) return@forEachIndexed
            val tag = if (p.grouped) AlertGrouping.groupTag(server.id, p.klass!!) else alertTag(server.id, items[0].uuid)
            p.replaces.filter { it != tag }.forEach { nm.cancel(it, ID_ALERT) }
            val title = items.firstNotNullOfOrNull { titles[it.uuid] } ?: shown.firstOrNull { it.klass == p.klass }?.title ?: "TrueNAS alert"
            // Only the first (most severe) one makes a sound; the rest arrive quietly.
            nm.notify(tag, ID_ALERT, buildGroup(server.id, server.name, items, title, tag, silent = i > 0).build())
        }
        updateSummary(server, extra = posts.drop(MAX_INDIVIDUAL).mapNotNull { p -> p.uuids.firstNotNullOfOrNull { titles[it] } })
    }

    /** Certificate expiry warning (1.2.0); opens the Certificates screen. */
    @SuppressLint("MissingPermission")
    fun postCertificate(server: ServerConfig, w: CertExpiry.Warning) {
        if (!canPost()) return
        val (title, text) = CertExpiry.text(w)
        val tag = "cert/${server.id}/${w.cert.id}"
        val n = base(if (w.expired) CH_CRITICAL else CH_WARNING)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSubText("${server.name} · Certificates")
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(openAppIntent(server.id, DeepLink.DEST_CERTIFICATE, tag, w.cert.name))
            .build()
        nm.notify(tag, ID_CERT, n)
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

    /** Removes notifications for alerts that are gone, dismissed or snoozed; grouped ones shrink (or go away). */
    fun withdraw(server: ServerConfig, uuids: Collection<String>) {
        if (uuids.isEmpty()) return
        removeFromShade(server.id, uuids.toSet())
        updateSummary(server)
    }

    /** Dismiss action / snooze: takes the alerts out of their notifications without needing the server config. */
    @SuppressLint("MissingPermission")
    fun removeFromShade(serverId: String, uuids: Set<String>) {
        uuids.forEach { nm.cancel(alertTag(serverId, it), ID_ALERT) }
        shown(serverId).filter { g -> g.items.any { it.uuid in uuids } }.forEach { g ->
            val rest = g.items.filter { it.uuid !in uuids }
            if (rest.isEmpty() || !canPost()) nm.cancel(g.tag, ID_ALERT)
            else nm.notify(g.tag, ID_ALERT, buildGroup(serverId, g.serverName, rest, g.title, g.tag, silent = true).build())
        }
    }

    fun cancelAlert(serverId: String, uuid: String) = removeFromShade(serverId, setOf(uuid))

    /** InboxStyle summary so several alerts collapse into one expandable group. */
    @SuppressLint("MissingPermission")
    fun updateSummary(server: ServerConfig, extra: List<String> = emptyList()) = updateSummary(server.id, server.name, extra)

    @SuppressLint("MissingPermission")
    fun updateSummary(serverId: String, serverName: String, extra: List<String> = emptyList()) {
        val prefix = "alert/$serverId/"
        val active = runCatching { nm.activeNotifications }.getOrDefault(emptyList())
            .filter { it.tag?.startsWith(prefix) == true }
        val lines = active.mapNotNull { it.notification.extras.getCharSequence(NotificationCompat.EXTRA_TITLE)?.toString() } + extra
        val summaryTag = "summary/$serverId"
        if (lines.size < 2 || !canPost()) {
            nm.cancel(summaryTag, ID_SUMMARY)
            return
        }
        val channel = active.map { it.notification.channelId }.let { ids ->
            listOf(CH_CRITICAL, CH_WARNING, CH_INFO).firstOrNull { it in ids } ?: CH_WARNING
        }
        val style = NotificationCompat.InboxStyle()
            .setBigContentTitle("${lines.size} TrueNAS alerts")
            .setSummaryText(serverName)
        lines.take(6).forEach { style.addLine(it) }
        if (lines.size > 6) style.addLine("+${lines.size - 6} more")
        val n = base(channel)
            .setContentTitle("${lines.size} TrueNAS alerts")
            .setContentText(lines.joinToString(", "))
            .setSubText(serverName)
            .setStyle(style)
            .setGroup(groupKey(serverId))
            .setGroupSummary(true)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .setContentIntent(openAppIntent(serverId, DeepLink.DEST_ALERTS, summaryTag))
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
