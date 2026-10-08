package app.truenascompanion.data.services

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/** One choice of a picker; a null [value] is sent as JSON `null`. */
data class Option(val value: String?, val label: String)

/** How a settings field is edited and sent. The draft keeps the text the user typed (see [ServiceForms.toDraft]). */
sealed interface FieldKind {
    /** Free text. [blankAsNull]: an empty field is sent as `null`. [secret]: masked with Show/Hide. */
    data class Text(
        val secret: Boolean = false,
        val multiline: Boolean = false,
        val blankAsNull: Boolean = false,
        val required: Boolean = false,
        val maxLength: Int? = null,
        val pattern: Regex? = null,
        val patternHint: String? = null,
    ) : FieldKind
    /** Whole number in [min]..[max]; when [nullable], an empty field means "automatic / unset" (`null`). */
    data class Number(val min: Long, val max: Long, val nullable: Boolean = false, val emptyLabel: String? = null) : FieldKind
    data object Toggle : FieldKind
    /**
     * Single choice; options come from the spec or, with [ServiceField.choicesKey], from the NAS. [numeric]: sent as a
     * number (ids). [custom]: "Other…" lets you type a value that isn't in the list (e.g. a network UPS hostname).
     */
    data class Pick(val options: List<Option> = emptyList(), val numeric: Boolean = false, val custom: Boolean = false) : FieldKind
    /** Several choices (a JSON list of strings). */
    data class MultiPick(val options: List<Option> = emptyList(), val minCount: Int = 0) : FieldKind
    /** A JSON list of strings, edited as comma-separated text. */
    data object TextList : FieldKind
}

typealias Draft = Map<String, JsonElement>

/**
 * A field of a service settings editor. [key] is the middleware field name (TrueNAS 25.10). [visibleIf] hides fields
 * that don't apply; hidden fields are neither validated nor sent, except that [clearWhenHidden] is sent when the
 * field is hidden but still set (e.g. the NFSv4 domain once NFSv4 is turned off).
 */
data class ServiceField(
    val key: String,
    val label: String,
    val kind: FieldKind,
    val help: String? = null,
    val section: String = "General",
    val advanced: Boolean = false,
    val choicesKey: String? = null,
    val visibleIf: ((Draft) -> Boolean)? = null,
    val clearWhenHidden: JsonElement? = null,
    /** Cross-field or format check on the draft; returns a message or null. */
    val check: ((Draft) -> String?)? = null,
) {
    fun visible(d: Draft) = visibleIf?.invoke(d) ?: true
    val secret: Boolean get() = (kind as? FieldKind.Text)?.secret == true
}

/** The services with a settings editor in 1.4.0, with the 25.10 `<namespace>.config` / `.update` methods. */
enum class ServiceKind(val service: String, val namespace: String, val title: String, val summary: String) {
    SSH("ssh", "ssh", "SSH", "Port, password login, forwarding and ciphers"),
    SMB("cifs", "smb", "SMB", "Windows/macOS file sharing: name, workgroup, guest, multichannel"),
    NFS("nfs", "nfs", "NFS", "Server threads, NFSv3/v4, bind addresses and ports"),
    UPS("ups", "ups", "UPS", "Battery backup: mode, driver, shutdown timer"),
    SNMP("snmp", "snmp", "SNMP", "Monitoring: location, contact, community, SNMPv3"),
    FTP("ftp", "ftp", "FTP", "Port, clients, anonymous access and TLS");

    companion object {
        fun of(service: String) = entries.firstOrNull { it.service == service }
    }
}

object ServiceForms {
    /** The middleware's placeholder for a secret it doesn't show to this session (`SECRET_VALUE`). */
    const val REDACTED = "********"

    private fun JsonElement?.text(): String = (this as? JsonPrimitive)?.takeUnless { it is JsonNull }?.contentOrNull.orEmpty()
    private fun JsonElement?.bool(): Boolean = (this as? JsonPrimitive)?.booleanOrNull ?: false
    private fun JsonElement?.strings(): List<String> = (this as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()
    fun Draft.text(key: String) = this[key].text()
    fun Draft.on(key: String) = this[key].bool()
    fun Draft.list(key: String) = this[key].strings()

    // ---------------- draft <-> API values ----------------

    /** Turns a `<service>.config` result into the editor's draft (text for text/number/list fields). */
    fun toDraft(fields: List<ServiceField>, config: JsonObject): Draft = fields.associate { f ->
        val v = config[f.key]
        f.key to when (f.kind) {
            is FieldKind.Text, is FieldKind.Number -> JsonPrimitive(v.text())
            FieldKind.Toggle -> JsonPrimitive(v.bool())
            is FieldKind.Pick -> if (v == null || v is JsonNull) JsonNull else JsonPrimitive(v.text())
            is FieldKind.MultiPick -> JsonArray(v.strings().map { JsonPrimitive(it) })
            FieldKind.TextList -> JsonPrimitive(v.strings().joinToString(", "))
        }
    }

    /** The value to send for [f], or null when the draft can't be converted (invalid number). */
    fun apiValue(f: ServiceField, d: Draft): JsonElement? {
        val v = d[f.key]
        return when (val k = f.kind) {
            is FieldKind.Text -> {
                val t = v.text().let { if (k.multiline) it else it.trim() }
                if (t.isEmpty() && k.blankAsNull) JsonNull else JsonPrimitive(t)
            }
            is FieldKind.Number -> {
                val t = v.text().trim()
                if (t.isEmpty()) { if (k.nullable) JsonNull else null } else t.toLongOrNull()?.takeIf { it in k.min..k.max }?.let { JsonPrimitive(it) }
            }
            FieldKind.Toggle -> JsonPrimitive(v.bool())
            is FieldKind.Pick -> when {
                v == null || v is JsonNull -> JsonNull
                k.numeric -> v.text().toLongOrNull()?.let { JsonPrimitive(it) } ?: JsonNull
                else -> JsonPrimitive(v.text())
            }
            is FieldKind.MultiPick -> JsonArray(v.strings().map { JsonPrimitive(it) })
            FieldKind.TextList -> JsonArray(v.text().split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }.distinct().map { JsonPrimitive(it) })
        }
    }

    private fun same(a: JsonElement?, b: JsonElement?): Boolean {
        val x = a ?: JsonNull; val y = b ?: JsonNull
        if (x is JsonPrimitive && y is JsonPrimitive && x !is JsonNull && y !is JsonNull) {
            if (x.isString != y.isString) return x.contentOrNull == y.contentOrNull
        }
        return x == y
    }

    /** Local checks of visible fields: required, ranges, patterns, lengths, then each field's own [ServiceField.check]. */
    fun validate(fields: List<ServiceField>, d: Draft): Map<String, String> {
        val errors = LinkedHashMap<String, String>()
        for (f in fields) {
            if (!f.visible(d)) continue
            val raw = d.text(f.key)
            val problem: String? = when (val k = f.kind) {
                is FieldKind.Text -> when {
                    k.secret && raw == REDACTED -> null
                    k.required && raw.isBlank() -> "Required"
                    k.maxLength != null && raw.trim().length > k.maxLength -> "At most ${k.maxLength} characters"
                    k.pattern != null && raw.isNotBlank() && !k.pattern.matches(raw.trim()) -> k.patternHint ?: "Invalid value"
                    else -> null
                }
                is FieldKind.Number -> when {
                    raw.isBlank() -> if (k.nullable) null else "Required"
                    raw.trim().toLongOrNull() == null -> "Enter a whole number"
                    raw.trim().toLong() !in k.min..k.max -> "Must be between ${k.min} and ${k.max}"
                    else -> null
                }
                is FieldKind.MultiPick -> if (d.list(f.key).size < k.minCount) "Pick at least ${k.minCount}" else null
                else -> null
            }
            (problem ?: f.check?.invoke(d))?.let { errors[f.key] = it }
        }
        return errors
    }

    /**
     * Only what changed, as the `<service>.update` argument. Hidden fields are skipped (or cleared, see
     * [ServiceField.clearWhenHidden]) and a secret still showing the redaction placeholder is never sent back.
     */
    fun changes(fields: List<ServiceField>, original: JsonObject, d: Draft): JsonObject {
        val out = LinkedHashMap<String, JsonElement>()
        for (f in fields) {
            if (!f.visible(d)) {
                val clear = f.clearWhenHidden ?: continue
                if (!same(original[f.key], clear) && original[f.key].text().isNotEmpty()) out[f.key] = clear
                continue
            }
            if (f.secret && d.text(f.key) == REDACTED) continue
            val v = apiValue(f, d) ?: continue
            if (!same(original[f.key], v)) out[f.key] = v
        }
        return JsonObject(out)
    }

    /** True if the draft differs from what the NAS has. */
    fun dirty(fields: List<ServiceField>, original: JsonObject, d: Draft) = changes(fields, original, d).isNotEmpty()

    fun fields(kind: ServiceKind): List<ServiceField> = when (kind) {
        ServiceKind.SSH -> ServiceSpecs.ssh
        ServiceKind.SMB -> ServiceSpecs.smb
        ServiceKind.NFS -> ServiceSpecs.nfs
        ServiceKind.UPS -> ServiceSpecs.ups
        ServiceKind.SNMP -> ServiceSpecs.snmp
        ServiceKind.FTP -> ServiceSpecs.ftp
    }
}
