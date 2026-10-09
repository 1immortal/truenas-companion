package app.truenascompanion.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
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
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import app.truenascompanion.MainActivity
import app.truenascompanion.data.model.Health

/** Home-screen widget: active server name, pool health, open alert count, optional route chip. */
class NasStatusWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val snap = WidgetStore.load(context)
        provideContent {
            GlanceTheme {
                WidgetContent(snap)
            }
        }
    }
}

@Composable
private fun WidgetContent(snap: WidgetSnapshot) {
    val bg = ColorProvider(day = Color(0xFF0B1B33), night = Color(0xFF0B1B33))
    val on = ColorProvider(day = Color(0xFFE8F1FF), night = Color(0xFFE8F1FF))
    val muted = ColorProvider(day = Color(0xFF9BB4D0), night = Color(0xFF9BB4D0))
    val accent = when (snap.poolHealth) {
        Health.HEALTHY -> ColorProvider(day = Color(0xFF3DDC97), night = Color(0xFF3DDC97))
        Health.WARNING -> ColorProvider(day = Color(0xFFFFB020), night = Color(0xFFFFB020))
        Health.CRITICAL -> ColorProvider(day = Color(0xFFFF5C5C), night = Color(0xFFFF5C5C))
        Health.UNKNOWN -> muted
    }
    Column(
        modifier = GlanceModifier.fillMaxSize().background(bg).padding(14.dp)
            .clickable(actionStartActivity<MainActivity>()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            snap.serverName ?: "YTN",
            style = TextStyle(color = on, fontSize = 15.sp, fontWeight = FontWeight.Bold),
            maxLines = 1,
        )
        Spacer(GlanceModifier.height(6.dp))
        if (snap.error != null) {
            Text(snap.error, style = TextStyle(color = ColorProvider(day = Color(0xFFFF8A80), night = Color(0xFFFF8A80)), fontSize = 13.sp), maxLines = 3)
        } else {
            Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Pools", style = TextStyle(color = muted, fontSize = 12.sp))
                Spacer(GlanceModifier.width(8.dp))
                Text(snap.poolLabel, style = TextStyle(color = accent, fontSize = 14.sp, fontWeight = FontWeight.Medium), maxLines = 1)
            }
            Spacer(GlanceModifier.height(4.dp))
            Text(
                when {
                    snap.alertCount == 0 -> "No open alerts"
                    snap.alertCount == 1 -> "1 open alert"
                    else -> "${snap.alertCount} open alerts"
                },
                style = TextStyle(
                    color = if (snap.alertCount > 0) ColorProvider(day = Color(0xFFFFB020), night = Color(0xFFFFB020)) else muted,
                    fontSize = 13.sp,
                ),
                maxLines = 1,
            )
            snap.routeLabel?.let {
                Spacer(GlanceModifier.height(6.dp))
                Text(it, style = TextStyle(color = muted, fontSize = 11.sp), maxLines = 1)
            }
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
        WidgetRefreshWorker.sync(context, placed = false)
    }
}
