package app.truenascompanion

import app.truenascompanion.data.net.RouteResolver
import app.truenascompanion.data.security.AppLock
import android.app.Application
import app.truenascompanion.data.api.SharedConnections
import app.truenascompanion.data.repository.TrueNasRepository
import app.truenascompanion.data.security.SecretCipher
import app.truenascompanion.data.store.SettingsStore
import app.truenascompanion.notify.AlertChecker
import app.truenascompanion.notify.AlertNotifier
import app.truenascompanion.notify.AlertScheduler
import app.truenascompanion.notify.BackgroundConnector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** A notification tap asking the UI to show [destination] for [serverId]. */
data class PendingDeepLink(val serverId: String?, val destination: String, val nonce: Long = System.nanoTime())

/** Tiny manual DI container — no DI framework needed for an app this size. */
class AppContainer(app: Application) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val settings = SettingsStore(app, SecretCipher())
    val notifier = AlertNotifier(app)
    /** One signed-in socket per server, shared by the UI, the periodic check and instant alerts where possible. */
    val sharedConnections = SharedConnections()
    val backgroundConnector = BackgroundConnector(settings)
    /** Local vs remote address per network, shared by the UI, periodic checks and instant alerts. */
    val routes = RouteResolver(app)
    val alertChecker = AlertChecker(settings, backgroundConnector, notifier, sharedConnections, routes)
    val repository = TrueNasRepository(settings, appScope, onSignedIn = { alertChecker.onSignedIn(it) }, shared = sharedConnections, resolver = routes)
    val deepLinks = MutableStateFlow<PendingDeepLink?>(null)
    val appLock = AppLock()
}

class TrueNasApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.notifier.createChannels()
        container.appScope.launch { container.settings.lockSettings.collect { container.appLock.onSettingsLoaded(it) } }
        // Keep WorkManager / the instant-alerts service in sync with the settings for the life of the process.
        container.appScope.launch {
            combine(container.settings.notificationPrefs, container.settings.servers) { p, s -> p to s }
                .distinctUntilChanged()
                .collect { (prefs, servers) -> AlertScheduler.sync(this@TrueNasApp, prefs, servers) }
        }
    }
}
