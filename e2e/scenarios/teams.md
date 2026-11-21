# Teams (the flat-teams registry)

- **Spec**: [tests/teams.spec.ts](../tests/teams.spec.ts)
- **Actors**: its own throwaway administrator (`e2e-teams-admin-*`, `specAdmins`); one throwaway user per test created
  through the Users page
- **Owns** (exclusive server-side state): its throwaway administrator (`e2e-teams-admin-*`, minted through the seed administrator's API in a `beforeAll` and deleted in an `afterAll`, after the file's own teardown; its sign-ins' live-ticket mints, one per page load, spend ITS per-user bucket, not the seed administrator's), its throwaway teams (unique `e2e-team-*` /
  `e2e-ro-team-*` / `e2e-roster-team-*` / `e2e-hnd-*` names and `e2e-h-*` handles) and users — all deleted by the end of the file (the roster test
  and handle tests tear down through the API in an `afterEach`, so a failing step leaves nothing behind)

## Scenario: admin creates a team, renames it, assigns its manager, and deletes it

1. The admin signs in and creates a throwaway user through the Users page.
2. On the Teams page they click **New team**, fill a unique name and a description, and Create.
   - *Expected*: the app lands on the new team's page — its name as the heading, "No manager".
3. They click **Edit**, change the name, and Save.
   - *Expected*: the heading shows the new name.
4. They click **Edit** again, search the **Manager** picker for the throwaway user, pick them, and Save.
   - *Expected*: the details page reads "Manager: <the user's name>".
5. Back on the Teams list they filter by the new name and delete the team from its row menu.
   - *Expected*: the row is gone; the throwaway user is deleted afterwards.

## Scenario: a regular user sees the read-only teams list

1. The admin creates a throwaway user and a throwaway team, then signs out.
2. The user signs in and opens the Teams page, filtering by the team's name.
   - *Expected*: the team is listed as a link; there is no **New team** button and no row menu.
3. They open the team.
   - *Expected*: the team page renders with its name as the heading and without the **Edit** button.
4. The admin signs back in and deletes the team and the user.

## Scenario: an administrator adds a member to a team's roster and removes them

1. The admin signs in, creates a throwaway user through the Users page and a throwaway team through **New team**.
   - *Expected*: the team's page has a **Members** section (h3) that reads "This team has no members yet".
2. They open the **Add a member** picker and type a single character.
   - *Expected*: the picker's description reads "Type at least 2 characters to search" and no option is listed.
3. They type the throwaway user's full name, pick "<name> (<email>)" and click **Add**.
   - *Expected*: the add answers 204 and the roster table lists the user's email.
4. They open the Teams list and filter by the team's name.
   - *Expected*: the team's **Members** count cell reads 1.
5. They open the team again and click **Remove <name>**, then confirm **Remove** in the dialog.
   - *Expected*: the remove answers 204 and the page is back to "This team has no members yet" with no roster table.
6. The throwaway team and user are deleted through the API (the `afterEach`).

## Scenario: an administrator gives a team a mention handle and a taken handle is refused

1. The admin signs in and, through **New team**, creates a team with a unique name and a unique lowercase handle.
   - *Expected*: the app lands on the team's page, which reads "Mention handle: @<handle>".
2. They open **New team** again, name a second team and type the same handle in uppercase, then Create.
   - *Expected*: the dialog stays open and reads "Use 2–30 lowercase letters, digits and single hyphens, starting with a letter" (the handle is never folded).
3. They type the first team's handle in lowercase and Create.
   - *Expected*: the server answers 409; the dialog stays open and the **Mention handle** field reads "A team with this handle already exists" (the name is not marked).
4. They type a free handle and Create.
   - *Expected*: the second team's page opens; the Teams list, filtered by the shared name stem, shows `@<handle>` and `@<handle>-b` in their rows.
5. Both teams are deleted through the API (the `afterEach`).

## Not covered here (and why)

- **A team manager's roster controls, the 200-member cap and a stranger's 403** — pinned by the server's `TeamMembersTest`
  and the SPA's `TeamDetails.test.tsx`; the journey drives the admin's happy path only.
- **Validation and conflicts (empty name, duplicate name, unknown manager)** — pinned by the
  server's `TeamTest` and the SPA's `Teams.test.tsx`/`TeamDetails.test.tsx`; the journey drives
  the happy path only.
