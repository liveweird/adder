package ch.nokillswit.threads

import ch.nokillswit.rooms.SnapshotField
import ch.nokillswit.workflows.FieldType
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.long

/**
 * The `FIELD_CHANGED` entry of one [change] of [field]: `{field, fieldLabel, from?, to?}`, the sides as DISPLAY text (an option's label,
 * the labels of a MULTI_SELECT joined with ", ", the member's name for USER, the raw value otherwise); an absent side means unset. Pure:
 * the caller looks the USER names up ([userNames], soft-deleted accounts included) before it calls.
 */
fun threadFieldChangedEvent(field: SnapshotField, change: FieldChange, userNames: Map<UInt, String>): ThreadEventDescriptor {
    val params = mutableMapOf("field" to field.key, "fieldLabel" to field.label)
    fieldDisplayText(field, change.from, userNames)?.let { params["from"] = it }
    fieldDisplayText(field, change.to, userNames)?.let { params["to"] = it }
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
