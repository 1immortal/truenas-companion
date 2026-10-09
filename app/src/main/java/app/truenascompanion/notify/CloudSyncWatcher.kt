package app.truenascompanion.notify

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.truenascompanion.TrueNasApp
import app.truenascompanion.data.api.ProtectionParsers
import app.truenascompanion.data.api.TrueNasApi
import app.truenascompanion.data.api.arr
import app.truenascompanion.data.api.obj
import app.truenascompanion.data.api.queryFilter
import app.truenascompanion.data.cloud.CloudRunWatch
import app.truenascompanion.data.model.JobState
import app.truenascompanion.data.model.LastJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.TimeUnit

/**
 * 1.5.0: "Notify me when it finishes" for cloud sync runs (and, since 1.6.0, replication runs) started in the app. While the app process lives it checks
 * the job every few seconds (then every 30 s); a 15-minute background check is the fallback if Android stops the
 * app. Each watch is removed before its notification is posted, so a run is reported once.
 */
object CloudSyncWatcher {
    const val MAX_AGE_MS = 3 * 86_400_000L
    const val NAME = "cloud-sync-watch"

    fun ended(job: LastJob?): Boolean = job != null && !job.state.active && job.state != JobState.UNKNOWN

    /** Pause before the [n]th check in the app: quick at first, then every 30 s. */
    fun pollDelayMs(n: Int): Long = when {
        n < 6 -> 5_000L
        n < 20 -> 15_000L
        else -> 30_000L
    }

    suspend fun add(context: Context, watch: CloudRunWatch) {
        val container = (context.applicationContext as TrueNasApp).container
        container.settings.updateCloudRunWatches { list -> list.filterNot { it.serverId == watch.serverId && it.jobId == watch.jobId } + watch }
        schedule(context, true)
        // One follower per run (a second tap on "Run now" must not start a second polling loop).
        val key = "${watch.serverId}:${watch.jobId}"
        synchronized(followers) {
            if (followers[key]?.isActive == true) return
            followers[key] = container.appScope.launch { try { follow(context, watch) } finally { synchronized(followers) { followers.remove(key) } } }
        }
    }

    private val followers = HashMap<String, kotlinx.coroutines.Job>()

    /** Removes the watch; true if it was still there (so the caller may notify). */
    suspend fun claim(context: Context, watch: CloudRunWatch): Boolean {
        val container = (context.applicationContext as TrueNasApp).container
        var had = false
        val left = container.settings.updateCloudRunWatches { list ->
            had = list.any { it.serverId == watch.serverId && it.jobId == watch.jobId }
            list.filterNot { it.serverId == watch.serverId && it.jobId == watch.jobId }
        }
        if (left.isEmpty()) schedule(context, false)
        return had
    }

    private suspend fun job(api: TrueNasApi, id: Long): LastJob? =
        ProtectionParsers.lastJob(api.rpc("core.get_jobs", queryFilter(Triple("id", "=", JsonPrimitive(id)))).arr()?.firstOrNull().obj())

    private suspend fun follow(context: Context, watch: CloudRunWatch) {
        val container = (context.applicationContext as TrueNasApp).container
        var n = 0
        var failures = 0
        while (true) {
            delay(pollDelayMs(n++))
            if (container.settings.cloudRunWatches().none { it.serverId == watch.serverId && it.jobId == watch.jobId }) return
            val server = container.settings.servers.first().firstOrNull { it.id == watch.serverId } ?: return
            try {
                // 1.8.0: only while the app's own connection is open. Signing in on a fresh socket every few seconds
                // (for hours) cost battery and filled the NAS audit log; in the background the periodic
                // CloudSyncWatchWorker (scheduled above) reports the result instead.
                val j = container.alertChecker.withSharedConnection(server) { api -> job(api, watch.jobId) }
                    ?: if (++failures > 3) return else continue
                failures = 0
                if (ended(j) && claim(context, watch)) {
                    post(context, server, watch, j!!)
                    return
                }
                if (j == null && n > 3) return // job gone: the background check decides
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                if (++failures > 20) return // offline for a while: leave it to the background check
            }
        }
    }

    private fun post(context: Context, server: app.truenascompanion.data.model.ServerConfig, w: CloudRunWatch, j: LastJob) {
        val notifier = (context.applicationContext as TrueNasApp).container.notifier
        if (w.kind == CloudRunWatch.KIND_REPLICATION) notifier.postReplicationDone(server, w.taskId, w.taskName, j.state, j.error)
        else notifier.postCloudSyncDone(server, w.taskId, w.taskName, w.dryRun, j.state, j.error)
    }

    fun schedule(context: Context, active: Boolean) {
        val wm = WorkManager.getInstance(context)
        if (!active) { wm.cancelUniqueWork(NAME); return }
        val request = PeriodicWorkRequestBuilder<CloudSyncWatchWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        wm.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /** One background pass over all watches; returns those still running. */
    suspend fun checkAll(context: Context): List<CloudRunWatch> {
        val container = (context.applicationContext as TrueNasApp).container
        val watches = container.settings.cloudRunWatches()
        val servers = container.settings.servers.first()
        val now = System.currentTimeMillis()
        watches.groupBy { it.serverId }.forEach { (serverId, list) ->
            val server = servers.firstOrNull { it.id == serverId }
            if (server == null) { list.forEach { claim(context, it) }; return@forEach }
            try {
                container.alertChecker.withConnection(server) { api ->
                    list.forEach { w ->
                        val j = job(api, w.jobId)
                        when {
                            ended(j) -> if (claim(context, w)) post(context, server, w, j!!)
                            // TrueNAS forgets old jobs after a while (and after a reboot): nothing left to report.
                            j == null -> claim(context, w)
                            now - w.startedAt > MAX_AGE_MS -> claim(context, w)
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                list.filter { now - it.startedAt > MAX_AGE_MS }.forEach { claim(context, it) }
            }
        }
        return container.settings.cloudRunWatches()
    }
}

class CloudSyncWatchWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val left = CloudSyncWatcher.checkAll(applicationContext)
        if (left.isEmpty()) CloudSyncWatcher.schedule(applicationContext, false)
        return Result.success()
    }
}
