package app.truenascompanion.data.files

import app.truenascompanion.data.api.TrueNasException
import app.truenascompanion.data.model.FileEntry
import app.truenascompanion.data.model.FileKind
import app.truenascompanion.data.model.FileSort
import java.util.Locale

/**
 * Pure rules of the file browser (1.3.0): which paths may be shown, sorting, names, file kinds and friendly errors.
 * The browser only ever shows pool datasets under `/mnt/<pool>`; TrueNAS system folders stay hidden unless the user
 * turns on "Show system folders".
 */
object FilePolicy {
    /** Deletes NAS files copied to the cache for "Open with" (1.8.0, security review: don't keep NAS data around). */
    fun clearOpenWithCache(context: android.content.Context) {
        runCatching { java.io.File(context.cacheDir, "files").listFiles()?.forEach { it.deleteRecursively() } }
    }

    const val ROOT = "/mnt"

    /**
     * Folders TrueNAS uses for itself: the apps dataset (`.ix-apps`, 24.10+; `ix-applications` before), the system
     * dataset (`.system`), Incus storage (`.ix-virt`) and the ZFS snapshot directory (`.zfs`).
     */
    val SYSTEM_NAMES = setOf(".system", ".ix-apps", "ix-apps", "ix-applications", ".ix-virt", ".zfs")

    /** Largest text file shown in the quick preview. */
    const val TEXT_PREVIEW_MAX = 512L * 1024
    /** Largest image shown in the quick preview. */
    const val IMAGE_PREVIEW_MAX = 20L * 1024 * 1024
    /** Largest file "Open with" downloads into the app's cache before handing it to another app. */
    const val OPEN_WITH_MAX = 1024L * 1024 * 1024
    /** From this size a transfer over the remote address shows the reverse-proxy hint. */
    const val PROXY_HINT_MIN = 100L * 1024 * 1024
    /** Entries per `filesystem.listdir` page. */
    const val PAGE_SIZE = 500

    /** `/mnt//tank/a/` → `/mnt/tank/a`; null for relative paths or `.` / `..` segments. */
    fun normalize(path: String): String? {
        if (!path.startsWith("/")) return null
        val parts = path.split('/').filter { it.isNotEmpty() }
        if (parts.any { it == "." || it == ".." || it.contains('\u0000') }) return null
        return "/" + parts.joinToString("/")
    }

    /**
     * True when [path] may be browsed: `/mnt` itself (the pool list) or anything inside `/mnt/<pool>` for a known pool
     * ([pools] empty = not known yet, any first segment). System folders anywhere in the path need [showSystem].
     */
    fun isAllowed(path: String, pools: Set<String>, showSystem: Boolean): Boolean {
        val p = normalize(path) ?: return false
        if (p == ROOT) return true
        if (!p.startsWith("$ROOT/")) return false
        val segments = p.removePrefix("$ROOT/").split('/')
        val pool = segments.first()
        if (pools.isNotEmpty() && pool !in pools) return false
        if (!showSystem && segments.any { isSystemName(it) }) return false
        return true
    }

    fun isSystemName(name: String) = name in SYSTEM_NAMES

    /** Hidden in the list unless "Show system folders" is on: system folders and dot files. */
    fun isVisible(entry: FileEntry, showSystem: Boolean): Boolean =
        showSystem || !(isSystemName(entry.name) || entry.name.startsWith("."))

    fun parent(path: String): String? {
        val p = normalize(path) ?: return null
        if (p == ROOT || p == "/") return null
        return p.substringBeforeLast('/').ifEmpty { "/" }.takeIf { it.startsWith(ROOT) }
    }

    fun child(dir: String, name: String): String = (normalize(dir) ?: dir).trimEnd('/') + "/" + name

    /** Breadcrumb: "Pools" (/mnt), then one crumb per folder. */
    fun breadcrumbs(path: String): List<Pair<String, String>> {
        val p = normalize(path) ?: return listOf("Pools" to ROOT)
        val out = mutableListOf("Pools" to ROOT)
        if (!p.startsWith("$ROOT/")) return out
        var cur = ROOT
        p.removePrefix("$ROOT/").split('/').forEach { seg -> cur = "$cur/$seg"; out += seg to cur }
        return out
    }

    /** Null when [name] is fine for a new folder or uploaded file, else the reason. */
    fun nameProblem(name: String): String? = when {
        name.isBlank() -> "Enter a name."
        name == "." || name == ".." -> "That name isn't allowed."
        name.contains('/') -> "Names can't contain /."
        name.contains('\u0000') -> "That name isn't allowed."
        name.toByteArray(Charsets.UTF_8).size > 255 -> "That name is too long."
        name != name.trim() -> "Names can't start or end with a space."
        else -> null
    }

    /** Folders first, then by [sort]; names compare case-insensitively with numbers in natural order. */
    fun sorted(entries: List<FileEntry>, sort: FileSort, descending: Boolean): List<FileEntry> {
        val byName = Comparator<FileEntry> { a, b -> naturalCompare(a.name, b.name) }
        val inner: Comparator<FileEntry> = when (sort) {
            FileSort.NAME -> byName
            FileSort.SIZE -> compareBy<FileEntry> { it.size }.then(byName)
            FileSort.DATE -> compareBy<FileEntry, Long?>(nullsFirst()) { it.mtimeMillis }.then(byName)
        }
        val ordered = if (descending) inner.reversed() else inner
        return entries.sortedWith(compareBy<FileEntry> { if (it.isDirectory) 0 else 1 }.then(ordered))
    }

    /** "file2" before "file10", case-insensitive. */
    fun naturalCompare(a: String, b: String): Int {
        var i = 0; var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]; val cb = b[j]
            if (ca.isDigit() && cb.isDigit()) {
                var ei = i; while (ei < a.length && a[ei].isDigit()) ei++
                var ej = j; while (ej < b.length && b[ej].isDigit()) ej++
                val na = a.substring(i, ei).trimStart('0'); val nb = b.substring(j, ej).trimStart('0')
                if (na.length != nb.length) return na.length - nb.length
                val c = na.compareTo(nb)
                if (c != 0) return c
                i = ei; j = ej
            } else {
                val c = ca.lowercaseChar().compareTo(cb.lowercaseChar())
                if (c != 0) return c
                i++; j++
            }
        }
        return (a.length - i) - (b.length - j)
    }

    /** Case-insensitive "contains" filter for the current folder. */
    fun matches(entry: FileEntry, query: String): Boolean = query.isBlank() || entry.name.contains(query.trim(), ignoreCase = true)

    fun extension(name: String): String = name.substringAfterLast('.', "").lowercase(Locale.ROOT).takeIf { it != name.lowercase(Locale.ROOT) } ?: ""

    private val IMAGE = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "avif", "svg", "tif", "tiff", "raw", "cr2", "nef", "arw", "dng")
    private val VIDEO = setOf("mp4", "mkv", "mov", "avi", "webm", "m4v", "wmv", "flv", "ts", "m2ts", "mpg", "mpeg", "3gp")
    private val AUDIO = setOf("mp3", "flac", "wav", "aac", "m4a", "ogg", "opus", "wma", "alac", "aiff")
    private val TEXT = setOf("txt", "log", "md", "csv", "tsv", "ini", "conf", "cfg", "nfo", "srt", "vtt", "env", "properties", "toml")
    private val CODE = setOf("json", "xml", "yaml", "yml", "sh", "py", "js", "ts", "kt", "java", "c", "h", "cpp", "go", "rs", "rb", "php", "html", "htm", "css", "sql", "ps1", "bat")
    private val ARCHIVE = setOf("zip", "tar", "gz", "tgz", "bz2", "xz", "zst", "7z", "rar", "lz4")
    private val DOCUMENT = setOf("doc", "docx", "odt", "rtf", "pages", "epub")
    private val SHEET = setOf("xls", "xlsx", "ods", "numbers")
    private val SLIDES = setOf("ppt", "pptx", "odp", "key")
    private val DISK = setOf("iso", "img", "qcow2", "vmdk", "vdi", "vhd", "vhdx", "raw")

    fun kindOf(entry: FileEntry): FileKind = when {
        entry.isDirectory -> FileKind.FOLDER
        entry.isSymlink -> FileKind.LINK
        else -> kindOfName(entry.name)
    }

    fun kindOfName(name: String): FileKind = when (val ext = extension(name)) {
        in IMAGE -> FileKind.IMAGE
        in VIDEO -> FileKind.VIDEO
        in AUDIO -> FileKind.AUDIO
        "pdf" -> FileKind.PDF
        in TEXT -> FileKind.TEXT
        in CODE -> FileKind.CODE
        in ARCHIVE -> FileKind.ARCHIVE
        in DOCUMENT -> FileKind.DOCUMENT
        in SHEET -> FileKind.SPREADSHEET
        in SLIDES -> FileKind.PRESENTATION
        in DISK -> FileKind.DISK_IMAGE
        else -> if (ext.isEmpty() && name.isNotEmpty()) FileKind.OTHER else FileKind.OTHER
    }

    /** MIME type for SAF / ACTION_VIEW (falls back to octet-stream). */
    fun mimeOf(name: String): String = when (val ext = extension(name)) {
        "jpg", "jpeg" -> "image/jpeg"; "png" -> "image/png"; "gif" -> "image/gif"; "webp" -> "image/webp"; "bmp" -> "image/bmp"
        "heic" -> "image/heic"; "heif" -> "image/heif"; "avif" -> "image/avif"; "svg" -> "image/svg+xml"; "tif", "tiff" -> "image/tiff"
        "mp4", "m4v" -> "video/mp4"; "mkv" -> "video/x-matroska"; "mov" -> "video/quicktime"; "webm" -> "video/webm"; "avi" -> "video/x-msvideo"
        "mp3" -> "audio/mpeg"; "flac" -> "audio/flac"; "wav" -> "audio/wav"; "m4a", "aac" -> "audio/aac"; "ogg", "opus" -> "audio/ogg"
        "pdf" -> "application/pdf"; "zip" -> "application/zip"; "gz", "tgz" -> "application/gzip"; "7z" -> "application/x-7z-compressed"
        "tar" -> "application/x-tar"; "json" -> "application/json"; "xml" -> "text/xml"; "html", "htm" -> "text/html"; "csv" -> "text/csv"
        "md" -> "text/markdown"; "doc" -> "application/msword"; "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "xls" -> "application/vnd.ms-excel"; "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        "ppt" -> "application/vnd.ms-powerpoint"; "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        "odt" -> "application/vnd.oasis.opendocument.text"; "ods" -> "application/vnd.oasis.opendocument.spreadsheet"
        "epub" -> "application/epub+zip"; "iso" -> "application/x-iso9660-image"
        else -> if (ext in TEXT || ext in CODE) "text/plain" else "application/octet-stream"
    }

    enum class Preview { TEXT, IMAGE }

    /** Quick in-app preview for small text files and images; null when the file is too big or not previewable. */
    fun previewKind(entry: FileEntry): Preview? {
        if (!entry.isFile) return null
        return when (kindOf(entry)) {
            FileKind.TEXT, FileKind.CODE -> Preview.TEXT.takeIf { entry.size <= TEXT_PREVIEW_MAX }
            FileKind.IMAGE -> Preview.IMAGE.takeIf { entry.size <= IMAGE_PREVIEW_MAX && extension(entry.name) in PREVIEW_IMAGES }
            else -> null
        }
    }

    private val PREVIEW_IMAGES = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "avif")

    /** `0o40755` → `drwxr-xr-x`. */
    fun permissions(mode: Int?, type: String? = null): String {
        if (mode == null) return "—"
        val t = when {
            type == "DIRECTORY" || (mode and 0xF000) == 0x4000 -> 'd'
            type == "SYMLINK" || (mode and 0xF000) == 0xA000 -> 'l'
            else -> '-'
        }
        val bits = "rwxrwxrwx"
        val sb = StringBuilder().append(t)
        for (i in 0 until 9) sb.append(if (mode and (1 shl (8 - i)) != 0) bits[i] else '-')
        if (mode and 0x800 != 0) sb.setCharAt(3, if (sb[3] == 'x') 's' else 'S')
        if (mode and 0x400 != 0) sb.setCharAt(6, if (sb[6] == 'x') 's' else 'S')
        if (mode and 0x200 != 0) sb.setCharAt(9, if (sb[9] == 'x') 't' else 'T')
        return sb.toString()
    }

    /** `0o755` style octal of the permission bits. */
    fun octal(mode: Int?): String = mode?.let { "%04o".format(it and 0xFFF) } ?: "—"

    /** Friendly text for listing / transfer errors (permission denied, path not found …). */
    fun friendlyError(e: Throwable): String = when {
        e is TrueNasException.Forbidden -> "Permission denied. Your TrueNAS account isn't allowed to do this (file access needs a full admin role)."
        e is TrueNasException.Rpc && e.errname == "ENOENT" -> "This file or folder no longer exists."
        e is TrueNasException.Rpc && e.errname == "ENOTDIR" -> "This isn't a folder."
        e is TrueNasException.Rpc && e.errname == "EEXIST" -> "Something with that name already exists here."
        e is TrueNasException.Rpc && (e.errname == "EACCES" || e.errname == "EPERM") -> "Permission denied."
        e is TrueNasException.Rpc && e.errname == "EXDEV" -> "That path is outside your pools."
        e is TrueNasException.Rpc && e.message.orEmpty().contains("locked dataset", true) -> "This dataset is locked. Unlock it in TrueNAS first."
        e is TrueNasException.Rpc && e.message.orEmpty().contains("does not exist", true) -> "This file or folder no longer exists."
        e is TrueNasException.Rpc && e.message.orEmpty().contains("not found", true) -> "This file or folder no longer exists."
        e is TrueNasException.Rpc && e.message.orEmpty().contains("not permitted", true) -> "TrueNAS doesn't allow that here."
        e is TrueNasException.Http && (e.code == 401 || e.code == 403) -> "TrueNAS refused the transfer (the one-time link expired or isn't allowed). Try again."
        e is TrueNasException.Http && e.code == 413 -> "The file is too big for a reverse proxy between you and TrueNAS. Try at home or over the VPN."
        e is TrueNasException.Http && e.code == 410 -> "The download link expired. Try again."
        e is TrueNasException -> e.message ?: "Something went wrong."
        else -> e.message ?: e.javaClass.simpleName
    }
}
