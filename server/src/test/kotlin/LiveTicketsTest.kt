package ch.nokillswit

import ch.nokillswit.live.LIVE_TICKET_TTL_MILLIS
import ch.nokillswit.live.LiveTicketBinding
import ch.nokillswit.live.LiveTickets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Unit tests for the in-memory socket-ticket store (deterministic via an injected clock; `MfaChallengesTest`'s shape). */
class LiveTicketsTest {

    private var now = 1_000_000L
    private fun store(maxTickets: Int = 10_000, ttlMillis: Long = LIVE_TICKET_TTL_MILLIS) =
        LiveTickets(maxTickets, ttlMillis, clock = { now })

    private fun LiveTickets.mint(userId: UInt = 7u, jti: String = "jti-$userId") = issue(
        userId = userId,
        isAdmin = false,
        roles = emptySet(),
        jti = jti,
        credentialRevision = 3,
        tokenExpiresAtMillis = now + 900_000,
    )

    @Test
    fun `a ticket redeems exactly once - the second redemption is indistinguishable from an unknown ticket`() {
        val s = store()
        val issued = s.mint()
        assertNotNull(s.redeem(issued.ticket))
        assertNull(s.redeem(issued.ticket))
        assertNull(s.redeem("not-a-ticket"))
        assertEquals(0, s.size(), "a redeemed ticket frees its slot")
    }

    @Test
    fun `a ticket is 43 base64url characters and every mint is different`() {
        val s = store()
        val tickets = List(50) { s.mint().ticket }
        assertEquals(50, tickets.toSet().size)
        tickets.forEach { assertTrue(Regex("[A-Za-z0-9_-]{43}").matches(it), "bad shape: $it") }
    }

    @Test
    fun `the binding round-trips what the mint was given and the ticket's own deadline`() {
        val s = store()
        val issued = s.issue(
            userId = 42u,
            isAdmin = true,
            roles = setOf("ADMIN"),
            jti = "the-jti",
            credentialRevision = 9,
            tokenExpiresAtMillis = now + 123_000,
        )
        assertEquals(now + LIVE_TICKET_TTL_MILLIS, issued.expiresAtMillis)
        val binding = s.redeem(issued.ticket)
        assertEquals(
            LiveTicketBinding(
                userId = 42u,
                isAdmin = true,
                roles = setOf("ADMIN"),
                jti = "the-jti",
                credentialRevision = 9,
                tokenExpiresAtMillis = now + 123_000,
                expiresAtMillis = now + LIVE_TICKET_TTL_MILLIS,
            ),
            binding,
        )
    }

    @Test
    fun `a ticket expires by the clock at its TTL - the last millisecond redeems, the deadline does not`() {
        val s = store(ttlMillis = 30_000)
        val early = s.mint(userId = 1u)
        val late = s.mint(userId = 2u)
        now += 29_999
        assertNotNull(s.redeem(early.ticket), "one millisecond before the deadline")
        now += 1
        assertNull(s.redeem(late.ticket), "at the deadline")
        assertNull(s.redeem(late.ticket), "an expired ticket is dropped, not retried")
    }

    @Test
    fun `a ticket never outlives the access token it was minted with`() {
        val s = store(ttlMillis = 30_000)
        val short = s.issue(1u, false, emptySet(), "jti", 0, tokenExpiresAtMillis = now + 10_000)
        assertEquals(now + 10_000, short.expiresAtMillis, "the token's exp is earlier than the TTL")
        val exact = s.issue(2u, false, emptySet(), "jti", 0, tokenExpiresAtMillis = now + 30_000)
        assertEquals(now + 30_000, exact.expiresAtMillis, "equal deadlines")
        val long = s.mint(userId = 3u)
        assertEquals(now + 30_000, long.expiresAtMillis, "a long-lived token leaves the TTL")
        now += 10_000
        assertNull(s.redeem(short.ticket), "redeemable only while the token itself is alive")
        assertEquals(20_000L, s.redeem(exact.ticket)?.let { it.expiresAtMillis - now })
        assertNotNull(s.redeem(long.ticket))
    }

    @Test
    fun `expired tickets are swept on every mint and free their capacity`() {
        val s = store(maxTickets = 3, ttlMillis = 30_000)
        repeat(3) { s.mint(userId = (it + 1).toUInt()) }
        assertEquals(3, s.size())
        now += 30_000
        // Full of EXPIRED tickets: the sweep inside the mint reclaims them rather than refusing.
        s.mint(userId = 9u)
        assertEquals(1, s.size())
    }

    @Test
    fun `a full store refuses a new ticket but keeps the held ones redeemable`() {
        val s = store(maxTickets = 2)
        val first = s.mint(userId = 1u)
        s.mint(userId = 2u)
        assertFailsWith<LiveTickets.CapacityExceededException> { s.mint(userId = 3u) }
        assertNotNull(s.redeem(first.ticket), "saturation rejects the new, never evicts the held")
        s.mint(userId = 3u)
    }

    @Test
    fun `several tickets of one user coexist and are independent`() {
        val s = store()
        val a = s.mint(userId = 5u)
        val b = s.mint(userId = 5u)
        assertNotEquals(a.ticket, b.ticket)
        assertEquals(5u, s.redeem(b.ticket)?.userId)
        assertEquals(5u, s.redeem(a.ticket)?.userId)
    }

    @Test
    fun `concurrent redemptions of one ticket have exactly one winner`() {
        val s = LiveTickets(maxTickets = 10)
        val ticket = s.issue(1u, false, emptySet(), "jti", 0, System.currentTimeMillis() + 60_000).ticket
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        val wins = AtomicInteger()
        val done = CountDownLatch(8)
        repeat(8) {
            pool.execute {
                start.await()
                if (s.redeem(ticket) != null) wins.incrementAndGet()
                done.countDown()
            }
        }
        start.countDown()
        assertTrue(done.await(10, TimeUnit.SECONDS))
        pool.shutdownNow()
        assertEquals(1, wins.get())
    }

    @Test
    fun `the store is never built with a non-positive bound`() {
        assertFailsWith<IllegalArgumentException> { LiveTickets(maxTickets = 0) }
        assertFailsWith<IllegalArgumentException> { LiveTickets(maxTickets = 1, ttlMillis = 0) }
    }

    @Test
    fun `the issued ticket and the binding mask their secrets`() {
        val s = store()
        val issued = s.mint(jti = "JTI-MARKER")
        assertFalse(issued.ticket in issued.toString(), "the ticket leaked into: $issued")
        assertTrue("expiresAtMillis=${issued.expiresAtMillis}" in issued.toString())
        val binding = s.redeem(issued.ticket)!!
        assertFalse("JTI-MARKER" in binding.toString(), "the jti leaked into: $binding")
        assertTrue("userId=7" in binding.toString())
    }
}
