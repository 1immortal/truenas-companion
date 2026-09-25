package app.truenascompanion.data.api

import app.truenascompanion.data.model.AlertItem
import app.truenascompanion.data.model.ApiFlavor
import app.truenascompanion.data.model.AppAction
import app.truenascompanion.data.model.AppInfo
import app.truenascompanion.data.model.LogLine
import app.truenascompanion.data.model.CatalogAppDetails
import app.truenascompanion.data.model.CatalogApp
import app.truenascompanion.data.model.AppStats
import app.truenascompanion.data.model.AppEditData
import app.truenascompanion.data.model.AppUpgradeSummary
import app.truenascompanion.data.model.JobInfo
import app.truenascompanion.data.model.Dataset
import app.truenascompanion.data.model.Disk
import app.truenascompanion.data.model.Pool
import app.truenascompanion.data.model.RealtimeStats
import app.truenascompanion.data.model.ServiceInfo
import app.truenascompanion.data.model.SystemInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonObject

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

    // --- Apps: catalog, install, edit, delete, rollback, logs, stats (WebSocket API, 24.10+) ---
    /** `app.available`: every app of the preferred trains. */
    suspend fun catalogApps(): List<CatalogApp> = throw unsupported()
    /** `catalog.get_app_details(name, {train})`. */
    suspend fun catalogAppDetails(name: String, train: String): CatalogAppDetails = throw unsupported()
    /** `app.create` job; returns the job id. */
    suspend fun startAppInstall(catalogApp: String, appName: String, train: String, version: String, values: JsonObject): Long = throw unsupported()
    /** `app.query` with `include_app_schema` + `retrieve_config`. */
    suspend fun appEditData(appName: String): AppEditData = throw unsupported()
    /** `app.update` job with new values (or a new compose config for custom apps). */
    suspend fun startAppUpdate(appName: String, values: JsonObject, customApp: Boolean): Long = throw unsupported()
    /** `app.delete` job. */
    suspend fun startAppDelete(appName: String, removeVolumes: Boolean): Long = throw unsupported()
    /** `app.rollback_versions`. */
    suspend fun appRollbackVersions(appName: String): List<String> = throw unsupported()
    /** `app.rollback` job. */
    suspend fun startAppRollback(appName: String, version: String, snapshot: Boolean): Long = throw unsupported()
    /** `app.container_log_follow` event source: the last [tail] lines, then new lines as they are written. */
    fun appLogs(appName: String, containerId: String, tail: Int = 500): Flow<LogLine> = kotlinx.coroutines.flow.flow { throw unsupported() }
    /** `app.stats` event source (all apps, every [intervalSeconds]). */
    fun appStats(intervalSeconds: Int = 3): Flow<List<AppStats>> = kotlinx.coroutines.flow.flow { throw unsupported() }

    override fun close()
}
