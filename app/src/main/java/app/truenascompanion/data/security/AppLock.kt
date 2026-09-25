package app.truenascompanion.data.security

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable

/** How long the app may stay in the background before it asks for fingerprint / face / PIN again. */
enum class RelockDelay(val label: String, val millis: Long) {
    IMMEDIATELY("Immediately", 0L),
    ONE_MINUTE("After 1 minute", 60_000L),
    FIVE_MINUTES("After 5 minutes", 5 * 60_000L),
    FIFTEEN_MINUTES("After 15 minutes", 15 * 60_000L),
}

@Serializable
data class LockSettings(
    val enabled: Boolean = false,
    val relock: RelockDelay = RelockDelay.ONE_MINUTE,
    /** Ask again before shutdown, reboot, deleting things, powering off VMs … (only while the lock is on). */
    val confirmDangerous: Boolean = true,
    /** Hide the app's content in the recents screen while the lock is on. */
    val privacyScreen: Boolean = true,
) {
    val guardsDangerousActions: Boolean get() = enabled && confirmDangerous
}

/**
 * Process-wide lock state. Pure logic (the clock is injectable) so it can be unit tested; the activity reports
 * visibility changes and the UI shows the lock screen while [locked] is true.
 *
 * - Cold start with the lock on: locked.
 * - Back from the background after at least the re-lock delay: locked. Rotation is ignored by the caller.
 * - The system credential screen (PIN / pattern) can stop our activity; visibility changes while an
 *   authentication is in progress are ignored so unlocking can't immediately re-lock.
 */
class AppLock(private val clock: () -> Long = SystemClock::elapsedRealtime) {
    private val _locked = MutableStateFlow(false)
    val locked: StateFlow<Boolean> = _locked.asStateFlow()

    private val _settings = MutableStateFlow<LockSettings?>(null)
    /** Null until loaded from storage (the UI waits, so content never flashes before the lock screen). */
    val settings: StateFlow<LockSettings?> = _settings.asStateFlow()

    private var backgroundAt: Long? = null
    private var authenticating = false

    fun onSettingsLoaded(s: LockSettings) {
        val previous = _settings.value
        _settings.value = s
        when {
            previous == null -> _locked.value = s.enabled // cold start
            !s.enabled -> _locked.value = false            // turned off
            // Turned on from the settings screen: the user just authenticated, so stay unlocked.
        }
    }

    fun onBackground() {
        if (authenticating) return
        backgroundAt = clock()
    }

    fun onForeground() {
        if (authenticating) return
        val since = backgroundAt ?: return
        backgroundAt = null
        val s = _settings.value ?: return
        if (s.enabled && clock() - since >= s.relock.millis) _locked.value = true
    }

    fun beginAuthentication() { authenticating = true }

    fun endAuthentication(success: Boolean, unlock: Boolean = true) {
        authenticating = false
        backgroundAt = null
        if (success && unlock) _locked.value = false
    }

    /** The device no longer has any screen lock: the app lock can't work, so it is lifted (and turned off by the caller). */
    fun forceUnlock() { authenticating = false; _locked.value = false }
}
