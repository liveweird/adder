package ch.nokillswit.rooms

import ch.nokillswit.infra.validation.sanitizeSingleLine
import ch.nokillswit.users.DEFAULT_LANGUAGE
import ch.nokillswit.workflows.FieldShape
import ch.nokillswit.workflows.FieldType
import ch.nokillswit.workflows.MAX_STATUSES
import ch.nokillswit.workflows.StatusCategory
import ch.nokillswit.workflows.StatusColour
import ch.nokillswit.workflows.StatusShape
import ch.nokillswit.workflows.requireLabel
import ch.nokillswit.workflows.shown
import ch.nokillswit.workflows.validateWorkflowShape
import io.ktor.server.plugins.BadRequestException
import kotlinx.serialization.Serializable

/**
 * A room's own workflow (V13): the statuses and fields it copied from a template and edits in place. Labels are PLAIN text in ONE
 * language (a template's label map resolved once, at creation: `chat-model.md` "Copy at creation"); a position is the array index on the
 * wire, never a field. This file is the wire shapes (the read and the whole-document PUT) and the request rules; the shared
 * vocabulary and the rules kernel live in `workflows/`.
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
    /** The threads currently in this status, from the usage port. */
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

/** One status of a room's workflow as a thread write sees it ([retired] ones keep their key and their row, `V13`). */
data class SnapshotStatus(
    val key: String,
    val label: String,
    val category: StatusCategory,
    val initial: Boolean,
    val retired: Boolean,
)

/** One field of a room's workflow as a thread write sees it. */
data class SnapshotField(
    val key: String,
    val label: String,
    val type: FieldType,
    val required: Boolean,
    val options: List<RoomOptionDef>,
    val retired: Boolean,
)

/**
 * A room's whole workflow, active AND retired rows, in position order (`RoomWorkflowService.snapshot`): what `threads/` validates a
 * thread write against and takes the label snapshots of its history events from. Plain data, so `threads/` never touches the rows.
 */
data class RoomWorkflowSnapshot(val statuses: List<SnapshotStatus>, val fields: List<SnapshotField>)

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

/** One status of the PUT document: the read shape without `threadCount` (a count is the server's to compute). */
@Serializable
data class RoomStatusDef(
    val key: String,
    val label: String,
    val colour: StatusColour,
    val category: StatusCategory,
    val initial: Boolean,
)

/** One field of the PUT document; `options` is explicit (empty for every kind without options). */
@Serializable
data class RoomFieldDef(
    val key: String,
    val label: String,
    val type: FieldType,
    val required: Boolean,
    val options: List<RoomOptionDef>,
)

/**
 * The whole-document PUT body: the ACTIVE statuses and fields in the new order (a key absent from it is retired, a retired key present in
 * it is revived), plus `replacements` `{retiredKey: replacementKey}` naming where the threads of each status this PUT retires go.
 */
@Serializable
data class RoomWorkflowRequest(
    val statuses: List<RoomStatusDef>,
    val fields: List<RoomFieldDef>,
    val replacements: Map<String, String> = emptyMap(),
)

private fun RoomOptionDef.sanitized(fieldKey: String) =
    copy(label = sanitizeSingleLine(label, "Label of option '${key.shown()}' of field '${fieldKey.shown()}'"))

/** Trims every label (a control character is a 400); keys are matched exactly, so they are never touched. */
fun sanitizedRoomWorkflow(request: RoomWorkflowRequest): RoomWorkflowRequest = request.copy(
    statuses = request.statuses.map { it.copy(label = sanitizeSingleLine(it.label, "Label of status '${it.key.shown()}'")) },
    fields = request.fields.map {
        it.copy(
            label = sanitizeSingleLine(it.label, "Label of field '${it.key.shown()}'"),
            options = it.options.map { option -> option.sanitized(it.key) },
        )
    },
)

/**
 * Room labels are single-language: within one list (the statuses, the fields, or ONE field's options) no two labels may be equal,
 * case-insensitively after trimming (the templates' per-language rule, `requireUniqueLabels`, for one language). Run after every label
 * passed [requireLabel], so the echoed label is at most `MAX_LABEL_LENGTH` characters.
 */
private fun requireUniqueRoomLabels(labels: List<String>, what: String) {
    val seen = HashSet<String>()
    for (label in labels) {
        if (!seen.add(label.lowercase())) throw BadRequestException("$what labels must be unique: '$label' is used twice")
    }
}

/**
 * The room workflow rules - enforced by the route AND re-checked by the service: the shared kernel (structure and keys), then the parts
 * it cannot see - status, field AND option labels (length, uniqueness per list) - and the shape of `replacements`. The key-budget
 * rule (a 409) and every rule that needs the stored rows run under the room lock (`RoomWorkflowPlan`).
 */
fun validateRoomWorkflow(request: RoomWorkflowRequest) {
    validateWorkflowShape(
        request.statuses.map { StatusShape(it.key, it.category, it.initial) },
        request.fields.map { field -> FieldShape(field.key, field.type, field.options.map { it.key }) },
    )
    request.statuses.forEach { requireLabel(it.label, "Label of status '${it.key}'") }
    request.fields.forEach { field ->
        requireLabel(field.label, "Label of field '${field.key}'")
        field.options.forEach { requireLabel(it.label, "Label of option '${it.key}' of field '${field.key}'") }
    }
    requireUniqueRoomLabels(request.statuses.map { it.label }, "Status")
    requireUniqueRoomLabels(request.fields.map { it.label }, "Field")
    request.fields.forEach { field -> requireUniqueRoomLabels(field.options.map { it.label }, "Option of field '${field.key}'") }
    // A change retires at most the statuses a room can have active, so a longer mapping is junk (and bounds the work of its check).
    if (request.replacements.size > MAX_STATUSES) {
        throw BadRequestException("replacements may name at most $MAX_STATUSES statuses, got ${request.replacements.size}")
    }
}
