package app.truenascompanion.data.api

import app.truenascompanion.data.net.CertificateInfo
import java.io.EOFException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertPathValidatorException
import java.security.cert.CertificateException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

sealed class TrueNasException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class Unreachable(message: String, cause: Throwable? = null) : TrueNasException(message, cause)
    class Timeout(message: String = "The server did not answer in time.") : TrueNasException(message)
    class UntrustedCertificate(val certificate: CertificateInfo?, cause: Throwable?) :
        TrueNasException("The server's HTTPS certificate is not trusted (self-signed or hostname mismatch).", cause)
    class Tls(message: String, cause: Throwable?) : TrueNasException(message, cause)
    class AuthFailed(message: String = "The API key was rejected.") : TrueNasException(message)
    class Forbidden(message: String = "The API key does not have permission for this action.") : TrueNasException(message)
    class MethodNotFound(val method: String) : TrueNasException("Not supported by this TrueNAS version: $method")
    class EndpointNotFound(message: String) : TrueNasException(message)
    class Rpc(val code: Int, val errname: String?, message: String) : TrueNasException(message)
    class Http(val code: Int, message: String) : TrueNasException(message)
    class JobFailed(message: String) : TrueNasException(message)
    class NotConnected : TrueNasException("Not connected to the server.")
    class NoServer : TrueNasException("No server configured.")
}

/** True if the error means "this API method doesn't exist on this server" — used to try an older/newer method. */
fun Throwable.isMethodMissing(): Boolean = when (this) {
    is TrueNasException.MethodNotFound -> true
    is TrueNasException.Rpc -> code == -32601 || errname == "ENOMETHOD" ||
        message?.contains("does not exist", true) == true || message?.contains("Method not found", true) == true
    else -> false
}

/** Converts low level network exceptions into [TrueNasException]s with clear messages. */
fun mapNetworkError(t: Throwable, certificate: () -> CertificateInfo?): TrueNasException {
    if (t is TrueNasException) return t
    val chain = generateSequence(t) { it.cause }.toList()
    return when {
        chain.any { it is CertPathValidatorException } ||
            (chain.any { it is SSLHandshakeException } && chain.any { it is CertificateException }) ||
            chain.any { it is SSLPeerUnverifiedException } ->
            TrueNasException.UntrustedCertificate(certificate(), t)
        chain.any { it is SSLHandshakeException || it is SSLException } ->
            TrueNasException.Tls("Secure connection failed: ${t.message ?: "TLS error"}. " +
                "If the server only speaks HTTP, use an http:// URL.", t)
        chain.any { it is UnknownHostException } ->
            TrueNasException.Unreachable("Host not found. Check the address and that your phone is on the right network (or VPN).", t)
        chain.any { it is ConnectException || it is NoRouteToHostException } ->
            TrueNasException.Unreachable("Could not connect to the server. Is it online and reachable on this port?", t)
        chain.any { it is SocketTimeoutException } -> TrueNasException.Timeout("Connection timed out.")
        chain.any { it is EOFException } ->
            TrueNasException.Unreachable("The connection was closed unexpectedly. If you used https://, the server may only speak http (or vice-versa).", t)
        else -> TrueNasException.Unreachable(t.message ?: t.javaClass.simpleName, t)
    }
}

fun Throwable.userMessage(): String = when (this) {
    is TrueNasException.AuthFailed -> message ?: "API key rejected."
    is TrueNasException -> message ?: "Unknown error"
    else -> message ?: javaClass.simpleName
}
