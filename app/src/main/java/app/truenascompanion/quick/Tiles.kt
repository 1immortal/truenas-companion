package app.truenascompanion.quick

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import app.truenascompanion.R
import app.truenascompanion.data.model.Health
import app.truenascompanion.notify.DeepLink
import app.truenascompanion.widget.WidgetSnapshot
import app.truenascompanion.widget.WidgetStore

/** Opens the app from a tile, collapsing the shade (PendingIntent form on Android 14+). */
@SuppressLint("StartActivityAndCollapseDeprecated")
internal fun TileService.openApp(intent: Intent, requestCode: Int) {
    val run = {
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, requestCode, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
    if (isLocked) unlockAndRun { run() } else run()
}

/** What the status tile shows, from the widget snapshot (no network access of its own). */
object StatusTileText {
    data class Display(val label: String, val subtitle: String, val active: Boolean)

    fun of(s: WidgetSnapshot): Display {
        val name = s.serverName ?: return Display("TrueNAS", "Not set up", false)
        // The widget refresh stores errors without health data.
        if (s.error != null) return Display(name, if (s.error.contains("Sign in", ignoreCase = true)) "Sign in needed" else "Offline", false)
        val health = when (s.poolHealth) {
            Health.HEALTHY -> "Healthy"
            Health.WARNING -> "Warning"
            Health.CRITICAL -> "Degraded"
            Health.UNKNOWN -> "Unknown"
        }
        val alerts = when (s.alertCount) { 0 -> ""; 1 -> " · 1 alert"; else -> " · ${s.alertCount} alerts" }
        return Display(name, health + alerts, s.poolHealth == Health.HEALTHY && s.alertCount == 0)
    }
}

/**
 * Server status tile (1.2.0): health and alert count from the last widget refresh, refreshed when the widget data
 * changes. Tapping it opens the app.
 */
class StatusTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        val tile = qsTile ?: return
        val d = StatusTileText.of(WidgetStore.load(this))
        tile.label = d.label
        if (Build.VERSION.SDK_INT >= 29) tile.subtitle = d.subtitle else tile.label = "${d.label}: ${d.subtitle}"
        tile.contentDescription = "${d.label}, ${d.subtitle}"
        tile.state = if (d.active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.icon = Icon.createWithResource(this, R.drawable.ic_tile_status)
        tile.updateTile()
    }

    override fun onClick() {
        super.onClick()
        openApp(QuickActions.intent(this, DeepLink.DEST_DASHBOARD), 7101)
    }
}

/** Configurable action tile (1.2.0): opens the app at the chosen quick action, which then asks for confirmation. */
class ActionTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        val tile = qsTile ?: return
        val a = QuickActions.tileAction(this)
        tile.label = a.longLabel.removeSuffix("…")
        if (Build.VERSION.SDK_INT >= 29) tile.subtitle = "TrueNAS"
        tile.contentDescription = "TrueNAS: ${a.longLabel}"
        tile.state = Tile.STATE_INACTIVE
        tile.icon = Icon.createWithResource(this, a.tileIcon)
        tile.updateTile()
    }

    override fun onClick() {
        super.onClick()
        val a = QuickActions.tileAction(this)
        QuickActions.reportUsed(this, a)
        openApp(QuickActions.intent(this, a.destination), 7102)
    }
}
