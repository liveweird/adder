package ch.nokillswit.rooms

import ch.nokillswit.authz.CallerPrincipal
import ch.nokillswit.infra.db.active
import ch.nokillswit.infra.db.jsonb
import ch.nokillswit.infra.db.nowMillis
import ch.nokillswit.infra.json.canonicalJson
import ch.nokillswit.infra.notify.LiveHint
import ch.nokillswit.infra.notify.LiveKind
import ch.nokillswit.infra.notify.notifyLive
import ch.nokillswit.infra.revision.requireRevision
import ch.nokillswit.workflows.FieldShape
import ch.nokillswit.workflows.FieldType
import ch.nokillswit.workflows.MAX_FIELD_KEYS_PER_ROOM
import ch.nokillswit.workflows.MAX_KEY_LENGTH
import ch.nokillswit.workflows.MAX_LABEL_LENGTH
import ch.nokillswit.workflows.MAX_STATUS_KEYS_PER_ROOM
import ch.nokillswit.workflows.StatusCategory
import ch.nokillswit.workflows.StatusColour
import ch.nokillswit.workflows.StatusShape
import ch.nokillswit.workflows.WorkflowDefinition
import ch.nokillswit.workflows.requireKeyBudget
import io.ktor.util.AttributeKey
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.vendors.ForUpdateOption
import org.jetbrains.exposed.v1.r2dbc.*
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

val RoomWorkflowServiceKey = AttributeKey<RoomWorkflowService>("RoomWorkflowService")

private val storedJson = Json { ignoreUnknownKeys = true }
private val optionsSerializer = ListSerializer(RoomOptionDef.serializer())

/** What a successful workflow PUT did: the new [revision] and the counts the route audits (`room.workflow_updated`). */
data class RoomWorkflowChange(
    val revision: Long,
    /** Statuses that became live: new keys and revived ones. */
    val statusesAdded: Int,
    val statusesRetired: Int,
    val fieldsAdded: Int,
    val fieldsRetired: Int,
    /** Threads moved to a replacement status. */
    val threadsMoved: Long,
)

/**
 * The room's own workflow rows (V13): [RoomStatuses] and [RoomFields], soft-deleted with a FULL `(room_id, key)` primary key (a retired
 * key stays reserved and is revived, never minted twice; `persistence.md`). Every method runs inside the caller's transaction when there
 * is one (Exposed reuses it), so room creation copies the workflow in the room's own transaction and a workflow write runs under the room
 * lock `withRoomWrite` took.
 */
class RoomWorkflowService(private val database: R2dbcDatabase) {
    object RoomStatuses : Table("room_statuses") {
        val roomId = reference("room_id", RoomService.Rooms)
        val key = varchar("key", length = MAX_KEY_LENGTH)
        val label = varchar("label", length = MAX_LABEL_LENGTH)
        val colour = varchar("colour", length = 10)
        val category = varchar("category", length = 10)
        val isInitial = bool("is_initial").default(false)
        val position = integer("position")

        /** Retired: the key stays reserved for the life of the room. */
        val markedAsDeleted = bool("marked_as_deleted").default(false)
        override val primaryKey = PrimaryKey(roomId, key)
    }

    object RoomFields : Table("room_fields") {
        val roomId = reference("room_id", RoomService.Rooms)
        val key = varchar("key", length = MAX_KEY_LENGTH)
        val label = varchar("label", length = MAX_LABEL_LENGTH)
        val type = varchar("type", length = 20)

        /** `[{key,label}]` canonical JSON; `[]` for a kind without options. */
        val optionsJson = jsonb("options")
        val required = bool("required").default(false)
        val position = integer("position")
        val markedAsDeleted = bool("marked_as_deleted").default(false)
        override val primaryKey = PrimaryKey(roomId, key)
    }

    /**
     * Copies [definition] into room [roomId] (the creation step): one row per status and field, `position` = array index, labels
     * resolved to [language] ([resolvedLabels]). Per-row statements, at most 20 rows each (`MAX_STATUSES`/`MAX_FIELDS`).
     */
    suspend fun copyFromTemplate(roomId: UInt, definition: WorkflowDefinition, language: String) = suspendTransaction(database) {
        val statusLabels = resolvedLabels(definition.statuses.map { it.label }, language)
        definition.statuses.forEachIndexed { index, status ->
            RoomStatuses.insert {
                it[RoomStatuses.roomId] = roomId
                it[key] = status.key
                it[label] = statusLabels[index]
                it[colour] = status.colour.name
                it[category] = status.category.name
                it[isInitial] = status.initial
                it[position] = index
            }
        }
        val fieldLabels = resolvedLabels(definition.fields.map { it.label }, language)
        definition.fields.forEachIndexed { index, field ->
            val optionLabels = resolvedLabels(field.options.map { it.label }, language)
            val options = field.options.mapIndexed { n, option -> RoomOptionDef(option.key, optionLabels[n]) }
            RoomFields.insert {
                it[RoomFields.roomId] = roomId
                it[key] = field.key
                it[label] = fieldLabels[index]
                it[type] = field.type.name
                it[optionsJson] = canonicalJson(Json.encodeToString(optionsSerializer, options))
                it[required] = field.required
                it[position] = index
            }
        }
    }

    /**
     * The whole-document PUT under the room lock (`chat-model.md` "Workflow PUT inside the block"), in this order: an archived room is
     * the typed `409`; [expectedRevision] against the stored one (`revision-conflict`); the status rows are locked `FOR NO KEY UPDATE`
     * (0.3 thread writes run under the same room lock, so none can land on a status being retired; the `(room_id, status_key)` FK
     * is the backstop); the pure plan; a field
     * retyped is `409 field-type-immutable`; the key budget (retired keys included) is the plain `409`; the replacement mapping (`400`)
     * and the thread usage from [usage] (`409 status-in-use`); then `is_initial` is cleared on EVERY row first (the partial unique index
     * is checked per row), absent keys retire, the document is upserted by key with `position` = index (retired keys revive); THEN the
     * moves (a replacement can be a status this PUT just added), and the revision is bumped. Any throw rolls the whole transaction
     * back, moves included.
     */
    suspend fun replace(
        roomId: UInt,
        caller: CallerPrincipal,
        guard: RoomGuard,
        expectedRevision: Long,
        request: RoomWorkflowRequest,
        usage: WorkflowUsage,
    ): RoomWorkflowChange {
        validateRoomWorkflow(request) // re-checked service-side so direct callers stay guarded
        return database.withRoomWrite(roomId, caller, guard) { _, standing ->
            if (standing.archived) throw roomArchived()
            val rooms = RoomService.Rooms
            val current = rooms.select(rooms.workflowRevision).where { rooms.id eq roomId }.toList().single()[rooms.workflowRevision]
            requireRevision(expectedRevision, current)
            val statusRows = RoomStatuses.selectAll()
                .where { RoomStatuses.roomId eq roomId }
                .orderBy(RoomStatuses.position to SortOrder.ASC, RoomStatuses.key to SortOrder.ASC)
                .forUpdate(ForUpdateOption.PostgreSQL.ForNoKeyUpdate)
                .toList()
            val fieldRows = RoomFields.selectAll().where { RoomFields.roomId eq roomId }.toList()
            val plan = planRoomWorkflow(
                storedStatuses = statusRows.map {
                    StoredStatus(it[RoomStatuses.key], StatusCategory.valueOf(it[RoomStatuses.category]), it[RoomStatuses.markedAsDeleted])
                },
                storedFields = fieldRows.map {
                    StoredField(it[RoomFields.key], FieldType.valueOf(it[RoomFields.type]), it[RoomFields.markedAsDeleted])
                },
                statuses = request.statuses.map { StatusShape(it.key, it.category, it.initial) },
                fields = request.fields.map { FieldShape(it.key, it.type, it.options.map { option -> option.key }) },
            )
            if (plan.fieldsRetyped.isNotEmpty()) throw fieldTypeImmutable(plan.fieldsRetyped)
            val storedStatusKeys = statusRows.mapTo(HashSet()) { it[RoomStatuses.key] }
            val storedFieldKeys = fieldRows.mapTo(HashSet()) { it[RoomFields.key] }
            requireKeyBudget(storedStatusKeys, request.statuses.map { it.key }, MAX_STATUS_KEYS_PER_ROOM, "status")
            requireKeyBudget(storedFieldKeys, request.fields.map { it.key }, MAX_FIELD_KEYS_PER_ROOM, "field")
            plan.requireValidReplacements(request.replacements)
            val counts = usage.threadCounts(roomId, plan.statusesToCount)
            val inUse = plan.statusesInUse(counts, request.replacements)
            if (inUse.isNotEmpty()) throw statusInUse(inUse)
            writeStatuses(roomId, request.statuses, storedStatusKeys, plan.statusesRetired)
            writeFields(roomId, request.fields, storedFieldKeys, plan.fieldsRetired)
            // AFTER the upsert: a replacement may be a status this very PUT adds or revives, and 0.3's thread rows reference
            // `room_statuses(room_id, key)`, so the target row must exist (and be live) when the threads move.
            val moved = plan.movesFor(counts, request.replacements)
                .sumOf { (from, to) -> usage.moveThreads(roomId, from, to, caller.userId) }
            rooms.update({ rooms.id eq roomId }) {
                it[rooms.workflowRevision] = current + 1
                it[rooms.updatedAt] = nowMillis()
            }
            // ONE hint however many threads moved: the moves are part of the workflow change (`live.md` "Hints").
            notifyLive(LiveHint(LiveKind.ROOM_WORKFLOW_UPDATED, roomId))
            RoomWorkflowChange(
                revision = current + 1,
                statusesAdded = plan.statusesBecomingLive,
                statusesRetired = plan.statusesRetired.size,
                fieldsAdded = plan.fieldsBecomingLive,
                fieldsRetired = plan.fieldsRetired.size,
                threadsMoved = moved,
            )
        }
    }

    private suspend fun writeStatuses(roomId: UInt, statuses: List<RoomStatusDef>, stored: Set<String>, retired: List<String>) {
        // The partial unique index uq_room_statuses_initial is checked per row: every flag (retired rows included) goes down first.
        RoomStatuses.update({ RoomStatuses.roomId eq roomId }) { it[RoomStatuses.isInitial] = false }
        if (retired.isNotEmpty()) {
            RoomStatuses.update({ (RoomStatuses.roomId eq roomId) and (RoomStatuses.key inList retired) }) {
                it[RoomStatuses.markedAsDeleted] = true
            }
        }
        statuses.forEachIndexed { index, status ->
            if (status.key in stored) {
                RoomStatuses.update({ (RoomStatuses.roomId eq roomId) and (RoomStatuses.key eq status.key) }) {
                    it[RoomStatuses.label] = status.label
                    it[RoomStatuses.colour] = status.colour.name
                    it[RoomStatuses.category] = status.category.name
                    it[RoomStatuses.isInitial] = status.initial
                    it[RoomStatuses.position] = index
                    it[RoomStatuses.markedAsDeleted] = false
                }
            } else {
                RoomStatuses.insert {
                    it[RoomStatuses.roomId] = roomId
                    it[RoomStatuses.key] = status.key
                    it[RoomStatuses.label] = status.label
                    it[RoomStatuses.colour] = status.colour.name
                    it[RoomStatuses.category] = status.category.name
                    it[RoomStatuses.isInitial] = status.initial
                    it[RoomStatuses.position] = index
                }
            }
        }
    }

    private suspend fun writeFields(roomId: UInt, fields: List<RoomFieldDef>, stored: Set<String>, retired: List<String>) {
        if (retired.isNotEmpty()) {
            RoomFields.update({ (RoomFields.roomId eq roomId) and (RoomFields.key inList retired) }) {
                it[RoomFields.markedAsDeleted] = true
            }
        }
        fields.forEachIndexed { index, field ->
            val options = canonicalJson(Json.encodeToString(optionsSerializer, field.options))
            if (field.key in stored) {
                RoomFields.update({ (RoomFields.roomId eq roomId) and (RoomFields.key eq field.key) }) {
                    it[RoomFields.label] = field.label
                    it[RoomFields.optionsJson] = options
                    it[RoomFields.required] = field.required
                    it[RoomFields.position] = index
                    it[RoomFields.markedAsDeleted] = false
                }
            } else {
                RoomFields.insert {
                    it[RoomFields.roomId] = roomId
                    it[RoomFields.key] = field.key
                    it[RoomFields.label] = field.label
                    it[RoomFields.type] = field.type.name
                    it[RoomFields.optionsJson] = options
                    it[RoomFields.required] = field.required
                    it[RoomFields.position] = index
                }
            }
        }
    }

    /**
     * The room's statuses and fields, retired ones included, in position order (a retired row keeps its last position, which an active
     * row can share, so the statuses tie-break by key: the order is total and the thread list's `sort=status` is deterministic). Runs
     * inside the caller's transaction (a thread write, under the room lock), so the snapshot is the very state the write is validated
     * against: no workflow PUT can interleave.
     */
    suspend fun snapshot(roomId: UInt): RoomWorkflowSnapshot = RoomWorkflowSnapshot(
        statuses = RoomStatuses.selectAll().where { RoomStatuses.roomId eq roomId }
            .orderBy(RoomStatuses.position to SortOrder.ASC, RoomStatuses.key to SortOrder.ASC).map {
            SnapshotStatus(
                key = it[RoomStatuses.key],
                label = it[RoomStatuses.label],
                category = StatusCategory.valueOf(it[RoomStatuses.category]),
                initial = it[RoomStatuses.isInitial],
                retired = it[RoomStatuses.markedAsDeleted],
            )
        }.toList(),
        fields = RoomFields.selectAll().where { RoomFields.roomId eq roomId }.orderBy(RoomFields.position to SortOrder.ASC).map {
            SnapshotField(
                key = it[RoomFields.key],
                label = it[RoomFields.label],
                type = FieldType.valueOf(it[RoomFields.type]),
                required = it[RoomFields.required],
                options = storedJson.decodeFromString(optionsSerializer, it[RoomFields.optionsJson]),
                retired = it[RoomFields.markedAsDeleted],
            )
        }.toList(),
    )

    /**
     * The type of every ACTIVE field of the room by key: the light read of a thread read (which fields to show, which hold users), one
     * narrow query instead of the whole [snapshot]. Runs inside the caller's transaction.
     */
    suspend fun activeFieldTypes(roomId: UInt): Map<String, FieldType> =
        RoomFields.select(RoomFields.key, RoomFields.type)
            .where { (RoomFields.roomId eq roomId) and (RoomFields.markedAsDeleted eq false) }
            .map { it[RoomFields.key] to FieldType.valueOf(it[RoomFields.type]) }
            .toList()
            .toMap()

    /**
     * The room's ACTIVE statuses and fields in position order with the workflow revision, or null when the room is missing or deleted
     * (the route runs its visibility guard first). One transaction with statement-level snapshots (a concurrent PUT may show a
     * momentarily mixed count).
     * The per-status thread counts come from [usage].
     */
    suspend fun read(roomId: UInt, usage: WorkflowUsage): RoomWorkflowResponse? = suspendTransaction(database) {
        val revision = RoomService.Rooms.select(RoomService.Rooms.workflowRevision)
            .where { (RoomService.Rooms.id eq roomId) and RoomService.Rooms.active() }
            .toList()
            .singleOrNull()
            ?.get(RoomService.Rooms.workflowRevision)
            ?: return@suspendTransaction null
        val statusRows = RoomStatuses.selectAll()
            .where { (RoomStatuses.roomId eq roomId) and (RoomStatuses.markedAsDeleted eq false) }
            .orderBy(RoomStatuses.position to SortOrder.ASC)
            .toList()
        val counts = usage.threadCounts(roomId, statusRows.map { it[RoomStatuses.key] })
        val fieldRows = RoomFields.selectAll()
            .where { (RoomFields.roomId eq roomId) and (RoomFields.markedAsDeleted eq false) }
            .orderBy(RoomFields.position to SortOrder.ASC)
            .toList()
        RoomWorkflowResponse(
            revision = revision,
            statuses = statusRows.map { row ->
                RoomStatusResponse(
                    key = row[RoomStatuses.key],
                    label = row[RoomStatuses.label],
                    colour = StatusColour.valueOf(row[RoomStatuses.colour]),
                    category = StatusCategory.valueOf(row[RoomStatuses.category]),
                    initial = row[RoomStatuses.isInitial],
                    threadCount = counts[row[RoomStatuses.key]] ?: 0L,
                )
            },
            fields = fieldRows.map { row ->
                RoomFieldResponse(
                    key = row[RoomFields.key],
                    label = row[RoomFields.label],
                    type = FieldType.valueOf(row[RoomFields.type]),
                    required = row[RoomFields.required],
                    options = storedJson.decodeFromString(optionsSerializer, row[RoomFields.optionsJson]),
                )
            },
        )
    }
}
