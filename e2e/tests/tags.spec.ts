// The ADMIN tag registry in the browser (1.2.0 S6, Hammurabi's `tags.spec.ts` ported): an administrator curates a category and its tags (create,
// edit, a stale save refused, delete); a regular user reads the registry through the API but has neither the page nor the writes. The rules
// (normalisation, one category per tag, retired tags, verdicts, the caps) are the server's (`TagCategoryTest`); this asserts what a person
// SEES. Owns: its throwaway administrator, user and category (unique `E2E Category-*` name, `e2e-tag-*` tags: a tag name is unique for ever, retired
// rows included, so every one carries a per-run suffix) - the category is deleted through the API in afterEach, then the user. The seeded Kind and
// Area categories are shared and read-only: this spec never edits or deletes them. The thread half (1.2.0 S7, Hammurabi's ADR half adapted): a
// member tags a thread from the side panel's grouped picker, the list's Tags filter finds it, the API refuses an unknown tag, and a CLOSED thread
// takes tags from its owner only. It also owns a throwaway `e2e-tags-*` room and two more users (rooms are removed first, then the users); the
// room copies the seeded Default template and its threads are never deleted (a room's removal hides them).
import type { APIRequestContext, Page } from "@playwright/test";
import { apiAsAdmin, apiAsUser, createUserViaUi, deleteViaApi, expect, login, signOut, specAdmins, test, threadList, throwawayUser, uniqueText } from "./helpers";

/** This file's own administrator (see `specAdmins`): the page loads of its sign-ins spend this account's live-ticket bucket, not the seed administrator's. */
const admin = specAdmins("e2e-tags-admin");

const REGISTRY = "/api/v1/tag-categories";
type Category = { id: number; name: string; revision: number; tags: string[] };

// API teardown, so a failing step leaves neither the category nor the user behind. A category is deleted under its CURRENT revision.
const categoryIds: number[] = [];
const userPaths: string[] = [];
test.afterEach(async () => {
  const ids = categoryIds.splice(0);
  if (ids.length > 0) {
    const { api } = await apiAsAdmin();
    try {
      const items = ((await (await api.get(REGISTRY)).json()) as { items: Category[] }).items;
      for (const id of ids) {
        const category = items.find((item) => item.id === id);
        if (category) expect((await api.delete(`${REGISTRY}/${id}`, { headers: { "X-Expected-Revision": String(category.revision) } })).status()).toBe(204);
      }
    } finally {
      await api.dispose();
    }
  }
  await deleteViaApi(userPaths.splice(0));
});

const rowOf = (page: Page, name: string) => page.getByRole("row").filter({ hasText: name });

test("an admin curates a tag category", async ({ page }) => {
  await admin.login(page);
  const name = uniqueText("E2E Category");
  const tagA = uniqueText("e2e-tag-a");
  const tagB = uniqueText("e2e-tag-b");
  const tagC = uniqueText("e2e-tag-c");

  // The nav leaf leads to the registry, which lists the seeded categories with their tags as pills.
  await page.getByRole("link", { name: "Tags" }).click();
  await expect(page).toHaveURL(/\/tags$/);
  await expect(page.getByRole("heading", { level: 2, name: "Tags" })).toBeVisible();
  await expect(rowOf(page, "Kind").getByText("postmortem", { exact: true })).toBeVisible();
  await expect(rowOf(page, "Area").getByText("infrastructure", { exact: true })).toBeVisible();

  // Create: the tag field takes Enter (or a comma) per tag and the registry keeps each folded to lower case.
  await page.getByRole("button", { name: "New category" }).click();
  const dialog = page.getByRole("dialog", { name: "New tag category" });
  await dialog.getByLabel("Name").fill(name);
  await dialog.getByLabel("Description").fill("Playwright's own category");
  await dialog.getByRole("combobox", { name: "Tags" }).fill(tagA);
  await page.keyboard.press("Enter");
  await dialog.getByRole("combobox", { name: "Tags" }).fill(tagB.toUpperCase());
  await page.keyboard.press("Enter");
  const [created] = await Promise.all([
    page.waitForResponse((r) => r.url().endsWith(REGISTRY) && r.request().method() === "POST" && r.status() === 201),
    dialog.getByRole("button", { name: "Create", exact: true }).click(),
  ]);
  const id = ((await created.json()) as { id: number }).id;
  categoryIds.push(id);
  await expect(dialog).toHaveCount(0);
  await expect(rowOf(page, name).getByText(tagA, { exact: true })).toBeVisible();
  await expect(rowOf(page, name).getByText(tagB, { exact: true })).toBeVisible();

  // Edit: rename it and add a third tag; the PUT is the whole replacement under the revision the editor was opened at.
  const renamed = `${name} renamed`;
  await page.getByRole("button", { name: `Operations for ${name}` }).click();
  await page.getByRole("menuitem", { name: `Edit ${name}` }).click();
  const editor = page.getByRole("dialog", { name: "Edit tag category" });
  await expect(editor.getByLabel("Name")).toHaveValue(name);
  await editor.getByLabel("Name").fill(renamed);
  await editor.getByRole("combobox", { name: "Tags" }).fill(tagC);
  await page.keyboard.press("Enter");
  await Promise.all([
    page.waitForResponse((r) => r.url().endsWith(`${REGISTRY}/${id}`) && r.request().method() === "PUT" && r.status() === 204),
    editor.getByRole("button", { name: "Save", exact: true }).click(),
  ]);
  await expect(editor).toHaveCount(0);
  for (const tag of [tagA, tagB, tagC]) await expect(rowOf(page, renamed).getByText(tag, { exact: true })).toBeVisible();

  // A stale save: another administrator saves the category while this editor is open, so this save is refused in words, keeps the dialog and
  // changes nothing.
  await page.getByRole("button", { name: `Operations for ${renamed}` }).click();
  await page.getByRole("menuitem", { name: `Edit ${renamed}` }).click();
  const stale = page.getByRole("dialog", { name: "Edit tag category" });
  await expect(stale.getByLabel("Name")).toHaveValue(renamed);
  const { api } = await apiAsUser(admin.first.email, admin.first.password);
  const current = ((await (await api.get(REGISTRY)).json()) as { items: Category[] }).items.find((item) => item.id === id)!;
  const other = await api.put(`${REGISTRY}/${id}`, {
    data: { name: renamed, description: null, tags: current.tags },
    headers: { "X-Expected-Revision": String(current.revision) },
  });
  expect(other.status()).toBe(204);
  await stale.getByLabel("Name").fill(`${renamed} again`);
  await stale.getByRole("button", { name: "Save", exact: true }).click();
  await expect(stale.getByText(/Another administrator changed this category after you opened it/)).toBeVisible();
  await stale.getByRole("button", { name: "Cancel", exact: true }).click();
  await expect(stale).toHaveCount(0);
  await expect(rowOf(page, renamed)).toBeVisible();
  const stored = ((await (await api.get(REGISTRY)).json()) as { items: Category[] }).items.find((item) => item.id === id)!;
  expect(stored.name).toBe(renamed);
  await api.dispose();

  // Delete asks first, then retires the category's tags with it: the row and the tags leave the registry.
  await page.getByRole("button", { name: `Operations for ${renamed}` }).click();
  await page.getByRole("menuitem", { name: `Delete ${renamed}` }).click();
  const confirm = page.getByRole("dialog", { name: "Delete tag category?" });
  await expect(confirm.getByText(/marked as retired/)).toBeVisible();
  await Promise.all([
    page.waitForResponse((r) => r.url().endsWith(`${REGISTRY}/${id}`) && r.request().method() === "DELETE" && r.status() === 204),
    confirm.getByRole("button", { name: "Delete", exact: true }).click(),
  ]);
  await expect(rowOf(page, renamed)).toHaveCount(0);
  await expect(page.getByText(tagA, { exact: true })).toHaveCount(0);
});

test("a regular user reads the registry through the API but has no Tags page and no writes", async ({ page }) => {
  await admin.login(page);
  const user = await createUserViaUi(page, "E2E Tag Reader");
  userPaths.push(`/api/v1/users/${user.id}`);
  await signOut(page);

  await login(page, user.email, user.password);
  await expect(page.getByRole("group", { name: "Administration" }).getByRole("link", { name: "Teams" })).toBeVisible();
  await expect(page.getByRole("link", { name: "Tags", exact: true })).toHaveCount(0);
  await page.goto("/tags");
  await expect(page).toHaveURL(/\/$/);
  await expect(page.getByRole("heading", { level: 2, name: "Adder" })).toBeVisible();

  const { api } = await apiAsUser(user.email, user.password);
  try {
    const read = await api.get(REGISTRY);
    expect(read.status()).toBe(200);
    const registry = (await read.json()) as { items: { name: string; tags: string[] }[] };
    expect(registry.items.map((item) => item.name)).toEqual(expect.arrayContaining(["Kind", "Area"]));
    expect(registry.items.find((item) => item.name === "Kind")?.tags).toEqual(["action", "announcement", "decision", "postmortem", "question"]);
    const write = await api.post(REGISTRY, { data: { name: uniqueText("e2e-nope"), tags: [uniqueText("e2e-nope")] } });
    expect(write.status()).toBe(403);
  } finally {
    await api.dispose();
  }
});

type Person = { id: number; name: string; email: string; password: string };

/** A room (copy of the seeded Default template) owned by a throwaway owner, with a throwaway teammate, one thread started by the owner and a category of two tags. */
async function threadScene(page: Page) {
  await admin.login(page);
  const owner = await throwawayUser(page, "E2E Tag Owner", userPaths);
  const mate = await throwawayUser(page, "E2E Tag Mate", userPaths);
  const { api: ownerApi } = await apiAsUser(owner.email, owner.password);
  const apis: APIRequestContext[] = [ownerApi];
  const templates = await ownerApi.get("/api/v1/workflow-templates?name=Default");
  const template = ((await templates.json()) as { items: { id: number; name: string }[] }).items.find((candidate) => candidate.name === "Default");
  expect(template, "the seeded Default template").toBeDefined();
  const created = await ownerApi.post("/api/v1/rooms", { data: { name: uniqueText("e2e-tags-room"), templateId: template!.id } });
  expect(created.status(), await created.text()).toBe(201);
  const roomId = ((await created.json()) as { id: number }).id;
  userPaths.unshift(`/api/v1/rooms/${roomId}`); // rooms before users
  expect((await ownerApi.post(`/api/v1/rooms/${roomId}/members`, { data: { userIds: [mate.id] } })).status()).toBe(200);
  const title = uniqueText("Tagged thread");
  const thread = await ownerApi.post(`/api/v1/rooms/${roomId}/threads`, { data: { title, body: "Seeded through the API." } });
  expect(thread.status(), await thread.text()).toBe(201);
  const other = uniqueText("Untagged thread");
  expect((await ownerApi.post(`/api/v1/rooms/${roomId}/threads`, { data: { title: other, body: "Seeded through the API." } })).status()).toBe(201);
  const number = ((await thread.json()) as { number: number }).number;
  const { api: adminApi } = await apiAsUser(admin.first.email, admin.first.password);
  apis.push(adminApi);
  const tagA = uniqueText("e2e-tag-a");
  const tagB = uniqueText("e2e-tag-b");
  const category = await adminApi.post(REGISTRY, { data: { name: uniqueText("E2E Category"), description: null, tags: [tagA, tagB] } });
  expect(category.status(), await category.text()).toBe(201);
  categoryIds.push(((await category.json()) as { id: number }).id);
  return { owner, mate, ownerApi, roomId, number, title, other, tagA, tagB, dispose: async () => { for (const api of apis) await api.dispose(); } };
}

const openThread = async (page: Page, who: Person, roomId: number, number: number) => {
  await login(page, who.email, who.password);
  await page.goto(`/rooms/${roomId}/threads/${number}`);
  const toggle = page.getByRole("button", { name: "Details", exact: true });
  await expect(toggle).toBeVisible();
  if ((await toggle.getAttribute("aria-expanded")) === "false") await toggle.click();
  await expect(page.getByRole("heading", { level: 4, name: "Tags", exact: true })).toBeVisible();
  return page.locator("[data-section=tags]");
};

test("a member tags a thread, the list filter finds it and the API refuses an unknown tag", async ({ page }) => {
  const scene = await threadScene(page);
  try {
    const section = await openThread(page, scene.mate, scene.roomId, scene.number);
    await expect(section.getByText("No tags")).toBeVisible();

    // The picker groups the registry by category; the save is a PUT that answers 204 and the pill shows in the panel, in the header's tag row and on the list row.
    await section.getByRole("button", { name: "Edit tags" }).click();
    await section.getByRole("combobox", { name: "Tags" }).click();
    await page.getByRole("option", { name: scene.tagA, exact: true }).click();
    await page.keyboard.press("Escape");
    const [saved] = await Promise.all([
      page.waitForResponse((r) => /\/threads\/\d+\/tags$/.test(r.url()) && r.request().method() === "PUT"),
      section.getByRole("button", { name: "Save tags" }).click(),
    ]);
    expect(saved.status()).toBe(204);
    await expect(section.getByText(scene.tagA, { exact: true })).toBeVisible();
    await expect(page.getByRole("group", { name: "Tags", exact: true }).first().getByText(scene.tagA, { exact: true })).toBeVisible();
    const row = threadList(page).getByRole("link", { name: new RegExp(scene.title) });
    await expect(row.getByText(scene.tagA, { exact: true })).toBeVisible();
    await expect(page.getByRole("group", { name: "History", exact: true }).getByText(`Added: ${scene.tagA}`)).toBeVisible();

    // The list's Tags filter writes threadTag into the address and keeps only the tagged thread; the other tag lists none.
    await page.goto(`/rooms/${scene.roomId}`);
    await page.getByRole("button", { name: /^Filters/ }).click();
    await page.getByRole("combobox", { name: "Tags", exact: true }).click();
    await page.getByRole("option", { name: scene.tagA, exact: true }).click();
    await expect(page).toHaveURL(new RegExp(`threadTag=${scene.tagA}`));
    await expect(threadList(page).getByRole("link", { name: new RegExp(scene.title) })).toBeVisible();
    await expect(threadList(page).getByRole("link", { name: new RegExp(scene.other) })).toHaveCount(0);
    await page.goto(`/rooms/${scene.roomId}?threadTag=${scene.tagB}`);
    await expect(threadList(page).getByRole("link", { name: new RegExp(scene.title) })).toHaveCount(0);

    // The API is strict: a name the registry does not hold is a 400 and changes nothing.
    const { api } = await apiAsUser(scene.mate.email, scene.mate.password);
    try {
      const current = (await (await api.get(`/api/v1/rooms/${scene.roomId}/threads/${scene.number}`)).json()) as { revision: number };
      const refused = await api.put(`/api/v1/rooms/${scene.roomId}/threads/${scene.number}/tags`, {
        data: { tags: [scene.tagA, uniqueText("e2e-unregistered")] },
        headers: { "X-Expected-Revision": String(current.revision) },
      });
      expect(refused.status()).toBe(400);
      const after = (await (await api.get(`/api/v1/rooms/${scene.roomId}/threads/${scene.number}`)).json()) as { revision: number; tags: { name: string }[] };
      expect(after.tags.map((tag) => tag.name)).toEqual([scene.tagA]);
      expect(after.revision).toBe(current.revision);
    } finally {
      await api.dispose();
    }
  } finally {
    await scene.dispose();
  }
});

test("a closed thread takes tags from its owner only", async ({ page }) => {
  const scene = await threadScene(page);
  try {
    const closed = await scene.ownerApi.put(`/api/v1/rooms/${scene.roomId}/threads/${scene.number}/status`, { data: { status: "done" } });
    expect(closed.status(), await closed.text()).toBe(204);

    // A plain member sees the tags and the reason, no Edit button; the API says 403 before it reads the header or the body.
    const section = await openThread(page, scene.mate, scene.roomId, scene.number);
    await expect(section.getByText(/only its owner, the room owner or an administrator can change its tags/)).toBeVisible();
    await expect(section.getByRole("button", { name: "Edit tags" })).toHaveCount(0);
    const { api } = await apiAsUser(scene.mate.email, scene.mate.password);
    try {
      const refused = await api.put(`/api/v1/rooms/${scene.roomId}/threads/${scene.number}/tags`, { data: { tags: [scene.tagA] } });
      expect(refused.status()).toBe(403);
    } finally {
      await api.dispose();
    }

    // The thread's owner tags it without reopening it.
    await signOut(page);
    const ownerSection = await openThread(page, scene.owner, scene.roomId, scene.number);
    await ownerSection.getByRole("button", { name: "Edit tags" }).click();
    await ownerSection.getByRole("combobox", { name: "Tags" }).click();
    await page.getByRole("option", { name: scene.tagB, exact: true }).click();
    await page.keyboard.press("Escape");
    await ownerSection.getByRole("button", { name: "Save tags" }).click();
    await expect(ownerSection.getByText(scene.tagB, { exact: true })).toBeVisible();
    const after = (await (await scene.ownerApi.get(`/api/v1/rooms/${scene.roomId}/threads/${scene.number}`)).json()) as { closed: boolean };
    expect(after.closed).toBe(true);
  } finally {
    await scene.dispose();
  }
});
