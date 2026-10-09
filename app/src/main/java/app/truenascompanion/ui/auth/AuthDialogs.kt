package app.truenascompanion.ui.auth

import androidx.compose.ui.text.style.TextOverflow
import app.truenascompanion.ui.components.GlowButton
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.widthIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Password
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.truenascompanion.TrueNasApp
import app.truenascompanion.data.model.Health
import app.truenascompanion.data.repository.AuthPrompt
import app.truenascompanion.ui.components.InfoBanner
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope

/** Shows the repository's sign-in prompt (password and/or 2FA code) above whatever screen is open. */
@Composable
fun AuthPromptHost() {
    val repo = (LocalContext.current.applicationContext as TrueNasApp).container.repository
    val prompt by repo.prompt.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    when (val p = prompt) {
        is AuthPrompt.Password -> PasswordDialog(
            serverName = p.server.name,
            username = p.server.username,
            rememberDefault = p.rememberDefault,
            error = p.error,
            busy = p.busy,
            onSubmit = { pw, remember -> scope.launch { repo.submitPassword(pw, remember) } },
            onCancel = repo::cancelPrompt,
        )
        is AuthPrompt.Otp -> OtpDialog(
            username = p.username,
            error = p.error,
            busy = p.busy,
            onSubmit = { code -> scope.launch { repo.submitOtp(code) } },
            onCancel = repo::cancelPrompt,
        )
        null -> Unit
    }
}

@Composable
fun PasswordDialog(
    serverName: String,
    username: String,
    rememberDefault: Boolean,
    error: String?,
    busy: Boolean,
    onSubmit: (password: String, remember: Boolean) -> Unit,
    onCancel: () -> Unit,
) {
    // 1.8.0 (security review L-5): secrets stay in memory only, never in the saved instance state (which can be
    // written to disk when the app is in the background).
    var password by remember { mutableStateOf("") }
    var show by rememberSaveable { mutableStateOf(false) }
    var remember by rememberSaveable(rememberDefault) { mutableStateOf(rememberDefault) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val submit = { if (password.isNotEmpty() && !busy) onSubmit(password, remember) }

    AlertDialog(
        onDismissRequest = { if (!busy) onCancel() },
        properties = DialogProperties(dismissOnClickOutside = false),
        icon = { Icon(Icons.Rounded.Lock, null) },
        title = { Text("Sign in to $serverName", maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    if (username.isNotBlank()) "Signing in as $username" else "Set a username for this server in its settings first.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                app.truenascompanion.ui.components.SecureWindowEffect()
                OutlinedTextField(
                    value = password, onValueChange = { password = it },
                    label = { Text("Password") }, singleLine = true, enabled = !busy,
                    visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { show = !show }) {
                            Icon(if (show) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, if (show) "Hide" else "Show")
                        }
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done, autoCorrectEnabled = false),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Remember password", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Stored encrypted on this phone. When your session ends you'll only need the 2FA code.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Switch(checked = remember, onCheckedChange = { remember = it }, enabled = !busy)
                }
                AnimatedVisibility(error != null) { InfoBanner(error ?: "", health = Health.CRITICAL) }
            }
        },
        confirmButton = {
            GlowButton(onClick = submit, enabled = password.isNotEmpty() && username.isNotBlank() && !busy) {
                if (busy) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)) }
                Text("Sign in")
            }
        },
        dismissButton = { TextButton(onClick = onCancel, enabled = !busy) { Text("Cancel") } },
    )
}

/** Friendly 6-digit code entry: big boxed digits, numeric keyboard, auto-submits once 6 digits are typed. */
@Composable
fun OtpDialog(
    username: String,
    error: String?,
    busy: Boolean,
    onSubmit: (String) -> Unit,
    onCancel: () -> Unit,
) {
    var code by remember { mutableStateOf("") } // not saved to instance state (L-5)
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    // Clear the field after a rejected code so the next one can be typed straight away.
    LaunchedEffect(error) { if (error != null) code = "" }

    AlertDialog(
        onDismissRequest = { if (!busy) onCancel() },
        properties = DialogProperties(dismissOnClickOutside = false),
        icon = { Icon(Icons.Rounded.Password, null) },
        title = { Text("Two-factor code") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "Enter the 6-digit code from your authenticator app for $username.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                BasicTextField(
                    value = code,
                    onValueChange = { v ->
                        val digits = v.filter(Char::isDigit).take(6)
                        code = digits
                        if (digits.length == 6 && !busy) onSubmit(digits)
                    },
                    enabled = !busy,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (code.length == 6 && !busy) onSubmit(code) }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    decorationBox = {
                        // Boxes share the available width so the row never overflows narrow dialogs (360dp, large fonts).
                        Row(Modifier.fillMaxWidth().widthIn(max = 320.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            repeat(6) { i ->
                                val ch = code.getOrNull(i)?.toString() ?: ""
                                val active = i == code.length && !busy
                                Box(
                                    Modifier.weight(1f).aspectRatio(0.78f)
                                        .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(12.dp))
                                        .border(
                                            width = if (active) 2.dp else 0.dp,
                                            color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest,
                                            shape = RoundedCornerShape(12.dp),
                                        ),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(ch, fontSize = 22.sp, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.headlineSmall, maxLines = 1, softWrap = false)
                                }
                            }
                        }
                    },
                )
                if (busy) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("Verifying…", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                AnimatedVisibility(error != null) { InfoBanner(error ?: "", health = Health.CRITICAL) }
                Spacer(Modifier.height(0.dp))
            }
        },
        confirmButton = {
            GlowButton(onClick = { onSubmit(code) }, enabled = code.length == 6 && !busy) { Text("Verify") }
        },
        dismissButton = { TextButton(onClick = onCancel, enabled = !busy) { Text("Cancel") } },
    )
}
