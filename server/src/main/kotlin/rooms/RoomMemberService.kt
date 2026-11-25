package ch.nokillswit.rooms

import ch.nokillswit.authz.ConflictException
import ch.nokillswit.authz.NotFoundException
import ch.nokillswit.authz.RoomRole
import ch.nokillswit.authz.RoomAccessType
import ch.nokillswit.authz.CallerPrincipal
import ch.nokillswit.infra.db.active
import ch.nokillswit.infra.db.containsNormalized
import ch.nokillswit.infra.db.greatestOf
import ch.nokillswit.infra.db.insertRows
import ch.nokillswit.infra.db.nowMillis
import ch.nokillswit.infra.notify.LiveHint
import ch.nokillswit.infra.notify.LiveKind
import ch.nokillswit.infra.notify.notifyLive
import ch.nokillswit.infra.paging.PageRequest
import ch.nokillswit.infra.paging.applyPaging
import ch.nokillswit.teams.TeamService
import ch.nokillswit.users.UserService
import io.ktor.server.plugins.BadRequestException
import io.ktor.util.AttributeKey
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.vendors.ForUpdateOption
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.update

val RoomMemberServiceKey = AttributeKey<RoomMemberService>("RoomMemberService")

private val SORTABLE_COLUMNS: Map<String, Column<*>> = mapOf(
    "id" to RoomService.RoomMembers.userId,
    "name" to UserService.Users.name,
    "role" to RoomService.RoomMembers.role,
    "addedAt" to RoomService.RoomMembers.addedAt,
)

/** The ONE sortable whitelist of the member list (`id` is the user id) - derived from the column map so the two can never drift. */
val ROOM_MEMBER_SORT_FIELDS: Set<String> = SORTABLE_COLUMNS.keys

/**
 * The room's membership (V13 `room_members`, a hard-delete join). Every write goes through `withRoomWrite` (`RoomLock.kt`): the room row
 * lock makes the 500-member cap and the last-OWNER count race-free without locking member rows, and the caller's guard is re-run on the
 * standing re-read under it. Membership management stays allowed on an ARCHIVED room (access to the record must stay manageable). Every
 * read and count resolves members through ACTIVE users (a soft-deleted user's row is inert); the cross-feature reads (`users`, the
 * team roster through [TeamService.activeRosterIds]) are listed in `persistence.md`.
 */
class RoomMemberService(
    private val database: R2dbcDatabase,
    private val teams: TeamService,
    private val notices: RoomNotices = RoomNotices.NONE,
) {
    private val members = RoomService.RoomMembers
    private val users = UserService.Users

    /** One page of the room's ACTIVE members: one predicate for the count and the rows, in one transaction. */
    suspend fun list(roomId: UInt, filter: RoomMemberListFilter, paging: PageRequest): RoomMemberListResult =
        suspendTransaction(database) {
            var predicate: Op<Boolean> = (members.roomId eq roomId) and users.active()
            filter.role?.let { predicate = predicate and (members.role eq it.name) }
            filter.name?.takeIf { it.isNotBlank() }?.let { predicate = predicate and users.name.containsNormalized(it) }
            val joined = members.innerJoin(users)
            val total = joined.select(members.userId).where { predicate }.count()
            val rows = joined.select(users.id, users.name, users.email, members.role, members.addedAt)
                .where { predicate }
                .applyPaging(paging, SORTABLE_COLUMNS)
                .map {
                    RoomMemberResponse(
                        userId = it[users.id].value,
                        name = it[users.name],
                        email = it[users.email],
                        role = RoomRole.valueOf(it[members.role]),
                        addedAt = it[members.addedAt],
                    )
                }
                .toList()
            RoomMemberListResult(rows, total)
        }

    /**
     * The ids among [candidates] that belong to an ACTIVE member of room [roomId] (a soft-deleted user's membership row is inert), one
     * query. Runs INSIDE the caller's transaction (a thread write, under the room lock), so a USER field value is judged against the
     * very membership the write commits with. The pure field kernel gets this set (`threads/ThreadFields.kt`).
     */
    suspend fun activeMemberIds(roomId: UInt, candidates: Collection<Long>): Set<Long> {
        if (candidates.isEmpty()) return emptySet()
        val ids = candidates.filter { it in 1..UInt.MAX_VALUE.toLong() }.map { it.toUInt() } // a wider id is no user (and must not wrap)
        return members.innerJoin(users).select(members.userId)
            .where { (members.roomId eq roomId) and (members.userId inList ids) and users.active() }
            .map { it[members.userId].value.toLong() }
            .toList()
            .toSet()
    }

    /** Every ACTIVE member of room [roomId] (at most [MAX_ROOM_MEMBERS]), one query, INSIDE the caller's transaction (the audience). */
    suspend fun allActiveMemberIds(roomId: UInt): List<UInt> = allActiveMemberIdsOf(roomId)

    /**
     * [userId]'s floor of unread counting in room [roomId] - `GREATEST(read_from, added_at)` (V19) - or null when they are not a member
     * (an ADMIN outside the room has no read state). One read of the person's own row, INSIDE the caller's transaction, no lock.
     */
    suspend fun unreadSince(roomId: UInt, userId: UInt): Long? =
        members.select(members.readFrom, members.addedAt)
            .where { (members.roomId eq roomId) and (members.userId eq userId) }
            .toList().singleOrNull()?.let { maxOf(it[members.readFrom], it[members.addedAt]) }

    /**
     * [userId] looked at room [roomId] at [at]: moves ONLY their own `seen_at`, forward only, and answers whether they have a membership
     * row (false = they are not, or no longer, a member, the route's `404`). Runs INSIDE the caller's transaction and takes NO room lock:
     * the update locks the person's own row to the commit, so a removal (which deletes the row under the room lock) waits for it, and a
     * removal that came first leaves nothing to update. View state: no hint, no audit line (`observability.md`).
     */
    suspend fun markSeenInTransaction(roomId: UInt, userId: UInt, at: Long): Boolean =
        members.update({ (members.roomId eq roomId) and (members.userId eq userId) }) { it[seenAt] = seenAt.greatestOf(at) } > 0

    /**
     * "Mark all as read" (1.2): [userId] has read everything of room [roomId] written up to [at]. Raises ONLY their own `read_from` (the
     * floor of unread counting, V19: every thread of the room, closed ones and ones nobody has opened included, reads 0 unread from
     * there; a message written later is unread again) and `seen_at` (the room's dot goes out), both forward only (`GREATEST`), and
     * answers whether they have a membership row (false = not, or no longer, a member: the route's `404`). One statement, so it is the
     * same cost for a room of three threads and of three thousand, and no `thread_reads` row moves. INSIDE the caller's transaction, NO
     * room lock (the update locks the person's own row to the commit, like [markSeenInTransaction]), and the caller keeps that
     * transaction SHORT: the notices are settled afterwards in their own transactions (`ThreadReadService.markRoomRead`). No hint.
     */
    suspend fun markAllReadInTransaction(roomId: UInt, userId: UInt, at: Long): Boolean =
        members.update({ (members.roomId eq roomId) and (members.userId eq userId) }) {
            it[seenAt] = seenAt.greatestOf(at)
            it[readFrom] = readFrom.greatestOf(at)
        } > 0

    /**
     * Holds [userId]'s membership row of room [roomId] for the rest of the caller's transaction (`FOR KEY SHARE`) and answers whether it
     * exists (false = they are not, or no longer, a member: the route's `404`). A removal deletes the row under the room lock and waits
     * for the share lock, so a write that follows (a watch, whose composite foreign key names this row) can never fail the key under it;
     * two holders never block each other. Takes NO room lock and writes nothing: INSIDE the caller's transaction, like
     * [markSeenInTransaction].
     */
    suspend fun holdMembership(roomId: UInt, userId: UInt): Boolean =
        members.select(members.userId)
            .where { (members.roomId eq roomId) and (members.userId eq userId) }
            .forUpdate(ForUpdateOption.PostgreSQL.ForKeyShare)
            .toList().isNotEmpty()

    /** [markSeenInTransaction] in a transaction of its own (`POST /rooms/{id}/seen`). */
    suspend fun markSeen(roomId: UInt, userId: UInt, at: Long): Boolean =
        suspendTransaction(database) { markSeenInTransaction(roomId, userId, at) }

    /**
     * Adds the named people and, when asked, the team's ACTIVE roster as it is NOW (a snapshot: a later roster edit never follows), always
     * as MEMBER. Under the room lock: the team (missing or deleted is a 400) and every named id (unknown or soft-deleted is a 400) are
     * resolved first, the people who already belong are subtracted (idempotent: they are counted, not re-added, and beat the cap), then
     * the 500-member cap over ACTIVE members is checked (a plain `409`) and the rows are written in one multi-row insert. A person both
     * named and on the roster counts as named (no `viaTeamId` in the audit).
     */
    suspend fun add(
        roomId: UInt,
        caller: CallerPrincipal,
        guard: RoomGuard,
        request: RoomMembersAddRequest,
    ): RoomMembersAddOutcome {
        validateRoomMembersAdd(request) // re-checked service-side so direct callers stay guarded
        return database.withRoomWrite(roomId, caller, guard) { fresh, _ ->
            val named = request.userIds.orEmpty().distinct()
            val roster = request.teamId?.let { teamId ->
                teams.activeRosterIds(teamId) ?: throw BadRequestException("Unknown or deleted team id: $teamId")
            }.orEmpty()
            requireActiveUsers(named)
            val namedSet = named.toSet()
            val viaTeam = roster.filter { it !in namedSet }
            val wanted = named + viaTeam
            val existing = existingMembers(roomId, wanted)
            val toAdd = wanted.filter { it !in existing }
            if (toAdd.isNotEmpty() && activeMemberCount(roomId) + toAdd.size > MAX_ROOM_MEMBERS) {
                throw ConflictException("A room may have at most $MAX_ROOM_MEMBERS members")
            }
            val stamp = nowMillis()
            members.insertRows(toAdd) {
                this[members.roomId] = roomId
                this[members.userId] = it
                this[members.role] = RoomRole.MEMBER.name
                this[members.addedAt] = stamp
            }
            if (wanted.isNotEmpty()) {
                RoomJoinRequests.deleteWhere {
                    (RoomJoinRequests.roomId eq roomId) and (RoomJoinRequests.userId inList wanted)
                }
            }
            // Only when someone joined: an add of people who all belong already is a no-op and announces nothing.
            if (toAdd.isNotEmpty()) notifyLive(LiveHint(LiveKind.ROOM_MEMBER_ADDED, roomId, targetUserIds = toAdd))
            RoomMembersAddOutcome(
                addedUserIds = toAdd,
                viaTeamUserIds = viaTeam.filter { it in toAdd }.toSet(),
                alreadyMembers = wanted.size - toAdd.size,
                noticesCreated = notices.membersAdded(roomId, fresh.userId, toAdd, namedSet),
            )
        }
    }

    /**
     * Sets [userId]'s role under the room lock. A target who is not an ACTIVE member is a `404`. Idempotent: returns null (no write)
     * when the member already has [role], else the role they had, so the route audits only a change. Demoting the last active OWNER
     * is the typed `409` `last-owner`.
     */
    suspend fun setRole(roomId: UInt, userId: UInt, caller: CallerPrincipal, guard: RoomGuard, role: RoomRole): RoomRole? =
        database.withRoomWrite(roomId, caller, guard) { _, _ ->
            val current = activeRole(roomId, userId) ?: throw NotFoundException("Member not found")
            if (current == role) return@withRoomWrite null
            if (current == RoomRole.OWNER) requireOtherActiveOwner(roomId, userId)
            members.update({ (members.roomId eq roomId) and (members.userId eq userId) }) { it[members.role] = role.name }
            notifyLive(LiveHint(LiveKind.ROOM_MEMBER_ROLE_CHANGED, roomId, targetUserIds = listOf(userId)))
            current
        }

    /**
     * Removes [userId]'s membership under the room lock (a leave or a removal: the route's guard tells them apart); 404 for a
     * non-member, `409` `last-owner` for the last active OWNER.
     */
    suspend fun remove(roomId: UInt, userId: UInt, caller: CallerPrincipal, guard: RoomGuard) {
        database.withRoomWrite(roomId, caller, guard) { _, standing ->
            val current = activeRole(roomId, userId) ?: throw NotFoundException("Member not found")
            if (current == RoomRole.OWNER) requireOtherActiveOwner(roomId, userId)
            members.deleteWhere { (RoomService.RoomMembers.roomId eq roomId) and (RoomService.RoomMembers.userId eq userId) }
            notifyLive(
                LiveHint(
                    LiveKind.ROOM_MEMBER_REMOVED,
                    roomId,
                    targetUserIds = listOf(userId),
                    requiresMembership = standing.accessType == RoomAccessType.RESTRICTED_HIDDEN,
                ),
            )
        }
    }

    /**
     * The last-OWNER rule: after the write at least one OWNER other than [userId] whose user is active must remain (else `409`).
     * Call under the room lock.
     */
    private suspend fun requireOtherActiveOwner(roomId: UInt, userId: UInt) {
        val others = members.innerJoin(users).select(members.userId)
            .where {
                (members.roomId eq roomId) and (members.role eq RoomRole.OWNER.name) and (members.userId neq userId) and
                    users.active()
            }
            .count()
        if (others == 0L) throw lastOwner()
    }

    /** [userId]'s role in the room when the member is an ACTIVE user, else null. */
    private suspend fun activeRole(roomId: UInt, userId: UInt): RoomRole? =
        members.innerJoin(users).select(members.role)
            .where { (members.roomId eq roomId) and (members.userId eq userId) and users.active() }
            .toList()
            .singleOrNull()
            ?.let { RoomRole.valueOf(it[members.role]) }

    private suspend fun activeMemberCount(roomId: UInt): Long =
        members.innerJoin(users).select(members.userId).where { (members.roomId eq roomId) and users.active() }.count()

    /** Which of [ids] already have a membership row (an inert row included: a re-add never collides with the primary key). */
    private suspend fun existingMembers(roomId: UInt, ids: List<UInt>): Set<UInt> {
        if (ids.isEmpty()) return emptySet()
        return members.select(members.userId)
            .where { (members.roomId eq roomId) and (members.userId inList ids) }
            .map { it[members.userId].value }
            .toList()
            .toSet()
    }

    /** A client-supplied id must be an ACTIVE user: unknown or soft-deleted is a `400`, never a 500 from the foreign key. */
    private suspend fun requireActiveUsers(ids: List<UInt>) {
        if (ids.isEmpty()) return
        val found = users.select(users.id).where { (users.id inList ids) and users.active() }.map { it[users.id].value }.toList().toSet()
        val missing = ids.filter { it !in found }
        if (missing.isNotEmpty()) {
            throw BadRequestException("Unknown or deleted user ids: ${missing.take(MAX_LISTED_IDS).joinToString(", ")}")
        }
    }

    private companion object {
        /** The ids a 400 names (the add takes at most 200): the rest would only bloat the problem body. */
        const val MAX_LISTED_IDS = 10
    }
}

/**
 * Every ACTIVE member of room [roomId] (at most [MAX_ROOM_MEMBERS], the cap) by user id, one query, inside the caller's transaction. The
 * one implementation: [RoomMemberService.allActiveMemberIds] (the notice audience) and the room delete's `revoked` targets share it.
 */
internal suspend fun allActiveMemberIdsOf(roomId: UInt): List<UInt> {
    val members = RoomService.RoomMembers
    return members.innerJoin(UserService.Users).select(members.userId)
        .where { (members.roomId eq roomId) and UserService.Users.active() }
        .orderBy(members.userId)
        .map { it[members.userId].value }
        .toList()
}
