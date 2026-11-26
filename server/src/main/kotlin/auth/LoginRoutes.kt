package ch.nokillswit.auth

import ch.nokillswit.audit.audit
import ch.nokillswit.authz.UnauthorizedException
import ch.nokillswit.infra.validation.canonicalEmail
import ch.nokillswit.users.Feature
import ch.nokillswit.users.MAX_EMAIL_LENGTH
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.application
import io.ktor.server.routing.post

/**
 * How a login line names the identity: the address of an account that EXISTS (`email`, which an operator needs to see who is
 * being guessed at), else the fixed-size, keyed `emailDigest` ([AuditDigest]). What was typed into the box of an identity with no
 * account can be anything, a password included (people do paste one there), so it never reaches a log line in the clear.
 */
private fun loginAuditIdentity(digest: AuditDigest, email: String, accountExists: Boolean): Pair<String, Any?> =
    if (accountExists) "email" to email else "emailDigest" to digest.of(email)

internal fun Route.login(deps: AuthDeps) {
    val jwtConfig = deps.jwtConfig
    val userService = deps.userService
    val loginThrottle = deps.loginThrottle
    val mailer = deps.mailer
    val mfaChallenges = deps.mfaChallenges
    val mfaTtlMinutes = deps.mfaTtlMinutes
    val auditDigest = application.auditDigest()
    post("/api/v1/login") {
        val req = call.receive<LoginRequest>()
        // Canonical identity: accounts are stored under the folded email, so the login
        // lookup folds the same way — a padded or case-variant submission matches its
        // account (and keeps sharing one lockout bucket).
        val email = canonicalEmail(req.email)
        // ONE fixed-shape existence query BEFORE the lock check, used only to choose how this request's lines name the identity (the
        // address of an account, the digest of a guess): the same single statement for a known and an unknown address, so the 429
        // refusals below it still say nothing about existence. The full lookup (the account and its features: one more query for a
        // known account) stays AFTER the check, as it was. An address longer than any account's cannot match one and is not probed.
        val exists = email.length <= MAX_EMAIL_LENGTH && userService.accountExists(email)
        val auditIdentity = loginAuditIdentity(auditDigest, email, accountExists = exists)
        val guess = AccountGuess(loginThrottle, email, listOf(auditIdentity))
        guess.requireNotLocked()
        val record = userService.findWithIdByEmail(email)
        // The unknown-email branch pays a full (discarded) bcrypt verify so its latency
        // matches the wrong-password branch — without it the fast 401 is a timing oracle
        // for account enumeration (the reset path equalizes the same way, via async work).
        val credentialsValid =
            if (record == null) {
                verifyPassword(req.password, TIMING_EQUALIZER_HASH)
                false
            } else {
                verifyPassword(req.password, record.second.passwordHash)
            }
        if (record == null || !credentialsValid) {
            guess.recordFailure()
            audit(
                "login.failure",
                auditIdentity,
                "reason" to if (record == null) "unknown_email" else "wrong_password",
            )
            throw UnauthorizedException("Unknown email or wrong password")
        }
        val (userId, user) = record
        // Email MFA (opt-in via the MFA feature flag, read straight off the DB record - no JWT exists yet): correct credentials
        // answer with a challenge, not tokens. The failure counter is NOT cleared here: the password is only the first factor, and
        // a caller holding it could otherwise log in afresh after every few wrong codes and never reach the lockout. The counter
        // clears when the code is accepted (auth/MfaRoutes.kt); until then a lock that tripped since the preflight still refuses.
        if (Feature.MFA !in user.disabledFeatures) {
            guess.requireNotLocked()
            issueMfaChallenge(call, mfaChallenges, mailer, mfaTtlMinutes, userId, user)
            return@post
        }
        guess.recordSuccess()
        audit("login.success", "email" to user.email, "userId" to userId.toLong())
        call.respond(HttpStatusCode.OK, jwtConfig.authResponse(userId, user))
    }
}
