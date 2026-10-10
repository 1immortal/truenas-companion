package app.truenascompanion.notify

import app.truenascompanion.data.api.TrueNasApi
import app.truenascompanion.data.api.arr
import app.truenascompanion.data.api.obj
import app.truenascompanion.data.api.str
import app.truenascompanion.data.api.long
import app.truenascompanion.data.api.double
import app.truenascompanion.data.model.Pool
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

enum class ProgressKind(val label: String) {
    SCRUB("Scrub"), RESILVER("Resilver"), REPLICATION("Replication"), CLOUD_SYNC("Cloud sync"), UPDATE("TrueNAS update"),
}

/** One running operation shown as an ongoing notification (1.10.0). [key] is stable for the life of the operation. */
data class ProgressItem(
    val key: String,
    val kind: ProgressKind,
    val title: String,
    val percent: Int?,
    val detail: String?,
    val destination: String,
    val arg: String? = null,
)

/** A `core.get_jobs` entry, reduced to what progress needs. */
data class RunningJob(val id: Long, val method: String, val description: String?, val percent: Int?, val progressText: String?, val firstArg: String?)

/**
 * Builds the progress list from `pool.query` (`scan`: function SCRUB/RESILVER, state SCANNING, percentage) and running
 * `core.get_jobs` entries. Job methods checked against TrueNAS 25.10: `replication.run` (also what zettarepl uses for
 * scheduled runs), `replication.run_onetime`, `cloudsync.sync`, `update.run`, `update.download`, `update.manual`,
 * `update.file`.
 */
object ProgressTracker {
    val JOB_METHODS = mapOf(
        "replication.run" to ProgressKind.REPLICATION,
        "replication.run_onetime" to ProgressKind.REPLICATION,
        "cloudsync.sync" to ProgressKind.CLOUD_SYNC,
        "update.run" to ProgressKind.UPDATE,
        "update.download" to ProgressKind.UPDATE,
        "update.manual" to ProgressKind.UPDATE,
        "update.file" to ProgressKind.UPDATE,
    )

    fun items(pools: List<Pool>?, jobs: List<RunningJob>?): List<ProgressItem> {
        val out = ArrayList<ProgressItem>()
        pools.orEmpty().forEach { p ->
            if (!p.scanState.equals("SCANNING", true)) return@forEach
            val kind = when {
                p.scanFunction.equals("SCRUB", true) -> ProgressKind.SCRUB
                p.scanFunction.equals("RESILVER", true) -> ProgressKind.RESILVER
                else -> return@forEach
            }
            out += ProgressItem(
                key = "${kind.name.lowercase()}:${p.name}",
                kind = kind,
                title = if (kind == ProgressKind.SCRUB) "Scrubbing ${p.name}" else "Resilvering ${p.name}",
                percent = p.scanPercent?.toInt(),
                detail = null,
                destination = DeepLink.DEST_POOL, arg = p.name,
            )
        }
        jobs.orEmpty().forEach { j ->
            val kind = JOB_METHODS[j.method] ?: return@forEach
            val title = when (j.method) {
                "update.download" -> "Downloading TrueNAS update"
                "update.run", "update.manual", "update.file" -> "Updating TrueNAS"
                else -> j.description?.takeIf { it.isNotBlank() }?.take(80) ?: kind.label
            }
            out += ProgressItem(
                key = "job:${j.id}",
                kind = kind,
                title = title,
                percent = j.percent,
                detail = j.progressText?.lineSequence()?.firstOrNull { it.isNotBlank() }?.take(120),
                destination = when (kind) {
                    ProgressKind.CLOUD_SYNC -> DeepLink.DEST_CLOUD_SYNC
                    ProgressKind.REPLICATION -> DeepLink.DEST_REPLICATION
                    ProgressKind.UPDATE -> DeepLink.DEST_UPDATE
                    else -> DeepLink.DEST_TASKS
                },
            )
        }
        return out
    }

    fun parseJob(o: JsonObject): RunningJob? {
        val progress = o["progress"].obj()
        return RunningJob(
            id = o.long("id") ?: return null,
            method = o.str("method") ?: return null,
            description = o.str("description"),
            percent = progress?.double("percent")?.toInt(),
            progressText = progress?.str("description"),
            firstArg = runCatching { o["arguments"].arr()?.firstOrNull()?.jsonPrimitive?.content }.getOrNull(),
        )
    }

    /** `core.get_jobs [["state", "=", "RUNNING"], ["method", "in", [...]]]`. */
    suspend fun runningJobs(api: TrueNasApi): List<RunningJob> {
        val filters = buildJsonArray {
            add(buildJsonArray { add(JsonPrimitive("state")); add(JsonPrimitive("=")); add(JsonPrimitive("RUNNING")) })
            add(buildJsonArray { add(JsonPrimitive("method")); add(JsonPrimitive("in")); add(buildJsonArray { JOB_METHODS.keys.forEach { add(JsonPrimitive(it)) } }) })
        }
        return api.rpc("core.get_jobs", filters).arr().orEmpty().mapNotNull { it.obj()?.let(::parseJob) }
    }
}
