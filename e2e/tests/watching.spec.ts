// Watching (1.1.0): a member watches a thread and stops (the toggle in the thread pane, the eye mark on the thread's row, the "Watching" filter,
// the watcher count), and the creator of a thread watches it from the start. The caller's own state: nothing is pushed, so every check reads
// the page after its own action.
// Owns: its throwaway `e2e-watch-*` rooms and users (and its own throwaway administrator, which only mints them), removed through the API in an
// `afterEach` (rooms first, then the users). Every room is created by a throwaway user through the API and only copies the seeded Default
// template. Threads are never deleted: a room's removal hides them. The per-user `read-state` and `chat-post` buckets are only ever spent by
// throwaway users, far below their limits.
import { type APIRequestContext, type Page } from "@playwright/test";
import { apiAsUser, deleteViaApi, expect, login, specAdmins, test, threadList, throwawayUser, uniqueText } from "./helpers";

/** This file's own administrator (see `specAdmins`): the page loads of its sign-ins spend this account's live-ticket bucket, not the seed administrator's. */
const admin = specAdmins("e2e-watch-admin");

const cleanup: string[] = [];
const apis: APIRequestContext[] = [];
test.afterEach(async () => {
  for (const api of apis.splice(0)) await api.dispose();
  await deleteViaApi(cleanup.splice(0));
});

interface Person {
  id: number;
  name: string;
  email: string;
  password: string;
}

/** The room of the scene: created by `owner` through the API with `mate` added as a plain member; `api` acts as the owner. */
interface Scene {
  owner: Person;
  mate: Person;
  roomId: number;
  api: APIRequestContext;
}

const WATCH_API = /\/api\/v1\/rooms\/\d+\/threads\/\d+\/watch$/;
const threadLink = (page: Page, title: string) => threadList(page).getByRole("link", { name: new RegExp(title) });
const watchButton = (page: Page) => page.getByRole("button", { name: "Watch this thread", exact: true });
const WATCHING_MARK = /You are watching this thread/;

/** The administrator (signed in on `page`) mints an owner and a teammate; the owner makes a room and adds the teammate, all through the API. */
async function scene(page: Page): Promise<Scene> {
  await admin.login(page);
  const owner = await throwawayUser(page, "E2E Watch Owner", cleanup);
  const mate = await throwawayUser(page, "E2E Watch Mate", cleanup);
  const { api } = await apiAsUser(owner.email, owner.password);
  apis.push(api);
  const templates = await api.get("/api/v1/workflow-templates?name=Default");
  expect(templates.status(), await templates.text()).toBe(200);
  const template = ((await templates.json()) as { items: { id: number; name: string }[] }).items.find((candidate) => candidate.name === "Default");
  expect(template, "the seeded Default template").toBeDefined();
  const created = await api.post("/api/v1/rooms", { data: { name: uniqueText("e2e-watch"), templateId: template!.id } });
  expect(created.status(), await created.text()).toBe(201);
  const roomId = ((await created.json()) as { id: number }).id;
  cleanup.unshift(`/api/v1/rooms/${roomId}`);
  const added = await api.post(`/api/v1/rooms/${roomId}/members`, { data: { userIds: [mate.id] } });
  expect(added.status(), await added.text()).toBe(200);
  return { owner, mate, roomId, api };
}

/** Start a thread as the owner through the API; returns its number. */
async function startThread(room: Scene, title: string): Promise<number> {
  const created = await room.api.post(`/api/v1/rooms/${room.roomId}/threads`, { data: { title, body: "Seeded through the API." } });
  expect(created.status(), await created.text()).toBe(201);
  return ((await created.json()) as { number: number }).number;
}

test("a member watches a thread and stops", async ({ page }) => {
  const room = await scene(page);
  const watched = uniqueText("Watched");
  const other = uniqueText("Other");
  const number = await startThread(room, watched);
  await startThread(room, other);
  await login(page, room.mate.email, room.mate.password);

  // The teammate did not start the thread: it is not watched, and only the creator is counted.
  await page.goto(`/rooms/${room.roomId}/threads/${number}`);
  await expect(watchButton(page)).toHaveAttribute("aria-pressed", "false");
  await expect(page.getByText("Watchers: 1")).toBeVisible();
  await expect(threadLink(page, watched)).not.toHaveAccessibleDescription(WATCHING_MARK);

  // Watching: the toggle presses, the count follows, the row is marked in words.
  const [watch] = await Promise.all([
    page.waitForResponse((r) => r.request().method() === "PUT" && WATCH_API.test(r.url())),
    watchButton(page).click(),
  ]);
  expect(watch.status()).toBe(204);
  await expect(watchButton(page)).toHaveAttribute("aria-pressed", "true");
  await expect(page.getByText("Watchers: 2")).toBeVisible();
  await expect(threadLink(page, watched)).toHaveAccessibleDescription(WATCHING_MARK);
  await expect(threadLink(page, other)).not.toHaveAccessibleDescription(WATCHING_MARK);

  // The "Watching" filter keeps only the watched thread, and the address holds it across a reload.
  await page.goto(`/rooms/${room.roomId}`);
  const toggle = page.getByRole("button", { name: /^Filters/ });
  await toggle.click();
  await expect(toggle).toHaveAttribute("aria-expanded", "true");
  const filter = page.getByRole("button", { name: "Watching", exact: true });
  await expect(filter).toHaveAttribute("aria-pressed", "false");
  await filter.click();
  await expect(filter).toHaveAttribute("aria-pressed", "true");
  await expect(page).toHaveURL(/[?&]watching=me(&|$)/);
  await expect(threadLink(page, watched)).toBeVisible();
  await expect(threadLink(page, other)).toHaveCount(0);
  await page.reload();
  await expect(page).toHaveURL(/[?&]watching=me(&|$)/);
  await expect(threadLink(page, watched)).toBeVisible();
  await expect(threadLink(page, other)).toHaveCount(0);
  await expect(page.getByRole("button", { name: "Watching", exact: true })).toHaveAttribute("aria-pressed", "true");

  // Stopping, from the thread's own page (the remembered "Watching" filter is restored onto the bare URL, so the list is still filtered):
  // the toggle releases, the count drops and the row leaves the filtered list.
  await page.goto(`/rooms/${room.roomId}/threads/${number}`);
  await expect(watchButton(page)).toHaveAttribute("aria-pressed", "true");
  await expect(threadLink(page, watched)).toBeVisible();
  await watchButton(page).click();
  await expect(watchButton(page)).toHaveAttribute("aria-pressed", "false");
  await expect(page.getByText("Watchers: 1")).toBeVisible();
  await expect(threadLink(page, watched)).toHaveCount(0);
});

test("the creator watches a new thread", async ({ page }) => {
  const room = await scene(page);
  const title = uniqueText("Mine");
  const number = await startThread(room, title);
  await login(page, room.owner.email, room.owner.password);

  await page.goto(`/rooms/${room.roomId}/threads/${number}`);
  await expect(watchButton(page)).toHaveAttribute("aria-pressed", "true");
  await expect(page.getByText("Watchers: 1")).toBeVisible();
  await expect(threadLink(page, title)).toHaveAccessibleDescription(WATCHING_MARK);
});
