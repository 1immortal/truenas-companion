package app.truenascompanion.data.api

import app.truenascompanion.data.model.ServerConfig
import java.util.concurrent.ConcurrentHashMap

/**
 * Live, authenticated connections that other parts of the app may borrow instead of opening (and signing in on)
 * their own socket. Owners publish/withdraw; borrowers must never close a borrowed connection.
 *
 * - The instant-alerts service publishes its long-lived connection: the app UI and the periodic check reuse it.
 * - The app's foreground connection is published too: a periodic check that runs while the app is open reuses it.
 */
class SharedConnections {
    private data class Entry(val server: ServerConfig, val api: TrueNasApi, val owner: String)

    private val entries = ConcurrentHashMap<String, MutableList<Entry>>()

    fun publish(server: ServerConfig, api: TrueNasApi, owner: String) {
        entries.compute(server.id) { _, list ->
            (list ?: mutableListOf()).apply { removeAll { it.owner == owner }; add(Entry(server, api, owner)) }
        }
    }

    fun withdraw(serverId: String, api: TrueNasApi) {
        entries.computeIfPresent(serverId) { _, list -> list.apply { removeAll { it.api === api } }.ifEmpty { null } }
    }

    /** An open connection for exactly this server configuration (same URL, credentials mode, pinned cert…), if any. */
    fun borrow(server: ServerConfig, excludeOwner: String? = null): TrueNasApi? =
        entries[server.id]?.toList()
            ?.filter { it.server == server && it.owner != excludeOwner && it.api.isAlive }
            // Prefer the long-lived service connection: it outlives the app's foreground connection.
            ?.sortedBy { if (it.owner == OWNER_SERVICE) 0 else 1 }
            ?.firstOrNull()?.api

    companion object {
        const val OWNER_SERVICE = "instant-service"
        const val OWNER_APP = "app"
    }
}
