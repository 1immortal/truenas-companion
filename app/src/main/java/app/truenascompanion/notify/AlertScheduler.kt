package app.truenascompanion.notify

import android.app.ForegroundServiceStartNotAllowedException
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import app.truenascompanion.AppContainer
import app.truenascompanion.data.model.ServerConfig
import app.truenascompanion.data.store.NotificationPrefs
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/** Keeps WorkManager and the instant-alerts service in line with the notification settings. */
object AlertScheduler {
    private const val PERIODIC = "alert-check"
    private const val NOW = "alert-check-now"
    private const val TAG = "AlertScheduler"

    fun sync(context: Context, prefs: NotificationPrefs, servers: List<ServerConfig>) {
        val wm = try {
            WorkManager.getInstance(context)
        } catch (e: IllegalStateException) {
            Log.w(TAG, "WorkManager not initialized", e)
            return
        }
        val enabled = servers.any { it.id in prefs.enabledServers }
        if (!enabled) {
            wm.cancelUniqueWork(PERIODIC)
            stopInstant(context)
            return
        }
        val minutes = prefs.intervalMinutes.coerceIn(15, 24 * 60).toLong()
        val request = PeriodicWorkRequestBuilder<AlertCheckWorker>(minutes, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        // UPDATE keeps the existing schedule and just applies a new interval.
        wm.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, request)
        if (prefs.instant) startInstant(context) else stopInstant(context)
    }

    /** Immediate one-off check (e.g. right after enabling, to set the baseline). */
    fun checkNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<AlertCheckWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.REPLACE, request)
    }

    fun syncAsync(context: Context, container: AppContainer, done: () -> Unit = {}) {
        container.appScope.launch {
            try {
                sync(context, container.settings.notificationPrefs.first(), container.settings.servers.first())
            } finally {
                done()
            }
        }
    }

    suspend fun ensureInstantService(context: Context, container: AppContainer) {
        val prefs = container.settings.notificationPrefs.first()
        if (prefs.instant && prefs.enabledServers.isNotEmpty() && !InstantAlertService.running) startInstant(context)
    }

    private fun startInstant(context: Context) {
        if (InstantAlertService.running) return
        try {
            ContextCompat.startForegroundService(context, Intent(context, InstantAlertService::class.java))
        } catch (e: IllegalStateException) {
            // Android 12+: not allowed while the app is in the background (the periodic check still runs).
            if (Build.VERSION.SDK_INT >= 31 && e is ForegroundServiceStartNotAllowedException) Log.i(TAG, "instant service start deferred: ${e.message}")
            else Log.w(TAG, "instant service start failed", e)
        } catch (e: SecurityException) {
            Log.w(TAG, "instant service start failed", e)
        }
    }

    private fun stopInstant(context: Context) {
        if (InstantAlertService.running) context.stopService(Intent(context, InstantAlertService::class.java))
    }
}
