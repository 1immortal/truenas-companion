package app.truenascompanion.data.cloud

import app.truenascompanion.data.api.ProtectionParsers
import app.truenascompanion.data.model.CronSchedule
import app.truenascompanion.data.model.LastJob
import app.truenascompanion.data.tasks.CronText
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** One entry of `cloudsync.providers` (TrueNAS 25.10). [taskSchema]: the task attribute keys this provider takes. */
data class CloudProvider(
    val name: String,
    val title: String,
    /** The web UI's OAuth helper page (www.truenas.com/oauth/…), null for providers without OAuth. */
    val oauthUrl: String? = null,
    val buckets: Boolean = false,
    val bucketTitle: String = "Bucket",
    val taskSchema: List<String> = listOf("folder"),
) {
    fun has(attr: String) = attr in taskSchema
    /** Attribute keys as sent: `b2_chunk_size` and `dropbox_chunk_size` are both `chunk_size` on the wire. */
    val wireKeys: Set<String> get() = taskSchema.map { if (it.endsWith("_chunk_size")) "chunk_size" else it }.toSet()
}

data class CloudCredential(val id: Int, val name: String, val type: String, val provider: JsonObject = JsonObject(emptyMap()))

/** A bandwidth limit from [time] ("HH:MM") on; [bandwidth] bytes/s, null = unlimited. */
data class BwLimit(val time: String, val bandwidth: Long?)

enum class CloudDirection(val label: String) { PUSH("Push"), PULL("Pull") }

enum class TransferMode(val label: String, val push: String, val pull: String) {
    SYNC("Sync", "Makes the cloud match the NAS folder. Files deleted on the NAS are deleted in the cloud.",
        "Makes the NAS folder match the cloud. Files not in the cloud are deleted from the NAS folder."),
    COPY("Copy", "Copies new and changed files to the cloud. Nothing is deleted.",
        "Copies new and changed files to the NAS. Nothing is deleted."),
    MOVE("Move", "Copies files to the cloud, then deletes them from the NAS.",
        "Copies files to the NAS, then deletes them from the cloud."),
    ;
    fun describe(d: CloudDirection) = if (d == CloudDirection.PUSH) push else pull
}

data class CloudSyncTask(
    val id: Int,
    val description: String,
    val path: String,
    val credentialId: Int?,
    val credentialName: String,
    val providerType: String,
    val attributes: JsonObject,
    val schedule: CronSchedule,
    val preScript: String = "",
    val postScript: String = "",
    val snapshot: Boolean = false,
    val include: List<String> = emptyList(),
    val exclude: List<String> = emptyList(),
    val enabled: Boolean = true,
    val job: LastJob? = null,
    val jobId: Long? = null,
    val locked: Boolean = false,
    val bwlimit: List<BwLimit> = emptyList(),
    val transfers: Int? = null,
    val direction: CloudDirection = CloudDirection.PUSH,
    val mode: TransferMode = TransferMode.SYNC,
    val encryption: Boolean = false,
    val filenameEncryption: Boolean = false,
    val encryptionPassword: String = "",
    val encryptionSalt: String = "",
    val createEmptySrcDirs: Boolean = false,
    val followSymlinks: Boolean = false,
) {
    val name: String get() = description.ifBlank { "Cloud sync $id" }
    val bucket: String? get() = (attributes["bucket"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
    val folder: String get() = (attributes["folder"] as? JsonPrimitive)?.contentOrNull.orEmpty()
    val running: Boolean get() = job?.state?.active == true
    /** "bucket/folder" or "/folder" (provider root when empty). */
    val remote: String get() = CloudSyncLogic.remoteText(bucket, folder)
}

/** A row of the bandwidth schedule while editing: [limit] in KiB/s, blank = unlimited. */
data class BwRow(val time: String = "", val limit: String = "")

/** Editable cloud sync task; numbers stay text while typing. */
data class CloudSyncForm(
    val description: String = "",
    val direction: CloudDirection = CloudDirection.PUSH,
    val mode: TransferMode = TransferMode.COPY,
    val path: String = "",
    val credentialId: Int? = null,
    val bucket: String = "",
    val folder: String = "",
    val schedule: CronSchedule = CronText.DEFAULT.copy(minute = "0", hour = "0"),
    val enabled: Boolean = true,
    val snapshot: Boolean = false,
    val transfers: String = "",
    val bwlimit: List<BwRow> = emptyList(),
    val include: String = "",
    val exclude: String = "",
    val preScript: String = "",
    val postScript: String = "",
    val encryption: Boolean = false,
    val filenameEncryption: Boolean = true,
    val encryptionPassword: String = "",
    val encryptionSalt: String = "",
    /** TrueNAS sent "********" for the saved password/salt: empty fields keep them. */
    val secretsHidden: Boolean = false,
    val followSymlinks: Boolean = false,
    val createEmptySrcDirs: Boolean = false,
    val fastList: Boolean = false,
    val region: String = "",
    val s3Encryption: Boolean = false,
    val storageClass: String = "",
    val chunkSize: String = "",
    val acknowledgeAbuse: Boolean = false,
    val bucketPolicyOnly: Boolean = false,
    /** Attributes as loaded, so keys this form doesn't show are kept. */
    val originalAttributes: JsonObject = JsonObject(emptyMap()),
)

object CloudSyncLogic {
    const val REDACTED = CloudProviders.REDACTED
    val STORAGE_CLASSES = listOf("", "STANDARD", "REDUCED_REDUNDANCY", "STANDARD_IA", "ONEZONE_IA", "INTELLIGENT_TIERING", "GLACIER", "GLACIER_IR", "DEEP_ARCHIVE")
    private val TIME = Regex("^([01]?\\d|2[0-3]):([0-5]\\d)$")

    fun remoteText(bucket: String?, folder: String): String {
        val f = folder.trim().trim('/')
        return when {
            bucket != null && f.isNotEmpty() -> "$bucket/$f"
            bucket != null -> bucket
            else -> "/$f"
        }
    }

    // --- parsing ---

    private fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.contentOrNull
    private fun JsonObject.b(k: String) = (this[k] as? JsonPrimitive)?.booleanOrNull
    private fun JsonObject.l(k: String) = (this[k] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.let { it.longOrNull ?: it.contentOrNull?.toDoubleOrNull()?.toLong() }
    private fun JsonObject.strings(k: String) = (this[k] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()

    fun provider(o: JsonObject): CloudProvider? = CloudProvider(
        name = o.s("name") ?: return null,
        title = o.s("title") ?: o.s("name")!!,
        oauthUrl = o.s("credentials_oauth"),
        buckets = o.b("buckets") ?: false,
        bucketTitle = o.s("bucket_title")?.takeIf { it.isNotBlank() } ?: "Bucket",
        taskSchema = (o["task_schema"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.s("property") } ?: listOf("folder"),
    )

    fun credential(o: JsonObject): CloudCredential? {
        val provider = o["provider"] as? JsonObject
        val type = provider?.s("type") ?: o.s("provider") ?: return null
        return CloudCredential(o.l("id")?.toInt() ?: return null, o.s("name").orEmpty(), type, provider ?: JsonObject(emptyMap()))
    }

    fun task(o: JsonObject): CloudSyncTask? {
        val cred = o["credentials"] as? JsonObject
        val provider = cred?.get("provider") as? JsonObject
        val job = o["job"] as? JsonObject
        return CloudSyncTask(
            id = o.l("id")?.toInt() ?: return null,
            description = o.s("description").orEmpty(),
            path = o.s("path").orEmpty(),
            credentialId = cred?.l("id")?.toInt() ?: o.l("credentials")?.toInt(),
            credentialName = cred?.s("name").orEmpty(),
            providerType = provider?.s("type") ?: cred?.s("provider") ?: "",
            attributes = o["attributes"] as? JsonObject ?: JsonObject(emptyMap()),
            schedule = ProtectionParsers.cron(o["schedule"] as? JsonObject),
            preScript = o.s("pre_script").orEmpty(),
            postScript = o.s("post_script").orEmpty(),
            snapshot = o.b("snapshot") ?: false,
            include = o.strings("include"),
            exclude = o.strings("exclude"),
            enabled = o.b("enabled") ?: true,
            job = ProtectionParsers.lastJob(job),
            jobId = job?.l("id"),
            locked = o.b("locked") ?: false,
            bwlimit = (o["bwlimit"] as? JsonArray)?.mapNotNull { e -> (e as? JsonObject)?.let { b -> b.s("time")?.let { BwLimit(it, b.l("bandwidth")) } } } ?: emptyList(),
            transfers = o.l("transfers")?.toInt(),
            direction = runCatching { CloudDirection.valueOf(o.s("direction")!!) }.getOrDefault(CloudDirection.PUSH),
            mode = runCatching { TransferMode.valueOf(o.s("transfer_mode")!!) }.getOrDefault(TransferMode.SYNC),
            encryption = o.b("encryption") ?: false,
            filenameEncryption = o.b("filename_encryption") ?: false,
            encryptionPassword = o.s("encryption_password").orEmpty(),
            encryptionSalt = o.s("encryption_salt").orEmpty(),
            createEmptySrcDirs = o.b("create_empty_src_dirs") ?: false,
            followSymlinks = o.b("follow_symlinks") ?: false,
        )
    }

    // --- form ---

    fun form(t: CloudSyncTask): CloudSyncForm {
        val a = t.attributes
        val hidden = t.encryptionPassword == REDACTED || t.encryptionSalt == REDACTED
        return CloudSyncForm(
            description = t.description, direction = t.direction, mode = t.mode, path = t.path, credentialId = t.credentialId,
            bucket = t.bucket.orEmpty(), folder = t.folder, schedule = t.schedule, enabled = t.enabled, snapshot = t.snapshot,
            transfers = t.transfers?.toString().orEmpty(),
            bwlimit = t.bwlimit.map { BwRow(it.time, it.bandwidth?.let { b -> (b / 1024).toString() }.orEmpty()) },
            include = t.include.joinToString("\n"), exclude = t.exclude.joinToString("\n"),
            preScript = t.preScript, postScript = t.postScript,
            encryption = t.encryption, filenameEncryption = t.filenameEncryption,
            encryptionPassword = t.encryptionPassword.takeUnless { it == REDACTED }.orEmpty(),
            encryptionSalt = t.encryptionSalt.takeUnless { it == REDACTED }.orEmpty(),
            secretsHidden = hidden,
            followSymlinks = t.followSymlinks, createEmptySrcDirs = t.createEmptySrcDirs,
            fastList = a.b("fast_list") ?: false,
            region = a.s("region").orEmpty(),
            s3Encryption = a.s("encryption") == "AES256",
            storageClass = a.s("storage_class").orEmpty(),
            chunkSize = a.l("chunk_size")?.toString().orEmpty(),
            acknowledgeAbuse = a.b("acknowledge_abuse") ?: false,
            bucketPolicyOnly = a.b("bucket_policy_only") ?: false,
            originalAttributes = a,
        )
    }

    /** Default chunk size (MiB) of a provider, from the 25.10 attribute model. */
    fun defaultChunk(p: CloudProvider?): Int? = when {
        p == null -> null
        p.has("b2_chunk_size") -> 96
        p.has("dropbox_chunk_size") -> 48
        else -> null
    }

    /** `attributes` with exactly the provider's keys (25.10 rejects others). Unknown provider: keep what was loaded. */
    fun attributes(f: CloudSyncForm, p: CloudProvider?): JsonObject = buildJsonObject {
        val keys = p?.wireKeys
        f.originalAttributes.forEach { (k, v) -> if (keys == null || k in keys) put(k, v) }
        if (p == null || p.buckets) { if (f.bucket.isNotBlank()) put("bucket", f.bucket.trim()) }
        put("folder", f.folder.trim())
        if (p == null) return@buildJsonObject
        if (p.has("fast_list")) put("fast_list", f.fastList)
        if (p.has("region")) put("region", f.region.trim())
        if (p.has("encryption")) put("encryption", if (f.s3Encryption) JsonPrimitive("AES256") else JsonNull)
        if (p.has("storage_class")) put("storage_class", f.storageClass)
        if (p.has("acknowledge_abuse")) put("acknowledge_abuse", f.acknowledgeAbuse)
        if (p.has("bucket_policy_only")) put("bucket_policy_only", f.bucketPolicyOnly)
        if ("chunk_size" in p.wireKeys) put("chunk_size", f.chunkSize.trim().toIntOrNull() ?: defaultChunk(p) ?: 96)
    }

    private fun lines(s: String) = s.lines().map { it.trim() }.filter { it.isNotEmpty() }

    /** Body of `cloudsync.create` / `cloudsync.update` (`args` is left alone: it is slated for removal). */
    fun taskJson(f: CloudSyncForm, p: CloudProvider?, update: Boolean): JsonObject = buildJsonObject {
        put("description", f.description.trim())
        put("direction", f.direction.name)
        put("transfer_mode", f.mode.name)
        put("path", f.path.trim().trimEnd('/').ifEmpty { f.path.trim() })
        f.credentialId?.let { put("credentials", it) }
        put("attributes", attributes(f, p))
        put("schedule", ProtectionParsers.cronJson(f.schedule.copy(
            minute = f.schedule.minute.trim(), hour = f.schedule.hour.trim(), dom = f.schedule.dom.trim(),
            month = f.schedule.month.trim(), dow = f.schedule.dow.trim(),
        )))
        put("enabled", f.enabled)
        put("snapshot", f.snapshot)
        put("transfers", f.transfers.trim().toIntOrNull()?.let { JsonPrimitive(it) } ?: JsonNull)
        put("bwlimit", buildJsonArray {
            f.bwlimit.forEach { r ->
                add(buildJsonObject {
                    put("time", normalizeTime(r.time) ?: r.time.trim())
                    put("bandwidth", r.limit.trim().toLongOrNull()?.let { JsonPrimitive(it * 1024) } ?: JsonNull)
                })
            }
        })
        put("include", JsonArray(lines(f.include).map { JsonPrimitive(it) }))
        put("exclude", JsonArray(lines(f.exclude).map { JsonPrimitive(it) }))
        put("pre_script", f.preScript)
        put("post_script", f.postScript)
        put("encryption", f.encryption)
        put("filename_encryption", f.filenameEncryption)
        // A hidden saved secret is only replaced when something new is typed (update merges unsent keys).
        if (!(update && f.secretsHidden && f.encryptionPassword.isEmpty())) put("encryption_password", f.encryptionPassword)
        if (!(update && f.secretsHidden && f.encryptionSalt.isEmpty())) put("encryption_salt", f.encryptionSalt)
        put("follow_symlinks", f.followSymlinks)
        put("create_empty_src_dirs", f.createEmptySrcDirs)
    }

    fun normalizeTime(s: String): String? = TIME.matchEntire(s.trim())?.let { m -> "%02d:%s".format(m.groupValues[1].toInt(), m.groupValues[2]) }

    /** Local checks, field → message. Fields: path, credentials, bucket, folder, schedule, transfers, bwlimit, encryption_password, snapshot, chunk_size. */
    fun errors(f: CloudSyncForm, p: CloudProvider?): Map<String, String> = buildMap {
        val path = f.path.trim()
        when {
            path.isEmpty() -> put("path", "Choose a folder on the NAS")
            !path.startsWith("/mnt/") || path.trimEnd('/') == "/mnt" -> put("path", "Pick a folder inside a pool (/mnt/…)")
        }
        if (f.credentialId == null) put("credentials", "Choose a cloud credential")
        if (p?.buckets == true && f.bucket.isBlank()) put("bucket", "Choose a ${p.bucketTitle.lowercase()}")
        if (!CronText.isValid(f.schedule)) put("schedule", "Fix the schedule")
        f.transfers.trim().takeIf { it.isNotEmpty() }?.let { t -> if ((t.toIntOrNull() ?: 0) < 1) put("transfers", "A whole number of 1 or more, or empty") }
        bwError(f.bwlimit)?.let { put("bwlimit", it) }
        if (f.encryption && f.encryptionPassword.isEmpty() && !f.secretsHidden) put("encryption_password", "Required when encryption is on")
        if (f.snapshot && f.direction == CloudDirection.PULL) put("snapshot", "Only for push tasks")
        else if (f.snapshot && f.mode == TransferMode.MOVE) put("snapshot", "Can't be used with Move")
        f.chunkSize.trim().takeIf { it.isNotEmpty() && p != null }?.let { c ->
            val n = c.toIntOrNull()
            when {
                p!!.has("b2_chunk_size") && (n == null || n < 5) -> put("chunk_size", "5 MiB or more")
                p.has("dropbox_chunk_size") && (n == null || n < 5 || n >= 150) -> put("chunk_size", "5–149 MiB")
            }
        }
    }

    /** Where a restore may write: a folder inside a pool. */
    fun restorePathError(path: String): String? = path.trim().let { p ->
        when {
            p.isEmpty() -> "Choose a folder"
            !p.startsWith("/mnt/") || p.trimEnd('/') == "/mnt" || p.trimEnd('/').count { it == '/' } < 2 -> "Pick a folder inside a pool"
            else -> null
        }
    }

    fun bwError(rows: List<BwRow>): String? {
        var last: String? = null
        rows.forEachIndexed { i, r ->
            val t = normalizeTime(r.time) ?: return "Row ${i + 1}: enter a time like 08:00"
            if (r.limit.isNotBlank() && (r.limit.trim().toLongOrNull() ?: 0) < 1) return "Row ${i + 1}: enter KiB/s or leave empty for unlimited"
            if (last != null && t <= last!!) return "Times must go up: $last, then $t"
            last = t
        }
        return null
    }

    /** Middleware field names → editor fields. */
    val FIELD_ALIASES = mapOf(
        "minute" to "schedule", "hour" to "schedule", "dom" to "schedule", "month" to "schedule", "dow" to "schedule",
        "time" to "bwlimit", "bandwidth" to "bwlimit", "encryption_salt" to "encryption_password",
    )
    val FIELDS = setOf(
        "description", "path", "credentials", "bucket", "folder", "schedule", "transfers", "bwlimit", "encryption_password",
        "snapshot", "chunk_size", "direction", "transfer_mode", "pre_script", "post_script", "include", "exclude", "region", "storage_class",
    )

    /** The provider's chunk setting label, or null when it has none. */
    fun chunkLabel(p: CloudProvider?): String? = when {
        p == null -> null
        p.has("b2_chunk_size") -> "Upload chunk size (MiB)"
        p.has("dropbox_chunk_size") -> "Upload chunk size (MiB)"
        else -> null
    }

    /** Plain-language "push /mnt/tank/photos → B2 photos-bucket/nas" line. */
    fun routeText(t: CloudSyncTask, providerTitle: String): String {
        val arrow = if (t.direction == CloudDirection.PUSH) "→" else "←"
        return "${t.path} $arrow $providerTitle: ${t.remote}"
    }

}
