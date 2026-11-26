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
- **Postgres password** (`infra/db/DatabasePassword.kt`, called at the head of `configureFlyway`, the first module to open a
  connection, so nothing ever connects with a public password; checkup C-ARCH-4): a blank password, the shipped default `adder`
  (the `postgres.password` default in `application.yaml` and the compose demo database) or the k8s template placeholder
  `CHANGE-ME` (also `changeme`; trimmed, case-insensitive) → warn in development, **refuse to start** in production. The
  message names `POSTGRES_PASSWORD`, never the value. Compose is unaffected: it runs development mode (`KTOR_DEVELOPMENT=true`) on the
  `adder` demo password, so `docker compose up --build` still works untouched; a real deployment creates the database with a
  private `POSTGRES_PASSWORD` (k8s: the `adder-secrets` Secret, `openssl rand -hex 16`) and an existing database whose role
  still has `adder` needs `ALTER ROLE adder PASSWORD '…'` first. The test container's password is deliberately NOT `adder`
  (`PostgresTestSupport`), so production-mode boot tests reach the later checks; `DatabasePasswordBootTest` pins this one.
- **Browser push endpoints (V44):** `DATA_ENCRYPTION_KEY` encrypts each provider endpoint with AES-GCM; only a SHA-256 digest is used
  for uniqueness. Endpoint values, VAPID tokens and key material never reach logs or audit fields. Production keeps refusing the
  burned key, and development mode keeps tolerating every
  burned default above with a warning (the clone-and-run demo). Neither is a gap to close.

**Browser Web Push (V44).** Opt-in is per provider endpoint and per signed-in account, at most five devices under a lock on the active
user row. Only known HTTPS browser-push provider hosts are accepted; all DNS answers are bounded and public IPv4, pinned into an
OkHttp client with proxies, redirects, cookies, retries and authentication disabled. The empty RFC 8030 POST carries no chat content:
the service worker shows generic copy and opens the already-authorized bell. Queue rows are made only with new in-app notices after
registration (no historical replay), claimed before any network call and expire after 15 minutes. Immediately before each send the server
rechecks the current subscription, active account and credential revision, room and membership, unseen notice and thread mute. A 404 or
410 deletes the endpoint; subscriptions untouched for 90 days expire. VAPID uses a matching P-256 key pair and a `mailto:`/`https:`
subject; partial, malformed or mismatched settings refuse startup, while all three blank make capability discovery answer unavailable.

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
one-time use): a `/refresh` response can be lost after the claim commits, leaving the client holding a retired token.
V55 records the rotation in the same PostgreSQL transaction as the denylist claim. A retry from any instance can receive
another pair within the database-clock grace period. One token has two aggregate re-mints (`MAX_GRACE_REUSES`). A replay
outside that window is `401` with `refresh.rejected reason=replayed` while the rotation is recorded. A logout-revoked or
metadata-less denylist entry is `reason=revoked` and has no grace. At the shared 10,000-slot limit, issuance can reclaim
rotations whose grace has ended. A new rotation without an available slot is strict.

`/logout` requires a valid access token. Its transaction revokes that JTI and any valid refresh JTI presented, clears the
affected accounts' rotations, and publishes the exact access-session hint at commit. It serializes with refresh claims for
those accounts. Other live token pairs remain valid. A refresh that claimed its token before the logout can still return a
fresh pair. The SPA handles an expired access token by trying a detached refresh before revoking the fresh pair. If that
refresh fails, it clears local sign-in state and the outstanding refresh token lives until expiry. A direct logout request
with an expired access token receives `401`. Keep the supplied `Recreate` strategy during the V55 upgrade. An old process
cannot clear a new process's durable grace records. An upgraded denylist entry from before V55 remains strict. The accepted cost of the grace: a stolen token replayed
within the grace of its legitimate rotation also gets a pair, at most twice (the window is short, the replay is audited and a logout ends it). There is
deliberately NO family-wide revocation and no "sign out everywhere": a replay outside the grace does not end the live
chain, and a stolen token that refreshes FIRST makes the victim's next refresh the rejected replay (the victim is signed
out and the trail shows `replayed`). The access token a rotation supersedes is not revoked: it stays valid until its
own expiry (<= 15 min; only a logout blocklists an access token, and the per-request check above ends it on any
delete, role change or password change). `POST /api/v1/logout` revokes the access token it is
called with and the one refresh token the client sends in the body (best-effort; both jtis go to the blocklist); it does
NOT end other pairs of the same user. What does end every outstanding refresh token of a user is a password
change/reset/bootstrap rotation (the `credential_revision` bump) or an admin deleting the account (`/refresh` re-reads
the user), and the same events end the ACCESS token at its next request (the per-request check above); a session that nothing refreshes also ends at the refresh TTL. **[changed] 1.2 D9: the refresh TTL is no longer the whole idle window.** A VISIBLE tab re-mints its socket ticket at every access-token expiry (the close 4001), and that refreshes the pair, so a visible tab never idles out. Until 1.2 a tab hidden for 5 minutes closed its socket and stopped doing so. Now a hidden tab goes quiet at 5 minutes and keeps its socket, and so keeps re-minting and refreshing at each 4001 (once per access-token lifetime), until it has been hidden for 8 hours of WALL time (`live.md` "The hidden tab": the client checks the clock at each quiet ping and before each re-mint, so a sleeping laptop's stalled timers do not extend it), when it closes. A forgotten hidden tab therefore keeps the session for up to 8 h + the refresh TTL, instead of 5 min + the refresh TTL, and holds one of the person's 16 sockets (`live.maxSocketsPerUser`) and one of the process's `live.maxSockets` meanwhile. A hidden or `idle` tab issues no re-read when the browser comes back online (`queryClient.ts`, `web-internals.md` "The live channel"). What can still refresh the pair while it is hidden is the socket's 4001 re-mint and a QUIET (hidden, not yet idle) tab's once-a-minute bell re-read (`live.md` "The hidden tab"), which is an API call and refreshes on a 401; both stop at the 8 hours, when the tab closes (`idle`) and makes no call at all, so the 8 hours stay the client's bound. The TTLs are DEFAULTS (access 900 s, refresh 3600 s: `JWT_ACCESS_EXPIRES_IN_SECONDS`, `JWT_REFRESH_EXPIRES_IN_SECONDS`, each configurable from 1 s to 365 days), so the tail after the 8 hours scales with the refresh setting. Accepted by the user (1.2 Q9). **The bound is strict only if nothing else calls the API from a hidden tab (1.2 branch audit):** TanStack's `refetchOnReconnect` used to re-read every mounted query on `online` even in a hidden or `idle` tab, and each 401 there silently refreshes the pair; `utils/queryClient.ts` now turns that refetch off while the tab is hidden or `idle` (`web-internals.md`; the focus refetch fires only on becoming visible and the one interval poll does not run in the background), so the live client's re-mint is the only keep-alive. The server is unchanged: it cannot tell a hidden tab from a visible one, and the 8 hours are the client's bound; a client that ignores it is no worse than any client that refreshes in a loop. Every other end of a session still works on a quiet socket: logout, an account delete or a password change (the next re-mint is refused, and the 60 s sweep closes the socket with 4003), a role change (the access token ends at its next request and the refresh re-reads the user, so the new pair carries the new roles; the sweep closes the old socket), and the refresh TTL once the tab has closed. Each refresh writes one `revoked_tokens` row for the token it replaces (one per access-token lifetime per signed-in session, a quiet hidden tab included; pruned at the next revoke once expired). The SPA copes with two tabs sharing one session
(`web/src/api/http.ts`, `web-internals.md`). The keep-script-out control is `plugins/SecurityHeaders.kt`, installed unconditionally and pinned by
`ServerTest` (its Plugins-phase interceptor, plus `appendSecurityHeaders` called by the two answers `plugins/Http.kt` gives earlier, in the Setup phase, that end the pipeline before it: the `415` for an encoded body and the `400` for a malformed Host; both pinned too; they carry no HSTS, because Ktor's HSTS hooks `onCall`, which a Setup-phase `finish()` skips - harmless, every other production response carries it): a strict CSP (`script-src 'self'`, `style-src 'self' 'unsafe-inline'`,
`object-src 'none'`, `base-uri 'self'`, `frame-ancestors 'none'` — the whole string is pinned
verbatim in `ServerTest`), `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`,
`Referrer-Policy: no-referrer`, `Permissions-Policy: geolocation=(), camera=(), microphone=(), payment=()`
(also pinned verbatim), `Cross-Origin-Opener-Policy: same-origin` and `Cross-Origin-Resource-Policy: same-origin` (C-SEC-9: on EVERY answer, the two early
Setup-phase ones and Swagger UI included, because they ride `appendSecurityHeaders`; nothing of Adder is meant to be embedded or opened by another site, the SPA loads
every resource from its own origin, and a CORS-mode `fetch` of the `http.corsHosts` callers is not subject to CORP; pinned by `CrossOriginPolicyTest`), and — production mode only, `plugins/Http.kt` — `Strict-Transport-Security`
(`max-age=31536000; includeSubDomains`, pinned by `ServerTest`'s production-mode boot behind
`http.behindProxy` with `X-Forwarded-Proto: https`; absent in development). Every `/api/` JSON and problem+json answer additionally carries
`Cache-Control: no-store` (`plugins/Http.kt`'s `CachingHeaders`, pinned by `ServerTest` together
with the unchanged caching of the SPA's static assets), so per-user data never lands in a browser
or proxy cache ("Caching" below). `text/html` (index.html and every SPA deep link answered with it) carries `Cache-Control: no-cache`
(revalidate every time, so a deploy's new hashed asset names are never masked by a stale shell), while the content-hashed
CSS/JS keep `max-age=86400`. The `Server` header is the fixed generic `adder` (`install(DefaultHeaders) { header(Server, …) }`
in `Http.kt`) — Ktor's default `Server: Ktor/<version>` disclosed the framework and its exact version; pinned in `ServerTest`
(no `Ktor` in it).

**Caching -- a settled decision (the owner chose `no-store`).** Every `/api/` answer (JSON and `problem+json`) is `Cache-Control: no-store`, so nothing lands in a browser or proxy cache. No route answers through cache validators: Flow's report GETs were the donor's only consumer, and its `ETag` seam (`ResponseValidators`) was removed unused (checkup C-SMELL-2); the first view GET that wants one ports it back. The rejected alternative was `private, no-cache` + `Vary: Authorization` + a logout `Clear-Site-Data: "cache"`, which would leave response bodies on disk between logins on the same profile (best-effort clearing only). Cross-user safety of any future `304` rests on two rules: authentication runs BEFORE any validator logic (an unauthenticated request is `401` with no validator, so a `304` is never a way to probe for data), and a validator never covers content that depends on the caller unless the caller's verdict is folded into the `ETag`.


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

**Per-account login lockout** (`auth/LoginThrottle.kt`, built by `configureLoginThrottle` in `auth/AccountLockout.kt`, published as
`LoginThrottleKey`; the module loads BEFORE the user routes, which share it): after
`security.lockout.threshold` (default 5, `$LOGIN_LOCKOUT_THRESHOLD`) consecutive failures for one
submitted email, `/login` answers `429` for `security.lockout.durationSeconds` (default 900,
`$LOGIN_LOCKOUT_DURATION_SECONDS`; longer when wrong emailed codes tripped it, see "progressive for MFA codes ONLY" below) — even with the correct password, and regardless of whether the
account exists (no enumeration signal). A success resets the counter. The PostgreSQL-shared store limits admission for ordinary
identities with `security.lockout.maxTracked` (default 10,000, `$LOGIN_LOCKOUT_MAX_TRACKED`). It reclaims expired state in bounded batches.
Fresh counters and active locks are never evicted to admit a new identity. At capacity, an untracked
identity receives an audited `429` before password verification, so saturation cannot bypass the
account throttle; tracked identities can still finish their normal flow. Known accounts can still record failures beyond that cap,
bounded by the users table. A fixed-size, deployment-keyed digest of
the canonical submitted identity bounds retained key size even for malformed login input;
oversized identities are also digested in audit fields instead of logged in full. The deployment
never stores the submitted address in this operational state, and a separate derivation label keeps the key apart from the audit
digest and password-reset cooldown. PostgreSQL time supplies every deadline, and the same counter is observed by every app instance
and survives restarts. This complements the per-IP `RateLimit` bucket,
which rotating hosts sidestep. The 429 is **thrown** (`TooManyRequestsException`), never
`respondProblem`ed directly — StatusPages' generic 429 status handler rewrites any non-StatusPages
429, so only the exception path keeps the specific detail. Tests: `LoginThrottleTest` (unit,
injected clock) + `LoginLockoutTest` (route). The SPA shows a neutral temporary sign-in limit for
either 429 cause.

**ONE counter for every guess at an account's credentials (C-SEC-2, C-SEC-3).** A wrong password at `/login`, a wrong emailed code at
`/login/mfa` and a wrong `currentPassword` on the self password change (`PUT /users/{id}/password`) all feed the same
`LoginThrottle` counter, so N wrong guesses across the three lock the account exactly as N failed logins would
(`AccountGuess` in `auth/AccountLockout.kt`). What each answers: the locking failure answers what
that route always answers (401 at login and the MFA step, 403 on the change); every LATER attempt answers the lockout's specific
`429` (thrown, see above), the change BEFORE any bcrypt (preflight), and an unseen identity at a full store the audited capacity
`429` - **but only where the account is not yet known (the 1.0.0 checkup's M1).** The MFA step (the challenge's subject) and the
self password change (the signed-in caller) act on an account that EXISTS, so their failures are counted even at a full store
(`AccountGuess.knownUserId`, `AccountThrottle.preflight/recordFailure(knownUserId = ...)`): otherwise a password holder could fill
the store with junk logins (about 670 a minute) and then guess emailed codes without ever locking. **The memory bound stays:** the
extra entry has a nullable unique foreign-key binding to the proved user id, so a full store has at most `maxTracked` physical
unbound identities plus one reserved row per durable user. A known operation locks that current active user row, rejects a stale
captured email, then takes the old/current digest advisory locks in lexical order and moves the bound row; repeated ADMIN email
changes cannot add reservations. An active user who later receives a deleted or renamed user's former address reclaims that stale
binding under the digest lock; the locked current user row plus active-email uniqueness proves the two durable user ids cannot both
actively own the address. An unbound row is the address-level failure window accumulated before an account was proved, so a later
proved operation at that same current address preserves and binds it; foreign bound state is discarded on address reuse. The capacity
refusal stays for an UNKNOWN identity, and at `/login` it stays for every untracked email:
the refusal comes BEFORE any password is compared and answers the same `429` for a known
and an unknown email, so it leaks nothing and lets no guess through, whereas counting a wrong password for a known account there
would have to answer differently from an unknown one (an existence oracle). **The accepted cost:** the above-cap entries can only be made for REAL accounts, so while an attacker holds the store full, a `/login` probe of an email flips from the capacity `429` to `401` once that account's owner mistypes an MFA code or their current password. That proves the account exists, and the probe then counts a failure against it. It needs the owner's own mistake inside the lock window plus a sustained fill that already denies sign-in to everyone untracked, so it is accepted. A correct password (non-MFA), a correct code or a correct current password clears the counter; a MISSING `currentPassword`
is a plain 403 that does not count (nothing was guessed). An admin resetting ANOTHER user's password verifies nothing, so it neither
counts nor is refused by the target's lock; the cost (L2) is that a locked owner with a live session cannot change their own
password until the window ends. The ways out: the emailed reset link (`POST /password-reset` is not behind the lock and a confirmed reset
changes the password without `currentPassword`), or an admin resetting the password; with `MAIL_TRANSPORT=disabled` (no reset mail) only
an admin can. For an MFA account the password step does NOT clear the counter (it is only the first factor: a
caller holding the password could otherwise log in afresh after every few wrong codes and never lock); an accepted code does.
The digest key is derived from the validated deployment JWT secret. A coordinated rotation stops every old-key process, clears only
the operational `account_login_attempts` and `password_reset_cooldowns` tables, then starts every process with the new key. This
resets lockout/escalation and cooldown state, never credentials, reset grants or token-revocation data. Intentionally retained proved
rows can still be found by user id and moved, but anonymous old-key rows and cooldowns are unaddressable by new-key submissions and
occupy capacity until their deadlines expire.
**Pending MFA challenges and a lock:** a lock tripped by the MFA step (or met by a wrong code there) drops the account's pending
challenges; a lock tripped by wrong passwords at `/login` or on the change does NOT, but no pending code can finish a sign-in
during it (a correct code answers the lockout's `429`, `AccountGuess.recordSuccess`) and the challenges expire on their own TTL.
**The lock is progressive for MFA codes ONLY (the owner's 2026-11-20 checkup decision; `LoginThrottle`).** Someone who HOLDS the
password and guesses emailed codes used to get `threshold` (5) guesses per flat 15-minute window indefinitely (about 175,000 guesses
a year against a 6-digit code, roughly a 16 % chance per year). Now a lock whose failure window held ANY wrong emailed code
(`AccountGuess.mfaCode`, set by the MFA step alone; `State.codeFailed`) doubles per consecutive such lock: the n-th lasts `durationSeconds x 2^(n-1)`
(900 s, 1800, 3600, ... ), capped at `security.lockout.mfaMaxDurationSeconds` (`$LOGIN_LOCKOUT_MFA_MAX_DURATION_SECONDS`, default
86,400 = 24 h, never below `durationSeconds`, 1..30 days boot-validated). **The bound that results (with the pacing
counter of rule (6) and the one-step decay of rule (2)):** the climb to the top costs 7 locks (15 min ... 16 h, about 32 h in all,
35 guesses); after that every lock is the 24 h cap, and a guess round is either a lock (5 guesses, then 24 h) or a paced period (4
guesses, no lock, 24 h): the best mix is 5 + 4 guesses per 48 h, about 4.5 a day, about 1,650 a year against a 6-digit code (10^-6
each), roughly a 0.17 % chance per year, down from the roughly 16 % of the flat window (35,000 windows x 5 guesses). Waiting does
not buy the cheap bottom of the ladder back: the level loses one step per quiet day, so a guesser who waits a day and climbs again is
back at the top. Each guess round also costs a
fresh login and a sign-in code email to the owner and a `login.lockout` line with a rising `escalationLevel`. **A lock whose window held wrong passwords ONLY (`/login`, the password change) is ALWAYS the
flat `durationSeconds`**: a username alone must never buy a longer lock-out of the real owner, because a wrong code needs a live
challenge, which needs the password. **Rules:** (1) the level is the count of consecutive code-escalated locks and lives in
its OWN map with its own `ExpiringIndex` (`escalations`/`escalationExpiries`), because the lock entry is pruned the instant the lock
ends; (2) it **decays ONE STEP per full `security.lockout.mfaDecaySeconds`** (`$LOGIN_LOCKOUT_MFA_DECAY_SECONDS`, default 86,400 = 24 h,
1..30 days) without an escalated lock, counted from the END of the last one (computed lazily from that instant; the expiry slot is
`end + level x decay`, where the level reaches 0 and the entry goes), not straight to 0: dropping to 0 let a guesser wait one period and
climb the cheap bottom of the ladder again, about 22 guesses a day, roughly 0.8 % a year. It is dropped at once by a
confirmed password reset (below) or a success that clears the counter: the accepted code (the full login), and also a correct password for a non-MFA account or a
correct current password on the change, both of which prove a full sign-in or a live session; a success REFUSED by an active lock
clears nothing; (3) a password-only window neither escalates NOR resets the level (decided: no reset, no escalation: otherwise the
holder of an MFA-escalated account could reset the ladder with five wrong passwords), and a failure that meets an active lock
(`ALREADY_LOCKED`) changes nothing; (4) **the window decides, not the tripping failure**: the counter remembers whether ANY of its
failures was a wrong code (`State.codeFailed`, dropped with the counter: when it trips, expires or a success clears it), and a
window with one escalates whatever failure then trips it. Otherwise a password holder would send four wrong codes and trip the lock
with a wrong password at `/login`, staying on the flat 15 minutes forever. This is safe for the owner: a wrong code needs a live
challenge, so someone who knows only the username cannot START an escalating window. They can CONTINUE one the owner left open with a
single code typo, but only inside that typo's 15-minute window and for at most one level per owner typo; (5) **memory**: the escalation map and the code
counter of rule (6) are written only by `recordFailure(mfaCode = true)`, which requires a proved user id (the MFA challenge's
subject), so their key space is the user table (one FK-bound row per durable user, moved to the current canonical-email digest),
and neither counts against
`maxTracked`: the capacity rule for unknown identities is unchanged; (6) **pacing**: the shared counter's window is only the flat
15 minutes, so a guesser who stays one short of the threshold and waits would never trip a lock. A SEPARATE per-account counter of
wrong CODES only (`codeGuesses`) closes that: it lives `mfaDecaySeconds` from the FIRST code failure of its period (a fixed window,
deliberately not sliding: a sliding one would let two typos a day apart keep it alive for ever), and at `threshold` it trips an
escalated lock whatever the shared counter says, then starts over. So pacing buys at most `threshold - 1` guesses per decay period
before an escalating lock. A success, a confirmed reset (`clear`) and an escalated lock drop it. The shared counter is NOT given a
longer life: with that, an owner's single typo would let a username-only attacker escalate them with wrong passwords for 24 h. The owner
cost: five mistyped codes within 24 h (not within 15 minutes) earn the first, 15-minute escalated lock; (7) **stale challenges**: a
challenge minted before a password change or a confirmed reset is dead ("invalidates any pending MFA challenge"): its wrong
codes are NOT counted (`MfaRoutes.challengeStillCurrent`, one primary-key read per wrong code; audited `login.mfa_failure`
`reason=credential_changed`, the challenge dropped), so an old challenge cannot re-lock the owner right after a reset lifted the lock. The level counter is clamped (32) and the doubling is step-wise against the cap,
so nothing overflows. **Nothing new is disclosed:** the 429 is the same `accountLockedException` for every cause and carries no
`Retry-After` today either; only the audit line carries the length and level. **Cost, accepted:** a real owner who mistypes codes
repeatedly can sit locked out for up to 24 h; the ways out are the lock's end and **a confirmed
password reset (the owner's 2026-11-20 decision: it lifts the lock AND the level)**: the reset link goes to the same mailbox as the
sign-in codes, so completing it proves the very factor a code guesser lacks (`LoginThrottle.clear`, keyed on the account's email,
called within `PasswordResetService.complete` so it commits or rolls back with the password replacement, keyed on the user id and
email verified under the user-row lock, `PasswordResetService.Completion`; the audit says so with `lockoutCleared`, below). Only
the CONFIRM does this: an unconfirmed `POST /password-reset` mails a link and clears nothing, so asking for mail cannot lift a lock;
`login.lockout` with `escalationLevel >= 2` is the operator's signal that a password
holder is guessing codes.
Audit: `login.lockout` (now with `lockSeconds` and `escalationLevel`, below)/`login.rejected_locked` (with `userId` and `source` for the MFA and change
routes; on `/login` the identity is the `email` of an account that exists and the keyed `emailDigest` (HMAC under a key derived from the JWT secret, `observability.md`) of a guess at none, C-SEC-9), `login.capacity_rejected` (`/login` only), `password.change_rejected_locked` (`targetUserId`/`byUserId`). Tests: `AccountGuessTest` (the race branches), `PasswordChangeTest`, `MfaLockoutTest`, `LoginThrottleProgressiveTest` (the ladder, the flat password lock, reset, decay, capacity, overflow on an injected clock).


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
response; its failure (`password_reset.notification_failed`) cannot undo the reset. A confirmed reset **clears the
per-account lockout** (the counter, an active lock of ANY cause and the MFA escalation level; owner decision 2026-11-20, "Per-account login
lockout" above; the clear joins grant consumption, password replacement and credential revision in the reset transaction, so a
database failure rolls all of them back; the `password_reset.completed` line carries `lockoutCleared`). A lost 204 can make a retry answer `401`: sign in with the
chosen password or request another link.

Throttling: per submitted email - one request per `security.passwordReset.minIntervalSeconds` (default 60,
`$PASSWORD_RESET_MIN_INTERVAL_SECONDS`) - uniformly for existing and unknown addresses (`auth/PostgresPasswordResetThrottle.kt`,
shared through PostgreSQL), with the hard `security.passwordReset.maxTracked` limit (default 10,000,
`$PASSWORD_RESET_MAX_TRACKED`: elapsed cooldowns are reclaimed, a fresh one never evicted; saturation rejects a new identity with
an audited `429` before any task starts). Only a deployment-keyed digest under its own derivation label is stored, and PostgreSQL
time makes the interval consistent across instances and restarts. The per-IP `password-reset` bucket (`security.rateLimit.passwordResetPerMinute`,
`$PASSWORD_RESET_RATE_LIMIT_PER_MINUTE` - blank follows the mode: 5/min production, 100/min development); and the confirm
step's own per-IP `password-reset-confirm` bucket, 10/min in every mode (not configurable - the MFA idiom). `Cache-Control:
no-store` already rides every `/api/` JSON and problem+json answer (`plugins/Http.kt`, StatusPages' 429s included); the bodiless
`204` has nothing to cache. The development `log` mail transport prints the link: it must stay inaccessible outside a local
demo (production refuses it). Tests: `PasswordResetServiceTest` (injected-clock expiry, digest storage, the single-use race,
revision/email/delete staleness, the 5-grant cap, the revision-conditional write that lets only one of two competing writers win, and
a completed reset followed by an admin password write: both succeed, the admin's password is the final one), `PasswordResetConfirmationTest` (the confirm route, its own
bucket, the byte ceiling, MFA, notification failure, the `mail.appUrl` boot contract), `PasswordResetTest` (the request route and
the full link roundtrip incl. the session revocation), `PasswordResetThrottleTest`, `LocalizedEmailTest`.

**Shared HTTP rate limits (V58).** Application request lanes share their existing per-IP and per-user allowances through PostgreSQL.
The `ready` bucket stays on each process so local health probes keep separate allowances. A window starts at the first admitted
request and refills after 60 seconds on the database clock. The existing `X-RateLimit-*` and `Retry-After` headers describe the
shared window. IP keys use the same canonical IPv4 or IPv6-prefix selection as before and user keys come from a verified token.
The table stores only domain-separated deployment HMAC digests. It holds at most 100,000 bucket identities across every replica,
including expired rows awaiting the bounded background sweep. A first-seen identity at that physical cap atomically reclaims at most
one expired row; if none is available, it receives `429`. Existing identities continue to use their per-key allowance and no active row
is reclaimed. A database failure, including a missing capacity guard, follows the ordinary server error path.
Ktor's in-process limiter registry has one constant entry per shared bucket, not one entry per submitted identity: the suspending
request-weight hook performs the PostgreSQL decision once and the response hook publishes that decision's real limit, remaining,
reset and retry headers. The cached limiter is stateless; its process-clock refill is used only for Ktor's registry eviction schedule.

Rotate `JWT_SECRET` across app instances in the same maintenance window. The new key starts new quota identities.
Old quota rows keep their original deadlines, so a table at capacity may refuse new keys until their remaining windows expire.

**Per-IP login bucket** (`security.rateLimit.loginPerMinute`, `$LOGIN_RATE_LIMIT_PER_MINUTE`;
every bucket is installed by `plugins/RateLimits.kt`, which also owns the bucket names — today
`login`, `refresh`, `password-reset`, `password-reset-confirm`, `mfa`, `ready`, `live-connect` (below), and the per-USER `telemetry`, `directory`, `room-name`, `room-members`, `chat-post`, `chat-write`, `chat-read`, `read-state`, `thread-watch`, `notification-prefs`, `notification-read`, `live-ticket`, `search`, `link-preview` and `password-change` below): blank **follows the mode** — 10/min in production,
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
**`GET /api/v1/users/directory/{id}` (1.2 D7)** is mounted in the SAME `rateLimit` block, so one entry read and one list page spend the same 120 a minute (pinned by a mixed 60+60 run); the `rate_limit.exceeded` path then carries the id (an id, no secret). It is no new disclosure: the same three-field entry the list gives, an unknown and a removed id one `404`, so it is an active/inactive oracle over the id space exactly as the member-add `400` below already is (bounded by the bucket).

**`GET /api/v1/tag-categories` (1.2, V32) rides the `chat-read` bucket (1.2 branch audit, low):** the whole registry is one read of at most 100 categories of 100 tags of 40 characters, which is **about half a megabyte** at the caps (since the read is paged, that is the bound PER PAGE at most: a page of the default 20 categories is a fifth of it, and `pageSize=100`, the maximum, is the whole registry) (about 0.46 million characters of names, 50,000 of descriptions; a few KB is only what a registry of a dozen tags weighs), that any signed-in user may make; it names no person and no room, so it is no enumeration surface, but it is the largest document a read answers, so it spends from the per-USER `chat-read` bucket (300/min, below) with the thread and tag lists, before every check like they do (`TagCategoryRoutes.kt`). The SPA reads it a few times a minute at the very most (the picker, the filter panel, the Tags page, a focus refetch), far below the bucket, and a script at 5 a second is what it stops; it has no deadline of its own (two short statements). `TagCategory.kt` judges the tag COUNT before it normalises any name, so a body of thousands of names is refused before any of them is processed. Its ADMIN writes ride no bucket (a handful a day, serialised by the table lock whose wait is bounded to 3 s: `persistence.md` "Tag categories (V32)").

**What the user directory discloses, and to whom** (a deliberate widening of the ADMIN-only user list, the user's decision D2, 2026-11-17, who accepts name+email
disclosure to any signed-in user): to every signed-in user, the `id`, `name` and `email` of every ACTIVE account, searchable by name or email
(case- and accent-insensitive). Never roles (who is an ADMIN), feature flags, language, MFA state, timestamps or the password hash: `UserDirectoryEntry` is a
three-field projection built in the service, not the `User` row filtered afterwards, and `UserDirectoryTest` pins the exact key set. A soft-deleted account is
absent (its email is freed for reuse). `%` and `_` in `q` are literal (`containsNormalized` escapes them AFTER the unaccent fold, in SQL: unaccent maps the fullwidth `％ ＿ ＼` to ASCII, so an earlier escape would be undone; `ContainsPatternTest`). **Residual risk:** the email is the login identifier, so
a signed-in colleague who harvests emails can (a) aim failed sign-ins at a victim's account to trigger the per-account lockout, bounded by the per-IP `login` bucket
(10/min in production) and the lockout's own window (**one IP can nonetheless keep about 30 accounts locked at once, ADMINs included**: 10 failures a minute locks two accounts at the threshold of 5, each for 15 minutes, using ids and emails harvested from the directory; an administrator locked out this way is only freed by the window or a restart), and (b) request password-reset mail for a victim, bounded by the per-IP `password-reset` bucket and the
per-email reset throttle (`PasswordResetThrottleTest`). Neither reveals or changes a credential, and the reset link goes only to the account's own mailbox. Collapse
the projection to `{id, name}` if that trade changes (the user's option 2 of D2).

**A second surface: the team roster.** `GET /api/v1/teams/{id}` returns `members[]` (`{userId, name, email}` of up to 200 ACTIVE members) to any signed-in user, with NO
per-user bucket (a plain read like the rest of `/teams`). So the directory is not the only read of other users' name+email. The honest bound: the harvest is limited by the
number of teams (a team's roster is one request, the team list pages at most 100 per request), it reveals nothing beyond what D2 already accepts (name+email of an active
user), and it covers only users who sit on some roster. `GET /teams?memberId=` (1.2 D7) is the reverse lookup of the same roster rows (the teams a person is on): no new data, and no bucket either. Not bucketed on purpose; if the directory's projection shrinks to `{id, name}` (D2 option 2), the roster's `email` must shrink with it.

**A third surface: room members.** A room OWNER (any user who creates a room) can enrol ANY active user id through `POST /api/v1/rooms/{id}/members` and then read that user's `name` and `email` in
`GET /rooms/{id}/members` (a reader-only list of up to 500 members); and the add's `400` ("Unknown or deleted user ids: ...") tells the caller which of up to 200 submitted ids are inactive, an
active/inactive oracle over the id space. So the directory and the team roster are not the only reads of other users' name+email, and a user who owns a room can walk ids without typing a query.
The bound: the `room-members` bucket (60 adds/min per user, so at most 12,000 ids a minute, each add enrolling the person visibly in a room they did not choose, which is itself noticed, and
audited per person as `room.member_added`), the 200-ids-per-add and 500-members-per-room caps, and the 16 KiB body limit. It reveals nothing beyond what D2 accepts (name+email of an active user); the
inactive-id oracle reveals only that an id is not an active account. Collapse the member list's `email` with the directory's if D2 option 2 is ever taken. `GET /rooms?memberId=` (1.2 D7) is the reverse lookup of the rooms a person is in, ANDed with the caller's own readable rooms (a member sees only the rooms they SHARE, an ADMIN reads every room anyway): the member lists already show it, so no new data.

**Per-USER room buckets** (`RateLimits.ROOM_NAME` and `RateLimits.ROOM_MEMBERS`, fixed in every mode; the telemetry idiom: `configureRoomRoutes` and `configureRoomMemberRoutes` mount
`rateLimit(RateLimitName(...))` INSIDE `authenticate {}` around the routes, keyed by the verified user id). **`room-name`, 30/min,** is ONE bucket shared by `POST /api/v1/rooms` and
`PUT /api/v1/rooms/{id}`: the two writes that put a name into the room-name namespace (a room name is unique among non-deleted rooms, case-insensitively, and reserves it for everyone
else). The rename shares it because its `409` on a clash is exactly the create's, so an unbucketed rename would be a free "does a room with this name exist" probe; a clash on either is
also audited as `room.name_conflict` (byUserId, roomId for a rename, null for a create). **`room-members`, 60/min,** on `POST /api/v1/rooms/{id}/members`: the add's `400` names the inactive
ids it refused, so a manager could sweep ids through it; the bucket slows that to a trickle (S4 below). The rejection is the generic `429` problem plus the coalesced `rate_limit.exceeded`
audit event (bucket name, `byUserId`); a request refused by validation (`400`) or a guard still takes a token, because the bucket sits before the handler. `RoomTest` pins 30 then `429`
across create and rename, `RoomMembersTest` 60 then `429`. The remaining room-lock writes (archive, unarchive, delete, a member's role PUT and removal, the workflow PUT) ride `chat-write` since the 1.0.0 checkup (C-SEC-7, below); the room reads stay unbucketed on purpose: guard-gated (a caller sees only the rooms they belong to) and bounded by the caps in `chat-model.md`.

**`chat-post`, 120/min (`RateLimits.CHAT_POST`, 0.3.0 S2),** per signed-in user, fixed in every mode: ONE bucket shared by the two writes that put speech into a room, `POST /api/v1/rooms/{id}/threads`
and `POST .../threads/{number}/messages` (0.3.0 S5); `configureThreadRoutes` and `configureMessageRoutes` mount it INSIDE `authenticate {}` around the POST, the room-bucket idiom. A person types a few messages a minute, so
120 only stops a runaway client or a script flooding a room. The other thread and message writes ride `chat-write` (below). The bucket sits BEFORE the guards, so
a refused or invalid POST (a `400`, a `403`, an outsider's `404`) takes a token too; the rejection is the generic `429` problem plus the coalesced `rate_limit.exceeded` audit event (bucket
`chat-post`, `byUserId`). `ThreadWriteBoundsTest` pins 120 invalid thread POSTs, then the `429` and its audit line, with another account and the reads untouched; `MessageTest` pins that thread creation and message posts spend the SAME tokens. A thread title and a message body are stored raw and
rendered without HTML by the SPA (Markdown without raw HTML, images as links, headings as bold paragraphs, links `noopener noreferrer`; the CSP is unchanged); their bounds (title 200, body 8,000 UTF-16 units, no control or hidden direction characters) are `chat-model.md` "API", enforced by the route and again by the service (`sanitizeMultiLine`).
**`chat-write`, 240/min (`RateLimits.CHAT_WRITE`, the 0.3.0 security audit's M1),** per signed-in user, fixed in every mode: ONE bucket shared by the thread and message writes that are not a POST - the thread details PUT, the thread tags PUT (1.2, `PUT .../threads/{number}/tags`: a whole-set replace under the room lock, so it belongs here), the status PUT, the owner PUT, the message edit (PUT), the message DELETE and, since 0.6.0, the reaction PUT, the thread pin PUT and the message pin PUT and, since the 1.0.0 checkup (C-SEC-7: the six room-lock writes that had been left unbucketed as "guard-gated, one row each" - the checkup counted five; the room delete is the sixth - are reversed, for the reason below), the room archive, the room unarchive, the room delete, a member's role PUT, a member's removal (a leave included) and the room workflow PUT - mounted INSIDE `authenticate {}` around each, before every guard (a refused write spends a token too). Each of them takes the room's write lock (`rooms/RoomLock.kt`) and a pooled connection for as long as it waits, so before this bucket one member's script could queue unlimited writers behind a room and hold the pool's 20 connections (the second half of the fix is the bounded lock wait, below). 240 is twice `chat-post`: a person moves a status, hands a thread over, edits or deletes a message a few times a minute at the very most, and 4 a second is a script, not a hand; the rejection is the generic `429` problem plus the coalesced `rate_limit.exceeded` audit event (bucket `chat-write`, `byUserId`). `ChatWriteLimitsTest` pins 240 across the routes, then the `429`, its audit line, another account and the reads untouched; `PinsTest` pins that the two pin PUTs spend the same bucket; `ChatWriteLimitsTest` also pins the six room-lock writes (240 across them, then `429` on each). The room's name write (`room-name`) and the member add (`room-members`) keep their own buckets, so they are NOT on this list.

**`chat-read`, 300/min (`RateLimits.CHAT_READ`),** per signed-in user, fixed in every mode: ONE bucket shared by `GET /rooms/{id}/threads`, the room hashtag lists, the message revision list and `GET /tag-categories`. The thread list uses room-scoped JSON containment for field filters (V49); the hashtag list groups the room’s tag rows. Mounted INSIDE `authenticate {}`, before the visibility guard (a refused or invalid read spends a token). **Sized against the SPA's real rate, so a normal member is never refused:** a live hint refetches the room's thread list at most once per 3 s (`web/src/utils/liveKeys.ts`, `AGGREGATE_MIN_GAP_MS`) and the tag list rides the same prefix while the filter panel is open, so one tab in a room with a hint every few seconds makes 20 + 20 = 40 requests a minute; three tabs plus a person paging and filtering by hand stay under 200. 300 (5 a second) is above that with room to spare and still stops a script. The rejection is the generic `429` problem plus the coalesced `rate_limit.exceeded` audit event (bucket `chat-read`, `byUserId`); `ThreadReadBudgetTest` pins 300 across the three routes, the `429`, its audit line and another account untouched. **The other half is a deadline** (`threads.readTimeoutSeconds`, `$THREADS_READ_TIMEOUT_SECONDS`, default 5, boot-validated 1..30; `threads/ReadLimiter.kt`, the search's `SearchBudget` reused): each read runs in ONE transaction whose statements share it, and a cancelled statement (`57014`) or a spent deadline is the typed `429` `urn:adder:problem:read-timeout` with `Retry-After: 2`, audited `read.timeout` (`byUserId`, `roomId`, `resource` = `threads`, `tags` or `thread`; ids only, never a filter value) with the throwable neither logged nor attached. The type is its own (not `search-timeout`): a client that tells a user to narrow a SEARCH must not say it of a list, and one type serves the three reads. **And the reads are bounded in flight, as the search is** (`ReadLimiter`, `threads/ReadLimiter.kt`): the bucket starts full, so without a cap 300 slow reads in one second could still hold all 20 pool connections. At most 8 reads per user run at once (a ref-counted map whose entry is removed at 0, so it never grows past the users with a read in flight; 8 is four tabs, each refetching the list and the tags in the same instant after a live hint's fixed 300 ms settle; the same hint also makes ONE thread-detail read per tab, so THREE reads per tab in all, but the detail has its own pool, below) and at most `threads.readMaxConcurrent` in the process (`$THREADS_READ_MAX_CONCURRENT`, default 8, boot-validated 1..16, shared by the bounded list reads, including the room hashtag list and message revisions. With the search, notice and thread-detail caps, readers can hold 19 of the default pool's 23 connections). Both slots are polled `tryAcquire`s under ONE bounded wait of about 1 s, so a legitimate burst after a live hint queues briefly instead of failing; a PRODUCTION boot refuses a configuration where `threads.readMaxConcurrent + search.maxConcurrent` plus the notification list's own cap of 4 (below) plus the thread detail's own cap of 3 (below) leaves fewer than four connections in `postgres.pool.maxSize` for writes, authentication, and other work (`requireReadersUnderPool`, `infra/db/Database.kt`; development mode skips the check, so a small test pool boots); past either the read is refused as `429` of type `urn:adder:problem:read-busy` with `Retry-After: 1`, audited `read.busy` (`byUserId`, `roomId`, `resource`, `limit` = `user` or `process`, `suppressed`; ids only), BEFORE a pool connection is asked for, and both permits are released in a `finally` (also after a timeout). The lists therefore hold at most 8 connections (the thread detail 3 and the notice list 4, each its own pool) for at most the 5 s budget. **The thread DETAIL read (`GET /rooms/{id}/threads/{number}`, 1.2 branch audit, medium) has its OWN `ReadLimiter` instance (resource `thread`) and is NOT in the bucket.** It had neither before: it sat outside `chat-read` and ran with no deadline, and its hashtag probe could read every index entry of a thread with few distinct tags and many rows (the probe is now a loose index scan of at most 20 descents, `persistence.md` "Hashtags" V31). It now runs under ONE deadline for its statements (`threads.readTimeoutSeconds`, `429` `read-timeout`) and its own in-flight caps (2 a user, `THREAD_READS_PER_USER`, and 3 in the process, `THREAD_READ_MAX_CONCURRENT`, no config key, `429` `read-busy`), NOT shared with the lists: an unbucketed loop of detail reads can fill its own pool of 3 and no more, so it never refuses a bucketed list read (`ThreadReadBudgetTest` pins both ways). Its `read.busy` and `read.timeout` lines are coalesced to one per user and minute with a `suppressed` count (the bucketed lists' are not: the bucket bounds their rate). A user holds 2 of the 3, so no one account takes the pool; a third tab of one user queues for the 1 s wait (a read takes milliseconds) instead of failing. **Why not the bucket, judged:** a live hint re-reads the open thread's detail within the 300 ms settle with NO spacing (only the lists are spaced, `liveKeys.ts`), so in a busy thread one tab reads it up to a few times a second and a second tab doubles that: 300 a minute shared with the lists would 429 an ordinary open thread in exactly the incident the product is for, and each refused read would be a visible failure. The deadline and the caps bound what the reads can cost (a few milliseconds each, at most 2 a user and 3 in the process at once): a script polling the detail holds a user's two slots and is refused as `read-busy`, never a pool connection beyond them. The SPA retries a read refused as `read-busy` or `read-timeout` ONCE, after `Retry-After` (capped at 5 s, plus a jitter; `api/http.ts` `shouldRetryQuery`, `queryRetryDelay`), so a thread opened under load does not show a failure for a moment the server itself calls short. `readCreated` (the create's own `201` body) bypasses the limiter: a write that committed must not answer `429`.

**`read-state`, 240/min (`RateLimits.READ_STATE`, 0.6.0),** per signed-in user, fixed in every mode: ONE bucket shared by the writes that only move the caller's OWN view of what they have read: `POST /notifications/{id}/seen` and `POST /notifications/seen-all` (and `PUT .../threads/{number}/read-marker` and `POST /rooms/{id}/seen`, V19, and `POST /rooms/{id}/read-all`, 1.2: `threads/ThreadReadRoutes.kt` and `rooms/RoomMemberRoutes.kt` mount it the same way, INSIDE `authenticate {}` and BEFORE every guard; the read marker's body is held to the 16 KiB small-body ceiling). They take no room lock and emit no hint, so the bound is for a runaway client or a script sweeping notification ids through the `404`, not for the room. `notifications/NotificationRoutes.kt` mounts it INSIDE `authenticate {}` and BEFORE every other check (an unknown id spends a token). 240 is the `chat-write` figure: the SPA marks a notice seen on a click and acknowledges what its reader reached a few times a minute. The `429` is the generic `rate_limit.exceeded` line (`bucket=read-state`, `byUserId`, coalesced). `NotificationsTest`. **"Mark all as read" (`POST /rooms/{id}/read-all`) spends ONE token and is bounded in work, in rate and in concurrency:** one short update of the caller's own member row, then the caller's own unseen notices of that room in chunks of 1,000, each its own short KEYSET transaction (`id >` the previous chunk's last id, the rows locked in id order) through `idx_notifications_unseen_room` (V34), so no statement is longer than a chunk (about 5.5 ms), the total is linear in the pile and none re-reads another's entries, however much a person has piled up (`BACKLOG.md` "bound what one recipient can pile up"), and neither the room lock nor the member row is held while it works. It has no body, moves no `thread_reads` row and no one else's state, so a request costs the same for a room of three threads and of three thousand. **One call per person at a time:** a second while the first runs is `429` `read-busy` with `Retry-After: 1` (audited `read.busy`, `resource=read-all`, coalesced), so 240 queued calls can never hold pooled connections (in-process: it relies on the one-replica rule). It is audited (`room.read_all`) **by what it did, not by how often it was called** (1.2 branch audit, low): one line for a call that settled at least one notice, one with `complete=false` for a call that failed or was cancelled after the floor was raised, none for a call that found nothing to settle (the bucket allows 240 calls a minute, so a no-op line per call was a 240-a-minute stream of identical lines), and the `read.busy` of the refused second call is coalesced to one line per person and minute (`suppressed` counts the rest; `observability.md`). The `unread` list filter rides the `chat-read` bucket and the list's own deadline like every other filter.

**`thread-watch`, 30/min (`RateLimits.THREAD_WATCH`, 1.1.0),** per signed-in user, fixed in every mode, shared by `PUT .../threads/{number}/watch` and `PUT .../threads/{number}/mute` (`threads/ThreadWatchRoutes.kt` and `threads/ThreadMuteRoutes.kt`; mounted INSIDE `authenticate {}` and BEFORE every guard, so a refused write spends a token; body held to the 16 KiB small-body ceiling). It is not the `read-state` bucket because, unlike the read-state writes, a watch IS audited (`thread.watch_changed`, one line per change): a toggling client must not flood the audit log, and its toggles must not spend the read marker's budget. A person watches a few threads an hour, so 30 a minute is a script. No room lock, no hint. The `429` is the generic `rate_limit.exceeded` line (`bucket=thread-watch`, `byUserId`, coalesced). `ThreadWatchTest`.

**`notification-prefs`, 30/min (`RateLimits.NOTIFICATION_PREFS`, 1.1.0),** per signed-in user, fixed in every mode, its own bucket for `PUT /users/{id}/notification-preferences` (`notifications/NotificationPreferenceRoutes.kt`; INSIDE `authenticate {}` and BEFORE every guard; body held to the 16 KiB small-body ceiling). The PUT replaces up to 24 rows under a `FOR NO KEY UPDATE` lock on the user row (a few multi-row statements, `insertRows`' binary split) and is audited, so it neither shares `read-state` (spent by clicks) nor needs more than a script's rate; an ADMIN acting for another person spends the ADMIN's own bucket. The `429` is the generic `rate_limit.exceeded` line (`bucket=notification-prefs`, `byUserId`, coalesced). `NotificationPreferencesTest`.

**`notification-read`, 240/min (`RateLimits.NOTIFICATION_READ`, 1.1.0),** per signed-in user, fixed in every mode, for `GET /notifications` (INSIDE `authenticate {}`, before the paging checks). Every `notification.created` hint makes each open tab re-read the list up to three times (the badge, the addressed count and, with the drawer open, its page), so a few busy tabs stay far below 4 a second. **Headroom with reply notices flowing (1.1.0 S6, judged):** the tab's live invalidator coalesces every notices key into ONE flush per 3 seconds (`SPACED_MIN_GAP_MS`, plus the 300 ms settle), so a tab makes at most 20 flushes a minute however many hints arrive, i.e. 40 reads a minute (60 with the drawer open); the bucket is per USER across tabs, so 240 holds for four tabs under a notice every few seconds, which is far beyond a normal person (a watcher of a thread with 20 replies a minute is the heavy case, and the thread they are reading adds none: a notice about the open thread is acknowledged by the read marker instead of re-read, `web-internals.md`). A fifth busy tab, or a read-marker re-read on top, can meet the `429`, which the SPA treats as a pause (one re-read after `Retry-After`, rows shown stay), so the bucket is NOT raised. The fan-out of ONE wide event (up to 500 recipients, one tab each) is at most about 1,500 reads, which the spread below keeps from arriving at the same instant and the notice limiter absorbs (4 in flight in the process; the rest answer `read-busy` with `Retry-After` and re-read once): a brief wave of `429`s, never a failed write, and why the wide-event budget exists; to keep that wave short the SPA spreads the `notification.created` re-read over a random 0 to 2 s like a `resync` (`SPREAD_MS` in `web/src/utils/liveKeys.ts`), a refused read is retried once after `Retry-After` plus a random 0 to 2 s with a capped exponential backoff for refusals in a row (`useNotificationRetryAfter`: no lock-step retries), and the server audits the refusal of the notice list once per user and minute (`read.busy`, `observability.md`). The read also runs through a `ReadLimiter` OF ITS OWN (`ChatWiring`; resource `notifications`): the same per-request deadline (`threads.readTimeoutSeconds`, `read-timeout`) but a fixed cap of 4 reads in the process and 2 per user (`NOTIFICATION_READ_MAX_CONCURRENT`, `NOTIFICATION_READS_PER_USER`; `read-busy`), so a flood of notice re-reads cannot take the thread lists' share of the pool nor the reverse, and ONE account (any number of tabs or scripts) can never hold every one of the 4 slots and starve the other readers (the 1.1.0 branch audit, L1; the third read of a tab's burst queues for the 1 s permit wait behind two that answer in milliseconds); the boot check counts the 4 with the thread-list and search caps. Its audit lines (`read.timeout`, `read.busy`) carry no `roomId`. The SPA treats a `429` as a pause (one re-read after `Retry-After`; rows already shown stay). `NotificationsTest`.

**Watching and notices (a rule for the steps that notify).** A watch is a person's own state, never a licence to be told about a room they have left: the recipients of a notice for a watched thread are `ThreadWatchService.recipientIds`: ONE joined statement (`thread_watches` joined to `room_members` on the composite key and to ACTIVE `users`), inside the write, never a raw `thread_watches` read (there is no other reader of the table for an audience: the old unjoined `watcherIds` was deleted) (the membership key already drops a leaver's watches, the intersection is the second line). The cross-room "Threads I watch" view (1.1.0 S4) starts from the caller's own rooms (`RoomService.memberRooms`), never from `thread_watches` alone, so a stale row can reach nothing outside a room the caller can read. A watch row's room is the THREAD's own (`ADD_WATCH_SQL` reads `t.room_id`), never a client or caller argument.

**Notices (`/api/v1/notifications`, V18, 0.6.0; `chat-model.md` "Notifications (V18)").** The surface is the caller's OWN rows, so it needs no guard and has nothing to disclose; what it must never do is show a room to someone who is not in it. (1) **A notice is ids only**: no name, title or body is stored (Lettuce's row kept `params` and a `link`), so none can leak after a leave or a moderator's delete, none needs scrubbing, and none can reach a log; the room's name, the thread's title and the actor's name are read at request time, in the list's own transaction, for the page only. (2) **The leak rule is in the schema**: `(room_id, recipient_id)` references `room_members ON DELETE CASCADE`, so removing a member deletes that person's notices of the room and a notice for a non-member cannot be inserted (23503); the list also keeps only the caller's ACTIVE rooms, so a deleted room's notices are hidden and an ADMIN who is not a member has none. (3) **Flooding is bounded by what already bounds the write**: at most 20 distinct people, 5 distinct teams and each keyword once count per message, and since 1.3 a quote adds at most ONE more directly addressed person, the author of the quoted message (never the poster, and only while they are an active member of the room: the same `activeMemberIds` statement that judges a mention, so a quote cannot be used to make a notice for a non-member or to name anyone outside the thread, and the author is one person, never a roster; the kind is `DIRECT`, so a refused wide event keeps it, and it rides the same `chat-post` bucket) (1.1: a group mention reaches only the team's active roster intersected with the room's active members, the thread's watchers or the room, all read inside the write; an edit tells only the NEWLY addressed, the old body's audience read as of the edit and a person it now names directly always newly, counts as a wide event when it adds more than 20 people or relabels more than 20 bells (a refused relabel still happens, with no hint, and `notification.created` carries `relabelled`), and deletes an upgraded notice only when its replacement is made) and a notice is made inside a `chat-post` or `chat-write` write (120 and 240 a minute per user, the room lock), the insert is ONE multi-row `INSERT ... SELECT FROM unnest(...) ON CONFLICT DO NOTHING RETURNING recipient_id, id, notification_type` with constant text (one statement under the lock whatever the recipient count; V27: one notice per message and recipient, `in_app`), the recipients' switches are ONE more constant statement (`chat-model.md` "Notice kinds (V27, V28)"), and the live hint is ONE per write to the targets whose notice shows in the app (at most `MAX_ROOM_MEMBERS` = 500 since S6: a new thread tells the 499 other members, a reply its watchers, and an ADMIN who is no member closing a thread all 500 members watch tells 500; **the wide-event budget bounds how OFTEN**: an event that would notify more than 20 people counts against the actor's `notifications.maxBroadcastsPerActorPerDay` (`$NOTIFICATIONS_MAX_BROADCASTS_PER_ACTOR_PER_DAY`, 500, boot-validated 1..10000) wide events per UTC day, kept on the actor's V24 row `notification_email_budget.broadcasts` by one atomic `UPDATE ... WHERE broadcasts < N`; over it the write STILL succeeds and only the people named directly (a `<@id>` mention, a hand-over's new owner) are told, one `notification.broadcast_budget_exceeded` audit line per actor and day; without it 120 posts a minute to 499 people would be about 86 million rows a day for one account; `NotificationBroadcastBudgetTest`). A person who switched a kind off in the app AND by email has no row at all. **A recipient's rows are not capped, so their READS are bounded:** the list runs under the per-user `notification-read` bucket (240 a minute), the notice list's own `ReadLimiter` (the deadline of `threads.readTimeoutSeconds`, 4 in flight in the process and 2 per user), and every filter and sort has an index that leads with the person: `type` by `idx_notifications_recipient_type`, `sort=createdAt` by `idx_notifications_recipient_created`, `seen=true` by `idx_notifications_seen` (V27, plan tests in `ThreadSchemaTest`; without the last, the capped count of a person with a long unseen history and few seen rows would walk the whole history to find its 1,001 matches); its count reads at most 1,001 rows and the API answers `total` capped at 1000 (`MAX_COUNTED_NOTICES`), so no read grows with a person's history, a count of 0 reads no page, and a page that starts at row 1000 or later (`(page - 1) * pageSize >= 1000`) is answered EMPTY without the OFFSET running (what is NOT bounded is the number of rows a recipient accumulates, 90 days of them: the per-recipient unseen cap is in `BACKLOG.md`; but a repeated THREAD-LEVEL notice does not pile up: a status move, a close, a reopen and a hand-over that finds the recipient's unseen row of the same kind about the same thread REFRESH it, so toggling a status ten times leaves each watcher one unseen row per kind - an index-backed lookup, `idx_notifications_unseen_thread`, V19; replies and mentions stay one row per MESSAGE and person, so a flood of messages still makes rows and is bounded by `chat-post`/`chat-write` and the wide-event budget only); `seen-all` works in chunks of 1,000 rows, each its own short transaction (120 mention-posts a minute could make about 172,800 rows a day for one target; retention is what removes them). (4) **The live hint goes to its targets only** (`Audience.TARGETS`): never to a subscriber of the room. (5) Rows are deleted with their message, with the membership and 90 days after they were made (`notifications.retentionDays`, 1..3650; an hourly sweep in its own scope, never inside a chat write). **The purge is batched**, 1,000 rows per short transaction with `FOR UPDATE SKIP LOCKED`, read in `created_at` order off `idx_notifications_created`: one big `DELETE` would hold its locks while a member removal or a message delete waited, under the room lock, into `room-busy`. (6) The kind is a persisted enum name with no CHECK: a name this build does not know is invisible, never fatal. The channel switches are stored as DEVIATIONS from defaults in code (`NotificationType.defaultOn`: the app on, email OFF for every kind): a person with no stored row is never mailed, and the PUT's `disabled`-only body of a 1.0.0 client cannot switch an email on (1.1 A28). (7) **The per-actor digest budget (V24, 1.0.0 checkup C-SEC-7, the user's decision "per-actor mail budget").** A room owner can add any active user to a room (`room-members`, 60 adds/min) and then @mention or hand a thread to them, and the digest would mail that person every interval naming a room of the owner's choosing. So one ACTOR can make at most `notifications.email.maxRecipientsPerActorPerDay` (`$NOTIFICATIONS_EMAIL_MAX_RECIPIENTS_PER_ACTOR_PER_DAY`, default 20, boot-validated 1..1000) DISTINCT people eligible for the digest per UTC day, across every kind of notice together, rooms together and an ADMIN actor included (no exemption: an ADMIN can enrol anyone too); from 1.1.0 the budget counts only recipients whose email is ON for the kind, in the order the caller listed them (the most specific reason first). The first N people reached stay eligible for the rest of the day (the same person counts once, however many notices); a later person's email is withheld: their notice is stamped `emailed_at` at insert, V22's "settled, never mailed", so no claim or settle ever sends it, and it is made at all only if their in-app switch is on (a person reached by neither channel gets no row). The state is `notification_email_budget` (one row per actor and UTC day: the admitted ids, bounded by the config, and `exceeded_at`), locked `FOR UPDATE` inside the write's transaction, so concurrent writes of one actor in several rooms cannot overshoot, and independent of the notices (removing and re-adding members does not reset it). The sweep's retention pass deletes budget days older than the notice retention. The first overrun of an actor's day is audited `notification.email_budget_exceeded` (`observability.md`). **What it limits: WHO can be mailed, not how often.** The admitted recipients (up to N a day per actor) can still get a digest every interval (`minIntervalSeconds`, 900 s) for as long as unseen notices keep arriving; their remedy is the per-kind email switch (`PUT /users/{id}/notification-preferences`). A per-(actor, recipient) limit is the next step if that proves insufficient. The budget row is locked `FOR UPDATE` after the room lock with no `lock_timeout` (`persistence.md` "The room lock"). `NotificationEmailBudgetTest` pins under, over, distinct counting, the day rollover on an injected clock, concurrency, that leaving and being re-added does not reset it, and the route-level wiring; `NumericConfigBoundsTest` the boot validation.

**`password-change`, 5/min (`RateLimits.PASSWORD_CHANGE`, C-SEC-2),** per signed-in user, fixed in every mode (the telemetry idiom, no config key): `PUT /api/v1/users/{id}/password`, mounted INSIDE `authenticate {}` by `configureUserRoutes`. The self change runs a bcrypt verify of `currentPassword` per request, so the bucket stops a stolen session guessing at dispatcher speed and any user occupying the small default dispatcher; the per-account lockout above is the actual guessing bound. The bucket sits BEFORE the guards (a refused or invalid change spends a token; it is also an admin's budget for resetting others, a few a minute at the very most). Rejection: the generic `429` problem plus the coalesced `rate_limit.exceeded` (bucket `password-change`, `byUserId`). Pinned by `PasswordChangeTest`. Successful self-service and administrator password changes send a localized, preference-independent security receipt after commit if mail delivery is enabled. Delivery failures are best-effort and recorded as `password.change_notification_failed` with only the user id and error type.

**`live-ticket`, 60/min (`RateLimits.LIVE_TICKET`, 0.4.0 S3),** per signed-in user, fixed in every mode: `POST /api/v1/live/tickets`, the exchange of a bearer for a single-use socket ticket (`live.md` "Tickets"). `configureLive` mounts it INSIDE `authenticate {}` (the telemetry idiom), so the key is the verified user id; a tab mints one ticket per (re)connect, so 60 a minute stops a reconnect loop or a script long before it can fill the store. The rejection is the generic `429` problem plus the coalesced `rate_limit.exceeded` audit event (bucket `live-ticket`, `byUserId`). `LiveTicketTest` pins 60 then `429`, its audit line and another account untouched.

**Socket tickets** (`live/LiveTickets.kt`, `LiveTicketPostgresStore.kt`, `live.md` "Tickets"). The ticket is 32 `SecureRandom` bytes
(43 base64url characters) bound to the requesting access token's user, role, JTI, credential revision and deadline. V53 stores only
its SHA-256 in PostgreSQL. Every process sharing that database can redeem it. An atomic deletion allows one redemption, and
unknown, used and expired tickets share the same answer. PostgreSQL sets the deadline to the earlier of 30 seconds and the
access token's expiry. The socket verifies the bound account and token before and after registration. A ticket does not grant room
access. Subscriber permissions are checked when subscribing.

The ticket travels in the first WebSocket frame (API-AUTH-002) and never appears in a URL or log (`LiveTicketTest`, `SecretToStringTest`).
The shared store limit is `live.maxTickets` (`$LIVE_MAX_TICKETS`, default 10,000, boot-validated 1–100,000). Mints prune a bounded
batch of expired rows and reject at capacity with `429`, `Retry-After: 30` and the coalesced `live.ticket_saturated` audit event.
A database failure yields `503` on mint or `1013` on redemption. The supplied deployment remains single-replica while other
admission state is still held by individual processes.

**The live socket** (`GET /api/v1/live`, 0.4.0 S4; `live.md`). The route is public at the HTTP layer (`security: []`) and authenticates IN-BAND: the first frame is `{"type":"auth","ticket":...}` within `live.authTimeoutMillis` (2 s, else close 4401). What stands between an anonymous client and a held connection, in the order a request meets it:
(1) the per-IP **`live-connect`** bucket, 600/min (`security.rateLimit.liveConnectPerMinute`, a number pins it; `429` + `Retry-After`, the coalesced `rate_limit.exceeded`), which really is FIRST: admission intercepts in the `Call` phase, after the route's `rateLimit`, so a refused request spends a token too (the 0.4.0 audit found it intercepting before the bucket, so refusals were never throttled; `LiveAdmissionTest`); (2) the **Origin rule** (the CSWSH defence): the header is REQUIRED, its scheme `http` or `https`, and its scheme and authority must equal the request's own scheme, host and port (what Ktor resolves, so `http.behindProxy` rewrites all three: `X-Forwarded-Proto`, `X-Forwarded-Host` with its port) or be listed in `http.corsHosts`; anything else, a missing header and `null` included, is a `403` and an audited `live.refused` `reason=origin` with a `detail` (`authority` | `scheme` | `host`), coalesced per origin host (`LiveAdmissionTest` pins the matcher table). The `Host` header is client text: an unreadable one (a non-numeric port, a bare IPv6 literal) is the same refusal, never a 500;
(3) a stopping server, `live.maxSockets` authenticated sockets, `live.maxPendingPerAddress` (8) unauthenticated ones from the caller's address (an IPv6 address counted by its /64, `live/LivePending.kt`) or `live.maxPendingAuth` (256) in total is a `503` `urn:adder:problem:live-unavailable` with `Retry-After` (the caps are advisory at admission, authoritative in the hub); (4) a plain non-upgrade GET passes the same gates and answers `400` (403 before 400). An unauthenticated socket lives at most 2 s and is counted, in total and per client address (`LiveHub.pending`, claimed atomically when the session starts; one host cannot hold the global cap); the first frame is parsed STRICTLY (8 KiB max, Ktor's 1009 beyond), and a frame before `auth`, a second `auth`, malformed JSON or an unknown type is 1008, a binary frame 1003 (each an audited, coalesced `live.refused` `reason=bad-frame`). **Per-socket bounds after authentication (S5):** more than 30 inbound frames in 10 s (a sliding window over the messages the reading loop receives, an application `ping` included; WebSocket protocol ping, pong and continuation frames never reach it, and Ktor reassembles a fragmented text message first, so the 8 KiB limit applies to the total. Checked BEFORE the parse so a flood costs a counter, not a database transaction) is 1008 and an audited `live.refused` `reason=inbound-rate` (`LiveInboundRateTest`, `LiveBoundsTest`); a repeated `subscribe` for an unreadable room is one coalesced `live.subscribe_denied` line a minute; the socket closes 4001 at its access token's `exp` and 4003 within 60 s of a logout, deletion, password change or role change (the sweep re-judges every open socket with the SAME decision as the per-request check, `TokenBlocklistService.accessVerdicts`; `AccessVerdictsTest`); the same pass re-checks non-ADMIN memberships (a hint lost in a listener gap cannot leave a removed member subscribed for more than 60 s; audited `live.revoked reason=membership`); the two halves run independently and a failed half is retried once after 5 s, so a failing query never leaves the other undone for a minute; a listener that has been down for more than 10 s is a `503` on the mint and a 1013 close of every open socket (a deaf channel is not left silent); and a new subscribe reads the stored role of the active user (a demoted or deleted ADMIN gains no new room).
Redemption re-judges the token (`accessVerdict`: a logout, delete, password change or role change between the mint and the redeem closes 4003), refuses past the token's `exp` (4001) and enforces `live.maxSocketsPerUser` (4429). A subscribe is judged per room by `RoomService.readableRoomIds` (members' rooms; an ADMIN every existing room): the answer deliberately mixes "no such room" with "not readable" (`refused`), and only EXISTING unreadable rooms are audited (`live.subscribe_denied`). No credential is ever in the URL; `Authorization` never reaches the socket; the ticket reaches no log line (`LiveSocketTest`, `LiveFramesTest`).
**With `http.corsHosts` set** the CORS plugin, installed application-wide, refuses a foreign `Origin` BEFORE the route (a bodiless `403`, no `live.refused` line: the same for every route; still a refusal, only the audit trail and the problem body are missing); the plugin has no per-route exemption, so the live route is not carved out. `RequestBodyLimit` is told the upgrade GET has no body (`plugins/Http.kt` `requestBodyLimit`: `Long.MAX_VALUE`, the plugin's "no limit"), because wrapping the request channel starves the server of every client frame in the test host. The CSP is unchanged (`connect-src 'self'` already matches the same host's `ws:`/`wss:`); adding scheme sources would allow sockets to ANY host.

**The room lock wait is bounded (M1).** Every room write runs under `withRoomWrite`, which takes the room row `FOR NO KEY UPDATE`; a queued writer holds a pooled connection while it waits, so the wait is capped by `SET LOCAL lock_timeout` (`rooms.lockWaitMillis`, `$ROOM_LOCK_WAIT_MILLIS`, default 3000, boot-validated 100..30000 through `requireConfigInt`). A wait that outruns it (SQLSTATE `55P03`) is `429` of type `urn:adder:problem:room-busy` with `Retry-After: 1`, audited `room.busy` (`byUserId`, `roomId`; not coalesced: each rejection already cost the caller the whole wait, and a pool of 20 bounds the rate, and the buckets above bound the rest). 429 because it is a cross-cutting status on every operation, and the right word: retry. Nothing was written. The setting is transaction-local and put back to the session default the moment the lock is held, so it cannot leak to the next borrower of a pooled connection and no other wait of the write (a unique-index wait, an FK key share) runs under it; it is a different setting from the statement timeout, so the "only `queryTimeout`" rule (`persistence.md` "Connection pool") is untouched. `RoomBusyTest` holds the lock in a raw transaction and pins the answer at the bound, the audit line, the clean one-connection pool and the boot validation.

**Search (`GET /api/v1/rooms/{id}/search` and `GET /api/v1/search`, V16, 0.5.0; `chat-model.md` "Search (V16)").** The surface that reads message text in bulk, so it is bounded four ways. (1) **`search`, 60/min (`RateLimits.SEARCH`),** per signed-in user, fixed in every mode, mounted INSIDE `authenticate {}` around the route and BEFORE the guards (a refused or invalid search spends a token, which also throttles a probe of foreign room ids through the 404); the rejection is the generic `429` problem plus the coalesced `rate_limit.exceeded` audit event (bucket `search`, `byUserId`). (2) **ONE deadline per request** (`search.timeoutSeconds`, `$SEARCH_TIMEOUT_SECONDS`, default 5, boot-validated 1..30; `threads/SearchBudget.kt`): each statement's `queryTimeout` (the only way a statement timeout may be set, `persistence.md`) is the whole seconds LEFT of the request's budget, so the sum of the statements is bounded, not just each one (a request ends within the budget plus at most one second of rounding); a cancelled statement (`57014`) or a deadline already spent between two statements is answered `429` of type `urn:adder:problem:search-timeout` with `Retry-After: 2`, audited `search.timeout` (`byUserId`, `roomId`; ids only), and the throwable is not logged. (3) **A process-wide concurrency cap** (`search.maxConcurrent`, `$SEARCH_MAX_CONCURRENT`, default 4, boot-validated 1..16, well under the pool's default of 23): a `Semaphore` `tryAcquire` BEFORE a pool connection is asked for, both routes together; a search past it is refused at once, never queued, as `429` of type `urn:adder:problem:search-busy` with `Retry-After: 1`, audited `search.busy` (`byUserId`, `roomId`; ids only). A flood of searches can therefore hold at most that many connections, and never starves the rest of the app. (4) **Page size 20, plain `q` up to 100 code points, and structured `q` up to 512 code points and 24 clauses**; the room is the first predicate, so a search never scans another room. **The watched view (1.1: `watching=true`, no `q`) is the cheapest search**: no text is matched and no message is read (the thread rows of the caller's own rooms, narrowed by a probe of their watches through `idx_thread_watches_user_room`), so it needs no new bound; it takes the same bucket, deadline and concurrency permit, and `q` is optional ONLY there and in **the tag view (1.2: a `threadTag`, no `q`)**, a given `q` being validated as ever. The tag view is as cheap and for the same reason: no text is matched and no message is read, the caller's own rooms are restricted first (`thread_tags` has no room column, so a tag alone never widens the scope) and their thread rows are narrowed by a correlated scalar probe of the `thread_tags` primary key per thread (about 0.9 ms for the count and the page on 30,000 threads and a caller in 40 rooms, `persistence.md` "Search (V16)"); it adds one name-to-id read of `tags` and, for the page, one `thread_tags` read, under the same bucket, ONE deadline and permit. **Timing, 1.2 branch audit (low), fixed rather than accepted:** the tag side used to be read whole (an `EXISTS` flattened into a hash join from `idx_thread_tags_tag`), so the request's time followed the tag's GLOBAL usage, in rooms the caller cannot see (15 ms against 0.08 ms for a popular and a rare tag), and an unknown name returned before any thread statement, so "never a tag" and "retired" differed in time. Forcing the per-thread probe scoped to the caller's rooms, as the text search does, kept every measured plan inside the budget (24 ms for a caller in 50,000 threads, about 0.4 microseconds a thread; the same for a popular, a rare, an unused and a never-held tag, which is probed as an id that matches nothing), so the coupling is gone and not documented as accepted; what remains is that the time grows with the caller's OWN threads, which they may know. The `threadTag` filter of the room's thread list is the same predicate **The search across rooms (`GET /search`) is scoped by ACTIVE membership alone, an ADMIN's too** (`authorization.md`): the rooms are read first (`RoomService.memberRooms`) and passed as one array argument, the one `search` bucket is shared with the room search, and the plan is deliberately bounded by the caller's OWN threads (`persistence.md` "Search (V16)": a hashed or table-wide plan is the abuse shape, so the probe is a correlated scalar subquery matched on an unindexable vector expression). **The abuse scenario, and its bound.** Nothing caps messages per room, rooms per user (`memberRooms` is unbounded: BACKLOG) or a message's size short of 8,000 characters. A hostile user joins many rooms of 8,000-character messages, then searches for a word that does not exist: the probe finds no first match, so it reads every message of every one of those rooms (about 7 to 10 microseconds each, `persistence.md` "Search (V16)"). The work is the CALLER's own scope, never the table's, and it is bounded: per request by the one deadline (the typed `429`, the statement cancelled by the database, nothing left running), per user by the 60/min bucket, and per process by the cap of 4 concurrent searches, so the worst case is four connections busy for the budget, not the pool. What it can still do is make other users' searches answer `search-busy` while it runs; it cannot touch another room's content, and no other route is affected. A per-user room cap, or the index in `BACKLOG.md`, are the follow-ups if that matters. **Query parsing and SQL**: plain search builds tsquery from `to_tsvector` lexemes and treats operators as punctuation. Structured search accepts only the bounded grammar and returns fixed errors for invalid clauses and time zones. SQL binds user input in both modes. **Snippets are typed segments `{text, hit}`, never markup**: `ts_headline` returns its input untouched (`<img onerror=...>` comes back verbatim), so the server headlines with the control markers U+0002/U+0003 (unforgeable: every title and body refuses control characters), splits on them and the SPA renders `text` as text. **A search term is user content**: it travels in the query string (API-LIST-005, as the directory's `q`) and reaches no log line, no audit line and no problem body beyond the requester's own `400` (which never echoes it; `ChatLogHygieneTest` plants a marker and `ThreadSearchTest` pins the timeout path, `GlobalSearchTest` the busy path). Audit lines and problem bodies carry the path only; the metrics registry refuses names with `?`. **No span carries the query string**: Ktor's server spans record it as `url.query`, so `core`'s `ScrubbedSpanExporter` drops `url.query` (and the full-URL spellings `url.full`, `http.url`, `http.target`) from every span before any configured exporter sees it (`ScrubbedSpanExporterTest`); the path and route, ids only, stay. A search term, a tag and a `field` value therefore reach no collector, and the directory's `q` with them. Tests: `ThreadSearchTest`, `GlobalSearchTest`, `SearchBudgetTest`, `FtsTest`.

**Find in thread (1.10.0).** Thread message search uses the same `search` bucket, semaphore and deadline. It resolves the room and thread
before accepting a query and searches only live messages of that thread. Message context shares the bounded thread-detail read pool and
limits each response to 51 messages. Foreign message IDs return the same `404` as missing IDs.

**Moderator delete (D2, `messages`):** a room OWNER or an ADMIN may delete, never edit, someone else's message (incident chat leaks credentials and the author may be asleep); the body is scrubbed, not just hidden, `deleted_by` records the remover and the `message.deleted` audit line says `moderated` (`observability.md`). A closed thread refuses even that (`409 thread-closed`): a steward reopens, deletes and closes it again, and the history shows it.

**The thread history keeps no free text (1.0.0, the 0.3.0 audit's M4 and checkup C-SEC-5/C-SEC-6; closed for TEXT, URL and titles).** `thread_events` is append-only, so a credential pasted into a title or a TEXT or URL field used to survive its own edit as `TITLE_CHANGED.from`/`to` or `FIELD_CHANGED.from`/`to`, served to every current and future reader of the room. Now a title change and the thread's start record no title, and a TEXT or URL change records the field and that it changed, never the text (`threads/ThreadFieldEvents.kt`'s `RECORDED_VALUE_TYPES` is an allow-list: SELECT, MULTI_SELECT, USER, DATE, NUMBER only, so a new type records nothing until it is listed); migration V23 removed the values from the rows written before it, scrubbing an unknown or retired field's too (`persistence.md` "History scrub (V23)", which also says what it cannot do: old row versions live in the heap until vacuum and in backups, and a leaked secret must still be rotated). A RETIRED field's remaining values (option labels, names, dates, numbers) are hidden at read time, as everywhere else (`chat-model.md` "History"). The moderator message delete above remains the answer for a secret in a message body. The audit trail never held any of this (ids, keys and counts only).

**The ADMIN clean-up path of a squatted name (today).** The room record has no creator column (V13 is committed). An ADMIN who finds a room squatting a name uses the audit trail:
`room.created` (byUserId, roomId, name) names the creator, `room.name_conflict` shows who probed, and `DELETE /rooms/{id}` (ADMIN, soft) frees the name. A per-creator cap on active rooms and an
owner/creator filter for ADMINs are a backlog item (`BACKLOG.md`).

**What the room surface discloses (names).** Hidden rooms are members-only, including for administrators. For a hidden room, a non-member's `GET`, `PUT`, archive, unarchive, `DELETE` and workflow read are the same `404` as for a room that does not exist, so
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
AFTER `verifyPassword`, so nothing about enumeration changes; the lockout counter is checked but NOT cleared there, see "ONE counter"
above; `login.success` is deliberately NOT emitted). A 6-digit code (`generateMfaCode`, `auth/Passwords.kt`) is emailed in the user's
stored language (EN fallback). V57 stores a digest of the opaque challenge ID and encrypted copies of the code and captured
email identity. Instances with the same database and data encryption keys can verify a pending challenge across restarts.
A PostgreSQL row lock serializes attempts, including successful consumption. PostgreSQL measures expiry (default 300 seconds,
`MFA_CODE_TTL_SECONDS`), and attempts share the configured limit (default 5, `MFA_MAX_ATTEMPTS`). The deployment-wide
`MFA_MAX_TRACKED` limit (default 10,000) counts active challenges. Issuance reclaims expired rows in batches and rejects new
issuance with an audited `429` at capacity while existing codes remain verifiable. Failed or cancelled delivery attempts to
discard the challenge, with expiry reclaiming it if cleanup fails. Keep the previous data encryption key available for the
challenge lifetime during key rotation, alongside the existing browser-push key rotation requirements. **An account holds at most 3 live
challenges** (`MfaChallenges.DEFAULT_MAX_PER_USER`, a constant, no config key): a further login REPLACES the oldest (refusing would
let anyone holding the password stop the owner signing in; the replacement needs no spare capacity). **The accepted cost (L1):** a password holder can therefore evict the owner's pending code with three logins of their own; the owner signs in again (a fresh code), and the password reset ends the holder's access. Eviction is no guess: the per-account lockout still bounds every guess at a code ("ONE counter"). The per-challenge attempt cap bounds
one code; **every wrong code also counts into the per-account lockout** (`auth/MfaRoutes.kt`, "ONE counter"), so the guesses across
challenges add up and a fresh login never resets them. The failure that locks the account, and every later wrong code, also drops the account's pending
challenges (`discardUser`), and a correct code arriving while the account is locked is refused with the lockout's `429`
(the counter restarts when a lock expires: the long-run bound is stated in "ONE counter").
Consequence: with the default threshold 5, five wrong codes (the default per-challenge cap) lock the account for 15 minutes.
`POST /api/v1/login/mfa` `{challengeId, code}` (own per-IP bucket `"mfa"`, hardcoded 10/min in
every mode) exchanges it for the ordinary `LoginResponse` — one fresh `userService.read` first
(the `/refresh` precedent: current roles/flags; a user deleted in between → the uniform 401
`user_gone`); every failure mode (unknown/expired/wrong code/attempt cap) is a **uniform 401**,
reasons live only in the `login.mfa_failure` audit event (which names the account by `userId` whenever the challenge was found); challenges are single-use and bound to
the credential revision and email verified at the password step. A password change, password reset or change of email invalidates
an earlier challenge. Counted attempts and successful clearing check the current account under the same row lock used by password
or email updates before acquiring the account's lockout guard. **Mail-less deployments fail closed**: on
`MAIL_TRANSPORT=disabled` an MFA-enabled login answers `503` (`login.mfa_unavailable`) — don't
enable MFA flags on the k8s default `disabled` transport; the features PUT keeps working, so an
admin can always flip the flag back. SPA: the Login card's second step (`PinInput`, `auth.mfa*`
keys). Tests: `MfaChallengesTest` (shared PostgreSQL store) + `MfaLoginTest` (route matrix and restart) + `MfaLockoutTest` (the lockout, the per-user cap, the audit `userId`); e2e `mfa.spec.ts`
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
memory-DoS backstop, not a business rule (the body is buffered before it decodes, so a few concurrent big bodies would otherwise pin real heap). **Request bodies are never decompressed** (checkup C-SEC-1): `Compression` is installed in `CompressResponse` mode, because its default also inflates a `Content-Encoding: gzip` request body after the ceiling counted only the wire bytes (8 KiB of gzip inflated to megabytes); and a request that carries any `Content-Encoding` other than `identity` is refused up front with a `415` problem (a `Setup`-phase interceptor in `plugins/Http.kt`, before any ceiling or parser), so a compressed or merely labelled body is never decoded and never parsed (`PayloadValidationTest`, `ChatWriteLimitsTest`). **`MAX_REQUEST_BODY_BYTES` = 512 KiB** is
the global ceiling (the largest legitimate body, a whole-document workflow PUT, is well under 150 KiB); **`MAX_SMALL_REQUEST_BODY_BYTES` = 16 KiB** applies to `POST` on the anonymous
auth routes (`/login`, `/login/mfa`, `/refresh`, `/password-reset`, `/password-reset/confirm`) and on `POST /rooms/{id}/members` (up to 200 ids is about 2 KiB): bodies a handful of
short fields long. The link-preview POST takes the same 16 KiB ceiling. The chat writes have their own, sized from their real maxima (0.3.0 audit M3, the arithmetic is in the KDoc in `plugins/Http.kt`): **`MAX_MESSAGE_REQUEST_BODY_BYTES` = 64 KiB** for the message POST and the message PUT (8,000 UTF-16 units at the widest wire spelling, a six-byte `\uXXXX` escape each, is 48,000 bytes) and **`MAX_THREAD_REQUEST_BODY_BYTES` = 192 KiB** for the thread POST and the thread details PUT (a 200-unit title, an 8,000-unit body, and up to 20 field values of which the widest is a 2,000-unit URL: about 170 KB; a client that `\u`-escapes 20 maximal non-ASCII URLs is deliberately not covered). The status, owner, read-marker, watch and tags PUTs (the tags body is at most 20 short names), the reaction PUT (`.../messages/{messageId}/reaction`) the two pin PUTs (`.../threads/{number}/pin`, `.../messages/{messageId}/pin`) and the notification-preferences PUT take the small **16 KiB** ceiling (their bodies are one key, one id or one boolean, or the preferences PUT's two short lists of at most 24 pairs). The ceilings match the path as ROUTING sees it (`routedPath` in `Http.kt`: segments percent-decoded, empty segments dropped), so `/api/v1/rooms/%31/members` or `/api/v1/%6Cogin` meets the same ceiling as its plain spelling instead of slipping to the 512 KiB global one (`ChatWriteLimitsTest`). **An ID segment of a ceiling's pattern matches any text (`[^/]+`), not `[0-9]+`** (C-SEC-9): the router converts a typed id with `String.toInt()`, which also accepts a leading `+` (`%2B1`), leading zeros and any Unicode decimal digit (Arabic-Indic, fullwidth), and a digits-only pattern let each of those spellings of a real id reach the 512 KiB global ceiling; a segment that is no id never routes, so the tighter ceiling there costs nothing (`BodyLimitIdSpellingTest` shows each spelling routes AND meets the ceiling). A thread's `fields` is also shaped right after the body decodes and BEFORE the room lock (`requireFieldShape`, `ThreadFields.kt`): at most 20 keys, each value a JSON primitive, `null` or an array of at most 50 string primitives, else a `400` with a fixed detail that echoes nothing; and the stored `fields` document has a canonical-text ceiling (`MAX_FIELDS_DOCUMENT_LENGTH`, `chat-model.md` "Field values"). Field-level `maxLength` validation rejects oversized values far earlier on ordinary payloads. `413` is declared in the spec on every body-taking operation (the spec is the
source of the count); `PayloadValidationTest` pins the global limit and the five auth routes, `RoomMembersTest` the member add (a 1 MiB `userIds` body is refused without decoding), `ChatWriteLimitsTest` the 64 KiB and 192 KiB ceilings (a maximal escaped message still posts) and the `fields` shape answered while the room is held.

**Request payload validation (convention — API-SEC-003/API-ERR-005).** Mutating routes validate
payloads up-front and throw `BadRequestException` (→ `400` + `ProblemDetail`) instead of letting
oversized/blank values die in the DB as `500`s. JSON body IDs use `JsonUInt` or `JsonInt` to enforce the integer type declared in
OpenAPI. Send `7` as a number. Quoted decimals, fractions, and out-of-range values receive `400`. Nullable IDs still accept `null`.
One cross-cutting example: password ≥
`MIN_PASSWORD_LENGTH` (10) chars AND ≤ 71 UTF-8 bytes (`validatePassword` in `users/UserRoutes.kt`
+ `MAX_PASSWORD_BYTES` in `auth/Passwords.kt` — **the bcrypt ceiling**: longer input makes bcrypt
throw, and the 500-vs-401 split would be an account-enumeration oracle, so login's
`verifyPassword` guards it too). Declare limits as `maxLength` in the OpenAPI spec. Keep new
validators feature-local and enforce them **after** the authz guard (403 wins over 400). Covered
by `PayloadValidationTest`. **The id a message quotes (1.3)** is one such input: `quotedMessageId` on the message POST is an int32 in 1 to 2,147,483,647 (`validateQuotedMessageId`, run by the route AND the service; a plain `400` outside it, the read marker's shape), and it is an INPUT TO A LOOKUP, so an id that names nothing usable (unknown, another thread's or room's, deleted) is ONE typed `400` `urn:adder:problem:quote-unavailable` with a fixed detail that echoes no id, answered after exactly one statement whatever the cause, so neither the answer nor its time separates a foreign id from a missing one (`MessageQuotesTest`).
**The one-emoji rule** (a reaction's `emoji`, `threads/Reactions.kt`, pure, no dependency; the full rule and the case table are `chat-model.md` "Reactions (V20)"): a bounded input judged by the STRUCTURE of its Unicode code points (JDK properties, a `BreakIterator` pass and a linear scan over at most 10 code points, so no pattern the input could make expensive), not by which code points occur. The value is stored as sent and compared by bytes, so a set-membership rule would store one emoji as many look-alike values (a selector, a trailing joiner, stacked skin tones, keycap marks) and let four people fill a message's 20 places with copies, and tag characters would smuggle invisible ASCII ("password"); the rule admits one fully qualified spelling per emoji (a flag pair, a keycap WITH its U+FE0F selector, one of three tag flags, elements joined by single joiners, one skin tone or one selector per element) and refuses a bidi override, a combining mark, a zero-width or control character whole. It refuses with a FIXED `400` detail that never echoes the value, and the SPA renders the value as text. What it cannot check is membership of the official list (a chain of up to five pictographs joined by joiners passes), which bounds the harm to one slot per distinct arrangement, and each person holds five. The limits are 20 distinct emoji per message and 5 per person per message (`409 reaction-limit`), read and written under the room lock so two writers cannot pass them; the write rides the `chat-write` bucket. `ReactionsTest`, `MessageReactionsTest`.

**Outbound message link previews (1.13.0).** An explicit Preview action posts a stored message destination to the authenticated
link-preview route. The per-user `link-preview` bucket allows 20 requests per minute and one in-flight preview. The service confirms
room access and matches the canonical URL to a live Markdown destination before and after external work. It releases database resources
before the fetch. The request has a 16 KiB ceiling and the canonical URL a 2,048-character ceiling. Responses use `Cache-Control: no-store`.

`infra/outbound` adapts Flow's connection-time DNS guard. Only HTTPS on port 443 with a public DNS hostname is eligible. The transport
rejects credentials, special-use hostnames and IP literals. It resolves and checks public IPv4 addresses, then pins the connection to
those addresses with normal TLS hostname and certificate verification. Every redirect and optional image goes through the same checks.
The client uses direct sockets and excludes cookies, credentials, proxies and forwarded browser headers. Two preview workers and a small
queue share a 10-second per-preview deadline. DNS uses a cancellable lookup with a two-second budget, one second per query
and at most four queries. Each resource follows at most three guarded redirects. HTML reads up to 256 KiB and yields
plain metadata. PNG and JPEG inputs allow up to 2 MiB and four megapixels. Re-encoding strips metadata and caps output at 640 pixels and
256 KiB. A failed optional image leaves the page title available when time permits. The guard emits a coalesced `outbound.blocked` event
without the destination URL. See `observability.md` and the message-preview API contract.

**CORS is off by default** (`plugins/Http.kt`): the plugin is installed only when
`http.corsHosts` (`$CORS_ALLOWED_HOSTS`, comma-separated hosts) is non-empty. **In production only an `https` origin of a listed host is admitted**
(`allowHost(..., schemes = ["https"])`; development admits `http` too, for a local front end): an `http` page can be rewritten in transit, and with it the token
it holds (C-SEC-9; `CrossOriginPolicyTest` pins both modes). **The live socket's Origin rule applies the same restriction** (`live/LiveAdmission.kt`,
`listedHttpsOnly`): in production a `http.corsHosts` entry admits an `https` origin only, an `http` one is the `403` with `live.refused reason=origin`
(`LiveAdmissionTest` pins the matcher). It is **defence in depth behind CORS**: with `http.corsHosts` set the CORS plugin judges the
`Origin` first and answers a foreign or `http` origin with its own bodiless `403`, so in production that branch is normally reached
by no request and writes no `live.refused` line; the server's own origin is judged by scheme equality as before. Production is
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

**The client address is NUMERIC, with or without a proxy** (0.4.0 audit). Every per-IP bucket (the `login`, `refresh`, `mfa`, `password-reset`, `password-reset-confirm`, `ready` and `live-connect` rate limits; the login LOCKOUT is per account, not per IP) and the live channel's per-address pending share key by `clientAddressKey()` (`infra/ClientAddress.kt`, checkup C-SEC-4): `call.request.origin.remoteHost` folded to an IPv4 address whole (a `:port` suffix dropped, the form Azure Application Gateway and IIS ARR write), an IPv6 one by its /64, an IPv4-mapped one as IPv4; a value that is NOT an address literal (junk forwarded text, a hostname) is never a key of its own, it counts under the TCP peer (`call.request.local.remoteAddress`), so rotating `X-Forwarded-For` text mints no buckets (`ClientAddressKeyTest` pins the precedence, `ForwardedHeadersTest`, `LivePendingTest`). The fallback is logged: one WARN per ten minutes (logger `ch.nokillswit.infra.ClientAddress`, the number of calls folded, never client text) when the trusted hop is no address. `origin.remoteHost` itself defaults on Netty to `InetSocketAddress.getHostName()`, a REVERSE-DNS lookup: a client with forward-confirmed PTR records for many addresses would get one key per address (and an IPv6 /64 would not group), and one whose PTR is shared or slow would share a key. So `configureHttp` registers a `Setup`-phase interceptor FIRST, before anything reads `remoteHost` (the JDK caches the resolved name), that copies `call.request.local.remoteAddress` (`getHostString()`, the literal) into the origin's `remoteHost`; behind a proxy, `resolveForwardedForOrigin` (registered after it) overrides that with the trusted hop, and a request with no `X-Forwarded-For` keeps the proxy's numeric address. The helper is `numericClientHost` (`ClientAddressAndHostTest`, played by hand because a test host cannot make Netty resolve a name; the proxy-trust tests `ForwardedHeadersTest` and `RawForwardedForLinesTest`, the latter on the real Netty engine, still hold).

**A malformed `Host` with `http.corsHosts` set** (0.4.0 audit): Ktor's CORS plugin compares the `Origin` with the request's own authority, and `origin.serverPort` throws a `NumberFormatException` on a `Host` whose port is not a number, so every `/api` call carrying an `Origin` was a 500 with an ERROR log and a stack trace. When CORS is installed, a second `Setup`-phase interceptor (before CORS's `Plugins`-phase hook) answers such a request `400` with a problem document; without CORS nothing reads the port and the interceptor is not installed (a bracketed IPv6 literal in `Host` is unreadable to Ktor too, so with `corsHosts` set it is a 400 as well). The live admission's own `detail=host` refusal stays as a backstop. 400 is a cross-cutting status (`CROSS_CUTTING_STATUSES`: the coverage gate asks no per-operation entry; the runtime conformance plugin only checks an operation that actually answers one it does not declare, as in `ClientAddressAndHostTest`, which uses `POST /login`, which declares 400).

**Trust the address or the share degrades** (`live.md` "The pending share per address"): the per-address pending share and the `live-connect` bucket are only as good as `remoteHost`. Behind a plain proxy with `http.behindProxy=false`, or through an SNAT load balancer (`externalTrafficPolicy: Cluster`), every client arrives as the proxy's or the node's address and they all share ONE `live.maxPendingPerAddress` (8); with `behindProxy=true` and no proxy that strips client-supplied `X-Forwarded-For` (a LoadBalancer or NodePort straight to the pod: the shipped manifests no longer have one, `k8s/ingress.yaml`), a client can still claim any REAL address literal (a fresh IPv4 address per request, or an IPv6 /64 of its own) and so pick its own key; junk text no longer can. Fix the trust (a proxy that sets the header and `behindProxy=true`, or `externalTrafficPolicy: Local`), or raise `live.maxPendingPerAddress`. **Behind a proxy the peer IS the proxy**: clients whose trusted hop is not an address (a header format `clientAddressKey()` does not read, or `http.proxyHops` pointing at a hop that is not the client) are all counted under the proxy's address and share ONE bucket per limit, a global lockout; the WARN above says so, so check the proxy's `X-Forwarded-For` format.

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
  calls the Kubernetes API, so no token is mounted. `app` also has a 5 s `preStop` `sleep` and `terminationGracePeriodSeconds: 50` (the endpoint leaves the Service while it still serves; the sleep is spent inside the grace period, pinned by `JvmMemoryFlagsTest` against the shutdown budget in `observability.md`). `app`: uid 10001, `runAsNonRoot`, seccomp
  `RuntimeDefault`, read-only root filesystem (only `/tmp`; the AppCDS archive `/app/cds/adder.jsa` is a root-owned 0444 file baked into the image and only ever read), no privilege escalation, `drop: [ALL]`.
  `postgres` runs the same way minus the read-only root filesystem: `runAsNonRoot` as the alpine image's
  own `postgres` user (**uid/gid 70** — 999 in the Debian image, so change `runAsUser`/`runAsGroup`/
  `fsGroup` together with the image variant), `fsGroup: 70` with `fsGroupChangePolicy: OnRootMismatch`,
  seccomp `RuntimeDefault`, `allowPrivilegeEscalation: false`, `drop: [ALL]` (a non-root start needs no
  capability: the entrypoint's chown/gosu step only runs as root). `readOnlyRootFilesystem` stays off for
  postgres — it writes its socket/lock files under `/var/run/postgresql` and temp files under `/tmp`.
- **`k8s/network-policies.yaml`.** `default-deny-all` (ingress AND egress for every pod in `adder`), then:
  `postgres` accepts 5432 only from `app`; `app` accepts 8087 only from the ingress-nginx controller's pods (the front-door bullet below);
  `app` may egress to `postgres:5432`, to cluster DNS (kube-system `k8s-app=kube-dns`, 53
  UDP/TCP), public SMTP on 587/465/25 and public IPv4 HTTPS on 443 for message link previews. The HTTPS rule excludes the same
  private, link-local, reserved and special-use pools as `PreviewTarget.kt`. Deployments that use publicly routed ranges for internal
  services must exclude those internal ranges as well. Things that need their own extra policy:
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
- **The front door — settled 2026-11-20: an ingress-nginx Ingress** (`k8s/ingress.yaml`; tested end to end on OrbStack the same day: SPA,
  login, a `101` and `ready` on `/api/v1/live` with a real ticket, held open 135 s through the Ingress, the NetworkPolicy enforced: a pod in
  another namespace times out on `app:8087`, reaches it with the policies deleted, and times out again when they are restored). The `app` Service is `ClusterIP`
  (no LoadBalancer/NodePort path around the proxy) and `allow-app-ingress` admits `:8087` only from the controller's pods
  (namespace `kubernetes.io/metadata.name: ingress-nginx` AND pod labels `app.kubernetes.io/name: ingress-nginx`,
  `app.kubernetes.io/component: controller`, ONE `from` item); the kubelet's probes need no rule. The two halves belong
  together: `HTTP_PROXY_HOPS=1` makes the app trust the LAST `X-Forwarded-For` entry, which is only the controller's
  when nothing else can reach the pod — the policy is what turns "trust one hop" into "trust only the ingress"
  (the app has no CIDR allow-list; the CNI's policy is that list). **Which entry is taken:** ingress-nginx's defaults
  (`use-forwarded-headers: false`) REPLACE `X-Forwarded-For` with the address of the connection nginx accepted (the client's own
  value moves to `X-Original-Forwarded-For`), so the app sees one entry and `resolveForwardedForOrigin` takes it (index
  `size - 1`); a client-sent `X-Forwarded-For: 1.2.3.4` never reaches the app as such. With `use-forwarded-headers: true` +
  `compute-full-forwarded-for: true` the controller APPENDS (`client-sent..., peer`) and the last entry is still the peer the
  controller saw; trust more hops only when a real proxy sits in front of the controller (a cloud LB that appends:
  `HTTP_PROXY_HOPS=2`), and never raise the count without that proxy, or a client-written entry becomes the key.
  `use-forwarded-headers: true` is safe only with `proxy-real-ip-cidr` narrowed to the trusted front proxy and together with
  `compute-full-forwarded-for`; it also passes the client's `X-Forwarded-Proto`/`-Host` through, which skips the ssl-redirect, and
  without both a client-written `X-Forwarded-For` becomes the per-IP key. F5's NGINX Ingress Controller ignores the
  `nginx.ingress.kubernetes.io/*` annotations and carries other labels, so with it the policy admits nothing and the app answers 502/504 (fail closed). **What "the
  peer the controller saw" is** depends on the cluster: with an SNAT in front of the controller it is the node's or gateway's
  address, not the browser's, and every client shares one per-IP bucket (the WARN above does not fire: it IS an address).
  **Verified on OrbStack (2026-11-20, Kubernetes v1.36.5, ingress-nginx controller-v1.15.1): this is what happens there**, even
  with the manifest's `externalTrafficPolicy: Local`: the controller's access log shows `192.168.194.1`, the pod-network gateway, for
  every request, so on OrbStack all clients share one per-IP bucket and one live pending share. No audit line carries an address
  (the buckets are keyed on it, never logged), so look at the controller's access log, whose first field is the peer the app
  gets. A spoofed `X-Forwarded-For` is not the key: 13 failed logins for different emails, each with its own client-sent
  `X-Forwarded-For`, were answered 401 nine times and 429 from the tenth (the 10/min `login` bucket, one successful login before),
  i.e. one shared key. Where the cluster supports it, `externalTrafficPolicy: Local` on the controller's Service (the cloud
  manifest's default) is the remedy for a node-level SNAT.
  **`X-Forwarded-Host` and the live socket (verified):** in the default mode the controller sets `X-Forwarded-Host` to the request's own
  Host (`$best_http_host`) and moves the client's value to `X-Original-Forwarded-Host`, so a client-sent one never reaches the app:
  the live socket answers `101` for the right Origin, `403` (`live.refused`, `authority`) for `Origin: https://evil.example` with or
  without `X-Forwarded-Host: evil.example`, and `403` for an `http://` Origin or none; no `Access-Control-*` header is ever sent
  (CORS is not installed while `http.corsHosts` is empty), so a cross-origin page cannot read the API. With
  `use-forwarded-headers: true` this changes (the client's value would pass through): see above.
  TLS ends at the controller (`ssl-redirect` 308, HSTS overwritten by the controller's own policy); the host, the certificate
  SAN and `MAIL_APP_URL` move together; `http.corsHosts` stays empty (single origin; the live socket's Origin check
  compares the browser's origin with the scheme/host/port rebuilt from `X-Forwarded-Proto`/`-Host`, which the controller
  sets from the request). Body ceiling: the largest app ceiling is 512 KiB (`MAX_REQUEST_BODY_BYTES`), the Ingress pins
  `proxy-body-size: 1m` so the app's own problem+json 413 answers. Community ingress-nginx was retired in March 2026: a
  production cluster re-verifies this paragraph against whichever controller it runs (the contract, not the name, is what
  the app needs: replace XFF, set Proto/Host, pass the upgrade, 60 s+ timeouts).

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
`password_reset.send_failed`; covered by `LogoutTest`. The delivery-failure ERROR lines (`auth/MfaEmail.kt`, both of `auth/PasswordResetEmail.kt`'s) log the class name (the MFA one also the user id), never the address or the throwable (an SMTP refusal can echo the recipient and server detail); `MfaLoginTest` pins the MFA line against a server that does exactly that. **Chat content never reaches a log line**: a message body, a thread title and a field value are free text (an incident chat holds pasted credentials), so no logger renders them - the audit lines carry ids, keys and counts, a refused write's `400` detail is a response, never a log, and `ChatLogHygieneTest` plants a marker in a body, a title and a TEXT field (written and refused: a control character, a nested value, an unknown key, a 413, a stale revision) and asserts it appears in no event of the ROOT logger (message, arguments, key/values, MDC, throwable chain). **A quoted excerpt is a body (1.3):** the quote a message carries is the quoted message's current text on the wire, never in a log or an audit line (`message.posted` names `quotedMessageId`, an id), and a second test plants a marker in the quoted and the quoting body and asserts the same for a quote posted, listed, read singly, pinned, refused (unknown, out of range, deleted) and a quoted message deleted afterwards (`ChatLogHygieneTest`). **A failed database statement never logs its bound values either**: a database exception's MESSAGE is unsafe (Exposed's `ExposedR2dbcException.getMessage()` appends the failed statements with their arguments expanded whenever the transaction's `debug` flag is on, and Postgres itself echoes values into some messages: an input-syntax failure, a `RAISE`, a malformed tsquery), so the 500 catch-all logs a chain holding one (or any suppressed exception, or a chain past the walk's bound: fail closed) as class names, the SQLSTATE and stack frames only (`plugins/ScrubbedFailure.kt`, `observability.md` "The 500 line"); any new log site for a throwable that may come from the database does the same (`scrubbedForLog()` for a throwable, `scrubbedSummary()` for one line), or logs `javaClass.simpleName`; the telemetry collector failure line and the readiness probe's DEBUG line already do. The loggers that print a cause or a request value below INFO (`Exposed`, `io.ktor.server.plugins.statuspages`, `io.r2dbc.postgresql` - every bound value at DEBUG on its `QUERY` and `PARAM` children - and Ktor's `io.ktor.server.auth.Authentication` and `io.ktor.server.plugins.ratelimit.RateLimit`, which trace `request.uri`) are pinned to INFO in `logback.xml`, so raising the root level never switches them on (`ChatLogHygieneTest`). **Spans too:** `core`'s `ScrubbedSpanExporter` wraps every configured trace exporter so a recorded exception leaves as `exception.type` only, never `exception.message`/`exception.stacktrace`, and the request's query string is dropped (`ScrubbedSpanExporterTest`). `ChatLogHygieneTest`'s echo test makes a message INSERT fail with a database message that carries the body and asserts the body in no ROOT-logger event (it fails on the unscrubbed catch-all). A **hashtag** is content too: a second test plants a tag marker and writes, edits, deletes, filters by and lists it (a refused `tag` filter included), and the boot reindex logs class names only (`MessageTagReindexTest`). Its cancelled-statement (57014) test is only a shape pin that the line still names the driver class and the SQLSTATE, not a leak proof. **An emoji is content too** (a reaction, 0.6.0): the reaction route takes it in the BODY, never the path, so the `path` of `authz.denied`, `room.access_denied` and `rate_limit.exceeded`, the access log and the route metrics carry none, and `message.reaction_changed` carries ids and `reacted` only; a third test plants a rare emoji and a marker and adds, removes and has it refused (not an emoji, two clusters, a limit, the closed thread, an outsider's `404`, an ADMIN's `403`) and asserts it in no event of the ROOT logger.

**Outbound email** (`infra/mail/`, ported from Lettuce): `configureMail` (registered at the top of
the infrastructure group, before Flyway) publishes `MailerKey` holding a `Mailer` or null.
`mail.transport` (`$MAIL_TRANSPORT`) selects `log` (dev default — the full message, **including
reset links and MFA codes**, goes to the `ch.nokillswit.mail` logger; **production mode refuses to
start on it**), `smtp` (Jakarta/Angus Mail over `mail.smtp.*` / `$SMTP_HOST` etc.; a blank host
refuses startup in any mode; **production also refuses a plaintext session** - `SMTP_STARTTLS=false` without `SMTP_SSL=true` - because the body carries MFA codes and reset links, C-SEC-9; `SMTP_STARTTLS` (default true, port 587, refuses a server that cannot upgrade) or `SMTP_SSL=true` (implicit TLS from the first byte, port 465, the certificate's name is checked, wins when both are set); development permits plaintext for the compose Mailpit; **in production the server certificate's name is checked on STARTTLS too** - whenever TLS is on (STARTTLS or implicit) the session writes `mail.smtp.ssl.checkserveridentity=true` EXPLICITLY, in every mode (Angus 2.x already defaults to it for both, and the setting must not depend on that default; it is never written `false`), so a certificate for another name fails the send, a misconfiguration to fix rather than accept; `SMTP_SSL=true` with port 587 or 25 (plaintext-first ports) logs a startup WARN: implicit TLS is port 465; `MailTransportTest`), or `disabled` (the Docker image default via
`ENV MAIL_TRANSPORT=disabled` — email features answer 503 through `respondMailUnavailable`). The
compose demo wires `smtp` → the bundled Mailpit (`http://localhost:8030` — 8025–8029 belong
to sibling stacks); k8s ships `disabled` with `SMTP_USER`/`SMTP_PASSWORD` read (optional) from
`adder-secrets`. Consumers: self-service password reset, email MFA and the digest of unseen notices (below). The recipient-language
content layer (`LocalizedText`) lives beside the transports; `mail.appUrl` is validated here too (above). Tests:
`MailTransportTest` (the transport matrix + both refusals + LogMailer delivery via the
`ch.nokillswit.mail` LogCapture); production-mode boot tests must override `mail.transport` with
`"disabled"`, since the dev-default `log` transport is refused in production.

**The notices' email digest** (`notifications/NotificationDigest.kt` (its statements: `NotificationDigestSql.kt`) + `NotificationEmail.kt`, V22, the third `Mailer` consumer; `chat-model.md` "Notifications (V18)"). The user's decision: a digest, OFF by default for every kind since 1.1.0 (1.1 D7 as amended: each person opts in per kind in the notification preferences; account email, the MFA code and the password-reset link, is not a notice and always goes); a notice still unseen after `notifications.email.delaySeconds` (300; 0..86400) is mailed, at most one email per person per `notifications.email.minIntervalSeconds` (900; 0..86400), both boot-validated with `requireConfigLong` on every boot, and refused together when the larger one plus `notifications.sweepIntervalSeconds` reaches 86400 (nothing older than a day is mailed, so such values would silently mail nothing; `NumericConfigBoundsTest`). (1) **What it may say**: who, the room's name, the thread's number and a link, in the recipient's stored language; **never a thread title, never any message text**: `DigestEntry` has no field that could carry one, so the builder cannot leak what it is never given (`NotificationEmailTest`, `NotificationDigestTest`, `ChatLogHygieneTest`-style log scan). The subject is a count and holds no name; names in the body (the recipient's, the actor's, the room's and the mentioned team's) pass `mailSafe`: every format character (Unicode `Cf`: zero-width characters, the soft hyphen, bidirectional controls) is DROPPED, the text is NFC-normalised, `plainName` runs (control and line-separator characters become a space, so a name cannot start a line; its bidi, mark and separator code points are written as unicode escapes, never as literal characters, and `SourceHygieneTest` fails the build on any literal bidi control, zero-width character or line/paragraph separator in main sources, C-SEC-8), then the text is made unclickable, because a mail client turns a bare address or domain into a link and a room owner chooses the room's name (80 characters): `@` becomes U+FF20, the slashes of `://` become U+2215, EVERY backslash becomes U+2216 (no UNC path or `file:\\host` for Outlook to link), and EVERY full stop (ASCII, U+3002, U+FF0E, U+FF61) that is not followed by a plain space or the end of the text becomes U+2024 (so `www.x.y`, `evil.example.com/login`, `a@b.c`, an IDN and a dot hidden behind a combining mark or a soft hyphen all read the same and link to nothing; "J.K. Rowling" reads "J․K. Rowling"). The rule is deliberately not "a dot between letters": a combining mark or a format character beside the dot defeated that (`NotificationEmailTest` asserts on a blunt oracle, never on the implementation's own predicate). The room's name is kept (0.6 D3: the mail names the room), single-line by validation, and a mail is plain text only, so it can still MISLEAD (a room named like an instruction) but it can carry no address or link of the room owner's choosing, and never injects a header or markup (`NotificationEmailTest`). (2) **Links** are built from `mail.appUrl` only (the origin the password reset uses, validated at boot, never a request's `Host`) and from ids; with it blank the mail has no link. They are the ONLY links in the mail: no name (`mailSafe`, above) can add another. (3) **Who is mailed**: re-checked at SEND time, in one short read right before each send, not only when the notice was made: the account is active (a soft-deleted one is skipped), the notice still exists and is unseen (a removed member's notices are deleted with the membership, a deleted message's with it), the room is an active room the person is an active member of, and the kind's email switch is ON at that moment (`effectivePreferences`: a stored row, else the default, which is off). What fails the check is skipped silently and stays claimed; it consumes neither pacing interval, so a later eligible item can be attempted without waiting. The notices of a kind whose email is off (every kind, for a person with no opt-in) or of a deleted account are SETTLED by the claim's own transaction (stamped `emailed_at`, before the claim; the settle reads the oldest `DIGEST_SCAN_CAP` due rows ONCE and judges those, so it never walks more than the cap), so they leave the index for good; switching a kind back on covers the notices made from then on. The only account state there is, besides active, is deleted. (4) **Claim, then send**: one statement stamps `emailed_at` (`FOR UPDATE SKIP LOCKED`; per PERSON, not per notice: at most `min(500, maxPerRun + max(10, maxPerRun / 5))` people a tick (the send budget plus a margin for those skipped at send time, so a tick does not claim five times what it may send and release it), in the order of their OLDEST waiting notice; nothing older than a day, nobody inside the interval, no deleted account, no kind switched off for email) BEFORE any send, **and it stamps ALL of a taken person's due notices within the scan, not 50**: the mail lists the first 10 (50 are read for it) and says "and N more" for the rest, which the subject counts too (**the send-time re-check runs on ALL of the person's claimed notices**, not on the listed ones, so dead notices at the head hide no live ones and are not counted), so a flooded person gets ONE mail, not one an interval for each 50 notices, and one flooded person's backlog is one slot that cannot crowd out the rest. **The scan is bounded**: the `eligible` set is `ORDER BY created_at, id LIMIT 5000` (`DIGEST_SCAN_CAP`) and both the claim's and the settle's statements run under a 10 s `queryTimeout` (`DIGEST_STATEMENT_TIMEOUT_SECONDS`, the transaction's `queryTimeout` being the only door to a statement timeout, `infra/db/StatementTimeoutCache.kt`); a statement past it is logged scrubbed (class names and the SQLSTATE) and the run ends with nothing claimed, the transaction having rolled back. **The fairness trade-off, stated**: a person whose notices all lie beyond the oldest 5,000 due ones waits for a later tick (the head leaves the index as it is mailed or settled); rows the claim's filters reject (a person inside the interval, a kind switched off) are still walked to find the 5,000, which the settle (the second kind) and the timeout (the rest) bound. Dropping the settle and letting the claim take switched-off and deleted people, to skip them at send time, was rejected: the unlisted tail would then count notices of a kind the person switched off. so a notice is mailed at most once, two processes never send one twice, a failed send is audited and not retried, and **no database connection is held across the SMTP call** (`persistence.md` "Connection pool"). **Failures and stops**: the re-check and the send share ONE per-recipient catch (a failure of either is audited as `notification.email_failed` with the class name only, and the recipient is not retried); a run ends after 3 failures in a row (`DIGEST_MAX_CONSECUTIVE_FAILURES`: with SMTP or the database down every further attempt is a certain failure and would hold the sweep's tick, and the purge behind it) and on cancellation, and in both cases the claims of the recipients that were never ATTEMPTED are released (`emailed_at` back to null, in a `NonCancellable` finally; nothing was sent, so this is not a retry). **What can still be lost**: a recipient attempted when the process dies or the run is cancelled MID-SEND (the message may or may not have left), a notice claimed for a person whose check then found nothing to mail, and the notices of a run whose release itself failed (logged): at most once is the rule, an unmailed digest is the price. The recipient mail interval is shared in PostgreSQL and capped at 50,000 active entries. At physical capacity, a new recipient atomically reuses one expired row; when every row is active, the recipient is deferred and the run logs a coalesced warning. (5) **Mail off**: `mail.transport=disabled` publishes no mailer; the digest does nothing, says so once and never refuses startup. (6) **Flooding and the send budget**: a person's notices are bounded by what bounds the write (`Notices` above) and by the interval, so reactions can add one notice per actor and message for its eligible author and share the chat-write bucket; a busy inbox gets at most one email per person per 15 minutes; the claim is one batch of people a tick. **The digest shares the `Mailer` with MFA codes and password resets**, so a run sends at most `notifications.email.maxPerRun` mails (default 100, boot-validated 1..1000, `requireConfigInt`; failed attempts count) and RELEASES the claims of the people beyond it (they ride the next tick): that budget, not a quota, is what keeps a flood of notices from exhausting the provider for the login mails. **`maxPerRun` is a per-TICK budget**, so the digest's mail rate is at most `maxPerRun / notifications.sweepIntervalSeconds` (set both with the provider's limit in mind). A per-day global cap is deliberately absent: it would block those mails. (7) The preferences PUT `PUT /users/{id}/notification-preferences` is self or ADMIN (403 first, then 400, then 404), a wholesale replace (`disabled` and optional `enabled` pairs; unlisted takes the default) under a `FOR NO KEY UPDATE` row lock, audited on a change of the person's deviations, capped at 16 KiB (`Http.kt`, pinned plain and percent-encoded by `ChatWriteLimitsTest`) and, for the PUT, under the per-user `notification-prefs` bucket (30 a minute, before the guards; the GET is unbucketed). Tests: `NotificationDigestTest`, `NotificationEmailTest`, `NotificationPreferencesTest`.

**Shared recipient pacing (V59).** Before SMTP, every eligible digest now reserves both a shared
per-recipient `minIntervalSeconds` window and the existing one-hour actor/recipient windows in one short transaction. Successful,
failed and cancellation-outcome-unknown attempts consume both; a final access/preference rejection consumes neither. The recipient
reservation is fail-closed at the former 50,000-entry bound and survives restarts and concurrent app instances.

**Encryption at rest** (`infra/crypto/`, ported from Lettuce). Browser push endpoints (V44) and pending email MFA codes and delivery addresses (V57) use application-side AES-256-GCM (`FieldCipher`: a
fresh 12-byte nonce per value, 128-bit tag, envelope `enc:v1:<base64(nonce||ciphertext)>`), so a
database-level attacker — SQL access, `pg_dump`, a stolen volume or backup — sees ciphertext; the
key lives with the app (`DATA_ENCRYPTION_KEY`, 64 hex chars from `openssl rand -hex 32`; compose
ships a burned demo key, k8s reads `adder-secrets`), never in the database. **Losing the key loses
every encrypted value** — back it up apart from the DB. To rotate, set the new `DATA_ENCRYPTION_KEY` and keep the old key as
`DATA_ENCRYPTION_KEY_PREVIOUS`, which decrypts existing endpoints when the current key fails. Registration writes use the current key.
Keep the previous key until all subscriptions written with it have been re-registered, removed, or expired. With the notification sweep
enabled, the last old-key subscription expires after 90 days and is removed on the next sweep. Keep the keys that cover any retained
database backups. **Never filter or sort on an encrypted column in SQL**. Each value has a fresh nonce.
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

Flow's `EncryptedAtRest` registry and `Reencrypt` boot backfill are candidates for automatic re-encryption during key rotation.
