# People (the profile any member can open)

- **Spec**: [tests/people.spec.ts](../tests/people.spec.ts)
- **Actors**: its own throwaway administrator (`e2e-people-op-*`, `specAdmins`, who mints the others and is also, in the second scenario, the person whose profile is read); a
  throwaway "viewer" and "person" (plain users) in the first scenario, a throwaway viewer and a throwaway "removed" user in the second, a throwaway "reader" and "author" in the third
- **Owns** (exclusive server-side state): its throwaway administrator (minted through the seed administrator's API in a `beforeAll` and deleted in an `afterAll`, after the file's
  own teardown; its API sessions spend ITS per-user buckets), its throwaway users (`E2E People *`, minted through the administrator's API), one throwaway team (`e2e-people-team-*`) with the
  person on its roster and two throwaway rooms (`e2e-people-shared-*`, `e2e-people-private-*`, and the third scenario's `e2e-people-link-room-*`, created through the users' own API sessions from a copy of the shared, read-only seeded Default
  template), all removed through the API in an `afterEach` (rooms and the team first, then the users). The viewer's profile reads spend the per-user `directory` bucket (120 a minute) of a
  throwaway account, far below it.

The first two scenarios open the address directly; the third follows the names that link to a profile (1.2 D6). What each list holds is the server's scope, so the assertions read
the page the server drew.

## Scenario: a member opens a person's profile and sees the rooms they share

1. The administrator mints a viewer and a person through the API. The viewer creates a room and adds the person; the person creates a second room of their own. The administrator
   creates a team and puts the person on its roster.
2. The viewer signs in and opens `/people/<the person's id>`.
   - *Expected*: the page's heading (h2) is the person's name and an "Email <address>" link is shown; the **Teams** list holds the team as a link; the **Rooms you share** list
     holds the shared room and NOT the person's private room; there is no **Edit user** link; an axe scan of the main area finds no WCAG A/AA violation.
3. The viewer clicks the shared room's link.
   - *Expected*: the address is that room's and its name is the page heading.

## Scenario: a profile shows no role and an unknown id says so

1. The administrator mints a viewer and a user to be removed. The viewer signs in and opens the profile of the administrator account.
   - *Expected*: the heading is that account's name (read from the directory API); the page says "You share no room with this person."; nothing in the main area mentions an
     administrator, a role, a language, feature flags or MFA; there is no **Edit user** link.
2. The viewer's API session reads the same person.
   - *Expected*: `GET /users/directory/{id}` answers 200 with exactly the keys `email`, `id` and `name`; `GET /users/{id}` answers 403.
3. The viewer opens `/people/2000000000`.
   - *Expected*: the heading "This person is not available" and "The account may have been removed, or the link is wrong."; the directory API answers 404.
4. The administrator deletes the throwaway user and the viewer opens that user's profile.
   - *Expected*: the same "This person is not available" page; the directory API answers the same 404.

## Scenario: a message author, a mention and a notice lead to the person and the room

1. The administrator mints a reader and an author. The author creates a room, adds the reader, starts a thread and posts a message that mentions the reader (`<@id>`). The reader
   signs in onto the thread.
2. The reader clicks the author's name in the message's own line.
   - *Expected*: the address is `/people/<the author's id>` and the page's heading is the author's name; going back lands on the thread with the message.
3. The reader clicks the mention in the body (it reads `@<the reader's name>`).
   - *Expected*: the address is `/people/<the reader's id>` with their name as the heading; going back lands on the thread again.
4. The thread's header names the owner (the author) as a link to `/people/<the author's id>`.
5. The reader opens the bell's drawer and clicks the room's name in the author's notice.
   - *Expected*: the drawer closes, the address is that room's and its name is the page heading.
   - *Expected* (after the room link): focus is on the main region (`#main-content`), not on `<body>`.
6. The reader opens the drawer again, sees the notice ("<author> mentioned you in #N …") and clicks in the padding at the row's left edge, outside every word.
   - *Expected*: the stretched notice link opens the notice: the address is the thread's with `message=`, and the drawer closes.
7. The reader opens the drawer once more, focuses the author's name in the notice row and presses Enter.
   - *Expected*: the drawer closes, the address is the author's profile, whose heading is their name, and focus is on the main region, not on `<body>`.
