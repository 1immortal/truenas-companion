package app.truenascompanion

import androidx.compose.material3.Text
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.truenascompanion.data.model.AlertItem
import app.truenascompanion.data.model.Health
import app.truenascompanion.data.store.ThemeMode
import app.truenascompanion.ui.AppNavBar
import app.truenascompanion.ui.alerts.AlertBadge
import app.truenascompanion.ui.alerts.AlertCard
import app.truenascompanion.ui.components.Scaffold
import app.truenascompanion.ui.components.TopAppBar
import app.truenascompanion.ui.storage.StorageTabs
import app.truenascompanion.ui.system.HubItem
import app.truenascompanion.ui.theme.DarkStatus
import app.truenascompanion.ui.theme.LightStatus
import app.truenascompanion.ui.theme.TrueNasTheme
import app.truenascompanion.widget.WidgetSnapshot
import app.truenascompanion.widget.WidgetText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 1.8.0: design-system and UX fixes (tokens, status colours, Storage tabs, alert badge, widget text). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h740dp-xxhdpi")
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
class V180DesignTest {
    @get:Rule val rule = createComposeRule()

    private fun alert(uuid: String, level: String = "WARNING", dismissed: Boolean = false) =
        AlertItem(uuid, level, "Pool tank is 85% full.", "ZpoolCapacityWarning", 0L, dismissed, false)

    @Test fun storageUsesFourTabsAndKeepsDeepLinkPanes() {
        assertEquals(listOf("Pools", "Data", "Shares", "Backup"), StorageTabs.groups)
        assertEquals(0, StorageTabs.groupOf(StorageTabs.POOLS))
        assertEquals(0, StorageTabs.groupOf(StorageTabs.DISKS))
        assertEquals(1, StorageTabs.groupOf(StorageTabs.DATASETS))
        assertEquals(1, StorageTabs.groupOf(StorageTabs.FILES))
        assertEquals(2, StorageTabs.groupOf(StorageTabs.SHARES))
        assertEquals(3, StorageTabs.groupOf(StorageTabs.PROTECTION))
        // Every pane is reachable from exactly one tab.
        val all = (0..3).flatMap { StorageTabs.subPanes(it) }
        assertEquals((0..5).toList(), all.sorted())
    }

    @Test fun alertBadgeCountsOpenUnsnoozedAlerts() {
        val now = 1_000_000L
        val list = listOf(alert("a"), alert("b"), alert("c", dismissed = true), alert("d"))
        assertEquals(2, AlertBadge.count(list, mapOf("b" to now + 60_000, "d" to now - 1), now))
        assertNull(AlertBadge.label(0))
        assertEquals("7", AlertBadge.label(7))
        assertEquals("99+", AlertBadge.label(250))
    }

    @Test fun infoAlertsHaveTheirOwnHealthBelowWarning() {
        assertEquals(Health.INFO, alert("x", "INFO").health)
        assertEquals(Health.INFO, alert("x", "NOTICE").health)
        assertEquals(Health.WARNING, alert("x", "WARNING").health)
        assertEquals(Health.CRITICAL, alert("x", "CRITICAL").health)
        // "Worst" logic compares ordinals: info must not outrank a warning.
        assertTrue(Health.INFO.ordinal < Health.WARNING.ordinal && Health.HEALTHY.ordinal < Health.INFO.ordinal)
    }

    private fun contrast(a: Color, b: Color): Double {
        val (hi, lo) = listOf(a.luminance(), b.luminance()).sortedDescending()
        return (hi + 0.05) / (lo + 0.05)
    }

    @Test fun statusTextIsReadableOnItsContainer() {
        for (s in listOf(LightStatus, DarkStatus)) for (h in listOf(Health.HEALTHY, Health.INFO, Health.WARNING, Health.CRITICAL)) {
            assertTrue("$h ${contrast(s.of(h), s.containerOf(h))}", contrast(s.of(h), s.containerOf(h)) >= 4.5)
        }
    }

    @Test fun lightWarningFillIsAmberNotBrown() {
        val fill = LightStatus.fillOf(Health.WARNING)
        // Amber: bright (luminance well above the brown 0x8A5A00 text colour) and red > green > blue.
        assertTrue(fill.luminance() > 0.4f)
        assertTrue(fill.red > fill.green && fill.green > fill.blue)
        assertTrue(LightStatus.fillOf(Health.WARNING) != LightStatus.of(Health.WARNING))
    }

    @Test fun widgetTextIsFriendly() {
        assertEquals("Open YTN to add a server", WidgetText.status(WidgetSnapshot()))
        assertEquals("Pools: 2 healthy", WidgetText.status(WidgetSnapshot(serverName = "Home NAS", poolLabel = "2 healthy")))
        assertEquals("Can't reach the NAS", WidgetText.status(WidgetSnapshot(serverName = "Home NAS", error = "Can't reach the NAS")))
        assertEquals("No open alerts", WidgetText.alerts(0))
        assertEquals("1 open alert", WidgetText.alerts(1))
        assertEquals("3 open alerts", WidgetText.alerts(3))
        assertNull(WidgetText.updated(0))
        assertEquals("Updated 14:05", WidgetText.updated(5) { "14:05" })
    }

    @Test fun hubUsesClearNames() {
        assertEquals("Running jobs", HubItem.TASKS.title)
        assertEquals("Scheduled tasks", HubItem.SCHEDULED.title)
        assertEquals("App lock & privacy", HubItem.SECURITY.title)
        assertTrue("tailscale" in HubItem.CONNECTION.keywords)
    }

    @Test fun alertsTabShowsCountBadge() {
        rule.setContent { TrueNasTheme(themeMode = ThemeMode.LIGHT, dynamicColor = false) { AppNavBar("dashboard", alertCount = 3) {} } }
        rule.onNode(hasContentDescription("Alerts, 3 open alerts")).assertExists()
        rule.onNode(hasText("3"), useUnmergedTree = true).assertExists()
    }

    @Test fun noBadgeWithoutAlerts() {
        rule.setContent { TrueNasTheme(themeMode = ThemeMode.LIGHT, dynamicColor = false) { AppNavBar("dashboard", alertCount = 0) {} } }
        assertEquals(0, rule.onAllNodes(hasContentDescription("open alerts", substring = true)).fetchSemanticsNodes().size)
    }

    @Test fun alertCardAnnouncesExpandStateAndSnooze() {
        rule.setContent {
            TrueNasTheme(themeMode = ThemeMode.DARK, dynamicColor = false) {
                AlertCard(alert("a"), onDismiss = {}, snoozedUntil = System.currentTimeMillis() + 3_600_000)
            }
        }
        val collapsed = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Collapsed")
        rule.onNode(collapsed).assertExists().performClick()
        rule.onNode(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Expanded")).assertExists()
        rule.onNode(hasText("Snoozed until", substring = true)).assertExists()
        rule.onNodeWithText("Unsnooze").assertExists()
    }

    @Test fun topBarTitleIsAHeading() {
        rule.setContent {
            TrueNasTheme(themeMode = ThemeMode.LIGHT, dynamicColor = false) {
                Scaffold(topBar = { TopAppBar(title = { Text("Services") }) }) { Text("body") }
            }
        }
        rule.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading) and androidx.compose.ui.test.hasAnyDescendant(hasText("Services")), useUnmergedTree = true).assertExists()
    }
}
