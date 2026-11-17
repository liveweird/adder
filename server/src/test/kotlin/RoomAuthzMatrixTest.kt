package ch.nokillswit

import ch.nokillswit.authz.RoomRole
import ch.nokillswit.rooms.RoomUpdateRequest
import ch.nokillswit.users.UserRole
import io.ktor.client.HttpClient
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.request.put
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking

/**
 * The room authorization matrix (`authorization.md` "Rooms"): for every implemented room operation, what an outsider, a plain member, the
 * room's OWNER and an ADMIN who is not a member each get. A non-member (an ADMIN excepted) always sees the uniform `404`, so the room's
 * existence is never disclosed; a plain member is `403` on every write; ADMIN reads and manages every room. Each cell gets a FRESH room
 * (a delete or an archive would poison the next), seeded through the service. The member and workflow-write rows join as C8 and C9 land.
 */
class RoomAuthzMatrixTest {
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

    private class Operation(
        val name: String,
        val outsider: HttpStatusCode,
        val member: HttpStatusCode,
        val owner: HttpStatusCode,
        val admin: HttpStatusCode,
        val call: suspend HttpClient.(UInt) -> HttpResponse,
    )

    private val ok = HttpStatusCode.OK
    private val noContent = HttpStatusCode.NoContent
    private val notFound = HttpStatusCode.NotFound
    private val forbidden = HttpStatusCode.Forbidden

    private fun update() = RoomUpdateRequest("Renamed ${UUID.randomUUID()}".take(60))

    private val operations = listOf(
        Operation("GET room", notFound, ok, ok, ok) { get("$rooms/$it") },
        Operation("GET workflow", notFound, ok, ok, ok) { get("$rooms/$it/workflow") },
        Operation("PUT room", notFound, forbidden, noContent, noContent) { putJson("$rooms/$it", update()) },
        Operation("POST archive", notFound, forbidden, noContent, noContent) { post("$rooms/$it/archive") },
        // The room is archived first, so the 204s are real unarchives, not no-ops.
        Operation("POST unarchive", notFound, forbidden, noContent, noContent) {
            TestRooms.archive(it)
            post("$rooms/$it/unarchive")
        },
        Operation("DELETE room", notFound, forbidden, forbidden, noContent) { delete("$rooms/$it") },
    )

    private class Cast(val owner: RoomActor, val member: RoomActor, val outsider: RoomActor, val admin: RoomActor)

    private suspend fun io.ktor.server.testing.ApplicationTestBuilder.cast(): Cast {
        suspend fun actor(prefix: String, role: UserRole = UserRole.USER) = seedActor("mx-$prefix", role).also { mintedUsers += it.id }
        return Cast(actor("owner"), actor("member"), actor("outsider"), actor("admin", UserRole.ADMIN))
    }

    private suspend fun freshRoom(cast: Cast): UInt {
        val template = templateId ?: TestTemplates.seed("mx").also { templateId = it }
        return TestRooms.seed(cast.owner.id, template, "Matrix ${UUID.randomUUID()}").also {
            TestRooms.addMember(it, cast.member.id, RoomRole.MEMBER)
            mintedRooms += it
        }
    }

    @Test
    fun `every room operation answers each kind of caller as the matrix says`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast()
        for (operation in operations) {
            val callers = listOf(
                Triple("outsider", cast.outsider, operation.outsider),
                Triple("member", cast.member, operation.member),
                Triple("owner", cast.owner, operation.owner),
                Triple("admin", cast.admin, operation.admin),
            )
            for ((who, actor, expected) in callers) {
                val response = operation.call(actor.client, freshRoom(cast))
                assertEquals(expected, response.status, "${operation.name} as $who")
            }
        }
    }

    @Test
    fun `an unknown or deleted room is 404 for everyone - the administrator included - on every operation`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast()
        val deleted = freshRoom(cast)
        assertEquals(noContent, cast.admin.client.delete("$rooms/$deleted").status)
        for (operation in operations) {
            for (room in listOf(999_999u, deleted)) {
                for (actor in listOf(cast.outsider, cast.member, cast.owner, cast.admin)) {
                    assertEquals(notFound, operation.call(actor.client, room).status, "${operation.name} on $room")
                }
            }
        }
    }

    @Test
    fun `a malformed body is 404 for an outsider, 403 for a plain member and 400 only for someone who may write`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast()
        val room = freshRoom(cast)
        suspend fun HttpClient.malformedPut() = put("$rooms/$room") {
            contentType(ContentType.Application.Json)
            setBody("{ not json")
        }
        assertEquals(notFound, cast.outsider.client.malformedPut().status)
        assertEquals(forbidden, cast.member.client.malformedPut().status)
        assertEquals(HttpStatusCode.BadRequest, cast.owner.client.malformedPut().status)
        assertEquals(HttpStatusCode.BadRequest, cast.admin.client.malformedPut().status)
    }

    @Test
    fun `an administrator outside the room really unarchives an archived room`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast()
        val room = freshRoom(cast)
        TestRooms.archive(room)
        assertNotNull(TestRooms.rawRoom(room)?.archivedAt)
        assertEquals(noContent, cast.admin.client.post("$rooms/$room/unarchive").status)
        assertNull(TestRooms.rawRoom(room)?.archivedAt, "the archive flag is cleared")
    }

    @Test
    fun `a plain member's 403 is not a hidden-room probe - only a non-member emits room access_denied`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast()
        val room = freshRoom(cast)
        withAuditCapture { capture ->
            fun denied(who: RoomActor) = capture.events.count {
                it.message == "room.access_denied" && it.hasKeyValue("roomId", room.toLong()) && it.hasKeyValue("byUserId", who.id.toLong())
            }
            assertEquals(forbidden, cast.member.client.putJson("$rooms/$room", update()).status)
            assertEquals(forbidden, cast.member.client.post("$rooms/$room/archive").status)
            assertEquals(forbidden, cast.member.client.delete("$rooms/$room").status)
            assertEquals(ok, cast.member.client.get("$rooms/$room").status)
            assertEquals(notFound, cast.outsider.client.get("$rooms/$room").status)
            assertEquals(0, denied(cast.member), "a member is not a probe")
            assertEquals(1, denied(cast.outsider))
        }
    }

    @Test
    fun `the OWNER of a room is not an administrator - delete stays 403 for them even on an archived room`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast()
        val room = freshRoom(cast)
        assertEquals(noContent, cast.owner.client.post("$rooms/$room/archive").status)
        assertEquals(forbidden, cast.owner.client.delete("$rooms/$room").status)
        assertEquals(ok, cast.owner.client.get("$rooms/$room").status, "the room is still there")
    }
}
