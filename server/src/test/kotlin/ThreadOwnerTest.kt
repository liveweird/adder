package ch.nokillswit

import ch.nokillswit.authz.RoomAccessType
import ch.nokillswit.infra.MUTATION_ID_HEADER
import ch.nokillswit.infra.json.decodeParams
import ch.nokillswit.infra.notify.LiveKind
import ch.nokillswit.plugins.ProblemDetail
import ch.nokillswit.rooms.ROOM_ARCHIVED_PROBLEM_TYPE
import ch.nokillswit.threads.THREAD_CLOSED_PROBLEM_TYPE
import ch.nokillswit.threads.ThreadOwnerRequest
import ch.nokillswit.threads.ThreadResponse
import ch.nokillswit.threads.ThreadStatusRequest
import ch.nokillswit.users.UserRole
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
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
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking

/**
 * The owner hand-over (0.3.0 S4, `PUT /rooms/{id}/threads/{number}/owner`): each steward may pass the thread on, a plain member
 * cannot (403, before the body), the new owner must be an ACTIVE member (400), a closed thread and an archived room are typed 409s, the
 * current owner again is a no-op, a removed owner's thread is passed on by a steward, the event, the audit line, and the steward guard
 * re-run under the room lock. Rooms, templates and users are SHARED suite state: every test mints UNIQUE rows and removes them.
 */
class ThreadOwnerTest {
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

    private class Cast(
        val owner: RoomActor,
        val threadOwner: RoomActor,
        val member: RoomActor,
        val outsider: RoomActor,
        val admin: RoomActor,
        val room: UInt,
    )

    /** A room owned by `owner` with thread #1 started by `threadOwner`; `member` is another plain member, `outsider` is no member. */
    private suspend fun ApplicationTestBuilder.cast(prefix: String): Cast {
        suspend fun actor(suffix: String, role: UserRole = UserRole.USER, name: String = "Test") =
            seedActor("$prefix-$suffix", role, name).also { mintedUsers += it.id }
        val owner = actor("own", name = "Olivia")
        val threadOwner = actor("thr", name = "Tomasz")
        val member = actor("mem", name = "Marek")
        val outsider = actor("out")
        val admin = actor("adm", UserRole.ADMIN)
        val template = TestThreads.seedTemplate().also { mintedTemplates += it }
        val room = TestRooms.seed(
            owner.id, template, "Own ${UUID.randomUUID().toString().substring(0, 8)}", accessType = RoomAccessType.PUBLIC,
        ).also { mintedRooms += it }
        TestRooms.addMember(room, threadOwner.id)
        TestRooms.addMember(room, member.id)
        TestThreads.seed(room, threadOwner.id)
        return Cast(owner, threadOwner, member, outsider, admin, room)
    }

    private suspend fun HttpClient.putOwner(room: UInt, userId: UInt, number: Int = 1, mutationId: String? = null): HttpResponse =
        put("$rooms/$room/threads/$number/owner") {
            contentType(ContentType.Application.Json)
            mutationId?.let { header(MUTATION_ID_HEADER, it) }
            setBody(ThreadOwnerRequest(userId))
        }

    private suspend fun HttpClient.thread(room: UInt): ThreadResponse = get("$rooms/$room/threads/1").body()

    private fun eventTypes(threadId: UInt) = runBlocking { TestThreads.rawEvents(threadId).map { it.type } }

    @Test
    fun `each steward passes the thread on - the event names both owners and shares one instant`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("own-ok")
        TestThreads.setStamps(cast.room, 1, createdAt = 1000, lastActivityAt = 1000) // backdated: the bump below must move it
        val before = cast.owner.client.thread(cast.room)

        // The thread owner (a plain member) hands over to another member; the new owner passes it back; the room owner and an ADMIN do too.
        assertEquals(HttpStatusCode.NoContent, cast.threadOwner.client.putOwner(cast.room, cast.member.id).status)
        val passed = cast.owner.client.thread(cast.room)
        assertEquals(listOf(cast.member.id, "Marek"), listOf(passed.ownerId, passed.ownerName))
        assertEquals(before.createdById, passed.createdById, "the creator never changes")
        assertEquals(before.revision, passed.revision, "the owner is not part of the details document")
        assertEquals(passed.updatedAt, passed.lastActivityAt)
        assertTrue(passed.lastActivityAt > before.lastActivityAt)
        assertEquals(passed.lastActivityAt, TestThreads.roomActivity(cast.room))
        val event = TestThreads.rawEvents(passed.id).single { it.type != "CREATED" }
        assertEquals("OWNER_CHANGED", event.type)
        assertEquals(listOf(cast.threadOwner.id, passed.lastActivityAt), listOf(event.userId, event.createdAt))
        assertEquals(
            mapOf(
                "fromId" to cast.threadOwner.id.toString(),
                "toId" to cast.member.id.toString(),
                "fromName" to "Tomasz",
                "toName" to "Marek",
            ),
            decodeParams(event.params),
        )

        assertEquals(HttpStatusCode.NoContent, cast.owner.client.putOwner(cast.room, cast.owner.id).status, "the room owner")
        assertEquals(HttpStatusCode.NoContent, cast.admin.client.putOwner(cast.room, cast.threadOwner.id).status, "an ADMIN")
        assertEquals(cast.threadOwner.id, cast.owner.client.thread(cast.room).ownerId)
        assertEquals(listOf("CREATED", "OWNER_CHANGED", "OWNER_CHANGED", "OWNER_CHANGED"), eventTypes(passed.id))
    }

    @Test
    fun `a plain member gets 403 - the new owner is a steward and the old one no longer is`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("own-403")
        assertEquals(HttpStatusCode.Forbidden, cast.member.client.putOwner(cast.room, cast.member.id).status)
        assertEquals(cast.threadOwner.id, cast.owner.client.thread(cast.room).ownerId)

        assertEquals(HttpStatusCode.NoContent, cast.threadOwner.client.putOwner(cast.room, cast.member.id).status)
        assertEquals(HttpStatusCode.Forbidden, cast.threadOwner.client.putOwner(cast.room, cast.threadOwner.id).status, "not the owner now")
        // The new owner is now a steward: they may close the thread.
        val close = cast.member.client.put("$rooms/${cast.room}/threads/1/status") {
            contentType(ContentType.Application.Json)
            setBody(ThreadStatusRequest("done"))
        }
        assertEquals(HttpStatusCode.NoContent, close.status)
    }

    @Test
    fun `the new owner must be an active member - a non-member, a deleted user, an unknown id and zero are 400`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("own-400")
        val leaver = seedActor("own-400-gone").also { mintedUsers += it.id }
        TestRooms.addMember(cast.room, leaver.id)
        TestUsers.softDelete(leaver.id)
        for (target in listOf(cast.outsider.id, leaver.id, 999_999u, 0u)) {
            assertEquals(HttpStatusCode.BadRequest, cast.owner.client.putOwner(cast.room, target).status, "target $target")
        }
        val malformed = listOf("{ not json", "{}", """{"ownerId":"x"}""", """{"ownerId":-1}""")
        for (json in malformed) {
            val response = cast.owner.client.put("$rooms/${cast.room}/threads/1/owner") {
                contentType(ContentType.Application.Json)
                setBody(json)
            }
            assertEquals(HttpStatusCode.BadRequest, response.status, json)
        }
        assertEquals(cast.threadOwner.id, cast.owner.client.thread(cast.room).ownerId)
        assertEquals(listOf("CREATED"), eventTypes(cast.owner.client.thread(cast.room).id))
    }

    @Test
    fun `a closed thread and an archived room refuse the hand-over with their typed 409s`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("own-409")
        TestThreads.closeRaw(cast.room, 1)
        val closed = cast.owner.client.putOwner(cast.room, cast.member.id)
        assertEquals(HttpStatusCode.Conflict, closed.status)
        assertEquals(THREAD_CLOSED_PROBLEM_TYPE, closed.body<ProblemDetail>().type)
        assertEquals(
            THREAD_CLOSED_PROBLEM_TYPE,
            cast.owner.client.putOwner(cast.room, cast.outsider.id).body<ProblemDetail>().type,
            "closed is checked before the target",
        )

        TestRooms.archive(cast.room)
        val archived = cast.owner.client.putOwner(cast.room, cast.member.id)
        assertEquals(HttpStatusCode.Conflict, archived.status)
        assertEquals(ROOM_ARCHIVED_PROBLEM_TYPE, archived.body<ProblemDetail>().type, "archived wins over closed")
        assertEquals(cast.threadOwner.id, cast.owner.client.thread(cast.room).ownerId)
    }

    @Test
    fun `naming the current owner is a 204 with no event, no audit line and no bump`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("own-noop")
        val before = TestThreads.raw(cast.room, 1)!!
        val activity = TestThreads.roomActivity(cast.room)
        withAuditCapture { capture ->
            assertEquals(HttpStatusCode.NoContent, cast.owner.client.putOwner(cast.room, cast.threadOwner.id).status)
            assertTrue(capture.events.none { it.message == "thread.owner_changed" })
        }
        assertEquals(before, TestThreads.raw(cast.room, 1))
        assertEquals(activity, TestThreads.roomActivity(cast.room))
        assertEquals(listOf("CREATED"), eventTypes(before.id))
    }

    @Test
    fun `a hand-over correlates only its owner hint and validates the optional header`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("own-correlation")
        val mutationId = "2c6c7f12-79a4-4f1a-8dc7-e9686608b20a"
        NotifyCapture().use { hints ->
            val emitted = hints.during {
                val response = cast.threadOwner.client.putOwner(
                    cast.room,
                    cast.member.id,
                    mutationId = mutationId,
                )
                assertEquals(HttpStatusCode.NoContent, response.status)
            }
            val ownerHint = emitted.single { it.kind == LiveKind.THREAD_OWNER_CHANGED }
            assertEquals(listOf(mutationId, cast.threadOwner.id), listOf(ownerHint.mutationId, ownerHint.originUserId))
            assertTrue(emitted.filter { it.kind == LiveKind.NOTIFICATION_CREATED }.all { it.mutationId == null })
            val noOp = hints.during {
                val response = cast.member.client.putOwner(
                    cast.room,
                    cast.member.id,
                    mutationId = mutationId,
                )
                assertEquals(HttpStatusCode.NoContent, response.status)
            }
            assertTrue(noOp.none { it.kind == LiveKind.THREAD_OWNER_CHANGED })
        }
        for (invalid in listOf(mutationId.uppercase(), " $mutationId", "$mutationId ")) {
            val response = cast.member.client.putOwner(
                cast.room,
                cast.threadOwner.id,
                mutationId = invalid,
            )
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }
    }

    @Test
    fun `a removed owner keeps the thread, inert - the room owner passes it on and the removed owner then sees 404`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("own-gone")
        PostgresTestSupport.connect().use { c ->
            c.prepareStatement("DELETE FROM room_members WHERE room_id = ? AND user_id = ?").use { st ->
                st.setInt(1, cast.room.toInt())
                st.setLong(2, cast.threadOwner.id.toLong())
                assertEquals(1, st.executeUpdate())
            }
        }
        assertEquals(cast.threadOwner.id, cast.owner.client.thread(cast.room).ownerId, "the thread keeps its owner")
        assertEquals(HttpStatusCode.NotFound, cast.threadOwner.client.putOwner(cast.room, cast.member.id).status, "no longer a reader")
        assertEquals(HttpStatusCode.Forbidden, cast.member.client.putOwner(cast.room, cast.member.id).status, "no steward")
        assertEquals(HttpStatusCode.NoContent, cast.owner.client.putOwner(cast.room, cast.member.id).status)
        assertEquals(cast.member.id, cast.owner.client.thread(cast.room).ownerId)
    }

    @Test
    fun `naming the current owner is a 204 even after they left the room - nothing is set, so nothing is validated`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("own-left")
        PostgresTestSupport.connect().use { c ->
            c.prepareStatement("DELETE FROM room_members WHERE room_id = ? AND user_id = ?").use { st ->
                st.setInt(1, cast.room.toInt())
                st.setLong(2, cast.threadOwner.id.toLong())
                assertEquals(1, st.executeUpdate())
            }
        }
        val before = TestThreads.raw(cast.room, 1)!!
        withAuditCapture { capture ->
            assertEquals(HttpStatusCode.NoContent, cast.owner.client.putOwner(cast.room, cast.threadOwner.id).status)
            assertTrue(capture.events.none { it.message == "thread.owner_changed" })
        }
        assertEquals(before, TestThreads.raw(cast.room, 1))
        assertEquals(listOf("CREATED"), eventTypes(before.id))
        // A DIFFERENT stranger is still refused.
        assertEquals(HttpStatusCode.BadRequest, cast.owner.client.putOwner(cast.room, cast.outsider.id).status)
    }

    @Test
    fun `an unknown thread is 404 before anything else, and a non-member never learns the thread exists`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("own-404")
        assertEquals(HttpStatusCode.NotFound, cast.owner.client.putOwner(cast.room, cast.member.id, number = 9).status)
        assertEquals(HttpStatusCode.NotFound, cast.member.client.putOwner(cast.room, cast.member.id, number = 9).status, "404 first")
        assertEquals(HttpStatusCode.NotFound, cast.outsider.client.putOwner(cast.room, cast.member.id).status)
    }

    @Test
    fun `a hand-over audits thread owner_changed with ids only, and a refusal audits no thread line`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("own-aud")
        val threadId = cast.owner.client.thread(cast.room).id.toLong()
        withAuditCapture { capture ->
            assertEquals(HttpStatusCode.NoContent, cast.owner.client.putOwner(cast.room, cast.member.id).status)
            assertEquals(HttpStatusCode.Forbidden, cast.threadOwner.client.putOwner(cast.room, cast.member.id).status)
            assertEquals(HttpStatusCode.BadRequest, cast.owner.client.putOwner(cast.room, cast.outsider.id).status)
            val lines = capture.events.filter { it.message == "thread.owner_changed" && it.hasKeyValue("roomId", cast.room.toLong()) }
            val line = assertNotNull(lines.singleOrNull(), "one write, one line")
            assertTrue(line.hasKeyValue("byUserId", cast.owner.id.toLong()))
            assertTrue(line.hasKeyValue("threadId", threadId) && line.hasKeyValue("number", 1))
            assertTrue(line.hasKeyValue("fromUserId", cast.threadOwner.id.toLong()))
            assertTrue(line.hasKeyValue("toUserId", cast.member.id.toLong()))
        }
    }

    @Test
    fun `the steward guard re-runs under the room lock - an owner replaced while the hand-over waits is a 403`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast("own-race")
        val activity = TestThreads.roomActivity(cast.room)

        TestThreads.holdingRoomLock(
            cast.room,
            "UPDATE threads SET owner_id = ? WHERE room_id = ? AND \"number\" = ?",
            cast.member.id, cast.room, 1u,
        ) { raw ->
            coroutineScope {
                // The route's pre-check passes (the owner swap is uncommitted); the write then waits for the room lock.
                val passing = async { cast.threadOwner.client.putOwner(cast.room, cast.owner.id) }
                awaitLockWait("FOR NO KEY UPDATE")
                raw.commit()
                assertEquals(HttpStatusCode.Forbidden, passing.await().status)
            }
        }
        assertEquals(cast.member.id, TestThreads.raw(cast.room, 1)!!.ownerId, "only the rival's swap")
        assertEquals(activity, TestThreads.roomActivity(cast.room))
    }
}
