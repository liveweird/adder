# Product backlog

Updated 2026-11-27. This file tracks **outstanding work only**. Implemented behaviour and release history belong elsewhere:
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

- [new] **Messages: the list's `total` counts the whole thread.** `MessageService.list` runs `count(*)` over `idx_messages_thread` for every page and every live re-read of an open thread (index-only, linear in the thread: about 5 ms at 150,000 messages, unbucketed and outside any deadline; 1.2 branch audit). Bound it like the hashtag probe (the 1.2 fix) by dropping the exact count for the keyset read (`id[lt]` needs only "older exist", a `limit + 1` probe) or by answering `threads.message_count` for the unfiltered page; both change the `total` the SPA reads, so it needs a decision first. The timeline also runs the quote read on each page. Manually loaded older pages are re-read on live hints. Measure whether to cap those pages or share a bounded read pool. The 1.10.0 context endpoint is already bounded.
- [new] **Reactions: the official emoji list.** The server checks that a value is one emoji grapheme by rule, not by list, so a chain of up to five pictographs joined by a zero-width joiner passes (`chat-model.md` "Reactions (V20)").
- [new] **Unread: read state pushed between a person's tabs.** 1.2.0 tells the other tabs of one browser when NOTICES were seen (`BroadcastChannel`, the bell count only); the rest (the rail's unread dot, a thread's unread count, the read floor) still catches up on focus, and another DEVICE needs a hint.
- [new] **Find: range and text filters on field values.** 0.5.0 filters SELECT, MULTI_SELECT and USER fields only (exact, any-of; 0.5 D4). Add DATE and NUMBER ranges (`field[gte]`, `field[lte]`) with their inputs, and TEXT filters.
- [new] **Find: facet counts in the filter panel.** 0.5.0 labels only the status options with the workflow's `threadCount`. Counts per owner, tag and field value would port Covenant's lifted-predicate facets (`ContractService.kt`).
- [new] **Find: search over field values, and Markdown stripped from snippets.** 0.5.0 searches message bodies and thread titles; a snippet is a fragment of the Markdown source as plain text, so from 0.6.0 a hit near a mention shows the raw `<@id>` token; show the member's name instead.
- [new] **Find: ticket keys.** `PROD-123` indexes as `prod` and `-123`, so `123` alone does not find it. Index the key whole, or add a second configuration for it.
- [todo] **Live: reuse the roster refresh after changing another member’s role.** The writer currently reads the roster, room detail and rail twice. Preserve immediate clearing of edit history when roles change.
- [todo] **Live: reuse the roster refresh after approving a join request.** The writer currently reads the roster, room detail and request queue twice. The applicant should still gain access before the approval response arrives.
- [todo] **Live: verify which public-join refreshes can be narrowed.** The joining tab repeatedly reads the rail and room detail. Preserve the read that catches up after the room subscription and test the change against a timed browser trace.
- [blocked] **Account reactivation receipt.** Adder now sends password-change email. A reactivation receipt depends on an account reactivation workflow. Adder currently supports soft deletion and has no reactivation transition.
- [new] **Tags: a per-room subset of the registry.** The registry is global (1.2 D1 to 1.2 D3, 1.2 A19): every room offers every live tag. A room could pick the categories or tags it uses, which would also let the picker stay short in a big registry.
- [new] **Tags: facet counts in the filter panel.** The `threadTag` filter lists the registry without counts (1.2 A19); counts per tag in the room (and across rooms) belong with the Covenant facet pattern in "Find: facet counts" above.
- [new] **Pins: a pinned-first order.** A pinned thread is marked in its row (1.2 D5) but sorts like any other; add a `pinned` sort or a pinned-first default, and decide how it meets the user's own sort.
- [new] **Quotes: the replies to a message.** A count ("3 replies") and a list of the messages that quote one, over `idx_messages_quoted` (V35); the count would ride the message list like `reactions`.
- [new] **Quotes: a selection of the text.** A quote is the whole message (the block shows a one-line excerpt); quoting a highlighted passage needs the passage on the wire and a rule for when the original is edited.
- [new] **Quotes: a keyboard shortcut for quoting.** 1.3.0 ships the kebab-menu item and the one-click icon on every message (1.3 D3); a shortcut (focus a message, press a key) is not built.
- [new] **Quotes: add, change or drop the quote in an edit.** The edit's `MessageRequest` keeps its shape (`chat-model.md` "Quotes (1.3)"): the quote is set when the message is posted and never changes.
- [new] **Quotes: what a pinned message and a search hit quote.** A pinned message (the pin card and the pin strip) and a search hit show the message itself and say nothing of the message it quotes.

## Foundation follow-ups

- [parked] **A test flake: `IllegalAccessError` at `LiveSweep.kt:71`.** Seen once during a 0.6.0 `koverXmlReport` run (`UserRoutesTest` "admin creates a user who can log in with the supplied password"), green on rerun: likely the live sweep's coroutine outliving `testApplication`'s class loader. The likely cause is fixed (the live scope is now joined on `ApplicationStopping`, before the pool is disposed; `infra/BackgroundScope.kt`); remove this item after a week of green server coverage runs without it.
- [new] **Room restore.** Soft delete (ADMIN `DELETE /rooms/{id}`) has no restore API; an ADMIN cannot bring a deleted room back except through the database. Decide the shape (a restore route, or a trash list) and the name-reuse rule.
- [new] **Relax the same-category replacement rule.** Retiring a status in use requires a replacement in the SAME category (so a thread never silently opens or closes). Consider allowing a cross-category move with an explicit confirmation now that threads exist.

- [new] **Room squatting: a creator column, a per-creator cap and an ADMIN clean-up path.** A room has no creator record (V13 is committed), so an ADMIN
  can only find the creator of a squatted name through the `room.created` audit line. Add a creator (a later migration), a cap on the active rooms one
  user may create, an owner/creator filter on `GET /rooms` for ADMINs and an ADMIN reassign/delete path; the `room-name` bucket only slows it (`security.md`).

- [new] **Bots posting into rooms.** Port Flow's `integration/` service tokens when an external system should post or update threads.
