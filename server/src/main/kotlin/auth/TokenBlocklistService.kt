package ch.nokillswit.auth

import ch.nokillswit.infra.db.nowMillis
import ch.nokillswit.users.UserRole
import ch.nokillswit.users.UserService.Users
import ch.nokillswit.users.asAdditionalRoles
import io.ktor.util.AttributeKey
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.r2dbc.*
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

val TokenBlocklistServiceKey = AttributeKey<TokenBlocklistService>("TokenBlocklistService")

/**
 * The default for how long after a refresh token was rotated a replay of it is still answered with a
 * fresh pair (the lost-response grace, `security.refresh.replayGraceSeconds`). It must outlast the SPA's
 * 30 s request deadline: a `/refresh` response that never arrived leaves the client holding the old
 * token, and its retry has to work. 0 turns the grace off (strict one-time use).
 */
internal const val REFRESH_REPLAY_GRACE_MS = 60_000L

/** What `/refresh` may do with the presented token once it has tried to claim it. */
enum class RefreshClaim {
    /** This call blocklisted the token: mint a pair. */
    CLAIMED,

    /** Rotated less than the grace window ago by this instance, at most [MAX_GRACE_REUSES] times: mint a pair again. */
    GRACE_REPLAY,

    /** Rotated longer ago, or the grace's re-mints are used up (and still remembered): a replay. */
    REPLAYED,

    /** Blocklisted by something else, or the rotation is not remembered (logout, restart, full record): revoked. */
    REVOKED,
}

/**
 * What the per-request check of an access token ([TokenBlocklistService.accessVerdict]) found. Anything but [LIVE]
 * is a `401`; the lower-case name is the `reason` of the `access.rejected` audit event.
 */
enum class AccessVerdict {
    /** The account exists, the token is not blocklisted and its revision and roles are the stored ones. */
    LIVE,

    /** The token's `jti` is on the blocklist (logout). */
    REVOKED,

    /** The account was soft-deleted (or never existed). */
    USER_GONE,

    /** The password changed since the token was issued (`users.credential_revision` moved). */
    CREDENTIAL_CHANGED,

    /** The account's role was changed since the token was issued. */
    ROLES_CHANGED,
}

class TokenBlocklistService(
    private val database: R2dbcDatabase,
    private val clock: () -> Long = ::nowMillis,
    private val replayGraceMillis: Long = REFRESH_REPLAY_GRACE_MS,
) {
    object RevokedTokens : Table("revoked_tokens") {
        val jti = varchar("jti", 36)
        val expiresAt = long("expires_at")
        override val primaryKey = PrimaryKey(jti)
    }

    // Which refresh jtis this instance rotated (auth/RefreshRotations.kt): drives the replay grace and
    // tells a replay from a logout revocation. In memory, per instance, bounded; a restart loses it.
    private val rotations = RefreshRotations(clock, replayGraceMillis)

    /** True while [jti] was rotated by this instance less than the grace window ago with a re-mint left. */
    fun inReplayGrace(jti: String): Boolean = rotations.inGrace(jti)

    /** True when this instance remembers rotating [jti] (inside or outside the grace). */
    fun wasRotated(jti: String): Boolean = rotations.remembers(jti)

    /** Logout ends the grace of every refresh token this instance rotated for [userId]: a later replay is `revoked`. */
    fun forgetRotations(userId: Long) = rotations.forget(userId)

    /**
     * The rotation step of `/refresh`: the atomic claim of [jti] (see [insertRevocation]), then the verdict.
     * Only the call that inserted the row is [RefreshClaim.CLAIMED]; the rotation is recorded inside the
     * claim's transaction, before its commit, so a racing loser is deterministically answered by the grace.
     * A replay is [RefreshClaim.GRACE_REPLAY] inside the grace and [RefreshClaim.REPLAYED]/[RefreshClaim.REVOKED] beyond it.
     */
    suspend fun claimRefresh(jti: String, expiresAtEpochMillis: Long, userId: Long): RefreshClaim =
        if (insertRevocation(jti, expiresAtEpochMillis) { rotations.record(jti, userId, expiresAtEpochMillis) }) {
            RefreshClaim.CLAIMED
        } else {
            rotations.replay(jti)
        }

    suspend fun revoke(jti: String, expiresAtEpochMillis: Long) {
        revokeIfAbsent(jti, expiresAtEpochMillis)
    }

    /**
     * Blocklists [jti] and reports whether THIS call inserted the row. The insert is one
     * `INSERT .. ON CONFLICT DO NOTHING` on the jti primary key, so of any number of concurrent
     * callers with the same jti exactly one gets `true` — the atomic "check not revoked + revoke"
     * that refresh-token rotation needs (`/refresh` treats `false` as a replay). A `false`
     * is the replay signal: the row exists, whoever wrote it.
     */
    suspend fun revokeIfAbsent(jti: String, expiresAtEpochMillis: Long): Boolean =
        insertRevocation(jti, expiresAtEpochMillis) {}

    // [onInserted] runs inside the transaction, right after this call's insert took the row and before the commit.
    private suspend fun insertRevocation(jti: String, expiresAtEpochMillis: Long, onInserted: () -> Unit): Boolean =
        suspendTransaction(database) {
            RevokedTokens.deleteWhere { expiresAt less nowMillis() }
            val inserted = RevokedTokens.insertIgnore {
                it[RevokedTokens.jti] = jti
                it[RevokedTokens.expiresAt] = expiresAtEpochMillis
            }.insertedCount > 0
            if (inserted) onInserted()
            inserted
        }

    /** Whether [jti] is on the blocklist: one plain select, never cached (`/refresh`'s fast path; requests use [accessVerdict]). */
    suspend fun isRevoked(jti: String): Boolean = suspendTransaction(database) {
        RevokedTokens.select(RevokedTokens.jti).where { RevokedTokens.jti eq jti }.firstOrNull() != null
    }

    /**
     * The access-token check behind EVERY authenticated request (`plugins/Security.kt`): ONE uncached statement over
     * the token's user row (ported from Lettuce's `isSessionLive`, extended) that reads the stored role, credential
     * revision and soft-delete flag plus whether [jti] is blocklisted. Nothing is cached, so a logout, a delete, a
     * demotion or a password change ends the token on its very next request on any replica. The order of the findings:
     * no row or deleted -> [AccessVerdict.USER_GONE], blocklisted -> [AccessVerdict.REVOKED], another
     * [credentialRevision] -> [AccessVerdict.CREDENTIAL_CHANGED], other [roles] (the token's additional-roles claim
     * against the stored role) -> [AccessVerdict.ROLES_CHANGED].
     */
    suspend fun accessVerdict(jti: String, userId: UInt, credentialRevision: Long, roles: Set<String>): AccessVerdict =
        suspendTransaction(database) {
            val revoked = exists(RevokedTokens.select(RevokedTokens.jti).where { RevokedTokens.jti eq jti }).alias("revoked")
            val row = Users.select(Users.role, Users.credentialRevision, Users.markedAsDeleted, revoked)
                .where { Users.id eq userId }
                .firstOrNull()
            decideAccess(
                row?.let { StoredAccount(it[Users.role], it[Users.credentialRevision], it[Users.markedAsDeleted]) },
                row?.get(revoked) ?: false,
                credentialRevision,
                roles,
            )
        }

    /**
     * [accessVerdict] for many tokens at once (the live channel's account sweep): the SAME decision function, but per chunk of
     * [ACCESS_VERDICT_CHUNK] checks ONE `users` read and ONE `revoked_tokens` read instead of one statement each. The answer is in the
     * order of [checks]. Like [accessVerdict] it is never cached.
     */
    suspend fun accessVerdicts(checks: List<AccessCheck>): List<AccessVerdict> =
        checks.chunked(ACCESS_VERDICT_CHUNK).flatMap { chunk ->
            suspendTransaction(database) {
                val accounts = Users.select(Users.id, Users.role, Users.credentialRevision, Users.markedAsDeleted)
                    .where { Users.id inList chunk.map { it.userId }.toSet() }
                    .map { it[Users.id].value to StoredAccount(it[Users.role], it[Users.credentialRevision], it[Users.markedAsDeleted]) }
                    .toList().toMap()
                val revoked = RevokedTokens.select(RevokedTokens.jti)
                    .where { RevokedTokens.jti inList chunk.map { it.jti }.toSet() }
                    .map { it[RevokedTokens.jti] }
                    .toList().toSet()
                chunk.map { decideAccess(accounts[it.userId], it.jti in revoked, it.credentialRevision, it.roles) }
            }
        }
}

/** Checks per statement pair of [TokenBlocklistService.accessVerdicts]: well under PostgreSQL's bind-parameter limit. */
internal const val ACCESS_VERDICT_CHUNK = 1000

/** One token whose standing [TokenBlocklistService.accessVerdicts] judges: the arguments of [TokenBlocklistService.accessVerdict]. */
class AccessCheck(val jti: String, val userId: UInt, val credentialRevision: Long, val roles: Set<String>)

/** The stored facts about an account that decide a token's standing. */
private class StoredAccount(val role: String, val credentialRevision: Long, val deleted: Boolean)

/**
 * The ONE decision behind [TokenBlocklistService.accessVerdict] and [TokenBlocklistService.accessVerdicts] (so the per-request check and
 * the live sweep cannot drift): no account or a deleted one -> [AccessVerdict.USER_GONE], blocklisted -> [AccessVerdict.REVOKED], another
 * credential revision -> [AccessVerdict.CREDENTIAL_CHANGED], other roles -> [AccessVerdict.ROLES_CHANGED].
 */
private fun decideAccess(account: StoredAccount?, revoked: Boolean, credentialRevision: Long, roles: Set<String>): AccessVerdict = when {
    account == null || account.deleted -> AccessVerdict.USER_GONE
    revoked -> AccessVerdict.REVOKED
    account.credentialRevision != credentialRevision -> AccessVerdict.CREDENTIAL_CHANGED
    account.role.storedRoleNames() != roles -> AccessVerdict.ROLES_CHANGED
    else -> AccessVerdict.LIVE
}

// The stored role as the token's `roles` claim carries it: additional roles only (empty for the baseline).
private fun String.storedRoleNames(): Set<String> = UserRole.valueOf(this).asAdditionalRoles().map { it.name }.toSet()
