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
    /** [fieldErrors]: the middleware's per-field validation errors (`error.data.extra`), e.g. `ssh_update.tcpport`. */
    class Rpc(val code: Int, val errname: String?, message: String, val fieldErrors: List<FieldError> = emptyList()) : TrueNasException(message)
    class Http(val code: Int, message: String) : TrueNasException(message)
    class JobFailed(message: String) : TrueNasException(message)
    class Unsupported(message: String) : TrueNasException(message)
    /** [requestSent]: the request had gone out before the connection dropped (it may have run on the NAS). */
    class NotConnected(val requestSent: Boolean = false) : TrueNasException("Not connected to the server.")
    /** 1.7.1: the connection dropped after a change was sent; it may or may not have been carried out. */
    class Interrupted : TrueNasException(
        "The connection dropped after the request was sent, so it may already have been carried out on the NAS. " +
            "Refresh to check before trying again.",
    )
    class NoServer : TrueNasException("No server configured.")
    /** Password sign-in needs user interaction (password and/or 2FA code). */
    class LoginRequired : TrueNasException("Sign in to continue.")
    class PasswordRejected(message: String = "Wrong username or password.") : TrueNasException(message)
    class OtpLockout : TrueNasException("Too many wrong two-factor codes. Please sign in again.")
    class TokenRejected : TrueNasException("The saved session has expired.")
    /** 1.7.1: the address is plain http://; credentials are never sent over it. */
    class InsecureAddress(val address: String) : TrueNasException(INSECURE_ADDRESS_MESSAGE)
    /**
     * 1.7.1: no saved session worked on the local / Tailscale / VPN address. The remembered password is only ever used
     * on the remote address, so the caller moves on to the next route (or asks the user).
     */
    class SessionNotOnThisRoute : TrueNasException("The saved session didn't work on this address.")
}

const val INSECURE_ADDRESS_MESSAGE =
    "This address uses http://, which isn't encrypted. To protect your password, API key and sessions, TrueNAS " +
        "Companion only signs in over HTTPS. Edit the server and use its https:// address (a self-signed certificate is fine: " +
        "you'll be asked to trust it once)."

/** One middleware validation error: [attribute] is the dotted path (`smb_update.netbiosname`, `data.tcpport`). */
data class FieldError(val attribute: String, val message: String) {
    /** The field name without the method/argument prefix (`netbiosname`); list items (`bindip.0`) map to the list. */
    val field: String get() = attribute.split('.').filter { it.isNotEmpty() && it.toIntOrNull() == null }.lastOrNull() ?: attribute
}

/** Per-field validation messages of a failed call (field name -> message); empty for other errors. */
fun Throwable.fieldErrors(): Map<String, String> =
    (this as? TrueNasException.Rpc)?.fieldErrors?.groupBy { it.field }?.mapValues { (_, v) -> v.joinToString("\n") { it.message } } ?: emptyMap()

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
                "Check the address and port: TrueNAS's HTTPS port is usually 443.", t)
        chain.any { it is UnknownHostException } ->
            TrueNasException.Unreachable("Host not found. Check the address and that your phone is on the right network (or VPN).", t)
        chain.any { it is ConnectException || it is NoRouteToHostException } ->
            TrueNasException.Unreachable("Could not connect to the server. Is it online and reachable on this port?", t)
        chain.any { it is SocketTimeoutException } -> TrueNasException.Timeout("Connection timed out.")
        chain.any { it is EOFException } ->
            TrueNasException.Unreachable("The connection was closed unexpectedly. Check that this port serves HTTPS (TrueNAS uses 443 by default).", t)
        else -> TrueNasException.Unreachable(t.message ?: t.javaClass.simpleName, t)
    }
}

fun Throwable.userMessage(): String = when (this) {
    is TrueNasException.AuthFailed -> message ?: "API key rejected."
    is TrueNasException -> message ?: "Unknown error"
    else -> message ?: javaClass.simpleName
}

/** Shown when TrueNAS answers `false` / 401 to an API key. */
const val API_KEY_REJECTED_MESSAGE =
    "API key rejected. TrueNAS revokes API keys that reach it over an insecure connection. This also happens " +
        "when you use https:// but a reverse proxy (for example Nginx Proxy Manager) forwards to TrueNAS over plain " +
        "http. Fix: set the proxy's upstream to https://<truenas>:443, then reset the key in TrueNAS " +
        "(My API Keys › Edit › Reset). Or switch this server to \"Password\" sign-in."
