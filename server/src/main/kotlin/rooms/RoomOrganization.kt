package ch.nokillswit.rooms

import ch.nokillswit.infra.JsonUInt
import ch.nokillswit.infra.validation.requireNoDuplicates
import ch.nokillswit.infra.validation.sanitizeSingleLine
import io.ktor.server.plugins.BadRequestException
import java.util.UUID
import kotlinx.serialization.Serializable

const val MAX_ROOM_ORGANIZATION_GROUPS = 50
const val MAX_ROOM_ORGANIZATION_GROUP_NAME_LENGTH = 60
const val MAX_ROOM_ORGANIZATION_PLACEMENTS = 2_000

@Serializable
data class RoomOrganizationGroup(val id: String, val name: String)

@Serializable
data class RoomOrganizationPlacement(val roomId: JsonUInt, val groupId: String?)

@Serializable
data class RoomOrganizationUpdateRequest(
    val groups: List<RoomOrganizationGroup>,
    val rooms: List<RoomOrganizationPlacement>,
)

@Serializable
data class RoomOrganizationResponse(
    val revision: Long,
    val groups: List<RoomOrganizationGroup>,
    val rooms: List<RoomOrganizationPlacement>,
)

internal fun normalizedRoomOrganization(request: RoomOrganizationUpdateRequest): RoomOrganizationUpdateRequest {
    if (request.groups.size > MAX_ROOM_ORGANIZATION_GROUPS) {
        throw BadRequestException("groups must contain at most $MAX_ROOM_ORGANIZATION_GROUPS items")
    }
    if (request.rooms.size > MAX_ROOM_ORGANIZATION_PLACEMENTS) {
        throw BadRequestException("rooms must contain at most $MAX_ROOM_ORGANIZATION_PLACEMENTS items")
    }
    val groups = request.groups.map { group ->
        val canonicalId = runCatching { UUID.fromString(group.id).toString() }.getOrNull()
        if (canonicalId == null || canonicalId != group.id) throw BadRequestException("Group id must be a canonical UUID")
        val name = sanitizeSingleLine(group.name, "Group name")
        if (name.isBlank() || name.length > MAX_ROOM_ORGANIZATION_GROUP_NAME_LENGTH) {
            throw BadRequestException("Group name must be 1-$MAX_ROOM_ORGANIZATION_GROUP_NAME_LENGTH characters")
        }
        RoomOrganizationGroup(canonicalId, name)
    }
    requireNoDuplicates(groups.map { it.id }, "Group ids must be unique")
    requireNoDuplicates(groups.map { it.name }, "Group names must be unique", String::lowercase)
    requireNoDuplicates(request.rooms.map { it.roomId.toString() }, "Room ids must be unique")
    val groupIds = groups.mapTo(HashSet(), RoomOrganizationGroup::id)
    if (request.rooms.any { it.groupId != null && it.groupId !in groupIds }) {
        throw BadRequestException("Every room group must exist in groups")
    }
    return RoomOrganizationUpdateRequest(groups, request.rooms)
}
