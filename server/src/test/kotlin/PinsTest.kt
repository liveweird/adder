package ch.nokillswit

import ch.nokillswit.authz.CallerPrincipal
import ch.nokillswit.infra.notify.LiveKind
import ch.nokillswit.infra.paging.PageResponse
import ch.nokillswit.plugins.ProblemDetail
import ch.nokillswit.rooms.ROOM_ARCHIVED_PROBLEM_TYPE
import ch.nokillswit.threads.MAX_ROOM_PINS
import ch.nokillswit.threads.MentionedUser
import ch.nokillswit.threads.MessageRequest
import ch.nokillswit.threads.MessageResponse
import ch.nokillswit.threads.PIN_LIMIT_PROBLEM_TYPE
import ch.nokillswit.threads.PinKind
import ch.nokillswit.threads.PinRequest
import ch.nokillswit.threads.ReactionRequest
import ch.nokillswit.threads.RoomPinResponse
import ch.nokillswit.threads.ThreadStatusRequest
import ch.nokillswit.users.UserRole
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking

/**
 * The room's pins (V21, `PUT .../threads/{number}/pin`, `PUT /rooms/{id}/messages/{messageId}/pin`, `GET /rooms/{id}/pins`): any member
 * pins and unpins, a pin of what is pinned is a quiet `204`, the 51st is a typed `409`, a closed thread can be pinned and an archived
 * room refuses, a deleted message loses its pin, and the guard order (404, then 403, then 400). Rooms, templates and users are SHARED
 * suite state: every test mints UNIQUE rows and removes them.
 */
class PinsTest {
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

    /** A room owned by `owner`, with `member` (who started thread #1), `outsider` (no member) and `admin` (an ADMIN, no member). */
    private class Cast(
        val owner: RoomActor,
        val member: RoomActor,
        val outsider: RoomActor,
        val admin: RoomActor,
        val room: UInt,
        val threadId: UInt,
    )

    private suspend fun ApplicationTestBuilder.cast(prefix: String): Cast {
        suspend fun actor(suffix: String, role: UserRole = UserRole.USER, name: String = "Test") =
            seedActor("$prefix-$suffix", role, name).also { mintedUsers += it.id }
        val owner = actor("own", name = "Olivia")
        val member = actor("mem", name = "Marek")
        val outsider = actor("out")
        val admin = actor("adm", UserRole.ADMIN)
        val template = TestThreads.seedTemplate().also { mintedTemplates += it }
        val room = TestRooms.seed(owner.id, template, "Pins ${UUID.randomUUID().toString().substring(0, 8)}").also { mintedRooms += it }
        TestRooms.addMember(room, member.id)
        val thread = TestThreads.seed(room, member.id, title = "Awaria bazy")
        return Cast(owner, member, outsider, admin, room, thread.id)
    }

    private suspend fun HttpClient.pinThread(room: UInt, number: Int, pinned: Boolean = true): HttpResponse =
        putJson("$rooms/$room/threads/$number/pin", PinRequest(pinned))

    private suspend fun HttpClient.pinMessage(room: UInt, message: UInt, pinned: Boolean = true): HttpResponse =
        putJson("$rooms/$room/messages/$message/pin", PinRequest(pinned))

    private suspend fun HttpClient.pins(room: UInt, query: String = ""): HttpResponse = get("$rooms/$room/pins$query")

    private suspend fun HttpClient.pinList(room: UInt, query: String = ""): PageResponse<RoomPinResponse> = pins(room, query).body()

    @Test
    fun `a member pins a thread and a message and the room lists both, the newest pin first`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("pin-ok")
        val message = TestThreads.post(cast.room, 1, cast.member.id, "The runbook is in the wiki")
        withAuditCapture { capture ->
            assertEquals(HttpStatusCode.NoContent, cast.member.client.pinThread(cast.room, 1).status)
            assertEquals(HttpStatusCode.NoContent, cast.owner.client.pinMessage(cast.room, message).status)

            val page = cast.member.client.pinList(cast.room)
            assertEquals(2, page.total)
            assertEquals(listOf(PinKind.MESSAGE, PinKind.THREAD), page.items.map { it.kind }, "the newest pin first")
            val (byOwner, byMember) = page.items
            assertEquals(listOf(cast.owner.id, "Olivia"), listOf(byOwner.pinnedById, byOwner.pinnedByName))
            assertEquals(listOf(cast.member.id, "Marek"), listOf(byMember.pinnedById, byMember.pinnedByName))
            assertTrue(byOwner.pinnedAt >= byMember.pinnedAt)

            val lines = capture.events.filter { it.message == "room.pin_changed" && it.hasKeyValue("roomId", cast.room.toLong()) }
            assertEquals(2, lines.size, "one line per change")
            val threadLine = lines.single { it.hasKeyValue("messageId", null) }
            assertTrue(threadLine.hasKeyValue("byUserId", cast.member.id.toLong()) && threadLine.hasKeyValue("pinned", true))
            assertTrue(threadLine.hasKeyValue("threadId", cast.threadId.toLong()))
            val messageLine = lines.single { it.hasKeyValue("messageId", message.toLong()) }
            assertTrue(messageLine.hasKeyValue("byUserId", cast.owner.id.toLong()) && messageLine.hasKeyValue("pinned", true))

            // Any member unpins, whoever pinned: the owner takes down the member's pin.
            assertEquals(HttpStatusCode.NoContent, cast.owner.client.pinThread(cast.room, 1, pinned = false).status)
            assertEquals(listOf(PinKind.MESSAGE), cast.member.client.pinList(cast.room).items.map { it.kind })
            val unpinned = capture.events.filter { it.message == "room.pin_changed" && it.hasKeyValue("pinned", false) }
            assertEquals(1, unpinned.size)
            val everything = capture.events.joinToString { e ->
                "${e.formattedMessage} ${e.keyValuePairs?.joinToString { "${it.key}=${it.value}" }}"
            }
            assertTrue("runbook" !in everything && "Awaria" !in everything, "no body or title reaches the audit trail")
        }
    }

    @Test
    fun `a listed message reads as it reads now, and a listed thread carries its title and status`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("pin-read")
        val message = TestThreads.post(cast.room, 1, cast.member.id, "As first written")
        assertEquals(HttpStatusCode.NoContent, cast.member.client.pinMessage(cast.room, message).status)
        assertEquals(HttpStatusCode.NoContent, cast.member.client.pinThread(cast.room, 1).status)
        assertEquals(
            HttpStatusCode.NoContent,
            cast.member.client.putJson("$rooms/${cast.room}/messages/$message", MessageRequest("Edited since")).status,
        )
        val close = cast.owner.client.putJson("$rooms/${cast.room}/threads/1/status", ThreadStatusRequest("done"))
        assertEquals(HttpStatusCode.NoContent, close.status)

        val items = cast.admin.client.pinList(cast.room).items // an ADMIN outside the room reads the pins
        val ofMessage = items.single { it.kind == PinKind.MESSAGE }
        val read = cast.member.client.get("$rooms/${cast.room}/messages/$message").body<MessageResponse>()
        assertEquals(read, ofMessage.message, "the ordinary message, as it reads now")
        assertEquals("Edited since", ofMessage.message?.body)
        val ofThread = items.single { it.kind == PinKind.THREAD }
        assertNull(ofThread.message)
        assertEquals(listOf("Awaria bazy", "done", true), listOf(ofThread.threadTitle, ofThread.threadStatus, ofThread.threadClosed))
        assertEquals(listOf(1, 1), items.map { it.threadNumber })
    }

    @Test
    fun `a pinned message's mentions come back in the pins list, as they read in the conversation`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("pin-mention")
        val message = TestThreads.post(cast.room, 1, cast.member.id, "Please look, <@${cast.owner.id}>")
        assertEquals(HttpStatusCode.NoContent, cast.member.client.pinMessage(cast.room, message).status)

        val pinned = cast.owner.client.pinList(cast.room).items.single().message
        assertNotNull(pinned)
        assertEquals(listOf(MentionedUser(cast.owner.id, "Olivia")), pinned.mentions)
        val read = cast.owner.client.get("$rooms/${cast.room}/messages/$message").body<MessageResponse>()
        assertEquals(read, pinned, "the same message the single read answers, mentions included")
    }

    @Test
    fun `a pinned message's reactions come back in the pins list, marked for the one who asks`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("pin-reaction")
        val message = TestThreads.post(cast.room, 1, cast.member.id, "React to the pin")
        assertEquals(HttpStatusCode.NoContent, cast.member.client.pinMessage(cast.room, message).status)
        val thumbsUp = String(Character.toChars(0x1F44D))
        val party = String(Character.toChars(0x1F389))
        suspend fun react(who: RoomActor, emoji: String) =
            who.client.putJson("$rooms/${cast.room}/messages/$message/reaction", ReactionRequest(emoji, true)).status
        assertEquals(HttpStatusCode.NoContent, react(cast.owner, thumbsUp))
        assertEquals(HttpStatusCode.NoContent, react(cast.member, party))

        suspend fun reactionsFor(who: RoomActor) = who.client.pinList(cast.room).items.single().message!!.reactions
        // As the owner: the thumbs up is theirs. As the member: the party is. An ADMIN outside the room reads, and has reacted to nothing.
        assertEquals(listOf(thumbsUp to true, party to false), reactionsFor(cast.owner).map { it.emoji to it.reacted })
        assertEquals(listOf(thumbsUp to false, party to true), reactionsFor(cast.member).map { it.emoji to it.reacted })
        assertEquals(listOf(thumbsUp to false, party to false), reactionsFor(cast.admin).map { it.emoji to it.reacted })
        assertEquals(listOf(listOf("Olivia"), listOf("Marek")), reactionsFor(cast.owner).map { it.names })
        // The same message the single read answers to the same reader, reactions included.
        for (who in listOf(cast.owner, cast.member)) {
            val read = who.client.get("$rooms/${cast.room}/messages/$message").body<MessageResponse>()
            assertEquals(read, who.client.pinList(cast.room).items.single().message)
        }
    }

    @Test
    fun `a pinned quoting message carries its quote in the pins list, as the conversation reads it`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("pin-quote")
        val quoted = TestThreads.post(cast.room, 1, cast.owner.id, "What the owner said")
        val quoting = TestThreads.messages.post(
            cast.room, 1, callerOf(cast.member), MessageRequest("An answer to it"), quotedMessageId = quoted,
        ).message.id
        assertEquals(HttpStatusCode.NoContent, cast.member.client.pinMessage(cast.room, quoting).status)

        val pinned = cast.owner.client.pinList(cast.room).items.single().message
        assertNotNull(pinned)
        assertEquals(quoted, pinned.quote?.messageId)
        assertEquals("What the owner said", pinned.quote?.body)
        assertEquals("Olivia", pinned.quote?.authorName)
        val read = cast.owner.client.get("$rooms/${cast.room}/messages/$quoting").body<MessageResponse>()
        assertEquals(read, pinned, "the same message the single read answers, its quote included")
        // The quoted message is deleted: the pin's quote reads as a deleted one, with no text.
        assertEquals(HttpStatusCode.NoContent, cast.owner.client.delete("$rooms/${cast.room}/messages/$quoted").status)
        val afterDelete = cast.owner.client.pinList(cast.room).items.single().message!!.quote!!
        assertEquals(listOf(true, null, false), listOf(afterDelete.deleted, afterDelete.body, afterDelete.truncated))
    }

    @Test
    fun `pinning what is pinned and unpinning what is not are 204 with no hint and no audit line`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("pin-noop")
        val message = TestThreads.post(cast.room, 1, cast.member.id, "Steady")
        NotifyCapture().use { capture ->
            withAuditCapture { audit ->
                val hints = capture.during {
                    assertEquals(HttpStatusCode.NoContent, cast.member.client.pinThread(cast.room, 1, pinned = false).status)
                    assertEquals(HttpStatusCode.NoContent, cast.member.client.pinMessage(cast.room, message, pinned = false).status)
                    assertEquals(HttpStatusCode.NoContent, cast.member.client.pinThread(cast.room, 1).status) // real
                    assertEquals(HttpStatusCode.NoContent, cast.owner.client.pinThread(cast.room, 1).status) // again
                }
                assertEquals(listOf(LiveKind.ROOM_PIN_CHANGED), hints.map { it.kind }, "only the one real change is announced")
                assertEquals(1, audit.events.count { it.message == "room.pin_changed" && it.hasKeyValue("roomId", cast.room.toLong()) })
            }
        }
        assertEquals(1, cast.member.client.pinList(cast.room).total)
    }

    @Test
    fun `the 51st pin is refused, a pin already there still answers 204, and an unpin frees a place`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("pin-limit")
        val messages = List(MAX_ROOM_PINS) { TestThreads.post(cast.room, 1, cast.member.id, "Message $it") }
        // 49 message pins and the thread itself make fifty, through the service: the route is for the one that must be refused.
        messages.take(MAX_ROOM_PINS - 1).forEach { TestThreads.pins.setMessagePin(cast.room, it, callerOf(cast.member), true) }
        TestThreads.pins.setThreadPin(cast.room, 1, callerOf(cast.member), true)
        assertEquals(MAX_ROOM_PINS.toLong(), cast.member.client.pinList(cast.room, "?pageSize=100").total)

        val refused = cast.member.client.pinMessage(cast.room, messages.last())
        assertEquals(HttpStatusCode.Conflict, refused.status)
        assertEquals(PIN_LIMIT_PROBLEM_TYPE, refused.body<ProblemDetail>().type)
        assertEquals(MAX_ROOM_PINS.toLong(), cast.member.client.pinList(cast.room).total, "nothing was written")
        assertEquals(HttpStatusCode.NoContent, cast.member.client.pinMessage(cast.room, messages.first()).status, "already pinned")
        val unpin = cast.owner.client.pinThread(cast.room, 1, pinned = false)
        assertEquals(HttpStatusCode.NoContent, unpin.status, "unpinning is never refused")
        assertEquals(HttpStatusCode.NoContent, cast.member.client.pinMessage(cast.room, messages.last()).status, "a place is free again")
    }

    @Test
    fun `two members pinning the last free place at once - one is 204, the other is 409, and the room holds 50`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("pin-race")
        val messages = List(MAX_ROOM_PINS) { TestThreads.post(cast.room, 1, cast.member.id, "Message $it") }
        // 48 message pins and the thread make 49; the last two messages race for the 50th place.
        messages.take(MAX_ROOM_PINS - 2).forEach { TestThreads.pins.setMessagePin(cast.room, it, callerOf(cast.member), true) }
        TestThreads.pins.setThreadPin(cast.room, 1, callerOf(cast.member), true)
        assertEquals(MAX_ROOM_PINS - 1L, cast.member.client.pinList(cast.room).total)

        val statuses = mutableListOf<HttpStatusCode>()
        TestThreads.holdingRoomLock(cast.room) { raw ->
            coroutineScope {
                // Both requests pass their lock-free checks, then queue for the room lock behind the raw transaction.
                val byMember = async { cast.member.client.pinMessage(cast.room, messages[MAX_ROOM_PINS - 2]) }
                val byOwner = async { cast.owner.client.pinMessage(cast.room, messages[MAX_ROOM_PINS - 1]) }
                awaitLockWait("FOR NO KEY UPDATE", waiters = 2)
                raw.commit()
                statuses += listOf(byMember.await().status, byOwner.await().status)
            }
        }
        assertEquals(listOf(HttpStatusCode.Conflict, HttpStatusCode.NoContent), statuses.sortedBy { it.value }.reversed(), "$statuses")
        assertEquals(MAX_ROOM_PINS.toLong(), cast.member.client.pinList(cast.room).total, "the cap held under two writers")
    }

    private fun callerOf(actor: RoomActor) = CallerPrincipal(actor.id, "fixture@test", emptySet())

    @Test
    fun `a closed thread can be pinned and an archived room refuses`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("pin-state")
        val message = TestThreads.post(cast.room, 1, cast.member.id, "Closing words")
        TestThreads.closeRaw(cast.room, 1)
        assertEquals(HttpStatusCode.NoContent, cast.member.client.pinThread(cast.room, 1).status, "a closed thread")
        assertEquals(HttpStatusCode.NoContent, cast.member.client.pinMessage(cast.room, message).status, "a message of a closed thread")
        assertEquals(2, cast.member.client.pinList(cast.room).total)

        TestRooms.archive(cast.room)
        for (response in listOf(
            cast.member.client.pinThread(cast.room, 1, pinned = false),
            cast.member.client.pinMessage(cast.room, message, pinned = false),
            cast.member.client.pinThread(cast.room, 1),
        )) {
            assertEquals(HttpStatusCode.Conflict, response.status)
            assertEquals(ROOM_ARCHIVED_PROBLEM_TYPE, response.body<ProblemDetail>().type)
        }
        assertEquals(2, cast.member.client.pinList(cast.room).total, "an archived room's pins are read-only")
    }

    @Test
    fun `a deleted message leaves the list, whoever deletes it`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("pin-del")
        val own = TestThreads.post(cast.room, 1, cast.member.id, "Mine")
        val moderated = TestThreads.post(cast.room, 1, cast.member.id, "Moderated")
        val kept = TestThreads.post(cast.room, 1, cast.member.id, "Kept")
        for (id in listOf(own, moderated, kept)) assertEquals(HttpStatusCode.NoContent, cast.owner.client.pinMessage(cast.room, id).status)
        assertEquals(HttpStatusCode.NoContent, cast.owner.client.pinThread(cast.room, 1).status)

        assertEquals(HttpStatusCode.NoContent, cast.member.client.delete("$rooms/${cast.room}/messages/$own").status)
        assertEquals(HttpStatusCode.NoContent, cast.owner.client.delete("$rooms/${cast.room}/messages/$moderated").status)

        val items = cast.member.client.pinList(cast.room).items
        assertEquals(listOf(kept), items.mapNotNull { it.message?.id })
        assertEquals(2, items.size, "the kept message and the thread")
        // The tombstone is gone from the pinned set for good: it answers 404 and cannot be pinned again.
        assertEquals(HttpStatusCode.NotFound, cast.owner.client.pinMessage(cast.room, own).status)
        assertEquals(2, cast.member.client.pinList(cast.room).total)
    }

    @Test
    fun `the list pages and sorts`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("pin-page")
        val ids = List(3) { TestThreads.post(cast.room, 1, cast.member.id, "Page $it") }
        ids.forEach {
            assertEquals(HttpStatusCode.NoContent, cast.member.client.pinMessage(cast.room, it).status)
        }
        val newestFirst = cast.member.client.pinList(cast.room)
        assertEquals(ids.reversed(), newestFirst.items.mapNotNull { it.message?.id }, "the default is -pinnedAt")
        assertEquals(listOf(1, 20), listOf(newestFirst.page, newestFirst.pageSize))
        assertEquals(3L, newestFirst.total)

        assertEquals(ids, cast.member.client.pinList(cast.room, "?sort=pinnedAt").items.mapNotNull { it.message?.id })
        val second = cast.member.client.pinList(cast.room, "?pageSize=2&page=2&sort=pinnedAt")
        assertEquals(listOf(ids.last()), second.items.mapNotNull { it.message?.id })
        assertEquals(3L, second.total, "the total is counted before paging")
        assertEquals(newestFirst.items.map { it.id }.sorted(), cast.member.client.pinList(cast.room, "?sort=id").items.map { it.id })

        assertEquals(HttpStatusCode.BadRequest, cast.member.client.pins(cast.room, "?sort=threadTitle").status)
        assertEquals(HttpStatusCode.BadRequest, cast.member.client.pins(cast.room, "?pageSize=101").status)
        assertEquals(HttpStatusCode.NotFound, cast.outsider.client.pins(cast.room, "?pageSize=101").status, "the 404 comes before the 400")
    }

    @Test
    fun `an outsider is 404, an administrator outside the room reads the list and is 403 on the writes, unknown things are 404`() =
        testApplication {
            usePostgresTestcontainer()
            val cast = cast("pin-authz")
            val message = TestThreads.post(cast.room, 1, cast.member.id, "Words")
            assertEquals(HttpStatusCode.NoContent, cast.member.client.pinThread(cast.room, 1).status)

            assertEquals(HttpStatusCode.NotFound, cast.outsider.client.pins(cast.room).status)
            assertEquals(HttpStatusCode.NotFound, cast.outsider.client.pinThread(cast.room, 1).status)
            assertEquals(HttpStatusCode.NotFound, cast.outsider.client.pinMessage(cast.room, message).status)
            // The outsider's malformed body is still 404; the administrator's is 403 (the guard comes before the body).
            val malformed = "$rooms/${cast.room}/threads/1/pin"
            assertEquals(HttpStatusCode.NotFound, cast.outsider.client.putRaw(malformed, "{}").status)
            assertEquals(HttpStatusCode.Forbidden, cast.admin.client.putRaw(malformed, "{}").status)

            assertEquals(HttpStatusCode.OK, cast.admin.client.pins(cast.room).status)
            assertEquals(HttpStatusCode.Forbidden, cast.admin.client.pinThread(cast.room, 1, pinned = false).status)
            assertEquals(HttpStatusCode.Forbidden, cast.admin.client.pinMessage(cast.room, message).status)
            assertEquals(1, cast.member.client.pinList(cast.room).total, "the refused writes changed nothing")

            assertEquals(HttpStatusCode.NotFound, cast.member.client.pinThread(cast.room, 99).status)
            assertEquals(HttpStatusCode.NotFound, cast.member.client.pinMessage(cast.room, 999_999u).status)
            val elsewhere = cast("pin-authz-other")
            val foreign = TestThreads.post(elsewhere.room, 1, elsewhere.member.id, "Another room")
            assertEquals(HttpStatusCode.NotFound, cast.member.client.pinMessage(cast.room, foreign).status, "another room's message")
            assertEquals(0, elsewhere.member.client.pinList(elsewhere.room).total)
        }

    @Test
    fun `a member's malformed body is 400 and a pin PUT over 16 KiB is 413 before any guard`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("pin-body")
        val path = "$rooms/${cast.room}/threads/1/pin"
        for (body in listOf("{}", """{"pinned":"yes"}""", "{ not json", """{"pinned":null}""")) {
            assertEquals(HttpStatusCode.BadRequest, cast.member.client.putRaw(path, body).status, body)
        }
        val padding = " ".repeat(20 * 1024)
        assertEquals(HttpStatusCode.PayloadTooLarge, cast.member.client.putRaw(path, """{"pinned":true,$padding"x":1}""").status)
        assertEquals(HttpStatusCode.PayloadTooLarge, cast.outsider.client.putRaw(path, """{"pinned":true,$padding"x":1}""").status)
        assertEquals(
            HttpStatusCode.PayloadTooLarge,
            cast.member.client.putRaw("$rooms/${cast.room}/messages/1/pin", """{"pinned":true,$padding"x":1}""").status,
        )
        assertEquals(0, cast.member.client.pinList(cast.room).total)
    }

    @Test
    fun `the pin PUTs spend the chat-write bucket of the user`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("pin-bucket")
        // 240 a minute: unpinning what is not pinned is a cheap 204 that still takes a token.
        repeat(240) {
            val status = cast.member.client.pinThread(cast.room, 1, pinned = false).status
            assertTrue(status != HttpStatusCode.TooManyRequests, "write ${it + 1}: $status")
        }
        assertEquals(HttpStatusCode.TooManyRequests, cast.member.client.pinThread(cast.room, 1).status)
        assertEquals(HttpStatusCode.TooManyRequests, cast.member.client.pinMessage(cast.room, 999_999u).status, "one bucket for both PUTs")
        val status = cast.member.client.putJson("$rooms/${cast.room}/threads/1/status", ThreadStatusRequest("open"))
        assertEquals(HttpStatusCode.TooManyRequests, status.status, "the same bucket as the status PUT")
        assertEquals(HttpStatusCode.OK, cast.member.client.pins(cast.room).status, "reads are not bucketed")
        assertEquals(HttpStatusCode.NoContent, cast.owner.client.pinThread(cast.room, 1).status, "another user has their own bucket")
        assertEquals(1, cast.owner.client.pinList(cast.room).total)
    }

    private suspend fun HttpClient.putRaw(path: String, json: String): HttpResponse = put(path) {
        contentType(ContentType.Application.Json)
        setBody(json)
    }
}
