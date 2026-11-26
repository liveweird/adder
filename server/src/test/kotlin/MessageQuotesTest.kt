package ch.nokillswit

import ch.nokillswit.infra.paging.PageResponse
import ch.nokillswit.plugins.ProblemDetail
import ch.nokillswit.rooms.ROOM_ARCHIVED_PROBLEM_TYPE
import ch.nokillswit.rooms.ROOM_BUSY_PROBLEM_TYPE
import ch.nokillswit.threads.GroupMention
import ch.nokillswit.threads.MAX_QUOTED_BODY
import ch.nokillswit.threads.MentionGroupKind
import ch.nokillswit.threads.MentionedUser
import ch.nokillswit.threads.MessagePageResponse
import ch.nokillswit.threads.MessagePostRequest
import ch.nokillswit.threads.MessageQuote
import ch.nokillswit.threads.MessageRequest
import ch.nokillswit.threads.MessageResponse
import ch.nokillswit.threads.PinRequest
import ch.nokillswit.threads.QUOTE_UNAVAILABLE_PROBLEM_TYPE
import ch.nokillswit.threads.RoomPinResponse
import ch.nokillswit.threads.THREAD_CLOSED_PROBLEM_TYPE
import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
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
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory

/**
 * Quotes (`chat-model.md` "Quotes"): a message quotes ONE live message from a readable thread, the quote is a live reference
 * (the quoted message as it reads now, mentions by name, a tombstone says deleted and carries no text), it is set when the message is
 * posted and an edit cannot touch it, ONE typed `400` covers every quote that cannot be made (and writes, hints and audits nothing),
 * every read independently hides a source room the reader cannot currently access, and quote resolution is batched per page. Rooms,
 * templates and users are SHARED suite state: every test mints UNIQUE rows and removes them.
 */
class MessageQuotesTest {
    private val rooms = "/api/v1/rooms"
    private val mintedRooms = mutableListOf<UInt>()
    private val mintedUsers = mutableListOf<UInt>()
    private val mintedTemplates = mutableListOf<UInt>()
    private val minted = MintedIds(mintedUsers, mintedTemplates, mintedRooms)

    @AfterTest
    fun cleanUp() = runBlocking {
        TestRooms.purge(mintedRooms)
        mintedUsers.forEach { TestUsers.softDelete(it) }
        mintedTemplates.forEach { TestTemplates.delete(it) }
    }

    private suspend fun ApplicationTestBuilder.cast(prefix: String): ThreadCast = seedThreadCast(prefix, "Quote", minted)

    private suspend fun HttpClient.quoting(room: UInt, body: String, quoted: UInt?, number: Int = 1): HttpResponse =
        postJson("$rooms/$room/threads/$number/messages", MessagePostRequest(body, quoted))

    private suspend fun HttpClient.page(room: UInt, query: String = "", number: Int = 1): MessagePageResponse =
        get("$rooms/$room/threads/$number/messages$query").body()

    private suspend fun HttpClient.read(room: UInt, id: UInt): MessageResponse = get("$rooms/$room/messages/$id").body()

    private suspend fun HttpClient.rawJson(method: HttpMethod, path: String, json: String): HttpResponse = request(path) {
        this.method = method
        contentType(ContentType.Application.Json)
        setBody(json)
    }

    private suspend fun HttpClient.postedQuote(room: UInt, body: String, quoted: UInt?): MessageResponse {
        val response = quoting(room, body, quoted)
        assertEquals(HttpStatusCode.Created, response.status, "post quoting $quoted")
        return response.body()
    }

    private fun sql(statement: String, vararg args: Any?) = PostgresTestSupport.connect().use { c ->
        c.prepareStatement(statement).use { st ->
            args.forEachIndexed { index, arg -> st.setObject(index + 1, arg) }
            st.executeUpdate()
        }
    }

    private fun messageCount(threadId: UInt): Int = runBlocking { TestThreads.rawMessages(threadId).size }

    @Test
    fun `a quoting post answers its quote and the list, the single read and the pins list read it the same`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("mq-read")
        val quotedId = TestThreads.post(cast.room, 1, cast.author.id, "Original **words**")
        val posted = cast.member.client.postedQuote(cast.room, "Agreed", quotedId)

        assertEquals(
            MessageQuote(
                quotedId, cast.author.id, "Alina", false, "Original **words**", false, null, emptyList(), emptyList(), cast.room, 1,
            ),
            posted.quote,
        )
        assertEquals(cast.member.id, posted.authorId)
        val listed = cast.member.client.page(cast.room).items.single { it.id == posted.id }
        assertEquals(posted, listed, "the list reads what the POST answered")
        assertEquals(posted, cast.admin.client.read(cast.room, posted.id), "the single read, to an ADMIN outside the room too")
        assertEquals(posted, cast.owner.client.page(cast.room, "?id[lt]=${posted.id + 1u}").items.single { it.id == posted.id })

        val pin = cast.member.client.putJson("$rooms/${cast.room}/messages/${posted.id}/pin", PinRequest(true))
        assertEquals(HttpStatusCode.NoContent, pin.status)
        val pins = cast.owner.client.get("$rooms/${cast.room}/pins").body<PageResponse<RoomPinResponse>>()
        assertEquals(posted, pins.items.single().message, "the pins list reads it the same")

        // A message that quotes nothing answers `quote: null`, everywhere.
        val plain = cast.member.client.postedQuote(cast.room, "No quote here", null)
        assertNull(plain.quote)
        assertNull(cast.member.client.read(cast.room, plain.id).quote)
        assertNull(cast.member.client.page(cast.room).items.single { it.id == plain.id }.quote)
    }

    @Test
    fun `one's own message and the thread's opening message can be quoted, and a quote shows one level only`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("mq-own")
        val own = TestThreads.post(cast.room, 1, cast.member.id, "My own earlier words")
        val self = cast.member.client.postedQuote(cast.room, "Adding to myself", own)
        assertEquals(own, self.quote?.messageId)
        assertEquals("Marek", self.quote?.authorName)

        val opening = cast.member.client.postedQuote(cast.room, "On the opening message", cast.opener)
        assertEquals(cast.opener, opening.quote?.messageId)
        assertEquals("Opening message", opening.quote?.body)

        // Quoting a message that itself quotes shows the quoted message's own text, never what it quoted.
        val second = cast.owner.client.postedQuote(cast.room, "Quoting a quote", self.id)
        assertEquals(self.id, second.quote?.messageId)
        assertEquals("Adding to myself", second.quote?.body)
    }

    @Test
    fun `the quote follows an edit of the quoted message and a rename of its author, and shows its mentions by name`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("mq-live")
        val quotedId = TestThreads.post(cast.room, 1, cast.author.id, "Before the edit")
        val quoting = cast.member.client.postedQuote(cast.room, "Replying", quotedId)

        // An edit of the quoted message: every quote of it shows the new text, mentions (people and groups) included.
        val edited = "After the edit, <@${cast.owner.id}> and <@everyone>"
        val edit = cast.author.client.putJson("$rooms/${cast.room}/messages/$quotedId", MessageRequest(edited))
        assertEquals(HttpStatusCode.NoContent, edit.status)
        val afterEdit = cast.member.client.read(cast.room, quoting.id).quote!!
        assertEquals(edited, afterEdit.body)
        assertNull(quoting.quote!!.editedAt, "as posted")
        assertEquals(TestThreads.rawMessages(cast.threadId).single { it.id == quotedId }.editedAt, afterEdit.editedAt)
        assertNotEquals(null, afterEdit.editedAt, "an edited quoted message says so")
        assertEquals(listOf(MentionedUser(cast.owner.id, "Olivia")), afterEdit.mentions)
        assertEquals(listOf(MentionGroupKind.EVERYONE), afterEdit.groupMentions.map(GroupMention::kind))
        assertEquals(afterEdit, cast.member.client.page(cast.room).items.single { it.id == quoting.id }.quote)

        // A rename of the quoted author, and of a mentioned person, reads at once; a removed account keeps its last name.
        sql("UPDATE users SET name = ? WHERE id = ?", "Alina Renamed", cast.author.id.toLong())
        sql("UPDATE users SET name = ? WHERE id = ?", "Olivia Renamed", cast.owner.id.toLong())
        val renamed = cast.member.client.read(cast.room, quoting.id).quote!!
        assertEquals("Alina Renamed", renamed.authorName)
        assertEquals(listOf("Olivia Renamed"), renamed.mentions.map { it.name })
        TestUsers.softDelete(cast.author.id)
        assertEquals("Alina Renamed", cast.member.client.read(cast.room, quoting.id).quote!!.authorName)
        assertEquals(cast.author.id, cast.member.client.read(cast.room, quoting.id).quote!!.authorId)
    }

    @Test
    fun `a deleted quoted message reads deleted with no text - its author's delete and a moderator's - and the quoting message stays`() =
        testApplication {
            usePostgresTestcontainer()
            val cast = cast("mq-del")
            val byAuthor = TestThreads.post(cast.room, 1, cast.author.id, "Secret one <@${cast.owner.id}>")
            val byModerator = TestThreads.post(cast.room, 1, cast.author.id, "Secret two")
            val first = cast.member.client.postedQuote(cast.room, "Re one", byAuthor)
            val second = cast.member.client.postedQuote(cast.room, "Re two", byModerator)
            assertEquals(
                listOf(false to "Secret one <@${cast.owner.id}>", false to "Secret two"),
                listOf(first, second).map { m -> m.quote!!.let { it.deleted to it.body } },
            )

            val edit = cast.author.client.putJson("$rooms/${cast.room}/messages/$byAuthor", MessageRequest("Secret one, edited"))
            assertEquals(HttpStatusCode.NoContent, edit.status)
            assertNotEquals(null, cast.member.client.read(cast.room, first.id).quote!!.editedAt)
            assertEquals(HttpStatusCode.NoContent, cast.author.client.delete("$rooms/${cast.room}/messages/$byAuthor").status)
            assertEquals(HttpStatusCode.NoContent, cast.owner.client.delete("$rooms/${cast.room}/messages/$byModerator").status)
            for (quoting in listOf(first, second)) {
                val quote = cast.member.client.read(cast.room, quoting.id).quote!!
                assertEquals(
                    MessageQuote(
                        quote.messageId, cast.author.id, "Alina", true, null, false, null, emptyList(), emptyList(), cast.room, 1,
                    ),
                    quote,
                    "deleted, no text, no names",
                )
                assertEquals(quote, cast.member.client.page(cast.room).items.single { it.id == quoting.id }.quote)
                assertEquals(false, cast.member.client.read(cast.room, quoting.id).deleted, "the quoting message stays")
            }
            assertEquals("Re one", cast.member.client.read(cast.room, first.id).body)

            // A deleted QUOTING message is a plain tombstone: no quote, no body.
            assertEquals(HttpStatusCode.NoContent, cast.member.client.delete("$rooms/${cast.room}/messages/${first.id}").status)
            val tombstone = cast.member.client.page(cast.room).items.single { it.id == first.id }
            assertEquals(listOf(true, null, null), listOf(tombstone.deleted, tombstone.body, tombstone.quote))
            // The reference itself is still in place (the soft delete is an UPDATE): the proof is the quote of the other message.
            assertTrue(cast.member.client.page(cast.room).items.single { it.id == second.id }.quote!!.deleted)
        }

    @Test
    fun `another thread and another readable room can be quoted with their source location`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("mq-cross")
        val otherThread = TestThreads.seed(cast.room, cast.author.id, title = "Another thread")
        val inOtherThread = TestThreads.post(cast.room, 2, cast.author.id, "In thread two")
        val template = TestThreads.seedTemplate().also { mintedTemplates += it }
        val otherRoom = TestRooms.seed(cast.owner.id, template, "Other ${UUID.randomUUID()}").also { mintedRooms += it }
        TestRooms.addMember(otherRoom, cast.member.id)
        TestThreads.seed(otherRoom, cast.owner.id)
        val inOtherRoom = TestThreads.post(otherRoom, 1, cast.owner.id, "In another room")

        val sameRoom = cast.member.client.postedQuote(cast.room, "Cross-thread", inOtherThread).quote!!
        assertEquals(cast.room, sameRoom.roomId)
        assertEquals(otherThread.number, sameRoom.threadNumber)
        assertEquals(inOtherThread, sameRoom.messageId)
        val crossRoomPost = cast.member.client.postedQuote(cast.room, "Cross-room", inOtherRoom)
        val crossRoom = crossRoomPost.quote!!
        assertEquals(otherRoom, crossRoom.roomId)
        assertEquals(1, crossRoom.threadNumber)
        assertEquals(inOtherRoom, crossRoom.messageId)
        assertEquals(crossRoom, cast.member.client.read(cast.room, crossRoomPost.id).quote)
    }

    @Test
    fun `an unknown id, an unreadable room message and a deleted one are ONE typed 400, and nothing is written or audited`() =
        testApplication {
            usePostgresTestcontainer()
            val cast = cast("mq-refuse")
            val template = TestThreads.seedTemplate().also { mintedTemplates += it }
            val otherRoom = TestRooms.seed(cast.owner.id, template, "Other ${UUID.randomUUID()}").also { mintedRooms += it }
            TestThreads.seed(otherRoom, cast.owner.id)
            val inOtherRoom = TestThreads.post(otherRoom, 1, cast.owner.id, "In another room")
            val tombstone = TestThreads.post(cast.room, 1, cast.author.id, "About to go")
            assertEquals(HttpStatusCode.NoContent, cast.author.client.delete("$rooms/${cast.room}/messages/$tombstone").status)

            val before = messageCount(cast.threadId)
            val counter = TestThreads.raw(cast.room, 1)!!.messageCount
            val problems = mutableListOf<ProblemDetail>()
            withAuditCapture { audit ->
                val refused = listOf(
                    "unknown" to 999_999u, "unreadable room" to inOtherRoom, "tombstone" to tombstone,
                )
                for ((why, id) in refused) {
                    val response = cast.member.client.quoting(cast.room, "Refused: $why", id)
                    assertEquals(HttpStatusCode.BadRequest, response.status, why)
                    problems += response.body<ProblemDetail>()
                }
                assertEquals(emptyList(), audit.events.filter { it.message == "message.posted" }, "a refused quote writes no audit line")
            }
            for (problem in problems) {
                assertEquals(QUOTE_UNAVAILABLE_PROBLEM_TYPE, problem.type)
                assertEquals(problems.first().copy(instance = null), problem.copy(instance = null), "one answer for every cause")
                val echoed = listOf("999999", inOtherRoom.toString(), tombstone.toString())
                assertTrue(echoed.none { it in problem.detail.orEmpty() }, "the detail echoes no id")
            }
            assertEquals(before, messageCount(cast.threadId), "nothing was written")
            assertEquals(counter, TestThreads.raw(cast.room, 1)!!.messageCount)
        }

    @Test
    fun `an id outside 1 to 2147483647 is the plain 400, and the edit cannot name a quote`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("mq-range")
        val post = "$rooms/${cast.room}/threads/1/messages"
        for (id in listOf(0L, 2_147_483_648L, 4_294_967_296L, -1L)) {
            val response = cast.member.client.rawJson(HttpMethod.Post, post, """{"body":"x","quotedMessageId":$id}""")
            assertEquals(HttpStatusCode.BadRequest, response.status, "quotedMessageId $id")
            assertNotEquals(QUOTE_UNAVAILABLE_PROBLEM_TYPE, response.body<ProblemDetail>().type, "$id is no message id: the plain 400")
        }
        // An explicit null quotes nothing. Even a string containing the opener's id is invalid JSON input for the integer field.
        val none = cast.member.client.rawJson(HttpMethod.Post, post, """{"body":"x","quotedMessageId":null}""")
        assertEquals(HttpStatusCode.Created, none.status)
        for (id in listOf("${cast.opener}", "+${cast.opener}", "00${cast.opener}")) {
            val text = cast.member.client.rawJson(HttpMethod.Post, post, """{"body":"x","quotedMessageId":"$id"}""")
            assertEquals(HttpStatusCode.BadRequest, text.status)
            assertNotEquals(QUOTE_UNAVAILABLE_PROBLEM_TYPE, text.body<ProblemDetail>().type)
        }

        // The edit keeps the quote, and a PUT that names `quotedMessageId` (to add, change or remove it) is the strict decoder's 400.
        val quotedId = TestThreads.post(cast.room, 1, cast.author.id, "Quoted")
        val quoting = cast.member.client.postedQuote(cast.room, "First draft", quotedId)
        val edit = cast.member.client.putJson("$rooms/${cast.room}/messages/${quoting.id}", MessageRequest("Second draft"))
        assertEquals(HttpStatusCode.NoContent, edit.status)
        val kept = cast.member.client.read(cast.room, quoting.id)
        assertEquals(listOf("Second draft", quotedId), listOf(kept.body, kept.quote?.messageId))
        assertNotEquals(null, kept.editedAt)
        val attempts = listOf("""{"body":"Third","quotedMessageId":${cast.opener}}""", """{"body":"Third","quotedMessageId":null}""")
        for (json in attempts) {
            val refused = cast.member.client.rawJson(HttpMethod.Put, "$rooms/${cast.room}/messages/${quoting.id}", json)
            assertEquals(HttpStatusCode.BadRequest, refused.status, json)
        }
        assertEquals(kept, cast.member.client.read(cast.room, quoting.id), "the refused edits changed nothing")
    }

    @Test
    fun `the guards come first - outsider 404, admin outside the room 403, closed thread and archived room 409 - whatever the id`() =
        testApplication {
            usePostgresTestcontainer()
            val cast = cast("mq-guard")
            val real = TestThreads.post(cast.room, 1, cast.author.id, "Quotable")
            for (id in listOf(real, 999_999u, 0u)) {
                assertEquals(HttpStatusCode.NotFound, cast.outsider.client.quoting(cast.room, "x", id).status, "outsider, $id")
                assertEquals(HttpStatusCode.Forbidden, cast.admin.client.quoting(cast.room, "x", id).status, "admin outside the room, $id")
            }
            // An archived room and a closed thread are refused before the quote is looked at (their 409 beats the typed 400).
            TestThreads.closeRaw(cast.room, 1)
            val closed = cast.member.client.quoting(cast.room, "x", 999_999u)
            assertEquals(HttpStatusCode.Conflict, closed.status)
            assertEquals(THREAD_CLOSED_PROBLEM_TYPE, closed.body<ProblemDetail>().type)
            sql("UPDATE threads SET status_key = 'open', closed_at = NULL WHERE id = ?", cast.threadId.toLong())
            TestRooms.archive(cast.room)
            val archived = cast.member.client.quoting(cast.room, "x", 999_999u)
            assertEquals(HttpStatusCode.Conflict, archived.status)
            assertEquals(ROOM_ARCHIVED_PROBLEM_TYPE, archived.body<ProblemDetail>().type)
        }

    @Test
    fun `a reference to another readable thread resolves in the list, the single read and the pins`() =
        testApplication {
            usePostgresTestcontainer()
            val cast = cast("mq-plant")
            val second = TestThreads.seed(cast.room, cast.author.id, title = "Thread two")
            val inOne = TestThreads.post(cast.room, 1, cast.author.id, "Words of thread one")
            val inTwo = TestThreads.post(cast.room, 2, cast.author.id, "Words of thread two")
            // Cross-thread references resolve no matter whether the write came from the route or this fixture shortcut.
            sql("UPDATE messages SET quoted_message_id = ? WHERE id = ?", inTwo.toLong(), inOne.toLong())
            sql("UPDATE messages SET quoted_message_id = ? WHERE id = ?", inOne.toLong(), inTwo.toLong())

            assertEquals(inTwo, cast.member.client.read(cast.room, inOne).quote?.messageId)
            assertEquals(inOne, cast.member.client.read(cast.room, inTwo).quote?.messageId)
            assertEquals(inTwo, cast.member.client.page(cast.room).items.single { it.id == inOne }.quote?.messageId)
            assertEquals(inOne, cast.member.client.page(cast.room, number = 2).items.single { it.id == inTwo }.quote?.messageId)
            for (id in listOf(inOne, inTwo)) {
                val pin = cast.member.client.putJson("$rooms/${cast.room}/messages/$id/pin", PinRequest(true))
                assertEquals(HttpStatusCode.NoContent, pin.status)
            }
            val pinned = cast.owner.client.get("$rooms/${cast.room}/pins").body<PageResponse<RoomPinResponse>>()
            assertEquals(setOf(inOne, inTwo), pinned.items.mapNotNull { it.message!!.quote?.messageId }.toSet())
            assertEquals(2, second.number)
        }

    @Test
    fun `a long quoted body is cut alike on the page or read apart, and a mention token past the cut is not carried`() =
        testApplication {
            usePostgresTestcontainer()
            val cast = cast("mq-long")
            val words = "word ".repeat(300) // 1,500 characters, whitespace everywhere
            val long = TestThreads.post(cast.room, 1, cast.author.id, "$words<@${cast.owner.id}>")
            val quoting = cast.member.client.postedQuote(cast.room, "Too long", long)
            val quote = quoting.quote!!
            assertTrue(quote.truncated)
            val cut = quote.body!!
            assertTrue(cut.codePointCount(0, cut.length) <= MAX_QUOTED_BODY)
            assertTrue(cut.endsWith("word"), "cut back to a whole word: ...${cut.takeLast(10)}")
            assertEquals(emptyList(), quote.mentions, "the token is past the cut: no names travel")
            // The single read reads the quoted row apart (it is not on the page); the list with the quoted row on the page cuts alike.
            assertEquals(quote, cast.member.client.read(cast.room, quoting.id).quote)
            assertEquals(quote, cast.member.client.page(cast.room, "?pageSize=100").items.single { it.id == quoting.id }.quote)
            assertEquals(quote, cast.member.client.page(cast.room, "?pageSize=1").items.single().quote, "the quoted row is read apart")

            // A short body with a token travels whole with its names, from either source.
            val short = TestThreads.post(cast.room, 1, cast.author.id, "Short <@${cast.owner.id}>")
            val shortQuoting = cast.member.client.postedQuote(cast.room, "Short quote", short)
            assertEquals(listOf(MentionedUser(cast.owner.id, "Olivia")), shortQuoting.quote!!.mentions)
            assertEquals(shortQuoting.quote, cast.member.client.page(cast.room, "?pageSize=1").items.single().quote)
        }

    @Test
    fun `a page of quoting messages sends ONE quote read, and a page that holds its quoted messages sends none`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("mq-sql")
        val quoted = (1..5).map { TestThreads.post(cast.room, 1, cast.author.id, "Early $it") }
        repeat(30) { TestThreads.post(cast.room, 1, cast.author.id, "Filler $it") }
        val quoting = quoted.map { cast.member.client.postedQuote(cast.room, "Late for $it", it).id }
        val driver = LoggerFactory.getLogger("io.r2dbc.postgresql.QUERY") as Logger
        val saved = driver.level
        val capture = LogCapture(driver.name)
        try {
            driver.level = Level.DEBUG
            fun quoteReads() = capture.events.map { it.formattedMessage }.filter { "SUBSTRING(" in it }

            // The newest 20 hold the five quoting messages and none of the quoted ones: exactly one read for all five.
            val narrow = cast.member.client.page(cast.room, "?pageSize=20")
            assertEquals(quoting.sorted(), narrow.items.filter { it.quote != null }.map { it.id }.sorted())
            assertEquals(1, quoteReads().size, "one statement: ${quoteReads()}")
            assertTrue("1001" in quoteReads().single(), "the body is bounded in SQL: ${quoteReads().single()}")

            // The whole thread on one page: every quoted message is on it already, so the quote read is not sent.
            val before = quoteReads().size
            val wide = cast.member.client.page(cast.room, "?pageSize=100")
            assertEquals(5, wide.items.count { it.quote != null })
            assertEquals(before, quoteReads().size, "a page that holds its quoted messages sends no quote read")

            // A page without a quote sends none either.
            cast.member.client.page(cast.room, "?pageSize=5&sort=id")
            assertEquals(before, quoteReads().size)
        } finally {
            driver.level = saved
            capture.detach()
        }
    }

    @Test
    fun `a readable source reaches one bounded quote check while an unknown or deleted source stops before it`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("mq-check")
        val real = TestThreads.post(cast.room, 1, cast.author.id, "Real")
        TestThreads.seed(cast.room, cast.author.id, title = "Another thread")
        val foreign = TestThreads.post(cast.room, 2, cast.author.id, "In thread two")
        val gone = TestThreads.post(cast.room, 1, cast.author.id, "About to go")
        assertEquals(HttpStatusCode.NoContent, cast.author.client.delete("$rooms/${cast.room}/messages/$gone").status)
        val driver = LoggerFactory.getLogger("io.r2dbc.postgresql.QUERY") as Logger
        val saved = driver.level
        val capture = LogCapture(driver.name)
        try {
            driver.level = Level.DEBUG
            fun checks() = capture.events.map { it.formattedMessage }.filter { "SUBSTRING(" in it }
            cast.member.client.postedQuote(cast.room, "Quoting", real)
            assertEquals(1, checks().size, "the locked check of a readable quote is one bounded statement")
            cast.member.client.postedQuote(cast.room, "Cross-thread", foreign)
            assertEquals(2, checks().size, "a readable message of another thread uses the same locked check")
            for (id in listOf(999_999u, gone)) {
                assertEquals(HttpStatusCode.BadRequest, cast.member.client.quoting(cast.room, "Refused", id).status)
                assertEquals(2, checks().size, "an unavailable source is refused by the non-disclosing preflight")
            }
            cast.member.client.postedQuote(cast.room, "Not quoting", null)
            assertEquals(2, checks().size, "a post that quotes nothing sends no check")
        } finally {
            driver.level = saved
            capture.detach()
        }
    }

    @Test
    fun `a page whose only tokens are in an off-page quoted body adds the quote, mentions, group and team reads`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("mq-cost")
        val team = TestTeams.seedMentionable("mqteam", listOf(cast.member.id))
        try {
            val tokens = TestThreads.post(cast.room, 1, cast.author.id, "Look <@${cast.owner.id}> <@everyone> ${team.token}")
            val plain = TestThreads.post(cast.room, 1, cast.author.id, "Nothing to resolve here")
            repeat(25) { TestThreads.post(cast.room, 1, cast.author.id, "Filler $it") }
            val quotingPlain = cast.member.client.postedQuote(cast.room, "Re the plain one", plain)
            val quotingTokens = cast.member.client.postedQuote(cast.room, "Re the tokens", tokens)
            val driver = LoggerFactory.getLogger("io.r2dbc.postgresql.QUERY") as Logger
            val saved = driver.level
            val capture = LogCapture(driver.name)
            try {
                driver.level = Level.DEBUG
                fun count(fragment: String) = capture.events.count { fragment in it.formattedMessage }
                fun reads() = listOf(count("SUBSTRING("), count("message_mentions"), count("message_group_mentions"), count("FROM teams"))

                // One row per page, no `<@` of its own: the first page's quoted body brings its three reads along (four statements in all).
                val first = cast.member.client.page(cast.room, "?pageSize=1").items.single()
                assertEquals(quotingTokens.id, first.id)
                assertEquals(listOf(1, 1, 1, 1), reads(), "the quote read, the mentions read, the group-mentions read, the team lookup")
                assertEquals(listOf(MentionedUser(cast.owner.id, "Olivia")), first.quote!!.mentions)
                assertEquals(
                    listOf(MentionGroupKind.TEAM, MentionGroupKind.EVERYONE).sorted(),
                    first.quote!!.groupMentions.map { it.kind }.sorted(),
                )

                // The next page's quoted body has no token: the quote read alone.
                val second = cast.member.client.page(cast.room, "?pageSize=1&page=2").items.single()
                assertEquals(quotingPlain.id, second.id)
                assertEquals(listOf(2, 1, 1, 1), reads(), "a quoted body without a token adds only the quote read")
            } finally {
                driver.level = saved
                capture.detach()
            }
        } finally {
            TestTeams.service.delete(team.id)
        }
    }

    @Test
    fun `a cross-room quote hits the bounded source lock and can be retried after it clears`() = testApplication {
        configureApp("rooms.lockWaitMillis" to "300")
        startApplication()
        val cast = cast("mq-busy")
        val template = TestThreads.seedTemplate().also { mintedTemplates += it }
        val source = TestRooms.seed(cast.owner.id, template, "Busy quote ${UUID.randomUUID()}").also { mintedRooms += it }
        TestRooms.addMember(source, cast.member.id)
        val quoted = TestThreads.seed(source, cast.owner.id).messageId
        val before = messageCount(cast.threadId)

        TestThreads.holdingRoomLock(source) {
            val response = cast.member.client.quoting(cast.room, "Waiting for source", quoted)
            assertEquals(HttpStatusCode.TooManyRequests, response.status)
            assertEquals("1", response.headers["Retry-After"])
            assertEquals(ROOM_BUSY_PROBLEM_TYPE, response.body<ProblemDetail>().type)
        }
        assertEquals(before, messageCount(cast.threadId))
        assertEquals(quoted, cast.member.client.postedQuote(cast.room, "Retried", quoted).quote?.messageId)
    }

    @Test
    fun `access revoked while a cross-room quote waits on the source lock refuses the post`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("mq-revoke")
        val template = TestThreads.seedTemplate().also { mintedTemplates += it }
        val source = TestRooms.seed(cast.owner.id, template, "Revoked quote ${UUID.randomUUID()}").also { mintedRooms += it }
        TestRooms.addMember(source, cast.member.id)
        val quoted = TestThreads.seed(source, cast.owner.id).messageId
        val before = messageCount(cast.threadId)

        TestThreads.holdingRoomLock(source, "DELETE FROM room_members WHERE room_id = ? AND user_id = ?", source, cast.member.id) { raw ->
            coroutineScope {
                val posting = async { cast.member.client.quoting(cast.room, "Lost source access", quoted) }
                awaitLockWait("FOR NO KEY UPDATE")
                raw.commit()
                val response = posting.await()
                assertEquals(HttpStatusCode.BadRequest, response.status)
                assertEquals(QUOTE_UNAVAILABLE_PROBLEM_TYPE, response.body<ProblemDetail>().type)
            }
        }
        assertEquals(before, messageCount(cast.threadId))
    }

    @Test
    fun `a quoted message deleted while the quoting post waits for the room lock is the typed 400`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("mq-race")
        val quoted = TestThreads.post(cast.room, 1, cast.author.id, "Deleted under the post")
        val before = messageCount(cast.threadId)

        TestThreads.holdingRoomLock(
            cast.room,
            "UPDATE messages SET body = '', marked_as_deleted = TRUE, deleted_by = ? WHERE id = ?",
            cast.author.id, quoted,
        ) { raw ->
            coroutineScope {
                // The route's own checks pass (the delete is uncommitted); the post then waits for the room lock and sees the tombstone.
                val posting = async { cast.member.client.quoting(cast.room, "Racing the delete", quoted) }
                awaitLockWait("FOR NO KEY UPDATE")
                raw.commit()
                val response = posting.await()
                assertEquals(HttpStatusCode.BadRequest, response.status)
                assertEquals(QUOTE_UNAVAILABLE_PROBLEM_TYPE, response.body<ProblemDetail>().type)
            }
        }
        assertEquals(before, messageCount(cast.threadId), "the refused post wrote no message")
    }

    @Test
    fun `a reference to another readable room resolves and disappears as soon as source access is lost`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("mq-room")
        val template = TestThreads.seedTemplate().also { mintedTemplates += it }
        val otherRoom = TestRooms.seed(cast.owner.id, template, "Other ${UUID.randomUUID()}").also { mintedRooms += it }
        TestRooms.addMember(otherRoom, cast.member.id)
        TestThreads.seed(otherRoom, cast.member.id)
        val foreign = TestThreads.post(otherRoom, 1, cast.member.id, "Words of another room")
        val local = TestThreads.post(cast.room, 1, cast.member.id, "Words of this room")
        sql("UPDATE messages SET quoted_message_id = ? WHERE id = ?", foreign.toLong(), local.toLong())

        assertEquals(foreign, cast.member.client.read(cast.room, local).quote?.messageId)
        assertEquals(foreign, cast.member.client.page(cast.room).items.single { it.id == local }.quote?.messageId)
        val pin = cast.member.client.putJson("$rooms/${cast.room}/messages/$local/pin", PinRequest(true))
        assertEquals(HttpStatusCode.NoContent, pin.status)
        val pinned = cast.owner.client.get("$rooms/${cast.room}/pins").body<PageResponse<RoomPinResponse>>()
        assertEquals(foreign, pinned.items.single().message!!.quote?.messageId)

        sql("DELETE FROM room_members WHERE room_id = ? AND user_id = ?", otherRoom.toLong(), cast.member.id.toLong())
        assertNull(cast.member.client.read(cast.room, local).quote)
        assertNull(cast.member.client.page(cast.room).items.single { it.id == local }.quote)
        val hiddenPin = cast.member.client.get("$rooms/${cast.room}/pins").body<PageResponse<RoomPinResponse>>()
        assertNull(hiddenPin.items.single().message!!.quote)
    }
}
