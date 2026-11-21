package ch.nokillswit

import java.io.File
import java.sql.Connection
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The SQL of `persistence.md` "Rollback below 1.1.0 (runbook)" runs against the migrated schema, so an operator who pastes it at 3 a.m. is
 * not the first to find a typo. The statements are READ FROM THE DOC (the fenced `sql` blocks that name the table or the index), so the
 * doc and the test cannot drift. Everything runs in ONE transaction that is rolled back: the shared container keeps nothing.
 *
 * What is pinned: the email-off seed writes an explicit "off" row for the two kinds a 1.0.0 binary knows, for every account and for
 * nobody twice, keeps a 1.1 opt-in row as it is, and leaves no email switched ON for a person who never opted in (nobody is mailed after
 * the rollback); the unseen index comes back; the watch backfill of the way back up is idempotent.
 */
class RollbackRunbookTest {
    // `repo.root` is set by server/build.gradle.kts (which also declares the doc as a test input); the `..` fallback covers an IDE run.
    private val doc: String = File(System.getProperty("repo.root") ?: "..", ".claude/docs/persistence.md").also {
        assertTrue(it.exists(), "persistence.md not found (${it.absolutePath})")
    }.readText()

    private fun sqlBlockWith(marker: String): String {
        val blocks = Regex("```sql\\n(.*?)```", RegexOption.DOT_MATCHES_ALL).findAll(doc).map { it.groupValues[1].trimIndent().trim() }
        return blocks.single { marker in it }
    }

    private fun Connection.scalar(sql: String, vararg args: Any?): Long = prepareStatement(sql).use { st ->
        args.forEachIndexed { index, arg -> st.setObject(index + 1, arg) }
        st.executeQuery().use { rs ->
            rs.next()
            rs.getLong(1)
        }
    }

    private fun Connection.exec(sql: String): Int = createStatement().use { it.executeUpdate(sql) }

    private fun Connection.user(marker: String, label: String): Long = scalar(
        "INSERT INTO users (\"name\", email, password_hash) VALUES ('Runbook', ?, 'x') RETURNING id",
        "runbook-$label-$marker@test",
    )

    private fun Connection.emailSwitches(user: Long): List<Triple<String, String, Boolean>> = prepareStatement(
        "SELECT notification_type, channel, enabled FROM user_notification_preferences WHERE user_id = ? ORDER BY 1, 2",
    ).use { st ->
        st.setLong(1, user)
        st.executeQuery().use { rs ->
            buildList { while (rs.next()) add(Triple(rs.getString(1), rs.getString(2), rs.getBoolean(3))) }
        }
    }

    @Test
    fun `the email-off seed writes an explicit off row per account and kind, keeps an opt-in, and mails nobody`() {
        val seed = sqlBlockWith("user_notification_preferences")
        val marker = UUID.randomUUID().toString().take(8)
        PostgresTestSupport.connect().use { c ->
            c.autoCommit = false
            try {
                val plain = c.user(marker, "plain")
                val optedIn = c.user(marker, "optin")
                c.exec(
                    "INSERT INTO user_notification_preferences (user_id, notification_type, channel, enabled) " +
                        "VALUES ($optedIn, 'MENTIONED', 'EMAIL', TRUE)",
                )

                val written = c.exec(seed)
                assertTrue(written >= 3, "rows for the person with none, one for an opt-in, and all others: $written")
                val off = listOf(Triple("MENTIONED", "EMAIL", false), Triple("THREAD_ASSIGNED", "EMAIL", false))
                assertEquals(off, c.emailSwitches(plain), "a person with no row gets an explicit off row for both kinds")
                assertEquals(
                    listOf(Triple("MENTIONED", "EMAIL", true), Triple("THREAD_ASSIGNED", "EMAIL", false)),
                    c.emailSwitches(optedIn),
                    "the stored opt-in stays (a 1.0.0 binary reads the row as off); the other kind is filled in",
                )
                assertEquals(0, c.exec(seed), "run twice: nothing more to write")
            } finally {
                c.rollback()
            }
        }
    }

    @Test
    fun `the unseen index of 1_0_0 comes back and goes, and the watch backfill of the way back up restores a creator's watch`() {
        val index = sqlBlockWith("CREATE INDEX IF NOT EXISTS idx_notifications_unseen")
        val dropIndex = sqlBlockWith("DROP INDEX IF EXISTS idx_notifications_unseen")
        val backfill = sqlBlockWith("INSERT INTO thread_watches")
        PostgresTestSupport.connect().use { c ->
            c.autoCommit = false
            try {
                c.exec(index)
                val present = c.scalar(
                    "SELECT count(*) FROM pg_indexes WHERE tablename = 'notifications' AND indexname = 'idx_notifications_unseen'",
                )
                assertEquals(1L, present, "the 1.0.0 bell index exists again")
                c.exec(index) // IF NOT EXISTS: pasting it twice is harmless

                // A thread started under 1.0.0: its creator is a member, and nothing made them a watcher.
                val creator = c.user(UUID.randomUUID().toString().take(8), "creator")
                val room = c.scalar(
                    "INSERT INTO rooms (\"name\", last_activity_at, created_at, updated_at) VALUES (?, 1, 1, 1) RETURNING id",
                    "Runbook ${UUID.randomUUID()}",
                )
                c.exec(
                    "INSERT INTO room_statuses (room_id, \"key\", label, colour, category, is_initial, position) " +
                        "VALUES ($room, 'open', 'Open', 'BLUE', 'OPEN', TRUE, 0)",
                )
                c.exec("INSERT INTO room_members (room_id, user_id, \"role\", added_at) VALUES ($room, $creator, 'MEMBER', 7)")
                val thread = c.scalar(
                    "INSERT INTO threads (room_id, \"number\", title, status_key, owner_id, created_by, last_activity_at, created_at, " +
                        "updated_at) VALUES ($room, 1, 'T', 'open', $creator, $creator, 1, 1, 1) RETURNING id",
                )
                val watches = "SELECT count(*) FROM thread_watches WHERE thread_id = $thread AND user_id = $creator"
                assertEquals(0L, c.scalar(watches), "no watch yet")
                assertTrue(c.exec(backfill) >= 1, "the creator is a watcher again")
                assertEquals(1L, c.scalar(watches), "one row for the creator, who is also the owner")
                assertEquals(0, c.exec(backfill), "the second run finds every watch already there (ON CONFLICT DO NOTHING)")

                c.exec(dropIndex)
                val gone = c.scalar(
                    "SELECT count(*) FROM pg_indexes WHERE tablename = 'notifications' AND indexname = 'idx_notifications_unseen'",
                )
                assertEquals(0L, gone, "and the way back up drops it again")
            } finally {
                c.rollback()
            }
        }
    }
}
