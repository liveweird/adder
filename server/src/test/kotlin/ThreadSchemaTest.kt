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
}
