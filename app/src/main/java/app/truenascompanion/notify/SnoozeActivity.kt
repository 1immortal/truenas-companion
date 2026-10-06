package app.truenascompanion.notify

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.clickable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import app.truenascompanion.TrueNasApp
import app.truenascompanion.data.store.AppearanceSettings
import app.truenascompanion.ui.theme.TrueNasTheme
import kotlinx.coroutines.launch

/**
 * Small dialog behind the notification's "Snooze" action (1.2.0). Snoozes are stored on the phone per alert id
 * (TrueNAS has no snooze); the alert is notified again by the first check after the snooze ends.
 */
class SnoozeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val serverId = intent.getStringExtra(EXTRA_SERVER_ID)
        val uuids = intent.getStringArrayExtra(EXTRA_UUIDS)?.toList().orEmpty()
        if (serverId == null || uuids.isEmpty()) { finish(); return }
        val container = (application as TrueNasApp).container
        setContent {
            val appearance by container.settings.appearance.collectAsStateWithLifecycle(initialValue = AppearanceSettings())
            TrueNasTheme(themeMode = appearance.themeMode, dynamicColor = appearance.dynamicColor) {
                SnoozeDialog(count = uuids.size, onPick = { option ->
                    lifecycleScope.launch {
                        snooze(container.settings, serverId, uuids, System.currentTimeMillis() + option.millis)
                        container.notifier.removeFromShade(serverId, uuids.toSet())
                        container.notifier.updateSummary(serverId, intent.getStringExtra(EXTRA_SERVER_NAME) ?: "TrueNAS")
                        finish()
                    }
                }, onDismiss = { finish() })
            }
        }
    }

    companion object {
        const val EXTRA_SERVER_ID = "server_id"
        const val EXTRA_UUIDS = "uuids"
        const val EXTRA_TAG = "tag"
        const val EXTRA_SERVER_NAME = "server_name"

        suspend fun snooze(settings: app.truenascompanion.data.store.SettingsStore, serverId: String, uuids: List<String>, until: Long) {
            settings.updateSnoozes(serverId) { cur -> cur + uuids.associateWith { until } }
        }
    }
}

@androidx.compose.runtime.Composable
fun SnoozeDialog(count: Int, onPick: (Snooze.Option) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (count > 1) "Snooze $count alerts" else "Snooze alert") },
        text = {
            Column {
                Text("You won't be notified again until the snooze ends. Snoozes stay on this phone.")
                Snooze.OPTIONS.forEach { o ->
                    ListItem(
                        headlineContent = { Text(o.label) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.fillMaxWidth().clickable { onPick(o) },
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
