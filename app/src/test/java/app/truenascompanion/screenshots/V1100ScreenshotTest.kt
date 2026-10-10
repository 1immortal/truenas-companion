package app.truenascompanion.screenshots

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.AdaptiveIconDrawable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import app.truenascompanion.R
import app.truenascompanion.data.model.*
import app.truenascompanion.data.runway.RunwayForecast
import app.truenascompanion.data.store.NotificationPrefs
import app.truenascompanion.data.store.ThemeMode
import app.truenascompanion.notify.AlertNotifier
import app.truenascompanion.notify.DeepLink
import app.truenascompanion.notify.ProgressItem
import app.truenascompanion.notify.ProgressKind
import app.truenascompanion.notify.rules.AlertRule
import app.truenascompanion.notify.rules.BackupTarget
import app.truenascompanion.notify.rules.RuleKind
import app.truenascompanion.notify.rules.RuleSeverity
import app.truenascompanion.ui.components.UiState
import app.truenascompanion.ui.dashboard.DashboardData
import app.truenascompanion.ui.dashboard.EditGrid
import app.truenascompanion.ui.dashboard.LiveStats
import app.truenascompanion.ui.dashboard.PoolRunway
import app.truenascompanion.ui.dashboard.WidgetGrid
import app.truenascompanion.ui.notifications.AlertRulesContent
import app.truenascompanion.ui.notifications.PhoneAlertsSection
import app.truenascompanion.ui.notifications.RuleChoices
import app.truenascompanion.ui.notifications.RulesUi
import app.truenascompanion.ui.theme.TrueNasTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.sin

/**
 * 1.10.0 previews, example data only: alert rules and the rule editor, a live progress notification, quiet hours,
 * the runway and ARC cards, dashboard edit mode, and the new home-screen widgets (also the picker preview images).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xxhdpi", application = android.app.Application::class)
class V1100ScreenshotTest {
    @get:Rule val rule = createComposeRule()
    private val dir = File(System.getProperty("screenshot.dir") ?: "build/screenshots").apply { mkdirs() }
    private fun out(name: String) = File(dir, "v1100_$name.png").absolutePath
    private val gib = 1024.0 * 1024 * 1024

    private fun shot(name: String, dark: Boolean, content: @Composable () -> Unit) {
        setUp(dark, content)
        rule.onRoot().captureRoboImage(out(name))
    }

    private fun setUp(dark: Boolean, content: @Composable () -> Unit) {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            TrueNasTheme(themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT, dynamicColor = false) {
                Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) { content() }
            }
        }
        repeat(8) { rule.mainClock.advanceTimeBy(250); shadowOf(android.os.Looper.getMainLooper()).idle() }
    }

    // ---------- rules ----------

    private val rules = AlertRule.suggested().mapIndexed { i, r -> r.copy(id = "r$i") } +
        AlertRule(id = "r9", kind = RuleKind.APP_NOT_RUNNING, severity = RuleSeverity.WARNING, target = "nextcloud", enabled = false)

    @Test fun rulesList() = shot("rules_list", false) {
        AlertRulesContent(UiState.Success(RulesUi("s1", "homenas", rules, firing = setOf("r0"))), onBack = {}, onRetry = {},
            onAdd = {}, onSuggested = {}, onEdit = {}, onToggle = { _, _ -> }, onDelete = {})
    }

    @Test fun rulesEmpty() = shot("rules_empty", true) {
        AlertRulesContent(UiState.Success(RulesUi("s1", "homenas", emptyList())), onBack = {}, onRetry = {},
            onAdd = {}, onSuggested = {}, onEdit = {}, onToggle = { _, _ -> }, onDelete = {})
    }

    /**
     * The editor's fields on a dialog-shaped surface (Robolectric can't settle an ExposedDropdownMenuBox inside a real
     * AlertDialog window, so the preview draws the same fields without the dialog window).
     */
    @Test fun ruleEditor() = shot("rules_editor", true) {
        val r = AlertRule(id = "e1", kind = RuleKind.DISK_TEMP, severity = RuleSeverity.WARNING, threshold = 50.0, minutes = 10, cooldownMinutes = 60)
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)), contentAlignment = Alignment.Center) {
            Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.padding(24.dp)) {
                Column(Modifier.padding(24.dp)) {
                    Text(r.kind.title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(bottom = 16.dp))
                    app.truenascompanion.ui.notifications.RuleEditorFields(r, "50", "10", r, null,
                        RuleChoices(apps = listOf("nextcloud", "jellyfin")), onRule = {}, onThreshold = {}, onMinutes = {})
                    Row(Modifier.fillMaxWidth().padding(top = 20.dp), horizontalArrangement = Arrangement.End) {
                        androidx.compose.material3.TextButton(onClick = {}) { Text("Cancel") }
                        androidx.compose.material3.TextButton(onClick = {}) { Text("Save") }
                    }
                }
            }
        }
    }

    // ---------- quiet hours ----------

    @Test fun quietHours() = shot("quiet_hours", false) {
        val server = ServerConfig(id = "s1", name = "homenas", url = "https://nas.example.com")
        val prefs = NotificationPrefs(enabledServers = setOf("s1"), quietEnabled = true, quietStart = 22 * 60 + 30, quietEnd = 7 * 60,
            quietDays = setOf(1, 2, 3, 4, 7), quietCriticalBreaksThrough = true)
        // Scrolled to the end, where quiet hours are.
        Column(Modifier.verticalScroll(androidx.compose.foundation.ScrollState(100_000)).padding(16.dp)) {
            PhoneAlertsSection(server, prefs, canNotify = true, batteryOk = true, onToggle = {}, onUpdate = {}, onAllowNotifications = {},
                onBattery = {}, onChannels = {}, onTest = {}, onRules = {})
        }
    }

    // ---------- progress notification ----------

    /** Draws the notifications AlertNotifier really builds (title, text, sub text, progress from the extras). */
    @Test fun progressNotification() {
        val notifier = AlertNotifier(ctx)
        val server = ServerConfig(id = "s1", name = "homenas", url = "https://nas.example.com")
        val scrub = notifier.buildProgress(server, ProgressItem("scrub:tank", ProgressKind.SCRUB, "Scrubbing tank", 42, null, DeepLink.DEST_POOL, "tank")).build()
        val repl = notifier.buildProgress(server, ProgressItem("job:7", ProgressKind.REPLICATION, "tank/photos → backup", 68,
            "Sending tank/photos@auto-2026-10-09_02-00", DeepLink.DEST_REPLICATION)).build()
        val ruleN = notifier.buildRule(server, app.truenascompanion.notify.rules.RuleEvent("r1|sdc", rules[1].copy(id = "r1"), "sdc",
            "Disk sdc stayed above 50°C for 10 min (lowest 52°C).")).build()
        fun ex(n: android.app.Notification, k: String) = n.extras.getCharSequence(k)?.toString().orEmpty()
        assertEquals("Scrubbing tank", ex(scrub, NotificationCompat.EXTRA_TITLE))
        shot("progress_notification", true) {
            ShadeMock(listOf(scrub, repl), ruleN, ::ex)
        }
    }

    @Composable
    private fun ShadeMock(progress: List<android.app.Notification>, ruleN: android.app.Notification, ex: (android.app.Notification, String) -> String) {
        val shade = Color(0xFF101418); val card = Color(0xFF232A33); val on = Color(0xFFE6E9EF); val sub = Color(0xFFAAB2BF)
        val brand = Color(0xFF2F5BEA); val accent = Color(0xFF9DB4FF)
        Column(Modifier.fillMaxSize().background(shade).padding(horizontal = 12.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            // Status bar with the Android 16 Live Update chip (short critical text).
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("02:41", color = on, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.width(8.dp))
                Row(Modifier.clip(RoundedCornerShape(50)).background(brand).padding(horizontal = 8.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.foundation.Image(painterResource(R.drawable.ic_stat_notify), null, Modifier.size(14.dp), colorFilter = ColorFilter.tint(Color.White))
                    Spacer(Modifier.width(4.dp))
                    Text(progress.first().extras.getCharSequence(NotificationCompat.EXTRA_SHORT_CRITICAL_TEXT)?.toString() ?: "", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
            Text("02:41", color = on, fontSize = 40.sp, fontWeight = FontWeight.Light, modifier = Modifier.padding(start = 8.dp, top = 8.dp))
            Text("Fri, Oct 9", color = sub, fontSize = 14.sp, modifier = Modifier.padding(start = 8.dp, bottom = 12.dp))
            progress.forEachIndexed { i, n ->
                val pct = n.extras.getInt(NotificationCompat.EXTRA_PROGRESS)
                Card(i == 0, false, card) {
                    Header(ex(n, NotificationCompat.EXTRA_SUB_TEXT) + " · now", ex(n, NotificationCompat.EXTRA_TITLE), ex(n, NotificationCompat.EXTRA_TEXT), on, sub, brand)
                    Spacer(Modifier.height(10.dp))
                    LinearProgressIndicator(progress = { pct / 100f }, modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                        color = accent, trackColor = Color(0xFF3A4350), strokeCap = StrokeCap.Round, gapSize = 0.dp, drawStopIndicator = {})
                }
            }
            Card(false, true, card) {
                Header(ex(ruleN, NotificationCompat.EXTRA_SUB_TEXT), ex(ruleN, NotificationCompat.EXTRA_TITLE), ex(ruleN, NotificationCompat.EXTRA_TEXT), on, sub, brand)
                Row(Modifier.padding(start = 48.dp, top = 10.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    ruleN.actions.orEmpty().forEach { Text(it.title.toString(), color = accent, fontSize = 14.sp, fontWeight = FontWeight.Medium) }
                }
            }
        }
    }

    @Composable private fun Card(top: Boolean, bottom: Boolean, bg: Color, content: @Composable () -> Unit) {
        val shape = RoundedCornerShape(if (top) 24.dp else 6.dp, if (top) 24.dp else 6.dp, if (bottom) 24.dp else 6.dp, if (bottom) 24.dp else 6.dp)
        Column(Modifier.fillMaxWidth().clip(shape).background(bg).padding(16.dp)) { content() }
    }

    @Composable private fun Header(meta: String, title: String, text: String, on: Color, sub: Color, brand: Color) {
        Row(verticalAlignment = Alignment.Top) {
            Box(Modifier.size(36.dp).clip(CircleShape).background(brand), contentAlignment = Alignment.Center) {
                androidx.compose.foundation.Image(painterResource(R.drawable.ic_stat_notify), null, Modifier.size(20.dp), colorFilter = ColorFilter.tint(Color.White))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(meta, color = sub, fontSize = 12.sp, maxLines = 1)
                Text(title, color = on, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text(text, color = sub, fontSize = 14.sp, maxLines = 2)
            }
        }
    }

    // ---------- dashboard ----------

    private val pools = listOf(
        Pool(1, "tank", "ONLINE", true, false, null, 32_000_000_000_000, 21_800_000_000_000, 10_200_000_000_000, null, "SCRUB", "FINISHED", 100.0, 0, listOf("sda", "sdb")),
        Pool(2, "fast", "ONLINE", true, false, null, 2_000_000_000_000, 820_000_000_000, 1_180_000_000_000, null, null, null, null, null, listOf("nvme0n1")),
        Pool(3, "scratch", "ONLINE", true, false, null, 4_000_000_000_000, 400_000_000_000, 3_600_000_000_000, null, null, null, null, null, listOf("sdc")),
    )
    private val live: LiveStats = run {
        var l = LiveStats()
        repeat(40) { i ->
            l = l.add(RealtimeStats(cpuPercent = 4.0 + 2 * sin(i / 3.0), cpuTempC = 44.0, memoryTotal = (62.6 * gib).toLong(), memoryAvailable = (6.1 * gib).toLong(),
                arcSize = (41.3 * gib).toLong(), netRxBytesPerSec = 5200.0, netTxBytesPerSec = 900.0, cpuCores = List(8) { 4.0 },
                arcHitPercent = 96.0 + 2.5 * sin(i / 5.0)))
        }
        l
    }
    private val data = DashboardData(
        loading = false, pools = pools,
        runway = listOf(
            PoolRunway("tank", RunwayForecast.Full(214, 48e9), List(60) { 0.62f + it * 0.0011f }, 0.68f),
            PoolRunway("fast", RunwayForecast.NotGrowing, List(30) { 0.41f + 0.004f * sin(it / 4.0).toFloat() }, 0.41f),
            PoolRunway("scratch", RunwayForecast.Collecting(4), List(4) { 0.1f }, 0.1f),
        ),
    )

    @Test fun runwayCard() = shot("runway_card", false) {
        WidgetGrid(listOf(WidgetConfig(WidgetType.RUNWAY), WidgetConfig(WidgetType.ARC), WidgetConfig(WidgetType.MEMORY), WidgetConfig(WidgetType.POOLS)),
            data, live, ApiFlavor.WEBSOCKET, onOpen = {}, onEdit = {})
    }

    @Test fun runwayCompactDark() = shot("runway_compact_dark", true) {
        WidgetGrid(listOf(WidgetConfig(WidgetType.RUNWAY), WidgetConfig(WidgetType.ARC), WidgetConfig(WidgetType.CPU)),
            data, live, ApiFlavor.WEBSOCKET, onOpen = {}, onEdit = {}, compact = true)
    }

    private val layout = DashboardLayout(WidgetType.entries.map { WidgetConfig(it, visible = it != WidgetType.REPORTS) }, DashboardDensity.COMPACT)

    @Test fun dashboardEdit() = shot("dashboard_edit", true) {
        EditGrid(layout, onMoveKey = { _, _ -> }, onMoveBy = { _, _ -> }, onToggleVisible = {}, onToggleSize = {}, onDensity = {})
    }

    /** Accessibility: every card offers "Move … up/down" custom actions and its position. */
    @Test fun editGridHasMoveActions() {
        var moved: Pair<WidgetType, Int>? = null
        setUp(false) { EditGrid(layout, onMoveKey = { _, _ -> }, onMoveBy = { t, d -> moved = t to d }, onToggleVisible = {}, onToggleSize = {}, onDensity = {}) }
        val node = rule.onNodeWithTag("edit_CPU")
        node.assert(SemanticsMatcher("has move actions") { n ->
            n.config.getOrElseNullable(SemanticsActions.CustomActions) { null }?.map { it.label }?.containsAll(listOf("Move CPU up", "Move CPU down")) == true
        })
        val actions = node.fetchSemanticsNode().config[SemanticsActions.CustomActions]
        rule.runOnIdle { actions.first { it.label == "Move CPU up" }.action() }
        assertEquals(WidgetType.CPU to -1, moved)
    }

    // ---------- home-screen widgets ----------

    private val ctx = ApplicationProvider.getApplicationContext<android.app.Application>()

    private fun releaseIcon(px: Int): Bitmap {
        val adaptive = ContextCompat.getDrawable(ctx, R.mipmap.ic_launcher) as AdaptiveIconDrawable
        val bg = ContextCompat.getDrawable(ctx, R.drawable.ic_launcher_background)!!
        val fg = ContextCompat.getDrawable(ctx, R.drawable.ic_launcher_foreground)!!
        check(adaptive.intrinsicWidth > 0)
        val full = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val c = Canvas(full)
        val layer = (px * 1.5f).toInt(); val off = -(layer - px) / 2
        bg.setBounds(off, off, off + layer, off + layer); bg.draw(c)
        fg.setBounds(off, off, off + layer, off + layer); fg.draw(c)
        val out = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val oc = Canvas(out)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        oc.drawRoundRect(0f, 0f, px.toFloat(), px.toFloat(), px * 0.3f, px * 0.3f, p)
        p.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC_IN)
        oc.drawBitmap(full, 0f, 0f, p)
        return out
    }

    private fun inflate(layout: Int, night: Boolean, wDp: Int, hDp: Int): Bitmap {
        val cfg = android.content.res.Configuration(ctx.resources.configuration).apply {
            uiMode = (uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK.inv()) or
                (if (night) android.content.res.Configuration.UI_MODE_NIGHT_YES else android.content.res.Configuration.UI_MODE_NIGHT_NO)
        }
        val themed = ctx.createConfigurationContext(cfg)
        val v = android.view.LayoutInflater.from(themed).inflate(layout, null)
        val d = themed.resources.displayMetrics.density
        val w = (wDp * d).toInt(); val h = (hDp * d).toInt()
        v.findViewById<android.widget.ImageView>(R.id.widget_preview_icon)?.setImageBitmap(releaseIcon((20 * d).toInt()))
        v.measure(android.view.View.MeasureSpec.makeMeasureSpec(w, android.view.View.MeasureSpec.EXACTLY), android.view.View.MeasureSpec.makeMeasureSpec(h, android.view.View.MeasureSpec.EXACTLY))
        v.layout(0, 0, w, h)
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(b))
        return b
    }

    private fun save(b: Bitmap, name: String) = File(dir, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }

    private val previews = listOf(
        Triple("dot", R.layout.widget_preview_dot, 72 to 72),
        Triple("pool", R.layout.widget_preview_pool, 160 to 72),
        Triple("dashboard", R.layout.widget_preview_dashboard, 320 to 160),
        Triple("actions", R.layout.widget_preview_actions, 250 to 90),
    )

    /** res/drawable-nodpi/widget_preview_<name>.png (the picker image before Android 12) are these renders. */
    @Test fun widgetPreviews() {
        previews.forEach { (name, layout, size) ->
            val b = inflate(layout, false, size.first, size.second)
            save(b, "widget_preview_$name")
            assertTrue(android.graphics.Color.alpha(b.getPixel(1, 1)) < 40)
            assertTrue(android.graphics.Color.alpha(b.getPixel(b.width / 2, b.height - 4)) > 250)
        }
        // A home-screen mock with all four (day and night).
        val d = ctx.resources.displayMetrics.density
        val w = (360 * d).toInt(); val h = (420 * d).toInt()
        val sheet = Bitmap.createBitmap(w * 2, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(sheet)
        c.drawPaint(Paint().apply { shader = android.graphics.LinearGradient(0f, 0f, sheet.width.toFloat(), h.toFloat(), android.graphics.Color.parseColor("#FF7B93D6"), android.graphics.Color.parseColor("#FF1D2747"), android.graphics.Shader.TileMode.CLAMP) })
        listOf(false, true).forEachIndexed { col, night ->
            val x = col * w + 20 * d
            c.drawBitmap(inflate(R.layout.widget_preview_dashboard, night, 320, 160), x, 40 * d, null)
            c.drawBitmap(inflate(R.layout.widget_preview_pool, night, 160, 72), x, 220 * d, null)
            c.drawBitmap(inflate(R.layout.widget_preview_dot, night, 72, 72), x + 248 * d, 220 * d, null)
            c.drawBitmap(inflate(R.layout.widget_preview_actions, night, 320, 90), x, 312 * d, null)
        }
        save(sheet, "v1100_widgets")
    }
}
