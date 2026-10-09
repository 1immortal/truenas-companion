package app.truenascompanion.data.replication

import app.truenascompanion.data.api.ProtectionParsers
import app.truenascompanion.data.api.arr
import app.truenascompanion.data.api.bool
import app.truenascompanion.data.api.long
import app.truenascompanion.data.api.obj
import app.truenascompanion.data.api.parseDate
import app.truenascompanion.data.api.prim
import app.truenascompanion.data.api.str
import app.truenascompanion.data.model.CronSchedule
import app.truenascompanion.data.model.LastJob
import app.truenascompanion.data.tasks.CronText
import app.truenascompanion.util.Format
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/*
 * 1.6.0: replication and the SSH keychain it needs, from the TrueNAS 25.10 middleware (`stable/goldeye`:
 * `plugins/replication.py`, `plugins/replication_/crud.py`, `plugins/keychain.py`, `plugins/keychain_/ssh_connections.py`,
 * `plugins/zettarepl.py`, `api/v25_10_2/{replication,replication_crud,keychain}.py`).
 */

/** `SSH_KEY_PAIR` keychain credential. Keys are null when TrueNAS redacted them ([redacted]). */
data class SshKeyPair(val id: Int, val name: String, val publicKey: String?, val privateKey: String?, val redacted: Boolean = false) {
    /** "ssh-rsa AAAA…xyz" shortened for lists. */
    val shortPublicKey: String? get() = publicKey?.trim()?.split(Regex("\\s+"))?.let { p ->
        if (p.size < 2) p.firstOrNull() else "${p[0]} ${p[1].take(12)}…${p[1].takeLast(8)}"
    }
}

/** `SSH_CREDENTIALS` keychain credential: an SSH connection to another system. */
data class SshConnection(
    val id: Int,
    val name: String,
    val host: String,
    val port: Int = 22,
    val username: String = "root",
    val privateKeyId: Int? = null,
    val remoteHostKey: String = "",
    val connectTimeout: Int = 10,
    val redacted: Boolean = false,
) {
    val address: String get() = if (redacted) "details hidden" else "$username@$host" + if (port != 22) ":$port" else ""
}

/** One entry of `keychaincredential.used_by`. */
data class KeychainUse(val title: String, val unbindMethod: String)

enum class ReplDirection(val label: String) { PUSH("Push"), PULL("Pull") }

enum class ReplTransport(val wire: String, val label: String, val help: String) {
    SSH("SSH", "SSH", "Encrypted. Works everywhere."),
    NETCAT("SSH+NETCAT", "SSH + netcat", "Faster, but the data travels unencrypted. Only for trusted networks."),
    LOCAL("LOCAL", "On this NAS", "Copies snapshots to another pool or dataset on this NAS."),
    ;
    companion object { fun of(s: String?) = entries.firstOrNull { it.wire == s } ?: SSH }
}

enum class Retention(val label: String, val help: String) {
    SOURCE("Same as source", "Snapshots deleted on the source are also deleted on the target."),
    CUSTOM("Custom", "Keep snapshots on the target for a set time."),
    NONE("Keep all", "Never delete snapshots on the target."),
}

enum class ReadonlyPolicy(val label: String, val help: String) {
    SET("Set", "Make the target datasets read-only after each run (recommended)."),
    REQUIRE("Require", "Fail if a target dataset isn't read-only already."),
    IGNORE("Ignore", "Leave the read-only setting alone."),
}

/** When an automatic task runs. */
enum class ReplTiming(val label: String) { AFTER_SNAPSHOTS("After snapshot task"), SCHEDULE("On a schedule"), MANUAL("Only when started") }

/** zettarepl progress while a replication runs (`state.progress`). */
data class ReplProgress(
    val dataset: String?,
    val snapshot: String?,
    val snapshotsSent: Int?,
    val snapshotsTotal: Int?,
    val bytesSent: Long?,
    val bytesTotal: Long?,
)

/** zettarepl state of a task: PENDING, WAITING, RUNNING, FINISHED, ERROR, HOLD. */
data class ReplState(
    val state: String,
    val atMillis: Long? = null,
    val error: String? = null,
    val reason: String? = null,
    val lastSnapshot: String? = null,
    val warnings: List<String> = emptyList(),
    val progress: ReplProgress? = null,
)

data class SnapshotTaskRef(val id: Int, val dataset: String, val namingSchema: String, val recursive: Boolean, val enabled: Boolean, val schedule: CronSchedule) {
    val label: String get() = "$dataset · ${CronText.describe(schedule).replaceFirstChar { it.lowercase() }}"
}

data class ReplicationTask(
    val id: Int,
    val name: String,
    val direction: ReplDirection = ReplDirection.PUSH,
    val transport: ReplTransport = ReplTransport.SSH,
    val sshCredentialsId: Int? = null,
    val sshCredentialsName: String? = null,
    val sourceDatasets: List<String> = emptyList(),
    val targetDataset: String = "",
    val recursive: Boolean = false,
    val exclude: List<String> = emptyList(),
    val periodicSnapshotTasks: List<SnapshotTaskRef> = emptyList(),
    val namingSchema: List<String> = emptyList(),
    val alsoIncludeNamingSchema: List<String> = emptyList(),
    val nameRegex: String? = null,
    val auto: Boolean = true,
    val schedule: CronSchedule? = null,
    val retention: Retention = Retention.NONE,
    val lifetimeValue: Int? = null,
    val lifetimeUnit: String? = null,
    val enabled: Boolean = true,
    val state: ReplState? = null,
    val job: LastJob? = null,
    val jobId: Long? = null,
    val raw: JsonObject = JsonObject(emptyMap()),
) {
    val running: Boolean get() = job?.state?.active == true || state?.state == "RUNNING"
    val failed: Boolean get() = !running && state?.state == "ERROR"
    val local: Boolean get() = transport == ReplTransport.LOCAL
}

/** Editable replication task; numbers stay text while typing. */
data class ReplicationForm(
    val name: String = "",
    val direction: ReplDirection = ReplDirection.PUSH,
    val transport: ReplTransport = ReplTransport.SSH,
    val sshCredentialsId: Int? = null,
    val sudo: Boolean = false,
    val netcatActiveSide: String = "LOCAL",
    val netcatListenAddress: String = "",
    val netcatPortMin: String = "",
    val netcatPortMax: String = "",
    val netcatConnectAddress: String = "",
    val sourceDatasets: List<String> = emptyList(),
    val targetDataset: String = "",
    val recursive: Boolean = false,
    val exclude: String = "",
    val properties: Boolean = true,
    val propertiesExclude: String = "",
    val propertiesOverride: String = "",
    val replicate: Boolean = false,
    val encryption: Boolean = false,
    val encryptionInherit: Boolean = false,
    val encryptionKeyFormat: String = "PASSPHRASE",
    val encryptionKey: String = "",
    val encryptionKeyInTrueNas: Boolean = true,
    val encryptionKeyLocation: String = "",
    val snapshotTaskIds: Set<Int> = emptySet(),
    val namingSchemas: String = "",
    val useRegex: Boolean = false,
    val nameRegex: String = "",
    val timing: ReplTiming = ReplTiming.SCHEDULE,
    val schedule: CronSchedule = CronText.DEFAULT.copy(minute = "0", hour = "0"),
    val onlyMatchingSchedule: Boolean = false,
    val allowFromScratch: Boolean = false,
    val readonly: ReadonlyPolicy = ReadonlyPolicy.SET,
    val holdPendingSnapshots: Boolean = false,
    val retention: Retention = Retention.SOURCE,
    val lifetimeValue: String = "2",
    val lifetimeUnit: String = "WEEK",
    val compression: String = "",
    val speedLimit: String = "",
    val largeBlock: Boolean = true,
    val compressed: Boolean = true,
    val retries: String = "5",
    val enabled: Boolean = true,
)

object ReplicationLogic {
    const val REDACTED = "********"
    const val KEY_IN_TRUENAS = "\$TrueNAS"
    val LIFETIME_UNITS = listOf("HOUR", "DAY", "WEEK", "MONTH", "YEAR")
    val COMPRESSION = listOf("", "LZ4", "PIGZ", "PLZIP")
    private val TIME = Regex("^([01]\\d|2[0-3]):[0-5]\\d$")

    private fun JsonObject.strings(k: String) = this[k].arr()?.mapNotNull { it.prim()?.contentOrNull } ?: emptyList()
    private fun lines(s: String) = s.lines().map { it.trim() }.filter { it.isNotEmpty() }

    // --- keychain ---

    fun keyPair(o: JsonObject): SshKeyPair? {
        if (o.str("type") != "SSH_KEY_PAIR") return null
        val a = o["attributes"].obj()
        return SshKeyPair(
            id = o.long("id")?.toInt() ?: return null,
            name = o.str("name").orEmpty(),
            publicKey = a?.str("public_key")?.takeUnless { it == REDACTED },
            privateKey = a?.str("private_key")?.takeUnless { it == REDACTED },
            redacted = a == null || a.str("private_key") == REDACTED,
        )
    }

    fun connection(o: JsonObject): SshConnection? {
        if (o.str("type") != "SSH_CREDENTIALS") return null
        val id = o.long("id")?.toInt() ?: return null
        val a = o["attributes"].obj() ?: return SshConnection(id, o.str("name").orEmpty(), "", redacted = true)
        return SshConnection(
            id = id, name = o.str("name").orEmpty(), host = a.str("host").orEmpty(), port = a.long("port")?.toInt() ?: 22,
            username = a.str("username") ?: "root", privateKeyId = a.long("private_key")?.toInt(),
            remoteHostKey = a.str("remote_host_key").orEmpty(), connectTimeout = a.long("connect_timeout")?.toInt() ?: 10,
        )
    }

    fun use(o: JsonObject): KeychainUse? = o.str("title")?.let { KeychainUse(it, o.str("unbind_method") ?: "delete") }

    // --- tasks ---

    fun snapshotTaskRef(o: JsonObject): SnapshotTaskRef? = SnapshotTaskRef(
        id = o.long("id")?.toInt() ?: return null,
        dataset = o.str("dataset").orEmpty(),
        namingSchema = o.str("naming_schema").orEmpty(),
        recursive = o.bool("recursive") ?: false,
        enabled = o.bool("enabled") ?: true,
        schedule = ProtectionParsers.cron(o["schedule"].obj()),
    )

    fun state(e: JsonElement?): ReplState? {
        val o = e.obj() ?: return null
        val p = o["progress"].obj()
        return ReplState(
            state = o.str("state")?.uppercase() ?: return null,
            atMillis = parseDate(o["datetime"]),
            error = o.str("error")?.trim()?.takeIf { it.isNotEmpty() },
            reason = o.str("reason")?.takeIf { it.isNotBlank() },
            lastSnapshot = o.str("last_snapshot"),
            warnings = o.strings("warnings"),
            progress = p?.let {
                ReplProgress(it.str("dataset"), it.str("snapshot"), it.long("snapshots_sent")?.toInt(), it.long("snapshots_total")?.toInt(),
                    it.long("bytes_sent"), it.long("bytes_total"))
            },
        )
    }

    fun task(o: JsonObject): ReplicationTask? {
        val creds = o["ssh_credentials"].obj()
        val job = o["job"].obj()
        return ReplicationTask(
            id = o.long("id")?.toInt() ?: return null,
            name = o.str("name").orEmpty(),
            direction = if (o.str("direction") == "PULL") ReplDirection.PULL else ReplDirection.PUSH,
            transport = ReplTransport.of(o.str("transport")),
            sshCredentialsId = creds?.long("id")?.toInt() ?: o.long("ssh_credentials")?.toInt(),
            sshCredentialsName = creds?.str("name"),
            sourceDatasets = o.strings("source_datasets"),
            targetDataset = o.str("target_dataset").orEmpty(),
            recursive = o.bool("recursive") ?: false,
            exclude = o.strings("exclude"),
            periodicSnapshotTasks = o["periodic_snapshot_tasks"].arr()?.mapNotNull { it.obj()?.let(::snapshotTaskRef) }.orEmpty(),
            namingSchema = o.strings("naming_schema"),
            alsoIncludeNamingSchema = o.strings("also_include_naming_schema"),
            nameRegex = o.str("name_regex")?.takeIf { it.isNotEmpty() },
            auto = o.bool("auto") ?: true,
            schedule = o["schedule"].obj()?.let(ProtectionParsers::cron),
            retention = runCatching { Retention.valueOf(o.str("retention_policy")!!) }.getOrDefault(Retention.NONE),
            lifetimeValue = o.long("lifetime_value")?.toInt(),
            lifetimeUnit = o.str("lifetime_unit"),
            enabled = o.bool("enabled") ?: true,
            state = state(o["state"]),
            job = ProtectionParsers.lastJob(job),
            jobId = job?.long("id"),
            raw = o,
        )
    }

    fun form(t: ReplicationTask): ReplicationForm {
        val o = t.raw
        val keyLocation = o.str("encryption_key_location").orEmpty()
        val schemas = if (t.direction == ReplDirection.PUSH) t.alsoIncludeNamingSchema else t.namingSchema
        return ReplicationForm(
            name = t.name, direction = t.direction, transport = t.transport, sshCredentialsId = t.sshCredentialsId,
            sudo = o.bool("sudo") ?: false,
            netcatActiveSide = o.str("netcat_active_side") ?: "LOCAL",
            netcatListenAddress = o.str("netcat_active_side_listen_address").orEmpty(),
            netcatPortMin = o.long("netcat_active_side_port_min")?.toString().orEmpty(),
            netcatPortMax = o.long("netcat_active_side_port_max")?.toString().orEmpty(),
            netcatConnectAddress = o.str("netcat_passive_side_connect_address").orEmpty(),
            sourceDatasets = t.sourceDatasets, targetDataset = t.targetDataset, recursive = t.recursive,
            exclude = t.exclude.joinToString("\n"),
            properties = o.bool("properties") ?: true,
            propertiesExclude = o.strings("properties_exclude").joinToString("\n"),
            propertiesOverride = o["properties_override"].obj()?.entries?.joinToString("\n") { (k, v) -> "$k=${v.prim()?.contentOrNull.orEmpty()}" }.orEmpty(),
            replicate = o.bool("replicate") ?: false,
            encryption = o.bool("encryption") ?: false,
            encryptionInherit = o.bool("encryption_inherit") ?: false,
            encryptionKeyFormat = o.str("encryption_key_format") ?: "PASSPHRASE",
            encryptionKey = o.str("encryption_key").orEmpty(),
            encryptionKeyInTrueNas = keyLocation.isEmpty() || keyLocation == KEY_IN_TRUENAS,
            encryptionKeyLocation = keyLocation.takeUnless { it == KEY_IN_TRUENAS }.orEmpty(),
            snapshotTaskIds = t.periodicSnapshotTasks.map { it.id }.toSet(),
            namingSchemas = schemas.joinToString("\n"),
            useRegex = t.nameRegex != null, nameRegex = t.nameRegex.orEmpty(),
            timing = when {
                !t.auto -> ReplTiming.MANUAL
                t.schedule == null && t.periodicSnapshotTasks.isNotEmpty() -> ReplTiming.AFTER_SNAPSHOTS
                else -> ReplTiming.SCHEDULE
            },
            schedule = t.schedule ?: CronText.DEFAULT.copy(minute = "0", hour = "0"),
            onlyMatchingSchedule = o.bool("only_matching_schedule") ?: false,
            allowFromScratch = o.bool("allow_from_scratch") ?: false,
            readonly = runCatching { ReadonlyPolicy.valueOf(o.str("readonly")!!) }.getOrDefault(ReadonlyPolicy.SET),
            holdPendingSnapshots = o.bool("hold_pending_snapshots") ?: false,
            retention = t.retention,
            lifetimeValue = t.lifetimeValue?.toString() ?: "2",
            lifetimeUnit = t.lifetimeUnit ?: "WEEK",
            compression = o.str("compression").orEmpty(),
            speedLimit = o.long("speed_limit")?.let { (it / 1024).toString() }.orEmpty(),
            largeBlock = o.bool("large_block") ?: true,
            compressed = o.bool("compressed") ?: true,
            retries = o.long("retries")?.toString() ?: "5",
            enabled = t.enabled,
        )
    }

    private fun strArr(list: List<String>) = JsonArray(list.map { JsonPrimitive(it) })
    private fun overrides(s: String): Map<String, String> = lines(s).mapNotNull { l ->
        val i = l.indexOf('=')
        if (i <= 0) null else l.substring(0, i).trim() to l.substring(i + 1).trim()
    }.toMap()

    private fun normalizeSchedule(s: CronSchedule) = s.copy(
        minute = s.minute.trim(), hour = s.hour.trim(), dom = s.dom.trim(), month = s.month.trim(), dow = s.dow.trim(),
    )

    /**
     * Body of `replication.create` / `replication.update` (update merges, so keys this editor doesn't show, such as
     * `restrict_schedule`, `lifetimes`, `embed`, `logging_level`, are left as they are). With [onetime] the shape of
     * `replication.run_onetime` (no name, auto, schedule, only_matching_schedule, enabled).
     */
    fun taskJson(f: ReplicationForm, onetime: Boolean = false): JsonObject = buildJsonObject {
        val push = f.direction == ReplDirection.PUSH
        val local = f.transport == ReplTransport.LOCAL
        val ssh = f.transport == ReplTransport.SSH
        if (!onetime) put("name", f.name.trim())
        put("direction", f.direction.name)
        put("transport", f.transport.wire)
        put("ssh_credentials", if (local) JsonNull else f.sshCredentialsId?.let { JsonPrimitive(it) } ?: JsonNull)
        put("sudo", !local && f.sudo)
        val netcat = f.transport == ReplTransport.NETCAT
        put("netcat_active_side", if (netcat) JsonPrimitive(f.netcatActiveSide) else JsonNull)
        put("netcat_active_side_listen_address", if (netcat) f.netcatListenAddress.trim().ifEmpty { null }?.let { JsonPrimitive(it) } ?: JsonNull else JsonNull)
        put("netcat_active_side_port_min", if (netcat) f.netcatPortMin.trim().toIntOrNull()?.let { JsonPrimitive(it) } ?: JsonNull else JsonNull)
        put("netcat_active_side_port_max", if (netcat) f.netcatPortMax.trim().toIntOrNull()?.let { JsonPrimitive(it) } ?: JsonNull else JsonNull)
        put("netcat_passive_side_connect_address", if (netcat) f.netcatConnectAddress.trim().ifEmpty { null }?.let { JsonPrimitive(it) } ?: JsonNull else JsonNull)
        put("source_datasets", strArr(f.sourceDatasets))
        put("target_dataset", f.targetDataset.trim().trim('/'))
        put("recursive", f.recursive)
        put("exclude", strArr(if (f.recursive && !f.replicate) lines(f.exclude) else emptyList()))
        put("properties", f.properties)
        put("properties_exclude", strArr(if (f.properties) lines(f.propertiesExclude) else emptyList()))
        put("properties_override", buildJsonObject { if (f.properties) overrides(f.propertiesOverride).forEach { (k, v) -> put(k, v) } })
        put("replicate", f.replicate)
        put("encryption", f.encryption)
        if (f.encryption && !f.encryptionInherit) {
            put("encryption_inherit", false)
            put("encryption_key", f.encryptionKey)
            put("encryption_key_format", f.encryptionKeyFormat)
            put("encryption_key_location", if (f.encryptionKeyInTrueNas) KEY_IN_TRUENAS else f.encryptionKeyLocation.trim())
        } else {
            put("encryption_inherit", if (f.encryption) JsonPrimitive(true) else JsonNull)
            put("encryption_key", JsonNull); put("encryption_key_format", JsonNull); put("encryption_key_location", JsonNull)
        }
        val schemas = if (f.useRegex) emptyList() else lines(f.namingSchemas)
        put("periodic_snapshot_tasks", JsonArray(if (push && !f.useRegex) f.snapshotTaskIds.sorted().map { JsonPrimitive(it) } else emptyList()))
        put("naming_schema", strArr(if (push) emptyList() else schemas))
        put("also_include_naming_schema", strArr(if (push) schemas else emptyList()))
        put("name_regex", if (f.useRegex) JsonPrimitive(f.nameRegex.trim()) else JsonNull)
        if (!onetime) {
            val timing = effectiveTiming(f)
            put("auto", timing != ReplTiming.MANUAL)
            put("schedule", if (timing == ReplTiming.SCHEDULE) ProtectionParsers.cronJson(normalizeSchedule(f.schedule), withMinute = true, withWindow = true) else JsonNull)
            put("only_matching_schedule", timing == ReplTiming.SCHEDULE && f.onlyMatchingSchedule)
            put("enabled", f.enabled)
        }
        put("allow_from_scratch", f.allowFromScratch)
        put("readonly", f.readonly.name)
        put("hold_pending_snapshots", push && f.holdPendingSnapshots)
        put("retention_policy", f.retention.name)
        if (f.retention == Retention.CUSTOM) {
            put("lifetime_value", f.lifetimeValue.trim().toIntOrNull() ?: 2)
            put("lifetime_unit", f.lifetimeUnit)
        } else {
            put("lifetime_value", JsonNull); put("lifetime_unit", JsonNull)
            put("lifetimes", JsonArray(emptyList()))
        }
        put("compression", if (ssh && f.compression.isNotEmpty()) JsonPrimitive(f.compression) else JsonNull)
        put("speed_limit", if (ssh) f.speedLimit.trim().toLongOrNull()?.takeIf { it > 0 }?.let { JsonPrimitive(it * 1024) } ?: JsonNull else JsonNull)
        put("large_block", f.largeBlock)
        put("compressed", f.compressed)
        put("retries", f.retries.trim().toIntOrNull() ?: 5)
    }

    /** Pull tasks and tasks without a bound snapshot task can't run "after the snapshot task". */
    fun effectiveTiming(f: ReplicationForm): ReplTiming =
        if (f.timing == ReplTiming.AFTER_SNAPSHOTS && (f.direction == ReplDirection.PULL || f.useRegex || f.snapshotTaskIds.isEmpty())) ReplTiming.SCHEDULE else f.timing

    private fun isChild(child: String, parent: String) = child == parent || child.startsWith("$parent/")

    /** Local checks mirroring `ReplicationService._validate` (field → message). */
    fun errors(f: ReplicationForm, snapshotTasks: List<SnapshotTaskRef> = emptyList(), onetime: Boolean = false): Map<String, String> = buildMap {
        val push = f.direction == ReplDirection.PUSH
        if (!onetime && f.name.isBlank()) put("name", "Give the task a name")
        if (f.transport != ReplTransport.LOCAL && f.sshCredentialsId == null) put("ssh_credentials", "Choose an SSH connection")
        if (f.sourceDatasets.isEmpty()) put("source_datasets", "Choose at least one dataset")
        if (f.targetDataset.isBlank()) put("target_dataset", "Choose where the snapshots go")
        else if (f.transport == ReplTransport.LOCAL && f.sourceDatasets.any { isChild(f.targetDataset.trim('/'), it) }) put("target_dataset", "Can't be inside a source dataset")
        val excl = lines(f.exclude)
        if (excl.isNotEmpty() && !f.recursive) put("exclude", "Only with \"Include child datasets\"")
        excl.firstOrNull { e -> f.sourceDatasets.none { e.startsWith("$it/") } }?.let { put("exclude", "$it isn't inside a source dataset") }
        if (f.replicate) {
            when {
                !f.recursive -> put("recursive", "Full filesystem replication needs child datasets")
                !f.properties -> put("properties", "Full filesystem replication needs properties")
            }
            if (excl.isNotEmpty()) put("exclude", "Not with full filesystem replication")
            if (f.retention != Retention.SOURCE) put("retention_policy", "Full filesystem replication keeps snapshots like the source")
        }
        val schemas = lines(f.namingSchemas)
        if (f.useRegex) {
            if (f.nameRegex.isBlank()) put("name_regex", "Enter a regular expression")
            else runCatching { Regex("(${f.nameRegex.trim()})$") }.onFailure { put("name_regex", "Not a valid regular expression") }
            if (f.retention == Retention.CUSTOM) put("retention_policy", "Use Same as source or Keep all with a regular expression")
        } else {
            schemas.firstOrNull { s -> listOf("%Y", "%m", "%d", "%H", "%M").any { it !in s } }?.let { put("naming_schema", "$it needs %Y %m %d %H and %M") }
            if (push && f.snapshotTaskIds.isEmpty() && schemas.isEmpty()) put("periodic_snapshot_tasks", "Pick a periodic snapshot task or enter a naming schema")
            if (!push && schemas.isEmpty()) put("naming_schema", "Enter the naming schema of the snapshots to pull")
        }
        if (push && !f.useRegex) {
            val bound = snapshotTasks.filter { it.id in f.snapshotTaskIds }
            if (f.enabled && !onetime) bound.firstOrNull { !it.enabled }?.let { put("periodic_snapshot_tasks", "The snapshot task for ${it.dataset} is turned off") }
            if (f.replicate) bound.firstOrNull { t -> !t.recursive || f.sourceDatasets.none { isChild(it, t.dataset) } }?.let {
                put("periodic_snapshot_tasks", "Full filesystem replication needs a recursive snapshot task of the source")
            }
        }
        if (!onetime && effectiveTiming(f) == ReplTiming.SCHEDULE && (f.timing != ReplTiming.AFTER_SNAPSHOTS || !push)) {
            if (!CronText.isValid(f.schedule)) put("schedule", "Fix the schedule")
            val b = f.schedule.begin ?: "00:00"; val e = f.schedule.end ?: "23:59"
            if (!TIME.matches(b) || !TIME.matches(e)) put("schedule", "Times look like 08:00")
            else if (b > e) put("schedule", "The window must start before it ends")
        }
        if (!onetime && push && f.timing == ReplTiming.AFTER_SNAPSHOTS && effectiveTiming(f) != ReplTiming.AFTER_SNAPSHOTS) put("auto", "Pick a periodic snapshot task first")
        if (f.retention == Retention.CUSTOM && (f.lifetimeValue.trim().toIntOrNull() ?: 0) < 1) put("lifetime_value", "A whole number of 1 or more")
        if (f.encryption && !f.encryptionInherit) {
            when {
                f.encryptionKey.isEmpty() -> put("encryption_key", "Required")
                f.encryptionKeyFormat == "HEX" && !Regex("^[0-9a-fA-F]{64}$").matches(f.encryptionKey.trim()) -> put("encryption_key", "64 hex digits")
                f.encryptionKeyFormat == "PASSPHRASE" && f.encryptionKey.length < 8 -> put("encryption_key", "At least 8 characters")
            }
            if (!f.encryptionKeyInTrueNas && !f.encryptionKeyLocation.trim().startsWith("/")) put("encryption_key_location", "A file path on the target, e.g. /root/key")
        }
        if (f.transport == ReplTransport.NETCAT) {
            val min = f.netcatPortMin.trim(); val max = f.netcatPortMax.trim()
            listOf(min, max).filter { it.isNotEmpty() }.forEach { p -> if ((p.toIntOrNull() ?: 0) !in 1..65535) put("netcat_active_side_port_min", "Ports are 1–65535") }
            if (min.toIntOrNull() != null && max.toIntOrNull() != null && min.toInt() > max.toInt()) put("netcat_active_side_port_max", "Must be at least the lowest port")
        }
        if (f.transport == ReplTransport.SSH && f.speedLimit.isNotBlank() && (f.speedLimit.trim().toLongOrNull() ?: 0) < 1) put("speed_limit", "KiB/s, or empty for unlimited")
        if ((f.retries.trim().toIntOrNull() ?: 0) < 1) put("retries", "1 or more")
        if (f.properties) lines(f.propertiesOverride).firstOrNull { it.indexOf('=') <= 0 }?.let { put("properties_override", "Use property=value, one per line") }
    }

    /** Middleware field names → editor fields (prefixes and list indexes are stripped by [app.truenascompanion.data.api.FieldError]). */
    val FIELD_ALIASES = mapOf(
        "minute" to "schedule", "hour" to "schedule", "dom" to "schedule", "month" to "schedule", "dow" to "schedule",
        "begin" to "schedule", "end" to "schedule", "also_include_naming_schema" to "naming_schema", "lifetime_unit" to "lifetime_value",
        "encryption_key_format" to "encryption_key", "encryption_inherit" to "encryption_key", "lifetimes" to "retention_policy",
        "netcat_active_side" to "netcat_active_side_port_min", "netcat_active_side_listen_address" to "netcat_active_side_port_min",
        "netcat_passive_side_connect_address" to "netcat_active_side_port_min", "only_matching_schedule" to "schedule",
    )
    val FIELDS = setOf(
        "name", "direction", "transport", "ssh_credentials", "source_datasets", "target_dataset", "recursive", "exclude", "properties",
        "properties_override", "retention_policy", "lifetime_value", "encryption_key", "encryption_key_location", "periodic_snapshot_tasks",
        "naming_schema", "name_regex", "schedule", "auto", "netcat_active_side_port_min", "netcat_active_side_port_max", "speed_limit",
        "compression", "retries", "hold_pending_snapshots",
    )

    // --- wording ---

    fun route(t: ReplicationTask): String {
        val src = t.sourceDatasets.joinToString(", ").ifEmpty { "?" }
        val remote = when (t.transport) {
            ReplTransport.LOCAL -> "this NAS"
            else -> t.sshCredentialsName ?: "SSH connection"
        }
        return if (t.direction == ReplDirection.PUSH) "$src\n→ $remote: ${t.targetDataset}" else "$remote: $src\n← ${t.targetDataset}"
    }

    fun whenText(t: ReplicationTask): String = when {
        !t.enabled -> "Off · runs only when started here"
        !t.auto -> "Runs only when started"
        t.schedule != null -> CronText.describe(t.schedule)
        t.periodicSnapshotTasks.isNotEmpty() -> "After the snapshot task" + if (t.periodicSnapshotTasks.size > 1) "s" else ""
        else -> "No schedule"
    }

    fun progressText(t: ReplicationTask): String? {
        t.job?.progressText?.let { return it }
        val p = t.state?.progress ?: return null
        return buildString {
            p.dataset?.let { append(it) }
            p.snapshot?.let { append("@").append(it) }
            if (p.snapshotsSent != null && p.snapshotsTotal != null) append(" (${p.snapshotsSent + 1} of ${p.snapshotsTotal})")
            if (p.bytesTotal != null && p.bytesTotal > 0) append(" · ${Format.bytes(p.bytesSent ?: 0)} of ${Format.bytes(p.bytesTotal)}")
        }.ifBlank { null }
    }

    fun percent(t: ReplicationTask): Double? = t.job?.percent ?: t.state?.progress?.let { p ->
        val total = p.snapshotsTotal ?: return@let null
        if (total <= 0) null else 100.0 * (p.snapshotsSent ?: 0) / total
    }

    /** Last-run state: zettarepl's state, or the last job when zettarepl has nothing yet. */
    fun lastState(t: ReplicationTask): ReplState? = t.state?.takeUnless { it.state == "PENDING" && t.job != null } ?: t.job?.let { j ->
        ReplState(
            when (j.state) {
                app.truenascompanion.data.model.JobState.SUCCESS -> "FINISHED"
                app.truenascompanion.data.model.JobState.FAILED, app.truenascompanion.data.model.JobState.ABORTED -> "ERROR"
                else -> j.state.name
            },
            j.finishedMillis ?: j.startedMillis, j.error,
        )
    }

    /** Checks for the restore dialog: a name and a target dataset that differs from the task's source. */
    fun restoreErrors(t: ReplicationTask, name: String, target: String): Map<String, String> = buildMap {
        if (name.isBlank()) put("name", "Give the new task a name")
        val tgt = target.trim().trim('/')
        when {
            tgt.isEmpty() -> put("target_dataset", "Choose where to restore to")
            t.direction == ReplDirection.PUSH && t.local && t.sourceDatasets.any { isChild(tgt, it) } -> put("target_dataset", "Pick a dataset outside the source")
        }
    }

    /** Problems with a pasted private key that TrueNAS would reject (it runs `ssh-keygen -y`). */
    fun privateKeyError(key: String, publicKey: String): String? {
        val k = key.trim()
        if (k.isEmpty()) return if (publicKey.isBlank()) "Paste a private key or generate one" else null
        if (!k.startsWith("-----BEGIN") || !k.contains("PRIVATE KEY-----")) return "Paste the whole key, from -----BEGIN … to END … PRIVATE KEY-----"
        if (k.contains("ENCRYPTED")) return "Keys with a passphrase aren't allowed"
        return null
    }

    fun publicKeyError(key: String): String? {
        val k = key.trim()
        if (k.isEmpty()) return null
        val parts = k.split(Regex("\\s+"))
        return if (parts.size < 2 || !(parts[0].startsWith("ssh-") || parts[0].startsWith("ecdsa-") || parts[0].startsWith("sk-"))) "Looks like ssh-ed25519 AAAA… comment" else null
    }
}
