package app.truenascompanion.data.api

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.longOrNull

/** A job id returned by a middleware `@job` method, or null when the method answered directly. */
internal fun JsonElement?.asJobId(): Long? = prim()?.takeUnless { it.isString }?.longOrNull

/**
 * Calls a middleware job method (e.g. `certificate.create`) and waits for it through `core.get_jobs`, like the web
 * UI's `api.job(...)`. Returns the job's `result`. Polls quickly first, then slower (see [jobPollDelayMs]).
 */
suspend fun TrueNasApi.callJob(method: String, vararg args: JsonElement, timeoutMs: Long = 180_000): JsonElement? {
    val first = rpc(method, *args)
    val id = first.asJobId() ?: return first
    return awaitJob(id, method, timeoutMs)
}

suspend fun TrueNasApi.awaitJob(id: Long, method: String = "job", timeoutMs: Long = 180_000): JsonElement? = withTimeout(timeoutMs) {
    var polls = 0
    while (true) {
        val filter = buildJsonArray { add(buildJsonArray { add(JsonPrimitive("id")); add(JsonPrimitive("=")); add(JsonPrimitive(id)) }) }
        val job = rpc("core.get_jobs", filter).arr()?.firstOrNull().obj()
        when (job?.str("state")) {
            "SUCCESS" -> return@withTimeout job["result"]
            "FAILED", "ABORTED" -> throw TrueNasException.JobFailed(
                job.str("error")?.lineSequence()?.firstOrNull { it.isNotBlank() } ?: "$method failed",
            )
            else -> delay(jobPollDelayMs(polls++))
        }
    }
    @Suppress("UNREACHABLE_CODE") null
}

/** `[[field, op, value]]` query filter helper. */
internal fun queryFilter(vararg f: Triple<String, String, JsonElement>) = buildJsonArray {
    f.forEach { (field, op, value) -> add(buildJsonArray { add(JsonPrimitive(field)); add(JsonPrimitive(op)); add(value) }) }
}
