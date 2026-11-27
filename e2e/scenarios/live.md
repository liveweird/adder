# Live updates across two browsers

- **Spec**: [tests/live.spec.ts](../tests/live.spec.ts)
- **Actors**: its own throwaway administrator (`e2e-live-admin-*`, `specAdmins`, who only mints the throwaway users); two throwaway regular users per scenario
  (a room's owner and a teammate the owner adds), each in its own browser context; one throwaway user in the last scenario
- **Owns** (exclusive server-side state): its throwaway administrator (`e2e-live-admin-*`, minted through the seed administrator's API in a `beforeAll` and deleted in an `afterAll`, after the file's own teardown; its sign-ins' live-ticket mints, one per page load, spend ITS per-user bucket, not the seed administrator's), its throwaway rooms (unique `e2e-live-*` names, created and filled through the owner's API session)
  and its users, removed through the API in an `afterEach` (rooms first, then the users, so a failing step leaves nothing behind). The seeded
  Default template is shared and read-only: rooms only copy it. Every room is created by a throwaway user, so the seed administrator is never a
  member of one. The last scenario owns one throwaway user and no room (the socket under test is that user's own, so its ticket bucket is untouched).
  The per-user `live-ticket` bucket (60 a minute) is far from reached: a sign-in or a page load mints one ticket. Deletion scenarios act on their own messages and threads. Room cleanup hides the remaining conversations.

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

## Scenario: a committed message deletion clears both tabs before its response and refreshes the writer once

1. The owner opens the same thread in two sessions. The thread includes an edited, pinned message.
2. The owner opens its edit history in the second tab and confirms deletion in the first. The browser holds the successful HTTP response.
   - *Expected*: both timelines replace the body with a tombstone. The pin and open history close without waiting for the response.
3. The response arrives. The writer's timeline and pins each read once and focus goes to the tombstone. Neither tab reloads.

## Scenario: a lost deletion response still removes the message and its quote live

1. The owner writes a message and quotes it in another room. The teammate opens the thread with the quote and subscribes to both rooms.
2. The owner deletes the original. The server commits and the test discards the HTTP response.
   - *Expected*: the owner sees a tombstone without a lingering error. The teammate sees the quote's deleted notice and the quoting reply remains visible. Neither page reloads.

## Scenario: thread deletion clears a merged conversation live and refreshes the writer once

1. The owner opens a pinned canonical thread. The teammate opens the conversation that was merged into it.
2. The owner confirms deletion and the browser holds the successful response.
   - *Expected*: the owner's thread list and pins lose the canonical thread. The teammate's pane removes the deleted history.
3. The response arrives.
   - *Expected*: the owner is back in the room and its thread list and pins each read once. Neither page reloads.

## Scenario: a lost thread deletion response still clears a cross-room quote

1. The owner posts in one room and quotes the message in another. The teammate opens the quoting reply and subscribes to both rooms.
2. The owner deletes the source thread. The server commits and sends the live event while the browser is holding the response.
   - *Expected*: the thread leaves the owner's list. The reader's reply stays visible and loses the source excerpt.
3. The test discards the response.
   - *Expected*: the owner returns to the room with their list filter intact, and neither page reloads.

## Scenario: numeric thread references link readable targets and update after creation or deletion

1. A member opens a conversation with repeated references to a merged source, a deleted number, and a number that has yet to be created.
   - *Expected*: the browser batches the distinct numbers. The source links to its original history with the list filters intact. The deleted and future numbers and the reference in inline code stay text.
2. The owner creates the future thread and then deletes the canonical thread and its merged sources.
   - *Expected*: the future number becomes a link. The merged source becomes plain text. The message stays in the conversation.
3. The owner posts another future number. The browser holds the first lookup after the server sees no match, then receives the thread’s creation event.
   - *Expected*: after the held response is released, the new reference becomes a link. The reader’s page does not reload.

## Scenario: a committed merge refreshes the writer once and updates the reader live

1. The owner and a teammate open the duplicate thread. The owner chooses a target and confirms the merge while the browser holds the successful response.
   - *Expected*: the teammate sees the merged banner, and the duplicate leaves the primary thread list. Its original history remains readable.
   - *Expected*: the event carries the request identifier only on the owner's socket. The writer has yet to reread the thread list or workflow.
2. The successful response arrives.
   - *Expected*: the writer opens the target and retains the list filter. The list and workflow each read once, and neither page reloads.

## Scenario: a lost merge response releases the writer's live refresh

1. The owner and teammate open a duplicate thread. The owner merges it, and the server commits while the browser holds the response.
   - *Expected*: the teammate sees the merged banner live.
2. The test discards the successful response.
   - *Expected*: the writer catches up through the live event and can still read the source's history. The writer stays on the original route, and neither page reloads.

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

## Scenario: adding a room member updates another reader and refreshes the writer once

1. The owner and a teammate open the Members page. The owner adds a third person from the directory, and the browser holds the successful response.
   - *Expected*: the teammate sees the new member. Only the writer's account may see its request identifier, and the writer has yet to reread the roster.
2. The response arrives.
   - *Expected*: the writer sees the new member after one roster read, and neither page reloads.

## Scenario: a lost add-member response releases the writer's roster refresh

1. The owner adds a person while the browser holds the successful response. The other reader sees the new member live.
2. The test discards the response.
   - *Expected*: the writer's live event runs its usual roster refresh. The new member appears, and neither page reloads.

## Scenario: a member removed from a room loses it from the rail and the open room

1. The same setup with an API-seeded thread; the owner opens the room's **Members** page and the teammate opens the thread.
   - *Expected*: the teammate's sidebar lists the room and the pane shows the thread.
2. The owner opens the teammate's row menu, chooses **Remove** and confirms in the "Remove from the room?" dialog. The browser holds the 204 response.
   - *Expected*: the room leaves their rail and the open conversation is replaced by a restricted visible access preview with **Request to join**.
     The revocation carries no mutation identifier. The owner's other tab removes the teammate from its roster live.
3. The response arrives.
   - *Expected*: the writer's roster refreshes once. None of the pages reload.

## Scenario: a self-leave finishes after live revocation has already replaced the room

1. A member and the owner each open the room’s Members page.
2. The member confirms **Leave room**. The server commits the removal, and the browser holds the successful response.
   - *Expected*: the leaving browser shows the access preview at its current URL and removes the room from its rail. The owner’s roster loses the member.
3. The browser receives the response.
   - *Expected*: the leaving browser navigates to Rooms. Neither page reloads.

## Scenario: the header says it is reconnecting while the browser is offline

1. The teammate opens an API-seeded thread whose message refers to another thread.
   - *Expected*: the reference is a link and no "Reconnecting…" badge is in the header.
2. The teammate's browser goes offline (`context.setOffline`).
   - *Expected*: within 15 seconds (the client's 5-second grace period, then the badge) the header shows "Reconnecting…".
3. While it is offline the owner posts a message and deletes the referenced thread through the API.
   - *Expected*: the offline page does not show it.
4. The teammate's browser comes back online.
   - *Expected*: the badge goes within 15 seconds. The missed message appears and the deleted reference becomes plain text, with no reload.

## Scenario: the live socket opens under the production content security policy

1. The admin creates a throwaway user. A fresh context records every `securitypolicyviolation` its documents report (an init script, installed
   before the first page script runs); the user signs in, which loads the shell, and a plain request then fetches `/` for its headers.
   - *Expected*: the document's `Content-Security-Policy` header contains `connect-src 'self';` and no `ws:` or `wss:` source.
2. The page's socket authenticates.
   - *Expected*: a WebSocket to the page's own host at `/api/v1/live` was opened and the server's `ready` frame arrived through it; the collector
     recorded no policy violation.
