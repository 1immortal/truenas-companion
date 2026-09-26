package app.truenascompanion.data.model

import kotlinx.serialization.Serializable

enum class WidgetSize { HALF, FULL }

/** Cards that render `reporting.realtime` data. */
val LIVE_TYPES = setOf(WidgetType.SYSTEM, WidgetType.CPU, WidgetType.MEMORY, WidgetType.TEMPERATURE, WidgetType.NETWORK)

enum class WidgetType(val title: String, val defaultSize: WidgetSize, val shortTitle: String = title) {
    SYSTEM("System", WidgetSize.FULL),
    CPU("CPU", WidgetSize.HALF),
    MEMORY("Memory", WidgetSize.HALF),
    TEMPERATURE("Temperature", WidgetSize.HALF, "Temps"),
    NETWORK("Network", WidgetSize.HALF),
    POOLS("Storage pools", WidgetSize.FULL, "Pools"),
    APPS("Apps", WidgetSize.HALF),
    ALERTS("Alerts", WidgetSize.HALF),
    PROTECTION("Data protection", WidgetSize.FULL, "Protection"),
}

@Serializable
data class WidgetConfig(
    val type: WidgetType,
    val visible: Boolean = true,
    val size: WidgetSize = type.defaultSize,
)

@Serializable
data class DashboardLayout(val widgets: List<WidgetConfig>) {

    /** True when a visible card renders realtime stats, i.e. the live subscription is worth keeping open. */
    val needsLiveStats: Boolean get() = widgets.any { it.visible && it.type in LIVE_TYPES }

    /** Drops duplicates/unknowns and appends widget types added in newer app versions. */
    fun normalized(): DashboardLayout {
        val seen = LinkedHashMap<WidgetType, WidgetConfig>()
        widgets.forEach { if (it.type !in seen) seen[it.type] = it }
        WidgetType.entries.forEach { if (it !in seen) seen[it] = WidgetConfig(it) }
        return DashboardLayout(seen.values.toList())
    }

    fun move(from: Int, to: Int): DashboardLayout {
        if (from !in widgets.indices || to !in widgets.indices) return this
        val list = widgets.toMutableList()
        list.add(to, list.removeAt(from))
        return copy(widgets = list)
    }

    fun update(type: WidgetType, transform: (WidgetConfig) -> WidgetConfig): DashboardLayout =
        copy(widgets = widgets.map { if (it.type == type) transform(it) else it })

    val visibleWidgets: List<WidgetConfig> get() = widgets.filter { it.visible }

    companion object {
        val DEFAULT = DashboardLayout(WidgetType.entries.map { WidgetConfig(it) })
    }
}
