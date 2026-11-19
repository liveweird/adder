package ch.nokillswit.authz

import io.ktor.server.application.ApplicationCall
import io.ktor.util.AttributeKey

/**
 * The facts of the access token that authenticated a call, which [CallerPrincipal] does not carry: its `jti`, its expiry and the
 * credential revision it was minted under. `plugins/Security.kt` stashes one on the call only after the per-request account check
 * answered LIVE; the live-update ticket (`live/LiveRoutes.kt`) binds these so the socket can be re-judged and closed at the token's
 * own `exp`. [expiresAtMillis] is always present: `validate` refuses a token without an `exp` (`access.rejected` `malformed`).
 * The `jti` is a blocklist key, not a credential, and cannot authenticate anything.
 */
data class AccessToken(val jti: String, val expiresAtMillis: Long, val credentialRevision: Long)

val AccessTokenKey = AttributeKey<AccessToken>("AccessToken")

/** The access token of this authenticated call; a call that never passed the JWT check (no principal) is a 401. */
fun ApplicationCall.accessToken(): AccessToken = attributes.getOrNull(AccessTokenKey) ?: throw UnauthorizedException()
