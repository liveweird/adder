package ch.nokillswit

import ch.nokillswit.plugins.ProblemDetail
import ch.nokillswit.teams.TeamCreateRequest
import ch.nokillswit.teams.TeamPageResponse
import ch.nokillswit.teams.TeamResponse
import ch.nokillswit.teams.TeamUpdateRequest
import ch.nokillswit.users.UserRole
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The flat-teams surface (V6): CRUD, the manager (V6), the list filters, the authz split (any
 * authenticated reads, ADMIN writes with guard-before-read), and the soft-delete/partial-index
 * semantics. Teams are SHARED suite state — every test mints UNIQUE names.
 */
class TeamTest {

    private fun name(prefix: String) = "$prefix-${UUID.randomUUID().toString().substring(0, 8)}"

    @Test
    fun `unauthenticated requests are 401`() = testApplication {
        usePostgresTestcontainer()
        val client = jsonClient()
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/teams").status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/teams/1").status)
        assertEquals(HttpStatusCode.Unauthorized, client.post("/api/v1/teams").status)
        assertEquals(HttpStatusCode.Unauthorized, client.put("/api/v1/teams/1").status)
        assertEquals(HttpStatusCode.Unauthorized, client.delete("/api/v1/teams/1").status)
    }

    @Test
    fun `non-admin may read but not write - uniformly 403 even on an unknown id`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("teamuser")
        val id = TestTeams.seed(name("teamro"))
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/teams").status)
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/teams/$id").status)
        assertEquals(HttpStatusCode.Forbidden, client.postJson("/api/v1/teams", TeamCreateRequest(name("x"))).status)
        assertEquals(HttpStatusCode.Forbidden, client.putJson("/api/v1/teams/999999", TeamUpdateRequest(name("x"))).status)
        assertEquals(HttpStatusCode.Forbidden, client.delete("/api/v1/teams/999999").status)
        // The guard runs before the body decodes: a non-admin's malformed body stays 403.
        val malformed = client.post("/api/v1/teams") {
            contentType(ContentType.Application.Json)
            setBody("{ not json")
        }
        assertEquals(HttpStatusCode.Forbidden, malformed.status)
    }

    @Test
    fun `admin CRUD round-trips - create, read, list, rename, delete`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("teamcrud", UserRole.ADMIN)
        val teamName = name("Payments")

        val create = admin.postJson(
            "/api/v1/teams",
            TeamCreateRequest(name = "  $teamName  ", description = "  Money movers  "),
        )
        assertEquals(HttpStatusCode.Created, create.status)
        assertNotNull(create.headers["Location"])
        val created = create.body<TeamResponse>()
        assertEquals(teamName, created.name, "the sanitizer trims")
        assertEquals("Money movers", created.description)

        val read = admin.get("/api/v1/teams/${created.id}").body<TeamResponse>()
        assertEquals(created, read)

        val page = admin.get("/api/v1/teams?name=${teamName.lowercase()}&sort=-name").body<TeamPageResponse>()
        assertEquals(1, page.total)
        assertEquals(created.id, page.items.single().id)
        assertEquals(HttpStatusCode.BadRequest, admin.get("/api/v1/teams?sort=description").status)

        val renamed = name("Treasury")
        assertEquals(
            HttpStatusCode.NoContent,
            admin.putJson("/api/v1/teams/${created.id}", TeamUpdateRequest(name = renamed, description = null)).status,
        )
        val afterRename = admin.get("/api/v1/teams/${created.id}").body<TeamResponse>()
        assertEquals(renamed, afterRename.name)
        assertEquals(null, afterRename.description, "PUT is a full replace — an omitted description clears")
        assertTrue(afterRename.updatedAt >= created.updatedAt)

        assertEquals(HttpStatusCode.NoContent, admin.delete("/api/v1/teams/${created.id}").status)
        assertEquals(HttpStatusCode.NotFound, admin.get("/api/v1/teams/${created.id}").status)
        assertTrue(TestTeams.rawRows().single { it.id == created.id }.markedAsDeleted, "delete must soft-delete")
    }

    @Test
    fun `invalid payloads are 400 - blank name, overlong fields, control characters`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("teambad", UserRole.ADMIN)
        val cases = listOf(
            TeamCreateRequest(name = "   "),
            TeamCreateRequest(name = "x".repeat(101)),
            TeamCreateRequest(name = name("ok"), description = "d".repeat(501)),
            TeamCreateRequest(name = "tab\tcontrol"),
        )
        for (case in cases) {
            assertEquals(HttpStatusCode.BadRequest, admin.postJson("/api/v1/teams", case).status, "expected 400 for $case")
        }
        assertEquals(HttpStatusCode.BadRequest, admin.putJson("/api/v1/teams/999999", TeamUpdateRequest(name = "")).status)
        assertEquals(HttpStatusCode.NotFound, admin.putJson("/api/v1/teams/999999", TeamUpdateRequest(name = name("nf"))).status)
        assertEquals(HttpStatusCode.NotFound, admin.delete("/api/v1/teams/999999").status)
    }

    @Test
    fun `an active name clash is 409 case-insensitively and a soft-deleted team frees its name`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("teamdup", UserRole.ADMIN)
        val n = name("Dup")
        val first = admin.postJson("/api/v1/teams", TeamCreateRequest(n)).body<TeamResponse>()
        val clash = admin.postJson("/api/v1/teams", TeamCreateRequest(n.uppercase()))
        assertEquals(HttpStatusCode.Conflict, clash.status)
        assertTrue(clash.body<ProblemDetail>().detail!!.contains("team with this name"))
        assertEquals(listOf("name"), clash.body<ProblemDetail>().keys)
        val other = admin.postJson("/api/v1/teams", TeamCreateRequest(name("other"))).body<TeamResponse>()
        assertEquals(HttpStatusCode.Conflict, admin.putJson("/api/v1/teams/${other.id}", TeamUpdateRequest(n)).status)
        assertEquals(HttpStatusCode.NoContent, admin.delete("/api/v1/teams/${first.id}").status)
        val second = admin.postJson("/api/v1/teams", TeamCreateRequest(n))
        assertEquals(HttpStatusCode.Created, second.status)
        assertTrue(second.body<TeamResponse>().id != first.id)
    }

    @Test
    fun `mutations audit and a failed mutation does not`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("teamaudit", UserRole.ADMIN)
        withAuditCapture { capture ->
            val n = name("audited")
            val created = admin.postJson("/api/v1/teams", TeamCreateRequest(n)).body<TeamResponse>()
            val event = capture.awaitEvent { it.message == "team.created" && it.hasKeyValue("teamId", created.id.toLong()) }
            assertNotNull(event, "create must audit")
            assertTrue(event.hasKeyValue("name", n))

            admin.putJson("/api/v1/teams/${created.id}", TeamUpdateRequest(name("renamed")))
            assertNotNull(capture.awaitEvent { it.message == "team.updated" && it.hasKeyValue("teamId", created.id.toLong()) })
            admin.delete("/api/v1/teams/${created.id}")
            assertNotNull(capture.awaitEvent { it.message == "team.deleted" && it.hasKeyValue("teamId", created.id.toLong()) })

            val before = capture.events.count { it.message == "team.created" }
            assertEquals(HttpStatusCode.BadRequest, admin.postJson("/api/v1/teams", TeamCreateRequest("")).status)
            assertEquals(before, capture.events.count { it.message == "team.created" }, "failed create must not audit")
        }
    }

    @Test
    fun `a manager round-trips on create, read and list and a PUT replaces or clears it`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("teammgr", UserRole.ADMIN)
        val ann = TestUsers.seed(email = uniqueEmail("mgr-a"), password = "pw", role = UserRole.USER, name = "Ann Manager")
        val bob = TestUsers.seed(email = uniqueEmail("mgr-b"), password = "pw", role = UserRole.USER, name = "Bob Manager")
        val teamName = name("managed")

        val created = admin.postJson("/api/v1/teams", TeamCreateRequest(name = teamName, managerId = ann))
            .body<TeamResponse>()
        assertEquals(ann, created.managerId)
        assertEquals("Ann Manager", created.managerName)
        assertEquals(created, admin.get("/api/v1/teams/${created.id}").body<TeamResponse>())
        val listed = admin.get("/api/v1/teams?name=$teamName").body<TeamPageResponse>().items.single()
        assertEquals(ann, listed.managerId)
        assertEquals("Ann Manager", listed.managerName)

        // One user may manage several teams.
        val second = admin.postJson("/api/v1/teams", TeamCreateRequest(name = name("managed2"), managerId = ann))
            .body<TeamResponse>()
        assertEquals(ann, second.managerId)

        assertEquals(
            HttpStatusCode.NoContent,
            admin.putJson("/api/v1/teams/${created.id}", TeamUpdateRequest(name = teamName, managerId = bob)).status,
        )
        val replaced = admin.get("/api/v1/teams/${created.id}").body<TeamResponse>()
        assertEquals(bob, replaced.managerId)
        assertEquals("Bob Manager", replaced.managerName)

        // PUT is a full replace: an omitted managerId clears it.
        assertEquals(HttpStatusCode.NoContent, admin.putJson("/api/v1/teams/${created.id}", TeamUpdateRequest(name = teamName)).status)
        val cleared = admin.get("/api/v1/teams/${created.id}").body<TeamResponse>()
        assertEquals(null, cleared.managerId)
        assertEquals(null, cleared.managerName)
        val clearedListed = admin.get("/api/v1/teams?name=$teamName").body<TeamPageResponse>().items.single()
        assertEquals(null, clearedListed.managerId)
    }

    @Test
    fun `an unknown or soft-deleted manager is 400 on create and on update and changes nothing`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("teammgrbad", UserRole.ADMIN)
        val gone = TestUsers.seed(email = uniqueEmail("mgr-gone"), password = "pw", role = UserRole.USER)
        TestUsers.softDelete(gone)
        val good = TestUsers.seed(email = uniqueEmail("mgr-good"), password = "pw", role = UserRole.USER)
        val teamName = name("mgrbad")

        for (bad in listOf(999999u, gone)) {
            val create = admin.postJson("/api/v1/teams", TeamCreateRequest(name = teamName, managerId = bad))
            assertEquals(HttpStatusCode.BadRequest, create.status, "create with manager $bad")
            assertTrue(create.body<ProblemDetail>().detail!!.contains("manager"))
        }
        assertTrue(TestTeams.rawRows().none { it.name == teamName }, "a rejected create leaves no team behind")

        val id = admin.postJson("/api/v1/teams", TeamCreateRequest(name = teamName, managerId = good)).body<TeamResponse>().id
        for (bad in listOf(999999u, gone)) {
            assertEquals(
                HttpStatusCode.BadRequest,
                admin.putJson("/api/v1/teams/$id", TeamUpdateRequest(name = name("renamed"), managerId = bad)).status,
                "update with manager $bad",
            )
        }
        val after = admin.get("/api/v1/teams/$id").body<TeamResponse>()
        assertEquals(good, after.managerId, "a rejected update changes nothing")
        assertEquals(teamName, after.name)
    }

    @Test
    fun `a soft-deleted manager reads as no manager and stops being the active manager`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("teammgrdel", UserRole.ADMIN)
        val manager = TestUsers.seed(email = uniqueEmail("mgr-del"), password = "pw", role = UserRole.USER, name = "Soon Gone")
        val teamName = name("mgrdel")
        val id = admin.postJson("/api/v1/teams", TeamCreateRequest(name = teamName, managerId = manager))
            .body<TeamResponse>().id
        assertTrue(TestTeams.service.isActiveManager(id, manager))

        TestUsers.softDelete(manager)

        val read = admin.get("/api/v1/teams/$id").body<TeamResponse>()
        assertEquals(null, read.managerId)
        assertEquals(null, read.managerName)
        val listed = admin.get("/api/v1/teams?name=$teamName").body<TeamPageResponse>().items.single()
        assertEquals(null, listed.managerId)
        assertEquals(null, listed.managerName)
        assertFalse(TestTeams.service.isActiveManager(id, manager), "a deleted manager holds no rights")
    }

    @Test
    fun `isActiveManager is true only for the active manager of an active team`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("teammgrchk", UserRole.ADMIN)
        val manager = TestUsers.seed(email = uniqueEmail("mgr-chk"), password = "pw", role = UserRole.USER)
        val other = TestUsers.seed(email = uniqueEmail("mgr-oth"), password = "pw", role = UserRole.USER)
        val managed = admin.postJson("/api/v1/teams", TeamCreateRequest(name = name("chk"), managerId = manager))
            .body<TeamResponse>().id
        val unmanaged = TestTeams.seed(name("chk-none"))

        assertTrue(TestTeams.service.isActiveManager(managed, manager))
        assertFalse(TestTeams.service.isActiveManager(managed, other), "somebody else")
        assertFalse(TestTeams.service.isActiveManager(unmanaged, manager), "a team they do not manage")
        assertFalse(TestTeams.service.isActiveManager(999999u, manager), "a missing team")
        assertEquals(HttpStatusCode.NoContent, admin.delete("/api/v1/teams/$managed").status)
        assertFalse(TestTeams.service.isActiveManager(managed, manager), "a deleted team")
    }

    @Test
    fun `being a team's manager grants no write on the team itself`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("teammgrrw", UserRole.ADMIN)
        val email = uniqueEmail("mgr-rw")
        val manager = TestUsers.seed(email = email, password = "pw", role = UserRole.USER)
        val id = admin.postJson("/api/v1/teams", TeamCreateRequest(name = name("rw"), managerId = manager))
            .body<TeamResponse>().id
        val client = authedClient(email, "pw")
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/teams/$id").status)
        assertEquals(
            HttpStatusCode.Forbidden,
            client.putJson("/api/v1/teams/$id", TeamUpdateRequest(name = name("hijack"), managerId = manager)).status,
        )
        assertEquals(HttpStatusCode.Forbidden, client.delete("/api/v1/teams/$id").status)
    }

    @Test
    fun `team manager_changed is audited on an actual change and not on a same-id re-PUT`() = testApplication {
        usePostgresTestcontainer()
        val adminEmail = uniqueEmail("mgr-audit-admin")
        val adminId = TestUsers.seed(email = adminEmail, password = "pw", role = UserRole.ADMIN)
        val admin = authedClient(adminEmail, "pw")
        val ann = TestUsers.seed(email = uniqueEmail("mgr-au"), password = "pw", role = UserRole.USER)
        withAuditCapture { capture ->
            val teamName = name("mgraudit")
            val created = admin.postJson("/api/v1/teams", TeamCreateRequest(name = teamName, managerId = ann))
                .body<TeamResponse>()
            val teamId = created.id.toLong()
            val createdEvent = capture.awaitEvent { it.message == "team.created" && it.hasKeyValue("teamId", teamId) }
            assertNotNull(createdEvent)
            assertTrue(createdEvent.hasKeyValue("managerUserId", ann.toLong()))

            // Same manager again: team.updated fires, team.manager_changed does not.
            admin.putJson("/api/v1/teams/${created.id}", TeamUpdateRequest(name = teamName, managerId = ann))
            assertNotNull(capture.awaitEvent { it.message == "team.updated" && it.hasKeyValue("teamId", teamId) })

            // A real change (clear) — and only now does the trail carry a manager line.
            admin.putJson("/api/v1/teams/${created.id}", TeamUpdateRequest(name = teamName))
            val changed = capture.awaitEvent { it.message == "team.manager_changed" && it.hasKeyValue("teamId", teamId) }
            assertNotNull(changed)
            assertTrue(changed.hasKeyValue("byUserId", adminId.toLong()))
            assertTrue(changed.hasKeyValue("managerFrom", ann.toLong()))
            assertTrue(changed.hasKeyValue("managerTo", null))
            assertEquals(
                1,
                capture.events.count { it.message == "team.manager_changed" && it.hasKeyValue("teamId", teamId) },
                "only the actual change is audited",
            )
        }
    }

    private fun handle(prefix: String) = "$prefix-${UUID.randomUUID().toString().substring(0, 8)}"

    @Test
    fun `a handle round-trips on create, read and list and is trimmed`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("teamhandle", UserRole.ADMIN)
        val h = handle("oncall")
        val created = admin.postJson("/api/v1/teams", TeamCreateRequest(name = name("handled"), handle = "  $h  ")).body<TeamResponse>()
        assertEquals(h, created.handle)
        assertEquals(created, admin.get("/api/v1/teams/${created.id}").body<TeamResponse>())
        assertEquals(h, admin.get("/api/v1/teams?handle=$h").body<TeamPageResponse>().items.single().handle)
        val bare = admin.postJson("/api/v1/teams", TeamCreateRequest(name = name("bare"))).body<TeamResponse>()
        assertEquals(null, bare.handle, "the handle is optional")
        val samePut = admin.putJson("/api/v1/teams/${created.id}", TeamUpdateRequest(name = created.name, handle = h))
        assertEquals(HttpStatusCode.NoContent, samePut.status, "a team may keep its own handle on a PUT")
        assertEquals(h, admin.get("/api/v1/teams/${created.id}").body<TeamResponse>().handle)
    }

    @Test
    fun `the handle grammar - 2 to 30 lowercase hyphenated words starting with a letter`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("teamgrammar", UserRole.ADMIN)
        val valid = listOf("ab", "a1", "on-call", "pay-2-go", "a" + "0".repeat(29))
        for (candidate in valid) {
            // The words are fixed: delete each team again so the next candidate (and a rerun in this container) finds it free.
            val response = admin.postJson("/api/v1/teams", TeamCreateRequest(name = name("grammar"), handle = candidate))
            assertEquals(HttpStatusCode.Created, response.status, "expected 201 for '$candidate'")
            assertEquals(HttpStatusCode.NoContent, admin.delete("/api/v1/teams/${response.body<TeamResponse>().id}").status)
        }
        val invalid = listOf(
            "", "   ", "a", "a".repeat(31), "1abc", "-ab", "ab-", "a--b", "a_b", "a b", "ab!", "żółw", "ab.cd", "ab\ncd",
            "Payments", "ON-CALL", "oN-call",
        )
        for (candidate in invalid) {
            val response = admin.postJson("/api/v1/teams", TeamCreateRequest(name = name("grammar"), handle = candidate))
            assertEquals(HttpStatusCode.BadRequest, response.status, "expected 400 for '$candidate'")
        }
        val team = admin.postJson("/api/v1/teams", TeamCreateRequest(name = name("grammarput"))).body<TeamResponse>()
        for (candidate in invalid) {
            val response = admin.putJson("/api/v1/teams/${team.id}", TeamUpdateRequest(name = team.name, handle = candidate))
            assertEquals(HttpStatusCode.BadRequest, response.status, "PUT: expected 400 for '$candidate'")
        }
        assertEquals(null, admin.get("/api/v1/teams/${team.id}").body<TeamResponse>().handle, "a rejected PUT changes nothing")
    }

    @Test
    fun `an uppercase handle is a 400 and is never folded to a taken or free lowercase one`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("teamupper", UserRole.ADMIN)
        val h = handle("upper")
        val response = admin.postJson("/api/v1/teams", TeamCreateRequest(name = name("upper"), handle = h.uppercase()))
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals(0, admin.get("/api/v1/teams?handle=$h").body<TeamPageResponse>().total, "nothing was stored under the folded form")
    }

    @Test
    fun `a reserved handle is a 400 on create and on update`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("teamreserved", UserRole.ADMIN)
        val team = admin.postJson("/api/v1/teams", TeamCreateRequest(name = name("reserved"))).body<TeamResponse>()
        for (word in listOf("everyone", "watchers", "observers", "here", "all", "channel", "room", "team")) {
            val create = admin.postJson("/api/v1/teams", TeamCreateRequest(name = name("reserved"), handle = word))
            assertEquals(HttpStatusCode.BadRequest, create.status, "create '$word'")
            assertTrue(create.body<ProblemDetail>().detail!!.contains("reserved"))
            val update = admin.putJson("/api/v1/teams/${team.id}", TeamUpdateRequest(name = team.name, handle = word))
            assertEquals(HttpStatusCode.BadRequest, update.status, "update '$word'")
        }
        // A reserved word as part of a longer handle is fine.
        val longer = handle("team")
        val update = admin.putJson("/api/v1/teams/${team.id}", TeamUpdateRequest(name = team.name, handle = longer))
        assertEquals(HttpStatusCode.NoContent, update.status)
    }

    @Test
    fun `an active handle clash is 409 and a soft-deleted team frees its handle`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("teamhandledup", UserRole.ADMIN)
        val h = handle("dup")
        val first = admin.postJson("/api/v1/teams", TeamCreateRequest(name = name("first"), handle = h)).body<TeamResponse>()
        val clash = admin.postJson("/api/v1/teams", TeamCreateRequest(name = name("second"), handle = h))
        assertEquals(HttpStatusCode.Conflict, clash.status)
        assertTrue(clash.body<ProblemDetail>().detail!!.contains("handle"), "the 409 says WHICH field clashed")
        assertEquals(listOf("handle"), clash.body<ProblemDetail>().keys, "and names it for a client")
        val other = admin.postJson("/api/v1/teams", TeamCreateRequest(name = name("other"))).body<TeamResponse>()
        assertEquals(
            HttpStatusCode.Conflict,
            admin.putJson("/api/v1/teams/${other.id}", TeamUpdateRequest(name = other.name, handle = h)).status,
        )
        assertEquals(HttpStatusCode.NoContent, admin.delete("/api/v1/teams/${first.id}").status)
        val reused = admin.postJson("/api/v1/teams", TeamCreateRequest(name = name("third"), handle = h))
        assertEquals(HttpStatusCode.Created, reused.status, "a deleted team freed the handle")
        assertEquals(h, reused.body<TeamResponse>().handle)
        // The name clash still reads as a name clash.
        val nameClash = admin.postJson("/api/v1/teams", TeamCreateRequest(name = reused.body<TeamResponse>().name, handle = handle("n")))
        assertEquals(HttpStatusCode.Conflict, nameClash.status)
        assertTrue(nameClash.body<ProblemDetail>().detail!!.contains("name"))
        assertEquals(listOf("name"), nameClash.body<ProblemDetail>().keys)
    }

    @Test
    fun `a PUT without a handle clears it and frees it for another team`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("teamhandleclear", UserRole.ADMIN)
        val h = handle("clear")
        val team = admin.postJson("/api/v1/teams", TeamCreateRequest(name = name("clearme"), handle = h)).body<TeamResponse>()
        assertEquals(HttpStatusCode.NoContent, admin.putJson("/api/v1/teams/${team.id}", TeamUpdateRequest(name = team.name)).status)
        assertEquals(null, admin.get("/api/v1/teams/${team.id}").body<TeamResponse>().handle)
        assertEquals(HttpStatusCode.Created, admin.postJson("/api/v1/teams", TeamCreateRequest(name = name("taker"), handle = h)).status)
    }

    @Test
    fun `the list filters by handle substring and sorts by handle`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("teamhandlelist", UserRole.ADMIN)
        val stem = "lst" + UUID.randomUUID().toString().substring(0, 8)
        val b = admin.postJson("/api/v1/teams", TeamCreateRequest(name = name("hb"), handle = "$stem-b")).body<TeamResponse>()
        val a = admin.postJson("/api/v1/teams", TeamCreateRequest(name = name("ha"), handle = "$stem-a")).body<TeamResponse>()
        admin.postJson("/api/v1/teams", TeamCreateRequest(name = name("hnone")))
        val ascending = admin.get("/api/v1/teams?handle=${stem.uppercase()}&sort=handle").body<TeamPageResponse>()
        assertEquals(listOf(a.id, b.id), ascending.items.map { it.id }, "case-insensitive substring, A before B")
        assertEquals(2, ascending.total, "a team without a handle never matches")
        val descending = admin.get("/api/v1/teams?handle=$stem&sort=-handle").body<TeamPageResponse>()
        assertEquals(listOf(b.id, a.id), descending.items.map { it.id })
        assertEquals(listOf(b.id), admin.get("/api/v1/teams?handle=$stem-b").body<TeamPageResponse>().items.map { it.id })
        assertEquals(HttpStatusCode.BadRequest, admin.get("/api/v1/teams?handle=${"x".repeat(31)}").status)
        val reader = seededClient("teamhandleuser").get("/api/v1/teams?handle=$stem")
        assertEquals(HttpStatusCode.OK, reader.status, "any authenticated user filters")
    }

    @Test
    fun `memberId lists the teams whose active roster holds the person, for any authenticated reader`() = testApplication {
        usePostgresTestcontainer()
        val reader = seededClient("teammemberid")
        val ada = TestUsers.seed(email = uniqueEmail("teammemberid-a"), password = "pw-123456789", role = UserRole.USER)
        val bob = TestUsers.seed(email = uniqueEmail("teammemberid-b"), password = "pw-123456789", role = UserRole.USER)
        val stem = "mid" + UUID.randomUUID().toString().substring(0, 8)
        val both = TestTeams.seedMentionable(stem + "x", members = listOf(ada, bob))
        val adaOnly = TestTeams.seedMentionable(stem + "y", members = listOf(ada))
        TestTeams.seedMentionable(stem + "z")

        suspend fun listing(query: String) = reader.get("/api/v1/teams?handle=$stem&sort=id&$query").body<TeamPageResponse>()
        assertEquals(listOf(both.id, adaOnly.id), listing("memberId=$ada").items.map { it.id })
        assertEquals(listOf(both.id), listing("memberId=$bob").items.map { it.id })
        assertEquals(2L, listing("memberId=$ada").total, "the total counts after the filter")
        val narrowed = listing("memberId=$ada&name=${both.name.replace(" ", "%20")}")
        assertEquals(listOf(both.id), narrowed.items.map { it.id }, "the other filters still narrow")
        assertEquals(3L, listing("").total, "without memberId every team of the stem is listed")
        assertEquals(0L, listing("memberId=2000000000").total, "an unknown id matches nothing")

        TestUsers.softDelete(bob)
        assertEquals(0L, listing("memberId=$bob").total, "the inert roster row of a removed user never counts")
        assertEquals(listOf(both.id, adaOnly.id), listing("memberId=$ada").items.map { it.id })

        // A deleted team is never listed, whoever is on its roster.
        val admin = seededClient("teammemberid-admin", UserRole.ADMIN)
        assertEquals(HttpStatusCode.NoContent, admin.delete("/api/v1/teams/${adaOnly.id}").status)
        assertEquals(listOf(both.id), listing("memberId=$ada").items.map { it.id })
        assertEquals(1L, listing("memberId=$ada").total)

        // Strict: ASCII digits of 1 to int32, one value. A blank, `0`, a sign, a fraction, another script's digits (U+0665)
        // and an overflow are all 400.
        val bads = listOf(
            "memberId=abc", "memberId=-1", "memberId=0", "memberId=%2B5", "memberId=1.5", "memberId=", "memberId=%D9%A5",
            "memberId=3000000000", "memberId=99999999999", "memberId=1&memberId=2",
        )
        for (bad in bads) {
            assertEquals(HttpStatusCode.BadRequest, reader.get("/api/v1/teams?$bad").status, bad)
        }
    }

    @Test
    fun `team created and updated audit lines carry the handle`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("teamhandleaudit", UserRole.ADMIN)
        withAuditCapture { capture ->
            val h = handle("audited")
            val created = admin.postJson("/api/v1/teams", TeamCreateRequest(name = name("hau"), handle = h)).body<TeamResponse>()
            val teamId = created.id.toLong()
            val createdEvent = capture.awaitEvent { it.message == "team.created" && it.hasKeyValue("teamId", teamId) }
            assertNotNull(createdEvent)
            assertTrue(createdEvent.hasKeyValue("handle", h))

            val renamed = handle("renamed")
            admin.putJson("/api/v1/teams/${created.id}", TeamUpdateRequest(name = created.name, handle = renamed))
            val updated = capture.awaitEvent {
                it.message == "team.updated" && it.hasKeyValue("teamId", teamId) && it.hasKeyValue("handle", renamed)
            }
            assertNotNull(updated, "the update carries the new handle")

            admin.putJson("/api/v1/teams/${created.id}", TeamUpdateRequest(name = created.name))
            assertNotNull(
                capture.awaitEvent { it.message == "team.updated" && it.hasKeyValue("teamId", teamId) && it.hasKeyValue("handle", null) },
                "a cleared handle is audited as null",
            )
            assertEquals(
                HttpStatusCode.BadRequest,
                admin.postJson("/api/v1/teams", TeamCreateRequest(name = name("hbad"), handle = "Bad")).status,
            )
            val rejected = capture.events.count { it.message == "team.created" && it.hasKeyValue("handle", "Bad") }
            assertEquals(0, rejected, "a rejected handle is not audited")
        }
    }
}
