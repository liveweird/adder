# Room workflow editor

- **Spec**: [tests/room-workflow.spec.ts](../tests/room-workflow.spec.ts)
- **Actors**: the seed administrator (`admin@adder.local`); throwaway regular users created through the Users page (a room's creator
  and owner, a Polish-speaking creator)
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
7. They open the **Overview** tab.
   - *Expected*: the workflow summary lists "Cancelled" under Closed and reads "Fields: Impact".

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

## Not covered here (and why)

- **The retire dialog** (removing a status that threads use, and the `status-in-use` follow-up): there are no threads in 0.2, so no
  status has a count; the dialog is covered by the web unit tests and its e2e arrives with the threads (0.3).
- **A plain member's and an archived room's read-only editor** are covered by the web unit tests; the axe sweep scans the editable page and an archived room's read-only view (a plain text list, not disabled controls).
