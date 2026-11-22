# Social: mentions, notifications, unread, reactions, pins and the notification email page

- **Spec**: [tests/social.spec.ts](../tests/social.spec.ts)
- **Actors**: its own throwaway administrator (`e2e-social-admin-*`, `specAdmins`, who only mints the throwaway users); two throwaway regular users per scenario (a room's
  owner, "the author", and a teammate the owner adds, "the reader"), each in its own browser context; one throwaway user in the last scenario
- **Owns** (exclusive server-side state): its throwaway administrator (`e2e-social-admin-*`, minted through the seed administrator's API in a `beforeAll` and deleted in an `afterAll`, after the file's own teardown; its sign-ins' live-ticket mints, one per page load, spend ITS per-user bucket, not the seed administrator's), its throwaway rooms (unique `e2e-soc-*` names, created and filled through the owner's API session) and
  its users, removed through the API in an `afterEach` (browser contexts first, then rooms, then the users, so a failing step leaves nothing behind).
  The seeded Default template is shared and read-only: rooms only copy it. Every room is created by a throwaway user, so the seed administrator is
  never a member of one. The per-user `live-ticket` (60 a minute), `chat-post` (120), `chat-write` (240) and `read-state` (240) buckets are only ever
  spent by throwaway users and a scenario stays far below them. Threads, messages, reactions and pins are never deleted through the API: a room's
  removal hides them with it.

Every scenario that watches a change first waits until the page's own socket has been acknowledged for the room (the `subscribed` frame), so the
change can only arrive by being pushed, and marks the document, so "without a reload" is checked rather than assumed. Where the scene needs
data the scenario is not about (a thread, a message that mentions someone) it is seeded through the owner's API session; the browsers then do
what the scenario is about.

## Scenario: a mention picked in the composer reaches the mentioned member's bell without a reload and opens the message

1. The administrator mints an author and a reader; the author makes a room through the API, starts a thread and THEN adds the reader (a new thread is told to the members
   already in the room, and this scenario counts what the mention causes).
2. The author opens the thread, the reader opens the room's **Threads** view (in the room, not in the thread); both sockets subscribe.
   - *Expected*: the reader's bell is named "Notifications (0 unseen)".
3. The author types `Heads up @` and the unique tail of the reader's name in the **Reply** box.
   - *Expected*: a list of the room's members appears; picking the reader turns the word into `@<name>` in the box.
4. The author finishes the sentence and presses **Ctrl+Enter**.
   - *Expected*: the post answers 201.
5. The reader does nothing.
   - *Expected*: the bell is named "Notifications (1 unseen)" and the room's link in the rail is described as "1 unseen notification"; the
     reader's document was not reloaded.
6. The reader opens the bell.
   - *Expected*: the drawer holds a link "`<author>` mentioned you in #1 `<title>`", marked unseen.
7. The reader clicks it.
   - *Expected*: the address holds `message=<id>` of the author's message; the drawer closes; the message is shown, with `@<reader>` in it; the
     bell says "0 unseen" and the rail's description is gone; the document was still not reloaded.

## Scenario: the Mentions me filter shows the thread and survives a reload

1. The same setup, without browsers on the room: the author starts two threads, "Plain" and "Mentioning", and replies in the second with a
   `<@reader>` mention. The reader signs in.
2. The reader opens the room and the **Filters** panel.
   - *Expected*: both threads are listed and **Mentions me** is not pressed.
3. The reader presses **Mentions me**.
   - *Expected*: the button is pressed, the address says `mention=me`, only "Mentioning" is listed, the Filters toggle shows 1.
4. The reader reloads the page.
   - *Expected*: the address, the pressed button and the narrowed list are all still there.

## Scenario: handing a thread over notifies its new owner

1. The same setup (the thread is started BEFORE the reader joins); the author opens it, the reader opens the room's **Threads** view.
   - *Expected*: the reader's bell says "0 unseen".
2. The author opens the side panel, searches the **Owner** picker for the reader, picks them and presses **Pass on**.
   - *Expected*: the owner PUT answers 204.
3. The reader does nothing.
   - *Expected*: the bell says "1 unseen" and the reader's document was not reloaded; the drawer holds "`<author>` made you the owner of #1
     `<title>`".
4. The reader clicks the notice.
   - *Expected*: the thread opens, its pane reads "Owner: `<reader>`", and the bell says "0 unseen".

## Scenario: a message posted elsewhere marks the room and the thread as new, and reading it clears both

1. The author makes a room and starts a thread; only then does the author add the reader (what was written before joining is never unread). The
   reader opens Home (`/`); the rail lists the room.
   - *Expected*: the room's link has no "New activity" description.
2. The author posts two messages to the thread through the API.
   - *Expected*: with no reload, the room's link in the rail is described as "New activity".
3. The reader clicks the room in the rail.
   - *Expected*: the room is acknowledged (`POST .../seen` answers 204); the thread's row is described as "2 unread messages".
4. The reader opens the thread.
   - *Expected*: exactly one "New messages" separator stands in the conversation, inside the first of the two new messages and not inside the
     older opening message.
   - *Expected*: once the reader is at the end, the thread's row loses its unread description.
5. The reader loads Home again.
   - *Expected*: the room's link has no "New activity" description (the server holds it, not the page).

## Scenario: the unread filter lists only threads with new messages

1. The administrator mints an author and a reader; the author makes a room through the API and starts a thread ("before joining"), THEN adds the reader, then starts a second
   thread and posts a message in it (the reader joined after the first, so only the second holds anything unread).
2. The reader opens the room's **Threads** view.
   - *Expected*: the second thread's row is described as "2 unread messages"; the first is described with nothing about unread.
3. The reader opens **Filters** and presses **Unread**.
   - *Expected*: the button is pressed, the address carries `unread=me`, only the second thread is listed, and the Filters badge says 1.
4. The reader reloads the page.
   - *Expected*: the address, the pressed button and the narrowed list survive; the API's `threads?unread=true` lists exactly the second thread.
5. The reader presses **Unread** again.
   - *Expected*: both threads are listed and the address no longer carries `unread`.

## Scenario: mark all as read clears a room

1. The administrator mints an author and a reader; the author makes a room through the API, starts a thread, THEN adds the reader, and posts two messages, the first of which mentions the reader.
2. The reader opens the room's **Threads** view with the Unread filter on (`?unread=me`); the socket subscribes.
   - *Expected*: the thread is described as "2 unread messages" and the bell is not named "Notifications (0 unseen)".
3. The reader presses **Mark all as read**, then **Cancel** in the dialog "Mark all as read?".
   - *Expected*: the dialog closes and the thread still says "2 unread messages".
4. The reader presses **Mark all as read** and confirms.
   - *Expected*: the request answers 204; the dialog closes; the bell is named "Notifications (0 unseen)"; the filtered list says "No threads match these filters."; the document was not
     reloaded; the API's `threads?unread=true` totals 0.
5. The author posts one more message through the API.
   - *Expected*: with no reload the thread is back in the filtered list, described as "1 unread message" (only what comes after the mark is unread).

## Scenario: a reaction from the picker appears for another member without a reload, and a second press removes it

1. The same setup with a started thread; both open it. Both browsers record every `securitypolicyviolation` the document reports.
   - *Expected*: the reader's message has no reactions list.
2. The author opens **Add a reaction** under the opening message, searches the picker for "thumbs up" and picks the thumbs-up.
   - *Expected*: the reaction PUT succeeds; the author's chip is named "👍, 1 reaction" and is pressed.
3. The reader does nothing.
   - *Expected*: a chip "👍, 1 reaction" appears, not pressed, described by the author's name; the document was not reloaded.
4. The reader presses the chip.
   - *Expected*: it reads "👍, 2 reactions" and is pressed; the author's chip follows to "👍, 2 reactions".
5. The reader presses it again.
   - *Expected*: it reads "👍, 1 reaction" and is not pressed; the author's chip follows to "👍, 1 reaction".
6. Both pages are asked what they recorded.
   - *Expected*: no policy violation in either (the lazy emoji chunk and its shadow-root picker run under the strict policy).

## Scenario: a pinned thread and a pinned message show in the pinned panel for another member, and unpinning removes them

1. The same setup with a started thread and a second message ("Keep this runbook step within reach."); both open the thread.
   - *Expected*: the reader's room button reads "Pinned (0)".
2. The author presses **Pin this thread**, then opens the message's menu and picks **Pin**.
   - *Expected*: both PUTs succeed; the thread button is pressed.
3. The reader does nothing.
   - *Expected*: the button reads "Pinned (2)" and the document was not reloaded.
4. The reader opens the panel.
   - *Expected*: it lists the thread (`#1 <title>`, a link) and "Message by `<author>`" with its text, each "Pinned by `<author>` on …", and an
     **Unpin** button for each (any member may unpin).
5. The author unpins the thread and then the message.
   - *Expected*: the reader's open panel says "Nothing is pinned yet." and no longer lists the thread; no reload.
6. The reader closes the panel.
   - *Expected*: the button reads "Pinned (0)".

## Scenario: a pinned message is marked in the conversation and listed in the thread's pinned strip

1. The same setup with a started thread (opening message "The opening message.") and two more messages posted through the API ("Keep this runbook step within reach." and "Rotate the replica credentials." with a second line); both open the thread.
   - *Expected*: the reader sees no pin strip and no "Pinned by" line.
2. The author opens each of those two messages' menu and picks **Pin** (the note last, so it is the newest pin).
   - *Expected*: both PUTs succeed.
3. The reader does nothing.
   - *Expected*: both messages read "Pinned by `<author>`" (with a pin shape and `data-pinned`), the opening message does not; a "Pins in this thread" strip stands under the header as ONE collapsed line, the button "Pinned: Rotate the replica credentials. (+1)" (`aria-expanded` false, the newest pin's first line and the count of the rest), with no link visible; the document was not reloaded.
4. The reader presses the strip.
   - *Expected*: it lists two entries in place, the note first, each with who pinned it and when; the button now reads "Pinned in this thread (2)".
5. The reader presses **Show in thread** of the runbook entry, moves the focus to the reply box, and presses it again.
   - *Expected*: the URL names the message (`message=<id>`), the message takes the focus, is on screen and is not covered (its top edge is below the strip's bottom edge: the expanded list is in the flow); after the focus moved away, the second press does the same again (a jump to a message already landed on).
6. The author presses **Pin this thread**.
   - *Expected*: with no reload, the reader's thread header carries a "Pinned" badge and the thread's list row a visible badge, the row's description says "Pinned" and its name is still the thread's.
7. The reader presses **Unpin** on the runbook entry, then on the last one.
   - *Expected*: the runbook message loses its "Pinned by" line in both pages at once and the focus lands on the strip's button; the strip then shows one pin, and after the last **Unpin** it is gone from both pages and the focus is on the thread's pane and no message reads "Pinned by"; the reader's document was not reloaded.
8. The author pins the runbook message again; the reader reloads.
   - *Expected*: the strip comes back already expanded ("Pinned in this thread (1)", the open/closed choice is remembered per browser), and it is still expanded after the reload.

## Scenario: a member removed from a room loses its notifications

1. The same setup (the thread is started before the reader joins); the author starts a thread, adds the reader and replies with a `<@reader>` mention through the API. The reader opens Home.
   - *Expected*: the bell says "1 unseen"; its drawer lists "`<author>` mentioned you in #1 `<title>`". The reader closes it with Escape.
2. The author removes the reader from the room (through the API: the Members page is the live spec's subject).
   - *Expected*: with no reload, the room leaves the reader's rail and the bell says "0 unseen".
3. The reader opens the bell.
   - *Expected*: the drawer says "You have no notifications."

## Scenario: the Notification preferences page saves a switch and keeps it after a reload

1. The administrator mints a user (the only actor here, no room); the user signs in and opens `/notification-preferences` from the address bar.
   - *Expected*: the page is titled "Notification preferences"; "Someone mentions me: In-app" is on, "Someone mentions me: Email" and "Someone makes me
     the owner of a thread: Email" are off, and **Save** is disabled.
2. The user switches "Someone mentions me: Email" on.
   - *Expected*: **Save** is enabled.
3. The user presses **Save**.
   - *Expected*: the preferences PUT answers 204, the toast "Notification preferences saved" shows and Save is disabled again.
4. The user reloads the page.
   - *Expected*: "Someone mentions me: Email" is on, the In-app switch is still on and the hand-over email switch is still off (the server holds the choice).

## Scenario: a kind switched off in the app stops arriving

1. The same setup (an owner and a teammate in a room, a thread started through the API BEFORE the teammate joined); the teammate is signed in and the live channel is up
   for the room.
   - *Expected*: the bell says "0 unseen".
2. The teammate opens `/notification-preferences` (the live channel is up again there), switches "Someone mentions me: In-app" off and presses **Save**.
   - *Expected*: the PUT answers 204 and the toast "Notification preferences saved" shows.
3. The owner mentions the teammate in a message and then hands the thread over to the teammate (both through the API).
   - *Expected*: with no action the bell says "1 unseen" (the hand-over alone).
4. The teammate opens the bell.
   - *Expected*: the drawer lists "`<owner>` made you the owner of #1 `<title>`" and no "mentioned you" notice.

## Not covered here (and why)

- **The email digest of unseen notifications** — clock-driven (a delay of minutes, then one mail per recipient); covered by `NotificationDigestTest` on the server.
- **The "N new messages below" arrivals button and "Jump to the first new message"** — scroll-position behaviour that depends on viewport height and
  message size; covered by the `MessageListUnread` component tests.
- **Reactions or pins refused** (a closed thread, an archived room, the 50-pin and the reaction limits) — covered by the server tests and the
  component tests; E2E asserts only the visible consequences above.

## Scenario: a watcher hears a reply

1. The administrator mints an author and a reader; the author makes a room through the API and starts a thread, THEN adds the reader (the room was not told of a thread
   the reader was not there for). The reader signs in and opens the thread.
2. The reader presses **Watch this thread**, then opens the room's **Threads** view (in the room, not in the thread); the socket subscribes.
   - *Expected*: the PUT answers 204; the reader's bell is named "Notifications (0 unseen)".
3. The author posts a reply through the API.
   - *Expected*: the bell is named "Notifications (1 unseen)" with no reload.
4. The reader opens the bell.
   - *Expected*: the drawer lists "<author> replied in #1 <title>".
5. The reader follows the notice.
   - *Expected*: the thread opens, shows the reply, and the bell is named "Notifications (0 unseen)".
6. With the thread open and read to its end, a script records every label the bell takes. The author posts another reply.
   - *Expected*: the read marker is sent (204); the reply is shown; the bell stays "Notifications (0 unseen)" and none of the recorded labels ever counted an unseen
     notice (the badge never went up and down again); the document was not reloaded; and the server lists no unseen notice for the reader (the read marker marked it
     seen, not merely the page's cache).

## Scenario: everyone in the room hears about a new thread

1. The administrator mints an author and a reader; the author makes a room through the API and adds the reader. Both sign in and open the room's **Threads** view; both
   sockets subscribe.
   - *Expected*: the reader's bell is named "Notifications (0 unseen)".
2. The author starts a thread through the API.
   - *Expected*: the reader's bell is named "Notifications (1 unseen)" with no reload (they watch nothing: a new thread is told to every member of the room).
3. The reader opens the bell.
   - *Expected*: the drawer lists "<author> started #1 <title>".
4. The author's own bell is looked at.
   - *Expected*: it is named "Notifications (0 unseen)" (nobody is told of their own thread).

## Scenario: a team mention reaches the team's members in the room

1. The administrator mints an author, a reader and an outsider; the author makes a room through the API and starts a thread, THEN adds the reader (the outsider never joins the
   room). The administrator, through the API, gives a team a mention handle and puts the reader AND the outsider on its roster. The author opens the thread, the reader
   opens the room's **Threads** view; both sockets subscribe.
   - *Expected*: the reader's bell is named "Notifications (0 unseen)".
2. The author types `Paging @` and the team's handle in the **Reply** box.
   - *Expected*: the list holds the team under a "Teams" heading; picking it turns the word into `@<handle>` in the box.
3. The author finishes the sentence and presses **Ctrl+Enter**.
   - *Expected*: the post answers 201 and its `groupMentions` lists the team with its handle.
4. The reader does nothing.
   - *Expected*: the bell is named "Notifications (1 unseen)" with no reload; the drawer holds "`<author>` mentioned your team in #1 `<title>`". The author's message
     reads `@<handle>`.
5. The outsider's notifications are read through the API.
   - *Expected*: the total is 0 (a team member who is not in the room is never told).

## Scenario: everyone is told once, with the most specific reason

1. The administrator mints an author and a reader; the author makes a room through the API and starts a thread, THEN adds the reader. The author opens the thread, the reader
   opens the room's **Threads** view; both sockets subscribe.
   - *Expected*: the reader's bell is named "Notifications (0 unseen)".
2. The author picks the reader from the list (`@<name>`), types `and @eve` and picks `@everyone` under the "Groups" heading.
   - *Expected*: the box reads `@<reader> and @everyone`, and a status line says "@everyone notifies the other member of this room." (no confirmation is asked).
3. The author presses **Ctrl+Enter**.
   - *Expected*: the post answers 201.
4. The reader does nothing.
   - *Expected*: the bell is named "Notifications (1 unseen)" (one notice, not two) with no reload; the drawer holds "`<author>` mentioned you in #1 `<title>`" and no
     "mentioned everyone" notice. Following it opens the message, which shows `@everyone`.
5. The reader goes back to the room's **Threads** view (a reader looking at the thread would have the notice acknowledged by the pane); the author posts a second message
   through the API that names only everyone.
   - *Expected*: the bell is named "Notifications (1 unseen)"; the reader's drawer holds "`<author>` mentioned everyone in #1 `<title>`"; the author's own bell stays "Notifications (0 unseen)".

## Scenario: the tab title counts unseen notifications and a second tab follows

1. The administrator mints an author and a reader; the author makes a room through the API, starts a thread, THEN adds the reader. The reader opens the room's **Threads** view (one browser
   context; the socket subscribes) and opens a SECOND tab of the same browser on the home page.
   - *Expected*: neither tab's title starts with a number.
2. The author posts a message through the API that mentions the reader.
   - *Expected*: with no reload both tabs' bells are named "Notifications (1 unseen)" and both titles start "(1) " and end "Adder" (the bell's number, `utils/titleCount.ts`).
3. In the second tab the reader opens the bell and presses **Mark all as seen**.
   - *Expected*: the second tab's bell is named "Notifications (0 unseen)" and its title loses the number; the FIRST tab, which was sent no live hint (the write emits none) and was not
     touched, follows through the browser's `BroadcastChannel`: its bell is named "Notifications (0 unseen)", its title has no number, and its document was not reloaded.

## Scenario: a hidden tab goes quiet and keeps counting, and closes after eight hours

1. The administrator mints an author and a reader; the author makes a room through the API, starts a thread, THEN adds the reader. The reader's page runs on Playwright's installed clock
   (`context.clock.install()` before anything loads: the page's timers fire when the test fast-forwards, not when it waits) and records every frame the page's socket sends and
   whether its newest socket is open; the reader signs in straight onto the room's **Threads** view (that is the browser's ONE page load, so one socket) and the socket subscribes. The tab is made "hidden" the way a browser reports it: the test overrides
   `document.visibilityState` and fires `visibilitychange` (a headless page is never really hidden).
2. The clock is fast-forwarded five minutes while the tab is hidden.
   - *Expected*: the page sent an `unsubscribe` for the room; its socket was not closed; the header's status region is empty (a deliberate quiet is not "Reconnecting…").
3. The clock is fast-forwarded two more minutes (a quiet tab re-reads the bell at most once a minute, and the jump may have flushed the page load's own re-read), then the author posts a
   message through the API that mentions the reader.
   - *Expected*: the reader's bell is named "Notifications (1 unseen)" and the title starts "(1) " (a quiet tab still hears a notice meant for the person).
4. The tab is shown again.
   - *Expected*: the page sent a `subscribe` for the room again; the title still starts "(1) ".
5. The tab is hidden again and the clock is fast-forwarded eight hours.
   - *Expected*: the page's socket was closed (the page's newest socket is closed) and the title has no number (it never shows one it cannot keep current).
6. The tab is shown again.
   - *Expected*: the socket reconnects (a socket is open again): the title starts "(1) " again and the bell is named "Notifications (1 unseen)".

