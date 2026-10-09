package app.truenascompanion.ui.accounts

import app.truenascompanion.ui.components.Tag
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.truenascompanion.data.model.Health
import app.truenascompanion.data.model.NasGroup
import app.truenascompanion.data.model.NasUser
import app.truenascompanion.ui.appViewModel
import app.truenascompanion.ui.components.*

/** Actions the lists can request; the screen turns them into confirmations / editors. */
sealed interface AccountAction {
    data class EditUser(val user: NasUser?) : AccountAction
    data class ResetPassword(val user: NasUser) : AccountAction
    data class ToggleLock(val user: NasUser) : AccountAction
    data class DeleteUser(val user: NasUser) : AccountAction
    data class EditGroup(val group: NasGroup?) : AccountAction
    data class DeleteGroup(val group: NasGroup) : AccountAction
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountsScreen(onBack: () -> Unit) {
    val vm = appViewModel { AccountsViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var action by remember { mutableStateOf<AccountAction?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Users & groups") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } })
        },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            if (state is UiState.Success) ExtendedFloatingActionButton(
                onClick = { action = if (tab == 0) AccountAction.EditUser(null) else AccountAction.EditGroup(null) },
                icon = { Icon(Icons.Rounded.Add, null) }, text = { Text(if (tab == 0) "Add user" else "Add group") },
            )
        },
    ) { padding ->
        PullToRefreshBox(isRefreshing = refreshing, onRefresh = { vm.refresh() }, modifier = Modifier.padding(padding).fillMaxSize()) {
            when (val s = state) {
                UiState.Loading -> SkeletonList(6, 76.dp)
                is UiState.Error -> ScrollableErrorState(s.message, s.isLoginRequired) { vm.refresh() }
                is UiState.Success -> AccountsContent(s.data, tab, onTab = { tab = it }, busy = busy, onAction = { action = it })
            }
        }
    }

    val data = (state as? UiState.Success)?.data
    when (val a = action) {
        null -> Unit
        is AccountAction.EditUser -> if (data != null) UserEditorDialog(
            user = a.user, data = data,
            onDismiss = { action = null },
            onSave = { input -> action = null; if (a.user == null) vm.createUser(input) else vm.updateUser(a.user, input) },
        )
        is AccountAction.EditGroup -> if (data != null) GroupEditorDialog(
            group = a.group, users = data.users,
            onDismiss = { action = null },
            onSave = { input -> action = null; if (a.group == null) vm.createGroup(input) else vm.updateGroup(a.group, input) },
        )
        is AccountAction.ResetPassword -> ResetPasswordDialog(a.user, onDismiss = { action = null }) { pw -> action = null; vm.resetPassword(a.user, pw) }
        is AccountAction.ToggleLock -> {
            val lock = !a.user.locked
            ConfirmDialog(
                title = if (lock) "Lock ${a.user.username}?" else "Unlock ${a.user.username}?",
                text = if (lock) "${a.user.username} won't be able to sign in to TrueNAS, SMB or SSH until you unlock the account. Running sessions are not ended."
                else "${a.user.username} can sign in again with their password or SSH keys.",
                confirmLabel = if (lock) "Lock" else "Unlock", destructive = lock, strongAuth = true,
                icon = if (lock) Icons.Rounded.Lock else Icons.Rounded.LockOpen,
                onConfirm = { action = null; vm.setLocked(a.user, lock) }, onDismiss = { action = null },
            )
        }
        is AccountAction.DeleteUser -> {
            var deleteGroup by remember(a) { mutableStateOf(true) }
            ConfirmDialog(
                title = "Delete ${a.user.username}?",
                text = "The account is removed from TrueNAS. Files it owns stay on disk. This can't be undone.",
                confirmLabel = "Delete", destructive = true, strongAuth = true, icon = Icons.Rounded.PersonRemove,
                onConfirm = { action = null; vm.deleteUser(a.user, deleteGroup) }, onDismiss = { action = null },
                extra = {
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(deleteGroup, onCheckedChange = { deleteGroup = it })
                        Text("Also delete the primary group ${a.user.groupName ?: ""} if no one else uses it", style = MaterialTheme.typography.bodyMedium)
                    }
                },
            )
        }
        is AccountAction.DeleteGroup -> {
            var deleteUsers by remember(a) { mutableStateOf(false) }
            ConfirmDialog(
                title = "Delete group ${a.group.name}?",
                text = "The group is removed from TrueNAS. Shares and permissions that use it stop matching anyone.",
                confirmLabel = "Delete", destructive = true, strongAuth = true, icon = Icons.Rounded.GroupRemove,
                onConfirm = { action = null; vm.deleteGroup(a.group, deleteUsers) }, onDismiss = { action = null },
                extra = {
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(deleteUsers, onCheckedChange = { deleteUsers = it })
                        Text("Also delete users whose primary group this is", style = MaterialTheme.typography.bodyMedium)
                    }
                },
            )
        }
    }
}

/** Stateless list content (screenshot tests render this with example data). */
@Composable
fun AccountsContent(data: AccountsData, tab: Int, onTab: (Int) -> Unit, busy: Boolean = false, onAction: (AccountAction) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var showSystem by rememberSaveable { mutableStateOf(false) }
    val q = query.trim().lowercase()
    val users = data.users.filter { (showSystem || !it.readOnly) && (q.isEmpty() || it.username.lowercase().contains(q) || it.fullName.lowercase().contains(q)) }
    val groups = data.groups.filter { (showSystem || !it.readOnly) && (q.isEmpty() || it.name.lowercase().contains(q)) }
    val groupNames = data.groups.associate { it.id to it.name }
    Column(Modifier.fillMaxSize()) {
        PrimaryTabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { onTab(0) }, text = { Text("Users (${data.users.count { !it.readOnly }})") })
            Tab(selected = tab == 1, onClick = { onTab(1) }, text = { Text("Groups (${data.groups.count { !it.readOnly }})") })
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        LazyColumn(contentPadding = PaddingValues(16.dp, 12.dp, 16.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxSize()) {
            item {
                OutlinedTextField(
                    value = query, onValueChange = { query = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    leadingIcon = { Icon(Icons.Rounded.Search, null) }, placeholder = { Text(if (tab == 0) "Search users" else "Search groups") },
                    shape = MaterialTheme.shapes.extraLarge,
                )
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (tab == 0) "${users.size} shown" else "${groups.size} shown",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f).padding(start = 4.dp),
                    )
                    FilterChip(selected = showSystem, onClick = { showSystem = !showSystem }, label = { Text("Show built-in") },
                        leadingIcon = if (showSystem) ({ Icon(Icons.Rounded.Check, null, Modifier.size(18.dp)) }) else null)
                }
            }
            if (tab == 0) {
                if (users.isEmpty()) item { EmptyState(Icons.Rounded.PersonSearch, "No users", if (q.isEmpty()) "Add a user for shares, SSH or the web UI." else "Nothing matches \"$query\".") }
                items(users, key = { "u${it.id}" }) { u -> UserCard(u, groupNames, onAction) }
            } else {
                if (groups.isEmpty()) item { EmptyState(Icons.Rounded.Groups, "No groups", if (q.isEmpty()) "Add a group to share access between users." else "Nothing matches \"$query\".") }
                items(groups, key = { "g${it.id}" }) { g -> GroupCard(g, data.users, onAction) }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun UserCard(u: NasUser, groupNames: Map<Int, String>, onAction: (AccountAction) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    ElevatedSection(onClick = { onAction(AccountAction.EditUser(u)) }, contentPadding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LetterAvatar(u.username)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(u.username, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(listOf(u.fullName, "UID ${u.uid}").filter { it.isNotBlank() }.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (u.readOnly) Icon(Icons.Rounded.Lock, "Read-only", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            else Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "Actions for ${u.username}") }
                DropdownMenu(menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Edit") }, leadingIcon = { Icon(Icons.Rounded.Edit, null) }, onClick = { menu = false; onAction(AccountAction.EditUser(u)) })
                    if (!u.passwordDisabled) DropdownMenuItem(text = { Text("Reset password") }, leadingIcon = { Icon(Icons.Rounded.Password, null) }, onClick = { menu = false; onAction(AccountAction.ResetPassword(u)) })
                    DropdownMenuItem(text = { Text(if (u.locked) "Unlock" else "Lock") }, leadingIcon = { Icon(if (u.locked) Icons.Rounded.LockOpen else Icons.Rounded.Lock, null) }, onClick = { menu = false; onAction(AccountAction.ToggleLock(u)) })
                    DropdownMenuItem(text = { Text("Delete", color = MaterialTheme.colorScheme.error) }, leadingIcon = { Icon(Icons.Rounded.Delete, null, tint = MaterialTheme.colorScheme.error) }, onClick = { menu = false; onAction(AccountAction.DeleteUser(u)) })
                }
            }
        }
        val chips = buildList {
            if (u.locked) add(Health.CRITICAL to "Locked")
            // 1.8.0: attributes are neutral tags; only "Locked" is a state.
            if (u.isAdmin) add(Health.INFO to "Admin")
            if (u.builtin) add(Health.UNKNOWN to "Built-in")
            if (u.smb) add(Health.UNKNOWN to "SMB")
            if (u.sshKeyCount > 0) add(Health.UNKNOWN to "${u.sshKeyCount} SSH key${if (u.sshKeyCount > 1) "s" else ""}")
            if (u.twoFactor) add(Health.UNKNOWN to "2FA")
            if (u.passwordDisabled) add(Health.UNKNOWN to "No password")
        }
        val groups = (listOfNotNull(u.groupName) + u.groups.mapNotNull { groupNames[it] }).distinct()
        if (chips.isNotEmpty() || groups.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                chips.forEach { (h, t) -> if (h == Health.CRITICAL) StatusChip(h, t) else Tag(t, brand = h == Health.INFO) }
            }
            if (groups.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text("Groups: " + groups.take(5).joinToString(", ") + if (groups.size > 5) " +${groups.size - 5}" else "",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun GroupCard(g: NasGroup, users: List<NasUser>, onAction: (AccountAction) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val names = users.filter { it.id in g.users }.map { it.username }
    ElevatedSection(onClick = { onAction(AccountAction.EditGroup(g)) }, contentPadding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(Icons.Rounded.Groups)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(g.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("GID ${g.gid} · ${names.size} member${if (names.size == 1) "" else "s"}" + if (g.smb) " · SMB" else "",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (g.readOnly) Icon(Icons.Rounded.Lock, "Read-only", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            else Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "Actions for ${g.name}") }
                DropdownMenu(menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Edit") }, leadingIcon = { Icon(Icons.Rounded.Edit, null) }, onClick = { menu = false; onAction(AccountAction.EditGroup(g)) })
                    DropdownMenuItem(text = { Text("Delete", color = MaterialTheme.colorScheme.error) }, leadingIcon = { Icon(Icons.Rounded.Delete, null, tint = MaterialTheme.colorScheme.error) }, onClick = { menu = false; onAction(AccountAction.DeleteGroup(g)) })
                }
            }
        }
        if (names.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(names.take(6).joinToString(", ") + if (names.size > 6) " +${names.size - 6}" else "",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (g.roles.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Tag(g.roles.joinToString(", ") { it.lowercase().replace('_', ' ') }, brand = true)
        }
    }
}
