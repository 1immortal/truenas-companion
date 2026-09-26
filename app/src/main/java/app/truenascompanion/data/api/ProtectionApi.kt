package app.truenascompanion.data.api

import app.truenascompanion.data.model.BackupKind
import app.truenascompanion.data.model.BackupTask
import app.truenascompanion.data.model.CronSchedule
import app.truenascompanion.data.model.JobState
import app.truenascompanion.data.model.LastJob
import app.truenascompanion.data.model.ScrubTask
import app.truenascompanion.data.model.SmartSchedule
import app.truenascompanion.data.model.SmartTestType
import app.truenascompanion.data.model.Snapshot
import app.truenascompanion.data.model.SnapshotTask
import app.truenascompanion.data.model.TaskRunState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** Input for a new or edited periodic snapshot task. */
data class SnapshotTaskInput(
    val dataset: String,
    val recursive: Boolean,
    val exclude: List<String>,
    val lifetimeValue: Int,
    val lifetimeUnit: String,
    val namingSchema: String,
    val schedule: CronSchedule,
    val enabled: Boolean,
    val allowEmpty: Boolean,
)

/**
 * Data protection calls (0.5.0). Method names and payloads were checked against the TrueNAS 25.10.3 middleware source:
 * `pool.snapshot.*`, `pool.snapshottask.*`, `pool.scrub.*`, `cronjob.*` (SMART, see [SmartSchedule]),
 * `replication.*`, `cloudsync.*`, `rsynctask.*`.
 */
class ProtectionApi(private val api: TrueNasApi) {

    // --- Snapshots ---

    /** Snapshots of one dataset (not its children), newest first. */
    suspend fun snapshots(dataset: String): List<Snapshot> {
        val res = api.rpc(
            "pool.snapshot.query",
            filter("dataset", "=", JsonPrimitive(dataset)),
            buildJsonObject {
                put("extra", buildJsonObject {
                    putJsonArray("properties") { add(JsonPrimitive("used")); add(JsonPrimitive("referenced")); add(JsonPrimitive("creation")) }
                    put("holds", true)
                })
            },
        )
        return res.arr().orEmpty().mapNotNull { it.obj()?.let(ProtectionParsers::snapshot) }
            .sortedByDescending { it.createdMillis ?: 0L }
    }

    suspend fun createSnapshot(dataset: String, name: String, recursive: Boolean): Snapshot? =
        api.rpc("pool.snapshot.create", buildJsonObject {
            put("dataset", dataset); put("name", name); put("recursive", recursive)
        }).obj()?.let(ProtectionParsers::snapshot)

    suspend fun deleteSnapshot(id: String) {
        api.rpc("pool.snapshot.delete", JsonPrimitive(id), buildJsonObject { put("recursive", false) })
    }

    /** [destroyNewer] = `recursive` (zfs rollback -r): destroys snapshots newer than [id]. Without it ZFS refuses if any exist. */
    suspend fun rollback(id: String, destroyNewer: Boolean) {
        api.rpc("pool.snapshot.rollback", JsonPrimitive(id), buildJsonObject {
            put("recursive", destroyNewer); put("recursive_clones", false); put("force", false)
        })
    }

    suspend fun clone(id: String, newDataset: String) {
        api.rpc("pool.snapshot.clone", buildJsonObject { put("snapshot", id); put("dataset_dst", newDataset) })
    }

    suspend fun hold(id: String) { api.rpc("pool.snapshot.hold", JsonPrimitive(id)) }
    suspend fun release(id: String) { api.rpc("pool.snapshot.release", JsonPrimitive(id)) }

    // --- Periodic snapshot tasks ---

    suspend fun snapshotTasks(): List<SnapshotTask> =
        api.rpc("pool.snapshottask.query").arr().orEmpty().mapNotNull { it.obj()?.let(ProtectionParsers::snapshotTask) }

    suspend fun createSnapshotTask(t: SnapshotTaskInput) { api.rpc("pool.snapshottask.create", ProtectionParsers.snapshotTaskJson(t)) }
    /** Snapshots taken so far keep their removal date even if the new schedule/naming no longer matches them. */
    suspend fun updateSnapshotTask(id: Int, t: SnapshotTaskInput) {
        api.rpc("pool.snapshottask.update", JsonPrimitive(id), JsonObject(ProtectionParsers.snapshotTaskJson(t) + ("fixate_removal_date" to JsonPrimitive(true))))
    }
    suspend fun setSnapshotTaskEnabled(id: Int, enabled: Boolean) {
        api.rpc("pool.snapshottask.update", JsonPrimitive(id), buildJsonObject { put("enabled", enabled) })
    }
    /** Existing snapshots keep their current removal date (`fixate_removal_date`), like the web UI's default. */
    suspend fun deleteSnapshotTask(id: Int) {
        api.rpc("pool.snapshottask.delete", JsonPrimitive(id), buildJsonObject { put("fixate_removal_date", true) })
    }
    suspend fun runSnapshotTask(id: Int) { api.rpc("pool.snapshottask.run", JsonPrimitive(id)) }

    // --- Scrubs ---

    suspend fun scrubTasks(): List<ScrubTask> =
        api.rpc("pool.scrub.query").arr().orEmpty().mapNotNull { it.obj()?.let(ProtectionParsers::scrubTask) }

    /** `pool.scrub.scrub(name, "START")` job: it stays running (with progress) until the scrub ends. Returns the job id. */
    suspend fun startScrub(pool: String): Long? = api.rpc("pool.scrub.scrub", JsonPrimitive(pool), JsonPrimitive("START")).jobId()
    suspend fun stopScrub(pool: String) { api.rpc("pool.scrub.scrub", JsonPrimitive(pool), JsonPrimitive("STOP")) }
    suspend fun pauseScrub(pool: String) { api.rpc("pool.scrub.scrub", JsonPrimitive(pool), JsonPrimitive("PAUSE")) }

    suspend fun updateScrubTask(id: Int, threshold: Int, schedule: CronSchedule, enabled: Boolean) {
        api.rpc("pool.scrub.update", JsonPrimitive(id), buildJsonObject {
            put("threshold", threshold); put("enabled", enabled); put("schedule", ProtectionParsers.cronJson(schedule, withMinute = true))
        })
    }

    suspend fun createScrubTask(poolId: Long, threshold: Int, schedule: CronSchedule) {
        api.rpc("pool.scrub.create", buildJsonObject {
            put("pool", poolId); put("threshold", threshold); put("enabled", true); put("schedule", ProtectionParsers.cronJson(schedule, withMinute = true))
        })
    }

    // --- SMART (25.10: cron jobs, see SmartSchedule) ---

    suspend fun smartSchedules(): List<SmartSchedule> =
        api.rpc("cronjob.query", filter("command", "^", JsonPrimitive(SMART_PREFIX)))
            .arr().orEmpty().mapNotNull { it.obj()?.let(ProtectionParsers::smartSchedule) }

    suspend fun createSmartSchedule(type: SmartTestType, disks: List<String>, schedule: CronSchedule, description: String) {
        api.rpc("cronjob.create", smartCronJson(type, disks, schedule, description, enabled = true))
    }

    suspend fun updateSmartSchedule(s: SmartSchedule) {
        api.rpc("cronjob.update", JsonPrimitive(s.cronId), smartCronJson(s.type, s.disks, s.schedule, s.description, s.enabled))
    }

    suspend fun setSmartScheduleEnabled(cronId: Int, enabled: Boolean) {
        api.rpc("cronjob.update", JsonPrimitive(cronId), buildJsonObject { put("enabled", enabled) })
    }

    suspend fun deleteSmartSchedule(cronId: Int) { api.rpc("cronjob.delete", JsonPrimitive(cronId)) }

    /** Runs a schedule now (`cronjob.run` job, also for disabled jobs). Returns the job id. */
    suspend fun runSmartSchedule(cronId: Int): Long? = api.rpc("cronjob.run", JsonPrimitive(cronId), JsonPrimitive(false)).jobId()

    /**
     * Starts a one-off test. 25.10 has no public "run a SMART test" method, so this creates a *disabled* cron job with
     * the same command TrueNAS' own migration uses, runs it once with `cronjob.run` and deletes it again.
     */
    suspend fun runSmartTestNow(type: SmartTestType, disks: List<String>, awaitJob: suspend (Long) -> Unit) {
        val created = api.rpc(
            "cronjob.create",
            smartCronJson(type, disks, CronSchedule(minute = "00", hour = "00", dom = "1", month = "1", dow = "*"), ONE_OFF_DESCRIPTION, enabled = false),
        ).obj()
        val id = created?.long("id")?.toInt() ?: throw TrueNasException.JobFailed("TrueNAS did not create the test job")
        try {
            api.rpc("cronjob.run", JsonPrimitive(id), JsonPrimitive(false)).jobId()?.let { awaitJob(it) }
        } finally {
            runCatching { api.rpc("cronjob.delete", JsonPrimitive(id)) }
        }
    }

    /** Leftover one-off jobs (e.g. the app was killed mid-way) are cleaned up here. */
    suspend fun cleanupOneOffSmartJobs(schedules: List<SmartSchedule>) {
        schedules.filter { it.description == ONE_OFF_DESCRIPTION && !it.enabled }.forEach { runCatching { deleteSmartSchedule(it.cronId) } }
    }

    // --- Backup tasks ---

    suspend fun backupTasks(): List<BackupTask> {
        val out = mutableListOf<BackupTask>()
        runCatching { api.rpc("replication.query") }.getOrNull()?.arr()?.forEach { e -> e.obj()?.let(ProtectionParsers::replication)?.let(out::add) }
        runCatching { api.rpc("cloudsync.query") }.getOrNull()?.arr()?.forEach { e -> e.obj()?.let(ProtectionParsers::cloudSync)?.let(out::add) }
        runCatching { api.rpc("rsynctask.query") }.getOrNull()?.arr()?.forEach { e -> e.obj()?.let(ProtectionParsers::rsync)?.let(out::add) }
        return out
    }

    /** Starts a run now and returns the job id. */
    suspend fun runBackup(t: BackupTask): Long? = when (t.kind) {
        BackupKind.REPLICATION -> api.rpc("replication.run", JsonPrimitive(t.id))
        BackupKind.CLOUD_SYNC -> api.rpc("cloudsync.sync", JsonPrimitive(t.id), buildJsonObject { put("dry_run", false) })
        BackupKind.RSYNC -> api.rpc("rsynctask.run", JsonPrimitive(t.id))
    }.jobId()

    suspend fun setBackupEnabled(t: BackupTask, enabled: Boolean) {
        when (t.kind) {
            BackupKind.REPLICATION -> api.rpc("replication.update", JsonPrimitive(t.id), buildJsonObject { put("enabled", enabled) })
            BackupKind.CLOUD_SYNC -> api.rpc("cloudsync.update", JsonPrimitive(t.id), buildJsonObject { put("enabled", enabled) })
            // validate_rpath defaults to true on update and would re-check the remote over SSH just to flip a switch.
            BackupKind.RSYNC -> api.rpc("rsynctask.update", JsonPrimitive(t.id), buildJsonObject { put("enabled", enabled); put("validate_rpath", false) })
        }
    }

    /** Full job entry (for the log excerpt of a finished run). */
    suspend fun job(id: Long): LastJob? =
        api.rpc("core.get_jobs", filter("id", "=", JsonPrimitive(id))).arr()?.firstOrNull()?.obj()?.let(ProtectionParsers::lastJob)

    companion object {
        const val SMART_PREFIX = "midclt call disk.smart_test"
        const val ONE_OFF_DESCRIPTION = "S.M.A.R.T. Test: one-off (TrueNAS Companion)"

        private fun filter(field: String, op: String, value: JsonElement) =
            buildJsonArray { add(buildJsonArray { add(JsonPrimitive(field)); add(JsonPrimitive(op)); add(value) }) }

        private fun JsonElement.jobId(): Long? = prim()?.takeUnless { it.isString }?.longOrNull

        /** Same command format as TrueNAS' `drop_smart` migration. Identifiers are validated so the shell quoting is safe. */
        fun smartCommand(type: SmartTestType, disks: List<String>): String {
            require(disks.isNotEmpty()) { "Pick at least one disk" }
            disks.forEach { require(it == "*" || SAFE_ID.matches(it)) { "Unexpected disk identifier: $it" } }
            val json = JsonArray(disks.map { JsonPrimitive(it) }).toString()
            return "$SMART_PREFIX ${type.name} '$json'"
        }

        private val SAFE_ID = Regex("""[A-Za-z0-9{}_.:/\-]+""")

        internal fun smartCronJson(type: SmartTestType, disks: List<String>, schedule: CronSchedule, description: String, enabled: Boolean) =
            buildJsonObject {
                put("command", smartCommand(type, disks))
                put("user", "root")
                put("description", description.ifBlank { "S.M.A.R.T. Test" }.let { if (it.startsWith("S.M.A.R.T. Test")) it else "S.M.A.R.T. Test: $it" })
                put("enabled", enabled)
                put("stdout", true)
                put("stderr", true)
                put("schedule", ProtectionParsers.cronJson(schedule, withMinute = true))
            }
    }
}

object ProtectionParsers {
    private val json = Json { ignoreUnknownKeys = true }

    private fun JsonObject.prop(key: String): String? = this["properties"].obj()?.get(key).obj()?.let { it.str("rawvalue") ?: it.str("value") }

    fun snapshot(o: JsonObject): Snapshot? {
        val id = o.str("id") ?: o.str("name") ?: return null
        return Snapshot(
            id = id,
            dataset = o.str("dataset") ?: id.substringBefore('@'),
            name = o.str("snapshot_name") ?: id.substringAfter('@'),
            createdMillis = o.prop("creation")?.toLongOrNull()?.times(1000),
            usedBytes = o.prop("used")?.toLongOrNull(),
            referencedBytes = o.prop("referenced")?.toLongOrNull(),
            held = o["holds"].obj()?.isNotEmpty() == true,
        )
    }

    fun cron(o: JsonObject?): CronSchedule = CronSchedule(
        minute = o?.str("minute") ?: "00",
        hour = o?.str("hour") ?: "*",
        dom = o?.str("dom") ?: "*",
        month = o?.str("month") ?: "*",
        dow = o?.str("dow") ?: "*",
        begin = o?.str("begin"),
        end = o?.str("end"),
    )

    fun cronJson(s: CronSchedule, withMinute: Boolean = true, withWindow: Boolean = false) = buildJsonObject {
        if (withMinute) put("minute", s.minute)
        put("hour", s.hour); put("dom", s.dom); put("month", s.month); put("dow", s.dow)
        if (withWindow) { put("begin", s.begin ?: "00:00"); put("end", s.end ?: "23:59") }
    }

    fun taskState(e: JsonElement?): TaskRunState? {
        val o = e.obj() ?: return null
        val state = o.str("state") ?: return null
        return TaskRunState(state.uppercase(), parseDate(o["datetime"]), o.str("error")?.trim()?.takeIf { it.isNotEmpty() })
    }

    fun snapshotTask(o: JsonObject): SnapshotTask? = SnapshotTask(
        id = o.long("id")?.toInt() ?: return null,
        dataset = o.str("dataset") ?: return null,
        recursive = o.bool("recursive") ?: false,
        exclude = o["exclude"].arr()?.mapNotNull { it.prim()?.contentOrNull }.orEmpty(),
        lifetimeValue = o.long("lifetime_value")?.toInt() ?: 2,
        lifetimeUnit = o.str("lifetime_unit") ?: "WEEK",
        namingSchema = o.str("naming_schema") ?: "auto-%Y-%m-%d_%H-%M",
        schedule = cron(o["schedule"].obj()),
        enabled = o.bool("enabled") ?: true,
        allowEmpty = o.bool("allow_empty") ?: true,
        state = taskState(o["state"]),
    )

    fun snapshotTaskJson(t: SnapshotTaskInput) = buildJsonObject {
        put("dataset", t.dataset)
        put("recursive", t.recursive)
        putJsonArray("exclude") { t.exclude.forEach { add(JsonPrimitive(it)) } }
        put("lifetime_value", t.lifetimeValue)
        put("lifetime_unit", t.lifetimeUnit)
        put("naming_schema", t.namingSchema)
        put("enabled", t.enabled)
        put("allow_empty", t.allowEmpty)
        put("schedule", cronJson(t.schedule, withMinute = true, withWindow = true))
    }

    fun scrubTask(o: JsonObject): ScrubTask? = ScrubTask(
        id = o.long("id")?.toInt() ?: return null,
        poolId = o.long("pool") ?: 0,
        poolName = o.str("pool_name") ?: "?",
        threshold = o.long("threshold")?.toInt() ?: 35,
        description = o.str("description").orEmpty(),
        schedule = cron(o["schedule"].obj()),
        enabled = o.bool("enabled") ?: true,
    )

    private val SMART_CMD = Regex("""midclt call disk\.smart_test\s+(\w+)\s+'?(\[.*])'?""")

    fun smartSchedule(o: JsonObject): SmartSchedule? {
        val cmd = o.str("command") ?: return null
        val m = SMART_CMD.find(cmd) ?: return null
        val type = runCatching { SmartTestType.valueOf(m.groupValues[1].uppercase()) }.getOrNull() ?: return null
        val disks = runCatching { json.parseToJsonElement(m.groupValues[2]).arr()?.mapNotNull { it.prim()?.contentOrNull } }.getOrNull() ?: return null
        return SmartSchedule(
            cronId = o.long("id")?.toInt() ?: return null,
            type = type,
            disks = disks,
            schedule = cron(o["schedule"].obj()),
            enabled = o.bool("enabled") ?: true,
            description = o.str("description").orEmpty(),
        )
    }

    fun lastJob(o: JsonObject?): LastJob? {
        o ?: return null
        val progress = o["progress"].obj()
        return LastJob(
            state = runCatching { JobState.valueOf(o.str("state")!!.uppercase()) }.getOrDefault(JobState.UNKNOWN),
            startedMillis = parseDate(o["time_started"]),
            finishedMillis = parseDate(o["time_finished"]),
            error = o.str("error")?.trim()?.takeIf { it.isNotEmpty() },
            logExcerpt = o.str("logs_excerpt")?.takeIf { it.isNotBlank() },
            percent = progress?.double("percent"),
            progressText = progress?.str("description")?.takeIf { it.isNotBlank() },
        )
    }

    fun replication(o: JsonObject): BackupTask? {
        val sources = o["source_datasets"].arr()?.mapNotNull { it.prim()?.contentOrNull }.orEmpty()
        val direction = o.str("direction") ?: "PUSH"
        val transport = o.str("transport") ?: ""
        return BackupTask(
            kind = BackupKind.REPLICATION,
            id = o.long("id")?.toInt() ?: return null,
            name = o.str("name") ?: "Replication",
            detail = buildString {
                append(sources.joinToString(", ").ifBlank { "?" })
                append(if (direction == "PULL") " ← " else " → ")
                append(o.str("target_dataset") ?: "?")
                if (transport == "LOCAL") append(" (local)")
            },
            enabled = o.bool("enabled") ?: true,
            schedule = o["schedule"].obj()?.let(::cron),
            lastJob = lastJob(o["job"].obj()),
            state = taskState(o["state"]),
        )
    }

    fun cloudSync(o: JsonObject): BackupTask? {
        val provider = o["credentials"].obj()?.let { c -> c["provider"].obj()?.str("type") ?: c.str("provider") ?: c.str("name") }
        val direction = o.str("direction") ?: "PUSH"
        return BackupTask(
            kind = BackupKind.CLOUD_SYNC,
            id = o.long("id")?.toInt() ?: return null,
            name = o.str("description")?.takeIf { it.isNotBlank() } ?: "Cloud sync ${o.long("id")}",
            detail = listOfNotNull(
                (if (direction == "PULL") "Pull to " else "Push from ") + (o.str("path") ?: "?"),
                provider?.let { prettyProvider(it) },
                o.str("transfer_mode")?.lowercase(),
            ).joinToString(" · "),
            enabled = o.bool("enabled") ?: true,
            schedule = o["schedule"].obj()?.let(::cron),
            lastJob = lastJob(o["job"].obj()),
            state = null,
            locked = o.bool("locked") ?: false,
        )
    }

    fun rsync(o: JsonObject): BackupTask? {
        val remote = listOfNotNull(o.str("remotehost"), o.str("remotemodule")?.takeIf { it.isNotBlank() } ?: o.str("remotepath")?.takeIf { it.isNotBlank() })
            .joinToString(if (o.str("mode") == "MODULE") "::" else ":")
        val direction = o.str("direction") ?: "PUSH"
        return BackupTask(
            kind = BackupKind.RSYNC,
            id = o.long("id")?.toInt() ?: return null,
            name = o.str("desc")?.takeIf { it.isNotBlank() } ?: "Rsync ${o.long("id")}",
            detail = (o.str("path") ?: "?") + (if (direction == "PULL") " ← " else " → ") + remote.ifBlank { "?" },
            enabled = o.bool("enabled") ?: true,
            schedule = o["schedule"].obj()?.let(::cron),
            lastJob = lastJob(o["job"].obj()),
            state = null,
            locked = o.bool("locked") ?: false,
        )
    }

    private fun prettyProvider(p: String) = when (p.uppercase()) {
        "S3" -> "S3"; "B2" -> "Backblaze B2"; "GOOGLE_DRIVE" -> "Google Drive"; "DROPBOX" -> "Dropbox"; "ONEDRIVE" -> "OneDrive"
        "AZUREBLOB" -> "Azure Blob"; "STORJ_IX" -> "Storj"; "SFTP" -> "SFTP"; "WEBDAV" -> "WebDAV"; "PCLOUD" -> "pCloud"
        else -> p.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }
    }
}
