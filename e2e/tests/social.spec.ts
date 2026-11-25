// Social (0.6.0): mentions picked in the composer reach the mentioned member's bell live and open the message; an owner can add a directory
// user and mention them without losing the draft; @here reaches foreground room members; the "Mentions me" filter; a
// hand-over notifies the new owner; the rail's and the thread list's unread marks and the conversation's "New" line; a reaction from the emoji
// picker (no content-security-policy violation) seen live by another member, who toggles theirs; the pinned panel and a pin's removal seen live; a pin's
// mark in the conversation, on the thread's header and list row, and the open thread's pin strip (1.2.0);
// the thread list's "Unread" filter and "Mark all as read" (1.2.0);
// the tab title counts the bell's number and a second tab of the same browser follows a "seen"; a hidden tab goes quiet, keeps counting, and closes after eight hours (1.2.0);
// a member removed from a room loses its notifications; the Notification preferences page keeps a saved switch; a kind switched off in the app stops arriving;
// a team mention (1.1.0) reaches the team's members who are in the room and no one else; everyone is told once, with the most specific reason;
// a quote (1.3.0) reaches the quoted message's author. Two browser contexts wherever live matters, with no reload in the watching one.
// Owns: its throwaway `e2e-soc-*` rooms and users, removed through the API in an `afterEach` (contexts first, then rooms, then the users). Every room
// is created by a throwaway user and only copies the seeded Default template. Threads and messages are never deleted: a room's removal hides them.
// The per-user `live-ticket`, `chat-post`, `chat-write` and `read-state` buckets are only ever spent by throwaway users, far below their limits.
import { chromium, type APIRequestContext, type Browser, type BrowserContext, type Page } from "@playwright/test";
import { BASE_URL } from "../playwright.config";
import { apiAsAdmin, apiAsUser, conversation, deleteViaApi, expect, login, rail, specAdmins, test, threadList, threadListRow, throwawayUser, uniqueText } from "./helpers";

/** This file's own administrator (see `specAdmins`): the page loads of its sign-ins spend this account's live-ticket bucket, not the seed administrator's. */
const admin = specAdmins("e2e-social-admin");

const cleanup: string[] = [];
const apis: APIRequestContext[] = [];
const contexts: BrowserContext[] = [];
const isolatedBrowsers: Browser[] = [];
test.afterEach(async () => {
  // Contexts first: a closed browser stops reconnecting, so no socket outlives the room it watched.
  for (const context of contexts.splice(0)) await context.close();
  for (const browser of isolatedBrowsers.splice(0)) await browser.close();
  for (const api of apis.splice(0)) await api.dispose();
  await deleteViaApi(cleanup.splice(0));
});

const MESSAGES_API = /\/api\/v1\/rooms\/\d+\/threads\/\d+\/messages$/;
const OWNER_API = /\/api\/v1\/rooms\/\d+\/threads\/\d+\/owner$/;
const THREAD_PIN_API = /\/api\/v1\/rooms\/\d+\/threads\/\d+\/pin$/;
const MESSAGE_PIN_API = /\/api\/v1\/rooms\/\d+\/messages\/\d+\/pin$/;
const PREFERENCES_API = /\/api\/v1\/users\/\d+\/notification-preferences$/;
const ROOM_SEEN_API =/\/api\/v1\/rooms\/\d+\/seen$/;
const READ_ALL_API = /\/api\/v1\/rooms\/\d+\/read-all$/;
const WATCH_API = /\/api\/v1\/rooms\/\d+\/threads\/\d+\/watch$/;
const READ_MARKER_API = /\/api\/v1\/rooms\/\d+\/threads\/\d+\/read-marker$/;

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

/** One signed-in browser: its own context (so its own session and its own socket), plus every frame its page received. */
interface Seat {
  page: Page;
  context: BrowserContext;
  frames: Frame[];
}

/** The room the people of a scene share: created by `owner` through the API, `mate` added as a plain member, `api` acting as the owner. */
interface Scene {
  owner: Person;
  mate: Person;
  roomId: number;
  roomName: string;
  api: APIRequestContext;
}

const threadLink = (page: Page, title: string) => threadList(page).getByRole("link", { name: new RegExp(title) });
const messageNamed = (page: Page, text: string) => conversation(page).getByRole("listitem").filter({ hasText: text });
/** The header bell: its name carries the unseen count ("Notifications (1 unseen)"). */
const bell = (page: Page) => page.getByRole("button", { name: /^Notifications \(/ });
const unseenBell = (count: number) => new RegExp(`^Notifications \\(${count} unseen\\)$`);
const drawer = (page: Page) => page.getByRole("dialog", { name: "Notifications", exact: true });
// The room header's drawer button ("Pinned (n)"); the open thread's pin strip is a button too, but its name is "Pinned: <excerpt>" or "Pinned in this thread (n)".
const pinnedButton = (page: Page) => page.getByRole("button", { name: /^Pinned( \(\d+\))?$/ });
const pinStrip = (page: Page) => page.getByRole("region", { name: "Pins in this thread", exact: true });
const pinnedDrawer = (page: Page) => page.getByRole("dialog", { name: "Pinned in this room", exact: true });

/** The admin (signed in on `page`) mints the owner and the teammate; the owner then makes a room and adds the teammate, all through the API. */
async function scene(page: Page, {
  addMate = true,
  accessType = "RESTRICTED_VISIBLE",
}: { addMate?: boolean; accessType?: "RESTRICTED_VISIBLE" | "RESTRICTED_HIDDEN" } = {}): Promise<Scene> {
  await admin.login(page);
  const owner = await throwawayUser(page, "E2E Soc Owner", cleanup);
  const mate = await throwawayUser(page, "E2E Soc Mate", cleanup);
  const { api } = await apiAsUser(owner.email, owner.password);
  apis.push(api);
  const templates = await api.get("/api/v1/workflow-templates?name=Default");
  expect(templates.status(), await templates.text()).toBe(200);
  const template = ((await templates.json()) as { items: { id: number; name: string }[] }).items.find((candidate) => candidate.name === "Default");
  expect(template, "the seeded Default template").toBeDefined();
  const roomName = uniqueText("e2e-soc");
  const created = await api.post("/api/v1/rooms", { data: { name: roomName, templateId: template!.id, accessType } });
  expect(created.status(), await created.text()).toBe(201);
  const roomId = ((await created.json()) as { id: number }).id;
  cleanup.unshift(`/api/v1/rooms/${roomId}`);
  const room: Scene = { owner, mate, roomId, roomName, api };
  if (addMate) await addMember(room);
  return room;
}

async function addMember(room: Scene, { notify = false } = {}): Promise<void> {
  if (!notify) await disableRoomAddedNotification(room.mate);
  const added = await room.api.post(`/api/v1/rooms/${room.roomId}/members`, { data: { userIds: [room.mate.id] } });
  expect(added.status(), await added.text()).toBe(200);
}

/** Keep scenarios about another notice at an empty bell while still adding the member through the UI under test. */
async function disableRoomAddedNotification(person: Person): Promise<void> {
  const { api } = await apiAsUser(person.email, person.password);
  try {
    const preferences = await api.put(`/api/v1/users/${person.id}/notification-preferences`, {
      data: { disabled: [], roomMembership: { inApp: false, email: false } },
    });
    expect(preferences.status(), await preferences.text()).toBe(204);
  } finally {
    await api.dispose();
  }
}

test("a hidden room membership notice arrives live and opens the room", async ({ page, browser }) => {
  const room = await scene(page, { addMate: false, accessType: "RESTRICTED_HIDDEN" });
  const reader = await signedIn(browser, room.mate);
  await expect.poll(() => reader.frames.some((frame) => frame.type === "ready")).toBe(true);
  await expect(bell(reader.page)).toHaveAccessibleName(unseenBell(0));
  await expect(rail(reader.page).getByRole("link", { name: room.roomName })).toHaveCount(0);

  await addMember(room, { notify: true });

  await expect(bell(reader.page)).toHaveAccessibleName(unseenBell(1));
  await bell(reader.page).click();
  await expect(drawer(reader.page).getByText(room.roomName, { exact: true })).toBeVisible();
  await drawer(reader.page).getByRole("link", { name: /open room/i }).click();
  await expect(reader.page).toHaveURL(new RegExp(`/rooms/${room.roomId}(?:/threads)?$`));
  await expect(bell(reader.page)).toHaveAccessibleName(unseenBell(0));
});

/** Start a thread as the owner through the API; returns its number. */
async function startThread(room: Scene, title: string, body = "Seeded through the API."): Promise<number> {
  const created = await room.api.post(`/api/v1/rooms/${room.roomId}/threads`, { data: { title, body } });
  expect(created.status(), await created.text()).toBe(201);
  return ((await created.json()) as { number: number }).number;
}

/** Post a message as the owner through the API (a `<@id>` token is a mention, `quotedMessageId` quotes a message of the thread); returns its id. */
async function postMessage(room: Scene, number: number, body: string, quotedMessageId?: number): Promise<number> {
  const posted = await room.api.post(`/api/v1/rooms/${room.roomId}/threads/${number}/messages`, { data: quotedMessageId === undefined ? { body } : { body, quotedMessageId } });
  expect(posted.status(), await posted.text()).toBe(201);
  return ((await posted.json()) as { id: number }).id;
}

/** A fresh browser context signed in as `who`, recording the frames of every live socket its page opens. */
async function signedIn(browser: Browser, who: Person, before?: (context: BrowserContext) => Promise<void>, landOn = "/"): Promise<Seat> {
  const context = await browser.newContext({ baseURL: BASE_URL });
  contexts.push(context);
  await before?.(context);
  const page = await context.newPage();
  const seat: Seat = { page, context, frames: [] };
  page.on("websocket", (socket) => {
    socket.on("framereceived", ({ payload }) => {
      try {
        seat.frames.push(JSON.parse(String(payload)) as Frame);
      } catch {
        // A frame that is not JSON is not one of ours; the client ignores it too.
      }
    });
  });
  await login(page, who.email, who.password, landOn);
  return seat;
}

/**
 * Open `path` and wait until the page's socket has been acknowledged for `roomId`: from then on a change is PUSHED, it can no longer be picked
 * up by the page's own load. Marks the document too, so a later check can prove nothing reloaded.
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

/** Records every `securitypolicyviolation` the document reports, from before the first script runs (read back with `cspViolations`). */
async function recordCspViolations(context: BrowserContext): Promise<void> {
  await context.addInitScript(() => {
    const seen: string[] = [];
    Object.assign(window, { e2eCspViolations: seen });
    document.addEventListener("securitypolicyviolation", (event) => seen.push(event.effectiveDirective));
  });
}

const cspViolations = (page: Page) => page.evaluate(() => (window as unknown as { e2eCspViolations?: string[] }).e2eCspViolations);

/** The last word of a throwaway user's name: unique to the run, and one token the member search matches as a substring. */
const uniquePart = (person: Person) => person.name.slice(person.name.lastIndexOf("-") + 1);

test("a mention picked in the composer reaches the mentioned member's bell without a reload and opens the message", async ({ page, browser }) => {
  // The teammate joins AFTER the thread opened: a new thread is told to the members already in the room, and this scenario counts what it causes.
  const room = await scene(page, { addMate: false });
  const title = uniqueText("Mention thread");
  await startThread(room, title);
  await addMember(room);
  const author = await signedIn(browser, room.owner);
  const reader = await signedIn(browser, room.mate);
  await openLive(author, `/rooms/${room.roomId}/threads/1`, room.roomId);
  // The teammate is in the room but not in the thread: a notice stays unseen until its message is read.
  await openLive(reader, `/rooms/${room.roomId}`, room.roomId);
  await expect(bell(reader.page)).toHaveAccessibleName(unseenBell(0));
  await expect(threadLink(reader.page, title)).toBeVisible();

  // The author types an @, the list names the room's members, and picking one turns the word into a mention.
  const box = author.page.getByRole("textbox", { name: "Reply", exact: true });
  await box.pressSequentially(`Heads up @${uniquePart(room.mate)}`);
  await author.page.getByRole("option", { name: room.mate.name }).click();
  await expect(box).toHaveValue(new RegExp(`@${room.mate.name}`));
  await box.pressSequentially("the replica is lagging.");
  const [posted] = await Promise.all([
    author.page.waitForResponse((r) => r.request().method() === "POST" && MESSAGES_API.test(r.url()) && r.status() === 201),
    box.press("Control+Enter"),
  ]);
  const messageId = ((await posted.json()) as { id: number }).id;

  // The teammate's bell counts it with no action; the room in the rail says so in words as well; the drawer words the notice.
  await expect(bell(reader.page)).toHaveAccessibleName(unseenBell(1));
  await expect(rail(reader.page).getByRole("link", { name: room.roomName })).toHaveAccessibleDescription(/1 unseen notification/);
  await expectNoReload(reader);
  await bell(reader.page).click();
  const notice = drawer(reader.page).getByRole("link", { name: new RegExp(`${room.owner.name} mentioned you in #1 ${title}`) });
  await expect(notice).toBeVisible();
  await expect(notice).toHaveAccessibleName(/^Unseen /);

  // Opening the notice goes to the message (`?message=`), shows it and the badge clears.
  await notice.click();
  await expect(reader.page).toHaveURL(new RegExp(`/rooms/${room.roomId}/threads/1\\?.*message=${messageId}`));
  await expect(drawer(reader.page)).toHaveCount(0);
  await expect(messageNamed(reader.page, "the replica is lagging.")).toBeVisible();
  await expect(messageNamed(reader.page, "the replica is lagging.")).toContainText(`@${room.mate.name}`);
  await expect(bell(reader.page)).toHaveAccessibleName(unseenBell(0));
  await expect(rail(reader.page).getByRole("link", { name: room.roomName })).not.toHaveAccessibleDescription(/unseen notification/);
  await expectNoReload(reader);
});

test("an owner can add a directory user and mention them without losing the draft", async ({ page, browser }) => {
  const room = await scene(page, { addMate: false });
  const title = uniqueText("Add and mention thread");
  await startThread(room, title);
  await disableRoomAddedNotification(room.mate);
  const author = await signedIn(browser, room.owner);
  const reader = await signedIn(browser, room.mate);
  await openLive(author, `/rooms/${room.roomId}/threads/1`, room.roomId);
  await expect.poll(() => reader.frames.some((frame) => frame.type === "ready")).toBe(true);
  await expect(bell(reader.page)).toHaveAccessibleName(unseenBell(0));

  const box = author.page.getByRole("textbox", { name: "Reply", exact: true });
  await author.page.getByRole("button", { name: "Add and mention" }).click();
  const dialog = author.page.getByRole("dialog", { name: "Add someone to this room" });
  await dialog.getByRole("button", { name: "Cancel" }).click();
  const term = uniquePart(room.mate);
  await box.pressSequentially(`Please review this draft @${term}`);
  await expect(author.page.getByRole("option", { name: /Add and mention.*Search the directory/ })).toBeVisible();
  await box.press("Enter");
  const userPicker = dialog.getByRole("combobox", { name: "User" });
  await expect(userPicker).toHaveValue(term);
  await userPicker.click();
  await author.page.getByRole("option", { name: `${room.mate.name} (${room.mate.email})`, exact: true }).click();
  await Promise.all([
    author.page.waitForResponse((response) => response.request().method() === "POST" && response.url().endsWith(`/rooms/${room.roomId}/members`) && response.status() === 200),
    dialog.getByRole("button", { name: "Add and mention" }).click(),
  ]);
  await expect(dialog).toHaveCount(0);
  await expect(box).toHaveValue(`Please review this draft @${room.mate.name} `);

  const [posted] = await Promise.all([
    author.page.waitForResponse((response) => response.request().method() === "POST" && MESSAGES_API.test(response.url()) && response.status() === 201),
    box.press("Control+Enter"),
  ]);
  const message = (await posted.json()) as { mentions: { userId: number; name: string }[] };
  expect(message.mentions).toContainEqual({ userId: room.mate.id, name: room.mate.name });
  await expect(bell(reader.page)).toHaveAccessibleName(unseenBell(1));
  await bell(reader.page).click();
  await expect(drawer(reader.page).getByRole("link", { name: new RegExp(`${room.owner.name} mentioned you in #1 ${title}`) })).toBeVisible();
});

test("here mentions only the room members who are online now", async ({ page, browser }) => {
  const room = await scene(page, { addMate: false });
  const title = uniqueText("Here thread");
  await startThread(room, title);
  await addMember(room);
  const author = await signedIn(browser, room.owner);
  const reader = await signedIn(browser, room.mate);
  await openLive(author, `/rooms/${room.roomId}/threads/1`, room.roomId);
  await openLive(reader, `/rooms/${room.roomId}`, room.roomId);
  await expect(bell(reader.page)).toHaveAccessibleName(unseenBell(0));

  const box = author.page.getByRole("textbox", { name: "Reply", exact: true });
  await box.pressSequentially("Are you around @onl");
  const groups = author.page.getByRole("group", { name: "Groups", exact: true });
  const here = groups.getByRole("option", { name: /@here.*Online members of this room/i });
  await expect(here).toBeVisible();
  await here.click();
  await expect(box).toHaveValue("Are you around @here ");
  const [posted] = await Promise.all([
    author.page.waitForResponse((response) => response.request().method() === "POST" && MESSAGES_API.test(response.url()) && response.status() === 201),
    box.press("Control+Enter"),
  ]);
  const message = (await posted.json()) as { groupMentions: { kind: string }[] };
  expect(message.groupMentions.map((group) => group.kind)).toEqual(["HERE"]);

  await expect(bell(reader.page)).toHaveAccessibleName(unseenBell(1));
  await expectNoReload(reader);
  await bell(reader.page).click();
  await expect(drawer(reader.page).getByRole("link", { name: new RegExp(`${room.owner.name} mentioned people here in #1 ${title}`) })).toBeVisible();
  await expect(bell(author.page)).toHaveAccessibleName(unseenBell(0));
});

test("the Mentions me filter shows the thread and survives a reload", async ({ page }) => {
  const room = await scene(page);
  const plain = uniqueText("Plain");
  const mentioning = uniqueText("Mentioning");
  await startThread(room, plain, "Nobody is addressed here.");
  const number = await startThread(room, mentioning, "Opening message.");
  await postMessage(room, number, `<@${room.mate.id}> can you take a look?`);
  await login(page, room.mate.email, room.mate.password);

  await page.goto(`/rooms/${room.roomId}`);
  await expect(threadLink(page, plain)).toBeVisible();
  await expect(threadLink(page, mentioning)).toBeVisible();
  const toggle = page.getByRole("button", { name: /^Filters/ });
  await toggle.click();
  await expect(toggle).toHaveAttribute("aria-expanded", "true");
  const filter = page.getByRole("button", { name: "Mentions me", exact: true });
  await expect(filter).toHaveAttribute("aria-pressed", "false");

  // Only the thread that mentions the signed-in member stays; the address keeps the filter.
  await filter.click();
  await expect(filter).toHaveAttribute("aria-pressed", "true");
  await expect(page).toHaveURL(/[?&]mention=me(&|$)/);
  await expect(threadLink(page, mentioning)).toBeVisible();
  await expect(threadLink(page, plain)).toHaveCount(0);
  await expect(toggle).toContainText("1");

  // The URL is the state: a reload keeps the filter and the narrowed list.
  await page.reload();
  await expect(page).toHaveURL(/[?&]mention=me(&|$)/);
  await expect(threadLink(page, mentioning)).toBeVisible();
  await expect(threadLink(page, plain)).toHaveCount(0);
  await expect(page.getByRole("button", { name: "Mentions me", exact: true })).toHaveAttribute("aria-pressed", "true");
});

test("handing a thread over notifies its new owner", async ({ page, browser }) => {
  const room = await scene(page, { addMate: false });
  const title = uniqueText("Handover thread");
  await startThread(room, title);
  await addMember(room); // after the thread opened: the room was not told of a thread the teammate was not there for
  const giver = await signedIn(browser, room.owner);
  const receiver = await signedIn(browser, room.mate);
  await openLive(giver, `/rooms/${room.roomId}/threads/1`, room.roomId);
  await openLive(receiver, `/rooms/${room.roomId}`, room.roomId);
  await expect(bell(receiver.page)).toHaveAccessibleName(unseenBell(0));

  // The owner passes the thread on: choosing the member does not move it, "Pass on" does.
  await expect(giver.page.getByRole("region", { name: /^#1 / })).toBeVisible();
  const toggle = giver.page.getByRole("button", { name: "Details", exact: true });
  if ((await toggle.getAttribute("aria-expanded")) === "false") await toggle.click();
  await expect(toggle).toHaveAttribute("aria-expanded", "true");
  const picker = giver.page.getByRole("combobox", { name: "Owner", exact: true });
  await picker.click();
  await picker.fill(room.mate.name);
  await giver.page.getByRole("option", { name: `${room.mate.name} (${room.mate.email})` }).click();
  await Promise.all([
    giver.page.waitForResponse((r) => r.request().method() === "PUT" && OWNER_API.test(r.url()) && r.status() === 204),
    giver.page.getByRole("button", { name: "Pass on", exact: true }).click(),
  ]);

  // The new owner's bell counts it without a reload and the drawer says who handed it over.
  await expect(bell(receiver.page)).toHaveAccessibleName(unseenBell(1));
  await expectNoReload(receiver);
  await bell(receiver.page).click();
  const notice = drawer(receiver.page).getByRole("link", { name: new RegExp(`${room.owner.name} made you the owner of #1 ${title}`) });
  await expect(notice).toBeVisible();
  await expect(notice).toContainText(`${room.owner.name} → ${room.mate.name}`);

  // Following it opens the thread, whose pane names the new owner, and the badge clears.
  await notice.click();
  await expect(receiver.page).toHaveURL(new RegExp(`/rooms/${room.roomId}/threads/1`));
  await expect(receiver.page.getByRole("region", { name: `#1 ${title}` })).toContainText(`Owner: ${room.mate.name}`);
  await expect(bell(receiver.page)).toHaveAccessibleName(unseenBell(0));
});

test("a message posted elsewhere marks the room and the thread as new, and reading it clears both", async ({ page, browser }) => {
  // The teammate joins AFTER the opening message: what was written before joining is never unread.
  const room = await scene(page, { addMate: false });
  const title = uniqueText("Unread thread");
  await startThread(room, title, "Written before the teammate joined.");
  await addMember(room);
  const reader = await signedIn(browser, room.mate);
  const roomLink = rail(reader.page).getByRole("link", { name: room.roomName });
  await openLive(reader, "/", room.roomId);
  await expect(roomLink).toBeVisible();
  await expect(roomLink).not.toHaveAccessibleDescription(/New activity/);

  // The owner writes two messages in the room the teammate is not looking at: the rail's link says "New activity", with no reload.
  await postMessage(room, 1, "First news.");
  await postMessage(room, 1, "Second news.");
  await expect(roomLink).toHaveAccessibleDescription(/New activity/);
  await expectNoReload(reader);

  // Opening the room shows which thread has them: its row says how many are unread. The room itself is acknowledged.
  const [seen] = await Promise.all([
    reader.page.waitForResponse((r) => r.request().method() === "POST" && ROOM_SEEN_API.test(r.url()) && r.status() === 204),
    roomLink.click(),
  ]);
  expect(seen.ok()).toBe(true);
  await expect(threadLink(reader.page, title)).toHaveAccessibleDescription(/2 unread messages/);

  // Opening the thread puts a "New" line above the first message the teammate had not read, and not above the older one.
  await threadLink(reader.page, title).click();
  await expect(messageNamed(reader.page, "First news.")).toBeVisible();
  const line = reader.page.getByRole("separator", { name: "New messages", exact: true });
  await expect(line).toHaveCount(1);
  await expect(messageNamed(reader.page, "First news.").getByRole("separator", { name: "New messages", exact: true })).toBeVisible();
  await expect(messageNamed(reader.page, "Written before the teammate joined.").getByRole("separator")).toHaveCount(0);

  // Reading it to the end clears the thread's mark; leaving the room, its dot is gone too (the server holds both, not the page).
  await expect(threadLink(reader.page, title)).not.toHaveAccessibleDescription(/unread/);
  await reader.page.goto("/");
  await expect(roomLink).toBeVisible();
  await expect(roomLink).not.toHaveAccessibleDescription(/New activity/);
});

test("the unread filter lists only threads with new messages", async ({ page, browser }) => {
  // The teammate joins AFTER the first thread: what was written before joining is never unread, so only the second thread is.
  const room = await scene(page, { addMate: false });
  const older = uniqueText("Before joining");
  const fresh = uniqueText("Fresh news");
  await startThread(room, older, "Written before the teammate joined.");
  await addMember(room);
  const freshNumber = await startThread(room, fresh, "Opening message.");
  await postMessage(room, freshNumber, "A second message.");
  const reader = await signedIn(browser, room.mate);
  await reader.page.goto(`/rooms/${room.roomId}`);
  await expect(threadLink(reader.page, older)).toBeVisible();
  await expect(threadLink(reader.page, fresh)).toHaveAccessibleDescription(/2 unread messages/);
  await expect(threadLink(reader.page, older)).not.toHaveAccessibleDescription(/unread/);

  // "Unread" narrows the list to the thread with something new; the address keeps it, and so does a reload.
  const toggle = reader.page.getByRole("button", { name: /^Filters/ });
  await toggle.click();
  const filter = reader.page.getByRole("button", { name: "Unread", exact: true });
  await expect(filter).toHaveAttribute("aria-pressed", "false");
  await filter.click();
  await expect(filter).toHaveAttribute("aria-pressed", "true");
  await expect(reader.page).toHaveURL(/[?&]unread=me(&|$)/);
  await expect(threadLink(reader.page, fresh)).toBeVisible();
  await expect(threadLink(reader.page, older)).toHaveCount(0);
  await expect(toggle).toContainText("1");
  await reader.page.reload();
  await expect(reader.page).toHaveURL(/[?&]unread=me(&|$)/);
  await expect(threadLink(reader.page, fresh)).toBeVisible();
  await expect(threadLink(reader.page, older)).toHaveCount(0);

  // The API agrees (the filter is exactly "unreadCount above 0"), and turning the filter off brings the other thread back.
  const { api: mateApi } = await apiAsUser(room.mate.email, room.mate.password);
  apis.push(mateApi);
  const unread = await mateApi.get(`/api/v1/rooms/${room.roomId}/threads?unread=true`);
  expect(unread.status(), await unread.text()).toBe(200);
  expect(((await unread.json()) as { items: { number: number }[] }).items.map((item) => item.number)).toEqual([freshNumber]);
  // The panel's open state is remembered, so after the reload it is still open.
  await expect(toggle).toHaveAttribute("aria-expanded", "true");
  await reader.page.getByRole("button", { name: "Unread", exact: true }).click();
  await expect(threadLink(reader.page, older)).toBeVisible();
  await expect(reader.page).not.toHaveURL(/unread=/);
});

test("mark all as read clears a room", async ({ page, browser }) => {
  const room = await scene(page, { addMate: false });
  const title = uniqueText("Mark all");
  await startThread(room, title, "Written before the teammate joined.");
  await addMember(room);
  await postMessage(room, 1, `<@${room.mate.id}> please have a look.`);
  await postMessage(room, 1, "And a second thing.");
  const reader = await signedIn(browser, room.mate);
  await openLive(reader, `/rooms/${room.roomId}?unread=me`, room.roomId);
  await expect(threadLink(reader.page, title)).toHaveAccessibleDescription(/2 unread messages/);
  await expect(bell(reader.page)).not.toHaveAccessibleName(unseenBell(0));

  // It asks first: Cancel changes nothing.
  const action = reader.page.getByRole("button", { name: "Mark all as read", exact: true });
  await action.click();
  const dialog = reader.page.getByRole("dialog", { name: "Mark all as read?", exact: true });
  await expect(dialog).toBeVisible();
  await dialog.getByRole("button", { name: "Cancel", exact: true }).click();
  await expect(dialog).toHaveCount(0);
  await expect(threadLink(reader.page, title)).toHaveAccessibleDescription(/2 unread messages/);

  // Confirming clears the thread's count, the room's notices (the bell) and the open Unread filter, without a reload.
  await action.click();
  const [marked] = await Promise.all([
    reader.page.waitForResponse((r) => r.request().method() === "POST" && READ_ALL_API.test(r.url()) && r.status() === 204),
    dialog.getByRole("button", { name: "Mark all as read", exact: true }).click(),
  ]);
  expect(marked.ok()).toBe(true);
  await expect(dialog).toHaveCount(0);
  await expect(bell(reader.page)).toHaveAccessibleName(unseenBell(0));
  await expect(reader.page.getByText("No threads match these filters.")).toBeVisible();
  await expectNoReload(reader);
  const { api: mateApi } = await apiAsUser(room.mate.email, room.mate.password);
  apis.push(mateApi);
  const none = await mateApi.get(`/api/v1/rooms/${room.roomId}/threads?unread=true`);
  expect(((await none.json()) as { total: number }).total).toBe(0);

  // Only what comes AFTER is unread again: a new message from the owner brings the thread back into the filter.
  await postMessage(room, 1, "Something new after the mark.");
  await expect(threadLink(reader.page, title)).toHaveAccessibleDescription(/1 unread message/);
});

test("a reaction from the picker appears for another member without a reload, and a second press removes it", async ({ page, browser }) => {
  const room = await scene(page);
  const title = uniqueText("Reaction thread");
  await startThread(room, title, "React to this one.");
  const thread = `/rooms/${room.roomId}/threads/1`;
  const author = await signedIn(browser, room.owner, recordCspViolations);
  const reader = await signedIn(browser, room.mate, recordCspViolations);
  await openLive(author, thread, room.roomId);
  await openLive(reader, thread, room.roomId);
  const readerMessage = messageNamed(reader.page, "React to this one.");
  await expect(readerMessage).toBeVisible();
  await expect(readerMessage.getByRole("list", { name: "Reactions" })).toHaveCount(0);

  // The author opens the emoji picker under the message and picks a thumbs-up (the picker is lazy-loaded and lives in a shadow root).
  const authorMessage = messageNamed(author.page, "React to this one.");
  await authorMessage.getByRole("button", { name: "Add a reaction", exact: true }).click();
  const picker = author.page.locator("em-emoji-picker");
  await picker.getByRole("searchbox").fill("thumbs up");
  await Promise.all([
    author.page.waitForResponse((r) => r.request().method() === "PUT" && /\/messages\/\d+\/reaction$/.test(r.url()) && r.ok()),
    picker.getByRole("button", { name: "👍" }).first().click(),
  ]);

  // The author's chip is pressed; the teammate's appears with no action, not pressed, and names who reacted.
  const authorChip = authorMessage.getByRole("button", { name: "👍, 1 reaction", exact: true });
  await expect(authorChip).toHaveAttribute("aria-pressed", "true");
  const readerChip = readerMessage.getByRole("button", { name: /^👍, \d+ reactions?$/ });
  await expect(readerChip).toHaveAccessibleName("👍, 1 reaction");
  await expect(readerChip).toHaveAttribute("aria-pressed", "false");
  await expect(readerChip).toHaveAccessibleDescription(room.owner.name);
  await expectNoReload(reader);

  // The separate roster action works by keyboard or touch and keeps the person's profile link out of the reaction toggle.
  await readerMessage.getByRole("button", { name: "See everyone who reacted with 👍" }).click();
  const roster = reader.page.getByRole("dialog", { name: "👍 reactions" });
  await expect(roster.getByRole("link", { name: room.owner.name })).toHaveAttribute("href", `/people/${room.owner.id}`);
  await roster.getByRole("button", { name: "Close" }).click();
  await expect(roster).toBeHidden();

  // The teammate presses it: theirs joins (two reactions, pressed), and the author's chip follows live.
  await readerChip.click();
  await expect(readerChip).toHaveAccessibleName("👍, 2 reactions");
  await expect(readerChip).toHaveAttribute("aria-pressed", "true");
  await expect(authorMessage.getByRole("button", { name: "👍, 2 reactions", exact: true })).toBeVisible();

  // A second press takes it back: one reaction again, no longer pressed for the teammate, and the author's chip says one.
  await readerChip.click();
  await expect(readerChip).toHaveAccessibleName("👍, 1 reaction");
  await expect(readerChip).toHaveAttribute("aria-pressed", "false");
  await expect(authorMessage.getByRole("button", { name: "👍, 1 reaction", exact: true })).toBeVisible();
  await expectNoReload(author);

  // The picker's lazy chunk and shadow-root styles ran under the stack's strict policy: the browser reported no violation, in either page.
  expect(await cspViolations(author.page), "no security policy violation on the author's page").toEqual([]);
  expect(await cspViolations(reader.page), "no security policy violation on the teammate's page").toEqual([]);
});

test("a pinned thread and a pinned message show in the pinned panel for another member, and unpinning removes them", async ({ page, browser }) => {
  const room = await scene(page);
  const title = uniqueText("Pinned thread");
  await startThread(room, title, "The opening message.");
  await postMessage(room, 1, "Keep this runbook step within reach.");
  const thread = `/rooms/${room.roomId}/threads/1`;
  const author = await signedIn(browser, room.owner);
  const reader = await signedIn(browser, room.mate);
  await openLive(author, thread, room.roomId);
  await openLive(reader, thread, room.roomId);
  await expect(pinnedButton(reader.page)).toHaveText("Pinned (0)");

  // The author pins the thread from its pane and one message from the message's menu.
  const threadPin = author.page.getByRole("button", { name: "Pin this thread", exact: true });
  await expect(threadPin).toHaveAttribute("aria-pressed", "false");
  await Promise.all([
    author.page.waitForResponse((r) => r.request().method() === "PUT" && THREAD_PIN_API.test(r.url()) && r.ok()),
    threadPin.click(),
  ]);
  await expect(threadPin).toHaveAttribute("aria-pressed", "true");
  const authorMessage = messageNamed(author.page, "Keep this runbook step within reach.");
  await authorMessage.getByRole("button", { name: /^Operations for message by / }).click();
  await Promise.all([
    author.page.waitForResponse((r) => r.request().method() === "PUT" && MESSAGE_PIN_API.test(r.url()) && r.ok()),
    author.page.getByRole("menuitem", { name: "Pin", exact: true }).click(),
  ]);

  // The teammate's button counts both with no reload, and the panel lists the thread and the message with who pinned them.
  await expect(pinnedButton(reader.page)).toHaveText("Pinned (2)");
  await expectNoReload(reader);
  await pinnedButton(reader.page).click();
  const panel = pinnedDrawer(reader.page);
  await expect(panel.getByRole("link", { name: `#1 ${title}`, exact: true })).toBeVisible();
  await expect(panel.getByText(`Message by ${room.owner.name}`)).toBeVisible();
  await expect(panel.getByText("Keep this runbook step within reach.")).toBeVisible();
  await expect(panel.getByText(new RegExp(`^Pinned by ${room.owner.name} on `))).toHaveCount(2);
  // A pin is the room's: a plain member may unpin too, so the controls are there for the teammate as well.
  await expect(panel.getByRole("button", { name: /^Unpin / })).toHaveCount(2);

  // The author unpins both; the teammate's open panel empties and its button says zero, still with no reload.
  await threadPin.click();
  await expect(threadPin).toHaveAttribute("aria-pressed", "false");
  await authorMessage.getByRole("button", { name: /^Operations for message by / }).click();
  await author.page.getByRole("menuitem", { name: "Unpin", exact: true }).click();
  await expect(panel.getByText("Nothing is pinned yet.")).toBeVisible();
  await expect(panel.getByRole("link", { name: `#1 ${title}`, exact: true })).toHaveCount(0);
  await expectNoReload(reader);
  await reader.page.keyboard.press("Escape");
  await expect(pinnedButton(reader.page)).toHaveText("Pinned (0)");
});

test("a pinned message is marked in the conversation and listed in the thread's pinned strip", async ({ page, browser }) => {
  const room = await scene(page);
  const title = uniqueText("Pin strip thread");
  await startThread(room, title, "The opening message.");
  const runbookId = await postMessage(room, 1, "Keep this runbook step within reach.");
  await postMessage(room, 1, "Rotate the replica credentials.\nSecond line of the note.");
  const thread = `/rooms/${room.roomId}/threads/1`;
  const author = await signedIn(browser, room.owner);
  const reader = await signedIn(browser, room.mate);
  await openLive(author, thread, room.roomId);
  await openLive(reader, thread, room.roomId);
  const readerRunbook = messageNamed(reader.page, "Keep this runbook step within reach.");
  const readerNote = messageNamed(reader.page, "Rotate the replica credentials.");
  await expect(readerRunbook).toBeVisible();
  // Nothing is pinned: no strip, no mark.
  await expect(pinStrip(reader.page)).toHaveCount(0);
  await expect(conversation(reader.page).getByText(/^Pinned by /)).toHaveCount(0);

  // The author pins both messages from their menus (the note last, so it is the newest pin).
  for (const text of ["Keep this runbook step within reach.", "Rotate the replica credentials."]) {
    await messageNamed(author.page, text).getByRole("button", { name: /^Operations for message by / }).click();
    await Promise.all([
      author.page.waitForResponse((r) => r.request().method() === "PUT" && MESSAGE_PIN_API.test(r.url()) && r.ok()),
      author.page.getByRole("menuitem", { name: "Pin", exact: true }).click(),
    ]);
  }

  // The reader, with no action and no reload: both messages say who pinned them (the words and the pin shape, not only a colour), and the strip
  // appeared under the header as ONE collapsed line quoting the newest pin's first line and counting the other.
  await expect(readerRunbook).toContainText(`Pinned by ${room.owner.name}`);
  await expect(readerNote).toContainText(`Pinned by ${room.owner.name}`);
  await expect(readerRunbook).toHaveAttribute("data-pinned", "true");
  await expect(conversation(reader.page).getByRole("listitem").filter({ hasText: "The opening message." })).not.toHaveAttribute("data-pinned", "true");
  const strip = pinStrip(reader.page);
  const toggle = strip.getByRole("button", { name: "Pinned: Rotate the replica credentials. (+1)", exact: true });
  await expect(toggle).toHaveAttribute("aria-expanded", "false");
  await expect(strip.getByRole("link")).toHaveCount(0);
  await expectNoReload(reader);

  // Expanded in place: both pins, newest first, each with its jump and its Unpin (any member may unpin).
  await toggle.click();
  const entries = strip.getByRole("listitem");
  await expect(entries).toHaveCount(2);
  await expect(entries.nth(0)).toContainText("Rotate the replica credentials. Second line of the note.");
  await expect(entries.nth(0)).toContainText(`Pinned by ${room.owner.name} on `);
  await expect(entries.nth(1)).toContainText("Keep this runbook step within reach.");

  // "Show in thread" is the 0.5.0 jump: it names the message in the URL, focuses it, and works again for a message it already landed on. The strip
  // is in flow above the conversation, so the target must be on screen and not covered: its top edge is below the strip's bottom edge.
  const expectShown = async (where: string) => {
    await expect(readerRunbook, `the message is on screen ${where}`).toBeInViewport();
    await expect(readerRunbook).toBeFocused();
    const stripBox = (await strip.boundingBox())!;
    const messageBox = (await readerRunbook.boundingBox())!;
    expect(messageBox.y, `the message is not under the strip ${where}`).toBeGreaterThanOrEqual(stripBox.y + stripBox.height);
  };
  const showRunbook = entries.nth(1).getByRole("link", { name: /^Show in thread: / });
  await showRunbook.click();
  await expect(reader.page).toHaveURL(new RegExp(`message=${runbookId}`));
  await expectShown("after the first jump");
  await reader.page.getByRole("textbox", { name: "Reply", exact: true }).focus();
  await expect(readerRunbook).not.toBeFocused();
  await showRunbook.click();
  await expect(reader.page).toHaveURL(new RegExp(`message=${runbookId}`));
  await expectShown("after jumping again to a message already landed on");

  // A pinned thread is marked too: a "Pinned" badge in its header and on its list row (the row keeps the thread's name; the badge is for the eye).
  const header = reader.page.getByRole("region", { name: new RegExp(`^#1 ${title}`) });
  await expect(header.locator("[data-pin-mark]")).toHaveCount(0);
  await author.page.getByRole("button", { name: "Pin this thread", exact: true }).click();
  await expect(header.locator("[data-pin-mark]")).toHaveText("Pinned");
  const row = threadLink(reader.page, title);
  await expect(threadListRow(reader.page, title).locator("[data-pin-mark]")).toBeVisible();
  await expect(row).toHaveAccessibleDescription(/Pinned/);

  // The reader unpins one from the strip: its mark goes at once in both pages and the focus lands on the strip's button; the last unpin takes the strip
  // away and the focus goes to the thread's pane. No reload in either page.
  await entries.nth(1).getByRole("button", { name: /^Unpin / }).click();
  await expect(readerRunbook).not.toContainText("Pinned by");
  await expect(messageNamed(author.page, "Keep this runbook step within reach.")).not.toContainText("Pinned by");
  await expect(toggle).toBeHidden();
  const single = strip.getByRole("button", { name: "Pinned in this thread (1)", exact: true });
  await expect(single).toHaveAttribute("aria-expanded", "true");
  await expect(single).toBeFocused();
  await strip.getByRole("button", { name: /^Unpin / }).click();
  await expect(pinStrip(reader.page)).toHaveCount(0);
  await expect(header).toBeFocused();
  await expect(pinStrip(author.page)).toHaveCount(0);
  await expect(conversation(reader.page).getByText(/^Pinned by /)).toHaveCount(0);
  await expectNoReload(reader);

  // Expanded or not is remembered per person: a new pin finds the strip open, and so does a reload.
  await messageNamed(author.page, "Keep this runbook step within reach.").getByRole("button", { name: /^Operations for message by / }).click();
  await author.page.getByRole("menuitem", { name: "Pin", exact: true }).click();
  await expect(strip.getByRole("button", { name: "Pinned in this thread (1)", exact: true })).toHaveAttribute("aria-expanded", "true");
  await reader.page.reload();
  await expect(pinStrip(reader.page).getByRole("button", { name: "Pinned in this thread (1)", exact: true })).toHaveAttribute("aria-expanded", "true");
});

test("a member removed from a room loses its notifications", async ({ page, browser }) => {
  const room = await scene(page, { addMate: false });
  const title = uniqueText("Notice thread");
  const number = await startThread(room, title);
  await addMember(room); // after the thread opened: the room was not told of a thread the teammate was not there for
  await postMessage(room, number, `<@${room.mate.id}> please look at this.`);
  const leaver = await signedIn(browser, room.mate);
  await openLive(leaver, "/", room.roomId);
  await expect(bell(leaver.page)).toHaveAccessibleName(unseenBell(1));
  await bell(leaver.page).click();
  await expect(drawer(leaver.page).getByRole("link", { name: new RegExp(`${room.owner.name} mentioned you in #${number} ${title}`) })).toBeVisible();
  await leaver.page.keyboard.press("Escape");
  await expect(drawer(leaver.page)).toHaveCount(0);

  // The owner removes the teammate (through the API: the Members page is the live spec's subject): the room leaves the rail, the notice goes with it.
  const removed = await room.api.delete(`/api/v1/rooms/${room.roomId}/members/${room.mate.id}`);
  expect(removed.status(), await removed.text()).toBe(204);
  await expect(rail(leaver.page).getByRole("link", { name: room.roomName })).toHaveCount(0);
  await expect(bell(leaver.page)).toHaveAccessibleName(unseenBell(0));
  await expectNoReload(leaver);
  await bell(leaver.page).click();
  await expect(drawer(leaver.page).getByText("You have no notifications.")).toBeVisible();
});

test("browser notification opt-in registers the current browser and can be reversed", async ({ page }) => {
  const room = await scene(page);
  // Full Chromium supports native notifications in headless mode, unlike its minimal headless shell on this platform.
  const browser = await chromium.launch({ channel: "chromium" });
  isolatedBrowsers.push(browser);
  const path = "/api/v1/notifications/push/subscriptions";
  const endpoint = "https://fcm.googleapis.com/fcm/send/adder-test-browser";
  let registered = false;
  const reader = await signedIn(browser, room.mate, async (context) => {
    await context.grantPermissions(["notifications"], { origin: new URL(BASE_URL).origin });
    await context.addInitScript((browserEndpoint) => {
      const key = "e2e.push-subscription";
      const subscription = {
        endpoint: browserEndpoint,
        options: { applicationServerKey: Uint8Array.of(4).buffer, userVisibleOnly: true },
        unsubscribe: () => {
          localStorage.removeItem(key);
          return Promise.resolve(true);
        },
      };
      PushManager.prototype.subscribe = () => {
        localStorage.setItem(key, "enabled");
        return Promise.resolve(subscription as PushSubscription);
      };
      PushManager.prototype.getSubscription = () => Promise.resolve(localStorage.getItem(key) ? subscription as PushSubscription : null);
    }, endpoint);
    await context.route("**/api/v1/notifications/push/config*", (route) => {
      const id = new URL(route.request().url()).searchParams.get("subscriptionId");
      return route.fulfill({ json: { available: true, publicKey: "BA", ...(id === null ? {} : { registered: registered && id === "99" }) } });
    });
    await context.route(`**${path}`, (route) => {
      registered = true;
      return route.fulfill({ status: 200, json: { id: 99 } });
    });
    await context.route(`**${path}/99`, (route) => {
      registered = false;
      return route.fulfill({ status: 204 });
    });
  });

  const cdp = await reader.context.newCDPSession(reader.page);
  const scope = new URL("/", BASE_URL);
  const { targetInfo } = await cdp.send("Target.getTargetInfo");
  await cdp.send("Browser.setPermission", {
    origin: scope.origin,
    browserContextId: targetInfo.browserContextId,
    permission: { name: "notifications" },
    setting: "granted",
  });
  await reader.page.goto("/notification-preferences");
  const enable = reader.page.getByRole("button", { name: "Enable on this browser" });
  const [request] = await Promise.all([
    reader.page.waitForRequest((outgoing) => outgoing.method() === "POST" && outgoing.url().endsWith(path), { timeout: 10_000 }),
    enable.click(),
  ]);
  expect(request.postDataJSON()).toEqual({ endpoint });
  await expect(reader.page.evaluate(async () => (await navigator.serviceWorker.ready).active?.scriptURL)).resolves.toContain("/adder-push.js");

  let registrationId: string | undefined;
  cdp.on("ServiceWorker.workerRegistrationUpdated", ({ registrations }) => {
    registrationId = registrations.find((registration) => registration.scopeURL === scope.href && !registration.isDeleted)?.registrationId;
  });
  await cdp.send("ServiceWorker.enable");
  await expect.poll(() => registrationId).toBeDefined();
  if (!registrationId) throw new Error("Expected Adder's push worker to be registered");
  await cdp.send("ServiceWorker.deliverPushMessage", { origin: scope.origin, registrationId, data: "" });
  await expect.poll(() => reader.page.evaluate(async () => {
    const worker = await navigator.serviceWorker.ready;
    return (await worker.getNotifications({ tag: "adder-notifications" })).map(({ title, body }) => ({ title, body }));
  })).toEqual([{ title: "Adder", body: "You have new notifications" }]);
  await reader.page.reload();
  const disable = reader.page.getByRole("button", { name: "Disable on this browser" });
  await Promise.all([
    reader.page.waitForResponse((response) => response.request().method() === "DELETE" && response.url().endsWith(`${path}/99`)),
    disable.click(),
  ]);
  await expect(enable).toBeVisible();
  await expect.poll(() => reader.page.evaluate(async () => {
    const registration = await navigator.serviceWorker.ready;
    return registration.pushManager.getSubscription();
  })).toBeNull();
  await cdp.detach();

  await reader.page.goto("/?notifications=1");
  await expect(drawer(reader.page)).toBeVisible();
});

test("the Notification preferences page saves a switch and keeps it after a reload", async ({ page }) => {
  await admin.login(page);
  const user = await throwawayUser(page, "E2E Soc Prefs", cleanup);
  await login(page, user.email, user.password);
  await page.goto("/notification-preferences");
  await expect(page.getByRole("heading", { level: 2, name: "Notification preferences" })).toBeVisible();
  const mentionsInApp = page.getByRole("switch", { name: "Someone mentions me: In-app", exact: true });
  const mentionsEmail = page.getByRole("switch", { name: "Someone mentions me: Email", exact: true });
  const handoversEmail = page.getByRole("switch", { name: "Someone makes me the owner of a thread: Email", exact: true });
  const save = page.getByRole("button", { name: "Save", exact: true });

  // A fresh account hears everything in the app and is emailed about nothing; nothing is saveable until a switch moves.
  await expect(mentionsInApp).toBeChecked();
  await expect(mentionsEmail).not.toBeChecked();
  await expect(handoversEmail).not.toBeChecked();
  await expect(save).toBeDisabled();

  // Switching mentions' email on enables Save; the save answers 204 and the page keeps the choice, with the other switches untouched.
  await mentionsEmail.setChecked(true);
  await expect(save).toBeEnabled();
  await Promise.all([
    page.waitForResponse((r) => r.request().method() === "PUT" && PREFERENCES_API.test(r.url()) && r.status() === 204),
    save.click(),
  ]);
  await expect(page.getByText("Notification preferences saved")).toBeVisible();
  await expect(save).toBeDisabled();

  // The server holds it: a reload shows mentions' email on and everything else as it was.
  await page.reload();
  await expect(page.getByRole("heading", { level: 2, name: "Notification preferences" })).toBeVisible();
  await expect(mentionsEmail).toBeChecked();
  await expect(mentionsInApp).toBeChecked();
  await expect(handoversEmail).not.toBeChecked();
});

test("a kind switched off in the app stops arriving", async ({ page, browser }) => {
  const room = await scene(page, { addMate: false });
  const title = uniqueText("Quiet thread");
  await startThread(room, title);
  await addMember(room); // after the thread opened: the room was not told of a thread the teammate was not there for
  const reader = await signedIn(browser, room.mate);
  await openLive(reader, `/rooms/${room.roomId}`, room.roomId);
  await expect(bell(reader.page)).toHaveAccessibleName(unseenBell(0));

  // The teammate switches "Someone mentions me" off in the app and saves, with the live channel up on the preferences page.
  await openLive(reader, "/notification-preferences", room.roomId);
  const mentionsInApp = reader.page.getByRole("switch", { name: "Someone mentions me: In-app", exact: true });
  await mentionsInApp.setChecked(false);
  await Promise.all([
    reader.page.waitForResponse((r) => r.request().method() === "PUT" && PREFERENCES_API.test(r.url()) && r.status() === 204),
    reader.page.getByRole("button", { name: "Save", exact: true }).click(),
  ]);
  await expect(reader.page.getByText("Notification preferences saved")).toBeVisible();

  // The owner mentions the teammate, THEN hands the thread over: the notices are made in that order, so once the hand-over has arrived the
  // mention would have too. The bell holds the hand-over alone.
  await postMessage(room, 1, `Are you there <@${room.mate.id}>?`);
  const handOver = await room.api.put(`/api/v1/rooms/${room.roomId}/threads/1/owner`, { data: { ownerId: room.mate.id } });
  expect(handOver.status(), await handOver.text()).toBe(204);
  await expect(bell(reader.page)).toHaveAccessibleName(unseenBell(1));
  await expectNoReload(reader);
  await bell(reader.page).click();
  await expect(drawer(reader.page).getByRole("link", { name: new RegExp(`${room.owner.name} made you the owner of #1 ${title}`) })).toBeVisible();
  await expect(drawer(reader.page).getByRole("link", { name: /mentioned you/ })).toHaveCount(0);
});


test("a watcher hears a reply", async ({ page, browser }) => {
  // The teammate joins AFTER the thread opened: the room was not told of a thread they were not there for, so their bell starts empty.
  const room = await scene(page, { addMate: false });
  const title = uniqueText("Watched thread");
  await startThread(room, title);
  await addMember(room);
  const reader = await signedIn(browser, room.mate);

  // The teammate watches the thread, then goes back to the room's list: they are no longer looking at it.
  await reader.page.goto(`/rooms/${room.roomId}/threads/1`);
  await Promise.all([
    reader.page.waitForResponse((r) => r.request().method() === "PUT" && WATCH_API.test(r.url()) && r.status() === 204),
    reader.page.getByRole("button", { name: "Watch this thread", exact: true }).click(),
  ]);
  await openLive(reader, `/rooms/${room.roomId}`, room.roomId);
  await expect(bell(reader.page)).toHaveAccessibleName(unseenBell(0));

  // The owner replies: the watcher's bell counts it without a reload and the drawer says who replied.
  await postMessage(room, 1, "The replica is lagging.");
  await expect(bell(reader.page)).toHaveAccessibleName(unseenBell(1));
  await expectNoReload(reader);
  await bell(reader.page).click();
  const notice = drawer(reader.page).getByRole("link", { name: new RegExp(`${room.owner.name} replied in #1 ${title}`) });
  await expect(notice).toBeVisible();

  // Following it opens the thread at that message, and reading it clears the badge.
  await notice.click();
  await expect(reader.page).toHaveURL(new RegExp(`/rooms/${room.roomId}/threads/1`));
  await expect(messageNamed(reader.page, "The replica is lagging.")).toBeVisible();
  await expect(bell(reader.page)).toHaveAccessibleName(unseenBell(0));

  // While the reader looks at the thread (at its end, the tab visible) a further reply never raises the badge, not even for a moment: the
  // pane acknowledges the notice through the read marker. Every label the bell takes from here on is recorded.
  await reader.page.evaluate(() => {
    const labels: string[] = [];
    Object.assign(window, { e2eBellLabels: labels });
    const button = [...document.querySelectorAll("button")].find((candidate) => /^Notifications \(/.test(candidate.getAttribute("aria-label") ?? ""));
    if (!button) throw new Error("the bell is not on the page");
    new MutationObserver(() => labels.push(button.getAttribute("aria-label") ?? "")).observe(button, { attributes: true, attributeFilter: ["aria-label"] });
  });
  const [marker] = await Promise.all([
    reader.page.waitForResponse((r) => r.request().method() === "PUT" && READ_MARKER_API.test(r.url()) && r.status() === 204),
    postMessage(room, 1, "Still lagging."),
  ]);
  expect(marker.ok()).toBe(true);
  await expect(messageNamed(reader.page, "Still lagging.")).toBeVisible();
  await expect(bell(reader.page)).toHaveAccessibleName(unseenBell(0));
  // A beat for any re-read the hint could still cause, then the recorded labels: none of them counted an unseen notice.
  await reader.page.waitForTimeout(1_500);
  const labels = await reader.page.evaluate(() => (window as unknown as { e2eBellLabels: string[] }).e2eBellLabels);
  expect(labels.filter((label) => !unseenBell(0).test(label)), "the badge never rose").toEqual([]);
  await expectNoReload(reader);
  // The pane's read marker is what kept it down, so the SERVER holds the notice as seen: nothing is waiting for the next re-read.
  const { api } = await apiAsUser(room.mate.email, room.mate.password);
  apis.push(api);
  await expect
    .poll(async () => ((await (await api.get("/api/v1/notifications?seen=false")).json()) as { total: number }).total, { message: "the server marked the notice seen" })
    .toBe(0);
});

test("a watched thread groups plain replies and keeps an addressed quote separate", async ({ page, browser }) => {
  const room = await scene(page, { addMate: false });
  const number = await startThread(room, uniqueText("Grouped checkout replies"));
  await addMember(room);
  const readerApi = (await apiAsUser(room.mate.email, room.mate.password)).api;
  apis.push(readerApi);
  const threadPath = `/api/v1/rooms/${room.roomId}/threads/${number}`;
  expect((await readerApi.put(`${threadPath}/watch`, { data: { watching: true } })).status()).toBe(204);
  const original = await readerApi.post(`${threadPath}/messages`, { data: { body: "My checkout observation" } });
  expect(original.status()).toBe(201);
  const { id: quotedId } = (await original.json()) as { id: number };
  const reader = await signedIn(browser, room.mate);
  await expect.poll(() => reader.frames.some((frame) => frame.type === "ready")).toBe(true);

  const oldest = await postMessage(room, number, "First checkout follow-up");
  await postMessage(room, number, "Second checkout follow-up");
  await postMessage(room, number, "This answers your checkout observation", quotedId);

  await expect(bell(reader.page)).toHaveAccessibleName(unseenBell(2));
  await bell(reader.page).click();
  const inbox = drawer(reader.page);
  const group = inbox.getByRole("link", { name: /2 new replies in #1/ });
  await expect(group).toBeVisible();
  await expect(inbox.getByRole("link", { name: /quoted your message/ })).toBeVisible();
  const seen = reader.page.waitForResponse((response) => response.request().method() === "POST" && /reply-groups\/.+\/seen$/.test(response.url()));
  await group.click();
  expect((await seen).status()).toBe(204);
  await expect(reader.page).toHaveURL(new RegExp(`/threads/${number}\\?.*message=${oldest}`));
  await expect(messageNamed(reader.page, "First checkout follow-up")).toBeFocused();
});

test("muting a thread silences its mentions and keeps the watch", async ({ page, browser }) => {
  const room = await scene(page, { addMate: false });
  await startThread(room, uniqueText("Muted checkout"));
  await addMember(room);
  const reader = await signedIn(browser, room.mate);
  const thread = `/rooms/${room.roomId}/threads/1`;
  await reader.page.goto(thread);
  const watch = reader.page.getByRole("button", { name: "Watch this thread", exact: true });
  const mute = reader.page.getByRole("button", { name: "Mute thread notifications", exact: true });
  await watch.click();
  await expect(watch).toHaveAttribute("aria-pressed", "true");
  await mute.click();
  await expect(mute).toHaveAttribute("aria-pressed", "true");
  await openLive(reader, `/rooms/${room.roomId}`, room.roomId);
  await postMessage(room, 1, `<@${room.mate.id}> quiet update`);

  const { api } = await apiAsUser(room.mate.email, room.mate.password);
  apis.push(api);
  const feed = await api.get("/api/v1/notifications/feed?seen=false");
  expect(feed.ok()).toBe(true);
  expect(((await feed.json()) as { total: number }).total).toBe(0);

  await reader.page.goto(thread);
  await expect(watch).toHaveAttribute("aria-pressed", "true");
  await expect(mute).toHaveAttribute("aria-pressed", "true");
  await mute.click();
  await expect(mute).toHaveAttribute("aria-pressed", "false");
  await openLive(reader, `/rooms/${room.roomId}`, room.roomId);
  await postMessage(room, 1, "A new reply after unmuting");
  await expect(bell(reader.page)).toHaveAccessibleName(unseenBell(1));
});

test("everyone in the room hears about a new thread", async ({ page, browser }) => {
  const room = await scene(page);
  const author = await signedIn(browser, room.owner);
  const reader = await signedIn(browser, room.mate);
  await openLive(author, `/rooms/${room.roomId}`, room.roomId);
  await openLive(reader, `/rooms/${room.roomId}`, room.roomId);
  await expect(bell(reader.page)).toHaveAccessibleName(unseenBell(0));

  // The owner starts a thread; the teammate, who watches nothing yet, is told live because they are a member of the room.
  const title = uniqueText("Brand new thread");
  await startThread(room, title);
  await expect(bell(reader.page)).toHaveAccessibleName(unseenBell(1));
  await expectNoReload(reader);
  await bell(reader.page).click();
  await expect(drawer(reader.page).getByRole("link", { name: new RegExp(`${room.owner.name} started #1 ${title}`) })).toBeVisible();

  // The author is never told of their own thread.
  await expect(bell(author.page)).toHaveAccessibleName(unseenBell(0));
});

test("a team mention reaches the team's members in the room", async ({ page, browser }) => {
  // The teammate joins AFTER the thread opened (the room is not told of a thread they were not there for); the outsider never joins the room.
  const room = await scene(page, { addMate: false });
  const outsider = await throwawayUser(page, "E2E Soc Outsider", cleanup);
  const title = uniqueText("Team thread");
  await startThread(room, title);
  await addMember(room);

  // The administrator gives a team a mention handle and puts the teammate AND the outsider on its roster, through the API.
  const { api: adminApi } = await apiAsAdmin();
  apis.push(adminApi);
  const handle = uniqueText("e2e-soc");
  const team = await adminApi.post("/api/v1/teams", { data: { name: handle, handle } });
  expect(team.status(), await team.text()).toBe(201);
  const teamId = ((await team.json()) as { id: number }).id;
  cleanup.unshift(`/api/v1/teams/${teamId}`);
  for (const member of [room.mate, outsider]) {
    const added = await adminApi.put(`/api/v1/teams/${teamId}/members/${member.id}`);
    expect(added.status(), await added.text()).toBe(204);
  }

  const author = await signedIn(browser, room.owner);
  const reader = await signedIn(browser, room.mate);
  await openLive(author, `/rooms/${room.roomId}/threads/1`, room.roomId);
  await openLive(reader, `/rooms/${room.roomId}`, room.roomId);
  await expect(bell(reader.page)).toHaveAccessibleName(unseenBell(0));

  // The author types an @ and the team's handle: the list offers the team under "Teams", and picking it turns the word into @handle.
  const box = author.page.getByRole("textbox", { name: "Reply", exact: true });
  await box.pressSequentially(`Paging @${handle}`);
  const option = author.page.getByRole("option", { name: new RegExp(`@${handle}`) });
  await expect(author.page.getByRole("group", { name: "Teams", exact: true })).toContainText(`@${handle}`);
  await option.click();
  await expect(box).toHaveValue(new RegExp(`@${handle}`));
  await box.pressSequentially("the replica is lagging.");
  const [posted] = await Promise.all([
    author.page.waitForResponse((r) => r.request().method() === "POST" && MESSAGES_API.test(r.url()) && r.status() === 201),
    box.press("Control+Enter"),
  ]);
  const message = (await posted.json()) as { id: number; groupMentions: { kind: string; teamHandle: string | null }[] };
  expect(message.groupMentions.map((group) => `${group.kind}:${group.teamHandle}`)).toEqual([`TEAM:${handle}`]);

  // The teammate (on the team and in the room) is told with no action; the drawer words it.
  await expect(bell(reader.page)).toHaveAccessibleName(unseenBell(1));
  await expectNoReload(reader);
  await bell(reader.page).click();
  await expect(drawer(reader.page).getByRole("link", { name: new RegExp(`${room.owner.name} mentioned your team in #1 ${title}`) })).toBeVisible();

  // The message shows the handle (not the token) to everyone who reads it.
  await expect(messageNamed(author.page, "the replica is lagging.")).toContainText(`@${handle}`);

  // The outsider is on the roster but not in the room: never told (and the room is none of their business).
  const { api: outsiderApi } = await apiAsUser(outsider.email, outsider.password);
  apis.push(outsiderApi);
  const inbox = await outsiderApi.get("/api/v1/notifications");
  expect(((await inbox.json()) as { total: number }).total, "a team member outside the room is never told").toBe(0);
});

test("everyone is told once, with the most specific reason", async ({ page, browser }) => {
  const room = await scene(page, { addMate: false });
  const title = uniqueText("Everyone thread");
  await startThread(room, title);
  await addMember(room);
  const author = await signedIn(browser, room.owner);
  const reader = await signedIn(browser, room.mate);
  await openLive(author, `/rooms/${room.roomId}/threads/1`, room.roomId);
  await openLive(reader, `/rooms/${room.roomId}`, room.roomId);
  await expect(bell(reader.page)).toHaveAccessibleName(unseenBell(0));

  // The author names the teammate AND everyone in one message: the composer says how many people that reaches, and does not ask.
  const box = author.page.getByRole("textbox", { name: "Reply", exact: true });
  await box.pressSequentially(`@${uniquePart(room.mate)}`);
  await author.page.getByRole("option", { name: room.mate.name }).click();
  await box.pressSequentially("and @eve");
  await author.page.getByRole("option", { name: /@everyone/ }).click();
  await expect(box).toHaveValue(new RegExp(`@${room.mate.name} and @everyone`));
  await expect(author.page.getByText("@everyone notifies the other member of this room.")).toBeVisible();
  const [posted] = await Promise.all([
    author.page.waitForResponse((r) => r.request().method() === "POST" && MESSAGES_API.test(r.url()) && r.status() === 201),
    box.press("Control+Enter"),
  ]);
  const messageId = ((await posted.json()) as { id: number }).id;

  // The teammate has ONE notice, worded as the mention (the most specific reason), not one for the mention and one for everyone.
  await expect(bell(reader.page)).toHaveAccessibleName(unseenBell(1));
  await expectNoReload(reader);
  await bell(reader.page).click();
  const notice = drawer(reader.page).getByRole("link", { name: new RegExp(`${room.owner.name} mentioned you in #1 ${title}`) });
  await expect(notice).toBeVisible();
  await expect(drawer(reader.page).getByRole("link", { name: /mentioned everyone/ })).toHaveCount(0);
  await notice.click();
  await expect(reader.page).toHaveURL(new RegExp(`message=${messageId}`));
  await expect(messageNamed(reader.page, "@everyone")).toContainText(`@${room.mate.name}`);

  // A second message that names only everyone is worded as such for the same teammate (who has left the thread for the room's list: a
  // reader looking at the thread would have it acknowledged by the pane); the author is never told of their own.
  await openLive(reader, `/rooms/${room.roomId}`, room.roomId);
  await expect(bell(reader.page)).toHaveAccessibleName(unseenBell(0));
  await postMessage(room, 1, "Standup in five <@everyone>");
  await expect(bell(reader.page)).toHaveAccessibleName(unseenBell(1));
  await bell(reader.page).click();
  await expect(drawer(reader.page).getByRole("link", { name: new RegExp(`${room.owner.name} mentioned everyone in #1 ${title}`) })).toBeVisible();
  await expect(bell(author.page)).toHaveAccessibleName(unseenBell(0));
});

/** The person's own page, a second TAB of the same browser context (one `localStorage`, one `BroadcastChannel` scope), signed in already. */
async function secondTab(seat: Seat): Promise<Page> {
  const tab = await seat.context.newPage();
  await tab.goto("/");
  await expect(bell(tab)).toBeVisible();
  return tab;
}

/** The unseen count a title leads with: "(2) Room · Adder" (the bell's number, `utils/titleCount.ts`); a title with no number has no parenthesis. */
const titled = (count: number) => new RegExp(`^\\(${count}\\) `);
const untitled = /^[^(]/;

test("the tab title counts unseen notifications and a second tab follows", async ({ page, browser }) => {
  const room = await scene(page, { addMate: false });
  const title = uniqueText("Title thread");
  await startThread(room, title);
  await addMember(room);
  const reader = await signedIn(browser, room.mate);
  await openLive(reader, `/rooms/${room.roomId}`, room.roomId);
  const other = await secondTab(reader);
  await expect(reader.page).toHaveTitle(untitled);
  await expect(other).toHaveTitle(untitled);

  // A mention reaches the bell of both tabs and each tab's title leads with the same number as its bell.
  await postMessage(room, 1, `<@${room.mate.id}> look at this.`);
  await expect(bell(reader.page)).toHaveAccessibleName(unseenBell(1));
  await expect(reader.page).toHaveTitle(titled(1));
  await expect(bell(other)).toHaveAccessibleName(unseenBell(1));
  await expect(other).toHaveTitle(titled(1));
  await expect(other).toHaveTitle(/Adder$/);

  // Marking the notice seen in the second tab tells the first one (the write emits no live hint): its bell and title drop to none, with no reload.
  await bell(other).click();
  await other.getByRole("dialog", { name: "Notifications", exact: true }).getByRole("button", { name: "Mark all as seen" }).click();
  await expect(bell(other)).toHaveAccessibleName(unseenBell(0));
  await expect(other).toHaveTitle(untitled);
  await expect(bell(reader.page)).toHaveAccessibleName(unseenBell(0));
  await expect(reader.page).toHaveTitle(untitled);
  await expectNoReload(reader);
});

test("a hidden tab goes quiet and keeps counting, and closes after eight hours", async ({ page, browser }) => {
  const room = await scene(page, { addMate: false });
  const title = uniqueText("Quiet thread");
  await startThread(room, title);
  await addMember(room);
  // The page's clock is Playwright's (installed before anything loads): the 5-minute and the 8-hour timers fire on `fastForward`, not on a wait.
  // The tab is "hidden" the way a browser reports it: `document.visibilityState` and a `visibilitychange` (a headless page is never really hidden).
  const sent: Frame[] = [];
  // Whether the page's socket is open (the one page load of this test opens exactly one).
  let socketOpen = false;
  let newest: unknown = null;
  const reader = await signedIn(
    browser,
    room.mate,
    async (context) => {
      await context.clock.install();
      context.on("page", (opened) =>
        opened.on("websocket", (socket) => {
          socketOpen = true;
          newest = socket;
          socket.on("framesent", ({ payload }) => sent.push(JSON.parse(String(payload)) as Frame));
          socket.on("close", () => {
            if (newest === socket) socketOpen = false;
          });
        }),
      );
    },
    // The room's page is the ONE page load of this browser (a sign-in that lands elsewhere first would leave a second socket to tell apart from this one).
    `/rooms/${room.roomId}`,
  );
  await expect.poll(() => reader.frames.some((frame) => frame.type === "subscribed" && frame.roomIds?.includes(room.roomId)), { message: "the socket subscribed to the room" }).toBe(true);
  const setHidden = (hidden: boolean) =>
    reader.page.evaluate((state) => {
      Object.defineProperty(document, "visibilityState", { configurable: true, get: () => state });
      document.dispatchEvent(new Event("visibilitychange"));
    }, hidden ? "hidden" : "visible");

  // Five minutes hidden: the tab drops its room but keeps its socket; the indicator says nothing (a deliberate quiet is not a reconnect).
  await setHidden(true);
  await reader.page.clock.fastForward("05:00");
  await expect.poll(() => sent.some((frame) => frame.type === "unsubscribe" && frame.roomIds?.includes(room.roomId)), { message: "the tab left its room" }).toBe(true);
  expect(socketOpen, "the socket stayed open").toBe(true);
  await expect(reader.page.getByRole("banner").getByRole("status")).toBeEmpty();

  // Quiet, it still hears a notice meant for the person: the bell and the title count it with no room subscription. A quiet tab re-reads the bell at most
  // once a minute, and the jump above may have flushed the page load's own re-read just now: let that minute pass (fake time) before the notice arrives.
  await reader.page.clock.fastForward("02:00");
  await postMessage(room, 1, `<@${room.mate.id}> are you there?`);
  await expect(bell(reader.page)).toHaveAccessibleName(unseenBell(1));
  await expect(reader.page).toHaveTitle(titled(1));

  // Shown again it subscribes back and re-reads (the backfill).
  sent.length = 0;
  await setHidden(false);
  await expect.poll(() => sent.some((frame) => frame.type === "subscribe" && frame.roomIds?.includes(room.roomId)), { message: "the tab subscribed back" }).toBe(true);
  await expect(reader.page).toHaveTitle(titled(1));

  // Eight hours hidden: the socket closes (the session may idle out) and the title never shows a number it cannot keep current.
  await setHidden(true);
  await reader.page.clock.fastForward("08:00:00");
  await expect.poll(() => socketOpen, { message: "the socket was closed" }).toBe(false);
  await expect(reader.page).toHaveTitle(untitled);

  // Shown, it reconnects and counts again.
  await setHidden(false);
  await expect(reader.page).toHaveTitle(titled(1));
  await expect(bell(reader.page)).toHaveAccessibleName(unseenBell(1));
  await expect.poll(() => socketOpen, { message: "a socket is open again" }).toBe(true);
});

test("a quote reaches the quoted author's bell without a reload and opens the reply", async ({ page, browser }) => {
  // The teammate joins AFTER the thread opened (so their bell starts empty) and asks a question; the owner then answers by quoting it. The quote is made
  // through the API: the composer's "Quote" is the threads spec's subject, this one is about who is told.
  const room = await scene(page, { addMate: false });
  const title = uniqueText("Quoted thread");
  await startThread(room, title);
  await addMember(room);
  const { api: mateApi } = await apiAsUser(room.mate.email, room.mate.password);
  apis.push(mateApi);
  const asked = await mateApi.post(`/api/v1/rooms/${room.roomId}/threads/1/messages`, { data: { body: "Can anyone confirm the cause?" } });
  expect(asked.status(), await asked.text()).toBe(201);
  const questionId = ((await asked.json()) as { id: number }).id;
  const reader = await signedIn(browser, room.mate);
  // The teammate is in the room but not in the thread: a notice stays unseen until its message is read.
  await openLive(reader, `/rooms/${room.roomId}`, room.roomId);
  await expect(bell(reader.page)).toHaveAccessibleName(unseenBell(0));

  // The owner quotes the question: the teammate does not watch the thread and is not mentioned, yet the bell counts it with no action, and the room in
  // the rail says so in words (a quote is ADDRESSED to its author, like a mention).
  const answerId = await postMessage(room, 1, "It was the replica lag.", questionId);
  await expect(bell(reader.page)).toHaveAccessibleName(unseenBell(1));
  await expect(rail(reader.page).getByRole("link", { name: room.roomName })).toHaveAccessibleDescription(/1 unseen notification/);
  await expectNoReload(reader);
  await bell(reader.page).click();
  const notice = drawer(reader.page).getByRole("link", { name: new RegExp(`${room.owner.name} quoted your message in #1 ${title}`) });
  await expect(notice).toBeVisible();
  await expect(notice).toHaveAccessibleName(/^Unseen /);

  // Opening the notice goes to the REPLY (`?message=`), shows it and the badge clears.
  await notice.click();
  await expect(reader.page).toHaveURL(new RegExp(`/rooms/${room.roomId}/threads/1\\?.*message=${answerId}`));
  await expect(drawer(reader.page)).toHaveCount(0);
  await expect(messageNamed(reader.page, "It was the replica lag.")).toBeVisible();
  await expect(bell(reader.page)).toHaveAccessibleName(unseenBell(0));
  await expect(rail(reader.page).getByRole("link", { name: room.roomName })).not.toHaveAccessibleDescription(/unseen notification/);
  await expectNoReload(reader);
});
