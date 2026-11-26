package ch.nokillswit.plugins

import io.ktor.server.plugins.ratelimit.RateLimiter
import io.r2dbc.spi.Row
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.count
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.single
import kotlinx.coroutines.flow.singleOrNull
import org.jetbrains.exposed.v1.core.IColumnType
import org.jetbrains.exposed.v1.core.IntegerColumnType
import org.jetbrains.exposed.v1.core.LongColumnType
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.core.statements.StatementType
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

internal const val RATE_LIMIT_WINDOW_MILLIS = 60_000L
internal const val RATE_LIMIT_MAX_ACTIVE_KEYS = 100_000
internal const val RATE_LIMIT_CLEANUP_BATCH = 1_000

internal data class SharedRateLimitDecision(
    val admitted: Boolean,
    val remaining: Int,
    val limit: Int,
    val serverNowMillis: Long,
    val refillAtMillis: Long,
    val capacityRefusal: Boolean = false,
)

/** PostgreSQL fixed-window limiter: the common active-key admission is one indexed UPDATE/RETURNING operation. */
internal class SharedRateLimitStore(
    private val database: R2dbcDatabase,
    private val maxStoredKeys: Int = RATE_LIMIT_MAX_ACTIVE_KEYS,
) {
    suspend fun consume(bucket: String, keyHash: String, limit: Int, weight: Int = 1): SharedRateLimitDecision {
        require(bucket.length in 1..40)
        require(KEY_HASH.matches(keyHash))
        require(limit > 0 && weight in 1..limit)
        consumeExistingWindow(bucket, keyHash, limit, weight)?.let { return it }
        admitNewKey(bucket, keyHash, limit, weight, retryLockedExpired = true)?.let { return it }
        delay(CAPACITY_RETRY_MILLIS)
        return checkNotNull(admitNewKey(bucket, keyHash, limit, weight, retryLockedExpired = false))
    }

    /**
     * Existing keys never need the capacity guard. Keep their tuple-lock transaction separate: a conditional UPDATE that loses a
     * READ COMMITTED race can briefly lock the latest tuple before returning no row, and must release it before any guard wait.
     */
    private suspend fun consumeExistingWindow(
        bucket: String,
        keyHash: String,
        limit: Int,
        weight: Int,
    ): SharedRateLimitDecision? = suspendTransaction(database) {
        consumeExisting(bucket, keyHash, limit, weight)?.let { return@suspendTransaction it }
        readExisting(bucket, keyHash, limit)?.let { existing ->
            if (existing.remaining < weight) return@suspendTransaction existing
            consumeExisting(bucket, keyHash, limit, weight)?.let { return@suspendTransaction it }
        }
        null
    }

    /** Guard-first path for a genuinely absent key; every row lock taken here follows the shared capacity guard. */
    private suspend fun admitNewKey(
        bucket: String,
        keyHash: String,
        limit: Int,
        weight: Int,
        retryLockedExpired: Boolean,
    ): SharedRateLimitDecision? = suspendTransaction(database) {
        var stored = checkNotNull(lockCapacity()) { "Rate-limit capacity guard is missing" }
        consumeExisting(bucket, keyHash, limit, weight)?.let { return@suspendTransaction it }
        readExisting(bucket, keyHash, limit)?.let { existing -> return@suspendTransaction existing }
        var capacity = capacity()
        if (stored >= maxStoredKeys && reclaimOneExpired()) {
            stored -= 1
            capacity = capacity()
        }
        if (stored >= maxStoredKeys) {
            if (retryLockedExpired && capacity.hasExpired) return@suspendTransaction null
            return@suspendTransaction SharedRateLimitDecision(
                admitted = false,
                remaining = 0,
                limit = limit,
                serverNowMillis = capacity.serverNow,
                refillAtMillis = capacity.earliestRefillAt ?: capacity.serverNow + RATE_LIMIT_WINDOW_MILLIS,
                capacityRefusal = true,
            )
        }
        insert(bucket, keyHash, limit, weight)?.let { return@suspendTransaction it }
        consumeExisting(bucket, keyHash, limit, weight)
            ?: checkNotNull(readExisting(bucket, keyHash, limit))
    }

    /** Ordered, bounded expiry cleanup; called only by the background sweep, never by a request. */
    suspend fun pruneExpired(batchSize: Int = RATE_LIMIT_CLEANUP_BATCH): Int {
        require(batchSize > 0)
        return suspendTransaction(database) {
            checkNotNull(lockCapacity()) { "Rate-limit capacity guard is missing" }
            TransactionManager.current().exec(
                PRUNE_EXPIRED,
                listOf(IntegerColumnType() to batchSize),
                explicitStatementType = StatementType.SELECT,
            ) { row -> checkNotNull(row.get(0, String::class.java)) }?.count() ?: 0
        }
    }

    private suspend fun consumeExisting(bucket: String, keyHash: String, limit: Int, weight: Int): SharedRateLimitDecision? =
        executeDecision(
            CONSUME_EXISTING,
            listOf(
                IntegerColumnType() to limit,
                IntegerColumnType() to weight,
                IntegerColumnType() to weight,
                LongColumnType() to RATE_LIMIT_WINDOW_MILLIS,
                TextColumnType() to bucket,
                TextColumnType() to keyHash,
                IntegerColumnType() to weight,
            ),
            admitted = true,
            limit = limit,
        )

    private suspend fun readExisting(bucket: String, keyHash: String, limit: Int): SharedRateLimitDecision? =
        executeDecision(
            READ_EXISTING,
            arguments(bucket, keyHash),
            admitted = false,
            limit = limit,
        )

    private suspend fun insert(bucket: String, keyHash: String, limit: Int, weight: Int): SharedRateLimitDecision? =
        executeDecision(
            INSERT_NEW,
            arguments(bucket, keyHash) + listOf(
                IntegerColumnType() to limit,
                IntegerColumnType() to weight,
                LongColumnType() to RATE_LIMIT_WINDOW_MILLIS,
            ),
            admitted = true,
            limit = limit,
        )

    private suspend fun executeDecision(
        sql: String,
        arguments: Iterable<Pair<IColumnType<*>, Any?>>,
        admitted: Boolean,
        limit: Int,
    ): SharedRateLimitDecision? = TransactionManager.current().exec(
        sql,
        arguments,
        explicitStatementType = StatementType.SELECT,
    ) { row -> row.decision(admitted, limit) }.singleOptional()

    private suspend fun lockCapacity(): Int? =
        TransactionManager.current().exec(
            LOCK_CAPACITY,
            explicitStatementType = StatementType.SELECT,
        ) { row -> checkNotNull(row.get(0, Int::class.javaObjectType)) }.singleOptional()

    private suspend fun reclaimOneExpired(): Boolean =
        TransactionManager.current().exec(
            RECLAIM_ONE_EXPIRED,
            explicitStatementType = StatementType.SELECT,
        ) { row -> checkNotNull(row.get(0, String::class.java)) }.singleOptional() != null

    private suspend fun capacity(): Capacity =
        TransactionManager.current().exec(CAPACITY, explicitStatementType = StatementType.SELECT) { row ->
            Capacity(
                checkNotNull(row.get(0, Long::class.javaObjectType)),
                checkNotNull(row.get(1, Boolean::class.javaObjectType)),
                row.get(2, Long::class.javaObjectType),
            )
        }.singleRequired()

    private fun arguments(bucket: String, keyHash: String) =
        listOf(TextColumnType() to bucket, TextColumnType() to keyHash)

    private data class Capacity(val serverNow: Long, val hasExpired: Boolean, val earliestRefillAt: Long?)

    private fun Row.decision(admitted: Boolean, limit: Int) = SharedRateLimitDecision(
        admitted = admitted,
        remaining = checkNotNull(get(1, Int::class.javaObjectType)),
        limit = limit,
        serverNowMillis = checkNotNull(get(0, Long::class.javaObjectType)),
        refillAtMillis = checkNotNull(get(2, Long::class.javaObjectType)),
    )

    private companion object {
        val KEY_HASH = Regex("[0-9a-f]{64}")
        const val CAPACITY_RETRY_MILLIS = 10L
    }
}

private suspend fun <T : Any> Flow<T?>?.singleOptional(): T? = this?.filterNotNull()?.singleOrNull()

private suspend fun <T : Any> Flow<T?>?.singleRequired(): T = checkNotNull(this).filterNotNull().single()

/** Stateless Ktor adapter; the suspending request-weight hook has already made and stored the real database decision. */
internal object SharedRateLimiter : RateLimiter {
    override suspend fun tryConsume(tokens: Int): RateLimiter.State {
        return when (tokens) {
            SHARED_RATE_LIMIT_ADMITTED_WEIGHT -> RateLimiter.State.Available(
                remainingTokens = 0,
                limit = 1,
                refillAtTimeMillis = System.currentTimeMillis() + RATE_LIMIT_WINDOW_MILLIS,
            )
            SHARED_RATE_LIMIT_REFUSED_WEIGHT -> RateLimiter.State.Exhausted(1.milliseconds)
            else -> error("Unexpected shared rate-limit decision weight: $tokens")
        }
    }
}

internal const val SHARED_RATE_LIMIT_ADMITTED_WEIGHT = 1
internal const val SHARED_RATE_LIMIT_REFUSED_WEIGHT = 2

private const val CONSUME_EXISTING = """
WITH clock AS MATERIALIZED (
  SELECT floor(extract(epoch FROM clock_timestamp()) * 1000)::bigint AS now_millis
)
UPDATE http_rate_limit_buckets AS bucket
SET tokens_remaining = CASE
      WHEN bucket.refill_at <= clock.now_millis THEN CAST(? AS int4) - CAST(? AS int4)
      ELSE bucket.tokens_remaining - CAST(? AS int4)
    END,
    refill_at = CASE
      WHEN bucket.refill_at <= clock.now_millis THEN clock.now_millis + CAST(? AS int8)
      ELSE bucket.refill_at
    END
FROM clock
WHERE bucket.bucket_name = CAST(? AS text) AND bucket.key_hash = CAST(? AS char(64))
  AND (bucket.refill_at <= clock.now_millis OR bucket.tokens_remaining >= CAST(? AS int4))
RETURNING clock.now_millis, bucket.tokens_remaining, bucket.refill_at"""

private const val READ_EXISTING = """
WITH clock AS MATERIALIZED (
  SELECT floor(extract(epoch FROM clock_timestamp()) * 1000)::bigint AS now_millis
)
SELECT clock.now_millis, bucket.tokens_remaining, bucket.refill_at
FROM http_rate_limit_buckets AS bucket CROSS JOIN clock
WHERE bucket.bucket_name = CAST(? AS text) AND bucket.key_hash = CAST(? AS char(64))
  AND bucket.refill_at > clock.now_millis"""

private const val INSERT_NEW = """
WITH input AS MATERIALIZED (
  SELECT CAST(? AS text) AS bucket_name, CAST(? AS char(64)) AS key_hash,
         CAST(? AS int4) AS limit_value, CAST(? AS int4) AS weight, CAST(? AS int8) AS window_millis
), clock AS MATERIALIZED (
  SELECT floor(extract(epoch FROM clock_timestamp()) * 1000)::bigint AS now_millis
), inserted AS (
  INSERT INTO http_rate_limit_buckets (bucket_name, key_hash, tokens_remaining, refill_at)
  SELECT input.bucket_name, input.key_hash, input.limit_value - input.weight, clock.now_millis + input.window_millis
  FROM input CROSS JOIN clock
  ON CONFLICT DO NOTHING
  RETURNING tokens_remaining, refill_at
), adjusted AS (
  UPDATE http_rate_limit_capacity_guard
  SET stored_count = stored_count + (SELECT count(*)::int FROM inserted)
  WHERE singleton
  RETURNING singleton
)
SELECT clock.now_millis, inserted.tokens_remaining, inserted.refill_at
FROM clock CROSS JOIN inserted CROSS JOIN adjusted"""

private const val LOCK_CAPACITY = "SELECT stored_count FROM http_rate_limit_capacity_guard WHERE singleton FOR UPDATE"

private const val CAPACITY = """
WITH clock AS MATERIALIZED (
  SELECT floor(extract(epoch FROM clock_timestamp()) * 1000)::bigint AS now_millis
)
SELECT clock.now_millis,
       EXISTS (SELECT 1 FROM http_rate_limit_buckets WHERE refill_at <= clock.now_millis),
       (SELECT refill_at FROM http_rate_limit_buckets
        WHERE refill_at > clock.now_millis ORDER BY refill_at LIMIT 1)
FROM clock"""

private const val RECLAIM_ONE_EXPIRED = """
WITH clock AS MATERIALIZED (
  SELECT floor(extract(epoch FROM clock_timestamp()) * 1000)::bigint AS now_millis
), expired AS (
  SELECT bucket_name, key_hash
  FROM http_rate_limit_buckets CROSS JOIN clock
  WHERE refill_at <= clock.now_millis
  ORDER BY refill_at, bucket_name, key_hash
  LIMIT 1 FOR UPDATE SKIP LOCKED
), deleted AS (
  DELETE FROM http_rate_limit_buckets AS bucket USING expired
  WHERE bucket.bucket_name = expired.bucket_name AND bucket.key_hash = expired.key_hash
  RETURNING bucket.key_hash
), adjusted AS (
  UPDATE http_rate_limit_capacity_guard
  SET stored_count = stored_count - (SELECT count(*)::int FROM deleted)
  WHERE singleton
  RETURNING singleton
)
SELECT deleted.key_hash FROM deleted CROSS JOIN adjusted"""

private const val PRUNE_EXPIRED = """
WITH clock AS MATERIALIZED (
  SELECT floor(extract(epoch FROM clock_timestamp()) * 1000)::bigint AS now_millis
), expired AS (
  SELECT bucket_name, key_hash
  FROM http_rate_limit_buckets CROSS JOIN clock
  WHERE refill_at <= clock.now_millis
  ORDER BY refill_at, bucket_name, key_hash
  LIMIT CAST(? AS int4) FOR UPDATE SKIP LOCKED
), deleted AS (
  DELETE FROM http_rate_limit_buckets AS bucket USING expired
  WHERE bucket.bucket_name = expired.bucket_name AND bucket.key_hash = expired.key_hash
  RETURNING bucket.key_hash
), adjusted AS (
  UPDATE http_rate_limit_capacity_guard
  SET stored_count = stored_count - (SELECT count(*)::int FROM deleted)
  WHERE singleton
  RETURNING singleton
)
SELECT deleted.key_hash FROM deleted CROSS JOIN adjusted"""
