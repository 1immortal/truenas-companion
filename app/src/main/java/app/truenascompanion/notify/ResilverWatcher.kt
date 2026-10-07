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
import app.truenascompanion.data.disks.DiskLogic
import app.truenascompanion.data.disks.DisksApi
import app.truenascompanion.data.model.PoolLayout
import app.truenascompanion.data.model.ResilverWatch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * 1.3.0: after a disk replacement the app remembers the pool ([ResilverWatch]) and checks it every 15 minutes in the
 * background (only while a watch exists) until the resilver is finished, then posts one notification.
 */
object ResilverWatcher {
    const val MAX_AGE_MS = 7 * 86_400_000L
    /** A replace that never showed a resilver (tiny pool, finished between checks) counts as done after this. */
    const val GRACE_MS = 2 * 60_000L

    enum class Verdict { RUNNING, DONE, EXPIRED }

    fun verdict(watch: ResilverWatch, pool: PoolLayout?, now: Long): Verdict = when {
        now - watch.startedAt > MAX_AGE_MS -> Verdict.EXPIRED
        pool == null -> Verdict.RUNNING
        DiskLogic.resilverRunning(pool.scan) -> Verdict.RUNNING
        pool.groups.any { g -> g.vdevs.any { hasReplacing(it) } } -> Verdict.RUNNING
        DiskLogic.resilverFinished(pool.scan, watch.startedAt) -> Verdict.DONE
        now - watch.startedAt > GRACE_MS -> Verdict.DONE
        else -> Verdict.RUNNING
    }

    private fun hasReplacing(n: app.truenascompanion.data.model.VdevNode): Boolean =
        n.type == "REPLACING" || n.children.any { hasReplacing(it) }

    suspend fun add(context: Context, watch: ResilverWatch) {
        val container = (context.applicationContext as TrueNasApp).container
        container.settings.updateResilverWatches { list -> list.filterNot { it.serverId == watch.serverId && it.poolId == watch.poolId } + watch }
        schedule(context, true)
    }

    /** The wizard saw the resilver finish on screen: no notification needed. */
    suspend fun remove(context: Context, serverId: String, poolId: Long) {
        val container = (context.applicationContext as TrueNasApp).container
        val left = container.settings.updateResilverWatches { list -> list.filterNot { it.serverId == serverId && it.poolId == poolId } }
        if (left.isEmpty()) schedule(context, false)
    }

    fun schedule(context: Context, active: Boolean) {
        val wm = WorkManager.getInstance(context)
        if (!active) { wm.cancelUniqueWork(NAME); return }
        val request = PeriodicWorkRequestBuilder<ResilverWatchWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        wm.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /** One pass over all watches. Returns the watches still running. */
    suspend fun checkAll(context: Context): List<ResilverWatch> {
        val container = (context.applicationContext as TrueNasApp).container
        val watches = container.settings.resilverWatches()
        val servers = container.settings.servers.first()
        val now = System.currentTimeMillis()
        val done = mutableListOf<ResilverWatch>()
        watches.groupBy { it.serverId }.forEach { (serverId, list) ->
            val server = servers.firstOrNull { it.id == serverId }
            if (server == null) { done += list; return@forEach }
            try {
                container.alertChecker.withConnection(server) { api ->
                    val disks = DisksApi(api)
                    list.forEach { w ->
                        val pool = disks.pool(w.poolId)
                        when (verdict(w, pool, now)) {
                            Verdict.RUNNING -> Unit
                            Verdict.EXPIRED -> done += w
                            Verdict.DONE -> {
                                done += w
                                container.notifier.postResilverDone(server, pool?.name ?: w.poolName, pool?.healthy == true, pool?.scan?.errors)
                            }
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                list.filter { now - it.startedAt > MAX_AGE_MS }.forEach { done += it } // offline / signed out: try again later
            }
        }
        return container.settings.updateResilverWatches { cur -> cur.filterNot { w -> done.any { it.serverId == w.serverId && it.poolId == w.poolId && it.startedAt == w.startedAt } } }
    }

    const val NAME = "resilver-watch"
}

class ResilverWatchWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val left = ResilverWatcher.checkAll(applicationContext)
        if (left.isEmpty()) ResilverWatcher.schedule(applicationContext, false)
        return Result.success()
    }
}
