# Workflow templates (the ADMIN registry)

- **Spec**: [tests/workflow-templates.spec.ts](../tests/workflow-templates.spec.ts)
- **Actors**: its own throwaway administrator (`e2e-workflow-templates-admin-*`, `specAdmins`); one throwaway regular user
  created through the Users page
- **Owns** (exclusive server-side state): its throwaway administrator (`e2e-workflow-templates-admin-*`, minted through the seed administrator's API in a `beforeAll` and deleted in an `afterAll`, after the file's own teardown; its sign-ins' live-ticket mints, one per page load, spend ITS per-user bucket, not the seed administrator's), its throwaway templates (unique `e2e-tpl-*` names) and
  user, removed through the API in an `afterEach` so a failing step leaves nothing behind. The seeded
  Default and Incident templates are shared and read-only: this spec (and every other) never edits or
  deletes them.

## Scenario: an administrator creates a workflow template, edits its labels in both languages and deletes it

1. The admin signs in and clicks the **Workflow templates** link in the Administration section.
   - *Expected*: the app is at `/workflow-templates` and its h2 reads "Workflow templates".
2. They click **New template**.
   - *Expected*: the h2 reads "New workflow template"; the form starts with two statuses whose English
     labels are "Open" and "Done".
3. They fill a unique `e2e-tpl-*` name and a description, type a Polish label for the first status, add a
   third status labelled "Review" and click **Create**.
   - *Expected*: the create answers 201 and the app returns to the list.
4. They filter the list by the name.
   - *Expected*: the row's Statuses cell reads 3. They open the template through its name link: the h2 reads
     "Edit workflow template", the first status carries its Polish label, its key is read-only and **Save** is
     disabled while nothing is changed.
5. They change the second status's English label to "Resolved" and its Polish label to "Rozwiązane".
   - *Expected*: **Save** becomes enabled; saving answers 204 and the app returns to the list.
6. They reopen the template.
   - *Expected*: both labels survived the round trip and the second status's key is still `done` (a saved
     key does not follow its label).
7. Back on the list, filtered by the name, they delete the template from its row menu and confirm.
   - *Expected*: the delete answers 204 and the row is gone.

## Scenario: a regular user picks a template when creating a room but has no template pages

1. The admin creates a throwaway regular user and signs out; the user signs in.
   - *Expected*: the sidebar's Administration section shows Teams and no **Workflow templates** link.
2. The user opens `/workflow-templates`, `/workflow-templates/new` and `/workflow-templates/1/edit` by URL.
   - *Expected*: each lands on Home (`/`, h2 "Adder") and the link is still absent.
   - *Expected*: no request to `/api/v1/workflow-templates` was made by the browser (the pages' queries stay
     disabled for a regular user).
3. They open **All rooms** in the sidebar's Rooms section, click **New room** and open the **Workflow template** picker.
   - *Expected*: the h2 reads "New room"; the picker lists the seeded **Default** template; picking **Incident** previews
     its statuses (the group "Statuses" contains Triage). The picker's reads are the only template requests the browser made,
     and the **Workflow templates** nav link is still absent. Nothing is created.

## Not covered here (and why)

- **Creating the room, a name clash or a template deleted mid-create** — `rooms.spec.ts` creates rooms (from the Incident
  template); the clash and the 400 are pinned by the server's `RoomTest` and the SPA's `CreateRoom.test.tsx`.
- **A stale save (409 `revision-conflict`), the name clash and every validation message** — pinned by the
  server's `WorkflowTemplateTest` and the SPA's `EditWorkflowTemplate.test.tsx` / `WorkflowTemplateForm.test.tsx`;
  the journey drives the happy path only.
- **Reordering, colours, fields and options in the editor** — pinned by `WorkflowEditor.test.tsx` and the row
  tests; the room workflow journey (`room-workflow.spec.ts`) drives them end to end.
