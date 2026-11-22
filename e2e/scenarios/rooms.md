# Rooms

- **Spec**: [tests/rooms.spec.ts](../tests/rooms.spec.ts)
- **Actors**: its own throwaway administrator (`e2e-rooms-admin-*`, `specAdmins`); throwaway regular users created through the Users page
  (a room's creator and owner, an outsider, the people the owner adds)
- **Owns** (exclusive server-side state): its throwaway administrator (`e2e-rooms-admin-*`, minted through the seed administrator's API in a `beforeAll` and deleted in an `afterAll`, after the file's own teardown; its sign-ins' live-ticket mints, one per page load, spend ITS per-user bucket, not the seed administrator's), its throwaway rooms (unique `e2e-room-*` names), one throwaway team
  (`e2e-room-team-*`, seeded through the API) and its users, removed through the API in an `afterEach` (rooms first, then
  the team, then the users, so a failing step leaves nothing behind). The seeded Default and Incident templates
  are shared and read-only: rooms only copy them. Every room is created by a throwaway user, so the seed administrator is
  never a member of one, and the per-user `room-name` bucket (30 a minute, shared by creating and renaming) and the `room-members` bucket (60 a minute, on the member add) are far from reached.

## Scenario: a user creates a room from the Incident template and finds it in the rooms rail

1. The admin creates a throwaway user and signs out; the user signs in.
   - *Expected*: the sidebar's **Rooms** section shows the rail's empty line "You are not in any room yet".
2. They click **All rooms**, then **New room**.
   - *Expected*: the app is at `/rooms` (h2 "Rooms"), then the h2 reads "New room".
3. They fill a unique `e2e-room-*` name and a description and pick the **Incident** workflow template.
   - *Expected*: the picker previews the template's five statuses (Triage, Investigating, Mitigating, Monitoring, Resolved).
4. They click **Create**.
   - *Expected*: the create answers 201 and the app opens the room: its h2 is the name and "1 member" shows in the compact header;
     the description is not on the page until **About this room** opens it (Escape closes it again); the **Threads** tab is current, the thread list is empty ("No open threads") and the pane says "Pick a thread".
     They click **New thread**: the app is at `/rooms/<id>/threads/new`, the **Threads** tab is still current, and after
     **More fields** the form offers the copied fields Severity, Component, Jira link and ETA (all optional in Incident).
     They open the **Workflow** tab: Status 1 is "Triage" with the Open category checked, Status 5 is "Resolved" with the
     Closed category checked (the copied statuses and their categories).
5. They look at the sidebar rail.
   - *Expected*: the room is a link in the **Rooms** section, `aria-current="page"` while open (and still after the
     **Settings** tab is opened), and **All rooms** is not marked current.
6. They open **All rooms** again and filter by the room's name.
   - *Expected*: the room's row shows an Owner badge beside its name and 1 in its members cell.
7. They narrow the viewport to phone width.
   - *Expected*: the result still shows its Owner badge, and the card labels its member count "Members".

## Scenario: the owner adds a person and a whole team, hands over ownership and leaves

1. The admin creates three throwaway users (an owner, a person, a teammate) and, through the API, a team holding the person
   and the teammate; then signs out. The owner signs in, creates a room from the Default template and opens its **Members**
   tab.
   - *Expected*: the app is at `/rooms/:id/members`, the **Members** tab is current, and the table lists one row: the owner,
     with the role Owner and a "You" marker.
2. They activate **Add people** from the header with the keyboard. Focus moves to the add section. They search the **Add a person** picker for the person's name, pick the "name (email)" option and click **Add person**.
   - *Expected*: the add answers 200; the person's row appears with the role Member and the header reads "2 members". At phone width the member card contains the row actions button.
3. They search the **Add a whole team** picker for the team, pick it and click **Add team**.
   - *Expected*: a dialog "Add the team?" says the team has 2 people; confirming answers 200 and closes it. The teammate's
     row appears as Member (the person, who already belongs, is skipped), the header reads "3 members" and the table holds
     the three members.
4. They click **Leave room** and confirm.
   - *Expected*: the leave answers 409 and an inline alert says "You are the only owner of this room. Make someone else an
     owner before you leave."; the page stays on the members list.
5. They open the person's row menu and choose **Make owner**.
   - *Expected*: the role change answers 204 and the person's row now reads Owner.
6. They click **Leave room** again and confirm.
   - *Expected*: the leave answers 204, the app lands on `/rooms`, the room is gone from the rail, and the room's URL now
     shows "This room does not exist, or you are not a member of it."
7. The person signs in.
   - *Expected*: the room is in their rail; its members page lists the person and the teammate but not the former owner,
     and shows the **Add a person** picker (the new owner has the controls).
8. The teammate signs in and opens the members page.
   - *Expected*: the list is read-only: no add pickers and no row menus, and **Leave room** is offered in the **Members** section's header.

## Scenario: a non-member cannot open a room while an administrator can

1. The admin creates two throwaway users (an owner and an outsider) and signs out. The owner signs in and creates a room
   from the Default template.
2. The outsider signs in and opens the room's URL.
   - *Expected*: the page says "This room does not exist, or you are not a member of it." under the h2 "Rooms" (the same
     answer as for an unknown id), and the room is not in the rail.
3. They open the Rooms page and filter by the room's name.
   - *Expected*: "No rooms yet".
4. The administrator signs in, opens the Rooms page and filters by the name.
   - *Expected*: the default **Mine** scope is empty ("No rooms yet", the admin is not a member).
5. They switch to **All**.
   - *Expected*: the room's row appears with "Your role: —" below its name (the admin has no role); opening it shows the room with its name
     as the h2, no "not a member" message, and the room still absent from the admin's own rail.

## Scenario: an owner archives and unarchives a room and an administrator deletes it

1. The admin creates a throwaway user and signs out; the user signs in, creates a room and opens its **Settings** tab.
   - *Expected*: the name field is editable and there is no **Delete room** button (deleting is the administrator's).
2. They click **Archive room** and confirm.
   - *Expected*: the archive answers 204; the page shows the "Archived" badge, the name field is read-only, **Unarchive room**
     is offered and the room has left the rail's active list.
3. They switch on the rail's **Show archived** and reload.
   - *Expected*: the room is listed in the rail's "Archived rooms" group (current while open), and still is after the reload.
4. They click **Unarchive room** and confirm.
   - *Expected*: the unarchive answers 204; the name field is editable again and **Archive room** is back.
5. The user signs out; the administrator signs in and opens the room's settings.
   - *Expected*: the admin (not a member) sees the room and a **Delete room** button.
6. They click **Delete room** and confirm **Delete**.
   - *Expected*: the delete answers 204, the app lands on `/rooms`, and the room's URL now shows "This room does not exist,
     or you are not a member of it."

## Scenario: the room tabs stay in view and open their controls on a phone

1. An owner creates an Incident room and narrows the browser to 390 × 844.
2. They open **Search**, **Members**, **Workflow**, and **Settings** in turn from the room navigation.
   - *Expected* — Each link fits on screen, takes the reader to its own route, and is the only current link. The search box,
     member heading, status editor, and room name field load on their respective pages. The page does not scroll sideways.

## Not covered here (and why)

- **Name clashes, validation, a template deleted mid-create, the `room-name` and `room-members` buckets (429)** — pinned by
  the server's `RoomTest` and the SPA's `CreateRoom.test.tsx` / `RoomSettings.test.tsx`; the journeys drive the happy paths.
- **Removing someone else, demoting an owner, a full room (409), an empty team, the members filters and sort** — pinned by
  the server's `RoomMembersTest` and the SPA's `RoomMembers.test.tsx`; the journey covers add, hand-over and leave.
- **The rail in the icon-rail mode, the "more rooms" link and the command palette's Rooms group** — pinned by `App.test.tsx`
  and `CommandPalette.test.tsx` (a list longer than 100 rooms is not worth minting here).
