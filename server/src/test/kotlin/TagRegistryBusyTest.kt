package ch.nokillswit

import ch.nokillswit.infra.ProblemTypes
import ch.nokillswit.infra.db.R2dbcDatabaseKey
import ch.nokillswit.plugins.ProblemDetail
import ch.nokillswit.tags.TAG_REGISTRY_LOCK_WAIT_MILLIS
import ch.nokillswit.tags.TagCategoryPageResponse
import ch.nokillswit.tags.TagCategoryRequest
import ch.nokillswit.users.UserRole
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.r2dbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

/**
 * The bounded wait for the registry's table lock (`infra/db/Locking.kt`, the room lock's rule, `rooms/RoomBusyTest`'s shape): while another
 * transaction holds `LOCK TABLE tag_categories IN SHARE ROW EXCLUSIVE MODE` a registry write answers `429` of the shared `room-busy`
 * type with `Retry-After: 1` at about [TAG_REGISTRY_LOCK_WAIT_MILLIS], audited `tag_category.busy`, writes nothing; a read is not blocked;
 * and neither the transaction-local `lock_timeout` nor the isolation override leaks to the pooled connection (the app runs on a
 * ONE-connection pool here, so "the next borrower" is the connection the failed write used).
 */
class TagRegistryBusyTest {
    @AfterTest
    fun cleanUp() = runBlocking { TestTags.purge() }

    private suspend fun showHere(setting: String): String? =
        TransactionManager.current().exec("SHOW $setting") { it.get(0, String::class.java) }?.toList()?.single()

    @Test
    fun `a registry write behind a held table lock is 429 room-busy at the wait - audited, writes nothing, leaks nothing`() =
        testApplication {
            configureApp("postgres.pool.maxSize" to "1", "postgres.pool.initialSize" to "1")
            startApplication()
            val admin = seededClient("tagbusy", UserRole.ADMIN)
            val name = categoryName("Busy")

            withAuditCapture { capture ->
                PostgresTestSupport.connect().use { raw ->
                    raw.autoCommit = false
                    raw.createStatement().use { it.execute("LOCK TABLE tag_categories IN SHARE ROW EXCLUSIVE MODE") }
                    try {
                        // Ordinary reads stay available meanwhile.
                        assertEquals(HttpStatusCode.OK, admin.get("/api/v1/tag-categories?pageSize=100").status)
                        val started = System.nanoTime()
                        val refused = admin.postJson("/api/v1/tag-categories", TagCategoryRequest(name, null, listOf(uniqueTag())))
                        val elapsed = (System.nanoTime() - started) / 1_000_000
                        assertEquals(HttpStatusCode.TooManyRequests, refused.status)
                        assertEquals("1", refused.headers["Retry-After"])
                        val problem = refused.body<ProblemDetail>()
                        assertEquals(ProblemTypes.ROOM_BUSY, problem.type)
                        assertTrue("busy" in problem.detail.orEmpty(), "${problem.detail}")
                        assertTrue(elapsed >= TAG_REGISTRY_LOCK_WAIT_MILLIS - 100, "it did wait for the lock ($elapsed ms)")
                        assertTrue(elapsed < TAG_REGISTRY_LOCK_WAIT_MILLIS + 2000, "it answered at the bound ($elapsed ms)")
                    } finally {
                        raw.rollback()
                    }
                }
                val busy = capture.awaitEvent { it.message == "tag_category.busy" }
                assertNotNull(busy, "the wait that outran the bound is audited")
                assertTrue(capture.events.none { it.message == "tag_category.created" }, "and nothing was created")
            }

            val listed = admin.get("/api/v1/tag-categories?pageSize=100").body<TagCategoryPageResponse>().items
            assertTrue(listed.none { it.name == name }, "the refused create wrote nothing")
            val db = application.attributes[R2dbcDatabaseKey]
            assertEquals("0", suspendTransaction(db) { showHere("lock_timeout") }, "no lock_timeout left on the pooled connection")
            assertEquals("read committed", suspendTransaction(db) { showHere("transaction_isolation") })
            assertEquals(HttpStatusCode.Created, admin.postCategory(name).status, "the lock is free again and the write goes through")
            assertEquals("0", suspendTransaction(db) { showHere("lock_timeout") })
        }
}
