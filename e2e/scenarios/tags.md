# The ADMIN tag registry

- **Spec**: [tests/tags.spec.ts](../tests/tags.spec.ts)
- **Actors**: one throwaway administrator (`e2e-tags-admin-*`, `specAdmins`: curates the category through the UI and makes the competing save through the
  API); one throwaway regular user, the Reader, who reads the registry and is refused the page and the writes
- **Owns** (exclusive server-side state): the throwaway administrator, the Reader (unique `E2E Tag Reader-*` name) and one throwaway category
  (unique `E2E Category-*` name, `e2e-tag-*` tags), all deleted through the API after each test (the category under its current revision, then the
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

## Not covered here (and why)

- **Retired tags, revival of a retired tag, one category per tag, name clashes, the 100/100 caps, normalisation, the race of two admins** - pinned by
  the server suite (`TagCategoryTest`) and the component tests (`Tags`, `tagCategoryForm`); the journey proves the happy path, the stale-revision words
  and the page's absence for a member.
- **Putting a tag on a thread** - the next increment (1.2 S7).
