import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { useLocation } from "react-router-dom";
import { screen, waitFor, within } from "@testing-library/react";
import NotificationsButton from "./NotificationsButton";
import type { NotificationItem } from "../api/notifications";
import { jsonResponse } from "../test/http";
import { renderWithProviders } from "../test/render";
import { browserTimeZone, formatDateTime } from "../utils/formatDate";

const ZONE = browserTimeZone();
const AT = 1_795_000_000_000;

const notice = (over: Partial<NotificationItem> = {}): NotificationItem => ({
  id: 77,
  type: "MENTIONED",
  wasSeen: false,
  createdAt: AT,
  actorId: 7,
  actorName: "Ada",
  roomId: 12,
  roomName: "Payments outage",
  threadNumber: 42,
  threadTitle: "Awaria bazy",
  messageId: 991,
  ...over,
});
const page = (items: NotificationItem[], total = items.length, pageNo = 1) => ({ items, page: pageNo, pageSize: 20, total });

function Where() {
  const { pathname, search } = useLocation();
  return <output aria-label="where">{pathname + search}</output>;
}

let unseenPage = page([notice()]);
let listPage = page([notice(), notice({ id: 76, wasSeen: true, type: "THREAD_ASSIGNED", messageId: null, actorName: "Bob", threadNumber: 5, threadTitle: "Runbook", roomName: "Ops" })]);
let listStatus = 200;
let markAllStatus = 204;
const calls: string[] = [];

function api(url: string, init?: RequestInit): Promise<Response> {
  const method = init?.method ?? "GET";
  calls.push(`${method} ${url}`);
  if (method === "POST" && url === "/api/v1/notifications/seen-all") return Promise.resolve(markAllStatus === 204 ? new Response(null, { status: 204 }) : jsonResponse(markAllStatus, { title: "Boom", status: markAllStatus }));
  if (method === "POST" && /^\/api\/v1\/notifications\/\d+\/seen$/.test(url)) return Promise.resolve(new Response(null, { status: 204 }));
  if (url.startsWith("/api/v1/notifications?") && url.includes("wasSeen=false")) return Promise.resolve(jsonResponse(200, unseenPage));
  if (url.startsWith("/api/v1/notifications?")) {
    if (listStatus !== 200) return Promise.resolve(jsonResponse(listStatus, { title: "Boom", status: listStatus }));
    const pageNo = Number(new URL(url, "http://x").searchParams.get("page"));
    return Promise.resolve(jsonResponse(200, pageNo > 1 ? { ...listPage, page: pageNo, items: [notice({ id: 10, threadTitle: "Second page" })] } : listPage));
  }
  return Promise.resolve(jsonResponse(404, { title: "Not found", status: 404 }));
}

function renderBell() {
  return renderWithProviders(
    <>
      <NotificationsButton />
      <Where />
    </>,
    { route: "/rooms/3" },
  );
}

const bell = () => screen.findByRole("button", { name: /^Notifications/ });

describe("NotificationsButton", () => {
  beforeEach(() => {
    unseenPage = page([notice()]);
    listPage = page([notice(), notice({ id: 76, wasSeen: true, type: "THREAD_ASSIGNED", messageId: null, actorName: "Bob", threadNumber: 5, threadTitle: "Runbook", roomName: "Ops" })]);
    listStatus = 200;
    markAllStatus = 204;
    calls.length = 0;
    localStorage.setItem("adder.auth.token", "fake-token");
    vi.stubGlobal("fetch", vi.fn(api));
  });
  afterEach(() => {
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  test("the button is labelled with the unseen total, which is also the badge", async () => {
    unseenPage = page([notice()], 3);
    renderBell();
    expect(await screen.findByRole("button", { name: "Notifications (3 unseen)" })).toBeInTheDocument();
    expect(screen.getByText("3")).toBeInTheDocument();
    // It asks for the unseen ones only, a page of 100, newest first by the server's default.
    expect(calls).toContain("GET /api/v1/notifications?page=1&pageSize=100&wasSeen=false");
  });

  test("past the server's cap the badge reads 999+ and the label says more than 999", async () => {
    unseenPage = page([notice()], 1000);
    renderBell();
    expect(await screen.findByRole("button", { name: "Notifications (999+ unseen)" })).toBeInTheDocument();
    expect(screen.getByText("999+")).toBeInTheDocument();
  });

  test("999 is shown as it is", async () => {
    unseenPage = page([notice()], 999);
    renderBell();
    expect(await screen.findByText("999")).toBeInTheDocument();
  });

  test("with nothing unseen there is no badge, and the label says 0", async () => {
    unseenPage = page([], 0);
    renderBell();
    expect(await screen.findByRole("button", { name: "Notifications (0 unseen)" })).toBeInTheDocument();
    expect(screen.queryByText("0")).not.toBeInTheDocument();
  });

  test("the unseen read waits for a signed-in session", async () => {
    localStorage.clear();
    renderBell();
    expect(await screen.findByRole("button", { name: /^Notifications/ })).toBeInTheDocument();
    expect(calls).toEqual([]);
  });

  test("the drawer lists who, what, the thread, the room and the time, as text", async () => {
    unseenPage = page([notice()], 1);
    listPage = page([notice({ actorName: "<b>Ada</b>", threadTitle: "**Awaria**" }), notice({ id: 76, wasSeen: true, type: "THREAD_ASSIGNED", messageId: null, actorName: "Bob", threadNumber: 5, threadTitle: "Runbook", roomName: "Ops" })]);
    const user = userEvent.setup();
    renderBell();
    await user.click(await bell());
    const drawer = await screen.findByRole("dialog", { name: "Notifications" });
    const mention = await within(drawer).findByRole("link", { name: /<b>Ada<\/b> mentioned you in #42 \*\*Awaria\*\*/ });
    expect(mention).toHaveTextContent(`Payments outage · ${formatDateTime(AT, ZONE)}`);
    expect(within(mention).getByText("Unseen")).toBeInTheDocument();
    expect(within(drawer).getByRole("link", { name: /Bob made you the owner of #5 Runbook/ })).toHaveTextContent("Ops ·");
    expect(drawer.querySelector("b")).toBeNull();
    // A seen one has no "Unseen" word.
    expect(within(within(drawer).getByRole("link", { name: /Bob made you/ })).queryByText("Unseen")).not.toBeInTheDocument();
  });

  test("a click marks the notice seen and opens the message the notice is about", async () => {
    const user = userEvent.setup();
    renderBell();
    await user.click(await bell());
    await user.click(await screen.findByRole("link", { name: /Ada mentioned you in #42 Awaria bazy/ }));
    expect(screen.getByLabelText("where")).toHaveTextContent("/rooms/12/threads/42?message=991");
    await waitFor(() => expect(calls).toContain("POST /api/v1/notifications/77/seen"));
    // The drawer closed, and the badge and list were re-read.
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    await waitFor(() => expect(calls.filter((call) => call.includes("wasSeen=false")).length).toBeGreaterThan(1));
  });

  test("a hand-over has no message: it opens the thread, and a seen notice is not marked again", async () => {
    const user = userEvent.setup();
    renderBell();
    await user.click(await bell());
    await user.click(await screen.findByRole("link", { name: /Bob made you the owner of #5 Runbook/ }));
    expect(screen.getByLabelText("where")).toHaveTextContent(/^\/rooms\/12\/threads\/5$/);
    expect(calls.some((call) => call.startsWith("POST"))).toBe(false);
  });

  test("Mark all as seen sends the one write and re-reads", async () => {
    unseenPage = page([notice()], 2);
    const user = userEvent.setup();
    renderBell();
    await user.click(await bell());
    await user.click(await screen.findByRole("button", { name: "Mark all as seen" }));
    await waitFor(() => expect(calls).toContain("POST /api/v1/notifications/seen-all"));
    await waitFor(() => expect(calls.filter((call) => call.includes("wasSeen=false")).length).toBeGreaterThan(1));
  });

  test("a failed Mark all as seen says so, inline", async () => {
    markAllStatus = 500;
    const user = userEvent.setup();
    renderBell();
    await user.click(await bell());
    await user.click(await screen.findByRole("button", { name: "Mark all as seen" }));
    expect(await screen.findByText("Could not mark the notifications as seen.")).toBeInTheDocument();
  });

  test("with nothing unseen there is no Mark all button", async () => {
    unseenPage = page([], 0);
    listPage = page([notice({ wasSeen: true })]);
    const user = userEvent.setup();
    renderBell();
    await user.click(await bell());
    await screen.findByRole("link", { name: /Ada mentioned you/ });
    expect(screen.queryByRole("button", { name: "Mark all as seen" })).not.toBeInTheDocument();
  });

  test("an empty list says so", async () => {
    unseenPage = page([], 0);
    listPage = page([]);
    const user = userEvent.setup();
    renderBell();
    await user.click(await bell());
    expect(await screen.findByText("You have no notifications.")).toBeInTheDocument();
  });

  test("a failed read says so, with the status words, and no raw error", async () => {
    listStatus = 500;
    const user = userEvent.setup();
    renderBell();
    await user.click(await bell());
    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent("Could not load the notifications.");
    expect(alert).not.toHaveTextContent("Boom");
  });

  test("a notice of a kind this build does not know shows its raw name", async () => {
    listPage = page([{ ...notice(), type: "FROM_THE_FUTURE" as NotificationItem["type"] }]);
    const user = userEvent.setup();
    renderBell();
    await user.click(await bell());
    expect(await screen.findByRole("link", { name: /FROM_THE_FUTURE/ })).toBeInTheDocument();
  });

  test("past 20 notices the drawer pages, 20 at a time", async () => {
    listPage = page([notice()], 45);
    const user = userEvent.setup();
    renderBell();
    await user.click(await bell());
    await screen.findByRole("link", { name: /Ada mentioned you in #42 Awaria bazy/ });
    expect(calls).toContain("GET /api/v1/notifications?page=1&pageSize=20");
    await user.click(screen.getByRole("button", { name: "3" }));
    expect(await screen.findByRole("link", { name: /Second page/ })).toBeInTheDocument();
    expect(calls).toContain("GET /api/v1/notifications?page=3&pageSize=20");
  });

  test("a small list has no pager", async () => {
    const user = userEvent.setup();
    renderBell();
    await user.click(await bell());
    await screen.findByRole("link", { name: /Ada mentioned you/ });
    expect(screen.queryByRole("button", { name: "2" })).not.toBeInTheDocument();
  });

  test("the drawer's close button has a name, and closing returns focus to the bell", async () => {
    const user = userEvent.setup();
    renderBell();
    const button = await bell();
    await user.click(button);
    const drawer = await screen.findByRole("dialog", { name: "Notifications" });
    await user.click(within(drawer).getByRole("button", { name: "Close" }));
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    await waitFor(() => expect(button).toHaveFocus());
  });

  test("the drawer is reachable from the keyboard", async () => {
    const user = userEvent.setup();
    renderBell();
    (await bell()).focus();
    await user.keyboard("{Enter}");
    expect(await screen.findByRole("dialog", { name: "Notifications" })).toBeInTheDocument();
    await user.keyboard("{Escape}");
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
  });
});
