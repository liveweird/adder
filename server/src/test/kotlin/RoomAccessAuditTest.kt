package ch.nokillswit

import ch.nokillswit.authz.CallerPrincipal
import ch.nokillswit.authz.NotFoundException
import ch.nokillswit.authz.RoomAccessType
import ch.nokillswit.rooms.visibleStanding
import ch.nokillswit.users.UserRole
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * `room.access_denied` is COALESCED per user (`rooms/RoomAccess.kt`, the `rate_limit.exceeded` idiom): a member sweeping room ids
 * writes one audit line a minute, not one per probe, and every probe still answers the same `404`. Run at the service seam so the
 * count is exact whatever else the suite audits. Users and rooms are SHARED suite state: every test mints UNIQUE rows.
 */
class RoomAccessAuditTest {
    private val users = mutableListOf<UInt>()
    private val rooms = mutableListOf<UInt>()
    private val templates = mutableListOf<UInt>()

    @AfterTest
    fun cleanUp() = runBlocking {
        TestRooms.purge(rooms)
        users.forEach { TestUsers.softDelete(it) }
        templates.forEach { TestTemplates.delete(it) }
    }

    private suspend fun user(prefix: String, role: UserRole = UserRole.USER): CallerPrincipal {
        val email = uniqueEmail(prefix)
        val id = TestUsers.seed(email = email, password = "pw", role = role).also { users += it }
        return CallerPrincipal(id, email, setOf(role))
    }

    @Test
    fun `fifty probes of an existing room by one outsider write one line, another outsider and the member write their own or none`() =
        runBlocking {
            val owner = user("ra-owner")
            val outsider = user("ra-out")
            val other = user("ra-other")
            val admin = user("ra-admin", UserRole.ADMIN)
            val template = TestThreads.seedTemplate().also { templates += it }
            val room = TestRooms.seed(
                owner.userId, template, "Aud ${UUID.randomUUID().toString().substring(0, 8)}", accessType = RoomAccessType.PUBLIC,
            ).also { rooms += it }
            val service = TestRooms.service

            withAuditCapture { capture ->
                repeat(50) { assertFailsWith<NotFoundException> { service.visibleStanding(outsider, room) } }
                fun lines(who: CallerPrincipal) = capture.events.filter {
                    it.message == "room.access_denied" && it.hasKeyValue("byUserId", who.userId.toLong())
                }
                val first = lines(outsider).single()
                assertTrue(first.hasKeyValue("roomId", room.toLong()) && first.hasKeyValue("suppressed", 0L))

                // The window is per USER: a second outsider still writes a line of their own.
                assertFailsWith<NotFoundException> { service.visibleStanding(other, room) }
                assertEquals(1, lines(other).size)
                assertEquals(1, lines(outsider).size, "the first outsider's window is untouched")

                // A member and an ADMIN reading this public room are no denial; a room that does not exist has nothing to deny.
                service.visibleStanding(owner, room)
                service.visibleStanding(admin, room)
                assertFailsWith<NotFoundException> { service.visibleStanding(other, 2_000_000_000u) }
                assertEquals(0, lines(owner).size + lines(admin).size)
                assertEquals(1, lines(other).size)
            }
        }
}
