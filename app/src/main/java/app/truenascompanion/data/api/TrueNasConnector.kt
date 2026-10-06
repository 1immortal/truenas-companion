package app.truenascompanion.data.api

import app.truenascompanion.data.model.ServerConfig
import app.truenascompanion.data.net.HttpClients
import app.truenascompanion.data.net.Keepalive

/** Shown when a server has no JSON-RPC WebSocket API (`/api/current`), i.e. TrueNAS older than 25.04. */
const val WEBSOCKET_API_REQUIRED_MESSAGE =
    "This server doesn't offer the TrueNAS WebSocket API (/api/current). TrueNAS Companion needs TrueNAS 25.04 or newer. " +
        "Update TrueNAS, or check that a reverse proxy in front of it forwards WebSocket connections."

object TrueNasConnector {
    /**
     * Connects and signs in with an API key over the JSON-RPC 2.0 WebSocket API (TrueNAS 25.04+).
     *
     * There is deliberately no fallback to the legacy REST API (`/api/v2.0`): TrueNAS 25.04+ logs every REST
     * request that carries credentials as a `LEGACY_REST` authentication and raises the "Deprecated REST API usage"
     * alert, and 26.04 removes REST altogether. A server without `/api/current` gets a clear error instead.
     */
    suspend fun connect(server: ServerConfig, apiKey: String, keepalive: Keepalive = Keepalive.FOREGROUND): TrueNasApi {
        val (client, tm) = HttpClients.create(server, keepalive)
        try {
            return WebSocketTrueNasApi.connect(client, tm, server, apiKey)
        } catch (e: TrueNasException.EndpointNotFound) {
            throw TrueNasException.Unsupported(WEBSOCKET_API_REQUIRED_MESSAGE)
        }
    }
}
