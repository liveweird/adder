// The room workflow editor: an owner reorders and recolours statuses, adds a closed status and a select field and finds the workflow
// intact after a reload; a save made on a stale revision is refused and Reload recovers; a room created by a Polish-speaking user
// carries the template's Polish labels. Owns: its throwaway `e2e-room-*` rooms and users, removed through the API in an `afterEach`
// (rooms first, then the users). The seeded Default and Incident templates are shared and read-only: rooms only copy them. Every
// room is created by a throwaway user, so the seed administrator is never a member of one; the per-user `room-name` bucket (30 a
// minute) is far from reached. The retire dialog (a status with threads) needs threads, so its e2e arrives with them (0.3).
import type { Page } from "@playwright/test";
import { accountMenu, apiAsAdmin, createUserViaUi, deleteViaApi, expect, login, loginViaForm, signOut, test, uniqueText } from "./helpers";

// API teardown, so a failing step leaves neither the room nor the user behind (rooms first: they reference users).
const cleanup: string[] = [];
test.afterEach(async () => {
  await deleteViaApi(cleanup.splice(0));
});

const ROOMS_API = /\/api\/v1\/rooms(\/\d+)?$/;
const WORKFLOW_API = /\/api\/v1\/rooms\/\d+\/workflow$/;
const statusRow = (page: Page, n: number) => page.getByRole("group", { name: `Status ${n}`, exact: true });

/** Create a throwaway user through the admin UI and register its cleanup. */
async function throwawayUser(page: Page, name: string) {
  const user = await createUserViaUi(page, name);
  cleanup.push(`/api/v1/users/${user.id}`);
  return user;
}

/** The create form as the signed-in user (in whatever language its UI speaks): `labels` are the form's own words. */
async function createRoomViaUi(page: Page, name: string, template: string, labels = { name: "Name", template: "Workflow template", create: "Create" }): Promise<number> {
  await page.goto("/rooms/new");
  await page.getByRole("textbox", { name: labels.name, exact: true }).fill(name);
  await page.getByRole("combobox", { name: labels.template }).click();
  await page.getByRole("option", { name: template, exact: true }).click();
  const [created] = await Promise.all([
    page.waitForResponse((r) => r.request().method() === "POST" && ROOMS_API.test(r.url()) && r.status() === 201),
    page.getByRole("button", { name: labels.create, exact: true }).click(),
  ]);
  const id = ((await created.json()) as { id: number }).id;
  cleanup.unshift(`/api/v1/rooms/${id}`);
  await expect(page).toHaveURL(new RegExp(`/rooms/${id}$`));
  return id;
}

/** Sign in as the owner of a fresh room made from `template`, and land on its Workflow tab. */
async function ownerOnWorkflowTab(page: Page, template: string): Promise<{ roomId: number; roomName: string }> {
  await login(page);
  const owner = await throwawayUser(page, "E2E Workflow Owner");
  await signOut(page);
  await login(page, owner.email, owner.password);
  const roomName = uniqueText("e2e-room");
  const roomId = await createRoomViaUi(page, roomName, template);
  await page.getByRole("link", { name: "Workflow", exact: true }).click();
  await expect(page).toHaveURL(new RegExp(`/rooms/${roomId}/workflow$`));
  await expect(page.getByRole("heading", { level: 2, name: roomName })).toBeVisible();
  await expect(page.getByRole("link", { name: "Workflow", exact: true })).toHaveAttribute("aria-current", "page");
  return { roomId, roomName };
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

  // The Overview's summary follows the saved workflow.
  await page.getByRole("link", { name: "Overview", exact: true }).click();
  await expect(page).toHaveURL(new RegExp(`/rooms/${roomId}$`));
  await expect(page.getByRole("group", { name: "Closed", exact: true })).toContainText("Cancelled");
  await expect(page.getByText("Fields: Impact")).toBeVisible();
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
  await login(page);
  const user = await throwawayUser(page, "E2E Workflow Polish");
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
  const roomId = await createRoomViaUi(page, roomName, "Incident", { name: "Nazwa", template: "Szablon przepływu pracy", create: "Utwórz" });
  await page.getByRole("link", { name: "Przepływ pracy", exact: true }).click();
  await expect(page).toHaveURL(new RegExp(`/rooms/${roomId}/workflow$`));
  const labels = ["Triaż", "Analiza", "Mitygacja", "Monitorowanie", "Rozwiązane"];
  for (const [index, label] of labels.entries()) {
    await expect(statusRow(page, index + 1).getByRole("textbox", { name: "Etykieta" })).toHaveValue(label);
  }
  await expect(page.getByRole("group", { name: "Pole 1", exact: true }).getByRole("textbox", { name: "Etykieta" }).first()).toHaveValue("Ważność");
});
