# The chat domain (sketch)

Owns: the chat domain model — rooms, membership, workflows (statuses and fields), threads, messages, history, mentions, reactions, pins, unread.
Not here: the live channel (`live.md`), the roadmap and decisions (`product.md`). **Status: a sketch.** Each version (0.2–0.6) turns its part
into binding rules in its own plan, then rewrites the section below as `## Rules` first. No chat package exists yet.

## Rooms (0.2)

- `rooms`: name, slug, description, `archived` (read-only), soft delete (`marked_as_deleted` + partial unique indexes).
- **Members only**: `room_members(room_id, user_id, role OWNER|MEMBER)`. The creator is OWNER; an OWNER or ADMIN adds/removes members (a person,
  or a whole team at once — `teams/`), transfers ownership, archives. A non-member gets `404` (existence is not disclosed). ADMIN reads every room.

## Workflows (0.2)

- A room owns its own workflow: ordered **statuses** `{key, label, colour, category OPEN|CLOSED}` (colour from the semantic vocabulary only;
  exactly one initial OPEN status) and **fields** `{key, label, type TEXT|NUMBER|DATE|SELECT|MULTI_SELECT|USER|URL, options, required}`.
- Copied at room creation from an ADMIN-curated **workflow template** registry. Seeds: *Default* (Open → In progress → Waiting → Done ✓ /
  Won't do ✓) and *Incident* (Triage → Investigating → Mitigating → Monitoring → Resolved ✓; fields Severity S1–S4, Component, Jira link, ETA).
- Edited by the room OWNER or an ADMIN as a whole-document PUT under `X-Expected-Revision` (Toadie). Keys are immutable; removing a status that
  threads use is a `409` unless a replacement mapping is given.

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
