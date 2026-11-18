package ch.nokillswit.threads

import ch.nokillswit.authz.CallerPrincipal
import ch.nokillswit.infra.db.jsonb
import ch.nokillswit.infra.db.nowMillis
import ch.nokillswit.infra.json.canonicalJson
import ch.nokillswit.infra.paging.PageRequest
import ch.nokillswit.infra.paging.applyPaging
import ch.nokillswit.rooms.RoomMemberService
import ch.nokillswit.rooms.RoomService
import ch.nokillswit.rooms.RoomWorkflowService
import ch.nokillswit.rooms.RoomWorkflowSnapshot
import ch.nokillswit.rooms.memberGuard
import ch.nokillswit.rooms.roomArchived
import ch.nokillswit.rooms.withRoomWrite
import ch.nokillswit.users.UserService
import ch.nokillswit.workflows.FieldType
import io.ktor.util.AttributeKey
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.dao.id.UIntIdTable
import org.jetbrains.exposed.v1.r2dbc.*
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

val ThreadServiceKey = AttributeKey<ThreadService>("ThreadService")

private val SORTABLE_COLUMNS: Map<String, Column<*>> = mapOf(
    "id" to ThreadService.Threads.id,
    "number" to ThreadService.Threads.number,
    "title" to ThreadService.Threads.title,
    "lastActivityAt" to ThreadService.Threads.lastActivityAt,
    "createdAt" to ThreadService.Threads.createdAt,
)

/** The ONE sortable whitelist of the thread list - derived from the column map so the two can never drift. */
val THREAD_SORT_FIELDS: Set<String> = SORTABLE_COLUMNS.keys

/**
 * The thread aggregate's record (V14 `threads`, never deleted in 0.3): list, read and create (with its opening message and its
 * `CREATED` event). Every write goes through `withRoomWrite` (`rooms/RoomLock.kt`): the room row lock is the thread lock
 * (`chat-model.md` "Guard order and locking"). Cross-feature reads: names come straight from `users`, and room data only through the
 * `rooms/` services ([RoomWorkflowService.snapshot], [RoomMemberService.activeMemberIds], [RoomService.touchActivity]); the list is in
 * `persistence.md`.
 */
class ThreadService(
    private val database: R2dbcDatabase,
    private val rooms: RoomService,
    private val workflows: RoomWorkflowService,
    private val members: RoomMemberService,
    private val events: ThreadEventService,
    private val messages: MessageService,
) {
    object Threads : UIntIdTable("threads") {
        val roomId = reference("room_id", RoomService.Rooms)

        /** The per-room number (`#42`): `MAX + 1` under the room lock, never reused. */
        val number = integer("number")
        val title = varchar("title", length = MAX_THREAD_TITLE_LENGTH)
        val statusKey = varchar("status_key", length = 40)
        val ownerId = reference("owner_id", UserService.Users)
        val createdBy = reference("created_by", UserService.Users)

        /** The `fields` column: a JSON object of field key to stored value (canonical JSON); a retired field keeps its value. */
        val fieldValues = jsonb("fields")
        val revision = long("revision").default(1L)
        val messageCount = integer("message_count").default(0)

        /** Mirrors the status category: set on a move into a CLOSED status, cleared on a move out. */
        val closedAt = long("closed_at").nullable()
        val lastActivityAt = long("last_activity_at")
        val createdAt = long("created_at")
        val updatedAt = long("updated_at")
    }

    /** One page of the room's threads: one predicate for the count and the rows, in one transaction. */
    suspend fun list(roomId: UInt, closed: Boolean?, paging: PageRequest): ThreadListResult = suspendTransaction(database) {
        var predicate: Op<Boolean> = Threads.roomId eq roomId
        closed?.let { predicate = predicate and (if (it) Threads.closedAt.isNotNull() else Threads.closedAt.isNull()) }
        val total = Threads.selectAll().where { predicate }.count()
        val rows = Threads.selectAll().where { predicate }.applyPaging(paging, SORTABLE_COLUMNS).toList()
        val names = userNames(rows.map { it[Threads.ownerId].value })
        ThreadListResult(
            items = rows.map {
                ThreadListItem(
                    id = it[Threads.id].value,
                    number = it[Threads.number],
                    title = it[Threads.title],
                    status = it[Threads.statusKey],
                    closed = it[Threads.closedAt] != null,
                    ownerId = it[Threads.ownerId].value,
                    ownerName = names.getValue(it[Threads.ownerId].value),
                    messageCount = it[Threads.messageCount],
                    lastActivityAt = it[Threads.lastActivityAt],
                    createdAt = it[Threads.createdAt],
                )
            },
            total = total,
        )
    }

    /** The thread [number] of room [roomId] as its ACTIVE fields show it, or null when there is none. */
    suspend fun read(roomId: UInt, number: Int): ThreadResponse? = suspendTransaction(database) {
        val row = Threads.selectAll().where { (Threads.roomId eq roomId) and (Threads.number eq number) }.toList().singleOrNull()
            ?: return@suspendTransaction null
        val activeTypes = workflows.activeFieldTypes(roomId)
        val values = storedFields(row[Threads.fieldValues]).filterKeys { it in activeTypes }
        val userFieldKeys = activeTypes.filterValues { it == FieldType.USER }.keys
        val fieldUserIds = userFieldKeys.mapNotNull { (values[it] as? JsonPrimitive)?.longOrNull?.toUInt() }
        val names = userNames(listOf(row[Threads.ownerId].value, row[Threads.createdBy].value) + fieldUserIds)
        ThreadResponse(
            id = row[Threads.id].value,
            number = number,
            title = row[Threads.title],
            status = row[Threads.statusKey],
            closed = row[Threads.closedAt] != null,
            closedAt = row[Threads.closedAt],
            ownerId = row[Threads.ownerId].value,
            ownerName = names.getValue(row[Threads.ownerId].value),
            createdById = row[Threads.createdBy].value,
            createdByName = names.getValue(row[Threads.createdBy].value),
            fields = values,
            fieldUserNames = fieldUserIds.associate { it.toString() to names.getValue(it) },
            messageCount = row[Threads.messageCount],
            revision = row[Threads.revision],
            lastActivityAt = row[Threads.lastActivityAt],
            createdAt = row[Threads.createdAt],
            updatedAt = row[Threads.updatedAt],
        )
    }

    /** The row id of thread [number] of room [roomId], or null: the events route resolves its thread with it. */
    suspend fun idOf(roomId: UInt, number: Int): UInt? = suspendTransaction(database) {
        Threads.select(Threads.id).where { (Threads.roomId eq roomId) and (Threads.number eq number) }
            .toList().singleOrNull()?.get(Threads.id)?.value
    }

    /**
     * The thread's owner id read WITHOUT a lock, or null when there is no such thread: the 403 pre-check of the steward guard (a missing
     * thread is `404` there) that the write repeats on the fresh row under the room lock.
     */
    suspend fun ownerOf(roomId: UInt, number: Int): UInt? = suspendTransaction(database) {
        Threads.select(Threads.ownerId).where { (Threads.roomId eq roomId) and (Threads.number eq number) }
            .toList().singleOrNull()?.get(Threads.ownerId)?.value
    }

    /**
     * Starts a thread in ONE room write, in this order (`chat-model.md` "Guard order and locking"): the member guard ([memberGuard]) on the
     * re-read caller and standing, an archived room is the typed `409`, the room's workflow snapshot gives the
     * initial status and the field definitions, the kernel validates the field values (a `400`, required fields at create), the
     * next per-room number, the thread (`message_count` = 1), its opening message, the `CREATED` event and the activity bump of the
     * thread and the room. Any throw rolls everything back.
     */
    suspend fun create(roomId: UInt, caller: CallerPrincipal, raw: ThreadCreateRequest): CreatedThread {
        // Sanitised AND validated service-side (both idempotent after the route): a direct caller can never hand a control character on.
        val request = sanitizedThreadCreate(raw)
        validateThreadCreate(request)
        return database.withRoomWrite(roomId, caller, memberGuard) { standing ->
            if (standing.archived) throw roomArchived()
            val snapshot = workflows.snapshot(roomId)
            val initial = snapshot.statuses.single { it.initial }
            val write = validatedFields(roomId, snapshot, request.fields)
            val maxNumber = Threads.number.max()
            val number = (Threads.select(maxNumber).where { Threads.roomId eq roomId }.toList().single()[maxNumber] ?: 0) + 1
            val stamp = nowMillis()
            val threadId = Threads.insert {
                it[Threads.roomId] = roomId
                it[Threads.number] = number
                it[title] = request.title
                it[statusKey] = initial.key
                it[ownerId] = caller.userId
                it[createdBy] = caller.userId
                it[fieldValues] = canonicalJson(JsonObject(write))
                it[messageCount] = 1
                it[lastActivityAt] = stamp
                it[createdAt] = stamp
                it[updatedAt] = stamp
            }[Threads.id].value
            val messageId = messages.insertInTransaction(threadId, caller.userId, request.body, stamp)
            events.record(threadId, caller.userId, threadCreatedEvent(request.title, initial.key, initial.label), stamp)
            rooms.touchActivity(roomId, stamp)
            CreatedThread(threadId, number, messageId)
        }
    }

    /** The kernel's verdict on a create's field values, against the definitions and the members of [roomId] as they are NOW. */
    private suspend fun validatedFields(
        roomId: UInt,
        snapshot: RoomWorkflowSnapshot,
        incoming: Map<String, JsonElement>,
    ): Map<String, JsonElement> {
        val definitions = snapshot.fields.map {
            FieldDefinition(it.key, it.type, it.required, it.options.map { option -> option.key }, it.retired)
        }
        val memberIds = members.activeMemberIds(roomId, incomingUserIds(definitions, incoming))
        return applyFieldValues(definitions, emptyMap(), incoming, memberIds, creating = true).merged
    }

    /** Display names by user id, soft-deleted accounts included (their recorded name is still the name). One query, empty in, no query. */
    private suspend fun userNames(ids: Collection<UInt>): Map<UInt, String> {
        if (ids.isEmpty()) return emptyMap()
        val users = UserService.Users
        return users.select(users.id, users.name).where { users.id inList ids.toSet() }
            .map { it[users.id].value to it[users.name] }.toList().toMap()
    }

    private fun storedFields(json: String): Map<String, JsonElement> = (Json.parseToJsonElement(json) as JsonObject)
}
