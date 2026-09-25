package app.truenascompanion.data.api

import app.truenascompanion.data.model.ServerConfig
import app.truenascompanion.data.net.HttpClients

object TrueNasConnector {
    /**
     * Connects and authenticates. Tries the JSON-RPC WebSocket API first (TrueNAS 25.04+); if that endpoint
     * does not exist on the server, falls back to the legacy REST API v2.0.
     */
    suspend fun connect(server: ServerConfig, apiKey: String): TrueNasApi {
        val (client, tm) = HttpClients.create(server)
        if (!server.forceRest) {
            try {
                return WebSocketTrueNasApi.connect(client, tm, server, apiKey)
            } catch (e: TrueNasException.EndpointNotFound) {
                // Older SCALE without /api/current -> REST below.
            }
        }
        return RestTrueNasApi.connect(client, tm, server, apiKey)
    }
}
