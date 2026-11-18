# Product backlog

Updated 2026-11-18. This file tracks **outstanding work only**. Implemented behaviour and release history belong elsewhere:
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

## Chat MVP (0.2–0.6)

- [new] **0.3.0 Threads and messages.** Threads with status, owner, fields and history; messages; close/reopen.
- [new] **0.4.0 Live updates.** The WebSocket channel (`live.md`).
- [new] **0.5.0 Find.** Filters, sorts, full-text search, hashtags.
- [new] **0.6.0 Mentions, reactions, pins, unread.**

## Foundation follow-ups

- [new] **Room restore.** Soft delete (ADMIN `DELETE /rooms/{id}`) has no restore API; an ADMIN cannot bring a deleted room back except through the database. Decide the shape (a restore route, or a trash list) and the name-reuse rule.
- [new] **What a removed option means for stored values (decided in 0.3).** A room's select field can retire an option, but until threads store field values there is nothing to migrate; 0.3 decides whether existing values keep the retired option (shown as "removed"), are cleared, or block the retire.
- [new] **Relax the same-category replacement rule.** Retiring a status in use requires a replacement in the SAME category (so a thread never silently opens or closes). Consider allowing a cross-category move with an explicit confirmation once threads exist.
- [new] **The retire-dialog e2e (0.3).** `RetireStatusModal` and the `409 status-in-use` flow are unit-tested only, because no thread can use a status in 0.2.0; add the Playwright scenario with threads in 0.3.

- [new] **Room squatting: a creator column, a per-creator cap and an ADMIN clean-up path.** A room has no creator record (V13 is committed), so an ADMIN
  can only find the creator of a squatted name through the `room.created` audit line. Add a creator (a later migration), a cap on the active rooms one
  user may create, an owner/creator filter on `GET /rooms` for ADMINs and an ADMIN reassign/delete path; the `room-name` bucket only slows it (`security.md`).

- [new] **Raise the coverage floors back.** The trim re-baselined them to lines 97 / branches 81 (Flow had 98 / 85 with its cut features' tests);
  raise them as the chat features add tests.
- [new] **Bots posting into rooms.** Port Flow's `integration/` service tokens when an external system should post or update threads.
- [new] **CI's e2e `npm audit` step is red on GHSA-fc5q-39xc-vjpf.** `picomatch` <= 4.0.7, reached through knip; no patched version exists yet. Per the
  user's choice of 2026-11-17 the step stays red until a fix ships and Dependabot picks it up (no ignore, no pin).
