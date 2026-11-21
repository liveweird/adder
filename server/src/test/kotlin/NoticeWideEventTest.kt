package ch.nokillswit

import ch.nokillswit.infra.notify.LiveKind
import ch.nokillswit.infra.notify.MAX_NOTIFY_PAYLOAD_BYTES
import ch.nokillswit.infra.notify.toPayload
import ch.nokillswit.rooms.MAX_ROOM_MEMBERS
import ch.nokillswit.threads.Notice
import ch.nokillswit.threads.NoticeReason
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

/**
 * The widest event there is: a notice for every member of a full room, 499 people (500 when an ADMIN outside the room acts). It is ONE
 * insert statement and ONE live hint whatever the audience, and it is made under the room lock, so its cost is measured here against the
 * pinned PostgreSQL image and the figures are recorded in `persistence.md` ("The wide-event budget (V29)"). Two measurements: the
 * `NoticeWriter` call alone (the preferences read, the insert and the hint), and the WHOLE write of a thread that tells the room (the lock,
 * the thread and message rows, the audience read of the room's members, then the notice), against the same write in a room of two. The
 * times are printed and read from the report; nothing asserts on a clock, so a busy runner cannot flake the test.
 */
class NoticeWideEventTest {
    private val mintedRooms = mutableListOf<UInt>()
    private val mintedUsers = mutableListOf<UInt>()
    private val mintedTemplates = mutableListOf<UInt>()

    @AfterTest
    fun cleanUp() = runBlocking {
        TestRooms.purge(mintedRooms)
        TestRooms.softDeleteUsers(mintedUsers)
        mintedTemplates.forEach { TestTemplates.delete(it) }
    }

    private suspend fun fullRoom(label: String, members: Int): Triple<UInt, UInt, List<UInt>> {
        val actor = TestUsers.seed(uniqueEmail("wide-act"), "pw", name = "Actor").also { mintedUsers += it }
        val template = TestThreads.seedTemplate().also { mintedTemplates += it }
        val room = TestRooms.seed(actor, template, "$label ${UUID.randomUUID().toString().substring(0, 8)}").also { mintedRooms += it }
        val people = TestRooms.seedMembers(room, members, "wide-${UUID.randomUUID().toString().substring(0, 8)}").also { mintedUsers += it }
        return Triple(actor, room, people)
    }

    @Test
    fun `a notice for the 499 other members of a full room is one insert and one hint, timed`() = runBlocking {
        val (actor, room, people) = fullRoom("Wide", MAX_ROOM_MEMBERS - 1)
        val thread = TestThreads.seed(room, actor)
        val writer = TestThreads.noticeWriter
        val audience = people.map { Notice(it, NoticeReason.THREAD_CREATED) }
        assertEquals(499, audience.size)

        val whole = mutableListOf<Long>()
        val insert = mutableListOf<Long>()
        NotifyCapture().use { capture ->
            val hints = capture.during {
                repeat(RUNS + 1) { run ->
                    val started = System.nanoTime()
                    var inside = 0L
                    val outcome = suspendTransaction(sharedDatabaseForTests()) {
                        val before = System.nanoTime()
                        writer.forThread(room, thread.id, thread.number, actor, audience).also { inside = System.nanoTime() - before }
                    }
                    assertEquals(499, outcome.recipients)
                    if (run > 0) { // the first run warms the plan and the pool
                        whole += (System.nanoTime() - started) / NANOS_PER_MILLI
                        insert += inside / NANOS_PER_MILLI
                    }
                }
            }
            assertEquals(RUNS + 1, hints.size, "one hint per event, never one per recipient")
            assertTrue(hints.all { it.kind == LiveKind.NOTIFICATION_CREATED && it.targetUserIds?.size == 499 })
            val bytes = hints.first().toPayload().toByteArray(Charsets.UTF_8).size
            assertTrue(bytes < MAX_NOTIFY_PAYLOAD_BYTES, "the 499-target hint is $bytes bytes")
            println("WIDE-EVENT 499 recipients: writer call ms=$insert; whole transaction ms=$whole; hint payload bytes=$bytes")
        }
        // The seeded thread told the 499 members of the room as it opened, then one batch per timed event.
        assertEquals((RUNS + 2) * 499, rows(room), "one row per recipient and event")
    }

    @Test
    fun `the whole write of a thread that tells a full room, against the same write in a room of two, timed`() = runBlocking {
        val big = fullRoom("Whole", MAX_ROOM_MEMBERS - 1)
        val small = fullRoom("Small", 1)
        suspend fun time(room: Triple<UInt, UInt, List<UInt>>): List<Long> = (0..RUNS).map {
            val started = System.nanoTime()
            val created = TestThreads.seed(room.second, room.first)
            assertEquals(room.third.size, created.notices.recipients, "everybody but the author is told")
            (System.nanoTime() - started) / NANOS_PER_MILLI
        }.drop(1) // the first run warms the plan and the pool
        val bigMs = time(big)
        val smallMs = time(small)
        println("WIDE-EVENT full write (thread, message, audience read, 499 notices, hint) ms=$bigMs; same write, 1 recipient ms=$smallMs")
        assertEquals((RUNS + 1) * 499, rows(big.second))
    }

    private fun rows(room: UInt): Int = PostgresTestSupport.connect().use { c ->
        c.prepareStatement("SELECT count(*) FROM notifications WHERE room_id = ? AND notification_type = 'THREAD_CREATED'").use { st ->
            st.setInt(1, room.toInt())
            st.executeQuery().use { rs -> rs.next(); rs.getInt(1) }
        }
    }

    private companion object {
        const val RUNS = 7
        const val NANOS_PER_MILLI = 1_000_000L
    }
}
