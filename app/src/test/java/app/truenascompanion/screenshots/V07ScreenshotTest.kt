package app.truenascompanion.screenshots

import android.graphics.Typeface
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.truenascompanion.data.model.AppContainerInfo
import app.truenascompanion.data.model.AppInfo
import app.truenascompanion.data.model.AppState
import app.truenascompanion.data.model.InstanceStatus
import app.truenascompanion.data.model.Route
import app.truenascompanion.data.model.VirtInstance
import app.truenascompanion.data.shell.ShellEnd
import app.truenascompanion.data.shell.ShellMessages
import app.truenascompanion.data.shell.ShellTarget
import app.truenascompanion.data.store.ThemeMode
import app.truenascompanion.ui.apps.AppDetailContent
import app.truenascompanion.ui.shell.ShellActions
import app.truenascompanion.ui.shell.ShellColors
import app.truenascompanion.ui.shell.ShellContainer
import app.truenascompanion.ui.shell.ShellContent
import app.truenascompanion.ui.shell.ShellPhase
import app.truenascompanion.ui.shell.ShellStartDialog
import app.truenascompanion.ui.shell.ShellUi
import app.truenascompanion.ui.system.ShellEntry
import app.truenascompanion.ui.theme.TrueNasTheme
import app.truenascompanion.ui.virt.ContainerCard
import com.github.takahirom.roborazzi.captureRoboImage
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** v0.7.0 previews: system / app / container shell. The terminal is the real Termux view fed sample output. Example data only. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h2000dp-xxhdpi", application = android.app.Application::class)
class V07ScreenshotTest {
    @get:Rule val rule = createComposeRule()
    private val dir = File(System.getProperty("screenshot.dir") ?: "build/screenshots").apply { mkdirs() }
    private fun out(name: String) = File(dir, "$name.png").absolutePath

    @Composable
    private fun Frame(dark: Boolean, content: @Composable () -> Unit) {
        val d = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(d.density, 1f)) {
            TrueNasTheme(themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT, dynamicColor = false) {
                Surface(color = MaterialTheme.colorScheme.background) { content() }
            }
        }
    }

    private fun shot(name: String, dark: Boolean = true, height: Int? = null, content: @Composable () -> Unit) {
        rule.mainClock.autoAdvance = false
        rule.setContent { Frame(dark) { Box(if (height != null) Modifier.fillMaxWidth().height(height.dp) else Modifier.fillMaxSize()) { content() } } }
        repeat(6) { rule.mainClock.advanceTimeBy(300); shadowOf(android.os.Looper.getMainLooper()).idle() }
        rule.onRoot().captureRoboImage(out(name))
    }

    private fun dialogShot(name: String, dark: Boolean, content: @Composable () -> Unit) {
        rule.mainClock.autoAdvance = false
        rule.setContent { Frame(dark) { Box(Modifier.fillMaxSize()) { content() } } }
        rule.mainClock.advanceTimeBy(1000)
        rule.onNode(isDialog()).captureRoboImage(out(name))
    }

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

    private object NoViewClient : com.termux.view.TerminalViewClient {
        override fun onScale(scale: Float) = 1f
        override fun onSingleTapUp(e: android.view.MotionEvent?) = Unit
        override fun shouldBackButtonBeMappedToEscape() = false
        override fun shouldEnforceCharBasedInput() = true
        override fun shouldUseCtrlSpaceWorkaround() = false
        override fun isTerminalViewSelected() = true
        override fun copyModeChanged(copyMode: Boolean) = Unit
        override fun onKeyDown(keyCode: Int, e: android.view.KeyEvent?, session: TerminalSession?) = false
        override fun onKeyUp(keyCode: Int, e: android.view.KeyEvent?) = false
        override fun onLongPress(event: android.view.MotionEvent?) = false
        override fun readControlKey() = false
        override fun readAltKey() = false
        override fun readShiftKey() = false
        override fun readFnKey() = false
        override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession?) = false
        override fun onEmulatorSet() = Unit
        override fun logError(tag: String?, message: String?) = Unit
        override fun logWarn(tag: String?, message: String?) = Unit
        override fun logInfo(tag: String?, message: String?) = Unit
        override fun logDebug(tag: String?, message: String?) = Unit
        override fun logVerbose(tag: String?, message: String?) = Unit
        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) = Unit
        override fun logStackTrace(tag: String?, e: Exception?) = Unit
    }

    private val esc = "\u001b"
    private fun prompt(dir: String = "~") = "$esc[1;32mtruenas_admin@homenas$esc[0m:$esc[1;36m$dir$esc[0m$ "
    private val hostOutput = buildString {
        append("Welcome to TrueNAS 25.10\r\n\r\n")
        append(prompt()); append("uptime\r\n")
        append(" 09:41:07 up 12 days,  3:02,  1 user,  load average: 0.21, 0.18, 0.15\r\n")
        append(prompt()); append("sudo zpool status -x\r\n")
        append("all pools are healthy\r\n")
        append(prompt()); append("df -h /mnt/tank\r\n")
        append("Filesystem      Size  Used Avail Use% Mounted on\r\n")
        append("tank            7.1T  2.9T  4.2T  41% /mnt/tank\r\n")
        append(prompt()); append("ls /mnt/tank\r\n")
        append("$esc[1;34mapps$esc[0m  $esc[1;34mbackups$esc[0m  $esc[1;34mmedia$esc[0m  $esc[1;34mphotos$esc[0m  $esc[1;34mvms$esc[0m\r\n")
        append(prompt()); append("sudo systemctl is-active nginx smbd\r\n")
        append("$esc[32mactive$esc[0m\r\n$esc[32mactive$esc[0m\r\n")
        append(prompt())
    }
    private val appOutput = buildString {
        append("/ # ps\r\n")
        append("PID   USER     TIME  COMMAND\r\n")
        append("    1 jellyfin  2:14 /jellyfin/jellyfin --ffmpeg /usr/lib/jellyfin-ffmpeg/ffmpeg\r\n")
        append("   61 root      0:00 /bin/sh\r\n")
        append("   68 root      0:00 ps\r\n")
        append("/ # ls /config\r\n")
        append("$esc[1;34mcache$esc[0m  $esc[1;34mdata$esc[0m  $esc[1;34mlog$esc[0m  $esc[1;34mmetadata$esc[0m  $esc[1;34mplugins$esc[0m\r\n")
        append("/ # tail -n 3 /config/log/log_20260928.log\r\n")
        append("[09:40:12] [INF] Startup complete 0:00:04.2\r\n")
        append("[09:40:58] [INF] Scheduled task Scan Media Library completed\r\n")
        append("[09:41:03] [INF] Playback started: Big Buck Bunny\r\n")
        append("/ # ")
    }

    /** The real Termux terminal view, fed sample ANSI output. */
    @Composable
    private fun Terminal(output: String, modifier: Modifier, fontSp: Int = 13) {
        val px = with(LocalDensity.current) { fontSp.dp.roundToPx() }
        AndroidView(modifier = modifier, factory = { ctx ->
            ShellColors.applyToEmulator()
            val session = TerminalSession(object : TerminalSession.Transport {
                override fun send(data: ByteArray, offset: Int, count: Int) = Unit
                override fun resize(columns: Int, rows: Int) = Unit
            }, 500, NoClient)
            val bytes = output.encodeToByteArray()
            session.feed(bytes, 0, bytes.size)
            TerminalView(ctx, null).apply {
                setTerminalViewClient(NoViewClient)
                setTextSize(px)
                setTypeface(Typeface.MONOSPACE)
                setBackgroundColor(ShellColors.background.toArgb())
                attachSession(session)
            }
        })
    }

    private val appTarget = ShellTarget.App("jellyfin", "a1", "jellyfin", "/bin/sh")
    private val running = ShellUi(phase = ShellPhase.RUNNING, route = Route.LOCAL)

    @Test fun systemShell() = shot("v07_system_shell", height = 760) {
        ShellContent(ShellTarget.Host, running, ShellActions(), terminal = { Terminal(hostOutput, it) })
    }

    @Test fun appShell() = shot("v07_app_shell", height = 760) {
        ShellContent(appTarget, running.copy(route = Route.REMOTE, warningDismissed = true, ctrl = true), ShellActions(), terminal = { Terminal(appOutput, it) })
    }

    @Test fun connecting() = shot("v07_connecting", height = 560) {
        ShellContent(ShellTarget.Instance("debian", null), ShellUi(route = Route.TAILSCALE), ShellActions(), terminal = { Terminal("", it) })
    }

    @Test fun proxyHint() = shot("v07_proxy_hint", height = 700) {
        ShellContent(ShellTarget.Host, ShellUi(phase = ShellPhase.ENDED, route = Route.REMOTE, message = ShellMessages.describe(ShellEnd.UpgradeFailed(400), Route.REMOTE)),
            ShellActions(), terminal = { Terminal("", it) })
    }

    @Test fun sessionEnded() = shot("v07_session_ended", height = 760) {
        ShellContent(appTarget, ShellUi(phase = ShellPhase.ENDED, route = Route.LOCAL, warningDismissed = true, message = ShellMessages.describe(ShellEnd.Exited, Route.LOCAL)),
            ShellActions(), terminal = { Terminal(appOutput + "exit\r\n", it) })
    }

    @Test
    @Config(qualifiers = "w780dp-h360dp-xxhdpi")
    fun landscape() = shot("v07_landscape") {
        ShellContent(ShellTarget.Host, running.copy(warningDismissed = true), ShellActions(), terminal = { Terminal(hostOutput, it, 12) })
    }

    @Test fun appStartDialog() = dialogShot("v07_app_shell_dialog", true) {
        ShellStartDialog("Shell in immich", listOf(
            ShellContainer("c1", "server", "ghcr.io/immich-app/immich-server:v1.140"),
            ShellContainer("c2", "machine-learning", "ghcr.io/immich-app/immich-machine-learning:v1.140"),
            ShellContainer("c3", "pgvecto", "ghcr.io/immich-app/postgres:16"),
            ShellContainer("c4", "redis", "valkey/valkey:8"),
        ), onOpen = { _, _ -> }, onDismiss = {})
    }

    @Test fun containerDialog() = dialogShot("v07_container_shell_dialog", false) {
        ShellStartDialog("Shell in debian", emptyList(), defaultLabel = "Default shell", onOpen = { _, _ -> }, onDismiss = {})
    }

    private val installed = AppInfo(
        name = "jellyfin", state = AppState.RUNNING, version = "10.10.7_1.2.0", upgradeAvailable = false, imageUpdatesAvailable = false,
        description = "Media server", portalUrl = "http://192.168.1.10:30013/", containers = 1, catalogName = "jellyfin", train = "stable",
        containerDetails = listOf(
            AppContainerInfo("a1", "jellyfin", "jellyfin/jellyfin:10.10.7", "running"),
            AppContainerInfo("a2", "permissions", "ixsystems/container-utils:1.0.2", "exited"),
        ),
    )

    @Test fun entryPoints() = shot("v07_entry_points", height = 1100) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ShellEntry(enabled = true) {}
            ContainerCard(VirtInstance("debian", "debian", "CONTAINER", InstanceStatus.RUNNING, "2", 2L * 1024 * 1024 * 1024, true,
                "Debian bookworm amd64", listOf("10.0.3.12"), "tank"), null, {}, {})
            Box(Modifier.height(560.dp)) { AppDetailContent(installed, null, {}, {}, {}) }
        }
    }
}
