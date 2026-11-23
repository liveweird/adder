// People (1.2.0): the profile any signed-in member can open, `/people/:id` (1.2 D7), and the names that lead to it (1.2 D6). A member sees the person's name and email, their teams and the rooms the two SHARE
// (never a room only the person is in), nothing about the account beyond the directory's entry (no role, flags, language), and a removed or unknown person is one
// "not available" page. A message's author, a mention in a message and a notice's actor and room are links to the person and the room. Owns: its throwaway `e2e-people-*` users, team and rooms (and its own throwaway administrator, `e2e-people-op-*`, who mints them and is also the
// "person with a privileged account" of the second scenario), removed through the API in an `afterEach` (rooms, then the team, then the users). Rooms only copy the
// seeded Default template, which is shared and read-only; every room is created by a throwaway user through the API.
import { type APIRequestContext, type Locator } from "@playwright/test";
import { apiAsUser, axeViolations, conversation, deleteViaApi, expect, login, specAdmins, test, uniqueText } from "./helpers";

/** This file's own administrator (see `specAdmins`); the second scenario also reads its profile as "a person with a privileged account". */
const admin = specAdmins("e2e-people-op");

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

/** A plain throwaway user minted through the administrator's API (no browser, no ticket); its removal is queued AFTER everything that references it. */
async function mint(adminApi: APIRequestContext, prefix: string): Promise<Person> {
  const name = uniqueText(prefix);
  const email = `${name.toLowerCase().replace(/[^a-z0-9-]/g, "-")}@adder.local`;
  const password = `E2e-${uniqueText("pw")}`;
  const created = await adminApi.post("/api/v1/users", { data: { name, email, password, roles: [] } });
  expect(created.status(), await created.text()).toBe(201);
  const id = ((await created.json()) as { id: number }).id;
  cleanup.push(`/api/v1/users/${id}`);
  return { id, name, email, password };
}

/** A room (unique name) created through `api` from the seeded Default template; its removal is queued first. */
async function makeRoom(api: APIRequestContext, name: string): Promise<number> {
  const templates = await api.get("/api/v1/workflow-templates?name=Default");
  expect(templates.status(), await templates.text()).toBe(200);
  const template = ((await templates.json()) as { items: { id: number; name: string }[] }).items.find((candidate) => candidate.name === "Default");
  expect(template, "the seeded Default template").toBeDefined();
  const created = await api.post("/api/v1/rooms", { data: { name, templateId: template!.id, accessType: "RESTRICTED_VISIBLE" } });
  expect(created.status(), await created.text()).toBe(201);
  const id = ((await created.json()) as { id: number }).id;
  cleanup.unshift(`/api/v1/rooms/${id}`);
  return id;
}

test("a member opens a person's profile and sees the rooms they share", async ({ page }) => {
  const { api: adminApi } = await apiAsUser(admin.first.email, admin.first.password);
  apis.push(adminApi);
  const viewer = await mint(adminApi, "E2E People Viewer");
  const person = await mint(adminApi, "E2E People Person");

  const { api: viewerApi } = await apiAsUser(viewer.email, viewer.password);
  const { api: personApi } = await apiAsUser(person.email, person.password);
  apis.push(viewerApi, personApi);
  const sharedName = uniqueText("e2e-people-shared");
  const privateName = uniqueText("e2e-people-private");
  const sharedId = await makeRoom(viewerApi, sharedName);
  await makeRoom(personApi, privateName);
  const added = await viewerApi.post(`/api/v1/rooms/${sharedId}/members`, { data: { userIds: [person.id] } });
  expect(added.status(), await added.text()).toBe(200);

  const teamName = uniqueText("e2e-people-team");
  const team = await adminApi.post("/api/v1/teams", { data: { name: teamName } });
  expect(team.status(), await team.text()).toBe(201);
  const teamId = ((await team.json()) as { id: number }).id;
  cleanup.unshift(`/api/v1/teams/${teamId}`); // before the users: the roster references the person
  const rostered = await adminApi.put(`/api/v1/teams/${teamId}/members/${person.id}`);
  expect(rostered.status(), await rostered.text()).toBe(204);

  // The address is the way in here; the next scenarios follow the names that link to a profile.
  await login(page, viewer.email, viewer.password, `/people/${person.id}`);
  const main = page.getByRole("main");
  await expect(main.getByRole("heading", { level: 2, name: person.name })).toBeVisible();
  await expect(main.getByRole("link", { name: `Email ${person.email}` })).toBeVisible();
  await expect(main.getByRole("list", { name: "Teams" }).getByRole("link", { name: teamName })).toBeVisible();
  const rooms = main.getByRole("list", { name: "Rooms you share" });
  await expect(rooms.getByRole("link", { name: sharedName })).toBeVisible();
  await expect(rooms.getByRole("link", { name: privateName })).toHaveCount(0);
  await expect(main.getByRole("link", { name: "Edit user" })).toHaveCount(0);
  expect(await axeViolations(page, "main")).toEqual([]);

  // A shared room's link lands in the room.
  await rooms.getByRole("link", { name: sharedName }).click();
  await expect(page).toHaveURL(new RegExp(`/rooms/${sharedId}$`));
  await expect(page.getByRole("heading", { level: 2, name: sharedName })).toBeVisible();
});

test("a profile shows no role and an unknown id says so", async ({ page }) => {
  const { api: adminApi } = await apiAsUser(admin.first.email, admin.first.password);
  apis.push(adminApi);
  const viewer = await mint(adminApi, "E2E People Viewer");
  const removed = await mint(adminApi, "E2E People Removed");
  const { api: viewerApi } = await apiAsUser(viewer.email, viewer.password);
  apis.push(viewerApi);

  // The privileged account's profile is the same page: name and email, no role, flags or language, and no admin control for the viewer.
  const entry = await viewerApi.get(`/api/v1/users/directory/${admin.first.id}`);
  expect(entry.status(), await entry.text()).toBe(200);
  const entryBody = (await entry.json()) as { name: string };
  await login(page, viewer.email, viewer.password, `/people/${admin.first.id}`);
  const main = page.getByRole("main");
  await expect(main.getByRole("heading", { level: 2, name: entryBody.name })).toBeVisible();
  await expect(main.getByText("You share no room with this person.")).toBeVisible();
  await expect(main.getByText(/administrator|role|language|feature|mfa/i)).toHaveCount(0);
  await expect(main.getByRole("link", { name: "Edit user" })).toHaveCount(0);

  // The API says the same: exactly the directory's three fields, and the wider read stays closed.
  expect(Object.keys(entryBody).sort()).toEqual(["email", "id", "name"]);
  expect((await viewerApi.get(`/api/v1/users/${admin.first.id}`)).status()).toBe(403);

  // An unknown id and a removed account are one page and one status.
  const gone = page.getByRole("heading", { level: 2, name: "This person is not available" });
  await page.goto("/people/2000000000");
  await expect(gone).toBeVisible();
  expect((await viewerApi.get("/api/v1/users/directory/2000000000")).status()).toBe(404);
  expect((await adminApi.delete(`/api/v1/users/${removed.id}`)).status()).toBe(204);
  await page.goto(`/people/${removed.id}`);
  await expect(gone).toBeVisible();
  await expect(page.getByText("The account may have been removed, or the link is wrong.")).toBeVisible();
  expect((await viewerApi.get(`/api/v1/users/directory/${removed.id}`)).status()).toBe(404);
});

test("a message author, a mention and a notice lead to the person and the room", async ({ page }) => {
  const { api: adminApi } = await apiAsUser(admin.first.email, admin.first.password);
  apis.push(adminApi);
  const reader = await mint(adminApi, "E2E People Reader");
  const author = await mint(adminApi, "E2E People Author");
  const { api: authorApi } = await apiAsUser(author.email, author.password);
  apis.push(authorApi);
  const roomName = uniqueText("e2e-people-link-room");
  const roomId = await makeRoom(authorApi, roomName);
  const added = await authorApi.post(`/api/v1/rooms/${roomId}/members`, { data: { userIds: [reader.id] } });
  expect(added.status(), await added.text()).toBe(200);
  const started = await authorApi.post(`/api/v1/rooms/${roomId}/threads`, { data: { title: uniqueText("e2e-people-thread"), body: "Seeded through the API." } });
  expect(started.status(), await started.text()).toBe(201);
  const number = ((await started.json()) as { number: number }).number;
  const posted = await authorApi.post(`/api/v1/rooms/${roomId}/threads/${number}/messages`, { data: { body: `<@${reader.id}> please look at the replica.` } });
  expect(posted.status(), await posted.text()).toBe(201);

  await login(page, reader.email, reader.password, `/rooms/${roomId}/threads/${number}`);
  const profile = (who: Person) => page.getByRole("main").getByRole("heading", { level: 2, name: who.name });
  const message = conversation(page).getByRole("listitem").filter({ hasText: "please look at the replica." });

  // The author's name, in the message's own line, opens their profile; going back lands on the same thread.
  await message.getByRole("link", { name: author.name, exact: true }).click();
  await expect(page).toHaveURL(new RegExp(`/people/${author.id}$`));
  await expect(profile(author)).toBeVisible();
  await page.goBack();
  await expect(message).toBeVisible();

  // The mention in the body reads @Name and is a link to that person (here the reader's own profile).
  await message.getByRole("link", { name: `@${reader.name}`, exact: true }).click();
  await expect(page).toHaveURL(new RegExp(`/people/${reader.id}$`));
  await expect(profile(reader)).toBeVisible();
  await page.goBack();
  await expect(message).toBeVisible();

  // The header's owner line links to the owner too (the author started the thread), and the bell's notice has a link for the actor and one for the room, beside the one that opens the notice.
  await expect(page.getByRole("region", { name: new RegExp(`^#${number} `) }).getByRole("link", { name: author.name, exact: true }).first()).toHaveAttribute("href", `/people/${author.id}`);
  const drawer = page.getByRole("dialog", { name: "Notifications", exact: true });
  const openBell = async () => {
    await page.getByRole("button", { name: /^Notifications \(/ }).click();
    return drawer;
  };
  // The drawer holds one row for the new thread and one for the mention (each names the room and the actor): the links of the mention's row.
  const mentionRow = (opened: Locator) => opened.getByRole("listitem").filter({ hasText: "mentioned you" });
  await mentionRow(await openBell()).getByRole("link", { name: roomName, exact: true }).click();
  await expect(page).toHaveURL(new RegExp(`/rooms/${roomId}$`));
  await expect(drawer).toHaveCount(0);
  await expect(page.getByRole("heading", { level: 2, name: roomName })).toBeVisible();

  // Focus is never dropped to <body> by following such a link: the main region takes it (the page the person just opened starts at its top).
  await expect(page.locator("#main-content")).toBeFocused();

  // The whole row opens the notice, not only its words: a click in the row's padding (the stretched link) lands on the message.
  const notices = await openBell();
  await expect(notices.getByRole("link", { name: new RegExp(`${author.name} mentioned you in #${number} `) })).toBeVisible();
  await mentionRow(notices).click({ position: { x: 2, y: 6 } });
  await expect(page).toHaveURL(new RegExp(`/rooms/${roomId}/threads/${number}\\?.*message=`));
  await expect(drawer).toHaveCount(0);

  // The actor's link works from the keyboard, and focus lands in the page, not on <body>.
  const keyboardRow = mentionRow(await openBell());
  await keyboardRow.getByRole("link", { name: author.name, exact: true }).focus();
  await page.keyboard.press("Enter");
  await expect(page).toHaveURL(new RegExp(`/people/${author.id}$`));
  await expect(drawer).toHaveCount(0);
  await expect(profile(author)).toBeVisible();
  await expect(page.locator("#main-content")).toBeFocused();
  expect(await page.evaluate(() => document.activeElement?.tagName)).not.toBe("BODY");
});
