package ch.nokillswit

import java.io.File
import java.nio.file.Files
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
    // `repo.root` is set by server/test-execution.gradle.kts (which also declares the doc as a test input);
    // the `..` fallback covers an IDE run.
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

    @Test
    fun `the 1_2_0 runbook deletes the TAGS_CHANGED history a 1_1_0 binary cannot decode, and nothing else`() {
        val purge = sqlBlockWith("TAGS_CHANGED")
        val marker = UUID.randomUUID().toString().take(8)
        PostgresTestSupport.connect().use { c ->
            c.autoCommit = false
            try {
                val user = c.user(marker, "tags")
                val room = c.scalar(
                    "INSERT INTO rooms (\"name\", last_activity_at, created_at, updated_at) VALUES (?, 1, 1, 1) RETURNING id",
                    "Runbook tags ${UUID.randomUUID()}",
                )
                c.exec(
                    "INSERT INTO room_statuses (room_id, \"key\", label, colour, category, is_initial, position) " +
                        "VALUES ($room, 'open', 'Open', 'BLUE', 'OPEN', TRUE, 0)",
                )
                val thread = c.scalar(
                    "INSERT INTO threads (room_id, \"number\", title, status_key, owner_id, created_by, last_activity_at, created_at, " +
                        "updated_at) VALUES ($room, 1, 'T', 'open', $user, $user, 1, 1, 1) RETURNING id",
                )
                val category = c.scalar(
                    "INSERT INTO tag_categories (\"name\", created_at, updated_at) VALUES (?, 1, 1) RETURNING id", "Runbook $marker",
                )
                val tag = c.scalar("INSERT INTO tags (category_id, \"name\") VALUES (?, ?) RETURNING id", category, "runbook-$marker")
                c.exec("INSERT INTO thread_tags (thread_id, tag_id, added_at, added_by) VALUES ($thread, $tag, 1, $user)")
                for (type in listOf("CREATED", "TAGS_CHANGED", "STATUS_CHANGED", "TAGS_CHANGED")) {
                    c.exec(
                        "INSERT INTO thread_events (thread_id, user_id, created_at, event_type) VALUES ($thread, $user, 1, '$type')",
                    )
                }
                val types = "SELECT event_type FROM thread_events WHERE thread_id = $thread ORDER BY id"
                fun events(): List<String> = c.createStatement().use { st ->
                    st.executeQuery(types).use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
                }

                assertTrue(c.exec(purge) >= 2, "both TAGS_CHANGED rows of the thread (and any other thread's) are deleted")
                assertEquals(listOf("CREATED", "STATUS_CHANGED"), events(), "every other event stays, in order")
                assertEquals(1L, c.scalar("SELECT count(*) FROM thread_tags WHERE thread_id = $thread"), "the tags stay on the thread")
                assertEquals(0, c.exec(purge), "run twice: nothing more to delete")
            } finally {
                c.rollback()
            }
        }
    }

    /**
     * The claim under "Rollback below 1.2.0": a binary that ships only the 1.1.0 migrations (V1 to V30, the same bytes: an applied file is
     * never edited) boots against the 1.2 schema (V34), because Flyway's default `ignoreMigrationPatterns` is `*:future`. A Flyway over
     * a copy of the V1 to V30 files, configured as `infra/db/Flyway.kt` configures the app (no `ignoreMigrationPatterns`, no
     * `validateOnMigrate`), validates and migrates nothing; with the pattern replaced by one that does NOT ignore future migrations the
     * same instance refuses, so the test fails the day somebody turns the default off.
     */
    @Test
    fun `a Flyway that ships only the 1_1_0 migrations validates and boots against the 1_2 schema`() =
        assertShippedSetBootsAgainstNewerSchema(LAST_1_1_0_MIGRATION, "1.1.0")

    /**
     * The same claim one release up (`persistence.md` "Message quotes (V35)", "Rollback below 1.3.0"): a binary that ships only the 1.2.0
     * migrations (V1 to V34) boots against the V35 schema, so the nullable `messages.quoted_message_id` and its partial index are inert
     * to it.
     */
    @Test
    fun `a Flyway that ships only the 1_2_0 migrations validates and boots against the V35 schema`() =
        assertShippedSetBootsAgainstNewerSchema(LAST_1_2_0_MIGRATION, "1.2.0")

    private fun assertShippedSetBootsAgainstNewerSchema(lastShipped: Int, release: String) {
        PostgresTestSupport.ensureMigrated()
        val shipped = Files.createTempDirectory("flyway-$release-")
        try {
            val source = File(System.getProperty("repo.root") ?: "..", "server/src/main/resources/db/migration")
            val files = source.listFiles { f -> f.name.endsWith(".sql") }.orEmpty()
                .filter { Regex("V(\\d+)__.*").matchEntire(it.name)!!.groupValues[1].toInt() <= lastShipped }
            assertEquals((1..lastShipped).toList(), files.map { it.name.substringAfter('V').substringBefore("__").toInt() }.sorted())
            files.forEach { it.copyTo(File(shipped.toFile(), it.name)) }
            fun flyway(vararg ignore: String) = org.flywaydb.core.Flyway.configure()
                .dataSource(PostgresTestSupport.jdbcUrl, PostgresTestSupport.user, PostgresTestSupport.password)
                .locations("filesystem:${shipped.toAbsolutePath()}")
                .apply { if (ignore.isNotEmpty()) ignoreMigrationPatterns(*ignore) }
                .load()

            val old = flyway()
            val newest = old.info().applied().maxOf { it.version.version.toInt() }
            assertTrue(newest > lastShipped, "the shared schema is ahead of the $release set (V$newest)")
            old.validate()
            assertEquals(0, old.migrate().migrationsExecuted, "the old jar's boot migrates nothing")
            assertTrue(
                runCatching { flyway("*:missing").validate() }.isFailure,
                "with future migrations NOT ignored the same set is refused: the default is what the runbook relies on",
            )
        } finally {
            shipped.toFile().deleteRecursively()
        }
    }

    private companion object {
        /** The newest migration 1.1.0 shipped (V25 to V30 are its own; V31 to V34 are 1.2.0's). */
        const val LAST_1_1_0_MIGRATION = 30

        /** The newest migration 1.2.0 shipped (V31 to V34 are its own; V35 is 1.3.0's). */
        const val LAST_1_2_0_MIGRATION = 34
    }
}
