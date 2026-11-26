package ch.nokillswit.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LoginAttemptPolicyTest {
    @Test
    fun `persisted counters at the integer boundary saturate and lock instead of wrapping negative`() {
        val now = 1_000L
        val transition = LoginAttemptPolicy(
            threshold = 5,
            lockoutMillis = 100,
            mfaMaxLockoutMillis = 1_000,
            mfaDecayMillis = 1_000,
        ).failure(
            LoginAttemptState(
                failures = Int.MAX_VALUE,
                stateExpiresAt = now + 1,
                codeFailed = true,
                codeGuesses = Int.MAX_VALUE,
                codeExpiresAt = now + 1,
            ),
            now,
            mfaCode = true,
        )

        assertEquals(LoginThrottle.FailureResult.LOCKED_NOW, transition.outcome.result)
        assertTrue(transition.state.failures >= 0)
        assertTrue(transition.state.codeGuesses >= 0)
    }
}
