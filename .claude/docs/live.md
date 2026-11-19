# The live channel

Owns: how open browsers learn about changes: the WebSocket endpoint and its handshake, the tickets, the frame protocol, the hint catalog, the NOTIFY
wire and the listener, the hub, the bounds and the deploy rules. Not here: what changes (`chat-model.md`); the decisions behind it (`product.md`
"0.4.0 live updates").
**Status: binding since 0.4.0.** The approach was decided on 2026-11-17 (WebSockets, the user's choice); this text replaces it. The feature lands in
steps (hint wire, listener, tickets, socket, revocation, web client); until a step lands, its rules describe the target, not the code. Deviations from
the 2026-11-17 text are marked **[changed]** with the reason.

## The shape

Writes stay REST. The server pushes **id-only hints** over one WebSocket per tab; the SPA invalidates the exact TanStack Query keys and refetches through
the existing REST endpoints, so authorization and OpenAPI conformance stay in one place. A hint says *that* something changed, never *what*.

- **Delivery is at-most-once, with no replay.** The refetch is the backfill; `resync` covers every gap (a listener outage, an overflowing socket queue).
- **A hint carries a kind and ids only**: never a title, body, name or field value (enforced by the `LiveHint` type).
- **Single replica.** Tickets and the hub live in memory, per process. A second replica needs sticky `/api/v1/live*` or a shared ticket table
  (`BACKLOG.md`).

## Endpoints, handshake and Origin

**`POST /api/v1/live/tickets`** (`mintLiveTicket`; bearer, any authenticated caller). Per-user bucket `live-ticket`, 60/min. Answers `200 {ticket, expiresAt}`
(an exchange, like login and refresh, so not 201), `401`, `429` (the bucket; `urn:adder:problem:live-socket-limit`, the per-user cap; ticket-store
saturation), `503 urn:adder:problem:live-unavailable` (stopping, or the global socket cap), `500`.

**`GET /api/v1/live`** (`connectLive`; `security: []`, authenticated in-band by the first frame). The typed `@Resource` idiom with `webSocket {}` nested
in it; a plain (non-Upgrade) GET answers 400 after admission (403 before 400).

- **[changed] The ticket travels in the first frame, never in the URL.** The 2026-11-17 text put it in `?ticket=`. API-AUTH-002 says tokens MUST NOT appear
  in the URL path or query string, and a query credential lands in every proxy access log. So the client sends `{"type":"auth","ticket":…}` within 5 s of
  the upgrade. The cost: the WebSocket GET is public at the HTTP layer, so an unauthenticated socket lives at most 5 s, under a pending cap and a per-IP
  bucket. The `Authorization` header never reaches the socket.
- **Browsers hide a refused handshake's HTTP status** (the page sees only `error` and close 1006). So every refusal the client must act on goes on the
  ticket POST (fetch sees the problem document) or into a 4xxx close code.

**Admission order:** per-IP bucket `live-connect` (600/min) -> 429; Origin -> 403; stopping -> 503; global sockets -> 503; global pending-auth -> 503;
otherwise 101. The caps are ADVISORY at admission (a few upgrades in flight can slip past) and authoritative afterwards: the hub's register enforces `maxSockets` and `maxSocketsPerUser`, the pending counter is incremented when the session starts. `RequestBodyLimit` is told the upgrade has no body (`Long.MAX_VALUE`; wrapping the request channel starves the test host's socket of client frames).

**Origin rule (CSWSH defence).** The header is required and its scheme must be `http` or `https`. It is allowed when its authority (case-insensitive,
default ports dropped) equals `call.request.origin.serverHost:serverPort` (`XForwardedHeaders` rewrites it when `http.behindProxy` is on), or the origin is
listed in `http.corsHosts`. Anything else (a missing header and `null` included) is `403` with `live.refused reason=origin`. It works unchanged for Vite
(5179, Host preserved), compose (8087) and `testApplication` (`http://localhost`).

**[changed] The CSP is unchanged.** `connect-src 'self'` already matches same-host `ws:`/`wss:` under CSP3. Adding `ws:`/`wss:` scheme sources would allow
sockets to ANY host: do not.

## Frames and close codes

Client to server (JSON text frames, parsed strictly):

```
{"type":"auth","ticket":"<43-char base64url>"}   first frame, within 5 s, exactly once
{"type":"subscribe","roomIds":[12,40]}            1..128 positive ids
{"type":"unsubscribe","roomIds":[40]}
{"type":"ping"}
```

Server to client (the client ignores unknown types and fields):

```
{"type":"ready","protocol":1,"maxSubscriptions":128}
{"type":"subscribed","roomIds":[12],"refused":[40],"capped":[]}
{"type":"event","kind":"message.posted","roomId":12,"threadNumber":7,"messageId":991}
{"type":"revoked","roomId":12}
{"type":"resync"}
{"type":"pong"}
```

`refused` deliberately mixes "no such room" with "not readable" (uniform, like the REST 404); `capped` lists the ids over 128. The frames are typed in the
OpenAPI spec as components (`LiveClientFrame`, `LiveServerFrame`, `LiveEventKind`) and generated into `schema.ts`; the Kotlin `LiveKind` enum is pinned
equal to the spec enum and to the audit names.

| Code | When | Client |
|---|---|---|
| 1000 | client closes (sign-out, unmount, hidden 5 min) | none |
| 1001 | server stopping (`ApplicationStopPreparing`) | reconnect after a random 1-5 s |
| 1003 | binary frame | backoff |
| 1008 | malformed, unknown or over-long frame; a frame before `auth`; a second `auth`; inbound rate | backoff |
| 1009 | frame over 8 KiB (Ktor) | backoff |
| 1011 | internal error | backoff |
| 4001 | the minting access token's `exp` passed | re-mint at once |
| 4003 | the sweep verdict is not LIVE, or the token was revoked at redemption | re-mint at once (`authedFetch` refreshes or signs out) |
| 4401 | no ticket, or an unknown, expired or used one, by the deadline | backoff |
| 4429 | per-user socket cap at redemption | "paused"; retry in 60 s |
| 1013 | the global socket table filled between admission and redemption, or the unauthenticated-socket cap between admission and the session start (rare races; `live.maxSockets`, `live.maxPendingAuth`) | backoff |

If 4001 or 4003 arrives twice within 10 s, the client falls back to backoff. No exception escapes a session (StatusPages cannot answer after a 101): catch,
log, close with 1011.

## Hints: the catalog and delivery

**[changed] A hint carries `threadNumber`, never `revision`.** The 2026-11-17 text proposed `{kind, roomId, threadId, messageId?, revision}`. The SPA keys
threads by number, and a thread's `revision` moves only on a details save, so it signals almost nothing.

Sixteen kinds; the wire name equals the audit name. `Recipients` is who gets an `event`; a *target* gets the personal frame on every socket it has,
subscribed or not (it may not be subscribed yet).

| Kind | Extras | Recipients |
|---|---|---|
| `room.created` | targets [creator] | targets |
| `room.updated` | | subscribers |
| `room.archived` / `room.unarchived` | | subscribers |
| `room.deleted` | targets = the active members, read before the delete | subscribers and targets get `revoked` |
| `room.member_added` | targets = the added users (at most 500) | subscribers and targets |
| `room.member_role_changed` | targets [user] | subscribers |
| `room.member_removed` | targets [user] | subscribers; non-ADMIN targets get `revoked` |
| `room.workflow_updated` | | subscribers |
| `thread.created` / `thread.updated` / `thread.status_changed` / `thread.owner_changed` | threadNumber | subscribers |
| `message.posted` / `message.edited` / `message.deleted` | threadNumber, messageId | subscribers |

- A socket gets at most one frame per hint. `room.member_removed` makes each non-ADMIN target socket drop the room (ACTIVE or PENDING) and receive
  `revoked`; `room.deleted` does the same for every subscriber and target.
- **Emitted only on a real change.** A no-op write (the same status, role, owner or details; archiving twice; an unchanged edit; adding an existing
  member) emits nothing. A refused or rolled-back write emits nothing. The opening message is part of `thread.created`. `moveThreads` (covered by
  `room.workflow_updated`) and `touchActivity` emit nothing. Team, user and auth writes emit no hint.
- **[changed] No single hook.** The 2026-11-17 text said "every write issues `pg_notify`". There is no common write path (`RoomService.create` is not under
  `withRoomWrite`, and several writes return early on a no-op), so each of the 15 change branches calls `notifyLive` itself, right before returning,
  inside the same transaction.
- **Event catalog and semantics** (API-CACHE-004) live in the `connectLive` description in the spec, copied from this section.

## The NOTIFY wire and the listener

**[changed] The channel is `adder_live`** (not `adder_events`, which suggests `thread_events` and `infra/db/EventLog.kt`). The payload is the `LiveHint`
JSON with `v: 1`. `infra/notify/Notify.kt` is infra, so `rooms` and `threads` call it without importing `live`: `notifyLive(hint)` runs
`SELECT 1 FROM pg_notify('adder_live', ?)` on the current transaction (the `MultiRowInsert.kt` `exec` precedent), consumes the result (an R2DBC statement
nobody subscribes to never runs) and `check`s the payload is under 8000 bytes. The worst case is a 500-id `room.member_added`, about 5.6 KB, so no
chunking; a test pins it. The Postgres facts and hazards are in `persistence.md` "NOTIFY".

**The listener** (`infra/notify/NotifyListener.kt`) is the single documented exception to the one bounded pool: one dedicated, non-pooled R2DBC connection
per process.

- **The connection** bypasses the pool, `StatementTimeoutCachingConnectionFactory` and `DetachingConnectionFactory`; it keeps
  `LeakedSocketClosingConnectionFactory`. It has its own `application_name` (`<applicationName>-live`), because `ConnectionPoolTest` counts the pool's name
  exactly. Pool sizing becomes `(maxSize + 1) x replicas + 1`.
- **Start** is lazy, on the first ticket mint (`ensureStarted()` is idempotent): connect, keep `pg_backend_pid()` for tests, `LISTEN adder_live`,
  subscribe to `getNotifications()` with unbounded demand and `trySend` each payload into a `Channel<String>(1024)`. A failed send is a gap.
- **Why unbounded demand:** in r2dbc-postgresql 1.1.3 the notifications flux is best-effort multicast, so a subscriber without demand silently loses them.
  And the listener must never stop reading: a stalled listening connection fills the cluster's notification queue and then every notifying commit fails.
- **Health:** a `SELECT 1` heartbeat every 30 s with a 5 s timeout (a half-open TCP connection leaves the listener "up" but deaf). On a flux error or
  completion or a failed heartbeat: close, reconnect with jittered backoff (1, 2, 4 ... 30 s). After every successful LISTEN (the first included) and every gap, `onGap` makes the hub
  broadcast `resync`.
- **Logs:** WARN once per outage, INFO on recovery, nothing per notification, no audit. `ApplicationStopped` closes the connection.

## Tickets

`live/LiveTickets.kt`, ported from `auth/MfaChallenges.kt`. A ticket is 32 bytes from `SecureRandom`, base64url (43 characters), stored by its SHA-256 as
`LiveTicketBinding(userId, isAdmin, roles, jti, credentialRevision, tokenExpiresAtMillis, expiresAtMillis)`. TTL 30 s; single use (redeeming is an atomic
remove); expired entries are swept on every mint; capacity `live.maxTickets`, and when full it answers 429 and audits `live.ticket_saturated`. Masked
`toString`, injectable clock. The ticket is never logged and never in a URL.

The route needs the token's `jti`, `exp` and `credentialRevision`, which `CallerPrincipal` lacks: `plugins/Security.kt` `validate {}` puts an
`AccessToken` into `call.attributes` (`authz/AccessToken.kt`). `CallerPrincipal` is unchanged.

**Redemption, in order:** (1) redeem the ticket, or 4401; (2) `TokenBlocklistService.accessVerdict(...)` must be LIVE, or 4003; (3) now before the token's
`exp`, or 4001; (4) the per-user cap has room, or 4429; (5) send `ready`.

## The hub

`live/LiveHub.kt` is one actor coroutine with one inbox channel. It owns `socketsByUser`, `subscribersByRoom` and each socket's per-room state (PENDING or
ACTIVE). Each socket has its own writer draining a `Channel<Frame>(64)`; the hub only ever `trySend`s, and on overflow clears that queue and enqueues one
`resync`.

- **Subscribe is race-free:** (1) the actor registers the rooms as PENDING; (2) outside the actor, `RoomService.readableRoomIds(userId, isAdmin, ids)` runs
  one query (ADMIN: every existing, non-deleted id; others: active membership, the same `active()` predicate as `readStanding`), returning `readable` and
  `deniedExisting` (the latter is audited); (3) the actor activates only the ids still PENDING, so a `room.member_removed` or `room.deleted` processed in
  between has already dropped them; (4) the ack goes out. Hints for a PENDING room are dropped; the client's refetch on the ack covers them.
- **Subscriptions** are the caller's rooms only; an ADMIN may subscribe to any existing room, and a demoted ADMIN keeps those subscriptions until the next
  sweep (at most 60 s).
- **Account standing.** Each socket closes with 4001 at its token's `exp`. Every 60 s a sweep calls `TokenBlocklistService.accessVerdicts(...)` (batched in
  chunks of 1000; the SAME decision function as `accessVerdict`, refactored so the two cannot drift); a verdict other than LIVE closes the socket with 4003
  and audits `live.revoked`. This catches logout, delete, password change and role change without touching any `auth/` or `users/` write path.
  Membership removal and room deletion are pushed at once (`revoked`), not swept. `sweepNow()` is an internal test seam.
- **Gap:** `onGap` sends `resync` to every socket (one atomic flag coalesces concurrent gaps; the listener starts with the first ticket mint, so a socket registered before its first LISTEN completes hears that LISTEN's own `resync`).
- **Delivery in the socket step (S4):** a hint goes to the ACTIVE subscribers of its room; the personal frames (`revoked`, the hints for a room the user was just added to), the 60 s account sweep and the close at the token's `exp` are the next step's. A full socket queue loses its events (and a pending pong) to ONE `resync` but keeps `ready`, `subscribed` and `revoked` (the socket is closed 1013 if even that cannot fit). Hints wait for the actor in a bounded count (`MAX_QUEUED_HINTS` 1024; past it a hint is a gap, i.e. a `resync`), while commands (register, unregister, subscribe) are never dropped. A session that is cancelled while it waits to register still unregisters (its socket is recorded first; the inbox is FIFO).
- **Shutdown:** `ApplicationStopPreparing` refuses admissions and mints with 503, then closes every socket with 1001 within 2 s; `ApplicationStopped`
  cancels the scope and closes the listener.

## Bounds

| Bound | Default | Where | On breach |
|---|---|---|---|
| connects per IP | 600/min | `RateLimits.LIVE_CONNECT` (`security.rateLimit.liveConnectPerMinute`) | 429 + `Retry-After`, `rate_limit.exceeded` |
| unauthenticated sockets | 256 | `live.maxPendingAuth` | 503 |
| auth deadline | 5 s | `live.authTimeoutMillis` | 4401 |
| sockets (global) | 1000 | `live.maxSockets` | 503 (upgrade and mint) |
| sockets per user | 16 | `live.maxSocketsPerUser` | mint 429 `live-socket-limit`; redeem 4429 |
| tickets held | 10,000 | `live.maxTickets` | 429 + `live.ticket_saturated` |
| mints per user | 60/min | `RateLimits.LIVE_TICKET` | 429 |
| ticket TTL | 30 s | constant | 4401 |
| subscriptions per socket | 128 | constant, announced in `ready` | `capped` + coalesced `live.refused reason=subscription-cap` |
| ids per subscribe frame | 128 | constant | 1008 |
| inbound frames | 30 per 10 s | constant | 1008 + `live.refused reason=inbound-rate` |
| frame size | 8 KiB | `maxFrameSize` | 1009 |
| outbound queue | 64 frames | constant | one `resync` |
| protocol ping / pong timeout | 25 s / 15 s | `pingPeriod` / `timeout` | Ktor closes |
| app ping (client) | 25 s; pong within 10 s | client | reconnect |
| socket life | the token's `exp` (at most 900 s) | binding | 4001 |
| account sweep | 60 s | constant + `sweepNow()` | 4003 + `live.revoked` |
| listener channel | 1024 | constant | `resync` to all |
| listener heartbeat | 30 s, 5 s timeout | constant | reconnect, then `resync` |
| hidden tab | 5 min | client | close 1000; reopen when shown |
| indicator delay | 5 s down | client | badge |

Only the keys tests must shrink are config (`requireConfigInt`/`requireConfigLong`, each pinned in `NumericConfigBoundsTest`): `live.maxTickets` 1..100,000,
`live.maxSockets` 1..10,000, `live.maxSocketsPerUser` 1..64, `live.maxPendingAuth` 1..10,000, `live.authTimeoutMillis` 100..30,000.

**"Idle close"** is four mechanisms, not one timer: the protocol heartbeat, the 5 s auth deadline, the close at the token's `exp`, and the client closing a
socket after 5 minutes hidden. A visible, authenticated socket is never closed for being quiet. **One socket per tab**; the 17th tab of a user shows "Live
updates off" and retries every minute (a shared leader-tab socket is a deliberate non-goal for now, revisit with 0.6.0 unread counts).

**Audits** (names in `observability.md` when each lands): `live.refused` (`reason` = origin | capacity | auth | socket-cap | subscription-cap | inbound-rate |
bad-frame; **[changed] no `ip`**: the `rate_limit.exceeded` rule, the client address is attacker-chosen text behind a proxy and the access log has it; `byUserId` after authentication, a printable `origin` for the Origin refusal; coalesced through `RateLimitAuditThrottle`, with a `suppressed` count), `live.subscribe_denied` (`byUserId` and
the EXISTING rooms the caller cannot read; never ids that do not exist; not coalesced, like `room.access_denied`), `live.revoked` (at most one line per user per
sweep), `live.ticket_saturated`, and the generic `rate_limit.exceeded` for `live-connect` and `live-ticket`. Not audited: connects, normal closes, 4001,
`resync` (DEBUG; the gauges count sockets). **Telemetry:** the latency collector excludes `/api/v1/live`; each socket is one OTel server span for its whole
life (the Ktor instrumentation has no request filter; measured in the socket step); gauges `live.sockets`, `live.listener_up` and `live.notify_queue_usage`
come with the telemetry step.

## Web

One `useLiveUpdates` socket in the shell, a framework-free state machine (`utils/liveClient.ts`; injected socket factory, clock and ticket minter) and a key
map (`utils/liveKeys.ts`) from frames to TanStack Query keys. The wanted subscription set is the active rail (up to 100 rooms) plus the open room; the
archived rail and All rooms keep refreshing on focus. States `connecting | live | down | paused | stopped`; backoff 1, 2, 4, 8, 16, then 30 s, each times a
random 0.5-1, reset after 60 s live; reconnect at once on `online` and when the tab becomes visible; a reconnect invalidates the subscribed rooms.
Invalidation is debounced (focused keys 300 ms, aggregate keys at most once per 3 s, `resync` after a random 0-2 s) and NEVER cancels an in-flight fetch.
The header shows a "Reconnecting..." badge after 5 s down and "Live updates off" at once when paused. The writer's own tab refetches twice (its mutation
refresh plus the hint); accepted. Vite's proxy needs `ws: true` (no `changeOrigin`, so the Origin check matches).

## Deploy and proxies

Compose serves 8087 directly. The Kubernetes (OrbStack) `adder` namespace uses a LoadBalancer Service and no Ingress, so the socket passes at L4; the 1001
close fits inside `terminationGracePeriodSeconds` 45. A **reverse proxy** must pass the upgrade (`proxy_http_version 1.1`, `Upgrade $http_upgrade`,
`Connection $connection_upgrade`), keep read and send timeouts at 60 s or more (25 s pings fit ingress-nginx's default), and forward the host (`Host $host`,
or `X-Forwarded-Host` with `http.behindProxy=true`), otherwise every Origin check fails. With `http.behindProxy=true` the per-IP bucket also sees the real
client. Production `HttpsRedirect`: the SPA derives `wss:` from `https:`. The front-door notes are in `security.md` and `ci.md`.
