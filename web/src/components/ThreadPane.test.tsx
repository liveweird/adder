import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import { QueryClient } from "@tanstack/react-query";
import ThreadPane from "./ThreadPane";
import type { RoomResponse } from "../api/rooms";
import { headingOutline } from "../test/headings";
import { jsonResponse } from "../test/http";
import { renderWithProviders } from "../test/render";
import { browserTimeZone, formatDateTime } from "../utils/formatDate";
import { focusThreadPaneState } from "../utils/roomLinks";

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
  statuses: [{ key: "triage", label: "Triage", colour: "BLUE", category: "OPEN", initial: true, threadCount: 1 }],
  fields: [],
};
const STARTED = Date.UTC(2026, 5, 1, 9, 30);
const MOVED = Date.UTC(2026, 5, 2, 18, 5);
const THREAD = {
  id: 1,
  number: 42,
  title: "Disk full on db-3",
  status: "triage",
  closed: false,
  closedAt: null,
  ownerId: 5,
  ownerName: "Ann Owner",
  createdById: 6,
  createdByName: "Bob Starter",
  fields: {},
  fieldUserNames: {},
  messageCount: 3,
  revision: 1,
  lastActivityAt: MOVED,
  createdAt: STARTED,
  updatedAt: MOVED,
};

describe("ThreadPane", () => {
  let mockFetch: ReturnType<typeof vi.fn>;
  const threadCalls = () => mockFetch.mock.calls.map(([url]) => url as string).filter((url) => url.includes("/threads/"));

  function serve(thread: Response | (() => Response)) {
    mockFetch.mockImplementation((url: string) => {
      if (url === "/api/v1/rooms/12/threads/42") return Promise.resolve(typeof thread === "function" ? thread() : thread);
      if (url === "/api/v1/rooms/12/workflow") return Promise.resolve(jsonResponse(200, WORKFLOW));
      return Promise.resolve(jsonResponse(404, { title: "Not found", status: 404 }));
    });
  }

  beforeEach(() => {
    mockFetch = vi.fn();
    vi.stubGlobal("fetch", mockFetch);
    localStorage.setItem("adder.auth.token", "fake-token");
  });
  afterEach(() => {
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  test("the header: #number and title as the pane's heading, the status, owner, count and both times in the browser's zone", async () => {
    serve(jsonResponse(200, THREAD));
    renderWithProviders(<ThreadPane room={ROOM} numberParam="42" />);
    const region = await screen.findByRole("region", { name: "#42 Disk full on db-3" });
    expect(region).toHaveTextContent("Owner: Ann Owner");
    expect(region).toHaveTextContent("3 messages");
    const zone = browserTimeZone();
    expect(region).toHaveTextContent(`Started by Bob Starter on ${formatDateTime(STARTED, zone)}`);
    expect(region).toHaveTextContent(`Last activity ${formatDateTime(MOVED, zone)}`);
    await waitFor(() => expect(region).toHaveTextContent("Triage"));
    expect(headingOutline()).toEqual([[3, "#42 Disk full on db-3"]]);
  });

  test("a number that names no thread is the not-found state, without a request", () => {
    serve(jsonResponse(200, THREAD));
    renderWithProviders(<ThreadPane room={ROOM} numberParam="abc" />);
    expect(screen.getByText("This thread does not exist.")).toBeInTheDocument();
    expect(threadCalls()).toEqual([]);
  });

  test("a 404 is the same not-found state", async () => {
    serve(jsonResponse(404, { title: "Not found", status: 404 }));
    renderWithProviders(<ThreadPane room={ROOM} numberParam="42" />);
    expect(await screen.findByText("This thread does not exist.")).toBeInTheDocument();
  });

  test("a 404 wins over a copy of the thread cached from an earlier visit", async () => {
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    queryClient.setQueryData(["rooms", "threads", 12, "detail", 42], THREAD);
    serve(jsonResponse(404, { title: "Not found", status: 404 }));
    renderWithProviders(<ThreadPane room={ROOM} numberParam="42" />, { queryClient });
    expect(await screen.findByText("This thread does not exist.")).toBeInTheDocument();
    expect(screen.queryByRole("region")).not.toBeInTheDocument();
  });

  test("a failed read is an inline alert", async () => {
    serve(jsonResponse(500, { title: "Boom", status: 500 }));
    renderWithProviders(<ThreadPane room={ROOM} numberParam="42" />);
    expect(await screen.findByText("Could not load the thread")).toBeInTheDocument();
    expect(screen.getByText("Load failed (500)")).toBeInTheDocument();
  });

  test("while loading, a spinner stands in", () => {
    mockFetch.mockImplementation(() => new Promise(() => undefined));
    renderWithProviders(<ThreadPane room={ROOM} numberParam="42" />);
    expect(screen.getByRole("status", { name: "Loading…" })).toBeInTheDocument();
  });

  test("after a navigation that asks for it (a list row, a started thread) the pane takes focus once its thread has loaded", async () => {
    serve(jsonResponse(200, THREAD));
    renderWithProviders(<ThreadPane room={ROOM} numberParam="42" />, { route: "/rooms/12/threads/42", state: focusThreadPaneState });
    const region = await screen.findByRole("region", { name: "#42 Disk full on db-3" });
    expect(region).toHaveAttribute("tabindex", "-1");
    await waitFor(() => expect(region).toHaveFocus());
  });

  test("a deep link or a reload (no focus request) leaves focus alone", async () => {
    serve(jsonResponse(200, THREAD));
    renderWithProviders(<ThreadPane room={ROOM} numberParam="42" />, { route: "/rooms/12/threads/42" });
    const region = await screen.findByRole("region", { name: "#42 Disk full on db-3" });
    expect(region).not.toHaveFocus();
  });

  test("a background refetch of the same thread does not pull focus back", async () => {
    serve(jsonResponse(200, THREAD));
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    renderWithProviders(
      <>
        <ThreadPane room={ROOM} numberParam="42" />
        <button>elsewhere</button>
      </>,
      { route: "/rooms/12/threads/42", state: focusThreadPaneState, queryClient },
    );
    const region = await screen.findByRole("region", { name: "#42 Disk full on db-3" });
    await waitFor(() => expect(region).toHaveFocus());
    screen.getByRole("button", { name: "elsewhere" }).focus();
    await queryClient.invalidateQueries({ queryKey: ["rooms", "threads", 12, "detail", 42] });
    expect(screen.getByRole("button", { name: "elsewhere" })).toHaveFocus();
  });

  test("a failed workflow read is an inline alert above the header; the thread still shows, its status as the bare key", async () => {
    mockFetch.mockImplementation((url: string) =>
      Promise.resolve(url === "/api/v1/rooms/12/threads/42" ? jsonResponse(200, THREAD) : jsonResponse(500, { title: "Boom", status: 500 })),
    );
    renderWithProviders(<ThreadPane room={ROOM} numberParam="42" />);
    expect(await screen.findByText("Could not load the room's statuses")).toBeInTheDocument();
    const region = screen.getByRole("region", { name: "#42 Disk full on db-3" });
    expect(region).toHaveTextContent("triage");
  });
});
