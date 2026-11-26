# Adder

Adder is **a chat tool crossed with an issue tracker** (rooms and threads, each thread with a workflow status and an owner) for short-lived, intensive, asynchronous collaboration — e.g. 30+ people working a complex, multi-threaded incident without chaos. People work in
**rooms**; all talk lives in **threads** (you either start a thread or reply in one); every thread carries a **workflow status plus configurable metadata** and an
**owner**, and a thread in a closed status takes no further activity until it is reopened. Room content is filterable, sortable and searchable; the usual chat affordances apply
(pins, @mentions, reactions, emoji, #hashtags), and updates reach open browsers live over a WebSocket. The name is the snake: quick, quiet, decisive.

## What's here today

### Fewer duplicate reads after message edits (1.34.0)

After a successful message edit, the sending tab reuses its refresh when the matching live event arrives. Other readers still see the edit live, including in pins and quotes. Edit history remains available to the author and authorized moderators.

### Fewer duplicate reads after chat updates (1.33.0)

When the app and API share an origin, a successful reaction, owner handover, status change, or edit to thread details or curated tags refreshes the sending tab once. Other tabs still receive the change live. Interrupted saves can recover through the live event.

### Optional two-instance deployment (1.32.0)

The Kubernetes profile can run two app processes behind one ingress and one PostgreSQL service. Tickets, account limits, socket admission, lookup of foreground tabs, and notification pacing work across both processes. The local helper keeps a single app by default.

### Shared limits for parallel chat actions (1.31.0)

Mark all as read and link previews each keep one in-flight request per account across application instances. An abandoned slot expires. Preview fetching holds no database connection while it contacts the external site.

### Shared digest delivery intervals (1.30.0)

Notification digest recipient intervals survive app restarts and apply across application instances. Adder reserves the recipient interval and sender pacing together before it contacts the email provider.

### Shared request limits (1.29.0)

Application request allowances are shared through PostgreSQL. Clients keep the same quotas and headers. Health probes keep a separate allowance for each app process.

### Shared email sign-in codes (1.28.0)

Instances using the same database and data encryption keys can verify each other's pending MFA challenges. Each code is single-use and its attempts and expiry are shared. Pending challenges issued by an earlier release need a fresh sign-in after the update.

### Shared sign-in protection (1.27.0)

Account credential attempts and password-reset request cooldowns use PostgreSQL. Limits persist across restarts. Password reset confirmation clears active lockout state in the password change transaction. Stored throttle keys are deployment-keyed digests.

### Shared session refresh and logout (1.26.0)

An interrupted refresh can retry on another instance using the same database. Logout revokes the current session's credentials and clears refresh grace in one transaction. Other signed-in sessions keep working. Deploy this release with the existing single-instance replacement strategy.

### Shared live socket limits and presence (1.25.0)

The per-user tab limit and foreground presence use shared PostgreSQL leases. `@here` can reach eligible room members whose foreground tabs connect through another application instance. Each process retains its own connection resource limits. The supplied deployments still run a single app.

### Durable live connection tickets (1.24.0)

- The connection ticket survives an app restart until it expires or is redeemed. The app rechecks session access when the browser reconnects.

### Faster reply refreshes (1.23.0)

- When the app and API share an origin, replying avoids a duplicate fetch cycle in the sending tab. Other tabs still see new replies live.

### Live account updates (1.22.0)

- Open conversations recheck access promptly after a logout, a role change, account deletion, or a password change. Other valid sign-ins remain connected.

### Notification retention and email pacing (1.21.0)

- The service retains up to 5,000 unseen notices per person. Expired notices leave the bell and cancel pending delivery. Read state stays independent.
- Digest emails space attempts from each actor to each recipient by at least an hour. The ordinary recipient digest interval and notification preferences still apply.

### Thread tags and message edit history (1.20.0)

- Choose curated **Tags** in the new-thread form. They save together with the opening message.
- Authors who are still room members, room owners, and administrators with room access can open **Edit history** from the message menu.
  Earlier bodies appear as plain text. Deleting a message removes its saved versions.
- API clients can use `GET /api/v1/rooms/{id}/hashtags` and the thread-list `hashtag` parameter. The existing `tags` path and `tag`
  parameter continue to work, and `threadTag` still selects curated tags.

### Thread workflow and conversation (1.19.0)

- Workflow changes appear between replies. Open **Details** for the full history.
- Use **Thread actions** to delete a thread or merge a duplicate into another thread in the same room. The destination lists the source under **Merged conversations**. Existing links still open the source history.
- Choose **Quote in another thread…** from a message menu to carry it to a reply in another thread or room. Readers see the quoted message only while they can access its source.
- The bell can notify you when someone reacts to your message. Team mentions name the team when you still belong to it.
- Mentions and hashtags in indented code, raw HTML, and image alt text are ignored.

### Chat and notification controls (1.18.0)

- Open the watcher count in a thread header or the people list for a reaction to see the full roster and open profiles. Names in history, search results, and thread lists also lead to profiles.
- Replying starts a watch. Use **Watch this thread** to change that choice. If you stop watching, later replies respect your choice. Use **Mute thread** to silence future notifications for that thread while keeping its watch.
- Pick `@here` to notify room members with a recently visible Adder tab. Room managers can use **Add and mention** to search the directory and add someone while keeping the draft.
- The bell shows what changed in a status or ownership update. A configured deployment offers **Browser notifications** in account notification preferences. Each browser opts in separately and receives generic alerts for unseen in-app notifications. Registrations expire after 90 days and can be enabled again there.

### External links and previews (1.13.0)

Select an external link or image in a message to review its destination. Choose **Open link** to open it in a new tab, or **Preview**
to see a page title or a supported PNG or JPEG image in a compact popover. Adder fetches a preview after you request it. In the
conversation, hovering can show a preview you already loaded.

### Room organizer (1.12.0)

Open **Organize rooms** on **All rooms** to group and order the rooms in your sidebar. Drag a room into a group or drag a group to change its position.
The row menus offer the same moves by keyboard or tap. Choose **Save organization** to apply the layout to your account.

### Search by status and hashtag (1.11.0)

Use `status:investigating` to find threads currently in that workflow status, or `hashtag:incident` to find messages with `#incident`.
Combine them with each other and with other clauses in global, room, or in-thread search. Pick a room to see its workflow keys and hashtag suggestions.

### Find in thread (1.10.0)

Open **Find in thread** in the conversation header to search messages in that thread. Enter plain keywords or use structured clauses such as
`"exact phrase" -draft`. Choose a result to read it with the messages that came before and after it. Existing links from quotes, pins, and
notifications use the same view. Use **Earlier messages** or **Later messages** to browse, and **Back to latest** to return to the conversation's end.

### Personal room groups (1.8.0)

Use **Organize rooms** on **All rooms** to create private groups for rooms you have joined. Move rooms between groups, reorder
groups and rooms, and save to update your sidebar. Your setup follows your account across devices.

### Personal Home (1.7.0)

Home shows the latest replies quoting your messages, open threads you own, watched threads with unread replies first, and rooms you own. Each section shows up to five items. Quote links open the reply in context, and pending join requests link to the room's Members tab.

### Room access (1.6.0)

Choose a room's access when you create it. Any signed-in user can find and join a **Public** room. Anyone can find a **Restricted, visible** room and request to join. A **Restricted, hidden** room is visible only to members. Existing rooms become restricted hidden.

Use **Members** to add people directly, review join requests and promote members to co-owner. You can step down when another active owner remains. People who join can read existing threads and replies.

Adder's repository started as an exact copy of [Flow](https://github.com/liveweird/flow) (1.57.1, `4e43e480`), trimmed to its generic foundation. 1.5.0 gives room Search, Members, Workflow and Settings a shared navigation layout. Search groups its filters, Members puts the roster first, Workflow keeps save actions in reach, and Settings separates room details, availability and deletion. The tabs wrap on narrow screens.

1.4.1 puts available status actions in the thread header and updates the Strike logo for both themes and browser tabs. 1.4.0 makes the workspace clearer and more efficient on every screen size:

- **Focused chat** — on larger screens the thread list and conversation use the available height and scroll independently. On phones an open thread gets a focused view with a clear return to the list.
- **Conversation clarity** — compact room and thread headers, clearer message hierarchy, comfortable line lengths, quieter actions and a reply composer pinned below the message timeline keep attention on the discussion. Workflow controls, metadata, tags, timestamps and history stay organised in Details.
- **Responsive room directory** — All rooms is a scannable table on larger screens and cards on narrow screens, with sorting and filters in both layouts.
- **Navigation** — a cleaner application header and command palette, plus an accessible mobile drawer with managed focus and Escape or backdrop closing.

1.3.0 added direct message quotes:

- **Quotes** — quote any earlier message in the same thread from its one-click action or menu. The reply shows the current message text and author, links back to the original, handles edited or deleted originals and notifies the quoted author unless you quote yourself.

1.2.0 gives the conversation more room and makes a busy room easier to sort:

- **Room view** — the thread's side panel opens on demand ("Details", remembered per person and browser) and the room headers are compact, so the conversation and the composer get the space. Pinned messages are marked where they stand and an open thread keeps its pins in a strip under its header.
- **Names as links** — a person's profile page (`/people/:id`: their teams and the rooms you share; an ADMIN sees every room; never the role or settings; `GET /api/v1/users/directory/{id}`), and people, rooms and teams are links wherever they are named. `GET /api/v1/rooms` and `GET /api/v1/teams` take `memberId`.
- **Hashtags on threads** — a thread's hashtags show on its row and header, and `#42` in a message links to thread 42 of the room.
- **Tags** — an ADMIN-curated registry (`/api/v1/tag-categories`, the **Kind** and **Area** categories are seeded) and curated tags on threads (`PUT /api/v1/rooms/{id}/threads/{number}/tags`: any member while the thread is open, its stewards once it is closed); the thread list and both searches filter by `threadTag`, a tag alone is a valid search.
- **Unread** — an "Unread" filter on the thread list and "Mark all as read" for a room (`POST /api/v1/rooms/{id}/read-all`, with a confirmation; it settles the room's notices too). The browser tab's title counts the bell's unseen notices; a hidden tab goes quiet after 5 minutes and disconnects after 8 hours, and tabs of one browser tell each other when notices were seen.

1.1.0 let people follow what matters and address groups:

- **Watching** — watch a thread (you watch the ones you start and the ones handed to you); a "Watching" filter on the thread list and a "Threads I watch" search, in one room and across rooms, show only those threads. Watchers are told of replies, status changes (close and reopen included) and owner changes (`PUT /api/v1/rooms/{id}/threads/{number}/watch`).
- **Notification kinds** — twelve kinds (a new thread in a room, a reply, a status change, a hand-over, the four mentions, a quote of your message, ...); each person is told at most once per event, with the most specific reason. "Notification preferences" is a grid of an in-app and an email switch per kind: in-app is on by default and **email is off by default for every kind**, so an upgrade from 1.0.0 stops the digest until each person switches it on. Account email (the MFA code, the password-reset link) is not a notification and always goes.
- **Group mentions** — `@team-handle` (an ADMIN gives a team a mention handle in the team editor), `@watchers` and `@everyone`, usable by any room member; an event that would notify more than 20 people counts against its author's budget of 500 such events a day.

0.6.0 made the rooms social:

- **Mentions** — type `@` in the composer and pick a room member (or a team or a group, 1.1.0); the mention shows as the person's current name (`<@id>` in the stored body), and the thread list's "Mentions me" filter shows only the threads that mention you.
- **Notifications** — a bell in the header counts your unseen notices (someone mentioned you or your team, handed a thread over, or replied in or moved a thread you watch); a click opens the message, "Mark all as seen" clears the count, and new ones arrive live. The bell also tells you when you are added to a room or your request to join is approved. A notice is ids only
  (`GET /api/v1/notifications`), kept for 90 days. A notice still unseen after five minutes is summed up in **one email digest** (at most one every 15 minutes; who, the room and the thread number with a link, never the message), switched ON per kind (it is off by default) under "Notification preferences" in the
  account menu; it needs `MAIL_TRANSPORT=smtp` and `MAIL_APP_URL`.
- **Unread** — a dot in the rooms rail, unread counts in the thread list and a "New" line in the conversation where you stopped reading, with a jump to it.
- **Reactions** — one emoji per reaction, shown with its count and the first few people; press it again to take yours back (20 distinct emoji per message, 5 per person).
- **Pins** — pin threads and messages to a room (any member; 50 a room); the room header's pinned panel lists them.

0.5.0 made a busy room findable:

- **Filters and sorts in the URL** — filter a room's threads by status, owner, hashtag and the room's choice and person fields, and sort by activity, newest, number or workflow order; the address bar carries the filter, so a link shows the same list to whoever opens it.
- **#hashtags** — written in messages, each is a link to the room's threads that use it; the room's tag list is paged.
- **Search** one room or all your joined rooms. Combine phrases and filters in a query such as `"disk alert" -test in:"On Call" during:2026-11 has:link`. Open the syntax guide to insert clauses and see examples. Turn on **Structured mode** to match whole words in a query with no operators. [Search syntax](.claude/docs/chat-model.md#structured-search-190).
- **Jump to a message** (`?message=`) from a search hit or a notice, and the **focus view** (the thread list hidden, the conversation at full width).

0.4.0 made the rooms live:

- **Live updates** over a WebSocket (`GET /api/v1/live`; `.claude/docs/live.md`) — the browser trades its bearer for a single-use ticket (`POST /api/v1/live/tickets`) and sends it in the socket's first frame. Every
  chat write emits one PostgreSQL `NOTIFY` hint (ids only) inside its transaction; one `LISTEN` connection per process fans the hints out to the sockets subscribed to a room, and the page refetches what changed. Threads,
  messages, status, owner and detail changes, members and the room workflow update without a reload; the header says **Reconnecting…** while the connection is down, or **Live updates off** when a user's tab cap is hit
  (16 sockets per user; a tab hidden for five minutes goes quiet, keeping its socket for personal notices, and closes after eight hours), and a removed member's room leaves the rail at once. A committed logout rechecks its exact session, and deletion, password and role changes recheck the account's sockets. The periodic sweep remains a fallback.
  Tickets, per-user admission and foreground leases are shared through PostgreSQL. The supplied Kubernetes profile supports an optional second app replica. The ADMIN telemetry panel shows the open sockets, the listener and the NOTIFY queue usage.
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

- **Rooms** (`/api/v1/rooms`) — created from a **workflow template** whose statuses and typed fields the room copies. A room can be archived (read-only) and unarchived. Owners can add people or a whole team as a snapshot of its current roster.
- **Workflow templates** — an ADMIN-curated registry (Default and Incident are seeded, English and Polish). A room's workflow is edited in place by its owners, as a whole document under `X-Expected-Revision`.
- The sidebar **rooms rail** (personal groups and order, recent activity for unplaced rooms, archived behind a switch) and **team members** (a roster managed by an ADMIN or the team's manager).

The 0.1.0 foundation:

- **Sign-in** with a JWT access/refresh pair, a server-side revocation blocklist, per-account lockout and per-IP rate limits; opt-in **email MFA**; **self-service password reset** by an
  emailed single-use link.
- **Users** (ADMIN-managed, `/api/v1/users`) with per-user **feature flags** and a synced UI/email **language** (English and Polish).
- **Teams** — a flat registry with an optional manager and a roster (a room adds a whole team at once).
- The React **shell**: navigation model, command palette, light/dark theme in the olive Adder brand with the "Strike" logo, a changelog, and the ADMIN **telemetry panel** (memory, JVM,
  requests and storage of the running process, 90 days of history).
- The quality gates, the Docker/Compose/Kubernetes deployment and a Playwright e2e suite, all inherited from Flow.

## Roadmap

0.2.0 rooms and workflows (shipped) · 0.3.0 threads and messages (shipped) · 0.4.0 live updates (shipped) · 0.5.0 find: filters, sort, search, hashtags (shipped) · 0.6.0 social: mentions, notifications, reactions, pins, unread (shipped) · 1.0.0 the checkup (shipped) · 1.1.0 watching, group mentions, notice kinds (shipped) · 1.2.0 room view, tags, unread (shipped) · 1.3.0 quotes · 1.4.0 clearer workspace.
The scope of each version and the decisions behind it: `.claude/docs/product.md`; the domain sketch: `.claude/docs/chat-model.md`; open items: `BACKLOG.md`. `CLAUDE.md`'s "Product" and
"Donors" sections say which sibling project each capability ports from.

## The stack

- **Backend**: Kotlin + [Ktor](https://ktor.io) (Netty), JWT auth with refresh tokens and a
  server-side revocation blocklist, PostgreSQL with [Flyway](https://flywaydb.org) migrations and
  [Exposed](https://github.com/JetBrains/Exposed) (R2DBC), OpenTelemetry, RFC 7807 problem-detail
  errors, Swagger UI at `/openapi` (development mode). Application-level encryption at rest
  (`infra/crypto/FieldCipher`, AES-256-GCM) protects browser push endpoints and pending email MFA codes and delivery addresses.
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

**Backup and restore.** `scripts/db-backup.sh` dumps the database (`pg_dump -Fc`, taken inside the Postgres container, so
nothing to install) and `scripts/db-restore.sh` loads a dump into an EMPTY database — it refuses a non-empty one unless you
pass `--replace`. Both also work against Kubernetes (`ADDER_PG_EXEC='kubectl -n adder exec -i deploy/postgres --'`). The
runbook, with the restore order and what a dump does not hold (`DATA_ENCRYPTION_KEY`, `JWT_SECRET`), is in
`.claude/docs/persistence.md` "Backup and restore".

## Running on Kubernetes (local)

The `adder` namespace has `postgres` and `app` Deployments. Each defaults to one replica. `Recreate` stops the old app pods
before starting the new version, so upgrading briefly interrupts the app. Application pods migrate on boot and use production
mode (`KTOR_DEVELOPMENT=false`).

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

For two app replicas, build an immutable image from Adder 1.32.0 or later. Pass the checked-out `APP_VERSION` in
`--build-arg ADDER_BUILD_VERSION` and the full commit SHA in `--build-arg ADDER_BUILD_COMMIT`. Apply it with the default invocation
first. Once its Deployment is ready, apply the same tag again
with `./k8s/apply-local.sh "$ADDER_IMAGE_TAG" --replicas=2`. Before scaling, the helper verifies that every ready app pod's
version and commit stamps match the selected local image. Both replicas use the same database, deployment secrets, and public
origin. The ingress may send a ticket request and the corresponding socket upgrade to different replicas.

The default connection budget for two apps is 48 steady PostgreSQL connections, including the two dedicated listeners.
Allow two short-lived Flyway connections during a concurrent start and leave capacity for the administration and monitoring
clients. The supplied PostgreSQL limit of 100 covers this budget at the default pool size of 23. The two-app render sets the
mail budget to 50 digest sends per sweep on each app, for the same combined default of 100. Run the one-argument helper with
the same image tag to return to one replica. For an upgrade or rollback, apply the target build with one replica first and
opt back in once that build is ready and supports the shared stores.


The manifests are hardened: no ServiceAccount token in any pod, non-root/seccomp/`drop: [ALL]` on the
app and postgres containers, and `k8s/network-policies.yaml` — default-deny plus only
app → postgres:5432, DNS and public egress on 587, 465, 25 (SMTP), and 8087 into
the app from the ingress-nginx controller only. NetworkPolicy is enforced only by a CNI that implements it (OrbStack's cluster does). Details
in `.claude/docs/security.md` ("Kubernetes pod and network hardening"). CI validates every manifest and
the `apply-local.sh` render with `kubeconform` (`k8s-static` job).

**The front door is an ingress-nginx Ingress** (`k8s/ingress.yaml`). The `app` Service is `ClusterIP`, so
no Service path reaches the pod except the Ingress, and `allow-app-ingress` admits only the ingress-nginx
controller's pods (a node-local or `hostNetwork` process and `kubectl port-forward` can still reach it, as in any cluster). The cluster needs the ingress-nginx controller in its `ingress-nginx` namespace (not part of
`k8s/`; community ingress-nginx was retired in March 2026, so this is a local reference, not a production
recipe). Then open <https://adder.k8s.orb.local> (OrbStack resolves `*.k8s.orb.local` ingress hosts). With no
`secretName` under `tls:` the controller serves its self-signed fake certificate, so the browser warns once;
plain HTTP is answered with a `308` to https.

- **Install the controller once per cluster** (OrbStack: enable Kubernetes first, `orbctl config set k8s.enable true`, then restart
  OrbStack once). Pinned to the last community release; the manifest pins the controller image by digest:
  `kubectl --context orbstack apply -f https://raw.githubusercontent.com/kubernetes/ingress-nginx/controller-v1.15.1/deploy/static/provider/cloud/deploy.yaml`, then
  `kubectl --context orbstack -n ingress-nginx wait --for=condition=Ready pod -l app.kubernetes.io/component=controller --timeout=240s`.
  Undo: the same command with `delete -f` (removes the `ingress-nginx` namespace, its cluster RBAC, the `nginx` IngressClass
  and the admission webhook). The cloud-provider manifest creates a `LoadBalancer` Service with `externalTrafficPolicy: Local`;
  OrbStack gives it an address of its own and resolves `*.k8s.orb.local` ingress hosts to it.
- The app trusts exactly one `X-Forwarded-For` hop (`HTTP_PROXY_HOPS=1`): ingress-nginx's defaults replace
  the header with the address it saw, and the NetworkPolicy keeps every other pod away from the app, so a
  client-sent `X-Forwarded-For` never becomes the rate-limit key (`.claude/docs/security.md`).
- The live WebSocket passes the Ingress (read/send timeouts 120 s against the server's 25 s pings).
- **OrbStack shows every client as one address.** The controller sees the pod-network gateway (`192.168.194.1` on the
  tested cluster) as the peer of every request, even with `externalTrafficPolicy: Local`, so the app's per-IP rate-limit buckets and
  the live per-address pending share (`live.maxPendingPerAddress`, 8) are shared by everyone on the cluster. Fine for one local user;
  there is no OrbStack-side fix (the audit trail carries no client address by design, so check the controller's access log, whose
  first field is that peer). A real cluster keeps the browser's address when its load balancer preserves it.
- A real host needs three edits in step: the host in `ingress.yaml` (twice), a certificate Secret named under
  `tls: secretName` whose SAN has that host, and `MAIL_APP_URL` in `app-deployment.yaml` when mail is on. CORS
  stays empty (single origin).
- `kubectl -n adder port-forward svc/app 8087` bypasses the Ingress for debugging: production mode answers
  plain HTTP with its `301` to https there. For throwaway plain-HTTP testing, edit `k8s/app-deployment.yaml`
  yourself — `apply-local.sh` only substitutes the image tag — setting `KTOR_DEVELOPMENT` to `"true"` (and
  `HTTP_BEHIND_PROXY` to `"false"`), and never commit or apply that in a shared or production cluster.
  Development mode is a different posture, not just "HTTP allowed": it also tolerates the burned demo
  credentials, exposes `/openapi` and the Swagger UI, and lifts the login per-IP bucket from 10/min to
  1000/min when it is left blank.

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

To create the optional Web Push key pair, keep `vapid.pem` out of the repository and deployment image:
`openssl ecparam -name prime256v1 -genkey -noout -out vapid.pem`. Encode the private key with
`openssl pkcs8 -topk8 -nocrypt -in vapid.pem -outform DER | openssl base64 -A | tr '+/' '-_' | tr -d '='` and the browser public key with
`openssl ec -in vapid.pem -pubout -conv_form uncompressed -outform DER | tail -c 65 | openssl base64 -A | tr '+/' '-_' | tr -d '='`.
Store those outputs in the two `NOTIFICATIONS_PUSH_*_KEY` secrets and securely remove the temporary PEM.
The local Compose stack forwards those settings and `NOTIFICATIONS_PUSH_SUBJECT` from its environment.
For a hosted deployment, supply the settings through the app's environment and serve Adder over HTTPS.

| Variable | Default | Purpose |
|---|---|---|
| `KTOR_DEVELOPMENT` | `true` | Development mode (`true`) vs production mode — HSTS/HTTPS redirect and the fail-closed startup checks. The image ships `false`. |
| `KTOR_STARTUP_TIMEOUT_MILLIS` | `140000` | How long Ktor lets module loading take before it cancels startup. The default outlasts the cold-start Flyway retry wait and stays inside the k8s startup probe; a database whose V16 search backfill outruns it needs this AND the probe's `failureThreshold` (`k8s/app-deployment.yaml`) raised together. |
| `LOGIN_LOCKOUT_THRESHOLD` | `5` | Consecutive failures per account before `/login` answers 429. |
| `LOGIN_LOCKOUT_DURATION_SECONDS` | `900` | How long a locked account stays locked. |
| `LOGIN_LOCKOUT_MFA_MAX_DURATION_SECONDS` | `86400` | Ceiling of the progressive lock: a lock tripped by wrong emailed codes doubles per consecutive such lock (15 min, 30, 60, ...) up to this; wrong passwords keep the flat duration. |
| `LOGIN_LOCKOUT_MFA_DECAY_SECONDS` | `86400` | How long after the last escalated lock ends its level is forgotten, and the period of the per-account wrong-code counter (a fixed window from its first failure: threshold wrong codes inside it trip an escalated lock even when paced). A successful sign-in or a confirmed reset forgets both at once. |
| `LOGIN_LOCKOUT_MAX_TRACKED` | `10000` | Maximum PostgreSQL rows for ordinary untrusted login identities; new identities receive 429 at capacity. Proved accounts have one additional stable reservation per user. |
| `LOGIN_RATE_LIMIT_PER_MINUTE` | *(blank)* | Per-IP login bucket; blank follows the mode (10 prod / 1000 dev). |
| `REFRESH_RATE_LIMIT_PER_MINUTE` | *(blank)* | Per-IP refresh bucket (blank = 30). |
| `READY_RATE_LIMIT_PER_MINUTE` | *(blank)* | Per-IP bucket on `GET /api/v1/ready` (blank = 60; the kubelet probes 12/min). |
| `PASSWORD_RESET_RATE_LIMIT_PER_MINUTE` | *(blank)* | Per-IP reset bucket; blank follows the mode (5 prod / 100 dev). |
| `REFRESH_REPLAY_GRACE_SECONDS` | `60` | How long a rotated refresh token may be replayed (a lost `/refresh` response, two racing tabs) before the replay is a 401; `0` = strict one-time use. Shared in PostgreSQL, with at most two retries per token (`.claude/docs/security.md` "Token storage"). |
| `PASSWORD_RESET_MIN_INTERVAL_SECONDS` | `60` | One reset request per submitted email per interval. |
| `PASSWORD_RESET_MAX_TRACKED` | `10000` | Maximum PostgreSQL reset-cooldown rows; new identities receive 429 at capacity. |
| `PASSWORD_RESET_TOKEN_TTL_SECONDS` | `900` | Lifetime of the emailed single-use reset link, 1–3600 seconds. |
| `MFA_CODE_TTL_SECONDS` | `300` | Lifetime of an emailed MFA code. |
| `MFA_MAX_ATTEMPTS` | `5` | Wrong-code attempts before a challenge dies. |
| `MFA_MAX_TRACKED` | `10000` | Maximum pending email-MFA challenges; new issuance receives 429 at capacity. |
| `LIVE_MAX_TICKETS` | `10000` | Maximum unexpired live-update socket tickets shared by the deployment (1–100000); a mint receives 429 at capacity (`.claude/docs/live.md`). |
| `LIVE_MAX_SOCKETS` | `1000` | Authenticated live-update sockets at once, the whole process (1–10000); past it an upgrade and a ticket mint answer 503 `live-unavailable`. |
| `LIVE_MAX_SOCKETS_PER_USER` | `16` | Sockets one user may hold across instances, one per tab (1–64); past it a mint answers 429 `live-socket-limit` and a redeem closes the socket 4429. |
| `LIVE_MAX_PENDING_AUTH` | `256` | Sockets that have upgraded but not yet sent a good `auth` frame, the only unauthenticated connections the server holds (1–10000); past it an upgrade answers 503. |
| `LIVE_MAX_PENDING_PER_ADDRESS` | `8` | Of those, how many one client address may hold (1–10000; an IPv6 address counts by its /64). |
| `LIVE_AUTH_TIMEOUT_MILLIS` | `2000` | How long an unauthenticated socket lives before it is closed 4401 (100–30000 ms; the first frame must carry the ticket). |
| `LIVE_CONNECT_RATE_LIMIT_PER_MINUTE` | *(blank)* | Per-IP bucket on the WebSocket upgrade `GET /api/v1/live`; blank = 600 in every mode. |
| `DATA_ENCRYPTION_KEY` | *(dev key, burned)* | AES-256-GCM key (64 hex) for stored browser-push endpoints — the dev default is burned, production refuses it. Back it up apart from the database. |
| `DATA_ENCRYPTION_KEY_PREVIOUS` | *(blank)* | Decrypt-only fallback during rotation. Keep it until browser subscriptions encrypted with the old key have been re-registered, removed, or expired and pending MFA challenges under that key have expired. |
| `SECURITY_CSRF_ENABLED` | `false` | CSRF plugin gate — off (bearer JWT, no cookies). |
| `JWT_SECRET` | `secret` | HMAC key for the access/refresh pair — production requires a private 64-hex key (`openssl rand -hex 32`); the placeholders and compose demo key are burned. |
| `JWT_ISSUER` | `http://0.0.0.0:8087/` | The `iss` claim the verifier requires. |
| `JWT_AUDIENCE` | `adder-api` | The `aud` claim the verifier requires. |
| `JWT_REALM` | `adder-api` | The `WWW-Authenticate` realm. |
| `JWT_ACCESS_EXPIRES_IN_SECONDS` | `900` | Access-token lifetime (the API bearer). |
| `JWT_REFRESH_EXPIRES_IN_SECONDS` | `3600` | Refresh-token lifetime. A session nothing refreshes ends after it, but a signed-in tab keeps refreshing (a visible one for ever; a hidden one for up to 8 h, then this lifetime: `.claude/docs/security.md` "Token storage"). |
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
| `SMTP_STARTTLS` | `true` | STARTTLS on the SMTP connection (port 587). Production refuses `smtp` with this and `SMTP_SSL` both `false`. The certificate's name is always checked against `SMTP_HOST` when TLS is on. |
| `SMTP_SSL` | `false` | Implicit TLS from the first byte (port 465; a warning at startup if paired with 587 or 25); wins over `SMTP_STARTTLS` when both are set. |
| `MAIL_FROM` | `adder@localhost` | Sender address of every outbound email. |
| `MAIL_APP_URL` | *(blank)* | The deployment's public **origin** (`https://host[:port]` in production — `http` only in development; no path, query or credentials) — the self-service password reset emails a link built from it. A malformed value refuses startup; blank = the reset answers 503. The notification digest builds its links from it too (none when blank). |
| `NOTIFICATIONS_EMAIL_DELAY_SECONDS` | `300` | A notice still unseen after this many seconds is emailed in a digest (0..86400). With `MAIL_TRANSPORT=disabled` nothing is mailed. |
| `NOTIFICATIONS_EMAIL_MIN_INTERVAL_SECONDS` | `900` | At most one digest per person per this many seconds across the deployment (0..86400). New notices wait for the next eligible digest. |
| `NOTIFICATIONS_EMAIL_MAX_PER_RUN` | `100` | The most digest emails one sweep tick sends (1..1000). The digest shares the mailer with MFA codes and password resets, so this budget protects them; the people beyond it wait for the next tick. |
| `NOTIFICATIONS_EMAIL_MAX_RECIPIENTS_PER_ACTOR_PER_DAY` | `20` | The most distinct people one person's @mentions and hand-overs can make eligible for the digest in a UTC day (1..1000). Over it the bell notice is still made and only its email is withheld. An ADMIN is not exempt. |
| `NOTIFICATIONS_MAX_BROADCASTS_PER_ACTOR_PER_DAY` | `500` | The most WIDE events (more than 20 people notified: a new thread in a big room, a reply in a heavily watched thread, a status move or a hand-over) one person causes in a UTC day (1..10000). Over it the write still succeeds and only the people named directly (`<@id>` mentions, the author of a quoted message, a hand-over's new owner) are told; one `notification.broadcast_budget_exceeded` audit line per person and day. |
| `NOTIFICATIONS_PUSH_PUBLIC_KEY` | *(blank)* | Unpadded base64url raw uncompressed P-256 public point (65 bytes, `04` prefix). Browser push is unavailable unless this, the private key and subject are all set. |
| `NOTIFICATIONS_PUSH_PRIVATE_KEY` | *(blank)* | Unpadded base64url PKCS#8 DER P-256 private key. Keep it in the deployment secret, never the repository. |
| `NOTIFICATIONS_PUSH_SUBJECT` | *(blank)* | VAPID operator contact, an absolute `mailto:` or `https:` URI. |
| `NOTIFICATIONS_PUSH_MAX_PER_RUN` | `100` | Queue rows claimed per sweep tick (1..500); at most four provider requests run concurrently. |
| `NOTIFICATIONS_RETENTION_DAYS` | `90` | How long a notice is kept before the sweep deletes it, at most once an hour (1..3650). |
| `NOTIFICATIONS_SWEEP_INTERVAL_SECONDS` | `60` | How often the notification sweep wakes (10..3600); the email digest rides the same tick. |
| `NOTIFICATIONS_SWEEP_ENABLED` | `true` | `false` keeps the sweep (purge and digest) from starting; the test harness turns it off. |
| `SEARCH_TIMEOUT_SECONDS` | `5` | The budget of one search request (all its statements); past it the search is cancelled and answers 429 `search-timeout` (1..30). |
| `SEARCH_MAX_CONCURRENT` | `4` | The most searches this process runs at once; the next answers 429 `search-busy` (1..16). With `THREADS_READ_MAX_CONCURRENT`, the notification list's fixed cap of 4 and the thread detail's fixed cap of 3, production startup requires at least four connections below `POSTGRES_POOL_MAX_SIZE` for writes and token checks. |
| `THREADS_READ_TIMEOUT_SECONDS` | `5` | The budget of one filtered thread-list or tag-list read; past it the read is cancelled and answers 429 `read-timeout` (1..30). |
| `THREADS_READ_MAX_CONCURRENT` | `8` | The most filtered thread-list and tag-list reads this process runs at once; the next answers 429 `read-busy` (1..16). |
| `THREADS_TAG_REINDEX_ON_BOOT` | `true` | Index the hashtags of messages written before the index, once after boot and without blocking the start; `false` leaves them unindexed until a boot that has it on. |
| `POSTGRES_JDBC_URL` | `jdbc:postgresql://localhost:5439/adder` | Flyway's JDBC URL. |
| `POSTGRES_R2DBC_URL` | `r2dbc:postgresql://localhost:5439/adder` | The runtime R2DBC URL. |
| `POSTGRES_USER` | `adder` | Database user. |
| `POSTGRES_PASSWORD` | `adder` | Database password. Production mode (`KTOR_DEVELOPMENT=false`) **refuses to start** on this default, on a blank value and on the k8s `CHANGE-ME` placeholder: set a private one on the database and the app (`openssl rand -hex 16`). The compose demo runs in development mode and keeps `adder`. |
| `POSTGRES_CONNECT_RETRIES` | `10` | How many times Flyway retries its boot connection (doubling waits capped at 8 s, ~63 s at 10) so a cold start waits for Postgres instead of crash-looping; `0` fails fast (0..15 — 15 is ~2 min, inside the k8s startup-probe budget). |
| `POSTGRES_POOL_MAX_SIZE` | `23` | Ceiling on concurrent pooled R2DBC connections for this instance (1..1000). |
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
