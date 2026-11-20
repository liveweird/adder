package ch.nokillswit

import ch.nokillswit.plugins.ProblemDetail
import ch.nokillswit.rooms.RoomUpdateRequest
import ch.nokillswit.threads.MessagePageResponse
import ch.nokillswit.threads.MessageRequest
import ch.nokillswit.threads.MessageResponse
import ch.nokillswit.threads.PinRequest
import ch.nokillswit.threads.ReactionRequest
import ch.nokillswit.threads.ThreadStatusRequest
import ch.nokillswit.threads.ThreadUpdateRequest
import ch.nokillswit.users.UserRole
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.encodedPath
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * The write bounds of the thread and message routes (the 0.3.0 security audit, M1 and M3): the per-user `chat-write` bucket of the
 * non-POST writes (the reaction and the two pin PUTs ride it), the body ceilings (64 KiB for a message, 192 KiB for a thread, 413 on the
 * rest), and the shape of `fields`, refused with a `400` BEFORE the room lock. The `chat-post` bucket and the lock races are
 * `ThreadWriteBoundsTest`; the room lock's own bound is `RoomBusyTest`. Rooms, templates and users are SHARED suite state: every test
 * mints UNIQUE rows.
 */
class ChatWriteLimitsTest {
    /** The `chat-write` bucket's size (`RateLimits`): 240 a minute per user. */
    private val chatWritePerMinute = 240

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

    /**
     * The non-POST writes, each a request that is refused or a no-op and never a 429 until the bucket is empty. A write added to the
     * bucket is one more entry here; the test below spends the 240 tokens across them in turn, whatever their number.
     */
    private fun otherWrites(room: UInt): List<suspend HttpClient.() -> HttpResponse> = listOf(
        { putJson("$rooms/$room/threads/1", ThreadUpdateRequest("T")) }, // 400: the revision header is missing
        { putJson("$rooms/$room/threads/1/status", ThreadStatusRequest("open")) }, // 204: already there
        { putJson("$rooms/$room/threads/1/owner", mapOf("ownerId" to 0)) }, // 400: not a member
        { putJson("$rooms/$room/messages/2000000000", MessageRequest("x")) }, // 404
        { delete("$rooms/$room/messages/2000000000") }, // 404
        { putJson("$rooms/$room/messages/2000000000/reaction", ReactionRequest("👍", true)) }, // 404
        { putJson("$rooms/$room/threads/99/pin", PinRequest(true)) }, // 404
        { putJson("$rooms/$room/messages/2000000000/pin", PinRequest(true)) }, // 404
    )

    @Test
    fun `chat-write is ONE bucket for the other writes - 240 a minute per user, then 429 with its audit line`() = testApplication {
        usePostgresTestcontainer()
        val limited = actor("cw-bucket")
        val other = actor("cw-bucket-other")
        val room = room(limited, other)
        val writes = otherWrites(room)

        withAuditCapture { capture ->
            repeat(chatWritePerMinute) { n ->
                val response = writes[n % writes.size](limited.client)
                assertTrue(response.status != HttpStatusCode.TooManyRequests, "request ${n + 1}: ${response.status}")
            }
            val rejected = writes.map { write -> write(limited.client) }
            assertTrue(rejected.all { it.status == HttpStatusCode.TooManyRequests }, "every one of the writes spends the same bucket")
            assertEquals(429, rejected.first().body<ProblemDetail>().status)
            assertTrue(
                Regex("\\d+").matches(rejected.first().headers["Retry-After"].orEmpty()),
                "the bucket 429 says when to retry, in whole seconds",
            )
            val event = capture.awaitEvent { it.message == "rate_limit.exceeded" && it.hasKeyValue("bucket", "chat-write") }
            assertNotNull(event)
            assertTrue(event.hasKeyValue("byUserId", limited.id.toLong()))
            val otherMove = other.client.putJson("$rooms/$room/threads/1/status", ThreadStatusRequest("open"))
            assertEquals(HttpStatusCode.NoContent, otherMove.status)
            assertEquals(HttpStatusCode.OK, limited.client.get("$rooms/$room/threads/1").status, "reads are not bucketed")
            assertEquals(HttpStatusCode.Created, limited.client.postJson("$rooms/$room/threads/1/messages", MessageRequest("x")).status)
        }
    }

    /**
     * The room-lock writes that ride `chat-write` since the 1.0.0 checkup (C-SEC-7): each a request that is refused or a no-op, so 240 of
     * them in any order change nothing the next one needs. Archive and unarchive alternate, ending unarchived.
     */
    private fun roomWrites(room: UInt, memberId: UInt): List<suspend HttpClient.() -> HttpResponse> = listOf(
        { post("$rooms/$room/archive") }, // 204
        { post("$rooms/$room/unarchive") }, // 204
        { delete("$rooms/$room") }, // 403: the owner is not an ADMIN
        { putJson("$rooms/$room/members/$memberId", mapOf("role" to "MEMBER")) }, // 204: the role it has
        { delete("$rooms/$room/members/2000000000") }, // 404: not a member
        { raw(HttpMethod.Put, "$rooms/$room/workflow", "{}") }, // 400: the revision header is missing
    )

    @Test
    fun `the room-lock writes spend the chat-write bucket - 240 a minute per user across the six, then 429`() = testApplication {
        usePostgresTestcontainer()
        val owner = actor("cw-room")
        val member = actor("cw-room-mem")
        val room = room(owner, member)
        val writes = roomWrites(room, member.id)

        withAuditCapture { capture ->
            repeat(chatWritePerMinute) { n ->
                val response = writes[n % writes.size](owner.client)
                assertTrue(response.status != HttpStatusCode.TooManyRequests, "request ${n + 1}: ${response.status}")
            }
            val rejected = writes.map { write -> write(owner.client) }
            assertTrue(
                rejected.all { it.status == HttpStatusCode.TooManyRequests },
                "every one of the six spends the same bucket: ${rejected.map { it.status }}",
            )
            assertEquals(429, rejected.first().body<ProblemDetail>().status)
            assertTrue(
                Regex("\\d+").matches(rejected.first().headers["Retry-After"].orEmpty()),
                "the bucket 429 says when to retry, in whole seconds",
            )
            val event = capture.awaitEvent { it.message == "rate_limit.exceeded" && it.hasKeyValue("bucket", "chat-write") }
            assertNotNull(event)
            assertTrue(event.hasKeyValue("byUserId", owner.id.toLong()))
            // Another account is untouched, and so are the room's name write (its own bucket) and the reads.
            assertEquals(HttpStatusCode.NoContent, member.client.delete("$rooms/$room/members/${member.id}").status, "another user's leave")
            assertEquals(HttpStatusCode.OK, owner.client.get("$rooms/$room").status, "reads are not bucketed")
            val rename = owner.client.putJson("$rooms/$room", RoomUpdateRequest("Renamed ${UUID.randomUUID().toString().substring(0, 8)}"))
            assertEquals(HttpStatusCode.NoContent, rename.status, "the rename rides the room-name bucket, not chat-write")
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
    fun `fields of the wrong shape is a 400 with a fixed detail BEFORE the room lock - held, so a 400 and not a lock-wait 429`() =
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
                    // A write that waited for the held room lock would answer the typed 429 `room-busy` once its lock wait ran out, never
                    // this 400: the status is the proof that the shape was refused first (no wall-clock bound to flake under load).
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
    fun `a compressed message body is never decompressed - it is a 415 and posts nothing`() = testApplication {
        usePostgresTestcontainer()
        val owner = actor("cw-gzip")
        val room = room(owner)
        val post = "$rooms/$room/threads/1/messages"
        for ((encoding, body) in compressedBodies("""{"body":"smuggled by compression"}""")) {
            assertTrue(body.size < 64 * 1024, "the $encoding wire body is under the message ceiling")
            val response = owner.client.request(post) {
                method = HttpMethod.Post
                header(HttpHeaders.ContentEncoding, encoding)
                contentType(ContentType.Application.Json)
                setBody(body)
            }
            assertEquals(HttpStatusCode.UnsupportedMediaType, response.status, encoding)
        }
        val posted = owner.client.get("$rooms/$room/threads/1/messages").body<MessagePageResponse>().items
        assertTrue(posted.none { it.body?.contains("smuggled") == true }, "nothing the compressed bodies carried was posted")
    }

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
            // The notification-preferences PUT, plain and with every digit of the user id percent-encoded (the routed path is the same).
            val preferences = """{"disabled":[],${" ".repeat(20 * 1024)}"x":1}"""
            assertEquals(
                HttpStatusCode.PayloadTooLarge,
                plain.rawEncoded(HttpMethod.Put, "/api/v1/users/${encodedDigits(owner.id)}/notification-preferences", preferences).status,
            )
            assertEquals(
                HttpStatusCode.PayloadTooLarge,
                plain.rawEncoded(HttpMethod.Put, "/api/v1/users/${owner.id}/notification-preferences", preferences).status,
            )
        }

    @Test
    fun `the status, owner, read-marker, reaction and preferences PUTs take at most 16 KiB`() = testApplication {
        usePostgresTestcontainer()
        val owner = actor("cw-small")
        val room = room(owner)
        val big = """{"status":"open",${" ".repeat(20 * 1024)}"x":1}"""
        assertEquals(HttpStatusCode.PayloadTooLarge, owner.client.raw(HttpMethod.Put, "$rooms/$room/threads/1/status", big).status)
        assertEquals(HttpStatusCode.PayloadTooLarge, owner.client.raw(HttpMethod.Put, "$rooms/$room/threads/1/owner", big).status)
        val message = TestThreads.post(room, 1, owner.id, "to react to")
        val marker = "$rooms/$room/threads/1/read-marker"
        assertEquals(HttpStatusCode.PayloadTooLarge, owner.client.raw(HttpMethod.Put, marker, big).status)
        assertEquals(HttpStatusCode.NoContent, owner.client.raw(HttpMethod.Put, marker, """{"messageId":$message}""").status)
        val reaction = "$rooms/$room/messages/$message/reaction"
        assertEquals(HttpStatusCode.PayloadTooLarge, owner.client.raw(HttpMethod.Put, reaction, big).status)
        val thumbsUp = """{"emoji":"👍","reacted":true}"""
        assertEquals(HttpStatusCode.NoContent, owner.client.raw(HttpMethod.Put, reaction, thumbsUp).status)
        val small = owner.client.raw(HttpMethod.Put, "$rooms/$room/threads/1/status", """{"status":"open"}""")
        assertEquals(HttpStatusCode.NoContent, small.status)
        val preferences = "/api/v1/users/${owner.id}/notification-preferences"
        assertEquals(HttpStatusCode.PayloadTooLarge, owner.client.raw(HttpMethod.Put, preferences, big).status)
        assertEquals(HttpStatusCode.NoContent, owner.client.raw(HttpMethod.Put, preferences, """{"disabled":[]}""").status)
    }
}
