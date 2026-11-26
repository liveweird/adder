package ch.nokillswit.threads

import ch.nokillswit.authz.CallerPrincipal
import ch.nokillswit.authz.NotFoundException
import ch.nokillswit.authz.RoomAccessType
import ch.nokillswit.authz.RoomRole
import ch.nokillswit.authz.requireMessageRemover
import ch.nokillswit.infra.db.active
import ch.nokillswit.infra.paging.PageRequest
import ch.nokillswit.infra.paging.applyPaging
import ch.nokillswit.infra.paging.toPage
import ch.nokillswit.rooms.RoomService
import ch.nokillswit.rooms.auditAndRequireRoomReader
import ch.nokillswit.rooms.freshCaller
import ch.nokillswit.rooms.readStanding
import ch.nokillswit.users.UserRole
import ch.nokillswit.users.UserService
import io.ktor.util.AttributeKey
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.alias
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.dao.id.UIntIdTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.exists
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.inSubQuery
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.R2dbcTransaction
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.selectAll

val MessageRevisionServiceKey = AttributeKey<MessageRevisionService>("MessageRevisionService")

private val REVISION_SORTABLE_COLUMNS: Map<String, Column<*>> = mapOf("id" to MessageRevisionService.MessageRevisions.id)

/** The one sortable field of a message's saved-body list. */
val MESSAGE_REVISION_SORT_FIELDS: Set<String> = REVISION_SORTABLE_COLUMNS.keys

/**
 * Saved message bodies (V47). Writes run inside the owning message transaction; reads use the chat list limiter and expose no current
 * body. The rows are hard-deleted security history: removing the message or its thread physically scrubs every saved body.
 */
class MessageRevisionService(
    private val database: R2dbcDatabase,
    private val roomService: RoomService,
    private val readLimiter: ReadLimiter = ReadLimiter(),
) {
    object MessageRevisions : UIntIdTable("message_revisions") {
        val messageId = reference("message_id", MessageService.Messages)
        val body = varchar("body", MAX_MESSAGE_BODY_LENGTH)
        val createdAt = long("created_at")
        val supersededAt = long("superseded_at")
    }

    private suspend fun R2dbcTransaction.authorize(caller: CallerPrincipal, roomId: UInt, messageId: UInt, budget: SearchBudget) {
        budget.arm(this)
        val currentCaller = freshCaller(caller) ?: throw NotFoundException("Room not found")
        budget.arm(this)
        val standing = roomService.auditAndRequireRoomReader(currentCaller, roomId, readStanding(roomId, caller.userId, lock = false))
        val messages = MessageService.Messages
        val threads = ThreadService.Threads
        budget.arm(this)
        val authorId = messages.innerJoin(threads).select(messages.authorId)
            .where { (messages.id eq messageId) and (threads.roomId eq roomId) and messages.active() and threads.active() }
            .toList().singleOrNull()?.get(messages.authorId)?.value
            ?: throw NotFoundException("Message not found")
        requireMessageRemover(currentCaller, standing, authorId)
    }

    /** Authorize before parsing paging. Every query that can release historical data uses a current-access predicate. */
    suspend fun list(
        caller: CallerPrincipal,
        roomId: UInt,
        messageId: UInt,
        parsePaging: () -> PageRequest,
    ): MessageRevisionPageResponse =
        readLimiter.run(database, caller.userId, roomId, "revisions") { budget ->
            authorize(caller, roomId, messageId, budget)
            val paging = parsePaging()
            val predicate = (MessageRevisions.messageId eq messageId) and readableRevisionParent(caller.userId, roomId, messageId)
            budget.arm(this)
            readPage(paging, predicate)
        }

    /** An outer join retains the total for an empty page. Both subqueries share one database snapshot. */
    private suspend fun readPage(paging: PageRequest, predicate: Op<Boolean>): MessageRevisionPageResponse {
        val total = MessageRevisions.id.count().alias("total")
        val totals = MessageRevisions.select(total).where { predicate }.alias("totals")
        val page = MessageRevisions.selectAll().where { predicate }.applyPaging(paging, REVISION_SORTABLE_COLUMNS).alias("page")
        val id = page[MessageRevisions.id]
        val body = page[MessageRevisions.body]
        val createdAt = page[MessageRevisions.createdAt]
        val supersededAt = page[MessageRevisions.supersededAt]
        val order = if (paging.sort.single().descending) SortOrder.DESC else SortOrder.ASC
        val rows = totals.join(page, JoinType.LEFT, additionalConstraint = { Op.TRUE })
            .select(totals[total], id, body, createdAt, supersededAt)
            .orderBy(id to order).toList()
        val items = rows.mapNotNull { row ->
            row.getOrNull(id)?.let { MessageRevisionResponse(it.value, row[body], row[createdAt], row[supersededAt]) }
        }
        return paging.toPage(items, rows.first()[totals[total]])
    }

    /** Snapshot [body] immediately before the owning edit overwrites it. The caller already rejected a no-op. */
    internal suspend fun recordInTransaction(messageId: UInt, body: String, createdAt: Long, supersededAt: Long) {
        MessageRevisions.insert {
            it[MessageRevisions.messageId] = messageId
            it[MessageRevisions.body] = body
            it[MessageRevisions.createdAt] = createdAt
            it[MessageRevisions.supersededAt] = supersededAt
        }
    }

    /** Physically scrub one deleted message's saved bodies inside its write transaction. */
    internal suspend fun purgeMessageInTransaction(messageId: UInt) {
        MessageRevisions.deleteWhere { MessageRevisions.messageId eq messageId }
    }

    /** Physically scrub every saved body of the deleted canonical thread and its merged sources. */
    internal suspend fun purgeThreadsInTransaction(threadIds: Collection<UInt>) {
        if (threadIds.isEmpty()) return
        val messageIds = MessageService.Messages.select(MessageService.Messages.id)
            .where { MessageService.Messages.threadId inList threadIds.toSet() }
        MessageRevisions.deleteWhere { MessageRevisions.messageId inSubQuery messageIds }
    }
}

/** Re-evaluated in each count/page statement, after any wait for query permits or a concurrent change of room access. */
private fun readableRevisionParent(userId: UInt, roomId: UInt, messageId: UInt): Op<Boolean> {
    val messages = MessageService.Messages
    val threads = ThreadService.Threads
    val rooms = RoomService.Rooms
    val members = RoomService.RoomMembers
    val users = UserService.Users
    val membership = (members.roomId eq roomId) and (members.userId eq userId)
    val memberModerator = exists(members.select(members.roomId).where {
        membership and ((members.role eq RoomRole.OWNER.name) or (messages.authorId eq userId))
    })
    val roomVisibleToAdmin = (rooms.accessType neq RoomAccessType.RESTRICTED_HIDDEN.name) or
        exists(members.select(members.roomId).where { membership })
    val authorizedUser = exists(users.select(users.id).where {
        (users.id eq userId) and users.active() and (memberModerator or ((users.role eq UserRole.ADMIN.name) and roomVisibleToAdmin))
    })
    return exists(messages.innerJoin(threads).innerJoin(rooms).select(messages.id).where {
        (messages.id eq messageId) and (threads.roomId eq roomId) and messages.active() and threads.active() and rooms.active() and
            authorizedUser
    })
}
