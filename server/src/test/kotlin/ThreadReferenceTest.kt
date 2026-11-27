package ch.nokillswit

import ch.nokillswit.authz.RoomAccessType
import ch.nokillswit.authz.NotFoundException
import ch.nokillswit.infra.paging.PageRequest
import ch.nokillswit.infra.paging.PageResponse
import ch.nokillswit.infra.paging.SortField
import ch.nokillswit.threads.ReadLimiter
import ch.nokillswit.threads.ThreadReferenceService
import ch.nokillswit.threads.ThreadReferenceResponse
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout

class ThreadReferenceTest {
    private val users = mutableListOf<UInt>()
    private val rooms = mutableListOf<UInt>()
    private val templates = mutableListOf<UInt>()

    @AfterTest
    fun cleanUp() = runBlocking {
        TestRooms.purge(rooms)
        users.forEach { TestUsers.softDelete(it) }
        templates.forEach { TestTemplates.delete(it) }
    }

    private suspend fun ApplicationTestBuilder.room(owner: RoomActor, accessType: RoomAccessType = RoomAccessType.RESTRICTED_HIDDEN): UInt {
        val template = TestThreads.seedTemplate("ref").also { templates += it }
        return TestRooms.seed(owner.id, template, "References ${UUID.randomUUID()}", accessType = accessType).also { rooms += it }
    }

    private fun path(room: UInt, query: String = "") = "/api/v1/rooms/$room/thread-references$query"

    @Test
    fun `references retain readable lifecycle numbers and page one snapshot`() = testApplication {
        usePostgresTestcontainer()
        val owner = seedActor("reference-owner").also { users += it.id }
        val room = room(owner)
        val otherRoom = room(owner)
        val source = TestThreads.seed(room, owner.id, title = "Merged source")
        val target = TestThreads.seed(room, owner.id, title = "Merge target")
        val closed = TestThreads.seed(room, owner.id, title = "Closed")
        val deleted = TestThreads.seed(room, owner.id, title = "Deleted")
        val sameNumber = TestThreads.seed(otherRoom, owner.id, title = "Same number, other room")
        val otherOnly = (2..5).map { TestThreads.seed(otherRoom, owner.id, title = "Other room $it") }.last()
        assertEquals(source.number, sameNumber.number)
        assertEquals(deleted.number + 1, otherOnly.number, "this requested number exists only in the other room")

        assertEquals(
            HttpStatusCode.NoContent,
            owner.client.putJson(
                "/api/v1/rooms/$room/threads/${source.number}/merge",
                mapOf("targetNumber" to target.number),
            ).status,
        )
        TestThreads.closeRaw(room, closed.number)
        TestThreads.closeRaw(room, deleted.number)
        assertEquals(HttpStatusCode.NoContent, owner.client.delete("/api/v1/rooms/$room/threads/${deleted.number}").status)
        TestRooms.archive(room)

        val requested = listOf(deleted.number, otherOnly.number, 9999, closed.number, source.number, target.number, source.number)
            .joinToString("&") { "number=$it" }
        val first = owner.client.get(path(room, "?$requested&page=1&pageSize=2")).body<PageResponse<ThreadReferenceResponse>>()
        val second = owner.client.get(path(room, "?$requested&page=2&pageSize=2")).body<PageResponse<ThreadReferenceResponse>>()
        val descending = owner.client.get(path(room, "?$requested&sort=-number")).body<PageResponse<ThreadReferenceResponse>>()

        assertEquals(listOf(source.number, target.number), first.items.map { it.number })
        assertEquals(listOf(closed.number), second.items.map { it.number })
        assertEquals(3L, first.total)
        assertEquals(3L, second.total)
        assertEquals(listOf(closed.number, target.number, source.number), descending.items.map { it.number })
        assertEquals(
            PageResponse(emptyList(), 1, 20, 0),
            owner.client.get(path(room, "?number=9999")).body<PageResponse<ThreadReferenceResponse>>(),
        )
    }

    @Test
    fun `room visibility precedes query validation and numbers are strict bounded and distinct`() = testApplication {
        usePostgresTestcontainer()
        val owner = seedActor("reference-guard-owner").also { users += it.id }
        val outsider = seedActor("reference-guard-outsider").also { users += it.id }
        val admin = seedActor("reference-guard-admin", ch.nokillswit.users.UserRole.ADMIN).also { users += it.id }
        val room = room(owner)
        val thread = TestThreads.seed(room, owner.id)

        for (client in listOf(outsider.client, admin.client)) {
            assertEquals(HttpStatusCode.NotFound, client.get(path(room, "?number=broken&page=0&sort=id")).status)
        }
        assertEquals(HttpStatusCode.NotFound, owner.client.get(path(2_000_000_000u, "?number=broken")).status)

        val malformed = listOf(
            "",
            "?number=",
            "?number=0",
            "?number=-1",
            "?number=+1",
            "?number=2147483648",
            "?number=one",
            "?number=${thread.number}&sort=id",
        )
        malformed.forEach { query ->
            assertEquals(HttpStatusCode.BadRequest, owner.client.get(path(room, query)).status, query)
        }
        val tooMany = (1..101).joinToString("&") { "number=$it" }
        assertEquals(HttpStatusCode.BadRequest, owner.client.get(path(room, "?$tooMany")).status)

        val deduplicated = owner.client.get(path(room, "?number=${thread.number}&number=${thread.number}"))
        assertEquals(HttpStatusCode.OK, deduplicated.status)
        assertEquals(
            PageResponse(listOf(ThreadReferenceResponse(thread.number)), 1, 20, 1),
            deduplicated.body(),
        )
    }

    @Test
    fun `membership and active account are checked again after the shared read permit wait`() = testApplication {
        usePostgresTestcontainer()
        val owner = seedActor("reference-wait-owner").also { users += it.id }
        val member = seedActor("reference-wait-member").also { users += it.id }
        val deletedActor = seedActor("reference-wait-deleted").also { users += it.id }
        val room = room(owner)
        TestRooms.addMember(room, member.id)
        TestRooms.addMember(room, deletedActor.id)
        val thread = TestThreads.seed(room, owner.id)
        val limiter = ReadLimiter(maxConcurrent = 1, maxWaitMillis = 5_000)
        val service = ThreadReferenceService(sharedDatabaseForTests(), limiter)
        val paging = PageRequest(1, 20, listOf(SortField("number", descending = false)))

        supervisorScope {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val blocker = async {
                limiter.run(sharedDatabaseForTests(), owner.id, room, "threads") {
                    entered.complete(Unit)
                    release.await()
                }
            }
            entered.await()
            val queued = async { service.list(member.id, room, listOf(thread.number), paging) }
            val deletedQueued = async { service.list(deletedActor.id, room, listOf(thread.number), paging) }
            withTimeout(5_000) {
                while (limiter.usersInFlight() != 3) delay(10)
            }
            PostgresTestSupport.connect().use { connection ->
                connection.prepareStatement("DELETE FROM room_members WHERE room_id = ? AND user_id = ?").use { statement ->
                    statement.setInt(1, room.toInt())
                    statement.setLong(2, member.id.toLong())
                    assertEquals(1, statement.executeUpdate())
                }
            }
            TestUsers.softDelete(deletedActor.id)
            release.complete(Unit)
            blocker.await()
            assertFailsWith<NotFoundException> { queued.await() }
            assertFailsWith<NotFoundException> { deletedQueued.await() }
        }
    }
}
