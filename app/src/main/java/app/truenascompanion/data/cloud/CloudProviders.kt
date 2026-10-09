package app.truenascompanion.data.cloud

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull

/**
 * Cloud credential fields per provider (1.5.0), from TrueNAS 25.10 `CloudCredentialProvider`
 * (middlewared api, cloud_sync_providers.py). Every value there is a pydantic `Secret`: TrueNAS shows them only to
 * full admins and CLOUD_SYNC_WRITE sessions and sends "********" to others.
 */
enum class FieldKind {
    TEXT,
    SECRET,
    /** OAuth token JSON pasted from the TrueNAS web UI or `rclone authorize` (masked, multi-line). */
    TOKEN,
    /** A JSON document such as a Google service account key (masked, multi-line). */
    JSON,
    INT,
    BOOL,
    URL,
    CHOICE,
    /** SFTP: an SSH key pair from System › Keychain (`keychaincredential` id, or none). */
    KEYPAIR,
}

data class CredField(
    val key: String,
    val label: String,
    val kind: FieldKind,
    val required: Boolean = false,
    /** Default shown for a new credential (INT/BOOL as text). */
    val default: String = "",
    val help: String? = null,
    /** CHOICE options: value → label. "" is sent as null when [nullable]. */
    val choices: List<Pair<String, String>> = emptyList(),
    /** Blank is sent as JSON null (SFTP password, Swift auth version). */
    val nullable: Boolean = false,
    /** CHOICE values are numbers (Swift auth_version). */
    val intChoice: Boolean = false,
    /** Hidden under "Advanced" in the editor. */
    val advanced: Boolean = false,
    /** Allowed values for URL fields: https only (Storj). */
    val httpsOnly: Boolean = false,
)

data class ProviderSpec(
    val type: String,
    val title: String,
    val fields: List<CredField>,
    /** Signs in with OAuth in the TrueNAS web UI; here the token is pasted. */
    val oauth: Boolean = false,
    /** Name for `rclone authorize "<name>"`. */
    val rcloneName: String? = null,
)

object CloudProviders {
    const val REDACTED = "********"

    private val AZURE_ACCOUNT = Regex("^[a-z0-9\\-.]+$", RegexOption.IGNORE_CASE)

    private fun oauthFields(vararg extra: CredField) = listOf(
        CredField("token", "Access token (JSON)", FieldKind.TOKEN, required = true),
        *extra,
        CredField("client_id", "OAuth client ID", FieldKind.TEXT, advanced = true, help = "Only if the token was made with your own app; leave empty otherwise"),
        CredField("client_secret", "OAuth client secret", FieldKind.SECRET, advanced = true),
    )

    val SPECS: List<ProviderSpec> = listOf(
        ProviderSpec("S3", "Amazon S3 / S3-compatible", listOf(
            CredField("access_key_id", "Access key ID", FieldKind.TEXT, required = true),
            CredField("secret_access_key", "Secret access key", FieldKind.SECRET, required = true),
            CredField("endpoint", "Endpoint URL", FieldKind.URL, help = "Leave empty for Amazon S3; e.g. https://s3.example.com for MinIO, Wasabi, R2…"),
            CredField("region", "Region", FieldKind.TEXT, advanced = true, help = "Leave empty to detect it automatically"),
            CredField("skip_region", "Don't detect the region", FieldKind.BOOL, default = "false", advanced = true, help = "For S3-compatible servers that don't support it"),
            CredField("signatures_v2", "Use signature version 2", FieldKind.BOOL, default = "false", advanced = true, help = "Only for old S3-compatible servers"),
            CredField("max_upload_parts", "Maximum upload parts", FieldKind.INT, default = "10000", advanced = true),
        )),
        ProviderSpec("B2", "Backblaze B2", listOf(
            CredField("account", "Key ID", FieldKind.TEXT, required = true, help = "Application key ID (or account ID)"),
            CredField("key", "Application key", FieldKind.SECRET, required = true),
        )),
        ProviderSpec("GOOGLE_DRIVE", "Google Drive", oauthFields(
            CredField("team_drive", "Shared drive ID", FieldKind.TEXT, help = "Leave empty for My Drive"),
        ), oauth = true, rcloneName = "drive"),
        ProviderSpec("DROPBOX", "Dropbox", oauthFields(), oauth = true, rcloneName = "dropbox"),
        ProviderSpec("ONEDRIVE", "Microsoft OneDrive", oauthFields(
            CredField("drive_type", "Drive type", FieldKind.CHOICE, required = true, default = "PERSONAL",
                choices = listOf("PERSONAL" to "Personal", "BUSINESS" to "Business", "DOCUMENT_LIBRARY" to "SharePoint document library")),
            CredField("drive_id", "Drive ID", FieldKind.TEXT, required = true, help = "Use Find drives after pasting the token"),
        ), oauth = true, rcloneName = "onedrive"),
        ProviderSpec("SFTP", "SFTP", listOf(
            CredField("host", "Host", FieldKind.TEXT, required = true, help = "e.g. backup.example.com"),
            CredField("port", "Port", FieldKind.INT, default = "22"),
            CredField("user", "User name", FieldKind.TEXT, required = true),
            CredField("pass", "Password", FieldKind.SECRET, nullable = true, help = "Leave empty when you use a key"),
            CredField("private_key", "SSH key pair", FieldKind.KEYPAIR, nullable = true, help = "Key pairs from System › Keychain in the web UI"),
        )),
        ProviderSpec("WEBDAV", "WebDAV", listOf(
            CredField("url", "URL", FieldKind.URL, required = true, help = "e.g. https://cloud.example.com/remote.php/dav/files/me/"),
            CredField("vendor", "Server", FieldKind.CHOICE, required = true, default = "NEXTCLOUD",
                choices = listOf("NEXTCLOUD" to "Nextcloud", "OWNCLOUD" to "ownCloud", "SHAREPOINT" to "SharePoint", "OTHER" to "Other")),
            CredField("user", "User name", FieldKind.TEXT),
            CredField("pass", "Password", FieldKind.SECRET),
        )),
        ProviderSpec("STORJ_IX", "Storj", listOf(
            CredField("access_key_id", "Access key ID", FieldKind.TEXT, required = true),
            CredField("secret_access_key", "Secret access key", FieldKind.SECRET, required = true),
            CredField("endpoint", "Endpoint URL", FieldKind.URL, required = true, default = "https://gateway.storjshare.io/", httpsOnly = true, advanced = true),
        )),
        ProviderSpec("AZUREBLOB", "Microsoft Azure Blob Storage", listOf(
            CredField("account", "Storage account name", FieldKind.TEXT, required = true),
            CredField("key", "Account key", FieldKind.SECRET, required = true),
            CredField("endpoint", "Endpoint URL", FieldKind.URL, advanced = true, help = "Leave empty for Azure's public cloud"),
        )),
        ProviderSpec("PCLOUD", "pCloud", oauthFields(
            CredField("hostname", "API host", FieldKind.TEXT, advanced = true, help = "Leave empty for US accounts; eapi.pcloud.com for EU accounts"),
        ), oauth = true, rcloneName = "pcloud"),
        ProviderSpec("BOX", "Box", oauthFields(), oauth = true, rcloneName = "box"),
        ProviderSpec("FTP", "FTP", listOf(
            CredField("host", "Host", FieldKind.TEXT, required = true),
            CredField("port", "Port", FieldKind.INT, default = "21"),
            CredField("user", "User name", FieldKind.TEXT, required = true),
            CredField("pass", "Password", FieldKind.SECRET),
        )),
        ProviderSpec("GOOGLE_CLOUD_STORAGE", "Google Cloud Storage", listOf(
            CredField("service_account_credentials", "Service account key (JSON)", FieldKind.JSON, required = true, help = "Paste the whole JSON key file"),
        )),
        ProviderSpec("GOOGLE_PHOTOS", "Google Photos", oauthFields(), oauth = true, rcloneName = "google photos"),
        ProviderSpec("YANDEX", "Yandex", oauthFields(), oauth = true, rcloneName = "yandex"),
        ProviderSpec("MEGA", "Mega", listOf(
            CredField("user", "User name", FieldKind.TEXT, required = true),
            CredField("pass", "Password", FieldKind.SECRET, required = true),
        )),
        ProviderSpec("HTTP", "HTTP (read only)", listOf(
            CredField("url", "URL", FieldKind.URL, required = true),
        )),
        ProviderSpec("HUBIC", "Hubic", listOf(CredField("token", "Access token (JSON)", FieldKind.TOKEN, required = true)), oauth = true, rcloneName = "hubic"),
        ProviderSpec("OPENSTACK_SWIFT", "OpenStack Swift", listOf(
            CredField("user", "User name", FieldKind.TEXT, required = true),
            CredField("key", "API key or password", FieldKind.SECRET, required = true),
            CredField("auth", "Authentication URL", FieldKind.TEXT, required = true),
            CredField("auth_version", "Auth version", FieldKind.CHOICE, nullable = true, intChoice = true,
                choices = listOf("" to "Automatic", "0" to "Legacy", "1" to "TempAuth", "2" to "Keystone v2", "3" to "Keystone v3")),
            CredField("endpoint_type", "Endpoint type", FieldKind.CHOICE, nullable = true,
                choices = listOf("" to "Default", "public" to "Public", "internal" to "Internal", "admin" to "Admin")),
            CredField("region", "Region", FieldKind.TEXT, advanced = true),
            CredField("tenant", "Tenant (project)", FieldKind.TEXT, advanced = true),
            CredField("tenant_id", "Tenant ID", FieldKind.TEXT, advanced = true),
            CredField("tenant_domain", "Tenant domain", FieldKind.TEXT, advanced = true),
            CredField("domain", "User domain", FieldKind.TEXT, advanced = true),
            CredField("user_id", "User ID", FieldKind.TEXT, advanced = true),
            CredField("storage_url", "Storage URL", FieldKind.TEXT, advanced = true),
            CredField("auth_token", "Auth token", FieldKind.SECRET, advanced = true),
            CredField("application_credential_id", "Application credential ID", FieldKind.TEXT, advanced = true),
            CredField("application_credential_name", "Application credential name", FieldKind.TEXT, advanced = true),
            CredField("application_credential_secret", "Application credential secret", FieldKind.SECRET, advanced = true),
        )),
    )

    private val byType = SPECS.associateBy { it.type }

    fun spec(type: String?): ProviderSpec? = type?.let { byType[it.uppercase()] }

    /** Order of the provider picker: the specs' order, then anything this app doesn't know (not editable here). */
    fun sortKey(type: String): Int = SPECS.indexOfFirst { it.type == type }.let { if (it < 0) SPECS.size else it }

    fun defaults(type: String): Map<String, String> = spec(type)?.fields?.associate { it.key to it.default } ?: emptyMap()

    /** Form values from a saved credential's `provider` object. */
    fun valuesOf(type: String, provider: JsonObject?): Map<String, String> {
        val spec = spec(type) ?: return emptyMap()
        return spec.fields.associate { f ->
            val p = provider?.get(f.key) as? JsonPrimitive
            val v = when {
                p == null || p is JsonNull -> if (f.kind == FieldKind.BOOL) "false" else ""
                f.kind == FieldKind.BOOL -> (p.booleanOrNull ?: false).toString()
                else -> p.contentOrNull.orEmpty()
            }
            f.key to v
        }
    }

    /** TrueNAS hid the saved secrets from this session (no full admin / CLOUD_SYNC_WRITE role). */
    fun redacted(values: Map<String, String>): Boolean = values.values.any { it == REDACTED }

    /** The `provider` object for create/update/verify, with exactly the keys of the 25.10 model. */
    fun providerJson(type: String, values: Map<String, String>): JsonObject = buildJsonObject {
        put("type", JsonPrimitive(type))
        spec(type)?.fields?.forEach { f ->
            val raw = values[f.key] ?: f.default
            val v = raw.trim()
            val el: JsonElement = when (f.kind) {
                FieldKind.BOOL -> JsonPrimitive(raw == "true")
                FieldKind.INT -> v.toIntOrNull()?.let { JsonPrimitive(it) } ?: f.default.toIntOrNull()?.let { JsonPrimitive(it) } ?: JsonNull
                FieldKind.KEYPAIR -> v.toIntOrNull()?.let { JsonPrimitive(it) } ?: JsonNull
                FieldKind.CHOICE -> when {
                    v.isEmpty() && f.nullable -> JsonNull
                    f.intChoice -> v.toIntOrNull()?.let { JsonPrimitive(it) } ?: JsonNull
                    else -> JsonPrimitive(v)
                }
                else -> if (v.isEmpty() && f.nullable) JsonNull else JsonPrimitive(v)
            }
            put(f.key, el)
        }
    }

    /** Local checks, field key → message. */
    fun errors(type: String, values: Map<String, String>): Map<String, String> = buildMap {
        val spec = spec(type) ?: return@buildMap
        spec.fields.forEach { f ->
            val v = values[f.key]?.trim().orEmpty()
            if (v == REDACTED) return@forEach
            when {
                f.required && v.isEmpty() && f.kind != FieldKind.BOOL -> put(f.key, if (f.kind == FieldKind.TOKEN) "Paste the token" else "Required")
                v.isEmpty() -> Unit
                f.kind == FieldKind.INT && (v.toIntOrNull() == null || v.toInt() < 0) -> put(f.key, "Enter a number")
                f.kind == FieldKind.URL && f.httpsOnly && !v.startsWith("https://", ignoreCase = true) -> put(f.key, "Use an https:// URL")
                f.kind == FieldKind.URL && !(v.startsWith("http://", ignoreCase = true) || v.startsWith("https://", ignoreCase = true)) ->
                    put(f.key, "Use a full URL starting with https://")
                f.kind == FieldKind.TOKEN -> tokenError(v)?.let { put(f.key, it) }
                f.kind == FieldKind.JSON && jsonObject(v) == null -> put(f.key, "Paste the whole JSON file, starting with {")
            }
        }
        if (type == "AZUREBLOB") values["account"]?.trim()?.takeIf { it.isNotEmpty() && it != REDACTED && !AZURE_ACCOUNT.matches(it) }
            ?.let { put("account", "Only letters, digits, - and .") }
        if (type == "SFTP") values["port"]?.trim()?.toIntOrNull()?.takeIf { it !in 1..65535 }?.let { put("port", "1–65535") }
        if (type == "FTP") values["port"]?.trim()?.toIntOrNull()?.takeIf { it !in 1..65535 }?.let { put("port", "1–65535") }
    }

    private fun jsonObject(s: String): JsonObject? = runCatching { Json.parseToJsonElement(s) as? JsonObject }.getOrNull()

    /** rclone OAuth tokens are JSON objects with an access_token (and usually refresh_token and expiry). */
    fun tokenError(s: String): String? {
        val o = jsonObject(s.trim()) ?: return "Paste the whole token, e.g. {\"access_token\":\"…\",…}"
        return if ((o["access_token"] as? JsonPrimitive)?.contentOrNull.isNullOrBlank()) "The token has no access_token" else null
    }

    /** Short "where is it" line for credential cards (no secrets). */
    fun summary(type: String, provider: JsonObject?): String? {
        fun s(k: String) = (provider?.get(k) as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() && it != REDACTED }
        return when (type) {
            "S3" -> s("endpoint")?.let { hostOf(it) } ?: "Amazon S3"
            "SFTP", "FTP" -> listOfNotNull(s("user"), s("host")).joinToString("@").ifEmpty { null }
            "WEBDAV", "HTTP" -> s("url")?.let { hostOf(it) }
            "STORJ_IX" -> s("endpoint")?.let { hostOf(it) }
            "AZUREBLOB" -> s("account")
            "ONEDRIVE" -> (provider?.get("drive_type") as? JsonPrimitive)?.contentOrNull?.let { t -> spec(type)?.fields?.first { it.key == "drive_type" }?.choices?.firstOrNull { it.first == t }?.second }
            "GOOGLE_DRIVE" -> if (s("team_drive") != null) "Shared drive" else "My Drive"
            else -> null
        }
    }

    private fun hostOf(url: String) = url.substringAfter("://").substringBefore('/').ifBlank { url }

    /** Pretty provider name for types missing from `cloudsync.providers` (e.g. offline previews). */
    fun title(type: String): String = spec(type)?.title ?: type.lowercase().replaceFirstChar { it.uppercase() }
}
