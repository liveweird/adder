// Live updates across two browsers: a reply, message edit or deletion, new thread, status move or membership removal reaches the other
// page through the shell's WebSocket. The header says "Reconnecting…" while the browser is offline and the page
// catches up when it is back; the socket opens under the stack's own Content-Security-Policy. Owns: its throwaway `e2e-live-*` rooms and users,
// removed through the API in an `afterEach` (rooms first, then the users); the CSP test owns one throwaway user and no room. Every room is created by a throwaway user and only copies the
// seeded Default template. Deletion scenarios act on their own messages and threads. The per-user `live-ticket` bucket (60 a minute) is far from reached: a sign-in or a page load mints one ticket, and the file's own
// administrator (`specAdmins`) spends its own bucket, never the seed administrator's.
import { type APIRequestContext, type Browser, type BrowserContext, type Page } from "@playwright/test";
import { BASE_URL } from "../playwright.config";
import { apiAsUser, conversation, deleteViaApi, expect, login, rail, rowOperation, specAdmins, test, threadList, threadListRow, throwawayUser, uniqueText } from "./helpers";

/** This file's own administrator (see `specAdmins`): the page loads of its sign-ins spend this account's live-ticket bucket, not the seed administrator's. */
const admin = specAdmins("e2e-live-admin");

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

interface Person {
  id: number;
  name: string;
  email: string;
  password: string;
}

/** A server frame as far as the specs read it (`LiveServerFrame` in the OpenAPI spec). */
interface Frame {
  type: string;
  roomId?: number;
  roomIds?: number[];
  kind?: string;
  messageId?: number;
  mutationId?: string;
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
  templateId: number;
  api: APIRequestContext;
}

const messageNamed = (page: Page, text: string) => conversation(page).getByRole("listitem").filter({ hasText: text });
const currentStatus = (page: Page) => page.getByRole("status", { name: "Thread status", exact: true });

/** The admin (signed in on `page`) mints the owner and the teammate; the owner then makes a room and adds the teammate, all through the API. */
async function scene(page: Page): Promise<Scene> {
  await admin.login(page);
  const owner = await throwawayUser(page, "E2E Live Owner", cleanup);
  const mate = await throwawayUser(page, "E2E Live Mate", cleanup);
  const { api } = await apiAsUser(owner.email, owner.password);
  apis.push(api);
  const templates = await api.get("/api/v1/workflow-templates?name=Default");
  expect(templates.status(), await templates.text()).toBe(200);
  const template = ((await templates.json()) as { items: { id: number; name: string }[] }).items.find((candidate) => candidate.name === "Default");
  expect(template, "the seeded Default template").toBeDefined();
  const templateId = template!.id;
  return { owner, mate, roomId: await createSharedRoom(api, templateId, mate.id), templateId, api };
}

async function createSharedRoom(api: APIRequestContext, templateId: number, memberId: number): Promise<number> {
  const created = await api.post("/api/v1/rooms", { data: { name: uniqueText("e2e-live"), templateId, accessType: "RESTRICTED_VISIBLE" } });
  expect(created.status(), await created.text()).toBe(201);
  const roomId = ((await created.json()) as { id: number }).id;
  cleanup.unshift(`/api/v1/rooms/${roomId}`);
  const added = await api.post(`/api/v1/rooms/${roomId}/members`, { data: { userIds: [memberId] } });
  expect(added.status(), await added.text()).toBe(200);
  return roomId;
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

test("a message posted in one browser appears in the other without a reload", async ({ page, browser }) => {
  const room = await scene(page);
  const title = await seedThread(room);
  const thread = `/rooms/${room.roomId}/threads/1`;
  const author = await signedIn(browser, room.owner);
  const reader = await signedIn(browser, room.mate);
  await openLive(author, thread, room.roomId);
  await openLive(reader, thread, room.roomId);
  await expect(reader.page.getByRole("heading", { level: 3, name: `#1 ${title}` })).toBeVisible();
  await expect(conversation(reader.page).locator("[data-message-id]")).toHaveCount(1);

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

test("a message edit refreshes the author once and updates another reader live", async ({ page, browser }) => {
  const room = await scene(page);
  const original = "Draft incident summary";
  const revised = "Revised incident summary";
  await seedThread(room, original);
  const thread = `/rooms/${room.roomId}/threads/1`;
  const timelinePath = `/api/v1${thread}/messages`;
  const timeline = await room.api.get(timelinePath);
  expect(timeline.status()).toBe(200);
  const { items: [{ id }] } = await timeline.json() as { items: { id: number }[] };
  const messagePath = `/api/v1/rooms/${room.roomId}/messages/${id}`;
  const pin = await room.api.put(`${messagePath}/pin`, { data: { pinned: true } });
  expect(pin.status()).toBe(204);

  const author = await signedIn(browser, room.owner);
  const reader = await signedIn(browser, room.mate);
  await author.page.clock.install();
  await openLive(author, thread, room.roomId);
  await openLive(reader, thread, room.roomId);
  const readerPins = reader.page.getByRole("region", { name: "Pins in this thread", exact: true });
  await expect(readerPins.getByRole("button", { name: `Pinned: ${original}`, exact: true })).toBeVisible();
  const operations = (seat: Seat, body: string) => messageNamed(seat.page, body).getByRole("button", { name: /^Operations for message by / });
  await operations(author, original).click();
  await author.page.getByRole("menuitem", { name: "Edit", exact: true }).click();
  const editor = author.page.getByRole("textbox", { name: "Edit message", exact: true });
  await editor.fill(`${revised} #reviewed`);
  // Let the initial subscription's spread reads start before measuring this write.
  await author.page.clock.runFor(2_100);
  let authorTimelineReads = 0;
  author.page.on("request", (request) => {
    if (request.method() === "GET" && new URL(request.url()).pathname === timelinePath) authorTimelineReads++;
  });

  let release!: () => void;
  const held = new Promise<void>((resolve) => { release = resolve; });
  await author.page.route((url) => url.pathname === messagePath, async (route) => {
    if (route.request().method() !== "PUT") return route.continue();
    const response = await route.fetch();
    expect(response.status()).toBe(204);
    await held;
    await route.fulfill({ response });
  });

  const editFrame = (seat: Seat) => seat.frames.find((frame) => frame.kind === "message.edited" && frame.messageId === id);
  try {
    const [request] = await Promise.all([
      author.page.waitForRequest((request) => request.method() === "PUT" && new URL(request.url()).pathname === messagePath),
      author.page.getByRole("button", { name: "Save", exact: true }).click(),
    ]);
    const mutationId = request.headers()["x-mutation-id"];
    expect(mutationId).toMatch(/^[0-9a-f-]{36}$/);
    await expect.poll(() => editFrame(author)?.mutationId).toBe(mutationId);
    await expect(messageNamed(reader.page, revised)).toBeVisible();
    await expect(readerPins.getByRole("button", { name: new RegExp(`^Pinned: ${revised}`) })).toBeVisible();
    expect(editFrame(reader)).toBeDefined();
    expect(editFrame(reader)?.mutationId).toBeUndefined();
    await author.page.clock.runFor(1_100);
    expect(authorTimelineReads).toBe(0);
    await expect(editor).toBeVisible();
  } finally {
    release();
  }

  await expect(editor).toHaveCount(0);
  await expect(messageNamed(author.page, revised)).toBeVisible();
  await author.page.clock.runFor(3_200);
  expect(authorTimelineReads).toBe(1);

  await operations(author, revised).click();
  await author.page.getByRole("menuitem", { name: "View edit history", exact: true }).click();
  const history = author.page.getByRole("dialog", { name: "Edit history", exact: true });
  await expect(history.locator("pre")).toHaveText(original);
  await operations(reader, revised).click();
  await expect(reader.page.getByRole("menuitem", { name: "Quote", exact: true })).toBeVisible();
  await expect(reader.page.getByRole("menuitem", { name: "View edit history", exact: true })).toHaveCount(0);
  await expectNoReload(author);
  await expectNoReload(reader);
});

test("a committed message deletion clears both tabs before its response and refreshes the writer once", async ({ page, browser }) => {
  const room = await scene(page);
  const earlier = "Earlier incident note";
  const deleted = "Wording to remove";
  await seedThread(room);
  const thread = `/rooms/${room.roomId}/threads/1`;
  const timelinePath = `/api/v1${thread}/messages`;
  const posted = await room.api.post(timelinePath, { data: { body: earlier } });
  expect(posted.status()).toBe(201);
  const { id } = await posted.json() as { id: number };
  const messagePath = `/api/v1/rooms/${room.roomId}/messages/${id}`;
  const edited = await room.api.put(messagePath, { data: { body: deleted } });
  expect(edited.status()).toBe(204);
  const pinned = await room.api.put(`${messagePath}/pin`, { data: { pinned: true } });
  expect(pinned.status()).toBe(204);

  const author = await signedIn(browser, room.owner);
  const otherTab = await signedIn(browser, room.owner);
  await author.page.clock.install();
  await openLive(author, thread, room.roomId);
  await openLive(otherTab, thread, room.roomId);
  const pins = otherTab.page.getByRole("region", { name: "Pins in this thread", exact: true });
  await expect(pins.getByRole("button", { name: `Pinned: ${deleted}`, exact: true })).toBeVisible();
  const operations = (seat: Seat) => messageNamed(seat.page, deleted).getByRole("button", { name: /^Operations for message by / });
  await operations(otherTab).click();
  await otherTab.page.getByRole("menuitem", { name: "View edit history", exact: true }).click();
  const history = otherTab.page.getByRole("dialog", { name: "Edit history", exact: true });
  await expect(history.locator("pre")).toHaveText(earlier);
  await operations(author).click();
  await author.page.getByRole("menuitem", { name: "Delete", exact: true }).click();
  const confirmation = author.page.getByRole("dialog", { name: "Delete this message?", exact: true });
  await author.page.clock.runFor(2_100);
  const reads = { timeline: 0, pins: 0 };
  author.page.on("request", (request) => {
    if (request.method() !== "GET") return;
    const path = new URL(request.url()).pathname;
    if (path === timelinePath) reads.timeline++;
    if (path === `/api/v1/rooms/${room.roomId}/pins`) reads.pins++;
  });

  let release!: () => void;
  const held = new Promise<void>((resolve) => { release = resolve; });
  await author.page.route((url) => url.pathname === messagePath, async (route) => {
    if (route.request().method() !== "DELETE") return route.continue();
    const response = await route.fetch();
    expect(response.status()).toBe(204);
    await held;
    await route.fulfill({ response });
  });

  const tombstone = (seat: Seat) => conversation(seat.page).locator(`[data-message-id="${id}"]`);
  try {
    await confirmation.getByRole("button", { name: "Delete", exact: true }).click();
    await expect.poll(() => author.frames.some((frame) => frame.kind === "message.deleted" && frame.messageId === id)).toBe(true);
    await author.page.clock.runFor(1_100);
    await expect(tombstone(author)).toContainText("This message was deleted.");
    await expect(tombstone(otherTab)).toContainText("This message was deleted.");
    await expect(pins).toHaveCount(0);
    await expect(history).toHaveCount(0);
  } finally {
    release();
  }

  await expect(confirmation).toHaveCount(0);
  await author.page.clock.runFor(3_200);
  expect(reads).toEqual({ timeline: 1, pins: 1 });
  await expect(tombstone(author)).toBeFocused();
  await expectNoReload(author);
  await expectNoReload(otherTab);
});

test("a lost deletion response still removes the message and its quote live", async ({ page, browser }) => {
  const room = await scene(page);
  const original = "Source note for the quote";
  await seedThread(room);
  const destinationRoom = await createSharedRoom(room.api, room.templateId, room.mate.id);
  await seedThread({ ...room, roomId: destinationRoom }, "Destination for the quote");
  const thread = `/rooms/${room.roomId}/threads/1`;
  const destination = `/rooms/${destinationRoom}/threads/1`;
  const posted = await room.api.post(`/api/v1${thread}/messages`, { data: { body: original } });
  expect(posted.status()).toBe(201);
  const { id } = await posted.json() as { id: number };
  const quoted = await room.api.post(`/api/v1${destination}/messages`, { data: { body: "Reply stands on its own", quotedMessageId: id } });
  expect(quoted.status()).toBe(201);

  const author = await signedIn(browser, room.owner);
  const reader = await signedIn(browser, room.mate);
  await openLive(author, thread, room.roomId);
  await openLive(reader, destination, destinationRoom);
  await expect.poll(() => reader.frames.some((frame) => frame.type === "subscribed" && frame.roomIds?.includes(room.roomId))).toBe(true);
  const replyInOtherThread = messageNamed(reader.page, "Reply stands on its own");
  await expect(replyInOtherThread.getByRole("link", { name: new RegExp(original) })).toBeVisible();

  const messagePath = `/api/v1/rooms/${room.roomId}/messages/${id}`;
  await author.page.route((url) => url.pathname === messagePath, async (route) => {
    if (route.request().method() !== "DELETE") return route.continue();
    const response = await route.fetch();
    expect(response.status()).toBe(204);
    await route.abort();
  });
  await messageNamed(author.page, original).getByRole("button", { name: /^Operations for message by / }).click();
  await author.page.getByRole("menuitem", { name: "Delete", exact: true }).click();
  await author.page.getByRole("dialog", { name: "Delete this message?", exact: true }).getByRole("button", { name: "Delete", exact: true }).click();

  const tombstone = conversation(author.page).locator(`[data-message-id="${id}"]`);
  await expect(tombstone).toHaveText(/This message was deleted\./);
  await expect(replyInOtherThread.getByRole("link", { name: /The quoted message was deleted/ })).toBeVisible();
  await expect(replyInOtherThread).not.toContainText(original);
  await expect(tombstone.getByRole("alert")).toHaveCount(0);
  await expectNoReload(author);
  await expectNoReload(reader);
});

test("thread deletion clears a merged conversation live and refreshes the writer once", async ({ page, browser }) => {
  const room = await scene(page);
  const sourceBody = "History from the merged source";
  const targetTitle = await seedThread(room, "Canonical conversation");
  await seedThread(room, sourceBody);
  const roomPath = `/rooms/${room.roomId}`;
  const targetPath = `${roomPath}/threads/1`;
  const sourcePath = `${roomPath}/threads/2`;
  const merged = await room.api.put(`/api/v1${sourcePath}/merge`, { data: { targetNumber: 1 } });
  expect(merged.status(), await merged.text()).toBe(204);
  const pinned = await room.api.put(`/api/v1${targetPath}/pin`, { data: { pinned: true } });
  expect(pinned.status()).toBe(204);

  const author = await signedIn(browser, room.owner);
  const reader = await signedIn(browser, room.mate);
  await author.page.clock.install();
  await openLive(author, targetPath, room.roomId);
  await openLive(reader, sourcePath, room.roomId);
  await expect(messageNamed(reader.page, sourceBody)).toBeVisible();
  await expect(threadListRow(author.page, targetTitle)).toBeVisible();
  await expect(author.page.getByRole("button", { name: "Pinned (1)", exact: true })).toBeVisible();
  await author.page.getByRole("button", { name: "Thread actions" }).click();
  await author.page.getByRole("menuitem", { name: "Delete thread", exact: true }).click();
  const confirmation = author.page.getByRole("dialog", { name: "Delete #1?", exact: true });
  await author.page.clock.runFor(2_100);

  const reads = { list: 0, pins: 0 };
  author.page.on("request", (request) => {
    if (request.method() !== "GET") return;
    const path = new URL(request.url()).pathname;
    if (path === `/api/v1${roomPath}/threads`) reads.list++;
    if (path === `/api/v1${roomPath}/pins`) reads.pins++;
  });

  let release!: () => void;
  const held = new Promise<void>((resolve) => { release = resolve; });
  await author.page.route((url) => url.pathname === `/api/v1${targetPath}`, async (route) => {
    if (route.request().method() !== "DELETE") return route.continue();
    const response = await route.fetch();
    expect(response.status()).toBe(204);
    await held;
    await route.fulfill({ response });
  });

  try {
    await confirmation.getByRole("button", { name: "Delete thread", exact: true }).click();
    await expect.poll(() => author.frames.some((frame) => frame.kind === "thread.deleted")).toBe(true);
    await author.page.clock.runFor(1_100);
    await expect(threadListRow(author.page, targetTitle)).toHaveCount(0);
    await expect(author.page.getByRole("button", { name: "Pinned (0)", exact: true })).toBeVisible();
    await expect(reader.page.getByText("This thread does not exist.")).toBeVisible();
    await expect(reader.page.getByText(sourceBody)).toHaveCount(0);
  } finally {
    release();
  }

  await expect(author.page).toHaveURL(new RegExp(`${roomPath}$`));
  await author.page.clock.runFor(3_200);
  expect(reads).toEqual({ list: 1, pins: 1 });
  await expectNoReload(author);
  await expectNoReload(reader);
});

test("a lost thread deletion response still clears a cross-room quote", async ({ page, browser }) => {
  const room = await scene(page);
  const sourceTitle = await seedThread(room);
  const destinationRoom = await createSharedRoom(room.api, room.templateId, room.mate.id);
  await seedThread({ ...room, roomId: destinationRoom });
  const roomPath = `/rooms/${room.roomId}`;
  const sourcePath = `${roomPath}/threads/1`;
  const destinationPath = `/rooms/${destinationRoom}/threads/1`;
  const original = "Quote from the thread being deleted";
  const posted = await room.api.post(`/api/v1${sourcePath}/messages`, { data: { body: original } });
  expect(posted.status()).toBe(201);
  const { id } = await posted.json() as { id: number };
  const quoted = await room.api.post(`/api/v1${destinationPath}/messages`, { data: { body: "Reply in the other room", quotedMessageId: id } });
  expect(quoted.status()).toBe(201);

  const author = await signedIn(browser, room.owner);
  const reader = await signedIn(browser, room.mate);
  await openLive(author, `${sourcePath}?scope=all`, room.roomId);
  await openLive(reader, destinationPath, destinationRoom);
  await expect.poll(() => reader.frames.some((frame) => frame.type === "subscribed" && frame.roomIds?.includes(room.roomId))).toBe(true);
  const replyInOtherRoom = messageNamed(reader.page, "Reply in the other room");
  await expect(replyInOtherRoom.getByRole("link", { name: new RegExp(original) })).toBeVisible();

  let release!: () => void;
  const held = new Promise<void>((resolve) => { release = resolve; });
  await author.page.route((url) => url.pathname === `/api/v1${sourcePath}`, async (route) => {
    if (route.request().method() !== "DELETE") return route.continue();
    const response = await route.fetch();
    expect(response.status()).toBe(204);
    await held;
    await route.abort();
  });
  await author.page.getByRole("button", { name: "Thread actions" }).click();
  await author.page.getByRole("menuitem", { name: "Delete thread", exact: true }).click();
  const confirmation = author.page.getByRole("dialog", { name: "Delete #1?", exact: true });
  try {
    await confirmation.getByRole("button", { name: "Delete thread", exact: true }).click();
    await expect.poll(() => author.frames.some((frame) => frame.kind === "thread.deleted")).toBe(true);
    await expect(threadListRow(author.page, sourceTitle)).toHaveCount(0);
    await expect(replyInOtherRoom).toBeVisible();
    await expect(replyInOtherRoom).not.toContainText(original);
  } finally {
    release();
  }

  await expect(author.page).toHaveURL(new RegExp(`${roomPath}\\?scope=all$`));
  await expect(confirmation).toHaveCount(0);
  await expectNoReload(author);
  await expectNoReload(reader);
});

async function openMergeDialog(page: Page, title: string) {
  await page.getByRole("button", { name: "Thread actions" }).click();
  await page.getByRole("menuitem", { name: "Merge duplicate", exact: true }).click();
  const dialog = page.getByRole("dialog", { name: "Merge duplicate thread", exact: true });
  await dialog.getByRole("textbox", { name: "Target thread number" }).fill("1");
  await expect(dialog.getByText(`#1 ${title}`, { exact: true })).toBeVisible();
  return dialog;
}

async function holdCommittedResponse(
  author: Seat,
  { path, method, status, loseResponse = false }: { path: string; method: string; status: number; loseResponse?: boolean },
) {
  let release!: () => void;
  const held = new Promise<void>((resolve) => { release = resolve; });
  await author.page.route((url) => url.pathname === path, async (route) => {
    if (route.request().method() !== method) return route.continue();
    const response = await route.fetch();
    expect(response.status()).toBe(status);
    await held;
    if (loseResponse) await route.abort();
    else await route.fulfill({ response });
  });
  return release;
}

const mergeBanner = (page: Page) => page.getByText("This conversation was merged into #1. Its history remains available here.", { exact: true });

test("a committed merge refreshes the writer once and updates the reader live", async ({ page, browser }) => {
  const room = await scene(page);
  const targetTitle = await seedThread(room, "Canonical conversation");
  const sourceTitle = await seedThread(room, "Duplicate history stays readable");
  const roomPath = `/rooms/${room.roomId}`;
  const sourcePath = `${roomPath}/threads/2`;
  const author = await signedIn(browser, room.owner);
  const reader = await signedIn(browser, room.mate);
  await author.page.clock.install();
  await openLive(author, `${sourcePath}?scope=all`, room.roomId);
  await openLive(reader, sourcePath, room.roomId);
  await expect(threadListRow(reader.page, sourceTitle)).toBeVisible();
  const dialog = await openMergeDialog(author.page, targetTitle);
  await author.page.clock.runFor(2_100);

  const reads = { list: 0, workflow: 0 };
  author.page.on("request", (request) => {
    if (request.method() !== "GET") return;
    const path = new URL(request.url()).pathname;
    if (path === `/api/v1${roomPath}/threads`) reads.list++;
    if (path === `/api/v1${roomPath}/workflow`) reads.workflow++;
  });
  const release = await holdCommittedResponse(author, { path: `/api/v1${sourcePath}/merge`, method: "PUT", status: 204 });
  let heldReads: typeof reads;
  try {
    await dialog.getByRole("button", { name: "Merge into #1", exact: true }).click();
    await expect.poll(() => author.frames.some((frame) => frame.kind === "thread.merged")).toBe(true);
    await author.page.clock.runFor(1_100);
    await expect(mergeBanner(reader.page)).toBeVisible();
    await expect(threadListRow(reader.page, sourceTitle)).toHaveCount(0);
    await expect(messageNamed(reader.page, "Duplicate history stays readable")).toBeVisible();
    heldReads = { ...reads };
  } finally {
    release();
  }

  await expect(author.page).toHaveURL(new RegExp(`${roomPath}/threads/1\\?scope=all$`));
  await expect(author.page.getByRole("heading", { name: `#1 ${targetTitle}`, exact: true })).toBeVisible();
  await author.page.clock.runFor(3_200);
  expect(reads).toEqual({ list: 1, workflow: 1 });
  expect(heldReads).toEqual({ list: 0, workflow: 0 });
  expect(author.frames.find((frame) => frame.kind === "thread.merged")?.mutationId).toMatch(/^[0-9a-f-]{36}$/);
  const readerFrame = reader.frames.find((frame) => frame.kind === "thread.merged");
  expect(readerFrame).toBeDefined();
  expect(readerFrame?.mutationId).toBeUndefined();
  await expectNoReload(author);
  await expectNoReload(reader);
});

test("a lost merge response releases the writer's live refresh", async ({ page, browser }) => {
  const room = await scene(page);
  const targetTitle = await seedThread(room);
  await seedThread(room, "Readable after a lost merge response");
  const sourcePath = `/rooms/${room.roomId}/threads/2`;
  const author = await signedIn(browser, room.owner);
  const reader = await signedIn(browser, room.mate);
  await author.page.clock.install();
  await openLive(author, sourcePath, room.roomId);
  await openLive(reader, sourcePath, room.roomId);
  const dialog = await openMergeDialog(author.page, targetTitle);
  await author.page.clock.runFor(2_100);
  const release = await holdCommittedResponse(author, { path: `/api/v1${sourcePath}/merge`, method: "PUT", status: 204, loseResponse: true });
  try {
    await dialog.getByRole("button", { name: "Merge into #1", exact: true }).click();
    await expect.poll(() => author.frames.some((frame) => frame.kind === "thread.merged")).toBe(true);
    await expect(mergeBanner(reader.page)).toBeVisible();
    await expect(mergeBanner(author.page)).toHaveCount(0);
  } finally {
    release();
  }

  await author.page.clock.runFor(1_100);
  await expect(mergeBanner(author.page)).toBeVisible();
  await expect(messageNamed(author.page, "Readable after a lost merge response")).toBeVisible();
  await expect(author.page).toHaveURL(new RegExp(`${sourcePath}$`));
  await expectNoReload(author);
  await expectNoReload(reader);
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
  const row = threadListRow(watcher.page, title);
  await expect(row).toContainText("Open");
  await expect(currentStatus(watcher.page)).toContainText("Open");

  // The mover picks another open status from the thread header's viable actions.
  await mover.page.getByRole("button", { name: "Change status", exact: true }).click();
  await Promise.all([
    mover.page.waitForResponse((r) => r.request().method() === "PUT" && STATUS_API.test(r.url()) && r.status() === 204),
    mover.page.getByRole("menuitem", { name: "Move to In progress", exact: true }).click(),
  ]);

  // The watcher's list row, current badge and available actions follow.
  await expect(row).toContainText("In progress");
  await expect(currentStatus(watcher.page)).toContainText("In progress");
  await watcher.page.getByRole("button", { name: "Change status", exact: true }).click();
  await expect(watcher.page.getByRole("menu", { name: "Change status", exact: true }).getByRole("menuitem")).toHaveText(["Move to Open", "Move to Waiting"]);
  await expectNoReload(watcher);
});

const memberRow = (page: Page, person: Person) => page.getByRole("row").filter({ hasText: person.email });

async function pickMemberToAdd(page: Page, person: Person) {
  const picker = page.getByRole("combobox", { name: "Add a person" });
  await picker.click();
  await picker.fill(person.name);
  await page.getByRole("option", { name: `${person.name} (${person.email})`, exact: true }).click();
}

function countRosterReads(page: Page, path: string) {
  let reads = 0;
  page.on("request", (request) => {
    if (request.method() === "GET" && new URL(request.url()).pathname === path) reads++;
  });
  return () => reads;
}

test("adding a room member updates another reader and refreshes the writer once", async ({ page, browser }) => {
  const room = await scene(page);
  const added = await throwawayUser(page, "E2E Live New Member", cleanup);
  const manager = await signedIn(browser, room.owner);
  const reader = await signedIn(browser, room.mate);
  const memberPath = `/rooms/${room.roomId}/members`;
  const apiPath = `/api/v1${memberPath}`;
  await manager.page.clock.install();
  await openLive(manager, memberPath, room.roomId);
  await openLive(reader, memberPath, room.roomId);
  await expect(memberRow(reader.page, room.owner)).toBeVisible();
  await pickMemberToAdd(manager.page, added);
  await manager.page.clock.runFor(2_100);

  const reads = countRosterReads(manager.page, apiPath);
  const release = await holdCommittedResponse(manager, { path: apiPath, method: "POST", status: 200 });
  let heldReads: number;
  try {
    await manager.page.getByRole("button", { name: "Add person", exact: true }).click();
    await expect.poll(() => manager.frames.some((frame) => frame.kind === "room.member_added" && frame.roomId === room.roomId)).toBe(true);
    await manager.page.clock.runFor(1_100);
    await expect(memberRow(reader.page, added)).toBeVisible();
    heldReads = reads();
  } finally {
    release();
  }

  await expect(memberRow(manager.page, added)).toBeVisible();
  await expect(manager.page.getByRole("button", { name: "Add person", exact: true })).toBeDisabled();
  await manager.page.clock.runFor(3_200);
  expect(reads()).toBe(1);
  expect(heldReads).toBe(0);
  expect(manager.frames.find((frame) => frame.kind === "room.member_added" && frame.roomId === room.roomId)?.mutationId).toMatch(/^[0-9a-f-]{36}$/);
  expect(reader.frames.find((frame) => frame.kind === "room.member_added" && frame.roomId === room.roomId)?.mutationId).toBeUndefined();
  await expectNoReload(manager);
  await expectNoReload(reader);
});

test("a lost add-member response releases the writer's roster refresh", async ({ page, browser }) => {
  const room = await scene(page);
  const added = await throwawayUser(page, "E2E Live New Member", cleanup);
  const manager = await signedIn(browser, room.owner);
  const reader = await signedIn(browser, room.mate);
  const memberPath = `/rooms/${room.roomId}/members`;
  await manager.page.clock.install();
  await openLive(manager, memberPath, room.roomId);
  await openLive(reader, memberPath, room.roomId);
  await expect(memberRow(reader.page, room.owner)).toBeVisible();
  await pickMemberToAdd(manager.page, added);
  await manager.page.clock.runFor(2_100);

  const release = await holdCommittedResponse(manager, { path: `/api/v1${memberPath}`, method: "POST", status: 200, loseResponse: true });
  try {
    await manager.page.getByRole("button", { name: "Add person", exact: true }).click();
    await expect.poll(() => manager.frames.some((frame) => frame.kind === "room.member_added" && frame.roomId === room.roomId)).toBe(true);
    await manager.page.clock.runFor(1_100);
    await expect(memberRow(reader.page, added)).toBeVisible();
    await expect(memberRow(manager.page, added)).toHaveCount(0);
  } finally {
    release();
  }

  await expect(memberRow(manager.page, added)).toBeVisible();
  await expectNoReload(manager);
  await expectNoReload(reader);
});

test("a member removed from a room loses it from the rail and the open room", async ({ page, browser }) => {
  const room = await scene(page);
  await seedThread(room);
  const manager = await signedIn(browser, room.owner);
  const removed = await signedIn(browser, room.mate);
  const observer = await signedIn(browser, room.owner);
  const memberPath = `/rooms/${room.roomId}/members`;
  const apiPath = `/api/v1${memberPath}`;
  await manager.page.clock.install();
  await openLive(manager, memberPath, room.roomId);
  await openLive(removed, `/rooms/${room.roomId}/threads/1`, room.roomId);
  await openLive(observer, memberPath, room.roomId);
  await expect(memberRow(observer.page, room.mate)).toBeVisible();
  const roomLink = rail(removed.page).getByRole("link", { name: /^e2e-live-/ });
  await expect(roomLink).toHaveCount(1);
  await expect(removed.page.getByRole("heading", { level: 3, name: /^#1 / })).toBeVisible();

  await rowOperation(manager.page, room.mate.name, "Remove");
  await manager.page.clock.runFor(2_100);
  const reads = countRosterReads(manager.page, apiPath);
  const release = await holdCommittedResponse(manager, { path: `${apiPath}/${room.mate.id}`, method: "DELETE", status: 204 });
  let heldReads: number;
  const dialog = manager.page.getByRole("dialog", { name: "Remove from the room?" });
  try {
    await dialog.getByRole("button", { name: "Remove", exact: true }).click();
    await expect.poll(() => manager.frames.some((frame) => frame.kind === "room.member_removed" && frame.roomId === room.roomId)).toBe(true);
    await manager.page.clock.runFor(1_100);
    // The losing socket is revoked and the owner's other tab refreshes before the writer receives the DELETE response.
    await expect(roomLink).toHaveCount(0);
    await expect(removed.page.getByRole("button", { name: "Request to join", exact: true })).toBeVisible();
    await expect(memberRow(observer.page, room.mate)).toHaveCount(0);
    heldReads = reads();
  } finally {
    release();
  }

  await expect(dialog).toHaveCount(0);
  await expect(memberRow(manager.page, room.mate)).toHaveCount(0);
  await manager.page.clock.runFor(3_200);
  expect(reads()).toBe(1);
  expect(heldReads).toBe(0);
  expect(manager.frames.find((frame) => frame.kind === "room.member_removed" && frame.roomId === room.roomId)?.mutationId).toMatch(/^[0-9a-f-]{36}$/);
  const revoked = removed.frames.find((frame) => frame.type === "revoked" && frame.roomId === room.roomId);
  expect(revoked).toBeDefined();
  expect(revoked?.mutationId).toBeUndefined();
  await expectNoReload(manager);
  await expectNoReload(removed);
  await expectNoReload(observer);
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
  // The socket under test belongs to a throwaway user: the ticket mint is a per-USER bucket (60 a minute), so this user's own bucket is
  // untouched by the administrator's sign-ins.
  await admin.login(page);
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
