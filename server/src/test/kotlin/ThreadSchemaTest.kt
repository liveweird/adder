package ch.nokillswit

import ch.nokillswit.notifications.MARK_IDS_SEEN_SQL
import ch.nokillswit.notifications.ROOM_SEEN_CHUNK_IDS_SQL
import ch.nokillswit.notifications.ROOM_UNSEEN_CEILING_SQL
import ch.nokillswit.notifications.MARK_THREAD_SEEN_SQL
import ch.nokillswit.threads.HASHTAGS_OF_SQL
import ch.nokillswit.threads.THREAD_UNREAD_SQL
import ch.nokillswit.threads.UNREAD_COUNTS_SQL
import ch.nokillswit.threads.UNREAD_PREDICATE_SQL
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
 * then the V15 tag index, the V16 stored search vectors and their later scoped indexes.
 */
class ThreadSchemaTest {
    private companion object {
        const val CHECK_VIOLATION = "23514"
        const val FK_VIOLATION = "23503"
        const val UNIQUE_VIOLATION = "23505"

        // The notice list's competing person-led indexes, dropped inside a rolled-back transaction so a plan test names its index (a
        // table other tests have filled makes equal-cost siblings tie).
        const val WITHOUT_RECIPIENT_SIBLINGS = "DROP INDEX idx_notifications_recipient; DROP INDEX idx_notifications_recipient_created"
        const val TIME_LED = "DROP INDEX idx_notifications_recipient; DROP INDEX idx_notifications_created"

        // 200 threads of a room no test has (2,000,000,000, ids from 1,500,000,001) with five tags each, analysed, written with foreign
        // keys off (a rolled-back transaction of the shared container's superuser, like `seedTagSpread`); the `before` of a plan test.
        const val SEED_THREAD_TAGS =
            "SET LOCAL session_replication_role = replica; " +
                "INSERT INTO threads (id, room_id, \"number\", title, status_key, owner_id, created_by, last_activity_at, created_at, " +
                "updated_at) " +
                "SELECT 1500000000 + t, 2000000000, t, 'T', 'open', 1, 1, 1, 1, 1 FROM generate_series(1, 200) AS t; " +
                "INSERT INTO thread_tags (thread_id, tag_id, added_at, added_by) " +
                "SELECT 1500000000 + t, k, 1, 1 FROM generate_series(1, 200) AS t, generate_series(1, 5) AS k; " +
                "ANALYZE threads; ANALYZE thread_tags"

        // Ids no sequence has issued (an int4 serial would need 1.5 billion rows): the threads, messages and room the hashtag plan tests
        // seed into a rolled-back transaction, so a row another test committed under a small id cannot reach their assertions.
        const val SEED_BASE = 1_500_000_000
        const val SEED_MESSAGE_BASE = 1_000_000_000
        const val SEED_ROOM = 2_000_000_000
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
    fun `group mentions are unique per message and kind even for the keywords, and go with their message, team and room`() {
        val marker = UUID.randomUUID().toString().take(8)
        PostgresTestSupport.connect().use { c ->
            val user = c.scalar(
                "INSERT INTO users (\"name\", email, password_hash) VALUES ('Schema', ?, 'x') RETURNING id",
                "schema-groups-$marker@test",
            )
            val room = c.scalar(
                "INSERT INTO rooms (\"name\", last_activity_at, created_at, updated_at) VALUES (?, 1, 1, 1) RETURNING id",
                "Schema groups $marker",
            )
            val team = c.scalar(
                "INSERT INTO teams (\"name\", created_at, updated_at) VALUES (?, 1, 1) RETURNING id",
                "Schema groups $marker",
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

                val insert =
                    "INSERT INTO message_group_mentions (message_id, group_kind, team_id, thread_id, room_id) VALUES (?, ?, ?, ?, ?)"
                assertEquals(1, c.exec(insert, first, "TEAM", team, thread, room))
                assertEquals(1, c.exec(insert, first, "EVERYONE", null, thread, room))
                assertEquals(1, c.exec(insert, first, "WATCHERS", null, thread, room))
                assertEquals(1, c.exec(insert, second, "EVERYONE", null, thread, room), "another message may name everyone too")
                assertState(UNIQUE_VIOLATION, "a team once per message") { c.exec(insert, first, "TEAM", team, thread, room) }
                // NULLS NOT DISTINCT: the keyword rows have a null team and are still unique per message and kind.
                assertState(UNIQUE_VIOLATION, "a keyword once per message") { c.exec(insert, first, "EVERYONE", null, thread, room) }
                assertState(FK_VIOLATION, "a group mention needs its message") {
                    c.exec(insert, Int.MAX_VALUE, "EVERYONE", null, thread, room)
                }
                assertState(FK_VIOLATION, "a team mention needs its team") { c.exec(insert, second, "TEAM", Int.MAX_VALUE, thread, room) }
                assertState(FK_VIOLATION, "a group mention needs its thread") {
                    c.exec(insert, second, "WATCHERS", null, Int.MAX_VALUE, room)
                }
                assertState(FK_VIOLATION, "a group mention needs its room") {
                    c.exec(insert, second, "WATCHERS", null, thread, Int.MAX_VALUE)
                }

                assertEquals(1, c.exec("DELETE FROM messages WHERE id = ?", second))
                val ofSecond = c.scalar("SELECT count(*) FROM message_group_mentions WHERE message_id = ?", second)
                assertEquals(0, ofSecond, "a message's rows go with it")
                // Teams are only soft-deleted in the application; a hard delete (never made by it) would take the team rows only.
                assertEquals(1, c.exec("DELETE FROM teams WHERE id = ?", team))
                assertEquals(2, c.scalar("SELECT count(*) FROM message_group_mentions WHERE room_id = ?", room), "the keyword rows stay")
                assertEquals(1, c.exec("DELETE FROM rooms WHERE id = ?", room))
                assertEquals(0, c.scalar("SELECT count(*) FROM message_group_mentions WHERE room_id = ?", room))
            } finally {
                c.exec("DELETE FROM rooms WHERE id = ?", room)
                c.exec("DELETE FROM teams WHERE id = ?", team)
                c.exec("DELETE FROM users WHERE id = ?", user)
            }
        }
    }

    @Test
    fun `the mentionsMe probe reads idx_message_group_mentions_room by room and thread, also as a generic plan`() {
        // The thread list's group part: an EXISTS per listed thread, correlated on the thread's id. The room and the thread lead the
        // index, so each probe touches that thread's few rows alone (never the room's). The watchers branch adds an EXISTS on the caller's
        // watch of that thread, served by the watches' own indexes.
        val sql = "SELECT 1 FROM message_group_mentions g WHERE g.room_id = ? AND g.thread_id = ? AND " +
            "(g.group_kind = 'EVERYONE' OR (g.group_kind = 'TEAM' AND g.team_id = ANY(?)) OR " +
            "(g.group_kind = 'WATCHERS' AND EXISTS (SELECT 1 FROM thread_watches w " +
            "WHERE w.room_id = g.room_id AND w.user_id = ? AND w.thread_id = g.thread_id)))"
        for (mode in listOf("auto", "force_generic_plan")) {
            val plan = explain(sql, "int4, int4, int4[], int8", "1, 1, '{1,2}', 1", mode)
            assertTrue("idx_message_group_mentions_room" in plan, "$mode: the probe uses the index:\n$plan")
            assertTrue(Regex("Index Cond:.*thread_id").containsMatchIn(plan), "$mode: the thread rides the index condition:\n$plan")
            assertTrue("Seq Scan on message_group_mentions" !in plan, "$mode: never a scan of the whole table:\n$plan")
            assertTrue("Seq Scan on thread_watches" !in plan, "$mode: the watch is an index probe:\n$plan")
        }
    }

    @Test
    fun `here snapshots key on message and user, cascade with either, and keep the recipient lookup index`() =
        withThread("here-snapshot") { c, user, thread ->
            val room = c.scalar("SELECT room_id FROM threads WHERE id = ?", thread)
            val other = c.scalar(
                "INSERT INTO users (\"name\", email, password_hash) VALUES ('Here', ?, 'x') RETURNING id",
                "schema-here-${UUID.randomUUID()}@test",
            )
            try {
                val first = c.scalar(INSERT_MESSAGE + " RETURNING id", thread, user, "one", false, null)
                val second = c.scalar(INSERT_MESSAGE + " RETURNING id", thread, user, "two", false, null)
                val insert = "INSERT INTO message_here_mentions (message_id, user_id, thread_id, room_id) VALUES (?, ?, ?, ?)"
                assertEquals(1, c.exec(insert, first, user, thread, room))
                assertEquals(1, c.exec(insert, first, other, thread, room), "one snapshot can hold several recipients")
                assertEquals(1, c.exec(insert, second, user, thread, room), "one recipient can occur in several snapshots")
                assertState(UNIQUE_VIOLATION, "a snapshot contains a recipient once") { c.exec(insert, first, user, thread, room) }
                assertEquals(
                    "CREATE INDEX idx_message_here_mentions_user_room ON public.message_here_mentions USING btree " +
                        "(user_id, room_id, thread_id)",
                    c.text("SELECT indexdef FROM pg_indexes WHERE indexname = 'idx_message_here_mentions_user_room'"),
                )

                assertEquals(1, c.exec("DELETE FROM messages WHERE id = ?", second))
                assertEquals(0, c.scalar("SELECT count(*) FROM message_here_mentions WHERE message_id = ?", second))
                assertEquals(1, c.exec("DELETE FROM users WHERE id = ?", other))
                assertEquals(1, c.scalar("SELECT count(*) FROM message_here_mentions WHERE message_id = ?", first))
            } finally {
                c.exec("DELETE FROM users WHERE id = ?", other)
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
    fun `message search is indexed by its live per-thread expression while title search stays room-scoped`() {
        PostgresTestSupport.connect().use { c ->
            val definition = c.text("SELECT indexdef FROM pg_indexes WHERE indexname = 'idx_messages_thread_search_live'")!!
            assertTrue("USING gist (thread_id, COALESCE(search, ''::tsvector))" in definition, definition)
            assertTrue("WHERE (NOT marked_as_deleted)" in definition, definition)
            assertEquals(
                0,
                c.scalar(
                    "SELECT count(*) FROM pg_index x JOIN pg_class t ON t.oid = x.indrelid " +
                        "JOIN pg_attribute a ON a.attrelid = t.oid AND a.attnum = ANY(x.indkey) " +
                        "WHERE t.relname = 'threads' AND a.attname = 'search'",
                ),
                "title search stays a filter on the room-led thread index",
            )
        }
    }

    @Test
    fun `canonical field filters use the room-scoped jsonb index in custom and generic plans`() {
        PostgresTestSupport.connect().use { c ->
            val definition = c.text("SELECT indexdef FROM pg_indexes WHERE indexname = 'idx_threads_room_fields_canonical'")!!
            assertTrue("USING gin (room_id, fields jsonb_path_ops)" in definition, definition)
            assertTrue("(NOT marked_as_deleted)" in definition, definition)
            assertTrue("(merged_into_thread_number IS NULL)" in definition, definition)
        }
        val sql = "SELECT id FROM threads WHERE room_id = ? AND NOT marked_as_deleted " +
            "AND merged_into_thread_number IS NULL AND (fields @> ? OR fields @> ?) AND fields @> ?"
        for (mode in listOf("auto", "force_generic_plan")) {
            val plan = explain(
                sql,
                "int4, jsonb, jsonb, jsonb",
                "1, '{\"severity\":\"s1\"}', '{\"severity\":\"s2\"}', '{\"labels\":[\"backend\"]}'",
                mode,
                before = "ALTER TABLE threads DROP CONSTRAINT fk_threads_merged_into; " +
                    "DROP INDEX uq_threads_room_number; DROP INDEX idx_threads_room_activity; " +
                    "DROP INDEX idx_threads_room_status; DROP INDEX idx_threads_marked_as_deleted; " +
                    "SET LOCAL enable_seqscan = off",
                restrictPlanner = false,
            )
            assertTrue("idx_threads_room_fields_canonical" in plan, "$mode: the composite GIN serves the filter:\n$plan")
            assertTrue(Regex("Index Cond:.*room_id").containsMatchIn(plan), "$mode: room scope is in the index condition:\n$plan")
            assertTrue("Seq Scan on threads" !in plan, "$mode: it never scans threads globally:\n$plan")
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
            assertEquals(1L, c.scalar("SELECT watching::int FROM thread_watches WHERE thread_id = ? AND user_id = ?", thread, user))
            assertEquals(1, c.exec("UPDATE thread_watches SET watching = FALSE WHERE thread_id = ? AND user_id = ?", thread, user))
            assertEquals(0L, c.scalar("SELECT watching::int FROM thread_watches WHERE thread_id = ? AND user_id = ?", thread, user))
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
    fun `the unread filter's predicate probes idx_messages_thread backwards and thread_reads by its key, also as a generic plan`() {
        // The list's `unread=true` predicate (ThreadUnread.kt) on the room's threads: for each thread past the floor, ONE backward walk of
        // idx_messages_thread that the EXISTS stops at its first hit, and the caller's marker by the primary key (user_id, thread_id).
        // Sequential scans, bitmaps and sorts are off for the same reason as the counts' test (a test table is tiny).
        val sql = "SELECT id FROM threads WHERE room_id = ? AND $UNREAD_PREDICATE_SQL"
        for (mode in listOf("auto", "force_generic_plan")) {
            val plan = explain(sql, "int4, int8, int8, int8", "1, 0, 1, 0", mode)
            assertTrue("idx_messages_thread" in plan, "$mode: the filter reads messages through their thread index:\n$plan")
            assertTrue("Index Scan Backward" in plan, "$mode: newest first, so the first hit ends the walk:\n$plan")
            assertTrue("thread_reads_pkey" in plan, "$mode: the caller's marker is a primary-key probe:\n$plan")
            assertTrue("Seq Scan on messages" !in plan, "$mode: never a scan of messages:\n$plan")
            assertTrue("Seq Scan on thread_reads" !in plan, "$mode: never a scan of thread_reads:\n$plan")
        }
    }

    @Test
    fun `the room-read ceiling and chunk read idx_notifications_unseen_room, never the person's other rooms, also as a generic plan`() {
        // "Mark all as read"'s two statements (`ROOM_UNSEEN_CEILING_SQL`, `ROOM_SEEN_CHUNK_IDS_SQL`) and its stamp (`MARK_IDS_SEEN_SQL`):
        // the person's unseen notices are spread over 30 rooms and the target room is one of them, so a plan that walked the person's
        // unseen rows (V19's `idx_notifications_unseen_thread`) would read 30 rooms' worth; V34's `(recipient_id, room_id, id)` reads
        // this room's alone, in id order, so the ceiling is one probe and the chunk stops at its limit.
        val statements = listOf(
            Triple(ROOM_UNSEEN_CEILING_SQL, "int8, int4", ""),
            Triple(ROOM_SEEN_CHUNK_IDS_SQL, "int8, int4, int4, int4, int8, int4", "0, 2000000000, 5, 1000"),
        )
        for ((sql, types, extra) in statements) {
            for (mode in listOf("auto", "force_generic_plan")) {
                val plan = explain(sql, types, "", mode, seed = { c -> seedRoomsSpread(c, extra) })
                assertTrue("idx_notifications_unseen_room" in plan, "$mode: the person-and-room index:\n$plan")
                assertTrue("idx_notifications_unseen_thread" !in plan, "$mode: not the thread-keyed sibling:\n$plan")
                assertTrue("idx_notifications_created" !in plan, "$mode: not the retention index:\n$plan")
                assertTrue("Seq Scan on notifications" !in plan, "$mode: never a scan of the whole table:\n$plan")
                assertTrue("Sort" !in plan, "$mode: id order comes off the index, no sort:\n$plan")
                if (extra.isNotEmpty()) {
                    assertTrue("id > " in plan, "$mode: the keyset bound is an index condition:\n$plan")
                    assertTrue("LockRows" in plan, "$mode: the rows are locked as they are selected:\n$plan")
                }
            }
        }
        // The stamp (a chunk of 1,000 ids) probes the primary key, on the same spread: never the new index with the ids as its LAST key.
        for (mode in listOf("auto", "force_generic_plan")) {
            val stamp = explain(
                MARK_IDS_SEEN_SQL,
                "int8, int8, int4[]",
                "",
                mode,
                seed = { c ->
                    val (person, room) = seedRoomsSpread(c, "").split(", ")
                    val ids = c.prepareStatement(
                        "SELECT id FROM notifications WHERE recipient_id = ? AND room_id = ? AND seen_at IS NULL ORDER BY id LIMIT 1000",
                    ).use { st ->
                        st.setLong(1, person.toLong())
                        st.setInt(2, room.toInt())
                        st.executeQuery().use { rs -> generateSequence { if (rs.next()) rs.getInt(1) else null }.toList() }
                    }
                    "5, $person, '${ids.joinToString(",", "{", "}")}'"
                },
            )
            assertTrue("notifications_pkey" in stamp, "$mode: the stamp is a primary-key probe of the chunk's ids:\n$stamp")
            assertTrue("Seq Scan on notifications" !in stamp, "$mode: never a scan of the whole table:\n$stamp")
        }
    }

    /**
     * One person with 200 unseen notices in each of 30 rooms and 200 seen ones in each (a notice is a row of the membership's own room).
     * Returns the arguments of the room-read statements for the FIRST room: the person, the room and, after them, [rest].
     */
    private fun seedRoomsSpread(c: Connection, rest: String): String {
        val marker = UUID.randomUUID().toString().take(8)
        val person = c.scalar(
            "INSERT INTO users (\"name\", email, password_hash) VALUES ('Spread', ?, 'x') RETURNING id",
            "spread-$marker@test",
        )
        val rooms = (1..30).map { n ->
            val room = c.scalar(
                "INSERT INTO rooms (\"name\", last_activity_at, created_at, updated_at) VALUES (?, 1, 1, 1) RETURNING id",
                "Spread $n $marker",
            )
            c.exec(
                "INSERT INTO room_statuses (room_id, \"key\", label, colour, category, is_initial, position) " +
                    "VALUES (?, 'open', 'Open', 'BLUE', 'OPEN', TRUE, 0)",
                room,
            )
            c.exec("INSERT INTO room_members (room_id, user_id, \"role\", added_at) VALUES (?, ?, 'MEMBER', 7)", room, person)
            c.exec(
                "INSERT INTO threads (room_id, \"number\", title, status_key, owner_id, created_by, last_activity_at, created_at, " +
                    "updated_at) VALUES (?, 1, 'T', 'open', ?, ?, 1, 1, 1)",
                room, person, person,
            )
            room
        }
        c.exec(
            "INSERT INTO notifications (recipient_id, room_id, notification_type, actor_id, thread_id, created_at, seen_at) " +
                "SELECT ?, t.room_id, 'THREAD_ASSIGNED', ?, t.id, 1, CASE WHEN k <= 200 THEN 2 END " +
                "FROM threads t CROSS JOIN generate_series(1, 400) AS k WHERE t.room_id = ANY (?)",
            person, person, c.createArrayOf("integer", rooms.map { it.toInt() }.toTypedArray()),
        )
        c.exec("ANALYZE notifications")
        val tail = if (rest.isEmpty()) "" else ", $rest"
        return "$person, ${rooms.first()}$tail"
    }

    @Test
    fun `a page's hashtags are one probe per thread of idx_message_tags_thread, ordered by the index, also as a generic plan`() {
        // V31: `WHERE thread_id = t.id ORDER BY tag LIMIT 20` per listed thread. The thread leads the index and the tag follows, so the
        // keys come out ordered (no sort, the limit stops early) and the room's tag index (V15, room first) is never walked for it.
        // Unlike the other plan pins this one leaves the planner alone (no sequential-scan switch): on a seeded spread of 100 threads and
        // 20,000 tag rows of ONE room (the shape that made the room-first index useless) the planner chooses the thread index by itself.
        for (mode in listOf("auto", "force_generic_plan")) {
            val plan = explain(HASHTAGS_OF_SQL, "int4[]", "", mode, restrictPlanner = false, seed = ::seedTagSpread)
            assertTrue("idx_message_tags_thread" in plan, "$mode: the probe reads the thread index:\n$plan")
            assertTrue(Regex("Index Cond:.*thread_id").containsMatchIn(plan), "$mode: the thread rides the index condition:\n$plan")
            assertTrue("idx_message_tags_room_tag" !in plan, "$mode: never the room's index:\n$plan")
            assertTrue("Seq Scan on message_tags" !in plan, "$mode: never a scan of the whole table:\n$plan")
        }
    }

    @Test
    fun `a thread of few tags and many rows costs a bounded loop of index probes, never a walk of its rows, also as a generic plan`() {
        // The loose index scan of V31's index: the first thread holds 3 distinct tags over 60,000 rows (a `SELECT DISTINCT ... LIMIT 20`
        // read every one of them, about 20,000 index entries a tag); the second holds 30 tags over 300 rows. Run for real (EXPLAIN
        // ANALYZE), the index hands over a handful of entries, one per probe, whatever the row count. The threads and messages are ids
        // nobody is issued (`SEED_BASE`: the sequences never get there), so no other test's committed rows can reach the assertions.
        for (mode in listOf("auto", "force_generic_plan")) {
            val plan = explain(
                HASHTAGS_OF_SQL, "int4[]", "", mode, restrictPlanner = false, analyze = true, seed = ::seedFewTagsManyRows,
            )
            assertTrue("Recursive Union" in plan, "$mode: the keys are a recursive chain of probes:\n$plan")
            assertTrue("Seq Scan on message_tags" !in plan, "$mode: never a scan of the whole table:\n$plan")
            assertTrue("idx_message_tags_room_tag" !in plan, "$mode: never the room's index:\n$plan")
            val probes = Regex("Index Only Scan using idx_message_tags_thread.*\\(actual rows=([0-9.]+) loops=(\\d+)\\)")
                .findAll(plan).map { it.groupValues[1].toDouble() * it.groupValues[2].toInt() }.toList()
            assertTrue(probes.isNotEmpty(), "$mode: the thread index is read:\n$plan")
            assertTrue(Regex("Index Cond:.*tag >").containsMatchIn(plan), "$mode: each next key is a chained `tag >` probe:\n$plan")
            // At most 20 probes per listed thread (3 threads) plus the first key of each: 63 entries, against ~60,000 for a walk.
            assertTrue(probes.sum() <= 63, "$mode: no more than one index entry per probe (${probes.sum()} read):\n$plan")
        }
        // The keys themselves: thread 1 its 3 tags, thread 2 its first 20 of 30 (key order), the empty thread 3 nothing.
        PostgresTestSupport.connect().use { c ->
            c.autoCommit = false
            try {
                seedFewTagsManyRows(c)
                val rows = c.prepareStatement(HASHTAGS_OF_SQL).use { st ->
                    st.setArray(1, c.createArrayOf("integer", arrayOf(SEED_BASE + 1, SEED_BASE + 2, SEED_BASE + 3)))
                    st.executeQuery().use { rs -> generateSequence { if (rs.next()) rs.getInt(1) to rs.getString(2) else null }.toList() }
                }
                val byThread = rows.groupBy({ it.first }, { it.second })
                assertEquals(listOf("tag0", "tag1", "tag2"), byThread[SEED_BASE + 1])
                assertEquals(20, byThread.getValue(SEED_BASE + 2).size)
                assertEquals(byThread.getValue(SEED_BASE + 2).sorted(), byThread.getValue(SEED_BASE + 2), "key order")
                assertEquals(byThread.getValue(SEED_BASE + 2).distinct(), byThread.getValue(SEED_BASE + 2), "distinct")
                assertTrue(SEED_BASE + 3 !in byThread, "a thread with no hashtag is absent")
            } finally {
                c.rollback()
            }
        }
    }

    /**
     * Thread `SEED_BASE + 1`: 3 tags x 20,000 messages in ONE room (60,000 rows); `+ 2`: 30 tags x 10 messages; `+ 3`: none, analysed,
     * with foreign keys off inside the rolled-back transaction. The thread, room and message ids are ones the sequences never issue.
     * Returns the statement's argument: the three thread ids.
     */
    private fun seedFewTagsManyRows(c: Connection): String {
        c.createStatement().use { st ->
            st.execute("SET LOCAL session_replication_role = replica")
            st.execute(
                "INSERT INTO message_tags (message_id, thread_id, room_id, tag) " +
                    "SELECT $SEED_MESSAGE_BASE + k, $SEED_BASE + 1, $SEED_ROOM, 'tag' || (k % 3) FROM generate_series(1, 60000) AS k",
            )
            st.execute(
                "INSERT INTO message_tags (message_id, thread_id, room_id, tag) " +
                    "SELECT $SEED_MESSAGE_BASE + 100000 + k, $SEED_BASE + 2, $SEED_ROOM, 'tag' || (k % 30) " +
                    "FROM generate_series(1, 300) AS k",
            )
            st.execute("ANALYZE message_tags")
        }
        return "'{${SEED_BASE + 1},${SEED_BASE + 2},${SEED_BASE + 3}}'"
    }

    /**
     * 100 threads of ONE room with 200 tag rows each over 30 tags, analysed: the spread of a room whose rows all share `room_id`. The rows
     * are written with foreign keys off (`session_replication_role`, the shared container's user is a superuser) inside the rolled-back
     * transaction. Returns the statement's argument: three thread ids.
     */
    private fun seedTagSpread(c: Connection): String {
        c.createStatement().use { st ->
            st.execute("SET LOCAL session_replication_role = replica")
            st.execute(
                "INSERT INTO message_tags (message_id, thread_id, room_id, tag) " +
                    "SELECT ($SEED_MESSAGE_BASE + t * 1000 + k), $SEED_BASE + t, $SEED_ROOM, 'tag' || (k % 30) " +
                    "FROM generate_series(1, 100) AS t, generate_series(1, 200) AS k",
            )
            st.execute("ANALYZE message_tags")
        }
        return "'{${SEED_BASE + 1},${SEED_BASE + 50},${SEED_BASE + 100}}'"
    }

    @Test
    fun `thread tags key on thread and tag, name a real person, take no tag delete, and go with their thread`() {
        withThread("threadtags") { c, user, thread ->
            val suffix = UUID.randomUUID().toString().take(8)
            val category = c.scalar(
                "INSERT INTO tag_categories (\"name\", created_at, updated_at) VALUES (?, 1, 1) RETURNING id",
                "Schema $suffix",
            )
            val tag = c.scalar("INSERT INTO tags (category_id, \"name\") VALUES (?, ?) RETURNING id", category, "schema-$suffix")
            try {
                val insert = "INSERT INTO thread_tags (thread_id, tag_id, added_at, added_by) VALUES (?, ?, 1, ?)"
                assertState(FK_VIOLATION, "a tag of the registry") { c.exec(insert, thread, tag + 1_000_000, user) }
                assertState(FK_VIOLATION, "a thread that exists") { c.exec(insert, thread + 1_000_000, tag, user) }
                assertState(FK_VIOLATION, "a person that exists") { c.exec(insert, thread, tag, user + 1_000_000) }
                assertEquals(1, c.exec(insert, thread, tag, user))
                assertState(UNIQUE_VIOLATION, "one row per thread and tag") { c.exec(insert, thread, tag, user) }
                // `tag_id` has no ON DELETE rule: a tag is only ever retired, so erasing one a thread carries is refused.
                assertState(FK_VIOLATION, "a tag is never erased while a thread carries it") {
                    c.exec("DELETE FROM tags WHERE id = ?", tag)
                }
                assertEquals(1, c.exec("DELETE FROM threads WHERE id = ?", thread))
                assertEquals(0L, c.scalar("SELECT count(*) FROM thread_tags WHERE tag_id = ?", tag), "the thread took its tag rows")
            } finally {
                c.exec("DELETE FROM thread_tags WHERE tag_id = ?", tag)
                c.exec("DELETE FROM tags WHERE id = ?", tag)
                c.exec("DELETE FROM tag_categories WHERE id = ?", category)
            }
        }
    }

    @Test
    fun `the threadTag filter and a page's tags are index probes of thread_tags, never a scan, also as a generic plan`() {
        // The list's `threadTag` predicate is a correlated SCALAR probe, `(SELECT 1 FROM thread_tags WHERE thread_id = threads.id AND
        // tag_id IN (...) LIMIT 1) IS NOT NULL` (`threadCarriesAnyThreadTag`: never an `EXISTS`, which the planner may flatten into a
        // semi-join that starts from the tag side and so takes time from the tag's GLOBAL usage), and a page's tags are one
        // `thread_id = ANY(...)` read joined to `tags`: the primary key (thread_id, tag_id) answers both. On a seeded spread of 200
        // threads with five tags each, sequential scans off, the plan names an index of the table and never scans it whole.
        val filter = "SELECT t.id FROM threads t WHERE t.room_id = ? AND " +
            "(SELECT 1 FROM thread_tags tt WHERE tt.thread_id = t.id AND tt.tag_id IN (?, ?) LIMIT 1) IS NOT NULL"
        val page = "SELECT tt.thread_id, tg.\"name\", tg.marked_as_deleted FROM thread_tags tt JOIN tags tg ON tg.id = tt.tag_id " +
            "WHERE tt.thread_id = ANY(?)"
        for (mode in listOf("auto", "force_generic_plan")) {
            val statements = listOf(
                Triple(filter, "int4, int4, int4", "2000000000, 2, 3"),
                Triple(page, "int4[]", "'{1500000001,1500000050,1500000100}'"),
            )
            for ((sql, types, args) in statements) {
                val plan = explain(sql, types, args, mode, restrictPlanner = true, before = SEED_THREAD_TAGS)
                assertTrue("thread_tags_pkey" in plan || "idx_thread_tags_tag" in plan, "$mode: an index of thread_tags:\n$plan")
                assertTrue("Seq Scan on thread_tags" !in plan, "$mode: never a scan of the whole table:\n$plan")
                if (sql === filter) {
                    assertTrue("SubPlan" in plan, "$mode: a per-thread probe, not a flattened join:\n$plan")
                    assertTrue("Semi Join" !in plan && "Hash" !in plan, "$mode: never a join that starts from the tag side:\n$plan")
                }
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
            // Nothing is dropped: the index must be CHOSEN against its siblings, on statistics that do not depend on what other tests left
            // in the table (a realistic spread is seeded and analysed inside the rolled-back transaction).
            val plan = explain(MARK_THREAD_SEEN_SQL, "int8, int8, int4, int4", "", mode, seed = ::seedUnseenSpread)
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
            val plan = explain(count, "int8, int4[]", "1, '{1,2}'", mode, before = WITHOUT_RECIPIENT_SIBLINGS)
            assertTrue("idx_notifications_recipient_type" in plan, "$mode: the kind filter reads the person-and-kind index:\n$plan")
            assertTrue("Seq Scan on notifications" !in plan, "$mode: never a scan of the whole table:\n$plan")
            val pagePlan = explain(page, "int8, int4[]", "1, '{1,2}'", mode)
            assertTrue("Seq Scan on notifications" !in pagePlan, "$mode: the page never scans the table either:\n$pagePlan")
        }
    }

    @Test
    fun `the notification list's createdAt sort and its seen=true filter are index-served, also as a generic plan`() {
        // The two list shapes the id/type indexes do not serve (the 1.1.0 branch audit, L1). `sort=createdAt` orders by (created_at, id):
        // idx_notifications_recipient_created is the only index that leads with the person and the time, so with the id index out of the
        // way the page must come off it with NO sort node (`-createdAt` adds a descending time and an ascending id tiebreaker: the
        // index walks backward and only an INCREMENTAL sort per equal time remains, which is bounded by the LIMIT). `seen=true` has no
        // other bounded path: the person's seen rows are found through idx_notifications_seen, never by walking their unseen history
        // to the 1,001st match; the other indexes are dropped so the planner has nothing else to choose.
        val kinds = ch.nokillswit.notifications.NotificationType.entries.joinToString { "'${it.name}'" }
        val where = "recipient_id = ? AND room_id = ANY(?) AND in_app AND notification_type IN ($kinds)"
        val ascending = "SELECT id FROM notifications WHERE $where ORDER BY created_at, id LIMIT 20"
        val descending = "SELECT id FROM notifications WHERE $where ORDER BY created_at DESC, id LIMIT 20"
        val seenCount = "SELECT id FROM notifications WHERE $where AND seen_at IS NOT NULL LIMIT 1001"
        val seenPage = "SELECT id FROM notifications WHERE $where AND seen_at IS NOT NULL ORDER BY id DESC LIMIT 20"
        fun hasPlainSort(plan: String) = plan.lines().any { Regex("^(->\\s+)?Sort\\s+\\(").containsMatchIn(it.trim()) } // not "Sort Key:"
        for (mode in listOf("auto", "force_generic_plan")) {
            val up = explain(ascending, "int8, int4[]", "1, '{1,2}'", mode, before = TIME_LED)
            assertTrue("idx_notifications_recipient_created" in up, "$mode: the time sort reads the person-and-time index:\n$up")
            assertTrue(!hasPlainSort(up) && "Seq Scan on notifications" !in up, "$mode: served in order, no sort, no table scan:\n$up")
            val down = explain(descending, "int8, int4[]", "1, '{1,2}'", mode, before = TIME_LED)
            assertTrue("idx_notifications_recipient_created" in down, "$mode: the descending sort reads it backward:\n$down")
            assertTrue(!hasPlainSort(down) && "Seq Scan on notifications" !in down, "$mode: at most an incremental sort:\n$down")

            val others = "$TIME_LED; DROP INDEX idx_notifications_recipient_created; DROP INDEX idx_notifications_recipient_type"
            val count = explain(seenCount, "int8, int4[]", "1, '{1,2}'", mode, before = others)
            assertTrue("idx_notifications_seen" in count, "$mode: the capped count of seen rows reads the seen index:\n$count")
            val page = explain(seenPage, "int8, int4[]", "1, '{1,2}'", mode, before = others)
            assertTrue("idx_notifications_seen" in page && "Seq Scan on notifications" !in page, "$mode: and so does the page:\n$page")
        }
    }

    /**
     * Five recipients with 16 notices in each of 50 threads (14 of them seen, so a recipient's unseen rows of one thread are 2 of 800):
     * the spread in which the person-and-thread partial index is the cheap way and the person-only index the dear one. Returns the
     * statement's arguments for one recipient and thread (the instant, the person, the thread, a message bound above any).
     */
    private fun seedUnseenSpread(c: Connection): String {
        val marker = UUID.randomUUID().toString().take(8)
        val people = (1..5).map { n ->
            c.scalar("INSERT INTO users (\"name\", email, password_hash) VALUES ('Spread', ?, 'x') RETURNING id", "spread-$n-$marker@test")
        }
        val room = c.scalar(
            "INSERT INTO rooms (\"name\", last_activity_at, created_at, updated_at) VALUES (?, 1, 1, 1) RETURNING id",
            "Spread $marker",
        )
        c.exec(
            "INSERT INTO room_statuses (room_id, \"key\", label, colour, category, is_initial, position) " +
                "VALUES (?, 'open', 'Open', 'BLUE', 'OPEN', TRUE, 0)",
            room,
        )
        val ids = people.joinToString(",", "ARRAY[", "]::bigint[]")
        c.exec("INSERT INTO room_members (room_id, user_id, \"role\", added_at) SELECT ?, u, 'MEMBER', 7 FROM unnest($ids) AS u", room)
        c.exec(
            "INSERT INTO threads (room_id, \"number\", title, status_key, owner_id, created_by, last_activity_at, created_at, " +
                "updated_at) SELECT ?, n, 'T', 'open', ?, ?, 1, 1, 1 FROM generate_series(1, 50) AS n",
            room, people[0], people[0],
        )
        c.exec(
            "INSERT INTO notifications (recipient_id, room_id, notification_type, actor_id, thread_id, created_at, seen_at) " +
                "SELECT u, ?, 'THREAD_ASSIGNED', ?, t.id, 1, CASE WHEN k <= 14 THEN 2 END " +
                "FROM unnest($ids) AS u CROSS JOIN threads t CROSS JOIN generate_series(1, 16) AS k WHERE t.room_id = ?",
            room, people[0], room,
        )
        c.exec("ANALYZE notifications")
        val thread = c.scalar("SELECT min(id) FROM threads WHERE room_id = ?", room)
        return "5, ${people[1]}, $thread, 1000000000"
    }

    @Test
    fun `a quote is a self-reference that takes NULL, survives a soft delete and nulls itself on a hard delete`() =
        withThread("quote") { c, user, thread ->
            val insert = "INSERT INTO messages (thread_id, author_id, body, created_at, quoted_message_id) VALUES (?, ?, ?, 1, ?) " +
                "RETURNING id"
            val quoted = c.scalar(insert, thread, user, "The quoted words", null)
            val quoting = c.scalar(insert, thread, user, "A reply", quoted)
            val untouched = c.scalar(insert, thread, user, "Another reply", null)
            val quotes = "SELECT count(*) FROM messages WHERE id = ? AND quoted_message_id IS NOT NULL"
            assertEquals(0, c.scalar(quotes, untouched), "NULL is allowed")
            assertState(FK_VIOLATION, "a quote names a message that exists") {
                c.scalar(insert, thread, user, "Dangling", quoted + 1_000_000_000)
            }
            assertEquals(
                "CREATE INDEX idx_messages_quoted ON public.messages USING btree (quoted_message_id) WHERE (quoted_message_id IS NOT NULL)",
                c.text("SELECT indexdef FROM pg_indexes WHERE indexname = 'idx_messages_quoted'"),
                "the index is partial: only quoting messages are in it",
            )

            // The application soft-deletes (an UPDATE): the reference stays, so the quote reads as a deleted quote.
            val tombstone = "UPDATE messages SET body = '', marked_as_deleted = TRUE, deleted_by = ? WHERE id = ?"
            assertEquals(1, c.exec(tombstone, user, quoted))
            assertEquals(quoted, c.scalar("SELECT quoted_message_id FROM messages WHERE id = ?", quoting), "a soft delete keeps it")

            // A HARD delete (a test purge, a cascade) nulls the reference and keeps the quoting row.
            assertEquals(1, c.exec("DELETE FROM messages WHERE id = ?", quoted))
            assertEquals(1, c.scalar("SELECT count(*) FROM messages WHERE id = ?", quoting), "the quoting message outlives the quoted row")
            assertEquals(0, c.scalar(quotes, quoting))
        }

    @Test
    fun `a thread delete cascading through quoting and quoted messages together is not blocked`() =
        withThread("quote-cascade") { c, user, thread ->
            val insert = "INSERT INTO messages (thread_id, author_id, body, created_at, quoted_message_id) VALUES (?, ?, 'x', 1, ?) " +
                "RETURNING id"
            val first = c.scalar(insert, thread, user, null)
            val second = c.scalar(insert, thread, user, first)
            c.scalar(insert, thread, user, second)
            c.scalar(insert, thread, user, first)
            assertEquals(1, c.exec("DELETE FROM threads WHERE id = ?", thread), "the cascade deletes quoted and quoting rows at once")
            assertEquals(0, c.scalar("SELECT count(*) FROM messages WHERE thread_id = ?", thread))
        }

    @Test
    fun `the quote foreign key's probe reads the partial idx_messages_quoted, also as a generic plan`() {
        // What ON DELETE SET NULL runs for every hard-deleted message (the action's UPDATE) and what a reference check would read: the
        // quoting rows of one message. Without the partial index it is a scan of `messages` per deleted row (a purge of a room of
        // thousands). The plan tests restrict the planner as the others do (a test table is tiny); measured un-restricted on 200,000
        // messages the same plan is chosen by itself (`persistence.md` "Message quotes (V35)").
        val probes = listOf(
            "UPDATE ONLY messages SET quoted_message_id = NULL WHERE ? OPERATOR(pg_catalog.=) quoted_message_id",
            "SELECT 1 FROM ONLY messages x WHERE quoted_message_id OPERATOR(pg_catalog.=) ? FOR KEY SHARE OF x",
        )
        for (sql in probes) {
            for (mode in listOf("auto", "force_generic_plan")) {
                val plan = explain(sql, "int4", "1", mode)
                assertTrue("Index Scan using idx_messages_quoted" in plan, "$mode: the probe reads the partial index:\n$plan")
                assertTrue("Seq Scan on messages" !in plan, "$mode: never a scan of the whole table:\n$plan")
            }
        }
    }

    private fun explain(
        sql: String,
        types: String,
        args: String,
        planCacheMode: String,
        before: String? = null,
        seed: ((Connection) -> String)? = null,
        restrictPlanner: Boolean = true,
        analyze: Boolean = false,
    ): String {
        var n = 0
        val numbered = Regex("[?]").replace(sql) { "\$${++n}" }
        return PostgresTestSupport.connect().use { c ->
            c.autoCommit = false
            try {
                c.createStatement().use { st ->
                    st.execute("SET LOCAL plan_cache_mode = $planCacheMode")
                    if (restrictPlanner) {
                        st.execute("SET LOCAL enable_seqscan = off")
                        st.execute("SET LOCAL enable_bitmapscan = off")
                        st.execute("SET LOCAL enable_sort = off")
                    }
                    val arguments = seed?.invoke(c) ?: args
                    before?.let {
                        // DDL is transactional: the rollback below puts it back. The ACCESS EXCLUSIVE lock waits at most 5 s for a
                        // stray reader.
                        st.execute("SET LOCAL lock_timeout = '5s'")
                        st.execute(it)
                    }
                    st.execute("PREPARE unread_plan($types) AS $numbered")
                    buildString {
                        val explain = if (analyze) "EXPLAIN (ANALYZE, BUFFERS, COSTS OFF, TIMING OFF)" else "EXPLAIN"
                        st.executeQuery("$explain EXECUTE unread_plan($arguments)").use { rs ->
                            while (rs.next()) appendLine(rs.getString(1))
                        }
                    }
                }
            } finally {
                c.rollback()
            }
        }
    }
}
