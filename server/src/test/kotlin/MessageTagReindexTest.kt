package ch.nokillswit

import ch.nokillswit.threads.MessageRequest
import ch.nokillswit.threads.MessageTagReindexKey
import ch.nokillswit.threads.MessageTagService
import ch.nokillswit.threads.TAGS_VERSION
import ch.nokillswit.users.UserRole
import ch.qos.logback.classic.Logger
import io.ktor.client.request.delete
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * The boot reindex of the hashtag index (`MessageTagService.reindexStale`, `chat-model.md` "Hashtags (V15)"): messages written before
 * V15 (`tags_version` 0, no `message_tags` row) are indexed once, in batches, `FOR NO KEY UPDATE SKIP LOCKED`; a message a writer holds
 * is skipped and found by the next run; a failure logs class names only and rolls the batch back. A "message from before the index" is a
 * real post whose rows are then removed and whose version is reset by raw SQL. Rooms and users are shared suite state: unique rows,
 * removed afterwards.
 */
class MessageTagReindexTest {
    private val mintedRooms = mutableListOf<UInt>()
    private val mintedUsers = mutableListOf<UInt>()
    private val mintedTemplates = mutableListOf<UInt>()

    @AfterTest
    fun cleanUp() = runBlocking {
        TestRooms.purge(mintedRooms)
        mintedUsers.forEach { TestUsers.softDelete(it) }
        mintedTemplates.forEach { TestTemplates.delete(it) }
    }

    private class Fixture(val room: UInt, val threadId: UInt, val author: UInt, val email: String)

    /** A room with one thread, seeded straight through the services: no client, so the application is NOT started by it. */
    private suspend fun fixture(prefix: String): Fixture {
        val email = uniqueEmail(prefix)
        val author = TestUsers.seed(email = email, password = "pw", role = UserRole.USER, name = "Reindex").also { mintedUsers += it }
        val template = TestThreads.seedTemplate().also { mintedTemplates += it }
        val room = TestRooms.seed(author, template, "Reidx ${UUID.randomUUID().toString().substring(0, 8)}").also { mintedRooms += it }
        val thread = TestThreads.seed(room, author)
        return Fixture(room, thread.id, author, email)
    }

    /** A message as 0.4.0 left it: its body, no tag row, `tags_version` 0. */
    private suspend fun Fixture.staleMessage(body: String): UInt {
        val id = TestThreads.post(room, 1, author, body)
        exec("DELETE FROM message_tags WHERE message_id = ?", id)
        exec("UPDATE messages SET tags_version = 0 WHERE id = ?", id)
        return id
    }

    private fun exec(sql: String, id: UInt) = PostgresTestSupport.connect().use { c ->
        c.prepareStatement(sql).use { st ->
            st.setInt(1, id.toInt())
            st.executeUpdate()
        }
    }

    private fun tagsOf(id: UInt): List<String> = PostgresTestSupport.connect().use { c ->
        c.prepareStatement("SELECT tag FROM message_tags WHERE message_id = ? ORDER BY tag").use { st ->
            st.setInt(1, id.toInt())
            st.executeQuery().use { rs -> generateSequence { if (rs.next()) rs.getString(1) else null }.toList() }
        }
    }

    private fun versionOf(id: UInt): Int = PostgresTestSupport.connect().use { c ->
        c.prepareStatement("SELECT tags_version FROM messages WHERE id = ?").use { st ->
            st.setInt(1, id.toInt())
            st.executeQuery().use { rs -> rs.next(); rs.getInt(1) }
        }
    }

    /** What a 0.4.0 binary does to a message it deletes: the tombstone, with the tag rows (and the version) untouched. */
    private fun tombstone(id: UInt, author: UInt) = PostgresTestSupport.connect().use { c ->
        c.prepareStatement("UPDATE messages SET body = '', marked_as_deleted = TRUE, deleted_by = ? WHERE id = ?").use { st ->
            st.setLong(1, author.toLong())
            st.setInt(2, id.toInt())
            st.executeUpdate()
        }
    }

    private val service: MessageTagService by lazy { MessageTagService(sharedDatabaseForTests()) }

    @Test
    fun `messages older than the index are indexed once`() = testApplication {
        usePostgresTestcontainer()
        val f = fixture("reidx-once")
        val first = f.staleMessage("old #Wdrożenie and #db-outage")
        val second = f.staleMessage("older #Wdrozenie")
        val plain = f.staleMessage("no tags here")
        assertEquals(listOf(0, 0, 0), listOf(first, second, plain).map(::versionOf))

        assertTrue(service.reindexStale() >= 3)
        assertEquals(listOf("db-outage", "wdrozenie"), tagsOf(first))
        assertEquals(listOf("wdrozenie"), tagsOf(second))
        assertEquals(emptyList(), tagsOf(plain))
        assertEquals(listOf(TAGS_VERSION.toInt()), listOf(first, second, plain).map(::versionOf).distinct())
    }

    @Test
    fun `a second run finds nothing`() = testApplication {
        usePostgresTestcontainer()
        val f = fixture("reidx-twice")
        val message = f.staleMessage("once #only")
        assertTrue(service.reindexStale() >= 1)
        val before = tagsOf(message)
        assertEquals(0, service.reindexStale())
        assertEquals(before, tagsOf(message))
    }

    @Test
    fun `a deleted message is skipped`() = testApplication {
        usePostgresTestcontainer()
        val f = fixture("reidx-del")
        val live = f.staleMessage("live #kept")
        val gone = f.staleMessage("gone #dropped")
        PostgresTestSupport.connect().use { c ->
            c.prepareStatement("UPDATE messages SET body = '', marked_as_deleted = TRUE, deleted_by = ? WHERE id = ?").use { st ->
                st.setLong(1, f.author.toLong())
                st.setInt(2, gone.toInt())
                st.executeUpdate()
            }
        }
        service.reindexStale()
        assertEquals(listOf("kept"), tagsOf(live))
        assertEquals(emptyList(), tagsOf(gone))
        assertEquals(0, versionOf(gone), "a tombstone is never indexed, so it stays at 0")
    }

    @Test
    fun `a row a writer holds is skipped and picked up later`() = testApplication {
        usePostgresTestcontainer()
        val f = fixture("reidx-held")
        val held = f.staleMessage("held #later")
        val free = f.staleMessage("free #now")
        PostgresTestSupport.connect().use { writer ->
            writer.autoCommit = false
            writer.prepareStatement("SELECT id FROM messages WHERE id = ? FOR NO KEY UPDATE").use { st ->
                st.setInt(1, held.toInt())
                st.executeQuery().close()
            }
            try {
                withTimeout(30_000) { service.reindexStale() }
                assertEquals(listOf("now"), tagsOf(free), "the free row is indexed")
                assertEquals(emptyList(), tagsOf(held), "the held row is skipped, not waited for")
                assertEquals(0, versionOf(held))
            } finally {
                writer.rollback()
            }
        }
        assertTrue(service.reindexStale() >= 1)
        assertEquals(listOf("later"), tagsOf(held))
        assertEquals(TAGS_VERSION.toInt(), versionOf(held))
    }

    @Test
    fun `the purge removes the tag rows a 0_4_0 delete left behind and nothing else`() = testApplication {
        usePostgresTestcontainer()
        val f = fixture("reidx-purge")
        val live = TestThreads.post(f.room, 1, f.author, "live #kept")
        val gone = TestThreads.post(f.room, 1, f.author, "creds in #leak-db-pw")
        assertEquals(listOf("leak-db-pw"), tagsOf(gone))
        tombstone(gone, f.author) // the rollback: the delete never touched message_tags, and the row's version stays 1

        assertEquals(1, service.purgeDeletedTags())
        assertEquals(emptyList(), tagsOf(gone))
        assertEquals(listOf("kept"), tagsOf(live))
        assertEquals(0, service.purgeDeletedTags(), "a second run finds nothing")
    }

    @Test
    fun `the purge runs in batches`() = testApplication {
        usePostgresTestcontainer()
        val f = fixture("reidx-purge-many")
        // 1,050 tombstones with one tag row each: more than one purge batch (1,000 messages).
        PostgresTestSupport.connect().use { c ->
            c.createStatement().use { st ->
                st.executeUpdate(
                    "INSERT INTO messages (thread_id, author_id, body, created_at, marked_as_deleted, deleted_by, tags_version) " +
                        "SELECT ${f.threadId}, ${f.author}, '', 1, TRUE, ${f.author}, 1 FROM generate_series(1, 1050)",
                )
                st.executeUpdate(
                    "INSERT INTO message_tags (message_id, thread_id, room_id, tag) " +
                        "SELECT id, thread_id, ${f.room}, 'leftover' FROM messages WHERE thread_id = ${f.threadId} AND marked_as_deleted",
                )
            }
        }
        assertEquals(1050, service.purgeDeletedTags())
        assertEquals(0, service.purgeDeletedTags())
    }

    @Test
    fun `a rollback that edited a message is repaired by resetting the version, as the runbook says`() = testApplication {
        usePostgresTestcontainer()
        val f = fixture("reidx-reset")
        val message = TestThreads.post(f.room, 1, f.author, "before #old")
        // A 0.4.0 edit: the body changes, nothing knows the index, and the version stays 1.
        PostgresTestSupport.connect().use { c ->
            c.prepareStatement("UPDATE messages SET body = 'after #new' WHERE id = ?").use { st ->
                st.setInt(1, message.toInt())
                st.executeUpdate()
            }
        }
        assertEquals(0, service.reindexStale(), "the reindex does not look at an indexed message")
        assertEquals(listOf("old"), tagsOf(message), "the gap the runbook closes")

        PostgresTestSupport.connect().use { c ->
            c.createStatement().use { it.executeUpdate("UPDATE messages SET tags_version = 0 WHERE id = $message") }
        }
        assertEquals(1, service.reindexStale())
        assertEquals(listOf("new"), tagsOf(message))
    }

    @Test
    fun `a delete or an edit that waits out a reindex batch leaves no stale tag behind`() = testApplication {
        usePostgresTestcontainer()
        val f = fixture("reidx-order")
        val client = authedClient(f.email, "pw")
        val doomed = f.staleMessage("secret #leak")
        val edited = f.staleMessage("first #before")
        // A reindex batch holds the message row (FOR NO KEY UPDATE) and writes the tags of the body it read; the write that arrives
        // meanwhile must queue on its row UPDATE and clear or replace those tags AFTER the batch commits.
        suspend fun whileBatchHolds(id: UInt, batchTag: String, write: suspend () -> HttpStatusCode): HttpStatusCode =
            PostgresTestSupport.connect().use { batch ->
                batch.autoCommit = false
                batch.prepareStatement("SELECT id FROM messages WHERE id = ? FOR NO KEY UPDATE").use { st ->
                    st.setInt(1, id.toInt())
                    st.executeQuery().close()
                }
                coroutineScope {
                    val writing = async { write() }
                    awaitLockWait("UPDATE messages")
                    batch.prepareStatement("INSERT INTO message_tags (message_id, thread_id, room_id, tag) VALUES (?, ?, ?, ?)").use { st ->
                        st.setInt(1, id.toInt())
                        st.setInt(2, f.threadId.toInt())
                        st.setInt(3, f.room.toInt())
                        st.setString(4, batchTag)
                        st.executeUpdate()
                    }
                    batch.commit()
                    writing.await()
                }
            }

        val delete = whileBatchHolds(doomed, "leak") { client.delete("/api/v1/rooms/${f.room}/messages/$doomed").status }
        assertEquals(HttpStatusCode.NoContent, delete)
        assertEquals(emptyList(), tagsOf(doomed), "the delete cleared the tag the batch wrote while it waited")

        val edit = whileBatchHolds(edited, "before") {
            client.putJson("/api/v1/rooms/${f.room}/messages/$edited", MessageRequest("now #after")).status
        }
        assertEquals(HttpStatusCode.NoContent, edit)
        assertEquals(listOf("after"), tagsOf(edited), "the edit replaced the tag the batch wrote while it waited")
    }

    @Test
    fun `boot runs it when enabled`() = testApplication {
        configureApp("threads.tagReindexOnBoot" to "true")
        val f = fixture("reidx-boot")
        val message = f.staleMessage("before boot #Boot")
        val deleted = TestThreads.post(f.room, 1, f.author, "deleted by 0.4.0 #leak")
        tombstone(deleted, f.author)
        startApplication()
        val indexed = withTimeout(60_000) { application.attributes[MessageTagReindexKey].await() }
        assertTrue(indexed != null && indexed >= 1, "the boot run indexed the stale message: $indexed")
        assertEquals(listOf("boot"), tagsOf(message))
        assertEquals(emptyList(), tagsOf(deleted), "and removed the rows a deleted message left")
    }

    @Test
    fun `the harness leaves the boot reindex off`() = testApplication {
        usePostgresTestcontainer()
        assertTrue(!application.attributes.contains(MessageTagReindexKey))
    }

    @Test
    fun `a failure logs class names only and leaves the rows stale`() = testApplication {
        val marker = "reidxmarker${UUID.randomUUID().toString().replace("-", "").take(12)}"
        val capture = LogCapture(Logger.ROOT_LOGGER_NAME)
        try {
            configureApp("threads.tagReindexOnBoot" to "true")
            val f = fixture("reidx-fail")
            val message = f.staleMessage("will fail #$marker")
            // The database raises from the tag insert with the tag in its own message, as it can for any bound value.
            PostgresTestSupport.connect().use { admin ->
                admin.createStatement().use { st ->
                    st.execute(
                        "CREATE OR REPLACE FUNCTION reidx_reject() RETURNS trigger LANGUAGE plpgsql AS \$\$ BEGIN " +
                            "IF position('$marker' in NEW.tag) > 0 THEN RAISE EXCEPTION 'rejected tag %', NEW.tag; END IF; " +
                            "RETURN NEW; END \$\$",
                    )
                    st.execute("CREATE TRIGGER reidx_reject_trg BEFORE INSERT ON message_tags FOR EACH ROW EXECUTE FUNCTION reidx_reject()")
                }
            }
            try {
                startApplication()
                val result = withTimeout(60_000) { application.attributes[MessageTagReindexKey].await() }
                assertEquals(null, result, "the reindex reported its failure")
            } finally {
                PostgresTestSupport.connect().use { admin ->
                    admin.createStatement().use { st ->
                        st.execute("DROP TRIGGER IF EXISTS reidx_reject_trg ON message_tags")
                        st.execute("DROP FUNCTION IF EXISTS reidx_reject()")
                    }
                }
            }
            val lines = capture.events.filter { it.loggerName == "ch.nokillswit.threads.MessageTagReindex" }
            assertEquals(1, lines.size, "one failure line: ${lines.map { it.formattedMessage }}")
            assertTrue("P0001" in lines.single().formattedMessage, "the line names the SQLSTATE: ${lines.single().formattedMessage}")
            val leaked = capture.events.filter { marker in it.formattedMessage || marker in (it.throwableProxy?.message ?: "") }
            assertEquals(emptyList(), leaked.map { it.formattedMessage.take(300) }, "a log line carries the tag")
            assertEquals(emptyList(), tagsOf(message), "the batch rolled back")
            assertEquals(0, versionOf(message), "the row stays stale for the next boot")
        } finally {
            capture.detach()
        }
    }
}
