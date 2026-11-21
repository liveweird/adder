# Find: filters, hashtags, search and the focus view

- **Spec**: [tests/find.spec.ts](../tests/find.spec.ts)
- **Actors**: its own throwaway administrator (`e2e-find-admin-*`, `specAdmins`, who only mints the throwaway users); throwaway regular users per scenario (a room's owner
  and, where it matters, a teammate), signed in without the form; one throwaway ADMINISTRATOR in the search-across-rooms scenario, minted through the
  seed administrator's API
- **Owns** (exclusive server-side state): its throwaway administrator (`e2e-find-admin-*`, minted through the seed administrator's API in a `beforeAll` and deleted in an `afterAll`, after the file's own teardown; its sign-ins' live-ticket mints, one per page load, spend ITS per-user bucket, not the seed administrator's), its throwaway rooms (unique `e2e-find-*` names, created and filled through the owners' API sessions) and its
  users, removed through the API in an `afterEach` (rooms first, then the users, so a failing step leaves nothing behind). The seeded Default and
  Incident templates are shared and read-only: rooms only copy them. Every room is created by a throwaway user, so the seed administrator is never a
  member of one. Threads and messages are never deleted through the API: a room's removal hides them with it. The per-user `search` bucket (60 a
  minute) is only ever spent by the throwaway user who searches, and a scenario runs a handful of searches. Search words are unique per run, so no
  other data can match them.

The data of each scenario is seeded through the API (threads, replies, status moves, memberships); the browser then does what the scenario is about.

## Scenario: a status and owner filter narrows the thread list and survives a reload

1. The administrator mints an owner and a teammate; the owner makes an Incident room through the API and adds the teammate. Three threads are
   seeded: Alpha (the owner's, Triage, Severity S1, `#outage`), Bravo (the teammate's, moved to Investigating, S2) and Charlie (the owner's,
   Investigating, S2, `#outage`).
2. The owner opens the room and the **Filters** panel.
   - *Expected*: all three threads are listed and the toggle shows no count.
3. The owner picks **Investigating** in **Status**.
   - *Expected*: the address says `status=investigating`; Bravo and Charlie are listed, Alpha is not; the toggle shows 1.
4. The owner presses **Mine**.
   - *Expected*: the button is pressed, the address says `owner=me`, only Charlie is listed, the toggle shows 2.
5. The owner picks **S1** in **Severity**.
   - *Expected*: the address holds `field=severity:s1`; the list says "No threads match these filters." and the toggle shows 3.
6. The owner removes **S1** and picks **S2**.
   - *Expected*: Charlie is listed again and the empty message is gone.
7. The owner opens **Hashtags**.
   - *Expected*: the picker offers `#outage` with its thread count (2). Picking it adds `tag=outage` to the address, Charlie stays listed, the toggle
     shows 4.
8. The owner reloads the page.
   - *Expected*: the address still holds the status, the owner and the tag; only Charlie is listed; the toggle shows 4 and **Mine** is still pressed.
9. The owner presses **Clear filters**.
   - *Expected*: all three threads are listed, the address holds none of the filter keys, the toggle shows no count and the Clear button is gone.

## Scenario: a hashtag in a message is a link that shows the threads using it

1. The administrator mints an owner; the owner makes a Default room through the API and seeds three threads: Release (no tag), Hotfix (the tag in
   capitals in its opening message) and Lunch (no tag).
2. The owner opens Release and replies, in the composer, with a message containing `#<tag>` and presses **Ctrl+Enter**.
   - *Expected*: the reply shows the tag as a link named `#<tag>`.
3. The owner clicks the link.
   - *Expected*: the owner stays in the app on Release's address with `tag=<tag>` and `scope=all` in it; Release and Hotfix are listed (the spelling's
     case is ignored), Lunch is not; the filter toggle shows 1 and the panel holds a pill named "Remove #<tag>".
4. The owner edits the reply through its menu, removing the tag, and saves.
   - *Expected*: the save answers 204; Release leaves the filtered list while Hotfix stays.

## Scenario: a thread's hashtags show on its list row and in its header

1. The administrator mints an owner; the owner makes a Default room through the API and seeds two threads: Release (its opening message carries a
   hashtag typed in capitals) and Lunch (none).
2. The owner opens the room.
   - *Expected*: Release's row shows the hashtag in dimmed text, lower case (the stored key), and holds no link of its own; Lunch's row shows none.
3. The owner opens Release and looks at the thread header.
   - *Expected*: a "Hashtags" group under the title holds a link named after the hashtag.
4. The owner replies with a second hashtag.
   - *Expected*: with no reload the header gains a link for it and Release's row shows it too.
5. The owner edits the reply through its menu, removing the second hashtag, and saves.
   - *Expected*: the save answers 204; the header's link for it is gone and the row no longer shows it.
6. The owner clicks the header's link for the first hashtag.
   - *Expected*: the address stays on Release with `tag=<hashtag>` and `scope=all`; Release is listed and Lunch is not.

## Scenario: a thread number in a message links to that thread

1. The administrator mints an owner; the owner makes a Default room through the API and seeds two threads, Origin and Target. A message in Origin
   says `Duplicate of #<Target's number>`, repeats the number once in inline code and names a number no thread has.
2. The owner opens Origin with `scope=all` in the address.
   - *Expected*: the message holds exactly one link named `#<Target's number>`; the repeat in code is code, not a link.
3. The owner clicks the link.
   - *Expected*: the address is Target's thread of the same room and still holds `scope=all`; the pane's heading reads `#<number> <Target>`.
4. The owner goes back and clicks the link for the number no thread has.
   - *Expected*: the address names that number and the pane says "This thread does not exist."

## Scenario: a room search finds a Polish word typed without accents and opens the message

1. The administrator mints an owner and a teammate; the owner makes a Default room through the API and adds the teammate. A thread is seeded whose
   second message (the teammate's) holds "Żółwie tempo wdrożenia to największy problem." among three later messages.
2. The owner opens the room and follows the **Search** link of the room's pages.
   - *Expected*: the address is `/rooms/<id>/search` and the box "Search this room" is empty.
3. The owner types a single character and presses **Search**.
   - *Expected*: an alert says "Type at least two characters, with a letter or a digit." and no search request was sent.
4. The owner types `zolw` and presses **Search**.
   - *Expected*: the address holds `q=zolw`; "1 thread found"; one result for the thread, its title in it, "1 matching message", and exactly one
     highlighted word, "Żółwie".
5. The owner clicks the message in the result.
   - *Expected*: the address is the thread's with `message=<id>`; the pane reads `#<number> <title>`; the message is focused and marked as the one
     jumped to, and the page announces "Found the message by <teammate>.".

## Scenario: a search across rooms never shows a room the reader is not a member of

1. The administrator mints a reader and a stranger, and (through the API) a second administrator. The reader makes rooms A and B and the stranger
   makes room C; the reader adds the second administrator to room A only. Each room holds a thread whose opening message contains the same unique word.
   - *Expected*: the stranger's own search for the word finds one thread (the control: the word is findable where the reader is a member).
2. The reader opens the command palette from the header and types the word.
   - *Expected*: the palette offers "Search all rooms for “<word>”".
3. The reader picks it.
   - *Expected*: the address is `/search?q=<word>`, the box holds the word, "2 threads found"; the hits are the threads of rooms A and B, each
     naming its room; nothing of room C (name or title) is on the page.
4. The second administrator signs in, opens **Search** (`/search`), types the word and presses **Search**.
   - *Expected*: "1 thread found", the thread of room A with its room's name; nothing of room B or room C: for an administrator too, membership is the
     scope of the search.

## Scenario: the focus view hides the thread list and survives a reload

1. The administrator mints an owner; the owner makes a Default room through the API and seeds two threads.
2. The owner opens the room, follows the first thread's row and looks at the **Focus on this thread** button.
   - *Expected*: the pane reads `#<number> <title>`; the button is not pressed; the thread list is visible.
3. The owner presses the button.
   - *Expected*: the button is pressed; the address holds `focus=1`; the thread list is hidden; the conversation is still shown.
4. The owner reloads the page.
   - *Expected*: the button is still pressed, the list is still hidden and the conversation is shown.
5. The owner presses the button again.
   - *Expected*: `focus` leaves the address; the list is visible again with the other thread's row; the button is not pressed.
