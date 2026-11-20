package ch.nokillswit.rooms

import ch.nokillswit.authz.CallerPrincipal
import ch.nokillswit.authz.RoomStanding
import ch.nokillswit.authz.RoomRole
import ch.nokillswit.infra.db.SoftDeletable
import ch.nokillswit.infra.db.active
import ch.nokillswit.infra.db.containsNormalized
import ch.nokillswit.infra.db.greatestOf
import ch.nokillswit.infra.db.nowMillis
import ch.nokillswit.infra.notify.LiveHint
import ch.nokillswit.infra.notify.LiveKind
import ch.nokillswit.infra.notify.notifyLive
import ch.nokillswit.infra.paging.PageRequest
import ch.nokillswit.infra.paging.applyPaging
import ch.nokillswit.plugins.RateLimitAuditThrottle
import ch.nokillswit.users.DEFAULT_LANGUAGE
import ch.nokillswit.users.UserRole
import ch.nokillswit.users.UserService
import ch.nokillswit.workflows.WorkflowDefinition
import ch.nokillswit.workflows.WorkflowTemplateService
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

/** Distinct rooms per read of [RoomService.lostMemberships]. */
private const val LOST_MEMBERSHIP_CHUNK = 500

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
    /** Coalesces the `room.access_denied` audit line per user (`RoomAccess.kt`): at most one a minute, with the folded count. */
    internal val denialAudit = RateLimitAuditThrottle()

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

        /** When the member last looked at the room (V19; 0 = since joining: every read is `GREATEST(seenAt, addedAt)`). */
        val seenAt = long("seen_at").default(0L)

        /** The floor of the member's unread counting (V19; 0 = since joining: every read is `GREATEST(readFrom, addedAt)`). */
        val readFrom = long("read_from").default(0L)
        override val primaryKey = PrimaryKey(roomId, userId)
    }

    /** The caller's standing in an active room (null = no such room): the route's visibility preamble, no lock. */
    suspend fun standing(roomId: UInt, userId: UInt): RoomStanding? = suspendTransaction(database) {
        readStanding(roomId, userId, lock = false)
    }

    /**
     * The id, name and archived flag of the ACTIVE room [roomId], or null when there is none: what a search hit shows of its room. No
     * membership check (the route's visibility preamble runs first).
     */
    suspend fun summary(roomId: UInt): RoomSummary? = suspendTransaction(database) {
        Rooms.select(Rooms.id, Rooms.name, Rooms.archivedAt).where { (Rooms.id eq roomId) and Rooms.active() }.toList().singleOrNull()
            ?.let { RoomSummary(it[Rooms.id].value, it[Rooms.name], it[Rooms.archivedAt] != null) }
    }

    /**
     * The ACTIVE rooms [userId] is an ACTIVE member of, in id order: the scope of the search across rooms. Membership only, whatever
     * the caller's role: an ADMIN reads every room one at a time but searches only the rooms they belong to (a room's own member list
     * is the one thing that widens what a search sees). An archived room is included (it is searched and labelled); a deleted room and
     * the rows of a soft-deleted user are inert. One read, no lock.
     */
    suspend fun memberRooms(userId: UInt): List<RoomSummary> = suspendTransaction(database) {
        RoomMembers.innerJoin(UserService.Users).innerJoin(Rooms).select(Rooms.id, Rooms.name, Rooms.archivedAt)
            .where { (RoomMembers.userId eq userId) and UserService.Users.active() and Rooms.active() }
            .orderBy(Rooms.id)
            .map { RoomSummary(it[Rooms.id].value, it[Rooms.name], it[Rooms.archivedAt] != null) }.toList()
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
            val mine = callerMemberships(ids, callerUserId)
            RoomListResult(
                items = rows.map { row ->
                    val id = row[Rooms.id].value
                    RoomListItem(
                        id = id,
                        name = row[Rooms.name],
                        description = row[Rooms.description],
                        archived = row[Rooms.archivedAt] != null,
                        role = mine[id]?.role,
                        memberCount = counts[id] ?: 0,
                        lastActivityAt = row[Rooms.lastActivityAt],
                        createdAt = row[Rooms.createdAt],
                        // Only a member has read state: an ADMIN outside the room never sees a dot.
                        unread = mine[id]?.let { row[Rooms.lastActivityAt] > it.seenSince } ?: false,
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
            role = callerMemberships(listOf(id), callerUserId)[id]?.role,
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
            ?: throw templateGone(request.templateId)
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
        notifyLive(LiveHint(LiveKind.ROOM_CREATED, id, targetUserIds = listOf(callerUserId)))
        id
    }

    /**
     * Which of [roomIds] the caller may read, for the live socket's subscribe (`live.md` "The hub"): ONE transaction, three reads. The
     * existing rooms are the non-deleted ones among [roomIds]; an ADMIN reads every one of them, anyone else the ones they are an ACTIVE
     * member of (the same membership join as [readStanding]: a soft-deleted user's row is inert). Whether the caller is an ADMIN is read
     * HERE from the stored role of the ACTIVE user, never taken from the socket (its snapshot dates from the ticket): a demoted or deleted
     * ADMIN cannot gain NEW rooms (their existing subscriptions end at the next account sweep). The rest of the existing rooms are
     * [ReadableRooms.deniedExisting], the ids the subscribe audits as `live.subscribe_denied`; an id that matches no room is in neither set
     * (nothing was denied, and a caller must not learn which ids exist). Archived rooms are readable.
     */
    suspend fun readableRoomIds(userId: UInt, roomIds: Collection<UInt>): ReadableRooms {
        if (roomIds.isEmpty()) return ReadableRooms(emptySet(), emptySet())
        return suspendTransaction(database) {
            val existing = Rooms.select(Rooms.id).where { (Rooms.id inList roomIds) and Rooms.active() }
                .map { it[Rooms.id].value }.toList().toSet()
            val admin = existing.isNotEmpty() && UserService.Users.select(UserService.Users.id)
                .where {
                    (UserService.Users.id eq userId) and (UserService.Users.role eq UserRole.ADMIN.name) and UserService.Users.active()
                }
                .toList().isNotEmpty()
            if (admin) {
                ReadableRooms(readable = existing, deniedExisting = emptySet())
            } else if (existing.isEmpty()) {
                ReadableRooms(readable = emptySet(), deniedExisting = emptySet())
            } else {
                val member = RoomMembers.innerJoin(UserService.Users).select(RoomMembers.roomId)
                    .where { (RoomMembers.roomId inList existing) and (RoomMembers.userId eq userId) and UserService.Users.active() }
                    .map { it[RoomMembers.roomId].value }.toList().toSet()
                ReadableRooms(readable = member, deniedExisting = existing - member)
            }
        }
    }

    /**
     * Of the (user, room) pairs a live socket holds ACTIVE subscriptions for, the ones whose owner is no longer an ACTIVE member of an
     * existing room (the removal's pushed hint can be lost in a listener gap; the live sweep and every gap re-check with this).
     * Callers pass non-ADMIN pairs only. One read per [LOST_MEMBERSHIP_CHUNK] distinct rooms.
     */
    suspend fun lostMemberships(pairs: Collection<Pair<UInt, UInt>>): Set<Pair<UInt, UInt>> {
        if (pairs.isEmpty()) return emptySet()
        val present = HashSet<Pair<UInt, UInt>>()
        pairs.map { it.second }.distinct().chunked(LOST_MEMBERSHIP_CHUNK).map { it.toSet() }.forEach { roomIds ->
            val userIds = pairs.filter { it.second in roomIds }.map { it.first }.toSet()
            present += suspendTransaction(database) {
                RoomMembers.innerJoin(UserService.Users).innerJoin(Rooms)
                    .select(RoomMembers.userId, RoomMembers.roomId)
                    .where {
                        (RoomMembers.roomId inList roomIds) and (RoomMembers.userId inList userIds) and
                            UserService.Users.active() and Rooms.active()
                    }
                    .map { it[RoomMembers.userId].value to it[RoomMembers.roomId].value }.toList()
            }
        }
        return pairs.filterTo(HashSet()) { it !in present }
    }

    /**
     * The metadata replace under the room lock: [guard] re-runs on the locked standing, an archived room is the typed `409`
     * (`room-archived`), then name and description are written (a name clash is the central 409).
     */
    suspend fun update(roomId: UInt, caller: CallerPrincipal, guard: RoomGuard, request: RoomUpdateRequest) {
        validateRoomUpdate(request)
        database.withRoomWrite(roomId, caller, guard) { _, standing ->
            if (standing.archived) throw roomArchived()
            Rooms.update({ Rooms.id eq roomId }) {
                it[name] = request.name
                it[description] = request.description
                it[updatedAt] = nowMillis()
            }
            notifyLive(LiveHint(LiveKind.ROOM_UPDATED, roomId))
        }
    }

    /** Archives or unarchives under the room lock; true on an actual change, false when the room already was in that state (no write). */
    suspend fun setArchived(roomId: UInt, caller: CallerPrincipal, guard: RoomGuard, archived: Boolean): Boolean =
        database.withRoomWrite(roomId, caller, guard) { _, standing ->
            if (standing.archived == archived) return@withRoomWrite false
            val stamp = nowMillis()
            Rooms.update({ Rooms.id eq roomId }) {
                it[archivedAt] = if (archived) stamp else null
                it[updatedAt] = stamp
            }
            notifyLive(LiveHint(if (archived) LiveKind.ROOM_ARCHIVED else LiveKind.ROOM_UNARCHIVED, roomId))
            true
        }

    /** Soft delete under the room lock: the room stops reading as active and frees its name; members and workflow rows stay with it. */
    suspend fun delete(roomId: UInt, caller: CallerPrincipal, guard: RoomGuard) {
        database.withRoomWrite(roomId, caller, guard) { _, _ ->
            // The members are read BEFORE the delete: they get the personal `revoked` frame (`live.md` "Hints").
            val memberIds = activeMemberIds(roomId)
            Rooms.update({ Rooms.id eq roomId }) {
                it[markedAsDeleted] = true
                it[updatedAt] = nowMillis()
            }
            notifyLive(LiveHint(LiveKind.ROOM_DELETED, roomId, targetUserIds = memberIds))
        }
    }

    /**
     * Bumps the room's `last_activity_at` (the rail order) to [at]: a new thread or message, a status, owner, title or field change
     * (`chat-model.md` "Activity"). Runs INSIDE the caller's transaction - the thread write, under the room lock `withRoomWrite` took -
     * and never opens one of its own (the `readStanding` idiom). The ACTOR has seen the room at [at] too ([RoomMembers.seenAt], only ever
     * moving forward): one's own activity never makes a room new for oneself; nobody else's row is touched (an ADMIN outside the room has
     * no row, so there is nothing to move).
     */
    suspend fun touchActivity(roomId: UInt, at: Long, actorUserId: UInt) {
        Rooms.update({ Rooms.id eq roomId }) { it[lastActivityAt] = at }
        RoomMembers.update({ (RoomMembers.roomId eq roomId) and (RoomMembers.userId eq actorUserId) }) {
            it[seenAt] = seenAt.greatestOf(at)
        }
    }

    /** Every ACTIVE member of [roomId] (at most 500, the cap), one query, inside the caller's transaction. */
    private suspend fun activeMemberIds(roomId: UInt): List<UInt> =
        RoomMembers.innerJoin(UserService.Users).select(RoomMembers.userId)
            .where { (RoomMembers.roomId eq roomId) and UserService.Users.active() }
            .map { it[RoomMembers.userId].value }
            .toList()

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

    /** The caller's role and the moment they last looked at each of [roomIds] they belong to (absent = not a member), one query. */
    private suspend fun callerMemberships(roomIds: List<UInt>, callerUserId: UInt): Map<UInt, CallerMembership> {
        if (roomIds.isEmpty()) return emptyMap()
        return RoomMembers.select(RoomMembers.roomId, RoomMembers.role, RoomMembers.seenAt, RoomMembers.addedAt)
            .where { (RoomMembers.roomId inList roomIds) and (RoomMembers.userId eq callerUserId) }
            .map {
                it[RoomMembers.roomId].value to
                    CallerMembership(RoomRole.valueOf(it[RoomMembers.role]), maxOf(it[RoomMembers.seenAt], it[RoomMembers.addedAt]))
            }
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
