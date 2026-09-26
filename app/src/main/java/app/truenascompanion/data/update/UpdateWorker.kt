package app.truenascompanion.data.update

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.truenascompanion.BuildConfig
import app.truenascompanion.BuildConfigInfo
import app.truenascompanion.MainActivity
import app.truenascompanion.R
import app.truenascompanion.TrueNasApp
import app.truenascompanion.notify.DeepLink
import java.util.concurrent.TimeUnit

/**
 * At most once a day (network + battery not low): one small HTTPS request to the GitHub API. Posts a single
 * low-importance notification per new version. Never downloads anything in the background.
 */
class UpdateCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as TrueNasApp).container
        val current = BuildConfigInfo.versionName(applicationContext)
        val result = UpdateChecker(BuildConfig.UPDATE_REPO).check(current)
        if (result is UpdateResult.Available) {
            container.updates.publish(result)
            val v = result.release.version
            if (container.settings.updateNotifiedVersion() != v) {
                notify(applicationContext, v)
                container.settings.setUpdateNotifiedVersion(v)
            }
        }
        return Result.success() // failures just wait for tomorrow
    }

    companion object {
        private const val NAME = "update-check"
        const val CHANNEL = "app_updates"

        fun sync(context: Context, enabled: Boolean) {
            val wm = try { WorkManager.getInstance(context) } catch (e: IllegalStateException) { return }
            if (!enabled) { wm.cancelUniqueWork(NAME); return }
            val request = PeriodicWorkRequestBuilder<UpdateCheckWorker>(24, TimeUnit.HOURS, 6, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresBatteryNotLow(true).build())
                .setInitialDelay(1, TimeUnit.HOURS)
                .build()
            wm.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        fun createChannel(context: Context) {
            val m = context.getSystemService(NotificationManager::class.java) ?: return
            m.createNotificationChannel(NotificationChannel(CHANNEL, "App updates", NotificationManager.IMPORTANCE_LOW).apply {
                description = "A new version of TrueNAS Companion is available"
                group = "app"
            })
        }

        private fun notify(context: Context, version: String) {
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) return
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(DeepLink.EXTRA_DESTINATION, DeepLink.DEST_SETTINGS)
            }
            val pi = PendingIntent.getActivity(context, 7001, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val n = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_notify)
                .setContentTitle("TrueNAS Companion $version is available")
                .setContentText("Tap to see what's new and update.")
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()
            context.getSystemService(NotificationManager::class.java)?.notify(7001, n)
        }
    }
}
