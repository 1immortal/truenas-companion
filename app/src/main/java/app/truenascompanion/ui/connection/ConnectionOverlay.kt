package app.truenascompanion.ui.connection

import android.app.Activity
import android.os.Build
import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.SettingsEthernet
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.FilledTonalButton
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.truenascompanion.AppContainer
import app.truenascompanion.data.api.TrueNasException
import app.truenascompanion.data.repository.ConnectionState
import app.truenascompanion.data.store.ConnectionTimeoutPrefs
import app.truenascompanion.ui.components.GlowButton
import app.truenascompanion.ui.components.glow
import app.truenascompanion.ui.lock.findFragmentActivity
import app.truenascompanion.ui.theme.LocalBrandColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive

/**
 * Default give-up window before the failure overlay (matches System › Connection default / OkHttp connectTimeout).
 * Kept as a top-level alias so existing tests keep compiling.
 */
const val CONNECTION_OVERLAY_GRACE_MS = ConnectionTimeoutPrefs.DEFAULT_MS

/** How often to auto-retry while still inside the give-up window. */
private const val AUTO_RETRY_INTERVAL_MS = 2_500L

/** What the full-screen connection gate should show (or [None]). */
sealed interface ConnectionOverlayUi {
    data object None : ConnectionOverlayUi
    data class Connecting(val serverName: String) : ConnectionOverlayUi
    data class Failed(val serverName: String, val detail: String) : ConnectionOverlayUi
}

/**
 * Pure decision for the connection overlay. Used by the UI and unit tests.
 *
 * - Only when a saved server was expected to connect (not first-run add-server).
 * - Not while the user is already on that server’s connection settings.
 * - Not for [TrueNasException.LoginRequired] (the sign-in dialog owns that).
 * - Failed only after [graceMs] (System › Connection “Give up after”) so retries can finish first.
 */
object ConnectionOverlayDecision {
    fun decide(
        hasSavedServers: Boolean,
        activeServerName: String?,
        connection: ConnectionState,
        elapsedSinceAttemptMs: Long,
        onServerEditScreen: Boolean,
        graceMs: Long = CONNECTION_OVERLAY_GRACE_MS,
    ): ConnectionOverlayUi {
        if (!hasSavedServers || activeServerName.isNullOrBlank()) return ConnectionOverlayUi.None
        if (onServerEditScreen) return ConnectionOverlayUi.None
        return when (connection) {
            ConnectionState.NoServer, ConnectionState.Idle, is ConnectionState.Connected -> ConnectionOverlayUi.None
            ConnectionState.Connecting -> ConnectionOverlayUi.Connecting(activeServerName)
            is ConnectionState.Failed -> {
                if (connection.error is TrueNasException.LoginRequired) return ConnectionOverlayUi.None
                if (elapsedSinceAttemptMs < graceMs) ConnectionOverlayUi.Connecting(activeServerName)
                else ConnectionOverlayUi.Failed(activeServerName, friendlyDetail(connection))
            }
        }
    }

    /** Short, non-technical copy for the failure card (never dumps stack traces or hostnames). */
    fun friendlyDetail(failed: ConnectionState.Failed): String = when (failed.error) {
        is TrueNasException.UntrustedCertificate ->
            "The server’s security certificate isn’t trusted yet. Open connection settings to review it."
        is TrueNasException.AuthFailed, is TrueNasException.PasswordRejected ->
            "Sign-in was rejected. Check the address and credentials in connection settings."
        is TrueNasException.Timeout ->
            "The server didn’t answer in time. Check that it’s online and that your phone can reach it."
        is TrueNasException.InsecureAddress ->
            "This server’s address uses unencrypted http://, so the app won’t sign in over it. Open connection settings and use its https:// address."
        else ->
            "Make sure the NAS is online and that your phone can reach it — on Wi‑Fi at home, or through your VPN."
    }
}

/**
 * Consumes every pointer event on this node in the Initial pass so descendants and anything behind
 * cannot see the gesture. Use only on the **underlay** (blurred content), never on a parent of the
 * overlay’s own buttons.
 */
fun Modifier.blockUnderlyingPointerInput(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            event.changes.forEach { it.consume() }
        }
    }
}

/**
 * Everything under the connection overlay while it's up: no touch, scroll or focus, and (1.7.1, a11y P0-2) nothing
 * for TalkBack / Switch Access either, so the blurred screen can't be read or double-tapped through the overlay.
 */
fun Modifier.connectionUnderlay(obscure: Boolean): Modifier =
    if (!obscure) this
    else this
        .clearAndSetSemantics { }
        .focusProperties { canFocus = false }
        .nestedScroll(BlockingNestedScrollConnection)
        .blockUnderlyingPointerInput()

/** Swallows only what children didn't use, so scrollable content inside the overlay still scrolls. */
internal val LeftoverScrollSink = object : NestedScrollConnection {
    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset = available
    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity = available
}

/** Nested-scroll sink: any scroll/fling that reaches this connection is fully consumed. */
internal val BlockingNestedScrollConnection = object : NestedScrollConnection {
    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset = available
    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset = available
    override suspend fun onPreFling(available: Velocity): Velocity = available
    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity = available
}

/**
 * Full-screen modal barrier above blurred content.
 *
 * - [clickable] on the scrim so empty areas participate in hit-testing (children still win).
 * - [nestedScroll] consumes scroll/fling so lists under the blur cannot move.
 * - Requests focus and hides the keyboard so underlay fields cannot type.
 *
 * Do **not** attach Initial-pass consumption here — that would steal clicks from Quit / Try again.
 */
@Composable
fun ConnectionModalBarrier(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        keyboard?.hide()
        focusManager.clearFocus(force = true)
        runCatching { focusRequester.requestFocus() }
    }
    Box(
        modifier
            .fillMaxSize()
            .testTag("connection_overlay_barrier")
            .semantics {
                isTraversalGroup = true
                // 1.7.1 (a11y P0-2): announced as its own pane.
                paneTitle = "Connection"
            }
            // Post-only: the failure card's own scrolling (1.7.1) works; only leftover scroll/fling stops here.
            .nestedScroll(LeftoverScrollSink)
            .focusRequester(focusRequester)
            .focusProperties { canFocus = true }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},
            ),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

/**
 * Blurs (API 31+) or dims/scales (older) [content] while a connection overlay is up.
 * Lives inside [app.truenascompanion.ui.lock.LockGate], so a locked app never shows server UI under the blur.
 *
 * When the overlay is visible, underlay pointer / nested-scroll / focus input is fully blocked.
 */
@Composable
fun ConnectionOverlayHost(
    container: AppContainer,
    hasSavedServers: Boolean,
    currentRoute: String?,
    onCheckConfig: (serverId: String) -> Unit,
    onActiveChange: (Boolean) -> Unit = {},
    /** 1.7.1: shown on the failure card when more than one server is saved. */
    onSwitchServer: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val connection by container.repository.state.collectAsStateWithLifecycle()
    val active by container.repository.activeServer.collectAsStateWithLifecycle()
    val giveUpMs by container.settings.connectionGiveUpMs.collectAsStateWithLifecycle(
        initialValue = ConnectionTimeoutPrefs.DEFAULT_MS,
    )
    // The server list and editor stay usable while the server is unreachable (fix the address, switch servers).
    val onEdit = currentRoute?.startsWith("server_edit") == true || currentRoute == "servers"
    val serverId = active?.id
    val serverName = active?.name?.takeIf { it.isNotBlank() } ?: active?.urlHost() ?: "your NAS"

    var attemptStartedAt by remember(serverId) { mutableLongStateOf(0L) }
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }

    LaunchedEffect(serverId) {
        attemptStartedAt = 0L
        snapshotFlow { connection }.collectLatest { c ->
            when (c) {
                ConnectionState.Connecting, is ConnectionState.Failed -> {
                    if (attemptStartedAt == 0L) attemptStartedAt = SystemClock.elapsedRealtime()
                }
                else -> attemptStartedAt = 0L
            }
        }
    }
    LaunchedEffect(attemptStartedAt, connection, giveUpMs) {
        if (attemptStartedAt == 0L) return@LaunchedEffect
        while (isActive) {
            now = SystemClock.elapsedRealtime()
            val elapsed = now - attemptStartedAt
            val done = connection !is ConnectionState.Connecting &&
                (connection !is ConnectionState.Failed || elapsed >= giveUpMs)
            if (done && connection !is ConnectionState.Connecting) break
            if (connection is ConnectionState.Connected ||
                connection is ConnectionState.Idle ||
                connection is ConnectionState.NoServer
            ) break
            delay(100)
        }
        now = SystemClock.elapsedRealtime()
    }

    // Auto-retry while still inside the give-up window and the last attempt failed.
    LaunchedEffect(attemptStartedAt, connection, giveUpMs, serverId) {
        if (attemptStartedAt == 0L) return@LaunchedEffect
        while (isActive) {
            val elapsed = SystemClock.elapsedRealtime() - attemptStartedAt
            if (elapsed >= giveUpMs) break
            if (connection is ConnectionState.Connected ||
                connection is ConnectionState.Idle ||
                connection is ConnectionState.NoServer
            ) break
            val failed = connection as? ConnectionState.Failed
            if (failed != null && failed.error !is TrueNasException.LoginRequired) {
                delay(AUTO_RETRY_INTERVAL_MS)
                val still = SystemClock.elapsedRealtime() - attemptStartedAt
                if (still < giveUpMs && container.repository.state.value is ConnectionState.Failed) {
                    container.repository.retryConnection()
                }
            } else {
                delay(200)
            }
        }
    }

    val elapsed = if (attemptStartedAt == 0L) 0L else (now - attemptStartedAt).coerceAtLeast(0L)
    val ui = ConnectionOverlayDecision.decide(
        hasSavedServers = hasSavedServers,
        activeServerName = serverName.takeIf { active != null },
        connection = connection,
        elapsedSinceAttemptMs = elapsed,
        onServerEditScreen = onEdit,
        graceMs = giveUpMs,
    )
    val obscure = ui !is ConnectionOverlayUi.None
    LaunchedEffect(obscure) { onActiveChange(obscure) }
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose { onActiveChange(false) }
    }

    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .obscureBackdrop(obscure)
                .connectionUnderlay(obscure),
        ) { content() }

        val context = LocalContext.current
        AnimatedVisibility(
            visible = obscure,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize(),
        ) {
            ConnectionModalBarrier {
                when (ui) {
                    is ConnectionOverlayUi.Connecting -> ConnectionConnectingPanel(ui.serverName)
                    is ConnectionOverlayUi.Failed -> ConnectionFailurePanel(
                        serverName = ui.serverName,
                        detail = ui.detail,
                        onQuit = {
                            val activity = context.findFragmentActivity() ?: context as? Activity
                            activity?.finishAffinity()
                        },
                        onCheckConfig = {
                            val id = serverId ?: return@ConnectionFailurePanel
                            onCheckConfig(id)
                        },
                        onRetry = {
                            attemptStartedAt = SystemClock.elapsedRealtime()
                            container.repository.retryConnection()
                        },
                        onSwitchServer = onSwitchServer,
                    )
                    ConnectionOverlayUi.None -> {}
                }
            }
        }
    }
}

private fun Modifier.obscureBackdrop(active: Boolean): Modifier {
    if (!active) return this
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        this.blur(28.dp)
    } else {
        this.graphicsLayer {
            scaleX = 0.985f
            scaleY = 0.985f
            alpha = 0.42f
        }
    }
}

/** Scrim + card; root is clickable so the dimmed area never falls through to the blur. */
@Composable
fun ConnectionConnectingPanel(serverName: String) {
    val brand = LocalBrandColors.current
    val accent = if (brand.dark) brand.accent else MaterialTheme.colorScheme.primary
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = if (brand.dark) 0.45f else 0.28f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},
            ),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
            shadowElevation = 8.dp,
            modifier = Modifier
                .padding(28.dp)
                .widthIn(max = 360.dp)
                .fillMaxWidth(),
        ) {
            Column(
                Modifier.padding(horizontal = 28.dp, vertical = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator(
                    Modifier.size(40.dp),
                    color = accent,
                    strokeWidth = 3.dp,
                )
                Spacer(Modifier.height(20.dp))
                Text(
                    "Connecting…",
                    style = MaterialTheme.typography.titleLarge,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Reaching $serverName",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
fun ConnectionFailurePanel(
    serverName: String,
    detail: String,
    onQuit: () -> Unit,
    onCheckConfig: () -> Unit,
    onRetry: (() -> Unit)? = null,
    onSwitchServer: (() -> Unit)? = null,
) {
    val brand = LocalBrandColors.current
    val accent = if (brand.dark) brand.accent else MaterialTheme.colorScheme.primary
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = if (brand.dark) 0.55f else 0.35f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},
            ),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
            shadowElevation = 10.dp,
            modifier = Modifier
                .padding(horizontal = 24.dp, vertical = 16.dp)
                .widthIn(max = 420.dp)
                .fillMaxWidth(),
        ) {
            // 1.7.1 (a11y P0-3): scrolls, so every button stays reachable in landscape and at 200 % font size.
            Column(
                Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Box(
                    Modifier
                        .size(64.dp)
                        .glow(accent, 18.dp, CircleShape, alpha = if (brand.dark) 0.5f else 0.28f)
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.CloudOff, null, tint = accent, modifier = Modifier.size(30.dp))
                }
                Spacer(Modifier.height(16.dp))
                Text(
                    "Can’t connect to $serverName",
                    style = MaterialTheme.typography.headlineSmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.semantics { heading(); liveRegion = LiveRegionMode.Polite },
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    detail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(24.dp))
                // Most common action first and most prominent; labels wrap instead of being cut off.
                if (onRetry != null) {
                    GlowButton(onClick = onRetry, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("connection_try_again")) {
                        Icon(Icons.Rounded.Refresh, null, Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Try again", textAlign = TextAlign.Center)
                    }
                    Spacer(Modifier.height(10.dp))
                    FilledTonalButton(onClick = onCheckConfig, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("connection_check_settings")) {
                        Icon(Icons.Rounded.SettingsEthernet, null, Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Connection settings", textAlign = TextAlign.Center)
                    }
                } else {
                    GlowButton(onClick = onCheckConfig, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("connection_check_settings")) {
                        Icon(Icons.Rounded.SettingsEthernet, null, Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Connection settings", textAlign = TextAlign.Center)
                    }
                }
                if (onSwitchServer != null) {
                    Spacer(Modifier.height(4.dp))
                    TextButton(onClick = onSwitchServer, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("connection_switch_server")) {
                        Icon(Icons.Rounded.SwapHoriz, null, Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Switch server", textAlign = TextAlign.Center)
                    }
                }
                TextButton(onClick = onQuit, modifier = Modifier.heightIn(min = 48.dp).testTag("connection_quit")) {
                    Text("Close app", textAlign = TextAlign.Center)
                }
            }
        }
    }
}

/** Host part of a URL for display when the server has no friendly name. */
private fun app.truenascompanion.data.model.ServerConfig.urlHost(): String =
    runCatching { java.net.URI(url).host }.getOrNull()?.takeIf { it.isNotBlank() } ?: url
