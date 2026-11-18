import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient } from "@tanstack/react-query";
import { Route, Routes } from "react-router-dom";
import RoomHome from "./RoomHome";
import { headingOutline } from "../test/headings";
import { jsonResponse } from "../test/http";
import { renderWithProviders } from "../test/render";

const ROOM = {
  id: 12,
  name: "Night Shift",
  description: "Handover notes",
  archived: false,
  archivedAt: null,
  role: "OWNER",
  memberCount: 3,
  workflowRevision: 1,
  lastActivityAt: 1,
  createdAt: 1,
  updatedAt: 1,
};
const WORKFLOW = {
  revision: 1,
  statuses: [
    { key: "open", label: "Triage", colour: "BLUE", category: "OPEN", initial: true, threadCount: 1 },
    { key: "done", label: "Done", colour: "TEAL", category: "CLOSED", initial: false, threadCount: 0 },
  ],
  fields: [{ key: "severity", label: "Severity", type: "TEXT", required: false, options: [] }],
};
const THREAD_ITEM = {
  id: 1,
  number: 7,
  title: "Disk full on db-3",
  status: "open",
  closed: false,
  ownerId: 5,
  ownerName: "Ann Owner",
  messageCount: 2,
  lastActivityAt: 1,
  createdAt: 1,
};
const THREADS = { items: [THREAD_ITEM], page: 1, pageSize: 50, total: 1 };
const THREAD = { ...THREAD_ITEM, closedAt: null, createdById: 5, createdByName: "Ann Owner", fields: {}, fieldUserNames: {}, revision: 1, updatedAt: 1 };

function renderHome(route = "/rooms/12") {
  return renderWithProviders(
    <Routes>
      <Route path="/rooms/:id" element={<RoomHome />} />
      <Route path="/rooms/:id/threads/new" element={<RoomHome isNew />} />
      <Route path="/rooms/:id/threads/:number" element={<RoomHome />} />
      <Route path="/rooms" element={<p>the rooms list</p>} />
    </Routes>,
    { route },
  );
}

function serve(mockFetch: ReturnType<typeof vi.fn>, room: unknown = ROOM) {
  mockFetch.mockImplementation((url: string) => {
    if (url === "/api/v1/rooms/12") return Promise.resolve(jsonResponse(200, room));
    if (url === "/api/v1/rooms/12/workflow") return Promise.resolve(jsonResponse(200, WORKFLOW));
    if (url.startsWith("/api/v1/rooms/12/threads?")) return Promise.resolve(jsonResponse(200, THREADS));
    if (url === "/api/v1/rooms/12/threads/7") return Promise.resolve(jsonResponse(200, THREAD));
    return Promise.resolve(jsonResponse(404, { title: "Not found", status: 404 }));
  });
}

describe("RoomHome page", () => {
  let mockFetch: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    mockFetch = vi.fn();
    vi.stubGlobal("fetch", mockFetch);
    localStorage.setItem("adder.auth.token", "fake-token");
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  test("the room's own route: the header, the tabs with Threads open, the thread list and a prompt in the pane", async () => {
    serve(mockFetch);
    const { container } = renderHome();
    expect(await screen.findByRole("heading", { level: 2, name: "Night Shift" })).toBeInTheDocument();
    expect(screen.getByText("Handover notes")).toBeInTheDocument();
    expect(screen.getByText("3 members")).toBeInTheDocument();
    expect(screen.queryByText("Archived")).not.toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Back to rooms" })).toHaveAttribute("href", "/rooms");
    expect(screen.getByRole("link", { name: "Threads" })).toHaveAttribute("aria-current", "page");
    expect(screen.getByRole("link", { name: "Workflow" })).toHaveAttribute("href", "/rooms/12/workflow");
    const list = await screen.findByRole("navigation", { name: "Threads" });
    expect(within(list).getByRole("link", { name: /Disk full on db-3/ })).toHaveAttribute("href", "/rooms/12/threads/7");
    expect(screen.getByText("Pick a thread")).toBeInTheDocument();
    // In a narrow main area only the list shows; the "Back to threads" link belongs to the pane routes.
    expect(container.querySelector("[data-pane]")).toHaveAttribute("data-pane", "list");
    expect(screen.queryByRole("link", { name: "Back to threads" })).not.toBeInTheDocument();
    expect(headingOutline()).toEqual([[2, "Night Shift"]]);
  });

  test("a thread's route: its pane beside the list, the thread marked in it, and a way back to the list", async () => {
    serve(mockFetch);
    const { container } = renderHome("/rooms/12/threads/7");
    expect(await screen.findByRole("region", { name: "#7 Disk full on db-3" })).toBeInTheDocument();
    expect(container.querySelector("[data-pane]")).toHaveAttribute("data-pane", "thread");
    const list = await screen.findByRole("navigation", { name: "Threads" });
    expect(within(list).getByRole("link", { name: /Disk full on db-3/ })).toHaveAttribute("aria-current", "page");
    expect(screen.getByRole("link", { name: "Threads" })).toHaveAttribute("aria-current", "page");
    expect(screen.getByRole("link", { name: "Back to threads" })).toHaveAttribute("href", "/rooms/12");
    expect(screen.queryByText("Pick a thread")).not.toBeInTheDocument();
    expect(headingOutline()).toEqual([
      [2, "Night Shift"],
      [3, "#7 Disk full on db-3"],
    ]);
  });

  test("the new-thread route: the form in the pane, the Threads tab still open", async () => {
    serve(mockFetch);
    const { container } = renderHome("/rooms/12/threads/new");
    expect(await screen.findByRole("heading", { level: 3, name: "New thread" })).toBeInTheDocument();
    expect(await screen.findByRole("textbox", { name: /Title/ })).toBeInTheDocument();
    expect(container.querySelector("[data-pane]")).toHaveAttribute("data-pane", "thread");
    expect(screen.getByRole("link", { name: "Back to threads" })).toHaveAttribute("href", "/rooms/12");
    expect(screen.getByRole("link", { name: "Threads" })).toHaveAttribute("aria-current", "page");
  });

  test("clicking through threads keeps the same list on screen, on the page the reader chose, and asks the pane for focus", async () => {
    const user = userEvent.setup();
    const listUrls: string[] = [];
    mockFetch.mockImplementation((url: string) => {
      if (url === "/api/v1/rooms/12") return Promise.resolve(jsonResponse(200, ROOM));
      if (url === "/api/v1/rooms/12/workflow") return Promise.resolve(jsonResponse(200, WORKFLOW));
      if (url.startsWith("/api/v1/rooms/12/threads?")) {
        listUrls.push(url);
        return Promise.resolve(jsonResponse(200, { ...THREADS, page: url.includes("page=2") ? 2 : 1, total: 120 }));
      }
      if (url === "/api/v1/rooms/12/threads/7") return Promise.resolve(jsonResponse(200, THREAD));
      return Promise.resolve(jsonResponse(404, { title: "Not found", status: 404 }));
    });
    renderHome();
    const nav = await screen.findByRole("navigation", { name: "Threads" });
    await user.click(screen.getByRole("button", { name: "2" }));
    await waitFor(() => expect(listUrls.at(-1)).toContain("page=2"));
    const requestsBefore = listUrls.length;
    // A row, then back to the list, then the new-thread form, then back: the list is never rebuilt and stays on page 2.
    await user.click(within(nav).getByRole("link", { name: /Disk full on db-3/ }));
    const region = await screen.findByRole("region", { name: "#7 Disk full on db-3" });
    await waitFor(() => expect(region).toHaveFocus());
    expect(screen.getByRole("navigation", { name: "Threads" })).toBe(nav);
    await user.click(screen.getByRole("link", { name: "Back to threads" }));
    await screen.findByText("Pick a thread");
    await user.click(screen.getByRole("link", { name: "New thread" }));
    await screen.findByRole("heading", { level: 3, name: "New thread" });
    expect(screen.getByRole("navigation", { name: "Threads" })).toBe(nav);
    expect(listUrls.every((url, index) => index < requestsBefore || url.includes("page=2"))).toBe(true);
    expect(screen.getByRole("button", { name: "2" })).toHaveAttribute("aria-current", "page");
  });

  test("a thread number that names nothing reads as not found in the pane, and the list stays", async () => {
    serve(mockFetch);
    renderHome("/rooms/12/threads/99");
    expect(await screen.findByText("This thread does not exist.")).toBeInTheDocument();
    expect(await screen.findByRole("navigation", { name: "Threads" })).toBeInTheDocument();
  });

  test("an archived room says so, and its list has no New thread", async () => {
    serve(mockFetch, { ...ROOM, archived: true, description: null, memberCount: 1 });
    renderHome();
    expect(await screen.findByText("Archived")).toBeInTheDocument();
    expect(screen.getByText("1 member")).toBeInTheDocument();
    await screen.findByRole("navigation", { name: "Threads" });
    expect(screen.queryByRole("link", { name: "New thread" })).not.toBeInTheDocument();
  });

  test("a failed thread read is an inline alert under the list controls; the room stays", async () => {
    mockFetch.mockImplementation((url: string) =>
      Promise.resolve(url === "/api/v1/rooms/12" ? jsonResponse(200, ROOM) : jsonResponse(500, { title: "Boom", status: 500 })),
    );
    renderHome();
    expect(await screen.findByText("Could not load the threads")).toBeInTheDocument();
    expect(screen.getByRole("heading", { level: 2, name: "Night Shift" })).toBeInTheDocument();
  });

  test("a room that does not exist (or is not the caller's) reads as one 404 under a static title", async () => {
    serve(mockFetch);
    renderHome("/rooms/99");
    expect(await screen.findByText("This room does not exist, or you are not a member of it.")).toBeInTheDocument();
    expect(headingOutline()).toEqual([[2, "Rooms"]]);
    expect(screen.getByRole("link", { name: "Back to rooms" })).toHaveAttribute("href", "/rooms");
  });

  test("a failed room read is a status-tagged alert", async () => {
    mockFetch.mockImplementation(() => Promise.resolve(jsonResponse(500, { title: "Boom", status: 500 })));
    renderHome();
    expect(await screen.findByText("Load failed (500)")).toBeInTheDocument();
  });

  test.each(["abc", "12abc", "0", "1e3"])("the id %s names no room: the not-found state, without a request", (id) => {
    renderHome(`/rooms/${id}`);
    expect(screen.getByText("This room does not exist, or you are not a member of it.")).toBeInTheDocument();
    expect(headingOutline()).toEqual([[2, "Rooms"]]);
    expect(mockFetch).not.toHaveBeenCalled();
  });

  test("a 404 wins over a copy of the room cached from an earlier visit", async () => {
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    queryClient.setQueryData(["rooms", "detail", 12], ROOM);
    mockFetch.mockImplementation(() => Promise.resolve(jsonResponse(404, { title: "Not found", status: 404 })));
    renderWithProviders(
      <Routes>
        <Route path="/rooms/:id" element={<RoomHome />} />
      </Routes>,
      { route: "/rooms/12", queryClient },
    );
    // The cached room shows first, then the refetch's 404 replaces it.
    expect(await screen.findByText("This room does not exist, or you are not a member of it.")).toBeInTheDocument();
    expect(screen.queryByRole("heading", { level: 2, name: "Night Shift" })).not.toBeInTheDocument();
  });
});
