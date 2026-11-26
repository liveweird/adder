# Threads and messages

- **Spec**: [tests/threads.spec.ts](../tests/threads.spec.ts)
- **Actors**: its own throwaway administrator (`e2e-threads-admin-*`, `specAdmins`); throwaway regular users created through the Users page (a room's owner
  and a teammate the owner adds)
- **Owns** (exclusive server-side state): its throwaway administrator (`e2e-threads-admin-*`, minted through the seed administrator's API in a `beforeAll` and deleted in an `afterAll`, after the file's own teardown; its sign-ins' live-ticket mints, one per page load, spend ITS per-user bucket, not the seed administrator's), its throwaway rooms (unique `e2e-thr-*` names) and its users, removed through the API in an
  `afterEach` (rooms first, then the users, so a failing step leaves nothing behind). The seeded Default template is shared and
  read-only (as is Incident): rooms only copy them. Every room is created by a throwaway user, so the seed administrator is never a member of one;
  the per-user `room-name` (30 a minute), `room-members` (60 a minute) and `chat-post` (120 a minute, on the thread and message
  POSTs) buckets are far from reached. Threads and messages are never deleted through the API: a room's removal hides them with it.

## Scenario: a member starts a thread from the room view and another member replies to it

1. The admin creates two throwaway users (an owner, a teammate) and signs out; the owner signs in, creates a room from the Default
   template and adds the teammate from the Members tab.
   - *Expected*: the add answers 200 and the teammate's row appears.
2. The owner opens the room's **Threads** tab, clicks **New thread**, fills a unique title and the first message (with a
   `**replica**` Markdown run), chooses one tag from the registry and clicks **Start thread**.
   - *Expected*: the create answers 201 with the chosen tag and the app is at `/rooms/<id>/threads/1`; the pane's h3 reads `#1 <title>`, the conversation
     shows one message by the owner with "replica" rendered bold, the header says "1 message" and the thread is a link in the list.
3. The owner signs out; the teammate signs in, follows the room in the rail and opens the thread from the list.
   - *Expected*: the conversation shows the opener; the composer is empty and its counter reads "0 / 8000".
4. The teammate types a line, presses **Enter**, types a second line (with a `**old logs**` run) and then presses **Ctrl+Enter**.
   - *Expected*: after the plain Enter the box holds both lines and no message was posted; after Ctrl+Enter the post answers 201, the
     box is empty, the reply is the last message in the conversation under the teammate's name with "old logs" bold, and the header
     says "2 messages". A reload still shows the reply.
5. The owner signs in again and opens the thread.
   - *Expected*: two messages: the owner's opener, then the teammate's reply, the newest at the bottom.

## Scenario: an author edits and deletes their own message and a room owner removes another member's message

1. The admin creates an owner and a teammate and signs out; the owner creates a room, adds the teammate and starts a thread
   (an opening note), then signs out.
2. The teammate signs in, opens the thread and posts three replies ("first draft", "oops wrong thread", "token is hunter2").
   - *Expected*: each post answers 201; the owner's opening note has a row menu for the teammate that offers only **Quote** and **Pin** (any member
     quotes and pins; never Edit, Delete or Remove), and their own replies have the full menu.
3. They open the menu of "first draft", choose **Edit**, replace the text with "final draft" and click **Save**.
   - *Expected*: the editor explains that earlier versions are retained; the edit answers 204, the editor goes, the message reads "final draft" with an "edited" marker and the old text is gone.
4. They open **View edit history** from "final draft".
   - *Expected*: a focused dialog shows "first draft" as inert source text with no rendered links; closing it returns to the conversation.
5. They open the menu of "oops wrong thread", choose **Delete** and confirm in the "Delete this message?" dialog.
   - *Expected*: the delete answers 204 and the dialog closes; the message becomes a tombstone "This message was deleted." that still
     names the teammate and has no menu.
6. The teammate signs out; the owner signs in and opens the thread, then opens the menu of their own opening note.
   - *Expected*: **Edit** and **Delete** are offered; after Escape the menu closes.
7. They open the menu of "token is hunter2" (the teammate's).
   - *Expected*: only **Remove** is offered, never Edit or Delete.
8. They choose **Remove** and confirm in the "Remove this message?" dialog.
   - *Expected*: the delete answers 204, the dialog closes, the text is gone and the message is a tombstone "Removed by a room owner
     or an administrator."; the earlier own-delete tombstone still reads "This message was deleted.".
9. The teammate signs in again and opens the thread.
   - *Expected*: both tombstones, each in its own words and under the teammate's name, and the "final draft" message still carries its
     "edited" marker.

## Scenario: a member quotes a message in a reply, the quote leads back to it, and a deleted message reads as deleted in the quote

1. The admin creates an owner and a teammate and signs out; the owner creates a room, adds the teammate and starts a thread whose opening
   message has a `**replica**` Markdown run, then signs out.
2. The teammate signs in, opens the thread and posts two replies ("Checking the lag now.", "Lag is under a second."), then presses the
   **Quote** icon button that sits beside the opening message's menu (one click, no menu opened; its name is "Quote the message by <the owner> on <time>" and its tooltip says "Quote").
   - *Expected*: a line above the Reply box says "Quoting <the owner>" and shows a plain excerpt of the opening message; the Reply box has the
     focus.
3. They type "Yes, caught up." and press **Ctrl+Enter**.
   - *Expected*: the post answers 201 and its body is `{body, quotedMessageId}` with the opening message's id (the 201 carries it as
     `quote.messageId`); the box and the line are both gone; the reply shows a link above its text whose name is its content
     ("Quoted message by <the owner> <excerpt>": a visually hidden lead-in, then the visible words), holding the owner's name and the opening message's text as plain text (no bold run), and it is still there after a reload.
4. They open the menu of "Lag is under a second.", look at its first item, choose it, and then press **Remove the quote**.
   - *Expected*: the first item is **Quote**; choosing it shows "Quoting <the teammate>" and the Reply box has the focus (the menu does not
     take it back); the remove button takes the line away and the box keeps the focus.
5. They click the quote of their reply and reload that history entry before returning.
   - *Expected*: the address names the opening message (`message=<id>`), which is focused and highlighted (`data-anchor`), and the status
     line says "Found the message by <the owner>.". **Back to reply** restores focus to "Yes, caught up.", changes the message target to
     that reply and disappears; **Back to latest** remains a separate action.
6. The owner (through the API) edits the opening message; the teammate opens the thread again.
   - *Expected*: the edit answers 204; the quote's link is named "Quoted message by <the owner> (edited) …" and reads "(edited)" beside the owner's name, with the new text and
     none of the old.
7. The owner (through the API) deletes the opening message; the teammate opens the thread again.
   - *Expected*: the delete answers 204; the quote's link is named "Quoted message by <the owner> The quoted message was deleted." and reads so, with no text of
     the message anywhere in the conversation; the reply keeps its own text; following the quote focuses the opening message, now a tombstone
     ("This message was deleted."); **Back to reply** restores the quoting reply again.

## Scenario: a member moves a thread between open statuses but cannot close it, its owner closes it, and the closed thread refuses replies until the room owner reopens it

1. The admin creates an owner and a teammate and signs out; the owner creates a room from the Default template, adds the teammate and
   starts a thread, then signs out.
2. The teammate signs in, opens the thread, and uses the keyboard to open **Change status** and choose **Move to In progress**.
   - *Expected*: with Details still collapsed, the menu offers only **Move to In progress** and **Move to Waiting**. Focus starts on the
     first action and returns to **Change status** after the 204. The current status reads "In progress". The teammate opens Details and sees no Owner picker.
3. The teammate signs out; the owner signs in, opens the thread, opens **Change status**, chooses **Close as Done**, and confirms **Close thread** in the
   "Close this thread?" dialog.
   - *Expected*: the menu exposes the viable workflow targets; the dialog names `#1` and Done; the move answers 204; the current status reads "Done", the reply box is replaced by the note
     "This thread is closed, so it takes no new messages…".
4. The owner signs out; the teammate signs in and opens the thread.
   - *Expected*: the closed note and current **Done** status show, there is no reply box, and no status action is present (reopening is for a steward).
5. The teammate signs out; the owner signs in, opens the thread and chooses **Reopen as Open** from **Change status**.
   - *Expected*: no confirm dialog; the move answers 204 and the reply box is back.
6. The teammate signs in again and posts "Back in business.".
   - *Expected*: the post answers 201 and the message is in the conversation.

## Scenario: the owner passes the thread on, the new owner edits its details, and the history lists every change

1. The admin creates an owner and a teammate and signs out; the owner creates a room from the **Incident** template (it carries the
   optional Severity, Component, Jira link and ETA fields), adds the teammate and starts a thread.
2. The owner opens the side panel: the Owner picker reads their own name. They search for the teammate, choose them, and click
   **Pass on**.
   - *Expected*: choosing alone moves nothing; **Pass on** answers 204 and the pane's header reads "Owner: <teammate>".
3. The owner signs out; the teammate signs in, opens the thread and its side panel.
   - *Expected*: the teammate (a plain member, now the thread's owner and so a steward) has the Owner picker, reading their own name.
4. They move the status to **Investigating** (204), click **Edit details**, change the title, pick Severity **S1**, fill Component and
   a Jira link, and click **Save**.
   - *Expected*: the save answers 204 and the editor goes; the pane's heading carries the new title; the read view shows Severity S1 and
     Component, and the Jira link is a link opening in a new tab without the opener.
5. They read the **History** section.
   - *Expected*: seven entries, newest first: the rename and the three field changes ("Renamed the thread", "Changed Severity",
     "Changed Component", "Changed Jira link", with "Severity: set to S1" under the select's change and no title, text or
     link value anywhere in the history: it records that they changed, not what they were), then "Moved the thread
     from Triage to Investigating" under the teammate's name, "Passed the thread from <owner> to <teammate>", and last "Started the thread
     in Triage" under the owner's name. After a reload there are still seven.

## Scenario: the details panel opens on demand and stays open for the next thread

1. The admin creates an owner and signs out; the owner creates a room from the Default template and starts two threads.
2. The owner opens the first thread.
   - *Expected*: the header's **Details** button reads `aria-expanded="false"`; the side panel's **Owner** section is not on the page,
     while the current status remains in the header; the composer's **Send** button is on screen without scrolling (the suite's 1280x720
     viewport: the compact headers give the conversation the height).
3. They press **Details**.
   - *Expected*: the button reads `aria-expanded="true"` and the panel's four sections, Owner, Details, Tags and History, show.
4. They open the second thread from the list (no reload), then reload the page.
   - *Expected*: the second thread opens with the panel already open, and so does the page after the reload: the choice is one
     switch per person per browser, not the thread's.
5. With the panel still open they start a third thread whose title is far too long for the header's row, and look at it at 1280x720 and
   at 1440x900.
   - *Expected*: the heading's name is the whole title (the row shows it ending in an ellipsis), the panel's sections show, **Send** is on
     screen at both sizes and the page does not scroll: the pane is a column bounded by the viewport, so a wrapping header cannot push the
     composer down. They pin that thread's opening message from its menu: a pin strip ("Pinned: A thread with a long title.") stands
     under the header as one collapsed line; at both sizes, collapsed and expanded (its capped list is in the flow under the header and the conversation
     shrinks to give the height back), **Send** is still on screen and the page still does not scroll. They reply with seven hashtags, one of them fifty
     characters long: the header gains a third row of hashtag links that is one line high (four links shrinking to an ellipsis and a "+3" button),
     and at both sizes, the strip collapsed and expanded, with the panel open and with it closed (they close it and open it again),
     **Send** is on screen, the page does not scroll and the row is still one line. They go back to the second thread.
6. They press **Details** again and reload.
   - *Expected*: the panel is closed (`aria-expanded="false"`, its sections not on the page): closing is remembered the same way.
7. Below the three-column width the page is not bounded: it scrolls, the conversation keeps a real height and the composer sticks to the
   bottom edge. On a phone (375x740, panel closed) they open the long-titled thread.
   - *Expected*: the heading's box is at least 200px wide (two lines, never a few characters squeezed beside the icons); the current status
     and **Change status** remain in the viewport; **Send** is on screen at the top of the page and after scrolling the page to its bottom;
     the conversation is at least 16rem (256px) tall.
8. At 1024x720 with the navigation open (the main area is too narrow for the third column, so the panel stacks above the conversation)
   they open the panel.
   - *Expected*: the panel shows (capped, scrolling inside) and the same three things hold: **Send** in view at the top and at the bottom of
     the page, the conversation at least 256px tall.

## Scenario: a cross-room quote opens its source and stays hidden from destination-only readers

1. The owner creates separate source and destination rooms and starts a thread in each. A second person joins the destination.
2. From the source message menu the owner chooses **Quote in another thread…**, finds the destination room and thread, then clicks **Send** after the source quote check finishes.
   - *Expected*: the composer carries the source quote to the destination.
3. The owner follows the quote, then selects **Back to reply**.
   - *Expected*: the links open and focus the correct messages across both rooms.
4. The second person reads the destination thread.
   - *Expected*: the reply remains readable and the source excerpt and source link are absent.
5. The owner grants the reader access to the source. After the reader reloads and sees the quote, the owner revokes access again.
   - *Expected*: the quote disappears from the open destination conversation without a reload.

## Not covered here (and why)

- The refusals that need a second actor at the same moment (a stale revision on the details editor, a thread closed under an open
  editor, a status retired meanwhile), the archived-room notices, and the history's paging past ten entries: unit-tested with the
  panel's components; they would need two browsers racing or a long setup.
- Loading older messages (more than 50 in a thread) and the scroll position: unit-tested with the list; a 50-message fixture is too slow
  through the UI.

## Scenario: message links ask before opening and load a preview only on request

1. A room owner posts an external link and a Markdown image, then hovers and middle-clicks the link.
   - *Expected*: No navigation or preview request occurs.
2. They activate the link by keyboard, read the destination, then explicitly request a preview.
   - *Expected*: The guarded endpoint is called once. A reserved test destination shows an unavailable preview. The confirmation passes an axe scan.
3. They dismiss the dialog with Escape, reopen it, and choose Open link.
   - *Expected*: Focus returns on dismissal. The confirmed page opens in a new tab without an opener or referrer.
4. They pin the message and open its image link from the room's pinned drawer.
   - *Expected*: The confirmation identifies the image URL and waits for a deliberate action. Tab stays within the confirmation.
     The first Escape returns focus to the image in the open drawer. The second Escape closes the drawer.
