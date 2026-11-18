# Room workflow editor

- **Spec**: [tests/room-workflow.spec.ts](../tests/room-workflow.spec.ts)
- **Actors**: the seed administrator (`admin@adder.local`); throwaway regular users created through the Users page (a room's creator
  and owner, a Polish-speaking creator); the room's owner, through the API, seeds the retire scenario's threads
- **Owns** (exclusive server-side state): its throwaway rooms (unique `e2e-room-*` names) and its users, removed through the API in
  an `afterEach` (rooms first, then the users, so a failing step leaves nothing behind). The seeded Default and Incident templates are
  shared and read-only: rooms only copy them. Every room is created by a throwaway user, so the seed administrator is never a member
  of one and the per-user `room-name` bucket (30 a minute) is far from reached. The one write the administrator makes (a workflow PUT in
  the second scenario) goes to the spec's own room.

## Scenario: an owner reorders and recolours statuses, adds a closed status and a select field, and the workflow survives a reload

1. The admin creates a throwaway user and signs out; the user signs in, creates a room from the **Default** template and opens its
   **Workflow** tab.
   - *Expected*: the app is at `/rooms/:id/workflow`, the **Workflow** tab is current, and the editor lists the template's five
     statuses with ONE plain **Label** each (Open, In progress, Waiting, Done, Won't do). A saved status's **Key** is read-only and
     **Save** is disabled.
2. They click **Move status 3 up** and open the second status's **Colour** select to pick Orange.
   - *Expected*: Waiting is now the second row; the colour input reads "Orange — warning" (the colour is named in words).
3. They click **Add status**, label the sixth row "Cancelled" and choose the **Closed** category.
   - *Expected*: the key follows the label to `cancelled`.
4. They click **Add field**, label it "Impact", pick the type **Select**, add two options and label them "High" and "Low".
   - *Expected*: **Save** is enabled.
5. They click **Save**.
   - *Expected*: the PUT answers 204 and the form is clean again (**Save** disabled: it re-seeded from the new revision); focus is on the "Room workflow" group.
6. They reload the page.
   - *Expected*: Waiting is the second status, still Orange; In progress is third; "Cancelled" is the sixth, Closed; Field 1 is
     "Impact" of type Select with the options "High" and "Low".
7. They open the **Threads** tab, click **New thread** and open **More fields**.
   - *Expected*: the form offers the saved field "Impact" (a Select).

## Scenario: a stale workflow save is refused and a reload recovers

1. A throwaway owner creates a room from the **Default** template, opens its **Workflow** tab and edits the first status's label to
   "Opened" without saving.
   - *Expected*: the draft holds the edit.
2. Through the API the administrator (who may manage any room) reads the workflow and saves it again under its revision, with
   "In progress" renamed "Doing".
   - *Expected*: that PUT answers 204 (the revision moved on).
3. The owner clicks **Save**.
   - *Expected*: the PUT answers 409 `revision-conflict`; an alert "This workflow was changed elsewhere" appears and the first
     status still reads "Opened" (the draft is kept).
4. They click **Reload the latest version**.
   - *Expected*: the second status reads "Doing", the first reads "Open" again (their unsaved edit is gone), the alert is gone and
     **Save** is disabled.
5. They edit the first label to "Opened" again and click **Save**, then reload the page.
   - *Expected*: the PUT answers 204 (it names the newer revision); after the reload the first status reads "Opened" and the
     second "Doing".

## Scenario: a room created by a Polish-speaking user gets the Polish labels of the template

1. The admin creates a throwaway user and signs out. The user signs in through the form and switches the UI to Polish in the account
   menu.
   - *Expected*: the language saves server-side (PUT answers 200) and the sidebar reads "Strona główna".
2. They create a room from the **Incident** template through the Polish create form.
   - *Expected*: the create answers 201 and the app opens the room.
3. They open the **Przepływ pracy** tab.
   - *Expected*: the five statuses' labels (**Etykieta**) are the template's Polish ones: Triaż, Analiza, Mitygacja, Monitorowanie,
     Rozwiązane; the first field (**Pole 1**) is "Ważność".

## Scenario: retiring a status that threads use asks for a replacement and moves the threads

1. A throwaway owner creates a room from the **Default** template. Through the API (signed in as that owner) two threads are
   started and each is moved to **Waiting**, an OPEN status; the owner opens the room's **Workflow** tab and reloads it.
   - *Expected*: the thread POSTs answer 201 and the moves 204; Waiting is the third status.
2. They click **Remove status 3** on Waiting.
   - *Expected*: the dialog "Move threads before removing" reads "2 threads are in “Waiting”. Move them to:"; **Remove status** is
     disabled until a target is picked; the **Move to** select offers only the other OPEN statuses (Done is not offered).
3. They pick **In progress** and click **Remove status**.
   - *Expected*: the dialog closes and a "Statuses to be removed" group says "“Waiting” will be removed; its 2 threads move to
     “In progress”." Nothing is saved yet.
4. They click **Save**.
   - *Expected*: the PUT answers 204, the pending-removal group is gone and the form re-seeded with four statuses (Done is third).
5. They open the **Threads** tab.
   - *Expected*: both threads' rows in the thread list show **In progress** and no longer Waiting.
6. They open each thread (and its **Details** panel where it is folded) and read its **History**.
   - *Expected*: three entries each; the newest reads "Waiting was retired; the thread moved to In progress", the one before it
     "Moved the thread from Open to Waiting".

## Not covered here (and why)

- **The `409 status-in-use` follow-up** (threads joining a status after the page was opened, so the dialog re-opens on save): a race
  that needs a second writer between two clicks; it is covered by the web unit tests.
- **A plain member's and an archived room's read-only editor** are covered by the web unit tests; the axe sweep scans the editable page and an archived room's read-only view (a plain text list, not disabled controls).
