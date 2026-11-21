# People (the profile any member can open)

- **Spec**: [tests/people.spec.ts](../tests/people.spec.ts)
- **Actors**: its own throwaway administrator (`e2e-people-op-*`, `specAdmins`, who mints the others and is also, in the second scenario, the person whose profile is read); a
  throwaway "viewer" and "person" (plain users) in the first scenario, a throwaway viewer and a throwaway "removed" user in the second
- **Owns** (exclusive server-side state): its throwaway administrator (minted through the seed administrator's API in a `beforeAll` and deleted in an `afterAll`, after the file's
  own teardown; its API sessions spend ITS per-user buckets), its throwaway users (`E2E People *`, minted through the administrator's API), one throwaway team (`e2e-people-team-*`) with the
  person on its roster and two throwaway rooms (`e2e-people-shared-*`, `e2e-people-private-*`, created through the users' own API sessions from a copy of the shared, read-only seeded Default
  template), all removed through the API in an `afterEach` (rooms and the team first, then the users). The viewer's profile reads spend the per-user `directory` bucket (120 a minute) of a
  throwaway account, far below it.

Nothing links to a profile until names become links (the next step of 1.2.0), so the viewer opens the address directly. What each list holds is the server's scope, so the assertions read
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
