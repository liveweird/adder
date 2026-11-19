package ch.nokillswit.rooms

import ch.nokillswit.authz.RoomRole
import ch.nokillswit.infra.paging.PageResponse
import ch.nokillswit.infra.validation.requireNameAndDescription
import ch.nokillswit.infra.validation.sanitizeSingleLine
import ch.nokillswit.infra.validation.sanitizedDescription
import ch.nokillswit.users.validateLanguage
import io.ktor.server.plugins.BadRequestException
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

/**
 * A room name is shared vocabulary that people look up and that must be unique case-insensitively, so it may not hold characters
 * that render as nothing: Unicode format characters (category Cf - zero-width space/joiner, bidi marks and overrides, the BOM) and the
 * line and paragraph separators U+2028/U+2029. Otherwise "Incident" and "Incident" + U+200B are two rooms that look identical
 * (impersonation), and a bidi override reorders the name on screen. Applies to room names only (not `sanitizeSingleLine`, which every
 * other feature shares). Ordinary letters, digits, punctuation, emoji and spaces pass.
 */
private fun requireVisibleName(name: String) {
    val hidden = name.codePoints().anyMatch {
        Character.getType(it) == Character.FORMAT.toInt() || it == LINE_SEPARATOR || it == PARAGRAPH_SEPARATOR
    }
    if (hidden) throw BadRequestException("Name must not contain invisible formatting characters")
}

private const val LINE_SEPARATOR = 0x2028
private const val PARAGRAPH_SEPARATOR = 0x2029

/** The room rules - enforced by the route AND re-checked by the service. */
fun validateRoomCreate(request: RoomCreateRequest) {
    requireNameAndDescription(request.name, request.description, MAX_ROOM_NAME_LENGTH, MAX_ROOM_DESCRIPTION_LENGTH)
    requireVisibleName(request.name)
    validateLanguage(request.language)
}

fun validateRoomUpdate(request: RoomUpdateRequest) {
    requireNameAndDescription(request.name, request.description, MAX_ROOM_NAME_LENGTH, MAX_ROOM_DESCRIPTION_LENGTH)
    requireVisibleName(request.name)
}

/**
 * The answer of `RoomService.readableRoomIds`: the rooms a caller may read, and the EXISTING rooms they may not (never an id that
 * matches no room).
 */
data class ReadableRooms(val readable: Set<UInt>, val deniedExisting: Set<UInt>)
