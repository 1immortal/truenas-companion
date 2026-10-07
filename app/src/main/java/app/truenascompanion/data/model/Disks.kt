package app.truenascompanion.data.model

/**
 * Disk details and guided replacement (1.3.0). Shapes follow TrueNAS 25.10: `pool.query` topology
 * (middlewared/plugins/pool_/topology.py), `disk.query` (api/v25_10_2/disk.py), `disk.details`
 * (plugins/disk_/availability.py) and `enclosure2.query` slots.
 */

enum class DiskKind(val label: String) { HDD("HDD"), SSD("SSD"), NVME("NVMe"), UNKNOWN("Disk") }

/** One node of a pool's vdev tree: a vdev (MIRROR, RAIDZ1 …) or a member disk (type DISK / FILE). */
data class VdevNode(
    val name: String,
    /** DISK, FILE, MIRROR, RAIDZ1-3, DRAID…, SPARE, REPLACING … (upper case, as TrueNAS reports it). */
    val type: String,
    val guid: String?,
    val status: String,
    val path: String?,
    /** Short disk name (`sda`) when TrueNAS could map the member to a disk. */
    val disk: String?,
    val readErrors: Long,
    val writeErrors: Long,
    val checksumErrors: Long,
    val size: Long?,
    val children: List<VdevNode> = emptyList(),
    /** For a member that isn't ONLINE: TrueNAS's record of the missing disk (`disk.disk_by_zfs_guid`). */
    val unavailDisk: UnavailDisk? = null,
) {
    val isLeaf: Boolean get() = type == "DISK" || type == "FILE"
    val errors: Long get() = readErrors + writeErrors + checksumErrors
}

data class UnavailDisk(val name: String?, val serial: String?, val model: String?, val size: Long?)

/** data / log / cache / spare / special / dedup group of a pool. */
data class VdevGroup(val category: String, val vdevs: List<VdevNode>) {
    val label: String get() = categoryLabel(category)

    companion object {
        fun categoryLabel(c: String) = when (c) {
            "data" -> "Data"
            "log" -> "Log (SLOG)"
            "cache" -> "Cache (L2ARC)"
            "spare" -> "Spare"
            "special" -> "Metadata (special)"
            "dedup" -> "Dedup"
            else -> c.replaceFirstChar { it.uppercase() }
        }
    }
}

data class ScanInfo(
    /** SCRUB | RESILVER */
    val function: String?,
    /** SCANNING | FINISHED | CANCELED */
    val state: String?,
    val percent: Double?,
    val bytesToProcess: Long?,
    val bytesProcessed: Long?,
    val bytesIssued: Long?,
    val errors: Long?,
    val startMillis: Long?,
    val endMillis: Long?,
    val secondsLeft: Long?,
    val paused: Boolean,
)

data class PoolLayout(
    val id: Long,
    val name: String,
    val guid: String?,
    val status: String,
    val healthy: Boolean,
    val statusDetail: String?,
    val size: Long?,
    val allocated: Long?,
    val free: Long?,
    val groups: List<VdevGroup>,
    val scan: ScanInfo?,
)

/** Where a disk sits in a pool: the leaf, its parent vdev and the vdev group category. */
data class PoolMember(
    val poolId: Long,
    val poolName: String,
    val category: String,
    val node: VdevNode,
    /** Parent vdev (MIRROR, RAIDZ1 …); null for a single-disk (stripe) vdev at the top level. */
    val parent: VdevNode?,
)

data class DiskInfo(
    val name: String,
    val identifier: String?,
    val serial: String?,
    val model: String?,
    val size: Long?,
    val type: String?,
    val bus: String?,
    val subsystem: String?,
    val rotationRate: Int?,
    val zfsGuid: String?,
    val pool: String?,
    val description: String?,
    val temperatureC: Double? = null,
    val tempMin: Double? = null,
    val tempMax: Double? = null,
    val tempAvg: Double? = null,
) {
    val kind: DiskKind get() = when {
        bus.equals("NVME", true) || subsystem.equals("nvme", true) || name.startsWith("nvme") -> DiskKind.NVME
        type.equals("SSD", true) -> DiskKind.SSD
        type.equals("HDD", true) -> DiskKind.HDD
        else -> DiskKind.UNKNOWN
    }
}

/** A disk that could take a failed member's place (`disk.details`: unused, or holding an exported pool). */
data class ReplacementCandidate(
    val name: String,
    val identifier: String,
    val serial: String?,
    val model: String?,
    val size: Long?,
    val type: String?,
    val bus: String?,
    /** Name of an exported pool on this disk: replacing destroys it and needs Force. */
    val exportedZpool: String?,
    /** Other disks reporting the same serial (USB enclosures): the serial can't identify the disk. */
    val duplicateSerial: List<String>,
)

/** A drive bay with an identify LED (`enclosure2.query`). */
data class EnclosureSlot(val enclosureId: String, val slot: Int, val dev: String, val supportsIdentify: Boolean, val lightOn: Boolean?)

/** An active alert that concerns one disk (SMART, temperature …). */
data class DiskAlert(val klass: String, val level: String, val text: String, val dateMillis: Long?)

/** Pool resilver the app keeps an eye on so it can notify when it finishes. */
@kotlinx.serialization.Serializable
data class ResilverWatch(val serverId: String, val poolId: Long, val poolName: String, val startedAt: Long)
