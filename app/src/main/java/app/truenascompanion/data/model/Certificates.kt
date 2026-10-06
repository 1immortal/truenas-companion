package app.truenascompanion.data.model

/** Certificates and CAs (1.2.0). In 25.10 CAs are certificates with `cert_type_CA`; CSRs have `cert_type_CSR`. */
enum class CertKind(val label: String) { CERTIFICATE("Certificate"), CA("CA"), CSR("CSR") }

enum class CertStatus { OK, EXPIRING, EXPIRED, NOT_APPLICABLE }

data class NasCertificate(
    val id: Int,
    val name: String,
    val kind: CertKind,
    val common: String?,
    val sans: List<String>,
    /** Issuer common name / organization (from the PEM on the phone; the API has no issuer field). */
    val issuer: String?,
    val selfSigned: Boolean,
    val fromMillis: Long?,
    val untilMillis: Long?,
    val expired: Boolean,
    val keyType: String?,
    val keyLength: Int?,
    val digest: String?,
    val fingerprint: String?,
    /** Issued through ACME (TrueNAS renews it by itself `renew_days` before expiry). */
    val acme: Boolean,
    val acmeUri: String?,
    val renewDays: Int?,
    val addToTrustedStore: Boolean,
    val parsed: Boolean,
    val dn: String?,
) {
    /** Whole days left, rounded down like the middleware's `(until - now).days`. Negative when expired. */
    fun daysLeft(nowMillis: Long): Long? = untilMillis?.let { Math.floorDiv(it - nowMillis, 86_400_000L) }

    fun status(nowMillis: Long, warnDays: Int): CertStatus {
        if (kind == CertKind.CSR) return CertStatus.NOT_APPLICABLE
        val d = daysLeft(nowMillis) ?: return if (expired) CertStatus.EXPIRED else CertStatus.NOT_APPLICABLE
        return when {
            expired || d < 0 -> CertStatus.EXPIRED
            d <= warnDays -> CertStatus.EXPIRING
            else -> CertStatus.OK
        }
    }

    /** Names this certificate covers (SANs without the "DNS:" prefix, or the common name). */
    val domains: List<String> get() = sans.map { it.substringAfter(':').trim() }.filter { it.isNotEmpty() }.ifEmpty { listOfNotNull(common) }
}

data class AcmeAuthenticator(val id: Int, val name: String, val type: String?)

/** Input of the import sheet. */
data class CertImport(val name: String, val certificate: String, val privateKey: String, val passphrase: String? = null, val addToTrustedStore: Boolean = false)

/** Input of the ACME sheet: an existing CSR, or a new one created for [common] + [sans] first. */
data class AcmeRequest(
    val name: String,
    val csrId: Int?,
    val newCsrCommon: String? = null,
    val newCsrSans: List<String> = emptyList(),
    val directoryUri: String,
    /** Domain → DNS authenticator id. */
    val dnsMapping: Map<String, Int>,
    val renewDays: Int = 10,
)
