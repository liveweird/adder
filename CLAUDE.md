# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Product

Adder is **Slack crossed with Jira** for short-lived, intensive, asynchronous collaboration — e.g. 30+ people working a complex, multi-threaded
incident without chaos. People work in **rooms** (members only); all talk lives in **threads** (you either start a thread or reply in one); every
thread carries a **workflow status + configurable metadata** (per room, copied from ADMIN-curated workflow templates) and an **owner** (the creator
at first, passable like a Jira assignee); a thread in a CLOSED-category status is **closed** — no further activity until its owner, the room owner
or an ADMIN reopens it. Room content is filterable, sortable and searchable; the usual chat affordances apply (pins, @mentions, reactions, emoji,
#hashtags). Updates reach open browsers live over a WebSocket.

**Roadmap:** **1.4.1 (current: refreshed Strike logo)** on **1.4.0 (clearer workspace)** on **1.3.0 (quotes)** on **1.2.0 (room view, tags, unread)** on **1.1.0 (watching, group mentions, notice kinds)** on **1.0.0 (the checkup)** on 0.1.0 to 0.6.0. The version chain, scope, decisions and assumptions: `.claude/docs/product.md`.
The domain sketch: `.claude/docs/chat-model.md`; the live protocol: `.claude/docs/live.md`. Open items: `BACKLOG.md`.

Brand: olive / venom chartreuse (the `adder` colour tuple in `web/src/theme.ts`, `primaryShade: { light: 8, dark: 9 }`). The logo "Strike" shows
a front-facing adder with slit pupils, fangs and a forked tongue (`web/public/logo-*.svg`).

## Donors

**Flow is the primary donor**: this repository started as an exact copy of Flow (`~/Sources/flow` @ `4e43e480`, Flow 1.57.1), trimmed in the shape
Astrolabe's bootstrap set (`~/Sources/astrolabe`, 2026-10-31); Covenant, Lettuce and Toadie came in indirectly through Flow. **Port, don't reinvent**:
when Adder needs a capability a sibling already has, port its implementation. What each donated and what to port next: `.claude/docs/product.md` "Donors".

## Commands

Gradle wrapper is at `./gradlew` (use `gradlew.bat` on Windows). JDK 21 toolchain is required
(auto-provisioned via foojay-resolver; the local dev JDK is pinned in `mise.toml` —
`temurin-21.0.13+9.0.LTS`, Node 24.22.3). Run `mise install` once, then `mise exec -- <command>`
or activate mise in your shell. If `./gradlew` resolves the wrong JDK, point it explicitly: `JAVA_HOME=$(mise where java) ./gradlew build`.

- All local gates, timed like CI: `scripts/gates.sh [server|web|e2e|all]` (stops at the first failure; `.claude/docs/build-times.md`)
- Build everything: `./gradlew build`; run the server (Ktor + Netty, port 8087): `./gradlew :server:run`; all tests: `./gradlew test`
- Run server tests only: `./gradlew :server:test` (Docker for Testcontainers; OrbStack: testing.md)
- Run a single test: `./gradlew :server:test --tests "ch.nokillswit.ServerTest.security headers are set on responses"`
- Static analysis (detekt, both Kotlin modules): `./gradlew detekt` — rides `check`, zero findings, no baseline file; rule
  tuning lives in `config/detekt/detekt.yml` ONLY; never add an uncommented `@Suppress`.
- Dependency alignment: `./gradlew :server:checkDependencyAlignment` — one version per aligned family
  (Netty, OpenTelemetry, kotlin stdlib/reflect, Jackson 2, Reactor) on the runtime classpath; rides `check`.
- Package the server: `./gradlew :server:installDist`. **Never use `:server:buildFatJar`** — the fat JAR breaks Flyway's
  `ServiceLoader` discovery.
- JVM memory flags are pre-tuned in `server/build.gradle.kts` (`applicationDefaultJvmArgs`; rationale in place).
- AppCDS class list (`server/cds/classlist.txt`, dumped into the Docker image): `node scripts/cds-classlist.mjs` regenerates it from a real run
  (in a git worktree pass `--image <tag>`); `CdsClassListTest` and CI's `scripts/check-cds-archive.sh` guard drift.
- **Run the whole stack with one command: `docker compose up --build`** (only Docker required). See "Running the full stack" below.
- Frontend: `cd web && npm install --legacy-peer-deps`, then
  `npm run dev|build|lint|test|test:coverage|knip|gen:api` (details in `web/CLAUDE.md`). The
  API-contract gate is `npm run lint:api` (Spectral lint of the OpenAPI spec + the conformant
  fixture against `api-guidelines/`) and `npm run check:api` (in-memory spec → `schema.ts` diff).
- E2E: start a throwaway stack and name it (`E2E_BASE_URL`, `E2E_MAILPIT_URL`; no default, the suite refuses without them), then
  `cd e2e && npm ci && npx playwright install chromium && npm test` (plus `lint`, `knip`, `typecheck`, `check:scenarios`).
- CI: `.github/workflows/ci.yml` re-runs every gate above on push/PR; the blackbox Playwright suite (`e2e.yml`) runs
  nightly and on demand; Dependabot checks every workspace weekly. The job list, the Markdown-only skip, Trivy and
  grouping: `.claude/docs/ci.md` (dependency rules: `dependencies.md`, `dependency-reproducibility.md`).

## Running the full stack

`docker compose up --build` serves everything at `http://localhost:8087` (sign in as `admin@adder.local`; the README
has the initial password). Local dev: `docker compose up postgres mailpit` (Postgres on host port
**5439**, Mailpit UI **8030**) + `./gradlew :server:run` (API on **8087**) + `cd web && npm run dev` (Vite on **5179**,
proxying `/api`), each in its own terminal; the ports avoid the sibling repos' and bind to 127.0.0.1 only. The sibling port table, Mailpit and the
Kubernetes (OrbStack) `adder` namespace: `.claude/docs/ci.md` "Running the full stack".

## Architecture

Adder is Flow's generic foundation under the chat domain (0.2.0 to 0.6.0). The foundation: **JWT auth**
with a sliding refresh pair and a server-side revocation blocklist, opt-in **email MFA**, self-service password reset,
per-account lockout and per-IP rate limits, **ADMIN-managed users** (`/api/v1/users`, `UserRole`) with per-user **feature flags**
and a synced UI/email **language** (EN/PL), flat **teams** with an optional manager (a room adds a whole team at once), the ADMIN
**telemetry** panel, and the React shell (nav model, palette, theme, changelog). Every feature follows the feature template below.

Multi-module Gradle build (Kotlin DSL) defined in `settings.gradle.kts` with two Kotlin modules
plus two standalone npm workspaces (Gradle never touches them):

- **`core`** — Kotlin Multiplatform (JVM target only currently). Shared code consumed by `server`.
  Holds the OpenTelemetry SDK bootstrap (`getOpenTelemetry(serviceName)`).
- **`server`** — Kotlin/JVM. The Ktor application. Depends on `core`. One process: there is no worker and no role switch.
- **`web/`** — Vite + React + TypeScript SPA that consumes the server's HTTP API.
- **`e2e/`** — Playwright blackbox suite against a named stack.

Group is `ch.nokillswit`, version `1.0.0-SNAPSHOT` (inherited from the donor scaffold; renaming is out of scope). Dependency
versions are centralized in `gradle/libs.versions.toml` (every pin carries its rationale; Ktor comes from the separate `ktorLibs`
catalog). Resolved Gradle dependencies use strict locking and SHA-256 verification; intentional updates follow
`.claude/docs/dependency-reproducibility.md`.

### Server bootstrap model

`server/src/main/kotlin/main.kt` just delegates to `io.ktor.server.netty.EngineMain`. The
application is wired declaratively in `server/src/main/resources/application.yaml` under
`ktor.application.modules` — each entry is a fully-qualified extension function on `Application`
(e.g. `ch.nokillswit.plugins.HttpKt.configureHttp`). **Module order is load-bearing**: base plugins
(Http → SecurityHeaders → Monitoring → Serialization → Security → RateLimits → ErrorHandling → OpenTelemetry → AutoHeadResponse → Resources → Sockets) →
infra (Mail → Crypto → Flyway → Database → Bootstrap → Health) → Telemetry (the sampler) → feature route modules (`configureLoginThrottle`, users, teams, workflow templates, tag categories, rooms ×3, threads, messages, reactions, read markers, watches, thread tags (`configureThreadTagRoutes`), pins, notifications ×3, search, `configureMessageTagReindex`, live, auth, password reset, telemetry reads) → `RoutingKt.configureRouting` strictly last (the SPA catch-all). `configureDatabase` publishes the services into
`Application.attributes` via `AttributeKey`s. To add a cross-cutting concern, create a `configureXxx()` extension under `plugins/` and register it
in `application.yaml`; do not call it from `main.kt`. There is no DI framework — services travel via `attributes`.

### Package layout

Source files sit flat under `server/src/main/kotlin/<area>/` but declare `package ch.nokillswit.<area>`
(no `ch/nokillswit` directory nesting — a deliberate idiom, the `InvalidPackageDeclaration` detekt
override). One line per package; `ls` gives the files. **Layering is one-way (`PackageBoundaryTest`):** `infra/` imports no feature package
except the composition root (`infra/db/Database.kt`, `Bootstrap.kt`); `telemetry/` is imported by nothing.

- `plugins/` — cross-cutting Ktor wiring (`configureXxx` that only `install` plugins; Security = JWT, ErrorHandling = RFC 7807). `Routing` (the SPA catch-all) is registered strictly last; `Health` after Database; `RateLimits` holds every bucket, per IP and per user (`security.md`).
- `infra/mail/` — outbound email (Lettuce's, ported): `MAIL_TRANSPORT` log/smtp/disabled; the `log` transport is refused in production (fail-closed); a null mailer = email features answer 503.
- `infra/crypto/` — encryption at rest (Lettuce's, ported): `FieldCipher` (AES-256-GCM `enc:v1:` envelopes, current + rotation key), `configureCrypto` (`DATA_ENCRYPTION_KEY`; a burned key fails startup closed). No consumer yet; Flow's `EncryptedAtRest` registry and `Reencrypt` come back with the first encrypted column.
- `infra/db/` — Flyway bootstrap, the R2DBC composition root (`Database.kt`, `ChatWiring.kt`), the seed bootstrap (admin rotation, prod fail-closed), `SoftDelete.kt` (ONE `active()` predicate, `lockActiveForWrite`), `Sql.kt` (`containsNormalized`, the only substring filter), `EventLog.kt` (the paged per-record history; the actor table is passed in), `Jsonb*.kt`, `Fts.kt` (V16), `MultiRowInsert.kt`, `Locking.kt` (`lockingTransaction`, the tag registry's only), `StatementTimeoutCache.kt` (only `queryTimeout` may set `STATEMENT_TIMEOUT`), `LeakedSocketGuard.kt`, `DetachingConnectionFactory.kt` (pool hygiene), `NotifyConnectionFactoryKey` (the listener's own factory); `persistence.md`.
- `infra/paging/` — the shared list-endpoint machinery (Lettuce's, ported verbatim; `list-endpoints.md`).
- `infra/validation/` — cross-feature input helpers (`sanitizeSingleLine`: trim + control-char 400; `sanitizeMultiLine`: message bodies, `\n`/`\t` kept).
- `infra/json/` — `CanonicalJson.kt` (canonical JSON + sha256 for stored payloads), `JsonParams.kt` (string-map event params).
- `infra/notify/` — the live channel's wire: `LiveKind`/`LiveHint` (ids only), `notifyLive` (one `pg_notify('adder_live', …)` per change branch, inside the write's transaction), `NotifyListener` (the one non-pooled LISTEN connection, started lazily by the live hub) and `LiveStatsKey` (the telemetry seam); `live.md`, `persistence.md`.
- `infra/revision/` — `X-Expected-Revision` for whole-document PUTs (Toadie's, header REQUIRED): `expectedRevision()` (400), `requireRevision` (typed 409 `urn:adder:problem:revision-conflict`).
- `infra/time/` — `Millis.kt`, `MILLIS_PER_DAY`, the one fixed-24h-day constant.
- `infra/config/` — `requireConfigInt`/`requireConfigLong`, boot-validated numeric config (Lettuce's).
- `infra/Failures.kt` — `catchingFailures` (keeps the failure without swallowing cancellation).
- `audit/` — the security audit trail: `audit(event, fields…)` → AUDIT-marked structured logs.
- `authz/` — `CallerPrincipal`, the guards (`requireAdmin`, `requireSelfOrAdmin`, `requireTeamManagerOrAdmin`, `requireRoomReader`, `requireRoomMember`, `requireRoomManager`, `requireThreadSteward`, `requireMessageAuthor`, `requireMessageRemover`), `AccessToken` (the token's jti, exp and credentialRevision, stashed by `validate`) and the typed HTTP exceptions (401/403/404/409/429).
- `auth/` — login (+ the email-MFA second step), refresh, logout, self-service password reset, token minting, password hashing, the revoked-token blocklist, the shared lockout (`AccountLockout.kt`); in-memory auth state is capacity-bounded with audited 429 saturation.
- `users/` — Adder's login accounts (REST `/api/v1/users`): ADMIN-only CRUD (self-delete 403, last-admin 409), per-user feature flags (`Feature` enum, the V5 disabled-set model; MFA is the inverted-default login-scoped flag), the ONE synced per-user UI+email language (`Languages.kt` = `SUPPORTED_LANGUAGES`).
- `teams/` — flat teams (V6) with an optional manager, a roster (V10) and a mention `handle` (V25): GET any authenticated, record writes ADMIN only, roster PUT/DELETE ADMIN or the manager. **The feature template** (below).
- `tags/` — the ADMIN tag registry (V32, 1.2.0; Hammurabi's port): `/api/v1/tag-categories`, GET any authenticated, writes ADMIN under `X-Expected-Revision` and `infra/db/Locking.kt`'s table lock (that registry only); `persistence.md` "Tag categories (V32)".
- `workflows/` — the workflow vocabulary (`StatusColour`, `StatusCategory`, `FieldType`, the limits) and `WorkflowRules.kt`, the pure kernel, and the ADMIN template registry (`WorkflowTemplate*.kt`, V11/V12: a `jsonb` document under `X-Expected-Revision`); a room's workflow shares the kernel (`chat-model.md`); rooms import `workflows`, never the reverse.
- `rooms/` — rooms (V13): members-only (a non-member gets a uniform 404; ADMIN reads every room), created from a workflow template whose statuses and fields the room COPIES. Every room write runs under `withRoomWrite` (`RoomLock.kt`); members `RoomMember*.kt`, the room's workflow `RoomWorkflow*.kt` (via the `WorkflowUsage` port, implemented by `threads/ThreadWorkflowUsage`); read state (V19). Rules: `chat-model.md`.
- `threads/` — threads, messages and the history (V14): `ThreadService`, `ThreadStatusService` (the steward rule), `ThreadLock.kt` (`withThreadWrite`/`withMessageWrite` inside `withRoomWrite`), `Message*.kt` (one quote per message, V35: `MessageQuotes.kt`; the three reads share `MessageAssembly.kt`), hashtags (V15), mentions (V17), the `ThreadNotices` port, search (V16), pins (V21), read markers, unread counts (V19), watches (V26) and group mentions (V30, `GroupMentions.kt`, the pure `NoticeAudience.kt`), no room lock, `ThreadEventService` over `infra/db/EventLog.kt`, the pure kernels `ThreadFields.kt` and `ThreadStatusRules.kt`; curated thread tags (V33, `ThreadTagService`); imports `rooms/`, `teams/` (`TeamService`) and `tags/` (the registry's `Tags` table, read), never the reverse (`chat-model.md`, `persistence.md`).
- `live/` — the live-update channel (`live.md`): `LiveTickets` (single-use first-frame tickets), `LiveRoutes.kt` (`POST /api/v1/live/tickets`; `GET /api/v1/live`, public at HTTP, per-IP `live-connect`), `LiveSocket` (auth, subscribe, close codes), `LiveHub` (one actor: subscriptions, hint fan-out), `LiveSweep` (60 s account and membership verdicts); imported by nothing (`PackageBoundaryTest`).
- `notifications/` — notices (V18, `/api/v1/notifications`): ids-only rows made INSIDE a write through `threads/`' `ThreadNotices` port (`NoticeWriter`, wired in `ChatWiring.kt`), the caller's own list, the two "seen" POSTs, an hourly retention sweep, twelve kinds (`NoticeKinds.kt`, `NoticeJudge.kt`: in-app on, email off by default), the email digest (V22), the per-actor budgets (`NotificationEmailBudget.kt`) and `/users/{id}/notification-preferences`; a top layer nothing imports (`PackageBoundaryTest`).
- `telemetry/` — per-process JVM/cgroup/request/storage samples in `ops.*` (V9), 90 days, hourly, ADMIN read API (`observability.md`).

The chat domain so far: `workflows/` and `rooms/` (0.2.0), `threads/` (0.3.0; `ThreadWorkflowUsage` implements the rooms port); `live/` (0.4.0); `notifications/` (0.6.0); 0.5.0 added no package (hashtags and search live in `threads/`, `infra/db/Fts.kt`).

**Feature template — copy `teams/`** (a small ADMIN-curated registry; the only shape the foundation ships). Before adding a feature, route,
service, migration or page, read `.claude/docs/conventions.md` in full.

### The OpenAPI contract

`server/src/main/resources/openapi/documentation.yaml` is hand-maintained and authoritative: every endpoint change edits it in
the same commit. The server suite validates every test-client `/api/` interaction against it (`OpenApiConformance.kt`,
`-Dopenapi.conformance=fail`); the frontend derives its types from it (`npm run gen:api` → committed `web/src/api/schema.ts`,
regenerated in the same commit). The file says `openapi: 3.1.0` but must use only 3.0-compatible constructs
(`OpenApiSpecTest`). `AnonymousAccessTest` sweeps every operation without `security: []` for a 401 — the "401 sweep" — so a new
route is covered the moment its spec entry lands. `info.version` equals `APP_VERSION` (`app-releases.md`).

### Cross-cutting conventions

Two docs apply to nearly every change and are always loaded:

@.claude/docs/testing.md
@.claude/docs/list-endpoints.md

Every other convention doc is **read on demand, not imported** (together they exceed the instruction-size budget) but binding:
**before touching an area, read its doc in full** (and name it in any implementer/reviewer brief). When a doc and this file
disagree, the doc wins. The always-loaded set (this file plus the two imports) stays under 36,000 bytes (`AlwaysLoadedDocsBudgetTest`).

| Doc | Read before you touch |
|---|---|
| `.claude/docs/conventions.md` | adding a feature, route, service, migration or page: the `teams/` feature template (DTO + validator, routes, service, migration + checksum pin, spec paths, `gen:api`, lazy page + nav entry, e2e spec + scenario) |
| `.claude/docs/persistence.md` | any migration (`db/migration/V*.sql`, V1–V35 today — applied bytes are immutable, `MigrationChecksumTest`), an Exposed table/`*Service.kt`, soft delete, a cross-feature table read/write (the list there IS the permission), the connection pool, the `ops` schema |
| `.claude/docs/security.md` | auth/JWT/MFA/password reset, rate limits, headers/CSP, secrets/encryption at rest, production fail-closed startup checks, payload validation |
| `.claude/docs/authorization.md` | any route: its guard (ADMIN vs any authenticated), 403-before-400/404 ordering, existence disclosure, the error/`ProblemDetail` mapping |
| `.claude/docs/observability.md` | any mutation or denial path (it needs an `audit(...)` event, listed there), logging, OpenTelemetry, health probes, the telemetry panel's server side |
| `.claude/docs/ci.md` | a CI job (ids and list), Dependabot rule or static-analysis config; the full-stack/Kubernetes notes and the sibling port table |
| `.claude/docs/product.md` | the per-version roadmap, the user's decisions, assumptions; what each donor repo contributed and what to port next |
| `.claude/docs/chat-model.md` | the chat domain (rooms, membership, workflows, threads, messages, history, mentions, reactions, pins): the sketch, refined per version |
| `.claude/docs/live.md` | the WebSocket live-update channel (tickets, protocol, LISTEN/NOTIFY fan-out, bounds): binding since 0.4.0 |
| `.claude/docs/test-fixtures.md` | any server test fixture, the harness, fork warm-up, OpenAPI conformance internals, e2e scenario files, Schemathesis |
| `.claude/docs/web-internals.md` | the `web/` transport/session layer, user/feature-flag pages, language switcher, colour tokens/logo, changelog wiring |
| `.claude/docs/dependencies.md` | any dependency, image or runtime-pin change |
| `.claude/docs/dependency-reproducibility.md` | lockfiles or `gradle/verification-metadata.xml` (the empty-`GRADLE_USER_HOME` rule) |
| `.claude/docs/app-releases.md` | a version bump, changelog entry, tag or GitHub release |
| `.claude/docs/build-times.md` | any CI/build/test-time change; when a gate gets slower (`scripts/timings/`, budgets, re-baselined 2026-11-20) |
| `.claude/docs/audit-status.md` | the checkup record (audited, fixed, parked); start the 1.0.0 checkup here |

A new doc under `.claude/docs/` gets a row here, not an `@` import.

### Frontend (`web/`)

See `web/CLAUDE.md` for the frontend conventions (flat directories, co-located tests, typed i18n
with EN/PL parity, the transport layer, theming — the olive `adder` brand is the interactive accent
only; red = blocking, orange = warning, gray = neutral, blue = in progress, grape = waiting, teal = success).
