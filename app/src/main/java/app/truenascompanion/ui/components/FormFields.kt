package app.truenascompanion.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Masked text field with a Show/Hide button (passwords, community strings, monitor passwords). [hiddenByServer]: TrueNAS
 * didn't send the current value; the field starts empty and keeps the saved secret unless something is typed.
 */
@Composable
fun SecretTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
    supporting: String? = null,
    singleLine: Boolean = true,
    hiddenByServer: Boolean = false,
) {
    var shown by rememberSaveable { mutableStateOf(false) }
    app.truenascompanion.ui.components.SecureWindowEffect()
    OutlinedTextField(
        value = value, onValueChange = onValueChange, singleLine = singleLine, isError = isError,
        label = { Text(label, maxLines = 1) },
        placeholder = if (hiddenByServer) ({ Text("Unchanged") }) else null,
        visualTransformation = if (shown) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
        // A secret TrueNAS won't reveal (pydantic Secret): say so even when the field isn't focused.
        supportingText = (if (hiddenByServer && !isError) HIDDEN_BY_SERVER else supporting)?.let { { Text(it) } },
        trailingIcon = {
            IconButton(onClick = { shown = !shown }, modifier = Modifier.testTag("secret-toggle")) {
                Icon(if (shown) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, if (shown) "Hide" else "Show")
            }
        },
        modifier = modifier.fillMaxWidth().testTag("secret:$label"),
    )
}

const val HIDDEN_BY_SERVER = "Hidden by TrueNAS. Leave empty to keep it unchanged."

/** Labeled choice. Short lists use a dropdown; long ones (e.g. UPS drivers) open a searchable list. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> PickField(
    label: String,
    selected: T?,
    options: List<T>,
    optionLabel: (T) -> String,
    onPick: (T) -> Unit,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
    supporting: String? = null,
    emptyText: String = "—",
) {
    val shown = selected?.let(optionLabel) ?: emptyText
    if (options.size > 14) {
        var open by remember { mutableStateOf(false) }
        Box(modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = shown, onValueChange = {}, readOnly = true, singleLine = true, isError = isError,
                label = { Text(label, maxLines = 1) }, supportingText = supporting?.let { { Text(it) } },
                trailingIcon = { Icon(Icons.Rounded.Search, null) }, modifier = Modifier.fillMaxWidth(),
            )
            Box(Modifier.matchParentSize().clickable { open = true })
        }
        if (open) SearchPickDialog(label, options, optionLabel, onDismiss = { open = false }) { open = false; onPick(it) }
        return
    }
    var open by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = open, onExpandedChange = { open = it && options.isNotEmpty() }, modifier = modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = shown, onValueChange = {}, readOnly = true, singleLine = true, isError = isError,
            label = { Text(label, maxLines = 1) }, supportingText = supporting?.let { { Text(it) } },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(open) },
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { o -> DropdownMenuItem(text = { Text(optionLabel(o), maxLines = 2, overflow = TextOverflow.Ellipsis) }, onClick = { open = false; onPick(o) }) }
        }
    }
}

@Composable
private fun <T> SearchPickDialog(title: String, options: List<T>, optionLabel: (T) -> String, onDismiss: () -> Unit, onPick: (T) -> Unit) {
    var query by remember { mutableStateOf("") }
    val shown = remember(query, options) { options.filter { query.isBlank() || optionLabel(it).contains(query.trim(), ignoreCase = true) } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(query, { query = it }, singleLine = true, placeholder = { Text("Search") }, leadingIcon = { Icon(Icons.Rounded.Search, null) }, modifier = Modifier.fillMaxWidth())
                LazyColumn(Modifier.heightIn(max = 380.dp)) {
                    items(shown) { o ->
                        Text(
                            optionLabel(o), style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.fillMaxWidth().clickable { onPick(o) }.padding(vertical = 10.dp, horizontal = 4.dp),
                        )
                    }
                    if (shown.isEmpty()) item { Text("No matches", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(8.dp)) }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
