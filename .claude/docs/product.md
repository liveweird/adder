# Product, roadmap and donors

Owns: what Adder is, the scope of every version, the decisions the user took, the defaults taken without asking, what each donor gave, and the
release history (newest first). Not here: the binding one-liners (the current version, "Port, don't reinvent") are in `CLAUDE.md`; the domain
sketch is `chat-model.md`; the live channel is `live.md`; how an area works now is in its own doc.

Read on demand when you need the product intent, the scope of a version, a decision the user already took or what a donor contributed. A decision
recorded here is the user's unless it is filed under "Assumptions" (taken without asking, change on request).

## What Adder is

Slack crossed with Jira, for **short-lived, intensive, asynchronous** collaboration of a few dozen people — the reference case is 30+ people
working a complex, multi-threaded incident who must avoid chaos.

1. It looks like a chat tool: people work in **rooms**.
2. In a room all communication is organised in **threads**: you either create a thread or reply in one.
3. **Each thread has a workflow**: a status (e.g. in progress, closed) and further metadata; statuses and metadata fields are configurable per
   room, with defaults.
4. Room content can be **filtered, sorted and searched** (limited to a thread, by keyword, …).
5. The standard chat functions apply: pinning, @mentions, reactions, emoji, searchable #hashtags.
6. A thread has an **owner** (like a Jira assignee): initially its creator; ownership can be passed.
7. A thread can be **closed**, which stops any activity within it.

The name is the snake: quick, quiet, decisive. **Brand:** olive / venom chartreuse (`adder` tuple, light primary `#586715`, dark `#465213`,
dark-scheme accent ink `#b2cc3c`) — the centre of the largest free gap in the sibling hue wheel (Toadie amber 26°, Lettuce green 142°, Astrolabe
cyan 189°, Flow blue 209°, Covenant violet 264°, Astrid red 350°). The logo "Strike" shows a front-facing adder on a disc. Success is
**teal** (never green, so it cannot read as the accent); red = blocking, orange = warning, gray = neutral, blue = in progress, grape = waiting.

## Decisions (the user's, 2026-11-17)

- **Scope of the first plan:** the foundation plus the chat MVP, 0.1 to 0.6; each increment gets its own detailed plan before it is built.
- **Room access:** members only. A room has members; only members read and post; the creator or an ADMIN adds people.
- **Closing:** a closed thread can be reopened by its owner, the room owner or an ADMIN; the reopen is recorded in the thread history.
- **Live updates:** WebSockets (not SSE, not polling).
- **Foundation:** Flow, Toadie and Lettuce as the base for stack, conventions, UI approach, app and API design — re-use as much as possible; the UI
  stays coherent with Lettuce, Toadie, Covenant, Flow, Astrolabe and Astrid; a new accent colour; an icon referring to the name.

### 0.2.0 rooms and workflows (the user's, 2026-11-17)

- **Any signed-in user creates a room** and becomes its OWNER. ADMINs manage and read every room; non-members get `404`.
- **Workflow templates are ADMIN-only** (edited by ADMIN, read by anyone). A room copies statuses and fields at creation; afterwards the OWNER or an
  ADMIN edits the room's copy **in place only**, with no re-apply from the template.
- **Tens of rooms:** a flat rail sorted by recent activity, archived rooms behind a toggle, no favourites or grouping.
- Retiring a status that threads use needs a replacement mapping; the contract is designed in 0.2 and 0.3 plugs in. Members are a person, or a whole
  team as a snapshot; the last OWNER cannot leave or be demoted (`409`). Archive and unarchive by OWNER or ADMIN; soft delete by ADMIN only. The
  workflow is a whole-document PUT under `X-Expected-Revision`; keys are immutable slugs; *Default* and *Incident* are seeded by migration.
- **D1. Team rosters** are edited by an ADMIN or the team's active manager (the first consumer of `requireTeamManagerOrAdmin`). The team record
  itself stays ADMIN-only.
- **D2. The user directory:** any signed-in user may look up `{id, name, email}` of active users, behind a per-user rate bucket. This changes a
  deliberate posture (the user list stays ADMIN-only); roles, feature flags and language are never exposed.
- **D3. The seed labels** are the ones in `chat-model.md` "Workflows" (Default and Incident, EN and PL), exactly as proposed. An applied migration's
  bytes are immutable, so afterwards the labels change only through the template editor.

### 0.4.0 live updates (the user's, 2026-11-19)

The architect's plan put three decisions to the user; all three took the recommended option. The rules they became: `live.md`.

- **D1. The ticket travels in the first frame** (`{"type":"auth","ticket":…}` within 2 s of the upgrade; 5 s until the 0.4.0 audit), not in `?ticket=` as the 2026-11-17 text said.
  This honours API-AUTH-002 (no tokens in a URL) and keeps the credential out of proxy logs; the cost is that the WebSocket GET is public at the HTTP layer
  (an unauthenticated socket lives at most 2 s, under a pending cap in total and per address and a per-IP bucket). The alternative was `?ticket=` with a written exception to a MUST.
- **D2. Account changes close a socket by a 60 s sweep, plus a close at the access token's `exp`.** Logout, user deletion, password change and role change
  are caught within a minute with no change to any `auth/` or `users/` write path; membership removal and room deletion are pushed at once. The alternative was
  pushing those four account events too (five more call sites and one more reviewed surface), now a `BACKLOG.md` item.
- **D3. One socket per tab, at most 16 per user, closed after 5 minutes hidden.** The 17th tab shows "Live updates off" and retries every minute. The
  alternative was one socket per browser through a leader tab (Web Locks plus BroadcastChannel): no cap pressure, but a second state machine and much harder
  tests; unread needs no background socket (a hidden tab re-reads on return). *Changed by 1.2 D9: after 5 minutes hidden the tab goes quiet (personal frames only) instead of closing, and closes after 8 hours.*

### 0.5.0 Find (the user's, 2026-11-19)

The plan put five decisions to the user; all five took the recommended option, so the plan's defaults are decisions, not assumptions. The rules they became: `chat-model.md` "Find (0.5)".

- **D1. "Mentions me" moves to 0.6.0.** Nothing exists to filter on: V14 stores no mention, and `<@id>` tokens only exist once the 0.6.0 composer writes them. The alternative was a stopgap preset that
  searches for the caller's display name (wrong for namesakes and renames, thrown away in 0.6.0). The roadmap row of 0.5.0 loses "mentions me"; 0.6.0 gains it.
- **D2. Global search covers member rooms only, for everyone, an ADMIN included.** An ADMIN still searches any single room, because they read every room. The alternative was an ADMIN opt-in for every
  room, which turns "may read any room" into "may grep every conversation at once" and would want an audit line per search.
- **D3. A hashtag tags the message.** A thread has a tag while one of its live messages has it; editing or deleting that message removes it; no tag management screen; `#Wdrożenie` and `#wdrozenie` are one tag.
  The alternative was a sticky thread tag (a `thread_tags` table, a remove action and history events).
- **D4. SELECT, MULTI_SELECT and USER field filters, exact match, any-of.** One SQL primitive (`fields @>`), no new index. The alternative added DATE and NUMBER ranges (`field[gte]`, `field[lte]`) with their
  inputs, about one more server step and two more input kinds; they are a `BACKLOG.md` item.
- **D5. The "thread only" focus view is a layout switch kept in the URL.** The list column hides and the conversation takes the main area; it survives a reload and can be shared. The alternatives were the
  switch plus search within the thread, or deferring it (the first is a `BACKLOG.md` item).

### 0.6.0 Social (the user's, 2026-11-20)

The plan put six decisions to the user. The user was asked for numbers and gave none; the defaults offered were accepted, so each is a decision, not an assumption. The rules they became: `chat-model.md` "Mentions, notifications, unread, reactions, pins (0.6)".

- **0.6 D1. The rooms rail shows a dot plus a count of unseen mentions.** A dot for activity since the member last looked at the room, plus the number of unseen mentions and hand-overs; one flag on the rail's own row, no extra request. The alternatives were an exact count of unread messages per room (two counters kept on every post and read, a drift risk and a large number in a busy room) and mentions only.
- **0.6 D2. Notification emails are a 5-minute digest, on by default.** A notice still unseen after 5 minutes is mailed; at most one email per person per 15 minutes; each person switches each kind off. *Reversed by 1.1 D7: email is off by default for every kind.* The alternatives were the same digest off by default, one email per notification at once, and no email in 0.6.0.
- **0.6 D3. The email says who, the room, the thread number and a link only.** Never the thread title, never message text. The alternatives were a count and a link only, and the title plus the first 200 characters of the message.
- **0.6 D4. Any member pins and unpins.** Fifty pins a room; every pin says who made it; each change is audited. The alternatives were the room OWNER or an ADMIN only, and any member pins while only the pinner, the room OWNER or an ADMIN unpins.
- **0.6 D5. One release, 0.6.0,** as the roadmap says. The alternative was 0.6.0 = mentions, notifications and unread and 0.7.0 = reactions and pins, before the 1.0.0 checkup.
- **0.6 D6. Account changes stay on the 60 s sweep.** The sweep the user chose for 0.4.0 (0.4 D2) is kept; pushing them over the per-user channel needs a hint without a room (a wire change) and five call sites in `auth/` and `users/`. The `BACKLOG.md` item stays, noting that the delivery class now exists.

### 1.0.0 checkup (the user's, 2026-11-20)

- **1.0 D1. The history keeps no raw TEXT or URL values and no title.** A `FIELD_CHANGED` of a TEXT or URL field records who, when and which field, not the text; SELECT, MULTI_SELECT, USER, DATE and NUMBER keep their from/to. The thread's titles (`TITLE_CHANGED.from`/`to`, and the opening title in `CREATED`) are treated like TEXT: the history is append-only, so a credential pasted into a title or a field could never be taken back, and this closes the leak and most of the history's growth at the source. Rows already written are scrubbed by migration V23. Taken from the checkup's C-SEC-5 and the old "scrub a leaked secret" item (the 0.3.0 audit's M4); the alternatives were an audited per-event redaction by a steward and a cap on events per thread. A retired field's remaining values are hidden at read time (C-SEC-6). The rules: `chat-model.md` "History", `persistence.md` "History scrub (V23)".

### 1.1.0 Watching (the user's, 2026-11-21)

Watching, group mentions and per-kind notices. The user took eight decisions (D1 to D8) and then confirmed the three open questions of the plan as the recommended options (D9 to D11) and amended D7. The rules they became: `chat-model.md` "Watching, group mentions and notice kinds (1.1)".

- **1.1 D1. Any member watches or unwatches any thread of their room, closed ones included.** Leaving the room drops the watches. The creator and each new owner watch automatically and may stop. A non-member ADMIN cannot watch.
- **1.1 D2. A "threads I watch" filter** on the thread list and on both searches.
- **1.1 D3. Watchers hear replies, status changes (close and reopen included) and owner changes.** Not field edits, reactions or pins.
- **1.1 D4. Every room member gets a new-thread notice.** Status changes go to watchers only.
- **1.1 D5. Team handles (stored as an id token), a watchers mention and `@everyone`, usable by any member.**
- **1.1 D6. One notice per recipient per event, labelled with the most specific reason.** The actor never gets one.
- **1.1 D7. A grid of in-app and email switches per notice kind (Lettuce's model), no master switch. In-app is on by default for every kind; email is OFF by default for EVERY kind,** the existing `MENTIONED` and `THREAD_ASSIGNED` included. This reverses 0.6 A15 (in-app cannot be switched off) and 0.6 D2 (email on by default). As first decided, email stayed on for the mention kinds; the user amended it the same day. Account email (the MFA code, the password-reset link) is not a notice kind and always goes.
- **1.1 D8. The V24 email budget stays for group mentions** (20 distinct recipients per actor per UTC day, one pool): the in-app notice is made as usual (subject to the recipient's in-app switch: off means no row, or a row with `in_app = false` when email is on) and the budget withholds only the email.
- **1.1 D9 (plan Q1). A safety net on wide fan-out.** An event that would notify more than 20 people is a "wide event"; each actor gets 500 a UTC day (config). Over it the write still succeeds and only direct mentions and the hand-over notify, with one audit line per actor per day. This qualifies D4 and the in-app notice rule (made as usual, subject to the in-app switch) in the over-budget case only. The alternatives were the same budget refusing the write with a typed 429, and no bound beyond `chat-post` (about 86M rows a day per account in the worst case, against 3.4M today).
- **1.1 D10 (plan Q2). Replying or being mentioned does not make you a watcher.** Only the two triggers of D1; `thread_watches` stays a plain hard-delete join. The alternative was watching on reply with a sticky unwatch (a `state` column, about a day more); "auto-watch on reply" is a `BACKLOG.md` item.
- **1.1 D11 (plan Q3). Three status kinds:** `THREAD_STATUS_CHANGED`, `THREAD_CLOSED`, `THREAD_REOPENED`. It mirrors `thread_events`, lets a person keep "closed" on and "moved" off, and the sentence can say "closed" without an extra column. The alternative was one kind with a generic label.

### 1.2.0 Room view (the user's, 2026-11-21)

Room-view polish: pins, room-view space, names as links, tags, hashtags, the unread filter and the tab-title count. The plan put nine questions (Q1 to Q9) to the user, who took every recommendation as written ("Looks good"), the Kind/Area tag seed names included; 1.2 D1 to 1.2 D9 are those answers in order. The rules they became: `chat-model.md` "Room view, tags and unread (1.2)".

- **1.2 D1 (Q1). Who sets a thread's tags.** Any member of the room and an ADMIN while the thread is open (the title-and-fields rule); on a CLOSED thread only its stewards (the thread owner while a member, the room OWNER, an ADMIN); an archived room refuses (`409`). Tidying up after an incident then needs no reopen (a reopen plus a close would notify every watcher twice and reset the close time). The alternatives were closing freezes tags (`409 thread-closed`), stewards only, and any member always (the pin rule).
- **1.2 D2 (Q2). One global ADMIN-curated tag registry for every room** (the port of Hammurabi's `tags/`), seeded by migration with two small categories an ADMIN can rename, extend or delete: **Kind** (`question`, `decision`, `action`, `announcement`, `postmortem`) and **Area** (`customer`, `security`, `data`, `infrastructure`, `process`). The seed is immutable once released, hence the user's yes on the names. The alternatives were the registry with no seed or another list, and a registry per room curated by its OWNER (no shared vocabulary, not a port).
- **1.2 D3 (Q3). The names.** **Hashtags** (the `#word`s of message text, 0.5 D3) keep the wire name `tag` and `GET /rooms/{id}/tags`; **tags** are the curated labels. New: response fields `hashtags` and `tags` (`[{name, retired}]`); the filter `threadTag` (repeatable names, any-of) on the thread list AND on both searches, where, like `watching=true`, a tag alone is a valid search; `PUT /rooms/{id}/threads/{number}/tags`. The SPA's URL keeps `tag` for hashtags and adds `threadTag`; the UI says "Hashtags" and "Tags". The alternatives were the thread list only (search by tag to the backlog) and an id-based `tagId` filter (unreadable URLs).
- **1.2 D4 (Q4). The side panel is collapsed by default at every width** and a "Details" button opens it; the choice is remembered per person per browser, the same for every room and thread (`adder.viewSettings.threadPane.detailsOpen`). The alternatives were per room, nothing remembered, and the URL.
- **1.2 D5 (Q5). The pins of an open thread** show as a strip under the thread header, present only when the thread has pinned messages: one line "Pinned (2)" with the first line of the newest pin, expanding in place to the list (author, time, a two-line excerpt, "Show in thread", "Unpin"); expanded or not is remembered per person. The room header's "Pinned (n)" drawer stays for the whole room. The alternatives were always expanded (three rows and "+N more"), a sticky cycling banner, and a section of the side panel.
- **1.2 D6 (Q6). Names stand as links wherever a name stands on its own:** a message's author, a mention in a message, the owner and the starter in the thread header, the owner in the side panel, a USER field's value, the history's actor, "pinned by", the members and team tables, a search hit's owner and room. In the bell the actor and the room become links INSIDE the row and the rest of the row still opens the thread. In a thread-list row the owner stays text (two targets in a 20rem row invite mis-clicks; the same link is one click away in the header). The alternatives were the owner in list rows too, and the bell left as one link.
- **1.2 D7 (Q7). What a non-ADMIN sees on a profile:** name and email (what the directory already gives every signed-in user, 0.2 D2), the person's teams and the rooms the viewer shares with them; never the role, feature flags, language, MFA or sign-in times. A removed account and an unknown id are the same "not available" page. An ADMIN sees the same page with every room the person is in and an "Edit user" link. The alternatives were name and email only, and an ADMIN badge (which would reverse "roles are never exposed" of 0.2 D2).
- **1.2 D8 (Q8). The tab title counts the bell's number,** every unseen in-app notice: `(3) Incident 42 · Adder`. It follows each person's per-kind switches, so the tab never disagrees with the bell. The alternatives were only what is addressed to me, that number plus a dot, and unread threads or messages across rooms (a new cross-room count; 0.6 D1 decided against exact unread counts).
- **1.2 D9 (Q9). The hidden tab goes quiet instead of closing (changes 0.4 D3).** After 5 minutes hidden the tab leaves every room and keeps the socket for personal frames only (one small re-read of the bell, its count and its addressed list, per notice, at most one a minute); after 8 hours hidden it closes as before and the title drops its number, never showing a stale one. Tabs of one browser tell each other when notices were seen (`BroadcastChannel`, no server change), or a hidden tab would keep saying `(3)` after the notices were read elsewhere. The cost, accepted: a forgotten hidden tab stays signed in up to 8 h + 1 h instead of 5 min + 1 h (the 5-minute close was the only thing that let an open browser's session idle out) and holds one of the person's 16 sockets meanwhile. The alternatives were quiet with no limit, keeping the 5-minute close (the title loses its number after 5 minutes), and polling once a minute while hidden.

### 1.3.0 Quotes (the user's, 2026-11-22)

"Reply to" a message, as Slack and Teams have it: a member picks a message, writes a reply, and the reply shows whose message it answers and what it said; a click leads back to the message. The plan put three questions (Q1 to Q3) to the user, who took the recommendation on two and amended the third; 1.3 D1 to 1.3 D3 are those answers in order. The rules they became: `chat-model.md` "Quotes (1.3)".

- **1.3 D1 (Q1). The word is "quote", everywhere.** In Adder every message of a thread is already a "reply" (the box is labelled "Reply", the bell says "replied in #42", the kind is `THREAD_REPLIED`), so the new thing needs a word of its own. The menu item is "Quote" (PL "Cytuj"), the composer says "Quoting Anna Nowak", the bell says "Anna quoted your message in #42 Title"; on the wire and in the schema `quotedMessageId`, `quote`, `quoted_message_id` and `MESSAGE_QUOTED`. The changelog introduces it as "reply to a message by quoting it". The alternatives were "Reply" in the UI with "quote" underneath (Slack's and Teams's words, at the price of one word for two things) and "Reply" on the wire too (`THREAD_REPLIED` and `MESSAGE_REPLIED` for different things, for good).
- **1.3 D2 (Q2). The author of a quoted message is told.** A twelfth notice kind, `MESSAGE_QUOTED`: in-app on and email off like every kind, its own two switches on the preferences page under Mentions, counted as addressed to the person (the rail's number, the thread's `@` mark, the bell's "Mentions" view); never for quoting yourself. Why: a member who asks a question in someone else's thread does not watch it (1.1 D10), so without it nothing tells them it was answered. The cost is a persisted name that can never be renamed. The alternatives were no notice in 1.3.0 (the kind can be added later with no migration) and counting a quote as a direct mention (nobody could mute quotes apart from mentions).
- **1.3 D3 (Q3). Two ways to quote: the kebab item and a one-click icon.** "Quote" is the first item of a message's kebab menu AND an always-visible icon button beside the menu on every message a member may quote (the plan recommended the menu item alone). The one-click button is the commonest action in a dense column, and the user took the cost of one more control on each row. The alternative was the menu item alone, and a toolbar that appears on hover (Adder has no hover toolbar; it cannot be reached by keyboard or touch without a second mechanism). A keyboard shortcut stays out (`BACKLOG.md`).

### 1.4.0 Clearer workspace (the user's, 2026-11-22)

The user asked for a modern, clean and professional application with better clarity, screen-space use and visual appearance, beginning with chat, then approved continuing the same direction across the workspace. This is a presentation and navigation milestone with no API, persistence or domain-rule change.

- **1.4 D1. Chat is a focused workspace.** On larger screens the thread list and conversation take the available height and scroll independently while the compact composer stays pinned below the message timeline. Compact headers, clearer message hierarchy, quieter actions and structured Details keep the discussion primary. On narrow screens an open thread replaces the list and provides a clear return action.
- **1.4 D2. The same visual system extends beyond chat.** Home prioritises up to six recently active joined rooms with unread cues. All rooms uses a dense table where space permits and responsive cards where it does not. The application header, mobile navigation and command palette use the same spacing, typography, surface and interaction conventions.
- **1.4 D3. Responsive navigation remains accessible.** The mobile drawer moves focus into the navigation, returns it when closed and closes through its explicit action, Escape or the backdrop. Compact presentation does not remove accessible names, keyboard access or assistive text.

## Assumptions (taken without asking; change on request)

- ADMIN may read every room (the family's ADMIN-overrides posture); a non-member gets `404`, never `403`.
- Closing is a status category: moving a thread into a CLOSED-category status closes it; moving it back to an OPEN status reopens it.
- Any room member may move a thread between OPEN statuses; ownership is passed by the owner, the room owner or an ADMIN.
- Each room owns its own copy of its workflow, copied at creation from an ADMIN-curated template (seeded *Default* and *Incident*).
- Mentions are stored as `<@userId>` tokens and rendered with the current name (rename-safe).
- Flow's integration service tokens are not ported in 0.1.0: with nothing to serve they would be dead weight (backlog: bots posting into rooms).
- The coverage floors were re-baselined after the trim (lines 97, branches 81; Flow's 98/85 relied on tests of the cut features) raised to 98/85 for 0.4.0, 98/86 for 0.5.0, 98/87 for 0.6.0 and 99/88 after the 1.0.0 checkup follow-ups (actuals 99.19/88.28 on the merged tree).

### 0.2.0 rooms and workflows

Detail and the rules they became: `chat-model.md` "Rules".

- **Label language.** Template labels are a language map `{en, pl?}` (EN required); a room's copy is single-language, resolved at creation from the
  request's `language` (default `en`, EN fallback per label) and plain text afterwards. Template name and description are not localised.
- **A team add is a snapshot**, not a live link. Member add is idempotent and always adds as MEMBER; promotion is a separate audited step.
- **Keys are reserved for the life of a room:** removing a status or field retires it, re-adding revives it, a field's `type` is immutable per key.
- **A workflow edit never closes or reopens a thread behind its history:** a replacement status has the same category as the retired one, and a
  category is editable only while no thread uses the status. Both can be relaxed later. Fields and options need no mapping; what a removed
  option means for stored values was decided in 0.3 (`chat-model.md` "Workflows": stored values survive).
- **Room names are unique** among non-deleted rooms, case-insensitively (archived rooms keep theirs); a clash discloses only that the name exists
  (Slack's private-channel rule). No slug; URLs use the numeric id.
- **Archived means read-only** for name, description, workflow and (0.3) content; membership management stays allowed.
- Soft delete has no restore API in 0.2. A soft-deleted user's membership rows stay, inert; a room whose owners are all deleted is managed by ADMINs,
  and deleting a user is not blocked by room ownership.
- `last_activity_at` equals creation time in 0.2 (0.3 bumps it). The rail shows the caller's member rooms; an ADMIN gets every room on the Rooms page.
- **Limits:** 2-20 statuses, 0-20 fields, 1-50 options per select; key <= 40 characters, label <= 60; at most 200 status keys and 200 field keys per room
  (retired included); room name <= 80, descriptions <= 500, template name <= 100; 500 members per room, 200 user ids per add, 200 members per team.
- `X-Expected-Revision` is required (missing or malformed `400`, stale `409`); every successful PUT bumps the revision. Wire enums are UPPER_CASE.
- Deleting a template never touches rooms; there is no "last template" rule.
- Rate buckets (all per signed-in user): `room-name` 30/min shared by `POST /rooms` and `PUT /rooms/{id}`, `room-members` 60/min on `POST /rooms/{id}/members`, and `directory` 120/min on the user directory; everything else is
  guard-gated and bounded by the caps above. `lockingTransaction` is not ported (one row lock per room instead). *[changed] by 1.2: ported for the tag registry ONLY.*

### 0.4.0 live updates

Taken without asking, in the 0.4.0 plan. Detail and the rules they became: `live.md`.

- **A1. Single replica.** Tickets and the hub live in memory, per process; a second replica is a `BACKLOG.md` item (sticky `/api/v1/live*` or a ticket table).
- **A2. Hints carry a kind and ids only**, never a title, body, name or field value. Delivery is at-most-once with no replay; the refetch is the backfill and
  `resync` covers every gap.
- **A3. Subscriptions** are the active rail (up to 100 rooms, `RAIL_PAGE_SIZE`) plus the open room. The archived rail and All rooms keep refreshing on focus.
- **A4. "Idle close"** means four mechanisms: the protocol heartbeat (Ktor pings every 25 s, pong timeout 15 s), the 2 s auth deadline, the close at the
  token's `exp`, and the client closing a socket after 5 minutes hidden. A visible, authenticated socket is never closed just for being quiet. *Changed by 1.2 D9: the hidden-tab mechanism is a quiet tab at 5 minutes and a close at 8 hours.*
- **A5. The CSP is unchanged:** `connect-src 'self'` already matches the same-host socket; scheme sources would open sockets to any host.
- **A6. The channel is `adder_live`** and the NOTIFY payload format is `v: 1`.
- **A7. The writer's tab refetches twice** (its own mutation refresh plus the hint). Accepted; skipping the echo is a `BACKLOG.md` item.
- **A8. The numbers in `live.md` "Bounds" are defaults;** only the ones tests must shrink become config keys.
- **A9. The ticket POST answers 200, not 201** (an exchange, like login and refresh).
- **A10. Frames are typed in the OpenAPI spec as components** and generated into `schema.ts`; the Kotlin `LiveKind` enum is pinned equal to the spec enum and
  to the audit names.
- **A11. Each socket produces one OTel server span,** and the latency telemetry excludes the live route.
- **A12. Team, user and auth writes emit no hint.**
- **A13. An ADMIN may subscribe to any existing room;** a demoted ADMIN keeps those subscriptions until the next sweep (at most 60 s).

### 0.5.0 Find

Taken without asking, in the 0.5.0 plan. Detail and the rules they became: `chat-model.md` "Find (0.5)".

- **A1. Stored generated columns over a text search configuration**, not a trigger and an immutable `unaccent` wrapper (`unaccent()` is STABLE, so it cannot feed a generated column; the configuration can).
- **A2. Matching:** all terms required within one message or one title; terms of three or more characters are prefixes (`simple` does not stem); case and accents ignored; no operators, no ranking.
- **A3. Search covers message bodies and thread titles.** Not field values, not tombstones.
- **A4. Results keep the page envelope:** an item is a thread, with its three newest matching messages; at most 20 a page; no shape exception.
- **A5. A snippet is a list of typed segments** (`{text, hit}`), a fragment of the Markdown source as plain text; the wire never carries marked-up text.
- **A6. Archived rooms are searched and labelled.** Deleted rooms never.
- **A7. One per-user bucket `search`, 60 a minute,** for both search endpoints. A statement budget of 5 s; past it the answer is a typed `429` `search-timeout` (the `room-busy` precedent).
- **A8. No facets endpoint** (per-status counts already ride the workflow read). Tags get their own counted, paged list because their set is unbounded.
- **A9. The thread list stays unbucketed and `threads.fields` gets no index.** A filter scans one room.
- **A10. A well-formed but unknown status key, owner id or tag matches nothing.** An unknown, retired or unfilterable FIELD key is a `400`, because its type decides the predicate.
- **A11. Tag grammar:** the sketch's characters and length, plus a left boundary, at least one letter (`#42` is a thread number), code and links skipped, the first 20 per message. `#fff` is a tag; accepted.
- **A12. Messages written before 0.5.0 get their tags from a reindex at boot,** not from SQL (`messages.tags_version`).
- **A13. The URL is the list's state.** The last query per room is remembered and restored when the Threads view opens bare. The old per-room `scope` and `order` keys are dropped.
- **A14. No new live hint.** `message.edited` also refreshes the thread list. Search results are not live.
- **A15. No new dependency:** the hashtag plugin walks the Markdown tree by hand.
- **A16. Search is submitted, not as-you-type.** The command palette offers "Search for ..." actions.
- **A17. Jumping to a found message loads older pages until it appears,** at most 10 loads.
- **A18. Database failures log exception classes and the SQLSTATE only,** never a message (Exposed expands bound values into it).

### 0.6.0 Social

Taken without asking, in the 0.6.0 plan. Detail and the rules they became: `chat-model.md` "Mentions, notifications, unread, reactions, pins (0.6)".

- **0.6 A1. The mention token is `<@id>`,** stored in the body as sent; nothing rewrites a body.
- **0.6 A2. Only an ACTIVE member of the room,** at the moment the message is written or edited, is mentioned; any other id is plain text: no row, no notice, never a refusal.
- **0.6 A3. The first 20 distinct mentions of a message count;** the rest are ignored. *Extended by 1.1 (5 teams, each keyword once).*
- **0.6 A4. No `@room`, no `@here`, no mention of a non-member.** *Partly reversed by 1.1: `<@everyone>`, `<@watchers>` and `<@team:id>`; `@here` and a non-member stay out.*
- **0.6 A5. A mention of oneself is shown and never notified.**
- **0.6 A6. An edit notifies only the people it newly mentions,** and removes the notice of a mention it drops. *Extended by 1.1 A9.*
- **0.6 A7. Names are read at request time.** A mention of a member who left or was deleted keeps the name.
- **0.6 A8. The thread list's filter is `mentionedId`,** repeatable and any-of (the `ownerId` shape); the SPA offers "Mentions me" only. *Extended by 1.1: `mentionsMe`.*
- **0.6 A9. The composer shows `@Name`.** A mention is one unit there (deleting into it removes it whole) and travels as `<@id>`.
- **0.6 A10. Two notification kinds:** `MENTIONED` and `THREAD_ASSIGNED`. A steward who takes a thread themself gets none. No notice for being added to a room, for a reply or for a reaction. *Reversed by 1.1: eleven kinds.*
- **0.6 A11. A notice is ids only,** and is listed only while its recipient is an active member of an active room, an ADMIN too.
- **0.6 A12. Leaving or being removed deletes the leaver's notices and read markers of that room.** Rejoining starts clean.
- **0.6 A13. Notices are hard-deleted:** with their message, with the membership, and 90 days after they were made.
- **0.6 A14. No "mark unseen" and no per-row delete in the bell** (Lettuce has both).
- **0.6 A15. In-app notices cannot be switched off.** The preference is about email, per kind; no master switch (two kinds are two switches). *Reversed by 1.1 D7: an in-app switch per kind.*
- **0.6 A16. A notice is seen** when its row is clicked, when its thread is read past its message, or by "Mark all as seen".
- **0.6 A17. Read state is a member's.** An ADMIN who is not a member has none: counts are 0 and a marker write is 403.
- **0.6 A18. Unread starts at joining.** After the upgrade, everything already written is read for everybody.
- **0.6 A19. A count looks at the newest 100 messages after the marker;** 100 means "100 or more" and shows as "99+". A tombstone counts.
- **0.6 A20. Posting marks the poster's thread read up to their message.** Opening a thread at its end marks it read; the "New" line shows what was new on that visit.
- **0.6 A21. A status, owner, title or field change makes a room "new"** (it is activity) and never a thread "unread" (only messages do).
- **0.6 A22. Read state is not pushed and not audited.** Another tab or device catches up on focus and with the room's next hint. *Changed by 1.2 D9: the bell count only.*
- **0.6 A23. A reaction is one emoji by the rule in `chat-model.md`,** at most 10 code points; 20 distinct per message (the roadmap), 5 per person per message.
- **0.6 A24. A reaction is speech:** members only, refused in a closed thread and an archived room. It is not activity: no rail bump, no unread, no notice.
- **0.6 A25. A message shows each reaction's count,** whether the reader reacted, and the first three names. The full list of reactors is a `BACKLOG.md` item.
- **0.6 A26. A reaction's audit line carries ids only,** never the emoji.
- **0.6 A27. A pin is of a thread or of a message; 50 per room.** A closed thread can be pinned (a pin is the room's); an archived room refuses; a deleted message loses its pin; no history event.
- **0.6 A28. Reactions and pins ride `chat-write`** (they take the room lock); the read-state writes ride a new per-user bucket, `read-state`, 240 a minute.
- **0.6 A29. Email:** nothing older than a day is mailed; a failed send is audited and not retried; a switched-off kind is skipped silently.
- **0.6 A30. Search snippets show raw `<@id>` tokens for now** (a `BACKLOG.md` item).
- **0.6 A31. No new dependency,** server or web.

### 1.1.0 Watching

Taken without asking, in the 1.1.0 plan. Detail and the rules they became: `chat-model.md` "Watching, group mentions and notice kinds (1.1)".

- **1.1 A1. The term is "watch"** (PL "obserwuj"); the composer's list also matches the typed word `observers`, and "observe" appears nowhere else.
- **1.1 A2. Watching is the caller's own state:** no room lock, no live hint, its own `thread-watch` bucket (30 a minute). It is audited (`thread.watch_changed`) because it changes who a write notifies.
- **1.1 A3. Auto-watch is the creator and each new owner** (again on a later hand-over after an unwatch). Reply and mention do not watch (1.1 D10).
- **1.1 A4. Existing threads are backfilled:** V26 makes each thread's creator and current owner who are still members a watcher, with no notice.
- **1.1 A5. `watcherCount` is on the wire; the list of watchers is not** (`BACKLOG.md`).
- **1.1 A6. Recipients are active members at write time;** a deleted user's watches are inert.
- **1.1 A7. What counts as activity** is a reply, a status change and an owner change (1.1 D3); not title or field edits, message edits as such, deletes, reactions, pins or the workflow PUT's `moveThreads`.
- **1.1 A8. Eleven kinds, in-app on and email off for all** (1.1 D7, 1.1 D11); every kind has both switches and none is locked on (Lettuce's `lockedOn` is not ported). Defaults live in code beside the enum and are frozen with the kind name.
- **1.1 A9. The edit rule:** an edit recomputes who the new body addresses against the message's existing notices (a fresh unseen notice when the kind becomes more specific; relabel in place when less; fall back to the base kind, else delete).
- **1.1 A10. Team mentions reach the roster only,** those who are also active room members; the manager counts only when on the roster.
- **1.1 A11. The handle rules:** optional, 2 to 30 characters `^[a-z][a-z0-9]*(-[a-z0-9]+)*$`, unique among active teams, freed on delete, uppercase a 400, a reserved set a 400.
- **1.1 A12. Old bodies are not reindexed:** a literal `<@everyone>` written before 1.1.0 stays text.
- **1.1 A13. `mentionsMe`** matches a direct mention, a team the caller is on now, everyone, or the watchers while the caller watches now. `mentionedId` stays direct only.
- **1.1 A14. The team-mention notice does not name the team** (`BACKLOG.md`).
- **1.1 A15. Channels are judged when the notice is made;** switching a kind back on covers notices made from then on.
- **1.1 A16. Group mentions of `@here` and of a non-member stay out;** the token grammar is `<@id>`, `<@team:id>`, `<@watchers>`, `<@everyone>`.
- **1.1 A17. Defaults are frozen with the kind name;** the table stores deviations only.
- **1.1 A18. The email budget counts mailable recipients only,** one pool for every kind, no carve-out.
- **1.1 A19. The wide-event budget is 500 a UTC day** (1.1 D9), configurable.
- **1.1 A20. Live: per-recipient targets,** one hint per event, at most 500 ids (499 when a member acts).
- **1.1 A21. The rail count and the `@` mark stay "addressed to me":** the four mention kinds plus `THREAD_ASSIGNED` (0.6 D1 stands); activity notices fill the bell only.
- **1.1 A22. `q` is optional only with `watching=true`** *[changed] by 1.2 D3: or a `threadTag`*; search has no `mentionsMe`.
- **1.1 A23. No confirm before `@everyone`;** a non-blocking line shows the member count.
- **1.1 A24. The digest text stays who, room, thread number and a link** (0.6 D3).
- **1.1 A25. Retention stays 90 days with no per-recipient cap.**
- **1.1 A26. No new dependency.**
- **1.1 A27. Search snippets still show raw tokens.**
- **1.1 A28. A 1.0.0 user who never touched their preferences stops getting the digest after the upgrade** (email is off by default, 1.1 D7). Their stored V22 "switched off" rows keep meaning off, and nobody has an opt-in row yet. The upgrade does not re-enable anything; the changelog entry says so. **An accepted exception to API-VER-002** (additive changes inside `/api/v1`): the meaning of the unchanged `GET`/`PUT /users/{id}/notification-preferences` body flips, so a 1.0.0 client's `{"disabled": []}` ("everything on") now leaves every email off; the spec says "Changed in 1.1.0" on both operations.
- **1.1 A29. Account email is not a notice kind and always goes:** the MFA code and the password-reset link are sent directly, never through the digest or the preferences. Adder sends no "password changed" or "account created" email; porting Lettuce's locked-on kinds for them is not in 1.1.0 (`BACKLOG.md`).
- **1.1 A30. The team PUT is a full replace, so an omitted `handle` clears it** (the `managerId` rule). A pre-1.1 client (an old SPA tab after the deploy, or a 1.0.0 script) that renames a team therefore clears its handle, and the `<@team:id>` tokens of that team then render as plain text and stop notifying. Accepted, **like A28 an exception to API-VER-002** (the meaning of the unchanged team PUT changes for a pre-1.1 client; the spec says so); traced by the `team.updated` audit line with `handle` null.
- **1.1 A31. The preferences PUT is a full replace, so a stale client resets what it does not know.** A pre-1.1 client (an old SPA tab after the deploy, or a 1.0.0 script) sends `disabled` only, so a save from it resets every in-app switch and every email opt-in the person set under 1.1 to the defaults (the app on, email off). Accepted, like A30; traced by `user.notification_preferences_changed` (`from`/`to` as `TYPE:CHANNEL=on|off`), and the spec says so.
- **1.1 A32. A steward can re-subscribe a member who unwatched a thread** by handing the thread to them (the new owner watches the thread they receive, 1.1 D1, again on every later hand-over) and back, or by taking it themself. Accepted (the S3 security audit): the steward is the room's owner, the thread's owner or an ADMIN; it is bounded by the `chat-write` bucket ONLY (the auto-watch and `THREAD_ASSIGNED` are direct, outside the wide-event budget, which limits just the `THREAD_OWNER_CHANGED` fan-out) and traced by `thread.owner_changed`; the member can stop watching again at once.
- **1.1 A33. A new notice is never made for the thread the reader is looking at: the pane acknowledges it** (S6, plan section "No badge flicker"). While a conversation is open, visible and scrolled to its end, a `notification.created` hint about that thread does not re-read the bell; the pane sends the read marker instead (forced once more even when the newest id was already sent, because a hand-over or status notice has no message and an edited older message's notice is no newer message), and the server marks the notice seen. A failed marker leaves the notice unseen on the server and the bell catches up at its next re-read.
- **1.1 A34. An edit tells only the NEWLY addressed, makes no base notice, and re-judges a relabel.** "Newly addressed" is addressed by the new body and not by the old one. Editing the opening message never tells a member who joined after it was written, editing a reply never tells a watcher who started watching after, and a mention the edit KEPT whose notice is gone (retention, a switch, a leave and rejoin) stays without one. A mention that falls back to the thread's base kind keeps its row and seen state and the person's channels are judged again for it (neither shown nor mailable deletes the row; a row already mailed or settled cannot be revived by email).
- **1.1 A35. The wide-event budget counts audiences before the channels are judged.** An event is wide when more than 20 people are in its audience, whoever has muted what; the cost it bounds (the audience reads and the insert) is paid for them all.
- **1.1 A36. When the most specific reason is fully off, the next applicable reason decides.** A person who switched `MENTIONED` off on both channels but watches the thread is told of the reply (`THREAD_REPLIED`); on an opening message, `THREAD_CREATED`; a new owner who muted `THREAD_ASSIGNED` hears `THREAD_OWNER_CHANGED`. Only when every applicable reason is off on both channels is there no notice. (A mention is not a way round a mute of the reply; a mute of the mention does not hide the message from a watcher.)
- **1.1 A37. A repeated thread-level notice replaces the person's unseen row of the same kind and thread** (status moves, close, reopen, owner change, hand-over): the old row is deleted and a NEW one inserted in one statement (a new id: it tops the bell and a `seen-all` already running cannot swallow it), with the actor, the person's CURRENT in-app verdict and the old row's `emailed_at` (not counted for the email budget again, a mail already sent not repeated). Only a row that can still deliver is replaced (shown in the app, or its mail still to come); a dead row is left and the event makes a fresh one. A row still WAITING for the digest keeps the instant of the FIRST event (the digest is due `notifications.email.delaySeconds` after it, so a churning thread's mail is never postponed), and one already past the delay is not replaced at all. Replies and mentions stay one notice per message. Once the row is seen, the next event makes a new one. **Two effects, accepted (the 1.1.0 branch audit, L5):** a SETTLED unseen row (shown in the app, its mail withheld or already sent) absorbs the later moves, so while it stays unseen it is replaced in place and nothing more is mailed for that thread and kind until the person sees it; and a row still WAITING for the digest that a SECOND actor refreshes is mailed naming that second actor (the row's actor is replaced), still as one mail and never an extra one.
- **1.1 A38. Wide events on an edit (counted before the channels, 1.1 A35).** An edit is ONE event: it is wide when the people it ADDS (the actor left out, before the channels are judged and before an unchanged upgrade is dropped) or the people whose bell its relabels change are more than 20. Over the budget the write still succeeds, only the people named directly are added, and the relabels still happen (a label must not lie) but their people get no hint; `notification.created` carries a `relabelled` count, so a relabel-only edit leaves a trace. **An upgrade (a less specific notice replaced by a fresh, more specific one) deletes the old row only when its replacement is made**: the wide-event budget, the person's switches (no applicable reason on either channel) and the email budget (an email-only kind over it) can each leave the person without a new row, and then they keep what they hold ONLY while that kind is still one of the new body's reasons for them (a fallback, say); a held kind the body no longer earns (everyone taken out for a team) is relabelled in place to the kind it does earn, or deleted when nothing applies ("a label must not lie" wins over keeping a notice). An upgrade is also dropped when no applicable reason reaches the person at all. One edit sends ONE `notification.created` hint, to the people it made a notice for and the people it relabelled (none of the relabelled when the edit was refused as a wide event).
- **1.1 A39. The old body's audience is read as of the edit, per reason.** A person is newly addressed at a reason R when the new body addresses them at R and the old one addressed them neither at R nor at anything more specific (the old audience is computed NOW: a team's roster, the watchers, the room's members; a team that has lost its handle since contributes nothing). A KEPT group token therefore re-addresses nobody, but a person the edit now names DIRECTLY is always newly addressed (`MENTIONED` is the most specific reason), whatever group reached them before: they get a fresh `MENTIONED` notice (reverses the first S7 draft). A group kind a person holds that the new body no longer earns for them is relabelled in place to the kind it does earn, or deleted when nothing applies.
- **1.1 A40. `mentionsMe` is a strict boolean both ways** (`false` is the complement); the group part (a team the caller is on NOW, everyone, the watchers while they watch NOW) applies only to a MEMBER of the room, so an ADMIN outside it is addressed by name only; `mentionedId` stays direct. The SPA's "Mentions me" (URL `mention=me`) sends `mentionsMe=true`.
- **1.1 A41. A group token is recorded when it is mentionable, not when it reaches someone.** An unknown, deleted or handle-less team is plain text and gets no row; a mentionable team, `<@watchers>` (even where nobody else watches, even in an opening message) and `<@everyone>` get a row and show as a mention, so `groupMentions` reflects what the author wrote that took effect, and a team with nobody in the room shows its handle and notifies no one.
- **1.1 A42. `groupMentions` on the wire:** `{kind, teamId, teamHandle, teamName}` with the three team fields null for the keywords, teams by id then `WATCHERS` then `EVERYONE`; a team shows its current handle and name. No new audit event: `notification.created`'s `kinds` already counts the group kinds, and a group mention that notified nobody leaves no line (like a direct mention of a non-member).
- **1.1 A43. The composer's list:** People (as before), Teams (`GET /teams?handle=`, from the first typed letter, only for a term a handle can hold), Groups (`@everyone`; `@watchers`, also found by `observers`, not offered in the new-thread form, which has no watchers but its author). A draft holding `@everyone` shows a non-blocking status line with the number of other members; there is no confirmation.

Of the 0.6 assumptions, **0.6 A10 and 0.6 A15 are reversed**, **0.6 A4 is partly reversed** (`@room` exists as `<@everyone>`, plus teams and watchers; `@here` and a non-member stay out), **0.6 A3, 0.6 A6 and 0.6 A8 are extended** (the 20 stays for people, with 5 teams and each keyword once; the edit rule of 1.1 A9; `mentionsMe` beside `mentionedId`). 0.6 D2's email-on default is reversed (1.1 D7); 0.6 D1 and 0.6 D3 stand.

### 1.2.0 Room view

Taken without asking, in the 1.2.0 plan (the user accepted the list on 2026-11-21 with the decisions above). Detail and the rules they became: `chat-model.md` "Room view, tags and unread (1.2)".

- **1.2 A1. Tags are set on an existing thread,** not in the new-thread form (`BACKLOG.md`).
- **1.2 A2. The registry's numbers and grammar are Hammurabi's:** a category name 1 to 60 unique among active ones, a description at most 500, 1 to 100 tags a category, at most 100 categories; a tag name trimmed, whitespace runs collapsed, lower-cased, 1 to 40 characters of `[\p{L}\p{N}][\p{L}\p{N} ._/+#:-]*`, unique over EVERY row and in exactly one category; removing a tag retires it and adding the name again revives the same row. The registry read is the shared paged envelope, not a bare `{items}` (the user's decision, 2026-11-22: API-STRUCT-004 allows no exception): sortable `id` and `name`, default `name`, and the SPA reads the whole registry in `pageSize=100` pages.
- **1.2 A3. A tag save rides the thread's own `revision`,** so an open details editor conflicts with it and the reverse.
- **1.2 A4. A tag change is history, not activity, and notifies nobody** (no rail bump, no unread, no notice; the pin rule; 1.1 A7 already leaves field edits out).
- **1.2 A5. Tag names appear in the history and the audit line** (an ADMIN-curated vocabulary, the status-label precedent).
- **1.2 A6. A retired tag stays on its threads until the next tag save,** shown struck through and said in words (`retired: true`); a save that still carries it is a `400`; the filter still matches it.
- **1.2 A7. Registry writes emit no live hint** (the team precedent); an open tab sees a retirement on its next read.
- **1.2 A8. `hashtags` on the wire:** the distinct folded keys of the thread's live messages, alphabetical, at most 20 per thread.
- **1.2 A9. `#42` as a link is the SPA's alone:** nothing is indexed, a missing number is not checked, titles and snippets are not linked.
- **1.2 A10. "Mark all as read" also marks the room's notices seen,** asks first (a confirmation), emits no live hint and is audited once (`room.read_all`: ids and a count; it settles an unbounded number of notices, in chunks, one call per person at a time). A message or notice committed in the same instant may be swallowed or settled (the `seen_at` limit already documented; a coalesced thread-level notice keeps its old time).
- **1.2 A11. A row read while the unread filter is on stays listed** until the list's next re-read.
- **1.2 A12. The unread filter is not on the searches.**
- **1.2 A13. The room description moves behind "About this room"** and the thread actions become icon buttons with unchanged accessible names.
- **1.2 A14. At release the thread-list column scrolled with the page.** The chat layout now gives it independent scrolling whenever the open thread fits the viewport.
- **1.2 A15. A profile link is not checked when drawn:** a removed person's link opens "not available".
- **1.2 A16. A hidden tab holds one of the person's 16 sockets for up to 8 hours** (1.2 D9).
- **1.2 A17. The title never shows a number it cannot keep current:** no number when signed out, `connecting`, `paused` or `idle` (the 8-hour close), and none once the socket has been `down` for the 5 s grace (the "Reconnecting…" badge's own: a token expiry's re-mint or a restart does not blink the title).
- **1.2 A18. `GET /rooms/{id}/tags` keeps meaning hashtags;** a `hashtag` alias is a `BACKLOG.md` item.
- **1.2 A19. No tag colours, no per-room subset of the registry, no facet counts.**

Reversed or changed: **0.4 D3 and 0.4 A4** (the hidden-tab close, by 1.2 D9) and the 0.2.0 note that `lockingTransaction` is not ported (ported for the registry only). 0.2 D2 stands (1.2 D7 adds no field to the directory entry `{id, name, email}`), and so do 0.6 D1 and 0.6 D4.

### 1.3.0 Quotes

Taken without asking, in the 1.3.0 plan (the user answered its three questions on 2026-11-22: the decisions above). Detail and the rules they became: `chat-model.md` "Quotes (1.3)".

- **1.3 A1. A quote points at one message of the same thread;** a new thread's opening message quotes nothing.
- **1.3 A2. A quote is set when the message is posted;** an edit cannot add, change or remove it.
- **1.3 A3. Nothing of the quoted text is stored with the quoting message.** The quote shows the quoted message as it reads now, at most 1,000 characters of it on the wire.
- **1.3 A4. A deleted quoted message reads "The quoted message was deleted." in every quote of it;** the quote stays a link to the tombstone.
- **1.3 A5. Quoting your own message is allowed.**
- **1.3 A6. One level:** no quote inside a quote.
- **1.3 A7. A quote chosen and then deleted before sending is refused on Send with its own words, and its text does not outlive the delete in the composer.** The composer line makes no request of its own, but it follows what the SPA already knows: when the conversation's loaded messages show the picked message deleted, or the server answers the post `quote-unavailable`, the draft's excerpt is BLANKED (the author stays, the line and the status line say the message is no longer available: a moderator's delete of a leaked credential is effective here too, 0.3 D2); when the loaded messages show it edited, the excerpt is refreshed from the loaded message. A message the list does not hold changes nothing. A delete made while the reader is in another thread reaches the line when that thread is opened again and its list re-reads (one round trip; accepted, the reader had already seen the text).
- **1.3 A8. An unknown id, a message of another thread or room and a deleted message are one answer** (a typed `400`).
- **1.3 A9. A quote is not a mention:** "Mentions me" does not list a thread for a quote.
- **1.3 A10. (1.3 D2) The notice counts as addressed,** sits under Mentions on the preferences page and survives a refused wide event.
- **1.3 A11. (1.3 D2) Deleting the quoted message leaves the notice about the reply.**
- **1.3 A12. The quote draft lives with the reply draft, in memory;** a reload drops both.
- **1.3 A13. The jump is the 0.5 jump unchanged** (ten loads of 50) and adds a history entry, as the pin strip's does.
- **1.3 A14. The pinned drawer, the pin strip, search hits and the bell show a quoting message's own text only.**
- **1.3 A15. `message.posted` gains `quotedMessageId` in the audit trail.**
- **1.3 A16. A 1.2 tab that saves notification preferences leaves the new kind at its default.**
- **1.3 A17. No keyboard shortcut for quoting.**

Extended: **1.1's eleven notice kinds** (a twelfth, `MESSAGE_QUOTED`, by 1.3 D2). Nothing else of 0.x to 1.2 is reversed: 1.1 D10 stands (a quote does not make a watcher), and so do 0.3 D2 (a moderator's delete of a leaked credential stays effective in every quote of it) and 0.2 D2.

### 0.3.0 threads and messages

Taken without asking: the user was away (2026-11-18) and said to take the architect's recommended defaults. **D1 and D2 are the architect's recommended
options, not the user's decisions**, and can be changed on request. Detail and the rules they became: `chat-model.md` "Threads and messages (0.3)".

- **D1. Who closes a thread:** the thread owner, the room OWNER or an ADMIN (the people who can reopen). Closing stops everyone, so it belongs to whoever
  can undo it; a member who thinks a thread is done says so or moves it to an OPEN status such as *Monitoring*. The alternative was any member closing
  (faster, but one member could freeze a thread its owner is still working on); it changes one predicate in the status rules and two matrix cells.
- **D2. Moderating messages:** the room OWNER or an ADMIN may delete, never edit, someone else's message (incident chat leaks credentials and the author may be
  asleep). It becomes a tombstone ("Removed by a room owner or an administrator"), `messages.deleted_by` records who, and the audit line is
  `message.deleted` with `moderated`. The alternative was authors only (a later migration would add the column).
- **Who does what.** Speech (start a thread, post): members only, so a non-member ADMIN gets 403 and joins first. OPEN-side metadata (OPEN->OPEN moves, title,
  fields): any reader. Stewardship (close, reopen, CLOSED->CLOSED, passing ownership): thread owner while a member, room OWNER, ADMIN. Own messages: edited and
  deleted by their author while a member.
- **Closed and archived.** A closed thread refuses posts, edits and deletes (moderator ones included), the details PUT and the owner hand-over (409
  `thread-closed`); stewards can still move it. An archived room refuses every content write (409 `room-archived`); reads work.
- **Ownership.** The new owner must be an active member (400); a removed or deleted owner keeps the thread, inert, until a steward passes it on; setting the
  current status or owner again is a no-op (204, no event, no audit).
- **Starting a thread** takes a title (single line, <= 200) and an opening message, in the initial status with the creator as owner; required fields must be supplied.
- **Numbers and counts.** `#N` is `MAX+1` per room and never reused; threads are never deleted in 0.3 (close as *Won't do*). `message_count` includes the
  opener and tombstones and never goes down.
- **Messages.** Markdown, 1 to 8,000 UTF-16 units; stored raw, rendered without HTML (headings as bold paragraphs, images as links, links in a new tab with
  `rel="noopener noreferrer"`; the CSP is unchanged). An edit overwrites (no versions) and sets `edited_at`; a delete scrubs the body and lists a tombstone.
- **Field values** by type (TEXT <= 500, NUMBER, DATE, SELECT, MULTI_SELECT <= 50, USER an active member, URL http/https); `null` clears; a required field cannot be
  cleared once set. Stored values survive a workflow edit.
- **Activity.** New threads and messages, and status, owner, title and field changes, bump the rail order; edits, deletes and workflow moves do not.
- **History** is the side panel, newest first, 10 per page; message edits and deletes are audited, not in the history; audit lines never carry a title,
  body or field value.
- **UI.** Enter inserts a newline, Ctrl/Cmd+Enter or Send posts; no polling or live refresh until 0.4 (shipped; refetch on focus, navigation and the caller's own
  writes); the thread list shows open threads, most recently active first, 50 per page, and the message list loads 50 at a time with "Load older messages";
  times render in the browser's zone as `YYYY-MM-DD HH:mm`.
- **Rate bucket:** `chat-post`, 120/min per user, on the two POSTs.

## Roadmap

Each increment gets its own plan (architect) before implementation. **Current version: 1.13.0** (external links and previews), on 1.12.0 (room organizer), on 1.11.0 (search by status and hashtag), on 1.10.0 (find in thread), on 1.9.0 (structured search), on 1.8.0 (personal room groups), 1.7.0 (personal Home), 1.6.0 (room access), 1.5.0 (clearer room tabs), 1.4.1 (thread status actions and refreshed Strike logo) and 1.4.0 (clearer workspace). 1.3.0 added quotes. 1.2.0 (room view, tags, unread) is tagged `v1.2.0` at 6018f17 (2026-11-22). 1.1.0 (watching, group mentions, notice kinds) is tagged `v1.1.0` at ec9531e (2026-11-21). 1.0.0 (the checkup) shipped 2026-11-21.

| Ver | Scope |
|---|---|
| **0.1.0 Foundation** | Flow trimmed: auth and email MFA, password reset, users and feature flags, teams with a manager, EN/PL, light/dark, changelog, telemetry panel, olive brand and the Strike logo |
| **0.2.0 Rooms and workflows** | The ADMIN workflow-template registry (seeded Default and Incident); rooms with members (person or whole team), OWNER/MEMBER roles, archive; the room workflow editor (statuses with colour/category/order, typed fields) under `X-Expected-Revision`; the rooms rail |
| **0.3.0 Threads and messages** | Threads with a per-room number, status, owner, fields and history (`EventLog` port); messages in Markdown with emoji; edit/delete own; close/reopen with the closed-thread `409`; the three-pane room view |
| **0.4.0 Live updates** (shipped) | `GET /api/v1/live` WebSocket with single-use tickets (sent in the first frame), subscribe/unsubscribe per room, `pg_notify` inside the write transaction, one LISTEN connection per process, bounded sockets, a 60 s account sweep; targeted query invalidation in the SPA (`live.md`) |
| **0.5.0 Find** (shipped) | Thread filters and sorts (status, category, owner, SELECT/MULTI_SELECT/USER fields, tag, workflow order), a focus view, Postgres full-text search per room and across member rooms (`chat-model.md` "Find (0.5)"), server-parsed #hashtags |
| **0.6.0 Social: mentions, notifications, unread, reactions, pins** (shipped) | `<@id>` mentions with an autocomplete over room members and the thread list's "mentions me" filter (moved from 0.5.0, 0.5 D1); notifications for mentions and hand-overs (Lettuce's model, ids only), pushed live over the per-user channel, with a 5-minute email digest by per-user preference (0.6 D2, 0.6 D3); per-member unread (a rail dot and mention count, thread counts, a "New" line; 0.6 D1); one-emoji reactions; room pins of threads and messages, any member (0.6 D4); one release (0.6 D5). `chat-model.md` "Mentions, notifications, unread, reactions, pins (0.6)" |
| **1.0.0 Checkup** (shipped) | The family's technical checkup (docs, DRY/SRP, tests, UI consistency, security, guardrails, infra) |
| **1.1.0 Watching, group mentions, notice kinds** (shipped) | Members watch threads (`PUT .../watch`, a creator and a new owner watch automatically, a "threads I watch" filter on the list and both searches); `<@team:id>`, `<@watchers>` and `<@everyone>` mentions with team handles; eleven notice kinds (every event notifies each person at most once, with the most specific reason; new-thread notices go to every member, activity to watchers); an in-app and an email switch per kind, email off by default for all (1.1 D7); a wide-event budget; V25 to V30. `chat-model.md` "Watching, group mentions and notice kinds (1.1)" |
| **1.2.0 Room view** (shipped) | A collapsed-by-default side panel and a compact room header (1.2 D4); pins marked in place with a strip in the open thread (1.2 D5); names as links with a profile page any member can open (`/people/:id`, 1.2 D6, 1.2 D7); a thread's hashtags on rows and header and `#42` as a link; an ADMIN tag registry (Kind and Area seeded) and curated tags on threads with a `threadTag` filter on the list and both searches (1.2 D1 to 1.2 D3); an "unread" list filter and "Mark all as read"; the unseen count in the tab title with a quiet hidden tab (1.2 D8, 1.2 D9); V31 to V34. `chat-model.md` "Room view, tags and unread (1.2)" |
| **1.3.0 Quotes** | A message quotes one earlier message of its own thread, a live reference that shows the quoted message as it reads now and says so when it was deleted (`quotedMessageId` on the POST, `quote` on every message; the "Quote" menu item and an always-visible quote icon, a line above the box, a block above the text that jumps back; 1.3 D1, D3); the quoted author is told by a twelfth notice kind, `MESSAGE_QUOTED` (1.3 D2); V35. `chat-model.md` "Quotes (1.3)" |
| **1.4.0 Clearer workspace** | A focused chat layout with independently scrolling thread and message regions, compact headers, clearer messages and composer, and structured Details; Home with active joined rooms and unread cues; a responsive room directory; a cleaner application header, mobile navigation and command palette (1.4 D1 to D3). No API, migration or domain-rule change. |
| **1.4.1 Thread status actions and refreshed Strike logo** | Valid workflow moves in the conversation header. Updated adder mark for light and dark themes and a dedicated browser favicon. |
| **1.5.0 Clearer room tabs** | Shared room navigation on Search, Members, Workflow and Settings, with grouped search filters, a roster-first member view, a responsive workflow editor and distinct settings sections. |
| **1.6.0 Room access** | Public self-join, visible restricted rooms with owner-reviewed requests, and restricted hidden rooms. Co-owners can step down while another active owner remains. |
| **1.7.0 Personal Home** | Recent replies quoting you, open threads you own, watched threads with unread first, and owned rooms with pending join requests. |
| **1.8.0 Personal room groups** | Private room groups, manual group and room order, and an account-synced sidebar layout. |
| **1.9.0 Structured search** | AND-combined clauses for text, room, people, calendar dates and message features. The query editor offers examples, entity suggestions and spelling corrections. |
| **1.10.0 Find in thread** | Paged message hits in the open thread, with structured syntax and direct message context from search, quotes, pins and existing links. |
| **1.11.0 Search by status and hashtag** | Structured clauses for a current workflow status and an indexed message hashtag, with room-aware suggestions. |
| **1.12.0 Room organizer** | Drag to move rooms and groups. Collapsible sections, contextual menus and a private, account-synced layout. |
| **1.13.0 External links and previews** | Destination confirmation and compact on-demand previews for message links and images. |

## Donors

Adder's repository started as an exact copy (`git archive`) of Flow @ `4e43e480` (`~/Sources/flow`, Flow 1.57.1), trimmed in the shape Astrolabe's
bootstrap (2026-10-31) set. Flow itself was copied from Covenant and trimmed; Lettuce and Toadie came in indirectly through it. **Port, don't
reinvent**: when Adder needs a capability a sibling repo already has, port its implementation.

- **Flow** (`~/Sources/flow`, the primary donor): the whole foundation — stack, API design, UI/UX, testing, gates, versioning, deployment, the
  telemetry panel. Ported in 0.5.0: `web/src/hooks/useLiveSearchParams.ts` (URL-held list state, with its test); Flow's `reportFilter.ts`/`useReportFilter.ts` (772 lines) are too heavy.
- **Astrolabe** (`~/Sources/astrolabe`): the trim recipe — the single-process deployment, `users/` (not `flowusers/`), the V1–V9 foundation
  migrations, the trimmed telemetry and teams, the bootstrap docs.
- **Lettuce** (`~/Sources/lettuce`): `infra/mail`, `infra/crypto`, `infra/paging`, the connection pool (through Flow); ported in 0.3.0: `MarkdownView` (the message variant),
  `EmojiPicker`/`EmojiButton`/`EmojiTextarea`, `infra/db/EventLog.kt`; ported in 0.5.0: `FilterMultiSelect.tsx` and `accessiblePill.tsx`; ported in 0.6.0: the shape of `notifications/NotificationService` (`list` with `seen`, `markSeen`, `markAllSeen`, the unknown-type rule) and its per-user preferences model (disabled pairs, a wholesale-replace PUT) with their test titles; reference only: `NotificationEmailer` and the per-language email builder (the mirror itself is replaced by the digest); next: the dictionary whole-document PUT.
- **Toadie** (`~/Sources/toadie`): `X-Expected-Revision` optimistic concurrency (ported in 0.2.0, `infra/revision/` and its `web/` half; the workflow-template and room-workflow PUTs consume it);
  `lockingTransaction` deliberately NOT ported for chat writes (a room write takes one `FOR NO KEY UPDATE` row lock on the room instead, `rooms/RoomLock.kt`); *changed by 1.2: ported (from Hammurabi) for the tag registry ONLY, `infra/db/Locking.kt`*; Toadie's tag categories are the ANCESTOR of 1.2's registry only (a JSON array, no revision, no join table: not ported from here); next:
  `blueprints/` `PropertyDefinition` as the reference for typed workflow fields.
- **Covenant** (`~/Sources/covenant`): ported in 0.6.0: the compact notification bell (`NotificationsButton.tsx`, itself Lettuce's; polling replaced by the live hint), while its in-transaction insert became the `ThreadNotices` port (`chat-model.md`); ported in 0.5.0: the pass-through group of `CommandPalette.tsx` and the shape of `useReviewInboxFilters.ts` (validate, omit defaults, `replace`); its facets (`ContractService.kt`) are a reference only (no facets so far); next: the `contracts/Lifecycle.kt` 409-on-illegal-move shape.
- **Hammurabi** (`~/Sources/hammurabi`): the donor of 1.2's tags (newer than Toadie's: tags are rows, the PUT and DELETE take `X-Expected-Revision`, retired tags revive): `tags/` (`TagCategory.kt`, `TagCategoryRoutes.kt`, `TagCategoryService.kt`), `infra/db/Locking.kt`, `V13__create_tags.sql`, the `adrs/AdrTagService.kt` shape for a record's tags, and the web half (`Tags` page, `TagCategoryEditorModal`, pills and editor, `tags.json`); ported in 1.2.0 (S6, S7).

Adder 1.13.0 adapts Flow's `infra/outbound/` connection-time DNS guard and URL-free `outbound.blocked` audit for message link previews.
Left in Flow on purpose: `ingest/`, `fetch/`, `jira/`, `gitlab/`, `norm/`, `identities/`, `metrics/`, `reports/`, `integration/` (service tokens),
`plugins/Role.kt`, `EncryptedAtRest`/`Reencrypt`.
Greenfield (no sibling has it): WebSocket push, @mention parsing and autocomplete, reactions (one-emoji grapheme check included), pins, read markers, a per-user notification-preferences table outside Lettuce, Postgres full-text search (confirmed 2026-11-19: no sibling has a `tsvector`, a hashtag parser or server-side highlighting; every one stops at `containsNormalized`).

## Release history

### v1.13.0 — external links and previews (2026-11-25)

External message and pinned-copy links open through a destination confirmation. Explicit preview requests show bounded page metadata
and verified PNG or JPEG content when available. The route uses the current message and room permissions and a guarded public HTTPS fetch.

### v1.12.0 — room organizer (2026-11-25)

Organize the private sidebar with collapsible groups, drag handles and keyboard and tap menus. Save applies the new order
through the existing revisioned account endpoint.

### v1.11.0 — search by status and hashtag (2026-11-25)

Structured search matches a thread's current workflow key with `status:` and a live message's indexed hashtag with `hashtag:`. The
same clauses work in global, room and thread search. Suggestions use a room's workflow and hashtags when there is a room in scope.
No database migration.

### v1.10.0 — find in thread (2026-11-25)

Search the open conversation with the same query syntax and a paged list of matching messages. Links to messages open a bounded context
window with the target and up to 25 neighbors on each side. Readers can browse earlier or later, or return to the latest conversation.
This replaces the sequential jump described in 0.5 A17 and 1.3 A13. No migration.

### v1.9.0 — structured search (2026-11-24)

Both search views provide a syntax guide and in-place suggestions. Both search endpoints accept `syntax=structured` and optional `timeZone`.
Existing plain searches remain compatible. Search uses the existing database schema.

### v1.8.0 — personal room groups (2026-11-24)

Organize joined rooms into private groups and arrange the groups and rooms in the sidebar. The layout follows the account across devices.
Migration V37 adds the personal layout table and existing memberships appear as ungrouped rooms until they are placed.

### v1.7.0 — personal Home (2026-11-23)

Home shows up to five recent replies quoting the caller's messages, open threads they own, watched threads and rooms they own.
Unread watched threads lead, and owners can open pending room requests from Home.

### v1.6.0 — room access (2026-11-23)

Creation sets the room's access type. The Rooms directory exposes public and restricted visible rooms to all signed-in users. Owners review
requests from Members and can add people directly. Restricted hidden rooms are visible only to members. Existing rooms default to hidden.
New members can read the room's existing conversation. Owners can share ownership or step down while another active owner remains.

### v1.5.0 — clearer room tabs (2026-11-23)

The Search, Members, Workflow and Settings tabs share navigation that marks the current page and wraps on narrow screens.
Search groups thread state, tags and watched threads below Keywords. Members starts with the roster and links directly to the add forms.
Workflow separates statuses and fields and keeps save actions in reach. Settings separates room details, availability and deletion.
The shared controls carry the same styling into global Search and workflow templates. The UI uses the existing API and permissions.
No data migration is required.

### v1.4.1 — thread status actions and refreshed Strike logo (2026-11-22)

The conversation header shows the current status and only the workflow moves available to the viewer. A single move gets a button and several moves appear in an action menu. Closing still asks for confirmation.

The app header and sign-in screens show a front-facing adder in both themes. Browser tabs use a simplified version of the same mark at small sizes.

### v1.4.0 — clearer workspace (2026-11-22)

The release metadata covers two UI passes. Chat now uses independent thread-list and conversation scrolling on larger screens, a focused thread view on narrow screens, compact room and thread headers, clearer message and quote treatments, a smaller composer pinned below the message timeline, and an organised Details panel for workflow controls, metadata, tags, timestamps and history. The wider workspace adds recently active joined rooms with unread cues to Home, a responsive table-or-card All rooms directory, and a consistent application header, mobile drawer and command palette. The mobile navigation manages focus and supports Escape and backdrop closing. There is no API, migration, dependency or domain-rule change, and no operator action is required. The version bump does not itself create a tag, publish a GitHub release or deploy containers.

### v1.2.0 — room view, tags, unread (2026-11-22)

Room-view polish on `feat/1.2.0-room-view`. Migrations V31 (`idx_message_tags_thread`, the hashtag index by thread), V32 (`tag_categories` and `tags`, seeded with **Kind**: `question`, `decision`, `action`, `announcement`, `postmortem` and **Area**: `customer`, `security`, `data`, `infrastructure`, `process`), V33 (`thread_tags`) and V34 (`idx_notifications_unseen_by_room`, the index behind "Mark all as read"). Shipped:
the side panel collapsed by default at every width (a "Details" button, remembered per person per browser) and compact room headers (1.2 D4, A13); pins marked in place with a strip in the open thread (1.2 D5); names as links wherever a name stands on its own and a profile page any member can open (`/people/:id`, `GET /api/v1/users/directory/{id}`, `memberId` on `GET /rooms` and `GET /teams`; 1.2 D6, D7); a thread's `hashtags` on rows and header and `#42` as a link (SPA only, 1.2 A9); the ADMIN tag registry (`/api/v1/tag-categories`, Hammurabi's port, a paged read like every list, writes under `X-Expected-Revision` and a table lock) and curated tags on threads (`PUT .../threads/{number}/tags`, riding the thread's revision; any member while the thread is open, a steward on a closed one; 1.2 D1 to D3), searchable by `threadTag` on the thread list and on both searches; the "unread" list filter and "Mark all as read" (`POST /rooms/{id}/read-all`, it also settles the room's notices); the tab title counting the bell, and a hidden tab that goes quiet after 5 minutes and closes after 8 hours with `BroadcastChannel` seen-sync across a browser's tabs (1.2 D8, D9; changes 0.4 D3). New audit events `tag_category.created`, `tag_category.updated`, `tag_category.deleted`, `thread.tags_changed` and `room.read_all`. No new dependency, no new `LiveKind`, no new rate bucket (the registry read, a thread's tags PUT and "Mark all as read" ride existing ones). Upgrade notes for operators: nothing to run; V31 to V34 are additive and inert to a 1.1.0 binary, so the rollback to 1.1.0 needs no dump (one `DELETE` first: "Rollback below 1.2.0" under "Thread tags (V33)" in `persistence.md`); the seeded Kind and Area names are immutable once released (an ADMIN renames or extends them in the registry); a forgotten hidden tab now holds one of the person's 16 sockets, and its session open, for up to 8 hours instead of 5 minutes (1.2 A16). Decisions 1.2 D1-D9 are the user's (2026-11-21, "Looks good"); A1-A19 were taken without asking. Open follow-ups (tags in the new-thread form, a per-room subset, facet counts, a `hashtag` alias, the unread filter on the searches, a pinned-first order): `BACKLOG.md`. Detail: `chat-model.md` "Room view, tags and unread (1.2)".

### v1.1.0 — watching, group mentions, notice kinds (2026-11-21)

Watching on `feat/1.1.0-watching`. Migrations V25 (`teams.handle`), V26 (`thread_watches`, backfilled once with each thread's creator and owner who are still members), V27 (the notice reshape: eleven kinds, `in_app`, the new unique index), V28 (`user_notification_preferences.enabled`), V29 (the wide-event budget) and V30 (`message_group_mentions`). Shipped:
`PUT .../threads/{number}/watch` (a creator and each new owner watch automatically; `watcherCount` on the wire), the "threads I watch" filter on the thread list and on both searches; eleven notice kinds made inside the write (a new thread tells every member, replies, status changes and hand-overs tell watchers, each person at most once per event with the most specific reason); `<@team:id>`, `<@watchers>` and `<@everyone>` mentions with an optional team handle set by an ADMIN; the preferences grid (an in-app and an email switch per kind) with **email off by default for every kind** (1.1 D7, A28: a 1.0.0 user who never touched their preferences stops getting the digest, and the changelog says so; account email, the MFA code and the reset link, always goes); a wide-event budget (500 a UTC day, `NOTIFICATIONS_MAX_BROADCASTS_PER_ACTOR_PER_DAY`); per-recipient live targets. New per-user buckets `thread-watch` (30/min), `notification-read` (240/min) and `notification-prefs` (30/min); new audit events `thread.watch_changed`, `notification.broadcast_budget_exceeded` and `user.notification_preferences_changed`. No new dependency. Upgrade notes for operators: the digest stays dark until each person opts in under Notification preferences; the production boot now refuses `POSTGRES_POOL_MAX_SIZE` of 18 or less (thread reads 10 + searches 4 + notices 4 must stay below the pool, `requireReadersUnderPool`; the default is 20); V30 needs PostgreSQL 15 or later (`NULLS NOT DISTINCT`; the shipped image is 18); the rollback runbooks to 1.0.0, with the SQL, are in `persistence.md` "Rollback below 1.1.0". Decisions 1.1 D1-D11 are the user's (2026-11-21); A1-A35 were taken without asking. Open follow-ups (the watchers list, the team named in its notice, auto-watch on reply, `@here`, coalescing activity notices, account-management notices): `BACKLOG.md`. Detail: `chat-model.md` "Watching, group mentions and notice kinds (1.1)".

### v0.6.0 — social (2026-11-20)

Social on `master` (tagged at `ba1bfd9`; two notification-digest fixes landed after the release commit `333dee9`). Migrations V17 (`message_mentions`), V18 (`notifications`), V19 (read state: a per-member room baseline and thread read markers), V20 (`message_reactions`), V21 (`room_pins`) and V22 (the notification email preferences and the digest's claim column). Shipped:
`<@id>` mentions (an autocomplete over the room's members, names read at request time, at most 20 counted per message) and the thread list's `mentionedId` filter ("Mentions me", moved from 0.5.0); notices (ids only, `MENTIONED` and `THREAD_ASSIGNED`) made INSIDE the write's transaction through the `ThreadNotices` port and pushed live to
their recipient only, the header bell with `GET /api/v1/notifications` and the two "seen" POSTs, an hourly retention sweep (90 days) and a leaver's notices gone with the membership; the email digest (a notice unseen for 5 minutes, at most one email per person per 15 minutes, names, room and thread number only, never content; a per-user, per-kind opt-out under
`/users/{id}/notification-preferences`); per-member unread (a rooms-rail dot, thread counts capped at "99+", a "New" line with a jump); one-emoji reactions (20 distinct per message, 5 per person, members only); room pins of threads and messages, any member, 50 a room. New per-user bucket `read-state` (240/min); the reaction and pin writes ride `chat-write`; new audit events `notification.created`, `notification.email_sent`, `notification.email_failed`,
`message.reaction_changed` and `room.pin_changed`; the read-state writes are deliberately unaudited view state. New optional settings `NOTIFICATIONS_RETENTION_DAYS`, `NOTIFICATIONS_SWEEP_INTERVAL_SECONDS`, `NOTIFICATIONS_SWEEP_ENABLED` and `NOTIFICATIONS_EMAIL_DELAY_SECONDS`, `NOTIFICATIONS_EMAIL_MIN_INTERVAL_SECONDS`, `NOTIFICATIONS_EMAIL_MAX_PER_RUN`. Upgrade notes for operators (V19 sets every membership's baseline in one statement under a lock on `room_members`,
about 1 to 3 s per 250,000 memberships; the digest needs `MAIL_TRANSPORT=smtp` and `MAIL_APP_URL`; the rollback runbook to 0.5.x): the GitHub release and `persistence.md` "Rollback below 0.6.0". Decisions 0.6 D1-D6 are the user's (2026-11-20); A1-A31 were taken without asking. Donors: Lettuce's notification service and preferences shape, Covenant's bell; mentions, reactions, pins and read markers are greenfield. Open follow-ups (the reactors' full list, search snippets that show `<@id>`, account changes over the per-user channel): `BACKLOG.md`. Detail: `chat-model.md` "Mentions, notifications, unread, reactions, pins (0.6)".

### v0.5.0 — find (2026-11-20)

Find on `feat/0.5.0-find`. Migrations V15 (`message_tags`, `messages.tags_version`) and V16 (the `adder_search` text search configuration and the stored `search` columns on `messages` and `threads`). Shipped:
the thread list's filters (repeated `status`, `ownerId`, `field=key:value` and `tag`) and the workflow-order sort, held in the URL and remembered per room (the SPA's `useLiveSearchParams`, `utils/threadListParams.ts`); server-parsed
#hashtags (a tag belongs to a live message, one grammar in Kotlin and TypeScript pinned by a shared case table, a boot reindex for older messages) with the room's paged tag list, the tag filter and message hashtags as links; full-text search
of one room (`GET /rooms/{id}/search`) and of the caller's member rooms (`GET /search`) over message bodies and thread titles (all terms, prefixes from three characters, accents and case ignored, typed highlight segments), one deadline
and a concurrency cap per search; the search pages and the palette's "Search for ..." actions; the jump to a found message (`?message=`) and the focus view (`focus=1`); a bounded read path for the filtered thread list and the tag list (a read deadline and a
concurrency limiter with typed `429`s `read-timeout` and `read-busy`), a capped sort list, and span-query scrubbing; failed statements log the exception class and the SQLSTATE only (A18). New per-user buckets `chat-read` (300/min, the thread
and tag reads) and `search` (60/min); new audit events `search.busy`, `search.timeout`, `read.busy`, `read.timeout`. Decisions D1-D5 are the user's (2026-11-19); A1-A18 were taken without asking. Donors: Flow's `useLiveSearchParams`, Lettuce's
`FilterMultiSelect`/`accessiblePill`, Covenant's palette pass-through and filter-state shape; search and hashtag parsing are greenfield. Open follow-ups (range and text field filters, facets, search within a thread, ticket keys, a
`btree_gin` search index): `BACKLOG.md`. Detail: `chat-model.md` "Find (0.5)".

### v0.4.0 — live updates (2026-11-19)

Live updates on `feat/0.4.0-live`; no migration. Shipped: single-use socket tickets (`POST /api/v1/live/tickets`, 30 s, in memory), the `GET /api/v1/live` WebSocket that
authenticates in its first frame (D1), every chat write emitting one `pg_notify('adder_live', …)` hint inside its transaction, one non-pooled LISTEN connection per process
with a bounded drain and a jittered reconnect, the hub (subscribe per room, `resync` on any gap, `revoked` when a membership is lost), the 60 s account sweep and the close at the token's
`exp` (D2), the bounds (16 sockets per user, 1000 per process, a 2 s auth deadline, D3's 5-minute hidden-tab close, since 1.2 D9 a quiet tab at 5 minutes and a close at 8 hours), the SPA's targeted query invalidation, the header "Reconnecting…" and
"Live updates off" indicator, views that stay put under a live refresh (room settings and the thread form keep their drafts), the telemetry panel's live-socket and listener gauges and the NOTIFY queue usage (the early warning before a full queue fails commits)
and a two-browser e2e. New per-user bucket `live-ticket` (60/min) and per-IP `live-connect`; new audit events `live.ticket_saturated`, `live.refused` and `live.revoked`. Decisions D1-D3 are
the user's (2026-11-19); A1-A13 were taken without asking. Donors: none (greenfield). Reverse-proxy notes: `live.md` "Deploy and proxies", `ci.md`. Open follow-ups: `BACKLOG.md`.

### v0.3.0 — threads and messages (2026-11-18)

Threads and messages on `feat/0.3.0-threads`. Migration V14 (threads, messages and the per-thread history in one migration). Shipped: a room's threads with a
per-room number, a title, an opening message, a status, an owner and the workflow's metadata fields (typed values, stored values survive a workflow edit); messages in
Markdown with emoji (author edit; author, room OWNER or ADMIN delete, leaving a tombstone); status moves with the steward rule for close, reopen and hand-over (the
thread owner while a member, the room OWNER, or an ADMIN; any reader moves a thread between OPEN statuses) and the closed-thread and archived-room `409`s; the per-thread
history over the ported `EventLog`; the `WorkflowUsage` port, so retiring a status that threads use moves them to a replacement (the retire-dialog e2e scenario); the
three-pane room view and the thread side panel. New per-user rate buckets `chat-post` (120/min, the two POSTs) and `chat-write` (240/min, the five other thread and message writes). Security hardening from the pre-release audit: the room-lock wait is bounded (`rooms.lockWaitMillis`, 3 s, then the audited `429 room-busy`), route-sized body ceilings (64 KiB messages, 192 KiB threads, 16 KiB status/owner) matched on the decoded path, the `fields` shape and document-size checks before any lock, the thread list never reads `fields`, URL values refuse hidden characters and userinfo, and an application-log hygiene test. History redaction (audit M4) is a `BACKLOG.md` item. Decisions D1 (who closes) and D2 (moderating messages) are
the architect's recommended defaults, taken while the user was away (see "0.3.0 threads and messages" above). Donors: `MarkdownView`, the emoji trio and `EventLog`
ported from Lettuce. Live refresh arrived in 0.4.0. Open follow-ups: `BACKLOG.md`.

### v0.2.0 — rooms and workflows (2026-11-18)

Rooms and workflows in fourteen commits on `feat/0.2.0-rooms-workflows`. Migrations V10-V13: team members (V10), the workflow-template registry (V11) and its
Default/Incident seeds (V12), rooms with members and the copied workflow (V13). Shipped: the ADMIN workflow-template registry; members-only rooms created from a
template (a person or a team snapshot as members, OWNER/MEMBER roles, the last-OWNER rule, archive/unarchive, ADMIN soft delete); the room workflow editor
(statuses with colour, category and order, typed fields, retire by key) under `X-Expected-Revision`; the user directory lookup; team rosters; the sidebar
rooms rail. Security hardening from the audit of the increment: a global 512 KiB request-body cap (413) and new per-user rate buckets (`room-name`, `room-members`,
`directory`). Donors: `X-Expected-Revision` ported from Toadie; `lockingTransaction` deliberately not ported. Open follow-ups: `BACKLOG.md`.

### v0.1.0 — the foundation (2026-11-17)

Flow trimmed to its generic core in nine commits (scaffold, core, server, tests, web, deploy, e2e/CI, docs, gates). Migrations V1–V9. No chat
feature yet.
