package ch.nokillswit.notifications

import ch.nokillswit.authz.UnauthorizedException
import ch.nokillswit.infra.crypto.FieldCipher
import ch.nokillswit.infra.db.active
import ch.nokillswit.infra.db.nowMillis
import ch.nokillswit.users.UserService
import io.ktor.server.config.ApplicationConfig
import io.ktor.server.plugins.BadRequestException
import io.ktor.util.AttributeKey
import java.net.URI
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.dao.id.UIntIdTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.vendors.ForUpdateOption
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.update

internal val BrowserPushServiceKey = AttributeKey<BrowserPushService>("BrowserPushService")
internal const val MAX_PUSH_SUBSCRIPTIONS_PER_USER = 5
internal const val MAX_PUSH_ENDPOINT_LENGTH = 2_048

internal class BrowserPushConfig(
    val publicKey: String,
    val publicKeyObject: ECPublicKey,
    val privateKeyObject: ECPrivateKey,
    val subject: String,
)

internal fun ApplicationConfig.browserPushConfig(): BrowserPushConfig? {
    val publicEncoded = propertyOrNull("notifications.push.publicKey")?.getString()?.trim().orEmpty()
    val privateEncoded = propertyOrNull("notifications.push.privateKey")?.getString()?.trim().orEmpty()
    val subject = propertyOrNull("notifications.push.subject")?.getString()?.trim().orEmpty()
    if (publicEncoded.isEmpty() && privateEncoded.isEmpty() && subject.isEmpty()) return null
    check(publicEncoded.isNotEmpty() && privateEncoded.isNotEmpty() && subject.isNotEmpty()) {
        "Browser push requires NOTIFICATIONS_PUSH_PUBLIC_KEY, NOTIFICATIONS_PUSH_PRIVATE_KEY and NOTIFICATIONS_PUSH_SUBJECT together"
    }
    val subjectUri = runCatching { URI(subject) }.getOrNull()
    check(subjectUri?.scheme in setOf("mailto", "https") && subjectUri?.isAbsolute == true && subjectUri.fragment == null) {
        "NOTIFICATIONS_PUSH_SUBJECT must be an absolute mailto: or https: URI without a fragment"
    }
    val decoder = Base64.getUrlDecoder()
    val publicBytes = runCatching { decoder.decode(publicEncoded) }
        .getOrElse { error("NOTIFICATIONS_PUSH_PUBLIC_KEY must be unpadded base64url") }
    check(publicBytes.size == 65 && publicBytes[0] == 4.toByte()) {
        "NOTIFICATIONS_PUSH_PUBLIC_KEY must encode a 65-byte uncompressed P-256 point"
    }
    val parameters = AlgorithmParameters.getInstance("EC").apply { init(ECGenParameterSpec("secp256r1")) }
        .getParameterSpec(java.security.spec.ECParameterSpec::class.java)
    fun coordinate(offset: Int) = java.math.BigInteger(1, publicBytes.copyOfRange(offset, offset + 32))
    val publicKey = KeyFactory.getInstance("EC").generatePublic(
        ECPublicKeySpec(ECPoint(coordinate(1), coordinate(33)), parameters),
    ) as ECPublicKey
    val privateBytes = runCatching { decoder.decode(privateEncoded) }
        .getOrElse { error("NOTIFICATIONS_PUSH_PRIVATE_KEY must be unpadded base64url") }
    val privateKey = runCatching {
        KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(privateBytes)) as ECPrivateKey
    }.getOrElse { error("NOTIFICATIONS_PUSH_PRIVATE_KEY must encode a PKCS#8 P-256 EC private key") }
    check(privateKey.params.curve == parameters.curve) { "NOTIFICATIONS_PUSH_PRIVATE_KEY must use P-256" }
    val proof = "adder-vapid-key-pair".toByteArray()
    val signature = Signature.getInstance("SHA256withECDSA").run {
        initSign(privateKey)
        update(proof)
        sign()
    }
    check(Signature.getInstance("SHA256withECDSA").run {
        initVerify(publicKey)
        update(proof)
        verify(signature)
    }) { "NOTIFICATIONS_PUSH_PUBLIC_KEY does not match NOTIFICATIONS_PUSH_PRIVATE_KEY" }
    return BrowserPushConfig(publicEncoded, publicKey, privateKey, subject)
}

internal class ClaimedPush(val subscriptionId: UInt, val notificationIds: List<UInt>)

internal class BrowserPushService(
    private val database: R2dbcDatabase,
    private val cipher: FieldCipher,
    val config: BrowserPushConfig?,
) {
    object Subscriptions : UIntIdTable("browser_push_subscriptions") {
        val userId = uinteger("user_id")
        val endpointHash = binary("endpoint_hash")
        val endpointEncrypted = text("endpoint_encrypted")
        val credentialRevision = long("credential_revision")
        val createdAt = long("created_at")
    }

    object Queue : Table("notification_push_queue") {
        val notificationId = uinteger("notification_id")
        val subscriptionId = uinteger("subscription_id")
        override val primaryKey = PrimaryKey(notificationId, subscriptionId)
    }

    suspend fun subscribe(userId: UInt, credentialRevision: Long, rawEndpoint: String): UInt {
        check(config != null) { "Browser push is unavailable" }
        val endpoint = validatePushEndpoint(rawEndpoint)
        val digest = MessageDigest.getInstance("SHA-256").digest(endpoint.toByteArray(Charsets.UTF_8))
        return suspendTransaction(database) {
            val activeRevision = UserService.Users.select(UserService.Users.credentialRevision)
                .where { (UserService.Users.id eq userId) and UserService.Users.active() }
                .forUpdate(ForUpdateOption.PostgreSQL.ForUpdate())
                .toList().singleOrNull()?.get(UserService.Users.credentialRevision)
            if (activeRevision != credentialRevision) throw UnauthorizedException()
            // A missing row cannot be locked. Serialize the digest itself so two users registering the same new endpoint cannot both
            // pass the lookup and race the global unique constraint. The later serialized registration owns the browser endpoint.
            exec(
                "SELECT pg_advisory_xact_lock(CAST(? AS int8))",
                listOf(org.jetbrains.exposed.v1.core.LongColumnType() to digest.advisoryLockKey()),
            ) { Unit }?.toList()
            val existing = Subscriptions.select(Subscriptions.id, Subscriptions.userId)
                .where { Subscriptions.endpointHash eq digest }
                .forUpdate(ForUpdateOption.PostgreSQL.ForUpdate())
                .toList().singleOrNull()
            val existingId = existing?.get(Subscriptions.id)?.value
            if (existingId != null && existing[Subscriptions.userId] == userId) {
                Subscriptions.update({ Subscriptions.id eq existingId }) {
                    it[endpointEncrypted] = cipher.encrypt(endpoint)
                    it[Subscriptions.credentialRevision] = credentialRevision
                    it[createdAt] = nowMillis()
                }
                return@suspendTransaction existingId
            }
            val count = Subscriptions.select(Subscriptions.id).where { Subscriptions.userId eq userId }
                .limit(MAX_PUSH_SUBSCRIPTIONS_PER_USER).count()
            if (count >= MAX_PUSH_SUBSCRIPTIONS_PER_USER) throw BadRequestException("At most 5 browsers can receive push notifications")
            if (existingId != null) Subscriptions.deleteWhere { Subscriptions.id eq existingId }
            Subscriptions.insert {
                it[Subscriptions.userId] = userId
                it[endpointHash] = digest
                it[endpointEncrypted] = cipher.encrypt(endpoint)
                it[Subscriptions.credentialRevision] = credentialRevision
                it[createdAt] = nowMillis()
            }[Subscriptions.id].value
        }
    }

    suspend fun unsubscribe(userId: UInt, id: UInt) {
        suspendTransaction(database) {
            Subscriptions.deleteWhere { (Subscriptions.id eq id) and (Subscriptions.userId eq userId) }
        }
    }

    suspend fun isRegistered(userId: UInt, credentialRevision: Long, id: UInt, createdAfter: Long): Boolean =
        suspendTransaction(database) {
            exec(
                REGISTERED_PUSH_SQL,
                listOf(
                    org.jetbrains.exposed.v1.core.IntegerColumnType() to id.toInt(),
                    org.jetbrains.exposed.v1.core.IntegerColumnType() to userId.toInt(),
                    org.jetbrains.exposed.v1.core.LongColumnType() to credentialRevision,
                    org.jetbrains.exposed.v1.core.LongColumnType() to createdAfter,
                ),
            ) { result -> result.get(0, Boolean::class.javaObjectType) == true }
                ?.toList()?.singleOrNull() == true
        }

    suspend fun removeExpired(id: UInt) {
        suspendTransaction(database) { Subscriptions.deleteWhere { Subscriptions.id eq id } }
    }

    suspend fun claim(limit: Int, createdAfter: Long): List<ClaimedPush> = suspendTransaction(database) {
        val rows = exec(
            CLAIM_PUSH_SQL,
            listOf(
                org.jetbrains.exposed.v1.core.IntegerColumnType() to limit,
                org.jetbrains.exposed.v1.core.LongColumnType() to createdAfter,
            ),
        ) { row ->
            row.get(0, Int::class.javaObjectType)!!.toUInt() to row.get(1, Int::class.javaObjectType)!!.toUInt()
        }?.toList().orEmpty().filterNotNull()
        rows.groupBy({ it.first }, { it.second }).map { (subscriptionId, notificationIds) ->
            ClaimedPush(subscriptionId, notificationIds.distinct())
        }
    }

    /** Rechecks every mutable verdict and decrypts only immediately before outbound I/O. */
    suspend fun eligibleEndpoint(push: ClaimedPush): String? = suspendTransaction(database) {
        val row = exec(
            ELIGIBLE_PUSH_SQL,
            listOf(
                org.jetbrains.exposed.v1.core.IntegerColumnType() to push.subscriptionId.toInt(),
                org.jetbrains.exposed.v1.core.ArrayColumnType<Int, List<Int>>(org.jetbrains.exposed.v1.core.IntegerColumnType()) to
                    push.notificationIds.map(UInt::toInt),
            ),
        ) { result -> result.get(0, String::class.java)!! }?.toList().orEmpty().filterNotNull().singleOrNull()
        row?.let { encrypted -> runCatching { cipher.decrypt(encrypted) }.getOrNull() }
    }

    suspend fun purgeStale(cutoffMillis: Long): Int = suspendTransaction(database) {
        Subscriptions.deleteWhere { Subscriptions.createdAt less cutoffMillis }
    }
}

private fun ByteArray.advisoryLockKey(): Long = take(8).fold(0L) { value, byte -> (value shl 8) or (byte.toLong() and 0xff) }

internal fun validatePushEndpoint(raw: String): String {
    val value = raw.trim()
    if (value.isEmpty() || value.length > MAX_PUSH_ENDPOINT_LENGTH) throw BadRequestException("Invalid push endpoint")
    val uri = runCatching { URI(value) }.getOrNull() ?: throw BadRequestException("Invalid push endpoint")
    val url = value.toHttpUrlOrNull() ?: throw BadRequestException("Invalid push endpoint")
    val host = url.host.lowercase().trimEnd('.')
    val validOrigin = uri.isAbsolute && uri.scheme == "https" && uri.port in setOf(-1, 443)
    val validAuthority = uri.rawUserInfo == null && uri.fragment == null
    val knownProvider = PUSH_PROVIDER_HOSTS.any { host == it || host.endsWith(".$it") }
    if (!validOrigin || !validAuthority || !knownProvider) {
        throw BadRequestException("Invalid push endpoint")
    }
    return url.toString()
}

private val PUSH_PROVIDER_HOSTS = setOf(
    "fcm.googleapis.com",
    "push.services.mozilla.com",
    "notify.windows.com",
    "web.push.apple.com",
)

private const val CLAIM_PUSH_SQL = """
WITH picked AS (
  SELECT q.notification_id, q.subscription_id
  FROM notification_push_queue q
  ORDER BY q.notification_id, q.subscription_id
  FOR UPDATE OF q SKIP LOCKED
  LIMIT ?
), claimed AS (
  DELETE FROM notification_push_queue q USING picked p
  WHERE q.notification_id = p.notification_id AND q.subscription_id = p.subscription_id
  RETURNING q.notification_id, q.subscription_id
)
SELECT s.id, n.id
FROM claimed c
JOIN notifications n ON n.id = c.notification_id
JOIN browser_push_subscriptions s ON s.id = c.subscription_id AND s.user_id = n.recipient_id
WHERE n.created_at >= CAST(? AS int8)
"""

private const val ELIGIBLE_PUSH_SQL = """
SELECT s.endpoint_encrypted
FROM browser_push_subscriptions s
JOIN users u ON u.id = s.user_id AND NOT u.marked_as_deleted AND u.credential_revision = s.credential_revision
WHERE s.id = CAST(? AS int4) AND EXISTS (
  SELECT 1
  FROM notifications n
  JOIN rooms r ON r.id = n.room_id AND NOT r.marked_as_deleted
  JOIN room_members rm ON rm.room_id = n.room_id AND rm.user_id = n.recipient_id
  LEFT JOIN thread_mutes tm ON tm.thread_id = n.thread_id AND tm.user_id = n.recipient_id
  WHERE n.id = ANY(CAST(? AS int4[])) AND n.recipient_id = s.user_id
    AND n.in_app AND n.seen_at IS NULL AND tm.thread_id IS NULL
)
"""

private const val REGISTERED_PUSH_SQL = """
SELECT EXISTS (
  SELECT 1
  FROM browser_push_subscriptions s
  JOIN users u ON u.id = s.user_id
  WHERE s.id = CAST(? AS int4) AND s.user_id = CAST(? AS int4)
    AND s.credential_revision = CAST(? AS int8) AND s.created_at >= CAST(? AS int8)
    AND u.credential_revision = s.credential_revision AND NOT u.marked_as_deleted
)
"""
