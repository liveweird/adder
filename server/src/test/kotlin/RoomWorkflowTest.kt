package ch.nokillswit

import ch.nokillswit.authz.RoomAccessType
import ch.nokillswit.authz.RoomRole
import ch.nokillswit.infra.revision.EXPECTED_REVISION_HEADER
import ch.nokillswit.infra.revision.REVISION_CONFLICT_PROBLEM_TYPE
import ch.nokillswit.plugins.ProblemDetail
import ch.nokillswit.rooms.FIELD_TYPE_IMMUTABLE_PROBLEM_TYPE
import ch.nokillswit.rooms.ROOM_ARCHIVED_PROBLEM_TYPE
import ch.nokillswit.rooms.RoomFieldDef
import ch.nokillswit.rooms.RoomOptionDef
import ch.nokillswit.rooms.RoomStatusDef
import ch.nokillswit.rooms.RoomWorkflowRequest
import ch.nokillswit.rooms.RoomWorkflowResponse
import ch.nokillswit.rooms.STATUS_IN_USE_PROBLEM_TYPE
import ch.nokillswit.rooms.WorkflowUsageKey
import ch.nokillswit.users.UserRole
import ch.nokillswit.workflows.FieldType
import ch.nokillswit.workflows.StatusCategory
import ch.nokillswit.workflows.StatusColour
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking

/**
 * The room workflow PUT (`PUT /api/v1/rooms/{id}/workflow`, 0.2.0 C9) over HTTP: reorder and relabel in place, retire and revive a key,
 * the `X-Expected-Revision` guard (missing 400, stale 409, the lock-wait race), the archived 409, the immutable field type, every kernel
 * rule as a 400, the key budget, and - through `FakeWorkflowUsage` - the thread-usage paths (`status-in-use`, the replacement mapping, the
 * moves). The role matrix per caller is the PUT row of `RoomAuthzMatrixTest`. Rooms, users and templates are SHARED suite state: every
 * test mints UNIQUE rows and removes them.
 */
class RoomWorkflowTest {
    private val rooms = "/api/v1/rooms"
    private val mintedRooms = mutableListOf<UInt>()
    private val mintedUsers = mutableListOf<UInt>()
    private var templateId: UInt? = null

    @AfterTest
    fun cleanUp() = runBlocking<Unit> {
        TestRooms.purge(mintedRooms)
        TestRooms.softDeleteUsers(mintedUsers)
        templateId?.let { TestTemplates.delete(it) }
    }

    private fun marker() = UUID.randomUUID().toString().substring(0, 8)

    private suspend fun ApplicationTestBuilder.actor(prefix: String, role: UserRole = UserRole.USER): RoomActor =
        seedActor(prefix, role).also { mintedUsers += it.id }

    /** A room owned by [owner]: statuses `open` (initial), `half` (OPEN), `done` (CLOSED); fields `severity` (SELECT), `note` (TEXT). */
    private suspend fun room(owner: RoomActor): UInt {
        val template = templateId ?: TestTemplates.seed("wf").also { templateId = it }
        return TestRooms.seed(owner.id, template, "Workflow ${marker()}", accessType = RoomAccessType.PUBLIC).also { mintedRooms += it }
    }

    private suspend fun HttpClient.workflow(room: UInt): RoomWorkflowResponse = get("$rooms/$room/workflow").body()

    private suspend fun HttpClient.putWorkflow(room: UInt, revision: Long?, body: RoomWorkflowRequest): HttpResponse =
        put("$rooms/$room/workflow") {
            if (revision != null) header(EXPECTED_REVISION_HEADER, revision.toString())
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private suspend fun HttpClient.putRaw(room: UInt, revision: Long?, json: String): HttpResponse = put("$rooms/$room/workflow") {
        if (revision != null) header(EXPECTED_REVISION_HEADER, revision.toString())
        contentType(ContentType.Application.Json)
        setBody(json)
    }

    /** The document the room has now, as the PUT body that would change nothing. */
    private fun RoomWorkflowResponse.asRequest() = RoomWorkflowRequest(
        statuses = statuses.map { RoomStatusDef(it.key, it.label, it.colour, it.category, it.initial) },
        fields = fields.map { RoomFieldDef(it.key, it.label, it.type, it.required, it.options) },
    )

    /** A new status `<key>` labelled with its key capitalised (unique among the fixture's labels). */
    private fun openStatus(key: String, colour: StatusColour = StatusColour.GRAY, category: StatusCategory = StatusCategory.OPEN) =
        RoomStatusDef(key, key.replaceFirstChar { it.uppercase() }, colour, category, initial = false)

    private fun RoomWorkflowRequest.status(key: String) = statuses.single { it.key == key }

    private fun RoomWorkflowRequest.field(key: String) = fields.single { it.key == key }

    private fun RoomWorkflowRequest.withoutStatus(key: String) = copy(statuses = statuses.filter { it.key != key })

    private fun RoomWorkflowRequest.mapStatus(key: String, change: (RoomStatusDef) -> RoomStatusDef) =
        copy(statuses = statuses.map { if (it.key == key) change(it) else it })

    private fun RoomWorkflowRequest.mapField(key: String, change: (RoomFieldDef) -> RoomFieldDef) =
        copy(fields = fields.map { if (it.key == key) change(it) else it })

    private suspend fun ApplicationTestBuilder.withUsage(usage: FakeWorkflowUsage) {
        configureApp()
        application { attributes.put(WorkflowUsageKey, usage) }
        startApplication()
    }

    @Test
    fun `statuses and fields are reordered and relabelled in place under the next revision`() = testApplication {
        usePostgresTestcontainer()
        val owner = actor("wf-edit")
        val room = room(owner)
        val before = owner.client.workflow(room)
        assertEquals(1, before.revision)
        val current = before.asRequest()

        val edited = current.copy(
            statuses = listOf(
                current.status("open").copy(label = "  Opened  "),
                current.status("done").copy(label = "Zażółć 🐍", colour = StatusColour.RED),
                current.status("half"),
            ),
            fields = listOf(
                current.field("note").copy(label = "Remarks", required = true),
                current.field("severity").copy(options = listOf(RoomOptionDef("s2", "Two"), RoomOptionDef("s3", "Three"))),
            ),
        )
        assertEquals(HttpStatusCode.NoContent, owner.client.putWorkflow(room, 1, edited).status)

        val after = owner.client.workflow(room)
        assertEquals(2, after.revision)
        assertEquals(listOf("open", "done", "half"), after.statuses.map { it.key })
        assertEquals(listOf("Opened", "Zażółć 🐍", "Half only"), after.statuses.map { it.label }, "labels are trimmed")
        assertEquals(StatusColour.RED, after.statuses[1].colour)
        assertEquals(listOf("note", "severity"), after.fields.map { it.key })
        assertEquals("Remarks", after.fields[0].label)
        assertTrue(after.fields[0].required)
        assertEquals(listOf("s2", "s3"), after.fields[1].options.map { it.key })
        // In place: no row was added or retired, positions are the new indices.
        val rows = TestRooms.rawStatuses(room)
        assertEquals(listOf("done" to 1, "half" to 2, "open" to 0), rows.map { it.key to it.position })
        assertTrue(rows.none { it.retired })
        assertEquals(1, rows.count { it.initial })
        assertEquals(2, TestRooms.rawFields(room).size)
    }

    @Test
    fun `a dropped key retires and keeps its row - the same key named again revives it, in any category`() = testApplication {
        usePostgresTestcontainer()
        val owner = actor("wf-revive")
        val room = room(owner)
        val current = owner.client.workflow(room).asRequest()

        // Retire the status `half` and the field `note`.
        val slim = current.withoutStatus("half").copy(fields = current.fields.filter { it.key != "note" })
        assertEquals(HttpStatusCode.NoContent, owner.client.putWorkflow(room, 1, slim).status)
        val retired = owner.client.workflow(room)
        assertEquals(listOf("open", "done"), retired.statuses.map { it.key })
        assertEquals(listOf("severity"), retired.fields.map { it.key })
        assertEquals(true, TestRooms.rawStatuses(room).single { it.key == "half" }.retired, "the row stays, retired")
        assertEquals(true, TestRooms.rawFields(room).single { it.key == "note" }.retired)

        // Name them again, `half` now CLOSED and first: the same rows come back, no duplicates.
        val revived = slim.copy(
            statuses = listOf(current.status("half").copy(label = "Parked", category = StatusCategory.CLOSED)) + slim.statuses,
            fields = slim.fields + current.field("note").copy(label = "Back again"),
        )
        assertEquals(HttpStatusCode.NoContent, owner.client.putWorkflow(room, 2, revived).status)
        val after = owner.client.workflow(room)
        assertEquals(3, after.revision)
        assertEquals(listOf("half", "open", "done"), after.statuses.map { it.key })
        assertEquals("Parked", after.statuses[0].label)
        assertEquals(StatusCategory.CLOSED, after.statuses[0].category)
        assertEquals(listOf("severity", "note"), after.fields.map { it.key })
        assertEquals(3, TestRooms.rawStatuses(room).size)
        assertEquals(2, TestRooms.rawFields(room).size)
        assertTrue(TestRooms.rawStatuses(room).none { it.retired } && TestRooms.rawFields(room).none { it.retired })
    }

    @Test
    fun `the initial status can move to another status or be retired in the same PUT`() = testApplication {
        usePostgresTestcontainer()
        val owner = actor("wf-initial")
        val room = room(owner)
        val current = owner.client.workflow(room).asRequest()

        val swapped = current.mapStatus("open") { it.copy(initial = false) }.mapStatus("half") { it.copy(initial = true) }
        assertEquals(HttpStatusCode.NoContent, owner.client.putWorkflow(room, 1, swapped).status)
        assertEquals(listOf("half"), owner.client.workflow(room).statuses.filter { it.initial }.map { it.key })

        // Retire the old initial row outright while a new status becomes the initial one.
        val replaced = swapped.copy(
            statuses = swapped.statuses.withoutKey("open") + RoomStatusDef("fresh", "Fresh", StatusColour.GRAY, StatusCategory.OPEN, false),
        )
        assertEquals(HttpStatusCode.NoContent, owner.client.putWorkflow(room, 2, replaced).status)
        val rows = TestRooms.rawStatuses(room)
        assertEquals(listOf("half"), rows.filter { it.initial }.map { it.key }, "exactly one row carries the initial flag")
        assertEquals(true, rows.single { it.key == "open" }.retired)
    }

    private fun List<RoomStatusDef>.withoutKey(key: String) = filter { it.key != key }

    @Test
    fun `a new status or field is added with its position, and an administrator who is not a member may edit`() = testApplication {
        usePostgresTestcontainer()
        val owner = actor("wf-add")
        val admin = actor("wf-add-admin", UserRole.ADMIN)
        val room = room(owner)
        val current = owner.client.workflow(room).asRequest()
        val grown = current.copy(
            statuses = current.statuses + RoomStatusDef("blocked", "Blocked", StatusColour.ORANGE, StatusCategory.OPEN, false),
            fields = current.fields + RoomFieldDef("link", "Link", FieldType.URL, false, emptyList()),
        )
        assertEquals(HttpStatusCode.NoContent, admin.client.putWorkflow(room, 1, grown).status)
        val after = owner.client.workflow(room)
        assertEquals(listOf("open", "half", "done", "blocked"), after.statuses.map { it.key })
        assertEquals(listOf("severity", "note", "link"), after.fields.map { it.key })
        assertEquals(StatusColour.ORANGE, after.statuses[3].colour)
    }

    @Test
    fun `the header is required - missing or malformed is 400 - and a stale revision is the typed 409`() = testApplication {
        usePostgresTestcontainer()
        val owner = actor("wf-rev")
        val room = room(owner)
        val current = owner.client.workflow(room).asRequest()
        val renamed = current.mapStatus("open") { it.copy(label = "Renamed") }

        assertEquals(HttpStatusCode.BadRequest, owner.client.putWorkflow(room, null, renamed).status)
        for (bad in listOf("0", "-1", "abc", "1.5", "99999999999999999999", "")) {
            val response = owner.client.put("$rooms/$room/workflow") {
                header(EXPECTED_REVISION_HEADER, bad)
                contentType(ContentType.Application.Json)
                setBody(renamed)
            }
            assertEquals(HttpStatusCode.BadRequest, response.status, "header '$bad'")
        }
        // The header is checked before the body: a bad body without the header is still the header's 400, never a 409.
        val headerless = owner.client.putRaw(room, null, "{ not json")
        assertEquals(HttpStatusCode.BadRequest, headerless.status)
        assertTrue(headerless.body<ProblemDetail>().detail.orEmpty().contains("header is required"), "the header's 400, not the body's")

        assertEquals(HttpStatusCode.NoContent, owner.client.putWorkflow(room, 1, renamed).status)
        for (stale in listOf(1L, 3L)) {
            val response = owner.client.putWorkflow(room, stale, current.mapStatus("open") { it.copy(label = "Stale $stale") })
            assertEquals(HttpStatusCode.Conflict, response.status, "revision $stale")
            val problem = response.body<ProblemDetail>()
            assertEquals(REVISION_CONFLICT_PROBLEM_TYPE, problem.type)
            assertNull(problem.keys)
        }
        val after = owner.client.workflow(room)
        assertEquals(2, after.revision, "only the one successful PUT bumped it")
        assertEquals("Renamed", after.statuses.first { it.key == "open" }.label)
    }

    @Test
    fun `a PUT that waits on the room lock while another writer bumps the revision answers 409 revision-conflict`() = testApplication {
        usePostgresTestcontainer()
        val owner = actor("wf-race")
        val room = room(owner)
        val current = owner.client.workflow(room).asRequest()
        val renamed = current.mapStatus("open") { it.copy(label = "Raced") }

        PostgresTestSupport.connect().use { raw ->
            raw.autoCommit = false
            // The raw transaction holds the room lock and bumps the revision; neither is visible until it commits.
            raw.prepareStatement("SELECT id FROM rooms WHERE id = ? FOR NO KEY UPDATE").use { st ->
                st.setInt(1, room.toInt())
                st.executeQuery().close()
            }
            raw.prepareStatement("UPDATE rooms SET workflow_revision = workflow_revision + 1 WHERE id = ?").use { st ->
                st.setInt(1, room.toInt())
                st.executeUpdate()
            }
            try {
                coroutineScope {
                    // The route passes its guards on the committed state (revision 1 is still current), the write waits for the lock.
                    val putting = async { owner.client.putWorkflow(room, 1, renamed) }
                    awaitLockWait("FOR NO KEY UPDATE")
                    raw.commit()
                    val response = putting.await()
                    assertEquals(HttpStatusCode.Conflict, response.status)
                    assertEquals(REVISION_CONFLICT_PROBLEM_TYPE, response.body<ProblemDetail>().type)
                }
            } finally {
                raw.rollback() // a no-op after the commit; releases the lock if an assertion failed first
            }
        }
        val after = owner.client.workflow(room)
        assertEquals(2, after.revision)
        assertEquals("Open", after.statuses.first { it.key == "open" }.label, "the refused write changed nothing")
    }

    @Test
    fun `an archived room refuses the PUT with the typed 409 even when the revision is stale, and accepts it again once unarchived`() =
        testApplication {
            usePostgresTestcontainer()
            val owner = actor("wf-archived")
            val room = room(owner)
            val current = owner.client.workflow(room).asRequest()
            assertEquals(HttpStatusCode.NoContent, owner.client.post("$rooms/$room/archive").status)

            for (revision in listOf(1L, 9L)) {
                val response = owner.client.putWorkflow(room, revision, current)
                assertEquals(HttpStatusCode.Conflict, response.status, "revision $revision")
                assertEquals(ROOM_ARCHIVED_PROBLEM_TYPE, response.body<ProblemDetail>().type)
            }
            assertEquals(1, owner.client.workflow(room).revision)
            assertEquals(HttpStatusCode.NoContent, owner.client.post("$rooms/$room/unarchive").status)
            assertEquals(HttpStatusCode.NoContent, owner.client.putWorkflow(room, 1, current).status)
        }

    @Test
    fun `a field's type is immutable per key - retired keys included - and the 409 names the keys`() = testApplication {
        usePostgresTestcontainer()
        val owner = actor("wf-type")
        val room = room(owner)
        val current = owner.client.workflow(room).asRequest()

        val retyped = current.mapField("note") { it.copy(type = FieldType.NUMBER) }
        val live = owner.client.putWorkflow(room, 1, retyped)
        assertEquals(HttpStatusCode.Conflict, live.status)
        val liveProblem = live.body<ProblemDetail>()
        assertEquals(FIELD_TYPE_IMMUTABLE_PROBLEM_TYPE, liveProblem.type)
        assertEquals(listOf("note"), liveProblem.keys)

        // Retire it, then bring the key back with another type: still refused, naming the retired key.
        val without = current.copy(fields = current.fields.filter { it.key != "note" })
        assertEquals(HttpStatusCode.NoContent, owner.client.putWorkflow(room, 1, without).status)
        val retired = owner.client.putWorkflow(room, 2, retyped)
        assertEquals(HttpStatusCode.Conflict, retired.status)
        assertEquals(listOf("note"), retired.body<ProblemDetail>().keys)
        // The same type revives it.
        assertEquals(HttpStatusCode.NoContent, owner.client.putWorkflow(room, 2, current).status)
        assertEquals(listOf("severity", "note"), owner.client.workflow(room).fields.map { it.key })
        // A select's options may change freely (the type is the same).
        val options = current.mapField("severity") { it.copy(options = listOf(RoomOptionDef("only", "Only one"))) }
        assertEquals(HttpStatusCode.NoContent, owner.client.putWorkflow(room, 3, options).status)
    }

    @Test
    fun `every kernel and label rule is a 400 and changes nothing`() = testApplication {
        usePostgresTestcontainer()
        val owner = actor("wf-kernel")
        val room = room(owner)
        val before = owner.client.workflow(room)
        val c = before.asRequest()
        fun status(key: String, category: StatusCategory = StatusCategory.OPEN) =
            RoomStatusDef(key, "Label $key", StatusColour.GRAY, category, false)
        fun options(count: Int) = (1..count).map { RoomOptionDef("o$it", "Option $it") }
        val select = c.field("severity")
        val tooMany = (1..19).map { status("s$it") } + c.status("open") + c.status("done")

        val cases = mapOf(
            "a single status" to c.copy(statuses = listOf(c.status("open"))),
            "21 statuses" to c.copy(statuses = tooMany),
            "no initial status" to c.mapStatus("open") { it.copy(initial = false) },
            "two initial statuses" to c.mapStatus("half") { it.copy(initial = true) },
            "a CLOSED initial status" to c.mapStatus("open") { it.copy(category = StatusCategory.CLOSED) },
            "no CLOSED status" to c.mapStatus("done") { it.copy(category = StatusCategory.OPEN) },
            "a duplicate status key" to c.copy(statuses = c.statuses + c.status("half")),
            "an invalid status key" to c.mapStatus("half") { it.copy(key = "Bad Key") },
            "a trailing-hyphen key" to c.mapStatus("half") { it.copy(key = "half-") },
            "a duplicate field key" to c.copy(fields = c.fields + c.field("note")),
            "an invalid field key" to c.mapField("note") { it.copy(key = "1note") },
            "21 fields" to c.copy(fields = (1..21).map { RoomFieldDef("f$it", "Field $it", FieldType.TEXT, false, emptyList()) }),
            "options on a TEXT field" to c.mapField("note") { it.copy(options = options(1)) },
            "a SELECT without options" to c.mapField("severity") { it.copy(options = emptyList()) },
            "51 options" to c.mapField("severity") { it.copy(options = options(51)) },
            "a duplicate option key" to c.mapField("severity") { it.copy(options = listOf(select.options[0], select.options[0])) },
            "an invalid option key" to c.mapField("severity") { it.copy(options = listOf(RoomOptionDef("S 1", "One"))) },
            "a blank status label" to c.mapStatus("half") { it.copy(label = "   ") },
            "a 61-character status label" to c.mapStatus("half") { it.copy(label = "x".repeat(61)) },
            "a blank field label" to c.mapField("note") { it.copy(label = "") },
            "a long field label" to c.mapField("note") { it.copy(label = "x".repeat(61)) },
            "a blank option label" to c.mapField("severity") { it.copy(options = listOf(RoomOptionDef("s1", " "))) },
            "a long option label" to c.mapField("severity") { it.copy(options = listOf(RoomOptionDef("s1", "x".repeat(61)))) },
            "status labels equal ignoring case" to c.mapStatus("half") { it.copy(label = "DONE") },
            "status labels equal after trimming" to c.mapStatus("half") { it.copy(label = " done ") },
            "field labels equal ignoring case" to c.mapField("note") { it.copy(label = "SEVERITY") },
            "option labels equal ignoring case" to
                c.mapField("severity") { it.copy(options = listOf(RoomOptionDef("s1", "Same"), RoomOptionDef("s2", "SAME"))) },
            "a control character in a label" to c.mapStatus("half") { it.copy(label = "a\u0007b") },
            "a control character in an option label" to
                c.mapField("severity") { it.copy(options = listOf(RoomOptionDef("s1", "a\nb"))) },
            "21 replacements" to c.copy(replacements = (1..21).associate { "r$it" to "open" }),
            "a replacement of a status nothing retires" to c.copy(replacements = mapOf("half" to "open")),
        )
        for ((name, body) in cases) {
            val response = owner.client.putWorkflow(room, 1, body)
            assertEquals(HttpStatusCode.BadRequest, response.status, name)
        }
        for ((name, json) in mapOf(
            "an unknown colour" to
                """{"statuses":[{"key":"open","label":"Open","colour":"PURPLE","category":"OPEN","initial":true}],"fields":[]}""",
            "a missing statuses list" to """{"fields":[]}""",
            "malformed JSON" to "{ not json",
        )) {
            assertEquals(HttpStatusCode.BadRequest, owner.client.putRaw(room, 1, json).status, name)
        }
        assertEquals(before, owner.client.workflow(room), "no refused PUT changed anything, the revision included")
    }

    @Test
    fun `the room reserves at most 200 status keys and 200 field keys, retired ones included - a plain 409`() = testApplication {
        usePostgresTestcontainer()
        val owner = actor("wf-budget")
        val room = room(owner)
        TestRooms.seedRetiredKeys(room, 197) // 3 statuses + 197 retired = 200 keys; 2 fields + 197 retired = 199
        val current = owner.client.workflow(room).asRequest()

        val extraStatus = current.copy(statuses = current.statuses + openStatus("extra"))
        val full = owner.client.putWorkflow(room, 1, extraStatus)
        assertEquals(HttpStatusCode.Conflict, full.status)
        assertEquals("about:blank", full.body<ProblemDetail>().type, "a plain conflict, not a typed one")
        // Reviving a retired key costs nothing; the document itself is within every limit.
        val revive = current.copy(statuses = current.statuses + openStatus("r1"))
        assertEquals(HttpStatusCode.NoContent, owner.client.putWorkflow(room, 1, revive).status)

        // The field list has one slot left, then it is full too.
        val withField = { key: String, base: RoomWorkflowRequest ->
            base.copy(fields = base.fields + RoomFieldDef(key, key, FieldType.TEXT, false, emptyList()))
        }
        val afterRevive = owner.client.workflow(room).asRequest()
        assertEquals(HttpStatusCode.NoContent, owner.client.putWorkflow(room, 2, withField("last-slot", afterRevive)).status)
        val fieldFull = owner.client.putWorkflow(room, 3, withField("one-more", owner.client.workflow(room).asRequest()))
        assertEquals(HttpStatusCode.Conflict, fieldFull.status)
        assertEquals(3, owner.client.workflow(room).revision, "the refused PUT bumped nothing")
    }

    @Test
    fun `the guards run before the header and the body - 404 for a non-member, 403 for a member, then 400`() = testApplication {
        usePostgresTestcontainer()
        val owner = actor("wf-guards")
        val member = actor("wf-guards-member")
        val outsider = actor("wf-guards-outsider")
        val room = room(owner)
        TestRooms.addMember(room, member.id, RoomRole.MEMBER)
        val current = owner.client.workflow(room).asRequest()

        // No header and a broken body: the verdict is still the caller's standing, never the 400.
        assertEquals(HttpStatusCode.NotFound, outsider.client.putRaw(room, null, "{ not json").status)
        assertEquals(HttpStatusCode.Forbidden, member.client.putRaw(room, null, "{ not json").status)
        assertEquals(HttpStatusCode.BadRequest, owner.client.putRaw(room, null, "{ not json").status)
        assertEquals(HttpStatusCode.NotFound, outsider.client.putWorkflow(room, 1, current).status)
        assertEquals(HttpStatusCode.Forbidden, member.client.putWorkflow(room, 1, current).status)
        // An unknown room is a 404 whoever asks and whatever they send.
        assertEquals(HttpStatusCode.NotFound, owner.client.putWorkflow(999_999u, null, current).status)
        assertEquals(1, owner.client.workflow(room).revision)
    }

    @Test
    fun `a deleted room answers 404 to its owner`() = testApplication {
        usePostgresTestcontainer()
        val owner = actor("wf-deleted")
        val admin = actor("wf-deleted-admin", UserRole.ADMIN)
        val room = room(owner)
        val current = owner.client.workflow(room).asRequest()
        assertEquals(HttpStatusCode.NoContent, admin.client.delete("$rooms/$room").status)
        assertEquals(HttpStatusCode.NotFound, owner.client.putWorkflow(room, 1, current).status)
    }

    @Test
    fun `a successful PUT is audited with the revision and the counts - a refused one is not`() = testApplication {
        usePostgresTestcontainer()
        val owner = actor("wf-audit")
        val room = room(owner)
        val current = owner.client.workflow(room).asRequest()
        val change = current.copy(
            // `half` retires, `blocked` is new; `note` retires, `link` is new.
            statuses = current.statuses.withoutKey("half") + openStatus("blocked", StatusColour.ORANGE),
            fields = current.fields.filter { it.key != "note" } + RoomFieldDef("link", "Link", FieldType.URL, false, emptyList()),
        )
        withAuditCapture { capture ->
            fun updates() = capture.events.filter { it.message == "room.workflow_updated" && it.hasKeyValue("roomId", room.toLong()) }
            assertEquals(HttpStatusCode.Conflict, owner.client.putWorkflow(room, 7, change).status)
            assertEquals(HttpStatusCode.BadRequest, owner.client.putWorkflow(room, 1, change.copy(statuses = emptyList())).status)
            assertTrue(updates().isEmpty(), "a refused PUT emits nothing")

            assertEquals(HttpStatusCode.NoContent, owner.client.putWorkflow(room, 1, change).status)
            val event = capture.awaitEvent { it.message == "room.workflow_updated" && it.hasKeyValue("roomId", room.toLong()) }
            val line = checkNotNull(event)
            assertTrue(line.hasKeyValue("byUserId", owner.id.toLong()))
            assertTrue(line.hasKeyValue("revision", 2L))
            assertTrue(line.hasKeyValue("statusesAdded", 1))
            assertTrue(line.hasKeyValue("statusesRetired", 1))
            assertTrue(line.hasKeyValue("fieldsAdded", 1))
            assertTrue(line.hasKeyValue("fieldsRetired", 1))
            assertTrue(line.hasKeyValue("threadsMoved", 0L))
            assertEquals(1, updates().size)
        }
    }

    @Test
    fun `a status that threads use cannot be retired without a replacement - 409 status-in-use names it`() = testApplication {
        val usage = FakeWorkflowUsage(mapOf("half" to 2L, "open" to 5L))
        withUsage(usage)
        val owner = actor("wf-inuse")
        val room = room(owner)
        val current = owner.client.workflow(room).asRequest()
        val counted = owner.client.workflow(room).statuses.map { it.threadCount }
        assertEquals(listOf(5L, 2L, 0L), counted, "the GET shows the usage port's counts")

        val response = owner.client.putWorkflow(room, 1, current.withoutStatus("half"))
        assertEquals(HttpStatusCode.Conflict, response.status)
        val problem = response.body<ProblemDetail>()
        assertEquals(STATUS_IN_USE_PROBLEM_TYPE, problem.type)
        assertEquals(listOf("half"), problem.keys)
        assertTrue(usage.moves.isEmpty(), "nothing moved")
        assertEquals(1, owner.client.workflow(room).revision)
        // A status nobody uses retires without a mapping: `spare` is added, then retired with no threads.
        val spare = current.copy(statuses = current.statuses + openStatus("spare", category = StatusCategory.CLOSED))
        assertEquals(HttpStatusCode.NoContent, owner.client.putWorkflow(room, 1, spare).status)
        assertEquals(HttpStatusCode.NoContent, owner.client.putWorkflow(room, 2, spare.withoutStatus("spare")).status)
    }

    @Test
    fun `a status's category cannot change while threads use it, and a mapping cannot help`() = testApplication {
        val usage = FakeWorkflowUsage(mapOf("half" to 2L))
        withUsage(usage)
        val owner = actor("wf-category")
        val room = room(owner)
        val current = owner.client.workflow(room).asRequest()
        val closedHalf = current.mapStatus("half") { it.copy(category = StatusCategory.CLOSED) }

        val response = owner.client.putWorkflow(room, 1, closedHalf)
        assertEquals(HttpStatusCode.Conflict, response.status)
        assertEquals(STATUS_IN_USE_PROBLEM_TYPE, response.body<ProblemDetail>().type)
        assertEquals(listOf("half"), response.body<ProblemDetail>().keys)
        // A mapping names retired statuses only: `half` stays in the document, so it is a 400, not a way out.
        val mapped = owner.client.putWorkflow(room, 1, closedHalf.copy(replacements = mapOf("half" to "done")))
        assertEquals(HttpStatusCode.BadRequest, mapped.status)

        // Threads in `half` do not block an edit that leaves its category and key alone.
        val relabel = owner.client.putWorkflow(room, 1, current.mapStatus("half") { it.copy(label = "Still half") })
        assertEquals(HttpStatusCode.NoContent, relabel.status)
    }

    @Test
    fun `a category change is free while no thread uses the status`() = testApplication {
        usePostgresTestcontainer()
        val owner = actor("wf-recat")
        val room = room(owner)
        val current = owner.client.workflow(room).asRequest()
        val closedHalf = current.mapStatus("half") { it.copy(category = StatusCategory.CLOSED) }
        assertEquals(HttpStatusCode.NoContent, owner.client.putWorkflow(room, 1, closedHalf).status)
        assertEquals(StatusCategory.CLOSED, owner.client.workflow(room).statuses.single { it.key == "half" }.category)
    }

    @Test
    fun `an invalid replacement mapping is 400 - unknown, not retired, another category, or itself retired`() = testApplication {
        val usage = FakeWorkflowUsage(mapOf("half" to 2L))
        withUsage(usage)
        val owner = actor("wf-map")
        val room = room(owner)
        val current = owner.client.workflow(room).asRequest()
        val retiringHalf = current.withoutStatus("half")

        val cases = mapOf(
            "a target that is not in the new document" to mapOf("half" to "ghost"),
            "a key that is not a status at all" to mapOf("ghost" to "open"),
            "a key this change does not retire" to mapOf("open" to "done"),
            "a target of another category" to mapOf("half" to "done"),
            "a target that is the status being retired" to mapOf("half" to "half"),
        )
        for ((name, mapping) in cases) {
            val response = owner.client.putWorkflow(room, 1, retiringHalf.copy(replacements = mapping))
            assertEquals(HttpStatusCode.BadRequest, response.status, name)
        }
        assertTrue(usage.moves.isEmpty(), "a refused mapping moves nothing")
        assertEquals(1, owner.client.workflow(room).revision)
    }

    @Test
    fun `a valid replacement moves the threads through the usage port and the audit counts them`() = testApplication {
        val usage = FakeWorkflowUsage(mapOf("half" to 2L, "open" to 5L))
        withUsage(usage)
        val owner = actor("wf-move")
        val room = room(owner)
        val current = owner.client.workflow(room).asRequest()

        withAuditCapture { capture ->
            val response = owner.client.putWorkflow(room, 1, current.withoutStatus("half").copy(replacements = mapOf("half" to "open")))
            assertEquals(HttpStatusCode.NoContent, response.status)
            assertEquals(listOf(FakeWorkflowUsage.Move(room, "half", "open", owner.id, targetLiveAtMove = true)), usage.moves)
            val line = checkNotNull(capture.awaitEvent { it.message == "room.workflow_updated" && it.hasKeyValue("roomId", room.toLong()) })
            assertTrue(line.hasKeyValue("threadsMoved", 2L))
            assertTrue(line.hasKeyValue("statusesRetired", 1))
        }
        val after = owner.client.workflow(room)
        assertEquals(listOf("open", "done"), after.statuses.map { it.key })
        assertEquals(7L, after.statuses.first { it.key == "open" }.threadCount, "the fake applied the move")
    }

    @Test
    fun `the threads move to a status the same PUT adds or revives - the upsert runs before the move`() = testApplication {
        val usage = FakeWorkflowUsage(mapOf("half" to 2L, "open" to 1L))
        withUsage(usage)
        val owner = actor("wf-order")
        val room = room(owner)
        val current = owner.client.workflow(room).asRequest()

        // `half` retires and its threads go to `blocked`, a status that does not exist until this very PUT.
        val added = current.withoutStatus("half").copy(
            statuses = current.statuses.withoutKey("half") + openStatus("blocked"),
            replacements = mapOf("half" to "blocked"),
        )
        assertEquals(HttpStatusCode.NoContent, owner.client.putWorkflow(room, 1, added).status)
        assertEquals(listOf(FakeWorkflowUsage.Move(room, "half", "blocked", owner.id, targetLiveAtMove = true)), usage.moves)
        assertEquals(2L, owner.client.workflow(room).statuses.single { it.key == "blocked" }.threadCount)

        // The same with a REVIVED target: `half` is retired now; `open` (1 thread) retires and `half` returns to take them.
        usage.moves.clear()
        val revived = owner.client.workflow(room).asRequest().let {
            it.copy(
                statuses = it.statuses.withoutKey("open").map { s -> s.copy(initial = false) } +
                    current.status("half").copy(initial = true),
                replacements = mapOf("open" to "half"),
            )
        }
        assertEquals(HttpStatusCode.NoContent, owner.client.putWorkflow(room, 2, revived).status)
        assertEquals(listOf(FakeWorkflowUsage.Move(room, "open", "half", owner.id, targetLiveAtMove = true)), usage.moves)
    }

    @Test
    fun `a mapping for a retired status with no threads moves nothing - and an idle PUT never asks the port to move`() = testApplication {
        val usage = FakeWorkflowUsage(mapOf("open" to 1L))
        withUsage(usage)
        val owner = actor("wf-idle")
        val room = room(owner)
        val current = owner.client.workflow(room).asRequest()

        val response = owner.client.putWorkflow(room, 1, current.withoutStatus("half").copy(replacements = mapOf("half" to "open")))
        assertEquals(HttpStatusCode.NoContent, response.status)
        assertTrue(usage.moves.isEmpty(), "no threads in `half`")
        assertEquals(HttpStatusCode.NoContent, owner.client.putWorkflow(room, 2, owner.client.workflow(room).asRequest()).status)
        assertTrue(usage.moves.isEmpty())
    }
}
