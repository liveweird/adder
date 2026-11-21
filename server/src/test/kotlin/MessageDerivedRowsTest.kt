package ch.nokillswit

import ch.nokillswit.authz.CallerPrincipal
import ch.nokillswit.rooms.RoomMemberService
import ch.nokillswit.teams.TeamService
import ch.nokillswit.threads.CreatedThread
import ch.nokillswit.threads.MessageFollower
import ch.nokillswit.threads.MessageMentionService
import ch.nokillswit.threads.MessageRef
import ch.nokillswit.threads.MessageRequest
import ch.nokillswit.threads.MessageService
import ch.nokillswit.threads.MessageTagService
import ch.nokillswit.threads.ThreadCreateRequest
import ch.nokillswit.threads.ThreadService
import ch.nokillswit.users.UserRole
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.selectAll

/** The message row as ANOTHER connection sees it: (body, deleted), or null while the row's transaction has not committed. */
private fun committedRow(messageId: UInt): Pair<String, Boolean>? = PostgresTestSupport.connect().use { c ->
    c.prepareStatement("SELECT body, marked_as_deleted FROM messages WHERE id = ?").use { st ->
        st.setInt(1, messageId.toInt())
        st.executeQuery().use { rs -> if (rs.next()) rs.getString(1) to rs.getBoolean(2) else null }
    }
}

/**
 * What follows a message's write (`threads/MessageFollower.kt`, `persistence.md` "Threads (V14)"): everything derived from a body or a
 * delete changes in the message's own transaction. The catalog (`pg_constraint`) names every table that references `messages`, so a
 * table a later migration adds is asked the same question the day it exists; the recording follower proves WHEN a follower runs.
 * Rooms, templates and users are SHARED suite state: every test mints UNIQUE rows and removes them.
 */
class MessageDerivedRowsTest {
    private val mintedRooms = mutableListOf<UInt>()
    private val mintedUsers = mutableListOf<UInt>()
    private val mintedTemplates = mutableListOf<UInt>()

    @AfterTest
    fun cleanUp() = runBlocking {
        TestRooms.purge(mintedRooms)
        mintedUsers.forEach { TestUsers.softDelete(it) }
        mintedTemplates.forEach { TestTemplates.delete(it) }
    }

    /** A room owned by [owner] (a member, so it may post, edit and delete its own messages). */
    private class World(val room: UInt, val owner: UInt) {
        val caller = CallerPrincipal(owner, "fixture@test", emptySet())
    }

    private suspend fun world(): World {
        val owner = TestUsers.seed(uniqueEmail("derived"), "pw", role = UserRole.USER).also { mintedUsers += it }
        val template = TestThreads.seedTemplate().also { mintedTemplates += it }
        val room = TestRooms.seed(owner, template, "Derived ${UUID.randomUUID().toString().substring(0, 8)}").also { mintedRooms += it }
        return World(room, owner)
    }

    private fun threadRequest(body: String) = ThreadCreateRequest("Derived", body, mapOf("severity" to JsonPrimitive("s1")))

    /** The tables that reference `messages`, with the referencing column of each, as the catalog says (the table itself excluded). */
    private fun referencingTables(): Map<String, List<String>> = PostgresTestSupport.connect().use { connection ->
        val sql = """
            SELECT (SELECT relname FROM pg_class WHERE oid = c.conrelid), a.attname
            FROM pg_constraint c
            JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = ANY (c.conkey)
            WHERE c.contype = 'f' AND c.confrelid = 'public.messages'::regclass AND c.conrelid <> c.confrelid
            ORDER BY 1, 2
        """.trimIndent()
        connection.createStatement().use { st ->
            st.executeQuery(sql).use { rs ->
                buildMap<String, MutableList<String>> {
                    while (rs.next()) getOrPut(rs.getString(1)) { mutableListOf() } += rs.getString(2)
                }
            }
        }
    }

    private fun rowsReferencing(table: String, columns: List<String>, messageId: UInt): Int = PostgresTestSupport.connect().use { c ->
        val where = columns.joinToString(" OR ") { "\"$it\" = ?" }
        c.prepareStatement("SELECT count(*) FROM \"$table\" WHERE $where").use { st ->
            columns.indices.forEach { st.setInt(it + 1, messageId.toInt()) }
            st.executeQuery().use { rs -> rs.next(); rs.getInt(1) }
        }
    }

    private fun messageRows(threadId: UInt): Int = PostgresTestSupport.connect().use { c ->
        c.prepareStatement("SELECT count(*) FROM messages WHERE thread_id = ?").use { st ->
            st.setInt(1, threadId.toInt())
            st.executeQuery().use { rs -> rs.next(); rs.getInt(1) }
        }
    }

    private fun threadRows(room: UInt): Int = PostgresTestSupport.connect().use { c ->
        c.prepareStatement("SELECT count(*) FROM threads WHERE room_id = ?").use { st ->
            st.setInt(1, room.toInt())
            st.executeQuery().use { rs -> rs.next(); rs.getInt(1) }
        }
    }

    /** One follower call: what it was, and the message row as the write itself saw it and as another connection saw it, at that moment. */
    private data class Call(
        val what: String,
        val ref: MessageRef,
        val insideBody: String,
        val insideDeleted: Boolean,
        val outside: Pair<String, Boolean>?,
    )

    private class Recording(private val notifies: List<UInt> = emptyList()) : MessageFollower {
        val calls = mutableListOf<Call>()

        private suspend fun record(what: String, ref: MessageRef) {
            // The same query a follower would run: it must see the row the write has just written.
            val messages = MessageService.Messages
            val row = messages.selectAll().where { messages.id eq ref.messageId }.toList().single()
            calls += Call(what, ref, row[messages.body], row[messages.markedAsDeleted], committedRow(ref.messageId))
        }

        override suspend fun posted(ref: MessageRef, authorId: UInt, body: String, at: Long): List<UInt> {
            record("posted:$authorId:$body", ref)
            return notifies
        }

        override suspend fun edited(ref: MessageRef, authorId: UInt, body: String): List<UInt> {
            record("edited:$authorId:$body", ref)
            return notifies
        }

        override suspend fun deleted(ref: MessageRef) = record("deleted", ref)
    }

    /** Throws from the named calls (`posted`, `edited`, `deleted`) and records nothing. */
    private class Failing(private val on: Set<String>) : MessageFollower {
        private fun fail(what: String): List<UInt> {
            if (what in on) error("the follower failed on $what")
            return emptyList()
        }

        override suspend fun posted(ref: MessageRef, authorId: UInt, body: String, at: Long): List<UInt> = fail("posted")

        override suspend fun edited(ref: MessageRef, authorId: UInt, body: String): List<UInt> = fail("edited")

        override suspend fun deleted(ref: MessageRef) {
            fail("deleted")
        }
    }

    /** Both services over the same followers (a thread's opening message and a post are the two ways a message is inserted). */
    private class Services(followers: List<MessageFollower>) {
        private val database = sharedDatabaseForTests()
        private val members = RoomMemberService(database, TeamService(database))
        val mentions = MessageMentionService(members, TestThreads.noticeWriter)
        val messages = MessageService(database, TestRooms.service, MessageTagService(database), mentions, followers)
        val threads = ThreadService(database, TestRooms.service, TestRooms.workflows, members, TestThreads.events, messages)
    }

    /**
     * Tables whose rows this test arranges for the message it deletes. A table the catalog lists that is not here has no proof it was ever
     * populated, so the test fails until the line that gives a message a row in it is added.
     */
    // The body's `#derived-row`, its mentions of the owner and of a peer in the room, the peer's notice (never the author's own), the
    // pin the test puts on the message and the reaction it seeds on it.
    private val arrangedByTheBody = setOf("message_tags", "message_mentions", "notifications", "room_pins", "message_reactions")

    @Test
    fun `a deleted message keeps no row in any table that references messages`() = runBlocking {
        val w = world()
        val peer = TestUsers.seed(uniqueEmail("derived-peer"), "pw", role = UserRole.USER).also { mintedUsers += it }
        TestRooms.addMember(w.room, peer)
        val seeded = TestThreads.seed(w.room, w.owner)
        val body = MessageRequest("Derived #derived-row <@${w.owner}> <@$peer>")
        val id = TestThreads.messages.post(w.room, seeded.number, w.caller, body).message.id
        TestThreads.pins.setMessagePin(w.room, id, w.caller, true)
        PostgresTestSupport.connect().use { c ->
            c.prepareStatement("INSERT INTO message_reactions (message_id, user_id, emoji, created_at) VALUES (?, ?, ?, 1)").use { st ->
                st.setInt(1, id.toInt())
                st.setLong(2, w.owner.toLong())
                st.setString(3, "\uD83D\uDC4D")
                st.executeUpdate()
            }
        }
        val tables = referencingTables()
        val unknown = tables.keys - arrangedByTheBody
        assertTrue(unknown.isEmpty(), "$unknown reference messages: arrange a row for each here, so the delete below is proven to clear it")
        tables.forEach { (table, columns) ->
            assertTrue(rowsReferencing(table, columns, id) > 0, "$table: the post should have left a row to clear")
        }

        TestThreads.messages.delete(w.room, id, w.caller)

        tables.forEach { (table, columns) ->
            assertEquals(0, rowsReferencing(table, columns, id), "$table keeps a row of a deleted message")
        }
    }

    @Test
    fun `a follower sees a post, a thread's opening message, an edit and a delete inside the write`() = runBlocking {
        val w = world()
        val first = Recording(listOf(5u, 6u))
        val second = Recording(listOf(6u, 7u))
        val services = Services(listOf(first, second))

        val created: CreatedThread = services.threads.create(w.room, w.caller, threadRequest("Opening"))
        assertEquals(listOf(5u, 6u, 7u), created.notified, "the union of what the followers answered, each id once")
        val posted = services.messages.post(w.room, created.number, w.caller, MessageRequest("A reply"))
        assertEquals(listOf(5u, 6u, 7u), posted.notified)
        val id = posted.message.id
        val edit = services.messages.edit(w.room, id, w.caller, MessageRequest("A reply, edited"))
        assertEquals(listOf(5u, 6u, 7u), edit?.notified)
        assertNull(services.messages.edit(w.room, id, w.caller, MessageRequest("A reply, edited")), "the same body is no write and no call")
        services.messages.delete(w.room, id, w.caller)

        // Every follower hears every write, in order; the opening message is a `posted` too (its thread's number rides on the ref).
        val expected = listOf(
            "posted:${w.owner}:Opening", "posted:${w.owner}:A reply", "edited:${w.owner}:A reply, edited", "deleted",
        )
        assertEquals(expected, first.calls.map { it.what })
        assertEquals(expected, second.calls.map { it.what })
        first.calls.forEach {
            assertEquals(w.room, it.ref.roomId)
            assertEquals(created.number, it.ref.threadNumber)
            assertEquals(created.id, it.ref.threadId)
        }
        assertEquals(listOf(created.messageId, id, id, id), first.calls.map { it.ref.messageId })
        // The write saw its own row already (after the row's statement), while nobody else could: it was still uncommitted or unchanged.
        val opening = first.calls[0]
        val reply = first.calls[1]
        val edited = first.calls[2]
        val deleted = first.calls[3]
        assertEquals("Opening" to false, opening.insideBody to opening.insideDeleted)
        assertNull(opening.outside, "the opening message is invisible to another connection until the write commits")
        assertNull(reply.outside)
        assertEquals("A reply" to false, reply.insideBody to reply.insideDeleted)
        assertEquals("A reply, edited" to false, edited.insideBody to edited.insideDeleted)
        assertEquals("A reply" to false, edited.outside, "another connection still reads the old body")
        assertEquals("" to true, deleted.insideBody to deleted.insideDeleted, "the follower runs after the row became a tombstone")
        assertEquals("A reply, edited" to false, deleted.outside, "another connection still reads the live message")
        assertEquals("" to true, committedRow(id), "and once the write committed everybody reads the tombstone")
    }

    @Test
    fun `a follower that throws rolls the message back`() = runBlocking {
        val w = world()
        val seeded = TestThreads.seed(w.room, w.owner)
        val live = TestThreads.post(w.room, seeded.number, w.owner, "Live #kept")
        val threadsBefore = threadRows(w.room)
        val messagesBefore = messageRows(seeded.id)

        fun assertUnchanged() {
            assertEquals(threadsBefore, threadRows(w.room), "no thread was left behind")
            assertEquals(messagesBefore, messageRows(seeded.id), "no message was left behind")
            assertEquals("Live #kept" to false, committedRow(live))
            assertEquals(1, rowsReferencing("message_tags", listOf("message_id"), live), "the tags of the live message are untouched")
        }

        val posting = Services(listOf(Failing(setOf("posted"))))
        assertFailsWith<IllegalStateException> { posting.messages.post(w.room, seeded.number, w.caller, MessageRequest("Never #lands")) }
        assertFailsWith<IllegalStateException> { posting.threads.create(w.room, w.caller, threadRequest("Never")) }
        assertUnchanged()

        assertFailsWith<IllegalStateException> {
            Services(listOf(Failing(setOf("edited")))).messages.edit(w.room, live, w.caller, MessageRequest("Rewritten #other"))
        }
        assertUnchanged()

        assertFailsWith<IllegalStateException> { Services(listOf(Failing(setOf("deleted")))).messages.delete(w.room, live, w.caller) }
        assertUnchanged()
    }
}
