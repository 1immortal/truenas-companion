package app.truenascompanion.ui.apps.form

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import app.truenascompanion.ui.components.ElevatedSection
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * The generated install / edit form: one collapsible card per question group. Stateless; [values] is the whole
 * values tree and every change produces a new tree via [onChange].
 */
@Composable
fun AppFormGroups(
    groups: List<FormGroup>,
    values: JsonObject,
    editing: Boolean,
    issues: Map<String, String>,
    onChange: (ValuePath, JsonElement) -> Unit,
    onRemove: (ValuePath, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        groups.forEachIndexed { i, g ->
            val shown = g.questions.filter { AppForm.visible(it.schema, values) }
            if (shown.isEmpty()) return@forEachIndexed
            var open by rememberSaveable(g.name) { mutableStateOf(i == 0) }
            val groupIssues = issues.keys.count { k -> shown.any { k == it.variable || k.startsWith(it.variable + ".") || k.startsWith(it.variable + "[") } }
            ElevatedSection(modifier = Modifier.animateContentSize(), contentPadding = 14.dp) {
                Row(Modifier.fillMaxWidth().clickable { open = !open }.heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(g.name, style = MaterialTheme.typography.titleMedium)
                        if (groupIssues > 0) Text("$groupIssues to fix", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
                        else if (!open) g.description?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1) }
                    }
                    Icon(if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, if (open) "Collapse" else "Expand")
                }
                AnimatedVisibility(open || groupIssues > 0) {
                    Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        shown.forEach { q ->
                            QuestionField(q, listOf(PathKey.Key(q.variable)), values[q.variable], editing, issues, onChange, onRemove)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun QuestionField(
    q: Question,
    path: ValuePath,
    value: JsonElement?,
    editing: Boolean,
    issues: Map<String, String>,
    onChange: (ValuePath, JsonElement) -> Unit,
    onRemove: (ValuePath, Int) -> Unit,
) {
    val s = q.schema
    val readOnly = !s.editable || (editing && s.immutable)
    val error = issues[path.display()]
    when {
        !s.supported -> UnsupportedField(q, value)
        s.type == "dict" -> {
            val obj = value as? JsonObject ?: JsonObject(emptyMap())
            val children = s.attrs.filter { AppForm.visible(it.schema, obj) }
            if (children.isEmpty()) return
            val body: @Composable () -> Unit = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    children.forEach { c -> QuestionField(c, path + PathKey.Key(c.variable), obj[c.variable], editing, issues, onChange, onRemove) }
                }
            }
            if (q.label.isBlank() || q.label == q.variable && path.size == 1) body()
            else Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FieldTitle(q.label, q.description)
                Row {
                    Box(Modifier.padding(start = 2.dp, end = 12.dp).width(2.dp).heightIn(min = 24.dp)) {
                        Surface(color = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f), modifier = Modifier.fillMaxWidth().heightIn(min = 24.dp)) {}
                    }
                    Box(Modifier.weight(1f)) { body() }
                }
            }
        }
        s.type == "list" -> ListField(q, path, value as? JsonArray ?: JsonArray(emptyList()), editing, issues, onChange, onRemove, readOnly)
        s.type == "boolean" -> Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(q.label, style = MaterialTheme.typography.bodyLarge)
                q.description?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            Spacer(Modifier.width(12.dp))
            Switch(checked = (value as? JsonPrimitive)?.booleanOrNull == true, onCheckedChange = { onChange(path, JsonPrimitive(it)) }, enabled = !readOnly)
        }
        s.enum.isNotEmpty() -> EnumField(q, value, readOnly, error) { onChange(path, it) }
        else -> TextField(q, value, readOnly, error) { onChange(path, AppForm.parseInput(s, it)) }
    }
}

@Composable
private fun FieldTitle(label: String, description: String?) {
    Column {
        Text(label, style = MaterialTheme.typography.titleSmall)
        description?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun TextField(q: Question, value: JsonElement?, readOnly: Boolean, error: String?, onText: (String) -> Unit) {
    val s = q.schema
    val text = value?.takeUnless { it is JsonNull }?.display().orEmpty()
    var reveal by remember { mutableStateOf(false) }
    if (s.private) app.truenascompanion.ui.components.SecureWindowEffect()
    OutlinedTextField(
        value = text, onValueChange = onText,
        label = { Text(q.label + if (s.required) " *" else "") },
        enabled = !readOnly,
        singleLine = s.type != "text",
        minLines = if (s.type == "text") 3 else 1,
        isError = error != null,
        supportingText = (error ?: q.description)?.let { { Text(it) } },
        visualTransformation = if (s.private && !reveal) PasswordVisualTransformation() else VisualTransformation.None,
        trailingIcon = if (s.private) ({
            IconButton(onClick = { reveal = !reveal }) { Icon(if (reveal) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, if (reveal) "Hide" else "Show") }
        }) else null,
        keyboardOptions = KeyboardOptions(
            keyboardType = when {
                s.type == "int" -> KeyboardType.Number
                s.type == "float" -> KeyboardType.Decimal
                s.type == "uri" -> KeyboardType.Uri
                s.private -> KeyboardType.Password
                else -> KeyboardType.Text
            },
        ),
        textStyle = if (s.type == "path" || s.type == "hostpath") MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace) else MaterialTheme.typography.bodyLarge,
        modifier = Modifier.fillMaxWidth(),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EnumField(q: Question, value: JsonElement?, readOnly: Boolean, error: String?, onPick: (JsonElement) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val current = q.schema.enum.firstOrNull { AppForm.matches(value ?: JsonNull, "=", it.value) }
    ExposedDropdownMenuBox(expanded = open, onExpandedChange = { if (!readOnly) open = it }) {
        OutlinedTextField(
            value = current?.label ?: value?.takeUnless { it is JsonNull }?.display().orEmpty(),
            onValueChange = {}, readOnly = true, enabled = !readOnly,
            label = { Text(q.label + if (q.schema.required) " *" else "") },
            isError = error != null,
            supportingText = (error ?: q.description)?.let { { Text(it) } },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = open) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable, enabled = !readOnly),
        )
        ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            q.schema.enum.forEach { o ->
                DropdownMenuItem(text = { Text(o.label) }, onClick = { open = false; onPick(o.value) })
            }
        }
    }
}

@Composable
private fun ListField(
    q: Question,
    path: ValuePath,
    list: JsonArray,
    editing: Boolean,
    issues: Map<String, String>,
    onChange: (ValuePath, JsonElement) -> Unit,
    onRemove: (ValuePath, Int) -> Unit,
    readOnly: Boolean,
) {
    val item = q.schema.items.singleOrNull()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FieldTitle(q.label, q.description)
        if (item == null) { UnsupportedField(q, list); return@Column }
        list.forEachIndexed { i, e ->
            Surface(
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
            ) {
                Column(Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${item.label.ifBlank { "Item" }} ${i + 1}", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                        if (!readOnly) IconButton(onClick = { onRemove(path, i) }) { Icon(Icons.Rounded.Close, "Remove") }
                    }
                    Box(Modifier.padding(end = 8.dp)) {
                        QuestionField(item.copy(label = if (item.schema.type == "dict") "" else item.label), path + PathKey.Index(i), e, editing, issues, onChange, onRemove)
                    }
                }
            }
        }
        if (!readOnly) TextButton(onClick = { onChange(path + PathKey.Index(list.size), AppForm.newItem(q.schema)) }) {
            Icon(Icons.Rounded.Add, null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Add ${item.label.ifBlank { "item" }.lowercase()}", maxLines = 1)
        }
    }
}

/** A field type the form can't render: the current/default value is kept and shown read-only. */
@Composable
private fun UnsupportedField(q: Question, value: JsonElement?) {
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
            Icon(Icons.Rounded.Info, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Column {
                Text(q.label, style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Kept as ${value?.takeUnless { it is JsonNull }?.display()?.take(80)?.ifBlank { null } ?: "default"}. Use Edit as JSON to change it.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
