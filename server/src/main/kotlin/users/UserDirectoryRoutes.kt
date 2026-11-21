package ch.nokillswit.users

import ch.nokillswit.authz.caller
import ch.nokillswit.authz.orNotFound
import io.ktor.http.HttpStatusCode
import io.ktor.server.resources.get
import io.ktor.server.response.respond
import io.ktor.server.routing.Route

/**
 * `GET /api/v1/users/directory/{id}` (1.2 D7): the directory entry `{id, name, email}` of ONE active user, for the profile page every
 * signed-in user may open. Mounted by `userDirectory` INSIDE its per-USER `directory` bucket, so it spends the same 120 a minute as the
 * list; no guard (so no 403; the 401 is `authenticate`'s). An unknown id and a soft-deleted account are the same `404`, so the answer
 * discloses nothing the directory list does not (an ACTIVE account's name and email, 0.2 D2). The same service projection as the list;
 * `GET /users/{id}` stays self-or-ADMIN. A read: no audit event (a 429 is the generic `rate_limit.exceeded`).
 */
internal fun Route.userDirectoryEntry(userService: UserService) {
    get<Users.Directory.Entry> { route ->
        // No guard to run, but every route reads the caller first (authorization.md "Guards"): a principal that does not parse is a 401.
        call.caller()
        call.respond(HttpStatusCode.OK, userService.directoryEntry(route.id).orNotFound("User"))
    }
}
