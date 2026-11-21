package ch.nokillswit.threads

import ch.nokillswit.authz.caller
import ch.nokillswit.infra.paging.toPage
import ch.nokillswit.plugins.RateLimits
import ch.nokillswit.rooms.RoomService
import ch.nokillswit.rooms.RoomServiceKey
import ch.nokillswit.rooms.RoomsRoute
import ch.nokillswit.rooms.visibleStanding
import io.ktor.http.HttpStatusCode
import io.ktor.resources.Resource
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.resources.get
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable

/** `/api/v1/rooms/{id}/search`: full-text search of one room's threads and messages (a child of [RoomsRoute.Id]). */
@Serializable
@Resource("search")
class RoomSearchRoute(val parent: RoomsRoute.Id)

/** `/api/v1/search`: full-text search across the rooms the caller is an ACTIVE member of. */
@Serializable
@Resource("/api/v1/search")
class SearchRoute

fun Application.configureThreadSearchRoutes() {
    val roomService = attributes[RoomServiceKey]
    val searchService = attributes[ThreadSearchServiceKey]

    routing {
        authenticate {
            roomSearch(roomService, searchService)
            globalSearch(searchService)
        }
    }
}

/**
 * `GET /rooms/{id}/search` (reader): the guard order is 401 from `authenticate`; the per-USER `search` bucket (before every guard, so a
 * refused search spends a token too and a probe of foreign room ids is throttled); visibility (404 for a missing room and a non-member
 * alike, audited `room.access_denied`); the parameters (400); then the query. A non-member's bad `q` is still a 404. `q` is optional
 * only with `watching=true` (the caller's watched threads of the room, no query). A search is a read: no audit line of its own, and the
 * term is never logged or put into an audit line or a problem detail.
 */
private fun Route.roomSearch(roomService: RoomService, searchService: ThreadSearchService) {
    rateLimit(RateLimitName(RateLimits.SEARCH)) {
        get<RoomSearchRoute> { route ->
            val caller = call.caller()
            val roomId = route.parent.id
            roomService.visibleStanding(caller, roomId)
            val params = call.parseSearchParams()
            val result = searchService.searchRoom(roomId, caller.userId, params.q, params.closed, params.paging, params.watching)
            call.respond(HttpStatusCode.OK, params.paging.toPage(result.items, result.total))
        }
    }
}

/**
 * `GET /search` (any authenticated caller, no room guard): the guard order is 401 from `authenticate`; the SAME per-USER `search`
 * bucket as the room search (one budget for both, before anything else); the parameters (400); then the query over the rooms the
 * caller is an ACTIVE member of, an ADMIN's too (`authorization.md`: membership is the scope, the role widens nothing here). There is no
 * room in the request, so nothing about any room can be refused, probed or disclosed: a caller with no rooms gets an empty page.
 * `watching=true` with no `q` is the cross-room "Threads I watch" view, which starts from the caller's rooms. A search is a read: no
 * audit line of its own, and the term is never logged or put into an audit line or a problem detail.
 */
private fun Route.globalSearch(searchService: ThreadSearchService) {
    rateLimit(RateLimitName(RateLimits.SEARCH)) {
        get<SearchRoute> {
            val caller = call.caller()
            val params = call.parseSearchParams()
            val result = searchService.searchMemberRooms(caller.userId, params.q, params.closed, params.paging, params.watching)
            call.respond(HttpStatusCode.OK, params.paging.toPage(result.items, result.total))
        }
    }
}
