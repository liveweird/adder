package ch.nokillswit

import ch.nokillswit.infra.revision.EXPECTED_REVISION_HEADER
import ch.nokillswit.plugins.ProblemDetail
import ch.nokillswit.threads.ThreadCreateRequest
import ch.nokillswit.threads.ThreadOwnerRequest
import ch.nokillswit.threads.ThreadStatusRequest
import ch.nokillswit.threads.ThreadUpdateRequest
import ch.nokillswit.users.UserRole
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive

/**
 * The thread authorization matrix (`authorization.md` "Threads"): for every implemented thread operation, what an outsider, a plain
 * member, the THREAD owner (a plain member who started the thread), the room's OWNER and an ADMIN who is not a member each get. A
 * non-member (an ADMIN excepted) always sees the uniform `404`; every reader reads; only members speak, so an ADMIN outside the room is
 * `403` on the POST. The rows: the three reads and the create (S2) and the details PUT (S3) and the status and owner PUTs (S4);
 * the message steps add theirs. Each cell gets a FRESH room,
 * seeded through the services.
 */
class ThreadAuthzMatrixTest {
    private val rooms = "/api/v1/rooms"
    private val mintedRooms = mutableListOf<UInt>()
    private val mintedUsers = mutableListOf<UInt>()
    private var templateId: UInt? = null

    @AfterTest
    fun cleanUp() = runBlocking<Unit> {
        TestRooms.purge(mintedRooms)
        mintedUsers.forEach { TestUsers.softDelete(it) }
        templateId?.let { TestTemplates.delete(it) }
    }

    private class Cast(
        val owner: RoomActor,
        val member: RoomActor,
        val threadOwner: RoomActor,
        val outsider: RoomActor,
        val admin: RoomActor,
    )

    /** One matrix row: the status each kind of caller gets and the call that makes it (the room's base path in, the response out). */
    private class Operation(
        val name: String,
        val outsider: HttpStatusCode,
        val member: HttpStatusCode,
        val threadOwner: HttpStatusCode,
        val owner: HttpStatusCode,
        val admin: HttpStatusCode,
        val prepare: suspend (UInt) -> Unit = {},
        val call: suspend (HttpClient, String, Cast) -> HttpResponse,
    )

    private val ok = HttpStatusCode.OK
    private val created = HttpStatusCode.Created
    private val noContent = HttpStatusCode.NoContent
    private val notFound = HttpStatusCode.NotFound
    private val forbidden = HttpStatusCode.Forbidden
    private val severity = mapOf("severity" to JsonPrimitive("s1"))

    private val operations = listOf(
        Operation("GET threads", notFound, ok, ok, ok, ok) { client, base, _ -> client.get("$base/threads") },
        Operation("GET thread", notFound, ok, ok, ok, ok) { client, base, _ -> client.get("$base/threads/1") },
        Operation("GET events", notFound, ok, ok, ok, ok) { client, base, _ -> client.get("$base/threads/1/events") },
        // Only members speak: an ADMIN who is not a member is 403 (and joins through the Members page first).
        Operation("POST thread", notFound, created, created, created, forbidden) { client, base, _ ->
            client.postJson("$base/threads", ThreadCreateRequest("Matrix", "Body", severity))
        },
        // Any reader edits the open-side metadata: the details PUT (thread #1 is at revision 1 in every fresh room).
        Operation("PUT details", notFound, noContent, noContent, noContent, noContent) { client, base, _ ->
            client.putDetails("$base/threads/1")
        },
        // The status PUT: any reader moves between OPEN statuses; every move that enters or leaves CLOSED needs a steward (the thread
        // owner while a member, the room owner, an ADMIN).
        Operation("PUT status OPEN to OPEN", notFound, noContent, noContent, noContent, noContent) { client, base, _ ->
            client.putStatus("$base/threads/1/status", "monitoring")
        },
        Operation("PUT status close", notFound, forbidden, noContent, noContent, noContent) { client, base, _ ->
            client.putStatus("$base/threads/1/status", "done")
        },
        Operation("PUT status reopen", notFound, forbidden, noContent, noContent, noContent, ::closeThread) { client, base, _ ->
            client.putStatus("$base/threads/1/status", "open")
        },
        Operation("PUT status CLOSED to CLOSED", notFound, forbidden, noContent, noContent, noContent, ::closeThread) { client, base, _ ->
            client.putStatus("$base/threads/1/status", "wontdo")
        },
        // The owner PUT is steward-only; the target is the plain member (an active member of every fresh room).
        Operation("PUT owner", notFound, forbidden, noContent, noContent, noContent) { client, base, cast ->
            client.putOwner("$base/threads/1/owner", cast.member.id)
        },
        // An unknown number is 404 for every caller who may read the room.
        Operation("GET unknown thread", notFound, notFound, notFound, notFound, notFound) { client, base, _ ->
            client.get("$base/threads/99")
        },
        Operation("GET unknown events", notFound, notFound, notFound, notFound, notFound) { client, base, _ ->
            client.get("$base/threads/99/events")
        },
        Operation("PUT unknown thread", notFound, notFound, notFound, notFound, notFound) { client, base, _ ->
            client.putDetails("$base/threads/99")
        },
        Operation("PUT unknown thread status", notFound, notFound, notFound, notFound, notFound) { client, base, _ ->
            client.putStatus("$base/threads/99/status", "monitoring")
        },
        // A plain member is not a steward but the thread is missing: 404 comes before the 403.
        Operation("PUT unknown thread owner", notFound, notFound, notFound, notFound, notFound) { client, base, cast ->
            client.putOwner("$base/threads/99/owner", cast.member.id)
        },
    )

    private suspend fun HttpClient.putDetails(path: String): HttpResponse = put(path) {
        header(EXPECTED_REVISION_HEADER, "1")
        contentType(ContentType.Application.Json)
        setBody(ThreadUpdateRequest("Edited by the matrix"))
    }

    private suspend fun HttpClient.putStatus(path: String, status: String): HttpResponse = put(path) {
        contentType(ContentType.Application.Json)
        setBody(ThreadStatusRequest(status))
    }

    private suspend fun HttpClient.putOwner(path: String, userId: UInt): HttpResponse = put(path) {
        contentType(ContentType.Application.Json)
        setBody(ThreadOwnerRequest(userId))
    }

    private suspend fun closeThread(room: UInt) = TestThreads.closeRaw(room, 1)

    private suspend fun ApplicationTestBuilder.cast(): Cast {
        suspend fun actor(prefix: String, role: UserRole = UserRole.USER) = seedActor("tx-$prefix", role).also { mintedUsers += it.id }
        return Cast(actor("owner"), actor("member"), actor("thread-owner"), actor("outsider"), actor("admin", UserRole.ADMIN))
    }

    /** A fresh room with the fixture thread #1 started by the thread owner. */
    private suspend fun freshRoom(cast: Cast): UInt {
        val template = templateId ?: TestThreads.seedTemplate("tx").also { templateId = it }
        return TestRooms.seed(cast.owner.id, template, "Matrix ${UUID.randomUUID()}").also { room ->
            TestRooms.addMember(room, cast.member.id)
            TestRooms.addMember(room, cast.threadOwner.id)
            mintedRooms += room
            TestThreads.seed(room, cast.threadOwner.id)
        }
    }

    @Test
    fun `every thread operation answers each kind of caller as the matrix says`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast()
        for (operation in operations) {
            val callers = listOf(
                Triple("outsider", cast.outsider, operation.outsider),
                Triple("member", cast.member, operation.member),
                Triple("thread owner", cast.threadOwner, operation.threadOwner),
                Triple("room owner", cast.owner, operation.owner),
                Triple("admin", cast.admin, operation.admin),
            )
            for ((who, actor, expected) in callers) {
                val room = freshRoom(cast)
                operation.prepare(room)
                val response = operation.call(actor.client, "$rooms/$room", cast)
                assertEquals(expected, response.status, "${operation.name} as $who")
            }
        }
    }

    @Test
    fun `an unknown or deleted room is 404 for everyone - the administrator included - on every operation`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast()
        val deleted = freshRoom(cast)
        assertEquals(HttpStatusCode.NoContent, cast.admin.client.delete("$rooms/$deleted").status)
        for (operation in operations) {
            for (room in listOf(999_999u, deleted)) {
                for (actor in listOf(cast.outsider, cast.member, cast.owner, cast.admin)) {
                    assertEquals(notFound, operation.call(actor.client, "$rooms/$room", cast).status, "${operation.name} on $room")
                }
            }
        }
    }

    @Test
    fun `a malformed create body is 404 for an outsider, 403 for an admin outside the room and 400 for a member`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast()
        val path = "$rooms/${freshRoom(cast)}/threads"
        suspend fun HttpClient.malformed() = request(path) {
            method = HttpMethod.Post
            contentType(ContentType.Application.Json)
            setBody("{ not json")
        }
        assertEquals(notFound, cast.outsider.client.malformed().status)
        assertEquals(forbidden, cast.admin.client.malformed().status)
        assertEquals(HttpStatusCode.BadRequest, cast.member.client.malformed().status)
        assertEquals(HttpStatusCode.BadRequest, cast.owner.client.malformed().status)
    }

    @Test
    fun `a non-member and a missing room get the identical problem body on every operation`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast()
        val real = "$rooms/${freshRoom(cast)}"
        for (operation in operations) {
            val hidden = operation.call(cast.outsider.client, real, cast)
            val missing = operation.call(cast.outsider.client, "$rooms/999999", cast)
            assertEquals(notFound, hidden.status, operation.name)
            assertEquals(notFound, missing.status, operation.name)
            // Only `instance` (the request path, which carries the id) may differ.
            val expected = missing.body<ProblemDetail>().copy(instance = null)
            assertEquals(expected, hidden.body<ProblemDetail>().copy(instance = null), operation.name)
        }
    }

    @Test
    fun `a thread is addressed through ITS room - the owner of another room cannot reach it by number`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast()
        val room = freshRoom(cast)
        val rival = seedActor("tx-rival").also { mintedUsers += it.id }
        val rivalRoom = TestRooms.seed(rival.id, templateId!!, "Rival ${UUID.randomUUID()}").also { mintedRooms += it }
        // The rival owns room `rivalRoom` (no threads) and is an outsider to `room`, where thread #1 lives.
        assertEquals(notFound, rival.client.get("$rooms/$room/threads/1").status)
        assertEquals(notFound, rival.client.get("$rooms/$rivalRoom/threads/1").status, "no thread #1 in the rival's own room")
        assertEquals(ok, rival.client.get("$rooms/$rivalRoom/threads").status)
    }
}
