package app.truenascompanion.ui.files

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.truenascompanion.AppContainer
import app.truenascompanion.data.api.awaitJob
import app.truenascompanion.data.files.FilePolicy
import app.truenascompanion.data.files.FileTransfers
import app.truenascompanion.data.files.FilesApi
import app.truenascompanion.data.files.SaveTarget
import app.truenascompanion.data.files.SaveTargets
import app.truenascompanion.data.model.FileEntry
import app.truenascompanion.data.model.FileSort
import app.truenascompanion.data.model.FileStat
import app.truenascompanion.data.model.Route
import app.truenascompanion.data.net.HttpClients
import app.truenascompanion.data.net.Keepalive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream

enum class TransferKind(val verb: String) { DOWNLOAD("Downloading"), UPLOAD("Uploading"), OPEN("Opening"), PREVIEW("Loading preview") }

data class TransferState(
    val kind: TransferKind,
    val name: String,
    val done: Long,
    val total: Long,
    /** Upload sent, TrueNAS is still writing the file (filesystem.put job). */
    val finishing: Boolean = false,
    val proxyHint: Boolean = false,
    /** Uploads: the file's path on the NAS. */
    val target: String? = null,
    /** Uploads: the body is streaming, so TrueNAS has already opened (created or truncated) [target]. */
    val started: Boolean = false,
    /** Uploads: [target] already existed and is being overwritten. */
    val replacing: Boolean = false,
) {
    /** Stopping now leaves an incomplete file on the NAS (TrueNAS 25.10 has no API to delete it). */
    val cancelLeavesPartial: Boolean get() = kind == TransferKind.UPLOAD && started
    val fraction: Float? get() = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else null
}

sealed interface FilePreview {
    val entry: FileEntry
    data class Text(override val entry: FileEntry, val text: String, val truncated: Boolean) : FilePreview
    data class Image(override val entry: FileEntry, val bitmap: ImageBitmap) : FilePreview
}

/** A file picked for upload that already exists on the NAS: ask before overwriting. */
data class PendingUpload(val uri: Uri, val name: String, val size: Long, val target: String, val exists: Boolean)

data class FileBrowserUi(
    val path: String = FilePolicy.ROOT,
    val entries: List<FileEntry> = emptyList(),
    val total: Int? = null,
    val loading: Boolean = true,
    val loadingMore: Boolean = false,
    val error: String? = null,
    val sort: FileSort = FileSort.NAME,
    val descending: Boolean = false,
    val query: String = "",
    val showSystem: Boolean = false,
    val mtimes: Map<String, Long> = emptyMap(),
    val transfer: TransferState? = null,
    val preview: FilePreview? = null,
    val details: FileEntry? = null,
    val detailsStat: FileStat? = null,
    val detailsError: String? = null,
    val pendingUpload: PendingUpload? = null,
    val route: Route? = null,
    val pools: List<String> = emptyList(),
    /** NAS paths of uploads that were stopped or failed midway in this session (the file there is incomplete). */
    val incomplete: Set<String> = emptySet(),
) {
    val atRoot: Boolean get() = path == FilePolicy.ROOT
    val hasMore: Boolean get() = total != null && entries.size < total
    val visible: List<FileEntry>
        get() = FilePolicy.sorted(
            entries.filter { FilePolicy.isVisible(it, showSystem) && FilePolicy.matches(it, query) }
                .map { e -> mtimes[e.path]?.let { e.copy(mtimeMillis = it) } ?: e },
            sort, descending,
        )
}

sealed interface FileEvent {
    data class Message(val text: String) : FileEvent
    data class OpenWith(val uri: Uri, val mime: String, val name: String) : FileEvent
    /** A download finished; the snackbar offers to open it. */
    data class Saved(val text: String, val uri: Uri, val mime: String, val name: String) : FileEvent
    /** Ask the user where to save [suggestedName] (system "Save as" dialog), optionally starting in Downloads. */
    data class PickSaveLocation(val suggestedName: String, val inDownloads: Boolean) : FileEvent
}

/**
 * File browser state (1.3.0). Listing, stat, mkdir and job control go over the shared WebSocket
 * ([TrueNasRepository.call]); the file bytes go over HTTP to TrueNAS's `/_download` and `/_upload` endpoints through
 * [TrueNasRepository.withEndpoint], i.e. the same route and certificate pin as the WebSocket.
 */
class FileBrowserViewModel(private val c: AppContainer, initialPath: String) : ViewModel() {
    private val _ui = MutableStateFlow(FileBrowserUi(path = FilePolicy.normalize(initialPath) ?: FilePolicy.ROOT))
    val ui: StateFlow<FileBrowserUi> = _ui.asStateFlow()
    private val events = Channel<FileEvent>(Channel.BUFFERED)
    val eventFlow = events.receiveAsFlow()

    private var loadJob: Job? = null
    private var mtimeJob: Job? = null
    private var searchJob: Job? = null
    private var transferJob: Job? = null
    /** Search text sent to TrueNAS (only used when the folder has more entries than one page). */
    private var serverQuery = ""

    init {
        viewModelScope.launch {
            val show = c.settings.filesShowSystem.first()
            _ui.update { it.copy(showSystem = show) }
            load(reset = true)
        }
        viewModelScope.launch { c.repository.route.collect { r -> _ui.update { it.copy(route = r) } } }
    }

    private fun msg(text: String) { events.trySend(FileEvent.Message(text)) }

    fun open(path: String) {
        val p = FilePolicy.normalize(path) ?: return
        if (!FilePolicy.isAllowed(p, _ui.value.pools.toSet(), _ui.value.showSystem)) { msg("That folder isn't available here."); return }
        serverQuery = ""
        _ui.update { it.copy(path = p, entries = emptyList(), total = null, query = "", error = null, mtimes = emptyMap()) }
        load(reset = true)
    }

    fun up(): Boolean {
        val parent = FilePolicy.parent(_ui.value.path) ?: return false
        open(parent)
        return true
    }

    fun refresh() = load(reset = true)

    fun setSort(sort: FileSort) {
        val cur = _ui.value
        val descending = if (cur.sort == sort) !cur.descending else (sort != FileSort.NAME)
        _ui.update { it.copy(sort = sort, descending = descending) }
        // Big folders are paged: the server orders by name / size so the first page is the right one.
        if (cur.hasMore && sort != FileSort.DATE) load(reset = true)
        if (sort == FileSort.DATE) fetchMtimes(all = true)
    }

    fun setQuery(q: String) {
        _ui.update { it.copy(query = q) }
        searchJob?.cancel()
        val needsServer = _ui.value.hasMore || serverQuery.isNotEmpty()
        if (!needsServer) return
        searchJob = viewModelScope.launch {
            delay(400)
            serverQuery = q.trim()
            load(reset = true)
        }
    }

    fun setShowSystem(show: Boolean) {
        _ui.update { it.copy(showSystem = show) }
        viewModelScope.launch { c.settings.setFilesShowSystem(show) }
        if (!show && !FilePolicy.isAllowed(_ui.value.path, emptySet(), false)) open(FilePolicy.ROOT)
    }

    fun loadMore() {
        val s = _ui.value
        if (s.loadingMore || s.loading || !s.hasMore) return
        load(reset = false)
    }

    private fun load(reset: Boolean) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val s = _ui.value
            if (reset) _ui.update { it.copy(loading = it.entries.isEmpty(), error = null) } else _ui.update { it.copy(loadingMore = true) }
            try {
                if (s.atRoot) {
                    val pools = c.repository.call { it.pools() }.map { it.name }.sorted()
                    val entries = pools.map { FileEntry(it, "${FilePolicy.ROOT}/$it", "DIRECTORY", 0, isMountpoint = true) }
                    _ui.update { it.copy(entries = entries, total = entries.size, loading = false, loadingMore = false, pools = pools) }
                    return@launch
                }
                if (s.pools.isEmpty()) {
                    val pools = runCatching { c.repository.call { it.pools() }.map { p -> p.name } }.getOrDefault(emptyList())
                    _ui.update { it.copy(pools = pools) }
                    if (pools.isNotEmpty() && !FilePolicy.isAllowed(s.path, pools.toSet(), s.showSystem)) {
                        _ui.update { it.copy(loading = false, error = "This folder isn't inside a pool.") }
                        return@launch
                    }
                }
                val offset = if (reset) 0 else s.entries.size
                val page = c.repository.call { FilesApi(it).list(s.path, s.sort, s.descending, serverQuery, offset) }
                _ui.update { cur ->
                    val merged = if (reset) page.entries else (cur.entries + page.entries).distinctBy { it.path }
                    cur.copy(
                        entries = merged,
                        total = page.total ?: if (page.entries.size < FilePolicy.PAGE_SIZE) merged.size else cur.total ?: (merged.size + 1),
                        loading = false, loadingMore = false, error = null,
                    )
                }
                fetchMtimes(all = _ui.value.sort == FileSort.DATE)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _ui.update { it.copy(loading = false, loadingMore = false, error = FilePolicy.friendlyError(e)) }
            }
        }
    }

    /** `listdir` has no timestamps: fetch modification times with `filesystem.stat` (a few at a time). */
    private fun fetchMtimes(all: Boolean) {
        val s = _ui.value
        if (s.atRoot) return
        val missing = s.entries.filter { it.path !in s.mtimes }.let { if (all) it else it.take(AUTO_MTIME_LIMIT) }
        if (missing.isEmpty()) return
        mtimeJob?.cancel()
        mtimeJob = viewModelScope.launch {
            missing.chunked(60).forEach { chunk ->
                val got = runCatching { c.repository.call { FilesApi(it).mtimes(chunk) } }.getOrDefault(emptyMap())
                _ui.update { it.copy(mtimes = it.mtimes + got) }
            }
        }
    }

    // --- details sheet ---

    fun showDetails(entry: FileEntry) {
        _ui.update { it.copy(details = entry, detailsStat = null, detailsError = null) }
        viewModelScope.launch {
            try {
                val st = c.repository.call { FilesApi(it).stat(entry.path) }
                _ui.update { if (it.details?.path == entry.path) it.copy(detailsStat = st) else it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _ui.update { if (it.details?.path == entry.path) it.copy(detailsError = FilePolicy.friendlyError(e)) else it }
            }
        }
    }

    fun hideDetails() = _ui.update { it.copy(details = null, detailsStat = null, detailsError = null) }

    /** Symlinks: follow only when the target stays inside the same pool. */
    fun openLink(entry: FileEntry) {
        viewModelScope.launch {
            try {
                val st = c.repository.call { FilesApi(it).stat(entry.path) }
                val pool = entry.path.removePrefix("${FilePolicy.ROOT}/").substringBefore('/')
                val real = st.realpath
                val inside = real == "${FilePolicy.ROOT}/$pool" || real.startsWith("${FilePolicy.ROOT}/$pool/")
                when {
                    !inside -> msg("This link points outside the pool, so it can't be opened here.")
                    st.type == "DIRECTORY" -> open(real)
                    else -> showDetails(entry.copy(type = st.type, size = st.size, realpath = real))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                msg(FilePolicy.friendlyError(e))
            }
        }
    }

    // --- mkdir ---

    fun mkdir(name: String) {
        val s = _ui.value
        FilePolicy.nameProblem(name)?.let { msg(it); return }
        if (s.atRoot) { msg("Open a pool first."); return }
        viewModelScope.launch {
            try {
                c.repository.call { FilesApi(it).mkdir(FilePolicy.child(s.path, name)) }
                msg("Folder \"$name\" created")
                load(reset = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                msg(FilePolicy.friendlyError(e))
            }
        }
    }

    // --- transfers ---

    private fun hint(size: Long) = size >= FilePolicy.PROXY_HINT_MIN && (_ui.value.route == null || _ui.value.route == Route.REMOTE)

    fun cancelTransfer() {
        transferJob?.cancel()
    }

    /** Streams [entry] from `/_download` into [out]. Runs inside [TrueNasRepository.withEndpoint]. */
    private suspend fun fetch(entry: FileEntry, out: OutputStream): Long =
        c.repository.withEndpoint { api, target ->
            val files = FilesApi(api)
            val ticket = files.startDownload(entry.realpath?.takeIf { entry.isSymlink } ?: entry.path, entry.name)
            try {
                val (client, _) = HttpClients.create(target, Keepalive.NONE)
                FileTransfers(client, target.url).download(ticket.url, out, entry.size) { done, total ->
                    _ui.update { it.copy(transfer = it.transfer?.copy(done = done, total = total)) }
                }
            } catch (e: Throwable) {
                withContext(NonCancellable) { files.abortJob(ticket.jobId) }
                throw e
            }
        }

    /** File waiting for the "Save as" dialog's answer (kept here so it survives rotation while the dialog is open). */
    private var pendingSave: FileEntry? = null

    /**
     * "Save to Downloads": Android 10+ writes straight into the shared Downloads collection (MediaStore, no permission);
     * Android 8-9 opens the "Save as" dialog in the Downloads folder instead.
     */
    fun saveToDownloads(entry: FileEntry) {
        if (transferJob?.isActive == true) { msg("Another transfer is running."); return }
        // Same rule as SaveTargets.mediaStoreDownloads(), spelled out so lint sees the API check.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) { askSaveLocation(entry, inDownloads = true); return }
        val resolver = c.context.contentResolver
        viewModelScope.launch {
            val target = withContext(Dispatchers.IO) { runCatching { SaveTargets.createDownload(resolver, entry.name) }.getOrNull() }
            if (target == null) { msg("Couldn't create the file in Downloads. Try \"Save as…\" instead."); return@launch }
            download(entry, target)
        }
    }

    /** "Save as…": the user picks the folder and name in the system dialog. */
    fun saveAs(entry: FileEntry) {
        if (transferJob?.isActive == true) { msg("Another transfer is running."); return }
        askSaveLocation(entry, inDownloads = false)
    }

    private fun askSaveLocation(entry: FileEntry, inDownloads: Boolean) {
        pendingSave = entry
        events.trySend(FileEvent.PickSaveLocation(entry.name, inDownloads))
    }

    /** Answer from the "Save as" dialog (null = cancelled). */
    fun onSaveLocation(uri: Uri?) {
        val e = pendingSave ?: return
        pendingSave = null
        if (uri != null) download(e, SaveTarget.Document(uri))
    }

    fun download(entry: FileEntry, target: SaveTarget) {
        if (transferJob?.isActive == true) {
            msg("Another transfer is running.")
            if (target is SaveTarget.Downloads) viewModelScope.launch(Dispatchers.IO) { SaveTargets.discard(c.context.contentResolver, target) }
            return
        }
        val ctx = c.context
        val resolver = ctx.contentResolver
        transferJob = viewModelScope.launch {
            _ui.update { it.copy(transfer = TransferState(TransferKind.DOWNLOAD, entry.name, 0, entry.size, proxyHint = hint(entry.size))) }
            var ok = false
            try {
                withContext(Dispatchers.IO) {
                    val out = resolver.openOutputStream(target.uri, "wt") ?: throw IllegalStateException("Can't write to the chosen file.")
                    out.use { fetch(entry, it) }
                    if (target is SaveTarget.Downloads && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) SaveTargets.publish(resolver, target)
                }
                ok = true
                val name = withContext(Dispatchers.IO) { SaveTargets.displayName(resolver, target.uri) } ?: entry.name
                val text = if (target is SaveTarget.Downloads) "Saved to Downloads as $name" else "Saved $name"
                events.trySend(FileEvent.Saved(text, target.uri, FilePolicy.mimeOf(name), name))
            } catch (e: CancellationException) {
                msg("Download cancelled")
            } catch (e: Throwable) {
                msg("Download failed: ${FilePolicy.friendlyError(e)}")
            } finally {
                // Never leave a half-written copy on the phone.
                if (!ok) withContext(NonCancellable + Dispatchers.IO) { SaveTargets.discard(resolver, target) }
                _ui.update { it.copy(transfer = null) }
            }
        }
    }

    /** "Open with": download into the app's cache (size-capped) and hand it to another app through the FileProvider. */
    fun openWith(entry: FileEntry) {
        if (transferJob?.isActive == true) { msg("Another transfer is running."); return }
        if (entry.size > FilePolicy.OPEN_WITH_MAX) { msg("This file is too big to open directly. Use Download instead."); return }
        val ctx = c.context
        transferJob = viewModelScope.launch {
            _ui.update { it.copy(transfer = TransferState(TransferKind.OPEN, entry.name, 0, entry.size, proxyHint = hint(entry.size))) }
            val dir = File(ctx.cacheDir, "files")
            val file = File(dir, safeName(entry.name))
            try {
                withContext(Dispatchers.IO) {
                    dir.listFiles()?.forEach { it.delete() }
                    dir.mkdirs()
                    file.outputStream().use { fetch(entry, it) }
                }
                val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.updates", file)
                events.send(FileEvent.OpenWith(uri, FilePolicy.mimeOf(entry.name), entry.name))
            } catch (e: CancellationException) {
                withContext(NonCancellable) { file.delete() }
                msg("Cancelled")
            } catch (e: Throwable) {
                file.delete()
                msg("Couldn't open: ${FilePolicy.friendlyError(e)}")
            } finally {
                _ui.update { it.copy(transfer = null) }
            }
        }
    }

    fun preview(entry: FileEntry) {
        val kind = FilePolicy.previewKind(entry) ?: run { openWith(entry); return }
        if (transferJob?.isActive == true) { msg("Another transfer is running."); return }
        transferJob = viewModelScope.launch {
            _ui.update { it.copy(transfer = TransferState(TransferKind.PREVIEW, entry.name, 0, entry.size)) }
            try {
                val cap = if (kind == FilePolicy.Preview.TEXT) FilePolicy.TEXT_PREVIEW_MAX else FilePolicy.IMAGE_PREVIEW_MAX
                val buf = CappedBuffer(cap)
                withContext(Dispatchers.IO) { fetch(entry, buf) }
                val bytes = buf.toByteArray()
                val p: FilePreview = when (kind) {
                    FilePolicy.Preview.TEXT -> FilePreview.Text(entry, decodeText(bytes), buf.overflow)
                    FilePolicy.Preview.IMAGE -> {
                        val bmp = withContext(Dispatchers.Default) { decodeImage(bytes) } ?: throw IllegalStateException("This image format can't be previewed.")
                        FilePreview.Image(entry, bmp)
                    }
                }
                _ui.update { it.copy(preview = p) }
            } catch (e: CancellationException) {
                msg("Cancelled")
            } catch (e: Throwable) {
                msg("Preview failed: ${FilePolicy.friendlyError(e)}")
            } finally {
                _ui.update { it.copy(transfer = null) }
            }
        }
    }

    fun closePreview() = _ui.update { it.copy(preview = null) }

    /** The user picked a file to upload: check the name and whether it would overwrite something. */
    fun prepareUpload(uri: Uri) {
        val s = _ui.value
        if (s.atRoot) { msg("Open a pool or folder first."); return }
        val ctx = c.context
        viewModelScope.launch {
            try {
                val (name, size) = withContext(Dispatchers.IO) { queryNameSize(ctx, uri) }
                FilePolicy.nameProblem(name)?.let { msg(it); return@launch }
                val target = FilePolicy.child(s.path, name)
                val exists = c.repository.call { FilesApi(it).exists(target) }
                _ui.update { it.copy(pendingUpload = PendingUpload(uri, name, size, target, exists)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                msg(FilePolicy.friendlyError(e))
            }
        }
    }

    fun dismissUpload() = _ui.update { it.copy(pendingUpload = null) }

    fun upload(p: PendingUpload) {
        _ui.update { it.copy(pendingUpload = null) }
        if (transferJob?.isActive == true) { msg("Another transfer is running."); return }
        val ctx = c.context
        transferJob = viewModelScope.launch {
            _ui.update { it.copy(transfer = TransferState(TransferKind.UPLOAD, p.name, 0, p.size, proxyHint = hint(p.size), target = p.target, replacing = p.exists)) }
            try {
                c.repository.withEndpoint { api, target ->
                    val files = FilesApi(api)
                    val token = files.uploadToken()
                    val (client, _) = HttpClients.create(target, Keepalive.NONE)
                    val jobId = FileTransfers(client, target.url).upload(
                        p.target, token, p.name, p.size,
                        open = { ctx.contentResolver.openInputStream(p.uri) ?: throw IllegalStateException("Can't read the chosen file.") },
                    ) { done, total -> _ui.update { it.copy(transfer = it.transfer?.copy(done = done, total = total, started = true)) } }
                    _ui.update { it.copy(transfer = it.transfer?.copy(finishing = true, started = true)) }
                    try {
                        api.awaitJob(jobId, "filesystem.put", timeoutMs = 60 * 60_000L)
                    } catch (e: CancellationException) {
                        withContext(NonCancellable) { files.abortJob(jobId) }
                        throw e
                    }
                }
                _ui.update { it.copy(incomplete = it.incomplete - p.target) }
                msg("Uploaded ${p.name}")
                load(reset = true)
            } catch (e: CancellationException) {
                val t = _ui.value.transfer
                if (t?.cancelLeavesPartial == true) {
                    markIncomplete(p.target)
                    msg(partialMessage("Upload stopped.", p.name, t))
                } else {
                    msg("Upload cancelled. Nothing was written on TrueNAS.")
                }
            } catch (e: Throwable) {
                val t = _ui.value.transfer
                if (t?.cancelLeavesPartial == true) {
                    markIncomplete(p.target)
                    msg(partialMessage("Upload failed: ${FilePolicy.friendlyError(e)}", p.name, t))
                } else {
                    msg("Upload failed: ${FilePolicy.friendlyError(e)}")
                }
            } finally {
                _ui.update { it.copy(transfer = null) }
            }
        }
    }

    /** Shows the incomplete file in the list (refresh runs outside the cancelled transfer job). */
    private fun markIncomplete(path: String) {
        _ui.update { it.copy(incomplete = it.incomplete + path) }
        viewModelScope.launch { load(reset = true) }
    }

    override fun onCleared() {
        transferJob?.cancel()
    }

    companion object {
        const val AUTO_MTIME_LIMIT = 300

        /** Snackbar text after an upload stopped midway: TrueNAS keeps what arrived and the app can't delete it. */
        fun partialMessage(prefix: String, name: String, t: TransferState): String =
            "$prefix An incomplete $name (${app.truenascompanion.util.Format.bytes(t.done)}" +
                (if (t.total > 0) " of ${app.truenascompanion.util.Format.bytes(t.total)}" else "") + ") is left on TrueNAS" +
                (if (t.replacing) " in place of the original" else "") + ". Delete or replace it over SMB/NFS or the TrueNAS shell."

        fun safeName(name: String): String = name.replace(Regex("[/\\\\:*?\"<>|\u0000]"), "_").take(120).ifBlank { "file" }

        fun decodeText(bytes: ByteArray): String {
            val s = String(bytes, Charsets.UTF_8)
            // A cut-off multi-byte character at the end shows as U+FFFD; drop it.
            return s.trimEnd('\uFFFD')
        }

        fun decodeImage(bytes: ByteArray): ImageBitmap? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (bounds.outWidth / sample > 2048 || bounds.outHeight / sample > 2048) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)?.asImageBitmap()
        }

        fun queryNameSize(ctx: Context, uri: Uri): Pair<String, Long> {
            var name: String? = null
            var size = -1L
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cur ->
                if (cur.moveToFirst()) {
                    val ni = cur.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val si = cur.getColumnIndex(OpenableColumns.SIZE)
                    if (ni >= 0) name = cur.getString(ni)
                    if (si >= 0 && !cur.isNull(si)) size = cur.getLong(si)
                }
            }
            return (name ?: uri.lastPathSegment?.substringAfterLast('/') ?: "upload") to size
        }
    }
}

/** Keeps at most [cap] bytes; the rest of the stream is read and dropped (the download job runs to its end). */
private class CappedBuffer(private val cap: Long) : OutputStream() {
    private val buf = ByteArrayOutputStream()
    var overflow = false
        private set

    override fun write(b: Int) {
        if (buf.size() < cap) buf.write(b) else overflow = true
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        val room = (cap - buf.size()).toInt().coerceAtLeast(0)
        if (room > 0) buf.write(b, off, minOf(room, len))
        if (len > room) overflow = true
    }

    fun toByteArray(): ByteArray = buf.toByteArray()
}
