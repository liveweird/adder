package ch.nokillswit

import ch.nokillswit.authz.CallerPrincipal
import ch.nokillswit.infra.presence.ForegroundPresence
import ch.nokillswit.notifications.NoticeWriter
import ch.nokillswit.notifications.NotificationService
import ch.nokillswit.rooms.RoomMemberService
import ch.nokillswit.rooms.RoomService
import ch.nokillswit.teams.TeamService
import ch.nokillswit.threads.CreatedThread
import ch.nokillswit.threads.MessageMentionService
import ch.nokillswit.threads.MessageRequest
import ch.nokillswit.threads.MessageService
import ch.nokillswit.threads.MessageTagService
import ch.nokillswit.threads.PinCleanup
import ch.nokillswit.threads.PinService
import ch.nokillswit.threads.ThreadCreateRequest
import ch.nokillswit.threads.ThreadEventService
import ch.nokillswit.threads.ThreadService
import ch.nokillswit.threads.ThreadTagService
import ch.nokillswit.threads.ThreadWatchService
import ch.nokillswit.workflows.FieldType
import ch.nokillswit.workflows.StatusCategory
import ch.nokillswit.workflows.StatusColour
import ch.nokillswit.workflows.WorkflowFieldDef
import ch.nokillswit.workflows.WorkflowOptionDef
import ch.nokillswit.workflows.WorkflowStatusDef
import ch.nokillswit.workflows.WorkflowTemplateRequest
import java.util.UUID
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.JsonElement
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.update

/**
 * Thread fixtures (0.3.0 S2, messages S5): direct, route-free seeding and raw reads of the thread aggregate, past the HTTP guards.
 * A thread is seeded through the REAL create (the service, so its opening message, `CREATED` event and activity bump exist); a state
 * the routes cannot reach yet (a closed thread, for the closed filter) is a raw update ([closeRaw]). Rooms own the cleanup: [TestRooms.purge]
 * cascades into threads, messages and events, and users are soft-deleted, so nothing here deletes.
 */
object TestThreads {
    val events: ThreadEventService by lazy { ThreadEventService(sharedDatabaseForTests()) }
    private val teams: TeamService by lazy { TeamService(sharedDatabaseForTests()) }
    private val members: RoomMemberService by lazy { RoomMemberService(sharedDatabaseForTests(), teams) }
    /** The notice writer (the `ThreadNotices` port) the real services share: a seeded mention makes its notice like a route's. */
    internal val noticeWriter: NoticeWriter by lazy { NoticeWriter() }
    /** The notice reads, "seen" writes and retention. */
    internal val notices: NotificationService by lazy { NotificationService(sharedDatabaseForTests(), TestRooms.service) }
    val messages: MessageService by lazy {
        val database = sharedDatabaseForTests()
        val mentions = MessageMentionService(members, noticeWriter, watches, teams, ForegroundPresence { emptySet() })
        MessageService(database, TestRooms.service, MessageTagService(database), mentions, listOf(PinCleanup()))
    }
    val pins: PinService by lazy { PinService(sharedDatabaseForTests(), messages) }
    /** The watches (V26): the same service the real thread writes add the creator's auto-watch through. */
    internal val watches: ThreadWatchService by lazy { ThreadWatchService(sharedDatabaseForTests(), members) }
    val service: ThreadService by lazy {
        val database = sharedDatabaseForTests()
        val tags = MessageTagService(database)
        ThreadService(
            database, TestRooms.service, TestRooms.workflows, members, events, messages, watches, teams, tags,
            ThreadTagService(database, events),
        )
    }

    /**
     * A small workflow template: `open` (initial) and `monitoring` (OPEN), `done` and `wontdo` (CLOSED);
     * a REQUIRED SELECT `severity` (`s1`, `s2`), and the optional TEXT
     * `note`, USER `lead`, DATE `due` and MULTI_SELECT `tags` (`a`, `b`). [name] must be unique.
     */
    private fun template(name: String) = WorkflowTemplateRequest(
        name = name,
        description = "A thread fixture template",
        statuses = listOf(
            WorkflowStatusDef("open", mapOf("en" to "Open"), StatusColour.GRAY, StatusCategory.OPEN, initial = true),
            WorkflowStatusDef("monitoring", mapOf("en" to "Monitoring"), StatusColour.BLUE, StatusCategory.OPEN, initial = false),
            WorkflowStatusDef("done", mapOf("en" to "Done"), StatusColour.TEAL, StatusCategory.CLOSED, initial = false),
            WorkflowStatusDef("wontdo", mapOf("en" to "Won't do"), StatusColour.GRAY, StatusCategory.CLOSED, initial = false),
        ),
        fields = listOf(
            WorkflowFieldDef(
                "severity", mapOf("en" to "Severity"), FieldType.SELECT, required = true,
                options = listOf(WorkflowOptionDef("s1", mapOf("en" to "Sev one")), WorkflowOptionDef("s2", mapOf("en" to "Sev two"))),
            ),
            WorkflowFieldDef("note", mapOf("en" to "Note"), FieldType.TEXT, required = false, options = emptyList()),
            WorkflowFieldDef("lead", mapOf("en" to "Lead"), FieldType.USER, required = false, options = emptyList()),
            WorkflowFieldDef("due", mapOf("en" to "Due"), FieldType.DATE, required = false, options = emptyList()),
            WorkflowFieldDef(
                "tags", mapOf("en" to "Tags"), FieldType.MULTI_SELECT, required = false,
                options = listOf(WorkflowOptionDef("a", mapOf("en" to "Tag A")), WorkflowOptionDef("b", mapOf("en" to "Tag B"))),
            ),
        ),
    )

    /** Seeds the fixture template (the caller deletes it with `TestTemplates.delete`). */
    suspend fun seedTemplate(prefix: String = "thr"): UInt =
        TestTemplates.seed(request = template("$prefix-${UUID.randomUUID().toString().substring(0, 8)}"))

    /** Starts a thread in [roomId] as [userId] (a member) through the real create; `severity` is supplied unless [fields] sets it. */
    suspend fun seed(
        roomId: UInt,
        userId: UInt,
        title: String = "Thread ${UUID.randomUUID().toString().substring(0, 8)}",
        body: String = "Opening message",
        fields: Map<String, JsonElement>? = null,
    ): CreatedThread {
        val caller = CallerPrincipal(userId, "fixture@test", emptySet())
        val values = fields ?: mapOf("severity" to kotlinx.serialization.json.JsonPrimitive("s1"))
        return service.create(roomId, caller, ThreadCreateRequest(title, body, values))
    }

    data class RawThread(
        val id: UInt,
        val roomId: UInt,
        val number: Int,
        val title: String,
        val statusKey: String,
        val ownerId: UInt,
        val createdBy: UInt,
        val fields: String,
        val revision: Long,
        val messageCount: Int,
        val closedAt: Long?,
        val lastActivityAt: Long,
        val createdAt: Long,
        val updatedAt: Long,
    )

    private fun ResultRow.toRaw(): RawThread {
        val t = ThreadService.Threads
        return RawThread(
            id = this[t.id].value, roomId = this[t.roomId].value, number = this[t.number], title = this[t.title],
            statusKey = this[t.statusKey], ownerId = this[t.ownerId].value, createdBy = this[t.createdBy].value,
            fields = this[t.fieldValues], revision = this[t.revision], messageCount = this[t.messageCount], closedAt = this[t.closedAt],
            lastActivityAt = this[t.lastActivityAt], createdAt = this[t.createdAt], updatedAt = this[t.updatedAt],
        )
    }

    /** The raw thread row, or null when the room has no such number. */
    suspend fun raw(roomId: UInt, number: Int): RawThread? = rawAll(roomId).firstOrNull { it.number == number }

    /** Every thread row of a room, by number. */
    suspend fun rawAll(roomId: UInt): List<RawThread> = suspendTransaction(sharedDatabaseForTests()) {
        val t = ThreadService.Threads
        t.selectAll().where { t.roomId eq roomId }.orderBy(t.number to SortOrder.ASC).map { it.toRaw() }.toList()
    }

    /** Closes a thread by raw update (moves it to [statusKey], a CLOSED status of its room, and stamps `closed_at` with [closedAt]). */
    suspend fun closeRaw(roomId: UInt, number: Int, statusKey: String = "done", closedAt: Long = System.currentTimeMillis()) {
        suspendTransaction(sharedDatabaseForTests()) {
            val t = ThreadService.Threads
            t.update({ (t.roomId eq roomId) and (t.number eq number) }) {
                it[t.statusKey] = statusKey
                it[t.closedAt] = closedAt
            }
        }
    }

    /** Moves a thread to [statusKey] by raw update, closing nothing (an OPEN status such as `monitoring`; [closeRaw] is the CLOSED one). */
    suspend fun moveRaw(roomId: UInt, number: Int, statusKey: String) {
        suspendTransaction(sharedDatabaseForTests()) {
            val t = ThreadService.Threads
            t.update({ (t.roomId eq roomId) and (t.number eq number) }) { it[t.statusKey] = statusKey }
        }
    }

    /**
     * Sets a thread's `created_at` and `last_activity_at` (and `updated_at`, when given) by raw update (distinct stamps for a sort test:
     * a loop shares a millisecond).
     */
    suspend fun setStamps(roomId: UInt, number: Int, createdAt: Long, lastActivityAt: Long, updatedAt: Long? = null) {
        suspendTransaction(sharedDatabaseForTests()) {
            val t = ThreadService.Threads
            t.update({ (t.roomId eq roomId) and (t.number eq number) }) {
                it[t.createdAt] = createdAt
                it[t.lastActivityAt] = lastActivityAt
                updatedAt?.let { stamp -> it[t.updatedAt] = stamp }
            }
        }
    }

    data class RawMessage(
        val id: UInt,
        val authorId: UInt,
        val body: String,
        val createdAt: Long,
        val deleted: Boolean,
        val editedAt: Long? = null,
        val deletedBy: UInt? = null,
    )

    /** Every message row of a thread, tombstones included, by id. */
    suspend fun rawMessages(threadId: UInt): List<RawMessage> = suspendTransaction(sharedDatabaseForTests()) {
        val m = MessageService.Messages
        m.selectAll().where { m.threadId eq threadId }.orderBy(m.id to SortOrder.ASC).map {
            RawMessage(
                it[m.id].value, it[m.authorId].value, it[m.body], it[m.createdAt], it[m.markedAsDeleted],
                it[m.editedAt], it[m.deletedBy]?.value,
            )
        }.toList()
    }

    /** Posts [body] to thread [number] of [roomId] as [userId] (a member) through the real post; returns the message id. */
    suspend fun post(roomId: UInt, number: Int, userId: UInt, body: String = "A reply"): UInt =
        messages.post(roomId, number, CallerPrincipal(userId, "fixture@test", emptySet()), MessageRequest(body)).message.id

    data class RawEvent(val id: UInt, val userId: UInt, val type: String, val params: String, val createdAt: Long)

    suspend fun rawEvents(threadId: UInt): List<RawEvent> = suspendTransaction(sharedDatabaseForTests()) {
        val e = ThreadEventService.ThreadEvents
        e.selectAll().where { e.ownerId eq threadId }.orderBy(e.id to SortOrder.ASC).map {
            RawEvent(it[e.id].value, it[e.userId].value, it[e.eventType], it[e.params], it[e.createdAt])
        }.toList()
    }

    /** The room's own `last_activity_at` (the rail order). */
    suspend fun roomActivity(roomId: UInt): Long = suspendTransaction(sharedDatabaseForTests()) {
        val r = RoomService.Rooms
        r.selectAll().where { r.id eq roomId }.map { it[r.lastActivityAt] }.toList().single()
    }

    /**
     * Holds the room lock (`FOR NO KEY UPDATE`) on a raw transaction that also runs [statement], when given, bound to [ids] (neither is
     * visible until [body] commits it): the lock-wait race shape. The transaction is rolled back at the end, a no-op after the commit.
     */
    suspend fun holdingRoomLock(
        roomId: UInt,
        statement: String? = null,
        vararg ids: UInt,
        body: suspend (java.sql.Connection) -> Unit,
    ) {
        PostgresTestSupport.connect().use { raw ->
            raw.autoCommit = false
            raw.prepareStatement("SELECT id FROM rooms WHERE id = ? FOR NO KEY UPDATE").use { st ->
                st.setInt(1, roomId.toInt())
                st.executeQuery().close()
            }
            statement?.let {
                raw.prepareStatement(it).use { st ->
                    ids.forEachIndexed { index, id -> st.setLong(index + 1, id.toLong()) }
                    st.executeUpdate()
                }
            }
            try {
                body(raw)
            } finally {
                raw.rollback() // a no-op after the commit; releases the lock if an assertion failed first
            }
        }
    }
}
