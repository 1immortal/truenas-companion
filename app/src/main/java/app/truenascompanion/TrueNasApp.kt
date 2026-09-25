package app.truenascompanion

import android.app.Application
import app.truenascompanion.data.repository.TrueNasRepository
import app.truenascompanion.data.security.SecretCipher
import app.truenascompanion.data.store.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Tiny manual DI container — no DI framework needed for an app this size. */
class AppContainer(app: Application) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val settings = SettingsStore(app, SecretCipher())
    val repository = TrueNasRepository(settings, appScope)
}

class TrueNasApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
