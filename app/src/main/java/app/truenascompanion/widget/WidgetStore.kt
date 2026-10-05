package app.truenascompanion.widget

import android.content.Context
import app.truenascompanion.data.model.Health
import org.json.JSONObject

data class WidgetSnapshot(
    val serverName: String? = null,
    val poolHealth: Health = Health.UNKNOWN,
    val poolLabel: String = "—",
    val alertCount: Int = 0,
    val routeLabel: String? = null,
    val error: String? = null,
    val updatedAt: Long = 0L,
)

/** Tiny SharedPreferences cache the Glance widget reads; written by [WidgetRefreshWorker]. */
object WidgetStore {
    private const val PREFS = "nas_status_widget"
    private const val KEY = "snapshot"

    fun load(context: Context): WidgetSnapshot {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return WidgetSnapshot(error = "Add a server in the app, then the widget will refresh.")
        return runCatching {
            val o = JSONObject(raw)
            WidgetSnapshot(
                serverName = o.optString("serverName").ifBlank { null },
                poolHealth = runCatching { Health.valueOf(o.optString("poolHealth", "UNKNOWN")) }.getOrDefault(Health.UNKNOWN),
                poolLabel = o.optString("poolLabel", "—"),
                alertCount = o.optInt("alertCount", 0),
                routeLabel = o.optString("routeLabel").ifBlank { null },
                error = o.optString("error").ifBlank { null },
                updatedAt = o.optLong("updatedAt", 0L),
            )
        }.getOrElse { WidgetSnapshot(error = "Couldn't read widget data.") }
    }

    fun save(context: Context, snap: WidgetSnapshot) {
        val o = JSONObject()
            .put("serverName", snap.serverName)
            .put("poolHealth", snap.poolHealth.name)
            .put("poolLabel", snap.poolLabel)
            .put("alertCount", snap.alertCount)
            .put("routeLabel", snap.routeLabel)
            .put("error", snap.error)
            .put("updatedAt", snap.updatedAt)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, o.toString()).apply()
    }
}
