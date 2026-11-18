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

### 0.2.0 rooms and workflows (the user's, 2026-11-17)

- **Any signed-in user creates a room** and becomes its OWNER. ADMINs manage and read every room; non-members get `404`.
- **Workflow templates are ADMIN-only** (edited by ADMIN, read by anyone). A room copies statuses and fields at creation; afterwards the OWNER or an
  ADMIN edits the room's copy **in place only**, with no re-apply from the template.
- **Tens of rooms:** a flat rail sorted by recent activity, archived rooms behind a toggle, no favourites or grouping.
- Retiring a status that threads use needs a replacement mapping; the contract is designed in 0.2 and 0.3 plugs in. Members are a person, or a whole
  team as a snapshot; the last OWNER cannot leave or be demoted (`409`). Archive and unarchive by OWNER or ADMIN; soft delete by ADMIN only. The
  workflow is a whole-document PUT under `X-Expected-Revision`; keys are immutable slugs; *Default* and *Incident* are seeded by migration.
- **D1. Team rosters** are edited by an ADMIN or the team's active manager (the first consumer of `requireTeamManagerOrAdmin`). The team record
  itself stays ADMIN-only.
- **D2. The user directory:** any signed-in user may look up `{id, name, email}` of active users, behind a per-user rate bucket. This changes a
  deliberate posture (the user list stays ADMIN-only); roles, feature flags and language are never exposed.
- **D3. The seed labels** are the ones in `chat-model.md` "Workflows" (Default and Incident, EN and PL), exactly as proposed. An applied migration's
  bytes are immutable, so afterwards the labels change only through the template editor.

## Assumptions (taken without asking; change on request)

- ADMIN may read every room (the family's ADMIN-overrides posture); a non-member gets `404`, never `403`.
- Closing is a status category: moving a thread into a CLOSED-category status closes it; moving it back to an OPEN status reopens it.
- Any room member may move a thread between OPEN statuses; ownership is passed by the owner, the room owner or an ADMIN.
- Each room owns its own copy of its workflow, copied at creation from an ADMIN-curated template (seeded *Default* and *Incident*).
- Mentions are stored as `<@userId>` tokens and rendered with the current name (rename-safe).
- Flow's integration service tokens are not ported in 0.1.0: with nothing to serve they would be dead weight (backlog: bots posting into rooms).
- The coverage floors were re-baselined once after the trim (lines 97, branches 81; Flow's 98/85 relied on tests of the cut features).

### 0.2.0 rooms and workflows

Detail and the rules they became: `chat-model.md` "Rules".

- **Label language.** Template labels are a language map `{en, pl?}` (EN required); a room's copy is single-language, resolved at creation from the
  request's `language` (default `en`, EN fallback per label) and plain text afterwards. Template name and description are not localised.
- **A team add is a snapshot**, not a live link. Member add is idempotent and always adds as MEMBER; promotion is a separate audited step.
- **Keys are reserved for the life of a room:** removing a status or field retires it, re-adding revives it, a field's `type` is immutable per key.
- **A workflow edit never closes or reopens a thread behind its history:** a replacement status has the same category as the retired one, and a
  category is editable only while no thread uses the status. Both can be relaxed later. Fields and options need no mapping; what a removed
  option means for stored values was decided in 0.3 (`chat-model.md` "Workflows": stored values survive).
- **Room names are unique** among non-deleted rooms, case-insensitively (archived rooms keep theirs); a clash discloses only that the name exists
  (Slack's private-channel rule). No slug; URLs use the numeric id.
- **Archived means read-only** for name, description, workflow and (0.3) content; membership management stays allowed.
- Soft delete has no restore API in 0.2. A soft-deleted user's membership rows stay, inert; a room whose owners are all deleted is managed by ADMINs,
  and deleting a user is not blocked by room ownership.
- `last_activity_at` equals creation time in 0.2 (0.3 bumps it). The rail shows the caller's member rooms; an ADMIN gets every room on the Rooms page.
- **Limits:** 2-20 statuses, 0-20 fields, 1-50 options per select; key <= 40 characters, label <= 60; at most 200 status keys and 200 field keys per room
  (retired included); room name <= 80, descriptions <= 500, template name <= 100; 500 members per room, 200 user ids per add, 200 members per team.
- `X-Expected-Revision` is required (missing or malformed `400`, stale `409`); every successful PUT bumps the revision. Wire enums are UPPER_CASE.
- Deleting a template never touches rooms; there is no "last template" rule.
- Rate buckets (all per signed-in user): `room-name` 30/min shared by `POST /rooms` and `PUT /rooms/{id}`, `room-members` 60/min on `POST /rooms/{id}/members`, and `directory` 120/min on the user directory; everything else is
  guard-gated and bounded by the caps above. `lockingTransaction` is not ported (one row lock per room instead).

### 0.3.0 threads and messages

Taken without asking: the user was away (2026-11-18) and said to take the architect's recommended defaults. **D1 and D2 are the architect's recommended
options, not the user's decisions**, and can be changed on request. Detail and the rules they became: `chat-model.md` "Threads and messages (0.3)".

- **D1. Who closes a thread:** the thread owner, the room OWNER or an ADMIN (the people who can reopen). Closing stops everyone, so it belongs to whoever
  can undo it; a member who thinks a thread is done says so or moves it to an OPEN status such as *Monitoring*. The alternative was any member closing
  (faster, but one member could freeze a thread its owner is still working on); it changes one predicate in the status rules and two matrix cells.
- **D2. Moderating messages:** the room OWNER or an ADMIN may delete, never edit, someone else's message (incident chat leaks credentials and the author may be
  asleep). It becomes a tombstone ("Removed by a room owner or an administrator"), `messages.deleted_by` records who, and the audit line is
  `message.deleted` with `moderated`. The alternative was authors only (a later migration would add the column).
- **Who does what.** Speech (start a thread, post): members only, so a non-member ADMIN gets 403 and joins first. OPEN-side metadata (OPEN->OPEN moves, title,
  fields): any reader. Stewardship (close, reopen, CLOSED->CLOSED, passing ownership): thread owner while a member, room OWNER, ADMIN. Own messages: edited and
  deleted by their author while a member.
- **Closed and archived.** A closed thread refuses posts, edits and deletes (moderator ones included), the details PUT and the owner hand-over (409
  `thread-closed`); stewards can still move it. An archived room refuses every content write (409 `room-archived`); reads work.
- **Ownership.** The new owner must be an active member (400); a removed or deleted owner keeps the thread, inert, until a steward passes it on; setting the
  current status or owner again is a no-op (204, no event, no audit).
- **Starting a thread** takes a title (single line, <= 200) and an opening message, in the initial status with the creator as owner; required fields must be supplied.
- **Numbers and counts.** `#N` is `MAX+1` per room and never reused; threads are never deleted in 0.3 (close as *Won't do*). `message_count` includes the
  opener and tombstones and never goes down.
- **Messages.** Markdown, 1 to 8,000 UTF-16 units; stored raw, rendered without HTML (headings as bold paragraphs, images as links, links in a new tab with
  `rel="noopener noreferrer"`; the CSP is unchanged). An edit overwrites (no versions) and sets `edited_at`; a delete scrubs the body and lists a tombstone.
- **Field values** by type (TEXT <= 500, NUMBER, DATE, SELECT, MULTI_SELECT <= 50, USER an active member, URL http/https); `null` clears; a required field cannot be
  cleared once set. Stored values survive a workflow edit.
- **Activity.** New threads and messages, and status, owner, title and field changes, bump the rail order; edits, deletes and workflow moves do not.
- **History** is the side panel, newest first, 10 per page; message edits and deletes are audited, not in the history; audit lines never carry a title,
  body or field value.
- **UI.** Enter inserts a newline, Ctrl/Cmd+Enter or Send posts; no polling or live refresh until 0.4 (refetch on focus, navigation and the caller's own
  writes); the thread list shows open threads, most recently active first, 50 per page, and the message list loads 50 at a time with "Load older messages";
  times render in the browser's zone as `YYYY-MM-DD HH:mm`.
- **Rate bucket:** `chat-post`, 120/min per user, on the two POSTs.

## Roadmap

Each increment gets its own plan (architect) before implementation. **Current version: 0.3.0.**

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
- **Lettuce** (`~/Sources/lettuce`): `infra/mail`, `infra/crypto`, `infra/paging`, the connection pool (through Flow); ported in 0.3.0: `MarkdownView` (the message variant),
  `EmojiPicker`/`EmojiButton`/`EmojiTextarea`, `infra/db/EventLog.kt`; next: `NotificationsButton` + `notifications/`, the dictionary whole-document PUT.
- **Toadie** (`~/Sources/toadie`): `X-Expected-Revision` optimistic concurrency (ported in 0.2.0, `infra/revision/` and its `web/` half; the workflow-template and room-workflow PUTs consume it);
  `lockingTransaction` deliberately NOT ported (a room write takes one `FOR NO KEY UPDATE` row lock on the room instead, `rooms/RoomLock.kt`); next:
  `blueprints/` `PropertyDefinition` as the reference for typed workflow fields.
- **Covenant** (`~/Sources/covenant`): next: the `contracts/Lifecycle.kt` 409-on-illegal-move shape.

Left in Flow on purpose: `ingest/`, `fetch/`, `jira/`, `gitlab/`, `norm/`, `identities/`, `metrics/`, `reports/`, `integration/` (service tokens),
`infra/outbound/` (the SSRF guard — port it with the first server-initiated call other than SMTP), `plugins/Role.kt`, `EncryptedAtRest`/`Reencrypt`.
Greenfield (no sibling has it): WebSocket push, @mention parsing, reactions, Postgres full-text search.

## Release history

### v0.3.0 — threads and messages (2026-11-18)

Threads and messages on `feat/0.3.0-threads`. Migration V14 (threads, messages and the per-thread history in one migration). Shipped: a room's threads with a
per-room number, a title, an opening message, a status, an owner and the workflow's metadata fields (typed values, stored values survive a workflow edit); messages in
Markdown with emoji (author edit; author, room OWNER or ADMIN delete, leaving a tombstone); status moves with the steward rule for close, reopen and hand-over (the
thread owner while a member, the room OWNER, or an ADMIN; any reader moves a thread between OPEN statuses) and the closed-thread and archived-room `409`s; the per-thread
history over the ported `EventLog`; the `WorkflowUsage` port, so retiring a status that threads use moves them to a replacement (the retire-dialog e2e scenario); the
three-pane room view and the thread side panel. New per-user rate buckets `chat-post` (120/min, the two POSTs) and `chat-write` (240/min, the five other thread and message writes). Security hardening from the pre-release audit: the room-lock wait is bounded (`rooms.lockWaitMillis`, 3 s, then the audited `429 room-busy`), route-sized body ceilings (64 KiB messages, 192 KiB threads, 16 KiB status/owner) matched on the decoded path, the `fields` shape and document-size checks before any lock, the thread list never reads `fields`, URL values refuse hidden characters and userinfo, and an application-log hygiene test. History redaction (audit M4) is a `BACKLOG.md` item. Decisions D1 (who closes) and D2 (moderating messages) are
the architect's recommended defaults, taken while the user was away (see "0.3.0 threads and messages" above). Donors: `MarkdownView`, the emoji trio and `EventLog`
ported from Lettuce. No live refresh until 0.4.0. Open follow-ups: `BACKLOG.md`.

### v0.2.0 — rooms and workflows (2026-11-18)

Rooms and workflows in fourteen commits on `feat/0.2.0-rooms-workflows`. Migrations V10-V13: team members (V10), the workflow-template registry (V11) and its
Default/Incident seeds (V12), rooms with members and the copied workflow (V13). Shipped: the ADMIN workflow-template registry; members-only rooms created from a
template (a person or a team snapshot as members, OWNER/MEMBER roles, the last-OWNER rule, archive/unarchive, ADMIN soft delete); the room workflow editor
(statuses with colour, category and order, typed fields, retire by key) under `X-Expected-Revision`; the user directory lookup; team rosters; the sidebar
rooms rail. Security hardening from the audit of the increment: a global 512 KiB request-body cap (413) and new per-user rate buckets (`room-name`, `room-members`,
`directory`). Donors: `X-Expected-Revision` ported from Toadie; `lockingTransaction` deliberately not ported. Open follow-ups: `BACKLOG.md`.

### v0.1.0 — the foundation (2026-11-17)

Flow trimmed to its generic core in nine commits (scaffold, core, server, tests, web, deploy, e2e/CI, docs, gates). Migrations V1–V9. No chat
feature yet.
