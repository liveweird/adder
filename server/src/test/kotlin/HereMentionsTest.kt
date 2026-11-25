package ch.nokillswit

import ch.nokillswit.infra.presence.ForegroundPresenceKey
import ch.nokillswit.notifications.NotificationType
import ch.nokillswit.threads.MessageRequest
import ch.nokillswit.threads.MessageResponse
import ch.nokillswit.threads.ThreadPageResponse
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking

class HereMentionsTest {
    private val minted = MintedIds(mutableListOf(), mutableListOf(), mutableListOf())

    @AfterTest
    fun cleanUp() = runBlocking {
        TestRooms.purge(minted.rooms)
        minted.users.forEach { TestUsers.softDelete(it) }
        minted.templates.forEach { TestTemplates.delete(it) }
    }

    private fun recipients(messageId: UInt): List<UInt> = PostgresTestSupport.connect().use { connection ->
        connection.prepareStatement("SELECT user_id FROM message_here_mentions WHERE message_id = ? ORDER BY user_id").use { statement ->
            statement.setInt(1, messageId.toInt())
            statement.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getLong(1).toUInt()) } }
        }
    }

    private fun notice(recipient: UInt, messageId: UInt): NotificationType? = PostgresTestSupport.connect().use { connection ->
        val sql = "SELECT notification_type FROM notifications WHERE recipient_id = ? AND message_id = ?"
        connection.prepareStatement(sql).use { statement ->
            statement.setLong(1, recipient.toLong())
            statement.setInt(2, messageId.toInt())
            statement.executeQuery().use { rows -> if (rows.next()) NotificationType.valueOf(rows.getString(1)) else null }
        }
    }

    @Test
    fun `here snapshots visible member tabs and a kept token preserves the snapshot`() = testApplication {
        usePostgresTestcontainer()
        val cast = seedPairCast("here-snapshot", "Here", minted, withThread = true)
        val presence = application.attributes[ForegroundPresenceKey]
        val ownerTab = presence.open(cast.owner.id)
        val bobTab = presence.open(cast.bob.id)
        val outsiderTab = presence.open(cast.outsider.id)
        presence.update(ownerTab, true)
        presence.update(bobTab, true)
        presence.update(outsiderTab, true)

        val posted = cast.ann.client.postJson(
            "/api/v1/rooms/${cast.room}/threads/1/messages", MessageRequest("Heads up <@here>"),
        )
        assertEquals(HttpStatusCode.Created, posted.status)
        val message = posted.body<MessageResponse>()
        assertEquals(listOf(cast.owner.id, cast.bob.id).sorted(), recipients(message.id))
        assertEquals(NotificationType.HERE_MENTIONED, notice(cast.owner.id, message.id))
        assertEquals(NotificationType.HERE_MENTIONED, notice(cast.bob.id, message.id))
        assertEquals(
            listOf(1),
            cast.bob.client.get("/api/v1/rooms/${cast.room}/threads?mentionsMe=true")
                .body<ThreadPageResponse>().items.map { it.number },
        )

        presence.update(ownerTab, false)
        presence.close(bobTab)
        assertEquals(
            HttpStatusCode.NoContent,
            cast.ann.client.putJson(
                "/api/v1/rooms/${cast.room}/messages/${message.id}", MessageRequest("Still heads up <@here>"),
            ).status,
        )
        assertEquals(listOf(cast.owner.id, cast.bob.id).sorted(), recipients(message.id))
    }

    @Test
    fun `removing and readding here takes a fresh snapshot`() = testApplication {
        usePostgresTestcontainer()
        val cast = seedPairCast("here-fresh", "Here", minted, withThread = true)
        val presence = application.attributes[ForegroundPresenceKey]
        val ownerTab = presence.open(cast.owner.id)
        val bobTab = presence.open(cast.bob.id)
        presence.update(ownerTab, true)
        presence.update(bobTab, true)
        val posted = cast.ann.client.postJson(
            "/api/v1/rooms/${cast.room}/threads/1/messages", MessageRequest("First <@here>"),
        ).body<MessageResponse>()

        assertEquals(HttpStatusCode.NoContent, cast.ann.client.putJson(
            "/api/v1/rooms/${cast.room}/messages/${posted.id}", MessageRequest("Nobody"),
        ).status)
        presence.update(ownerTab, false)
        assertEquals(HttpStatusCode.NoContent, cast.ann.client.putJson(
            "/api/v1/rooms/${cast.room}/messages/${posted.id}", MessageRequest("Again <@here>"),
        ).status)
        assertEquals(listOf(cast.bob.id), recipients(posted.id))
    }
}
