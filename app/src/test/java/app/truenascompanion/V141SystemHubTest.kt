package app.truenascompanion

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import app.truenascompanion.data.model.NasUpdateStatus
import app.truenascompanion.data.store.ConnectionTimeoutPrefs
import app.truenascompanion.data.store.ThemeMode
import app.truenascompanion.notify.AlertTarget
import app.truenascompanion.notify.DeepLink
import app.truenascompanion.ui.system.HUB_LIST_TAG
import app.truenascompanion.ui.system.HubActions
import app.truenascompanion.ui.system.HubGroup
import app.truenascompanion.ui.system.HubHeader
import app.truenascompanion.ui.system.HubItem
import app.truenascompanion.ui.system.HubSummary
import app.truenascompanion.ui.system.PowerSection
import app.truenascompanion.ui.system.SystemHubContent
import app.truenascompanion.ui.system.SystemPage
import app.truenascompanion.ui.system.filterHub
import app.truenascompanion.ui.system.hubSubtitles
import app.truenascompanion.ui.system.hubTileTag
import app.truenascompanion.ui.system.open
import app.truenascompanion.ui.theme.TrueNasTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** 1.4.1: the compact System hub. Every former System-tab feature is one tap away, search filters, deep links land on the right page. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xxhdpi", application = android.app.Application::class)
class V141SystemHubTest {
    @get:Rule val rule = createComposeRule()

    private fun show(content: @Composable () -> Unit) = rule.setContent { TrueNasTheme(themeMode = ThemeMode.DARK, dynamicColor = false) { content() } }

    private val header = HubHeader("homenas", "nas.example.com", "25.10.4", 1_036_800, online = true)

    @Test fun everyHubItemIsShownOnceAndEachTileOpensIt() {
        // Each item belongs to exactly one group.
        assertEquals(HubItem.entries.toSet(), HubGroup.entries.flatMap { it.items }.toSet())
        assertEquals(HubItem.entries.size, HubGroup.entries.sumOf { it.items.size })

        val opened = mutableListOf<HubItem>()
        var switched = 0
        show { SystemHubContent(header, hubSubtitles(HubSummary()), onOpen = { opened += it }, onSwitchServer = { switched++ }) }
        rule.onNodeWithText("homenas").assertIsDisplayed()
        rule.onNodeWithTag("hub_header").performClick()
        assertEquals(1, switched)
        val order = HubGroup.entries.flatMap { it.items }
        order.forEach { item ->
            rule.onNodeWithTag(HUB_LIST_TAG).performScrollToNode(hasTestTag(hubTileTag(item)))
            rule.onNodeWithTag(hubTileTag(item)).assertIsDisplayed().performClick()
        }
        assertEquals(order, opened)
    }

    @Test fun eachItemReachesItsScreenOrPage() {
        val hits = mutableListOf<String>()
        val a = HubActions(
            onPage = { hits += "page:${it.name}" }, onJobs = { hits += "jobs" }, onOverview = { hits += "overview" },
            onServices = { hits += "services" }, onScheduledTasks = { hits += "tasks" }, onAccounts = { hits += "accounts" },
            onCertificates = { hits += "certs" }, onAudit = { hits += "audit" }, onReports = { hits += "reports" }, onShell = { hits += "shell" },
        )
        val expected = mapOf(
            HubItem.UPDATES to "page:UPDATES", HubItem.POWER to "page:POWER", HubItem.TASKS to "jobs", HubItem.ALL_SERVERS to "overview",
            HubItem.SERVICES to "services", HubItem.SCHEDULED to "tasks", HubItem.ACCOUNTS to "accounts", HubItem.CERTIFICATES to "certs",
            HubItem.AUDIT to "audit", HubItem.SHELL to "shell", HubItem.REPORTS to "reports", HubItem.ALERTS to "page:ALERTS",
            HubItem.SECURITY to "page:SECURITY", HubItem.CONNECTION to "page:CONNECTION", HubItem.APPEARANCE to "page:APPEARANCE",
            HubItem.ABOUT to "page:ABOUT",
        )
        assertEquals(HubItem.entries.toSet(), expected.keys)
        HubItem.entries.forEach { item ->
            hits.clear(); item.open(a)
            assertEquals("$item", listOf(expected.getValue(item)), hits)
        }
        // Every page is reachable from a tile.
        assertEquals(SystemPage.entries.toSet(), HubItem.entries.mapNotNull { it.page }.toSet())
    }

    @Test fun searchFindsSettingsByEverydayWords() {
        fun hits(q: String) = filterHub(q).flatMap { it.second }
        assertEquals(listOf(HubItem.POWER), hits("reboot"))
        assertEquals(listOf(HubItem.POWER), hits("power off"))
        assertTrue(hits("shut down").contains(HubItem.POWER))
        assertEquals(listOf(HubItem.SCHEDULED), hits("cron"))
        assertEquals(listOf(HubItem.APPEARANCE), hits("dark"))
        assertEquals(listOf(HubItem.SECURITY), hits("fingerprint"))
        assertEquals(listOf(HubItem.ABOUT), hits("channel"))
        assertEquals(listOf(HubItem.UPDATES), hits("boot environments"))
        assertTrue(hits("ssh").containsAll(listOf(HubItem.SERVICES, HubItem.ACCOUNTS, HubItem.SHELL)))
        assertTrue(hits("CERT").contains(HubItem.CERTIFICATES))
        assertTrue(hits("zzz-nothing").isEmpty())
        assertEquals(HubItem.entries.size, hits("  ").size)
    }

    @Test fun searchFieldFiltersTiles() {
        show { SystemHubContent(header, hubSubtitles(HubSummary()), onOpen = {}, onSwitchServer = {}, searchOpen = true, query = "theme") }
        rule.onNodeWithTag("hub_search").assertIsDisplayed()
        rule.onNodeWithTag(hubTileTag(HubItem.APPEARANCE)).assertIsDisplayed()
        rule.onNodeWithTag(hubTileTag(HubItem.POWER)).assertDoesNotExist()
        rule.onNodeWithTag("hub_header").assertDoesNotExist()
    }

    @Test fun searchWithNoMatchSaysSo() {
        show { SystemHubContent(header, hubSubtitles(HubSummary()), onOpen = {}, onSwitchServer = {}, searchOpen = true, query = "qwerty") }
        rule.onNodeWithText("Nothing matches", substring = true).assertIsDisplayed()
    }

    @Test fun liveSubtitles() {
        val update = NasUpdateStatus("NORMAL", "TrueNAS-SCALE-Goldeye", "GENERAL", "25.10.5", null, null, null, null, null, null)
        val s = hubSubtitles(
            HubSummary(
                connected = true, nasUpdate = update, runningJobs = 2, serverCount = 3, servicesRunning = 4, cronJobs = 2, initScripts = 1,
                certsExpiring = 1, certsExpired = 0, alertsMode = HubSummary.AlertsMode.INSTANT, lockOn = true,
                giveUpMs = ConnectionTimeoutPrefs.DEFAULT_MS, themeMode = ThemeMode.DARK, appVersion = "1.4.1",
            ),
        )
        assertEquals("Update: 25.10.5", s.getValue(HubItem.UPDATES).text)
        assertTrue(s.getValue(HubItem.UPDATES).attention)
        assertEquals("2 running", s.getValue(HubItem.TASKS).text)
        assertEquals("3 saved", s.getValue(HubItem.ALL_SERVERS).text)
        assertEquals("4 running", s.getValue(HubItem.SERVICES).text)
        assertEquals("2 jobs · 1 script", s.getValue(HubItem.SCHEDULED).text)
        assertEquals("1 expiring soon", s.getValue(HubItem.CERTIFICATES).text)
        assertTrue(s.getValue(HubItem.CERTIFICATES).attention)
        assertEquals("Instant", s.getValue(HubItem.ALERTS).text)
        assertEquals("On", s.getValue(HubItem.SECURITY).text)
        assertEquals("Retry for ${ConnectionTimeoutPrefs.label(ConnectionTimeoutPrefs.DEFAULT_MS)}", s.getValue(HubItem.CONNECTION).text)
        assertEquals("Dark theme", s.getValue(HubItem.APPEARANCE).text)
        assertEquals("Version 1.4.1", s.getValue(HubItem.ABOUT).text)
        assertEquals("NAS terminal", s.getValue(HubItem.SHELL).text)

        // Nothing known yet (offline): short descriptions, nothing highlighted.
        val plain = hubSubtitles(HubSummary())
        assertTrue(plain.values.none { it.attention || it.text.isBlank() })
        assertEquals("Connect first", plain.getValue(HubItem.SHELL).text)
        val upToDate = hubSubtitles(HubSummary(nasUpdate = update.copy(newVersion = null), cronJobs = 0, initScripts = 0, certsExpired = 0, certsExpiring = 0, alertsMode = HubSummary.AlertsMode.PERIODIC, alertsIntervalMinutes = 60))
        assertEquals("Up to date", upToDate.getValue(HubItem.UPDATES).text)
        assertEquals("None yet", upToDate.getValue(HubItem.SCHEDULED).text)
        assertEquals("All valid", upToDate.getValue(HubItem.CERTIFICATES).text)
        assertEquals("Every 1 h", upToDate.getValue(HubItem.ALERTS).text)
    }

    @Test fun deepLinksOpenTheRightPage() {
        assertEquals(SystemPage.ALERTS, SystemPage.forSettingsLink("service")) // instant-alerts notification
        assertEquals(SystemPage.ABOUT, SystemPage.forSettingsLink(SystemPage.ARG_APP_UPDATE)) // app-update notification
        assertNull(SystemPage.forSettingsLink(null)) // older notifications: the hub
        SystemPage.entries.forEach { assertEquals(it, SystemPage.parse(it.name.lowercase())) }
        assertNull(SystemPage.parse("nope"))
        assertEquals(DeepLink.DEST_UPDATE, AlertTarget.Update.destination)

        val root = File("src/main/java/app/truenascompanion").takeIf { it.exists() } ?: File("app/src/main/java/app/truenascompanion")
        val appRoot = File(root, "ui/AppRoot.kt").readText()
        // TrueNAS update alerts open System › Updates; the settings link honours its argument.
        assertTrue(appRoot.contains("AlertTarget.Update -> { switchTab(Tab.SYSTEM.route); navigate(Routes.systemPage(app.truenascompanion.ui.system.SystemPage.UPDATES)) }"))
        assertTrue(appRoot.contains("SystemPage.forSettingsLink(link.arg)"))
        // Pages are ordinary pushed routes inside the connection overlay host, and the bottom bar only shows on tabs.
        val overlay = appRoot.indexOf("ConnectionOverlayHost(")
        val page = appRoot.indexOf("pushed(Routes.SYSTEM_PAGE")
        assertTrue(overlay in 0 until page)
        assertTrue(appRoot.contains("val showBar = Tab.entries.any { it.route == route }"))
        val worker = File(root, "data/update/UpdateWorker.kt").readText()
        assertTrue(worker.contains("SystemPage.ARG_APP_UPDATE"))
    }

    @Test fun powerNeedsAConnection() {
        var reboot = 0
        show { PowerSection("homenas", enabled = false, onReboot = { reboot++ }, onShutdown = {}) }
        rule.onNodeWithText("Reboot").assertIsNotEnabled()
        rule.onNodeWithText("Shut down").assertIsNotEnabled()
        assertEquals(0, reboot)
    }

    @Test fun powerButtonsCallBack() {
        var reboot = 0
        var off = 0
        show { PowerSection("homenas", enabled = true, onReboot = { reboot++ }, onShutdown = { off++ }) }
        rule.onNodeWithText("Reboot").assertIsEnabled().performClick()
        rule.onNodeWithText("Shut down").performClick()
        assertEquals(1, reboot); assertEquals(1, off)
        assertFalse(reboot > 1)
    }
}
