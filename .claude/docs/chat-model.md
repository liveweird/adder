# The chat domain

Owns: the chat domain model — rooms, membership, workflows (statuses and fields), threads, messages, history, mentions, reactions, pins, unread.
Not here: the live channel (`live.md`), the roadmap and decisions (`product.md`). **Status:** rooms and workflows (0.2) are binding `## Rules`;
threads onward (0.3–0.6) are still a sketch, and each version turns its part into rules in its own plan first. `workflows/` and `rooms/` (0.2.0) are the first chat packages; the room routes land commit by commit (the record and its read-only workflow in C7, members in C8, the workflow PUT in C9).

## Rules

Binding for 0.2.0 (rooms and workflows). The code lands commit by commit; where a rule names a file, that file is the destination. The user's
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
  case-insensitively after trimming, a duplicate being a 400 that names the label; a room's own labels (0.2.0 C9) follow the same rule). Seeds by migration, never edited afterwards: *Default* (Open initial, In progress, Waiting,
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
  retired status's replacement must have the same category. Fields and options need no mapping in 0.2; what a removed option means for stored
  values is decided in 0.3.

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
  the pure kernel (`RoomWorkflowPlan.kt`: retired, revived, added, re-categorised); field-type and key-budget checks; usage counts, then the
  replacement checks; `usage.moveThreads`; clear `is_initial` on every row (first: the partial unique index is checked per row); retire absent
  keys; upsert payload rows by key with `position = index`; bump `workflow_revision`. Per-row Exposed statements (<= 20 rows each).
- **409 problem types** (`urn:adder:problem:`): `revision-conflict`, `last-owner`, `room-archived`, `status-in-use` and `field-type-immutable`
  (both carrying `keys`, the `ProblemDetail` extension member), plus the plain key-budget conflict.

### The mapping contract 0.3 plugs into

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
server tests cover the retire path through a fake and the web through a mocked `threadCount`. 0.3 does four things and changes nothing else:
thread rows reference `(room_id, status_key)` with an FK to `room_statuses(room_id, key)`; a thread write that sets a status takes `FOR SHARE`
on that status row and requires it active (a thread can then never land on a status a PUT is retiring); it implements `WorkflowUsage` (the move also
writes one `thread_events` row per thread) and registers it under the key; it adds the e2e for the retire dialog.

## Threads and messages (0.3)

- `threads`: per-room sequence number (`#42`), title, `status_key`, `owner_id` (the creator at first), `fields` (jsonb, validated against the
  room's field definitions), `last_activity_at`, reply count, `revision`.
- **Closed** = the status's category is CLOSED. A closed thread refuses replies, edits, reactions, field and owner changes (`409`,
  problem type `thread-closed`). Reopen = move to an OPEN status, allowed to the thread owner, the room OWNER or an ADMIN.
- Ownership passes by the owner, the room OWNER or an ADMIN; any member moves between OPEN statuses.
- `messages`: Markdown body (≤ 8,000 chars), author, edited flag, soft delete (a "message deleted" tombstone); authors edit/delete their own.
- `thread_events` (Lettuce's `EventLog`): created, status changed, owner changed, field changed (`from`/`to` params), closed, reopened —
  rendered in the viewer's language, never stored as text.

## Find (0.5)

Thread filters and sorts per `list-endpoints.md`; full-text search over messages (trigger-maintained `tsvector`, an immutable `unaccent` wrapper,
the `simple` configuration for EN+PL, GIN index), limited to the caller's rooms; #hashtags (`#[\p{L}\p{N}_-]{1,50}`) parsed server-side into
`message_tags`.

## Mentions, reactions, pins, unread (0.6)

`<@userId>` tokens parsed into `message_mentions` and notified (Lettuce's `notifications/`); reactions keyed `(message, user, emoji)`, one emoji
grapheme, ≤ 20 distinct per message; room-level pins of threads or messages; a per-member last-read marker per thread.
