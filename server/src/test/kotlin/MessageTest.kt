package ch.nokillswit

import ch.nokillswit.plugins.ProblemDetail
import ch.nokillswit.rooms.ROOM_ARCHIVED_PROBLEM_TYPE
import ch.nokillswit.threads.MAX_MESSAGE_BODY_LENGTH
import ch.nokillswit.threads.MessagePageResponse
import ch.nokillswit.threads.MessageRequest
import ch.nokillswit.threads.MessageResponse
import ch.nokillswit.infra.MUTATION_ID_HEADER
import ch.nokillswit.threads.THREAD_CLOSED_PROBLEM_TYPE
import ch.nokillswit.threads.ThreadCreateRequest
import ch.nokillswit.threads.ThreadResponse
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
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
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive

/**
 * The messages of a thread (0.3.0 S5): the keyset list with its tombstones, the post (the count and the activity bump on one stamp,
 * the `Location`), the body rules, the author-only edit, the author / room OWNER / ADMIN delete (D2) that leaves a listed tombstone, the
 * typed 409s (closed thread, archived room, a moderator's delete included), the audit lines (ids only, never a body), the `chat-post`
 * bucket shared with thread creation, and race 5 (the thread closed under a waiting post). Rooms, templates and users are SHARED suite
 * state: every test mints UNIQUE rows and removes them.
 */
class MessageTest {
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

    private val minted = MintedIds(mintedUsers, mintedTemplates, mintedRooms)

    /** A room owned by `owner` with thread #1 started by `author` (a plain member); `member` is another one, `admin` is no member. */
    private suspend fun ApplicationTestBuilder.cast(prefix: String): ThreadCast = seedThreadCast(prefix, "Msg", minted)

    private suspend fun HttpClient.send(method: HttpMethod, path: String, json: String?): HttpResponse = request(path) {
        this.method = method
        if (json != null) {
            contentType(ContentType.Application.Json)
            setBody(json)
        }
    }

    private suspend fun HttpClient.post(room: UInt, body: String, number: Int = 1): HttpResponse =
        postJson("$rooms/$room/threads/$number/messages", MessageRequest(body))

    private suspend fun HttpClient.postWithMutation(
        room: UInt,
        body: String,
        mutationId: String,
        repeatHeader: Boolean = false,
    ): HttpResponse = request("$rooms/$room/threads/1/messages") {
        method = HttpMethod.Post
        contentType(ContentType.Application.Json)
        header(MUTATION_ID_HEADER, mutationId)
        if (repeatHeader) headers.append(MUTATION_ID_HEADER, mutationId)
        setBody(MessageRequest(body))
    }

    private suspend fun HttpClient.edit(room: UInt, id: UInt, body: String): HttpResponse =
        putJson("$rooms/$room/messages/$id", MessageRequest(body))

    private suspend fun HttpClient.editWithMutation(
        room: UInt,
        id: UInt,
        body: String,
        mutationId: String,
        repeatHeader: Boolean = false,
    ): HttpResponse = request("$rooms/$room/messages/$id") {
        method = HttpMethod.Put
        contentType(ContentType.Application.Json)
        header(MUTATION_ID_HEADER, mutationId)
        if (repeatHeader) headers.append(MUTATION_ID_HEADER, mutationId)
        setBody(MessageRequest(body))
    }

    private suspend fun HttpClient.page(room: UInt, query: String = "", number: Int = 1): MessagePageResponse =
        get("$rooms/$room/threads/$number/messages$query").body()

    private suspend fun HttpClient.message(room: UInt, id: UInt): HttpResponse = get("$rooms/$room/messages/$id")

    private fun rawMessage(cast: ThreadCast, id: UInt) = runBlocking { TestThreads.rawMessages(cast.threadId).single { it.id == id } }

    private fun count(cast: ThreadCast) = runBlocking { TestThreads.raw(cast.room, 1)!!.messageCount }

    @Test
    fun `a member posts - 201 with Location, the count and both activity stamps share the message's instant`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("msg-post")
        TestThreads.setStamps(cast.room, 1, createdAt = 1000, lastActivityAt = 1000) // backdated: the bump below must move it
        val before = TestThreads.raw(cast.room, 1)!!

        val response = cast.member.client.post(cast.room, "Hello **team**")
        assertEquals(HttpStatusCode.Created, response.status)
        val message = response.body<MessageResponse>()
        assertEquals("/api/v1/rooms/${cast.room}/messages/${message.id}", response.headers[HttpHeaders.Location])
        assertEquals(
            listOf<Any?>(1, cast.member.id, "Marek", "Hello **team**", false, false, null),
            listOf(
                message.threadNumber, message.authorId, message.authorName, message.body,
                message.deleted, message.deletedByModerator, message.editedAt,
            ),
        )

        val after = TestThreads.raw(cast.room, 1)!!
        assertEquals(before.messageCount + 1, after.messageCount)
        assertEquals(message.createdAt, after.lastActivityAt)
        assertEquals(message.createdAt, TestThreads.roomActivity(cast.room))
        assertEquals(before.updatedAt, after.updatedAt, "a message is not an edit of the thread record")
        assertEquals(before.revision, after.revision, "a message never moves the details revision")
        assertEquals(listOf("CREATED"), TestThreads.rawEvents(cast.threadId).map { it.type }, "messages are not history")

        // The single read serves the same message, to any reader (an ADMIN outside the room too).
        assertEquals(message, cast.admin.client.message(cast.room, message.id).body<MessageResponse>())
        assertEquals(HttpStatusCode.NotFound, cast.outsider.client.message(cast.room, message.id).status)
    }

    @Test
    fun `the body is normalised and bounded - CRLF to LF, blank, 8001 units and control characters are 400`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("msg-body")
        val normalised = cast.member.client.post(cast.room, "\r\n  first\r\nsecond\tcol  \r\n\r\n").body<MessageResponse>()
        assertEquals("  first\nsecond\tcol", normalised.body, "CRLF to LF, blank lines and trailing space dropped, indentation kept")
        assertEquals(HttpStatusCode.Created, cast.member.client.post(cast.room, "x".repeat(MAX_MESSAGE_BODY_LENGTH)).status)

        val before = count(cast)
        for (bad in listOf("", "   \n\t ", "x".repeat(MAX_MESSAGE_BODY_LENGTH + 1), "bell\u0007", "bidi‮reversed", "sep arator")) {
            assertEquals(HttpStatusCode.BadRequest, cast.member.client.post(cast.room, bad).status, "body of ${bad.length} units")
        }
        for (json in listOf("{ not json", "{}", """{"body":[]}""", """{"body":null}""")) {
            val response = cast.member.client.send(HttpMethod.Post, "$rooms/${cast.room}/threads/1/messages", json)
            assertEquals(HttpStatusCode.BadRequest, response.status, json)
        }
        assertEquals(before, count(cast), "a refused post writes nothing")
        // Authorisation answers before the body: an outsider's malformed body is 404, an ADMIN outside the room's is 403.
        val malformed = "{ not json"
        val path = "$rooms/${cast.room}/threads/1/messages"
        assertEquals(HttpStatusCode.NotFound, cast.outsider.client.send(HttpMethod.Post, path, malformed).status)
        assertEquals(HttpStatusCode.Forbidden, cast.admin.client.send(HttpMethod.Post, path, malformed).status)
        assertEquals(HttpStatusCode.NotFound, cast.member.client.post(cast.room, "Hi", number = 9).status, "an unknown thread")
    }

    @Test
    fun `mutation ids are strict correlation only and are checked between the member guard and body`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("msg-mutation")
        val mutationId = "2c6c7f12-79a4-4f1a-8dc7-e9686608b20a"
        val before = count(cast)

        val first = cast.member.client.postWithMutation(cast.room, "First", mutationId)
        val second = cast.member.client.postWithMutation(cast.room, "Second", mutationId)
        assertEquals(HttpStatusCode.Created, first.status)
        assertEquals(HttpStatusCode.Created, second.status)
        assertTrue(first.body<MessageResponse>().id != second.body<MessageResponse>().id, "the header is not an idempotency key")
        assertEquals(before + 2, count(cast))

        val bad = listOf(
            mutationId.uppercase(),
            "2c6c7f12-79a4-1f1a-8dc7-e9686608b20a",
            " $mutationId",
            "$mutationId ",
            "not-a-uuid",
        )
        bad.forEach { value ->
            assertEquals(
                HttpStatusCode.BadRequest,
                cast.member.client.postWithMutation(cast.room, "Never written", value).status,
                value,
            )
        }
        assertEquals(
            HttpStatusCode.BadRequest,
            cast.member.client.postWithMutation(cast.room, "Never written", mutationId, repeatHeader = true).status,
        )
        assertEquals(before + 2, count(cast), "malformed and repeated headers write nothing")

        val path = "$rooms/${cast.room}/threads/1/messages"
        suspend fun HttpClient.guardedMalformed(): HttpResponse = request(path) {
            method = HttpMethod.Post
            contentType(ContentType.Application.Json)
            header(MUTATION_ID_HEADER, "invalid")
            setBody("{ not json")
        }
        assertEquals(HttpStatusCode.Unauthorized, jsonClient().guardedMalformed().status)
        assertEquals(HttpStatusCode.NotFound, cast.outsider.client.guardedMalformed().status)
        assertEquals(HttpStatusCode.Forbidden, cast.admin.client.guardedMalformed().status)
        val member = cast.member.client.guardedMalformed()
        assertEquals(HttpStatusCode.BadRequest, member.status)
        assertTrue(
            MUTATION_ID_HEADER in member.body<ProblemDetail>().detail.orEmpty(),
            "the header is checked before decoding the malformed body",
        )
    }

    @Test
    fun `the list pages by keyset across three pages, and total is counted after the id-lt filter`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("msg-keyset")
        val ids = listOf(cast.opener) + (1..5).map { TestThreads.post(cast.room, 1, cast.member.id, "Reply $it") }
        val client = cast.admin.client

        val first = client.page(cast.room, "?sort=-id&pageSize=2")
        assertEquals(ids.takeLast(2).reversed(), first.items.map { it.id })
        assertEquals(6L, first.total)
        val second = client.page(cast.room, "?sort=-id&pageSize=2&id[lt]=${first.items.last().id}")
        assertEquals(ids.slice(2..3).reversed(), second.items.map { it.id })
        assertEquals(4L, second.total, "total is counted AFTER the id[lt] filter")
        val third = client.page(cast.room, "?sort=-id&pageSize=2&id[lt]=${second.items.last().id}")
        assertEquals(ids.take(2).reversed(), third.items.map { it.id })
        assertEquals(2L, third.total)
        assertEquals(third.items.size.toLong(), third.total, "total equals what is loaded: no older messages remain")
        assertTrue(client.page(cast.room, "?sort=-id&pageSize=2&id[lt]=${ids.first()}").items.isEmpty())

        assertEquals(ids.reversed(), client.page(cast.room).items.map { it.id }, "the default order is newest first (-id)")
        assertEquals(ids, client.page(cast.room, "?sort=id").items.map { it.id }, "ascending on request")
        assertEquals(ids.reversed(), client.page(cast.room, "?sort=-id").items.map { it.id })
        assertEquals(listOf(1), client.page(cast.room).items.map { it.threadNumber }.distinct())
    }

    @Test
    fun `the list rejects what it does not know and an unknown thread or a non-member is 404`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("msg-list400")
        val base = "$rooms/${cast.room}/threads/1/messages"
        val bad = listOf(
            "?sort=body", "?sort=-createdAt", "?id[lt]=abc", "?id[lt]=0", "?id[lt]=-3", "?id[lt]=2147483648",
            "?id[lt]=5&id[lt]=6", "?page=0", "?pageSize=101",
        )
        for (query in bad) {
            assertEquals(HttpStatusCode.BadRequest, cast.member.client.get("$base$query").status, query)
        }
        assertEquals(HttpStatusCode.OK, cast.member.client.get("$base?id[lt]=2147483647").status)
        assertEquals(HttpStatusCode.NotFound, cast.member.client.get("$rooms/${cast.room}/threads/9/messages").status)
        assertEquals(HttpStatusCode.NotFound, cast.outsider.client.get(base).status)
        // A path id past int32 is refused like every id.
        assertEquals(HttpStatusCode.BadRequest, cast.member.client.get("$rooms/${cast.room}/messages/4294967295").status)
    }

    @Test
    fun `a delete leaves a listed tombstone - scrubbed, the count never goes down, and reads and writes of it are 404`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("msg-tomb")
        val own = TestThreads.post(cast.room, 1, cast.member.id, "my own words")
        val moderated = TestThreads.post(cast.room, 1, cast.member.id, "the password is hunter2")
        assertEquals(HttpStatusCode.NoContent, cast.member.client.edit(cast.room, own, "my edited words").status)
        val countBefore = count(cast)
        val activity = TestThreads.raw(cast.room, 1)!!.lastActivityAt

        assertEquals(HttpStatusCode.NoContent, cast.member.client.delete("$rooms/${cast.room}/messages/$own").status)
        assertEquals(HttpStatusCode.NoContent, cast.owner.client.delete("$rooms/${cast.room}/messages/$moderated").status)

        val listed = cast.member.client.page(cast.room).items.associateBy { it.id }
        val ownTomb = listed.getValue(own)
        val ownShape = listOf(ownTomb.body, ownTomb.deleted, ownTomb.deletedByModerator)
        assertEquals(listOf(null, true, false), ownShape, "the author's own delete")
        assertNull(ownTomb.editedAt, "a tombstone carries no editedAt either")
        assertNotNull(rawMessage(cast, own).editedAt, "...though the row keeps it")
        val moderatedTomb = listed.getValue(moderated)
        assertEquals(listOf(null, true, true), listOf(moderatedTomb.body, moderatedTomb.deleted, moderatedTomb.deletedByModerator))
        assertEquals(listOf("Marek", cast.member.id), listOf(moderatedTomb.authorName, moderatedTomb.authorId), "the author stays named")
        assertFalse(listed.getValue(cast.opener).deleted)
        assertFalse(listed.getValue(cast.opener).deletedByModerator, "always a boolean, false while the message lives")

        val rawOwn = rawMessage(cast, own)
        val rawShape = listOf(rawOwn.body, rawOwn.deleted, rawOwn.deletedBy)
        assertEquals(listOf("", true, cast.member.id), rawShape, "scrubbed, deleted_by is the author")
        val rawModerated = rawMessage(cast, moderated)
        assertEquals(listOf("", cast.owner.id), listOf(rawModerated.body, rawModerated.deletedBy), "deleted_by is the moderator")
        assertEquals(countBefore, count(cast), "message_count never goes down")
        assertEquals(activity, TestThreads.raw(cast.room, 1)!!.lastActivityAt, "a delete does not bump the activity")

        for (id in listOf(own, moderated)) {
            assertEquals(HttpStatusCode.NotFound, cast.member.client.message(cast.room, id).status)
            assertEquals(HttpStatusCode.NotFound, cast.member.client.edit(cast.room, id, "back").status)
            assertEquals(HttpStatusCode.NotFound, cast.member.client.delete("$rooms/${cast.room}/messages/$id").status)
            assertEquals(HttpStatusCode.NotFound, cast.owner.client.delete("$rooms/${cast.room}/messages/$id").status)
            assertEquals(HttpStatusCode.NotFound, cast.admin.client.delete("$rooms/${cast.room}/messages/$id").status)
        }
    }

    @Test
    fun `a message is addressed through ITS room - another room's path, an unknown id and a deleted room are all 404`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("msg-room")
        val template = TestThreads.seedTemplate().also { mintedTemplates += it }
        val elsewhere = TestRooms.seed(cast.owner.id, template, "Else ${UUID.randomUUID()}").also { mintedRooms += it }
        TestRooms.addMember(elsewhere, cast.author.id)
        // The owner and the author are members of BOTH rooms, so only the room match can refuse.
        assertEquals(HttpStatusCode.OK, cast.owner.client.message(cast.room, cast.opener).status)
        assertEquals(HttpStatusCode.NotFound, cast.owner.client.message(elsewhere, cast.opener).status)
        assertEquals(HttpStatusCode.NotFound, cast.author.client.edit(elsewhere, cast.opener, "moved").status)
        assertEquals(HttpStatusCode.NotFound, cast.owner.client.delete("$rooms/$elsewhere/messages/${cast.opener}").status)
        assertEquals(HttpStatusCode.NotFound, cast.owner.client.message(cast.room, 999_999u).status)
        assertEquals(HttpStatusCode.NotFound, cast.admin.client.message(999_999u, cast.opener).status)
        assertFalse(rawMessage(cast, cast.opener).deleted)
        assertEquals("Opening message", rawMessage(cast, cast.opener).body)
    }

    @Test
    fun `only the author edits - 204 sets editedAt, nobody else may, and the same body is a no-op with no audit line`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("msg-edit")
        val id = TestThreads.post(cast.room, 1, cast.member.id, "first draft")
        TestThreads.setStamps(cast.room, 1, createdAt = 1000, lastActivityAt = 1000)
        val before = TestThreads.raw(cast.room, 1)!!
        val activity = TestThreads.roomActivity(cast.room)

        withAuditCapture { capture ->
            assertEquals(HttpStatusCode.NoContent, cast.member.client.edit(cast.room, id, "second draft\r\n").status)
            val edited = cast.member.client.message(cast.room, id).body<MessageResponse>()
            assertEquals("second draft", edited.body)
            val editedAt = assertNotNull(edited.editedAt)
            assertTrue(editedAt >= edited.createdAt)

            // The same body again (after sanitising) writes nothing: editedAt stays, there is no audit line.
            assertEquals(HttpStatusCode.NoContent, cast.member.client.edit(cast.room, id, "second draft\n\n").status)
            assertEquals(editedAt, cast.member.client.message(cast.room, id).body<MessageResponse>().editedAt)

            // Nobody else edits - the thread's owner, the room owner and an ADMIN included (they may delete, never edit).
            for (other in listOf(cast.author, cast.owner, cast.admin)) {
                assertEquals(HttpStatusCode.Forbidden, other.client.edit(cast.room, id, "hijacked").status)
            }
            assertEquals(HttpStatusCode.NotFound, cast.outsider.client.edit(cast.room, id, "hijacked").status)
            assertEquals("second draft", rawMessage(cast, id).body)

            val lines = capture.events.filter { it.message == "message.edited" && it.hasKeyValue("roomId", cast.room.toLong()) }
            val line = assertNotNull(lines.singleOrNull(), "one edit, one line")
            assertTrue(line.hasKeyValue("byUserId", cast.member.id.toLong()))
            assertTrue(line.hasKeyValue("threadId", cast.threadId.toLong()) && line.hasKeyValue("messageId", id.toLong()))
            val everything = capture.events.joinToString { e ->
                "${e.formattedMessage} ${e.keyValuePairs?.joinToString { "${it.key}=${it.value}" }}"
            }
            assertTrue("draft" !in everything && "hijacked" !in everything, "no body reaches the audit trail")
        }
        val after = TestThreads.raw(cast.room, 1)!!
        assertEquals(before, after, "an edit bumps nothing and counts nothing")
        assertEquals(activity, TestThreads.roomActivity(cast.room))
        assertEquals(listOf("CREATED"), TestThreads.rawEvents(cast.threadId).map { it.type }, "edits go to the audit trail only")
        for (bad in listOf("", "  ", "x".repeat(MAX_MESSAGE_BODY_LENGTH + 1), "ctl\u0001")) {
            assertEquals(HttpStatusCode.BadRequest, cast.member.client.edit(cast.room, id, bad).status)
        }
    }

    @Test
    fun `edit mutation ids are checked after the author guard and before the body`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("msg-edit-mutation")
        val id = TestThreads.post(cast.room, 1, cast.member.id, "first draft")
        val mutationId = "2c6c7f12-79a4-4f1a-8dc7-e9686608b20a"

        assertEquals(HttpStatusCode.NoContent, cast.member.client.editWithMutation(cast.room, id, "second draft", mutationId).status)
        assertEquals(
            HttpStatusCode.BadRequest,
            cast.member.client.editWithMutation(cast.room, id, "never written", mutationId.uppercase()).status,
        )
        assertEquals(
            HttpStatusCode.BadRequest,
            cast.member.client.editWithMutation(cast.room, id, "never written", mutationId, repeatHeader = true).status,
        )

        suspend fun HttpClient.guardedMalformed(messageId: UInt): HttpResponse = request("$rooms/${cast.room}/messages/$messageId") {
            method = HttpMethod.Put
            contentType(ContentType.Application.Json)
            header(MUTATION_ID_HEADER, "invalid")
            setBody("{ not json")
        }
        assertEquals(HttpStatusCode.Unauthorized, jsonClient().guardedMalformed(id).status)
        assertEquals(HttpStatusCode.NotFound, cast.outsider.client.guardedMalformed(id).status)
        assertEquals(HttpStatusCode.Forbidden, cast.owner.client.guardedMalformed(id).status)
        val member = cast.member.client.guardedMalformed(id)
        assertEquals(HttpStatusCode.BadRequest, member.status)
        assertTrue(MUTATION_ID_HEADER in member.body<ProblemDetail>().detail.orEmpty())
        assertEquals("second draft", rawMessage(cast, id).body)
    }

    @Test
    fun `the author, the room owner and an admin delete - a plain member cannot - and the audit says moderated or not`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("msg-del")
        val a = TestThreads.post(cast.room, 1, cast.member.id, "secret value a")
        val b = TestThreads.post(cast.room, 1, cast.member.id, "secret value b")
        val c = TestThreads.post(cast.room, 1, cast.member.id, "secret value c")

        withAuditCapture { capture ->
            val byOther = cast.author.client.delete("$rooms/${cast.room}/messages/$a")
            assertEquals(HttpStatusCode.Forbidden, byOther.status, "another plain member")
            assertEquals(HttpStatusCode.NotFound, cast.outsider.client.delete("$rooms/${cast.room}/messages/$a").status)
            assertEquals(HttpStatusCode.NoContent, cast.member.client.delete("$rooms/${cast.room}/messages/$a").status)
            assertEquals(HttpStatusCode.NoContent, cast.owner.client.delete("$rooms/${cast.room}/messages/$b").status)
            assertEquals(HttpStatusCode.NoContent, cast.admin.client.delete("$rooms/${cast.room}/messages/$c").status)

            val lines = capture.events.filter { it.message == "message.deleted" && it.hasKeyValue("roomId", cast.room.toLong()) }
            assertEquals(3, lines.size, "one line per delete, none for a refusal")
            fun lineOf(id: UInt) = lines.single { it.hasKeyValue("messageId", id.toLong()) }
            assertTrue(lineOf(a).hasKeyValue("moderated", false) && lineOf(a).hasKeyValue("byUserId", cast.member.id.toLong()))
            assertTrue(lineOf(b).hasKeyValue("moderated", true) && lineOf(b).hasKeyValue("byUserId", cast.owner.id.toLong()))
            assertTrue(lineOf(c).hasKeyValue("moderated", true) && lineOf(c).hasKeyValue("byUserId", cast.admin.id.toLong()))
            assertTrue(lines.all { it.hasKeyValue("authorId", cast.member.id.toLong()) })
            assertTrue(lines.all { it.hasKeyValue("threadId", cast.threadId.toLong()) })
            val everything = capture.events.joinToString { e ->
                "${e.formattedMessage} ${e.keyValuePairs?.joinToString { "${it.key}=${it.value}" }}"
            }
            assertTrue("secret value" !in everything, "no body reaches the audit trail")
        }
        assertEquals(listOf(cast.member.id, cast.owner.id, cast.admin.id), listOf(a, b, c).map { rawMessage(cast, it).deletedBy })
    }

    @Test
    fun `an author who left the room can neither edit nor delete - 404 - and a room owner still removes their message`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("msg-left")
        val id = TestThreads.post(cast.room, 1, cast.member.id, "I was here")
        PostgresTestSupport.connect().use { c ->
            c.prepareStatement("DELETE FROM room_members WHERE room_id = ? AND user_id = ?").use { st ->
                st.setInt(1, cast.room.toInt())
                st.setLong(2, cast.member.id.toLong())
                assertEquals(1, st.executeUpdate())
            }
        }
        assertEquals(HttpStatusCode.NotFound, cast.member.client.edit(cast.room, id, "still me").status)
        assertEquals(HttpStatusCode.NotFound, cast.member.client.delete("$rooms/${cast.room}/messages/$id").status)
        assertEquals(HttpStatusCode.NotFound, cast.member.client.post(cast.room, "back again").status)
        assertEquals(HttpStatusCode.NoContent, cast.owner.client.delete("$rooms/${cast.room}/messages/$id").status)
        assertTrue(rawMessage(cast, id).deleted)
    }

    @Test
    fun `a closed thread refuses a post, an edit and a delete - a moderator's too - with thread-closed`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("msg-closed")
        val id = TestThreads.post(cast.room, 1, cast.member.id, "before the close")
        TestThreads.closeRaw(cast.room, 1)
        val before = TestThreads.raw(cast.room, 1)!!
        val roomActivity = TestThreads.roomActivity(cast.room)

        val refusals = listOf(
            "post" to cast.member.client.post(cast.room, "too late"),
            "edit" to cast.member.client.edit(cast.room, id, "too late"),
            "edit with the same body" to cast.member.client.edit(cast.room, id, "before the close"),
            "delete" to cast.member.client.delete("$rooms/${cast.room}/messages/$id"),
            "moderator delete" to cast.owner.client.delete("$rooms/${cast.room}/messages/$id"),
            "admin delete" to cast.admin.client.delete("$rooms/${cast.room}/messages/$id"),
        )
        for ((what, response) in refusals) {
            assertEquals(HttpStatusCode.Conflict, response.status, what)
            assertEquals(THREAD_CLOSED_PROBLEM_TYPE, response.body<ProblemDetail>().type, what)
        }
        assertEquals(before, TestThreads.raw(cast.room, 1), "nothing was written")
        assertEquals(roomActivity, TestThreads.roomActivity(cast.room))
        assertEquals("before the close", rawMessage(cast, id).body)
        assertFalse(rawMessage(cast, id).deleted)
        // Reads still work on a closed thread.
        assertEquals(HttpStatusCode.OK, cast.member.client.message(cast.room, id).status)
        assertEquals(2, cast.member.client.page(cast.room).items.size, "the opener and the one reply")
    }

    @Test
    fun `an archived room refuses a post, an edit and a delete with room-archived, which wins over closed`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("msg-arch")
        val id = TestThreads.post(cast.room, 1, cast.member.id, "before the archive")
        TestRooms.archive(cast.room)
        val before = TestThreads.raw(cast.room, 1)!!

        suspend fun assertArchived() {
            val refusals = listOf(
                cast.member.client.post(cast.room, "too late"),
                cast.member.client.edit(cast.room, id, "too late"),
                cast.member.client.delete("$rooms/${cast.room}/messages/$id"),
                cast.owner.client.delete("$rooms/${cast.room}/messages/$id"),
            )
            for (response in refusals) {
                assertEquals(HttpStatusCode.Conflict, response.status)
                assertEquals(ROOM_ARCHIVED_PROBLEM_TYPE, response.body<ProblemDetail>().type)
            }
        }
        assertArchived()
        TestThreads.closeRaw(cast.room, 1)
        assertArchived()
        assertEquals(before.messageCount, count(cast))
        assertEquals("before the archive", rawMessage(cast, id).body)
        assertEquals(HttpStatusCode.OK, cast.member.client.message(cast.room, id).status)
        assertEquals(HttpStatusCode.OK, cast.admin.client.get("$rooms/${cast.room}/threads/1/messages").status)
    }

    @Test
    fun `posting audits message posted with ids only - once per post, none for a refusal`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("msg-aud")
        withAuditCapture { capture ->
            val body = "Secret body ${UUID.randomUUID()}"
            val posted = cast.member.client.post(cast.room, body).body<MessageResponse>()
            assertEquals(HttpStatusCode.BadRequest, cast.member.client.post(cast.room, " ").status)
            assertEquals(HttpStatusCode.Forbidden, cast.admin.client.post(cast.room, body).status)
            assertEquals(HttpStatusCode.NotFound, cast.outsider.client.post(cast.room, body).status)
            val line = assertNotNull(
                capture.awaitEvent { it.message == "message.posted" && it.hasKeyValue("roomId", cast.room.toLong()) },
            )
            assertTrue(line.hasKeyValue("byUserId", cast.member.id.toLong()))
            assertTrue(line.hasKeyValue("threadId", cast.threadId.toLong()) && line.hasKeyValue("number", 1))
            assertTrue(line.hasKeyValue("messageId", posted.id.toLong()))
            assertEquals(1, capture.events.count { it.message == "message.posted" }, "a refusal audits no post")
            val everything = capture.events.joinToString { e ->
                "${e.formattedMessage} ${e.keyValuePairs?.joinToString { "${it.key}=${it.value}" }}"
            }
            assertTrue(body !in everything, "no body reaches the audit trail")
        }
    }

    @Test
    fun `chat-post is ONE bucket for thread creation and message posts - 120 a minute per user, then 429`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("msg-bucket")
        val client = cast.member.client
        val severity = mapOf("severity" to JsonPrimitive("s1"))
        withAuditCapture { capture ->
            repeat(60) { n ->
                assertEquals(HttpStatusCode.BadRequest, client.post(cast.room, " ").status, "message post ${n + 1}")
                val create = client.postJson("$rooms/${cast.room}/threads", ThreadCreateRequest(" ", "B", severity))
                assertEquals(HttpStatusCode.BadRequest, create.status, "thread create ${n + 1}")
            }
            val rejected = client.post(cast.room, "one too many")
            assertEquals(HttpStatusCode.TooManyRequests, rejected.status)
            assertEquals(429, rejected.body<ProblemDetail>().status)
            val event = capture.awaitEvent { it.message == "rate_limit.exceeded" && it.hasKeyValue("bucket", "chat-post") }
            assertNotNull(event)
            assertTrue(event.hasKeyValue("byUserId", cast.member.id.toLong()))
            val create = client.postJson("$rooms/${cast.room}/threads", ThreadCreateRequest("T", "B", severity))
            assertEquals(HttpStatusCode.TooManyRequests, create.status)
            assertEquals(HttpStatusCode.Created, cast.author.client.post(cast.room, "another user is untouched").status)
            assertEquals(HttpStatusCode.OK, client.get("$rooms/${cast.room}/threads/1/messages").status, "reads are not bucketed")
        }
    }

    @Test
    fun `race 5 - a thread closed while a post waits for the room lock is 409 thread-closed and nothing is written`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("msg-race5")
        val before = TestThreads.raw(cast.room, 1)!!
        val activity = TestThreads.roomActivity(cast.room)

        TestThreads.holdingRoomLock(
            cast.room,
            "UPDATE threads SET status_key = 'done', closed_at = ? WHERE room_id = ? AND \"number\" = ?",
            1_000u, cast.room, 1u,
        ) { raw ->
            coroutineScope {
                // The route's own checks pass (the close is uncommitted: the thread is still open); the post then waits for the room lock.
                val posting = async { cast.member.client.post(cast.room, "racing the close") }
                awaitLockWait("FOR NO KEY UPDATE")
                raw.commit()
                val response = posting.await()
                assertEquals(HttpStatusCode.Conflict, response.status)
                assertEquals(THREAD_CLOSED_PROBLEM_TYPE, response.body<ProblemDetail>().type)
            }
        }
        val after = TestThreads.raw(cast.room, 1)!!
        val state = listOf(after.statusKey, after.closedAt, after.messageCount)
        assertEquals(listOf("done", 1_000L, before.messageCount), state, "only the rival's close")
        assertEquals(1, TestThreads.rawMessages(cast.threadId).size, "the refused post wrote no message")
        assertEquals(activity, TestThreads.roomActivity(cast.room))
    }

    @Test
    fun `a thread read after posts reports the message count the opener and every post add up to`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("msg-count")
        repeat(3) { assertEquals(HttpStatusCode.Created, cast.member.client.post(cast.room, "reply $it").status) }
        val thread = cast.member.client.get("$rooms/${cast.room}/threads/1").body<ThreadResponse>()
        assertEquals(4, thread.messageCount)
        assertNull(rawMessage(cast, cast.opener).editedAt)
    }

    @Test
    fun `race 5b - the author and the room owner delete at once - the waiting second one is 404, deleted_by stays and one is audited`() =
        testApplication {
            usePostgresTestcontainer()
            val cast = cast("msg-race5b")
            val id = TestThreads.post(cast.room, 1, cast.member.id, "delete me twice")

            withAuditCapture { capture ->
                TestThreads.holdingRoomLock(
                    cast.room,
                    "UPDATE messages SET body = '', marked_as_deleted = TRUE, deleted_by = ? WHERE id = ?",
                    cast.member.id, id,
                ) { raw ->
                    coroutineScope {
                        // The pre-check passes (the author's delete is uncommitted); the moderator's delete then waits for the lock.
                        val moderating = async { cast.owner.client.delete("$rooms/${cast.room}/messages/$id") }
                        awaitLockWait("FOR NO KEY UPDATE")
                        raw.commit()
                        assertEquals(HttpStatusCode.NotFound, moderating.await().status)
                    }
                }
                assertTrue(capture.events.none { it.message == "message.deleted" && it.hasKeyValue("roomId", cast.room.toLong()) })
            }
            val row = rawMessage(cast, id)
            assertEquals(listOf("", true, cast.member.id), listOf(row.body, row.deleted, row.deletedBy), "the first delete stands")
        }

    @Test
    fun `race 5c - a room owner demoted while their moderator delete waits for the lock gets 403 and the body stays`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("msg-race5c")
        val id = TestThreads.post(cast.room, 1, cast.member.id, "still here")

        withAuditCapture { capture ->
            TestThreads.holdingRoomLock(
                cast.room,
                "UPDATE room_members SET role = 'MEMBER' WHERE room_id = ? AND user_id = ?",
                cast.room, cast.owner.id,
            ) { raw ->
                coroutineScope {
                    val moderating = async { cast.owner.client.delete("$rooms/${cast.room}/messages/$id") }
                    awaitLockWait("FOR NO KEY UPDATE")
                    raw.commit()
                    assertEquals(HttpStatusCode.Forbidden, moderating.await().status)
                }
            }
            assertTrue(capture.events.none { it.message == "message.deleted" && it.hasKeyValue("roomId", cast.room.toLong()) })
        }
        val row = rawMessage(cast, id)
        assertEquals(listOf("still here", false, null), listOf(row.body, row.deleted, row.deletedBy))
    }

    @Test
    fun `an admin who wrote a message and is no member any more deletes it - 204, not moderated`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("msg-admauth")
        TestRooms.addMember(cast.room, cast.admin.id)
        val id = TestThreads.post(cast.room, 1, cast.admin.id, "an admin's words")
        PostgresTestSupport.connect().use { c ->
            c.prepareStatement("DELETE FROM room_members WHERE room_id = ? AND user_id = ?").use { st ->
                st.setInt(1, cast.room.toInt())
                st.setLong(2, cast.admin.id.toLong())
                assertEquals(1, st.executeUpdate())
            }
        }
        withAuditCapture { capture ->
            assertEquals(HttpStatusCode.NoContent, cast.admin.client.delete("$rooms/${cast.room}/messages/$id").status)
            val deleted = capture.events.filter { it.message == "message.deleted" && it.hasKeyValue("messageId", id.toLong()) }
            val line = assertNotNull(deleted.singleOrNull())
            assertTrue(line.hasKeyValue("moderated", false) && line.hasKeyValue("authorId", cast.admin.id.toLong()))
        }
        val tomb = cast.member.client.page(cast.room).items.single { it.id == id }
        assertEquals(listOf(true, false), listOf(tomb.deleted, tomb.deletedByModerator))
        assertEquals(cast.admin.id, rawMessage(cast, id).deletedBy)
    }
}
