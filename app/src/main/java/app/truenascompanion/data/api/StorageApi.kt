package app.truenascompanion.data.api

import app.truenascompanion.data.model.Dataset
import app.truenascompanion.data.model.DatasetCreateRequest
import app.truenascompanion.data.model.NfsShare
import app.truenascompanion.data.model.NfsShareInput
import app.truenascompanion.data.model.SmbShare
import app.truenascompanion.data.model.SmbShareInput
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Datasets, ZVOLs and shares (0.8.0). Method names and payloads match TrueNAS SCALE 25.10 middleware:
 * `pool.dataset.*`, `sharing.smb.*`, `sharing.nfs.*` (api/v25_10_5).
 */
class StorageApi(private val api: TrueNasApi) {

    suspend fun datasets(): List<Dataset> = api.datasets()

    /** `pool.dataset.create` — filesystem or VOLUME (ZVOL). */
    suspend fun createDataset(req: DatasetCreateRequest): Dataset? {
        val data = buildJsonObject {
            put("name", req.name)
            put("type", req.type)
            if (req.type.equals("FILESYSTEM", true)) put("share_type", req.shareType)
            req.compression?.takeIf { it != "INHERIT" }?.let { put("compression", it) }
            req.comments?.takeIf { it.isNotBlank() }?.let { put("comments", it) }
            if (req.type.equals("VOLUME", true)) {
                put("volsize", req.volsize ?: error("ZVOL size is required"))
                put("sparse", req.sparse)
            }
        }
        return api.rpc("pool.dataset.create", data).obj()?.let(Parsers::dataset)
    }

    /** `pool.dataset.rename` — id is the current full name; [newName] is the full new path. */
    suspend fun renameDataset(id: String, newName: String, recursive: Boolean = false) {
        api.rpc(
            "pool.dataset.rename",
            JsonPrimitive(id),
            buildJsonObject {
                put("new_name", newName)
                put("recursive", recursive)
                put("force", false)
            },
        )
    }

    /** `pool.dataset.delete` — recursive removes children; force ignores busy mount. */
    suspend fun deleteDataset(id: String, recursive: Boolean = false, force: Boolean = false) {
        api.rpc(
            "pool.dataset.delete",
            JsonPrimitive(id),
            buildJsonObject {
                put("recursive", recursive)
                put("force", force)
            },
        )
    }

    suspend fun smbShares(): List<SmbShare> =
        api.rpc("sharing.smb.query").arr()?.mapNotNull { it.obj()?.let(Parsers::smbShare) }
            ?.sortedBy { it.name.lowercase() } ?: emptyList()

    suspend fun createSmbShare(input: SmbShareInput): SmbShare? =
        api.rpc("sharing.smb.create", smbJson(input)).obj()?.let(Parsers::smbShare)

    suspend fun updateSmbShare(id: Int, input: SmbShareInput): SmbShare? =
        api.rpc("sharing.smb.update", JsonPrimitive(id), smbJson(input, forUpdate = true)).obj()?.let(Parsers::smbShare)

    suspend fun deleteSmbShare(id: Int) {
        api.rpc("sharing.smb.delete", JsonPrimitive(id))
    }

    suspend fun nfsShares(): List<NfsShare> =
        api.rpc("sharing.nfs.query").arr()?.mapNotNull { it.obj()?.let(Parsers::nfsShare) }
            ?.sortedBy { it.path.lowercase() } ?: emptyList()

    suspend fun createNfsShare(input: NfsShareInput): NfsShare? =
        api.rpc("sharing.nfs.create", nfsJson(input)).obj()?.let(Parsers::nfsShare)

    suspend fun updateNfsShare(id: Int, input: NfsShareInput): NfsShare? =
        api.rpc("sharing.nfs.update", JsonPrimitive(id), nfsJson(input)).obj()?.let(Parsers::nfsShare)

    suspend fun deleteNfsShare(id: Int) {
        api.rpc("sharing.nfs.delete", JsonPrimitive(id))
    }

    companion object {
        val COMPRESSION_CHOICES = listOf(
            "INHERIT", "OFF", "LZ4", "ZSTD", "ZSTD-3", "ZSTD-5", "GZIP", "GZIP-6", "ZLE", "LZJB",
        )
        val SHARE_TYPES = listOf("GENERIC", "SMB", "NFS", "MULTIPROTOCOL", "APPS")
        val SMB_PURPOSES = listOf(
            "DEFAULT_SHARE", "MULTIPROTOCOL_SHARE", "TIMEMACHINE_SHARE", "LEGACY_SHARE",
        )

        fun pathForDataset(datasetId: String) = "/mnt/$datasetId"

        private fun smbJson(input: SmbShareInput, forUpdate: Boolean = false) = buildJsonObject {
            put("name", input.name)
            put("path", input.path)
            put("purpose", input.purpose)
            put("enabled", input.enabled)
            put("comment", input.comment)
            put("readonly", input.readonly)
            put("browsable", input.browsable)
            if (!forUpdate) {
                // Create requires purpose; options default from purpose on the server.
            }
        }

        private fun nfsJson(input: NfsShareInput) = buildJsonObject {
            put("path", input.path)
            put("comment", input.comment)
            put("enabled", input.enabled)
            put("ro", input.readonly)
            putJsonArray("networks") { input.networks.forEach { add(JsonPrimitive(it)) } }
            putJsonArray("hosts") { input.hosts.forEach { add(JsonPrimitive(it)) } }
        }
    }
}
