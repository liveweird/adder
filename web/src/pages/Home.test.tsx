import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { headingOutline } from "../test/headings";
import { jsonResponse } from "../test/http";
import { renderWithProviders, screen, waitFor, within } from "../test/render";
import { pinBrowserTimeZone } from "../test/timeZone";
import Home from "./Home";

type FetchMock = ReturnType<typeof vi.fn>;

const room = (id: number, name: string, unread = false) => ({
  id,
  name,
  description: id === 1 ? "Handover notes" : null,
  archived: false,
  role: "MEMBER",
  memberCount: 3,
  lastActivityAt: Date.UTC(2026, 10, 20, 12 - id),
  createdAt: 1,
  unread,
});

describe("Home", () => {
  let mockFetch: FetchMock;
  let zone: ReturnType<typeof pinBrowserTimeZone>;

  beforeEach(() => {
    mockFetch = vi.fn(() =>
      Promise.resolve(
        jsonResponse(200, {
          items: [
            room(1, "Night Shift", true),
            room(2, "Platform"),
            room(3, "Identity"),
            room(4, "Payments"),
            room(5, "Design"),
            room(6, "Support"),
            room(7, "Not shown"),
          ],
          page: 1,
          pageSize: 100,
          total: 7,
        }),
      ),
    );
    vi.stubGlobal("fetch", mockFetch);
    localStorage.setItem("adder.auth.token", "fake-token");
    zone = pinBrowserTimeZone("UTC");
  });

  afterEach(() => {
    zone.mockRestore();
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  test("offers All rooms and a focused activity-ordered list of joined rooms", async () => {
    renderWithProviders(<Home />);

    expect(screen.getByRole("heading", { level: 2, name: "Adder" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "All rooms" })).toHaveAttribute("href", "/rooms");
    const nightShift = await screen.findByRole("link", { name: /Night Shift/ });
    expect(nightShift).toHaveAttribute("href", "/rooms/1");
    expect(within(nightShift).getByText("Handover notes")).toBeInTheDocument();
    expect(within(nightShift).getByText("New")).toBeInTheDocument();
    expect(nightShift).toHaveAccessibleName(/new activity/);
    expect(within(nightShift).getByText("Active 2026-11-20 11:00")).toBeInTheDocument();

    const roomLinks = screen
      .getAllByRole("link")
      .filter((link) => /^\/rooms\/\d+$/.test(link.getAttribute("href") ?? ""));
    expect(roomLinks.map((link) => link.getAttribute("href"))).toEqual([
      "/rooms/1",
      "/rooms/2",
      "/rooms/3",
      "/rooms/4",
      "/rooms/5",
      "/rooms/6",
    ]);
    expect(screen.queryByText("Not shown")).not.toBeInTheDocument();
    expect(headingOutline()).toEqual([
      [2, "Adder"],
      [3, "Active rooms"],
    ]);
    expect(mockFetch).toHaveBeenCalledWith(
      "/api/v1/rooms?page=1&pageSize=100&sort=-lastActivityAt&archived=false&member=true",
      expect.anything(),
    );
  });

  test("shows a useful empty state when the caller has no active rooms", async () => {
    mockFetch.mockImplementation(() =>
      Promise.resolve(jsonResponse(200, { items: [], page: 1, pageSize: 100, total: 0 })),
    );
    renderWithProviders(<Home />);

    expect(await screen.findByText("No active rooms yet")).toBeInTheDocument();
    expect(screen.getByText("Open All rooms to find or create a room.")).toBeInTheDocument();
  });

  test("keeps the entry action available and reports a room load failure inline", async () => {
    mockFetch.mockImplementation(() =>
      Promise.resolve(jsonResponse(500, { title: "Boom", status: 500 })),
    );
    renderWithProviders(<Home />);

    expect(await screen.findByText("Could not load your rooms")).toBeInTheDocument();
    expect(screen.getByRole("alert")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "All rooms" })).toHaveAttribute("href", "/rooms");
    await waitFor(() => expect(screen.queryByRole("status", { name: "Loading" })).not.toBeInTheDocument());
  });
});
