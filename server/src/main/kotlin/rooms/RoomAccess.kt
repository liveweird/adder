package ch.nokillswit.rooms

import ch.nokillswit.audit.audit
import ch.nokillswit.authz.CallerPrincipal
import ch.nokillswit.authz.RoomStanding
import ch.nokillswit.authz.isAdmin
import ch.nokillswit.authz.requireRoomReader

/**
 * The route preamble every room route runs FIRST (after authentication): the caller's standing in [roomId], then the visibility
 * guard. A missing room and a non-member are both the uniform `404` (`requireRoomReader`); an ADMIN reads every room. When the room
 * EXISTS and the caller is neither a member nor an ADMIN the denial is audited (`room.access_denied`, the response stays `404`): the
 * only trace of someone probing room ids. Routes needing a role run `requireRoomManager` on the returned standing next, which keeps the
 * documented order: 401, visibility (404), role (403), then header and body (400).
 */
internal suspend fun RoomService.visibleStanding(caller: CallerPrincipal, roomId: UInt): RoomStanding {
    val standing = standing(roomId, caller.userId)
    if (standing != null && standing.role == null && !caller.isAdmin()) {
        audit("room.access_denied", "byUserId" to caller.userId.toLong(), "roomId" to roomId.toLong())
    }
    requireRoomReader(caller, standing)
    return checkNotNull(standing) { "requireRoomReader admits only a room that exists" }
}
