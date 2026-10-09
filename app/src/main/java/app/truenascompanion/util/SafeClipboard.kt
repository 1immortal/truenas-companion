package app.truenascompanion.util

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle

/**
 * 1.8.0 (security review L-1): one way to copy text. Secrets are flagged sensitive (hidden from the clipboard preview
 * on Android 13+) and cleared again after [SENSITIVE_CLEAR_MS], unless something else was copied meanwhile.
 */
object SafeClipboard {
    const val SENSITIVE_CLEAR_MS = 60_000L
    private var serial = 0L

    fun copy(context: Context, label: String, text: String, sensitive: Boolean) {
        val cm = context.getSystemService(ClipboardManager::class.java) ?: return
        val tag = "$label#${++serial}"
        val clip = ClipData.newPlainText(tag, text)
        if (sensitive) {
            clip.description.extras = PersistableBundle().apply {
                putBoolean(if (Build.VERSION.SDK_INT >= 33) ClipDescription.EXTRA_IS_SENSITIVE else "android.content.extra.IS_SENSITIVE", true)
            }
        }
        cm.setPrimaryClip(clip)
        if (sensitive) Handler(Looper.getMainLooper()).postDelayed({ clearIfOurs(cm, tag) }, SENSITIVE_CLEAR_MS)
    }

    /** Clears the clipboard only if it still holds the clip labelled [tag]. */
    internal fun clearIfOurs(cm: ClipboardManager, tag: String) {
        val current = runCatching { cm.primaryClipDescription?.label?.toString() }.getOrNull()
        if (current != tag) return
        if (Build.VERSION.SDK_INT >= 28) cm.clearPrimaryClip() else cm.setPrimaryClip(ClipData.newPlainText("", ""))
    }
}
