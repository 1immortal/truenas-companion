package app.truenascompanion.data.api

import app.truenascompanion.data.model.AlertItem
import app.truenascompanion.data.model.AppInfo
import app.truenascompanion.data.model.AppState
import app.truenascompanion.data.model.Dataset
import app.truenascompanion.data.model.Disk
import app.truenascompanion.data.model.Pool
import app.truenascompanion.data.model.RealtimeStats
import app.truenascompanion.data.model.ServiceInfo
import app.truenascompanion.data.model.SystemInfo
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import java.time.Instant
import java.time.OffsetDateTime

// --- tolerant JSON helpers: TrueNAS payloads differ between releases, never crash on a missing field ---
internal fun JsonElement?.obj(): JsonObject? = this as? JsonObject
internal fun JsonElement?.arr(): JsonArray? = this as? JsonArray
internal fun JsonElement?.prim(): JsonPrimitive? = (this as? JsonPrimitive)?.takeUnless { it is JsonNull }
internal fun JsonObject.str(key: String): String? = this[key].prim()?.contentOrNull
internal fun JsonObject.long(key: String): Long? = this[key].prim()?.let { it.longOrNull ?: it.doubleOrNull?.toLong() ?: it.contentOrNull?.toLongOrNull() }
internal fun JsonObject.double(key: String): Double? = this[key].prim()?.let { it.doubleOrNull ?: it.contentOrNull?.toDoubleOrNull() }
internal fun JsonObject.bool(key: String): Boolean? = this[key].prim()?.booleanOrNull

/** ZFS property objects look like {"parsed": 123, "value": "123", "rawvalue": "123"}. */
internal fun JsonObject.zfsLong(key: String): Long? {
    val e = this[key] ?: return null
    e.prim()?.let { return it.longOrNull ?: it.doubleOrNull?.toLong() }
    val o = e.obj() ?: return null
    return o.long("parsed") ?: o.long("rawvalue") ?: o.long("value")
}

internal fun JsonObject.zfsString(key: String): String? {
    val e = this[key] ?: return null
    e.prim()?.let { return it.contentOrNull }
    val o = e.obj() ?: return null
    return o["parsed"].prim()?.contentOrNull ?: o.str("value")
}

/** Dates arrive either as ISO strings or as {"$date": epochMillis}. */
internal fun parseDate(e: JsonElement?): Long? {
    e.obj()?.let { o -> return o.long("\$date") }
    val s = e.prim()?.contentOrNull ?: return null
    s.toLongOrNull()?.let { return it }
    return runCatching { OffsetDateTime.parse(s).toInstant().toEpochMilli() }.getOrNull()
        ?: runCatching { Instant.parse(s).toEpochMilli() }.getOrNull()
}

object Parsers {

    fun systemInfo(o: JsonObject) = SystemInfo(
        hostname = o.str("hostname") ?: "TrueNAS",
        version = o.str("version") ?: "unknown",
        uptimeSeconds = o.double("uptime_seconds")?.toLong(),
        uptimeText = o.str("uptime"),
        cpuModel = o.str("model"),
        cores = o.long("cores")?.toInt(),
        physicalMemory = o.long("physmem"),
        loadAverage = o["loadavg"].arr()?.mapNotNull { it.prim()?.doubleOrNull } ?: emptyList(),
        systemProduct = o.str("system_product"),
        eccMemory = o.bool("ecc_memory"),
    )

    /**
     * Parses a `reporting.realtime` event (`fields` object). Handles the 25.x schema
     * (`cpu.cpu.usage`, `cpu.cpuN.temp`, `memory.physical_memory_*`, `interfaces.*.received_bytes_rate`)
     * and falls back to older layouts where possible.
     */
    fun realtime(fields: JsonObject, fallbackTotalMemory: Long? = null): RealtimeStats {
        val cpu = fields["cpu"].obj()
        val cpuPercent = cpu?.let { c ->
            c["cpu"].obj()?.double("usage")
                ?: c["average"].obj()?.double("usage")
                ?: c.entries.filter { it.key.startsWith("cpu") }.mapNotNull { it.value.obj()?.double("usage") }
                    .takeIf { it.isNotEmpty() }?.average()
        }
        val coreTemps = cpu?.entries?.filter { it.key.startsWith("cpu") && it.key != "cpu" }
            ?.mapNotNull { it.value.obj()?.double("temp") } ?: emptyList()
        val legacyTemps = cpu?.get("temperature_celsius").obj()?.values?.mapNotNull { it.prim()?.doubleOrNull } ?: emptyList()
        val cpuTemp = cpu?.get("cpu").obj()?.double("temp")
            ?: coreTemps.maxOrNull()
            ?: legacyTemps.maxOrNull()

        val mem = fields["memory"].obj()
        val total = mem?.long("physical_memory_total") ?: fallbackTotalMemory
        val available = mem?.long("physical_memory_available")
            ?: mem?.get("classes").obj()?.let { cls ->
                // Pre-25.04 layout: memory.classes.{unused,cache,buffers,...}
                cls.long("unused")?.let { it + (cls.long("cache") ?: 0) + (cls.long("buffers") ?: 0) }
            }

        var rx = 0.0
        var tx = 0.0
        var anyIface = false
        fields["interfaces"].obj()?.forEach { (_, v) ->
            val i = v.obj() ?: return@forEach
            if (i.str("link_state") == "LINK_STATE_DOWN") return@forEach
            anyIface = true
            rx += i.double("received_bytes_rate") ?: 0.0
            tx += i.double("sent_bytes_rate") ?: 0.0
        }
        return RealtimeStats(
            cpuPercent = cpuPercent,
            cpuTempC = cpuTemp,
            memoryTotal = total,
            memoryAvailable = available,
            arcSize = mem?.long("arc_size"),
            netRxBytesPerSec = if (anyIface) rx else null,
            netTxBytesPerSec = if (anyIface) tx else null,
        )
    }

    private fun collectDisks(e: JsonElement?, out: MutableList<String>) {
        when (e) {
            is JsonObject -> {
                e.str("disk")?.let { out += it }
                e.values.forEach { collectDisks(it, out) }
            }
            is JsonArray -> e.forEach { collectDisks(it, out) }
            else -> Unit
        }
    }

    fun pool(o: JsonObject): Pool {
        val scan = o["scan"].obj()
        val disks = mutableListOf<String>()
        collectDisks(o["topology"], disks)
        return Pool(
            id = o.long("id") ?: 0,
            name = o.str("name") ?: "?",
            status = o.str("status") ?: "UNKNOWN",
            healthy = o.bool("healthy") ?: false,
            warning = o.bool("warning") ?: false,
            statusDetail = o.str("status_detail"),
            size = o.long("size"),
            allocated = o.long("allocated"),
            free = o.long("free"),
            fragmentation = o.str("fragmentation"),
            scanFunction = scan?.str("function"),
            scanState = scan?.str("state"),
            scanPercent = scan?.double("percentage"),
            scanErrors = scan?.long("errors"),
            diskNames = disks.distinct().map { it.substringAfterLast('/') },
        )
    }

    fun disk(o: JsonObject, poolByDisk: Map<String, String>) = Disk(
        name = o.str("name") ?: o.str("devname") ?: "?",
        serial = o.str("serial"),
        model = o.str("model"),
        size = o.long("size"),
        type = o.str("type"),
        rotationRate = o.long("rotationrate")?.toInt(),
        pool = o.str("pool") ?: poolByDisk[o.str("name")],
        temperatureC = null,
    )

    /** disk.temperatures returns {"sda": 34} or, on some versions, {"sda": {"temperature": 34, ...}}. */
    fun diskTemperatures(o: JsonObject): Map<String, Double> = o.mapNotNull { (name, v) ->
        val t = v.prim()?.doubleOrNull ?: v.obj()?.let { it.double("temperature") ?: it.double("temp") ?: it.double("current") }
        t?.let { name to it }
    }.toMap()

    fun dataset(o: JsonObject): Dataset {
        val id = o.str("id") ?: o.str("name") ?: "?"
        val encrypted = o.bool("encrypted") ?: false
        return Dataset(
            id = id,
            pool = o.str("pool") ?: id.substringBefore('/'),
            type = o.str("type") ?: "FILESYSTEM",
            used = o.zfsLong("used"),
            available = o.zfsLong("available"),
            encrypted = encrypted,
            locked = o.bool("locked") ?: (encrypted && o.bool("key_loaded") == false),
            mountpoint = o.str("mountpoint"),
        )
    }

    fun app(o: JsonObject): AppInfo {
        val portals = o["portals"].obj()
        return AppInfo(
            name = o.str("name") ?: o.str("id") ?: "?",
            state = when (o.str("state")?.uppercase()) {
                "RUNNING" -> AppState.RUNNING
                "STOPPED" -> AppState.STOPPED
                "DEPLOYING" -> AppState.DEPLOYING
                "STOPPING" -> AppState.STOPPING
                "CRASHED" -> AppState.CRASHED
                else -> AppState.UNKNOWN
            },
            version = o.str("human_version") ?: o.str("version"),
            upgradeAvailable = o.bool("upgrade_available") ?: false,
            imageUpdatesAvailable = o.bool("image_updates_available") ?: false,
            description = o["metadata"].obj()?.str("description"),
            portalUrl = portals?.values?.firstNotNullOfOrNull { it.prim()?.contentOrNull },
            containers = o["active_workloads"].obj()?.long("containers")?.toInt(),
        )
    }

    /** Pre-24.10 Kubernetes based apps (`chart.release.query`). */
    fun chartRelease(o: JsonObject): AppInfo {
        val portals = o["portals"].obj()
        return AppInfo(
            name = o.str("name") ?: o.str("id") ?: "?",
            state = when (o.str("status")?.uppercase()) {
                "ACTIVE" -> AppState.RUNNING
                "STOPPED" -> AppState.STOPPED
                "DEPLOYING" -> AppState.DEPLOYING
                else -> AppState.UNKNOWN
            },
            version = o.str("human_version") ?: o.str("chart_version"),
            upgradeAvailable = o.bool("update_available") ?: false,
            imageUpdatesAvailable = o.bool("container_images_update_available") ?: false,
            description = o["chart_metadata"].obj()?.str("description"),
            portalUrl = portals?.values?.firstNotNullOfOrNull { v -> v.arr()?.firstOrNull()?.prim()?.contentOrNull ?: v.prim()?.contentOrNull },
            containers = null,
            legacyChart = true,
        )
    }

    fun alert(o: JsonObject): AlertItem {
        val formatted = o.str("formatted")
        return AlertItem(
            uuid = o.str("uuid") ?: o.str("id") ?: "",
            level = o.str("level") ?: "INFO",
            text = app.truenascompanion.util.Format.stripHtml(formatted ?: o.str("text") ?: ""),
            klass = o.str("klass"),
            datetimeMillis = parseDate(o["last_occurrence"]) ?: parseDate(o["datetime"]),
            dismissed = o.bool("dismissed") ?: false,
            oneShot = o.bool("one_shot") ?: false,
        )
    }

    fun service(o: JsonObject) = ServiceInfo(
        id = o.long("id") ?: 0,
        service = o.str("service") ?: "?",
        running = o.str("state").equals("RUNNING", ignoreCase = true),
        enabledOnBoot = o.bool("enable") ?: false,
    )
}
