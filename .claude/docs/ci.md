### CI and static-analysis gate details

Read on demand before changing a CI job, a Dependabot rule or a static-analysis config; the commands a session runs stay in `CLAUDE.md` "Commands" and the binding one-line rules in `.claude/docs/testing.md`.

**CI** (moved from `CLAUDE.md` "Commands").

- CI: `.github/workflows/ci.yml` re-runs every gate above on push/PR (job ids `changes`, `server`, `gradle-vulnerability-scan`, `web`, `e2e-static`, `k8s-static`, `images`, and the failure-only `alert`; the `changes` job lets a PR changing only Markdown skip `server` and `images`, except that a change to `CLAUDE.md`, `.claude/docs/testing.md` or `.claude/docs/list-endpoints.md` (the three files `AlwaysLoadedDocsBudgetTest` reads) still runs `server`, so the 36,000-byte budget cannot be broken by a docs-only PR, and so does one to `.claude/docs/persistence.md` (`RollbackRunbookTest` runs the SQL of its rollback runbook); `server` — incl. the OpenAPI
  coverage gate, strict Gradle dependency verification (`--dependency-verification strict`) plus a
  lock/verification-metadata drift check, `gradle-vulnerability-scan` (a HIGH/CRITICAL Gradle lockfile vulnerability scan),
  `web` — incl. the API-contract gate (`lint:api` + `check:api`), `npm run lint` over a restored ESLint result cache (`web/.eslintcache` via `actions/cache`, keyed on the lockfile + `eslint.config.js` + the commit with a prefix restore, so a PR re-lints only the files it changed) and the coverage run in shuffled order
  (`npm run test:coverage -- --sequence.shuffle --sequence.seed=<seed>`, which is also the `isolate: false` independence proof; `scripts/gates.sh web` mirrors it with a
  random seed it prints; CI picks the seed itself and writes it, with the replay command, to the job summary before the run, so a shuffle-order failure can be reproduced), `e2e-static` —
  lint/knip/typecheck/scenario parity/setup, the toolchain-pins check, `npm audit` (high+) on both npm workspaces, `k8s-static` (kubeconform over
  `k8s/`, the default one-replica deployment, the opt-in two-replica render, and no path around the Ingress: no `LoadBalancer`/`NodePort`/`hostPort`/`hostNetwork`, the app Service `ClusterIP`, `allow-app-ingress` naming the ingress-nginx namespace), and `images`, an image build plus a Trivy scan of it, on `master` pushes and on every pull request that changes more than Markdown — the `changes` job decides it, as it does `server`); the blackbox Playwright
  suite (`e2e.yml`) runs nightly and on demand, and re-scans the image it built with the same Trivy gate (its "Build images" step is a plain `docker compose build`, like `images`). Dependabot (`.github/dependabot.yml`) checks every
  workspace, Actions and container manifests weekly — the version PRs are a signal, and Gradle bumps are done in manual rounds (strict locks + checksum regeneration). Dependabot security alerts, secret scanning and push protection are enabled (2026-11-20, C-GATE-5); an open security alert is triaged within the week, fixed or dismissed with a reason; `.claude/docs/dependencies.md` describes
  grouping, compatibility pins and runtime verification, and
  `.claude/docs/dependency-reproducibility.md` describes the Gradle lock/checksum mechanism itself. The job time budgets were re-baselined on 2026-11-20 (`build-times.md`).

**Concurrency.** `ci.yml` cancels a superseded run only for `pull_request` events (`cancel-in-progress: ${{ github.event_name == 'pull_request' }}`); a push to
`master` runs in a group of its own (the run id), because inside one shared group GitHub keeps a single pending run and drops the older pending one even without
`cancel-in-progress` — every master commit (so every release commit) is fully verified. `e2e.yml` (nightly/on demand) declares no `concurrency`.

**Failure alerting** (checkup 3, D5-1/D8-5; nightly + alerting was chosen over a PR-time e2e subset, to keep PR CI fast).

- **Where and when.** `e2e.yml` job `alert` runs `if: failure() && github.event_name == 'schedule'` (the nightly only; an on-demand `workflow_dispatch` run stays quiet because its author is watching) and `ci.yml` job `alert` runs
  `if: failure() && github.ref == 'refs/heads/master'` (a master push; a pull request never alerts) and `needs` EVERY other job (`changes`, `server`, `gradle-vulnerability-scan`, `web`, `e2e-static`, `k8s-static`, `images`), so any red master job alerts, not only `images` (checkup 1.0.0 C-GATE-7). Both are skipped (not run) on every green or cancelled run and add nothing to a gate's time. **A new `ci.yml` job joins that `needs` list in the same commit.**
  The nightly also goes red when a test passed only on retry (`failOnFlakyTests`, `e2e/README.md`), which opens the same issue.
- **One reusable piece.** Both call `.github/workflows/ci-alert.yml` (`workflow_call`, input `title`; no third-party action, only the runner's `gh`). The calling job grants `permissions: issues: write` with the built-in `GITHUB_TOKEN`; the workflows' default stays `contents: read` and no secret is involved.
- **Dedupe.** The label `ci-failure` (created with `gh label create --force`) plus an exact title, one issue per workflow: "Nightly e2e is failing" and "master CI is failing" (the latter was "master images job is failing" until 1.0.0; an issue still open under the old title is closed by hand). An OPEN issue with that label and title gets a comment (run URL, date, head SHA, trigger); otherwise the issue is opened with the same body. Every context value reaches the script through `env:` (script-injection rule); no commit message, branch name or PR title is used.
- **Closing is manual.** Nothing closes the issue automatically, not even a green run: close it once the cause is understood, and the next red run opens a fresh one. A red `alert` job (e.g. a token problem) is itself a failed run.

**Transient resolution failures** (checkup 1.0.0 C-GATE-1). A dependency miss from Maven Central or the plugin portal (`Could not find io.ktor:ktor-version-catalog`, a 404/5xx/timeout) failed 3 of 8 master pushes with no retry. `scripts/gradle-retry.sh <gradle command>` re-runs the command up to 3 times (`GRADLE_RETRY_ATTEMPTS`, pause 15 s then 30 s, `GRADLE_RETRY_PAUSE_SECONDS`) ONLY when the output carries a network/resolution signature and never on `Dependency verification failed`; a compile error or failing test is final on the first attempt. Between attempts it deletes `caches/modules-2/metadata-*`, because Gradle caches a missing module for 24 h and a bare re-run would read the cached miss; the jars stay and every artifact is still verified, so locking and checksum verification are untouched (no `--offline`, no `--refresh-dependencies`). Use it with `--no-daemon` only. It guards three places: the `Dockerfile` `server` stage (`./gradlew --version` and `:server:installDist`, so `images` and `e2e.yml` both inherit it; the script is copied to `/usr/local/bin/gradle-retry`), the `server` job's "Resolve Gradle dependencies (bounded retry)" step, which runs the root task `resolveAllDependencies` (`build.gradle.kts`: every resolvable configuration of every project and each buildscript classpath, resolved to its files under strict locking and verification, so it throws on a miss and downloads the jars; the `dependencies`/`buildEnvironment` report tasks cannot serve, they print FAILED and exit 0) so the 25-minute build starts on a warm cache; the full `check` build is deliberately NOT wrapped (a test log could match a network signature and re-run it), and `gradle-vulnerability-scan`. What counts as transient: `Could not GET|HEAD|download`, `Could not find <group>:<artifact>:<version>` (a full coordinate), timeouts/resets, HTTP 5xx, DNS; never a bare `Could not find`/`Could not resolve` (a build-script `Could not find method` is final), never `Dependency verification failed` or a strict-lock violation (`not part of the dependency lock state`). The script refuses a `gradlew` command without `--no-daemon`. A genuinely wrong coordinate is retried too (about a minute lost) before it fails. The metadata deletion also drops the artifact index, so jars may be downloaded again on a retry; they are re-verified. The coverage is as good as the signatures: a transient failure that prints none of them fails on the first attempt, as before.

Gradle can report a missing plugin marker as `could not resolve plugin artifact 'group:artifact.gradle.plugin:version'` before any task runs. The same bounded retry recognizes that coordinate and clears the dependency metadata before retrying. Version pins, dependency locks, and checksum verification apply on every attempt.

**Local gates vs CI (`scripts/gates.sh`).** The script runs `server-build`, `web-gates` and `e2e-static` (lint, knip, typecheck, scenario parity, `npm run test:setup` and
`node scripts/check-toolchain-pins.mjs`, the same cheap checks as the CI `e2e-static` job). **CI-only scans**, deliberately not in the script (the header comment of
`gates.sh` lists them too): `npm audit` on both workspaces and the HIGH/CRITICAL Gradle lockfile vulnerability scan (they read a live advisory database and the network, and
their result changes overnight without a commit), kubeconform in `k8s-static` (a downloaded checksum-pinned binary), and the Trivy / AppCDS image checks (need a built image).

**Static analysis and dead-code gates** (moved from `testing.md`).

**Static analysis (detekt).** `./gradlew detekt` runs detekt over `core` + `server` (plain rule
sets, no type resolution) and rides `check`, so `build` fails on any finding — the gate is zero
findings with **no baseline file**. Repo tuning lives in `config/detekt/detekt.yml`, layered on
the bundled defaults; every override there carries a one-line comment naming the deliberate idiom
it protects (wildcard Ktor imports, the flat feature-package layout, declarative `*Routes.kt`
registrars, the validation-throw convention, guard-clause returns). Fix new findings in code
first; extend the config only for a genuinely deliberate idiom, and prefer a config override over
`@Suppress` (a per-site `@Suppress` needs a one-line justifying comment). Runs in seconds, no
Docker — safe to run anytime, unlike the test suite.

**Frontend static analysis (sonarjs + knip).** The SPA's counterpart, same
zero-findings/no-baseline policy: `cd web && npm run lint` carries `eslint-plugin-sonarjs`
(recommended set) plus core size/complexity backstops tuned generously for React's
one-function-per-page architecture (`cognitive-complexity` 40, `complexity` 50 — backstops
against future monsters, not targets); every override in `web/eslint.config.js` carries the idiom
comment. `cd web && npm run knip` is the dead-code gate (unused files/exports/dependencies; test
files count as entries, so a flagged export is unused even by tests) — the generated
`src/api/schema.ts` is excluded (type-checked by `tsc`, not style-linted). `web/scripts/*.mjs` (the build-time Node scripts, e.g. `check-api.mjs`) are linted too —
`js.configs.recommended` + sonarjs with Node globals, a separate block in `web/eslint.config.js`.

**Running the full stack** (moved from `CLAUDE.md`; the ports and the one-liners stay there).

`docker compose up --build` serves everything at `http://localhost:8087` (sign in as
`admin@adder.local` — see the README for where the initial password comes from); local dev is
`docker compose up postgres mailpit` (Postgres on host port **5439**) + `./gradlew :server:run`
(API on **8087**) + `cd web && npm run dev` (Vite on **5179**, proxying `/api` to :8087), each in
its own terminal. The compose stack is postgres + mailpit + app only, and bundles **Mailpit** (`http://localhost:8030`) and wires the
app's password-reset and MFA email to it (`MAIL_TRANSPORT=smtp`). Ports deliberately differ across the sibling repos so every stack can run side by side
(host ports bind to 127.0.0.1 only):

| Repo | App | Postgres | Mailpit UI | Vite |
|---|---|---|---|---|
| Lettuce | 8080 | 5432 | 8025 | 5173 |
| Toadie | 8081 | 5433 | 8026 | 5174 |
| Covenant | 8082 | 5434 | 8027 | 5175 |
| Flow | 8084 | 5435 | 8028 | 5176 |
| Grenadiers | 8085 | 5436 | – | 5177 |
| Astrolabe | 8086 | 5437 | 8029 | 5178 |
| Astrid | 8088 | 5438 | – | 5180 |
| **Adder** | **8087** | **5439** | **8030** | **5179** |

Kubernetes
(OrbStack) deployment targets the dedicated `adder` namespace — see `k8s/secret.yaml`'s header for
the secret-creation command. The app image is `adder-app` and the default Deployment has one replica beside PostgreSQL. The
`apply-local.sh` two-replica option checks rollout readiness and compares each ready app pod’s version and commit with the selected immutable build before it renders two app pods.
Both renders retain `Recreate`, ingress-only networking, and the same Secret references. The `k8s-static` job validates the
rendered manifests and runs the mocked preflight cases, including older builds and a Deployment in progress.
The two-app rehearsal passed all 195 Chromium scenarios against two containers sharing PostgreSQL and Mailpit. A
round-robin HTTP and WebSocket proxy exercised both apps. PostgreSQL peaked at 19 client connections across 271 samples
under the browser workload, within the 48-connection steady budget. When the proxy sent traffic to the surviving app and the first app
stopped, the open browser reconnected in 2.6 seconds and received a new reply without reloading. Kubernetes manifests
were validated separately with the pinned schemas and a mocked preflight.
**The front door is an ingress-nginx Ingress** (`k8s/ingress.yaml`, decided 2026-11-20): the `app` Service is `ClusterIP`, so there is no `localhost:8087`
for the cluster; the app is at `https://adder.k8s.orb.local` (OrbStack resolves `*.k8s.orb.local` ingress hosts; the controller's LoadBalancer answers on 80/443; with `tls:` and
no `secretName` the controller's self-signed fake certificate is served, so a browser warns once and `curl -k` works; plain HTTP gets the controller's 308). It needs the ingress-nginx
controller installed in the cluster's `ingress-nginx` namespace (not part of `k8s/`; the README has the pinned install and undo commands; community ingress-nginx was retired in March 2026, so pin a release you have reviewed and treat it as a local reference), and
`allow-app-ingress` admits only that namespace's controller pods to `:8087`. `k8s-static` validates `ingress.yaml` with the rest of `k8s/*.yaml` (and the `apply-local.sh` render, which copies it). Host,
certificate SAN and `MAIL_APP_URL` move together (the file's header); `http.corsHosts` stays empty (single origin). Trust: `HTTP_PROXY_HOPS=1` (`security.md` "Reverse proxy").

**The live socket in front of a proxy** (0.4.0; `live.md` "Deploy and proxies", `security.md`). Compose serves 8087 directly; the OrbStack `adder` namespace sits behind the ingress-nginx Ingress, whose
`proxy-read-timeout`/`proxy-send-timeout` are 120 s (the server pings every 25 s) and which passes the upgrade on its own; the socket's 1001 close (the hub's 2 s) fits inside `terminationGracePeriodSeconds` 50 (a 5 s `preStop` sleep comes first, `observability.md`). Vite needs `ws: true` on its `/api` proxy and NO `changeOrigin`: the Origin check compares the
browser's origin with the request's own host, so the proxied request must keep the original `Host`. A reverse proxy in front of a real deployment must pass the upgrade (`proxy_http_version 1.1`,
`Upgrade $http_upgrade`, `Connection $connection_upgrade`), keep read and send timeouts at 60 s or more (the server pings every 25 s), and give the application the browser's own scheme, host and port, or every Origin check is a `403`: **TLS at the proxy needs `X-Forwarded-Proto: https` plus
`http.behindProxy=true`**, **a public port other than 443 must be in `X-Forwarded-Host`** (`app.example.com:8443`), and **`Host $host` alone behind TLS gets a 403** (right host,
wrong scheme); with `http.behindProxy=true` the per-IP `live-connect` bucket and the per-address pending share also see the real client. Without real client addresses (a plain proxy with `behindProxy=false`, an SNAT load balancer: a Kubernetes Service with `externalTrafficPolicy: Cluster`, which can also be what the ingress controller's own Service is on a cluster that SNATs: check the address in an audit line) EVERYONE shares ONE `live.maxPendingPerAddress` (8) and one `live-connect` bucket: raise the share or fix the trust; with a client-supplied `X-Forwarded-For` (no proxy stripping it) a client picks its own key and both are bypassable (`security.md`). The opt-in two-replica profile exchanges tickets across both apps through the shared database. Each replica keeps its own connection resource caps.

**AppCDS (class-data sharing).**

- The Docker build dumps `/app/cds/adder.jsa` from the committed `server/cds/classlist.txt` (a few seconds per image build); Trivy scans the same image as before.
- The `images` job then runs `scripts/check-cds-archive.sh adder-app` (the image's launcher with `-XX:+PrintSharedArchiveAndExit`, under a second, no database): it fails when the archive does not fit the runtime
  (a changed jar, `-XX:-UseCompressedOops`/`ObjectAlignmentInBytes` added to `applicationDefaultJvmArgs`, another JDK) or is not wired, because such an image starts silently WITHOUT the archive and slower than before.
- `scripts/cds-classlist.mjs` regenerates the class list from a real run in its own throwaway compose project (own ports, own volume); it builds its own image and refuses to overwrite the list from an unhealthy run. Regenerate it whenever the class set changes materially (`CdsClassListTest` guards drift).
