package app.truenascompanion.ui.files

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.truenascompanion.data.api.StorageApi
import app.truenascompanion.data.files.FilePolicy
import app.truenascompanion.data.model.Dataset
import app.truenascompanion.data.model.Pool
import app.truenascompanion.ui.components.ElevatedSection
import app.truenascompanion.ui.components.EmptyState
import app.truenascompanion.ui.components.IconBadge
import app.truenascompanion.ui.components.SectionTitle
import app.truenascompanion.util.Format

/** Storage → Files (1.3.0): pick a pool or dataset to browse. */
@Composable
fun FilesLauncher(pools: List<Pool>, datasets: List<Dataset>, onBrowse: (String) -> Unit) {
    if (pools.isEmpty()) {
        LazyColumn(Modifier.fillMaxSize()) { item { EmptyState(Icons.Rounded.Storage, "No pools", "Create a storage pool in TrueNAS to browse its files here.") } }
        return
    }
    val shown = datasets.filter { d ->
        d.depth > 0 && !d.isVolume && !d.isSystem && d.id.split('/').none { FilePolicy.isSystemName(it) || it.startsWith(".") }
    }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxSize()) {
        item {
            Text(
                "Browse, download and upload files on your pools. Downloads are saved where you choose on the phone.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item { SectionTitle("Pools") }
        items(pools, key = { "p:" + it.name }) { p ->
            ElevatedSection(contentPadding = 14.dp, onClick = { onBrowse("${FilePolicy.ROOT}/${p.name}") }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconBadge(Icons.Rounded.Storage)
                    Spacer(Modifier.width(12.dp))
                    androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
                        Text(p.name, style = MaterialTheme.typography.titleMedium)
                        Text("${FilePolicy.ROOT}/${p.name} · ${Format.bytes(p.free)} free", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null)
                }
            }
        }
        if (shown.isNotEmpty()) {
            item { SectionTitle("Datasets") }
            items(shown, key = { "d:" + it.id }) { d ->
                val path = d.mountpoint?.takeIf { it.startsWith("${FilePolicy.ROOT}/") } ?: StorageApi.pathForDataset(d.id)
                ElevatedSection(contentPadding = 12.dp, onClick = if (d.locked) null else ({ onBrowse(path) }), modifier = Modifier.padding(start = ((d.depth - 1).coerceIn(0, 3) * 12).dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (d.locked) Icons.Rounded.Lock else Icons.Rounded.Folder, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
                            Text(d.shortName, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(if (d.locked) "Locked: unlock it in TrueNAS first" else path, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (!d.locked) Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null)
                    }
                }
            }
        }
    }
}
