import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { headingOutline } from "../test/headings";
import { jsonResponse } from "../test/http";
import { renderWithProviders, screen, waitFor, within } from "../test/render";
import { pinBrowserTimeZone } from "../test/timeZone";
import Home from "./Home";

type FetchMock = ReturnType<typeof vi.fn>;

const HOME = {
  quotes: [{ roomId: 3, roomName: "Night Shift", roomArchived: false, threadNumber: 42, threadTitle: "Database handover", messageId: 991,
    authorName: "Ann Owner", body: "**Disk is full**, ask <@7>", truncated: false, mentions: [{ userId: 7, name: "Bob Reader" }],
    groupMentions: [], createdAt: Date.UTC(2026, 10, 20, 11), unread: true }],
  ownedThreads: [{ roomId: 3, roomName: "Night Shift", roomArchived: false, number: 8, title: "Rotate credentials", status: "working",
    statusLabel: "In progress", statusColour: "BLUE", closed: false, unreadCount: 2, lastActivityAt: Date.UTC(2026, 10, 20, 10) }],
  watchedThreads: [{ roomId: 6, roomName: "Platform", roomArchived: true, number: 17, title: "Legacy deployment", status: "done",
    statusLabel: "Done", statusColour: "TEAL", closed: true, unreadCount: 0, lastActivityAt: Date.UTC(2026, 10, 19, 16) }],
  ownedRooms: [{ id: 9, name: "Incident Leads", accessType: "RESTRICTED_VISIBLE", archived: false, unread: true,
    pendingJoinRequests: 3, lastActivityAt: Date.UTC(2026, 10, 20, 9) }],
} as const;

describe("Home", () => {
  let mockFetch: FetchMock;
  let zone: ReturnType<typeof pinBrowserTimeZone>;

  beforeEach(() => {
    mockFetch = vi.fn(() => Promise.resolve(jsonResponse(200, HOME)));
    vi.stubGlobal("fetch", mockFetch);
    localStorage.setItem("adder.auth.token", "fake-token");
    zone = pinBrowserTimeZone("UTC");
  });

  afterEach(() => {
    zone.mockRestore();
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  test("renders the four personal queues with useful marks and a stable heading outline", async () => {
    renderWithProviders(<Home />);

    expect(await screen.findByText("Disk is full, ask @Bob Reader")).toBeInTheDocument();
    expect(screen.getByText("In progress")).toBeInTheDocument();
    expect(screen.getByText("2 unread")).toBeInTheDocument();
    expect(screen.getByText("Restricted, visible")).toBeInTheDocument();
    expect(screen.getByText("3 join requests")).toBeInTheDocument();
    expect(screen.getByText("Archived")).toBeInTheDocument();
    expect(headingOutline()).toEqual([[2, "Home"], [3, "Replies quoting you"], [3, "Open threads you own"], [3, "Watched threads"], [3, "Rooms you own"]]);
    expect(mockFetch).toHaveBeenCalledWith("/api/v1/home", expect.anything());
  });

  test("links rows to the precise work destination", async () => {
    renderWithProviders(<Home />);

    const quote = await screen.findByRole("link", { name: /Database handover/ });
    expect(quote).toHaveAttribute("href", "/rooms/3/threads/42?message=991");
    expect(screen.getByRole("link", { name: /Rotate credentials/ })).toHaveAttribute("href", "/rooms/3/threads/8");
    expect(screen.getByRole("link", { name: /Legacy deployment/ })).toHaveAttribute("href", "/rooms/6/threads/17");
    expect(screen.getByRole("link", { name: /Incident Leads/ })).toHaveAttribute("href", "/rooms/9/members#room-join-requests-title");
    expect(screen.getByRole("link", { name: "View all" })).toHaveAttribute("href", "/search?watching=1");
    expect(screen.getByRole("link", { name: "All rooms" })).toHaveAttribute("href", "/rooms");
    expect(within(quote).getByText("New")).toBeInTheDocument();
    expect(quote).toHaveAccessibleName(/New activity/);
  });

  test("gives each empty queue a compact explanation", async () => {
    mockFetch.mockImplementation(() => Promise.resolve(jsonResponse(200, { quotes: [], ownedThreads: [], watchedThreads: [], ownedRooms: [] })));
    renderWithProviders(<Home />);

    expect(await screen.findByText("No one has quoted you recently.")).toBeInTheDocument();
    expect(screen.getByText("You have no open threads in active rooms.")).toBeInTheDocument();
    expect(screen.getByText("You are not watching any threads.")).toBeInTheDocument();
    expect(screen.getByText("You do not own any rooms.")).toBeInTheDocument();
  });

  test("shows a lower-bound unread count without claiming an exact total", async () => {
    mockFetch.mockImplementation(() => Promise.resolve(jsonResponse(200, {
      ...HOME,
      ownedThreads: [{ ...HOME.ownedThreads[0], unreadCount: 100 }],
    })));
    renderWithProviders(<Home />);

    expect(await screen.findByText("99+ unread")).toBeInTheDocument();
    expect(screen.queryByText("100 unread")).not.toBeInTheDocument();
  });

  test("keeps navigation available and reports a dashboard load failure", async () => {
    mockFetch.mockImplementation(() => Promise.resolve(jsonResponse(500, { title: "Boom", status: 500 })));
    renderWithProviders(<Home />);

    expect(await screen.findByText("Could not load your home")).toBeInTheDocument();
    expect(screen.getByRole("alert")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "All rooms" })).toHaveAttribute("href", "/rooms");
    await waitFor(() => expect(screen.queryByRole("status", { name: "Loading" })).not.toBeInTheDocument());
  });
});
