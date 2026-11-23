import type { APIRequestContext } from "@playwright/test";
import { apiAsUser, axeViolations, deleteViaApi, expect, login, specAdmins, test, uniqueText } from "./helpers";

const admins = specAdmins("e2e-home-admin");
const cleanup: string[] = [];

test.afterEach(async () => {
  await deleteViaApi(cleanup.splice(0));
});

async function mintUser(api: APIRequestContext, name: string) {
  const email = `${uniqueText("e2e-home")}@adder.local`;
  const password = uniqueText("E2e-home-password");
  const response = await api.post("/api/v1/users", { data: { name, email, password, roles: [] } });
  expect(response.status()).toBe(201);
  const { id } = (await response.json()) as { id: number };
  cleanup.push(`/api/v1/users/${id}`);
  return { id, email, password };
}

async function createRoom(api: APIRequestContext, name: string, templateId: number) {
  const response = await api.post("/api/v1/rooms", { data: { name, templateId, accessType: "RESTRICTED_VISIBLE" } });
  expect(response.status()).toBe(201);
  const { id } = (await response.json()) as { id: number };
  cleanup.unshift(`/api/v1/rooms/${id}`);
  return id;
}

test("Home shows personal quotes, owned threads, watched threads, and room requests", async ({ page }) => {
  const { api: admin } = await apiAsUser(admins.first.email, admins.first.password);
  const owner = await mintUser(admin, "Home Owner");
  const colleague = await mintUser(admin, "Home Colleague");
  const applicant = await mintUser(admin, "Home Applicant");
  await admin.dispose();

  const { api: ownerApi } = await apiAsUser(owner.email, owner.password);
  const { api: colleagueApi } = await apiAsUser(colleague.email, colleague.password);
  const { api: applicantApi } = await apiAsUser(applicant.email, applicant.password);
  const ownedRoom = uniqueText("Incident coordination");
  const joinedRoom = uniqueText("Support queue");
  let roomId: number;
  let threadNumber: number;
  let quotedReplyId: number;
  try {
    const templates = await ownerApi.get("/api/v1/workflow-templates?name=Default");
    const { items } = (await templates.json()) as { items: { id: number; name: string }[] };
    const templateId = items.find((template) => template.name === "Default")!.id;
    roomId = await createRoom(ownerApi, ownedRoom, templateId);
    const joinedId = await createRoom(colleagueApi, joinedRoom, templateId);
    expect((await ownerApi.post(`/api/v1/rooms/${roomId}/members`, { data: { userIds: [colleague.id] } })).status()).toBe(200);
    expect((await colleagueApi.post(`/api/v1/rooms/${joinedId}/members`, { data: { userIds: [owner.id] } })).status()).toBe(200);
    expect((await applicantApi.post(`/api/v1/rooms/${roomId}/join-requests`)).status()).toBe(204);

    const owned = await ownerApi.post(`/api/v1/rooms/${roomId}/threads`, {
      data: { title: "Check the incident timeline", body: "Gather the rollout updates here." },
    });
    expect(owned.status()).toBe(201);
    threadNumber = ((await owned.json()) as { number: number }).number;
    const messages = `/api/v1/rooms/${roomId}/threads/${threadNumber}/messages`;
    const original = await ownerApi.post(messages, { data: { body: "The rollout began at nine." } });
    expect(original.status()).toBe(201);
    const { id: quotedMessageId } = (await original.json()) as { id: number };
    const reply = await colleagueApi.post(messages, { data: { body: "I checked the rollout timestamps.", quotedMessageId } });
    expect(reply.status()).toBe(201);
    quotedReplyId = ((await reply.json()) as { id: number }).id;

    const watched = await colleagueApi.post(`/api/v1/rooms/${joinedId}/threads`, {
      data: { title: "Watch the support handoff", body: "Please follow the afternoon handoff." },
    });
    expect(watched.status()).toBe(201);
    const watchedNumber = ((await watched.json()) as { number: number }).number;
    expect((await ownerApi.put(`/api/v1/rooms/${joinedId}/threads/${watchedNumber}/watch`, { data: { watching: true } })).status()).toBe(204);
  } finally {
    await Promise.all([ownerApi.dispose(), colleagueApi.dispose(), applicantApi.dispose()]);
  }

  await page.setViewportSize({ width: 390, height: 844 });
  await login(page, owner.email, owner.password);
  const quotes = page.getByRole("region", { name: "Replies quoting you" });
  const ownedThreads = page.getByRole("region", { name: "Open threads you own" });
  const watchedThreads = page.getByRole("region", { name: "Watched threads" });
  const rooms = page.getByRole("region", { name: "Rooms you own" });
  const quote = quotes.getByRole("link", { name: /I checked the rollout timestamps/ });
  await expect(quote).toBeVisible();
  await expect(ownedThreads.getByRole("link", { name: /Check the incident timeline/ })).toBeVisible();
  await expect(watchedThreads.getByRole("link", { name: /Watch the support handoff/ })).toBeVisible();
  await expect(rooms.getByText(ownedRoom)).toBeVisible();
  await expect(rooms.getByText(joinedRoom)).toHaveCount(0);
  expect(await axeViolations(page, "main")).toEqual([]);

  await quote.click();
  await expect(page).toHaveURL(new RegExp(`/rooms/${roomId}/threads/${threadNumber}\\?message=${quotedReplyId}`));
  await expect(page.getByText("I checked the rollout timestamps.", { exact: true })).toBeVisible();
  await page.goto("/");
  await expect(quote).toBeVisible();
  const requestLink = rooms.getByRole("link", { name: /join request/i });
  await requestLink.click();
  await expect(page).toHaveURL(new RegExp(`/rooms/${roomId}/members#room-join-requests-title$`));
  const queueHeading = page.getByRole("heading", { name: "Join requests", exact: true });
  await expect(queueHeading).toBeFocused();
  await expect(queueHeading).toBeInViewport();
  const headerBottom = await page.getByRole("banner").evaluate((banner) => banner.getBoundingClientRect().bottom);
  expect((await queueHeading.boundingBox())?.y).toBeGreaterThanOrEqual(headerBottom);
  await expect(page.getByRole("button", { name: "Approve Home Applicant" })).toBeVisible();
});
