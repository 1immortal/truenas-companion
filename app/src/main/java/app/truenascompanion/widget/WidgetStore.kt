package app.truenascompanion.widget

import androidx.core.content.edit
import android.content.Context
import app.truenascompanion.data.model.Health
import org.json.JSONObject

/** 1.10.0: one pool on the pool-usage and dashboard widgets. */
data class WidgetPool(val name: String, val usedPercent: Int, val health: Health)

data class WidgetSnapshot(
    val serverName: String? = null,
    val poolHealth: Health = Health.UNKNOWN,
    val poolLabel: String = "—",
    val alertCount: Int = 0,
    val routeLabel: String? = null,
    val error: String? = null,
    val updatedAt: Long = 0L,
    val pools: List<WidgetPool> = emptyList(),
    /** Apps running / installed (null when not loaded: only the dashboard widget needs them). */
    val appsRunning: Int? = null,
    val appsTotal: Int? = null,
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
                pools = o.optJSONArray("pools")?.let { a ->
                    (0 until a.length()).mapNotNull { i ->
                        val p = a.optJSONObject(i) ?: return@mapNotNull null
                        WidgetPool(p.optString("name"), p.optInt("used"), runCatching { Health.valueOf(p.optString("health", "UNKNOWN")) }.getOrDefault(Health.UNKNOWN))
                    }
                } ?: emptyList(),
                appsRunning = if (o.has("appsRunning")) o.optInt("appsRunning") else null,
                appsTotal = if (o.has("appsTotal")) o.optInt("appsTotal") else null,
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
            .put("pools", org.json.JSONArray().apply { snap.pools.forEach { p -> put(JSONObject().put("name", p.name).put("used", p.usedPercent).put("health", p.health.name)) } })
        snap.appsRunning?.let { o.put("appsRunning", it) }
        snap.appsTotal?.let { o.put("appsTotal", it) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putString(KEY, o.toString()) }
        // 1.2.0: the status tile shows the same data; ask the system to refresh it if it's in the shade.
        runCatching {
            android.service.quicksettings.TileService.requestListeningState(context, android.content.ComponentName(context, app.truenascompanion.quick.StatusTileService::class.java))
        }
    }
}
