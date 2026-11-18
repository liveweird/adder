# Rooms

- **Spec**: [tests/rooms.spec.ts](../tests/rooms.spec.ts)
- **Actors**: the seed administrator (`admin@adder.local`); throwaway regular users created through the Users page
  (a room's creator and owner, an outsider)
- **Owns** (exclusive server-side state): its throwaway rooms (unique `e2e-room-*` names) and users, removed through the
  API in an `afterEach` (rooms first, so a failing step leaves nothing behind). The seeded Default and Incident templates
  are shared and read-only: rooms only copy them. Every room is created by a throwaway user, so the seed administrator is
  never a member of one, and the per-user `room-create` bucket (30 a minute) is far from reached.

## Scenario: a user creates a room from the Incident template and finds it in the rooms rail

1. The admin creates a throwaway user and signs out; the user signs in.
   - *Expected*: the sidebar's **Rooms** section shows the rail's empty line "You are not in any room yet".
2. They click **All rooms**, then **New room**.
   - *Expected*: the app is at `/rooms` (h2 "Rooms"), then the h2 reads "New room".
3. They fill a unique `e2e-room-*` name and a description and pick the **Incident** workflow template.
   - *Expected*: the picker previews the template's five statuses (Triage, Investigating, Mitigating, Monitoring, Resolved).
4. They click **Create**.
   - *Expected*: the create answers 201 and the app opens the room: its h2 is the name, the description and "1 member"
     show, the placeholder "Threads arrive next" is there, and the workflow summary lists Triage under Open and Resolved
     under Closed, with the fields Severity, Component, Jira link and ETA.
5. They look at the sidebar rail.
   - *Expected*: the room is a link in the **Rooms** section, `aria-current="page"` while open (and still after the
     **Settings** tab is opened), and **All rooms** is not marked current.
6. They open **All rooms** again and filter by the room's name.
   - *Expected*: the room's row reads Owner in its role cell and 1 in its members cell.

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
   - *Expected*: the room's row appears with "—" as the role (the admin has none); opening it shows the room with its name
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

## Not covered here (and why)

- **The owner adds a person and a team, hands over ownership and leaves** — the members journey, `rooms.spec.ts` test 2
  arrives with the members page.
- **Name clashes, validation, a template deleted mid-create, the last-owner rule, the room-create bucket (429)** — pinned by
  the server's `RoomTest` and the SPA's `CreateRoom.test.tsx` / `RoomSettings.test.tsx`; the journeys drive the happy paths.
- **The rail in the icon-rail mode, the "more rooms" link and the command palette's Rooms group** — pinned by `App.test.tsx`
  and `CommandPalette.test.tsx` (a list longer than 100 rooms is not worth minting here).
