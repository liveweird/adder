// Find: the thread list's filters (status, owner, a SELECT field, hashtags; the badge, Clear, the URL that survives a reload), hashtags in messages
// (a link that filters the list, gone when the tag is edited away), a thread's hashtags on its row and in its header, `#42` as a link, the room's own search (a Polish word typed without its accents, the highlighted
// snippet, the jump to the message, the client's refusal of a short term), the search across rooms (only the reader's rooms, an administrator's too;
// reached through the command palette), a tag picked on its own (no term) across rooms (1.2.0 S8) and the focus view. Owns: its throwaway `e2e-find-*` rooms and users (one of them an administrator,
// minted through the seed administrator's API), removed through the API in an `afterEach` (rooms first, then the users). Every room is created by a
// throwaway user and only copies the seeded Default or Incident template. Threads and messages are never deleted: a room's removal hides them.
// Every user runs a handful of searches, far below the per-user `search` bucket (60 a minute) that no other spec's account shares.
import type { APIRequestContext, Page } from "@playwright/test";
import { randomUUID } from "node:crypto";
import { apiAsAdmin, apiAsUser, conversation, deleteViaApi, expect, login, specAdmins, test, threadList, throwawayUser, uniqueText } from "./helpers";

/** This file's own administrator (see `specAdmins`): the page loads of its sign-ins spend this account's live-ticket bucket, not the seed administrator's. */
const admin = specAdmins("e2e-find-admin");

const cleanup: string[] = [];
const apis: APIRequestContext[] = [];
/** Tag categories this file's tests made (the registry is shared): deleted under their CURRENT revision, as the seed administrator. */
const categoryIds: number[] = [];
test.afterEach(async () => {
  for (const api of apis.splice(0)) await api.dispose();
  await deleteViaApi(cleanup.splice(0));
  const ids = categoryIds.splice(0);
  if (ids.length > 0) {
    const { api } = await apiAsAdmin();
    try {
      const items = ((await (await api.get("/api/v1/tag-categories?pageSize=100")).json()) as { items: { id: number; revision: number }[] }).items;
      for (const id of ids) {
        const category = items.find((item) => item.id === id);
        if (category) expect((await api.delete(`/api/v1/tag-categories/${id}`, { headers: { "X-Expected-Revision": String(category.revision) } })).status()).toBe(204);
      }
    } finally {
      await api.dispose();
    }
  }
});

const MESSAGES_API = /\/api\/v1\/rooms\/\d+\/threads\/\d+\/messages$/;
const MESSAGE_API = /\/api\/v1\/rooms\/\d+\/messages\/\d+$/;
const ROOM_SEARCH_API = /\/api\/v1\/rooms\/\d+\/search\?/;

interface Person {
  id: number;
  name: string;
  email: string;
  password: string;
}

/** The thread list is the labelled `nav`, the conversation the labelled region. */
const threadLink = (page: Page, title: string) => threadList(page).getByRole("link", { name: new RegExp(title) });
const messageNamed = (page: Page, text: string) => conversation(page).getByRole("listitem").filter({ hasText: text });
const filtersToggle = (page: Page) => page.getByRole("button", { name: /^Filters/ });

/** A word no other run's data holds, in one token: the full text search would otherwise split it at every hyphen. */
const rareWord = (prefix: string) => `${prefix}${randomUUID().replace(/-/g, "").slice(0, 8)}`;

/** Create a room as `api`'s user from a seeded template, register its cleanup and return its id. */
async function createRoom(api: APIRequestContext, name: string, template: "Default" | "Incident"): Promise<number> {
  const templates = await api.get(`/api/v1/workflow-templates?name=${template}`);
  expect(templates.status(), await templates.text()).toBe(200);
  const found = ((await templates.json()) as { items: { id: number; name: string }[] }).items.find((candidate) => candidate.name === template);
  expect(found, `the seeded ${template} template`).toBeDefined();
  const created = await api.post("/api/v1/rooms", { data: { name, templateId: found!.id } });
  expect(created.status(), await created.text()).toBe(201);
  const id = ((await created.json()) as { id: number }).id;
  cleanup.unshift(`/api/v1/rooms/${id}`);
  return id;
}

async function addMembers(api: APIRequestContext, roomId: number, people: Person[]): Promise<void> {
  const added = await api.post(`/api/v1/rooms/${roomId}/members`, { data: { userIds: people.map((person) => person.id) } });
  expect(added.status(), await added.text()).toBe(200);
}

/** Start a thread as `api`'s user; returns its number. */
async function startThread(api: APIRequestContext, roomId: number, title: string, body: string, fields?: Record<string, string>): Promise<number> {
  const created = await api.post(`/api/v1/rooms/${roomId}/threads`, { data: { title, body, fields } });
  expect(created.status(), await created.text()).toBe(201);
  return ((await created.json()) as { number: number }).number;
}

async function postMessage(api: APIRequestContext, roomId: number, number: number, body: string): Promise<void> {
  const posted = await api.post(`/api/v1/rooms/${roomId}/threads/${number}/messages`, { data: { body } });
  expect(posted.status(), await posted.text()).toBe(201);
}

async function moveThread(api: APIRequestContext, roomId: number, number: number, status: string): Promise<void> {
  const moved = await api.put(`/api/v1/rooms/${roomId}/threads/${number}/status`, { data: { status } });
  expect(moved.status(), await moved.text()).toBe(204);
}

/** The file's administrator (signed in on `page`) mints regular users through the Users page, then `page` is left signed in as the first of them. */
async function people(page: Page, ...names: string[]): Promise<Person[]> {
  await admin.login(page);
  const minted: Person[] = [];
  for (const name of names) minted.push(await throwawayUser(page, name, cleanup));
  await login(page, minted[0].email, minted[0].password);
  return minted;
}

async function actingAs(person: Person): Promise<APIRequestContext> {
  const { api } = await apiAsUser(person.email, person.password);
  apis.push(api);
  return api;
}

/** Open the filter panel (collapsed on a fresh browser) and return its controls. */
async function openPanel(page: Page) {
  const toggle = filtersToggle(page);
  await expect(toggle).toBeVisible();
  if ((await toggle.getAttribute("aria-expanded")) !== "true") await toggle.click();
  await expect(toggle).toHaveAttribute("aria-expanded", "true");
}

/** Pick one option of a filter's multi-select: open it through its label, then click the option (its name may carry a count). */
async function pick(page: Page, label: string, option: string | RegExp): Promise<void> {
  await page.getByRole("combobox", { name: label, exact: true }).click();
  await page.getByRole("option", { name: option }).click();
  await page.keyboard.press("Escape");
}

test("a status and owner filter narrows the thread list and survives a reload", async ({ page }) => {
  const [owner, mate] = await people(page, "E2E Find Owner", "E2E Find Mate");
  const api = await actingAs(owner);
  const mateApi = await actingAs(mate);
  const roomId = await createRoom(api, uniqueText("e2e-find"), "Incident");
  await addMembers(api, roomId, [mate]);
  const alpha = uniqueText("Alpha");
  const bravo = uniqueText("Bravo");
  const charlie = uniqueText("Charlie");
  await startThread(api, roomId, alpha, "Disk alert #outage", { severity: "s1" });
  const bravoNumber = await startThread(mateApi, roomId, bravo, "Pager noise", { severity: "s2" });
  const charlieNumber = await startThread(api, roomId, charlie, "Cache flush #outage", { severity: "s2" });
  await moveThread(api, roomId, bravoNumber, "investigating");
  await moveThread(api, roomId, charlieNumber, "investigating");

  await page.goto(`/rooms/${roomId}`);
  for (const title of [alpha, bravo, charlie]) await expect(threadLink(page, title)).toBeVisible();
  await openPanel(page);
  await expect(filtersToggle(page).getByText(/^\d+$/)).toHaveCount(0);

  // Status: Investigating holds Bravo and Charlie; the badge counts one filter.
  await pick(page, "Status", /^Investigating/);
  await expect(page).toHaveURL(/[?&]status=investigating(&|$)/);
  await expect(threadLink(page, alpha)).toHaveCount(0);
  await expect(threadLink(page, bravo)).toBeVisible();
  await expect(threadLink(page, charlie)).toBeVisible();
  await expect(filtersToggle(page)).toContainText("1");

  // Mine: only the threads the signed-in user owns, Charlie; the badge counts two.
  const mine = page.getByRole("button", { name: "Mine", exact: true });
  await expect(mine).toHaveAttribute("aria-pressed", "false");
  await mine.click();
  await expect(mine).toHaveAttribute("aria-pressed", "true");
  await expect(page).toHaveURL(/[?&]owner=me(&|$)/);
  await expect(threadLink(page, bravo)).toHaveCount(0);
  await expect(threadLink(page, charlie)).toBeVisible();
  await expect(filtersToggle(page)).toContainText("2");

  // A SELECT field: S1 matches none of these two; the empty state names the filter, not the room. Swapping S1 for S2 brings Charlie back.
  await pick(page, "Severity", "S1");
  await expect(page).toHaveURL(/[?&]field=severity(%3A|:)s1(&|$)/);
  await expect(page.getByText("No threads match these filters.")).toBeVisible();
  await expect(filtersToggle(page)).toContainText("3");
  await page.getByRole("button", { name: "Remove S1", exact: true }).click();
  await pick(page, "Severity", "S2");
  await expect(threadLink(page, charlie)).toBeVisible();
  await expect(page.getByText("No threads match these filters.")).toHaveCount(0);

  // Hashtags: the picker offers the room's tags with their thread counts; Charlie carries #outage.
  await page.getByRole("combobox", { name: "Hashtags", exact: true }).click();
  await expect(page.getByRole("option", { name: /^#outage\s*\(2\)$/ })).toBeVisible();
  await page.getByRole("option", { name: /^#outage/ }).click();
  await page.keyboard.press("Escape");
  await expect(page).toHaveURL(/[?&]tag=outage(&|$)/);
  await expect(threadLink(page, charlie)).toBeVisible();
  await expect(filtersToggle(page)).toContainText("4");

  // The URL is the state: a reload keeps every filter, the badge and the narrowed list.
  await page.reload();
  await expect(page).toHaveURL(/status=investigating/);
  await expect(page).toHaveURL(/owner=me/);
  await expect(page).toHaveURL(/tag=outage/);
  await expect(threadLink(page, charlie)).toBeVisible();
  await expect(threadLink(page, alpha)).toHaveCount(0);
  await expect(threadLink(page, bravo)).toHaveCount(0);
  await expect(filtersToggle(page)).toContainText("4");
  await expect(page.getByRole("button", { name: "Mine", exact: true })).toHaveAttribute("aria-pressed", "true");

  // Clear: every panel filter goes at once, the badge with them, and the plain list is back.
  await page.getByRole("button", { name: "Clear filters", exact: true }).click();
  for (const title of [alpha, bravo, charlie]) await expect(threadLink(page, title)).toBeVisible();
  await expect(page).not.toHaveURL(/status=|owner=|tag=|field=/);
  await expect(filtersToggle(page).getByText(/^\d+$/)).toHaveCount(0);
  await expect(page.getByRole("button", { name: "Clear filters", exact: true })).toHaveCount(0);
});

test("a hashtag in a message is a link that shows the threads using it", async ({ page }) => {
  const [owner] = await people(page, "E2E Find Owner");
  const api = await actingAs(owner);
  const roomId = await createRoom(api, uniqueText("e2e-find"), "Default");
  const tag = rareWord("rollback");
  const first = uniqueText("Release");
  const second = uniqueText("Hotfix");
  const third = uniqueText("Lunch");
  const firstNumber = await startThread(api, roomId, first, "Release notes are ready.");
  await startThread(api, roomId, second, `Patch shipped, see #${tag.toUpperCase()}.`);
  await startThread(api, roomId, third, "Where do we eat?");

  // A reply typed in the composer carries the tag; it renders as a link named after the tag.
  await page.goto(`/rooms/${roomId}/threads/${firstNumber}`);
  const box = page.getByRole("textbox", { name: "Reply", exact: true });
  await box.fill(`Held back until #${tag} is verified.`);
  await Promise.all([
    page.waitForResponse((r) => r.request().method() === "POST" && MESSAGES_API.test(r.url()) && r.status() === 201),
    box.press("Control+Enter"),
  ]);
  const posted = messageNamed(page, "Held back until");
  await expect(posted).toHaveCount(1);
  const link = posted.getByRole("link", { name: `#${tag}`, exact: true });
  await expect(link).toBeVisible();

  // The link stays in the app and narrows the list to the threads using the tag, whatever the case it was typed in, closed ones included.
  await link.click();
  await expect(page).toHaveURL(new RegExp(`/rooms/${roomId}/threads/${firstNumber}\\?.*tag=${tag}`));
  await expect(page).toHaveURL(/scope=all/);
  await expect(threadLink(page, first)).toBeVisible();
  await expect(threadLink(page, second)).toBeVisible();
  await expect(threadLink(page, third)).toHaveCount(0);
  await openPanel(page);
  await expect(filtersToggle(page)).toContainText("1");
  await expect(page.getByRole("button", { name: `Remove #${tag}`, exact: true })).toBeVisible();

  // Editing the tag out of the message takes its thread out of the filtered list; the other thread stays.
  await posted.getByRole("button", { name: /^Operations for message by / }).click();
  await page.getByRole("menuitem", { name: "Edit", exact: true }).click();
  await page.getByRole("textbox", { name: "Edit message", exact: true }).fill("Held back until it is verified.");
  await Promise.all([
    page.waitForResponse((r) => r.request().method() === "PUT" && MESSAGE_API.test(r.url()) && r.status() === 204),
    page.getByRole("button", { name: "Save", exact: true }).click(),
  ]);
  await expect(messageNamed(page, "it is verified")).toBeVisible();
  await expect(threadLink(page, first)).toHaveCount(0);
  await expect(threadLink(page, second)).toBeVisible();
});

/** Reply in the open thread's composer and wait for the 201: the message is on screen once this returns. */
async function reply(page: Page, text: string): Promise<void> {
  const box = page.getByRole("textbox", { name: "Reply", exact: true });
  await box.fill(text);
  await Promise.all([
    page.waitForResponse((r) => r.request().method() === "POST" && MESSAGES_API.test(r.url()) && r.status() === 201),
    box.press("Control+Enter"),
  ]);
}

test("a thread's hashtags show on its list row and in its header", async ({ page }) => {
  const [owner] = await people(page, "E2E Find Owner");
  const api = await actingAs(owner);
  const roomId = await createRoom(api, uniqueText("e2e-find"), "Default");
  const first = rareWord("outage");
  const second = rareWord("rollback");
  const release = uniqueText("Release");
  const lunch = uniqueText("Lunch");
  const number = await startThread(api, roomId, release, `Shipping, see #${first.toUpperCase()}.`);
  await startThread(api, roomId, lunch, "Where do we eat?");

  // The row says the thread's hashtags in dimmed text (the stored, folded key) and holds no link of its own: the row is the one link.
  await page.goto(`/rooms/${roomId}`);
  const row = threadLink(page, release);
  await expect(row).toContainText(`#${first}`);
  await expect(row.getByRole("link")).toHaveCount(0);
  await expect(threadLink(page, lunch).locator("[data-row-hashtags]")).toHaveCount(0);

  // The pane header's third row links them; a reply adds one, which both the header and the row pick up with no reload.
  await row.click();
  const header = page.getByRole("group", { name: "Hashtags", exact: true }).first();
  await expect(header.getByRole("link", { name: `#${first}`, exact: true })).toBeVisible();
  await reply(page, `Held back until #${second} is verified.`);
  await expect(header.getByRole("link", { name: `#${second}`, exact: true })).toBeVisible();
  await expect(row).toContainText(`#${second}`);

  // Editing it out drops it from the header (the edit re-reads the thread) and from the row.
  const posted = messageNamed(page, "Held back until");
  await posted.getByRole("button", { name: /^Operations for message by / }).click();
  await page.getByRole("menuitem", { name: "Edit", exact: true }).click();
  await page.getByRole("textbox", { name: "Edit message", exact: true }).fill("Held back until it is verified.");
  await Promise.all([
    page.waitForResponse((r) => r.request().method() === "PUT" && MESSAGE_API.test(r.url()) && r.status() === 204),
    page.getByRole("button", { name: "Save", exact: true }).click(),
  ]);
  await expect(messageNamed(page, "it is verified")).toBeVisible();
  await expect(header.getByRole("link", { name: `#${second}`, exact: true })).toHaveCount(0);
  await expect(row).not.toContainText(`#${second}`);

  // A header link narrows the list to the threads that use the hashtag, closed ones included.
  await header.getByRole("link", { name: `#${first}`, exact: true }).click();
  await expect(page).toHaveURL(new RegExp(`/rooms/${roomId}/threads/${number}\\?.*tag=${first}`));
  await expect(page).toHaveURL(/scope=all/);
  await expect(threadLink(page, release)).toBeVisible();
  await expect(threadLink(page, lunch)).toHaveCount(0);
});

test("a thread number in a message links to that thread", async ({ page }) => {
  const [owner] = await people(page, "E2E Find Owner");
  const api = await actingAs(owner);
  const roomId = await createRoom(api, uniqueText("e2e-find"), "Default");
  const origin = uniqueText("Origin");
  const target = uniqueText("Target");
  const originNumber = await startThread(api, roomId, origin, "Where it started.");
  const targetNumber = await startThread(api, roomId, target, "Where it went.");
  const missing = 9_000_000 + originNumber;
  await postMessage(api, roomId, originNumber, `Duplicate of #${targetNumber}, never \`#${targetNumber}\`, and see #${missing}.`);

  // A number is a link named after it; in code it is not one. The link keeps the list's keys and leads to that thread of the same room.
  await page.goto(`/rooms/${roomId}/threads/${originNumber}?scope=all`);
  const message = messageNamed(page, "Duplicate of");
  await expect(message.getByRole("link", { name: `#${targetNumber}`, exact: true })).toHaveCount(1);
  await expect(message.locator("code")).toContainText(`#${targetNumber}`);
  await message.getByRole("link", { name: `#${targetNumber}`, exact: true }).click();
  await expect(page).toHaveURL(new RegExp(`/rooms/${roomId}/threads/${targetNumber}\\?.*scope=all`));
  await expect(page.getByRole("heading", { level: 3, name: `#${targetNumber} ${target}` })).toBeVisible();

  // Nothing checks that a number exists: one with no thread opens the pane's "does not exist".
  await page.goBack();
  await page.getByRole("link", { name: `#${missing}`, exact: true }).click();
  await expect(page).toHaveURL(new RegExp(`/rooms/${roomId}/threads/${missing}`));
  await expect(page.getByText("This thread does not exist.")).toBeVisible();
});

test("a room search finds a Polish word typed without accents and opens the message", async ({ page }) => {
  const [owner, mate] = await people(page, "E2E Find Owner", "E2E Find Mate");
  const api = await actingAs(owner);
  const mateApi = await actingAs(mate);
  const roomId = await createRoom(api, uniqueText("e2e-find"), "Default");
  await addMembers(api, roomId, [mate]);
  const title = uniqueText("Wdrożenie");
  const number = await startThread(api, roomId, title, "Zaczynamy wdrożenie.");
  await postMessage(mateApi, roomId, number, "Żółwie tempo wdrożenia to największy problem.");
  for (const text of ["Sprawdzam logi.", "Rollback gotowy.", "Czekamy na zielone testy."]) await postMessage(api, roomId, number, text);

  // The Search tab of the room opens an empty form with the rules of matching.
  await page.goto(`/rooms/${roomId}`);
  await page.getByRole("navigation", { name: "Room pages", exact: true }).getByRole("link", { name: "Search", exact: true }).click();
  await expect(page).toHaveURL(new RegExp(`/rooms/${roomId}/search$`));
  const box = page.getByRole("searchbox", { name: "Keywords", exact: true });
  await expect(box).toBeVisible();

  // One character is refused by the client, in words, without a request.
  let searches = 0;
  page.on("request", (request) => {
    if (ROOM_SEARCH_API.test(request.url())) searches += 1;
  });
  await box.fill("a");
  await page.getByRole("button", { name: "Search", exact: true }).click();
  await expect(page.getByRole("alert").filter({ hasText: "Type at least two characters" })).toBeVisible();
  expect(searches, "a term the server would refuse never travels").toBe(0);

  // "zolw" has none of the accents; the word still matches from its start, and the match is marked in the snippet.
  await box.fill("zolw");
  await Promise.all([
    page.waitForResponse((r) => ROOM_SEARCH_API.test(r.url()) && r.status() === 200),
    page.getByRole("button", { name: "Search", exact: true }).click(),
  ]);
  await expect(page).toHaveURL(/[?&]q=zolw(&|$)/);
  await expect(page.getByText("1 thread found")).toBeVisible();
  const hit = page.getByRole("list", { name: "Search results", exact: true }).getByRole("article");
  await expect(hit).toHaveCount(1);
  await expect(hit).toContainText(title);
  await expect(hit.locator("mark")).toHaveText(["Żółwie"]);
  await expect(hit).toContainText("1 matching message");

  // The message hit opens the thread at that message: focused, highlighted and announced.
  await hit.getByRole("link", { name: /Żółwie tempo/ }).click();
  await expect(page).toHaveURL(new RegExp(`/rooms/${roomId}/threads/${number}\\?.*message=\\d+`));
  await expect(page.getByRole("heading", { level: 3, name: `#${number} ${title}` })).toBeVisible();
  const found = messageNamed(page, "Żółwie tempo");
  await expect(found).toHaveCount(1);
  await expect(found).toBeFocused();
  await expect(found).toHaveAttribute("data-anchor", "true");
  await expect(page.getByText(`Found the message by ${mate.name}.`)).toBeAttached();
});

test("a search across rooms never shows a room the reader is not a member of", async ({ page }) => {
  const [reader, stranger] = await people(page, "E2E Find Reader", "E2E Find Stranger");
  // An administrator is no more a member of a room than anyone: the account is made through the seed administrator's API, like the others.
  const adminPassword = `E2e-${randomUUID()}`.slice(0, 40);
  const adminName = uniqueText("E2E Find Admin");
  const adminEmail = `${adminName.toLowerCase().replace(/[^a-z0-9-]/g, "-")}@adder.local`;
  const { api: seedApi } = await apiAsAdmin();
  apis.push(seedApi);
  const minted = await seedApi.post("/api/v1/users", { data: { name: adminName, email: adminEmail, password: adminPassword, roles: ["ADMIN"] } });
  expect(minted.status(), await minted.text()).toBe(201);
  const administrator: Person = { id: ((await minted.json()) as { id: number }).id, name: adminName, email: adminEmail, password: adminPassword };
  cleanup.push(`/api/v1/users/${administrator.id}`);

  const readerApi = await actingAs(reader);
  const strangerApi = await actingAs(stranger);
  const word = rareWord("kestrel");
  const roomA = uniqueText("e2e-find-a");
  const roomB = uniqueText("e2e-find-b");
  const roomC = uniqueText("e2e-find-c");
  const idA = await createRoom(readerApi, roomA, "Default");
  const idB = await createRoom(readerApi, roomB, "Default");
  const idC = await createRoom(strangerApi, roomC, "Default");
  await addMembers(readerApi, idA, [administrator]);
  const titles = { a: uniqueText("Aviary"), b: uniqueText("Belfry"), c: uniqueText("Cellar") };
  await startThread(readerApi, idA, titles.a, `The ${word} nests here.`);
  await startThread(readerApi, idB, titles.b, `Another ${word} on the roof.`);
  await startThread(strangerApi, idC, titles.c, `Private ${word} sighting.`);

  // The stranger's room does hold the word: its own member finds it, so the reader's missing hit below is the scope's doing.
  const control = await strangerApi.get(`/api/v1/search?q=${word}`);
  expect(control.status(), await control.text()).toBe(200);
  expect(((await control.json()) as { total: number }).total).toBe(1);

  // The reader reaches the search through the command palette: with a searchable term it offers "Search all rooms for ...".
  await page.goto("/");
  await page.getByRole("button", { name: /^Search or jump to a page/ }).click();
  await page.getByPlaceholder("Search or jump to a page…").fill(word);
  await page.getByRole("button", { name: `Search all rooms for “${word}”` }).click();
  await expect(page).toHaveURL(new RegExp(`/search\\?q=${word}$`));
  await expect(page.getByRole("searchbox", { name: "Search all your rooms", exact: true })).toHaveValue(word);
  await expect(page.getByText("2 threads found")).toBeVisible();
  const hits = page.getByRole("list", { name: "Search results", exact: true }).getByRole("article");
  await expect(hits).toHaveCount(2);
  await expect(hits.filter({ hasText: titles.a })).toContainText(roomA);
  await expect(hits.filter({ hasText: titles.b })).toContainText(roomB);
  await expect(page.getByText(roomC)).toHaveCount(0);
  await expect(page.getByText(titles.c)).toHaveCount(0);

  // An administrator reads the same page with the same scope: the one room they were added to, and neither of the others.
  await login(page, administrator.email, administrator.password);
  await page.goto("/search");
  const box = page.getByRole("searchbox", { name: "Search all your rooms", exact: true });
  await box.fill(word);
  await Promise.all([
    page.waitForResponse((r) => r.url().includes("/api/v1/search?") && r.status() === 200),
    page.getByRole("button", { name: "Search", exact: true }).click(),
  ]);
  await expect(page.getByText("1 thread found")).toBeVisible();
  const adminHits = page.getByRole("list", { name: "Search results", exact: true }).getByRole("article");
  await expect(adminHits).toHaveCount(1);
  await expect(adminHits).toContainText(titles.a);
  await expect(adminHits).toContainText(roomA);
  for (const hidden of [titles.b, roomB, titles.c, roomC]) await expect(page.getByText(hidden)).toHaveCount(0);
});

test("a tag alone finds its threads across rooms", async ({ page }) => {
  const [reader, stranger] = await people(page, "E2E Find Tagger", "E2E Find Outsider");
  const readerApi = await actingAs(reader);
  const strangerApi = await actingAs(stranger);
  const { api: adminApi } = await apiAsUser(admin.first.email, admin.first.password);
  apis.push(adminApi);
  // A tag name is unique for ever (a retired one still holds it), so each run carries its own; the category goes in afterEach.
  const tag = uniqueText("e2e-find-tag");
  const category = await adminApi.post("/api/v1/tag-categories", { data: { name: uniqueText("E2E Find Category"), description: null, tags: [tag] } });
  expect(category.status(), await category.text()).toBe(201);
  categoryIds.push(((await category.json()) as { id: number }).id);

  const rooms = { a: uniqueText("e2e-find-a"), b: uniqueText("e2e-find-b"), c: uniqueText("e2e-find-c") };
  const idA = await createRoom(readerApi, rooms.a, "Default");
  const idB = await createRoom(readerApi, rooms.b, "Default");
  const idC = await createRoom(strangerApi, rooms.c, "Default");
  const word = rareWord("heron");
  const titles = { a: uniqueText("Atrium"), b: uniqueText("Boiler"), c: uniqueText("Cistern"), plain: uniqueText("Plain") };
  const numberA = await startThread(readerApi, idA, titles.a, `The ${word} stands here.`);
  const numberB = await startThread(readerApi, idB, titles.b, "Another thread, no special word.");
  const numberC = await startThread(strangerApi, idC, titles.c, "Somebody else's room.");
  await startThread(readerApi, idA, titles.plain, "Carries no tag at all.");
  /** Tag a thread with `tag` as its member (a fresh thread is at revision 1). */
  async function tagThread(api: APIRequestContext, roomId: number, number: number): Promise<void> {
    const saved = await api.put(`/api/v1/rooms/${roomId}/threads/${number}/tags`, { data: { tags: [tag] }, headers: { "X-Expected-Revision": "1" } });
    expect(saved.status(), await saved.text()).toBe(204);
  }
  await tagThread(readerApi, idA, numberA);
  await tagThread(readerApi, idB, numberB);
  await tagThread(strangerApi, idC, numberC);

  // The control: the stranger's own search by the tag finds their one thread, so the reader's missing hit below is the scope's doing.
  const control = await strangerApi.get(`/api/v1/search?threadTag=${tag}`);
  expect(control.status(), await control.text()).toBe(200);
  expect(((await control.json()) as { total: number }).total).toBe(1);

  // The reader picks the tag in the search page's grouped Tags control and presses nothing else: no term, and still a search.
  await page.goto("/search");
  const box = page.getByRole("searchbox", { name: "Search all your rooms", exact: true });
  await expect(box).toHaveValue("");
  await page.getByRole("combobox", { name: "Tags", exact: true }).click();
  const [searched] = await Promise.all([
    page.waitForResponse((r) => r.url().includes("/api/v1/search?") && r.url().includes("threadTag=") && r.status() === 200),
    page.getByRole("option", { name: tag, exact: true }).click(),
  ]);
  expect(searched.url()).not.toContain("q=");
  await page.keyboard.press("Escape");
  await expect(page).toHaveURL(new RegExp(`[?&]threadTag=${tag}(&|$)`));
  await expect(page.getByText("2 threads found")).toBeVisible();
  const hits = page.getByRole("list", { name: "Search results", exact: true }).getByRole("article");
  await expect(hits).toHaveCount(2);
  await expect(hits.filter({ hasText: titles.a })).toContainText(rooms.a);
  await expect(hits.filter({ hasText: titles.b })).toContainText(rooms.b);
  // Each hit shows its tag as a pill and no snippet (there is no term to mark); nothing of the stranger's room or the untagged thread shows.
  await expect(hits.filter({ hasText: titles.a }).getByRole("group", { name: "Tags", exact: true })).toContainText(tag);
  await expect(hits.filter({ hasText: titles.a }).getByText(/matching message/)).toHaveCount(0);
  for (const hidden of [titles.c, titles.plain, rooms.c]) await expect(page.getByText(hidden)).toHaveCount(0);

  // A term beside the tag narrows it; the address keeps both over a reload.
  await box.fill(word);
  await Promise.all([
    page.waitForResponse((r) => r.url().includes("/api/v1/search?") && r.url().includes(`q=${word}`) && r.status() === 200),
    page.getByRole("button", { name: "Search", exact: true }).click(),
  ]);
  await expect(page).toHaveURL(new RegExp(`q=${word}&threadTag=${tag}$`));
  await expect(page.getByText("1 thread found")).toBeVisible();
  await expect(hits).toHaveCount(1);
  await expect(hits).toContainText(titles.a);
  await page.reload();
  await expect(box).toHaveValue(word);
  await expect(page.getByText("1 thread found")).toBeVisible();
});

test("the focus view hides the thread list and survives a reload", async ({ page }) => {
  const [owner] = await people(page, "E2E Find Owner");
  const api = await actingAs(owner);
  const roomId = await createRoom(api, uniqueText("e2e-find"), "Default");
  const title = uniqueText("Deep dive");
  const other = uniqueText("Side thread");
  const number = await startThread(api, roomId, title, "Reading this in peace.");
  await startThread(api, roomId, other, "Something else.");

  await page.goto(`/rooms/${roomId}`);
  await threadLink(page, title).click();
  await expect(page.getByRole("heading", { level: 3, name: `#${number} ${title}` })).toBeVisible();
  const focus = page.getByRole("button", { name: "Focus on this thread", exact: true });
  await expect(focus).toHaveAttribute("aria-pressed", "false");
  await expect(threadList(page)).toBeVisible();

  // On: the list goes, the address says so and the conversation stays.
  await focus.click();
  await expect(focus).toHaveAttribute("aria-pressed", "true");
  await expect(page).toHaveURL(/[?&]focus=1(&|$)/);
  await expect(threadList(page)).toBeHidden();
  await expect(messageNamed(page, "Reading this in peace.")).toBeVisible();

  // A reload keeps the view: the URL is the only state.
  await page.reload();
  await expect(page.getByRole("button", { name: "Focus on this thread", exact: true })).toHaveAttribute("aria-pressed", "true");
  await expect(threadList(page)).toBeHidden();
  await expect(messageNamed(page, "Reading this in peace.")).toBeVisible();

  // Off: the list is back, with its rows, and the key leaves the address.
  await page.getByRole("button", { name: "Focus on this thread", exact: true }).click();
  await expect(page).not.toHaveURL(/focus=/);
  await expect(threadList(page)).toBeVisible();
  await expect(threadLink(page, other)).toBeVisible();
  await expect(page.getByRole("button", { name: "Focus on this thread", exact: true })).toHaveAttribute("aria-pressed", "false");
});
