package ch.nokillswit

import ch.nokillswit.threads.MentionedUser
import ch.nokillswit.threads.MessagePageResponse
import ch.nokillswit.threads.MessageRequest
import ch.nokillswit.threads.MessageResponse
import ch.nokillswit.threads.ThreadCreateRequest
import ch.nokillswit.threads.ThreadPageResponse
import ch.nokillswit.threads.ThreadResponse
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.net.URLEncoder
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive

/**
 * The mention index over HTTP (`chat-model.md` "Mentions (V17)"): a mention belongs to a MESSAGE, so a thread is mentioned while one of
 * its LIVE messages mentions the person. A post, a thread's opening message, an edit and a delete (a moderator's too) each keep
 * `message_mentions` in step in the message's own transaction; the message's `mentions` and the thread list's `mentionedUserId` read
 * it. The parser is `MentionsTest`. Rooms, templates and users are SHARED suite state: every test mints UNIQUE rows and removes them.
 */
class MessageMentionsTest {
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

    /** A room owned by `owner` with `ann` and `bob` in it; `outsider` is no member. */
    private class Cast(val owner: RoomActor, val ann: RoomActor, val bob: RoomActor, val outsider: RoomActor, val room: UInt)

    private suspend fun ApplicationTestBuilder.cast(prefix: String): Cast {
        suspend fun actor(suffix: String) = seedActor("$prefix-$suffix", name = "Name-$suffix").also { mintedUsers += it.id }
        val owner = actor("own")
        val ann = actor("ann")
        val bob = actor("bob")
        val outsider = actor("out")
        val template = TestThreads.seedTemplate().also { mintedTemplates += it }
        val room = TestRooms.seed(owner.id, template, "Men ${UUID.randomUUID().toString().substring(0, 8)}").also { mintedRooms += it }
        TestRooms.addMember(room, ann.id)
        TestRooms.addMember(room, bob.id)
        return Cast(owner, ann, bob, outsider, room)
    }

    private fun encoded(value: String): String = URLEncoder.encode(value, Charsets.UTF_8)

    private fun token(vararg actors: RoomActor): String = actors.joinToString(" ") { "<@${it.id}>" }

    private fun user(actor: RoomActor, name: String) = MentionedUser(actor.id, name)

    private fun mentioning(vararg actors: RoomActor): String = actors.joinToString("&", prefix = "?") { "mentionedUserId=${it.id}" }

    private suspend fun HttpClient.post(room: UInt, number: Int, body: String): MessageResponse {
        val response = postJson("$rooms/$room/threads/$number/messages", MessageRequest(body))
        assertEquals(HttpStatusCode.Created, response.status)
        return response.body()
    }

    private suspend fun HttpClient.edit(room: UInt, id: UInt, body: String) {
        assertEquals(HttpStatusCode.NoContent, putJson("$rooms/$room/messages/$id", MessageRequest(body)).status)
    }

    private suspend fun HttpClient.listed(room: UInt, number: Int): List<MessageResponse> =
        get("$rooms/$room/threads/$number/messages?pageSize=100").body<MessagePageResponse>().items

    private suspend fun HttpClient.single(room: UInt, id: UInt): MessageResponse = get("$rooms/$room/messages/$id").body()

    private suspend fun HttpClient.threadNumbers(room: UInt, query: String): List<Int> =
        get("$rooms/$room/threads$query").body<ThreadPageResponse>().items.map { it.number }.sorted()

    private fun mentionedIds(messageId: UInt): List<UInt> = PostgresTestSupport.connect().use { c ->
        c.prepareStatement("SELECT user_id FROM message_mentions WHERE message_id = ? ORDER BY user_id").use { st ->
            st.setInt(1, messageId.toInt())
            st.executeQuery().use { rs -> generateSequence { if (rs.next()) rs.getLong(1).toUInt() else null }.toList() }
        }
    }

    private fun rowVersions(messageId: UInt): String = PostgresTestSupport.connect().use { c ->
        val sql = "SELECT coalesce(string_agg(xmin::text, ',' ORDER BY user_id), '') FROM message_mentions WHERE message_id = ?"
        c.prepareStatement(sql).use { st ->
            st.setInt(1, messageId.toInt())
            st.executeQuery().use { rs -> rs.next(); rs.getString(1) }
        }
    }

    private fun rename(id: UInt, name: String) = PostgresTestSupport.connect().use { c ->
        c.prepareStatement("UPDATE users SET \"name\" = ? WHERE id = ?").use { st ->
            st.setString(1, name)
            st.setLong(2, id.toLong())
            st.executeUpdate()
        }
    }

    @Test
    fun `a posted message lists the members it mentions, by their current names`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("mn-post")
        TestThreads.seed(cast.room, cast.ann.id)
        val expected = listOf(user(cast.ann, "Name-ann"), user(cast.bob, "Name-bob"))
        // The body names bob first, the answer is ordered by id.
        val posted = cast.ann.client.post(cast.room, 1, "hello ${token(cast.bob, cast.ann)} and ${token(cast.bob)} again")
        assertEquals(expected.sortedBy { it.userId }, posted.mentions, "the post's own answer")
        assertEquals(listOf(cast.ann.id, cast.bob.id).sorted(), mentionedIds(posted.id), "one row per person")
        assertEquals(posted.mentions, cast.owner.client.single(cast.room, posted.id).mentions, "the single read")
        val listed = cast.owner.client.listed(cast.room, 1).single { it.id == posted.id }
        assertEquals(posted.mentions, listed.mentions, "the list")
        assertEquals(emptyList(), cast.owner.client.listed(cast.room, 1).first { it.id != posted.id }.mentions, "the opening message")
        assertEquals(emptyList(), cast.ann.client.post(cast.room, 1, "no token here").mentions)
        assertEquals(emptyList(), cast.ann.client.post(cast.room, 1, "code `${token(cast.bob)}` is not read").mentions)
    }

    @Test
    fun `a thread's opening message is indexed too`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("mn-open")
        val response = cast.ann.client.postJson(
            "$rooms/${cast.room}/threads",
            ThreadCreateRequest("Opening", "Start with ${token(cast.bob)}", mapOf("severity" to JsonPrimitive("s1"))),
        )
        assertEquals(HttpStatusCode.Created, response.status)
        val thread = response.body<ThreadResponse>()
        val opener = TestThreads.rawMessages(thread.id).single()
        assertEquals(listOf(cast.bob.id), mentionedIds(opener.id))
        assertEquals(listOf(user(cast.bob, "Name-bob")), cast.owner.client.listed(cast.room, thread.number).single().mentions)
        assertEquals(listOf(thread.number), cast.owner.client.threadNumbers(cast.room, mentioning(cast.bob)))
    }

    @Test
    fun `an id that is not an active member of the room is not a mention`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("mn-not")
        val gone = seedActor("mn-not-gone", name = "Name-gone").also { mintedUsers += it.id }
        TestRooms.addMember(cast.room, gone.id)
        TestUsers.softDelete(gone.id)
        TestThreads.seed(cast.room, cast.ann.id)
        val unknown = Int.MAX_VALUE
        val body = "outsider ${token(cast.outsider)} unknown <@$unknown> deleted ${token(gone)} " +
            "member ${token(cast.bob)} oneself ${token(cast.ann)}"
        val posted = cast.ann.client.post(cast.room, 1, body)
        val members = listOf(cast.ann.id, cast.bob.id).sorted()
        assertEquals(members, mentionedIds(posted.id), "no row for an outsider, an unknown id or a deleted user")
        assertEquals(members, posted.mentions.map { it.userId })
        assertEquals(body, cast.owner.client.single(cast.room, posted.id).body, "the body is stored as sent")
        assertEquals(emptyList(), cast.owner.client.threadNumbers(cast.room, mentioning(cast.outsider)))
    }

    @Test
    fun `an edit adds and drops mentions, and an unchanged edit writes nothing`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("mn-edit")
        TestThreads.seed(cast.room, cast.ann.id)
        val message = cast.ann.client.post(cast.room, 1, "first ${token(cast.bob)}")
        assertEquals(listOf(cast.bob.id), mentionedIds(message.id))
        val before = rowVersions(message.id)

        // Other words, the same people: no mention row is rewritten.
        cast.ann.client.edit(cast.room, message.id, "reworded ${token(cast.bob)}")
        assertEquals(before, rowVersions(message.id), "an edit that keeps the set rewrites no row")
        // The very same body again is a no-op (204, no write at all).
        cast.ann.client.edit(cast.room, message.id, "reworded ${token(cast.bob)}")
        assertEquals(before, rowVersions(message.id))

        // A changed set: one person added, one dropped.
        cast.ann.client.edit(cast.room, message.id, "now ${token(cast.owner)} and ${token(cast.ann)}")
        assertEquals(listOf(cast.owner.id, cast.ann.id).sorted(), mentionedIds(message.id))
        assertEquals(emptyList(), cast.owner.client.threadNumbers(cast.room, mentioning(cast.bob)))
        val now = listOf(user(cast.owner, "Name-own"), user(cast.ann, "Name-ann")).sortedBy { it.userId }
        assertEquals(now, cast.owner.client.single(cast.room, message.id).mentions)
        // A person outside the room never gets a row, an edit included.
        cast.ann.client.edit(cast.room, message.id, "now ${token(cast.outsider, cast.owner)}")
        assertEquals(listOf(cast.owner.id), mentionedIds(message.id))
        cast.ann.client.edit(cast.room, message.id, "no mentions any more")
        assertEquals(emptyList(), mentionedIds(message.id))
        assertEquals(emptyList(), cast.owner.client.single(cast.room, message.id).mentions)
    }

    @Test
    fun `an edit keeps the mention of a member who left while the token stays`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("mn-keep")
        TestThreads.seed(cast.room, cast.ann.id)
        val message = cast.ann.client.post(cast.room, 1, "first ${token(cast.bob)}")
        assertEquals(HttpStatusCode.NoContent, cast.owner.client.delete("$rooms/${cast.room}/members/${cast.bob.id}").status)
        cast.ann.client.edit(cast.room, message.id, "reworded ${token(cast.bob)}")
        assertEquals(listOf(cast.bob.id), mentionedIds(message.id), "the unchanged mention stays")
        cast.ann.client.edit(cast.room, message.id, "now ${token(cast.owner)}")
        assertEquals(listOf(cast.owner.id), mentionedIds(message.id), "dropping the token drops the row")
        // A new mention of the person who left is no mention.
        cast.ann.client.edit(cast.room, message.id, "again ${token(cast.owner, cast.bob)}")
        assertEquals(listOf(cast.owner.id), mentionedIds(message.id))
    }

    @Test
    fun `a deleted message loses its mentions, a moderator's delete included`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("mn-del")
        TestThreads.seed(cast.room, cast.ann.id)
        val own = cast.ann.client.post(cast.room, 1, "mine ${token(cast.bob)}")
        val other = cast.ann.client.post(cast.room, 1, "theirs ${token(cast.bob, cast.owner)}")
        assertEquals(listOf(1, 2), listOf(mentionedIds(own.id).size, mentionedIds(other.id).size))
        assertEquals(listOf(1), cast.owner.client.threadNumbers(cast.room, mentioning(cast.bob)))

        assertEquals(HttpStatusCode.NoContent, cast.ann.client.delete("$rooms/${cast.room}/messages/${own.id}").status)
        assertEquals(emptyList(), mentionedIds(own.id), "the author's delete")
        assertEquals(listOf(1), cast.owner.client.threadNumbers(cast.room, mentioning(cast.bob)), "the other message still counts")
        assertEquals(HttpStatusCode.NoContent, cast.owner.client.delete("$rooms/${cast.room}/messages/${other.id}").status)
        assertEquals(emptyList(), mentionedIds(other.id), "the room OWNER's delete")
        assertEquals(emptyList(), cast.owner.client.threadNumbers(cast.room, mentioning(cast.bob, cast.owner)))
        val tombstones = cast.owner.client.listed(cast.room, 1).filter { it.deleted }
        assertEquals(listOf(own.id, other.id), tombstones.map { it.id }.sorted())
        assertEquals(listOf(emptyList(), emptyList()), tombstones.map { it.mentions }, "a tombstone mentions nobody")
    }

    @Test
    fun `a renamed user shows the new name, and a member who left keeps theirs`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("mn-name")
        TestThreads.seed(cast.room, cast.ann.id)
        val message = cast.ann.client.post(cast.room, 1, "hi ${token(cast.bob, cast.owner)}")
        rename(cast.bob.id, "Robert Renamed")
        assertEquals(
            listOf(user(cast.owner, "Name-own"), user(cast.bob, "Robert Renamed")).sortedBy { it.userId },
            cast.ann.client.single(cast.room, message.id).mentions,
        )
        assertEquals(HttpStatusCode.NoContent, cast.owner.client.delete("$rooms/${cast.room}/members/${cast.bob.id}").status)
        TestUsers.softDelete(cast.bob.id)
        val after = cast.owner.client.listed(cast.room, 1).single { it.id == message.id }.mentions
        val names = after.map { it.name }.sorted()
        assertEquals(listOf("Name-own", "Robert Renamed"), names, "a person who left, then was deleted, keeps the name")
    }

    @Test
    fun `the filter finds a thread through any live message, any of the ids`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("mn-find")
        repeat(4) { TestThreads.seed(cast.room, cast.owner.id) }
        cast.ann.client.post(cast.room, 1, "for ${token(cast.bob)}")
        cast.ann.client.post(cast.room, 2, "for ${token(cast.owner)} and ${token(cast.bob)}")
        val gone = cast.ann.client.post(cast.room, 3, "for ${token(cast.bob)}")
        cast.ann.client.delete("$rooms/${cast.room}/messages/${gone.id}")
        cast.ann.client.post(cast.room, 4, "code only `${token(cast.bob)}`")
        TestThreads.closeRaw(cast.room, 2)

        val owner = cast.owner.client
        assertEquals(listOf(1, 2), owner.threadNumbers(cast.room, mentioning(cast.bob)), "a deleted message and code mention nobody")
        assertEquals(listOf(2), owner.threadNumbers(cast.room, "?mentionedUserId=${cast.owner.id}"), "a closed thread is found too")
        assertEquals(listOf(1, 2), owner.threadNumbers(cast.room, mentioning(cast.owner, cast.bob)), "any-of")
        assertEquals(listOf(2), owner.threadNumbers(cast.room, mentioning(cast.bob) + "&closed=true"), "combined with another filter")
        assertEquals(emptyList(), owner.threadNumbers(cast.room, "?mentionedUserId=${cast.ann.id}"), "nobody mentioned ann")
        assertEquals(emptyList(), owner.threadNumbers(cast.room, "?mentionedUserId=2147483647"), "a well-formed id nobody was mentioned as")
        assertEquals(2L, owner.get("$rooms/${cast.room}/threads?mentionedUserId=${cast.bob.id}").body<ThreadPageResponse>().total)
        assertEquals(listOf(1, 2), cast.bob.client.threadNumbers(cast.room, "?mentionedUserId=${cast.bob.id}"), "any member asks it")
    }

    @Test
    fun `a malformed id or a 21st value is 400, and a non-member is 404 whatever the filter`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("mn-400")
        TestThreads.seed(cast.room, cast.owner.id)
        val owner = cast.owner.client
        for (bad in listOf("0", "-1", "abc", "2147483648", "1.5", "+5", "٣", "1٣", "x' OR 1=1--")) {
            assertEquals(HttpStatusCode.BadRequest, owner.get("$rooms/${cast.room}/threads?mentionedUserId=${encoded(bad)}").status, bad)
        }
        val twenty = (1..20).joinToString("&") { "mentionedUserId=$it" }
        assertEquals(HttpStatusCode.OK, owner.get("$rooms/${cast.room}/threads?$twenty").status, "20 ids")
        assertEquals(HttpStatusCode.BadRequest, owner.get("$rooms/${cast.room}/threads?$twenty&mentionedUserId=21").status, "21 ids")
        assertEquals(
            HttpStatusCode.OK,
            owner.get("$rooms/${cast.room}/threads?$twenty&mentionedUserId=20&mentionedUserId=1").status,
            "a repeated id collapses before the bound",
        )

        val outsider = cast.outsider.client
        val queries = listOf("mentionedUserId=${cast.owner.id}", "mentionedUserId=abc", "mentionedUserId=0", "$twenty&mentionedUserId=21")
        for (query in queries) {
            assertEquals(HttpStatusCode.NotFound, outsider.get("$rooms/${cast.room}/threads?$query").status, query)
        }
    }
}
