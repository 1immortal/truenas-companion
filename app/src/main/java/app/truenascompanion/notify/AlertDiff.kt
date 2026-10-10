package app.truenascompanion.notify

import app.truenascompanion.data.model.AlertItem
import kotlinx.serialization.Serializable

/** TrueNAS alert levels (middlewared `alert.base.AlertLevel`), ordered by severity. */
enum class AlertLevel(val rank: Int, val label: String) {
    INFO(1, "Info"),
    NOTICE(2, "Notice"),
    WARNING(3, "Warning"),
    ERROR(4, "Error"),
    CRITICAL(5, "Critical"),
    ALERT(6, "Alert"),
    EMERGENCY(7, "Emergency");

    val group: SeverityGroup
        get() = when {
            rank >= ERROR.rank -> SeverityGroup.CRITICAL
            this == WARNING -> SeverityGroup.WARNING
            else -> SeverityGroup.INFO
        }

    companion object {
        fun parse(value: String?): AlertLevel = entries.firstOrNull { it.name.equals(value?.trim(), ignoreCase = true) } ?: INFO
    }
}

/** One notification channel per group so users can tune sound/vibration in Android settings. */
enum class SeverityGroup { CRITICAL, WARNING, INFO }

/** What we remember about an alert between checks (persisted per server). */
@Serializable
data class SeenAlert(val uuid: String, val level: String, val title: String, val dismissed: Boolean = false)

/**
 * Quiet window in minutes after midnight; may wrap past midnight (e.g. 22:00–07:00).
 * 1.10.0: [days] (ISO 1 = Monday … 7 = Sunday) are the days the window *starts* on, so "Fri 22:00–07:00" also covers
 * Saturday morning. [criticalBreaksThrough]: Error/Critical and more severe still notify during quiet hours.
 */
data class QuietHours(
    val enabled: Boolean = false,
    val startMinute: Int = 22 * 60,
    val endMinute: Int = 7 * 60,
    val days: Set<Int> = ALL_DAYS,
    val criticalBreaksThrough: Boolean = true,
) {
    /** [isoDay] 0 = unknown (days are ignored). */
    fun contains(minuteOfDay: Int, isoDay: Int = 0): Boolean {
        if (!enabled || startMinute == endMinute || days.isEmpty()) return false
        fun on(day: Int) = isoDay == 0 || day in days
        return if (startMinute < endMinute) minuteOfDay in startMinute until endMinute && on(isoDay)
        else (minuteOfDay >= startMinute && on(isoDay)) || (minuteOfDay < endMinute && on(if (isoDay == 1) 7 else isoDay - 1))
    }

    companion object {
        val ALL_DAYS: Set<Int> = (1..7).toSet()
    }
}

data class AlertFilter(
    val minLevel: AlertLevel = AlertLevel.WARNING,
    val notifyOnClear: Boolean = false,
    val quietHours: QuietHours = QuietHours(),
) {
    /** Critical and above come through during quiet hours unless the user turned "critical breaks through" off. */
    fun allows(level: AlertLevel, minuteOfDay: Int, isoDay: Int = 0): Boolean =
        level.rank >= minLevel.rank && !quiet(level, minuteOfDay, isoDay)

    /** True when [level] is held back by quiet hours right now (independent of the minimum level). */
    fun quiet(level: AlertLevel, minuteOfDay: Int, isoDay: Int = 0): Boolean =
        quietHours.contains(minuteOfDay, isoDay) && !(quietHours.criticalBreaksThrough && level.rank >= AlertLevel.CRITICAL.rank)
}

data class AlertDiffResult(
    /** New, non-dismissed alerts that pass the filter: post a notification for each. */
    val toNotify: List<AlertItem>,
    /** Alerts at/above the minimum level that disappeared (only filled when "notify on clear" is on). */
    val cleared: List<SeenAlert>,
    /** Alerts that are gone or were dismissed (on the NAS or elsewhere): remove their notifications. */
    val withdrawn: Set<String>,
    /** New alerts held back by quiet hours. */
    val suppressed: Int,
    /** First check for this server: nothing is notified, the current list becomes the baseline. */
    val isBaseline: Boolean,
    /** State to persist for the next check. */
    val seen: List<SeenAlert>,
)

object AlertDiff {
    /**
     * Compares the current `alert.list` with what was seen last time.
     * [previous] is null on the very first check (baseline: remember everything, notify nothing, so enabling
     * notifications doesn't flood the phone with alerts the user already knows about).
     */
    fun compute(
        previous: List<SeenAlert>?,
        current: List<AlertItem>,
        filter: AlertFilter,
        minuteOfDay: Int,
        isoDay: Int = 0,
        titleOf: (AlertItem) -> String,
    ): AlertDiffResult {
        val seen = current.filter { it.uuid.isNotBlank() }
            .map { SeenAlert(it.uuid, it.level, titleOf(it), it.dismissed) }
        if (previous == null) {
            return AlertDiffResult(emptyList(), emptyList(), emptySet(), 0, isBaseline = true, seen = seen)
        }
        val prev = previous.associateBy { it.uuid }
        val currentIds = current.map { it.uuid }.toSet()

        val fresh = current.filter { it.uuid.isNotBlank() && !it.dismissed && it.uuid !in prev }
        val atLevel = fresh.filter { AlertLevel.parse(it.level).rank >= filter.minLevel.rank }
        val toNotify = atLevel.filter { filter.allows(AlertLevel.parse(it.level), minuteOfDay, isoDay) }
            .sortedByDescending { AlertLevel.parse(it.level).rank }

        val gone = previous.filter { it.uuid !in currentIds }
        val cleared = if (!filter.notifyOnClear) emptyList() else gone.filter {
            !it.dismissed && filter.allows(AlertLevel.parse(it.level), minuteOfDay, isoDay)
        }
        val newlyDismissed = current.filter { it.dismissed && prev[it.uuid]?.dismissed == false }.map { it.uuid }

        return AlertDiffResult(
            toNotify = toNotify,
            cleared = cleared,
            withdrawn = (gone.map { it.uuid } + newlyDismissed).toSet(),
            suppressed = atLevel.size - toNotify.size,
            isBaseline = false,
            seen = seen,
        )
    }

    /** "ZpoolCapacityWarning" -> "Zpool capacity warning" (fallback when the class title is unknown). */
    fun humanize(klass: String?): String? {
        if (klass.isNullOrBlank()) return null
        val words = klass.replace(Regex("([a-z0-9])([A-Z])"), "$1 $2").replace(Regex("([A-Z]+)([A-Z][a-z])"), "$1 $2")
            .replace('_', ' ').trim().split(Regex("\\s+"))
        return words.mapIndexed { i, w -> if (i == 0 || w.length > 1 && w.all { it.isUpperCase() }) w else w.lowercase() }
            .joinToString(" ")
    }

    fun title(alert: AlertItem, classTitles: Map<String, String>): String =
        alert.klass?.let { classTitles[it] } ?: humanize(alert.klass) ?: "${AlertLevel.parse(alert.level).label} alert"
}
