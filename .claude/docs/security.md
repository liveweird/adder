### Security posture: development vs production mode

`ktor.development` is env-overridable (`$KTOR_DEVELOPMENT:true`): local `:server:run`/tests
default to development mode; **the Docker image ships `KTOR_DEVELOPMENT=false`** (production
mode), and `docker-compose.yaml` explicitly sets it back to `true` because it is the local
plain-HTTP demo. Production mode activates HSTS + HTTPS redirect and **fail-closed startup
checks**:

- **JWT secret** (`plugins/Security.kt`): production requires a private 64-character hex key
  generated with `openssl rand -hex 32`. Blank, malformed, repeated-character and publicly
  committed values (including the compose demo key and Kubernetes template placeholder) refuse
  startup. Development warns and retains its demo behavior. The format check cannot establish
  randomness; generate and store the key privately.
- **Seed passwords** (`infra/db/Bootstrap.kt`, module `configureBootstrap`, runs after
  `configureDatabase`): in production mode, if any active account still carries the well-known
  `changeme` bcrypt hash the app **refuses to start**. The seed admin is also checked against
  known plaintexts so a freshly salted hash of an unsafe initial password from an older build
  cannot pass that hash-equality check. Setting `ADMIN_INITIAL_PASSWORD` (config
  `bootstrap.adminInitialPassword`) rotates the V3 seed admin's password at startup — idempotent:
  only applied while the admin still has the seed hash, so an admin-chosen password is never
  overwritten. Before rotating in production, bootstrap rejects the known `changeme`/`CHANGE-ME`
  values and applies the normal account password length and bcrypt byte limits. Covered by
  `BootstrapTest`; seed-mutating tests restore state via `TestSeedState.restoreSeedAccounts()`
  (`TestEnvironment.kt`).
- **Mail transport** (`infra/mail/Mail.kt`): `mail.transport=log` writes outbound email (including
  password-reset links and MFA codes) into the application log — permitted with a warning in development,
  **refuse to start** in production; `smtp` with a blank host refuses to start in **any** mode.
  A non-blank `mail.appUrl` that is not the deployment's `https` origin (`http` only in development; no
  path, query, fragment or userinfo) refuses to start in **any** mode (blank is allowed: the reset answers 503
  and boot warns). See "Outbound email" below.
- **Data-encryption key** (`infra/crypto/Crypto.kt`, module `configureCrypto`, right after
  `configureMail` and BEFORE `configureDatabase`): blank or the burned repo-committed dev default
  → warn in development, **refuse to start** in production; a malformed key (not 64 hex chars —
  the k8s template placeholder included) refuses in ANY mode. See "Encryption at rest" below.
  Covered by `CryptoBootTest`; every production-mode boot test overrides
  `security.encryption.key` with `strongEncryptionKey()`.

**JWT model** (`auth/Tokens.kt` + `plugins/Security.kt`): login issues an access/refresh **pair**,
both carrying `email`, `userId`, a `roles` array, a `typ` claim (`"access"`/`"refresh"`), a
`credentialRevision` and a random `jti`. The `jwt {}` verifier requires audience (`adder-api`), issuer
(`http://0.0.0.0:8087/` by default), `typ == "access"`, and a PRESENT `jti` (a jti-less token could never be
revoked, so both the verifier and `/refresh` reject it outright). **Every authenticated request then runs ONE
uncached database statement** (`TokenBlocklistService.accessVerdict`, ported from Lettuce's `isSessionLive` and
extended; `revoked_tokens` is written by `/logout` and by the refresh rotation, the revoke path prunes expired
rows): it reads the token's user row (`role`, `credential_revision`, `marked_as_deleted`) and whether the `jti` is
blocklisted, and the token is accepted only when the account exists and is not deleted, the `jti` is not
blocklisted, the token's `credentialRevision` equals the stored one and its `roles` equal the stored role. Anything else is a `401`
audited `access.rejected` with a `reason` (`user_gone`, `revoked`, `credential_changed`, `roles_changed`, and
`malformed` for a signed access token lacking `roles` or `exp` (the verifier alone would accept an `exp`-less token that never expires; `accessPrincipal` is the one place that refuses it, for every bearer route, so the live-ticket mint needs no rule of its own), or whose `userId` (1..4294967295) or `credentialRevision` (non-negative) is not an integral JSON
number - the same `strictLongClaim` rule as `/refresh`, so `1.0`, `1e0` and a numeric string are rejected on both paths -
a pre-deploy token, which the SPA silently refreshes), all with ONE response text ("Access token is no longer
valid - refresh or sign in again"; the audit event is coalesced to one per user and reason per minute with a `suppressed` count, like
`rate_limit.exceeded`, so a client replaying a stale token writes a line a minute, not a line per request). Structural rejections (junk, expired, wrong audience/`typ`, no `jti`) stay
unaudited and never reach the database. Nothing is cached: a logout, an admin delete, a demotion/promotion or a
password change/reset ends the token on its very next request, on any replica (the old 30-second verdict cache,
`REVOCATION_CACHE_TTL_MS`, is gone); the price is one primary-key read per request (about 1 ms). A role change does NOT bump the
credential revision, so the SPA's `401` -> `/refresh` succeeds with the new roles. Not compared: the MFA flag and
other feature flags (they ride the token until the next refresh), lockout, name/email/language. Pinned by
`StaleAccessTokenTest`. **A lookup that THROWS inside the JWT `validate` block
(database unreachable, pool acquire timeout) is an outage, not a bad token:** ported from
Lettuce, `validate` runs the lookup through `catchingFailures` (`infra/Failures.kt`)
and, on failure, stashes the cause on the call (`BlocklistFailureKey` in `plugins/Security.kt`);
Ktor's JWT provider turns any throw out of `validate` into a plain challenge, so the challenge
answers the catch-all's logged `500` (`respondInternalError`, `plugins/ErrorHandling.kt`) instead
of `401` — the SPA reads a `401` as session expiry and would sign the user out over a transient
outage. A malformed/expired/wrong-audience/wrong-`typ` token never reaches the lookup and stays
the uniform `401`. Pinned by `BlocklistOutageTest`; the OpenAPI spec already declares `500` on
every operation and the coverage gate ignores it (`CROSS_CUTTING_STATUSES` in
`OpenApiConformance.kt`), so no spec change is needed. Full token/refresh/logout semantics: see
"Authorization model" in `.claude/docs/authorization.md`.

**Token storage & response headers (the XSS posture).** The SPA keeps BOTH tokens in
`localStorage` under `adder.auth.*` (`web/src/api/session.ts` — access, refresh, roles, userId,
disabledFeatures and a random session identity; no password is ever persisted; everything else in
localStorage is view state). A deliberate trade-off, documented rather than hidden: any script
running on the origin can read the REFRESH token, i.e. a renewable session unbound to device/IP — so the
real control is keeping foreign script out. **The refresh token's lifecycle (rotation with a short replay grace):**
`POST /api/v1/refresh` verifies the presented refresh token (signature, issuer, audience, a PRESENT `exp`, `typ`, a
present `jti`, not blocklisted, the user still active, `credentialRevision` current) and then CLAIMS it: an atomic
`INSERT .. ON CONFLICT DO NOTHING` of its `jti` into `revoked_tokens` (until the token's own expiry;
`TokenBlocklistService.revokeIfAbsent`, pinned directly by `TokenBlocklistRotationTest`), after which a fresh
access/refresh pair is minted. Only the claim is atomic; delivery of the response is not, so of any number of
concurrent calls with one token exactly one inserts the row (`CLAIMED`) and the rest are decided by the grace below.
**Replay grace** (`security.refresh.replayGraceSeconds`, `$REFRESH_REPLAY_GRACE_SECONDS`, default 60, 0 = strict
one-time use): a `/refresh` response can be lost after the claim committed (the SPA's 30 s deadline, a dropped VPN, a
reload mid-flight), leaving the client holding the already-retired token. So a replay of a token THIS instance rotated
less than the grace ago is answered with another fresh pair (audit `refresh.replay_grace`, `userId`) instead of a `401`
that would sign the user out; the same grace answers the loser of two tabs refreshing at once (the rotation is
recorded inside the claim's transaction, before its commit, so a racing loser, which only resumes after that commit,
always finds it). One token gets at most 2 such re-mints (`MAX_GRACE_REUSES`; a lost response or a racing tab needs one);
after that, or after the window, a replay is rejected. **A logout that REACHES the route body ends the grace**: it
drops every rotation record of the access principal's user and of the presented refresh token's own user
(`TokenBlocklistService.forgetRotations`), so a copy of a recently rotated token cannot mint after sign-out and its replay
is `reason=revoked` (it also ends the grace of that user's other devices, whose next lost-response retry then signs out:
strict, never lax). `/logout` sits behind `authenticate {}`, so it needs a valid ACCESS token: the SPA's `logout()`
(`web/src/api/auth.ts`) therefore, on a `401` (the stored access token expired after idle or a lost refresh response),
exchanges the captured refresh token for a new pair without touching the already-cleared local session
(`refreshPairDetached`) and calls `/logout` again with the NEW pair, so the live refresh token is the one revoked; if that
refresh fails the sign-out stays local only and the refresh token lives until its own expiry (<= 1 h). A direct API caller
that logs out with an expired access token gets the plain `401` and revokes nothing. Known limitation: a refresh and a
logout racing within the same few milliseconds can leave the freshly minted pair unrevoked, bounded by the refresh TTL. The rotation records (`auth/RefreshRotations.kt`, `RefreshRotationsTest`) are in memory
and per instance like the lockout/MFA state (single replica) and capacity-bounded at 10,000: when full, records whose
token has expired or whose grace has passed are evicted first and, if it is still full, the NEW record is dropped. A
restart, or a dropped record, loses them and the behaviour falls back to strict. Outside the grace, a replay of a rotated token the instance still remembers is a `401`
audited as `refresh.rejected reason=replayed`; a token blocklisted by logout (or whose rotation is not remembered) is
`reason=revoked`, so the trail separates theft/replay from logout. The accepted cost of the grace: a stolen token replayed
within the grace of its legitimate rotation also gets a pair, at most twice (the window is short, the replay is audited and a logout ends it). There is
deliberately NO family-wide revocation and no "sign out everywhere": a replay outside the grace does not end the live
chain, and a stolen token that refreshes FIRST makes the victim's next refresh the rejected replay (the victim is signed
out and the trail shows `replayed`). The access token a rotation supersedes is not revoked: it stays valid until its
own expiry (<= 15 min; only a logout blocklists an access token, and the per-request check above ends it on any
delete, role change or password change). `POST /api/v1/logout` revokes the access token it is
called with and the one refresh token the client sends in the body (best-effort; both jtis go to the blocklist); it does
NOT end other pairs of the same user. What does end every outstanding refresh token of a user is a password
change/reset/bootstrap rotation (the `credential_revision` bump) or an admin deleting the account (`/refresh` re-reads
the user), and the same events end the ACCESS token at its next request (the per-request check above); an idle session
also ends at the refresh TTL. The cost is one `revoked_tokens` row per refresh (about one per
15 minutes per active session, pruned at the next revoke once expired). The SPA copes with two tabs sharing one session
(`web/src/api/http.ts`, `web-internals.md`). The keep-script-out control is `plugins/SecurityHeaders.kt`, installed unconditionally and pinned by
`ServerTest`: a strict CSP (`script-src 'self'`, `style-src 'self' 'unsafe-inline'`,
`object-src 'none'`, `base-uri 'self'`, `frame-ancestors 'none'` — the whole string is pinned
verbatim in `ServerTest`), `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`,
`Referrer-Policy: no-referrer`, `Permissions-Policy: geolocation=(), camera=(), microphone=(), payment=()`
(also pinned verbatim), and — production mode only, `plugins/Http.kt` — `Strict-Transport-Security`
(`max-age=31536000; includeSubDomains`, pinned by `ServerTest`'s production-mode boot behind
`http.behindProxy` with `X-Forwarded-Proto: https`; absent in development). Every `/api/` JSON and problem+json answer additionally carries
`Cache-Control: no-store` (`plugins/Http.kt`'s `CachingHeaders`, pinned by `ServerTest` together
with the unchanged caching of the SPA's static assets), so per-user data never lands in a browser
or proxy cache -- including the `200`/`304` of a validator-answering view GET, which adds an `ETag` to the same `no-store` ("Caching" below). `text/html` (index.html and every SPA deep link answered with it) carries `Cache-Control: no-cache`
(revalidate every time, so a deploy's new hashed asset names are never masked by a stale shell), while the content-hashed
CSS/JS keep `max-age=86400`. The `Server` header is the fixed generic `adder` (`install(DefaultHeaders) { header(Server, …) }`
in `Http.kt`) — Ktor's default `Server: Ktor/<version>` disclosed the framework and its exact version; pinned in `ServerTest`
(no `Ktor` in it).

**Caching -- a settled decision (the owner chose `no-store`).** Every `/api/` answer is `Cache-Control: no-store`. A route that answers through cache
validators (a view GET; Flow's report GETs were the first consumer, no Adder route is one yet) OFFERS a weak `ETag`
(`ApplicationCall.offerValidator`, `plugins/ResponseValidators.kt`) and the plugin writes `ETag` + `no-store` only when the call really ends as a `200`/`304`: so
nothing lands in a browser or proxy cache, and the server-side `304` helps only a client that sends `If-None-Match` itself (it saves the computation, not the
transfer). The rejected alternative was `private, no-cache` + `Vary: Authorization` + a logout `Clear-Site-Data: "cache"`, which would leave response bodies
on disk between logins on the same profile (best-effort clearing only). Cross-user safety of a `304` rests on two rules: authentication runs BEFORE any
validator logic (an unauthenticated request is `401` with no validator, so a `304` is never a way to probe for data), and a validator never covers content that
depends on the caller unless the caller's verdict is folded into the `ETag`. A `problem+json` response is always `no-store` and carries no validator. No
route offers a validator in v0.1.0; `ResponseValidators` is exercised by the contract tests until the first view endpoint lands.


`style-src` carries `'unsafe-inline'` deliberately: Mantine's style props/CSS variables render
inline `style` attributes and runtime `<style>` tags, and the app uses `style={{}}` itself — a
STYLES-only concession; it does not reopen script injection.

The Swagger UI is the ONLY CSP exemption (it bootstraps with inline script/style), and it applies
only when `http.exposeOpenApi` is on (`Application.exposesOpenApi()`, the same definition that
mounts the UI: the property when set, else development mode) AND the path is exactly `/openapi` or
starts with `/openapi/`. With it off — production by default — `/openapi`, `/openapi/x` and
look-alikes such as `/openapiX` are ordinary paths (the SPA catch-all answers them with
`index.html`) and carry the full CSP. The exempt paths still get the non-CSP hardening headers.
All of it is pinned in `ServerTest`.

Consequences: never weaken `script-src` (no CDN scripts, no `unsafe-inline` for scripts — Vite
bundles everything same-origin, fonts included), and the SPA must stay free of
`dangerouslySetInnerHTML`/`eval` sinks (currently zero). Moving tokens to httpOnly cookies would
trade this for CSRF machinery — revisit only with that full picture.

**Login timing equalizer** (`auth/AuthRoutes.kt` + `TIMING_EQUALIZER_HASH` in
`auth/Passwords.kt`): an unknown email pays a full, discarded bcrypt verify against a fixed
cost-12 hash so its 401 takes as long as a wrong password's — without it, response timing is an
account-enumeration oracle (the reset path equalizes via async processing; login equalizes
in-line).

**Per-account login lockout** (`auth/LoginThrottle.kt`, wired in `configureAuthRoutes`): after
`security.lockout.threshold` (default 5, `$LOGIN_LOCKOUT_THRESHOLD`) consecutive failures for one
submitted email, `/login` answers `429` for `security.lockout.durationSeconds` (default 900,
`$LOGIN_LOCKOUT_DURATION_SECONDS`) — even with the correct password, and regardless of whether the
account exists (no enumeration signal). A success resets the counter. The in-memory, per-instance (so the app runs ONE replica — `ci.yml` `k8s-static` pins it)
store has a hard `security.lockout.maxTracked` limit (default 10,000,
`$LOGIN_LOCKOUT_MAX_TRACKED`): expired state is reclaimed through an expiry index, but fresh
counters and active locks are never evicted to admit a new identity. At capacity, an untracked
identity receives an audited `429` before password verification, so saturation cannot bypass the
account throttle; tracked identities can still finish their normal flow. A fixed-size digest of
the canonical submitted identity bounds retained key size even for malformed login input;
oversized identities are also digested in audit fields instead of logged in full. The deployment
is single-replica; a restart resets the throttle. This complements the per-IP `RateLimit` bucket,
which rotating hosts sidestep. The 429 is **thrown** (`TooManyRequestsException`), never
`respondProblem`ed directly — StatusPages' generic 429 status handler rewrites any non-StatusPages
429, so only the exception path keeps the specific detail. Tests: `LoginThrottleTest` (unit,
injected clock) + `LoginLockoutTest` (route). The SPA shows a neutral temporary sign-in limit for
either 429 cause.


**Self-service password reset by confirm link** (`auth/PasswordResetRoutes.kt`, `auth/PasswordResetService.kt`, V8; SPA:
"Forgot password?" on `/login` → `/reset-password`, the emailed link opens `/reset-password/confirm`; Toadie's design, 2D3).
The public `POST /api/v1/password-reset` takes `{email}` and answers a uniform `202` for any well-formed request - the
lookup, grant issuance and email delivery run **asynchronously** after the response (uniform latency, no existence oracle),
and the request **changes nothing**: no password, no session. An unknown or soft-deleted account (`findWithIdByEmail`
filters active rows) sends nothing. Each link carries a 256-bit `SecureRandom` token (43-char base64url); only its SHA-256
digest is persisted in `password_reset_tokens` with the user id, the **account snapshot** (`credential_revision` AND
`email`) and the expiry. Lookup is SQL equality on the digest, so no constant-time compare is needed (OWASP Forgot-Password
cheat sheet). The lifetime is `security.passwordReset.tokenTtlSeconds` (`$PASSWORD_RESET_TOKEN_TTL_SECONDS`, default 900,
boot-validated 1..3600). **Several links may coexist** - a newer request never revokes a usable one, or anyone typing an
address into the form could kill the owner's link; a failed delivery revokes only the grant it just minted
(`password_reset.send_failed`); expired rows are pruned on every issue. **An account holds at most 5 unexpired grants** (a constant in
`PasswordResetService`, counted under the user-row lock so concurrent requests cannot slip past it): at the cap `issue` mints
nothing, the async task sends no email and audits `password_reset.capped` (userId only), and the request route still answers `202`
(no oracle). Earlier links stay valid. This bounds the remaining nuisance of the unauthenticated form - anyone who knows an
address can still make the owner receive reset mail, but at most 5 live links per lifetime (15 min by default) per account,
on top of the one-request-per-minute throttle. The async task that finds the account moved between its lookup and the grant insert
audits `password_reset.stale_account` (userId) and sends nothing. A grant dies on **use** (confirming deletes every grant
of that user), a **password change** (the captured `credential_revision` no longer matches), an **email change** (the captured
`email` no longer matches - a Flow deviation from Toadie, whose `auth_version` moves on email edits; the credential revision moves
only on password changes and must stay that way), an account **soft delete** and **expiry**; a role change does not
invalidate it. The email renders through `LocalizedText` in the RECIPIENT'S stored language (V1 `users.language`, EN fallback);
no email carries a password.

The link is `<mail.appUrl>/reset-password/confirm#token=…`. The token sits in the URL **fragment**, which never reaches the
server, its access logs or `Referer`; the SPA strips it from history and keeps it in component memory, and loading the page
consumes nothing (there is deliberately no token pre-check endpoint - an invalid token is learned at submit). The origin is
`mail.appUrl` (`$MAIL_APP_URL`), **configured, never derived from the request `Host`**, and validated at boot by
`configureMail` in any mode: a non-blank value must be an `https` origin (`http` only in development), with a host and no
path beyond `/`, userinfo, query or fragment, at most 2048 characters - otherwise startup is refused (a wrong origin would mail
reset tokens to another host). A **blank** value is allowed: the request route then answers `503` ("set MAIL_APP_URL") and boot
logs ONE `warn` when the transport is not `disabled`. `503` also when the deployment has no outbound email
(`MAIL_TRANSPORT=disabled`).

Public `POST /api/v1/password-reset/confirm` takes `{token, password}`. The grant is checked FIRST (`isUsable`, before any
bcrypt: a junk token never costs a hash; for the PASSWORD rules the 401 wins over a 400 - a body that fails to decode is a
400 first), then `validatePassword` (a refused password is a `400`
that does **not** consume the grant), then `PasswordResetService.complete` does consumption, the password write
(`updatePasswordInTransaction`, conditional on the captured revision) and the deletion of every sibling grant in **one
transaction**, under a `FOR NO KEY UPDATE` lock of the user row (persistence.md "Row locks vs. FK locks"), so competing confirmations (across instances too) have exactly one
winner. An unknown, malformed, expired, used or stale grant is one uniform `401` (`password_reset.rejected`). Success is `204`
with **no auto-login**: the `credential_revision` bump signs every other session out through the per-request access check and
kills pending refresh tokens and MFA challenges, while MFA stays as it was, so the next login still asks for the code. A
best-effort localized notice ("your password was reset, sessions signed out"; no token, no password) is emailed after the
response; its failure (`password_reset.notification_failed`) cannot undo the reset. A confirmed reset does **not** clear the
per-account lockout (in-memory, at most 15 minutes, per instance). A lost 204 can make a retry answer `401`: sign in with the
chosen password or request another link.

Throttling: per submitted email - one request per `security.passwordReset.minIntervalSeconds` (default 60,
`$PASSWORD_RESET_MIN_INTERVAL_SECONDS`) - uniformly for existing and unknown addresses (`auth/PasswordResetThrottle.kt`,
in-memory/per-instance like the lockout), with the hard `security.passwordReset.maxTracked` limit (default 10,000,
`$PASSWORD_RESET_MAX_TRACKED`: elapsed cooldowns are reclaimed, a fresh one never evicted; saturation rejects a new identity with
an audited `429` before any task starts); the per-IP `password-reset` bucket (`security.rateLimit.passwordResetPerMinute`,
`$PASSWORD_RESET_RATE_LIMIT_PER_MINUTE` - blank follows the mode: 5/min production, 100/min development); and the confirm
step's own per-IP `password-reset-confirm` bucket, 10/min in every mode (not configurable - the MFA idiom). `Cache-Control:
no-store` already rides every `/api/` JSON and problem+json answer (`plugins/Http.kt`, StatusPages' 429s included); the bodiless
`204` has nothing to cache. The development `log` mail transport prints the link: it must stay inaccessible outside a local
demo (production refuses it). Tests: `PasswordResetServiceTest` (injected-clock expiry, digest storage, the single-use race,
revision/email/delete staleness, the 5-grant cap, the revision-conditional write that lets only one of two competing writers win, and
a completed reset followed by an admin password write: both succeed, the admin's password is the final one), `PasswordResetConfirmationTest` (the confirm route, its own
bucket, the byte ceiling, MFA, notification failure, the `mail.appUrl` boot contract), `PasswordResetTest` (the request route and
the full link roundtrip incl. the session revocation), `PasswordResetThrottleTest`, `LocalizedEmailTest`.

**Per-IP login bucket** (`security.rateLimit.loginPerMinute`, `$LOGIN_RATE_LIMIT_PER_MINUTE`;
every bucket is installed by `plugins/RateLimits.kt`, which also owns the bucket names — today
`login`, `refresh`, `password-reset`, `password-reset-confirm`, `mfa`, `ready`, `live-connect` (below), and the per-USER `telemetry`, `directory`, `room-name`, `room-members`, `chat-post`, `chat-write`, `live-ticket` and `search` below): blank **follows the mode** — 10/min in production,
1000/min in development — and an explicit number pins it in either mode (the `http.exposeOpenApi`
idiom). Development is lifted because the e2e suite drives its logins from one host and would
otherwise sleep out the bucket; **the per-account lockout above is the actual brute-force defence
and is identical in both modes**. The sibling `refresh` bucket defaults to 30/min in both modes and
is pinnable via `security.rateLimit.refreshPerMinute` (`$REFRESH_RATE_LIMIT_PER_MINUTE`). The anonymous
`GET /api/v1/ready` (one pooled DB round trip per call) rides the `ready` bucket, 60/min in both modes, pinnable via
`security.rateLimit.readyPerMinute` (`$READY_RATE_LIMIT_PER_MINUTE`): the kubelet probes it every 5 s (12/min) and the
e2e global setup every 2 s (30/min), so probers stay far under it; `/api/v1/health` does no I/O and has no bucket.

`RateLimitResponseTest` and `LoginTest` pin the value to `10` explicitly, since tests run in
development mode.

**Per-USER telemetry bucket** (`telemetry`, a fixed 30/min in every mode, `RateLimits.TELEMETRY`) -- keyed by the verified user id, not the client IP, so a NAT'd office is not one bucket -- for
`GET /api/v1/telemetry/current` and `/history` (ADMIN only): `configureTelemetryRoutes` mounts `rateLimit(RateLimitName(RateLimits.TELEMETRY))`
once INSIDE `authenticate {}` around both reads, so the key is the verified user id (`TelemetryRoutesTest`). The panel polls `current` once a
minute and fetches a history per expanded section, a handful a minute; each call is a few `ops` aggregations, so the
bound is for a runaway client. The rejection is the generic `429` problem and the coalesced `rate_limit.exceeded` audit event (bucket `telemetry`, `byUserId`).

**Per-USER directory bucket** (`directory`, a fixed 120/min in every mode, `RateLimits.DIRECTORY`) for `GET /api/v1/users/directory`, the read built to let
any signed-in user (not just an ADMIN) look accounts up (it is no longer the ONE read that shows other users' names and emails: see "What the user directory discloses"). `configureUserRoutes` mounts `rateLimit(RateLimitName(RateLimits.DIRECTORY))` INSIDE
`authenticate {}` (the telemetry idiom), keyed by the verified user id: a NAT'd office is not one bucket and changing IP buys nothing. A member picker
makes a debounced lookup per keystroke pause (a handful a minute). The route adds two bounds of its own: `q` is REQUIRED and at least 2 code points after
trimming (max 100; code points, `codePointCount`, never UTF-16 units), and a page holds at most 20 rows (`400` beyond). **Honest scope: these slow and audit a
bulk harvest, they do not prevent one.** A signed-in insider can still walk the directory with two-letter queries inside the bucket (up to 120 pages of 20 rows, i.e. 2,400
rows, a minute), and every rejection is the generic `429` problem plus the coalesced `rate_limit.exceeded` audit event (bucket `directory`, `byUserId`, one per user per
minute). `UserDirectoryTest` pins the bounds, 120 then `429`, per user, and the audit event.

**What the user directory discloses, and to whom** (a deliberate widening of the ADMIN-only user list, the user's decision D2, 2026-11-17, who accepts name+email
disclosure to any signed-in user): to every signed-in user, the `id`, `name` and `email` of every ACTIVE account, searchable by name or email
(case- and accent-insensitive). Never roles (who is an ADMIN), feature flags, language, MFA state, timestamps or the password hash: `UserDirectoryEntry` is a
three-field projection built in the service, not the `User` row filtered afterwards, and `UserDirectoryTest` pins the exact key set. A soft-deleted account is
absent (its email is freed for reuse). `%` and `_` in `q` are literal (`containsNormalized` escapes them). **Residual risk:** the email is the login identifier, so
a signed-in colleague who harvests emails can (a) aim failed sign-ins at a victim's account to trigger the per-account lockout, bounded by the per-IP `login` bucket
(10/min in production) and the lockout's own window (**one IP can nonetheless keep about 30 accounts locked at once, ADMINs included**: 10 failures a minute locks two accounts at the threshold of 5, each for 15 minutes, using ids and emails harvested from the directory; an administrator locked out this way is only freed by the window or a restart), and (b) request password-reset mail for a victim, bounded by the per-IP `password-reset` bucket and the
per-email reset throttle (`PasswordResetThrottleTest`). Neither reveals or changes a credential, and the reset link goes only to the account's own mailbox. Collapse
the projection to `{id, name}` if that trade changes (the user's option 2 of D2).

**A second surface: the team roster.** `GET /api/v1/teams/{id}` returns `members[]` (`{userId, name, email}` of up to 200 ACTIVE members) to any signed-in user, with NO
per-user bucket (a plain read like the rest of `/teams`). So the directory is not the only read of other users' name+email. The honest bound: the harvest is limited by the
number of teams (a team's roster is one request, the team list pages at most 100 per request), it reveals nothing beyond what D2 already accepts (name+email of an active
user), and it covers only users who sit on some roster. Not bucketed on purpose; if the directory's projection shrinks to `{id, name}` (D2 option 2), the roster's `email` must shrink with it.

**A third surface: room members.** A room OWNER (any user who creates a room) can enrol ANY active user id through `POST /api/v1/rooms/{id}/members` and then read that user's `name` and `email` in
`GET /rooms/{id}/members` (a reader-only list of up to 500 members); and the add's `400` ("Unknown or deleted user ids: ...") tells the caller which of up to 200 submitted ids are inactive, an
active/inactive oracle over the id space. So the directory and the team roster are not the only reads of other users' name+email, and a user who owns a room can walk ids without typing a query.
The bound: the `room-members` bucket (60 adds/min per user, so at most 12,000 ids a minute, each add enrolling the person visibly in a room they did not choose, which is itself noticed, and
audited per person as `room.member_added`), the 200-ids-per-add and 500-members-per-room caps, and the 16 KiB body limit. It reveals nothing beyond what D2 accepts (name+email of an active user); the
inactive-id oracle reveals only that an id is not an active account. Collapse the member list's `email` with the directory's if D2 option 2 is ever taken.

**Per-USER room buckets** (`RateLimits.ROOM_NAME` and `RateLimits.ROOM_MEMBERS`, fixed in every mode; the telemetry idiom: `configureRoomRoutes` and `configureRoomMemberRoutes` mount
`rateLimit(RateLimitName(...))` INSIDE `authenticate {}` around the routes, keyed by the verified user id). **`room-name`, 30/min,** is ONE bucket shared by `POST /api/v1/rooms` and
`PUT /api/v1/rooms/{id}`: the two writes that put a name into the room-name namespace (a room name is unique among non-deleted rooms, case-insensitively, and reserves it for everyone
else). The rename shares it because its `409` on a clash is exactly the create's, so an unbucketed rename would be a free "does a room with this name exist" probe; a clash on either is
also audited as `room.name_conflict` (byUserId, roomId for a rename, null for a create). **`room-members`, 60/min,** on `POST /api/v1/rooms/{id}/members`: the add's `400` names the inactive
ids it refused, so a manager could sweep ids through it; the bucket slows that to a trickle (S4 below). The rejection is the generic `429` problem plus the coalesced `rate_limit.exceeded`
audit event (bucket name, `byUserId`); a request refused by validation (`400`) or a guard still takes a token, because the bucket sits before the handler. `RoomTest` pins 30 then `429`
across create and rename, `RoomMembersTest` 60 then `429`. Every other room route is unbucketed on purpose: guard-gated (a caller sees only the rooms they belong to) and bounded by the caps in `chat-model.md`.

**`chat-post`, 120/min (`RateLimits.CHAT_POST`, 0.3.0 S2),** per signed-in user, fixed in every mode: ONE bucket shared by the two writes that put speech into a room, `POST /api/v1/rooms/{id}/threads`
and `POST .../threads/{number}/messages` (0.3.0 S5); `configureThreadRoutes` and `configureMessageRoutes` mount it INSIDE `authenticate {}` around the POST, the room-bucket idiom. A person types a few messages a minute, so
120 only stops a runaway client or a script flooding a room. The other thread and message writes ride `chat-write` (below). The bucket sits BEFORE the guards, so
a refused or invalid POST (a `400`, a `403`, an outsider's `404`) takes a token too; the rejection is the generic `429` problem plus the coalesced `rate_limit.exceeded` audit event (bucket
`chat-post`, `byUserId`). `ThreadWriteBoundsTest` pins 120 invalid thread POSTs, then the `429` and its audit line, with another account and the reads untouched; `MessageTest` pins that thread creation and message posts spend the SAME tokens. A thread title and a message body are stored raw and
rendered without HTML by the SPA (Markdown without raw HTML, images as links, headings as bold paragraphs, links `noopener noreferrer`; the CSP is unchanged); their bounds (title 200, body 8,000 UTF-16 units, no control or hidden direction characters) are `chat-model.md` "API", enforced by the route and again by the service (`sanitizeMultiLine`).
**`chat-write`, 240/min (`RateLimits.CHAT_WRITE`, the 0.3.0 security audit's M1),** per signed-in user, fixed in every mode: ONE bucket shared by the five thread and message writes that are not a POST - the thread details PUT, the status PUT, the owner PUT, the message edit (PUT) and the message DELETE - mounted INSIDE `authenticate {}` around each, before every guard (a refused write spends a token too). Each of them takes the room's write lock (`rooms/RoomLock.kt`) and a pooled connection for as long as it waits, so before this bucket one member's script could queue unlimited writers behind a room and hold the pool's 20 connections (the second half of the fix is the bounded lock wait, below). 240 is twice `chat-post`: a person moves a status, hands a thread over, edits or deletes a message a few times a minute at the very most, and 4 a second is a script, not a hand; the rejection is the generic `429` problem plus the coalesced `rate_limit.exceeded` audit event (bucket `chat-write`, `byUserId`). `ChatWriteLimitsTest` pins 240 across the five routes, then the `429`, its audit line, another account and the reads untouched.

**`live-ticket`, 60/min (`RateLimits.LIVE_TICKET`, 0.4.0 S3),** per signed-in user, fixed in every mode: `POST /api/v1/live/tickets`, the exchange of a bearer for a single-use socket ticket (`live.md` "Tickets"). `configureLive` mounts it INSIDE `authenticate {}` (the telemetry idiom), so the key is the verified user id; a tab mints one ticket per (re)connect, so 60 a minute stops a reconnect loop or a script long before it can fill the store. The rejection is the generic `429` problem plus the coalesced `rate_limit.exceeded` audit event (bucket `live-ticket`, `byUserId`). `LiveTicketTest` pins 60 then `429`, its audit line and another account untouched.

**Socket tickets** (`live/LiveTickets.kt`, ported from `auth/MfaChallenges.kt`; redeemed by the socket, `live/LiveSocket.kt`, 0.4.0 S4). A ticket is 32 `SecureRandom` bytes (43 base64url characters) bound to the caller and to the access token that asked (`authz/AccessToken.kt`: `jti`, `exp`, `credentialRevision`; `plugins/Security.kt` stashes it on the call only after the per-request account check answered LIVE). It is valid 30 s and works ONCE (redeeming is an atomic remove; unknown, used and expired are one answer). Only its SHA-256 is stored, so a heap dump holds no usable ticket, and the lookup is a map equality on the digest (the password-reset idiom). **It travels in the socket's FIRST FRAME, never in a URL** (API-AUTH-002: a query credential lands in every proxy access log), is never logged (`LiveTicketTest` renders every event of every logger; `IssuedTicket`, `LiveTicketResponse` and the binding mask it in `toString`, `SecretToStringTest`) and the `Authorization` header never reaches the socket. The store is capacity-bounded (`live.maxTickets`, `$LIVE_MAX_TICKETS`, default 10,000, boot-validated 1..100,000 (about 50 MB at the ceiling)): expired entries are swept on every mint, then a full store answers `429` with `Retry-After: 30` and audits `live.ticket_saturated` (coalesced per user, one line a minute with `suppressed`), never evicting a held ticket. A ticket also never outlives its token: its deadline is the earlier of the 30 s TTL and the token's `exp`. In memory and per instance, like the MFA challenges: the single replica; a restart only invalidates the tickets in flight (the socket mints again). A ticket authorizes no room: subscriptions are judged per room when they are made (below).

**The live socket** (`GET /api/v1/live`, 0.4.0 S4; `live.md`). The route is public at the HTTP layer (`security: []`) and authenticates IN-BAND: the first frame is `{"type":"auth","ticket":...}` within `live.authTimeoutMillis` (2 s, else close 4401). What stands between an anonymous client and a held connection, in the order a request meets it:
(1) the per-IP **`live-connect`** bucket, 600/min (`security.rateLimit.liveConnectPerMinute`, a number pins it; `429` + `Retry-After`, the coalesced `rate_limit.exceeded`), which really is FIRST: admission intercepts in the `Call` phase, after the route's `rateLimit`, so a refused request spends a token too (the 0.4.0 audit found it intercepting before the bucket, so refusals were never throttled; `LiveAdmissionTest`); (2) the **Origin rule** (the CSWSH defence): the header is REQUIRED, its scheme `http` or `https`, and its scheme and authority must equal the request's own scheme, host and port (what Ktor resolves, so `http.behindProxy` rewrites all three: `X-Forwarded-Proto`, `X-Forwarded-Host` with its port) or be listed in `http.corsHosts`; anything else, a missing header and `null` included, is a `403` and an audited `live.refused` `reason=origin` with a `detail` (`authority` | `scheme` | `host`), coalesced per origin host (`LiveAdmissionTest` pins the matcher table). The `Host` header is client text: an unreadable one (a non-numeric port, a bare IPv6 literal) is the same refusal, never a 500;
(3) a stopping server, `live.maxSockets` authenticated sockets, `live.maxPendingPerAddress` (8) unauthenticated ones from the caller's address (an IPv6 address counted by its /64, `live/LivePending.kt`) or `live.maxPendingAuth` (256) in total is a `503` `urn:adder:problem:live-unavailable` with `Retry-After` (the caps are advisory at admission, authoritative in the hub); (4) a plain non-upgrade GET passes the same gates and answers `400` (403 before 400). An unauthenticated socket lives at most 2 s and is counted, in total and per client address (`LiveHub.pending`, claimed atomically when the session starts; one host cannot hold the global cap); the first frame is parsed STRICTLY (8 KiB max, Ktor's 1009 beyond), and a frame before `auth`, a second `auth`, malformed JSON or an unknown type is 1008, a binary frame 1003 (each an audited, coalesced `live.refused` `reason=bad-frame`). **Per-socket bounds after authentication (S5):** more than 30 inbound frames in 10 s (a sliding window over the messages the reading loop receives, an application `ping` included; WebSocket protocol ping, pong and continuation frames never reach it, and Ktor reassembles a fragmented text message first, so the 8 KiB limit applies to the total. Checked BEFORE the parse so a flood costs a counter, not a database transaction) is 1008 and an audited `live.refused` `reason=inbound-rate` (`LiveInboundRateTest`, `LiveBoundsTest`); a repeated `subscribe` for an unreadable room is one coalesced `live.subscribe_denied` line a minute; the socket closes 4001 at its access token's `exp` and 4003 within 60 s of a logout, deletion, password change or role change (the sweep re-judges every open socket with the SAME decision as the per-request check, `TokenBlocklistService.accessVerdicts`; `AccessVerdictsTest`); the same pass re-checks non-ADMIN memberships (a hint lost in a listener gap cannot leave a removed member subscribed for more than 60 s; audited `live.revoked reason=membership`); the two halves run independently and a failed half is retried once after 5 s, so a failing query never leaves the other undone for a minute; a listener that has been down for more than 10 s is a `503` on the mint and a 1013 close of every open socket (a deaf channel is not left silent); and a new subscribe reads the stored role of the active user (a demoted or deleted ADMIN gains no new room).
Redemption re-judges the token (`accessVerdict`: a logout, delete, password change or role change between the mint and the redeem closes 4003), refuses past the token's `exp` (4001) and enforces `live.maxSocketsPerUser` (4429). A subscribe is judged per room by `RoomService.readableRoomIds` (members' rooms; an ADMIN every existing room): the answer deliberately mixes "no such room" with "not readable" (`refused`), and only EXISTING unreadable rooms are audited (`live.subscribe_denied`). No credential is ever in the URL; `Authorization` never reaches the socket; the ticket reaches no log line (`LiveSocketTest`, `LiveFramesTest`).
**With `http.corsHosts` set** the CORS plugin, installed application-wide, refuses a foreign `Origin` BEFORE the route (a bodiless `403`, no `live.refused` line: the same for every route; still a refusal, only the audit trail and the problem body are missing); the plugin has no per-route exemption, so the live route is not carved out. `RequestBodyLimit` is told the upgrade GET has no body (`plugins/Http.kt` `requestBodyLimit`: `Long.MAX_VALUE`, the plugin's "no limit"), because wrapping the request channel starves the server of every client frame in the test host. The CSP is unchanged (`connect-src 'self'` already matches the same host's `ws:`/`wss:`); adding scheme sources would allow sockets to ANY host.

**The room lock wait is bounded (M1).** Every room write runs under `withRoomWrite`, which takes the room row `FOR NO KEY UPDATE`; a queued writer holds a pooled connection while it waits, so the wait is capped by `SET LOCAL lock_timeout` (`rooms.lockWaitMillis`, `$ROOM_LOCK_WAIT_MILLIS`, default 3000, boot-validated 100..30000 through `requireConfigInt`). A wait that outruns it (SQLSTATE `55P03`) is `429` of type `urn:adder:problem:room-busy` with `Retry-After: 1`, audited `room.busy` (`byUserId`, `roomId`; not coalesced: each rejection already cost the caller the whole wait, and a pool of 20 bounds the rate, and the buckets above bound the rest). 429 because it is a cross-cutting status on every operation, and the right word: retry. Nothing was written. The setting is transaction-local and put back to the session default the moment the lock is held, so it cannot leak to the next borrower of a pooled connection and no other wait of the write (a unique-index wait, an FK key share) runs under it; it is a different setting from the statement timeout, so the "only `queryTimeout`" rule (`persistence.md` "Connection pool") is untouched. `RoomBusyTest` holds the lock in a raw transaction and pins the answer at the bound, the audit line, the clean one-connection pool and the boot validation.

**Search (`GET /api/v1/rooms/{id}/search` and `GET /api/v1/search`, V16, 0.5.0; `chat-model.md` "Search (V16)").** The surface that reads message text in bulk, so it is bounded four ways. (1) **`search`, 60/min (`RateLimits.SEARCH`),** per signed-in user, fixed in every mode, mounted INSIDE `authenticate {}` around the route and BEFORE the guards (a refused or invalid search spends a token, which also throttles a probe of foreign room ids through the 404); the rejection is the generic `429` problem plus the coalesced `rate_limit.exceeded` audit event (bucket `search`, `byUserId`). (2) **ONE deadline per request** (`search.timeoutSeconds`, `$SEARCH_TIMEOUT_SECONDS`, default 5, boot-validated 1..30; `threads/SearchBudget.kt`): each statement's `queryTimeout` (the only way a statement timeout may be set, `persistence.md`) is the whole seconds LEFT of the request's budget, so the sum of the statements is bounded, not just each one (a request ends within the budget plus at most one second of rounding); a cancelled statement (`57014`) or a deadline already spent between two statements is answered `429` of type `urn:adder:problem:search-timeout` with `Retry-After: 2`, audited `search.timeout` (`byUserId`, `roomId`; ids only), and the throwable is not logged. (3) **A process-wide concurrency cap** (`search.maxConcurrent`, `$SEARCH_MAX_CONCURRENT`, default 4, boot-validated 1..16, well under the pool's 20): a `Semaphore` `tryAcquire` BEFORE a pool connection is asked for, both routes together; a search past it is refused at once, never queued, as `429` of type `urn:adder:problem:search-busy` with `Retry-After: 1`, audited `search.busy` (`byUserId`, `roomId`; ids only). A flood of searches can therefore hold at most that many connections, and never starves the rest of the app. (4) **Page size 20 and a 100-character `q`**; the room is the first predicate, so a search never scans another room. **The search across rooms (`GET /search`) is scoped by ACTIVE membership alone, an ADMIN's too** (`authorization.md`): the rooms are read first (`RoomService.memberRooms`) and passed as one array argument, the one `search` bucket is shared with the room search, and the plan is deliberately bounded by the caller's OWN threads (`persistence.md` "Search (V16)": a hashed or table-wide plan is the abuse shape, so the probe is a correlated scalar subquery matched on an unindexable vector expression). **The abuse scenario, and its bound.** Nothing caps messages per room, rooms per user (`memberRooms` is unbounded: BACKLOG) or a message's size short of 8,000 characters. A hostile user joins many rooms of 8,000-character messages, then searches for a word that does not exist: the probe finds no first match, so it reads every message of every one of those rooms (about 7 to 10 microseconds each, `persistence.md` "Search (V16)"). The work is the CALLER's own scope, never the table's, and it is bounded: per request by the one deadline (the typed `429`, the statement cancelled by the database, nothing left running), per user by the 60/min bucket, and per process by the cap of 4 concurrent searches, so the worst case is four connections busy for the budget, not the pool. What it can still do is make other users' searches answer `search-busy` while it runs; it cannot touch another room's content, and no other route is affected. A per-user room cap, or the index in `BACKLOG.md`, are the follow-ups if that matters. **No input is a query error or an injection**: the tsquery is built by the database from `to_tsvector`'s own lexemes (`infra/db/Fts.kt`), the text is only ever a bound argument, and `&`, `|`, `!`, quotes and `:*` are punctuation. **Snippets are typed segments `{text, hit}`, never markup**: `ts_headline` returns its input untouched (`<img onerror=...>` comes back verbatim), so the server headlines with the control markers U+0002/U+0003 (unforgeable: every title and body refuses control characters), splits on them and the SPA renders `text` as text. **A search term is user content**: it travels in the query string (API-LIST-005, as the directory's `q`) and reaches no log line, no audit line and no problem body beyond the requester's own `400` (which never echoes it; `ChatLogHygieneTest` plants a marker and `ThreadSearchTest` pins the timeout path, `GlobalSearchTest` the busy path). Audit lines and problem bodies carry the path only; the metrics registry refuses names with `?`. **OpenTelemetry server spans carry `url.query` when traces are exported** (off by default, `observability.md`), so with a trace backend configured the terms reach it, as the directory's `q` already does: leave tracing off, or scrub `url.query` in the collector, where search terms are sensitive. Tests: `ThreadSearchTest`, `GlobalSearchTest`, `SearchBudgetTest`, `FtsTest`.

**Moderator delete (D2, `messages`):** a room OWNER or an ADMIN may delete, never edit, someone else's message (incident chat leaks credentials and the author may be asleep); the body is scrubbed, not just hidden, `deleted_by` records the remover and the `message.deleted` audit line says `moderated` (`observability.md`). A closed thread refuses even that (`409 thread-closed`): a steward reopens, deletes and closes it again, and the history shows it.

**The ADMIN clean-up path of a squatted name (today).** The room record has no creator column (V13 is committed). An ADMIN who finds a room squatting a name uses the audit trail:
`room.created` (byUserId, roomId, name) names the creator, `room.name_conflict` shows who probed, and `DELETE /rooms/{id}` (ADMIN, soft) frees the name. A per-creator cap on active rooms and an
owner/creator filter for ADMINs are a backlog item (`BACKLOG.md`).

**What the room surface discloses (names).** A room is members-only: a non-member's `GET`, `PUT`, archive, unarchive, `DELETE` and workflow read are the same `404` as for a room that does not exist, so
a room's existence is not disclosed through any status code (`authorization.md` "Rooms"; `RoomAuthzMatrixTest` pins the whole table); the one probe it cannot hide, the name clash, discloses only
that a non-deleted room of that name exists (`409` on create or rename; Slack's private-channel rule). The denied probe is audited (`room.access_denied`, `observability.md`), which is the
detection a quiet `404` would otherwise lack. A room response carries no other user's data (the member list, with names and emails, is `GET /rooms/{id}/members`, a reader-only route; see the third surface below).

**Email MFA (opt-in)** (`auth/MfaChallenges.kt` + `auth/MfaEmail.kt`, wired in
`configureAuthRoutes`): a per-user second factor behind the `MFA` feature flag — **the one
inverted-default flag**: `V5` seeds every existing user as MFA-disabled and `UserService.create`
inserts the disabled row for every new user (admin create and test seeds both funnel through it),
so MFA is OFF until an admin removes the row via the features PUT (the per-user editor or the
`/feature-flags` bulk screen; mind the wholesale-replace semantics — a PUT whose disabled set
omits `MFA` ENABLES it, including the "empty array re-enables everything" idiom). With MFA
enabled, `POST /login` with correct credentials normally answers
`200 MfaChallengeResponse {mfaRequired, challengeId, expiresAt}` instead of tokens (the branch runs
AFTER `verifyPassword`, so nothing about enumeration or lockout changes; `login.success` is
deliberately NOT emitted); a 6-digit code (`generateMfaCode`, `auth/Passwords.kt`) is held in the
in-memory challenge store (config `security.mfa.codeTtlSeconds` default 300
`$MFA_CODE_TTL_SECONDS`, `security.mfa.maxAttempts` default 5 `$MFA_MAX_ATTEMPTS`; per-instance
like the throttles — a restart just means signing in again) and emailed in the user's stored
language (V1 `users.language`, EN fallback). Its hard `security.mfa.maxTracked` limit (default
10,000, `$MFA_MAX_TRACKED`) first drops expired challenges, then rejects new issuance with an
audited `429` at capacity; existing codes remain verifiable and no rejected code is emailed. A
failed or cancelled delivery discards its challenge so a retry can use that slot.
`POST /api/v1/login/mfa` `{challengeId, code}` (own per-IP bucket `"mfa"`, hardcoded 10/min in
every mode) exchanges it for the ordinary `LoginResponse` — one fresh `userService.read` first
(the `/refresh` precedent: current roles/flags; a user deleted in between → the uniform 401
`user_gone`); every failure mode (unknown/expired/wrong code/attempt cap) is a **uniform 401**,
reasons live only in the `login.mfa_failure` audit event; challenges are single-use and bound to
the credential revision verified at the password step; a subsequent password change/reset
invalidates the pending challenge. **Mail-less deployments fail closed**: on
`MAIL_TRANSPORT=disabled` an MFA-enabled login answers `503` (`login.mfa_unavailable`) — don't
enable MFA flags on the k8s default `disabled` transport; the features PUT keeps working, so an
admin can always flip the flag back. SPA: the Login card's second step (`PinInput`, `auth.mfa*`
keys). Tests: `MfaChallengesTest` (pure store) + `MfaLoginTest` (route matrix); e2e `mfa.spec.ts`
drives the emailed code through Mailpit.

**Swagger/OpenAPI gate** (`plugins/Routing.kt`): `/openapi` (UI + spec) is served only in development
mode, or when `http.exposeOpenApi` (`$HTTP_EXPOSE_OPENAPI`) is explicitly `"true"` — blank follows
the mode, `"false"` hides it even in dev (the mount lives in `configureRouting`; the CSP exemption follows the same condition). Bearer auth cannot protect a browser-loaded UI (page
loads carry no `Authorization` header), hence a gate rather than `authenticate {}`.

**Accounts are admin-managed.** There is no self-signup: administrators create accounts at
`/users/new`. The initial password is generated CLIENT-side (`web/src/utils/password.ts`, 96
bits) and shown to the admin exactly once in the reveal modal — the server stores only the bcrypt
hash and no response ever carries plaintext; users rotate it via the Change password page (the
existing `PUT /users/{id}/password` rules). Deletion is the soft-delete convention plus two
guards: no self-delete (403) and the last active administrator can be neither deleted nor demoted
(409).

**Canonical email identity.** Emails are folded to `trim().lowercase()` (`canonicalEmail` in
`users/Validation.kt`) at EVERY entry point — login and `findWithIdByEmail` itself (defense-in-
depth) today, and every future create/update/import — so one mailbox is one account and a
padded/case-variant login matches (and shares one lockout bucket). The V1 partial unique index
stays byte-wise (all writes are canonical).

**Request body ceilings.** `RequestBodyLimit` (installed in `plugins/Http.kt`; the limit is chosen per call by `requestBodyLimit`) rejects oversized bodies with a `413` problem (mapped in
`plugins/ErrorHandling.kt`) from the declared `Content-Length` before any guard and any receive/validation work (a chunked body is cut off while it is read), so it answers for an anonymous or non-member caller too: a
memory-DoS backstop, not a business rule (the body is buffered before it decodes, so a few concurrent big bodies would otherwise pin real heap). **`MAX_REQUEST_BODY_BYTES` = 512 KiB** is
the global ceiling (the largest legitimate body, a whole-document workflow PUT, is well under 150 KiB); **`MAX_SMALL_REQUEST_BODY_BYTES` = 16 KiB** applies to `POST` on the anonymous
auth routes (`/login`, `/login/mfa`, `/refresh`, `/password-reset`, `/password-reset/confirm`) and on `POST /rooms/{id}/members` (up to 200 ids is about 2 KiB): bodies a handful of
short fields long. The chat writes have their own, sized from their real maxima (0.3.0 audit M3, the arithmetic is in the KDoc in `plugins/Http.kt`): **`MAX_MESSAGE_REQUEST_BODY_BYTES` = 64 KiB** for the message POST and the message PUT (8,000 UTF-16 units at the widest wire spelling, a six-byte `\uXXXX` escape each, is 48,000 bytes) and **`MAX_THREAD_REQUEST_BODY_BYTES` = 192 KiB** for the thread POST and the thread details PUT (a 200-unit title, an 8,000-unit body, and up to 20 field values of which the widest is a 2,000-unit URL: about 170 KB; a client that `\u`-escapes 20 maximal non-ASCII URLs is deliberately not covered). The status and owner PUTs take the small **16 KiB** ceiling (their bodies are one key or one id). The ceilings match the path as ROUTING sees it (`routedPath` in `Http.kt`: segments percent-decoded, empty segments dropped), so `/api/v1/rooms/%31/members` or `/api/v1/%6Cogin` meets the same ceiling as its plain spelling instead of slipping to the 512 KiB global one (`ChatWriteLimitsTest`). A thread's `fields` is also shaped right after the body decodes and BEFORE the room lock (`requireFieldShape`, `ThreadFields.kt`): at most 20 keys, each value a JSON primitive, `null` or an array of at most 50 string primitives, else a `400` with a fixed detail that echoes nothing; and the stored `fields` document has a canonical-text ceiling (`MAX_FIELDS_DOCUMENT_LENGTH`, `chat-model.md` "Field values"). Field-level `maxLength` validation rejects oversized values far earlier on ordinary payloads. `413` is declared in the spec on every body-taking operation (the spec is the
source of the count); `PayloadValidationTest` pins the global limit and the five auth routes, `RoomMembersTest` the member add (a 1 MiB `userIds` body is refused without decoding), `ChatWriteLimitsTest` the 64 KiB and 192 KiB ceilings (a maximal escaped message still posts) and the `fields` shape answered while the room is held.

**Request payload validation (convention — API-SEC-003/API-ERR-005).** Mutating routes validate
payloads up-front and throw `BadRequestException` (→ `400` + `ProblemDetail`) instead of letting
oversized/blank values die in the DB as `500`s. One cross-cutting example: password ≥
`MIN_PASSWORD_LENGTH` (10) chars AND ≤ 71 UTF-8 bytes (`validatePassword` in `users/UserRoutes.kt`
+ `MAX_PASSWORD_BYTES` in `auth/Passwords.kt` — **the bcrypt ceiling**: longer input makes bcrypt
throw, and the 500-vs-401 split would be an account-enumeration oracle, so login's
`verifyPassword` guards it too). Declare limits as `maxLength` in the OpenAPI spec. Keep new
validators feature-local and enforce them **after** the authz guard (403 wins over 400). Covered
by `PayloadValidationTest`.

**Outbound HTTP calls (SSRF posture): not ported.** v0.1.0 makes no server-initiated HTTP call (SMTP aside), so Flow's `infra/outbound/` guard (the host allow-list, `GuardedDns`, the
blocked-address ranges, `outbound.blocked`) was left in Flow on purpose (`product.md` "Donors"). Port it, with its tests, in the commit that adds the first outbound call, and add its
section and audit event here.

**CORS is off by default** (`plugins/Http.kt`): the plugin is installed only when
`http.corsHosts` (`$CORS_ALLOWED_HOSTS`, comma-separated hosts) is non-empty. Production is
single-origin (Ktor serves the SPA) and dev goes through the Vite proxy, so no cross-origin caller
exists by default — no `anyHost()`. **Reverse proxy**: set `HTTP_BEHIND_PROXY=true` (config
`http.behindProxy`) when TLS terminates at an ingress/proxy — it installs `XForwardedHeaders`,
configured to honour ONLY the canonical `X-Forwarded-Host`/`X-Forwarded-Proto` (Ktor's defaults
also read `X-Forwarded-Server`/`X-Forwarded-Protocol`/`X-Forwarded-SSL`/`Front-End-Https`, which a
proxy that sets just the canonical ones passes through from the client untouched — Lettuce's
v3.6.2 finding, ported) and never `X-Forwarded-Port` (a client-supplied non-numeric value used to
throw a 500 out of `CallSetup` before any route ran; nothing reads it, since the scheme-derived
default port is all the HTTPS redirect needs), so rate-limit buckets key on the real client IP and
the HTTPS redirect sees the real scheme; the proxy must set (and strip client-supplied)
`X-Forwarded-For`/`X-Forwarded-Proto`. **`X-Forwarded-For` is folded before Ktor ever reads it**:
Ktor's `XForwardedHeaders` resolves a for-header via `Headers.get(name)` — the FIRST header LINE
only — so a proxy that APPENDS a fresh `X-Forwarded-For` line instead of merging into one the
client already sent (HAProxy's `option forwardedfor`) would leave the client-supplied first line as
the trusted value. `resolveForwardedForOrigin` (`plugins/Http.kt`) runs at the `Setup` phase —
strictly before `XForwardedHeaders`' own `Plugins`-phase `onCall` — reads every
`X-Forwarded-For` header line via `getAll`, folds them into one hop-ordered list (RFC 2616: same-
name header fields may be combined by joining their values with a comma, in the order received),
and resolves the trusted hop itself directly into the call's `MutableOriginConnectionPoint`;
`XForwardedHeaders` is then configured with an empty `forHeaders` so it never overwrites that
result with its own unfolded, first-line-only read. The hop selection itself is unchanged: read
from the END of the combined list, keyed on `HTTP_PROXY_HOPS` (config `http.proxyHops`,
boot-validated ≥ 1 via `requireConfigInt` — a config error, not a runtime concern): 1 (the default,
one TLS-terminating proxy that APPENDS) trusts the value the last proxy wrote; N skips the N-1
addresses trusted proxies appended after the client's — never the first value, which is
client-supplied and spoofable. A hop count in excess of what the request actually carries falls
back to the last available value (the same fallback Ktor's own `useLastProxy()`/
`skipLastProxies()` apply) rather than throwing. Off by default because honoring these headers
from direct clients lets them spoof both. Covered by `ForwardedHeadersTest`; the multi-line fold
itself needs a real Netty engine to pin (`RawForwardedForLinesTest` writes the raw HTTP/1.1
request by hand — ktor-client's own request writer folds repeated `header()` calls into one wire
line before send, so `testApplication`'s client cannot reproduce two genuinely distinct lines).

**The client address is NUMERIC, with or without a proxy** (0.4.0 audit). Every per-IP bucket (login lockout, refresh, ready, `live-connect`) and the live channel's per-address pending share key by `call.request.origin.remoteHost`. On Netty that defaults to `InetSocketAddress.getHostName()`, a REVERSE-DNS lookup: a client with forward-confirmed PTR records for many addresses would get one key per address (and an IPv6 /64 would not group), and one whose PTR is shared or slow would share a key. So `configureHttp` registers a `Setup`-phase interceptor FIRST, before anything reads `remoteHost` (the JDK caches the resolved name), that copies `call.request.local.remoteAddress` (`getHostString()`, the literal) into the origin's `remoteHost`; behind a proxy, `resolveForwardedForOrigin` (registered after it) overrides that with the trusted hop, and a request with no `X-Forwarded-For` keeps the proxy's numeric address. The helper is `numericClientHost` (`ClientAddressAndHostTest`, played by hand because a test host cannot make Netty resolve a name; the proxy-trust tests `ForwardedHeadersTest` and `RawForwardedForLinesTest`, the latter on the real Netty engine, still hold).

**A malformed `Host` with `http.corsHosts` set** (0.4.0 audit): Ktor's CORS plugin compares the `Origin` with the request's own authority, and `origin.serverPort` throws a `NumberFormatException` on a `Host` whose port is not a number, so every `/api` call carrying an `Origin` was a 500 with an ERROR log and a stack trace. When CORS is installed, a second `Setup`-phase interceptor (before CORS's `Plugins`-phase hook) answers such a request `400` with a problem document; without CORS nothing reads the port and the interceptor is not installed (a bracketed IPv6 literal in `Host` is unreadable to Ktor too, so with `corsHosts` set it is a 400 as well). The live admission's own `detail=host` refusal stays as a backstop. 400 is a cross-cutting status (`CROSS_CUTTING_STATUSES`: the coverage gate asks no per-operation entry; the runtime conformance plugin only checks an operation that actually answers one it does not declare, as in `ClientAddressAndHostTest`, which uses `POST /login`, which declares 400).

**Trust the address or the share degrades** (`live.md` "The pending share per address"): the per-address pending share and the `live-connect` bucket are only as good as `remoteHost`. Behind a plain proxy with `http.behindProxy=false`, or through an SNAT load balancer (`externalTrafficPolicy: Cluster`), every client arrives as the proxy's or the node's address and they all share ONE `live.maxPendingPerAddress` (8); with `behindProxy=true` and no proxy that strips client-supplied `X-Forwarded-For` (the shipped OrbStack LoadBalancer, "Known gap" in the README), a client picks its own key and the share is bypassable. Fix the trust (a proxy that sets the header and `behindProxy=true`, or `externalTrafficPolicy: Local`), or raise `live.maxPendingPerAddress`.

CSRF install is gated behind `security.csrf.enabled` (default **`false`** in `application.yaml`,
env-overridable via `SECURITY_CSRF_ENABLED`); the configured `originMatchesHost()` +
`allowOrigin("http://localhost:8087")` + `checkHeader("X-CSRF-Token")` combo is unsatisfiable from
both the Ktor test client and the dev SPA on `:5179`, and CSRF protection is anyway moot for this
app's bearer-JWT auth model — browsers do not auto-attach `Authorization` headers, so cross-site
forms cannot forge an authenticated request. Re-enable only if you move to cookie-based session
auth and fix the allow-list accordingly.

**Default admin.** Migration `V3__seed_admin.sql` inserts a single bootstrap administrator on
first boot: `admin@adder.local` / `changeme` (role `ADMIN`), idempotent via `ON CONFLICT DO
NOTHING`. The migration is kept **unchanged** (dev + e2e depend on it; checksums must not change)
— production neutralizes it at startup via the bootstrap above. There are no demo seed users.
**Kubernetes secrets** live in the `adder-secrets` Secret in the `adder` namespace
(`k8s/secret.yaml` is a placeholder template — create the real one out-of-band with the
`kubectl create secret generic` command in its header; the `app` Deployment consumes it via
`secretKeyRef`).

**Probes and the plain-HTTP crash-loop** (`k8s/app-deployment.yaml`, ported from Toadie): every
probe (`startupProbe`/`readinessProbe`/`livenessProbe`) sends `X-Forwarded-Proto: https` — the
header the TLS-terminating ingress sets, which `HTTP_BEHIND_PROXY`/`HTTP_PROXY_HOPS` above makes
the app trust. Without it, production mode answers the kubelet's plain-HTTP probe request with a
301 to `https://<pod-ip>/`, which the kubelet FOLLOWS (same host) into a refused `:443` — every
probe fails and the pod crash-loops (Lettuce's v3.6.1 incident, reproduced in production).
`startupProbe` (30 x 5s = ~150s) covers JVM boot + Flyway migrations before the liveness clock
starts, so a slow first boot cannot trigger a restart.

**Kubernetes pod and network hardening** (`k8s/*.yaml`, validated by ci.yml's `k8s-static`
job — `kubeconform -strict` over the raw manifests and over what `k8s/apply-local.sh` renders):

- **Pods.** Both (`app`, `postgres`) set `automountServiceAccountToken: false` — nothing
  calls the Kubernetes API, so no token is mounted. `app`: uid 10001, `runAsNonRoot`, seccomp
  `RuntimeDefault`, read-only root filesystem (only `/tmp`; the AppCDS archive `/app/cds/adder.jsa` is a root-owned 0444 file baked into the image and only ever read), no privilege escalation, `drop: [ALL]`.
  `postgres` runs the same way minus the read-only root filesystem: `runAsNonRoot` as the alpine image's
  own `postgres` user (**uid/gid 70** — 999 in the Debian image, so change `runAsUser`/`runAsGroup`/
  `fsGroup` together with the image variant), `fsGroup: 70` with `fsGroupChangePolicy: OnRootMismatch`,
  seccomp `RuntimeDefault`, `allowPrivilegeEscalation: false`, `drop: [ALL]` (a non-root start needs no
  capability: the entrypoint's chown/gosu step only runs as root). `readOnlyRootFilesystem` stays off for
  postgres — it writes its socket/lock files under `/var/run/postgresql` and temp files under `/tmp`.
- **`k8s/network-policies.yaml`.** `default-deny-all` (ingress AND egress for every pod in `adder`), then:
  `postgres` accepts 5432 only from `app`; `app` accepts 8087 (see the open decision below);
  `app` may egress to `postgres:5432`, to cluster DNS (kube-system `k8s-app=kube-dns`, 53
  UDP/TCP) and to public addresses on 443, 587/465/25 (SMTP) — RFC 1918,
  link-local (cloud metadata `169.254.169.254`) and CGNAT ranges excluded, so a compromised pod cannot
  reach other namespaces or cluster services. Things that need their own extra policy:
  an in-cluster/private SMTP relay, an OTLP collector (`OTEL_*_EXPORTER=otlp`; the default is console),
  any private upstream. **Cluster assumptions:** DNS is
  matched as pods labelled `k8s-app: kube-dns` in the `kube-system` namespace (stock CoreDNS); a
  NodeLocal DNSCache (link-local resolver address) or OpenShift (`openshift-dns` namespace, different
  labels) needs the DNS rule adjusted, or every lookup times out; SMTP submission on port 2525 and any
  IPv6 destination are not covered (the `ipBlock` is IPv4 only) and need their own rule. Enforcement needs a CNI that
  implements NetworkPolicy (OrbStack's local cluster does). A CNI that does not enforce leaves the objects inert, which is harmless. New
  pods take a few seconds to be programmed, so a very fast first connection can fail once — the
  `startupProbe` window absorbs it.
- **The live socket needs the front door to pass the upgrade** (0.4.0; `ci.md` "The live socket in front of a proxy"): `Upgrade`/`Connection` forwarded, read timeouts of 60 s or more, and the browser's own scheme, host and port: TLS ended at the proxy needs `X-Forwarded-Proto: https` plus `http.behindProxy=true`, a public port other than 443 must be in `X-Forwarded-Host`, and `Host $host` alone behind TLS is a `403` (right host, wrong scheme), otherwise every Origin check is a `403`.
- **Still open — the front door (decision pending).** `k8s/app-service.yaml` is an OrbStack
  `LoadBalancer` and the manifests default to the production posture (`KTOR_DEVELOPMENT=false`,
  `HTTP_BEHIND_PROXY=true`), which assumes a TLS-terminating proxy that sets and overwrites
  `X-Forwarded-For`/`-Proto`. The reference ships no Ingress: over the bare LB, a plain-HTTP request gets
  the production HTTPS redirect and `X-Forwarded-*` is client-controlled. Either (a) add a TLS Ingress +
  `type: ClusterIP`, or (b) keep the LB and document a local overlay with `KTOR_DEVELOPMENT=true` /
  `HTTP_BEHIND_PROXY=false`. Whichever is chosen must also tighten `allow-app-ingress`, whose `from` is
  deliberately absent today (the 8087 port is its only restriction), to the ingress controller's
  namespace.

**Log hygiene.** Every data class that carries a password, token, MFA code, password hash or signing key
(`LoginRequest`, `LoginResponse`, `MfaVerifyRequest`, `RefreshRequest`, `LogoutRequest`, `User`,
`UserCreateRequest`, `PasswordUpdateRequest`, `PasswordResetConfirmRequest`, `JwtConfig`, the MFA challenge types, `IssuedToken`)
overrides `toString()` and prints each secret
through `maskedSecret` (`infra/Secrets.kt`: `***`, or `null` when absent), so templating one into a log line or an
exception message never reveals it; a new secret-bearing class does the same. Pinned by `SecretToStringTest`. `infra/db/Flyway.kt`'s startup log line renders the operator-supplied JDBC URL
through `jdbcUrlForLogging()` — `host:port/db` only, no userinfo or query string — so a
`jdbc:postgresql://host/db?user=...&password=...` form never lands a credential in the log
(`.claude/docs/observability.md`, "never log secrets"); covered by `FlywayLogTest`. `POST
/api/v1/logout`'s malformed-body debug line (`auth/AuthRoutes.kt`) logs only the parse failure's
exception CLASS NAME, never the throwable — the cause chain can embed a body excerpt (kotlinx's
decode-error message), the same `errorType`-only rule as `login.mfa_send_failed`/
`password_reset.send_failed`; covered by `LogoutTest`. The delivery-failure ERROR lines (`auth/MfaEmail.kt`, both of `auth/PasswordResetEmail.kt`'s) log the class name (the MFA one also the user id), never the address or the throwable (an SMTP refusal can echo the recipient and server detail); `MfaLoginTest` pins the MFA line against a server that does exactly that. **Chat content never reaches a log line**: a message body, a thread title and a field value are free text (an incident chat holds pasted credentials), so no logger renders them - the audit lines carry ids, keys and counts, a refused write's `400` detail is a response, never a log, and `ChatLogHygieneTest` plants a marker in a body, a title and a TEXT field (written and refused: a control character, a nested value, an unknown key, a 413, a stale revision) and asserts it appears in no event of the ROOT logger (message, arguments, key/values, MDC, throwable chain). **A failed database statement never logs its bound values either**: a database exception's MESSAGE is unsafe (Exposed's `ExposedR2dbcException.getMessage()` appends the failed statements with their arguments expanded whenever the transaction's `debug` flag is on, and Postgres itself echoes values into some messages: an input-syntax failure, a `RAISE`, a malformed tsquery), so the 500 catch-all logs a chain holding one (or any suppressed exception, or a chain past the walk's bound: fail closed) as class names, the SQLSTATE and stack frames only (`plugins/ScrubbedFailure.kt`, `observability.md` "The 500 line"); any new log site for a throwable that may come from the database does the same (`scrubbedForLog()` for a throwable, `scrubbedSummary()` for one line), or logs `javaClass.simpleName`; the telemetry collector failure line and the readiness probe's DEBUG line already do. Two loggers that print a cause below INFO (`Exposed`, `io.ktor.server.plugins.statuspages`) are pinned to INFO in `logback.xml`. **Spans too:** `core`'s `ScrubbedSpanExporter` wraps every configured trace exporter so a recorded exception leaves as `exception.type` only, never `exception.message`/`exception.stacktrace` (`ScrubbedSpanExporterTest`). `ChatLogHygieneTest`'s echo test makes a message INSERT fail with a database message that carries the body and asserts the body in no ROOT-logger event (it fails on the unscrubbed catch-all). A **hashtag** is content too: a second test plants a tag marker and writes, edits, deletes, filters by and lists it (a refused `tag` filter included), and the boot reindex logs class names only (`MessageTagReindexTest`). Its cancelled-statement (57014) test is only a shape pin that the line still names the driver class and the SQLSTATE, not a leak proof.

**Outbound email** (`infra/mail/`, ported from Lettuce): `configureMail` (registered at the top of
the infrastructure group, before Flyway) publishes `MailerKey` holding a `Mailer` or null.
`mail.transport` (`$MAIL_TRANSPORT`) selects `log` (dev default — the full message, **including
reset links and MFA codes**, goes to the `ch.nokillswit.mail` logger; **production mode refuses to
start on it**), `smtp` (Jakarta/Angus Mail over `mail.smtp.*` / `$SMTP_HOST` etc.; a blank host
refuses startup in any mode), or `disabled` (the Docker image default via
`ENV MAIL_TRANSPORT=disabled` — email features answer 503 through `respondMailUnavailable`). The
compose demo wires `smtp` → the bundled Mailpit (`http://localhost:8030` — 8025–8029 belong
to sibling stacks); k8s ships `disabled` with `SMTP_USER`/`SMTP_PASSWORD` read (optional) from
`adder-secrets`. Consumers: self-service password reset and email MFA. The recipient-language
content layer (`LocalizedText`) lives beside the transports; `mail.appUrl` is validated here too (above). Tests:
`MailTransportTest` (the transport matrix + both refusals + LogMailer delivery via the
`ch.nokillswit.mail` LogCapture); production-mode boot tests must override `mail.transport` with
`"disabled"`, since the dev-default `log` transport is refused in production.

**Encryption at rest** (`infra/crypto/`, Lettuce's, ported; v0.1.0 stores no encrypted value yet — the first consumer will be a stored credential, e.g. a bot token). Sensitive columns are encrypted application-side with AES-256-GCM (`FieldCipher`: a
fresh 12-byte nonce per value, 128-bit tag, envelope `enc:v1:<base64(nonce||ciphertext)>`), so a
database-level attacker — SQL access, `pg_dump`, a stolen volume or backup — sees ciphertext; the
key lives with the app (`DATA_ENCRYPTION_KEY`, 64 hex chars from `openssl rand -hex 32`; compose
ships a burned demo key, k8s reads `adder-secrets`), never in the database. **Losing the key loses
every encrypted value** — back it up apart from the DB. Rotation: set the OLD key as
`DATA_ENCRYPTION_KEY_PREVIOUS` (decrypt-only fallback — a wrong-key attempt fails cleanly on GCM's
tag, then the previous key is tried) and the new one as `DATA_ENCRYPTION_KEY`, then re-encrypt every row under the
current key and remove the previous key. **Not ported yet:** Flow's `EncryptedAtRest` registry (a service owning encrypted columns registers
in `infra/db/Bootstrap.kt`; never remove a registration once one lands — a rotation would strand that feature's rows) and `Reencrypt.kt` (`reencryptRows`,
the boot backfill/rotation body, selecting through the ONE sanctioned SQL predicate over an encrypted column: `notLike "enc:v1:%"`) have no consumer here and
come back with the first encrypted column. **Never filter or sort on an encrypted column in SQL** — ciphertext carries no order or equality (every value has its own nonce).
A credential column is write-only end to end: accepted on create/update, never returned, never in an audit line (the response carries a `has…` boolean instead).
Tests: `FieldCipherTest` (roundtrip, fresh nonces, legacy passthrough, tamper, wrong key, rotation, malformed keys), `CryptoBootTest`.

**Trusted PostgreSQL extensions.** `unaccent` (V4) is a contrib extension trusted since PG13: `CREATE EXTENSION IF NOT EXISTS` needs only `CREATE` on the
database, no superuser. Any further contrib extension a migration needs is checked for the same trust before it is relied on.

### Telemetry

`telemetry/` (`observability.md` "Telemetry") records Adder's own vital signs into `ops.*` and serves them to ADMINs only (`GET /api/v1/telemetry/current`
and `/history`, `authorization.md`; per-user bucket `telemetry`, 30/min). What it discloses, and to whom — every ADMIN, nobody else (a USER gets the
uniform `403`):

- **Process identity:** the role (the constant `app`), the pod / host name (`HOSTNAME`, up to 100 control-free characters), the JVM start, the image's build version and
  commit, the processor count, the heap maximum and the container memory limit - and the live memory, CPU, thread, GC and connection-pool figures.
  NEVER JVM arguments, environment variables, system properties, request paths with identifiers or any user or chat data (pinned: the `ops` tables hold
  numbers and these labels only).
- **Route usage:** the normalised API routes (`/api/v1/users/{id}`, path parameters as written, never an identifier) with their request counts,
  latencies and 5xx / 429 counts. The telemetry routes themselves and the probes are excluded.

**Metric-name cardinality is bounded.** Ktor's `DropwizardMetrics` (3.6) names a meter after `request.uri` - query string included - for every response it
could not route and for every second response of a call (the StatusPages re-answer of the RateLimit plugin's bodiless `429`, routing's `405`), and a
registered Dropwizard instrument is never evicted: an unauthenticated client (`POST /api/v1/login?x=<random>` past the login bucket, random unrouted
paths or methods) could mint one meter per distinct URI until the heap is gone, and the periodic `Slf4jReporter` would print each of them, query
strings included. So `plugins/Monitoring.kt`'s `TelemetryRegistry` registers NO `ktor.calls.` name containing a `?` (the caller gets a detached,
unregistered instrument), caps the whole `ktor.calls.` family at 2,000 registered names (past it: detached instruments and one WARN), and the reporter
filters out any name with a `?` as defence in depth. Pinned by `TelemetryRegistryTest` (a flood of unrouted, 405 and rate-limited requests with varying
query strings registers none). The sampler reads the registry's timers and meters only, so detached instruments cost it nothing.

### Not yet ported

Flow's outbound-call guard (above) and its `EncryptedAtRest` registry / `Reencrypt` boot backfill ("Encryption at rest") have no consumer here and come back with the first feature that needs
them; new subsystems arrive with their own section in this file.
