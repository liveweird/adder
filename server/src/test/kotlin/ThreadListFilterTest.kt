package ch.nokillswit

import ch.nokillswit.authz.RoomAccessType
import ch.nokillswit.infra.revision.EXPECTED_REVISION_HEADER
import ch.nokillswit.rooms.RoomFieldDef
import ch.nokillswit.rooms.RoomOptionDef
import ch.nokillswit.rooms.RoomStatusDef
import ch.nokillswit.rooms.RoomWorkflowRequest
import ch.nokillswit.rooms.RoomWorkflowResponse
import ch.nokillswit.rooms.RoomWorkflowSnapshot
import ch.nokillswit.rooms.SnapshotStatus
import ch.nokillswit.threads.ThreadPageResponse
import ch.nokillswit.threads.ThreadService
import ch.nokillswit.threads.WatchRequest
import ch.nokillswit.threads.statusOrder
import ch.nokillswit.users.UserRole
import ch.nokillswit.workflows.FieldType
import ch.nokillswit.workflows.StatusCategory
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.net.URLEncoder
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

/**
 * The thread list's filters and the workflow-order sort over HTTP (`chat-model.md` "The thread list: filters and sorts"):
 * `status` and `ownerId` any-of, the `field=key:value` filter on SELECT, MULTI_SELECT and USER fields (any-of within a key, all-of
 * across keys), the `400`s (a retired or unfilterable field, a malformed key, id or pair, too many values), `closed` and the total,
 * `sort=status`, and the `404` that outranks every `400`. The plain list, `closed`, the other sorts and paging are `ThreadTest`.
 * Rooms, templates and users are SHARED suite state: every test mints UNIQUE rows and removes them.
 */
class ThreadListFilterTest {
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

    private suspend fun ApplicationTestBuilder.actor(prefix: String): RoomActor = seedActor(prefix).also { mintedUsers += it.id }

    /** A room of the fixture template (statuses `open`, `monitoring`, `done`, `wontdo`) owned by [owner], with [members] added. */
    private suspend fun room(owner: RoomActor, vararg members: RoomActor): UInt {
        val template = TestThreads.seedTemplate().also { mintedTemplates += it }
        return TestRooms.seed(
            owner.id, template, "Flt ${UUID.randomUUID().toString().substring(0, 8)}", accessType = RoomAccessType.PUBLIC,
        ).also { room ->
            mintedRooms += room
            members.forEach { TestRooms.addMember(room, it.id) }
        }
    }

    private fun text(value: String) = JsonPrimitive(value)

    private fun encoded(value: String): String = URLEncoder.encode(value, Charsets.UTF_8)

    private fun severity(value: String): Map<String, JsonElement> = mapOf("severity" to text(value))

    private suspend fun HttpClient.numbers(room: UInt, query: String): List<Int> =
        get("$rooms/$room/threads$query").body<ThreadPageResponse>().items.map { it.number }

    private suspend fun HttpClient.statusOf(room: UInt, query: String): HttpStatusCode = get("$rooms/$room/threads$query").status

    private suspend fun HttpClient.workflow(room: UInt): RoomWorkflowResponse = get("$rooms/$room/workflow").body()

    private suspend fun HttpClient.putWorkflow(room: UInt, change: (RoomWorkflowRequest) -> RoomWorkflowRequest) {
        val current = workflow(room)
        val request = RoomWorkflowRequest(
            statuses = current.statuses.map { RoomStatusDef(it.key, it.label, it.colour, it.category, it.initial) },
            fields = current.fields.map { RoomFieldDef(it.key, it.label, it.type, it.required, it.options) },
        )
        val response = put("$rooms/$room/workflow") {
            header(EXPECTED_REVISION_HEADER, current.revision.toString())
            contentType(ContentType.Application.Json)
            setBody(change(request))
        }
        assertEquals(HttpStatusCode.NoContent, response.status)
    }

    @Test
    fun `status takes any of the repeated keys`() = testApplication {
        usePostgresTestcontainer()
        val owner = actor("flt-st")
        val room = room(owner)
        repeat(4) { TestThreads.seed(room, owner.id) }
        TestThreads.moveRaw(room, 2, "monitoring")
        TestThreads.closeRaw(room, 3, "done")
        TestThreads.closeRaw(room, 4, "wontdo")

        assertEquals(listOf(1), owner.client.numbers(room, "?status=open"))
        assertEquals(listOf(1, 3), owner.client.numbers(room, "?status=open&status=done"))
        assertEquals(listOf(1, 3), owner.client.numbers(room, "?status=open&status=done&status=open"), "duplicates collapse")
        assertEquals(listOf(2, 3, 4), owner.client.numbers(room, "?status=monitoring&status=done&status=wontdo"))
        assertEquals(listOf(1, 2, 3, 4), owner.client.numbers(room, "?status="), "a blank value filters nothing")
    }

    @Test
    fun `an unknown status key matches nothing and a malformed one is 400`() = testApplication {
        usePostgresTestcontainer()
        val owner = actor("flt-st-bad")
        val room = room(owner)
        TestThreads.seed(room, owner.id)

        val unknown = owner.client.get("$rooms/$room/threads?status=nosuch").body<ThreadPageResponse>()
        assertEquals(0L, unknown.total)
        assertEquals(emptyList(), unknown.items)
        for (bad in listOf("Open", "1open", "a--b", "a_b", "open-", "a".repeat(41), "x' OR 1=1--")) {
            assertEquals(HttpStatusCode.BadRequest, owner.client.statusOf(room, "?status=${encoded(bad)}"), bad)
        }
        assertEquals(HttpStatusCode.BadRequest, owner.client.statusOf(room, "?" + (1..21).joinToString("&") { "status=s$it" }), "21 values")
        assertEquals(HttpStatusCode.OK, owner.client.statusOf(room, "?" + (1..20).joinToString("&") { "status=s$it" }), "20 values")
    }

    @Test
    fun `ownerId takes any of the repeated ids`() = testApplication {
        usePostgresTestcontainer()
        val owner = actor("flt-own")
        val ann = actor("flt-own-ann")
        val bob = actor("flt-own-bob")
        val room = room(owner, ann, bob)
        TestThreads.seed(room, owner.id)
        TestThreads.seed(room, ann.id)
        TestThreads.seed(room, bob.id)
        TestThreads.seed(room, ann.id)

        assertEquals(listOf(2, 4), owner.client.numbers(room, "?ownerId=${ann.id}"))
        assertEquals(listOf(1, 3), owner.client.numbers(room, "?ownerId=${owner.id}&ownerId=${bob.id}"))
        assertEquals(listOf(1, 3), owner.client.numbers(room, "?ownerId=${owner.id}&ownerId=${bob.id}&ownerId=${owner.id}"))
        assertEquals(emptyList(), owner.client.numbers(room, "?ownerId=2147483647"), "a well-formed unknown owner matches nothing")
        for (bad in listOf("0", "-1", "abc", "2147483648", "1.5", "+5", "\u0663", "1\u0663", "x' OR 1=1--")) {
            assertEquals(HttpStatusCode.BadRequest, owner.client.statusOf(room, "?ownerId=${encoded(bad)}"), bad)
        }
        assertEquals(HttpStatusCode.BadRequest, owner.client.statusOf(room, "?" + (1..21).joinToString("&") { "ownerId=$it" }), "21 ids")
    }

    @Test
    fun `a SELECT filter is any-of within a field and all-of across fields`() = testApplication {
        usePostgresTestcontainer()
        val owner = actor("flt-sel")
        val lead = actor("flt-sel-lead")
        val room = room(owner, lead)
        val leadId = JsonPrimitive(lead.id.toLong())
        TestThreads.seed(room, owner.id, fields = severity("s1"))
        TestThreads.seed(room, owner.id, fields = severity("s2") + ("lead" to leadId))
        TestThreads.seed(room, owner.id, fields = severity("s1") + ("lead" to leadId))
        TestThreads.seed(room, owner.id, fields = severity("s2"))

        assertEquals(listOf(1, 3), owner.client.numbers(room, "?field=severity:s1"))
        assertEquals(listOf(2, 4), owner.client.numbers(room, "?field=severity:s2"))
        assertEquals(listOf(1, 2, 3, 4), owner.client.numbers(room, "?field=severity:s1&field=severity:s2"), "any-of within a field")
        assertEquals(listOf(3), owner.client.numbers(room, "?field=severity:s1&field=lead:${lead.id}"), "all-of across fields")
        assertEquals(listOf(2, 3), owner.client.numbers(room, "?field=lead:${lead.id}&field=severity:s1&field=severity:s2"))
        assertEquals(emptyList(), owner.client.numbers(room, "?field=severity:s1&field=severity:s9&field=lead:${owner.id}"))
        assertEquals(emptyList(), owner.client.numbers(room, "?field=severity:nosuch"), "a value that is no option matches nothing")
    }

    @Test
    fun `a MULTI_SELECT filter matches a thread that holds the option`() = testApplication {
        usePostgresTestcontainer()
        val owner = actor("flt-multi")
        val room = room(owner)
        fun tags(vararg keys: String) = severity("s1") + ("tags" to JsonArray(keys.map { text(it) }))
        TestThreads.seed(room, owner.id, fields = tags("a", "b"))
        TestThreads.seed(room, owner.id, fields = tags("b"))
        TestThreads.seed(room, owner.id, fields = severity("s1"))

        assertEquals(listOf(1), owner.client.numbers(room, "?field=tags:a"))
        assertEquals(listOf(1, 2), owner.client.numbers(room, "?field=tags:b"))
        assertEquals(listOf(1, 2), owner.client.numbers(room, "?field=tags:a&field=tags:b"), "any-of within a field")
        assertEquals(listOf(1, 2), owner.client.numbers(room, "?field=tags:b&field=tags:c"), "a value that is no option adds nothing")
        assertEquals(2L, owner.client.get("$rooms/$room/threads?field=tags:b").body<ThreadPageResponse>().total)
    }

    @Test
    fun `a USER filter matches the stored member id`() = testApplication {
        usePostgresTestcontainer()
        val owner = actor("flt-user")
        val ann = actor("flt-user-ann")
        val bob = actor("flt-user-bob")
        val room = room(owner, ann, bob)
        TestThreads.seed(room, owner.id, fields = severity("s1") + ("lead" to JsonPrimitive(ann.id.toLong())))
        TestThreads.seed(room, owner.id, fields = severity("s1") + ("lead" to JsonPrimitive(bob.id.toLong())))
        TestThreads.seed(room, owner.id, fields = severity("s1"))

        assertEquals(listOf(1), owner.client.numbers(room, "?field=lead:${ann.id}"))
        assertEquals(listOf(1, 2), owner.client.numbers(room, "?field=lead:${ann.id}&field=lead:${bob.id}"))
        assertEquals(emptyList(), owner.client.numbers(room, "?field=lead:${owner.id}"), "a member who leads nothing matches nothing")
        assertEquals(emptyList(), owner.client.numbers(room, "?field=lead:2147483647"))
    }

    @Test
    fun `a removed option still finds the threads that kept it`() = testApplication {
        usePostgresTestcontainer()
        val owner = actor("flt-opt")
        val room = room(owner)
        TestThreads.seed(room, owner.id, fields = severity("s1"))
        TestThreads.seed(room, owner.id, fields = severity("s2") + ("tags" to JsonArray(listOf(text("a"), text("b")))))

        owner.client.putWorkflow(room) { request ->
            request.copy(
                fields = request.fields.map {
                    when (it.key) {
                        "severity" -> it.copy(options = it.options.filter { option -> option.key == "s1" })
                        "tags" -> it.copy(options = listOf(RoomOptionDef("b", "Tag B")))
                        else -> it
                    }
                },
            )
        }

        assertEquals(listOf(2), owner.client.numbers(room, "?field=severity:s2"), "the removed SELECT option")
        assertEquals(listOf(2), owner.client.numbers(room, "?field=tags:a"), "the removed MULTI_SELECT option")
        assertEquals(listOf(1), owner.client.numbers(room, "?field=severity:s1"))
    }

    @Test
    fun `an unknown, retired, TEXT, URL, DATE or NUMBER field key is 400`() = testApplication {
        usePostgresTestcontainer()
        val owner = actor("flt-key")
        val room = room(owner)
        TestThreads.seed(room, owner.id, fields = severity("s1") + ("tags" to JsonArray(listOf(text("a")))))
        owner.client.putWorkflow(room) { request ->
            request.copy(
                fields = request.fields.filter { it.key != "tags" } +
                    RoomFieldDef("link", "Link", FieldType.URL, required = false, options = emptyList()) +
                    RoomFieldDef("size", "Size", FieldType.NUMBER, required = false, options = emptyList()),
            )
        }

        assertEquals(listOf(1), owner.client.numbers(room, "?field=severity:s1"), "an active field still filters")
        val rejected = listOf(
            "nosuch:a" to "unknown",
            "tags:a" to "retired: its values are hidden",
            "note:x" to "TEXT",
            "link:x" to "URL",
            "due:2026-01-01" to "DATE",
            "size:7" to "NUMBER",
        )
        for ((pair, why) in rejected) {
            assertEquals(HttpStatusCode.BadRequest, owner.client.statusOf(room, "?field=${encoded(pair)}"), why)
        }
        assertEquals(HttpStatusCode.BadRequest, owner.client.statusOf(room, "?field=severity:s1&field=note:x"), "one bad key spoils all")
    }

    @Test
    fun `a field without a colon, a value of the wrong shape, or a 21st value is 400`() = testApplication {
        usePostgresTestcontainer()
        val owner = actor("flt-shape")
        val room = room(owner)
        TestThreads.seed(room, owner.id)

        val malformed = listOf(
            "severity", "severity:", ":s1", "Severity:s1", "severity:S1", "severity:s1:s2", "severity:1a", "severity:" + "a".repeat(41),
            "lead:0", "lead:-1", "lead:abc", "lead:1.5", "lead:+5", "lead:2147483648", "lead:99999999999999999999", "tags:",
            "lead:\u0663", "severity:x' OR 1=1--", "x' OR 1=1--",
        )
        for (pair in malformed) {
            assertEquals(HttpStatusCode.BadRequest, owner.client.statusOf(room, "?field=${encoded(pair)}"), pair)
        }
        fun many(count: Int) = "?" + (1..count).joinToString("&") { "field=severity:s$it" }
        assertEquals(HttpStatusCode.OK, owner.client.statusOf(room, many(20)), "20 distinct values")
        assertEquals(HttpStatusCode.BadRequest, owner.client.statusOf(room, many(21)), "the 21st")
        assertEquals(HttpStatusCode.OK, owner.client.statusOf(room, many(20) + "&field=severity:s1"), "a duplicate collapses first")
    }

    @Test
    fun `filters combine with closed and the total counts after them`() = testApplication {
        usePostgresTestcontainer()
        val owner = actor("flt-combo")
        val ann = actor("flt-combo-ann")
        val room = room(owner, ann)
        TestThreads.seed(room, owner.id, fields = severity("s1"))
        TestThreads.seed(room, ann.id, fields = severity("s1"))
        TestThreads.seed(room, ann.id, fields = severity("s1"))
        TestThreads.seed(room, ann.id, fields = severity("s2"))
        TestThreads.closeRaw(room, 3)
        TestThreads.closeRaw(room, 4)

        val query = "?field=severity:s1&ownerId=${ann.id}&closed=false"
        val page = owner.client.get("$rooms/$room/threads$query").body<ThreadPageResponse>()
        assertEquals(listOf(2), page.items.map { it.number })
        assertEquals(1L, page.total)
        assertEquals(listOf(3), owner.client.numbers(room, "?field=severity:s1&ownerId=${ann.id}&closed=true"))
        assertEquals(listOf(2, 3), owner.client.numbers(room, "?field=severity:s1&status=open&status=done&ownerId=${ann.id}"))
        val firstOfTwo = owner.client.get("$rooms/$room/threads?field=severity:s1&pageSize=1&page=2").body<ThreadPageResponse>()
        assertEquals(listOf(2), firstOfTwo.items.map { it.number })
        assertEquals(3L, firstOfTwo.total, "the total is after the filter and before the page")
        assertEquals(listOf(1, 2, 3, 4), owner.client.numbers(room, ""), "no filter, no change")
    }

    @Test
    fun `sort=status follows the workflow order, also after a reorder`() = testApplication {
        usePostgresTestcontainer()
        val owner = actor("flt-sort")
        val room = room(owner)
        repeat(6) { TestThreads.seed(room, owner.id) }
        TestThreads.closeRaw(room, 1, "wontdo")
        TestThreads.closeRaw(room, 2, "done")
        TestThreads.moveRaw(room, 3, "monitoring")
        TestThreads.closeRaw(room, 4, "done")
        // 5 and 6 stay in `open`; the fixture's workflow order is open, monitoring, done, wontdo.

        assertEquals(listOf(5, 6, 3, 2, 4, 1), owner.client.numbers(room, "?sort=status"), "ties break by id")
        assertEquals(listOf(1, 2, 4, 3, 5, 6), owner.client.numbers(room, "?sort=-status"), "descending reverses; the id stays ascending")
        assertEquals(listOf(5, 6, 3, 2, 4, 1), owner.client.numbers(room, "?sort=status,id"))
        assertEquals(listOf(6, 5, 3, 4, 2, 1), owner.client.numbers(room, "?sort=status,-id"))
        assertEquals(listOf(2, 4), owner.client.numbers(room, "?sort=status&status=done"), "sorted and filtered")

        owner.client.putWorkflow(room) { request ->
            request.copy(statuses = listOf("wontdo", "done", "monitoring", "open").map { key -> request.statuses.single { it.key == key } })
        }
        assertEquals(listOf(1, 2, 4, 3, 5, 6), owner.client.numbers(room, "?sort=status"), "the reordered workflow")
        assertEquals(listOf(5, 6, 3, 2, 4, 1), owner.client.numbers(room, "?sort=-status"))
        val page = owner.client.get("$rooms/$room/threads?sort=status&pageSize=2&page=2").body<ThreadPageResponse>()
        assertEquals(listOf(4, 3), page.items.map { it.number })
        assertEquals(6L, page.total)
    }

    @Test
    fun `a retired status sorts where it sits under sort=status`() = testApplication {
        usePostgresTestcontainer()
        val owner = actor("flt-retired")
        val room = room(owner)
        repeat(4) { TestThreads.seed(room, owner.id) }
        TestThreads.closeRaw(room, 2, "done")
        // `wontdo` (the last position) has no thread, so the PUT may retire it; the routes refuse a move INTO a retired status, a raw
        // update does not. A retired row keeps its last position, so it still sorts after the three live statuses.
        owner.client.putWorkflow(room) { request -> request.copy(statuses = request.statuses.filter { it.key != "wontdo" }) }
        TestThreads.closeRaw(room, 1, "wontdo")
        TestThreads.moveRaw(room, 3, "monitoring")

        assertEquals(listOf(4, 3, 2, 1), owner.client.numbers(room, "?sort=status"), "the retired status keeps its place")
        assertEquals(listOf(1, 2, 3, 4), owner.client.numbers(room, "?sort=-status"))
    }

    @Test
    fun `a status key the snapshot lacks sorts last under the status order`() = testApplication {
        usePostgresTestcontainer()
        val owner = actor("flt-else")
        val room = room(owner)
        repeat(3) { TestThreads.seed(room, owner.id) }
        TestThreads.closeRaw(room, 1, "done")
        TestThreads.moveRaw(room, 2, "monitoring")
        // A snapshot that does not know `done`: the ELSE rank, not an error, and not first.
        val snapshot = RoomWorkflowSnapshot(
            statuses = listOf(
                SnapshotStatus("open", "Open", StatusCategory.OPEN, initial = true, retired = false),
                SnapshotStatus("monitoring", "Monitoring", StatusCategory.OPEN, initial = false, retired = false),
            ),
            fields = emptyList(),
        )
        val threads = ThreadService.Threads
        val numbers = suspendTransaction(sharedDatabaseForTests()) {
            threads.select(threads.number).where { threads.roomId eq room }.orderBy(statusOrder(snapshot) to SortOrder.ASC)
                .map { it[threads.number] }.toList()
        }
        assertEquals(listOf(3, 2, 1), numbers)
    }

    @Test
    fun `sort=status combines with a field filter`() = testApplication {
        usePostgresTestcontainer()
        val owner = actor("flt-sort-field")
        val room = room(owner)
        TestThreads.seed(room, owner.id, fields = severity("s1"))
        TestThreads.seed(room, owner.id, fields = severity("s2"))
        TestThreads.seed(room, owner.id, fields = severity("s1"))
        TestThreads.seed(room, owner.id, fields = severity("s1"))
        TestThreads.closeRaw(room, 1, "done")
        TestThreads.moveRaw(room, 3, "monitoring")

        assertEquals(listOf(4, 3, 1), owner.client.numbers(room, "?sort=status&field=severity:s1"))
        assertEquals(listOf(1, 3, 4), owner.client.numbers(room, "?sort=-status&field=severity:s1"))
        val page = owner.client.get("$rooms/$room/threads?sort=status&field=severity:s1&pageSize=2&page=2").body<ThreadPageResponse>()
        assertEquals(listOf(1), page.items.map { it.number })
        assertEquals(3L, page.total)
    }

    @Test
    fun `watching keeps the threads the caller watches, and false the ones they do not`() = testApplication {
        usePostgresTestcontainer()
        val owner = actor("flt-wa")
        val member = actor("flt-wa-m")
        val admin = seedActor("flt-wa-adm", UserRole.ADMIN).also { mintedUsers += it.id }
        val room = room(owner, member)
        repeat(3) { TestThreads.seed(room, owner.id) }
        TestThreads.closeRaw(room, 3, "done")
        assertEquals(listOf(1, 2, 3), owner.client.numbers(room, "?watching=true"), "a creator watches what they start")
        assertEquals(emptyList(), owner.client.numbers(room, "?watching=false"))
        assertEquals(emptyList(), member.client.numbers(room, "?watching=true"))
        assertEquals(listOf(1, 2, 3), member.client.numbers(room, "?watching=false"))

        assertEquals(HttpStatusCode.NoContent, member.client.putJson("$rooms/$room/threads/2/watch", WatchRequest(true)).status)
        assertEquals(HttpStatusCode.NoContent, member.client.putJson("$rooms/$room/threads/3/watch", WatchRequest(true)).status)
        assertEquals(listOf(2, 3), member.client.numbers(room, "?watching=true"))
        assertEquals(listOf(1), member.client.numbers(room, "?watching=false"))
        assertEquals(listOf(3), member.client.numbers(room, "?watching=true&closed=true"), "combined with another filter")
        assertEquals(listOf(2), member.client.numbers(room, "?watching=true&closed=false&status=open"))
        val page = member.client.get("$rooms/$room/threads?watching=true&pageSize=1").body<ThreadPageResponse>()
        assertEquals(2L, page.total, "the total counts after the filter")
        val items = member.client.get("$rooms/$room/threads").body<ThreadPageResponse>().items
        assertEquals(mapOf(1 to false, 2 to true, 3 to true), items.associate { it.number to it.watching }, "the flag of each row")

        // A caller who is no member watches nothing; a blank value filters nothing; a repeat or a non-boolean is 400.
        assertEquals(emptyList(), admin.client.numbers(room, "?watching=true"))
        assertEquals(listOf(1, 2, 3), admin.client.numbers(room, "?watching=false"))
        assertEquals(listOf(1, 2, 3), owner.client.numbers(room, "?watching="))
        for (query in listOf("?watching=yes", "?watching=1", "?watching=TRUE", "?watching=true&watching=false")) {
            assertEquals(HttpStatusCode.BadRequest, owner.client.statusOf(room, query), query)
        }
    }

    @Test
    fun `a non-member is 404 whatever the filter`() = testApplication {
        usePostgresTestcontainer()
        val owner = actor("flt-404")
        val outsider = actor("flt-404-out")
        val room = room(owner)
        TestThreads.seed(room, owner.id)

        val queries = listOf(
            "", "?status=open", "?status=Bad_Key", "?field=nocolon", "?field=note:x", "?ownerId=0", "?sort=nope", "?watching=maybe",
        )
        for (query in queries) {
            assertEquals(HttpStatusCode.NotFound, outsider.client.statusOf(room, query), query)
        }
        assertEquals(HttpStatusCode.BadRequest, owner.client.statusOf(room, "?field=nocolon"), "a member's bad filter is still 400")
    }
}
