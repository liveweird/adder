import type { APIRequestContext, Locator, Page } from "@playwright/test";
import { apiAsUser, axeViolations, deleteViaApi, expect, login, specAdmins, test, uniqueText } from "./helpers";

const admins = specAdmins("e2e-organization-admin");
const cleanup: string[] = [];

test.use({ hasTouch: true });

test.afterEach(async () => {
  await deleteViaApi(cleanup.splice(0));
});

async function createPerson(api: APIRequestContext, name: string) {
  const email = `${uniqueText("e2e-organization")}@adder.local`;
  const password = uniqueText("E2e-organization-password");
  const response = await api.post("/api/v1/users", { data: { name, email, password, roles: [] } });
  expect(response.status()).toBe(201);
  const { id } = (await response.json()) as { id: number };
  cleanup.push(`/api/v1/users/${id}`);
  return { id, email, password };
}

async function joinedRooms(api: APIRequestContext, names: string[], otherUserId: number) {
  const templates = await api.get("/api/v1/workflow-templates?name=Default");
  expect(templates.status()).toBe(200);
  const { items } = (await templates.json()) as { items: { id: number; name: string }[] };
  const templateId = items.find((template) => template.name === "Default")!.id;
  for (const name of names) {
    const room = await api.post("/api/v1/rooms", { data: { name, templateId, accessType: "RESTRICTED_VISIBLE" } });
    expect(room.status()).toBe(201);
    const { id } = (await room.json()) as { id: number };
    cleanup.unshift(`/api/v1/rooms/${id}`);
    const members = await api.post(`/api/v1/rooms/${id}/members`, { data: { userIds: [otherUserId] } });
    expect(members.status()).toBe(200);
  }
}

async function center(target: Locator, height = 0.5) {
  const bounds = await target.boundingBox();
  expect(bounds).not.toBeNull();
  return { x: bounds!.x + bounds!.width / 2, y: bounds!.y + bounds!.height * height };
}

async function touchDrag(page: Page, handle: Locator, destination: Locator) {
  await handle.scrollIntoViewIfNeeded();
  await expect(destination).toBeInViewport();
  const from = await center(handle);
  const to = await center(destination);
  const cdp = await page.context().newCDPSession(page);
  try {
    await cdp.send("Input.dispatchTouchEvent", { type: "touchStart", touchPoints: [from] });
    for (const fraction of [0.25, 0.5, 0.75, 1]) {
      const point = { x: from.x + (to.x - from.x) * fraction, y: from.y + (to.y - from.y) * fraction };
      await cdp.send("Input.dispatchTouchEvent", { type: "touchMove", touchPoints: [point] });
    }
    await cdp.send("Input.dispatchTouchEvent", { type: "touchEnd", touchPoints: [] });
  } finally {
    await cdp.detach();
  }
}

async function mouseDrag(page: Page, handle: Locator, destination: Locator, height: number) {
  await handle.scrollIntoViewIfNeeded();
  const from = await center(handle);
  const to = await center(destination, height);
  await page.mouse.move(from.x, from.y);
  await page.mouse.down();
  await page.mouse.move(to.x, to.y, { steps: 8 });
  await page.mouse.up();
}

test("private room groups and room order follow the account", async ({ page, browser }) => {
  const { api: admin } = await apiAsUser(admins.first.email, admins.first.password);
  const organizer = await createPerson(admin, "e2e Organizer");
  const colleague = await createPerson(admin, "e2e Colleague");
  await admin.dispose();
  const [intake, alerts, releases] = ["Intake", "Alerts", "Releases"].map((name) => uniqueText(`e2e ${name}`));
  const { api } = await apiAsUser(organizer.email, organizer.password);
  try {
    await joinedRooms(api, [intake, alerts, releases], colleague.id);
  } finally {
    await api.dispose();
  }

  const projects = uniqueText("e2e Projects");
  const operations = uniqueText("e2e Operations");
  await page.setViewportSize({ width: 390, height: 844 });
  await login(page, organizer.email, organizer.password, "/rooms");
  await page.getByRole("main").getByRole("link", { name: "Organize rooms" }).click();
  await expect(page.getByRole("heading", { name: "Organize rooms" })).toBeVisible();
  for (const name of [projects, operations]) {
    await page.getByRole("button", { name: "New group", exact: true }).click();
    await page.getByRole("textbox", { name: "Group name", exact: true }).fill(name);
    await page.getByRole("button", { name: "Create group" }).click();
    await expect(page.getByRole("region", { name })).toBeVisible();
  }
  await page.getByRole("button", { name: `Collapse ${projects}`, exact: true }).click();
  await touchDrag(page, page.getByRole("button", { name: `Drag ${releases}`, exact: true }),
    page.getByRole("button", { name: `Expand ${projects}`, exact: true }));
  await expect(page.getByRole("region", { name: projects }).getByText(releases, { exact: true })).toBeVisible();

  for (const room of [intake, alerts]) {
    const actions = page.getByRole("button", { name: `Actions for ${room}`, exact: true });
    await actions.click();
    await page.getByRole("menuitem", { name: "Move to group" }).click();
    await page.getByRole("menuitem", { name: operations, exact: true }).click();
    await expect(actions).toBeFocused();
  }
  const groupRegion = page.getByRole("region", { name: operations });
  await expect(groupRegion.getByRole("listitem")).toHaveText([new RegExp(intake), new RegExp(alerts)]);
  await groupRegion.getByRole("button", { name: `Actions for ${alerts}`, exact: true }).press("Enter");
  await page.getByRole("menuitem", { name: "Move up", exact: true }).press("Enter");
  await expect(groupRegion.getByRole("listitem")).toHaveText([new RegExp(alerts), new RegExp(intake)]);

  const groupHeadings = page.getByRole("main").getByRole("heading", { level: 3 });
  const operationsHandle = page.getByRole("button", { name: `Drag ${operations}`, exact: true });
  const projectsHeader = page.getByRole("button", { name: `Collapse ${projects}`, exact: true });
  await mouseDrag(page, operationsHandle, projectsHeader, 0.25);
  await expect(groupHeadings).toHaveText([operations, projects, "Ungrouped"]);
  await mouseDrag(page, operationsHandle, projectsHeader, 0.8);
  await expect(groupHeadings).toHaveText([projects, operations, "Ungrouped"]);
  await mouseDrag(page, operationsHandle, projectsHeader, 0.25);
  await expect(groupHeadings).toHaveText([operations, projects, "Ungrouped"]);
  expect(await axeViolations(page, "main")).toEqual([]);
  await page.getByRole("button", { name: "Save organization" }).click();
  await expect(page.getByText("Room organization saved", { exact: true })).toBeVisible();

  const freshDevice = await browser.newPage({ viewport: { width: 1440, height: 900 } });
  try {
    await login(freshDevice, organizer.email, organizer.password);
    const nav = freshDevice.getByRole("navigation", { name: "Navigation" });
    const ordered = nav.getByRole("group", { name: operations, exact: true });
    await expect(ordered.getByRole("link")).toHaveText([alerts, intake]);
    const roomNavigation = nav.getByRole("group", { name: "Rooms", exact: true });
    await expect(roomNavigation.getByRole("link")).toHaveText(["All rooms", alerts, intake, releases]);
  } finally {
    await freshDevice.close();
  }

  const other = await browser.newPage({ viewport: { width: 1440, height: 900 } });
  try {
    await login(other, colleague.email, colleague.password, "/rooms/organize");
    await expect(other.getByRole("region", { name: "Ungrouped", exact: true }).getByText(intake, { exact: true })).toBeVisible();
    await expect(other.getByRole("group", { name: operations, exact: true })).toHaveCount(0);
    await expect(other.getByRole("region", { name: projects, exact: true })).toHaveCount(0);
  } finally {
    await other.close();
  }
});
