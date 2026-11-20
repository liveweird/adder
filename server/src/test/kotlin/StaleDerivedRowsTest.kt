package ch.nokillswit

import ch.nokillswit.threads.MessageTagReindexKey
import ch.nokillswit.threads.STALE_DERIVED_TABLES
import ch.nokillswit.threads.StaleDerivedRows
import ch.nokillswit.threads.bootRun
import ch.nokillswit.users.UserRole
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
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

        // Database-wide on purpose: the purge has no scope. It is safe here because every test that leaves a tombstone with derived rows
        // (this class) cleans its own up, the forks have containers of their own, and the classes of one fork run one after another.
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

    @Test
    fun `a message locked by another transaction is skipped without waiting and purged once the lock is gone`() = testApplication {
        usePostgresTestcontainer()
        awaitBootRun()
        val f = fixture("stale-lock")
        val gone = TestThreads.post(f.room, 1, f.author, "going")
        derivedRows(f, gone)
        tombstone(gone, f.author)

        PostgresTestSupport.connect().use { holder ->
            holder.autoCommit = false
            holder.prepareStatement("SELECT id FROM messages WHERE id = ? FOR UPDATE").use { st ->
                st.setInt(1, gone.toInt())
                st.executeQuery().use { it.next() }
            }
            // A purge that waited for the lock would hit the timeout; it answers at once and the message keeps its rows.
            assertEquals(0, withTimeout(20_000) { purge.purge() }, "a locked message is skipped")
            assertEquals(4, derivedCount(gone), "the locked message keeps its rows")
            holder.rollback()
        }
        assertEquals(4, purge.purge(), "the next run takes the message the lock held back")
        assertEquals(0, derivedCount(gone))
    }

    @Test
    fun `a pin of a thread and a thread-level notice have no message and survive`() = testApplication {
        usePostgresTestcontainer()
        awaitBootRun()
        val f = fixture("stale-nomsg")
        val gone = TestThreads.post(f.room, 1, f.author, "going")
        derivedRows(f, gone)
        tombstone(gone, f.author)
        exec(
            "INSERT INTO room_pins (room_id, thread_id, message_id, pinned_by, pinned_at) VALUES (?, ?, NULL, ?, 1)",
            f.room, f.threadId, f.author,
        )
        exec(
            "INSERT INTO notifications (recipient_id, room_id, notification_type, actor_id, thread_id, message_id, created_at) " +
                "VALUES (?, ?, 'THREAD_ASSIGNED', ?, ?, NULL, 1)",
            f.author, f.room, f.author, f.threadId,
        )

        assertEquals(4, purge.purge())
        assertEquals(2, messageLessRows(f.threadId), "the thread's pin and its hand-over notice are still there")
    }

    private fun messageLessRows(threadId: UInt): Int = PostgresTestSupport.connect().use { c ->
        val sql = "SELECT (SELECT count(*) FROM room_pins WHERE thread_id = ? AND message_id IS NULL)" +
            " + (SELECT count(*) FROM notifications WHERE thread_id = ? AND message_id IS NULL)"
        c.prepareStatement(sql).use { st ->
            st.setInt(1, threadId.toInt())
            st.setInt(2, threadId.toInt())
            st.executeQuery().use { rs -> rs.next(); rs.getInt(1) }
        }
    }

    @Test
    fun `a small batch size takes several rounds, each limited to its batch`() = testApplication {
        usePostgresTestcontainer()
        awaitBootRun()
        val f = fixture("stale-rounds")
        exec(
            "INSERT INTO messages (thread_id, author_id, body, created_at, marked_as_deleted, deleted_by, tags_version) " +
                "SELECT ?, ?, '', 1, TRUE, ?, 1 FROM generate_series(1, 5)",
            f.threadId, f.author, f.author,
        )
        exec(
            "INSERT INTO message_mentions (message_id, user_id, thread_id, room_id) SELECT id, ?, thread_id, ? FROM messages " +
                "WHERE thread_id = ? AND marked_as_deleted",
            f.author, f.room, f.threadId,
        )
        // Five messages with one row each, two to a batch: 2 + 2 + 1, then the empty round that ends it.
        assertEquals(listOf(2, 2, 1), StaleDerivedRows(sharedDatabaseForTests(), batchSize = 2).purgeRounds())
        assertEquals(emptyList(), StaleDerivedRows(sharedDatabaseForTests(), batchSize = 2).purgeRounds())
    }

    @Test
    fun `every table that references messages is purged here or named as handled elsewhere`() {
        // The catalog, as MessageDerivedRowsTest asks it: a table a later migration adds fails this until it is dealt with.
        val referencing = PostgresTestSupport.connect().use { c ->
            val sql = "SELECT DISTINCT (SELECT relname FROM pg_class WHERE oid = conrelid) FROM pg_constraint " +
                "WHERE contype = 'f' AND confrelid = 'public.messages'::regclass AND conrelid <> confrelid"
            c.createStatement().use { st ->
                st.executeQuery(sql).use { rs -> buildSet { while (rs.next()) add(rs.getString(1)) } }
            }
        }
        // message_tags: the tag rows of deleted messages go through MessageTagService.purgeDeletedTags, the pass before this one.
        val handledElsewhere = setOf("message_tags")
        assertEquals(STALE_DERIVED_TABLES + handledElsewhere, referencing)
    }

    @Test
    fun `a failing purge does not stop the others`() = runBlocking {
        val ran = mutableListOf<String>()
        // One step of the boot pass: records that it ran, then answers [result], or fails when it is null.
        fun step(name: String, result: Int?): suspend () -> Int = {
            ran += name
            result ?: error("boom")
        }

        // The tag purge fails: the derived purge and the reindex still run, and the reindex answers.
        assertEquals(7, bootRun(step("tags", null), step("derived", 1), step("reindex", 7)))
        assertEquals(listOf("tags", "derived", "reindex"), ran)

        ran.clear()
        // The derived purge fails: the reindex still runs.
        assertEquals(7, bootRun(step("tags", 1), step("derived", null), step("reindex", 7)))
        assertEquals(listOf("tags", "derived", "reindex"), ran)

        ran.clear()
        // The reindex fails: its null says so, the purges ran before it.
        assertNull(bootRun(step("tags", 1), step("derived", 1), step("reindex", null)))
        assertEquals(listOf("tags", "derived", "reindex"), ran)
    }
}
