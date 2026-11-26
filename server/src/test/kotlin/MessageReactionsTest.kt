package ch.nokillswit

import ch.nokillswit.infra.notify.LiveKind
import ch.nokillswit.infra.MUTATION_ID_HEADER
import ch.nokillswit.notifications.NotificationPageResponse
import ch.nokillswit.notifications.NotificationType
import ch.nokillswit.plugins.ProblemDetail
import ch.nokillswit.rooms.ROOM_ARCHIVED_PROBLEM_TYPE
import ch.nokillswit.threads.MAX_REACTION_UNITS
import ch.nokillswit.threads.MessagePageResponse
import ch.nokillswit.threads.MessageResponse
import ch.nokillswit.threads.REACTION_LIMIT_PROBLEM_TYPE
import ch.nokillswit.threads.THREAD_CLOSED_PROBLEM_TYPE
import ch.nokillswit.threads.ReactionRequest
import ch.nokillswit.threads.ReactorPageResponse
import ch.nokillswit.threads.ReactionSummary
import ch.nokillswit.threads.reactionKey
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking

/** The emoji of code point [codePoint], as the picker would send it. */
private fun emoji(codePoint: Int): String = String(Character.toChars(codePoint))

private val THUMBS_UP = emoji(0x1F44D)
private val PARTY = emoji(0x1F389)
private val THUMBS_UP_MEDIUM_TONE = THUMBS_UP + emoji(0x1F3FD)

/**
 * The reaction PUT and what a message read carries (V20, `chat-model.md` "Reactions (V20)"): the toggle, its no-op, the order and the
 * names of the reactions, the two limits and the room lock under them, the one-emoji rule's `400` that echoes nothing, the closed and
 * archived refusals, the guard order, and the delete that clears a message's reactions. Rooms, templates and users are SHARED suite
 * state: every test mints UNIQUE rows and removes them.
 */
class MessageReactionsTest {
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

    /** The shared thread cast plus `message`, a message of thread #1 written by `author`. */
    private class Cast(base: ThreadCast, val message: UInt) : ThreadCast(base)

    /** A room owned by Olivia with thread #1 started by Alina and Marek as the third member; the ADMIN and the outsider are not members. */
    private suspend fun ApplicationTestBuilder.cast(prefix: String): Cast {
        val base = seedThreadCast(prefix, "Rx", minted, adminName = "Adam")
        return Cast(base, TestThreads.post(base.room, 1, base.author.id, "React to this"))
    }

    private fun path(room: UInt, message: UInt) = "$rooms/$room/messages/$message/reaction"

    private suspend fun HttpClient.react(
        room: UInt,
        message: UInt,
        emoji: String,
        reacted: Boolean = true,
        mutationId: String? = null,
    ): HttpResponse = request(path(room, message)) {
        method = HttpMethod.Put
        contentType(ContentType.Application.Json)
        mutationId?.let { header(MUTATION_ID_HEADER, it) }
        setBody(ReactionRequest(emoji, reacted))
    }

    private suspend fun HttpClient.rawReact(room: UInt, message: UInt, json: String): HttpResponse = request(path(room, message)) {
        method = HttpMethod.Put
        contentType(ContentType.Application.Json)
        setBody(json)
    }

    private suspend fun HttpClient.reactionsOf(room: UInt, message: UInt): List<ReactionSummary> =
        get("$rooms/$room/messages/$message").body<MessageResponse>().reactions

    private suspend fun HttpClient.reactorsOf(room: UInt, message: UInt, key: String, page: Int = 1): ReactorPageResponse =
        get("$rooms/$room/messages/$message/reactors?reactionKey=$key&page=$page&pageSize=2&sort=createdAt").body()

    private suspend fun HttpClient.reactionNotices(): NotificationPageResponse =
        get("/api/v1/notifications?type=REACTION_ADDED&pageSize=100").body()

    /** Reactions straight into the table (the limit tests start from a crowded message; the person is not the one under test). */
    private fun seedRaw(message: UInt, user: UInt, vararg emoji: String) = PostgresTestSupport.connect().use { c ->
        c.prepareStatement("INSERT INTO message_reactions (message_id, user_id, emoji, created_at) VALUES (?, ?, ?, ?)").use { st ->
            emoji.forEachIndexed { index, value ->
                st.setInt(1, message.toInt())
                st.setLong(2, user.toLong())
                st.setString(3, value)
                st.setLong(4, 1_000L + index)
                st.executeUpdate()
            }
        }
    }

    private fun rawRows(message: UInt): Int = PostgresTestSupport.connect().use { c ->
        c.prepareStatement("SELECT count(*) FROM message_reactions WHERE message_id = ?").use { st ->
            st.setInt(1, message.toInt())
            st.executeQuery().use { rs -> rs.next(); rs.getInt(1) }
        }
    }

    @Test
    fun `a member adds a reaction and removes it`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("rx-toggle")
        withAuditCapture { capture ->
            assertEquals(HttpStatusCode.NoContent, cast.member.client.react(cast.room, cast.message, THUMBS_UP).status)
            val own = cast.member.client.reactionsOf(cast.room, cast.message)
            assertEquals(listOf(ReactionSummary(THUMBS_UP, reactionKey(THUMBS_UP), 1, true, listOf("Marek"))), own)
            assertEquals(1, rawRows(cast.message))

            assertEquals(HttpStatusCode.NoContent, cast.member.client.react(cast.room, cast.message, THUMBS_UP, reacted = false).status)
            assertEquals(emptyList(), cast.member.client.reactionsOf(cast.room, cast.message))
            assertEquals(0, rawRows(cast.message))

            val lines = capture.events.filter { it.message == "message.reaction_changed" && it.hasKeyValue("roomId", cast.room.toLong()) }
            assertEquals(listOf(true, false), lines.map { line -> line.keyValuePairs.single { it.key == "reacted" }.value })
            lines.forEach {
                assertTrue(it.hasKeyValue("byUserId", cast.member.id.toLong()))
                assertTrue(it.hasKeyValue("threadId", cast.threadId.toLong()))
                assertTrue(it.hasKeyValue("messageId", cast.message.toLong()))
                assertTrue(it.keyValuePairs.none { pair -> THUMBS_UP in pair.value.toString() }, "an audit line never carries the emoji")
            }
        }
    }

    @Test
    fun `an author has one notice per reacting person while that person leaves any reaction`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("rx-notice")

        assertEquals(HttpStatusCode.NoContent, cast.member.client.react(cast.room, cast.message, THUMBS_UP).status)
        val first = cast.author.client.reactionNotices().items.single()
        assertEquals(NotificationType.REACTION_ADDED, first.type)
        assertEquals(cast.member.id, first.actorId)
        assertEquals(cast.message, first.messageId)

        assertEquals(HttpStatusCode.NoContent, cast.member.client.react(cast.room, cast.message, PARTY).status)
        assertEquals(listOf(first.id), cast.author.client.reactionNotices().items.map { it.id }, "a second emoji keeps one notice")

        assertEquals(HttpStatusCode.NoContent, cast.member.client.react(cast.room, cast.message, THUMBS_UP, reacted = false).status)
        assertEquals(1L, cast.author.client.reactionNotices().total, "the actor still leaves another reaction")

        assertEquals(HttpStatusCode.NoContent, cast.member.client.react(cast.room, cast.message, PARTY, reacted = false).status)
        assertEquals(0L, cast.author.client.reactionNotices().total, "the actor's last reaction takes the notice")

        assertEquals(HttpStatusCode.NoContent, cast.author.client.react(cast.room, cast.message, THUMBS_UP).status)
        assertEquals(0L, cast.author.client.reactionNotices().total, "reacting to one's own message is silent")
    }

    @Test
    fun `a reaction to a former room member's message succeeds without making a notice`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("rx-former")
        val removal = cast.owner.client.delete("$rooms/${cast.room}/members/${cast.author.id}")
        assertEquals(HttpStatusCode.NoContent, removal.status)
        assertEquals(HttpStatusCode.NoContent, cast.member.client.react(cast.room, cast.message, THUMBS_UP).status)
        assertEquals(THUMBS_UP, cast.member.client.reactionsOf(cast.room, cast.message).single().emoji)
        assertEquals(0L, cast.author.client.reactionNotices().total)
    }

    @Test
    fun `a stored value the rule would refuse today can still be removed`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("rx-legacy")
        val unqualifiedKeycap = "1\u20E3" // refused as a new reaction (the selector is required), stored raw as an older rule could have
        seedRaw(cast.message, cast.member.id, unqualifiedKeycap)
        assertEquals(1, rawRows(cast.message))
        val adding = cast.member.client.react(cast.room, cast.message, unqualifiedKeycap)
        assertEquals(HttpStatusCode.BadRequest, adding.status, "adding is judged")

        assertEquals(HttpStatusCode.NoContent, cast.member.client.react(cast.room, cast.message, unqualifiedKeycap, reacted = false).status)

        assertEquals(0, rawRows(cast.message), "the row is gone")
        for (value in listOf("", "a".repeat(MAX_REACTION_UNITS + 1))) {
            assertEquals(HttpStatusCode.BadRequest, cast.member.client.react(cast.room, cast.message, value, reacted = false).status)
        }
    }

    @Test
    fun `the state it already has is a 204 with no hint and no audit line`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("rx-noop")
        suspend fun add() = cast.member.client.react(cast.room, cast.message, THUMBS_UP)
        suspend fun remove() = cast.member.client.react(cast.room, cast.message, THUMBS_UP, reacted = false)
        NotifyCapture().use { hints ->
            withAuditCapture { capture ->
                val seen = hints.during {
                    assertEquals(HttpStatusCode.NoContent, remove().status)
                    assertEquals(HttpStatusCode.NoContent, add().status)
                    assertEquals(HttpStatusCode.NoContent, add().status)
                    assertEquals(HttpStatusCode.NoContent, remove().status)
                    assertEquals(HttpStatusCode.NoContent, remove().status)
                }
                assertEquals(2, seen.count { it.kind == LiveKind.MESSAGE_REACTION_CHANGED }, "one reaction hint per actual change")
                val noticeHints = seen.filter { it.kind == LiveKind.NOTIFICATION_CREATED }
                assertEquals(2, noticeHints.size, "the notice add and removal each invalidate the author's bell")
                assertTrue(noticeHints.all { it.targetUserIds == listOf(cast.author.id) })
                val changes = capture.events.filter { it.message == "message.reaction_changed" }
                assertEquals(2, changes.count { it.hasKeyValue("roomId", cast.room.toLong()) })
            }
        }
    }

    @Test
    fun `a changed reaction carries validated correlation metadata while a no-op emits no hint`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("rx-correlation")
        val mutationId = "2c6c7f12-79a4-4f1a-8dc7-e9686608b20a"
        NotifyCapture().use { hints ->
            val emitted = hints.during {
                assertEquals(
                    HttpStatusCode.NoContent,
                    cast.member.client.react(cast.room, cast.message, THUMBS_UP, mutationId = mutationId).status,
                )
            }
            val changed = emitted.single { it.kind == LiveKind.MESSAGE_REACTION_CHANGED }
            assertEquals(mutationId, changed.mutationId)
            assertEquals(cast.member.id, changed.originUserId)
            assertTrue(emitted.filter { it.kind == LiveKind.NOTIFICATION_CREATED }.all { it.mutationId == null })

            val noOp = hints.during {
                assertEquals(
                    HttpStatusCode.NoContent,
                    cast.member.client.react(cast.room, cast.message, THUMBS_UP, mutationId = mutationId).status,
                )
            }
            assertTrue(noOp.none { it.kind == LiveKind.MESSAGE_REACTION_CHANGED })
        }

        for (invalid in listOf(mutationId.uppercase(), " $mutationId", "$mutationId ", "00000000-0000-1000-8000-000000000000")) {
            assertEquals(
                HttpStatusCode.BadRequest,
                cast.member.client.react(cast.room, cast.message, PARTY, mutationId = invalid).status,
                invalid,
            )
        }
        val repeated = cast.member.client.request(path(cast.room, cast.message)) {
            method = HttpMethod.Put
            contentType(ContentType.Application.Json)
            headers.append(MUTATION_ID_HEADER, mutationId)
            headers.append(MUTATION_ID_HEADER, mutationId)
            setBody(ReactionRequest(PARTY, true))
        }
        assertEquals(HttpStatusCode.BadRequest, repeated.status)
    }

    @Test
    fun `a message lists each emoji with its count, the reader's own and the first three names`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("rx-list")
        TestRooms.addMember(cast.room, cast.admin.id)
        // The party is the first emoji to appear, then four people choose the thumbs up (owner, member, admin, author: by time).
        assertEquals(HttpStatusCode.NoContent, cast.author.client.react(cast.room, cast.message, PARTY).status)
        for (who in listOf(cast.owner, cast.member, cast.admin, cast.author)) {
            assertEquals(HttpStatusCode.NoContent, who.client.react(cast.room, cast.message, THUMBS_UP).status)
        }
        val expectedFor = { member: Boolean, author: Boolean ->
            listOf(
                ReactionSummary(PARTY, reactionKey(PARTY), 1, author, listOf("Alina")),
                ReactionSummary(THUMBS_UP, reactionKey(THUMBS_UP), 4, member || author, listOf("Olivia", "Marek", "Adam")),
            )
        }
        assertEquals(expectedFor(true, false), cast.member.client.reactionsOf(cast.room, cast.message), "the reader's own is marked")
        assertEquals(expectedFor(false, true), cast.author.client.reactionsOf(cast.room, cast.message))

        // The list carries the same, per reader; a message nobody reacted to has none.
        val page = cast.member.client.get("$rooms/${cast.room}/threads/1/messages").body<MessagePageResponse>()
        assertEquals(expectedFor(true, false), page.items.single { it.id == cast.message }.reactions)
        assertTrue(page.items.filter { it.id != cast.message }.all { it.reactions.isEmpty() }, "a message nobody reacted to has none")
    }

    @Test
    fun `a member pages through every reactor without putting the emoji in the URL`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("rx-roster")
        TestRooms.addMember(cast.room, cast.admin.id)
        for (who in listOf(cast.owner, cast.member, cast.admin, cast.author)) {
            assertEquals(HttpStatusCode.NoContent, who.client.react(cast.room, cast.message, THUMBS_UP).status)
        }
        val summary = cast.member.client.reactionsOf(cast.room, cast.message).single()
        assertEquals(64, summary.key.length)
        assertTrue(summary.key.all { it in '0'..'9' || it in 'a'..'f' })

        val first = cast.member.client.reactorsOf(cast.room, cast.message, summary.key)
        assertEquals(4L, first.total)
        assertEquals(listOf(cast.owner.id, cast.member.id), first.items.map { it.userId })
        assertEquals(listOf("Olivia", "Marek"), first.items.map { it.name })
        val second = cast.member.client.reactorsOf(cast.room, cast.message, summary.key, page = 2)
        assertEquals(listOf(cast.admin.id, cast.author.id), second.items.map { it.userId })

        suspend fun reactorStatus(client: HttpClient, key: String) = client
            .get("$rooms/${cast.room}/messages/${cast.message}/reactors?reactionKey=$key")
            .status
        assertEquals(HttpStatusCode.NotFound, reactorStatus(cast.member.client, "0".repeat(64)))
        assertEquals(HttpStatusCode.BadRequest, reactorStatus(cast.member.client, "bad"))
        assertEquals(HttpStatusCode.NotFound, reactorStatus(cast.outsider.client, summary.key))
    }

    @Test
    fun `an emoji is compared by bytes - a thumbs up and the same with a skin tone are two reactions`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("rx-bytes")
        assertEquals(HttpStatusCode.NoContent, cast.member.client.react(cast.room, cast.message, THUMBS_UP).status)
        assertEquals(HttpStatusCode.NoContent, cast.member.client.react(cast.room, cast.message, THUMBS_UP_MEDIUM_TONE).status)
        assertEquals(listOf(THUMBS_UP, THUMBS_UP_MEDIUM_TONE), cast.member.client.reactionsOf(cast.room, cast.message).map { it.emoji })
        assertEquals(HttpStatusCode.NoContent, cast.member.client.react(cast.room, cast.message, THUMBS_UP, reacted = false).status)
        assertEquals(listOf(THUMBS_UP_MEDIUM_TONE), cast.member.client.reactionsOf(cast.room, cast.message).map { it.emoji })
    }

    @Test
    fun `the 21st distinct emoji is refused while an emoji already there takes one more person`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("rx-twenty")
        val twenty = (0 until 20).map { emoji(0x1F600 + it) }
        seedRaw(cast.message, cast.owner.id, *twenty.toTypedArray())

        val refused = cast.member.client.react(cast.room, cast.message, emoji(0x1F600 + 20))
        assertEquals(HttpStatusCode.Conflict, refused.status)
        assertEquals(REACTION_LIMIT_PROBLEM_TYPE, refused.body<ProblemDetail>().type)
        assertEquals(20, cast.member.client.reactionsOf(cast.room, cast.message).size)

        assertEquals(HttpStatusCode.NoContent, cast.member.client.react(cast.room, cast.message, twenty.first()).status)
        val after = cast.member.client.reactionsOf(cast.room, cast.message)
        assertEquals(20, after.size)
        assertEquals(2, after.single { it.emoji == twenty.first() }.count)
        // Removing is never refused; the owner's row still keeps that emoji on the message, so the 21st is still refused.
        assertEquals(HttpStatusCode.NoContent, cast.member.client.react(cast.room, cast.message, twenty.first(), reacted = false).status)
        assertEquals(HttpStatusCode.Conflict, cast.member.client.react(cast.room, cast.message, emoji(0x1F600 + 20)).status)
    }

    @Test
    fun `a member's sixth emoji on one message is refused`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("rx-five")
        val six = (0 until 6).map { emoji(0x1F600 + it) }
        seedRaw(cast.message, cast.owner.id, six[5]) // the sixth is already on the message, held by somebody else
        for (value in six.take(5)) {
            assertEquals(HttpStatusCode.NoContent, cast.member.client.react(cast.room, cast.message, value).status)
        }
        val refused = cast.member.client.react(cast.room, cast.message, six[5])
        assertEquals(HttpStatusCode.Conflict, refused.status)
        assertEquals(REACTION_LIMIT_PROBLEM_TYPE, refused.body<ProblemDetail>().type, "joining an emoji already there is no way round")
        assertEquals(6, rawRows(cast.message), "the owner's row and the member's five")
        // Removing is never refused; the freed place takes the sixth.
        assertEquals(HttpStatusCode.NoContent, cast.member.client.react(cast.room, cast.message, six[0], reacted = false).status)
        assertEquals(HttpStatusCode.NoContent, cast.member.client.react(cast.room, cast.message, six[5]).status)
        // The limit is per person: somebody else still adds.
        assertEquals(HttpStatusCode.NoContent, cast.author.client.react(cast.room, cast.message, six[0]).status)
    }

    @Test
    fun `a letter, two emoji, a bare heart and an emoji with a combining mark are 400, and the answer echoes nothing`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("rx-bad")
        val secret = "hunter2-${UUID.randomUUID()}"
        val bad = listOf(secret, "a", "5", THUMBS_UP + THUMBS_UP, emoji(0x2764), THUMBS_UP + "́", THUMBS_UP + "‮", "")
        for (value in bad) {
            val response = cast.member.client.react(cast.room, cast.message, value)
            assertEquals(HttpStatusCode.BadRequest, response.status, "a ${value.length}-unit value")
            val problem = response.body<ProblemDetail>()
            assertEquals("emoji must be a single emoji", problem.detail)
            // A one-character value is a substring of any text; the secret and the emoji-bearing values are the ones that could be echoed.
            val echoable = value.length > 1 || value.any { it.code > 127 }
            assertFalse(echoable && value in problem.toString(), "the answer echoes the value")
        }
        val malformed = listOf(
            "{ not json", "{}", """{"emoji":"$THUMBS_UP"}""", """{"emoji":5,"reacted":true}""",
            """{"emoji":"$THUMBS_UP","reacted":"yes"}""",
        )
        for (json in malformed) {
            assertEquals(HttpStatusCode.BadRequest, cast.member.client.rawReact(cast.room, cast.message, json).status, json)
        }
        assertEquals(0, rawRows(cast.message), "a refused request writes nothing")
        // Authorisation answers before the body: an outsider's bad emoji is 404, an ADMIN outside the room's is 403.
        assertEquals(HttpStatusCode.NotFound, cast.outsider.client.react(cast.room, cast.message, "a").status)
        assertEquals(HttpStatusCode.Forbidden, cast.admin.client.react(cast.room, cast.message, "a").status)
    }

    @Test
    fun `a closed thread and an archived room refuse`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("rx-closed")
        assertEquals(HttpStatusCode.NoContent, cast.member.client.react(cast.room, cast.message, THUMBS_UP).status)
        TestThreads.closeRaw(cast.room, 1)

        suspend fun assertRefused(type: String) {
            for (response in listOf(
                cast.member.client.react(cast.room, cast.message, PARTY),
                cast.member.client.react(cast.room, cast.message, THUMBS_UP, reacted = false),
            )) {
                assertEquals(HttpStatusCode.Conflict, response.status)
                assertEquals(type, response.body<ProblemDetail>().type)
            }
            assertEquals(listOf(THUMBS_UP), cast.member.client.reactionsOf(cast.room, cast.message).map { it.emoji }, "reads still work")
        }
        assertRefused(THREAD_CLOSED_PROBLEM_TYPE)
        TestRooms.archive(cast.room)
        assertRefused(ROOM_ARCHIVED_PROBLEM_TYPE)
    }

    @Test
    fun `an administrator outside the room is 403, an outsider 404, a deleted message 404`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("rx-authz")
        val foreignRoom = TestRooms.seed(cast.owner.id, mintedTemplates.first(), "Rx other ${UUID.randomUUID().toString().substring(0, 8)}")
            .also { mintedRooms += it }
        TestRooms.addMember(foreignRoom, cast.member.id)
        TestThreads.seed(foreignRoom, cast.owner.id)
        val foreign = TestThreads.post(foreignRoom, 1, cast.owner.id, "Another room")
        val deleted = TestThreads.post(cast.room, 1, cast.author.id, "Gone soon")
        assertEquals(HttpStatusCode.NoContent, cast.author.client.delete("$rooms/${cast.room}/messages/$deleted").status)

        withAuditCapture { capture ->
            assertEquals(HttpStatusCode.Forbidden, cast.admin.client.react(cast.room, cast.message, THUMBS_UP).status)
            assertEquals(HttpStatusCode.NotFound, cast.outsider.client.react(cast.room, cast.message, THUMBS_UP).status)
            assertTrue(
                capture.events.any {
                    it.message == "room.access_denied" && it.hasKeyValue("roomId", cast.room.toLong()) &&
                        it.hasKeyValue("byUserId", cast.outsider.id.toLong())
                },
                "a non-member's probe is audited",
            )
            assertTrue(capture.awaitEvent { it.message == "authz.denied" && it.hasKeyValue("path", path(cast.room, cast.message)) } != null)
            assertTrue(capture.events.none { it.message == "message.reaction_changed" })
        }
        assertEquals(HttpStatusCode.NotFound, cast.member.client.react(cast.room, deleted, THUMBS_UP).status, "a deleted message")
        assertEquals(HttpStatusCode.NotFound, cast.member.client.react(cast.room, foreign, THUMBS_UP).status, "a message of another room")
        assertEquals(HttpStatusCode.NotFound, cast.member.client.react(cast.room, 2_000_000_000u, THUMBS_UP).status, "an unknown message")
        assertEquals(HttpStatusCode.NotFound, cast.member.client.react(999_999u, cast.message, THUMBS_UP).status, "an unknown room")
        assertEquals(0, rawRows(cast.message) + rawRows(foreign))
    }

    @Test
    fun `a deleted message loses its reactions`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("rx-delete")
        for (who in listOf(cast.author, cast.member, cast.owner)) {
            assertEquals(HttpStatusCode.NoContent, who.client.react(cast.room, cast.message, THUMBS_UP).status)
        }
        assertEquals(3, rawRows(cast.message))

        val removal = cast.owner.client.delete("$rooms/${cast.room}/messages/${cast.message}")
        assertEquals(HttpStatusCode.NoContent, removal.status, "a moderator's delete")
        assertEquals(0, rawRows(cast.message), "the delete's own transaction cleared them")
        val tombstone = cast.member.client.get("$rooms/${cast.room}/threads/1/messages").body<MessagePageResponse>()
            .items.single { it.id == cast.message }
        assertTrue(tombstone.deleted)
        assertEquals(emptyList(), tombstone.reactions)
    }

    @Test
    fun `two members reacting at once never pass the limit`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("rx-race")
        seedRaw(cast.message, cast.owner.id, *(0 until 19).map { emoji(0x1F600 + it) }.toTypedArray())
        val twentieth = emoji(0x1F600 + 19)
        val twentyFirst = emoji(0x1F600 + 20)

        // Two people each add a NEW emoji while the room lock is held: exactly one fits under the 20.
        TestThreads.holdingRoomLock(cast.room) { raw ->
            coroutineScope {
                val first = async { cast.member.client.react(cast.room, cast.message, twentieth) }
                val second = async { cast.author.client.react(cast.room, cast.message, twentyFirst) }
                awaitLockWait("FOR NO KEY UPDATE", waiters = 2)
                raw.commit()
                val statuses = listOf(first.await().status, second.await().status)
                assertEquals(listOf(HttpStatusCode.NoContent, HttpStatusCode.Conflict), statuses.sortedBy { it.value })
            }
        }
        assertEquals(20, cast.member.client.reactionsOf(cast.room, cast.message).size)

        // One person adds two NEW emoji at once with four of their own already on it: exactly one fits under the 5.
        val other = cast("rx-race5")
        seedRaw(other.message, other.member.id, *(0 until 4).map { emoji(0x1F600 + it) }.toTypedArray())
        TestThreads.holdingRoomLock(other.room) { raw ->
            coroutineScope {
                val first = async { other.member.client.react(other.room, other.message, emoji(0x1F610)) }
                val second = async { other.member.client.react(other.room, other.message, emoji(0x1F611)) }
                awaitLockWait("FOR NO KEY UPDATE", waiters = 2)
                raw.commit()
                val statuses = listOf(first.await().status, second.await().status)
                assertEquals(listOf(HttpStatusCode.NoContent, HttpStatusCode.Conflict), statuses.sortedBy { it.value })
            }
        }
        assertEquals(5, other.member.client.reactionsOf(other.room, other.message).count { it.reacted })
    }
}
