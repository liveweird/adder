package ch.nokillswit

import ch.nokillswit.authz.TooManyRequestsException
import ch.nokillswit.infra.paging.PageRequest
import ch.nokillswit.infra.paging.SortField
import ch.nokillswit.threads.BudgetSpent
import ch.nokillswit.threads.SEARCH_TIMEOUT_PROBLEM_TYPE
import ch.nokillswit.threads.SearchBudget
import ch.nokillswit.threads.ThreadEventService
import ch.nokillswit.threads.ThreadSearchService
import ch.nokillswit.threads.ThreadTagService
import ch.nokillswit.users.UserRole
import io.ktor.server.testing.testApplication
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * The one deadline of a search request (`threads/SearchBudget.kt`): the arithmetic over a fake clock, and the service answering the
 * audited `429` `search-timeout` when the deadline passes BETWEEN two statements (no statement is ever cancelled in that case, so the
 * per-statement `57014` path cannot be what answers). The statement-level cancel is `ThreadSearchTest`/`GlobalSearchTest`.
 */
class SearchBudgetTest {
    private val mintedRooms = mutableListOf<UInt>()
    private val mintedUsers = mutableListOf<UInt>()
    private val mintedTemplates = mutableListOf<UInt>()

    @AfterTest
    fun cleanUp() = runBlocking {
        TestRooms.purge(mintedRooms)
        TestTags.purge()
        mintedUsers.forEach { TestUsers.softDelete(it) }
        mintedTemplates.forEach { TestTemplates.delete(it) }
    }

    private class FakeClock(var nanos: Long = 0L) : () -> Long {
        override fun invoke(): Long = nanos
        fun seconds(value: Double) {
            nanos = (value * 1_000_000_000L).toLong()
        }
    }

    @Test
    fun `the time left is the budget less the time spent, in whole seconds rounded up`() {
        val clock = FakeClock()
        val budget = SearchBudget(5, clock)
        assertEquals(5, budget.remainingSeconds())
        clock.seconds(0.001)
        assertEquals(5, budget.remainingSeconds(), "a started second still counts as a second")
        clock.seconds(1.0)
        assertEquals(4, budget.remainingSeconds())
        clock.seconds(3.2)
        assertEquals(2, budget.remainingSeconds(), "1.8 s left rounds up to 2")
        clock.seconds(4.999)
        assertEquals(1, budget.remainingSeconds(), "never less than a second while any time is left")
    }

    @Test
    fun `a spent deadline refuses the next statement`() {
        val clock = FakeClock()
        val budget = SearchBudget(2, clock)
        clock.seconds(2.0)
        assertFailsWith<BudgetSpent> { budget.remainingSeconds() }
        clock.seconds(30.0)
        assertFailsWith<BudgetSpent> { budget.remainingSeconds() }
    }

    @Test
    fun `a deadline spent between two statements is the audited 429 search-timeout and gives the permit back`() = testApplication {
        usePostgresTestcontainer()
        val owner = seedActor("sb-own").also { mintedUsers += it.id }
        val template = TestThreads.seedTemplate().also { mintedTemplates += it }
        val room = TestRooms.seed(owner.id, template, "Budget ${UUID.randomUUID().toString().substring(0, 8)}").also { mintedRooms += it }
        val term = "budgetword" + UUID.randomUUID().toString().replace("-", "").take(10)
        TestThreads.seed(room, owner.id, title = "Budget", body = "The $term waits")
        val paging = PageRequest(page = 1, pageSize = 20, sort = listOf(SortField("lastActivityAt", descending = true)))

        // Every read of the clock is two seconds later than the last: the budget (5 s) is 3 s left at the first arm, 1 s at the next,
        // and spent at the third, before the page is read.
        var ticks = 0L
        var skewed = true
        val tagService = ThreadTagService(sharedDatabaseForTests(), ThreadEventService(sharedDatabaseForTests()))
        val service = ThreadSearchService(sharedDatabaseForTests(), TestRooms.service, tagService, 5, 1) {
            if (skewed) ticks++ * 2_000_000_000L else System.nanoTime()
        }
        withAuditCapture { capture ->
            val failure = assertFailsWith<TooManyRequestsException> { service.searchRoom(room, owner.id, term, null, paging) }
            assertEquals(SEARCH_TIMEOUT_PROBLEM_TYPE, failure.problemType)
            assertTrue(term !in failure.message.orEmpty())
            val event = capture.awaitEvent { it.message == "search.timeout" && it.hasKeyValue("byUserId", owner.id.toLong()) }
            assertNotNull(event)
            assertTrue(event.hasKeyValue("roomId", room.toLong()))
        }

        // The service's ONE permit was released by the failure: with an honest clock the same service answers, twice.
        skewed = false
        assertEquals(1, service.searchRoom(room, owner.id, term, null, paging).total)
        assertEquals(1, service.searchRoom(room, owner.id, term, null, paging).total, "every search gives its permit back")
    }

    /**
     * A search with a `q` AND a `threadTag` arms the budget before the name-to-id read and AGAIN before the query-text statement (the
     * clock is read once per arm): on a clock that runs two seconds per read, the budget of 5 s is spent at the third arm, which is the one
     * before the query text, so that statement is never sent. With the second arm missing the query text would go out on the first
     * arm's stale timeout and the request would be cut one statement later.
     */
    @Test
    fun `a q beside a threadTag arms the budget again before the query text is sent`() = testApplication {
        usePostgresTestcontainer()
        val owner = seedActor("sb-tag-own").also { mintedUsers += it.id }
        val admin = seedActor("sb-tag-adm", UserRole.ADMIN).also { mintedUsers += it.id }
        val tag = admin.client.createCategory().tags.single()
        val template = TestThreads.seedTemplate().also { mintedTemplates += it }
        val name = "BudgetTag ${UUID.randomUUID().toString().substring(0, 8)}"
        val room = TestRooms.seed(owner.id, template, name).also { mintedRooms += it }
        val term = "budgettag" + UUID.randomUUID().toString().replace("-", "").take(10)
        TestThreads.seed(room, owner.id, title = "Budget", body = "The $term waits")
        val paging = PageRequest(page = 1, pageSize = 20, sort = listOf(SortField("lastActivityAt", descending = true)))
        var ticks = 0L
        val tagService = ThreadTagService(sharedDatabaseForTests(), ThreadEventService(sharedDatabaseForTests()))
        val service = ThreadSearchService(sharedDatabaseForTests(), TestRooms.service, tagService, 5, 1) { ticks++ * 2_000_000_000L }
        val driver = org.slf4j.LoggerFactory.getLogger("io.r2dbc.postgresql.QUERY") as ch.qos.logback.classic.Logger
        val saved = driver.level
        val capture = LogCapture(driver.name)
        try {
            driver.level = ch.qos.logback.classic.Level.DEBUG
            val failure = assertFailsWith<TooManyRequestsException> {
                service.searchRoom(room, owner.id, term, null, paging, tagNames = listOf(tag))
            }
            assertEquals(SEARCH_TIMEOUT_PROBLEM_TYPE, failure.problemType)
            val sent = capture.events.map { it.formattedMessage.lowercase() }
            assertTrue(sent.any { "from tags" in it }, "the name-to-id read ran: $sent")
            assertTrue(sent.none { "to_tsvector" in it || "string_agg" in it }, "the query text was never sent: $sent")
        } finally {
            driver.level = saved
            capture.detach()
        }
    }
}
