package ch.nokillswit

import ch.nokillswit.live.LiveTicketBinding
import ch.nokillswit.live.LiveTicketCapacityExceededException
import ch.nokillswit.live.LiveTicketDraft
import ch.nokillswit.live.LiveTicketStore

/** Deterministic store for ticket and socket tests that need isolated ticket state. */
internal class LiveTicketMemoryStore(
    private val maxTickets: Int,
    private val ttlMillis: Long,
    private val clock: () -> Long = System::currentTimeMillis,
) : LiveTicketStore {
    private val held = HashMap<String, LiveTicketBinding>()

    init {
        require(maxTickets > 0)
        require(ttlMillis > 0)
    }

    override suspend fun issue(digest: String, draft: LiveTicketDraft): Long = synchronized(held) {
        val now = clock()
        held.entries.removeIf { it.value.expiresAtMillis <= now }
        if (held.size >= maxTickets) throw LiveTicketCapacityExceededException()
        val expiresAt = minOf(now + ttlMillis, draft.tokenExpiresAtMillis)
        held[digest] = LiveTicketBinding(
            draft.userId, draft.isAdmin, draft.roles, draft.jti, draft.credentialRevision, draft.tokenExpiresAtMillis, expiresAt,
        )
        expiresAt
    }

    override suspend fun redeem(digest: String): LiveTicketBinding? = synchronized(held) {
        held.remove(digest)?.takeIf { it.expiresAtMillis > clock() }
    }
}

/** Soft-deleting an account deliberately retains its row, so tests remove its ephemeral ticket rows explicitly. */
internal fun deleteLiveTickets(userIds: Collection<UInt>) {
    if (userIds.isEmpty()) return
    PostgresTestSupport.connect().use { connection ->
        connection.prepareStatement("DELETE FROM live_tickets WHERE user_id = ANY(?)").use { statement ->
            statement.setArray(1, connection.createArrayOf("bigint", userIds.map { it.toLong() }.toTypedArray()))
            statement.executeUpdate()
        }
    }
}
