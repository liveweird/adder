package ch.nokillswit.rooms

import ch.nokillswit.audit.audit
import ch.nokillswit.authz.CallerPrincipal
import ch.nokillswit.authz.RoomAccessType
import ch.nokillswit.authz.NotFoundException
import ch.nokillswit.authz.RoomStanding
import ch.nokillswit.authz.isAdmin
import ch.nokillswit.authz.requireRoomReader

/**
 * The route preamble every room route runs FIRST (after authentication): the caller's standing in [roomId], then the visibility
 * guard. A missing room and a caller without content access are both the uniform `404` (`requireRoomReader`); an ADMIN outside a hidden
 * room is denied too. When the room exists and the caller cannot read it, the denial is audited (`room.access_denied`): the
 * only trace of someone probing room ids. The line is COALESCED per user ([RoomService.denialAudit], the `rate_limit.exceeded` idiom):
 * at most one a minute, carrying the first denied `roomId` and the number of denials folded since the last line (`suppressed`), so a
 * member sweeping room ids cannot flood the audit log (every denial still answers the same `404`). Routes needing a role run
 * `requireRoomManager` on the returned standing next, which keeps the documented order: 401, visibility (404), role (403), then header
 * and body (400).
 */
internal suspend fun RoomService.visibleStanding(caller: CallerPrincipal, roomId: UInt): RoomStanding {
    val standing = standing(roomId, caller.userId)
    if (
        standing != null && standing.role == null &&
        (!caller.isAdmin() || standing.accessType == RoomAccessType.RESTRICTED_HIDDEN)
    ) {
        denialAudit.admit("room.access_denied:${caller.userId}")?.let { suppressed ->
            audit("room.access_denied", "byUserId" to caller.userId.toLong(), "roomId" to roomId.toLong(), "suppressed" to suppressed)
        }
    }
    requireRoomReader(caller, standing)
    return checkNotNull(standing) { "requireRoomReader admits only a room that exists" }
}

/** A member or the signed-in discoverer of a public/visible room; hidden outsiders get the uniform audited 404. */
internal suspend fun RoomService.discoverableStanding(caller: CallerPrincipal, roomId: UInt): RoomStanding {
    val standing = standing(roomId, caller.userId)
    if (standing != null && (standing.role != null || standing.accessType != RoomAccessType.RESTRICTED_HIDDEN)) return standing
    if (standing != null) {
        denialAudit.admit("room.access_denied:${caller.userId}")?.let { suppressed ->
            audit("room.access_denied", "byUserId" to caller.userId.toLong(), "roomId" to roomId.toLong(), "suppressed" to suppressed)
        }
    }
    throw NotFoundException("Room not found")
}
