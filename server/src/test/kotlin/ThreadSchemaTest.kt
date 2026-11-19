package ch.nokillswit

import java.sql.Connection
import java.sql.SQLException
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The V14 constraints, by raw SQL (no service exists yet): the tombstone shape of `messages`, the composite status foreign key, the
 * per-room number, and the room hard-delete cascade that the `NO ACTION` foreign key must let through (`TestRooms.purge` relies on it).
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
}
