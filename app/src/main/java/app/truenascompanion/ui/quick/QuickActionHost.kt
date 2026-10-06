package app.truenascompanion.ui.quick

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.truenascompanion.AppContainer
import app.truenascompanion.data.api.ProtectionApi
import app.truenascompanion.data.api.userMessage
import app.truenascompanion.data.model.AppAction
import app.truenascompanion.data.model.AppState
import app.truenascompanion.quick.QuickAction
import app.truenascompanion.quick.QuickActions
import app.truenascompanion.ui.components.ConfirmDialog
import kotlinx.coroutines.launch

/** A quick action from an app shortcut or the action tile, waiting for the user's confirmation. */
data class QuickRequest(val action: QuickAction, val arg: String? = null, val nonce: Long = System.nanoTime())

data class PickItem(val key: String, val title: String, val subtitle: String?, val enabled: Boolean = true)

/**
 * Shows the confirmation for a quick action (1.2.0). Nothing runs without the user confirming here, and with the app
 * lock on the fingerprint/face prompt follows (ConfirmDialog strongAuth). Restart/scrub let the user pick the target,
 * preselecting the one a dynamic shortcut carries.
 */
@Composable
fun QuickActionHost(container: AppContainer, request: QuickRequest?, onDone: () -> Unit, onOpenShell: () -> Unit) {
    val r = request ?: return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val server by container.repository.activeServer.collectAsState()
    val serverName = server?.name ?: "your NAS"
    val serverId = server?.id
    fun toast(t: String) = Toast.makeText(context, t, Toast.LENGTH_LONG).show()

    when (r.action) {
        QuickAction.SHELL -> ConfirmDialog(
            title = "Open a shell on $serverName?",
            text = "Opens a root shell on the NAS. Commands run with full privileges.",
            confirmLabel = "Open shell", strongAuth = true, icon = Icons.Rounded.Terminal,
            onConfirm = { onDone(); onOpenShell() }, onDismiss = onDone,
        )
        QuickAction.ALERTS -> LaunchedEffect(r) { onDone() }
        QuickAction.RESTART_APP, QuickAction.SCRUB_POOL -> {
            val restart = r.action == QuickAction.RESTART_APP
            var items by remember(r) { mutableStateOf<List<PickItem>?>(null) }
            var loadError by remember(r) { mutableStateOf<String?>(null) }
            var selected by remember(r) { mutableStateOf(r.arg) }
            LaunchedEffect(r) {
                try {
                    items = if (restart) {
                        container.repository.call { it.apps() }.sortedWith(compareBy({ it.state != AppState.RUNNING && it.state != AppState.CRASHED }, { it.name }))
                            .map { PickItem(it.name, it.name, it.state.name.lowercase().replaceFirstChar { c -> c.uppercase() }, enabled = it.state != AppState.DEPLOYING && it.state != AppState.STOPPING) }
                    } else {
                        container.repository.call { it.pools() }.map { p ->
                            PickItem(p.name, p.name, if (p.scrubRunning) "Scrub running" else p.status, enabled = !p.scrubRunning)
                        }
                    }
                    if (selected == null || items?.none { it.key == selected && it.enabled } == true) selected = items?.firstOrNull { it.enabled }?.key
                } catch (e: Throwable) {
                    loadError = e.userMessage()
                }
            }
            QuickPickDialog(
                title = if (restart) "Restart an app" else "Scrub a pool",
                text = if (restart) "The app's containers stop and start again on $serverName; it's unavailable for a moment."
                else "Reads every block on the pool to find and repair errors. It runs in the background and can take hours; the pool stays usable but slower.",
                confirmLabel = if (restart) "Restart" else "Start scrub",
                icon = if (restart) Icons.Rounded.RestartAlt else Icons.Rounded.Storage,
                items = items, error = loadError, selected = selected, onSelect = { selected = it },
                onDismiss = onDone,
                onConfirm = {
                    val target = selected ?: return@QuickPickDialog
                    onDone()
                    scope.launch {
                        try {
                            if (restart) {
                                val app = container.repository.call { it.apps() }.firstOrNull { it.name == target } ?: error("$target isn't installed")
                                container.repository.call { it.appAction(app, AppAction.RESTART) }
                                toast("Restarting $target…")
                            } else {
                                container.repository.call { ProtectionApi(it).startScrub(target) }
                                toast("Scrub started on $target")
                            }
                            serverId?.let { QuickActions.pushRecent(context, r.action, it, target) }
                        } catch (e: Throwable) {
                            toast(e.userMessage())
                        }
                    }
                },
            )
        }
    }
}

/** Confirmation with a target list (stateless; screenshot tests render it). */
@Composable
fun QuickPickDialog(
    title: String, text: String, confirmLabel: String, icon: androidx.compose.ui.graphics.vector.ImageVector,
    items: List<PickItem>?, error: String?, selected: String?, onSelect: (String) -> Unit, onDismiss: () -> Unit, onConfirm: () -> Unit,
) {
    ConfirmDialog(
        title = title, text = text, confirmLabel = confirmLabel, strongAuth = true, icon = icon,
        onConfirm = { if (selected != null) onConfirm() }, onDismiss = onDismiss,
        extra = {
            Spacer(Modifier.height(8.dp))
            when {
                error != null -> Text(error, color = MaterialTheme.colorScheme.error)
                items == null -> Box(Modifier.fillMaxWidth().height(80.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                items.isEmpty() -> Text("Nothing to choose from.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> items.take(30).forEach { item ->
                    Row(
                        Modifier.fillMaxWidth().selectable(selected = selected == item.key, enabled = item.enabled, role = Role.RadioButton) { onSelect(item.key) }.padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = selected == item.key, onClick = null, enabled = item.enabled)
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            item.subtitle?.let { s -> Text(s, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                    }
                }
            }
        },
    )
}
