# The chat domain

Owns: the chat domain model — rooms, membership, workflows (statuses and fields), threads, messages, history, mentions, reactions, pins, unread.
Not here: the live channel (`live.md`), the roadmap and decisions (`product.md`). **Status:** rooms and workflows (0.2) and threads and messages (0.3) are binding rules;
0.5 onward is still a sketch, and each version turns its part into rules in its own plan first. `workflows/` and `rooms/` (0.2.0) are the first chat packages; the room routes landed commit by commit (the record and its read-only workflow in C7, members in C8, the workflow PUT in C9).

## Rules

Binding for 0.2.0 (rooms and workflows) and, from "Threads and messages (0.3)", 0.3.0. The code lands commit by commit; where a rule names a file, that file is the destination. The user's
decisions and the defaults taken without asking are in `product.md` ("Decisions", "Assumptions"); the per-route matrix and the audit events move to
`authorization.md` and `observability.md` with the route that introduces them.

### Rooms

- **Members only.** `room_members(room_id, user_id, role OWNER|MEMBER)`. Any signed-in user creates a room and becomes its OWNER. A non-member
  gets `404` (existence is not disclosed; the denial is audited `room.access_denied`, the response stays 404); an ADMIN reads and manages every
  room. OWNER or ADMIN edits name and description, manages members and the workflow, archives and unarchives; soft delete is ADMIN only
  (reader `404` first, then `403`), with no restore API in 0.2.
- **Guard order, every room route:** 401; visibility (404 for a missing room and a non-member alike); role (403); the `X-Expected-Revision`
  header, then the body (400); then, under the room lock, the guard again, the state checks (409) and the write. A non-member's malformed body is
  still 404, a plain member's still 403.
- **Membership.** A person or a whole team. A team add is a **snapshot** of the active roster at that moment: a later roster edit never follows
  (a live link would eject people mid-incident and break per-member roles and the last-OWNER rule). Add is idempotent (existing members are
  skipped and counted) and always adds as MEMBER; promotion is a separate audited step. The last OWNER cannot leave, be demoted or be removed
  (`409`, `urn:adder:problem:last-owner`): after the write the room keeps at least one OWNER whose user is active. A member leaves by deleting their
  own membership; an OWNER or ADMIN removes others.
- **Deleted users.** Their membership rows stay, inert: lists and the last-OWNER count resolve through active users, a room whose owners are all
  deleted is managed by ADMINs, and deleting a user is never blocked by room ownership.
- **Room names** may not contain invisible formatting characters (Unicode category Cf, U+2028/U+2029): a `400`, applied to room names only (`rooms/Room.kt`), so two rooms never look identical and a bidi override cannot reorder a name.
- **Archived = read-only** for name, description, workflow and (0.3) content (`409`, `room-archived`); membership management stays allowed, so
  access to the record stays manageable. Archive and unarchive are no-ops (and unaudited) when already in that state.
- **Names** are unique among non-deleted rooms, case-insensitively (a partial unique index; archived rooms keep theirs; a delete frees the name).
  A clash discloses only that a room of that name exists. No slug: URLs use the numeric id.
- **Activity.** `last_activity_at` equals creation time in 0.2 and 0.3 bumps it. The rail shows the caller's member rooms, most recent first; an
  ADMIN gets every room on the Rooms page.
- **Limits:** room name <= 80, description <= 500; 500 members per room, 200 user ids per add; 200 members per team.

### Workflows

- **Vocabulary.** A room owns its workflow: ordered **statuses** `{key, label, colour GRAY|BLUE|GRAPE|TEAL|RED|ORANGE, category OPEN|CLOSED,
  initial}` and **fields** `{key, label, type TEXT|NUMBER|DATE|SELECT|MULTI_SELECT|USER|URL, options[{key,label}] (SELECT kinds only), required}`.
  Wire enums are UPPER_CASE; the SPA lowercases to the Mantine hue in one util. A position is the array index on the wire, never a field.
- **Kernel** (`workflows/WorkflowRules.kt`, pure, shared by templates and rooms): 2-20 statuses, 0-20 fields, 1-50 options per select; exactly one
  initial status and it is OPEN; at least one CLOSED status; keys unique per list and matching `^[a-z][a-z0-9]*(-[a-z0-9]+)*$` (<= 40 chars);
  labels <= 60; options only on SELECT kinds. At most 200 status keys and 200 field keys per room, retired ones included.
- **Templates.** An ADMIN-curated registry `workflow_templates` (any signed-in user reads; writes are ADMIN with guard-before-read), one JSONB
  `definition` document plus a `revision`. Name (<= 100) and description are plain registry text. Labels are a language map `{en, pl?}` (EN
  required, PL optional, Lettuce's dictionary shape; per language, labels are unique among the statuses, among the fields and among a field's options,
  case-insensitively after trimming, a duplicate being a 400 that names the label; a room's own labels follow the same rule in its ONE language, `rooms/RoomWorkflow.kt`). Seeds by migration, never edited afterwards: *Default* (Open initial, In progress, Waiting,
  Done CLOSED, Won't do CLOSED) and *Incident* (Triage initial, Investigating, Mitigating, Monitoring, Resolved CLOSED; optional fields Severity
  S1-S4 SELECT, Component TEXT, Jira link URL, ETA DATE), in English and Polish. Deleting a template never touches rooms; there is no
  "last template" rule.
- **Copy at creation.** A room copies statuses and fields once, with labels resolved to ONE language from the request's `language` (the SPA sends
  its UI language; default `en`; EN fallback per label). When per-label resolution would clash within a list, that whole list is copied in EN. Room labels are plain text afterwards: a room's statuses are shared vocabulary people name in
  chat, and two-language rooms breed stale half-translations. There is no re-apply; afterwards OWNER or ADMIN edits the room's copy in place only.
- **Whole-document PUT under `X-Expected-Revision`** (templates and rooms; Toadie's guard, `infra/revision/ExpectedRevision.kt`). The header is
  REQUIRED: missing or malformed is `400`, stale is `409` (`revision-conflict`); every successful PUT bumps the revision. `infra/` carries it so
  0.3 threads reuse it. Adder has no legacy client, which is why it is not optional as in Toadie.
- **Keys are immutable slugs, reserved for the life of a room.** Removing a status or field **retires** it (soft delete: `marked_as_deleted`); adding
  a retired key back revives the row. A field's `type` is immutable per key (`409`, `field-type-immutable`, carrying `keys`). The PK
  `(room_id, key)` is deliberately NOT partial: a retired key is never minted twice, so 0.3's history and the thread FK stay unambiguous. This
  is the documented exception to "free a unique field on delete".
- **A workflow edit never closes or reopens a thread behind its history.** A status's category is editable only while no thread uses it, and a
  retired status's replacement must have the same category. Fields and options need no mapping.
- **Stored values survive a workflow edit** (decided in 0.3; `threads.fields` is a jsonb object keyed by field key). A **retired field's value is kept,
  hidden** (not returned, not validated), and **comes back when the field is revived**. A **removed option's stored key is kept and shown as removed**
  (a "removed option" hint) until someone changes it; a newly chosen value must be a live option. **Unchanged values skip validation**, so a stale value
  never blocks a save; a value the caller changes is validated against the current definition. The kernel is `threads/ThreadFields.kt`.

### Data model (V10-V13)

All timestamps are epoch-millis `BIGINT`; no CHECK on enum-valued columns (the Kotlin enum is the whitelist, the V5 rule).

- `V10 team_members(team_id, user_id)`: the team roster, a pure join. `V11 workflow_templates`, `V12` the two seeds, `V13` the whole room aggregate
  in one migration so a room never exists without its workflow: `rooms`, `room_members`, `room_statuses`, `room_fields`.
- **Hard-delete** the pure joins `team_members` and `room_members` (the audit trail holds adds and removals; a re-add must not collide with a dead
  row). **Soft-delete with a partial unique name** `rooms` and `workflow_templates`. **Soft-delete with a full key** `room_statuses` and
  `room_fields`. A template's statuses and fields are not rows; they are replaced with the document.
- `rooms.workflow_revision` is the room workflow's revision; `room_statuses` has a partial unique index on the one initial row per room.

### Locking and the room write

- **One lock, one helper.** `withRoomWrite(roomId, callerUserId, guard) { ... }` (`rooms/RoomLock.kt`, after Astrolabe's `withPlanWrite`), in one
  transaction: select the active room row `FOR NO KEY UPDATE` (child inserts take `FOR KEY SHARE`, which does not conflict), re-read the caller's
  membership and re-run the same guard (only this verdict authorises the write), then run the block. Every room-scoped write goes through it:
  metadata, archive, members, roles, workflow. That makes the last-OWNER count, the revision compare and the archived check race-free without
  locking member rows. Toadie's table-level `lockingTransaction` is deliberately NOT ported: the invariants are per room, and a table lock would
  serialise every room.
- **Workflow PUT inside the block:** archived -> 409; `requireRevision`; select the room's status rows `FOR NO KEY UPDATE`; plan the change with
  the pure kernel (`RoomWorkflowPlan.kt`: retired, revived, added, re-categorised); field-type and key-budget checks; the replacement checks (400), then the
  usage counts (409 `status-in-use`); clear `is_initial` on every row (first: the partial unique index is checked per row); retire absent keys; upsert payload
  rows by key with `position = index` (reviving retired keys); THEN `usage.moveThreads` (after the upsert: a replacement may be a status this
  very PUT adds or revives, and 0.3's thread rows reference `room_statuses`, so the target row must exist and be live when the threads move);
  bump `workflow_revision`. Per-row Exposed statements (<= 20 rows each).
- **409 problem types** (`urn:adder:problem:`): `revision-conflict`, `last-owner`, `room-archived`, `status-in-use` and `field-type-immutable`
  (both carrying `keys`, the `ProblemDetail` extension member), plus the plain key-budget conflict.

### The mapping contract 0.3 plugs into

> **Revised for 0.3** by two plan findings; both are already applied in 0.2.0 C9 (the text below is as built): `moveThreads` runs **after** the status upsert
> (a replacement the same PUT adds would otherwise hit the FK, a SQLSTATE 23503 that `ErrorHandling.kt` would answer as a 500), and the status-row `FOR SHARE` is
> **dropped** (every thread write and the workflow PUT hold the same room lock, so the status read inside the lock is current; the FK stays as a backstop).
> **Still to build in S6** (`threads/ThreadWorkflowUsage.kt`): the real port; `Database.kt` registers it and `NoThreadsYet` and the `?: NoThreadsYet`
> fallback are deleted, routes reading `attributes[WorkflowUsageKey]` per request (a test still overrides it, as `FakeWorkflowUsage` does);
> `threadCounts` is one `GROUP BY status_key`; `moveThreads` selects the ids, runs one `UPDATE`, and writes one `STATUS_REPLACED` event per thread (labels from
> `RoomWorkflowService.snapshot`), with no activity bump and `closed_at` untouched (same category). S6 rewrites this section as built.

Retiring a status that threads use needs a replacement mapping `replacements: {retiredKey: replacementKey}` in the PUT body. `400` when a key is
not a status this PUT retires, or the target is absent from the new document or has another category; `409 status-in-use` with `keys` when a retired
or re-categorised status has threads and no valid replacement. 0.2 has no threads, so the seam is a port:

```kotlin
interface WorkflowUsage {   // rooms/WorkflowUsage.kt; both run inside the caller's transaction
    suspend fun threadCounts(roomId: UInt, keys: Collection<String>): Map<String, Long>
    suspend fun moveThreads(roomId: UInt, from: String, to: String, byUserId: UInt): Long
}
object NoThreadsYet : WorkflowUsage            // 0.2: no counts, moves nothing
val WorkflowUsageKey = AttributeKey<WorkflowUsage>("WorkflowUsage")
```

Routes resolve it per request with `attributes.getOrNull(WorkflowUsageKey) ?: NoThreadsYet` (the telemetry seam idiom), so a test can put a fake;
server tests cover the retire path through a fake and the web through a mocked `threadCount`. **As built (0.2.0 C9)**, the contract 0.3 implements is exactly this:
`GET` calls `threadCounts` with every live status key; the `PUT` calls it with the keys it retires or re-categorises (`RoomWorkflowPlan.statusesToCount`), after the
`FOR NO KEY UPDATE` lock on the status rows and the mapping's `400` checks, and answers `409 status-in-use` (`keys` = the offending statuses) for a counted status that is re-categorised
(no mapping can help: only retired statuses take one) or retired without an entry in `replacements`; `moveThreads(roomId, from, to, byUserId)` is called once per retired status that has
threads AND a mapping (in the order retired; never for a status with no threads), inside the same transaction, AFTER the status upsert (so the target may be a status this PUT adds or revives; pinned by `FakeWorkflowUsage`'s `targetLiveAtMove`) and
before the revision bump, so any later failure rolls the moves back;
the sum is the audited `threadsMoved`. A revived status may change category freely (a retired status has no threads: they were moved when it retired). The only absent-threads
behaviour 0.2 pins is `NoThreadsYet` (no counts, no moves). 0.3 does four things and changes nothing else:
thread rows reference `(room_id, status_key)` with an FK to `room_statuses(room_id, key)`; every thread write runs under `withRoomWrite`, the same room lock as
the workflow PUT, so the status read inside the lock is current (a thread can then never land on a status a PUT is retiring), and the FK stays as a backstop; it implements `WorkflowUsage` (the move also
writes one `thread_events` row per thread; when `moveThreads` runs the SOURCE status is already retired, so it must not require the source live) and registers it under the key; it adds the e2e for the retire dialog.

## Threads and messages (0.3)

Binding for 0.3.0; the code lands commit by commit (plan steps S1-S6), and where a rule names a file, that file is the destination. D1 and D2 below are the
architect's recommended options taken as assumptions (`product.md` "0.3.0 threads and messages"); changing one changes one predicate and a few matrix cells.

### Data model (V14)

- **One migration, `V14__create_threads.sql`**, the whole aggregate: `threads`, `messages`, `thread_events`. Its header comment states each table's delete class:
  `threads` are **never deleted** in 0.3 (close one as *Won't do*; a room's soft delete hides them with the room); `messages` are **soft-deleted** with a
  listed tombstone; `thread_events` is **append-only** (Toadie's `V23__create_catalog_file_events.sql` shape). No CHECK on enum-valued columns (the V5 rule).
- `threads`: `id`, `room_id` (FK `rooms` ON DELETE CASCADE), per-room `number` (`#42`, unique `(room_id, number)`), `title` <= 200, `status_key`, `owner_id`
  (the creator at first), `created_by`, `fields` jsonb object, `revision`, `message_count`, `closed_at`, `last_activity_at`, `created_at`, `updated_at`.
  The composite FK `(room_id, status_key)` -> `room_statuses(room_id, key)` keeps the default `NO ACTION` (checked at statement end), so a room hard delete
  that cascades into both tables at once passes; `RESTRICT` would fail mid-cascade.
- **`closed_at` mirrors the status category**: set on a move into a CLOSED status, cleared on a move into an OPEN one. It stays exact because a category
  cannot change on a status in use and a replacement has the same category (Workflows, above).
- `messages`: `thread_id` (CASCADE), `author_id`, `body` <= 8,000, `created_at`, `edited_at`, `marked_as_deleted`, `deleted_by` (set only on a delete).
- `thread_events` (Toadie's/Lettuce's event log): `thread_id`, `user_id` (RESTRICT), `created_at`, `event_type`, `params` (a string map via `infra/json/JsonParams.kt`).
- **Numbers** are `COALESCE(MAX(number), 0) + 1` per room under the room lock and are never reused; a clash on `uq_threads_room_number` (23505) maps to 409.
- **`message_count`** counts every message, the opener and tombstones included, and never goes down (the sketch's "reply count"; the UI says "N messages").

### Who may do what

Four groups (D1 and D2 are the architect's recommendations, taken as assumptions):

- **Speech** (start a thread, post a message): **members only**. An ADMIN who is not a member gets `403` and joins through the Members page first
  (only members read and post, for the sake of the audit trail).
- **OPEN-side metadata** (a move between OPEN statuses, title, fields): any reader, member or ADMIN.
- **Stewardship** (close, reopen, CLOSED->CLOSED moves, passing ownership; D1): the thread owner while a member, the room OWNER, or an ADMIN. Closing stops
  everyone, so it belongs to whoever can undo it; the rule is `ThreadStatusRules.kt` plus `requireThreadSteward`.
- **Messages:** an author edits and deletes their own while a member. The room OWNER or an ADMIN may **delete, never edit**, someone else's message (D2):
  it becomes a tombstone ("Removed by a room owner or an administrator", `deletedByModerator`) and the audit line says `moderated`. Incident chat leaks
  credentials and the author may be asleep.

### Closed and archived

- **Closed** = the status's category is CLOSED. A closed thread refuses posts, message edits and deletes (moderator ones included), the details PUT and the
  owner hand-over: `409`, `urn:adder:problem:thread-closed`. Stewards still move it between statuses (reopen, or CLOSED->CLOSED); to delete a message in a
  closed thread a steward reopens it, deletes and closes it again, and the history shows all of it. (Reactions, 0.6, will follow the same refusal.)
- **Reopen** is recorded in the history (`REOPENED`), by the thread owner, the room OWNER or an ADMIN.
- **An archived room refuses every content write** (`409`, `room-archived`); reads still work.
- **Owner hand-over:** the new owner must be an active room member (else `400`); a removed or deleted owner keeps the thread, inert, until a steward passes
  it on. Setting the current status or owner again is a `204` with no event and no audit line.

### Guard order and locking

- **Guard order, every thread and message route:** 401; visibility via `visibleStanding` (404 for a missing room and a non-member alike, audited
  `room.access_denied`); the **pre-check before the body** (403): member for the two POSTs, steward for the owner PUT, author for the message PUT (the last two
  from a lock-free read of the owner or author; a missing thread or message is `404` here); the header, then the body (400); then, under the room lock: the
  room guard again on the fresh caller, the thread or message (404), the act guard again (403), archived (409), closed (409), revision (409), the
  state-dependent 400s, then the write, its events and the activity bump.
- **The status PUT is the one body-dependent guard**: whether a steward is needed depends on the target's category, so that check runs under the lock after
  the target resolves. An unknown or retired key is a `400` first (every reader can read the workflow, so it discloses nothing).
- **The room lock is the thread lock.** Every content write goes through `withRoomWrite` (`rooms/RoomLock.kt`): posting updates `rooms.last_activity_at`,
  i.e. the room row, so a lighter lock upgraded by that UPDATE would deadlock two posters; edits and deletes take the same lock, so the closed and archived
  checks cannot race a close or an archive. Writes serialise per room (a few ms each, fine at incident scale). **No thread-row or status-row locks.** The
  block receives the fresh caller and standing (`ThreadLock.kt`: `withThreadWrite`, `withMessageWrite`). The lock order is the room row first; the FK
  checks' `FOR KEY SHARE` on `rooms`, `room_statuses`, `threads` and `users` rows does not conflict with `FOR NO KEY UPDATE`.
- **Single messages live at `/rooms/{id}/messages/{messageId}`** (API-RES-006 bans three path parameters); the route checks the message belongs to that
  room, and a message in another room, unknown or deleted is a uniform `404`.

### API (rule form; the spec is `documentation.yaml`, the matrix `authorization.md`, the events `observability.md`)

- `GET /rooms/{id}/threads?closed=`: reader; paged; sortable `id`, `number`, `title`, `lastActivityAt`, `createdAt`; `closed` is the only filter (a strict
  boolean on `closed_at`; omitted = both; 0.5 adds the rest). `POST` (member, `chat-post` bucket) takes `{title, body, fields?}` and answers `201` +
  `Location`; it starts in the initial status with the creator as owner, required fields must be supplied, and the opening message is part of the create.
- `GET /rooms/{id}/threads/{number}`: reader. `PUT` takes `{title, fields}` under `X-Expected-Revision` (REQUIRED; `204`, a no-op when nothing changed and
  then no revision bump). `PUT .../status` `{status}` and `PUT .../owner` `{userId}` are single-value sets: last writer wins by design, the history records
  each change.
- `GET .../events`: reader; paged; sortable `id`, `createdAt`, default `-createdAt,-id`.
- `GET .../messages?id[lt]=`: reader; keyset paging through the `id[lt]` filter inside the standard envelope (`total` counts after the filter, so
  `total > items.length` means older messages exist); sortable `id`; tombstones included (`body: null`, `deleted: true`). `POST` (member, `chat-post`
  bucket) answers `201` + `Location: /api/v1/rooms/{id}/messages/{messageId}`.
- `GET|PUT|DELETE /rooms/{id}/messages/{messageId}`: GET is reader (a deleted message is `404`); PUT is author-only and last-writer-wins (`204`, a no-op on an
  unchanged body); DELETE is the author, the room OWNER or an ADMIN. Every write on a deleted message is `404`.
- **Rate limit:** one per-user bucket, `chat-post` 120/min, on the two POSTs; edits, deletes and metadata writes stay unbucketed (guard-gated, one row each).
- Limits: title single line <= 200 (`sanitizeSingleLine`); a message body is Markdown, 1 to 8,000 UTF-16 units after CRLF -> LF and trimming, `\n` and `\t` allowed,
  other control characters a `400` (`sanitizeMultiLine`); stored raw, rendered without HTML.

### Field values

Checked by the pure kernel `threads/ThreadFields.kt` against the room's field definitions (active and retired) and the active member ids; `null` clears a field.

| Type | Valid value |
|---|---|
| TEXT | single line <= 500; blank means unset |
| NUMBER | finite, \|x\| <= 1e15 |
| DATE | strict `YYYY-MM-DD`, a real calendar day |
| SELECT | one option key |
| MULTI_SELECT | 1 to 50 distinct option keys; empty means unset |
| USER | the id of an active room member |
| URL | absolute `http`/`https`, <= 2,000 |

An unknown or retired key is a `400`; a required field is supplied at create and cannot be cleared once set (one never set may stay unset after create);
unchanged values skip validation (see "Stored values" under Workflows).

### Activity

`threads.last_activity_at` and `rooms.last_activity_at` (the rail order) are bumped by a new thread, a new message, and a status, owner, title or field change.
Edits, deletes and the workflow PUT's `moveThreads` do not bump.

### History (`thread_events`)

- Types, persisted and frozen: `CREATED`, `TITLE_CHANGED`, `STATUS_CHANGED`, `CLOSED`, `REOPENED`, `OWNER_CHANGED`, `FIELD_CHANGED` (one per field),
  `STATUS_REPLACED` (written by `moveThreads`). Events are written in the transaction of the change, by `threads/ThreadEventService.kt` over the ported
  `infra/db/EventLog.kt`, which takes the actor table and its name and deleted columns as parameters (`infra` may not name `users/`; `threads/` supplies
  `UserService.Users`).
- **Params** are string maps of keys and ids plus snapshots of labels and names, never rendered text: `CREATED {title, status, statusLabel}`;
  `TITLE_CHANGED {from, to}`; `STATUS_CHANGED`/`CLOSED`/`REOPENED`/`STATUS_REPLACED {from, to, fromLabel, toLabel}`; `OWNER_CHANGED {fromId, toId, fromName,
  toName}`; `FIELD_CHANGED {field, fieldLabel, from?, to?}` (display text: option labels joined with ", ", the member name for USER, raw otherwise; absent =
  unset). Titles and TEXT values are recorded (nothing is encrypted at rest). The sentence is rendered in the viewer's language.
- **Message edits and deletes go to the audit trail only**, not to the history.
- Placement: the side panel, newest first, 10 per page (interleaving it into the conversation is a BACKLOG item).

### Audit

Every mutation calls `audit(...)` with ids, keys and counts, **never a title, body or field value**: `thread.created` (names the opening message; no separate
`message.posted` for it), `thread.updated` (on a change only, `titleChanged`, `fieldsChanged` = keys), `thread.status_changed` (transition `MOVED`/`CLOSED`/`REOPENED`),
`thread.owner_changed`, `message.posted`, `message.edited`, `message.deleted` (with `authorId` and `moderated`). The 429 on `chat-post` is audited like the other buckets. The list is
owned by `observability.md`.

## Find (0.5)

Thread filters and sorts per `list-endpoints.md`; full-text search over messages (trigger-maintained `tsvector`, an immutable `unaccent` wrapper,
the `simple` configuration for EN+PL, GIN index), limited to the caller's rooms; #hashtags (`#[\p{L}\p{N}_-]{1,50}`) parsed server-side into
`message_tags`.

## Mentions, reactions, pins, unread (0.6)

`<@userId>` tokens parsed into `message_mentions` and notified (Lettuce's `notifications/`); reactions keyed `(message, user, emoji)`, one emoji
grapheme, ≤ 20 distinct per message; room-level pins of threads or messages; a per-member last-read marker per thread.
