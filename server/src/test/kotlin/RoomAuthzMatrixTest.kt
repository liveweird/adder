package ch.nokillswit

import ch.nokillswit.authz.RoomAccessType
import ch.nokillswit.authz.RoomRole
import ch.nokillswit.infra.revision.EXPECTED_REVISION_HEADER
import ch.nokillswit.rooms.RoomFieldDef
import ch.nokillswit.rooms.RoomMemberRoleRequest
import ch.nokillswit.rooms.RoomMembersAddRequest
import ch.nokillswit.rooms.RoomOptionDef
import ch.nokillswit.rooms.RoomStatusDef
import ch.nokillswit.rooms.RoomUpdateRequest
import ch.nokillswit.rooms.RoomWorkflowRequest
import ch.nokillswit.workflows.FieldType
import ch.nokillswit.workflows.StatusCategory
import ch.nokillswit.workflows.StatusColour
import ch.nokillswit.users.UserRole
import ch.nokillswit.plugins.ProblemDetail
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.request.request
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
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
 * room's OWNER and an ADMIN who is not a member each get in a PUBLIC room. A regular non-member gets the uniform `404` from content
 * routes; a plain member is `403` on every management write; an ADMIN reads and manages a visible room. Each cell gets a FRESH room
 * (a delete or an archive would poison the next), seeded through the service. The member rows are C8's, the workflow-write row
 * C9's.
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
        val call: suspend (Call) -> HttpResponse,
    )

    /** What an operation is called with: the caller's client and id, the cast (for a target) and the room. */
    private class Call(val client: HttpClient, val caller: RoomActor, val cast: Cast, val room: UInt)

    private val ok = HttpStatusCode.OK
    private val noContent = HttpStatusCode.NoContent
    private val notFound = HttpStatusCode.NotFound
    private val forbidden = HttpStatusCode.Forbidden
    private val conflict = HttpStatusCode.Conflict

    private fun update() = RoomUpdateRequest("Renamed ${UUID.randomUUID()}".take(60))

    /** The fixture template's own workflow (`TestTemplates.bilingual`, in English), a PUT of it is valid. */
    private fun workflowBody() = RoomWorkflowRequest(
        statuses = listOf(
            RoomStatusDef("open", "Open", StatusColour.GRAY, StatusCategory.OPEN, initial = true),
            RoomStatusDef("half", "Half only", StatusColour.BLUE, StatusCategory.OPEN, initial = false),
            RoomStatusDef("done", "Done", StatusColour.TEAL, StatusCategory.CLOSED, initial = false),
        ),
        fields = listOf(
            RoomFieldDef(
                "severity", "Severity", FieldType.SELECT, true,
                listOf(RoomOptionDef("s1", "Sev one"), RoomOptionDef("s2", "Sev two")),
            ),
            RoomFieldDef("note", "Note", FieldType.TEXT, false, emptyList()),
        ),
    )

    private val operations = listOf(
        Operation("GET room", notFound, ok, ok, ok) { it.client.get("$rooms/${it.room}") },
        Operation("GET workflow", notFound, ok, ok, ok) { it.client.get("$rooms/${it.room}/workflow") },
        Operation("GET members", notFound, ok, ok, ok) { it.client.get("$rooms/${it.room}/members") },
        // Read state is a member's own: any member (the OWNER too), never an ADMIN outside the room (403, nothing to move).
        Operation("POST seen", notFound, noContent, noContent, forbidden) { it.client.post("$rooms/${it.room}/seen") },
        Operation("PUT room", notFound, forbidden, noContent, noContent) { it.client.putJson("$rooms/${it.room}", update()) },
        Operation("POST archive", notFound, forbidden, noContent, noContent) { it.client.post("$rooms/${it.room}/archive") },
        // The room is archived first, so the 204s are real unarchives, not no-ops.
        Operation("POST unarchive", notFound, forbidden, noContent, noContent) {
            TestRooms.archive(it.room)
            it.client.post("$rooms/${it.room}/unarchive")
        },
        // A fresh room is at workflow revision 1.
        Operation("PUT workflow", notFound, forbidden, noContent, noContent) {
            it.client.put("$rooms/${it.room}/workflow") {
                header(EXPECTED_REVISION_HEADER, "1")
                contentType(ContentType.Application.Json)
                setBody(workflowBody())
            }
        },
        Operation("DELETE room", notFound, forbidden, forbidden, noContent) { it.client.delete("$rooms/${it.room}") },
        // Members (C8): the manager rows. The add's target is the outsider (not in the fresh room); the role and removal
        // target a second plain member.
        Operation("POST members", notFound, forbidden, ok, ok) {
            it.client.postJson("$rooms/${it.room}/members", RoomMembersAddRequest(userIds = listOf(it.cast.outsider.id)))
        },
        Operation("PUT member role", notFound, forbidden, noContent, noContent) {
            it.client.putJson("$rooms/${it.room}/members/${it.cast.other.id}", RoomMemberRoleRequest(RoomRole.OWNER))
        },
        Operation("DELETE other member", notFound, forbidden, noContent, noContent) {
            it.client.delete("$rooms/${it.room}/members/${it.cast.other.id}")
        },
        // Leaving: a member may; the sole OWNER may not (last-owner 409); an ADMIN outside the room is not a member (404).
        Operation("DELETE own membership", notFound, noContent, conflict, notFound) {
            it.client.delete("$rooms/${it.room}/members/${it.caller.id}")
        },
    )

    private class Cast(val owner: RoomActor, val member: RoomActor, val outsider: RoomActor, val admin: RoomActor, val other: RoomActor)

    private suspend fun io.ktor.server.testing.ApplicationTestBuilder.cast(): Cast {
        suspend fun actor(prefix: String, role: UserRole = UserRole.USER) = seedActor("mx-$prefix", role).also { mintedUsers += it.id }
        return Cast(actor("owner"), actor("member"), actor("outsider"), actor("admin", UserRole.ADMIN), actor("other"))
    }

    private suspend fun freshRoom(cast: Cast): UInt {
        val template = templateId ?: TestTemplates.seed("mx").also { templateId = it }
        return TestRooms.seed(cast.owner.id, template, "Matrix ${UUID.randomUUID()}", accessType = RoomAccessType.PUBLIC).also {
            TestRooms.addMember(it, cast.member.id, RoomRole.MEMBER)
            TestRooms.addMember(it, cast.other.id, RoomRole.MEMBER)
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
                val response = operation.call(Call(actor.client, actor, cast, freshRoom(cast)))
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
                    assertEquals(notFound, operation.call(Call(actor.client, actor, cast, room)).status, "${operation.name} on $room")
                }
            }
        }
    }

    @Test
    fun `a malformed body is 404 for an outsider, 403 for a plain member and 400 only for someone who may write`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast()
        val room = freshRoom(cast)
        // The metadata PUT, the workflow PUT, the member add and the role PUT all decode a body: the same guard-before-body order on each.
        val bodyRoutes = listOf(
            Triple("PUT room", HttpMethod.Put, "$rooms/$room"),
            Triple("PUT workflow", HttpMethod.Put, "$rooms/$room/workflow"),
            Triple("POST members", HttpMethod.Post, "$rooms/$room/members"),
            Triple("PUT member role", HttpMethod.Put, "$rooms/$room/members/${cast.other.id}"),
        )
        for ((name, method, path) in bodyRoutes) {
            assertEquals(notFound, cast.outsider.client.malformed(method, path).status, "$name as outsider")
            assertEquals(forbidden, cast.member.client.malformed(method, path).status, "$name as member")
            assertEquals(HttpStatusCode.BadRequest, cast.owner.client.malformed(method, path).status, "$name as owner")
            assertEquals(HttpStatusCode.BadRequest, cast.admin.client.malformed(method, path).status, "$name as admin")
        }
    }

    private suspend fun HttpClient.malformed(method: HttpMethod, path: String): HttpResponse = request(path) {
        this.method = method
        contentType(ContentType.Application.Json)
        setBody("{ not json")
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

    @Test
    fun `an OWNER of one room is a plain outsider to another - 404 on every operation`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast()
        val other = freshRoom(cast) // owned by cast.owner
        val rival = actor("mx-rival")
        val rivalRoom = TestRooms.seed(rival.id, templateId!!, "Rival ${UUID.randomUUID()}").also { mintedRooms += it }
        assertEquals(ok, rival.client.get("$rooms/$rivalRoom").status, "the rival owns their own room")
        for (operation in operations) {
            val response = operation.call(Call(rival.client, rival, cast, other))
            assertEquals(notFound, response.status, "${operation.name} as an owner of another room")
        }
    }

    @Test
    fun `a non-member and a missing room get the identical problem body on every operation`() = testApplication {
        usePostgresTestcontainer()
        val cast = cast()
        val real = freshRoom(cast)
        for (operation in operations) {
            val hidden = operation.call(Call(cast.outsider.client, cast.outsider, cast, real))
            val missing = operation.call(Call(cast.outsider.client, cast.outsider, cast, 999_999u))
            assertEquals(notFound, hidden.status, operation.name)
            assertEquals(notFound, missing.status, operation.name)
            // Only `instance` (the request path, which carries the id) may differ.
            val expected = missing.body<ProblemDetail>().copy(instance = null)
            assertEquals(expected, hidden.body<ProblemDetail>().copy(instance = null), operation.name)
        }
    }

    private suspend fun io.ktor.server.testing.ApplicationTestBuilder.actor(prefix: String) =
        seedActor(prefix).also { mintedUsers += it.id }
}
