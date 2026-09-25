package app.truenascompanion.ui.lock

import android.app.KeyguardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockClock
import androidx.compose.material.icons.rounded.PrivacyTip
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.truenascompanion.TrueNasApp
import app.truenascompanion.data.security.AppLock
import app.truenascompanion.data.security.LockSettings
import app.truenascompanion.data.security.RelockDelay
import app.truenascompanion.ui.components.ElevatedSection
import app.truenascompanion.ui.components.GlowButton
import app.truenascompanion.ui.components.glow
import app.truenascompanion.ui.system.SettingRow
import app.truenascompanion.ui.theme.LocalBrandColors
import app.truenascompanion.ui.theme.LocalStatusColors
import kotlinx.coroutines.launch

/** Fingerprint / face with the device PIN, pattern or password as fallback. Works on every API level (26+). */
object Biometrics {
    const val AUTHENTICATORS = BIOMETRIC_WEAK or DEVICE_CREDENTIAL

    enum class Availability { READY, NO_SCREEN_LOCK, UNAVAILABLE }

    fun availability(context: Context): Availability {
        val result = BiometricManager.from(context).canAuthenticate(AUTHENTICATORS)
        val secure = ContextCompat.getSystemService(context, KeyguardManager::class.java)?.isDeviceSecure == true
        return when {
            result == BiometricManager.BIOMETRIC_SUCCESS || secure -> Availability.READY
            result == BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED || result == BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE ||
                result == BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> Availability.NO_SCREEN_LOCK
            else -> Availability.UNAVAILABLE
        }
    }

    sealed interface Result {
        data object Success : Result
        data object Cancelled : Result
        /** No fingerprint/face and no screen lock any more. */
        data object Unavailable : Result
        data class Failed(val message: String) : Result
    }

    fun authenticate(activity: FragmentActivity, title: String, subtitle: String?, onResult: (Result) -> Unit) {
        val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onResult(Result.Success)
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = onResult(
                when (errorCode) {
                    BiometricPrompt.ERROR_USER_CANCELED, BiometricPrompt.ERROR_NEGATIVE_BUTTON, BiometricPrompt.ERROR_CANCELED -> Result.Cancelled
                    BiometricPrompt.ERROR_NO_DEVICE_CREDENTIAL, BiometricPrompt.ERROR_NO_BIOMETRICS, BiometricPrompt.ERROR_HW_NOT_PRESENT ->
                        if (availability(activity) == Availability.READY) Result.Failed(errString.toString()) else Result.Unavailable
                    else -> Result.Failed(errString.toString())
                },
            )
            // A single unrecognised finger just lets the user try again inside the system prompt.
        })
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .apply { subtitle?.let { setSubtitle(it) } }
            .setAllowedAuthenticators(AUTHENTICATORS)
            .setConfirmationRequired(false)
            .build()
        runCatching { prompt.authenticate(info) }.onFailure { onResult(Result.Failed(it.message ?: "Authentication isn't available")) }
    }

    fun enrollIntent(): Intent =
        if (Build.VERSION.SDK_INT >= 30) Intent(Settings.ACTION_BIOMETRIC_ENROLL).putExtra(Settings.EXTRA_BIOMETRIC_AUTHENTICATORS_ALLOWED, AUTHENTICATORS)
        else Intent(Settings.ACTION_SECURITY_SETTINGS)
}

fun Context.findFragmentActivity(): FragmentActivity? {
    var c: Context? = this
    while (c is ContextWrapper) { if (c is FragmentActivity) return c; c = c.baseContext }
    return null
}

/**
 * Re-authentication before dangerous actions (shutdown, reboot, delete, power off …) when the app lock and
 * "Confirm dangerous actions" are on. Everything else (and tests / previews) runs the action straight away.
 */
fun interface DangerGuard {
    fun guard(reason: String, action: () -> Unit)
}

val LocalDangerGuard = staticCompositionLocalOf { DangerGuard { _, action -> action() } }

/** Wires [DangerGuard] to BiometricPrompt using the current lock settings. */
@Composable
fun rememberDangerGuard(): DangerGuard {
    val context = LocalContext.current
    val container = (context.applicationContext as TrueNasApp).container
    return remember(container) {
        DangerGuard { reason, action ->
            val s = container.appLock.settings.value
            val activity = context.findFragmentActivity()
            if (s?.guardsDangerousActions != true || activity == null || Biometrics.availability(context) != Biometrics.Availability.READY) {
                action()
            } else {
                container.appLock.beginAuthentication()
                Biometrics.authenticate(activity, "Confirm it's you", reason) { r ->
                    container.appLock.endAuthentication(r == Biometrics.Result.Success, unlock = false)
                    if (r == Biometrics.Result.Success || r == Biometrics.Result.Unavailable) action()
                }
            }
        }
    }
}

/** Shows the lock screen instead of [content] while the app is locked (screens aren't composed, so nothing streams). */
@Composable
fun LockGate(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val container = (context.applicationContext as TrueNasApp).container
    val lock = container.appLock
    val locked by lock.locked.collectAsStateWithLifecycle()
    if (!locked) { content(); return }

    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val unlock: () -> Unit = unlock@{
        val activity = context.findFragmentActivity() ?: return@unlock
        error = null
        lock.beginAuthentication()
        Biometrics.authenticate(activity, "Unlock TrueNAS Companion", null) { r ->
            when (r) {
                Biometrics.Result.Success -> lock.endAuthentication(true)
                Biometrics.Result.Cancelled -> lock.endAuthentication(false)
                is Biometrics.Result.Failed -> { lock.endAuthentication(false); error = r.message }
                Biometrics.Result.Unavailable -> {
                    // The screen lock was removed in Android settings: an app lock can't work without it.
                    lock.forceUnlock()
                    scope.launch { container.settings.updateLockSettings { it.copy(enabled = false) } }
                }
            }
        }
    }
    // Prompt automatically each time the lock screen becomes visible.
    LifecycleResumeEffect(Unit) {
        unlock()
        onPauseOrDispose { }
    }
    LockScreen(error = error, onUnlock = unlock)
}

/** Stateless lock screen (also rendered by the screenshot tests). */
@Composable
fun LockScreen(error: String?, onUnlock: () -> Unit) {
    val brand = LocalBrandColors.current
    val accent = if (brand.dark) brand.accent else MaterialTheme.colorScheme.primary
    Box(
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(listOf(MaterialTheme.colorScheme.background, MaterialTheme.colorScheme.primary.copy(alpha = if (brand.dark) 0.18f else 0.08f))),
        ),
    ) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(
                Modifier.size(88.dp).glow(accent, 22.dp, CircleShape, alpha = if (brand.dark) 0.55f else 0.3f)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.Lock, null, tint = accent, modifier = Modifier.size(40.dp)) }
            Spacer(Modifier.height(28.dp))
            Text("TrueNAS Companion is locked", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Text(
                "Use your fingerprint, face or screen lock to continue.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
            )
            AnimatedVisibility(error != null) {
                Text(
                    error ?: "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center, modifier = Modifier.padding(top = 16.dp),
                )
            }
            Spacer(Modifier.height(32.dp))
            GlowButton(onClick = onUnlock) {
                Icon(Icons.Rounded.Fingerprint, null, Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Unlock", maxLines = 1)
            }
        }
    }
}

/** Settings › Security (stateful wrapper). */
@Composable
fun SecuritySettings(onMessage: (String) -> Unit) {
    val context = LocalContext.current
    val container = (context.applicationContext as TrueNasApp).container
    val settings by container.settings.lockSettings.collectAsStateWithLifecycle(initialValue = null)
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) { tick++; onPauseOrDispose { } }
    val availability = remember(tick) { Biometrics.availability(context) }
    val s = settings ?: return
    fun save(f: (LockSettings) -> LockSettings) = scope.launch { container.settings.updateLockSettings(f) }

    SecuritySection(
        settings = s,
        availability = availability,
        onToggle = { on ->
            val activity = context.findFragmentActivity()
            if (activity == null) return@SecuritySection
            // Confirm with the same prompt before turning the lock on or off, so it's proven to work on this device.
            container.appLock.beginAuthentication()
            Biometrics.authenticate(activity, if (on) "Turn on app lock" else "Turn off app lock", null) { r ->
                container.appLock.endAuthentication(r == Biometrics.Result.Success, unlock = false)
                when (r) {
                    Biometrics.Result.Success -> save { it.copy(enabled = on, confirmDangerous = if (on && !it.enabled) true else it.confirmDangerous) }
                    Biometrics.Result.Unavailable -> onMessage("Set up a screen lock in Android settings first")
                    is Biometrics.Result.Failed -> onMessage(r.message)
                    Biometrics.Result.Cancelled -> Unit
                }
            }
        },
        onRelock = { d -> save { it.copy(relock = d) } },
        onConfirmDangerous = { v -> save { it.copy(confirmDangerous = v) } },
        onPrivacy = { v -> save { it.copy(privacyScreen = v) } },
        onSetUp = { runCatching { context.startActivity(Biometrics.enrollIntent()) }.onFailure { runCatching { context.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS)) } } },
    )
}

/** Stateless section (also rendered by the screenshot tests). */
@Composable
fun SecuritySection(
    settings: LockSettings,
    availability: Biometrics.Availability,
    onToggle: (Boolean) -> Unit,
    onRelock: (RelockDelay) -> Unit,
    onConfirmDangerous: (Boolean) -> Unit,
    onPrivacy: (Boolean) -> Unit,
    onSetUp: () -> Unit,
) {
    val ready = availability == Biometrics.Availability.READY
    ElevatedSection {
        SettingRow(
            Icons.Rounded.Fingerprint, "App lock",
            when {
                !ready && !settings.enabled -> "Needs a screen lock (PIN, pattern or password) on this phone"
                settings.enabled -> "Fingerprint, face or screen lock is needed to open the app"
                else -> "Ask for your fingerprint, face or screen lock when opening the app"
            },
        ) { Switch(checked = settings.enabled, onCheckedChange = onToggle, enabled = ready || settings.enabled) }

        if (!ready && !settings.enabled) {
            Spacer(Modifier.height(12.dp))
            Surface(color = LocalStatusColors.current.warning.copy(alpha = 0.12f), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(start = 14.dp, end = 8.dp, top = 12.dp, bottom = 4.dp)) {
                    Text(
                        "This phone has no fingerprint, face or screen lock set up, so the app can't be locked.",
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(end = 6.dp),
                    )
                    TextButton(onClick = onSetUp, modifier = Modifier.align(Alignment.End)) { Text("Set up", maxLines = 1) }
                }
            }
        }

        AnimatedVisibility(settings.enabled) {
            Column {
                SectionDivider()
                SettingRow(Icons.Rounded.LockClock, "Lock again", "When the app has been in the background") {
                    RelockPicker(settings.relock, onRelock)
                }
                SectionDivider()
                SettingRow(Icons.Rounded.VerifiedUser, "Confirm dangerous actions", "Ask again before shutdown, reboot, deleting or powering off") {
                    Switch(checked = settings.confirmDangerous, onCheckedChange = onConfirmDangerous)
                }
                SectionDivider()
                SettingRow(
                    Icons.Rounded.PrivacyTip, "Hide in recent apps",
                    if (Build.VERSION.SDK_INT >= 33) "Blank preview in the app switcher"
                    else "Blank preview in the app switcher. This also blocks screenshots on this Android version.",
                ) { Switch(checked = settings.privacyScreen, onCheckedChange = onPrivacy) }
            }
        }
    }
}

@Composable
private fun SectionDivider() =
    HorizontalDivider(Modifier.padding(vertical = 14.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))

@Composable
private fun RelockPicker(selected: RelockDelay, onPick: (RelockDelay) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }, contentPadding = PaddingValues(start = 12.dp, end = 6.dp)) {
            Text(selected.shortLabel, maxLines = 1, softWrap = false)
            Icon(Icons.Rounded.ExpandMore, null, Modifier.size(20.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            RelockDelay.entries.forEach { d ->
                DropdownMenuItem(
                    text = { Text(d.label, maxLines = 1) },
                    trailingIcon = if (d == selected) ({ Icon(Icons.Rounded.CheckCircle, null, tint = MaterialTheme.colorScheme.primary) }) else null,
                    onClick = { open = false; onPick(d) },
                )
            }
        }
    }
}

private val RelockDelay.shortLabel: String get() = when (this) {
    RelockDelay.IMMEDIATELY -> "Now"
    RelockDelay.ONE_MINUTE -> "1 min"
    RelockDelay.FIVE_MINUTES -> "5 min"
    RelockDelay.FIFTEEN_MINUTES -> "15 min"
}
