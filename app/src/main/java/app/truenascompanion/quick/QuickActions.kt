package app.truenascompanion.quick

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.service.quicksettings.TileService
import androidx.core.content.edit
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import app.truenascompanion.MainActivity
import app.truenascompanion.R
import app.truenascompanion.notify.DeepLink

/**
 * Quick actions (1.2.0): launcher shortcuts and the Quick Settings action tile. They only open the app at the right
 * place; the app then asks for confirmation (and the fingerprint when the app lock is on) before doing anything.
 */
enum class QuickAction(val id: String, val destination: String, val shortLabel: String, val longLabel: String, val icon: Int, val tileIcon: Int) {
    SHELL("shell", DeepLink.DEST_SHELL, "Shell", "Open shell", R.drawable.ic_shortcut_shell, R.drawable.ic_tile_shell),
    ALERTS("alerts", DeepLink.DEST_ALERTS, "Alerts", "Show alerts", R.drawable.ic_shortcut_alerts, R.drawable.ic_tile_alerts),
    RESTART_APP("restart_app", DeepLink.DEST_RESTART_APP, "Restart app…", "Restart an app…", R.drawable.ic_shortcut_restart, R.drawable.ic_tile_restart),
    SCRUB_POOL("scrub_pool", DeepLink.DEST_SCRUB_POOL, "Scrub pool…", "Scrub a pool…", R.drawable.ic_shortcut_scrub, R.drawable.ic_tile_scrub);

    companion object {
        fun byId(id: String?) = entries.firstOrNull { it.id == id }
        fun byDestination(d: String?) = entries.firstOrNull { it.destination == d }
    }
}

object QuickActions {
    private const val PREFS = "quick_actions"
    private const val KEY_TILE = "tile_action"
    /** Dynamic shortcuts kept next to the four static ones (launchers show about five in total). */
    const val MAX_DYNAMIC = 2

    fun intent(context: Context, destination: String, arg: String? = null, serverId: String? = null): Intent =
        Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(DeepLink.EXTRA_DESTINATION, destination)
            arg?.let { putExtra(DeepLink.EXTRA_ARG, it) }
            serverId?.let { putExtra(DeepLink.EXTRA_SERVER_ID, it) }
        }

    /** Action of the configurable Quick Settings tile (plain SharedPreferences so the tile can read it synchronously). */
    fun tileAction(context: Context): QuickAction =
        QuickAction.byId(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_TILE, null)) ?: QuickAction.ALERTS

    fun setTileAction(context: Context, action: QuickAction) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putString(KEY_TILE, action.id) }
        runCatching { TileService.requestListeningState(context, ComponentName(context, ActionTileService::class.java)) }
    }

    fun dynamicId(action: QuickAction, serverId: String, arg: String) = "recent:${action.id}:$serverId:$arg"

    /**
     * After a restart or scrub the target becomes a dynamic shortcut ("Restart nextcloud…"). Tapping it opens the same
     * confirmation as the static shortcut, with the target preselected.
     */
    fun pushRecent(context: Context, action: QuickAction, serverId: String, arg: String) = runCatching {
        val verb = if (action == QuickAction.RESTART_APP) "Restart" else "Scrub"
        val info = ShortcutInfoCompat.Builder(context, dynamicId(action, serverId, arg))
            .setShortLabel("$verb $arg…")
            .setLongLabel("$verb $arg…")
            .setIcon(IconCompat.createWithResource(context, action.icon))
            .setIntent(intent(context, action.destination, arg, serverId))
            .setRank(0)
            .build()
        ShortcutManagerCompat.pushDynamicShortcut(context, info)
        val dynamic = ShortcutManagerCompat.getDynamicShortcuts(context).filter { it.id.startsWith("recent:") }
        if (dynamic.size > MAX_DYNAMIC) {
            ShortcutManagerCompat.removeDynamicShortcuts(context, dynamic.sortedBy { it.rank }.drop(MAX_DYNAMIC).map { it.id })
        }
        ShortcutManagerCompat.reportShortcutUsed(context, info.id)
    }

    /** Drops dynamic shortcuts of servers that were removed. */
    fun pruneShortcuts(context: Context, serverIds: Set<String>) = runCatching {
        val stale = ShortcutManagerCompat.getDynamicShortcuts(context).map { it.id }
            .filter { it.startsWith("recent:") && it.split(':').getOrNull(2) !in serverIds }
        if (stale.isNotEmpty()) ShortcutManagerCompat.removeDynamicShortcuts(context, stale)
    }

    fun reportUsed(context: Context, action: QuickAction) = runCatching { ShortcutManagerCompat.reportShortcutUsed(context, action.id) }
}
