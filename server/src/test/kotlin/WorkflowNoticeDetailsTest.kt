package ch.nokillswit

import ch.nokillswit.threads.ThreadOwnerRequest
import ch.nokillswit.threads.ThreadStatusRequest
import ch.nokillswit.threads.WatchRequest
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class WorkflowNoticeDetailsTest {
    private val minted = MintedIds(mutableListOf(), mutableListOf(), mutableListOf())

    @AfterTest
    fun cleanUp() = runBlocking {
        TestRooms.purge(minted.rooms)
        minted.users.forEach { TestUsers.softDelete(it) }
        minted.templates.forEach { TestTemplates.delete(it) }
    }

    @Test
    fun `status notices keep the transition they describe in either feed view`() = testApplication {
        usePostgresTestcontainer()
        val cast = seedPairCast("notice-status", "Workflow notices", minted, withThread = true)
        val thread = "/api/v1/rooms/${cast.room}/threads/1"
        assertEquals(HttpStatusCode.NoContent, cast.ann.client.putJson("$thread/watch", WatchRequest(true)).status)

        for (status in listOf("monitoring", "open", "done", "open")) {
            assertEquals(HttpStatusCode.NoContent, cast.owner.client.putJson("$thread/status", ThreadStatusRequest(status)).status)
        }

        for (view in listOf("", "&groupReplies=true")) {
            cast.ann.client.assertChange("THREAD_STATUS_CHANGED", "Monitoring", "Open", view)
            cast.ann.client.assertChange("THREAD_CLOSED", "Open", "Done", view)
            cast.ann.client.assertChange("THREAD_REOPENED", "Done", "Open", view)
        }
    }

    @Test
    fun `assignment and coalesced owner notices describe their specific handover`() = testApplication {
        usePostgresTestcontainer()
        val cast = seedPairCast("notice-owner", "Ownership notices", minted, withThread = true)
        val thread = "/api/v1/rooms/${cast.room}/threads/1"
        assertEquals(HttpStatusCode.NoContent, cast.bob.client.putJson("$thread/watch", WatchRequest(true)).status)

        assertEquals(HttpStatusCode.NoContent, cast.owner.client.putJson("$thread/owner", ThreadOwnerRequest(cast.ann.id)).status)
        cast.ann.client.assertChange("THREAD_ASSIGNED", "Name-own", "Name-ann")
        cast.bob.client.assertChange("THREAD_OWNER_CHANGED", "Name-own", "Name-ann")

        assertEquals(HttpStatusCode.NoContent, cast.ann.client.putJson("$thread/owner", ThreadOwnerRequest(cast.owner.id)).status)
        cast.bob.client.assertChange("THREAD_OWNER_CHANGED", "Name-ann", "Name-own")
        cast.ann.client.assertChange("THREAD_ASSIGNED", "Name-own", "Name-ann")
    }

    private suspend fun HttpClient.assertChange(type: String, from: String, to: String, view: String = "") {
        val page = get("/api/v1/notifications/feed?seen=false$view").body<JsonObject>()
        val notice = page.getValue("items").jsonArray.map { it.jsonObject }.single { it.getValue("type").jsonPrimitive.content == type }
        val change = assertNotNull(notice["change"], "$type exposes its recorded transition").jsonObject
        assertEquals(from, change.getValue("from").jsonPrimitive.content)
        assertEquals(to, change.getValue("to").jsonPrimitive.content)
    }
}
