package app.truenascompanion.data.protection

import app.truenascompanion.data.model.CronSchedule

/** Schedule presets offered in the task editors (same cron as the TrueNAS web UI presets). */
enum class SchedulePreset(val label: String, val schedule: CronSchedule?) {
    HOURLY("Hourly", CronSchedule(minute = "0", hour = "*")),
    DAILY("Daily", CronSchedule(minute = "0", hour = "0")),
    WEEKLY("Weekly", CronSchedule(minute = "0", hour = "0", dow = "sun")),
    MONTHLY("Monthly", CronSchedule(minute = "0", hour = "0", dom = "1")),
    CUSTOM("Custom", null),
}

object Schedules {
    private const val HOUR = 3_600_000L
    private const val DAY = 24 * HOUR
    private val DAYS = listOf("Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday")
    private val DOW_NAMES = mapOf("sun" to 0, "mon" to 1, "tue" to 2, "wed" to 3, "thu" to 4, "fri" to 5, "sat" to 6)
    private val FIELD = Regex("""^[0-9a-zA-Z*/,\-]+$""")

    private fun num(v: String) = v.trim().toIntOrNull()

    /** Which preset a schedule matches; presets keep their own time (e.g. daily at 03:00). Ignores the begin/end window. */
    fun presetOf(s: CronSchedule): SchedulePreset {
        val m = num(s.minute); val h = num(s.hour); val dom = s.dom.trim(); val dow = s.dow.trim().lowercase()
        if (s.month.trim() != "*" || m == null) return SchedulePreset.CUSTOM
        return when {
            s.hour.trim() == "*" && dom == "*" && dow == "*" -> SchedulePreset.HOURLY
            h == null -> SchedulePreset.CUSTOM
            dom == "*" && dow == "*" -> SchedulePreset.DAILY
            dom == "*" && dayIndex(dow) != null -> SchedulePreset.WEEKLY
            num(dom) != null && dow == "*" -> SchedulePreset.MONTHLY
            else -> SchedulePreset.CUSTOM
        }
    }

    fun dayIndex(dow: String): Int? { val d = dow.trim().lowercase(); return DOW_NAMES[d] ?: d.toIntOrNull()?.takeIf { it in 0..7 }?.let { it % 7 } }

    val dayNames: List<String> get() = DAYS
    val dayKeys = listOf("sun", "mon", "tue", "wed", "thu", "fri", "sat")

    private fun time(s: CronSchedule): String? {
        val h = s.hour.trim().toIntOrNull() ?: return null
        val m = s.minute.trim().toIntOrNull() ?: return null
        return "%02d:%02d".format(h, m)
    }

    private fun dayName(dow: String): String? = dayIndex(dow)?.let { DAYS.getOrNull(it) }

    /** Short human description, e.g. "Every hour", "Daily at 03:00", "Sundays at 00:00". */
    fun describe(s: CronSchedule): String {
        val t = time(s)
        val everyHour = s.hour.trim() == "*" && s.dom.trim() == "*" && s.month.trim() == "*" && s.dow.trim() == "*"
        return when {
            everyHour && s.minute.trim().toIntOrNull() != null -> "Every hour" + (s.minute.trim().toInt().takeIf { it != 0 }?.let { " at :%02d".format(it) } ?: "")
            s.hour.trim().startsWith("*/") && s.dom.trim() == "*" && s.dow.trim() == "*" && s.month.trim() == "*" ->
                "Every ${s.hour.trim().removePrefix("*/")} hours"
            t != null && s.dom.trim() == "*" && s.month.trim() == "*" && s.dow.trim() == "*" -> "Daily at $t"
            t != null && s.dom.trim() == "*" && s.month.trim() == "*" && dayName(s.dow) != null -> "${dayName(s.dow)}s at $t"
            t != null && s.dom.trim().toIntOrNull() != null && s.month.trim() == "*" && s.dow.trim() == "*" -> "Monthly on day ${s.dom.trim()} at $t"
            else -> "Custom (${s.expression})"
        }
    }

    /** Parses "minute hour dom month dow"; null if it isn't 5 plausible cron fields. */
    fun parse(expr: String, base: CronSchedule = CronSchedule()): CronSchedule? {
        val parts = expr.trim().split(Regex("\\s+"))
        if (parts.size != 5 || parts.any { !FIELD.matches(it) }) return null
        return base.copy(minute = parts[0], hour = parts[1], dom = parts[2], month = parts[3], dow = parts[4])
    }

    /** Rough interval between runs, for "overdue" detection. */
    fun approxIntervalMillis(s: CronSchedule): Long {
        val hour = s.hour.trim(); val dom = s.dom.trim(); val dow = s.dow.trim(); val month = s.month.trim()
        return when {
            month != "*" -> 366 * DAY
            dom != "*" -> 31 * DAY
            dow != "*" -> 7 * DAY
            hour == "*" -> HOUR
            hour.startsWith("*/") -> (hour.removePrefix("*/").toLongOrNull() ?: 1) * HOUR
            hour.contains(',') -> DAY / hour.split(',').size.coerceAtLeast(1)
            else -> DAY
        }
    }
}
