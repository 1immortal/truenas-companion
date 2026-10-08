package app.truenascompanion.data.api

import app.truenascompanion.data.model.CronJob
import app.truenascompanion.data.model.CronJobInput
import app.truenascompanion.data.model.InitScript
import app.truenascompanion.data.model.InitScriptInput
import app.truenascompanion.data.model.InitScriptType
import app.truenascompanion.data.model.InitScriptWhen
import app.truenascompanion.data.model.LastJob
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * System › Scheduled tasks (1.4.0), TrueNAS 25.10 (`plugins/cron.py`, `plugins/init_shutdown_script.py`):
 * `cronjob.query/create/update/delete`, `cronjob.run(id, skip_disabled)` (a job with logs),
 * `initshutdownscript.query/create/update/delete` and `user.query` for the user picker.
 * There is no public way to run an init/shutdown script on demand (`initshutdownscript.execute_init_tasks` is private).
 */
class TasksApi(private val api: TrueNasApi) {

    suspend fun cronJobs(): List<CronJob> =
        api.rpc("cronjob.query").arr()?.mapNotNull { it.obj()?.let(::cronJob) }?.sortedBy { it.id } ?: emptyList()

    suspend fun createCronJob(c: CronJobInput): CronJob? = api.rpc("cronjob.create", cronJson(c)).obj()?.let(::cronJob)

    suspend fun updateCronJob(id: Int, c: CronJobInput): CronJob? = api.rpc("cronjob.update", JsonPrimitive(id), cronJson(c)).obj()?.let(::cronJob)

    suspend fun setCronEnabled(id: Int, enabled: Boolean) {
        api.rpc("cronjob.update", JsonPrimitive(id), buildJsonObject { put("enabled", enabled) })
    }

    suspend fun deleteCronJob(id: Int) { api.rpc("cronjob.delete", JsonPrimitive(id)) }

    /** Starts `cronjob.run` (runs even when the job is disabled) and returns the job id. */
    suspend fun runCronJob(id: Int): Long =
        api.rpc("cronjob.run", JsonPrimitive(id), JsonPrimitive(false)).asJobId() ?: throw TrueNasException.JobFailed("TrueNAS didn't start the cron job")

    /** Polls `core.get_jobs` until the job ends, reporting progress (and the log excerpt) on the way. */
    suspend fun followJob(jobId: Long, onUpdate: (LastJob) -> Unit): LastJob {
        var polls = 0
        while (true) {
            val o = api.rpc("core.get_jobs", queryFilter(Triple("id", "=", JsonPrimitive(jobId)))).arr()?.firstOrNull().obj()
            val j = ProtectionParsers.lastJob(o)
            if (j != null) {
                onUpdate(j)
                if (!j.state.active && j.state != app.truenascompanion.data.model.JobState.UNKNOWN) return j
            }
            delay(jobPollDelayMs(polls++))
        }
    }

    suspend fun initScripts(): List<InitScript> =
        api.rpc("initshutdownscript.query").arr()?.mapNotNull { it.obj()?.let(::initScript) }
            ?.sortedWith(compareBy({ it.whenRun.ordinal }, { it.id })) ?: emptyList()

    suspend fun createInitScript(s: InitScriptInput): InitScript? = api.rpc("initshutdownscript.create", initJson(s)).obj()?.let(::initScript)

    suspend fun updateInitScript(id: Int, s: InitScriptInput): InitScript? =
        api.rpc("initshutdownscript.update", JsonPrimitive(id), initJson(s)).obj()?.let(::initScript)

    suspend fun setInitScriptEnabled(id: Int, enabled: Boolean) {
        api.rpc("initshutdownscript.update", JsonPrimitive(id), buildJsonObject { put("enabled", enabled) })
    }

    suspend fun deleteInitScript(id: Int) { api.rpc("initshutdownscript.delete", JsonPrimitive(id)) }

    /** User names for the cron job's "Run as" picker: root first, then local users, then the other built-in accounts. */
    suspend fun usernames(): List<String> {
        val options = buildJsonObject { put("select", JsonArray(listOf("username", "builtin", "locked").map { JsonPrimitive(it) })) }
        val users = api.rpc("user.query", JsonArray(emptyList()), options).arr()?.mapNotNull { it.obj() } ?: emptyList()
        return users.mapNotNull { u -> u.str("username")?.let { it to (u.bool("builtin") ?: false) } }
            .sortedWith(compareBy({ it.first != "root" }, { it.second }, { it.first.lowercase() }))
            .map { it.first }.distinct()
    }

    companion object {
        fun cronJob(o: JsonObject): CronJob? = CronJob(
            id = o.long("id")?.toInt() ?: return null,
            description = o.str("description").orEmpty(),
            command = o.str("command").orEmpty(),
            user = o.str("user").orEmpty(),
            schedule = ProtectionParsers.cron(o["schedule"].obj()),
            enabled = o.bool("enabled") ?: true,
            hideStdout = o.bool("stdout") ?: true,
            hideStderr = o.bool("stderr") ?: false,
        )

        fun cronJson(c: CronJobInput) = buildJsonObject {
            put("description", c.description.trim())
            put("command", c.command.trim())
            put("user", c.user.trim())
            put("enabled", c.enabled)
            put("stdout", c.hideStdout)
            put("stderr", c.hideStderr)
            put("schedule", ProtectionParsers.cronJson(c.schedule.copy(
                minute = c.schedule.minute.trim(), hour = c.schedule.hour.trim(), dom = c.schedule.dom.trim(),
                month = c.schedule.month.trim(), dow = c.schedule.dow.trim(),
            )))
        }

        fun initScript(o: JsonObject): InitScript? = InitScript(
            id = o.long("id")?.toInt() ?: return null,
            type = runCatching { InitScriptType.valueOf(o.str("type")!!.uppercase()) }.getOrDefault(InitScriptType.COMMAND),
            command = o.str("command").orEmpty(),
            script = o.str("script").orEmpty(),
            whenRun = runCatching { InitScriptWhen.valueOf(o.str("when")!!.uppercase()) }.getOrDefault(InitScriptWhen.POSTINIT),
            enabled = o.bool("enabled") ?: true,
            timeout = o.long("timeout")?.toInt() ?: 10,
            comment = o.str("comment").orEmpty(),
        )

        /** Only the field that matches the type is filled; the other is sent empty, like the web UI. */
        fun initJson(s: InitScriptInput) = buildJsonObject {
            put("type", s.type.name)
            put("command", if (s.type == InitScriptType.COMMAND) s.command.trim() else "")
            put("script", if (s.type == InitScriptType.SCRIPT) s.script.trim() else "")
            put("when", s.whenRun.name)
            put("enabled", s.enabled)
            put("timeout", s.timeout)
            put("comment", s.comment.trim())
        }

        /** Local checks before saving; field -> message (fields: command, script, timeout, comment). */
        fun initErrors(s: InitScriptInput): Map<String, String> = buildMap {
            if (s.type == InitScriptType.COMMAND && s.command.isBlank()) put("command", "Enter a command")
            if (s.type == InitScriptType.SCRIPT) {
                when {
                    s.script.isBlank() -> put("script", "Pick or type the script's path")
                    !s.script.trim().startsWith("/") -> put("script", "Use a full path, e.g. /mnt/tank/scripts/start.sh")
                }
            }
            if (s.timeout !in 1..86_400) put("timeout", "1–86400 seconds")
            if (s.comment.length > 255) put("comment", "At most 255 characters")
        }

        /** Local checks of a cron job; field -> message (fields: command, user, schedule). */
        fun cronErrors(c: CronJobInput): Map<String, String> = buildMap {
            if (c.command.isBlank()) put("command", "Enter a command")
            if (c.user.isBlank()) put("user", "Pick a user")
            else if (' ' in c.user.trim()) put("user", "User names can't contain spaces")
            if (!app.truenascompanion.data.tasks.CronText.isValid(c.schedule)) put("schedule", "Fix the schedule")
            if (c.description.length > 200) put("description", "At most 200 characters")
        }
    }
}
