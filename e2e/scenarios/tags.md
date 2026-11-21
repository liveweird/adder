# The ADMIN tag registry

- **Spec**: [tests/tags.spec.ts](../tests/tags.spec.ts)
- **Actors**: one throwaway administrator (`e2e-tags-admin-*`, `specAdmins`: curates the category through the UI and makes the competing save through the
  API); one throwaway regular user, the Reader, who reads the registry and is refused the page and the writes
- **Owns** (exclusive server-side state): the throwaway administrator, the Reader (unique `E2E Tag Reader-*` name) and one throwaway category
  (unique `E2E Category-*` name, `e2e-tag-*` tags) and, for the thread scenarios, an owner, a teammate and an `e2e-tags-room-*` room with two threads (the room copies the seeded Default template; threads are never deleted, a room's removal hides them), all deleted through the API after each test (the category under its current revision, then the
  user). The tag registry is SHARED, append-only state: the seeded **Kind** and **Area** categories are only read, never edited or deleted, and a tag
  name is unique for good (even once retired), so every tag carries a per-run suffix.

## Scenario: an admin curates a tag category

1. The administrator signs in and follows **Tags** in the Administration section of the sidebar.
   - *Expected*: the URL is `/tags` and the h2 reads "Tags"; the seeded **Kind** row shows `postmortem` and the **Area** row `infrastructure` as pills.
2. They press **New category**, fill a unique name and a description, type two tags (Enter after each; the second in capitals) and press **Create**.
   - *Expected*: the POST answers 201 and the dialog closes; the table row shows the category with both tags as pills, the capitalised one folded to
     lower case.
3. From the row's **Operations** menu they choose **Edit**, rename the category, add a third tag and press **Save**.
   - *Expected*: the PUT answers 204 and the dialog closes; the renamed row lists all three tags.
4. They open the editor again; meanwhile another administrator saves the same category through the API. They change the name and press **Save**.
   - *Expected*: the dialog stays open and says another administrator changed the category after it was opened; **Cancel** closes it and the stored name
     is the other administrator's.
5. They choose **Delete** from the row's menu.
   - *Expected*: the confirmation says the tags are marked as retired on the threads that carry them; confirming answers 204 and the row and its tags
     leave the table.

## Scenario: a regular user reads the registry through the API but has no Tags page and no writes

1. The Reader signs in.
   - *Expected*: the Administration section has a **Teams** link and no **Tags** link; opening `/tags` lands on Home.
2. Through the API they read the registry and try to create a category.
   - *Expected*: the read is `200` and lists the seeded **Kind** (five tags, alphabetical) and **Area** categories; the create is `403`.

## Scenario: a member tags a thread, the list filter finds it and the API refuses an unknown tag

1. A member of a room opens a thread of it (the owner started it; a second thread stays untagged), opens **Details** and finds the **Tags** section.
   - *Expected*: it reads "No tags" and has an **Edit tags** button.
2. They press **Edit tags**, open the grouped picker, pick one registry tag, close the list and press **Save tags**.
   - *Expected*: the PUT answers 204; the tag shows as a pill in the section, in the header's tag row and on the thread's list row, and the history says "Added: " with the tag.
3. They open the room's filter panel and pick the same tag in the **Tags** control.
   - *Expected*: the address holds `threadTag=<tag>`; the list keeps only the tagged thread; asking for the category's other tag lists neither.
4. Through the API they put a set that holds the tag and a name the registry does not hold.
   - *Expected*: 400; the thread's tags and revision are unchanged.

## Scenario: a closed thread takes tags from its owner only

1. The thread's owner closes the thread (through the API); a plain member opens it and its **Tags** section.
   - *Expected*: the section says only the owner, the room owner or an administrator can change the tags and has no **Edit tags** button; the member's API PUT (no header, a body) is a 403.
2. The owner signs in, opens the same section, picks a tag and saves.
   - *Expected*: the tag shows as a pill and the thread is still closed.

## Not covered here (and why)

- **Retired tags, revival of a retired tag, one category per tag, name clashes, the 100/100 caps, normalisation, the race of two admins** - pinned by
  the server suite (`TagCategoryTest`) and the component tests (`Tags`, `tagCategoryForm`); the journey proves the happy path, the stale-revision words
  and the page's absence for a member.
- **The retired-tag cue, the cap of 20, the revision conflict and the audit/history/hint details** - pinned by the server suite (`ThreadTagsTest`, `ThreadTagFilterTest`, `NotifyHintsTest`) and the component tests (`ThreadTagsEditor`, `ThreadTagsView`, `ThreadFiltersTags`).
