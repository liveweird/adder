package ch.nokillswit

import ch.nokillswit.authz.RoomAccessType
import ch.nokillswit.authz.RoomRole
import ch.nokillswit.infra.MUTATION_ID_HEADER
import ch.nokillswit.infra.notify.LiveHint
import ch.nokillswit.infra.notify.LiveKind
import ch.nokillswit.plugins.ProblemDetail
import ch.nokillswit.rooms.LAST_OWNER_PROBLEM_TYPE
import ch.nokillswit.rooms.MAX_ROOM_MEMBERS
import ch.nokillswit.rooms.MAX_USER_IDS_PER_ADD
import ch.nokillswit.rooms.RoomMemberPageResponse
import ch.nokillswit.rooms.RoomMemberRoleRequest
import ch.nokillswit.rooms.RoomMembersAddRequest
import ch.nokillswit.rooms.RoomMembersAddResponse
import ch.nokillswit.rooms.RoomResponse
import ch.nokillswit.users.UserRole
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.post
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
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking

/**
 * Room membership (V13) over HTTP: add a person, a whole team (a snapshot) or both, idempotently and always as MEMBER; the 400s for unknown
 * or deleted ids; the 500-member cap; role changes, leaving and removal; the last-OWNER rule (`409`, `urn:adder:problem:last-owner`) for a
 * leave, a demotion and an ADMIN's removal; membership management on an archived room; the audit trail; the paged list; and the lock-wait
 * race where the other owner is demoted while a leave waits. The role matrix per caller is the write rows of `RoomAuthzMatrixTest`.
 * Rooms, teams and users are SHARED suite state: every test mints UNIQUE rows and removes them.
 */
class RoomMembersTest {
    private val rooms = "/api/v1/rooms"
    private val mintedRooms = mutableListOf<UInt>()
    private val mintedUsers = mutableListOf<UInt>()
    private val mintedTeams = mutableListOf<UInt>()
    private var templateId: UInt? = null

    @AfterTest
    fun cleanUp() = runBlocking<Unit> {
        TestRooms.purge(mintedRooms)
        TestRooms.softDeleteUsers(mintedUsers)
        mintedTeams.forEach { TestTeams.service.delete(it) }
        templateId?.let { TestTemplates.delete(it) }
    }

    private fun marker() = UUID.randomUUID().toString().substring(0, 8)

    private fun members(room: UInt) = "$rooms/$room/members"

    private fun member(room: UInt, userId: UInt) = "$rooms/$room/members/$userId"

    private suspend fun ApplicationTestBuilder.actor(prefix: String, role: UserRole = UserRole.USER, name: String = "Test"): RoomActor =
        seedActor(prefix, role, name).also { mintedUsers += it.id }

    /** A room owned by [owner], seeded through the service (the real create path). */
    private suspend fun room(owner: RoomActor, accessType: RoomAccessType = RoomAccessType.PUBLIC): UInt {
        val template = templateId ?: TestTemplates.seed("mem").also { templateId = it }
        return TestRooms.seed(owner.id, template, "Members ${marker()}", accessType = accessType).also { mintedRooms += it }
    }

    private suspend fun team(vararg users: RoomActor): UInt {
        val id = TestTeams.seed("roster-${marker()}")
        mintedTeams += id
        users.forEach { TestTeams.service.addMember(id, it.id) { } }
        return id
    }

    private suspend fun HttpClient.add(room: UInt, userIds: List<UInt>? = null, teamId: UInt? = null): HttpResponse =
        postJson(members(room), RoomMembersAddRequest(userIds, teamId))

    private suspend fun HttpClient.addCorrelated(
        room: UInt,
        request: RoomMembersAddRequest,
        mutationId: String,
        repeatMutationId: Boolean = false,
    ): HttpResponse = post(members(room)) {
        contentType(ContentType.Application.Json)
        header(MUTATION_ID_HEADER, mutationId)
        if (repeatMutationId) headers.append(MUTATION_ID_HEADER, mutationId)
        setBody(request)
    }

    private suspend fun HttpClient.removeCorrelated(
        room: UInt,
        userId: UInt,
        mutationId: String,
        repeatMutationId: Boolean = false,
    ): HttpResponse = delete(member(room, userId)) {
        header(MUTATION_ID_HEADER, mutationId)
        if (repeatMutationId) headers.append(MUTATION_ID_HEADER, mutationId)
    }

    private suspend fun HttpClient.page(room: UInt, query: String = ""): RoomMemberPageResponse {
        val response = get(members(room) + query)
        assertEquals(HttpStatusCode.OK, response.status, "list $query")
        return response.body()
    }

    private suspend fun HttpClient.roles(room: UInt): Map<UInt, RoomRole> =
        page(room, "?pageSize=100").items.associate { it.userId to it.role }

    private suspend fun HttpClient.setRole(room: UInt, userId: UInt, role: RoomRole): HttpResponse =
        putJson(member(room, userId), RoomMemberRoleRequest(role))

    private suspend fun HttpResponse.problemType(): String = body<ProblemDetail>().type

    @Test
    fun `an owner adds a person as MEMBER, the add is idempotent and one audit line names the person`() = testApplication {
        usePostgresTestcontainer()
        val owner = actor("mem-add-owner")
        val ann = actor("mem-add-ann", name = "Ann Added")
        val room = room(owner)

        withAuditCapture { capture ->
            val first = owner.client.add(room, listOf(ann.id))
            assertEquals(HttpStatusCode.OK, first.status)
            assertEquals(RoomMembersAddResponse(added = 1, alreadyMembers = 0), first.body())
            assertEquals(mapOf(owner.id to RoomRole.OWNER, ann.id to RoomRole.MEMBER), owner.client.roles(room))
            assertEquals(2, owner.client.get("$rooms/$room").body<RoomResponse>().memberCount)

            val again = owner.client.add(room, listOf(ann.id, ann.id, owner.id))
            assertEquals(
                RoomMembersAddResponse(added = 0, alreadyMembers = 2),
                again.body(),
                "duplicates collapse; existing people are counted",
            )
            assertEquals(RoomRole.OWNER, owner.client.roles(room)[owner.id], "an add never demotes an existing OWNER")

            val line = capture.awaitEvent { it.message == "room.member_added" && it.hasKeyValue("roomId", room.toLong()) }
            assertNotNull(line)
            assertTrue(line.hasKeyValue("byUserId", owner.id.toLong()))
            assertTrue(line.hasKeyValue("targetUserId", ann.id.toLong()))
            assertTrue(line.keyValuePairs.none { it.key == "viaTeamId" && it.value != null }, "added directly, not through a team")
            assertEquals(1, capture.events.count { it.message == "room.member_added" && it.hasKeyValue("roomId", room.toLong()) })
        }
    }

    @Test
    fun `member addition validates correlation after manager access and emits it only for an actual addition`() = testApplication {
        usePostgresTestcontainer()
        val owner = actor("mem-add-echo-owner")
        val member = actor("mem-add-echo-member")
        val outsider = actor("mem-add-echo-outsider")
        val admin = actor("mem-add-echo-admin", UserRole.ADMIN)
        val ann = actor("mem-add-echo-ann")
        val publicRoom = room(owner)
        val hiddenRoom = room(owner, RoomAccessType.RESTRICTED_HIDDEN)
        TestRooms.addMember(publicRoom, member.id)
        val request = RoomMembersAddRequest(userIds = listOf(ann.id))
        val mutationId = "2c6c7f12-79a4-4f1a-8dc7-e9686608b20a"

        assertEquals(HttpStatusCode.Forbidden, member.client.addCorrelated(publicRoom, request, mutationId.uppercase()).status)
        assertEquals(HttpStatusCode.NotFound, outsider.client.addCorrelated(publicRoom, request, mutationId.uppercase()).status)
        assertEquals(HttpStatusCode.NotFound, admin.client.addCorrelated(hiddenRoom, request, mutationId.uppercase()).status)
        assertEquals(HttpStatusCode.BadRequest, owner.client.addCorrelated(publicRoom, request, mutationId.uppercase()).status)
        assertEquals(
            HttpStatusCode.BadRequest,
            owner.client.addCorrelated(publicRoom, request, mutationId, repeatMutationId = true).status,
        )

        NotifyCapture().use { capture ->
            val hints = capture.during {
                assertEquals(HttpStatusCode.OK, owner.client.addCorrelated(publicRoom, request, mutationId).status)
                val noOp = owner.client.addCorrelated(publicRoom, request, mutationId)
                assertEquals(RoomMembersAddResponse(added = 0, alreadyMembers = 1), noOp.body())
            }
            assertEquals(
                listOf(
                    LiveHint(
                        LiveKind.ROOM_MEMBER_ADDED,
                        publicRoom,
                        targetUserIds = listOf(ann.id),
                        mutationId = mutationId,
                        originUserId = owner.id,
                    ),
                    LiveHint(LiveKind.NOTIFICATION_CREATED, publicRoom, targetUserIds = listOf(ann.id)),
                ),
                hints,
            )
        }
    }

    @Test
    fun `a team add snapshots the active roster - a deleted member is skipped and a later roster change does not follow`() =
        testApplication {
            usePostgresTestcontainer()
            val owner = actor("mem-team-owner")
            val bea = actor("mem-team-bea", name = "Bea")
            val cy = actor("mem-team-cy", name = "Cy")
            val gone = actor("mem-team-gone", name = "Gone")
            val late = actor("mem-team-late", name = "Late")
            val teamId = team(bea, cy, gone)
            TestUsers.softDelete(gone.id)
            val room = room(owner)

            withAuditCapture { capture ->
                val response = owner.client.add(room, teamId = teamId)
                assertEquals(RoomMembersAddResponse(added = 2, alreadyMembers = 0), response.body())
                assertEquals(setOf(owner.id, bea.id, cy.id), owner.client.roles(room).keys)
                assertNull(TestRooms.rawRole(room, gone.id), "the deleted roster member got no row")
                assertTrue(
                    capture.events.filter { it.message == "room.member_added" && it.hasKeyValue("roomId", room.toLong()) }
                        .all { it.hasKeyValue("viaTeamId", teamId.toLong()) },
                    "every person added through the team names it",
                )
                assertEquals(2, capture.events.count { it.message == "room.member_added" && it.hasKeyValue("roomId", room.toLong()) })
            }

            // The snapshot never follows: a roster edit afterwards changes nothing in the room.
            TestTeams.service.addMember(teamId, late.id) { }
            TestTeams.service.removeMember(teamId, bea.id) { }
            assertEquals(setOf(owner.id, bea.id, cy.id), owner.client.roles(room).keys)
            assertEquals(
                RoomMembersAddResponse(added = 1, alreadyMembers = 1),
                owner.client.add(room, teamId = teamId).body(),
                "a fresh add sees the new roster",
            )
        }

    @Test
    fun `people and a team together are one set - a person named and on the roster counts once`() = testApplication {
        usePostgresTestcontainer()
        val owner = actor("mem-both-owner")
        val ann = actor("mem-both-ann")
        val bea = actor("mem-both-bea")
        val cy = actor("mem-both-cy")
        val teamId = team(bea, cy)
        val room = room(owner)
        TestRooms.addMember(room, cy.id)

        withAuditCapture { capture ->
            val response = owner.client.add(room, listOf(ann.id, bea.id), teamId)
            assertEquals(RoomMembersAddResponse(added = 2, alreadyMembers = 1), response.body())
            assertEquals(setOf(owner.id, ann.id, bea.id, cy.id), owner.client.roles(room).keys)
            fun via(user: RoomActor) = capture.events.single {
                it.message == "room.member_added" && it.hasKeyValue("roomId", room.toLong()) &&
                    it.hasKeyValue("targetUserId", user.id.toLong())
            }.keyValuePairs.firstOrNull { it.key == "viaTeamId" }?.value
            assertNull(via(ann), "named directly")
            assertNull(via(bea), "named AND on the roster: counted as named")
        }
    }

    @Test
    fun `an unknown or deleted user or team, an empty body and too many ids are 400 and nothing is added`() = testApplication {
        usePostgresTestcontainer()
        val owner = actor("mem-bad-owner")
        val ann = actor("mem-bad-ann")
        val gone = actor("mem-bad-gone")
        TestUsers.softDelete(gone.id)
        val deletedTeam = team().also { TestTeams.service.delete(it) }
        val room = room(owner)

        suspend fun assertBad(response: HttpResponse, why: String) = assertEquals(HttpStatusCode.BadRequest, response.status, why)
        assertBad(owner.client.add(room, listOf(ann.id, 999_999u)), "an unknown id beats the valid one")
        assertBad(owner.client.add(room, listOf(ann.id, gone.id)), "a soft-deleted user")
        assertBad(owner.client.add(room, teamId = 999_999u), "an unknown team")
        assertBad(owner.client.add(room, listOf(ann.id), teamId = deletedTeam), "a deleted team")
        assertBad(owner.client.add(room), "neither userIds nor teamId")
        assertBad(owner.client.add(room, emptyList()), "an empty userIds")
        assertBad(owner.client.add(room, (1..MAX_USER_IDS_PER_ADD + 1).map { it.toUInt() }), "more than $MAX_USER_IDS_PER_ADD ids")
        assertEquals(setOf(owner.id), owner.client.roles(room).keys, "nothing was added")
        assertEquals(
            HttpStatusCode.OK,
            owner.client.add(room, (1..MAX_USER_IDS_PER_ADD).map { ann.id }).status,
            "200 ids (here duplicates) is within bounds",
        )
    }

    @Test
    fun `a room holds 500 active members - the add past it is 409 and atomic, an existing member beats the cap`() =
        testApplication {
            usePostgresTestcontainer()
            val owner = actor("mem-cap-owner")
            val room = room(owner)
            val seeded = TestRooms.seedMembers(room, MAX_ROOM_MEMBERS - 1, "mem-cap-${marker()}")
            mintedUsers += seeded
            val one = actor("mem-cap-one")
            val two = actor("mem-cap-two")
            assertEquals(MAX_ROOM_MEMBERS.toLong(), owner.client.page(room).total)

            val refused = owner.client.add(room, listOf(one.id))
            assertEquals(HttpStatusCode.Conflict, refused.status)
            assertEquals("about:blank", refused.problemType(), "the cap is a plain conflict")
            assertEquals(
                HttpStatusCode.OK,
                owner.client.add(room, listOf(seeded.first())).status,
                "already a member: idempotent beats the cap",
            )
            assertEquals(MAX_ROOM_MEMBERS.toLong(), owner.client.page(room).total)

            TestUsers.softDelete(seeded.last())
            assertEquals(MAX_ROOM_MEMBERS - 1L, owner.client.page(room).total, "an inert row does not count")
            assertEquals(HttpStatusCode.Conflict, owner.client.add(room, listOf(one.id, two.id)).status, "two newcomers, one place")
            assertNull(TestRooms.rawRole(room, one.id), "the refused add wrote nothing, not even the first person")
            assertEquals(RoomMembersAddResponse(1, 0), owner.client.add(room, listOf(one.id)).body())
        }

    @Test
    fun `a role change is idempotent, audited only on a change, and a non-member or an inert member is 404`() = testApplication {
        usePostgresTestcontainer()
        val owner = actor("mem-role-owner")
        val ann = actor("mem-role-ann")
        val gone = actor("mem-role-gone")
        val stranger = actor("mem-role-stranger")
        val room = room(owner)
        TestRooms.addMember(room, ann.id)
        TestRooms.addMember(room, gone.id)
        TestUsers.softDelete(gone.id)

        withAuditCapture { capture ->
            fun changes() = capture.events.filter { it.message == "room.member_role_changed" && it.hasKeyValue("roomId", room.toLong()) }
            assertEquals(HttpStatusCode.NoContent, owner.client.setRole(room, ann.id, RoomRole.MEMBER).status, "the role she has")
            assertEquals(0, changes().size, "no change, no audit line")

            assertEquals(HttpStatusCode.NoContent, owner.client.setRole(room, ann.id, RoomRole.OWNER).status)
            assertEquals(RoomRole.OWNER, TestRooms.rawRole(room, ann.id))
            val promoted = changes().single()
            assertTrue(promoted.hasKeyValue("byUserId", owner.id.toLong()))
            assertTrue(promoted.hasKeyValue("targetUserId", ann.id.toLong()))
            assertTrue(promoted.hasKeyValue("roleFrom", "MEMBER"))
            assertTrue(promoted.hasKeyValue("roleTo", "OWNER"))

            assertEquals(HttpStatusCode.NoContent, owner.client.setRole(room, ann.id, RoomRole.OWNER).status)
            assertEquals(1, changes().size, "a repeat audits nothing")
            assertEquals(
                HttpStatusCode.NoContent,
                owner.client.setRole(room, ann.id, RoomRole.MEMBER).status,
                "another owner remains, so a demotion is fine",
            )
            assertEquals(2, changes().size)
        }

        assertEquals(HttpStatusCode.NotFound, owner.client.setRole(room, stranger.id, RoomRole.OWNER).status, "not a member")
        assertEquals(HttpStatusCode.NotFound, owner.client.setRole(room, 999_999u, RoomRole.OWNER).status, "no such user")
        assertEquals(
            HttpStatusCode.NotFound,
            owner.client.setRole(room, gone.id, RoomRole.OWNER).status,
            "an inert membership is not a member",
        )
        val badRole = owner.client.put(member(room, ann.id)) {
            contentType(ContentType.Application.Json)
            setBody("""{"role":"BOSS"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, badRole.status)
        assertEquals(RoomRole.MEMBER, TestRooms.rawRole(room, ann.id))
    }

    @Test
    fun `a member leaves, an owner or an administrator removes another, a plain member cannot, and a removed person can be added again`() =
        testApplication {
            usePostgresTestcontainer()
            val owner = actor("mem-rm-owner")
            val ann = actor("mem-rm-ann")
            val bea = actor("mem-rm-bea")
            val cy = actor("mem-rm-cy")
            val admin = actor("mem-rm-admin", UserRole.ADMIN)
            val room = room(owner)
            listOf(ann, bea, cy).forEach { TestRooms.addMember(room, it.id) }

            withAuditCapture { capture ->
                fun removed(target: RoomActor) = capture.events.singleOrNull {
                    it.message == "room.member_removed" && it.hasKeyValue("roomId", room.toLong()) &&
                        it.hasKeyValue("targetUserId", target.id.toLong())
                }
                assertEquals(
                    HttpStatusCode.Forbidden,
                    ann.client.delete(member(room, bea.id)).status,
                    "a plain member removes only themselves",
                )
                assertEquals(HttpStatusCode.NoContent, ann.client.delete(member(room, ann.id)).status, "a leave")
                assertNull(TestRooms.rawRole(room, ann.id))
                assertEquals(HttpStatusCode.NotFound, ann.client.get("$rooms/$room").status, "no longer a member: the uniform 404")
                val left = removed(ann)
                assertNotNull(left)
                assertTrue(left.hasKeyValue("self", true))
                assertTrue(left.hasKeyValue("byUserId", ann.id.toLong()))

                assertEquals(HttpStatusCode.NoContent, owner.client.delete(member(room, bea.id)).status)
                assertTrue(assertNotNull(removed(bea)).hasKeyValue("self", false))
                assertEquals(HttpStatusCode.NoContent, admin.client.delete(member(room, cy.id)).status, "an administrator outside the room")
                assertTrue(assertNotNull(removed(cy)).hasKeyValue("byUserId", admin.id.toLong()))

                assertEquals(HttpStatusCode.NotFound, owner.client.delete(member(room, bea.id)).status, "already gone")
                assertEquals(HttpStatusCode.NotFound, owner.client.delete(member(room, 999_999u)).status)
                assertEquals(
                    1,
                    capture.events.count { it.message == "room.member_removed" && it.hasKeyValue("targetUserId", bea.id.toLong()) },
                )
            }

            assertEquals(setOf(owner.id), owner.client.roles(room).keys)
            assertEquals(
                RoomMembersAddResponse(1, 0),
                owner.client.add(room, listOf(ann.id)).body(),
                "a hard delete: a re-add never collides",
            )
        }

    @Test
    fun `member removal validates correlation after authoritative access and never correlates a self leave`() = testApplication {
        usePostgresTestcontainer()
        val owner = actor("mem-rm-echo-owner")
        val member = actor("mem-rm-echo-member")
        val ann = actor("mem-rm-echo-ann")
        val admin = actor("mem-rm-echo-admin", UserRole.ADMIN)
        val room = room(owner)
        val hiddenRoom = room(owner, RoomAccessType.RESTRICTED_HIDDEN)
        TestRooms.addMember(room, member.id)
        TestRooms.addMember(room, ann.id)
        val mutationId = "2c6c7f12-79a4-4f1a-8dc7-e9686608b20a"

        assertEquals(HttpStatusCode.Forbidden, member.client.removeCorrelated(room, ann.id, mutationId.uppercase()).status)
        assertEquals(HttpStatusCode.NotFound, admin.client.removeCorrelated(hiddenRoom, admin.id, mutationId.uppercase()).status)
        assertEquals(
            HttpStatusCode.NotFound,
            admin.client.removeCorrelated(room, admin.id, mutationId.uppercase()).status,
            "a visible-room administrator still cannot leave a room they have not joined",
        )
        assertEquals(HttpStatusCode.BadRequest, owner.client.removeCorrelated(room, ann.id, mutationId.uppercase()).status)
        assertEquals(
            HttpStatusCode.BadRequest,
            owner.client.removeCorrelated(room, ann.id, mutationId, repeatMutationId = true).status,
        )

        NotifyCapture().use { capture ->
            val hints = capture.during {
                assertEquals(HttpStatusCode.NoContent, owner.client.removeCorrelated(room, ann.id, mutationId).status)
                assertEquals(HttpStatusCode.NoContent, member.client.removeCorrelated(room, member.id, mutationId).status)
            }
            assertEquals(
                listOf(
                    LiveHint(
                        LiveKind.ROOM_MEMBER_REMOVED,
                        room,
                        targetUserIds = listOf(ann.id),
                        mutationId = mutationId,
                        originUserId = owner.id,
                    ),
                    LiveHint(LiveKind.ROOM_MEMBER_REMOVED, room, targetUserIds = listOf(member.id)),
                ),
                hints,
            )
        }
    }

    @Test
    fun `an administrator who is not a member cannot leave - 404 - but a member administrator can`() = testApplication {
        usePostgresTestcontainer()
        val owner = actor("mem-adm-owner")
        val admin = actor("mem-adm-admin", UserRole.ADMIN)
        val room = room(owner)
        assertEquals(HttpStatusCode.NotFound, admin.client.delete(member(room, admin.id)).status)
        assertEquals(HttpStatusCode.OK, admin.client.add(room, listOf(admin.id)).status, "an administrator may add themselves")
        assertEquals(HttpStatusCode.NoContent, admin.client.delete(member(room, admin.id)).status)
    }

    @Test
    fun `the last active OWNER cannot leave, be demoted or be removed - even by an administrator`() = testApplication {
        usePostgresTestcontainer()
        val owner = actor("mem-last-owner")
        val ann = actor("mem-last-ann")
        val admin = actor("mem-last-admin", UserRole.ADMIN)
        val room = room(owner)
        TestRooms.addMember(room, ann.id)

        suspend fun assertLastOwner(response: HttpResponse, what: String) {
            assertEquals(HttpStatusCode.Conflict, response.status, what)
            assertEquals(LAST_OWNER_PROBLEM_TYPE, response.problemType(), what)
        }
        assertLastOwner(owner.client.delete(member(room, owner.id)), "leave")
        assertLastOwner(owner.client.setRole(room, owner.id, RoomRole.MEMBER), "demote themselves")
        assertLastOwner(admin.client.delete(member(room, owner.id)), "removal by an administrator")
        assertLastOwner(admin.client.setRole(room, owner.id, RoomRole.MEMBER), "demotion by an administrator")
        assertEquals(RoomRole.OWNER, TestRooms.rawRole(room, owner.id), "every refusal wrote nothing")

        // Transfer, then leave.
        assertEquals(HttpStatusCode.NoContent, owner.client.setRole(room, ann.id, RoomRole.OWNER).status)
        assertEquals(HttpStatusCode.NoContent, owner.client.delete(member(room, owner.id)).status)
        assertEquals(mapOf(ann.id to RoomRole.OWNER), ann.client.roles(room))
        assertLastOwner(ann.client.delete(member(room, ann.id)), "the new last owner")
    }

    @Test
    fun `a soft-deleted owner does not count, and an administrator manages a room whose owners are all deleted`() =
        testApplication {
            usePostgresTestcontainer()
            val owner = actor("mem-del-owner")
            val second = actor("mem-del-second")
            val ann = actor("mem-del-ann")
            val admin = actor("mem-del-admin", UserRole.ADMIN)
            val room = room(owner)
            TestRooms.addMember(room, second.id, RoomRole.OWNER)
            TestRooms.addMember(room, ann.id)

            TestUsers.softDelete(second.id)
            assertEquals(HttpStatusCode.Conflict, owner.client.delete(member(room, owner.id)).status, "the deleted owner is inert")
            assertEquals(HttpStatusCode.Conflict, owner.client.setRole(room, owner.id, RoomRole.MEMBER).status)

            TestUsers.softDelete(owner.id)
            // Nobody active owns the room: an ADMIN promotes, and the rule is then satisfied by the promoted member.
            assertEquals(HttpStatusCode.NoContent, admin.client.setRole(room, ann.id, RoomRole.OWNER).status)
            assertEquals(HttpStatusCode.Conflict, admin.client.delete(member(room, ann.id)).status)
            assertEquals(listOf(ann.id), admin.client.page(room, "?role=OWNER").items.map { it.userId }, "inert owners are not listed")
        }

    @Test
    fun `membership management stays allowed on an archived room`() = testApplication {
        usePostgresTestcontainer()
        val owner = actor("mem-arch-owner")
        val ann = actor("mem-arch-ann")
        val bea = actor("mem-arch-bea")
        val room = room(owner)
        TestRooms.addMember(room, bea.id)
        TestRooms.archive(room)

        assertEquals(RoomMembersAddResponse(1, 0), owner.client.add(room, listOf(ann.id)).body())
        assertEquals(HttpStatusCode.NoContent, owner.client.setRole(room, ann.id, RoomRole.OWNER).status)
        assertEquals(HttpStatusCode.NoContent, owner.client.delete(member(room, bea.id)).status)
        assertEquals(HttpStatusCode.NoContent, ann.client.delete(member(room, ann.id)).status, "a leave too")
        assertEquals(setOf(owner.id), owner.client.roles(room).keys)
    }

    @Test
    fun `the member list filters by role and name, sorts, pages and shows active users only`() = testApplication {
        usePostgresTestcontainer()
        val owner = actor("mem-list-owner", name = "Zed Owner")
        val ann = actor("mem-list-ann", name = "Ann Émile")
        val bea = actor("mem-list-bea", name = "Bea Brown")
        val doomed = actor("mem-list-doomed", name = "Doomed Dan")
        val outsider = actor("mem-list-out")
        val room = room(owner)
        TestRooms.addMember(room, ann.id)
        TestRooms.addMember(room, bea.id)
        TestRooms.addMember(room, doomed.id)
        TestUsers.softDelete(doomed.id)

        val all = owner.client.page(room, "?sort=name")
        assertEquals(listOf("Ann Émile", "Bea Brown", "Zed Owner"), all.items.map { it.name })
        assertEquals(3L, all.total, "an inert member is neither listed nor counted")
        assertTrue(all.items.first().email.startsWith("mem-list-ann-"))
        assertEquals(listOf(owner.id), owner.client.page(room, "?role=OWNER").items.map { it.userId })
        assertEquals(listOf(ann.id, bea.id), owner.client.page(room, "?role=MEMBER&sort=id").items.map { it.userId })
        assertEquals(listOf(ann.id), owner.client.page(room, "?name=emile").items.map { it.userId }, "case- and accent-insensitive")
        assertEquals(listOf("Zed Owner", "Bea Brown", "Ann Émile"), owner.client.page(room, "?sort=-name").items.map { it.name })
        assertEquals(owner.id, owner.client.page(room, "?sort=-role,id").items.first().userId, "-role puts the OWNER first")
        assertEquals(owner.id, owner.client.page(room, "?sort=role,id").items.last().userId, "role ascending puts the OWNER last")
        assertEquals(3, owner.client.page(room, "?sort=-addedAt").items.size)
        val second = owner.client.page(room, "?sort=name&pageSize=2&page=2")
        assertEquals(listOf("Zed Owner"), second.items.map { it.name })
        assertEquals(3L, second.total)

        assertEquals(HttpStatusCode.BadRequest, owner.client.get(members(room) + "?role=BOSS").status)
        assertEquals(HttpStatusCode.BadRequest, owner.client.get(members(room) + "?sort=email").status)
        assertEquals(HttpStatusCode.BadRequest, owner.client.get(members(room) + "?name=${"x".repeat(101)}").status)
        assertEquals(HttpStatusCode.BadRequest, owner.client.get(members(room) + "?role=OWNER&role=MEMBER").status)
        assertEquals(HttpStatusCode.NotFound, outsider.client.get(members(room)).status)
    }

    @Test
    fun `an owner demoted while another owner's leave waits for the room lock makes that leave 409 last-owner`() = testApplication {
        usePostgresTestcontainer()
        val ann = actor("mem-race-ann")
        val bea = actor("mem-race-bea")
        val room = room(ann)
        TestRooms.addMember(room, bea.id, RoomRole.OWNER)

        PostgresTestSupport.connect().use { raw ->
            raw.autoCommit = false
            // The raw transaction holds the room lock and demotes ann; neither is visible until it commits.
            raw.prepareStatement("SELECT id FROM rooms WHERE id = ? FOR NO KEY UPDATE").use { st ->
                st.setInt(1, room.toInt())
                st.executeQuery().close()
            }
            raw.prepareStatement("UPDATE room_members SET role = 'MEMBER' WHERE room_id = ? AND user_id = ?").use { st ->
                st.setInt(1, room.toInt())
                st.setLong(2, ann.id.toLong())
                st.executeUpdate()
            }
            try {
                coroutineScope {
                    // The route's own checks pass (the demotion is uncommitted); bea's leave then waits for the room lock.
                    val leaving = async { bea.client.delete(member(room, bea.id)) }
                    awaitLockWait("FOR NO KEY UPDATE")
                    raw.commit()
                    val response = leaving.await()
                    assertEquals(HttpStatusCode.Conflict, response.status)
                    assertEquals(LAST_OWNER_PROBLEM_TYPE, response.problemType())
                }
            } finally {
                raw.rollback() // a no-op after the commit; releases the lock if an assertion failed first
            }
        }
        assertEquals(RoomRole.OWNER, TestRooms.rawRole(room, bea.id), "the refused leave wrote nothing")
    }

    @Test
    fun `an owner demoted while an add waits is refused before correlation validation`() = testApplication {
        usePostgresTestcontainer()
        val owner = actor("mem-add-race-owner")
        val ann = actor("mem-add-race-ann")
        val room = room(owner)
        val request = RoomMembersAddRequest(userIds = listOf(ann.id))
        val malformedMutationId = "2C6C7F12-79A4-4F1A-8DC7-E9686608B20A"

        PostgresTestSupport.connect().use { raw ->
            raw.autoCommit = false
            raw.prepareStatement("SELECT id FROM rooms WHERE id = ? FOR NO KEY UPDATE").use { st ->
                st.setInt(1, room.toInt())
                st.executeQuery().close()
            }
            raw.prepareStatement("UPDATE room_members SET role = 'MEMBER' WHERE room_id = ? AND user_id = ?").use { st ->
                st.setInt(1, room.toInt())
                st.setLong(2, owner.id.toLong())
                st.executeUpdate()
            }
            try {
                coroutineScope {
                    // The route sees the still-committed OWNER. Header validation must wait behind the authoritative locked re-check.
                    val adding = async { owner.client.addCorrelated(room, request, malformedMutationId) }
                    awaitLockWait("FOR NO KEY UPDATE")
                    raw.commit()
                    assertEquals(HttpStatusCode.Forbidden, adding.await().status)
                }
            } finally {
                raw.rollback()
            }
        }
        assertNull(TestRooms.rawRole(room, ann.id), "the refused add wrote no membership")
    }

    @Test
    fun `two owners leaving at once cannot empty the room - exactly one is refused`() = testApplication {
        usePostgresTestcontainer()
        val ann = actor("mem-both-ann-leave")
        val bea = actor("mem-both-bea-leave")
        val room = room(ann)
        TestRooms.addMember(room, bea.id, RoomRole.OWNER)

        val statuses = coroutineScope {
            val first = async { ann.client.delete(member(room, ann.id)).status }
            val second = async { bea.client.delete(member(room, bea.id)).status }
            listOf(first.await(), second.await())
        }
        assertEquals(listOf(HttpStatusCode.NoContent, HttpStatusCode.Conflict), statuses.sortedBy { it.value })
        assertEquals(1, TestRooms.rawChildCounts(room).first, "one owner remains")
    }

    @Test
    fun `a team whose members are all soft-deleted adds nobody - 200 with zero added and zero already members`() = testApplication {
        usePostgresTestcontainer()
        val owner = actor("mem-empty-owner")
        val gone1 = actor("mem-empty-gone1")
        val gone2 = actor("mem-empty-gone2")
        val teamId = team(gone1, gone2)
        TestUsers.softDelete(gone1.id)
        TestUsers.softDelete(gone2.id)
        val room = room(owner)

        val response = owner.client.add(room, teamId = teamId)
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(RoomMembersAddResponse(added = 0, alreadyMembers = 0), response.body())
        assertEquals(setOf(owner.id), owner.client.roles(room).keys)
    }

    @Test
    fun `a caller removed from the room while their own leave waits for the room lock gets 404`() = testApplication {
        usePostgresTestcontainer()
        val owner = actor("mem-lockleave-owner")
        val ann = actor("mem-lockleave-ann")
        val room = room(owner)
        TestRooms.addMember(room, ann.id)

        PostgresTestSupport.connect().use { raw ->
            raw.autoCommit = false
            raw.prepareStatement("SELECT id FROM rooms WHERE id = ? FOR NO KEY UPDATE").use { st ->
                st.setInt(1, room.toInt())
                st.executeQuery().close()
            }
            raw.prepareStatement("DELETE FROM room_members WHERE room_id = ? AND user_id = ?").use { st ->
                st.setInt(1, room.toInt())
                st.setLong(2, ann.id.toLong())
                st.executeUpdate()
            }
            try {
                coroutineScope {
                    // The route's own checks pass (the removal is uncommitted); the leave then waits for the room lock and is re-judged.
                    val leaving = async { ann.client.delete(member(room, ann.id)) }
                    awaitLockWait("FOR NO KEY UPDATE")
                    raw.commit()
                    assertEquals(HttpStatusCode.NotFound, leaving.await().status)
                }
            } finally {
                raw.rollback()
            }
        }
        assertEquals(setOf(owner.id), owner.client.roles(room).keys)
    }

    @Test
    fun `a negative id in the add body is a 400 and nothing is added`() = testApplication {
        usePostgresTestcontainer()
        val owner = actor("mem-neg-owner")
        val room = room(owner)
        suspend fun raw(body: String) = owner.client.post(members(room)) {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        assertEquals(HttpStatusCode.BadRequest, raw("""{"userIds":[-1]}""").status)
        assertEquals(HttpStatusCode.BadRequest, raw("""{"teamId":-1}""").status)
        assertEquals(setOf(owner.id), owner.client.roles(room).keys)
    }

    @Test
    fun `the member add refuses a body over 16 KiB with 413 before decoding it, and a normal add is unaffected`() = testApplication {
        usePostgresTestcontainer()
        val owner = actor("mem-big-owner")
        val ann = actor("mem-big-ann")
        val outsider = actor("mem-big-out")
        val room = room(owner)
        suspend fun HttpClient.rawAdd(body: String) = post(members(room)) {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        // About 1 MiB of ids: not even decoded (it would also be a 400 for 200+ ids), refused on its size.
        val megabyte = """{"userIds":[${(1..170_000).joinToString(",") { "100000" }}]}"""
        assertTrue(megabyte.length > 1024 * 1024)
        assertEquals(HttpStatusCode.PayloadTooLarge, owner.client.rawAdd(megabyte).status)
        val twentyKib = """{"userIds":[${(1..3_000).joinToString(",") { "100000" }}]}"""
        assertTrue(twentyKib.length in (16 * 1024 + 1)..(40 * 1024))
        assertEquals(HttpStatusCode.PayloadTooLarge, owner.client.rawAdd(twentyKib).status)
        // The size check is cheaper than any guard (it reads the Content-Length before routing), so it answers for a non-member too.
        assertEquals(HttpStatusCode.PayloadTooLarge, outsider.client.rawAdd(megabyte).status)
        assertEquals(HttpStatusCode.OK, owner.client.add(room, listOf(ann.id)).status)
    }

    @Test
    fun `the member add rides a per-user bucket of 60 a minute - 429 beyond it, per user, reads unaffected`() = testApplication {
        usePostgresTestcontainer()
        val owner = actor("mem-bucket-owner")
        val other = actor("mem-bucket-other")
        val room = room(owner)
        val otherRoom = room(other)
        withAuditCapture { capture ->
            repeat(60) { n -> assertEquals(HttpStatusCode.OK, owner.client.add(room, listOf(other.id)).status, "add ${n + 1}") }
            val rejected = owner.client.add(room, listOf(other.id))
            assertEquals(HttpStatusCode.TooManyRequests, rejected.status)
            val event = capture.awaitEvent { it.message == "rate_limit.exceeded" && it.hasKeyValue("bucket", "room-members") }
            assertNotNull(event)
            assertTrue(event.hasKeyValue("byUserId", owner.id.toLong()))
            assertEquals(HttpStatusCode.OK, other.client.add(otherRoom, listOf(owner.id)).status, "another user's bucket is untouched")
            assertEquals(HttpStatusCode.OK, owner.client.get(members(room)).status, "reads are not bucketed")
            assertEquals(HttpStatusCode.NoContent, owner.client.setRole(room, other.id, RoomRole.OWNER).status, "neither are role changes")
        }
    }
}
