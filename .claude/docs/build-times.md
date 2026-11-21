### Build, test and CI times

Adder starts as a small app: a tiny database, no external integration in the tests, a few hundred source files. **Everything should be fast.** When a build, test or CI
job is not, the job is to find out WHY — not to raise a timeout. This doc is the measuring kit, the
budgets and the investigation log. Read it before you change a gate, a CI job, the
test harness (fixtures, forks, Kover) or anything that makes a gate slower.

> **Restart at v0.1.0.** Flow's history (its WHY investigations, status checks and measured figures, ~430 KB) was not carried over: the numbers described a different app and
> would mislead here. The method and the budget mechanics below are kept. **The budgets in `scripts/timings/budgets.json` were re-baselined on 2026-11-20** (the 1.0.0 checkup,
> "Status check, 2026-11-20" below): every row whose `why` says "Measured" carries a target set from a series; a row that still says PROVISIONAL (CI `server-tests`, the `ci-steps`
> rows, local `server-test` and `e2e-playwright`) has no series yet. Flow's `~/Sources/flow/.claude/docs/build-times.md` is the donor for any technique (statement-timeout cache, batched inserts,
> fork balancing, the ESLint cache, AppCDS): read its WHY before re-inventing one, port it when the same symptom shows up.
>
> **"WHY n" in a code or workflow comment** (e.g. `build-times.md WHY 3`, 17, 24, 26, 27) is the donor's numbering: it points at that WHY entry in Flow's file, not at this one. Adder's own
> entries start a fresh series below and are cited as `Adder WHY n`.

## How to measure

Three tools, node built-ins only (no dependency; `scripts/` is outside the `web/` and `e2e/`
workspaces, so knip and eslint never see it). Budgets live in ONE place, `scripts/timings/budgets.json`.

**Remote — `node scripts/timings/ci-times.mjs`** (needs an authenticated `gh`; read-only calls):

```
node scripts/timings/ci-times.mjs --limit 20            # last 20 successful runs per workflow (ci, e2e)
node scripts/timings/ci-times.mjs --branch master       # the trunk only (PR noise excluded)
node scripts/timings/ci-times.mjs --steps web           # + the slowest steps of that job's latest run
node scripts/timings/ci-times.mjs --workflows e2e --steps e2e   # the nightly's per-step rows (build / start / specs)
node scripts/timings/ci-times.mjs --tsv                 # raw rows: created, sha, event, branch, job, seconds
```

`--steps JOB` also shows each step's target/alarm and an `over target`/`OVER ALARM` flag when the step has a
row under `ci-steps` in `budgets.json` (keys `<job>/<step name>`, the names the workflow gives its steps; today
the nightly `e2e` job's five steps).

Per job: latest, median of the last 5, median of the 5 before, min/max, the budget, and flags —
`TREND +N%` (recent median more than 25 % above the previous one and, once the median is 60 s or more, by at least 10 s too — below that the rule is relative-only),
`over target`, `OVER ALARM`. A `(run wall)` row is the time to green. Runs that were not successful
are listed separately (cancelled/failed counts, and `TIMEOUT?` for a job stopped right at a
`timeout-minutes` value — those never enter the medians).

**Local — `node scripts/timings/local-times.mjs`:**

```
node scripts/timings/local-times.mjs record server-build -- ./gradlew cleanTest check :server:installDist -Pforks=2
node scripts/timings/local-times.mjs record web-gates -- sh -c 'cd web && npm run lint:api && npm run check:api && npm run lint && npm run knip && npm run test:coverage && npm run build'
node scripts/timings/local-times.mjs report                       # latest / median 5 / previous 5 / flags
node scripts/timings/local-times.mjs slow-tests                   # top classes + cases from server/build/test-results/test
node scripts/timings/local-times.mjs slow-tests --forks 2         # + the fork-balance block (needs a weights file, see "Fork balancing" below)
node scripts/timings/local-times.mjs test-weights                 # per-class weights of a full run -> server/test-weights.tsv (the committed file was re-measured on 2026-11-21)
```

**The one entry point — `scripts/gates.sh [server|web|e2e|all]`** (default `all`) runs the gates the way CI does,
each wrapped in `record`: `server-build` (`./gradlew cleanTest check :server:installDist`, with `JAVA_HOME=$(mise where java)` and the
OrbStack `DOCKER_HOST` set only when unset and the socket exists), `web-gates`, `e2e-static`. It stops at the first
failing gate with that gate's exit code, prints one line per gate and ends with `local-times.mjs report`. Prefer it
to typing the commands above, so every pre-commit run lands in the series. (Playwright is not part of it — it needs
a running stack.)

`record` runs the command, appends one row (ISO time, git short sha, branch, worktree, activity,
seconds, exit code, host cores) to `${XDG_DATA_HOME:-~/.local/share}/adder/timings.tsv` — outside the
repo, **shared by every worktree** — prints the verdict against the budget and exits with the
command's own code. Non-zero exits are recorded but excluded from the medians. Activity names are
free-form; the ones with budgets are `server-build`, `server-test`, `web-gates`, `e2e-static`,
`e2e-playwright` (anything else gets trend flags but no budget; the in-test targets below are not
`record` activities — Gradle and JVM start-up would swamp them). Record a full-gate run whenever you run one anyway (the pre-commit
`server-build`, `web-gates`) — the point is a series, not a benchmark session.
`slow-tests` also takes `--wall <s>` (the Gradle-reported total), `--top-classes`, `--top-tests`,
`--budget ci.server-tests` and `--summary-file`. Suite span and the sum of class times are both shown
(they agree with one fork; with `-Pforks=N` the span is the wall and the sum is N forks' CPU time, fixtures included).

**In CI** the `server` job ends its build with a "Test time report (job summary)" step (`if: always()`,
`continue-on-error`, never a gate): the top 15 slowest classes and top 10 cases go to the run's job
summary, and a `::warning::` annotation appears when the test time exceeds the `ci.server-tests` alarm (the test task's own budget; `ci.server` stays the whole job's).
It uses the runner's preinstalled `node` (the scripts need node >= 18; ubuntu-24.04 ships 20+).

## Budgets

The ONE place for the numbers is `scripts/timings/budgets.json` (`target` = what it SHOULD take for this app; `alarm` = the hard ceiling; a `why` per row; `ci`, `ci-steps` and
`local` sections). A target is a claim about the app ("tiny DB, no integrations → this cheap"), and a miss is a question to answer, not a number to adjust. CI runners are
public-repo `ubuntu-24.04` (4 vCPU, 16 GB); local figures are for the dev machine. The rows (re-baselined 2026-11-20; seconds target/alarm — the JSON is the source of truth, this table only
orients you):

| Activity | Target | Alarm |
|---|---|---|
| CI `changes` | 15 s | 30 s |
| CI `server-tests` (PROVISIONAL; the test task alone; the CI warning compares test time with this) | 3 min | 8 min |
| CI `server` (compile + detekt + tests + Kover) | 5 min | 10 min |
| CI `web` | 2.5 min | 4 min |
| CI `e2e-static` | 30 s | 1 min |
| CI `gradle-vulnerability-scan` | 1.25 min | 2 min |
| CI `images` (plain `docker compose build`, no layer cache, + Trivy) | 3.25 min | 4 min |
| CI `k8s-static` | 20 s | 45 s |
| CI `e2e` (nightly; its five `ci-steps` rows stay PROVISIONAL) | 8 min | 15 min |
| local `server-build` (`cleanTest check :server:installDist -Pforks=2`) | 2.5 min | 5 min |
| local `server-test` (PROVISIONAL, no series) | 2 min | 5 min |
| local `web-gates` | 45 s | 1.5 min |
| local `e2e-static` | 10 s | 30 s |
| local `e2e-playwright` (PROVISIONAL, no series) | 5 min | 10 min |

The targets were Flow's per-gate claims scaled down for a foundation-sized suite until 2026-11-20; "Status check, 2026-11-20" below sets each from its measured series and says why the four that moved moved. The rule for changing one is below ("Review cadence").

**Fork balancing (ported).** The `-Pforks` default is 1; `scripts/gates.sh` runs 2 (`ADDER_FORKS`, 1..8) and CI runs `-Pforks=2` (`TEST_FORKS`). The class order is weight-balanced from
`server/test-weights.tsv` (`test-fixtures.md` "Balanced fork order"); the committed weights were re-measured from a full run on 2026-11-21 (1.1.0), so **re-measure them** again at every milestone check
and whenever the CI summary's fork-balance block warns (`node scripts/timings/local-times.mjs test-weights`).

## Investigation log

A TREND flag or budget breach gets a dated entry here (the symptom, the evidence, the cause, the outcome), keyed by a running WHY number so a code comment can point at it
(`Adder WHY n`).

### Status check, 2026-11-20 (the 1.0.0 checkup; the first re-baseline)

**CI** (`node scripts/timings/ci-times.mjs --branch master`, 6 successful push runs on master from 2026-11-19 to 2026-11-20 and 3 nightlies; medians of the last 5, the way the script computes them):
`server` 4m44s (4m35s-5m29s), `images` 3m03s (2m50s-3m14s), `web` 2m16s (1m33s-3m02s), `gradle-vulnerability-scan` 1m06s (42-79 s), `e2e-static` 28 s (23-63 s), `k8s-static` 13 s (5-16 s), `changes` 7 s (4-11 s),
the nightly `e2e` 7m46s (6m45s, 7m46s, 13m10s). The time to green (`run wall`) is 5m03s. Four rows were over target (WHY 1 to 4 below); one master run failed and is not counted (C-GATE-1). The `server` job has since gained a step, "Resolve Gradle dependencies (bounded retry)" (`scripts/gradle-retry.sh ./gradlew … resolveAllDependencies`, `ci.md` "Transient resolution failures"), which downloads and verifies every dependency before the `check` build; it adds its own time to the job and takes some off the build step (the cache is warm), so the figure above predates it and has to be read again at the next status check (not re-measured here). **Local** (`~/.local/share/adder/timings.tsv`, `local-times.mjs report`): `server-build` median 86 s over 63 full runs (last 30: 92 s; 61-126 s on 18 cores),
`web-gates` 18 s over 132 successful runs (last 30: 23 s; 16-37 s), `e2e-static` 2.3 s over 30; `server-test` and `e2e-playwright` have no series. **Suite and floors** (the same checkup's `scripts/gates.sh all`): server 160 classes and 1280 tests, Kover lines 98.72 % (floor 98) and branches 87.37 % (floor 87); web 192 files and
1978 tests, coverage 98.96 / 96.69 / 99.18 / 99.77 against the 98 / 96 / 99 / 99 thresholds, so the floors stay. `server/test-weights.tsv` was re-measured on 2026-11-20 (a full run at `a6aa9b2`, `-Pforks=1`).

**How the targets were set.** Following "Budgets" above (a target is a claim about the app, a miss a question to answer): a row inside its target keeps it and only says "Measured" in its `why`; a row over its target gets an entry below, and its target moves only where the answer is a floor the original claim
ignored, set at the measured median rounded up to the next 15 s (CI) and with the alarm kept unless the new target left it less than a fifth of the alarm as headroom (only `web`, 2m30s against 3 min). The local rows, which the machine had far undershot (`web-gates` 18 s against 90 s), were tightened to about twice the series' median, so a real regression now shows as `over target` instead of hiding under a number five times too wide.

1. **WHY 1: `images` over target (3m03s against 2m30s) - ANSWERED 2026-11-20: the floor of a plain `docker compose build`, target 2m30s -> 3m15s.** The latest run's steps: "Build images" 2m14s (73 %, Gradle `installDist`, the Vite build and the AppCDS dump in Docker with no layer cache, the Flow decision of its WHY 9),
   the Trivy scan 42 s (23 %), the rest about 7 s. The spread over six runs is 2m50s-3m14s, so it is a floor and not a drift. No change to the job.
2. **WHY 2: `web` over target (2m16s against 1m30s) - ANSWERED 2026-11-20: the suite outgrew the "small SPA" claim, target 1m30s -> 2m30s, alarm 3 min -> 4 min.** The latest run (3m02s, the slowest of six) split into the shuffled-order coverage run 1m29s (49 %), `npm ci` 56 s (31 %) and the build 14 s; lint, knip and the API checks are a few seconds each.
   The suite is 192 files and 1978 tests run in shuffled order with coverage. The alarm moved because the worst of six runs touched 3m02s on a runner draw while the median is 2m16s. The ESLint cache is already restored in the job; the `npm ci` and the test time were not profiled further.
3. **WHY 3: `gradle-vulnerability-scan` over target (1m06s against 1m) - ANSWERED 2026-11-20: a runner draw, target 1m -> 1m15s.** The six runs range 42-79 s (the median of all six is 59.5 s). The latest run's steps: the settings-plugin audit 28 s (53 %) and the lockfile scan 17 s (32 %); the spread is the Trivy database download and Gradle's resolution.
4. **WHY 4: nightly `e2e` over target (7m46s against 7 min) - ANSWERED 2026-11-20: three runs, target 7 min -> 8 min.** The runs took 6m45s, 7m46s and 13m10s (the slow one on 2026-11-19, on the 0.4.0 release commit); the latest run's steps: "Run E2E" 2m59s, "Build images" 1m52s, "Install Playwright browser" 37s (over its 30 s step target), the Trivy scan 33 s, "Start the stack" 16 s.
   The same image build as WHY 1 is a quarter of it. The five step rows stay PROVISIONAL (one run's steps is not a series); re-read them after ten nightlies.
5. **WHY 5: local `web-gates` TREND flag (+37 % at the checkup's run) - ANSWERED 2026-11-20: load on a shared host, not a regression; target 90 s -> 45 s.** The series moves with how many worktrees and agents share the 18 cores: `137562e` took 27.3 s in one worktree and 18.9 s in another, and the median of the last 5 is now 19 s
   (the 5 before: 25 s), so the flag is gone. Below 60 s the trend rule is relative-only (25 %), which is a 5-second wobble at this size: the flag is a prompt to read the series, not a finding. The tightened target (45 s) is what would catch a real slowdown.

Open: the CI `server-tests` row and the local `server-test` and `e2e-playwright` rows (no series; record them with the next `scripts/gates.sh` and the first Playwright runs), the `ci-steps` rows (above), and a re-read of the `server` row once the resolve step has run a few times (it adds a step, and its net effect on the 4m44s is unmeasured).

## Review cadence

- Run `node scripts/timings/ci-times.mjs --branch master` at every milestone / PR, and in every
  checkup; run `local-times.mjs report` when a local gate feels slower.
- A `TREND` flag or a budget breach becomes a dated investigation entry in this doc (WHY, evidence,
  outcome) — **never just a raised budget.** A budget changes only with the measurement that justifies
  it, in the same commit as the edit to `scripts/timings/budgets.json` and the table above.
- A new gate or CI job gets a budget row before it lands.
- The `slow-tests` output of the CI job summary is the first stop when the `server` job regresses:
  a new class or case near the top of the list is almost always the cause.
- Refresh `server/test-weights.tsv` (after a full run: `node scripts/timings/local-times.mjs test-weights`) whenever the CI summary's fork-balance block warns and at every milestone check.
