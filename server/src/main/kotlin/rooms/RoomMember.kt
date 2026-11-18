package ch.nokillswit.rooms

import ch.nokillswit.authz.RoomRole
import ch.nokillswit.infra.paging.PageResponse
import io.ktor.server.plugins.BadRequestException
import kotlinx.serialization.Serializable

/**
 * Room membership (V13 `room_members`): a person or a whole team (a SNAPSHOT of the active roster at that moment) is added as MEMBER;
 * promotion is a separate step; the last OWNER whose user is active can never leave, be demoted or be removed.
 * `chat-model.md` "Membership".
 */
const val MAX_ROOM_MEMBERS = 500
const val MAX_USER_IDS_PER_ADD = 200

/** One list row: an ACTIVE user's membership. */
@Serializable
data class RoomMemberResponse(
    val userId: UInt,
    val name: String,
    val email: String,
    val role: RoomRole,
    val addedAt: Long,
)

typealias RoomMemberPageResponse = PageResponse<RoomMemberResponse>

/** Both filters optional: [name] is a case- and accent-insensitive substring of the member's display name. */
data class RoomMemberListFilter(val role: RoomRole? = null, val name: String? = null)

data class RoomMemberListResult(val items: List<RoomMemberResponse>, val total: Long)

/** At least one of [userIds] (at most [MAX_USER_IDS_PER_ADD]) and [teamId]; always added as MEMBER. */
@Serializable
data class RoomMembersAddRequest(
    val userIds: List<UInt>? = null,
    val teamId: UInt? = null,
)

/** [added] people got a membership row now, [alreadyMembers] of the requested (and the team's) people had one. */
@Serializable
data class RoomMembersAddResponse(val added: Int, val alreadyMembers: Int)

@Serializable
data class RoomMemberRoleRequest(val role: RoomRole)

/** What the add wrote: [addedUserIds] in order, and which of them came ONLY through the team ([viaTeamUserIds]) for the audit line. */
data class RoomMembersAddOutcome(val addedUserIds: List<UInt>, val viaTeamUserIds: Set<UInt>, val alreadyMembers: Int)

/** The rules of the add body - enforced by the route AND re-checked by the service. */
fun validateRoomMembersAdd(request: RoomMembersAddRequest) {
    val ids = request.userIds.orEmpty()
    if (ids.isEmpty() && request.teamId == null) throw BadRequestException("Name at least one of userIds and teamId")
    if (ids.size > MAX_USER_IDS_PER_ADD) throw BadRequestException("userIds takes at most $MAX_USER_IDS_PER_ADD ids")
}

/** The `name` filter of the member list: a substring, so anything longer than a 50-character user name plus slack is a `400`. */
const val MAX_MEMBER_NAME_FILTER_LENGTH = 100
