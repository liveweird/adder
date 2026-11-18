package ch.nokillswit.threads

import ch.nokillswit.infra.validation.requireNoDuplicates
import ch.nokillswit.infra.validation.sanitizeSingleLine
import ch.nokillswit.workflows.FieldType
import ch.nokillswit.workflows.MAX_FIELDS
import ch.nokillswit.workflows.MAX_FIELD_KEYS_PER_ROOM
import ch.nokillswit.workflows.MAX_KEY_LENGTH
import ch.nokillswit.workflows.MAX_OPTIONS
import ch.nokillswit.workflows.shown
import io.ktor.server.plugins.BadRequestException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import java.net.URI
import java.net.URISyntaxException
import java.time.LocalDate
import java.time.format.DateTimeParseException
import kotlin.math.abs
import kotlin.math.floor

// The pure field kernel of a thread (no database, no request, no language): it validates the typed values a thread carries against the
// room's field definitions and answers the merged document and what changed. Toadie's injected-lookup shape: the caller reads the
// definitions (`RoomWorkflowService.snapshot`) and the active member ids under the room lock and hands them in.
//
// The rules (chat-model.md "Threads and messages"):
// - Incoming values MERGE over the stored ones: a key that is absent is untouched, `null` clears the field. An unknown or retired
//   key is a 400.
// - Retired fields keep their stored values, hidden: [applyFieldValues] never drops them from the merged document.
// - A value equal to the stored one skips validation, so a stale value (a removed option, a left member) never blocks a save; a
//   MULTI_SELECT may keep a removed option it already holds but cannot newly choose one. The same goes for a retired field: its
//   unchanged value (a stale form that still carries it) is ignored, while a changed or new value for it is a 400.
// - A required field must hold a value on create; later it cannot be cleared once set, while one that was never set may stay unset.
// - Per type: TEXT single line, 500 characters at most, blank means unset; NUMBER a finite JSON number, `|x| <= 1e15`; DATE a real
//   calendar day as `YYYY-MM-DD`; SELECT one option key; MULTI_SELECT 1 to 50 distinct option keys, empty means unset; USER the id of
//   an active member; URL absolute `http`/`https`, trimmed, 2,000 characters at most. A value of the wrong JSON type is a 400.
// - Stored forms are CANONICAL, never the client's literal token (Ktor's JSON is lenient: `+7`, `007`, `.5`, `0x1p3`, an unquoted word
//   all reach this kernel as primitives, and a token that is not valid JSON would fail the jsonb write). A NUMBER must match the strict
//   JSON number grammar (at most 32 characters) and is stored as the parsed double: an integral value as a JSON integer (`7` and `7.0`
//   are the same stored `7`), anything else as the double's own text, so precision is that of a double. A USER id is `JsonPrimitive(id)`.
//   Values compare by that canonical value (numbers numerically; MULTI_SELECT as a set), so a respelling is no change.
// - MULTI_SELECT is stored in option-definition order, with kept removed options last (in their stored order): a reorder is no change.
// - The request's `fields` has a SHAPE before it has values ([requireFieldShape], run before the room lock): at most MAX_FIELDS keys, each
//   value a JSON primitive or null, or an array of at most MAX_OPTIONS string primitives; the stored document has a size ceiling
//   ([requireFieldsDocumentSize]). Both answer a fixed-detail 400.

/** Longest TEXT field value, in UTF-16 units. */
const val MAX_TEXT_FIELD_LENGTH = 500

/** Longest URL field value, in UTF-16 units. */
const val MAX_URL_FIELD_LENGTH = 2000

/** The largest magnitude a NUMBER field may hold. */
const val MAX_NUMBER_FIELD_MAGNITUDE = 1e15

/** Longest accepted NUMBER token, in characters. */
const val MAX_NUMBER_TOKEN_LENGTH = 32

/**
 * The ceiling on the canonical JSON text of a thread's stored `fields` document, in UTF-16 units (a backstop: the kernel's per-type limits
 * already bound it, so a legitimate write never meets it). The worst legitimate document holds every field key the room may ever have used
 * ([MAX_FIELD_KEYS_PER_ROOM], live and retired ones, a retired field keeps its last value), each at its largest: a key of
 * [MAX_KEY_LENGTH] units with its quotes and colon, and the widest value of any type - a MULTI_SELECT of [MAX_OPTIONS] option keys (each
 * [MAX_KEY_LENGTH] units, quoted, comma-separated, in brackets; a 2,000-unit URL and a TEXT of 500 units with every unit escaped are both
 * narrower), the entries comma-separated in braces. That is 439,001 units, which is the ceiling exactly: a document past it can only come
 * from a kernel bug, and the write refuses instead of growing the row. (The live fields alone, at most 20, are under 44,000.)
 */
val MAX_FIELDS_DOCUMENT_LENGTH: Int = run {
    val multiSelect = MAX_OPTIONS * (MAX_KEY_LENGTH + 2) + (MAX_OPTIONS - 1) + 2
    val url = MAX_URL_FIELD_LENGTH + 2
    val text = 2 * MAX_TEXT_FIELD_LENGTH + 2
    val entry = MAX_KEY_LENGTH + 3 + maxOf(multiSelect, url, text)
    2 + MAX_FIELD_KEYS_PER_ROOM * entry + (MAX_FIELD_KEYS_PER_ROOM - 1)
}

private const val FIELD_SHAPE_DETAIL = "Each field value must be a single value, null or a list of option keys"

private val DATE_SHAPE = Regex("""\d{4}-\d{2}-\d{2}""")
private val NUMBER_GRAMMAR = Regex("""-?(0|[1-9]\d*)(\.\d+)?([eE][+-]?\d+)?""")
private val USER_ID_GRAMMAR = Regex("""\d{1,18}""")
private val URL_SCHEMES = setOf("http", "https")

/**
 * The shape of a request's `fields` map, checked right after the body decodes and BEFORE any lock: Ktor's JSON hands over any tree, and a
 * nested object or a huge array must neither reach the kernel nor wait for the room lock to be refused. At most [MAX_FIELDS] keys (the
 * live-field maximum); each value a JSON primitive, null, or an array of at most [MAX_OPTIONS] string primitives. Fixed details, no echo.
 */
fun requireFieldShape(fields: Map<String, JsonElement>) {
    if (fields.size > MAX_FIELDS) throw BadRequestException("A request sets at most $MAX_FIELDS fields")
    val wellFormed = fields.values.all { value ->
        value is JsonPrimitive || (value is JsonArray && value.size <= MAX_OPTIONS && value.all { it is JsonPrimitive && it.isString })
    }
    if (!wellFormed) throw BadRequestException(FIELD_SHAPE_DETAIL)
}

/** The stored `fields` document must stay within [MAX_FIELDS_DOCUMENT_LENGTH] (its canonical JSON text): a fixed-detail 400. */
fun requireFieldsDocumentSize(canonical: String) {
    if (canonical.length > MAX_FIELDS_DOCUMENT_LENGTH) throw BadRequestException("The field values are too large to store")
}

/** One field of the room workflow as the kernel needs it; [retired] fields are never writable but keep their values. */
data class FieldDefinition(
    val key: String,
    val type: FieldType,
    val required: Boolean,
    val optionKeys: List<String>,
    val retired: Boolean,
)

/** One field whose value changed: [from] and [to] are the stored form, `null` meaning unset. */
data class FieldChange(val key: String, val from: JsonElement?, val to: JsonElement?)

/** The outcome: the whole document to store (retired values included) and the changes, in definition order. */
data class FieldWrite(val merged: Map<String, JsonElement>, val changes: List<FieldChange>)

/**
 * Applies [incoming] over [current] against [definitions]. [activeMemberIds] holds the ids among the incoming USER values that belong
 * to an active member of the room (a superset is fine); [creating] is true for the thread's first write, where every active required
 * field must be supplied. Throws [BadRequestException] for any rule above.
 */
fun applyFieldValues(
    definitions: List<FieldDefinition>,
    current: Map<String, JsonElement>,
    incoming: Map<String, JsonElement>,
    activeMemberIds: Set<Long>,
    creating: Boolean,
): FieldWrite {
    val byKey = definitions.associateBy { it.key }
    incoming.forEach { (key, raw) ->
        val definition = byKey[key] ?: throw BadRequestException("Unknown field '${key.shown()}'")
        // A retired field is read-only, but a stale form may still carry its unchanged value: that is ignored, not refused.
        if (definition.retired && !sameValue(definition.type, current[key], raw.takeUnless { it is JsonNull })) {
            throw BadRequestException("Field '$key' is retired")
        }
    }
    val merged = current.toMutableMap()
    val changes = mutableListOf<FieldChange>()
    for (definition in definitions.filter { !it.retired }) {
        val before = current[definition.key]
        val raw = incoming[definition.key]
        val after = if (raw == null || raw == before) before else normalise(definition, raw, before, activeMemberIds)
        if (!sameValue(definition.type, before, after)) {
            changes += FieldChange(definition.key, before, after)
            if (after == null) merged.remove(definition.key) else merged[definition.key] = after
        }
        if (definition.required && after == null && (creating || before != null)) {
            throw BadRequestException("Field '${definition.key}' is required")
        }
    }
    return FieldWrite(merged, changes)
}

/**
 * The ids among the [incoming] values of active USER fields that parse as a user id (the same grammar [applyFieldValues] judges):
 * what the caller looks up with `RoomMemberService.activeMemberIds` before it hands the member set to the kernel. A value that is not
 * an id is skipped here and refused by the kernel with its 400.
 */
fun incomingUserIds(definitions: List<FieldDefinition>, incoming: Map<String, JsonElement>): Set<Long> =
    definitions.filter { it.type == FieldType.USER && !it.retired }.mapNotNullTo(HashSet()) { definition ->
        (incoming[definition.key] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.takeIf { USER_ID_GRAMMAR.matches(it) }?.toLong()
    }

// Numbers compare by value (1 and 1.0 are the same stored number) and a MULTI_SELECT as a set (its order is the definition's, which
// may have been reordered since); everything else by its JSON form.
private fun sameValue(type: FieldType, a: JsonElement?, b: JsonElement?): Boolean = when {
    type == FieldType.NUMBER && a is JsonPrimitive && b is JsonPrimitive -> a.doubleOrNull == b.doubleOrNull
    type == FieldType.MULTI_SELECT && a is JsonArray && b is JsonArray -> a.toSet() == b.toSet()
    else -> a == b
}

/** The validated stored form of [raw], or `null` when it means "unset". */
private fun normalise(definition: FieldDefinition, raw: JsonElement, before: JsonElement?, activeMemberIds: Set<Long>): JsonElement? {
    if (raw is JsonNull) return null
    val key = definition.key
    return when (definition.type) {
        FieldType.TEXT -> textValue(stringOf(raw, key), key)
        FieldType.NUMBER -> numberValue(raw, key)
        FieldType.DATE -> dateValue(stringOf(raw, key), key)
        FieldType.SELECT -> selectValue(stringOf(raw, key), definition)
        FieldType.MULTI_SELECT -> multiSelectValue(raw, definition, before)
        FieldType.USER -> userValue(raw, key, activeMemberIds)
        FieldType.URL -> urlValue(stringOf(raw, key), key)
    }
}

private fun stringOf(raw: JsonElement, key: String): String =
    (raw as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw BadRequestException("Field '$key' must be a string")

private fun numberOf(raw: JsonElement, key: String, what: String): JsonPrimitive =
    (raw as? JsonPrimitive)?.takeIf { !it.isString } ?: throw BadRequestException("Field '$key' must be $what")

private fun textValue(text: String, key: String): JsonElement? {
    val clean = sanitizeSingleLine(text, "Field '$key'")
    if (clean.length > MAX_TEXT_FIELD_LENGTH) throw BadRequestException("Field '$key' must be at most $MAX_TEXT_FIELD_LENGTH characters")
    return clean.takeIf { it.isNotEmpty() }?.let(::JsonPrimitive)
}

private fun numberValue(raw: JsonElement, key: String): JsonElement {
    val token = numberOf(raw, key, "a number").content
    val number = token.takeIf { it.length <= MAX_NUMBER_TOKEN_LENGTH && NUMBER_GRAMMAR.matches(it) }?.toDouble()
    if (number == null || !number.isFinite() || abs(number) > MAX_NUMBER_FIELD_MAGNITUDE) {
        throw BadRequestException("Field '$key' must be a JSON number no larger than 1e15 in magnitude")
    }
    return if (number == floor(number)) JsonPrimitive(number.toLong()) else JsonPrimitive(number)
}

private fun dateValue(text: String, key: String): JsonElement {
    val valid = DATE_SHAPE.matches(text) && try {
        LocalDate.parse(text)
        true
    } catch (_: DateTimeParseException) {
        false
    }
    if (!valid) throw BadRequestException("Field '$key' must be a calendar date as YYYY-MM-DD")
    return JsonPrimitive(text)
}

private fun selectValue(text: String, definition: FieldDefinition): JsonElement {
    if (text !in definition.optionKeys) throw BadRequestException("Field '${definition.key}' must be one of its options")
    return JsonPrimitive(text)
}

private fun multiSelectValue(raw: JsonElement, definition: FieldDefinition, before: JsonElement?): JsonElement? {
    val key = definition.key
    val items = (raw as? JsonArray)?.map { stringOf(it, key) } ?: throw BadRequestException("Field '$key' must be an array of option keys")
    if (items.isEmpty()) return null
    if (items.size > MAX_OPTIONS) throw BadRequestException("Field '$key' takes at most $MAX_OPTIONS options")
    requireNoDuplicates(items, "Field '$key' must not repeat an option")
    val kept = (before as? JsonArray).orEmpty().map { (it as JsonPrimitive).content }
    if (items.any { it !in definition.optionKeys && it !in kept }) throw BadRequestException("Field '$key' must hold only its options")
    val ordered = definition.optionKeys.filter { it in items } + kept.filter { it in items && it !in definition.optionKeys }
    return JsonArray(ordered.map(::JsonPrimitive))
}

private fun userValue(raw: JsonElement, key: String, activeMemberIds: Set<Long>): JsonElement {
    val id = numberOf(raw, key, "a user id").content.takeIf { USER_ID_GRAMMAR.matches(it) }?.toLong()
    if (id == null || id !in activeMemberIds) throw BadRequestException("Field '$key' must be the id of an active room member")
    return JsonPrimitive(id)
}

private fun urlValue(raw: String, key: String): JsonElement {
    // The same rule as every other single-line text: trimmed, and a control or hidden direction character is a 400 (a bidi override in a
    // link can make it read as another address).
    val text = sanitizeSingleLine(raw, "Field '$key'")
    val uri = parsedWebUri(text)
        ?: throw BadRequestException("Field '$key' must be an absolute http or https URL of at most $MAX_URL_FIELD_LENGTH characters")
    // `https://trusted.example@evil.example/` opens evil.example while reading as trusted.example: user info has no place in a link.
    if (uri.rawUserInfo != null) throw BadRequestException("Field '$key' must not contain a user name or password in the URL")
    return JsonPrimitive(text)
}

private fun parsedWebUri(text: String): URI? {
    if (text.length > MAX_URL_FIELD_LENGTH) return null
    val uri = try {
        URI(text)
    } catch (_: URISyntaxException) {
        return null
    }
    return uri.takeIf { it.scheme?.lowercase() in URL_SCHEMES && !it.host.isNullOrEmpty() }
}
