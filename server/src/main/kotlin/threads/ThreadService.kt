package ch.nokillswit.threads

import ch.nokillswit.authz.CallerPrincipal
import ch.nokillswit.infra.db.SoftDeletable
import ch.nokillswit.infra.db.jsonb
import ch.nokillswit.infra.db.nowMillis
import ch.nokillswit.infra.json.canonicalJson
import ch.nokillswit.infra.notify.LiveHint
import ch.nokillswit.infra.notify.LiveKind
import ch.nokillswit.infra.notify.notifyLive
import ch.nokillswit.infra.paging.PageRequest
import ch.nokillswit.infra.paging.applyPaging
import ch.nokillswit.infra.revision.requireRevision
import ch.nokillswit.rooms.RoomMemberService
import ch.nokillswit.rooms.RoomService
import ch.nokillswit.rooms.RoomWorkflowService
import ch.nokillswit.rooms.RoomWorkflowSnapshot
import ch.nokillswit.rooms.memberGuard
import ch.nokillswit.rooms.readerGuard
import ch.nokillswit.rooms.roomArchived
import ch.nokillswit.rooms.withRoomWrite
import ch.nokillswit.teams.TeamService
import ch.nokillswit.users.UserService
import ch.nokillswit.workflows.FieldType
import io.ktor.util.AttributeKey
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.dao.id.UIntIdTable
import org.jetbrains.exposed.v1.r2dbc.*
import org.jetbrains.exposed.v1.r2dbc.R2dbcTransaction
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

val ThreadServiceKey = AttributeKey<ThreadService>("ThreadService")

private val SORTABLE_COLUMNS: Map<String, Column<*>> = mapOf(
    "id" to ThreadService.Threads.id,
    "number" to ThreadService.Threads.number,
    "title" to ThreadService.Threads.title,
    "lastActivityAt" to ThreadService.Threads.lastActivityAt,
    "createdAt" to ThreadService.Threads.createdAt,
)

/** The columns of a list row ([ThreadListItem]); `fields` is deliberately absent (`ThreadService.list`). */
private val LIST_COLUMNS: List<Column<*>> = with(ThreadService.Threads) {
    listOf(id, number, title, statusKey, closedAt, ownerId, messageCount, lastActivityAt, createdAt)
}

/**
 * The ONE sortable whitelist of the thread list - derived from the column map so the two can never drift, plus [STATUS_SORT], the
 * computed workflow order that [ThreadService.list] adds to the map for the one request that sorts by it.
 */
val THREAD_SORT_FIELDS: Set<String> = SORTABLE_COLUMNS.keys + STATUS_SORT

/**
 * The thread aggregate's record (V14 `threads`, never deleted in 0.3): list, read, create (with its opening message and its
 * `CREATED` event) and the details edit; the status and owner writes are `ThreadStatusService`. Every write goes through `withRoomWrite`
 * (`rooms/RoomLock.kt`, via `withThreadWrite`): the room row lock is the thread lock
 * (`chat-model.md` "Guard order and locking"). Cross-feature reads: names come straight from `users`, and room data only through the
 * `rooms/` services ([RoomWorkflowService.snapshot], [RoomMemberService.activeMemberIds], [RoomService.touchActivity]); the list is in
 * `persistence.md`. The caller's own watch of a thread is [ThreadWatchService]'s: this service reads it for the list and the thread
 * and writes the creator's auto-watch inside [create].
 */
class ThreadService(
    private val database: R2dbcDatabase,
    private val rooms: RoomService,
    private val workflows: RoomWorkflowService,
    private val members: RoomMemberService,
    private val events: ThreadEventService,
    private val messages: MessageService,
    private val watches: ThreadWatchService,
    private val teams: TeamService,
    private val tags: MessageTagService,
    private val threadTags: ThreadTagService,
    private val readLimiters: ThreadReadLimiters = ThreadReadLimiters(),
) {
    object Threads : UIntIdTable("threads"), SoftDeletable {
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
        override val markedAsDeleted = bool("marked_as_deleted").default(false)
        val mergedIntoThreadNumber = integer("merged_into_thread_number").nullable()
    }

    /**
     * One page of the room's threads: one predicate ([predicate]) for the count and the rows, in one transaction. The rows select only
     * the columns a [ThreadListItem] shows: never `fields`, the jsonb document that can be tens of kilobytes per thread (page size 100
     * would otherwise load megabytes the list never reads). The room's workflow snapshot is read only when a `field` filter or
     * `sort=status` needs it, so the plain list keeps its two queries. The whole read runs through the [ThreadReadLimiters.lists]:
     * ONE deadline (`threads.readTimeoutSeconds`, past it the audited `429` `read-timeout`) and the in-flight caps (`read-busy`).
     */
    suspend fun list(callerUserId: UInt, roomId: UInt, filter: ThreadListFilter, paging: PageRequest): ThreadListResult =
        readLimiters.lists.run(database, callerUserId, roomId, "threads") { budget ->
            val sortsByStatus = paging.sort.any { it.name == STATUS_SORT }
            budget.arm(this)
            val workflow = if (filter.needsWorkflow || sortsByStatus) workflows.snapshot(roomId) else null
            // The caller's floor of unread counting, read ONCE: null for someone who is no member (an ADMIN outside the room has no
            // read state). The `unread` filter, the `mentionsMe` filter (a group mention addresses members alone) and the counts share it.
            budget.arm(this)
            val floor = members.unreadSince(roomId, callerUserId)
            val addressable = filter.mentionsMe != null && floor != null
            budget.arm(this)
            val callerTeams = if (addressable) teams.teamIdsOf(callerUserId) else emptyList()
            // The `threadTag` names are resolved to registry ids (retired ones too) in this transaction; an unknown name has no id.
            budget.arm(this)
            val threadTagIds = threadTags.idsOf(filter.threadTags)
            val predicate = filter.predicate(roomId, callerUserId, workflow, addressable, callerTeams, threadTagIds, floor)
            val columns: Map<String, Expression<*>> = workflow?.takeIf { sortsByStatus }
                ?.let { SORTABLE_COLUMNS + (STATUS_SORT to statusOrder(it)) } ?: SORTABLE_COLUMNS
            budget.arm(this)
            val total = Threads.selectAll().where { predicate }.count()
            budget.arm(this)
            val rows = Threads.select(LIST_COLUMNS).where { predicate }.applyPaging(paging, columns).toList()
            budget.arm(this)
            val names = userNames(rows.map { it[Threads.ownerId].value })
            budget.arm(this)
            // A caller who is no member (an ADMIN outside the room) has no read state: every count is 0.
            val unread = floor?.let { unreadCountsOf(callerUserId, it, rows.map { row -> row[Threads.id].value }) }.orEmpty()
            budget.arm(this)
            val watching = watches.watchingAmong(roomId, callerUserId, rows.map { row -> row[Threads.id].value })
            budget.arm(this)
            val hashtags = tags.hashtagsOf(rows.map { row -> row[Threads.id].value })
            budget.arm(this)
            val curated = threadTags.forThreads(rows.map { row -> row[Threads.id].value })
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
                        unreadCount = unread[it[Threads.id].value] ?: 0,
                        watching = it[Threads.id].value in watching,
                        hashtags = hashtags[it[Threads.id].value].orEmpty(),
                        tags = curated[it[Threads.id].value].orEmpty(),
                    )
                },
                total = total,
            )
        }

    /**
     * The thread [number] of room [roomId] as its ACTIVE fields show it to [callerUserId] (the unread count and the first unread message
     * are THEIRS, 0 and null for someone who is no member), or null when there is none. Runs through the [ThreadReadLimiters.detail]
     * limiter (resource `thread`): ONE deadline for its statements (`threads.readTimeoutSeconds`, past it the audited `429`
     * `read-timeout`) and the in-flight caps (`read-busy`), so a pile of messages, hashtags or watchers can never hold a pooled
     * connection for long. It is NOT in the `chat-read` bucket (a live hint re-reads the open thread within the settle time, unspaced,
     * so a busy thread would spend the bucket the lists live on, `security.md`), and for the same reason its permits are its OWN, not
     * the lists': an unbucketed loop of detail reads can fill its own small pool and no more.
     */
    suspend fun read(roomId: UInt, number: Int, callerUserId: UInt): ThreadResponse? =
        readLimiters.detail.run(database, callerUserId, roomId, "thread") { budget -> readThread(roomId, number, callerUserId, budget) }

    /**
     * The thread a create just wrote, as [read] shows it, WITHOUT the limiter: the write has already committed, so its `201` must not
     * turn into a `429` (the thread has one message and no history, the cost is a constant).
     */
    suspend fun readCreated(roomId: UInt, number: Int, callerUserId: UInt): ThreadResponse? =
        suspendTransaction(database) { readThread(roomId, number, callerUserId, null) }

    /** The statements of [read], INSIDE the caller's transaction, armed with [budget] before each when there is one. */
    private suspend fun R2dbcTransaction.readThread(roomId: UInt, number: Int, callerUserId: UInt, budget: SearchBudget?): ThreadResponse? {
        budget?.arm(this)
        val row = Threads.selectAll().where { threadAddress(roomId, number) }.toList().singleOrNull()
            ?: return null
        budget?.arm(this)
        val activeTypes = workflows.activeFieldTypes(roomId)
        val values = storedFields(row[Threads.fieldValues]).filterKeys { it in activeTypes }
        val userFieldKeys = activeTypes.filterValues { it == FieldType.USER }.keys
        val fieldUserIds = userFieldKeys.mapNotNull { (values[it] as? JsonPrimitive)?.longOrNull?.toUInt() }
        budget?.arm(this)
        val names = userNames(listOf(row[Threads.ownerId].value, row[Threads.createdBy].value) + fieldUserIds)
        val threadId = row[Threads.id].value
        budget?.arm(this)
        val floor = members.unreadSince(roomId, callerUserId)
        budget?.arm(this)
        val unread = floor?.let { unreadOfThread(callerUserId, threadId, it) }
        budget?.arm(this)
        val watching = watches.watchingAmong(roomId, callerUserId, listOf(threadId))
        budget?.arm(this)
        val watcherCount = watches.count(threadId)
        budget?.arm(this)
        val muted = floor != null && isThreadMuted(threadId, callerUserId)
        budget?.arm(this)
        val hashtags = tags.hashtagsOf(listOf(threadId))[threadId].orEmpty()
        budget?.arm(this)
        val curated = threadTags.forThreads(listOf(threadId))[threadId].orEmpty()
        budget?.arm(this)
        val mergedSourceCount = Threads.selectAll().where { mergedSources(roomId, number) }.count()
        return ThreadResponse(
            id = threadId,
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
            unreadCount = unread?.count ?: 0,
            firstUnreadMessageId = unread?.firstMessageId,
            watching = threadId in watching,
            watcherCount = watcherCount,
            muted = muted,
            hashtags = hashtags,
            tags = curated,
            mergedIntoThreadNumber = row[Threads.mergedIntoThreadNumber],
            mergedSourceCount = mergedSourceCount,
        )
    }

    /** The row id of thread [number] of room [roomId], or null: the events route resolves its thread with it. */
    suspend fun idOf(roomId: UInt, number: Int): UInt? = suspendTransaction(database) {
        Threads.select(Threads.id).where { threadAddress(roomId, number) }
            .toList().singleOrNull()?.get(Threads.id)?.value
    }

    /**
     * One page of thread [threadId]'s history: the events with the values of the room's RETIRED fields left out (a retired field's values
     * are hidden everywhere, `chat-model.md` "Stored values survive a workflow edit"). The retired keys are read in a short
     * transaction of their own just before the events' (a field revived in between is shown or hidden by the one read or the other).
     */
    suspend fun history(roomId: UInt, threadId: UInt, paging: PageRequest, createdAt: LongRange? = null): ThreadEventListResult {
        val retired = suspendTransaction(database) { workflows.retiredFieldKeys(roomId) }
        return events.list(threadId, paging, retired, createdAt)
    }

    /**
     * The thread's owner id read WITHOUT a lock, or null when there is no such thread: the 403 pre-check of the steward guard (a missing
     * thread is `404` there) that the write repeats on the fresh row under the room lock.
     */
    suspend fun ownerOf(roomId: UInt, number: Int): UInt? = suspendTransaction(database) {
        Threads.select(Threads.ownerId).where { threadAddress(roomId, number) }
            .toList().singleOrNull()?.get(Threads.ownerId)?.value
    }

    /**
     * Starts a thread in ONE room write, in this order (`chat-model.md` "Guard order and locking"): the member guard ([memberGuard]) on the
     * re-read caller and standing, an archived room is the typed `409`, the room's workflow snapshot gives the
     * initial status and the field definitions, the kernel validates the field values (a `400`, required fields at create), the
     * create's live registry tags, the next per-room number, the thread (`message_count` = 1), the creator's watch, its opening message,
     * the `CREATED` event, an optional `TAGS_CHANGED` event, and the activity bump of the thread and the room. Any throw rolls everything
     * back; initial tags do not advance the thread beyond revision 1.
     */
    suspend fun create(roomId: UInt, caller: CallerPrincipal, raw: ThreadCreateRequest): CreatedThread {
        // Sanitised AND validated service-side (both idempotent after the route): a direct caller can never hand a control character on.
        val request = sanitizedThreadCreate(raw)
        validateThreadCreate(request)
        val hashtags = parseHashtags(request.body) // before the room lock
        return database.withRoomWrite(roomId, caller, memberGuard) { _, standing ->
            if (standing.archived) throw roomArchived()
            val snapshot = workflows.snapshot(roomId)
            val initial = snapshot.statuses.single { it.initial }
            val document = storedDocument(validatedFields(roomId, snapshot, emptyMap(), request.fields, creating = true).merged)
            // Resolve the whole live set before allocating a number or inserting the thread; associations still commit with the create.
            val initialTags = threadTags.requireLiveTagIds(request.tags)
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
                it[fieldValues] = document
                it[messageCount] = 1
                it[lastActivityAt] = stamp
                it[createdAt] = stamp
                it[updatedAt] = stamp
            }[Threads.id].value
            // The creator watches their own thread (1.1 D1), under the room lock the member guard ran under: they cannot have left.
            watches.addInTransaction(threadId, caller.userId, stamp)
            val opening = messages.insertInTransaction(
                roomId, threadId, number, caller.userId, request.body, hashtags, stamp, opening = true,
            )
            events.record(threadId, caller.userId, threadCreatedEvent(initial.key, initial.label), stamp)
            threadTags.addInitialInTransaction(threadId, caller.userId, initialTags, stamp)
            rooms.touchActivity(roomId, stamp, caller.userId)
            // The opening message is part of this hint, never a `message.posted` of its own.
            notifyLive(LiveHint(LiveKind.THREAD_CREATED, roomId, threadNumber = number))
            // The followers run after the hint, so a notice's own hint follows `thread.created` on the wire.
            CreatedThread(threadId, number, opening.id, messages.followPosted(opening))
        }
    }

    /**
     * Edits the title and the field values under `X-Expected-Revision` in ONE thread write ([withThreadWrite], the room guard is the
     * reader's): an archived room is `409 room-archived`, a closed thread `409 thread-closed`, a stale [expectedRevision] `409
     * revision-conflict` (the thread row is read under the room lock, so the compare is race-free), then the kernel judges the
     * values against the workflow and members as they are NOW (`400`). Nothing changed answers null and writes nothing (no revision
     * bump, no event, no activity). Otherwise the revision is bumped, one `TITLE_CHANGED` and one `FIELD_CHANGED` per changed field
     * are recorded and the thread and the room are bumped, all stamped with ONE instant.
     */
    suspend fun updateDetails(
        roomId: UInt,
        number: Int,
        caller: CallerPrincipal,
        expectedRevision: Long,
        raw: ThreadUpdateRequest,
        mutationId: String? = null,
    ): ThreadChange? {
        val request = sanitizedThreadUpdate(raw)
        validateThreadUpdate(request)
        return database.withThreadWrite(roomId, number, caller, readerGuard) { write ->
            write.requireRoomActive()
            write.requireThreadOpen()
            val thread = write.thread
            requireRevision(expectedRevision, thread.revision)
            val snapshot = workflows.snapshot(roomId)
            val fieldWrite = validatedFields(roomId, snapshot, thread.fields, request.fields, creating = false)
            val titleChanged = request.title != thread.title
            if (!titleChanged && fieldWrite.changes.isEmpty()) return@withThreadWrite null
            val document = storedDocument(fieldWrite.merged)
            val stamp = nowMillis()
            val revision = thread.revision + 1
            Threads.update({ Threads.id eq thread.id }) {
                it[title] = request.title
                it[fieldValues] = document
                it[Threads.revision] = revision
                it[lastActivityAt] = stamp
                it[updatedAt] = stamp
            }
            if (titleChanged) events.record(thread.id, caller.userId, threadTitleChangedEvent(), stamp)
            val names = userNames(userIdsOfChanges(snapshot.fields, fieldWrite.changes))
            fieldWrite.changes.forEach { change ->
                val field = snapshot.fields.single { it.key == change.key }
                events.record(thread.id, caller.userId, threadFieldChangedEvent(field, change, names), stamp)
            }
            rooms.touchActivity(roomId, stamp, caller.userId)
            notifyLive(
                LiveHint(
                    LiveKind.THREAD_UPDATED,
                    roomId,
                    threadNumber = number,
                    mutationId = mutationId,
                    originUserId = caller.userId.takeIf { mutationId != null },
                ),
            )
            ThreadChange(thread.id, revision, titleChanged, fieldWrite.changes.map { it.key })
        }
    }

    /** The kernel's verdict on [incoming] over the [current] values, against the definitions and members of [roomId] as they are NOW. */
    private suspend fun validatedFields(
        roomId: UInt,
        snapshot: RoomWorkflowSnapshot,
        current: Map<String, JsonElement>,
        incoming: Map<String, JsonElement>,
        creating: Boolean,
    ): FieldWrite {
        val definitions = snapshot.fields.map {
            FieldDefinition(it.key, it.type, it.required, it.options.map { option -> option.key }, it.retired)
        }
        val memberIds = members.activeMemberIds(roomId, incomingUserIds(definitions, incoming))
        return applyFieldValues(definitions, current, incoming, memberIds, creating)
    }

    /** The canonical text of the merged `fields` document, within [MAX_FIELDS_DOCUMENT_LENGTH] (a fixed-detail `400` beyond it). */
    private fun storedDocument(merged: Map<String, JsonElement>): String =
        canonicalJson(JsonObject(merged)).also(::requireFieldsDocumentSize)

    private fun storedFields(json: String): Map<String, JsonElement> = (Json.parseToJsonElement(json) as JsonObject)
}
