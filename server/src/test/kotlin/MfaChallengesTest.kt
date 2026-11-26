package ch.nokillswit

import ch.nokillswit.auth.MfaChallenges
import ch.nokillswit.auth.MfaChallenges.Outcome
import ch.nokillswit.infra.crypto.FieldCipher
import ch.nokillswit.infra.json.sha256Hex
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The shared database is the authority for challenge limits, consumption, and expiry. */
class MfaChallengesTest {
    private val accounts = mutableListOf<UInt>()
    private val key = strongEncryptionKey()

    private fun store(maxAttempts: Int = 5, maxTracked: Int = 10_000, maxPerUser: Int = 3, cipher: FieldCipher = FieldCipher(key)) =
        MfaChallenges(sharedDatabaseForTests(), cipher, ttlMillis = 300_000, maxAttempts, maxTracked, maxPerUser)

    private suspend fun user(): UInt = TestUsers.seed(uniqueEmail("mfa-challenges"), "pw").also(accounts::add)

    @AfterTest
    fun cleanUp() = runBlocking {
        accounts.forEach { userId ->
            store().discardUser(userId)
            TestUsers.softDelete(userId)
        }
    }

    @Test
    fun `a rotated key ring redeems the digest-only encrypted challenge`() = runBlocking<Unit> {
        val user = user()
        val issued = store().issue(user, EMAIL, credentialRevision = 7)
        assertEquals(6, issued.code.length)
        assertTrue(issued.code.all { it.isDigit() })
        assertEquals(32, issued.challengeId.length)
        PostgresTestSupport.connect().use { connection ->
            connection.prepareStatement(
                "SELECT challenge_hash, encrypted_email, encrypted_code, expires_at FROM mfa_challenges WHERE user_id = ?",
            ).use { statement ->
                statement.setLong(1, user.toLong())
                statement.executeQuery().use { rows ->
                    assertTrue(rows.next())
                    assertEquals(sha256Hex(issued.challengeId), rows.getString(1))
                    assertTrue(rows.getString(2).startsWith(FieldCipher.PREFIX))
                    assertTrue(rows.getString(3).startsWith(FieldCipher.PREFIX))
                    assertNotEquals(EMAIL, rows.getString(2))
                    assertNotEquals(issued.code, rows.getString(3))
                    assertEquals(issued.expiresAt, rows.getLong(4))
                }
            }
        }
        val other = store(cipher = FieldCipher(strongEncryptionKey(), previousKeyHex = key))
        val outcome = assertIs<Outcome.Success>(other.verify(issued.challengeId, issued.code))
        assertEquals(MfaChallenges.Subject(user, EMAIL), outcome.subject)
        assertEquals(7L, outcome.credentialRevision)
        assertUnknown(store(), issued)
    }

    @Test
    fun `concurrent redemptions from separate stores have one winner`() = runBlocking {
        val issued = store().issue(user(), EMAIL, credentialRevision = 0)
        val outcomes = coroutineScope {
            List(8) { async { store().verify(issued.challengeId, issued.code) } }.awaitAll()
        }
        assertEquals(1, outcomes.count { it is Outcome.Success })
        assertTrue(outcomes.filterIsInstance<Outcome.Failure>().all { it.reason == "unknown_challenge" })
    }

    @Test
    fun `an expired challenge is discarded and audited as expiry instead of a guess`() = runBlocking {
        val user = user()
        val issued = store().issue(user, EMAIL, credentialRevision = 0)
        expire(issued)
        val failure = assertIs<Outcome.Failure>(store().verify(issued.challengeId, issued.code))
        assertEquals("expired", failure.reason)
        assertEquals(user, failure.subject?.userId)
        assertFalse(failure.wrongCode)
        assertUnknown(store(), issued)
    }

    @Test
    fun `the account and revision of an incorrect code are counted until attempts are exhausted`() = runBlocking {
        val user = user()
        val first = store(maxAttempts = 2)
        val second = store(maxAttempts = 2)
        val issued = first.issue(user, EMAIL, credentialRevision = 8)
        val wrong = assertIs<Outcome.Failure>(second.verify(issued.challengeId, "x"))
        assertEquals("wrong_code", wrong.reason)
        assertEquals(MfaChallenges.Subject(user, EMAIL), wrong.subject)
        assertEquals(8L, wrong.credentialRevision)
        assertTrue(wrong.wrongCode)
        val exhausted = assertIs<Outcome.Failure>(first.verify(issued.challengeId, "x"))
        assertEquals("too_many_attempts", exhausted.reason)
        assertTrue(exhausted.wrongCode)
        assertUnknown(second, issued)
        val malformed = assertIs<Outcome.Failure>(second.verify("not-a-challenge-id", "123456"))
        assertEquals("unknown_challenge", malformed.reason)
        assertNull(malformed.subject)
        assertFalse(malformed.wrongCode)
    }

    @Test
    fun `concurrent issuance enforces capacity and leaves the first challenge active`() = runBlocking<Unit> {
        val users = List(5) { user() }
        val outcomes = coroutineScope {
            users.map { id -> async { runCatching { store(maxTracked = 1).issue(id, EMAIL, 0) } } }.awaitAll()
        }
        assertEquals(1, outcomes.count { it.isSuccess })
        outcomes.filter { it.isFailure }.forEach { assertIs<MfaChallenges.CapacityExceededException>(it.exceptionOrNull()) }
        val issued = outcomes.single { it.isSuccess }.getOrThrow()
        assertIs<Outcome.Success>(store(maxTracked = 1).verify(issued.challengeId, issued.code))
    }

    @Test
    fun `a full store lets the same account replace its oldest slot and preserves another account's challenge`() = runBlocking<Unit> {
        val first = store(maxTracked = 2, maxPerUser = 1)
        val second = store(maxTracked = 2, maxPerUser = 1)
        val user = user()
        val a = first.issue(user, EMAIL, credentialRevision = 0)
        val b = first.issue(user(), EMAIL, credentialRevision = 0)
        assertFailsWith<MfaChallenges.CapacityExceededException> { second.issue(user(), EMAIL, credentialRevision = 0) }
        val replacement = second.issue(user, EMAIL, credentialRevision = 0)
        assertNotEquals(a.challengeId, replacement.challengeId)
        assertUnknown(second, a)
        assertIs<Outcome.Success>(second.verify(b.challengeId, b.code))
        assertIs<Outcome.Success>(first.verify(replacement.challengeId, replacement.code))
    }

    @Test
    fun `the oldest of several live challenges is replaced using issue order`() = runBlocking<Unit> {
        val first = store(maxPerUser = 2)
        val second = store(maxPerUser = 2)
        val user = user()
        val a = first.issue(user, EMAIL, credentialRevision = 0)
        val b = second.issue(user, EMAIL, credentialRevision = 0)
        val c = first.issue(user, EMAIL, credentialRevision = 0)
        assertUnknown(second, a)
        assertIs<Outcome.Success>(second.verify(b.challengeId, b.code))
        assertIs<Outcome.Success>(second.verify(c.challengeId, c.code))
    }

    @Test
    fun `expiry and delivery cleanup release capacity for other accounts`() = runBlocking<Unit> {
        val first = store(maxTracked = 1)
        val second = store(maxTracked = 1)
        val old = first.issue(user(), EMAIL, credentialRevision = 0)
        expire(old)
        val unsent = second.issue(user(), EMAIL, credentialRevision = 0)
        assertUnknown(first, old)
        first.discard("bad-format")
        first.discard(unsent.challengeId)
        val fresh = second.issue(user(), EMAIL, credentialRevision = 0)
        assertUnknown(second, unsent)
        assertIs<Outcome.Success>(first.verify(fresh.challengeId, fresh.code))
    }

    @Test
    fun `a lock can discard just the affected account's pending challenges from another instance`() = runBlocking<Unit> {
        val mine = user()
        val first = store()
        val second = store()
        val issued = List(3) { first.issue(mine, EMAIL, credentialRevision = 0) }
        val other = second.issue(user(), EMAIL, credentialRevision = 0)
        second.discardUser(mine)
        issued.forEach { assertUnknown(first, it) }
        assertIs<Outcome.Success>(first.verify(other.challengeId, other.code))
    }

    private suspend fun assertUnknown(challenges: MfaChallenges, issued: MfaChallenges.IssuedChallenge) {
        assertEquals("unknown_challenge", assertIs<Outcome.Failure>(challenges.verify(issued.challengeId, issued.code)).reason)
    }

    private fun expire(issued: MfaChallenges.IssuedChallenge) {
        PostgresTestSupport.connect().use { connection ->
            connection.prepareStatement("UPDATE mfa_challenges SET expires_at = 0 WHERE challenge_hash = ?").use { statement ->
                statement.setString(1, sha256Hex(issued.challengeId))
                assertEquals(1, statement.executeUpdate())
            }
        }
    }

    private companion object {
        const val EMAIL = "mfa@example.test"
    }
}
