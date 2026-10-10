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
    // 1.3.0: guided disk replacement for a pool (from VolumeStatus alerts) and finished resilvers
    const val DEST_REPLACE_DISK = "replace_disk"
    // Quick actions (1.2.0): app shortcuts and the Quick Settings action tile
    const val DEST_SHELL = "quick_shell"
    const val DEST_RESTART_APP = "quick_restart_app"
    const val DEST_SCRUB_POOL = "quick_scrub_pool"
    const val DEST_DASHBOARD = "dashboard"
    // 1.5.0: Storage › Protection › Cloud sync (finished manual runs, CloudSyncTaskFailed alerts)
    const val DEST_CLOUD_SYNC = "cloud_sync"
    // 1.6.0: Storage › Protection › Replication (finished manual runs, ReplicationFailed / ReplicationSuccess alerts)
    const val DEST_REPLICATION = "replication"
    // 1.10.0: phone alert rules editor, Updates page and Running jobs
    const val DEST_RULES = "alert_rules"
    const val DEST_TASKS = "tasks"
}

/** Builds and posts all app notifications (alerts, sign-in reminder, test, instant-mode service). */
class AlertNotifier(private val context: Context) {
    private val nm = NotificationManagerCompat.from(context)

    companion object {
        /** Lock-screen (public) title: severity and count only, no NAS name or alert text (pure, unit tested). */
        fun publicAlertTitle(level: AlertLevel, count: Int): String {
            val sev = when (level.group) { SeverityGroup.CRITICAL -> "critical"; SeverityGroup.WARNING -> "warning"; SeverityGroup.INFO -> "info" }
            return "TrueNAS: $count $sev alert" + if (count == 1) "" else "s"
        }

        const val CH_CRITICAL = "alerts_critical"
        const val CH_WARNING = "alerts_warning"
        const val CH_INFO = "alerts_info"
        const val CH_CLEARED = "alerts_cleared"
        const val CH_ACCOUNT = "account"
        const val CH_SERVICE = "instant_service"
        const val CH_TRANSFER = "file_transfers"
        /** 1.10.0: ongoing progress (scrub, resilver, replication, cloud sync, TrueNAS update). */
        const val CH_PROGRESS = "progress"
        private const val GROUP_ALERTS = "alerts"
        private const val GROUP_APP = "app"

        const val ID_ALERT = 1
        const val ID_TRANSFER = 0x7f0010
        const val ID_SUMMARY = 2
        const val ID_SIGN_IN = 3
        const val ID_CLEARED = 4
        const val ID_TEST = 5
        const val ID_CERT = 6
        const val ID_RESILVER = 7
        const val ID_CLOUD_SYNC = 8
        const val ID_REPLICATION = 9
        const val ID_RULE = 10
        const val ID_PROGRESS = 11
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
            ch(CH_CRITICAL, "Critical", NotificationManager.IMPORTANCE_HIGH, "Error, Critical, Alert and Emergency alerts", GROUP_ALERTS),
            ch(CH_WARNING, "Warnings", NotificationManager.IMPORTANCE_DEFAULT, "Warning alerts", GROUP_ALERTS),
            ch(CH_INFO, "Info", NotificationManager.IMPORTANCE_LOW, "Info and Notice alerts", GROUP_ALERTS),
            // 1.10.0: same id as before (so the user's sound/vibration choices are kept), now named "Recovered" and
            // also used when a phone alert rule's condition ends.
            ch(CH_CLEARED, "Recovered", NotificationManager.IMPORTANCE_LOW, "An alert went away on the NAS, or a phone alert rule recovered", GROUP_ALERTS),
            ch(CH_PROGRESS, "Progress", NotificationManager.IMPORTANCE_LOW, "Ongoing scrubs, resilvers, replications, cloud syncs and TrueNAS updates", GROUP_ALERTS),
            ch(CH_ACCOUNT, "Sign-in reminders", NotificationManager.IMPORTANCE_DEFAULT, "The saved session expired and alerts are paused", GROUP_APP),
            ch(CH_SERVICE, "Instant alerts connection", NotificationManager.IMPORTANCE_MIN, "Silent notification shown while instant alerts keep a live connection", GROUP_APP),
            ch(CH_TRANSFER, "File transfers", NotificationManager.IMPORTANCE_LOW, "Shown while a file is uploaded to or downloaded from the NAS", GROUP_APP),
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
            DeepLinkGuard.sign(context, this)
        }
        return PendingIntent.getActivity(context, "$requestKey/$destination".hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /**
     * 1.7.1 (security M-3): while the app lock is on (or its setting isn't loaded yet), alerts are only dismissed from
     * inside the app, behind the lock; the notification has no Dismiss action then.
     */
    @Volatile var appLockOn: Boolean = true

    /** Dismiss needs the phone unlocked: Android 12+ marks the action, older versions go through [AlertDismissActivity]. */
    private fun dismissAction(serverId: String, uuids: List<String>, label: String): NotificationCompat.Action? {
        if (appLockOn) return null
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            return NotificationCompat.Action.Builder(0, label, dismissIntent(serverId, uuids)).setAuthenticationRequired(true).build()
        }
        val intent = Intent(context, AlertDismissActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_HISTORY or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
            putExtra(AlertActionReceiver.EXTRA_SERVER_ID, serverId)
            putExtra(AlertActionReceiver.EXTRA_UUIDS, uuids.toTypedArray())
        }
        val pi = PendingIntent.getActivity(context, "dismissui/$serverId/${uuids.joinToString(",")}".hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Action.Builder(0, label, pi).build()
    }

    /**
     * 1.7.1 (security M-3): what the lock screen shows when the user hides sensitive notification content: the app and
     * the severity, never the alert text, server or pool names.
     */
    private fun publicVersion(channel: String, title: String): android.app.Notification =
        NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setColor(BRAND_COLOR)
            .setContentTitle(title)
            .setContentText("Unlock to see details")
            .build()

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
        .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
        .setPublicVersion(publicVersion(channel, "YTN"))

    // --- alerts ---

    /** Lock-screen title of a hidden alert, e.g. "TrueNAS: 1 critical alert" (pure, unit tested). */

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
            .setPublicVersion(publicVersion(channelFor(level), publicAlertTitle(level, count)))
        dismissAction(serverId, uuids, if (count > 1) "Dismiss all" else "Dismiss")?.let { b.addAction(it) }
        b.addAction(0, "Snooze", snoozeIntent(serverId, serverName, uuids, tag))
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

    /** 1.3.0: a disk replacement's resilver finished (or the pool reports a problem afterwards). */
    @SuppressLint("MissingPermission") // canPost() checks POST_NOTIFICATIONS
    fun postResilverDone(server: ServerConfig, poolName: String, healthy: Boolean, errors: Long?) {
        if (!canPost()) return
        val tag = "resilver/${server.id}/$poolName"
        val title = if (healthy) "Disk replacement finished" else "Resilver finished on $poolName"
        val text = when {
            healthy -> "Pool $poolName finished resilvering and is healthy again. You can remove the old disk."
            (errors ?: 0) > 0 -> "Pool $poolName finished resilvering with $errors errors. Check the pool."
            else -> "Pool $poolName finished resilvering but isn't healthy yet. Check the pool."
        }
        val n = base(if (healthy) CH_INFO else CH_WARNING)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSubText("${server.name} · Storage")
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setContentIntent(openAppIntent(server.id, DeepLink.DEST_POOL, tag, poolName))
            .build()
        nm.notify(tag, ID_RESILVER, n)
    }

    /** 1.5.0: a cloud sync run started from the app finished (the user asked to be notified). */
    @SuppressLint("MissingPermission") // canPost() checks POST_NOTIFICATIONS
    fun postCloudSyncDone(server: ServerConfig, taskId: Int, taskName: String, dryRun: Boolean, state: app.truenascompanion.data.model.JobState, error: String?) {
        if (!canPost()) return
        val tag = "cloudsync/${server.id}/$taskId"
        val ok = state == app.truenascompanion.data.model.JobState.SUCCESS
        val what = if (dryRun) "Dry run of $taskName" else taskName
        val title = when {
            ok -> "$what finished"
            state == app.truenascompanion.data.model.JobState.ABORTED -> "$what was stopped"
            else -> "$what failed"
        }
        val text = when {
            ok && dryRun -> "Nothing was changed. Open the task's log to see what a real run would do."
            ok -> "The cloud sync finished successfully."
            state == app.truenascompanion.data.model.JobState.ABORTED -> "The run was aborted before it finished."
            else -> error?.lineSequence()?.firstOrNull { it.isNotBlank() }?.take(300) ?: "Open the task's log in the app for details."
        }
        val n = base(if (ok || state == app.truenascompanion.data.model.JobState.ABORTED) CH_INFO else CH_WARNING)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSubText("${server.name} · Cloud sync")
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setContentIntent(openAppIntent(server.id, DeepLink.DEST_CLOUD_SYNC, tag, taskId.toString()))
            .build()
        nm.notify(tag, ID_CLOUD_SYNC, n)
    }

    /** 1.6.0: a replication started from the app finished (the user asked to be notified). [taskId] -1: a one-time run. */
    @SuppressLint("MissingPermission") // canPost() checks POST_NOTIFICATIONS
    fun postReplicationDone(server: ServerConfig, taskId: Int, taskName: String, state: app.truenascompanion.data.model.JobState, error: String?) {
        if (!canPost()) return
        val tag = "replication/${server.id}/$taskId"
        val ok = state == app.truenascompanion.data.model.JobState.SUCCESS
        val title = when {
            ok -> "$taskName finished"
            state == app.truenascompanion.data.model.JobState.ABORTED -> "$taskName was stopped"
            else -> "$taskName failed"
        }
        val text = when {
            ok -> "The snapshots were replicated."
            state == app.truenascompanion.data.model.JobState.ABORTED -> "The replication was stopped before it finished."
            else -> error?.lineSequence()?.firstOrNull { it.isNotBlank() }?.take(300) ?: "Open the task's log in the app for details."
        }
        val n = base(if (ok || state == app.truenascompanion.data.model.JobState.ABORTED) CH_INFO else CH_WARNING)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSubText("${server.name} · Replication")
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setContentIntent(openAppIntent(server.id, DeepLink.DEST_REPLICATION, tag, taskId.toString()))
            .build()
        nm.notify(tag, ID_REPLICATION, n)
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

    // --- 1.10.0: phone alert rules ---

    fun ruleTag(serverId: String, key: String) = "rule/$serverId/$key"

    /** Lock-screen title of a rule notification: severity only, never the NAS, pool or disk names (1.7.1 rules). */
    fun publicRuleTitle(severity: app.truenascompanion.notify.rules.RuleSeverity) = "YTN: ${severity.label.lowercase()} (phone rule)"

    fun buildRule(server: ServerConfig, e: app.truenascompanion.notify.rules.RuleEvent): NotificationCompat.Builder {
        val level = e.rule.severity.level
        val channel = channelFor(level)
        val tag = ruleTag(server.id, e.key)
        val open = openAppIntent(server.id, ruleDestination(e.rule.kind), tag, e.subject.takeIf { it.isNotEmpty() && e.rule.kind.opensSubject() })
        return base(channel)
            .setContentTitle(app.truenascompanion.notify.rules.RuleEngine.title(e))
            .setContentText(e.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(e.text))
            .setSubText("${server.name} · ${e.rule.severity.label} · Phone rule")
            .setCategory(if (level.group == SeverityGroup.CRITICAL) NotificationCompat.CATEGORY_ERROR else NotificationCompat.CATEGORY_STATUS)
            .setPriority(if (level.group == SeverityGroup.CRITICAL) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, "Edit rule", openAppIntent(server.id, DeepLink.DEST_RULES, "$tag/rules"))
            .setPublicVersion(publicVersion(channel, publicRuleTitle(e.rule.severity)))
    }

    @SuppressLint("MissingPermission")
    fun postRule(server: ServerConfig, e: app.truenascompanion.notify.rules.RuleEvent) {
        if (!canPost()) return
        nm.notify(ruleTag(server.id, e.key), ID_RULE, buildRule(server, e).build())
    }

    /** The rule's condition ended: the notification turns into a quiet "Recovered" one. */
    @SuppressLint("MissingPermission")
    fun postRuleRecovered(server: ServerConfig, e: app.truenascompanion.notify.rules.RuleEvent) {
        if (!canPost()) return
        val tag = ruleTag(server.id, e.key)
        val text = "Back to normal: ${e.rule.summary().replaceFirstChar { it.lowercase() }} is no longer true."
        val n = base(CH_CLEARED)
            .setContentTitle("Recovered: " + app.truenascompanion.notify.rules.RuleEngine.title(e))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSubText("${server.name} · Phone rule")
            .setContentIntent(openAppIntent(server.id, DeepLink.DEST_DASHBOARD, "$tag/recovered"))
            .setPublicVersion(publicVersion(CH_CLEARED, "YTN: recovered (phone rule)"))
            .build()
        nm.notify(tag, ID_RULE, n)
    }

    fun cancelRule(serverId: String, key: String) = nm.cancel(ruleTag(serverId, key), ID_RULE)

    /** A deleted rule: removes all its notifications (any subject). */
    fun cancelRuleAll(serverId: String, ruleId: String) {
        val prefix = ruleTag(serverId, "$ruleId|")
        runCatching { nm.activeNotifications }.getOrDefault(emptyList())
            .filter { it.id == ID_RULE && it.tag?.startsWith(prefix) == true }.forEach { nm.cancel(it.tag, ID_RULE) }
    }

    private fun ruleDestination(kind: app.truenascompanion.notify.rules.RuleKind): String = when (kind) {
        app.truenascompanion.notify.rules.RuleKind.POOL_USAGE, app.truenascompanion.notify.rules.RuleKind.SCRUB_AGE -> DeepLink.DEST_POOL
        app.truenascompanion.notify.rules.RuleKind.DISK_TEMP -> DeepLink.DEST_DISK
        app.truenascompanion.notify.rules.RuleKind.APP_NOT_RUNNING -> DeepLink.DEST_APP
        app.truenascompanion.notify.rules.RuleKind.BACKUP_FAILED, app.truenascompanion.notify.rules.RuleKind.BACKUP_STALE -> DeepLink.DEST_REPLICATION
        app.truenascompanion.notify.rules.RuleKind.CERT_EXPIRY -> DeepLink.DEST_CERTIFICATE
        else -> DeepLink.DEST_DASHBOARD
    }

    private fun app.truenascompanion.notify.rules.RuleKind.opensSubject() = this == app.truenascompanion.notify.rules.RuleKind.POOL_USAGE ||
        this == app.truenascompanion.notify.rules.RuleKind.SCRUB_AGE || this == app.truenascompanion.notify.rules.RuleKind.DISK_TEMP ||
        this == app.truenascompanion.notify.rules.RuleKind.APP_NOT_RUNNING || this == app.truenascompanion.notify.rules.RuleKind.CERT_EXPIRY

    // --- 1.10.0: live progress ---

    fun progressTag(serverId: String, key: String) = "progress/$serverId/$key"

    /**
     * An ongoing, silent progress notification. On Android 16+ it asks to be promoted to a Live Update (status-bar
     * chip and lock screen) with the platform progress style; older versions show a normal progress bar. Not a
     * foreground service: it's posted by checks that run anyway (see [ProgressTracker]).
     */
    fun buildProgress(server: ServerConfig, p: ProgressItem): NotificationCompat.Builder {
        val tag = progressTag(server.id, p.key)
        val pct = p.percent?.coerceIn(0, 100)
        val b = base(CH_PROGRESS)
            .setContentTitle(p.title)
            .setContentText(listOfNotNull(pct?.let { "$it%" }, p.detail).joinToString(" · ").ifEmpty { "Running…" })
            .setSubText("${server.name} · ${p.kind.label}")
            .setOngoing(true)
            .setAutoCancel(false)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openAppIntent(server.id, p.destination, tag, p.arg))
            .setPublicVersion(publicVersion(CH_PROGRESS, "YTN: ${p.kind.label.lowercase()} in progress"))
            .setRequestPromotedOngoing(true)
        pct?.let { b.setShortCriticalText("$it%") }
        if (Build.VERSION.SDK_INT >= 36) {
            b.setStyle(NotificationCompat.ProgressStyle().setProgress(pct ?: 0).setProgressIndeterminate(pct == null).setStyledByProgress(true))
        } else {
            b.setProgress(100, pct ?: 0, pct == null)
        }
        return b
    }

    @SuppressLint("MissingPermission")
    fun postProgress(server: ServerConfig, p: ProgressItem) {
        if (!canPost()) return
        nm.notify(progressTag(server.id, p.key), ID_PROGRESS, buildProgress(server, p).build())
    }

    /** Keys of the progress notifications of [serverId] currently shown. */
    fun shownProgress(serverId: String): Set<String> {
        val prefix = "progress/$serverId/"
        return runCatching { nm.activeNotifications }.getOrDefault(emptyList())
            .filter { it.id == ID_PROGRESS && it.tag?.startsWith(prefix) == true }.map { it.tag!!.removePrefix(prefix) }.toSet()
    }

    fun cancelProgress(serverId: String, key: String) = nm.cancel(progressTag(serverId, key), ID_PROGRESS)

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

    /** 1.8.0: silent ongoing notification of [app.truenascompanion.data.files.TransferService]. */
    fun buildTransfer(title: String) = base(CH_TRANSFER)
        .setContentTitle(title)
        .setContentText("Keep YTN open in the background until it finishes")
        .setOngoing(true)
        .setSilent(true)
        .setProgress(0, 0, true)
        .setCategory(NotificationCompat.CATEGORY_PROGRESS)
        .setContentIntent(openAppIntent(null, DeepLink.DEST_DASHBOARD, "transfer"))
        .build()
}
