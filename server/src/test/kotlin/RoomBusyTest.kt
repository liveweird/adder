package ch.nokillswit

import ch.nokillswit.authz.CallerPrincipal
import ch.nokillswit.infra.db.R2dbcDatabaseKey
import ch.nokillswit.plugins.ProblemDetail
import ch.nokillswit.rooms.ROOM_BUSY_PROBLEM_TYPE
import ch.nokillswit.rooms.readerGuard
import ch.nokillswit.rooms.withRoomWrite
import ch.nokillswit.threads.MessageRequest
import ch.nokillswit.threads.ThreadStatusRequest
import ch.nokillswit.users.UserRole
import io.ktor.client.call.body
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.r2dbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

/**
 * The bounded wait for the room lock (`rooms/RoomLock.kt`, the 0.3.0 security audit's M1): while another transaction holds the room row
 * a write answers `429` of type `room-busy` with `Retry-After: 1` within about the configured wait, not after the pool's own timeouts,
 * audited `room.busy`, writes nothing, and the transaction-local `lock_timeout` leaks nothing to the pooled connection. The app runs on a
 * ONE-connection pool here, so "the next borrower" is always the connection the failed write used. Rooms, templates and users are
 * SHARED suite state: every test mints UNIQUE rows.
 */
class RoomBusyTest {
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

    private fun ApplicationTestBuilder.configureWithShortWait(waitMillis: Int) = configureApp(
        "rooms.lockWaitMillis" to waitMillis.toString(),
        "postgres.pool.maxSize" to "1",
        "postgres.pool.initialSize" to "1",
    )

    private suspend fun ApplicationTestBuilder.actor(prefix: String, role: UserRole = UserRole.USER) =
        seedActor(prefix, role).also { mintedUsers += it.id }

    private suspend fun shownLockTimeout(): String? = suspendTransaction(sharedDatabaseForTests()) { lockTimeoutHere() }

    private suspend fun lockTimeoutHere(): String? =
        TransactionManager.current().exec("SHOW lock_timeout") { it.get(0, String::class.java) }?.toList()?.single()

    private suspend fun assertBusy(response: HttpResponse, elapsedMillis: Long, waitMillis: Int) {
        assertEquals(HttpStatusCode.TooManyRequests, response.status)
        assertEquals("1", response.headers["Retry-After"])
        val problem = response.body<ProblemDetail>()
        assertEquals(ROOM_BUSY_PROBLEM_TYPE, problem.type)
        assertEquals(429, problem.status)
        assertTrue("busy" in problem.detail.orEmpty(), "not the rate-limit sentence: ${problem.detail}")
        assertTrue(elapsedMillis >= waitMillis - 50, "it did wait for the lock ($elapsedMillis ms)")
        assertTrue(elapsedMillis < waitMillis + 2000, "it answered at the bound, not at the default or the pool's ($elapsedMillis ms)")
    }

    @Test
    fun `a write behind a held room lock is 429 room-busy at the wait - audited, nothing written, no lock_timeout left behind`() =
        testApplication {
            configureWithShortWait(300)
            startApplication()
            val owner = actor("busy-own")
            val member = actor("busy-mem")
            val template = TestThreads.seedTemplate().also { mintedTemplates += it }
            val room = TestRooms.seed(owner.id, template, "Busy ${UUID.randomUUID().toString().substring(0, 8)}").also { mintedRooms += it }
            TestRooms.addMember(room, member.id)
            TestThreads.seed(room, owner.id)
            val before = TestThreads.raw(room, 1)!!
            val activity = TestThreads.roomActivity(room)

            withAuditCapture { capture ->
                TestThreads.holdingRoomLock(room) {
                    val started = System.nanoTime()
                    val post = member.client.postJson("$rooms/$room/threads/1/messages", MessageRequest("behind the lock"))
                    assertBusy(post, (System.nanoTime() - started) / 1_000_000, 300)

                    val moveStarted = System.nanoTime()
                    val move = owner.client.putJson("$rooms/$room/threads/1/status", ThreadStatusRequest("monitoring"))
                    assertBusy(move, (System.nanoTime() - moveStarted) / 1_000_000, 300)
                }
                val busy = capture.awaitEvent { it.message == "room.busy" && it.hasKeyValue("byUserId", member.id.toLong()) }
                assertNotNull(busy)
                assertTrue(busy.hasKeyValue("roomId", room.toLong()))
                assertTrue(
                    capture.events.none { it.message == "rate_limit.exceeded" && it.hasKeyValue("byUserId", member.id.toLong()) },
                    "a busy room is not a rate-limit rejection",
                )
                val everything = capture.events.filter { it.message == "room.busy" }.joinToString { e ->
                    "${e.formattedMessage} ${e.keyValuePairs?.joinToString { "${it.key}=${it.value}" }}"
                }
                assertTrue("behind the lock" !in everything)
            }

            val after = TestThreads.raw(room, 1)!!
            val unchanged = { row: TestThreads.RawThread -> listOf(row.statusKey, row.messageCount, row.revision) }
            assertEquals(unchanged(before), unchanged(after))
            assertEquals(1, TestThreads.rawMessages(before.id).size, "the refused post wrote no message")
            assertEquals(activity, TestThreads.roomActivity(room))

            // The lock is free again and the very connection that timed out (the pool holds ONE) is clean: no leftover lock_timeout, and
            // the write goes through.
            val db = application.attributes[R2dbcDatabaseKey]
            assertEquals("0", suspendTransaction(db) { lockTimeoutHere() })
            assertEquals(HttpStatusCode.Created, member.client.postJson("$rooms/$room/threads/1/messages", MessageRequest("after")).status)
            assertEquals("0", suspendTransaction(db) { lockTimeoutHere() })
            assertEquals("0", shownLockTimeout())
        }

    @Test
    fun `inside a room write the bound is lifted once the lock is held - no other wait of the write runs under it`() = testApplication {
        configureWithShortWait(300)
        startApplication()
        val owner = actor("busy-in")
        val template = TestThreads.seedTemplate().also { mintedTemplates += it }
        val room = TestRooms.seed(owner.id, template, "BusyIn ${UUID.randomUUID().toString().substring(0, 8)}").also { mintedRooms += it }
        val db = application.attributes[R2dbcDatabaseKey]

        val inside = db.withRoomWrite(room, CallerPrincipal(owner.id, "busy@test", setOf(UserRole.USER)), readerGuard) { _, _ ->
            lockTimeoutHere()
        }
        assertEquals("0", inside)
    }

    @Test
    fun `the boot refuses a room lock wait below 100 ms`() = testApplication {
        configureApp("rooms.lockWaitMillis" to "99")
        assertStartupFails("rooms.lockWaitMillis") { startApplication() }
    }

    @Test
    fun `the boot refuses a room lock wait above 30 s`() = testApplication {
        configureApp("rooms.lockWaitMillis" to "30001")
        assertStartupFails("rooms.lockWaitMillis") { startApplication() }
    }
}
