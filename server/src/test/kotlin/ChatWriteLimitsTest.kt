package ch.nokillswit

import ch.nokillswit.plugins.ProblemDetail
import ch.nokillswit.threads.MessageRequest
import ch.nokillswit.threads.MessageResponse
import ch.nokillswit.threads.ThreadStatusRequest
import ch.nokillswit.threads.ThreadUpdateRequest
import ch.nokillswit.users.UserRole
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.encodedPath
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.util.UUID
import kotlin.system.measureTimeMillis
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * The write bounds of the thread and message routes (the 0.3.0 security audit, M1 and M3): the per-user `chat-write` bucket of the five
 * non-POST writes, the body ceilings (64 KiB for a message, 192 KiB for a thread, 413 on the rest), and the shape of `fields`, refused
 * with a `400` BEFORE the room lock. The `chat-post` bucket and the lock races are `ThreadWriteBoundsTest`; the room lock's own bound is
 * `RoomBusyTest`. Rooms, templates and users are SHARED suite state: every test mints UNIQUE rows.
 */
class ChatWriteLimitsTest {
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

    private suspend fun ApplicationTestBuilder.actor(prefix: String, role: UserRole = UserRole.USER) =
        seedActor(prefix, role).also { mintedUsers += it.id }

    /** A room owned by [owner] with [member] in it, when given, and thread #1 started by the owner. */
    private suspend fun room(owner: RoomActor, member: RoomActor? = null): UInt {
        val template = TestThreads.seedTemplate().also { mintedTemplates += it }
        val room = TestRooms.seed(owner.id, template, "Lim ${UUID.randomUUID().toString().substring(0, 8)}").also { mintedRooms += it }
        member?.let { TestRooms.addMember(room, it.id) }
        TestThreads.seed(room, owner.id)
        return room
    }

    private suspend fun HttpClient.raw(method: HttpMethod, path: String, json: String): HttpResponse = request(path) {
        this.method = method
        contentType(ContentType.Application.Json)
        setBody(json)
    }

    /** The five non-POST writes, each as a request that is refused or a no-op, never a 429 until the bucket is empty. */
    private suspend fun HttpClient.fiveWrites(room: UInt): List<HttpResponse> = listOf(
        putJson("$rooms/$room/threads/1", ThreadUpdateRequest("T")), // 400: the revision header is missing
        putJson("$rooms/$room/threads/1/status", ThreadStatusRequest("open")), // 204: already there
        putJson("$rooms/$room/threads/1/owner", mapOf("userId" to 0)), // 400: not a member
        putJson("$rooms/$room/messages/2000000000", MessageRequest("x")), // 404
        delete("$rooms/$room/messages/2000000000"), // 404
    )

    @Test
    fun `chat-write is ONE bucket for the five other writes - 240 a minute per user, then 429 with its audit line`() = testApplication {
        usePostgresTestcontainer()
        val limited = actor("cw-bucket")
        val other = actor("cw-bucket-other")
        val room = room(limited, other)

        withAuditCapture { capture ->
            repeat(48) { n ->
                limited.client.fiveWrites(room).forEachIndexed { which, response ->
                    assertTrue(response.status != HttpStatusCode.TooManyRequests, "round ${n + 1}, write ${which + 1}: ${response.status}")
                }
            }
            val rejected = limited.client.fiveWrites(room)
            assertTrue(rejected.all { it.status == HttpStatusCode.TooManyRequests }, "every one of the five spends the same bucket")
            assertEquals(429, rejected.first().body<ProblemDetail>().status)
            assertNotNull(rejected.first().headers["Retry-After"], "the bucket 429 says when to retry")
            val event = capture.awaitEvent { it.message == "rate_limit.exceeded" && it.hasKeyValue("bucket", "chat-write") }
            assertNotNull(event)
            assertTrue(event.hasKeyValue("byUserId", limited.id.toLong()))
            val otherMove = other.client.putJson("$rooms/$room/threads/1/status", ThreadStatusRequest("open"))
            assertEquals(HttpStatusCode.NoContent, otherMove.status)
            assertEquals(HttpStatusCode.OK, limited.client.get("$rooms/$room/threads/1").status, "reads are not bucketed")
            assertEquals(HttpStatusCode.Created, limited.client.postJson("$rooms/$room/threads/1/messages", MessageRequest("x")).status)
        }
    }

    @Test
    fun `a message write over 64 KiB is 413 before any guard, a maximal escaped body still posts, and a thread keeps the larger ceiling`() =
        testApplication {
            usePostgresTestcontainer()
            val owner = actor("cw-413")
            val outsider = actor("cw-413-out")
            val room = room(owner)
            val padding = "x".repeat(70 * 1024)

            val post = "$rooms/$room/threads/1/messages"
            assertEquals(HttpStatusCode.PayloadTooLarge, owner.client.raw(HttpMethod.Post, post, """{"body":"$padding"}""").status)
            assertEquals(HttpStatusCode.PayloadTooLarge, outsider.client.raw(HttpMethod.Post, post, """{"body":"$padding"}""").status)
            val message = TestThreads.post(room, 1, owner.id, "to edit")
            val edit = "$rooms/$room/messages/$message"
            assertEquals(HttpStatusCode.PayloadTooLarge, owner.client.raw(HttpMethod.Put, edit, """{"body":"$padding"}""").status)

            // 8,000 UTF-16 units, every one a six-byte JSON escape: 48,000 bytes, the widest legitimate message, is under the ceiling.
            val widest = """{"body":"${"\\u00e9".repeat(8000)}"}"""
            assertTrue(widest.length in 48_000..65_535, "${widest.length} bytes")
            val posted = owner.client.raw(HttpMethod.Post, post, widest)
            assertEquals(HttpStatusCode.Created, posted.status)
            assertEquals(8000, posted.body<MessageResponse>().body?.length)
            assertEquals(HttpStatusCode.NoContent, owner.client.raw(HttpMethod.Put, edit, widest).status)

            // A thread create may carry more than a message: 150 KB of insignificant whitespace passes where the message ceiling would not.
            val create = "$rooms/$room/threads"
            val roomy = """{"title":"T","body":"B",${" ".repeat(150_000)}"fields":{"severity":"s1"}}"""
            assertEquals(HttpStatusCode.Created, owner.client.raw(HttpMethod.Post, create, roomy).status)
            val tooBig = """{"title":"T","body":"B",${" ".repeat(200 * 1024)}"fields":{"severity":"s1"}}"""
            assertEquals(HttpStatusCode.PayloadTooLarge, owner.client.raw(HttpMethod.Post, create, tooBig).status)
            assertEquals(HttpStatusCode.PayloadTooLarge, outsider.client.raw(HttpMethod.Post, create, tooBig).status)
            val details = "$rooms/$room/threads/1"
            assertEquals(HttpStatusCode.PayloadTooLarge, owner.client.raw(HttpMethod.Put, details, tooBig).status)
            assertEquals(2, TestThreads.rawAll(room).size, "only the seed and the 150 KB create were written")
        }

    @Test
    fun `fields of the wrong shape is a 400 with a fixed detail BEFORE the room lock - held, and the answer is still immediate`() =
        testApplication {
            usePostgresTestcontainer()
            val owner = actor("cw-shape")
            val member = actor("cw-shape-mem")
            val room = room(owner, member)
            val tooMany = (1..21).joinToString(",") { "\"k$it\":1" }
            val bad = listOf(
                """{"note":{"a":{"b":1}}}""",
                """{"note":{}}""",
                """{"tags":[["a"]]}""",
                """{"tags":[{"a":1}]}""",
                """{"tags":[1]}""",
                """{"tags":[${(0..50).joinToString(",") { "\"a$it\"" }}]}""",
                "{$tooMany}",
            )
            val revision = TestThreads.raw(room, 1)!!.revision

            TestThreads.holdingRoomLock(room) {
                for (fields in bad) {
                    val elapsed = measureTimeMillis {
                        val createBody = """{"title":"T","body":"B","fields":$fields}"""
                        val create = member.client.raw(HttpMethod.Post, "$rooms/$room/threads", createBody)
                        assertEquals(HttpStatusCode.BadRequest, create.status, fields)
                        val detail = create.body<ProblemDetail>().detail.orEmpty()
                        assertTrue("single value" in detail || "at most 20 fields" in detail, detail)
                        assertTrue("a1" !in detail && "k21" !in detail, "the detail echoes nothing: $detail")
                        val update = member.client.request("$rooms/$room/threads/1") {
                            method = HttpMethod.Put
                            headers.append("X-Expected-Revision", revision.toString())
                            contentType(ContentType.Application.Json)
                            setBody("""{"title":"T","fields":$fields}""")
                        }
                        assertEquals(HttpStatusCode.BadRequest, update.status, fields)
                    }
                    assertTrue(elapsed < 1500, "refused without waiting for the lock ($elapsed ms): $fields")
                }
            }
            assertEquals(1, TestThreads.rawAll(room).size, "nothing was written")
            assertEquals(revision, TestThreads.raw(room, 1)!!.revision)
        }

    /** Sends [json] to the path exactly as spelled (percent-encoding kept): the client would otherwise decode and re-encode it. */
    private suspend fun HttpClient.rawEncoded(method: HttpMethod, encodedPath: String, json: String): HttpResponse = request {
        this.method = method
        url { this.encodedPath = encodedPath }
        contentType(ContentType.Application.Json)
        setBody(json)
    }

    private fun encodedDigits(id: UInt): String = id.toString().map { "%%%02X".format(it.code) }.joinToString("")

    @Test
    fun `the ceilings match the path as routing sees it - an encoded room id, number or letter does not slip past to the global one`() =
        testApplication {
            usePostgresTestcontainer()
            val owner = actor("cw-enc")
            val room = room(owner)
            val id = encodedDigits(room)
            // No token and no conformance plugin: the ceiling answers before authentication, and an encoded spelling is not a spec path.
            val plain = createClient { }
            val padding = "x".repeat(70 * 1024)

            // Each of these routes to the same handler as its plain spelling, and each must meet the same ceiling (413, before any guard).
            assertEquals(
                HttpStatusCode.PayloadTooLarge,
                plain.rawEncoded(HttpMethod.Post, "/api/v1/rooms/$id/threads/%31/messages", """{"body":"$padding"}""").status,
            )
            val thread = """{"title":"T","body":"B",${" ".repeat(200 * 1024)}"x":1}"""
            assertEquals(HttpStatusCode.PayloadTooLarge, plain.rawEncoded(HttpMethod.Post, "/api/v1/rooms/$id/threads", thread).status)
            val members = """{"userIds":[1],${" ".repeat(20 * 1024)}"x":1}"""
            assertEquals(HttpStatusCode.PayloadTooLarge, plain.rawEncoded(HttpMethod.Post, "/api/v1/%72ooms/$id/members", members).status)
            // The anonymous 16 KiB routes too, with a trailing slash and a doubled one (routing ignores both).
            val junk = """{"email":"a@b.c","password":"${"p".repeat(20 * 1024)}"}"""
            assertEquals(HttpStatusCode.PayloadTooLarge, plain.rawEncoded(HttpMethod.Post, "/api/v1/%6Cogin", junk).status)
            assertEquals(HttpStatusCode.PayloadTooLarge, plain.rawEncoded(HttpMethod.Post, "/api/v1//login/", junk).status)
        }

    @Test
    fun `the status and owner PUTs take at most 16 KiB`() = testApplication {
        usePostgresTestcontainer()
        val owner = actor("cw-small")
        val room = room(owner)
        val big = """{"status":"open",${" ".repeat(20 * 1024)}"x":1}"""
        assertEquals(HttpStatusCode.PayloadTooLarge, owner.client.raw(HttpMethod.Put, "$rooms/$room/threads/1/status", big).status)
        assertEquals(HttpStatusCode.PayloadTooLarge, owner.client.raw(HttpMethod.Put, "$rooms/$room/threads/1/owner", big).status)
        val small = owner.client.raw(HttpMethod.Put, "$rooms/$room/threads/1/status", """{"status":"open"}""")
        assertEquals(HttpStatusCode.NoContent, small.status)
    }
}
