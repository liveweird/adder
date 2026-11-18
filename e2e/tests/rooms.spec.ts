// Rooms: a user creates a room from a workflow template and finds it in the sidebar's rooms rail; a non-member cannot open a room
// while an administrator can; an owner archives and unarchives it and an administrator deletes it. Owns: its throwaway rooms (unique
// `e2e-room-*` names) and users, removed through the API in an `afterEach` (rooms first). The seeded Default and Incident templates
// are shared and read-only here: rooms only copy them. Every room is created by a throwaway user (the room-create bucket is
// per user, 30 a minute: this file creates a handful), so the seed administrator is never a member of any of them.
import type { Page } from "@playwright/test";
import { createUserViaUi, deleteViaApi, expect, login, openFilters, signOut, test, uniqueText } from "./helpers";

// API teardown, so a failing step leaves neither the room nor the user behind (rooms first: they reference users).
const cleanup: string[] = [];
test.afterEach(async () => {
  await deleteViaApi(cleanup.splice(0));
});

const ROOMS_API = /\/api\/v1\/rooms(\/\d+)?(\/(archive|unarchive))?$/;
/** The sidebar's Rooms section: the static "All rooms" leaf and the caller's rooms rail. */
const rail = (page: Page) => page.getByRole("group", { name: "Rooms", exact: true });
const NOT_A_MEMBER = "This room does not exist, or you are not a member of it.";

/** Create a throwaway user through the admin UI and register its cleanup. */
async function throwawayUser(page: Page, name: string) {
  const user = await createUserViaUi(page, name);
  cleanup.push(`/api/v1/users/${user.id}`);
  return user;
}

/** Create a room from the create form as the signed-in user and register its cleanup; leaves the browser on the new room. */
async function createRoomViaUi(page: Page, name: string, template: string): Promise<number> {
  await page.goto("/rooms/new");
  await page.getByRole("textbox", { name: "Name", exact: true }).fill(name);
  await page.getByRole("combobox", { name: "Workflow template" }).click();
  await page.getByRole("option", { name: template, exact: true }).click();
  const [created] = await Promise.all([
    page.waitForResponse((r) => r.request().method() === "POST" && ROOMS_API.test(r.url()) && r.status() === 201),
    page.getByRole("button", { name: "Create", exact: true }).click(),
  ]);
  const id = ((await created.json()) as { id: number }).id;
  cleanup.unshift(`/api/v1/rooms/${id}`);
  await expect(page).toHaveURL(new RegExp(`/rooms/${id}$`));
  await expect(page.getByRole("heading", { level: 2, name })).toBeVisible();
  return id;
}

test("a user creates a room from the Incident template and finds it in the rooms rail", async ({ page }) => {
  await login(page);
  const user = await throwawayUser(page, "E2E Room Creator");
  await signOut(page);
  await login(page, user.email, user.password);
  const name = uniqueText("e2e-room");

  // A fresh user is in no room: the rail says so, and the Rooms page leads to the create form.
  await expect(rail(page).getByText("You are not in any room yet")).toBeVisible();
  await rail(page).getByRole("link", { name: "All rooms" }).click();
  await expect(page).toHaveURL(/\/rooms$/);
  await expect(page.getByRole("heading", { level: 2, name: "Rooms", exact: true })).toBeVisible();
  await page.getByRole("link", { name: "New room" }).click();
  await expect(page.getByRole("heading", { level: 2, name: "New room" })).toBeVisible();

  // Picking the Incident template previews its five statuses.
  await page.getByRole("textbox", { name: "Name", exact: true }).fill(name);
  await page.getByRole("textbox", { name: "Description" }).fill("Playwright's own room");
  await page.getByRole("combobox", { name: "Workflow template" }).click();
  await page.getByRole("option", { name: "Incident", exact: true }).click();
  const preview = page.getByRole("group", { name: "Statuses" });
  for (const status of ["Triage", "Investigating", "Mitigating", "Monitoring", "Resolved"]) await expect(preview).toContainText(status);
  const [created] = await Promise.all([
    page.waitForResponse((r) => r.request().method() === "POST" && ROOMS_API.test(r.url()) && r.status() === 201),
    page.getByRole("button", { name: "Create", exact: true }).click(),
  ]);
  const id = ((await created.json()) as { id: number }).id;
  cleanup.unshift(`/api/v1/rooms/${id}`);

  // The creator lands on the room: its header, the placeholder and the copied workflow as pills.
  await expect(page).toHaveURL(new RegExp(`/rooms/${id}$`));
  await expect(page.getByRole("heading", { level: 2, name })).toBeVisible();
  await expect(page.getByText("Playwright's own room")).toBeVisible();
  await expect(page.getByText("1 member", { exact: true })).toBeVisible();
  await expect(page.getByText("Threads arrive next")).toBeVisible();
  await expect(page.getByRole("group", { name: "Open", exact: true })).toContainText("Triage");
  await expect(page.getByRole("group", { name: "Closed", exact: true })).toContainText("Resolved");
  await expect(page.getByText("Fields: Severity, Component, Jira link, ETA")).toBeVisible();

  // The rail lists the room and marks it current; the room's other pages keep it highlighted.
  const link = rail(page).getByRole("link", { name, exact: true });
  await expect(link).toHaveAttribute("aria-current", "page");
  await expect(rail(page).getByRole("link", { name: "All rooms" })).not.toHaveAttribute("aria-current");
  await page.getByRole("link", { name: "Settings", exact: true }).click();
  await expect(page).toHaveURL(new RegExp(`/rooms/${id}/settings$`));
  await expect(link).toHaveAttribute("aria-current", "page");

  // The Rooms page lists it too (filtered by name), with the creator as its owner.
  await rail(page).getByRole("link", { name: "All rooms" }).click();
  await openFilters(page);
  await page.getByLabel("Name", { exact: true }).fill(name);
  const row = page.getByRole("row").filter({ has: page.getByRole("link", { name: `Open room ${name}` }) });
  await expect(row.getByRole("cell").nth(1)).toHaveText("Owner");
  await expect(row.getByRole("cell").nth(2)).toHaveText("1");
});

test("a non-member cannot open a room while an administrator can", async ({ page }) => {
  await login(page);
  const owner = await throwawayUser(page, "E2E Room Owner");
  const outsider = await throwawayUser(page, "E2E Room Outsider");
  await signOut(page);

  await login(page, owner.email, owner.password);
  const name = uniqueText("e2e-room");
  const id = await createRoomViaUi(page, name, "Default");
  await signOut(page);

  // The outsider gets the same answer as for a room that does not exist, and the room is in none of their lists.
  await login(page, outsider.email, outsider.password);
  await page.goto(`/rooms/${id}`);
  await expect(page.getByText(NOT_A_MEMBER)).toBeVisible();
  await expect(page.getByRole("heading", { level: 2, name: "Rooms", exact: true })).toBeVisible();
  await expect(rail(page).getByRole("link", { name, exact: true })).toHaveCount(0);
  await page.getByRole("link", { name: "Back to rooms" }).click();
  await openFilters(page);
  await page.getByLabel("Name", { exact: true }).fill(name);
  await expect(page.getByText("No rooms yet")).toBeVisible();
  await signOut(page);

  // The administrator is not a member: their own list is empty of it, "All" finds it without a role, and it opens.
  await login(page);
  await page.goto("/rooms");
  await openFilters(page);
  await page.getByLabel("Name", { exact: true }).fill(name);
  await expect(page.getByText("No rooms yet")).toBeVisible();
  await page.getByRole("radiogroup", { name: "Which rooms to list" }).getByText("All", { exact: true }).click();
  const row = page.getByRole("row").filter({ has: page.getByRole("link", { name: `Open room ${name}` }) });
  await expect(row.getByRole("cell").nth(1)).toHaveText("—");
  await page.getByRole("link", { name: `Open room ${name}` }).click();
  await expect(page.getByRole("heading", { level: 2, name })).toBeVisible();
  await expect(page.getByText(NOT_A_MEMBER)).toHaveCount(0);
  await expect(rail(page).getByRole("link", { name, exact: true })).toHaveCount(0);
});

test("an owner archives and unarchives a room and an administrator deletes it", async ({ page }) => {
  await login(page);
  const owner = await throwawayUser(page, "E2E Room Archiver");
  await signOut(page);

  await login(page, owner.email, owner.password);
  const name = uniqueText("e2e-room");
  const id = await createRoomViaUi(page, name, "Default");
  await page.getByRole("link", { name: "Settings", exact: true }).click();
  const nameBox = page.getByRole("textbox", { name: "Name", exact: true });
  await expect(nameBox).toBeEditable();
  // An owner archives and unarchives; deleting is the administrator's.
  await expect(page.getByRole("button", { name: "Delete room" })).toHaveCount(0);

  // Archive asks first; the details turn read-only and the room leaves the active rail.
  await page.getByRole("button", { name: "Archive room" }).click();
  await expect(page.getByRole("dialog", { name: "Archive this room?" })).toBeVisible();
  await Promise.all([
    page.waitForResponse((r) => r.request().method() === "POST" && r.url().endsWith(`/api/v1/rooms/${id}/archive`) && r.status() === 204),
    page.getByRole("dialog").getByRole("button", { name: "Archive room" }).click(),
  ]);
  await expect(page.getByRole("dialog")).toHaveCount(0);
  await expect(page.getByText("Archived", { exact: true })).toBeVisible();
  await expect(nameBox).not.toBeEditable();
  await expect(page.getByRole("button", { name: "Unarchive room" })).toBeVisible();
  await expect(rail(page).getByRole("link", { name, exact: true })).toHaveCount(0);

  // "Show archived" in the rail brings it back, dimmed under its own group; the choice survives a reload.
  await rail(page).getByRole("switch", { name: "Show archived" }).click({ force: true });
  await expect(page.getByRole("group", { name: "Archived rooms" }).getByRole("link", { name, exact: true })).toHaveAttribute("aria-current", "page");
  await page.reload();
  await expect(page.getByRole("group", { name: "Archived rooms" }).getByRole("link", { name, exact: true })).toBeVisible();

  // Unarchive is the same flow the other way: the details are editable again.
  await page.getByRole("button", { name: "Unarchive room" }).click();
  await Promise.all([
    page.waitForResponse((r) => r.request().method() === "POST" && r.url().endsWith(`/api/v1/rooms/${id}/unarchive`) && r.status() === 204),
    page.getByRole("dialog").getByRole("button", { name: "Unarchive room" }).click(),
  ]);
  await expect(page.getByRole("dialog")).toHaveCount(0);
  await expect(nameBox).toBeEditable();
  await expect(page.getByRole("button", { name: "Archive room" })).toBeVisible();
  await signOut(page);

  // The administrator (not a member) deletes the room from its settings, after a confirm.
  await login(page);
  await page.goto(`/rooms/${id}/settings`);
  await expect(page.getByRole("heading", { level: 2, name })).toBeVisible();
  await page.getByRole("button", { name: "Delete room" }).click();
  await expect(page.getByRole("dialog", { name: "Delete room?" })).toBeVisible();
  await Promise.all([
    page.waitForResponse((r) => r.request().method() === "DELETE" && ROOMS_API.test(r.url()) && r.status() === 204),
    page.getByRole("dialog").getByRole("button", { name: "Delete", exact: true }).click(),
  ]);
  await expect(page).toHaveURL(/\/rooms$/);
  await page.goto(`/rooms/${id}`);
  await expect(page.getByText(NOT_A_MEMBER)).toBeVisible();
});
