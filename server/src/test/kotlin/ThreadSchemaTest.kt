package ch.nokillswit

import java.sql.Connection
import java.sql.SQLException
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The V14 constraints, by raw SQL (no service exists yet): the tombstone shape of `messages`, the composite status foreign key, the
 * per-room number, and the room hard-delete cascade that the `NO ACTION` foreign key must let through (`TestRooms.purge` relies on it);
 * then the V15 tag index and the V16 search columns (stored generated vectors over the accent-folding configuration, and no index on them).
 */
class ThreadSchemaTest {
    private companion object {
        const val CHECK_VIOLATION = "23514"
        const val FK_VIOLATION = "23503"
        const val UNIQUE_VIOLATION = "23505"
        const val INSERT_MESSAGE =
            "INSERT INTO messages (thread_id, author_id, body, created_at, marked_as_deleted, deleted_by) VALUES (?, ?, ?, 1, ?, ?)"
    }

    private fun Connection.exec(sql: String, vararg args: Any?): Int = prepareStatement(sql).use { st ->
        args.forEachIndexed { index, arg -> st.setObject(index + 1, arg) }
        st.executeUpdate()
    }

    private fun Connection.scalar(sql: String, vararg args: Any?): Long = prepareStatement(sql).use { st ->
        args.forEachIndexed { index, arg -> st.setObject(index + 1, arg) }
        st.executeQuery().use { rs ->
            rs.next()
            rs.getLong(1)
        }
    }

    private fun assertState(state: String, why: String, block: () -> Unit) {
        assertEquals(state, assertFailsWith<SQLException>(why) { block() }.sqlState, why)
    }

    @Test
    fun `the thread aggregate enforces its shape and a room delete cascades through it`() {
        val marker = UUID.randomUUID().toString().take(8)
        PostgresTestSupport.connect().use { c ->
            val user = c.scalar(
                "INSERT INTO users (\"name\", email, password_hash) VALUES ('Schema', ?, 'x') RETURNING id",
                "schema-$marker@test",
            )
            val room = c.scalar(
                "INSERT INTO rooms (\"name\", last_activity_at, created_at, updated_at) VALUES (?, 1, 1, 1) RETURNING id",
                "Schema $marker",
            )
            try {
                c.exec(
                    "INSERT INTO room_statuses (room_id, \"key\", label, colour, category, is_initial, position) " +
                        "VALUES (?, 'open', 'Open', 'BLUE', 'OPEN', TRUE, 0)",
                    room,
                )
                fun insertThread(number: Int, status: String) = c.scalar(
                    "INSERT INTO threads (room_id, \"number\", title, status_key, owner_id, created_by, " +
                        "last_activity_at, created_at, updated_at) VALUES (?, ?, 'T', ?, ?, ?, 1, 1, 1) RETURNING id",
                    room, number, status, user, user,
                )
                val thread = insertThread(1, "open")
                assertState(UNIQUE_VIOLATION, "a number is unique per room") { insertThread(1, "open") }
                assertState(CHECK_VIOLATION, "a number is positive") { insertThread(0, "open") }
                assertState(FK_VIOLATION, "a thread's status must be one of its room's") { insertThread(2, "nope") }

                assertEquals(1, c.exec(INSERT_MESSAGE, thread, user, "hello", false, null))
                assertEquals(1, c.exec(INSERT_MESSAGE, thread, user, "", true, user))
                val rejected = listOf(
                    "a live message has a body" to listOf<Any?>("", false, null),
                    "a tombstone has an empty body" to listOf<Any?>("x", true, user),
                    "a tombstone records who deleted it" to listOf<Any?>("", true, null),
                    "a live message has no deleter" to listOf<Any?>("x", false, user),
                    "a body is at most 8000 characters" to listOf<Any?>("x".repeat(8001), false, null),
                )
                for ((why, values) in rejected) {
                    assertState(CHECK_VIOLATION, why) { c.exec(INSERT_MESSAGE, thread, user, *values.toTypedArray()) }
                }

                assertEquals(
                    1,
                    c.exec(
                        "INSERT INTO thread_events (thread_id, user_id, created_at, event_type) VALUES (?, ?, 1, 'CREATED')",
                        thread,
                        user,
                    ),
                )
                // The cascade: the room's hard delete takes statuses, threads, messages and events in one statement.
                assertEquals(1, c.exec("DELETE FROM rooms WHERE id = ?", room))
                assertEquals(0, c.scalar("SELECT count(*) FROM room_statuses WHERE room_id = ?", room))
                assertEquals(0, c.scalar("SELECT count(*) FROM threads WHERE room_id = ?", room))
                assertEquals(0, c.scalar("SELECT count(*) FROM messages WHERE thread_id = ?", thread))
                assertEquals(0, c.scalar("SELECT count(*) FROM thread_events WHERE thread_id = ?", thread))
            } finally {
                c.exec("DELETE FROM rooms WHERE id = ?", room)
                c.exec("DELETE FROM users WHERE id = ?", user)
            }
        }
    }

    @Test
    fun `message tags key on message and tag, default the version to 0, and go with their message and their room`() {
        val marker = UUID.randomUUID().toString().take(8)
        PostgresTestSupport.connect().use { c ->
            val user = c.scalar(
                "INSERT INTO users (\"name\", email, password_hash) VALUES ('Schema', ?, 'x') RETURNING id",
                "schema-tags-$marker@test",
            )
            val room = c.scalar(
                "INSERT INTO rooms (\"name\", last_activity_at, created_at, updated_at) VALUES (?, 1, 1, 1) RETURNING id",
                "Schema tags $marker",
            )
            try {
                c.exec(
                    "INSERT INTO room_statuses (room_id, \"key\", label, colour, category, is_initial, position) " +
                        "VALUES (?, 'open', 'Open', 'BLUE', 'OPEN', TRUE, 0)",
                    room,
                )
                val thread = c.scalar(
                    "INSERT INTO threads (room_id, \"number\", title, status_key, owner_id, created_by, " +
                        "last_activity_at, created_at, updated_at) VALUES (?, 1, 'T', 'open', ?, ?, 1, 1, 1) RETURNING id",
                    room, user, user,
                )
                val first = c.scalar(INSERT_MESSAGE + " RETURNING id", thread, user, "one #a", false, null)
                val second = c.scalar(INSERT_MESSAGE + " RETURNING id", thread, user, "two #a", false, null)
                assertEquals(0, c.scalar("SELECT tags_version FROM messages WHERE id = ?", first), "a message starts un-indexed")
                // No CHECK on the version (a new one would scan `messages` under an exclusive lock): only code writes the column.
                assertEquals(1, c.exec("UPDATE messages SET tags_version = 1 WHERE id = ?", second))

                val insertTag = "INSERT INTO message_tags (message_id, thread_id, room_id, tag) VALUES (?, ?, ?, ?)"
                assertEquals(1, c.exec(insertTag, first, thread, room, "a"))
                assertEquals(1, c.exec(insertTag, second, thread, room, "a"), "two messages may carry one tag")
                assertState(UNIQUE_VIOLATION, "a message carries a tag once") { c.exec(insertTag, first, thread, room, "a") }
                assertState(CHECK_VIOLATION, "a tag is not empty") { c.exec(insertTag, first, thread, room, "") }
                assertState("22001", "a tag is at most 150 characters") { c.exec(insertTag, first, thread, room, "x".repeat(151)) }
                assertEquals(1, c.exec(insertTag, first, thread, room, "x".repeat(150)))
                assertState(FK_VIOLATION, "a tag needs its message") { c.exec(insertTag, Int.MAX_VALUE, thread, room, "a") }
                assertState(FK_VIOLATION, "a tag needs its thread") { c.exec(insertTag, second, Int.MAX_VALUE, room, "b") }
                assertState(FK_VIOLATION, "a tag needs its room") { c.exec(insertTag, second, thread, Int.MAX_VALUE, "b") }

                assertEquals(1, c.exec("DELETE FROM messages WHERE id = ?", second))
                assertEquals(0, c.scalar("SELECT count(*) FROM message_tags WHERE message_id = ?", second), "a message's rows go with it")
                assertEquals(2, c.scalar("SELECT count(*) FROM message_tags WHERE room_id = ?", room))
                // The room's hard delete (the test purge) cascades through the thread and the message into the tags.
                assertEquals(1, c.exec("DELETE FROM rooms WHERE id = ?", room))
                assertEquals(0, c.scalar("SELECT count(*) FROM message_tags WHERE room_id = ?", room))
            } finally {
                c.exec("DELETE FROM rooms WHERE id = ?", room)
                c.exec("DELETE FROM users WHERE id = ?", user)
            }
        }
    }

    @Test
    fun `message mentions key on message and user, and go with their message and their room`() {
        val marker = UUID.randomUUID().toString().take(8)
        PostgresTestSupport.connect().use { c ->
            val user = c.scalar(
                "INSERT INTO users (\"name\", email, password_hash) VALUES ('Schema', ?, 'x') RETURNING id",
                "schema-mentions-$marker@test",
            )
            val other = c.scalar(
                "INSERT INTO users (\"name\", email, password_hash) VALUES ('Schema', ?, 'x') RETURNING id",
                "schema-mentions-other-$marker@test",
            )
            val room = c.scalar(
                "INSERT INTO rooms (\"name\", last_activity_at, created_at, updated_at) VALUES (?, 1, 1, 1) RETURNING id",
                "Schema mentions $marker",
            )
            try {
                c.exec(
                    "INSERT INTO room_statuses (room_id, \"key\", label, colour, category, is_initial, position) " +
                        "VALUES (?, 'open', 'Open', 'BLUE', 'OPEN', TRUE, 0)",
                    room,
                )
                val thread = c.scalar(
                    "INSERT INTO threads (room_id, \"number\", title, status_key, owner_id, created_by, " +
                        "last_activity_at, created_at, updated_at) VALUES (?, 1, 'T', 'open', ?, ?, 1, 1, 1) RETURNING id",
                    room, user, user,
                )
                val first = c.scalar(INSERT_MESSAGE + " RETURNING id", thread, user, "one", false, null)
                val second = c.scalar(INSERT_MESSAGE + " RETURNING id", thread, user, "two", false, null)

                val insert = "INSERT INTO message_mentions (message_id, user_id, thread_id, room_id) VALUES (?, ?, ?, ?)"
                assertEquals(1, c.exec(insert, first, user, thread, room))
                assertEquals(1, c.exec(insert, first, other, thread, room), "a message mentions several people")
                assertEquals(1, c.exec(insert, second, user, thread, room), "a person is mentioned in several messages")
                assertState(UNIQUE_VIOLATION, "a message mentions a person once") { c.exec(insert, first, user, thread, room) }
                assertState(FK_VIOLATION, "a mention needs its message") { c.exec(insert, Int.MAX_VALUE, user, thread, room) }
                assertState(FK_VIOLATION, "a mention needs its user") { c.exec(insert, second, Int.MAX_VALUE, thread, room) }
                assertState(FK_VIOLATION, "a mention needs its thread") { c.exec(insert, second, other, Int.MAX_VALUE, room) }
                assertState(FK_VIOLATION, "a mention needs its room") { c.exec(insert, second, other, thread, Int.MAX_VALUE) }

                assertEquals(1, c.exec("DELETE FROM messages WHERE id = ?", second))
                val rowsOfSecond = c.scalar("SELECT count(*) FROM message_mentions WHERE message_id = ?", second)
                assertEquals(0, rowsOfSecond, "a message's rows go with it")
                assertEquals(2, c.scalar("SELECT count(*) FROM message_mentions WHERE room_id = ?", room))
                // The room's hard delete (the test purge) cascades through the thread and the message into the mentions.
                assertEquals(1, c.exec("DELETE FROM rooms WHERE id = ?", room))
                assertEquals(0, c.scalar("SELECT count(*) FROM message_mentions WHERE room_id = ?", room))
            } finally {
                c.exec("DELETE FROM rooms WHERE id = ?", room)
                c.exec("DELETE FROM users WHERE id IN (?, ?)", user, other)
            }
        }
    }

    private fun Connection.text(sql: String, vararg args: Any?): String? = prepareStatement(sql).use { st ->
        args.forEachIndexed { index, arg -> st.setObject(index + 1, arg) }
        st.executeQuery().use { rs ->
            rs.next()
            rs.getString(1)
        }
    }

    /** Runs [block] with a user, a room, its `open` status and thread #1 (all removed afterwards), for the V16 search-column tests. */
    private fun withThread(label: String, block: (c: Connection, user: Long, thread: Long) -> Unit) {
        val marker = UUID.randomUUID().toString().take(8)
        PostgresTestSupport.connect().use { c ->
            val user = c.scalar(
                "INSERT INTO users (\"name\", email, password_hash) VALUES ('Schema', ?, 'x') RETURNING id",
                "schema-$label-$marker@test",
            )
            val room = c.scalar(
                "INSERT INTO rooms (\"name\", last_activity_at, created_at, updated_at) VALUES (?, 1, 1, 1) RETURNING id",
                "Schema $label $marker",
            )
            try {
                c.exec(
                    "INSERT INTO room_statuses (room_id, \"key\", label, colour, category, is_initial, position) " +
                        "VALUES (?, 'open', 'Open', 'BLUE', 'OPEN', TRUE, 0)",
                    room,
                )
                val thread = c.scalar(
                    "INSERT INTO threads (room_id, \"number\", title, status_key, owner_id, created_by, " +
                        "last_activity_at, created_at, updated_at) VALUES (?, 1, 'Żółw nad rzeką', 'open', ?, ?, 1, 1, 1) RETURNING id",
                    room, user, user,
                )
                block(c, user, thread)
            } finally {
                c.exec("DELETE FROM rooms WHERE id = ?", room)
                c.exec("DELETE FROM users WHERE id = ?", user)
            }
        }
    }

    @Test
    fun `the search columns are stored generated columns`() {
        PostgresTestSupport.connect().use { c ->
            // 's' = STORED. PostgreSQL 18 reads a bare GENERATED ALWAYS AS as VIRTUAL ('v'), which cannot be indexed.
            for (table in listOf("messages", "threads")) {
                assertEquals(
                    "s",
                    c.text("SELECT attgenerated::text FROM pg_attribute WHERE attrelid = ?::regclass AND attname = 'search'", table),
                    "$table.search",
                )
            }
        }
    }

    @Test
    fun `the configuration folds accents`() {
        PostgresTestSupport.connect().use { c ->
            fun vector(text: String) = c.text("SELECT to_tsvector('public.adder_search'::regconfig, ?)::text", text)
            assertEquals("'zolw':1", vector("Żółw"))
            assertEquals("'aeiou':1", vector("ÀÉÎÕÜ"))
            assertEquals("'hello':1 'world':2", vector("Hello WORLD"), "ASCII keeps simple's case folding")
            withThread("fold") { conn, _, thread ->
                assertEquals("'nad':2 'rzeka':3 'zolw':1", conn.text("SELECT search::text FROM threads WHERE id = ?", thread))
            }
        }
    }

    @Test
    fun `neither search vector is indexed - the match is a per-thread probe through idx_messages_thread`() {
        PostgresTestSupport.connect().use { c ->
            listOf("messages", "threads").forEach { table ->
                assertEquals(
                    0,
                    c.scalar(
                        "SELECT count(*) FROM pg_index x JOIN pg_class t ON t.oid = x.indrelid " +
                            "JOIN pg_attribute a ON a.attrelid = t.oid AND a.attnum = ANY(x.indkey) " +
                            "WHERE t.relname = '$table' AND a.attname = 'search'",
                    ),
                    "$table.search has no index: the search probes each searched thread's messages through idx_messages_thread " +
                        "(a later index is a deliberate migration with its own measurement, BACKLOG.md)",
                )
            }
            assertEquals(1, c.scalar("SELECT count(*) FROM pg_class WHERE relname = 'idx_messages_thread'"), "the probe's index exists")
        }
    }

    @Test
    fun `a tombstone's vector is empty`() {
        withThread("tomb") { c, user, thread ->
            val message = c.scalar(INSERT_MESSAGE + " RETURNING id", thread, user, "outage in the database", false, null)
            assertEquals("'database':4 'in':2 'outage':1 'the':3", c.text("SELECT search::text FROM messages WHERE id = ?", message))
            // A delete scrubs the body to '' and the generated column follows it in the same statement.
            assertEquals(1, c.exec("UPDATE messages SET body = '', marked_as_deleted = TRUE, deleted_by = ? WHERE id = ?", user, message))
            assertEquals("", c.text("SELECT search::text FROM messages WHERE id = ?", message))
        }
    }

    @Test
    fun `an 8,000-character token stores no vector and no error`() {
        withThread("long") { c, user, thread ->
            // The parser skips a token this long with a NOTICE (a warning, not an error), so the INSERT still succeeds.
            val message = c.scalar(INSERT_MESSAGE + " RETURNING id", thread, user, "a".repeat(8000), false, null)
            assertEquals("", c.text("SELECT search::text FROM messages WHERE id = ?", message))
            val second = c.scalar(INSERT_MESSAGE + " RETURNING id", thread, user, "findable " + "b".repeat(3000), false, null)
            assertEquals("'findable':1", c.text("SELECT search::text FROM messages WHERE id = ?", second), "only the long token is skipped")
        }
    }
}
