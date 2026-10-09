package app.truenascompanion

import app.truenascompanion.data.model.CronSchedule
import app.truenascompanion.data.model.JobState
import app.truenascompanion.data.model.LastJob
import app.truenascompanion.data.replication.ReplDirection
import app.truenascompanion.data.replication.ReplProgress
import app.truenascompanion.data.replication.ReplState
import app.truenascompanion.data.replication.ReplTiming
import app.truenascompanion.data.replication.ReplTransport
import app.truenascompanion.data.replication.ReplicationForm
import app.truenascompanion.data.replication.ReplicationTask
import app.truenascompanion.data.replication.Retention
import app.truenascompanion.data.replication.SnapshotTaskRef
import app.truenascompanion.data.replication.SshConnection
import app.truenascompanion.data.replication.SshKeyPair
import app.truenascompanion.ui.replication.ReplEditorRefs
import app.truenascompanion.ui.replication.ReplicationData

/** Example replication data for 1.6.0 tests and previews (example hosts only). */
object ReplicationSamples {
    val NOW = ProtectionSamples.NOW
    private const val H = 3_600_000L
    private val hourly = CronSchedule("0", "*", "*", "*", "*")

    val snapTasks = listOf(
        SnapshotTaskRef(3, "tank/photos", "auto-%Y-%m-%d_%H-%M", true, true, hourly),
        SnapshotTaskRef(4, "tank/family", "auto-%Y-%m-%d_%H-%M", true, true, CronSchedule("0", "0", "*", "*", "*")),
    )
    val keys = listOf(
        SshKeyPair(4, "replication-key", "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIExampleOnlyKeyMaterial0123456789 root@truenas",
            "-----BEGIN OPENSSH PRIVATE KEY-----\nexample\n-----END OPENSSH PRIVATE KEY-----"),
        SshKeyPair(6, "offsite-key", "ssh-rsa AAAAB3NzaC1yc2EAAAADAQABExampleOnly987654321 nas@example", "x"),
    )
    val connections = listOf(
        SshConnection(5, "Backup NAS", "nas2.example.com", 22, "root", 4, "ssh-ed25519 AAAAexample", 10),
        SshConnection(7, "Offsite", "203.0.113.7", 2222, "zfs", 6, "ssh-ed25519 AAAAexample2", 10),
    )
    val tasks = listOf(
        ReplicationTask(12, "Photos to backup NAS", ReplDirection.PUSH, ReplTransport.SSH, 5, "Backup NAS", listOf("tank/photos"), "backup/photos",
            recursive = true, periodicSnapshotTasks = listOf(snapTasks[0]), auto = true, retention = Retention.SOURCE,
            state = ReplState("RUNNING", NOW - H / 6, progress = ReplProgress("tank/photos/2026", "auto-2026-10-09_10-00", 1, 4, 1_610_612_736, 4_294_967_296))),
        ReplicationTask(13, "Family to offsite", ReplDirection.PUSH, ReplTransport.NETCAT, 7, "Offsite", listOf("tank/family"), "offsite/family",
            recursive = true, auto = true, schedule = CronSchedule("30", "1", "*", "*", "*"), retention = Retention.CUSTOM, lifetimeValue = 3, lifetimeUnit = "MONTH",
            state = ReplState("ERROR", NOW - 5 * H, error = "No incremental base on dataset 'tank/family' and replication from scratch is not allowed")),
        ReplicationTask(14, "Pull media", ReplDirection.PULL, ReplTransport.SSH, 5, "Backup NAS", listOf("media"), "tank/media-copy",
            auto = false, retention = Retention.NONE, state = ReplState("FINISHED", NOW - 26 * H, lastSnapshot = "media@auto-2026-10-08_00-00")),
        ReplicationTask(15, "Apps to second pool", ReplDirection.PUSH, ReplTransport.LOCAL, null, null, listOf("tank/apps"), "fast/apps-copy",
            recursive = false, periodicSnapshotTasks = listOf(snapTasks[1]), enabled = false,
            job = LastJob(JobState.SUCCESS, NOW - 72 * H, NOW - 71 * H, null, null, 100.0, null)),
    )
    val data = ReplicationData(tasks, connections, keys)
    val refs = ReplEditorRefs(connections, snapTasks, listOf("auto-%Y-%m-%d_%H-%M"))

    val form = ReplicationForm(
        name = "Photos to backup NAS", sshCredentialsId = 5, sourceDatasets = listOf("tank/photos"), targetDataset = "backup/photos",
        recursive = true, exclude = "tank/photos/cache", snapshotTaskIds = setOf(3), timing = ReplTiming.AFTER_SNAPSHOTS,
        compression = "LZ4", speedLimit = "20480",
    )
}
