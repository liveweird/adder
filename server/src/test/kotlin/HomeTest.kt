package ch.nokillswit

import ch.nokillswit.home.HomeResponse
import ch.nokillswit.threads.MessagePostRequest
import ch.nokillswit.threads.ReadMarkerRequest
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * The personal Home projection: membership is its visibility boundary, each section applies its own lifecycle rules, quote replies use
 * the existing live message projection, and the room-owner queue ignores requests from deleted applicants.
 */
class HomeTest {
    private val minted = MintedIds(mutableListOf(), mutableListOf(), mutableListOf())

    @AfterTest
    fun cleanUp() = runBlocking {
        TestRooms.purge(minted.rooms)
        minted.users.forEach { TestUsers.softDelete(it) }
        minted.templates.forEach { TestTemplates.delete(it) }
    }

    private fun pending(roomId: UInt, userId: UInt) = PostgresTestSupport.connect().use { connection ->
        connection.prepareStatement(
            "INSERT INTO room_join_requests (room_id, user_id, status, requested_at) VALUES (?, ?, 'PENDING', ?)",
        ).use { statement ->
            statement.setInt(1, roomId.toInt())
            statement.setLong(2, userId.toLong())
            statement.setLong(3, System.currentTimeMillis())
            statement.executeUpdate()
        }
    }

    @Test
    fun `home keeps useful archived work, scopes every section to membership and projects quotes with existing read state`() =
        testApplication {
            usePostgresTestcontainer()
            val cast = seedThreadCast("home", "Home", minted)
            val replyBody = "Following up with <@${cast.owner.id}> and <@everyone>"
            val posted = cast.member.client.postJson(
                "/api/v1/rooms/${cast.room}/threads/1/messages",
                MessagePostRequest(replyBody, cast.opener),
            )
            assertEquals(HttpStatusCode.Created, posted.status)
            val replyId = posted.body<ch.nokillswit.threads.MessageResponse>().id
            val newerRead = TestThreads.seed(cast.room, cast.author.id, title = "Newer but read")

            // The original may become a tombstone: Home carries the still-live reply, not any text from the original.
            assertEquals(HttpStatusCode.NoContent, cast.author.client.delete("/api/v1/rooms/${cast.room}/messages/${cast.opener}").status)
            val now = System.currentTimeMillis()
            TestThreads.setStamps(cast.room, 1, createdAt = now - 2_000, lastActivityAt = now - 1_000)
            TestThreads.setStamps(cast.room, 2, createdAt = now, lastActivityAt = now)
            TestThreads.closeRaw(cast.room, 1)
            pending(cast.room, cast.outsider.id)
            pending(cast.room, cast.admin.id)
            TestUsers.softDelete(cast.admin.id)
            TestRooms.archive(cast.room)

            val authorHome = cast.author.client.get("/api/v1/home").body<HomeResponse>()
            assertEquals(emptyList(), authorHome.ownedThreads, "closed threads are not owned work")
            assertEquals(2, authorHome.watchedThreads.size, "watches stay useful in an archived room")
            assertEquals(
                listOf(1, 2),
                authorHome.watchedThreads.map { it.number },
                "an older unread watch leads a newer read watch",
            )
            with(authorHome.watchedThreads.first()) {
                assertEquals(cast.room, roomId)
                assertTrue(roomArchived)
                assertTrue(closed)
                assertTrue(unreadCount > 0)
            }
            assertEquals(newerRead.number, authorHome.watchedThreads.last().number)
            assertEquals(0, authorHome.watchedThreads.last().unreadCount)
            with(authorHome.quotes.single()) {
                assertEquals(cast.room, roomId)
                assertTrue(roomArchived)
                assertEquals(replyId, messageId)
                assertEquals("Marek", authorName)
                assertEquals(replyBody, body)
                assertFalse(truncated)
                assertTrue(unread)
                assertEquals(listOf(cast.owner.id), mentions.map { it.userId })
                assertEquals(listOf(ch.nokillswit.threads.MentionGroupKind.EVERYONE), groupMentions.map { it.kind })
            }

            assertEquals(
                HttpStatusCode.NoContent,
                cast.author.client.putJson("/api/v1/rooms/${cast.room}/threads/1/read-marker", ReadMarkerRequest(replyId)).status,
            )
            assertFalse(cast.author.client.get("/api/v1/home").body<HomeResponse>().quotes.single().unread)

            val activeRoom = TestRooms.seed(
                cast.owner.id,
                minted.templates.single(),
                "Home active ${UUID.randomUUID().toString().take(8)}",
            ).also { minted.rooms += it }
            repeat(6) { index -> TestThreads.seed(activeRoom, cast.owner.id, title = "Owned ${index + 1}") }
            TestThreads.closeRaw(activeRoom, 6)
            val ownerHome = cast.owner.client.get("/api/v1/home").body<HomeResponse>()
            assertEquals(5, ownerHome.ownedThreads.size, "the section is capped after closed threads are filtered")
            assertTrue(ownerHome.ownedThreads.none { it.closed })
            assertEquals(listOf(cast.room, activeRoom), ownerHome.ownedRooms.map { it.id }, "pending work leads even when archived")
            with(ownerHome.ownedRooms.first()) {
                assertTrue(archived)
                assertEquals(1, pendingJoinRequests, "a deleted applicant is not pending owner work")
            }
            assertFalse(ownerHome.ownedRooms.last().archived)

            val outsiderHome = cast.outsider.client.get("/api/v1/home").body<HomeResponse>()
            assertTrue(outsiderHome.quotes.isEmpty())
            assertTrue(outsiderHome.ownedThreads.isEmpty())
            assertTrue(outsiderHome.watchedThreads.isEmpty())
            assertTrue(outsiderHome.ownedRooms.isEmpty())
        }
}
