package app.truenascompanion.data.cloud

import kotlinx.serialization.Serializable

/**
 * A run started from the app that should post a notification when it ends: cloud sync (1.5.0) or, since 1.6.0,
 * replication ([kind] = [KIND_REPLICATION]; [taskId] is -1 for a one-time replication).
 */
@Serializable
data class CloudRunWatch(
    val serverId: String,
    val taskId: Int,
    val taskName: String,
    val jobId: Long,
    val dryRun: Boolean,
    val startedAt: Long,
    val kind: String = KIND_CLOUD_SYNC,
) {
    companion object {
        const val KIND_CLOUD_SYNC = "cloud_sync"
        const val KIND_REPLICATION = "replication"
    }
}
