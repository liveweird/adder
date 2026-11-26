package ch.nokillswit.auth

import ch.nokillswit.infra.crypto.DeploymentKeyedDigest
import ch.nokillswit.infra.validation.canonicalEmail
import ch.nokillswit.plugins.JwtConfigKey
import io.ktor.server.application.Application
import io.ktor.util.AttributeKey

/**
 * How an audit line names an address that has no account: a keyed digest, never the text. What is typed into the email box can be
 * anything, a password included, and an UNSALTED hash of a short or weak one is recoverable offline by trying candidates, so the digest
 * is an HMAC-SHA256 under a key only this deployment holds. Repeated attempts at one address still carry one digest (so an operator
 * can correlate a guessing run), and nothing outside the deployment can compute or test one.
 *
 * The key is DERIVED from the JWT secret, `HMAC-SHA256(jwtSecret, "adder.audit-digest.v1")`: that secret is the one every mode
 * already requires (production refuses a missing or public one at startup), so the digest needs no new setting and no key that could be
 * forgotten. The fixed label makes the key a different value from the secret itself, which signs tokens, so a digest is never a token
 * signature and a leaked digest key does not sign anything. Rotating the JWT secret changes every digest, which only breaks
 * correlation across the rotation (the tokens are invalidated by it too). Stored account-attempt state uses another derived HMAC
 * domain, and the in-memory policy kernel's `loginIdentityKey` is never logged.
 */
internal class AuditDigest(jwtSecret: String) {
    private val digest = DeploymentKeyedDigest(jwtSecret, DERIVATION_LABEL)

    /** The 64-character lowercase hex digest of [email], folded like the lockout key (trimmed, lower-cased). */
    fun of(email: String): String = digest.ofCanonical(canonicalEmail(email))

    private companion object {
        const val DERIVATION_LABEL = "adder.audit-digest.v1"

    }
}

private val AuditDigestKey = AttributeKey<AuditDigest>("AuditDigest")

/** The application's [AuditDigest], derived once from the JWT secret that `configureSecurity` published. */
internal fun Application.auditDigest(): AuditDigest =
    attributes.computeIfAbsent(AuditDigestKey) { AuditDigest(attributes[JwtConfigKey].secret) }
