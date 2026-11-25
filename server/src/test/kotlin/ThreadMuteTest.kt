package ch.nokillswit

import ch.nokillswit.threads.MessageRequest
import ch.nokillswit.threads.WatchRequest
import ch.nokillswit.users.UserRole
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ThreadMuteTest {
    private val minted = MintedIds(mutableListOf(), mutableListOf(), mutableListOf())

    @AfterTest
    fun cleanUp() = runBlocking {
        TestRooms.purge(minted.rooms)
        minted.users.forEach { TestUsers.softDelete(it) }
        minted.templates.forEach { TestTemplates.delete(it) }
    }

    @Test
    fun `muting suppresses new mentions and watcher notices until unmuted`() = testApplication {
        usePostgresTestcontainer()
        val cast = seedPairCast("mute-alerts", "Mute chat", minted, withThread = true)
        val thread = "/api/v1/rooms/${cast.room}/threads/1"
        val member = cast.ann.client
        assertEquals(HttpStatusCode.NoContent, member.putJson("$thread/watch", WatchRequest(true)).status)
        val first = cast.owner.client.postJson("$thread/messages", MessageRequest("<@${cast.ann.id}> first"))
        assertEquals(HttpStatusCode.Created, first.status)
        assertEquals(HttpStatusCode.NoContent, member.putJson("$thread/mute", mapOf("muted" to true)).status)
        assertEquals(HttpStatusCode.NoContent, member.putJson("$thread/mute", mapOf("muted" to true)).status)
        val state = member.get(thread).body<JsonObject>()
        assertTrue(state.getValue("muted").jsonPrimitive.boolean)
        assertTrue(state.getValue("watching").jsonPrimitive.boolean)

        val feed = "/api/v1/notifications/feed?type=MENTIONED&type=THREAD_REPLIED"
        val quiet = cast.owner.client.postJson("$thread/messages", MessageRequest("<@${cast.ann.id}> quiet"))
        assertEquals(HttpStatusCode.Created, quiet.status)
        assertEquals(HttpStatusCode.Created, cast.owner.client.postJson("$thread/messages", MessageRequest("quiet reply")).status)
        assertEquals(1, member.get(feed).body<JsonObject>().getValue("total").jsonPrimitive.int)
        assertEquals(HttpStatusCode.NoContent, member.putJson("$thread/mute", mapOf("muted" to false)).status)
        assertEquals(HttpStatusCode.Created, cast.owner.client.postJson("$thread/messages", MessageRequest("loud reply")).status)
        assertEquals(2, member.get(feed).body<JsonObject>().getValue("total").jsonPrimitive.int)
    }

    @Test
    fun `mute is a member preference and removal clears it`() = testApplication {
        usePostgresTestcontainer()
        val cast = seedPairCast("mute-private", "Mute access", minted, withThread = true)
        val admin = seedActor("mute-admin", UserRole.ADMIN, "Mute admin").also { minted.users += it.id }
        val thread = "/api/v1/rooms/${cast.room}/threads/1"
        val request = mapOf("muted" to true)
        assertEquals(HttpStatusCode.NotFound, cast.outsider.client.putJson("$thread/mute", request).status)
        assertEquals(HttpStatusCode.Forbidden, admin.client.putJson("$thread/mute", request).status)
        assertEquals(HttpStatusCode.NoContent, cast.ann.client.putJson("$thread/mute", request).status)
        assertEquals(HttpStatusCode.NoContent, cast.owner.client.delete("/api/v1/rooms/${cast.room}/members/${cast.ann.id}").status)
        TestRooms.addMember(cast.room, cast.ann.id)
        val current = cast.ann.client.get(thread).body<JsonObject>()
        assertEquals(false, current["muted"]?.jsonPrimitive?.boolean ?: false)
    }
}
