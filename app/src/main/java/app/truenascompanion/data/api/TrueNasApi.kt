package app.truenascompanion.data.api

import app.truenascompanion.data.model.AlertItem
import app.truenascompanion.data.model.ApiFlavor
import app.truenascompanion.data.model.AppAction
import app.truenascompanion.data.model.AppInfo
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
interface TrueNasApi : AutoCloseable {
    val flavor: ApiFlavor
    val supportsRealtime: Boolean
    val isAlive: Boolean

    suspend fun systemInfo(): SystemInfo
    fun realtimeStats(): Flow<RealtimeStats>
    suspend fun pools(): List<Pool>
    suspend fun disks(): List<Disk>
    suspend fun diskTemperatures(names: List<String>): Map<String, Double>
    suspend fun datasets(): List<Dataset>
    suspend fun apps(): List<AppInfo>
    suspend fun appAction(app: AppInfo, action: AppAction)
    suspend fun alerts(): List<AlertItem>
    suspend fun dismissAlert(uuid: String)
    suspend fun services(): List<ServiceInfo>
    suspend fun serviceAction(service: String, start: Boolean)
    suspend fun reboot()
    suspend fun shutdown()

    override fun close()
}
