# Adder

Adder is **a chat tool crossed with an issue tracker** (rooms and threads, each thread with a workflow status and an owner) for short-lived, intensive, asynchronous collaboration — e.g. 30+ people working a complex, multi-threaded incident without chaos. People work in
**rooms** (members only); all talk lives in **threads** (you either start a thread or reply in one); every thread carries a **workflow status plus configurable metadata** and an
**owner**, and a thread in a closed status takes no further activity until it is reopened. Room content is filterable, sortable and searchable; the usual chat affordances apply
(pins, @mentions, reactions, emoji, #hashtags), and updates reach open browsers live over a WebSocket. The name is the snake: quick, quiet, decisive.

## What's here today (0.4.0 — live updates, on the 0.3.0 threads and messages, the 0.2.0 rooms and the 0.1.0 foundation)

Adder's repository started as an exact copy of [Flow](https://github.com/liveweird/flow) (1.57.1, `4e43e480`), trimmed to its generic foundation. 0.4.0 makes the rooms live:

- **Live updates** over a WebSocket (`GET /api/v1/live`; `.claude/docs/live.md`) — the browser trades its bearer for a single-use ticket (`POST /api/v1/live/tickets`) and sends it in the socket's first frame. Every
  chat write emits one PostgreSQL `NOTIFY` hint (ids only) inside its transaction; one `LISTEN` connection per process fans the hints out to the sockets subscribed to a room, and the page refetches what changed. Threads,
  messages, status, owner and detail changes, members and the room workflow update without a reload; the header says **Reconnecting…** while the connection is down, or **Live updates off** when a user's tab cap is hit
  (16 sockets per user, a hidden tab closes after five minutes), and a removed member's room leaves the rail at once. A logout, deletion, password or role change closes the user's sockets within 60 s. Memory-only and
  per-process: one replica for now (`BACKLOG.md`). The ADMIN telemetry panel shows the open sockets, the listener and the NOTIFY queue usage.
- **Behind a reverse proxy** the WebSocket upgrade must pass: `proxy_http_version 1.1`, `Upgrade $http_upgrade`, `Connection $connection_upgrade`, read/send timeouts of 60 s or more (the server pings every 25 s), and the
  original host (`Host $host`, or `X-Forwarded-Host` with `HTTP_BEHIND_PROXY=true`), otherwise the Origin check answers 403 (`.claude/docs/live.md` "Deploy and proxies", `.claude/docs/ci.md`). Behind a TLS-terminating
  proxy the socket also needs `X-Forwarded-Proto: https` with `HTTP_BEHIND_PROXY=true`, and a public port other than 443 inside `X-Forwarded-Host`.

0.3.0 put the conversation into the rooms of 0.2.0:

- **Threads** (`/api/v1/rooms/{id}/threads`) — started in a room with a title and an opening message, numbered per room (#1, #2, ...), each with a **workflow status**, an **owner** (the creator at first, passable)
  and the room's typed **metadata fields**. A thread in a CLOSED-category status is frozen (409) until reopened; the thread owner while a member, the room OWNER or an ADMIN closes, reopens and hands over, and any
  reader may move a thread between open statuses. Every change lands in the thread's **history**, shown in the side panel.
- **Messages** — Markdown and emoji; the author edits, the author, a room OWNER or an ADMIN deletes, leaving a tombstone. Retiring a workflow status that threads use moves them to a replacement.
- The three-pane **room view** (rooms rail, thread list, the open conversation with its side panel).

0.2.0 added the rooms they live in, on the 0.1.0 platform:

- **Rooms** (`/api/v1/rooms`) — members-only, created from a **workflow template** whose statuses (colour, open/closed category, order) and typed fields the room copies. People or a whole
  team (a snapshot) are added as members; OWNER or ADMIN manages the room, a room always keeps one active OWNER, and a room can be archived (read-only) and unarchived. A non-member sees a uniform 404.
- **Workflow templates** — an ADMIN-curated registry (Default and Incident are seeded, English and Polish). A room's workflow is edited in place by its owners, as a whole document under `X-Expected-Revision`.
- The sidebar **rooms rail** (most recently active first, archived behind a switch) and **team members** (a roster managed by an ADMIN or the team's manager).

The 0.1.0 foundation:

- **Sign-in** with a JWT access/refresh pair, a server-side revocation blocklist, per-account lockout and per-IP rate limits; opt-in **email MFA**; **self-service password reset** by an
  emailed single-use link.
- **Users** (ADMIN-managed, `/api/v1/users`) with per-user **feature flags** and a synced UI/email **language** (English and Polish).
- **Teams** — a flat registry with an optional manager and a roster (a room adds a whole team at once).
- The React **shell**: navigation model, command palette, light/dark theme in the olive Adder brand with the "Strike" logo, a changelog, and the ADMIN **telemetry panel** (memory, JVM,
  requests and storage of the running process, 90 days of history).
- The quality gates, the Docker/Compose/Kubernetes deployment and a Playwright e2e suite, all inherited from Flow.

## Roadmap

0.2.0 rooms and workflows (shipped) · 0.3.0 threads and messages (shipped) · 0.4.0 live updates (shipped) · 0.5.0 filters, sort, search, hashtags · 0.6.0 mentions, reactions, pins, unread · 1.0.0 checkup.
The scope of each version and the decisions behind it: `.claude/docs/product.md`; the domain sketch: `.claude/docs/chat-model.md`; open items: `BACKLOG.md`. `CLAUDE.md`'s "Product" and
"Donors" sections say which sibling project each capability ports from.

## The stack

- **Backend**: Kotlin + [Ktor](https://ktor.io) (Netty), JWT auth with refresh tokens and a
  server-side revocation blocklist, PostgreSQL with [Flyway](https://flywaydb.org) migrations and
  [Exposed](https://github.com/JetBrains/Exposed) (R2DBC), OpenTelemetry, RFC 7807 problem-detail
  errors, Swagger UI at `/openapi` (development mode). Application-level encryption at rest
  (`infra/crypto/FieldCipher`, AES-256-GCM) is in place for the first stored credential. One process serves everything.
- **Frontend**: [Vite](https://vite.dev) + React 19 + TypeScript + [Mantine](https://mantine.dev),
  react-i18next (English + Polish), a typed API client generated from the OpenAPI contract.
- **Quality gates**: detekt (zero findings), Kover coverage floors, dependency-family alignment,
  ESLint + sonarjs, knip, Vitest coverage floors, runtime OpenAPI conformance in the server test
  suite, and Playwright e2e with axe accessibility scans. Pushes to `master` and pull requests run
  the server/web gates and E2E static/setup checks; the full browser suite runs nightly and on
  demand.

## Running the whole stack (one command)

```bash
docker compose up --build
```

Then open <http://localhost:8087> and sign in as `admin@adder.local` / `changeme`. This compose
file runs in development mode (`KTOR_DEVELOPMENT=true`), which tolerates the seed admin's
well-known `changeme` password (the V3 seed migration); any real deployment ships `KTOR_DEVELOPMENT=false` (the Docker
image's default) and **refuses to start** while any active account still carries that password —
set `ADMIN_INITIAL_PASSWORD` to rotate it at first boot instead (see "Configuration" below and
`.claude/docs/security.md`). Swagger UI: <http://localhost:8087/openapi>. Mailpit (reset and MFA
email): <http://localhost:8030>.

Ports are chosen to coexist with the sibling stacks on the same machine (the full table is in
`.claude/docs/ci.md`): the app is on **8087**, Postgres is host-mapped to **5439**, the Vite dev server uses **5179**,
Mailpit **8030**. All host ports bind to 127.0.0.1.

On a cold start the app waits for Postgres rather than exiting: Flyway retries its boot connection
(`POSTGRES_CONNECT_RETRIES`, default 10 — about a minute of backing-off attempts; see "Configuration"),
so `docker compose up` and Kubernetes restarts need no manual ordering.

## Running on Kubernetes (local)

Two Deployments, each a single replica with `Recreate` updates (the old pod stops before the
new one starts, so an upgrade briefly interrupts it): `postgres` and `app` (the HTTP API and the SPA; one process). The application pod migrates on boot and runs in production mode
(`KTOR_DEVELOPMENT=false`), in the `adder` namespace.

With a local cluster that shares the Docker image store (e.g. OrbStack):

```bash
ADDER_IMAGE_TAG="dev-$(git rev-parse --short=12 HEAD)-$(date +%s)"
docker build -t "adder-app:${ADDER_IMAGE_TAG}" .
kubectl create namespace adder
# create the adder-secrets Secret — see the header comment in k8s/secret.yaml
./k8s/apply-local.sh "$ADDER_IMAGE_TAG"
```

The helper renders the deployment image with the selected build tag and excludes the Secret
template. Use a new tag for each rebuild; do not apply `k8s/` directly, since the checked-in image
name is a placeholder. An existing installation already has the namespace and Secret.

The manifests are hardened: no ServiceAccount token in any pod, non-root/seccomp/`drop: [ALL]` on the
app and postgres containers, and `k8s/network-policies.yaml` — default-deny plus only
app → postgres:5432, DNS and public egress on 443 / 587, 465, 25 (SMTP), and 8087 into
the app. NetworkPolicy is enforced only by a CNI that implements it (OrbStack's cluster does). Details
in `.claude/docs/security.md` ("Kubernetes pod and network hardening"). CI validates every manifest and
the `apply-local.sh` render with `kubeconform` (`k8s-static` job).

**Known gap: no ingress is shipped.** `app` is exposed through a bare `type: LoadBalancer`
Service, but the `app` Deployment is configured as if a TLS-terminating proxy sat in front
(`HTTP_BEHIND_PROXY=true`, `HTTP_PROXY_HOPS=1`). Today that means:

- Plain HTTP through the LoadBalancer (e.g. `http://localhost:8087`) is answered with a `301` to
  `https://…` — production mode redirects every request that does not arrive as HTTPS. The pods'
  own probes send `X-Forwarded-Proto: https`, which is why they still pass.
- `X-Forwarded-For` and `X-Forwarded-Proto` are client-supplied here: a bare LoadBalancer neither
  terminates TLS nor strips them, so the per-IP rate-limit buckets key on a value the caller
  controls, and a caller can send `X-Forwarded-Proto: https` to skip the redirect (unverified on
  OrbStack: how its LoadBalancer treats these headers has not been checked).
- The live channel depends on real client addresses too: the per-address share of unauthenticated
  sockets (`live.maxPendingPerAddress`, default 8) keys like the rate-limit buckets, so a caller-chosen
  `X-Forwarded-For` bypasses it. Put a proxy that sets the header in front, or set
  `externalTrafficPolicy: Local` on the Service; with a plain proxy and `HTTP_BEHIND_PROXY=false`, or an
  SNAT load balancer, all clients share one share, so raise `live.maxPendingPerAddress`.
- For throwaway local testing over plain HTTP, edit `k8s/app-deployment.yaml` yourself —
  `apply-local.sh` only substitutes the image tag — setting `KTOR_DEVELOPMENT` to `"true"` (and
  `HTTP_BEHIND_PROXY` to `"false"`), and never commit or apply that in a shared or production
  cluster. Development mode is a different posture, not just "HTTP allowed": it also tolerates
  the burned demo credentials, exposes `/openapi` and the Swagger UI, and lifts the login per-IP
  bucket from 10/min to 1000/min when it is left blank.

Whether the reference deployment gains a TLS-terminating Ingress (with a `ClusterIP` Service) or a
documented local-only overlay is still to be decided; until then treat this manifest set as a local
reference, not a production recipe.

## Local development

Run each long-lived process in a separate terminal from the repository root:

```bash
docker compose up postgres mailpit   # database on :5439 and local mail on :8030
./gradlew :server:run                # API on :8087
(cd web && npm install --legacy-peer-deps && npm run dev)    # SPA on :5179
```

The local JDK and Node runtime are pinned in `mise.toml` (Temurin 21 and Node 24 LTS). Run
`mise install` and use `mise exec -- <command>` or activate mise in your shell. If `./gradlew`
resolves the wrong JDK (a system JRE ahead of the mise shim on `PATH`), point it explicitly:
`JAVA_HOME=$(mise where java) ./gradlew build`. If server tests need a Docker daemon and you use
OrbStack without `/var/run/docker.sock`, export `DOCKER_HOST=unix://$HOME/.orbstack/run/docker.sock`
first. See [dependency maintenance](.claude/docs/dependencies.md) for compatibility pins,
automated updates, and runtime verification. The gates, timed the way CI runs them: `scripts/gates.sh [server|web|e2e|all]`.

## Configuration (environment variables)

`server/src/main/resources/application.yaml` is the authoritative reference — every setting there
is a `$VAR:default` pair, and the table below lists them (defaults as shipped; the
Docker image additionally sets `KTOR_DEVELOPMENT=false`, `MAIL_TRANSPORT=disabled` and
`WEB_STATIC_DIR=/app/web`). Production mode refuses the burned demo keys and the `log` mail
transport at startup (`.claude/docs/security.md`).

JVM options are not in the table: the image bakes its heap/GC flags (`server/build.gradle.kts`), so size a deployment with
`JAVA_OPTS` (the baked heap is `-Xmx256m`). Leave `SERVER_OPTS` unset — the image uses it to switch on its
class-data-sharing (AppCDS) archive, and setting it drops the archive. Build-time identity for the telemetry panel: the image build args `ADDER_BUILD_VERSION` and `ADDER_BUILD_COMMIT`.

| Variable | Default | Purpose |
|---|---|---|
| `KTOR_DEVELOPMENT` | `true` | Development mode (`true`) vs production mode — HSTS/HTTPS redirect and the fail-closed startup checks. The image ships `false`. |
| `LOGIN_LOCKOUT_THRESHOLD` | `5` | Consecutive failures per account before `/login` answers 429. |
| `LOGIN_LOCKOUT_DURATION_SECONDS` | `900` | How long a locked account stays locked. |
| `LOGIN_LOCKOUT_MAX_TRACKED` | `10000` | Maximum in-memory login identities; new identities receive 429 at capacity while active counters and locks remain. |
| `LOGIN_RATE_LIMIT_PER_MINUTE` | *(blank)* | Per-IP login bucket; blank follows the mode (10 prod / 1000 dev). |
| `REFRESH_RATE_LIMIT_PER_MINUTE` | *(blank)* | Per-IP refresh bucket (blank = 30). |
| `READY_RATE_LIMIT_PER_MINUTE` | *(blank)* | Per-IP bucket on `GET /api/v1/ready` (blank = 60; the kubelet probes 12/min). |
| `PASSWORD_RESET_RATE_LIMIT_PER_MINUTE` | *(blank)* | Per-IP reset bucket; blank follows the mode (5 prod / 100 dev). |
| `REFRESH_REPLAY_GRACE_SECONDS` | `60` | How long a rotated refresh token may be replayed (a lost `/refresh` response, two racing tabs) before the replay is a 401; `0` = strict one-time use. In-memory, per instance (`.claude/docs/security.md` "Token storage"). |
| `PASSWORD_RESET_MIN_INTERVAL_SECONDS` | `60` | One reset request per submitted email per interval. |
| `PASSWORD_RESET_MAX_TRACKED` | `10000` | Maximum in-memory reset cooldowns; new identities receive 429 at capacity. |
| `PASSWORD_RESET_TOKEN_TTL_SECONDS` | `900` | Lifetime of the emailed single-use reset link, 1–3600 seconds. |
| `MFA_CODE_TTL_SECONDS` | `300` | Lifetime of an emailed MFA code. |
| `MFA_MAX_ATTEMPTS` | `5` | Wrong-code attempts before a challenge dies. |
| `MFA_MAX_TRACKED` | `10000` | Maximum pending email-MFA challenges; new issuance receives 429 at capacity. |
| `LIVE_MAX_TICKETS` | `10000` | Maximum unexpired live-update socket tickets held (1–100000); a mint receives 429 at capacity (`.claude/docs/live.md`). |
| `DATA_ENCRYPTION_KEY` | *(dev key, burned)* | AES-256-GCM key (64 hex) for stored credentials (none stored yet; reserved for the first stored credential) — the dev default is burned, production refuses it. Back it up apart from the database. |
| `DATA_ENCRYPTION_KEY_PREVIOUS` | *(blank)* | Decrypt-only fallback during a key rotation (boot once, then remove). |
| `SECURITY_CSRF_ENABLED` | `false` | CSRF plugin gate — off (bearer JWT, no cookies). |
| `JWT_SECRET` | `secret` | HMAC key for the access/refresh pair — production requires a private 64-hex key (`openssl rand -hex 32`); the placeholders and compose demo key are burned. |
| `JWT_ISSUER` | `http://0.0.0.0:8087/` | The `iss` claim the verifier requires. |
| `JWT_AUDIENCE` | `adder-api` | The `aud` claim the verifier requires. |
| `JWT_REALM` | `adder-api` | The `WWW-Authenticate` realm. |
| `JWT_ACCESS_EXPIRES_IN_SECONDS` | `900` | Access-token lifetime (the API bearer). |
| `JWT_REFRESH_EXPIRES_IN_SECONDS` | `3600` | Refresh-token lifetime — the idle-session window. |
| `ADMIN_INITIAL_PASSWORD` | *(blank)* | Rotates the V3 seed admin's `changeme` at startup while it still carries the seed hash; production rejects known placeholders and passwords outside the ordinary account limits. |
| `HTTP_BEHIND_PROXY` | `false` | Honour `X-Forwarded-*` from a TLS-terminating proxy (rate-limit keys, HTTPS redirect). |
| `HTTP_PROXY_HOPS` | `1` | Trusted proxies in front of the app when `HTTP_BEHIND_PROXY=true`; `X-Forwarded-For` is trusted from the END of the list (1 = the last proxy's value). |
| `CORS_ALLOWED_HOSTS` | *(blank)* | Comma-separated cross-origin hosts; blank = CORS not installed. |
| `HTTP_EXPOSE_OPENAPI` | *(blank)* | Serve Swagger UI + the spec at `/openapi`; blank follows the mode. |
| `METRICS_REPORT_INTERVAL_SECONDS` | `300` | How often the Dropwizard metrics registry is dumped to the `metrics` logger: `0` = off, otherwise 30..3600 seconds (`.claude/docs/observability.md`). |
| `TELEMETRY_ENABLED` | `true` | The per-process telemetry sampler (JVM, container memory and connection-pool figures into the `ops` schema, 7 days raw and 90 days hourly): `false` starts none (`.claude/docs/observability.md` "Telemetry"). |
| `TELEMETRY_SAMPLE_INTERVAL_SECONDS` | `300` | How often a process samples itself, 60..1800 and a divisor of 3600. |
| `TELEMETRY_STORAGE_INTERVAL_SECONDS` | `3600` | How often database/schema sizes are measured, 900..86400. |
| `WEB_STATIC_DIR` | *(blank)* | Directory of the built SPA to serve; blank in local dev (Vite serves it). The image sets `/app/web`. |
| `MAIL_TRANSPORT` | `log` | `log` (dev only — production refuses it), `smtp`, or `disabled` (email features answer 503; the image default). |
| `SMTP_HOST` | *(blank)* | SMTP server (required for `smtp`). |
| `SMTP_PORT` | `587` | SMTP port. |
| `SMTP_USER` | *(blank)* | SMTP user (optional). |
| `SMTP_PASSWORD` | *(blank)* | SMTP password (optional). |
| `SMTP_STARTTLS` | `true` | STARTTLS on the SMTP connection. |
| `MAIL_FROM` | `adder@localhost` | Sender address of every outbound email. |
| `MAIL_APP_URL` | *(blank)* | The deployment's public **origin** (`https://host[:port]` in production — `http` only in development; no path, query or credentials) — the self-service password reset emails a link built from it. A malformed value refuses startup; blank = the reset answers 503. |
| `POSTGRES_JDBC_URL` | `jdbc:postgresql://localhost:5439/adder` | Flyway's JDBC URL. |
| `POSTGRES_R2DBC_URL` | `r2dbc:postgresql://localhost:5439/adder` | The runtime R2DBC URL. |
| `POSTGRES_USER` | `adder` | Database user. |
| `POSTGRES_PASSWORD` | `adder` | Database password. |
| `POSTGRES_CONNECT_RETRIES` | `10` | How many times Flyway retries its boot connection (doubling waits capped at 8 s, ~63 s at 10) so a cold start waits for Postgres instead of crash-looping; `0` fails fast (0..15 — 15 is ~2 min, inside the k8s startup-probe budget). |
| `POSTGRES_POOL_MAX_SIZE` | `20` | Ceiling on concurrent pooled R2DBC connections for this instance (1..1000). |
| `POSTGRES_POOL_INITIAL_SIZE` | `2` | Connections the pool fills up to on its first acquire (0..maxSize). |
| `POSTGRES_POOL_MAX_ACQUIRE_SECONDS` | `10` | How long a caller waits for a free pooled connection before failing (1..600). |
| `POSTGRES_POOL_MAX_IDLE_SECONDS` | `600` | How long an idle pooled connection may sit before recycling (1..86400). |
| `POSTGRES_POOL_IDLE_IN_TRANSACTION_TIMEOUT_SECONDS` | `300` | PostgreSQL's `idle_in_transaction_session_timeout` on every pooled connection: a session left idle inside an open transaction is ended and its locks released (0 = off, else up to 86400; `.claude/docs/persistence.md` "Connection pool"). |
| `ROOM_LOCK_WAIT_MILLIS` | `3000` | The longest a room write waits for the room's write lock before it answers `429 room-busy` with `Retry-After: 1` (100..30000; `.claude/docs/persistence.md` "Rooms (V13)"). |

## Useful Gradle tasks

| Task | What it does |
| ---- | ------------ |
| `./gradlew build` | Compiles everything and runs every gate: detekt, tests (Testcontainers), Kover verify, dependency alignment |
| `./gradlew :server:run` | Runs the API against the compose Postgres |
| `./gradlew :server:test` | Server test suite (needs a Docker daemon for Testcontainers) |
| `./gradlew detekt` | Static analysis only |
| `./gradlew :server:checkDependencyAlignment` | One version per aligned dependency family on the runtime classpath |
| `./gradlew :server:installDist` | Builds the runnable distribution (used by the Docker image; never `buildFatJar`) |

Frontend: `cd web && npm run build | lint | test | test:coverage | knip | gen:api`.
E2E: `cd e2e && npm ci && npx playwright install chromium && npm test`.

## License

MIT — see [LICENSE](LICENSE).
