// Live updates across two browsers: one member acts, another sees it without reloading. A reply, a new thread, a status move and a removal
// reach the other member's open page through the shell's WebSocket; the header says "Reconnecting…" while the browser is offline and the page
// catches up when it is back; the socket opens under the stack's own Content-Security-Policy. Owns: its throwaway `e2e-live-*` rooms and users,
// removed through the API in an `afterEach` (rooms first, then the users); the CSP test owns one throwaway user and no room. Every room is created by a throwaway user and only copies the
// seeded Default template. Threads and messages are never deleted: a room's removal hides them. The per-user `live-ticket` bucket (60 a minute) is far from reached: a sign-in or a page load mints one ticket.
import { type APIRequestContext, type Browser, type BrowserContext, type Page } from "@playwright/test";
import { BASE_URL } from "../playwright.config";
import { apiAsUser, deleteViaApi, expect, login, rowOperation, test, throwawayUser, uniqueText } from "./helpers";

const cleanup: string[] = [];
const apis: APIRequestContext[] = [];
const contexts: BrowserContext[] = [];
test.afterEach(async () => {
  // Contexts first: a closed browser stops reconnecting, so no socket outlives the room it watched.
  for (const context of contexts.splice(0)) await context.close();
  for (const api of apis.splice(0)) await api.dispose();
  await deleteViaApi(cleanup.splice(0));
});

const MESSAGES_API = /\/api\/v1\/rooms\/\d+\/threads\/\d+\/messages$/;
const STATUS_API = /\/api\/v1\/rooms\/\d+\/threads\/\d+\/status$/;
const THREADS_API = /\/api\/v1\/rooms\/\d+\/threads$/;
const MEMBER_API = /\/api\/v1\/rooms\/\d+\/members\/\d+$/;

interface Person {
  id: number;
  name: string;
  email: string;
  password: string;
}

/** A server frame as far as the specs read it (`LiveServerFrame` in the OpenAPI spec). */
interface Frame {
  type: string;
  roomIds?: number[];
}

/** One signed-in browser: its own context (so its own session and its own socket), plus every frame and socket address it saw. */
interface Seat {
  page: Page;
  context: BrowserContext;
  frames: Frame[];
  sockets: string[];
}

/** The room the people of a scene share: created by `owner` through the API, `mate` added as a plain member, `api` acting as the owner. */
interface Scene {
  owner: Person;
  mate: Person;
  roomId: number;
  api: APIRequestContext;
}

/** The sidebar's Rooms section (the rail lists the caller's rooms). */
const rail = (page: Page) => page.getByRole("group", { name: "Rooms", exact: true });
/** The conversation's scroller: its messages are the list items inside it. */
const conversation = (page: Page) => page.getByRole("region", { name: "Messages", exact: true });
const messageNamed = (page: Page, text: string) => conversation(page).getByRole("listitem").filter({ hasText: text });
const threadList = (page: Page) => page.getByRole("navigation", { name: "Threads", exact: true });
const statusSelect = (page: Page) => page.getByRole("combobox", { name: "Thread status", exact: true });

/** The admin (signed in on `page`) mints the owner and the teammate; the owner then makes a room and adds the teammate, all through the API. */
async function scene(page: Page): Promise<Scene> {
  await login(page);
  const owner = await throwawayUser(page, "E2E Live Owner", cleanup);
  const mate = await throwawayUser(page, "E2E Live Mate", cleanup);
  const { api } = await apiAsUser(owner.email, owner.password);
  apis.push(api);
  const templates = await api.get("/api/v1/workflow-templates?name=Default");
  expect(templates.status(), await templates.text()).toBe(200);
  const template = ((await templates.json()) as { items: { id: number; name: string }[] }).items.find((candidate) => candidate.name === "Default");
  expect(template, "the seeded Default template").toBeDefined();
  const created = await api.post("/api/v1/rooms", { data: { name: uniqueText("e2e-live"), templateId: template!.id } });
  expect(created.status(), await created.text()).toBe(201);
  const roomId = ((await created.json()) as { id: number }).id;
  cleanup.unshift(`/api/v1/rooms/${roomId}`);
  const added = await api.post(`/api/v1/rooms/${roomId}/members`, { data: { userIds: [mate.id] } });
  expect(added.status(), await added.text()).toBe(200);
  return { owner, mate, roomId, api };
}

/** Seed one thread (number 1: every room here is new) as the owner and return its title. */
async function seedThread(room: Scene, body = "Seeded through the API."): Promise<string> {
  const title = uniqueText("Live thread");
  const created = await room.api.post(`/api/v1/rooms/${room.roomId}/threads`, { data: { title, body } });
  expect(created.status(), await created.text()).toBe(201);
  return title;
}

/** A fresh browser context signed in as `who`, recording the frames of every live socket its page opens. */
async function signedIn(browser: Browser, who: { email: string; password: string }, before?: (context: BrowserContext) => Promise<void>): Promise<Seat> {
  const context = await browser.newContext({ baseURL: BASE_URL });
  contexts.push(context);
  await before?.(context);
  const page = await context.newPage();
  const seat: Seat = { page, context, frames: [], sockets: [] };
  page.on("websocket", (socket) => {
    seat.sockets.push(socket.url());
    socket.on("framereceived", ({ payload }) => {
      try {
        seat.frames.push(JSON.parse(String(payload)) as Frame);
      } catch {
        // A frame that is not JSON is not one of ours; the client ignores it too.
      }
    });
  });
  await login(page, who.email, who.password);
  return seat;
}

/**
 * Open `path` and wait until the page's socket has been acknowledged for `roomId`: from then on a change is PUSHED, it can no longer be
 * picked up by the page's own load. Marks the document too, so a later check can prove nothing reloaded.
 */
async function openLive(seat: Seat, path: string, roomId: number): Promise<void> {
  seat.frames.length = 0;
  await seat.page.goto(path);
  await expect.poll(() => seat.frames.some((frame) => frame.type === "subscribed" && frame.roomIds?.includes(roomId)), { message: "the socket subscribed to the room" }).toBe(true);
  await seat.page.evaluate(() => {
    Object.assign(window, { e2eLiveMarker: true });
  });
}

/** The marker `openLive` set survives only while the document is the same one: a reload would have wiped it. */
async function expectNoReload(seat: Seat): Promise<void> {
  expect(await seat.page.evaluate(() => (window as unknown as { e2eLiveMarker?: boolean }).e2eLiveMarker), "the page was not reloaded").toBe(true);
}

/** Post a reply with Ctrl+Enter from the composer. */
async function reply(page: Page, text: string): Promise<void> {
  const box = page.getByRole("textbox", { name: "Reply", exact: true });
  await box.fill(text);
  await Promise.all([
    page.waitForResponse((r) => r.request().method() === "POST" && MESSAGES_API.test(r.url()) && r.status() === 201),
    box.press("Control+Enter"),
  ]);
  await expect(box).toHaveValue("");
}

/**
 * Opens the thread's side panel (a third column in a wide main area, folded behind "Details" below that). The pane is awaited FIRST: the
 * toggle renders with it, and one looked for earlier reads as hidden.
 */
async function openDetails(page: Page): Promise<void> {
  await expect(page.getByRole("region", { name: /^#\d+ / })).toBeVisible();
  const toggle = page.getByRole("button", { name: "Details", exact: true });
  if (await toggle.isVisible()) {
    if ((await toggle.getAttribute("aria-expanded")) === "false") await toggle.click();
    await expect(toggle).toHaveAttribute("aria-expanded", "true");
  }
  await expect(page.getByRole("heading", { level: 4, name: "Status", exact: true })).toBeVisible();
}

test("a message posted in one browser appears in the other without a reload", async ({ page, browser }) => {
  const room = await scene(page);
  const title = await seedThread(room);
  const thread = `/rooms/${room.roomId}/threads/1`;
  const author = await signedIn(browser, room.owner);
  const reader = await signedIn(browser, room.mate);
  await openLive(author, thread, room.roomId);
  await openLive(reader, thread, room.roomId);
  await expect(reader.page.getByRole("heading", { level: 3, name: `#1 ${title}` })).toBeVisible();
  await expect(conversation(reader.page).getByRole("listitem")).toHaveCount(1);

  // The author replies; the reader's open thread gains the message, and its header count follows.
  await reply(author.page, "The replica is catching up.");
  await expect(messageNamed(reader.page, "The replica is catching up.")).toBeVisible();
  await expect(messageNamed(reader.page, "The replica is catching up.")).toContainText(room.owner.name);
  await expect(reader.page.getByRole("region", { name: `#1 ${title}` })).toContainText("2 messages");

  // And the other way round: the reader's reply reaches the author's page.
  await reply(reader.page, "Confirmed, lag is falling.");
  await expect(messageNamed(author.page, "Confirmed, lag is falling.")).toBeVisible();
  await expect(messageNamed(author.page, "Confirmed, lag is falling.")).toContainText(room.mate.name);
  await expect(conversation(author.page).getByRole("listitem").last()).toContainText("Confirmed, lag is falling.");
  await expectNoReload(reader);
  await expectNoReload(author);
});

test("a thread started by one member appears in another member's thread list", async ({ page, browser }) => {
  const room = await scene(page);
  const author = await signedIn(browser, room.owner);
  const reader = await signedIn(browser, room.mate);
  await openLive(author, `/rooms/${room.roomId}`, room.roomId);
  await openLive(reader, `/rooms/${room.roomId}`, room.roomId);
  await expect(threadList(reader.page).getByRole("link")).toHaveCount(0);

  // The author starts a thread from the room view.
  const title = uniqueText("Disk full");
  await author.page.getByRole("link", { name: "New thread", exact: true }).click();
  await expect(author.page).toHaveURL(new RegExp(`/rooms/${room.roomId}/threads/new$`));
  await author.page.getByRole("textbox", { name: "Title", exact: true }).fill(title);
  await author.page.getByRole("textbox", { name: "First message", exact: true }).fill("The **replica** is out of space.");
  await Promise.all([
    author.page.waitForResponse((r) => r.request().method() === "POST" && THREADS_API.test(r.url()) && r.status() === 201),
    author.page.getByRole("button", { name: "Start thread", exact: true }).click(),
  ]);
  await expect(author.page).toHaveURL(new RegExp(`/rooms/${room.roomId}/threads/1$`));

  // The reader's list gains it as a link, and following it shows the opening message.
  const link = threadList(reader.page).getByRole("link", { name: new RegExp(title) });
  await expect(link).toBeVisible();
  await expectNoReload(reader);
  await link.click();
  await expect(reader.page.getByRole("heading", { level: 3, name: `#1 ${title}` })).toBeVisible();
  await expect(messageNamed(reader.page, "is out of space")).toBeVisible();
});

test("a status change made by one member shows in another member's open thread", async ({ page, browser }) => {
  const room = await scene(page);
  const title = await seedThread(room);
  const thread = `/rooms/${room.roomId}/threads/1`;
  const mover = await signedIn(browser, room.owner);
  const watcher = await signedIn(browser, room.mate);
  await openLive(mover, thread, room.roomId);
  await openLive(watcher, thread, room.roomId);
  await openDetails(mover.page);
  await openDetails(watcher.page);
  const row = threadList(watcher.page).getByRole("link", { name: new RegExp(title) });
  await expect(row).toContainText("Open");
  await expect(statusSelect(watcher.page)).toHaveValue("Open");

  // The mover picks another open status in the side panel.
  await statusSelect(mover.page).click();
  await Promise.all([
    mover.page.waitForResponse((r) => r.request().method() === "PUT" && STATUS_API.test(r.url()) && r.status() === 204),
    mover.page.getByRole("option", { name: "In progress", exact: true }).click(),
  ]);

  // The watcher's list row and status select follow.
  await expect(row).toContainText("In progress");
  await expect(statusSelect(watcher.page)).toHaveValue("In progress");
  await expectNoReload(watcher);
});

test("a member removed from a room loses it from the rail and the open room", async ({ page, browser }) => {
  const room = await scene(page);
  await seedThread(room);
  const manager = await signedIn(browser, room.owner);
  const removed = await signedIn(browser, room.mate);
  await openLive(manager, `/rooms/${room.roomId}/members`, room.roomId);
  await openLive(removed, `/rooms/${room.roomId}/threads/1`, room.roomId);
  const roomLink = rail(removed.page).getByRole("link", { name: /^e2e-live-/ });
  await expect(roomLink).toHaveCount(1);
  await expect(removed.page.getByRole("heading", { level: 3, name: /^#1 / })).toBeVisible();

  // The owner removes the teammate from the Members page.
  await rowOperation(manager.page, room.mate.name, "Remove");
  await Promise.all([
    manager.page.waitForResponse((r) => r.request().method() === "DELETE" && MEMBER_API.test(r.url()) && r.status() === 204),
    manager.page.getByRole("dialog", { name: "Remove from the room?" }).getByRole("button", { name: "Remove", exact: true }).click(),
  ]);

  // The removed member's rail drops the room and the open room turns into the same "not a member" answer as for a stranger.
  await expect(roomLink).toHaveCount(0);
  await expect(removed.page.getByText("This room does not exist, or you are not a member of it.")).toBeVisible();
  await expectNoReload(removed);
});

test("the header says it is reconnecting while the browser is offline", async ({ page, browser }) => {
  const room = await scene(page);
  const title = await seedThread(room);
  const offline = await signedIn(browser, room.mate);
  await openLive(offline, `/rooms/${room.roomId}/threads/1`, room.roomId);
  await expect(offline.page.getByRole("heading", { level: 3, name: `#1 ${title}` })).toBeVisible();
  const badge = offline.page.getByText("Reconnecting…", { exact: true });
  await expect(badge).toHaveCount(0);

  // Offline: after the grace period the header says so, and a message posted meanwhile (by the owner, through the API) stays unseen.
  await offline.context.setOffline(true);
  await expect(badge).toBeVisible({ timeout: 15_000 });
  const posted = await room.api.post(`/api/v1/rooms/${room.roomId}/threads/1/messages`, { data: { body: "Posted while you were away." } });
  expect(posted.status(), await posted.text()).toBe(201);
  await expect(messageNamed(offline.page, "Posted while you were away.")).toHaveCount(0);

  // Back online: the socket reconnects at once, the badge goes and the page catches up on what it missed.
  await offline.context.setOffline(false);
  await expect(badge).toHaveCount(0, { timeout: 15_000 });
  await expect(messageNamed(offline.page, "Posted while you were away.")).toBeVisible();
});

test("the live socket opens under the production content security policy", async ({ page, browser }) => {
  // A throwaway user, not the seed administrator: the ticket mint is a per-USER bucket (60 a minute) and the administrator is every
  // other spec's account, so a full parallel run would spend it before this test signs in.
  await login(page);
  const user = await throwawayUser(page, "E2E Live Csp", cleanup);
  // Every violation the document reports is recorded from before the first script runs.
  const seat = await signedIn(browser, user, async (context) => {
    await context.addInitScript(() => {
      const seen: string[] = [];
      Object.assign(window, { e2eCspViolations: seen });
      document.addEventListener("securitypolicyviolation", (event) => seen.push(event.effectiveDirective));
    });
  });
  // The sign-in already loaded the shell and its socket; the document's own header is read with a plain request for the same address.
  const response = await seat.page.request.get("/");
  expect(response.ok()).toBe(true);

  // The policy is the strict one: same-origin connections only, with no ws:/wss: source added for the socket.
  const policy = response.headers()["content-security-policy"] ?? "";
  expect(policy).toContain("connect-src 'self';");
  expect(policy).not.toMatch(/\bwss?:/);

  // The socket opened on the page's own origin and the server answered `ready`: the browser did not block it.
  await expect.poll(() => seat.frames.some((frame) => frame.type === "ready"), { message: "the live socket became ready" }).toBe(true);
  expect(seat.sockets.some((address) => new URL(address).host === new URL(BASE_URL).host && new URL(address).pathname === "/api/v1/live")).toBe(true);
  const violations = await seat.page.evaluate(() => (window as unknown as { e2eCspViolations?: string[] }).e2eCspViolations);
  expect(violations, "no security policy violation was reported").toEqual([]);
});
