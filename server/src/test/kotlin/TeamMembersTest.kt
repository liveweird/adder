package ch.nokillswit

import ch.nokillswit.teams.MAX_TEAM_MEMBERS
import ch.nokillswit.teams.TeamCreateRequest
import ch.nokillswit.teams.TeamPageResponse
import ch.nokillswit.teams.TeamResponse
import ch.nokillswit.teams.TeamUpdateRequest
import ch.nokillswit.users.UserRole
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.put
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking

/**
 * The team roster (V10): `PUT`/`DELETE /api/v1/teams/{id}/members/{userId}` for an ADMIN or the team's active manager (the guard
 * first, uniform 403, re-checked under the team row lock), reads resolved through ACTIVE users, the 200-member cap and the audit
 * trail. Teams and users are SHARED suite state, so every test mints UNIQUE rows.
 */
class TeamMembersTest {

    // Everything a test mints is soft-deleted afterwards (the shared container keeps its user and team lists small).
    private val mintedUsers = mutableListOf<UInt>()
    private val mintedTeams = mutableListOf<UInt>()

    @AfterTest
    fun softDeleteWhatTheTestMinted() = runBlocking {
        mintedTeams.forEach { TestTeams.service.delete(it) }
        mintedUsers.forEach { TestUsers.softDelete(it) }
    }

    private fun name(prefix: String) = "$prefix-${UUID.randomUUID().toString().substring(0, 8)}"

    private class Actor(val id: UInt, val client: HttpClient)

    private suspend fun io.ktor.server.testing.ApplicationTestBuilder.user(prefix: String, display: String = "Test"): Actor {
        val email = uniqueEmail(prefix)
        val id = TestUsers.seed(email = email, password = "pw", role = UserRole.USER, name = display)
        mintedUsers += id
        return Actor(id, authedClient(email, "pw"))
    }

    private suspend fun HttpClient.team(managerUserId: UInt? = null): UInt =
        postJson("/api/v1/teams", TeamCreateRequest(name = "roster-${UUID.randomUUID()}".take(40), managerUserId = managerUserId))
            .body<TeamResponse>().id.also { mintedTeams += it }

    private suspend fun HttpClient.roster(teamId: UInt): TeamResponse = get("/api/v1/teams/$teamId").body()

    private suspend fun HttpClient.memberCount(teamId: UInt): Int =
        get("/api/v1/teams?sort=-id&pageSize=100").body<TeamPageResponse>().items.single { it.id == teamId }.memberCount

    private fun path(teamId: UInt, userId: UInt) = "/api/v1/teams/$teamId/members/$userId"

    @Test
    fun `an admin and the team's manager edit the roster, another team's manager and a stranger get a uniform 403`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("roster-admin", UserRole.ADMIN)
        val manager = user("roster-mgr")
        val otherManager = user("roster-othermgr")
        val stranger = user("roster-stranger")
        val target = user("roster-target", "Target Person")
        val teamId = admin.team(manager.id)
        val otherTeamId = admin.team(otherManager.id)
        val unknownTeam = 999_999u

        // Writes: 403 for the strangers whether or not the team exists (guard first), and nothing changes.
        for (actor in listOf(otherManager, stranger)) {
            assertEquals(HttpStatusCode.Forbidden, actor.client.put(path(teamId, target.id)).status)
            assertEquals(HttpStatusCode.Forbidden, actor.client.delete(path(teamId, target.id)).status)
            assertEquals(HttpStatusCode.Forbidden, actor.client.put(path(unknownTeam, target.id)).status, "unknown team: uniform")
            assertEquals(HttpStatusCode.Forbidden, actor.client.delete(path(unknownTeam, target.id)).status, "unknown team: uniform")
        }
        assertEquals(HttpStatusCode.Forbidden, manager.client.put(path(otherTeamId, target.id)).status, "a manager of another team")
        assertEquals(HttpStatusCode.Forbidden, manager.client.put(path(unknownTeam, target.id)).status)
        assertTrue(admin.roster(teamId).members.isEmpty())
        assertTrue(admin.roster(otherTeamId).members.isEmpty())

        // The manager adds and removes on their own team; the admin on any, and the admin's unknown team is a 404.
        assertEquals(HttpStatusCode.NoContent, manager.client.put(path(teamId, target.id)).status)
        assertEquals(listOf(target.id), admin.roster(teamId).members.map { it.userId })
        assertEquals(HttpStatusCode.NoContent, manager.client.delete(path(teamId, target.id)).status)
        assertEquals(HttpStatusCode.NoContent, admin.put(path(otherTeamId, target.id)).status)
        assertEquals(HttpStatusCode.NoContent, admin.delete(path(otherTeamId, target.id)).status)
        assertEquals(HttpStatusCode.NotFound, admin.put(path(unknownTeam, target.id)).status)
        assertEquals(HttpStatusCode.NotFound, admin.delete(path(unknownTeam, target.id)).status)

        // Reading stays open to everyone, a stranger included.
        assertEquals(HttpStatusCode.OK, stranger.client.get("/api/v1/teams/$teamId").status)
    }

    @Test
    fun `a replaced manager loses the roster right at once and a soft-deleted team is gone for everyone`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("roster-chg", UserRole.ADMIN)
        val manager = user("roster-chg-mgr")
        val target = user("roster-chg-target")
        val teamName = name("chg")
        val teamId = admin.postJson("/api/v1/teams", TeamCreateRequest(name = teamName, managerUserId = manager.id))
            .body<TeamResponse>().id.also { mintedTeams += it }
        assertEquals(HttpStatusCode.NoContent, manager.client.put(path(teamId, target.id)).status)

        assertEquals(HttpStatusCode.NoContent, admin.putJson("/api/v1/teams/$teamId", TeamUpdateRequest(name = teamName)).status)
        assertEquals(HttpStatusCode.Forbidden, manager.client.delete(path(teamId, target.id)).status, "the grant is per request")

        assertEquals(HttpStatusCode.NoContent, admin.delete("/api/v1/teams/$teamId").status)
        assertEquals(HttpStatusCode.Forbidden, manager.client.put(path(teamId, target.id)).status, "the manager of a deleted team")
        assertEquals(HttpStatusCode.Forbidden, manager.client.delete(path(teamId, target.id)).status, "the manager of a deleted team")
        assertEquals(HttpStatusCode.NotFound, admin.put(path(teamId, target.id)).status, "a soft-deleted team cannot take members")
        assertEquals(HttpStatusCode.NotFound, admin.delete(path(teamId, target.id)).status)
    }

    @Test
    fun `adding is idempotent, an unknown or deleted user is 404 and removing a non-member is 404`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("roster-idem", UserRole.ADMIN)
        val teamId = admin.team()
        val ann = user("roster-ann", "Ann")
        val gone = user("roster-gone", "Gone")
        TestUsers.softDelete(gone.id)

        assertEquals(HttpStatusCode.NoContent, admin.put(path(teamId, ann.id)).status)
        assertEquals(HttpStatusCode.NoContent, admin.put(path(teamId, ann.id)).status, "added or already present")
        assertEquals(1, admin.roster(teamId).members.size, "no duplicate row")

        assertEquals(HttpStatusCode.NotFound, admin.put(path(teamId, 999_999u)).status)
        assertEquals(HttpStatusCode.NotFound, admin.put(path(teamId, gone.id)).status, "a soft-deleted user cannot be added")
        assertEquals(1, admin.roster(teamId).members.size)

        assertEquals(HttpStatusCode.NoContent, admin.delete(path(teamId, ann.id)).status)
        assertEquals(HttpStatusCode.NotFound, admin.delete(path(teamId, ann.id)).status, "no longer a member")
        assertEquals(HttpStatusCode.NotFound, admin.delete(path(teamId, 999_999u)).status)
        assertTrue(admin.roster(teamId).members.isEmpty())
    }

    @Test
    fun `members and memberCount resolve through active users, by name`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("roster-read", UserRole.ADMIN)
        val teamId = admin.team()
        val bea = user("roster-bea", "Bea Roster")
        val abe = user("roster-abe", "Abe Roster")
        val doomed = user("roster-doomed", "Doomed Roster")
        for (u in listOf(bea, abe, doomed)) assertEquals(HttpStatusCode.NoContent, admin.put(path(teamId, u.id)).status)

        val before = admin.roster(teamId)
        assertEquals(listOf("Abe Roster", "Bea Roster", "Doomed Roster"), before.members.map { it.name })
        assertTrue(before.members.all { it.email.startsWith("roster-") })
        val listed = admin.get("/api/v1/teams?sort=-id&pageSize=100").body<TeamPageResponse>().items.single { it.id == teamId }
        assertEquals(3, listed.memberCount)

        TestUsers.softDelete(doomed.id)

        assertEquals(listOf("Abe Roster", "Bea Roster"), admin.roster(teamId).members.map { it.name })
        val after = admin.get("/api/v1/teams?sort=-id&pageSize=100").body<TeamPageResponse>().items.single { it.id == teamId }
        assertEquals(2, after.memberCount)
        assertEquals(0, admin.get("/api/v1/teams?name=${"nope-" + UUID.randomUUID()}").body<TeamPageResponse>().items.size)
    }

    @Test
    fun `the roster holds 200 active members - an existing member stays idempotent and a deleted one frees a place`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("roster-cap", UserRole.ADMIN)
        val teamId = admin.team()
        val marker = name("cap")
        val ids = (1..MAX_TEAM_MEMBERS).map {
            TestUsers.seed(email = "$marker-$it@test", password = "pw", role = UserRole.USER, name = "Cap $marker $it")
                .also { id -> mintedUsers += id }
        }
        PostgresTestSupport.connect().use { raw ->
            raw.prepareStatement("INSERT INTO team_members (team_id, user_id) SELECT ?, id FROM users WHERE email LIKE ?").use {
                it.setInt(1, teamId.toInt())
                it.setString(2, "$marker-%@test")
                assertEquals(MAX_TEAM_MEMBERS, it.executeUpdate())
            }
        }
        assertEquals(MAX_TEAM_MEMBERS, admin.roster(teamId).members.size)
        assertEquals(MAX_TEAM_MEMBERS, admin.memberCount(teamId))
        val newcomer = user("roster-capnew")

        assertEquals(HttpStatusCode.Conflict, admin.put(path(teamId, newcomer.id)).status)
        assertEquals(MAX_TEAM_MEMBERS, admin.memberCount(teamId), "the refused add changed nothing")
        assertEquals(HttpStatusCode.NoContent, admin.put(path(teamId, ids.first())).status, "already a member: idempotent beats the cap")
        assertEquals(MAX_TEAM_MEMBERS, admin.roster(teamId).members.size)

        TestUsers.softDelete(ids.last())
        assertEquals(MAX_TEAM_MEMBERS - 1, admin.roster(teamId).members.size, "an inert row does not count")
        assertEquals(HttpStatusCode.NoContent, admin.put(path(teamId, newcomer.id)).status)
        assertEquals(MAX_TEAM_MEMBERS, admin.roster(teamId).members.size)
        assertEquals(MAX_TEAM_MEMBERS, admin.memberCount(teamId))
    }

    @Test
    fun `a manager replaced while a write waits for the team lock is re-checked and answers 403`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("roster-race", UserRole.ADMIN)
        val manager = user("roster-race-mgr")
        val target = user("roster-race-target")
        val teamId = admin.team(manager.id)
        assertEquals(HttpStatusCode.NoContent, admin.put(path(teamId, target.id)).status)

        // PUT: the add of a second user waits and is refused; DELETE: the removal of the first waits and is refused.
        val second = user("roster-race-second")
        PostgresTestSupport.connect().use { raw ->
            raw.autoCommit = false
            raw.prepareStatement("UPDATE teams SET manager_user_id = NULL WHERE id = ?").use { st ->
                st.setInt(1, teamId.toInt())
                st.executeUpdate()
            }
            try {
                coroutineScope {
                    val adding = async { manager.client.put(path(teamId, second.id)) }
                    val removing = async { manager.client.delete(path(teamId, target.id)) }
                    // The up-front guards saw the committed grant; both writes wait on the team row.
                    awaitLockWait("FOR NO KEY UPDATE")
                    raw.commit()
                    assertEquals(HttpStatusCode.Forbidden, adding.await().status)
                    assertEquals(HttpStatusCode.Forbidden, removing.await().status)
                }
            } finally {
                raw.rollback()
            }
        }
        assertEquals(listOf(target.id), admin.roster(teamId).members.map { it.userId }, "the stale grant wrote nothing")
    }

    @Test
    fun `roster changes audit only an actual change`() = testApplication {
        usePostgresTestcontainer()
        val adminEmail = uniqueEmail("roster-audit")
        val adminId = TestUsers.seed(email = adminEmail, password = "pw", role = UserRole.ADMIN)
        val admin = authedClient(adminEmail, "pw")
        val teamId = admin.team()
        val ann = user("roster-aud-ann")
        withAuditCapture { capture ->
            fun count(event: String) = capture.events.count {
                it.message == event && it.hasKeyValue("teamId", teamId.toLong()) && it.hasKeyValue("targetUserId", ann.id.toLong())
            }
            assertEquals(HttpStatusCode.NoContent, admin.put(path(teamId, ann.id)).status)
            val added = capture.awaitEvent { it.message == "team.member_added" && it.hasKeyValue("teamId", teamId.toLong()) }
            assertNotNull(added)
            assertTrue(added.hasKeyValue("byUserId", adminId.toLong()))
            assertTrue(added.hasKeyValue("targetUserId", ann.id.toLong()))

            assertEquals(HttpStatusCode.NoContent, admin.put(path(teamId, ann.id)).status)
            assertEquals(HttpStatusCode.NotFound, admin.put(path(teamId, 999_999u)).status)
            assertEquals(1, count("team.member_added"), "a repeat add and a failed add audit nothing")

            assertEquals(HttpStatusCode.NoContent, admin.delete(path(teamId, ann.id)).status)
            val removed = capture.awaitEvent { it.message == "team.member_removed" && it.hasKeyValue("teamId", teamId.toLong()) }
            assertNotNull(removed)
            assertTrue(removed.hasKeyValue("byUserId", adminId.toLong()))
            assertEquals(HttpStatusCode.NotFound, admin.delete(path(teamId, ann.id)).status)
            assertEquals(1, count("team.member_removed"), "removing a non-member audits nothing")
            assertFalse(admin.roster(teamId).members.any { it.userId == ann.id })
        }
    }
}
