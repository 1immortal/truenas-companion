package app.truenascompanion.ui.shell

import app.truenascompanion.ui.components.GlowButton
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.truenascompanion.data.shell.WebShellProtocol

/** A container that can take a shell (only running ones can). */
data class ShellContainer(val id: String, val name: String, val detail: String?)

private const val DEFAULT = "__default__"
private const val CUSTOM = "__custom__"

/**
 * Picks the container (if there are several) and the program to run, like the web UI's app shell.
 * [defaultLabel] non-null adds a "default shell" option (Incus containers: the NAS picks the instance's shell).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ShellStartDialog(
    title: String,
    containers: List<ShellContainer>,
    onOpen: (containerId: String?, command: String?) -> Unit,
    onDismiss: () -> Unit,
    defaultLabel: String? = null,
    initialCustom: Boolean = false,
) {
    var container by remember { mutableStateOf(containers.firstOrNull()?.id) }
    var choice by remember { mutableStateOf(if (initialCustom) CUSTOM else if (defaultLabel != null) DEFAULT else WebShellProtocol.DEFAULT_COMMAND) }
    var custom by remember { mutableStateOf("") }
    val command: String? = when (choice) {
        DEFAULT -> null
        CUSTOM -> custom.trim()
        else -> choice
    }
    val valid = (containers.isEmpty() || container != null) && (command == null || WebShellProtocol.validCommand(command))
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Terminal, null) },
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (containers.size > 1) {
                    Text("Container", style = MaterialTheme.typography.labelLarge)
                    containers.forEach { c ->
                        Row(Modifier.fillMaxWidth().clickable { container = c.id }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = container == c.id, onClick = { container = c.id })
                            Column(Modifier.weight(1f)) {
                                Text(c.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                c.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                            }
                        }
                    }
                }
                Text("Shell", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    defaultLabel?.let { FilterChip(selected = choice == DEFAULT, onClick = { choice = DEFAULT }, label = { Text(it) }) }
                    WebShellProtocol.COMMANDS.forEach { cmd ->
                        FilterChip(selected = choice == cmd, onClick = { choice = cmd }, label = { Text(cmd, fontFamily = FontFamily.Monospace) })
                    }
                    FilterChip(selected = choice == CUSTOM, onClick = { choice = CUSTOM }, label = { Text("Custom") })
                }
                if (choice == CUSTOM) OutlinedTextField(
                    value = custom, onValueChange = { custom = it }, singleLine = true, label = { Text("Program") },
                    placeholder = { Text("/bin/ash", fontFamily = FontFamily.Monospace) },
                    isError = custom.isNotBlank() && !WebShellProtocol.validCommand(custom.trim()),
                    supportingText = { Text("One program path, no arguments (the NAS runs it as is).") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Minimal images often have only /bin/sh. The shell closes when you leave it, and whatever is still running in it stops.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { GlowButton(enabled = valid, onClick = { onOpen(container, command) }) { Text("Open shell") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
