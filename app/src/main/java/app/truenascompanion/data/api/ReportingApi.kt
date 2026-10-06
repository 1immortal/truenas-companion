package app.truenascompanion.data.api

import app.truenascompanion.data.model.ReportData
import app.truenascompanion.data.model.ReportGraph
import app.truenascompanion.data.model.ReportRange
import app.truenascompanion.data.model.ReportSeries
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put

/**
 * Reporting history (1.1.0): the same calls as the web UI's Reports page in 25.10 (`reports.service.ts`):
 * `reporting.netdata_graphs` for the available graphs/identifiers and
 * `reporting.netdata_get_data([{name, identifier}], {start, end, aggregate})` for the data.
 */
class ReportingApi(private val api: TrueNasApi) {

    suspend fun graphs(): List<ReportGraph> =
        api.rpc("reporting.netdata_graphs").arr()?.mapNotNull { it.obj()?.let(::parseGraph) } ?: emptyList()

    /** Several graphs in one call (one result per graph/identifier). [endSec] defaults to now. */
    suspend fun data(graphs: List<Pair<String, String?>>, range: ReportRange, endSec: Long = System.currentTimeMillis() / 1000, startSec: Long = endSec - range.seconds): List<ReportData> {
        if (graphs.isEmpty()) return emptyList()
        val g = buildJsonArray {
            graphs.forEach { (name, id) -> add(buildJsonObject { put("name", name); id?.let { put("identifier", it) } }) }
        }
        val q = buildJsonObject { put("start", startSec); put("end", endSec); put("aggregate", true) }
        return api.rpc("reporting.netdata_get_data", g, q).arr()?.mapNotNull { it.obj()?.let(::parseData) } ?: emptyList()
    }

    companion object {
        fun parseGraph(o: JsonObject): ReportGraph? = ReportGraph(
            name = o.str("name") ?: return null,
            title = o.str("title") ?: o.str("name")!!,
            verticalLabel = o.str("vertical_label"),
            identifiers = (o["identifiers"] as? JsonArray)?.mapNotNull { it.prim()?.contentOrNull },
        )

        private fun num(e: JsonElement?): Float = (e as? JsonPrimitive)?.takeUnless { it is JsonNull }?.doubleOrNull?.toFloat() ?: Float.NaN

        fun parseData(o: JsonObject): ReportData? {
            val name = o.str("name") ?: return null
            val legend = o["legend"].arr()?.mapNotNull { it.prim()?.contentOrNull } ?: emptyList()
            val rows = o["data"].arr()?.mapNotNull { it.arr() } ?: emptyList()
            val labels = legend.drop(1) // legend[0] == "time"
            val times = LongArray(rows.size) { i -> num(rows[i].getOrNull(0)).toLong() }
            val agg = o["aggregations"].obj()
            val series = labels.mapIndexed { idx, label ->
                val col = idx + 1
                ReportSeries(
                    label = label,
                    values = FloatArray(rows.size) { i -> num(rows[i].getOrNull(col)) },
                    min = agg?.get("min").obj()?.double(label),
                    mean = agg?.get("mean").obj()?.double(label),
                    max = agg?.get("max").obj()?.double(label),
                )
            }
            return ReportData(
                name = name,
                identifier = o.str("identifier"),
                start = o.long("start") ?: times.firstOrNull() ?: 0,
                end = o.long("end") ?: times.lastOrNull() ?: 0,
                times = times,
                series = series,
            )
        }

        /**
         * Keeps the chart light: at most [max] points by averaging buckets (gaps stay gaps). 1 month of netdata
         * history is ~700-2600 rows depending on the tier.
         */
        fun downsample(d: ReportData, max: Int = 360): ReportData {
            val n = d.times.size
            if (n <= max) return d
            val bucket = (n + max - 1) / max
            val m = (n + bucket - 1) / bucket
            val times = LongArray(m) { b -> d.times[minOf(n - 1, b * bucket + bucket / 2)] }
            val series = d.series.map { s ->
                ReportSeries(s.label, FloatArray(m) { b ->
                    var sum = 0f; var c = 0
                    for (i in b * bucket until minOf(n, (b + 1) * bucket)) { val v = s.values[i]; if (!v.isNaN()) { sum += v; c++ } }
                    if (c == 0) Float.NaN else sum / c
                }, s.min, s.mean, s.max)
            }
            return d.copy(times = times, series = series)
        }
    }
}
