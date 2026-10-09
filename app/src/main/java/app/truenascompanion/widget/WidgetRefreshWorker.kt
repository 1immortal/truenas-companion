package app.truenascompanion.widget

import android.content.Context
import androidx.glance.appwidget.updateAll
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.truenascompanion.TrueNasApp
import app.truenascompanion.data.api.TrueNasException
import app.truenascompanion.data.model.Health
import app.truenascompanion.data.model.Route
import app.truenascompanion.data.net.Keepalive
import app.truenascompanion.data.vpn.TunnelHolder
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * Refreshes the home-screen widget from the active server.
 * Uses the same background session / VPN patterns as phone alerts ([BackgroundConnector] + [RouteResolver.acquire]).
 * Period is 30 minutes with a network constraint.
 */
class WidgetRefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        // 1.7.1 (review P1-1): no widget on any home screen, no network work (and no more runs).
        if (!hasWidgets(applicationContext)) {
            sync(applicationContext, placed = false)
            return Result.success()
        }
        val c = (applicationContext as TrueNasApp).container
        val servers = c.settings.servers.first()
        val activeId = c.settings.activeServerId.first()
        val server = servers.firstOrNull { it.id == activeId } ?: servers.firstOrNull()
        if (server == null) {
            WidgetStore.save(applicationContext, WidgetSnapshot(error = "Add a server in the app."))
            NasStatusWidget().updateAll(applicationContext)
            return Result.success()
        }
        var target = server
        return try {
            target = c.routes.acquire(server, TunnelHolder.CHECK)
            val api = c.backgroundConnector.connect(target, Keepalive.NONE)
            try {
                val pools = runCatching { api.pools() }.getOrDefault(emptyList())
                val alerts = runCatching { api.alerts().filter { !it.dismissed } }.getOrDefault(emptyList())
                val worst = pools.map { it.health }.minByOrNull { rank(it) } ?: Health.UNKNOWN
                val label = when {
                    pools.isEmpty() -> "No pools"
                    pools.all { it.health == Health.HEALTHY } -> "${pools.size} healthy"
                    else -> pools.joinToString { "${it.name}: ${it.status}" }.take(48)
                }
                WidgetStore.save(
                    applicationContext,
                    WidgetSnapshot(
                        serverName = server.name.ifBlank { server.displayHost },
                        poolHealth = worst,
                        poolLabel = label,
                        alertCount = alerts.size,
                        routeLabel = when (target.activeRoute) {
                            Route.VPN -> "via VPN"
                            Route.TAILSCALE -> "via Tailscale"
                            Route.LOCAL -> "on LAN"
                            Route.REMOTE -> "remote"
                        },
                        updatedAt = System.currentTimeMillis(),
                    ),
                )
            } finally {
                api.close()
            }
            NasStatusWidget().updateAll(applicationContext)
            Result.success()
        } catch (e: TrueNasException.SessionNotOnThisRoute) {
            // 1.7.1: the saved session didn't work on the local/VPN address; the next attempt uses the remote one.
            c.routes.fail(server.id, target.activeRoute)
            if (runAttemptCount < 3) Result.retry() else Result.success()
        } catch (e: TrueNasException.LoginRequired) {
            WidgetStore.save(applicationContext, WidgetSnapshot(serverName = server.name, error = "Sign in again in the app"))
            NasStatusWidget().updateAll(applicationContext)
            Result.success()
        } catch (e: Throwable) {
            WidgetStore.save(applicationContext, WidgetSnapshot(serverName = server.name, error = "Can't reach the NAS right now"))
            NasStatusWidget().updateAll(applicationContext)
            if (runAttemptCount < 3) Result.retry() else Result.success()
        } finally {
            runCatching { c.routes.release(target, TunnelHolder.CHECK) }
        }
    }

    private fun rank(h: Health) = when (h) {
        Health.CRITICAL -> 0
        Health.WARNING -> 1
        Health.UNKNOWN -> 2
        Health.HEALTHY -> 3
    }

    companion object {
        private const val UNIQUE = "nas-status-widget"

        private const val UNIQUE_NOW = "nas-status-widget-now"

        /** 1.7.1 (review P1-1): true only if the widget is placed on a home screen. */
        fun hasWidgets(context: Context): Boolean = runCatching {
            android.appwidget.AppWidgetManager.getInstance(context)
                .getAppWidgetIds(android.content.ComponentName(context, NasStatusWidgetReceiver::class.java)).isNotEmpty()
        }.getOrDefault(false)

        /** Periodic refresh while a widget is placed; nothing (and the job cancelled) otherwise. */
        fun sync(context: Context, placed: Boolean = hasWidgets(context)) {
            runCatching {
                val wm = WorkManager.getInstance(context)
                if (!placed) {
                    wm.cancelUniqueWork(UNIQUE)
                    wm.cancelUniqueWork(UNIQUE_NOW)
                    return
                }
                val req = PeriodicWorkRequestBuilder<WidgetRefreshWorker>(30, TimeUnit.MINUTES)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .build()
                wm.enqueueUniquePeriodicWork(UNIQUE, ExistingPeriodicWorkPolicy.UPDATE, req)
            }
        }

        /** One refresh soon (deduplicated), only when a widget is placed. */
        fun refreshNow(context: Context, placed: Boolean = hasWidgets(context)) {
            if (!placed) return
            runCatching {
                WorkManager.getInstance(context).enqueueUniqueWork(
                    UNIQUE_NOW, androidx.work.ExistingWorkPolicy.KEEP,
                    OneTimeWorkRequestBuilder<WidgetRefreshWorker>()
                        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                        .build(),
                )
            }
        }
    }
}
