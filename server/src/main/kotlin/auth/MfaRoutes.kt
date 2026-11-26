package ch.nokillswit.auth

import ch.nokillswit.audit.audit
import ch.nokillswit.authz.UnauthorizedException
import ch.nokillswit.plugins.JwtConfig
import ch.nokillswit.users.UserService
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post

/**
 * Second login step for MFA-enabled accounts: exchange the challenge id + the emailed code for the ordinary token pair. Not behind
 * `authenticate` - there is no token yet.
 *
 * Every wrong code counts into the per-ACCOUNT lockout ([AccountThrottle], the counter `/login` uses), so the guesses across
 * any number of fresh challenges add up and lock the account; the locking failure and every later one for the account also drop its
 * pending challenges, so no live code can finish a sign-in during a lock. A correct code clears the counter, exactly as a
 * password-only login does.
 */
internal fun Route.mfaVerify(
    jwtConfig: JwtConfig,
    userService: UserService,
    mfaChallenges: MfaChallenges,
    loginThrottle: PostgresAccountThrottle,
) {
    post("/api/v1/login/mfa") {
        val req = call.receive<MfaVerifyRequest>()
        when (val outcome = mfaChallenges.verify(req.challengeId, req.code)) {
            is MfaChallenges.Outcome.Failure -> {
                val subject = outcome.subject
                // The account is named whenever the challenge was found; an unknown id names nobody (no userId field at all).
                val named = subject?.let { arrayOf("userId" to it.userId.toLong()) } ?: emptyArray()
                if (outcome.wrongCode && subject != null) {
                    when (val failure = loginThrottle.recordMfaFailure(subject, checkNotNull(outcome.credentialRevision))) {
                        is MfaAttempt.Stale -> {
                            mfaChallenges.discard(req.challengeId)
                            audit(EVENT_LOGIN_MFA_FAILURE, "reason" to failure.auditReason, *named)
                        }
                        is MfaAttempt.Current -> {
                            audit(EVENT_LOGIN_MFA_FAILURE, "reason" to outcome.reason, *named)
                            mfaGuess(loginThrottle, mfaChallenges, subject).handleFailure(failure.outcome)
                        }
                    }
                } else {
                    audit(EVENT_LOGIN_MFA_FAILURE, "reason" to outcome.reason, *named)
                }
                // Uniform for every failure mode — a guesser learns nothing about
                // whether the challenge exists, expired, or the code was wrong.
                throw UnauthorizedException("Invalid or expired sign-in code")
            }
            is MfaChallenges.Outcome.Success -> {
                val userId = outcome.subject.userId
                // One read (the /refresh precedent): the user must still exist and be
                // active, and the pair is minted from their current roles/flags.
                val user = userService.read(userId)
                if (user == null) {
                    audit(EVENT_LOGIN_MFA_FAILURE, "reason" to "user_gone", "userId" to userId.toLong())
                    throw UnauthorizedException("Invalid or expired sign-in code")
                }
                when (val attempt = loginThrottle.recordMfaSuccess(outcome.subject, outcome.credentialRevision)) {
                    is MfaAttempt.Stale -> {
                        audit(EVENT_LOGIN_MFA_FAILURE, "reason" to attempt.auditReason, "userId" to userId.toLong())
                        throw UnauthorizedException("Invalid or expired sign-in code")
                    }
                    is MfaAttempt.Current -> mfaGuess(loginThrottle, mfaChallenges, outcome.subject).handleSuccess(attempt.outcome)
                }
                audit("login.mfa_success", "email" to user.email, "userId" to userId.toLong())
                call.respond(
                    HttpStatusCode.OK,
                    jwtConfig.authResponse(userId, user),
                )
            }
        }
    }
}

/** The MFA step's guess at [subject]'s account: a lock (now or already) also drops the account's pending challenges. */
private fun mfaGuess(throttle: AccountThrottle, challenges: MfaChallenges, subject: MfaChallenges.Subject) = AccountGuess(
    throttle,
    subject.email,
    listOf("userId" to subject.userId.toLong(), "source" to "mfa"),
    // The challenge was minted for a real account: a wrong code counts even at a full lockout store.
    knownUserId = subject.userId,
    // A lock a wrong code trips escalates (progressive for MFA only); the password step and the change keep the flat lock.
    mfaCode = true,
    onLocked = { challenges.discardUser(subject.userId) },
)
