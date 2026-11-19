package ch.nokillswit

import kotlinx.coroutines.runBlocking
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `RoomService.lostMemberships` (the live sweep's membership read), directly against the shared container: which (user, room) pairs of a
 * socket's ACTIVE subscriptions no longer have an ACTIVE member of an existing room behind them. The chunking (500 distinct rooms per
 * read), a deleted room, a soft-deleted user, a removed member and a room that never existed.
 */
class LostMembershipsTest {
    private val users = mutableListOf<UInt>()
    private val roomIds = mutableListOf<UInt>()
    private val templates = mutableListOf<UInt>()

    @AfterTest
    fun cleanUp() = runBlocking {
        TestRooms.purge(roomIds)
        TestRooms.softDeleteUsers(users)
        templates.forEach { TestTemplates.delete(it) }
    }

    private suspend fun user(): UInt = TestUsers.seed(email = uniqueEmail("lost"), password = "pw").also { users += it }

    private suspend fun room(owner: UInt, vararg members: UInt): UInt {
        val template = TestThreads.seedTemplate().also { templates += it }
        val room = TestRooms.seed(owner, template, "Lost ${UUID.randomUUID().toString().substring(0, 8)}").also { roomIds += it }
        members.forEach { TestRooms.addMember(room, it) }
        return room
    }

    private fun softDeleteRoom(roomId: UInt) = PostgresTestSupport.connect().use { connection ->
        connection.prepareStatement("UPDATE rooms SET marked_as_deleted = TRUE WHERE id = ?").use { st ->
            st.setInt(1, roomId.toInt())
            assertEquals(1, st.executeUpdate())
        }
    }

    private fun removeMember(roomId: UInt, userId: UInt) = PostgresTestSupport.connect().use { connection ->
        connection.prepareStatement("DELETE FROM room_members WHERE room_id = ? AND user_id = ?").use { st ->
            st.setInt(1, roomId.toInt())
            st.setLong(2, userId.toLong())
            assertEquals(1, st.executeUpdate())
        }
    }

    /** [count] bare rooms (no workflow rows: this read touches only `rooms` and `room_members`) with [member] in all but [except]. */
    private fun bulkRooms(count: Int, member: UInt, except: Int?): List<UInt> = PostgresTestSupport.connect().use { connection ->
        val marker = UUID.randomUUID().toString().substring(0, 8)
        val ids = connection.prepareStatement(
            "INSERT INTO rooms (\"name\", last_activity_at, created_at, updated_at) " +
                "SELECT 'Bulk ' || ? || ' ' || g, 1, 1, 1 FROM generate_series(1, ?) g ORDER BY g RETURNING id",
        ).use { st ->
            st.setString(1, marker)
            st.setInt(2, count)
            st.executeQuery().use { rs -> generateSequence { if (rs.next()) rs.getInt(1).toUInt() else null }.toList() }
        }.sorted()
        roomIds += ids
        val withMember = ids.filterIndexed { index, _ -> index != except }
        connection.prepareStatement(
            "INSERT INTO room_members (room_id, user_id, \"role\", added_at) SELECT id, ?, 'MEMBER', 1 FROM rooms WHERE id = ANY (?)",
        ).use { st ->
            st.setLong(1, member.toLong())
            st.setArray(2, connection.createArrayOf("integer", withMember.map { it.toInt() }.toTypedArray()))
            assertEquals(withMember.size, st.executeUpdate())
        }
        ids
    }

    @Test
    fun `nothing held is nothing lost`() = runBlocking {
        assertEquals(emptySet(), TestRooms.service.lostMemberships(emptyList()))
    }

    @Test
    fun `an active member of an existing room keeps it, a removed member does not`() = runBlocking {
        val owner = user()
        val member = user()
        val room = room(owner, member)
        assertEquals(emptySet(), TestRooms.service.lostMemberships(listOf(owner to room, member to room)))
        removeMember(room, member)
        assertEquals(setOf(member to room), TestRooms.service.lostMemberships(listOf(owner to room, member to room)))
    }

    @Test
    fun `a deleted room is lost for every member, and so is a room that never existed`() = runBlocking {
        val owner = user()
        val member = user()
        val room = room(owner, member)
        val other = room(owner)
        softDeleteRoom(room)
        val never = 2_000_000_000u
        assertEquals(
            setOf(owner to room, member to room, owner to never),
            TestRooms.service.lostMemberships(listOf(owner to room, member to room, owner to other, owner to never)),
        )
    }

    @Test
    fun `a soft-deleted user is lost in every room, an active one next to them is not`() = runBlocking {
        val owner = user()
        val gone = user()
        val room = room(owner, gone)
        TestUsers.softDelete(gone)
        assertEquals(setOf(gone to room), TestRooms.service.lostMemberships(listOf(owner to room, gone to room)))
    }

    @Test
    fun `more than 500 rooms are read in chunks and each pair is judged against its own room`() = runBlocking {
        val member = user()
        val stranger = user()
        // 620 distinct rooms = two chunks of 500 and 120. The member is in all but the room at index 550 (second chunk); the stranger
        // in none.
        val rooms = bulkRooms(620, member, except = 550)
        val pairs = rooms.map { member to it } + (stranger to rooms[3]) + (stranger to rooms[600])
        val lost = TestRooms.service.lostMemberships(pairs)
        assertEquals(setOf(member to rooms[550], stranger to rooms[3], stranger to rooms[600]), lost)
        assertEquals(620, rooms.toSet().size)
    }
}
