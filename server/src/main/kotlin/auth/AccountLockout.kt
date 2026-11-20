package ch.nokillswit.auth

import ch.nokillswit.audit.audit
import ch.nokillswit.authz.TooManyRequestsException
import ch.nokillswit.infra.config.requireConfigInt
import ch.nokillswit.infra.config.requireConfigLong
import io.ktor.server.application.Application
import io.ktor.util.AttributeKey

/**
 * The ONE per-account failure counter, shared by every route that verifies a credential for an account: `/login` (the
 * password), `/login/mfa` (the emailed code) and the self password change (`currentPassword`). N wrong guesses across all
 * three lock the account exactly as N failed logins would. Published as an attribute (not built inside `configureAuthRoutes`)
 * because `configureUserRoutes` registers earlier in `application.yaml` than the auth routes and reads it per request.
 */
val LoginThrottleKey = AttributeKey<LoginThrottle>("LoginThrottle")

/** Module `configureLoginThrottle` (application.yaml, before the feature routes): builds the throttle from `security.lockout.*`. */
fun Application.configureLoginThrottle() {
    // Boot-validated through the shared requireConfigInt/requireConfigLong: a malformed or out-of-range value is a config error,
    // e.g. LOGIN_LOCKOUT_THRESHOLD=0 would lock out every account on its first attempt.
    attributes.put(
        LoginThrottleKey,
        LoginThrottle(
            threshold = requireConfigInt(environment.config, "security.lockout.threshold", min = 1),
            // Bounded above (30 days) as well as below: an unbounded value survives the *1000 millis conversion right up until it
            // doesn't - a durationSeconds near Long.MAX_VALUE overflows into a negative millis figure, which would silently
            // DISABLE the lockout instead of refusing to start.
            lockoutMillis = requireConfigLong(
                environment.config, "security.lockout.durationSeconds", min = 1, max = MAX_DURATION_SECONDS,
            ) * 1000,
            maxTracked = requireConfigInt(environment.config, "security.lockout.maxTracked", min = 1, max = MAX_TRACKED_IDENTITIES),
        ),
    )
}

/** The 429 every locked-account refusal answers (login, MFA step, password change), so the detail never forks. */
internal fun accountLockedException() =
    TooManyRequestsException("Too many failed login attempts for this account — try again later")

/** The 429 a full lockout store answers for an identity it does not track yet. */
internal fun lockoutCapacityException() =
    TooManyRequestsException("Too many sign-in identities are being tracked — try again later")

/**
 * One credential check of one account against the shared [LoginThrottle]: what a route does around its password / code
 * verification. The three callers (`/login`, `/login/mfa`, the self password change) differ only in their audit identity, the
 * event for a refused locked account, whether a full store refuses ([refuseAtCapacity]) and what a lock must also do
 * ([onLocked], e.g. drop the account's pending MFA challenges). Every refusal is a THROWN 429, never `respondProblem`:
 * StatusPages' generic 429 handler would otherwise replace the specific detail.
 */
internal class AccountGuess(
    private val throttle: LoginThrottle,
    private val email: String,
    private val auditFields: List<Pair<String, Any?>>,
    private val lockedEvent: String = EVENT_LOGIN_REJECTED_LOCKED,
    /**
     * False for a caller that is already signed in (the password change): a full store must not refuse every signed-in user's
     * change (an anonymous caller can fill it), so an untracked identity is let through UNCOUNTED, still audited as
     * `login.capacity_rejected`; the caller's own per-user bucket bounds those guesses.
     */
    private val refuseAtCapacity: Boolean = true,
    private val onLocked: () -> Unit = {},
) {
    private fun audited(event: String) = audit(event, *auditFields.toTypedArray())

    /** BEFORE any password work: a locked identity (and, if [refuseAtCapacity], an untracked one at a full store) is refused. */
    fun requireNotLocked() {
        when (throttle.preflight(email)) {
            LoginThrottle.Preflight.ALLOWED -> Unit
            LoginThrottle.Preflight.LOCKED -> {
                audited(lockedEvent)
                throw accountLockedException()
            }
            LoginThrottle.Preflight.CAPACITY_EXCEEDED -> {
                audited(EVENT_LOGIN_CAPACITY_REJECTED)
                if (refuseAtCapacity) throw lockoutCapacityException()
            }
        }
    }

    /**
     * Counts one wrong guess and answers what `/login` answers to a failure: nothing on a plain or a locking failure (the caller
     * then sends its own failure; the locking one also writes `login.lockout`), a 429 on an already-active lock (the race the
     * preflight cannot see) or a full store.
     */
    fun recordFailure() {
        when (throttle.recordFailure(email)) {
            LoginThrottle.FailureResult.CAPACITY_EXCEEDED -> {
                audited(EVENT_LOGIN_CAPACITY_REJECTED)
                if (refuseAtCapacity) throw lockoutCapacityException()
            }
            LoginThrottle.FailureResult.ALREADY_LOCKED -> {
                onLocked()
                audited(lockedEvent)
                throw accountLockedException()
            }
            LoginThrottle.FailureResult.LOCKED_NOW -> {
                onLocked()
                audited(EVENT_LOGIN_LOCKOUT)
            }
            LoginThrottle.FailureResult.RECORDED -> Unit
        }
    }

    /** A verified credential clears the counter, unless a lock tripped since the preflight: that still refuses, as at login. */
    fun recordSuccess() {
        if (throttle.recordSuccess(email) == LoginThrottle.SuccessResult.LOCKED) {
            onLocked()
            audited(lockedEvent)
            throw accountLockedException()
        }
    }
}
