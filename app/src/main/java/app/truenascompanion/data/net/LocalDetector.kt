package app.truenascompanion.data.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * Finds the TrueNAS web UI on a LAN host whose port/scheme the user doesn't remember (e.g. the UI was moved off
 * 80/443 because Nginx Proxy Manager runs on the same box).
 *
 * All candidates are probed in parallel with short timeouts. A candidate counts as TrueNAS when `/api/versions`
 * (unauthenticated on 25.04+) returns a JSON list of `vXX…` versions, or the login page mentions TrueNAS (older
 * releases). HTTPS is preferred. Detection accepts any certificate because nothing secret is sent; the found URL
 * still goes through the normal certificate check / fingerprint pinning before it is used.
 */
object LocalDetector {
    data class Candidate(val scheme: String, val port: Int) {
        fun url(host: String): String {
            val default = (scheme == "https" && port == 443) || (scheme == "http" && port == 80)
            return "$scheme://${hostForUrl(host)}" + if (default) "" else ":$port"
        }
    }

    /** 1.7.1: HTTPS only (the app no longer signs in over http, and cleartext traffic is disabled app-wide). */
    val CANDIDATES = listOf(
        Candidate("https", 443), Candidate("https", 444), Candidate("https", 8443), Candidate("https", 9443),
    )

    data class Found(val url: String, val https: Boolean)

    /** Strips a scheme, port and path the user may have typed: "https://nas.lan:8443/ui" -> "nas.lan". */
    fun hostOf(input: String): String? {
        var h = input.trim().substringAfter("://").substringBefore('/').substringBefore('?')
        if (h.startsWith("[")) h = h.substringBefore(']') + "]"          // IPv6 literal
        else if (h.count { it == ':' } == 1) h = h.substringBefore(':')  // host:port
        return h.takeIf { it.isNotBlank() && it.none(Char::isWhitespace) }
    }

    private fun hostForUrl(host: String) = if (host.contains(':') && !host.startsWith("[")) "[$host]" else host

    /** Decides whether a response body identifies TrueNAS (pure, unit tested). */
    fun looksLikeTrueNas(path: String, body: String): Boolean = when (path) {
        "/api/versions" -> runCatching {
            (Json.parseToJsonElement(body) as? JsonArray)?.any { (it as? JsonPrimitive)?.content?.matches(Regex("v\\d+\\.\\d+.*")) == true } == true
        }.getOrDefault(false)
        else -> body.contains("TrueNAS", ignoreCase = true) && !body.contains("Nginx Proxy Manager", ignoreCase = true)
    }

    /** Preference among the candidates that answered like TrueNAS: https first, then the list order. */
    fun pick(found: List<Candidate>, order: List<Candidate> = CANDIDATES): Candidate? = order.firstOrNull { it in found }

    suspend fun detect(host: String, client: OkHttpClient = detectionClient(), candidates: List<Candidate> = CANDIDATES): Found? = withContext(Dispatchers.IO) {
        val hits = coroutineScope {
            candidates.map { c -> async { c.takeIf { isTrueNas(client, c.url(host)) } } }.awaitAll().filterNotNull()
        }
        pick(hits, candidates)?.let { Found(it.url(host), it.scheme == "https") }
    }

    private fun isTrueNas(client: OkHttpClient, base: String): Boolean {
        for (path in listOf("/api/versions", "/")) {
            val body = runCatching {
                client.newCall(Request.Builder().url(base + path).get().build()).execute().use { r ->
                    // An http port that redirects to https isn't an http hit (the https candidate is found on its own).
                    if (!r.isSuccessful || r.request.url.scheme != base.substringBefore(':')) null else r.body.source().let { src -> src.request(64 * 1024); src.buffer.snapshot().utf8() }
                }
            }.getOrNull() ?: if (path == "/api/versions") continue else return false
            if (looksLikeTrueNas(path, body)) return true
        }
        return false
    }

    /** Short timeouts; accepts self-signed certificates for detection only (no credentials are ever sent here). */
    fun detectionClient(): OkHttpClient {
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustAll), null) }
        return OkHttpClient.Builder()
            .connectTimeout(1500, TimeUnit.MILLISECONDS)
            .readTimeout(2500, TimeUnit.MILLISECONDS)
            .callTimeout(4, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(false)
            .sslSocketFactory(ssl.socketFactory, trustAll)
            .hostnameVerifier { _, _ -> true }
            .build()
    }
}
