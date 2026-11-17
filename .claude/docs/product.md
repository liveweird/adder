# Product, roadmap and donors

Owns: what Adder is, the scope of every version, the decisions the user took, the defaults taken without asking, what each donor gave, and the
release history (newest first). Not here: the binding one-liners (the current version, "Port, don't reinvent") are in `CLAUDE.md`; the domain
sketch is `chat-model.md`; the live channel is `live.md`; how an area works now is in its own doc.

Read on demand when you need the product intent, the scope of a version, a decision the user already took or what a donor contributed. A decision
recorded here is the user's unless it is filed under "Assumptions" (taken without asking, change on request).

## What Adder is

Slack crossed with Jira, for **short-lived, intensive, asynchronous** collaboration of a few dozen people — the reference case is 30+ people
working a complex, multi-threaded incident who must avoid chaos.

1. It looks like a chat tool: people work in **rooms**.
2. In a room all communication is organised in **threads**: you either create a thread or reply in one.
3. **Each thread has a workflow**: a status (e.g. in progress, closed) and further metadata; statuses and metadata fields are configurable per
   room, with defaults.
4. Room content can be **filtered, sorted and searched** (limited to a thread, by keyword, …).
5. The standard chat functions apply: pinning, @mentions, reactions, emoji, searchable #hashtags.
6. A thread has an **owner** (like a Jira assignee): initially its creator; ownership can be passed.
7. A thread can be **closed**, which stops any activity within it.

The name is the snake: quick, quiet, decisive. **Brand:** olive / venom chartreuse (`adder` tuple, light primary `#586715`, dark `#465213`,
dark-scheme accent ink `#b2cc3c`) — the centre of the largest free gap in the sibling hue wheel (Toadie amber 26°, Lettuce green 142°, Astrolabe
cyan 189°, Flow blue 209°, Covenant violet 264°, Astrid red 350°). The logo "Strike": a rattlesnake seen from above on an olive disc. Success is
**teal** (never green, so it cannot read as the accent); red = blocking, orange = warning, gray = neutral, blue = in progress, grape = waiting.

## Decisions (the user's, 2026-11-17)

- **Scope of the first plan:** the foundation plus the chat MVP, 0.1 to 0.6; each increment gets its own detailed plan before it is built.
- **Room access:** members only. A room has members; only members read and post; the creator or an ADMIN adds people.
- **Closing:** a closed thread can be reopened by its owner, the room owner or an ADMIN; the reopen is recorded in the thread history.
- **Live updates:** WebSockets (not SSE, not polling).
- **Foundation:** Flow, Toadie and Lettuce as the base for stack, conventions, UI approach, app and API design — re-use as much as possible; the UI
  stays coherent with Lettuce, Toadie, Covenant, Flow, Astrolabe and Astrid; a new accent colour; an icon referring to the name.

## Assumptions (taken without asking; change on request)

- ADMIN may read every room (the family's ADMIN-overrides posture); a non-member gets `404`, never `403`.
- Closing is a status category: moving a thread into a CLOSED-category status closes it; moving it back to an OPEN status reopens it.
- Any room member may move a thread between OPEN statuses; ownership is passed by the owner, the room owner or an ADMIN.
- Each room owns its own copy of its workflow, copied at creation from an ADMIN-curated template (seeded *Default* and *Incident*).
- Mentions are stored as `<@userId>` tokens and rendered with the current name (rename-safe).
- Flow's integration service tokens are not ported in 0.1.0: with nothing to serve they would be dead weight (backlog: bots posting into rooms).
- The coverage floors were re-baselined once after the trim (lines 97, branches 81; Flow's 98/85 relied on tests of the cut features).

## Roadmap

Each increment gets its own plan (architect) before implementation. **Current version: 0.1.0.**

| Ver | Scope |
|---|---|
| **0.1.0 Foundation** | Flow trimmed: auth and email MFA, password reset, users and feature flags, teams with a manager, EN/PL, light/dark, changelog, telemetry panel, olive brand and the Strike logo |
| **0.2.0 Rooms and workflows** | The ADMIN workflow-template registry (seeded Default and Incident); rooms with members (person or whole team), OWNER/MEMBER roles, archive; the room workflow editor (statuses with colour/category/order, typed fields) under `X-Expected-Revision`; the rooms rail |
| **0.3.0 Threads and messages** | Threads with a per-room number, status, owner, fields and history (`EventLog` port); messages in Markdown with emoji; edit/delete own; close/reopen with the closed-thread `409`; the three-pane room view |
| **0.4.0 Live updates** | `GET /api/v1/live` WebSocket with single-use tickets, subscribe/unsubscribe per room, `pg_notify` inside the write transaction, one LISTEN connection per process, bounded sockets; targeted query invalidation in the SPA |
| **0.5.0 Find** | Thread filters and sorts (status, category, owner, fields, tag, mentions me), Postgres full-text search per room and across member rooms, server-parsed #hashtags |
| **0.6.0 Mentions, reactions, pins, unread** | @mention autocomplete and notifications (Lettuce's model, pushed live; email by preference), reactions, room pins, per-member unread markers |
| **1.0.0 Checkup** | The family's technical checkup (docs, DRY/SRP, tests, UI consistency, security, guardrails, infra) |

## Donors

Adder's repository started as an exact copy (`git archive`) of Flow @ `4e43e480` (`~/Sources/flow`, Flow 1.57.1), trimmed in the shape Astrolabe's
bootstrap (2026-10-31) set. Flow itself was copied from Covenant and trimmed; Lettuce and Toadie came in indirectly through it. **Port, don't
reinvent**: when Adder needs a capability a sibling repo already has, port its implementation.

- **Flow** (`~/Sources/flow`, the primary donor): the whole foundation — stack, API design, UI/UX, testing, gates, versioning, deployment, the
  telemetry panel.
- **Astrolabe** (`~/Sources/astrolabe`): the trim recipe — the single-process deployment, `users/` (not `flowusers/`), the V1–V9 foundation
  migrations, the trimmed telemetry and teams, the bootstrap docs.
- **Lettuce** (`~/Sources/lettuce`): `infra/mail`, `infra/crypto`, `infra/paging`, the connection pool (through Flow); next: `MarkdownView`,
  `EmojiPicker`/`EmojiButton`/`EmojiTextarea`, `NotificationsButton` + `notifications/`, `infra/db/EventLog.kt`, the dictionary whole-document PUT.
- **Toadie** (`~/Sources/toadie`): next: `X-Expected-Revision` optimistic concurrency, `blueprints/` `PropertyDefinition` as the reference for typed
  workflow fields, `lockingTransaction`.
- **Covenant** (`~/Sources/covenant`): next: the `contracts/Lifecycle.kt` 409-on-illegal-move shape.

Left in Flow on purpose: `ingest/`, `fetch/`, `jira/`, `gitlab/`, `norm/`, `identities/`, `metrics/`, `reports/`, `integration/` (service tokens),
`infra/outbound/` (the SSRF guard — port it with the first server-initiated call other than SMTP), `plugins/Role.kt`, `EncryptedAtRest`/`Reencrypt`.
Greenfield (no sibling has it): WebSocket push, @mention parsing, reactions, Postgres full-text search.

## Release history

### v0.1.0 — the foundation (2026-11-17)

Flow trimmed to its generic core in nine commits (scaffold, core, server, tests, web, deploy, e2e/CI, docs, gates). Migrations V1–V9. No chat
feature yet.
