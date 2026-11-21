### Authorization model

Layered RBAC. Implemented in the `server/src/main/kotlin/authz/` package.

- **Global roles are an additive set**: every user is implicitly a regular user (USER is the
  baseline — never transmitted), and additional roles — currently only `ADMIN` (`UserRole` in
  `users/User.kt`; a future role is just a new enum value) — only ever ADD privileges. The API
  carries them as a `roles` array (`[]` = regular user); `isAdmin()` (`authz/Guards.kt`) =
  `ADMIN ∈ roles`. Storage is a single `users.role` column with a CHECK (V1) — the **wire shape is
  already the set** (`roles` claim + `LoginResponse.roles`), so moving to a Lettuce-style
  `user_roles` join table when a second additional role arrives never breaks issued tokens or
  clients. **ADMIN is the management role**: it owns the whole user-management surface (the
  `/users` CRUD below) and may reset any user's password (`requireSelfOrAdmin`'s admin branch);
  the management surfaces attach to it (users and flat-team CRUD).
- **Login** issues a **pair** of JWTs, both carrying `email`, `userId`, a `roles` array claim, a
  `credentialRevision` and a `typ` claim (`"access"` / `"refresh"`), minted by `auth/Tokens.kt`
  (`JwtConfig.issueAccessToken` / `issueRefreshToken`). The short-lived **access** token
  (`jwt.accessExpiresInSeconds`, default 900) is the API bearer; the longer-lived **refresh**
  token (`jwt.refreshExpiresInSeconds`, default 3600) is exchanged at `POST /api/v1/refresh` for a
  fresh pair. `LoginResponse` (also the `/refresh` response) exposes `token`/`expiresAt`,
  `refreshToken`/`refreshExpiresAt`, `userId`, and `roles`. The `jwt {}` verifier in
  `plugins/Security.kt` additionally requires `typ == "access"` and runs the per-request account check
  (`TokenBlocklistService.accessVerdict`: one uncached statement - non-revoked `jti`, user exists and not deleted,
  `credentialRevision` and `roles` equal the stored ones; `security.md` "JWT model"), so a refresh token cannot
  authenticate an API call and a stale access token is a `401` on its very next request.
- **Session auto-extension (pure sliding).** `/api/v1/refresh` (rate-limited, no `authenticate` —
  the access token may be expired) verifies the refresh token's signature/issuer/audience/`typ`/
  non-revocation, then does **one** `userService.read(userId)` to confirm the user is still active
  (soft-delete-filtered) and pick up their current roles/email, **requires the signed
  refresh-token `credentialRevision` to equal `users.credential_revision`**. Every password
  change/reset/bootstrap rotation increments that revision atomically with the hash update, so
  even changes in the same millisecond invalidate earlier credentials. Missing or malformed
  revision claims are rejected. The refresh token is **rotated**: after those checks pass, its `jti` is claimed
  with one atomic insert into the blocklist (`TokenBlocklistService.claimRefresh`, until the token's own expiry) and a fresh
  pair is minted - only the claim is atomic, not the delivery of the response. So it works once; a replay inside the
  60-second grace (`security.refresh.replayGraceSeconds`; covers a lost response and the loser of two racing tabs) is
  answered with another pair (at most twice per token, and a logout that reaches the route ends the grace of that user's rotations) and audited
  `refresh.replay_grace`, a replay after it is the uniform `401` with
  `refresh.rejected reason=replayed` (`revoked` stays for logout-revoked tokens; the grace/records are in memory,
  per instance, and a restart falls back to strict - `security.md` "Token storage"). There is no family-wide revocation; the superseded access token lives until its own expiry; an idle session (no refresh
  within the refresh TTL) ends, as does one hit by a password change/reset or account delete (`security.md` "Token storage"
  states the consequence). Every rejection is audited (`refresh.rejected` with `reason`). Frontend:
  `web/src/api/http.ts` `authedFetch` silently refreshes (single-flighted within the current
  session, and serialised across tabs by a Web Lock where the browser has one) and retries once on a `401`; a refresh rejected because another tab already
  rotated the pair adopts the pair in storage instead of signing out; on a definitive refresh rejection it clears only the
  session that initiated the request. Responses from an obsolete session cannot publish
  credentials, clear a newer session, or retry an operation as the newer account. Logout clears
  local credentials immediately and revokes its captured token pair best-effort; when its access token has expired
  (`/logout` needs one) it renews the pair detached from the cleared session and revokes the NEW pair.
- **The public surface** is exactly eight operations, each declaring `security: []` in the spec:
  `POST /login`, `/login/mfa`, `/refresh`, `/password-reset`, `/password-reset/confirm`, the live WebSocket upgrade `GET /api/v1/live` (it authenticates in its first frame, `security.md` "The live socket") and the two probes
  `GET /api/v1/health` (liveness — the process answers) and `GET /api/v1/ready` (readiness — a
  database round trip within a short budget, else `503`; `plugins/Health.kt`). Probes disclose
  nothing but `{status: ok}`; everything else sits inside `authenticate {}` (`AnonymousAccessTest`
  sweeps it from the spec — the "401 sweep": every operation without a declared `security: []` is
  probed token-less, so a new route is covered the moment its spec entry lands).
- **A reset token authorizes exactly one thing**: replacing that one account's password through
  `POST /password-reset/confirm`, never a bearer-authenticated call. The confirm checks the grant before validating the
  password (a uniform `401` for an unknown, expired, used or stale grant, so `401` wins over `400`), then rechecks expiry,
  the credential revision and the email under a user-row lock while it consumes the grant and writes the password in one
  transaction. Confirmation invalidates every session of the account and any pending MFA challenge; normal sign-in,
  including enabled MFA, still follows. The authenticated ADMIN password `PUT` is unchanged. Details: `security.md`
  "Self-service password reset by confirm link".
- **Guards** in `authz/Guards.kt` are plain `suspend`-friendly function calls used at the top of
  each route handler — there is no Ktor plugin or DSL. Each route reads `call.caller()` (which
  parses the JWT claims into a `CallerPrincipal{userId, email, roles}` — missing/unknown claims
  throw `UnauthorizedException`) and then invokes the relevant guard: `isAdmin(caller)`,
  `requireAdmin(caller)`, `requireSelfOrAdmin(caller, targetUserId)`, `requireTeamManagerOrAdmin(caller, teamId,
  isManager)` (ADMIN, or the team's ACTIVE manager via the injected `TeamService.isActiveManager` lookup, which
  answers false for a missing team so the 403 is uniform — its first consumer is the team roster below); new guards join
  this file so the whole policy stays in one place.
- **Resource rules**:
  - `/api/v1/users` (list/create) and `PUT`/`DELETE /api/v1/users/{id}` → **ADMIN only** — the
    whole management surface is admin-gated (the list included; regular users keep only the self
    password change, and the narrow member-picker directory below). `GET /users/{id}` → target user or ADMIN, **guard-before-read** (an
    unauthorized caller 403s uniformly whether or not the id exists). Two protections with a
    deliberate 403/409 split: **self-delete is a `ForbiddenException` (403)** — a
    permissions-shaped rule about WHO acts — while **deleting or demoting the LAST active
    administrator is a `ConflictException` (409)** — a state-shaped rule about what the system
    can afford to lose. Initial passwords are client-generated (the SPA) and stored only as
    bcrypt hashes; no response carries plaintext.
  - `GET /api/v1/users/directory?q=&page=&pageSize=&sort=` → **any authenticated user, no guard** (so no 403; the 401 is `authenticate`'s): the
    member-picker lookup a room owner needs, since an OWNER is any user and `GET /users` is ADMIN-only. It is a deliberate, narrow exception to the
    "management surface is admin-gated" rule above, and it is NOT the ADMIN list opened up: a separate route (the literal `directory` segment wins
    over `{id}`), a separate service method (`UserService.directory`) and a separate projection, `{id, name, email}` of ACTIVE users only
    (`UserDirectoryEntry`; roles, feature flags, language and the password hash never leave the service). `q` matches name OR email
    (`containsNormalized`); `q` is REQUIRED and 2 to 100 code points after trimming, a page holds at most 20 rows, `400` otherwise; sortable `name` (default) and `id`.
    Because it lets a regular user list accounts it rides the per-USER `directory` bucket (120/min, `security.md`; `429` beyond). Those bounds slow and audit
    a bulk harvest; they do not prevent an insider enumerating (D2 accepts name+email disclosure to any signed-in user). A read: no audit event (a 429 is the
    generic `rate_limit.exceeded`). The ADMIN `GET /users` is unchanged. Tests: `UserDirectoryTest`.
  - `GET /api/v1/users/directory/{id}` (1.2 D7, `users/UserDirectoryRoutes.kt`) → **any authenticated user, no guard** (so no 403; the 401 is `authenticate`'s, swept by `AnonymousAccessTest`): ONE ACTIVE user's directory entry
    `{id, name, email}`, the header of the profile page `/people/:id`. The same projection and service path as the list (`UserService.directoryEntry`), so it adds no field and no role, flag, language or MFA state; **an unknown id and a
    soft-deleted account are the SAME `404` with the same body** (no existence disclosure beyond what the list's ACTIVE rows already show); a malformed id is `400`. It sits inside the list's `directory` bucket (120/min, shared by both).
    `GET /users/{id}` stays self-or-ADMIN. A read: no audit event. Tests: `UserDirectoryTest`.
  - `GET /api/v1/search?q=&closed=&watching=&page=&pageSize=&sort=` (0.5.0; `watching`, 1.1, makes `q` optional and lists only the CALLER's watched threads of their own member rooms, never a deleted room's, `threads/ThreadSearchRoutes.kt`) → **any authenticated caller, no guard** (so no 403 and, with no room in the request, no 404; the 401 is `authenticate`'s, swept by `AnonymousAccessTest`): the scope is ACTIVE membership of ACTIVE rooms and nothing else, an ADMIN's too (the "Threads" section below: Search). Tests: `GlobalSearchTest`.
  - `POST /api/v1/live/tickets` (0.4.0 S3, `live/LiveRoutes.kt`) → **any authenticated caller, no guard** (so no 403; the 401 is `authenticate`'s, swept by `AnonymousAccessTest`): it mints a single-use, 30-second socket ticket bound to the caller and to the access token that made the call (`call.accessToken()`, `authz/AccessToken.kt`; `CallerPrincipal` is unchanged), and no body is read. Per-USER bucket `live-ticket` (60/min; `429` beyond it) and a bounded store (`429` at `live.maxTickets`, audited `live.ticket_saturated`); both rejections are `429`s that disclose nothing about any room. A ticket proves identity only: what a socket may see is judged per room at subscribe time with the room routes' own readable predicate (`live.md`; S4), and the token is re-judged by `accessVerdict` when the ticket is redeemed (S4). The saturation `429` carries `Retry-After: 30`; a mint while the listener has been down for more than 10 s is a `503 live-unavailable`. Tests: `LiveTicketTest`, `LiveTicketsTest`.
  - `GET /api/v1/live` (0.4.0 S4, `live/LiveRoutes.kt`, `live/LiveSocket.kt`) → **public at the HTTP layer, authenticated by its first frame** (a single-use ticket bound to the caller and their access token, re-judged by `accessVerdict` at redemption). Admission order: per-IP `live-connect` bucket (429; it runs first, so refusals spend a token), Origin (403, scheme included, audited `live.refused`), then the 503s (stopping, sockets, unauthenticated sockets per address and in total); a plain GET is `400` after admission, so **403 before 400**. Authorization of what a socket sees is the room routes' own readable rule, applied per room at subscribe time by `RoomService.readableRoomIds`: a member subscribes to their rooms, an ADMIN to every existing room, and anything else is `refused` UNIFORMLY (no such room and not readable are one answer, like the REST `404`; an existing room the caller cannot read is audited `live.subscribe_denied`). The per-user socket cap (16) answers `429 live-socket-limit` at the mint and 4429 at the redemption. **Revocation latencies (0.4.0 S5):** removal from a room and deletion of a room revoke the subscription at once (the pushed `revoked`; if the hint is lost in a listener gap, the gap's membership re-check or at the latest the 60 s sweep does it, audited `live.revoked reason=membership`, so the bound is **immediate normally, at most 60 s after a gap**; an ADMIN merely removed from a room keeps it, since ADMIN reads every room); a logout, user deletion, password change or role change ends the socket within 60 s (the account sweep, 4003, `live.revoked`), which is also when a DEMOTED ADMIN loses the room subscriptions it held only as ADMIN (a NEW subscribe reads the stored role of the active user, so a demoted or deleted ADMIN gains no new room even before that); the socket never outlives its access token (4001 at `exp`). Tests: `LiveRevocationTest`, `LiveAdmissionTest`, `LiveSocketTest`, `LiveFanOutTest`.
  - `/api/v1/teams` (V6, flat teams) → `GET` list and `GET /{id}` **any authenticated user**
    (the Teams page reads them; a room will add a whole team at once, `chat-model.md`); `POST` and `PUT`/`DELETE /{id}` **ADMIN only**, with `requireAdmin` BEFORE the body decodes and the
    id lookup (guard-before-read: a non-admin probe gets a uniform 403 whether or not the id exists, and a
    non-admin's malformed body stays 403). A
    case-insensitive name clash with an active team `409` (the V6 partial index). The optional
    `managerId` (create and the full-replace PUT — omitted = no manager) must be an ACTIVE user,
    else `400` ("Unknown or deleted manager ids"); `TeamResponse`/`TeamListItem` carry `managerId` +
    `managerName`, both null for a soft-deleted manager. The optional mention `handle` (V25, 1.1.0; create and the full-replace PUT, omitted = cleared) is written by the same ADMIN-only routes (an ordinary `400` after the guard: 2 to 30 characters, `^[a-z][a-z0-9]*(-[a-z0-9]+)*$`, uppercase refused and never folded, eight reserved words; a clash with an active team `409`), and read by every authenticated user on `TeamResponse`/`TeamListItem` and through `GET /teams?handle=` (a substring filter, `sort=handle`): a handle discloses nothing a team's name does not, and it grants no right. Being a team's manager grants NO write on the team
    record itself (still ADMIN only) — any team-scoped right a manager gets later is checked via `requireTeamManagerOrAdmin`. `GET /teams?memberId=` (1.2 D7) lists the teams whose roster holds that ACTIVE person (any reader, like the roster itself: `GET /teams/{id}` already shows `members[]`; an unknown or removed id is an empty page, a malformed one `400`). Mutations audit `team.created/updated/deleted` and `team.manager_changed`
    (only on an actual change). Tests: `TeamTest` (the handle's grammar, reserved words, clash, release on delete, clear on PUT, filter, sort), `GuardsTest`.
  - `/api/v1/workflow-templates` (V11) → `GET` list and `GET /{id}` **any authenticated user** (the room-create form picks a template); `POST`, `PUT`/`{id}` and
    `DELETE /{id}` **ADMIN only**, the teams idiom with `requireAdmin` as the FIRST statement: a non-admin gets a uniform `403` whether or not the id exists, with or
    without the `X-Expected-Revision` header and whatever the body. The PUT's order is guard `403` → `expectedRevision()` `400` (missing, repeated or malformed) →
    sanitise + validate the body `400` → under the row lock (`FOR NO KEY UPDATE`) `404` for a missing or deleted id → `requireRevision` `409`
    (`urn:adder:problem:revision-conflict`) → write with `revision + 1`; an ADMIN therefore sees `400` for a missing header even on an unknown id.
    A case-insensitive name clash with an active template is a plain `409` (the V11 partial index). Mutations audit `workflow_template.created/updated/deleted`.
    Tests: `WorkflowTemplateTest`.
  - `PUT` / `DELETE /api/v1/teams/{id}/members/{userId}` (the roster, V10) → **ADMIN or the team's ACTIVE manager** (decision D1): the first consumer of
    `requireTeamManagerOrAdmin`, run as the FIRST statement of the handler, so every other caller (another team's manager, a stranger) gets the uniform `403`
    whether or not the team exists; the ADMIN alone reaches the `404` for an unknown team. Because the grant can be revoked between that check and the
    write, `TeamService.addMember` / `removeMember` take the same guard as a lambda and re-run it INSIDE the write transaction after
    `lockActiveForWrite` locks the team row (a manager change, `TeamService.update`, takes the same lock, so the verdict under the lock is the
    authoritative one; pinned by a lock-wait test). The guard the route passes (`rosterGuard`) judges the caller as `TeamService.currentCaller` re-reads
    them (the stored `users.role`, none for a deleted account), not the token's claim, so an ADMIN demoted or deleted while the write waited for the lock is
    refused with the uniform `403` the way `withRoomWrite` re-reads the caller (C-SEC-9; `TeamMembersTest`). The PUT has no body (nothing to decode) and is idempotent: `204` for an added or an already-present
    member, and the audit event `team.member_added` fires only on an actual add; an unknown or soft-deleted user is `404`, a roster already holding 200 ACTIVE
    members `409` (an existing member beats the cap). The DELETE is `404` for a user who is not on the roster and audits `team.member_removed`. Reading the
    roster (`TeamResponse.members`, `TeamListItem.memberCount`) stays any authenticated user and shows ACTIVE users only. The manager grants nothing on the team
    RECORD (still ADMIN only). Tests: `TeamMembersTest`.
  - `PUT /api/v1/users/{id}/features` → ADMIN only; **self allowed** (an admin adjusting their own
    flags is a feature, and the users routes are never gated, so a fully self-disabled admin can
    always get back); unknown/soft-deleted target → 404; unknown feature name → 400 (enum
    decode); wholesale replace (idempotent — same-set re-PUT is 204, not 409); audited
    `user.features_changed` (featuresFrom/featuresTo) only on an actual change. **Per-user feature
    flags (V5, Lettuce's model)**: `Feature { MFA }` stored as the DISABLED set
    (`user_disabled_features`, no row = enabled). **`MFA` is inverted-default and login-scoped** —
    every user starts with the MFA row present (the V5 seed + the `UserService.create`
    chokepoint), so email MFA is opt-in; it gates the LOGIN FLOW only, and no route is gated on a
    feature yet (the first gated area adds a `requireFeatureEnabled(caller, feature)` guard to `Guards.kt`, run as the FIRST
    guard — before any read, so a disabled caller gets a uniform 403 even for a missing id). Mind the wholesale-replace PUT: a disabled set omitting MFA — including the
    empty "re-enable everything" array — ENABLES the second factor. The set rides both JWTs as
    the `disabledFeatures` claim (missing claim = empty — outstanding tokens survive the deploy;
    unknown value = 401) and `LoginResponse`/`UserResponse.disabledFeatures`, so a change takes
    effect at the target's next refresh (≤15 min) or login. Users list:
    `feature=<F>&featureEnabled=true|false` filter pair (must come together, 400 otherwise). SPA:
    `hasFeature()` (`api/session.ts`, localStorage `adder.auth.disabledFeatures`), the per-user
    editor `/users/:id/features` (the Users table's Features button) and the admin per-feature
    screen `/feature-flags` (state filter + bulk enable/disable over every row matching the
    current filters, behind a count-stating confirm — a client-side loop of the same per-user
    wholesale PUTs). Tests: `FeatureFlagsTest` + the GuardsTest/AuditTest cases.
  - `PUT /api/v1/users/{id}/language` → **target user or ADMIN** (`requireSelfOrAdmin`, the V1
    `language` column — Lettuce's model): the ONE synced per-user language, set at create
    (`UserCreateRequest.language`, default `"en"`), applied to the SPA at sign-in (rides
    `LoginResponse`, applied by `persistSession` on login/MFA/refresh) and used for every email
    sent to the user (read fresh at send time). The header language switcher is the self-service
    writer (fire-and-forget save on switch); the whole-user PUT deliberately never touches it.
    Unsupported code → 400 (after the guard — 403 wins); idempotent; audited
    `user.language_changed` on an actual change. Tests: `UserLanguageTest`.
  - `GET`/`PUT /api/v1/users/{id}/notification-preferences` → **target user or ADMIN** (`requireSelfOrAdmin` FIRST: 403 before anything is read, then the body 400, then the target 404 for an unknown or soft-deleted user), the in-app and email switches of the notices (V22/V28, `notifications/NotificationPreferenceRoutes.kt`; the PUT is a wholesale replace: `disabled` pairs are off, optional `enabled` pairs are on, a pair in neither takes its default, a pair twice or in both lists is a 400; audited as `user.notification_preferences_changed` on a change of the person's deviations only). The PUT rides its own per-user `notification-prefs` bucket (30 a minute, BEFORE the guards, so a refused write spends a token; `rate_limit.exceeded` with `bucket=notification-prefs`); the GET is unbucketed. Tests: `NotificationPreferencesTest`.
  - `PUT /api/v1/users/{id}/password` → target user or ADMIN (`requireSelfOrAdmin`). New password
    must be ≥ 10 chars (`MIN_PASSWORD_LENGTH` in `users/UserRoutes.kt`) and ≤ 71 UTF-8 bytes
    (`MAX_PASSWORD_BYTES`, `auth/Passwords.kt` — the bcrypt ceiling), else `400`. A **self-change**
    (even by an admin) additionally requires a correct `currentPassword` in the body (else `403`,
    audited `password.change_denied`) — checked BEFORE the length validation, so 403 wins over
    400 (the convention everywhere); an admin resetting **another** user's password does not. The route rides the
    per-user `password-change` bucket (5 a minute, before the guards) and a wrong `currentPassword` counts into the
    account's login lockout (`security.md` "Per-account login lockout"): a locked account's self change answers the
    lockout's `429`, after the guard and BEFORE any password check; the admin-resets-another path neither counts nor is
    refused by the target's lock. Tests: `PasswordChangeTest`. A
    successful change stamps `users.password_changed_at` and atomically increments
    `users.credential_revision`, which invalidates all outstanding refresh tokens and pending MFA
    challenges (see "Session auto-extension") and, through the per-request revision check, every outstanding
    ACCESS token too - the token a self-change was made with is a `401` on the next request (the SPA signs out
    explicitly). Covered by `PasswordChangeTest` and `StaleAccessTokenTest`.
  - **A delete, a demotion/promotion or a password change takes effect on the account's very next request.** An
    access token's `jwt {}` validation (`plugins/Security.kt`) runs ONE uncached statement over the user row
    (`TokenBlocklistService.accessVerdict`): a soft-deleted user is `401` (`access.rejected reason=user_gone`), a token
    whose `roles` differ from the stored role is `401` `roles_changed` (the SPA's `401` -> `/refresh` then succeeds
    with the NEW roles and re-renders; a delete or password change makes the refresh fail too, which signs the user
    out), a changed `credentialRevision` is `401` `credential_changed`, a logged-out `jti` is `401` `revoked`. The
    `ADMIN` guards therefore never run on a stale role, `caller()` still reads `roles`/`disabledFeatures` off the (now
    verified) claims, and `disabledFeatures` alone still lags to the next refresh (`FeatureFlagsTest`). Over HTTP the
    caller of `DELETE /users/{id}` is therefore always an active admin, so the last-admin `409` is reachable only
    as a race (the caller deleted between the access check and the locked admin count; pinned with a lock-wait test in
    `UserRoutesTest`) - the self-delete `403` is the HTTP answer.
  - **The team-manager grant is checked in the database on every request too, by its own predicate.**
    `requireTeamManagerOrAdmin` asks `TeamService.isActiveManager` (an ACTIVE team whose `manager_user_id` is the caller
    AND an ACTIVE user) per request, so assigning or clearing a manager takes effect on the very next request (a
    soft-deleted manager's token is already a `401` at the access check; the ACTIVE-user predicate stays as the second
    line); its ADMIN short-circuit rides the (verified) JWT roles. Never cache the verdict in a claim. A route that merely
    SHAPES its answer by the grant (e.g. whether a block of per-person figures is returned) asks `isActiveManager` the same way, after
    the ADMIN short-circuit; the `ETag` of such an answer carries the verdict ("Caching" in `security.md`). The roster writes above are its consumer; any later team-scoped
    write follows the same two-step (guard first, re-run under the row lock).
  - **Rooms (V13): the room-scoped matrix** (`/api/v1/rooms…`, `rooms/RoomRoutes.kt`, `RoomWorkflowRoutes.kt`, `RoomMemberRoutes.kt`; `chat-model.md` "Rooms"). A room is **members-only**. The guards are DB-free and
    take the caller's `RoomStanding(role?, archived)` (`rooms/RoomAccess.kt`), read by the route (and re-read under the room lock): `requireRoomReader` (null standing = no such room, or no
    role and not an ADMIN, is `NotFoundException("Room not found")`) and `requireRoomManager` (the reader check first, then OWNER or ADMIN, else `403`). **Guard order, every room route:**
    (1) `401` from `authenticate`; (2) visibility: `404` for a missing or deleted room and for a non-member alike (the route preamble `visibleStanding` also audits `room.access_denied` when
    the room EXISTS and the caller is neither a member nor an ADMIN; the response is still the uniform `404`); (3) role: `403`; (4) the `X-Expected-Revision` header, then the body: `400`;
    (5) under the room lock (`withRoomWrite`), the guard AGAIN on the re-read standing AND the re-read `users` row of the caller (a deleted account is the uniform `404`; an ADMIN demoted since the token was minted is judged as a plain user), then the state checks (`409`), then the write. A non-member's malformed body is therefore `404`, a plain
    member's `403`, and only someone who may write sees the `400`.

    | Operation | outsider | member | owner | admin (non-member) |
    |---|---|---|---|---|
    | `GET /rooms/{id}`, `GET /rooms/{id}/workflow`, `GET /rooms/{id}/members` | 404 | 200 | 200 | 200 |
    | `PUT /rooms/{id}`, `PUT /rooms/{id}/workflow` (+ `X-Expected-Revision`), `POST /rooms/{id}/archive`, `POST /rooms/{id}/unarchive` | 404 | 403 | 204 | 204 |
    | `POST /rooms/{id}/seen` (V19; the caller's own `seen_at`, `read-state` bucket, no room lock; any member, the OWNER too; an ADMIN outside the room has no read state) | 404 | 204 | 204 | 403 |
    | `POST /rooms/{id}/members` (200 `{added, alreadyMembers}`) | 404 | 403 | 200 | 200 |
    | `PUT /rooms/{id}/members/{userId}` (role), `DELETE /rooms/{id}/members/{userId}` of ANOTHER member | 404 | 403 | 204 | 204 |
    | `DELETE /rooms/{id}/members/{callerId}` (leave) | 404 | 204 | 204, or `409 last-owner` for the last one | 404 (not a member) |
    | `DELETE /rooms/{id}` (reader `404`, THEN `requireAdmin` `403`) | 404 | 403 | 403 | 204 |
    | an unknown or deleted id, any operation | 404 | 404 | 404 | 404 |

    `GET /rooms` and `POST /rooms` have no per-room guard: any authenticated user lists the rooms they may read (their own; an ADMIN's is every room; `member=true|false` narrows; `memberId=` (1.2 D7) keeps only the rooms that ACTIVE person is in, ANDed with the readable set: a member gets the rooms they SHARE with the person, an ADMIN every room of the person, an unknown or removed id an empty page, a malformed or repeated one `400`; it shows nothing a shared room's member list does not) and creates one
    (becoming its OWNER; the per-USER `room-name` bucket, 30/min, shared with the rename `PUT /rooms/{id}`, and a per-USER `room-members` bucket, 60/min, on the member add, `security.md`). The room's OWNER is NOT an ADMIN: delete stays `403` for them, even on an archived room. A name clash on create or
    rename is `409`, an archived room refuses `PUT` with the typed `409` `urn:adder:problem:room-archived`; archive and unarchive are idempotent `204`s (audited only on a change). **A caller
    demoted or removed while their write waited for the room lock** is refused by the re-run guard (`403`, or `404` once they are no longer a member): pinned with a lock-wait test in `RoomTest` (the last-OWNER race, with the OTHER owner demoted while a leave waits, in `RoomMembersTest`).
    A soft-deleted user's membership rows stay inert (never read as a role: a deleted user's token is already a `401` at the access check). **Members (0.2.0 C8):** the manager guard covers the add, the role change and the removal of someone else; a **leave** (deleting
    your own membership) needs only the reader standing, so a plain member may leave and an ADMIN who is not a member gets the `404` (nothing to leave); the leave re-runs `requireRoomReader`
    under the lock. A plain member who tries to remove ANOTHER member is `403`. Membership management stays allowed on an ARCHIVED room. The add always adds `MEMBER`; promotion is
    `PUT .../members/{userId}`. **The last-OWNER rule:** after the write at least one `OWNER` whose user is active must remain, else `409` typed `urn:adder:problem:last-owner`
    (leave, demotion and removal, an ADMIN's included), counted under the room lock (`RoomMemberService`); a target who is not an ACTIVE member is `404`. A room whose owners are all deleted
    is managed by ADMINs (promote someone, then the rule holds). The workflow PUT (C9) follows the same order: visibility, role, the header, the body, then the lock; from 0.3 on any member posts, replies and moves a thread between OPEN statuses, the thread owner, the room OWNER or an
    ADMIN passes ownership and reopens a closed thread, and a closed thread refuses further activity with `409`. Tests: `RoomTest`, `RoomMembersTest`, `RoomAuthzMatrixTest`, `GuardsTest`.
  - **Threads (V14, 0.3.0 S2): the thread matrix** (`/api/v1/rooms/{id}/threads…`, `threads/ThreadRoutes.kt`; `chat-model.md` "Who may do what", "Guard order and locking"). A thread route
    runs the room's order: `401`; the per-USER `chat-post` bucket (the POST only, `security.md`); visibility (`visibleStanding`: the uniform `404`, `room.access_denied` audited); the pre-check
    before the body (`403`); the body (`400`); then, under the room lock, the guard AGAIN on the fresh caller and standing, the archived `409`, the field-value `400`s (the kernel, against the workflow read under the lock), and the write. **`requireRoomMember(caller, standing)`**
    (`authz/Guards.kt`, DB-free) is the speech guard: `requireRoomReader` first, then an actual member, else `403` - an ADMIN reads every room but only members speak, so an ADMIN outside the
    room is `403` on a POST and joins through the Members page first; `rooms/RoomLock.kt` wraps it as `memberGuard` for `withRoomWrite`. A creator removed while the create waits for the lock is
    refused by the re-run guard with the `404` (`ThreadWriteBoundsTest`, race 2); two creates waiting at once take consecutive numbers (race 1). The S2 rows (the later steps add the writes'):

    | Operation | outsider | member | thread owner (plain member) | room owner | admin (non-member) |
    |---|---|---|---|---|---|
    | `GET /rooms/{id}/threads`, `GET .../threads/{number}`, `GET .../threads/{number}/events` | 404 | 200 | 200 | 200 | 200 |
    | `GET /rooms/{id}/tags` (the room's hashtags, 0.5.0; a reader, the per-user `chat-read` bucket) | 404 | 200 | 200 | 200 | 200 |
    | `GET /rooms/{id}/search` (full-text search, 0.5.0; a reader; the per-user `search` bucket first, so a 429 can precede the 404; the matrix also pins a member's `400` for a bad `q`) | 404 | 200 | 200 | 200 | 200 |
    | `POST /rooms/{id}/threads` (201 + `Location`) | 404 | 201 | 201 | 201 | 403 |
    | `PUT .../threads/{number}` (details, `X-Expected-Revision`) | 404 | 204 | 204 | 204 | 204 |
    | `PUT .../threads/{number}/status`, OPEN to OPEN (`monitoring`) | 404 | 204 | 204 | 204 | 204 |
    | `PUT .../threads/{number}/status`, close, reopen or CLOSED to CLOSED (a STEWARD) | 404 | 403 | 204 | 204 | 204 |
    | `PUT .../threads/{number}/owner` (a STEWARD) | 404 | 403 | 204 | 204 | 204 |
    | `GET .../threads/{number}/messages`, `GET /rooms/{id}/messages/{messageId}` | 404 | 200 | 200 | 200 | 200 |
    | `POST .../threads/{number}/messages` (201 + `Location`) | 404 | 201 | 201 | 201 | 403 |
    | `PUT /rooms/{id}/messages/{messageId}`, someone else's message (author only) | 404 | 403 | 403 | 403 | 403 |
    | `DELETE /rooms/{id}/messages/{messageId}`, someone else's message (the room OWNER or an ADMIN, D2) | 404 | 403 | 403 | 204 | 204 |
    | `PUT` or `DELETE` the caller's OWN message (while a member) | - | 204 | 204 | 204 | - |
    | `GET /rooms/{id}/pins` (0.6.0, a reader; unbucketed: at most 50 rows) | 404 | 200 | 200 | 200 | 200 |
    | `PUT .../threads/{number}/read-marker` (V19; the caller's OWN marker, `read-state` bucket, no room lock; an ADMIN outside the room has no read state: 403 before the body, the thread and the message) | 404 | 204 | 204 | 204 | 403 |
    | the same for an unknown thread, an unknown message, or a message of another thread or room (`404`) | 404 | 404 | 404 | 404 | 403 |
    | `PUT .../threads/{number}/watch` (V26; the caller's OWN watch, any MEMBER, a closed thread and an archived room included; its own `thread-watch` bucket, no room lock; an ADMIN outside the room cannot watch: 403 before the body and the thread) | 404 | 204 | 204 | 204 | 403 |
    | the same for an unknown thread (`404`) | 404 | 404 | 404 | 404 | 403 |
    | `PUT .../threads/{number}/pin`, `PUT /rooms/{id}/messages/{messageId}/pin` (any MEMBER pins and unpins, a closed thread included; `chat-write` bucket) | 404 | 204 | 204 | 204 | 403 |
    | `PUT /rooms/{id}/messages/{messageId}/reaction` (the caller's own reaction, any message of the room; V20) | 404 | 204 | 204 | 204 | 403 |
    | the same in a closed thread (`409 thread-closed`), or on an unknown, deleted or other room's message (`404`) | 404 | 409 / 404 | 409 / 404 | 409 / 404 | 403 |
    | an unknown number, an unknown message id or a message of another room, any read or write | 404 | 404 | 404 | 404 | 404 (a POST, a pin PUT or a reaction PUT: 403 for the ADMIN) |

    **The steward rule (D1, S4):** closing, reopening, a CLOSED to CLOSED move and passing ownership belong to a STEWARD - the thread owner while a member, the room OWNER or an ADMIN, i.e. whoever can undo a close. `isThreadSteward` /
    **`requireThreadSteward(caller, standing, threadOwnerId)`** (`authz/Guards.kt`, DB-free: `requireRoomReader` first, then the rule, else `403`; a thread owner who has left the room is no steward and sees the `404`). The owner PUT
    runs it as the pre-check on a lock-free `ownerOf` read (a missing thread is `404`, a non-steward `403`, both before the body) and again under the room lock on the fresh caller, standing and row (a thread handed over while
    the call waited is refused with the `403`). The status PUT is the one BODY-dependent guard (whether a steward is needed follows the target's category), so it runs under the lock after the target resolves: an unknown or
    retired key is a `400` first (a reader can read the workflow, so it discloses nothing), then the steward `403` (even for the status the thread already has: a no-op must not bypass the steward rule), then the archived `409`
    (the no-op included), then the write. Race 4 (`ThreadStatusTest`): a thread closed while a member's OPEN to OPEN move waits for the lock is a `403`. A closed thread refuses the owner hand-over with `409 thread-closed` but not a
    status move.
    **Messages (S5):** a message is addressed through its room (`/rooms/{id}/messages/{messageId}`, API-RES-006), and the lookup joins the thread with `threads.room_id` = the path's room, so a message of another room, an unknown one and a DELETED one are the same `404`. **`requireMessageAuthor(caller, standing, authorId)`** (the edit; `requireRoomReader` first, then the author while a member, else `403`: the room OWNER and an ADMIN do NOT edit someone else's words) and
    **`requireMessageRemover`** (the delete: the author while a member, the room OWNER or an ADMIN) are DB-free; the routes run them as the pre-check on a lock-free `authorOf` read (a missing message is `404`, before the body) and the write runs them again on the fresh caller, standing and row under the room lock. Under the lock the order is: guard, message `404`, act guard `403`, archived `409`, closed `409` (a moderator's delete included), the no-op, the write. Race 5 (`MessageTest`): a thread closed while a post waits for the lock is `409 thread-closed` with nothing written.
    **Pins (0.6.0, V21):** the pin PUTs take the same guards as a post, so the order is `401`, the `chat-write` bucket (`429`), visibility (`404`), `requireRoomMember` (`403`, an ADMIN outside the room included, before the body), the body (`400`), then under the room lock the member guard again, the thread or message (`404`; a message is the same lookup as the edit's), the archived `409`, the state it already has (`204`), the 51st pin (`409 pin-limit`). There is no act guard: any member pins and unpins any thread or message (an ADMIN who is not a member reads the list and pins nothing). `PinsTest`, `ThreadAuthzMatrixTest`.
    **Reactions (V20):** a reaction is speech, so the PUT is the member's, like the POST: `requireRoomMember` on the visibility result BEFORE the body (an ADMIN outside the room is `403`, which is also what hides whether the message exists from them: the message is looked up only under the lock, after the guard), then the body (`400`, a fixed detail), then under the room lock the guard again on the fresh caller, the message `404` (unknown, deleted, another room's), the archived `409`, the closed `409`, the no-op, the limits `409 reaction-limit`, the write. There is no act guard: anyone who may speak reacts to anyone's message, and each person writes only their OWN reaction (the caller's id is the row's `user_id`, never read from the body). The `403` is audited as `authz.denied`, a non-member's `404` as `room.access_denied` (`MessageReactionsTest`, the matrix rows of `ThreadAuthzMatrixTest`).
    A malformed create body is `404` for an outsider, `403` for an ADMIN outside the room and `400` only for a member. A thread is addressed by `(room id, number)`, so another room's number is
    never reachable: the lookup is scoped to the room the visibility check admitted. An archived room refuses the POST with the typed `409` `urn:adder:problem:room-archived`. Tests:
    `ThreadAuthzMatrixTest` (a fresh room per cell), `ThreadTest`, `ThreadWriteBoundsTest`, `MessageTest`, `GuardsTest`.
    **Search:** the order is `401`, the `search` bucket (`429`), visibility (`404`, audited `room.access_denied`), the parameters (`400`), then the query: a non-member's bad `q` is still `404`, and the search reads only the room the check admitted (`threads.room_id` is its first predicate). An ADMIN outside the room searches it (an ADMIN reads every room); with `watching=true` they get an empty page (a non-member watches nothing), and a non-member's `watching=true` with no `q` is still the `404`. `ThreadSearchTest`.
    **`GET /search` (search across rooms, 0.5.0):** any authenticated caller, NO guard and no room in the request, so there is no `404` and nothing to disclose or probe; the order is `401`, the SAME per-user `search` bucket (`429`), the parameters (`400`, before any room is read), then the query. **The scope is ACTIVE membership of an ACTIVE room (`RoomService.memberRooms`), an ADMIN's too (0.5 D2)**: the role is never read, so an ADMIN who is not a member finds nothing of that room here (they still search it through `GET /rooms/{id}/search`, which admits them), and a hit, a count or a room name of a room the caller is not in appears nowhere. The rooms are read first and the statements then restrict `threads.room_id` to them, so no statement can touch another room; a removed member loses the room on the next request, a deleted room is never searched, an archived one is (`roomArchived`). No room filter exists (a non-member's room id could only ever be an oracle). `GlobalSearchTest`.
    **List filters:** `status`, `ownerId`, `mentionedId`, `watching`, `mentionsMe` (both bound to the CALLER: they take no user id; the group part of `mentionsMe` applies only to a room MEMBER) and `field` narrow what `GET /rooms/{id}/threads` returns inside the room the visibility check admitted; they never widen it (the predicate always starts at `room_id = ?`). The order is `401`, the visibility `404`, then the filter `400`s, so a non-member's malformed filter is still `404`. A RETIRED field key is a `400`, never a match: its stored values are hidden from readers (`chat-model.md` "Stored values survive"), and a filter would otherwise let a reader probe them. The `tag` filter is the same kind (`EXISTS` over `message_tags` correlated on the thread and bound to the room's id), and `GET /rooms/{id}/tags` reads `message_tags` by its own `room_id = ?`, so a tag is never read across rooms (`MessageTagsTest` seeds the same tag in two rooms); a non-member's tag list, malformed `tag` included, is `404`. `watching` is bound to the CALLER (`EXISTS` over `thread_watches` on the caller's own id and the room's id; it takes no user id, so nobody's watches can be probed), and a non-member ADMIN, who has no membership row and so no watch, matches nothing under `true`. Tests: `ThreadListFilterTest`, `MessageTagsTest`.
    **Notifications (0.6.0, V18):** `GET /notifications` is any authenticated caller with NO guard and no room in the request, so there is no `404` to probe and nothing to disclose: the predicate starts at `recipient_id = <caller>` and keeps the rows of the caller's ACTIVE rooms (`RoomService.memberRooms`: active membership of an active room, **an ADMIN's too, a role widens nothing**; a deleted room's notices are hidden). The two "seen" POSTs are scoped the same way (`WHERE id = ? AND recipient_id = <caller>`): **somebody else's notice is the same `404` as one that does not exist, never a `403`** (the hidden-resource idiom: a `403` would confirm the id), after the per-user `read-state` bucket (`429`, spent first, so an id sweep costs tokens); seen again is `204`. They are unaudited view state (`observability.md`), so a probe leaves only the bucket's own `rate_limit.exceeded` line. A notice is made only for an ACTIVE member of the room (the foreign key to `room_members` refuses any other), so an ADMIN who is not a member is never a recipient. `NotificationsTest`.
- **Existence disclosure (403 vs 404) — deliberate policy (API-ERR-006)**: three sanctioned idioms,
  and every new resource picks one consciously rather than mixing them. **Guard-before-read**
  (`GET /users/{id}`): a forbidden caller gets a uniform 403 whether or not the id exists — use it
  where existence itself is sensitive. **Read-before-guard**: missing → 404, existing-but-
  forbidden → 403 — acceptable where ids are sequential and existence is no secret. **Hidden resource →
  uniform 404** (rooms): a caller who may not even READ the resource gets the same 404 as for one that does
  not exist, and the 403 is reserved for a caller who can see it but may not do the thing (visibility first,
  then role); the probe is audited (`room.access_denied`) instead of answered. The password
  PUT effectively guards first (`requireSelfOrAdmin` needs no read), then maps a zero-row update
  to 404 — safe because the only callers past the guard are the target and admins.
- **Audit on denial**: every 403 emits `authz.denied` (method/path/byUserId/detail) from the
  `ForbiddenException` handler in `plugins/ErrorHandling.kt` — denials are part of the security
  trail, and route code gets it for free by **throwing**, never hand-rolling a 403 response. See
  "Audit trail" in `.claude/docs/observability.md`.
  - `GET /api/v1/telemetry/current` and `GET /api/v1/telemetry/history?group=&range=` (`telemetry/TelemetryRoutes.kt`) → **ADMIN only, read-only**
    (operational data: process sizes, container limits, request figures). `requireAdmin(call.caller())` is the first
    statement, before the query is read, so a non-admin gets the uniform `403` for a bad or missing `group`/`range` too (`403` outranks `400`);
    only then is an unknown, missing, blank or repeated `group` / `range` a `400`. No mutation, so no audit event of its own (a denial is the
    generic `authz.denied`); both ride the per-user `telemetry` rate bucket (30/min, mounted inside `authenticate`; `security.md` "Telemetry").
    See `.claude/docs/observability.md` "Telemetry" for the shapes. Tests: `TelemetryRoutesTest`.
- **Exceptions**: `UnauthorizedException` (→ 401), `ForbiddenException` (→ 403),
  `NotFoundException` (→ 404), `ConflictException` (→ 409), and `TooManyRequestsException` (→ 429) live
  in `authz/Exceptions.kt` and are mapped in `plugins/ErrorHandling.kt`. **All error bodies are
  RFC 7807 `application/problem+json` (API-ERR-001/002)**
  (`ProblemDetail{type,title,status,detail,instance,keys?}`; `instance` defaults to the request path
  without query parameters; a stale `X-Expected-Revision` is the typed 409
  `urn:adder:problem:revision-conflict`, and `keys` lists the workflow keys a conflict is about, or the field of a team's unique clash: `["name"]` / `["handle"]`) — emit them via the `ApplicationCall.respondProblem(status, detail)`
  helper (it uses an explicit `TextContent` so ContentNegotiation does not relabel the media type
  as `application/json`). `StatusPages` routes `BadRequestException`→400 (**with fixed vocabulary
  for converter failures**: a `ContentConvertException` anywhere in the cause chain
  (ContentNegotiation's malformed-JSON wrap, whose raw message carries the target FQCN) answers
  "Request body is invalid or does not match the expected schema", and the Resources decode wrap
  "Can't transform call to resource" answers "Path or query parameter is malformed"; our
  validators' own messages, cause-less, pass through), `CannotTransformContentToTypeException`→400
  when Content-Type is absent, otherwise →415 for an unsupported request media type (the abstract
  ContentTransformationException parent is deliberately not caught), the typed exceptions as above
  (**the route 404 convention**: read preambles and zero-row mutation results
  `throw NotFoundException("<Resource> not found")` — never a hand-rolled
  `respondProblem(NotFound, …)` + return), unique-violation `23505`→409 and NUL-byte `22021`→400
  (via the R2DBC/Exposed cause-chain walkers), and a catch-all `Throwable`→500 (logged) through
  that helper. A `status(TooManyRequests)` handler additionally gives the `RateLimit`
  plugin's bodiless 429 (the per-IP buckets and EVERY per-USER bucket, `RateLimits.kt` lists them) a generic problem body and emits the `rate_limit.exceeded` audit event
  (bucket/method/path, coalesced to one per bucket per minute with a `suppressed` count — per user, with `byUserId`, for every per-user bucket —
  `observability.md`) — **that handler rewrites any non-StatusPages
  429**, which is why caller-specific 429s (the login lockout) must go through
  `TooManyRequestsException` (handled calls are marked and skipped) — and a sibling
  `status(MethodNotAllowed)` handler gives routing's bodiless wrong-method 405 a problem body too
  (no `Allow` header — Ktor doesn't surface the allowed-method set; test with the default client,
  the conformance plugin would rightly flag the out-of-spec operation). The SPA fallback
  (`plugins/Routing.kt`, only when `web.staticDir` is set) never answers under `/api` (or `/api`
  itself): an interceptor on its route node throws `NotFoundException`, so an unknown API path is
  the standard 404 problem body, never index.html (a wrong method on a declared `/api` path is then a 404 too, not
  405 — the 405 handler answers only when no `staticDir` is served) (`ServerTest` covers it WITH a `staticDir`,
  through the default client — the conformance plugin would flag the undeclared path). A pre-routing intercept in
  `configureErrorHandling` additionally 400s any `/api/` path with a negative-integer segment
  (kotlinx decodes `UInt` via `toInt().toUInt()`, so `/users/-1` would WRAP to 4294967295 and flow
  into the normal lookup instead of failing as the spec's `minimum: 0` promises). The JWT
  `challenge` in `plugins/Security.kt` also calls `respondProblem` (it runs outside
  `StatusPages`). Test HTTP clients must register the `application/problem+json` content type
  (`json(contentType = ContentType.parse("application/problem+json"))`) to decode error bodies
  with `body<ProblemDetail>()` — the shared test-client defaults do.
- **Tests**: `GuardsTest` covers the guard matrix, `AnonymousAccessTest` sweeps the 401 surface
  FROM THE SPEC (plus the forged-secret and jti-less token cases), `UserRoutesTest` the management
  CRUD + protections (with `TestUsers.withSoloAdmins` isolating the last-admin 409s in the shared
  container), `PasswordChangeTest` the self-vs-admin rules, `AuditTest` the denial and mutation
  events. The shared `TestUsers.seed` helper defaults to `role = UserRole.ADMIN` so privileged
  fixtures are terse; pass `role = UserRole.USER` when you need a non-privileged caller.

### Not yet ported from Lettuce

- **The HR read-only auditor role** (audited `hr.read`/`hr.list` cross-user reads) — with it,
  Lettuce's rule that every HR-privileged read is audit-logged.
- **The management-chain rule** (`teams/ManagementChain.kt` — transitive manager rights) —
  **deliberately NOT ported**: Adder's teams are flat (ONE optional direct manager, no
  chain, no succession history). Port only if a hierarchy of teams ever becomes a requirement.