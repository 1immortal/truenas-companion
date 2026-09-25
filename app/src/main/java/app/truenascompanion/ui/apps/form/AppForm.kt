package app.truenascompanion.ui.apps.form

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Model of a TrueNAS app "questions" schema (catalog `schema.questions`, as normalized by the middleware) and the
 * value operations the generated install/edit form needs. Pure Kotlin so it can be unit tested.
 */
data class Question(
    val variable: String,
    val label: String,
    val description: String?,
    val group: String?,
    val schema: FieldSchema,
)

data class EnumOption(val value: JsonElement, val label: String)

data class Condition(val variable: String, val op: String, val value: JsonElement)

data class FieldSchema(
    val type: String,
    val default: JsonElement?,
    val required: Boolean,
    val nullable: Boolean,
    val private: Boolean,
    val hidden: Boolean,
    val editable: Boolean,
    val immutable: Boolean,
    val min: Double?,
    val max: Double?,
    val minLength: Int?,
    val maxLength: Int?,
    val enum: List<EnumOption>,
    val attrs: List<Question>,
    val items: List<Question>,
    val showIf: List<Condition>,
) {
    val supported: Boolean get() = type in SUPPORTED_TYPES
    val isNumber: Boolean get() = type == "int" || type == "float"

    companion object {
        val TEXT_TYPES = setOf("string", "text", "path", "hostpath", "ipaddr", "uri")
        val SUPPORTED_TYPES = TEXT_TYPES + setOf("int", "float", "boolean", "dict", "list")
    }
}

data class FormGroup(val name: String, val description: String?, val questions: List<Question>)

/** One step of a path into the values tree. */
sealed interface PathKey {
    data class Key(val name: String) : PathKey
    data class Index(val index: Int) : PathKey
}

typealias ValuePath = List<PathKey>

fun ValuePath.display(): String = buildString {
    this@display.forEach { k ->
        when (k) {
            is PathKey.Key -> { if (isNotEmpty()) append('.'); append(k.name) }
            is PathKey.Index -> append("[${k.index}]")
        }
    }
}

object AppForm {
    fun parseQuestions(schema: JsonObject?): List<Question> =
        (schema?.get("questions") as? JsonArray)?.mapNotNull { (it as? JsonObject)?.let(::parseQuestion) } ?: emptyList()

    fun groups(schema: JsonObject?): List<FormGroup> {
        val questions = parseQuestions(schema)
        val declared = (schema?.get("groups") as? JsonArray)?.mapNotNull { g ->
            (g as? JsonObject)?.let { o -> o.string("name")?.let { it to o.string("description") } }
        } ?: emptyList()
        val names = (declared.map { it.first } + questions.mapNotNull { it.group }).distinct()
        val result = names.map { n -> FormGroup(n, declared.firstOrNull { it.first == n }?.second, questions.filter { it.group == n }) }
            .filter { it.questions.isNotEmpty() }
        val ungrouped = questions.filter { it.group == null || it.group !in names }
        return if (ungrouped.isEmpty()) result else result + FormGroup("Other", null, ungrouped)
    }

    fun parseQuestion(o: JsonObject): Question? {
        val variable = o.string("variable") ?: return null
        val s = o["schema"] as? JsonObject ?: JsonObject(emptyMap())
        return Question(
            variable = variable,
            label = o.string("label")?.takeIf { it.isNotBlank() } ?: variable,
            description = o.string("description")?.takeIf { it.isNotBlank() },
            group = o.string("group"),
            schema = FieldSchema(
                type = s.string("type") ?: "string",
                default = s["default"],
                required = s.bool("required") ?: false,
                nullable = s.bool("null") ?: false,
                private = s.bool("private") ?: false,
                hidden = s.bool("hidden") ?: false,
                editable = s.bool("editable") ?: true,
                immutable = s.bool("immutable") ?: false,
                min = s.number("min"),
                max = s.number("max"),
                minLength = s.number("min_length")?.toInt(),
                maxLength = s.number("max_length")?.toInt(),
                enum = (s["enum"] as? JsonArray)?.mapNotNull { e ->
                    (e as? JsonObject)?.let { eo -> EnumOption(eo["value"] ?: JsonNull, eo.string("description") ?: eo["value"]?.display() ?: "") }
                } ?: emptyList(),
                attrs = (s["attrs"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.let(::parseQuestion) } ?: emptyList(),
                items = (s["items"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.let(::parseQuestion) } ?: emptyList(),
                showIf = (s["show_if"] as? JsonArray)?.mapNotNull { c ->
                    (c as? JsonArray)?.takeIf { it.size >= 3 }?.let { Condition(it[0].jsonPrimitive.content, it[1].jsonPrimitive.content, it[2]) }
                } ?: emptyList(),
            ),
        )
    }

    /** Default value for a field (its `default`, or built from its attributes for dicts). */
    fun defaultFor(schema: FieldSchema): JsonElement = when {
        schema.default != null && schema.default !is JsonNull -> schema.default
        schema.type == "dict" -> JsonObject(schema.attrs.associate { it.variable to defaultFor(it.schema) })
        schema.type == "list" -> JsonArray(emptyList())
        schema.type == "boolean" -> JsonPrimitive(false)
        schema.default is JsonNull || schema.nullable || schema.isNumber -> JsonNull
        schema.type in FieldSchema.TEXT_TYPES -> JsonPrimitive("")
        else -> JsonNull
    }

    /** Fills in missing keys from the schema defaults (recursively), keeping everything that's already set. */
    fun withDefaults(questions: List<Question>, values: JsonObject): JsonObject {
        val out = LinkedHashMap<String, JsonElement>(values)
        for (q in questions) {
            val cur = values[q.variable]
            out[q.variable] = when {
                cur == null -> fill(q.schema, defaultFor(q.schema))
                else -> fill(q.schema, cur)
            }
        }
        return JsonObject(out)
    }

    private fun fill(schema: FieldSchema, value: JsonElement): JsonElement = when {
        schema.type == "dict" && value is JsonObject && schema.attrs.isNotEmpty() -> withDefaults(schema.attrs, value)
        schema.type == "list" && value is JsonArray && schema.items.size == 1 -> JsonArray(value.map { fill(schema.items[0].schema, it) })
        else -> value
    }

    /** New element for a list field. */
    fun newItem(list: FieldSchema): JsonElement = list.items.firstOrNull()?.let { fill(it.schema, defaultFor(it.schema)) } ?: JsonNull

    fun get(root: JsonElement?, path: ValuePath): JsonElement? {
        var cur = root
        for (k in path) {
            cur = when (k) {
                is PathKey.Key -> (cur as? JsonObject)?.get(k.name)
                is PathKey.Index -> (cur as? JsonArray)?.getOrNull(k.index)
            } ?: return null
        }
        return cur
    }

    /** Returns a copy of [root] with [value] at [path] (creating objects on the way). */
    fun set(root: JsonElement?, path: ValuePath, value: JsonElement): JsonElement {
        if (path.isEmpty()) return value
        return when (val k = path.first()) {
            is PathKey.Key -> {
                val obj = root as? JsonObject ?: JsonObject(emptyMap())
                JsonObject(LinkedHashMap(obj).apply { put(k.name, set(obj[k.name], path.drop(1), value)) })
            }
            is PathKey.Index -> {
                val arr = (root as? JsonArray)?.toMutableList() ?: mutableListOf()
                while (arr.size <= k.index) arr.add(JsonNull)
                arr[k.index] = set(arr[k.index], path.drop(1), value)
                JsonArray(arr)
            }
        }
    }

    fun removeAt(root: JsonElement?, listPath: ValuePath, index: Int): JsonElement {
        val list = (get(root, listPath) as? JsonArray)?.toMutableList() ?: return root ?: JsonNull
        if (index in list.indices) list.removeAt(index)
        return set(root, listPath, JsonArray(list))
    }

    /** `show_if` conditions refer to sibling fields in the same dict. */
    fun visible(schema: FieldSchema, siblings: JsonObject?): Boolean =
        !schema.hidden && schema.showIf.all { c -> matches(siblings?.get(c.variable) ?: JsonNull, c.op, c.value) }

    fun matches(actual: JsonElement, op: String, expected: JsonElement): Boolean {
        fun num(e: JsonElement) = (e as? JsonPrimitive)?.let { it.doubleOrNull ?: it.contentOrNull?.toDoubleOrNull() }
        return when (op) {
            "=" -> same(actual, expected)
            "!=" -> !same(actual, expected)
            "in" -> (expected as? JsonArray)?.any { same(actual, it) } ?: false
            "nin" -> (expected as? JsonArray)?.none { same(actual, it) } ?: true
            ">" -> (num(actual) ?: return false) > (num(expected) ?: return false)
            ">=" -> (num(actual) ?: return false) >= (num(expected) ?: return false)
            "<" -> (num(actual) ?: return false) < (num(expected) ?: return false)
            "<=" -> (num(actual) ?: return false) <= (num(expected) ?: return false)
            else -> true // unknown operator: show the field rather than hide something important
        }
    }

    private fun same(a: JsonElement, b: JsonElement): Boolean {
        if (a is JsonNull || b is JsonNull) return a is JsonNull && b is JsonNull
        if (a is JsonPrimitive && b is JsonPrimitive) {
            a.booleanOrNull?.let { ab -> return b.booleanOrNull == ab }
            val an = a.doubleOrNull; val bn = b.doubleOrNull
            if (an != null && bn != null) return an == bn
            return a.contentOrNull == b.contentOrNull
        }
        return a == b
    }

    data class Issue(val path: ValuePath, val message: String)

    /** Client-side checks for visible fields (the server validates everything again). */
    fun validate(questions: List<Question>, values: JsonObject, base: ValuePath = emptyList()): List<Issue> {
        val out = mutableListOf<Issue>()
        for (q in questions) {
            if (!visible(q.schema, values)) continue
            val path = base + PathKey.Key(q.variable)
            val v = values[q.variable] ?: JsonNull
            val s = q.schema
            when {
                s.type == "dict" && v is JsonObject -> out += validate(s.attrs, v, path)
                s.type == "list" && v is JsonArray -> {
                    val item = s.items.singleOrNull()
                    v.forEachIndexed { i, e ->
                        val ip = path + PathKey.Index(i)
                        if (item != null && item.schema.type == "dict" && e is JsonObject) out += validate(item.schema.attrs, e, ip)
                        else if (item != null) checkScalar(item.label, item.schema, e)?.let { out += Issue(ip, it) }
                    }
                }
                else -> checkScalar(q.label, s, v)?.let { out += Issue(path, it) }
            }
        }
        return out
    }

    private fun checkScalar(label: String, s: FieldSchema, v: JsonElement): String? {
        if (!s.supported || s.type == "boolean") return null
        val text = (v as? JsonPrimitive)?.takeUnless { it is JsonNull }?.contentOrNull
        if (text.isNullOrEmpty()) return if (s.required && !s.nullable) "$label is required" else null
        if (s.isNumber) {
            val n = text.toDoubleOrNull() ?: return "$label must be a number"
            if (s.type == "int" && n % 1.0 != 0.0) return "$label must be a whole number"
            s.min?.let { if (n < it) return "$label must be at least ${it.clean()}" }
            s.max?.let { if (n > it) return "$label must be at most ${it.clean()}" }
        } else {
            s.minLength?.let { if (text.length < it) return "$label needs at least $it characters" }
            s.maxLength?.let { if (text.length > it) return "$label can have at most $it characters" }
        }
        if (s.enum.isNotEmpty() && s.enum.none { same(it.value, v) }) return "Choose a value for $label"
        return null
    }

    private fun Double.clean() = if (this % 1.0 == 0.0) toLong().toString() else toString()

    /** Parses typed text into the JSON value for a scalar field. */
    fun parseInput(s: FieldSchema, text: String): JsonElement = when {
        s.isNumber && text.isBlank() -> JsonNull
        s.type == "int" -> text.trim().toLongOrNull()?.let(::JsonPrimitive) ?: JsonPrimitive(text)
        s.type == "float" -> text.trim().toDoubleOrNull()?.let(::JsonPrimitive) ?: JsonPrimitive(text)
        text.isEmpty() && s.nullable -> JsonNull
        else -> JsonPrimitive(text)
    }

    /** Fields the form can't render anywhere in the tree (they keep their default/current value). */
    fun unsupported(questions: List<Question>): List<Question> = questions.flatMap { q ->
        when {
            !q.schema.supported -> listOf(q)
            q.schema.type == "dict" -> unsupported(q.schema.attrs)
            q.schema.type == "list" -> unsupported(q.schema.items)
            else -> emptyList()
        }
    }

    private val appNamePattern = Regex("^[a-z]([-a-z0-9]*[a-z0-9])?$")

    /** Same rule as the middleware's `app_name` field. */
    fun appNameError(name: String): String? = when {
        name.isEmpty() -> "Enter a name"
        name.length > 40 -> "At most 40 characters"
        !appNamePattern.matches(name) -> "Lowercase letters, digits and hyphens; start with a letter, don't end with a hyphen"
        else -> null
    }
}

internal fun JsonElement.display(): String = when (this) {
    is JsonPrimitive -> if (this is JsonNull) "" else content
    else -> toString()
}

private fun JsonObject.string(k: String) = (this[k] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.contentOrNull
private fun JsonObject.bool(k: String) = (this[k] as? JsonPrimitive)?.booleanOrNull
private fun JsonObject.number(k: String) = (this[k] as? JsonPrimitive)?.let { it.doubleOrNull ?: it.longOrNull?.toDouble() }
