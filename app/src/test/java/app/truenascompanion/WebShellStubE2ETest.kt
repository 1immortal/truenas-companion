package app.truenascompanion

import app.truenascompanion.data.api.Credentials
import app.truenascompanion.data.api.LoginStep
import app.truenascompanion.data.api.WebSocketAuth
import app.truenascompanion.data.model.AuthMethod
import app.truenascompanion.data.model.ServerConfig
import app.truenascompanion.data.shell.ShellEnd
import app.truenascompanion.data.shell.ShellListener
import app.truenascompanion.data.shell.ShellTarget
import app.truenascompanion.data.shell.WebShellConnection
import app.truenascompanion.data.shell.WebShellProtocol
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalOutput
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * End-to-end against `tools/webshell_stub.py` (a copy of the middleware shell handler around a real pty):
 * sign in, get a one-time token, open the shell, run commands, resize, exit. Feeds the output into the real
 * terminal emulator. Skipped unless SHELL_STUB_URL is set, e.g. `SHELL_STUB_URL=http://127.0.0.1:8081`.
 */
class WebShellStubE2ETest {
    private object NoClient : TerminalSessionClient {
        override fun onTextChanged(changedSession: TerminalSession) = Unit
        override fun onTitleChanged(changedSession: TerminalSession) = Unit
        override fun onSessionFinished(finishedSession: TerminalSession) = Unit
        override fun onCopyTextToClipboard(session: TerminalSession, text: String?) = Unit
        override fun onPasteTextFromClipboard(session: TerminalSession?) = Unit
        override fun onBell(session: TerminalSession) = Unit
        override fun onColorsChanged(session: TerminalSession) = Unit
        override fun onTerminalCursorStateChange(state: Boolean) = Unit
        override fun getTerminalCursorStyle(): Int? = null
        override fun logError(tag: String?, message: String?) = Unit
        override fun logWarn(tag: String?, message: String?) = Unit
        override fun logInfo(tag: String?, message: String?) = Unit
        override fun logDebug(tag: String?, message: String?) = Unit
        override fun logVerbose(tag: String?, message: String?) = Unit
        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) = Unit
        override fun logStackTrace(tag: String?, e: Exception?) = Unit
    }

    @Test
    fun realPtyThroughStub() = runBlocking {
        val base = System.getenv("SHELL_STUB_URL")
        assumeTrue("set SHELL_STUB_URL to run", !base.isNullOrBlank())
        val cfg = ServerConfig(id = "s", name = "stub", url = base!!, username = "admin", authMethod = AuthMethod.PASSWORD, sessionDays = 7)
        val api = (WebSocketAuth.login(cfg, Credentials.Password("admin", "demo"), 604800) as LoginStep.Success).api
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        lateinit var conn: WebShellConnection
        val emulator = TerminalEmulator(object : TerminalOutput() {
            override fun write(data: ByteArray, offset: Int, count: Int) = conn.send(data, offset, count)
            override fun titleChanged(oldTitle: String?, newTitle: String?) = Unit
            override fun onCopyTextToClipboard(text: String?) = Unit
            override fun onPasteTextFromClipboard() = Unit
            override fun onBell() = Unit
            override fun onColorsChanged() = Unit
        }, 80, 24, 500, NoClient)
        val connected = CountDownLatch(1)
        val ended = CountDownLatch(1)
        var sid: String? = null
        val end = java.util.concurrent.atomic.AtomicReference<ShellEnd?>()
        conn = WebShellConnection(OkHttpClient(), WebShellProtocol.url(base), scope, object : ShellListener {
            override fun onConnected(id: String?) { sid = id; connected.countDown() }
            override fun onOutput(data: ByteArray) = synchronized(emulator) { emulator.append(data, data.size) }
            override fun onEnd(end: ShellEnd) { endRef(end); ended.countDown() }
            fun endRef(e: ShellEnd) = end.set(e)
        })
        conn.open(api.shellToken(), ShellTarget.App("demo", "3f2a9c1be0d4", "web", "/bin/sh"))
        assertTrue(connected.await(10, TimeUnit.SECONDS))
        fun screen() = synchronized(emulator) { emulator.screen.transcriptText }
        fun waitFor(s: String) { repeat(200) { if (screen().contains(s)) return; Thread.sleep(25) }; throw AssertionError("'$s' not on screen") }

        emulator.paste("echo hi \$((6*7))") // goes through TerminalOutput.write -> binary frames
        conn.send("\r".encodeToByteArray())
        waitFor("hi 42")
        api.resizeShell(sid!!, 100, 30)
        conn.send("stty size\r".encodeToByteArray())
        waitFor("30 100")
        // a token is single use: the first socket gets a shell, the second is refused
        val used = api.shellToken()
        val results = java.util.concurrent.LinkedBlockingQueue<Any>()
        fun probe() = WebShellConnection(OkHttpClient(), WebShellProtocol.url(base), scope, object : ShellListener {
            override fun onConnected(id: String?) { results.put("connected") }
            override fun onOutput(data: ByteArray) = Unit
            override fun onEnd(end: ShellEnd) { if (end !is ShellEnd.Closed) results.put(end) }
        }).also { it.open(used, ShellTarget.Host) }
        val p1 = probe()
        assertEquals("connected", results.poll(10, TimeUnit.SECONDS))
        val p2 = probe()
        assertEquals(ShellEnd.Rejected("Invalid token"), results.poll(10, TimeUnit.SECONDS))
        p1.close(); p2.close()

        conn.send("exit\r".encodeToByteArray())
        assertTrue(ended.await(10, TimeUnit.SECONDS))
        assertEquals(ShellEnd.Exited, end.get())
        api.close()
        scope.cancel()
    }
}
