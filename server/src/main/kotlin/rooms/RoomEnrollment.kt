package ch.nokillswit.rooms

import ch.nokillswit.authz.RoomAccessType
import ch.nokillswit.infra.paging.PageResponse
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.dao.id.UIntIdTable

@Serializable
enum class RoomJoinRequestStatus { PENDING, REJECTED }

@Serializable
enum class RoomJoinDecision { APPROVE, REJECT }

@Serializable
data class RoomAccessResponse(
    val id: UInt,
    val name: String,
    val description: String?,
    val accessType: RoomAccessType,
    val archived: Boolean,
    val requestStatus: RoomJoinRequestStatus?,
)

@Serializable
data class RoomJoinRequestResponse(val id: UInt, val userId: UInt, val name: String, val requestedAt: Long)

typealias RoomJoinRequestPageResponse = PageResponse<RoomJoinRequestResponse>

data class RoomJoinRequestListResult(val items: List<RoomJoinRequestResponse>, val total: Long)

@Serializable
data class RoomJoinDecisionRequest(val decision: RoomJoinDecision)

data class RoomJoinDecisionOutcome(val decision: RoomJoinDecision, val userId: UInt)

/** V36's owner-reviewed enrollment record. A direct invitation or approval removes it. */
internal object RoomJoinRequests : UIntIdTable("room_join_requests") {
    val roomId = reference("room_id", RoomService.Rooms)
    val userId = reference("user_id", ch.nokillswit.users.UserService.Users)
    val status = varchar("status", 10)
    val requestedAt = long("requested_at")
}
