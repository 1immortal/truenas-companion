package app.truenascompanion.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * Back handling for editors (1.8.0, UX review: unsaved-changes warnings everywhere). Returns the function the screen's
 * back/close buttons call; system back goes through it too. With unsaved changes it asks first.
 */
@Composable
fun rememberDiscardGuard(dirty: Boolean, what: String = "these changes", onLeave: () -> Unit): () -> Unit {
    var ask by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = dirty) { ask = true }
    if (ask) ConfirmDialog(
        title = "Discard changes?", text = "You haven't saved $what.",
        confirmLabel = "Discard", destructive = true, requireAuth = false,
        onConfirm = { ask = false; onLeave() }, onDismiss = { ask = false },
    )
    return remember(dirty, onLeave) { { if (dirty) ask = true else onLeave() } }
}

/**
 * Full-screen form (1.8.0, UI review P1: SMB/NFS/dataset forms no longer squeeze into an alert dialog). Close (X) on
 * the left, the save action on the right, the form scrolls underneath and stays above the keyboard.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FullScreenEditor(
    title: String,
    saveLabel: String?,
    canSave: Boolean,
    dirty: Boolean,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false, dismissOnClickOutside = false, dismissOnBackPress = false),
    ) {
        FullScreenEditorBody(title, saveLabel, canSave, dirty, onSave, onDismiss, content)
    }
}

/** The editor without the window, for previews and tests. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FullScreenEditorBody(
    title: String,
    saveLabel: String?,
    canSave: Boolean,
    dirty: Boolean,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val close = rememberDiscardGuard(dirty, onLeave = onDismiss)
    BackHandler(enabled = !dirty) { onDismiss() }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = close) { Icon(Icons.Rounded.Close, contentDescription = "Close") } },
                actions = {
                    if (saveLabel != null) GlowButton(onClick = onSave, enabled = canSave, modifier = Modifier.padding(end = 8.dp).testTag("editor_save")) { Text(saveLabel, maxLines = 1) }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().imePadding().verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                Modifier.widthIn(max = 640.dp).fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                content = content,
            )
            Spacer(Modifier.heightIn(min = 24.dp))
        }
    }
}

/** A checkbox row that TalkBack reads as one control ("Read-only, checkbox, checked"); the whole row is the target. */
@Composable
fun CheckRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(checked, enabled = enabled, role = Role.Checkbox, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked, onCheckedChange = null, enabled = enabled)
        Spacer(Modifier.width(12.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

/** Switch counterpart of [CheckRow]. */
@Composable
fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier, supporting: String? = null, enabled: Boolean = true) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 56.dp).toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (supporting != null) Text(supporting, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked, onCheckedChange = null, enabled = enabled)
    }
}
