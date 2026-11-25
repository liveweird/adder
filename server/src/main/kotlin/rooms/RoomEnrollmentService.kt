package ch.nokillswit.rooms

import ch.nokillswit.authz.CallerPrincipal
import ch.nokillswit.authz.ConflictException
import ch.nokillswit.authz.ForbiddenException
import ch.nokillswit.authz.NotFoundException
import ch.nokillswit.authz.RoomAccessType
import ch.nokillswit.authz.RoomRole
import ch.nokillswit.infra.db.active
import ch.nokillswit.infra.db.nowMillis
import ch.nokillswit.infra.notify.LiveHint
import ch.nokillswit.infra.notify.LiveKind
import ch.nokillswit.infra.notify.notifyLive
import ch.nokillswit.infra.paging.PageRequest
import ch.nokillswit.infra.paging.applyPaging
import ch.nokillswit.users.UserService
import io.ktor.util.AttributeKey
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.update

val RoomEnrollmentServiceKey = AttributeKey<RoomEnrollmentService>("RoomEnrollmentService")

private val JOIN_REQUEST_SORTABLE_COLUMNS: Map<String, Column<*>> = mapOf(
    "id" to RoomJoinRequests.id,
    "requestedAt" to RoomJoinRequests.requestedAt,
)

val ROOM_JOIN_REQUEST_SORT_FIELDS: Set<String> = JOIN_REQUEST_SORTABLE_COLUMNS.keys

class RoomEnrollmentService(private val database: R2dbcDatabase, private val notices: RoomNotices = RoomNotices.NONE) {
    suspend fun access(roomId: UInt, userId: UInt): RoomAccessResponse? = suspendTransaction(database) {
        val rooms = RoomService.Rooms
        val row = rooms.select(rooms.id, rooms.name, rooms.description, rooms.accessType, rooms.archivedAt)
            .where { (rooms.id eq roomId) and rooms.active() }
            .toList()
            .singleOrNull() ?: return@suspendTransaction null
        val member = activeRole(roomId, userId) != null
        val accessType = RoomAccessType.valueOf(row[rooms.accessType])
        if (!member && accessType == RoomAccessType.RESTRICTED_HIDDEN) return@suspendTransaction null
        val requestStatus = if (member) null else RoomJoinRequests.select(RoomJoinRequests.status)
            .where { (RoomJoinRequests.roomId eq roomId) and (RoomJoinRequests.userId eq userId) }
            .map { RoomJoinRequestStatus.valueOf(it[RoomJoinRequests.status]) }
            .toList()
            .singleOrNull()
        RoomAccessResponse(
            id = roomId,
            name = row[rooms.name],
            description = row[rooms.description],
            accessType = accessType,
            archived = row[rooms.archivedAt] != null,
            requestStatus = requestStatus,
        )
    }

    /** Returns true only when this call creates the membership. */
    suspend fun join(roomId: UInt, caller: CallerPrincipal): Boolean =
        database.withRoomWrite(roomId, caller, publicJoinGuard) { fresh, standing ->
            if (standing.role != null) return@withRoomWrite false
            addMember(roomId, fresh.userId)
            true
        }

    /** Returns true when a new request was made or a rejection was submitted again. */
    suspend fun requestJoin(roomId: UInt, caller: CallerPrincipal): Boolean =
        database.withRoomWrite(roomId, caller, visibleRequestGuard) { fresh, standing ->
            if (standing.role != null) return@withRoomWrite false
            val current = RoomJoinRequests.select(RoomJoinRequests.status)
                .where { (RoomJoinRequests.roomId eq roomId) and (RoomJoinRequests.userId eq fresh.userId) }
                .toList()
                .singleOrNull()
            if (current?.get(RoomJoinRequests.status) == RoomJoinRequestStatus.PENDING.name) return@withRoomWrite false
            if (current != null) clearRequest(roomId, fresh.userId)
            RoomJoinRequests.insert {
                it[RoomJoinRequests.roomId] = roomId
                it[userId] = fresh.userId
                it[status] = RoomJoinRequestStatus.PENDING.name
                it[requestedAt] = nowMillis()
            }
            true
        }

    suspend fun list(roomId: UInt, paging: PageRequest): RoomJoinRequestListResult = suspendTransaction(database) {
        val users = UserService.Users
        val predicate = (RoomJoinRequests.roomId eq roomId) and
            (RoomJoinRequests.status eq RoomJoinRequestStatus.PENDING.name) and users.active()
        val joined = RoomJoinRequests.innerJoin(users)
        val total = joined.selectAll().where { predicate }.count()
        val rows = joined.select(RoomJoinRequests.id, RoomJoinRequests.userId, users.name, RoomJoinRequests.requestedAt)
            .where { predicate }
            .applyPaging(paging, JOIN_REQUEST_SORTABLE_COLUMNS)
            .toList()
        RoomJoinRequestListResult(
            rows.map {
                RoomJoinRequestResponse(
                    it[RoomJoinRequests.id].value,
                    it[RoomJoinRequests.userId].value,
                    it[users.name],
                    it[RoomJoinRequests.requestedAt],
                )
            },
            total,
        )
    }

    suspend fun decide(
        roomId: UInt,
        requestId: UInt,
        caller: CallerPrincipal,
        request: RoomJoinDecisionRequest,
    ): RoomJoinDecisionOutcome = database.withRoomWrite(roomId, caller, ownerGuard) { fresh, standing ->
        if (standing.accessType != RoomAccessType.RESTRICTED_VISIBLE) throw ForbiddenException()
        val users = UserService.Users
        val requestRow = RoomJoinRequests.innerJoin(users).select(RoomJoinRequests.userId)
            .where {
                (RoomJoinRequests.id eq requestId) and (RoomJoinRequests.roomId eq roomId) and
                    (RoomJoinRequests.status eq RoomJoinRequestStatus.PENDING.name) and users.active()
            }
            .toList()
            .singleOrNull() ?: throw NotFoundException("Join request not found")
        val userId = requestRow[RoomJoinRequests.userId].value
        if (request.decision == RoomJoinDecision.REJECT) {
            RoomJoinRequests.update({ RoomJoinRequests.id eq requestId }) {
                it[status] = RoomJoinRequestStatus.REJECTED.name
            }
            return@withRoomWrite RoomJoinDecisionOutcome(request.decision, userId)
        }
        if (activeRole(roomId, userId) != null) {
            clearRequest(roomId, userId)
            return@withRoomWrite RoomJoinDecisionOutcome(request.decision, userId)
        }
        addMember(roomId, userId)
        RoomJoinDecisionOutcome(request.decision, userId, notices.membersAdded(roomId, fresh.userId, listOf(userId)))
    }

    private suspend fun addMember(roomId: UInt, userId: UInt) {
        val members = RoomService.RoomMembers
        val activeCount = members.innerJoin(UserService.Users).select(members.userId)
            .where { (members.roomId eq roomId) and UserService.Users.active() }
            .count()
        if (activeCount >= MAX_ROOM_MEMBERS) throw ConflictException("A room may have at most $MAX_ROOM_MEMBERS members")
        val stamp = nowMillis()
        members.insert {
            it[members.roomId] = roomId
            it[members.userId] = userId
            it[role] = RoomRole.MEMBER.name
            it[addedAt] = stamp
        }
        clearRequest(roomId, userId)
        notifyLive(LiveHint(LiveKind.ROOM_MEMBER_ADDED, roomId, targetUserIds = listOf(userId)))
    }

    private suspend fun activeRole(roomId: UInt, userId: UInt): RoomRole? {
        val members = RoomService.RoomMembers
        return members.innerJoin(UserService.Users).select(members.role)
            .where { (members.roomId eq roomId) and (members.userId eq userId) and UserService.Users.active() }
            .map { RoomRole.valueOf(it[members.role]) }
            .toList()
            .singleOrNull()
    }

    private suspend fun clearRequest(roomId: UInt, userId: UInt) {
        RoomJoinRequests.deleteWhere {
            (RoomJoinRequests.roomId eq roomId) and (RoomJoinRequests.userId eq userId)
        }
    }

    private companion object {
        val ownerGuard: RoomGuard = { _, standing ->
            if (standing == null) throw NotFoundException("Room not found")
            if (standing.role != RoomRole.OWNER) throw ForbiddenException()
        }
        val publicJoinGuard: RoomGuard = { _, standing ->
            if (standing == null) throw NotFoundException("Room not found")
            if (standing.role == null && standing.accessType == RoomAccessType.RESTRICTED_HIDDEN) {
                throw NotFoundException("Room not found")
            }
            if (standing.accessType != RoomAccessType.PUBLIC) throw ForbiddenException()
        }
        val visibleRequestGuard: RoomGuard = { _, standing ->
            if (standing == null || (standing.role == null && standing.accessType == RoomAccessType.RESTRICTED_HIDDEN)) {
                throw NotFoundException("Room not found")
            }
            if (standing.accessType != RoomAccessType.RESTRICTED_VISIBLE) throw ForbiddenException()
        }
    }
}
