package app.truenascompanion.widget

import android.content.Context
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider as GlanceColor
import app.truenascompanion.MainActivity
import app.truenascompanion.R
import app.truenascompanion.data.model.Health
import java.text.DateFormat
import java.util.Date

/**
 * Home-screen widget (1.8.0 redesign): the app's own light/dark palette and status colors, rounded corners, a header
 * with the YTN mark and server name, one big status line, alerts, route and "Updated 13:40". Compact at 2×1.
 */
class NasStatusWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(COMPACT, REGULAR))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val snap = WidgetStore.load(context)
        provideContent { WidgetContent(snap) }
    }

    companion object {
        val COMPACT = DpSize(110.dp, 48.dp)
        val REGULAR = DpSize(180.dp, 110.dp)
    }
}

/** Widget colors = the app's theme (light / dark follow the system), same values as StatusColors. */
internal object WidgetColors {
    val track = c(0xFFDCE3F7, 0xFF1E2A4F)
    private fun c(day: Long, night: Long) = ColorProvider(day = Color(day), night = Color(night))
    val bg = c(0xFFF3F6FF, 0xFF0B1430)
    val on = c(0xFF0E1A3A, 0xFFE6ECFF)
    val muted = c(0xFF4A5680, 0xFFA9B6DA)
    val healthy = c(0xFF1B7F4B, 0xFF6EE7A8)
    val warning = c(0xFF8A5A00, 0xFFFFCC66)
    val critical = c(0xFFB3261E, 0xFFFF9A91)
    val info = c(0xFF2F5BEA, 0xFFA9BCFF)
    fun of(h: Health): GlanceColor = when (h) {
        Health.HEALTHY -> healthy
        Health.INFO -> info
        Health.WARNING -> warning
        Health.CRITICAL -> critical
        Health.UNKNOWN -> muted
    }
}

/** Text lines of the widget (pure, unit tested). */
internal object WidgetText {
    fun status(s: WidgetSnapshot): String = when {
        s.serverName == null -> "Open YTN to add a server"
        s.error != null -> s.error
        else -> "Pools: ${s.poolLabel}"
    }
    fun alerts(count: Int): String = when (count) { 0 -> "No open alerts"; 1 -> "1 open alert"; else -> "$count open alerts" }
    fun updated(at: Long, format: (Long) -> String = { DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it)) }): String? =
        at.takeIf { it > 0 }?.let { "Updated ${format(it)}" }
}

@Composable
private fun WidgetContent(snap: WidgetSnapshot) {
    val compact = LocalSize.current.height < 100.dp
    val tone = when {
        snap.error != null -> WidgetColors.critical
        else -> WidgetColors.of(snap.poolHealth)
    }
    Column(
        modifier = GlanceModifier.fillMaxSize().appWidgetBackground().background(WidgetColors.bg)
            .then(if (Build.VERSION.SDK_INT >= 31) GlanceModifier.cornerRadius(android.R.dimen.system_app_widget_background_radius) else GlanceModifier.cornerRadius(20.dp))
            .padding(horizontal = 14.dp, vertical = if (compact) 8.dp else 12.dp)
            .clickable(actionStartActivity<MainActivity>()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Image(ImageProvider(R.mipmap.ic_launcher), contentDescription = null, modifier = GlanceModifier.size(if (compact) 18.dp else 22.dp))
            Spacer(GlanceModifier.width(8.dp))
            Text(
                snap.serverName ?: "YTN",
                style = TextStyle(color = WidgetColors.on, fontSize = 15.sp, fontWeight = FontWeight.Bold),
                maxLines = 1, modifier = GlanceModifier.defaultWeight(),
            )
            if (!compact) snap.routeLabel?.let { Text(it, style = TextStyle(color = WidgetColors.muted, fontSize = 12.sp), maxLines = 1) }
        }
        Spacer(GlanceModifier.height(if (compact) 2.dp else 8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("● ", style = TextStyle(color = tone, fontSize = if (compact) 13.sp else 16.sp))
            Text(
                WidgetText.status(snap),
                style = TextStyle(color = tone, fontSize = if (compact) 13.sp else 17.sp, fontWeight = FontWeight.Medium),
                maxLines = if (compact) 1 else 2,
            )
        }
        if (!compact && snap.error == null && snap.serverName != null) {
            Spacer(GlanceModifier.height(4.dp))
            Text(
                WidgetText.alerts(snap.alertCount),
                style = TextStyle(color = if (snap.alertCount > 0) WidgetColors.warning else WidgetColors.muted, fontSize = 13.sp),
                maxLines = 1,
            )
        }
        if (!compact) WidgetText.updated(snap.updatedAt)?.let {
            Spacer(GlanceModifier.height(6.dp))
            Text(it, style = TextStyle(color = WidgetColors.muted, fontSize = 12.sp), maxLines = 1)
        }
    }
}

class NasStatusWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = NasStatusWidget()

    /** 1.7.1: the refresh job runs only while at least one widget is placed. */
    override fun onEnabled(context: android.content.Context) {
        super.onEnabled(context)
        WidgetRefreshWorker.sync(context, placed = true)
        WidgetRefreshWorker.refreshNow(context, placed = true)
    }

    override fun onDisabled(context: android.content.Context) {
        super.onDisabled(context)
        // 1.10.0: other widgets may still be placed; they share the refresh job.
        WidgetRefreshWorker.sync(context)
    }
}
