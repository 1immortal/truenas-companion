package app.truenascompanion.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * 1.7.1 (security M-4): screens that show or take secrets (passwords, API keys, private keys, the shell, VPN configs)
 * set FLAG_SECURE while they are on screen, whatever the app-lock setting: no screenshots, screen recordings or
 * recents thumbnails of them. MainActivity applies [requests] together with the app lock state.
 */
object SecureWindow {
    private val _requests = MutableStateFlow(0)
    val requests: StateFlow<Int> = _requests

    fun acquire() = _requests.update { it + 1 }
    fun release() = _requests.update { (it - 1).coerceAtLeast(0) }
}

/** Marks the current screen (or dialog) as showing secrets for as long as it is composed. */
@Composable
fun SecureWindowEffect() {
    DisposableEffect(Unit) {
        SecureWindow.acquire()
        onDispose { SecureWindow.release() }
    }
}
