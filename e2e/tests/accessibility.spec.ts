// Axe accessibility smoke: WCAG 2.0/2.1 A+AA scans over the login screen, the authenticated list
// and form pages, the detail pages of one API-seeded fixture team, and the overlays (a registry editor
// modal) — where focus traps, aria-modal and labels actually live, and the app shell at a phone width
// (the burger); the page sets run in the light scheme and again in the dark one. Owns: the fixture team
// (unique `e2e-axe-*` name) and the fixture room (unique `e2e-axe-room-*` name, a copy of the seeded Default template, with one thread
// in it, pinned together with its opening message) and an archived twin of it, created and deleted via the API.
import type { APIRequestContext, Locator, Page } from "@playwright/test";
import { ADMIN, apiAsAdmin, axeViolations, expect, expectScheme, login, type Scheme, test, uniqueText } from "./helpers";

// No waivers: the theme's text/dimmed/ink tokens are AA-tested in web/src/theme.test.ts, so
// the color-contrast rule runs for real here (Lettuce's posture, not Toadie's waiver). Fix a
// finding at the token level — never by patching single elements.
async function scan(page: Page, include?: string): Promise<void> {
  // Keep the assert readable on failure: one line per violation with the offending nodes.
  expect(await axeViolations(page, include)).toEqual([]);
}

test("login screen has no WCAG A/AA violations", async ({ page }) => {
  await page.goto("/login");
  await expect(page.getByRole("button", { name: "Sign in" })).toBeVisible();
  await scan(page);
});

// One test per page keeps the report line-per-page.
const AUTHED_PAGES: { path: string; heading: string }[] = [
  { path: "/", heading: "Adder" },
  { path: "/rooms", heading: "Rooms" },
  { path: "/rooms/new", heading: "New room" },
  { path: "/teams", heading: "Teams" },
  { path: "/users", heading: "Users" },
  { path: "/users/new", heading: "New user" },
  { path: "/workflow-templates", heading: "Workflow templates" },
  { path: "/workflow-templates/new", heading: "New workflow template" },
  { path: "/feature-flags", heading: "Feature flags" },
  { path: "/change-password", heading: "Change password" },
  { path: "/notification-preferences", heading: "Notification emails" },
  { path: "/changelog", heading: "Changelog" },
];

// The light pass keeps its original titles; the dark pass (Playwright's `colorScheme: "dark"`, which
// the app's `auto` default follows) repeats the set under a suffix, so a finding names its scheme.
function registerAuthedPageScans(scheme: Scheme): void {
  const suffix = scheme === "dark" ? " in the dark scheme" : "";
  for (const { path, heading } of AUTHED_PAGES) {
    test(`${path} has no WCAG A/AA violations${suffix}`, async ({ page }) => {
      await login(page);
      await page.goto(path);
      // The page title is the first heading.
      await expect(page.getByRole("heading", { name: heading }).first()).toBeVisible();
      await expectScheme(page, scheme);
      await scan(page);
    });
  }
}
registerAuthedPageScans("light");
test.describe("dark scheme", () => {
  test.use({ colorScheme: "dark" });
  registerAuthedPageScans("dark");
  test("login screen has no WCAG A/AA violations in the dark scheme", async ({ page }) => {
    await page.goto("/login");
    await expect(page.getByRole("button", { name: "Sign in" })).toBeVisible();
    await expectScheme(page, "dark");
    await scan(page);
  });
});

// The app shell at a phone width (390x844): the desktop rail toggle gives way to the burger — the
// one control that opens the nav drawer — so the shell is scanned with it present, light and dark.
function registerMobileShellScan(scheme: Scheme): void {
  const suffix = scheme === "dark" ? " in the dark scheme" : "";
  test(`the app shell at a mobile width has no WCAG A/AA violations${suffix}`, async ({ page }) => {
    await login(page);
    await page.goto("/");
    await expect(page.getByRole("heading", { name: "Adder" }).first()).toBeVisible();
    await expect(page.getByRole("button", { name: "Toggle navigation" })).toBeVisible();
    await expectScheme(page, scheme);
    await scan(page);
  });
}
test.describe("mobile width", () => {
  test.use({ viewport: { width: 390, height: 844 } });
  registerMobileShellScan("light");
  test.describe("dark scheme", () => {
    test.use({ colorScheme: "dark" });
    registerMobileShellScan("dark");
  });
});

test.describe("detail pages and overlays", () => {
  let api: APIRequestContext;
  let adminId: number;
  let teamId: number | undefined;
  let teamName: string;
  let roomId: number | undefined;
  let roomName: string;
  // The fixture room's one thread (threads are never deleted; the room's deletion hides it).
  let threadNumber: number;
  let threadTitle: string;
  // A second fixture room, archived: its workflow page is the read-only view (plain text, not the editor).
  let archivedRoomId: number | undefined;

  test.beforeAll(async () => {
    ({ api, userId: adminId } = await apiAsAdmin());
    teamName = uniqueText("e2e-axe-team");
    const created = await api.post("/api/v1/teams", { data: { name: teamName } });
    expect(created.status(), await created.text()).toBe(201);
    teamId = (await created.json() as { id: number }).id;
    // The fixture room copies the seeded Default template (shared and read-only); the admin becomes its owner.
    const templates = await api.get("/api/v1/workflow-templates?name=Default");
    expect(templates.status(), await templates.text()).toBe(200);
    const defaultTemplate = ((await templates.json()) as { items: { id: number; name: string }[] }).items.find((t) => t.name === "Default");
    expect(defaultTemplate, "the seeded Default template").toBeDefined();
    roomName = uniqueText("e2e-axe-room");
    const room = await api.post("/api/v1/rooms", { data: { name: roomName, templateId: defaultTemplate!.id } });
    expect(room.status(), await room.text()).toBe(201);
    roomId = (await room.json() as { id: number }).id;
    // The thread view and the thread list need a thread to show: the admin owns the fixture room, so it may start one.
    threadTitle = uniqueText("e2e-axe-thread");
    const thread = await api.post(`/api/v1/rooms/${roomId}/threads`, { data: { title: threadTitle, body: "Seeded for the accessibility sweep." } });
    expect(thread.status(), await thread.text()).toBe(201);
    threadNumber = (await thread.json() as { number: number }).number;
    // The pinned panel needs something to list: the thread and its opening message are pinned (a pin is the room's, so the room's deletion takes them).
    const pinnedThread = await api.put(`/api/v1/rooms/${roomId}/threads/${threadNumber}/pin`, { data: { pinned: true } });
    expect(pinnedThread.status(), await pinnedThread.text()).toBe(204);
    const messages = await api.get(`/api/v1/rooms/${roomId}/threads/${threadNumber}/messages`);
    expect(messages.status(), await messages.text()).toBe(200);
    const openingId = ((await messages.json()) as { items: { id: number }[] }).items[0].id;
    const pinnedMessage = await api.put(`/api/v1/rooms/${roomId}/messages/${openingId}/pin`, { data: { pinned: true } });
    expect(pinnedMessage.status(), await pinnedMessage.text()).toBe(204);
    const archived = await api.post("/api/v1/rooms", { data: { name: uniqueText("e2e-axe-room"), templateId: defaultTemplate!.id } });
    expect(archived.status(), await archived.text()).toBe(201);
    archivedRoomId = (await archived.json() as { id: number }).id;
    const archive = await api.post(`/api/v1/rooms/${archivedRoomId}/archive`);
    expect(archive.status(), await archive.text()).toBe(204);
  });
  test.afterAll(async () => {
    // A fixture whose creation failed has no id: skip it. Every delete is attempted whatever the others do, and the context is always disposed.
    const failures: string[] = [];
    const paths = [roomId === undefined ? null : `/api/v1/rooms/${roomId}`, archivedRoomId === undefined ? null : `/api/v1/rooms/${archivedRoomId}`, teamId === undefined ? null : `/api/v1/teams/${teamId}`];
    try {
      for (const path of paths) {
        if (path === null) continue;
        try {
          const response = await api.delete(path);
          expect([204, 404], `${path} -> ${response.status()}`).toContain(response.status());
        } catch (error) {
          failures.push(String(error));
        }
      }
    } finally {
      // `api` is unset when even the admin session could not be opened.
      await (api as APIRequestContext | undefined)?.dispose();
    }
    expect(failures, "fixture teardown").toEqual([]);
  });

  /** Opens the thread's side panel when it is folded behind the "Details" toggle (a narrower main area); in the wide layout it is always there. */
  const openSidePanel = async (page: Page) => {
    const toggle = page.getByRole("button", { name: "Details", exact: true });
    // The toggle is `display: none` in the wide layout, so visibility (not presence) says whether the panel is folded.
    if ((await toggle.isVisible()) && (await toggle.getAttribute("aria-expanded")) === "false") await toggle.click();
    await expect(page.getByRole("combobox", { name: "Thread status", exact: true })).toBeVisible();
  };

  const DETAIL_PAGES: { name: string; path: () => string; settled: (page: Page) => Locator; alsoSettled?: (page: Page) => Locator; before?: (page: Page) => Promise<void> }[] = [
    { name: "the team page", path: () => `/teams/${teamId}`, settled: (p) => p.getByRole("heading", { name: teamName }) },
    // Settled once the thread list has loaded: the seeded thread is a link in the labelled Threads nav.
    { name: "the room home page (the thread list)", path: () => `/rooms/${roomId}`, settled: (p) => p.getByRole("navigation", { name: "Threads", exact: true }).getByRole("link", { name: new RegExp(threadTitle) }) },
    // The three-pane view with a thread open: the pane's heading is `#<number> <title>`; the list beside it is loaded too.
    {
      name: "the room's thread view",
      path: () => `/rooms/${roomId}/threads/${threadNumber}`,
      settled: (p) => p.getByRole("navigation", { name: "Threads", exact: true }).getByRole("link", { name: new RegExp(threadTitle) }),
      alsoSettled: (p) => p.getByRole("region", { name: new RegExp(`^#${threadNumber} `) }),
    },
    // The side panel: Status, Owner (the fixture room's admin owns the room and the thread, so the member picker is drawn), Details and History.
    {
      name: "the thread's side panel",
      path: () => `/rooms/${roomId}/threads/${threadNumber}`,
      settled: (p) => p.getByRole("region", { name: new RegExp(`^#${threadNumber} `) }),
      before: async (p) => {
        await openSidePanel(p);
        await expect(p.getByRole("heading", { level: 4, name: "History", exact: true })).toBeVisible();
        await expect(p.getByText("Started the thread in Open")).toBeVisible();
      },
    },
    // The details editor open: the title and the (Default template: none) fields, the Cancel and Save buttons.
    {
      name: "the thread's details editor",
      path: () => `/rooms/${roomId}/threads/${threadNumber}`,
      settled: (p) => p.getByRole("region", { name: new RegExp(`^#${threadNumber} `) }),
      before: async (p) => {
        await openSidePanel(p);
        await p.getByRole("button", { name: "Edit details", exact: true }).click();
        await expect(p.getByRole("textbox", { name: /^Title/ })).toBeVisible();
      },
    },
    { name: "the new-thread form", path: () => `/rooms/${roomId}/threads/new`, settled: (p) => p.getByRole("textbox", { name: /Title/ }) },
    // Settled once the admin's own row (the fixture room's owner) is in the table: the list has loaded, the controls are drawn.
    { name: "the room members page", path: () => `/rooms/${roomId}/members`, settled: (p) => p.getByRole("table", { name: "Members", exact: true }).getByText(ADMIN) },
    // Settled once the editor has loaded: the first status row's label is in (the fixture room is a copy of Default, the admin its owner).
    { name: "the room workflow page", path: () => `/rooms/${roomId}/workflow`, settled: (p) => p.getByRole("group", { name: "Status 1", exact: true }).getByRole("textbox", { name: "Label" }) },
    // The read-only view: an archived room's workflow is plain text under a note.
    { name: "the archived room's read-only workflow page", path: () => `/rooms/${archivedRoomId}/workflow`, settled: (p) => p.getByRole("heading", { level: 3, name: "Statuses" }) },
    { name: "the room settings page", path: () => `/rooms/${roomId}/settings`, settled: (p) => p.getByRole("heading", { level: 2, name: roomName }) },
    { name: "the edit-user page", path: () => `/users/${adminId}/edit`, settled: (p) => p.getByRole("heading", { name: "Edit user" }) },
    { name: "the user-features page", path: () => `/users/${adminId}/features`, settled: (p) => p.getByRole("heading", { name: "Feature flags" }) },
  ];
  for (const { name, path, settled, alsoSettled, before } of DETAIL_PAGES) {
    test(`${name} has no WCAG A/AA violations`, async ({ page }) => {
      await login(page);
      await page.goto(path());
      await expect(settled(page)).toBeVisible();
      if (alsoSettled) await expect(alsoSettled(page)).toBeVisible();
      if (before) await before(page);
      await scan(page);
    });
  }

  test("the reset-password page has no WCAG A/AA violations", async ({ page }) => {
    await page.goto("/reset-password");
    await expect(page.getByRole("heading", { name: "Reset password" })).toBeVisible();
    await scan(page);
    // The emailed link's page (the form state of a well-formed token; nothing is submitted, so nothing is consumed).
    await page.goto(`/reset-password/confirm#token=${"a".repeat(43)}`);
    await expect(page.getByRole("button", { name: "Set new password" })).toBeVisible();
    await scan(page);
  });

  test("the not-found page has no WCAG A/AA violations", async ({ page }) => {
    await login(page);
    await page.goto(`/${uniqueText("nowhere")}`);
    await expect(page.getByRole("heading").first()).toBeVisible();
    await scan(page);
  });

  // Overlays are scanned scoped to the dialog: the page behind them is inert, and the dialog is
  // where a missing label, a broken focus trap or a contrast slip would hide from the page sweep.
  // `toBeVisible` passes mid-transition; axe measures contrast against the fade-in's partial
  // opacity, so the scan waits for the dialog to settle at full opacity.
  async function settledDialog(page: Page): Promise<Locator> {
    const dialog = page.getByRole("dialog");
    await expect(dialog).toBeVisible();
    await expect(dialog).toHaveCSS("opacity", "1");
    return dialog;
  }

  test("the room leave dialog has no WCAG A/AA violations", async ({ page }) => {
    await login(page);
    await page.goto(`/rooms/${roomId}/members`);
    // The fixture room's owner is the seed admin, so the Leave button is there; the dialog is only opened and scanned, never confirmed.
    await page.getByRole("button", { name: "Leave room", exact: true }).click();
    const modal = await settledDialog(page);
    await expect(modal.getByRole("heading", { name: "Leave this room?" })).toBeVisible();
    await scan(page, '[role="dialog"]');
  });

  test("the notification drawer has no WCAG A/AA violations", async ({ page }) => {
    await login(page);
    await page.goto("/");
    await page.getByRole("button", { name: /^Notifications \(/ }).click();
    const drawer = await settledDialog(page);
    await expect(drawer.getByRole("heading", { name: "Notifications" })).toBeVisible();
    await expect(drawer.getByText("You have no notifications.")).toBeVisible();
    await scan(page, '[role="dialog"]');
  });

  test("the pinned panel has no WCAG A/AA violations", async ({ page }) => {
    await login(page);
    await page.goto(`/rooms/${roomId}`);
    await page.getByRole("button", { name: /^Pinned/ }).click();
    const panel = await settledDialog(page);
    // Settled once both pins (the thread and its opening message) are listed.
    await expect(panel.getByRole("link", { name: `#${threadNumber} ${threadTitle}`, exact: true })).toBeVisible();
    await expect(panel.getByText("Seeded for the accessibility sweep.")).toBeVisible();
    await scan(page, '[role="dialog"]');
  });

  test("a team editor modal has no WCAG A/AA violations", async ({ page }) => {
    await login(page);
    await page.goto("/teams");
    await page.getByRole("button", { name: "New team" }).click();
    const modal = await settledDialog(page);
    await expect(modal.getByRole("heading", { name: "New team" })).toBeVisible();
    await scan(page, '[role="dialog"]');
  });
});
