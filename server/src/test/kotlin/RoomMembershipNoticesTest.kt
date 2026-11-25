package ch.nokillswit

import ch.nokillswit.authz.RoomAccessType
import ch.nokillswit.notifications.NotificationPreferencesResponse
import ch.nokillswit.notifications.NotificationPreferencesUpdateRequest
import ch.nokillswit.notifications.RoomMembershipNotificationPreference
import ch.nokillswit.rooms.RoomJoinDecision
import ch.nokillswit.rooms.RoomJoinDecisionRequest
import ch.nokillswit.rooms.RoomJoinRequestPageResponse
import ch.nokillswit.rooms.RoomMembersAddRequest
import ch.nokillswit.users.UserRole
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RoomMembershipNoticesTest {
    private val users = mutableListOf<UInt>()
    private val rooms = mutableListOf<UInt>()
    private val templates = mutableListOf<UInt>()

    @AfterTest
    fun cleanUp() = runBlocking {
        TestRooms.purge(rooms)
        TestRooms.softDeleteUsers(users)
        templates.forEach { TestTemplates.delete(it) }
    }

    private suspend fun ApplicationTestBuilder.actor(prefix: String): RoomActor =
        seedActor(prefix, UserRole.USER).also { users += it.id }

    private suspend fun room(owner: RoomActor, access: RoomAccessType): UInt {
        val template = TestTemplates.seed("room-notice").also { templates += it }
        return TestRooms.seed(owner.id, template, "Membership notices ${uniqueEmail("room-notice")}", accessType = access)
            .also { rooms += it }
    }

    private suspend fun RoomActor.feed(query: String = "") = client.get("/api/v1/notifications/feed$query").let {
        assertEquals(HttpStatusCode.OK, it.status)
        it.body<JsonObject>().getValue("items").jsonArray.map { row -> row.jsonObject }
    }

    @Test
    fun `only the person added to a hidden room gets a room notice and removing them erases it`() = testApplication {
        usePostgresTestcontainer()
        val owner = actor("notice-owner")
        val member = actor("notice-member")
        val outsider = actor("notice-outsider")
        val room = room(owner, RoomAccessType.RESTRICTED_HIDDEN)
        val members = "/api/v1/rooms/$room/members"

        val request = RoomMembersAddRequest(userIds = listOf(member.id))
        assertEquals(HttpStatusCode.OK, owner.client.postJson(members, request).status)
        assertEquals(HttpStatusCode.OK, owner.client.postJson(members, request).status)
        assertTrue(owner.feed().isEmpty())
        assertTrue(outsider.feed().isEmpty())

        val row = member.feed("?seen=false").single()
        assertEquals("ROOM_ADDED", row.getValue("type").jsonPrimitive.content)
        assertEquals(room.toString(), row.getValue("roomId").jsonPrimitive.content)
        assertEquals(owner.id.toString(), row.getValue("actorId").jsonPrimitive.content)
        assertEquals("null", row.getValue("threadNumber").toString())
        assertEquals("null", row.getValue("threadTitle").toString())
        assertEquals("null", row.getValue("messageId").toString())
        assertTrue(member.client.get("/api/v1/notifications").body<JsonObject>().getValue("items").jsonArray.isEmpty())

        val noticeId = row.getValue("id").jsonPrimitive.content
        val seen = "/api/v1/notifications/$noticeId/seen"
        assertEquals(HttpStatusCode.NotFound, outsider.client.post(seen).status)
        assertEquals(HttpStatusCode.NoContent, member.client.post(seen).status)
        assertTrue(member.feed("?seen=false").isEmpty())
        assertEquals(HttpStatusCode.NoContent, owner.client.delete("$members/${member.id}").status)
        assertTrue(member.feed().isEmpty())
        assertEquals(HttpStatusCode.NotFound, member.client.post(seen).status)
    }

    @Test
    fun `owner approval delivers a room notice while a public self join does not`() = testApplication {
        usePostgresTestcontainer()
        val owner = actor("notice-approver")
        val requester = actor("notice-requester")
        val room = room(owner, RoomAccessType.RESTRICTED_VISIBLE)
        val publicRoom = room(owner, RoomAccessType.PUBLIC)
        val requests = "/api/v1/rooms/$room/join-requests"

        assertEquals(HttpStatusCode.NoContent, requester.client.post("/api/v1/rooms/$publicRoom/join").status)
        assertEquals(HttpStatusCode.NoContent, requester.client.post(requests).status)
        assertTrue(requester.feed().isEmpty())
        val id = owner.client.get(requests).body<RoomJoinRequestPageResponse>().items.single().id
        val approval = RoomJoinDecisionRequest(RoomJoinDecision.APPROVE)
        assertEquals(HttpStatusCode.NoContent, owner.client.putJson("$requests/$id", approval).status)
        assertEquals(room.toString(), requester.feed("?type=ROOM_ADDED").single().getValue("roomId").jsonPrimitive.content)
    }

    @Test
    fun `opting out keeps the bell quiet and a legacy preference update restores the defaults`() = testApplication {
        usePostgresTestcontainer()
        val owner = actor("notice-pref-owner")
        val member = actor("notice-pref-member")
        val room = room(owner, RoomAccessType.RESTRICTED_HIDDEN)
        val endpoint = "/api/v1/users/${member.id}/notification-preferences"
        val off = RoomMembershipNotificationPreference(inApp = false, email = false)

        val saved = member.client.putJson(endpoint, NotificationPreferencesUpdateRequest(emptyList(), roomMembership = off))
        assertEquals(HttpStatusCode.NoContent, saved.status)
        assertEquals(off, member.client.get(endpoint).body<NotificationPreferencesResponse>().roomMembership)
        val added = owner.client.postJson("/api/v1/rooms/$room/members", RoomMembersAddRequest(userIds = listOf(member.id)))
        assertEquals(HttpStatusCode.OK, added.status)
        assertTrue(member.feed().isEmpty())

        assertEquals(HttpStatusCode.NoContent, member.client.putJson(endpoint, NotificationPreferencesUpdateRequest(emptyList())).status)
        assertEquals(
            RoomMembershipNotificationPreference(inApp = true, email = false),
            member.client.get(endpoint).body<NotificationPreferencesResponse>().roomMembership,
        )
    }
}
