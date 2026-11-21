package ch.nokillswit

import ch.nokillswit.threads.GroupMention
import ch.nokillswit.threads.MentionGroupKind
import ch.nokillswit.threads.MessagePageResponse
import ch.nokillswit.threads.MessageRequest
import ch.nokillswit.threads.MessageResponse
import ch.nokillswit.threads.ThreadCreateRequest
import ch.nokillswit.threads.ThreadPageResponse
import ch.nokillswit.threads.ThreadResponse
import ch.nokillswit.threads.WatchRequest
import ch.nokillswit.users.UserRole
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive

/**
 * The group-mention index and its readers over HTTP (`chat-model.md` "Group mentions (V25, V30)"): a message's `groupMentions` (the post's
 * own answer, the list, the single read, a tombstone), a team's CURRENT handle and name, a team that stops being mentionable, the index
 * kept in step by an edit and a delete, and the thread list's `mentionsMe` (a direct mention, a team the caller is on NOW, everyone, the
 * watchers while they watch NOW; an ADMIN outside the room is addressed by name only). Who is TOLD is `GroupMentionNoticesTest`. Rooms,
 * templates, users and teams are SHARED suite state: every test mints UNIQUE rows and removes them.
 */
class GroupMentionsTest {
    private val rooms = "/api/v1/rooms"
    private val minted = MintedIds(mutableListOf(), mutableListOf(), mutableListOf())
    private val teams = mutableListOf<UInt>()

    @AfterTest
    fun cleanUp() = runBlocking {
        TestRooms.purge(minted.rooms)
        minted.users.forEach { TestUsers.softDelete(it) }
        minted.templates.forEach { TestTemplates.delete(it) }
        teams.forEach { TestTeams.service.delete(it) }
    }

    private suspend fun ApplicationTestBuilder.cast(prefix: String): PairCast = seedPairCast(prefix, "Gmn", minted, withThread = true)

    private suspend fun team(prefix: String, vararg members: RoomActor) =
        TestTeams.seedMentionable(prefix, members.map { it.id }).also { teams += it.id }

    private suspend fun HttpClient.post(room: UInt, body: String, thread: Int = 1): MessageResponse {
        val response = postJson("$rooms/$room/threads/$thread/messages", MessageRequest(body))
        assertEquals(HttpStatusCode.Created, response.status)
        return response.body()
    }

    private suspend fun HttpClient.edit(room: UInt, id: UInt, body: String) =
        assertEquals(HttpStatusCode.NoContent, putJson("$rooms/$room/messages/$id", MessageRequest(body)).status)

    private suspend fun HttpClient.single(room: UInt, id: UInt): MessageResponse = get("$rooms/$room/messages/$id").body()

    private suspend fun HttpClient.numbers(room: UInt, query: String): List<Int> =
        get("$rooms/$room/threads$query").body<ThreadPageResponse>().items.map { it.number }.sorted()

    private fun teamMention(team: TestTeams.MentionableTeam) = GroupMention(MentionGroupKind.TEAM, team.id, team.handle, team.name)

    @Test
    fun `a message lists the group mentions that took effect - the post, the list and the single read agree, teams then keywords`() =
        testApplication {
            usePostgresTestcontainer()
            val cast = cast("gmn-wire")
            val a = team("wire", cast.bob)
            val b = team("wire", cast.bob)
            val posted = cast.ann.client.post(
                cast.room,
                "<@everyone> ${b.token} <@watchers> ${a.token} ${a.token} <@team:2147483000> plain `<@team:${b.id + 1000u}>`",
            )

            val expected = listOf(a, b).sortedBy { it.id }.map(::teamMention) +
                GroupMention(MentionGroupKind.WATCHERS) + GroupMention(MentionGroupKind.EVERYONE)
            assertEquals(expected, posted.groupMentions, "the post's own answer: a team once, an unknown or coded token not at all")
            assertEquals(expected, cast.owner.client.single(cast.room, posted.id).groupMentions)
            val listed = cast.owner.client.get("$rooms/${cast.room}/threads/1/messages?pageSize=100").body<MessagePageResponse>().items
            assertEquals(expected, listed.single { it.id == posted.id }.groupMentions)
            assertEquals(emptyList(), listed.first { it.id != posted.id }.groupMentions, "the opening message names nobody")
            assertEquals(emptyList(), posted.mentions, "mentions is unchanged: people only")
        }

    @Test
    fun `a team shows its current handle and name, and stops showing when it is deleted or loses its handle`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("gmn-team")
        val oncall = team("curr", cast.bob)
        val posted = cast.ann.client.post(cast.room, "paging ${oncall.token}")
        assertEquals(listOf(teamMention(oncall)), posted.groupMentions)

        PostgresTestSupport.connect().use { c ->
            c.prepareStatement("UPDATE teams SET handle = ?, name = ? WHERE id = ?").use { st ->
                st.setString(1, "renamed-${oncall.handle.takeLast(8)}")
                st.setString(2, "Renamed")
                st.setInt(3, oncall.id.toInt())
                st.executeUpdate()
            }
        }
        assertEquals(
            listOf(GroupMention(MentionGroupKind.TEAM, oncall.id, "renamed-${oncall.handle.takeLast(8)}", "Renamed")),
            cast.owner.client.single(cast.room, posted.id).groupMentions,
            "a renamed handle shows on the old message",
        )

        PostgresTestSupport.connect().use { c ->
            c.prepareStatement("UPDATE teams SET handle = NULL WHERE id = ?").use { st ->
                st.setInt(1, oncall.id.toInt())
                st.executeUpdate()
            }
        }
        assertEquals(emptyList(), cast.owner.client.single(cast.room, posted.id).groupMentions, "no handle: plain text")
        TestTeams.service.delete(oncall.id)
        assertEquals(emptyList(), cast.owner.client.single(cast.room, posted.id).groupMentions, "deleted: plain text")
    }

    @Test
    fun `an edit keeps the index in step and a delete clears it, a moderator's included`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("gmn-edit")
        val a = team("edit", cast.bob)
        val b = team("edit", cast.bob)
        val posted = cast.ann.client.post(cast.room, "${a.token} <@everyone>")
        assertEquals(listOf(a.id), TestGroupRows.teamsOf(posted.id))
        assertEquals(listOf("EVERYONE"), TestGroupRows.keywordsOf(posted.id))

        cast.ann.client.edit(cast.room, posted.id, "${b.token} <@watchers>")
        assertEquals(listOf(b.id), TestGroupRows.teamsOf(posted.id))
        assertEquals(listOf("WATCHERS"), TestGroupRows.keywordsOf(posted.id))
        assertEquals(
            listOf(teamMention(b), GroupMention(MentionGroupKind.WATCHERS)),
            cast.owner.client.single(cast.room, posted.id).groupMentions,
        )

        cast.ann.client.edit(cast.room, posted.id, "no tokens any more")
        assertEquals(emptyList(), TestGroupRows.teamsOf(posted.id))
        assertEquals(emptyList(), TestGroupRows.keywordsOf(posted.id))

        val other = cast.ann.client.post(cast.room, "${a.token} <@everyone> <@watchers>")
        assertEquals(HttpStatusCode.NoContent, cast.owner.client.delete("$rooms/${cast.room}/messages/${other.id}").status)
        assertEquals(emptyList(), TestGroupRows.teamsOf(other.id))
        assertEquals(emptyList(), TestGroupRows.keywordsOf(other.id))
        val tombstone = cast.owner.client.get("$rooms/${cast.room}/threads/1/messages?pageSize=100").body<MessagePageResponse>()
            .items.single { it.id == other.id }
        assertEquals(emptyList(), tombstone.groupMentions, "a tombstone names nobody")
    }

    @Test
    fun `mentionsMe finds the threads that address the caller - by name, through their team, everyone or the watchers they are in`() =
        testApplication {
            usePostgresTestcontainer()
            val cast = cast("gmn-me")
            val admin = seedActor("gmn-me-adm", UserRole.ADMIN).also { minted.users += it.id }
            val mine = team("me", cast.bob)
            val others = team("others", cast.ann)
            val bobId = cast.bob.id
            val author = cast.ann.client
            suspend fun thread(title: String, body: String): Int =
                author.postJson(
                    "$rooms/${cast.room}/threads",
                    ThreadCreateRequest(title, body, mapOf("severity" to JsonPrimitive("s1"))),
                ).body<ThreadResponse>().number
            val direct = thread("direct", "hi <@$bobId>")
            val viaTeam = thread("team", "hi ${mine.token}")
            val otherTeam = thread("other team", "hi ${others.token}")
            val everyone = thread("everyone", "<@everyone>")
            val watchers = thread("watchers", "<@watchers>")
            val plain = thread("plain", "nothing")
            // Bob watches `watchers` (and will be told by the reply, which names them) but not the others.
            val reply = author.post(cast.room, "<@watchers>", thread = watchers)
            val watch = "$rooms/${cast.room}/threads/$watchers/watch"
            assertEquals(HttpStatusCode.NoContent, cast.bob.client.putJson(watch, WatchRequest(true)).status)
            val bob = cast.bob.client

            assertEquals(listOf(direct, viaTeam, everyone, watchers), bob.numbers(cast.room, "?mentionsMe=true"))
            assertEquals(
                listOf(1, otherTeam, plain),
                bob.numbers(cast.room, "?mentionsMe=false"),
                "the seeded thread, a team bob is not on, nothing at all",
            )
            assertEquals(listOf(direct), bob.numbers(cast.room, "?mentionedId=$bobId"), "mentionedId stays direct only")
            assertEquals(listOf(direct, everyone, viaTeam, watchers).sorted(), bob.numbers(cast.room, "?mentionsMe=true&pageSize=100"))
            assertEquals(listOf(everyone, watchers).filter { it == watchers }, bob.numbers(cast.room, "?mentionsMe=true&watching=true"))

            // The team and the watch are read NOW: leaving the team or stopping to watch drops the thread.
            assertEquals(HttpStatusCode.NoContent, cast.bob.client.putJson(watch, WatchRequest(false)).status)
            TestTeams.service.removeMember(mine.id, bobId) {}
            assertEquals(listOf(direct, everyone), bob.numbers(cast.room, "?mentionsMe=true"))
            assertEquals(reply.groupMentions.map { it.kind }, listOf(MentionGroupKind.WATCHERS))

            // An ADMIN outside the room is addressed by name only, never through a group.
            assertEquals(emptyList(), admin.client.numbers(cast.room, "?mentionsMe=true"))
            // A strict boolean: anything else, or a repeated key, is a 400.
            assertEquals(HttpStatusCode.BadRequest, bob.get("$rooms/${cast.room}/threads?mentionsMe=maybe").status)
            assertEquals(HttpStatusCode.BadRequest, bob.get("$rooms/${cast.room}/threads?mentionsMe=true&mentionsMe=false").status)
            val hidden = cast.outsider.client.get("$rooms/${cast.room}/threads?mentionsMe=maybe")
            assertEquals(HttpStatusCode.NotFound, hidden.status, "the 404 outranks")
        }
}
