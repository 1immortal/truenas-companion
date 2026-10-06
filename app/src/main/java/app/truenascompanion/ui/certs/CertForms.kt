package app.truenascompanion.ui.certs

/** Input helpers and validation for the import and ACME sheets (1.2.0). Mirrors the middleware's checks. */
object CertForms {
    // RE_CERTIFICATE_NAME in the 25.10 middleware certificate API models, max 120 characters.
    private val NAME = Regex("^[a-z0-9_\\-]+$", RegexOption.IGNORE_CASE)
    private val BLOCK = Regex("-----BEGIN ([A-Z0-9 ]+)-----[\\s\\S]*?-----END \\1-----")

    fun nameError(name: String): String? = when {
        name.isBlank() -> "Enter a name"
        name.length > 120 -> "At most 120 characters"
        !NAME.matches(name.trim()) -> "Only letters, digits, - and _"
        else -> null
    }

    /** Splits pasted or picked PEM text into the certificate (with chain) and the private key. */
    fun splitPem(text: String): Pair<String?, String?> {
        val blocks = BLOCK.findAll(text).toList()
        val certs = blocks.filter { it.groupValues[1] == "CERTIFICATE" }.joinToString("\n") { it.value }.ifBlank { null }
        val key = blocks.firstOrNull { it.groupValues[1].endsWith("PRIVATE KEY") }?.value
        return certs to key
    }

    fun importError(name: String, certificate: String, privateKey: String): String? =
        nameError(name) ?: when {
            splitPem(certificate).first == null -> "Paste a PEM certificate (-----BEGIN CERTIFICATE-----)"
            splitPem(privateKey).second == null -> "Paste the PEM private key (-----BEGIN … PRIVATE KEY-----)"
            else -> null
        }

    /** Domains typed as "a.example.com, b.example.com" or one per line. */
    fun domains(text: String): List<String> = text.split(',', ' ', '\n', '\t').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct()

    private val DOMAIN = Regex("^(\\*\\.)?([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,63}$")

    fun domainError(d: String): String? = if (DOMAIN.matches(d)) null else "\"$d\" isn't a valid domain name"

    fun acmeError(name: String, domains: List<String>, mapping: Map<String, Int>, tos: Boolean, renewDays: Int): String? =
        nameError(name) ?: when {
            domains.isEmpty() -> "Enter at least one domain"
            else -> domains.firstNotNullOfOrNull { domainError(it) }
        } ?: when {
            domains.any { it !in mapping } -> "Choose a DNS authenticator for every domain"
            renewDays !in 1..30 -> "Renew 1 to 30 days before expiry"
            !tos -> "Accept the ACME terms of service"
            else -> null
        }

    /** Let's Encrypt production first, staging after (from `certificate.acme_server_choices`). */
    fun defaultDirectory(choices: Map<String, String>): String? =
        choices.keys.firstOrNull { !it.contains("staging", ignoreCase = true) } ?: choices.keys.firstOrNull()
}
