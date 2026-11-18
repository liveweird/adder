import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { screen, within } from "@testing-library/react";
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
    { key: "open", label: "Triage", colour: "BLUE", category: "OPEN", initial: true, threadCount: 0 },
    { key: "waiting", label: "Waiting", colour: "GRAPE", category: "OPEN", initial: false, threadCount: 0 },
    { key: "done", label: "Done", colour: "TEAL", category: "CLOSED", initial: false, threadCount: 0 },
  ],
  fields: [
    { key: "severity", label: "Severity", type: "SELECT", required: true, options: [] },
    { key: "eta", label: "ETA", type: "DATE", required: false, options: [] },
  ],
};

function renderHome(route = "/rooms/12") {
  return renderWithProviders(
    <Routes>
      <Route path="/rooms/:id" element={<RoomHome />} />
      <Route path="/rooms" element={<p>the rooms list</p>} />
    </Routes>,
    { route },
  );
}

function serve(mockFetch: ReturnType<typeof vi.fn>, room: unknown = ROOM, workflow: unknown = WORKFLOW) {
  mockFetch.mockImplementation((url: string) => {
    if (url === "/api/v1/rooms/12") return Promise.resolve(jsonResponse(200, room));
    if (url === "/api/v1/rooms/12/workflow") return Promise.resolve(jsonResponse(200, workflow));
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

  test("shows the header, the tabs, the threads placeholder and the workflow as pills grouped by category", async () => {
    serve(mockFetch);
    renderHome();
    expect(await screen.findByRole("heading", { level: 2, name: "Night Shift" })).toBeInTheDocument();
    expect(screen.getByText("Handover notes")).toBeInTheDocument();
    expect(screen.getByText("3 members")).toBeInTheDocument();
    expect(screen.queryByText("Archived")).not.toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Back to rooms" })).toHaveAttribute("href", "/rooms");
    expect(screen.getByRole("link", { name: "Overview" })).toHaveAttribute("aria-current", "page");
    expect(screen.getByText("Threads arrive next")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Workflow" })).toHaveAttribute("href", "/rooms/12/workflow");
    expect(screen.getByRole("link", { name: "Open the workflow" })).toHaveAttribute("href", "/rooms/12/workflow");
    const open = await screen.findByRole("group", { name: "Open" });
    expect(within(open).getByText("Triage")).toBeInTheDocument();
    expect(within(open).getByText("Waiting")).toBeInTheDocument();
    expect(within(screen.getByRole("group", { name: "Closed" })).getByText("Done")).toBeInTheDocument();
    expect(screen.getByText("Fields: Severity, ETA")).toBeInTheDocument();
    expect(headingOutline()).toEqual([
      [2, "Night Shift"],
      [3, "Workflow"],
    ]);
  });

  test("an archived room says so, a single member and no description or fields are handled, an empty category is omitted", async () => {
    serve(
      mockFetch,
      { ...ROOM, archived: true, description: null, memberCount: 1 },
      { ...WORKFLOW, statuses: WORKFLOW.statuses.slice(0, 1), fields: [] },
    );
    renderHome();
    expect(await screen.findByText("Archived")).toBeInTheDocument();
    expect(screen.getByText("1 member")).toBeInTheDocument();
    expect(await screen.findByText("No extra fields")).toBeInTheDocument();
    expect(screen.queryByRole("group", { name: "Closed" })).not.toBeInTheDocument();
  });

  test("a failed workflow read is an inline alert under the workflow heading; the room stays", async () => {
    mockFetch.mockImplementation((url: string) =>
      Promise.resolve(url === "/api/v1/rooms/12" ? jsonResponse(200, ROOM) : jsonResponse(500, { title: "Boom", status: 500 })),
    );
    renderHome();
    expect(await screen.findByText("Could not load the workflow")).toBeInTheDocument();
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
