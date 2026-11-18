package ch.nokillswit

import ch.nokillswit.infra.db.EVENT_LOG_DEFAULT_SORT
import ch.nokillswit.infra.db.EVENT_LOG_SORT_FIELDS
import ch.nokillswit.threads.ThreadEventDescriptor
import ch.nokillswit.threads.ThreadEventPageResponse
import ch.nokillswit.threads.ThreadEventType
import ch.nokillswit.threads.threadCreatedEvent
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

/**
 * A thread's history (V14 `thread_events`, over the ported `infra/db/EventLog.kt`), after Toadie's `CatalogFileEventsTest`: the pure
 * descriptors first (no database), then the route - paging, the default newest-first sort, an unknown sort, the actor's name and
 * `userDeleted`, and the reader guard. Only the create writes events through a route in S2; the other kinds are recorded through the
 * service, which is exactly the call the later writes make.
 */
class ThreadEventsTest {
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

    @Test
    fun `the event types are persisted and frozen - a name is never renamed or removed`() {
        assertEquals(
            listOf("CREATED", "TITLE_CHANGED", "STATUS_CHANGED", "CLOSED", "REOPENED", "OWNER_CHANGED", "FIELD_CHANGED", "STATUS_REPLACED"),
            ThreadEventType.entries.map { it.name },
        )
    }

    @Test
    fun `a creation event names the title and the initial status with its label`() {
        val event = threadCreatedEvent("Disk full", "open", "Open")
        assertEquals(ThreadEventType.CREATED, event.type)
        assertEquals(mapOf("title" to "Disk full", "status" to "open", "statusLabel" to "Open"), event.params)
    }

    @Test
    fun `the events endpoint sorts on id and createdAt only, newest first by default`() {
        assertEquals(setOf("id", "createdAt"), EVENT_LOG_SORT_FIELDS)
        assertEquals(listOf("createdAt" to true, "id" to true), EVENT_LOG_DEFAULT_SORT.map { it.name to it.descending })
    }

    @Test
    fun `a thread's history pages, reads newest first by default and names the actor, a deleted one included`() = testApplication {
        usePostgresTestcontainer()
        val owner = seedActor("evt-own", name = "Olivia").also { mintedUsers += it.id }
        val leaver = seedActor("evt-gone", name = "Gone Gary").also { mintedUsers += it.id }
        val template = TestThreads.seedTemplate().also { mintedTemplates += it }
        val room = TestRooms.seed(owner.id, template, "Evt ${UUID.randomUUID().toString().substring(0, 8)}").also { mintedRooms += it }
        val thread = TestThreads.seed(room, owner.id, title = "History")
        suspendTransaction(sharedDatabaseForTests()) {
            suspend fun record(by: UInt, type: ThreadEventType, params: Map<String, String> = emptyMap()) =
                TestThreads.events.record(thread.id, by, ThreadEventDescriptor(type, params))
            record(owner.id, ThreadEventType.TITLE_CHANGED, mapOf("from" to "A", "to" to "B"))
            record(leaver.id, ThreadEventType.CLOSED, mapOf("from" to "open", "to" to "done"))
            record(owner.id, ThreadEventType.REOPENED)
            record(owner.id, ThreadEventType.FIELD_CHANGED, mapOf("field" to "note"))
        }
        TestUsers.softDelete(leaver.id)
        suspend fun page(query: String = ""): ThreadEventPageResponse =
            owner.client.get("$rooms/$room/threads/${thread.number}/events$query").body()

        val newestFirst = page()
        assertEquals(5L, newestFirst.total)
        assertEquals(
            listOf(
                ThreadEventType.FIELD_CHANGED, ThreadEventType.REOPENED, ThreadEventType.CLOSED,
                ThreadEventType.TITLE_CHANGED, ThreadEventType.CREATED,
            ),
            newestFirst.items.map { it.type },
            "the default is newest first, the id descending as the same-millisecond tiebreaker",
        )
        val closed = newestFirst.items.single { it.type == ThreadEventType.CLOSED }
        assertEquals(mapOf("from" to "open", "to" to "done"), closed.params)
        val actor = listOf(closed.userId, closed.userName, closed.userDeleted)
        assertEquals(listOf(leaver.id, "Gone Gary", true), actor, "a deleted actor keeps the name")
        assertFalse(newestFirst.items.first().userDeleted)
        assertEquals("Olivia", newestFirst.items.first().userName)
        assertEquals(newestFirst.items.map { it.id }.sortedDescending(), newestFirst.items.map { it.id })

        assertEquals(newestFirst.items.map { it.id }.reversed(), page("?sort=createdAt").items.map { it.id })
        assertEquals(newestFirst.items.map { it.id }.reversed(), page("?sort=id").items.map { it.id })
        assertEquals(newestFirst.items.map { it.id }, page("?sort=-id").items.map { it.id })
        val last = page("?pageSize=2&page=3")
        assertEquals(listOf(ThreadEventType.CREATED), last.items.map { it.type })
        assertEquals(5L, last.total, "the total is before pagination")
        assertEquals(2, last.pageSize)
        for (bad in listOf("?sort=timestamp", "?sort=userId", "?pageSize=0", "?page=x")) {
            assertEquals(HttpStatusCode.BadRequest, owner.client.get("$rooms/$room/threads/1/events$bad").status, bad)
        }
        assertTrue(page().items.all { it.createdAt > 0 })
    }

    @Test
    fun `a history is addressed by room and number - an unknown number is 404`() = testApplication {
        usePostgresTestcontainer()
        val owner = seedActor("evt-addr").also { mintedUsers += it.id }
        val template = TestThreads.seedTemplate().also { mintedTemplates += it }
        val one = TestRooms.seed(owner.id, template, "EvtA ${UUID.randomUUID().toString().substring(0, 8)}").also { mintedRooms += it }
        val two = TestRooms.seed(owner.id, template, "EvtB ${UUID.randomUUID().toString().substring(0, 8)}").also { mintedRooms += it }
        TestThreads.seed(one, owner.id)
        assertEquals(HttpStatusCode.OK, owner.client.get("$rooms/$one/threads/1/events").status)
        assertEquals(HttpStatusCode.NotFound, owner.client.get("$rooms/$one/threads/2/events").status)
        assertEquals(HttpStatusCode.NotFound, owner.client.get("$rooms/$two/threads/1/events").status, "room two has no thread 1")
    }
}
