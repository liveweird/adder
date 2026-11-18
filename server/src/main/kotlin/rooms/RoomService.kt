package ch.nokillswit.rooms

import ch.nokillswit.authz.CallerPrincipal
import ch.nokillswit.authz.RoomStanding
import ch.nokillswit.authz.RoomRole
import ch.nokillswit.infra.db.SoftDeletable
import ch.nokillswit.infra.db.active
import ch.nokillswit.infra.db.containsNormalized
import ch.nokillswit.infra.db.nowMillis
import ch.nokillswit.infra.paging.PageRequest
import ch.nokillswit.infra.paging.applyPaging
import ch.nokillswit.users.DEFAULT_LANGUAGE
import ch.nokillswit.users.UserService
import ch.nokillswit.workflows.WorkflowDefinition
import ch.nokillswit.workflows.WorkflowTemplateService
import io.ktor.server.plugins.BadRequestException
import io.ktor.util.AttributeKey
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.dao.id.UIntIdTable
import org.jetbrains.exposed.v1.r2dbc.*
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

val RoomServiceKey = AttributeKey<RoomService>("RoomService")

private val SORTABLE_COLUMNS: Map<String, Column<*>> = mapOf(
    "id" to RoomService.Rooms.id,
    "name" to RoomService.Rooms.name,
    "lastActivityAt" to RoomService.Rooms.lastActivityAt,
    "createdAt" to RoomService.Rooms.createdAt,
)

/** The ONE sortable whitelist - derived from the column map so the two can never drift. */
val ROOM_SORT_FIELDS: Set<String> = SORTABLE_COLUMNS.keys

/**
 * The room record and its membership (V13). Every write that changes a room goes through `withRoomWrite` (`RoomLock.kt`); the
 * cross-feature reads (the active-user join of every member count, the template read at creation) are listed in `persistence.md`.
 */
class RoomService(
    private val database: R2dbcDatabase,
    private val templates: WorkflowTemplateService,
    private val workflows: RoomWorkflowService,
) {
    object Rooms : UIntIdTable("rooms"), SoftDeletable {
        // Case-folded name uniqueness rides the partial unique index uq_rooms_name_active (V13, non-deleted rooms); Exposed defs are
        // query-only, so no `.uniqueIndex()` here.
        val name = varchar("name", length = MAX_ROOM_NAME_LENGTH)
        val description = varchar("description", length = MAX_ROOM_DESCRIPTION_LENGTH).nullable()

        /** Null = active; the epoch-millis moment it was archived otherwise. */
        val archivedAt = long("archived_at").nullable()

        /** The room workflow's optimistic-concurrency token (V13), bumped by every successful workflow PUT. */
        val workflowRevision = long("workflow_revision").default(1L)
        val lastActivityAt = long("last_activity_at")
        val createdAt = long("created_at")
        val updatedAt = long("updated_at")
        override val markedAsDeleted = bool("marked_as_deleted").default(false)
    }

    /** The membership join (V13, hard-delete). A soft-deleted user keeps an inert row; every read resolves members through ACTIVE users. */
    object RoomMembers : Table("room_members") {
        val roomId = reference("room_id", Rooms)
        val userId = reference("user_id", UserService.Users)
        val role = varchar("role", length = 10)
        val addedAt = long("added_at")
        override val primaryKey = PrimaryKey(roomId, userId)
    }

    /** The caller's standing in an active room (null = no such room): the route's visibility preamble, no lock. */
    suspend fun standing(roomId: UInt, userId: UInt): RoomStanding? = suspendTransaction(database) {
        readStanding(roomId, userId, lock = false)
    }

    /**
     * The rooms [callerUserId] may read, filtered and paged: a member's own rooms, plus every room for an ADMIN ([callerIsAdmin]).
     * `member=true` narrows to the caller's own, `member=false` to the readable ones the caller is NOT in. One predicate for the count and
     * the rows, in one transaction; the member counts and the caller's roles are batched lookups for the page, not per row.
     */
    suspend fun list(callerUserId: UInt, callerIsAdmin: Boolean, filter: RoomListFilter, paging: PageRequest): RoomListResult =
        suspendTransaction(database) {
            val predicate = buildPredicate(filter, callerUserId, callerIsAdmin)
            val total = Rooms.selectAll().where { predicate }.count()
            val rows = Rooms.selectAll().where { predicate }.applyPaging(paging, SORTABLE_COLUMNS).toList()
            val ids = rows.map { it[Rooms.id].value }
            val counts = activeMemberCounts(ids)
            val roles = callerRoles(ids, callerUserId)
            RoomListResult(
                items = rows.map { row ->
                    val id = row[Rooms.id].value
                    RoomListItem(
                        id = id,
                        name = row[Rooms.name],
                        description = row[Rooms.description],
                        archived = row[Rooms.archivedAt] != null,
                        role = roles[id],
                        memberCount = counts[id] ?: 0,
                        lastActivityAt = row[Rooms.lastActivityAt],
                        createdAt = row[Rooms.createdAt],
                    )
                },
                total = total,
            )
        }

    /** The room as seen by [callerUserId] (their role, null for an ADMIN outside it), or null when missing or deleted. */
    suspend fun read(id: UInt, callerUserId: UInt): RoomResponse? = suspendTransaction(database) {
        val row = Rooms.selectAll().where { (Rooms.id eq id) and Rooms.active() }.toList().singleOrNull()
            ?: return@suspendTransaction null
        RoomResponse(
            id = id,
            name = row[Rooms.name],
            description = row[Rooms.description],
            archived = row[Rooms.archivedAt] != null,
            archivedAt = row[Rooms.archivedAt],
            role = callerRoles(listOf(id), callerUserId)[id],
            memberCount = activeMemberCounts(listOf(id))[id] ?: 0,
            workflowRevision = row[Rooms.workflowRevision],
            lastActivityAt = row[Rooms.lastActivityAt],
            createdAt = row[Rooms.createdAt],
            updatedAt = row[Rooms.updatedAt],
        )
    }

    /**
     * Creates the room in ONE transaction: validate, read the template (unknown or deleted is a 400, a client-supplied id, never a 500),
     * insert the room, insert the creator as its OWNER, copy the template's statuses and fields with their labels resolved to the
     * request's language. A name clash with a non-deleted room rides the V13 partial index into the central 23505 -> 409, which rolls
     * the whole transaction back.
     */
    suspend fun create(callerUserId: UInt, request: RoomCreateRequest): UInt = suspendTransaction(database) {
        validateRoomCreate(request) // re-checked service-side so direct callers stay guarded
        val template = templates.read(request.templateId)
            ?: throw BadRequestException("Unknown or deleted workflow template id: ${request.templateId}")
        val stamp = nowMillis()
        val id = Rooms.insert {
            it[name] = request.name
            it[description] = request.description
            it[lastActivityAt] = stamp
            it[createdAt] = stamp
            it[updatedAt] = stamp
        }[Rooms.id].value
        RoomMembers.insert {
            it[roomId] = id
            it[userId] = callerUserId
            it[role] = RoomRole.OWNER.name
            it[addedAt] = stamp
        }
        workflows.copyFromTemplate(id, WorkflowDefinition(template.statuses, template.fields), request.language ?: DEFAULT_LANGUAGE)
        id
    }

    /**
     * The metadata replace under the room lock: [guard] re-runs on the locked standing, an archived room is the typed `409`
     * (`room-archived`), then name and description are written (a name clash is the central 409).
     */
    suspend fun update(roomId: UInt, caller: CallerPrincipal, guard: RoomGuard, request: RoomUpdateRequest) {
        validateRoomUpdate(request)
        database.withRoomWrite(roomId, caller, guard) { standing ->
            if (standing.archived) throw roomArchived()
            Rooms.update({ Rooms.id eq roomId }) {
                it[name] = request.name
                it[description] = request.description
                it[updatedAt] = nowMillis()
            }
        }
    }

    /** Archives or unarchives under the room lock; true on an actual change, false when the room already was in that state (no write). */
    suspend fun setArchived(roomId: UInt, caller: CallerPrincipal, guard: RoomGuard, archived: Boolean): Boolean =
        database.withRoomWrite(roomId, caller, guard) { standing ->
            if (standing.archived == archived) return@withRoomWrite false
            val stamp = nowMillis()
            Rooms.update({ Rooms.id eq roomId }) {
                it[archivedAt] = if (archived) stamp else null
                it[updatedAt] = stamp
            }
            true
        }

    /** Soft delete under the room lock: the room stops reading as active and frees its name; members and workflow rows stay with it. */
    suspend fun delete(roomId: UInt, caller: CallerPrincipal, guard: RoomGuard) {
        database.withRoomWrite(roomId, caller, guard) {
            Rooms.update({ Rooms.id eq roomId }) {
                it[markedAsDeleted] = true
                it[updatedAt] = nowMillis()
            }
        }
    }

    /**
     * Bumps the room's `last_activity_at` (the rail order) to [at]: a new thread or message, a status, owner, title or field change
     * (`chat-model.md` "Activity"). Runs INSIDE the caller's transaction - the thread write, under the room lock `withRoomWrite` took -
     * and never opens one of its own (the `readStanding` idiom).
     */
    suspend fun touchActivity(roomId: UInt, at: Long) {
        Rooms.update({ Rooms.id eq roomId }) { it[lastActivityAt] = at }
    }

    /** One grouped query for a page's member counts (ACTIVE users only; the inner join drops a soft-deleted member). */
    private suspend fun activeMemberCounts(roomIds: List<UInt>): Map<UInt, Int> {
        if (roomIds.isEmpty()) return emptyMap()
        val count = RoomMembers.userId.count()
        return RoomMembers.innerJoin(UserService.Users)
            .select(RoomMembers.roomId, count)
            .where { (RoomMembers.roomId inList roomIds) and UserService.Users.active() }
            .groupBy(RoomMembers.roomId)
            .map { it[RoomMembers.roomId].value to it[count].toInt() }
            .toList()
            .toMap()
    }

    /** The caller's role in each of [roomIds] they belong to (absent = not a member), one query. */
    private suspend fun callerRoles(roomIds: List<UInt>, callerUserId: UInt): Map<UInt, RoomRole> {
        if (roomIds.isEmpty()) return emptyMap()
        return RoomMembers.select(RoomMembers.roomId, RoomMembers.role)
            .where { (RoomMembers.roomId inList roomIds) and (RoomMembers.userId eq callerUserId) }
            .map { it[RoomMembers.roomId].value to RoomRole.valueOf(it[RoomMembers.role]) }
            .toList()
            .toMap()
    }

    private fun buildPredicate(filter: RoomListFilter, callerUserId: UInt, callerIsAdmin: Boolean): Op<Boolean> {
        val memberOf = Rooms.id inSubQuery RoomMembers.select(RoomMembers.roomId).where { RoomMembers.userId eq callerUserId }
        var op: Op<Boolean> = Rooms.active()
        if (!callerIsAdmin) op = op and memberOf
        when (filter.member) {
            true -> op = op and memberOf
            false -> op = op and not(memberOf)
            null -> Unit
        }
        filter.archived?.let { op = op and (if (it) Rooms.archivedAt.isNotNull() else Rooms.archivedAt.isNull()) }
        filter.name?.takeIf { it.isNotBlank() }?.let { op = op and Rooms.name.containsNormalized(it) }
        return op
    }
}
