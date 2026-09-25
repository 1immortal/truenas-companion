package app.truenascompanion

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.truenascompanion.data.store.AppearanceSettings
import app.truenascompanion.ui.AppRoot
import app.truenascompanion.ui.theme.TrueNasTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val container = (application as TrueNasApp).container
        setContent {
            val appearance by container.settings.appearance.collectAsStateWithLifecycle(initialValue = AppearanceSettings())
            TrueNasTheme(themeMode = appearance.themeMode, dynamicColor = appearance.dynamicColor) {
                AppRoot()
            }
        }
    }
}
