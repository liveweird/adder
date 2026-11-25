package ch.nokillswit

import ch.nokillswit.infra.presence.ForegroundPresence
import kotlin.test.Test
import kotlin.test.assertEquals

class ForegroundPresenceTest {
    @Test
    fun `a user stays present while any visible tab has a fresh lease`() {
        var now = 100_000L
        val presence = ForegroundPresence({ now }, leaseMillis = 60_000)
        val first = presence.open(7u)
        val second = presence.open(7u)
        presence.update(first, true)
        presence.update(second, false)
        assertEquals(setOf(7u), presence.activeUserIds())

        presence.close(first)
        assertEquals(emptySet(), presence.activeUserIds())
        presence.update(second, true)
        now += 60_001
        assertEquals(emptySet(), presence.activeUserIds())
    }

    @Test
    fun `stop fails closed`() {
        val presence = ForegroundPresence()
        presence.update(presence.open(9u), true)
        presence.stop()
        assertEquals(emptySet(), presence.activeUserIds())
    }
}
