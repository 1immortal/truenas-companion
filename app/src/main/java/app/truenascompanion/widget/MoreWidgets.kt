package app.truenascompanion.widget

import android.content.Context
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ColumnScope
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import app.truenascompanion.MainActivity
import app.truenascompanion.R
import app.truenascompanion.data.model.Health
import app.truenascompanion.notify.DeepLink
import app.truenascompanion.quick.QuickAction
import app.truenascompanion.quick.QuickActions

/*
 * 1.10.0: more widget sizes. All of them read the one cached WidgetSnapshot written by WidgetRefreshWorker, so adding
 * widgets never adds network calls or wakeups (the shared job runs only while at least one widget is placed).
 * Widgets never run anything on the NAS: the actions widget's quick action opens the app, where restart / scrub still
 * need the app unlock and a confirmation.
 */

/** Pure helpers shared by the widgets (unit tested). */
internal object WidgetModel {
    /** Overall tone: error > worst pool > alerts. */
    fun tone(s: WidgetSnapshot): Health = when {
        s.serverName == null -> Health.UNKNOWN
        s.error != null -> Health.CRITICAL
        s.poolHealth != Health.HEALTHY -> s.poolHealth
        s.alertCount > 0 -> Health.WARNING
        else -> Health.HEALTHY
    }

    fun dotDescription(s: WidgetSnapshot): String = when {
        s.serverName == null -> "YTN: no server"
        s.error != null -> "${s.serverName}: ${s.error}"
        else -> "${s.serverName}: pools ${s.poolLabel.lowercase()}, ${WidgetText.alerts(s.alertCount).lowercase()}"
    }

    /** Fullest pool first: the 2×1 widget shows the one most likely to need attention. */
    fun fullest(s: WidgetSnapshot): WidgetPool? = s.pools.maxByOrNull { it.usedPercent }

    fun usageTone(percent: Int): Health = when {
        percent >= 90 -> Health.CRITICAL
        percent >= 80 -> Health.WARNING
        else -> Health.HEALTHY
    }

    fun apps(s: WidgetSnapshot): String? {
        val total = s.appsTotal ?: return null
        return if (total == 0) "No apps" else "${s.appsRunning ?: 0} of $total apps running"
    }
}

private fun frame(compact: Boolean = false) = GlanceModifier.fillMaxSize().appWidgetBackground().background(WidgetColors.bg)
    .then(if (Build.VERSION.SDK_INT >= 31) GlanceModifier.cornerRadius(android.R.dimen.system_app_widget_background_radius) else GlanceModifier.cornerRadius(20.dp))
    .padding(horizontal = if (compact) 8.dp else 14.dp, vertical = if (compact) 6.dp else 10.dp)

private fun openApp(context: Context, destination: String? = null) =
    if (destination == null) actionStartActivity<MainActivity>() else androidx.glance.appwidget.action.actionStartActivity(QuickActions.intent(context, destination))

/** Shared receiver behaviour: the refresh job runs only while at least one YTN widget is placed. */
abstract class YtnWidgetReceiver : GlanceAppWidgetReceiver() {
    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        WidgetRefreshWorker.sync(context, placed = true)
        WidgetRefreshWorker.refreshNow(context, placed = true)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        WidgetRefreshWorker.sync(context)
    }
}

// ---- 1×1 status dot ----

class StatusDotWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Single
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val snap = WidgetStore.load(context)
        provideContent { StatusDot(snap) }
    }
}

@Composable
internal fun StatusDot(snap: WidgetSnapshot) {
    val context = LocalContext.current
    val tone = WidgetColors.of(WidgetModel.tone(snap))
    Column(
        modifier = frame(compact = true).clickable(openApp(context))
            .semantics { contentDescription = WidgetModel.dotDescription(snap) },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(GlanceModifier.size(28.dp).cornerRadius(14.dp).background(tone)) {}
        Spacer(GlanceModifier.height(4.dp))
        Text(
            if (snap.serverName != null && snap.error == null && snap.alertCount > 0) "${snap.alertCount}" else (snap.serverName ?: "YTN"),
            style = TextStyle(color = WidgetColors.on, fontSize = 11.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center),
            maxLines = 1,
        )
    }
}

class StatusDotWidgetReceiver : YtnWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = StatusDotWidget()
}

// ---- 2×1 pool usage ----

class PoolUsageWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Single
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val snap = WidgetStore.load(context)
        provideContent { PoolUsage(snap) }
    }
}

@Composable
internal fun PoolUsage(snap: WidgetSnapshot) {
    val context = LocalContext.current
    val pool = WidgetModel.fullest(snap)
    Column(
        modifier = frame().clickable(openApp(context, DeepLink.DEST_STORAGE)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (pool == null || snap.error != null) {
            Text(snap.serverName ?: "YTN", style = TextStyle(color = WidgetColors.on, fontSize = 14.sp, fontWeight = FontWeight.Bold), maxLines = 1)
            Text(WidgetText.status(snap).takeIf { snap.error != null || snap.serverName == null } ?: "No pools", style = TextStyle(color = WidgetColors.muted, fontSize = 12.sp), maxLines = 1)
        } else {
            PoolLine(pool)
            if (snap.pools.size > 1) {
                Text("+${snap.pools.size - 1} more on ${snap.serverName}", style = TextStyle(color = WidgetColors.muted, fontSize = 11.sp), maxLines = 1)
            } else snap.serverName?.let { Text(it, style = TextStyle(color = WidgetColors.muted, fontSize = 11.sp), maxLines = 1) }
        }
    }
}

@Composable
private fun ColumnScope.PoolLine(pool: WidgetPool) {
    val tone = WidgetColors.of(if (pool.health != Health.HEALTHY) pool.health else WidgetModel.usageTone(pool.usedPercent))
    Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(pool.name, style = TextStyle(color = WidgetColors.on, fontSize = 14.sp, fontWeight = FontWeight.Bold), maxLines = 1, modifier = GlanceModifier.defaultWeight())
        Text("${pool.usedPercent}%", style = TextStyle(color = tone, fontSize = 14.sp, fontWeight = FontWeight.Bold), maxLines = 1)
    }
    Spacer(GlanceModifier.height(4.dp))
    LinearProgressIndicator(
        progress = pool.usedPercent / 100f,
        modifier = GlanceModifier.fillMaxWidth().height(6.dp),
        color = tone,
        backgroundColor = WidgetColors.track,
    )
    Spacer(GlanceModifier.height(4.dp))
}

class PoolUsageWidgetReceiver : YtnWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = PoolUsageWidget()
}

// ---- 4×2 dashboard ----

class NasDashboardWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Single
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val snap = WidgetStore.load(context)
        provideContent { DashboardGlance(snap) }
    }
}

@Composable
internal fun DashboardGlance(snap: WidgetSnapshot) {
    val context = LocalContext.current
    val tall = LocalSize.current.height >= 150.dp
    Column(modifier = frame().clickable(openApp(context))) {
        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Image(ImageProvider(R.mipmap.ic_launcher), contentDescription = null, modifier = GlanceModifier.size(20.dp))
            Spacer(GlanceModifier.width(8.dp))
            Text(snap.serverName ?: "YTN", style = TextStyle(color = WidgetColors.on, fontSize = 15.sp, fontWeight = FontWeight.Bold), maxLines = 1, modifier = GlanceModifier.defaultWeight())
            Text("● ", style = TextStyle(color = WidgetColors.of(WidgetModel.tone(snap)), fontSize = 14.sp))
            snap.routeLabel?.let { Text(it, style = TextStyle(color = WidgetColors.muted, fontSize = 12.sp), maxLines = 1) }
        }
        Spacer(GlanceModifier.height(6.dp))
        if (snap.serverName == null || snap.error != null) {
            Text(WidgetText.status(snap), style = TextStyle(color = WidgetColors.critical, fontSize = 14.sp), maxLines = 2)
        } else {
            Row(GlanceModifier.fillMaxWidth()) {
                Text(
                    WidgetText.alerts(snap.alertCount),
                    style = TextStyle(color = if (snap.alertCount > 0) WidgetColors.warning else WidgetColors.muted, fontSize = 13.sp),
                    maxLines = 1, modifier = GlanceModifier.defaultWeight().clickable(openApp(context, DeepLink.DEST_ALERTS)),
                )
                WidgetModel.apps(snap)?.let { Text(it, style = TextStyle(color = WidgetColors.muted, fontSize = 13.sp), maxLines = 1) }
            }
            Spacer(GlanceModifier.height(6.dp))
            val shown = snap.pools.sortedByDescending { it.usedPercent }.take(if (tall) 3 else 2)
            if (shown.isEmpty()) Text("No pools", style = TextStyle(color = WidgetColors.muted, fontSize = 13.sp))
            shown.forEach { PoolLine(it) }
        }
        WidgetText.updated(snap.updatedAt)?.let {
            Spacer(GlanceModifier.defaultWeight())
            Text(it, style = TextStyle(color = WidgetColors.muted, fontSize = 11.sp), maxLines = 1)
        }
    }
}

class DashboardWidgetReceiver : YtnWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = NasDashboardWidget()
}

// ---- actions ----

/** Refresh button: re-runs the shared widget job now (read-only). */
class RefreshWidgetsAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        WidgetRefreshWorker.refreshNow(context)
    }
}

class ActionsWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Single
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val snap = WidgetStore.load(context)
        val quick = QuickActions.tileAction(context)
        provideContent { ActionsGlance(snap, quick) }
    }
}

@Composable
internal fun ActionsGlance(snap: WidgetSnapshot, quick: QuickAction) {
    val context = LocalContext.current
    Column(modifier = frame(compact = true), verticalAlignment = Alignment.CenterVertically) {
        Row(GlanceModifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("● ", style = TextStyle(color = WidgetColors.of(WidgetModel.tone(snap)), fontSize = 12.sp))
            Text(snap.serverName ?: "YTN", style = TextStyle(color = WidgetColors.on, fontSize = 13.sp, fontWeight = FontWeight.Bold), maxLines = 1, modifier = GlanceModifier.defaultWeight())
            WidgetText.updated(snap.updatedAt)?.let { Text(it, style = TextStyle(color = WidgetColors.muted, fontSize = 11.sp), maxLines = 1) }
        }
        Spacer(GlanceModifier.height(4.dp))
        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            ActionButton(R.drawable.ic_widget_refresh, "Refresh", GlanceModifier.clickable(actionRunCallback<RefreshWidgetsAction>()))
            Spacer(GlanceModifier.width(6.dp))
            ActionButton(
                R.drawable.ic_shortcut_alerts,
                if (snap.alertCount > 0) "Alerts (${snap.alertCount})" else "Alerts",
                GlanceModifier.clickable(openApp(context, DeepLink.DEST_ALERTS)),
            )
            if (quick != QuickAction.ALERTS) {
                Spacer(GlanceModifier.width(6.dp))
                // Opens the app's confirmation (and the app lock): nothing runs from the home screen.
                ActionButton(quick.icon, quick.shortLabel, GlanceModifier.clickable(openApp(context, quick.destination)))
            }
        }
    }
}

@Composable
private fun androidx.glance.layout.RowScope.ActionButton(icon: Int, label: String, modifier: GlanceModifier) {
    Column(
        modifier = modifier.defaultWeight().cornerRadius(14.dp).background(WidgetColors.track).padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(ImageProvider(icon), contentDescription = null, modifier = GlanceModifier.size(22.dp))
        Text(label, style = TextStyle(color = WidgetColors.on, fontSize = 11.sp, textAlign = TextAlign.Center), maxLines = 1)
    }
}

class ActionsWidgetReceiver : YtnWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ActionsWidget()
}
