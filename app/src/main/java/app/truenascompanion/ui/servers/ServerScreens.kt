package app.truenascompanion.ui.servers

import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.heightIn
import app.truenascompanion.ui.components.GlowButton
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.VpnKey
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.NetworkCheck
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.truenascompanion.data.model.AuthMethod
import app.truenascompanion.data.model.Health
import app.truenascompanion.ui.auth.OtpDialog
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material.icons.rounded.Password
import androidx.compose.material.icons.rounded.Person
import app.truenascompanion.data.model.ServerConfig
import app.truenascompanion.ui.appViewModel
import app.truenascompanion.ui.components.ConfirmDialog
import app.truenascompanion.ui.components.ElevatedSection
import app.truenascompanion.ui.components.EmptyState
import app.truenascompanion.ui.components.IconBadge
import app.truenascompanion.ui.components.InfoBanner
import app.truenascompanion.ui.components.SkeletonList
import app.truenascompanion.ui.components.StatusChip
import app.truenascompanion.ui.theme.LocalStatusColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerListScreen(onAdd: () -> Unit, onEdit: (String) -> Unit, onOpen: () -> Unit, onBack: (() -> Unit)?) {
    val vm = appViewModel { ServerListViewModel(it) }
    val servers by vm.servers.collectAsStateWithLifecycle()
    val activeId by vm.activeId.collectAsStateWithLifecycle()
    var toDelete by remember { mutableStateOf<ServerConfig?>(null) }
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text("Your servers") },
                navigationIcon = { onBack?.let { IconButton(onClick = it) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } } },
                scrollBehavior = scroll,
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = onAdd, icon = { Icon(Icons.Rounded.Add, null) }, text = { Text("Add server") })
        },
    ) { padding ->
        val list = servers
        when {
            list == null -> Column(Modifier.padding(padding)) { SkeletonList(3) }
            list.isEmpty() -> Column(Modifier.padding(padding).fillMaxSize(), verticalArrangement = Arrangement.Center) {
                EmptyState(
                    icon = Icons.Rounded.Storage,
                    title = "Welcome to TrueNAS Companion",
                    message = "Add your TrueNAS SCALE server to monitor storage, apps, alerts and more — right from your phone.",
                    action = { Button(onClick = onAdd) { Text("Add your first server") } },
                )
            }
            else -> LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 8.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(list, key = { it.id }) { server ->
                    val active = server.id == activeId
                    ElevatedSection(onClick = { vm.select(server.id); onOpen() }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconBadge(Icons.Rounded.Dns)
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text(server.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(server.displayHost, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Spacer(Modifier.height(6.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    if (active) StatusChip(Health.HEALTHY, "Active")
                                    if (!server.isHttps) StatusChip(Health.CRITICAL, "HTTP: off")
                                    else if (server.pinnedCertSha256 != null) StatusChip(Health.UNKNOWN, "Pinned cert", showIcon = false)
                                }
                            }
                            IconButton(onClick = { onEdit(server.id) }) { Icon(Icons.Rounded.Edit, "Edit") }
                            IconButton(onClick = { toDelete = server }) { Icon(Icons.Rounded.Delete, "Delete") }
                        }
                    }
                }
            }
        }
    }
    toDelete?.let { s ->
        ConfirmDialog(
            title = "Remove ${s.name}?",
            text = "The saved API key and dashboard layout for this server will be deleted from this phone.",
            confirmLabel = "Remove", destructive = true, icon = Icons.Rounded.Delete,
            onConfirm = { vm.delete(s.id); toDelete = null }, onDismiss = { toDelete = null },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerEditScreen(serverId: String?, onDone: () -> Unit, onBack: (() -> Unit)?, onVpn: ((String) -> Unit)? = null) {
    val vm = appViewModel(key = "edit-$serverId") { ServerEditViewModel(it, serverId) }
    val s by vm.state.collectAsStateWithLifecycle()
    var showKey by rememberSaveable { mutableStateOf(false) }
    var advanced by rememberSaveable { mutableStateOf(false) }
    val container = (androidx.compose.ui.platform.LocalContext.current.applicationContext as app.truenascompanion.TrueNasApp).container
    val routes by container.routes.lastRoute.collectAsStateWithLifecycle()
    val currentRoute = serverId?.let { routes[it] }

    LaunchedEffect(s.saved) { if (s.saved) onDone() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (serverId == null) "Connect to TrueNAS" else "Edit server") },
                navigationIcon = { onBack?.let { IconButton(onClick = it) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } } },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // --- how to sign in ---
            Text("Sign in with", style = MaterialTheme.typography.titleSmall)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                AuthMethod.entries.forEachIndexed { i, m ->
                    SegmentedButton(
                        selected = s.authMethod == m,
                        onClick = { vm.update { it.copy(authMethod = m) } },
                        shape = SegmentedButtonDefaults.itemShape(i, AuthMethod.entries.size),
                        icon = { SegmentedButtonDefaults.Icon(s.authMethod == m) { Icon(if (m == AuthMethod.API_KEY) Icons.Rounded.Key else Icons.Rounded.Person, null, Modifier.size(18.dp)) } },
                    ) { Text(if (m == AuthMethod.API_KEY) "API key" else "Password", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis) }
                }
            }
            Text(
                if (s.authMethod == AuthMethod.API_KEY)
                    "In the TrueNAS web UI open the account menu (top right) › My API Keys › Add, or Credentials › Users › View API Keys. Copy the key (it is shown only once) and paste it below."
                else
                    "Use your TrueNAS web UI account. If two-factor authentication is on, you'll be asked for the 6-digit code. The app then keeps a session so you don't need a code every time you open it.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = s.url, onValueChange = { v -> vm.update { it.copy(url = v) } },
                label = { Text("Server address") }, placeholder = { Text("https://truenas.local") },
                leadingIcon = { Icon(if (s.isHttp) Icons.Rounded.LockOpen else Icons.Rounded.Lock, null) },
                singleLine = true, isError = s.urlError != null,
                supportingText = { Text(s.urlError ?: "Host or IP, optional port. https:// is assumed if omitted.") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
            AnimatedVisibility(ServerEditViewModel.reviewStillRequired(s)) {
                InfoBanner(
                    "The web UI certificate was changed. Tap \"${if (s.authMethod == AuthMethod.PASSWORD) "Test sign-in" else "Test connection"}\" to review the new certificate and trust it, then save. " +
                        "TrueNAS switches back to the old certificate if the app doesn't reconnect within 10 minutes.",
                    health = Health.WARNING,
                )
            }
            AnimatedVisibility(s.isHttp) {
                InfoBanner(
                    "http:// isn't encrypted, so the app won't sign in over it. Use the https:// address: TrueNAS serves HTTPS on port 443 " +
                        "by default, and a self-signed certificate is fine (you'll review and trust it once).",
                    health = Health.CRITICAL,
                )
            }
            if (s.authMethod == AuthMethod.API_KEY) {
                app.truenascompanion.ui.components.SecureWindowEffect()
                OutlinedTextField(
                    value = s.apiKey, onValueChange = { v -> vm.update { it.copy(apiKey = v) } },
                    label = { Text("API key") },
                    placeholder = { if (s.hasSavedKey) Text("Saved. Leave empty to keep it") },
                    leadingIcon = { Icon(Icons.Rounded.Key, null) },
                    trailingIcon = {
                        IconButton(onClick = { showKey = !showKey }) {
                            Icon(if (showKey) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, if (showKey) "Hide" else "Show")
                        }
                    },
                    visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                    singleLine = true,
                    supportingText = { Text("Stored encrypted with the Android Keystore.") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next, autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                OutlinedTextField(
                    value = s.username, onValueChange = { v -> vm.update { it.copy(username = v) } },
                    label = { Text("Username") }, placeholder = { Text("truenas_admin") }, singleLine = true,
                    leadingIcon = { Icon(Icons.Rounded.Person, null) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next, autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth(),
                )
                app.truenascompanion.ui.components.SecureWindowEffect()
                OutlinedTextField(
                    value = s.password, onValueChange = { v -> vm.update { it.copy(password = v) } },
                    label = { Text("Password") },
                    placeholder = { if (s.hasSavedPassword) Text("Saved. Leave empty to keep it") },
                    leadingIcon = { Icon(Icons.Rounded.Password, null) },
                    trailingIcon = {
                        IconButton(onClick = { showKey = !showKey }) {
                            Icon(if (showKey) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, if (showKey) "Hide" else "Show")
                        }
                    },
                    visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                    singleLine = true,
                    supportingText = { Text("Used to sign in. Only kept on the phone if you turn on \"Remember password\".") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done, autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth(),
                )
                ElevatedSection {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Remember password", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "Off: when your session ends you enter password + code again. On: the password is stored encrypted " +
                                    "(Android Keystore) and only the 2FA code is asked.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Switch(checked = s.rememberPassword, onCheckedChange = { v -> vm.update { it.copy(rememberPassword = v) } })
                    }
                    Spacer(Modifier.height(14.dp))
                    Text("Stay signed in for", style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(8.dp))
                    val options = listOf(1 to "1 day", 7 to "7 days", 30 to "30 days")
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        options.forEachIndexed { i, (days, label) ->
                            SegmentedButton(
                                selected = s.sessionDays == days, onClick = { vm.update { it.copy(sessionDays = days) } },
                                shape = SegmentedButtonDefaults.itemShape(i, options.size),
                                icon = {},
                            ) { Text(label, maxLines = 1, softWrap = false) }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "The session renews whenever the app or its alerts connect, plus a light background renewal about twice a day. " +
                            "TrueNAS still asks for your password and 2FA code 30 days after you last entered them, after the NAS " +
                            "or its middleware restarts, or if the phone is offline for longer than this.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            OutlinedTextField(
                value = s.name, onValueChange = { v -> vm.update { it.copy(name = v) } },
                label = { Text("Nickname (optional)") }, placeholder = { Text("Home NAS") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            LocalAddressSection(
                s = s,
                currentRoute = currentRoute,
                onLocalUrl = { v -> vm.updateLocal { it.copy(localUrl = v) } },
                onDetect = vm::detectLocal,
                onCheck = vm::checkLocal,
                onMode = { m -> vm.updateLocal { it.copy(routeMode = m) } },
                onForgetCert = vm::forgetLocalCertificate,
            )

            if (serverId != null && onVpn != null) VpnEntryCard(onClick = { onVpn(serverId) })

            TextButton(onClick = { advanced = !advanced }) {
                Text("Advanced options")
                Icon(if (advanced) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null)
            }
            AnimatedVisibility(advanced) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (s.authMethod == AuthMethod.API_KEY) {
                        OutlinedTextField(
                            value = s.username, onValueChange = { v -> vm.update { it.copy(username = v) } },
                            label = { Text("API key owner username") }, placeholder = { Text("truenas_admin") }, singleLine = true,
                            supportingText = { Text("Only needed on TrueNAS 26+/27, where API key login requires the username.") },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    if (s.pinnedCert != null) {
                        ElevatedSection {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.Shield, null, tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(10.dp))
                                Text("Trusted self-signed certificate", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                                TextButton(onClick = vm::forgetPinnedCertificate) { Text("Forget") }
                            }
                            MaskedCertificateFingerprint(s.pinnedCert ?: "")
                        }
                    } else if (s.authMethod == AuthMethod.PASSWORD) {
                        Text("No advanced options for password sign-in.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            TestOutcomeCard(s)

            // Stacked full-width buttons: labels never wrap, even at 360dp with large fonts.
            Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                FilledTonalButton(onClick = vm::test, enabled = s.canTest && !s.testing, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    if (s.testing) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Rounded.NetworkCheck, null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (s.authMethod == AuthMethod.PASSWORD) "Test sign-in" else "Test connection", maxLines = 1)
                }
                GlowButton(onClick = vm::save, enabled = s.canSave && !s.testing, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Save", maxLines = 1) }
            }
            if (s.authMethod == AuthMethod.PASSWORD) {
                Text(
                    "Tip: test sign-in before saving. The session from the test is kept, so you won't be asked for a code again right away.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    s.otp?.let { otp ->
        OtpDialog(username = otp.username, error = otp.error, busy = otp.busy, onSubmit = vm::submitOtp, onCancel = vm::cancelOtp)
    }

    s.pendingCertificate?.let { cert ->
        CertificateTrustDialog(cert, local = false, onTrust = vm::trustPendingCertificate, onDismiss = vm::dismissCertificate)
    }
    s.pendingLocalCertificate?.let { cert ->
        CertificateTrustDialog(cert, local = true, onTrust = vm::trustLocalCertificate, onDismiss = vm::dismissLocalCertificate)
    }
    s.detected?.let { found ->
        DetectedAddressDialog(found, s.authMethod, onUse = vm::confirmDetected, onDismiss = vm::dismissDetected)
    }
}

@Composable
private fun TestOutcomeCard(s: ServerEditState) {
    val status = LocalStatusColors.current
    AnimatedVisibility(s.outcome != null) {
        when (val o = s.outcome) {
            is TestOutcome.Success -> ElevatedSection {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.CheckCircle, null, tint = status.healthy)
                    Spacer(Modifier.width(10.dp))
                    Text("Connected to ${o.result.info.hostname}", style = MaterialTheme.typography.titleMedium)
                }
                Spacer(Modifier.height(6.dp))
                Text(o.result.info.version, style = MaterialTheme.typography.bodyMedium)
                Text("Using ${o.result.flavor.label}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            is TestOutcome.Failure -> InfoBanner(o.message, health = Health.CRITICAL)
            null -> Unit
        }
    }
}

/** Link from the server form to the VPN screen (saved servers only: the tunnel config is stored per server). */
@Composable
fun VpnEntryCard(onClick: () -> Unit) {
    app.truenascompanion.ui.components.ElevatedSection(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.VpnKey, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("VPN & remote access", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Built-in WireGuard, Tailscale, or set up a VPN on your NAS.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null)
        }
    }
}
