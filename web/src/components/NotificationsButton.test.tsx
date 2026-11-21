import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { useLocation } from "react-router-dom";
import { act, screen, waitFor, within } from "@testing-library/react";
import { QueryClient } from "@tanstack/react-query";
import NotificationsButton from "./NotificationsButton";
import type { NotificationItem } from "../api/notifications";
import { clearSession } from "../api/session";
import i18n from "../i18n";
import { jsonResponse } from "../test/http";
import { renderWithProviders } from "../test/render";
import { browserTimeZone, formatDateTime } from "../utils/formatDate";
import { bindSessionQueryCache } from "../utils/sessionQueryCache";

const ZONE = browserTimeZone();
const AT = 1_795_000_000_000;

const notice = (over: Partial<NotificationItem> = {}): NotificationItem => ({
  id: 77,
  type: "MENTIONED",
  seen: false,
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
  const { pathname, search, state } = useLocation();
  return (
    <>
      <output aria-label="where">{pathname + search}</output>
      <output aria-label="state">{JSON.stringify(state)}</output>
    </>
  );
}

let unseenPage = page([notice()]);
let listPage = page([notice(), notice({ id: 76, seen: true, type: "THREAD_ASSIGNED", messageId: null, actorName: "Bob", threadNumber: 5, threadTitle: "Runbook", roomName: "Ops" })]);
let listStatus = 200;
let markAllStatus = 204;
let seenStatus = 204;
let unseenStatus = 200;
/** Overrides the list's answer for a page (the default serves `listPage`, and a made-up second page for any page past 1). */
let serveListPage: ((pageNo: number) => unknown) | null = null;
const calls: string[] = [];

function api(url: string, init?: RequestInit): Promise<Response> {
  const method = init?.method ?? "GET";
  calls.push(`${method} ${url}`);
  if (method === "POST" && url === "/api/v1/notifications/seen-all") return Promise.resolve(markAllStatus === 204 ? new Response(null, { status: 204 }) : jsonResponse(markAllStatus, { title: "Boom", status: markAllStatus }));
  if (method === "POST" && /^\/api\/v1\/notifications\/\d+\/seen$/.test(url)) return Promise.resolve(seenStatus === 204 ? new Response(null, { status: 204 }) : jsonResponse(seenStatus, { title: "Boom", status: seenStatus }));
  if (url.startsWith("/api/v1/notifications?") && url.includes("seen=false")) return Promise.resolve(unseenStatus === 200 ? jsonResponse(200, unseenPage) : jsonResponse(unseenStatus, { title: "Boom", status: unseenStatus }));
  if (url.startsWith("/api/v1/notifications?")) {
    if (listStatus !== 200) return Promise.resolve(jsonResponse(listStatus, { title: "Boom", status: listStatus }));
    const pageNo = Number(new URL(url, "http://x").searchParams.get("page"));
    if (serveListPage) return Promise.resolve(jsonResponse(200, serveListPage(pageNo)));
    return Promise.resolve(jsonResponse(200, pageNo > 1 ? { ...listPage, page: pageNo, items: [notice({ id: 10, threadTitle: "Second page" })] } : listPage));
  }
  return Promise.resolve(jsonResponse(404, { title: "Not found", status: 404 }));
}

function renderBell(route = "/rooms/3", queryClient?: QueryClient) {
  return renderWithProviders(
    <>
      <NotificationsButton />
      <Where />
    </>,
    { route, queryClient },
  );
}

const bell = () => screen.findByRole("button", { name: /^Notifications/ });

describe("NotificationsButton", () => {
  beforeEach(() => {
    unseenPage = page([notice()]);
    listPage = page([notice(), notice({ id: 76, seen: true, type: "THREAD_ASSIGNED", messageId: null, actorName: "Bob", threadNumber: 5, threadTitle: "Runbook", roomName: "Ops" })]);
    listStatus = 200;
    markAllStatus = 204;
    seenStatus = 204;
    unseenStatus = 200;
    serveListPage = null;
    calls.length = 0;
    localStorage.setItem("adder.auth.token", "fake-token");
    vi.stubGlobal("fetch", vi.fn(api));
  });
  afterEach(async () => {
    vi.unstubAllGlobals();
    localStorage.clear();
    await i18n.changeLanguage("en");
  });

  test("the button is labelled with the unseen total, which is also the badge", async () => {
    unseenPage = page([notice()], 3);
    renderBell();
    expect(await screen.findByRole("button", { name: "Notifications (3 unseen)" })).toBeInTheDocument();
    expect(screen.getByText("3").closest("[data-unread-cue=count]")).not.toBeNull();
    // It asks for the unseen ones only, a page of 100, newest first by the server's default.
    expect(calls).toContain("GET /api/v1/notifications?page=1&pageSize=100&seen=false");
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
    listPage = page([notice({ actorName: "<b>Ada</b>", threadTitle: "**Awaria**" }), notice({ id: 76, seen: true, type: "THREAD_ASSIGNED", messageId: null, actorName: "Bob", threadNumber: 5, threadTitle: "Runbook", roomName: "Ops" })]);
    const user = userEvent.setup();
    renderBell();
    await user.click(await bell());
    const drawer = await screen.findByRole("dialog", { name: "Notifications" });
    const mention = await within(drawer).findByRole("link", { name: /<b>Ada<\/b> mentioned you in #42 \*\*Awaria\*\*/ });
    expect(mention).toHaveTextContent(`Payments outage · ${formatDateTime(AT, ZONE)}`);
    expect(within(mention).getByText("Unseen")).toBeInTheDocument();
    // The unseen row carries the shared blue dot; the seen one has none.
    expect(mention.querySelectorAll("[data-unread-cue=dot]")).toHaveLength(1);
    expect(within(drawer).getByRole("link", { name: /Bob made you/ }).querySelector("[data-unread-cue]")).toBeNull();
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
    await waitFor(() => expect(calls.filter((call) => call.includes("seen=false")).length).toBeGreaterThan(1));
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
    await waitFor(() => expect(calls.filter((call) => call.includes("seen=false")).length).toBeGreaterThan(1));
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
    listPage = page([notice({ seen: true })]);
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

  test("the pager's icon-only previous and next buttons have names", async () => {
    listPage = page([notice()], 45);
    const user = userEvent.setup();
    renderBell();
    await user.click(await bell());
    await screen.findByRole("link", { name: /Ada mentioned you in #42 Awaria bazy/ });
    expect(screen.getByRole("button", { name: "Previous page" })).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Next page" }));
    expect(await screen.findByRole("link", { name: /Second page/ })).toBeInTheDocument();
    expect(calls).toContain("GET /api/v1/notifications?page=2&pageSize=20");
  });

  test("a hand-over carries the focus-the-pane state, a message jump does not", async () => {
    const user = userEvent.setup();
    renderBell();
    await user.click(await bell());
    await user.click(await screen.findByRole("link", { name: /Bob made you the owner of #5 Runbook/ }));
    expect(screen.getByLabelText("state")).toHaveTextContent('{"focusPane":true}');
    await user.click(await bell());
    await user.click(await screen.findByRole("link", { name: /Ada mentioned you in #42 Awaria bazy/ }));
    expect(screen.getByLabelText("where")).toHaveTextContent("/rooms/12/threads/42?message=991");
    expect(screen.getByLabelText("state")).toHaveTextContent("null");
  });

  test("a notice that leads where the person already is gives the focus back to the bell", async () => {
    const user = userEvent.setup();
    renderBell("/rooms/12/threads/42?message=991");
    const button = await bell();
    await user.click(button);
    await user.click(await screen.findByRole("link", { name: /Ada mentioned you in #42 Awaria bazy/ }));
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    await waitFor(() => expect(button).toHaveFocus());
  });

  test("a notice that leads elsewhere does not pull the focus back to the bell", async () => {
    const user = userEvent.setup();
    renderBell();
    const button = await bell();
    await user.click(button);
    await user.click(await screen.findByRole("link", { name: /Ada mentioned you in #42 Awaria bazy/ }));
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    expect(button).not.toHaveFocus();
  });

  test("a page that a re-read empties steps back to the last page that exists, not just one", async () => {
    let total = 100;
    serveListPage = (pageNo) => page(total === 100 || pageNo < 3 ? [notice({ id: pageNo, threadTitle: `Row ${pageNo}` })] : [], total, pageNo);
    unseenPage = page([notice()], 2);
    const user = userEvent.setup();
    renderBell();
    await user.click(await bell());
    await screen.findByRole("link", { name: /Row 1/ });
    await user.click(screen.getByRole("button", { name: "5" }));
    await screen.findByRole("link", { name: /Row 5/ });
    // Three pages' worth of notices go; the page the person is on is now past the end (25 notices = 2 pages).
    total = 25;
    await user.click(screen.getByRole("button", { name: "Mark all as seen" }));
    expect(await screen.findByRole("link", { name: /Row 2/ })).toBeInTheDocument();
    expect(calls.some((call) => call.includes("page=4&"))).toBe(false);
    expect(screen.getByRole("button", { name: "2", current: "page" })).toBeInTheDocument();
  });

  test.each([404, 500])("a failed seen write (%i) still re-reads the badge and the list", async (status) => {
    seenStatus = status;
    const user = userEvent.setup();
    renderBell();
    await user.click(await bell());
    await user.click(await screen.findByRole("link", { name: /Ada mentioned you in #42 Awaria bazy/ }));
    await waitFor(() => expect(calls).toContain("POST /api/v1/notifications/77/seen"));
    await waitFor(() => expect(calls.filter((call) => call.includes("seen=false")).length).toBeGreaterThan(1));
  });

  test("the badge text is hidden from assistive technology, and the bell says whether its drawer is open", async () => {
    unseenPage = page([notice()], 3);
    const user = userEvent.setup();
    renderBell();
    const button = await screen.findByRole("button", { name: "Notifications (3 unseen)" });
    expect(screen.getByText("3").closest("[aria-hidden='true']")).not.toBeNull();
    expect(button).toHaveAttribute("aria-expanded", "false");
    await user.click(button);
    await screen.findByRole("dialog", { name: "Notifications" });
    expect(button).toHaveAttribute("aria-expanded", "true");
  });

  test("a Ctrl/Cmd-click marks the notice seen but keeps the drawer, the page and the focus", async () => {
    const user = userEvent.setup();
    renderBell();
    await user.click(await bell());
    const link = await screen.findByRole("link", { name: /Ada mentioned you in #42 Awaria bazy/ });
    await user.keyboard("{Control>}");
    await user.click(link);
    await user.keyboard("{/Control}");
    await waitFor(() => expect(calls).toContain("POST /api/v1/notifications/77/seen"));
    expect(screen.getByRole("dialog", { name: "Notifications" })).toBeInTheDocument();
    expect(screen.getByLabelText("where")).toHaveTextContent(/^\/rooms\/3$/);
  });

  test("Mark all as seen shows while the list holds an unseen row, even if the badge read failed", async () => {
    unseenStatus = 500;
    const user = userEvent.setup();
    renderBell();
    await user.click(await bell());
    await screen.findByRole("link", { name: /Ada mentioned you/ });
    expect(await screen.findByRole("button", { name: "Mark all as seen" })).toBeInTheDocument();
  });

  test("signing out while mounted clears the badge, closes the drawer and reads nothing more", async () => {
    unseenPage = page([notice()], 3);
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const unbind = bindSessionQueryCache(queryClient);
    const user = userEvent.setup();
    renderBell("/rooms/3", queryClient);
    await user.click(await screen.findByRole("button", { name: "Notifications (3 unseen)" }));
    await screen.findByRole("link", { name: /Ada mentioned you/ });
    act(() => clearSession());
    expect(await screen.findByRole("button", { name: "Notifications (0 unseen)" })).toBeInTheDocument();
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    expect(screen.queryByText("3")).not.toBeInTheDocument();
    const reads = calls.length;
    await new Promise((resolve) => setTimeout(resolve, 50));
    expect(calls).toHaveLength(reads);
    unbind();
  });

  test.each([
    [2, "Powiadomienia (2 nieprzeczytane)"],
    [5, "Powiadomienia (5 nieprzeczytanych)"],
  ])("in Polish %i unseen reads with the right plural form", async (count, name) => {
    await i18n.changeLanguage("pl");
    unseenPage = page([notice()], count);
    renderBell();
    expect(await screen.findByRole("button", { name })).toBeInTheDocument();
  });

  test.each([
    ["MENTIONED", "Ada mentioned you in #42 Awaria bazy"],
    ["TEAM_MENTIONED", "Ada mentioned your team in #42 Awaria bazy"],
    ["WATCHERS_MENTIONED", "Ada mentioned the watchers of #42 Awaria bazy"],
    ["EVERYONE_MENTIONED", "Ada mentioned everyone in #42 Awaria bazy"],
    ["THREAD_REPLIED", "Ada replied in #42 Awaria bazy"],
    ["THREAD_CREATED", "Ada started #42 Awaria bazy"],
    ["THREAD_ASSIGNED", "Ada made you the owner of #42 Awaria bazy"],
    ["THREAD_OWNER_CHANGED", "Ada changed the owner of #42 Awaria bazy"],
    ["THREAD_STATUS_CHANGED", "Ada moved #42 Awaria bazy to another status"],
    ["THREAD_CLOSED", "Ada closed #42 Awaria bazy"],
    ["THREAD_REOPENED", "Ada reopened #42 Awaria bazy"],
  ] as const)("a %s notice reads as its own sentence", async (type, sentence) => {
    listPage = page([notice({ type, messageId: null })]);
    const user = userEvent.setup();
    renderBell();
    await user.click(await bell());
    const drawer = await screen.findByRole("dialog", { name: "Notifications" });
    const link = await within(drawer).findByRole("link", { name: new RegExp(sentence) });
    expect(link).toHaveAttribute("href", "/rooms/12/threads/42");
  });

  test("in Polish every kind has its sentence", async () => {
    await i18n.changeLanguage("pl");
    listPage = page([notice({ type: "THREAD_REOPENED", messageId: null }), notice({ id: 5, type: "TEAM_MENTIONED" })]);
    const user = userEvent.setup();
    renderBell();
    await user.click(await screen.findByRole("button", { name: /^Powiadomienia/ }));
    const drawer = await screen.findByRole("dialog", { name: "Powiadomienia" });
    expect(await within(drawer).findByText("Ada ponownie otwiera wątek #42 Awaria bazy")).toBeInTheDocument();
    expect(within(drawer).getByText("Ada wspomina o Twoim zespole w #42 Awaria bazy")).toBeInTheDocument();
  });

  test("the All / Mentions switch asks the server for the addressed kinds only, and back", async () => {
    const user = userEvent.setup();
    renderBell();
    await user.click(await bell());
    const drawer = await screen.findByRole("dialog", { name: "Notifications" });
    await within(drawer).findByRole("link", { name: /Ada mentioned you/ });
    const listCalls = () => calls.filter((c) => c.startsWith("GET /api/v1/notifications?") && c.includes("pageSize=20"));
    expect(listCalls().every((c) => !c.includes("type="))).toBe(true);

    await user.click(within(drawer).getByRole("radio", { name: "Mentions" }));
    await waitFor(() => expect(listCalls().some((c) => c.includes("type=MENTIONED&type=TEAM_MENTIONED&type=WATCHERS_MENTIONED&type=EVERYONE_MENTIONED&type=THREAD_ASSIGNED"))).toBe(true));
    // The badge still counts every unseen notice: its own query never names a type.
    expect(calls.filter((c) => c.includes("pageSize=100")).every((c) => !c.includes("type="))).toBe(true);

    listPage = page([]);
    await user.click(within(drawer).getByRole("radio", { name: "All" }));
    await waitFor(() => expect(listCalls().filter((c) => !c.includes("type=")).length).toBeGreaterThan(1));
  });

  test("an empty Mentions view says so in its own words", async () => {
    const user = userEvent.setup();
    serveListPage = () => page([]);
    renderBell();
    await user.click(await bell());
    const drawer = await screen.findByRole("dialog", { name: "Notifications" });
    await user.click(within(drawer).getByRole("radio", { name: "Mentions" }));
    expect(await within(drawer).findByText("You have no mentions.")).toBeInTheDocument();
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
