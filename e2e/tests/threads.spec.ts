// Threads and messages: a member starts a thread from the room view and another member replies to it; an author edits and deletes their own
// message and a room owner removes another member's; a member quotes a message in a reply (1.3.0), the quote leads back to it and follows an edit
// and a delete of the quoted message; a member moves a thread between open statuses but cannot close it, its owner closes it
// and the closed thread refuses replies until it is reopened; the owner passes the thread on, the new owner edits its details and the history
// lists every change. Owns: its throwaway `e2e-thr-*` rooms and users, removed through the API in an
// `afterEach` (rooms first, then the users). The seeded Default and Incident templates are shared and read-only: rooms only copy them. Every room is created
// by a throwaway user, so the seed administrator is never a member of one; the per-user `room-name` (30 a minute), `room-members` (60) and
// `chat-post` (120) buckets are far from reached. Threads are never deleted: a room's removal hides its threads with it.
import type { Page } from "@playwright/test";
import { apiAsUser, conversation, createRoomViaUi, deleteViaApi, expect, login, rail, signOut, specAdmins, test, threadList, throwawayUser, uniqueText } from "./helpers";

/** This file's own administrator (see `specAdmins`): the page loads of its sign-ins spend this account's live-ticket bucket, not the seed administrator's. */
const admin = specAdmins("e2e-threads-admin");

// API teardown, so a failing step leaves neither the room nor the user behind (rooms first: they reference users).
const cleanup: string[] = [];
test.afterEach(async () => {
  await deleteViaApi(cleanup.splice(0));
});

const MEMBERS_API = /\/api\/v1\/rooms\/\d+\/members(\/\d+)?$/;
const THREADS_API = /\/api\/v1\/rooms\/\d+\/threads$/;
const MESSAGES_API = /\/api\/v1\/rooms\/\d+\/threads\/\d+\/messages$/;
const MESSAGE_API = /\/api\/v1\/rooms\/\d+\/messages\/\d+$/;
const THREAD_API = /\/api\/v1\/rooms\/\d+\/threads\/\d+$/;
const STATUS_API = /\/api\/v1\/rooms\/\d+\/threads\/\d+\/status$/;
const OWNER_API = /\/api\/v1\/rooms\/\d+\/threads\/\d+\/owner$/;
/** The "edited" marker carries the edit time as visible text. */
const EDITED_MARKER = /^edited \d{4}-\d{2}-\d{2} \d{2}:\d{2}$/;

const messageNamed = (page: Page, text: string) => conversation(page).getByRole("listitem").filter({ hasText: text });
const kebab = (item: ReturnType<typeof messageNamed>) => item.getByRole("button", { name: /^Operations for message by / });

/** The room owner adds one person from the directory on the Members tab; leaves the browser there. */
async function addMemberViaUi(page: Page, roomId: number, person: { name: string; email: string }): Promise<void> {
  await page.goto(`/rooms/${roomId}/members`);
  const picker = page.getByRole("combobox", { name: "Add a person" });
  await picker.click();
  await picker.fill(person.name);
  await page.getByRole("option", { name: `${person.name} (${person.email})` }).click();
  await Promise.all([
    page.waitForResponse((r) => r.request().method() === "POST" && MEMBERS_API.test(r.url()) && r.status() === 200),
    page.getByRole("button", { name: "Add person", exact: true }).click(),
  ]);
  await expect(page.getByRole("table", { name: "Members", exact: true }).getByRole("row").filter({ hasText: person.email })).toBeVisible();
}

/** Start a thread from the room view's "New thread" form; lands on the new thread's pane. */
async function startThreadViaUi(page: Page, roomId: number, title: string, body: string): Promise<void> {
  await page.goto(`/rooms/${roomId}`);
  await page.getByRole("link", { name: "New thread", exact: true }).click();
  await expect(page).toHaveURL(new RegExp(`/rooms/${roomId}/threads/new$`));
  await page.getByRole("textbox", { name: "Title", exact: true }).fill(title);
  await page.getByRole("textbox", { name: "First message", exact: true }).fill(body);
  await Promise.all([
    page.waitForResponse((r) => r.request().method() === "POST" && THREADS_API.test(r.url()) && r.status() === 201),
    page.getByRole("button", { name: "Start thread", exact: true }).click(),
  ]);
  await expect(page).toHaveURL(new RegExp(`/rooms/${roomId}/threads/\\d+$`));
}

/** Post a reply with Ctrl+Enter from the composer (the key is the subject of the first journey; here it is the quickest way). */
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
 * Open a thread from the room's thread list (the labelled `nav`). The list shows OPEN threads by default, so a CLOSED thread is not in it:
 * pass `closed` to open it by its own address instead (every thread here is `#1`).
 */
async function openThread(page: Page, roomId: number, title: string, { closed = false }: { closed?: boolean } = {}): Promise<void> {
  if (closed) {
    await page.goto(`/rooms/${roomId}/threads/1`);
  } else {
    await page.goto(`/rooms/${roomId}`);
    await page.getByRole("navigation", { name: "Threads", exact: true }).getByRole("link", { name: new RegExp(title) }).click();
  }
  await expect(page.getByRole("heading", { level: 3, name: `#1 ${title}` })).toBeVisible();
}

/**
 * Opens the thread's side panel: collapsed at every width until the header's "Details" button opens it (1.2 D4), and the choice is remembered in
 * the browser, so a panel an earlier step left open is already open. The pane is awaited FIRST (the button renders with it), and the panel is
 * awaited settled at the end: its Status section is the first thing drawn once it shows.
 */
async function openDetails(page: Page): Promise<void> {
  await expect(page.getByRole("region", { name: /^#\d+ / })).toBeVisible();
  const toggle = page.getByRole("button", { name: "Details", exact: true });
  if ((await toggle.getAttribute("aria-expanded")) === "false") await toggle.click();
  await expect(toggle).toHaveAttribute("aria-expanded", "true");
  await expect(page.getByRole("heading", { level: 4, name: "Status", exact: true })).toBeVisible();
}

/** The side panel's status select; a plain `getByRole("combobox")` would also match the owner picker. */
const statusSelect = (page: Page) => page.getByRole("combobox", { name: "Thread status", exact: true });
const ownerPicker = (page: Page) => page.getByRole("combobox", { name: "Owner", exact: true });

/** Pick an enabled status from the select; the move is a PUT that answers 204. */
async function moveTo(page: Page, label: string): Promise<void> {
  await statusSelect(page).click();
  await Promise.all([
    page.waitForResponse((r) => r.request().method() === "PUT" && STATUS_API.test(r.url()) && r.status() === 204),
    page.getByRole("option", { name: label, exact: true }).click(),
  ]);
}

test("a member starts a thread from the room view and another member replies to it", async ({ page }) => {
  await admin.login(page);
  const owner = await throwawayUser(page, "E2E Thread Owner", cleanup);
  const mate = await throwawayUser(page, "E2E Thread Mate", cleanup);
  await signOut(page);

  // The owner makes the room, adds the teammate and starts a thread: the first message is the thread's opener, and the pane opens on it.
  await login(page, owner.email, owner.password);
  const roomName = uniqueText("e2e-thr");
  const roomId = await createRoomViaUi(page, roomName, "Default", cleanup);
  await addMemberViaUi(page, roomId, mate);
  const title = uniqueText("Disk full");
  await startThreadViaUi(page, roomId, title, "The **replica** is out of space.");
  await expect(page.getByRole("heading", { level: 3, name: `#1 ${title}` })).toBeVisible();
  await expect(page).toHaveURL(new RegExp(`/rooms/${roomId}/threads/1$`));
  const opener = messageNamed(page, "is out of space");
  await expect(opener).toHaveCount(1);
  await expect(opener).toContainText(owner.name);
  await expect(opener.locator("strong")).toHaveText("replica");
  await expect(page.getByRole("region", { name: `#1 ${title}` })).toContainText("1 message");
  await expect(page.getByRole("navigation", { name: "Threads", exact: true }).getByRole("link", { name: new RegExp(title) })).toBeVisible();
  await signOut(page);

  // The teammate finds the room in their rail, opens the thread from the list and replies: Enter adds a line, Ctrl+Enter sends.
  await login(page, mate.email, mate.password);
  await rail(page).getByRole("link", { name: roomName, exact: true }).click();
  await page.getByRole("navigation", { name: "Threads", exact: true }).getByRole("link", { name: new RegExp(title) }).click();
  await expect(page.getByRole("heading", { level: 3, name: `#1 ${title}` })).toBeVisible();
  await expect(messageNamed(page, "is out of space")).toBeVisible();
  const box = page.getByRole("textbox", { name: "Reply", exact: true });
  await expect(page.getByText("0 / 8000", { exact: true })).toBeVisible();
  let posts = 0;
  page.on("request", (request) => {
    if (request.method() === "POST" && MESSAGES_API.test(request.url())) posts += 1;
  });
  await box.click();
  await page.keyboard.type("Freed the **old logs**.");
  await page.keyboard.press("Enter");
  await page.keyboard.type("Watching the disk.");
  await expect(box).toHaveValue("Freed the **old logs**.\nWatching the disk.");
  expect(posts, "Enter alone does not post").toBe(0);
  await Promise.all([
    page.waitForResponse((r) => r.request().method() === "POST" && MESSAGES_API.test(r.url()) && r.status() === 201),
    page.keyboard.press("Control+Enter"),
  ]);
  await expect(box).toHaveValue("");
  const mine = messageNamed(page, "Watching the disk.");
  await expect(mine).toHaveCount(1);
  await expect(mine).toContainText(mate.name);
  await expect(mine.locator("strong")).toHaveText("old logs");
  await expect(page.getByRole("region", { name: `#1 ${title}` })).toContainText("2 messages");
  // The newest message is the last in the conversation, and the reply stays after a reload.
  await expect(conversation(page).getByRole("listitem").last()).toContainText("Watching the disk.");
  await page.reload();
  await expect(messageNamed(page, "Watching the disk.")).toBeVisible();
  await signOut(page);

  // The owner opens the thread again and sees the teammate's reply under their own opening message.
  await login(page, owner.email, owner.password);
  await openThread(page, roomId, title);
  await expect(conversation(page).getByRole("listitem")).toHaveCount(2);
  await expect(conversation(page).getByRole("listitem").last()).toContainText(mate.name);
  await expect(conversation(page).getByRole("listitem").last()).toContainText("Watching the disk.");
});

test("an author edits and deletes their own message and a room owner removes another member's message", async ({ page }) => {
  await admin.login(page);
  const owner = await throwawayUser(page, "E2E Thread Owner", cleanup);
  const mate = await throwawayUser(page, "E2E Thread Mate", cleanup);
  await signOut(page);

  await login(page, owner.email, owner.password);
  const roomId = await createRoomViaUi(page, uniqueText("e2e-thr"), "Default", cleanup);
  await addMemberViaUi(page, roomId, mate);
  const title = uniqueText("Pager storm");
  await startThreadViaUi(page, roomId, title, "Opening note from the owner.");
  await signOut(page);

  // The teammate posts three messages; their own have the full menu, the owner's opener only Quote (1.3.0) and Pin (any member quotes and pins, 0.6.0) - never Edit, Delete or Remove.
  await login(page, mate.email, mate.password);
  await openThread(page, roomId, title);
  for (const text of ["first draft", "oops wrong thread", "token is hunter2"]) await reply(page, text);
  await kebab(messageNamed(page, "Opening note")).click();
  await expect(page.getByRole("menuitem", { name: "Quote", exact: true })).toBeVisible();
  await expect(page.getByRole("menuitem", { name: "Pin", exact: true })).toBeVisible();
  await expect(page.getByRole("menuitem")).toHaveCount(2);
  await page.keyboard.press("Escape");
  await expect(page.getByRole("menuitem")).toHaveCount(0);
  await expect(kebab(messageNamed(page, "first draft"))).toBeVisible();

  // Edit: the editor takes the place of the body; saving puts the new body back, marked "edited".
  const edited = messageNamed(page, "first draft");
  await kebab(edited).click();
  await page.getByRole("menuitem", { name: "Edit", exact: true }).click();
  const editor = page.getByRole("textbox", { name: "Edit message", exact: true });
  await editor.fill("final draft");
  await Promise.all([
    page.waitForResponse((r) => r.request().method() === "PUT" && MESSAGE_API.test(r.url()) && r.status() === 204),
    page.getByRole("button", { name: "Save", exact: true }).click(),
  ]);
  await expect(editor).toHaveCount(0);
  const final = messageNamed(page, "final draft");
  await expect(final).toHaveCount(1);
  await expect(final.getByText(EDITED_MARKER)).toBeVisible();
  await expect(messageNamed(page, "first draft")).toHaveCount(0);

  // Delete: the confirm asks, and the message becomes a tombstone that still names its author.
  const wrong = messageNamed(page, "oops wrong thread");
  await kebab(wrong).click();
  await page.getByRole("menuitem", { name: "Delete", exact: true }).click();
  const confirm = page.getByRole("dialog", { name: "Delete this message?" });
  await Promise.all([
    page.waitForResponse((r) => r.request().method() === "DELETE" && MESSAGE_API.test(r.url()) && r.status() === 204),
    confirm.getByRole("button", { name: "Delete", exact: true }).click(),
  ]);
  await expect(page.getByRole("dialog")).toHaveCount(0);
  await expect(messageNamed(page, "oops wrong thread")).toHaveCount(0);
  const tombstone = messageNamed(page, "This message was deleted.");
  await expect(tombstone).toHaveCount(1);
  await expect(tombstone).toContainText(mate.name);
  await expect(kebab(tombstone)).toHaveCount(0);
  await signOut(page);

  // The room owner sees the teammate's messages with Remove only (never Edit or Delete), and their own opener with Edit and Delete.
  await login(page, owner.email, owner.password);
  await openThread(page, roomId, title);
  await kebab(messageNamed(page, "Opening note")).click();
  await expect(page.getByRole("menuitem", { name: "Edit", exact: true })).toBeVisible();
  await expect(page.getByRole("menuitem", { name: "Delete", exact: true })).toBeVisible();
  await page.keyboard.press("Escape");
  await expect(page.getByRole("menuitem")).toHaveCount(0);
  await kebab(messageNamed(page, "token is hunter2")).click();
  await expect(page.getByRole("menuitem", { name: "Remove", exact: true })).toBeVisible();
  await expect(page.getByRole("menuitem", { name: "Edit", exact: true })).toHaveCount(0);
  await expect(page.getByRole("menuitem", { name: "Delete", exact: true })).toHaveCount(0);
  await page.getByRole("menuitem", { name: "Remove", exact: true }).click();
  const removeDialog = page.getByRole("dialog", { name: "Remove this message?" });
  await Promise.all([
    page.waitForResponse((r) => r.request().method() === "DELETE" && MESSAGE_API.test(r.url()) && r.status() === 204),
    removeDialog.getByRole("button", { name: "Remove", exact: true }).click(),
  ]);
  await expect(page.getByRole("dialog")).toHaveCount(0);
  await expect(messageNamed(page, "token is hunter2")).toHaveCount(0);
  await expect(messageNamed(page, "Removed by a room owner or an administrator.")).toHaveCount(1);
  // The earlier own-delete tombstone is still the author's own wording.
  await expect(messageNamed(page, "This message was deleted.")).toHaveCount(1);
  await signOut(page);

  // The teammate finds both tombstones, each in its own words, and their edited message intact.
  await login(page, mate.email, mate.password);
  await openThread(page, roomId, title);
  await expect(messageNamed(page, "Removed by a room owner or an administrator.")).toContainText(mate.name);
  await expect(messageNamed(page, "This message was deleted.")).toContainText(mate.name);
  await expect(messageNamed(page, "final draft").getByText(EDITED_MARKER)).toBeVisible();
});

test("a member quotes a message in a reply, the quote leads back to it, and a deleted message reads as deleted in the quote", async ({ page }) => {
  await admin.login(page);
  const owner = await throwawayUser(page, "E2E Thread Owner", cleanup);
  const mate = await throwawayUser(page, "E2E Thread Mate", cleanup);
  await signOut(page);

  await login(page, owner.email, owner.password);
  const roomId = await createRoomViaUi(page, uniqueText("e2e-thr"), "Default", cleanup);
  await addMemberViaUi(page, roomId, mate);
  const title = uniqueText("Replica lag");
  await startThreadViaUi(page, roomId, title, "Is the **replica** caught up with the primary?");
  await signOut(page);

  // The teammate posts two replies, then quotes the owner's opening message with the icon beside its menu: the line above the box names the author
  // and shows a plain excerpt, and the box takes the focus.
  await login(page, mate.email, mate.password);
  await openThread(page, roomId, title);
  for (const text of ["Checking the lag now.", "Lag is under a second."]) await reply(page, text);
  const box = page.getByRole("textbox", { name: "Reply", exact: true });
  const opener = conversation(page).getByRole("listitem").first();
  await expect(opener).toContainText("Is the replica caught up with the primary?");
  // The icon button is named with its message, like the menu; its tooltip says "Quote".
  await opener.getByRole("button", { name: `Quote the message by ${owner.name}` }).click();
  const chip = page.locator("p", { hasText: `Quoting ${owner.name}` });
  await expect(chip).toBeVisible();
  await expect(chip.locator("xpath=..")).toContainText("Is the replica caught up with the primary?");
  await expect(box).toBeFocused();

  // Send carries the quoted message's id; the line and the text go together, and the reply wears the quote above its text.
  await box.fill("Yes, caught up.");
  const [posted] = await Promise.all([
    page.waitForResponse((r) => r.request().method() === "POST" && MESSAGES_API.test(r.url()) && r.status() === 201),
    box.press("Control+Enter"),
  ]);
  const quote = ((await posted.json()) as { quote: { messageId: number } | null }).quote;
  expect(quote, "the 201 carries the quote").not.toBeNull();
  const openerId = quote!.messageId;
  expect(posted.request().postDataJSON()).toEqual({ body: "Yes, caught up.", quotedMessageId: openerId });
  await expect(box).toHaveValue("");
  await expect(chip).toHaveCount(0);
  const answer = messageNamed(page, "Yes, caught up.");
  await expect(answer).toHaveCount(1);
  const block = answer.getByRole("link", { name: `Quoted message by ${owner.name}` });
  await expect(block).toContainText(owner.name);
  await expect(block).toContainText("Is the replica caught up with the primary?");
  // Plain text: the quoted message's bold run is not bold in the quote.
  await expect(block.locator("strong")).toHaveCount(0);
  await page.reload();
  await expect(block).toContainText("Is the replica caught up with the primary?");

  // The menu's first item is Quote too; the line's button removes it and leaves the focus in the box.
  await kebab(messageNamed(page, "Lag is under a second.")).click();
  await expect(page.getByRole("menuitem").first()).toHaveText("Quote");
  await page.getByRole("menuitem", { name: "Quote", exact: true }).click();
  await expect(page.locator("p", { hasText: `Quoting ${mate.name}` })).toBeVisible();
  await expect(box).toBeFocused();
  await page.getByRole("button", { name: "Remove the quote", exact: true }).click();
  await expect(page.locator("p", { hasText: "Quoting " })).toHaveCount(0);
  await expect(box).toBeFocused();

  // A click on the quote leads back to the quoted message: in the address, focused, highlighted and announced.
  await block.click();
  await expect(page).toHaveURL(new RegExp(`/rooms/${roomId}/threads/1\\?.*message=${openerId}`));
  await expect(opener).toBeFocused();
  await expect(opener).toHaveAttribute("data-anchor", "true");
  await expect(page.getByText(`Found the message by ${owner.name}.`)).toBeAttached();

  // The quote shows the quoted message as it reads NOW: an edit by its author is marked in words and shows the new text; a delete says so and
  // shows no text, and the quote stays a link to the tombstone.
  const { api: ownerApi } = await apiAsUser(owner.email, owner.password);
  try {
    const edit = await ownerApi.put(`/api/v1/rooms/${roomId}/messages/${openerId}`, { data: { body: "Replica lag is zero." } });
    expect(edit.status(), await edit.text()).toBe(204);
    await openThread(page, roomId, title);
    const edited = messageNamed(page, "Yes, caught up.").getByRole("link", { name: `Quoted message by ${owner.name} (edited)` });
    await expect(edited).toContainText("(edited)");
    await expect(edited).toContainText("Replica lag is zero.");
    await expect(edited).not.toContainText("caught up with the primary");

    const removed = await ownerApi.delete(`/api/v1/rooms/${roomId}/messages/${openerId}`);
    expect(removed.status(), await removed.text()).toBe(204);
    await openThread(page, roomId, title);
    const deleted = messageNamed(page, "Yes, caught up.").getByRole("link", { name: `Quoted message by ${owner.name} The quoted message was deleted.` });
    await expect(deleted).toContainText("The quoted message was deleted.");
    await expect(deleted).not.toContainText("Replica lag is zero.");
    await expect(messageNamed(page, "Yes, caught up.")).toContainText("Yes, caught up.");
    await expect(conversation(page).getByText("Replica lag is zero.")).toHaveCount(0);
    await deleted.click();
    await expect(opener).toBeFocused();
    await expect(opener).toContainText("This message was deleted.");
  } finally {
    await ownerApi.dispose();
  }
});

test("a member moves a thread between open statuses but cannot close it, its owner closes it, and the closed thread refuses replies until the room owner reopens it", async ({ page }) => {
  await admin.login(page);
  const owner = await throwawayUser(page, "E2E Thread Owner", cleanup);
  const mate = await throwawayUser(page, "E2E Thread Mate", cleanup);
  await signOut(page);

  await login(page, owner.email, owner.password);
  const roomId = await createRoomViaUi(page, uniqueText("e2e-thr"), "Default", cleanup);
  await addMemberViaUi(page, roomId, mate);
  const title = uniqueText("Latency spike");
  await startThreadViaUi(page, roomId, title, "p99 doubled after the deploy.");
  await signOut(page);

  // The teammate (a plain member, not the thread's owner) moves it between two OPEN statuses; the CLOSED ones are disabled, with the reason.
  await login(page, mate.email, mate.password);
  await openThread(page, roomId, title);
  await openDetails(page);
  const region = page.getByRole("region", { name: `#1 ${title}` });
  await expect(statusSelect(page)).toHaveValue("Open");
  await expect(ownerPicker(page)).toHaveCount(0);
  await moveTo(page, "In progress");
  await expect(statusSelect(page)).toHaveValue("In progress");
  await expect(region).toContainText("In progress");
  await statusSelect(page).click();
  const done = page.getByRole("option", { name: /^Done/ });
  await expect(done).toHaveAttribute("data-combobox-disabled", "true");
  await expect(done).toContainText("Needs the thread owner, a room owner or an administrator");
  await expect(page.getByRole("option", { name: /^Won't do/ })).toHaveAttribute("data-combobox-disabled", "true");
  await page.keyboard.press("Escape");
  await signOut(page);

  // The owner closes it: the select asks first, and the closed thread swaps the reply box for the note.
  await login(page, owner.email, owner.password);
  await openThread(page, roomId, title);
  await openDetails(page);
  await statusSelect(page).click();
  await page.getByRole("option", { name: "Done", exact: true }).click();
  const confirm = page.getByRole("dialog", { name: "Close this thread?" });
  await expect(confirm).toContainText("Moving #1 to Done closes it");
  await Promise.all([
    page.waitForResponse((r) => r.request().method() === "PUT" && STATUS_API.test(r.url()) && r.status() === 204),
    confirm.getByRole("button", { name: "Close thread", exact: true }).click(),
  ]);
  await expect(page.getByRole("dialog")).toHaveCount(0);
  await expect(statusSelect(page)).toHaveValue("Done");
  await expect(page.getByText(/^This thread is closed, so it takes no new messages/)).toBeVisible();
  await expect(page.getByRole("textbox", { name: "Reply", exact: true })).toHaveCount(0);
  await signOut(page);

  // The teammate finds it closed: no reply box, and no status but the current one can be picked.
  await login(page, mate.email, mate.password);
  await openThread(page, roomId, title, { closed: true });
  await expect(page.getByText(/^This thread is closed, so it takes no new messages/)).toBeVisible();
  await expect(page.getByRole("textbox", { name: "Reply", exact: true })).toHaveCount(0);
  await openDetails(page);
  await statusSelect(page).click();
  await expect(page.getByRole("option", { name: /^Open/ })).toHaveAttribute("data-combobox-disabled", "true");
  await page.keyboard.press("Escape");
  await signOut(page);

  // The room owner reopens it (no confirm), and the teammate can reply again.
  await login(page, owner.email, owner.password);
  await openThread(page, roomId, title, { closed: true });
  await openDetails(page);
  await moveTo(page, "Open");
  await expect(page.getByRole("dialog")).toHaveCount(0);
  await expect(page.getByRole("textbox", { name: "Reply", exact: true })).toBeVisible();
  await signOut(page);
  await login(page, mate.email, mate.password);
  await openThread(page, roomId, title);
  await reply(page, "Back in business.");
  await expect(messageNamed(page, "Back in business.")).toHaveCount(1);
});

test("the owner passes the thread on, the new owner edits its details, and the history lists every change", async ({ page }) => {
  await admin.login(page);
  const owner = await throwawayUser(page, "E2E Thread Owner", cleanup);
  const mate = await throwawayUser(page, "E2E Thread Mate", cleanup);
  await signOut(page);

  // The Incident template carries the optional fields (Severity, Component, Jira link, ETA) the new owner will fill in.
  await login(page, owner.email, owner.password);
  const roomId = await createRoomViaUi(page, uniqueText("e2e-thr"), "Incident", cleanup);
  await addMemberViaUi(page, roomId, mate);
  const title = uniqueText("Queue backlog");
  await startThreadViaUi(page, roomId, title, "The ingest queue is growing.");

  // The owner passes it on: choosing a member does not move it, "Pass on" does.
  await openDetails(page);
  const picker = ownerPicker(page);
  await expect(picker).toHaveValue(owner.name);
  await picker.click();
  await picker.fill(mate.name);
  await page.getByRole("option", { name: `${mate.name} (${mate.email})` }).click();
  await Promise.all([
    page.waitForResponse((r) => r.request().method() === "PUT" && OWNER_API.test(r.url()) && r.status() === 204),
    page.getByRole("button", { name: "Pass on", exact: true }).click(),
  ]);
  const region = page.getByRole("region", { name: `#1 ${title}` });
  await expect(region).toContainText(`Owner: ${mate.name}`);
  await signOut(page);

  // The new owner (a plain member, now a steward of this thread) moves the status and fills in the details.
  await login(page, mate.email, mate.password);
  await openThread(page, roomId, title);
  await openDetails(page);
  await expect(ownerPicker(page)).toHaveValue(mate.name);
  await moveTo(page, "Investigating");
  await page.getByRole("button", { name: "Edit details", exact: true }).click();
  const newTitle = `${title} (updated)`;
  await page.getByRole("textbox", { name: /^Title/ }).fill(newTitle);
  await page.getByRole("combobox", { name: "Severity", exact: true }).click();
  await page.getByRole("option", { name: "S1", exact: true }).click();
  await page.getByRole("textbox", { name: "Component", exact: true }).fill("ingest-api");
  await page.getByRole("textbox", { name: "Jira link", exact: true }).fill("https://example.test/INC-1");
  await Promise.all([
    page.waitForResponse((r) => r.request().method() === "PUT" && THREAD_API.test(r.url()) && r.status() === 204),
    page.getByRole("button", { name: "Save", exact: true }).click(),
  ]);
  // The editor ends, the pane heading carries the new title and the read view shows the values (the link opens in a new tab).
  await expect(page.getByRole("heading", { level: 3, name: `#1 ${newTitle}` })).toBeVisible();
  await expect(page.getByRole("textbox", { name: "Component", exact: true })).toHaveCount(0);
  await expect(page.getByText("Severity", { exact: true }).locator("xpath=following-sibling::dd[1]")).toHaveText("S1");
  await expect(page.getByText("Component", { exact: true }).locator("xpath=following-sibling::dd[1]")).toHaveText("ingest-api");
  const link = page.getByRole("link", { name: "https://example.test/INC-1", exact: true });
  await expect(link).toHaveAttribute("target", "_blank");
  await expect(link).toHaveAttribute("rel", "noopener noreferrer");

  // The history lists every change, newest first: the title and three fields, the move, the hand-over and the start.
  const entries = page.getByRole("group", { name: "History", exact: true }).locator(".mantine-Timeline-item");
  await expect(entries).toHaveCount(7);
  await expect(entries.nth(6)).toContainText("Started the thread in Triage");
  await expect(entries.nth(6)).toContainText(owner.name);
  await expect(entries.nth(5)).toContainText(`Passed the thread from ${owner.name} to ${mate.name}`);
  await expect(entries.nth(4)).toContainText("Moved the thread from Triage to Investigating");
  await expect(entries.nth(4)).toContainText(mate.name);
  const newest = page.getByRole("group", { name: "History", exact: true });
  for (const text of ["Renamed the thread", "Changed Severity", "Changed Component", "Changed Jira link"]) await expect(newest).toContainText(text);
  // The history keeps no free text: neither title, nor the TEXT and URL values, is recorded (a select value still is).
  await expect(newest).not.toContainText(title);
  await expect(newest).not.toContainText(newTitle);
  await expect(newest).not.toContainText("ingest-api");
  await expect(newest).not.toContainText("INC-1");
  await expect(newest).toContainText("Severity: set to S1");

  // The changes outlive a reload.
  await page.reload();
  await expect(page.getByRole("heading", { level: 3, name: `#1 ${newTitle}` })).toBeVisible();
  await openDetails(page);
  await expect(page.getByRole("group", { name: "History", exact: true }).locator(".mantine-Timeline-item")).toHaveCount(7);
});

test("the details panel opens on demand and stays open for the next thread", async ({ page }) => {
  await admin.login(page);
  const owner = await throwawayUser(page, "E2E Thread Owner", cleanup);
  await signOut(page);

  await login(page, owner.email, owner.password);
  const roomId = await createRoomViaUi(page, uniqueText("e2e-thr"), "Default", cleanup);
  const first = uniqueText("Panel first");
  const second = uniqueText("Panel second");
  await startThreadViaUi(page, roomId, first, "The first thread.");
  await startThreadViaUi(page, roomId, second, "The second thread.");

  // Collapsed by default: the header's Details button says so, the panel's sections are not on the page, and the conversation has the room —
  // at the suite's 1280x720 the composer's Send is on screen without scrolling.
  await openThread(page, roomId, first);
  const details = page.getByRole("button", { name: "Details", exact: true });
  await expect(details).toHaveAttribute("aria-expanded", "false");
  await expect(page.getByRole("heading", { level: 4, name: "Status", exact: true })).toBeHidden();
  await expect(statusSelect(page)).toBeHidden();
  await expect(page.getByRole("button", { name: "Send", exact: true })).toBeInViewport();

  // Opening it shows Status, Owner, Details and History beside (or above) the conversation.
  await details.click();
  await expect(details).toHaveAttribute("aria-expanded", "true");
  for (const name of ["Status", "Owner", "Details", "History"]) await expect(page.getByRole("heading", { level: 4, name, exact: true })).toBeVisible();
  await expect(page.getByRole("button", { name: "Send", exact: true })).toBeInViewport();

  // The choice is the person's, not the thread's: the next thread opens with the panel open, and so does a reload.
  await threadList(page).getByRole("link", { name: new RegExp(second) }).click();
  await expect(page.getByRole("heading", { level: 3, name: `#2 ${second}` })).toBeVisible();
  await expect(page.getByRole("button", { name: "Details", exact: true })).toHaveAttribute("aria-expanded", "true");
  await expect(page.getByRole("heading", { level: 4, name: "Status", exact: true })).toBeVisible();
  await page.reload();
  await expect(page.getByRole("heading", { level: 3, name: `#2 ${second}` })).toBeVisible();
  await expect(page.getByRole("button", { name: "Details", exact: true })).toHaveAttribute("aria-expanded", "true");

  // The pane is a column bounded by the viewport, so nothing in the header can push the composer off the screen: Send stays in view with the panel
  // OPEN at both sizes, and with a title far too long for the header's row (it ends in an ellipsis; the heading's name is still the whole title).
  const longTitle = uniqueText("A very long thread title that cannot possibly fit on the one row the header gives it beside the status and the four icon buttons");
  await startThreadViaUi(page, roomId, longTitle, "A thread with a long title.");
  await expect(page.getByRole("heading", { level: 3, name: `#3 ${longTitle}` })).toBeVisible();
  await expect(page.getByRole("button", { name: "Details", exact: true })).toHaveAttribute("aria-expanded", "true");
  await expect(page.getByRole("heading", { level: 4, name: "Status", exact: true })).toBeVisible();
  // Seven hashtags, one of them fifty characters long, put a third row under the header (1.2.0). It is ONE line however narrow the header is
  // (four links that shrink to an ellipsis, then "+3"), so it cannot push Send off the screen either.
  await reply(page, ["#alpha", "#beta", "#gamma", "#delta", "#epsilon", "#zeta", `#${"x".repeat(50)}`].join(" "));
  const tagRow = page.getByRole("group", { name: "Hashtags", exact: true }).first();
  await expect(tagRow.getByRole("button", { name: /^\+3: / })).toBeVisible();
  const oneLine = async (where: string) => expect((await tagRow.boundingBox())!.height, `the hashtag row is one line, ${where}`).toBeLessThan(28);
  for (const size of [
    { width: 1280, height: 720 },
    { width: 1440, height: 900 },
  ]) {
    await page.setViewportSize(size);
    await expect(page.getByRole("button", { name: "Send", exact: true })).toBeInViewport();
    expect(await page.evaluate(() => document.documentElement.scrollHeight <= window.innerHeight), `no page scroll at ${size.width}x${size.height}`).toBe(true);
    await oneLine(`panel open at ${size.width}x${size.height}`);
  }
  // A pinned message adds the pin strip under the header (1.2.0): one line collapsed; expanded, its capped list is in flow and the
  // conversation gives the height back, so Send stays in view and the page still does not scroll, with the panel open, at both sizes.
  await conversation(page).getByRole("listitem").filter({ hasText: "A thread with a long title." }).getByRole("button", { name: /^Operations for message by / }).click();
  await Promise.all([
    page.waitForResponse((r) => r.request().method() === "PUT" && /\/messages\/\d+\/pin$/.test(r.url()) && r.ok()),
    page.getByRole("menuitem", { name: "Pin", exact: true }).click(),
  ]);
  const pinStrip = page.getByRole("region", { name: "Pins in this thread", exact: true });
  const pinToggle = pinStrip.getByRole("button", { name: "Pinned: A thread with a long title.", exact: true });
  await expect(pinToggle).toHaveAttribute("aria-expanded", "false");
  for (const size of [
    { width: 1280, height: 720 },
    { width: 1440, height: 900 },
  ]) {
    await page.setViewportSize(size);
    for (const state of ["collapsed", "expanded"]) {
      if (state === "expanded") await pinToggle.click();
      await expect(page.getByRole("button", { name: "Send", exact: true }), `Send with the strip ${state} at ${size.width}x${size.height}`).toBeInViewport();
      expect(await page.evaluate(() => document.documentElement.scrollHeight <= window.innerHeight), `no page scroll with the strip ${state} at ${size.width}x${size.height}`).toBe(true);
      await oneLine(`panel open, strip ${state} at ${size.width}x${size.height}`);
    }
    await expect(pinStrip.getByRole("link", { name: /^Show in thread/ })).toBeVisible();
    await pinStrip.getByRole("button", { name: /^Pinned in this thread/ }).click();
  }
  // The same with the panel CLOSED, strip collapsed and expanded: the third row is there, still one line, and Send stays in view.
  const detailsButton = page.getByRole("button", { name: "Details", exact: true });
  await detailsButton.click();
  await expect(detailsButton).toHaveAttribute("aria-expanded", "false");
  for (const size of [
    { width: 1280, height: 720 },
    { width: 1440, height: 900 },
  ]) {
    await page.setViewportSize(size);
    for (const state of ["collapsed", "expanded"]) {
      if (state === "expanded") await pinToggle.click();
      const where = `the panel closed and the strip ${state} at ${size.width}x${size.height}`;
      await expect(page.getByRole("button", { name: "Send", exact: true }), `Send with ${where}`).toBeInViewport();
      expect(await page.evaluate(() => document.documentElement.scrollHeight <= window.innerHeight), `no page scroll with ${where}`).toBe(true);
      await oneLine(where);
    }
    await pinStrip.getByRole("button", { name: /^Pinned in this thread/ }).click();
  }
  await detailsButton.click();
  await expect(detailsButton).toHaveAttribute("aria-expanded", "true");
  await page.setViewportSize({ width: 1280, height: 720 });
  await threadList(page).getByRole("link", { name: new RegExp(second) }).click();

  // Closing it is remembered the same way.
  await page.getByRole("button", { name: "Details", exact: true }).click();
  await expect(page.getByRole("button", { name: "Details", exact: true })).toHaveAttribute("aria-expanded", "false");
  await page.reload();
  await expect(page.getByRole("heading", { level: 3, name: `#2 ${second}` })).toBeVisible();
  await expect(page.getByRole("button", { name: "Details", exact: true })).toHaveAttribute("aria-expanded", "false");
  await expect(page.getByRole("heading", { level: 4, name: "Status", exact: true })).toBeHidden();

  // Narrow layouts are not bounded: the page scrolls, the conversation keeps a real height (at least 16rem) and the composer sticks to the bottom
  // edge, so Send is in view both at the top of the page and after scrolling to its bottom. A phone first (375x740, panel closed): the long
  // title also keeps a readable box (two lines, not a few characters beside the icons).
  const sendInView = async (where: string) => {
    const send = page.getByRole("button", { name: "Send", exact: true });
    await page.evaluate(() => window.scrollTo(0, 0));
    await expect(send, `Send at the top, ${where}`).toBeInViewport();
    await page.evaluate(() => window.scrollTo(0, document.documentElement.scrollHeight));
    await expect(send, `Send at the bottom, ${where}`).toBeInViewport();
    expect((await conversation(page).boundingBox())!.height, `the conversation's height, ${where}`).toBeGreaterThanOrEqual(256);
  };
  await page.setViewportSize({ width: 375, height: 740 });
  await page.goto(`/rooms/${roomId}/threads/3`);
  const heading = page.getByRole("heading", { level: 3, name: `#3 ${longTitle}` });
  await expect(heading).toBeVisible();
  expect((await heading.boundingBox())!.width, "the title's box on a phone").toBeGreaterThanOrEqual(200);
  await sendInView("on a phone");

  // The panel stacked above the conversation (1024x720, the navigation open: the main area is too narrow for the third column).
  await page.setViewportSize({ width: 1024, height: 720 });
  await page.goto(`/rooms/${roomId}/threads/3`);
  await expect(heading).toBeVisible();
  await page.getByRole("button", { name: "Details", exact: true }).click();
  await expect(page.getByRole("button", { name: "Details", exact: true })).toHaveAttribute("aria-expanded", "true");
  await expect(page.getByRole("heading", { level: 4, name: "Status", exact: true })).toBeVisible();
  await sendInView("with the panel stacked open");
});
