package app.truenascompanion.data.files

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.truenascompanion.TrueNasApp
import app.truenascompanion.notify.AlertNotifier

/**
 * 1.8.0 (code review P1-7): a short-lived `dataSync` foreground service that runs while a file upload or download is
 * in progress. The transfer itself stays in the file browser; this keeps the app process, CPU and network alive when
 * you switch apps, so replacing a file on the NAS can't be cut off half-way (TrueNAS truncates the target as soon as
 * the upload starts, and its API has no rename to upload to a temporary name first).
 */
class TransferService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            release()
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        val title = intent?.getStringExtra(EXTRA_TITLE) ?: "Transferring a file"
        val notification = (application as TrueNasApp).container.notifier.buildTransfer(title)
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        try {
            ServiceCompat.startForeground(this, AlertNotifier.ID_TRANSFER, notification, type)
        } catch (e: Exception) {
            // Not allowed right now (e.g. started from the background): the transfer still runs, just unprotected.
            Log.w(TAG, "foreground start failed: ${e.message}")
            stopSelf()
            return START_NOT_STICKY
        }
        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java)?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ytn:transfer")?.apply {
                setReferenceCounted(false)
                acquire(MAX_MS)
            }
        }
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) { // Android 15 dataSync limit (6 h): give up cleanly
        release(); stopSelf()
    }

    override fun onDestroy() { release(); super.onDestroy() }

    private fun release() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    companion object {
        private const val TAG = "TransferService"
        private const val ACTION_STOP = "app.truenascompanion.transfer.STOP"
        private const val EXTRA_TITLE = "title"
        /** Upper bound for the wake lock (the longest transfer the file browser waits for). */
        const val MAX_MS = 60 * 60_000L

        fun start(context: Context, title: String) {
            runCatching {
                ContextCompat.startForegroundService(context, Intent(context, TransferService::class.java).putExtra(EXTRA_TITLE, title))
            }.onFailure { Log.w(TAG, "start failed: ${it.message}") }
        }

        fun stop(context: Context) {
            runCatching { context.startService(Intent(context, TransferService::class.java).setAction(ACTION_STOP)) }
        }
    }
}
