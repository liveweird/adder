package ch.nokillswit

import ch.nokillswit.threads.MessageTagReindexKey
import ch.nokillswit.threads.StaleDerivedRows
import ch.nokillswit.users.UserRole
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * The boot purge of the derived rows of DELETED messages (`StaleDerivedRows`, `persistence.md` "Mentions (V17)" "Rollback below 0.6.0"):
 * a 0.5.x binary run against V17+ deletes a message without clearing its mention, reaction, pin and notice rows. The rollback is
 * simulated by raw SQL (a tombstone written the way 0.5.x writes it, the rows inserted straight into the tables). Rooms and users are
 * shared suite state: unique rows, removed afterwards.
 */
class StaleDerivedRowsTest {
    private val mintedRooms = mutableListOf<UInt>()
    private val mintedUsers = mutableListOf<UInt>()
    private val mintedTemplates = mutableListOf<UInt>()

    @AfterTest
    fun cleanUp() = runBlocking {
        TestRooms.purge(mintedRooms)
        mintedUsers.forEach { TestUsers.softDelete(it) }
        mintedTemplates.forEach { TestTemplates.delete(it) }
    }

    private class Fixture(val room: UInt, val threadId: UInt, val author: UInt)

    private suspend fun fixture(prefix: String): Fixture {
        val author = TestUsers.seed(uniqueEmail(prefix), "pw", role = UserRole.USER, name = "Stale").also { mintedUsers += it }
        val template = TestThreads.seedTemplate().also { mintedTemplates += it }
        val room = TestRooms.seed(author, template, "Stale ${UUID.randomUUID().toString().substring(0, 8)}").also { mintedRooms += it }
        return Fixture(room, TestThreads.seed(room, author).id, author)
    }

    private fun exec(sql: String, vararg args: UInt) = PostgresTestSupport.connect().use { c ->
        c.prepareStatement(sql).use { st ->
            args.forEachIndexed { i, a -> st.setLong(i + 1, a.toLong()) }
            st.executeUpdate()
        }
    }

    /** One row of every derived kind for [message], as the 0.6.0 writes left them. */
    private fun derivedRows(f: Fixture, message: UInt) {
        exec(
            "INSERT INTO message_mentions (message_id, user_id, thread_id, room_id) VALUES (?, ?, ?, ?)",
            message, f.author, f.threadId, f.room,
        )
        exec("INSERT INTO message_reactions (message_id, user_id, emoji, created_at) VALUES (?, ?, 'x', 1)", message, f.author)
        exec(
            "INSERT INTO room_pins (room_id, thread_id, message_id, pinned_by, pinned_at) VALUES (?, ?, ?, ?, 1)",
            f.room, f.threadId, message, f.author,
        )
        exec(
            "INSERT INTO notifications (recipient_id, room_id, notification_type, actor_id, thread_id, message_id, created_at) " +
                "VALUES (?, ?, 'MENTIONED', ?, ?, ?, 1)",
            f.author, f.room, f.author, f.threadId, message,
        )
    }

    private fun derivedCount(message: UInt): Int = PostgresTestSupport.connect().use { c ->
        val sql = "SELECT (SELECT count(*) FROM message_mentions WHERE message_id = ?)" +
            " + (SELECT count(*) FROM message_reactions WHERE message_id = ?)" +
            " + (SELECT count(*) FROM room_pins WHERE message_id = ?)" +
            " + (SELECT count(*) FROM notifications WHERE message_id = ?)"
        c.prepareStatement(sql).use { st ->
            (1..4).forEach { st.setInt(it, message.toInt()) }
            st.executeQuery().use { rs -> rs.next(); rs.getInt(1) }
        }
    }

    /** What a 0.5.x binary does to a message it deletes: the tombstone, every derived row untouched. */
    private fun tombstone(id: UInt, author: UInt) =
        exec("UPDATE messages SET body = '', marked_as_deleted = TRUE, deleted_by = ? WHERE id = ?", author, id)

    private val purge: StaleDerivedRows by lazy { StaleDerivedRows(sharedDatabaseForTests()) }

    /** Waits for the boot task, so it cannot race a test's own rows. */
    private suspend fun ApplicationTestBuilder.awaitBootRun(): Int? =
        withTimeout(60_000) { application.attributes[MessageTagReindexKey].await() }

    @Test
    fun `the purge removes the mention, reaction, pin and notice rows of a deleted message and nothing else`() = testApplication {
        usePostgresTestcontainer()
        awaitBootRun()
        val f = fixture("stale-purge")
        val live = TestThreads.post(f.room, 1, f.author, "live")
        val gone = TestThreads.post(f.room, 1, f.author, "going")
        derivedRows(f, live)
        derivedRows(f, gone)
        tombstone(gone, f.author)

        assertEquals(4, purge.purge())
        assertEquals(0, derivedCount(gone))
        assertEquals(4, derivedCount(live), "a live message keeps every derived row")
        assertEquals(0, purge.purge(), "a second run finds nothing")
    }

    @Test
    fun `the purge runs in batches`() = testApplication {
        usePostgresTestcontainer()
        awaitBootRun()
        val f = fixture("stale-many")
        // 1,050 tombstones with one mention row each: more than one batch (1,000 messages).
        exec(
            "INSERT INTO messages (thread_id, author_id, body, created_at, marked_as_deleted, deleted_by, tags_version) " +
                "SELECT ?, ?, '', 1, TRUE, ?, 1 FROM generate_series(1, 1050)",
            f.threadId, f.author, f.author,
        )
        exec(
            "INSERT INTO message_mentions (message_id, user_id, thread_id, room_id) SELECT id, ?, thread_id, ? FROM messages " +
                "WHERE thread_id = ? AND marked_as_deleted",
            f.author, f.room, f.threadId,
        )
        assertEquals(1050, purge.purge())
        assertEquals(0, purge.purge())
    }

    @Test
    fun `the boot pass runs the purge`() = testApplication {
        // The rows are made BEFORE the application is configured: the boot task runs once, when the application starts.
        val f = fixture("stale-boot")
        val gone = TestThreads.post(f.room, 1, f.author, "going")
        derivedRows(f, gone)
        tombstone(gone, f.author)
        assertEquals(4, derivedCount(gone))

        usePostgresTestcontainer()
        awaitBootRun()
        assertEquals(0, derivedCount(gone), "the boot task removed the rows a rollback left")
    }
}
