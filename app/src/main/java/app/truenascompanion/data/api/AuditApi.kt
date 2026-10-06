package app.truenascompanion.data.api

import app.truenascompanion.data.model.AuditEntry
import app.truenascompanion.data.model.AuditEvents
import app.truenascompanion.data.model.AuditFilter
import app.truenascompanion.data.model.AuditQuick
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * System › Audit (1.1.0). Same request shape as the web UI's `AuditApiDataProvider`:
 * `audit.query({services: [svc], "query-filters": [...], "query-options": {limit, offset, order_by}})` and
 * `{"query-options": {"count": true}}` for the total. Filters use the same fields (event, username, address,
 * message_timestamp, service_data.*, event_data.*). Export is done on the phone (CSV) from the filtered rows,
 * so no `/_download` HTTP endpoint is needed.
 */
class AuditApi(private val api: TrueNasApi) {

    suspend fun query(filter: AuditFilter, offset: Int, limit: Int = PAGE_SIZE, nowSec: Long = System.currentTimeMillis() / 1000): List<AuditEntry> =
        api.rpc("audit.query", request(filter, nowSec, buildJsonObject {
            put("limit", limit)
            put("offset", offset)
            putJsonArray("order_by") { add(JsonPrimitive("-message_timestamp")) }
        })).arr()?.mapNotNull { it.obj()?.let(::parse) } ?: emptyList()

    suspend fun count(filter: AuditFilter, nowSec: Long = System.currentTimeMillis() / 1000): Int? =
        api.rpc("audit.query", request(filter, nowSec, buildJsonObject { put("count", true) })).prim()?.intOrNull

    companion object {
        const val PAGE_SIZE = 50
        /** Rows exported at most (the web UI's export has no limit but is a server-side download). */
        const val EXPORT_LIMIT = 5_000
        private val pretty = Json { prettyPrint = true }

        fun request(filter: AuditFilter, nowSec: Long, options: JsonObject) = buildJsonObject {
            putJsonArray("services") { add(JsonPrimitive(filter.service.api)) }
            put("query-filters", filters(filter, nowSec))
            put("query-options", options)
        }

        private fun f(field: String, op: String, value: JsonElement) = buildJsonArray { add(JsonPrimitive(field)); add(JsonPrimitive(op)); add(value) }

        /** Escapes regex characters and turns `*` into `.*`, like the web UI's basic search. */
        fun regex(term: String): String = term.trim().replace(Regex("""[-/\\^$+?.()|\[\]{}]"""), """\\$0""").replace("*", ".*")

        fun filters(filter: AuditFilter, nowSec: Long): JsonArray = buildJsonArray {
            filter.time.seconds?.let { add(f("message_timestamp", ">", JsonPrimitive(nowSec - it))) }
            filter.event?.let { add(f("event", "=", JsonPrimitive(it))) }
            filter.username.trim().takeIf { it.isNotEmpty() }?.let { add(f("username", "~", JsonPrimitive(regex(it)))) }
            filter.address.trim().takeIf { it.isNotEmpty() }?.let { add(f("address", "~", JsonPrimitive(regex(it)))) }
            if (AuditQuick.AUTHENTICATION in filter.quick && filter.event == null) add(f("event", "=", JsonPrimitive("AUTHENTICATION")))
            if (AuditQuick.METHOD_CALLS in filter.quick && filter.event == null) add(f("event", "=", JsonPrimitive("METHOD_CALL")))
            if (AuditQuick.FAILED in filter.quick) add(f("success", "=", JsonPrimitive(false)))
            if (AuditQuick.LEGACY_REST in filter.quick) {
                // What TrueNAS' own "Deprecated REST API usage" alert counts (alert/source/rest.py).
                if (filter.event == null && AuditQuick.AUTHENTICATION !in filter.quick) add(f("event", "=", JsonPrimitive("AUTHENTICATION")))
                add(f("service_data.protocol", "=", JsonPrimitive("LEGACY_REST")))
            }
            // Basic search box: an event name match searches by event, anything else by username (web UI behaviour).
            filter.search.trim().takeIf { it.isNotEmpty() }?.let { term ->
                val pattern = regex(term)
                val eventPattern = Regex(pattern.replace(Regex("\\s+"), "_").uppercase().replace("_", "[_-]"), RegexOption.IGNORE_CASE)
                val ev = AuditEvents.ALL.firstOrNull { eventPattern.containsMatchIn(it) }
                if (ev != null) add(f("event", "~", JsonPrimitive(ev))) else add(f("username", "~", JsonPrimitive(pattern)))
            }
        }

        fun parse(o: JsonObject): AuditEntry? {
            val event = o.str("event") ?: return null
            val ev = o["event_data"].obj()
            val svc = o["service_data"].obj()
            val summary = when {
                event == "METHOD_CALL" -> listOfNotNull(ev?.str("method"), ev?.str("description")?.takeIf { it.isNotBlank() }).joinToString(" · ")
                event == "AUTHENTICATION" || event == "LOGOUT" -> listOfNotNull(
                    ev?.get("credentials").obj()?.str("credentials"),
                    svc?.str("protocol"),
                ).joinToString(" · ")
                ev?.get("file").obj()?.get("path") != null -> ev?.get("file").obj()?.str("path").orEmpty()
                ev?.str("command") != null -> ev?.str("command").orEmpty()
                else -> ev?.str("reason").orEmpty()
            }
            return AuditEntry(
                auditId = o.str("audit_id"),
                timestamp = o.long("message_timestamp") ?: parseDate(o["timestamp"])?.div(1000) ?: 0,
                address = o.str("address").orEmpty(),
                username = o.str("username").orEmpty(),
                service = o.str("service").orEmpty(),
                event = event,
                success = o.bool("success") ?: true,
                session = o.str("session"),
                summary = summary,
                raw = o,
            )
        }

        fun prettyJson(o: JsonObject): String = pretty.encodeToString(JsonObject.serializer(), o)

        private fun csvCell(s: String): String =
            if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + s.replace("\"", "\"\"") + "\"" else s

        /** CSV with the web UI's columns plus the raw event data as compact JSON. */
        fun toCsv(entries: List<AuditEntry>): String = buildString {
            append("timestamp,service,event,username,address,success,summary,audit_id,session,event_data\r\n")
            entries.forEach { e ->
                val iso = java.time.Instant.ofEpochSecond(e.timestamp).toString()
                listOf(iso, e.service, e.event, e.username, e.address, e.success.toString(), e.summary, e.auditId.orEmpty(), e.session.orEmpty(),
                    e.raw["event_data"]?.toString().orEmpty())
                    .joinTo(this, ",") { csvCell(it) }
                append("\r\n")
            }
        }
    }
}
