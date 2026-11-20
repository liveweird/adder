import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { screen, waitFor } from "@testing-library/react";
import { notifications } from "@mantine/notifications";
import NotificationPreferences from "./NotificationPreferences";
import { headingOutline } from "../test/headings";
import { jsonResponse } from "../test/http";
import { renderWithProviders } from "../test/render";

const PATH = "/api/v1/users/5/notification-preferences";
const MENTIONED = "Someone mentions me";
const ASSIGNED = "Someone makes me the owner of a thread";

function prefs(mentioned: boolean, assigned: boolean, emailAvailable = true) {
  return { emailAvailable, kinds: [{ type: "MENTIONED", email: mentioned }, { type: "THREAD_ASSIGNED", email: assigned }] };
}

/** GET answers `stored`; a PUT answers `putStatus` and, on a 204, the next GET answers what the PUT disabled. */
function stubApi(stored: ReturnType<typeof prefs>, putStatus = 204) {
  let current = stored;
  const fetchMock = vi.fn((url: string, init?: RequestInit) => {
    if (url !== PATH) return Promise.resolve(jsonResponse(404, {}));
    if (init?.method === "PUT") {
      if (putStatus !== 204) return Promise.resolve(jsonResponse(putStatus, { title: "x", status: putStatus }));
      const off = (JSON.parse(init.body as string) as { disabled: { type: string }[] }).disabled.map((d) => d.type);
      current = { ...current, kinds: current.kinds.map((k) => ({ ...k, email: !off.includes(k.type) })) };
      return Promise.resolve(new Response(null, { status: 204 }));
    }
    return Promise.resolve(jsonResponse(200, current));
  });
  vi.stubGlobal("fetch", fetchMock);
  return fetchMock;
}

function putBodies(fetchMock: ReturnType<typeof stubApi>) {
  return fetchMock.mock.calls
    .filter(([, init]) => init?.method === "PUT")
    .map(([, init]) => JSON.parse(init?.body as string) as unknown);
}

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

  test("shows one switch per kind with the stored state, and the h2 outline", async () => {
    stubApi(prefs(true, false));
    renderWithProviders(<NotificationPreferences />);
    expect(await screen.findByRole("switch", { name: MENTIONED })).toBeChecked();
    expect(screen.getByRole("switch", { name: ASSIGNED })).not.toBeChecked();
    expect(screen.getByRole("group", { name: "Email me when" })).toBeInTheDocument();
    expect(headingOutline()).toEqual([[2, "Notification emails"], [3, "Email me when"]]);
    expect(screen.queryByText(/Email is off on this server/)).not.toBeInTheDocument();
    // Nothing changed: nothing to save.
    expect(screen.getByRole("button", { name: "Save" })).toBeDisabled();
  });

  test("a change saves the whole set of switched-off kinds, toasts and re-reads", async () => {
    const toast = vi.spyOn(notifications, "show").mockReturnValue("id");
    const fetchMock = stubApi(prefs(true, true));
    const user = userEvent.setup();
    renderWithProviders(<NotificationPreferences />);
    await user.click(await screen.findByRole("switch", { name: ASSIGNED }));
    await user.click(screen.getByRole("button", { name: "Save" }));
    await waitFor(() => expect(toast).toHaveBeenCalledWith(expect.objectContaining({ message: "Notification emails saved", color: "teal" })));
    expect(putBodies(fetchMock)).toEqual([{ disabled: [{ type: "THREAD_ASSIGNED", channel: "EMAIL" }] }]);
    await waitFor(() => expect(screen.getByRole("switch", { name: ASSIGNED })).not.toBeChecked());
    expect(screen.getByRole("switch", { name: MENTIONED })).toBeChecked();
    expect(screen.getByRole("button", { name: "Save" })).toBeDisabled();
  });

  test("switching everything back on saves an empty disabled list", async () => {
    vi.spyOn(notifications, "show").mockReturnValue("id");
    const fetchMock = stubApi(prefs(false, false));
    const user = userEvent.setup();
    renderWithProviders(<NotificationPreferences />);
    await user.click(await screen.findByRole("switch", { name: MENTIONED }));
    await user.click(screen.getByRole("switch", { name: ASSIGNED }));
    await user.click(screen.getByRole("button", { name: "Save" }));
    await waitFor(() => expect(putBodies(fetchMock)).toEqual([{ disabled: [] }]));
  });

  test("with no email on the server it says so and disables the switches", async () => {
    stubApi(prefs(true, true, false));
    renderWithProviders(<NotificationPreferences />);
    expect(await screen.findByText(/Email is off on this server/)).toBeInTheDocument();
    expect(screen.getByRole("switch", { name: MENTIONED })).toBeDisabled();
    expect(screen.getByRole("switch", { name: ASSIGNED })).toBeDisabled();
    expect(screen.getByRole("switch", { name: MENTIONED })).toBeChecked();
  });

  test("a failed load shows an inline alert and no switches", async () => {
    vi.stubGlobal("fetch", vi.fn(() => Promise.resolve(jsonResponse(500, { title: "x", status: 500 }))));
    renderWithProviders(<NotificationPreferences />);
    expect(await screen.findByRole("alert")).toBeInTheDocument();
    expect(screen.queryByRole("switch")).not.toBeInTheDocument();
  });

  test("a failed save shows the status inline, keeps the toggles and sends no toast", async () => {
    const toast = vi.spyOn(notifications, "show").mockReturnValue("id");
    stubApi(prefs(true, true), 500);
    const user = userEvent.setup();
    renderWithProviders(<NotificationPreferences />);
    await user.click(await screen.findByRole("switch", { name: MENTIONED }));
    await user.click(screen.getByRole("button", { name: "Save" }));
    expect(await screen.findByText("Saving the choices failed (500)")).toBeInTheDocument();
    expect(screen.getByRole("switch", { name: MENTIONED })).not.toBeChecked();
    expect(toast).not.toHaveBeenCalled();
  });

  test("a network failure on save has its own words", async () => {
    const fetchMock = stubApi(prefs(true, true));
    const user = userEvent.setup();
    renderWithProviders(<NotificationPreferences />);
    await user.click(await screen.findByRole("switch", { name: MENTIONED }));
    fetchMock.mockImplementation((_url: string, init?: RequestInit) =>
      init?.method === "PUT" ? Promise.reject(new TypeError("Failed to fetch")) : Promise.resolve(jsonResponse(200, prefs(true, true))),
    );
    await user.click(screen.getByRole("button", { name: "Save" }));
    expect(await screen.findByText(/Saving the choices failed\. Check your connection/)).toBeInTheDocument();
  });
});
