package ch.nokillswit.workflows

import ch.nokillswit.infra.db.SoftDeletable
import ch.nokillswit.infra.db.active
import ch.nokillswit.infra.db.containsNormalized
import ch.nokillswit.infra.db.jsonb
import ch.nokillswit.infra.db.lockActiveForWrite
import ch.nokillswit.infra.db.nowMillis
import ch.nokillswit.infra.json.canonicalJson
import ch.nokillswit.infra.paging.PageRequest
import ch.nokillswit.infra.paging.applyPaging
import ch.nokillswit.infra.revision.requireRevision
import io.ktor.util.AttributeKey
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.dao.id.UIntIdTable
import org.jetbrains.exposed.v1.core.vendors.ForUpdateOption
import org.jetbrains.exposed.v1.r2dbc.*
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

val WorkflowTemplateServiceKey = AttributeKey<WorkflowTemplateService>("WorkflowTemplateService")

private val SORTABLE_COLUMNS: Map<String, Column<*>> = mapOf(
    "id" to WorkflowTemplateService.Templates.id,
    "name" to WorkflowTemplateService.Templates.name,
    "updatedAt" to WorkflowTemplateService.Templates.updatedAt,
)

/** The ONE sortable whitelist - derived from the column map so the two can never drift. */
val WORKFLOW_TEMPLATE_SORT_FIELDS: Set<String> = SORTABLE_COLUMNS.keys

// The stored document is tolerant on read (a later version may add a member); it is written complete (`encodeDefaults`) through
// the canonical-JSON writer so two equal documents are byte-equal text.
private val storedJson = Json { ignoreUnknownKeys = true }
private val writtenJson = Json { encodeDefaults = true }

/** The length of the document's [member] array, 0 when the member is absent or not an array (a malformed row still lists). */
private fun arrayLength(member: String): ExpressionWithColumnType<Int> = object : ExpressionWithColumnType<Int>() {
    override val columnType: IColumnType<Int> = IntegerColumnType()

    override fun toQueryBuilder(queryBuilder: QueryBuilder) {
        val array = "${WorkflowTemplateService.Templates.definition.nameInDatabaseCase()} -> '$member'"
        queryBuilder.append("CASE WHEN jsonb_typeof($array) = 'array' THEN jsonb_array_length($array) ELSE 0 END")
    }
}

class WorkflowTemplateService(private val database: R2dbcDatabase) {
    object Templates : UIntIdTable("workflow_templates"), SoftDeletable {
        // Case-folded name uniqueness rides the partial unique index uq_workflow_templates_name_active (V11, active rows only).
        val name = varchar("name", length = MAX_TEMPLATE_NAME_LENGTH)
        val description = varchar("description", length = MAX_TEMPLATE_DESCRIPTION_LENGTH).nullable()

        /** The statuses + fields document (V11), canonical JSON text. */
        val definition = jsonb("definition")

        /** The optimistic-concurrency token (`X-Expected-Revision`); every successful PUT stores `revision + 1`. */
        val revision = long("revision").default(1L)
        val createdAt = long("created_at")
        val updatedAt = long("updated_at")
        override val markedAsDeleted = bool("marked_as_deleted").default(false)
    }

    suspend fun list(filter: WorkflowTemplateListFilter, paging: PageRequest): WorkflowTemplateListResult = suspendTransaction(database) {
        val predicate = buildPredicate(filter) and Templates.active()
        val total = Templates.selectAll().where { predicate }.count()
        // The counts come from SQL, never from decoding the documents: one undecodable row cannot 500 the whole list.
        val statusCount = arrayLength("statuses")
        val fieldCount = arrayLength("fields")
        val rows = Templates
            .select(
                Templates.id, Templates.name, Templates.description, Templates.revision, Templates.updatedAt, statusCount, fieldCount,
            )
            .where { predicate }
            .applyPaging(paging, SORTABLE_COLUMNS)
            .toList()
        WorkflowTemplateListResult(
            items = rows.map { row ->
                WorkflowTemplateListItem(
                    id = row[Templates.id].value,
                    name = row[Templates.name],
                    description = row[Templates.description],
                    statusCount = row[statusCount],
                    fieldCount = row[fieldCount],
                    revision = row[Templates.revision],
                    updatedAt = row[Templates.updatedAt],
                )
            },
            total = total,
        )
    }

    /** The template, or null when missing/deleted. */
    suspend fun read(id: UInt): WorkflowTemplateResponse? = suspendTransaction(database) {
        val row = Templates.selectAll().where { (Templates.id eq id) and Templates.active() }.toList().singleOrNull()
            ?: return@suspendTransaction null
        val definition = decode(row[Templates.definition])
        WorkflowTemplateResponse(
            id = id,
            name = row[Templates.name],
            description = row[Templates.description],
            statuses = definition.statuses,
            fields = definition.fields,
            revision = row[Templates.revision],
            createdAt = row[Templates.createdAt],
            updatedAt = row[Templates.updatedAt],
        )
    }

    /** Creates the template at revision 1. A name clash with an active template rides the V11 partial index into 23505 -> 409. */
    suspend fun create(request: WorkflowTemplateRequest): UInt = suspendTransaction(database) {
        validateWorkflowTemplate(request) // re-checked service-side so direct callers stay guarded
        val stamp = nowMillis()
        Templates.insert {
            it[name] = request.name
            it[description] = request.description
            it[definition] = encode(request)
            it[revision] = 1L
            it[createdAt] = stamp
            it[updatedAt] = stamp
        }[Templates.id].value
    }

    /**
     * The whole-document replace under optimistic concurrency. Locks the active row `FOR NO KEY UPDATE` (writers serialize; no
     * stall on FK checks, persistence.md "Row locks vs. FK locks"), THEN compares [expectedRevision] with the stored one
     * ([requireRevision]: a stale write is the typed 409) and writes with `revision + 1`. Returns the new revision, or null when the
     * template is missing or deleted (the route's 404).
     */
    suspend fun update(id: UInt, expectedRevision: Long, request: WorkflowTemplateRequest): Long? = suspendTransaction(database) {
        validateWorkflowTemplate(request)
        val locked = Templates.select(Templates.revision)
            .where { (Templates.id eq id) and Templates.active() }
            .forUpdate(ForUpdateOption.PostgreSQL.ForNoKeyUpdate)
            .toList()
            .singleOrNull()
            ?: return@suspendTransaction null
        val current = locked[Templates.revision]
        requireRevision(expectedRevision, current)
        Templates.update({ (Templates.id eq id) and Templates.active() }) {
            it[name] = request.name
            it[description] = request.description
            it[definition] = encode(request)
            it[revision] = current + 1
            it[updatedAt] = nowMillis()
        }
        current + 1
    }

    /** Soft delete: the template stops reading as active and frees its name; rooms that copied it are untouched. `0` = missing (404). */
    suspend fun delete(id: UInt): Int = suspendTransaction(database) {
        if (!Templates.lockActiveForWrite(id)) return@suspendTransaction 0
        Templates.update({ (Templates.id eq id) and Templates.active() }) {
            it[markedAsDeleted] = true
            it[updatedAt] = nowMillis()
        }
    }

    private fun encode(request: WorkflowTemplateRequest): String = canonicalJson(
        writtenJson.encodeToJsonElement(WorkflowDefinition.serializer(), WorkflowDefinition(request.statuses, request.fields)),
    )

    private fun decode(stored: String): WorkflowDefinition = storedJson.decodeFromString(WorkflowDefinition.serializer(), stored)

    private fun buildPredicate(filter: WorkflowTemplateListFilter): Op<Boolean> {
        var op: Op<Boolean> = Op.TRUE
        filter.name?.takeIf { it.isNotBlank() }?.let { op = op and Templates.name.containsNormalized(it) }
        return op
    }
}
