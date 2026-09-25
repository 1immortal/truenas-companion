package app.truenascompanion.data.model

import kotlinx.serialization.Serializable

enum class WidgetSize { HALF, FULL }

enum class WidgetType(val title: String, val defaultSize: WidgetSize) {
    SYSTEM("System", WidgetSize.FULL),
    CPU("CPU", WidgetSize.HALF),
    MEMORY("Memory", WidgetSize.HALF),
    TEMPERATURE("Temperature", WidgetSize.HALF),
    NETWORK("Network", WidgetSize.HALF),
    POOLS("Storage pools", WidgetSize.FULL),
    APPS("Apps", WidgetSize.HALF),
    ALERTS("Alerts", WidgetSize.HALF),
}

@Serializable
data class WidgetConfig(
    val type: WidgetType,
    val visible: Boolean = true,
    val size: WidgetSize = type.defaultSize,
)

@Serializable
data class DashboardLayout(val widgets: List<WidgetConfig>) {

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
