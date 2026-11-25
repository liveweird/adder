package ch.nokillswit.rooms

import ch.nokillswit.audit.audit

/** Writes notices for new memberships inside the transaction that added them. */
fun interface RoomNotices {
    suspend fun membersAdded(roomId: UInt, actorId: UInt, recipients: List<UInt>, named: Set<UInt>): Int

    companion object {
        internal val NONE = RoomNotices { _, _, _, _ -> 0 }
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
