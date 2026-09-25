package app.truenascompanion

import android.content.Intent
import android.os.Bundle
import android.os.Build
import android.view.WindowManager
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.truenascompanion.data.store.AppearanceSettings
import app.truenascompanion.notify.DeepLink
import app.truenascompanion.ui.AppRoot
import app.truenascompanion.ui.theme.TrueNasTheme

/** FragmentActivity (not ComponentActivity) because BiometricPrompt needs it. */
class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val container = (application as TrueNasApp).container
        if (savedInstanceState == null) handleIntent(intent)
        applyPrivacy(container)
        setContent {
            val appearance by container.settings.appearance.collectAsStateWithLifecycle(initialValue = AppearanceSettings())
            TrueNasTheme(themeMode = appearance.themeMode, dynamicColor = appearance.dynamicColor) {
                AppRoot()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val c = (application as TrueNasApp).container
        c.appLock.onForeground()
        c.repository.setForeground(true)
    }

    override fun onStop() {
        super.onStop()
        // Rotation stops/starts the activity within milliseconds; the repository waits 30 s before closing the socket.
        val c = (application as TrueNasApp).container
        if (!isChangingConfigurations) c.appLock.onBackground()
        c.repository.setForeground(false)
    }

    /**
     * Recents privacy: on Android 13+ the app-switcher preview is simply disabled while the lock is on; older versions
     * only have FLAG_SECURE (which also blocks screenshots), so it's used while the lock is on and the privacy option
     * is enabled. The lock screen itself is always FLAG_SECURE.
     */
    private fun applyPrivacy(container: AppContainer) {
        lifecycleScope.launch {
            combine(container.appLock.locked, container.appLock.settings) { locked, s -> locked to s }.collect { (locked, s) ->
                val hide = s?.enabled == true && s.privacyScreen
                val secure = locked || (hide && Build.VERSION.SDK_INT < 33)
                if (secure) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                if (Build.VERSION.SDK_INT >= 33) setRecentsScreenshotEnabled(!hide)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val destination = intent?.getStringExtra(DeepLink.EXTRA_DESTINATION) ?: return
        val serverId = intent.getStringExtra(DeepLink.EXTRA_SERVER_ID)
        (application as TrueNasApp).container.deepLinks.value = PendingDeepLink(serverId, destination)
        intent.removeExtra(DeepLink.EXTRA_DESTINATION)
    }
}
