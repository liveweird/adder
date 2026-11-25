import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { screen, waitFor } from "@testing-library/react";
import NotificationsButton from "./NotificationsButton";
import type { NotificationFeedItem } from "../api/notifications";
import { ChannelStub } from "../test/broadcast";
import { jsonResponse } from "../test/http";
import { renderWithProviders } from "../test/render";
import { openSeenSync } from "../utils/seenSync";

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
let page = { items: [notice], page: 1, pageSize: 20, total: 2 };

let writeStatus = 204;
const calls: string[] = [];

function api(url: string, init?: RequestInit): Promise<Response> {
  calls.push(`${init?.method ?? "GET"} ${url}`);
  if (init?.method === "POST") return Promise.resolve(writeStatus === 204 ? new Response(null, { status: 204 }) : jsonResponse(writeStatus, { title: "Boom", status: writeStatus }));
  if (url.startsWith("/api/v1/notifications/feed?")) return Promise.resolve(jsonResponse(200, page));
  return Promise.resolve(jsonResponse(404, { title: "Not found", status: 404 }));
}

// The person's other tabs hear of a seen-write that landed (1.2 D9), so a hidden tab's title count follows; a failed write tells nobody.
describe("NotificationsButton: telling the person's other tabs", () => {
  let close: () => void;
  beforeEach(() => {
    writeStatus = 204;
    page = { items: [notice], page: 1, pageSize: 20, total: 2 };
    calls.length = 0;
    localStorage.setItem("adder.auth.token", "fake-token");
    vi.stubGlobal("fetch", vi.fn(api));
    close = openSeenSync(7, () => undefined, (name) => new ChannelStub(name));
  });
  afterEach(() => {
    close();
    ChannelStub.reset();
    vi.unstubAllGlobals();
    localStorage.clear();
  });
  const announcements = () => ChannelStub.open[0].posted;

  async function openDrawer() {
    const user = userEvent.setup();
    renderWithProviders(<NotificationsButton />, { route: "/rooms/3" });
    await user.click(await screen.findByRole("button", { name: /^Notifications/ }));
    return user;
  }

  test("a notice marked seen and Mark all as seen each announce once, after the write landed", async () => {
    const user = await openDrawer();
    await user.click(await screen.findByRole("link", { name: /Ada mentioned you in #42 Awaria bazy/ }));
    await waitFor(() => expect(announcements()).toEqual([{ v: 1, userId: 7 }]));
    await user.click(await screen.findByRole("button", { name: /^Notifications/ }));
    await user.click(await screen.findByRole("button", { name: "Mark all as seen" }));
    await waitFor(() => expect(announcements()).toHaveLength(2));
  });

  test("a write that failed announces nothing", async () => {
    writeStatus = 500;
    const user = await openDrawer();
    await user.click(await screen.findByRole("button", { name: "Mark all as seen" }));
    await screen.findByText("Could not mark the notifications as seen.");
    await user.click(await screen.findByRole("link", { name: /Ada mentioned you in #42 Awaria bazy/ }));
    await waitFor(() => expect(calls).toContain("POST /api/v1/notifications/77/seen"));
    expect(announcements()).toEqual([]);
  });

  test("a grouped reply clears its room-thread snapshot through the returned ceiling", async () => {
    page = {
      items: [{ ...notice, id: 90, type: "THREAD_REPLIED", actorName: "Newest actor", messageId: 880, replyCount: 12, replyFloorId: 79, replyCeilingId: 90 }],
      page: 1,
      pageSize: 20,
      total: 1,
    };
    const user = await openDrawer();
    const grouped = await screen.findByRole("link", { name: "Unseen 12 new replies in #42 Awaria bazy" });
    expect(grouped).toHaveAttribute("href", "/rooms/12/threads/42?message=880");
    expect(grouped.closest("li")).not.toHaveTextContent("Newest actor");
    await user.click(grouped);
    await waitFor(() => expect(calls).toContain("POST /api/v1/notifications/reply-groups/12/42/seen"));
    expect(announcements()).toEqual([{ v: 1, userId: 7 }]);
  });
});
