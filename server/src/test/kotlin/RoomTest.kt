package ch.nokillswit

import ch.nokillswit.plugins.ProblemDetail
import ch.nokillswit.rooms.MAX_ROOM_NAME_LENGTH
import ch.nokillswit.rooms.ROOM_ARCHIVED_PROBLEM_TYPE
import ch.nokillswit.rooms.TEMPLATE_GONE_PROBLEM_TYPE
import ch.nokillswit.rooms.RoomCreateRequest
import ch.nokillswit.rooms.RoomPageResponse
import ch.nokillswit.rooms.RoomResponse
import ch.nokillswit.authz.RoomRole
import ch.nokillswit.rooms.RoomUpdateRequest
import ch.nokillswit.rooms.RoomWorkflowResponse
import ch.nokillswit.rooms.WorkflowUsageKey
import ch.nokillswit.rooms.resolvedLabels
import ch.nokillswit.users.UserRole
import ch.nokillswit.workflows.StatusCategory
import ch.nokillswit.workflows.StatusColour
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

/**
 * The room record (V13) over HTTP: create with the copied workflow (labels resolved to the request language, EN per-label fallback), the
 * template and name rules, the list filters and sorts, archive/unarchive, soft delete, the per-user create bucket, the audit trail
 * (`room.access_denied` included) and the lock-wait race where the caller is demoted while a write waits. The role matrix per caller
 * is `RoomAuthzMatrixTest`. Rooms, templates and users are SHARED suite state: every test mints UNIQUE rows and removes them.
 */
class RoomTest {
    private val rooms = "/api/v1/rooms"
    private val mintedRooms = mutableListOf<UInt>()
    private val mintedUsers = mutableListOf<UInt>()
    private val mintedTemplates = mutableListOf<UInt>()

    @AfterTest
    fun cleanUp() = runBlocking {
        TestRooms.purge(mintedRooms)
        mintedUsers.forEach { TestUsers.softDelete(it) }
        mintedTemplates.forEach { TestTemplates.delete(it) }
    }

    private fun marker() = UUID.randomUUID().toString().substring(0, 8)

    private suspend fun io.ktor.server.testing.ApplicationTestBuilder.actor(prefix: String, role: UserRole = UserRole.USER): RoomActor =
        seedActor(prefix, role).also { mintedUsers += it.id }

    private suspend fun template(): UInt = TestTemplates.seed().also { mintedTemplates += it }

    private suspend fun HttpClient.create(name: String, templateId: UInt, language: String? = null): RoomResponse {
        val response = postJson(rooms, RoomCreateRequest(name = name, templateId = templateId, language = language))
        assertEquals(HttpStatusCode.Created, response.status, "create $name")
        return response.body<RoomResponse>().also { mintedRooms += it.id }
    }

    private suspend fun HttpClient.workflow(roomId: UInt): RoomWorkflowResponse = get("$rooms/$roomId/workflow").body()

    @Test
    fun `create copies the template with English labels by default and makes the creator the OWNER`() = testApplication {
        usePostgresTestcontainer()
        val owner = actor("room-create")
        val templateId = template()

        val response = owner.client.postJson(rooms, RoomCreateRequest("  Copy ${marker()}  ", templateId, description = "  A room  "))
        assertEquals(HttpStatusCode.Created, response.status)
        val room = response.body<RoomResponse>().also { mintedRooms += it.id }
        assertEquals("/api/v1/rooms/${room.id}", response.headers["Location"])
        assertTrue(room.name.startsWith("Copy "), "the sanitizer trims")
        assertEquals("A room", room.description)
        assertEquals(RoomRole.OWNER, room.role)
        assertEquals(1, room.memberCount)
        assertEquals(1L, room.workflowRevision)
        assertFalse(room.archived)
        assertNull(room.archivedAt)
        assertEquals(room.createdAt, room.lastActivityAt, "0.2: last activity equals creation")
        assertEquals(room, owner.client.get("$rooms/${room.id}").body<RoomResponse>())

        val workflow = owner.client.workflow(room.id)
        assertEquals(1L, workflow.revision)
        assertEquals(
            listOf(
                listOf("open", "Open", "GRAY", "OPEN", "true"),
                listOf("half", "Half only", "BLUE", "OPEN", "false"),
                listOf("done", "Done", "TEAL", "CLOSED", "false"),
            ),
            workflow.statuses.map { listOf(it.key, it.label, "${it.colour}", "${it.category}", "${it.initial}") },
        )
        assertTrue(workflow.statuses.all { it.threadCount == 0L })
        assertEquals(StatusColour.GRAY, workflow.statuses.first().colour)
        assertEquals(StatusCategory.CLOSED, workflow.statuses.last().category)
        val severity = workflow.fields.first()
        assertEquals(
            listOf("severity", "Severity", "SELECT", "true"),
            listOf(severity.key, severity.label, "${severity.type}", "${severity.required}"),
        )
        assertEquals(listOf("s1" to "Sev one", "s2" to "Sev two"), severity.options.map { it.key to it.label })
        assertEquals(listOf("note", "Note", "TEXT"), workflow.fields.last().let { listOf(it.key, it.label, "${it.type}") })
        assertTrue(workflow.fields.last().options.isEmpty())
    }

    @Test
    fun `a Polish room gets the Polish labels with the English text as the per-label fallback`() = testApplication {
        usePostgresTestcontainer()
        val owner = actor("room-pl")
        val room = owner.client.create("Pl ${marker()}", template(), language = "pl")

        val workflow = owner.client.workflow(room.id)
        assertEquals(listOf("Otwarte", "Half only", "Zrobione"), workflow.statuses.map { it.label })
        assertEquals(listOf("Ważność", "Note"), workflow.fields.map { it.label })
        assertEquals(listOf("Waga jeden", "Sev two"), workflow.fields.first().options.map { it.label })
    }

    @Test
    fun `a room created from the seeded Incident template in Polish carries its five statuses and four fields`() = testApplication {
        usePostgresTestcontainer()
        val owner = actor("room-incident")
        val room = owner.client.create("Incident ${marker()}", TestTemplates.seededId("Incident"), language = "pl")

        val workflow = owner.client.workflow(room.id)
        assertEquals(listOf("triage", "investigating", "mitigating", "monitoring", "resolved"), workflow.statuses.map { it.key })
        assertEquals("Triaż", workflow.statuses.first().label)
        assertEquals(StatusCategory.CLOSED, workflow.statuses.last().category)
        assertEquals(listOf("severity", "component", "jira-link", "eta"), workflow.fields.map { it.key })
        assertEquals(listOf("S1", "S2", "S3", "S4"), workflow.fields.first().options.map { it.label })
    }

    @Test
    fun `the copy is independent of the template - deleting it leaves the room's workflow untouched`() = testApplication {
        usePostgresTestcontainer()
        val owner = actor("room-indep")
        val templateId = template()
        val room = owner.client.create("Indep ${marker()}", templateId)
        val before = owner.client.workflow(room.id)

        TestTemplates.delete(templateId)

        assertEquals(before, owner.client.workflow(room.id))
        // ...and the deleted template can no longer be copied.
        val late = owner.client.postJson(rooms, RoomCreateRequest("Late ${marker()}", templateId))
        assertEquals(HttpStatusCode.BadRequest, late.status)
        // ...as a typed problem the SPA tells apart from every other 400 by its URI, never by the detail text.
        assertEquals(TEMPLATE_GONE_PROBLEM_TYPE, late.body<ProblemDetail>().type)
    }

    @Test
    fun `labels fall back to English for the whole list when per-label resolution would clash`() {
        val clashing = listOf(mapOf("en" to "Open", "pl" to "Otwarte"), mapOf("en" to "Otwarte"))
        assertEquals(listOf("Open", "Otwarte"), resolvedLabels(clashing, "pl"), "Otwarte/Otwarte would clash, so EN for the list")
        val clean = listOf(mapOf("en" to "Open", "pl" to "Otwarte"), mapOf("en" to "Done", "pl" to "Zrobione"))
        assertEquals(listOf("Otwarte", "Zrobione"), resolvedLabels(clean, "pl"))
        assertEquals(listOf("Open", "Done"), resolvedLabels(clean, "en"))
        assertEquals(emptyList(), resolvedLabels(emptyList(), "pl"))
    }

    @Test
    fun `invalid creates are 400 - unknown or deleted template, bad language, bad name or description`() = testApplication {
        usePostgresTestcontainer()
        val owner = actor("room-bad")
        val templateId = template()
        val deleted = template().also { TestTemplates.delete(it) }
        val cases = listOf(
            RoomCreateRequest("Ok ${marker()}", 999_999u),
            RoomCreateRequest("Ok ${marker()}", deleted),
            RoomCreateRequest("Ok ${marker()}", templateId, language = "de"),
            RoomCreateRequest("   ", templateId),
            RoomCreateRequest("x".repeat(MAX_ROOM_NAME_LENGTH + 1), templateId),
            RoomCreateRequest("Ok ${marker()}", templateId, description = "d".repeat(501)),
            RoomCreateRequest("tab\tcontrol", templateId),
        )
        for (case in cases) {
            assertEquals(HttpStatusCode.BadRequest, owner.client.postJson(rooms, case).status, "expected 400 for $case")
        }
    }

    @Test
    fun `a name clash is 409 case-insensitively, archived rooms keep their names and a deleted room frees its own`() = testApplication {
        usePostgresTestcontainer()
        val owner = actor("room-dup")
        val other = actor("room-dup-other")
        val admin = actor("room-dup-admin", UserRole.ADMIN)
        val templateId = template()
        val name = "Dup ${marker()}"
        val first = owner.client.create(name, templateId)

        val clash = other.client.postJson(rooms, RoomCreateRequest(name.uppercase(), templateId))
        assertEquals(HttpStatusCode.Conflict, clash.status)
        assertTrue(clash.body<ProblemDetail>().detail!!.contains("room with this name"))
        assertEquals(HttpStatusCode.NoContent, owner.client.post("$rooms/${first.id}/archive").status)
        val archivedClash = other.client.postJson(rooms, RoomCreateRequest(name, templateId))
        assertEquals(HttpStatusCode.Conflict, archivedClash.status, "archived keeps its name")

        assertEquals(HttpStatusCode.NoContent, admin.client.delete("$rooms/${first.id}").status)
        val second = other.client.create(name, templateId)
        assertTrue(second.id != first.id)
        assertEquals(RoomRole.OWNER, second.role)
    }

    @Test
    fun `the list filters, sorts and pages, and scopes non-admins to their own rooms`() = testApplication {
        usePostgresTestcontainer()
        val owner = actor("room-list")
        val other = actor("room-list-other")
        val admin = actor("room-list-admin", UserRole.ADMIN)
        val templateId = template()
        val m = marker()
        val alpha = owner.client.create("Alpha $m", templateId)
        val bravo = owner.client.create("Bravo $m", templateId)
        val charlie = owner.client.create("Charlie $m", templateId)
        val delta = other.client.create("Delta $m", templateId)
        TestRooms.addMember(delta.id, owner.id)
        assertEquals(HttpStatusCode.NoContent, owner.client.post("$rooms/${bravo.id}/archive").status)

        suspend fun HttpClient.list(query: String): RoomPageResponse = get("$rooms?name=$m&$query").body()
        fun RoomPageResponse.names() = items.map { it.name.substringBefore(' ') }

        // A non-admin sees the rooms they are in - Delta as a plain member - and never the ones they are not in.
        val mine = owner.client.list("sort=name")
        assertEquals(listOf("Alpha", "Bravo", "Charlie", "Delta"), mine.names())
        assertEquals(listOf(RoomRole.OWNER, RoomRole.OWNER, RoomRole.OWNER, RoomRole.MEMBER), mine.items.map { it.role })
        assertEquals(listOf(true), mine.items.filter { it.name.startsWith("Bravo") }.map { it.archived })
        assertEquals(listOf(1, 1, 1, 2), mine.items.map { it.memberCount })
        assertEquals(listOf("Delta"), other.client.list("").names())
        assertEquals(emptyList(), other.client.list("member=false").names(), "a non-admin has nothing readable outside their rooms")

        assertEquals(listOf("Alpha", "Charlie", "Delta"), owner.client.list("archived=false&sort=name").names())
        assertEquals(listOf("Bravo"), owner.client.list("archived=true").names())
        assertEquals(listOf("Delta", "Charlie", "Bravo", "Alpha"), owner.client.list("sort=-name").names())
        assertEquals(listOf("Alpha", "Bravo", "Charlie", "Delta"), owner.client.list("sort=createdAt").names())
        assertEquals(listOf("Delta", "Charlie", "Bravo", "Alpha"), owner.client.list("sort=-lastActivityAt,-id").names())
        assertEquals(listOf(alpha.id, bravo.id, charlie.id, delta.id), owner.client.list("sort=id").items.map { it.id })
        val firstPage = owner.client.list("sort=name&pageSize=3&page=1")
        assertEquals(4L, firstPage.total)
        assertEquals(listOf("Alpha", "Bravo", "Charlie"), firstPage.names())
        assertEquals(listOf("Delta"), owner.client.list("sort=name&pageSize=3&page=2").names())
        assertEquals(listOf("Delta"), owner.client.get("$rooms?name=delta%20$m").body<RoomPageResponse>().names(), "case-insensitive")

        // An ADMIN sees every room: a null role outside them, `member=true` narrows to their own (none), `member=false` to the others.
        val everything = admin.client.list("sort=name")
        assertEquals(4, everything.items.size)
        assertTrue(everything.items.all { it.role == null })
        assertEquals(emptyList(), admin.client.list("member=true").names())
        assertEquals(4, admin.client.list("member=false").items.size)

        for (bad in listOf("sort=description", "member=maybe", "archived=1", "member=true&member=false", "pageSize=0", "page=0")) {
            assertEquals(HttpStatusCode.BadRequest, owner.client.get("$rooms?$bad").status, "expected 400 for $bad")
        }
        assertEquals(HttpStatusCode.BadRequest, owner.client.get("$rooms?name=${"x".repeat(MAX_ROOM_NAME_LENGTH + 1)}").status)
    }

    @Test
    fun `member counts resolve through active users - a soft-deleted member drops out of the list and the read`() = testApplication {
        usePostgresTestcontainer()
        val owner = actor("room-count")
        val member = actor("room-count-member")
        val room = owner.client.create("Count ${marker()}", template())
        TestRooms.addMember(room.id, member.id)

        val byName = "$rooms?name=${room.name.replace(" ", "%20")}"
        suspend fun listed() = owner.client.get(byName).body<RoomPageResponse>().items.single().memberCount
        assertEquals(2, listed())
        assertEquals(2, owner.client.get("$rooms/${room.id}").body<RoomResponse>().memberCount)

        TestUsers.softDelete(member.id)

        assertEquals(1, listed())
        assertEquals(1, owner.client.get("$rooms/${room.id}").body<RoomResponse>().memberCount)
    }

    @Test
    fun `an owner renames a room and clears its description, a clash is 409 and bad bodies are 400`() = testApplication {
        usePostgresTestcontainer()
        val owner = actor("room-put")
        val templateId = template()
        val a = owner.client.create("PutA ${marker()}", templateId)
        val b = owner.client.create("PutB ${marker()}", templateId)
        val renamed = "Renamed ${marker()}"

        val first = owner.client.putJson("$rooms/${a.id}", RoomUpdateRequest("  $renamed ", "Now described"))
        assertEquals(HttpStatusCode.NoContent, first.status)
        val after = owner.client.get("$rooms/${a.id}").body<RoomResponse>()
        assertEquals(renamed, after.name)
        assertEquals("Now described", after.description)
        assertTrue(after.updatedAt >= a.updatedAt)
        assertEquals(a.workflowRevision, after.workflowRevision, "a metadata edit never moves the workflow revision")

        assertEquals(HttpStatusCode.NoContent, owner.client.putJson("$rooms/${a.id}", RoomUpdateRequest(renamed)).status)
        assertNull(owner.client.get("$rooms/${a.id}").body<RoomResponse>().description, "PUT is a full replace")
        assertEquals(HttpStatusCode.Conflict, owner.client.putJson("$rooms/${a.id}", RoomUpdateRequest(b.name.uppercase())).status)
        val sameName = owner.client.putJson("$rooms/${a.id}", RoomUpdateRequest(renamed))
        assertEquals(HttpStatusCode.NoContent, sameName.status, "its own name is no clash")
        val invalid = listOf(
            RoomUpdateRequest(" "),
            RoomUpdateRequest("x".repeat(MAX_ROOM_NAME_LENGTH + 1)),
            RoomUpdateRequest("ok", "d".repeat(501)),
        )
        for (bad in invalid) {
            assertEquals(HttpStatusCode.BadRequest, owner.client.putJson("$rooms/${a.id}", bad).status, "expected 400 for $bad")
        }
    }

    @Test
    fun `archive and unarchive are idempotent, an archived room refuses a metadata PUT with the typed 409 and unarchive restores it`() =
        testApplication {
            usePostgresTestcontainer()
            val owner = actor("room-arch")
            val room = owner.client.create("Arch ${marker()}", template())

            withAuditCapture { capture ->
                fun count(event: String) = capture.events.count { it.message == event && it.hasKeyValue("roomId", room.id.toLong()) }
                assertEquals(HttpStatusCode.NoContent, owner.client.post("$rooms/${room.id}/archive").status)
                assertEquals(HttpStatusCode.NoContent, owner.client.post("$rooms/${room.id}/archive").status, "already archived: a no-op")
                val archived = owner.client.get("$rooms/${room.id}").body<RoomResponse>()
                assertTrue(archived.archived)
                assertNotNull(archived.archivedAt)
                assertNotNull(TestRooms.rawRoom(room.id)?.archivedAt)

                val blocked = owner.client.putJson("$rooms/${room.id}", RoomUpdateRequest("Blocked ${marker()}"))
                assertEquals(HttpStatusCode.Conflict, blocked.status)
                assertEquals(ROOM_ARCHIVED_PROBLEM_TYPE, blocked.body<ProblemDetail>().type)
                assertEquals(room.name, owner.client.get("$rooms/${room.id}").body<RoomResponse>().name)
                assertEquals(HttpStatusCode.OK, owner.client.get("$rooms/${room.id}/workflow").status, "an archived room still reads")

                assertEquals(HttpStatusCode.NoContent, owner.client.post("$rooms/${room.id}/unarchive").status)
                assertEquals(HttpStatusCode.NoContent, owner.client.post("$rooms/${room.id}/unarchive").status, "already active: a no-op")
                assertFalse(owner.client.get("$rooms/${room.id}").body<RoomResponse>().archived)
                val rename = owner.client.putJson("$rooms/${room.id}", RoomUpdateRequest("Back ${marker()}"))
                assertEquals(HttpStatusCode.NoContent, rename.status)

                capture.awaitEvent { it.message == "room.unarchived" && it.hasKeyValue("roomId", room.id.toLong()) }
                assertEquals(1, count("room.archived"), "the repeat archive audits nothing")
                assertEquals(1, count("room.unarchived"), "the repeat unarchive audits nothing")
            }
        }

    @Test
    fun `an administrator soft-deletes a room, even an archived one - it vanishes everywhere and its members and workflow rows stay`() =
        testApplication {
            usePostgresTestcontainer()
            val owner = actor("room-del")
            val admin = actor("room-del-admin", UserRole.ADMIN)
            val room = owner.client.create("Del ${marker()}", template())
            assertEquals(HttpStatusCode.NoContent, owner.client.post("$rooms/${room.id}/archive").status)

            assertEquals(HttpStatusCode.NoContent, admin.client.delete("$rooms/${room.id}").status)
            assertTrue(TestRooms.rawRoom(room.id)!!.markedAsDeleted, "delete must soft-delete")
            assertEquals(Triple(1, 3, 2), TestRooms.rawChildCounts(room.id), "members, statuses and fields stay with the deleted room")
            for (client in listOf(owner.client, admin.client)) {
                assertEquals(HttpStatusCode.NotFound, client.get("$rooms/${room.id}").status)
                assertEquals(HttpStatusCode.NotFound, client.get("$rooms/${room.id}/workflow").status)
            }
            assertEquals(HttpStatusCode.NotFound, admin.client.delete("$rooms/${room.id}").status, "a second delete finds no active room")
            assertEquals(HttpStatusCode.NotFound, owner.client.post("$rooms/${room.id}/unarchive").status)
            assertEquals(emptyList(), admin.client.get("$rooms?name=${room.name.replace(" ", "%20")}").body<RoomPageResponse>().items)
        }

    @Test
    fun `creating rooms rides the per-user room-name bucket of 30 a minute, 429 beyond it, audited`() = testApplication {
        usePostgresTestcontainer()
        val limited = actor("room-bucket")
        val other = actor("room-bucket-other")
        val body = RoomCreateRequest("Bucket ${marker()}", 999_999u) // an unknown template: a 400 that still takes a token

        withAuditCapture { capture ->
            repeat(30) { n ->
                assertEquals(HttpStatusCode.BadRequest, limited.client.postJson(rooms, body).status, "request ${n + 1}")
            }
            val rejected = limited.client.postJson(rooms, body)
            assertEquals(HttpStatusCode.TooManyRequests, rejected.status)
            assertEquals(429, rejected.body<ProblemDetail>().status)
            val event = capture.awaitEvent { it.message == "rate_limit.exceeded" && it.hasKeyValue("bucket", "room-name") }
            assertNotNull(event)
            assertTrue(event.hasKeyValue("byUserId", limited.id.toLong()))
            // The bucket is per USER: another account is untouched, and reads are not bucketed at all.
            assertEquals(HttpStatusCode.BadRequest, other.client.postJson(rooms, body).status)
            assertEquals(HttpStatusCode.OK, limited.client.get(rooms).status)
        }
    }

    @Test
    fun `mutations audit, a non-member's probe audits room access_denied and a refused or unknown one does not`() = testApplication {
        usePostgresTestcontainer()
        val owner = actor("room-aud")
        val outsider = actor("room-aud-out")
        val admin = actor("room-aud-admin", UserRole.ADMIN)
        val templateId = template()

        withAuditCapture { capture ->
            val room = owner.client.create("Aud ${marker()}", templateId, language = "pl")
            fun events(event: String) = capture.events.filter { it.message == event && it.hasKeyValue("roomId", room.id.toLong()) }
            val created = events("room.created").single()
            assertTrue(created.hasKeyValue("byUserId", owner.id.toLong()))
            assertTrue(created.hasKeyValue("templateId", templateId.toLong()))
            assertTrue(created.hasKeyValue("language", "pl"))
            assertTrue(created.hasKeyValue("name", room.name))

            assertEquals(HttpStatusCode.NoContent, owner.client.putJson("$rooms/${room.id}", RoomUpdateRequest(room.name)).status)
            assertTrue(events("room.updated").single().hasKeyValue("byUserId", owner.id.toLong()))

            // The outsider's probes are 404 and audited; the response never differs from "no such room".
            assertEquals(HttpStatusCode.NotFound, outsider.client.get("$rooms/${room.id}").status)
            assertEquals(HttpStatusCode.NotFound, outsider.client.get("$rooms/${room.id}/workflow").status)
            assertEquals(HttpStatusCode.NotFound, outsider.client.putJson("$rooms/${room.id}", RoomUpdateRequest("Probe")).status)
            assertEquals(HttpStatusCode.NotFound, outsider.client.delete("$rooms/${room.id}").status)
            assertEquals(4, events("room.access_denied").size)
            assertTrue(events("room.access_denied").all { it.hasKeyValue("byUserId", outsider.id.toLong()) })
            // An unknown id is not "denied" (there is nothing to deny), and an ADMIN outside the room is not denied either.
            assertEquals(HttpStatusCode.NotFound, outsider.client.get("$rooms/999999").status)
            assertEquals(HttpStatusCode.OK, admin.client.get("$rooms/${room.id}").status)
            assertEquals(4, events("room.access_denied").size)
            assertTrue(capture.events.none { it.message == "room.access_denied" && it.hasKeyValue("roomId", 999_999L) })

            assertEquals(HttpStatusCode.NoContent, admin.client.delete("$rooms/${room.id}").status)
            val deleted = capture.awaitEvent { it.message == "room.deleted" && it.hasKeyValue("roomId", room.id.toLong()) }
            assertNotNull(deleted)
            assertTrue(deleted.hasKeyValue("byUserId", admin.id.toLong()))
            assertEquals(1, events("room.updated").size, "the one update, nothing for the refused probes")
        }
    }

    @Test
    fun `a new room counts no threads and starting one counts it - the real usage port`() = testApplication {
        usePostgresTestcontainer()
        val owner = actor("room-nousage")
        val room = owner.client.create("NoUsage ${marker()}", template())
        assertTrue(owner.client.workflow(room.id).statuses.all { it.threadCount == 0L }, "a new room has no threads")
        TestThreads.seed(room.id, owner.id)
        val counts = owner.client.workflow(room.id).statuses.associate { it.key to it.threadCount }
        assertEquals(1L, counts["open"], "the thread starts in the initial status")
        assertEquals(1L, counts.values.sum())
    }

    @Test
    fun `the workflow read takes each status's threadCount from the registered usage port`() = testApplication {
        configureApp()
        application { attributes.put(WorkflowUsageKey, FakeWorkflowUsage(mapOf("open" to 3L, "done" to 1L, "ghost" to 9L))) }
        startApplication()
        val owner = actor("room-usage")
        val room = owner.client.create("Usage ${marker()}", template())
        assertEquals(listOf(3L, 0L, 1L), owner.client.workflow(room.id).statuses.map { it.threadCount })
    }

    @Test
    fun `a caller demoted while their write waits for the room lock is refused with 403 and nothing is written`() = testApplication {
        usePostgresTestcontainer()
        val owner = actor("room-race")
        val room = owner.client.create("Race ${marker()}", template())
        val admin = actor("room-race-admin", UserRole.ADMIN)

        PostgresTestSupport.connect().use { raw ->
            raw.autoCommit = false
            // The raw transaction holds the room lock and demotes the caller; neither is visible until it commits.
            raw.prepareStatement("SELECT id FROM rooms WHERE id = ? FOR NO KEY UPDATE").use { st ->
                st.setInt(1, room.id.toInt())
                st.executeQuery().close()
            }
            raw.prepareStatement("UPDATE room_members SET role = 'MEMBER' WHERE room_id = ? AND user_id = ?").use { st ->
                st.setInt(1, room.id.toInt())
                st.setLong(2, owner.id.toLong())
                st.executeUpdate()
            }
            try {
                coroutineScope {
                    // The route's own guard passes (the demotion is uncommitted), the write then waits for the room lock.
                    val putting = async { owner.client.putJson("$rooms/${room.id}", RoomUpdateRequest("Raced ${marker()}")) }
                    awaitLockWait("FOR NO KEY UPDATE")
                    raw.commit()
                    assertEquals(HttpStatusCode.Forbidden, putting.await().status)
                }
            } finally {
                raw.rollback() // a no-op after the commit; releases the lock if an assertion failed first
            }
        }
        assertEquals(room.name, admin.client.get("$rooms/${room.id}").body<RoomResponse>().name, "the refused write changed nothing")
    }

    @Test
    fun `renaming shares the create bucket - 30 a minute per user across both`() = testApplication {
        usePostgresTestcontainer()
        val limited = actor("room-rename-bucket")
        val other = actor("room-rename-bucket-other")
        val templateId = template()
        val room = limited.client.create("Rename bucket ${marker()}", templateId) // token 1
        val unknownTemplate = RoomCreateRequest("Bucket ${marker()}", 999_999u)

        repeat(19) { n ->
            val renamed = limited.client.putJson("$rooms/${room.id}", RoomUpdateRequest(room.name))
            assertEquals(HttpStatusCode.NoContent, renamed.status, "rename ${n + 1}")
        }
        repeat(10) { assertEquals(HttpStatusCode.BadRequest, limited.client.postJson(rooms, unknownTemplate).status) }
        // 30 tokens are spent: the next rename is refused by the bucket, not by the room.
        assertEquals(HttpStatusCode.TooManyRequests, limited.client.putJson("$rooms/${room.id}", RoomUpdateRequest(room.name)).status)
        assertEquals(HttpStatusCode.TooManyRequests, limited.client.postJson(rooms, unknownTemplate).status, "the one bucket covers both")
        assertEquals(HttpStatusCode.OK, limited.client.get("$rooms/${room.id}").status, "reads are not bucketed")
        // A stranger's rename of a room they cannot see still takes THEIR tokens (the bucket sits before the guards), and is a 404.
        assertEquals(HttpStatusCode.NotFound, other.client.putJson("$rooms/${room.id}", RoomUpdateRequest("Nope ${marker()}")).status)
    }

    @Test
    fun `a name clash on create or rename is audited as room name_conflict, with the room id for a rename`() = testApplication {
        usePostgresTestcontainer()
        val owner = actor("room-clash")
        val rival = actor("room-clash-rival")
        val templateId = template()
        val taken = owner.client.create("Taken ${marker()}", templateId)
        val mine = rival.client.create("Mine ${marker()}", templateId)

        withAuditCapture { capture ->
            val clash = rival.client.putJson("$rooms/${mine.id}", RoomUpdateRequest(taken.name.uppercase()))
            assertEquals(HttpStatusCode.Conflict, clash.status)
            val rename = capture.awaitEvent { it.message == "room.name_conflict" && it.hasKeyValue("roomId", mine.id.toLong()) }
            assertNotNull(rename)
            assertTrue(rename.hasKeyValue("byUserId", rival.id.toLong()))

            assertEquals(HttpStatusCode.Conflict, rival.client.postJson(rooms, RoomCreateRequest(taken.name, templateId)).status)
            val create = capture.awaitEvent {
                it.message == "room.name_conflict" && it.hasKeyValue("roomId", null) && it.hasKeyValue("byUserId", rival.id.toLong())
            }
            assertNotNull(create)
            // A refused rename that is not a clash audits nothing of the kind.
            assertEquals(HttpStatusCode.NoContent, rival.client.putJson("$rooms/${mine.id}", RoomUpdateRequest("Fresh ${marker()}")).status)
            assertEquals(2, capture.events.count { it.message == "room.name_conflict" })
        }
    }

    @Test
    fun `a room name with invisible formatting characters is a 400 on create and rename, ordinary Unicode passes`() = testApplication {
        usePostgresTestcontainer()
        val owner = actor("room-invisible")
        val templateId = template()
        val room = owner.client.create("Visible ${marker()}", templateId)
        val hidden = listOf(
            "zero\u200Bwidth", "joiner\u200D", "bidi\u202Eoverride", "bom\uFEFF",
            "soft\u00ADhyphen", "line\u2028separator", "para\u2029separator",
        )
        for (name in hidden) {
            val created = owner.client.postJson(rooms, RoomCreateRequest("$name ${marker()}", templateId))
            assertEquals(HttpStatusCode.BadRequest, created.status, "create ${name.length}")
            val renamed = owner.client.putJson("$rooms/${room.id}", RoomUpdateRequest(name))
            assertEquals(HttpStatusCode.BadRequest, renamed.status, "rename ${name.length}")
        }
        val fine = owner.client.create("Zażółć gęślą \uD83D\uDEA8 ${marker()}", templateId)
        assertTrue(fine.name.startsWith("Zażółć"))
    }

    @Test
    fun `a negative template id in the body is a 400`() = testApplication {
        usePostgresTestcontainer()
        val owner = actor("room-negative")
        val response = owner.client.post(rooms) {
            contentType(ContentType.Application.Json)
            setBody("""{"name":"Negative ${marker()}","templateId":-1}""")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `a caller whose account is deleted while their write waits for the room lock gets 404 and nothing is written`() = testApplication {
        usePostgresTestcontainer()
        val owner = actor("room-gone")
        val room = owner.client.create("Gone ${marker()}", template())
        val admin = actor("room-gone-admin", UserRole.ADMIN)

        holdingRoomLock(room.id, "UPDATE users SET marked_as_deleted = TRUE WHERE id = ?", owner.id) {
            coroutineScope {
                val putting = async { owner.client.putJson("$rooms/${room.id}", RoomUpdateRequest("Raced ${marker()}")) }
                awaitLockWait("FOR NO KEY UPDATE")
                it.commit()
                assertEquals(HttpStatusCode.NotFound, putting.await().status)
            }
        }
        assertEquals(room.name, admin.client.get("$rooms/${room.id}").body<RoomResponse>().name)
    }

    @Test
    fun `an administrator demoted while their write waits for the room lock is judged as a plain user`() = testApplication {
        usePostgresTestcontainer()
        val owner = actor("room-demote")
        val room = owner.client.create("Demote ${marker()}", template())
        val admin = actor("room-demote-admin", UserRole.ADMIN)

        holdingRoomLock(room.id, "UPDATE users SET \"role\" = 'USER' WHERE id = ?", admin.id) {
            coroutineScope {
                // The token still says ADMIN and the route's own checks pass; the locked re-read of the users row does not.
                val putting = async { admin.client.putJson("$rooms/${room.id}", RoomUpdateRequest("Raced ${marker()}")) }
                awaitLockWait("FOR NO KEY UPDATE")
                it.commit()
                assertEquals(HttpStatusCode.NotFound, putting.await().status)
            }
        }
        assertEquals(room.name, owner.client.get("$rooms/${room.id}").body<RoomResponse>().name, "the refused write changed nothing")
    }

    /** Holds the room lock on a raw transaction that also runs [statement] (bound to [userId]) until [body] commits it. */
    private suspend fun holdingRoomLock(roomId: UInt, statement: String, userId: UInt, body: suspend (java.sql.Connection) -> Unit) {
        PostgresTestSupport.connect().use { raw ->
            raw.autoCommit = false
            raw.prepareStatement("SELECT id FROM rooms WHERE id = ? FOR NO KEY UPDATE").use { st ->
                st.setInt(1, roomId.toInt())
                st.executeQuery().close()
            }
            raw.prepareStatement(statement).use { st ->
                st.setLong(1, userId.toLong())
                st.executeUpdate()
            }
            try {
                body(raw)
            } finally {
                raw.rollback() // a no-op after the commit; releases the lock if an assertion failed first
            }
        }
    }

    @Test
    fun `a room is new after someone else's activity, seen after the member says so, and never new from one's own activity`() =
        testApplication {
            usePostgresTestcontainer()
            val owner = actor("room-unread-own")
            val member = actor("room-unread-mem")
            val admin = actor("room-unread-adm", UserRole.ADMIN)
            val template = TestThreads.seedTemplate().also { mintedTemplates += it }
            val name = "Unread ${marker()}"
            val room = TestRooms.seed(owner.id, template, name).also { mintedRooms += it }
            TestRooms.addMember(room, member.id)
            suspend fun HttpClient.dot() = get("$rooms?name=$name").body<RoomPageResponse>().items.single().unread

            delay(5)
            assertFalse(member.client.dot(), "a fresh member has seen the room")
            assertFalse(owner.client.dot())
            TestThreads.seed(room, owner.id)
            assertTrue(member.client.dot(), "new activity by somebody else")
            assertFalse(owner.client.dot(), "one's own activity never makes a room new")
            assertFalse(admin.client.dot(), "an ADMIN outside the room has no read state")

            delay(5)
            assertEquals(HttpStatusCode.NoContent, member.client.postJson("$rooms/$room/seen", "").status)
            assertFalse(member.client.dot(), "seen after the member says so")
            delay(5)
            TestThreads.post(room, 1, owner.id, "more")
            assertTrue(member.client.dot(), "and new again after the next activity")
            delay(5)
            TestThreads.post(room, 1, member.id, "my own reply")
            assertFalse(member.client.dot(), "the member's own activity has them seen it")
            assertTrue(owner.client.dot(), "the member's reply is news to the owner")
        }
}
