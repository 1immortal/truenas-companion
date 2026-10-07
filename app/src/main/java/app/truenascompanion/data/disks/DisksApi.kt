package app.truenascompanion.data.disks

import app.truenascompanion.data.api.Parsers
import app.truenascompanion.data.api.TrueNasApi
import app.truenascompanion.data.api.arr
import app.truenascompanion.data.api.asJobId
import app.truenascompanion.data.api.awaitJob
import app.truenascompanion.data.api.bool
import app.truenascompanion.data.api.double
import app.truenascompanion.data.api.long
import app.truenascompanion.data.api.obj
import app.truenascompanion.data.api.parseDate
import app.truenascompanion.data.api.str
import app.truenascompanion.data.model.DiskAlert
import app.truenascompanion.data.model.DiskInfo
import app.truenascompanion.data.model.EnclosureSlot
import app.truenascompanion.data.model.PoolLayout
import app.truenascompanion.data.model.ReplacementCandidate
import app.truenascompanion.data.model.ScanInfo
import app.truenascompanion.data.model.UnavailDisk
import app.truenascompanion.data.model.VdevGroup
import app.truenascompanion.data.model.VdevNode
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Disk details and replacement calls (1.3.0), JSON-RPC over the shared WebSocket, with the arguments the TrueNAS 25.10
 * web UI uses (middlewared plugins/pool_/replace_disk.py, pool_disk_operations.py, disk_/availability.py,
 * enclosure_/enclosure2.py; webui replace-disk-dialog, unused-disk-select, zfs-info-card):
 *
 * - `pool.query` → topology (vdev tree with per-member read/write/checksum errors) and `scan` (scrub / resilver).
 * - `disk.query [] {extra: {pools: true}}`, `disk.temperatures`, `disk.temperature_agg(names, 7)`.
 * - `disk.details` → `{used, unused}`: replacement candidates are the unused disks plus used ones that only hold an
 *   exported pool (same as the web UI).
 * - `pool.offline|online(id, {label: guid})`, `pool.detach(id, {label: guid})`.
 * - `pool.replace(id, {label: guid, disk: identifier, force, preserve_settings, preserve_description})` (a job).
 * - `enclosure2.query` / `enclosure2.set_slot_status({enclosure_id, slot, status: ON|CLEAR})` for the identify LED.
 *
 * SMART: TrueNAS 25.10 removed `smart.test.*` results from the API; the only SMART information left is the
 * SMART alert source, so the disk screen shows matching alerts.
 */
class DisksApi(private val api: TrueNasApi) {

    suspend fun pools(): List<PoolLayout> = api.rpc("pool.query").arr().orEmpty().mapNotNull { it.obj()?.let(::pool) }

    suspend fun pool(id: Long): PoolLayout? =
        api.rpc("pool.query", buildJsonArray { add(buildJsonArray { add(JsonPrimitive("id")); add(JsonPrimitive("=")); add(JsonPrimitive(id)) }) })
            .arr()?.firstOrNull()?.obj()?.let(::pool)

    suspend fun disks(): List<DiskInfo> {
        val list = api.rpc("disk.query", JsonArray(emptyList()), buildJsonObject { put("extra", buildJsonObject { put("pools", true) }) })
            .arr().orEmpty().mapNotNull { it.obj()?.let(::disk) }
        if (list.isEmpty()) return list
        val names = list.map { it.name }
        val temps = runCatching { Parsers.diskTemperatures(api.rpc("disk.temperatures", JsonArray(names.map { JsonPrimitive(it) })).obj() ?: JsonObject(emptyMap())) }
            .getOrDefault(emptyMap())
        return list.map { it.copy(temperatureC = temps[it.name]) }
    }

    /** 7-day min / max / average temperature per disk (empty when reporting has no data). */
    suspend fun temperatureAgg(names: List<String>): Map<String, Triple<Double?, Double?, Double?>> = runCatching {
        val o = api.rpc("disk.temperature_agg", JsonArray(names.map { JsonPrimitive(it) }), JsonPrimitive(7)).obj() ?: return emptyMap()
        o.mapNotNull { (k, v) -> v.obj()?.let { k to Triple(it.double("min"), it.double("max"), it.double("avg")) } }.toMap()
    }.getOrDefault(emptyMap())

    suspend fun candidates(): List<ReplacementCandidate> {
        val o = api.rpc("disk.details").obj() ?: return emptyList()
        val unused = o["unused"].arr().orEmpty().mapNotNull { it.obj() }
        val exported = o["used"].arr().orEmpty().mapNotNull { it.obj() }.filter { it.str("exported_zpool") != null }
        return (unused + exported).mapNotNull(::candidate)
    }

    suspend fun enclosureSlots(): List<EnclosureSlot> = runCatching {
        api.rpc("enclosure2.query").arr().orEmpty().flatMap { e -> e.obj()?.let(::slots).orEmpty() }
    }.getOrDefault(emptyList())

    suspend fun setIdentify(slot: EnclosureSlot, on: Boolean) {
        api.rpc("enclosure2.set_slot_status", buildJsonObject {
            put("enclosure_id", slot.enclosureId); put("slot", slot.slot); put("status", if (on) "ON" else "CLEAR")
        })
    }

    suspend fun offline(poolId: Long, guid: String) { api.rpc("pool.offline", JsonPrimitive(poolId), label(guid)) }
    suspend fun online(poolId: Long, guid: String) { api.rpc("pool.online", JsonPrimitive(poolId), label(guid)) }
    suspend fun detach(poolId: Long, guid: String) { api.rpc("pool.detach", JsonPrimitive(poolId), label(guid)) }

    /** Starts `pool.replace`; returns the job id (or null if the server answered at once). */
    suspend fun startReplace(poolId: Long, guid: String, identifier: String, force: Boolean): Long? =
        api.rpc("pool.replace", JsonPrimitive(poolId), buildJsonObject {
            put("label", guid); put("disk", identifier); put("force", force)
            put("preserve_settings", true); put("preserve_description", true)
        }).asJobId()

    suspend fun awaitJob(id: Long) { api.awaitJob(id, "pool.replace", timeoutMs = 30 * 60_000L) }

    /** Active alerts about this disk (SMART*, DiskTemperature*, DiskNotDetected …) matched by name or serial. */
    suspend fun alertsFor(name: String, serial: String?): List<DiskAlert> =
        api.alerts().filter { !it.dismissed && matches(it.klass, it.args, it.text, name, serial) }
            .map { DiskAlert(it.klass ?: "", it.level, it.text, it.datetimeMillis) }

    companion object {
        private fun label(guid: String) = buildJsonObject { put("label", guid) }

        private val DISK_ALERT = Regex("^(SMART.*|DiskTemperature.*|DiskNotDetected|DiskIOErrors|NVMe.*|Disk.*)$")

        fun matches(klass: String?, args: JsonElement?, text: String, name: String, serial: String?): Boolean {
            if (klass == null || !DISK_ALERT.matches(klass)) return false
            val o = args.obj()
            val values = listOfNotNull(o?.str("device"), o?.str("disk"), o?.str("name"), o?.str("serial"), (args as? JsonPrimitive)?.takeIf { it.isString }?.content)
                .map { it.removePrefix("/dev/") }
            if (values.any { it == name || (serial != null && it == serial) }) return true
            return Regex("(^|[^a-z0-9])${Regex.escape(name)}([^a-z0-9]|$)").containsMatchIn(text) ||
                (serial != null && serial.length >= 4 && text.contains(serial))
        }

        fun pool(o: JsonObject): PoolLayout {
            val topo = o["topology"].obj()
            val groups = listOf("data", "special", "dedup", "log", "cache", "spare").mapNotNull { c ->
                topo?.get(c).arr()?.mapNotNull { it.obj()?.let(::node) }?.takeIf { it.isNotEmpty() }?.let { VdevGroup(c, it) }
            }
            val scan = o["scan"].obj()?.let { s ->
                ScanInfo(
                    function = s.str("function"), state = s.str("state"), percent = s.double("percentage"),
                    bytesToProcess = s.long("bytes_to_process"), bytesProcessed = s.long("bytes_processed"), bytesIssued = s.long("bytes_issued"),
                    errors = s.long("errors"), startMillis = parseDate(s["start_time"]), endMillis = parseDate(s["end_time"]),
                    secondsLeft = s.long("total_secs_left"), paused = s["pause"]?.let { p -> parseDate(p) != null } ?: false,
                )
            }
            return PoolLayout(
                id = o.long("id") ?: 0, name = o.str("name") ?: "?", guid = o.str("guid"), status = o.str("status") ?: "UNKNOWN",
                healthy = o.bool("healthy") ?: false, statusDetail = o.str("status_detail"),
                size = o.long("size"), allocated = o.long("allocated"), free = o.long("free"), groups = groups, scan = scan,
            )
        }

        fun node(o: JsonObject): VdevNode {
            val stats = o["stats"].obj()
            val un = o["unavail_disk"].obj()
            return VdevNode(
                name = o.str("name") ?: "?",
                type = o.str("type")?.uppercase() ?: "DISK",
                guid = o.str("guid"),
                status = o.str("status") ?: "UNKNOWN",
                path = o.str("path"),
                disk = o.str("disk"),
                readErrors = stats?.long("read_errors") ?: 0,
                writeErrors = stats?.long("write_errors") ?: 0,
                checksumErrors = stats?.long("checksum_errors") ?: 0,
                size = stats?.long("size"),
                children = o["children"].arr().orEmpty().mapNotNull { it.obj()?.let(::node) },
                unavailDisk = un?.let { UnavailDisk(it.str("name"), it.str("serial"), it.str("model"), it.long("size")) },
            )
        }

        fun disk(o: JsonObject) = DiskInfo(
            name = o.str("name") ?: o.str("devname")?.removePrefix("/dev/") ?: "?",
            identifier = o.str("identifier"),
            serial = o.str("serial")?.takeIf { it.isNotBlank() },
            model = o.str("model")?.takeIf { it.isNotBlank() },
            size = o.long("size"),
            type = o.str("type"),
            bus = o.str("bus"),
            subsystem = o.str("subsystem"),
            rotationRate = o.long("rotationrate")?.toInt(),
            zfsGuid = o.str("zfs_guid"),
            pool = o.str("pool"),
            description = o.str("description")?.takeIf { it.isNotBlank() },
        )

        fun candidate(o: JsonObject): ReplacementCandidate? {
            val identifier = o.str("identifier") ?: return null
            return ReplacementCandidate(
                name = o.str("name") ?: o.str("devname") ?: "?",
                identifier = identifier,
                serial = o.str("serial")?.takeIf { it.isNotBlank() },
                model = o.str("model")?.takeIf { it.isNotBlank() },
                size = o.long("size"),
                type = o.str("type"),
                bus = o.str("bus"),
                exportedZpool = o.str("exported_zpool"),
                duplicateSerial = o["duplicate_serial"].arr().orEmpty().mapNotNull { (it as? JsonPrimitive)?.content },
            )
        }

        fun slots(enc: JsonObject): List<EnclosureSlot> {
            val id = enc.str("id") ?: return emptyList()
            val bays = enc["elements"].obj()?.get("Array Device Slot").obj() ?: return emptyList()
            return bays.mapNotNull { (slot, v) ->
                val s = v.obj() ?: return@mapNotNull null
                val dev = s.str("dev") ?: return@mapNotNull null
                val n = slot.toIntOrNull() ?: return@mapNotNull null
                EnclosureSlot(id, n, dev, s.bool("supports_identify_light") ?: false, s.str("drive_bay_light_status")?.let { it == "ON" })
            }
        }
    }
}
