package ch.nokillswit.rooms

import ch.nokillswit.infra.db.active
import ch.nokillswit.infra.db.jsonb
import ch.nokillswit.infra.json.canonicalJson
import ch.nokillswit.workflows.FieldType
import ch.nokillswit.workflows.MAX_KEY_LENGTH
import ch.nokillswit.workflows.MAX_LABEL_LENGTH
import ch.nokillswit.workflows.StatusCategory
import ch.nokillswit.workflows.StatusColour
import ch.nokillswit.workflows.WorkflowDefinition
import io.ktor.util.AttributeKey
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.r2dbc.*
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

val RoomWorkflowServiceKey = AttributeKey<RoomWorkflowService>("RoomWorkflowService")

private val storedJson = Json { ignoreUnknownKeys = true }
private val optionsSerializer = ListSerializer(RoomOptionDef.serializer())

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
     * The room's ACTIVE statuses and fields in position order with the workflow revision, or null when the room is missing or deleted
     * (the route runs its visibility guard first). One transaction: the revision, the rows and the usage counts are one consistent read.
     * The per-status thread counts come from [usage] (0.2: always 0).
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
