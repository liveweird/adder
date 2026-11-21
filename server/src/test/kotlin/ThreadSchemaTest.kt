package ch.nokillswit

import ch.nokillswit.notifications.MARK_THREAD_SEEN_SQL
import ch.nokillswit.threads.THREAD_UNREAD_SQL
import ch.nokillswit.threads.UNREAD_COUNTS_SQL
import ch.nokillswit.threads.WATCHING_AMONG_SQL
import java.sql.Connection
import java.sql.SQLException
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

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
                // The migration header claims the users cascade too: a hard-deleted user takes their own mention rows and no one else's.
                assertEquals(1, c.exec("DELETE FROM users WHERE id = ?", other))
                assertEquals(0, c.scalar("SELECT count(*) FROM message_mentions WHERE user_id = ?", other), "a user's rows go with them")
                assertEquals(1, c.scalar("SELECT count(*) FROM message_mentions WHERE room_id = ?", room), "the other person's row stays")
                // The room's hard delete (the test purge) cascades through the thread and the message into the mentions.
                assertEquals(1, c.exec("DELETE FROM rooms WHERE id = ?", room))
                assertEquals(0, c.scalar("SELECT count(*) FROM message_mentions WHERE room_id = ?", room))
            } finally {
                c.exec("DELETE FROM rooms WHERE id = ?", room)
                c.exec("DELETE FROM users WHERE id IN (?, ?)", user, other)
            }
        }
    }

    @Test
    fun `room pins are unique per thread and per message, a thread and its message may both be pinned, and the rows cascade`() {
        withThread("pins") { c, user, thread ->
            val room = c.scalar("SELECT room_id FROM threads WHERE id = ?", thread)
            val first = c.scalar(INSERT_MESSAGE + " RETURNING id", thread, user, "one", false, null)
            val second = c.scalar(INSERT_MESSAGE + " RETURNING id", thread, user, "two", false, null)
            val insertPin = "INSERT INTO room_pins (room_id, thread_id, message_id, pinned_by, pinned_at) VALUES (?, ?, ?, ?, 1)"

            assertEquals(1, c.exec(insertPin, room, thread, null, user), "the thread pin")
            assertEquals(1, c.exec(insertPin, room, thread, first, user), "a message of the same thread, pinned too")
            assertEquals(1, c.exec(insertPin, room, thread, second, user))
            assertState(UNIQUE_VIOLATION, "a thread is pinned once") { c.exec(insertPin, room, thread, null, user) }
            assertState(UNIQUE_VIOLATION, "a message is pinned once") { c.exec(insertPin, room, thread, first, user) }
            assertState(FK_VIOLATION, "a pin needs its message") { c.exec(insertPin, room, thread, Int.MAX_VALUE, user) }
            assertState(FK_VIOLATION, "a pin needs its thread") { c.exec(insertPin, room, Int.MAX_VALUE, null, user) }
            val third = c.scalar(INSERT_MESSAGE + " RETURNING id", thread, user, "three", false, null)
            assertState(FK_VIOLATION, "a pin needs the person who made it") { c.exec(insertPin, room, thread, third, Long.MAX_VALUE) }

            assertEquals(1, c.exec("DELETE FROM messages WHERE id = ?", second))
            assertEquals(0, c.scalar("SELECT count(*) FROM room_pins WHERE message_id = ?", second), "a message's pin goes with it")
            assertEquals(2, c.scalar("SELECT count(*) FROM room_pins WHERE room_id = ?", room))
            assertEquals(1, c.exec("DELETE FROM rooms WHERE id = ?", room))
            assertEquals(0, c.scalar("SELECT count(*) FROM room_pins WHERE room_id = ?", room), "a room's hard delete takes its pins")
        }
    }

    @Test
    fun `notifications key on the membership, are unique per message and recipient, and go with their message and their member`() {
        val marker = UUID.randomUUID().toString().take(8)
        PostgresTestSupport.connect().use { c ->
            val user = c.scalar(
                "INSERT INTO users (\"name\", email, password_hash) VALUES ('Schema', ?, 'x') RETURNING id",
                "schema-notice-$marker@test",
            )
            val other = c.scalar(
                "INSERT INTO users (\"name\", email, password_hash) VALUES ('Schema', ?, 'x') RETURNING id",
                "schema-notice-other-$marker@test",
            )
            val room = c.scalar(
                "INSERT INTO rooms (\"name\", last_activity_at, created_at, updated_at) VALUES (?, 1, 1, 1) RETURNING id",
                "Schema notices $marker",
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
                val message = c.scalar(INSERT_MESSAGE + " RETURNING id", thread, user, "one", false, null)
                val member = "INSERT INTO room_members (room_id, user_id, \"role\", added_at) VALUES (?, ?, 'MEMBER', 1)"
                assertEquals(1, c.exec(member, room, user))
                val insert = "INSERT INTO notifications " +
                    "(recipient_id, room_id, notification_type, actor_id, thread_id, message_id, created_at) VALUES (?, ?, ?, ?, ?, ?, 1)"

                assertState(FK_VIOLATION, "a notice needs its recipient's membership of the room") {
                    c.exec(insert, other, room, "MENTIONED", user, thread, message)
                }
                assertEquals(1, c.exec(insert, user, room, "MENTIONED", other, thread, message))
                assertState(UNIQUE_VIOLATION, "one notice per message and recipient (V27)") {
                    c.exec(insert, user, room, "MENTIONED", other, thread, message)
                }
                assertState(UNIQUE_VIOLATION, "whatever the kind: the most specific reason makes the one notice") {
                    c.exec(insert, user, room, "EVERYONE_MENTIONED", other, thread, message)
                }
                val second = c.scalar(INSERT_MESSAGE + " RETURNING id", thread, user, "two", false, null)
                assertEquals(1, c.exec(insert, user, room, "A_KIND_FROM_THE_FUTURE", other, thread, second), "the kind carries no CHECK")
                assertEquals(1, c.exec(insert, user, room, "THREAD_ASSIGNED", other, thread, null))
                val again = c.exec(insert, user, room, "THREAD_ASSIGNED", other, thread, null)
                assertEquals(1, again, "a hand-over has no message to be unique by")
                assertState(FK_VIOLATION, "a notice needs its message") {
                    c.exec(insert, user, room, "MENTIONED", other, thread, Int.MAX_VALUE)
                }
                assertState(FK_VIOLATION, "a notice needs its actor") {
                    c.exec(insert, user, room, "MENTIONED", Int.MAX_VALUE, thread, null)
                }
                assertEquals(4, c.scalar("SELECT count(*) FROM notifications WHERE room_id = ?", room))

                assertEquals(1, c.exec("DELETE FROM messages WHERE id = ?", message))
                assertEquals(3, c.scalar("SELECT count(*) FROM notifications WHERE room_id = ?", room), "a message's notices go with it")
                // Removing the member takes every notice of that person in the room: the leak rule is in the schema.
                assertEquals(1, c.exec("DELETE FROM room_members WHERE room_id = ? AND user_id = ?", room, user))
                assertEquals(0, c.scalar("SELECT count(*) FROM notifications WHERE room_id = ?", room))
                // The room's hard delete (the test purge) cascades through the members and the threads.
                assertEquals(1, c.exec(member, room, user))
                assertEquals(1, c.exec(insert, user, room, "THREAD_ASSIGNED", other, thread, null))
                assertEquals(1, c.exec("DELETE FROM rooms WHERE id = ?", room))
                assertEquals(0, c.scalar("SELECT count(*) FROM notifications WHERE room_id = ?", room))
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

    @Test
    fun `read state has its defaults, its membership key and goes with the member and the thread`() {
        withThread("reads") { c, user, thread ->
            val room = c.scalar("SELECT room_id FROM threads WHERE id = ?", thread)
            val insertMarker =
                "INSERT INTO thread_reads (user_id, thread_id, room_id, last_read_message_id, read_at) VALUES (?, ?, ?, ?, 1)"
            assertState(FK_VIOLATION, "a marker needs the membership row") { c.exec(insertMarker, user, thread, room, 5) }
            val join = "INSERT INTO room_members (room_id, user_id, \"role\", added_at) VALUES (?, ?, 'MEMBER', 7)"
            assertEquals(1, c.exec(join, room, user))
            val state = "SELECT %s FROM room_members WHERE room_id = ? AND user_id = ?"
            assertEquals(0L, c.scalar(state.format("seen_at"), room, user), "0 = since joining")
            assertEquals(0L, c.scalar(state.format("read_from"), room, user), "0 = since joining")
            assertState(CHECK_VIOLATION, "a marker is a positive message id") { c.exec(insertMarker, user, thread, room, 0) }
            assertEquals(1, c.exec(insertMarker, user, thread, room, 5))
            assertState(UNIQUE_VIOLATION, "one marker per member and thread") { c.exec(insertMarker, user, thread, room, 6) }

            assertEquals(1, c.exec("DELETE FROM room_members WHERE room_id = ? AND user_id = ?", room, user))
            assertEquals(0L, c.scalar("SELECT count(*) FROM thread_reads WHERE user_id = ?", user), "the membership key took the marker")
            assertEquals(1, c.exec(join, room, user))
            assertEquals(1, c.exec(insertMarker, user, thread, room, 5))
            assertEquals(1, c.exec("DELETE FROM threads WHERE id = ?", thread))
            assertEquals(0L, c.scalar("SELECT count(*) FROM thread_reads WHERE user_id = ?", user), "the thread took the marker")
        }
    }

    @Test
    fun `watches have their membership key, one row per member and thread, and go with the member and the thread`() {
        withThread("watches") { c, user, thread ->
            val room = c.scalar("SELECT room_id FROM threads WHERE id = ?", thread)
            val insert = "INSERT INTO thread_watches (thread_id, user_id, room_id, created_at) VALUES (?, ?, ?, 1)"
            assertState(FK_VIOLATION, "a watch needs the membership row") { c.exec(insert, thread, user, room) }
            val join = "INSERT INTO room_members (room_id, user_id, \"role\", added_at) VALUES (?, ?, 'MEMBER', 7)"
            assertEquals(1, c.exec(join, room, user))
            assertState(FK_VIOLATION, "the key names the room the member belongs to") { c.exec(insert, thread, user, room + 1) }
            assertEquals(1, c.exec(insert, thread, user, room))
            assertState(UNIQUE_VIOLATION, "one watch per member and thread") { c.exec(insert, thread, user, room) }

            assertEquals(1, c.exec("DELETE FROM room_members WHERE room_id = ? AND user_id = ?", room, user))
            assertEquals(0L, c.scalar("SELECT count(*) FROM thread_watches WHERE user_id = ?", user), "the membership key took the watch")
            assertEquals(1, c.exec(join, room, user))
            assertEquals(0L, c.scalar("SELECT count(*) FROM thread_watches WHERE user_id = ?", user), "rejoining starts clean")
            assertEquals(1, c.exec(insert, thread, user, room))
            assertEquals(1, c.exec("DELETE FROM threads WHERE id = ?", thread))
            assertEquals(0L, c.scalar("SELECT count(*) FROM thread_watches WHERE user_id = ?", user), "the thread took the watch")
        }
    }

    @Test
    fun `the watching read is an index probe, never a scan of thread_watches, also as a generic plan`() {
        // The list page's one batched read: the person and the room lead idx_thread_watches_user_room and the thread ids ride its last
        // column, so the read touches that person's watches of that room alone. On a tiny test table the planner may as well probe the
        // primary key per thread id (equally an index, and cheaper there), so the pin is "an index of the table, never a sequential scan".
        for (mode in listOf("auto", "force_generic_plan")) {
            val plan = explain(WATCHING_AMONG_SQL, "int8, int4, int4[]", "1, 1, '{1,2}'", mode)
            assertTrue("idx_thread_watches_user_room" in plan || "thread_watches_pkey" in plan, "$mode: the read probes an index:\n$plan")
            assertTrue("Seq Scan on thread_watches" !in plan, "$mode: never a scan of the whole table:\n$plan")
        }
    }

    @Test
    fun `the unread statements read idx_messages_thread, also as a generic plan`() {
        // Each statement as a PREPARED one over its argument types, with sequential scans, bitmap scans and sorts off (a test table is
        // tiny, so its statistics can make a bitmap or forward read plus a sort look cheaper; a real thread of thousands does not): the
        // messages of a thread are read backwards through their thread index, at most 100 index entries a thread, never a table scan.
        val counts = Triple(UNREAD_COUNTS_SQL, "int8, int8, int4[], int8", "0, 1, '{1,2}', 0")
        val one = Triple(THREAD_UNREAD_SQL, "int4, int8, int4, int8", "1, 1, 1, 0")
        for ((sql, types, args) in listOf(counts, one)) {
            for (mode in listOf("auto", "force_generic_plan")) {
                val plan = explain(sql, types, args, mode)
                assertTrue("idx_messages_thread" in plan, "$mode: the count reads messages through their thread index:\n$plan")
                assertTrue("Index Scan Backward" in plan, "$mode: newest first, so the limit stops early:\n$plan")
                assertTrue("Seq Scan on messages" !in plan, "$mode: never a scan of the whole table:\n$plan")
            }
        }
    }

    @Test
    fun `the membership cascade finds a member's watches through idx_thread_watches_user_room, also as a generic plan`() {
        // A member row's delete (a leave, a removal) drops that person's watches of that room by (room_id, user_id), the shape of the
        // foreign key's cascade statement: only the person-and-room index leads with both, the primary key leads with the thread.
        for (mode in listOf("auto", "force_generic_plan")) {
            val plan = explain("SELECT thread_id FROM thread_watches WHERE room_id = ? AND user_id = ?", "int4, int8", "1, 1", mode)
            assertTrue("idx_thread_watches_user_room" in plan, "$mode: the cascade uses the person-and-room index:\n$plan")
            assertTrue("Seq Scan on thread_watches" !in plan, "$mode: never a scan of the whole table:\n$plan")
        }
    }

    @Test
    fun `the thread-read update finds the unseen notices through idx_notifications_unseen_thread, also as a generic plan`() {
        // The read marker's `NoticeWriter.threadRead` statement: the person and the thread lead the partial index (V19), so the
        // write reads only that person's unseen rows of that thread, never the person's whole unseen list nor the table.
        for (mode in listOf("auto", "force_generic_plan")) {
            val plan = explain(MARK_THREAD_SEEN_SQL, "int8, int8, int4, int4", "5, 1, 1, 1", mode)
            assertTrue("idx_notifications_unseen_thread" in plan, "$mode: the update uses the person-and-thread index:\n$plan")
            assertTrue("Seq Scan on notifications" !in plan, "$mode: never a scan of the whole table:\n$plan")
        }
    }

    @Test
    fun `the notification list's type filter is served by idx_notifications_recipient_type, also as a generic plan`() {
        // The shape of `NotificationService.list`'s capped count (Exposed spells it out; the predicate is the same): the person, the known
        // kinds the `type` filter narrows to, and `in_app` (every list read has it, so the index is partial on it). A test table is empty,
        // so the planner would walk the person-only index; with that one out of the way (a rolled-back DROP) the read must be served by the
        // person-and-kind index, the only one that leads with both, and never by a scan of the table. The newest-first page statement
        // keeps the person-only index as an alternative, so only its "no table scan" half is pinned.
        val where = "recipient_id = ? AND room_id = ANY(?) AND in_app AND notification_type IN ('MENTIONED', 'THREAD_ASSIGNED')"
        val count = "SELECT id FROM notifications WHERE $where LIMIT 1001"
        val page = "SELECT id FROM notifications WHERE $where ORDER BY id DESC LIMIT 20"
        for (mode in listOf("auto", "force_generic_plan")) {
            val plan = explain(count, "int8, int4[]", "1, '{1,2}'", mode, before = "DROP INDEX idx_notifications_recipient")
            assertTrue("idx_notifications_recipient_type" in plan, "$mode: the kind filter reads the person-and-kind index:\n$plan")
            assertTrue("Seq Scan on notifications" !in plan, "$mode: never a scan of the whole table:\n$plan")
            val pagePlan = explain(page, "int8, int4[]", "1, '{1,2}'", mode)
            assertTrue("Seq Scan on notifications" !in pagePlan, "$mode: the page never scans the table either:\n$pagePlan")
        }
    }

    private fun explain(sql: String, types: String, args: String, planCacheMode: String, before: String? = null): String {
        var n = 0
        val numbered = Regex("[?]").replace(sql) { "\$${++n}" }
        return PostgresTestSupport.connect().use { c ->
            c.autoCommit = false
            try {
                c.createStatement().use { st ->
                    st.execute("SET LOCAL plan_cache_mode = $planCacheMode")
                    st.execute("SET LOCAL enable_seqscan = off")
                    st.execute("SET LOCAL enable_bitmapscan = off")
                    st.execute("SET LOCAL enable_sort = off")
                    before?.let {
                        // DDL is transactional: the rollback below puts it back. The ACCESS EXCLUSIVE lock waits at most 5 s for a
                        // stray reader.
                        st.execute("SET LOCAL lock_timeout = '5s'")
                        st.execute(it)
                    }
                    st.execute("PREPARE unread_plan($types) AS $numbered")
                    buildString {
                        st.executeQuery("EXPLAIN EXECUTE unread_plan($args)").use { rs -> while (rs.next()) appendLine(rs.getString(1)) }
                    }
                }
            } finally {
                c.rollback()
            }
        }
    }
}
