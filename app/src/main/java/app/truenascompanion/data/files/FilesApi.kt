package app.truenascompanion.data.files

import app.truenascompanion.data.api.TrueNasApi
import app.truenascompanion.data.api.TrueNasException
import app.truenascompanion.data.api.arr
import app.truenascompanion.data.api.bool
import app.truenascompanion.data.api.double
import app.truenascompanion.data.api.long
import app.truenascompanion.data.api.obj
import app.truenascompanion.data.api.prim
import app.truenascompanion.data.api.str
import app.truenascompanion.data.model.DownloadTicket
import app.truenascompanion.data.model.FileEntry
import app.truenascompanion.data.model.FilePage
import app.truenascompanion.data.model.FileSort
import app.truenascompanion.data.model.FileStat
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * File browser calls (1.3.0), all JSON-RPC over the shared WebSocket. Methods and arguments as in TrueNAS 25.10
 * (middlewared/plugins/filesystem.py, api/v25_10_2/filesystem.py, service/core_service.py):
 *
 * - `filesystem.listdir(path, query-filters, query-options)`: `select` avoids xattr / ZFS-attribute lookups,
 *   `limit` + `offset` page huge folders, `count` returns the number of matches. Ordering is done by
 *   middlewared's filter_list, which applies `order_by` keys one after another (stable), so the *last* key is primary:
 *   `[name, type]` lists folders first (DIRECTORY < FILE < OTHER < SYMLINK), each group by name.
 * - `filesystem.stat(path)`: owner and group names, timestamps (float seconds).
 * - `filesystem.mkdir({path, options: {mode}})`: only below /mnt.
 * - `core.download("filesystem.get", [path], filename, buffered=false)`: returns `[job_id, "/_download/<id>?…"]`;
 *   the job streams the file once that URL is fetched (within 60 s for unbuffered downloads).
 * - `auth.generate_token(300, {}, match_origin=true, single_use=true)`: one-time token for the `/_upload` request of
 *   the `filesystem.put` job (same as the web shell token).
 *
 * TrueNAS 25.10 has no API to rename, move or delete files (only `pool.dataset.*` for whole datasets), so the browser
 * offers none of those.
 */
class FilesApi(private val api: TrueNasApi) {

    suspend fun list(path: String, sort: FileSort, descending: Boolean, query: String, offset: Int, limit: Int = FilePolicy.PAGE_SIZE): FilePage {
        val filters = filters(query)
        val total = if (offset == 0) runCatching {
            api.rpc("filesystem.listdir", JsonPrimitive(path), filters, buildJsonObject { put("count", true) }).prim()?.longOrNull?.toInt()
        }.getOrNull() else null
        val key = when (sort) { FileSort.SIZE -> "size"; else -> "name" }
        val options = buildJsonObject {
            put("select", JsonArray(SELECT.map { JsonPrimitive(it) }))
            put("order_by", buildJsonArray { add(JsonPrimitive(if (descending && sort == FileSort.SIZE) "-$key" else key)); add(JsonPrimitive("type")) })
            put("offset", offset)
            put("limit", limit)
        }
        val entries = api.rpc("filesystem.listdir", JsonPrimitive(path), filters, options).arr().orEmpty()
            .mapNotNull { it.obj()?.let { o -> entry(o, path) } }
        return FilePage(entries, total, offset)
    }

    suspend fun stat(path: String): FileStat = stat(api.rpc("filesystem.stat", JsonPrimitive(path)).obj() ?: throw TrueNasException.Rpc(0, "ENOENT", "Path $path not found"), path)

    /** True if something exists at [path] (ENOENT → false). */
    suspend fun exists(path: String): Boolean = try {
        stat(path); true
    } catch (e: TrueNasException.Rpc) {
        if (e.errname == "ENOENT" || e.message.orEmpty().contains("not found", true)) false else throw e
    }

    /** Modification times for [entries] (a few `filesystem.stat` calls at a time on the same socket). */
    suspend fun mtimes(entries: List<FileEntry>, parallel: Int = 6): Map<String, Long> = coroutineScope {
        val gate = Semaphore(parallel)
        entries.map { e ->
            async { gate.withPermit { runCatching { e.path to stat(e.path).mtimeMillis }.getOrNull() } }
        }.awaitAll().mapNotNull { p -> p?.second?.let { p.first to it } }.toMap()
    }

    suspend fun mkdir(path: String): FileEntry? =
        api.rpc("filesystem.mkdir", buildJsonObject {
            put("path", path)
            put("options", buildJsonObject { put("mode", "755"); put("raise_chmod_error", false) })
        }).obj()?.let { entry(it, path.substringBeforeLast('/')) }

    /** Starts the streaming `filesystem.get` job and returns its one-time `/_download/...` URL (path relative to the server). */
    suspend fun startDownload(path: String, filename: String): DownloadTicket {
        val res = api.rpc("core.download", JsonPrimitive("filesystem.get"), JsonArray(listOf(JsonPrimitive(path))), JsonPrimitive(filename), JsonPrimitive(false)).arr()
        val id = res?.getOrNull(0)?.prim()?.longOrNull
        val url = res?.getOrNull(1)?.prim()?.contentOrNull
        if (id == null || url == null || !url.startsWith("/_download/")) throw TrueNasException.Rpc(0, null, "TrueNAS did not return a download link.")
        return DownloadTicket(id, url)
    }

    /** One-time token (5 min, bound to this address) for the `/_upload` request. */
    suspend fun uploadToken(): String =
        api.rpc("auth.generate_token", JsonPrimitive(300), JsonObject(emptyMap()), JsonPrimitive(true), JsonPrimitive(true))
            .prim()?.contentOrNull?.takeIf { it.isNotBlank() } ?: throw TrueNasException.Unsupported("TrueNAS did not issue an upload token.")

    suspend fun abortJob(id: Long) {
        runCatching { api.rpc("core.job_abort", JsonPrimitive(id)) }
    }

    companion object {
        val SELECT = listOf("name", "path", "realpath", "type", "size", "mode", "uid", "gid", "acl", "is_mountpoint")

        /** Search: case-insensitive "contains" (`Crin`), like the web UI's filter boxes. */
        fun filters(query: String): JsonArray = if (query.isBlank()) JsonArray(emptyList()) else buildJsonArray {
            add(buildJsonArray { add(JsonPrimitive("name")); add(JsonPrimitive("Crin")); add(JsonPrimitive(query.trim())) })
        }

        fun entry(o: JsonObject, dir: String): FileEntry? {
            val name = o.str("name") ?: return null
            return FileEntry(
                name = name,
                path = o.str("path") ?: FilePolicy.child(dir, name),
                type = o.str("type") ?: "OTHER",
                size = o.long("size") ?: 0,
                mode = o.long("mode")?.toInt(),
                uid = o.long("uid")?.toInt(),
                gid = o.long("gid")?.toInt(),
                acl = o.bool("acl") ?: false,
                isMountpoint = o.bool("is_mountpoint") ?: false,
                realpath = o.str("realpath"),
            )
        }

        private fun secs(o: JsonObject, key: String): Long? = o.double(key)?.takeIf { it > 0 }?.let { (it * 1000).toLong() }

        fun stat(o: JsonObject, path: String) = FileStat(
            path = path,
            realpath = o.str("realpath") ?: path,
            type = o.str("type") ?: "OTHER",
            size = o.long("size") ?: 0,
            mode = o.long("mode")?.toInt() ?: 0,
            uid = o.long("uid")?.toInt() ?: -1,
            gid = o.long("gid")?.toInt() ?: -1,
            user = o.str("user"),
            group = o.str("group"),
            atimeMillis = secs(o, "atime"),
            mtimeMillis = secs(o, "mtime"),
            ctimeMillis = secs(o, "ctime"),
            btimeMillis = secs(o, "btime"),
            acl = o.bool("acl") ?: false,
            isMountpoint = o.bool("is_mountpoint") ?: false,
            nlink = o.long("nlink"),
        )
    }
}
