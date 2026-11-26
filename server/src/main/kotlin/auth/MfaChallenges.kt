package ch.nokillswit.auth

import ch.nokillswit.infra.crypto.FieldCipher
import ch.nokillswit.infra.json.sha256Hex
import ch.nokillswit.infra.maskedSecret
import ch.nokillswit.users.UserService.Users
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.HexFormat
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.core.statements.StatementType
import org.jetbrains.exposed.v1.core.vendors.ForUpdateOption
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.update

private object MfaChallengeCapacity : Table("mfa_challenge_capacity_guard") {
    val singleton = bool("singleton")
    override val primaryKey = PrimaryKey(singleton)
}

private object MfaChallengeRows : LongIdTable("mfa_challenges", "issue_order") {
    val challengeHash = varchar("challenge_hash", 64)
    val userId = reference("user_id", Users)
    val encryptedEmail = text("encrypted_email")
    val encryptedCode = text("encrypted_code")
    val credentialRevision = long("credential_revision")
    val expiresAt = long("expires_at")
    val attempts = integer("attempts").default(0)
}

/**
 * One shared pending email-MFA store. A row lock makes each code single-use and counts guesses across all instances.
 * Only a digest of the secret challenge id and encrypted code and identity are persisted.
 */
class MfaChallenges(
    private val database: R2dbcDatabase,
    private val cipher: FieldCipher,
    private val ttlMillis: Long,
    private val maxCodeAttempts: Int,
    private val maxTracked: Int = 10_000,
    private val maxPerUser: Int = DEFAULT_MAX_PER_USER,
) {
    init {
        require(maxTracked > 0) { "maxTracked must be positive" }
        require(maxPerUser > 0) { "maxPerUser must be positive" }
    }

    class CapacityExceededException : RuntimeException()

    data class IssuedChallenge(val challengeId: String, val code: String, val expiresAt: Long) {
        override fun toString(): String =
            "IssuedChallenge(challengeId=${maskedSecret(challengeId)}, code=${maskedSecret(code)}, expiresAt=$expiresAt)"
    }

    /** The account and lockout identity the password step verified. */
    data class Subject(val userId: UInt, val email: String)

    sealed interface Outcome {
        data class Success(val subject: Subject, val credentialRevision: Long) : Outcome

        /** Reasons are audit-only. A wrong code counts toward account lockout while its credential revision is current. */
        data class Failure(
            val reason: String,
            val subject: Subject? = null,
            val wrongCode: Boolean = false,
            val credentialRevision: Long? = null,
        ) : Outcome
    }

    /** A new challenge replaces this account's oldest active challenge if its own slots are full. */
    suspend fun issue(userId: UInt, email: String, credentialRevision: Long): IssuedChallenge {
        val id = HexFormat.of().formatHex(ByteArray(CHALLENGE_ID_BYTES).also(random::nextBytes))
        val hash = sha256Hex(id)
        val code = generateMfaCode()
        val encryptedEmail = cipher.encrypt(email)
        val encryptedCode = cipher.encrypt(code)
        val deadline = suspendTransaction(database) {
            // The separate lock statement lets a waiter count from a snapshot that sees the preceding mint's commit.
            lockCapacity()
            val now = databaseNow()
            pruneExpired(now)
            val rows = MfaChallengeRows
            val active = rows.expiresAt greater now
            val own = rows.select(rows.id).where { (rows.userId eq userId) and active }
                .orderBy(rows.id).limit(maxPerUser).map { it[rows.id].value }.toList()
            if (own.size == maxPerUser) {
                rows.deleteWhere { rows.id eq own.first() }
            } else if (rows.select(rows.id).where { active }.count() >= maxTracked) {
                return@suspendTransaction null
            }
            val expiresAt = now + ttlMillis
            rows.insert {
                it[challengeHash] = hash
                it[rows.userId] = userId
                it[rows.encryptedEmail] = encryptedEmail
                it[rows.encryptedCode] = encryptedCode
                it[rows.credentialRevision] = credentialRevision
                it[rows.expiresAt] = expiresAt
            }
            expiresAt
        } ?: throw CapacityExceededException()
        return IssuedChallenge(id, code, deadline)
    }

    suspend fun verify(challengeId: String, code: String): Outcome {
        if (!challengeIdPattern.matches(challengeId)) return Outcome.Failure("unknown_challenge")
        return suspendTransaction(database) {
            val rows = MfaChallengeRows
            val hash = sha256Hex(challengeId)
            val row = rows.selectAll().where { rows.challengeHash eq hash }
                .forUpdate(ForUpdateOption.PostgreSQL.ForUpdate()).firstOrNull()
                ?: return@suspendTransaction Outcome.Failure("unknown_challenge")
            val id = row[rows.id].value
            val subject = Subject(row[rows.userId].value, cipher.decrypt(row[rows.encryptedEmail]))
            val revision = row[rows.credentialRevision]
            // Read the clock after locking the row, so a transaction that waited cannot accept an expired code.
            if (row[rows.expiresAt] <= databaseNow()) {
                rows.deleteWhere { rows.id eq id }
                return@suspendTransaction Outcome.Failure("expired", subject)
            }
            if (MessageDigest.isEqual(cipher.decrypt(row[rows.encryptedCode]).toByteArray(), code.toByteArray())) {
                rows.deleteWhere { rows.id eq id }
                return@suspendTransaction Outcome.Success(subject, revision)
            }
            val attempts = row[rows.attempts] + 1
            val reason = if (attempts >= maxCodeAttempts) {
                rows.deleteWhere { rows.id eq id }
                "too_many_attempts"
            } else {
                rows.update({ rows.id eq id }) { it[rows.attempts] = attempts }
                "wrong_code"
            }
            Outcome.Failure(reason, subject, wrongCode = true, credentialRevision = revision)
        }
    }

    /** Serialize account-wide removal with mints. Verification takes only a challenge row lock. */
    suspend fun discardUser(userId: UInt) {
        suspendTransaction(database) {
            lockCapacity()
            MfaChallengeRows.deleteWhere { MfaChallengeRows.userId eq userId }
        }
    }

    /** Drops an undelivered or stale challenge by id. */
    suspend fun discard(challengeId: String) {
        if (!challengeIdPattern.matches(challengeId)) return
        suspendTransaction(database) {
            MfaChallengeRows.deleteWhere { challengeHash eq sha256Hex(challengeId) }
        }
    }

    private suspend fun lockCapacity() {
        val guard = MfaChallengeCapacity
        check(guard.select(guard.singleton).where { guard.singleton eq true }
            .forUpdate(ForUpdateOption.PostgreSQL.ForUpdate()).firstOrNull() != null) { "MFA capacity guard is missing" }
    }

    private suspend fun pruneExpired(now: Long) {
        val rows = MfaChallengeRows
        val expired = rows.select(rows.id).where { rows.expiresAt lessEq now }
            .orderBy(rows.expiresAt to SortOrder.ASC, rows.id to SortOrder.ASC).limit(EXPIRY_CLEANUP_BATCH)
            .forUpdate(ForUpdateOption.PostgreSQL.ForUpdate(ForUpdateOption.PostgreSQL.MODE.SKIP_LOCKED))
            .map { it[rows.id].value }.toList()
        if (expired.isNotEmpty()) rows.deleteWhere { rows.id inList expired }
    }

    private suspend fun databaseNow(): Long = checkNotNull(
        TransactionManager.current().exec(DATABASE_NOW, explicitStatementType = StatementType.SELECT) { row ->
            row.get(0, Long::class.javaObjectType)!!
        }?.firstOrNull(),
    )

    companion object {
        /** Holding a second code makes it possible to complete sign-in while a retry is in flight. */
        const val DEFAULT_MAX_PER_USER = 3
        private const val CHALLENGE_ID_BYTES = 16
        private const val EXPIRY_CLEANUP_BATCH = 256
        private const val DATABASE_NOW = "SELECT floor(extract(epoch FROM clock_timestamp()) * 1000)::bigint"
        private val random = SecureRandom()
        private val challengeIdPattern = Regex("[0-9a-f]{32}")
    }
}
