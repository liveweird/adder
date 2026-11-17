package ch.nokillswit.rooms

import ch.nokillswit.users.DEFAULT_LANGUAGE
import ch.nokillswit.workflows.FieldType
import ch.nokillswit.workflows.StatusCategory
import ch.nokillswit.workflows.StatusColour
import kotlinx.serialization.Serializable

/**
 * A room's own workflow (V13): the statuses and fields it copied from a template and edits in place. Labels are PLAIN text in ONE
 * language (a template's label map resolved once, at creation: `chat-model.md` "Copy at creation"); a position is the array index on the
 * wire, never a field. This file is the read shape; the shared vocabulary and the rules kernel live in `workflows/`.
 */

/** One select option, as stored in `room_fields.options` and as sent on the wire. */
@Serializable
data class RoomOptionDef(
    val key: String,
    val label: String,
)

@Serializable
data class RoomStatusResponse(
    val key: String,
    val label: String,
    val colour: StatusColour,
    val category: StatusCategory,
    val initial: Boolean,
    /** The threads currently in this status, from the usage port (always 0 until 0.3 brings threads). */
    val threadCount: Long,
)

@Serializable
data class RoomFieldResponse(
    val key: String,
    val label: String,
    val type: FieldType,
    val required: Boolean,
    val options: List<RoomOptionDef>,
)

/** The ACTIVE statuses and fields of a room in position order; retired rows are not shown. */
@Serializable
data class RoomWorkflowResponse(
    /** The room workflow's optimistic-concurrency token (`X-Expected-Revision` of its PUT). */
    val revision: Long,
    val statuses: List<RoomStatusResponse>,
    val fields: List<RoomFieldResponse>,
)

/**
 * The room label for one template label map: the [language] label, else the EN one (the fallback is per label, so a half-translated
 * template still copies completely). A template always carries `en` (`requireLabelMap`), so a miss is a corrupt document.
 */
internal fun Map<String, String>.resolvedLabel(language: String): String =
    this[language]?.takeIf { it.isNotBlank() } ?: this[DEFAULT_LANGUAGE] ?: error("A workflow label without an '$DEFAULT_LANGUAGE' value")

/**
 * The resolved labels of ONE list (the statuses, the fields, or one field's options). Resolution is per label, so a template's per
 * language uniqueness can break in the mix (one status translated to a word another status keeps in English): the whole list then
 * falls back to EN, which the template guarantees unique, so a room never starts with a label clash its own workflow rules reject.
 */
internal fun resolvedLabels(labels: List<Map<String, String>>, language: String): List<String> {
    val resolved = labels.map { it.resolvedLabel(language) }
    val distinct = resolved.map { it.lowercase() }.toSet().size == resolved.size
    return if (distinct) resolved else labels.map { it.resolvedLabel(DEFAULT_LANGUAGE) }
}
