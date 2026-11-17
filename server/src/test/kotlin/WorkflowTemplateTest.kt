package ch.nokillswit

import ch.nokillswit.infra.revision.EXPECTED_REVISION_HEADER
import ch.nokillswit.infra.revision.REVISION_CONFLICT_PROBLEM_TYPE
import ch.nokillswit.plugins.ProblemDetail
import ch.nokillswit.users.SUPPORTED_LANGUAGES
import ch.nokillswit.users.UserRole
import ch.nokillswit.workflows.FieldType
import ch.nokillswit.workflows.StatusCategory
import ch.nokillswit.workflows.StatusColour
import ch.nokillswit.workflows.WorkflowFieldDef
import ch.nokillswit.workflows.WorkflowOptionDef
import ch.nokillswit.workflows.WorkflowStatusDef
import ch.nokillswit.workflows.WorkflowTemplatePageResponse
import ch.nokillswit.workflows.WorkflowTemplateRequest
import ch.nokillswit.workflows.WorkflowTemplateResponse
import ch.nokillswit.workflows.WorkflowTemplateService
import ch.nokillswit.workflows.validateWorkflowTemplate
import ch.qos.logback.classic.spi.ILoggingEvent
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
import io.ktor.server.testing.testApplication
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

/**
 * The ADMIN workflow-template registry (V11, seeded by V12): reads by any authenticated user, writes by ADMIN with the guard
 * before everything else (uniform 403), the whole-document PUT under `X-Expected-Revision`, the label-map rules, the soft-delete
 * name reuse, the audit trail and the seeded Default and Incident. Templates are SHARED suite state - every test mints UNIQUE names
 * and never touches the two seeds.
 */
class WorkflowTemplateTest {

    private val base = "/api/v1/workflow-templates"

    private fun name(prefix: String) = "$prefix-${UUID.randomUUID().toString().substring(0, 8)}"

    private fun label(en: String, pl: String? = null): Map<String, String> =
        if (pl == null) mapOf("en" to en) else mapOf("en" to en, "pl" to pl)

    private fun status(
        key: String,
        category: StatusCategory = StatusCategory.OPEN,
        initial: Boolean = false,
        colour: StatusColour = StatusColour.GRAY,
    ) = WorkflowStatusDef(key, label(key.replaceFirstChar { it.uppercase() }, "PL $key"), colour, category, initial)

    private fun request(
        name: String = name("tpl"),
        statuses: List<WorkflowStatusDef> = listOf(status("open", initial = true), status("done", StatusCategory.CLOSED)),
        fields: List<WorkflowFieldDef> = emptyList(),
    ) = WorkflowTemplateRequest(name = name, description = "A test flow", statuses = statuses, fields = fields)

    private fun severityField(options: List<WorkflowOptionDef> = listOf(WorkflowOptionDef("s1", label("S1")))) =
        WorkflowFieldDef("severity", label("Severity", "Ważność"), FieldType.SELECT, required = false, options = options)

    private fun statusRow(s: WorkflowStatusDef) =
        listOf(s.key, s.label.getValue("en"), s.label.getValue("pl"), "${s.colour}", "${s.category}", "${s.initial}")

    private fun ILoggingEvent.isTemplateEvent(verb: String, id: UInt) =
        message == "workflow_template.$verb" && hasKeyValue("templateId", id.toLong())

    private suspend fun HttpClient.putTemplate(id: UInt, revision: Long?, body: WorkflowTemplateRequest): HttpResponse =
        put("$base/$id") {
            if (revision != null) header(EXPECTED_REVISION_HEADER, revision.toString())
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private suspend fun HttpClient.putRaw(path: String, revision: Long?, json: String): HttpResponse = put(path) {
        if (revision != null) header(EXPECTED_REVISION_HEADER, revision.toString())
        contentType(ContentType.Application.Json)
        setBody(json)
    }

    private suspend fun HttpClient.createTemplate(body: WorkflowTemplateRequest): WorkflowTemplateResponse {
        val response = postJson(base, body)
        assertEquals(HttpStatusCode.Created, response.status)
        return response.body()
    }

    private suspend fun rawRows(): List<Triple<UInt, String, Boolean>> = suspendTransaction(sharedDatabaseForTests()) {
        val t = WorkflowTemplateService.Templates
        t.selectAll().map { Triple(it[t.id].value, it[t.name], it[t.markedAsDeleted]) }.toList()
    }

    @Test
    fun `unauthenticated requests are 401`() = testApplication {
        usePostgresTestcontainer()
        val client = jsonClient()
        assertEquals(HttpStatusCode.Unauthorized, client.get(base).status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("$base/1").status)
        assertEquals(HttpStatusCode.Unauthorized, client.post(base).status)
        assertEquals(HttpStatusCode.Unauthorized, client.put("$base/1").status)
        assertEquals(HttpStatusCode.Unauthorized, client.delete("$base/1").status)
    }

    @Test
    fun `any authenticated user reads but writes are uniformly 403 whatever the id, header or body`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("tplro-admin", UserRole.ADMIN)
        val user = seededClient("tplro-user")
        val created = admin.createTemplate(request())

        assertEquals(HttpStatusCode.OK, user.get(base).status)
        assertEquals(created, user.get("$base/${created.id}").body<WorkflowTemplateResponse>())
        assertEquals(HttpStatusCode.NotFound, user.get("$base/999999").status)

        assertEquals(HttpStatusCode.Forbidden, user.postJson(base, request()).status)
        // A known and an unknown id, with and without the header, with a valid and a malformed body: always the same 403.
        assertEquals(HttpStatusCode.Forbidden, user.putTemplate(created.id, created.revision, request()).status)
        assertEquals(HttpStatusCode.Forbidden, user.putTemplate(999999u, created.revision, request()).status)
        assertEquals(HttpStatusCode.Forbidden, user.putTemplate(999999u, null, request()).status)
        assertEquals(HttpStatusCode.Forbidden, user.putRaw("$base/999999", 1, "{ not json").status)
        assertEquals(HttpStatusCode.Forbidden, user.delete("$base/${created.id}").status)
        assertEquals(HttpStatusCode.Forbidden, user.delete("$base/999999").status)
        val malformed = user.post(base) {
            contentType(ContentType.Application.Json)
            setBody("{ not json")
        }
        assertEquals(HttpStatusCode.Forbidden, malformed.status)
        assertEquals(created, admin.get("$base/${created.id}").body<WorkflowTemplateResponse>(), "nothing changed")
    }

    @Test
    fun `admin CRUD round-trips - create, read, list, replace under the revision, delete`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("tplcrud", UserRole.ADMIN)
        val templateName = name("Support")

        val create = admin.postJson(
            base,
            request(name = "  $templateName  ", fields = listOf(severityField())).copy(description = "  Tickets  "),
        )
        assertEquals(HttpStatusCode.Created, create.status)
        val created = create.body<WorkflowTemplateResponse>()
        assertEquals("$base/${created.id}", create.headers["Location"])
        assertEquals(templateName, created.name, "the sanitizer trims")
        assertEquals("Tickets", created.description)
        assertEquals(1, created.revision)
        assertEquals(listOf("open", "done"), created.statuses.map { it.key })
        assertEquals(label("Severity", "Ważność"), created.fields.single().label)
        assertEquals(created, admin.get("$base/${created.id}").body<WorkflowTemplateResponse>())

        val page = admin.get("$base?name=${templateName.lowercase()}&sort=-name").body<WorkflowTemplatePageResponse>()
        assertEquals(1, page.total)
        val item = page.items.single()
        assertEquals(created.id, item.id)
        assertEquals(2, item.statusCount)
        assertEquals(1, item.fieldCount)
        assertEquals(1, item.revision)
        assertEquals(HttpStatusCode.BadRequest, admin.get("$base?sort=description").status)

        val renamed = name("Helpdesk")
        val replacement = request(
            name = renamed,
            statuses = listOf(
                status("new", initial = true, colour = StatusColour.RED),
                status("working", colour = StatusColour.BLUE),
                status("closed", StatusCategory.CLOSED, colour = StatusColour.TEAL),
            ),
        ).copy(description = null)
        assertEquals(HttpStatusCode.NoContent, admin.putTemplate(created.id, 1, replacement).status)
        val afterPut = admin.get("$base/${created.id}").body<WorkflowTemplateResponse>()
        assertEquals(2, afterPut.revision, "every successful PUT bumps the revision")
        assertEquals(renamed, afterPut.name)
        assertEquals(null, afterPut.description, "PUT is a full replace - an omitted description clears")
        assertEquals(listOf("new", "working", "closed"), afterPut.statuses.map { it.key })
        assertEquals(emptyList(), afterPut.fields, "an omitted field is removed")
        assertTrue(afterPut.updatedAt >= created.updatedAt)
        assertEquals(created.createdAt, afterPut.createdAt)

        assertEquals(HttpStatusCode.NoContent, admin.delete("$base/${created.id}").status)
        assertEquals(HttpStatusCode.NotFound, admin.get("$base/${created.id}").status)
        val afterDelete = admin.get("$base?name=${renamed.lowercase()}").body<WorkflowTemplatePageResponse>()
        assertEquals(0, afterDelete.total, "a deleted template leaves the list")
        assertTrue(rawRows().single { it.first == created.id }.third, "delete must soft-delete")
    }

    @Test
    fun `the PUT needs the header - missing or malformed is 400 - and a stale revision is the typed 409`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("tplrev", UserRole.ADMIN)
        val created = admin.createTemplate(request())
        val replacement = request(name = name("rev2"))

        assertEquals(HttpStatusCode.BadRequest, admin.putTemplate(created.id, null, replacement).status)
        for (bad in listOf("0", "-1", "abc", "1.5", "99999999999999999999")) {
            val response = admin.put("$base/${created.id}") {
                header(EXPECTED_REVISION_HEADER, bad)
                contentType(ContentType.Application.Json)
                setBody(replacement)
            }
            assertEquals(HttpStatusCode.BadRequest, response.status, "header '$bad'")
        }
        // The header outranks the id lookup: a missing header on an unknown id is still 400 for an admin.
        assertEquals(HttpStatusCode.BadRequest, admin.putTemplate(999999u, null, replacement).status)
        assertEquals(HttpStatusCode.NotFound, admin.putTemplate(999999u, 1, replacement).status)

        assertEquals(HttpStatusCode.NoContent, admin.putTemplate(created.id, 1, replacement).status)
        val stale = admin.putTemplate(created.id, 1, request(name = name("rev3")))
        assertEquals(HttpStatusCode.Conflict, stale.status)
        val problem = stale.body<ProblemDetail>()
        assertEquals(REVISION_CONFLICT_PROBLEM_TYPE, problem.type)
        val current = admin.get("$base/${created.id}").body<WorkflowTemplateResponse>()
        assertEquals(2, current.revision)
        assertEquals(replacement.name, current.name, "the stale write changed nothing")
        // An expected revision from the future is as stale as one from the past.
        assertEquals(HttpStatusCode.Conflict, admin.putTemplate(created.id, 3, replacement).status)
    }

    @Test
    fun `a PUT that waits on the row lock while another writer bumps the revision is the typed 409`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("tplrace", UserRole.ADMIN)
        val created = admin.createTemplate(request())
        PostgresTestSupport.connect().use { raw ->
            raw.autoCommit = false
            raw.prepareStatement("UPDATE workflow_templates SET revision = revision + 1 WHERE id = ?").use { st ->
                st.setLong(1, created.id.toLong())
                st.executeUpdate()
            }
            try {
                coroutineScope {
                    val waiting = async { admin.putTemplate(created.id, 1, request(name = name("race"))) }
                    awaitLockWait("FOR NO KEY UPDATE")
                    raw.commit()
                    val response = waiting.await()
                    assertEquals(HttpStatusCode.Conflict, response.status)
                    assertEquals(REVISION_CONFLICT_PROBLEM_TYPE, response.body<ProblemDetail>().type)
                }
            } finally {
                raw.rollback() // a no-op after the commit; releases the lock if an assertion failed first
            }
        }
        assertEquals(created.name, admin.get("$base/${created.id}").body<WorkflowTemplateResponse>().name)
    }

    @Test
    fun `invalid documents are 400 on create and on replace and change nothing`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("tplbad", UserRole.ADMIN)
        val created = admin.createTemplate(request())
        val open = status("open", initial = true)
        val done = status("done", StatusCategory.CLOSED)
        val tooLong = "x".repeat(61)
        val cases = mapOf(
            "blank name" to request(name = "   "),
            "overlong name" to request(name = "x".repeat(101)),
            "overlong description" to request().copy(description = "d".repeat(501)),
            "one status" to request(statuses = listOf(open)),
            "no initial status" to request(statuses = listOf(status("open"), done)),
            "no closed status" to request(statuses = listOf(open, status("working"))),
            "bad status key" to request(statuses = listOf(open.copy(key = "Open Now"), done)),
            "duplicate status keys" to request(statuses = listOf(open, open.copy(initial = false), done)),
            "status without an English label" to request(statuses = listOf(open.copy(label = mapOf("pl" to "Otwarte")), done)),
            "status with an empty label map" to request(statuses = listOf(open.copy(label = emptyMap()), done)),
            "status with a blank English label" to request(statuses = listOf(open.copy(label = label("  ")), done)),
            "status with a blank Polish label" to request(statuses = listOf(open.copy(label = label("Open", "  ")), done)),
            "status with an overlong label" to request(statuses = listOf(open.copy(label = label(tooLong)), done)),
            "status with an overlong Polish label" to request(statuses = listOf(open.copy(label = label("Open", tooLong)), done)),
            "status with a control character in a label" to request(statuses = listOf(open.copy(label = label("Op\ten")), done)),
            "an unsupported label language" to request(statuses = listOf(open.copy(label = mapOf("en" to "Open", "de" to "Offen")), done)),
            "field with an empty label map" to request(fields = listOf(severityField().copy(label = emptyMap()))),
            "select without options" to request(fields = listOf(severityField(options = emptyList()))),
            "options on a text field" to request(
                fields = listOf(severityField().copy(key = "note", type = FieldType.TEXT)),
            ),
            "duplicate option keys" to request(
                fields = listOf(severityField(listOf(WorkflowOptionDef("s1", label("S1")), WorkflowOptionDef("s1", label("S1 again"))))),
            ),
            "option with an empty label map" to request(fields = listOf(severityField(listOf(WorkflowOptionDef("s1", emptyMap()))))),
            "option without an English label" to request(
                fields = listOf(severityField(listOf(WorkflowOptionDef("s1", mapOf("pl" to "S1"))))),
            ),
            "option with a blank label" to request(fields = listOf(severityField(listOf(WorkflowOptionDef("s1", label(" ")))))),
            "option with an overlong label" to request(fields = listOf(severityField(listOf(WorkflowOptionDef("s1", label(tooLong)))))),
        )
        for ((why, case) in cases) {
            assertEquals(HttpStatusCode.BadRequest, admin.postJson(base, case).status, "create: $why")
            assertEquals(HttpStatusCode.BadRequest, admin.putTemplate(created.id, 1, case).status, "replace: $why")
        }
        assertEquals(created, admin.get("$base/${created.id}").body<WorkflowTemplateResponse>(), "nothing changed")
    }

    @Test
    fun `an admin's malformed JSON is 400 and an invalid body on an unknown id is 400 before the lookup`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("tplmalformed", UserRole.ADMIN)
        val created = admin.createTemplate(request())
        val post = admin.post(base) {
            contentType(ContentType.Application.Json)
            setBody("{ not json")
        }
        assertEquals(HttpStatusCode.BadRequest, post.status)
        assertEquals(HttpStatusCode.BadRequest, admin.putRaw("$base/${created.id}", 1, "{ not json").status)
        assertEquals(HttpStatusCode.BadRequest, admin.putRaw("$base/999999", 1, "{ not json").status)
        // Validation runs before the id lookup, so an invalid body on an unknown id is 400, while a valid one is 404.
        assertEquals(HttpStatusCode.BadRequest, admin.putTemplate(999999u, 1, request(name = "  ")).status)
        assertEquals(HttpStatusCode.NotFound, admin.putTemplate(999999u, 1, request()).status)
        assertEquals(HttpStatusCode.BadRequest, admin.get("$base?name=${"x".repeat(101)}").status)
        assertEquals(created, admin.get("$base/${created.id}").body<WorkflowTemplateResponse>(), "nothing changed")
    }

    @Test
    fun `labels are unique per language among statuses, fields and one field's options - case-insensitively`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("tpluniq", UserRole.ADMIN)
        val open = status("open", initial = true)
        val done = status("done", StatusCategory.CLOSED)
        fun option(key: String, en: String, pl: String? = null) = WorkflowOptionDef(key, label(en, pl))
        val duplicates = mapOf(
            "status labels differing in case" to
                (request(statuses = listOf(open, done.copy(label = label("OPEN", "PL done")))) to "'OPEN'"),
            "status labels differing in padding" to
                (
                    request(statuses = listOf(open.copy(label = label("Open", "Jedno")), done.copy(label = label("Done", " jedno ")))) to
                        "'jedno'"
                    ),
            "field labels" to (request(fields = listOf(severityField(), severityField().copy(key = "other"))) to "'Severity'"),
            "option labels" to
                (request(fields = listOf(severityField(listOf(option("a", "Same"), option("b", "SAME"))))) to "'SAME'"),
            "option labels in Polish only" to
                (request(fields = listOf(severityField(listOf(option("a", "One", "Tak"), option("b", "Two", "tak"))))) to "'tak'"),
        )
        for ((why, case) in duplicates) {
            val response = admin.postJson(base, case.first)
            assertEquals(HttpStatusCode.BadRequest, response.status, why)
            assertTrue(response.body<ProblemDetail>().detail!!.contains(case.second), "$why: the message names the label")
        }
        // The same text under two languages (and the same option label in two different fields) is fine.
        val fine = request(
            fields = listOf(
                severityField(listOf(option("a", "ETA", "ETA"), option("b", "Other", "Inne"))),
                severityField(listOf(option("a", "ETA", "ETA"))).copy(key = "second", label = label("Second", "Drugie")),
            ),
        )
        assertEquals(HttpStatusCode.Created, admin.postJson(base, fine).status)
    }

    @Test
    fun `the error messages name the offending language and the key whose label has a control character`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("tplmsg", UserRole.ADMIN)
        val open = status("open", initial = true)
        val done = status("done", StatusCategory.CLOSED)
        val language = admin.postJson(base, request(statuses = listOf(open.copy(label = mapOf("en" to "Open", "de" to "Offen")), done)))
        assertTrue(language.body<ProblemDetail>().detail!!.contains("'de'"))
        val control = admin.postJson(base, request(statuses = listOf(open, done.copy(label = label("Do\tne")))))
        assertTrue(control.body<ProblemDetail>().detail!!.contains("status 'done'"))
        val optionControl = admin.postJson(
            base,
            request(fields = listOf(severityField(listOf(WorkflowOptionDef("s1", label("S\n1")))))),
        )
        val detail = optionControl.body<ProblemDetail>().detail!!
        assertTrue(detail.contains("option 's1'") && detail.contains("field 'severity'"), detail)
    }

    @Test
    fun `a PUT waiting on the row lock while a delete commits is 404`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("tplrace-del", UserRole.ADMIN)
        val created = admin.createTemplate(request())
        PostgresTestSupport.connect().use { raw ->
            raw.autoCommit = false
            raw.prepareStatement("UPDATE workflow_templates SET marked_as_deleted = true WHERE id = ?").use { st ->
                st.setLong(1, created.id.toLong())
                st.executeUpdate()
            }
            try {
                coroutineScope {
                    val waiting = async { admin.putTemplate(created.id, 1, request(name = name("race-del"))) }
                    awaitLockWait("FOR NO KEY UPDATE")
                    raw.commit()
                    assertEquals(HttpStatusCode.NotFound, waiting.await().status)
                }
            } finally {
                raw.rollback()
            }
        }
    }

    @Test
    fun `an option with no label at all is a 400, not a stored label-less option`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("tpllabelless", UserRole.ADMIN)
        val created = admin.createTemplate(request())
        val statuses = """[
            {"key":"open","label":{"en":"Open"},"colour":"GRAY","category":"OPEN","initial":true},
            {"key":"done","label":{"en":"Done"},"colour":"TEAL","category":"CLOSED","initial":false}
        ]"""
        fun body(option: String) = """{"name":"${name("nolabel")}","statuses":$statuses,"fields":[
            {"key":"severity","label":{"en":"Severity"},"type":"SELECT","required":false,"options":[$option]}]}"""
        // The `label` member absent altogether, present but empty, and present with only an unsupported language.
        for (option in listOf("""{"key":"s1"}""", """{"key":"s1","label":{}}""", """{"key":"s1","label":{"de":"S1"}}""")) {
            val raw = body(option)
            val create = admin.post(base) {
                contentType(ContentType.Application.Json)
                setBody(raw)
            }
            assertEquals(HttpStatusCode.BadRequest, create.status, "create: $option")
            assertEquals(HttpStatusCode.BadRequest, admin.putRaw("$base/${created.id}", 1, raw).status, "replace: $option")
        }
        assertEquals(created, admin.get("$base/${created.id}").body<WorkflowTemplateResponse>(), "nothing changed")
    }

    @Test
    fun `an active name clash is 409 on create and replace and a soft-deleted template frees its name`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("tpldup", UserRole.ADMIN)
        val n = name("Dup")
        val first = admin.createTemplate(request(name = n))
        val clash = admin.postJson(base, request(name = n.uppercase()))
        assertEquals(HttpStatusCode.Conflict, clash.status)
        assertTrue(clash.body<ProblemDetail>().detail!!.contains("workflow template with this name"))

        val other = admin.createTemplate(request())
        val clashingPut = admin.putTemplate(other.id, 1, request(name = n))
        assertEquals(HttpStatusCode.Conflict, clashingPut.status)
        assertTrue(clashingPut.body<ProblemDetail>().detail!!.contains("workflow template with this name"))
        assertEquals(1, admin.get("$base/${other.id}").body<WorkflowTemplateResponse>().revision, "the refused PUT changed nothing")

        assertEquals(HttpStatusCode.NoContent, admin.delete("$base/${first.id}").status)
        val second = admin.postJson(base, request(name = n))
        assertEquals(HttpStatusCode.Created, second.status)
        assertTrue(second.body<WorkflowTemplateResponse>().id != first.id)
        assertEquals(HttpStatusCode.NotFound, admin.delete("$base/${first.id}").status, "a deleted template cannot be deleted again")
        assertEquals(HttpStatusCode.NotFound, admin.putTemplate(first.id, 1, request()).status, "nor replaced")
    }

    @Test
    fun `mutations audit and a failed mutation does not`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("tplaudit", UserRole.ADMIN)
        withAuditCapture { capture ->
            val n = name("audited")
            val created = admin.createTemplate(request(name = n, fields = listOf(severityField())))
            val createdEvent = capture.awaitEvent { it.isTemplateEvent("created", created.id) }
            assertNotNull(createdEvent, "create must audit")
            assertTrue(createdEvent.hasKeyValue("name", n))
            assertTrue(createdEvent.hasKeyValue("statuses", 2))
            assertTrue(createdEvent.hasKeyValue("fields", 1))

            admin.putTemplate(created.id, 1, request(name = name("renamed")))
            val updated = capture.awaitEvent { it.isTemplateEvent("updated", created.id) }
            assertNotNull(updated, "replace must audit")
            assertTrue(updated.hasKeyValue("revision", 2L))

            admin.delete("$base/${created.id}")
            assertNotNull(capture.awaitEvent { it.isTemplateEvent("deleted", created.id) })

            val live = admin.createTemplate(request())
            val before = capture.events.count { it.message.startsWith("workflow_template.") }
            assertEquals(HttpStatusCode.BadRequest, admin.postJson(base, request(name = "")).status)
            assertEquals(HttpStatusCode.Conflict, admin.putTemplate(live.id, 99, request()).status)
            assertEquals(HttpStatusCode.NotFound, admin.delete("$base/999999").status)
            assertEquals(before, capture.events.count { it.message.startsWith("workflow_template.") }, "a refused write must not audit")
        }
    }

    // The V12 seeds are the shipped starting point of every room (decision D3): they must decode, satisfy the very kernel a room
    // copy is held to, and carry both languages, because an applied migration can never be corrected afterwards.
    @Test
    fun `the seeded Default and Incident decode, pass the kernel and carry both languages`() = testApplication {
        usePostgresTestcontainer()
        val client = seededClient("tplseed")
        val seeded = listOf("Default", "Incident").associateWith { seedName ->
            val page = client.get("$base?name=$seedName&pageSize=100").body<WorkflowTemplatePageResponse>()
            val item = page.items.single { it.name == seedName }
            client.get("$base/${item.id}").body<WorkflowTemplateResponse>()
        }
        for ((seedName, template) in seeded) {
            // The kernel plus the label-map rules, exactly as an ADMIN's own PUT of this document would be held to them.
            validateWorkflowTemplate(WorkflowTemplateRequest(template.name, template.description, template.statuses, template.fields))
            val labels = template.statuses.map { it.label } + template.fields.flatMap { listOf(it.label) + it.options.map { o -> o.label } }
            for (map in labels) {
                assertEquals(SUPPORTED_LANGUAGES.toSet(), map.keys, "$seedName carries every supported language: $map")
                assertTrue(map.values.none { it.isBlank() })
            }
            assertEquals(1, template.revision)
        }

        val default = seeded.getValue("Default")
        assertEquals(
            listOf(
                listOf("open", "Open", "Otwarte", "GRAY", "OPEN", "true"),
                listOf("in-progress", "In progress", "W toku", "BLUE", "OPEN", "false"),
                listOf("waiting", "Waiting", "Oczekuje", "GRAPE", "OPEN", "false"),
                listOf("done", "Done", "Zrobione", "TEAL", "CLOSED", "false"),
                listOf("wont-do", "Won't do", "Nie robimy", "GRAY", "CLOSED", "false"),
            ),
            default.statuses.map(::statusRow),
        )
        assertEquals(emptyList(), default.fields)

        val incident = seeded.getValue("Incident")
        assertEquals(
            listOf(
                listOf("triage", "Triage", "Triaż", "RED", "OPEN", "true"),
                listOf("investigating", "Investigating", "Analiza", "ORANGE", "OPEN", "false"),
                listOf("mitigating", "Mitigating", "Mitygacja", "BLUE", "OPEN", "false"),
                listOf("monitoring", "Monitoring", "Monitorowanie", "GRAPE", "OPEN", "false"),
                listOf("resolved", "Resolved", "Rozwiązane", "TEAL", "CLOSED", "false"),
            ),
            incident.statuses.map(::statusRow),
        )
        assertEquals(
            listOf(
                listOf("severity", "Severity", "Ważność", "SELECT", "false"),
                listOf("component", "Component", "Komponent", "TEXT", "false"),
                listOf("jira-link", "Jira link", "Link do Jiry", "URL", "false"),
                listOf("eta", "ETA", "ETA", "DATE", "false"),
            ),
            incident.fields.map { listOf(it.key, it.label.getValue("en"), it.label.getValue("pl"), "${it.type}", "${it.required}") },
        )
        val severity = incident.fields.first()
        assertEquals(listOf("s1", "s2", "s3", "s4"), severity.options.map { it.key })
        assertEquals(listOf("S1", "S2", "S3", "S4"), severity.options.map { it.label.getValue("en") })
        assertEquals(emptyList(), incident.fields.drop(1).flatMap { it.options })
    }
}
