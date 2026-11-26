package ch.nokillswit

import ch.nokillswit.authz.CallerPrincipal
import ch.nokillswit.authz.RoomRole
import ch.nokillswit.infra.paging.PageRequest
import ch.nokillswit.infra.paging.SortField
import ch.nokillswit.plugins.ProblemDetail
import ch.nokillswit.threads.MessageRequest
import ch.nokillswit.threads.MessageResponse
import ch.nokillswit.threads.MessageRevisionPageResponse
import ch.nokillswit.threads.MessageRevisionServiceKey
import ch.nokillswit.threads.THREAD_CLOSED_PROBLEM_TYPE
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class MessageRevisionTest {
    private val rooms = mutableListOf<UInt>()
    private val users = mutableListOf<UInt>()
    private val templates = mutableListOf<UInt>()
    private val minted = MintedIds(users, templates, rooms)

    @AfterTest
    fun cleanUp() = runBlocking {
        TestRooms.purge(rooms)
        users.forEach { TestUsers.softDelete(it) }
        templates.forEach { TestTemplates.delete(it) }
    }

    private suspend fun ApplicationTestBuilder.cast(prefix: String) = seedThreadCast(prefix, "Revision", minted)

    private suspend fun HttpClient.edit(roomId: UInt, messageId: UInt, body: String) =
        putJson("/api/v1/rooms/$roomId/messages/$messageId", MessageRequest(body))

    private suspend fun HttpClient.revisions(roomId: UInt, messageId: UInt, query: String = "") =
        get("/api/v1/rooms/$roomId/messages/$messageId/revisions$query")

    private fun revisionCount(messageIds: Collection<UInt>): Int {
        if (messageIds.isEmpty()) return 0
        PostgresTestSupport.connect().use { connection ->
            val placeholders = messageIds.joinToString(",") { "?" }
            connection.prepareStatement("SELECT count(*) FROM message_revisions WHERE message_id IN ($placeholders)").use { statement ->
                messageIds.forEachIndexed { index, id -> statement.setInt(index + 1, id.toInt()) }
                statement.executeQuery().use { rows ->
                    rows.next()
                    return rows.getInt(1)
                }
            }
        }
    }

    @Test
    fun `changed edits save only prior bodies with a continuous timeline and paging`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("rev-list")
        val posted = cast.member.client.postJson(
            "/api/v1/rooms/${cast.room}/threads/1/messages",
            MessageRequest("first draft"),
        ).body<MessageResponse>()

        assertEquals(HttpStatusCode.NoContent, cast.member.client.edit(cast.room, posted.id, "second draft").status)
        assertEquals(HttpStatusCode.NoContent, cast.member.client.edit(cast.room, posted.id, "third draft").status)
        assertEquals(HttpStatusCode.NoContent, cast.member.client.edit(cast.room, posted.id, "third draft\n\n").status)

        val response = cast.member.client.revisions(cast.room, posted.id, "?pageSize=1")
        assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
        val descending = response.body<MessageRevisionPageResponse>()
        assertEquals(2L, descending.total)
        assertEquals(listOf("second draft"), descending.items.map { it.body })
        val pastEnd = cast.member.client.revisions(cast.room, posted.id, "?page=3&pageSize=1").body<MessageRevisionPageResponse>()
        assertEquals(2L, pastEnd.total)
        assertTrue(pastEnd.items.isEmpty())
        val ascending = cast.member.client.revisions(cast.room, posted.id, "?sort=id").body<MessageRevisionPageResponse>()
        assertEquals(listOf("first draft", "second draft"), ascending.items.map { it.body })
        assertEquals(posted.createdAt, ascending.items[0].createdAt)
        assertEquals(ascending.items[0].supersededAt, ascending.items[1].createdAt)
        assertTrue(ascending.items[1].supersededAt >= ascending.items[1].createdAt)
        assertEquals(2, revisionCount(listOf(posted.id)), "the no-op edit stores no revision")

        TestThreads.closeRaw(cast.room, 1)
        val refused = cast.member.client.edit(cast.room, posted.id, "fourth draft")
        assertEquals(HttpStatusCode.Conflict, refused.status)
        assertEquals(THREAD_CLOSED_PROBLEM_TYPE, refused.body<ProblemDetail>().type)
        assertEquals(2, revisionCount(listOf(posted.id)), "a failed edit stores no revision")
        assertEquals(
            listOf("second draft", "first draft"),
            cast.member.client.revisions(cast.room, posted.id).body<MessageRevisionPageResponse>().items.map { it.body },
            "history remains readable on a closed thread",
        )
    }

    @Test
    fun `the page cannot release saved bodies when the room role changes after authorization`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("rev-guard-race")
        TestRooms.addMember(cast.room, cast.outsider.id, RoomRole.OWNER)
        assertEquals(HttpStatusCode.NoContent, cast.author.client.edit(cast.room, cast.opener, "new opening").status)

        val service = application.attributes[MessageRevisionServiceKey]
        val caller = CallerPrincipal(cast.owner.id, "rev-guard-race@test", emptySet())
        val result = service.list(caller, cast.room, cast.opener) {
            PostgresTestSupport.connect().use { connection ->
                connection.prepareStatement("UPDATE room_members SET role = 'MEMBER' WHERE room_id = ? AND user_id = ?").use { sql ->
                    sql.setInt(1, cast.room.toInt())
                    sql.setLong(2, cast.owner.id.toLong())
                    assertEquals(1, sql.executeUpdate())
                }
            }
            PageRequest(1, 20, listOf(SortField("id", descending = true)))
        }

        assertEquals(0L, result.total)
        assertTrue(result.items.isEmpty())
        assertEquals(1, revisionCount(listOf(cast.opener)), "the earlier body still exists for an authorized reader")
    }

    @Test
    fun `a clock moving backwards does not reject a real edit or reverse its revision timestamp`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("rev-clock")
        val previousStart = System.currentTimeMillis() + 60_000
        PostgresTestSupport.connect().use { connection ->
            connection.prepareStatement("UPDATE messages SET edited_at = ? WHERE id = ?").use { sql ->
                sql.setLong(1, previousStart)
                sql.setInt(2, cast.opener.toInt())
                assertEquals(1, sql.executeUpdate())
            }
        }
        assertEquals(HttpStatusCode.NoContent, cast.author.client.edit(cast.room, cast.opener, "after skew").status)
        val recorded = cast.author.client.revisions(cast.room, cast.opener).body<MessageRevisionPageResponse>().items.single()
        assertEquals(previousStart, recorded.createdAt)
        assertEquals(previousStart, recorded.supersededAt)
    }

    @Test
    fun `history checks the live parent and permission before paging parameters`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("rev-auth")
        val id = TestThreads.post(cast.room, 1, cast.member.id, "private first draft")
        assertEquals(HttpStatusCode.NoContent, cast.member.client.edit(cast.room, id, "current body").status)

        assertEquals(HttpStatusCode.OK, cast.member.client.revisions(cast.room, id).status, "current author-member")
        assertEquals(HttpStatusCode.OK, cast.owner.client.revisions(cast.room, id).status, "room owner")
        assertEquals(HttpStatusCode.OK, cast.admin.client.revisions(cast.room, id).status, "admin with room access")
        assertEquals(HttpStatusCode.Forbidden, cast.author.client.revisions(cast.room, id).status, "another room member")
        assertEquals(HttpStatusCode.NotFound, cast.outsider.client.revisions(cast.room, id, "?sort=body").status)
        assertEquals(HttpStatusCode.Forbidden, cast.author.client.revisions(cast.room, id, "?sort=body").status)
        assertEquals(HttpStatusCode.BadRequest, cast.member.client.revisions(cast.room, id, "?sort=body").status)
        assertEquals(HttpStatusCode.NotFound, cast.owner.client.revisions(cast.room, 2_000_000u, "?sort=body").status)

        PostgresTestSupport.connect().use { connection ->
            connection.prepareStatement("DELETE FROM room_members WHERE room_id = ? AND user_id = ?").use { statement ->
                statement.setInt(1, cast.room.toInt())
                statement.setLong(2, cast.member.id.toLong())
                assertEquals(1, statement.executeUpdate())
            }
        }
        assertEquals(HttpStatusCode.NotFound, cast.member.client.revisions(cast.room, id).status, "access loss applies immediately")
        assertEquals(HttpStatusCode.OK, cast.owner.client.revisions(cast.room, id).status)
    }

    @Test
    fun `message and canonical thread deletion physically scrub saved bodies`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("rev-delete")
        val moderated = TestThreads.post(cast.room, 1, cast.member.id, "remove this old body")
        assertEquals(HttpStatusCode.NoContent, cast.member.client.edit(cast.room, moderated, "remove this current body").status)
        assertEquals(1, revisionCount(listOf(moderated)))
        assertEquals(
            HttpStatusCode.NoContent,
            cast.owner.client.delete("/api/v1/rooms/${cast.room}/messages/$moderated").status,
            "a moderator may delete another person's message",
        )
        assertEquals(0, revisionCount(listOf(moderated)))
        assertEquals(HttpStatusCode.NotFound, cast.owner.client.revisions(cast.room, moderated).status)

        val source = TestThreads.seed(cast.room, cast.owner.id, title = "Duplicate", body = "source old")
        val target = TestThreads.seed(cast.room, cast.owner.id, title = "Canonical", body = "target old")
        assertEquals(HttpStatusCode.NoContent, cast.owner.client.edit(cast.room, source.messageId, "source current").status)
        assertEquals(HttpStatusCode.NoContent, cast.owner.client.edit(cast.room, target.messageId, "target current").status)
        assertEquals(2, revisionCount(listOf(source.messageId, target.messageId)))
        assertEquals(
            HttpStatusCode.NoContent,
            cast.owner.client.putJson(
                "/api/v1/rooms/${cast.room}/threads/${source.number}/merge",
                mapOf("targetNumber" to target.number),
            ).status,
        )
        assertEquals(
            HttpStatusCode.NoContent,
            cast.owner.client.delete("/api/v1/rooms/${cast.room}/threads/${target.number}").status,
        )
        assertEquals(0, revisionCount(listOf(source.messageId, target.messageId)), "the target and its merged sources are scrubbed")
    }
}
