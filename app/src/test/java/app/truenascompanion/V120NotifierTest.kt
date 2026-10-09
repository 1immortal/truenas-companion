package app.truenascompanion

import app.truenascompanion.notify.DeepLinkGuard
import android.Manifest
import android.app.Application
import android.app.NotificationManager
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import app.truenascompanion.data.model.AlertItem
import app.truenascompanion.data.model.ServerConfig
import app.truenascompanion.notify.AlertNotifier
import app.truenascompanion.notify.CertExpiry
import app.truenascompanion.notify.DeepLink
import app.truenascompanion.data.model.CertKind
import app.truenascompanion.data.model.NasCertificate
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** 1.2.0: grouped alert notifications with counts, snooze/dismiss actions and deep-link intents (Robolectric). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class V120NotifierTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val nm get() = app.getSystemService(NotificationManager::class.java)
    private val notifier by lazy { AlertNotifier(app) }
    private val server = ServerConfig(id = "s1", name = "Example NAS", url = "https://nas.example.com")

    private fun alert(uuid: String, klass: String, text: String, args: String? = null) =
        AlertItem(uuid, "WARNING", text, klass, null, false, false, args = args?.let { Json.parseToJsonElement(it) })

    @Before fun setUp() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        notifier.createChannels()
    }

    /** 1.7.1 (security M-3): with the app lock on there's no Dismiss action; alert details stay off the lock screen. */
    @Test fun appLockRemovesDismissAndLockScreenShowsOnlyCount() {
        notifier.appLockOn = true
        notifier.postAlerts(server, listOf(alert("u1", "SMARTTestFailed", "SMART test failed on sda", """{"name":"sda"}""")), mapOf("u1" to "SMART test failed"))
        val n = alertNotifications().single().notification
        assertEquals(listOf("Snooze", "Open"), n.actions.map { it.title.toString() })
        assertEquals(android.app.Notification.VISIBILITY_PRIVATE, n.visibility)
        val pub = n.publicVersion!!
        val title = pub.extras.getCharSequence(android.app.Notification.EXTRA_TITLE).toString()
        assertEquals("TrueNAS: 1 warning alert", title)
        assertFalse(pub.extras.getCharSequence(android.app.Notification.EXTRA_TEXT)?.contains("sda") == true)
        // M-2: the app's own intents are signed, so they may preselect the server and screen.
        val link = DeepLinkGuard.parse(app, shadowOf(n.contentIntent).savedIntent)
        assertEquals("s1", link?.serverId)
        val forged = android.content.Intent(shadowOf(n.contentIntent).savedIntent).putExtra(DeepLink.EXTRA_SERVER_ID, "s2")
        assertEquals(null, DeepLinkGuard.parse(app, forged)?.serverId)
    }

    private fun alertNotifications() = nm.activeNotifications.filter { it.tag?.startsWith("alert/s1/") == true }

    @Test fun repeatedAlertsShareOneNotificationWithCount() {
        notifier.appLockOn = false
        notifier.postAlerts(server, listOf(alert("u1", "SMARTTestFailed", "SMART test failed on sda", """{"name":"sda"}""")), mapOf("u1" to "SMART test failed"))
        assertEquals(listOf("alert/s1/u1"), alertNotifications().map { it.tag })
        val single = alertNotifications().single().notification
        assertEquals(listOf("Dismiss", "Snooze", "Open"), single.actions.map { it.title.toString() })
        assertEquals(DeepLink.DEST_DISK, shadowOf(single.contentIntent).savedIntent.getStringExtra(DeepLink.EXTRA_DESTINATION))
        assertEquals("sda", shadowOf(single.contentIntent).savedIntent.getStringExtra(DeepLink.EXTRA_ARG))

        notifier.postAlerts(server, listOf(alert("u2", "SMARTTestFailed", "SMART test failed on sdb", """{"name":"sdb"}""")), mapOf("u2" to "SMART test failed"))
        val grouped = alertNotifications().single()
        assertEquals("alert/s1/k:SMARTTestFailed", grouped.tag)
        assertEquals("SMART test failed · 2", grouped.notification.extras.getCharSequence(NotificationCompat.EXTRA_TITLE).toString())
        assertEquals(2, grouped.notification.number)
        assertEquals("Dismiss all", grouped.notification.actions[0].title.toString())
        // Different disks: the group opens the disk list.
        val open = shadowOf(grouped.notification.contentIntent).savedIntent
        assertEquals(DeepLink.DEST_DISK, open.getStringExtra(DeepLink.EXTRA_DESTINATION))
        assertEquals(null, open.getStringExtra(DeepLink.EXTRA_ARG))

        // One alert clears: the group shrinks to a count of 1, then disappears.
        notifier.withdraw(server, listOf("u1"))
        val left = alertNotifications().single()
        assertEquals("SMART test failed", left.notification.extras.getCharSequence(NotificationCompat.EXTRA_TITLE).toString())
        notifier.removeFromShade("s1", setOf("u2"))
        assertTrue(alertNotifications().isEmpty())
    }

    @Test fun differentClassesStaySeparate() {
        notifier.postAlerts(server, listOf(
            alert("p1", "VolumeStatus", "Pool tank is DEGRADED", """{"volume":"tank"}"""),
            alert("a1", "AppUpdate", "An update is available for nextcloud", """{"count":1,"apps":"nextcloud"}"""),
        ), mapOf("p1" to "Pool status", "a1" to "App update"))
        val byTag = alertNotifications().associateBy { it.tag }
        assertEquals(setOf("alert/s1/p1", "alert/s1/a1"), byTag.keys)
        val pool = shadowOf(byTag.getValue("alert/s1/p1").notification.contentIntent).savedIntent
        assertEquals(DeepLink.DEST_POOL, pool.getStringExtra(DeepLink.EXTRA_DESTINATION))
        assertEquals("tank", pool.getStringExtra(DeepLink.EXTRA_ARG))
        val appIntent = shadowOf(byTag.getValue("alert/s1/a1").notification.contentIntent).savedIntent
        assertEquals(DeepLink.DEST_APP, appIntent.getStringExtra(DeepLink.EXTRA_DESTINATION))
        assertEquals("nextcloud", appIntent.getStringExtra(DeepLink.EXTRA_ARG))
        // Two alert notifications: the inbox summary lists both.
        assertTrue(nm.activeNotifications.any { it.tag == "summary/s1" })
    }

    @Test fun certificateWarningOpensCertificates() {
        val c = NasCertificate(7, "web", CertKind.CERTIFICATE, "nas.example.com", listOf("DNS:nas.example.com"), "Example CA", false, null, null, false,
            null, null, null, null, false, null, null, false, true, null)
        notifier.postCertificate(server, CertExpiry.Warning(c, 5, false))
        val n = nm.activeNotifications.single { it.tag == "cert/s1/7" }.notification
        assertEquals(AlertNotifier.CH_WARNING, n.channelId)
        assertEquals("Certificate expires in 5 days", n.extras.getCharSequence(NotificationCompat.EXTRA_TITLE).toString())
        val i = shadowOf(n.contentIntent).savedIntent
        assertEquals(DeepLink.DEST_CERTIFICATE, i.getStringExtra(DeepLink.EXTRA_DESTINATION))
        assertEquals("web", i.getStringExtra(DeepLink.EXTRA_ARG))
    }
}
