package app.truenascompanion.ui.storage

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DriveFileRenameOutline
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.truenascompanion.data.api.StorageApi
import app.truenascompanion.data.model.Dataset
import app.truenascompanion.data.model.DatasetCreateRequest
import app.truenascompanion.data.model.Health
import app.truenascompanion.data.model.Pool
import app.truenascompanion.ui.components.CapacityBar
import app.truenascompanion.ui.components.ConfirmDialog
import app.truenascompanion.ui.components.ElevatedSection
import app.truenascompanion.ui.components.EmptyState
import app.truenascompanion.ui.components.GlowButton
import app.truenascompanion.ui.components.LabeledValue
import app.truenascompanion.ui.components.StatusChip
import app.truenascompanion.util.Format

private val listPadding = PaddingValues(16.dp)

@Composable
fun DatasetsPane(
    datasets: List<Dataset>,
    pools: List<Pool>,
    busy: Set<String>,
    onOpenSnapshots: (String) -> Unit,
    onCreate: (DatasetCreateRequest) -> Unit,
    onRename: (id: String, newName: String) -> Unit,
    onDelete: (id: String, recursive: Boolean, force: Boolean) -> Unit,
) {
    var showSystem by rememberSaveable { mutableStateOf(false) }
    var createFor by remember { mutableStateOf<String?>(null) }
    var renameTarget by remember { mutableStateOf<Dataset?>(null) }
    var deleteTarget by remember { mutableStateOf<Dataset?>(null) }
    val list = if (showSystem) datasets else datasets.filterNot { it.isSystem }

    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
        LazyColumn(contentPadding = listPadding, verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxSize()) {
            item {
                FilterChip(selected = showSystem, onClick = { showSystem = !showSystem }, label = { Text("Show system datasets") })
                Text(
                    "Tap a dataset for snapshots. Use ⋮ to rename or delete. + creates under a pool or parent.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (list.isEmpty()) {
                item { EmptyState(Icons.Rounded.Folder, "No datasets", "Create a dataset or ZVOL with the + button.") }
            }
            items(list, key = { it.id }) { d ->
                DatasetCard(
                    d = d,
                    busy = d.id in busy,
                    onOpen = { onOpenSnapshots(d.id) },
                    onCreateChild = { createFor = d.id },
                    onRename = { renameTarget = d },
                    onDelete = { deleteTarget = d },
                )
            }
            item { Spacer(Modifier.height(72.dp)) }
        }
        FloatingActionButton(
            onClick = { createFor = pools.firstOrNull()?.name ?: "" },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        ) { Icon(Icons.Rounded.Add, "Create dataset") }
    }

    createFor?.let { parent ->
        CreateDatasetDialog(
            parentHint = parent,
            poolNames = pools.map { it.name },
            onDismiss = { createFor = null },
            onConfirm = { onCreate(it); createFor = null },
        )
    }
    renameTarget?.let { d ->
        RenameDatasetDialog(d, onDismiss = { renameTarget = null }, onConfirm = { onRename(d.id, it); renameTarget = null })
    }
    deleteTarget?.let { d ->
        DeleteDatasetDialogWithOptions(d, onDismiss = { deleteTarget = null }, onConfirm = { r, f -> onDelete(d.id, r, f); deleteTarget = null })
    }
}

@Composable
private fun DatasetCard(
    d: Dataset,
    busy: Boolean,
    onOpen: () -> Unit,
    onCreateChild: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    ElevatedSection(contentPadding = 14.dp, onClick = onOpen, modifier = Modifier.padding(start = (d.depth.coerceAtMost(4) * 12).dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                when {
                    d.locked -> Icons.Rounded.Lock
                    d.isVolume -> Icons.Rounded.Album
                    else -> Icons.Rounded.Folder
                },
                null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(if (d.depth == 0) d.id else d.shortName, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    buildString {
                        append(d.id)
                        if (d.isVolume) append(" · ZVOL")
                        d.compression?.takeIf { !it.equals("OFF", true) && !it.equals("INHERIT", true) }?.let { append(" · $it") }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (d.encrypted) StatusChip(if (d.locked) Health.WARNING else Health.HEALTHY, if (d.locked) "Locked" else "Encrypted", showIcon = false)
            IconButton(onClick = { menu = true }, enabled = !busy) { Icon(Icons.Rounded.MoreVert, "Actions") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                if (!d.isVolume) DropdownMenuItem(text = { Text("Create child") }, onClick = { menu = false; onCreateChild() }, leadingIcon = { Icon(Icons.Rounded.Add, null) })
                if (d.depth > 0) DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; onRename() }, leadingIcon = { Icon(Icons.Rounded.DriveFileRenameOutline, null) })
                if (d.depth > 0) DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; onDelete() }, leadingIcon = { Icon(Icons.Rounded.Delete, null) })
            }
        }
        Spacer(Modifier.height(10.dp))
        CapacityBar(d.usedFraction, height = 8.dp)
        Spacer(Modifier.height(4.dp))
        Text(
            buildString {
                append("${Format.bytes(d.used)} used · ${Format.bytes(d.available)} available")
                d.compressratio?.takeIf { it.isNotBlank() && it != "1.00x" }?.let { append(" · $it") }
                d.volsize?.let { append(" · size ${Format.bytes(it)}") }
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        d.comments?.let {
            Spacer(Modifier.height(4.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreateDatasetDialog(
    parentHint: String,
    poolNames: List<String>,
    onDismiss: () -> Unit,
    onConfirm: (DatasetCreateRequest) -> Unit,
) {
    var parent by rememberSaveable { mutableStateOf(parentHint.ifBlank { poolNames.firstOrNull().orEmpty() }) }
    var name by rememberSaveable { mutableStateOf("") }
    var asZvol by rememberSaveable { mutableStateOf(false) }
    var shareType by rememberSaveable { mutableStateOf("GENERIC") }
    var compression by rememberSaveable { mutableStateOf("INHERIT") }
    var comments by rememberSaveable { mutableStateOf("") }
    var volGiB by rememberSaveable { mutableStateOf("10") }
    var sparse by rememberSaveable { mutableStateOf(true) }
    val child = name.trim()
    val full = if (parent.isBlank()) child else "$parent/$child"
    val valid = child.isNotEmpty() && !child.contains('/') && parent.isNotEmpty() &&
        (!asZvol || (volGiB.toDoubleOrNull()?.let { it > 0 } == true))

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (asZvol) "New ZVOL" else "New dataset") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(parent, { parent = it }, label = { Text("Parent") }, singleLine = true, modifier = Modifier.fillMaxWidth(), supportingText = { Text("Pool or parent dataset") })
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(asZvol, { asZvol = it })
                    Text("Create as ZVOL (block device)", style = MaterialTheme.typography.bodyMedium)
                }
                if (!asZvol) {
                    Text("Share type", style = MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        StorageApi.SHARE_TYPES.take(4).forEach { t ->
                            FilterChip(selected = shareType == t, onClick = { shareType = t }, label = { Text(t) })
                        }
                    }
                } else {
                    OutlinedTextField(
                        volGiB, { volGiB = it.filter { c -> c.isDigit() || c == '.' } },
                        label = { Text("Size (GiB)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(sparse, { sparse = it })
                        Text("Sparse (thin provision)", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Text("Compression", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("INHERIT", "LZ4", "ZSTD", "OFF").forEach { c ->
                        FilterChip(selected = compression == c, onClick = { compression = c }, label = { Text(c) })
                    }
                }
                OutlinedTextField(comments, { comments = it }, label = { Text("Comments (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("Will create: $full", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            GlowButton(
                onClick = {
                    onConfirm(
                        DatasetCreateRequest(
                            name = full,
                            type = if (asZvol) "VOLUME" else "FILESYSTEM",
                            shareType = shareType,
                            compression = compression,
                            comments = comments.trim().ifBlank { null },
                            volsize = if (asZvol) ((volGiB.toDoubleOrNull() ?: 0.0) * (1L shl 30)).toLong() else null,
                            sparse = sparse,
                        ),
                    )
                },
                enabled = valid,
            ) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun RenameDatasetDialog(d: Dataset, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    val parent = d.id.substringBeforeLast('/', missingDelimiterValue = "")
    var leaf by rememberSaveable { mutableStateOf(d.shortName) }
    val newName = if (parent.isEmpty()) leaf.trim() else "$parent/${leaf.trim()}"
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename ${d.shortName}") },
        text = {
            Column {
                OutlinedTextField(leaf, { leaf = it }, label = { Text("New name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("Full path: $newName", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            GlowButton(onClick = { onConfirm(newName) }, enabled = leaf.trim().isNotEmpty() && newName != d.id) { Text("Rename") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun DeleteDatasetDialogWithOptions(d: Dataset, onDismiss: () -> Unit, onConfirm: (recursive: Boolean, force: Boolean) -> Unit) {
    var recursive by rememberSaveable { mutableStateOf(false) }
    var force by rememberSaveable { mutableStateOf(false) }
    val guard = app.truenascompanion.ui.lock.LocalDangerGuard.current
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Delete, null) },
        title = { Text("Delete ${d.shortName}?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("This permanently deletes ${d.id} and its data.")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(recursive, { recursive = it })
                    Text("Also delete children", style = MaterialTheme.typography.bodyMedium)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(force, { force = it })
                    Text("Force (ignore busy)", style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.Button(
                onClick = { guard.guard("Delete dataset") { onConfirm(recursive, force) } },
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
            ) { Text("Delete") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
