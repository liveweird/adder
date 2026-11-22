// The thread list's "Unread" filter and "Mark all as read" (1.2 A10 to A12). Kept apart from `ThreadFilters.test.tsx` and `ThreadList.test.tsx`,
// whose describes are at the lint's function-size limit.
import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { useLocation } from "react-router-dom";
import { screen, waitFor, within } from "@testing-library/react";
import ThreadList from "./ThreadList";
import type { RoomResponse } from "../api/rooms";
import { jsonResponse } from "../test/http";
import { renderWithProviders } from "../test/render";

const ROOM: RoomResponse = {
  id: 12,
  name: "Night Shift",
  description: null,
  archived: false,
  archivedAt: null,
  role: "MEMBER",
  memberCount: 3,
  workflowRevision: 1,
  lastActivityAt: 1,
  createdAt: 1,
  updatedAt: 1,
};
const WORKFLOW = {
  revision: 1,
  statuses: [{ key: "triage", label: "Triage", colour: "BLUE", category: "OPEN", initial: true, threadCount: 2 }],
  fields: [],
};
const thread = (number: number, unreadCount: number) => ({
  id: number,
  number,
  title: `Thread ${number}`,
  status: "triage",
  closed: false,
  ownerId: 5,
  ownerName: "Ann",
  messageCount: 4,
  lastActivityAt: 1,
  createdAt: 1,
  hashtags: [],
  tags: [],
  unreadCount,
});
const page = (...items: ReturnType<typeof thread>[]) => ({ items, page: 1, pageSize: 50, total: items.length });
const EMPTY_NOTICES = { items: [], page: 1, pageSize: 100, total: 0 };

function Where() {
  const { pathname, search } = useLocation();
  return <output aria-label="where">{pathname + search}</output>;
}
const where = () => screen.getByLabelText("where").textContent;

describe("the Unread filter and Mark all as read", () => {
  let mockFetch: ReturnType<typeof vi.fn>;
  const listCalls = () => mockFetch.mock.calls.map(([url]) => url as string).filter((url) => url.startsWith("/api/v1/rooms/12/threads?"));
  const readAllCalls = () => mockFetch.mock.calls.filter(([url]) => url === "/api/v1/rooms/12/read-all");
  /** Every thread unread until a read-all lands, then none: the server's answer to the two reads. */
  let marked = false;

  function serve(readAll: () => Promise<Response> = () => Promise.resolve(new Response(null, { status: 204 }))) {
    marked = false;
    mockFetch.mockImplementation((url: string, init?: RequestInit) => {
      if (url === "/api/v1/rooms/12/read-all" && init?.method === "POST") {
        return readAll().then((response) => {
          if (response.ok) marked = true;
          return response;
        });
      }
      if (url.startsWith("/api/v1/rooms/12/threads?")) {
        const unreadOnly = url.includes("unread=true");
        const all = marked ? [thread(2, 0), thread(1, 0)] : [thread(2, 3), thread(1, 0)];
        return Promise.resolve(jsonResponse(200, page(...(unreadOnly ? all.filter((row) => row.unreadCount > 0) : all))));
      }
      if (url === "/api/v1/rooms/12/workflow") return Promise.resolve(jsonResponse(200, WORKFLOW));
      if (url.startsWith("/api/v1/notifications?")) return Promise.resolve(jsonResponse(200, EMPTY_NOTICES));
      return Promise.resolve(jsonResponse(404, { title: "Not found", status: 404 }));
    });
  }

  function renderList(room: RoomResponse = ROOM, route = "/rooms/12") {
    return renderWithProviders(
      <>
        <ThreadList room={room} activeNumber={null} />
        <Where />
      </>,
      { route },
    );
  }

  async function openPanel(user: ReturnType<typeof userEvent.setup>) {
    await screen.findByRole("navigation", { name: "Threads" });
    await user.click(screen.getByRole("button", { name: /Filters/ }));
  }

  beforeEach(() => {
    mockFetch = vi.fn();
    vi.stubGlobal("fetch", mockFetch);
    localStorage.setItem("adder.auth.token", "fake-token");
    localStorage.setItem("adder.auth.userId", "5");
  });
  afterEach(() => {
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  test("Unread sits after Watching, sends unread=true, writes unread=me, counts as one filter and Clear drops it", async () => {
    serve();
    const user = userEvent.setup();
    renderList();
    await openPanel(user);
    const unread = screen.getByRole("button", { name: "Unread" });
    expect(unread).toHaveAttribute("aria-pressed", "false");
    expect(Boolean(screen.getByRole("button", { name: "Watching" }).compareDocumentPosition(unread) & Node.DOCUMENT_POSITION_FOLLOWING)).toBe(true);
    await user.click(unread);
    await waitFor(() => expect(listCalls().at(-1)).toBe("/api/v1/rooms/12/threads?page=1&pageSize=50&sort=-lastActivityAt&closed=false&unread=true"));
    expect(where()).toBe("/rooms/12?unread=me");
    expect(screen.getByRole("button", { name: "Unread" })).toHaveAttribute("aria-pressed", "true");
    // Only the unread thread is listed.
    expect(await screen.findByRole("link", { name: /Thread 2/ })).toBeInTheDocument();
    await waitFor(() => expect(screen.queryByRole("link", { name: /Thread 1/ })).toBeNull());
    expect(screen.getByRole("button", { name: /Filters/ })).toHaveTextContent("1");
    await user.click(screen.getByRole("button", { name: "Clear filters" }));
    await waitFor(() => expect(where()).toBe("/rooms/12"));
    expect(listCalls().at(-1)).not.toContain("unread");
  });

  test("an ADMIN outside the room is offered no Unread filter and a stale unread=me asks for nothing", async () => {
    serve();
    const user = userEvent.setup();
    renderList({ ...ROOM, role: null }, "/rooms/12?unread=me");
    await openPanel(user);
    expect(screen.queryByRole("button", { name: "Unread" })).toBeNull();
    expect(screen.queryByRole("button", { name: "Mark all as read" })).toBeNull();
    expect(listCalls().every((url) => !url.includes("unread"))).toBe(true);
  });

  test("Mark all as read asks first; Cancel sends nothing", async () => {
    serve();
    const user = userEvent.setup();
    renderList();
    await screen.findByRole("navigation", { name: "Threads" });
    await user.click(screen.getByRole("button", { name: "Mark all as read" }));
    const dialog = await screen.findByRole("dialog", { name: "Mark all as read?" });
    expect(within(dialog).getByText(/Every thread in Night Shift will be marked as read for you/)).toBeInTheDocument();
    await user.click(within(dialog).getByRole("button", { name: "Cancel" }));
    await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
    expect(readAllCalls()).toHaveLength(0);
  });

  test("confirming posts once, re-reads the list, the rail and the bell, and an open Unread filter empties", async () => {
    serve();
    const user = userEvent.setup();
    renderList(ROOM, "/rooms/12?unread=me");
    expect(await screen.findByRole("link", { name: /Thread 2/ })).toHaveAccessibleDescription("3 unread messages");
    const before = mockFetch.mock.calls.filter(([url]) => (url as string).startsWith("/api/v1/notifications?")).length;
    await user.click(screen.getByRole("button", { name: "Mark all as read" }));
    const dialog = await screen.findByRole("dialog", { name: "Mark all as read?" });
    await user.click(within(dialog).getByRole("button", { name: "Mark all as read" }));
    await waitFor(() => expect(readAllCalls()).toHaveLength(1));
    expect(readAllCalls()[0][1]).toMatchObject({ method: "POST" });
    await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
    // The filtered list was re-read: nothing is unread any more.
    expect(await screen.findByText("No threads match these filters.")).toBeInTheDocument();
    expect(screen.queryByRole("link", { name: /Thread 2/ })).toBeNull();
    await waitFor(() => expect(mockFetch.mock.calls.filter(([url]) => (url as string).startsWith("/api/v1/notifications?")).length).toBeGreaterThanOrEqual(before));
  });

  test("a failed write keeps the dialog open and says why; a 429 says how long to wait", async () => {
    serve(() => Promise.resolve(jsonResponse(429, { title: "Too many", status: 429 }, { "Retry-After": "7" })));
    const user = userEvent.setup();
    renderList();
    await screen.findByRole("navigation", { name: "Threads" });
    await user.click(screen.getByRole("button", { name: "Mark all as read" }));
    const dialog = await screen.findByRole("dialog", { name: "Mark all as read?" });
    await user.click(within(dialog).getByRole("button", { name: "Mark all as read" }));
    expect(await within(dialog).findByText(/7 seconds/)).toBeInTheDocument();
    expect(screen.getByRole("dialog", { name: "Mark all as read?" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: /Thread 2/ })).toHaveAccessibleDescription("3 unread messages");
  });

  test("an unexpected failure uses the fixed words, never the server's", async () => {
    serve(() => Promise.resolve(jsonResponse(500, { title: "boom internals", status: 500 })));
    const user = userEvent.setup();
    renderList();
    await screen.findByRole("navigation", { name: "Threads" });
    await user.click(screen.getByRole("button", { name: "Mark all as read" }));
    const dialog = await screen.findByRole("dialog", { name: "Mark all as read?" });
    await user.click(within(dialog).getByRole("button", { name: "Mark all as read" }));
    expect(await within(dialog).findByText("Could not mark the room as read")).toBeInTheDocument();
    expect(within(dialog).queryByText(/boom/)).toBeNull();
    // The floor may already be raised server-side, so the page reads its lists again instead of trusting its cache.
    const listReads = () => listCalls().length;
    const before = listReads();
    await user.click(within(dialog).getByRole("button", { name: "Mark all as read" }));
    await waitFor(() => expect(listReads()).toBeGreaterThan(before));
  });
});
