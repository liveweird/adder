# Product backlog

Updated 2026-11-19. This file tracks **outstanding work only**. Implemented behaviour and release history belong elsewhere:
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

- [new] **0.5.0 Find.** Filters, sorts, full-text search, hashtags.
- [new] **0.6.0 Mentions, reactions, pins, unread.** Includes the thread list's "mentions me" filter, moved from 0.5.0 (`product.md`, 0.5 D1).
- [new] **Find: range and text filters on field values.** 0.5.0 filters SELECT, MULTI_SELECT and USER fields only (exact, any-of; 0.5 D4). Add DATE and NUMBER ranges (`field[gte]`, `field[lte]`) with their inputs, and TEXT filters.
- [new] **Find: facet counts in the filter panel.** 0.5.0 labels only the status options with the workflow's `threadCount`. Counts per owner, tag and field value would port Covenant's lifted-predicate facets (`ContractService.kt`).
- [new] **Find: search within one thread, and anchored message reads.** The focus view (0.5 D5) is a layout switch only. Add a flat, paged list of one thread's matching messages, and a read of the messages around an anchor so a jump needs no loop of older pages.
- [new] **Find: search over field values, and Markdown stripped from snippets.** 0.5.0 searches message bodies and thread titles; a snippet is a fragment of the Markdown source as plain text.
- [new] **Find: phrase and exclusion operators.** 0.5.0 matches all terms, as prefixes, with no operators and no ranking.
- [new] **Find: ticket keys.** `PROD-123` indexes as `prod` and `-123`, so `123` alone does not find it. Index the key whole, or add a second configuration for it.
- [new] **Hashtags: a thread's tags on list rows and in the side panel, and `#42` as a link.** 0.5.0 shows tags only as links in message text and in the filter panel; `#42` is a thread number and stays plain text.
- [new] **Find: a search for a caller in hundreds of rooms with a word nobody wrote.** The search probes the caller's own threads' messages (about 7 to 10 microseconds each, no early exit when nothing matches): 1.0 s for the count plus 0.8 s for the page at 100,000 messages of the caller's own, linear after that, and the 5 s budget answers the typed 429 past it (`persistence.md` "Search (V16)"). A `btree_gin` index over `(thread_id, search)`, or a `room_id` on `messages` with a composite, would let an index serve it (V16 builds none: a GIN index on `messages.search` was dropped from it because no statement read it); the measurement did not demand one for 0.5.0. The same scan makes a room with hundreds of thousands of messages slow for an absent word. There is no per-user room cap either: `memberRooms` is unbounded, and a hostile user in many rooms of 8,000-character messages is the abuse scenario in `security.md` "Search" (bounded by the deadline, the bucket and the concurrency cap, not by size).
- [new] **Find: an index on `threads.fields`** (GIN, `jsonb_path_ops`) if a room passes a few thousand threads. 0.5.0 filters by scanning one room (0.5 A9).
- [new] **Live: a second replica.** The ticket store and the hub are in memory, per process (`live.md`), so tickets work on one replica only. Two replicas need
  sticky routing of `/api/v1/live*` (the ticket POST and the socket on the same pod) or a shared ticket table; every replica's LISTEN connection already
  hears every hint.
- [new] **Live: push account changes.** 0.4.0 closes a socket for a logout, user deletion, password change or role change by a 60 s sweep (the user's decision D2).
  Pushing those four events (a NOTIFY the hub acts on at once, about five call sites in `auth/` and `users/`) would close the gap to zero; the sweep stays as the
  safety net.
- [new] **Live: skip the writer tab's echo.** The tab that made a write refetches twice, once for its own mutation and once for the hint. Tag the hint with the
  writer's socket (or a client id) so that tab can skip it.
- [new] **E2E: the live-ticket bucket is shared across a full parallel run.** The seed admin's `live-ticket` bucket (60/min per user) is shared by every Playwright
  worker that signs in as it, so live specs use throwaway users; a new live spec must do the same.
- [new] **Conversation: arrivals.** With live updates a reader scrolled up gets no sign that messages arrived. Add a "New messages below" affordance and a
  screen-reader announcement; do it with the 0.6.0 unread markers.

## Foundation follow-ups

- [new] **Sign-out fires a stray 401 refetch.** `bindSessionQueryCache` clears the query cache while the old page is still mounted, so the rail (`useRailRooms`) and room header (`RoomPage`) queries refetch once without a token. Harmless console noise; gate those queries on a live session or unmount before clearing.
- [new] **Scrub a leaked secret from a thread's history (0.3.0 audit M4).** `thread_events` is append-only: a credential pasted into a title or a TEXT/URL field survives an edit as `TITLE_CHANGED.from`/`FIELD_CHANGED.from`, served to every current and future reader, and only SQL removes it. The moderator message delete (D2) exists for exactly this leak. Options: an audited per-event redaction of free-text params by a steward or ADMIN (`thread.history_redacted`), or recording title and TEXT/URL changes without their raw values. A product call.
- [new] **`RowLockVersusDeriveTest` (0.3.0 audit).** `persistence.md` promised it back "with the first long chat transaction"; the workflow PUT's `moveThreads` under the room lock is one. Port Flow's test shape.
- [new] **The emoji picker under the production CSP (0.3.0 audit).** No e2e opens the picker, and no spec listens for `securitypolicyviolation`; add both. `@emoji-mart/react` 1.1.1 also declares React ≤18 as a peer (installs only with `--legacy-peer-deps`); consider inlining its ~20-line wrapper.
- [new] **Room restore.** Soft delete (ADMIN `DELETE /rooms/{id}`) has no restore API; an ADMIN cannot bring a deleted room back except through the database. Decide the shape (a restore route, or a trash list) and the name-reuse rule.
- [new] **History interleaved in the conversation.** 0.3 shows a thread's history in the side panel only; consider interleaving the events with the messages in one timeline.
- [new] **The message composer nests a `<div>` in a `<p>`.** React warns twice in the console when a thread pane mounts ("cannot be a descendant of <p>"): `MessageComposer`'s Mantine `Textarea` `description` renders block content inside its `<p>`. Seen while verifying 0.5.0 W1; pre-existing.
- [new] **The MFA email failure line logs the address and the full throwable.** `auth/MfaEmail.kt:98` (`log.error("MFA code email delivery failed for ${user.email}", e)`) breaks the `errorType`-only rule that `PasswordResetEmail` and the audit line follow. Found during 0.5.0 S0.
- [new] **Message edit history.** An edit overwrites the body and sets `edited_at`; no versions are kept. Decide whether to keep them (who may see them, and how a moderator delete scrubs them).
- [new] **Thread delete or merge.** Threads are never deleted in 0.3 (close one as *Won't do*); decide whether an owner or ADMIN may delete a thread, or merge a duplicate into another.
- [new] **Relax the same-category replacement rule.** Retiring a status in use requires a replacement in the SAME category (so a thread never silently opens or closes). Consider allowing a cross-category move with an explicit confirmation now that threads exist.

- [new] **Room squatting: a creator column, a per-creator cap and an ADMIN clean-up path.** A room has no creator record (V13 is committed), so an ADMIN
  can only find the creator of a squatted name through the `room.created` audit line. Add a creator (a later migration), a cap on the active rooms one
  user may create, an owner/creator filter on `GET /rooms` for ADMINs and an ADMIN reassign/delete path; the `room-name` bucket only slows it (`security.md`).

- [new] **Bots posting into rooms.** Port Flow's `integration/` service tokens when an external system should post or update threads.
- [new] **CI's e2e `npm audit` step is red on GHSA-fc5q-39xc-vjpf.** `picomatch` <= 4.0.7, reached through knip; no patched version exists yet. Per the
  user's choice of 2026-11-17 the step stays red until a fix ships and Dependabot picks it up (no ignore, no pin).
- [new] **Refuse access tokens without `exp` in `accessPrincipal`.** The JWT verifier accepts them on every route except the live ticket mint; only a
  holder of the signing secret can mint one.
