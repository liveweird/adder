package ch.nokillswit

import ch.nokillswit.threads.MessageRequest
import ch.nokillswit.threads.MessageResponse
import ch.nokillswit.threads.PinRequest
import ch.nokillswit.threads.ReactionRequest
import ch.nokillswit.threads.ThreadSearchPageResponse
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.net.URLEncoder
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/** HTTP and database integration coverage for the opt-in structured search execution. */
class StructuredSearchRoutesTest {
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

    private fun encoded(value: String): String = URLEncoder.encode(value, Charsets.UTF_8)

    private suspend fun HttpClient.structured(path: String, query: String, extra: String = ""): HttpResponse =
        get("$path?syntax=structured&q=${encoded(query)}$extra")

    private suspend fun HttpClient.hits(path: String, query: String, extra: String = ""): ThreadSearchPageResponse {
        val response = structured(path, query, extra)
        assertEquals(HttpStatusCode.OK, response.status)
        return response.body()
    }

    private suspend fun HttpClient.post(room: UInt, number: Int, body: String): MessageResponse {
        val response = postJson("$rooms/$room/threads/$number/messages", MessageRequest(body))
        assertEquals(HttpStatusCode.Created, response.status)
        return response.body()
    }

    @Test
    fun `message clauses are ANDed on one live message`() = testApplication {
        usePostgresTestcontainer()
        val cast = seedPairCast("ss-message", "Structured", minted)
        TestThreads.seed(cast.room, cast.owner.id, title = "Runbook", body = "alpha only https://old.example")
        val matching = cast.ann.client.post(cast.room, 1, "alpha beta prefixable https://example.test 🐍 zażółć")
        cast.bob.client.post(cast.room, 1, "beta only")
        val other = TestThreads.seed(cast.room, cast.owner.id, title = "Other", body = "alpha beta prefixable without link")
        TestThreads.seed(cast.room, cast.owner.id, title = "Malformed", body = "boundarymarker showww.example https://")
        setCreatedAt(matching.id, Instant.parse("2026-02-14T12:00:00Z").toEpochMilli())

        val path = "$rooms/${cast.room}/search"
        val who = "from:#${cast.ann.id}"
        val combined = cast.owner.client.hits(path, "alpha beta prefix* $who has:link has:emoji:🐍")
        assertEquals(listOf(1), combined.items.map { it.threadNumber })
        assertEquals(listOf(1), cast.owner.client.hits(path, "\"alpha beta\" -missing on:2026-02-14 $who").items.map { it.threadNumber })
        assertEquals(0, cast.owner.client.hits(path, "\"zazolc\"").total, "exact phrases preserve accents")
        assertEquals(0, cast.owner.client.hits(path, "alpha from:#${cast.bob.id} has:link").total,
            "clauses on different messages must not be combined")
        assertEquals(0, cast.owner.client.hits(path, "boundarymarker has:link").total,
            "a link needs a boundary and content after the scheme")
        assertEquals(0, cast.owner.client.hits(path, "alpha after:2026-02-14 before:2026-02-14").total)

        assertEquals(HttpStatusCode.NoContent, cast.owner.client.delete("$rooms/${cast.room}/messages/${matching.id}").status)
        assertEquals(0, cast.owner.client.hits(path, "has:emoji:🐍").total, "a tombstone is never a candidate")
        assertFalse(other.id == 0u)
    }

    @Test
    fun `pins and reactions keep title and message targets distinct`() = testApplication {
        usePostgresTestcontainer()
        val cast = seedPairCast("ss-target", "Targets", minted)
        val thread = TestThreads.seed(cast.room, cast.owner.id, title = "Pinned title token", body = "plain opener")
        val reacted = cast.ann.client.post(cast.room, 1, "x".repeat(299) + "😀 tail")
        val emoji = "👍"
        assertEquals(
            HttpStatusCode.NoContent,
            cast.bob.client.putJson("$rooms/${cast.room}/messages/${reacted.id}/reaction", ReactionRequest(emoji, true)).status,
        )
        assertEquals(
            HttpStatusCode.NoContent,
            cast.owner.client.putJson("$rooms/${cast.room}/threads/1/pin", PinRequest(true)).status,
        )

        val path = "$rooms/${cast.room}/search"
        val pinned = cast.owner.client.hits(path, "has:pin")
        assertEquals(listOf(1), pinned.items.map { it.threadNumber })
        assertTrue(pinned.items.single().messages.isEmpty(), "a whole-thread pin is a title-level result")
        assertEquals(listOf(1), cast.owner.client.hits(path, "pinned has:pin").items.map { it.threadNumber })
        val reactionHit = cast.owner.client.hits(path, "has:reaction:$emoji").items.single()
        assertTrue(reactionHit.messages.single().snippet.joinToString("") { it.text }.contains("😀"),
            "plain structured snippets truncate without splitting a supplementary code point")
        assertEquals(0, cast.owner.client.hits(path, "pinned has:reaction:$emoji").total,
            "a title must not satisfy message-specific reaction clauses")

        assertEquals(HttpStatusCode.NoContent, cast.owner.client.delete("$rooms/${cast.room}/messages/${reacted.id}").status)
        assertEquals(0, cast.owner.client.hits(path, "has:reaction:$emoji").total)
        assertEquals(HttpStatusCode.NoContent, cast.owner.client.delete("$rooms/${cast.room}/messages/${thread.messageId}").status)
        assertEquals(listOf(1), cast.owner.client.hits(path, "has:pin").items.map { it.threadNumber },
            "a whole-thread pin remains searchable after every message is a tombstone")
        assertTrue(thread.id > 0u)
    }

    @Test
    fun `references resolve only inside admitted rooms and legacy search stays unchanged`() = testApplication {
        usePostgresTestcontainer()
        val cast = seedPairCast("ss-scope", "Visible Room", minted)
        TestThreads.seed(cast.room, cast.owner.id, title = "Visible marker", body = "scope token")
        cast.ann.client.post(cast.room, 1, "participant token")
        val hidden = seedRoomCast("ss-hidden", "Hidden Room", minted)
        TestThreads.seed(hidden.room, hidden.owner.id, title = "Hidden marker", body = "scope token")

        val global = "/api/v1/search"
        val scoped = cast.ann.client.hits(
            global,
            "scope in:\"${roomName(cast.room)}\" owner:#${cast.owner.id} with:#${cast.ann.id}",
        )
        assertEquals(listOf(cast.room), scoped.items.map { it.roomId })
        assertEquals(0, cast.ann.client.hits(global, "scope in:#${hidden.room}").total)

        val roomPath = "$rooms/${cast.room}/search"
        assertEquals(HttpStatusCode.NotFound, cast.outsider.client.structured(roomPath, "\"unterminated").status,
            "room visibility must precede structured parsing")
        assertEquals(HttpStatusCode.BadRequest, cast.ann.client.get("$roomPath?syntax=future&q=scope").status)
        val legacy = cast.ann.client.get("$roomPath?q=scope").body<ThreadSearchPageResponse>()
        assertEquals(listOf(1), legacy.items.map { it.threadNumber })
    }

    @Test
    fun `named and numeric thread filters match their scoped targets`() = testApplication {
        usePostgresTestcontainer()
        val cast = seedPairCast("ss-names", "Named Room", minted)
        TestThreads.seed(cast.room, cast.owner.id, title = "New outage", body = "Outage resolved")
        cast.ann.client.post(cast.room, 1, "Documented follow-up")
        TestThreads.seed(cast.room, cast.owner.id, title = "Backup outage", body = "Ready")
        val named = cast.ann.client.hits("/api/v1/search", "owner:NAME-OWN with:Name-ann in:#${cast.room}")
        assertEquals(listOf(1), named.items.map { it.threadNumber })
        assertEquals(0, named.items.single().matchCount, "thread-only filters do not return unrelated messages")
        assertTrue(named.items.single().messages.isEmpty())

        val path = "$rooms/${cast.room}/search"
        val from = cast.ann.client.hits(path, "from:${actorEmail(cast.ann.id)} owner:Name-own")
        assertEquals(1, from.items.single().matchCount)
        assertTrue(from.items.single().messages.single().snippet.all { !it.hit })
        assertEquals(0, cast.ann.client.hits(path, "from:Name-out").total)
        assertEquals(0, cast.ann.client.hits(path, "from:Name-ann in:\"Somewhere else\" in:#${cast.room}").total)
        assertEquals(0, cast.outsider.client.hits("/api/v1/search", "in:#${cast.room}").total)

        val title = cast.ann.client.hits(path, "new outage -draft", "&pageSize=1")
        assertEquals("New outage", title.items.single().title)
        assertTrue(title.items.single().titleSnippet.any { it.hit })
        assertEquals(0, cast.ann.client.hits(path, "outage", "&page=3&pageSize=1").items.size)
        for (url in listOf(
            "$path?syntax=structured&watching=true",
            "$path?syntax=structured&q=%20",
            "/api/v1/search?syntax=structured&q=outage&timeZone=Invalid%2FZone",
            "/api/v1/search?syntax=structured&q=outage&q=duplicate",
        )) {
            assertEquals(HttpStatusCode.BadRequest, cast.ann.client.get(url).status, url)
        }
    }

    @Test
    fun `message pins and literals yield bounded matching snippets`() = testApplication {
        usePostgresTestcontainer()
        val cast = seedPairCast("ss-snippets", "Snippets", minted)
        val opener = TestThreads.seed(cast.room, cast.owner.id, title = "Outage", body = "🚀 launched")
        val long = cast.ann.client.post(cast.room, 1, "begin ".repeat(70) + "https://example.test/ 🚀 " + "end ".repeat(70))
        val tail = cast.ann.client.post(cast.room, 1, "part before the launch 🚀")
        assertEquals(
            HttpStatusCode.NoContent,
            cast.owner.client.putJson("$rooms/${cast.room}/messages/${long.id}/pin", PinRequest(true)).status,
        )
        val path = "$rooms/${cast.room}/search"
        val pins = cast.ann.client.hits(path, "has:pin").items.single()
        assertEquals(listOf(long.id), pins.messages.map { it.id })
        val link = cast.ann.client.hits(path, "has:link").items.single().messages.single()
        assertEquals(listOf("https://example.test/"), link.snippet.filter { it.hit }.map { it.text })
        assertTrue(link.snippet.first().text.startsWith("… "))
        assertTrue(link.snippet.last().text.endsWith(" …"))

        val emoji = cast.ann.client.hits(path, "has:emoji:🚀").items.single()
        assertEquals(3, emoji.matchCount)
        assertEquals(listOf(tail.id, long.id, opener.messageId), emoji.messages.map { it.id })
        assertTrue(emoji.messages.all { message -> message.snippet.any { it.hit && it.text == "🚀" } })
        assertEquals("🚀", emoji.messages.last().snippet.first().text)
        assertEquals("🚀", emoji.messages.first().snippet.last().text)
        assertEquals(1, cast.ann.client.hits(path, "-draft has:pin").items.single().matchCount)
    }

    private fun actorEmail(id: UInt): String = PostgresTestSupport.connect().use { connection ->
        connection.prepareStatement("SELECT email FROM users WHERE id = ?").use { statement ->
            statement.setLong(1, id.toLong())
            statement.executeQuery().use { result -> assertTrue(result.next()); result.getString(1) }
        }
    }

    private fun roomName(roomId: UInt): String = PostgresTestSupport.connect().use { connection ->
        connection.prepareStatement("SELECT name FROM rooms WHERE id = ?").use { statement ->
            statement.setInt(1, roomId.toInt())
            statement.executeQuery().use { result -> result.next(); result.getString(1) }
        }
    }

    private fun setCreatedAt(messageId: UInt, value: Long) {
        PostgresTestSupport.connect().use { connection ->
            connection.prepareStatement("UPDATE messages SET created_at = ? WHERE id = ?").use { statement ->
                statement.setLong(1, value)
                statement.setInt(2, messageId.toInt())
                assertEquals(1, statement.executeUpdate())
            }
        }
        assertEquals(2026, Instant.ofEpochMilli(value).atZone(ZoneOffset.UTC).year)
    }
}
