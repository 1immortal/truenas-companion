package app.truenascompanion.notify.rules

import app.truenascompanion.data.api.ReportingApi
import app.truenascompanion.data.api.TrueNasApi
import app.truenascompanion.data.api.CertificatesApi
import app.truenascompanion.data.api.ProtectionApi
import app.truenascompanion.data.model.AppInfo
import app.truenascompanion.data.model.AppState
import app.truenascompanion.data.model.BackupKind
import app.truenascompanion.data.model.BackupTask
import app.truenascompanion.data.model.NasCertificate
import app.truenascompanion.data.model.Pool
import app.truenascompanion.data.model.ReportRange
import app.truenascompanion.data.model.ServiceInfo
import app.truenascompanion.notify.CertExpiry
import kotlinx.coroutines.CancellationException
import java.util.Locale

/**
 * Everything the rules may need from the NAS. Each part is loaded at most once per evaluation and only when an enabled
 * rule needs it; a part that fails to load is null and the rules using it keep their state.
 */
interface RuleData {
    suspend fun pools(): List<Pool>?
    suspend fun apps(): List<AppInfo>?
    suspend fun services(): List<ServiceInfo>?
    suspend fun backups(): List<BackupTask>?
    suspend fun certificates(): List<NasCertificate>?
    /** Average CPU use in percent over the last [minutes] (netdata history on the NAS). */
    suspend fun cpuAverage(minutes: Int): Double?
    /** Average memory use without the ZFS cache, in percent of RAM, over the last [minutes]. */
    suspend fun ramAverage(minutes: Int): Double?
    /** Per disk, the lowest temperature over the last [minutes] (so "stayed above X" = lowest > X). */
    suspend fun diskTempLowest(minutes: Int): Map<String, Double>?
}

/** Live [RuleData] over one connection. Verified against TrueNAS 25.10 (see docs/TECHNICAL.md, 1.10.0). */
class ApiRuleData(private val api: TrueNasApi, private val now: () -> Long = System::currentTimeMillis) : RuleData {
    private val cache = HashMap<String, Any?>()
    private suspend fun <T> once(key: String, block: suspend () -> T): T? {
        if (key in cache) @Suppress("UNCHECKED_CAST") return cache[key] as T?
        val v = try { block() } catch (e: CancellationException) { throw e } catch (_: Throwable) { null }
        cache[key] = v
        return v
    }

    override suspend fun pools() = once("pools") { api.pools() }
    override suspend fun apps() = once("apps") { api.apps() }
    override suspend fun services() = once("services") { api.services() }
    override suspend fun backups() = once("backups") { ProtectionApi(api).backupTasks() }
    override suspend fun certificates() = once("certs") { CertificatesApi(api).certificates() }

    private suspend fun window(graphs: List<Pair<String, String?>>, minutes: Int) = ReportingApi(api).data(
        graphs, ReportRange.HOUR, endSec = now() / 1000, startSec = now() / 1000 - minutes * 60L,
    )

    override suspend fun cpuAverage(minutes: Int) = once("cpu$minutes") {
        val d = window(listOf("cpu" to null), minutes).firstOrNull() ?: return@once null
        RuleFindings.cpuMean(d)
    }

    override suspend fun ramAverage(minutes: Int) = once("ram$minutes") {
        val total = api.systemInfo().physicalMemory ?: return@once null
        val res = window(listOf("memory" to null, "arcsize" to null), minutes)
        val avail = res.firstOrNull { it.name == "memory" }?.series?.firstOrNull()?.let { RuleFindings.mean(it) } ?: return@once null
        val arc = res.firstOrNull { it.name == "arcsize" }?.series?.firstOrNull()?.let { RuleFindings.mean(it) } ?: 0.0
        RuleFindings.ramPercent(total.toDouble(), avail, arc)
    }

    override suspend fun diskTempLowest(minutes: Int) = once("temp$minutes") {
        val ids = ReportingApi(api).graphs().firstOrNull { it.name == "disktemp" }?.identifiers.orEmpty()
        if (ids.isEmpty()) return@once null
        window(ids.map { "disktemp" to it }, minutes).mapNotNull { d ->
            val s = d.series.firstOrNull() ?: return@mapNotNull null
            val low = s.values.filter { !it.isNaN() }.minOrNull()?.toDouble() ?: s.min ?: return@mapNotNull null
            RuleFindings.diskName(d.identifier ?: return@mapNotNull null) to low
        }.toMap()
    }
}

/** Pure condition checks (unit tested). */
object RuleFindings {
    private fun pct(v: Double) = String.format(Locale.US, "%.0f%%", v)

    fun mean(s: app.truenascompanion.data.model.ReportSeries): Double? =
        s.mean ?: s.values.filter { !it.isNaN() }.takeIf { it.isNotEmpty() }?.average()

    /** The `cpu` graph has one column per thread plus the total ("cpu"); use the total when present. */
    fun cpuMean(d: app.truenascompanion.data.model.ReportData): Double? {
        val total = d.series.firstOrNull { it.label.equals("cpu", true) }
        return if (total != null) mean(total) else d.series.mapNotNull { mean(it) }.takeIf { it.isNotEmpty() }?.average()
    }

    fun ramPercent(total: Double, available: Double, arc: Double): Double? {
        if (total <= 0) return null
        val used = (total - available).coerceIn(0.0, total)
        val services = if (arc <= used) used - arc else used
        return services / total * 100
    }

    /** "sda | Type: HDD | Model: … " → "sda". */
    fun diskName(identifier: String) = identifier.substringBefore(" | ").trim()

    /** What [rule] finds right now; null when its data isn't available ([data] null: no connection). */
    suspend fun evaluate(rule: AlertRule, data: RuleData?, unreachableSince: Long?, now: Long): List<Finding>? {
        if (rule.kind == RuleKind.UNREACHABLE) {
            val since = unreachableSince
            return if (since != null && now - since >= rule.minutes * 60_000L) listOf(Finding("", "The phone couldn't reach the NAS for ${(now - since) / 60_000} min."))
            else emptyList()
        }
        return evaluateConnected(rule, data ?: return null, now)
    }

    private suspend fun evaluateConnected(rule: AlertRule, data: RuleData, now: Long): List<Finding>? = when (rule.kind) {
        RuleKind.POOL_USAGE -> data.pools()?.mapNotNull { p ->
            val size = p.size ?: return@mapNotNull null
            val used = p.allocated ?: return@mapNotNull null
            if (size <= 0) return@mapNotNull null
            val v = used.toDouble() / size * 100
            if (v > rule.threshold) Finding(p.name, "Pool ${p.name} is ${pct(v)} full (rule: over ${pct(rule.threshold)}).") else null
        }
        RuleKind.DISK_TEMP -> data.diskTempLowest(rule.minutes)?.mapNotNull { (disk, low) ->
            if (low > rule.threshold) Finding(disk, String.format(Locale.US, "Disk %s stayed above %.0f°C for %d min (lowest %.0f°C).", disk, rule.threshold, rule.minutes, low)) else null
        }
        RuleKind.APP_NOT_RUNNING -> data.apps()?.let { apps ->
            val name = rule.target ?: return@let emptyList()
            val app = apps.firstOrNull { it.name == name }
            when {
                app == null -> listOf(Finding(name, "App $name isn't installed any more."))
                app.state == AppState.RUNNING || app.state == AppState.DEPLOYING -> emptyList()
                else -> listOf(Finding(name, "App $name is ${app.state.name.lowercase()}."))
            }
        }
        RuleKind.SERVICE_STOPPED -> data.services()?.let { list ->
            val name = rule.target ?: return@let emptyList()
            val s = list.firstOrNull { it.service == name } ?: return@let emptyList()
            if (s.running || s.unknown) emptyList() else listOf(Finding(name, "Service ${s.displayName} is stopped."))
        }
        RuleKind.BACKUP_FAILED -> data.backups()?.filter { it.matches(rule.target) && it.enabled && it.failed }?.map {
            Finding("${it.kind.name}:${it.id}", "${it.kind.label} \"${it.name}\" failed" + (it.lastJob?.error?.lineSequence()?.firstOrNull { l -> l.isNotBlank() }?.take(160)?.let { e -> ": $e" } ?: "."))
        }
        RuleKind.BACKUP_STALE -> data.backups()?.filter { it.matches(rule.target) && it.enabled && !it.running }?.mapNotNull {
            val last = it.lastSuccessMillis
            val limit = (rule.threshold * 3_600_000).toLong()
            when {
                last == null -> Finding("${it.kind.name}:${it.id}", "${it.kind.label} \"${it.name}\" has no successful run on record.")
                now - last > limit -> Finding("${it.kind.name}:${it.id}", "${it.kind.label} \"${it.name}\" last succeeded ${hoursAgo(now - last)}.")
                else -> null
            }
        }
        RuleKind.SCRUB_AGE -> data.pools()?.mapNotNull { p ->
            val scrub = p.scanFunction.equals("SCRUB", true)
            when {
                p.scrubRunning -> null
                scrub && p.scanEndMillis != null -> {
                    val days = (now - p.scanEndMillis) / 86_400_000.0
                    if (days > rule.threshold) Finding(p.name, String.format(Locale.US, "Pool %s was last scrubbed %.0f days ago.", p.name, days)) else null
                }
                p.scanFunction == null -> Finding(p.name, "Pool ${p.name} has no scrub on record.")
                else -> null // last scan was a resilver: the scrub date isn't known
            }
        }
        RuleKind.CPU_HIGH -> data.cpuAverage(rule.minutes)?.let { v ->
            if (v > rule.threshold) listOf(Finding("", "CPU averaged ${pct(v)} over the last ${rule.minutes} min.")) else emptyList()
        }
        RuleKind.RAM_HIGH -> data.ramAverage(rule.minutes)?.let { v ->
            if (v > rule.threshold) listOf(Finding("", "Memory use (without ZFS cache) averaged ${pct(v)} over the last ${rule.minutes} min.")) else emptyList()
        }
        RuleKind.CERT_EXPIRY -> data.certificates()?.let { certs ->
            CertExpiry.evaluate(certs, rule.threshold.toInt(), now).map { w -> Finding(w.cert.name, CertExpiry.text(w).second) }
        }
        RuleKind.UNREACHABLE -> emptyList()
    }

    private fun hoursAgo(ms: Long): String {
        val h = ms / 3_600_000
        return if (h < 48) "$h hours ago" else "${h / 24} days ago"
    }

    private fun BackupTask.matches(target: String?) = when (target) {
        BackupTarget.REPLICATION -> kind == BackupKind.REPLICATION
        BackupTarget.CLOUD_SYNC -> kind == BackupKind.CLOUD_SYNC
        else -> kind == BackupKind.REPLICATION || kind == BackupKind.CLOUD_SYNC
    }

    /** Rules that need a connection (everything except "unreachable"). */
    fun needsConnection(rules: List<AlertRule>) = rules.any { it.enabled && it.kind != RuleKind.UNREACHABLE }
}
