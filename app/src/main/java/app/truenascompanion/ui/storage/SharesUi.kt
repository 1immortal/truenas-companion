package app.truenascompanion.ui.storage

import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import app.truenascompanion.ui.components.CheckRow
import app.truenascompanion.ui.components.FullScreenEditor
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.FolderShared
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Share
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
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.truenascompanion.data.api.StorageApi
import app.truenascompanion.data.model.Dataset
import app.truenascompanion.data.model.Health
import app.truenascompanion.data.model.NfsShare
import app.truenascompanion.data.model.NfsShareInput
import app.truenascompanion.data.model.SmbShare
import app.truenascompanion.data.model.SmbShareInput
import app.truenascompanion.ui.components.ConfirmDialog
import app.truenascompanion.ui.components.ElevatedSection
import app.truenascompanion.ui.components.EmptyState
import app.truenascompanion.ui.components.GlowButton
import app.truenascompanion.ui.components.IconBadge
import app.truenascompanion.ui.components.StatusChip
import app.truenascompanion.ui.lock.LocalDangerGuard

private val listPadding = PaddingValues(16.dp)

data class SharesData(val smb: List<SmbShare>, val nfs: List<NfsShare>)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SharesPane(
    data: SharesData,
    datasets: List<Dataset>,
    busy: Set<String>,
    onCreateSmb: (SmbShareInput) -> Unit,
    onUpdateSmb: (Int, SmbShareInput) -> Unit,
    onDeleteSmb: (Int) -> Unit,
    onCreateNfs: (NfsShareInput) -> Unit,
    onUpdateNfs: (Int, NfsShareInput) -> Unit,
    onDeleteNfs: (Int) -> Unit,
    onBrowse: (String) -> Unit = {},
    /** Third sub-tab (iSCSI block shares), loaded separately; null hides the tab. */
    iscsiContent: (@Composable () -> Unit)? = null,
) {
    var sub by rememberSaveable { mutableIntStateOf(0) }
    var editingSmb by remember { mutableStateOf<SmbShare?>(null) }
    var creatingSmb by remember { mutableStateOf(false) }
    var deleteSmb by remember { mutableStateOf<SmbShare?>(null) }
    var editingNfs by remember { mutableStateOf<NfsShare?>(null) }
    var creatingNfs by remember { mutableStateOf(false) }
    var deleteNfs by remember { mutableStateOf<NfsShare?>(null) }
    val paths = datasets.filter { !it.isVolume && !it.isSystem && it.mountpoint != null }.map { it.id }

    Column(Modifier.fillMaxSize()) {
        PrimaryTabRow(selectedTabIndex = sub) {
            Tab(selected = sub == 0, onClick = { sub = 0 }, text = { Text("SMB (${data.smb.size})") })
            Tab(selected = sub == 1, onClick = { sub = 1 }, text = { Text("NFS (${data.nfs.size})") })
            if (iscsiContent != null) Tab(selected = sub == 2, onClick = { sub = 2 }, text = { Text("iSCSI") })
        }
        Box(Modifier.fillMaxSize()) {
            when (sub) {
                0 -> SmbList(data.smb, busy, onEdit = { editingSmb = it }, onDelete = { deleteSmb = it }, onBrowse = onBrowse)
                1 -> NfsList(data.nfs, busy, onEdit = { editingNfs = it }, onDelete = { deleteNfs = it }, onBrowse = onBrowse)
                else -> iscsiContent?.invoke()
            }
            if (sub < 2) FloatingActionButton(
                onClick = { if (sub == 0) creatingSmb = true else creatingNfs = true },
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            ) { Icon(Icons.Rounded.Add, "Add share") }
        }
    }

    if (creatingSmb || editingSmb != null) {
        SmbShareDialog(
            existing = editingSmb,
            datasetPaths = paths,
            onDismiss = { creatingSmb = false; editingSmb = null },
            onConfirm = { input ->
                val e = editingSmb
                if (e == null) onCreateSmb(input) else onUpdateSmb(e.id, input)
                creatingSmb = false; editingSmb = null
            },
        )
    }
    if (creatingNfs || editingNfs != null) {
        NfsShareDialog(
            existing = editingNfs,
            datasetPaths = paths,
            onDismiss = { creatingNfs = false; editingNfs = null },
            onConfirm = { input ->
                val e = editingNfs
                if (e == null) onCreateNfs(input) else onUpdateNfs(e.id, input)
                creatingNfs = false; editingNfs = null
            },
        )
    }
    deleteSmb?.let { s ->
        ConfirmDialog(
            title = "Delete SMB share ${s.name}?",
            text = "Removes the share ${s.name} (${s.path}). The dataset itself is not deleted.",
            confirmLabel = "Delete",
            destructive = true,
            icon = Icons.Rounded.Delete,
            onConfirm = { onDeleteSmb(s.id); deleteSmb = null },
            onDismiss = { deleteSmb = null },
        )
    }
    deleteNfs?.let { s ->
        ConfirmDialog(
            title = "Delete NFS share?",
            text = "Removes the NFS export for ${s.path}. The dataset itself is not deleted.",
            confirmLabel = "Delete",
            destructive = true,
            icon = Icons.Rounded.Delete,
            onConfirm = { onDeleteNfs(s.id); deleteNfs = null },
            onDismiss = { deleteNfs = null },
        )
    }
}

@Composable
private fun SmbList(shares: List<SmbShare>, busy: Set<String>, onEdit: (SmbShare) -> Unit, onDelete: (SmbShare) -> Unit, onBrowse: (String) -> Unit = {}) {
    if (shares.isEmpty()) {
        LazyColumn(Modifier.fillMaxSize()) {
            item { EmptyState(Icons.Rounded.FolderShared, "No SMB shares", "Add a share pointing at a dataset under /mnt.") }
        }
        return
    }
    LazyColumn(contentPadding = listPadding, verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxSize()) {
        items(shares, key = { it.id }) { s ->
            var menu by remember { mutableStateOf(false) }
            ElevatedSection(contentPadding = 14.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconBadge(Icons.Rounded.FolderShared)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(s.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(s.path, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val bits = listOfNotNull(
                            s.purpose.removeSuffix("_SHARE").lowercase().replaceFirstChar { it.uppercase() },
                            if (s.readonly) "read-only" else null,
                            if (!s.browsable) "hidden" else null,
                            s.comment.takeIf { it.isNotBlank() },
                        )
                        Text(bits.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    StatusChip(
                        when {
                            s.locked == true -> Health.WARNING
                            !s.enabled -> Health.UNKNOWN
                            else -> Health.HEALTHY
                        },
                        when {
                            s.locked == true -> "Locked"
                            !s.enabled -> "Off"
                            else -> "On"
                        },
                        showIcon = false,
                    )
                    IconButton(onClick = { menu = true }, enabled = "smb:${s.id}" !in busy) { Icon(Icons.Rounded.MoreVert, null) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        if (s.path.startsWith("/mnt/") && s.locked != true) DropdownMenuItem(text = { Text("Browse files") }, onClick = { menu = false; onBrowse(s.path) }, leadingIcon = { Icon(Icons.Rounded.FolderOpen, null) })
                        DropdownMenuItem(text = { Text("Edit") }, onClick = { menu = false; onEdit(s) }, leadingIcon = { Icon(Icons.Rounded.Edit, null) })
                        app.truenascompanion.ui.components.DestructiveMenuItem("Delete", Icons.Rounded.Delete) { menu = false; onDelete(s) }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(72.dp)) }
    }
}

@Composable
private fun NfsList(shares: List<NfsShare>, busy: Set<String>, onEdit: (NfsShare) -> Unit, onDelete: (NfsShare) -> Unit, onBrowse: (String) -> Unit = {}) {
    if (shares.isEmpty()) {
        LazyColumn(Modifier.fillMaxSize()) {
            item { EmptyState(Icons.Rounded.Share, "No NFS shares", "Export a dataset over NFS for Linux and other UNIX clients.") }
        }
        return
    }
    LazyColumn(contentPadding = listPadding, verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxSize()) {
        items(shares, key = { it.id }) { s ->
            var menu by remember { mutableStateOf(false) }
            ElevatedSection(contentPadding = 14.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconBadge(Icons.Rounded.Share)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(s.path, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val bits = listOfNotNull(
                            if (s.readonly) "read-only" else "read-write",
                            s.networks.takeIf { it.isNotEmpty() }?.joinToString(", ")?.let { "nets $it" },
                            s.hosts.takeIf { it.isNotEmpty() }?.joinToString(", ")?.let { "hosts $it" },
                            s.comment.takeIf { it.isNotBlank() },
                        )
                        Text(bits.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    StatusChip(
                        when {
                            s.locked == true -> Health.WARNING
                            !s.enabled -> Health.UNKNOWN
                            else -> Health.HEALTHY
                        },
                        when {
                            s.locked == true -> "Locked"
                            !s.enabled -> "Off"
                            else -> "On"
                        },
                        showIcon = false,
                    )
                    IconButton(onClick = { menu = true }, enabled = "nfs:${s.id}" !in busy) { Icon(Icons.Rounded.MoreVert, null) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        if (s.path.startsWith("/mnt/") && s.locked != true) DropdownMenuItem(text = { Text("Browse files") }, onClick = { menu = false; onBrowse(s.path) }, leadingIcon = { Icon(Icons.Rounded.FolderOpen, null) })
                        DropdownMenuItem(text = { Text("Edit") }, onClick = { menu = false; onEdit(s) }, leadingIcon = { Icon(Icons.Rounded.Edit, null) })
                        app.truenascompanion.ui.components.DestructiveMenuItem("Delete", Icons.Rounded.Delete) { menu = false; onDelete(s) }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(72.dp)) }
    }
}

@Composable
internal fun SmbShareDialog(
    existing: SmbShare?,
    datasetPaths: List<String>,
    onDismiss: () -> Unit,
    onConfirm: (SmbShareInput) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(existing?.name.orEmpty()) }
    var dataset by rememberSaveable { mutableStateOf(existing?.datasetId ?: datasetPaths.firstOrNull().orEmpty()) }
    var purpose by rememberSaveable { mutableStateOf(existing?.purpose ?: "DEFAULT_SHARE") }
    var comment by rememberSaveable { mutableStateOf(existing?.comment.orEmpty()) }
    var enabled by rememberSaveable { mutableStateOf(existing?.enabled ?: true) }
    var readonly by rememberSaveable { mutableStateOf(existing?.readonly ?: false) }
    var browsable by rememberSaveable { mutableStateOf(existing?.browsable ?: true) }
    val path = StorageApi.pathForDataset(dataset)
    val valid = name.isNotBlank() && dataset.isNotBlank() && !name.any { it in """\/[]:|<>+=;,*?" """ }
    val initial = remember { listOf(name, dataset, purpose, comment, enabled, readonly, browsable) }
    val dirty = listOf(name, dataset, purpose, comment, enabled, readonly, browsable) != initial

    FullScreenEditor(
        title = if (existing == null) "New SMB share" else "Edit SMB share",
        saveLabel = if (existing == null) "Create" else "Save",
        canSave = valid, dirty = dirty, onDismiss = onDismiss,
        onSave = { onConfirm(SmbShareInput(name.trim(), path, purpose, enabled, comment.trim(), readonly, browsable)) },
    ) {
        OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = existing == null)
        OutlinedTextField(dataset, { dataset = it }, label = { Text("Dataset") }, singleLine = true, modifier = Modifier.fillMaxWidth(), supportingText = { Text("Path: $path") })
        if (datasetPaths.isNotEmpty()) {
            Text("Quick pick", style = MaterialTheme.typography.labelLarge, modifier = Modifier.semantics { heading() })
            @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                datasetPaths.take(8).forEach { id ->
                    FilterChip(selected = dataset == id, onClick = { dataset = id; if (name.isBlank()) name = id.substringAfterLast('/') }, label = { Text(id, maxLines = 1) })
                }
            }
        }
        Text("Purpose", style = MaterialTheme.typography.labelLarge, modifier = Modifier.semantics { heading() })
        @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            StorageApi.SMB_PURPOSES.take(3).forEach { p ->
                FilterChip(selected = purpose == p, onClick = { purpose = p }, label = { Text(p.removeSuffix("_SHARE").lowercase().replaceFirstChar { it.uppercase() }) })
            }
        }
        OutlinedTextField(comment, { comment = it }, label = { Text("Comment") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        CheckRow("Enabled", enabled, { enabled = it })
        CheckRow("Read-only", readonly, { readonly = it })
        CheckRow("Browsable", browsable, { browsable = it })
    }
}

@Composable
private fun NfsShareDialog(
    existing: NfsShare?,
    datasetPaths: List<String>,
    onDismiss: () -> Unit,
    onConfirm: (NfsShareInput) -> Unit,
) {
    var dataset by rememberSaveable { mutableStateOf(existing?.datasetId ?: datasetPaths.firstOrNull().orEmpty()) }
    var comment by rememberSaveable { mutableStateOf(existing?.comment.orEmpty()) }
    var networks by rememberSaveable { mutableStateOf(existing?.networks?.joinToString(", ").orEmpty()) }
    var hosts by rememberSaveable { mutableStateOf(existing?.hosts?.joinToString(", ").orEmpty()) }
    var enabled by rememberSaveable { mutableStateOf(existing?.enabled ?: true) }
    var readonly by rememberSaveable { mutableStateOf(existing?.readonly ?: false) }
    val path = StorageApi.pathForDataset(dataset)
    val valid = dataset.isNotBlank()
    val initial = remember { listOf(dataset, comment, networks, hosts, enabled, readonly) }
    val dirty = listOf(dataset, comment, networks, hosts, enabled, readonly) != initial

    FullScreenEditor(
        title = if (existing == null) "New NFS share" else "Edit NFS share",
        saveLabel = if (existing == null) "Create" else "Save",
        canSave = valid, dirty = dirty, onDismiss = onDismiss,
        onSave = {
            onConfirm(
                NfsShareInput(
                    path = path,
                    comment = comment.trim(),
                    enabled = enabled,
                    readonly = readonly,
                    networks = networks.split(',').map { it.trim() }.filter { it.isNotEmpty() },
                    hosts = hosts.split(',').map { it.trim() }.filter { it.isNotEmpty() },
                ),
            )
        },
    ) {
        OutlinedTextField(dataset, { dataset = it }, label = { Text("Dataset") }, singleLine = true, modifier = Modifier.fillMaxWidth(), supportingText = { Text("Path: $path") })
        if (datasetPaths.isNotEmpty()) {
            @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                datasetPaths.take(8).forEach { id ->
                    FilterChip(selected = dataset == id, onClick = { dataset = id }, label = { Text(id, maxLines = 1) })
                }
            }
        }
        OutlinedTextField(networks, { networks = it }, label = { Text("Networks (CIDR, comma-separated)") }, singleLine = true, modifier = Modifier.fillMaxWidth(), supportingText = { Text("Empty = all networks") })
        OutlinedTextField(hosts, { hosts = it }, label = { Text("Hosts (comma-separated)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(comment, { comment = it }, label = { Text("Comment") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        CheckRow("Enabled", enabled, { enabled = it })
        CheckRow("Read-only", readonly, { readonly = it })
    }
}
