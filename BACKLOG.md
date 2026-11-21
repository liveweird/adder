# Product backlog

Updated 2026-11-21. This file tracks **outstanding work only**. Implemented behaviour and release history belong elsewhere:
- [README.md](README.md);
- the [application changelog](web/src/changelog/entries.ts);
- the topic guides under `.claude/docs/` (the roadmap: `.claude/docs/product.md`).

Entries are proposals, not delivery commitments.

**Status tags.** Every top-level item under a `##` section opens with one tag:
- `[next]`: what comes next;
- `[todo]`: confirmed, to do;
- `[new]`: proposed, not yet confirmed. That includes the decisions only the user can take. Claude adds items as
  `[new]`, and only the user promotes them;
- `[parked]`: deliberately set aside until a trigger;
- `[blocked]`: waiting on someone or something else.

The `backlog` mod (`liveweird/claude-mods`) summarises the tags on the status line and in `/backlog`.

## Chat MVP (0.5–0.6)

- [new] **Mentions: `@here`, and mentioning someone who is not a member.** 1.1.0 ships `<@everyone>`, `<@watchers>` and `<@team:id>`; an id that is not an active member still stays plain text, and `@here` does not exist (1.1 A16). Add `@here`, and an offer to add the named person to the room.
- [new] **Mentions and hashtags: blank indented code and raw HTML in the shared pre-pass.** `textOutsideCodeAndLinks` (`threads/MarkdownText.kt`) does not see an indented code block (four spaces, then `<@5>`), a raw HTML block or an image's alt text, so the server indexes (and notifies) a mention the SPA shows raw. Blanking them there would close the gap for mentions and change which hashtags are indexed too (`#tag` in an indented block stops being a tag), so it needs the hashtag case table and a note in the release text. The disagreement is documented in `web-internals.md` "Mentions in messages" and pinned by a SPA test.
- [new] **Notices for being added to a room and for a reaction.** 1.1.0 has eleven kinds, replies among them (a watcher is told, and the owner watches); being added to a room and a reaction have none (1.1 A8).
- [new] **Reactions: the full list of who reacted.** A message shows each emoji's count and the first three names (0.6 A25); add a paged read of the reactors.
- [new] **Reactions: the official emoji list.** The server checks that a value is one emoji grapheme by rule, not by list, so a chain of up to five pictographs joined by a zero-width joiner passes (`chat-model.md` "Reactions (V20)").
- [next] **Unread: an "unread" filter on the thread list, and "Mark all as read" in a room.** The second would raise `read_from`, which is why that column is its own.
- [next] **Unread: a count in the browser tab title.** Needs the hidden-tab socket rule revisited (a hidden tab closes its socket after 5 minutes, `live.md`).
- [new] **Unread: read state pushed between a person's tabs.** A `BroadcastChannel` would do it with no server change; 0.6.0 lets another tab catch up on focus.
- [new] **Notifications: browser push, and muting a thread.** 1.1.0 has an in-app and an email switch per kind (1.1 D7). Browser push is a third channel; muting a thread would silence its mentions too (stopping to watch only ends the activity notices).
- [next] **Room view: better use of screen space.** (the user, 2026-11-21) (1) The right-hand side panel (status, owner, fields, history) is always shown but rarely used: it should take space only when needed (collapsed by default, opened on demand). (2) The header takes a lot of vertical space: make it more compact.
- [next] **People and rooms as links wherever their names appear.** (the user, 2026-11-21) A person's name (a message author, a mention, an owner, the actor of a notice) and a room's name (a notice, a search hit) should be a link to its screen, not a plain label. A room has one (`/rooms/:id`); a person has none any member can open (the `/users` pages are ADMIN-only), so this needs a read-only profile page first, and a rule for what a non-ADMIN may see on it.
- [next] **Tags: an ADMIN-curated registry set on threads, beside hashtags.** (the user, 2026-11-21) Port Hammurabi's `tags/` (itself Toadie's: V13 `tag_categories` + `tags`, `/api/v1/tag-categories`, any authenticated GET, ADMIN writes under `X-Expected-Revision` in a `lockingTransaction`, a tag in exactly one category, normalised names, retired-not-erased with revive, a seed) and a thread's own `thread_tags` set by an explicit PUT (Hammurabi's `PUT /adrs/{id}/tags`, at most 20, strict: unknown or retired is a `400`). The words stay apart: **hashtags** are the `#word`s parsed from message text (0.5.0, V15) and keep their name; **tags** are the curated labels. To decide: who sets a thread's tags (any member, or the steward), whether a closed thread freezes them, one global registry or per room, and a list parameter name that does not clash with the hashtag filter's `tag`.
- [new] **Find: range and text filters on field values.** 0.5.0 filters SELECT, MULTI_SELECT and USER fields only (exact, any-of; 0.5 D4). Add DATE and NUMBER ranges (`field[gte]`, `field[lte]`) with their inputs, and TEXT filters.
- [new] **Find: facet counts in the filter panel.** 0.5.0 labels only the status options with the workflow's `threadCount`. Counts per owner, tag and field value would port Covenant's lifted-predicate facets (`ContractService.kt`).
- [new] **Find: search within one thread, and anchored message reads.** The focus view (0.5 D5) is a layout switch only. Add a flat, paged list of one thread's matching messages, and a read of the messages around an anchor so a jump needs no loop of older pages.
- [new] **Find: search over field values, and Markdown stripped from snippets.** 0.5.0 searches message bodies and thread titles; a snippet is a fragment of the Markdown source as plain text, so from 0.6.0 a hit near a mention shows the raw `<@id>` token; show the member's name instead.
- [new] **Find: phrase and exclusion operators.** 0.5.0 matches all terms, as prefixes, with no operators and no ranking.
- [new] **Find: ticket keys.** `PROD-123` indexes as `prod` and `-123`, so `123` alone does not find it. Index the key whole, or add a second configuration for it.
- [next] **Hashtags: a thread's tags on list rows and in the side panel, and `#42` as a link.** 0.5.0 shows tags only as links in message text and in the filter panel; `#42` is a thread number and stays plain text.
- [new] **Find: a search for a caller in hundreds of rooms with a word nobody wrote.** The search probes the caller's own threads' messages (about 7 to 10 microseconds each, no early exit when nothing matches): 1.0 s for the count plus 0.8 s for the page at 100,000 messages of the caller's own, linear after that, and the 5 s budget answers the typed 429 past it (`persistence.md` "Search (V16)"). A `btree_gin` index over `(thread_id, search)`, or a `room_id` on `messages` with a composite, would let an index serve it (V16 builds none: a GIN index on `messages.search` was dropped from it because no statement read it); the measurement did not demand one for 0.5.0. The same scan makes a room with hundreds of thousands of messages slow for an absent word. There is no per-user room cap either: `memberRooms` is unbounded, and a hostile user in many rooms of 8,000-character messages is the abuse scenario in `security.md` "Search" (bounded by the deadline, the bucket and the concurrency cap, not by size).
- [new] **Find: an index on `threads.fields`** (GIN, `jsonb_path_ops`) if a room passes a few thousand threads. 0.5.0 filters by scanning one room (0.5 A9).
- [new] **Live: a second replica.** The ticket store and the hub are in memory, per process (`live.md`), so tickets work on one replica only. Two replicas need
  sticky routing of `/api/v1/live*` (the ticket POST and the socket on the same pod) or a shared ticket table; every replica's LISTEN connection already
  hears every hint.
- [new] **Live: push account changes.** 0.4.0 closes a socket for a logout, user deletion, password change or role change by a 60 s sweep (the user's decision D2).
  Pushing those four events (a NOTIFY the hub acts on at once, about five call sites in `auth/` and `users/`) would close the gap to zero; the sweep stays as the
  safety net. Since 0.6.0 the per-user delivery class exists (a hint's targets reach every socket of those users); what is still missing is a hint without a room.
- [new] **Live: skip the writer tab's echo.** The tab that made a write refetches twice, once for its own mutation and once for the hint. Tag the hint with the
  writer's socket (or a client id) so that tab can skip it.
- [new] **Notifications: bound what one recipient can pile up (more pressing since 1.1.0).** Eleven kinds, watching and group mentions make a busy room fill the bell far faster than 0.6.0 did, and retention stays 90 days (1.1 A25). A per-recipient cap on UNSEEN notices (the list's `total` is capped at 1000, the rows are not), and batching the two writes that still touch every row in one statement: `threadRead` (marks a thread's notices seen) and the removal cascade of a leaving member's notices. `seen-all` is already chunked by 1,000 rows, one short transaction each.
- [new] **Boot purge: probe each derived table through its message index.** `StaleDerivedRows` finds the deleted messages that still carry a mention, reaction, pin or notice by a `UNION` over the four tables (30 s statement budget). At a very large size, probe each table through its `message_id` index per candidate batch instead of one union.
- [new] **Watching: the list of who watches a thread.** 1.1.0 puts `watcherCount` on the wire and not the people (1.1 A5). Add a paged read (members only) and, with it, an answer to who may see it.
- [new] **Notices: name the team in a team-mention notice.** The notice says "mentioned you", not which team did (1.1 A14); `message_group_mentions` has the team, so the bell and the digest can say "mentioned your team <name>".
- [new] **Notices: one bell row per busy thread.** Replies and mentions stay one notice per message (status and owner moves already fold into one unseen row, 1.1 A37); a watched incident thread can still fill the bell. Collapse a thread's unseen activity notices into one row ("12 replies in #42") in the bell and the digest; pairs with the per-recipient unseen cap above.
- [new] **Pool headroom: readers 18 of 20 leave two connections for writes and token verdicts.** The production boot check (`requireReadersUnderPool`) only demands that thread reads (10) + searches (4) + notices (4) stay below `POSTGRES_POOL_MAX_SIZE`, so at the default of 20 the readers may hold 18 and leave two connections for every write, the 60 s live sweep and token verdicts (1.1.0 branch audit, L2). Require a margin (a reserve for writes) or raise the default pool.
- [new] **Notices: status and owner detail through an `event_id`.** A status or owner notice says that something moved, not from what to what; point the notice at its `thread_events` row so the bell can show the move.
- [new] **Watching: watch on reply, with a sticky unwatch.** 1.1.0 watches on create and on hand-over only (1.1 D10). Watching on reply needs a `state` column on `thread_watches` so that a person who unwatched is not pulled back (about a day more).
- [new] **Account-management notices: port Lettuce's locked-on `PASSWORD_CHANGED` / `ACCOUNT_REACTIVATED`.** Adder sends the MFA code and the reset link only (1.1 A29); a "your password changed" and an "account reactivated" email are a locked-on kind that no preference can switch off.
- [new] **API guidelines: two lines for the lost-update inventory (the user owns the file).** Add to `api-guidelines/API-GUIDELINES.md` "Lost-update inventory" (API-CACHE-003): (1) `PUT /api/v1/rooms/{id}/threads/{number}/watch` is a single-value set of the caller's own state, last writer wins by design, a repeat is an idempotent `204` (`ThreadWatchTest`); (2) `PUT /api/v1/users/{id}/notification-preferences` is a full replace, last-write-wins and accepted, so a stale client resets what it does not know (1.1 A31).

## 1.0.0 checkup (2026-11-20)

The record, with evidence and the proposed fix per id: `.claude/docs/audit-status.md` "1.0.0 checkup". The fix-before-1.0.0 tier, the can-follow tier and every decision are done ("Fixed so far" there); what is left below can follow the tag.

- [new] **Deferred refactors (C-CPX-1).** Donor-sized machinery (`MultiRowInsert.kt`, the 400-line `server/build.gradle.kts` block, `AuthRoutes.kt` login, `NotificationDigest.kt` on the 400-line cap).
- [new] **A per-(actor, recipient) digest limit.** The V24 budget caps how many distinct people one actor can make email-eligible per day, not how often an admitted recipient is mailed (one digest per 15 minutes while notices keep coming; the remedy today is the per-kind email switch). Add a pair limit if that proves insufficient.

## Foundation follow-ups

- [new] **Client-aborted responses log as ERROR.** Three full e2e runs left 88 `Unhandled exception` ERROR lines for `GET /assets/*.js` and `/api/v1/telemetry/history` whose root cause is a closed channel (`CancellationException`, `ClosedByteChannelException`, `ChannelWriteException`): the browser navigated away mid-response. Log a client abort at DEBUG (or not at all) in `ErrorHandling`, so a real ERROR stands out.
- [new] **Sign-out fires a stray 401 refetch.** `bindSessionQueryCache` clears the query cache while the old page is still mounted, so the rail (`useRailRooms`) and room header (`RoomPage`) queries refetch once without a token. Harmless console noise; gate those queries on a live session or unmount before clearing.
- [new] **A test flake: `IllegalAccessError` at `LiveSweep.kt:71`.** Seen once during a 0.6.0 `koverXmlReport` run (`UserRoutesTest` "admin creates a user who can log in with the supplied password"), green on rerun: likely the live sweep's coroutine outliving `testApplication`'s class loader. The likely cause is fixed (the live scope is now joined on `ApplicationStopping`, before the pool is disposed; `infra/BackgroundScope.kt`); remove this item after a week of green nightlies without it.
- [new] **`ChatLogHygieneTest` does not plant a pin.** The 0.6.0 plan wanted every chat write in it; reactions and the digest are covered (the digest in `NotificationDigestTest`), pins only by `PinsTest`'s audit assertions.
- [new] **`RowLockVersusDeriveTest` (0.3.0 audit).** `persistence.md` promised it back "with the first long chat transaction"; the workflow PUT's `moveThreads` under the room lock is one. Port Flow's test shape.
- [new] **The emoji picker's React peer (0.3.0 audit).** `@emoji-mart/react` 1.1.1 declares React ≤18 as a peer (installs only with `--legacy-peer-deps`); consider inlining its ~20-line wrapper. (The picker under the production CSP is covered since 0.6.0: `social.spec.ts` opens it and listens for `securitypolicyviolation`.)
- [new] **Room restore.** Soft delete (ADMIN `DELETE /rooms/{id}`) has no restore API; an ADMIN cannot bring a deleted room back except through the database. Decide the shape (a restore route, or a trash list) and the name-reuse rule.
- [new] **History interleaved in the conversation.** 0.3 shows a thread's history in the side panel only; consider interleaving the events with the messages in one timeline.
- [new] **The message composer nests a `<div>` in a `<p>`.** React warns twice in the console when a thread pane mounts ("cannot be a descendant of <p>"): `MessageComposer`'s Mantine `Textarea` `description` renders block content inside its `<p>`. Seen while verifying 0.5.0 W1; pre-existing.
- [new] **Message edit history.** An edit overwrites the body and sets `edited_at`; no versions are kept. Decide whether to keep them (who may see them, and how a moderator delete scrubs them).
- [new] **Thread delete or merge.** Threads are never deleted in 0.3 (close one as *Won't do*); decide whether an owner or ADMIN may delete a thread, or merge a duplicate into another.
- [new] **Relax the same-category replacement rule.** Retiring a status in use requires a replacement in the SAME category (so a thread never silently opens or closes). Consider allowing a cross-category move with an explicit confirmation now that threads exist.

- [new] **Room squatting: a creator column, a per-creator cap and an ADMIN clean-up path.** A room has no creator record (V13 is committed), so an ADMIN
  can only find the creator of a squatted name through the `room.created` audit line. Add a creator (a later migration), a cap on the active rooms one
  user may create, an owner/creator filter on `GET /rooms` for ADMINs and an ADMIN reassign/delete path; the `room-name` bucket only slows it (`security.md`).

- [new] **Bots posting into rooms.** Port Flow's `integration/` service tokens when an external system should post or update threads.
