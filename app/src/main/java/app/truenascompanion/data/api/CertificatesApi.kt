package app.truenascompanion.data.api

import app.truenascompanion.data.model.AcmeAuthenticator
import app.truenascompanion.data.model.AcmeRequest
import app.truenascompanion.data.model.CertImport
import app.truenascompanion.data.model.CertKind
import app.truenascompanion.data.model.NasCertificate
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.ByteArrayInputStream
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Credentials › Certificates (1.2.0), TrueNAS 25.10 (`api/v25_10_2/certificate.py`, `plugins/crypto_`):
 * `certificate.query`, `certificate.create` (job; IMPORTED / CSR / ACME), `certificate.update` (job; `renew_days`),
 * `certificate.acme_server_choices`, `acme.dns.authenticator.query`, `webui.crypto.get_certificate_domain_names`,
 * and for the web UI certificate `system.general.ui_certificate_choices` / `config` / `update` / `checkin`.
 * ACME certificates are renewed by TrueNAS itself (daily `certificate.renew_certs`, private); only `renew_days` can be set.
 */
class CertificatesApi(private val api: TrueNasApi) {

    suspend fun certificates(): List<NasCertificate> =
        api.rpc("certificate.query").arr()?.mapNotNull { it.obj()?.let(::parse) }
            ?.sortedWith(compareBy({ it.kind.ordinal }, { it.name.lowercase() })) ?: emptyList()

    /** Id of the certificate the web UI uses (`system.general.config().ui_certificate`). */
    suspend fun uiCertificateId(): Int? {
        val v = api.rpc("system.general.config").obj()?.get("ui_certificate")
        return v.obj()?.long("id")?.toInt() ?: v.prim()?.intOrNull
    }

    /** `{id: name}` of certificates allowed for the web UI (no CAs or CSRs). */
    suspend fun uiCertificateChoices(): Map<Int, String> =
        api.rpc("system.general.ui_certificate_choices").obj()
            ?.mapNotNull { (k, v) -> k.toIntOrNull()?.let { it to (v.prim()?.contentOrNull ?: k) } }?.toMap() ?: emptyMap()

    suspend fun acmeServers(): Map<String, String> =
        api.rpc("certificate.acme_server_choices").obj()?.mapValues { (_, v) -> v.prim()?.contentOrNull ?: "" } ?: emptyMap()

    suspend fun dnsAuthenticators(): List<AcmeAuthenticator> =
        api.rpc("acme.dns.authenticator.query").arr()?.mapNotNull { e ->
            val o = e.obj() ?: return@mapNotNull null
            AcmeAuthenticator(o.long("id")?.toInt() ?: return@mapNotNull null, o.str("name") ?: "Authenticator", o["attributes"].obj()?.str("authenticator"))
        } ?: emptyList()

    suspend fun domainNames(csrId: Int): List<String> =
        api.rpc("webui.crypto.get_certificate_domain_names", JsonPrimitive(csrId)).arr()?.mapNotNull { it.prim()?.contentOrNull } ?: emptyList()

    suspend fun import(input: CertImport): JsonElement? = api.callJob("certificate.create", importJson(input))

    /** Creates a CSR (RSA 2048, SHA256 like the web UI's defaults) and returns its id. */
    suspend fun createCsr(name: String, common: String, sans: List<String>): Int? =
        api.callJob("certificate.create", csrJson(name, common, sans)).obj()?.long("id")?.toInt()

    /** ACME issuing can take minutes (DNS propagation); the job is awaited for up to 10 minutes. */
    suspend fun createAcme(req: AcmeRequest): JsonElement? {
        val csrId = req.csrId ?: createCsr("${req.name}_csr", req.newCsrCommon ?: error("Enter a domain"), req.newCsrSans)
            ?: throw TrueNasException.JobFailed("The CSR could not be created")
        return api.callJob("certificate.create", acmeJson(req, csrId), timeoutMs = 600_000)
    }

    suspend fun setRenewDays(id: Int, days: Int): JsonElement? =
        api.callJob("certificate.update", JsonPrimitive(id), buildJsonObject { put("renew_days", days.coerceIn(1, 30)) })

    /**
     * Switches the web UI certificate. TrueNAS restarts its web server after [restartDelaySec] and rolls the change
     * back after [rollbackSec] unless [checkin] is called from a working connection.
     */
    suspend fun setUiCertificate(id: Int, restartDelaySec: Int = 3, rollbackSec: Int = UI_CERT_ROLLBACK_SECONDS) {
        api.rpc("system.general.update", buildJsonObject {
            put("ui_certificate", id)
            put("ui_restart_delay", restartDelaySec)
            put("rollback_timeout", rollbackSec)
        })
    }

    suspend fun checkin() { api.rpc("system.general.checkin") }

    companion object {
        /** Long enough to review and trust the new certificate on the phone. */
        const val UI_CERT_ROLLBACK_SECONDS = 600

        fun importJson(i: CertImport) = buildJsonObject {
            put("name", i.name.trim())
            put("create_type", "CERTIFICATE_CREATE_IMPORTED")
            put("certificate", i.certificate.trim())
            put("privatekey", i.privateKey.trim())
            i.passphrase?.takeIf { it.isNotEmpty() }?.let { put("passphrase", it) }
            put("add_to_trusted_store", i.addToTrustedStore)
        }

        fun csrJson(name: String, common: String, sans: List<String>) = buildJsonObject {
            put("name", name.trim())
            put("create_type", "CERTIFICATE_CREATE_CSR")
            put("key_type", "RSA")
            put("key_length", 2048)
            put("digest_algorithm", "SHA256")
            put("common", common.trim())
            putJsonArray("san") { (listOf(common.trim()) + sans.map { it.trim() }).filter { it.isNotEmpty() }.distinct().forEach { add(JsonPrimitive(it)) } }
        }

        fun acmeJson(req: AcmeRequest, csrId: Int) = buildJsonObject {
            put("name", req.name.trim())
            put("create_type", "CERTIFICATE_CREATE_ACME")
            put("csr_id", csrId)
            put("tos", true)
            put("acme_directory_uri", req.directoryUri)
            put("renew_days", req.renewDays.coerceIn(1, 30))
            putJsonObject("dns_mapping") { req.dnsMapping.forEach { (d, id) -> put(d, id) } }
        }

        private val UNTIL: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE MMM d HH:mm:ss yyyy", Locale.US)

        /** `until` / `from` are UTC strings like `Tue Oct  6 10:00:00 2026` (the middleware parses them as UTC). */
        fun parseCertDate(s: String?): Long? {
            if (s.isNullOrBlank()) return null
            val norm = s.trim().replace(Regex("\\s+"), " ")
            return runCatching { LocalDateTime.parse(norm, UNTIL).toInstant(ZoneOffset.UTC).toEpochMilli() }.getOrNull()
                ?: runCatching { java.time.OffsetDateTime.parse(norm).toInstant().toEpochMilli() }.getOrNull()
        }

        /** Issuer and self-signed flag from the PEM (first certificate of the chain). */
        fun pemInfo(pem: String?): Pair<String?, Boolean>? {
            if (pem.isNullOrBlank() || !pem.contains("BEGIN CERTIFICATE")) return null
            return runCatching {
                val x = CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(pem.trim().toByteArray())) as X509Certificate
                val issuer = rdn(x.issuerX500Principal.name, "CN") ?: rdn(x.issuerX500Principal.name, "O") ?: x.issuerX500Principal.name
                issuer to (x.issuerX500Principal == x.subjectX500Principal)
            }.getOrNull()
        }

        /** Value of the first [type] attribute in an RFC 2253 DN (`CN=R11,O=Let's Encrypt,C=US`); handles `\,` escapes. */
        internal fun rdn(dn: String, type: String): String? {
            val parts = ArrayList<String>(); val cur = StringBuilder(); var esc = false
            for (ch in dn) {
                when {
                    esc -> { cur.append(ch); esc = false }
                    ch == '\\' -> esc = true
                    ch == ',' || ch == '+' -> { parts += cur.toString(); cur.clear() }
                    else -> cur.append(ch)
                }
            }
            parts += cur.toString()
            return parts.map { it.trim() }.firstOrNull { it.substringBefore('=').trim().equals(type, true) }
                ?.substringAfter('=')?.trim()?.removeSurrounding("\"")?.takeIf { it.isNotEmpty() }
        }

        fun parse(o: JsonObject): NasCertificate? {
            val id = o.long("id")?.toInt() ?: return null
            val kind = when {
                o.bool("cert_type_CSR") == true -> CertKind.CSR
                o.bool("cert_type_CA") == true -> CertKind.CA
                else -> CertKind.CERTIFICATE
            }
            val pem = pemInfo(o.str("certificate"))
            val acme = o["acme"].let { it != null && it != JsonNull } || !o.str("acme_uri").isNullOrBlank()
            return NasCertificate(
                id = id,
                name = o.str("name") ?: "#$id",
                kind = kind,
                common = o.str("common"),
                sans = o["san"].arr()?.mapNotNull { it.prim()?.contentOrNull } ?: emptyList(),
                issuer = pem?.first ?: if (acme) "ACME" else null,
                selfSigned = pem?.second ?: false,
                fromMillis = parseCertDate(o.str("from")),
                untilMillis = parseCertDate(o.str("until")),
                expired = o.bool("expired") ?: false,
                keyType = o.str("key_type"),
                keyLength = o.long("key_length")?.toInt(),
                digest = o.str("digest_algorithm"),
                fingerprint = o.str("fingerprint"),
                acme = acme,
                acmeUri = o.str("acme_uri"),
                renewDays = o.long("renew_days")?.toInt(),
                addToTrustedStore = o.bool("add_to_trusted_store") ?: false,
                parsed = o.bool("parsed") ?: true,
                dn = o.str("DN"),
            )
        }
    }
}
