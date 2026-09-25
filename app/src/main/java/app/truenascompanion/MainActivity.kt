package app.truenascompanion

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.truenascompanion.data.store.AppearanceSettings
import app.truenascompanion.notify.DeepLink
import app.truenascompanion.ui.AppRoot
import app.truenascompanion.ui.theme.TrueNasTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val container = (application as TrueNasApp).container
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            val appearance by container.settings.appearance.collectAsStateWithLifecycle(initialValue = AppearanceSettings())
            TrueNasTheme(themeMode = appearance.themeMode, dynamicColor = appearance.dynamicColor) {
                AppRoot()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        (application as TrueNasApp).container.repository.setForeground(true)
    }

    override fun onStop() {
        super.onStop()
        // Rotation stops/starts the activity within milliseconds; the repository waits 30 s before closing the socket.
        (application as TrueNasApp).container.repository.setForeground(false)
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
