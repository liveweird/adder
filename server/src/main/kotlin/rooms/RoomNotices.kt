package ch.nokillswit.rooms

import ch.nokillswit.audit.audit

/** Writes notices and coordinates their recipient locks inside membership transactions. */
interface RoomNotices {
    suspend fun membersAdded(roomId: UInt, actorId: UInt, recipients: List<UInt>, named: Set<UInt>): Int

    /** Locks this membership's notice rows in ascending bounded batches before its cascading delete. */
    suspend fun prepareMemberRemoval(roomId: UInt, userId: UInt) = Unit

    companion object {
        internal val NONE = object : RoomNotices {
            override suspend fun membersAdded(roomId: UInt, actorId: UInt, recipients: List<UInt>, named: Set<UInt>) = 0
        }
    }
}

/** Called by the routes after the membership changes and their notices have committed. */
internal fun auditRoomNotices(actorId: UInt, roomId: UInt, count: Int) {
    if (count == 0) return
    audit(
        "notification.created",
        "byUserId" to actorId.toLong(),
        "roomId" to roomId.toLong(),
        "recipients" to count,
        "kinds" to "ROOM_ADDED:$count",
    )
}
