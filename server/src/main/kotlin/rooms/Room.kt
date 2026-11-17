package ch.nokillswit.rooms

import ch.nokillswit.authz.RoomRole
import ch.nokillswit.infra.paging.PageResponse
import ch.nokillswit.infra.validation.requireNameAndDescription
import ch.nokillswit.infra.validation.sanitizeSingleLine
import ch.nokillswit.infra.validation.sanitizedDescription
import ch.nokillswit.users.validateLanguage
import kotlinx.serialization.Serializable

/**
 * Rooms (V13): the members-only place people work in. Any signed-in user creates one (becoming its OWNER) from a workflow template
 * whose statuses and fields the room COPIES; afterwards the room edits its own copy. A non-member gets a uniform `404`; an ADMIN reads
 * and manages every room; the room's OWNER or an ADMIN edits it, archives it and unarchives it; only an ADMIN deletes (soft) it.
 * `chat-model.md` "Rooms" holds the rules.
 */
const val MAX_ROOM_NAME_LENGTH = 80
const val MAX_ROOM_DESCRIPTION_LENGTH = 500

/** `language` picks the template label each status, field and option is copied with (default `en`, EN fallback per label). */
@Serializable
data class RoomCreateRequest(
    val name: String,
    val templateId: UInt,
    val description: String? = null,
    val language: String? = null,
)

/** The metadata PUT is a full replace: an omitted description clears it. */
@Serializable
data class RoomUpdateRequest(
    val name: String,
    val description: String? = null,
)

/** One list row. [role] is the CALLER's role in the room: null for an ADMIN who is not a member. */
@Serializable
data class RoomListItem(
    val id: UInt,
    val name: String,
    val description: String?,
    val archived: Boolean,
    val role: RoomRole?,
    /** The member count, ACTIVE users only. */
    val memberCount: Int,
    val lastActivityAt: Long,
    val createdAt: Long,
)

@Serializable
data class RoomResponse(
    val id: UInt,
    val name: String,
    val description: String?,
    val archived: Boolean,
    val archivedAt: Long?,
    val role: RoomRole?,
    val memberCount: Int,
    /** The room workflow's optimistic-concurrency token (`X-Expected-Revision` of its PUT); starts at 1. */
    val workflowRevision: Long,
    val lastActivityAt: Long,
    val createdAt: Long,
    val updatedAt: Long,
)

typealias RoomPageResponse = PageResponse<RoomListItem>

/** `member` = true: only rooms the caller is in; false: only readable rooms the caller is NOT in; null: every readable room. */
data class RoomListFilter(
    val name: String? = null,
    val archived: Boolean? = null,
    val member: Boolean? = null,
)

data class RoomListResult(val items: List<RoomListItem>, val total: Long)

/** Trims the scalars (a control character is a 400); the language is validated by [validateRoomCreate]. */
fun sanitizedRoomCreate(request: RoomCreateRequest): RoomCreateRequest = request.copy(
    name = sanitizeSingleLine(request.name, "Name"),
    description = sanitizedDescription(request.description),
)

fun sanitizedRoomUpdate(request: RoomUpdateRequest): RoomUpdateRequest = RoomUpdateRequest(
    name = sanitizeSingleLine(request.name, "Name"),
    description = sanitizedDescription(request.description),
)

/** The room rules - enforced by the route AND re-checked by the service. */
fun validateRoomCreate(request: RoomCreateRequest) {
    requireNameAndDescription(request.name, request.description, MAX_ROOM_NAME_LENGTH, MAX_ROOM_DESCRIPTION_LENGTH)
    validateLanguage(request.language)
}

fun validateRoomUpdate(request: RoomUpdateRequest) {
    requireNameAndDescription(request.name, request.description, MAX_ROOM_NAME_LENGTH, MAX_ROOM_DESCRIPTION_LENGTH)
}
