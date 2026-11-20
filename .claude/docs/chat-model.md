# The chat domain

Owns: the chat domain model — rooms, membership, workflows (statuses and fields), threads, messages, history, mentions, reactions, pins, unread.
Not here: the live channel (`live.md`), the roadmap and decisions (`product.md`). **Status:** rooms and workflows (0.2) and threads and messages (0.3) are binding rules;
Find (0.5) is binding too (its code lands step by step); 0.6 onward is still a sketch, and each version turns its part into rules in its own plan first. `workflows/` and `rooms/` (0.2.0) are the first chat packages; the room routes landed commit by commit (the record and its read-only workflow in C7, members in C8, the workflow PUT in C9).

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

### Live hints (0.4.0)

Every write above that changes something emits one id-only `adder_live` NOTIFY hint from its own transaction (`notifyLive`, `infra/notify/`): a no-op, a refused or a rolled-back write emits none. The catalog is `live.md` "Hints"; the Postgres facts are `persistence.md` "NOTIFY".

### The mapping contract 0.3 plugs into

> **Revised for 0.3** by two plan findings, **as built (0.3.0 S6)**: `moveThreads` runs **after** the status upsert (a replacement the same PUT adds would otherwise hit
> the FK, a SQLSTATE 23503 that `ErrorHandling.kt` would answer as a 500), and the status-row `FOR SHARE` is **dropped** (every thread write and the workflow PUT hold
> the same room lock, so the status read inside the lock is current; the FK stays as a backstop). `threads/ThreadWorkflowUsage.kt` is the real port, registered by
> `Database.kt` under `WorkflowUsageKey`; `NoThreadsYet` and the `?: NoThreadsYet` fallback are gone, and the routes read `attributes[WorkflowUsageKey]` per request.

Retiring a status that threads use needs a replacement mapping `replacements: {retiredKey: replacementKey}` in the PUT body. `400` when a key is
not a status this PUT retires, or the target is absent from the new document or has another category; `409 status-in-use` with `keys` when a retired
or re-categorised status has threads and no valid replacement. `rooms/` never imports `threads/`, so the seam is a port:

```kotlin
interface WorkflowUsage {   // rooms/WorkflowUsage.kt; both run inside the caller's transaction
    suspend fun threadCounts(roomId: UInt, keys: Collection<String>): Map<String, Long>
    suspend fun moveThreads(roomId: UInt, from: String, to: String, byUserId: UInt): Long
}
val WorkflowUsageKey = AttributeKey<WorkflowUsage>("WorkflowUsage")   // Database.kt registers ThreadWorkflowUsage under it
```

Routes read `attributes[WorkflowUsageKey]` per request, so a test's `application { }` block can overwrite the real port with a fake (`FakeWorkflowUsage`; Ktor runs the
`application.yaml` modules, `configureDatabase` included, before that block; `test-fixtures.md`). **As built**, the contract is:
`GET` calls `threadCounts` with every live status key; the `PUT` calls it with the keys it retires or re-categorises (`RoomWorkflowPlan.statusesToCount`), after the
`FOR NO KEY UPDATE` lock on the status rows and the mapping's `400` checks, and answers `409 status-in-use` (`keys` = the offending statuses) for a counted status that is re-categorised
(no mapping can help: only retired statuses take one) or retired without an entry in `replacements`; `moveThreads(roomId, from, to, byUserId)` is called once per retired status that has
threads AND a mapping (in the order retired; never for a status with no threads), inside the same transaction, AFTER the status upsert (so the target may be a status this PUT adds or revives; pinned by `FakeWorkflowUsage`'s `targetLiveAtMove` and, with real threads, by `ThreadWorkflowUsageTest`) and
before the revision bump, so any later failure rolls the moves back; the sum is the audited `threadsMoved`. A revived status may change category freely (a retired status has no threads: they were moved when it retired).

`ThreadWorkflowUsage`: `threadCounts` is ONE `SELECT status_key, count(*) ... GROUP BY status_key` over `idx_threads_room_status`, for the keys asked (a status with no thread is absent).
`moveThreads` selects the ids of the room's threads in `from`, runs one `UPDATE threads SET status_key = to, updated_at = now` over them and appends one `STATUS_REPLACED`
event per thread (`{from, to, fromLabel, toLabel}`, one multi-row insert, by `byUserId`, on the move's own instant), then returns the count. The labels come from `RoomWorkflowService.snapshot`
(retired rows included: the source is already retired when this runs). A workflow move is **not activity**: `last_activity_at` (the thread's and the room's rail order), `message_count` and
the thread `revision` (the details token) stay; `closed_at` is untouched because the replacement has the source's category (so a CLOSED to CLOSED replacement keeps the original close time, as the status route's does).
Thread rows reference `(room_id, status_key)` with an FK to `room_statuses(room_id, key)` (`fk_threads_status`): a backstop, since every thread write runs under `withRoomWrite`, the same room lock as the workflow PUT (race 6: a status move waiting
on the lock while a PUT retires its target re-reads the status inside the lock and answers `400`).

## Threads and messages (0.3)

Binding for 0.3.0; the code lands commit by commit (plan steps S1-S6), and where a rule names a file, that file is the destination. D1 and D2 below are the
architect's recommended options taken as assumptions (`product.md` "0.3.0 threads and messages"); changing one changes one predicate and a few matrix cells.

### Data model (V14)

- **One migration, `V14__create_threads.sql`**, the whole aggregate: `threads`, `messages`, `thread_events`. Its header comment states each table's delete class:
  `threads` are **never deleted** in 0.3 (close one as *Won't do*; a room's soft delete hides them with the room); `messages` are **soft-deleted** with a
  listed tombstone; `thread_events` is **append-only** (Toadie's `V23__create_catalog_file_events.sql` shape). No CHECK on enum-valued columns (the V5 rule).
- `threads`: `id`, `room_id` (FK `rooms` ON DELETE CASCADE), per-room `number` (`#42`, unique `(room_id, number)`), `title` <= 200, `status_key`, `owner_id`
  (the creator at first), `created_by`, `fields` jsonb object, `revision`, `message_count`, `closed_at`, `last_activity_at`, `created_at`, `updated_at`.
  The composite FK `(room_id, status_key)` -> `room_statuses(room_id, key)` keeps the default `NO ACTION` (checked at statement end); the room hard-delete
  cascade into both tables at once is pinned by a test (`ThreadSchemaTest`).
- **`closed_at` mirrors the status category**: set on a move into a CLOSED status (kept across a CLOSED to CLOSED move), cleared on a move into an OPEN one. It stays exact because a category
  cannot change on a status in use and a replacement has the same category (Workflows, above).
- `messages`: `thread_id` (CASCADE), `author_id`, `body` <= 8,000, `created_at`, `edited_at`, `marked_as_deleted`, `deleted_by` (set on every delete, the author's own included; `deletedByModerator` = it differs from `author_id`). CHECKs: a tombstone has an empty body and a `deleted_by`, a live message has a body and none.
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
  checks cannot race a close or an archive. Writes serialise per room (a few ms each, fine at incident scale) and **the wait is bounded**: a writer queued behind a busy room holds a pooled connection, so the lock statement runs under `SET LOCAL lock_timeout` (3 s, `rooms.lockWaitMillis`) and a wait past it is the audited `429 room-busy` with `Retry-After: 1` (`persistence.md` "Rooms (V13)", `security.md`); the five non-POST writes also ride the per-user `chat-write` bucket, so one member cannot queue a room's lock full. **No thread-row or status-row locks.** The
  block receives the fresh caller and standing (`ThreadLock.kt`: `withThreadWrite`, `withMessageWrite`). The lock order is the room row first; the FK
  checks' `FOR KEY SHARE` on `rooms`, `room_statuses`, `threads` and `users` rows does not conflict with `FOR NO KEY UPDATE`.
- **Single messages live at `/rooms/{id}/messages/{messageId}`** (API-RES-006 bans three path parameters); the route checks the message belongs to that
  room, and a message in another room, unknown or deleted is a uniform `404`.

### API (rule form; the spec is `documentation.yaml`, the matrix `authorization.md`, the events `observability.md`)

- `GET /rooms/{id}/threads?closed=`: reader; paged; sortable `id`, `number`, `title`, `lastActivityAt`, `createdAt`; `closed` is the only filter (a strict
  boolean on `closed_at`; omitted = both; 0.5 adds the rest: "Find (0.5)"). `POST` (member, `chat-post` bucket) takes `{title, body, fields?}` and answers `201` +
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
- **Rate limits (per user, fixed in every mode):** `chat-post` 120/min on the two POSTs; `chat-write` 240/min, ONE bucket shared by the thread details, status and owner PUTs, the message edit and the message delete. Those five take the room lock like the POSTs, so leaving them unbucketed (the original reasoning: guard-gated, one row each) let one member queue writers behind a room and hold the DB pool; the audit (M1) bucketed them and bounded the lock wait. `chat-read` 300/min, ONE bucket shared by the thread list and the tag list (the two reads that scan a room's rows; sized against the SPA's refetch rate, `security.md`). The buckets sit before the guards. A room whose lock stays busy past the wait answers `429 room-busy` (`Retry-After: 1`) on any of the writes; the two reads are bounded by a deadline (`429 read-timeout`, `Retry-After: 2`) and by in-flight caps (`429 read-busy`, `Retry-After: 1`: at most 8 per user, `threads.readMaxConcurrent` per process).
- **Body ceilings:** 64 KiB for a message POST/PUT, 192 KiB for a thread POST and the details PUT (`security.md` "Request body ceilings"); `413` beyond.
- Limits: title single line <= 200 (`sanitizeSingleLine`); a message body is Markdown, 1 to 8,000 UTF-16 units after CRLF and a lone CR become LF and the leading blank lines and the trailing whitespace are dropped (the first line's indentation stays, so an indented code block survives), `\n` and `\t` allowed,
  other control characters and the hidden direction and separator characters (U+202A-202E, U+2066-2069, U+2028/2029) a `400` (`sanitizeMultiLine`; titles and TEXT values use `sanitizeSingleLine`, the same hidden-character rule; the zero-width joiner of emoji is fine); stored raw, rendered without HTML.

### Field values

Checked by the pure kernel `threads/ThreadFields.kt` against the room's field definitions (active and retired) and the active member ids; `null` clears a field.

| Type | Valid value |
|---|---|
| TEXT | single line <= 500, no control or hidden direction characters; blank means unset |
| NUMBER | strict JSON number grammar (<= 32 characters), finite, \|x\| <= 1e15; stored as one canonical number (`7` and `7.0` are the same) |
| DATE | strict `YYYY-MM-DD`, a real calendar day |
| SELECT | one option key |
| MULTI_SELECT | 1 to 50 distinct option keys; empty means unset; stored in option order, kept removed options last |
| USER | the id of an active room member |
| URL | absolute `http`/`https`, trimmed, <= 2,000, no control or hidden direction characters (`sanitizeSingleLine`), **no user info** (`https://trusted.example@evil.example/` is a `400`) |

An unknown key is a `400`, and so is a changed or new value for a retired key (an UNCHANGED retired value, e.g. from a stale form, is ignored); a required field is supplied at create and cannot be cleared once set (one never set may stay unset after create);
unchanged values skip validation (see "Stored values" under Workflows).

**Shape and size of `fields` (audit M2, M3).** The request's `fields` is shape-checked right after the body decodes and before the room lock (`requireFieldShape`, run by the route and the service through `validateThreadCreate`/`validateThreadUpdate`): at most 20 keys (`MAX_FIELDS`, the live-field maximum), each value a JSON primitive, `null`, or an array of at most 50 (`MAX_OPTIONS`) string primitives; anything else, a nested object or array included, is a `400` with a fixed detail that echoes nothing. The stored document has a ceiling too: `MAX_FIELDS_DOCUMENT_LENGTH` = **439,001** UTF-16 units of canonical JSON, checked on the merged document in the create and in a changing details PUT (`400` "The field values are too large to store"). Why that number: the kernel already bounds every value, so this is a backstop that a legitimate write never meets, and it is sized as the worst legitimate document - a room may use 200 field keys over its life (`MAX_FIELD_KEYS_PER_ROOM`, retired ones keep their last value and stay in the document), each key 40 units and each value at its widest, a 50-option MULTI_SELECT (2,151 units; a 2,000-unit URL and a 500-unit TEXT with every unit escaped are narrower): `2 + 200 x (40 + 3 + 2,151) + 199`. The live 20 alone are under 44,000. The thread list never reads `fields` (it selects the list columns only: a page of 100 would otherwise load megabytes the row never shows).

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
`thread.owner_changed`, `message.posted`, `message.edited`, `message.deleted` (with `authorId` and `moderated`). The 429 on `chat-post`, `chat-write` and `chat-read` is audited like the other buckets, a `room-busy` 429 is its own `room.busy` line, and the list reads' `read-timeout` and `read-busy` 429s are `read.timeout` and `read.busy` lines (ids only). The list is
owned by `observability.md`.

## Find (0.5)

Binding for 0.5.0: thread filters and sorts, #hashtags, full-text search. The code lands in plan steps, server first and then web; where a rule names a file, that file
is the destination. The user's decisions 0.5 D1-0.5 D5 (2026-11-19) and the assumptions 0.5 A1-0.5 A18 are in `product.md` "0.5.0 Find". Deviations from the old sketch are marked **[changed]**.

### Paged lists of a room

A room's members are the first paged sub-collection: `GET /rooms/{id}/members` (reader; `{userId,name,email,role,addedAt}` rows; sortable `id` = the user id, `name`, `role`, `addedAt`; filters `role` (enum, `400` on an unknown name) and `name` (`containsNormalized` on the display name, at most 100 characters); ACTIVE users only, count and rows over ONE predicate in one transaction, in `rooms/RoomMemberService`). A room holds at most 500 members, so the page envelope is bounded by that cap, never unbounded.

A room's threads and a thread's history are the next two: `GET /rooms/{id}/threads` (reader; sortable `id`, `number`, `title`, `lastActivityAt`, `createdAt`; the strict boolean filter `closed`; in `threads/ThreadService`) and `GET .../threads/{number}/events` (sortable `id`, `createdAt`, default `-createdAt,-id`, the shared `EVENT_LOG_*` of `infra/db/EventLog.kt`, so an old sort name such as `timestamp` is a `400`). 0.5 extends the thread list (below) and adds the room's tags and the two searches.

### The thread list: filters and sorts

`GET /rooms/{id}/threads` keeps its guard, its statuses and its response. New parameters (every array one repeatable key; `style: form`, `explode: true`, `minItems`/`maxItems` in the spec, duplicates collapsed before the bound):

| Parameter | Meaning | Value | Bound |
|---|---|---|---|
| `status` | any of these status keys | `KEY_GRAMMAR`, at most 40 | 1-20 distinct |
| `ownerId` | any of these owners | int32, at least 1 | 1-20 distinct |
| `tag` | a live message of the thread carries any of these | a tag as written (case-folded), or a stored key from `GET /rooms/{id}/tags`: 1-150 characters, no `#`, no control character | 1-10 distinct after case folding |
| `field` | `key:value`; any-of within a key, all-of across keys | key: an ACTIVE SELECT, MULTI_SELECT or USER field; value: an option key or a user id | 1-20 distinct |
| `sort=status` | the status's position in the room's workflow | | |

- **[changed] One repeatable `field=key:value`, not `fields[key]=`**: API-NAME-002 bans bracketed field names, and a room's own key could be `page`, `sort` or `status`. A key cannot hold a colon (`KEY_GRAMMAR`, `workflows/Workflow.kt`).
- **Already there, not new:** `closed` is the category filter; "mine" is `ownerId=<own id>` (the SPA sends it); per-status counts ride `GET /rooms/{id}/workflow` (`threadCount`).
- **`400`:** a malformed key, id or tag; a `field` without a colon; an unknown, retired, TEXT, URL, DATE or NUMBER field key; a value of the wrong shape for its type; too many values. A **retired field is a `400`, never a match** (its values are hidden: "Stored values survive" above). A well-formed but unknown status key, owner id or tag matches nothing; a removed option's key still filters the threads that kept it. TEXT, URL, DATE and NUMBER filters are not in 0.5.0 (0.5 D4).
- **Order:** 401, visibility `404`, then the `400`s: a non-member's bad filter is still `404`. A filter never widens what a reader sees.
- **One predicate for count and rows**, in one transaction: `room_id = ? [AND closed_at IS (NOT) NULL] [AND status_key IN (...)] [AND owner_id IN (...)] [AND EXISTS (SELECT 1 FROM message_tags mt WHERE mt.room_id = ? AND mt.thread_id = threads.id AND mt.tag IN (<folded key>, ...))] [AND (fields @> CAST(? AS jsonb) OR ...)]`, one `@>` group per field key. Containment documents: SELECT `{"severity":"s1"}`; USER `{"lead":42}` (numbers compare by value, a string never equals a number); MULTI_SELECT `{"labels":["a"]}`.
- **`sort=status`** (a `sort` list names each field at most once and at most as many fields as the whitelist holds, a `400` otherwise: `parsePaging`, so this `CASE` costs at most one per status key) is `ORDER BY CASE WHEN status_key = ? THEN 0 ... ELSE n END, id` over the room's reserved status keys (at most 200, retired ones included, so a retired status sorts where it sits) as `RoomWorkflowService.snapshot` returns them in position order; the `ELSE` ranks a key the snapshot lacks last: `threads/` never joins `room_statuses` ("Room data comes only through the `rooms/` services", `persistence.md`). `applyPaging` takes `Expression<*>` columns for it (the one departure from the verbatim Lettuce port). The snapshot is read only when a `field` filter or `sort=status` is present; the plain list keeps its two queries, and the rows still never select `fields`.
- **Where it lives:** `threads/ThreadListFilter.kt` parses `status`, `ownerId` and `field` in the route (shape `400`s: key grammar, ids of 1 to int32, a pair without a colon, more than 20 distinct values); the type checks (unknown, retired or unfilterable key, a value of the wrong shape) run in `ThreadService.list`'s own transaction over the snapshot; `infra/db/JsonbOps.kt` is the `@>` operator (a custom Exposed `Op`, verified against a real query: it renders `fields @> CAST(? AS jsonb)` with the document as a bound argument).
- `threads.fields` gets **no index** (0.5 A9): a filter scans one room. An index is a `BACKLOG.md` item for a room past a few thousand threads. There are no facet counts. The list and the tag list share the per-USER `chat-read` bucket (300/min) and each read runs under ONE `threads.readTimeoutSeconds` deadline (5 s; past it, the audited `429` `urn:adder:problem:read-timeout`): `security.md`.

### Hashtags (V15)

**[changed]** from the sketch (`#[\p{L}\p{N}_-]{1,50}` into `message_tags`): the same table, but a parsed grammar with boundaries, parsed in Kotlin only, backfilled by a boot reindex. PostgreSQL's regex has no `\p{L}` and its classes follow the database locale, and SQL cannot skip code the way the SPA's Markdown tree does, so **no tag is parsed or backfilled in SQL**.

- **What a hashtag tags (0.5 D3): the message.** A thread has a tag while one of its LIVE messages has it; editing a tag away or deleting the message removes it. No tag management screen; `#Wdrozenie` and `#wdrozenie` (case and accent folded) are one tag.
- **Grammar** (one rule, Kotlin and TypeScript; the case table `server/src/test/resources/hashtag-cases.json` is read by `HashtagsTest.kt` and by the SPA parser's test): `(?<![\p{L}\p{N}#&/])#([\p{L}\p{N}_-]{1,50})(?![\p{L}\p{N}_-])` and the capture holds a `\p{L}`. The Kotlin parser (`threads/Hashtags.kt`, pure) checks the left boundary on the code point before the `#`, not with a lookbehind (Java's lookbehind reads one UTF-16 unit and would miss a supplementary-plane letter); every pass is linear (`HashtagsTest` runs 400,000-character hostile bodies). A run of 51 or more characters is no tag; `#42` is a thread number, not a tag; `#fff` is a tag (accepted). The body is NFC-normalised for parsing only; canonical reordering is quadratic in a run of misordered combining marks, which the 8,000-unit body cap bounds (10 to 20 ms measured at the cap, `HashtagsTest`), and a `tag` filter value is length-checked before it is normalised. Fenced code, inline code, link destinations (the run after `](` up to `)` or whitespace, at most 2,000 characters; link TEXT is searched, which the SPA's tree does not link: a known disagreement, with two harmless edge cases of the same kind: `foo\#a` and `&amp;#a`, where the server indexes `a` and the SPA, reading the unescaped text `foo#a` or `&#a`, sees the hash glued to something and does not link it; and `https://x.com/a<#b`, which the SPA links as `b` and the server's bare-URL replacement swallows), link reference definitions, `<autolinks>` and bare `http(s)://` and `www.` URLs are replaced by a space first. The first 20 distinct tags of a message are indexed, the rest ignored, never refused.
- **`message_tags (message_id, thread_id, room_id, tag)`**, primary key `(message_id, tag)`, all three FKs `ON DELETE CASCADE`, ONE index `idx_message_tags_room_tag (room_id, tag, thread_id)`; `thread_id` and `room_id` are copied from the message's thread (a message never changes thread, a thread never changes room) so both reads start at one room's rows: the tag list is an index-only scan of the room and the list's filter semi-joins that scan with the room's threads (`persistence.md` "Hashtags (V15)" has the `EXPLAIN` evidence). `tag` is the folded key (`btrim(left(lower(public.unaccent(<kotlin-lower-cased tag>)), 150))`, one expression on write and on filter), `VARCHAR(150)` because folding can lengthen a tag (a vulgar fraction becomes up to five characters, `⅒` is `" 1/10"`, so a 50-character tag can fold to 246: the key is cut, never refused; the leading space `unaccent` puts before a fraction is trimmed). `messages.tags_version SMALLINT NOT NULL DEFAULT 0` (0 = not indexed yet; the constant default adds the column without rewriting the table, and there is no CHECK: a new one would scan `messages` under an exclusive lock). **The table is a derived index: hard-deleted, rebuilt from the bodies, nothing references a row, and a deleted message's tags must not outlive its scrubbed body.**
- **Lifecycle**, inside the message's own write under the room lock (`MessageService.insertInTransaction`, the edit, the delete): post and a thread's opening message parse and insert, ONE `INSERT ... SELECT ... FROM unnest(...) ON CONFLICT DO NOTHING` per message (two spellings can fold to one key) and set `tags_version = 1`; an edit parses the old and the new body and, if the lower-cased sets differ (or the message's `tags_version` is below the current one: it was never indexed, so an unchanged-looking set still has no rows), deletes and inserts, and sets `tags_version`; a delete, a moderator's too, deletes the message's rows AFTER the row's own update (the statement that waits out a reindex holding the row). The message's own `UPDATE` is first in both, so the tags are written last. No new hint, audit line or `MessageResponse` field: the edit's `message.edited` hint already covers a tag change.
- **Reindex at boot** (`MessageTagService.reindexStale`, once, in its own scope, when `threads.tagReindexOnBoot` is true; the test harness turns it off): batches of 200 live messages with `tags_version < 1`, `FOR NO KEY UPDATE OF messages SKIP LOCKED`, one delete and ONE insert statement per batch, then the version. A racing edit waits and writes its own tags, or holds the row and is skipped. Before it, `purgeDeletedTags` removes the tag rows of DELETED messages (at EVERY boot, whatever `threads.tagReindexOnBoot` says: it only deletes rows that must not exist) in batches (a 0.4.0 binary after a rollback deletes without knowing the index; `persistence.md` has the runbook). It logs counts; a failure logs class names only and leaves the rows stale for the next boot. Bumping `TAGS_VERSION` reindexes everything after a grammar change.
- **`GET /rooms/{id}/tags`** (`listRoomTags`, reader): `{tag, threadCount}` rows in the paged envelope (`RoomTagPage`); sortable `tag`, `threadCount`, default `-threadCount` (the helper's `id` tiebreaker maps to `tag`, as the members list maps `id` to the user id); filter `tag`, a `containsNormalized` substring of at most 50 characters, for the picker. **A returned `tag` is a valid `tag` filter value** (`requireTagFilter` accepts the stored-key shape, 1-150 code points without `#` or control characters, because folding adds punctuation and length: `ʻ` becomes `'`, `ß` becomes `ss`, `½` becomes `1/2` once trimmed), and the 10-value bound counts values after case folding. `threadCount` counts distinct threads with a live message carrying the tag, open and closed. Reader-only, on the `chat-read` bucket shared with the thread list and under the same read deadline (`read-timeout`). Statuses: 200/400/401/404/429/500.
- **The SPA renders a tag as a link** (a remark plugin that walks the Markdown tree and skips `code`, `inlineCode`, `link`, `linkReference`, `definition`, `html` and `image`) to the thread list with `tag=<as typed>&scope=all`; the server folds it.
- **Not in 0.5.0:** tags in titles, a thread's tag list on rows or in the side panel, a rename or merge of tags, `#42` as a link.

### Search (V16)

**[changed]** from the sketch (a trigger-maintained `tsvector`, an immutable `unaccent` wrapper, the `simple` configuration, a GIN index that was built at first and dropped again): `unaccent()` is STABLE, so it cannot feed a generated column or an expression index, and the trigger and the wrapper were two workarounds for one problem. PostgreSQL's own answer is a **text search configuration with `unaccent` as a filtering dictionary**: `to_tsvector(regconfig, text)` is IMMUTABLE, so the vector is a **stored generated column**, with no trigger and no wrapper, and the `ALTER TABLE` is the backfill. `ts_headline` with that configuration marks hits in the ORIGINAL text (a wrapper design would headline unaccented text or miss accented hits).

```sql
CREATE TEXT SEARCH CONFIGURATION public.adder_search (COPY = pg_catalog.simple);
ALTER TEXT SEARCH CONFIGURATION public.adder_search
    ALTER MAPPING FOR hword, hword_part, word, numword, numhword, hword_numpart WITH public.unaccent, pg_catalog.simple;
ALTER TABLE messages ADD COLUMN search tsvector GENERATED ALWAYS AS (pg_catalog.to_tsvector('public.adder_search'::regconfig, body)) STORED;
ALTER TABLE threads  ADD COLUMN search tsvector GENERATED ALWAYS AS (pg_catalog.to_tsvector('public.adder_search'::regconfig, title)) STORED;
```

**No index on either vector, deliberately.** The match is a per-thread probe: the search restricts to the searched rooms' threads first, then reads each thread's messages through `idx_messages_thread` and stops at the first live message that matches, so the work is **linear in the searched rooms' own messages** (a room's, or a caller's across rooms), never in the table. A GIN index on `messages.search` was part of V16 until the cross-room search was measured: no statement reads it (`persistence.md` "Search (V16)" has the `EXPLAIN` evidence), it would cost about 100 MB per 100,000 messages and slow every post, and a table-wide match is the cost the search avoids. When a room's or a caller's message count makes the probe too slow, `BACKLOG.md` holds the options (`btree_gin` over `(thread_id, search)`, or a `room_id` on `messages`): a LATER migration, with its own measurement.

- **`STORED` must be written**: on PostgreSQL 18 a bare `GENERATED ALWAYS AS (...)` is VIRTUAL, and is computed again on every read, and indexes on virtual generated columns are not supported. The six token types are the ones that can hold non-ASCII letters; the ASCII types keep `simple` alone. `threads.search` gets no index: the room index drives that scan and the title match is a filter on it.
- **Backfill cost:** each `ALTER TABLE` rewrites its table and computes every row (about 7 s per 100,000 messages of 1.4 KB; no index is built: `persistence.md` "Search (V16)"); the app is not serving while Flyway runs and the Kubernetes startup probe allows about 150 s. **Never declare `search` in an Exposed table object** (`selectAll()` would read a vector on every message read, and the driver has no `tsvector` codec): `infra/db/Fts.kt` refers to the columns as free expressions. After the configuration changes, or a PostgreSQL major upgrade changes the `unaccent` rules, recompute with `ALTER TABLE ... ALTER COLUMN search SET EXPRESSION AS (...)`, in the same migration file that alters the configuration. A non-superuser owner can create the configuration; it cannot be dropped while a column depends on it.
- **What matches (0.5 A2, 0.5 A3):** **[changed]** message bodies and thread titles, never field values, never tombstones. All terms are required within one message or one title; **[changed]** `simple` does not stem, so every term of three or more characters matches as a prefix and shorter ones match exactly (`awaria w bazie` becomes `'awaria':* & 'bazie':* & 'w'`); case and accents are ignored; no operators, no ranking. Parser facts that need a doc line: `PROD-123` indexes as `prod` and `-123` (searching `PROD-123` finds it, `123` alone does not: BACKLOG); `foo_bar` splits in two; `#db-outage` indexes `db-outage`, `db` and `outage`, so hashtag text is searchable; a token of 8,000 characters is skipped with a NOTICE and the INSERT still succeeds.
- **No input is a syntax error.** The tsquery is built from `to_tsvector`'s own lexemes (documents and queries share one parser and one folding), so `'; DROP TABLE messages; --` comes out as plain terms; input with no lexeme (`!!!`, `<script>`) gives a NULL query, which matches nothing and raises nothing.
- **Endpoints:** `GET /rooms/{id}/search` (`searchRoom`, reader) and `GET /search` (`searchRooms`, any authenticated caller, over the rooms they are an ACTIVE member of). `q` is REQUIRED (the shared `Q` parameter, 2-100 code points after trimming, `sanitizeSingleLine`, a letter or a digit), `closed` is a strict boolean (omitted = both), `sort` is `lastActivityAt`, `createdAt` or `id` (default `-lastActivityAt`), `pageSize` default and maximum 20 (`SearchPageSize`). **Check order:** 401; the `search` bucket (429); for the room route, visibility (404, audited `room.access_denied`); the parameters (400); the query. Statuses: room 200/400/401/404/429/500; global the same without 404. `q` stays in the query string (API-LIST-005) and out of every trail: audit lines and problem bodies carry the path only.
- **One item is a thread** (`ThreadSearchHit`: room id, name and `roomArchived`; thread number, title and `titleSnippet`; status key, `closed`, owner, `messageCount`, `lastActivityAt`; `matchCount`; and `messages`, the three newest matching live messages with their `snippet`), in the page envelope (`total` counts matching threads, no shape exception). A title-only hit has no messages and `matchCount` 0. A snippet is a list of typed segments `{text, hit}`: `ts_headline` returns markup untouched, so the wire never carries a marked-up string; the server headlines with the markers U+0002 and U+0003 (unforgeable, because every title and body refuses control characters), splits on them and collapses whitespace. A snippet is a fragment of the Markdown source as plain text. Only the page's rows are headlined.
- **The search across rooms (`GET /search`)** reads the caller's rooms first (`RoomService.memberRooms`: ACTIVE rooms of an ACTIVE membership, an archived one included, a deleted one never; membership only, an ADMIN's too) and passes them as ONE array argument (`threads.room_id = ANY(...)`); no rooms is an empty page without a search; there is no room filter. The thread predicate probes a thread's messages through a correlated scalar subquery on `COALESCE(search, '')` (never `EXISTS`, never the bare column), so the work is bounded by the caller's own threads in every plan mode: the evidence is in `persistence.md` "Search (V16)". The room search takes the same path with one room.
- **Statements**, one read transaction under ONE deadline of `search.timeoutSeconds` (5, 1..30): the query text (`string_agg` over `unnest(to_tsvector(...))`, quoted and cast to `tsquery::text`; Kotlin keeps it and later statements cast it back); the count and the page of threads (`t.search @@ q OR (the first live message of the thread with m.search @@ q) IS NOT NULL`, `ORDER BY t.last_activity_at DESC, t.id`); the snippets for the page's thread ids only (a `row_number()` over the thread's matching messages, `rn <= 3`, `count(*)` as `hits`); the title headlines with `HighlightAll`. **One deadline for the request** (`SearchBudget`): each statement's `queryTimeout` is the whole seconds LEFT of `search.timeoutSeconds` (rounded up, the driver takes whole seconds), the member-rooms read included, and a deadline already passed between two statements sends no further statement, so a request ends within the budget plus at most one second whatever its number of statements. Past it (`isQueryCanceled()` or the spent deadline) the service answers a typed `429` `urn:adder:problem:search-timeout` with `Retry-After: 2`, audited `search.timeout` (ids only, never the term); the throwable is not logged. **At most `search.maxConcurrent` (4, 1..16) searches run in the process at once, both routes together**: a `Semaphore` `tryAcquire` before any pool connection is asked for (never a wait), and a refusal is the typed `429` `urn:adder:problem:search-busy` with `Retry-After: 1`, audited `search.busy` (ids only). Two types because the remedies differ: `search-timeout` says narrow the query, `search-busy` says the server is loaded and to retry shortly.
- **[changed] Who finds what (0.5 D2: member rooms only, for everyone):** a member finds their room; an ADMIN who is not a member gets 200 from the room route (an ADMIN reads every room) and **no hit from that room in the global search**; an outsider gets 404 (audited) from the room route and no hit, count or name globally; an archived room is searched and labelled (`roomArchived`), a deleted room never; a deleted message never (empty vector and `NOT marked_as_deleted`); a removed member loses the room on the next request. The global route reads the caller's rooms through `RoomService.memberRooms(userId)` (`(id, name, archived)` of active rooms joined to the caller's membership and the active user); `threads/` still never touches a room table, and no rooms means an empty page without a search.
- **Where it lives:** `infra/db/Fts.kt` (the configuration name, the query text, `@@`, the headline options, `splitHeadline`; no feature named), `threads/ThreadSearch.kt` (DTOs, `validateSearchQuery`, `parseSearchParams`), `ThreadSearchService.kt` (the transaction, the budget, the page), `ThreadSearchSnippets.kt` (the two headline statements, raw SQL: a window function over a column no table object declares), `ThreadSearchRoutes.kt` (`configureThreadSearchRoutes`). The room route reads its room's name and archived flag through `RoomService.summary`, the route for all rooms the caller's rooms through `RoomService.memberRooms`; `SearchBudget.kt` is the request deadline. A snippet is a `ts_headline` FRAGMENT (`MaxFragments=2, MaxWords=18, MinWords=6`): it drops a short word (three characters or fewer) at the edge of a fragment, so `The outage started` shows as `outage started`; the title is headlined whole (`HighlightAll`).
- **Buckets, caps and logs:** one per-user bucket `search` (60/min) for both GETs, before every guard; `rate_limit.exceeded` gains the bucket; the process-wide concurrency cap above. A search is a read: no audit line. Tags, terms and snippets never reach a log line. A failed statement logs exception CLASS names and the SQLSTATE only: Exposed's `ExposedR2dbcException.getMessage()` appends the statements with their arguments expanded, so a failed message INSERT would log its body and a failed search its term.
- **Not searched or not live in 0.5.0:** field values; Markdown stripped from snippets; phrase and exclusion operators; search within one thread; ticket keys; search results are not live (a busy room would spend the reader's bucket on refetches; `revoked` invalidates an open global search).

### The SPA's half of Find

- **The list's state is the URL** (0.5 A13): `scope`, `order`, `status`, `owner` (an id or `me`), `tag`, `field`, `page`, `focus`, `message`; junk is dropped and defaults are omitted; a filter change deletes `page` and uses `replace`; every in-room link keeps the list keys. The last non-default query per room is remembered (`roomThreads.<id>.query`) and restored once on a bare Threads view; "Clear filters" forgets it. The old per-room `scope` and `order` localStorage keys are dropped.
- **The focus view (0.5 D5)** is a layout switch: `focus=1` on a thread route hides the list column and the conversation takes the main area; it lives in the URL (reloadable, shareable), not remembered.
- **Jump to a message** (0.5 A17): `message=<id>` loads older pages until it appears, at most 10 loads, then scrolls, focuses and highlights; past the bound, or for a gone message, a gray note says so.
- **Search is submitted** (0.5 A16), not as-you-type: `/rooms/:id/search` and `/search`, and the palette offers "Search for ..." actions. A snippet renders as text and `<mark>`, never HTML, never Markdown.
- **Live (0.5 A14):** no new hint kind; `message.edited` also invalidates the thread list (an edit can add or drop a hashtag); the tag picker's key sits under the list prefix.

## Mentions, reactions, pins, unread (0.6)

**[changed]** The thread list's "mentions me" filter moved here from 0.5 (0.5 D1): until the 0.6 composer writes `<@userId>` tokens and `message_mentions` exists, there is nothing to filter on.

`<@userId>` tokens parsed into `message_mentions` and notified (Lettuce's `notifications/`); reactions keyed `(message, user, emoji)`, one emoji
grapheme, ≤ 20 distinct per message; room-level pins of threads or messages; a per-member last-read marker per thread.
