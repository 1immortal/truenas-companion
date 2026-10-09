package app.truenascompanion.data.api

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
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

suspend fun TrueNasApi.awaitJob(id: Long, method: String = "job", timeoutMs: Long = 180_000): JsonElement? {
    // 1.7.1 (review P1-5): withTimeoutOrNull + an explicit Timeout, so a caller's cancellation isn't turned into
    // "timed out" and our timeout doesn't look like a cancellation to the caller.
    var done = false
    var result: JsonElement? = null
    withTimeoutOrNull(timeoutMs) {
        var polls = 0
        while (!done) {
            val filter = buildJsonArray { add(buildJsonArray { add(JsonPrimitive("id")); add(JsonPrimitive("=")); add(JsonPrimitive(id)) }) }
            val job = rpc("core.get_jobs", filter).arr()?.firstOrNull().obj()
            when (job?.str("state")) {
                "SUCCESS" -> { result = job["result"]; done = true }
                "FAILED", "ABORTED" -> throw TrueNasException.JobFailed(
                    job.str("error")?.lineSequence()?.firstOrNull { it.isNotBlank() } ?: "$method failed",
                )
                else -> delay(jobPollDelayMs(polls++))
            }
        }
    }
    if (!done) throw TrueNasException.Timeout("$method is still running on the NAS (it continues there). Check its progress in Jobs.")
    return result
}

/** `[[field, op, value]]` query filter helper. */
internal fun queryFilter(vararg f: Triple<String, String, JsonElement>) = buildJsonArray {
    f.forEach { (field, op, value) -> add(buildJsonArray { add(JsonPrimitive(field)); add(JsonPrimitive(op)); add(value) }) }
}
