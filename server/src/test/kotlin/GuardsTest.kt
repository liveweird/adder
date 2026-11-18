package ch.nokillswit

import ch.nokillswit.authz.CallerPrincipal
import ch.nokillswit.authz.ForbiddenException
import ch.nokillswit.authz.isAdmin
import ch.nokillswit.authz.isThreadSteward
import ch.nokillswit.authz.requireAdmin
import ch.nokillswit.authz.NotFoundException
import ch.nokillswit.authz.requireFeatureEnabled
import ch.nokillswit.authz.requireMessageAuthor
import ch.nokillswit.authz.requireMessageRemover
import ch.nokillswit.authz.requireRoomManager
import ch.nokillswit.authz.requireRoomMember
import ch.nokillswit.authz.requireRoomReader
import ch.nokillswit.authz.requireSelfOrAdmin
import ch.nokillswit.authz.requireTeamManagerOrAdmin
import ch.nokillswit.authz.requireThreadSteward
import ch.nokillswit.authz.RoomRole
import ch.nokillswit.authz.RoomStanding
import ch.nokillswit.users.UserRole
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Pure unit tests for the authz guards (no server, no database). */
class GuardsTest {

    private val admin = CallerPrincipal(userId = 1u, email = "admin@test", roles = setOf(UserRole.ADMIN))
    private val user = CallerPrincipal(userId = 2u, email = "user@test", roles = emptySet())

    @Test
    fun `isAdmin reflects the ADMIN role`() {
        assertTrue(admin.isAdmin())
        assertFalse(user.isAdmin())
    }

    @Test
    fun `requireFeatureEnabled rejects a caller whose set carries the feature`() {
        requireFeatureEnabled(user, ch.nokillswit.users.Feature.MFA)
        val gated = CallerPrincipal(
            userId = 3u,
            email = "gated@test",
            roles = emptySet(),
            disabledFeatures = setOf(ch.nokillswit.users.Feature.MFA),
        )
        assertFailsWith<ForbiddenException> { requireFeatureEnabled(gated, ch.nokillswit.users.Feature.MFA) }
    }

    @Test
    fun `requireAdmin passes an admin and rejects a regular user`() {
        requireAdmin(admin)
        assertFailsWith<ForbiddenException> { requireAdmin(user) }
    }

    @Test
    fun `requireSelfOrAdmin allows self, allows admin on anyone, rejects a third party`() {
        requireSelfOrAdmin(user, targetUserId = 2u)
        requireSelfOrAdmin(admin, targetUserId = 2u)
        assertFailsWith<ForbiddenException> { requireSelfOrAdmin(user, targetUserId = 3u) }
    }

    @Test
    fun `requireTeamManagerOrAdmin passes an admin without consulting the lookup, passes the manager, rejects everyone else uniformly`() =
        runBlocking<Unit> {
            val asked = mutableListOf<Pair<UInt, UInt>>()
            val managerOfTeam7: suspend (UInt, UInt) -> Boolean = { team, who ->
                asked += team to who
                team == 7u && who == user.userId
            }
            requireTeamManagerOrAdmin(admin, teamId = 7u, isManager = managerOfTeam7)
            requireTeamManagerOrAdmin(admin, teamId = 999u, isManager = managerOfTeam7)
            assertEquals(emptyList(), asked, "an admin never reaches the lookup")

            requireTeamManagerOrAdmin(user, teamId = 7u, isManager = managerOfTeam7)
            val stranger = CallerPrincipal(userId = 9u, email = "stranger@test", roles = emptySet())
            assertFailsWith<ForbiddenException> { requireTeamManagerOrAdmin(stranger, teamId = 7u, isManager = managerOfTeam7) }
            // The manager of ANOTHER team, and an unknown team, fail the same way (the lookup answers false).
            assertFailsWith<ForbiddenException> { requireTeamManagerOrAdmin(user, teamId = 8u, isManager = managerOfTeam7) }
            assertFailsWith<ForbiddenException> { requireTeamManagerOrAdmin(user, teamId = 999999u, isManager = managerOfTeam7) }
        }

    @Test
    fun `requireTeamManagerOrAdmin fails closed - a lookup failure propagates for a non-admin and is never reached for an admin`() =
        runBlocking<Unit> {
            val broken: suspend (UInt, UInt) -> Boolean = { _, _ -> error("database unavailable") }
            requireTeamManagerOrAdmin(admin, teamId = 7u, isManager = broken)
            val failure = assertFailsWith<IllegalStateException> { requireTeamManagerOrAdmin(user, teamId = 7u, isManager = broken) }
            assertEquals("database unavailable", failure.message)
        }

    private val owner = RoomStanding(RoomRole.OWNER, archived = false)
    private val member = RoomStanding(RoomRole.MEMBER, archived = false)
    private val outside = RoomStanding(role = null, archived = false)

    @Test
    fun `requireRoomReader admits a member and an admin, and answers a missing room and a non-member with the same 404`() {
        requireRoomReader(user, member)
        requireRoomReader(user, owner)
        requireRoomReader(admin, outside) // an ADMIN reads every room, member or not
        requireRoomReader(admin, member)
        val missing = assertFailsWith<NotFoundException> { requireRoomReader(user, null) }
        val stranger = assertFailsWith<NotFoundException> { requireRoomReader(user, outside) }
        assertEquals(missing.message, stranger.message, "a missing room and a non-member are indistinguishable")
        // An ADMIN gets no free pass for a room that does not exist: nothing to read.
        assertFailsWith<NotFoundException> { requireRoomReader(admin, null) }
    }

    @Test
    fun `requireRoomManager admits the OWNER and an admin, refuses a plain member with 403 and an outsider with the 404`() {
        requireRoomManager(user, owner)
        requireRoomManager(admin, owner)
        requireRoomManager(admin, outside) // an ADMIN manages every room
        requireRoomManager(admin, member)
        assertFailsWith<ForbiddenException> { requireRoomManager(user, member) }
        // Visibility comes first: an outsider must never learn the room exists through a 403.
        assertFailsWith<NotFoundException> { requireRoomManager(user, outside) }
        assertFailsWith<NotFoundException> { requireRoomManager(user, null) }
        assertFailsWith<NotFoundException> { requireRoomManager(admin, null) }
    }

    @Test
    fun `requireRoomMember admits a member, refuses an administrator outside the room with 403 and an outsider with the 404`() {
        requireRoomMember(user, member)
        requireRoomMember(user, owner)
        requireRoomMember(admin, member) // an ADMIN who IS a member speaks like any member
        // Only members speak: an ADMIN reads every room but is refused here, after the reader check passed.
        assertFailsWith<ForbiddenException> { requireRoomMember(admin, outside) }
        // Visibility comes first: an outsider must never learn the room exists through a 403.
        assertFailsWith<NotFoundException> { requireRoomMember(user, outside) }
        assertFailsWith<NotFoundException> { requireRoomMember(user, null) }
        assertFailsWith<NotFoundException> { requireRoomMember(admin, null) }
    }

    @Test
    fun `the room guards ignore the archived flag - archived is a state check made under the lock, not a permission`() {
        val archivedOwner = RoomStanding(RoomRole.OWNER, archived = true)
        requireRoomReader(user, archivedOwner)
        requireRoomManager(user, archivedOwner)
        requireRoomMember(user, archivedOwner)
    }

    @Test
    fun `requireThreadSteward admits the thread owner, the room owner and an admin, and refuses any other member with 403`() {
        val threadOwner = CallerPrincipal(userId = 5u, email = "to@test", roles = emptySet())
        requireThreadSteward(threadOwner, member, 5u)
        requireThreadSteward(threadOwner, owner, 5u)
        requireThreadSteward(user, owner, 5u) // the room OWNER, whoever owns the thread
        requireThreadSteward(admin, member, 5u)
        requireThreadSteward(admin, outside, 5u) // an ADMIN outside the room
        assertFailsWith<ForbiddenException> { requireThreadSteward(user, member, 5u) }
        assertFalse(isThreadSteward(user, member, 5u))
        assertTrue(isThreadSteward(threadOwner, member, 5u))
    }

    @Test
    fun `requireThreadSteward answers the 404 first - an outsider or a missing room, even for the thread's own owner`() {
        val threadOwner = CallerPrincipal(userId = 5u, email = "to@test", roles = emptySet())
        assertFailsWith<NotFoundException> { requireThreadSteward(threadOwner, outside, 5u) }
        assertFailsWith<NotFoundException> { requireThreadSteward(user, null, 5u) }
        assertFailsWith<NotFoundException> { requireThreadSteward(admin, null, 5u) }
        // A removed owner is no steward by that title alone: the verdict needs a role in the room.
        assertFalse(isThreadSteward(threadOwner, outside, 5u))
        assertFalse(isThreadSteward(threadOwner, null, 5u))
    }

    @Test
    fun `requireMessageAuthor admits only the author while a member - the room owner and an admin may not edit someone else's message`() {
        val author = CallerPrincipal(userId = 5u, email = "author@test", roles = emptySet())
        requireMessageAuthor(author, member, 5u)
        requireMessageAuthor(author, owner, 5u) // an OWNER edits their OWN message
        val adminAuthor = CallerPrincipal(userId = 6u, email = "admin-author@test", roles = setOf(UserRole.ADMIN))
        requireMessageAuthor(adminAuthor, member, 6u) // an ADMIN who is a member and wrote it
        assertFailsWith<ForbiddenException> { requireMessageAuthor(user, member, 5u) }
        assertFailsWith<ForbiddenException> { requireMessageAuthor(user, owner, 5u) } // the room OWNER: delete, never edit (D2)
        assertFailsWith<ForbiddenException> { requireMessageAuthor(admin, member, 5u) }
        assertFailsWith<ForbiddenException> { requireMessageAuthor(admin, outside, 1u) } // an ADMIN who is no member no longer speaks
    }

    @Test
    fun `requireMessageAuthor answers the 404 first - an outsider or a missing room, even for the author`() {
        val author = CallerPrincipal(userId = 5u, email = "author@test", roles = emptySet())
        assertFailsWith<NotFoundException> { requireMessageAuthor(author, outside, 5u) }
        assertFailsWith<NotFoundException> { requireMessageAuthor(user, null, 5u) }
        assertFailsWith<NotFoundException> { requireMessageAuthor(admin, null, 5u) }
    }

    @Test
    fun `requireMessageRemover admits the author while a member, the room owner and an admin, and refuses any other member with 403`() {
        val author = CallerPrincipal(userId = 5u, email = "author@test", roles = emptySet())
        requireMessageRemover(author, member, 5u)
        requireMessageRemover(user, owner, 5u) // the room OWNER removes anyone's message (D2)
        requireMessageRemover(admin, member, 5u)
        requireMessageRemover(admin, outside, 5u) // an ADMIN outside the room
        assertFailsWith<ForbiddenException> { requireMessageRemover(user, member, 5u) }
        // An author who left the room is no remover by that title alone: the verdict needs a role in the room, and the outsider sees 404.
        assertFailsWith<NotFoundException> { requireMessageRemover(author, outside, 5u) }
        assertFailsWith<NotFoundException> { requireMessageRemover(user, null, 5u) }
        assertFailsWith<NotFoundException> { requireMessageRemover(admin, null, 5u) }
    }
}
