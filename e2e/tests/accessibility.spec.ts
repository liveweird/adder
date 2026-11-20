// Axe accessibility smoke: WCAG 2.0/2.1 A+AA scans over the login screen, the authenticated list
// and form pages, the detail pages of one API-seeded fixture team and room (the conversation with its messages, reactions, pins, the
// mention picker open and the "New messages" marker; the search pages; the open thread filter panel; a workflow template's edit page), and
// the overlays (a registry editor modal) — where focus traps, aria-modal and labels actually live, and the app shell at a phone width
// (the burger); every set runs in the light scheme and again in the dark one. Owns: the fixture team
// (unique `e2e-axe-*` name), the fixture room (unique `e2e-axe-room-*` name, a copy of the seeded Default template, with one thread
// in it, pinned together with its opening message, with a teammate's messages and reactions) and an archived twin of it, and the
// teammate (unique `e2e-axe-mate-*` name, a plain user), created and deleted via the API. Every signed-in test acts as ONE throwaway
// administrator (`e2e-axe-admin-*`, minted through the seed administrator's API): the sweep opens ~80 pages, each minting a live-socket
// ticket, and the per-user `live-ticket` bucket (60 a minute) is too small to share the seed administrator with the parallel specs.
import type { APIRequestContext, Locator, Page } from "@playwright/test";
import { randomUUID } from "node:crypto";
import { apiAsAdmin, apiAsUser, axeViolations, expect, expectScheme, login, openFilters, type Scheme, test, uniqueText } from "./helpers";

// No waivers: the theme's text/dimmed/ink tokens are AA-tested in web/src/theme.test.ts, so
// the color-contrast rule runs for real here (Lettuce's posture, not Toadie's waiver). Fix a
// finding at the token level — never by patching single elements.
async function scan(page: Page, include?: string): Promise<void> {
  // Keep the assert readable on failure: one line per violation with the offending nodes.
  expect(await axeViolations(page, include)).toEqual([]);
}

/** The sweep's one administrator: the fixture room's owner and the account every signed-in test acts as. */
const sweepAdmin = { id: 0, name: "", email: "", password: "" };

test.beforeAll(async () => {
  sweepAdmin.name = uniqueText("e2e-axe-admin");
  sweepAdmin.email = `${sweepAdmin.name.toLowerCase().replace(/[^a-z0-9-]/g, "-")}@adder.local`;
  sweepAdmin.password = `E2e-${randomUUID()}`.slice(0, 40);
  const { api: seedApi } = await apiAsAdmin();
  try {
    const minted = await seedApi.post("/api/v1/users", { data: { name: sweepAdmin.name, email: sweepAdmin.email, password: sweepAdmin.password, roles: ["ADMIN"] } });
    expect(minted.status(), await minted.text()).toBe(201);
    sweepAdmin.id = (await minted.json() as { id: number }).id;
  } finally {
    await seedApi.dispose();
  }
});

// The detail block's own teardown (its rooms, team and teammate) runs first; the administrator that owned them goes last.
test.afterAll(async () => {
  if (sweepAdmin.id === 0) return;
  const { api: seedApi } = await apiAsAdmin();
  try {
    const response = await seedApi.delete(`/api/v1/users/${sweepAdmin.id}`);
    expect([204, 404], `the sweep administrator -> ${response.status()}`).toContain(response.status());
  } finally {
    await seedApi.dispose();
  }
});

/** Signs in as the sweep's administrator. */
const signIn = (page: Page) => login(page, sweepAdmin.email, sweepAdmin.password);

test("login screen has no WCAG A/AA violations", async ({ page }) => {
  await page.goto("/login");
  await expect(page.getByRole("button", { name: "Sign in" })).toBeVisible();
  await scan(page);
});

// One test per page keeps the report line-per-page.
const AUTHED_PAGES: { path: string; heading: string }[] = [
  { path: "/", heading: "Adder" },
  { path: "/search", heading: "Search" },
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
      await signIn(page);
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
    await signIn(page);
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

/** The fixture teammate's handles, filled in by `seedConversation` as each exists. */
type Teammate = { id?: number; api?: APIRequestContext };

/**
 * The fixture room's one thread (the admin owns the room, so it may start one), pinned together with its opening message: the pinned panel needs
 * something to list (a pin is the room's, so the room's deletion takes them). Threads are never deleted; the room's deletion hides it.
 */
async function seedThread(api: APIRequestContext, roomId: number): Promise<{ threadNumber: number; threadTitle: string }> {
  const threadTitle = uniqueText("e2e-axe-thread");
  const thread = await api.post(`/api/v1/rooms/${roomId}/threads`, { data: { title: threadTitle, body: "Seeded for the accessibility sweep." } });
  expect(thread.status(), await thread.text()).toBe(201);
  const threadNumber = (await thread.json() as { number: number }).number;
  const pinnedThread = await api.put(`/api/v1/rooms/${roomId}/threads/${threadNumber}/pin`, { data: { pinned: true } });
  expect(pinnedThread.status(), await pinnedThread.text()).toBe(204);
  const messages = await api.get(`/api/v1/rooms/${roomId}/threads/${threadNumber}/messages`);
  expect(messages.status(), await messages.text()).toBe(200);
  const openingId = ((await messages.json()) as { items: { id: number }[] }).items[0].id;
  const pinnedMessage = await api.put(`/api/v1/rooms/${roomId}/messages/${openingId}/pin`, { data: { pinned: true } });
  expect(pinnedMessage.status(), await pinnedMessage.text()).toBe(204);
  return { threadNumber, threadTitle };
}

/**
 * The teammate joins the room and writes into the thread: Markdown, a mention of the admin, a code block and reactions on one message. The
 * teammate's id and API session are recorded in `mate` the moment they exist, so a failure further on still tears them down.
 */
async function seedConversation(api: APIRequestContext, adminId: number, roomId: number, threadNumber: number, mate: Teammate): Promise<void> {
  const mateName = uniqueText("e2e-axe-mate");
  const mateEmail = `${mateName}@adder.local`;
  const matePassword = "e2e-only-password";
  const created = await api.post("/api/v1/users", { data: { name: mateName, email: mateEmail, password: matePassword, roles: [] } });
  expect(created.status(), await created.text()).toBe(201);
  mate.id = (await created.json() as { id: number }).id;
  const added = await api.post(`/api/v1/rooms/${roomId}/members`, { data: { userIds: [mate.id] } });
  expect(added.status(), await added.text()).toBe(200);
  ({ api: mate.api } = await apiAsUser(mateEmail, matePassword));
  const mateApi = mate.api;
  const messagesPath = `/api/v1/rooms/${roomId}/threads/${threadNumber}/messages`;
  const post = async (client: APIRequestContext, body: string): Promise<number> => {
    const posted = await client.post(messagesPath, { data: { body } });
    expect(posted.status(), await posted.text()).toBe(201);
    return (await posted.json() as { id: number }).id;
  };
  const lagging = await post(mateApi, "The **replica** is lagging: see [the dashboard](https://example.test/dash) and `pg_stat_replication`.");
  await post(mateApi, `<@${adminId}> could you take the failover?\n\n- first drain the writer\n- then promote #axe-sweep\n\n\`\`\`\nSELECT pg_promote();\n\`\`\``);
  await post(api, "On it, promoting now.");
  for (const [client, emoji] of [[mateApi, "👍"], [api, "👍"], [mateApi, "🎉"]] as const) {
    const reacted = await client.put(`/api/v1/rooms/${roomId}/messages/${lagging}/reaction`, { data: { emoji, reacted: true } });
    expect(reacted.status(), await reacted.text()).toBe(204);
  }
}

test.describe("detail pages and overlays", () => {
  let api: APIRequestContext;
  let adminId: number;
  // The teammate: a plain member of the fixture room whose messages, reactions and later posts give the conversation its other voice.
  const mate: Teammate = {};
  // The seeded Default template: its edit page is scanned (read only, never saved).
  let defaultTemplateId: number;
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
    ({ api, userId: adminId } = await apiAsUser(sweepAdmin.email, sweepAdmin.password));
    teamName = uniqueText("e2e-axe-team");
    const created = await api.post("/api/v1/teams", { data: { name: teamName } });
    expect(created.status(), await created.text()).toBe(201);
    teamId = (await created.json() as { id: number }).id;
    // The fixture room copies the seeded Default template (shared and read-only); the admin becomes its owner.
    const templates = await api.get("/api/v1/workflow-templates?name=Default");
    expect(templates.status(), await templates.text()).toBe(200);
    const defaultTemplate = ((await templates.json()) as { items: { id: number; name: string }[] }).items.find((t) => t.name === "Default");
    expect(defaultTemplate, "the seeded Default template").toBeDefined();
    defaultTemplateId = defaultTemplate!.id;
    roomName = uniqueText("e2e-axe-room");
    const room = await api.post("/api/v1/rooms", { data: { name: roomName, templateId: defaultTemplate!.id } });
    expect(room.status(), await room.text()).toBe(201);
    roomId = (await room.json() as { id: number }).id;
    ({ threadNumber, threadTitle } = await seedThread(api, roomId));
    await seedConversation(api, adminId, roomId, threadNumber, mate);
    const archived = await api.post("/api/v1/rooms", { data: { name: uniqueText("e2e-axe-room"), templateId: defaultTemplate!.id } });
    expect(archived.status(), await archived.text()).toBe(201);
    archivedRoomId = (await archived.json() as { id: number }).id;
    const archive = await api.post(`/api/v1/rooms/${archivedRoomId}/archive`);
    expect(archive.status(), await archive.text()).toBe(204);
  });
  test.afterAll(async () => {
    // A fixture whose creation failed has no id: skip it. Every delete is attempted whatever the others do, and the context is always disposed.
    const failures: string[] = [];
    // The teammate goes last: the rooms that reference it go first.
    const paths = [
      roomId === undefined ? null : `/api/v1/rooms/${roomId}`,
      archivedRoomId === undefined ? null : `/api/v1/rooms/${archivedRoomId}`,
      teamId === undefined ? null : `/api/v1/teams/${teamId}`,
      mate.id === undefined ? null : `/api/v1/users/${mate.id}`,
    ];
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
      await mate.api?.dispose();
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

  const DETAIL_PAGES: {
    name: string;
    path: () => string;
    settled: (page: Page) => Locator;
    alsoSettled?: (page: Page) => Locator;
    /** Writes the page is about, through the API, before it is opened. */
    seed?: () => Promise<void>;
    before?: (page: Page) => Promise<void>;
  }[] = [
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
    { name: "the room members page", path: () => `/rooms/${roomId}/members`, settled: (p) => p.getByRole("table", { name: "Members", exact: true }).getByText(sweepAdmin.email) },
    // Settled once the editor has loaded: the first status row's label is in (the fixture room is a copy of Default, the admin its owner).
    { name: "the room workflow page", path: () => `/rooms/${roomId}/workflow`, settled: (p) => p.getByRole("group", { name: "Status 1", exact: true }).getByRole("textbox", { name: "Label" }) },
    // The read-only view: an archived room's workflow is plain text under a note.
    { name: "the archived room's read-only workflow page", path: () => `/rooms/${archivedRoomId}/workflow`, settled: (p) => p.getByRole("heading", { level: 3, name: "Statuses" }) },
    { name: "the room settings page", path: () => `/rooms/${roomId}/settings`, settled: (p) => p.getByRole("heading", { level: 2, name: roomName }) },
    // The open filter panel of the thread list (collapsed by default; the toggle persists per browser, so it is opened explicitly).
    {
      name: "the thread list's open filter panel",
      path: () => `/rooms/${roomId}`,
      settled: (p) => p.getByRole("navigation", { name: "Threads", exact: true }).getByRole("link", { name: new RegExp(threadTitle) }),
      before: async (p) => {
        await openFilters(p);
      },
    },
    // The conversation: the teammate's Markdown, the mention of the admin, a code block, the admin's reply and a message with two kinds of reaction;
    // the thread itself and its opening message are pinned (the pin button is pressed, the message carries its mark).
    {
      name: "the conversation with messages, reactions and pins",
      path: () => `/rooms/${roomId}/threads/${threadNumber}`,
      settled: (p) => p.getByRole("region", { name: new RegExp(`^#${threadNumber} `) }),
      before: async (p) => {
        await expect(p.getByRole("button", { name: "👍, 2 reactions", exact: true })).toBeVisible();
        await expect(p.getByRole("button", { name: "🎉, 1 reaction", exact: true })).toBeVisible();
        await expect(p.getByText("On it, promoting now.")).toBeVisible();
      },
    },
    // The mention picker: an @ in the composer opens the room's members as a listbox over the page.
    {
      name: "the mention picker",
      path: () => `/rooms/${roomId}/threads/${threadNumber}`,
      settled: (p) => p.getByRole("region", { name: new RegExp(`^#${threadNumber} `) }),
      before: async (p) => {
        await p.getByRole("textbox", { name: "Reply", exact: true }).pressSequentially("@");
        await expect(p.getByRole("listbox", { name: "People in this room", exact: true })).toBeVisible();
        await expect(p.getByRole("option").first()).toBeVisible();
      },
    },
    // The "New messages" line: the teammate writes AFTER the admin's last read, so the admin's open finds one unread message under the line.
    {
      name: "the conversation with the new-messages marker",
      path: () => `/rooms/${roomId}/threads/${threadNumber}`,
      settled: (p) => p.getByRole("region", { name: new RegExp(`^#${threadNumber} `) }),
      seed: async () => {
        const posted = await mate.api!.post(`/api/v1/rooms/${roomId}/threads/${threadNumber}/messages`, { data: { body: uniqueText("Unread news") } });
        expect(posted.status(), await posted.text()).toBe(201);
      },
      before: async (p) => {
        await expect(p.getByRole("separator", { name: "New messages", exact: true })).toBeVisible();
      },
    },
    // The search pages with a hit: the room's own (the hit wears the room's status pill) and the one across rooms (an Open pill and the room's name).
    {
      name: "the room's search results",
      path: () => `/rooms/${roomId}/search?q=${encodeURIComponent(threadTitle)}`,
      settled: (p) => p.getByRole("link", { name: `#${threadNumber} ${threadTitle}`, exact: true }),
    },
    {
      name: "the search results across rooms",
      path: () => `/search?q=${encodeURIComponent(threadTitle)}`,
      settled: (p) => p.getByRole("link", { name: `#${threadNumber} ${threadTitle}`, exact: true }),
    },
    // The edit page of the seeded Default template (shared and read only for every spec: opened and scanned, never saved).
    {
      name: "the workflow template's edit page",
      path: () => `/workflow-templates/${defaultTemplateId}/edit`,
      settled: (p) => p.getByRole("heading", { name: "Edit workflow template" }),
      before: async (p) => {
        await expect(p.getByRole("textbox", { name: "Name" }).first()).toHaveValue("Default");
      },
    },
    { name: "the edit-user page", path: () => `/users/${adminId}/edit`, settled: (p) => p.getByRole("heading", { name: "Edit user" }) },
    { name: "the user-features page", path: () => `/users/${adminId}/features`, settled: (p) => p.getByRole("heading", { name: "Feature flags" }) },
  ];
  // Every state below runs in the light scheme and again in the dark one (Playwright's `colorScheme`, which the `auto` default follows),
  // under a suffix, so a finding names its scheme. `expectScheme` runs before each scan: the scan really is in that palette.
  function registerDetailScans(scheme: Scheme): void {
    const suffix = scheme === "dark" ? " in the dark scheme" : "";
    for (const { name, path, settled, alsoSettled, seed, before } of DETAIL_PAGES) {
      test(`${name} has no WCAG A/AA violations${suffix}`, async ({ page }) => {
        await seed?.();
        await signIn(page);
        await page.goto(path());
        await expect(settled(page)).toBeVisible();
        if (alsoSettled) await expect(alsoSettled(page)).toBeVisible();
        if (before) await before(page);
        await expectScheme(page, scheme);
        await scan(page);
      });
    }

    test(`the reset-password page has no WCAG A/AA violations${suffix}`, async ({ page }) => {
      await page.goto("/reset-password");
      await expect(page.getByRole("heading", { name: "Reset password" })).toBeVisible();
      await expectScheme(page, scheme);
      await scan(page);
      // The emailed link's page (the form state of a well-formed token; nothing is submitted, so nothing is consumed).
      await page.goto(`/reset-password/confirm#token=${"a".repeat(43)}`);
      await expect(page.getByRole("button", { name: "Set new password" })).toBeVisible();
      await scan(page);
    });

    test(`the not-found page has no WCAG A/AA violations${suffix}`, async ({ page }) => {
      await signIn(page);
      await page.goto(`/${uniqueText("nowhere")}`);
      await expect(page.getByRole("heading").first()).toBeVisible();
      await expectScheme(page, scheme);
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

    test(`the room leave dialog has no WCAG A/AA violations${suffix}`, async ({ page }) => {
      await signIn(page);
      await page.goto(`/rooms/${roomId}/members`);
      // The fixture room's owner is the seed admin, so the Leave button is there; the dialog is only opened and scanned, never confirmed.
      await page.getByRole("button", { name: "Leave room", exact: true }).click();
      const modal = await settledDialog(page);
      await expect(modal.getByRole("heading", { name: "Leave this room?" })).toBeVisible();
      await expectScheme(page, scheme);
      await scan(page, '[role="dialog"]');
    });

    test(`the notification drawer has no WCAG A/AA violations${suffix}`, async ({ page }) => {
      await signIn(page);
      await page.goto("/");
      await page.getByRole("button", { name: /^Notifications \(/ }).click();
      const drawer = await settledDialog(page);
      await expect(drawer.getByRole("heading", { name: "Notifications" })).toBeVisible();
      // The teammate's message mentions the admin, so the drawer lists that notice (a populated drawer, not the empty state).
      await expect(drawer.getByRole("link", { name: new RegExp(`mentioned you in #${threadNumber} ${threadTitle}`) })).toBeVisible();
      await expectScheme(page, scheme);
      await scan(page, '[role="dialog"]');
    });

    test(`the pinned panel has no WCAG A/AA violations${suffix}`, async ({ page }) => {
      await signIn(page);
      await page.goto(`/rooms/${roomId}`);
      await page.getByRole("button", { name: /^Pinned/ }).click();
      const panel = await settledDialog(page);
      // Settled once both pins (the thread and its opening message) are listed.
      await expect(panel.getByRole("link", { name: `#${threadNumber} ${threadTitle}`, exact: true })).toBeVisible();
      await expect(panel.getByText("Seeded for the accessibility sweep.")).toBeVisible();
      await expectScheme(page, scheme);
      await scan(page, '[role="dialog"]');
    });

    test(`a team editor modal has no WCAG A/AA violations${suffix}`, async ({ page }) => {
      await signIn(page);
      await page.goto("/teams");
      await page.getByRole("button", { name: "New team" }).click();
      const modal = await settledDialog(page);
      await expect(modal.getByRole("heading", { name: "New team" })).toBeVisible();
      await expectScheme(page, scheme);
      await scan(page, '[role="dialog"]');
    });
  }
  registerDetailScans("light");
  test.describe("dark scheme", () => {
    test.use({ colorScheme: "dark" });
    registerDetailScans("dark");
  });
});
