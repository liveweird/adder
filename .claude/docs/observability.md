### Observability

`plugins/OpenTelemetry.kt` installs `KtorServerTelemetry` and obtains the SDK via
`getOpenTelemetry("adder")` from the `core` module. `plugins/Monitoring.kt` separately installs
Dropwizard metrics (dumped to the `metrics` logger by a `Slf4jReporter` every `monitoring.reporterIntervalSeconds`, env
`METRICS_REPORT_INTERVAL_SECONDS`: default **300**, `0` = reporter off with the meters still collected, otherwise 30..3600, anything
else fails startup via `requireConfigInt`; it was a fixed 10 s in Flow, a main source of idle allocation and
log volume, since each dump formats the whole registry into dozens of lines the console logs exporter re-emits; nothing in the repo —
docs, e2e, tests — reads those lines, they are a look-back aid; pinned by `MonitoringReporterConfigTest`; the registry is a `TelemetryRegistry` — timers on a true sliding window of `telemetry.sampleIntervalSeconds`, capped at 300 s (the reservoir keeps ~16 bytes per timer update until it ages out of the window, so the cap bounds the memory cost per request) — published as `MetricRegistryKey`, and Ktor's JVM metric sets are OFF (`registerJvmMetricSets = false`: its thread-states gauge dumped every thread on each reporter run; the telemetry sampler reads the MXBeans itself), see "Telemetry") and the `CallId` plugin using `X-Request-Id`
(client-supplied ids are honored only when ≤64 URL-safe chars — they reach logs and OTel
attributes, so junk is ignored rather than propagated). The SDK is also wired for **logs**: a
Logback `OpenTelemetryAppender` (`server/src/main/resources/logback.xml`, the sole root appender
— re-adding STDOUT would double every line, since the interim `console` logs exporter already
prints to System.out) bridges every SLF4J log into the OTel logs SDK, and
`configureOpenTelemetry` (`plugins/OpenTelemetry.kt`) installs the appender (flushing the
pre-install boot-log buffer) while `getOpenTelemetry` (`core/.../OpenTelemetry.kt`) sets the
interim exporter to `console`. Defaults are set via `addPropertiesSupplier` (the lowest-precedence
config tier) **on purpose**, so the sink can be redirected to a collector by env alone with no
code change — set `OTEL_LOGS_EXPORTER=otlp` + `OTEL_EXPORTER_OTLP_ENDPOINT` (the
`opentelemetry-exporter-otlp` dep is already on the classpath); `OTEL_TRACES_EXPORTER` likewise.
Metrics and traces exporters default to `none` (`otel.metrics.exporter`/`otel.traces.exporter`).
**Shutdown ordering (the SDK closes LAST).** JVM shutdown hooks run concurrently and unordered. The
OpenTelemetry autoconfigure SDK registers its own hook (flush + close the logs pipeline) and Ktor's
`start(wait = true)` registers another (it fires `ApplicationStopping`, where a component may audit a final event); with both live, the SDK often closed first and every audit line
emitted during Ktor's stop was silently dropped (the DB release still happened — only the log line
vanished). So `getOpenTelemetry` (`core`) calls `disableShutdownHook()` and `configureOpenTelemetry`
registers `TelemetryShutdown`'s hook instead: it waits (bounded, 20 s) for `ApplicationStopped` — the end of
Ktor's stop — and only then closes the SDK. Ktor raises an event's handlers in subscription order, so the
latch handler is subscribed from inside the `ApplicationStarted` handler (after every module has subscribed its
own `ApplicationStopped` work — the pool dispose) and therefore runs last. The 20 s sits
in a budget: Ktor grace + timeout ≈ 11 s by default, and 20 s + the SDK's ≤10 s `close()`
is exactly the Kubernetes default `terminationGracePeriodSeconds` (30 s), so `k8s/app-deployment.yaml` sets 45 s
(`JvmMemoryFlagsTest` asserts it is at least `STOP_WAIT_SECONDS` + 10 s + 15 s) — raising Ktor's shutdown timeout or
lowering the pod's grace must revisit `STOP_WAIT_SECONDS`; if the application never finished starting (a boot failure)
there is no Ktor stop to wait for and it flushes at once, so the failure's own lines still export. Anything
that must log during a stop belongs in an `ApplicationStopping`/`ApplicationStopped` handler, never in its
own JVM hook (it would race the SDK again). Pinned by `ShutdownAuditTest`: a forked child JVM (a real Netty
server + the real `configureOpenTelemetry`) gets `kill -TERM` and its stdout must hold the line its
`ApplicationStopping` handler audited (not `Process.destroy()`, which closes the child's pipes first).

**Convention for non-fatal "this should never happen" events:** emit a WARN with the
`SHOULD_NEVER_HAPPEN` marker (`MarkerFactory.getMarker`) plus key/value attributes — they flow
through the appender to OTel. Reserve it for branches that are genuinely unreachable by ordinary
requests (no current usage — Lettuce's history shows how easily an "unreachable" branch turns out
to be a routine denial path).

**The 500 line (`plugins/ErrorHandling.kt`, `plugins/ScrubbedFailure.kt`).** The catch-all logs ERROR `Unhandled exception while processing <METHOD> <path>` (no query string) with the failure as its throwable. When the cause chain holds a database exception (`R2dbcException`, which Exposed's `ExposedR2dbcException` extends, or `java.sql.SQLException`), OR any link has suppressed exceptions, OR the chain is deeper than the walk's 32-link bound (fail closed: a database exception could hide in either), the throwable is replaced by `ScrubbedFailure`s mirroring the chain: each link is `<exception class name> [SQLSTATE <state>]` with the original stack frames and NO message and no suppressed exceptions, because a driver or Exposed message can carry bound values (Exposed expands the statement's arguments into the message when the transaction's `debug` flag is on; Postgres echoes values into some messages), and a message body, a title or a search term is such a value. Any other failure is logged unchanged. Two one-line sites that render a failure without a stack use `scrubbedSummary()` (the links joined by ` <- `): the telemetry collector WARN/DEBUG and the readiness probe's DEBUG. `ChatLogHygieneTest` pins it (the echo test proves the leak is closed; the 57014 test only pins that the line still names the driver class and the SQLSTATE); `ScrubbedFailureTest` pins the rendering. **No logger below INFO prints a failure's message:** DEBUG statement logging of the driver stays off, and `logback.xml` pins the two other loggers that would print a cause below INFO, `Exposed` (its DEBUG `Transaction attempt #N failed: ${cause.message}`) and `io.ktor.server.plugins.statuspages` (its TRACE line with the URI and `cause.toString()`), to INFO.

**Spans carry an exception's type only.** The Ktor instrumentation records the RAW throwable of a failed request (`Span.recordException`: `exception.message` and `exception.stacktrace` with every "Caused by" message). `core`'s `getOpenTelemetry` wraps whichever exporter `OTEL_TRACES_EXPORTER` selects in `ScrubbedSpanExporter` (`addSpanExporterCustomizer`), which drops those two attributes from every span event and empties a span status description; `exception.type` and every other attribute stay. Pinned by `ScrubbedSpanExporterTest` (the wrapper, and the real `getOpenTelemetry` wiring through a test-only exporter SPI). The OTel logs appender needs no wrapper: the 500 catch-all hands it the scrubbed throwable.

**Database connection guards (WARN logs, no audit event; `persistence.md` "Connection pool").** Logger
`ch.nokillswit.infra.db.LeakedSocketGuard`: `closed the socket of a broken database connection the driver had left open (backend pid
<pid>; its transaction rolls back)` each time the guard disposes a socket r2dbc-postgresql gave up on without closing (the r2dbc-postgresql
1.1.3 driver bug; it follows the driver's own `Connection Error` ERROR, e.g. "Response queue is full"); `leaked-socket guard disabled:
r2dbc-postgresql internals not found` once at boot (pool setup), if a driver upgrade renamed the internals it reads. A pooled session
the server ends under `idle_in_transaction_session_timeout` surfaces in Adder only as a generic connection-closed error on its next
statement (in its own request or job); the `FATAL 25P03` naming the idle timeout is in the PostgreSQL log alone.

**The live socket's OTel span (0.4.0 S4, measured).** The Ktor server instrumentation has no request filter, so each WebSocket is ONE `SERVER` span named `GET /(RateLimit live-connect)/api/v1/live/(method:GET)/(header:Connection = Upgrade)` with `http.response.status_code=101` that ends when the socket ends: a 3.2 s socket measured a 3,190 ms span (`OTEL_TRACES_EXPORTER=logging` against a real Netty server), so a 15-minute socket is a 15-minute span (and the Dropwizard `ktor.calls` timer likewise, which is why `telemetry/LatencyCollector.kt` excludes `/api/v1/live` from the panel's p50/p95 while the ticket mint stays counted). A refused upgrade (403) is an ordinary short span. Traces are off by default (`otel.traces.exporter=none`); a collector sees these long spans per socket, at most `live.maxSockets` at a time.

**The live listener (WARN/INFO logs, no audit event; `persistence.md` "The LISTEN connection").** Logger `ch.nokillswit.infra.notify.NotifyListener`, never one line per notification: INFO `live listener started` (the first
successful LISTEN) and INFO `live listener recovered` (the first LISTEN after an outage); WARN `live listener could not connect (<exception class names>); retrying with backoff` (it never came up) or `live listener lost its database connection (<exception class names>); reconnecting with backoff` (it had) ONCE per outage (the class names only,
never a message: a driver message can carry the host or the URL), repeated only if a session that had come up drops again. A gap (a reconnect, a full channel) is the hub's `resync` (DEBUG, `live.md`), not a log line of the listener.

**Audit trail.** Security-relevant events are structured INFO logs on the dedicated
`ch.nokillswit.audit` logger with the `AUDIT` marker, emitted via `audit("area.event", "key" to value, …)`
(`audit/Audit.kt`) — they ride the same Logback→OTel pipeline, so shipping them to a
collector/SIEM is env-only. Fields travel as SLF4J key/values, not in the message text (assert
with `hasKeyValue` from `TestEnvironment.kt`). Emitted today:

- `login.success` / `login.failure` (with `reason`: unknown_email/wrong_password) /
  `login.lockout` (the throttle tripping) / `login.rejected_locked` (an attempt against a locked
  account) / `login.capacity_rejected` (an unseen identity cannot be admitted into the full
  throttle); login audits use `email` for ordinary identities and a fixed-size `emailDigest` for
  oversized submissions,
- `login.mfa_challenge` (email/userId — correct credentials, second factor pending) /
  `login.mfa_success` / `login.mfa_failure` (with `reason`:
  unknown_challenge/expired/wrong_code/too_many_attempts/user_gone/credential_changed) /
  `login.mfa_unavailable` (MFA enabled on a mail-less deployment → 503) /
  `login.mfa_capacity_rejected` (the pending challenge store is full → 429, no code sent) /
  `login.mfa_send_failed` (email/errorType),
- `logout`,
- `password_reset.requested` (email) / `password_reset.unknown_email` (email) / `password_reset.throttled` (email) /
  `password_reset.capacity_rejected` (email - new identity at the full cooldown store, no async task started) /
  `password_reset.link_sent` (userId/email) / `password_reset.capped` (userId - the account already holds the maximum of 5
  unexpired links: nothing minted, nothing sent, the request still answered 202) / `password_reset.stale_account` (userId - the
  account changed between the async task's lookup and the grant insert: nothing minted, nothing sent) / `password_reset.send_failed` (email/errorType - the link never left; its grant is
  revoked) / `password_reset.rejected` (no fields - a confirm against an invalid, expired, used or stale grant, uniform) /
  `password_reset.completed` (userId - the confirm) / `password_reset.notification_failed` (userId/errorType - the post-reset
  notice; the reset stands) - the self-service reset trail, none of it carrying a token or a password; the async task's events
  double as test barriers,
- `refresh.rejected` (with `reason`: invalid_or_expired/wrong_token_type/revoked/malformed/
  user_gone/credential_changed/replayed — `replayed` is a rotated refresh token presented again after the replay grace,
  `revoked` a logout-revoked one or a rotation this instance no longer remembers),
- `access.rejected` (with `reason`: user_gone/revoked/credential_changed/roles_changed/malformed, `userId` - the
  token's claim, null when absent OR out of range - and `suppressed`; `plugins/Security.kt`) - the per-request access check (`TokenBlocklistService.accessVerdict`)
  refused an otherwise valid signed access token: the account was deleted, the token was logged out, the password was
  changed, the role was changed, or the token lacks a claim the check needs (`roles`, `exp`, an invalid `userId`/`credentialRevision`: a pre-deploy token or a hand-signed one). **Coalesced** like `rate_limit.exceeded` (its own `RateLimitAuditThrottle`): at most ONE event per user and reason per 60 s, `suppressed`
  counting the rejections folded into it since the previous one (0 on the first), so a stale token replayed on every request writes a line a minute;
  the keys are bounded by the accounts that ever held a signed token. Junk, expired, wrong-audience/`typ` and jti-less tokens never reach the check and emit nothing. Never the token,
- `refresh.replay_grace` (userId — a rotated refresh token presented again inside the grace
  (`security.refresh.replayGraceSeconds`) and answered with a fresh pair, at most twice per token; a lost response or a racing tab, or a replay),
- `password.changed` (targetUserId/byUserId/selfChange) / `password.change_denied` (wrong or
  missing current password),
- `user.features_changed` (byUserId/targetUserId/featuresFrom/featuresTo — only on an actual
  change),
- `user.language_changed` (the per-user language — byUserId/targetUserId + `from`/`to` codes;
  emitted only on an actual change; `user.created` additionally carries `language` when
  non-default),
- `user.created` (byUserId/newUserId/email/roles — roles as STORED, i.e. the folded
  additional-roles set) / `user.updated` (name/email deltas, only when changed) /
  `user.roles_changed` (rolesFrom/rolesTo, both as stored) / `user.deleted`
  (byUserId/targetUserId) — the user events (`/api/v1/users`),
- `team.created` (byUserId/teamId/name/managerUserId, nullable) / `team.updated`
  (byUserId/teamId/name) / `team.manager_changed` (byUserId/teamId/managerFrom/managerTo, nullable ids as STORED —
  only when a PUT actually changes the manager; a manager assignment can grant team-scoped rights, so it is its own
  trail line, the `user.roles_changed` precedent) / `team.deleted` (byUserId/teamId) — every team mutation; a rejected save
  emits nothing,
- `team.member_added` (byUserId/teamId/targetUserId — only on an ACTUAL add: the PUT is idempotent, so a repeat emits nothing) /
  `team.member_removed` (byUserId/teamId/targetUserId — only when a row was removed) — the roster events (`PUT`/`DELETE /teams/{id}/members/{userId}`,
  ADMIN or the team's manager; the actor is the `byUserId`),
- `workflow_template.created` (byUserId/templateId/name/statuses/fields — the last two are counts) / `workflow_template.updated` (byUserId/templateId/name/revision —
  the NEW revision) / `workflow_template.deleted` (byUserId/templateId) — every template mutation (`/api/v1/workflow-templates`, ADMIN only); a refused write (400, 403,
  404, a stale-revision or name 409) emits no `workflow_template.*` event (a 403 still emits `authz.denied`),
- `room.created` (byUserId/roomId/name/templateId/language — the language the labels were resolved to, `en` when the request named none) / `room.updated` (byUserId/roomId/name) /
  `room.archived` / `room.unarchived` (byUserId/roomId — only on an ACTUAL change: both are idempotent, so a repeat emits nothing) / `room.deleted` (byUserId/roomId) — the room
  record events (`/api/v1/rooms`, C7; OWNER or ADMIN, `room.deleted` ADMIN only); a refused write (400, 403, 404, a name or archived 409) emits no `room.*` event of its own (a 403 still
  emits `authz.denied`),
- `room.workflow_updated` (byUserId/roomId/revision/statusesAdded/statusesRetired/fieldsAdded/fieldsRetired/threadsMoved) — `PUT /api/v1/rooms/{id}/workflow` (C9; OWNER or ADMIN), once per
  successful PUT, after the transaction commits. `revision` is the NEW workflow revision; the four status/field counts are Ints (`Added` counts a key that became live, new or revived, `Retired`
  a live key the document dropped); `threadsMoved` is a Long, the threads moved to a replacement status. A refused PUT (400, 403, 404, a stale-revision, archived,
  `field-type-immutable`, key-budget or `status-in-use` 409) emits no `room.workflow_updated` (a 403 still emits `authz.denied`),
- `room.member_added` (byUserId/roomId/targetUserId/viaTeamId) — ONE line per person actually added (`POST /api/v1/rooms/{id}/members`, C8; OWNER or ADMIN); `viaTeamId` is the team whose roster
  snapshot brought the person in, null for a person named directly (a person named AND on the roster counts as named); a person who already belonged emits nothing (the response counts them).
  `room.member_role_changed` (byUserId/roomId/targetUserId/roleFrom/roleTo) — `PUT .../members/{userId}`, on an ACTUAL change only (the same role again is a `204` that emits nothing).
  `room.member_removed` (byUserId/roomId/targetUserId/self) — `DELETE .../members/{userId}`; `self` is true for a leave. A refused write (400, 403, 404, the member-cap or `last-owner` 409) emits no
  `room.member_*` event (a 403 still emits `authz.denied`); the events are emitted by the route after the transaction commits,
- `room.name_conflict` (byUserId/roomId — roomId null for a create) — a room create or rename answered `409` because a non-deleted room already holds the name (case-insensitively): the trace of someone using the clash as
  a "does a room with this name exist" probe (`security.md`). Emitted by the route when the unique-index violation passes through it, before the central 409 answers; bounded by the shared `room-name` bucket (30/min),
- `thread.created` (byUserId/roomId/threadId/number/messageId) - `POST /api/v1/rooms/{id}/threads` (0.3.0 S2; a MEMBER): one line per thread, emitted by the route after the transaction commits;
  it names the opening message (`messageId`), and there is no separate `message.posted` for it. **Thread and message audit lines carry ids, keys and counts only, never a title, a message body or a
  field value** (`ThreadWriteBoundsTest` pins that no title or body reaches the trail); the user-facing history is `thread_events`, not the audit log. A refused create (400, including every field-value
  rule, 403, 404, or the archived-room 409, the only 409 a create has) emits no `thread.*` event (a 403 still emits `authz.denied`; a non-member's 404 emits `room.access_denied`),
- `thread.updated` (byUserId/roomId/threadId/number/revision/titleChanged/fieldsChanged) - `PUT /api/v1/rooms/{id}/threads/{number}` (0.3.0 S3; any reader): one line per CHANGE, emitted by the route after
  the transaction commits; `revision` is the NEW one, `titleChanged` a boolean and `fieldsChanged` the comma-joined field KEYS, never the title or a value. A no-op PUT (`204`, nothing changed) and a refused one
  (400, 404, the archived, closed or stale-revision 409) emit no `thread.*` line,
- `thread.status_changed` (byUserId/roomId/threadId/number/from/to/transition) - `PUT .../threads/{number}/status` (0.3.0 S4; any reader for an OPEN to OPEN move, a steward for every move that enters or leaves
  CLOSED): `from` and `to` are status KEYS and `transition` is `MOVED`, `CLOSED` or `REOPENED` (the reopen is audited and recorded in the history). Setting the status the thread already has is a `204` with no line,
  and so is every refusal (400, 403, 404, the archived 409),
- `thread.owner_changed` (byUserId/roomId/threadId/number/fromUserId/toUserId) - `PUT .../threads/{number}/owner` (0.3.0 S4; a steward): user ids only. Naming the current owner is a `204` with no line; a refusal
  (400, 403, 404, the archived or closed 409) emits no `thread.*` line (a 403 still emits `authz.denied`),
- `message.posted` (byUserId/roomId/threadId/number/messageId) - `POST .../threads/{number}/messages` (0.3.0 S5; a MEMBER): one line per post, emitted by the route after the transaction commits. The opening message of a thread is `thread.created`'s, never a `message.posted`. A refusal (400, 403, 404, the archived or closed 409, either 429: the bucket or `room-busy`) emits no `message.*` line,
- `message.edited` (byUserId/roomId/threadId/messageId) - `PUT /api/v1/rooms/{id}/messages/{messageId}` (0.3.0 S5; the author): one line per CHANGE; an unchanged body (`204`) and every refusal emit none. Edits are audit-only (they are not in the thread history),
- `message.deleted` (byUserId/roomId/threadId/messageId/authorId/moderated) - `DELETE /api/v1/rooms/{id}/messages/{messageId}` (0.3.0 S5; the author, a room OWNER or an ADMIN): `authorId` is whose message it was and `moderated` is true when the caller is not the author (a boolean, `deleted_by <> author_id`). A refusal (403, 404, the archived or closed 409) emits none. **Never a body**: `MessageTest` pins that no message text reaches the trail,
- **No audit event for hashtags** (0.5.0): `message_tags` is DERIVED data written inside the message's own write, so its writes are the message's `message.posted`/`message.edited`/`message.deleted` lines and nothing more; the tag list and the `tag` filter are reads. A tag, like a body, reaches no log line (`ChatLogHygieneTest`). The one log line of the feature is the boot reindex's (logger `ch.nokillswit.threads.MessageTagReindex`, `threads/MessageTagReindex.kt`): INFO `message tag reindex: N message(s) indexed, M tag row(s) of deleted messages removed` once after a boot that found either (silent when there is neither), and WARN `message tag reindex failed, the rows stay stale until the next boot: <class names and SQLSTATE>` through `scrubbedSummary()` (never a database message, which can echo a tag or a body). The reindex is off when `threads.tagReindexOnBoot` is false (the test harness),
- `search.busy` (byUserId/roomId) - a search (`GET /rooms/{id}/search`, `GET /search`, 0.5.0, `threads/ThreadSearchService.kt`) found the process already running `search.maxConcurrent` searches (4 by default) and was refused at once with `429` of type `urn:adder:problem:search-busy` and `Retry-After: 1`, before a pool connection was asked for. Ids only, **never the term**; `roomId` is null for a search across rooms. NOT coalesced (the per-user `search` bucket bounds the rate, and the refusal costs nothing). No throwable.
- `search.timeout` (byUserId/roomId) - a search (`GET /rooms/{id}/search`, `GET /search`, 0.5.0, `threads/ThreadSearchService.kt`) outran its request deadline (`search.timeoutSeconds`, 5 s by default, ONE deadline for all its statements; the database cancelled the statement, SQLSTATE `57014`, or the deadline had passed between two statements) and was answered `429` of type `urn:adder:problem:search-timeout` with `Retry-After: 2`. Ids only, **never the term**; `roomId` is null for a search across rooms. NOT coalesced (each rejection cost the caller the whole budget, and the `search` bucket bounds the rate). The throwable is not logged. A search that succeeds is a read: no audit line,
- `room.busy` (byUserId/roomId) - a room write (any thread, message, member, workflow or record write; `withRoomWrite`, `rooms/RoomLock.kt`) waited longer than the bounded room-lock wait (`rooms.lockWaitMillis`, 3 s by default) for another writer to finish and was answered `429` of type `urn:adder:problem:room-busy` with `Retry-After: 1`; nothing was written. Ids only; NOT coalesced (each rejection cost the caller the whole wait, and the per-user buckets bound the rate). The first signal of a runaway writer or a stuck transaction holding a room (`security.md` "The room lock wait is bounded"),
- `room.access_denied` (byUserId/roomId) — a signed-in caller who is NOT a member (and not an ADMIN) addressed a room that EXISTS, through any room route (GET, PUT, archive, unarchive,
  DELETE, the workflow read, the member list, add, role change and removal). The response is the uniform `404`, the same as for a room that does not exist, so this line is the only trace of someone probing room ids;
  it is NOT emitted for an id that matches no active room (nothing was denied), nor for an ADMIN. Emitted by the route preamble (`rooms/RoomAccess.kt`) before the guard answers,
- `live.ticket_saturated` (byUserId) - `POST /api/v1/live/tickets` (0.4.0 S3) found the in-memory ticket store at `live.maxTickets` after sweeping the expired ones and answered `429` (`live/LiveRoutes.kt`; no ticket is minted). **Coalesced** per user (`RateLimitAuditThrottle`, key `live.ticket_saturated:<userId>`): at most one line per user per 60 s with a `suppressed` count of the rejections folded in (0 on the first); the answer carries `Retry-After: 30`. A ticket never appears in any log line (`LiveTicketTest`),
- `live.refused` (reason, suppressed; `byUserId` once the caller is known) - the live channel (0.4.0 S4, `live/`) refused or closed a connection or a mint. `reason` is one of `origin` (the WebSocket upgrade's `Origin` was missing, foreign, of another scheme than the request's, or malformed, or the request's `Host` was unreadable: `403`; carries a printable, 100-character `origin` and a `detail` = `authority` | `scheme` | `host`), `capacity` (a `503`: `limit` = `sockets`, `pending-per-address` or `pending-auth`, or a redeem into a full table, or an upgrade that lost the race for the last unauthenticated slot: close 1013), `stopping` (a `503` from a server that is shutting down, at the upgrade or the mint), `listener-down` (a mint `503` while the notification listener has been down for more than 10 s; `byUserId`), `auth` (the socket closed 4401 for no ticket by the deadline or an unknown, used or expired one, or 4003 when the ticket's token no longer verifies, `verdict` = revoked | user_gone | credential_changed | roles_changed), `socket-cap` (the per-user cap, `limit`: the mint's `429 live-socket-limit` or the redeem's 4429), `subscription-cap` (a subscribe went over 128 rooms; those ids are `capped` in the ack), `inbound-rate` (more than 30 inbound frames in 10 s on one socket: close 1008; `limit`) and `bad-frame` (1003 or 1008: binary, malformed, unknown, misplaced or invalid ids; `detail`; a fragmented text message is reassembled by Ktor before the loop sees it and is judged as one message). **Coalesced** through `RateLimitAuditThrottle` (key `live.refused:<reason>` before authentication, `live.refused:<reason>:<userId>` after it, with `:<limit>` appended when the line names a `limit`, and `live.refused:origin:<origin host>` for `origin`, in a set of at most 64 hosts per process, the rest sharing `*`): at most one line per key per 60 s with a `suppressed` count (0 on the first). The client address is deliberately NOT a field (the `rate_limit.exceeded` rule). Not audited: connects, normal closes, the 1001 close of a stopping server, `resync`. With `http.corsHosts` set the CORS plugin refuses a foreign origin before the route (bodiless `403`, no `live.refused origin` line). Tests: `LiveAdmissionTest`, `LiveSocketTest`, `LiveBoundsTest`.
- `live.subscribe_denied` (byUserId, roomIds) - a socket's `subscribe` named ROOMS THAT EXIST AND THE CALLER CANNOT READ (the REST `room.access_denied` counterpart; an ADMIN reads every room, so never). The ack still lists them as `refused`, indistinguishable from a missing room, and an id that matches no room is never listed here. **Coalesced per user** (`live.subscribe_denied:<userId>` through `RateLimitAuditThrottle`: at most one line a minute with a `suppressed` count; the line lists the union of the rooms denied since the last line, at most 20 ids, because the 30 frames per 10 s limit alone would still allow a line every 0.3 s per socket; the sweep tick FLUSHES the tail of a window whose probe stopped, so the last ids and the `suppressed` count are written within a window plus a tick). `LiveSocketTest`, `LiveBoundsTest`.
- `live.revoked` (byUserId, reason, verdict, sockets, rooms) - the 60 s account sweep (0.4.0 S5, `live/LiveSweep.kt`) found open sockets of a user whose token is no longer LIVE and closed them 4003 (`reason=verdict`), or a non-ADMIN user's subscriptions to rooms they are no longer an active member of, whose pushed `revoked` hint was lost (`reason=membership`; `rooms` = how many rooms, `sockets` = how many sockets, nothing closes; also written by the gap-triggered re-check). For `reason=verdict`, `verdict` is the lower-case `AccessVerdict` name (revoked | user_gone | credential_changed | roles_changed; several, sorted and comma-joined, when the user's sockets carry different tokens), `sockets` how many it closed. **At most one line per user per pass and reason**, so it needs no throttle. Not audited: the 4001 close at a token's `exp` (DEBUG), membership removal and room deletion (their own audit lines; the socket only hears `revoked`). `LiveRevocationTest`.
- `bootstrap.admin_password_rotated` (userId/email — the fixed seed-admin email, never the password or
  its hash; `infra/db/Bootstrap.kt`, only when `ADMIN_INITIAL_PASSWORD` actually replaced the seed
  hash, so an idempotent re-boot emits nothing) — the boot-time credential event, so an admin-password reset leaves a trail beyond a log line
  (Flow's `crypto.reencrypted` boot-backfill event comes back with `EncryptedAtRest`/`Reencrypt`, `security.md` "Encryption at rest"),
- `authz.denied` (every 403, from the `ForbiddenException` handler in `plugins/ErrorHandling.kt`,
  with method/path/byUserId/detail — `path` is the request path only, never the query string),
- `rate_limit.exceeded` (bucket/method/path/suppressed, plus `byUserId` on the per-USER `telemetry`, `directory`, `room-name`, `room-members`, `chat-post`, `chat-write`, `live-ticket` and `search` buckets — a token bucket (`plugins/RateLimits.kt`: per-IP login, refresh,
  password-reset, password-reset-confirm, mfa, ready; per-user telemetry, directory, room-name, room-members, chat-post, chat-write, live-ticket and search) rejected the request with a 429, emitted from the 429 status handler in
  `plugins/ErrorHandling.kt`; `bucket` is stamped by the bucket's own `requestKey`. **Coalesced**
  (`RateLimitAuditThrottle`): at most ONE event per bucket per 60 s, `suppressed` counting the rejections folded into it
  since the previous event (0 on the first), so an unauthenticated flood writes a line a minute per bucket instead of one
  per request; keyed by bucket only (per-IP buckets), so memory is bounded whoever floods. The per-USER `telemetry`, `directory`, `room-name`, `room-members`, `chat-post`, `chat-write`, `live-ticket` and `search` buckets are keyed by
  `telemetry:<userId>` / `directory:<userId>` / `room-name:<userId>` / `room-members:<userId>` / `chat-post:<userId>` / `chat-write:<userId>` / `live-ticket:<userId>` / `search:<userId>` instead and carry `byUserId` -- the server-minted claim of a verified token, never client text; only a signed-in
  account reaches that key, so the key space is the account count. The client address is deliberately NOT a field
  (it is `X-Forwarded-For`-derived behind a proxy, i.e. attacker-chosen text; the ingress log has it), and neither is the
  query string. The per-account lockout's 429s are their own `login.lockout`/`login.rejected_locked` events and do not
  emit this).

Field-naming convention: the acting caller is `byUserId` everywhere except the auth lifecycle
events (`login.*`, `logout`, `refresh.rejected`, `access.rejected`, `password_reset.*`), where `userId` identifies the account being
authenticated.

Never log secrets (passwords, tokens); emails/ids are fine. When adding a security-relevant
mutation or denial path, emit an `audit(...)` event alongside it and extend this list in the same
change (Toadie's one sanctioned exception was a per-user view-state PUT written on every drag —
pure view state may stay unaudited, with a documented justification) — the catalog grows with every chat mutation
(room, member, workflow, thread, message, reaction, pin: each gets its `area.event` names here in the commit that adds the route; the live channel's refusals, `live.md`). Tested in
`AuditTest` via a Logback `ListAppender` on the audit logger (the shared `LogCapture` helper in
`TestEnvironment.kt`).

**Health probes** (`plugins/Health.kt`): `GET /api/v1/health` answers `{status: ok}` whenever the
process serves requests (the image's `HEALTHCHECK`, the compose healthcheck and the k8s liveness
probe); `GET /api/v1/ready` additionally round-trips the database (one `SELECT` on `users`, 3 s
budget) and answers a `503` problem while it fails (the k8s readiness probe and the e2e global
setup wait on it). Both are public and unaudited — they are called every few seconds and disclose
nothing. `/ready` alone rides a per-IP bucket (`ready`, 60/min, security.md; the kubelet needs 12/min), so an over-limit caller gets a `429` (the generic `rate_limit.exceeded` audit, bucket `ready`); `/health` does no I/O and is unbucketed. Tests: `HealthTest` (the `ReadinessProbeKey` seam swaps the database probe for a failing
one).

### Telemetry

`telemetry/` (the `ops` schema, `persistence.md` "Telemetry (V9)") is how Adder measures itself: **the process samples itself into
Postgres, and the ADMIN read API ("Read API" below) and the panel read the rows.** Adder is ONE process (the process `role` is the constant `app`), but the
design is multi-process-safe (two replicas, a rolling deploy) and kept that way on purpose. `configureTelemetry` runs after the database and monitoring seams (`application.yaml`); `telemetry.enabled` (env
`TELEMETRY_ENABLED`, default true) switches the sampler off, the suite sets it false (`TestEnvironment.configureApp`). The
`telemetry.*` block is validated on every boot, enabled or not: `sampleIntervalSeconds` 60..1800 and a divisor of 3600 (default 300; it is the stored raw `resolution`; validated by
`plugins/Monitoring.kt`, which needs it for the timers' window) and `storageIntervalSeconds` 900..86400 (3600), the cadence of the storage collector below; env `TELEMETRY_*` (README).

- **Per process, every tick** (`TelemetrySampler`, a `SupervisorJob` scope on `Dispatchers.IO`; the first tick 5 s after
  `ApplicationStarted`, then aligned to the interval; cancelled on `ApplicationStopping`, before the pool is disposed): `JvmProbe` —
  heap used/committed, the old generation's peak since the previous tick (`MemoryPoolMXBean` peak, reset after each read), non-heap, direct
  buffers, process CPU as a fraction of the available processors (CPU-time delta over wall delta), `ThreadMXBean.threadCount` (never a
  thread dump), GC count and time per collector as deltas; `CgroupProbe` — `/proc/self/status` `VmRSS`, and the memory of the process's OWN cgroup (from `/proc/self/cgroup`, joined under
  the mount root, falling back to the root itself; v2 `memory.current`, `memory.stat` `anon` and `inactive_file`, `memory.peak` — a LIFETIME peak, unlike the per-interval `mem.old_gen_peak` — `memory.max`; v1 fallback; "working set" =
  current − inactive_file, the figure the kubelet evicts on); the connection pool (`PoolStatsKey`: acquired, idle, pending, allocated); the live channel (`LiveCollectors.kt`, collector `live`, JVM group: `live.sockets`, this process's authenticated sockets, every tick, and `live.listener_up`, 1 once the LISTEN session has reached LISTEN, 0 while a started listener is not up, with NO row until the listener has started, since it starts lazily on the first ticket mint and "never started" is not an outage; a sample caught in the moments between the first mint and the first LISTEN reads 0 once, deliberately: hiding "started, never connected" would hide the outage the gauge exists for); and `LatencyCollector` (below). A missing
  file (macOS, no memory controller, an old kernel without `memory.peak`) is a null figure and no row, never an error. The metric
  registry (names ≤ 48 chars, kind GAUGE|DELTA|PERCENTILE, unit, group) is `TelemetryMetrics.kt` — a new metric needs no migration.
- **Cluster section, one process per interval.** After its own samples a tick tries `pg_try_advisory_xact_lock(TELEMETRY_LOCK_KEY)`
  (`persistence.md`); the holder runs the cluster collectors in ONE transaction, each in a savepoint (a
  failing collector is undone alone and the rest still run), and commits their readings with a `telemetry.cluster_ms` marker row. A process that
  loses the try-lock, or finds the marker already at this interval's bucket, skips — so two replicas do the work
  once. The cluster collectors (`ClusterCollector`; per-process ones are `ProcessCollector`): **retention** (the hourly rollup, below, and the prune), and **notify** (`live.notify_queue_usage`, STORAGE group, RATIO: `SELECT pg_notification_queue_usage()`, the fraction of the cluster's NOTIFY queue in use, which fills when a LISTENing connection stops reading and then makes every notifying commit fail; a catalog function, read once per interval by the lock holder); the storage collector is the heavy one.
- **Requests (`LatencyCollector.kt`, `LatencyCollectorTest`).** Every tick it reads Ktor's Dropwizard TIMERS and status METERS from the registry (`MetricRegistryKey`;
  never its gauges) and writes, per normalised route (label `/api/v1/users/{id}`) and for `*` (every `/api/` route except `/api/v1/telemetry` and the `/api/v1/health` / `/api/v1/ready` probes),
  `http.count` (delta of the timer count against the value kept in memory; the first tick counts since process start), `http.p50`/`http.p95` (ms; the windowed snapshots of the
  timers that saw a request, merged into one set of observations per label, so `*` is a real percentile) and `http.5xx`/`http.429` (deltas of the per-status meters). A label has
  rows only in an interval it saw something (a 5xx/429 row only when non-zero). Series names are `ktor.calls.<route>.timer|meter|<status>` with `<route>` the whole selector chain
  (`/(authenticate "default")/api/v1/users/{id}/(method:GET)`); `normaliseRoute` strips every parenthesised selector (pinned by a test against the
  names Ktor 3.6 writes). Only ROUTED series are read: a `.<status>` meter counts when its route also has a `.timer` (the timer is stopped in the same hook that marks the
  status meter; the all-status `.meter` is marked earlier, when the routed call starts, and is not used), and a name containing `?` (Ktor's fallback name for a
  response it did not route: the URI, query included) is ignored. **Ktor marks a response each time the response pipeline runs**, and `ErrorHandling`'s `status(429)`
  handler answers the RateLimit plugin's bodiless 429 a second time: on a route with wrapping selectors that second mark lands under the URI-named fallback (ignored), on
  a bare route it lands twice in the `429` meter, so the surplus of a route's status meters over its timer delta is taken off its 429 count (a thrown exception is
  counted once; a 5xx needs no correction). `previous` keeps only the names seen in the latest tick.
- **Heavy collector: storage (cluster, outside the lock transaction).** The cluster transaction does only the cheap work
  (retention) and DECIDES whether the heavy collector is due (its marker, `telemetry.storage_ms`, is at least its interval older than the
  current bucket); a due one is CLAIMED by a marker row of value 0 committed with the cheap readings, so a heavy run is attempted at most once per interval whatever its
  outcome (a failure is not retried before the next interval, a rollback of the light phase claims nothing). After that commit the heavy phase runs under
  its own budget (`TelemetryConfig.heavyBudgetMillis` = 2/5 of the interval, so light + heavy fit in an interval), every statement in a SHORT transaction of its own
  (`HeavyReader`; `queryTimeout` 30 s, at most 3/4 of the heavy budget - 18 s at a 60 s interval), the readings inserted in one more transaction, and the marker set to the run's
  duration in milliseconds (at least 1; a marker still at 0 is a claim that did not complete). A failure is one WARN per collector then "recovered"; the heavy phase
  overrunning its budget is the collector `heavy`. No lock or long transaction spans the scans, so a deploy's `ALTER` is never queued behind them.
  **Storage** (every `storageIntervalSeconds`): `db.size` (`pg_database_size`) and `db.schema.size` per `public`/`ops` (and every schema a later migration adds): the sum of
  `pg_total_relation_size` (heap + TOAST + indexes) over relations of kind r/m. Catalog reads only.

- **Resolution and retention.** A raw sample (`resolution` = the sample interval in seconds, so any value below 3600) is `(ts = the interval bucket, process, metric, label, n = 1, avg = max =
  value)`, written with `ON CONFLICT DO NOTHING` (a repeated tick in a bucket adds nothing). Once an hour ended at least `max(10 minutes, interval / 2)` ago, the
  next cluster section folds every complete hour after the newest hourly row into hourly rows (`resolution` 3600: `n` = sum of the samples,
  `avg` = their n-weighted mean, `max` = the largest max, so a 5-minute peak survives; for a DELTA metric `avg * n` is the hour's total) — one idempotent
  `INSERT … SELECT … GROUP BY … ON CONFLICT DO NOTHING`. Raw rows are deleted after 7 days, hourly rows after 90, and a process unseen for 90 days
  with its samples (`ON DELETE CASCADE`). The prune runs only after a rollup that INSERTED rows (about once an hour); a rollup that fails throws before it, so raw rows are never pruned while the rollup keeps failing.
- **Failure isolation (it must never be the reason something else is slow or down).** Every collector runs in `catchingFailures` and is
  independent of the others; the whole tick runs under `withTimeoutOrNull(interval / 2)`; every statement under the transaction's
  `queryTimeout` (a quarter of the interval, 15..60 s; the only way a statement timeout may be set). Per collector, the FIRST failure is one WARN
  (`Telemetry collector <name> failed: <exception toString>`, no stack; a database failure renders as class names and SQLSTATE only, `scrubbedSummary()`), repeats are DEBUG, and the first success afterwards one INFO
  `Telemetry collector <name> recovered` (logger `ch.nokillswit.telemetry.TelemetrySampler`; the tick budget is the collector `tick`). It touches neither
  `/ready` nor any other probe, and no audit event: it records no user action. Pinned by `TelemetrySamplerTest`.
- **Never recorded:** JVM arguments, environment variables, system properties, request paths with identifiers, any user or chat data. A process
  row holds the role, the instance (`HOSTNAME`, else the resolver's host name, ≤ 100 chars), the JVM start, the build version and commit
  (the image's `ADDER_BUILD_VERSION`/`ADDER_BUILD_COMMIT`, stamped with `--build-arg`; null outside the image), the available processors,
  the heap maximum and the container memory limit. The `ops` tables hold only numbers and these labels.
- **Seams** (lower layers publish, `telemetry/` reads; nothing imports it — `PackageBoundaryTest`): `plugins/Monitoring.kt`'s `MetricRegistryKey`,
  `infra/db/Database.kt`'s `PoolStatsKey` (pinned present for r2dbc-pool 1.0.2 by `PoolStatsTest`) and `infra/notify/LiveStats.kt`'s `LiveStatsKey` (a `() -> LiveStats(sockets, listenerUp?)` published by `live/LiveRoutes.kt`'s `configureLive`, so `telemetry/` never imports `live/`); the test seams are
  `TelemetryOverridesKey` (clock, probes, identity, first-tick delay) and `TelemetrySamplerKey` (the running sampler).
- **Read API (ADMIN; `TelemetryRoutes.kt`, `TelemetryReads.kt`, `TelemetryCurrent.kt`, `TelemetryHistory.kt`, `TelemetryWire.kt`; `authorization.md`).** Two GETs, registered with the feature
  routes, every statement under a 5 s `queryTimeout`. `GET /api/v1/telemetry/current` answers `{generatedAt, sampleIntervalSeconds, processes[], storage, notifyQueue, requests}`:
  `processes` = every process seen in the last 24 h that is live, every stale one seen within the last hour (a wedged replica, a just-replaced pod), plus per role the newest older stale one when none is alive (`stale` = no tick, or no sample, for more than 3 sample intervals: `last_seen` or the newest sample's bucket that old, or no sample at all for a process older than that), each with the figures of ITS newest raw
  bucket (a figure that bucket did not write is null, never carried over): the `memory` block (heap used/committed/max, old-gen peak, non-heap, direct, rss, cgroup current/anon/working set/peak,
  `containerLimitBytes`, `percentOfLimit` = working set / limit in percent, one decimal, null without a limit) and the `jvm` block (cpu ratio, threads, GC count/time summed over collectors, the four pool figures, `liveSockets` and `liveListenerUp`: null before the listener started);
  `storage` = the newest `db.size` / `db.schema.size` measurement and, as its own `notifyQueue {measuredAt, usage}` field (independent of `storage`, which stays null until the hourly measurement), the newest `live.notify_queue_usage` (0..1, every sample interval, not hourly); `requests` = the newest bucket of the live processes (counts and errors add up, p50 weighted by the requests of the processes that wrote one, p95 the largest): the `*` total and the 15 busiest routes; `windowSeconds` is the counts' window (the sample interval), `percentileWindowSeconds` the p50/p95 window (the interval capped at 300 s, the timers' reservoir). `GET /api/v1/telemetry/history?group=memory|storage|requests|jvm&range=24h|7d|90d`
  (both required, a `400` otherwise) answers `{group, range, bucketSeconds, from, to, historyFrom, truncated, series[{key, metric, label, role, connectionId, unit, kind, points[{t, avg, max}]}]}`.
  Buckets: 24h = the sample interval (widened to a whole multiple of it only if 24 h would exceed 400 points, i.e. an interval under 216 s), 7d = 3600 s (hourly rows PLUS the raw rows after the newest hourly row, so the
  current hours are never missing and nothing is counted twice), 90d = 21600 s (hourly rows grouped to six hours in SQL). Per-process groups (memory, jvm, requests) merge the processes of one `role` into one series
  (`key` = `<role>:<metric>[:<label>]`) so a trend survives a restart; `requests` series merge ALL serving roles per route (`key` = `<metric>:<route>`, `role` null); storage is cluster-wide (`role` null). A GAUGE/PERCENTILE point is the n-weighted mean and the largest sample; a DELTA point's `avg` is the bucket's
  TOTAL and `max` the largest single interval. `requests` keeps `*` (all five metrics) and the ten busiest routes of the range (count, p50, p95 only: the series cap).
  At most 400 points per series and 40 series (`truncated` when more matched, in registry then label order). `historyFrom` = the oldest instant the range can hold, clipped to the range: the first process start. Both reads ride the per-user `telemetry` bucket (30/min, security.md). Pinned by `TelemetryRoutesTest` (clock pinned through `TelemetryOverridesKey`).
- **Registry bound:** the `TelemetryRegistry` never registers a `ktor.calls.` name containing `?` and registers at most 2,000 `ktor.calls.` names (security.md "Telemetry"); the Slf4jReporter filters `?` names.
- **Tests:** `CgroupProbeTest`, `JvmProbeTest`, `TelemetryConfigTest`, `TelemetryStoreTest` (insert, rollup, prune boundaries, cascade, the lock),
  `LatencyCollectorTest`, `TelemetrySamplerTest` (the storage collector too), `TelemetryRoutesTest` (the read API), `PoolStatsTest`, `TelemetryRegistryTest` (the sliding window, no JVM gauge sets); the live gauges: `TelemetrySamplerTest` (the readings and the notify collector), `TelemetryWiringTest` (the production wiring), `TelemetryRoutesTest` (the read API).

### Not yet ported

Flow's sync-job, data-source, metrics, capacity and outbound-guard audit events (`data_source.*`, `sync_job.*`, `outbound.blocked`, `team.capacity_*`, `public_holiday.*`,
`crypto.reencrypted`) were dropped with their features. The chat events are added above in the commit that adds the route. Flow's "code-read refusals" paragraph (a row-budget refusal
that is neither an audit event nor a log line of its own) came with its report features and is not ported.
