package app.truenascompanion.data.cloud

import kotlinx.serialization.Serializable

/** A cloud sync run started from the app that should post a notification when it ends (1.5.0). */
@Serializable
data class CloudRunWatch(
    val serverId: String,
    val taskId: Int,
    val taskName: String,
    val jobId: Long,
    val dryRun: Boolean,
    val startedAt: Long,
)
