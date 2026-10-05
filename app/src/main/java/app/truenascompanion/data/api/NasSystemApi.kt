package app.truenascompanion.data.api

import app.truenascompanion.data.model.BootEnvironment
import app.truenascompanion.data.model.NasUpdateStatus
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * NAS system updates and boot environments (0.9.0). Matches TrueNAS SCALE 25.10 middleware /
 * web UI: `update.status`, `update.run` (job), `boot.environment.*`.
 */
class NasSystemApi(private val api: TrueNasApi) {

    /** `update.status` — no params. */
    suspend fun updateStatus(): NasUpdateStatus {
        val o = api.rpc("update.status").obj() ?: error("Empty update.status")
        val status = o["status"].obj()
        val current = status?.get("current_version")?.obj()
        val neu = status?.get("new_version")?.obj()
        val manifest = neu?.get("manifest")?.obj()
        val err = o["error"].obj()
        val progress = o["update_download_progress"].obj()
        return NasUpdateStatus(
            code = o.str("code") ?: "ERROR",
            currentTrain = current?.str("train"),
            currentProfile = current?.str("profile"),
            newVersion = neu?.str("version"),
            releaseNotes = neu?.str("release_notes"),
            releaseNotesUrl = neu?.str("release_notes_url"),
            changelog = manifest?.str("changelog"),
            errorReason = err?.str("reason"),
            downloadPercent = progress?.double("percent")?.toFloat(),
            downloadDescription = progress?.str("description"),
        )
    }

    /**
     * Starts `update.run` as a middleware job (download + apply). Returns the job id.
     * Web UI: `update.run([{ reboot: true }])`.
     */
    suspend fun startUpdate(reboot: Boolean = true): Long {
        val res = api.rpc("update.run", buildJsonObject { put("reboot", reboot) })
        return res.prim()?.takeUnless { it.isString }?.longOrNull
            ?: throw TrueNasException.JobFailed("update.run did not return a job id")
    }

    suspend fun bootEnvironments(): List<BootEnvironment> =
        api.rpc("boot.environment.query").arr()?.mapNotNull { it.obj()?.let(::parseBootEnv) }
            ?.sortedByDescending { it.createdMillis ?: 0L } ?: emptyList()

    suspend fun activateBootEnv(id: String) {
        api.rpc("boot.environment.activate", buildJsonObject { put("id", id) })
    }

    suspend fun cloneBootEnv(id: String, target: String) {
        api.rpc("boot.environment.clone", buildJsonObject { put("id", id); put("target", target) })
    }

    suspend fun destroyBootEnv(id: String) {
        api.rpc("boot.environment.destroy", buildJsonObject { put("id", id) })
    }

    suspend fun keepBootEnv(id: String, keep: Boolean) {
        api.rpc("boot.environment.keep", buildJsonObject { put("id", id); put("value", keep) })
    }

    companion object {
        fun parseBootEnv(o: kotlinx.serialization.json.JsonObject): BootEnvironment? {
            val id = o.str("id") ?: return null
            return BootEnvironment(
                id = id,
                dataset = o.str("dataset").orEmpty(),
                active = o.bool("active") ?: false,
                activated = o.bool("activated") ?: false,
                createdMillis = parseDate(o["created"]),
                usedBytes = o.long("used_bytes"),
                used = o.str("used"),
                keep = o.bool("keep") ?: false,
                canActivate = o.bool("can_activate") ?: false,
            )
        }
    }
}
