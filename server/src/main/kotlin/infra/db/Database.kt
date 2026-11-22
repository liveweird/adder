package ch.nokillswit.infra.db

import ch.nokillswit.auth.PasswordResetService
import ch.nokillswit.auth.PasswordResetServiceKey
import ch.nokillswit.auth.TokenBlocklistService
import ch.nokillswit.auth.TokenBlocklistServiceKey
import ch.nokillswit.infra.config.requireConfigInt
import ch.nokillswit.infra.config.requireConfigLong
import ch.nokillswit.rooms.RoomMemberService
import ch.nokillswit.rooms.RoomMemberServiceKey
import ch.nokillswit.rooms.RoomService
import ch.nokillswit.rooms.RoomServiceKey
import ch.nokillswit.rooms.RoomWorkflowService
import ch.nokillswit.rooms.RoomWorkflowServiceKey
import ch.nokillswit.rooms.useRoomLockWait
import ch.nokillswit.tags.TagCategoryService
import ch.nokillswit.tags.TagCategoryServiceKey
import ch.nokillswit.teams.TeamService
import ch.nokillswit.teams.TeamServiceKey
import ch.nokillswit.users.UserService
import ch.nokillswit.users.UserServiceKey
import ch.nokillswit.workflows.WorkflowTemplateService
import ch.nokillswit.workflows.WorkflowTemplateServiceKey
import io.ktor.server.application.*
import io.ktor.server.config.ApplicationConfig
import io.ktor.util.AttributeKey
import io.r2dbc.pool.ConnectionPool
import io.r2dbc.pool.ConnectionPoolConfiguration
import io.r2dbc.postgresql.PostgresqlConnectionFactoryProvider
import io.r2dbc.spi.ConnectionFactories
import io.r2dbc.spi.ConnectionFactory
import io.r2dbc.spi.ConnectionFactoryOptions
import io.r2dbc.spi.Option
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabaseConfig
import java.time.Duration

/** The connected database itself — read by the readiness probe (plugins/Health.kt); services get
 *  it injected, and `ConnectionPoolTest` opens transactions directly against the SAME pool the
 *  app's services use to observe `pg_stat_activity` bounded by `postgres.pool.maxSize`. */
val R2dbcDatabaseKey = AttributeKey<R2dbcDatabase>("R2dbcDatabase")

/**
 * The factory of the ONE non-pooled connection the live listener holds (`infra/notify/NotifyListener.kt`, `.claude/docs/persistence.md`
 * "The LISTEN connection"): the pool's own options under the application name `<applicationName>-live`, wrapped only in the
 * leaked-socket guard. It opens nothing by itself (a connection is made by whoever calls `create()`), so there is nothing to dispose.
 */
val NotifyConnectionFactoryKey = AttributeKey<ConnectionFactory>("NotifyConnectionFactory")

/** One reading of the pooled connections (r2dbc-pool's `PoolMetrics`); see [PoolStatsKey]. */
data class PoolSnapshot(val acquired: Int, val idle: Int, val pendingAcquire: Int, val allocated: Int, val maxAllocated: Int)

/**
 * The pool's live figures, for the telemetry sampler (`telemetry/`, which sits above `infra/` and so reads them through this
 * key): a function, because the numbers change per call. Null when the pool reports no metrics (pinned present for
 * r2dbc-pool 1.0.2 by `PoolStatsTest`) - the sampler then simply records none.
 */
val PoolStatsKey = AttributeKey<() -> PoolSnapshot?>("PoolStats")

/**
 * The bounded pool sizing read from `postgres.pool.*` (`.claude/docs/persistence.md`
 * "Connection pool") — boot-validated through the shared [requireConfigInt]/[requireConfigLong]
 * (`infra/config/`, ported from Lettuce), so a malformed or out-of-range bound refuses startup
 * with a config-error message naming the key, before any connection is attempted.
 */
private data class PoolBounds(
    val maxSize: Int,
    val initialSize: Int,
    val maxAcquireTimeSeconds: Long,
    val maxIdleTimeSeconds: Long,
    val idleInTransactionTimeoutSeconds: Long,
    val applicationName: String,
)

private fun readPoolBounds(config: ApplicationConfig): PoolBounds {
    val maxSize = requireConfigInt(config, "postgres.pool.maxSize", min = 1, max = 1000)
    val initialSize = requireConfigInt(config, "postgres.pool.initialSize", min = 0, max = maxSize)
    val maxAcquireTimeSeconds = requireConfigLong(config, "postgres.pool.maxAcquireTimeSeconds", min = 1, max = 600)
    val maxIdleTimeSeconds = requireConfigLong(config, "postgres.pool.maxIdleTimeSeconds", min = 1, max = 86400)
    // 0 = off (PostgreSQL's own meaning); see DEFAULT_IDLE_IN_TRANSACTION_TIMEOUT for the default and why.
    val idleInTransactionTimeoutSeconds = requireConfigLong(config, "postgres.pool.idleInTransactionTimeoutSeconds", min = 0, max = 86400)
    // application_name = adder on every pooled connection by default (deliberate — ops can count
    // this instance's own connections in pg_stat_activity, and ConnectionPoolTest relies on it);
    // overridable per test application instance so overlapping test apps sharing the
    // Testcontainer never share one count.
    val applicationName = config.propertyOrNull("postgres.pool.applicationName")?.getString() ?: "adder"
    return PoolBounds(maxSize, initialSize, maxAcquireTimeSeconds, maxIdleTimeSeconds, idleInTransactionTimeoutSeconds, applicationName)
}

/**
 * Connects Exposed to a bounded R2DBC [ConnectionPool] instead of a raw per-transaction
 * connection factory (`.claude/docs/persistence.md` "Connection pool"): a plain
 * `r2dbc:postgresql://` connect opens one PostgreSQL backend per `suspendTransaction` with
 * nothing capping how many run at once — measured in Lettuce, v3.16.1: a 120-parallel burst
 * against one endpoint took ALL 100 backends of PostgreSQL's default `max_connections` (6 × 500
 * "too many clients", 7 × 401 because the JWT validation's blocklist read failed too — fixed
 * there in v3.16.2 by the same 500-not-401 change ported into `plugins/Security.kt` alongside
 * this pool); pooled, the same burst peaks at `maxSize` = 20.
 *
 * [ConnectionFactoryOptions.parse] plus the user/password/application-name mutations produce
 * [options], from which [ConnectionFactories.get] resolves the PLAIN (unpooled) PostgreSQL
 * factory that [ConnectionPool] then wraps. Exposed's
 * `R2dbcDatabase.connect(connectionFactory, databaseConfig, ...)` overload derives its SQL
 * dialect and reported URL from `databaseConfig.connectionFactoryOptions` alone — it only calls
 * `ConnectionFactories.get(options)` itself when the `connectionFactory` argument is null, and
 * otherwise reads `getDialectName`/`getUrlString` straight off `options` — so [options] (still
 * carrying `driver=postgresql`) is threaded into `databaseConfig` unchanged even though actual
 * traffic goes through the pool. The pool is disposed on [ApplicationStopped] so the hundreds of
 * `testApplication`s the suite boots each release their connections.
 */
private fun Application.connectPooled(): R2dbcDatabase {
    val config = environment.config
    val bounds = readPoolBounds(config)
    val options = config.r2dbcOptions(bounds.applicationName)
    val (database, pool) = connectPooledDatabase(options, bounds)
    monitor.subscribe(ApplicationStopped) { pool.dispose() }
    attributes.put(PoolStatsKey) { pool.snapshot() }
    val liveOptions = config.r2dbcOptions("${bounds.applicationName}$LIVE_APPLICATION_SUFFIX")
    attributes.put(NotifyConnectionFactoryKey, notifyConnectionFactory(liveOptions))
    return database
}

/** The `postgres.*` connection options (URL, user, password) under [applicationName]; the pool's and the listener's share them. */
private fun ApplicationConfig.r2dbcOptions(applicationName: String): ConnectionFactoryOptions =
    ConnectionFactoryOptions.parse(property("postgres.r2dbcUrl").getString())
        .mutate()
        .option(ConnectionFactoryOptions.USER, property("postgres.user").getString())
        .option(ConnectionFactoryOptions.PASSWORD, property("postgres.password").getString())
        .option(PostgresqlConnectionFactoryProvider.APPLICATION_NAME, applicationName)
        .build()

/** Appended to the pool's `application_name` for the listener's connection (`ConnectionPoolTest` counts the pool's own name exactly). */
internal const val LIVE_APPLICATION_SUFFIX = "-live"

/**
 * A plain, unpooled factory over [options] for the listener: it bypasses the pool, [StatementTimeoutCachingConnectionFactory] (the
 * listener sets no statement timeout, and a remembered one would be wrong for a session that outlives every request) and
 * [DetachingConnectionFactory] (there is no borrowing frame to detach from), and keeps the leaked-socket guard so a broken
 * connection's socket is really closed. Nothing is set on the session: it is not in a transaction.
 */
internal fun notifyConnectionFactory(options: ConnectionFactoryOptions): ConnectionFactory =
    LeakedSocketClosingConnectionFactory(ConnectionFactories.get(options))

internal fun ConnectionPool.snapshot(): PoolSnapshot? =
    metrics.orElse(null)?.let {
        PoolSnapshot(it.acquiredSize(), it.idleSize(), it.pendingAcquireSize(), it.allocatedSize(), it.maxAllocatedSize)
    }

/**
 * Distinct SQL texts each pooled connection keeps as a cached, named server-side prepared statement
 * (r2dbc-postgresql's `preparedStatementCacheQueries`; the driver's default, -1, is unbounded and never
 * closes one). Adder's statements are a few hundred fixed texts (Exposed's own plus the quantized multi-row
 * inserts of `MultiRowInsert.kt`); 256 keeps the hot ones and lets an LRU eviction close the rest, so a
 * long-lived connection's backend memory stays bounded.
 */
internal const val PREPARED_STATEMENT_CACHE_QUERIES = 256

/**
 * PostgreSQL's `idle_in_transaction_session_timeout` for every pooled connection (`postgres.pool.idleInTransactionTimeoutSeconds`,
 * sent as a startup parameter, so it is the session default — it also overrides an `ALTER ROLE/DATABASE … SET` of the same
 * setting — and no `SET` ever touches it): the server ends a session that sits idle INSIDE a transaction for longer, rolling the
 * transaction back and releasing its locks. It is the backstop for a session whose client stopped talking to it with a
 * transaction open — the r2dbc-postgresql socket leak `LeakedSocketGuard.kt` closes is the one seen (build-times WHY 24), a
 * frozen request thread or a coroutine stuck mid-transaction are the others. An idle gap inside a transaction can never be longer than
 * the transaction, and no transaction spans an outbound call or a mail send, and every one is a few statements, so 5 minutes is
 * far beyond any legitimate gap. It ends only an IDLE session — one stuck `active` (in `ClientWrite`) is the guard's job.
 */
internal val DEFAULT_IDLE_IN_TRANSACTION_TIMEOUT: Duration = Duration.ofMinutes(5)

private const val IDLE_IN_TRANSACTION_TIMEOUT = "idle_in_transaction_session_timeout"

/**
 * [options] plus the `idle_in_transaction_session_timeout` startup parameter, unless the URL already set one (an operator's
 * `options=` wins). The driver takes `options` as a map or as its `key=value;key=value` string form.
 */
internal fun withIdleInTransactionTimeout(options: ConnectionFactoryOptions, timeout: Duration): ConnectionFactoryOptions {
    val millis = timeout.toMillis().toString()
    val existing = if (options.hasOption(STARTUP_OPTIONS)) options.getValue(STARTUP_OPTIONS) else null
    val alreadySet = when (existing) {
        null -> false
        is Map<*, *> -> existing.containsKey(IDLE_IN_TRANSACTION_TIMEOUT)
        else -> IDLE_IN_TRANSACTION_TIMEOUT in existing.toString()
    }
    if (alreadySet) return options
    val merged: Any = when (existing) {
        null -> mapOf(IDLE_IN_TRANSACTION_TIMEOUT to millis)
        is Map<*, *> -> existing + (IDLE_IN_TRANSACTION_TIMEOUT to millis)
        else -> "$existing;$IDLE_IN_TRANSACTION_TIMEOUT=$millis"
    }
    return options.mutate().option(STARTUP_OPTIONS, merged).build()
}

// The driver's `options` option is typed Map<String, String>, but a URL's `?options=` arrives as its string form.
@Suppress("UNCHECKED_CAST") // reading and writing the one option whose declared type the URL form does not honour
private val STARTUP_OPTIONS = PostgresqlConnectionFactoryProvider.OPTIONS as Option<Any>

/**
 * The pool construction itself, shared by [connectPooled] (production and every `testApplication`)
 * and the test harness's own direct-access database (`sharedDatabaseForTests()`, which had been an
 * UNPOOLED connect paying a fresh PostgreSQL backend, ~4 ms, per transaction). The caller owns
 * disposing the returned [ConnectionPool].
 */
internal fun connectPooledDatabase(
    options: ConnectionFactoryOptions,
    maxSize: Int,
    initialSize: Int,
    maxAcquireTime: Duration,
    maxIdleTime: Duration,
    idleInTransactionTimeout: Duration = DEFAULT_IDLE_IN_TRANSACTION_TIMEOUT,
): Pair<R2dbcDatabase, ConnectionPool> {
    // Bound the driver's prepared-statement cache (its default is unbounded, and every cached text stays a
    // named server-side statement for the connection's life): see PREPARED_STATEMENT_CACHE_QUERIES.
    val cached = if (options.hasOption(PostgresqlConnectionFactoryProvider.PREPARED_STATEMENT_CACHE_QUERIES)) {
        options
    } else {
        options.mutate()
            .option(PostgresqlConnectionFactoryProvider.PREPARED_STATEMENT_CACHE_QUERIES, PREPARED_STATEMENT_CACHE_QUERIES)
            .build()
    }
    LeakedSockets.checkSupported()
    // The decorators sit below the pool: the remembered statement timeout belongs to one physical connection
    // (StatementTimeoutCache.kt), and the pool's destroy of a broken connection must reach the socket guard (LeakedSocketGuard.kt).
    // The detaching one ends the subscriber chain reactor-netty keeps on each channel at its bridge, so it never reaches the pool's
    // borrower and the creating caller's frame (DetachingConnectionFactory.kt, build-times WHY 51 and 52); it is OUTERMOST so an
    // orphan it closes is the decorated connection, whose close reaches the socket guard.
    val rawFactory = DetachingConnectionFactory(
        StatementTimeoutCachingConnectionFactory(
            LeakedSocketClosingConnectionFactory(ConnectionFactories.get(withIdleInTransactionTimeout(cached, idleInTransactionTimeout))),
        ),
    )
    val pool = ConnectionPool(
        ConnectionPoolConfiguration.builder(rawFactory)
            .maxSize(maxSize)
            .initialSize(initialSize)
            // r2dbc-pool 1.0.2 builds create() as `Mono.defer { acquire.timeout(maxAcquireTime) }.retry(acquireRetry)`
            // with acquireRetry defaulting to 1, so the effective acquire deadline is ~2x maxAcquireTime (the
            // retried waiter re-queues). Kept deliberately: the same retry transparently replaces a connection that
            // fails LOCAL validation (closed by a Postgres/pod restart). `.claude/docs/persistence.md`.
            .maxAcquireTime(maxAcquireTime)
            .maxIdleTime(maxIdleTime)
            .build(),
    )
    val databaseConfig = R2dbcDatabaseConfig.Builder().apply {
        connectionFactoryOptions = options
        // ONE attempt per suspendTransaction: Exposed's default of three retries any
        // R2dbcException, and the pool's acquire timeout is one — retrying a saturated pool
        // three times would turn the acquire budget into 3x the queueing per request exactly
        // when the pool is already full. Adder has no path relying on Exposed's retry.
        defaultMaxAttempts = 1
    }
    return R2dbcDatabase.connect(connectionFactory = pool, databaseConfig = databaseConfig) to pool
}

/**
 * The notification list's OWN limiter cap (`ChatWiring`; fixed, no config key): at most this many of its reads in the process, so a flood
 * of notice re-reads (every `notification.created` hint makes each open tab read it up to three times) can never crowd the thread and tag
 * lists out of `threads.readMaxConcurrent`, nor they it. It is counted by [requireReadersUnderPool] with the other readers.
 */
internal const val NOTIFICATION_READ_MAX_CONCURRENT = 4

/**
 * The thread detail's OWN limiter cap (`ChatWiring`; fixed, no config key): at most this many `GET .../threads/{number}` reads in the
 * process. The detail read is not in the `chat-read` bucket (a live hint re-reads the open thread unspaced), so an unbucketed loop of it
 * must never be able to take `threads.readMaxConcurrent` from the bucketed lists, nor they it. Each read takes a few milliseconds, so 3
 * serve a room of a few dozen people re-reading after one hint within the 1 s permit wait. Counted by [requireReadersUnderPool].
 */
internal const val THREAD_READ_MAX_CONCURRENT = 3

/**
 * The fail-closed pool check of production mode: the list reads, the thread detail, the notification list and the searches each hold ONE
 * pooled connection while they run, so their process caps together must stay below the pool, or a flood of them could take every
 * connection (the rest of the app, the room-write path included, would then wait for the pool). Development mode skips it: tests run
 * tiny pools on purpose (`ConnectionPoolTest`).
 */
internal fun requireReadersUnderPool(readMaxConcurrent: Int, searchMaxConcurrent: Int, poolMaxSize: Int) {
    val notices = NOTIFICATION_READ_MAX_CONCURRENT
    val detail = THREAD_READ_MAX_CONCURRENT
    check(readMaxConcurrent + searchMaxConcurrent + notices + detail < poolMaxSize) {
        "Config \"threads.readMaxConcurrent\" ($readMaxConcurrent) + \"search.maxConcurrent\" ($searchMaxConcurrent) + the notification " +
            "list's cap ($notices) + the thread detail's cap ($detail) must stay below \"postgres.pool.maxSize\" ($poolMaxSize), so " +
            "reads and searches together can never take the whole pool"
    }
}

private fun connectPooledDatabase(options: ConnectionFactoryOptions, bounds: PoolBounds) = connectPooledDatabase(
    options,
    maxSize = bounds.maxSize,
    initialSize = bounds.initialSize,
    maxAcquireTime = Duration.ofSeconds(bounds.maxAcquireTimeSeconds),
    maxIdleTime = Duration.ofSeconds(bounds.maxIdleTimeSeconds),
    idleInTransactionTimeout = Duration.ofSeconds(bounds.idleInTransactionTimeoutSeconds),
)

/**
 * The DI composition root: connects the one R2DBC database (through the bounded pool above) and
 * publishes every service into [Application.attributes]. Feature modules read their services back
 * via the AttributeKey — application.yaml runs this module before any route module, so the keys
 * are always present.
 */
suspend fun Application.configureDatabase() {
    // Both are boot-validated here, once: the list reads' and the searches' process caps (`ReadLimiter`, `ThreadSearchService`); the
    // notification list's own fixed cap is counted with them below.
    val readMaxConcurrent = requireConfigInt(environment.config, "threads.readMaxConcurrent", min = 1, max = 16)
    val searchMaxConcurrent = requireConfigInt(environment.config, "search.maxConcurrent", min = 1, max = 16)
    if (!developmentMode) requireReadersUnderPool(readMaxConcurrent, searchMaxConcurrent, readPoolBounds(environment.config).maxSize)
    val database = connectPooled()
    // The bound on a room write's wait for the room lock (rooms/RoomLock.kt), boot-validated like the pool's own bounds.
    database.useRoomLockWait(requireConfigInt(environment.config, "rooms.lockWaitMillis", min = 100, max = 30_000))
    val userService = UserService(database)
    attributes.put(R2dbcDatabaseKey, database)
    attributes.put(UserServiceKey, userService)
    val teamService = TeamService(database)
    attributes.put(TeamServiceKey, teamService)
    // The ADMIN tag registry (V32, 1.2.0): its writes are the one user of `lockingTransaction` (`infra/db/Locking.kt`).
    attributes.put(TagCategoryServiceKey, TagCategoryService(database))
    val workflowTemplateService = WorkflowTemplateService(database)
    attributes.put(WorkflowTemplateServiceKey, workflowTemplateService)
    // The room aggregate (V13): a room copies a template at creation, so the room service reads the template service.
    val roomWorkflowService = RoomWorkflowService(database)
    attributes.put(RoomWorkflowServiceKey, roomWorkflowService)
    val roomService = RoomService(database, workflowTemplateService, roomWorkflowService)
    attributes.put(RoomServiceKey, roomService)
    val roomMemberService = RoomMemberService(database, teamService)
    attributes.put(RoomMemberServiceKey, roomMemberService)
    publishChatServices(
        database, roomService, roomMemberService, roomWorkflowService, teamService, readMaxConcurrent, searchMaxConcurrent,
    )
    attributes.put(
        TokenBlocklistServiceKey,
        TokenBlocklistService(
            database,
            replayGraceMillis = requireConfigLong(
                environment.config, "security.refresh.replayGraceSeconds", min = 0, max = 600,
            ) * 1000,
        ),
    )
    // Persisted single-use reset links (V8): the lifetime is boot-validated, 1 second .. 1 hour.
    attributes.put(
        PasswordResetServiceKey,
        PasswordResetService(
            database,
            ttlMillis = requireConfigLong(environment.config, "security.passwordReset.tokenTtlSeconds", min = 1, max = 3600) * 1000,
        ),
    )
}
