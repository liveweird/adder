package ch.nokillswit.workflows

import ch.nokillswit.authz.ConflictException
import ch.nokillswit.infra.validation.requireNoDuplicates
import io.ktor.server.plugins.BadRequestException

/**
 * The pure rules kernel shared by the template registry and a room's workflow: no database, no request, no language.
 * Toadie's shape: one small thrower per rule, composed by [validateWorkflowShape]. A violation of the document's own shape is a
 * 400 ([BadRequestException]); the one rule that depends on stored state, the per-room key budget, is a 409.
 *
 * The kernel sees only the structural facts ([StatusShape], [FieldShape]); labels are validated per text by [requireLabel], so a
 * template (a label per language) and a room (one label) share the same limits.
 *
 * What the caller owns (the kernel cannot see it): it runs `sanitizeSingleLine` over every label first (trim + control-character
 * 400), then [requireLabel] over status, field AND option labels (the shape types carry keys only, so [validateWorkflowShape]
 * never checks a label), and runs [requireKeyBudget] under the room lock with the STORED keys, retired ones included.
 */
data class StatusShape(val key: String, val category: StatusCategory, val initial: Boolean)

data class FieldShape(val key: String, val type: FieldType, val optionKeys: List<String> = emptyList())

// A rejected key is echoed back in the message, bounded so a megabyte "key" does not become a megabyte error body. Truncation is
// by code point (never splits a surrogate pair) and is marked with an ellipsis.
private fun String.shown(): String =
    if (codePointCount(0, length) <= MAX_KEY_LENGTH) this else substring(0, offsetByCodePoints(0, MAX_KEY_LENGTH)) + "\u2026"

/** [what] names the thing for the message ("Status key", "Option key of field 'severity'"). */
fun requireKey(key: String, what: String) {
    if (key.length !in 1..MAX_KEY_LENGTH || !KEY_GRAMMAR.matches(key)) {
        throw BadRequestException(
            "$what must be 1-$MAX_KEY_LENGTH characters: lower-case letters and digits, " +
                "single hyphens between groups, starting with a letter",
        )
    }
}

/**
 * A label is non-blank and at most [MAX_LABEL_LENGTH] code points (an emoji counts once, not as its two UTF-16 units); trimming and
 * control characters are the sanitiser's job.
 */
fun requireLabel(label: String, what: String) {
    if (label.isBlank() || label.codePointCount(0, label.length) > MAX_LABEL_LENGTH) {
        throw BadRequestException("$what must be 1-$MAX_LABEL_LENGTH characters")
    }
}

private fun requireCount(count: Int, range: IntRange, what: String) {
    if (count !in range) throw BadRequestException("A workflow needs ${range.first}-${range.last} $what, got $count")
}

private fun requireStatusRules(statuses: List<StatusShape>) {
    requireCount(statuses.size, MIN_STATUSES..MAX_STATUSES, "statuses")
    statuses.forEach { requireKey(it.key, "Status key '${it.key.shown()}'") }
    requireNoDuplicates(statuses.map { it.key }, "Status keys must be unique")
    val initial = statuses.filter { it.initial }
    if (initial.size != 1) throw BadRequestException("Exactly one status must be the initial one, got ${initial.size}")
    if (initial.single().category != StatusCategory.OPEN) throw BadRequestException("The initial status must be in the OPEN category")
    if (statuses.none { it.category == StatusCategory.CLOSED }) {
        throw BadRequestException("At least one status must be in the CLOSED category")
    }
}

private fun requireOptionRules(field: FieldShape) {
    if (!field.type.takesOptions) {
        if (field.optionKeys.isNotEmpty()) throw BadRequestException("Field '${field.key}' of type ${field.type} cannot have options")
        return
    }
    if (field.optionKeys.size !in MIN_OPTIONS..MAX_OPTIONS) {
        throw BadRequestException(
            "Field '${field.key}' of type ${field.type} needs $MIN_OPTIONS-$MAX_OPTIONS options, got ${field.optionKeys.size}",
        )
    }
    field.optionKeys.forEach { requireKey(it, "Option key '${it.shown()}' of field '${field.key}'") }
    requireNoDuplicates(field.optionKeys, "Option keys of field '${field.key}' must be unique")
}

private fun requireFieldRules(fields: List<FieldShape>) {
    requireCount(fields.size, 0..MAX_FIELDS, "fields")
    fields.forEach { requireKey(it.key, "Field key '${it.key.shown()}'") }
    requireNoDuplicates(fields.map { it.key }, "Field keys must be unique")
    fields.forEach(::requireOptionRules)
}

/**
 * The structure of a workflow document: 2-20 statuses (unique slug keys, exactly one initial and it is OPEN, at least one
 * CLOSED), 0-20 fields (unique slug keys), options only on the select kinds (1-50, unique slugs).
 */
fun validateWorkflowShape(statuses: List<StatusShape>, fields: List<FieldShape>) {
    requireStatusRules(statuses)
    requireFieldRules(fields)
}

/**
 * Keys are reserved for the life of a room, retired ones included (a retired key is revived, never minted twice): the union of
 * the room's [existingKeys] (live and retired) and the document's [incomingKeys] may not exceed [max]. A plain 409, not a 400:
 * the document alone is valid, the room's history is what is full.
 */
fun requireKeyBudget(existingKeys: Set<String>, incomingKeys: Collection<String>, max: Int, what: String) {
    val total = (existingKeys + incomingKeys).size
    if (total > max) {
        throw ConflictException("A room can reserve at most $max $what keys (retired ones included); this change would make $total")
    }
}
