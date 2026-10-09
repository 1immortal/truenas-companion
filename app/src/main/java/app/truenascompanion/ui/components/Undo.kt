package app.truenascompanion.ui.components

import android.os.Build
import android.view.accessibility.AccessibilityManager
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 1.8.1: Undo for alert dismiss/snooze and server removal.
 *
 * The snackbar stays [UndoTiming.BASE_MS] (6 s). When an accessibility service such as TalkBack is on it stays at least
 * [UndoTiming.ACCESSIBLE_MS] (12 s), and never shorter than the "Time to take action" the user picked in Android's
 * accessibility settings (Android 10+). Material snackbars are polite live regions, so TalkBack reads the message and
 * the Undo action.
 */
object UndoTiming {
    const val BASE_MS = 6_000L
    const val ACCESSIBLE_MS = 12_000L

    /** [recommendedMs]: Android's recommended timeout for this content (null below Android 10). */
    fun timeoutMs(accessibilityOn: Boolean, recommendedMs: Long?): Long =
        maxOf(BASE_MS, recommendedMs ?: 0L, if (accessibilityOn) ACCESSIBLE_MS else 0L)

    fun timeoutMs(context: android.content.Context): Long {
        val am = context.getSystemService(AccessibilityManager::class.java) ?: return BASE_MS
        val on = am.isEnabled && (am.isTouchExplorationEnabled ||
            am.getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK).isNotEmpty())
        val recommended = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            am.getRecommendedTimeoutMillis(BASE_MS.toInt(), AccessibilityManager.FLAG_CONTENT_TEXT or AccessibilityManager.FLAG_CONTENT_CONTROLS).toLong()
        } else null
        return timeoutMs(on, recommended)
    }
}

/** The Undo timeout for this device (see [UndoTiming]). */
@Composable
fun undoTimeoutMs(): Long = UndoTiming.timeoutMs(LocalContext.current)

/**
 * Shows [message] with an Undo action for [timeoutMs]. True if the user tapped Undo; false when it timed out or was
 * swiped away.
 */
suspend fun SnackbarHostState.showUndo(message: String, timeoutMs: Long): Boolean {
    val result = withTimeoutOrNull(timeoutMs) {
        showSnackbar(message, actionLabel = "Undo", withDismissAction = true, duration = SnackbarDuration.Indefinite)
    }
    return result == SnackbarResult.ActionPerformed
}

/** An action that can still be taken back: shown as an Undo snackbar; [undo] runs if the user taps Undo. */
class UndoEvent(val message: String, val undo: () -> Unit, val expired: () -> Unit = {})
