package app.truenascompanion.data.model

import kotlinx.serialization.json.JsonObject

/** `audit.query` services (25.10 `AuditQuery.services`). */
enum class AuditService(val api: String, val label: String) {
    MIDDLEWARE("MIDDLEWARE", "Middleware"),
    SMB("SMB", "SMB"),
    SUDO("SUDO", "Sudo"),
    SYSTEM("SYSTEM", "System"),
}

/** Event names from the web UI's `AuditEvent` enum (System › Audit). */
object AuditEvents {
    val ALL = listOf(
        "AUTHENTICATION", "LOGOUT", "METHOD_CALL", "REBOOT",
        "CONNECT", "DISCONNECT", "CREATE", "CLOSE", "READ", "WRITE", "OFFLOAD_READ", "OFFLOAD_WRITE", "SET_ACL",
        "RENAME", "UNLINK", "SET_ATTR", "SET_QUOTA",
        "ACCEPT", "REJECT",
        "GENERIC", "LOGIN", "CREDENTIAL", "ESCALATION", "PRIVILEGED", "EXPORT", "IDENTITY", "TIME-CHANGE", "MODULE-LOAD",
        "SERVICE", "TTY_RECORD",
    )
    val BY_SERVICE = mapOf(
        AuditService.MIDDLEWARE to listOf("AUTHENTICATION", "LOGOUT", "METHOD_CALL", "REBOOT"),
        AuditService.SMB to listOf("AUTHENTICATION", "CONNECT", "DISCONNECT", "CREATE", "CLOSE", "READ", "WRITE", "OFFLOAD_READ", "OFFLOAD_WRITE", "SET_ACL", "RENAME", "UNLINK", "SET_ATTR", "SET_QUOTA"),
        AuditService.SUDO to listOf("ACCEPT", "REJECT"),
        AuditService.SYSTEM to listOf("GENERIC", "LOGIN", "LOGOUT", "CREDENTIAL", "ESCALATION", "PRIVILEGED", "EXPORT", "IDENTITY", "TIME-CHANGE", "MODULE-LOAD", "SERVICE", "TTY_RECORD"),
    )
    fun label(e: String) = e.lowercase().split('_', '-').joinToString(" ") { it.replaceFirstChar(Char::uppercase) }
        .replace("Acl", "ACL").replace("Tty", "TTY").replace("Set Attr", "Set Attribute")
}

/** Time windows for the audit filter. */
enum class AuditTimeRange(val label: String, val seconds: Long?) {
    HOUR("Last hour", 3_600), DAY("24 hours", 86_400), WEEK("7 days", 7 * 86_400), MONTH("30 days", 30 * 86_400), ALL("All", null),
}

/** Quick filters shown as chips. */
enum class AuditQuick(val label: String) {
    AUTHENTICATION("Authentication"),
    FAILED("Failed"),
    LEGACY_REST("REST logins"),
    METHOD_CALLS("Method calls"),
    ;

    /** Whether the chip makes sense for [service] (REST logins and method calls only exist in the middleware log). */
    fun appliesTo(service: AuditService): Boolean = when (this) {
        FAILED -> true
        AUTHENTICATION -> service == AuditService.MIDDLEWARE || service == AuditService.SMB
        LEGACY_REST, METHOD_CALLS -> service == AuditService.MIDDLEWARE
    }
}

/** Everything the filter sheet can set; converted to `query-filters` by `AuditApi.filters`. */
data class AuditFilter(
    val service: AuditService = AuditService.MIDDLEWARE,
    val search: String = "",
    val event: String? = null,
    val username: String = "",
    val address: String = "",
    val time: AuditTimeRange = AuditTimeRange.DAY,
    val quick: Set<AuditQuick> = emptySet(),
) {
    val activeCount: Int get() = listOfNotNull(event, username.ifBlank { null }, address.ifBlank { null }).size +
        quick.size + (if (time != AuditTimeRange.DAY) 1 else 0)
}

data class AuditEntry(
    val auditId: String?,
    /** Seconds since epoch (`message_timestamp`). */
    val timestamp: Long,
    val address: String,
    val username: String,
    val service: String,
    val event: String,
    val success: Boolean,
    val session: String?,
    /** Short human summary: the method for METHOD_CALL, the credential type for AUTHENTICATION, the SMB path… */
    val summary: String,
    val raw: JsonObject,
)
