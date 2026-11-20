# Threads and messages

- **Spec**: [tests/threads.spec.ts](../tests/threads.spec.ts)
- **Actors**: the seed administrator (`admin@adder.local`); throwaway regular users created through the Users page (a room's owner
  and a teammate the owner adds)
- **Owns** (exclusive server-side state): its throwaway rooms (unique `e2e-thr-*` names) and its users, removed through the API in an
  `afterEach` (rooms first, then the users, so a failing step leaves nothing behind). The seeded Default template is shared and
  read-only (as is Incident): rooms only copy them. Every room is created by a throwaway user, so the seed administrator is never a member of one;
  the per-user `room-name` (30 a minute), `room-members` (60 a minute) and `chat-post` (120 a minute, on the thread and message
  POSTs) buckets are far from reached. Threads and messages are never deleted through the API: a room's removal hides them with it.

## Scenario: a member starts a thread from the room view and another member replies to it

1. The admin creates two throwaway users (an owner, a teammate) and signs out; the owner signs in, creates a room from the Default
   template and adds the teammate from the Members tab.
   - *Expected*: the add answers 200 and the teammate's row appears.
2. The owner opens the room's **Threads** tab, clicks **New thread**, fills a unique title and the first message (with a
   `**replica**` Markdown run) and clicks **Start thread**.
   - *Expected*: the create answers 201 and the app is at `/rooms/<id>/threads/1`; the pane's h3 reads `#1 <title>`, the conversation
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
   - *Expected*: each post answers 201; the owner's opening note has a row menu for the teammate that offers only **Pin** (any member pins; never
     Edit, Delete or Remove), and their own replies have the full menu.
3. They open the menu of "first draft", choose **Edit**, replace the text with "final draft" and click **Save**.
   - *Expected*: the edit answers 204, the editor goes, the message reads "final draft" with an "edited" marker and the old text is gone.
4. They open the menu of "oops wrong thread", choose **Delete** and confirm in the "Delete this message?" dialog.
   - *Expected*: the delete answers 204 and the dialog closes; the message becomes a tombstone "This message was deleted." that still
     names the teammate and has no menu.
5. The teammate signs out; the owner signs in and opens the thread, then opens the menu of their own opening note.
   - *Expected*: **Edit** and **Delete** are offered; after Escape the menu closes.
6. They open the menu of "token is hunter2" (the teammate's).
   - *Expected*: only **Remove** is offered, never Edit or Delete.
7. They choose **Remove** and confirm in the "Remove this message?" dialog.
   - *Expected*: the delete answers 204, the dialog closes, the text is gone and the message is a tombstone "Removed by a room owner
     or an administrator."; the earlier own-delete tombstone still reads "This message was deleted.".
8. The teammate signs in again and opens the thread.
   - *Expected*: both tombstones, each in its own words and under the teammate's name, and the "final draft" message still carries its
     "edited" marker.

## Scenario: a member moves a thread between open statuses but cannot close it, its owner closes it, and the closed thread refuses replies until the room owner reopens it

1. The admin creates an owner and a teammate and signs out; the owner creates a room from the Default template, adds the teammate and
   starts a thread, then signs out.
2. The teammate (a plain member, not the thread's owner) opens the thread and the side panel (below a wide main area the **Details**
   toggle opens it) and picks **In progress** in the status select.
   - *Expected*: the select reads "Open" first; there is no Owner picker for them; the move answers 204 and the select and the pane's
     header read "In progress".
3. They open the status select again.
   - *Expected*: **Done** and **Won't do** are disabled, and Done carries the hint "Needs the thread owner, a room owner or an
     administrator".
4. The teammate signs out; the owner signs in, opens the thread and its side panel, picks **Done** and confirms **Close thread** in the
   "Close this thread?" dialog.
   - *Expected*: the dialog names `#1` and Done; the move answers 204; the select reads "Done", the reply box is replaced by the note
     "This thread is closed, so it takes no new messages…".
5. The owner signs out; the teammate signs in and opens the thread, then its status select.
   - *Expected*: the closed note, no reply box, and **Open** is disabled (reopening is for a steward).
6. The teammate signs out; the owner signs in, opens the thread and picks **Open**.
   - *Expected*: no confirm dialog; the move answers 204 and the reply box is back.
7. The teammate signs in again and posts "Back in business.".
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
     "Changed Component", "Changed Jira link", with the old and new title and "Severity: set to S1" under them), then "Moved the thread
     from Triage to Investigating" under the teammate's name, "Passed the thread from <owner> to <teammate>", and last "Started the thread
     in Triage" under the owner's name. After a reload there are still seven.

## Not covered here (and why)

- The refusals that need a second actor at the same moment (a stale revision on the details editor, a thread closed under an open
  editor, a status retired meanwhile), the archived-room notices, and the history's paging past ten entries: unit-tested with the
  panel's components; they would need two browsers racing or a long setup.
- Loading older messages (more than 50 in a thread) and the scroll position: unit-tested with the list; a 50-message fixture is too slow
  through the UI.
