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
saturation), `503 urn:adder:problem:live-unavailable` (stopping, the global socket cap, or a deaf channel: the listener has been down for more than 10 s, "The NOTIFY wire and the listener"), `500`.

**`GET /api/v1/live`** (`connectLive`; `security: []`, authenticated in-band by the first frame). The typed `@Resource` idiom with `webSocket {}` nested
in it; a plain (non-Upgrade) GET answers 400 after admission (403 before 400).

- **[changed] The ticket travels in the first frame, never in the URL.** The 2026-11-17 text put it in `?ticket=`. API-AUTH-002 says tokens MUST NOT appear
  in the URL path or query string, and a query credential lands in every proxy access log. So the client sends `{"type":"auth","ticket":…}` within 2 s of
  the upgrade (it sends it from its `open` handler, one round trip; the default was 5 s until the 0.4.0 audit). The cost: the WebSocket GET is public at the
  HTTP layer, so an unauthenticated socket lives at most 2 s, under a pending cap in total and per client address, and a per-IP bucket. The `Authorization` header never reaches the socket.
- **Browsers hide a refused handshake's HTTP status** (the page sees only `error` and close 1006). So every refusal the client must act on goes on the
  ticket POST (fetch sees the problem document) or into a 4xxx close code.

**Admission order:** per-IP bucket `live-connect` (600/min) -> 429; Origin -> 403; stopping -> 503; global sockets -> 503; unauthenticated sockets of
the caller's address -> 503; global unauthenticated sockets -> 503; otherwise 101. **The bucket really is first** [changed in the 0.4.0 audit]: the route's
`rateLimit` runs in the plugin phases and `installLiveAdmission` intercepts in the `Call` phase (it used to intercept in `Plugins`, before the bucket, so
refused requests were never throttled; `LiveAdmissionTest` pins a foreign-Origin flood going 403 then 429). The caps are ADVISORY at admission (a few upgrades in flight
can slip past) and authoritative afterwards: the hub's register enforces `maxSockets` and `maxSocketsPerUser`, and the session's own atomic claim (`PendingSlots`)
enforces both pending caps when it starts. `RequestBodyLimit` is told the upgrade has no body (`Long.MAX_VALUE`; wrapping the request channel starves the test host's socket of client frames).

**Origin rule (CSWSH defence).** The header is required and its scheme must be `http` or `https`. It is allowed when its **scheme** equals the request's
(`call.request.origin.scheme`) and its authority (case-insensitive, default ports dropped) equals `serverHost:serverPort` (`XForwardedHeaders` rewrites all
three when `http.behindProxy` is on: `X-Forwarded-Proto`, `X-Forwarded-Host` with its port), or the origin is listed in `http.corsHosts` (**in production over `https` only**, like CORS: an `http` origin of a listed host is `403 detail=authority`; development admits both. Defence in depth: with `http.corsHosts` set, the CORS plugin judges the `Origin` first and answers a foreign or `http` origin with its own bodiless `403`, so in production this branch is normally reached by no request and writes no `live.refused` line). Anything else (a
missing header and `null` included) is `403` with `live.refused reason=origin` and a `detail`: `authority` (some other site), `scheme` (this host, the
other scheme: a TLS front door that does not send `X-Forwarded-Proto`, or `http://x:443` against an https site) or `host` (the request's own `Host` cannot be
read). [changed in the 0.4.0 audit: the scheme used to be ignored.] The `Host` header is client text: a non-numeric port (`localhost:abc`) made Ktor's
`serverPort` throw and the route answer an anonymous 500 with a stack trace; it is now a refusal like any other. Ktor reads the port after the FIRST colon, so
a bare or bracketed IPv6 literal in `Host` (`::1`, `[::1]:8087`) is unreadable too: browse a name, not an IPv6 literal (a known limitation, not a regression).
It works unchanged for Vite (5179, Host preserved), compose (8087) and `testApplication` (`http://localhost`).

**[changed] The CSP is unchanged.** `connect-src 'self'` already matches same-host `ws:`/`wss:` under CSP3. Adding `ws:`/`wss:` scheme sources would allow
sockets to ANY host: do not.

## Frames and close codes

Client to server (JSON text frames, parsed strictly):

```
{"type":"auth","ticket":"<43-char base64url>"}   first frame, within 2 s, exactly once
{"type":"subscribe","roomIds":[12,40]}            1..128 positive ids
{"type":"unsubscribe","roomIds":[40]}
{"type":"ping"}
{"type":"foreground","visible":true}
```

Server to client (the client ignores unknown types and fields):

```
{"type":"ready","protocol":1,"maxSubscriptions":128,"presenceSupported":true}
{"type":"subscribed","roomIds":[12],"refused":[40],"capped":[]}
{"type":"event","kind":"message.posted","roomId":12,"threadNumber":7,"messageId":991}
{"type":"revoked","roomId":12}
{"type":"resync"}
{"type":"pong"}
```

`refused` deliberately mixes "no such room" with "not readable" (uniform, like the REST 404); `capped` lists the ids over 128. The frames are typed in the
OpenAPI spec as components (`LiveClientFrame`, `LiveServerFrame`, `LiveEventKind`) and generated into `schema.ts`; the Kotlin `LiveKind` enum is pinned
equal to the spec enum and to the audit names.

`foreground` is additive and capability-gated: a client sends it only after a `ready` carrying `presenceSupported: true`. A visible tab renews
the frame on the normal 25-second ping cadence and sends `visible:false` as soon as it is hidden. The server keeps a 60-second process-local
lease per authenticated socket; any fresh visible socket makes the user present, and shutdown clears the registry before sockets drain.

| Code | When | Client |
|---|---|---|
| 1000 | client closes (sign-out, unmount, hidden 8 h; **[changed] 1.2 D9**: was 5 min) | none |
| 1001 | server stopping (`ApplicationStopPreparing`) | reconnect after a random 1-5 s |
| 1003 | binary frame | backoff |
| 1008 | malformed, unknown or over-long frame; a frame before `auth`; a second `auth`; more than 30 frames in 10 s | backoff |
| 1009 | a message over 8 KiB (Ktor; a fragmented text message is reassembled first and the limit applies to the total, before authentication too) | backoff |
| 1011 | internal error | backoff |
| 4001 | the minting access token's `exp` passed | re-mint after a random 0-1 s |
| 4003 | the sweep verdict is not LIVE, or the token was revoked at redemption | re-mint after a random 0-1 s (`authedFetch` refreshes or signs out) |
| 4401 | no ticket, or an unknown, expired or used one, by the deadline | backoff |
| 4429 | per-user socket cap at redemption | "paused"; retry in 60 s |
| 1013 | the global socket table filled between admission and redemption, or an unauthenticated-socket cap (total or per address) between admission and the session start (rare races; `live.maxSockets`, `live.maxPendingAuth`, `live.maxPendingPerAddress`); the notification listener has been down for more than 10 s (every open socket, and any that registers meanwhile) | backoff |

If 4001 or 4003 arrives twice within 10 s, the client falls back to backoff. No exception escapes a session (StatusPages cannot answer after a 101): catch,
log, close with 1011.

## Hints: the catalog and delivery

**[changed] A hint carries `threadNumber`, never `revision`.** The 2026-11-17 text proposed `{kind, roomId, threadId, messageId?, revision}`. The SPA keys
threads by number, and a thread's `revision` moves only on a details save or a tag save (1.2 A3), so it signals almost nothing. **A tag save hints `thread.updated` while its audit line is `thread.tags_changed`** (the one write whose hint name differs from its audit name: the SPA re-reads the same thread, list and history for it).

Nineteen kinds (the three 0.6 kinds, `notification.created`, `message.reaction_changed` and `room.pin_changed`, are the last three rows below); the wire name equals the audit name. `Recipients` is who gets an `event`; a *target* gets the personal frame on every socket it has,
subscribed or not (it may not be subscribed yet).

| Kind | Extras | Recipients |
|---|---|---|
| `room.created` | targets [creator] | targets only |
| `room.updated` | | subscribers |
| `room.archived` / `room.unarchived` | | subscribers |
| `room.deleted` | targets = the active members, read before the delete | subscribers and targets get `revoked` |
| `room.member_added` | targets = the added users (at most 500) | subscribers and targets |
| `room.member_role_changed` | targets [user] | subscribers and targets |
| `room.member_removed` | targets [user]; internal `requiresMembership` for hidden rooms | subscribers; targets who lose access get `revoked`; an ADMIN target in a visible room hears the event even when unsubscribed |
| `room.workflow_updated` | | subscribers |
| `thread.created` / `thread.updated` / `thread.status_changed` / `thread.owner_changed` | threadNumber | subscribers (`thread.updated` is the details PUT and, since 1.2, a thread's tag save: the open pane re-reads its thread and the list its rows) |
| `thread.deleted` / `thread.merged` | threadNumber | subscribers refresh the room's conversation and workflow queries. Deletion also clears cached conversation content and search hits. A targeted notice hint refreshes affected bells. |
| `message.posted` / `message.edited` / `message.deleted` | threadNumber, messageId | subscribers |
| `notification.created` | threadNumber; messageId for a message-level notice; targets = the recipients whose notice shows in the app (`in_app`; at most `MAX_ROOM_MEMBERS` = 500 since S6: 499 when a member acts, 500 when an ADMIN outside the room moves a thread all members watch; the room is told of a new thread, the watchers of a reply, a status move and a hand-over; one hint per EVENT, never per recipient: `LiveHintTest` pins 500 ten-digit ids with the widest thread number and message id under the 8,000-byte limit, `NotifyHintsTest` delivers it whole) | targets only |
| `message.reaction_changed` | threadNumber, messageId | subscribers (a reaction that changed state; `MessageReactionService.set`, audited by `ReactionRoutes.kt`) |
| `room.pin_changed` | threadNumber; messageId for a message pin | subscribers |

**Cost of `notification.created`:** each hint makes every open tab of a target re-read the notice list (the badge and the addressed count, and the drawer's page when it is open: up to two reads per tab, three with the drawer open), all under the per-user `notification-read` bucket (240/min) and the notice list's OWN read limiter (4 in flight; `security.md`). The tab spreads the re-read over a random 0 to 2 s and never re-reads for a notice about the thread the reader is looking at (`web-internals.md` "Unread").

**The per-user channel (0.6).** A hint that names `targetUserIds` already reaches every socket of those users, subscribed to the room or not, because the hub indexes sockets by user and a socket's user is its ticket's user: "subscribing to yourself" needs no frame, no check and no new ticket rule. So 0.6 adds no second NOTIFY channel, no client frame and no change to tickets, bounds or close codes; it adds one delivery class, **targets only** (`Audience.TARGETS` in `live/LiveHubModel.kt`, next to `SUBSCRIBERS` and `SUBSCRIBERS_AND_TARGETS`: `room.created` joins `notification.created` there instead of being a special case of `fanOut`). The frame is the existing `event` (`kind`, `roomId`, `threadNumber`, `messageId`); a recipient was an active member of that room when the write committed, so the ids tell them nothing new. A post that mentions someone emits `message.posted`, then `notification.created`, in one transaction; a hand-over emits `thread.owner_changed`, then ONE `notification.created` to the new owner and the other watchers (to the other watchers alone when the steward took the thread themself). The order is structural: the message's followers (`MessageFollower`, `persistence.md` "The follower seam") run after the counters and the write's own hint, so a follower's hint follows it (`NotifyHintsTest`). A thread's opening message emits `thread.created`, then the notice (every active member but the author); a post `message.posted`, then the notice of its watchers and mentioned; an edit `message.edited`, then the notice for the NEWLY mentioned; a status move `thread.status_changed`, then the notice of the watchers; a delete only `message.deleted`. **Read state emits nothing** (a read marker, "room seen", "notification seen"): it is unaudited view state, and a hint per acknowledgement would multiply a busy thread's NOTIFY rate by its readers (`persistence.md` "NOTIFY"); the person's other tabs hear of a settled NOTICE over the browser's `BroadcastChannel` (1.2 D9, "The hidden tab"), anything else catches up on focus. A reaction or pin that is already in the requested state emits nothing. A tab still running an older SPA treats an unknown kind as "re-read that room" (harmless). Each new kind is named after a real `audit(...)` line, which `LiveHintTest` requires. The 1.22 account recheck uses the same PostgreSQL channel and stays inside the server. See "Account rechecks" below.

- A socket gets at most one frame per hint. `room.member_removed` makes each target socket that loses access drop the room (ACTIVE or PENDING) and receive
  `revoked`; `room.deleted` does the same for every subscriber and target.
- **Emitted only on a real change.** A no-op write (the same status, role, owner or details; archiving twice; an unchanged edit; adding an existing
  member) emits nothing. A refused or rolled-back write emits nothing. The opening message is part of `thread.created`. `moveThreads` (covered by
  `room.workflow_updated`) and `touchActivity` emit nothing. Team, user and auth writes emit no room event. Relevant account writes emit the internal recheck described below. A watch also emits no room event (`PUT .../threads/{number}/watch`, 1.1.0: the caller's own state, like the read marker; no `LiveKind`), nor "Mark all as read" (`POST /rooms/{id}/read-all`, 1.2: the caller's own floor and notices; the tab that sent it re-reads its own caches, the person's other tabs re-read the bell through the `BroadcastChannel` ("The hidden tab" below), and their rooms and threads catch up on focus).
- **[changed] No single hook.** The 2026-11-17 text said "every write issues `pg_notify`". There is no common write path (`RoomService.create` is not under
  `withRoomWrite`, and several writes return early on a no-op), so each of the 19 change branches (`notifyLive` call sites) calls `notifyLive` itself, right before returning,
  inside the same transaction.
- **Event catalog and semantics** (API-CACHE-004) live in the `connectLive` description in the spec, copied from this section.

## The NOTIFY wire and the listener

**[changed] The channel is `adder_live`** (not `adder_events`, which suggests `thread_events` and `infra/db/EventLog.kt`). The room payload is `LiveHint` JSON with `v: 1`. Since 1.22, the same channel also accepts an internal versioned account envelope. `infra/notify/Notify.kt` is infra, so `rooms` and `threads` call it without importing `live`: `notifyLive(hint)` runs
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
- **A deaf channel is not silent** [added in the 0.4.0 audit]. Hints committed while the listener is down reach nobody, and the `resync` after the reconnect
  only helps a socket that is still open and believes it is live. So the listener tracks `downForMillis()` (since it stopped being up, or since it started: a
  failed reconnect does not restart the clock), and past `LISTENER_DEAF_MILLIS` (10 s, a constant) the hub is DEAF (`LiveHub.isDeaf`): the ticket mint answers
  `503 live-unavailable` (audited `live.refused reason=listener-down`), and a watcher (checking every second) closes every open socket 1013, again on every
  check while the outage lasts so a socket that registered meanwhile goes too. The client's badge appears (the socket is down) and it retries with backoff;
  when the listener is back the next mint works and the reconnect's `ready` refetch is the backfill. Shorter outages stay covered by the `resync`.
- **Logs:** WARN once per outage, INFO on recovery, nothing per notification, no audit. `ApplicationStopping` closes the connection (`LiveHub.stop()`, before the scope is cancelled).

## Tickets

`live/LiveTickets.kt`, ported from `auth/MfaChallenges.kt`. A ticket is 32 bytes from `SecureRandom`, base64url (43 characters), stored by its SHA-256 as
`LiveTicketBinding(userId, isAdmin, roles, jti, credentialRevision, tokenExpiresAtMillis, expiresAtMillis)`. TTL 30 s; single use (redeeming is an atomic
remove); expired entries are swept on every mint; capacity `live.maxTickets`, and when full it answers 429 and audits `live.ticket_saturated`. Masked
`toString`, injectable clock. The ticket is never logged and never in a URL.

The route needs the token's `jti`, `exp` and `credentialRevision`, which `CallerPrincipal` lacks: `plugins/Security.kt` `validate {}` puts an
`AccessToken` into `call.attributes` (`authz/AccessToken.kt`). `CallerPrincipal` is unchanged.

**Redemption, in order:** (1) redeem the ticket, or 4401; (2) `TokenBlocklistService.accessVerdict(...)` must be LIVE, or 4003; (3) now before the token's
`exp`, or 4001; (4) register subject to the socket caps; (5) recheck the access verdict after registration, or close 4003; (6) send `ready`.

## The hub

`live/LiveHub.kt` is one actor coroutine with one inbox channel. It owns `socketsByUser`, `subscribersByRoom` and each socket's per-room state (PENDING or
ACTIVE). Each socket has its own writer draining a `Channel<Frame>(64)`; the hub only ever `trySend`s, and on overflow clears that queue and enqueues one
`resync`.

- **Subscribe is race-free:** (1) the actor registers the rooms as PENDING; (2) outside the actor, `RoomService.readableRoomIds(userId, ids)` runs
  a transaction over active rooms, memberships and the current role. Members can subscribe to their rooms. ADMINs can also subscribe to public and restricted visible rooms. The result contains `readable` and
  `deniedExisting` (the latter is audited); (3) the actor activates only the ids still PENDING, so a `room.member_removed` or `room.deleted` processed in
  between has already dropped them; (4) the ack goes out. Hints for a PENDING room are dropped; the client's refetch on the ack covers them.
- **Subscriptions** are the caller's rooms only; an ADMIN may also subscribe to any public or restricted visible room, and a demoted administrator’s stale socket closes when its current account verdict is rechecked.
- **Account standing.** Each socket closes with 4001 at its token's `exp`. Every 60 s a sweep calls `TokenBlocklistService.accessVerdicts(...)` (batched in
  chunks of 1000; the SAME decision function as `accessVerdict`, refactored so the two cannot drift: `decideAccess`); a verdict other than LIVE closes the socket with 4003
  and audits `live.revoked` (one line per user per sweep). The sweep (`live/LiveSweep.kt`) takes a snapshot of the sockets from the hub actor and completes each failing socket's `closeRequest`; **the verdict half and the membership half run independently** (a failing verdict query never skips the membership re-check, and the reverse), a failed half is **retried once after 5 s** (`SWEEP_RETRY_MILLIS`) instead of waiting a whole interval, and `sweepNow()` reports the first failure after both ran; the 4001 close is a timer in the session itself, not the hub. This catches logout, delete, password change and role change without touching any `auth/` or `users/` write path.
  Membership removal and room deletion are pushed at once (`revoked`); the same pass re-checks room access for all sockets' (user, room) pairs and pushes `revoked` for subscriptions that lost access (audited, one `live.revoked reason=membership` line per user per pass), because a hint lost in a listener gap would otherwise leave a removed member subscribed. A gap triggers that re-check straight away (coalesced), so the bound is **immediate normally, at most 60 s after a gap**. `sweepNow()` is an internal test seam. **New** subscriptions never trust the ticket-time role: `readableRoomIds` reads the stored role of the ACTIVE user, so a demoted or deleted ADMIN cannot gain new rooms (its existing ones last until the sweep, A13).
- **Gap:** `onGap` sends `resync` to every socket (one atomic flag coalesces concurrent gaps; the listener starts with the first ticket mint, so a socket registered before its first LISTEN completes hears that LISTEN's own `resync`).
- **Delivery:** a hint goes to the ACTIVE subscribers of its room and, for `room.created`, `room.member_added` and `room.member_role_changed`, to every socket of each target, subscribed or not (`room.created` and `notification.created` to their targets ONLY: a new room has no subscribers, and a notice is personal; `live/LiveHubModel.kt` `LiveKind.audience()` names each kind's class); the hub never widens on `targetUserIds` of any other kind. `room.member_removed` drops the room from each target socket that lost access (ACTIVE or PENDING) and sends it `revoked`. An ADMIN target keeps access to a visible room and hears the removal as an `event` even when unsubscribed. `room.deleted` drops the room from every subscriber and target socket and sends each `revoked`, and voids every subscribe still PENDING for it (the readable check ran before the delete, so it must not activate afterwards). Every socket gets one frame per hint. A full socket queue loses its events (and a pending pong) to ONE `resync` but keeps `ready`, `subscribed` and `revoked` (the socket is closed 1013 if even that cannot fit). Hints wait for the actor in a bounded count (`MAX_QUEUED_HINTS` 1024; past it a hint is a gap, i.e. a `resync`), while commands (register, unregister, subscribe) are never dropped. A session that is cancelled while it waits to register still unregisters (its socket is recorded first; the inbox is FIFO).
- **Shutdown:** `ApplicationStopPreparing` refuses admissions and mints with 503, then closes every socket with 1001 within 2 s; `ApplicationStopping`
  (the `backgroundScope` hook, `infra/BackgroundScope.kt`) closes the hub and its listener (bounded, 1 s), then CANCELS AND JOINS (bounded, 1 s) the one live scope that
  holds the hub actor, the hint consumer, the deaf watcher, the 60 s sweep and the recheck loop. That is before `ApplicationStopped` disposes the pool, so
  no live coroutine runs against a disposed pool or a test application's dead class loader (the old `ApplicationStopped` cancel had no join and ran after
  the pool's own dispose). `BackgroundScopeTest` pins it.

## Bounds

| Bound | Default | Where | On breach |
|---|---|---|---|
| connects per IP | 600/min | `RateLimits.LIVE_CONNECT` (`security.rateLimit.liveConnectPerMinute`) | 429 + `Retry-After`, `rate_limit.exceeded` |
| unauthenticated sockets | 256 | `live.maxPendingAuth` | 503 |
| unauthenticated sockets per client address | 8 (IPv6 by /64) | `live.maxPendingPerAddress` | 503 + `live.refused reason=capacity limit=pending-per-address` |
| auth deadline | 2 s | `live.authTimeoutMillis` | 4401 |
| sockets (global) | 1000 (hidden tabs hold slots too, 1.2 D9) | `live.maxSockets` | 503 (upgrade and mint) |
| sockets per user | 16 (a hidden tab holds one for up to 8 h, 1.2 D9) | `live.maxSocketsPerUser` | mint 429 `live-socket-limit`; redeem 4429 |
| tickets held | 10,000 | `live.maxTickets` | 429 + `live.ticket_saturated` |
| mints per user | 60/min | `RateLimits.LIVE_TICKET` | 429 |
| ticket TTL | 30 s | constant | 4401 |
| subscriptions per socket | 128 | constant, announced in `ready` | `capped` + coalesced `live.refused reason=subscription-cap` |
| ids per subscribe frame | 128 | constant | 1008 |
| inbound messages | 30 per 10 s (an application `ping` counts; WebSocket protocol ping, pong and continuation frames never reach the loop) | constant | 1008 + `live.refused reason=inbound-rate` |
| frame size | 8 KiB | `maxFrameSize` | 1009 |
| outbound queue | 64 frames | constant | one `resync` |
| protocol ping / pong timeout | 25 s / 15 s | `pingPeriod` / `timeout` | Ktor closes |
| app ping (client) | 25 s while visible; pong within 10 s (**[changed] 1.2 D9: not while quiet**) | client | reconnect |
| socket life | the token's `exp` (at most 900 s) | binding | 4001 |
| account sweep | 60 s | constant + `sweepNow()` | 4003 + `live.revoked` |
| listener channel | 1024 | constant | `resync` to all |
| listener heartbeat | 30 s, 5 s timeout | constant | reconnect, then `resync` |
| listener down | 10 s | `LISTENER_DEAF_MILLIS` | mint 503, open sockets 1013 |
| sweep retry | 5 s | `SWEEP_RETRY_MILLIS` | one retry of a failed half |
| hidden tab, quiet | 5 min (`HIDDEN_QUIET_MS`) | client | **[changed] 1.2 D9** (was: close 1000): leave every room, keep the socket for personal frames; the once-a-minute ping (`QUIET_PING_EVERY_MS`) REPLACES the 25 s app ping while quiet, and NO frame since the last ping is a failure at the next tick (no pong timer: a hidden tab's timers are throttled) |
| hidden tab, close | 8 h (`HIDDEN_CLOSE_MS`), counted from the tab being hidden | client | close 1000 (`idle`); the title drops its number; reopen when shown |
| quiet bell re-read | at most once a minute | client invalidator | only `["notifications"]` is kept while quiet |
| indicator delay | 5 s down | client | badge |

Only the keys tests must shrink are config (`requireConfigInt`/`requireConfigLong`, each pinned in `NumericConfigBoundsTest`): `live.maxTickets` 1..100,000,
`live.maxSockets` 1..10,000, `live.maxSocketsPerUser` 1..64, `live.maxPendingAuth` 1..10,000, `live.maxPendingPerAddress` 1..10,000, `live.authTimeoutMillis` 100..30,000.

**The pending share per address.** `live.maxPendingPerAddress` keeps one host from holding the global `maxPendingAuth` (before the audit, 256 sockets from one
address left every other tab 503 for the length of the deadline, and the 600/min bucket let it refill them). The key is the client address the per-IP bucket
uses (`clientAddressKey()`, `infra/ClientAddress.kt`, shared with `plugins/RateLimits.kt`: the real client behind a proxy with `http.behindProxy`), an IPv4 address whole, an IPv6 address by its /64 (an
IPv4-mapped one as IPv4; text that is no address literal counts under the TCP peer); entries exist only while a socket holds a slot. An office behind one NAT shares its address's 8: a tab holds a
slot only for the round trip between the upgrade and its `auth` frame, so a reconnect storm backs off (503, then jittered backoff) rather than failing. **The share is only as good as the client address** (`security.md` "Trust the address or the share degrades"): `remoteHost` is the NUMERIC peer address (`plugins/Http.kt` copies it before anything can trigger a reverse-DNS lookup), or the trusted `X-Forwarded-For` hop with `http.behindProxy=true`. Without real client addresses (a plain proxy with `behindProxy=false`, an SNAT load balancer such as a Kubernetes Service with `externalTrafficPolicy: Cluster`) EVERYONE shares ONE `live.maxPendingPerAddress`: raise it (up to 10,000) or fix the trust. With a client-supplied `X-Forwarded-For` (`behindProxy=true` and no proxy stripping it) a client can still pick its own key by claiming real address literals, as with every per-IP bucket (junk text is counted under the peer).

**"Idle close"** is four mechanisms, not one timer: the protocol heartbeat, the 2 s auth deadline, the close at the token's `exp`, and the client closing a
socket after 8 hours hidden (**[changed] by 1.2 D9**: it was 5 minutes; see "The hidden tab" below). A visible, authenticated socket is never closed for being quiet. **One socket per tab**; the 17th tab of a user shows "Live
updates off" and retries every minute (a shared leader-tab socket is a deliberate non-goal; **[changed] 1.2**: a hidden tab now keeps a socket for personal frames, so the tab title can follow the bell).

**The hidden tab (1.2 D9, binding, implemented in 1.2.0 S10; changes 0.4 D3 and 0.4 A4).** After 5 minutes hidden the client goes QUIET instead of
closing: the wanted subscription set becomes empty (the ordinary unsubscribe diff, so the tab holds no room subscription), the socket stays for personal
frames only, a ping goes once a minute and "no frame since the last ping" is a failure at the next tick (the normal reconnect path). While quiet the
invalidator re-reads only the bell's count key, at most once a minute. After 8 hours hidden the client closes as before (`suspend()`) and the tab title
drops its number: it never shows a stale one. A hidden or `idle` tab makes no reconnect re-read of its own (`utils/queryClient.ts`: no refetch on `online`); while it is hidden but not yet idle, the pair is refreshed by the socket's 4001 re-mint and by the quiet tab's once-a-minute bell re-read (an API call that refreshes on a 401), and both stop at the 8 hours. Shown again, a quiet tab subscribes its wanted rooms back and the client hands the consumer a synthetic `resync` (the backfill: the rooms, the rail and the bell re-read behind the 0 to 2 s spread; the status is `live` before it, so the invalidator is no longer quiet); a closed one reopens (a fresh connect, whose `ready` is the backfill). A socket that drops while quiet reconnects through the ordinary backoff and comes up quiet (no rooms), a 4001 or 4003 re-mints as ever, and showing the tab while it is reconnecting brings the retry forward. A deliberate quiet is status `quiet`, never `down`: the "Reconnecting…" badge does not flash for it (a real drop while quiet still shows it after 5 s).
Tabs of one browser also tell each other when notices were seen (`BroadcastChannel`, no server change; `utils/seenSync.ts`), or a hidden tab would
keep saying `(3)` after the notices were read elsewhere: after a tab's OWN seen-write LANDED (the bell's "seen" and "seen all", a read marker whose cached unseen rows held a notice of the thread, "Mark all as read") it posts `{v: 1, userId}` on the channel `adder.seen.<userId>`; every other tab of that user re-reads `["notifications"]` through its invalidator (so a quiet one spaces it a minute apart). A message that names another user or any other shape is ignored, the channel closes with the signed-in identity, and where `BroadcastChannel` is missing or throws nothing happens (the other tabs catch up on focus, as before). It carries no data: the re-read is the truth. **The cost, accepted:** a forgotten hidden tab stays signed in up to 8 h + 1 h (the refresh
TTL) instead of 5 min + 1 h, since the 5-minute close was the only thing that let an open browser's session idle out, and it holds one of the person's
16 sockets (`live.maxSocketsPerUser`) meanwhile. **The 8 hours are wall time:** the client records when the tab was hidden by the clock and, at each quiet ping tick and before every (re)mint (a 4001 or 4003 re-mint, a backoff retry), closes instead once 8 hours have passed, so a laptop that slept (its timers stalled) cannot extend the session; the 8-hour timer stays as a backstop. **Slots:** a tab hidden within its last 8 hours still holds one of the person's 16 sockets AND one of the process's `live.maxSockets` (1000), so forgotten hidden tabs can crowd out a visible one: a tab that is refused at the cap (`429 live-socket-limit`, 4429, or the global `503`) shows "Live updates off" / "Reconnecting…" and retries every minute or with backoff, and the person frees a slot by closing a tab (a quiet one is not asked to give way). The server, the ticket rules, the caps and the 5 s indicator do not change; `LiveStatus` shows nothing for quiet. **Quiet follows the tab, not the socket:** the invalidator is quiet from the moment the tab has been hidden 5 minutes until it is shown (also while the socket is reconnecting), and a `BroadcastChannel` message is dropped by a tab that is `idle` (no socket; showing it reconnects and the `ready` re-read is the backfill).

**Audits** (names in `observability.md` when each lands): `live.refused` (`reason` = origin | capacity | stopping | listener-down | auth | socket-cap | subscription-cap | inbound-rate |
bad-frame; **[changed] no `ip`**: the `rate_limit.exceeded` rule, the client address is attacker-chosen text behind a proxy and the access log has it; `byUserId` after authentication, a printable `origin` for the Origin refusal; coalesced through `RateLimitAuditThrottle`, with a `suppressed` count; **the window is per reason, and per `limit` for a refusal that names one (a
per-address refusal does not hide the global one), and per origin HOST for `origin`** in a set of at most 64 hosts per process, past which they share one window, so a
probe from one site does not hide another's), `live.subscribe_denied` (`byUserId` and
the EXISTING rooms the caller cannot read; never ids that do not exist; **[changed] coalesced per user** like `live.refused`, one line a minute with `suppressed` and the union of at most 20 denied ids: the frame-rate limit alone still allows a line every 0.3 s per socket; **the tail a stopped probe leaves is flushed by the sweep tick** once its window is due, without counting a rejection), `live.revoked` (at most one line per user per
sweep and kind: `reason=verdict` closed sockets, `reason=membership` revoked subscriptions), `live.ticket_saturated`, and the generic `rate_limit.exceeded` for `live-connect` and `live-ticket`. Not audited: connects, normal closes, 4001,
`resync` (DEBUG; the gauges count sockets). **Telemetry:** the latency collector excludes `/api/v1/live`; each socket is one OTel server span for its whole
life (the Ktor instrumentation has no request filter; measured in the socket step); gauges `live.sockets`, `live.listener_up` (no row until the lazy listener has started) and `live.notify_queue_usage`
(`pg_notification_queue_usage()`, cluster section) ride the telemetry sampler through `infra/notify/LiveStats.kt`'s `LiveStatsKey` (`observability.md` "Telemetry").

## Web

One `useLiveUpdates` socket in the shell, a framework-free state machine (`utils/liveClient.ts`; injected socket factory, clock and ticket minter) and a key
map (`utils/liveKeys.ts`) from frames to TanStack Query keys. The wanted subscription set is the active rail (up to 100 rooms) plus the open room; the
archived rail and All rooms keep refreshing on focus. States `connecting | live | quiet | down | paused | idle | stopped`; backoff 1, 2, 4, 8, 16, then 30 s, each times a
random 0.5-1, reset after 60 s live; reconnect at once on `online` and when the tab becomes visible (`quiet` and `idle` are the hidden-tab states, "The hidden tab"); a reconnect invalidates the subscribed rooms.
Invalidation is debounced (focused keys 300 ms, aggregate keys at most once per 3 s, `resync` after a random 0-2 s) and NEVER cancels an in-flight fetch.
The header shows a "Reconnecting..." badge after 5 s down and "Live updates off" at once when paused. Successful reply writes can reuse their own refresh when the matching live hint arrives. See "Reply correlation". Vite's proxy needs `ws: true` (no `changeOrigin`, so the Origin check matches). The socket URL is derived from the same base as the REST
calls (`VITE_API_BASE`): empty means the page's own host (`ws:` on http, `wss:` on https), an absolute base maps `http`/`https` to `ws`/`wss` on THAT origin
(the server then needs the page's origin in `http.corsHosts`, as for REST). **Adder's own CSP (`connect-src 'self'`) blocks such a socket, as it blocks the REST calls**: an absolute base only works when the SPA is served from somewhere else, under that host's own CSP. A base without a leading slash resolves relative to the page, as `fetch` does. After a 4001 or 4003 the client re-mints after a random 0-1 s, so a whole room
of sockets that expired together does not mint in one instant.

**One mint per connect** (measured against the compose stack, 1.0.0 follow-up): a full page load of the production build is exactly one ticket and one
socket; client-side navigation, a rail change and a token rotation mint none (`useLiveUpdates.test.tsx` pins it). `vite dev` mints TWO per load,
because React StrictMode runs the mount effect twice: the first client is stopped at once and never opens its socket, and its ticket expires unused after
30 s. That is a dev-only cost, deliberately left alone. A page load is the unit that spends the 60/min `live-ticket` bucket, which is why the e2e suite
gives each spec file its own administrator and signs in onto the page it opens (`e2e/README.md` "Rate buckets").

## Deploy and proxies

Compose serves 8087 directly. The Kubernetes (OrbStack) `adder` namespace is fronted by an ingress-nginx Ingress (`k8s/ingress.yaml`: upgrade passed by the controller, `proxy-read-timeout`/`proxy-send-timeout` 120 s, TLS ended there); the 1001
close fits inside `terminationGracePeriodSeconds` 50 (a 5 s `preStop` sleep comes first, `observability.md`). A **reverse proxy** must pass the upgrade (`proxy_http_version 1.1`, `Upgrade $http_upgrade`,
`Connection $connection_upgrade`) and keep read and send timeouts at 60 s or more (25 s pings fit ingress-nginx's default). The Origin check needs the
request's scheme, host AND port to be the browser's, which is a statement about headers, not about `Host`: **TLS at the proxy needs
`X-Forwarded-Proto: https` plus `http.behindProxy=true`** (the application sees plain http otherwise, and an `https://` Origin is refused for its scheme);
**a public port other than 443 (or 80) must be in `X-Forwarded-Host`** (`app.example.com:8443`; without the port the default one is meant); **`Host $host`
alone, with TLS ended at the proxy, gets a 403** (right host, wrong scheme). Plain http end to end needs nothing but `Host`. With `http.behindProxy=true` the
per-IP bucket and the per-address pending share also see the real client. Production `HttpsRedirect`: the SPA derives `wss:` from `https:`, or from
`VITE_API_BASE` when that is absolute ("Web"). Without real client addresses at the application (see "The pending share per address") the 8 unauthenticated slots are everyone's together. The front-door notes are in `security.md` and `ci.md`.

## Account rechecks

A committed logout, successful account update, deletion or credential revision change emits a private account id on `adder_live`. The same transaction commits the state change and the hint. The account hint has only the account id and envelope version. It never becomes a WebSocket frame. Logout invalidates the access token it revoked, and a separate session of the same user stays live while its own credentials are current.

Each hub coalesces up to 1,024 pending account ids and looks up matching sockets through its user index. Overflow or a listener gap requests a full recheck. Database verdicts run outside the hub actor, and a failed recheck retries once after five seconds using a fresh snapshot. The 60-second sweep remains a fallback. Each newly registered socket is rechecked before its first frame is sent, closing the gap between ticket validation and registration.

## Reply correlation

The reply POST accepts one optional `X-Mutation-Id` header with a canonical lowercase UUIDv4. The same identifier may accompany a `message.posted` hint on the writer’s account sockets. Other recipients receive the ordinary hint. A repeated request can create another message. The identifier has no effect on authorization, retries or notification events.

The sender records the operation in its logical session before issuing the write. An early matching hint waits for the write and its query refresh. After a successful refresh, only the hint for the returned message is redundant. A failed request, failed refresh or expired correlation releases the held event through the normal invalidator. Another tab refreshes as usual. Room revocations, resync, message revisions and deletions keep their independent handling.

In a warm two-tab Chromium rehearsal, a reply produced two timeline, detail, thread-list and rail reads in the writer before correlation, and one of each afterward. The observer still read each once. Holding the POST response after the observer received the event caused no writer reads until the response arrived. Aborting that held response released the event and the committed reply appeared in both tabs.
