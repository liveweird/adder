### Persistence

PostgreSQL is the only database. Connection settings come from the `postgres:` block in
`application.yaml` (env-overridable via `POSTGRES_JDBC_URL`, `POSTGRES_R2DBC_URL`,
`POSTGRES_USER`, `POSTGRES_PASSWORD`); defaults match the `docker compose up postgres` service
(host port **5439** — the sibling repos occupy 5432–5438 on the same machine, table in `ci.md`;
in-network consumers use `postgres:5432`). There is one persistence stack:

- **Flyway** (`infra/db/Flyway.kt`) — runs schema migrations from
  `server/src/main/resources/db/migration/` at startup via the Java API, opening a short-lived
  JDBC connection. Migrations are the single source of truth for schema; do not call
  `SchemaUtils.create` anywhere. **An applied migration's bytes are immutable — comments
  included**: Flyway validates stored checksums at startup, so any edit to an existing `V*.sql`
  makes every long-lived database refuse to boot (a failure no fresh-container CI run can see).
  `MigrationChecksumTest` pins every file's checksum; a new migration adds one manifest line, and
  a red pin means REVERT the edit (clarifications go into this doc), never update the pinned
  value. **The one exception, an unreleased migration:** a migration may still change while NO released
  version has applied it (today V17 to V22, the 0.6.0 migrations, and V23, the 1.0.0 history scrub: nothing but a developer's own database, or an OrbStack deployment from this branch, has them);
  the pin is then re-pinned in the same commit, and a dev database that booted an earlier checksum of such a file is reset (`docker compose down -v`) or repaired (`flyway repair`).
  **The lanes of one release land in any order, so a dev database can apply a later 0.6 migration before an earlier one (V20 before V18, say):** Flyway then refuses the
  lower number (`outOfOrder` is off) and the table it creates is missing, so reset such a database with `docker compose down -v`; a fresh one (a test container, CI) applies them in order.
  The exception ends with the release that contains the file. The range today is **V1–V23**: V1 `users`, V2 `revoked_tokens`, V3 the seed admin, V4 the
  `unaccent` extension, V5 `user_disabled_features` (V1–V5 are Flow's), V6 `teams` (with the optional
  manager), V7 `users.credential_revision`, V8 `password_reset_tokens`, V9 the `ops` telemetry
  schema, V10 `team_members` (the roster), V11 `workflow_templates` (the registry), V12 its two seed rows, V13 the room aggregate (`rooms`, `room_members`, `room_statuses`, `room_fields`), V14 the thread aggregate (`threads`, `messages`, `thread_events`), V15 the hashtag index (`message_tags`, `messages.tags_version`), V16 the search columns (`messages.search` and `threads.search`, the `adder_search` configuration; its GIN index was dropped before release), V17 the mention index (`message_mentions`), V18 the notices (`notifications`), V19 the read state (`room_members.seen_at` and `read_from`, `thread_reads`), V20 the reaction table (`message_reactions`), V21 the room pins (`room_pins`), V22 the digest's claim column and the email switches (`notifications.emailed_at`, `user_notification_preferences`), V23 the history scrub (data only: `thread_events.params`). (The bootstrap renumbered the donor's history; from V10 on, migrations are append-only like any other.)
- **Exposed + R2DBC** (`infra/db/Database.kt` + the feature services) — runtime DB access.
  `Database.kt` connects the `R2dbcDatabase` and is the composition root (the chat's services are built by `ChatWiring.kt` beside it, called from `configureDatabase`): it constructs the
  services and publishes them into `Application.attributes` (`UserServiceKey`, `TeamServiceKey`,
  `WorkflowTemplateServiceKey`, `RoomServiceKey`, `RoomWorkflowServiceKey`, `TokenBlocklistServiceKey`, `PasswordResetServiceKey` today); each service itself lives next to the feature it serves
  (`users/UserService.kt`, `teams/TeamService.kt`, `rooms/RoomService.kt`, `auth/TokenBlocklistService.kt`, `auth/PasswordResetService.kt`). The Exposed
  table `object`s (e.g. `UserService.Users`, nested inside their service) are used for queries
  only, not DDL. Ids are `UIntIdTable` — unsigned end-to-end (the spec declares `minimum: 0`, and
  `ErrorHandling.kt` 400s negative path segments before kotlinx's `UInt` decoding can silently
  wrap them); one wrinkle inherited from Toadie: V1 creates `users.id` as `BIGSERIAL` (64-bit in
  SQL, 32-bit everywhere above it) while `teams` uses `SERIAL`/`INTEGER` — harmless at this scale,
  documented so nobody "fixes" one to match the other without a migration (an `INTEGER` FK column
  may reference the `BIGINT` id; Postgres compares them fine).

The `org.postgresql:postgresql` JDBC driver is on the classpath solely for Flyway; runtime queries
go through R2DBC.

### Connection pool

- **Applies when:** touching `infra/db/Database.kt`'s connect call, the `postgres.pool.*`
  configuration, or reasoning about how many PostgreSQL backends one Adder instance can hold.
- **Requirement:** Exposed connects through ONE bounded `io.r2dbc:r2dbc-pool` `ConnectionPool`
  (ported from Lettuce via Flow) wrapping the plain PostgreSQL R2DBC factory, built by the one shared
  `connectPooledDatabase` (the test harness's direct `sharedDatabaseForTests()` uses it too, with a
  small pool of its own) — never a raw `r2dbc:postgresql://` connect, which opens one backend per `suspendTransaction` with nothing
  capping concurrency (measured in Lettuce: 120 parallel requests took ALL 100 backends of PostgreSQL's default `max_connections`;
  the JWT validation's blocklist read failed and surfaced as an invalid token — so a throwing blocklist lookup answers the
  catch-all's 500, never 401, see "JWT model" in `.claude/docs/security.md`).
  Bounds come from `application.yaml`'s `postgres.pool` block, each boot-validated (startup fails
  outside the range, via the shared `infra/config/` `requireConfigInt`/`requireConfigLong` helpers): `maxSize`
  (`POSTGRES_POOL_MAX_SIZE`, default 20, 1..1000), `initialSize` (`POSTGRES_POOL_INITIAL_SIZE`,
  default 2, 0..maxSize — the floor the pool fills up to on its FIRST acquire, not at
  construction: r2dbc-pool warms up lazily and `warmup()` is deliberately not called),
  `maxAcquireTimeSeconds` (`POSTGRES_POOL_MAX_ACQUIRE_SECONDS`, default 10,
  1..600) and `maxIdleTimeSeconds` (`POSTGRES_POOL_MAX_IDLE_SECONDS`, default 600, 1..86400). Every
  pooled connection carries `application_name = adder` (`postgres.pool.applicationName`, test-only
  override), so operators count this instance's backends with
  `SELECT count(*) FROM pg_stat_activity WHERE application_name = 'adder'`. The driver's per-connection
  prepared-statement cache is bounded (`PREPARED_STATEMENT_CACHE_QUERIES` = 256 in `Database.kt`, applied in
  `connectPooledDatabase`, so the test pool has it too; r2dbc-postgresql's default is unbounded and every cached text stays a
  named server-side statement for the connection's life) — it matters because `MultiRowInsert.kt`'s SQL text varies with the row count
  (quantized to a handful of sizes per table). **A multi-row statement is bounded in payload as well as in bind parameters**:
  `MultiRowInsert.kt` also splits a batch so one statement binds at most `MAX_PAYLOAD_CHARS` (1 MiB) of `String` values (a lone
  heavier row goes alone, never refused there). `insertRows` / `insertRowsIgnoringConflicts` build each row (run the caller's body)
  lazily, only while the statement that carries it is assembled, so the rows in flight are one statement's cap, not the call's, and
  each body runs exactly once. Exposed lists every
  statement a transaction ran until it ends (parsed SQL and encoded parameters included): `insertRows` clears the list after each statement.
- **Statement timeouts: use the transaction's `queryTimeout`, nothing else.** Exposed's R2DBC executor *requests* the
  transaction's `queryTimeout` (whole seconds, default 0, never null) from the driver before EVERY statement. The telemetry reads
  use it (`queryTimeout` 5 s, "Telemetry (V9)"). **The per-connection cache (`infra/db/StatementTimeoutCache.kt`).**
  r2dbc-postgresql answers each request with its own `SET STATEMENT_TIMEOUT` simple query that Exposed awaits — a large share of all
  round trips. `connectPooledDatabase` therefore wraps the RAW connection factory (below the pool) in
  `StatementTimeoutCachingConnectionFactory`: each physical connection remembers the value the server last confirmed, and the
  driver call is *sent* only when the requested value differs from it. No reset on release is needed — Exposed requests its
  value before every statement, so the borrower after a 30 s budget sees `0 != 30` and sends it, which is also why a budget
  never leaks to the next borrower. **The hazard it handles:** PostgreSQL reverts a session-level
  `SET` issued inside a transaction when that transaction rolls back, and a statement cancelled by the timeout aborts its
  transaction — a naive cache would then believe the budget is still set and run the next query unbudgeted. The remembered value
  is therefore forgotten on every rollback (also to a savepoint), on a failed commit and on `setAutoCommit(true)`, forgotten
  before a SET is sent and remembered only once it completed under an unchanged generation, and kept across a successful commit.
  **Rule: ONLY the transaction's `queryTimeout` may set the statement timeout.** A `SET LOCAL statement_timeout` may stay in
  force for the rest of its transaction (the cache no longer sends a request that matches its remembered value, so nothing
  overwrites it), and a session-level `SET`, `set_config(…, false)`, `RESET` or `DISCARD` on a pooled connection desyncs the cache
  from the backend. Deployment assumption: one client connection is one server session — a transaction-mode PgBouncer in front of
  Postgres would break the cache (none today). `StatementTimeoutCacheTest` (own one-connection pool) pins convergence, the
  rolled-back SET, the aborted-transaction canary, a budget enforced again after a cancelled statement, and the skip with its limit.
- **Sizing.** Size it as `(maxSize + 1) × replicas + 1` (the `+ 1` per replica is the live listener's connection, "The LISTEN connection" below; the last is Flyway's short-lived JDBC connection, `infra/db/Flyway.kt`) well under
  the server's `max_connections`. `maxAcquireTimeSeconds` is a per-attempt deadline, and the effective
  bound is about 2× the setting. r2dbc-pool 1.0.2's `ConnectionPool` builds `create()` as
  `Mono.defer { acquire.flatMap(…).timeout(maxAcquireTime) }.retry(acquireRetry)` and the builder default is
  `acquireRetry = 1`: the timeout is an ordinary error to `retry`, so a timed-out waiter is
  re-queued at the BACK of the acquire queue for a second full deadline before the failure surfaces. Adder keeps the
  default on purpose: the same retry is what transparently replaces a connection that fails `LOCAL` validation
  (`prepareConnection` invalidates it and the outer retry acquires another), so a connection closed from the driver
  side — a Postgres or pod restart, a FIN/RST on an idle socket — costs no request, whereas with
  `acquireRetry(0)` each such stale connection would fail one. After a Postgres restart the pool therefore heals itself as long as
  at most one stale connection is handed out per acquire; size `maxAcquireTimeSeconds` knowing a saturated pool fails after twice it
  (`ConnectionPoolTest` pins the ~2×). A caller that waits past the acquire deadline fails with the pool's timeout
  exception, which `plugins/ErrorHandling.kt`'s catch-all renders as a logged `500` — deliberately NOT a new declared status, since the
  OpenAPI conformance gate would need it on every operation. The pool is disposed on `ApplicationStopped`, so every `testApplication` the
  suite boots releases its connections. Every background loop (notification sweep, telemetry sampler, tag reindex, the live channel) therefore
  lives in an `Application.backgroundScope(...)` (`infra/BackgroundScope.kt`), which cancels AND joins it (1 s) on `ApplicationStopping`, earlier. Exposed's `R2dbcDatabase.connect(connectionFactory, databaseConfig)` derives the dialect from
  `databaseConfig.connectionFactoryOptions` alone, so the parsed options (still `driver=postgresql`) are threaded into the config
  unchanged while traffic goes through the pool. The same config pins `defaultMaxAttempts = 1`: Exposed would otherwise retry ANY
  `R2dbcException` three times, and the pool's acquire timeout is one — a saturated pool would cost 3 × the acquire budget per request.
  **Dependency alignment:** r2dbc-pool declares an older reactor-pool than the reactor-core Adder resolves, so
  `server/build.gradle.kts` imports the Reactor BOM (`reactor-bom` in `gradle/libs.versions.toml`) as a platform — it pins
  reactor-core, reactor-pool and reactor-netty as one release train, and `:server:checkDependencyAlignment` fails when any
  reactor module resolves to a version other than the BOM's. Move the `reactor-bom` catalog line together with the `netty` pin.
  Covered by `ConnectionPoolTest` (bounded concurrency, a saturated pool's acquire timeout, its ~2× deadline, disposal on
  `ApplicationStopped` and on a later module's failed startup, and the config range-check cases) and `checkDependencyAlignment`'s own Reactor guard.
- **A backend left holding its transaction.** Two defences, both on every pooled connection
  (built in `connectPooledDatabase`, so the test pool has them too). (1) `infra/db/LeakedSocketGuard.kt`: r2dbc-postgresql 1.1.3
  never closes the socket after a protocol-level connection error ("Response queue is full"). The backend then keeps its transaction
  and locks for the life of the process — `idle in transaction`, or `active` in `ClientWrite`, which no server timeout ends. A decorator
  on the RAW factory (below the pool, beside the statement-timeout cache) disposes the netty channel after the driver's close when the
  client is closed but the channel is open; the pool's release validation (`LOCAL`) fails such a connection at once and destroys it. It
  reads two private driver fields by reflection; `LeakedSocketGuardTest` pins them AND the driver bug on an unguarded connection (red
  after an upgrade = the upstream fix landed, delete the guard). (2) `idle_in_transaction_session_timeout`, sent as a startup parameter
  (`postgres.pool.idleInTransactionTimeoutSeconds`, `POSTGRES_POOL_IDLE_IN_TRANSACTION_TIMEOUT_SECONDS`, default 300, 0..86400, 0 = off;
  an operator's own `options=` key in the URL wins): the server ends any pooled session idle inside a transaction for longer — the
  backstop for a client that stopped talking with a transaction open (a coroutine stuck mid-transaction). No transaction may span an
  outbound call or a mail send. It is a different setting from the statement timeout, so the "only `queryTimeout`" rule is untouched.
  `LeakedSocketGuardTest` pins both.
- **A pooled connection must not pin the frame that created it.** reactor-netty keeps the subscriber
  chain of the `create()` that opened a channel on the channel (`ChannelOperations.listener`) for the channel's whole life
  (`maxIdleTimeSeconds` of idleness, never while the connection is reused). Through r2dbc-pool's allocator, the pool's borrower and
  Exposed's `awaitOne` that chain reached the continuation of the coroutine that happened to trigger the creation, so a finished
  request's frame stayed reachable from the netty selector for minutes. `infra/db/DetachingConnectionFactory.kt` ends the chain at a
  bridge: it subscribes to the real `create()` with a `DetachingBridge`, which forwards the one connection / error / completion to the
  pool and then DROPS the pool's subscriber. It offers the driver an EMPTY Reactor context (reactor-netty copies the subscriber context
  into the observer at connect time and keeps the copy for the channel's life). It is the OUTERMOST decorator of the raw factory in
  `connectPooledDatabase` so that a connection it closes is the decorated one (the close reaches the socket guard). It honours demand,
  delivers exactly once and CLOSES a connection nobody owns (it arrived after a cancel): never a leaked socket.
  `PooledConnectionPinTest` proves it with weak references on a fresh pool (a 16 MiB array held across the query, the function returned,
  the connection idle, `System.gc()` must clear it); its callers run on `Dispatchers.IO` (a test that runs the query directly on the
  `runBlocking` thread keeps a stale reference on that thread's own stack and is red either way). `DetachingConnectionFactoryTest`
  pins the contract without a database.
- **Exception:** the pool runs r2dbc-pool's defaults for liveness (`ValidationDepth.LOCAL`, no
  `maxLifeTime`): a connection killed server-side between uses is handed out once and fails that
  request with a 500 — accepted until a deployment introduces an idle killer or proxy.

### Cross-feature table reads AND writes (the service-layer rule, inherited from Lettuce)

A feature service MAY query — or WRITE — another feature's Exposed table objects directly when the
operation must run **inside its own transaction** (SQL joins, atomic snapshots, a single soft-delete that must also close a
dependent row elsewhere) — the transaction boundary must be explicit rather than relying on an unrelated
service to preserve it. Exposed reuses an enclosing transaction for nested `suspendTransaction`
calls on the same database. Route handlers never touch tables (services only). The reads/writes in
place:

- `TeamService` reads `UserService.Users` for the manager: the active manager's display name (one
  batched lookup per page), the manager id check on create AND update, and `isActiveManager`'s `Teams` ⋈ `Users` join (the
  team-manager guard). `teams.manager_user_id` has no `ON DELETE` rule: users are only ever soft-deleted;
  a future hard purge of users must clear it first, and a future user RESTORE must clear or re-audit the
  manager grants the user held (a soft-deleted manager's id stays in the column, inert only while the user
  is deleted). A future user RESTORE must also deal with the roster rows the user kept and the 200 cap: a restored user's inert `team_members`
  rows become visible again and could push a team's `memberCount` past the 200 inline `members` list (clear them, or re-check the cap). It also owns the roster (V10): `TeamService.TeamMembers` JOINs `UserService.Users` for every roster read (`members`, the page's
  `memberCount`: the inner join drops a soft-deleted member, so a read never shows an inactive user) and for the add's active-user check
  and the cap count, all inside the roster write's own transaction, which first locks the active team row (`lockActiveForWrite`).
  `team_members.user_id` references `users` with `ON DELETE CASCADE` like every join here (users are only soft-deleted, so the rule
  never fires today). A room's team add reads the roster through `TeamService.activeRosterIds` (ACTIVE users only, null for a missing or deleted team), inside the room write's transaction.
- `RoomService` (V13) READS `UserService.Users` for every member count (`RoomMembers` ⋈ `Users`, the inner join keeps ACTIVE users only, one grouped query per list page) and,
  at creation, reads the template through `WorkflowTemplateService.read` INSIDE the room's own transaction (Exposed reuses the enclosing one), so the room, its
  OWNER row and the copy of the template's statuses and fields commit or roll back together; it writes only its own tables (`RoomWorkflowService` owns
  `room_statuses` and `room_fields`, and `RoomService.create` calls its `copyFromTemplate`). `rooms/` imports `workflows/` and `users/`; neither imports `rooms/`
  (`PackageBoundaryTest`). `room_members.user_id` references `users` with `ON DELETE CASCADE` like every join here (users are only soft-deleted, so it never fires);
  a future user RESTORE makes a restored user's inert membership rows visible again, so it must re-check the 500-member cap of every room they sit in AND treat the OWNER roles they bring back (a restored user may suddenly be the active OWNER of a room that had none, or one of several): clear or re-audit those roles instead of letting the restore silently re-grant ownership. `RoomMemberService` (0.2.0 C8) owns the membership writes (the only writer of
  `room_members` after `RoomService.create`'s OWNER row): every one runs inside `withRoomWrite`, so the room lock makes the 500-member cap (counted over ACTIVE users) and the last-OWNER
  count race-free. It READS `UserService.Users` (the active-user check of every named id, and the join under the member list, the cap count and the last-OWNER count) and, for a team add,
  `TeamService.activeRosterIds` (a `TeamService` method called inside the room's own transaction, Exposed reusing the enclosing one), so the roster SNAPSHOT and the rows it becomes
  commit together. The add is ONE multi-row `insertRows` after the existing members are subtracted (so the primary key is never hit), the last-OWNER rule is the last-admin check's shape
  (`UserService.updateGuarded`) without its row locks: "another `OWNER` joined to an active user exists" under the room lock. A member's removal is a hard `DELETE` of the row.
- `ThreadService` (V14, 0.3.0 S2) reads `UserService.Users` directly for display names (one batched `id IN (...)` lookup per list page or read: the owner, the creator and the users held by USER fields,
  soft-deleted accounts included, since a name outlives the account) and writes only its own `threads` and, through `MessageService.insertInTransaction` and `ThreadEventService.record`, `messages`
  and `thread_events`, all inside the create's ONE transaction under `withRoomWrite`, so a thread, its opening message and its `CREATED` event commit or roll back together. **Room data comes
  only through the `rooms/` services, never their tables:** `RoomWorkflowService.snapshot` (the room's statuses and fields, retired included), `RoomMemberService.activeMemberIds`
  (the active members among a set of ids) and `RoomService.touchActivity` (the room's `last_activity_at`), each running INSIDE the caller's transaction (Exposed reuses it). The history reads
  `UserService.Users` through the shared `infra/db/EventLog.kt` (the actor's name and deleted flag, joined in the rows' own transaction): `infra/` may not name `users/`, so `threads/` passes the
  table and its two columns in as an `EventLogActor`. `threads/` imports `rooms/`, `users/` and `workflows/`; `rooms/` never imports `threads/` (`PackageBoundaryTest`).
- `MessageService` (V15) WRITES `message_tags` through `MessageTagService` (its own package) inside the message's own write transaction under the room lock: `insertInTransaction` indexes a new body, an edit rewrites a changed tag set, a delete clears it. The tag list and the list filter read `message_tags` by its own `room_id`, never a `rooms/` table; the boot reindex touches `messages` (`tags_version`), reads `threads.room_id` (an inner join, locking the message rows only) and `message_tags`.
- `MessageMentionService` (V17) is a `MessageFollower` of `MessageService`: it WRITES `message_mentions` inside the message's own write transaction under the room lock, takes the room's active members through `RoomMemberService.activeMemberIds` (never `room_members`) and READS `UserService.Users` for the mentioned people's names (soft-deleted accounts included; one grouped read per message page, joined in the rows' own transaction). `ThreadListFilter` reads `message_mentions` by its own `room_id`, `thread_id` and `user_id` (`threadMentionsAny`).
- `StaleDerivedRows` (`threads/StaleDerivedRows.kt`, run by the `MessageTagReindex.kt` boot task) DELETES from `message_mentions`, `message_reactions`, `room_pins` and `notifications` the rows of messages that are `marked_as_deleted`, selecting and locking the messages (`FOR NO KEY UPDATE OF messages SKIP LOCKED`) in raw SQL, constant text, batches of 1,000 messages, one transaction each. It names `notifications` (a `notifications/` table, which `threads/` otherwise reaches only through the `ThreadNotices` port) because the net exists for the messages the port was never told about (a 0.5.x delete); it reads no column but `message_id`, and it never logs an id.
- `PinService` (V21) writes `room_pins` and reads `threads` (the pinned thread's number, title, status key and `closed_at`, never `fields`), the pinned messages through `MessageService.readInTransaction` and `UserService.Users` for names (`userNames`); it reads no `rooms/` table (the room lock, the archived flag and the member guard come from `withThreadWrite` / `withMessageWrite`). The list's count, rows and messages are separate READ COMMITTED statements in one transaction, so `total` can be one higher than the page for a read that races a delete.
- `NotificationService` (V18, `notifications/`, the top layer: it imports `threads/`, `rooms/` and `users/`, nothing imports it but the composition root (`ChatWiring.kt`)) is the implementation of the `ThreadNotices` port `threads/` declares and WRITES `notifications` INSIDE the caller's transaction (a message's or a hand-over's write, under the room lock): the insert per recipient, the two deletes (a dropped mention, a deleted message) and the "seen" stamps of a thread read. Its list READS `threads` (number and title of the page's threads: `ThreadService.Threads`), `UserService.Users` for the actors' names (`userNames`, soft-deleted accounts included) and the rooms only through `RoomService.memberRooms` (the caller's ACTIVE rooms, one array argument, the search across rooms' shape). The "seen" writes and the retention purge touch only `notifications`. No `rooms/` table is read or written; the foreign key to `room_members` is the one link.
- `ThreadSearchService` (V16) reads `threads` and `messages` through their own table objects and the generated `search` vectors through `infra/db/Fts.kt`'s free expressions, `UserService.Users` for the owner and author names (`userNames`) and the rooms only through `RoomService.summary` (one ACTIVE room: id, name, archived) and `RoomService.memberRooms` (the search across rooms: the ACTIVE rooms the caller is an ACTIVE member of, membership only, an ADMIN's too), never a `rooms/` table. It writes nothing. One read transaction under `search.timeoutSeconds` as its `queryTimeout`.
- **`rooms/` reaches threads only through the `WorkflowUsage` port** (`rooms/WorkflowUsage.kt`); `threads/ThreadWorkflowUsage` implements it and writes `threads` (`status_key`, `updated_at`) and `thread_events` ON `rooms`' BEHALF
  inside the workflow PUT's own transaction, under the room lock (`threadCounts` is one grouped read of `threads`; `moveThreads` one `UPDATE` plus a multi-row `STATUS_REPLACED` insert). It reads the status labels through `RoomWorkflowService.snapshot`,
  never `room_statuses` directly. `Database.kt` (the composition root) registers it, the one place both packages are named.
- `auth/TokenBlocklistService.accessVerdict` (the per-request access-token check, `plugins/Security.kt`) READS
  `users/UserService.Users` (`role`, `credential_revision`, `marked_as_deleted`, by primary key) and its own
  `revoked_tokens` (an `EXISTS` on the `jti`) in ONE uncached statement per authenticated request; it writes nothing.
- `auth/PasswordResetService` (V8) READS `users/UserService.Users` (the `active()` / `credential_revision` / `email` predicate
  of a grant's liveness, and `FOR UPDATE` on the user row) and WRITES `users.password_hash` / `password_changed_at` / `credential_revision`
  through `users`' `updatePasswordInTransaction` inside its own transaction (the grant is consumed in the same transaction).
- `plugins/Health.kt` (`GET /api/v1/ready`, the readiness probe) READS `users/UserService.Users` (`id`, `LIMIT 1`), a bounded round trip
  that proves the database answers; it writes nothing and returns no row.
- `telemetry/StorageCollectors.kt` (the storage panel) reads, OUTSIDE the telemetry cluster transaction (the heavy phase: each statement is a short
  transaction of its own, `HeavyCollectors.kt`): the catalogs (`pg_class`, `pg_namespace`, `pg_attribute`, `pg_database_size`, `pg_total_relation_size`) for
  sizes — row COUNTS and sizes only, never a payload; it writes nothing outside `ops`.
- `telemetry/TelemetryRoutes.kt` (the ADMIN read API: `TelemetryReads.kt`, `TelemetryCurrent.kt`, `TelemetryHistory.kt`) READS `ops.telemetry_processes` /
  `ops.telemetry_samples` (its own schema; one SELECT per transaction under `queryTimeout` 5 s, the history grouped per role/metric/label/bucket in SQL).

- `NotificationDigest` (V22, `notifications/`) READS `users` (name, email and language of an ACTIVE account; the claim and the settle also join `users.marked_as_deleted`; each reads at most 5,000 `notifications` rows a tick under a 10 s `queryTimeout`) and its own `user_notification_preferences` (the switched-off kinds, inside the claim) and `threads` (the number only, never the title) inside its per-recipient re-check, besides the rooms through `RoomService.memberRooms` and `userNames`; it WRITES only `notifications.emailed_at` (the claim and its release).
- `NotificationPreferenceService` (V22, `notifications/`) locks the person's `users` row `FOR NO KEY UPDATE` (the existence check of its read-then-replace, so two replaces serialize) and otherwise touches only `user_notification_preferences`; the digest reads that table through `emailDisabledKinds`.

List each new cross-feature read/write here as it lands — the list IS the permission.

### Schemas

Adder's PostgreSQL database is split across schemas:

- **`public`** — the foundation tables (`users`, `teams`, `team_members`, `workflow_templates`, `revoked_tokens`, `user_disabled_features`, `password_reset_tokens`), the
  room aggregate (`rooms`, `room_members`, `room_statuses`, `room_fields`, V13), the thread aggregate (`threads`, `messages`, `thread_events`, V14) and, later, the remaining chat tables (`chat-model.md`). Flyway's own history table also stays here — `flyway.schemas` is left at its default.
- **`ops`** — Adder's own operational telemetry (V9, "Telemetry (V9)" below): the process registry and the metric samples each
  process writes about itself. Numbers and labels only, never user or chat data.

**How Exposed addresses a schema-qualified table.** No Exposed `Schema` object and no
`search_path` override are involved: a table object simply passes the dotted, schema-qualified name straight to the `Table(...)`
constructor — e.g. `object Samples : Table("ops.telemetry_samples")` — and Exposed/R2DBC resolve it as-is, including a cross-schema
FK reference. `CREATE SCHEMA IF NOT EXISTS <schema>` runs in the first migration that needs the schema (V9 for `ops`) — never a
standalone "create schemas" migration.

### Team roster (V10)

`team_members (team_id, user_id)`: composite primary key, `team_id` → `teams` and `user_id` → `users` both `ON DELETE CASCADE`, an index on `user_id`
(Covenant's V6 shape). Reads resolve members through ACTIVE users (the inner join), so neither `members` nor `memberCount` ever shows a soft-deleted
user; a team's inline roster is bounded at 200 (`MAX_TEAM_MEMBERS`, the add's cap counts active members). Every roster write runs in one
transaction that locks the active team row `FOR NO KEY UPDATE` first, which serializes roster writes (a race-free cap and idempotent add) and
a manager change against them. Authorization: `authorization.md`.

### Workflow templates (V11, V12)

`workflow_templates (id, name, description, definition jsonb, revision, created_at, updated_at, marked_as_deleted)`: a registry row plus ONE
`definition` document `{statuses:[…], fields:[…]}` (Toadie's `blueprints.definition` shape; the statuses and fields are not rows, they are replaced
with the document). Stored through `canonicalJson` (`WorkflowTemplateService.encode`), decoded with a tolerant `Json { ignoreUnknownKeys = true }`;
a `CHECK (jsonb_typeof(definition) = 'object')` is the only DB-side shape rule, the Kotlin enums and `workflows/WorkflowRules.kt` are the whitelist
(the V5 rule). Soft-deleted with the partial unique index `uq_workflow_templates_name_active` (`LOWER(name)`, active rows only; its 409 wording is in
`UNIQUE_CONSTRAINT_DETAILS`), so a deleted template frees its name. `revision` (`BIGINT`, `CHECK > 0`, starts at 1) is the `X-Expected-Revision` token:
the PUT locks the active row `FOR NO KEY UPDATE`, THEN compares (`requireRevision`), THEN writes `revision + 1` in the same transaction. A room
COPIES a template at creation (V13), so a template edit or delete never touches a room. **V12** seeds Default and Incident (decision D3) with
`ON CONFLICT DO NOTHING`; their labels are an applied migration's bytes, so they change only through the ADMIN template editor, never by editing V12
(`WorkflowTemplateTest` decodes both seeds through the kernel and pins every label and both languages).
**The enum names inside the document are persisted values and are frozen:** `StatusColour`, `StatusCategory` and `FieldType` constants live as strings
in the JSONB (and, from V13, in room rows), so renaming or removing one needs a data migration, exactly like a persisted enum column. **Rolling back is
lossy across a definition-shape change:** the read is tolerant (`ignoreUnknownKeys`) but the PUT replaces the WHOLE document from the DTOs, so an older
build that reads a newer document and saves it drops the members it does not know. The list counts (`statusCount`, `fieldCount`) are computed in SQL
(`jsonb_array_length`, 0 for a missing or non-array member), so one undecodable document cannot 500 the list.

### Rooms (V13)

The whole room aggregate in ONE migration, so a room never exists without its workflow: `rooms (id, name, description, archived_at, workflow_revision, last_activity_at,
created_at, updated_at, marked_as_deleted)`, `room_members (room_id, user_id, role, added_at)`, `room_statuses` and `room_fields` (the room's own copy of a template's statuses
and fields; `position` is the array index, retired rows keep a stale one). Enum-valued columns (`role`, `colour`, `category`, `type`) carry no CHECK: the Kotlin enum is the
whitelist (the V5 rule), and the enum NAMES are persisted values, frozen like the template document's. Timestamps are epoch-millis `BIGINT`; ids are `SERIAL` (a
`room_id` is `INTEGER`, referencing `users` through `BIGINT`, the teams wrinkle above).

- **`rooms` is soft-deleted with a partial unique name** (`uq_rooms_name_active`, `LOWER(name)` over non-deleted rooms; an archived room keeps its name, a delete frees it;
  the 409 wording is in `UNIQUE_CONSTRAINT_DETAILS`). A clash therefore discloses only that a room of that name exists. `archived_at` (null = active) is state, not deletion.
- **`idx_rooms_last_activity`** (`last_activity_at`, partial over non-deleted rooms) serves the recent-activity sort of the rooms rail and the Rooms page.
- **`room_members` is a hard-delete join** (the `team_members` class: the audit trail holds adds and removals, a re-add must not collide with a dead row, nothing references
  a membership row). A soft-deleted user keeps an inert row; every read resolves members through ACTIVE users (the inner join), and the last-OWNER count (C8) does too (a role change or removal of a soft-deleted user's row is a `404`: it is not an active member).
- **`room_statuses` and `room_fields` are soft-deleted ("retired") with a FULL primary key `(room_id, key)`, deliberately NOT partial**: this is the documented exception to
  "free a unique field on delete". A retired key stays reserved for the life of the room and is revived, never minted twice, so a later thread's history and its
  `(room_id, status_key)` foreign key stay unambiguous. `uq_room_statuses_initial` is a partial unique index (`WHERE is_initial`): exactly one initial status per room, checked
  per row, so a write that moves the initial flag must clear it on every row first (C9).
- **`workflow_revision`** is the `X-Expected-Revision` token of the room workflow's PUT (C9; `BIGINT`, `CHECK > 0`, starts at 1), separate from the room row's own
  `updated_at`: a name edit never moves it.
- **The room lock.** Every write to a room goes through `withRoomWrite` (`rooms/RoomLock.kt`), which selects the ACTIVE room row `FOR NO KEY UPDATE` (the rule of "Row locks vs. FK
  locks" below: child inserts such as members and statuses take `FOR KEY SHARE` on the room, which does not conflict; 0.3 thread and message writes run under `withRoomWrite` themselves, because posting bumps `rooms.last_activity_at` and a lighter lock upgraded by that UPDATE would deadlock two concurrent posters), then re-reads the caller's membership
  and re-runs the route's guard on that locked standing, then runs the write. The role is read AFTER the lock is held (READ COMMITTED gives every statement its own snapshot), so a
  role change that committed while the caller waited is the verdict. The lock transaction also re-reads the CALLER's `users` row (active flag and role): a caller deleted while the write waited is the `404`, an ADMIN demoted meanwhile is judged as a plain user (the token's roles are only a hint); the membership role read joins ACTIVE users. A hard delete of a room (tests only) cascades to members, statuses and fields.
  **The wait for the lock is bounded** (the 0.3.0 security audit's M1): a writer queued behind the room holds a pooled connection while it waits (20 of them, a 10 s acquire timeout), so unbounded waiters could stall every request. `withRoomWrite` therefore runs its first two statements as `SET LOCAL lock_timeout = <rooms.lockWaitMillis>` (default 3000 ms, `$ROOM_LOCK_WAIT_MILLIS`, boot-validated 100..30000 in `configureDatabase` through `requireConfigInt`, recorded per `R2dbcDatabase` by `useRoomLockWait`; a database nobody configured waits the default) and the lock select, and, once the lock is held, `SET LOCAL lock_timeout TO DEFAULT`. A wait that outruns the bound raises SQLSTATE `55P03` (`lock_not_available`; `Throwable.isLockNotAvailable()` in `ErrorHandling.kt`), which `withRoomWrite` alone catches and turns into the audited `room.busy` plus the typed `429 room-busy` with `Retry-After: 1` (`rooms/RoomProblems.kt`; the failed statement aborts the transaction, which rolls back). `lock_timeout` is NOT `statement_timeout`: the "only `queryTimeout`" rule of "Connection pool" stays, and `StatementTimeoutCache` neither sees nor needs to see this setting. `SET LOCAL` lasts to the end of the transaction (commit or rollback), so nothing can leak to the next borrower of the pooled connection, whatever the outcome; lifting it right after the lock is held keeps every other wait of the write (a unique-index wait on a room name, an FK `KEY SHARE` behind a role change) off the bound, so `55P03` can only come from the one place that maps it. `RoomBusyTest` pins it on a ONE-connection pool (the answer at the bound, `SHOW lock_timeout` = 0 afterwards and inside the block).
- A room's labels are plain single-language text in `label` (`VARCHAR(60)`); `room_fields.options` is a `jsonb` array `[{key,label}]` written through `canonicalJson`
  (`RoomWorkflowService.copyFromTemplate`) and read with a tolerant decoder; `[]` for a kind without options.

### Credential revision (V7)

`users.credential_revision` is an internal nonnegative BIGINT generation, initially zero. Every
password update/reset and conditional bootstrap rotation increments it atomically in SQL with the
hash update. It is carried in signed access and refresh tokens and pending MFA challenges; the per-request access
check (`TokenBlocklistService.accessVerdict`), refresh and MFA acceptance compare it with the current user row.
Soft delete and role changes do NOT bump it (the access check compares the stored role separately; a delete
is `user_gone`). It is not exposed in user DTOs. A password-reset grant (V8) captures it and goes stale when it moves.
`password_changed_at` remains a timestamp, not an authorization boundary.

### Password reset tokens (V8)

`password_reset_tokens`: single-use grants, only the SHA-256 digest of the emailed token is stored (`token_hash` PK), with the
account snapshot (`email`, `credential_revision`) the link was issued against and `expires_at`; `user_id` references `users` with
`ON DELETE CASCADE`. A hard-delete table (expiring credentials, not history — see "Soft delete (convention)"); expired rows are
pruned opportunistically.

### Telemetry (V9)

`ops.telemetry_processes` and `ops.telemetry_samples` (V9; `telemetry/TelemetryStore.kt`, `.claude/docs/observability.md` "Telemetry")
are the tables of the `ops` schema (created by this migration, the "no standalone create-schemas migration" rule). A process row is one
JVM run (`role`, `instance`, `started_at`, `last_seen`, `build_version`/`build_commit`, `processors`, `heap_max_bytes`, nullable
`container_limit_bytes`; unique on `(role, instance, started_at)`; `role` is the constant `"app"` — Adder is one process; the column stays for a future split);
`telemetry_samples` is ONE narrow table for every metric —
`(resolution, metric, ts, process_id, label)` is the primary key (`resolution` = the sample width in seconds: a raw row stores the sampler's real interval, 60..1800 and a divisor of 3600, an hourly rollup 3600 - the tier is `resolution < 3600`; the CHECK enforces it), `n`/`avg`/`max` the figures, so a metric is added in
`TelemetryMetrics.kt` with no migration. Epoch-millis `BIGINT` timestamps, `SERIAL` ids, the cluster section's rows (its marker today) attributed to
the process that took the lock. Writes go through `infra/db/MultiRowInsert.kt`'s `insertRowsIgnoringConflicts`; every statement runs under
the transaction's `queryTimeout`.

**The telemetry prune is a hard-delete exception**: samples are regenerated, high-volume observations with no reader once old, so they
are deleted, not soft-deleted — raw rows older than 7 days, hourly rows older than 90, processes unseen for 90 days (their samples
cascade, `ON DELETE CASCADE`; an index on `process_id` keeps that cascade off a table scan). The prune runs inside the telemetry cluster
transaction under `TELEMETRY_LOCK_KEY`, and a row exactly at its cutoff stays. The tests own the schema:
`TelemetryStoreTest`/`TelemetrySamplerTest` empty `ops` before each test (the suite starts no sampler).

### Soft delete (convention)

`users`, `teams`, `workflow_templates`, `rooms` and `messages` are **soft-deleted** (and a room's `room_statuses`/`room_fields` are retired, never removed: "Rooms (V13)"; `threads` are never deleted in 0.3 and `thread_events` is append-only: "Threads (V14)") — rows are flagged, never physically removed; every
business entity follows the same convention. Only join/token tables (today: `revoked_tokens`, a
pure token registry, `password_reset_tokens` (V8, expiring single-use credentials, not history), `user_disabled_features`, a pure flag join
whose PUT is a wholesale replace, `room_members` (V13, the room membership join, the same reasoning), `team_members` (V10, the roster join: the audit trail holds the adds and removals, a re-add must not
collide with a dead row, nothing references a membership row; a soft-deleted user keeps an inert row that reads skip), `message_tags` (V15, a DERIVED index rebuilt from the bodies: "Hashtags (V15)"), `message_mentions` (V17, the same reasoning: "Mentions (V17)"), `notifications` (V18, a pointer that goes with what it points at and by retention: "Notices (V18)"), `room_pins` (V21, a pointer, removed by unpinning and with its message: "Pins (V21)"), `thread_reads` (V19, a member's view state overwritten in place: "Read state (V19)"), `user_notification_preferences` (V22, a flag join with a wholesale-replace PUT, the `user_disabled_features` class: "Notices (V18)"), and the `ops.telemetry_*` samples (the prune above)) hard-delete — a new
hard-delete table needs a documented justification, exactly like Lettuce's exceptions list. Pure operational history with no reader once
old (job queues, samples) may be pruned by a fixed documented retention window, the same shape as the JWT blocklist's opportunistic
pruning of expired `revoked_tokens` rows. To add soft-delete to a new entity, follow the established pattern
(reference implementation: `users/UserService.kt`):

1. **Migration** — `marked_as_deleted BOOLEAN NOT NULL DEFAULT FALSE` in the CREATE (a retrofit
   adds the column plus `CREATE INDEX idx_<t>_marked_as_deleted ON <t>(marked_as_deleted);`).
2. **Exposed table** — declare the table `: UIntIdTable("…"), SoftDeletable` with
   `override val markedAsDeleted = bool("marked_as_deleted").default(false)`; the ONE `active()`
   predicate, `nowMillis()` and `lockActiveForWrite` come from `infra/db/SoftDelete.kt` (no
   private copies).
3. **Filter every read** — `read`, `list`, `count`, and any lookup (e.g. `findWithIdByEmail`) get
   `… and active()`. Apply it in the shared list predicate so the `count()` (total) and the row
   select stay consistent.
   **The one tombstone exception (`messages`, V14):** a deleted message is still LISTED (`MessageService.list` does not apply `active()`: the body is scrubbed to `''`, `deleted_by` is set, the row answers `body: null, deleted: true`), so the conversation keeps its shape and `message_count` never goes down. Everything else skips it: the single read, the edit and the delete (`MessageService.read`, `authorOf`, `withMessageWrite`) filter on `active()` and a deleted message is `404` there.
4. **`delete` flips the flag** — `update({ (id eq id) and (markedAsDeleted eq false) }) { it[markedAsDeleted] = true }`,
   returning the affected-row `Int`; guard `update` mutations the same way. The route maps
   `0 → 404` (the `orNotFound` helper), so a missing-or-already-deleted row is `404` (not `204`)
   and delete stays idempotent in effect — `UserService.deleteGuarded` shows the shape, adding the
   last-admin check inside the same transaction.
5. **Routes need no special-casing** — they key `404`/`204`/`NoContent` off the row-count and the
   `active()`-filtered `read`.

**Freeing a unique business field on delete.** To let a value be reused once its holder is
soft-deleted, use a **partial unique index** over active rows instead of a global `UNIQUE`:
`CREATE UNIQUE INDEX uq_<t>_<col>_active ON <t>(<col>) WHERE NOT marked_as_deleted;`. Skip the
Exposed `.uniqueIndex()` on that column (Exposed defs are query-only — the DB enforces it). A
clash with an **active** row still raises `23505 → 409` (mapped centrally in
`plugins/ErrorHandling.kt`, which names WHAT clashed per constraint — extend
`UNIQUE_CONSTRAINT_DETAILS` when adding a partial unique index). In place today: `users.email`
(`uq_users_email_active`, `V1`) and the team name (`uq_teams_name_active`, `V6` — an expression
index over `LOWER(name)`), the workflow template name (`uq_workflow_templates_name_active`, V11) and the room name (`uq_rooms_name_active`, V13).

**`infra/db/Sql.kt`** (ported from Lettuce with the first list endpoint): `containsNormalized` —
the case- AND accent-insensitive substring filter over `public.unaccent` (V4); every per-column
substring filter MUST use it. Also `orVanished` (post-commit read-back guard → 500, for every
create that reads its row back).

### Row locks vs. FK locks

PostgreSQL's foreign-key check takes `FOR KEY SHARE` on the REFERENCED row for every child insert and keeps it until the
inserting transaction ends. A long transaction whose inserts reference a parent row therefore holds key-share locks on every parent it
touched for its whole run; `SELECT … FOR UPDATE` on that parent conflicts with `FOR KEY SHARE` and would wait behind it (Flow hit this
with its long-running derive job against team rows; the chat writes — threads and messages referencing a room — are the same shape).

The rule: **lock a row `FOR NO KEY UPDATE` unless the write changes its key.** That mode conflicts with itself and with
`UPDATE`/`DELETE` (writers of the row, e.g. a soft delete racing a child insert, still serialize) but
NOT with `FOR KEY SHARE`, so FK locks never stall it. `lockActiveForWrite` (`infra/db/SoftDelete.kt`,
`FOR NO KEY UPDATE`), `withRoomWrite` (the room row, every room-scoped write; the thread and message inserts reference it), `TeamService.update` and `PasswordResetService.lockUser` (the active `users` row) follow it; none of them touches
`id`, and the unique indexes on these tables are partial or expression indexes (`uq_teams_name_active`, `uq_users_email_active`), which
PostgreSQL does not count as key columns — a rename is still a non-key update. Plain `FOR UPDATE` is for a row nothing references from a
long transaction (`users` is referenced only by columns no long transaction inserts — `updated_by_*`, `teams.manager_user_id`,
`user_disabled_features.user_id`, `password_reset_tokens.user_id`, and the V14 columns `threads.owner_id`/`created_by`, `messages.author_id`/`deleted_by`, `thread_events.user_id` (short inserts only): `UserService.lockedActiveAdminCount`), or for
`SKIP LOCKED` claims. A new `FOR UPDATE` on an FK-referenced row needs that justification in a comment. Flow pinned both halves with `RowLockVersusDeriveTest` (a writer completes while another transaction holds `FOR KEY SHARE` on the row, and a second `FOR NO KEY UPDATE` writer still waits); Adder re-adds that test with the first long chat transaction.

### `infra/db/Jsonb.kt` and canonical JSON

`infra/db/Jsonb.kt` — a repo-local `jsonb` column type, because `exposed-r2dbc` 1.5.0 ships no
JSON column type of its own. It needs no reflection into the raw `io.r2dbc.spi.Statement`: Exposed
already ships a `JsonColumnMarker` interface plus a `PostgresSpecificTypeMapper` that specifically
recognizes any `IColumnType` implementing it (binding a null via `Statement.bindNull(index,
Json::class)`, a `String` via `Statement.bind(index, Json.of(value))` —
`io.r2dbc.postgresql.codec.Json`) — implementing the marker IS the whole binding. Values are the
caller's JSON text verbatim; PostgreSQL's own `jsonb` storage reformats regardless (whitespace, key
order), so "round-trips" means the same VALUES survive, not the same bytes. **The one exception to "same values" is U+0000**:
PostgreSQL `jsonb` and `text` cannot store a NUL (`unsupported Unicode escape sequence`, SQLSTATE 22P05 for jsonb; `22021` for
text). Every payload that goes through `canonicalJson` (`infra/json/CanonicalJson.kt`) therefore has each U+0000 in a string value AND
in an object key replaced with U+FFFD, before the text is hashed, so the stored text and its sha256 agree (U+FFFD marks that something
was there and cannot merge the tokens around it, which dropping the character would). The helper `String.replaceNul()` (same file)
applies the same rule to free text bound for a `text` column that is not a canonical JSON payload (a database exception's message can
quote the offending value). Covered by `JsonbColumnTest` (insert/select/update, null, nested objects/arrays) and `CanonicalJsonTest`.

`infra/json/CanonicalJson.kt` — canonical JSON for anything stored through `Jsonb.kt`: object
keys sorted RECURSIVELY (arrays keep their own order — position is meaning), rendered via
kotlinx.serialization's compact `JsonElement.toString()`, plus a `sha256Hex` digest of the result.
Two payloads that differ ONLY in object key order canonicalize to byte-identical text, so their
digests agree — this is key-order independence, not general structural equality: canonicalization
does not normalize number spelling (`1`, `1.0` and `1e0` stay distinct token text). The output must stay byte-stable, since stored
text and sha256 are compared against stored rows.

### Threads (V14)

The whole thread aggregate in ONE migration (`V14__create_threads.sql`, whose header comment states the delete class of each table): `threads (id, room_id, number, title, status_key,
owner_id, created_by, fields, revision, message_count, closed_at, last_activity_at, created_at, updated_at)`, `messages (id, thread_id, author_id, body, created_at, edited_at,
marked_as_deleted, deleted_by)` and `thread_events (id, thread_id, user_id, created_at, event_type, params)`. Ids are `SERIAL`, timestamps epoch-millis `BIGINT`, user references
`BIGINT` (the V1 wrinkle). `event_type` carries no CHECK (the Kotlin enum is the whitelist, the V5 rule), and its NAMES are persisted values, frozen like the template document's.

- **Delete classes.** `threads`: never deleted in 0.3 (a thread is closed, not removed; a room's soft delete hides its threads with it). `messages`: soft delete
  (`marked_as_deleted`), because a deleted message stays listed as a tombstone: the body is scrubbed to `''` and `deleted_by` records who removed it (D2: the author, a room OWNER or an
  ADMIN; set on EVERY delete). Three CHECKs pin the shape: a tombstone has an empty body and a `deleted_by`, a live message has a body (1 to 8,000 characters) and none (`ThreadSchemaTest`). `thread_events`: append-only (Toadie's `catalog_file_events` shape: no update, no delete; `user_id` is `ON DELETE RESTRICT`).
  A room's hard delete (tests only) cascades `rooms` to `threads` to `messages` and `thread_events`.
- **The composite status foreign key** `fk_threads_status (room_id, status_key) -> room_statuses(room_id, key)` keeps the default `NO ACTION` (checked at statement end). A room hard delete cascades into `room_statuses` and `threads` in one statement (`TestRooms.purge`); `ThreadSchemaTest` pins that the cascade passes (it passes under `RESTRICT` too on current PostgreSQL, so the choice is the conservative default, not a requirement). It is a backstop: every
  status-setting write holds the room lock and reads the status inside it, so a status in use cannot be retired under a thread write, and statuses are retired with a soft delete
  (never hard-deleted), so the FK can only fail when a write names a status that does not exist: a bug, a 500 (`ErrorHandling.kt` maps only 23505, the unique violation, and 22021, a character the encoding cannot hold; a foreign-key violation 23503 is not mapped).
- **Per-room numbers.** `uq_threads_room_number (room_id, number)`; `MAX(number) + 1` under the room lock, never reused. A clash (a lock bug) is a 23505, mapped to 409.
- **`closed_at` mirrors the status category** (set on a move into a CLOSED status, cleared on a move into an OPEN one) and stays exact because a status in use cannot change category and a
  replacement must have the same one. `message_count` counts every message, tombstones included, and never goes down. `fields` is a `jsonb` object (`CHECK jsonb_typeof = 'object'`) of
  field key to value; retired fields keep theirs. `revision` is the `X-Expected-Revision` token of the details PUT.
- **FK locks on `users`.** The references to `users(id)` (`threads.owner_id`, `threads.created_by`, `messages.author_id`, `messages.deleted_by`, `thread_events.user_id`) take `FOR KEY SHARE` on
  the actor's row for the inserting transaction. The last-admin `FOR UPDATE` on admin rows conflicts with it only for the length of a role change: the "short inserts only" justification of
  "Row locks vs. FK locks".
- **Indexes:** `idx_threads_room_activity (room_id, last_activity_at)` (the list order), `idx_threads_room_status (room_id, status_key)` (the usage counts), `idx_messages_thread (thread_id, id)`
  (the keyset list), `idx_thread_events_thread (thread_id, created_at, id)` (the history's `-createdAt,-id` page).
- The Exposed tables are nested in their services (`ThreadService.Threads`, `MessageService.Messages`, `ThreadEventService.ThreadEvents`); S2 ships the thread list, read and create and the history read; S5 adds `MessageService`'s list (tombstones included, `id[lt]` keyset), single read, post, edit and delete.
- **`MessageService` (S5)** reads `UserService.Users` for author names (soft-deleted accounts included) and writes only `messages`, its own thread's `message_count`/`last_activity_at` and (V15) the message's `message_tags` rows; every write is ONE `withThreadWrite` / `withMessageWrite` under the room lock (`ThreadLock.kt`), the message read joined to its thread with `threads.room_id` = the path's room, so a message of another room is a `404`. A post updates `messages`, `threads` (`message_count + 1`, `last_activity_at`) and, through `RoomService.touchActivity`, `rooms.last_activity_at` on ONE stamp; an edit sets `body`, `edited_at` and `tags_version` only; a delete scrubs the body and sets `marked_as_deleted` and `deleted_by`, and neither bumps the count or the activity.
- **The follower seam.** Whatever is derived from a message's body or lifecycle is written by a `MessageFollower` (`threads/MessageFollower.kt`, `internal`), never by a new line in `MessageService`: a post, a thread's opening message (`insertInTransaction` takes the thread's number for the `MessageRef`), an edit and a delete each call every follower INSIDE the write under the room lock, AFTER the row's own statement, the thread and room counters and the write's own live hint (`message.posted`, `thread.created`, `message.edited`, `message.deleted`), so a follower that throws rolls the message back, a follower may read the new row, and a hint a follower emits (`notification.created`) follows the write's own on the wire. `MessageService.insertInTransaction` inserts the row and its tags and answers an `InsertedMessage`; the caller runs `followPosted` once its counters and hint are written (the post and the thread create both do, `MessageDerivedRowsTest` pins both). `posted` and `edited` answer the ids of the people the follower notified; the service hands their union to the route (`PostedMessage`, `MessageEdit`, `CreatedThread` carry it as `notified`, never in a response body). The hashtag index (V15) is still `MessageService`'s own call; the mention index (V17) is the first follower (it also calls the `ThreadNotices` port, so a mention's notice is made in the same transaction) and `MessageService` always runs it before any other; a table that references `messages` is cleared by a follower, and `MessageDerivedRowsTest` asks `pg_constraint` which tables those are and asserts a deleted message has no row in any. An edit whose body is the same as the stored one calls no follower (and writes no hint): there is nothing to re-derive. The body parsers share one Markdown pre-pass, `threads/MarkdownText.kt` (`textOutsideCodeAndLinks`).

### Hashtags (V15)

`V15__create_message_tags.sql`: `message_tags (message_id, thread_id, room_id, tag)`, primary key `(message_id, tag)`, all three foreign keys `ON DELETE CASCADE`, `tag VARCHAR(150)` with `CHECK (char_length(tag) >= 1)`, ONE index `idx_message_tags_room_tag (room_id, tag, thread_id)`; and `messages.tags_version SMALLINT NOT NULL DEFAULT 0` (no CHECK). The table is a DERIVED index of the hashtags in LIVE message bodies (`chat-model.md` "Hashtags (V15)" owns the grammar and the lifecycle).

- **Hard delete, justified.** Nothing references a row, it is rebuilt from the bodies at any time, and a deleted message's tags must not outlive its scrubbed body: a soft-delete flag would keep `#the-leaked-thing` findable. A moderator's delete clears the rows in the delete's own transaction; the cascades take them with a message, a thread or a room (a test purge).
- **The folded key** is `btrim(left(lower(public.unaccent(<kotlin-lower-cased tag>)), 150))`, the same expression on write and on filter (`foldedTagKey`), so a key that folding lengthened past 150 characters (a vulgar fraction becomes up to five: `⅒` is `" 1/10"`, so a 50-character tag can fold to 246) is cut on both sides and still matches itself: an overflow never fails a message write. `unaccent` puts a SPACE before a fraction (`½` is `" 1/2"`), and the cut can end on one, so the key is trimmed: a key never starts or ends with a space (the filter trims its values).
- **`tags_version`** is the grammar version a message's tags were written under (`TAGS_VERSION`, 1; 0 = never indexed). The constant default adds the column without rewriting `messages`, and V15 puts NO CHECK on it (only code writes it, and a new CHECK would scan the whole table under an exclusive lock). Every write of a body sets it; the boot reindex takes the live messages below the current version.
- **The reindex row lock.** `MessageTagService.reindexStale` selects batches of 200 `FOR NO KEY UPDATE OF messages SKIP LOCKED` (the thread rows stay free for the writers), ordered by `id` and keyset-paged (the primary key, no index on the version): a message a writer holds is skipped (the writer sets its own version) and found by the next boot; a writer arriving while a batch holds its row waits for the batch's commit. A batch is one delete and ONE `INSERT ... SELECT ... FROM unnest(<four parallel arrays>) ON CONFLICT DO NOTHING` (the statement text is constant), so the row locks are held for one round trip, not thousands. An edit and a delete run the message's row UPDATE BEFORE touching its tags: that statement is where they wait out a reindex that holds the row, and the tags they then write or clear are the last word (`MessageTagReindexTest` pins the order).
- **One index, chosen by `EXPLAIN`.** Both reads start at one room, so `idx_message_tags_room_tag (room_id, tag, thread_id)` serves them: on a throwaway postgres:18.6 with 100 rooms x 500 threads x 4 messages and ~99,000 tag rows (a room holds ~1,000 of them), the tag list is `Index Only Scan ... Cond: room_id = ?` (0 heap fetches after a VACUUM, 0.2 ms, the grouped count and the substring filter on the same scan), and the thread filter is `Index Scan ... Cond: room_id = ? AND tag = ANY(...)` feeding a semi-join with the room's threads (0.1 to 0.5 ms). A `(thread_id, tag)` index would only serve a per-thread probe, and with the room predicate nothing needs one: there is none, and the `thread_id` foreign key has no supporting index either (a thread is never deleted; only a test purge cascades through one).
- **Rollback below 0.5.0 (runbook).** A 0.4.x binary knows nothing of `message_tags`: a message it DELETES keeps its tag rows, and a message it EDITS keeps its old tags at `tags_version` 1, so neither is seen by the reindex of live messages below the current version. The boot clears the first case by itself (`MessageTagService.purgeDeletedTags`, batches of 1,000 messages, before the reindex, at every boot whatever `threads.tagReindexOnBoot` says); the second needs `UPDATE messages SET tags_version = 0;` before the next 0.5.0 boot (the reindex then rebuilds every message's tags from its body). **Release-notes line:** *If you roll back from 0.5.0 to 0.4.x and later upgrade again, run `UPDATE messages SET tags_version = 0;` before starting 0.5.0, so hashtags of messages edited under 0.4.x are rebuilt (tags of messages deleted under 0.4.x are removed automatically).*
- **No trigger, no SQL parsing.** The parser is Kotlin (`threads/Hashtags.kt` over the shared pre-pass `threads/MarkdownText.kt`): PostgreSQL's regex has no `\p{L}`, its classes follow the database locale, and SQL cannot skip code the way the SPA's Markdown tree does.

### Mentions (V17)

`V17__create_message_mentions.sql`: `message_mentions (message_id, user_id, thread_id, room_id)`, primary key `(message_id, user_id)`, all four foreign keys `ON DELETE CASCADE`, ONE index `idx_message_mentions_user_room (user_id, room_id, thread_id)`. A DERIVED index of the mentions in LIVE message bodies (`chat-model.md` "Mentions (V17)" owns the grammar and the lifecycle); its header comment states the delete class.

- **Hard delete, justified.** The `message_tags` reasoning: nothing references a row, the message's own write rebuilds it from the body, and a deleted message's mentions must not outlive its scrubbed body. `MessageMentionService` (a `MessageFollower`) clears the rows in the delete's own transaction, a moderator's too; a message deleted by a 0.5.x binary after a rollback is cleared by the boot purge `StaleDerivedRows` ("Rollback below 0.6.0"); the cascades take them with a message, a thread, a room or a user (a test purge).
- **No backfill, no version column.** A message written before V17 has no row, so an old literal `<@42>` stays plain text and no reindex exists. `thread_id` and `room_id` are copied from the message's thread (a message never changes thread, a thread never changes room), so the filter starts at one person in one room.
- **The edit diffs against its own rows, not against the old body**: a follower runs after the row was overwritten and sees only the new body. A person already indexed stays mentioned while the token stays, whatever happened to their membership since; a person not indexed yet is added only when an ACTIVE member of the room; an unchanged set writes nothing.
- **Names are not stored**: a read joins `users`, soft-deleted accounts included, so a rename shows at once and a mention of someone who left keeps the name.
- **Rollback below 0.6.0 (runbook).** A 0.5.x binary starts against a V17+ database and knows nothing of `message_mentions`, `notifications`, `message_reactions` or `room_pins`. Every table that references `messages` (`pg_constraint`, as `MessageDerivedRowsTest` asks) is `message_tags` (V15, handled by its own runbook above), `message_mentions`, `notifications`, `message_reactions` and `room_pins`, and none of them is cleared by a 0.5.x write: a message it DELETES keeps its mention, notice, reaction and pin rows, and a message it EDITS keeps its old mention rows and the notices made for them. The boot clears the first case by itself (`StaleDerivedRows.purge`, in the `MessageTagReindex.kt` boot task right after `purgeDeletedTags`, at every boot whatever `threads.tagReindexOnBoot` says: batches of 1,000 deleted messages that still carry a row, `FOR NO KEY UPDATE OF messages SKIP LOCKED`, the four tables deleted by one statement per batch; `StaleDerivedRowsTest`). The second needs SQL BEFORE restarting 0.6.x, because nothing can tell an edited message's mentions from the body (there is no version column and no reindex): with `<rollback start>` the epoch milliseconds at which the first 0.5.x binary started,

  ```sql
  DELETE FROM notifications WHERE notification_type = 'MENTIONED' AND message_id IN (SELECT id FROM messages WHERE edited_at >= <rollback start>);
  DELETE FROM message_mentions WHERE message_id IN (SELECT id FROM messages WHERE marked_as_deleted OR edited_at >= <rollback start>);
  ```

  The first line drops the mention notices (`notification_type = 'MENTIONED'`, the V18 column; a `THREAD_ASSIGNED` notice has no message and is not touched) made for those messages. It cannot tell a notice for a mention that the 0.5.x edit removed from one whose mention still stands, so a notice (seen or not) of someone who is STILL mentioned in an edited message is lost too, and because the second line also drops that message's mention rows, a later 0.6.x edit of it finds no row for anyone and notifies everyone it mentions again. The second drops their mention rows, and the deleted messages' too (the boot purge would do that half itself, it finds a row in any of the four tables, so the order of the two lines does not matter). A message edited under 0.5.x then shows its tokens as plain text (and is not found by `mentionedUserId`) until it is edited again under 0.6.x; reactions and pins of a LIVE message are still true and stay. Messages posted under 0.5.x carry no row either way (a literal `<@42>` stays text, as before V17). **Release-notes line:** *If you roll back from 0.6.0 to 0.5.x and later upgrade again, run the two `DELETE` statements of "Rollback below 0.6.0" (`persistence.md`) with the time the rollback started before starting 0.6.0, so mentions of messages edited under 0.5.x are not shown stale (rows of messages deleted under 0.5.x are removed automatically).*
- **Read state across a round trip through 0.5.x (V19).** A 0.5.x binary writes no marker and does not move `seen_at`: after the way back up, a post written under 0.5.x has no marker of its author's, so it shows as unread to its own author (and the room's dot may be set for the actor); the next read marker or "room seen" clears it. Nothing is lost or wrong beyond that; no SQL is needed.
- **One index, chosen by `EXPLAIN`** on a throwaway postgres:18.6 (900 threads, 87,250 messages): the filter's `EXISTS` is a `Bitmap Index Scan on idx_message_mentions_user_room` semi-joined with the room's threads, 0.09 ms in the generic plan.

### Notices (V18)

`V18__create_notifications.sql`: `notifications (id, recipient_id, room_id, notification_type, actor_id, thread_id, message_id?, created_at, seen_at?)`, ids and stamps ONLY (no name, title or body column: `chat-model.md` "Notifications (V18)" owns the rules, `security.md` "Notices" the leak rule). Foreign keys: `actor_id`, `thread_id` and `message_id` `ON DELETE CASCADE`; **`fk_notifications_member (room_id, recipient_id)` references `room_members (room_id, user_id)` `ON DELETE CASCADE`**. Indexes: `idx_notifications_created (created_at)` (the retention purge), `idx_notifications_recipient (recipient_id, id)` (the drawer, newest first, a backward scan), `idx_notifications_unseen (recipient_id, id) WHERE seen_at IS NULL` (the badge) and the unique `uq_notifications_message (message_id, recipient_id, notification_type) WHERE message_id IS NOT NULL` (the insert is `ON CONFLICT DO NOTHING`; a hand-over has no message, so each is its own row). The header comment states the delete class.

- **Hard delete, justified.** A notice is a POINTER, not a record: it goes with what it points at and with the membership it was made for. A message is only soft-deleted, so the message's own write removes its notices by hand (`ThreadNotices.messageRemoved`, a moderator's delete too; an edit that drops a mention calls `mentionsDropped`); the cascades take them with a thread, a room, a user (a test purge) and a message row (the one other path: the boot purge `StaleDerivedRows` removes the notices of messages a 0.5.x rollback deleted without calling the port, see the cross-feature list); **the membership key is the leak rule in the schema**: removing a member (a leave, a removal) deletes that person's notices of the room in the removal's own transaction (bounded by one person's rows), and rejoining starts clean. The third way out is retention: `NotificationSweep` deletes rows older than `notifications.retentionDays` (90, 1..3650) at most once an hour, in its OWN scope and transaction after `ApplicationStarted` (`notifications.sweepEnabled` is false in the test harness, `sweepIntervalSeconds` 10..3600 is the tick), never inside a chat write (those hold the room lock).
- **A notice is made inside the write that caused it**, not after it: the insert and its `notification.created` hint commit with the post, the edit or the hand-over, so a rolled-back write announces nothing. The FK needs the recipient to be an ACTIVE member (the mention follower and the owner check already guarantee it; any other value is a 23503, never mapped, so a bug is a 500, not a silent notice).
- **No CHECK on `notification_type`** (the V5 rule): the Kotlin enum `NotificationType` is the whitelist, its names are persisted and frozen, and the list predicate `notification_type IN (<known names>)` keeps a name this build does not know invisible and uncounted.
- **Retention is batched and cap-counted.** `NotificationService.purgeOlderThan` deletes in batches of 1,000 (`PURGE_BATCH`), each its own transaction (`LIMIT 1000 FOR UPDATE SKIP LOCKED`, the `MessageTagService.reindexStale` shape), `ORDER BY created_at` straight off `idx_notifications_created` (the one index a retention purge needs: a binary search over the primary key on "ids follow time" was tried and dropped, because it skips an old row that sits behind a younger one). The list's count is `LIMIT 1001` inside (`MAX_COUNTED_NOTICES`), so `total` is capped at 1000, and a page starting at or past that cap is empty with no OFFSET read. `markAllSeen` updates in chunks of 1,000 ids (`SEEN_BATCH`), each its own short transaction, bounded to the newest id at its start. The insert is one `INSERT ... SELECT FROM unnest(CAST(? AS int8[])) ... ON CONFLICT DO NOTHING RETURNING recipient_id` with constant text.
- **The list's statements**: one predicate for the count and the rows in ONE transaction (`recipient_id = ? AND room_id = ANY(CAST(? AS int4[])) [AND seen_at IS (NOT) NULL] AND notification_type IN (...)`), the rooms first from `RoomService.memberRooms`. Measured on a throwaway `postgres:18.6-alpine` (200,000 rows, generic plans): the badge page 0.06 ms (`idx_notifications_unseen`), the drawer page 0.02 ms (`idx_notifications_recipient`, backward), the drawer's count 1.2 ms for a person with 3,390 rows, "seen" for one thread 0.1 ms. "Seen" is `UPDATE ... WHERE id = ? AND recipient_id = <caller> AND seen_at IS NULL`; a repeat probes the row once instead of rewriting it.

- **The email digest (V22): claim, then send.** `notifications.emailed_at BIGINT` (null until claimed) and the partial index `idx_notifications_email_due (created_at) WHERE seen_at IS NULL AND emailed_at IS NULL` serve ONE claim statement with constant text (`WITH eligible AS MATERIALIZED (... ORDER BY created_at, id LIMIT 5000), people AS MATERIALIZED (... GROUP BY recipient_id ORDER BY MIN(created_at) LIMIT <maxPerRun + max(10, maxPerRun / 5), at most 500>), ranked AS MATERIALIZED (row_number per person), picked AS MATERIALIZED (... FOR UPDATE OF n SKIP LOCKED) UPDATE ... FROM picked RETURNING ..., rn`, per person so a flooded backlog is one slot AND one digest: every due notice of a taken person within the scan is stamped, the rank says which 50 are read for the mail): unseen, unclaimed, older than `notifications.email.delaySeconds`, younger than a day, of an active account, of a kind not switched off for email, not of a person already mailed (or skipped) within `minIntervalSeconds` (`<> ALL(array)`); a constant `WITH scanned AS (the oldest 5000 due rows), doomed AS (... FOR UPDATE SKIP LOCKED) UPDATE ... RETURNING` before it, in the same transaction, settles (stamps) the notices among those 5000 of a switched-off kind or a deleted account. **Both statements are bounded**: the 5000-row scans (`DIGEST_SCAN_CAP`; a person beyond them waits for a later tick; rows the claim's filters reject are still walked to find them, which the settle and the timeout bound) and a 10 s `queryTimeout` on the transaction (`DIGEST_STATEMENT_TIMEOUT_SECONDS`; a cancelled statement is logged scrubbed and the run claims nothing). The cap and the timeout are the real bounds; the people limit bounds only the work AFTER the scan. The claim is its own short transaction and commits BEFORE any send; the send happens outside every transaction, preceded by one short read transaction per recipient that re-checks the account, the notice (still there, still unseen), the room membership and the switches (`NotificationDigest.resolve`), so no pooled connection spans an SMTP call. `user_notification_preferences (user_id, notification_type, channel)` holds the DISABLED pairs (no row = on; no CHECK on either name, the Kotlin enums are the whitelists, a name this build does not know is ignored); the PUT replaces under `FOR NO KEY UPDATE` on the user row. `NotificationDigest` reads the other features' tables inside its re-check: `users` (name, email, language of an active user), `threads` (the number only, never the title), `rooms`/`room_members` through `RoomService.memberRooms`, and `userNames` for the actors; it writes only `notifications.emailed_at`. **Stops and failures:** the per-recipient re-check and the send share one catch (audited, not retried); a run ends after 3 failures in a row or on cancellation and RELEASES the claims of the recipients it never attempted (`UPDATE ... SET emailed_at = NULL WHERE id IN (...) AND emailed_at = <this run's stamp>`, in a `NonCancellable` finally, constant shape, one short transaction). What can still be lost: a recipient attempted when the process dies or is cancelled mid-send, and a release that itself failed; the rule stays at most once.

### Search (V16)

- **What V16 is.** `CREATE TEXT SEARCH CONFIGURATION public.adder_search (COPY = simple)` with `unaccent` (V4) as a filtering dictionary in front of `simple` for the six token types that can hold non-ASCII letters; then `search tsvector GENERATED ALWAYS AS (pg_catalog.to_tsvector('public.adder_search'::regconfig, body | title)) STORED` on `messages` and `threads`, and **no index** (a GIN index on `messages.search` was built here at first and dropped: nothing read it, see below). `to_tsvector(regconfig, text)` is IMMUTABLE, so no trigger and no wrapper function is needed, and the `ALTER TABLE` is the backfill. **`STORED` must be written**: on PostgreSQL 18 a bare `GENERATED ALWAYS AS (...)` is VIRTUAL (computed again on every read; an index on it is not supported either; `ThreadSchemaTest` pins `attgenerated = 's'`). Neither vector has an index (`ThreadSchemaTest` pins that too, and that `idx_messages_thread`, the probe's index, exists): the match is a per-thread probe through `idx_messages_thread`, **linear in the searched rooms' own messages**. A tombstone's body is `''`, so its vector is empty; a token of 8,000 characters is skipped with a NOTICE and the INSERT still succeeds.
- **Never in an Exposed table object.** `selectAll()` would read a vector on every message read and the driver has no `tsvector` codec: `infra/db/Fts.kt` names the configuration and every search expression (`ftsVector`, `ftsMatches`, the query text, the headline) and refers to the columns as free expressions. A plain Exposed `insert` answers `RETURNING *`, which would send the vector (about 1.75x the body: 3 KB for a 1.8 KB body) back on every post, so `MessageService.insertInTransaction` uses `insertReturning(listOf(Messages.id))` (`RETURNING messages.id`; `ThreadSearchTest` pins it through the driver's query log). The thread INSERT still answers `RETURNING *` (its vector is the title, at most 200 characters).
- **Measured** (throwaway `postgres:18.6-alpine`, 200,000 messages of about 1.5 KB, 20,000 threads): the migration's `ALTER TABLE messages` took **14.4 s** (about **7 s per 100,000 messages**; the vectors of 1.76 KB Zipf-distributed bodies came to 3.1 KB each, the table 1.17 GB with its TOAST), `ALTER TABLE threads` 51 ms. The first V16 also built a GIN index (0.5 to 4 s, 82 to 214 MB per 200,000 messages: not built any more, so the backfill is the `ALTER TABLE` alone, about 7 s per 100,000 messages; the figures are the measured `ALTER TABLE`s, not re-run without the index, which only subtracted its build). The application is not serving while Flyway runs and the Kubernetes startup probe allows about 150 s, so past about two million messages the probe needs raising. **So does the application's own startup timeout** (`ktor.application.startupTimeoutMillis`, 140,000 ms by default, `application.yaml`): it covers the Flyway run, and at about 7 s per 100,000 messages a database near two million messages (about 140 s) cannot boot before the migration ends. It is overridable with `KTOR_STARTUP_TIMEOUT_MILLIS`; raise it together with the startupProbe's `failureThreshold` (`k8s/app-deployment.yaml`: 30 x 5 s), never one alone, or run the migration once out of band (a one-off pod on the same image) before the rollout. Query times on the 200,000-message table (a three-letter prefix that nearly every message carries is the worst case): a room's count 3 ms and its page 0.1 ms (the planner walks the room's threads and probes `idx_messages_thread`), the snippets for 20 threads 11 ms, a table-wide `messages.search @@ 'kub':*` count 370 ms through the GIN index that existed then (the shape a cross-room search over every room would pay, and why none is used).
- **The thread statements' plan, chosen by `EXPLAIN` (the search across rooms; the room search shares it).** The rooms come first as ONE array argument (`threads.room_id = ANY(CAST(? AS int4[]))`: no bind per room), then `threads.search @@ q OR (SELECT messages.id FROM messages WHERE thread_id = threads.id AND NOT marked_as_deleted AND COALESCE(messages.search, ''::tsvector) @@ q LIMIT 1) IS NOT NULL` (`ThreadSearchService.matchesTitleOrMessage`, `Fts.kt` `ftsRowVector`). Measured on a throwaway `postgres:18.6-alpine`: 1,000 rooms x 20 threads x 10 messages (200,000 messages of about 1.5 KB, 1.1 GB with TOAST, `idx_messages_search` 214 MB), a prefix on 90% of the messages (`kub:*`), a rare pair (`outage:* & happen:*`, 5%) and a word on no message, for a caller in 1, 3, 50 and 500 rooms (10,000 threads, 100,000 messages), the count then the page, as prepared statements in both plan modes. **The first shape, `EXISTS` (the room search's), is rejected:** under an `OR` PostgreSQL may plan it as a HASHED subplan over every matching message of the whole table, and it picked a seq scan of the 1.1 GB table for the common prefix at 500 rooms (1.3 s each for the count and the page; `SubPlan 2 -> Seq Scan on messages (actual rows=180068)`), a cost that grows with the table whatever rooms the caller is in; the other plan mode picked the GIN bitmap (81 ms), so the cost depended on the plan cache. (These runs had the GIN index, `idx_messages_search`, 214 MB; with it gone `EXISTS` can only be that seq scan.) **The plain scalar subquery is rejected too:** its generic plan (a prepared statement's 6th run on a connection, `PREPARED_STATEMENT_CACHE_QUERIES` keeps them server-side) is `BitmapAnd(idx_messages_thread, idx_messages_search)` PER THREAD, a table-wide bitmap per probe: over 15 s, cancelled, for the same 500-room caller. **Chosen: the scalar subquery matched on `COALESCE(search, ''::tsvector)`**, which no index can serve (the guard for the day an index on the vector exists), so the plan is `Bitmap Heap Scan on threads (room_id = ANY)` with `SubPlan 1 -> Index Scan using idx_messages_thread` and a filter, identical in the custom and the generic plan, bounded by the caller's OWN threads and stopping at a thread's first match. Count + page, in ms (custom plans; the generic plan's are the same or lower, 500 rooms 94+74, 595+584, 745+673): 1 room 1.5+0.3 to 3.4+2.3; 3 rooms 2+0.9 (common), 7+4 (rare), 6+4 (no match); 50 rooms 26+9, 101+56, 88+73; **500 rooms 159+86 (common), 887+643 (rare), 1,017+788 (a word on no message, the worst case: no early exit, about 7 to 10 microseconds per message of the caller's)**, 95+75 for a mid prefix. The worst case is linear in the CALLER's messages, not the table's, and the 5 s budget bounds it; a caller in thousands of rooms with a word nobody wrote would time out (the typed `429`), which is what the index follow-up in `BACKLOG.md` is for (`btree_gin` over `(thread_id, search)`, or a `room_id` on `messages`). **The GIN index was then dropped from V16 (before any deployment, so the file could change):** with this plan no statement reads `idx_messages_search`, and it cost 214 MB per 200,000 messages plus write amplification on every post. The match is the per-thread probe, linear in the searched rooms' own messages, and the follow-up above is for when that is too slow; no index was added for it. **The room route is linear in its own room's messages too:** one room holding hundreds of thousands of messages (the cap is on members, not messages) searched for a word nobody wrote reads them all, bounded by the request deadline (`security.md` "Search"). The snippet statement matches on the same wrapped vector (the page's 20 thread ids through `idx_messages_thread`: 7 ms; on the bare column, with the GIN index then present, the generic plan added the table-wide bitmap: 29 ms and growing with the table). `GlobalSearchTest` pins the statement shape and, under `plan_cache_mode = force_generic_plan` with sequential scans off, that the plan names `idx_messages_thread` and has no hashed subplan.
- **Recompute runbook.** The configuration is immutable by promise. After it changes, the stored vectors are stale until `ALTER TABLE messages ALTER COLUMN search SET EXPRESSION AS (pg_catalog.to_tsvector('public.adder_search'::regconfig, body));` and the same for `threads` over `title` rewrite them (no `STORED`: the column already is, and the keyword is a syntax error on PostgreSQL 18.6; `SET EXPRESSION` rewrites the stored vectors; no index depends on them today, and one added later is rebuilt by it, so no `REINDEX`). A migration that alters the configuration runs both statements in the SAME file. **A PostgreSQL major upgrade** that changes `unaccent.rules` changes no configuration, so nothing in a migration file notices: run the two `ALTER TABLE ... SET EXPRESSION` statements by hand, or ship them in a new migration, with the application down. The cost and the exclusive lock are V16's (the rewrite time above). The configuration cannot be dropped while a column depends on it. A non-superuser owner can create it.
- **Disk space.** The rewrite writes a new `messages` heap and TOAST while the old ones still exist until commit, so plan for free space of roughly the table's old size plus 2.5 times that size (the vectors are about 1.75 times the body). If the volume runs out, the migration rolls back and the pod crash-loops on Flyway until space is freed.
- **Search never logs.** A term, a lexeme or a snippet reaches no log line: a failed statement logs class names and the SQLSTATE only (`security.md` "Log hygiene"), and the cancelled statement is caught in `ThreadSearchService` (`search.timeout`, ids only).

### Read state (V19)

`V19__create_read_state.sql`: `room_members` gains `seen_at` and `read_from` (BIGINT NOT NULL DEFAULT 0, "since joining": every read is `GREATEST(column, added_at)`, so a member row written by any path, a raw test helper too, needs neither named), and `thread_reads (user_id, thread_id, room_id, last_read_message_id, read_at)` is created, primary key `(user_id, thread_id)`, `last_read_message_id > 0` (CHECK), `thread_id` `ON DELETE CASCADE`, **`fk_thread_reads_member (room_id, user_id)` references `room_members (room_id, user_id)` `ON DELETE CASCADE`** (a marker for a non-member is a 23503, a leaver's markers go with the row, so rejoining starts clean). `last_read_message_id` is a position, not a reference: no foreign key to `messages`, so a deleted message needs no cleanup and a tombstone is still a place to have read up to. `chat-model.md` "Unread (V19)" owns the rules.

- **The baseline rewrites data once.** The migration's `UPDATE room_members m SET seen_at = r.last_activity_at, read_from = r.last_activity_at FROM rooms r WHERE r.id = m.room_id` stamps EVERY existing member row once (a small table) with its room's own `last_activity_at`: an instant of the application's clock (no database clock is read, as nothing else in the schema does), at or after every message and activity written so far, since a write stamps its message and its room with the same instant. So after the upgrade nothing already written is unread or new for anybody, and the first message written after it is strictly newer. The two columns are separate on purpose: `seen_at` is "when the member last looked at the room" (the rail's dot), `read_from` is the floor of unread counting (a later "mark all as read" raises it). A dev database that has already run V20, V21 or a later 0.6 migration without this one is reset (the out-of-order rule above); a fresh database applies them in order. `ThreadReadTest` runs the file's own `UPDATE` (scoped to one room) and pins both floors exactly (equal to the room's last activity) and "what existed is read, what comes later is unread".
- **Two writers that take NO room lock, and why that is safe.** The marker route (`ThreadReadService.markRead`) and `POST /rooms/{id}/seen` (`RoomMemberService.markSeen`) run ONE short transaction each, never `withRoomWrite`: they lock only the caller's OWN `room_members` row (`UPDATE ... SET seen_at = GREATEST(seen_at, now)`, which also answers "no row: the caller was removed meanwhile", a 404), then read the thread and the message and upsert the marker. A writer queued behind the room lock holds a pooled connection and thirty readers acknowledging a busy thread would queue behind the room's writers and behind each other; and they emit no NOTIFY (one global commit lock, "NOTIFY" above). They cannot deadlock with a room write, which takes the room row first and the member row second (`touchActivity` sets the actor's `seen_at`; a post's follower upserts the author's marker): the marker transaction never waits for the room lock, so it always finishes and releases the member row; and the `fk_thread_reads_member` check takes only `FOR KEY SHARE` on the member row, which neither this `UPDATE` (a non-key update, `FOR NO KEY UPDATE`) nor another KEY SHARE conflicts with. A member removal (under the room lock) deletes the member row and so waits for a marker write that holds it; one that came first leaves nothing to update, which is why the marker's own foreign key can never fail (never a 500). Every statement is scoped by the caller's id: a write never moves another person's marker.
- **Written by the chat, under the room lock:** a post and a thread's opening message upsert the author's marker (`AuthorReadMarker`, a message follower, always on), and `RoomService.touchActivity(roomId, at, actorUserId)` moves the actor's `seen_at` forward (an ADMIN outside the room has no row, so nothing moves).
- **The statements** (`ThreadUnread.kt`, constant text, the array keeps it so): one list page's counts are `SELECT t.id, (SELECT count(*) FROM (SELECT m.created_at FROM messages m WHERE m.thread_id = t.id AND m.id > COALESCE(r.last_read_message_id, 0) ORDER BY m.id DESC LIMIT 100) recent WHERE recent.created_at > ?) FROM threads t LEFT JOIN thread_reads r ON ... WHERE t.id = ANY(CAST(? AS int4[])) AND t.last_activity_at > ?`; a thread's count and first unread message are the same inner read with `count(*)` and `min(id)`. Both read `messages` BACKWARDS through `idx_messages_thread`, so the `LIMIT 100` stops early (`ThreadSchemaTest` pins the index and the backward scan as an auto and as a generic plan); measured on 300 threads and 69,250 messages: **0.7 ms** for a page of 50 threads (five of them 5,000 messages long, the same plan custom and generic), 0.86 ms when every thread reads its full 100, 0.02 ms for one thread's first unread. The marker is `INSERT ... ON CONFLICT (user_id, thread_id) DO UPDATE ... WHERE thread_reads.last_read_message_id < EXCLUDED.last_read_message_id`: it only moves forward, and is raw SQL (the `INSERT_TAGS` precedent) because it must not depend on how a library renders a conditional upsert.
- **Cross-feature reads (the list IS the permission).** `threads/` reads `room_members` only through `RoomMemberService` (`unreadSince`, `markSeenInTransaction`); `rooms/` reads `room_members.seen_at`, `added_at` for the caller in the room list and never `messages` or `thread_reads`; `ThreadReadService` writes `thread_reads` and reads `threads` (the id by room and number) and `messages` (the id, that it is the thread's: a tombstone counts), and tells `notifications` through the `ThreadNotices` port (`threadRead`).
- **`idx_notifications_unseen_thread (recipient_id, thread_id) WHERE seen_at IS NULL`** (also V19, on V18's table). The marker write marks the person's unseen notices of one thread seen (`NotificationService.threadRead`, the constant `MARK_THREAD_SEEN_SQL`); V18's `idx_notifications_unseen (recipient_id, id)` would walk all of that person's unseen notices to find one thread's, and this statement runs at the marker's rate (up to 240 a minute) without the room lock. `ThreadSchemaTest` pins the index in the plan of that statement, auto and generic.
- **Known limit: a "seen" stamp is taken at request time.** `seen_at` (room seen, a marker write, the actor's own activity) is stamped when the request runs, while a room write takes its stamp at its start under the room lock and commits later. A post whose stamp is earlier than a "seen" that committed before it can therefore be hidden from the rail's dot (`last_activity_at` not past `seen_at`). The window is one room write (milliseconds, bounded by the lock wait), and the thread counts stay right: they compare message ids with the marker, and a message newer than the floor is counted by id, not by time.

### Reactions (V20)

`V20__create_message_reactions.sql`: `message_reactions (message_id, user_id, emoji, created_at)`, primary key `(message_id, emoji, user_id)`, `message_id` and `user_id` `ON DELETE CASCADE`, no other index (the key groups a message's rows by emoji, which is how they are read, and a person's own reactions of a message are found through the same key). V17 to V19 are the other migrations of the same release; the number is never reused or shifted.

- **`emoji VARCHAR(32) COLLATE "C"`.** Equality and order are by BYTES whatever the database's locale: a heart with and without its variation selector are two values, and a thumbs up and the same with a skin tone are two reactions. 32 holds the longest accepted value (the Kotlin rule refuses more than 10 code points and 32 UTF-16 units; a family of four is 7 characters and 25 bytes). `CHECK (char_length(emoji) >= 1)` is the floor only: what counts as ONE emoji is a rule in `threads/Reactions.kt`, never a CHECK (the V5 rule). The value is stored as sent.
- **Delete class: HARD.** A reaction is a join toggled on and off (the audit line holds the changes), nothing references a row, and a re-add must not collide with a dead one. A deleted message (a soft delete, tombstone) loses its reactions in the delete's own transaction through the message follower (`MessageReactionService.follower`), and `MessageDerivedRowsTest` asks `pg_constraint` which tables reference `messages` and proves the delete cleared this one. The rows also go with their message or user (`ON DELETE CASCADE`: a test purge; users are only ever soft-deleted).
- **`MessageReactionService` (V20)** writes `message_reactions` only, inside ONE message write under the room lock (`withMessageWrite`, the member guard re-run on the re-read caller), so the two limits (20 distinct emoji on a message, 5 of one person's on it) are read and written under the same lock and two writers cannot pass them. It reads `messages` and `threads` through the message write's own row, `UserService.Users` for the reactors' display names (soft-deleted accounts included, `userNames`) and never a `rooms/` table. A message read attaches the reactions of its page through ONE grouped statement (`GROUP BY message_id, emoji`, `bool_or(user_id = ?)`, the first three reactors by `array_agg(... ORDER BY created_at, user_id)`) inside the read's own transaction; it is the key's prefix scan, so no extra index is needed.

### Pins (V21)

`V21__create_room_pins.sql`: `room_pins (id SERIAL, room_id, thread_id, message_id NULL, pinned_by, pinned_at)`; `room_id`, `thread_id` and `message_id` are `ON DELETE CASCADE` (a test purge, and the rows of a hard-deleted message), `pinned_by` references `users` with no cascade (a user is soft-deleted; the pin keeps saying who made it). Two partial unique indexes, `uq_room_pins_thread (thread_id) WHERE message_id IS NULL` and `uq_room_pins_message (message_id) WHERE message_id IS NOT NULL`, so a thread and one of its messages can both be pinned and nothing is pinned twice; `idx_room_pins_room (room_id, pinned_at)` serves the list. A message pin carries its thread too, for the panel's link. The cap of 50 is code, not a constraint: counted under the room lock (`PinService.change`), so two concurrent writers of one room cannot both take the last place. `chat-model.md` "Pins" owns the rules.

- **Delete class: hard** (a pointer; unpinning removes the row). A message is soft-deleted, so the cascade never fires for it: `PinCleanup`, a `MessageFollower` (`threads/PinService.kt`, registered in `Database.kt`), deletes the message's pin in the delete's own transaction, whoever deletes it; `MessageDerivedRowsTest` proves it through the catalog.
- `PinService` reads `threads` (the number, title, status key and `closed_at` of the pinned thread; never `fields`), reads the pinned messages through `MessageService.readInTransaction` (the ordinary `MessageResponse`, one query, in the list's own transaction) and `UserService.Users` for names; it writes only `room_pins`. A pin is NOT activity: no `rooms.last_activity_at` or thread bump, no history event. Both PUTs are one `withThreadWrite` / `withMessageWrite` under the room lock with the member guard.

### History scrub (V23)

`V23__scrub_history_values.sql` is a DATA-ONLY migration (no DDL, no new object): the user's 1.0.0 decision (2026-11-20, checkup C-SEC-5/C-SEC-6, the 0.3.0 audit's M4) is that the append-only `thread_events` history keeps no raw TEXT or URL value and no title, because a credential pasted into one could never be taken back. The write path stopped recording them first (`threads/ThreadFieldEvents.kt`: an allow-list of the types whose values are recorded; `threadTitleChangedEvent()` and `threadCreatedEvent` carry no title; `chat-model.md` "History"); the migration removes them from the rows already written. Three `UPDATE`s of `params` (JSON TEXT), each touching only the rows that still hold a value:

- **`TITLE_CHANGED`**: `params` becomes `{}` (`from` and `to` both go; the new title is the next rename's `from`, and the thread shows the current one).
- **`CREATED`**: the `title` key is removed (the opening title); `status` and `statusLabel` stay.
- **`FIELD_CHANGED`**: `from` and `to` are removed UNLESS the room's field (`threads.room_id` + `room_fields.key` = `params->>'field'`) is a SELECT, MULTI_SELECT, USER, DATE or NUMBER field. **An allow-list, so doubt scrubs:** a TEXT or URL field, a retired TEXT or URL field (its `room_fields` row stays, the key is never minted twice, and `type` is immutable per key, so today's type is the type at the event), and an event whose field is not found in the room at all are all scrubbed. A retired SELECT/USER/DATE/NUMBER field keeps its stored values: they are hidden at READ time instead ("Retired fields", below), so a revived field gets them back. Another kind's own `from`/`to` (`STATUS_CHANGED`, `CLOSED`, `REOPENED`, `STATUS_REPLACED`) is never touched: the statements name their event type.

**Idempotent**: every statement's `WHERE` selects only rows that still hold a value, so a second run changes nothing (`ThreadHistoryScrubTest` runs the file twice). **The rewritten text** is jsonb's rendering of the same object (key order and one space after each colon differ), which `decodeParams` reads alike; rows the statements do not select are not rewritten at all. **The casts to jsonb cannot fail** on a row the app wrote (`params` is only ever `encodeParams` output, and the one thing jsonb refuses, U+0000, is refused by title and field validation); if one ever did, the migration fails loudly and rolls back whole (Flyway runs it in one transaction), never half-scrubs.

**Lock and duration.** Plain `UPDATE`s: `ROW EXCLUSIVE` on `thread_events` (reads and the appends of any other instance carry on) and a row lock only on the rows rewritten, which nothing else ever updates (the table is append-only; the only other writer is a cascade from a room purge). No batching: a `DO` loop cannot commit inside Flyway's transaction, and nothing waits on these rows. **Measured** on `postgres:18.6-alpine` (the test image), 200 rooms x 500 threads and 1.6 million events (500,000 renames, 500,000 TEXT/URL changes of 200 characters a side, 300,000 SELECT changes, 200,000 status moves, 100,000 creations): the three statements, in file order, took **1.6 s, 0.4 s and 5.3 s (about 7.3 s in all)** in one transaction, rewrote 1.1 million rows and grew the table and its index from 500 MB to 665 MB until vacuum; a second run took 1.4 s and rewrote nothing. A real table is a tiny fraction of that (a member's edits are bucketed at 240 a minute).

**WAL and disk space.** Every rewritten row is a new tuple, so the three statements write WAL and a second copy of each rewritten row's page content until vacuum: plan for free space of roughly the rewritten rows' size again on the data volume (the measured 1.1M rows added 165 MB) plus the WAL they produce (about the same order) and replicas' lag; if the volume runs out the migration rolls back whole and the pod crash-loops on Flyway until space is freed, as for V16's "Disk space".

**Deploy overlap.** The shipped compose file and the Kubernetes `Recreate` strategy (`k8s/app-deployment.yaml`) stop the old instance before the new one starts, so no old binary writes the old shape after V23; the re-run in the rollback runbook below matters only for a rolling deploy elsewhere or a rollback.

**What it does NOT do (say so to whoever asks).** The migration is logical: the old row versions stay in the heap (and in TOAST, and in any backup, replica or WAL archive taken before) until vacuum reclaims them, and autovacuum does that on its own schedule. To remove them physically at once an operator runs `VACUUM FULL thread_events` (an `ACCESS EXCLUSIVE` lock: a maintenance window) and treats older backups as holding the old text. **A secret that was already in a title or a field must still be rotated**: the scrub closes the leak for the future and for the API, not for a copy already taken.

**Retired fields (C-SEC-6).** `GET .../threads/{n}/events` answers a `FIELD_CHANGED` of a RETIRED field without its `from`/`to` (`ThreadService.history` reads the room's retired keys, `ThreadEventService.list` strips them): the entry stays, so the pages and `total` do not change, and the values return with a revived field. It is a read-time filter, not a second scrub, because the values are not secrets (they are option labels, names, dates, numbers) and the workflow editor can revive the field.

**Rollback below 1.0.0 (runbook).** A pre-1.0.0 binary starts against a V23 database without trouble (the migration changed no schema, and it reads the scrubbed rows: an absent `from`/`to` already means "unset" to it, `TITLE_CHANGED {}` shows no old/new line). But it writes the OLD shape for every title and field change made while it runs, and a rolling deploy does the same for as long as an old instance is alive. After the last old instance is gone (or after the way back up from a rollback), re-run the three statements of the file by hand against the database (`psql -f V23__scrub_history_values.sql` is exactly that: idempotent, and Flyway's checksum is untouched, being a run of the file's text, not a `flyway_schema_history` write).

### NOTIFY (live hints, 0.4.0)

Every chat write that changes something emits ONE `pg_notify('adder_live', <LiveHint JSON>)` from `infra/notify/Notify.kt` (`notifyLive`), inside the write's own transaction, right before
its change branch returns (the 15 sites, the catalog and the delivery rules are in `live.md` "Hints"). There is deliberately no hook in `withRoomWrite`: `RoomService.create` is not under
it and several writes return early on a no-op, which must emit nothing. It is a `SELECT 1 FROM pg_notify(...)` whose result is consumed (an R2DBC statement nobody subscribes to never runs;
the `MultiRowInsert.kt` `exec` precedent), so it needs a current transaction, and it holds NO table and writes NO row (no migration).

- **A hint is ids only** (the `LiveHint` type has no text field) and the payload is checked under 8000 bytes before the database sees it (`MAX_NOTIFY_PAYLOAD_BYTES`; PostgreSQL rejects 8000 or more).
  The worst case is a 500-id `room.member_added`, about 5.6 KB, so there is no chunking; `LiveHintTest` and `NotifyHintsTest` pin it.
- **PostgreSQL facts the design leans on:** a NOTIFY is delivered only when its transaction commits, in commit order, and is dropped on rollback (so a refused or rolled-back write cannot announce
  itself); identical payloads within ONE transaction collapse into one delivery (a write emits one hint, so this never bites); notifying transactions serialize on ONE global lock during commit (a
  cluster-wide cost, accepted at chat-write rates); the cluster's notification queue fills only if a LISTENing connection stops reading, after which EVERY notifying commit fails. That last hazard is why
  the listener (S2) drains with unbounded demand and never stops reading.
- **Tests read the channel through a plain JDBC `LISTEN`** (`NotifyCapture`), bracketed by a sentinel hint of its own, so "emits nothing" is proven by commit order, not by sleeping.

### The LISTEN connection (live, 0.4.0)

`infra/notify/NotifyListener.kt` is the **one documented exception to the bounded pool**: ONE dedicated, non-pooled R2DBC connection per process that `LISTEN`s on `adder_live` for the life of the process
(a pooled connection is borrowed and returned, a listening session must be neither). `configureDatabase` publishes its factory as `NotifyConnectionFactoryKey` (`infra/db/Database.kt`, `notifyConnectionFactory`); the
listener itself is built and started by the live feature (`live.md` "The listener"), lazily, on the first ticket mint. It adds ONE backend per replica, hence the sizing formula above.

- **Built differently on purpose.** Same `postgres.*` URL, user and password as the pool (`ApplicationConfig.r2dbcOptions`, extracted so the two cannot drift), but its `application_name` is
  `<postgres.pool.applicationName>-live` (`adder-live`; `ConnectionPoolTest` counts the pool's own name exactly, and `pg_stat_activity` shows the listener apart). The factory is the raw driver factory wrapped ONLY in
  `LeakedSocketClosingConnectionFactory`: no pool, no `StatementTimeoutCachingConnectionFactory` (the listener sets no statement timeout; a remembered one would be wrong for a session that outlives every request) and no
  `DetachingConnectionFactory` (nothing borrows it, so no frame to detach from). The session is never in a transaction, so `idle_in_transaction_session_timeout` has nothing to end.
- **It never stops reading.** r2dbc-postgresql 1.1.3 publishes notifications through a best-effort multicast sink: a subscriber without demand silently LOSES them. The listener subscribes to `getNotifications()` with
  unbounded demand (before `LISTEN`, so none falls between) and copies each payload into a `Channel<String>(1024)` with `trySend`; a payload that finds it full is dropped and reported as a gap. A listening connection that
  stopped reading would fill the cluster's notification queue and make EVERY notifying commit fail ("NOTIFY" above), which is why a slow consumer must cost `resync`s, never backpressure on the socket.
- **It heals itself.** A `SELECT 1` heartbeat every 30 s (5 s timeout; a half-open TCP connection leaves the session "up" but deaf, and only a statement notices), a flux error or completion, or a failed step closes the
  session (the close is bounded and reaches the leaked-socket guard) and reconnects with jittered backoff (1, 2, 4 ... 30 s, each wait 50 to 100 %). After EVERY successful LISTEN (the first included, and BEFORE
  the listener reports itself up) and for every dropped payload, `onGap` fires: hints committed while no session was listening (the first connect's own window, an outage) are gone (at-most-once, `live.md`), and the hub
  answers a gap with `resync`. `onGap` can be called from the driver's event loop and from the listener's coroutine, possibly at once, so the consumer's callback must be thread-safe, cheap, non-blocking and
  non-throwing (S4: an atomic flag or a conflated channel `trySend`).
- **Not config.** The capacity, heartbeat, step timeout and backoff are `NotifyListenerTimings` defaults, constructor parameters only so `NotifyListenerTest` (no app, shared container: committed vs rolled-back, a terminated
  backend, a full channel, a stalled heartbeat, the `-live` name and close on stop) can shrink them. Logs: `observability.md`.

### Backup and restore

A manual runbook (checkup C-ARCH-2, decided 2026-11-20: no scheduled job). The unit is `pg_dump -Fc` of the `adder` database,
taken INSIDE the Postgres container/pod and streamed out, so the host needs no Postgres client and no password (the image's
local socket trusts the in-container user). `scripts/db-backup.sh [OUT.dump]` and `scripts/db-restore.sh DUMP.dump [--replace]`
wrap it; both take the command that reaches the container from `ADDER_PG_EXEC` (also `ADDER_DB_USER`/`ADDER_DB_NAME`,
default `adder`/`adder`; the value is word-split on purpose):

| Where | `ADDER_PG_EXEC` |
|---|---|
| compose, default project | unset (default `docker exec -i adder-postgres`) |
| compose, another project | `docker compose -p NAME exec -T postgres` (a container name changed by an override: `docker exec -i THAT-NAME`) |
| Kubernetes (`adder` namespace) | `kubectl -n adder exec -i deploy/postgres --` |

- **Backup** (live, the app may keep running: one transaction, a consistent snapshot): `scripts/db-backup.sh`. The file is written
  `0600` (it holds every user, message and password hash; treat it like the database), never over an existing file, and only
  kept if `pg_restore --list` reads it and finds table data. The bare equivalent is
  `(umask 077; docker exec adder-postgres pg_dump -U adder -d adder -Fc > adder.dump)`.
- **Restore into an EMPTY database** (a new machine, a lost volume): bring up ONLY Postgres, restore, then start the app.
  Compose: `docker compose up -d --wait postgres && scripts/db-restore.sh adder.dump && docker compose up -d app`.
  Kubernetes: scale the app to zero (`kubectl -n adder scale deploy/app --replicas=0`), make sure the `postgres` pod runs on a
  fresh PVC, restore, scale the app back to 1. The app must NOT have booted against the empty database first: Flyway and the
  seed admin would have made it non-empty, and the script refuses (see below). On start the app finds the schema current
  (`Current version of schema "public": 22`, nothing applied). The restore is one transaction (`--single-transaction
  --exit-on-error`): all or nothing, and `--no-owner --no-acl` leaves everything owned by the connecting role.
- **Safe by default:** the restore refuses unless the target has NO table, view or sequence outside the system schemas AND no other
  client is connected (`pg_stat_activity`: the app still running; `--force-connected` overrides, for a client you know is idle), and
  changes nothing when it refuses. `--replace` is the explicit way over a non-empty database and DESTROYS what is there: in ONE
  explicit transaction it drops every extension (bar `plpgsql`) and every non-system schema (`public`, `ops`; V4 puts `unaccent` in
  `public`, V9 creates `ops`) with `CASCADE`, recreates an empty `public`, and replays the dump rendered by `pg_restore -f -`, so
  the result is exactly the dump even when the live database is AHEAD of it (a newer table with a foreign key into `rooms`, a higher
  Flyway history row; `pg_restore --clean` would trip over the first and leave the second). The script is fed to `psql` without `-1`
  and ends in `COMMIT;`, so a cut stream rolls back instead of committing half a restore (the rendered SQL goes through a `0600`
  temp file first, so a failed `pg_restore` never reaches the database). A future migration that creates another schema or an
  extension needs no change here: the drop is by catalog, not by name. Stop the app first (compose `docker compose stop app`; k8s
  scale to 0). To restore beside the data instead, make a scratch database (`ADDER_DB_NAME=adder_restored` after `createdb`) and compare.
- **Not in the dump:** `DATA_ENCRYPTION_KEY` and `JWT_SECRET` (the Secret / compose env). An encrypted column is unreadable without
  its key (no column is encrypted yet, `security.md`); keep the key apart from the dump. A restored database keeps its users'
  password hashes and `credential_revision`s, so every outstanding token stays valid or not as the live system decided. In-memory state (lockouts, MFA codes, refresh rotations) starts empty.
- **A dump restores into the same or a newer app version** (Flyway migrates forward on boot). An OLDER app against a newer database is
  not refused by Flyway (its default ignores `*:future` migrations; the config is unchanged), so never roll the app back past the
  schema without restoring a dump of that older schema. Restore into the same Postgres major (18).
- **Tested** 2026-11-20 against a throwaway compose project (own `-p`, ports, volume): seed rows through the API, dump, `down -v`,
  fresh Postgres, restore, app start (Flyway "Current version … 22", 0 migrations applied), same row counts and logins; the
  non-empty and app-still-running refusals, and `--replace` over a database AHEAD of the dump (an extra table with a foreign key into `rooms`
  plus a fake higher Flyway row: afterwards exactly the dump, app boots). Repeat that drill after a migration that adds a schema or extension.
