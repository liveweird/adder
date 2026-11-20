// Rooms: a user creates a room from a workflow template and finds it in the sidebar's rooms rail; the owner adds a person and a whole
// team, hands over ownership and leaves (the last owner cannot); a non-member cannot open a room while an administrator can; an owner
// archives and unarchives it and an administrator deletes it. Owns: its throwaway rooms (unique `e2e-room-*` names), one throwaway
// team (`e2e-room-team-*`) and users, removed through the API in an `afterEach` (rooms first, then the team, then the users). The seeded Default and Incident templates
// are shared and read-only here: rooms only copy them. Every room is created by a throwaway user (the room-name bucket is
// per user, 30 a minute, and the member add has its own per-user bucket: this file stays far below both), so the seed administrator is never a member of any of them.
import { apiAsAdmin, createRoomViaUi, deleteViaApi, expect, login, openFilters, rail, rowOperation, signOut, specAdmins, test, throwawayUser, uniqueText } from "./helpers";

/** This file's own administrator (see `specAdmins`): the page loads of its sign-ins spend this account's live-ticket bucket, not the seed administrator's. */
const admin = specAdmins("e2e-rooms-admin");

// API teardown, so a failing step leaves neither the room nor the user behind (rooms first: they reference users).
const cleanup: string[] = [];
test.afterEach(async () => {
  await deleteViaApi(cleanup.splice(0));
});

const ROOMS_API = /\/api\/v1\/rooms(\/\d+)?(\/(archive|unarchive))?$/;
const NOT_A_MEMBER = "This room does not exist, or you are not a member of it.";

test("a user creates a room from the Incident template and finds it in the rooms rail", async ({ page }) => {
  await admin.login(page);
  const user = await throwawayUser(page, "E2E Room Creator", cleanup);
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

  // The creator lands on the room: its header, the Threads tab open, the (still empty) thread list and the pane's prompt.
  await expect(page).toHaveURL(new RegExp(`/rooms/${id}$`));
  await expect(page.getByRole("heading", { level: 2, name })).toBeVisible();
  await expect(page.getByText("Playwright's own room")).toBeVisible();
  await expect(page.getByText("1 member", { exact: true })).toBeVisible();
  await expect(page.getByRole("link", { name: "Threads", exact: true })).toHaveAttribute("aria-current", "page");
  await expect(page.getByText("No open threads")).toBeVisible();
  await expect(page.getByText("Pick a thread")).toBeVisible();
  // The copied workflow reaches the new-thread form: Incident's four fields are all optional, so they sit behind "More fields".
  await page.getByRole("link", { name: "New thread", exact: true }).click();
  await expect(page).toHaveURL(new RegExp(`/rooms/${id}/threads/new$`));
  await expect(page.getByRole("link", { name: "Threads", exact: true })).toHaveAttribute("aria-current", "page");
  await page.getByRole("button", { name: "More fields" }).click();
  await expect(page.getByRole("combobox", { name: "Severity", exact: true })).toBeVisible();
  for (const label of ["Component", "Jira link", "ETA"]) await expect(page.getByLabel(label, { exact: true })).toBeVisible();

  // The copied statuses and their categories, on the Workflow tab: Triage is the open initial status, Resolved the one closed status.
  await page.getByRole("link", { name: "Workflow", exact: true }).click();
  await expect(page).toHaveURL(new RegExp(`/rooms/${id}/workflow$`));
  const triage = page.getByRole("group", { name: "Status 1", exact: true });
  await expect(triage.getByRole("textbox", { name: "Label" })).toHaveValue("Triage");
  await expect(triage.getByRole("radio", { name: "Open" })).toBeChecked();
  const resolved = page.getByRole("group", { name: "Status 5", exact: true });
  await expect(resolved.getByRole("textbox", { name: "Label" })).toHaveValue("Resolved");
  await expect(resolved.getByRole("radio", { name: "Closed" })).toBeChecked();

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

const MEMBERS_API = /\/api\/v1\/rooms\/\d+\/members(\/\d+)?$/;
const LAST_OWNER = "You are the only owner of this room. Make someone else an owner before you leave.";

test("the owner adds a person and a whole team, hands over ownership and leaves", async ({ page }) => {
  await admin.login(page);
  const owner = await throwawayUser(page, "E2E Room Owner", cleanup);
  const person = await throwawayUser(page, "E2E Room Person", cleanup);
  const mate = await throwawayUser(page, "E2E Room Teammate", cleanup);
  // The fixture team holds the person (who will already belong by then) and a teammate; built through the API, since the journey's
  // subject is the room. It goes ahead of the users in the teardown (it references them).
  const teamName = uniqueText("e2e-room-team");
  const { api } = await apiAsAdmin();
  try {
    const created = await api.post("/api/v1/teams", { data: { name: teamName } });
    expect(created.status(), await created.text()).toBe(201);
    const teamId = ((await created.json()) as { id: number }).id;
    cleanup.unshift(`/api/v1/teams/${teamId}`);
    for (const member of [person, mate]) {
      const added = await api.put(`/api/v1/teams/${teamId}/members/${member.id}`);
      expect(added.status(), await added.text()).toBe(204);
    }
  } finally {
    await api.dispose();
  }
  await signOut(page);

  await login(page, owner.email, owner.password);
  const name = uniqueText("e2e-room");
  const id = await createRoomViaUi(page, name, "Default", cleanup);
  await page.getByRole("link", { name: "Members", exact: true }).click();
  await expect(page).toHaveURL(new RegExp(`/rooms/${id}/members$`));
  await expect(page.getByRole("link", { name: "Members", exact: true })).toHaveAttribute("aria-current", "page");
  const table = page.getByRole("table", { name: "Members", exact: true });
  const rowOf = (email: string) => table.getByRole("row").filter({ hasText: email });
  await expect(rowOf(owner.email).getByRole("cell").nth(2)).toHaveText("Owner");
  await expect(rowOf(owner.email).getByText("You", { exact: true })).toBeVisible();

  // Add a person from the directory: they join as a plain member.
  const personPicker = page.getByRole("combobox", { name: "Add a person" });
  await personPicker.click();
  await personPicker.fill(person.name);
  await page.getByRole("option", { name: `${person.name} (${person.email})` }).click();
  await Promise.all([
    page.waitForResponse((r) => r.request().method() === "POST" && MEMBERS_API.test(r.url()) && r.status() === 200),
    page.getByRole("button", { name: "Add person", exact: true }).click(),
  ]);
  await expect(rowOf(person.email).getByRole("cell").nth(2)).toHaveText("Member");
  await expect(page.getByText("2 members", { exact: true })).toBeVisible();

  // Add a whole team: the confirm states how many people it holds, and the one who already belongs is skipped.
  const teamPicker = page.getByRole("combobox", { name: "Add a whole team" });
  await teamPicker.click();
  await teamPicker.fill(teamName);
  await page.getByRole("option", { name: teamName, exact: true }).click();
  await page.getByRole("button", { name: "Add team", exact: true }).click();
  const dialog = page.getByRole("dialog", { name: "Add the team?" });
  await expect(dialog).toContainText(`“${teamName}” has 2 people.`);
  await Promise.all([
    page.waitForResponse((r) => r.request().method() === "POST" && MEMBERS_API.test(r.url()) && r.status() === 200),
    dialog.getByRole("button", { name: "Add team", exact: true }).click(),
  ]);
  await expect(page.getByRole("dialog")).toHaveCount(0);
  await expect(rowOf(mate.email).getByRole("cell").nth(2)).toHaveText("Member");
  await expect(page.getByText("3 members", { exact: true })).toBeVisible();
  await expect(table.getByRole("row")).toHaveCount(4); // the header and three members

  // The only owner cannot leave: the refusal says to hand over first, and nothing changed.
  await page.getByRole("button", { name: "Leave room", exact: true }).click();
  await Promise.all([
    page.waitForResponse((r) => r.request().method() === "DELETE" && MEMBERS_API.test(r.url()) && r.status() === 409),
    page.getByRole("dialog").getByRole("button", { name: "Leave room", exact: true }).click(),
  ]);
  await expect(page.getByRole("alert").filter({ hasText: LAST_OWNER })).toBeVisible();
  await expect(page).toHaveURL(new RegExp(`/rooms/${id}/members$`));

  // Hand ownership over, then leave: the room is gone from the owner's rail and from their reach.
  await Promise.all([
    page.waitForResponse((r) => r.request().method() === "PUT" && MEMBERS_API.test(r.url()) && r.status() === 204),
    rowOperation(page, person.name, "Make owner"),
  ]);
  await expect(rowOf(person.email).getByRole("cell").nth(2)).toHaveText("Owner");
  await page.getByRole("button", { name: "Leave room", exact: true }).click();
  await expect(page.getByRole("dialog", { name: "Leave this room?" })).toBeVisible();
  await Promise.all([
    page.waitForResponse((r) => r.request().method() === "DELETE" && MEMBERS_API.test(r.url()) && r.status() === 204),
    page.getByRole("dialog").getByRole("button", { name: "Leave room", exact: true }).click(),
  ]);
  await expect(page).toHaveURL(/\/rooms$/);
  await expect(rail(page).getByRole("link", { name, exact: true })).toHaveCount(0);
  await page.goto(`/rooms/${id}`);
  await expect(page.getByText(NOT_A_MEMBER)).toBeVisible();
  await signOut(page);

  // The new owner sees the room with the original owner gone, and has the controls.
  await login(page, person.email, person.password);
  await expect(rail(page).getByRole("link", { name, exact: true })).toBeVisible();
  await page.goto(`/rooms/${id}/members`);
  await expect(rowOf(person.email)).toBeVisible();
  await expect(rowOf(mate.email)).toBeVisible();
  await expect(rowOf(owner.email)).toHaveCount(0);
  await expect(page.getByRole("combobox", { name: "Add a person" })).toBeVisible();
  await signOut(page);

  // A plain member reads the list without the controls, and can still leave from the Members section's header.
  await login(page, mate.email, mate.password);
  await page.goto(`/rooms/${id}/members`);
  await expect(rowOf(person.email)).toBeVisible();
  await expect(page.getByRole("combobox", { name: "Add a person" })).toHaveCount(0);
  await expect(page.getByRole("button", { name: /^Operations for / })).toHaveCount(0);
  await expect(page.getByRole("button", { name: "Leave room", exact: true })).toBeVisible();
});

test("a non-member cannot open a room while an administrator can", async ({ page }) => {
  await admin.login(page);
  const owner = await throwawayUser(page, "E2E Room Owner", cleanup);
  const outsider = await throwawayUser(page, "E2E Room Outsider", cleanup);
  await signOut(page);

  await login(page, owner.email, owner.password);
  const name = uniqueText("e2e-room");
  const id = await createRoomViaUi(page, name, "Default", cleanup);
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
  await admin.login(page);
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
  await admin.login(page);
  const owner = await throwawayUser(page, "E2E Room Archiver", cleanup);
  await signOut(page);

  await login(page, owner.email, owner.password);
  const name = uniqueText("e2e-room");
  const id = await createRoomViaUi(page, name, "Default", cleanup);
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
  await admin.login(page);
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
