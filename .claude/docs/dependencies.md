# Dependency maintenance

Dependency declarations, lockfiles and `gradle/verification-metadata.xml` are the source of truth.
Keep Gradle and the two npm workspaces (`web/`, `e2e/`) independent; do not upgrade to the newest
major merely because it is available. `.claude/docs/dependency-reproducibility.md` describes the
Gradle lock/checksum mechanism itself (what is covered, the hard empty-`GRADLE_USER_HOME` rule for
regenerating verification metadata, and how to review a regenerated file) — this doc stays focused
on the maintenance workflow: grouping, compatibility pins, and acceptance checks.

## Update automation

`.github/dependabot.yml` checks Gradle, each npm workspace, GitHub Actions, Dockerfiles,
Kubernetes image references, and Compose weekly. Ordinary minor/patch updates are grouped by
workspace. Vitest and its coverage plugins form a separate group so their exact-version peers move
together. The Gradle bulk group excludes Exposed and the Netty/Reactor/OpenTelemetry families:
their pins need individual compatibility review before integration. Exclusion from a group does
not ignore an update or exempt it from review.

Dependabot vulnerability alerts, secret scanning and push protection are separate GitHub repository settings
from the version-update configuration. Decision of 2026-11-20 (checkup C-GATE-5, "Enable alerts + manual rounds"): on `liveweird/adder`
they are **enabled**, the weekly version PRs stay as a signal, and Gradle bumps are hand-assembled **manual rounds** (strict locking plus
`verification-metadata.xml` regeneration from an empty `GRADLE_USER_HOME` make an unattended Dependabot Gradle PR incomplete by construction).
An open security alert is triaged within the week: fixed, or dismissed on GitHub with a reason. Security fixes still need the normal
checks; never auto-merge a breaking migration to clear an alert.

**Transitive npm pins.** Prefer `npm update <pkg>` inside the existing ranges. Only when a parent's range excludes the first patched version, add an `overrides` entry scoped to that parent (never a blanket one: other parents still need their own major), with its rationale here. Current: `minimatch` `^11.0.9` under `@typescript-eslint/typescript-estree` and `eslint-plugin-sonarjs` in `web/` and `e2e/` (both ask `^10`, whose last release 10.2.6 is in the advisory range below 11.0.1; the named `minimatch`/`Minimatch` exports they use exist in 11). Drop the entries when those parents move to minimatch 11.

Dependabot does not cover every declaration here. At each monthly maintenance pass, inspect the
exact JDK pin in `mise.toml`, Ktor's imported catalog in `settings.gradle.kts`, and the runtime
versions actually used by cached images and local tools. Compare official release metadata, not
just open PRs. The CI toolchain pins (`.nvmrc`, the `java-version` in `ci.yml`, the wrapper checksum,
the kubeconform version/sha) are in that same manual list — see "CI toolchain pins and scans".

**The PostgreSQL image is pinned in THREE places that must move together:** `docker-compose.yaml`,
`k8s/postgres-deployment.yaml` and `PostgresTestSupport.IMAGE`
(`server/src/test/kotlin/PostgresTestSupport.kt`). Dependabot only sees the first two, and as
separate PRs (the `docker-compose` and `docker` ecosystems cannot share a group; the `postgres`
group in `dependabot.yml` merges the `/` and `/k8s` `docker` directories at most). So a Postgres
tag/digest bump lands in ONE hand-assembled PR touching all three literals — close or redo
Dependabot's split PRs into it, re-deriving the digest per "Runtime and image verification" below. The
Testcontainers literal is no longer a monthly manual inspection item: `PostgresImagePinTest`
asserts it equals the compose and k8s `image:` values, so a partial bump fails the build.

## Compatibility boundaries

- Keep TypeScript on the supported shared major across web and E2E. TypeScript 7 needs a
  coordinated migration: the current ESLint tooling requires TypeScript below 6.1, and
  `openapi-typescript` uses the compiler API and declares TypeScript 5. The existing web
  `--legacy-peer-deps` exception permits TypeScript 6; it is not permission to bypass arbitrary
  peer conflicts. In particular, all Mantine packages must use the same release.
- Match `@types/node` to the Node runtime major. Keep production on an LTS line; newer types can
  expose APIs missing at runtime even if the build passes.
- Upgrade React/React DOM and their type packages together. Upgrade Vitest and
  `@vitest/coverage-v8` together and validate coverage without lowering floors.
- Read the current rationale in `gradle/libs.versions.toml` before moving Exposed,
  Netty/Reactor Netty, or OpenTelemetry. Inspect published BOMs/POMs as well as release notes.
  `:server:checkDependencyAlignment` must pass, but alignment alone does not prove compatibility.

## Runtime and image verification

Keep PostgreSQL on the same tested major/minor across Compose, Kubernetes and Testcontainers.
External images use exact release tags and reviewed multi-platform digests where available;
refresh both together. The tag describes the intended version, while the digest selects bytes. A
local rebuild without an updated base digest does not pick up security fixes. Verify
`java -version`, `node --version`, and `postgres --version` inside the selected images when
changing runtime pins. A `mise` version identifier may differ from the Java version it installs;
record the actual binary version when verifying a JDK patch. Re-derive every image digest from the registry (`docker buildx imagetools inspect <tag>`, the index digest) rather than copying it from a Dependabot PR: Dependabot's digest can differ from the tag's current one in non-deployed platform entries.

Use the build-specific local Kubernetes image workflow in `.claude/skills/run-stack/SKILL.md`. Do
not redeploy a mutable `latest` tag and assume every node has the same image. Never remove
development database volumes as part of an update.

## Acceptance checks

Refresh each changed workspace from its committed lockfile. For Gradle changes, regenerate the
project and buildscript locks with the command below and review their diff. Run each changed
workspace's documented lint, dead-code, type, test/coverage and build gates; regenerate frontend
API types and confirm there is no unexplained schema drift. Run `npm audit` in both workspaces and
distinguish runtime advisories from development-only exposure. For Gradle changes, run the
complete build and alignment gate on the pinned JDK; database changes need a fresh Testcontainers
database and the complete server suite, including concurrency cases.

Build the application image, verify health, and run the full Playwright journeys after
cross-stack updates. Existing Dependabot PR checks are evidence for their recorded commit only;
refresh against current main before merging. A version check or npm audit is not a comprehensive
JVM/container vulnerability scan.

Regenerate the Gradle project/buildscript/plugin lockfiles with:

```
./gradlew :verifySettingsPluginAudit :buildEnvironment :core:buildEnvironment :server:buildEnvironment \
  :dependencies :core:dependencies :server:dependencies --write-locks
```

then review the diff; do not hand-edit generated locks. `.github/workflows/ci.yml`'s
`gradle-vulnerability-scan` job scans all seven Gradle lockfiles (root/settings/core/server,
project + buildscript) with Trivy and fails on a HIGH or CRITICAL advisory; the Docker build
copies the locks before resolving server dependencies.

A dependency or plugin change also needs `gradle/verification-metadata.xml` regenerated in the
same commit — Gradle's separate SHA-256 checksum gate on every resolved artifact. This step has a
hard rule: it **must** run from an empty `GRADLE_USER_HOME` (a warm one silently omits checksums
for anything already cached), so it is a separate command from the plain `--write-locks` one above,
not an extra flag tacked onto it:

```
export JAVA_HOME=$(mise where java)
export DOCKER_HOST=unix://$HOME/.orbstack/run/docker.sock
GRADLE_USER_HOME=$(mktemp -d) ./gradlew --write-verification-metadata sha256 \
  :verifySettingsPluginAudit :buildEnvironment :core:buildEnvironment :server:buildEnvironment \
  :dependencies :core:dependencies :server:dependencies \
  build :server:installDist
```

Then validate from a second, also-empty `GRADLE_USER_HOME` with
`./gradlew --dependency-verification strict build` before committing. See
`.claude/docs/dependency-reproducibility.md` for what this covers, why the task list must resolve
every configuration CI and the Docker build resolve, and how to review the generated checksums.
`.github/workflows/ci.yml`'s `server` job checks `gradle/verification-metadata.xml` is present,
runs `--dependency-verification strict`, and diffs it (with the lockfiles) after the build; the
Docker build's `server` stage copies the whole `gradle/` directory, so it picks up the file
automatically once it exists.

## CI toolchain pins and scans

`.github/workflows/ci.yml` and `e2e.yml` run on the toolchain the repo was verified on, and scan what
ships. Dependabot covers none of the pins in the first three rows — bump them by hand, together.

| Pin / gate | Where | Bump rule |
|---|---|---|
| JDK `21.0.13` (`actions/setup-java` `java-version`, both Gradle jobs) | `ci.yml` | the exact patch of `mise.toml`'s `java` (`temurin-21.0.13+9.0.LTS`) and the Dockerfile's `eclipse-temurin:21.0.13_9-jdk-noble` / `-jre-noble` tags — one PR moves all three. A Temurin respin (e.g. `21.0.12.1+1`) is `temurin-21.0.12+101.0.LTS` in mise (Adoptium folds the respin into the build number); `setup-java` resolves the patch to its newest respin, so `ci.yml` names the patch only, while the Dockerfile tag names the respin. `node scripts/check-toolchain-pins.mjs` (an `e2e-static` step) fails the build with one line per mismatch if the JDK or node pins diverge |
| Node `24.22.3` (`node-version-file: .nvmrc`, web/e2e-static/e2e jobs) | `.nvmrc` | mirrors `mise.toml`'s `node` and the Dockerfile's `node:24.22.3-alpine` (guarded by the same script); `.nvmrc` holds the bare version, no `v` |
| Gradle `distributionSha256Sum` | `gradle/wrapper/gradle-wrapper.properties` | the wrapper validates the downloaded distribution against it (CI, local, and the Docker build's `./gradlew --version`). On a Gradle bump take the value from `https://services.gradle.org/distributions/gradle-<version>-bin.zip.sha256` (the `bin` type the URL uses), confirm it against a `shasum -a 256` of the downloaded zip, and change URL and checksum in the same commit |
| `npm audit --omit=dev --audit-level=high` | `ci.yml` `web` | runtime dependencies only (a dev-tool advisory never reaches the bundle). The LAST step of the job, so an overnight advisory cannot hide the real gate results |
| `npm audit --audit-level=high` | `ci.yml` `e2e-static` | every e2e dependency is a dev tool, so the whole tree. Also the last step |
| `trivy image` (HIGH/CRITICAL, `--ignore-unfixed`, exit 1) | `ci.yml` `images` (main pushes) and `e2e.yml` (nightly, the last step) | the same digest-pinned `trivy:0.74.0` as the Gradle lockfile scan — three copies of the pin, bump them together; run against the compose-built `adder-app`. The nightly copy catches an advisory published between merges. A red step is a fixable OS-package or JAR advisory: refresh the base-image digests (see "Runtime and image verification") or the dependency. It cannot be waived per-CVE without a `.trivyignore` with a dated justification — there is none |
| `kubeconform v0.8.0` (`-strict`, Kubernetes 1.33.0) | `ci.yml` `k8s-static` | the tarball is pinned by sha256 in the workflow (`CHECKSUMS` file of the release); bump version and sha together. Kubernetes version follows the local OrbStack cluster |
| `actions/cache` | `ci.yml` `web` (the ESLint cache), `e2e.yml` (the Playwright browsers) — the BuildKit layer cache and `docker/setup-buildx-action` are not used by either image build | SHA-pinned like every action; Dependabot's `github-actions` ecosystem updates it |
| `kubeconform -schema-location` (`yannh/kubernetes-json-schema@8df8a883…`) | `ci.yml` `k8s-static` (`KUBECONFORM_SCHEMAS`) | the schema repo is pinned to a commit so an upstream schema change cannot redden the job; move it with the kubeconform version |

The npm audits are a moving gate: a newly published advisory can turn an unrelated PR red. That is the
point — fix the dependency, or (if it is a false positive for how the package is used) record a
justified, dated exception here and adapt the audit step in the same PR (`npm audit` has no built-in
allow-list); never lower `--audit-level` to get green. Measured state on 2026-09-30: `npm audit` reports 0 vulnerabilities in
`web` (full and `--omit=dev`) and in `e2e`; the locally built image scans clean (no fixable
HIGH/CRITICAL) — those are the baselines the gates started from.

**Image build cache: none.** Both image builds (`ci.yml` `images` on main pushes, the nightly `e2e`) are a plain
`docker compose build`. A BuildKit `type=local` layer cache persisted with `actions/cache` was tried in Flow and removed: it cost more than it saved, and each ~1 GB cache entry pushed
the repository over GitHub's 10 GB cache quota and evicted the Playwright browser cache. Do not re-add one without re-measuring both.

## The WebSocket artifacts (0.4.0 S4)

`io.ktor:ktor-server-websockets` (the live-update socket, `plugins/Sockets.kt` and `live/`) moved from the test classpaths, where the test host dragged it in, to `implementation` in
`server/build.gradle.kts`; it is the same locked 3.6.1 (`ktorLibs.server.websockets`, no version of its own), already checksummed, and its `ktor-websockets` runtime was on the main classpath
before. The one NEW artifact is test-only: `io.ktor:ktor-client-websockets` 3.6.1 (`testImplementation(ktorLibs.client.websockets)`, the client plugin `liveClient()` installs in
`TestEnvironment`'s live fixtures; never used for `/api/` HTTP). Locks and `gradle/verification-metadata.xml` were regenerated per `dependency-reproducibility.md` (an EMPTY `GRADLE_USER_HOME`); the only
checksum additions are the three `ktor-client-websockets` entries, recorded by Gradle from Maven Central like the rest of the file (no independent publisher signature, same caveat as the baseline).

## Buildscript advisory follow-up (inherited from Flow, 2026-09-26)

CI scans reported CVE-2026-84939 in the root plugin classpath's transitive
`org.freemarker:freemarker` 2.3.32 (caught in Covenant first). A root buildscript constraint selects
Apache FreeMarker 2.3.35, and the regenerated root buildscript lockfile records that version. This is a
build-time dependency; it is not packaged in the application runtime.

## Lessons ported from Toadie's Dependabot rounds (2026-09-26)

Sibling project Toadie hit these in its own weekly Dependabot rounds; they generalize to any
workspace here running the same tools:

- **A `knip` bump can add or drop findings with no change to this repo's code.** Toadie's 6.36
  started counting an export used only inside its own declaring file as unused, and separately
  raised a "Configuration hint … Remove from ignore" finding for an `ignore` entry that no longer
  suppressed anything. Read the whole `npm run knip` output on a Dependabot `knip` bump, not just
  a `grep knip` on the CI log — the failure can hide behind an unrelated-looking hint line. Fix by
  dropping the now-needless `export` keyword or `knip.json` ignore entry; do not silence the new
  rule. Flow's own `knip.json` carried exactly this stale entry (`ignore: ["src/api/schema.ts"]`)
  by the time this section was written — `schema.ts` is now fully consumed, so the entry was
  removed rather than kept as a no-op.
- **A required "Quality gate" style status check makes Dependabot PR merges serial, not
  parallel.** If branch protection requires every PR to contain the target branch's current HEAD,
  update-branch a Dependabot PR, wait for its checks, merge, then update-branch the next one —
  merging cheapest/most-likely-green first keeps a later red PR attributable to its own diff
  rather than a stale base. A config-only PR (`.github/**`) that doesn't change a Docker image can
  have its downstream image rebuild run in parallel with its own CI, since nothing about the image
  input changed.

## Dependency history

Adder 1.13.0 adds CommonMark Java 0.33.0 with the autolink extension for validating stored message destinations and Jsoup 1.24.1 for
bounded HTML parsing. Guarded preview connections use the existing OkHttp 5.5.1 pin as a direct dependency. The project generated
Gradle locks and artifact checksums for the new module graph and validated them with strict verification from a second empty Gradle home.

Flow's batch-by-batch bump log (2026-09 .. 2026-11) is not carried over: Adder starts from the dependency set Flow 1.57.1 resolved at `4e43e480` (minus WireMock and the
integration-only clients, `infra/outbound`'s guarded HTTP client with them), and its own rounds are recorded below, newest first, as they happen. The v0.1.0 lockfiles and
`gradle/verification-metadata.xml` were regenerated from an empty `GRADLE_USER_HOME` per `dependency-reproducibility.md`.

**2026-11-20: the first security-alert fixes** (Dependabot alerts #1-#6, right after alerts were enabled): `fast-uri` 3.1.8 -> 3.1.9 in `web/` (`npm update`, inside ajv's range), and `minimatch` 10.2.6 -> 11.0.9 under `@typescript-eslint/typescript-estree` and `eslint-plugin-sonarjs` in `web/` and `e2e/` through the parent-scoped overrides above; this clears the GHSA-g3hv-75wg-m249 note of 2026-11-19. `npm audit` reads 0 in both workspaces.

**2026-11-19: i18next 26.7.1 -> 27.1.1 with react-i18next 17.1.12 -> 18.0.1** (replaces Dependabot #9 and #10; react-i18next 18 requires i18next 27, so they move together). The one break was typing: i18next 27 defaults `strictInterpolationChecks` to true, so a `t()` with variables needs its options object; the workflow validation messages pass `params ?? {}` (`ValidationError.tsx`, `workflowForm.ts` `validationText`). The other v27 defaults do not reach Adder: `fallbackLng` is already `en`, no locale value is empty (the `returnEmptyString` change), `escapeValue` is off (the `/` change), and there is no `Trans`, SSR or resource-store API use.

**2026-11-19: one hand-assembled round** (replaces Dependabot PRs #1-#8 and #11-#13, which branched from the 0.1.0 tree and carried neither regenerated Gradle locks nor checksums; the majors #9 react-i18next 18 and #10 i18next 27 were left alone).

- **Images.** Mailpit v1.33.0 -> v1.33.1 (compose). Postgres 18.6-alpine digest `10a5c69a` -> `9ba53c7d` in all THREE places (compose, `k8s/postgres-deployment.yaml`, `PostgresTestSupport.IMAGE`; `PostgresImagePinTest`). Node 24.22.1 -> 24.22.3 (Dockerfile, `.nvmrc`, `mise.toml`, the `CLAUDE.md` and run-stack mentions; `check-toolchain-pins.mjs` agrees). Temurin 21.0.13_9 jdk/jre digests moved to `a2e61ec9` / `977602aa`. Every digest was re-read from the registry index (`docker buildx imagetools inspect`), not copied from a PR; the two Temurin digests are the tags' current ones, which differ from Dependabot's `06c1f157` / `0d57a8e9` only in the riscv64 entry (amd64/arm64 images are byte-identical).
- **Actions.** `actions/setup-java` 6.2.0 -> 6.3.0 (`d1374f8a`, both Gradle jobs in `ci.yml`; the tag resolves to that commit). Nothing else pins the Playwright version: the browser cache key of `e2e.yml` is read from `npx playwright --version`.
- **e2e.** `@playwright/test` 1.65.1, `eslint` 10.15.0, `knip` 6.45.1, `typescript-eslint` 8.72.2 (lock refreshed from the committed one).
- **web.** All `@mantine/*` on **9.8.0** (charts and spotlight exact; core/form/hooks/notifications `^9.8.0` but locked to 9.8.0 — 9.8.1 exists and would have split the family from the exact pins), `@tabler/icons-react` 3.52.0, `@tanstack/react-query` 5.107.1, `@stoplight/spectral-cli` 6.17.1, `@testing-library/user-event` 14.6.10, `@vitejs/plugin-react` 6.3.0, `eslint` 10.15.0, `knip` 6.45.1, `postcss` 8.5.33, `typescript-eslint` 8.72.2, `vite` 8.3.8. Packages were installed at exactly the versions reviewed; newer patches published since are for the next round. knip and eslint raised no new findings.
- **Gradle.** Jackson 2.22.4 -> 2.23.0 (catalog plus the buildscript constraints in `server/build.gradle.kts`; `jackson-annotations` is `2.23`), Flyway 13.12.0 -> 13.12.2, logback 1.6.6 -> 1.6.7, Exposed 1.6.0 -> 1.6.1, OpenTelemetry SDK 1.67.0 -> 1.68.0 **together with the instrumentation line 2.33.1-alpha -> 2.34.0-alpha** (the catalog rule: 2.34.0's BOM imports SDK 1.68.0, so the SDK bump alone would have broken the pair). Gradle wrapper 9.8.2 -> 9.9.1: `distributionSha256Sum` from `services.gradle.org` and confirmed against a `shasum -a 256` of the downloaded zip, wrapper jar regenerated by the 9.9.1 distribution and matched to its published `-wrapper.jar.sha256` (CI has no separate wrapper-validation step). Transitive moves in the locks: okhttp 5.5.0 -> 5.5.1 (OpenTelemetry), commons-lang3 3.21.0 -> 3.22.0 (buildscript classpath only; the runtime lock keeps 3.20.0), and the buildscript locks' `kotlin-stdlib` 2.4.10 -> 2.4.21 (Gradle 9.9.1's own Kotlin). `checkDependencyAlignment`: Netty 4.2.20.Final, OpenTelemetry 1.68.0, Jackson 2.23, kotlin 2.4.21 — one version each. Locks regenerated, `gradle/verification-metadata.xml` regenerated from an empty `GRADLE_USER_HOME` and validated with `--dependency-verification strict` from a second one; the only additions are the moved coordinates plus POM/`.module`-only entries for versions Gradle consults during conflict resolution (errorprone 2.49.0, okio 3.12.0, kotlinx-io 0.6.0, slf4j 2.0.9, reactor-core 3.5.20 and similar), recorded from Maven Central like the rest of the file.
- **Audit.** `npm audit --audit-level=high` is clean in both workspaces; both report a moderate minimatch advisory (GHSA-g3hv-75wg-m249) through `typescript-eslint`/`eslint-plugin-sonarjs`, dev-only and below the gate (`--omit=dev` is clean in `web`), already present at 0.4.0 (`08a01ea`): this round only adds patched 11.0.9 copies under `eslint`.

A dependency kept for a future consumer stays on the classpath only while a test or source file uses it; knip/dependency analysis flags an orphan, and a consumer-less one is deleted
and re-added with its feature.

## Web runtime dependencies with a pairing rule

Installed with `npm install --legacy-peer-deps` (the existing web exception: recharts' React peers are tolerated the same way `openapi-typescript`'s TypeScript peer is).

- `@mantine/charts` (**exact**) — the telemetry panel's charts (and any later chart). Pinned to `@mantine/core`'s release, exactly like `@mantine/spotlight`:
  **every `@mantine/*` package must sit on one version** — a chart/spotlight package a minor behind core renders unstyled or throws on a changed prop contract. The
  `web-minor-patch` Dependabot group batches every `@mantine/*` bump into one PR; check the resulting lockfile diff shows one Mantine version.
- `recharts` — `@mantine/charts`' rendering engine (a peer it does not bundle). ~400 kB: it must only ever enter **lazy chunks** (`npm run build` shows it as its own chunk, and the main
  `index-*.js` stays free of it). Each chart component imports `@mantine/charts/styles.css` itself — without it the tooltip renders unstyled.
- `react-markdown` + `remark-gfm` + `remark-gemoji` — the markdown renderer behind `components/MarkdownView.tsx` (the changelog bodies; later the message bodies). Lazy-chunk-only: they must not enter the main bundle.
- `emoji-mart` and `@emoji-mart/data` provide the emoji picker behind `components/EmojiPicker.tsx`. The component mounts the picker
  element directly and updates it as the language or theme changes. Lazy-chunk-only: the picker mounts
  through `EmojiButton`'s `React.lazy` and the data set (a few hundred kB) has its own `emoji-data` code-splitting group in `vite.config.ts`; none of them may enter the main bundle (`npm run build` shows the chunks). **No CDN, ever:**
  `data` AND `i18n` are always passed to the `Picker` explicitly (one i18n file per `SUPPORTED_LANGUAGES` entry) — omitting either makes emoji-mart fetch it from a CDN at runtime, which the CSP (`connect-src 'self'`)
  blocks and a self-hosted deployment must not attempt. `EmojiPicker.test.tsx` covers the bundled data, locale, and element lifecycle.
- **`react-is` override** (`web/package.json` `"overrides": { "react-is": "^19" }`): under React 19 the tree otherwise keeps `react-is@17` (pulled by `@testing-library/dom`'s
  `pretty-format` and `prop-types`), and recharts imports `isFragment` from `react-is` — which must match React's major. `npm ls react-is` must show one version; keep the
  override matched to React's major when React moves.
- Not installed yet: `@mantine/dates` + `dayjs` (date pickers), `@dnd-kit/*` and `@tanstack/react-virtual`; add them with the first commit that imports them (knip flags an orphan), `@mantine/dates` pinned **exact** like the other `@mantine/*`.
