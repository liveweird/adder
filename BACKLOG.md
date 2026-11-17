# Product backlog

Updated 2026-11-17. This file tracks **outstanding work only**. Implemented behaviour and release history belong elsewhere:
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

- [new] **0.2.0 Rooms and workflows.** Workflow templates, rooms with members, the room workflow editor (`product.md` "Roadmap").
- [new] **0.3.0 Threads and messages.** Threads with status, owner, fields and history; messages; close/reopen.
- [new] **0.4.0 Live updates.** The WebSocket channel (`live.md`).
- [new] **0.5.0 Find.** Filters, sorts, full-text search, hashtags.
- [new] **0.6.0 Mentions, reactions, pins, unread.**

## Foundation follow-ups

- [new] **Raise the coverage floors back.** The trim re-baselined them to lines 97 / branches 81 (Flow had 98 / 85 with its cut features' tests);
  raise them as the chat features add tests.
- [new] **Bots posting into rooms.** Port Flow's `integration/` service tokens when an external system should post or update threads.
- [new] **CI's e2e `npm audit` step is red on GHSA-fc5q-39xc-vjpf.** `picomatch` <= 4.0.7, reached through knip; no patched version exists yet. Per the
  user's choice of 2026-11-17 the step stays red until a fix ships and Dependabot picks it up (no ignore, no pin).
