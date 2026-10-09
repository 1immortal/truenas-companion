package app.truenascompanion.ui.accounts

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.truenascompanion.data.model.GroupInput
import app.truenascompanion.data.model.NasGroup
import app.truenascompanion.data.model.NasUser
import app.truenascompanion.data.model.UserInput
import app.truenascompanion.ui.components.ConfirmDialog
import app.truenascompanion.ui.components.InfoBanner
import app.truenascompanion.ui.components.SectionTitle
import app.truenascompanion.ui.lock.LocalLockGuard

/** Validation shared by the editor and unit tests. Mirrors the middleware's username rules (`validate_username`). */
object AccountValidation {
    private val NAME = Regex("^[a-zA-Z0-9_][a-zA-Z0-9_.-]*[$]?$")
    fun username(name: String): String? = when {
        name.isBlank() -> "Required"
        name.length > 32 -> "32 characters at most"
        !NAME.matches(name) -> "Letters, digits, . _ - only; can't start with - or ."
        else -> null
    }
    fun groupName(name: String): String? = username(name)
    fun password(pw: String, confirm: String): String? = when {
        pw.isEmpty() -> "Required"
        pw != confirm -> "Passwords don't match"
        else -> null
    }
    fun sshKeys(keys: String): String? {
        val bad = keys.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
            .firstOrNull { line -> line.split(Regex("\\s+")).none { it.startsWith("ssh-") || it.startsWith("ecdsa-") || it.startsWith("sk-") } }
        return if (bad != null) "Not an SSH public key: ${bad.take(24)}…" else null
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditorScaffold(title: String, canSave: Boolean, readOnly: Boolean, onDismiss: () -> Unit, onSave: () -> Unit, inline: Boolean, dirty: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    // 1.8.0: the shared full-screen editor (filled Save, unsaved-changes warning on close/back).
    val save = if (readOnly) null else "Save"
    if (inline) app.truenascompanion.ui.components.FullScreenEditorBody(title, save, canSave, dirty && !readOnly, onSave, onDismiss, content)
    else app.truenascompanion.ui.components.FullScreenEditor(title, save, canSave, dirty && !readOnly, onSave, onDismiss, content)
}

@Composable
private fun SwitchRow(title: String, subtitle: String?, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) =
    app.truenascompanion.ui.components.SwitchRow(title, checked, onChange, supporting = subtitle, enabled = enabled)

@Composable
private fun PasswordField(value: String, onChange: (String) -> Unit, label: String, enabled: Boolean = true, error: String? = null) {
    var visible by remember { mutableStateOf(false) }
    app.truenascompanion.ui.components.SecureWindowEffect()
    OutlinedTextField(
        value, onChange, label = { Text(label) }, singleLine = true, enabled = enabled, modifier = Modifier.fillMaxWidth(),
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        isError = error != null, supportingText = error?.let { { Text(it) } },
        trailingIcon = { IconButton(onClick = { visible = !visible }) { Icon(if (visible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, if (visible) "Hide" else "Show") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> Dropdown(label: String, options: List<T>, selected: T?, text: (T) -> String, enabled: Boolean = true, fallback: String? = null, onSelect: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = open, onExpandedChange = { if (enabled) open = it }) {
        OutlinedTextField(
            value = selected?.let(text) ?: fallback ?: "", onValueChange = {}, readOnly = true, enabled = enabled, label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(open) },
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled),
        )
        ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { o -> DropdownMenuItem(text = { Text(text(o)) }, onClick = { open = false; onSelect(o) }) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun UserEditorDialog(user: NasUser?, data: AccountsData, onDismiss: () -> Unit, onSave: (UserInput) -> Unit, inline: Boolean = false) {
    val creating = user == null
    val readOnly = user?.readOnly == true
    var username by remember { mutableStateOf(user?.username ?: "") }
    var fullName by remember { mutableStateOf(user?.fullName ?: "") }
    var email by remember { mutableStateOf(user?.email ?: "") }
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var passwordDisabled by remember { mutableStateOf(user?.passwordDisabled ?: false) }
    var groupCreate by remember { mutableStateOf(creating) }
    var primary by remember { mutableStateOf(user?.groupId) }
    var extra by remember { mutableStateOf(user?.groups?.toSet() ?: emptySet()) }
    var home by remember { mutableStateOf(user?.home ?: UserInput.DEFAULT_HOME) }
    var homeCreate by remember { mutableStateOf(false) }
    var shell by remember { mutableStateOf(user?.shell ?: data.shells.keys.firstOrNull { it.endsWith("/zsh") } ?: "/usr/bin/zsh") }
    var keys by remember { mutableStateOf(user?.sshPubKey ?: "") }
    var smb by remember { mutableStateOf(user?.smb ?: true) }
    var sshPassword by remember { mutableStateOf(user?.sshPasswordEnabled ?: false) }
    var locked by remember { mutableStateOf(user?.locked ?: false) }
    var showSystemGroups by remember { mutableStateOf(false) }
    var confirmSave by remember { mutableStateOf<UserInput?>(null) }

    val usernameError = if (creating || username != user.username) AccountValidation.username(username)
        ?.takeIf { username.isNotEmpty() || creating } else null
    val taken = data.users.any { it.username == username && it.id != user?.id }
    val pwError = if (creating && !passwordDisabled) AccountValidation.password(password, confirm).takeIf { password.isNotEmpty() || confirm.isNotEmpty() } else null
    val keysError = AccountValidation.sshKeys(keys)
    val smbConflict = smb && passwordDisabled
    val canSave = !readOnly && AccountValidation.username(username) == null && !taken && keysError == null && !smbConflict &&
        (!creating || passwordDisabled || AccountValidation.password(password, confirm) == null) &&
        (creating && groupCreate || primary != null) && fullName.isNotBlank()

    val form = listOf(username, fullName, email, password, confirm, passwordDisabled, groupCreate, primary, extra, home, homeCreate, shell, keys, smb, sshPassword, locked)
    val initialForm = remember { form }
    EditorScaffold(
        title = when { creating -> "New user"; readOnly -> user.username; else -> "Edit ${user.username}" },
        canSave = canSave, readOnly = readOnly, onDismiss = onDismiss, inline = inline, dirty = form != initialForm && confirmSave == null,
        onSave = {
            confirmSave = UserInput(
                username = username.trim(), fullName = fullName.trim(), email = email.trim().ifEmpty { null },
                password = if (creating && !passwordDisabled) password else null,
                groupCreate = creating && groupCreate, primaryGroup = if (creating && groupCreate) null else primary,
                groups = extra.toList().sorted(), home = home.trim().ifEmpty { UserInput.DEFAULT_HOME }, homeCreate = homeCreate,
                shell = shell, sshPubKey = keys.trim().ifEmpty { null }, smb = smb, passwordDisabled = passwordDisabled,
                sshPasswordEnabled = sshPassword, locked = locked,
            )
        },
    ) {
        if (readOnly) InfoBanner("Built-in and system accounts are managed by TrueNAS and shown read-only.")
        SectionTitle("Identity")
        OutlinedTextField(username, { username = it.trim() }, label = { Text("Username") }, singleLine = true, enabled = !readOnly, modifier = Modifier.fillMaxWidth(),
            isError = usernameError != null || taken, supportingText = { (if (taken) "Already in use" else usernameError)?.let { Text(it) } })
        OutlinedTextField(fullName, { fullName = it }, label = { Text("Full name") }, singleLine = true, enabled = !readOnly, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(email, { email = it }, label = { Text("Email (optional)") }, singleLine = true, enabled = !readOnly, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))

        SectionTitle("Sign-in")
        if (creating) {
            SwitchRow("Disable password", "Key-only accounts can't use SMB", passwordDisabled, onChange = { passwordDisabled = it; if (it) smb = false })
            if (!passwordDisabled) {
                PasswordField(password, { password = it }, "Password")
                PasswordField(confirm, { confirm = it }, "Confirm password", error = pwError)
            }
        } else {
            SwitchRow("Disable password", "Key-only accounts can't use SMB", passwordDisabled, enabled = !readOnly, onChange = { passwordDisabled = it; if (it) smb = false })
            if (!readOnly) Text("Use “Reset password” in the user's menu to set a new password.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        SwitchRow("SMB access", "Lets the user sign in to SMB shares", smb, enabled = !readOnly && !passwordDisabled, onChange = { smb = it })
        if (smbConflict) Text("SMB users need a password.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        SwitchRow("Lock account", "Blocks every sign-in", locked, enabled = !readOnly, onChange = { locked = it })

        SectionTitle("Groups")
        val groupChoices = data.groups.filter { showSystemGroups || !it.readOnly || it.id == primary || it.id in extra }
        if (creating) SwitchRow("Create a primary group", "Named like the user", groupCreate, onChange = { groupCreate = it })
        if (!creating || !groupCreate) Dropdown("Primary group", data.groups, data.groups.firstOrNull { it.id == primary }, { it.name }, enabled = !readOnly, fallback = user?.groupName?.takeIf { primary == user.groupId }) { primary = it.id }
        Text("Additional groups", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            groupChoices.filter { it.id != primary }.forEach { g ->
                FilterChip(selected = g.id in extra, enabled = !readOnly, onClick = { extra = if (g.id in extra) extra - g.id else extra + g.id }, label = { Text(g.name) })
            }
            if (!readOnly) AssistChip(onClick = { showSystemGroups = !showSystemGroups }, label = { Text(if (showSystemGroups) "Hide built-in" else "Show built-in…") })
        }
        if (data.groups.any { it.id in extra && "FULL_ADMIN" in it.roles }) InfoBanner("A group in the selection grants full administrator rights.")

        SectionTitle("Home & shell")
        OutlinedTextField(home, { home = it }, label = { Text("Home directory") }, singleLine = true, enabled = !readOnly, modifier = Modifier.fillMaxWidth(),
            supportingText = { Text("A dataset path such as /mnt/tank/home, or ${UserInput.DEFAULT_HOME} for none") })
        if (!readOnly && home.trim() != UserInput.DEFAULT_HOME && home.trim() != user?.home)
            SwitchRow("Create home directory", "Creates ${home.trim().trimEnd('/')}/${username.ifBlank { "user" }}", homeCreate, onChange = { homeCreate = it })
        val shells = data.shells.ifEmpty { mapOf(shell to shell) }
        Dropdown("Shell", shells.keys.toList(), shell, { shells[it] ?: it }, enabled = !readOnly) { shell = it }

        SectionTitle("SSH")
        OutlinedTextField(keys, { keys = it }, label = { Text("Public keys (one per line)") }, enabled = !readOnly, minLines = 3, maxLines = 8, modifier = Modifier.fillMaxWidth(),
            isError = keysError != null, supportingText = { Text(keysError ?: "ssh-ed25519 AAAA… user@example") })
        SwitchRow("Allow SSH password login", null, sshPassword, enabled = !readOnly && !passwordDisabled, onChange = { sshPassword = it })
        Spacer(Modifier.height(24.dp))
    }

    confirmSave?.let { input ->
        ConfirmDialog(
            title = if (creating) "Create ${input.username}?" else "Save ${input.username}?",
            text = buildString {
                append(if (creating) "A new account is added to TrueNAS." else "The account is updated on TrueNAS.")
                if (input.locked && user?.locked != true) append(" The account will be locked.")
                if (input.homeCreate) append(" A home directory is created.")
            },
            confirmLabel = if (creating) "Create" else "Save", strongAuth = true, icon = Icons.Rounded.ManageAccounts,
            onConfirm = { confirmSave = null; onSave(input) }, onDismiss = { confirmSave = null },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GroupEditorDialog(group: NasGroup?, users: List<NasUser>, onDismiss: () -> Unit, onSave: (GroupInput) -> Unit, inline: Boolean = false) {
    val creating = group == null
    val readOnly = group?.readOnly == true
    var name by remember { mutableStateOf(group?.name ?: "") }
    var gid by remember { mutableStateOf(group?.gid?.toString() ?: "") }
    var smb by remember { mutableStateOf(group?.smb ?: true) }
    var members by remember { mutableStateOf(group?.users?.toSet() ?: emptySet()) }
    var confirmSave by remember { mutableStateOf<GroupInput?>(null) }
    val nameError = AccountValidation.groupName(name).takeIf { name.isNotEmpty() }
    val gidValue = gid.trim().toIntOrNull()
    val gidError = if (gid.isNotBlank() && (gidValue == null || gidValue < 0)) "A number" else null
    val canSave = !readOnly && AccountValidation.groupName(name) == null && gidError == null

    val form = listOf(name, gid, smb, members)
    val initialForm = remember { form }
    EditorScaffold(
        title = when { creating -> "New group"; readOnly -> group.name; else -> "Edit ${group.name}" },
        canSave = canSave, readOnly = readOnly, onDismiss = onDismiss, inline = inline, dirty = form != initialForm && confirmSave == null,
        onSave = { confirmSave = GroupInput(name.trim(), if (creating) gidValue else null, smb, members.toList().sorted()) },
    ) {
        if (readOnly) InfoBanner("Built-in groups are managed by TrueNAS and shown read-only.")
        OutlinedTextField(name, { name = it.trim() }, label = { Text("Name") }, singleLine = true, enabled = !readOnly, modifier = Modifier.fillMaxWidth(),
            isError = nameError != null, supportingText = nameError?.let { { Text(it) } })
        OutlinedTextField(gid, { gid = it }, label = { Text(if (creating) "GID (blank = next free)" else "GID") }, singleLine = true, enabled = creating,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth(),
            isError = gidError != null, supportingText = gidError?.let { { Text(it) } })
        SwitchRow("SMB group", "Usable in SMB share permissions", smb, enabled = !readOnly, onChange = { smb = it })
        SectionTitle("Members")
        val choices = users.filter { !it.readOnly || it.id in members }
        if (choices.isEmpty()) Text("No local users yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            choices.forEach { u ->
                FilterChip(selected = u.id in members, enabled = !readOnly, onClick = { members = if (u.id in members) members - u.id else members + u.id }, label = { Text(u.username) })
            }
        }
        Spacer(Modifier.height(24.dp))
    }

    confirmSave?.let { input ->
        ConfirmDialog(
            title = if (creating) "Create group ${input.name}?" else "Save group ${input.name}?",
            text = "${input.users.size} member${if (input.users.size == 1) "" else "s"}." + if (input.smb) " Usable for SMB." else "",
            confirmLabel = if (creating) "Create" else "Save", strongAuth = true, icon = Icons.Rounded.Groups,
            onConfirm = { confirmSave = null; onSave(input) }, onDismiss = { confirmSave = null },
        )
    }
}

@Composable
fun ResetPasswordDialog(user: NasUser, onDismiss: () -> Unit, onReset: (String) -> Unit) {
    var pw by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    val lockGuard = LocalLockGuard.current
    val error = AccountValidation.password(pw, confirm).takeIf { confirm.isNotEmpty() }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Password, null) },
        title = { Text("Reset password for ${user.username}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${user.username} signs in with the new password from now on (web UI, SMB and SSH if allowed).", style = MaterialTheme.typography.bodySmall)
                PasswordField(pw, { pw = it }, "New password")
                PasswordField(confirm, { confirm = it }, "Confirm password", error = error)
            }
        },
        confirmButton = {
            TextButton(enabled = AccountValidation.password(pw, confirm) == null, onClick = { lockGuard.guard("Reset password for ${user.username}") { onReset(pw) } }) { Text("Reset") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
