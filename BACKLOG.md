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

## Chat MVP (0.4–0.6)

- [new] **0.4.0 Live updates.** The WebSocket channel (`live.md`).
- [new] **0.5.0 Find.** Filters, sorts, full-text search, hashtags.
- [new] **0.6.0 Mentions, reactions, pins, unread.**
- [new] **Live: a second replica.** The ticket store and the hub are in memory, per process (`live.md`), so tickets work on one replica only. Two replicas need
  sticky routing of `/api/v1/live*` (the ticket POST and the socket on the same pod) or a shared ticket table; every replica's LISTEN connection already
  hears every hint.
- [new] **Live: push account changes.** 0.4.0 closes a socket for a logout, user deletion, password change or role change by a 60 s sweep (the user's decision D2).
  Pushing those four events (a NOTIFY the hub acts on at once, about five call sites in `auth/` and `users/`) would close the gap to zero; the sweep stays as the
  safety net.
- [new] **Live: skip the writer tab's echo.** The tab that made a write refetches twice, once for its own mutation and once for the hint. Tag the hint with the
  writer's socket (or a client id) so that tab can skip it.
- [new] **Conversation: arrivals.** With live updates a reader scrolled up gets no sign that messages arrived. Add a "New messages below" affordance and a
  screen-reader announcement; do it with the 0.6.0 unread markers.

## Foundation follow-ups

- [new] **Sign-out fires a stray 401 refetch.** `bindSessionQueryCache` clears the query cache while the old page is still mounted, so the rail (`useRailRooms`) and room header (`RoomPage`) queries refetch once without a token. Harmless console noise; gate those queries on a live session or unmount before clearing.
- [new] **Scrub a leaked secret from a thread's history (0.3.0 audit M4).** `thread_events` is append-only: a credential pasted into a title or a TEXT/URL field survives an edit as `TITLE_CHANGED.from`/`FIELD_CHANGED.from`, served to every current and future reader, and only SQL removes it. The moderator message delete (D2) exists for exactly this leak. Options: an audited per-event redaction of free-text params by a steward or ADMIN (`thread.history_redacted`), or recording title and TEXT/URL changes without their raw values. A product call.
- [new] **`RowLockVersusDeriveTest` (0.3.0 audit).** `persistence.md` promised it back "with the first long chat transaction"; the workflow PUT's `moveThreads` under the room lock is one. Port Flow's test shape.
- [new] **The emoji picker under the production CSP (0.3.0 audit).** No e2e opens the picker, and no spec listens for `securitypolicyviolation`; add both. `@emoji-mart/react` 1.1.1 also declares React ≤18 as a peer (installs only with `--legacy-peer-deps`); consider inlining its ~20-line wrapper.
- [new] **Room restore.** Soft delete (ADMIN `DELETE /rooms/{id}`) has no restore API; an ADMIN cannot bring a deleted room back except through the database. Decide the shape (a restore route, or a trash list) and the name-reuse rule.
- [new] **History interleaved in the conversation.** 0.3 shows a thread's history in the side panel only; consider interleaving the events with the messages in one timeline.
- [new] **Message edit history.** An edit overwrites the body and sets `edited_at`; no versions are kept. Decide whether to keep them (who may see them, and how a moderator delete scrubs them).
- [new] **Thread delete or merge.** Threads are never deleted in 0.3 (close one as *Won't do*); decide whether an owner or ADMIN may delete a thread, or merge a duplicate into another.
- [new] **Relax the same-category replacement rule.** Retiring a status in use requires a replacement in the SAME category (so a thread never silently opens or closes). Consider allowing a cross-category move with an explicit confirmation now that threads exist.

- [new] **Room squatting: a creator column, a per-creator cap and an ADMIN clean-up path.** A room has no creator record (V13 is committed), so an ADMIN
  can only find the creator of a squatted name through the `room.created` audit line. Add a creator (a later migration), a cap on the active rooms one
  user may create, an owner/creator filter on `GET /rooms` for ADMINs and an ADMIN reassign/delete path; the `room-name` bucket only slows it (`security.md`).

- [new] **Raise the coverage floors back.** The trim re-baselined them to lines 97 / branches 81 (Flow had 98 / 85 with its cut features' tests);
  raise them as the chat features add tests.
- [new] **Bots posting into rooms.** Port Flow's `integration/` service tokens when an external system should post or update threads.
- [new] **CI's e2e `npm audit` step is red on GHSA-fc5q-39xc-vjpf.** `picomatch` <= 4.0.7, reached through knip; no patched version exists yet. Per the
  user's choice of 2026-11-17 the step stays red until a fix ships and Dependabot picks it up (no ignore, no pin).
