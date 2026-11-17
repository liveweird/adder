package ch.nokillswit.workflows

import ch.nokillswit.infra.paging.PageResponse
import ch.nokillswit.infra.validation.requireNameAndDescription
import ch.nokillswit.infra.validation.sanitizeSingleLine
import ch.nokillswit.infra.validation.sanitizedDescription
import ch.nokillswit.users.DEFAULT_LANGUAGE
import ch.nokillswit.users.SUPPORTED_LANGUAGES
import io.ktor.server.plugins.BadRequestException
import kotlinx.serialization.Serializable

/**
 * The ADMIN-curated workflow-template registry (V11, seeded by V12): a name, a description and ONE definition document
 * (statuses + fields). Everyone reads templates (the room-create form picks one); ADMIN writes them. A room copies a template's
 * statuses and fields at creation and edits its own copy afterwards, so a template edit or delete never reaches a room. The whole
 * document is replaced by one PUT under `X-Expected-Revision`.
 *
 * Labels are a language map `{en, pl?}` (Lettuce's dictionary `values` shape): EN is required, PL optional, no other language.
 * Name and description are plain registry text, not localised.
 */
const val MAX_TEMPLATE_NAME_LENGTH = 100
const val MAX_TEMPLATE_DESCRIPTION_LENGTH = 500

/** One status of the document. `initial`, `required` and `options` are explicit on the wire: a whole-document PUT states everything. */
@Serializable
data class WorkflowStatusDef(
    val key: String,
    val label: Map<String, String>,
    val colour: StatusColour,
    val category: StatusCategory,
    val initial: Boolean,
)

@Serializable
data class WorkflowOptionDef(
    val key: String,
    val label: Map<String, String>,
)

@Serializable
data class WorkflowFieldDef(
    val key: String,
    val label: Map<String, String>,
    val type: FieldType,
    val required: Boolean,
    val options: List<WorkflowOptionDef>,
)

/** The stored `definition` document (V11). Position is the array index; keys are immutable slugs. */
@Serializable
data class WorkflowDefinition(
    val statuses: List<WorkflowStatusDef>,
    val fields: List<WorkflowFieldDef>,
)

/** Create and PUT carry the same body: the PUT is a full replace. */
@Serializable
data class WorkflowTemplateRequest(
    val name: String,
    val description: String? = null,
    val statuses: List<WorkflowStatusDef>,
    val fields: List<WorkflowFieldDef>,
)

@Serializable
data class WorkflowTemplateResponse(
    val id: UInt,
    val name: String,
    val description: String?,
    val statuses: List<WorkflowStatusDef>,
    val fields: List<WorkflowFieldDef>,
    /** The optimistic-concurrency token a PUT names in `X-Expected-Revision`; starts at 1, every successful PUT bumps it. */
    val revision: Long,
    val createdAt: Long,
    val updatedAt: Long,
)

/** The list row: the document itself is only on the single-template read. */
@Serializable
data class WorkflowTemplateListItem(
    val id: UInt,
    val name: String,
    val description: String?,
    val statusCount: Int,
    val fieldCount: Int,
    val revision: Long,
    val updatedAt: Long,
)

typealias WorkflowTemplatePageResponse = PageResponse<WorkflowTemplateListItem>

data class WorkflowTemplateListFilter(
    val name: String? = null,
)

data class WorkflowTemplateListResult(val items: List<WorkflowTemplateListItem>, val total: Long)

private fun Map<String, String>.sanitizedLabel(what: String): Map<String, String> = mapValues { sanitizeSingleLine(it.value, what) }

private fun WorkflowStatusDef.sanitized() = copy(label = label.sanitizedLabel("Label of status '${key.shown()}'"))

private fun WorkflowOptionDef.sanitized(fieldKey: String) =
    copy(label = label.sanitizedLabel("Label of option '${key.shown()}' of field '${fieldKey.shown()}'"))

private fun WorkflowFieldDef.sanitized() = copy(
    label = label.sanitizedLabel("Label of field '${key.shown()}'"),
    options = options.map { it.sanitized(key) },
)

/** Trims every scalar text (names, descriptions, every label of every language); a control character is a 400. */
fun sanitizedWorkflowTemplate(request: WorkflowTemplateRequest): WorkflowTemplateRequest = WorkflowTemplateRequest(
    name = sanitizeSingleLine(request.name, "Name"),
    description = sanitizedDescription(request.description),
    statuses = request.statuses.map { it.sanitized() },
    fields = request.fields.map { it.sanitized() },
)

/**
 * A label map after Lettuce's `validateDictionaryUpdate`: only [SUPPORTED_LANGUAGES] as keys, [DEFAULT_LANGUAGE] required, any other
 * language optional ("omit it to clear" - a PRESENT blank value is a 400), every value within the shared label limits
 * ([requireLabel]). The values are already trimmed by the sanitiser.
 */
fun requireLabelMap(label: Map<String, String>, what: String) {
    val unsupported = label.keys - SUPPORTED_LANGUAGES.toSet()
    if (unsupported.isNotEmpty()) {
        // The offending keys are echoed (bounded: at most three, each cut like a rejected workflow key), never the whole map.
        throw BadRequestException(
            "$what: unsupported label language ${unsupported.sorted().take(3).joinToString { "'${it.shown()}'" }} " +
                "(supported: ${SUPPORTED_LANGUAGES.joinToString()})",
        )
    }
    if (label[DEFAULT_LANGUAGE].isNullOrBlank()) throw BadRequestException("$what must provide an '$DEFAULT_LANGUAGE' label")
    label.forEach { (language, text) -> requireLabel(text, "$what ($language)") }
}

/**
 * Lettuce's per-language uniqueness rule: within one list of [labels] (the statuses, the fields, or ONE field's options) no two
 * labels of the same language may be equal, case-insensitively after trimming. The same text under two languages is fine (`ETA` / `ETA`).
 * Run after every label passed [requireLabelMap], so the echoed label is at most [MAX_LABEL_LENGTH] characters.
 */
fun requireUniqueLabels(labels: List<Map<String, String>>, what: String) {
    for (language in SUPPORTED_LANGUAGES) {
        val seen = HashSet<String>()
        for (text in labels.mapNotNull { it[language]?.trim() }) {
            if (!seen.add(text.lowercase())) throw BadRequestException("$what labels must be unique ($language): '$text' is used twice")
        }
    }
}

/**
 * The template rules - enforced by the route AND re-checked by the service. The kernel validates the structure first (so every key
 * echoed below is a valid slug); the label maps are the part of the document the kernel cannot see, and cover status, field AND
 * option labels, each list then held to per-language uniqueness ([requireUniqueLabels]).
 */
fun validateWorkflowTemplate(request: WorkflowTemplateRequest) {
    requireNameAndDescription(request.name, request.description, MAX_TEMPLATE_NAME_LENGTH, MAX_TEMPLATE_DESCRIPTION_LENGTH)
    validateWorkflowShape(
        request.statuses.map { StatusShape(it.key, it.category, it.initial) },
        request.fields.map { field -> FieldShape(field.key, field.type, field.options.map { it.key }) },
    )
    request.statuses.forEach { requireLabelMap(it.label, "Label of status '${it.key}'") }
    request.fields.forEach { field ->
        requireLabelMap(field.label, "Label of field '${field.key}'")
        field.options.forEach { requireLabelMap(it.label, "Label of option '${it.key}' of field '${field.key}'") }
    }
    requireUniqueLabels(request.statuses.map { it.label }, "Status")
    requireUniqueLabels(request.fields.map { it.label }, "Field")
    request.fields.forEach { field -> requireUniqueLabels(field.options.map { it.label }, "Option of field '${field.key}'") }
}
