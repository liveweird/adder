// The ADMIN workflow-template registry: an administrator creates a template, edits its labels in both languages and deletes it; a
// regular user picks a template when creating a room but has no template pages. Owns: its throwaway templates (unique `e2e-tpl-*` names) and user, removed through the API in an
// `afterEach`. The seeded Default and Incident templates are shared and read-only: no spec edits or deletes them.
import type { Page } from "@playwright/test";
import { createUserViaUi, deleteViaApi, expect, login, openFilters, rowOperation, signOut, test, uniqueText } from "./helpers";

// API teardown, so a failing step leaves neither the template nor the user behind.
const cleanup: string[] = [];
test.afterEach(async () => {
  await deleteViaApi(cleanup.splice(0));
});

const statusRow = (page: Page, n: number) => page.getByRole("group", { name: `Status ${n}`, exact: true });
const TEMPLATE_API = /\/api\/v1\/workflow-templates(\/\d+)?$/;

test("an administrator creates a workflow template, edits its labels in both languages and deletes it", async ({ page }) => {
  await login(page);
  const name = uniqueText("e2e-tpl");

  // The nav leaf leads to the registry; New template opens the create form with a valid two-status skeleton.
  await page.getByRole("link", { name: "Workflow templates" }).click();
  await expect(page).toHaveURL(/\/workflow-templates$/);
  await expect(page.getByRole("heading", { level: 2, name: "Workflow templates" })).toBeVisible();
  await page.getByRole("link", { name: "New template" }).click();
  await expect(page.getByRole("heading", { level: 2, name: "New workflow template" })).toBeVisible();
  await expect(statusRow(page, 1).getByRole("textbox", { name: "Label (EN)" })).toHaveValue("Open");
  await expect(statusRow(page, 2).getByRole("textbox", { name: "Label (EN)" })).toHaveValue("Done");

  // Name it, translate the first status, add a third one and create.
  await page.getByRole("textbox", { name: "Name" }).fill(name);
  await page.getByRole("textbox", { name: "Description" }).fill("Playwright's own template");
  await statusRow(page, 1).getByRole("textbox", { name: "Label (PL)" }).fill("Otwarte");
  await page.getByRole("button", { name: "Add status" }).click();
  await statusRow(page, 3).getByRole("textbox", { name: "Label (EN)" }).fill("Review");
  const [created] = await Promise.all([
    page.waitForResponse((r) => r.request().method() === "POST" && TEMPLATE_API.test(r.url()) && r.status() === 201),
    page.getByRole("button", { name: "Create" }).click(),
  ]);
  cleanup.push(`/api/v1/workflow-templates/${((await created.json()) as { id: number }).id}`);

  // The list shows it (filtered by name) with its three statuses; its name opens the editor.
  await expect(page).toHaveURL(/\/workflow-templates$/);
  await openFilters(page);
  await page.getByLabel("Name", { exact: true }).fill(name);
  const row = page.getByRole("row").filter({ has: page.getByRole("link", { name: `Open workflow template ${name}` }) });
  await expect(row.getByRole("cell").nth(2)).toHaveText("3");
  await page.getByRole("link", { name: `Open workflow template ${name}` }).click();
  await expect(page.getByRole("heading", { level: 2, name: "Edit workflow template" })).toBeVisible();
  await expect(statusRow(page, 1).getByRole("textbox", { name: "Label (PL)" })).toHaveValue("Otwarte");
  // Nothing changed yet; a saved status keeps its key.
  await expect(page.getByRole("button", { name: "Save", exact: true })).toBeDisabled();
  await expect(statusRow(page, 1).getByRole("textbox", { name: "Key" })).toHaveAttribute("readonly", "");

  // Edit the labels of the Closed status in both languages and save.
  await statusRow(page, 2).getByRole("textbox", { name: "Label (EN)" }).fill("Resolved");
  await statusRow(page, 2).getByRole("textbox", { name: "Label (PL)" }).fill("Rozwiązane");
  await expect(page.getByRole("button", { name: "Save", exact: true })).toBeEnabled();
  await Promise.all([
    page.waitForResponse((r) => r.request().method() === "PUT" && TEMPLATE_API.test(r.url()) && r.status() === 204),
    page.getByRole("button", { name: "Save", exact: true }).click(),
  ]);
  await expect(page).toHaveURL(/\/workflow-templates$/);

  // Reopened, both languages survived the round trip (and the key did not follow the new label).
  await openFilters(page);
  await page.getByLabel("Name", { exact: true }).fill(name);
  await page.getByRole("link", { name: `Open workflow template ${name}` }).click();
  await expect(statusRow(page, 2).getByRole("textbox", { name: "Label (EN)" })).toHaveValue("Resolved");
  await expect(statusRow(page, 2).getByRole("textbox", { name: "Label (PL)" })).toHaveValue("Rozwiązane");
  await expect(statusRow(page, 2).getByRole("textbox", { name: "Key" })).toHaveValue("done");

  // Delete from the list's row menu.
  await page.getByRole("link", { name: "Back to workflow templates" }).click();
  await openFilters(page);
  await page.getByLabel("Name", { exact: true }).fill(name);
  await rowOperation(page, name, `Delete ${name}`);
  await Promise.all([
    page.waitForResponse((r) => r.request().method() === "DELETE" && TEMPLATE_API.test(r.url()) && r.status() === 204),
    page.getByRole("dialog").getByRole("button", { name: "Delete", exact: true }).click(),
  ]);
  await expect(page.getByRole("link", { name: `Open workflow template ${name}` })).toHaveCount(0);
});

test("a regular user picks a template when creating a room but has no template pages", async ({ page }) => {
  await login(page);
  const user = await createUserViaUi(page, "E2E Template Reader");
  cleanup.push(`/api/v1/users/${user.id}`);
  await signOut(page);

  await login(page, user.email, user.password);
  const requests: string[] = [];
  page.on("request", (request) => {
    if (request.url().includes("/api/v1/workflow-templates")) requests.push(request.url());
  });
  await expect(page.getByRole("group", { name: "Administration" }).getByRole("link", { name: "Teams" })).toBeVisible();
  await expect(page.getByRole("link", { name: "Workflow templates" })).toHaveCount(0);

  // Every template URL sends the user home, and nothing is requested on the way.
  for (const path of ["/workflow-templates", "/workflow-templates/new", "/workflow-templates/1/edit"]) {
    await page.goto(path);
    await expect(page).toHaveURL(/\/$/);
    await expect(page.getByRole("heading", { level: 2, name: "Adder" })).toBeVisible();
    await expect(page.getByRole("link", { name: "Workflow templates" })).toHaveCount(0);
  }
  expect(requests).toEqual([]);

  // The other half: the room-create form reads the same list for any signed-in user and previews what a pick would copy.
  await page.getByRole("group", { name: "Rooms", exact: true }).getByRole("link", { name: "All rooms" }).click();
  await page.getByRole("link", { name: "New room" }).click();
  await expect(page.getByRole("heading", { level: 2, name: "New room" })).toBeVisible();
  await page.getByRole("combobox", { name: "Workflow template" }).click();
  await expect(page.getByRole("option", { name: "Default", exact: true })).toBeVisible();
  await page.getByRole("option", { name: "Incident", exact: true }).click();
  await expect(page.getByRole("group", { name: "Statuses" })).toContainText("Triage");
  expect(requests.length).toBeGreaterThan(0);
  // Nothing is created: the form is left as it is, and the template pages stay out of reach.
  await expect(page.getByRole("link", { name: "Workflow templates" })).toHaveCount(0);
});
