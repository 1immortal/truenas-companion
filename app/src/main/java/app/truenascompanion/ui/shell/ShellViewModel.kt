package app.truenascompanion.ui.shell

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.os.Build
import android.os.PersistableBundle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.truenascompanion.AppContainer
import app.truenascompanion.data.api.TrueNasApi
import app.truenascompanion.data.model.Route
import app.truenascompanion.data.net.HttpClients
import app.truenascompanion.data.net.Keepalive
import app.truenascompanion.data.net.toInfo
import app.truenascompanion.data.repository.TrueNasRepository
import app.truenascompanion.data.shell.ShellEnd
import app.truenascompanion.data.shell.ShellListener
import app.truenascompanion.data.shell.ShellMessage
import app.truenascompanion.data.shell.ShellMessages
import app.truenascompanion.data.shell.ShellTarget
import app.truenascompanion.data.shell.WebShellConnection
import app.truenascompanion.data.shell.WebShellProtocol
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class ShellPhase { CONNECTING, RUNNING, ENDED }

data class ShellUi(
    val phase: ShellPhase = ShellPhase.CONNECTING,
    val message: ShellMessage? = null,
    val route: Route? = null,
    val fontSp: Int = ShellViewModel.DEFAULT_FONT,
    val ctrl: Boolean = false,
    val alt: Boolean = false,
    /** Changes with every new session, so the view attaches the new one. */
    val epoch: Int = 0,
    /** Shown in the background warning banner once per screen. */
    val warningDismissed: Boolean = false,
)

/**
 * One web shell session. The terminal state lives here (survives rotation); the [TerminalView] attaches to [session].
 * The shell socket closes when the screen goes away and after the app spent [TrueNasRepository.BACKGROUND_GRACE_MS]
 * in the background, like the app's own connection.
 */
class ShellViewModel(private val c: AppContainer, val target: ShellTarget) : ViewModel(), TerminalSessionClient {
    private val _ui = MutableStateFlow(ShellUi())
    val ui: StateFlow<ShellUi> = _ui.asStateFlow()

    var session: TerminalSession? = null
        private set
    private var connection: WebShellConnection? = null
    private var api: TrueNasApi? = null
    private var shellId: String? = null
    private var size: Pair<Int, Int>? = null
    private var resizeJob: Job? = null
    private var backgroundJob: Job? = null
    /** Bumped by connect, disconnect and onCleared so a token request still in flight can't open a shell afterwards. */
    private var generation = 0

    /** The view currently showing [session] (set/cleared by the screen; never kept across its disposal). */
    @Volatile var view: TerminalView? = null

    init {
        connect()
        viewModelScope.launch {
            c.repository.foreground.collect { fg ->
                backgroundJob?.cancel()
                if (!fg && _ui.value.phase != ShellPhase.ENDED) backgroundJob = launch {
                    delay(TrueNasRepository.BACKGROUND_GRACE_MS)
                    disconnect("The shell was closed after 30 seconds in the background to save battery. Anything that was still running in it was stopped.")
                }
            }
        }
    }

    fun connect() {
        // Retire any socket first. close() reports onEnd synchronously; the session is replaced just below,
        // so that report doesn't stick (it only updates the UI while this session is still current).
        val previous = connection
        connection = null
        shellId = null
        val gen = ++generation
        previous?.close()
        val epoch = _ui.value.epoch + 1
        _ui.update { it.copy(phase = ShellPhase.CONNECTING, message = null, epoch = epoch) }
        val transport = object : TerminalSession.Transport {
            override fun send(data: ByteArray, offset: Int, count: Int) {
                connection?.send(data, offset, count)
            }

            override fun resize(columns: Int, rows: Int) {
                size = columns to rows
                pushSize()
            }
        }
        val s = TerminalSession(transport, TRANSCRIPT_ROWS, this)
        session = s
        size?.let { (cols, rows) -> s.updateSize(cols, rows) }
        viewModelScope.launch {
            try {
                val (a, resolved) = c.repository.shellEndpoint()
                if (gen != generation) return@launch
                api = a
                _ui.update { it.copy(route = resolved.activeRoute.takeIf { resolved.hasAlternativeRoutes }) }
                val token = a.shellToken()
                if (gen != generation) return@launch
                val (client, tm) = HttpClients.create(resolved, Keepalive.FOREGROUND)
                // The shared client reads with a 30 s timeout, which would drop an idle prompt. No read timeout
                // here; the 30 s ping still fails the socket if the NAS stops answering.
                val shellClient = client.newBuilder().readTimeout(0, java.util.concurrent.TimeUnit.MILLISECONDS).build()
                val route = resolved.activeRoute
                // The listener runs later, so it closes through this box rather than the val still being built.
                val box = arrayOfNulls<WebShellConnection>(1)
                val conn = WebShellConnection(
                    shellClient, WebShellProtocol.url(resolved.url), viewModelScope,
                    listener = object : ShellListener {
                        override fun onConnected(id: String?) {
                            if (gen != generation) { box[0]?.close(); return }
                            shellId = id
                            _ui.update { it.copy(phase = ShellPhase.RUNNING) }
                            pushSize()
                        }

                        override fun onOutput(data: ByteArray) = s.feed(data, 0, data.size)

                        override fun onEnd(end: ShellEnd) {
                            s.markFinished()
                            if (gen == generation && session === s) _ui.update { it.copy(phase = ShellPhase.ENDED, message = ShellMessages.describe(end, route)) }
                        }
                    },
                    certificate = { tm.lastChain?.firstOrNull()?.toInfo() },
                )
                box[0] = conn
                if (gen != generation || session !== s) return@launch
                connection = conn
                conn.open(token, target)
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                s.markFinished()
                if (gen == generation && session === s) _ui.update { it.copy(phase = ShellPhase.ENDED, message = ShellMessages.beforeConnect(e)) }
            }
        }
    }

    fun disconnect(reason: String? = null) {
        generation++
        val current = connection
        connection = null
        shellId = null
        if (current != null) current.close(reason)
        else _ui.update { it.copy(phase = ShellPhase.ENDED, message = ShellMessages.describe(ShellEnd.Closed(reason), it.route)) }
    }

    /** `core.resize_shell` over the normal API once the shell is running (debounced: the view reports every layout). */
    private fun pushSize() {
        val id = shellId ?: return
        val (cols, rows) = size ?: return
        resizeJob?.cancel()
        resizeJob = viewModelScope.launch {
            delay(150)
            runCatching { withContext(Dispatchers.IO) { api?.resizeShell(id, cols, rows) } }
        }
    }

    fun setFont(sp: Int) = _ui.update { it.copy(fontSp = sp.coerceIn(MIN_FONT, MAX_FONT)) }
    fun toggleCtrl() = _ui.update { it.copy(ctrl = !it.ctrl) }
    fun toggleAlt() = _ui.update { it.copy(alt = !it.alt) }
    fun dismissWarning() = _ui.update { it.copy(warningDismissed = true) }

    /** Ctrl / Alt from the extra-keys row apply to the next key only. */
    fun consumeCtrl(): Boolean = _ui.value.ctrl.also { if (it) _ui.update { u -> u.copy(ctrl = false) } }
    fun consumeAlt(): Boolean = _ui.value.alt.also { if (it) _ui.update { u -> u.copy(alt = false) } }

    fun paste(text: String) {
        if (_ui.value.phase != ShellPhase.RUNNING || text.isEmpty()) return
        session?.emulator?.paste(text)
    }

    override fun onCleared() {
        generation++
        connection?.close()
        connection = null
        view = null
    }

    // --- TerminalSessionClient (main thread). Nothing is logged: terminal content never leaves the screen. ---
    override fun onTextChanged(changedSession: TerminalSession) { if (changedSession === session) view?.onScreenUpdated() }
    override fun onTitleChanged(changedSession: TerminalSession) = Unit
    override fun onSessionFinished(finishedSession: TerminalSession) = Unit
    override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
        val ctx = view?.context ?: return
        val clip = ClipData.newPlainText("Terminal", text)
        // Terminal output can contain secrets: keep it out of the clipboard preview.
        clip.description.extras = PersistableBundle().apply {
            putBoolean(if (Build.VERSION.SDK_INT >= 33) ClipDescription.EXTRA_IS_SENSITIVE else "android.content.extra.IS_SENSITIVE", true)
        }
        ctx.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(clip)
    }
    override fun onPasteTextFromClipboard(session: TerminalSession) {
        val ctx = view?.context ?: return
        val text = ctx.getSystemService(ClipboardManager::class.java)?.primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString() ?: return
        paste(text)
    }
    override fun onBell(session: TerminalSession) = Unit
    override fun onColorsChanged(session: TerminalSession) { view?.invalidate() }
    override fun onTerminalCursorStateChange(state: Boolean) = Unit
    override fun getTerminalCursorStyle(): Int? = null
    override fun logError(tag: String?, message: String?) = Unit
    override fun logWarn(tag: String?, message: String?) = Unit
    override fun logInfo(tag: String?, message: String?) = Unit
    override fun logDebug(tag: String?, message: String?) = Unit
    override fun logVerbose(tag: String?, message: String?) = Unit
    override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) = Unit
    override fun logStackTrace(tag: String?, e: Exception?) = Unit

    companion object {
        const val TRANSCRIPT_ROWS = 3000
        const val DEFAULT_FONT = 13
        const val MIN_FONT = 7
        const val MAX_FONT = 28
    }
}
