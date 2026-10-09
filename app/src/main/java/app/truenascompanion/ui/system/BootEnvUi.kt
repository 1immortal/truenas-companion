package app.truenascompanion.ui.system

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import app.truenascompanion.AppContainer
import app.truenascompanion.data.api.NasSystemApi
import app.truenascompanion.data.api.userMessage
import app.truenascompanion.data.model.BootEnvironment
import app.truenascompanion.data.model.Health
import app.truenascompanion.ui.appViewModel
import app.truenascompanion.ui.components.ConfirmDialog
import app.truenascompanion.ui.components.ElevatedSection
import app.truenascompanion.ui.components.GlowButton
import app.truenascompanion.ui.components.IconBadge
import app.truenascompanion.ui.components.SkeletonCard
import app.truenascompanion.ui.components.StatusChip
import app.truenascompanion.ui.components.UiState
import app.truenascompanion.util.Format
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

class BootEnvViewModel(private val c: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow<UiState<List<BootEnvironment>>>(UiState.Loading)
    val state = _state.asStateFlow()
    private val _busy = MutableStateFlow<Set<String>>(emptySet())
    val busy = _busy.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()
    fun consumeMessage() { _message.value = null }

    fun refresh() = viewModelScope.launch {
        try {
            _state.value = UiState.Success(c.repository.call { NasSystemApi(it).bootEnvironments() })
        } catch (e: Throwable) {
            if (_state.value !is UiState.Success) _state.value = UiState.Error(e.userMessage(), e)
            else _message.value = e.userMessage()
        }
    }

    private fun action(id: String, ok: String, block: suspend (NasSystemApi) -> Unit) {
        if (id in _busy.value) return
        viewModelScope.launch {
            _busy.value = _busy.value + id
            try {
                c.repository.call { block(NasSystemApi(it)) }
                _message.value = ok
                refresh()
            } catch (e: Throwable) {
                _message.value = e.userMessage()
            } finally {
                _busy.value = _busy.value - id
            }
        }
    }

    fun activate(id: String) = action(id, "Will boot into $id next time") { it.activateBootEnv(id) }
    fun destroy(id: String) = action(id, "Deleted $id") { it.destroyBootEnv(id) }
    fun keep(id: String, keep: Boolean) = action(id, if (keep) "Keeping $id" else "No longer keeping $id") { it.keepBootEnv(id, keep) }
    fun clone(id: String, target: String) = action(id, "Cloned to $target") { it.cloneBootEnv(id, target) }
}

@Composable
fun BootEnvSection() {
    val vm = appViewModel { BootEnvViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    var activate by remember { mutableStateOf<BootEnvironment?>(null) }
    var destroy by remember { mutableStateOf<BootEnvironment?>(null) }
    var cloneSrc by remember { mutableStateOf<BootEnvironment?>(null) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(vm) { lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { vm.refresh() } }

    BootEnvCard(state, busy, message, onActivate = { activate = it }, onKeep = { vm.keep(it.id, !it.keep) }, onClone = { cloneSrc = it }, onDelete = { destroy = it })

    activate?.let { be ->
        ConfirmDialog(
            title = "Activate ${be.id}?",
            text = "The NAS will use this boot environment on the next reboot. The running system stays as it is until then.",
            confirmLabel = "Activate",
            destructive = true,
            icon = Icons.Rounded.PlayArrow,
            onConfirm = { vm.activate(be.id); activate = null },
            onDismiss = { activate = null },
        )
    }
    destroy?.let { be ->
        ConfirmDialog(
            title = "Delete ${be.id}?",
            text = "This permanently removes the boot environment ${be.id}. You can’t undo it.",
            confirmLabel = "Delete",
            destructive = true,
            icon = Icons.Rounded.Delete,
            onConfirm = { vm.destroy(be.id); destroy = null },
            onDismiss = { destroy = null },
        )
    }
    cloneSrc?.let { be ->
        var target by rememberSaveable { mutableStateOf("${be.id}-copy") }
        AlertDialog(
            onDismissRequest = { cloneSrc = null },
            title = { Text("Clone ${be.id}") },
            text = {
                OutlinedTextField(target, { target = it }, label = { Text("New name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            },
            confirmButton = {
                GlowButton(onClick = { vm.clone(be.id, target.trim()); cloneSrc = null }, enabled = target.trim().isNotEmpty()) { Text("Clone") }
            },
            dismissButton = { TextButton(onClick = { cloneSrc = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun BootEnvRow(
    be: BootEnvironment,
    busy: Boolean,
    onActivate: () -> Unit,
    onKeep: () -> Unit,
    onClone: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val date = be.createdMillis?.let { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it)) }
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(be.id, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(date, be.used ?: be.usedBytes?.let { Format.bytes(it) }, if (be.keep) "kept" else null).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        when {
            be.active && be.activated -> StatusChip(Health.HEALTHY, "Active", showIcon = false)
            be.active -> StatusChip(Health.HEALTHY, "Running", showIcon = false)
            be.activated -> StatusChip(Health.WARNING, "Next boot", showIcon = false)
        }
        IconButton(onClick = { menu = true }, enabled = !busy) { Icon(Icons.Rounded.MoreVert, null) }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            if (be.canActivate && !be.activated) DropdownMenuItem(text = { Text("Activate") }, onClick = { menu = false; onActivate() }, leadingIcon = { Icon(Icons.Rounded.PlayArrow, null) })
            DropdownMenuItem(
                text = { Text(if (be.keep) "Unkeep" else "Keep") },
                onClick = { menu = false; onKeep() },
                leadingIcon = { Icon(if (be.keep) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder, null) },
            )
            DropdownMenuItem(text = { Text("Clone") }, onClick = { menu = false; onClone() }, leadingIcon = { Icon(Icons.Rounded.ContentCopy, null) })
            if (!be.active && !be.activated) app.truenascompanion.ui.components.DestructiveMenuItem("Delete", Icons.Rounded.Delete) { menu = false; onDelete() }
        }
    }
}

/** Stateless boot environment card (System › Updates & boot; also rendered by the screenshot tests). */
@Composable
fun BootEnvCard(
    state: UiState<List<BootEnvironment>>,
    busy: Set<String>,
    message: String?,
    onActivate: (BootEnvironment) -> Unit,
    onKeep: (BootEnvironment) -> Unit,
    onClone: (BootEnvironment) -> Unit,
    onDelete: (BootEnvironment) -> Unit,
) {
    ElevatedSection {
        SettingRow(Icons.Rounded.Storage, "Boot environments", "Activate, clone, keep or delete")
        Spacer(Modifier.height(8.dp))
        when (val s = state) {
            UiState.Loading -> repeat(2) { SkeletonCard(height = 64.dp); Spacer(Modifier.height(6.dp)) }
            is UiState.Error -> Text(s.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            is UiState.Success -> {
                if (s.data.isEmpty()) Text("No boot environments reported.", style = MaterialTheme.typography.bodySmall)
                s.data.forEachIndexed { i, be ->
                    BootEnvRow(
                        be = be,
                        busy = be.id in busy,
                        onActivate = { onActivate(be) },
                        onKeep = { onKeep(be) },
                        onClone = { onClone(be) },
                        onDelete = { onDelete(be) },
                    )
                    if (i < s.data.lastIndex) HorizontalDivider(Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                }
            }
        }
        message?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
    }
}
