package app.truenascompanion.data.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/** A published release with an installable APK. */
data class ReleaseInfo(
    val version: String,
    val title: String,
    val notes: String,
    val pageUrl: String,
    val apkName: String,
    val apkUrl: String,
    val apkSize: Long?,
    /** Lowercase hex SHA-256 from GitHub's asset `digest` ("sha256:…"), or null if GitHub didn't provide one. */
    val sha256: String?,
    /** URL of a `<apk>.sha256` asset, used when [sha256] is missing. */
    val checksumUrl: String?,
    /** Channel this asset was selected for. */
    val channel: UpdateChannel = UpdateChannel.RELEASE,
)

sealed interface UpdateResult {
    data class Available(val release: ReleaseInfo) : UpdateResult
    data class UpToDate(val latest: String) : UpdateResult
    /** Updates can't be checked; [message] is shown to the user. */
    data class Unavailable(val message: String) : UpdateResult
}

/**
 * Reads the latest release of a **public** GitHub repository ([repo] = "owner/name") without any token.
 * Never embeds credentials: a private repository simply answers 404 and the app says updates can't be checked.
 *
 * Picks the APK asset for [UpdateChannel] — unified names
 * `truenas-companion-release.apk` / `truenas-companion-debug.apk` (preferred), with a fallback to the
 * older versioned filenames for releases published before 1.0.3.
 */
class UpdateChecker(
    private val repo: String,
    private val client: OkHttpClient = defaultClient,
    private val apiBase: String = "https://api.github.com",
) {
    suspend fun check(currentVersion: String, channel: UpdateChannel = UpdateChannel.defaultForBuild()): UpdateResult =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("$apiBase/repos/$repo/releases/latest")
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("User-Agent", "TrueNAS-Companion-Android")
                .build()
            try {
                client.newCall(request).execute().use { res ->
                    val body = res.body.string()
                    when {
                        res.code == 404 -> UpdateResult.Unavailable(
                            "Updates can't be checked: no public releases were found at github.com/$repo (the repository may be private)."
                        )
                        res.code == 403 || res.code == 429 -> UpdateResult.Unavailable(
                            if (res.header("x-ratelimit-remaining") == "0" || body.contains("rate limit", true))
                                "GitHub's hourly limit for update checks from this network was reached. Try again later."
                            else "GitHub refused the update check (HTTP ${res.code})."
                        )
                        !res.isSuccessful -> UpdateResult.Unavailable("Update check failed (HTTP ${res.code}).")
                        else -> {
                            val release = parseRelease(body, channel)
                                ?: return@use UpdateResult.Unavailable(
                                    "The latest release has no ${channel.label} APK (${channel.apkAssetName})."
                                )
                            if (compareVersions(release.version, currentVersion) > 0) UpdateResult.Available(release)
                            else UpdateResult.UpToDate(release.version)
                        }
                    }
                }
            } catch (e: IOException) {
                UpdateResult.Unavailable("Couldn't reach GitHub. Check your connection and try again.")
            }
        }

    companion object {
        val defaultClient: OkHttpClient by lazy {
            OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
        }

        private val json = Json { ignoreUnknownKeys = true }

        private fun JsonObject.s(k: String) = (this[k] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull

        /** Parses `GET /repos/{repo}/releases/latest`. Null if it isn't a usable release (draft, no matching APK). */
        fun parseRelease(body: String, channel: UpdateChannel = UpdateChannel.defaultForBuild()): ReleaseInfo? {
            val o = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
            if (o["draft"]?.jsonPrimitive?.contentOrNull == "true") return null
            val tag = o.s("tag_name") ?: return null
            val assets = (o["assets"] as? kotlinx.serialization.json.JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            val apk = pickApkAsset(assets, channel) ?: return null
            val apkName = apk.s("name")!!
            val checksum = assets.firstOrNull { it.s("name") == UpdateAssets.checksumName(apkName) }
            return ReleaseInfo(
                version = normalizeVersion(tag),
                title = o.s("name")?.takeIf { it.isNotBlank() } ?: tag,
                notes = o.s("body").orEmpty().trim(),
                pageUrl = o.s("html_url") ?: "",
                apkName = apkName,
                apkUrl = apk.s("browser_download_url") ?: return null,
                apkSize = (apk["size"] as? kotlinx.serialization.json.JsonPrimitive)?.longOrNull,
                sha256 = apk.s("digest")?.takeIf { it.startsWith("sha256:", true) }?.substringAfter(':')?.lowercase()
                    ?.takeIf { it.matches(Regex("[0-9a-f]{64}")) },
                checksumUrl = checksum?.s("browser_download_url"),
                channel = channel,
            )
        }

        /**
         * Prefer unified names (`truenas-companion-release.apk` / `…-debug.apk`).
         * Fall back to deprecated versioned names from pre-1.0.3 releases.
         */
        fun pickApkAsset(assets: List<JsonObject>, channel: UpdateChannel): JsonObject? {
            val preferred = channel.apkAssetName
            assets.firstOrNull { it.s("name") == preferred }?.let { return it }
            // Deprecated: truenas-companion-v1.0.2.apk / truenas-companion-v1.0.2-debug.apk
            return when (channel) {
                UpdateChannel.RELEASE -> assets.firstOrNull { obj ->
                    val n = obj.s("name") ?: return@firstOrNull false
                    n.endsWith(".apk", ignoreCase = true) &&
                        !n.endsWith(".apk.sha256", ignoreCase = true) &&
                        n.startsWith("truenas-companion", ignoreCase = true) &&
                        !n.contains("-debug", ignoreCase = true)
                }
                UpdateChannel.DEBUG -> assets.firstOrNull { obj ->
                    val n = obj.s("name") ?: return@firstOrNull false
                    n.endsWith(".apk", ignoreCase = true) &&
                        n.contains("-debug", ignoreCase = true) &&
                        !n.endsWith(".sha256", ignoreCase = true)
                }
            }
        }

        /** "v0.5.0" / "0.5.0-debug" -> "0.5.0". */
        fun normalizeVersion(v: String): String = v.trim().removePrefix("v").removePrefix("V").substringBefore('-').substringBefore('+')

        /** Numeric dotted comparison ("0.10.0" > "0.9.3"); missing parts count as 0. */
        fun compareVersions(a: String, b: String): Int {
            val pa = normalizeVersion(a).split('.').map { it.toIntOrNull() ?: 0 }
            val pb = normalizeVersion(b).split('.').map { it.toIntOrNull() ?: 0 }
            for (i in 0 until maxOf(pa.size, pb.size)) {
                val c = (pa.getOrElse(i) { 0 }).compareTo(pb.getOrElse(i) { 0 })
                if (c != 0) return c
            }
            return 0
        }

        /** Release notes are Markdown; this keeps them readable as plain text. */
        fun plainNotes(md: String): String = md.lines().joinToString("\n") { line ->
            line.replace(Regex("""^#{1,6}\s*"""), "")
                .replace(Regex("""\*\*(.+?)\*\*"""), "$1")
                .replace(Regex("""`([^`]+)`"""), "$1")
                .replace(Regex("""\[([^\]]+)]\([^)]+\)"""), "$1")
                .replace(Regex("""^\s*[-*]\s+"""), "• ")
        }.replace(Regex("\n{3,}"), "\n\n").trim()
            // The dialog has its own "What's new" title.
            .let { t -> if (t.lineSequence().firstOrNull()?.trim()?.matches(Regex("(?i)what'?s new:?")) == true) t.substringAfter('\n', "").trim() else t }
    }
}
