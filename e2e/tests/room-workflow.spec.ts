// The room workflow editor: an owner reorders and recolours statuses, adds a closed status and a select field and finds the workflow
// intact after a reload; a save made on a stale revision is refused and Reload recovers; a room created by a Polish-speaking user
// carries the template's Polish labels. Owns: its throwaway `e2e-room-*` rooms and users, removed through the API in an `afterEach`
// (rooms first, then the users). The seeded Default and Incident templates are shared and read-only: rooms only copy them. Every
// room is created by a throwaway user, so the seed administrator is never a member of one; the per-user `room-name` bucket (30 a
// minute) is far from reached. Retiring a status that threads use opens the retire dialog; the owner seeds those threads through the API
// (the thread POSTs share a per-user `chat-post` bucket of 120 a minute, far from reached).
import { type Page, request as playwrightRequest } from "@playwright/test";
import { BASE_URL } from "../playwright.config";
import { accountMenu, apiAsAdmin, createRoomViaUi, deleteViaApi, expect, login, loginViaForm, signOut, specAdmins, test, throwawayUser, uniqueText } from "./helpers";

/** This file's own administrator (see `specAdmins`): the page loads of its sign-ins spend this account's live-ticket bucket, not the seed administrator's. */
const admin = specAdmins("e2e-room-workflow-admin");

// API teardown, so a failing step leaves neither the room nor the user behind (rooms first: they reference users).
const cleanup: string[] = [];
test.afterEach(async () => {
  await deleteViaApi(cleanup.splice(0));
});

const WORKFLOW_API = /\/api\/v1\/rooms\/\d+\/workflow$/;
const statusRow = (page: Page, n: number) => page.getByRole("group", { name: `Status ${n}`, exact: true });

/** Sign in as the owner of a fresh room made from `template`, and land on its Workflow tab. */
async function ownerOnWorkflowTab(page: Page, template: string): Promise<{ roomId: number; roomName: string; owner: { email: string; password: string } }> {
  await admin.login(page);
  const owner = await throwawayUser(page, "E2E Workflow Owner", cleanup);
  await signOut(page);
  await login(page, owner.email, owner.password);
  const roomName = uniqueText("e2e-room");
  const roomId = await createRoomViaUi(page, roomName, template, cleanup);
  await page.getByRole("link", { name: "Workflow", exact: true }).click();
  await expect(page).toHaveURL(new RegExp(`/rooms/${roomId}/workflow$`));
  await expect(page.getByRole("heading", { level: 2, name: roomName })).toBeVisible();
  await expect(page.getByRole("link", { name: "Workflow", exact: true })).toHaveAttribute("aria-current", "page");
  return { roomId, roomName, owner };
}

/** The thread POSTs and moves the seeding makes for the room's owner, who may start and move threads; the context is the caller's to dispose. */
async function seedThreadsInWaiting(roomId: number, owner: { email: string; password: string }, titles: string[]): Promise<void> {
  const anonymous = await playwrightRequest.newContext({ baseURL: BASE_URL });
  const signedIn = await anonymous.post("/api/v1/login", { data: { email: owner.email, password: owner.password } });
  expect(signedIn.ok(), await signedIn.text()).toBeTruthy();
  const { token } = (await signedIn.json()) as { token: string };
  await anonymous.dispose();
  const api = await playwrightRequest.newContext({ baseURL: BASE_URL, extraHTTPHeaders: { Authorization: `Bearer ${token}` } });
  try {
    for (const title of titles) {
      const started = await api.post(`/api/v1/rooms/${roomId}/threads`, { data: { title, body: "Seeded for the retire dialog." } });
      expect(started.status(), await started.text()).toBe(201);
      const { number } = (await started.json()) as { number: number };
      // Waiting is an OPEN status of the Default template, so any reader may move a thread into it.
      const moved = await api.put(`/api/v1/rooms/${roomId}/threads/${number}/status`, { data: { status: "waiting" } });
      expect(moved.status(), await moved.text()).toBe(204);
    }
  } finally {
    await api.dispose();
  }
}

/** Opens a thread's side panel: a third column in a wide main area, otherwise folded behind the "Details" toggle (hidden by CSS when wide, so visibility decides). */
async function openDetails(page: Page): Promise<void> {
  const toggle = page.getByRole("button", { name: "Details", exact: true });
  if (!(await toggle.isVisible())) return;
  if ((await toggle.getAttribute("aria-expanded")) === "false") await toggle.click();
  await expect(toggle).toHaveAttribute("aria-expanded", "true");
}

const save = (page: Page) =>
  Promise.all([
    page.waitForResponse((r) => r.request().method() === "PUT" && WORKFLOW_API.test(r.url()) && r.status() === 204),
    page.getByRole("button", { name: "Save", exact: true }).click(),
  ]);

test("an owner reorders and recolours statuses, adds a closed status and a select field, and the workflow survives a reload", async ({ page }) => {
  const { roomId } = await ownerOnWorkflowTab(page, "Default");

  // The Default template's five statuses, in one plain label each; nothing is changed yet, and a saved key is read-only.
  await expect(statusRow(page, 1).getByRole("textbox", { name: "Label" })).toHaveValue("Open");
  await expect(statusRow(page, 3).getByRole("textbox", { name: "Label" })).toHaveValue("Waiting");
  await expect(statusRow(page, 1).getByRole("textbox", { name: "Key" })).toHaveAttribute("readonly", "");
  await expect(page.getByRole("button", { name: "Save", exact: true })).toBeDisabled();

  // Move Waiting up and recolour it orange (the option names the colour in words, not by the hue alone).
  await page.getByRole("button", { name: "Move status 3 up", exact: true }).click();
  await expect(statusRow(page, 2).getByRole("textbox", { name: "Label" })).toHaveValue("Waiting");
  await statusRow(page, 2).getByRole("combobox", { name: "Colour" }).click();
  await page.getByRole("option", { name: /^Orange/ }).click();
  await expect(statusRow(page, 2).getByRole("combobox", { name: "Colour" })).toHaveValue("Orange — warning");

  // Add a closed status.
  await page.getByRole("button", { name: "Add status", exact: true }).click();
  await statusRow(page, 6).getByRole("textbox", { name: "Label" }).fill("Cancelled");
  await statusRow(page, 6).getByText("Closed", { exact: true }).click();
  await expect(statusRow(page, 6).getByRole("textbox", { name: "Key" })).toHaveValue("cancelled");

  // Add a select field with two options.
  await page.getByRole("button", { name: "Add field", exact: true }).click();
  const field = page.getByRole("group", { name: "Field 1", exact: true });
  await field.getByRole("textbox", { name: "Label" }).first().fill("Impact");
  await field.getByRole("combobox", { name: "Type" }).click();
  await page.getByRole("option", { name: "Select", exact: true }).click();
  const options = field.getByRole("group", { name: "Options", exact: true });
  await options.getByRole("button", { name: "Add option" }).click();
  await options.getByRole("textbox", { name: "Label" }).nth(0).fill("High");
  await options.getByRole("button", { name: "Add option" }).click();
  await options.getByRole("textbox", { name: "Label" }).nth(1).fill("Low");

  await expect(page.getByRole("button", { name: "Save", exact: true })).toBeEnabled();
  await save(page);
  // Re-seeded from the saved revision: clean again.
  await expect(page.getByRole("button", { name: "Save", exact: true })).toBeDisabled();
  // Focus lands on the workflow group (the re-seed replaced the focused control).
  await expect(page.getByRole("group", { name: "Room workflow", exact: true })).toBeFocused();

  // A reload reads it all back from the server: order, colour, the new closed status and the field with its options.
  await page.reload();
  await expect(statusRow(page, 2).getByRole("textbox", { name: "Label" })).toHaveValue("Waiting");
  await expect(statusRow(page, 2).getByRole("combobox", { name: "Colour" })).toHaveValue("Orange — warning");
  await expect(statusRow(page, 3).getByRole("textbox", { name: "Label" })).toHaveValue("In progress");
  await expect(statusRow(page, 6).getByRole("textbox", { name: "Label" })).toHaveValue("Cancelled");
  await expect(statusRow(page, 6).getByRole("radio", { name: "Closed" })).toBeChecked();
  const saved = page.getByRole("group", { name: "Field 1", exact: true });
  await expect(saved.getByRole("textbox", { name: "Label" }).first()).toHaveValue("Impact");
  await expect(saved.getByRole("combobox", { name: "Type" })).toHaveValue("Select");
  await expect(saved.getByRole("textbox", { name: "Label" }).nth(1)).toHaveValue("High");
  await expect(saved.getByRole("textbox", { name: "Label" }).nth(2)).toHaveValue("Low");

  // The Threads view follows the saved workflow: the new-thread form offers the new field.
  await page.getByRole("link", { name: "Threads", exact: true }).click();
  await expect(page).toHaveURL(new RegExp(`/rooms/${roomId}$`));
  await page.getByRole("link", { name: "New thread", exact: true }).click();
  await page.getByRole("button", { name: "More fields" }).click();
  await expect(page.getByRole("combobox", { name: "Impact", exact: true })).toBeVisible();
});

test("a stale workflow save is refused and a reload recovers", async ({ page }) => {
  const { roomId } = await ownerOnWorkflowTab(page, "Default");
  await statusRow(page, 1).getByRole("textbox", { name: "Label" }).fill("Opened");

  // Meanwhile an administrator (who may manage any room) saves a newer revision through the API: "In progress" becomes "Doing".
  const { api } = await apiAsAdmin();
  try {
    const read = await api.get(`/api/v1/rooms/${roomId}/workflow`);
    expect(read.status(), await read.text()).toBe(200);
    const current = (await read.json()) as { revision: number; statuses: { key: string; label: string; colour: string; category: string; initial: boolean }[]; fields: unknown[] };
    const replaced = await api.put(`/api/v1/rooms/${roomId}/workflow`, {
      headers: { "X-Expected-Revision": String(current.revision) },
      data: {
        statuses: current.statuses.map((s) => ({ key: s.key, label: s.key === "in-progress" ? "Doing" : s.label, colour: s.colour, category: s.category, initial: s.initial })),
        fields: current.fields,
      },
    });
    expect(replaced.status(), await replaced.text()).toBe(204);
  } finally {
    await api.dispose();
  }

  // The owner's save answers 409: the alert keeps the draft in place and offers Reload.
  const [refused] = await Promise.all([
    page.waitForResponse((r) => r.request().method() === "PUT" && WORKFLOW_API.test(r.url()) && r.status() === 409),
    page.getByRole("button", { name: "Save", exact: true }).click(),
  ]);
  expect(await refused.text()).toContain("revision-conflict");
  await expect(page.getByText("This workflow was changed elsewhere")).toBeVisible();
  await expect(statusRow(page, 1).getByRole("textbox", { name: "Label" })).toHaveValue("Opened");

  // Reload swaps in the newer version (the administrator's label, the owner's edit gone) and leaves a clean form.
  await page.getByRole("button", { name: "Reload the latest version" }).click();
  await expect(statusRow(page, 2).getByRole("textbox", { name: "Label" })).toHaveValue("Doing");
  await expect(statusRow(page, 1).getByRole("textbox", { name: "Label" })).toHaveValue("Open");
  await expect(page.getByText("This workflow was changed elsewhere")).toHaveCount(0);
  await expect(page.getByRole("button", { name: "Save", exact: true })).toBeDisabled();

  // The edit applied again now saves, under the newer revision.
  await statusRow(page, 1).getByRole("textbox", { name: "Label" }).fill("Opened");
  await save(page);
  await page.reload();
  await expect(statusRow(page, 1).getByRole("textbox", { name: "Label" })).toHaveValue("Opened");
  await expect(statusRow(page, 2).getByRole("textbox", { name: "Label" })).toHaveValue("Doing");
});

test("a room created by a Polish-speaking user gets the Polish labels of the template", async ({ page }) => {
  await admin.login(page);
  const user = await throwawayUser(page, "E2E Workflow Polish", cleanup);
  await signOut(page);

  // The throwaway signs in and switches to Polish (the choice saves server-side; the UI follows).
  await loginViaForm(page, user.email, user.password);
  await Promise.all([
    page.waitForResponse((r) => r.url().endsWith(`/api/v1/users/${user.id}/language`) && r.request().method() === "PUT" && r.ok()),
    (async () => {
      await accountMenu(page).click();
      await page.getByRole("menuitem", { name: "Polski" }).click();
    })(),
  ]);
  await expect(page.getByRole("link", { name: "Strona główna" })).toBeVisible();

  // Creating a room in Polish copies the template's Polish labels (a room's labels are plain text in its creator's language).
  const roomName = uniqueText("e2e-room");
  const roomId = await createRoomViaUi(page, roomName, "Incident", cleanup, { name: "Nazwa", template: "Szablon przepływu pracy", create: "Utwórz" });
  await page.getByRole("link", { name: "Przepływ pracy", exact: true }).click();
  await expect(page).toHaveURL(new RegExp(`/rooms/${roomId}/workflow$`));
  const labels = ["Triaż", "Analiza", "Mitygacja", "Monitorowanie", "Rozwiązane"];
  for (const [index, label] of labels.entries()) {
    await expect(statusRow(page, index + 1).getByRole("textbox", { name: "Etykieta" })).toHaveValue(label);
  }
  await expect(page.getByRole("group", { name: "Pole 1", exact: true }).getByRole("textbox", { name: "Etykieta" }).first()).toHaveValue("Ważność");
});

test("retiring a status that threads use asks for a replacement and moves the threads", async ({ page }) => {
  const { roomId, owner } = await ownerOnWorkflowTab(page, "Default");
  const titles = [uniqueText("e2e-retire-a"), uniqueText("e2e-retire-b")];
  await seedThreadsInWaiting(roomId, owner, titles);

  // A reload reads the workflow with the thread counts: the status the threads sit in can no longer simply be removed.
  await page.reload();
  await expect(statusRow(page, 3).getByRole("textbox", { name: "Label" })).toHaveValue("Waiting");
  await statusRow(page, 3).getByRole("button", { name: "Remove status 3", exact: true }).click();

  // The dialog counts the threads and asks for a replacement of the same category (Open): Done and Won't do are not offered.
  const dialog = page.getByRole("dialog", { name: "Move threads before removing" });
  await expect(dialog).toContainText("2 threads are in “Waiting”. Move them to:");
  await expect(dialog.getByRole("button", { name: "Remove status", exact: true })).toBeDisabled();
  await dialog.getByRole("combobox", { name: "Move to" }).click();
  await expect(page.getByRole("option", { name: "Done", exact: true })).toHaveCount(0);
  await page.getByRole("option", { name: "In progress", exact: true }).click();
  await dialog.getByRole("button", { name: "Remove status", exact: true }).click();
  await expect(dialog).toHaveCount(0);

  // The editor lists the pending removal with the move, and nothing is saved until Save.
  const pending = page.getByRole("group", { name: "Statuses to be removed", exact: true });
  await expect(pending).toContainText("“Waiting” will be removed; its 2 threads move to “In progress”.");
  await save(page);
  await expect(page.getByRole("group", { name: "Statuses to be removed", exact: true })).toHaveCount(0);
  // Re-seeded from the saved revision: Waiting is gone, so Done is now the third status and there are four.
  await expect(statusRow(page, 3).getByRole("textbox", { name: "Label" })).toHaveValue("Done");
  await expect(statusRow(page, 5)).toHaveCount(0);

  // The thread list shows the replacement on both threads.
  await page.getByRole("link", { name: "Threads", exact: true }).click();
  await expect(page).toHaveURL(new RegExp(`/rooms/${roomId}$`));
  const list = page.getByRole("navigation", { name: "Threads", exact: true });
  for (const title of titles) {
    const row = list.getByRole("link", { name: new RegExp(title) });
    await expect(row).toContainText("In progress");
    await expect(row).not.toContainText("Waiting");
  }

  // Each thread's history lists the move as the newest entry (after its start and its move into Waiting).
  for (const [index, title] of titles.entries()) {
    await page.goto(`/rooms/${roomId}/threads/${index + 1}`);
    await expect(page.getByRole("heading", { level: 3, name: `#${index + 1} ${title}` })).toBeVisible();
    await openDetails(page);
    const entries = page.getByRole("group", { name: "History", exact: true }).locator(".mantine-Timeline-item");
    await expect(entries).toHaveCount(3);
    await expect(entries.nth(0)).toContainText("Waiting was retired; the thread moved to In progress");
    await expect(entries.nth(1)).toContainText("Moved the thread from Open to Waiting");
  }
});
