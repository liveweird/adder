package ch.nokillswit

import ch.nokillswit.authz.RoomAccessType
import ch.nokillswit.rooms.RoomOrganizationGroup
import ch.nokillswit.rooms.RoomOrganizationPlacement
import ch.nokillswit.rooms.RoomOrganizationResponse
import ch.nokillswit.rooms.RoomOrganizationUpdateRequest
import ch.nokillswit.rooms.RoomPageResponse
import ch.nokillswit.users.UserRole
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
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class RoomOrganizationTest {
    private val endpoint = "/api/v1/room-organization"
    private val mintedRooms = mutableListOf<UInt>()
    private val mintedUsers = mutableListOf<UInt>()
    private var templateId: UInt? = null

    @AfterTest
    fun cleanUp() = runBlocking<Unit> {
        purgeOrganizations(mintedUsers)
        TestRooms.purge(mintedRooms)
        TestRooms.softDeleteUsers(mintedUsers)
        templateId?.let { TestTemplates.delete(it) }
    }

    private suspend fun ApplicationTestBuilder.actor(prefix: String, role: UserRole = UserRole.USER): RoomActor =
        seedActor(prefix, role).also { mintedUsers += it.id }

    private suspend fun room(owner: RoomActor, name: String, accessType: RoomAccessType = RoomAccessType.RESTRICTED_HIDDEN): UInt {
        val template = templateId ?: TestTemplates.seed("organization").also { templateId = it }
        return TestRooms.seed(owner.id, template, "$name ${UUID.randomUUID().toString().take(8)}", accessType = accessType)
            .also { mintedRooms += it }
    }

    private suspend fun RoomActor.replace(revision: Long, request: RoomOrganizationUpdateRequest): HttpResponse = client.put(endpoint) {
        header("X-Expected-Revision", revision)
        contentType(ContentType.Application.Json)
        setBody(request)
    }

    @Test
    fun `organization is private to its caller, including from admins and for hidden rooms`() = testApplication {
        usePostgresTestcontainer()
        val owner = actor("organization-private-owner")
        val outsider = actor("organization-private-outsider")
        val admin = actor("organization-private-admin", UserRole.ADMIN)
        val hidden = room(owner, "Hidden")
        val group = RoomOrganizationGroup(UUID.randomUUID().toString(), "Focus")

        val saved = owner.replace(1, RoomOrganizationUpdateRequest(listOf(group), listOf(RoomOrganizationPlacement(hidden, group.id))))
        assertEquals(HttpStatusCode.OK, saved.status)
        assertEquals(2, saved.body<RoomOrganizationResponse>().revision)
        assertEquals(listOf(hidden), owner.client.get(endpoint).body<RoomOrganizationResponse>().rooms.map { it.roomId })
        assertEquals(RoomOrganizationResponse(1, emptyList(), emptyList()), outsider.client.get(endpoint).body())
        assertEquals(RoomOrganizationResponse(1, emptyList(), emptyList()), admin.client.get(endpoint).body())
        assertEquals(
            HttpStatusCode.BadRequest,
            admin.replace(1, RoomOrganizationUpdateRequest(emptyList(), listOf(RoomOrganizationPlacement(hidden, null)))).status,
            "application admins cannot organize someone else's hidden room",
        )
    }

    @Test
    fun `replace normalizes names, is idempotent, and rejects stale revisions`() = testApplication {
        usePostgresTestcontainer()
        val owner = actor("organization-revision-owner")
        val joined = room(owner, "Archived")
        TestRooms.archive(joined)
        val group = RoomOrganizationGroup(UUID.randomUUID().toString(), "  Focus  ")
        val request = RoomOrganizationUpdateRequest(listOf(group), listOf(RoomOrganizationPlacement(joined, group.id)))

        val first = owner.replace(1, request)
        assertEquals(HttpStatusCode.OK, first.status)
        val normalized = first.body<RoomOrganizationResponse>()
        assertEquals(2, normalized.revision)
        assertEquals("Focus", normalized.groups.single().name)

        val noOp = owner.replace(2, request)
        assertEquals(HttpStatusCode.OK, noOp.status)
        assertEquals(2, noOp.body<RoomOrganizationResponse>().revision)
        assertEquals(HttpStatusCode.Conflict, owner.replace(1, request).status)
    }

    @Test
    fun `validation rejects ambiguous groups and rooms outside the caller membership`() = testApplication {
        usePostgresTestcontainer()
        val owner = actor("organization-validation-owner")
        val other = actor("organization-validation-other")
        val foreignRoom = room(other, "Foreign", RoomAccessType.PUBLIC)
        val firstId = UUID.randomUUID().toString()
        val secondId = UUID.randomUUID().toString()

        assertEquals(
            HttpStatusCode.BadRequest,
            owner.replace(
                1,
                RoomOrganizationUpdateRequest(
                    listOf(RoomOrganizationGroup(firstId, "Focus"), RoomOrganizationGroup(secondId, "focus")),
                    emptyList(),
                ),
            ).status,
        )
        assertEquals(
            HttpStatusCode.BadRequest,
            owner.replace(1, RoomOrganizationUpdateRequest(emptyList(), listOf(RoomOrganizationPlacement(foreignRoom, null)))).status,
        )
        assertEquals(
            HttpStatusCode.BadRequest,
            owner.replace(
                1,
                RoomOrganizationUpdateRequest(
                    listOf(RoomOrganizationGroup("00000000-0000-4000-A000-000000000000", "Upper")),
                    emptyList(),
                ),
            ).status,
        )
    }

    @Test
    fun `read omits placements whose room or membership has disappeared`() = testApplication {
        usePostgresTestcontainer()
        val owner = actor("organization-stale-owner")
        val membershipGone = room(owner, "Membership gone")
        val roomGone = room(owner, "Room gone")
        val group = RoomOrganizationGroup(UUID.randomUUID().toString(), "Later")
        val request = RoomOrganizationUpdateRequest(
            listOf(group),
            listOf(RoomOrganizationPlacement(membershipGone, group.id), RoomOrganizationPlacement(roomGone, null)),
        )
        assertEquals(HttpStatusCode.OK, owner.replace(1, request).status)

        removeMembership(membershipGone, owner.id)
        TestRooms.purge(listOf(roomGone))
        val read = owner.client.get(endpoint).body<RoomOrganizationResponse>()
        assertEquals(listOf(group), read.groups)
        assertTrue(read.rooms.isEmpty())
    }

    @Test
    fun `personal order groups rooms before pagination and leaves unranked rooms newest first`() = testApplication {
        usePostgresTestcontainer()
        val owner = actor("organization-sort-owner")
        val inSecondGroup = room(owner, "Second group")
        val inFirstGroup = room(owner, "First group")
        val explicitUngrouped = room(owner, "Ungrouped")
        val unrankedOlder = room(owner, "Unranked older")
        val unrankedNewer = room(owner, "Unranked newer")
        setLastActivity(unrankedOlder, 10)
        setLastActivity(unrankedNewer, 20)
        val first = RoomOrganizationGroup(UUID.randomUUID().toString(), "First")
        val second = RoomOrganizationGroup(UUID.randomUUID().toString(), "Second")
        val request = RoomOrganizationUpdateRequest(
            listOf(first, second),
            listOf(
                RoomOrganizationPlacement(inSecondGroup, second.id),
                RoomOrganizationPlacement(inFirstGroup, first.id),
                RoomOrganizationPlacement(explicitUngrouped, null),
            ),
        )
        assertEquals(HttpStatusCode.OK, owner.replace(1, request).status)

        val firstPage = owner.client.get("/api/v1/rooms?member=true&sort=personalOrder&pageSize=2&page=1").body<RoomPageResponse>()
        val secondPage = owner.client.get("/api/v1/rooms?member=true&sort=personalOrder&pageSize=2&page=2").body<RoomPageResponse>()
        val thirdPage = owner.client.get("/api/v1/rooms?member=true&sort=personalOrder&pageSize=2&page=3").body<RoomPageResponse>()
        assertEquals(listOf(inFirstGroup, inSecondGroup), firstPage.items.map { it.id })
        assertEquals(listOf(explicitUngrouped, unrankedNewer), secondPage.items.map { it.id })
        assertEquals(listOf(unrankedOlder), thirdPage.items.map { it.id })
        val reversed = owner.client.get("/api/v1/rooms?member=true&sort=-personalOrder&pageSize=5").body<RoomPageResponse>()
        assertEquals(
            listOf(explicitUngrouped, inSecondGroup, inFirstGroup, unrankedNewer, unrankedOlder),
            reversed.items.map { it.id },
        )
        assertEquals(
            HttpStatusCode.BadRequest,
            owner.client.get("/api/v1/rooms?sort=personalOrder").status,
            "personal order is caller-membership-only",
        )
    }

    private fun purgeOrganizations(userIds: Collection<UInt>) {
        if (userIds.isEmpty()) return
        PostgresTestSupport.connect().use { connection ->
            connection.prepareStatement("DELETE FROM user_room_organizations WHERE user_id = ANY (?)").use { statement ->
                statement.setArray(1, connection.createArrayOf("bigint", userIds.map { it.toLong() }.toTypedArray()))
                statement.executeUpdate()
            }
        }
    }

    private fun removeMembership(roomId: UInt, userId: UInt) {
        PostgresTestSupport.connect().use { connection ->
            connection.prepareStatement("DELETE FROM room_members WHERE room_id = ? AND user_id = ?").use { statement ->
                statement.setLong(1, roomId.toLong())
                statement.setLong(2, userId.toLong())
                statement.executeUpdate()
            }
        }
    }

    private fun setLastActivity(roomId: UInt, at: Long) {
        PostgresTestSupport.connect().use { connection ->
            connection.prepareStatement("UPDATE rooms SET last_activity_at = ? WHERE id = ?").use { statement ->
                statement.setLong(1, at)
                statement.setLong(2, roomId.toLong())
                statement.executeUpdate()
            }
        }
    }
}
