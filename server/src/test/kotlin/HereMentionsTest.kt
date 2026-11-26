package ch.nokillswit

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
import java.util.UUID

class HereMentionsTest {
    private val minted = MintedIds(mutableListOf(), mutableListOf(), mutableListOf())

    @AfterTest
    fun cleanUp() = runBlocking {
        TestRooms.purge(minted.rooms)
        if (minted.users.isNotEmpty()) PostgresTestSupport.connect().use { connection ->
            connection.prepareStatement("DELETE FROM live_session_leases WHERE user_id = ANY(?)").use { statement ->
                statement.setArray(1, connection.createArrayOf("bigint", minted.users.map(UInt::toLong).toTypedArray()))
                statement.executeUpdate()
            }
        }
        minted.users.forEach { TestUsers.softDelete(it) }
        minted.templates.forEach { TestTemplates.delete(it) }
    }

    private fun foreground(vararg userIds: UInt) {
        PostgresTestSupport.connect().use { connection ->
            connection.prepareStatement(
                "INSERT INTO live_session_leases " +
                    "(session_id, instance_id, user_id, token_expires_at, socket_expires_at, foreground_expires_at) " +
                    "VALUES (?, ?, ?, " +
                    "(extract(epoch FROM clock_timestamp()) * 1000)::bigint + 60000, " +
                    "(extract(epoch FROM clock_timestamp()) * 1000)::bigint + 60000, " +
                    "(extract(epoch FROM clock_timestamp()) * 1000)::bigint + 60000)",
            ).use { statement ->
                userIds.forEach { userId ->
                    statement.setObject(1, UUID.randomUUID())
                    statement.setObject(2, UUID.randomUUID())
                    statement.setLong(3, userId.toLong())
                    statement.addBatch()
                }
                statement.executeBatch()
            }
        }
    }

    private fun background(userId: UInt) {
        PostgresTestSupport.connect().use { connection ->
            connection.prepareStatement("UPDATE live_session_leases SET foreground_expires_at = NULL WHERE user_id = ?").use { statement ->
                statement.setLong(1, userId.toLong())
                statement.executeUpdate()
            }
        }
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
        foreground(cast.owner.id, cast.bob.id, cast.outsider.id)

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

        background(cast.owner.id)
        background(cast.bob.id)
        assertEquals(
            HttpStatusCode.NoContent,
            cast.ann.client.putJson(
                "/api/v1/rooms/${cast.room}/messages/${message.id}", MessageRequest("Still heads up <@here>"),
            ).status,
        )
        assertEquals(listOf(cast.owner.id, cast.bob.id).sorted(), recipients(message.id))

        assertEquals(HttpStatusCode.NoContent, cast.ann.client.putJson(
            "/api/v1/rooms/${cast.room}/messages/${message.id}",
            MessageRequest("Heads up <@here>, including <@${cast.bob.id}>"),
        ).status)
        assertEquals(listOf(cast.owner.id, cast.bob.id).sorted(), recipients(message.id))
        assertEquals(NotificationType.HERE_MENTIONED, notice(cast.owner.id, message.id))
        assertEquals(NotificationType.MENTIONED, notice(cast.bob.id, message.id))
    }

    @Test
    fun `removing and readding here takes a fresh snapshot`() = testApplication {
        usePostgresTestcontainer()
        val cast = seedPairCast("here-fresh", "Here", minted, withThread = true)
        foreground(cast.owner.id, cast.bob.id)
        val posted = cast.ann.client.postJson(
            "/api/v1/rooms/${cast.room}/threads/1/messages", MessageRequest("First <@here>"),
        ).body<MessageResponse>()

        assertEquals(HttpStatusCode.NoContent, cast.ann.client.putJson(
            "/api/v1/rooms/${cast.room}/messages/${posted.id}", MessageRequest("Nobody"),
        ).status)
        background(cast.owner.id)
        assertEquals(HttpStatusCode.NoContent, cast.ann.client.putJson(
            "/api/v1/rooms/${cast.room}/messages/${posted.id}", MessageRequest("Again <@here>"),
        ).status)
        assertEquals(listOf(cast.bob.id), recipients(posted.id))
    }

    @Test
    fun `here has no recipients when there are no foreground tabs`() = testApplication {
        usePostgresTestcontainer()
        val cast = seedPairCast("here-empty", "Here", minted, withThread = true)
        val response = cast.owner.client.postJson(
            "/api/v1/rooms/${cast.room}/threads/1/messages", MessageRequest("Anyone <@here>?"),
        )
        assertEquals(HttpStatusCode.Created, response.status)
        val message = response.body<MessageResponse>()
        assertEquals(emptyList(), recipients(message.id))
        assertEquals(null, notice(cast.ann.id, message.id))
    }
}
