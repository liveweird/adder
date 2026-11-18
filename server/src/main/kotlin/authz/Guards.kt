package ch.nokillswit.authz

import ch.nokillswit.users.Feature
import ch.nokillswit.users.UserRole

fun CallerPrincipal.isAdmin(): Boolean = UserRole.ADMIN in roles

fun requireAdmin(caller: CallerPrincipal) {
    if (!caller.isAdmin()) throw ForbiddenException("Admin role required")
}

/**
 * The area gate for per-user feature flags (V5): run as the FIRST guard of a gated route,
 * before any read, so a disabled caller gets a uniform 403 even for a missing id. No route
 * is gated yet — MFA (the only flag today) is login-scoped and read off the DB record; the
 * first gateable feature area brings the first call site. The set rides the JWT, so a
 * change takes effect at the next refresh (≤15 min) or login.
 */
fun requireFeatureEnabled(caller: CallerPrincipal, feature: Feature) {
    if (feature in caller.disabledFeatures) {
        throw ForbiddenException("The ${feature.name} feature is disabled for this account")
    }
}

fun requireSelfOrAdmin(caller: CallerPrincipal, targetUserId: UInt) {
    if (caller.isAdmin()) return
    if (caller.userId != targetUserId) throw ForbiddenException("Caller may only act on their own user")
}

/**
 * ADMIN, or the team's current ACTIVE manager (V6). [isManager] answers false for a missing or deleted team too,
 * so run this as the FIRST guard of a team-scoped write, before any read: every other caller gets a uniform 403
 * whether or not the id exists. The lookup is injected so the guard stays DB-free (`GuardsTest` is pure). Its first consumer is
 * the team roster (`PUT`/`DELETE /teams/{id}/members/{userId}`): the route runs it first, and `TeamService` re-runs the same guard
 * inside the write transaction after `lockActiveForWrite`, so a grant revoked while the write waited for the lock is refused.
 */
suspend fun requireTeamManagerOrAdmin(
    caller: CallerPrincipal,
    teamId: UInt,
    isManager: suspend (teamId: UInt, userId: UInt) -> Boolean,
) {
    if (caller.isAdmin()) return
    if (!isManager(teamId, caller.userId)) throw ForbiddenException("Team manager or admin role required")
}

/**
 * The visibility guard of a room route: a member of the room, or an ADMIN (who reads every room). A missing room ([standing] null) and a
 * non-member are the SAME `404` (`NotFoundException`), so a room's existence is never disclosed (the third existence idiom,
 * `authorization.md`: "hidden resource, uniform 404"). DB-free: the caller's standing is read by the route (and re-read under the
 * room lock for a write) and passed in. The route audits `room.access_denied` for a room that exists; this guard only decides.
 */
fun requireRoomReader(caller: CallerPrincipal, standing: RoomStanding?) {
    if (standing == null || (standing.role == null && !caller.isAdmin())) throw NotFoundException("Room not found")
}

/**
 * The management guard of a room route: [requireRoomReader] first (so an outsider's answer is the `404`, never the `403`), then the
 * room's OWNER or an ADMIN, else a `403`. Run it as the guard of the room write ([RoomStanding] re-read under the room lock), so a caller
 * demoted while the write waited for the lock is refused.
 */
fun requireRoomManager(caller: CallerPrincipal, standing: RoomStanding?) {
    requireRoomReader(caller, standing)
    if (standing?.role != RoomRole.OWNER && !caller.isAdmin()) throw ForbiddenException("Room owner or admin role required")
}

/**
 * Whether [caller] is a STEWARD of a thread owned by [threadOwnerId] (D1, `chat-model.md` "Who may do what"): the thread owner while
 * a member, the room's OWNER or an ADMIN, i.e. whoever can undo a close. A thread owner who has left the room is no steward (a removed
 * owner keeps the thread, inert). DB-free; the pure status rules take the verdict as a flag.
 */
fun isThreadSteward(caller: CallerPrincipal, standing: RoomStanding?, threadOwnerId: UInt): Boolean =
    caller.isAdmin() || standing?.role == RoomRole.OWNER || (standing?.role != null && caller.userId == threadOwnerId)

/**
 * The stewardship guard of a thread route (close, reopen, CLOSED to CLOSED, passing ownership): [requireRoomReader] first (an outsider's
 * answer is the `404`, never the `403`), then [isThreadSteward], else a `403`. The route runs it on a lock-free read of the owner
 * (a missing thread is `404` there) and the write runs it again on the fresh caller, standing and thread row under the room lock.
 */
fun requireThreadSteward(caller: CallerPrincipal, standing: RoomStanding?, threadOwnerId: UInt) {
    requireRoomReader(caller, standing)
    if (!isThreadSteward(caller, standing, threadOwnerId)) {
        throw ForbiddenException("Thread owner, room owner or admin role required")
    }
}

/**
 * The speech guard of a room (start a thread, post a message): [requireRoomReader] first (an outsider's answer is the `404`, never the
 * `403`), then an actual MEMBER, else a `403`. An ADMIN reads and manages every room but only members speak, so an ADMIN who is not a
 * member is refused here and joins through the Members page first (`chat-model.md` "Who may do what"). Run it as the guard of the
 * write ([RoomStanding] re-read under the room lock), so a caller removed while the write waited is refused with the `404`.
 */
fun requireRoomMember(caller: CallerPrincipal, standing: RoomStanding?) {
    requireRoomReader(caller, standing)
    if (standing?.role == null) throw ForbiddenException("Room membership required")
}

/**
 * The edit guard of a message (`PUT /rooms/{id}/messages/{messageId}`): [requireRoomReader] first (an outsider's answer is the `404`,
 * never the `403`), then the message's own AUTHOR while still a member, else a `403`. Nobody edits someone else's words, a room
 * OWNER and an ADMIN included (D2: they may delete, never edit), and an author who has left the room (or an ADMIN author who never
 * was a member) no longer speaks. The route runs it on a lock-free read of the author (a missing or deleted message is `404`
 * there) and the write runs it again on the fresh caller, standing and row under the room lock.
 */
fun requireMessageAuthor(caller: CallerPrincipal, standing: RoomStanding?, authorId: UInt) {
    requireRoomReader(caller, standing)
    if (standing?.role == null || caller.userId != authorId) throw ForbiddenException("Only the author may edit a message")
}

/**
 * The delete guard of a message (`DELETE /rooms/{id}/messages/{messageId}`, D2): [requireRoomReader] first, then the AUTHOR while still a
 * member, the room's OWNER or an ADMIN (a moderator removal: incident chat leaks credentials and the author may be asleep), else a
 * `403`. Run like [requireMessageAuthor]: on a lock-free read at the route, again on the fresh rows under the room lock.
 */
fun requireMessageRemover(caller: CallerPrincipal, standing: RoomStanding?, authorId: UInt) {
    requireRoomReader(caller, standing)
    val isAuthorMember = standing?.role != null && caller.userId == authorId
    if (!caller.isAdmin() && standing?.role != RoomRole.OWNER && !isAuthorMember) {
        throw ForbiddenException("Message author, room owner or admin role required")
    }
}
