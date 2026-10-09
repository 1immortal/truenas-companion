package app.truenascompanion.data.api

import app.truenascompanion.data.cloud.CloudCredential
import app.truenascompanion.data.cloud.CloudProvider
import app.truenascompanion.data.cloud.CloudSyncLogic
import app.truenascompanion.data.cloud.CloudSyncTask
import app.truenascompanion.data.cloud.TransferMode
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Result of `cloudsync.credentials.verify`: rclone could list the remote, or why not. */
data class VerifyResult(val valid: Boolean, val error: String? = null, val excerpt: String? = null) {
    /** Short message for the UI (rclone's excerpt, else the first lines of its error output). */
    val message: String? get() = (excerpt?.takeIf { it.isNotBlank() } ?: error?.lineSequence()?.filter { it.isNotBlank() }?.take(3)?.joinToString("\n"))?.trim()
}

/** One item of `cloudsync.list_buckets` / `cloudsync.list_directory` (rclone lsjson). */
data class RemoteEntry(val name: String, val path: String, val isDir: Boolean, val size: Long?, val decrypted: String? = null)

data class OneDriveDrive(val id: String, val type: String, val name: String, val description: String)

/** An SSH key pair from System › Keychain (`keychaincredential`), for SFTP credentials. */
data class KeyPair(val id: Int, val name: String)

/**
 * Cloud sync (1.5.0), TrueNAS 25.10 (`plugins/cloud_sync.py`, `plugins/cloud_sync_/crud.py`):
 * `cloudsync.credentials.query/create/update/delete/verify`, `cloudsync.providers`, `cloudsync.query/create/update/delete`,
 * `cloudsync.sync(id, {dry_run})` (a job with logs, abortable), `cloudsync.abort`, `cloudsync.restore`,
 * `cloudsync.list_buckets`, `cloudsync.list_directory`, `cloudsync.onedrive_list_drives` and
 * `keychaincredential.query` for SFTP key pairs. Everything goes over the WebSocket JSON-RPC connection.
 */
class CloudSyncApi(private val api: TrueNasApi) {

    suspend fun providers(): List<CloudProvider> =
        api.rpc("cloudsync.providers").arr()?.mapNotNull { it.obj()?.let(CloudSyncLogic::provider) } ?: emptyList()

    suspend fun credentials(): List<CloudCredential> =
        api.rpc("cloudsync.credentials.query").arr()?.mapNotNull { it.obj()?.let(CloudSyncLogic::credential) }
            ?.sortedBy { it.name.lowercase() } ?: emptyList()

    suspend fun createCredential(name: String, provider: JsonObject): CloudCredential? =
        api.rpc("cloudsync.credentials.create", credentialJson(name, provider)).obj()?.let(CloudSyncLogic::credential)

    /** Update replaces the whole `provider` object (shallow merge on the NAS). */
    suspend fun updateCredential(id: Int, name: String, provider: JsonObject): CloudCredential? =
        api.rpc("cloudsync.credentials.update", JsonPrimitive(id), credentialJson(name, provider)).obj()?.let(CloudSyncLogic::credential)

    /** Fails with "This credential is used by cloud sync task …" while a task uses it. */
    suspend fun deleteCredential(id: Int) { api.rpc("cloudsync.credentials.delete", JsonPrimitive(id)) }

    suspend fun verify(provider: JsonObject): VerifyResult {
        val o = api.rpc("cloudsync.credentials.verify", provider).obj()
        return VerifyResult(o?.bool("valid") ?: false, o?.str("error"), o?.str("excerpt"))
    }

    suspend fun tasks(): List<CloudSyncTask> =
        api.rpc("cloudsync.query").arr()?.mapNotNull { it.obj()?.let(CloudSyncLogic::task) }?.sortedBy { it.id } ?: emptyList()

    suspend fun task(id: Int): CloudSyncTask? =
        api.rpc("cloudsync.query", queryFilter(Triple("id", "=", JsonPrimitive(id)))).arr()?.firstOrNull().obj()?.let(CloudSyncLogic::task)

    suspend fun createTask(body: JsonObject): CloudSyncTask? = api.rpc("cloudsync.create", body).obj()?.let(CloudSyncLogic::task)

    suspend fun updateTask(id: Int, body: JsonObject): CloudSyncTask? =
        api.rpc("cloudsync.update", JsonPrimitive(id), body).obj()?.let(CloudSyncLogic::task)

    suspend fun setEnabled(id: Int, enabled: Boolean) {
        api.rpc("cloudsync.update", JsonPrimitive(id), buildJsonObject { put("enabled", enabled) })
    }

    /** Deleting a task also aborts a run in progress (done by TrueNAS). */
    suspend fun deleteTask(id: Int) { api.rpc("cloudsync.delete", JsonPrimitive(id)) }

    /** Starts a run (or a dry run that changes nothing) and returns the job id. */
    suspend fun sync(id: Int, dryRun: Boolean): Long =
        api.rpc("cloudsync.sync", JsonPrimitive(id), buildJsonObject { put("dry_run", dryRun) }).asJobId()
            ?: throw TrueNasException.JobFailed("TrueNAS didn't start the cloud sync")

    /** False when nothing was running. */
    suspend fun abort(id: Int): Boolean = api.rpc("cloudsync.abort", JsonPrimitive(id)).prim()?.booleanOrNullSafe() ?: false

    /** Creates the opposite-direction task (disabled) that copies the cloud data back into [path]. */
    suspend fun restore(id: Int, description: String, mode: TransferMode, path: String): CloudSyncTask? =
        api.rpc("cloudsync.restore", JsonPrimitive(id), buildJsonObject {
            put("description", description.trim())
            put("transfer_mode", mode.name)
            put("path", path.trim())
        }).obj()?.let(CloudSyncLogic::task)

    suspend fun listBuckets(credentialId: Int): List<RemoteEntry> =
        api.rpc("cloudsync.list_buckets", JsonPrimitive(credentialId)).arr()?.mapNotNull { it.obj()?.let(::entry) }
            ?.sortedBy { it.name.lowercase() } ?: emptyList()

    /**
     * Lists [folder] (in [bucket] for bucket providers) without decryption: the task's folder is a path on the remote
     * itself, encrypted names included.
     */
    suspend fun listDirectory(credentialId: Int, bucket: String?, folder: String): List<RemoteEntry> =
        api.rpc("cloudsync.list_directory", listJson(credentialId, bucket, folder)).arr()?.mapNotNull { it.obj()?.let(::entry) }
            ?.sortedWith(compareBy({ !it.isDir }, { it.name.lowercase() })) ?: emptyList()

    suspend fun oneDriveDrives(clientId: String, clientSecret: String, token: String): List<OneDriveDrive> =
        api.rpc("cloudsync.onedrive_list_drives", buildJsonObject {
            put("client_id", clientId.trim()); put("client_secret", clientSecret.trim()); put("token", token.trim())
        }).arr()?.mapNotNull { e ->
            e.obj()?.let { o -> o.str("drive_id")?.let { OneDriveDrive(it, o.str("drive_type").orEmpty(), o.str("name").orEmpty(), o.str("description").orEmpty()) } }
        } ?: emptyList()

    suspend fun keyPairs(): List<KeyPair> {
        val options = buildJsonObject { put("select", JsonArray(listOf("id", "name").map { JsonPrimitive(it) })) }
        return api.rpc("keychaincredential.query", queryFilter(Triple("type", "=", JsonPrimitive("SSH_KEY_PAIR"))), options).arr()
            ?.mapNotNull { e -> e.obj()?.let { o -> o.long("id")?.let { KeyPair(it.toInt(), o.str("name").orEmpty()) } } } ?: emptyList()
    }

    companion object {
        fun credentialJson(name: String, provider: JsonObject) = buildJsonObject {
            put("name", name.trim())
            put("provider", provider)
        }

        fun listJson(credentialId: Int, bucket: String?, folder: String) = buildJsonObject {
            put("credentials", credentialId)
            put("encryption", false)
            put("filename_encryption", false)
            put("encryption_password", "")
            put("encryption_salt", "")
            put("attributes", buildJsonObject {
                if (!bucket.isNullOrBlank()) put("bucket", bucket)
                put("folder", folder)
            })
            put("args", "")
        }

        fun entry(o: JsonObject): RemoteEntry? = RemoteEntry(
            name = o.str("Name") ?: return null,
            path = o.str("Path") ?: o.str("Name")!!,
            isDir = o.bool("IsDir") ?: o.bool("IsBucket") ?: false,
            size = o.long("Size")?.takeIf { it >= 0 },
            decrypted = o.str("Decrypted"),
        )

        private fun JsonPrimitive.booleanOrNullSafe(): Boolean? = when (content) { "true" -> true; "false" -> false; else -> null }
    }
}
