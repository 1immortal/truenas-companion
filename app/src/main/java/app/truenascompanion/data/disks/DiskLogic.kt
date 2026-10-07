package app.truenascompanion.data.disks

import app.truenascompanion.data.model.PoolLayout
import app.truenascompanion.data.model.PoolMember
import app.truenascompanion.data.model.ReplacementCandidate
import app.truenascompanion.data.model.ScanInfo
import app.truenascompanion.data.model.VdevNode

/**
 * Pure rules for disk actions and the replace wizard (1.3.0). The Online / Offline / Detach rules are those of the
 * TrueNAS 25.10 web UI (zfs-info-card.component.ts), so the app never offers an action the web UI would hide.
 */
object DiskLogic {

    /** Every leaf (disk) of the pool with its parent vdev and category. */
    fun members(pool: PoolLayout): List<PoolMember> = pool.groups.flatMap { g ->
        g.vdevs.flatMap { top -> collect(pool, g.category, top, null) }
    }

    private fun collect(pool: PoolLayout, category: String, node: VdevNode, parent: VdevNode?): List<PoolMember> =
        if (node.isLeaf) listOf(PoolMember(pool.id, pool.name, category, node, parent))
        else node.children.flatMap { collect(pool, category, it, node) }

    /** The member for disk [name] (matched on the mapped disk or the missing disk's recorded name). */
    fun memberFor(pools: List<PoolLayout>, name: String): PoolMember? =
        pools.asSequence().flatMap { members(it).asSequence() }.firstOrNull { m ->
            m.node.disk == name || m.node.unavailDisk?.name == name || m.node.path?.substringAfterLast('/') == name
        }

    fun memberByGuid(pool: PoolLayout, guid: String): PoolMember? = members(pool).firstOrNull { it.node.guid == guid }

    private fun isSpareOrCache(category: String) = category == "spare" || category == "cache"

    fun canOffline(m: PoolMember): Boolean =
        m.node.status != "OFFLINE" && m.node.status != "UNAVAIL" && !isSpareOrCache(m.category) && m.node.guid != null

    fun canOnline(m: PoolMember): Boolean =
        m.node.status != "ONLINE" && m.node.status != "UNAVAIL" && !isSpareOrCache(m.category) && m.node.guid != null

    fun canDetach(m: PoolMember): Boolean = m.parent?.type in setOf("MIRROR", "REPLACING", "SPARE") && m.node.guid != null

    /** The web UI offers Replace on every member; the app does too (data, log, special, dedup members and spares). */
    fun canReplace(m: PoolMember): Boolean = m.node.guid != null

    /**
     * Taking a member offline must not take the vdev down: in a stripe (no parent) or when the parent has no other
     * healthy member, offlining is refused by ZFS anyway; the wizard warns earlier.
     */
    fun offlineIsSafe(m: PoolMember): Boolean {
        val p = m.parent ?: return false
        val others = p.children.filter { it.guid != m.node.guid }
        val healthyOthers = others.count { it.status == "ONLINE" }
        return when {
            p.type == "MIRROR" -> healthyOthers >= 1
            p.type.startsWith("RAIDZ") -> {
                val parity = p.type.removePrefix("RAIDZ").toIntOrNull() ?: 1
                p.children.count { it.status != "ONLINE" && it.guid != m.node.guid } < parity
            }
            else -> healthyOthers >= 1
        }
    }

    /** The member to replace by default: the first one that isn't ONLINE or has errors, else the first one. */
    fun preselect(pool: PoolLayout, diskName: String? = null): PoolMember? {
        val all = members(pool).filter { canReplace(it) }
        diskName?.let { n -> all.firstOrNull { it.node.disk == n || it.node.unavailDisk?.name == n }?.let { return it } }
        return all.firstOrNull { it.node.status != "ONLINE" } ?: all.firstOrNull { it.node.errors > 0 } ?: all.firstOrNull()
    }

    sealed class SizeCheck {
        object Ok : SizeCheck()
        /** Unknown size (the old disk is missing and TrueNAS has no record): ZFS still checks it. */
        object Unknown : SizeCheck()
        data class TooSmall(val missing: Long) : SizeCheck()
    }

    /** The new disk must be at least as large as the one it replaces. */
    fun sizeCheck(old: Long?, new: Long?): SizeCheck = when {
        old == null || new == null || old <= 0 || new <= 0 -> SizeCheck.Unknown
        new >= old -> SizeCheck.Ok
        else -> SizeCheck.TooSmall(old - new)
    }

    /** Size of the member being replaced: vdev stats, else TrueNAS's record of the missing disk, else the disk list. */
    fun memberSize(m: PoolMember, diskSizes: Map<String, Long>): Long? =
        m.node.disk?.let { diskSizes[it] } ?: m.node.unavailDisk?.size ?: m.node.size?.takeIf { it > 0 }

    /** Candidates that may be picked: not the member itself, sorted so suitable ones come first. */
    fun candidates(all: List<ReplacementCandidate>, oldSize: Long?): List<ReplacementCandidate> =
        all.sortedWith(compareBy<ReplacementCandidate> { sizeCheck(oldSize, it.size) is SizeCheck.TooSmall }.thenBy { it.exportedZpool != null }.thenBy { it.name })

    fun needsForce(c: ReplacementCandidate) = c.exportedZpool != null

    fun resilverRunning(scan: ScanInfo?): Boolean = scan?.function == "RESILVER" && scan.state == "SCANNING"

    fun resilverFinished(scan: ScanInfo?, since: Long): Boolean =
        scan?.function == "RESILVER" && scan.state == "FINISHED" && (scan.endMillis ?: Long.MAX_VALUE) >= since - 60_000

    /** "about 2 h 5 min" from TrueNAS's `total_secs_left`; null when unknown. */
    fun eta(secondsLeft: Long?): String? {
        val s = secondsLeft?.takeIf { it > 0 } ?: return null
        val d = s / 86_400; val h = (s % 86_400) / 3600; val m = (s % 3600) / 60
        return when {
            d > 0 -> "about ${d} d ${h} h"
            h > 0 -> "about ${h} h ${m} min"
            m > 0 -> "about $m min"
            else -> "less than a minute"
        }
    }
}
