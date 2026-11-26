# Live updates across two browsers

- **Spec**: [tests/live.spec.ts](../tests/live.spec.ts)
- **Actors**: its own throwaway administrator (`e2e-live-admin-*`, `specAdmins`, who only mints the throwaway users); two throwaway regular users per scenario
  (a room's owner and a teammate the owner adds), each in its own browser context; one throwaway user in the last scenario
- **Owns** (exclusive server-side state): its throwaway administrator (`e2e-live-admin-*`, minted through the seed administrator's API in a `beforeAll` and deleted in an `afterAll`, after the file's own teardown; its sign-ins' live-ticket mints, one per page load, spend ITS per-user bucket, not the seed administrator's), its throwaway rooms (unique `e2e-live-*` names, created and filled through the owner's API session)
  and its users, removed through the API in an `afterEach` (rooms first, then the users, so a failing step leaves nothing behind). The seeded
  Default template is shared and read-only: rooms only copy it. Every room is created by a throwaway user, so the seed administrator is never a
  member of one. The last scenario owns one throwaway user and no room (the socket under test is that user's own, so its ticket bucket is untouched).
  The per-user `live-ticket` bucket (60 a minute) is far from reached: a sign-in or a page load mints one ticket. Threads and messages are never deleted through the API: a room's removal hides them with it.

Every scenario that watches a change first waits until the page's own socket has been acknowledged for the room (the `subscribed` frame), so the
change can only arrive by being pushed, and marks the document, so "without a reload" is checked rather than assumed.

## Scenario: a message posted in one browser appears in the other without a reload

1. The admin creates an owner and a teammate through the Users page; the owner makes a room through the API, adds the teammate and starts a
   thread.
2. The owner and the teammate each sign in in their own browser context and open the thread; both sockets subscribe to the room.
   - *Expected*: the teammate's pane reads `#1 <title>` and holds one message.
3. The owner types a reply and presses **Ctrl+Enter**.
   - *Expected*: the post answers 201; with no action in the teammate's browser the reply appears in their conversation under the owner's name
     and the header says "2 messages".
4. The teammate replies the same way.
   - *Expected*: the reply appears in the owner's conversation as its last message, under the teammate's name.
   - *Expected*: neither document was reloaded (the marker set on load is still there).

## Scenario: a message edit refreshes the author once and updates another reader live

1. The owner starts a thread and pins its opening message. Both members open the thread and subscribe.
2. The owner edits the pinned message and adds a hashtag. The browser holds the successful save response.
   - *Expected*: the edit hint includes the request identifier on the author's socket. The teammate receives the edit without that identifier.
   - *Expected*: the teammate's conversation and pin show the new text while the author is still saving. The author has yet to re-read the timeline.
3. The browser receives the save response.
   - *Expected*: the author's new body appears after exactly one timeline read, even after the live refresh window elapses.
   - *Expected*: the author can read the original body in edit history. The teammate's message menu has no history action. Neither page reloads.

## Scenario: a thread started by one member appears in another member's thread list

1. The same setup, without a thread: both members open the room's **Threads** view.
   - *Expected*: the teammate's thread list holds no link.
2. The owner clicks **New thread**, fills a unique title and a first message with a `**replica**` Markdown run and clicks **Start thread**.
   - *Expected*: the create answers 201 and the owner lands on `/rooms/<id>/threads/1`.
3. The teammate does nothing.
   - *Expected*: the thread appears in their list as a link, and their document was not reloaded.
4. The teammate follows the link.
   - *Expected*: the pane reads `#1 <title>` and shows the owner's opening message.

## Scenario: a status change made by one member shows in another member's open thread

1. The same setup with an API-seeded thread; both members open it.
   - *Expected*: the teammate's list row and current-status badge both say "Open".
2. The owner opens **Change status** in the thread header and picks **Move to In progress**.
   - *Expected*: the move answers 204.
3. The teammate does nothing.
   - *Expected*: their list row and current-status badge now say "In progress", the header menu offers **Move to Open** and **Move to Waiting**, and their document was not reloaded.

## Scenario: a member removed from a room loses it from the rail and the open room

1. The same setup with an API-seeded thread; the owner opens the room's **Members** page and the teammate opens the thread.
   - *Expected*: the teammate's sidebar lists the room and the pane shows the thread.
2. The owner opens the teammate's row menu, chooses **Remove** and confirms in the "Remove from the room?" dialog.
   - *Expected*: the delete answers 204.
3. The teammate does nothing.
   - *Expected*: the room leaves their rail and the open conversation is replaced by a restricted visible access preview with **Request to join**.
     Their document was not reloaded.

## Scenario: the header says it is reconnecting while the browser is offline

1. The same setup with an API-seeded thread; the teammate opens it.
   - *Expected*: no "Reconnecting…" badge is in the header.
2. The teammate's browser goes offline (`context.setOffline`).
   - *Expected*: within 15 seconds (the client's 5-second grace period, then the badge) the header shows "Reconnecting…".
3. While it is offline the owner posts a message through the API.
   - *Expected*: the offline page does not show it.
4. The teammate's browser comes back online.
   - *Expected*: the badge goes within 15 seconds and the missed message appears in the conversation, still with no reload.

## Scenario: the live socket opens under the production content security policy

1. The admin creates a throwaway user. A fresh context records every `securitypolicyviolation` its documents report (an init script, installed
   before the first page script runs); the user signs in, which loads the shell, and a plain request then fetches `/` for its headers.
   - *Expected*: the document's `Content-Security-Policy` header contains `connect-src 'self';` and no `ws:` or `wss:` source.
2. The page's socket authenticates.
   - *Expected*: a WebSocket to the page's own host at `/api/v1/live` was opened and the server's `ready` frame arrived through it; the collector
     recorded no policy violation.
