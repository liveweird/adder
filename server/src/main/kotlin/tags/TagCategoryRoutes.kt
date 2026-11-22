package ch.nokillswit.tags

import ch.nokillswit.audit.audit
import ch.nokillswit.authz.caller
import ch.nokillswit.authz.TooManyRequestsException
import ch.nokillswit.authz.orNotFound
import ch.nokillswit.authz.requireAdmin
import ch.nokillswit.infra.ProblemTypes
import ch.nokillswit.infra.paging.parsePaging
import ch.nokillswit.infra.paging.toPage
import ch.nokillswit.infra.revision.expectedRevision
import ch.nokillswit.plugins.RateLimits
import ch.nokillswit.plugins.isLockNotAvailable
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.resources.Resource
import io.ktor.server.application.*
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.request.receive
import io.ktor.server.resources.delete
import io.ktor.server.resources.get
import io.ktor.server.resources.href
import io.ktor.server.resources.post
import io.ktor.server.resources.put
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable

@Serializable
@Resource("/api/v1/tag-categories")
class TagCategoriesRoute {
    @Serializable
    @Resource("{id}")
    class Id(val parent: TagCategoriesRoute = TagCategoriesRoute(), val id: UInt)
}

/**
 * Runs one registry write ([TagCategoryService]'s `lockingTransaction`) and answers a wait for the table lock that outran the bound
 * (SQLSTATE `55P03`, `TAG_REGISTRY_LOCK_WAIT_MILLIS`) as the room lock does: `429` of the shared type `urn:adder:problem:room-busy` with
 * `Retry-After: 1` (a registry-specific detail; nothing was written), audited `tag_category.busy`. Any other failure passes through.
 */
private suspend fun <T> registryWrite(byUserId: UInt, write: suspend () -> T): T = try {
    write()
} catch (failure: Exception) {
    if (!failure.isLockNotAvailable()) throw failure
    audit("tag_category.busy", "byUserId" to byUserId.toLong())
    throw TooManyRequestsException(
        "The tag registry is busy - retry in a moment",
        problemType = ProblemTypes.ROOM_BUSY,
        retryAfterSeconds = 1,
    )
}

/**
 * The tag registry (`/api/v1/tag-categories`, Hammurabi's, itself Toadie's): the read is any-authenticated (the thread tag picker and the
 * Tags page),
 * every mutation ADMIN-only with the guard BEFORE the header, the body and the id lookup, so a non-admin probe gets a uniform 403 whether
 * or not the id exists (guard-before-read, as for teams). The PUT and the DELETE take `X-Expected-Revision` (400 missing, 404 unknown id,
 * then 409 stale). Every mutation audits after its commit.
 */
fun Application.configureTagCategoryRoutes() {
    val service = attributes[TagCategoryServiceKey]

    routing {
        authenticate {
            // A page of the registry is up to about half a megabyte (100 categories of 100 names of 40 characters, `TagCategory.kt`), so
            // the read rides the per-USER `chat-read` bucket with the room reads (before every check, as they do): the picker and the
            // filter panel read it a few times a minute at the very most, a script reads it at 5 a second.
            rateLimit(RateLimitName(RateLimits.CHAT_READ)) {
                get<TagCategoriesRoute> {
                    call.caller()
                    val paging = call.parsePaging(sortable = TAG_CATEGORY_SORT_FIELDS, defaultSort = TAG_CATEGORY_DEFAULT_SORT)
                    val result = service.list(paging)
                    call.respond(HttpStatusCode.OK, paging.toPage(result.items, result.total))
                }
            }
            post<TagCategoriesRoute> {
                val caller = call.caller()
                requireAdmin(caller)
                val request = sanitizedTagCategoryRequest(call.receive())
                validateTagCategoryRequest(request)
                val created = registryWrite(caller.userId) { service.create(request) }
                audit(
                    "tag_category.created",
                    "byUserId" to caller.userId.toLong(),
                    "categoryId" to created.id.toLong(),
                    "name" to created.name,
                    "tags" to created.tags.size,
                )
                call.response.header(HttpHeaders.Location, call.application.href(TagCategoriesRoute.Id(id = created.id)))
                call.respond(HttpStatusCode.Created, created)
            }
            put<TagCategoriesRoute.Id> { route ->
                val caller = call.caller()
                requireAdmin(caller)
                val expected = call.expectedRevision()
                val request = sanitizedTagCategoryRequest(call.receive())
                validateTagCategoryRequest(request)
                registryWrite(caller.userId) { service.update(route.id, expected, request) }.orNotFound("Tag category")
                audit(
                    "tag_category.updated",
                    "byUserId" to caller.userId.toLong(),
                    "categoryId" to route.id.toLong(),
                    "name" to request.name,
                    "tags" to request.tags.size,
                    // The stored revision is `expected` (the compare passed under the lock) and the write stored one more: the NEW one.
                    "revision" to expected + 1,
                )
                call.respond(HttpStatusCode.NoContent)
            }
            delete<TagCategoriesRoute.Id> { route ->
                val caller = call.caller()
                requireAdmin(caller)
                val expected = call.expectedRevision()
                registryWrite(caller.userId) { service.delete(route.id, expected) }.orNotFound("Tag category")
                audit("tag_category.deleted", "byUserId" to caller.userId.toLong(), "categoryId" to route.id.toLong())
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}
