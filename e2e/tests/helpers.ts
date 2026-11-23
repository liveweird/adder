import AxeBuilder from "@axe-core/playwright";
import { type APIRequestContext, expect, type Page, request as playwrightRequest, test } from "@playwright/test";
import { randomUUID } from "node:crypto";
import { BASE_URL } from "../playwright.config";

export { expect, test };

/** The seeded bootstrap admin (V3) — the compose demo leaves its password unrotated. */
export const ADMIN = "admin@adder.local";
const PASSWORD = "changeme";

/**
 * Navigate to a usable sign-in form. Any leftover session has to go first: while one exists
 * the app's RedirectIfAuthed bounces /login to the home page, so the form never renders and
 * a fill() waits out the whole test timeout.
 */
async function gotoSignInForm(page: Page): Promise<void> {
  if (page.url().startsWith("http")) {
    await page.evaluate(() => localStorage.clear());
    await page.goto("/login");
  } else {
    // A fresh page (about:blank) is a fresh browser context: there is no session to clear, so ONE
    // load of the SPA is enough — the clear-and-reload branch above cost a second one in every
    // login (~125 per run, ~95 of them on the axe sweeps; build-times.md WHY 7).
    await page.goto("/login");
  }
  await expect(page.getByRole("button", { name: "Sign in" })).toBeVisible();
}

/**
 * Sign in through the real login form. Use it where the credential or the form IS the subject (the first sign-in with a
 * generated password, a reset password, the language riding the login response); everything else takes `login`.
 */
export async function loginViaForm(page: Page, email = ADMIN, password = PASSWORD): Promise<void> {
  await gotoSignInForm(page);
  // Target by textbox role: getByLabel("Password") also matches the visibility-toggle button.
  await page.getByRole("textbox", { name: "Email" }).fill(email);
  await page.getByRole("textbox", { name: "Password" }).fill(password);
  await page.getByRole("button", { name: "Sign in" }).click();
  await expect(accountMenu(page)).toBeVisible({ timeout: 15_000 });
}

/** The `/login` response fields the SPA persists (`storeSession` in `web/src/api/session.ts`). */
interface LoginBody {
  token: string;
  refreshToken: string;
  roles: string[];
  userId: number;
  disabledFeatures?: string[];
}

/**
 * The `localStorage` of a signed-in SPA: the keys `storeSession` writes, from the tokens the server just issued. The session identity
 * is left out on purpose: the SPA gives a session without one a fresh identity on first use, exactly as for an older session.
 */
function sessionEntries(body: LoginBody): [string, string][] {
  return [
    ["adder.auth.token", body.token],
    ["adder.auth.refreshToken", body.refreshToken],
    ["adder.auth.roles", JSON.stringify(body.roles)],
    ["adder.auth.userId", String(body.userId)],
    ["adder.auth.disabledFeatures", JSON.stringify(body.disabledFeatures ?? [])],
  ];
}

/**
 * Sign in WITHOUT the form: the real `POST /api/v1/login` through the page's request context (so the tokens, the revocation
 * blocklist and any credential-revision check are the server's own), then the session written to `localStorage` exactly as the SPA
 * would persist it, then ONE load of `/`. The form costs a full SPA boot on `/login`, two fills, a click and a client-side redirect
 * per sign-in, and the suite signs in ~190 times (build-times.md WHY 37). The session is minted per call: `signOut` revokes the pair
 * server-side, so one reused across tests would be a revoked one. A refused login (wrong password, an MFA challenge, a rate limit) or a
 * session the shell does not accept falls back to the real form, which then reports what is wrong. Pass credentials as a regular
 * user to read as one; the specs whose subject is a credential call `loginViaForm`.
 *
 * **Every full page load of the SPA mints one live-socket ticket** (`useLiveUpdates` opens the tab's socket once the shell mounts), and
 * the mint rides a per-user bucket of 60 a minute, so a test that signs in and then `goto`s its page loads twice. `landOn` is the path
 * that single load opens instead of `/`: a test that goes straight to a page signs in with it and spends one ticket, not two.
 */
export async function login(page: Page, email = ADMIN, password = PASSWORD, landOn = "/"): Promise<void> {
  const minted = await page.request.post("/api/v1/login", { data: { email, password } });
  const body = minted.ok() ? ((await minted.json()) as Partial<LoginBody>) : undefined;
  if (body?.token === undefined || body.refreshToken === undefined || body.roles === undefined || body.userId === undefined) {
    await loginViaForm(page, email, password);
    if (landOn !== "/") await page.goto(landOn);
    return;
  }
  // `localStorage` needs a document on the app's origin; a fresh page is about:blank, and a static asset is the cheapest document
  // the origin serves (the SPA is not booted for it).
  if (!page.url().startsWith("http")) await page.goto("/favicon.svg");
  await page.evaluate((entries) => {
    localStorage.clear();
    for (const [key, value] of entries) localStorage.setItem(key, value);
  }, sessionEntries(body as LoginBody));
  await page.goto(landOn);
  try {
    await expect(accountMenu(page)).toBeVisible({ timeout: 15_000 });
  } catch {
    // Drift in the persisted keys costs one slow sign-in here, never a red spec that is not about signing in.
    await loginViaForm(page, email, password);
    if (landOn !== "/") await page.goto(landOn);
  }
}

/** The header account-menu trigger — visible only inside the authenticated shell (v1.19.0). */
export function accountMenu(page: Page) {
  return page.getByRole("button", { name: "Account menu" });
}

/** Sign out through the account menu (the former header Logout button). */
export async function signOut(page: Page): Promise<void> {
  await accountMenu(page).click();
  await page.getByRole("menuitem", { name: "Sign out" }).click();
}

/**
 * Delete a user from the Users list through its row menu (v1.19.0: the row actions sit under
 * an "Operations for <name>" kebab) — the cleanup step every throwaway-user spec ends with.
 * Filters the list to the name first, then waits for the DELETE to land.
 */
export async function deleteUserRow(page: Page, name: string): Promise<void> {
  await page.goto("/users");
  await openFilters(page);
  await page.getByLabel("Name", { exact: true }).fill(name);
  await rowOperation(page, name, `Delete ${name}`);
  await Promise.all([
    page.waitForResponse((r) => r.request().method() === "DELETE" && r.ok()),
    page.getByRole("dialog").getByRole("button", { name: "Delete", exact: true }).click(),
  ]);
}

/**
 * Create a throwaway user through the real UI (an admin must be signed in) and capture the
 * generated password from the one-time reveal modal. Never mutate seeded accounts — use this.
 * The id comes from the POST response; the password from the dialog after "Show password".
 */
export async function createUserViaUi(
  page: Page,
  namePrefix = "E2E User",
): Promise<{ id: number; name: string; email: string; password: string }> {
  const name = uniqueText(namePrefix);
  const email = `${name.toLowerCase().replace(/[^a-z0-9-]/g, "-")}@adder.local`;
  await page.goto("/users/new");
  await page.getByRole("textbox", { name: "Name" }).fill(name);
  await page.getByRole("textbox", { name: "Email" }).fill(email);
  const [created] = await Promise.all([
    page.waitForResponse(
      (r) => r.url().endsWith("/api/v1/users") && r.request().method() === "POST" && r.ok(),
    ),
    page.getByRole("button", { name: "Create" }).click(),
  ]);
  const id: number = (await created.json()).id;
  const dialog = page.getByRole("dialog");
  // Masked as "*" until revealed — click the eye toggle first.
  await dialog.getByRole("button", { name: "Show password" }).click();
  await expect(dialog.getByRole("button", { name: "Hide password" })).toHaveAttribute("aria-pressed", "true");
  const password = (await dialog.locator("code").textContent()) ?? "";
  // Keep the credential out of assertion output: validate only the captured value's shape.
  expect(password.length > 0 && !/^\*+$/.test(password), "generated password is revealed").toBe(true);
  // Mantine renders both a header X and the footer button named Close.
  await dialog.getByRole("button", { name: "Close", exact: true }).last().click();
  await expect(page).toHaveURL(/\/users$/);
  return { id, name, email, password };
}

/**
 * A throwaway user through the admin UI (an admin must be signed in), with its cleanup registered: `cleanup` is the spec's own
 * teardown list (`deleteViaApi` in its `afterEach`; users go AFTER the rooms and teams that reference them, so push, never unshift).
 */
export async function throwawayUser(page: Page, name: string, cleanup: string[]) {
  const user = await createUserViaUi(page, name);
  cleanup.push(`/api/v1/users/${user.id}`);
  return user;
}

/** Collision-free text so specs never depend on absolute counts or clean state. */
export function uniqueText(prefix: string): string {
  return `${prefix}-${randomUUID().slice(0, 8)}`;
}

/**
 * Ensure a list view's filter panel is expanded. Idempotent on purpose: the open/collapsed
 * state persists per view in localStorage (adder.viewSettings.*), so within one test a
 * revisited page restores the panel open — a blind toggle click would close it again.
 */
export async function openFilters(page: Page): Promise<void> {
  const toggle = page.getByRole("button", { name: "Filters" });
  await expect(toggle).toBeVisible();
  if ((await toggle.getAttribute("aria-expanded")) !== "true") {
    await toggle.click();
  }
  await expect(toggle).toHaveAttribute("aria-expanded", "true");
}

/**
 * Drive a list row's action through its "Operations for <name>" kebab menu (the Users rows
 * today; list rows carry interpolated item names, hence the open `string` union member).
 */
export async function rowOperation(
  page: Page,
  name: string,
  operation: "Edit" | "Delete" | (string & {}),
): Promise<void> {
  const trigger = page.getByRole("button", { name: `Operations for ${name}` });
  // A missing row fails HERE with its name, not as an anonymous click timeout at the end of the test budget.
  await expect(trigger).toBeVisible();
  // Ensure THIS row's menu actually opened: a previous row's still-fading dropdown treats
  // the first click as its outside-click and swallows it, leaving the WRONG menu mounted —
  // an unscoped menuitem click would then drive the other row's operation. Bounded: an
  // unbounded toPass() retries until the TEST budget is gone and reports the wrong step.
  await expect(async () => {
    if ((await trigger.getAttribute("aria-expanded")) !== "true") await trigger.click();
    expect(await trigger.getAttribute("aria-expanded")).toBe("true");
  }).toPass({ timeout: 10_000 });
  const dropdownId = await trigger.getAttribute("aria-controls");
  await page.locator(`[id="${dropdownId}"]`).getByRole("menuitem", { name: operation }).click();
}

/**
 * API-side seeding for specs whose subject is a PAGE, not the journey that creates its data (the
 * accessibility sweep): one admin-authenticated request context against the same stack. Every id
 * it creates is the caller's to delete directly through the same API — the Owns rule applies unchanged.
 */
export async function apiAsAdmin(): Promise<{ api: APIRequestContext; userId: number }> {
  return apiAsUser(ADMIN, PASSWORD);
}

/**
 * The same for any account (a throwaway user's credentials from `throwawayUser`): one bearer-authenticated request context, so a spec
 * can act as that user through the API (seed a room it owns, post a message from "the other tab") without a browser. The caller disposes it.
 */
export async function apiAsUser(email: string, password: string): Promise<{ api: APIRequestContext; userId: number }> {
  const anonymous = await playwrightRequest.newContext({ baseURL: BASE_URL });
  const login = await anonymous.post("/api/v1/login", { data: { email, password } });
  expect(login.ok(), await login.text()).toBeTruthy();
  const { token, userId } = (await login.json()) as { token: string; userId: number };
  await anonymous.dispose();
  const api = await playwrightRequest.newContext({ baseURL: BASE_URL, extraHTTPHeaders: { Authorization: `Bearer ${token}` } });
  return { api, userId };
}

/** A throwaway administrator minted by `specAdmins`. */
interface SpecAdmin {
  id: number;
  email: string;
  password: string;
}

/** The handle `specAdmins` returns. */
export interface SpecAdmins {
  /** The first administrator (the one that owns what the spec seeds); valid from the spec's `beforeAll` on. */
  readonly first: SpecAdmin;
  /** Every administrator of the pool, the first included; valid from the spec's `beforeAll` on. */
  readonly all: readonly SpecAdmin[];
  /** Signs `page` in as the first administrator (`login`'s `landOn`: the one path that single load opens). */
  login(page: Page, landOn?: string): Promise<void>;
  /** Signs `page` in as the next administrator of the pool after the first, in turn (the only one of a pool of one): for pages whose reader does not matter, so no one user spends the bucket. */
  loginNext(page: Page, landOn?: string): Promise<void>;
}

/**
 * Throwaway ADMINISTRATORS for the calling spec file, in place of the seed administrator: call it once at the top of the file. The
 * suite's page loads each mint a live-socket ticket (see `login`), and that bucket is per USER (60 a minute), so seventeen specs
 * signing in as the one seed administrator spent it in a full run. Each file therefore mints its own `count` administrators in
 * `beforeAll` (through the seed administrator's API: no browser, no ticket) and deletes them in `afterAll`, after the file's own
 * `afterEach` teardown has removed the rooms and teams they own. Every one is attempted, whatever the others answer, and a
 * partly failed mint still deletes the ones that exist. 204 and 404 pass. Use more than one only for a file that opens dozens of
 * pages in a minute (the accessibility sweep); the random password never leaves this process.
 */
export function specAdmins(prefix: string, count = 1): SpecAdmins {
  const admins: SpecAdmin[] = [];
  let turn = 0;
  test.beforeAll(async () => {
    const { api } = await apiAsAdmin();
    try {
      for (let i = 0; i < count; i++) {
        const name = uniqueText(prefix);
        const email = `${name.toLowerCase().replace(/[^a-z0-9-]/g, "-")}@adder.local`;
        const password = `E2e-${randomUUID()}`.slice(0, 40);
        const minted = await api.post("/api/v1/users", { data: { name, email, password, roles: ["ADMIN"] } });
        expect(minted.status(), await minted.text()).toBe(201);
        admins.push({ id: ((await minted.json()) as { id: number }).id, email, password });
      }
    } finally {
      await api.dispose();
    }
  });
  test.afterAll(async () => {
    if (admins.length === 0) return;
    // The ids leave `admins` only once the client exists: a seed-administrator login that fails here must not leak ADMIN accounts silently.
    let api: APIRequestContext;
    try {
      ({ api } = await apiAsAdmin());
    } catch (error) {
      throw new Error(`spec administrators LEAKED (delete them by hand): ids ${admins.map((admin) => admin.id).join(", ")}; the seed login failed`, { cause: error });
    }
    const minted = admins.splice(0);
    const failures: string[] = [];
    try {
      for (const admin of minted) {
        try {
          const status = (await api.delete(`/api/v1/users/${admin.id}`)).status();
          if (status !== 204 && status !== 404) failures.push(`/api/v1/users/${admin.id} -> ${status}`);
        } catch (error) {
          failures.push(`/api/v1/users/${admin.id} -> ${String(error)}`);
        }
      }
    } finally {
      await api.dispose();
    }
    expect(failures, "spec administrator teardown DELETEs that answered neither 204 nor 404").toEqual([]);
  });
  const signInAs = (admin: SpecAdmin, page: Page, landOn?: string) => login(page, admin.email, admin.password, landOn);
  return {
    get first() {
      return admins[0];
    },
    get all() {
      return admins;
    },
    login: (page, landOn) => signInAs(admins[0], page, landOn),
    // A pool leaves the first administrator (the owner of what the spec seeds) to the pages that are about it.
    loginNext: (page, landOn) => {
      const readers = admins.length > 1 ? admins.slice(1) : admins;
      return signInAs(readers[turn++ % readers.length], page, landOn);
    },
  };
}

/**
 * Teardown through the API for the rows a test created, for a spec's `test.afterEach` — so a failing step leaves nothing
 * behind. `paths` are deleted IN THE GIVEN ORDER
 * (children first) through a FRESH admin session. EVERY path is
 * attempted; 204 and 404 pass, since the test body may already have deleted a row through the UI — so callers must register
 * only paths built from a create response's id (a mistyped path would also answer 404 and go unnoticed). Any other status is
 * collected and thrown once at the end, listing all of them.
 */
export async function deleteViaApi(paths: string[]): Promise<void> {
  if (paths.length === 0) return;
  const { api } = await apiAsAdmin();
  const failures: string[] = [];
  try {
    for (const path of paths) {
      const status = (await api.delete(path)).status();
      if (status !== 204 && status !== 404) failures.push(`${path} -> ${status}`);
    }
  } finally {
    await api.dispose();
  }
  expect(failures, `teardown DELETEs that answered neither 204 nor 404`).toEqual([]);
}

const AXE_TAGS = ["wcag2a", "wcag2aa", "wcag21a", "wcag21aa"];

/**
 * An axe WCAG 2.0/2.1 A+AA scan of the open page (or of `include`, a selector), as one readable
 * line per violation with the offending nodes — `[]` when clean. No waivers, `color-contrast`
 * included: the theme's tokens are AA-tested in `web/src/theme.test.ts`, so a finding is fixed at
 * the token level, never by patching single elements.
 */
export async function axeViolations(page: Page, include?: string): Promise<{ id: string; impact: string | null | undefined; help: string; nodes: string[] }[]> {
  const builder = new AxeBuilder({ page }).withTags(AXE_TAGS);
  const results = await (include ? builder.include(include) : builder).analyze();
  return results.violations.map((v) => ({
    id: v.id,
    impact: v.impact,
    help: v.help,
    nodes: v.nodes.map((n) => n.target.join(" ")),
  }));
}

export type Scheme = "light" | "dark";

/** The scheme the page must actually render in — Mantine stamps it on <html> (the `auto` default follows Playwright's emulated `colorScheme`). */
export async function expectScheme(page: Page, scheme: Scheme): Promise<void> {
  await expect(page.locator("html")).toHaveAttribute("data-mantine-color-scheme", scheme);
}

/** The sidebar's Rooms section: the static "All rooms" leaf and the caller's rooms rail. */
export const rail = (page: Page) => page.getByRole("group", { name: "Rooms", exact: true });
/** The conversation's scroller: its messages are the list items inside it. */
export const conversation = (page: Page) => page.getByRole("region", { name: "Messages", exact: true });
/** A room's thread list (the navigation beside the conversation). */
export const threadList = (page: Page) => page.getByRole("navigation", { name: "Threads", exact: true });

const ROOM_CREATE_API = /\/api\/v1\/rooms$/;

/**
 * Create a room from the create form as the signed-in user and register its cleanup (`cleanup` is the spec's teardown list, rooms
 * first: `unshift`); leaves the browser on the new room. The default access choice lets the teardown administrator delete it.
 * `labels` are the form's own words for a UI that speaks another language or a test exercising a specific access type.
 */
export async function createRoomViaUi(
  page: Page,
  name: string,
  template: string,
  cleanup: string[],
  labels: { name: string; template: string; create: string; access?: string } = { name: "Name", template: "Workflow template", create: "Create" },
): Promise<number> {
  await page.goto("/rooms/new");
  await page.getByRole("textbox", { name: labels.name, exact: true }).fill(name);
  await page.getByText(labels.access ?? "Restricted, visible", { exact: true }).click();
  await page.getByRole("combobox", { name: labels.template }).click();
  await page.getByRole("option", { name: template, exact: true }).click();
  const [created] = await Promise.all([
    page.waitForResponse((r) => r.request().method() === "POST" && ROOM_CREATE_API.test(r.url()) && r.status() === 201),
    page.getByRole("button", { name: labels.create, exact: true }).click(),
  ]);
  const id = ((await created.json()) as { id: number }).id;
  cleanup.unshift(`/api/v1/rooms/${id}`);
  await expect(page).toHaveURL(new RegExp(`/rooms/${id}$`));
  await expect(page.getByRole("heading", { level: 2, name })).toBeVisible();
  return id;
}
