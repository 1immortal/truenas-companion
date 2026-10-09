package app.truenascompanion.data.net

import app.truenascompanion.data.api.TrueNasException
import app.truenascompanion.data.model.Route
import app.truenascompanion.data.model.ServerConfig

/**
 * 1.7.1 (security C-1/H-2): moves saved http:// addresses to HTTPS.
 *
 * Since 1.7.1 the app never signs in over plain http. For each http address of a server (usually the home address,
 * `http://192.168.1.50`) it looks for TrueNAS's HTTPS on the same host (port 443 first, then 444, 8443, 9443; nothing
 * secret is sent), then checks the certificate without credentials:
 * - CA-valid certificate (and not a different NAS): the address is switched to https right away;
 * - self-signed certificate: the user is shown the certificate once ("Trust and use HTTPS" / "Not now");
 * - no HTTPS found (e.g. not at home right now) or a different NAS answered: the address stays off and a banner says why,
 *   with a "Set up HTTPS" button to try again later. The remote address keeps working meanwhile.
 */
object HttpsUpgrade {
    sealed interface Outcome {
        val route: Route

        /** HTTPS works with a certificate the phone already trusts: use [url]. */
        data class Upgraded(override val route: Route, val url: String) : Outcome
        /** HTTPS answers with a self-signed certificate: ask the user once. [sameNas] as in [LocalCheck.check]. */
        data class NeedsTrust(override val route: Route, val from: String, val url: String, val cert: CertificateInfo, val sameNas: Boolean?) : Outcome
        /** No HTTPS on that host right now, or another machine answered ([differentNas]). The address stays off. */
        data class NotAvailable(override val route: Route, val from: String, val differentNas: Boolean = false) : Outcome
    }

    /** The http address of [route], or null when it's https / not set. */
    fun insecureUrl(s: ServerConfig, route: Route): String? = when (route) {
        Route.REMOTE -> s.url
        Route.LOCAL -> s.localUrl
        Route.TAILSCALE -> s.tailscaleUrl
        Route.VPN -> null
    }?.takeIf { it.startsWith("http://", ignoreCase = true) }

    /**
     * Tries one address. [detect] finds TrueNAS's HTTPS on a host; [check] is [LocalCheck.check] (throws
     * [TrueNasException.UntrustedCertificate] with the certificate for a self-signed one).
     */
    suspend fun probe(
        server: ServerConfig,
        route: Route,
        detect: suspend (host: String) -> String? = { h -> LocalDetector.detect(h)?.url },
        check: suspend (candidate: ServerConfig, reference: ServerConfig?) -> Boolean? = { c, r -> if (r == null) checkAlone(c) else LocalCheck.check(c, r) },
    ): Outcome? {
        val from = insecureUrl(server, route) ?: return null
        val host = LocalDetector.hostOf(from) ?: return Outcome.NotAvailable(route, from)
        val url = runCatching { detect(host) }.getOrNull() ?: return Outcome.NotAvailable(route, from)
        // Compare with the remote address when that one is HTTPS (same NAS check via /api/boot_id, no credentials).
        val reference = server.takeIf { route != Route.REMOTE && it.isHttps }?.forRoute(Route.REMOTE)
        val candidate = server.copy(url = url, pinnedCertSha256 = null, certReviewRequired = false, activeRoute = route)
        return try {
            when (check(candidate, reference)) {
                false -> Outcome.NotAvailable(route, from, differentNas = true)
                else -> Outcome.Upgraded(route, url)
            }
        } catch (e: TrueNasException.UntrustedCertificate) {
            val cert = e.certificate ?: return Outcome.NotAvailable(route, from)
            // Same-NAS check over a connection pinned to exactly the certificate shown (still no credentials).
            val same = runCatching { check(candidate.copy(pinnedCertSha256 = cert.sha256), reference) }.getOrNull()
            if (same == false) Outcome.NotAvailable(route, from, differentNas = true)
            else Outcome.NeedsTrust(route, from, url, cert, same)
        } catch (e: Throwable) {
            Outcome.NotAvailable(route, from)
        }
    }

    /** Applies an accepted outcome to the saved server ([pin] for a trusted self-signed certificate). */
    fun apply(s: ServerConfig, route: Route, url: String, pin: String?): ServerConfig = when (route) {
        Route.REMOTE -> s.copy(url = url, pinnedCertSha256 = pin)
        Route.LOCAL -> s.copy(localUrl = url, localPinnedCertSha256 = pin)
        Route.TAILSCALE -> s.copy(tailscaleUrl = url, tailscalePinnedCertSha256 = pin)
        Route.VPN -> s
    }

    /** TLS check of an address that has no HTTPS reference to compare with (the remote address itself was http). */
    private suspend fun checkAlone(candidate: ServerConfig): Boolean? = LocalCheck.check(candidate, candidate).let { null }
}

/** A self-signed certificate waiting for the user's decision. */
data class HttpsTrustRequest(val serverId: String, val serverName: String, val outcome: HttpsUpgrade.Outcome.NeedsTrust)

/**
 * Runs [HttpsUpgrade] once per server after the upgrade to 1.7.1 (and again on "Set up HTTPS"), applies CA-trusted
 * results, queues self-signed certificates for the trust prompt, and remembers what to tell the user.
 */
class HttpsUpgradeManager(
    private val servers: suspend () -> List<ServerConfig>,
    private val update: suspend (String, (ServerConfig) -> ServerConfig) -> Unit,
    private val onChanged: suspend (serverId: String, route: Route) -> Unit = { _, _ -> },
    private val probe: suspend (ServerConfig, Route) -> HttpsUpgrade.Outcome? = { s, r -> HttpsUpgrade.probe(s, r) },
) {
    private val _requests = kotlinx.coroutines.flow.MutableStateFlow<List<HttpsTrustRequest>>(emptyList())
    /** Pending trust prompts; the UI shows the first one. */
    val requests: kotlinx.coroutines.flow.StateFlow<List<HttpsTrustRequest>> = _requests

    private val _busy = kotlinx.coroutines.flow.MutableStateFlow<Set<String>>(emptySet())
    /** Servers being checked right now ("Set up HTTPS" shows progress). */
    val busy: kotlinx.coroutines.flow.StateFlow<Set<String>> = _busy

    private val _notes = kotlinx.coroutines.flow.MutableStateFlow<Map<String, String>>(emptyMap())
    /** Result of the last check per server, for the banner (e.g. "No HTTPS found on 192.168.1.50 right now"). */
    val notes: kotlinx.coroutines.flow.StateFlow<Map<String, String>> = _notes

    private val mutex = kotlinx.coroutines.sync.Mutex()

    /** The automatic one-time attempt for every server that still has http addresses. */
    suspend fun runAutomatic() = mutex.withLockNow {
        for (s in servers()) {
            if (s.httpsUpgradeTried || s.insecureAddresses.isEmpty()) continue
            attempt(s)
            update(s.id) { it.copy(httpsUpgradeTried = true) }
        }
    }

    /** "Set up HTTPS" on the banner. */
    suspend fun retry(serverId: String) = mutex.withLockNow {
        servers().firstOrNull { it.id == serverId }?.let { attempt(it) }
    }

    private suspend fun attempt(s: ServerConfig) {
        _busy.value = _busy.value + s.id
        try {
            val notes = mutableListOf<String>()
            for (route in s.insecureAddresses) {
                when (val o = probe(s, route)) {
                    null -> Unit
                    is HttpsUpgrade.Outcome.Upgraded -> {
                        update(s.id) { HttpsUpgrade.apply(it, route, o.url, null) }
                        onChanged(s.id, route)
                    }
                    is HttpsUpgrade.Outcome.NeedsTrust ->
                        _requests.value = _requests.value.filterNot { it.serverId == s.id && it.outcome.route == route } +
                            HttpsTrustRequest(s.id, s.name, o)
                    is HttpsUpgrade.Outcome.NotAvailable -> notes += noteFor(o)
                }
            }
            _notes.value = if (notes.isEmpty()) _notes.value - s.id else _notes.value + (s.id to notes.joinToString(" "))
        } finally {
            _busy.value = _busy.value - s.id
        }
    }

    /** "Trust and use HTTPS". */
    suspend fun trust(request: HttpsTrustRequest) {
        val o = request.outcome
        update(request.serverId) { HttpsUpgrade.apply(it, o.route, o.url, o.cert.sha256) }
        _requests.value = _requests.value - request
        onChanged(request.serverId, o.route)
    }

    /** "Not now": the http address stays off; the banner offers to try again. */
    fun notNow(request: HttpsTrustRequest) {
        _requests.value = _requests.value - request
    }

    suspend fun dismissBanner(serverId: String) = update(serverId) { it.copy(httpsBannerDismissed = true) }

    private suspend inline fun <T> kotlinx.coroutines.sync.Mutex.withLockNow(block: () -> T): T {
        lock()
        try { return block() } finally { unlock() }
    }

    companion object {
        fun noteFor(o: HttpsUpgrade.Outcome.NotAvailable): String {
            val host = o.from.substringAfter("://")
            return if (o.differentNas) "A different machine answers HTTPS at $host, so it wasn't used."
            else "No HTTPS answered at $host just now (are you on your home Wi-Fi?)."
        }

        /** Banner text for a server with http addresses (pure, unit tested). */
        fun bannerText(s: ServerConfig): String? {
            val bad = s.insecureAddresses
            if (bad.isEmpty()) return null
            val names = bad.joinToString(" and ") { r ->
                when (r) { Route.REMOTE -> "server address"; Route.LOCAL -> "home address"; Route.TAILSCALE -> "Tailscale address"; Route.VPN -> "VPN" }
            }
            val rest = if (Route.REMOTE in bad) "The app can't sign in until it uses https://."
            else "The app uses the server address (${s.displayHost}) instead, so everything keeps working."
            return "Your $names uses unencrypted http://, so it's turned off. $rest"
        }
    }
}
