// Social (0.6.0): mentions picked in the composer reach the mentioned member's bell live and open the message; the "Mentions me" filter; a
// hand-over notifies the new owner; the rail's and the thread list's unread marks and the conversation's "New" line; a reaction from the emoji
// picker (no content-security-policy violation) seen live by another member, who toggles theirs; the pinned panel and a pin's removal seen live;
// a member removed from a room loses its notifications; the Notification preferences page keeps a saved switch; a kind switched off in the app stops arriving. Two browser contexts wherever live matters, with no reload in the watching one.
// Owns: its throwaway `e2e-soc-*` rooms and users, removed through the API in an `afterEach` (contexts first, then rooms, then the users). Every room
// is created by a throwaway user and only copies the seeded Default template. Threads and messages are never deleted: a room's removal hides them.
// The per-user `live-ticket`, `chat-post`, `chat-write` and `read-state` buckets are only ever spent by throwaway users, far below their limits.
import { type APIRequestContext, type Browser, type BrowserContext, type Page } from "@playwright/test";
import { BASE_URL } from "../playwright.config";
import { apiAsUser, conversation, deleteViaApi, expect, login, rail, specAdmins, test, threadList, throwawayUser, uniqueText } from "./helpers";

/** This file's own administrator (see `specAdmins`): the page loads of its sign-ins spend this account's live-ticket bucket, not the seed administrator's. */
const admin = specAdmins("e2e-social-admin");

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
const OWNER_API = /\/api\/v1\/rooms\/\d+\/threads\/\d+\/owner$/;
const THREAD_PIN_API = /\/api\/v1\/rooms\/\d+\/threads\/\d+\/pin$/;
const MESSAGE_PIN_API = /\/api\/v1\/rooms\/\d+\/messages\/\d+\/pin$/;
const PREFERENCES_API = /\/api\/v1\/users\/\d+\/notification-preferences$/;
const ROOM_SEEN_API =/\/api\/v1\/rooms\/\d+\/seen$/;
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
const pinnedButton = (page: Page) => page.getByRole("button", { name: /^Pinned/ });
const pinnedDrawer = (page: Page) => page.getByRole("dialog", { name: "Pinned in this room", exact: true });

/** The admin (signed in on `page`) mints the owner and the teammate; the owner then makes a room and adds the teammate, all through the API. */
async function scene(page: Page, { addMate = true }: { addMate?: boolean } = {}): Promise<Scene> {
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
  const created = await api.post("/api/v1/rooms", { data: { name: roomName, templateId: template!.id } });
  expect(created.status(), await created.text()).toBe(201);
  const roomId = ((await created.json()) as { id: number }).id;
  cleanup.unshift(`/api/v1/rooms/${roomId}`);
  const room: Scene = { owner, mate, roomId, roomName, api };
  if (addMate) await addMember(room);
  return room;
}

async function addMember(room: Scene): Promise<void> {
  const added = await room.api.post(`/api/v1/rooms/${room.roomId}/members`, { data: { userIds: [room.mate.id] } });
  expect(added.status(), await added.text()).toBe(200);
}

/** Start a thread as the owner through the API; returns its number. */
async function startThread(room: Scene, title: string, body = "Seeded through the API."): Promise<number> {
  const created = await room.api.post(`/api/v1/rooms/${room.roomId}/threads`, { data: { title, body } });
  expect(created.status(), await created.text()).toBe(201);
  return ((await created.json()) as { number: number }).number;
}

/** Post a message as the owner through the API (a `<@id>` token is a mention); returns its id. */
async function postMessage(room: Scene, number: number, body: string): Promise<number> {
  const posted = await room.api.post(`/api/v1/rooms/${room.roomId}/threads/${number}/messages`, { data: { body } });
  expect(posted.status(), await posted.text()).toBe(201);
  return ((await posted.json()) as { id: number }).id;
}

/** A fresh browser context signed in as `who`, recording the frames of every live socket its page opens. */
async function signedIn(browser: Browser, who: Person, before?: (context: BrowserContext) => Promise<void>): Promise<Seat> {
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
  await login(page, who.email, who.password);
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
  await expect(notice).toContainText("Unseen");

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
  if (await toggle.isVisible()) {
    if ((await toggle.getAttribute("aria-expanded")) === "false") await toggle.click();
    await expect(toggle).toHaveAttribute("aria-expanded", "true");
  }
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
