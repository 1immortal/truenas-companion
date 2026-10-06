package app.truenascompanion.data.net

import android.annotation.SuppressLint
import app.truenascompanion.data.model.ServerConfig
import okhttp3.OkHttpClient
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.text.DateFormat
import java.util.concurrent.TimeUnit
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/** Human readable details of a server certificate, shown in the "Trust this server?" dialog. */
data class CertificateInfo(
    val sha256: String,
    val subject: String,
    val issuer: String,
    val validFrom: String,
    val validUntil: String,
    val selfSigned: Boolean,
)

fun X509Certificate.sha256Fingerprint(): String =
    MessageDigest.getInstance("SHA-256").digest(encoded).joinToString(":") { "%02X".format(it) }

fun X509Certificate.toInfo(): CertificateInfo {
    val df = DateFormat.getDateInstance(DateFormat.MEDIUM)
    return CertificateInfo(
        sha256 = sha256Fingerprint(),
        subject = subjectX500Principal.name,
        issuer = issuerX500Principal.name,
        validFrom = df.format(notBefore),
        validUntil = df.format(notAfter),
        selfSigned = subjectX500Principal == issuerX500Principal,
    )
}

/**
 * Trust manager implementing "trust this server" (trust on first use):
 * - If the user pinned a certificate fingerprint for this server and the leaf certificate matches,
 *   the connection is accepted (even if self-signed / wrong hostname).
 * - Otherwise normal system CA validation applies. Nothing is blindly trusted.
 * The last seen chain is recorded so the UI can offer to pin it after a failure.
 */
@SuppressLint("CustomX509TrustManager") // Delegates to the platform trust manager; only an explicitly pinned leaf is accepted in addition.
class PinningTrustManager(private val pinnedSha256: String?, private val requirePin: Boolean = false) : X509TrustManager {
    private val system: X509TrustManager = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        .apply { init(null as KeyStore?) }
        .trustManagers.filterIsInstance<X509TrustManager>().first()

    @Volatile
    var lastChain: Array<X509Certificate>? = null
        private set

    fun matchesPin(cert: X509Certificate?): Boolean =
        pinnedSha256 != null && cert != null && cert.sha256Fingerprint().equals(pinnedSha256, ignoreCase = true)

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) =
        throw CertificateException("Client certificates are not supported")

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
        lastChain = chain
        if (matchesPin(chain.firstOrNull())) return
        // 1.2.0: after the web UI certificate was changed from the app, even a CA-signed chain needs a fresh review.
        if (requirePin) throw CertificateException("The server certificate changed; review it before connecting")
        system.checkServerTrusted(chain, authType)
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = system.acceptedIssuers
}

/**
 * WebSocket keep-alive ping, per purpose. Each ping wakes the radio (and on mobile data keeps it in a high-power state
 * for several seconds), so longer is cheaper; it must stay below reverse-proxy idle timeouts (nginx / Nginx Proxy
 * Manager default `proxy_read_timeout` is 60 s).
 */
enum class Keepalive(val seconds: Long) {
    /** App on screen: detect dead connections reasonably fast. */
    FOREGROUND(30),
    /** Instant-alerts service: as rare as the proxy allows. */
    LONG_LIVED(45),
    /** One-shot background checks: a few calls and done, no pings needed (calls have their own timeouts). */
    NONE(0),
}

object HttpClients {
    private val base: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    fun create(server: ServerConfig, keepalive: Keepalive = Keepalive.FOREGROUND): Pair<OkHttpClient, PinningTrustManager> {
        val tm = PinningTrustManager(server.pinnedCertSha256, requirePin = server.certReviewRequired)
        val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(tm), null) }
        val defaultVerifier = HttpsURLConnection.getDefaultHostnameVerifier()
        val verifier = HostnameVerifier { host, session ->
            val leaf = runCatching { session.peerCertificates.firstOrNull() as? X509Certificate }.getOrNull()
            tm.matchesPin(leaf) || defaultVerifier.verify(host, session)
        }
        val client = base.newBuilder()
            .sslSocketFactory(ssl.socketFactory, tm)
            .hostnameVerifier(verifier)
            .pingInterval(keepalive.seconds, TimeUnit.SECONDS)
            .build()
        return client to tm
    }
}
