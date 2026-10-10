package app.truenascompanion

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import app.truenascompanion.data.model.Health
import app.truenascompanion.data.model.ServerConfig
import app.truenascompanion.notify.AlertNotifier
import app.truenascompanion.notify.DeepLink
import app.truenascompanion.notify.ProgressItem
import app.truenascompanion.notify.ProgressKind
import app.truenascompanion.notify.rules.AlertRule
import app.truenascompanion.notify.rules.RuleEvent
import app.truenascompanion.notify.rules.RuleKind
import app.truenascompanion.notify.rules.RuleSeverity
import app.truenascompanion.widget.WidgetPool
import app.truenascompanion.widget.WidgetRefreshWorker
import app.truenascompanion.widget.WidgetSnapshot
import app.truenascompanion.widget.WidgetStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** 1.10.0: rule and progress notifications, channels, widgets (Robolectric). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class V1100NotifierTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val nm get() = app.getSystemService(NotificationManager::class.java)
    private val notifier by lazy { AlertNotifier(app) }
    private val server = ServerConfig(id = "s1", name = "Example NAS", url = "https://nas.example.com")

    @Before fun setUp() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        notifier.createChannels()
    }

    private fun title(n: Notification) = n.extras.getCharSequence(NotificationCompat.EXTRA_TITLE).toString()

    @Test fun channelsKeepTheirIdsAndGainProgress() {
        val ch = nm.notificationChannels.associateBy { it.id }
        assertEquals("Critical", ch.getValue(AlertNotifier.CH_CRITICAL).name.toString())
        assertEquals("Warnings", ch.getValue(AlertNotifier.CH_WARNING).name.toString())
        assertEquals("Info", ch.getValue(AlertNotifier.CH_INFO).name.toString())
        assertEquals("Recovered", ch.getValue(AlertNotifier.CH_CLEARED).name.toString())
        assertEquals("Progress", ch.getValue(AlertNotifier.CH_PROGRESS).name.toString())
        assertEquals(NotificationManager.IMPORTANCE_LOW, ch.getValue(AlertNotifier.CH_PROGRESS).importance)
    }

    @Test fun ruleNotificationAndRecoveredKeepTheLockScreenPrivate() {
        val rule = AlertRule(id = "r1", kind = RuleKind.POOL_USAGE, severity = RuleSeverity.CRITICAL, threshold = 85.0)
        val e = RuleEvent("r1|tank", rule, "tank", "Pool tank is 91% full (rule: over 85%).")
        notifier.postRule(server, e)
        val sbn = nm.activeNotifications.single { it.tag == "rule/s1/r1|tank" }
        val n = sbn.notification
        assertEquals(AlertNotifier.CH_CRITICAL, n.channelId)
        assertEquals("Pool usage · tank", title(n))
        assertEquals(listOf("Edit rule"), n.actions.map { it.title.toString() })
        assertEquals(DeepLink.DEST_POOL, shadowOf(n.contentIntent).savedIntent.getStringExtra(DeepLink.EXTRA_DESTINATION))
        assertEquals(DeepLink.DEST_RULES, shadowOf(n.actions[0].actionIntent).savedIntent.getStringExtra(DeepLink.EXTRA_DESTINATION))
        val pub = n.publicVersion!!
        assertEquals("YTN: critical (phone rule)", title(pub))
        assertFalse(pub.extras.getCharSequence(NotificationCompat.EXTRA_TEXT).toString().contains("tank"))

        notifier.postRuleRecovered(server, e)
        val rec = nm.activeNotifications.single { it.tag == "rule/s1/r1|tank" }.notification
        assertEquals(AlertNotifier.CH_CLEARED, rec.channelId)
        assertEquals("Recovered: Pool usage · tank", title(rec))
        assertEquals("YTN: recovered (phone rule)", title(rec.publicVersion!!))

        notifier.postRule(server, e.copy(key = "r1|fast", subject = "fast"))
        notifier.cancelRuleAll("s1", "r1")
        assertTrue(nm.activeNotifications.none { it.tag?.startsWith("rule/s1/r1|") == true })
    }

    @Test fun progressNotificationIsOngoingSilentAndPromotable() {
        val p = ProgressItem("scrub:tank", ProgressKind.SCRUB, "Scrubbing tank", 42, null, DeepLink.DEST_POOL, "tank")
        notifier.postProgress(server, p)
        val n = nm.activeNotifications.single { it.tag == "progress/s1/scrub:tank" }.notification
        assertEquals(AlertNotifier.CH_PROGRESS, n.channelId)
        assertTrue(n.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertEquals("Scrubbing tank", title(n))
        assertEquals("42%", n.extras.getCharSequence(NotificationCompat.EXTRA_TEXT).toString())
        assertEquals(42, n.extras.getInt(NotificationCompat.EXTRA_PROGRESS))
        assertEquals(100, n.extras.getInt(NotificationCompat.EXTRA_PROGRESS_MAX))
        assertTrue(n.extras.getBoolean(NotificationCompat.EXTRA_REQUEST_PROMOTED_ONGOING))
        assertEquals("YTN: scrub in progress", title(n.publicVersion!!))
        assertEquals(setOf("scrub:tank"), notifier.shownProgress("s1"))
        notifier.cancelProgress("s1", "scrub:tank")
        assertTrue(notifier.shownProgress("s1").isEmpty())

        val unknown = notifier.buildProgress(server, ProgressItem("job:1", ProgressKind.CLOUD_SYNC, "Cloud sync", null, "Listing files", DeepLink.DEST_CLOUD_SYNC)).build()
        assertTrue(unknown.extras.getBoolean(NotificationCompat.EXTRA_PROGRESS_INDETERMINATE))
        assertEquals("Listing files", unknown.extras.getCharSequence(NotificationCompat.EXTRA_TEXT).toString())
    }

    @Test fun widgetSnapshotRoundTripsPoolsAndApps() {
        val snap = WidgetSnapshot(serverName = "Home NAS", poolHealth = Health.WARNING, poolLabel = "1 degraded", alertCount = 2,
            updatedAt = 1L, pools = listOf(WidgetPool("tank", 82, Health.WARNING)), appsRunning = 3, appsTotal = 4)
        WidgetStore.save(app, snap)
        assertEquals(snap, WidgetStore.load(app))
        WidgetStore.save(app, snap.copy(appsRunning = null, appsTotal = null, pools = emptyList()))
        assertEquals(snap.copy(appsRunning = null, appsTotal = null, pools = emptyList()), WidgetStore.load(app))
    }

    @Test fun everyWidgetReceiverIsRegisteredAndCounted() {
        val pm = app.packageManager
        WidgetRefreshWorker.RECEIVERS.forEach { cls ->
            val info = pm.getReceiverInfo(android.content.ComponentName(app, cls), android.content.pm.PackageManager.GET_META_DATA)
            assertTrue(cls.simpleName, info.metaData?.getInt("android.appwidget.provider", 0) != 0)
        }
        assertEquals(5, WidgetRefreshWorker.RECEIVERS.size)
        assertFalse(WidgetRefreshWorker.hasWidgets(app))
    }
}
