package app.truenascompanion.notify.rules

import app.truenascompanion.notify.AlertLevel
import kotlinx.serialization.Serializable
import java.util.Locale
import java.util.UUID

/**
 * 1.10.0: custom alert rules, evaluated on the phone per server during the existing background check (no extra
 * wake-ups). Rules notify on transitions only: once when the condition starts, and "recovered" when it ends.
 */
@Serializable
enum class RuleKind(
    val title: String,
    /** Unit of [AlertRule.threshold]; null when the rule has no threshold. */
    val unit: String?,
    val defaultThreshold: Double,
    /** True when [AlertRule.minutes] ("for N minutes") applies. */
    val usesMinutes: Boolean = false,
    val defaultMinutes: Int = 0,
    /** True when the rule watches one named thing ([AlertRule.target]): an app, a service or a backup type. */
    val needsTarget: Boolean = false,
) {
    POOL_USAGE("Pool usage", "%", 85.0),
    DISK_TEMP("Disk temperature", "°C", 50.0, usesMinutes = true, defaultMinutes = 10),
    APP_NOT_RUNNING("App not running", null, 0.0, needsTarget = true),
    SERVICE_STOPPED("Service stopped", null, 0.0, needsTarget = true),
    BACKUP_FAILED("Replication or cloud sync failed", null, 0.0),
    BACKUP_STALE("No successful backup", "hours", 48.0),
    SCRUB_AGE("Scrub overdue", "days", 35.0),
    CPU_HIGH("High CPU", "%", 90.0, usesMinutes = true, defaultMinutes = 15),
    RAM_HIGH("High memory", "%", 90.0, usesMinutes = true, defaultMinutes = 15),
    CERT_EXPIRY("Certificate expiring", "days", 14.0),
    UNREACHABLE("NAS unreachable", "", 0.0, usesMinutes = true, defaultMinutes = 30),
}

@Serializable
enum class RuleSeverity(val label: String, val level: AlertLevel) {
    CRITICAL("Critical", AlertLevel.CRITICAL),
    WARNING("Warning", AlertLevel.WARNING),
    INFO("Info", AlertLevel.INFO),
}

/** Which backups [RuleKind.BACKUP_FAILED] / [RuleKind.BACKUP_STALE] look at ([AlertRule.target]). */
object BackupTarget {
    const val ANY = "any"
    const val REPLICATION = "replication"
    const val CLOUD_SYNC = "cloudsync"
}

@Serializable
data class AlertRule(
    val id: String = UUID.randomUUID().toString(),
    val kind: RuleKind,
    val enabled: Boolean = true,
    val severity: RuleSeverity = RuleSeverity.WARNING,
    val threshold: Double = kind.defaultThreshold,
    val minutes: Int = kind.defaultMinutes,
    /** App or service name ([RuleKind.needsTarget]); for backups one of [BackupTarget]. */
    val target: String? = null,
    /** After a recovery, the same rule stays quiet for this long even if the condition comes back (0 = off). */
    val cooldownMinutes: Int = 0,
) {
    /** One line for lists, e.g. "Pool usage over 85% · Warning". */
    fun summary(): String = describe(this)

    companion object {
        private fun num(v: Double) = if (v % 1.0 == 0.0) v.toLong().toString() else String.format(Locale.US, "%.1f", v)

        fun describe(r: AlertRule): String = when (r.kind) {
            RuleKind.POOL_USAGE -> "A pool is more than ${num(r.threshold)}% full"
            RuleKind.DISK_TEMP -> "A disk stays above ${num(r.threshold)}°C for ${r.minutes} min"
            RuleKind.APP_NOT_RUNNING -> "App ${r.target ?: "?"} isn't running"
            RuleKind.SERVICE_STOPPED -> "Service ${r.target?.uppercase() ?: "?"} is stopped"
            RuleKind.BACKUP_FAILED -> "${backupLabel(r.target)} failed"
            RuleKind.BACKUP_STALE -> "${backupLabel(r.target)} hasn't succeeded in ${num(r.threshold)} hours"
            RuleKind.SCRUB_AGE -> "A pool's last scrub is older than ${num(r.threshold)} days"
            RuleKind.CPU_HIGH -> "CPU averages over ${num(r.threshold)}% for ${r.minutes} min"
            RuleKind.RAM_HIGH -> "Memory (without ZFS cache) averages over ${num(r.threshold)}% for ${r.minutes} min"
            RuleKind.CERT_EXPIRY -> "A certificate expires within ${num(r.threshold)} days"
            RuleKind.UNREACHABLE -> "The NAS can't be reached for ${r.minutes} min"
        }

        fun backupLabel(target: String?) = when (target) {
            BackupTarget.REPLICATION -> "A replication"
            BackupTarget.CLOUD_SYNC -> "A cloud sync"
            else -> "A replication or cloud sync"
        }

        /** The one-tap "Suggested rules" pack. Nothing is on until the user adds it. */
        fun suggested(): List<AlertRule> = listOf(
            AlertRule(kind = RuleKind.POOL_USAGE, severity = RuleSeverity.WARNING, threshold = 85.0, cooldownMinutes = 24 * 60),
            AlertRule(kind = RuleKind.DISK_TEMP, severity = RuleSeverity.WARNING, threshold = 50.0, minutes = 10, cooldownMinutes = 60),
            AlertRule(kind = RuleKind.BACKUP_FAILED, severity = RuleSeverity.WARNING, target = BackupTarget.ANY),
            AlertRule(kind = RuleKind.BACKUP_STALE, severity = RuleSeverity.WARNING, threshold = 48.0, target = BackupTarget.ANY),
            AlertRule(kind = RuleKind.SCRUB_AGE, severity = RuleSeverity.INFO, threshold = 35.0),
            AlertRule(kind = RuleKind.UNREACHABLE, severity = RuleSeverity.CRITICAL, minutes = 30, cooldownMinutes = 60),
        )

        val THRESHOLD_LIMITS: Map<RuleKind, ClosedFloatingPointRange<Double>> = mapOf(
            RuleKind.POOL_USAGE to 1.0..100.0,
            RuleKind.DISK_TEMP to 20.0..100.0,
            RuleKind.BACKUP_STALE to 1.0..24.0 * 90,
            RuleKind.SCRUB_AGE to 1.0..365.0,
            RuleKind.CPU_HIGH to 1.0..100.0,
            RuleKind.RAM_HIGH to 1.0..100.0,
            RuleKind.CERT_EXPIRY to 1.0..365.0,
        )

        /** Validation for the editor; null when the rule can be saved. */
        fun problem(r: AlertRule): String? {
            THRESHOLD_LIMITS[r.kind]?.let { lim ->
                if (r.threshold.isNaN() || r.threshold !in lim) return "Pick a value between ${num(lim.start)} and ${num(lim.endInclusive)}"
            }
            if (r.kind.usesMinutes && r.minutes !in 1..24 * 60) return "Pick between 1 and 1440 minutes"
            if (r.kind.needsTarget && r.target.isNullOrBlank()) return if (r.kind == RuleKind.APP_NOT_RUNNING) "Pick an app" else "Pick a service"
            if (r.cooldownMinutes !in 0..7 * 24 * 60) return "Cooldown can be at most a week"
            return null
        }
    }
}

/** A rule's condition is true for one [subject] (a pool, disk, app, task or certificate; "" for the whole NAS). */
data class Finding(val subject: String, val text: String)

@Serializable
data class RuleHit(val since: Long, val notified: Boolean = false, val text: String = "", val ruleId: String = "")

/** Persisted per server so transitions survive restarts. Keys are "<rule id>|<subject>". */
@Serializable
data class RuleState(
    val firing: Map<String, RuleHit> = emptyMap(),
    val lastRecovered: Map<String, Long> = emptyMap(),
    /** First failed connection of the current outage (phone side), for [RuleKind.UNREACHABLE]. */
    val unreachableSince: Long? = null,
)

data class RuleEvent(val key: String, val rule: AlertRule, val subject: String, val text: String)

data class RuleStep(
    val state: RuleState,
    /** Conditions that are true and not notified yet (new ones, or held back by quiet hours last time). */
    val fire: List<RuleEvent>,
    /** Conditions that ended after a notification was shown. */
    val recovered: List<RuleEvent>,
    /** Notifications to remove without a "recovered" (rule deleted or turned off). */
    val dropped: Set<String>,
)

object RuleEngine {
    fun key(ruleId: String, subject: String) = "$ruleId|$subject"

    /**
     * One evaluation. [findings]: per rule id, what is true right now, or null when the data wasn't available (the
     * rule's state is kept as is). Rules missing from [findings] are treated like null.
     */
    fun step(rules: List<AlertRule>, findings: Map<String, List<Finding>?>, state: RuleState, now: Long): RuleStep {
        val active = rules.filter { it.enabled }.associateBy { it.id }
        val firing = LinkedHashMap<String, RuleHit>()
        val recovered = mutableListOf<RuleEvent>()
        val dropped = mutableSetOf<String>()
        val lastRecovered = HashMap(state.lastRecovered)
        // Existing hits: keep, recover or drop.
        state.firing.forEach { (k, hit) ->
            val ruleId = hit.ruleId.ifEmpty { k.substringBefore('|') }
            val subject = k.substringAfter('|', "")
            val rule = active[ruleId]
            if (rule == null) { dropped += k; return@forEach }
            val now0 = findings[ruleId] ?: run { firing[k] = hit; return@forEach }
            if (now0.any { it.subject == subject }) firing[k] = hit.copy(text = now0.first { it.subject == subject }.text)
            else {
                lastRecovered[k] = now
                if (hit.notified) recovered += RuleEvent(k, rule, subject, hit.text)
            }
        }
        // New hits.
        active.values.forEach { rule ->
            findings[rule.id]?.forEach { f ->
                val k = key(rule.id, f.subject)
                if (k in firing) return@forEach
                val cool = rule.cooldownMinutes * 60_000L
                val last = lastRecovered[k]
                if (cool > 0 && last != null && now - last < cool) return@forEach
                firing[k] = RuleHit(since = now, notified = false, text = f.text, ruleId = rule.id)
            }
        }
        // Forget old recoveries (longest cooldown is a week).
        lastRecovered.entries.removeAll { now - it.value > 8 * 86_400_000L || it.key.substringBefore('|') !in active }
        val fire = firing.filterValues { !it.notified }.map { (k, h) -> RuleEvent(k, active.getValue(h.ruleId.ifEmpty { k.substringBefore('|') }), k.substringAfter('|', ""), h.text) }
        return RuleStep(state.copy(firing = firing, lastRecovered = lastRecovered), fire, recovered, dropped)
    }

    fun markNotified(state: RuleState, keys: Collection<String>): RuleState =
        if (keys.isEmpty()) state else state.copy(firing = state.firing.mapValues { (k, h) -> if (k in keys) h.copy(notified = true) else h })

    /** Title of a rule notification, e.g. "Pool usage · tank". */
    fun title(e: RuleEvent): String = e.rule.kind.title + if (e.subject.isNotEmpty()) " · ${e.subject}" else ""
}
