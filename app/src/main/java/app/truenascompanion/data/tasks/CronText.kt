package app.truenascompanion.data.tasks

import app.truenascompanion.data.model.CronSchedule

/** Schedule presets of the cron job editor. Daily/weekly/monthly keep their own time; hourly keeps its minute. */
enum class CronPreset(val label: String) { HOURLY("Hourly"), DAILY("Daily"), WEEKLY("Weekly"), MONTHLY("Monthly"), CUSTOM("Custom") }

/** The five cron fields with the ranges croniter (and so TrueNAS) accepts. */
enum class CronField(val label: String, val min: Int, val max: Int, val names: List<String> = emptyList(), val hint: String) {
    MINUTE("Minute", 0, 59, hint = "0–59"),
    HOUR("Hour", 0, 23, hint = "0–23"),
    DOM("Day of month", 1, 31, hint = "1–31"),
    MONTH("Month", 1, 12, listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec"), "1–12 or jan–dec"),
    DOW("Day of week", 0, 7, listOf("sun", "mon", "tue", "wed", "thu", "fri", "sat"), "0–7 or sun–sat (0 and 7 = Sunday)");
}

/**
 * Human-readable cron schedules ("Every day at 03:00") plus parsing and per-field validation for the cron job editor
 * (1.4.0). Field syntax follows croniter: a star, a step like "every n", a value, a range (optionally with a step), comma lists, and names for month/weekday.
 */
object CronText {
    val DAY_NAMES = listOf("Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday")
    val MONTH_NAMES = listOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")

    /** A new job runs every day at 00:00, like the TrueNAS web UI's default. */
    val DEFAULT = CronSchedule(minute = "0", hour = "0", dom = "*", month = "*", dow = "*")

    private fun num(v: String) = v.trim().toIntOrNull()
    private fun star(v: String) = v.trim() == "*"

    /** Validates one field; null if fine, else a short message for the field. */
    fun fieldError(field: CronField, value: String): String? {
        val v = value.trim().lowercase()
        if (v.isEmpty()) return "Required"
        for (item in v.split(',')) {
            if (item.isEmpty()) return "Empty item in the list"
            val (range, step) = item.split('/', limit = 2).let { it[0] to it.getOrNull(1) }
            if (step != null && (step.toIntOrNull() ?: 0) < 1) return "Step after / must be 1 or more"
            if (range == "*") continue
            val ends = range.split('-')
            if (ends.size > 2) return "Use a-b for a range"
            val nums = ends.map { value(field, it) ?: return "Use ${field.hint}" }
            if (nums.size == 2 && nums[0] > nums[1] && field != CronField.DOW) return "Range must go from low to high"
        }
        return null
    }

    private fun value(field: CronField, s: String): Int? {
        val t = s.trim()
        t.toIntOrNull()?.let { return it.takeIf { n -> n in field.min..field.max } }
        val i = field.names.indexOf(t)
        return if (i < 0) null else if (field == CronField.MONTH) i + 1 else i
    }

    fun errors(s: CronSchedule): Map<CronField, String> = listOfNotNull(
        fieldError(CronField.MINUTE, s.minute)?.let { CronField.MINUTE to it },
        fieldError(CronField.HOUR, s.hour)?.let { CronField.HOUR to it },
        fieldError(CronField.DOM, s.dom)?.let { CronField.DOM to it },
        fieldError(CronField.MONTH, s.month)?.let { CronField.MONTH to it },
        fieldError(CronField.DOW, s.dow)?.let { CronField.DOW to it },
    ).toMap()

    fun isValid(s: CronSchedule) = errors(s).isEmpty()

    /** Parses "minute hour day month weekday" (5 fields); null when malformed. */
    fun parse(expr: String): CronSchedule? {
        val p = expr.trim().split(Regex("\\s+"))
        if (p.size != 5) return null
        val s = CronSchedule(minute = p[0], hour = p[1], dom = p[2], month = p[3], dow = p[4])
        return s.takeIf { isValid(it) }
    }

    fun get(s: CronSchedule, f: CronField) = when (f) {
        CronField.MINUTE -> s.minute; CronField.HOUR -> s.hour; CronField.DOM -> s.dom; CronField.MONTH -> s.month; CronField.DOW -> s.dow
    }

    fun set(s: CronSchedule, f: CronField, v: String) = when (f) {
        CronField.MINUTE -> s.copy(minute = v); CronField.HOUR -> s.copy(hour = v); CronField.DOM -> s.copy(dom = v)
        CronField.MONTH -> s.copy(month = v); CronField.DOW -> s.copy(dow = v)
    }

    /** Weekday index 0 (Sunday) … 6 for a single weekday value (number 0–7 or name), else null. */
    fun dayIndex(dow: String): Int? {
        val d = dow.trim().lowercase()
        CronField.DOW.names.indexOf(d).takeIf { it >= 0 }?.let { return it }
        return d.toIntOrNull()?.takeIf { it in 0..7 }?.let { it % 7 }
    }

    fun presetOf(s: CronSchedule): CronPreset {
        val m = num(s.minute) ?: return CronPreset.CUSTOM
        if (m !in 0..59 || !star(s.month)) return CronPreset.CUSTOM
        if (star(s.hour)) return if (star(s.dom) && star(s.dow)) CronPreset.HOURLY else CronPreset.CUSTOM
        if (num(s.hour)?.takeIf { it in 0..23 } == null) return CronPreset.CUSTOM
        return when {
            star(s.dom) && star(s.dow) -> CronPreset.DAILY
            star(s.dom) && dayIndex(s.dow) != null -> CronPreset.WEEKLY
            num(s.dom)?.let { it in 1..31 } == true && star(s.dow) -> CronPreset.MONTHLY
            else -> CronPreset.CUSTOM
        }
    }

    /** Switches preset, keeping the time (and minute) the user already picked where it makes sense. */
    fun applyPreset(s: CronSchedule, p: CronPreset): CronSchedule {
        val m = num(s.minute)?.takeIf { it in 0..59 } ?: 0
        val h = num(s.hour)?.takeIf { it in 0..23 } ?: 0
        return when (p) {
            CronPreset.HOURLY -> CronSchedule(minute = "$m", hour = "*", dom = "*", month = "*", dow = "*")
            CronPreset.DAILY -> CronSchedule(minute = "$m", hour = "$h", dom = "*", month = "*", dow = "*")
            CronPreset.WEEKLY -> CronSchedule(minute = "$m", hour = "$h", dom = "*", month = "*", dow = dayIndex(s.dow)?.toString() ?: "0")
            CronPreset.MONTHLY -> CronSchedule(minute = "$m", hour = "$h", dom = num(s.dom)?.takeIf { it in 1..31 }?.toString() ?: "1", month = "*", dow = "*")
            CronPreset.CUSTOM -> s
        }
    }

    fun ordinal(n: Int): String {
        val suffix = if (n % 100 in 11..13) "th" else when (n % 10) { 1 -> "st"; 2 -> "nd"; 3 -> "rd"; else -> "th" }
        return "$n$suffix"
    }

    private fun hhmm(h: Int, m: Int) = "%02d:%02d".format(h, m)

    /** "03:00" or "03:00 and 15:00" for numeric minute + numeric hour list; null otherwise. */
    private fun times(s: CronSchedule): String? {
        val m = num(s.minute)?.takeIf { it in 0..59 } ?: return null
        val hours = s.hour.trim().split(',').map { num(it)?.takeIf { h -> h in 0..23 } ?: return null }
        return joinAnd(hours.map { hhmm(it, m) })
    }

    private fun joinAnd(items: List<String>): String = when (items.size) {
        0 -> ""; 1 -> items[0]; else -> items.dropLast(1).joinToString(", ") + " and " + items.last()
    }

    /** Weekday description for a numeric/name list or range, e.g. "Monday and Friday", "weekdays"; null if too complex. */
    private fun days(dow: String): String? {
        val d = dow.trim().lowercase()
        if (d == "1-5" || d == "mon-fri") return "weekday"
        if (d in setOf("0,6", "6,0", "sat,sun", "sun,sat", "6,7", "6-7", "sat-sun")) return "weekend day"
        val idx = d.split(',').map { dayIndex(it) ?: return null }.distinct()
        return joinAnd(idx.map { DAY_NAMES[it] })
    }

    /** Friendly description, e.g. "Every day at 03:00", "Every Sunday at 02:30", "Every 15 minutes". */
    fun describe(s: CronSchedule): String {
        val minute = s.minute.trim(); val hour = s.hour.trim()
        val restStar = star(s.dom) && star(s.month) && star(s.dow)
        if (restStar && star(hour)) {
            when {
                minute == "*" -> return "Every minute"
                minute.startsWith("*/") && num(minute.removePrefix("*/")) != null -> {
                    val n = num(minute.removePrefix("*/"))!!
                    return if (n == 1) "Every minute" else "Every $n minutes"
                }
                num(minute) != null -> return if (num(minute) == 0) "Every hour" else "Every hour at :%02d past".format(num(minute))
                minute.split(',').all { num(it) != null } -> return "Every hour at " + joinAnd(minute.split(',').map { ":%02d".format(num(it)) })
            }
        }
        if (restStar && hour.startsWith("*/") && num(minute) != null) {
            val n = num(hour.removePrefix("*/")) ?: return custom(s)
            val past = num(minute)!!.takeIf { it != 0 }?.let { " at :%02d past".format(it) } ?: ""
            return (if (n == 1) "Every hour" else "Every $n hours") + past
        }
        windowed(s)?.let { return it }
        val t = times(s) ?: return custom(s)
        val dom = s.dom.trim(); val month = s.month.trim()
        return when {
            restStar -> "Every day at $t"
            star(dom) && star(month) -> when (val d = days(s.dow)) {
                null -> custom(s)
                "weekday" -> "Weekdays at $t"
                "weekend day" -> "Weekends at $t"
                else -> "Every $d at $t"
            }
            star(s.dow) && star(month) && num(dom) != null -> "Every month on the ${ordinal(num(dom)!!)} at $t"
            star(s.dow) && star(month) && dom.split(',').all { num(it) != null } ->
                "Every month on the " + joinAnd(dom.split(',').map { ordinal(num(it)!!) }) + " at $t"
            star(s.dow) && num(dom) != null && monthIndex(month) != null -> "Every year on ${num(dom)} ${MONTH_NAMES[monthIndex(month)!!]} at $t"
            else -> custom(s)
        }
    }

    /** Repeats inside an hour range, e.g. "Every 30 minutes from 09:00 to 17:59 on weekdays". */
    private fun windowed(s: CronSchedule): String? {
        if (!star(s.dom) || !star(s.month)) return null
        val minute = s.minute.trim()
        val every = when {
            minute == "*" -> "Every minute"
            minute.startsWith("*/") -> num(minute.removePrefix("*/"))?.takeIf { it in 1..59 }?.let { if (it == 1) "Every minute" else "Every $it minutes" }
            else -> null
        } ?: return null
        val range = s.hour.trim().split('-')
        if (range.size != 2) return null
        val from = num(range[0])?.takeIf { it in 0..23 } ?: return null
        val to = num(range[1])?.takeIf { it in from..23 } ?: return null
        val window = "$every from ${hhmm(from, 0)} to ${hhmm(to, 59)}"
        if (star(s.dow)) return window
        return when (val d = days(s.dow)) {
            null -> null
            "weekday" -> "$window on weekdays"
            "weekend day" -> "$window on weekends"
            else -> "$window on " + d.replace(Regex("day\\b"), "days")
        }
    }

    private fun monthIndex(m: String): Int? {
        val t = m.trim().lowercase()
        CronField.MONTH.names.indexOf(t).takeIf { it >= 0 }?.let { return it }
        return num(t)?.takeIf { it in 1..12 }?.minus(1)
    }

    private fun custom(s: CronSchedule) = "Custom: ${s.expression}"
}
