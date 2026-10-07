package app.truenascompanion.data.model

/**
 * File browser (1.3.0). Shapes follow TrueNAS 25.10 `filesystem.listdir` (FilesystemDirEntry) and `filesystem.stat`
 * (FilesystemStatData), middlewared/api/v25_10_2/filesystem.py. `listdir` has no timestamps, so [mtimeMillis] is filled
 * in later from `filesystem.stat`.
 */
data class FileEntry(
    val name: String,
    val path: String,
    /** DIRECTORY | FILE | SYMLINK | OTHER */
    val type: String,
    val size: Long,
    val mode: Int? = null,
    val uid: Int? = null,
    val gid: Int? = null,
    val acl: Boolean = false,
    val isMountpoint: Boolean = false,
    val realpath: String? = null,
    val mtimeMillis: Long? = null,
) {
    val isDirectory: Boolean get() = type == "DIRECTORY"
    val isSymlink: Boolean get() = type == "SYMLINK"
    val isFile: Boolean get() = type == "FILE"
}

data class FileStat(
    val path: String,
    val realpath: String,
    val type: String,
    val size: Long,
    val mode: Int,
    val uid: Int,
    val gid: Int,
    val user: String?,
    val group: String?,
    val atimeMillis: Long?,
    val mtimeMillis: Long?,
    val ctimeMillis: Long?,
    val btimeMillis: Long?,
    val acl: Boolean,
    val isMountpoint: Boolean,
    val nlink: Long?,
)

enum class FileSort(val label: String) { NAME("Name"), SIZE("Size"), DATE("Modified") }

enum class FileKind { FOLDER, IMAGE, VIDEO, AUDIO, TEXT, CODE, ARCHIVE, PDF, DOCUMENT, SPREADSHEET, PRESENTATION, DISK_IMAGE, LINK, OTHER }

/** One page of a folder listing. [total] is the folder's entry count (after the search filter) when known. */
data class FilePage(val entries: List<FileEntry>, val total: Int?, val offset: Int)

/** `core.download` answer: the job that streams the file and the one-time `/_download/...` URL for it. */
data class DownloadTicket(val jobId: Long, val url: String)
