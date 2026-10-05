package app.truenascompanion.ui.connection

import android.app.Activity
import android.os.Build
import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.truenascompanion.AppContainer
import app.truenascompanion.data.api.TrueNasException
import app.truenascompanion.data.repository.ConnectionState
import app.truenascompanion.ui.components.GlowButton
import app.truenascompanion.ui.components.glow
import app.truenascompanion.ui.lock.findFragmentActivity
import app.truenascompanion.ui.theme.LocalBrandColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive

/** How long to keep a calm “Connecting…” state before revealing the failure overlay. */
const val CONNECTION_OVERLAY_GRACE_MS = 2_500L

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
 * - Failed only after [graceMs] so a brief connecting state shows first.
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
        else ->
            "Make sure the NAS is online and that your phone can reach it — on Wi‑Fi at home, or through your VPN."
    }
}

/**
 * Blurs (API 31+) or dims/scales (older) [content] while a connection overlay is up.
 * Lives inside [app.truenascompanion.ui.lock.LockGate], so a locked app never shows server UI under the blur.
 */
@Composable
fun ConnectionOverlayHost(
    container: AppContainer,
    hasSavedServers: Boolean,
    currentRoute: String?,
    onCheckConfig: (serverId: String) -> Unit,
    content: @Composable () -> Unit,
) {
    val connection by container.repository.state.collectAsStateWithLifecycle()
    val active by container.repository.activeServer.collectAsStateWithLifecycle()
    val onEdit = currentRoute?.startsWith("server_edit") == true
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
    LaunchedEffect(attemptStartedAt, connection) {
        if (attemptStartedAt == 0L) return@LaunchedEffect
        while (isActive) {
            now = SystemClock.elapsedRealtime()
            val elapsed = now - attemptStartedAt
            val done = connection !is ConnectionState.Connecting &&
                (connection !is ConnectionState.Failed || elapsed >= CONNECTION_OVERLAY_GRACE_MS)
            if (done && connection !is ConnectionState.Connecting) break
            if (connection is ConnectionState.Connected ||
                connection is ConnectionState.Idle ||
                connection is ConnectionState.NoServer
            ) break
            delay(100)
        }
        now = SystemClock.elapsedRealtime()
    }

    val elapsed = if (attemptStartedAt == 0L) 0L else (now - attemptStartedAt).coerceAtLeast(0L)
    val ui = ConnectionOverlayDecision.decide(
        hasSavedServers = hasSavedServers,
        activeServerName = serverName.takeIf { active != null },
        connection = connection,
        elapsedSinceAttemptMs = elapsed,
        onServerEditScreen = onEdit,
    )
    val obscure = ui !is ConnectionOverlayUi.None

    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .obscureBackdrop(obscure),
        ) { content() }

        val context = LocalContext.current
        AnimatedVisibility(
            visible = obscure,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize(),
        ) {
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
                    onRetry = { container.repository.retryConnection() },
                )
                ConnectionOverlayUi.None -> {}
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

@Composable
fun ConnectionConnectingPanel(serverName: String) {
    val brand = LocalBrandColors.current
    val accent = if (brand.dark) brand.accent else MaterialTheme.colorScheme.primary
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = if (brand.dark) 0.45f else 0.28f)),
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
) {
    val brand = LocalBrandColors.current
    val accent = if (brand.dark) brand.accent else MaterialTheme.colorScheme.primary
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = if (brand.dark) 0.55f else 0.35f)),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
            shadowElevation = 10.dp,
            modifier = Modifier
                .padding(28.dp)
                .widthIn(max = 400.dp)
                .fillMaxWidth(),
        ) {
            Column(
                Modifier.padding(horizontal = 24.dp, vertical = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Box(
                    Modifier
                        .size(72.dp)
                        .glow(accent, 18.dp, CircleShape, alpha = if (brand.dark) 0.5f else 0.28f)
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.CloudOff, null, tint = accent, modifier = Modifier.size(34.dp))
                }
                Spacer(Modifier.height(20.dp))
                Text(
                    "Can’t connect to $serverName",
                    style = MaterialTheme.typography.headlineSmall,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    detail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(28.dp))
                GlowButton(onClick = onCheckConfig, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.SettingsEthernet, null, Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Check connection settings", maxLines = 1)
                }
                Spacer(Modifier.height(10.dp))
                OutlinedButton(onClick = onQuit, modifier = Modifier.fillMaxWidth()) {
                    Text("Quit app", maxLines = 1)
                }
                if (onRetry != null) {
                    Spacer(Modifier.height(4.dp))
                    TextButton(onClick = onRetry) { Text("Try again") }
                }
            }
        }
    }
}

/** Host part of a URL for display when the server has no friendly name. */
private fun app.truenascompanion.data.model.ServerConfig.urlHost(): String =
    runCatching { java.net.URI(url).host }.getOrNull()?.takeIf { it.isNotBlank() } ?: url
