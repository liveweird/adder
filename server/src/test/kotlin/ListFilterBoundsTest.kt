package ch.nokillswit

import ch.nokillswit.teams.MAX_TEAM_NAME_LENGTH
import ch.nokillswit.teams.TeamPageResponse
import ch.nokillswit.users.MAX_EMAIL_LENGTH
import ch.nokillswit.users.MAX_NAME_LENGTH
import ch.nokillswit.users.UserRole
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The `name` / `email` filters of the user and team lists are bounded like the rooms', members' and templates' (one parser,
 * `optionalBoundedString`): the stored column's own limit, in code points after trimming; one past it is a `400`, the limit itself
 * a `200`. The rooms, members and templates cases live with their own suites.
 */
class ListFilterBoundsTest {

    @Test
    fun `the user list bounds its name and email filters at the stored limits`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("filterbounds", UserRole.ADMIN)
        assertEquals(HttpStatusCode.OK, admin.get("/api/v1/users?name=${"x".repeat(MAX_NAME_LENGTH)}").status)
        assertEquals(HttpStatusCode.BadRequest, admin.get("/api/v1/users?name=${"x".repeat(MAX_NAME_LENGTH + 1)}").status)
        assertEquals(HttpStatusCode.OK, admin.get("/api/v1/users?email=${"x".repeat(MAX_EMAIL_LENGTH)}").status)
        assertEquals(HttpStatusCode.BadRequest, admin.get("/api/v1/users?email=${"x".repeat(MAX_EMAIL_LENGTH + 1)}").status)
    }

    @Test
    fun `the team list bounds its name filter at the stored limit`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("filterbounds")
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/teams?name=${"x".repeat(MAX_TEAM_NAME_LENGTH)}").status)
        assertEquals(HttpStatusCode.BadRequest, client.get("/api/v1/teams?name=${"x".repeat(MAX_TEAM_NAME_LENGTH + 1)}").status)
    }

    @Test
    fun `a filter is trimmed before it matches - padding neither fails nor widens it`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("filtertrim")
        val name = "Annabel-${UUID.randomUUID().toString().substring(0, 8)}"
        TestTeams.seed(name)
        val padded = client.get("/api/v1/teams?name=%20${name.lowercase()}%20").body<TeamPageResponse>()
        assertEquals(listOf(name), padded.items.map { it.name })
    }
}
