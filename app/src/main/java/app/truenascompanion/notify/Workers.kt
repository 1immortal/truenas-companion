package app.truenascompanion.notify

import app.truenascompanion.data.repository.sessionTtlSeconds
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.truenascompanion.TrueNasApp
import app.truenascompanion.data.model.AuthMethod
import app.truenascompanion.data.model.ServerConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/** Periodic background check of `alert.list` for every server with notifications on. */
class AlertCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as TrueNasApp).container
        container.alertChecker.checkAll()
        // Network hiccups just wait for the next period; retrying sooner would cost battery for little gain.
        // If instant mode is on but its service was killed, bring it back (allowed only in some states; best effort).
        AlertScheduler.ensureInstantService(applicationContext, container)
        return Result.success()
    }
}

/** Runs the notification's "Dismiss" action (`alert.dismiss`) with a network constraint and retries. */
class DismissAlertWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val serverId = inputData.getString(KEY_SERVER) ?: return Result.failure()
        val uuid = inputData.getString(KEY_UUID) ?: return Result.failure()
        val checker = (applicationContext as TrueNasApp).container.alertChecker
        return when (checker.dismiss(serverId, uuid)) {
            CheckOutcome.OK, CheckOutcome.SIGN_IN_NEEDED -> Result.success()
            CheckOutcome.NETWORK_ERROR -> if (runAttemptCount < 5) Result.retry() else Result.failure()
            CheckOutcome.FAILED -> Result.failure()
        }
    }

    companion object {
        const val KEY_SERVER = "server"
        const val KEY_UUID = "uuid"
    }
}

class AlertActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_DISMISS) return
        val serverId = intent.getStringExtra(EXTRA_SERVER_ID) ?: return
        val uuid = intent.getStringExtra(EXTRA_UUID) ?: return
        val container = (context.applicationContext as TrueNasApp).container
        container.notifier.cancelAlert(serverId, uuid)
        val request = OneTimeWorkRequestBuilder<DismissAlertWorker>()
            .setInputData(workDataOf(DismissAlertWorker.KEY_SERVER to serverId, DismissAlertWorker.KEY_UUID to uuid))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("dismiss-$serverId-$uuid", ExistingWorkPolicy.KEEP, request)
    }

    companion object {
        const val ACTION_DISMISS = "app.truenascompanion.action.DISMISS_ALERT"
        const val EXTRA_SERVER_ID = "server_id"
        const val EXTRA_UUID = "uuid"
    }
}

/** Restarts instant mode after a reboot or app update (WorkManager restores the periodic check by itself). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                val pending = goAsync()
                val container = (context.applicationContext as TrueNasApp).container
                AlertScheduler.syncAsync(context.applicationContext, container) { pending.finish() }
            }
        }
    }
}

/**
 * Session keep-alive: every ~12 h (with network, not on low battery) renews the saved session tokens of password
 * servers that are due (primary past half of its lifetime, or no spare). Usually nothing is due and it does no
 * network I/O at all; when due it is one short WebSocket sign-in and two `auth.generate_token` calls.
 */
class SessionKeepAliveWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as TrueNasApp).container
        for (server in container.settings.servers.first().filter { it.authMethod == AuthMethod.PASSWORD }) {
            // No skip while a socket is open: a live connection does not extend the *saved* tokens.
            // Usually nothing is due: then no network and no tunnel at all.
            if (!container.sessions.renewalDue(server.id, server.sessionTtlSeconds())) continue
            var target: ServerConfig? = null
            try {
                target = container.routes.acquire(server, app.truenascompanion.data.vpn.TunnelHolder.CHECK)
                container.backgroundConnector.renewSession(target)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Network trouble keeps the tokens (see SessionTokenManager); the next run tries again.
                android.util.Log.i("SessionKeepAlive", "renewal for ${server.id} skipped: ${e.javaClass.simpleName}")
            } finally {
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                    container.routes.release(target, app.truenascompanion.data.vpn.TunnelHolder.CHECK)
                }
            }
        }
        return Result.success()
    }

    companion object {
        private const val NAME = "session-keepalive"

        fun sync(context: Context, servers: List<ServerConfig>) {
            val wm = try { WorkManager.getInstance(context) } catch (e: IllegalStateException) { return }
            if (servers.none { it.authMethod == AuthMethod.PASSWORD }) {
                wm.cancelUniqueWork(NAME)
                return
            }
            val request = PeriodicWorkRequestBuilder<SessionKeepAliveWorker>(12, TimeUnit.HOURS, 2, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresBatteryNotLow(true).build()
                )
                .build()
            wm.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
