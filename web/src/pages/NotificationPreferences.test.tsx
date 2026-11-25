import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { screen, waitFor, within } from "@testing-library/react";
import { notifications } from "@mantine/notifications";
import NotificationPreferences from "./NotificationPreferences";
import type { NotificationPreferences as Preferences, RoomMembershipPreferences } from "../api/notifications";
import enNotifications from "../locales/en/notifications.json";
import { headingOutline } from "../test/headings";
import { jsonResponse } from "../test/http";
import { renderWithProviders } from "../test/render";

const PATH = "/api/v1/users/5/notification-preferences";
const KINDS = Object.keys(enNotifications.event).filter((type) => type !== "ROOM_ADDED") as Preferences["kinds"][number]["type"][];

/** Every kind at its default (the app on, email off) unless `over` says otherwise. */
function prefs(
  over: Record<string, { inApp?: boolean; email?: boolean }> = {},
  emailAvailable = true,
  roomMembership: RoomMembershipPreferences = { inApp: true, email: false },
): Preferences {
  return { emailAvailable, kinds: KINDS.map((type) => ({ type, inApp: true, email: false, ...over[type] })), roomMembership };
}

type Pair = { type: string; channel: string };
type PutBody = { disabled: Pair[]; enabled: Pair[]; roomMembership?: RoomMembershipPreferences };

/** GET answers `stored`; a PUT answers `putStatus` and, on a 204, the next GET answers what the PUT stated. */
function stubApi(stored: Preferences, putStatus = 204) {
  let current = stored;
  const fetchMock = vi.fn((url: string, init?: RequestInit) => {
    if (url !== PATH) return Promise.resolve(jsonResponse(404, {}));
    if (init?.method === "PUT") {
      if (putStatus !== 204) return Promise.resolve(jsonResponse(putStatus, { title: "x", status: putStatus }));
      const body = JSON.parse(init.body as string) as PutBody;
      const on = (type: string, channel: string) => body.enabled.some((p) => p.type === type && p.channel === channel);
      current = {
        ...current,
        kinds: current.kinds.map((k) => ({ ...k, inApp: on(k.type, "IN_APP"), email: on(k.type, "EMAIL") })),
        roomMembership: body.roomMembership ?? current.roomMembership,
      };
      return Promise.resolve(new Response(null, { status: 204 }));
    }
    return Promise.resolve(jsonResponse(200, current));
  });
  vi.stubGlobal("fetch", fetchMock);
  return fetchMock;
}

function putBodies(fetchMock: ReturnType<typeof stubApi>) {
  return fetchMock.mock.calls.filter(([, init]) => init?.method === "PUT").map(([, init]) => JSON.parse(init?.body as string) as PutBody);
}

const app = (label: string) => screen.findByRole("switch", { name: `${label}: In-app` });
const email = (label: string) => screen.getByRole("switch", { name: `${label}: Email` });
const MENTIONED = "Someone mentions me";
const REPLIED = "A reply in a thread I watch";
const ROOM_ADDED = "Someone adds me to a room";

describe("NotificationPreferences page", () => {
  beforeEach(() => {
    localStorage.setItem("adder.auth.token", "fake-token");
    localStorage.setItem("adder.auth.userId", "5");
  });
  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
    localStorage.clear();
  });

  test("shows the grid: every kind in a group with an In-app and an Email switch at the stored state, and the heading outline", async () => {
    stubApi(prefs({ MENTIONED: { inApp: false, email: true } }));
    renderWithProviders(<NotificationPreferences />);
    expect(await app(MENTIONED)).not.toBeChecked();
    expect(email(MENTIONED)).toBeChecked();
    expect(email(REPLIED)).not.toBeChecked();
    expect(screen.getByRole("switch", { name: `${REPLIED}: In-app` })).toBeChecked();
    expect(headingOutline()).toEqual([
      [2, "Notification preferences"],
      [3, "Mentions"],
      [3, "Threads you watch"],
      [3, "Threads handed to you"],
      [3, "Rooms"],
    ]);
    // Every kind of the contract is drawn once per column, in a named table.
    expect(screen.getAllByRole("switch")).toHaveLength((KINDS.length + 1) * 2);
    expect(within(screen.getByRole("table", { name: "Rooms" })).getByText("A new thread in a room I am in")).toBeInTheDocument();
    expect(within(screen.getByRole("table", { name: "Rooms" })).getByText(ROOM_ADDED)).toBeInTheDocument();
    expect(within(screen.getByRole("table", { name: "Mentions" })).getByText("Someone quotes my message")).toBeInTheDocument();
    expect(screen.queryByText(/Email is off on this server/)).not.toBeInTheDocument();
    // Nothing changed: nothing to save.
    expect(screen.getByRole("button", { name: "Save" })).toBeDisabled();
  });

  test("room membership switches use their dedicated response property, fall back for an older response, and save outside legacy pairs", async () => {
    const fetchMock = stubApi(prefs({}, true, { inApp: false, email: true }));
    const user = userEvent.setup();
    renderWithProviders(<NotificationPreferences />);
    expect(await app(ROOM_ADDED)).not.toBeChecked();
    expect(email(ROOM_ADDED)).toBeChecked();
    await user.click(await app(ROOM_ADDED));
    await user.click(email(ROOM_ADDED));
    await user.click(screen.getByRole("button", { name: "Save" }));
    await waitFor(() => expect(putBodies(fetchMock)).toHaveLength(1));
    const [body] = putBodies(fetchMock);
    expect(body.roomMembership).toEqual({ inApp: true, email: false });
    expect([...body.disabled, ...body.enabled].some((pair) => pair.type === "ROOM_ADDED")).toBe(false);

    const legacy = prefs();
    delete legacy.roomMembership;
    vi.unstubAllGlobals();
    stubApi(legacy);
    renderWithProviders(<NotificationPreferences />);
    const roomSwitches = await screen.findAllByRole("switch", { name: `${ROOM_ADDED}: In-app` });
    expect(roomSwitches.at(-1)).toBeChecked();
    expect(screen.getAllByRole("switch", { name: `${ROOM_ADDED}: Email` }).at(-1)).not.toBeChecked();
  });

  test("a change saves EVERY pair, the off ones as disabled and the on ones as enabled, toasts and re-reads", async () => {
    const toast = vi.spyOn(notifications, "show").mockReturnValue("id");
    const fetchMock = stubApi(prefs());
    const user = userEvent.setup();
    renderWithProviders(<NotificationPreferences />);
    await user.click(await app(MENTIONED));
    await user.click(email(REPLIED));
    await user.click(screen.getByRole("button", { name: "Save" }));
    await waitFor(() => expect(toast).toHaveBeenCalledWith(expect.objectContaining({ message: "Notification preferences saved", color: "teal" })));
    const [body] = putBodies(fetchMock);
    expect(body.disabled.length + body.enabled.length).toBe(KINDS.length * 2);
    expect(body.disabled).toContainEqual({ type: "MENTIONED", channel: "IN_APP" });
    expect(body.enabled).toContainEqual({ type: "THREAD_REPLIED", channel: "EMAIL" });
    expect(body.enabled).toContainEqual({ type: "THREAD_CLOSED", channel: "IN_APP" });
    expect(body.disabled).toContainEqual({ type: "THREAD_CLOSED", channel: "EMAIL" });
    await waitFor(() => expect(screen.getByRole("switch", { name: `${MENTIONED}: In-app` })).not.toBeChecked());
    expect(email(REPLIED)).toBeChecked();
    expect(screen.getByRole("button", { name: "Save" })).toBeDisabled();
  });

  test("a group's All on and All off switch its column and leave the other column alone", async () => {
    const fetchMock = stubApi(prefs());
    vi.spyOn(notifications, "show").mockReturnValue("id");
    const user = userEvent.setup();
    renderWithProviders(<NotificationPreferences />);
    await app(MENTIONED);
    await user.click(screen.getByRole("button", { name: "All on: Email in Mentions" }));
    for (const label of [MENTIONED, "Someone quotes my message", "Someone mentions my team", "Someone mentions everyone in a room I am in"]) {
      expect(email(label)).toBeChecked();
      expect(screen.getByRole("switch", { name: `${label}: In-app` })).toBeChecked();
    }
    expect(email(REPLIED)).not.toBeChecked();
    await user.click(screen.getByRole("button", { name: "All off: In-app in Mentions" }));
    expect(screen.getByRole("switch", { name: `${MENTIONED}: In-app` })).not.toBeChecked();
    expect(email(MENTIONED)).toBeChecked();
    await user.click(screen.getByRole("button", { name: "Save" }));
    await waitFor(() => expect(putBodies(fetchMock)).toHaveLength(1));
  });

  test("with no email on the server it says so, disables the email column and its bulk buttons, and keeps the app column live", async () => {
    stubApi(prefs({ MENTIONED: { email: true } }, false));
    renderWithProviders(<NotificationPreferences />);
    expect(await screen.findByText(/Email is off on this server/)).toBeInTheDocument();
    expect(email(MENTIONED)).toBeDisabled();
    expect(email(MENTIONED)).toBeChecked();
    expect(screen.getByRole("button", { name: "All on: Email in Mentions" })).toBeDisabled();
    expect(screen.getByRole("switch", { name: `${MENTIONED}: In-app` })).toBeEnabled();
    expect(screen.getByRole("button", { name: "All on: In-app in Mentions" })).toBeEnabled();
  });

  test("a failed load shows an inline alert and no switches", async () => {
    vi.stubGlobal("fetch", vi.fn(() => Promise.resolve(jsonResponse(500, { title: "x", status: 500 }))));
    renderWithProviders(<NotificationPreferences />);
    expect(await screen.findByRole("alert")).toBeInTheDocument();
    expect(screen.queryByRole("switch")).not.toBeInTheDocument();
  });

  test("a failed save shows the status inline, keeps the toggles and sends no toast", async () => {
    const toast = vi.spyOn(notifications, "show").mockReturnValue("id");
    stubApi(prefs(), 500);
    const user = userEvent.setup();
    renderWithProviders(<NotificationPreferences />);
    await user.click(await app(MENTIONED));
    await user.click(screen.getByRole("button", { name: "Save" }));
    expect(await screen.findByText("Saving the choices failed (500)")).toBeInTheDocument();
    expect(screen.getByRole("switch", { name: `${MENTIONED}: In-app` })).not.toBeChecked();
    expect(toast).not.toHaveBeenCalled();
  });

  test("a network failure on save has its own words", async () => {
    const fetchMock = stubApi(prefs());
    const user = userEvent.setup();
    renderWithProviders(<NotificationPreferences />);
    await user.click(await app(MENTIONED));
    fetchMock.mockImplementation((_url: string, init?: RequestInit) =>
      init?.method === "PUT" ? Promise.reject(new TypeError("Failed to fetch")) : Promise.resolve(jsonResponse(200, prefs())),
    );
    await user.click(screen.getByRole("button", { name: "Save" }));
    expect(await screen.findByText(/Saving the choices failed\. Check your connection/)).toBeInTheDocument();
  });
});
