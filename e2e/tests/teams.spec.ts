// The flat-teams registry: an admin creates a team, renames it, assigns its manager and deletes it;
// a regular user sees the read-only list; an admin manages a team's roster. Owns: its throwaway team(s) and user (unique names, deleted by the end of the file).
import { createUserViaUi, deleteUserRow, deleteViaApi, expect, login, openFilters, rowOperation, signOut, specAdmins, test, uniqueText } from "./helpers";

/** This file's own administrator (see `specAdmins`): the page loads of its sign-ins spend this account's live-ticket bucket, not the seed administrator's. */
const admin = specAdmins("e2e-teams-admin");

test("admin creates a team, renames it, assigns its manager, and deletes it", async ({ page }) => {
  await admin.login(page);
  const member = await createUserViaUi(page, "E2E Team Member");
  const teamName = uniqueText("e2e-team");

  // Create through the modal — a new team lands on its own page.
  await page.goto("/teams");
  await expect(page.getByRole("heading", { name: "Teams" })).toBeVisible();
  await page.getByRole("button", { name: "New team" }).click();
  const dialog = page.getByRole("dialog");
  await dialog.getByLabel("Name").fill(teamName);
  await dialog.getByLabel("Description").fill("Playwright's own team");
  await dialog.getByRole("button", { name: "Create", exact: true }).click();
  await expect(page).toHaveURL(/\/teams\/\d+$/);
  await expect(page.getByRole("heading", { name: teamName })).toBeVisible();
  await expect(page.getByText("No manager")).toBeVisible();

  // Rename from the details page.
  const renamed = `${teamName}-renamed`;
  await page.getByRole("button", { name: "Edit", exact: true }).click();
  await page.getByRole("dialog").getByLabel("Name").fill(renamed);
  await page.getByRole("dialog").getByRole("button", { name: "Save", exact: true }).click();
  await expect(page.getByRole("heading", { name: renamed })).toBeVisible();
  await expect(page.getByRole("dialog")).toHaveCount(0);

  // Assign the throwaway user as the team's manager from the editor's searchable picker.
  await page.getByRole("button", { name: "Edit", exact: true }).click();
  const editor = page.getByRole("dialog");
  const managerPicker = editor.getByRole("combobox", { name: "Manager" });
  await managerPicker.click();
  await managerPicker.fill(member.name);
  await page.getByRole("option", { name: `${member.name} (${member.email})` }).click();
  await Promise.all([
    page.waitForResponse((r) => r.request().method() === "PUT" && /\/api\/v1\/teams\/\d+$/.test(r.url()) && r.ok()),
    editor.getByRole("button", { name: "Save", exact: true }).click(),
  ]);
  await expect(page.getByText(`Manager: ${member.name}`)).toBeVisible();

  // The list shows it (filtered by name); delete from the row menu.
  await page.goto("/teams");
  await openFilters(page);
  await page.getByLabel("Name", { exact: true }).fill(renamed);
  await expect(page.getByRole("link", { name: `Open team ${renamed}` })).toBeVisible();
  await rowOperation(page, renamed, `Delete ${renamed}`);
  await Promise.all([
    page.waitForResponse((r) => r.request().method() === "DELETE" && /\/api\/v1\/teams\/\d+$/.test(r.url()) && r.ok()),
    page.getByRole("dialog").getByRole("button", { name: "Delete", exact: true }).click(),
  ]);
  await expect(page.getByRole("link", { name: `Open team ${renamed}` })).toHaveCount(0);

  await deleteUserRow(page, member.name);
});

test("a regular user sees the read-only teams list", async ({ page }) => {
  await admin.login(page);
  const user = await createUserViaUi(page, "E2E Team Reader");
  const teamName = uniqueText("e2e-ro-team");
  await page.goto("/teams");
  await page.getByRole("button", { name: "New team" }).click();
  await page.getByRole("dialog").getByLabel("Name").fill(teamName);
  await page.getByRole("dialog").getByRole("button", { name: "Create", exact: true }).click();
  await expect(page).toHaveURL(/\/teams\/\d+$/);
  await signOut(page);

  await login(page, user.email, user.password);
  await page.goto("/teams");
  await openFilters(page);
  await page.getByLabel("Name", { exact: true }).fill(teamName);
  await expect(page.getByRole("link", { name: `Open team ${teamName}` })).toBeVisible();
  await expect(page.getByRole("button", { name: "New team" })).toHaveCount(0);
  await expect(page.getByRole("button", { name: `Operations for ${teamName}` })).toHaveCount(0);
  await page.getByRole("link", { name: `Open team ${teamName}` }).click();
  await expect(page.getByRole("heading", { name: teamName })).toBeVisible();
  await expect(page.getByRole("button", { name: "Edit", exact: true })).toHaveCount(0);
  await signOut(page);

  // Cleanup as the admin.
  await admin.login(page);
  await page.goto("/teams");
  await openFilters(page);
  await page.getByLabel("Name", { exact: true }).fill(teamName);
  await rowOperation(page, teamName, `Delete ${teamName}`);
  await Promise.all([
    page.waitForResponse((r) => r.request().method() === "DELETE" && /\/api\/v1\/teams\/\d+$/.test(r.url()) && r.ok()),
    page.getByRole("dialog").getByRole("button", { name: "Delete", exact: true }).click(),
  ]);
  await deleteUserRow(page, user.name);
});

test.describe("roster", () => {
  // API teardown, so a failing step leaves neither the team nor the user behind (the team first: it references the user).
  const cleanup: string[] = [];
  test.afterEach(async () => {
    await deleteViaApi(cleanup.splice(0));
  });

  test("an administrator adds a member to a team's roster and removes them", async ({ page }) => {
    await admin.login(page);
    const member = await createUserViaUi(page, "E2E Roster Member");
    cleanup.unshift(`/api/v1/users/${member.id}`);
    const teamName = uniqueText("e2e-roster-team");

    await page.goto("/teams");
    await page.getByRole("button", { name: "New team" }).click();
    await page.getByRole("dialog").getByLabel("Name").fill(teamName);
    await page.getByRole("dialog").getByRole("button", { name: "Create", exact: true }).click();
    await expect(page).toHaveURL(/\/teams\/\d+$/);
    cleanup.unshift(`/api/v1/teams/${/\/teams\/(\d+)$/.exec(page.url())![1]}`);
    await expect(page.getByRole("heading", { level: 3, name: "Members" })).toBeVisible();
    await expect(page.getByText("This team has no members yet")).toBeVisible();

    // The directory needs two characters: one lists nothing (the hint is the box's description), the full name finds the user by name and email.
    const picker = page.getByRole("combobox", { name: "Add a member" });
    await picker.click();
    await picker.fill(member.name.slice(0, 1));
    await expect(picker).toHaveAccessibleDescription("Type at least 2 characters to search");
    await expect(page.getByRole("option")).toHaveCount(0);
    await picker.fill(member.name);
    await page.getByRole("option", { name: `${member.name} (${member.email})` }).click();
    await Promise.all([
      page.waitForResponse((r) => r.request().method() === "PUT" && /\/api\/v1\/teams\/\d+\/members\/\d+$/.test(r.url()) && r.status() === 204),
      page.getByRole("button", { name: "Add", exact: true }).click(),
    ]);
    const roster = page.getByRole("table", { name: `Members of ${teamName}` });
    await expect(roster.getByText(member.email)).toBeVisible();

    // The teams list counts them.
    await page.goto("/teams");
    await openFilters(page);
    await page.getByLabel("Name", { exact: true }).fill(teamName);
    const teamRow = page.getByRole("row").filter({ has: page.getByRole("link", { name: `Open team ${teamName}` }) });
    await expect(teamRow.getByRole("cell").nth(3)).toHaveText("1");
    await page.getByRole("link", { name: `Open team ${teamName}` }).click();

    // Remove asks first, then the roster is empty again.
    await page.getByRole("button", { name: `Remove ${member.name}` }).click();
    await Promise.all([
      page.waitForResponse((r) => r.request().method() === "DELETE" && /\/api\/v1\/teams\/\d+\/members\/\d+$/.test(r.url()) && r.status() === 204),
      page.getByRole("dialog").getByRole("button", { name: "Remove", exact: true }).click(),
    ]);
    await expect(page.getByText("This team has no members yet")).toBeVisible();
    await expect(page.getByRole("table", { name: `Members of ${teamName}` })).toHaveCount(0);
  });
});
