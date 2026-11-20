package ch.nokillswit.threads

import ch.nokillswit.rooms.SnapshotField
import ch.nokillswit.workflows.FieldType
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.long

/**
 * The field types whose values the history records. An allow-list on purpose: a TEXT or URL value is free text that can hold a pasted
 * secret and the history is append-only, so it records THAT such a field changed, never the text (1.0.0, `chat-model.md` "History");
 * a type added later records nothing until it is listed here.
 */
private val RECORDED_VALUE_TYPES = setOf(FieldType.SELECT, FieldType.MULTI_SELECT, FieldType.USER, FieldType.DATE, FieldType.NUMBER)

/**
 * The `FIELD_CHANGED` entry of one [change] of [field]: `{field, fieldLabel, from?, to?}`. For a SELECT, MULTI_SELECT, USER, DATE or
 * NUMBER field the sides are DISPLAY text (an option's label, the labels of a MULTI_SELECT joined with ", ", the member's name for
 * USER, the raw value otherwise) and an absent side means unset; a TEXT or URL field carries neither side. Pure: the caller looks the
 * USER names up ([userNames], soft-deleted accounts included) before it calls.
 */
fun threadFieldChangedEvent(field: SnapshotField, change: FieldChange, userNames: Map<UInt, String>): ThreadEventDescriptor {
    val params = mutableMapOf("field" to field.key, "fieldLabel" to field.label)
    if (field.type in RECORDED_VALUE_TYPES) {
        fieldDisplayText(field, change.from, userNames)?.let { params["from"] = it }
        fieldDisplayText(field, change.to, userNames)?.let { params["to"] = it }
    }
    return ThreadEventDescriptor(ThreadEventType.FIELD_CHANGED, params)
}

/** The USER ids held by [changes] of USER fields (either side), the names the events need. */
fun userIdsOfChanges(fields: List<SnapshotField>, changes: List<FieldChange>): Set<UInt> {
    val userKeys = fields.filter { it.type == FieldType.USER }.mapTo(HashSet()) { it.key }
    return changes.filter { it.key in userKeys }.flatMap { listOf(it.from, it.to) }
        .mapNotNullTo(HashSet()) { (it as JsonPrimitive?)?.long?.toUInt() }
}

private fun fieldDisplayText(field: SnapshotField, value: JsonElement?, userNames: Map<UInt, String>): String? {
    if (value == null) return null
    // A removed option keeps its stored key: it shows as the key (there is no label left to show).
    fun optionLabel(key: String) = field.options.firstOrNull { it.key == key }?.label ?: key
    return when (field.type) {
        FieldType.USER -> (value as JsonPrimitive).long.toUInt().let { userNames[it] ?: it.toString() }
        FieldType.SELECT -> optionLabel((value as JsonPrimitive).content)
        FieldType.MULTI_SELECT -> (value as JsonArray).joinToString(", ") { optionLabel((it as JsonPrimitive).content) }
        else -> (value as JsonPrimitive).content
    }
}
