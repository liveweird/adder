# Audit status

The checkup record: what was audited, what was fixed, what was parked. The first checkup is the **1.0.0 checkup** (2026-11-20, below).

Flow's three checkups (2026-09-30, 2026-10-06, 2026-10-23) audited the donor code this repository was trimmed from; their findings and fixes are Flow's record
(`~/Sources/flow/.claude/docs/audit-status.md`) and were not carried over, because most of what they covered (the data-pipeline and reporting features) is not here.

## Method

One `checkup-lead` plans the run and adjudicates it: one `auditor` (and a `security-auditor` for authn/authz) per dimension — docs and specs, DRY/SRP/SOLID, unnecessary
complexity, conventions, tests, UI consistency, authn/authz, guardrails, architecture and infrastructure, API spec, smells and dead code — in parallel, after a `gate-runner`
baseline. Every high/medium claim is re-read against the code before it is kept. Each finding keeps an id, a severity, `path:line` evidence and a proposed fix; the findings become
`BACKLOG.md` items, and the fixing PRs are listed here under "Fixed so far".

## 1.0.0 checkup (2026-11-20, master @ `a0ca3e3`, tag `v0.6.0` @ `ba1bfd9`)

**Run.** 13 sub-agents: one `gate-runner`, eleven `auditor`s (docs split three ways; complexity and dead code shared one), one `security-auditor`. Read-only; the tests auditor
ran the stability probes. 174 findings returned, 88 high or medium, all 88 re-read: 8 dropped, 1 partly dropped, 15 downgraded or moved to decisions, duplicates merged.

**Not audited.** The Playwright suite (the compose stack on :8087 was left alone; e2e judged statically and from three green nightlies); the security pass was static only and did
not read `WorkflowTemplateService`, `TeamService`, `RoomWorkflowPlan`, the telemetry store, `web/src/api/http.ts` or `web/src/utils/liveClient.ts` in full; `security.md` and
`persistence.md` were swept by catalog, not sentence by sentence; no browser was opened; unused `libs.versions.toml` entries were not checked.

**Gate baseline.** `scripts/gates.sh all` green: server-build 105 s (budget 180), web-gates 27 s (budget 90; TREND +37 %), e2e-static 3.1 s. Server 160 classes, 1280 tests; Kover
line 98.72 % (floor 98), branch 87.37 % (floor 87); detekt 0. Web 192 files, 1978 tests; coverage 98.96 / 96.69 / 99.18 / 99.77 against 98 / 96 / 99 / 99. Stability probes: vitest
shuffled with three seeds, server `-Pforks=1` and `-Pforks=2`, all green; the `LiveSweep` flake did not reproduce. CI medians over target: `images` 3m10s (2m30s), `web` 1m58s
(1m30s), `gradle-vulnerability-scan` 1m08s (1m), nightly e2e 7m46s (7m). The push run on `a0ca3e3` was red (C-GATE-1).

**Verdict per dimension.** Every dimension: *mostly* sound. Domain docs match the code both ways (80/80 audit events, 17/17 rate buckets); chat authorization holds on all 72
operations (no cross-room IDOR, injection, stored XSS or bypass found); code and spec agree 72 to 72; boundaries enforced; 0 TODO/FIXME; EN/PL parity complete.

Paths: `S/` = `server/src/main/kotlin/`, `T/` = `server/src/test/kotlin/`, `D/` = `.claude/docs/`, `spec` = `server/src/main/resources/openapi/documentation.yaml`.

### Findings

| id | sev | evidence | problem | proposed fix |
|---|---|---|---|---|
| C-SEC-1 | high | `S/plugins/Http.kt:242` | `install(Compression)` decodes request bodies by default (`maxDecodedContentLength = -1`) after the wire-byte body limit, so one anonymous gzip `POST /login` under 16 KiB can inflate past the heap. Confirmed from source and bytecode, not executed. | `mode = CompressResponse`; gzip and gzip-of-gzip tests in `PayloadValidationTest`/`ChatWriteLimitsTest`; a line in `security.md`. |
| C-SEC-2 | medium | `S/users/UserRoutes.kt:301-332` | Self password change: a bcrypt verify per request, no bucket, no lockout. | Per-user bucket + count wrong `currentPassword` in `LoginThrottle`; spec 429. |
| C-SEC-3 | medium | `S/auth/MfaChallenges.kt:61-71`, `S/auth/AuthRoutes.kt:233-241` | MFA attempts bounded per challenge only; a fresh login gives a fresh challenge, the account never locks; `login.mfa_failure` has no user id. | Cap live challenges per user, count failed codes into the lockout, add `userId`. |
| C-SEC-4 | medium | `S/plugins/RateLimits.kt:282-287`, `S/plugins/Http.kt:287-302`, `k8s/app-deployment.yaml:88-91` | Per-IP keys are the raw forwarded text; the manifest trusts the proxy behind a bare LoadBalancer, so a client picks its own key. | Key through `pendingAddressKey`; manifest half waits for a decision. |
| C-SEC-5 | medium | `S/threads/ThreadService.kt:270-275`, `V14__create_threads.sql:57` | Details PUTs append raw from/to values into append-only history inside a 240/min bucket; a member can fill the volume (computed). | A product decision (with the M4 "scrub a leaked secret" item). |
| C-SEC-6 | low | `S/threads/ThreadRoutes.kt:109-116` vs `D/chat-model.md:65` | A retired field's values stay readable in the history. | Filter at read time or state the exemption. |
| C-SEC-7 | low | `S/rooms/RoomRoutes.kt:143,153`, `RoomMemberRoutes.kt:106,128`, `RoomWorkflowRoutes.kt:32` | Five room-lock writes carry no bucket (deliberate per `security.md:311`). | Decision. |
| C-SEC-8 | low | `S/notifications/NotificationEmail.kt:72-73` | Nine literal bidi/separator characters in source. | Write as `\uXXXX`; a source-hygiene test. |
| C-SEC-9 | low | see the checkup report | Seven hardening items (denial audit coalescing, `SMTP_STARTTLS=false` in production, COOP/CORP, open egress 443, `+1` path ids, raw login email in audit lines, the roster guard under the lock); not re-read. | One hardening commit. |
| C-GATE-1 | high | branch protection 404, rulesets `[]`; run 44673472719 `images` | Nothing requires a green run; dependency resolution failed in 3 of 8 master runs with no retry. | Retry resolution in Dockerfile and CI; a ruleset is a decision. |
| C-GATE-2 | medium | v0.1.0, v0.2.0, v0.3.0 tagged on red runs | Against `app-releases.md` "wait for CI". | A `gh run list --commit` step in the checklist. |
| C-GATE-3 | medium | `.github/workflows/ci.yml:44-55`, `T/AlwaysLoadedDocsBudgetTest.kt:35` | A Markdown-only PR skips the job holding the docs budget test; 9 bytes left. | Exempt the always-loaded docs from the skip. |
| C-GATE-4 | medium | `scripts/timings/budgets.json`, `D/build-times.md:107-112` | Four CI rows over target and a TREND flag with no status check. | First status check, re-baseline. |
| C-GATE-5 | medium | GitHub settings | Public repo, Dependabot alerts and secret scanning disabled. | Decision. |
| C-GATE-6 | medium | 13 closed Dependabot PRs, branches left | Dependabot lands nothing unaided (Gradle verification metadata). | Delete branches; decision on automation. |
| C-GATE-7 | medium | `.github/workflows/ci.yml:359-366` | Only a red `images` opens an issue; issue #14 open since 2026-11-18. | One alert job needing every job. |
| C-GATE-8 | medium | `e2e/playwright.config.ts:22`, `e2e/tests/mfa.spec.ts:56`, `password-reset.spec.ts:45` | Nightly green on retry-only passes; MFA and reset specs skip without Mailpit. | Fail on flaky count; require Mailpit in global setup. |
| C-GATE-9 | low | runs 44672658427, 44673472719 | The BACKLOG npm-audit item is stale (step green). | Removed from BACKLOG. |
| C-GATE-10 | low | `config/detekt/detekt.yml:63,68`, `web/tsconfig.app.json`, `T/PackageBoundaryTest.kt:34-36,101` | A detekt exclude for a nonexistent file; implicit TS strict; no foundation→chat boundary rule. | Small fixes. |
| C-ARCH-1 | medium | `S/live/LiveRoutes.kt:79-90` | The live scope is cancelled on `ApplicationStopped` with no join, after the pool's dispose; the likely cause of the `LiveSweep` flake. | Cancel and join on `ApplicationStopping`. |
| C-ARCH-2 | medium | `k8s/postgres-data-persistentvolumeclaim.yaml:14` | No backup or restore procedure. | Decision. |
| C-ARCH-3 | low | `server/build.gradle.kts:85-96` | No direct-memory cap (suspected). | Measure, then cap. |
| C-ARCH-4 | low | `application.yaml:5,327`, `S/infra/db/Database.kt:126` | Dev mode default outside the image; production tolerates the default DB password. | Decision. |
| C-ARCH-5 | low | `k8s/app-deployment.yaml:92-97` | Mail comment omits the digest; no `preStop`. | Comment fix. |
| C-TEST-1 | medium | `T/LiveRevocationTest.kt:456`, `T/LivePendingTest.kt:169,247`, `T/ChatWriteLimitsTest.kt:189`, `T/ConnectionPoolTest.kt:136-137` | Wall-clock upper bounds with 350 ms–2 s margins. | Assert order or widen. |
| C-TEST-2 | medium | Kover: `LiveSocket.kt` 64/92, `AuthRoutes.kt` 71/95, `ErrorHandling.kt` 47/64 branches | Weakest coverage on socket error closes and auth/error paths. | Tests, then raise floors. |
| C-TEST-3 | low | `S/auth/PasswordResetRoutes.kt:68`, `e2e/scripts/check-scenarios.mjs` | `password_reset.throttled` unasserted; no populated-upgrade migration test; `check:scenarios` skips `accessibility.spec.ts`. | Add assertions. |
| C-TEST-4 | low | `T/LiveSocketTest.kt:124-130` | A 1 s token may race its expiry (suspected). | 3 s token. |
| C-DOC-1 | medium | `README.md:8,10,48`, `web/README.md:3-5` | READMEs describe 0.4.0. | Rewrite. |
| C-DOC-2 | medium | `D/product.md:276-278,262-270,102`, `D/test-fixtures.md:118` | No v0.6.0 release entry; stale donor plans; floors 98/86. | Update. |
| C-DOC-3 | medium | `CLAUDE.md:95,69` | Module order omits eight modules and misorders some; "chat domain still to be built". | Byte-neutral fix. |
| C-DOC-4 | medium | `D/build-times.md:10`, `D/dependencies.md:20` | "There is no remote yet". | Drop. |
| C-DOC-5 | medium | `README.md:162` | 15 of 72 environment variables missing (0.4–0.6). | Add rows. |
| C-DOC-6 | medium | `D/app-releases.md:5-7,28-33` | Doc says per-PR bumps; practice is per milestone; v0.6.0 tagged two fixes after the release commit. | Decision, then align. |
| C-DOC-7 | medium | `D/conventions.md:31-32` | "Deliberate exceptions: none"; `live/`, `notifications/`, `auth/` string routes unrecorded. | Record them. |
| C-DOC-8 | medium | `D/live.md:183,131`, `D/product.md:295`, `D/chat-model.md:200,270` | Four stale facts (`readableRoomIds` signature, 5 s vs 2 s auth deadline, 15 vs 19 branches, sort whitelist). | One-line fixes. |
| C-DOC-9 | medium | `D/web-internals.md:357,239,311`, `web/CLAUDE.md:77,28` | Five web-doc contradictions. | Small fixes. |
| C-DOC-10 | low | `S/telemetry/TelemetryStore.kt:31,38,69,232` and others | Donor migration numbers and "WHY n" references. | Renumber. |
| C-DOC-11 | low | `D/testing.md:17`, `D/test-fixtures.md:73`, `D/build-times.md` | Weights file called "the donor's"; forks range 1..3. | Reword. |
| C-DOC-12 | low | `e2e/README.md:53,59`, `web/CLAUDE.md` | Web/e2e nits (not re-read). | One sweep. |
| C-API-1 | medium | `spec:2124-2126`, `S/notifications/NotificationService.kt:209-212` | Capped `total` not in the list-endpoints ledger. | Ledger line. |
| C-API-2 | medium | `spec:2634-2654` | Shared 429 prose wrong ("all five" for eight routes). | Rewrite. |
| C-API-3 | medium | `spec:1375,1380` | Search `q` description contradicts the 400. | Fix. |
| C-API-4 | medium | `spec:3506-3534` | Fifteen problem URNs in prose only; no code↔spec registry. | Registry + diff test. |
| C-API-5 | medium | spec property names | Seven styles for person references; mixed boolean forms. | Decision before the tag. |
| C-API-6 | medium | `spec:1346-1349` vs `:2559-2575` | `tag` means substring on one route and exact repeatable on another. | Decision before the tag. |
| C-API-7 | medium | `spec:2886-2888` vs `S/live/LiveFrames.kt:51,71` | Ack ids `int32`, minimum 0; code accepts `UInt`, minimum 1. | `int64`, minimum 1. |
| C-API-8 | low | `spec:1069-1085` and others | Missing 400s and bounds (not re-read). | Spec hygiene. |
| C-DRY-1 | medium | `S/rooms/RoomRoutes.kt:91-94` and four more | Bounded `name` filter hand-rolled three times, diverged. | `optionalBoundedString`. |
| C-DRY-2 | medium | `LoginThrottle`, `PasswordResetThrottle`, `MfaChallenges`, `LiveTickets` | TTL expiry index written four times. | `ExpiringIndex`. |
| C-DRY-3 | medium | `NotificationSweep.kt:128-143`, `Telemetry.kt:74-82`, `MessageTagReindex.kt:52-61` | Background-job scope and join copied three times. | One helper (with C-ARCH-1). |
| C-DRY-4 | medium | `ReadLimiter.kt:93-103`, `ThreadSearchService.kt:94-114` | Budgeted-transaction wrapper twice. | One helper. |
| C-DRY-5 | medium | 14 test `Cast` classes; e2e helpers ×3 | Fixtures redeclared per file. | Move to shared helpers. |
| C-DRY-6 | low | see the report | `markedAsDeleted eq`, `System.currentTimeMillis()`, `sha256Hex` twice, lockout message thrice. | Cleanup. |
| C-SMELL-1 | medium | `S/infra/validation/PathGlob.kt` and six more | Dead Kotlin with no main caller. | Delete with tests. |
| C-SMELL-2 | low | `ResponseValidators.kt:20`, `.gitignore:46-50`, `web/package.json:5`, five i18n keys | Donor residue; three version strings. | Delete or correct. |
| C-CPX-1 | low | `MultiRowInsert.kt`, `server/build.gradle.kts:228-620`, `AuthRoutes.kt:141-225`, `NotificationDigest.kt` (400 lines) | Donor-sized machinery; a file on the cap. | Deferred. |
| C-CONV-1 | low | `NotificationPreferenceRoutes.kt:56-60`, `MessageTagService.kt:42-50`, `LiveRoutes.kt:100-150` | Inline validation; unpinned sort whitelists; unaudited ticket mints. | Small follow-ups. |
| C-UI-1 | medium | `ThreadList.tsx:151`, `RoomsRail.tsx:104`, `NotificationsButton.tsx:51,122` and more | Colourless badges render olive; counts render red; unread drawn four ways. | Decision on the cue, then a theme default. |
| C-UI-2 | medium | `web/index.html:12` | Every route titled "Adder" (WCAG 2.4.2). | `useDocumentTitle`. |
| C-UI-3 | medium | `Rooms.tsx:144,147`, `RoomMembers.tsx:226`, `WorkflowTemplates.tsx:123` | List dates in UTC, chat times in the browser zone. | `browserTimeZone()`. |
| C-UI-4 | medium | `e2e/tests/accessibility.spec.ts` | Axe sweep misses search, template edit, filter panel, conversation states, dark detail pages. | Add states. |
| C-UI-5 | low | see the report | UI polish (hand-rolled confirm, no Ctrl+Enter in new thread, English error branch, three paginations). | Polish pass. |

### Remediation tiers

- **Before tagging 1.0.0:** C-SEC-1; C-SEC-2 + C-SEC-3; C-ARCH-1 with C-DRY-3; C-SEC-4 (code half); C-GATE-1, 3, 7, 8; dead code and residue (C-SMELL-1, C-SMELL-2, C-GATE-10,
  C-DOC-10); the docs (C-DOC-1 to 5, 7 to 9, 11; C-GATE-4); spec prose (C-API-1, 2, 3, 4, 7).
- **Can follow:** C-TEST-1 to 3; C-DRY-1, 2, 4, 5, 6; C-UI-2 to 5; C-SEC-6, 8, 9; C-ARCH-3, 5; C-API-8; C-CONV-1; C-CPX-1.
- **Decisions for the user:** required checks on master (C-GATE-1); the k8s front door (C-SEC-4); thread-history growth and redaction (C-SEC-5, C-SEC-6, BACKLOG M4); API
  names before the freeze (C-API-5, C-API-6); the release process (C-DOC-6); room enrolment and digest mail with the unbucketed room writes (C-SEC-7); GitHub scanning (C-GATE-5);
  unused surface in 1.0.0 (`infra/crypto`, dev-mode default, default DB password; C-ARCH-4); backups (C-ARCH-2); Dependabot automation (C-GATE-6); the unread cue (C-UI-1).

## Fixed so far

The 1.0.0 checkup's fix-before-1.0.0 tier, on `fix/1.0.0-checkup` (2026-11-20):

| ids | commit | what |
|---|---|---|
| C-SMELL-1, C-SMELL-2 (part), C-GATE-10, C-DOC-10 | `a18f4cd` | dead code and donor residue deleted; `PackageBoundaryTest` keys exemptions by path and keeps the foundation off the chat packages |
| C-ARCH-1, C-DRY-3 | `ae9e7f3` | every background scope built by `infra/BackgroundScope.kt`, joined (bounded) on `ApplicationStopping` before the pool is disposed |
| C-GATE-1 (retry half), C-GATE-3, C-GATE-7, C-GATE-8 | `a43ed32` | `scripts/gradle-retry.sh` + `resolveAllDependencies`; the docs budget runs on Markdown-only PRs; one alert job for any red master job; Playwright fails on a flaky test and requires Mailpit |
| C-SEC-1, C-SEC-4 (code half), C-SMELL-2 (ETag seam) | `3c98030` | request bodies never decoded, a non-identity `Content-Encoding` is a 415; every per-IP bucket keyed by `infra/ClientAddress.kt` |
| C-API-1, 2, 3, 4, 7 | `8d70e37` | spec prose from the code; `infra/ProblemTypes.kt` and `ProblemTypesTest`; live frame ids int64, minimum 1 |
| C-DOC-1 to 5, 7 to 9, 11, 12; C-GATE-4 | `a961c12` | docs caught up to 0.6.0; the first build-times status check and re-baselined budgets |
| C-SEC-2, C-SEC-3 | `43e3557` | a `password-change` bucket and one per-account lockout for wrong current passwords and wrong MFA codes; at most three live challenges per account |
| C-SEC-3 (rest), branch audit M1, L1-L7 | `4fed5b9` | a known account's wrong code or password counts even at a full lockout store; security headers on the early 415/400; join bounds fit the 20 s stop window; the AppCDS list regenerated |

Still open: the rest are `BACKLOG.md` items under "1.0.0 checkup" (the can-follow tier and the decisions).
