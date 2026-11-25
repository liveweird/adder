import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { screen, waitFor, within } from "@testing-library/react";
import NotificationsButton from "./NotificationsButton";
import type { NotificationFeedItem } from "../api/notifications";
import { jsonResponse } from "../test/http";
import { renderWithProviders } from "../test/render";

const notice: NotificationFeedItem = {
  id: 77,
  type: "MENTIONED",
  seen: false,
  createdAt: 1_795_000_000_000,
  actorId: 7,
  actorName: "Ada",
  roomId: 12,
  roomName: "Payments outage",
  threadNumber: 42,
  threadTitle: "Awaria bazy",
  messageId: 991,
};
const page = { items: [notice], page: 1, pageSize: 20, total: 1 };

/** `notification-read` is a per-user bucket: a refused list is a 429 with `Retry-After`, a pause rather than a failure. */
let listStatus = 200;
const calls: string[] = [];

function api(url: string, init?: RequestInit): Promise<Response> {
  calls.push(`${init?.method ?? "GET"} ${url}`);
  if (init?.method === "POST") return Promise.resolve(new Response(null, { status: 204 }));
  if (url.startsWith("/api/v1/notifications/feed?") && url.includes("seen=false")) return Promise.resolve(jsonResponse(200, page));
  if (url.startsWith("/api/v1/notifications/feed?")) {
    return Promise.resolve(listStatus === 429 ? jsonResponse(429, { title: "Slow down", status: 429 }, { "Retry-After": "1" }) : jsonResponse(200, page));
  }
  return Promise.resolve(jsonResponse(404, { title: "Not found", status: 404 }));
}

const listReads = () => calls.filter((c) => c.startsWith("GET /api/v1/notifications/feed?") && c.includes("pageSize=20")).length;

describe("NotificationsButton: a 429 on the notice list", () => {
  beforeEach(() => {
    listStatus = 200;
    calls.length = 0;
    localStorage.setItem("adder.auth.token", "fake-token");
    vi.stubGlobal("fetch", vi.fn(api));
  });
  afterEach(() => {
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  test("schedules ONE re-read after Retry-After, and the rows already shown stay while it is refused", async () => {
    const user = userEvent.setup();
    renderWithProviders(<NotificationsButton />, { route: "/rooms/3" });
    await user.click(await screen.findByRole("button", { name: /^Notifications/ }));
    const drawer = await screen.findByRole("dialog", { name: "Notifications" });
    await within(drawer).findByRole("link", { name: /Ada mentioned you/ });
    const before = listReads();

    // The next read of the list is refused with Retry-After: 1. The rows already shown stay and no alert replaces them.
    listStatus = 429;
    await user.click(within(drawer).getByRole("button", { name: "Mark all as seen" }));
    await waitFor(() => expect(listReads()).toBe(before + 1));
    expect(within(drawer).getByRole("link", { name: /Ada mentioned you/ })).toBeInTheDocument();
    expect(within(drawer).queryByRole("alert")).not.toBeInTheDocument();

    // After the pause ONE more read goes out (the server answers fine now) and nothing else retried in between.
    listStatus = 200;
    await waitFor(() => expect(listReads()).toBe(before + 2), { timeout: 3_000 });
    await new Promise((resolve) => setTimeout(resolve, 300));
    expect(listReads()).toBe(before + 2);
  });

  test("with nothing to show yet it says so, then re-reads once after Retry-After and shows the rows", async () => {
    listStatus = 429;
    const user = userEvent.setup();
    renderWithProviders(<NotificationsButton />, { route: "/rooms/3" });
    await user.click(await screen.findByRole("button", { name: /^Notifications/ }));
    const drawer = await screen.findByRole("dialog", { name: "Notifications" });
    expect(await within(drawer).findByRole("alert")).toHaveTextContent("Could not load the notifications.");
    listStatus = 200;
    expect(await within(drawer).findByRole("link", { name: /Ada mentioned you/ }, { timeout: 3_000 })).toBeInTheDocument();
    expect(within(drawer).queryByRole("alert")).not.toBeInTheDocument();
  });
});
