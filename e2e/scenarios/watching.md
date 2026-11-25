# Watching: the toggle, the row mark, the filter, the creator's own watch and the search's watched list

- **Spec**: [tests/watching.spec.ts](../tests/watching.spec.ts)
- **Actors**: its own throwaway administrator (`e2e-watch-admin-*`, `specAdmins`, who only mints the throwaway users); two throwaway regular users per scenario (a
  room's owner, "the creator", and a teammate the owner adds, "the teammate")
- **Owns** (exclusive server-side state): its throwaway administrator (`e2e-watch-admin-*`, minted through the seed administrator's API in a `beforeAll` and deleted in
  an `afterAll`, after the file's own teardown; its sign-ins' live-ticket mints spend ITS per-user bucket, not the seed administrator's), its throwaway rooms (unique
  `e2e-watch-*` names, created and filled through the owner's API session) and its users, removed through the API in an `afterEach` (rooms first, then the users, so a
  failing step leaves nothing behind). The seeded Default template is shared and read-only: rooms only copy it. Every room is created by a throwaway user, so the seed
  administrator is never a member of one. The per-user `read-state` (240 a minute) and `chat-post` (120) buckets are only ever spent by throwaway users and a scenario
  stays far below them; the search scenario spends a handful of the per-user `search` bucket (60 a minute) and of the `thread-watch` bucket (30) of its own throwaway teammate. Threads are never deleted through the API: a room's removal hides them with it.

A watch is the caller's own state (like a read marker): the server emits no live event for it, so nothing here waits for a push; each check reads the page after
the action that caused it. Threads the scenario is not about are seeded through the owner's API session; the browser then does what the scenario is about.

## Scenario: a member watches a thread and stops

1. The administrator mints a creator and a teammate; the creator makes a room through the API, adds the teammate and starts two threads, "Watched" and "Other". The
   teammate signs in and opens the thread "Watched".
   - *Expected*: the button "Watch this thread" is not pressed; "Watchers: 1" is shown (the creator watches their own thread); the thread's row in the list has no
     "You are watching this thread" description.
2. The teammate presses "Watch this thread".
   - *Expected*: the PUT answers 204; the button is pressed; the count reads "Watchers: 2"; the row of "Watched" is described as "You are watching this thread" and
     the row of "Other" is not.
3. The teammate opens the room's thread list, opens the **Filters** panel and presses **Watching**.
   - *Expected*: the button is pressed; the address holds `watching=me`; only "Watched" is listed.
4. The teammate reloads the page.
   - *Expected*: the address still holds `watching=me`; only "Watched" is listed and the button is still pressed.
5. The teammate opens the thread "Watched" again (the remembered "Watching" filter still narrows the list) and presses "Watch this thread".
   - *Expected*: the button is released, the count reads "Watchers: 1" and the row leaves the filtered list.

## Scenario: the creator watches a new thread

1. The administrator mints a creator and a teammate; the creator makes a room through the API, adds the teammate and starts a thread. The creator signs in and opens it.
   - *Expected*: the button "Watch this thread" is already pressed; "Watchers: 1" is shown; the thread's row is described as "You are watching this thread".

## Scenario: the watching filter lists watched threads in the room and across rooms

1. The administrator mints a creator and a teammate; the creator makes two rooms through the API, adds the teammate to both and starts two threads in each ("WatchedA"
   and "OtherA" in the first, "WatchedB" and "OtherB" in the second). The teammate watches "WatchedA" and "WatchedB" through the API, then signs in.
2. The teammate opens the first room's **Search** tab (`/rooms/<id>/search`, by address) and turns on the **Threads I watch** switch, with the box empty.
   - *Expected*: the address holds `watching=1`; the page says "1 thread found" and lists only "WatchedA"; "OtherA" is not shown; no "matching message" line and no
     highlighted word appear (there is no search term).
3. The teammate opens the command palette from Home, types "watch" and picks **Threads I watch**.
   - *Expected*: the address is `/search?watching=1`; the switch is on; the page says "2 threads found" and lists "WatchedA" and "WatchedB" (each naming its room); neither
     "OtherA" nor "OtherB" is shown.
4. The teammate reloads the page, then turns the switch off.
   - *Expected*: after the reload both watched threads are still listed; after the switch is turned off the address no longer holds `watching` and no results are shown
     (a search needs a term when the switch is off).

## Scenario: the unread filter lists unread threads in the room and across rooms

1. The creator makes two rooms, adds the teammate, and creates one read control plus two threads that will receive unread replies. The teammate marks both rooms read, then the creator posts `#urgent` replies to one thread in each room.
2. The teammate opens the first room's Search tab and turns on **Unread** with an empty query.
   - *Expected*: the address holds `unread=me`; only the thread with the reply is listed, with no text-match requirement.
3. The teammate searches for `status:open hashtag:urgent` while **Unread** stays on.
   - *Expected*: the same unread thread remains and the URL retains `unread=me`; the structured clauses and unread state are combined with AND.
4. The teammate opens `/search?unread=me`.
   - *Expected*: **Unread** is on and exactly the two unread threads are listed, one from each room; the read control is absent.
