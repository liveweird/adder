### Test fixtures and harness internals

The binding one-line rules are in `testing.md`; this file is the reference behind them. Adder has the harness, the OpenAPI conformance layer, the fork warm-up and the
watchdogs, the weight-balanced fork order and the fixture-tripwire machinery — Flow's domain fixtures (the synced and derived stub fixtures, the normalized-layer sweep)
were not ported. When a chat increment adds a shared fixture, its rules (warm in a `@BeforeClass`, read-only vs clone, the guard test, the tripwire) are added here in the same commit.

**Runtime OpenAPI conformance.** Every `/api/` interaction the server test suite produces is
validated against `documentation.yaml` by the `OpenApiConformance` Ktor client plugin
(`server/src/test/kotlin/OpenApiConformance.kt`), installed via the shared test-client defaults —
so `jsonClient()`/`authedClient()` traffic is checked automatically and drift (undeclared
endpoint/method/status, response-schema or content-type mismatch) fails the exercising test with
the validation report. Response side is fully validated; request-side validation is ignored except
unknown-path/method (tests deliberately send malformed payloads and missing tokens). The spec is
fed to the validator relabeled 3.0.3 in memory (swagger-request-validator's 3.1 support is
unreliable); `OpenApiSpecTest` pins that the document stays 3.0-compatible (use `nullable:`, never
`type: [..., "null"]`) plus static invariants (unique operationIds, 401/500 declared, paths under
`/api/v1/`). `-Dopenapi.conformance=warn|off` relaxes enforcement for drift triage only (default
`fail`). A coverage report (exercised vs. declared operation/status pairs) is written to
`server/build/reports/openapi-conformance/coverage.md` after each test run, and it is a GATE: the
sibling `gaps.txt` lists every declared (operation, status) pair the suite never produced — minus
the cross-cutting statuses a shared plugin answers for every route alike and one test pins each
(`400` malformed id/body, `401`, `413`, `415`, `429`) and `500`/`default`, which the public API
offers no honest way to force — and `server/build.gradle.kts` fails the `test` task on a
non-empty file whenever the WHOLE suite ran (a `--tests` filter skips the gate). The gate is
**fork-safe**: each test JVM writes its own `exercised-<pid>-<uuid>.txt` and re-merges every fork's file
under a file lock into the ONE `coverage.md` + `gaps.txt` (`OpenApiCoverageMerge`, pinned by
`OpenApiCoverageMergeTest` — a pair exercised by ANY fork is covered, so the last fork to exit
leaves the complete union), and the `test` task clears the directory first so stale per-fork files
never leak in; a whole-suite run that leaves NO `gaps.txt` fails the gate too. A new operation
therefore lands with a test per declared status, or with its status list trimmed to what the
route can actually answer (`CoverageGapsTest` pins the cross-cutting statuses). `413` is declared on EVERY operation that takes a request body
(`OpenApiSpecTest` pins it) but is never a per-operation test — the one `PayloadValidationTest` case covers the shared
limit. Tests that use
`testApplication`'s default `client` bypass the plugin — prefer `jsonClient()`.

**Schemathesis (optional manual fuzz pass, not in CI).** Property-based fuzzing of the running
stack from the spec: `docker compose up --build` (compose ships dev mode, so `/openapi` is
exposed), grab a token —
`TOKEN=$(curl -s -X POST localhost:8087/api/v1/login -H 'Content-Type: application/json' -d '{"email":"admin@adder.local","password":"changeme"}' | jq -r .token)`
— then
`uvx schemathesis run -c all -H "Authorization: Bearer $TOKEN" --exclude-path /api/v1/logout http://localhost:8087/openapi/documentation.yaml --url http://localhost:8087`.
The `/logout` exclusion is load-bearing: fuzzing it **revokes the bearer token** (everything after
401s). Login fuzzing also trips the per-account lockout for `admin@adder.local` (the spec's
example email) — in-memory, so `docker compose restart app` clears it. Expect residual noise from
stateful invariants the spec cannot express (rate-limit 429s, TRACE probes); a **`Server error`
count above zero is the real signal**. It complements, not replaces, the suite-piggybacked
conformance layer above; fuzz junk lives only in the compose volume (`docker compose down -v`
resets). Needs `uv` (or `pipx`); no Python dependency lives in the repo.
**E2E scenarios (design artifacts).** The Playwright suite in `e2e/` is governed by
`e2e/README.md` (run recipes, the parallel state-ownership rulebook, the coverage map). Every spec
has a **natural-language scenario file** in `e2e/scenarios/` — versioned, deliberately
non-executable design artifacts (actors, owned state, numbered user-level steps, expected
outcomes; `## Scenario:` headings equal the `test()` titles verbatim). `e2e/scenarios/README.md`
holds the format and the **compiler contract** — the house rules any human/agent/tool must
satisfy when turning a scenario into spec code. Same-commit rule: a new or behaviorally changed
test lands with its scenario file and its one-line entry in the e2e README's coverage map;
`cd e2e && npm run check:scenarios` enforces the parity mechanically (both directions, orphan
files included), and `npm run typecheck` covers what Playwright's transpile-only TS handling
never checks. CI also runs `cd e2e && npm run test:setup`; the setup tests cover reuse of a
healthy stack, startup of the unavailable default stack, and the invariant that services and
volumes remain intact. E2E global setup reuses a healthy stack, starts the default stack when
unavailable, and leaves services and volumes intact. (Flow's shared read-only synced fixture, exported to the specs as `E2E_SHARED_*`, is not ported; the chat increments add a deterministic dataset and its own fixture rules here — `e2e/README.md` "Parallel execution".)

**Balanced fork order (`-Pforks>=2`).** Gradle's `MaxNParallelTestDefinitionProcessor` deals candidate classes to
the forks round-robin in visit order (position `p` goes to fork `p % forks`), all up front, with no work stealing;
the scanner visits each directory in sorted order. `server/build.gradle.kts` therefore replaces `testClassesDirs`
with a composite of one file tree per candidate class (a composite is visited in declaration order and keeps
package-relative paths) plus a per-directory catch-all for everything that is not `**/*Test.class`, so the candidate
set equals the plain tree's. The order comes from `balancedCandidateOrder`: classes sorted by weight, descending
(ties by name), dealt greedily to the least loaded fork that still has a free slot (each fork's slot count is capped
at its round-robin share, so the interleaving reproduces the plan), then position `p` takes fork `p % forks`'s next
class. The weights are `server/test-weights.tsv` (`<FQN><TAB><seconds>`, suite time minus the fixture-cost markers,
written by `local-times.mjs test-weights` from a full run; the committed file was re-measured from a full run on 2026-11-21 — refresh it at every milestone check
and when the CI summary's fork-balance block warns). Degradation: a missing or unparseable file warns once and keeps the alphabetical order; a class not in the file weighs the average; a deleted class's row is
ignored. `--tests` filters work unchanged. The file is not a task input (only the class trees are). A red local rerun is reordered by Gradle's `RunPreviousFailedFirst` (failed
classes go first), so the balance is off for that rerun. `slow-tests --forks N --weights server/test-weights.tsv` prints the fork-balance block (span vs sum / N, drift of measured
against recorded weights; the CI `server` job and `scripts/gates.sh` run it).

**The pinned head: fork warm-up (every `-Pforks`).** The first class of a JVM pays the container, Flyway and the first
Ktor boot, and when it runs in `testApplication` that is inside the hard 60 s limit. `ForkWarmup.kt` moves it into a
`@BeforeClass` (JUnit 4 does not time it): `ForkWarmup.warm()` migrates the container (`ensureMigrated()`), boots the app
once via `TestApplication { configureApp() }` and throws it away (marker `app-boot`, beside the `postgres` marker in `TEST-ch.nokillswit.ForkWarmup<k>Test.xml`); `ForkWarmup.assertWarm()` is the
one test of each `ForkWarmup<k>Test`. There are eight such classes (`ForkWarmup1Test`..`ForkWarmup8Test`, one per
`-Pforks` value 1..8) because Gradle deals one class FILE to one fork and collapses a repeated class name, while
`TestClassNamingTest` allows one concrete `*Test` class per file and forbids an abstract `*Test` base. The `testClassesDirs`
Callable puts `ForkWarmup1Test`..`ForkWarmup<forks>Test` at positions `0..forks-1` (one file tree each), so the round-robin
deal gives fork k its own `ForkWarmup<k>Test` as the first class it runs. `balancedCandidateOrder` (via `isForkWarmup`) never
plans a warm-up class; the surplus `ForkWarmup<forks+1..8>Test` stay out of the run through the per-dir
`exclude(**/*Test.class)` catch-all in the weights path and through `exclude(FORK_WARMUP_GLOB)` in the plain path (`-Pforks=1`
or no weights file). The Callable never throws: before `compileTestKotlin` ran, or while the compiled tree is stale
and lacks a needed `ForkWarmup<k>Test.class`, it returns the plain dirs (so `--dry-run` works) and is evaluated again at execution; the test task's `doFirst`, after the
compile, then fails a run whose class is still missing (naming the file; "run clean" if the source exists). Not covered: a `--tests` run drops every
warm-up class (no test in it matches), and a failed-first rerun (`RunPreviousFailedFirst`) may open a fork with another
class; both still open cold, which is fine for a focused run. The **tripwire** (`ForkWarmupLedger`, next to the stall
watchdog in `server/build.gradle.kts`) records each JVM's first class and the result of every `ForkWarmup*Test`; a FULL run
(the OpenAPI gate's condition: no `--tests`) fails unless exactly `ForkWarmup1..forksTest` ran SUCCESSFULLY in `forks`
distinct JVMs, and only warns about a JVM whose first class was not its warm-up. If the previous run's warm-up class failed, Gradle runs failed classes first and can deal two
warm-ups to one fork, so the distinct-JVM check can fire once on that rerun: rerun. It guards the Gradle-internals assumptions (round-robin dealing, in-order forwarding,
class-name dedupe). The throwaway boot runs `Bootstrap` on a fresh database before any test: no test may assume it is the fork's first boot.

**Fixture tripwires.** `FixtureTripwire.kt` (pinned by `FixtureTripwireTest`) is the machinery for a shared, read-only fixture connection: the fixture arms it with the baseline it captures
when its own init completes, and every later `connectionId()` call re-snapshots the connection and compares, so a consumer that mutates the shared state fails the NEXT test that asks
for it, whatever class order the fork runs in (`perCallerClass` re-checks an expensive snapshot only when the calling test class changes; after a failure the baseline is re-captured,
so one mutation fails ONE test). No fixture uses it yet (Flow's synced and derived stub fixtures did); the first shared chat fixture arms it and adds its explicit tripwire test here.

**Coverage gates.** Backend Kover enforces line- and branch-coverage floors in
`server/build.gradle.kts` (the `minBound` line/branch floors, wired into `check` via
`koverVerify`). The measurement EXCLUDES classes annotated `@kotlinx.serialization.Serializable`
(`kover.reports.filters`): kotlinx-serialization synthesizes one branch per optional property in a
data class's generated constructor and serializer — noise no test can exercise; behavior never
lives in a wire shape (services, validators and a DTO's companion object stay measured). The
floors sit just below current actuals — the convention is to **re-measure and raise** them as
coverage improves, never to lower them for new code: `check` runs only `koverVerify`, so run
`./gradlew :server:koverXmlReport` for fresh actuals. Every fork's Kover agent writes into the one shared `test.ic` (if CI ever flakes on Kover, fall back to `-Pforks=1`).
Kover costs roughly a tenth of the test wall and stays on everywhere `check` runs — the floors are a gate. There is **no
Gradle property to switch it off**; for a timing run or a tight dev loop over `:server:test` alone, temporarily add
`kover { currentProject { instrumentation { disabledForTestTasks.add("test") } } }` to `server/build.gradle.kts` and never commit
it (no `test.ic` is written, so `koverVerify` would have nothing to check). The floors (lines 99, branches 88; actuals 99.19/88.28 on the merged 1.0.0 follow-ups) were re-baselined after the trim (97/81) and raised for 0.4.0, 0.5.0, 0.6.0 and the checkup, and rise again as coverage improves. Frontend vitest enforces thresholds in
`web/vite.config.ts` (`test.coverage.thresholds`, same re-measure convention — the current
actuals are noted in a comment beside them); run `cd web && npm run test:coverage`.

**Server test setup and forks.**

Integration tests live flat in `server/src/test/kotlin/` (kotlin.test + `io.ktor.server.testing.testApplication`; only `auth/` nests: in-package unit tests of `auth` internals)
and override the `postgres.*` config keys via `MapApplicationConfig` to point at a Testcontainers
`PostgreSQLContainer("postgres:18.6-alpine@sha256:9ba53c7d…")` (the same digest `docker-compose.yaml` and
`k8s/postgres-deployment.yaml` pin — `PostgresImagePinTest`) started lazily by `PostgresTestSupport` and **shared
across the whole suite within a JVM fork** (one container per fork; test-side direct database access, `sharedDatabaseForTests()`, goes through a small
r2dbc-pool built by production's own `connectPooledDatabase` — an unpooled connect paid ~4 ms of backend
setup per transaction). Running tests requires a working Docker daemon (Docker Desktop,
OrbStack, etc. — with OrbStack and no `/var/run/docker.sock`, export
`DOCKER_HOST=unix://$HOME/.orbstack/run/docker.sock`). The container runs **all** Flyway
migrations, so the V3 seed admin (`admin@adder.local`) is present — tests scope their assertions
with unique prefixes/filters (`uniqueEmail("marker")`) rather than asserting absolute counts.

**Parallel forks (`-Pforks=N`).** `server/build.gradle.kts` sets `maxParallelForks` from the `forks` Gradle
property (an integer 1..8, anything else fails any build that runs the `test` task; default 1 = one JVM; CI and `scripts/gates.sh` run `-Pforks=2`; `forkEvery` is deliberately unset). Each fork is its own
JVM, so it starts its OWN Testcontainers Postgres (`PostgresTestSupport` is a per-JVM `object`) and holds its own
fixture singletons — every "shared suite" rule in this doc
(`withSoloAdmins`, `restoreSeedAccounts`, the seed admin) therefore holds per fork, never across forks; a test must never assume another class ran before it
on the same JVM, or that a class ran at all in this one. Gradle hands whole CLASSES to forks in whatever order it
likes, so **class order is not a contract**: any class may be the first thing a fork runs after the fork's pinned
`ForkWarmup<k>Test` (a `--tests` run or a failed-first rerun has no such pin and opens cold). The container is migrated by `PostgresTestSupport` itself the moment it starts (a `Flyway.migrate()`
in its lazy init, the same call as `infra/db/Flyway.kt`), so a fixture or raw-JDBC helper never needs its own
"has Flyway run yet?" guard (`PostgresTestSupport.ensureMigrated()` is the explicit spelling). The OpenAPI gate above
merges the forks' coverage.

**Stall watchdog (`-PtestStallMinutes=N`).** Why: on 2026-10-17 (CI run 39062260538) a hung test left the `server` job silent until the
25-minute timeout cancelled it, and a cancelled job uploads no `server-reports` artifact, so there was no evidence. `TestStallWatchdog` (top of the `test`
task's section in `server/build.gradle.kts`) is a Test-task listener that tracks, per test JVM (the "Gradle Test Executor N" suite in a descriptor's parent chain),
the time of the last suite/test start or finish. An executor that has started, not finished and been quiet for the threshold gets (1) `jcmd <pid> Thread.print -l`
(the `jcmd` of the test task's `javaLauncher`; the test JVM's pid is found among Gradle's descendants by its command line, which ends in `'Gradle Test Executor N'`)
written to `server/build/reports/hung-tests/Gradle-Test-Executor-N-<yyyyMMdd-HHmmss>.txt` and logged at ERROR with the class/test in flight, then (2) its
JVM is destroyed, so the task fails ("Process 'Gradle Test Executor N' finished with non-zero exit value 137") once the other forks have run their
queues, instead of at the CI job timeout (`jcmd` itself is bounded at 30 s, so a JVM too wedged to attach to is still killed). The
directory is cleared at the start of each run; CI uploads `server/build/reports` with the `server-reports` artifact when the job is not cancelled, and the
dump is in the log either way. `-PtestStallMinutes` is an integer 1..60 (default `DEFAULT_TEST_STALL_MINUTES` = 5; the measurement behind it - carried over from Flow - is in the comment on the constant); every executor also logs `test watchdog: … longest quiet gap N s (in flight: …)` when it finishes, which
is the number to re-measure the default from. The quiet gap is a single test's run time or a class's `@BeforeClass` fixture warm-up, nothing else.
The matching source-side bound: every raw JDBC connection a test opens comes from `PostgresTestSupport.connect()` (pgjdbc `connectTimeout`/`loginTimeout` 30 s,
`socketTimeout` 180 s, `tcpKeepAlive`), never a bare `DriverManager.getConnection`: a call parked in a socket read cannot be cancelled, and the 180 s bound is
above any legitimate statement (a lock wait behind another transaction) and below the watchdog, so one half-dead connection fails ITS test with a stack trace.
**The `TestEnvironment.kt` harness** — use it instead of hand-rolling setup:

- `configureApp(vararg overrides)` (on `TestApplicationBuilder`, so `ForkWarmup.warm()` can use it outside `testApplication`) points the app at the shared container (with CSRF off)
  WITHOUT starting it — tests that assert startup behavior (the fail-closed checks) add their
  overrides and call `startApplication()` themselves; `usePostgresTestcontainer()` is the
  configure-and-start shorthand. It sets `mail.appUrl` to `https://localhost:8087` (the origin the emailed reset link is built
  from, validated at boot in every mode - so `https`, which the production-mode boot tests, reusing this config, also accept); a test
  that wants the blank-origin 503 or a malformed origin overrides it.
  It sets `telemetry.enabled` to `false` (the sampler would write a process row and samples into the shared database from every booted app):
  the telemetry tests (`TelemetrySamplerTest`, `TelemetryStoreTest`) build the sampler directly over `TelemetryFixtures.kt`'s helpers and empty `ops`
  before each test (`wipeTelemetry`; nothing else writes there); a test that wants the real sampler opts in with `"telemetry.enabled" to "true"` plus
  `TelemetryOverridesKey` (a fixed identity, an immediate first tick).
  It also sets `threads.tagReindexOnBoot` to `false` (the boot reindex of the hashtag index, V15, would walk every message of the shared database; the purge of deleted messages' tag rows still runs at every boot, so a test that plants such rows awaits `MessageTagReindexKey` first): `MessageTagReindexTest` calls `MessageTagService.reindexStale()` directly, and its boot tests opt in with `"threads.tagReindexOnBoot" to "true"` and read the run's result from `MessageTagReindexKey`; they seed their stale messages (a real post, then its tag rows deleted and `tags_version` reset by raw SQL) BEFORE the first client call, because the first client starts the application and so the reindex. `server/src/test/resources/hashtag-cases.json` is the hashtag grammar's shared case table (`HashtagsTest` here, the SPA parser's test there); a case is `{name, body, tags}` where `tags` is the distinct (case-folded, first spelling kept) list of at most 20.
- `server/src/test/resources/mention-cases.json` (`{name, body, ids}` plus optional `teams`, `everyone`, `watchers`, absent meaning none): the ONE statement of the mention grammar, read by `MentionsTest` and by the web parser's `utils/mentions.test.ts`, like `hashtag-cases.json`. `MessageDerivedRowsTest.arrangedByTheBody` lists the derived tables a test body arranges a row in (`message_tags`, `message_mentions`, `message_group_mentions`, …).
- `ForkWarmup.warm()` / `ForkWarmup.assertWarm()` (`ForkWarmup.kt`): the fork's untimed Postgres + first-boot warm-up, called only by the `ForkWarmup<k>Test` classes; no other test calls it.
- Password-reset fixtures: `newPasswordResetService(ttlMillis, clock)` builds a `PasswordResetService` over the shared database (an
  injected clock for expiry), `issueToken(userId, revision, email)` unwraps its `IssueOutcome` to the emailed token (null when stale
  or capped), and `resetTokenHashes(userId)` lists the stored digests (the table never holds a raw token).
- `jsonClient()` / `authedClient(email, password)` — the standard HTTP clients; both go through
  the shared test-client defaults (JSON + `application/problem+json` negotiation + the
  `OpenApiConformance` plugin), and `authedClient` logs in and attaches the bearer on every
  request.
- `LogCapture(loggerName)` + `hasKeyValue` — a Logback `ListAppender` for asserting the audit
  trail (`ch.nokillswit.audit`); `awaitEvent` polls for asynchronously produced events. Detach in
  a finally.
- `TestUsers.seed(email, password, name, role)` (bcrypt cost 4 for speed; defaults to ADMIN — pass
  `UserRole.USER` for a non-privileged caller) — every seeded user funnels through
  `UserService.create`, so they carry the inverted-default MFA-disabled row and log in
  single-step and `TestUsers.softDelete(id)` (direct table update, bypassing the delete endpoint's
  guards) and `TestUsers.withSoloAdmins(ids) { }` (temporarily parks every other active admin —
  the last-admin-protection pin). `seededClient(prefix, role)` is the one-line seed+login
  fixture.
- `postJson`/`putJson` (the JSON body ceremony) + `HttpClient.login` (the raw login POST),
  `withAuditCapture { }` (attach/detach on the audit logger), `withSeedRestored { }` and
  `assertStartupFails(part) { }` for bootstrap/fail-closed tests — use these instead of re-rolling
  the blocks they replaced.
- **Many failed logins → seeded accounts.** A login for an UNKNOWN email pays a discarded cost-12 bcrypt
  verify (the timing equalizer, ~225 ms locally); a test that needs a dozen failures (rate limit,
  proxy trust) logs in as freshly seeded accounts (`TestUsers.seed`, cost 4) with a wrong password —
  the same 401 path at ~1 ms; soft-delete them afterwards.
- `TestTeams.seed(name)` — a fresh team fixture; beside it, raw-row readers for what
  the API hides (`TestTeams.rawRows`, `TestUsers.stampPasswordChangedAt`). Shared
  suite state (the seed admin) is never mutated destructively — tests mint UNIQUE rows and remove
  their own.
- **Tag fixtures** (`TagFixtures.kt`, 1.2.0 S6; Hammurabi's, without its ADR halves): `categoryName()` and `uniqueTag()` mint names no other test uses (a tag name is unique for ever, retired rows included, and the registry is shared state); the client helpers `createCategory`, `postCategory`, `saveCategory`, `deleteCategory`, `categoryRevision` and `registryTags` drive the routes as the caller; every category a helper creates is remembered by `TestTags`, and a test class calls `TestTags.purge()` in an `@AfterTest` (a raw delete, the `thread_tags` rows of its tags first (V33), then the tags: the seeded Kind and Area are never touched, and a test may purge its rooms before or after). `TestTags.categories` is the registry's table for the cap test's direct inserts.
- **Room fixtures** (`RoomFixtures.kt`, 0.2.0): `TestTemplates.seed(prefix)` mints a unique bilingual template (statuses `open` initial, `half` OPEN, `done` CLOSED; fields `severity`
  SELECT s1/s2, `note` TEXT) and `TestTemplates.seededId(name)` finds a V12 seed; `TestRooms.seed(ownerId, templateId, name, language)` creates a room through the real service path, with
  raw helpers beside it (`addMember`, `setRole`, `archive`, `seedMembers(room, count, marker)` for the 500 cap, `seedRetiredKeys(room, count)` for the 200-key budget, `rawRooms`,
  `rawStatuses`/`rawFields` for the retired rows the API hides, `purge`, `softDeleteUsers`); `seedActor(prefix, role)` seeds a user and logs them in (a `RoomActor`: id + client). Every test
  mints UNIQUE rows and removes them in an `@AfterTest`.
- **Thread fixtures** (`ThreadFixtures.kt`, 0.3.0 S2): `TestThreads.seedTemplate(prefix)` mints a unique template (statuses `open` initial, `monitoring` OPEN, `done` and `wontdo` CLOSED; fields `severity` SELECT REQUIRED, `note` TEXT,
  `lead` USER, `due` DATE, `tags` MULTI_SELECT) and `TestThreads.seed(roomId, userId, title, body, fields)` starts a thread through the REAL create (service, so the opening message, the `CREATED` event
  and the activity bump exist; `severity` is supplied unless `fields` says otherwise). Raw helpers beside it: `raw(roomId, number)` / `rawAll(roomId)` (the thread rows), `rawMessages(threadId)`,
  `rawEvents(threadId)`, `roomActivity(roomId)`, `closeRaw(roomId, number, statusKey)` (a closed thread by raw update, past the status route's guards), `moveRaw(roomId, number, statusKey)` (an OPEN status such as `monitoring`, closing nothing) and `setStamps` (distinct
  `created_at`/`last_activity_at` for a sort test: a seed loop shares a millisecond). `TestThreads.events.record(...)` appends a history event (inside a `suspendTransaction`). `TestRooms.purge` cascades
  into threads, messages and events, so a test only purges its rooms. `awaitLockWait(fragment, waiters = n)` waits for `n` sessions blocked on a lock (race 1: two creates at once), and `TestThreads.holdingRoomLock(roomId, statement?, ids…) { raw -> }` is the shared race shape: a raw transaction holding the room lock (and running `statement`, e.g. a revision bump or a membership delete) until the body commits it (races 2-4).
- **Quiet rooms** (`NotificationFixtures.kt` `TestNotices`, 1.1.0 S6): a seeded thread now tells the members already in the room (`THREAD_CREATED`) and makes its creator a watcher, so a test that counts a person's notices as 1.0.0 did starts with `TestNotices.quietRoom(room)` (the room's notices and watches deleted; `clearRoom` for the notices alone). `NoticeWideEventTest` times the 499-recipient insert; `NotificationBroadcastBudgetTest` and `ThreadWatchNoticesTest` are the S6 classes.
- **Notification preferences** (`NotificationFixtures.kt`, 1.1.0): email is OFF by default for every kind, so a test that needs a recipient in the digest or in the email budget opts them in with `TestNotificationPreferences.optInEmail(userId, types)` (raw `INSERT ... ON CONFLICT DO UPDATE`, `enabled = true`); `set(userId, type, channel, enabled)` stores one switch and `clear(userId)` goes back to the defaults. `NoticeWriterTest` drives the `ThreadNotices` port with every reason; `NoticeAudienceTest` is the pure audience kernel's table.
- **Read state** (`ThreadReadTest`): a membership and the first message after it can share a millisecond and an unread message is strictly NEWER than the member's floor (`GREATEST(read_from, added_at)`), so the test waits a few milliseconds between the two (`SETTLE`). A marker is read straight from `thread_reads` over `PostgresTestSupport.connect()`; the lock-free write is shown with `TestThreads.holdingRoomLock(room)` (the PUT answers at once) and the removal race with the same helper running a raw `DELETE FROM room_members` (the PUT waits on the member row, then answers 404). The upgrade rule runs the migration file's own `UPDATE`, read from the classpath and scoped to one room. `ThreadSchemaTest` pins the two unread statements' plans (`THREAD_UNREAD_SQL`, `UNREAD_COUNTS_SQL`, `internal` and visible to the test source set) as a custom and as a generic plan.
- **The follower seam** (`threads/MessageFollower.kt`, `persistence.md` "Threads (V14)"): `TestThreads.messages` and `TestThreads.service` run with the mention follower and the pin cleanup over the real `NoticeWriter` (`TestThreads.noticeWriter`; so a mention seeded through them makes its notice like a route's would, and `TestThreads.notices` is the `NotificationService` read side that lists it; `MessageService` always adds the reactions' own, so a delete clears a message's reactions whoever built the service). A test of a follower builds its own `MessageService(db, TestRooms.service, MessageTagService(db), MessageMentionService(members, TestThreads.noticeWriter), listOf(follower))` and a `ThreadService` over it (`MessageDerivedRowsTest`'s `Services`; the constructor and the interface are `internal`, visible to the test source set). The recording follower in that test stores each call with the message row as the write saw it (a query in the follower's own transaction) and as ANOTHER connection saw it at that moment (`committedRow`: null for an insert still uncommitted, the old body for an edit), which is the proof that it ran inside the write and after the row's statement; a failing follower proves the rollback. A table a migration adds that references `messages` makes `a deleted message keeps no row in any table that references messages` fail until the test arranges a row in it (the `arrangedByTheBody` set and its comment): the catalog, not the author, decides which tables count. The recording follower runs after the write's counters and live hint (`MessageFollower`), and `NotifyHintsTest` pins the wire order of a notice's hint after the write's own. `NotificationSweepTest` drives `NotificationSweep` with an injected clock and purge lambda (`notifications.sweepEnabled` is false in the harness; the boot test opts in).
- **Watching** (`ThreadWatchTest`, V26): `TestThreads.watches` is the `ThreadWatchService` the seeded thread writes add the creator's auto-watch through (`TestThreads.service` takes it as a constructor argument, as does `MessageDerivedRowsTest`'s `Services`); a watch is read straight from `thread_watches` over `PostgresTestSupport.connect()`. The removal race is the read marker's (`TestThreads.holdingRoomLock` running a raw `DELETE FROM room_members`, `awaitLockWait("room_members")`: the PUT waits on the member row, then answers 404), the lock-free write the same helper with nothing run, and the inert watcher of a soft-deleted user is read through `suspendTransaction(sharedDatabaseForTests()) { TestThreads.watches.count(thread) }`. `ThreadSchemaTest` pins `WATCHING_AMONG_SQL`'s plan (`idx_thread_watches_user_room`, auto and generic) and the table's key and cascades.
- **Teams you can mention** (1.1.0): `TestTeams.seedMentionable(prefix, members)` makes a team with a unique handle and a roster (`MentionableTeam.token` is `<@team:id>`); `TestGroupRows` reads `message_group_mentions` raw. `GroupMentionNoticesTest` drives group mentions and their edits over HTTP (a 500-member room, the wide-event budget through `configureApp("notifications.maxBroadcastsPerActorPerDay" to "1")`); the relabel's one-statement batching is checked by `count(DISTINCT cmin)` of the rows.
- **Casts** (`ThreadCasts.kt`): the actors-plus-room shapes several chat classes shared, built by `seedRoomCast` (owner, member, outsider), `seedPairCast` (owner, ann, bob, outsider, optional thread #1) and `seedThreadCast` (owner, author, member, outsider, ADMIN, thread #1); each registers what it mints in the class's own `MintedIds(users, templates, rooms)` for its `@AfterTest`. A class whose cast differs (a matrix of callers, an extra thread) keeps a private `Cast`.
- **A session on a bare application** (`LiveSocketFaultsTest`): `liveSession` runs on an application with only the WebSocket plugin and one route, its own `LiveSessionDeps` (a blocklist or room service on an unreachable database, a clock seam), a hub that never starts its listener and tickets issued directly. Never mount a second socket route inside the real application: the body-limit wrapper in `plugins/Http.kt` exempts only the exact live path and starves any other socket of client frames.
- **Reactions** (V20): `server/src/test/resources/reaction-cases.json` is the case table of the one-emoji rule (name, the value as `\u` escapes, accepted), read by `ReactionsTest` only (the SPA picks from a list and validates nothing); a new case is a new row, never a code change. `MessageReactionsTest` seeds a crowded message by raw `INSERT` into `message_reactions` (`seedRaw`: the limit tests start from 19 or 20 emoji of one person, which the service would refuse) and proves the two limits under concurrent writers with the room lock held on a raw connection (`TestThreads.holdingRoomLock` plus `awaitLockWait("FOR NO KEY UPDATE", waiters = 2)`, then the commit releases both: exactly one fits). `ChatWriteLimitsTest` spends the `chat-write` bucket across the list of non-POST writes the test itself builds, so a new write is one more line there and the rounds adapt.
- **Live fixtures** (`LiveFixtures.kt`, 0.4.0 S4): `liveClient()` is `createClient { install(WebSockets) }` and is for SOCKETS ONLY, never for `/api/` HTTP (it bypasses the OpenAPI conformance plugin: a plain GET, a ticket mint or a write goes through `jsonClient()`/`authedClient()`/`RoomActor.client`, so the 400/403/429/503 of `connectLive` and the mint are validated against the spec); `HttpClient.mintLiveTicket()`; `openLive(client, origin = LIVE_TEST_ORIGIN, configure = {})` opens `GET /api/v1/live` with an `Origin` header (null sends none; `configure` adds request headers, e.g. an `X-Forwarded-For` to be another client address under `http.behindProxy`, which is how the per-address pending share is tested) and records the upgrade's `101` in the coverage report (`OpenApiCoverage.record("GET", "/api/v1/live", 101)`: the conformance plugin cannot wrap an upgrade); `LiveTestSocket` (`auth`, `subscribe`, `unsubscribe`, `sendText`, `nextFrame`, `nextFrameOrNull`, `awaitClose`); `openAuthenticatedLive(client, actor)` (ticket, auth, `ready`); `awaitListenerUp(hub)` and `LiveTestSocket.nextNonResync()` (the first LISTEN's own gap signal is a `resync` that can arrive at any point); **`LiveTestSocket.settle()`** (a ping read up to its pong) before ANY `nextFrameOrNull(...) == null` assertion on a socket opened before `awaitListenerUp`: that `resync` can land on any socket registered before the hub handled the gap, so an unsettled silence check is a flake (it was `LiveRevocationTest > a new room reaches the creator's other socket`, 0.4.0 audit). The hub is `application.attributes[LiveHubKey]` (`socketCount`, `socketsOf`, `pendingAuth`, `isStopping`, `listener.backendPid`). **The marker trick:** NOTIFY is delivered in commit order, so "this write reaches nobody" is proven by doing it, then a known write the socket DOES hear, and asserting the next frame is the marker; to make a frame-order assertion after an `unsubscribe`, send a `ping` and await the `pong` first (frames are handled in order). No sleeps. `LiveHubTest` runs the hub with no database or application over fake `HubSocket`s (the actor is FIFO, so `beginSubscribe` is the barrier). `LiveSweep.sweepNow()` (`application.attributes[LiveSweepKey]`) runs the 60 s account sweep on demand and answers how many sockets it closed (a test that needs the loop itself builds `LiveSweep(hub, blocklist, rooms, intervalMillis = 100, retryDelayMillis = 50).start(scope)`; its `judge` and `lost` parameters are the seams that make one half fail, `LiveRevocationTest`); a 4001 test shrinks the token with `jwt.accessExpiresInSeconds` (3 s leaves room for the login and the mint). `LiveHub.deafAfterMillis`/`deafCheckMillis` (internal, 10 s / 1 s in production: `LiveListenerOutageTest` sets 0 / 25 ms BEFORE the first mint, which starts the watcher); `LiveAuditTest` runs the audit coalescing on a fake clock (`RateLimitAuditThrottle(clock = …)`). `LiveHub.beforeCommand` (internal, null in production) holds the actor still so a test can pile up hints and commands behind it; the Ktor test host does NOT cancel a server handler when the client drops, so a session cancelled mid-register is proven at the hub (an `unregister` queued behind a held `register` leaves zero), not through a socket.
  **The two-instance pattern** (`LiveFanOutTest`): a second `TestApplication { configureApp() }` is started inside a `testApplication` and stopped in a `finally`; tickets are per process, so the second instance logs in and mints for ITSELF and the socket is opened on it, while the first instance writes through its REST clients; both LISTEN on the shared container. A killed LISTEN connection is `pg_terminate_backend(hub.listener.backendPid)`.
- **The populated upgrade** (`PopulatedUpgradeTest`, checkup C-TEST-3): creates its OWN database (`upgrade_<uuid>`) on the fork's container, migrates it with Flyway `target` = V24 (1.0.0's last migration), fills every table through `src/test/resources/upgrade/populated-v24.sql` (plain V24 SQL, a 1.0.0 database: the history already in the shape V23 leaves; it never changes, an applied schema does not; it replaced the 0.6.0 `populated-v22.sql` so the 1.0.0 to 1.1.0 path is the one exercised, and V23's scrub is `ThreadHistoryScrubTest`'s), migrates to the latest, then checks the history, per-table row counts and row digests (byte-identical, every table), V25's handle column (`SELECT count(*) FROM teams WHERE handle IS NOT NULL` = 0, soft-deleted rows included), V26's backfill (exactly each thread's creator and current owner who still hold a membership row, the soft-deleted member's row included, an owner who left the room excluded, each stamped with its thread's creation, no notice made; then `watching` and `watcherCount` through the API), and boots the app on it (`configureApp` with that database's URLs) for authed reads under the OpenAPI conformance; the database is dropped in a `finally`. A migration this test breaks is the bug, not the test. The source is the previous release only: an upgrade that skips a release (0.6.0 straight to 1.1.0, without 1.0.0's V23 and V24) is not exercised.
- **The usage seam** (`WorkflowUsage`, `rooms/WorkflowUsage.kt`): `Database.kt` registers the real `ThreadWorkflowUsage` under `WorkflowUsageKey` and the routes read `attributes[WorkflowUsageKey]` per request (`NoThreadsYet` is gone),
  so an ordinary room test counts and moves real threads (`ThreadWorkflowUsageTest`). A test that needs counts without threads overwrites the key with `FakeWorkflowUsage(counts)` (fixed per-status counts, every move recorded in `moves`
  and applied to the counts) BEFORE the app starts: `configureApp()`, `application { attributes.put(WorkflowUsageKey, fake) }`, `startApplication()` (`RoomWorkflowTest`). Ktor runs the `application.yaml` modules (so `configureDatabase`'s
  `put`) BEFORE the test's `application { }` block, hence the fake wins; `RoomTest`'s override test would fail otherwise. `RoomWorkflowTest` keeps covering the retire paths (`status-in-use`, the mapping, `threadsMoved`) through the fake.
- `TestSeedState.restoreSeedAccounts()` — bootstrap/production-mode tests rotate the seed admin's
  password in the SHARED container; call this afterwards so later tests (and re-runs) see the
  pristine V3 state. Production-mode boots must also override `"mail.transport" to "disabled"` —
  the dev-default `log` transport is refused in production (`MailTransportTest`) — and
  `"security.encryption.key" to strongEncryptionKey()` — the dev-default data-encryption key is
  burned (`CryptoBootTest`); the checks fire in module order (JWT → mail → crypto → DB password → seed
  passwords), so a test asserting a later check must satisfy every earlier one.

**Frontend test internals** (moved from `testing.md`; the binding rules stay there).

**Frontend tests.** Vitest + happy-dom + Testing Library, **co-located** next to the source
(`Foo.test.tsx` beside `Foo.tsx`). `src/test/setup.ts` imports `../i18n` and forces `en`, so text
assertions match the English resources; `src/test/render.tsx` is the shared wrapper and — like
every file-local `MantineProvider` — must pass **`env="test"`** (since Mantine 9.4 the
Popover/Combobox dropdown is `display: none` until Floating UI sees a real bounding box, which
never happens in happy-dom, so Select-option clicks silently fail without it). `src/test/http.ts`
holds the fetch-stubbing helpers. The shared setup also forces the reduced-motion media query and
makes every Mantine test provider honor it: `Transition` invokes its animation hook even with
`env="test"`, so synchronous reduced-motion transitions prevent callbacks from outliving happy-dom
teardown. Other media queries retain their normal behavior; the application theme is unchanged.
`setup.ts` also raises testing-library's `asyncUtilTimeout` to 4 s (and `vite.config.ts` the `testTimeout` to 10 s): the
module registry is cleared per file, so the first test of a shuffled file to touch a lazy chunk pays its cold
import, which exceeds the 1 s `findBy`/`waitFor` default on a contended machine. A lazy read is still always awaited
(`findBy…`/`waitFor`) — a synchronous `getBy…` after the page's own controls appear is the bug, not the timeout. To
reproduce shuffle flakes run several `npx vitest run --sequence.shuffle --sequence.seed=N src/pages src/components`
in parallel (a quiet single run hides them).

**The suite runs with `isolate: false`** (`web/vite.config.ts` — a vitest process reuses its module registry and
globals across files). Vitest already scopes `vi.mock`
registrations per test file; what carries over is the EVALUATED `src/` modules, so `setup.ts` calls
`vi.resetModules()` before every file (npm packages stay cached) and each file's own mocks apply. Tests must not assume anything an earlier test left
behind — await lazy chunks (`findBy…`/`waitFor`, never a synchronous `getBy…` right after the first
data assertion), reset module-level state in `afterEach`, and unstub globals/timers a test installed. The
proof is `cd web && npx vitest run --sequence.shuffle` (run it a few times after adding a test); a test
that only passes in file order is the bug, not the config.
