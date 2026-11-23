package ch.nokillswit

import ch.nokillswit.authz.RoomAccessType
import ch.nokillswit.authz.RoomRole
import ch.nokillswit.rooms.RoomAccessResponse
import ch.nokillswit.rooms.RoomJoinDecision
import ch.nokillswit.rooms.RoomJoinDecisionRequest
import ch.nokillswit.rooms.RoomJoinRequestPageResponse
import ch.nokillswit.rooms.RoomJoinRequestStatus
import ch.nokillswit.rooms.MAX_ROOM_MEMBERS
import ch.nokillswit.rooms.RoomPageResponse
import ch.nokillswit.rooms.RoomResponse
import ch.nokillswit.users.UserRole
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/** V36 room discovery, self-enrollment and owner-reviewed requests. */
class RoomEnrollmentTest {
    private val rooms = "/api/v1/rooms"
    private val mintedRooms = mutableListOf<UInt>()
    private val mintedUsers = mutableListOf<UInt>()
    private var templateId: UInt? = null

    @AfterTest
    fun cleanUp() = runBlocking<Unit> {
        TestRooms.purge(mintedRooms)
        TestRooms.softDeleteUsers(mintedUsers)
        templateId?.let { TestTemplates.delete(it) }
    }

    private fun marker() = UUID.randomUUID().toString().substring(0, 8)

    private suspend fun ApplicationTestBuilder.actor(prefix: String, role: UserRole = UserRole.USER): RoomActor =
        seedActor(prefix, role, name = "Name $prefix").also { mintedUsers += it.id }

    private suspend fun room(owner: RoomActor, accessType: RoomAccessType): UInt {
        val template = templateId ?: TestTemplates.seed("enroll").also { templateId = it }
        return TestRooms.seed(owner.id, template, "Enrollment ${accessType.name} ${marker()}", accessType = accessType)
            .also { mintedRooms += it }
    }

    private fun access(roomId: UInt) = "$rooms/$roomId/access"
    private fun join(roomId: UInt) = "$rooms/$roomId/join"
    private fun requests(roomId: UInt) = "$rooms/$roomId/join-requests"

    @Test
    fun `visible rooms are discoverable, hidden rooms stay private, and anyone may join a public room`() = testApplication {
        usePostgresTestcontainer()
        val owner = actor("enroll-discover-owner")
        val outsider = actor("enroll-discover-outsider")
        val admin = actor("enroll-discover-admin", UserRole.ADMIN)
        val publicRoom = room(owner, RoomAccessType.PUBLIC)
        val visibleRoom = room(owner, RoomAccessType.RESTRICTED_VISIBLE)
        val hiddenRoom = room(owner, RoomAccessType.RESTRICTED_HIDDEN)

        val directory = outsider.client.get("$rooms?pageSize=100").body<RoomPageResponse>()
        assertTrue(directory.items.any { it.id == publicRoom && it.accessType == RoomAccessType.PUBLIC })
        assertTrue(directory.items.any { it.id == visibleRoom && it.accessType == RoomAccessType.RESTRICTED_VISIBLE })
        assertTrue(directory.items.none { it.id == hiddenRoom })
        assertEquals(HttpStatusCode.OK, outsider.client.get(access(publicRoom)).status)
        assertEquals(HttpStatusCode.OK, outsider.client.get(access(visibleRoom)).status)
        assertEquals(HttpStatusCode.NotFound, outsider.client.get(access(hiddenRoom)).status)
        assertEquals(HttpStatusCode.NotFound, outsider.client.get("$rooms/$publicRoom").status, "discovery does not expose content")
        assertEquals(HttpStatusCode.OK, admin.client.get("$rooms/$publicRoom").status, "an admin may inspect a visible room")
        assertEquals(HttpStatusCode.NotFound, admin.client.get("$rooms/$hiddenRoom").status, "hidden means hidden from admins too")

        assertEquals(HttpStatusCode.NoContent, outsider.client.post(join(publicRoom)).status)
        assertEquals(HttpStatusCode.NoContent, outsider.client.post(join(publicRoom)).status, "joining is idempotent")
        val joined = outsider.client.get("$rooms/$publicRoom").body<RoomResponse>()
        assertEquals(RoomRole.MEMBER, joined.role)
        assertEquals(RoomAccessType.PUBLIC, joined.accessType)
        assertEquals(1, joined.ownerCount)
        assertEquals(HttpStatusCode.Forbidden, outsider.client.post(join(visibleRoom)).status)
        assertEquals(HttpStatusCode.Forbidden, outsider.client.post(requests(publicRoom)).status)
        assertEquals(HttpStatusCode.NotFound, outsider.client.post(requests(hiddenRoom)).status)
        assertEquals(HttpStatusCode.NotFound, outsider.client.post(join(hiddenRoom)).status)
        assertEquals(HttpStatusCode.NotFound, owner.client.get("$rooms/999999/join-requests").status)
    }

    @Test
    fun `owners reject, accept, and safely ignore stale join request decisions`() = testApplication {
        usePostgresTestcontainer()
        val owner = actor("enroll-review-owner")
        val coOwner = actor("enroll-review-coowner")
        val requester = actor("enroll-review-requester")
        val member = actor("enroll-review-member")
        val admin = actor("enroll-review-admin", UserRole.ADMIN)
        val room = room(owner, RoomAccessType.RESTRICTED_VISIBLE)
        TestRooms.addMember(room, coOwner.id, RoomRole.OWNER)
        TestRooms.addMember(room, member.id)

        assertEquals(HttpStatusCode.NoContent, requester.client.post(requests(room)).status)
        assertEquals(HttpStatusCode.NoContent, requester.client.post(requests(room)).status, "a pending request is idempotent")
        assertEquals(RoomJoinRequestStatus.PENDING, requester.client.get(access(room)).body<RoomAccessResponse>().requestStatus)
        assertEquals(HttpStatusCode.Forbidden, member.client.get(requests(room)).status)
        assertEquals(HttpStatusCode.Forbidden, admin.client.get(requests(room)).status, "application admins do not review room requests")

        val first = owner.client.get(requests(room)).body<RoomJoinRequestPageResponse>().items.single()
        assertEquals(requester.id, first.userId)
        assertEquals(
            HttpStatusCode.Forbidden,
            member.client.putJson("${requests(room)}/${first.id}", RoomJoinDecisionRequest(RoomJoinDecision.APPROVE)).status,
        )
        assertEquals(
            HttpStatusCode.NoContent,
            owner.client.putJson("${requests(room)}/${first.id}", RoomJoinDecisionRequest(RoomJoinDecision.REJECT)).status,
        )
        assertEquals(RoomJoinRequestStatus.REJECTED, requester.client.get(access(room)).body<RoomAccessResponse>().requestStatus)
        assertEquals(
            HttpStatusCode.NotFound,
            owner.client.putJson("${requests(room)}/${first.id}", RoomJoinDecisionRequest(RoomJoinDecision.APPROVE)).status,
            "a decided request cannot be replayed",
        )

        assertEquals(HttpStatusCode.NoContent, requester.client.post(requests(room)).status)
        val second = owner.client.get(requests(room)).body<RoomJoinRequestPageResponse>().items.single()
        assertNotEquals(first.id, second.id, "resubmission gets a fresh decision id")
        assertEquals(
            HttpStatusCode.NotFound,
            coOwner.client.putJson("${requests(room)}/${first.id}", RoomJoinDecisionRequest(RoomJoinDecision.APPROVE)).status,
            "the stale decision id stays invalid",
        )
        assertEquals(
            HttpStatusCode.NoContent,
            coOwner.client.putJson("${requests(room)}/${second.id}", RoomJoinDecisionRequest(RoomJoinDecision.APPROVE)).status,
        )
        val joined = requester.client.get("$rooms/$room").body<RoomResponse>()
        assertEquals(RoomRole.MEMBER, joined.role)
        assertEquals(2, joined.ownerCount)
        assertEquals(0L, owner.client.get(requests(room)).body<RoomJoinRequestPageResponse>().total)
    }

    @Test
    fun `a full room keeps an approved applicant pending`() = testApplication {
        usePostgresTestcontainer()
        val owner = actor("enroll-cap-owner")
        val requester = actor("enroll-cap-requester")
        val room = room(owner, RoomAccessType.RESTRICTED_VISIBLE)
        assertEquals(HttpStatusCode.NoContent, requester.client.post(requests(room)).status)
        val requestId = owner.client.get(requests(room)).body<RoomJoinRequestPageResponse>().items.single().id
        mintedUsers += TestRooms.seedMembers(room, MAX_ROOM_MEMBERS - 1, "enroll-cap-${marker()}")

        assertEquals(
            HttpStatusCode.Conflict,
            owner.client.putJson("${requests(room)}/$requestId", RoomJoinDecisionRequest(RoomJoinDecision.APPROVE)).status,
        )
        assertEquals(RoomJoinRequestStatus.PENDING, requester.client.get(access(room)).body<RoomAccessResponse>().requestStatus)
        assertEquals(1L, owner.client.get(requests(room)).body<RoomJoinRequestPageResponse>().total)
    }

    @Test
    fun `a full public room declines new members and allows repeat joins`() = testApplication {
        usePostgresTestcontainer()
        val owner = actor("enroll-full-public-owner")
        val newcomer = actor("enroll-full-public-newcomer")
        val room = room(owner, RoomAccessType.PUBLIC)
        mintedUsers += TestRooms.seedMembers(room, MAX_ROOM_MEMBERS - 1, "enroll-full-public-${marker()}")

        assertEquals(HttpStatusCode.Conflict, newcomer.client.post(join(room)).status)
        assertEquals(HttpStatusCode.NotFound, newcomer.client.get("$rooms/$room").status)
        assertEquals(HttpStatusCode.NoContent, owner.client.post(join(room)).status)
    }
}
