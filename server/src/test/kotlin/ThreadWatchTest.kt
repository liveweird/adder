package ch.nokillswit

import ch.nokillswit.threads.ThreadCreateRequest
import ch.nokillswit.threads.ThreadOwnerRequest
import ch.nokillswit.threads.MessageRequest
import ch.nokillswit.threads.ThreadWatcherResponse
import ch.nokillswit.infra.paging.PageResponse
import ch.nokillswit.threads.ThreadPageResponse
import ch.nokillswit.threads.ThreadResponse
import ch.nokillswit.threads.WatchRequest
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

/**
 * Watching (V26, `chat-model.md` "Watching (V26)"): any member watches or unwatches any thread of the room (a closed one and an archived
 * room included), the guard order, the no-op that writes and audits nothing, the watches that go with the membership, the three
 * auto-watches (the creator, each new owner and a reply author), the batched `watching` of the list, and the write that takes no room lock.
 * Rooms, templates and users are SHARED suite state: every test mints UNIQUE rows and removes them.
 */
class ThreadWatchTest {
    private val rooms = "/api/v1/rooms"
    private val minted = MintedIds(mutableListOf(), mutableListOf(), mutableListOf())

    @AfterTest
    fun cleanUp() = runBlocking {
        TestRooms.purge(minted.rooms)
        minted.users.forEach { TestUsers.softDelete(it) }
        minted.templates.forEach { TestTemplates.delete(it) }
    }

    private suspend fun HttpClient.watch(room: UInt, number: Int, watching: Boolean): HttpResponse =
        putJson("$rooms/$room/threads/$number/watch", WatchRequest(watching))

    private suspend fun HttpClient.rawWatch(room: UInt, json: String): HttpResponse = request("$rooms/$room/threads/1/watch") {
        method = HttpMethod.Put
        contentType(ContentType.Application.Json)
        setBody(json)
    }

    private suspend fun HttpClient.thread(room: UInt, number: Int = 1): ThreadResponse = get("$rooms/$room/threads/$number").body()

    private suspend fun HttpClient.listed(room: UInt): ThreadPageResponse = get("$rooms/$room/threads").body()

    /** The watchers of thread [threadId] straight from the table. */
    private fun watchers(threadId: UInt): Set<UInt> = PostgresTestSupport.connect().use { c ->
        c.prepareStatement("SELECT user_id FROM thread_watches WHERE thread_id = ? AND watching").use { st ->
            st.setInt(1, threadId.toInt())
            st.executeQuery().use { rs -> buildSet { while (rs.next()) add(rs.getLong(1).toUInt()) } }
        }
    }

    private fun watchRows(room: UInt, user: UInt): Int = PostgresTestSupport.connect().use { c ->
        c.prepareStatement("SELECT count(*) FROM thread_watches WHERE room_id = ? AND user_id = ?").use { st ->
            st.setInt(1, room.toInt())
            st.setLong(2, user.toLong())
            st.executeQuery().use { rs -> rs.next(); rs.getInt(1) }
        }
    }

    @Test
    fun `a member watches and stops watching, and a repeat changes nothing and audits nothing`() = testApplication {
        usePostgresTestcontainer()
        val cast = seedThreadCast("wt-toggle", "Watch toggle", minted)
        withAuditCapture { capture ->
            assertFalse(cast.member.client.thread(cast.room).watching)
            assertEquals(1, cast.member.client.thread(cast.room).watcherCount, "the creator watches their own thread")
            assertEquals(HttpStatusCode.NoContent, cast.member.client.watch(cast.room, 1, false).status, "already off: nothing written")
            assertEquals(0, watchRows(cast.room, cast.member.id), "an off no-op is not remembered as an explicit unwatch")
            assertEquals(HttpStatusCode.NoContent, cast.member.client.watch(cast.room, 1, true).status)
            assertEquals(HttpStatusCode.NoContent, cast.member.client.watch(cast.room, 1, true).status, "again: nothing written")
            val watching = cast.member.client.thread(cast.room)
            assertTrue(watching.watching)
            assertEquals(2, watching.watcherCount)
            assertTrue(cast.member.client.listed(cast.room).items.single().watching)
            assertTrue(cast.author.client.thread(cast.room).watching, "the creator's own watch is theirs, not Marek's")
            assertFalse(cast.owner.client.thread(cast.room).watching, "the room owner watches nothing")

            assertEquals(HttpStatusCode.NoContent, cast.member.client.watch(cast.room, 1, false).status)
            assertEquals(HttpStatusCode.NoContent, cast.member.client.watch(cast.room, 1, false).status, "again: nothing written")
            assertFalse(cast.member.client.thread(cast.room).watching)
            assertEquals(1, cast.member.client.thread(cast.room).watcherCount)
            assertFalse(cast.member.client.listed(cast.room).items.single().watching)

            val lines = capture.events.filter { it.message == "thread.watch_changed" && it.hasKeyValue("roomId", cast.room.toLong()) }
            assertEquals(2, lines.size, "one line per CHANGE, none for a repeat")
            val (on, off) = lines
            assertTrue(on.hasKeyValue("byUserId", cast.member.id.toLong()) && on.hasKeyValue("watching", true))
            assertTrue(on.hasKeyValue("threadId", cast.threadId.toLong()) && on.hasKeyValue("number", 1))
            assertTrue(off.hasKeyValue("watching", false) && off.hasKeyValue("byUserId", cast.member.id.toLong()))
            val everything = capture.events.joinToString { e ->
                "${e.formattedMessage} ${e.keyValuePairs?.joinToString { "${it.key}=${it.value}" }}"
            }
            assertTrue(TestThreads.raw(cast.room, 1)!!.title !in everything, "no title reaches the audit trail")
        }
    }

    @Test
    fun `a closed thread and an archived room can be watched and unwatched`() = testApplication {
        usePostgresTestcontainer()
        val cast = seedThreadCast("wt-closed", "Watch closed", minted)
        TestThreads.closeRaw(cast.room, 1)
        assertEquals(HttpStatusCode.NoContent, cast.member.client.watch(cast.room, 1, true).status)
        assertTrue(cast.member.client.thread(cast.room).watching)
        TestRooms.archive(cast.room)
        assertEquals(HttpStatusCode.NoContent, cast.member.client.watch(cast.room, 1, false).status)
        assertFalse(cast.member.client.thread(cast.room).watching)
        assertEquals(HttpStatusCode.NoContent, cast.owner.client.watch(cast.room, 1, true).status)
        assertTrue(cast.owner.client.thread(cast.room).watching)
    }

    @Test
    fun `an ADMIN outside the room is 403, an outsider 404 and audited, and both before the body`() = testApplication {
        usePostgresTestcontainer()
        val cast = seedThreadCast("wt-guard", "Watch guard", minted)
        val bad = """{"watching":"yes"}"""
        assertEquals(HttpStatusCode.Forbidden, cast.admin.client.watch(cast.room, 1, true).status)
        assertEquals(HttpStatusCode.Forbidden, cast.admin.client.rawWatch(cast.room, bad).status, "the member rule before the body")
        withAuditCapture { capture ->
            assertEquals(HttpStatusCode.NotFound, cast.outsider.client.rawWatch(cast.room, bad).status, "visibility before the body")
            assertEquals(HttpStatusCode.NotFound, cast.outsider.client.watch(cast.room, 1, true).status)
            val denied = capture.awaitEvent { it.message == "room.access_denied" && it.hasKeyValue("byUserId", cast.outsider.id.toLong()) }
            assertNotNull(denied)
            assertTrue(capture.events.none { it.message == "thread.watch_changed" }, "a refused write is not a watch")
        }
        for (json in listOf("""{}""", bad, """{"watching":null}""", """{"watching":1}""", "not json")) {
            assertEquals(HttpStatusCode.BadRequest, cast.member.client.rawWatch(cast.room, json).status, json)
        }
        assertEquals(setOf(cast.author.id), watchers(cast.threadId), "nothing was written")
    }

    @Test
    fun `an unknown thread and an unknown room are 404`() = testApplication {
        usePostgresTestcontainer()
        val cast = seedThreadCast("wt-404", "Watch 404", minted)
        assertEquals(HttpStatusCode.NotFound, cast.member.client.watch(cast.room, 99, true).status)
        assertEquals(HttpStatusCode.NotFound, cast.member.client.watch(2_000_000_000u, 1, true).status)
        assertEquals(setOf(cast.author.id), watchers(cast.threadId))
    }

    @Test
    fun `leaving drops the watches and rejoining starts clean`() = testApplication {
        usePostgresTestcontainer()
        val cast = seedThreadCast("wt-leave", "Watch leave", minted)
        assertEquals(HttpStatusCode.NoContent, cast.member.client.watch(cast.room, 1, true).status)
        assertEquals(1, watchRows(cast.room, cast.member.id))
        assertEquals(HttpStatusCode.NoContent, cast.member.client.delete("$rooms/${cast.room}/members/${cast.member.id}").status)
        assertEquals(0, watchRows(cast.room, cast.member.id), "the membership key took the watch with the row")
        assertEquals(setOf(cast.author.id), watchers(cast.threadId))
        TestRooms.addMember(cast.room, cast.member.id)
        assertFalse(cast.member.client.thread(cast.room).watching, "rejoining starts clean")
        assertEquals(HttpStatusCode.NoContent, cast.member.client.watch(cast.room, 1, true).status)
        assertTrue(cast.member.client.thread(cast.room).watching)
    }

    @Test
    fun `a member removed while their watch write runs gets 404, never 500`() = testApplication {
        usePostgresTestcontainer()
        val cast = seedThreadCast("wt-race", "Watch race", minted)
        TestThreads.holdingRoomLock(
            cast.room,
            "DELETE FROM room_members WHERE room_id = ? AND user_id = ?",
            cast.room, cast.member.id,
        ) { raw ->
            coroutineScope {
                // The route's own checks pass (the removal is uncommitted); the write then waits on the member row, not on the room lock.
                val writing = async { cast.member.client.watch(cast.room, 1, true) }
                awaitLockWait("room_members")
                raw.commit()
                assertEquals(HttpStatusCode.NotFound, writing.await().status)
            }
        }
        assertEquals(0, watchRows(cast.room, cast.member.id))
    }

    @Test
    fun `a watch write does not wait for the room lock`() = testApplication {
        usePostgresTestcontainer()
        val cast = seedThreadCast("wt-lock", "Watch lock", minted)
        TestThreads.holdingRoomLock(cast.room) {
            // A write that took the room lock would wait and answer the typed 429 `room-busy`: a 204 while it is held proves it did not.
            withTimeout(30_000) {
                assertEquals(HttpStatusCode.NoContent, cast.member.client.watch(cast.room, 1, true).status)
                assertEquals(HttpStatusCode.NoContent, cast.member.client.watch(cast.room, 1, false).status)
            }
        }
    }

    @Test
    fun `two watch writes of one member hold the member row without blocking each other`() = testApplication {
        usePostgresTestcontainer()
        val cast = seedThreadCast("wt-share", "Watch share", minted)
        PostgresTestSupport.connect().use { raw ->
            raw.autoCommit = false
            raw.prepareStatement("SELECT user_id FROM room_members WHERE room_id = ? AND user_id = ? FOR KEY SHARE").use { st ->
                st.setInt(1, cast.room.toInt())
                st.setLong(2, cast.member.id.toLong())
                st.executeQuery().close()
            }
            try {
                withTimeout(30_000) { assertEquals(HttpStatusCode.NoContent, cast.member.client.watch(cast.room, 1, true).status) }
            } finally {
                raw.rollback()
            }
        }
        assertTrue(cast.member.client.thread(cast.room).watching)
    }

    @Test
    fun `a room owner removing a member drops that member's watches through the API`() = testApplication {
        usePostgresTestcontainer()
        val cast = seedThreadCast("wt-remove", "Watch remove", minted)
        assertEquals(HttpStatusCode.NoContent, cast.member.client.watch(cast.room, 1, true).status)
        assertEquals(1, watchRows(cast.room, cast.member.id))
        assertEquals(HttpStatusCode.NoContent, cast.owner.client.delete("$rooms/${cast.room}/members/${cast.member.id}").status)
        assertEquals(0, watchRows(cast.room, cast.member.id), "the membership key took the watch")
        assertEquals(setOf(cast.author.id), watchers(cast.threadId), "other members' watches stay")
    }

    @Test
    fun `the creator watches a new thread, by the route and by the service`() = testApplication {
        usePostgresTestcontainer()
        val cast = seedThreadCast("wt-create", "Watch create", minted)
        val severity = mapOf("severity" to JsonPrimitive("s1"))
        val created = cast.member.client.postJson("$rooms/${cast.room}/threads", ThreadCreateRequest("Disk full", "Body", severity))
        assertEquals(HttpStatusCode.Created, created.status)
        val thread = created.body<ThreadResponse>()
        assertTrue(thread.watching, "the creator watches what they start")
        assertEquals(1, thread.watcherCount)
        assertEquals(setOf(cast.member.id), watchers(thread.id))
        assertEquals(setOf(cast.author.id), watchers(cast.threadId), "the seeded thread (the service) has its creator")
        val list = cast.member.client.listed(cast.room).items.associateBy { it.number }
        assertTrue(list.getValue(thread.number).watching)
        assertFalse(list.getValue(1).watching, "Marek did not start thread 1")
    }

    @Test
    fun `a new owner watches unless they explicitly unwatched, including on a later hand-over`() = testApplication {
        usePostgresTestcontainer()
        val cast = seedThreadCast("wt-owner", "Watch owner", minted)
        val path = "$rooms/${cast.room}/threads/1/owner"
        assertEquals(HttpStatusCode.NoContent, cast.owner.client.putJson(path, ThreadOwnerRequest(cast.member.id)).status)
        assertEquals(setOf(cast.author.id, cast.member.id), watchers(cast.threadId), "the creator keeps watching")
        assertEquals(2, cast.owner.client.thread(cast.room).watcherCount)

        // The explicit choice survives both the no-op and a hand-over elsewhere and back.
        assertEquals(HttpStatusCode.NoContent, cast.member.client.watch(cast.room, 1, false).status)
        assertEquals(HttpStatusCode.NoContent, cast.owner.client.putJson(path, ThreadOwnerRequest(cast.member.id)).status)
        assertEquals(setOf(cast.author.id), watchers(cast.threadId), "naming the owner again writes nothing")
        assertEquals(HttpStatusCode.NoContent, cast.owner.client.putJson(path, ThreadOwnerRequest(cast.owner.id)).status)
        assertEquals(setOf(cast.author.id, cast.owner.id), watchers(cast.threadId), "a steward who takes the thread watches it")
        assertEquals(HttpStatusCode.NoContent, cast.owner.client.putJson(path, ThreadOwnerRequest(cast.member.id)).status)
        assertEquals(setOf(cast.author.id, cast.owner.id), watchers(cast.threadId), "a later hand-over respects the sticky unwatch")
    }

    @Test
    fun `replying starts a watch, while explicit unwatch is sticky until Watch is chosen again`() = testApplication {
        usePostgresTestcontainer()
        val cast = seedThreadCast("wt-reply", "Watch reply", minted)
        val messages = "$rooms/${cast.room}/threads/1/messages"
        assertFalse(cast.member.client.thread(cast.room).watching)
        assertEquals(HttpStatusCode.Created, cast.member.client.postJson(messages, MessageRequest("I can help")).status)
        assertTrue(cast.member.client.thread(cast.room).watching, "a successful reply starts watching")

        assertEquals(HttpStatusCode.NoContent, cast.member.client.watch(cast.room, 1, false).status)
        assertEquals(HttpStatusCode.Created, cast.member.client.postJson(messages, MessageRequest("Still helping")).status)
        assertFalse(cast.member.client.thread(cast.room).watching, "another reply respects explicit Unwatch")

        assertEquals(HttpStatusCode.NoContent, cast.member.client.watch(cast.room, 1, true).status)
        assertTrue(cast.member.client.thread(cast.room).watching)
    }

    @Test
    fun `members page through active watchers by name while non-member administrators are refused`() = testApplication {
        usePostgresTestcontainer()
        val cast = seedThreadCast("wt-list", "Watch list", minted)
        assertEquals(HttpStatusCode.NoContent, cast.member.client.watch(cast.room, 1, true).status)
        val path = "$rooms/${cast.room}/threads/1/watchers?pageSize=1&sort=name"
        val first = cast.member.client.get(path).body<PageResponse<ThreadWatcherResponse>>()
        assertEquals(2L, first.total)
        assertEquals(1, first.items.size)
        val second = cast.member.client.get("$path&page=2").body<PageResponse<ThreadWatcherResponse>>()
        assertEquals(2L, second.total)
        assertEquals(setOf(cast.author.id, cast.member.id), (first.items + second.items).map { it.userId }.toSet())
        assertEquals(HttpStatusCode.Forbidden, cast.admin.client.get(path).status)
        assertEquals(HttpStatusCode.NoContent, cast.member.client.watch(cast.room, 1, false).status)
        val remaining = cast.author.client.get("$rooms/${cast.room}/threads/1/watchers").body<PageResponse<ThreadWatcherResponse>>()
        assertEquals(listOf(cast.author.id), remaining.items.map { it.userId })
        assertEquals(1L, remaining.total)
    }

    @Test
    fun `the watch PUT has its own bucket of 30 a minute, apart from read-state`() = testApplication {
        usePostgresTestcontainer()
        val cast = seedThreadCast("wt-bucket", "Watch bucket", minted)
        repeat(30) { n -> assertEquals(HttpStatusCode.NoContent, cast.member.client.watch(cast.room, 1, true).status, "write ${n + 1}") }
        withAuditCapture { capture ->
            assertEquals(HttpStatusCode.TooManyRequests, cast.member.client.watch(cast.room, 1, false).status)
            assertNotNull(capture.awaitEvent { it.message == "rate_limit.exceeded" && it.hasKeyValue("bucket", "thread-watch") })
        }
        val seen = cast.member.client.postJson("$rooms/${cast.room}/seen", "")
        assertEquals(HttpStatusCode.NoContent, seen.status, "the read-state bucket is not spent")
        assertEquals(HttpStatusCode.OK, cast.member.client.get("$rooms/${cast.room}/threads/1").status, "reads are not bucketed")
        assertTrue(cast.member.client.thread(cast.room).watching, "the refused write changed nothing")
    }

    @Test
    fun `a non-member ADMIN reads the thread with no watch of their own and the real count`() = testApplication {
        usePostgresTestcontainer()
        val cast = seedThreadCast("wt-admin", "Watch admin", minted)
        assertEquals(HttpStatusCode.NoContent, cast.member.client.watch(cast.room, 1, true).status)
        val read = cast.admin.client.thread(cast.room)
        assertFalse(read.watching)
        assertEquals(2, read.watcherCount)
        assertFalse(cast.admin.client.listed(cast.room).items.single().watching)
    }

    @Test
    fun `a soft-deleted watcher is inert, neither counted nor listed`() = testApplication {
        usePostgresTestcontainer()
        val cast = seedThreadCast("wt-inert", "Watch inert", minted)
        assertEquals(HttpStatusCode.NoContent, cast.member.client.watch(cast.room, 1, true).status)
        val service = TestThreads.watches
        suspend fun counted() =
            suspendTransaction(sharedDatabaseForTests()) { service.count(cast.threadId) to service.recipientIds(cast.threadId) }
        assertEquals(2 to listOf(cast.author.id, cast.member.id).sorted(), counted())
        TestUsers.softDelete(cast.member.id)
        assertEquals(1 to listOf(cast.author.id), counted(), "a deleted account's row stays but never counts")
        val listed = cast.author.client.get("$rooms/${cast.room}/threads/1/watchers").body<PageResponse<ThreadWatcherResponse>>()
        assertEquals(listOf(cast.author.id), listed.items.map { it.userId })
        assertEquals(1L, listed.total)
        val among = suspendTransaction(sharedDatabaseForTests()) {
            service.watchingAmong(cast.room, cast.author.id, listOf(cast.threadId, 2_000_000_000u)) to
                service.watchingAmong(cast.room, cast.author.id, emptyList())
        }
        assertEquals(setOf(cast.threadId) to emptySet(), among)
    }
}
