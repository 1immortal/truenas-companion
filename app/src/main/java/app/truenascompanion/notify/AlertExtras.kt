package app.truenascompanion.notify

import app.truenascompanion.data.api.obj
import app.truenascompanion.data.api.str
import app.truenascompanion.data.model.AlertItem
import app.truenascompanion.data.model.CertKind
import app.truenascompanion.data.model.NasCertificate
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Where an alert leads (1.2.0), from its `klass` and `args` (TrueNAS 25.10 `middlewared/alert/source/`).
 * Carried in notification intents as [destination] + [arg]; unknown classes open the Alerts tab.
 */
sealed interface AlertTarget {
    val destination: String
    val arg: String? get() = null
    /** Button label in the app, e.g. "Open pool tank". */
    val label: String

    data class Pool(val name: String) : AlertTarget {
        override val destination get() = DeepLink.DEST_POOL; override val arg get() = name; override val label get() = "Open pool $name"
    }
    data class Disk(val name: String?) : AlertTarget {
        override val destination get() = DeepLink.DEST_DISK; override val arg get() = name; override val label get() = if (name != null) "Open disk $name" else "Open disks"
    }
    data class App(val name: String) : AlertTarget {
        override val destination get() = DeepLink.DEST_APP; override val arg get() = name; override val label get() = "Open $name"
    }
    data object Apps : AlertTarget { override val destination get() = DeepLink.DEST_APPS; override val label get() = "Open apps" }
    data class Dataset(val name: String?) : AlertTarget {
        override val destination get() = DeepLink.DEST_DATASET; override val arg get() = name; override val label get() = "Open datasets"
    }
    data class Snapshots(val dataset: String) : AlertTarget {
        override val destination get() = DeepLink.DEST_SNAPSHOTS; override val arg get() = dataset; override val label get() = "Open snapshots"
    }
    data object Update : AlertTarget { override val destination get() = DeepLink.DEST_UPDATE; override val label get() = "Open updates" }
    data class Certificate(val name: String?) : AlertTarget {
        override val destination get() = DeepLink.DEST_CERTIFICATE; override val arg get() = name; override val label get() = "Open certificates"
    }
    /** 1.3.0: a pool reports unhealthy member disks; opens the guided disk replacement for that pool. */
    data class ReplaceDisk(val pool: String) : AlertTarget {
        override val destination get() = DeepLink.DEST_REPLACE_DISK; override val arg get() = pool; override val label get() = "Replace disk in $pool"
    }
    /** 1.5.0: Storage › Protection › Cloud sync (failed cloud sync tasks, finished manual runs). */
    data object CloudSync : AlertTarget { override val destination get() = DeepLink.DEST_CLOUD_SYNC; override val label get() = "Open cloud sync" }
    data object Alerts : AlertTarget { override val destination get() = DeepLink.DEST_ALERTS; override val label get() = "Open alerts" }

    companion object {
        private fun JsonElement?.text(vararg keys: String): String? {
            val o = obj()
            if (o == null) return (this as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf { it.isNotBlank() }
            return keys.firstNotNullOfOrNull { k -> o.str(k)?.takeIf { it.isNotBlank() } }
        }

        fun of(alert: AlertItem): AlertTarget = of(alert.klass, alert.args)

        fun of(klass: String?, args: JsonElement?): AlertTarget {
            val k = klass ?: return Alerts
            return when {
                k == "VolumeStatus" && !args.text("devices").isNullOrBlank() ->
                    args.text("volume", "pool")?.let { ReplaceDisk(it) } ?: Alerts
                k == "VolumeStatus" || k.startsWith("ZpoolCapacity") || k == "PoolUSBDisks" || k == "PoolUpgraded" || k.startsWith("Scrub") ->
                    args.text("volume", "pool", "pool_name", "name")?.let { Pool(it) } ?: Alerts
                k.startsWith("SMART") || k.startsWith("DiskTemperature") || k == "DiskNotDetected" ->
                    Disk(args.text("name", "device", "disk")?.removePrefix("/dev/"))
                k == "AppUpdate" -> args.text("apps")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.singleOrNull()?.let { App(it) } ?: Apps
                k.startsWith("Applications") || k == "FailuresInAppMigration" || k.startsWith("App") -> Apps
                k == "SnapshotCount" -> args.text("dataset")?.let { Snapshots(it) } ?: Dataset(null)
                k.startsWith("Quota") -> Dataset(args.text("dataset"))
                k == "EncryptedDataset" || k == "SnapshotTotalCount" -> Dataset(null)
                k == "HasUpdate" || k == "CurrentlyRunningVersionDoesNotMatchProfile" -> Update
                k.startsWith("Certificate") || k == "WebUiCertificateSetupFailed" -> Certificate(args.text("name"))
                k == "CloudSyncTaskFailed" -> CloudSync
                else -> Alerts
            }
        }

        fun decode(destination: String?, arg: String?): AlertTarget = when (destination) {
            DeepLink.DEST_POOL -> arg?.let { Pool(it) } ?: Alerts
            DeepLink.DEST_DISK -> Disk(arg)
            DeepLink.DEST_REPLACE_DISK -> arg?.let { ReplaceDisk(it) } ?: Disk(null)
            DeepLink.DEST_APP -> arg?.let { App(it) } ?: Apps
            DeepLink.DEST_APPS -> Apps
            DeepLink.DEST_DATASET -> Dataset(arg)
            DeepLink.DEST_SNAPSHOTS -> arg?.let { Snapshots(it) } ?: Dataset(null)
            DeepLink.DEST_UPDATE -> Update
            DeepLink.DEST_CERTIFICATE -> Certificate(arg)
            DeepLink.DEST_CLOUD_SYNC -> CloudSync
            else -> Alerts
        }
    }
}

/** Local snoozes (1.2.0): alert uuid → until (epoch ms), never sent to the NAS. */
object Snooze {
    data class Option(val label: String, val millis: Long)
    val OPTIONS = listOf(Option("1 hour", 3_600_000L), Option("8 hours", 8 * 3_600_000L), Option("1 day", 86_400_000L), Option("1 week", 7 * 86_400_000L))

    data class Plan(
        /** Snoozes to store next. */
        val next: Map<String, Long>,
        /** Alerts whose snooze ran out and that should be notified again now. */
        val wake: Set<String>,
    )

    /**
     * [active]: uuids currently active (not dismissed) on the NAS. Snoozes of alerts that are gone are dropped; expired
     * snoozes wake the alert if [allowedNow] lets it through (quiet hours), otherwise they wait.
     */
    fun plan(snoozes: Map<String, Long>, active: Set<String>, nowMillis: Long, allowedNow: (String) -> Boolean): Plan {
        val next = LinkedHashMap<String, Long>(); val wake = LinkedHashSet<String>()
        snoozes.forEach { (uuid, until) ->
            when {
                uuid !in active -> Unit
                until > nowMillis -> next[uuid] = until
                allowedNow(uuid) -> wake += uuid
                else -> next[uuid] = until
            }
        }
        return Plan(next, wake)
    }

    fun isSnoozed(snoozes: Map<String, Long>, uuid: String, nowMillis: Long) = (snoozes[uuid] ?: 0L) > nowMillis
}

/** Certificate expiry warnings folded into the alert check (1.2.0). */
@Serializable
data class CertCheckState(val lastCheck: Long = 0L, /** cert id → "EXPIRING" / "EXPIRED" already notified. */ val notified: Map<String, String> = emptyMap())

object CertExpiry {
    /** At most twice a day, and only inside an alert check that runs anyway (no extra wakeups). */
    const val INTERVAL_MS = 12 * 3_600_000L

    data class Warning(val cert: NasCertificate, val daysLeft: Long, val expired: Boolean) {
        val state: String get() = if (expired) "EXPIRED" else "EXPIRING"
    }

    fun due(state: CertCheckState, nowMillis: Long) = nowMillis - state.lastCheck >= INTERVAL_MS || nowMillis < state.lastCheck

    /**
     * Certificates and CAs (not CSRs) that expire within [warnDays]. ACME certificates are renewed by TrueNAS
     * `renew_days` before expiry, so they only warn once that renewal is overdue (same threshold as TrueNAS' own alert).
     */
    fun evaluate(certs: List<NasCertificate>, warnDays: Int, nowMillis: Long): List<Warning> = certs.mapNotNull { c ->
        if (c.kind == CertKind.CSR || !c.parsed) return@mapNotNull null
        val days = c.daysLeft(nowMillis) ?: return@mapNotNull null
        val threshold = if (c.acme) minOf(warnDays, (c.renewDays ?: 10) - 1) else warnDays
        when {
            c.expired || days < 0 -> Warning(c, days, true)
            days <= threshold -> Warning(c, days, false)
            else -> null
        }
    }

    /** Which warnings are new since [state]; the returned map is the notified set to store (resolved certs drop out). */
    fun diff(warnings: List<Warning>, state: CertCheckState): Pair<List<Warning>, Map<String, String>> {
        val fresh = warnings.filter { state.notified[it.cert.id.toString()] != it.state }
        val stillWarning = warnings.associate { it.cert.id.toString() to it.state }
        val kept = state.notified.filterKeys { it in stillWarning }
        return fresh to kept
    }

    fun text(w: Warning): Pair<String, String> = if (w.expired) {
        "Certificate expired: ${w.cert.name}" to "${w.cert.name} (${w.cert.domains.firstOrNull() ?: w.cert.kind.label}) has expired. Browsers and apps will refuse it."
    } else {
        val d = when (w.daysLeft) { 0L -> "today"; 1L -> "tomorrow"; else -> "in ${w.daysLeft} days" }
        "Certificate expires $d" to "${w.cert.name} (${w.cert.domains.firstOrNull() ?: w.cert.kind.label}) expires $d." +
            if (w.cert.acme) " Automatic ACME renewal hasn't happened yet." else ""
    }
}

/** Grouping of repeated alerts (same class) into one notification with a count (1.2.0). */
object AlertGrouping {
    /** An alert notification currently shown: its tag, class and the alert uuids it covers. */
    data class Shown(val tag: String, val klass: String?, val uuids: List<String>)

    /** A notification to post: [uuids] of class [klass] (grouped when more than one). [replaces] are tags to cancel. */
    data class Post(val klass: String?, val uuids: List<String>, val replaces: List<String>) {
        val grouped: Boolean get() = uuids.size > 1
    }

    fun groupTag(serverId: String, klass: String) = "alert/$serverId/k:$klass"

    /** Merges [fresh] alerts (uuid, klass) with what is already [shown]; alerts without a class are never grouped. */
    fun plan(fresh: List<Pair<String, String?>>, shown: List<Shown>): List<Post> {
        val out = ArrayList<Post>()
        fresh.groupBy { it.second }.forEach { (klass, items) ->
            val ids = items.map { it.first }
            if (klass == null) { ids.forEach { out += Post(null, listOf(it), emptyList()) }; return@forEach }
            val existing = shown.filter { it.klass == klass }
            val all = (existing.flatMap { it.uuids } + ids).distinct()
            out += Post(klass, all, existing.map { it.tag })
        }
        return out
    }
}
