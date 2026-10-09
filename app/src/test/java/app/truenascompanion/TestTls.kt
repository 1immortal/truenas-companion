package app.truenascompanion

import app.truenascompanion.data.net.sha256Fingerprint
import okhttp3.mockwebserver.MockWebServer
import java.io.File
import java.security.KeyStore
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory

/**
 * 1.7.1: the app refuses plain http, so test servers speak HTTPS with a throwaway self-signed certificate made by the
 * JDK's keytool at test time (nothing is committed). Tests pin it like a user who tapped "Trust".
 */
object TestTls {
    private const val PASS = "test-only"

    private val keyStore: KeyStore by lazy {
        val dir = File(System.getProperty("java.io.tmpdir"), "truenas-test-tls").apply { mkdirs() }
        val file = File(dir, "server-${ProcessHandle.current().pid()}.p12")
        if (!file.exists()) {
            val keytool = File(System.getProperty("java.home"), "bin/keytool").path
            val p = ProcessBuilder(
                keytool, "-genkeypair", "-alias", "nas", "-keyalg", "EC", "-groupname", "secp256r1", "-validity", "30",
                "-dname", "CN=nas.example.com", "-ext", "SAN=ip:127.0.0.1,dns:localhost",
                "-storetype", "PKCS12", "-keystore", file.path, "-storepass", PASS, "-keypass", PASS,
            ).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            check(p.waitFor() == 0) { "keytool failed: $out" }
            file.deleteOnExit()
        }
        KeyStore.getInstance("PKCS12").apply { file.inputStream().use { load(it, PASS.toCharArray()) } }
    }

    val certificate: X509Certificate get() = keyStore.getCertificate("nas") as X509Certificate

    /** Pin for [ServerConfig.pinnedCertSha256] and friends. */
    val pin: String get() = certificate.sha256Fingerprint()

    val tlsSocketFactory: SSLSocketFactory by lazy {
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(keyStore, PASS.toCharArray()) }
        SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }.socketFactory
    }

    fun MockWebServer.https(): MockWebServer = apply { useHttps(tlsSocketFactory, false) }
}
