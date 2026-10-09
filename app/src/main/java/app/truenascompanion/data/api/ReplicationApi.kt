package app.truenascompanion.data.api

import app.truenascompanion.data.replication.KeychainUse
import app.truenascompanion.data.replication.ReplTransport
import app.truenascompanion.data.replication.ReplicationLogic
import app.truenascompanion.data.replication.ReplicationTask
import app.truenascompanion.data.replication.SnapshotTaskRef
import app.truenascompanion.data.replication.SshConnection
import app.truenascompanion.data.replication.SshKeyPair
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** Input for `keychaincredential.setup_ssh_connection` (manual or semi-automatic). */
data class SshSetup(
    val connectionName: String,
    /** Null: generate a new key pair named [newKeyName]. */
    val existingKeyId: Int?,
    val newKeyName: String,
    val semiAutomatic: Boolean,
    // semi-automatic: the other TrueNAS signs in and trusts the key
    val url: String = "",
    val verifySsl: Boolean = true,
    val adminUsername: String = "root",
    val password: String = "",
    val otp: String = "",
    val token: String = "",
    val sudo: Boolean = false,
    // manual
    val host: String = "",
    val port: Int = 22,
    val remoteHostKey: String = "",
    // both
    val username: String = "root",
    val connectTimeout: Int = 10,
)

/**
 * Replication (1.6.0), TrueNAS 25.10 (`plugins/replication.py`, `plugins/replication_/crud.py`, `plugins/keychain.py`,
 * `plugins/keychain_/ssh_connections.py`): `replication.query/create/update/delete/run/run_onetime/restore/list_datasets/
 * list_naming_schemas`, `pool.snapshottask.query`, `keychaincredential.query/create/update/delete/used_by/
 * generate_ssh_key_pair/remote_ssh_host_key_scan/setup_ssh_connection`. JSON-RPC over the WebSocket only.
 */
class ReplicationApi(private val api: TrueNasApi) {

    suspend fun tasks(): List<ReplicationTask> =
        api.rpc("replication.query").arr()?.mapNotNull { it.obj()?.let(ReplicationLogic::task) }?.sortedBy { it.name.lowercase() } ?: emptyList()

    suspend fun task(id: Int): ReplicationTask? =
        api.rpc("replication.query", queryFilter(Triple("id", "=", JsonPrimitive(id)))).arr()?.firstOrNull().obj()?.let(ReplicationLogic::task)

    suspend fun create(body: JsonObject): ReplicationTask? = api.rpc("replication.create", body).obj()?.let(ReplicationLogic::task)

    /** Update merges into the stored task: keys that aren't sent keep their value. */
    suspend fun update(id: Int, body: JsonObject): ReplicationTask? = api.rpc("replication.update", JsonPrimitive(id), body).obj()?.let(ReplicationLogic::task)

    suspend fun setEnabled(id: Int, enabled: Boolean) { api.rpc("replication.update", JsonPrimitive(id), buildJsonObject { put("enabled", enabled) }) }

    /** Snapshots already sent stay on the target. */
    suspend fun delete(id: Int) { api.rpc("replication.delete", JsonPrimitive(id)) }

    /** Starts a run (a job; TrueNAS refuses a turned-off, running or held task) and returns the job id. */
    suspend fun run(id: Int): Long =
        api.rpc("replication.run", JsonPrimitive(id)).asJobId() ?: throw TrueNasException.JobFailed("TrueNAS didn't start the replication")

    /** Replicates once with these settings without saving a task (a job). */
    suspend fun runOnetime(body: JsonObject): Long =
        api.rpc("replication.run_onetime", body).asJobId() ?: throw TrueNasException.JobFailed("TrueNAS didn't start the replication")

    /** Creates the opposite-direction task (turned off, retention "keep all") that brings the snapshots back into [targetDataset]. */
    suspend fun restore(id: Int, name: String, targetDataset: String): ReplicationTask? =
        api.rpc("replication.restore", JsonPrimitive(id), buildJsonObject {
            put("name", name.trim()); put("target_dataset", targetDataset.trim().trim('/'))
        }).obj()?.let(ReplicationLogic::task)

    /** Datasets on the other side (or this NAS for [ReplTransport.LOCAL]), as zettarepl sees them. */
    suspend fun listDatasets(transport: ReplTransport, sshCredentials: Int?): List<String> =
        api.rpc("replication.list_datasets", JsonPrimitive(transport.wire), sshCredentials?.let { JsonPrimitive(it) } ?: JsonNull)
            .arr()?.mapNotNull { it.prim()?.contentOrNull }?.sorted() ?: emptyList()

    suspend fun namingSchemas(): List<String> =
        runCatching { api.rpc("replication.list_naming_schemas").arr()?.mapNotNull { it.prim()?.contentOrNull } }.getOrNull() ?: emptyList()

    suspend fun snapshotTasks(): List<SnapshotTaskRef> =
        api.rpc("pool.snapshottask.query").arr()?.mapNotNull { it.obj()?.let(ReplicationLogic::snapshotTaskRef) }?.sortedBy { it.dataset } ?: emptyList()

    // --- keychain ---

    private suspend fun keychain(type: String) =
        api.rpc("keychaincredential.query", queryFilter(Triple("type", "=", JsonPrimitive(type)))).arr().orEmpty().mapNotNull { it.obj() }

    suspend fun connections(): List<SshConnection> = keychain("SSH_CREDENTIALS").mapNotNull(ReplicationLogic::connection).sortedBy { it.name.lowercase() }

    suspend fun keyPairs(): List<SshKeyPair> = keychain("SSH_KEY_PAIR").mapNotNull(ReplicationLogic::keyPair).sortedBy { it.name.lowercase() }

    suspend fun usedBy(id: Int): List<KeychainUse> =
        api.rpc("keychaincredential.used_by", JsonPrimitive(id)).arr()?.mapNotNull { it.obj()?.let(ReplicationLogic::use) } ?: emptyList()

    /** Without `cascade`, TrueNAS refuses to delete a credential that is in use. */
    suspend fun deleteCredential(id: Int) { api.rpc("keychaincredential.delete", JsonPrimitive(id)) }

    suspend fun generateKeyPair(): Pair<String, String> {
        val o = api.rpc("keychaincredential.generate_ssh_key_pair").obj()
        return (o?.str("private_key").orEmpty()) to (o?.str("public_key").orEmpty())
    }

    suspend fun createKeyPair(name: String, privateKey: String, publicKey: String): SshKeyPair? =
        api.rpc("keychaincredential.create", keyPairJson(name, privateKey, publicKey)).obj()?.let(ReplicationLogic::keyPair)

    /** Renames; the keys stay as they are (update merges `name` into the stored credential). */
    suspend fun renameCredential(id: Int, name: String) { api.rpc("keychaincredential.update", JsonPrimitive(id), buildJsonObject { put("name", name.trim()) }) }

    /** `attributes` is replaced as a whole, so every field is sent. */
    suspend fun updateConnection(c: SshConnection) {
        api.rpc("keychaincredential.update", JsonPrimitive(c.id), buildJsonObject {
            put("name", c.name.trim()); put("attributes", connectionAttributes(c))
        })
    }

    suspend fun scanHostKey(host: String, port: Int, timeout: Int): String =
        api.rpc("keychaincredential.remote_ssh_host_key_scan", buildJsonObject {
            put("host", host.trim()); put("port", port); put("connect_timeout", timeout)
        }).prim()?.contentOrNull.orEmpty()

    suspend fun setupConnection(s: SshSetup): SshConnection? =
        api.rpc("keychaincredential.setup_ssh_connection", setupJson(s)).obj()?.let(ReplicationLogic::connection)

    companion object {
        fun keyPairJson(name: String, privateKey: String, publicKey: String) = buildJsonObject {
            put("name", name.trim())
            put("type", "SSH_KEY_PAIR")
            put("attributes", buildJsonObject {
                put("private_key", privateKey.trim().ifEmpty { null }?.let { JsonPrimitive(it) } ?: JsonNull)
                put("public_key", publicKey.trim().ifEmpty { null }?.let { JsonPrimitive(it) } ?: JsonNull)
            })
        }

        fun connectionAttributes(c: SshConnection) = buildJsonObject {
            put("host", c.host.trim()); put("port", c.port); put("username", c.username.trim())
            c.privateKeyId?.let { put("private_key", it) }
            put("remote_host_key", c.remoteHostKey.trim()); put("connect_timeout", c.connectTimeout)
        }

        fun setupJson(s: SshSetup) = buildJsonObject {
            put("connection_name", s.connectionName.trim())
            put("setup_type", if (s.semiAutomatic) "SEMI-AUTOMATIC" else "MANUAL")
            put("private_key", buildJsonObject {
                if (s.existingKeyId == null) { put("generate_key", true); put("name", s.newKeyName.trim()) }
                else { put("generate_key", false); put("existing_key_id", s.existingKeyId) }
            })
            if (s.semiAutomatic) put("semi_automatic_setup", buildJsonObject {
                put("url", s.url.trim().trimEnd('/'))
                put("verify_ssl", s.verifySsl)
                if (s.token.isNotBlank()) put("token", s.token.trim())
                else {
                    put("admin_username", s.adminUsername.trim())
                    put("password", s.password)
                    if (s.otp.isNotBlank()) put("otp_token", s.otp.trim())
                }
                put("username", s.username.trim())
                put("connect_timeout", s.connectTimeout)
                put("sudo", s.sudo)
            }) else put("manual_setup", buildJsonObject {
                put("host", s.host.trim()); put("port", s.port); put("username", s.username.trim())
                put("remote_host_key", s.remoteHostKey.trim()); put("connect_timeout", s.connectTimeout)
            })
        }
    }
}
