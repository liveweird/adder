package ch.nokillswit

import ch.nokillswit.auth.AccessCheck
import ch.nokillswit.auth.AccessVerdict
import ch.nokillswit.live.HubSocket
import ch.nokillswit.live.LiveAccountRechecks
import ch.nokillswit.live.SocketView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Pure account-prompt coalescing and exact-token decisions; no listener, database or WebSocket is involved. */
class LiveAccountRechecksTest {
    private fun socket(userId: UInt, jti: String) =
        HubSocket(userId, false, Long.MAX_VALUE, jti = jti, credentialRevision = 2, roles = emptySet())

    @Test
    fun `coalesced account ids check only those users and an exact revoked jti closes only its socket`() = runBlocking {
        val revoked = socket(7u, "revoked")
        val otherSession = socket(7u, "other")
        val unrelated = socket(8u, "unrelated")
        val checks = mutableListOf<AccessCheck>()
        val rechecks = LiveAccountRechecks(
            snapshot = { users ->
                assertEquals(setOf(7u), users)
                listOf(revoked, otherSession).map { SocketView(it, emptyList()) }
            },
            judge = { asked ->
                checks += asked
                asked.map { if (it.jti == "revoked") AccessVerdict.REVOKED else AccessVerdict.LIVE }
            },
        )

        repeat(20) { rechecks.accountChanged(7u) }
        assertEquals(1, rechecks.recheckNow())
        assertEquals(listOf("revoked", "other"), checks.map { it.jti })
        assertTrue(revoked.closeRequest.isCompleted)
        assertFalse(otherSession.closeRequest.isCompleted)
        assertFalse(unrelated.closeRequest.isCompleted)
    }

    @Test
    fun `overflow and a listener gap widen the pass to a full check`() = runBlocking {
        val sockets = (1u..4u).map { socket(it, "jti-$it") }
        val batches = mutableListOf<List<UInt>>()
        val rechecks = LiveAccountRechecks(
            snapshot = { sockets.map { SocketView(it, emptyList()) } },
            judge = { asked -> batches += asked.map { it.userId }; List(asked.size) { AccessVerdict.LIVE } },
            maxPending = 2,
        )

        rechecks.accountChanged(1u)
        rechecks.accountChanged(2u)
        rechecks.accountChanged(3u)
        rechecks.recheckNow()
        rechecks.accountChanged(1u)
        rechecks.gap()
        rechecks.recheckNow()

        assertEquals(listOf(listOf(1u, 2u, 3u, 4u), listOf(1u, 2u, 3u, 4u)), batches)
    }

    @Test
    fun `a failed pass retries once after a fresh socket snapshot`() = runBlocking {
        val first = socket(7u, "first")
        val replacement = socket(7u, "replacement")
        var snapshots = 0
        var attempts = 0
        val rechecks = LiveAccountRechecks(
            snapshot = {
                snapshots++
                listOf(SocketView(if (snapshots == 1) first else replacement, emptyList()))
            },
            judge = {
                attempts++
                if (attempts == 1) error("transient")
                listOf(AccessVerdict.REVOKED)
            },
            retryDelayMillis = 1,
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            rechecks.start(scope)
            rechecks.accountChanged(7u)
            withTimeout(5_000) {
                while (!replacement.closeRequest.isCompleted) delay(10)
            }
            assertEquals(2, snapshots)
            assertFalse(first.closeRequest.isCompleted)
            assertTrue(replacement.closeRequest.isCompleted)
        } finally {
            scope.cancel()
        }
    }
}
