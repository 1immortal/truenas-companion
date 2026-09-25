package app.truenascompanion.data.api

import app.truenascompanion.data.model.AlertItem
import app.truenascompanion.data.model.ApiFlavor
import app.truenascompanion.data.model.AppAction
import app.truenascompanion.data.model.AppInfo
import app.truenascompanion.data.model.AppUpgradeSummary
import app.truenascompanion.data.model.JobInfo
import app.truenascompanion.data.model.Dataset
import app.truenascompanion.data.model.Disk
import app.truenascompanion.data.model.Pool
import app.truenascompanion.data.model.RealtimeStats
import app.truenascompanion.data.model.ServiceInfo
import app.truenascompanion.data.model.SystemInfo
import kotlinx.coroutines.flow.Flow

/**
 * Abstraction over the two TrueNAS APIs:
 * - [WebSocketTrueNasApi]: JSON-RPC 2.0 over WebSocket at `/api/current` (TrueNAS 25.04+), supports live stats.
 * - [RestTrueNasApi]: legacy REST API v2.0 at `/api/v2.0` (older SCALE releases), polling only.
 */
private fun unsupported() = TrueNasException.Unsupported(
    "This needs the WebSocket API (TrueNAS SCALE 25.04 or newer). Switch the server's connection away from the legacy REST API.",
)

interface TrueNasApi : AutoCloseable {
    val flavor: ApiFlavor
    val supportsRealtime: Boolean
    val isAlive: Boolean

    suspend fun systemInfo(): SystemInfo
    fun realtimeStats(): Flow<RealtimeStats>
    suspend fun pools(): List<Pool>
    suspend fun disks(): List<Disk>
    /** Disk names only (for temperature lookups); implementations can avoid the pool query that [disks] needs. */
    suspend fun diskNames(): List<String> = disks().map { it.name }
    suspend fun diskTemperatures(names: List<String>): Map<String, Double>
    suspend fun datasets(): List<Dataset>
    suspend fun apps(): List<AppInfo>
    suspend fun appAction(app: AppInfo, action: AppAction)
    suspend fun alerts(): List<AlertItem>
    suspend fun dismissAlert(uuid: String)
    /** Alert class id -> human title (`alert.list_categories`); empty if unavailable. */
    suspend fun alertClassTitles(): Map<String, String> = emptyMap()
    /**
     * Emits whenever the alert list changes (`core.subscribe("alert.list")`: ADDED / CHANGED / REMOVED events),
     * and fails with [TrueNasException.NotConnected] when the connection drops. WebSocket API only.
     */
    fun alertEvents(): Flow<Unit> = kotlinx.coroutines.flow.flow { throw unsupported() }
    suspend fun services(): List<ServiceInfo>
    suspend fun serviceAction(service: String, start: Boolean)
    suspend fun reboot()
    suspend fun shutdown()

    // --- App upgrades & jobs (WebSocket API only) ---
    /** `app.upgrade_summary(app, {app_version: "latest"})`. */
    suspend fun appUpgradeSummary(app: AppInfo): AppUpgradeSummary = throw unsupported()
    /** Starts the `app.upgrade` job and returns its job id. */
    suspend fun startAppUpgrade(app: AppInfo, snapshotHostPaths: Boolean): Long = throw unsupported()
    /** Starts the `catalog.sync` job (refreshes the catalog so new app versions show up) and returns its job id. */
    suspend fun startCatalogSync(): Long = throw unsupported()
    /** Live list of recent middleware jobs: `core.get_jobs` snapshot + `core.subscribe("core.get_jobs")` updates. */
    fun jobs(): Flow<List<JobInfo>> = kotlinx.coroutines.flow.flow { throw unsupported() }
    /** `core.job_abort(id)`. */
    suspend fun abortJob(id: Long): Unit = throw unsupported()

    override fun close()
}
