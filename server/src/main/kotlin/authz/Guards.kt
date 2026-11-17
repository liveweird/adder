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
