package app.truenascompanion.ui.shell

import android.content.ClipboardManager
import android.graphics.Typeface
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.FormatSize
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.TextDecrease
import androidx.compose.material.icons.rounded.TextIncrease
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.truenascompanion.data.shell.ShellMessage
import app.truenascompanion.data.shell.ShellTarget
import app.truenascompanion.ui.appViewModel
import app.truenascompanion.ui.components.RouteChip
import com.termux.terminal.KeyHandler
import com.termux.terminal.TerminalColors
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import java.util.Properties

/** Royal blue / cyan terminal palette (always dark, whatever the app theme). */
object ShellColors {
    val background = Color(0xFF070E24)
    val surface = Color(0xFF0E1A3A)
    val border = Color(0xFF22345F)
    val foreground = Color(0xFFE6ECFF)
    val dim = Color(0xFF8FA3D1)
    val cyan = Color(0xFF22D3EE)
    val blue = Color(0xFF4F7BFF)

    private var applied = false

    /** Applies the palette to the emulator's (global) color scheme once, before sessions are created. */
    fun applyToEmulator() {
        if (applied) return
        applied = true
        val p = Properties()
        fun hex(c: Color) = String.format("#%06X", c.toArgb() and 0xFFFFFF)
        p["background"] = hex(background)
        p["foreground"] = hex(foreground)
        p["cursor"] = hex(cyan)
        val ansi = listOf(
            "#1B2547", "#FF5C7A", "#34D399", "#FBBF24", "#4F7BFF", "#C084FC", "#22D3EE", "#D6DEF5",
            "#51618F", "#FF8FA3", "#6EE7B7", "#FDE68A", "#8AA8FF", "#D8B4FE", "#67E8F9", "#FFFFFF",
        )
        ansi.forEachIndexed { i, c -> p["color$i"] = c }
        TerminalColors.COLOR_SCHEME.updateWith(p)
    }
}

fun shellTitle(target: ShellTarget): String = when (target) {
    ShellTarget.Host -> "System shell"
    is ShellTarget.App -> target.appName
    is ShellTarget.Instance -> target.id
}

fun shellSubtitle(target: ShellTarget): String = when (target) {
    ShellTarget.Host -> "TrueNAS host"
    is ShellTarget.App -> "${target.containerName} · ${target.command}"
    is ShellTarget.Instance -> "Container · ${target.command ?: "default shell"}"
}

@Composable
fun ShellScreen(target: ShellTarget, key: String, onBack: () -> Unit) {
    app.truenascompanion.ui.components.SecureWindowEffect() // 1.7.1 (M-4)
    ShellColors.applyToEmulator()
    val vm = appViewModel(key = "shell-$key") { ShellViewModel(it, target) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var confirmLeave by remember { mutableStateOf(false) }
    val leave = { if (ui.phase == ShellPhase.RUNNING) confirmLeave = true else onBack() }
    BackHandler(onBack = leave)
    ShellContent(
        target = target,
        ui = ui,
        actions = ShellActions(
            back = leave,
            reconnect = vm::connect,
            disconnect = { vm.disconnect() },
            font = vm::setFont,
            paste = {
                val text = context.getSystemService(ClipboardManager::class.java)?.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
                if (text != null) vm.paste(text)
            },
            ctrl = vm::toggleCtrl,
            alt = vm::toggleAlt,
            dismissWarning = vm::dismissWarning,
        ),
        terminal = { mod -> TerminalPane(vm, ui, mod) },
        extraKey = { key -> vm.view?.let { sendExtraKey(it, key, vm) } },
    )
    if (confirmLeave) AlertDialog(
        onDismissRequest = { confirmLeave = false },
        icon = { Icon(Icons.Rounded.WarningAmber, null) },
        title = { Text("Close the shell?") },
        text = { Text("The shell session ends when you leave, and anything still running in it is stopped.") },
        confirmButton = { app.truenascompanion.ui.components.DestructiveButton(onClick = { confirmLeave = false; vm.disconnect(); onBack() }) { Text("Close shell") } },
        dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text("Stay") } },
    )
}

class ShellActions(
    val back: () -> Unit = {},
    val reconnect: () -> Unit = {},
    val disconnect: () -> Unit = {},
    val font: (Int) -> Unit = {},
    val paste: () -> Unit = {},
    val ctrl: () -> Unit = {},
    val alt: () -> Unit = {},
    val dismissWarning: () -> Unit = {},
)

enum class ExtraKey(val label: String) {
    ESC("Esc"), TAB("Tab"), CTRL("Ctrl"), ALT("Alt"), LEFT("←"), DOWN("↓"), UP("↑"), RIGHT("→"),
    PIPE("|"), DASH("-"), SLASH("/"), TILDE("~"), HOME("Home"), END("End"), PGUP("PgUp"), PGDN("PgDn"),
}

private fun sendExtraKey(view: TerminalView, key: ExtraKey, vm: ShellViewModel) {
    if (vm.ui.value.phase != ShellPhase.RUNNING || view.currentSession?.emulator == null) return
    fun code(k: Int) {
        var mod = 0
        if (vm.consumeCtrl()) mod = mod or KeyHandler.KEYMOD_CTRL
        if (vm.consumeAlt()) mod = mod or KeyHandler.KEYMOD_ALT
        view.handleKeyCode(k, mod)
    }
    // inputCodePoint() applies a pending Ctrl / Alt itself (through the view client).
    fun char(c: Char) = view.inputCodePoint(c.code, false, false)
    when (key) {
        ExtraKey.CTRL, ExtraKey.ALT -> Unit
        ExtraKey.ESC -> code(KeyEvent.KEYCODE_ESCAPE)
        ExtraKey.TAB -> code(KeyEvent.KEYCODE_TAB)
        ExtraKey.LEFT -> code(KeyEvent.KEYCODE_DPAD_LEFT)
        ExtraKey.DOWN -> code(KeyEvent.KEYCODE_DPAD_DOWN)
        ExtraKey.UP -> code(KeyEvent.KEYCODE_DPAD_UP)
        ExtraKey.RIGHT -> code(KeyEvent.KEYCODE_DPAD_RIGHT)
        ExtraKey.HOME -> code(KeyEvent.KEYCODE_MOVE_HOME)
        ExtraKey.END -> code(KeyEvent.KEYCODE_MOVE_END)
        ExtraKey.PGUP -> code(KeyEvent.KEYCODE_PAGE_UP)
        ExtraKey.PGDN -> code(KeyEvent.KEYCODE_PAGE_DOWN)
        ExtraKey.PIPE -> char('|')
        ExtraKey.DASH -> char('-')
        ExtraKey.SLASH -> char('/')
        ExtraKey.TILDE -> char('~')
    }
}

/** Stateless shell screen (the terminal itself is a slot so previews can show sample output). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShellContent(
    target: ShellTarget,
    ui: ShellUi,
    actions: ShellActions,
    terminal: @Composable (Modifier) -> Unit,
    extraKey: (ExtraKey) -> Unit = {},
) {
    Scaffold(
        containerColor = ShellColors.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = ShellColors.surface, titleContentColor = ShellColors.foreground,
                    navigationIconContentColor = ShellColors.foreground, actionIconContentColor = ShellColors.cyan,
                ),
                navigationIcon = { IconButton(onClick = actions.back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
                title = {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Terminal, null, Modifier.size(18.dp), tint = ShellColors.cyan)
                            Spacer(Modifier.width(6.dp))
                            Text(shellTitle(target), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(shellSubtitle(target), style = MaterialTheme.typography.labelSmall, color = ShellColors.dim, maxLines = 1,
                                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                            ui.route?.let { Spacer(Modifier.width(6.dp)); RouteChip(it) }
                        }
                    }
                },
                actions = {
                    var sizeMenu by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { sizeMenu = true }) { Icon(Icons.Rounded.FormatSize, "Text size") }
                        DropdownMenu(expanded = sizeMenu, onDismissRequest = { sizeMenu = false }) {
                            Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = { actions.font(ui.fontSp - 1) }) { Icon(Icons.Rounded.TextDecrease, "Smaller text") }
                                Text("${ui.fontSp} sp", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 6.dp))
                                IconButton(onClick = { actions.font(ui.fontSp + 1) }) { Icon(Icons.Rounded.TextIncrease, "Larger text") }
                            }
                            Text("Tip: pinch the terminal to zoom", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                        }
                    }
                    IconButton(onClick = actions.paste, enabled = ui.phase == ShellPhase.RUNNING) { Icon(Icons.Rounded.ContentPaste, "Paste") }
                    if (ui.phase == ShellPhase.ENDED) IconButton(onClick = actions.reconnect) { Icon(Icons.Rounded.Refresh, "Reconnect") }
                    else IconButton(onClick = actions.disconnect) { Icon(Icons.Rounded.LinkOff, "Disconnect") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().imePadding()) {
            if (!ui.warningDismissed && ui.phase != ShellPhase.ENDED) WarningStrip(actions.dismissWarning)
            Box(Modifier.weight(1f).fillMaxWidth().background(ShellColors.background)) {
                terminal(Modifier.fillMaxSize().padding(horizontal = 4.dp))
                when (ui.phase) {
                    ShellPhase.CONNECTING -> Row(
                        Modifier.align(Alignment.Center).background(ShellColors.surface, RoundedCornerShape(16.dp))
                            .border(1.dp, ShellColors.border, RoundedCornerShape(16.dp)).padding(horizontal = 18.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(Modifier.size(18.dp), color = ShellColors.cyan, strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                        Text("Opening shell…", color = ShellColors.foreground)
                    }
                    ShellPhase.ENDED -> ui.message?.let { EndedCard(it, actions, Modifier.align(Alignment.Center)) }
                    ShellPhase.RUNNING -> Unit
                }
            }
            ExtraKeysRow(ui, actions, extraKey)
        }
    }
}

@Composable
private fun WarningStrip(onDismiss: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(ShellColors.surface).padding(start = 12.dp, top = 6.dp, bottom = 6.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.WarningAmber, null, Modifier.size(16.dp), tint = Color(0xFFFBBF24))
        Spacer(Modifier.width(8.dp))
        Text(
            "Running commands stop when the shell closes: when you leave this screen, or 30 s after the app goes to the background. " +
                "For long jobs use nohup or tmux on the NAS.",
            style = MaterialTheme.typography.bodySmall, color = ShellColors.dim, modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onDismiss) { Text("OK", color = ShellColors.cyan) }
    }
}

@Composable
private fun EndedCard(m: ShellMessage, actions: ShellActions, modifier: Modifier) {
    Surface(
        color = ShellColors.surface, shape = RoundedCornerShape(20.dp),
        modifier = modifier.padding(20.dp).widthIn(max = 460.dp).border(1.dp, ShellColors.border, RoundedCornerShape(20.dp)),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(m.title, style = MaterialTheme.typography.titleMedium, color = ShellColors.foreground, fontWeight = FontWeight.SemiBold)
            Text(m.detail, style = MaterialTheme.typography.bodyMedium, color = ShellColors.dim)
            m.hint?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = ShellColors.cyan) }
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (m.canReconnect) OutlinedButton(onClick = actions.reconnect) {
                    Icon(Icons.Rounded.Refresh, null, Modifier.size(18.dp), tint = ShellColors.cyan); Spacer(Modifier.width(6.dp)); Text("Reconnect", color = ShellColors.cyan)
                }
                TextButton(onClick = actions.back) { Text("Close", color = ShellColors.foreground) }
            }
        }
    }
}

@Composable
private fun ExtraKeysRow(ui: ShellUi, actions: ShellActions, onKey: (ExtraKey) -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(ShellColors.surface).navigationBarsPadding().horizontalScroll(rememberScrollState())
            .padding(horizontal = 6.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ExtraKey.entries.forEach { k ->
            val active = (k == ExtraKey.CTRL && ui.ctrl) || (k == ExtraKey.ALT && ui.alt)
            val enabled = ui.phase == ShellPhase.RUNNING
            Box(
                Modifier.heightIn(min = 40.dp).widthIn(min = 44.dp)
                    .background(if (active) ShellColors.cyan else ShellColors.background, RoundedCornerShape(10.dp))
                    .border(1.dp, if (active) ShellColors.cyan else ShellColors.border, RoundedCornerShape(10.dp))
                    .clickable(enabled = enabled) {
                        when (k) {
                            ExtraKey.CTRL -> actions.ctrl()
                            ExtraKey.ALT -> actions.alt()
                            else -> onKey(k)
                        }
                    }
                    .padding(horizontal = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    k.label, fontFamily = FontFamily.Monospace, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                    color = when { active -> ShellColors.background; enabled -> ShellColors.foreground; else -> ShellColors.dim.copy(alpha = 0.5f) },
                )
            }
        }
    }
}

@Composable
private fun TerminalPane(vm: ShellViewModel, ui: ShellUi, modifier: Modifier) {
    val density = LocalDensity.current
    val px = with(density) { ui.fontSp.sp.roundToPx() }
    val client = remember(vm) { ShellViewClient(vm) }
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            TerminalView(ctx, null).apply {
                setTerminalViewClient(client)
                setTextSize(px)
                setTypeface(Typeface.MONOSPACE)
                setBackgroundColor(ShellColors.background.toArgb())
                isFocusable = true
                isFocusableInTouchMode = true
                keepScreenOn = true
                vm.session?.let { attachSession(it) }
                tag = ui.epoch
                vm.view = this
                client.view = this
            }
        },
        update = { v ->
            if (v.tag != ui.epoch) { vm.session?.let { v.attachSession(it) }; v.tag = ui.epoch }
            v.setTextSize(px)
            if (ui.phase == ShellPhase.RUNNING && !v.hasFocus()) v.requestFocus()
        },
        onRelease = { v -> if (vm.view === v) vm.view = null; client.view = null },
    )
}

/** Glue between Termux's view and the view model: pinch zoom, Ctrl/Alt from the extra keys, keyboard on tap. */
private class ShellViewClient(private val vm: ShellViewModel) : TerminalViewClient {
    var view: TerminalView? = null
    override fun onScale(scale: Float): Float {
        if (scale < 0.9f || scale > 1.1f) {
            vm.setFont(vm.ui.value.fontSp + if (scale > 1f) 1 else -1)
            return 1f
        }
        return scale
    }

    override fun onSingleTapUp(e: MotionEvent?) {
        val v = view ?: return
        v.requestFocus()
        v.context.getSystemService(InputMethodManager::class.java)?.showSoftInput(v, InputMethodManager.SHOW_IMPLICIT)
    }

    override fun shouldBackButtonBeMappedToEscape() = false
    override fun shouldEnforceCharBasedInput() = true
    override fun shouldUseCtrlSpaceWorkaround() = false
    override fun isTerminalViewSelected() = true
    override fun copyModeChanged(copyMode: Boolean) = Unit
    override fun onKeyDown(keyCode: Int, e: KeyEvent?, session: TerminalSession?) = false
    override fun onKeyUp(keyCode: Int, e: KeyEvent?) = false
    override fun onLongPress(event: MotionEvent?) = false
    override fun readControlKey() = vm.consumeCtrl()
    override fun readAltKey() = vm.consumeAlt()
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
