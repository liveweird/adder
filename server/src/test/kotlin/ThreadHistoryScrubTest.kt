package ch.nokillswit

import ch.nokillswit.authz.CallerPrincipal
import ch.nokillswit.infra.json.decodeParams
import ch.nokillswit.threads.ThreadEventPageResponse
import ch.nokillswit.threads.ThreadEventType
import ch.nokillswit.threads.ThreadUpdateRequest
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.server.testing.testApplication
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive

/**
 * What a thread's history keeps of a value (1.0.0, `chat-model.md` "History"): no TEXT or URL text and no title, written or already
 * stored (the V23 scrub), and no value of a RETIRED field, read back. Rooms, templates and users are SHARED suite state: every test
 * mints UNIQUE rows and removes them.
 */
class ThreadHistoryScrubTest {
    private val rooms = "/api/v1/rooms"
    private val mintedRooms = mutableListOf<UInt>()
    private val mintedUsers = mutableListOf<UInt>()
    private val mintedTemplates = mutableListOf<UInt>()

    @AfterTest
    fun cleanUp() = runBlocking {
        TestRooms.purge(mintedRooms)
        mintedUsers.forEach { TestUsers.softDelete(it) }
        mintedTemplates.forEach { TestTemplates.delete(it) }
    }

    private suspend fun room(ownerId: UInt): UInt {
        val template = TestThreads.seedTemplate().also { mintedTemplates += it }
        return TestRooms.seed(ownerId, template, "Scrub ${UUID.randomUUID().toString().substring(0, 8)}").also { mintedRooms += it }
    }

    private fun sql(statement: String, vararg args: Any) = PostgresTestSupport.connect().use { c ->
        c.prepareStatement(statement).use { st ->
            args.forEachIndexed { index, arg -> st.setObject(index + 1, arg) }
            st.executeUpdate()
        }
    }

    private fun setRetired(room: UInt, key: String, retired: Boolean) =
        sql("UPDATE room_fields SET marked_as_deleted = ? WHERE room_id = ? AND \"key\" = ?", retired, room.toInt(), key)

    @Test
    fun `a TEXT change, a rename and a retired field's values never reach the history response`() = testApplication {
        usePostgresTestcontainer()
        val owner = seedActor("scrub-own", name = "Olivia").also { mintedUsers += it.id }
        val room = room(owner.id)
        val secret = "hunter2-${UUID.randomUUID()}"
        val thread = TestThreads.seed(room, owner.id, title = "Title $secret")
        val caller = CallerPrincipal(owner.id, "scrub@test", emptySet())
        val first = ThreadUpdateRequest(
            "Renamed $secret",
            mapOf("severity" to JsonPrimitive("s2"), "note" to JsonPrimitive(secret), "due" to JsonPrimitive("2026-12-24")),
        )
        TestThreads.service.updateDetails(room, 1, caller, expectedRevision = 1, raw = first)
        val second = ThreadUpdateRequest("Renamed again", mapOf("note" to JsonPrimitive("$secret-2")))
        TestThreads.service.updateDetails(room, 1, caller, expectedRevision = 2, raw = second)

        val stored = TestThreads.rawEvents(thread.id)
        assertTrue(stored.none { secret in it.params }, "neither a title nor a TEXT value is stored in the history: $stored")
        assertEquals(listOf(emptyMap(), emptyMap()), stored.filter { it.type == "TITLE_CHANGED" }.map { decodeParams(it.params) })

        suspend fun page() = owner.client.get("$rooms/$room/threads/1/events?pageSize=100").body<ThreadEventPageResponse>()
        suspend fun fieldEvents() = page().items.filter { it.type == ThreadEventType.FIELD_CHANGED }.map { it.params }
        fun List<Map<String, String>>.of(field: String) = filter { it["field"] == field }

        val live = fieldEvents()
        assertEquals(listOf(mapOf("field" to "note", "fieldLabel" to "Note")), live.of("note").distinct(), "the change, never the text")
        assertEquals("2026-12-24", live.of("due").single()["to"])
        assertEquals("Sev two", live.of("severity").single()["to"])

        // A retired field's values are hidden, the entry is not (the pages and the total do not move); a revived field shows them again.
        val total = page().total
        setRetired(room, "due", true)
        val hidden = fieldEvents()
        assertEquals(mapOf("field" to "due", "fieldLabel" to "Due"), hidden.of("due").single())
        assertEquals("Sev two", hidden.of("severity").single()["to"], "a live field keeps its values")
        assertEquals(total, page().total)
        val storedDue = TestThreads.rawEvents(thread.id).map { decodeParams(it.params) }.single { it["field"] == "due" }
        assertEquals("2026-12-24", storedDue["to"], "hidden on read, not erased")
        setRetired(room, "due", false)
        assertEquals("2026-12-24", fieldEvents().of("due").single()["to"], "it comes back with the field")
    }

    @Test
    fun `V23 scrubs the TEXT, URL, unknown-field and title rows already written and nothing else`() = testApplication {
        usePostgresTestcontainer()
        val owner = seedActor("scrub-mig", name = "Olivia").also { mintedUsers += it.id }
        val a = room(owner.id)
        val b = room(owner.id)
        val threadA = TestThreads.seed(a, owner.id).id
        val threadB = TestThreads.seed(b, owner.id).id
        // The fixture template has no URL or NUMBER field: add them, and one key that is a SELECT in room A and a URL in room B.
        fun field(room: UInt, key: String, type: String, position: Int) = sql(
            "INSERT INTO room_fields (room_id, \"key\", label, \"type\", options, required, position) " +
                "VALUES (?, ?, ?, ?, '[]'::jsonb, FALSE, ?)",
            room.toInt(), key, key, type, position,
        )
        field(a, "link", "URL", 10)
        field(a, "count", "NUMBER", 11)
        field(a, "shared", "SELECT", 12)
        field(b, "shared", "URL", 12)
        setRetired(a, "note", true) // a retired TEXT field is still TEXT
        setRetired(a, "due", true) // a retired DATE field is still a DATE: hidden on read, not erased

        fun plant(thread: UInt, type: String, params: String): UInt = PostgresTestSupport.connect().use { c ->
            c.prepareStatement(
                "INSERT INTO thread_events (thread_id, user_id, created_at, event_type, params) VALUES (?, ?, ?, ?, ?) RETURNING id",
            ).use { st ->
                st.setInt(1, thread.toInt())
                st.setLong(2, owner.id.toLong())
                st.setLong(3, 1L)
                st.setString(4, type)
                st.setString(5, params)
                st.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1).toUInt()
                }
            }
        }
        fun changed(field: String, from: String? = "old secret", to: String? = "new secret") = buildString {
            append("""{"field":"$field","fieldLabel":"L"""")
            from?.let { append(""","from":"$it"""") }
            to?.let { append(""","to":"$it"""") }
            append("}")
        }
        fun paramsOf(id: UInt): String = PostgresTestSupport.connect().use { c ->
            c.prepareStatement("SELECT params FROM thread_events WHERE id = ?").use { st ->
                st.setInt(1, id.toInt())
                st.executeQuery().use { rs ->
                    rs.next()
                    rs.getString(1)
                }
            }
        }

        // Old-shape rows that must be scrubbed ...
        val title = plant(threadA, "TITLE_CHANGED", """{"from":"old secret","to":"new secret"}""")
        val created = plant(threadA, "CREATED", """{"title":"old secret","status":"open","statusLabel":"Open"}""")
        val fieldRows = listOf(
            plant(threadA, "FIELD_CHANGED", changed("note")),
            plant(threadA, "FIELD_CHANGED", changed("link", from = null)),
            plant(threadA, "FIELD_CHANGED", changed("ghost")),
            plant(threadB, "FIELD_CHANGED", changed("shared")),
        )
        // ... and rows that must stay byte for byte: the recorded types, an event with no value, and other kinds' own from/to.
        val kept = mapOf(
            plant(threadA, "FIELD_CHANGED", changed("severity")) to "SELECT",
            plant(threadA, "FIELD_CHANGED", changed("lead")) to "USER",
            plant(threadA, "FIELD_CHANGED", changed("due")) to "DATE, retired",
            plant(threadA, "FIELD_CHANGED", changed("tags")) to "MULTI_SELECT",
            plant(threadA, "FIELD_CHANGED", changed("count")) to "NUMBER",
            plant(threadA, "FIELD_CHANGED", changed("shared")) to "a SELECT here, a URL in the other room",
            plant(threadA, "FIELD_CHANGED", changed("note", from = null, to = null)) to "no value to scrub",
            plant(threadA, "STATUS_CHANGED", """{"from":"open","to":"done","fromLabel":"Open","toLabel":"Done"}""") to "a status move",
            plant(threadA, "OWNER_CHANGED", """{"fromId":"1","toId":"2","fromName":"A","toName":"B"}""") to "a hand-over",
        )
        val newShape = TestThreads.rawEvents(threadA).filter { it.type == "CREATED" && it.id != created }.associate { it.id to it.params }
        val keptBefore = kept.keys.associateWith(::paramsOf)

        val migration = checkNotNull(javaClass.getResourceAsStream("/db/migration/V23__scrub_history_values.sql"))
            .use { it.readBytes().decodeToString() }
        fun runMigration() = PostgresTestSupport.connect().use { c -> c.createStatement().use { it.execute(migration) } }
        runMigration()

        assertEquals("{}", paramsOf(title), "a rename keeps nothing")
        assertEquals(
            mapOf("status" to "open", "statusLabel" to "Open"), decodeParams(paramsOf(created)),
            "the opening title goes, the status stays",
        )
        for (id in fieldRows) {
            assertEquals(setOf("field", "fieldLabel"), decodeParams(paramsOf(id)).keys, "the field stays, its values go (row $id)")
            assertFalse("secret" in paramsOf(id), "row $id")
        }
        assertEquals("note", decodeParams(paramsOf(fieldRows[0]))["field"], "a retired TEXT field is still TEXT")
        for ((id, why) in kept) assertEquals(keptBefore.getValue(id), paramsOf(id), "left alone: $why")
        for ((id, params) in newShape) assertEquals(params, paramsOf(id), "a row already in the new shape is not rewritten")

        // Idempotent: a second run finds nothing left to rewrite.
        val every = listOf(title, created) + fieldRows + kept.keys
        val afterOnce = every.associateWith(::paramsOf)
        runMigration()
        assertEquals(afterOnce, every.associateWith(::paramsOf))
    }
}
