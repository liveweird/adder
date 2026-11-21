package ch.nokillswit

import ch.nokillswit.auth.hashPassword
import ch.nokillswit.authz.RoomRole
import ch.nokillswit.infra.paging.PageResponse
import ch.nokillswit.notifications.NotificationPageResponse
import ch.nokillswit.notifications.NotificationType
import ch.nokillswit.rooms.RoomPageResponse
import ch.nokillswit.teams.TeamPageResponse
import ch.nokillswit.threads.MessagePageResponse
import ch.nokillswit.threads.MessageResponse
import ch.nokillswit.threads.PinKind
import ch.nokillswit.threads.RoomPinResponse
import ch.nokillswit.threads.ThreadEventPageResponse
import ch.nokillswit.threads.ThreadEventType
import ch.nokillswit.threads.ThreadPageResponse
import ch.nokillswit.threads.ThreadResponse
import ch.nokillswit.threads.ThreadSearchPageResponse
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.sql.ResultSet
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.MigrationState
import org.flywaydb.core.api.MigrationVersion

/**
 * The populated-upgrade test (checkup C-TEST-3): every other test applies the migrations to an EMPTY database, which cannot see a
 * migration that mishandles rows. This one builds the schema of the previous release (1.0.0, Flyway V24) in a database of its OWN,
 * fills every table with realistic rows (`upgrade/populated-v24.sql`: plain SQL, Unicode, values at their limits, soft-deleted rows,
 * the history in the shapes the 1.0.0 server writes), migrates it to the latest version, and checks that
 *
 *  - Flyway applied every migration and validates the history;
 *  - no table lost a row and every table is byte for byte what it was (a digest of every row's text over the release's own columns);
 *  - V25's `teams.handle` is null on every row (the soft-deleted included) and V26's backfill made exactly the creator and the current
 *    owner of each thread watchers where they still hold a membership row, stamped with the thread's creation, with no notice;
 *  - the application boots on the migrated database and its reads answer, each checked against the OpenAPI contract.
 *
 * The database is created on the fork's Testcontainers server, named uniquely and dropped in a `finally`: the shared suite database is
 * never touched, so the test is order-independent and safe in parallel forks. The migrations run OUTSIDE `testApplication`, so
 * its 60 s limit covers only the boot and the reads. A new migration needs no edit here unless it breaks the upgrade (that is the
 * point): the digests cover the release's own columns, so an additive column is ignored while a dropped or renamed one fails. A
 * deliberate API change may still need edits to the read-through assertions. The V24 script is never edited, because an applied
 * migration's schema does not change. (V23's scrub of the history is proven by `ThreadHistoryScrubTest`, which runs the file itself.)
 */
class PopulatedUpgradeTest {
    @Test
    fun `a populated database of the previous release upgrades to the latest schema and serves the API`() {
        val database = "upgrade_" + UUID.randomUUID().toString().replace("-", "").take(12)
        execute("CREATE DATABASE $database")
        try {
            val atRelease = flywayFor(database, target = PREVIOUS_RELEASE_VERSION)
            assertEquals(PREVIOUS_RELEASE_VERSION.toInt(), atRelease.migrate().migrationsExecuted, "a fresh database to the release schema")
            populate(database)
            val columns = columns(database)
            val before = snapshot(database, columns)
            assertTrue(before.isNotEmpty() && before.values.all { it.rows > 0 }, "every table of the release holds rows: $before")

            assertAllMigrationsApplied(database)
            assertColumnsKept(columns, columns(database))
            assertDataPreserved(database, before, snapshot(database, columns))

            migratedApiServes(database)
        } finally {
            // A failing drop must not mask the assertion error that is already propagating.
            runCatching { execute("DROP DATABASE IF EXISTS $database WITH (FORCE)") }
        }
    }

    // ---- the migration -------------------------------------------------------------------------------------------------------

    private fun assertAllMigrationsApplied(database: String) {
        val total = flywayFor(database).info().all().size
        val result = flywayFor(database).migrate()
        assertTrue(result.success)
        assertEquals(total - PREVIOUS_RELEASE_VERSION.toInt(), result.migrationsExecuted, "exactly the migrations after the release")

        val flyway = flywayFor(database)
        flyway.validate()
        val info = flyway.info()
        assertTrue(info.pending().isEmpty(), "nothing pending")
        assertEquals(total, info.applied().size, "every migration is in the history")
        assertTrue(info.all().all { it.state == MigrationState.SUCCESS }, info.all().map { it.version to it.state }.toString())
        assertEquals(info.all().last().version, info.current().version)
        val after = info.applied().map { it.version.version }
        assertTrue("25" in after && "26" in after, "V25 and V26 ran on the populated database: $after")
    }

    /** An additive migration may add columns; a dropped or renamed one of the release's columns fails, naming the table. */
    private fun assertColumnsKept(before: Map<String, List<String>>, after: Map<String, List<String>>) {
        for ((table, was) in before) {
            val now = after[table].orEmpty()
            assertEquals(emptyList(), was - now.toSet(), "$table lost or renamed columns of the release: was $was, now $now")
        }
    }

    private fun assertDataPreserved(database: String, before: Map<String, TableState>, after: Map<String, TableState>) {
        // V25 adds teams.handle with no backfill: not one team of the release, the soft-deleted included, holds a handle.
        val handles = query(database, "SELECT count(*) FROM teams WHERE handle IS NOT NULL") { it.getLong(1) }.single()
        assertEquals(0L, handles, "V25's column is empty on every migrated team, deleted ones too")
        for ((table, was) in before) {
            val now = after.getValue(table)
            assertEquals(was.rows, now.rows, "$table keeps every row")
            assertEquals(was.digest, now.digest, "$table: its release columns are byte for byte what they were")
        }
        assertWatchesBackfilled(database)
    }

    /**
     * V26 makes a thread's creator and its current owner watchers when they still hold a membership row (a soft-deleted member's row
     * counts, the watch is inert), once when they are one person, and nobody else: thread 7's owner (user 4) is no member of room 2.
     * Each watch is stamped with its thread's creation and the backfill makes no notice (the notices table is byte for byte what it was).
     */
    private fun assertWatchesBackfilled(database: String) {
        val expected = listOf(
            1 to 2, 1 to 3, // thread 1: creator 2, owner 3
            2 to 2, // creator and owner are one person
            3 to 2, 3 to 3, // creator 3, owner 2
            4 to 4,
            5 to 6, // a soft-deleted member
            6 to 2,
            7 to 2, // creator 2; the owner (4) left room 2
            8 to 3,
            9 to 2, // a soft-deleted room keeps its threads' watches
        )
        val watches = query(database, "SELECT thread_id, user_id FROM thread_watches ORDER BY 1, 2") { it.getInt(1) to it.getInt(2) }
        assertEquals(expected, watches, "the backfill: creators and owners who still hold a membership row")
        val wrongRoom = query(
            database,
            "SELECT count(*) FROM thread_watches w JOIN threads t ON t.id = w.thread_id " +
                "WHERE w.room_id <> t.room_id OR w.created_at <> t.created_at",
        ) { it.getLong(1) }.single()
        assertEquals(0L, wrongRoom, "a watch carries its thread's room and its creation instant")
    }

    // ---- the application on the migrated database ----------------------------------------------------------------------------

    private fun migratedApiServes(database: String) = testApplication {
        configureApp(
            "postgres.jdbcUrl" to PostgresTestSupport.jdbcUrlFor(database),
            "postgres.r2dbcUrl" to PostgresTestSupport.r2dbcUrl.substringBeforeLast('/') + "/" + database,
        )
        startApplication()
        val olivia = authedClient(OLIVIA, PASSWORD)
        assertRoomsAndTeams(olivia)
        assertThreadList(olivia)
        assertThreadAndHistory(olivia)
        assertMessagesPinsAndSearch(olivia, database)
        assertNotifications(olivia, authedClient(ZOE, PASSWORD))
        assertWatching(olivia, authedClient(ZOE, PASSWORD))
    }

    private suspend fun assertRoomsAndTeams(olivia: HttpClient) {
        val rooms = olivia.getBody<RoomPageResponse>("/api/v1/rooms?pageSize=100")
        assertEquals(listOf(1u, 2u), rooms.items.map { it.id }.sorted(), "the soft-deleted room is not listed")
        val incident = rooms.items.single { it.id == 1u }
        assertEquals("Incydent: baza danych (2026-11-19)", incident.name)
        assertEquals(RoomRole.OWNER, incident.role)
        assertEquals(3, incident.memberCount, "the soft-deleted member is not counted")
        assertTrue(incident.unread, "room 1 has activity after the caller's last visit")
        assertFalse(incident.archived)
        val quiet = rooms.items.single { it.id == 2u }
        assertTrue(quiet.archived)
        assertFalse(quiet.unread)

        val teams = olivia.getBody<TeamPageResponse>("/api/v1/teams?pageSize=100").items.filter { it.name == "Platform Reliability" }
        assertEquals(2, teams.single().memberCount, "the roster counts active users only")
        assertNull(teams.single().handle, "V25 adds the mention handle with no backfill")
        assertEquals("Olivia Żółkiewska", teams.single().managerName)
    }

    private suspend fun assertThreadList(olivia: HttpClient) {
        suspend fun numbers(query: String) =
            olivia.getBody<ThreadPageResponse>("/api/v1/rooms/1/threads?pageSize=100$query").items.map { it.number }

        assertEquals(listOf(1, 2, 3, 4, 5, 6), numbers("").sorted())
        assertEquals(listOf(3), numbers("&status=resolved"))
        assertEquals(listOf(3), numbers("&closed=true"))
        assertEquals(listOf(5), numbers("&ownerId=6"))
        assertEquals(listOf(1), numbers("&field=severity:s2"))
        assertEquals(listOf(1), numbers("&tag=incydent"))
        assertEquals(listOf(1), numbers("&tag=${"Żółć".encodeURLQuery()}"), "the tag index folds accents like the filter")
        assertEquals(listOf(2), numbers("&mentionedId=4"))
        assertEquals(listOf(2, 4, 5, 1, 6, 3), numbers("&sort=status"), "workflow order of the live statuses, ties by id")
        val longTitle = olivia.getBody<ThreadPageResponse>("/api/v1/rooms/1/threads?pageSize=100").items.single { it.number == 6 }.title
        assertEquals(200, longTitle.length)
    }

    /** The backfilled watches through the API: the person's flag, the filter and the count (a deleted member's watch is not counted). */
    private suspend fun assertWatching(olivia: HttpClient, zoe: HttpClient) {
        suspend fun numbers(client: HttpClient, query: String) =
            client.getBody<ThreadPageResponse>("/api/v1/rooms/1/threads?pageSize=100$query").items.map { it.number }.sorted()

        assertEquals(listOf(1, 2, 3, 6), numbers(olivia, "&watching=true"), "olivia created 1, 2 and 6 and owns 2, 3 and 6")
        assertEquals(listOf(4, 5), numbers(olivia, "&watching=false"))
        assertEquals(listOf(1, 3), numbers(zoe, "&watching=true"), "zoe owns 1 and created 3")
        val one = olivia.getBody<ThreadResponse>("/api/v1/rooms/1/threads/1")
        assertTrue(one.watching)
        assertEquals(2, one.watcherCount)
        val five = olivia.getBody<ThreadResponse>("/api/v1/rooms/1/threads/5")
        assertFalse(five.watching)
        assertEquals(0, five.watcherCount, "the only watcher of thread 5 is a deleted account")
    }

    private suspend fun assertThreadAndHistory(olivia: HttpClient) {
        val thread = olivia.getBody<ThreadResponse>("/api/v1/rooms/1/threads/1")
        assertEquals("Zoë Ångström", thread.ownerName)
        assertEquals(7L, thread.revision)
        assertEquals(5, thread.messageCount)
        assertEquals(
            setOf("severity", "summary", "runbook", "due", "impact", "lead", "components", "shared"), thread.fields.keys,
            "retired fields keep their values, hidden",
        )
        assertEquals(500, (thread.fields.getValue("summary") as JsonPrimitive).content.length)
        assertEquals(2000, (thread.fields.getValue("runbook") as JsonPrimitive).content.length)
        assertEquals("Olivia Żółkiewska", thread.fieldUserNames["2"])

        val history = olivia.getBody<ThreadEventPageResponse>("/api/v1/rooms/1/threads/1/events?pageSize=100")
        assertEquals(21L, history.total)
        assertEquals(setOf(2u, 3u, 6u), history.items.map { it.actorId }.toSet())
        val gone = history.items.single { it.params["field"] == "components" }
        assertEquals(6u, gone.actorId)
        assertTrue(gone.actorDeleted, "an event of a since-deleted account still names its actor")
        assertEquals("Gerard Odszedł", gone.actorName)
        fun fieldEvents(field: String) = history.items.filter { it.type == ThreadEventType.FIELD_CHANGED && it.params["field"] == field }
        assertEquals(
            mapOf("field" to "summary", "fieldLabel" to "Podsumowanie"), fieldEvents("summary").first().params,
            "a TEXT change keeps no text",
        )
        assertEquals("S2", fieldEvents("severity").single { it.params["from"] == "S3" }.params["to"])
        assertEquals(
            mapOf("field" to "deadline", "fieldLabel" to "Deadline"), fieldEvents("deadline").single().params,
            "a retired field's values are hidden on read",
        )
        assertEquals(
            mapOf("status" to "triage", "statusLabel" to "Triaż"), history.items.single { it.type == ThreadEventType.CREATED }.params,
        )
        assertTrue(history.items.filter { it.type == ThreadEventType.TITLE_CHANGED }.all { it.params.isEmpty() })
        assertEquals(
            setOf(
                ThreadEventType.CREATED, ThreadEventType.TITLE_CHANGED, ThreadEventType.STATUS_CHANGED, ThreadEventType.OWNER_CHANGED,
                ThreadEventType.FIELD_CHANGED,
            ),
            history.items.map { it.type }.toSet(),
        )
        val closing = olivia.getBody<ThreadEventPageResponse>("/api/v1/rooms/1/threads/3/events?pageSize=100").items.map { it.type }
        assertTrue(ThreadEventType.CLOSED in closing && ThreadEventType.REOPENED in closing)
        val replaced = olivia.getBody<ThreadEventPageResponse>("/api/v1/rooms/1/threads/5/events?pageSize=100").items
        assertNotNull(replaced.singleOrNull { it.type == ThreadEventType.STATUS_REPLACED })
    }

    private suspend fun assertMessagesPinsAndSearch(olivia: HttpClient, database: String) {
        val messages = olivia.getBody<MessagePageResponse>("/api/v1/rooms/1/threads/1/messages?pageSize=100")
        assertEquals(5L, messages.total)
        val byId = messages.items.associateBy(MessageResponse::id)
        val longest = byId.getValue(3u)
        assertEquals(storedBody(database, 3), longest.body, "the 8,000-character body arrives intact")
        assertEquals(8000, longest.body!!.length)
        val tombstone = byId.getValue(5u)
        assertTrue(tombstone.deleted && tombstone.deletedByModerator)
        assertNull(tombstone.body)
        val opener = byId.getValue(1u)
        assertEquals(listOf("Zoë Ångström"), opener.mentions.map { it.name })
        assertEquals(listOf("👍" to 2, "❤️" to 1), opener.reactions.map { it.emoji to it.count }.sortedByDescending { it.second })
        val edited = byId.getValue(2u)
        assertEquals(listOf("Olivia Żółkiewska", "Gerard Odszedł"), edited.mentions.map { it.name })
        assertEquals(setOf("👍🏽", "🇵🇱"), edited.reactions.map { it.emoji }.toSet())
        assertNotNull(edited.editedAt)

        val pins = olivia.getBody<PageResponse<RoomPinResponse>>("/api/v1/rooms/1/pins")
        assertEquals(listOf(PinKind.MESSAGE, PinKind.THREAD), pins.items.map { it.kind }, "newest pin first")
        assertEquals(2L, pins.total)

        val hits = olivia.getBody<ThreadSearchPageResponse>("/api/v1/rooms/1/search?q=zazolc")
        assertEquals(listOf(1), hits.items.map { it.threadNumber }, "the stored search vectors survived the upgrade")
        assertEquals(2L, hits.items.single().matchCount)
    }

    private suspend fun assertNotifications(olivia: HttpClient, zoe: HttpClient) {
        val unseen = zoe.getBody<NotificationPageResponse>("/api/v1/notifications?pageSize=100")
        assertEquals(setOf(1u, 5u), unseen.items.map { it.id }.toSet())
        assertTrue(unseen.items.none { it.seen })
        assertEquals(setOf(NotificationType.MENTIONED, NotificationType.THREAD_ASSIGNED), unseen.items.map { it.type }.toSet())
        assertNull(unseen.items.single { it.id == 5u }.messageId)
        assertEquals("Olivia Żółkiewska", unseen.items.first().actorName)
        assertEquals(2L, zoe.getBody<NotificationPageResponse>("/api/v1/notifications?seen=false").total)
        assertEquals(0L, zoe.getBody<NotificationPageResponse>("/api/v1/notifications?seen=true").total)

        val own = olivia.getBody<NotificationPageResponse>("/api/v1/notifications?pageSize=100")
        assertEquals(listOf(2u), own.items.map { it.id }, "a soft-deleted room's notice is not listed")
        assertTrue(own.items.single().seen)
    }

    private suspend inline fun <reified T> HttpClient.getBody(path: String): T {
        val response = get(path)
        assertEquals(HttpStatusCode.OK, response.status, path)
        return response.body()
    }

    private fun String.encodeURLQuery(): String = java.net.URLEncoder.encode(this, Charsets.UTF_8)

    // ---- the database --------------------------------------------------------------------------------------------------------

    private fun flywayFor(database: String, target: String? = null): Flyway = Flyway.configure()
        .dataSource(PostgresTestSupport.jdbcUrlFor(database), PostgresTestSupport.user, PostgresTestSupport.password)
        .locations("classpath:db/migration")
        .apply { if (target != null) target(MigrationVersion.fromVersion(target)) }
        .load()

    private fun execute(sql: String, database: String? = null) {
        val connection = if (database == null) PostgresTestSupport.connect() else PostgresTestSupport.connect(database)
        connection.use { c -> c.createStatement().use { it.execute(sql) } }
    }

    private fun populate(database: String) {
        val hash = runBlocking { hashPassword(PASSWORD, cost = 4) }
        val script = checkNotNull(javaClass.getResourceAsStream("/upgrade/populated-v24.sql")).use { it.readBytes().decodeToString() }
        assertTrue("{{PASSWORD_HASH}}" in script)
        execute(script.replace("{{PASSWORD_HASH}}", hash), database)
    }

    private fun <T> query(database: String, sql: String, row: (ResultSet) -> T): List<T> =
        PostgresTestSupport.connect(database).use { c ->
            c.createStatement().use { st -> st.executeQuery(sql).use { rs -> buildList { while (rs.next()) add(row(rs)) } } }
        }

    private fun storedBody(database: String, id: Int): String? =
        query(database, "SELECT body FROM messages WHERE id = $id") { it.getString(1) }.single()

    /** Each table's column names at the release, in column order: the columns the digests cover. */
    private fun columns(database: String): Map<String, List<String>> = query(
        database,
        "SELECT c.table_schema || '.' || c.table_name, c.column_name FROM information_schema.columns c " +
            "JOIN information_schema.tables t ON t.table_schema = c.table_schema AND t.table_name = c.table_name " +
            "WHERE t.table_type = 'BASE TABLE' AND c.table_schema IN ('public', 'ops') AND c.table_name <> 'flyway_schema_history' " +
            "ORDER BY 1, c.ordinal_position",
    ) { it.getString(1) to it.getString(2) }.groupBy({ it.first }, { it.second })

    /**
     * A table's row count and a digest of every row's text over [columns] only (the generated search vectors included), in a stable
     * order, so a column a later migration adds does not change the digest.
     */
    private fun snapshot(database: String, columns: Map<String, List<String>>): Map<String, TableState> =
        columns.mapValues { (table, names) ->
            val row = names.joinToString(", ") { "\"$it\"" }
            query(
                database,
                "SELECT count(*), md5(coalesce(string_agg(r::text, E'\\n' ORDER BY r::text), '')) " +
                    "FROM (SELECT row($row) AS r FROM $table) s",
            ) { TableState(it.getLong(1), it.getString(2)) }.single()
        }

    private data class TableState(val rows: Long, val digest: String)

    private companion object {
        /** The last migration 1.0.0 shipped (`git ls-tree v1.0.0 server/src/main/resources/db/migration`): V24. */
        const val PREVIOUS_RELEASE_VERSION = "24"
        const val PASSWORD = "upgrade-pass-1!"
        const val OLIVIA = "olivia.owner@upgrade.test"
        const val ZOE = "zoe@upgrade.test"
    }
}
