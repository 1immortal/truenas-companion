package app.truenascompanion.data.net

import app.truenascompanion.data.api.mapNetworkError
import app.truenascompanion.data.model.ServerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Checks a local address without sending credentials: TLS trust (throws UntrustedCertificate so the usual
 * fingerprint pinning dialog can be shown) and, on 25.04+, whether it's the same NAS as the remote address by
 * comparing the unauthenticated `/api/boot_id`.
 */
object LocalCheck {
    /** @return true = same NAS, false = a different machine answered, null = couldn't tell (older TrueNAS / remote offline). */
    suspend fun check(local: ServerConfig, remote: ServerConfig): Boolean? = withContext(Dispatchers.IO) {
        coroutineScope {
            val remoteId = async { runCatching { bootId(remote) }.getOrNull() }
            val localId = bootId(local, strict = true)
            val r = remoteId.await()
            if (localId == null || r == null) null else localId == r
        }
    }

    private fun bootId(server: ServerConfig, strict: Boolean = false): String? {
        val (client, tm) = HttpClients.create(server, Keepalive.NONE)
        val quick: OkHttpClient = client.newBuilder().connectTimeout(3, TimeUnit.SECONDS).readTimeout(4, TimeUnit.SECONDS)
            .callTimeout(6, TimeUnit.SECONDS).retryOnConnectionFailure(false).build()
        return try {
            quick.newCall(Request.Builder().url(server.url.trimEnd('/') + "/api/boot_id").get().build()).execute().use { r ->
                if (r.isSuccessful) r.body.string().trim().trim('"').takeIf { it.isNotBlank() && it.length < 128 } else null
            }
        } catch (t: Throwable) {
            if (strict) throw mapNetworkError(t) { tm.lastChain?.firstOrNull()?.toInfo() } else null
        }
    }
}
