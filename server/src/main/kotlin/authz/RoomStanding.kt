package ch.nokillswit.authz

import kotlinx.serialization.Serializable

/** A member's role in a room (`room_members.role`, V13): the Kotlin enum is the whitelist, UPPER_CASE on the wire. */
@Serializable
enum class RoomRole { OWNER, MEMBER }

/** A room's immutable discovery and enrollment policy. */
@Serializable
enum class RoomAccessType { PUBLIC, RESTRICTED_VISIBLE, RESTRICTED_HIDDEN }

/**
 * What the room guards (`requireRoomReader`, `requireRoomManager`) decide on: the caller's role in the room (null = not a member) and
 * whether the room is archived. A null `RoomStanding` stands for "no such room" (missing or soft-deleted), so the guards keep a missing
 * room and a non-member indistinguishable. Plain values keep the guards DB-free (`GuardsTest` is pure) and keep `authz` free of any
 * reference to the `rooms` package: the room feature imports `authz`, never the reverse.
 */
data class RoomStanding(
    val role: RoomRole?,
    val archived: Boolean,
    val accessType: RoomAccessType = RoomAccessType.RESTRICTED_HIDDEN,
)
