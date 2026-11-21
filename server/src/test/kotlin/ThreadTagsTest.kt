package ch.nokillswit

import ch.nokillswit.infra.json.decodeParams
import ch.nokillswit.infra.revision.EXPECTED_REVISION_HEADER
import ch.nokillswit.infra.revision.REVISION_CONFLICT_PROBLEM_TYPE
import ch.nokillswit.plugins.ProblemDetail
import ch.nokillswit.rooms.ROOM_ARCHIVED_PROBLEM_TYPE
import ch.nokillswit.tags.TagCategoryList
import ch.nokillswit.threads.ThreadEventPageResponse
import ch.nokillswit.threads.ThreadPageResponse
import ch.nokillswit.threads.ThreadResponse
import ch.nokillswit.threads.ThreadTag
import ch.nokillswit.threads.ThreadTagsRequest
import ch.nokillswit.threads.ThreadUpdateRequest
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.request
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive

/**
 * A thread's curated tags (1.2.0 S7, V33, `chat-model.md` "Tags"; Hammurabi's `AdrTagsTest` ported): the whole-set PUT applied as a diff,
 * the no-op, who may tag an open and a closed thread, the archived room, the revision, a name that is no live tag (a fixed 400 that echoes
 * no name), a retired tag on a thread (shown, kept until the next save, refused when a save still carries it), the cap judged first, the
 * `TAGS_CHANGED` history row, the audit line, and "history, not activity". The thread-list filter is `ThreadTagFilterTest`; the live hint
 * is `NotifyHintsTest`. Rooms, users and the registry are SHARED suite state: every test mints UNIQUE rows and removes them, and the tags
 * go in the SAME `@AfterTest` (a registry purge clears the `thread_tags` of its tags first, `TestTags.purge`).
 */
class ThreadTagsTest {
    private val rooms = "/api/v1/rooms"
    private val minted = MintedIds(mutableListOf(), mutableListOf(), mutableListOf())

    @AfterTest
    fun cleanUp() = runBlocking {
        TestRooms.purge(minted.rooms)
        TestTags.purge()
        minted.users.forEach { TestUsers.softDelete(it) }
        minted.templates.forEach { TestTemplates.delete(it) }
    }

    /** The cast of the thread tests plus a category of [tags] (created by the cast's ADMIN, who is no member of the room). */
    private class Tagged(val cast: ThreadCast, val tags: List<String>) {
        val room get() = cast.room
    }

    private suspend fun ApplicationTestBuilder.tagged(prefix: String, count: Int = 4): Tagged {
        usePostgresTestcontainer()
        val cast = seedThreadCast(prefix, "Tags", minted)
        val category = cast.admin.client.createCategory(tags = List(count) { uniqueTag() })
        return Tagged(cast, category.tags)
    }

    private suspend fun HttpClient.thread(room: UInt): ThreadResponse = get("$rooms/$room/threads/1").body()

    /** PUTs [tags] under [revision] (default: the thread's current one, read as the ADMIN of the cast who may read every room). */
    private suspend fun HttpClient.putTags(room: UInt, tags: List<String>, revision: Long, number: Int = 1): HttpResponse =
        put("$rooms/$room/threads/$number/tags") {
            header(EXPECTED_REVISION_HEADER, revision.toString())
            contentType(ContentType.Application.Json)
            setBody(ThreadTagsRequest(tags))
        }

    private suspend fun Tagged.revision(): Long = cast.admin.client.thread(room).revision

    private suspend fun Tagged.save(client: HttpClient, tags: List<String>): HttpResponse = client.putTags(room, tags, revision())

    private suspend fun Tagged.names(): List<String> = cast.admin.client.thread(room).tags.map { it.name }

    private fun HttpResponse.expect(expected: HttpStatusCode) =
        assertEquals(expected, status, "${request.method.value} ${request.url.encodedPath}")

    private suspend fun Tagged.history(): List<Pair<String, Map<String, String>>> =
        cast.admin.client.get("$rooms/$room/threads/1/events?pageSize=100&sort=id").body<ThreadEventPageResponse>().items
            .map { it.type.name to it.params }

    @Test
    fun `a member tags an open thread - the thread, the list and the history show it, and it is not activity`() = testApplication {
        val w = tagged("ttag-set")
        val (a, b, c) = w.tags
        val before = w.cast.member.client.thread(w.room)
        val activity = TestThreads.roomActivity(w.room)
        val raw = TestThreads.raw(w.room, 1)!!

        w.save(w.cast.member.client, listOf(c, a)).expect(HttpStatusCode.NoContent)

        val after = w.cast.member.client.thread(w.room)
        assertEquals(listOf(ThreadTag(a, false), ThreadTag(c, false)), after.tags, "code point order, not the order sent")
        assertEquals(before.revision + 1, after.revision, "a tag save rides the thread's revision")
        assertTrue(after.updatedAt >= before.updatedAt)
        assertEquals(before.lastActivityAt, after.lastActivityAt, "not activity")
        assertEquals(before.unreadCount, after.unreadCount, "not unread")
        assertEquals(activity, TestThreads.roomActivity(w.room), "the room's rail does not move")
        assertEquals(raw.messageCount, after.messageCount)
        val row = w.cast.member.client.get("$rooms/${w.room}/threads").body<ThreadPageResponse>().items.single()
        assertEquals(listOf(ThreadTag(a, false), ThreadTag(c, false)), row.tags, "the list row carries them too")
        assertEquals(
            listOf("CREATED" to mapOf("status" to "open", "statusLabel" to "Open"), "TAGS_CHANGED" to mapOf("added" to "$a, $c")),
            w.history(),
            "one event, only the added side (a side with no change has no key), and nothing else: no notice, no activity event",
        )
        assertFalse(b in w.names())
    }

    @Test
    fun `a save is a diff, the same set again writes nothing, and an empty list clears the thread`() = testApplication {
        val w = tagged("ttag-diff")
        val (a, b, c) = w.tags
        w.save(w.cast.member.client, listOf(a, b)).expect(HttpStatusCode.NoContent)

        w.save(w.cast.author.client, listOf(c, b)).expect(HttpStatusCode.NoContent)
        assertEquals(listOf(b, c), w.names())
        val steady = w.cast.admin.client.thread(w.room)
        assertEquals(
            listOf("TAGS_CHANGED" to mapOf("added" to "$a, $b"), "TAGS_CHANGED" to mapOf("added" to c, "removed" to a)),
            w.history().drop(1),
            "the second save added $c and removed $a; a side is its names joined with ', '",
        )

        w.cast.member.client.putTags(w.room, listOf(b, c), steady.revision).expect(HttpStatusCode.NoContent)
        w.cast.member.client.putTags(w.room, listOf(c, b), steady.revision).expect(HttpStatusCode.NoContent) // order does not matter
        val same = w.cast.admin.client.thread(w.room)
        assertEquals(steady.revision, same.revision, "no revision bump")
        assertEquals(steady.updatedAt, same.updatedAt)
        assertEquals(3, w.history().size, "no history entry either")

        w.save(w.cast.member.client, emptyList()).expect(HttpStatusCode.NoContent)
        assertEquals(emptyList(), w.names())
        assertEquals(mapOf("removed" to "$b, $c"), w.history().last().second)
    }

    @Test
    fun `a thread's tags sort by code point, the registry's one order of names`() = testApplication {
        usePostgresTestcontainer()
        val cast = seedThreadCast("ttag-order", "Tags", minted)
        val suffix = UUID.randomUUID().toString().take(8)
        // `-` (U+002D) sorts before `b` (U+0062): by code point "a-c" comes before "ab" (a libc collation may say otherwise).
        val dashed = "q$suffix-a-c"
        val plain = "q$suffix-ab"
        val wide = "q$suffix-ａ" // fullwidth `a`: after every ASCII name by code point
        cast.admin.client.createCategory(tags = listOf(wide, plain, dashed))
        val w = Tagged(cast, listOf(dashed, plain, wide))
        w.save(cast.member.client, listOf(wide, plain, dashed)).expect(HttpStatusCode.NoContent)
        assertEquals(listOf(dashed, plain, wide), w.names())
        assertEquals(mapOf("added" to "$dashed, $plain, $wide"), w.history().last().second)
    }

    @Test
    fun `on an open thread any member and an ADMIN tag it, an outsider gets the uniform 404`() = testApplication {
        val w = tagged("ttag-open")
        val (a, b, c) = w.tags
        w.save(w.cast.member.client, listOf(a)).expect(HttpStatusCode.NoContent) // a plain member, not the owner of the thread
        w.save(w.cast.admin.client, listOf(a, b)).expect(HttpStatusCode.NoContent) // an ADMIN who is no member: a reader
        w.save(w.cast.owner.client, listOf(a, b, c)).expect(HttpStatusCode.NoContent)
        val revision = w.revision()
        w.cast.outsider.client.putTags(w.room, listOf(a), revision).expect(HttpStatusCode.NotFound)
        w.cast.outsider.client.putTags(w.room, listOf(a), revision, number = 99).expect(HttpStatusCode.NotFound)
        w.cast.member.client.putTags(w.room, listOf(a), revision, number = 99).expect(HttpStatusCode.NotFound)
        assertEquals(listOf(a, b, c), w.names(), "the refused calls wrote nothing")
    }

    @Test
    fun `on a closed thread only its stewards tag it, and the 403 comes before the header and the body`() = testApplication {
        val w = tagged("ttag-closed")
        val (a, b) = w.tags
        TestThreads.closeRaw(w.room, 1)
        val revision = w.revision()

        // Marek is a plain member and Alina (who started it, so owns it) is its owner: the steward rule, not membership.
        w.cast.member.client.putTags(w.room, listOf(a), revision).expect(HttpStatusCode.Forbidden)
        val bare = w.cast.member.client.put("$rooms/${w.room}/threads/1/tags") {
            contentType(ContentType.Application.Json)
            setBody("not json at all")
        }
        assertEquals(HttpStatusCode.Forbidden, bare.status, "403 beats the missing header and the broken body")
        w.cast.outsider.client.putTags(w.room, listOf(a), revision).expect(HttpStatusCode.NotFound)
        assertEquals(emptyList(), w.names())

        w.cast.author.client.putTags(w.room, listOf(a), revision).expect(HttpStatusCode.NoContent) // the thread's owner
        w.cast.owner.client.putTags(w.room, listOf(a, b), revision + 1).expect(HttpStatusCode.NoContent) // the room OWNER
        w.cast.admin.client.putTags(w.room, listOf(b), revision + 2).expect(HttpStatusCode.NoContent) // an ADMIN
        assertEquals(listOf(b), w.names())
        val closed = w.cast.admin.client.thread(w.room)
        assertTrue(closed.closed, "tagging does not reopen it")
        assertEquals("done", closed.status)
    }

    @Test
    fun `an archived room refuses with the typed 409`() = testApplication {
        val w = tagged("ttag-arch")
        TestRooms.archive(w.room)
        val response = w.save(w.cast.member.client, listOf(w.tags.first()))
        response.expect(HttpStatusCode.Conflict)
        assertEquals(ROOM_ARCHIVED_PROBLEM_TYPE, response.body<ProblemDetail>().type)
        assertEquals(emptyList(), w.names())
    }

    @Test
    fun `the tag save rides the thread's revision - missing, malformed and stale are refused, and a details save conflicts with it`() =
        testApplication {
            val w = tagged("ttag-rev")
            val (a, b) = w.tags
            val revision = w.revision()
            val member = w.cast.member.client
            val noHeader = member.put("$rooms/${w.room}/threads/1/tags") {
                contentType(ContentType.Application.Json)
                setBody(ThreadTagsRequest(listOf(a)))
            }
            noHeader.expect(HttpStatusCode.BadRequest)
            val malformed = member.put("$rooms/${w.room}/threads/1/tags") {
                header(EXPECTED_REVISION_HEADER, "one")
                contentType(ContentType.Application.Json)
                setBody(ThreadTagsRequest(listOf(a)))
            }
            malformed.expect(HttpStatusCode.BadRequest)

            member.putTags(w.room, listOf(a), revision).expect(HttpStatusCode.NoContent)
            val stale = member.putTags(w.room, listOf(b), revision)
            stale.expect(HttpStatusCode.Conflict)
            assertEquals(REVISION_CONFLICT_PROBLEM_TYPE, stale.body<ProblemDetail>().type)

            // A details save with the same revision also moves it: an open details editor conflicts with a tag save and the reverse.
            val details = member.put("$rooms/${w.room}/threads/1") {
                header(EXPECTED_REVISION_HEADER, revision.toString())
                contentType(ContentType.Application.Json)
                setBody(ThreadUpdateRequest("Renamed", mapOf("severity" to JsonPrimitive("s1"))))
            }
            details.expect(HttpStatusCode.Conflict)
            assertEquals(listOf(a), w.names())
            val now = w.revision()
            member.put("$rooms/${w.room}/threads/1") {
                header(EXPECTED_REVISION_HEADER, now.toString())
                contentType(ContentType.Application.Json)
                setBody(ThreadUpdateRequest("Renamed", mapOf("severity" to JsonPrimitive("s1"))))
            }.expect(HttpStatusCode.NoContent)
            member.putTags(w.room, listOf(b), now).expect(HttpStatusCode.Conflict)
        }

    @Test
    fun `two saves at one revision - one wins, the other is told to reload`() = testApplication {
        val w = tagged("ttag-race")
        val (a, b) = w.tags
        val revision = w.revision()
        val (first, second) = coroutineScope {
            val one = async { w.cast.member.client.putTags(w.room, listOf(a), revision) }
            val two = async { w.cast.owner.client.putTags(w.room, listOf(b), revision) }
            one.await() to two.await()
        }
        assertEquals(setOf(HttpStatusCode.NoContent, HttpStatusCode.Conflict), setOf(first.status, second.status))
        assertEquals(revision + 1, w.revision())
        assertEquals(1, w.names().size)
    }

    @Test
    fun `an unknown or retired name is a 400 naming no tag, and a malformed one a 400 with a bounded detail`() = testApplication {
        val w = tagged("ttag-live")
        val (a, b) = w.tags
        val unknown = "never-${UUID.randomUUID().toString().take(8)}"
        val member = w.cast.member.client

        val refused = w.save(member, listOf(a, unknown))
        refused.expect(HttpStatusCode.BadRequest)
        val text = refused.bodyAsText()
        assertFalse(unknown in text || a in text, "the detail names no tag: $text")
        assertEquals(emptyList(), w.names(), "nothing was written, the live one included")

        // Retire `b` in the registry: dropping it from its category retires the row, so a save may not name it.
        val category = w.cast.admin.client.get("/api/v1/tag-categories").body<TagCategoryList>().items
            .single { b in it.tags }
        w.cast.admin.client.saveCategory(category.id, tags = category.tags - b, name = category.name).expect(HttpStatusCode.NoContent)
        val retired = w.save(member, listOf(b))
        retired.expect(HttpStatusCode.BadRequest)
        assertFalse(b in retired.bodyAsText(), "nor a retired one")

        // The grammar and a repeat are the route's own 400s, before any lock.
        // A malformed name's detail is the registry grammar's: it quotes the name the caller typed, but never more than the 40-character
        // limit of it (a request may carry a name as long as the body ceiling).
        val comma = w.save(member, listOf("bad,name"))
        comma.expect(HttpStatusCode.BadRequest)
        assertTrue("'bad,name'" in comma.bodyAsText(), comma.bodyAsText())
        w.save(member, listOf("-leading")).expect(HttpStatusCode.BadRequest)
        val huge = w.save(member, listOf("x".repeat(3000)))
        huge.expect(HttpStatusCode.BadRequest)
        val detail = huge.bodyAsText()
        assertTrue("x".repeat(41) !in detail && detail.length < 600, "the detail is bounded: ${detail.take(200)}")
        w.save(member, listOf("x".repeat(41))).expect(HttpStatusCode.BadRequest)
        // The four letters that render as nothing (Hangul fillers) are no tag, alone or inside a name (the grammar alone would admit them).
        for (blank in listOf("\u115F", "\u1160", "\u3164", "\uFFA0")) {
            for (name in listOf(blank, "x${blank}y", "x$blank")) {
                val refused = w.save(member, listOf(name))
                refused.expect(HttpStatusCode.BadRequest)
                assertTrue("invisible" in refused.bodyAsText(), refused.bodyAsText())
            }
        }
        w.save(member, listOf(a, a)).expect(HttpStatusCode.BadRequest)
        w.save(member, listOf(a, a.uppercase())).expect(HttpStatusCode.BadRequest) // the same tag once normalised
        w.save(member, listOf("bad\u0007control")).expect(HttpStatusCode.BadRequest)
        assertEquals(emptyList(), w.names())
        // A name is trimmed, collapsed and lower-cased: the registry's own spelling.
        w.save(member, listOf("  ${a.uppercase()}  ")).expect(HttpStatusCode.NoContent)
        assertEquals(listOf(a), w.names())
    }

    @Test
    fun `a retired tag stays on its threads, shown as retired, and a save that carries it is refused until dropped`() = testApplication {
        val w = tagged("ttag-retired")
        val (a, b, c) = w.tags
        val member = w.cast.member.client
        w.save(member, listOf(a, b)).expect(HttpStatusCode.NoContent)

        // The registry retires `a`: dropping it from its category (a rename is a drop plus an add).
        val category = w.cast.admin.client.get("/api/v1/tag-categories").body<TagCategoryList>().items
            .single { a in it.tags }
        w.cast.admin.client.saveCategory(category.id, tags = category.tags - a, name = category.name).expect(HttpStatusCode.NoContent)

        val shown = member.thread(w.room).tags
        assertEquals(listOf(ThreadTag(a, true), ThreadTag(b, false)), shown, "kept, and marked: the SPA strikes it through")
        assertEquals(
            listOf(ThreadTag(a, true)),
            member.get("$rooms/${w.room}/threads").body<ThreadPageResponse>().items.single().tags.filter { it.retired },
        )

        // The set as the thread shows it still carries the retired tag: refused until the picker drops it.
        w.save(member, listOf(a, b, c)).expect(HttpStatusCode.BadRequest)
        assertEquals(listOf(a, b), w.names())

        // Adding a tag to a thread that carries a retired one succeeds when the save drops the retired one (the picker does).
        w.save(member, listOf(b, c)).expect(HttpStatusCode.NoContent)
        assertEquals(listOf(ThreadTag(b, false), ThreadTag(c, false)), member.thread(w.room).tags)
        assertEquals(mapOf("added" to c, "removed" to a), w.history().last().second, "the retired one is the removed side")
    }

    @Test
    fun `deleting a category retires its tags and the threads keep them as retired`() = testApplication {
        val w = tagged("ttag-catdel", count = 2)
        val member = w.cast.member.client
        w.save(member, w.tags).expect(HttpStatusCode.NoContent)
        val category = w.cast.admin.client.get("/api/v1/tag-categories").body<TagCategoryList>().items
            .single { w.tags.first() in it.tags }
        w.cast.admin.client.deleteCategory(category.id).expect(HttpStatusCode.NoContent)
        assertEquals(w.tags.map { ThreadTag(it, true) }, member.thread(w.room).tags)
        w.save(member, emptyList()).expect(HttpStatusCode.NoContent) // clearing is always allowed
        assertEquals(emptyList(), w.names())
    }

    @Test
    fun `the cap is judged before any name is looked at, and twenty tags are accepted`() = testApplication {
        val w = tagged("ttag-cap", count = 21)
        val member = w.cast.member.client
        // 21 names, the last of them garbage: the count answers first, with the cap in the detail, not the grammar.
        val tooMany = w.save(member, w.tags.take(20) + "bad\u0007control")
        tooMany.expect(HttpStatusCode.BadRequest)
        assertTrue("at most 20" in tooMany.bodyAsText(), tooMany.bodyAsText())
        w.save(member, w.tags.take(20)).expect(HttpStatusCode.NoContent)
        assertEquals(20, w.names().size)
    }

    @Test
    fun `the history lists a tag change as TAGS_CHANGED with its actor`() = testApplication {
        val w = tagged("ttag-hist")
        val (a, b) = w.tags
        w.save(w.cast.member.client, listOf(a, b)).expect(HttpStatusCode.NoContent)
        val events = w.cast.admin.client.get("$rooms/${w.room}/threads/1/events?sort=-id").body<ThreadEventPageResponse>().items
        val latest = events.first()
        assertEquals("TAGS_CHANGED", latest.type.name)
        assertEquals(w.cast.member.id, latest.actorId)
        assertEquals(mapOf("added" to "$a, $b"), latest.params)
        val stored = TestThreads.rawEvents(TestThreads.raw(w.room, 1)!!.id).single { it.type == "TAGS_CHANGED" }
        assertEquals(mapOf("added" to "$a, $b"), decodeParams(stored.params))
    }

    @Test
    fun `a real change audits thread tags_changed with ids, counts and names - a no-op and a refusal audit nothing`() = testApplication {
        val w = tagged("ttag-audit")
        val (a, b) = w.tags
        withAuditCapture { capture ->
            w.save(w.cast.member.client, listOf(a, b)).expect(HttpStatusCode.NoContent)
            val event = capture.awaitEvent { it.message == "thread.tags_changed" && it.hasKeyValue("roomId", w.room.toLong()) }
            assertNotNull(event)
            assertTrue(event.hasKeyValue("byUserId", w.cast.member.id.toLong()))
            assertTrue(event.hasKeyValue("threadId", w.cast.threadId.toLong()))
            assertTrue(event.hasKeyValue("number", 1))
            assertTrue(event.hasKeyValue("revision", 2L))
            assertTrue(event.hasKeyValue("addedCount", 2))
            assertTrue(event.hasKeyValue("removedCount", 0))
            assertTrue(event.hasKeyValue("added", "$a,$b"))
            assertTrue(event.hasKeyValue("removed", ""))

            w.save(w.cast.member.client, listOf(b, a)).expect(HttpStatusCode.NoContent) // the same set
            w.cast.member.client.putTags(w.room, listOf(a), 1).expect(HttpStatusCode.Conflict) // stale
            w.save(w.cast.member.client, listOf("nope-${UUID.randomUUID()}")).expect(HttpStatusCode.BadRequest)
            assertEquals(1, capture.events.count { it.message == "thread.tags_changed" })
        }
    }

    @Test
    fun `a thread's tag rows go with the thread, and a registry purge clears them`() = testApplication {
        val w = tagged("ttag-rows")
        w.save(w.cast.member.client, listOf(w.tags.first())).expect(HttpStatusCode.NoContent)
        val threadId = w.cast.threadId.toInt()
        fun rows(): Int = PostgresTestSupport.connect().use { c ->
            c.prepareStatement("SELECT count(*) FROM thread_tags WHERE thread_id = ?").use { st ->
                st.setInt(1, threadId)
                st.executeQuery().use { rs -> rs.next(); rs.getInt(1) }
            }
        }
        assertEquals(1, rows())
        TestRooms.purge(listOf(w.room))
        assertEquals(0, rows(), "the thread's cascade took the row")
    }

    @Test
    fun `denials are audited - authz denied on the closed-thread 403, room access_denied on a non-member's 404`() =
        testApplication {
            val w = tagged("ttag-deny")
            TestThreads.closeRaw(w.room, 1)
            val revision = w.revision()
            withAuditCapture { capture ->
                w.cast.member.client.putTags(w.room, listOf(w.tags.first()), revision).expect(HttpStatusCode.Forbidden)
                val denied = capture.awaitEvent { it.message == "authz.denied" && it.hasKeyValue("byUserId", w.cast.member.id.toLong()) }
                assertNotNull(denied)
                assertTrue(denied.hasKeyValue("method", "PUT") && denied.hasKeyValue("path", "$rooms/${w.room}/threads/1/tags"))
                w.cast.outsider.client.putTags(w.room, listOf(w.tags.first()), revision).expect(HttpStatusCode.NotFound)
                val outsider = w.cast.outsider.id.toLong()
                val probe = capture.awaitEvent { it.message == "room.access_denied" && it.hasKeyValue("byUserId", outsider) }
                assertNotNull(probe)
                assertTrue(probe.hasKeyValue("roomId", w.room.toLong()))
                assertTrue(capture.events.none { it.message == "thread.tags_changed" }, "a refusal audits no change")
            }
        }

    private fun tagRows(threadId: UInt): Int = PostgresTestSupport.connect().use { c ->
        c.prepareStatement("SELECT count(*) FROM thread_tags WHERE thread_id = ?").use { st ->
            st.setInt(1, threadId.toInt())
            st.executeQuery().use { rs -> rs.next(); rs.getInt(1) }
        }
    }

    @Test
    fun `race - a thread closed while a member's tag save waits on the room lock is a 403 and nothing is written`() = testApplication {
        val w = tagged("ttag-race-close")
        val revision = w.revision()
        TestThreads.holdingRoomLock(
            w.room,
            "UPDATE threads SET status_key = 'done', closed_at = ? WHERE room_id = ? AND \"number\" = ?",
            1_000u, w.room, 1u,
        ) { raw ->
            coroutineScope {
                // The route's pre-check passes (the close is uncommitted: the thread is still open); the save then waits for the room lock.
                val saving = async { w.cast.member.client.putTags(w.room, listOf(w.tags.first()), revision) }
                awaitLockWait("FOR NO KEY UPDATE")
                raw.commit()
                assertEquals(HttpStatusCode.Forbidden, saving.await().status)
            }
        }
        assertEquals(0, tagRows(w.cast.threadId), "no tag row")
        assertEquals(listOf("CREATED"), TestThreads.rawEvents(w.cast.threadId).map { it.type }, "no event")
        assertEquals(revision, TestThreads.raw(w.room, 1)!!.revision, "revision unchanged")
    }

    @Test
    fun `race - a member removed while their tag save waits on the room lock is a 404 and nothing is written`() = testApplication {
        val w = tagged("ttag-race-removed")
        val revision = w.revision()
        TestThreads.holdingRoomLock(
            w.room,
            "DELETE FROM room_members WHERE room_id = ? AND user_id = ?",
            w.room, w.cast.member.id,
        ) { raw ->
            coroutineScope {
                val saving = async { w.cast.member.client.putTags(w.room, listOf(w.tags.first()), revision) }
                awaitLockWait("FOR NO KEY UPDATE")
                raw.commit()
                assertEquals(HttpStatusCode.NotFound, saving.await().status)
            }
        }
        assertEquals(0, tagRows(w.cast.threadId))
        assertEquals(listOf("CREATED"), TestThreads.rawEvents(w.cast.threadId).map { it.type })
        assertEquals(revision, TestThreads.raw(w.room, 1)!!.revision)
    }
}
