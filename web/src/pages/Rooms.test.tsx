import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { screen, waitFor, within } from "@testing-library/react";
import { Route, Routes } from "react-router-dom";
import Rooms from "./Rooms";
import { headingOutline } from "../test/headings";
import { jsonResponse } from "../test/http";
import { renderWithProviders } from "../test/render";
import { pinBrowserTimeZone } from "../test/timeZone";

const ROLES_KEY = "adder.auth.roles";

type FetchMock = ReturnType<typeof vi.fn>;

// 20:00 UTC is already the next calendar day in Auckland, the zone these tests pin: the list prints the reader's day, not UTC's.
const EVENING = 20 * 3_600_000;

const PAGE = {
  items: [
    { id: 1, name: "Night Shift", description: "Handover notes", archived: false, role: "OWNER", memberCount: 3, lastActivityAt: 86_400_000 + EVENING, createdAt: 172_800_000 + EVENING },
    { id: 2, name: "Old Incident", description: null, archived: true, role: "MEMBER", memberCount: 1, lastActivityAt: 0, createdAt: 0 },
    { id: 3, name: "Someone Else's", description: null, archived: false, role: null, memberCount: 12, lastActivityAt: 0, createdAt: 0 },
  ],
  page: 1,
  pageSize: 20,
  total: 3,
};

function renderPage() {
  return renderWithProviders(
    <Routes>
      <Route path="/rooms" element={<Rooms />} />
    </Routes>,
    { route: "/rooms" },
  );
}

const listUrls = (mockFetch: FetchMock) => mockFetch.mock.calls.map(([url]) => String(url)).filter((url) => url.startsWith("/api/v1/rooms?"));

describe("Rooms page", () => {
  let mockFetch: FetchMock;
  let zone: ReturnType<typeof pinBrowserTimeZone>;

  beforeEach(() => {
    mockFetch = vi.fn(() => Promise.resolve(jsonResponse(200, PAGE)));
    vi.stubGlobal("fetch", mockFetch);
    localStorage.setItem("adder.auth.token", "fake-token");
    zone = pinBrowserTimeZone("Pacific/Auckland");
  });

  afterEach(() => {
    zone.mockRestore();
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  test("lists the caller's active rooms as links with role, members and dates, under the page outline", async () => {
    renderPage();
    expect(await screen.findByRole("link", { name: "Open room Night Shift" })).toHaveAttribute("href", "/rooms/1");
    expect(screen.getByRole("link", { name: "New room" })).toHaveAttribute("href", "/rooms/new");
    expect(screen.getByText("Handover notes")).toBeInTheDocument();
    const row = screen.getByRole("row", { name: /Night Shift/ });
    expect(within(row).getByText("Owner")).toBeInTheDocument();
    expect(within(row).getByText("3")).toBeInTheDocument();
    // 1970-01-02T20:00Z and 1970-01-03T20:00Z, read in the pinned zone (Auckland, a day ahead of UTC by then).
    expect(within(row).getByText("1970-01-03")).toBeInTheDocument();
    expect(within(row).getByText("1970-01-04")).toBeInTheDocument();
    // An archived row is marked in words; an ADMIN's non-member row has no role.
    expect(within(screen.getByRole("row", { name: /Old Incident/ })).getByText("Archived")).toBeInTheDocument();
    expect(within(screen.getByRole("row", { name: /Someone Else's/ })).getByText("—")).toBeInTheDocument();
    expect(headingOutline()).toEqual([[2, "Rooms"]]);
    // A regular user has no Mine/All control and only ever asks for their own, active rooms.
    expect(screen.queryByRole("radio", { name: "All" })).not.toBeInTheDocument();
    expect(listUrls(mockFetch)).toEqual(["/api/v1/rooms?page=1&pageSize=20&sort=name&archived=false&member=true"]);
  });

  test("Show archived asks for both, and the choice persists", async () => {
    const user = userEvent.setup();
    renderPage();
    await screen.findByRole("link", { name: "Open room Night Shift" });
    await user.click(screen.getByRole("switch", { name: "Include archived rooms" }));
    await waitFor(() => expect(listUrls(mockFetch).at(-1)).toBe("/api/v1/rooms?page=1&pageSize=20&sort=name&member=true"));
    expect(localStorage.getItem("adder.viewSettings.rooms.showArchived")).toBe("true");
  });

  test("an administrator chooses between Mine and All; All drops the member filter, and the choice persists", async () => {
    localStorage.setItem(ROLES_KEY, JSON.stringify(["ADMIN"]));
    const user = userEvent.setup();
    renderPage();
    const scope = await screen.findByRole("radiogroup", { name: "Which rooms to list" });
    expect(within(scope).getByRole("radio", { name: "Mine" })).toBeChecked();
    await user.click(within(scope).getByText("All"));
    await waitFor(() => expect(listUrls(mockFetch).at(-1)).toBe("/api/v1/rooms?page=1&pageSize=20&sort=name&archived=false"));
    expect(localStorage.getItem("adder.viewSettings.rooms.scope")).toBe('"all"');
  });

  test("a stored All is ignored for a regular user", async () => {
    localStorage.setItem("adder.viewSettings.rooms.scope", '"all"');
    renderPage();
    await screen.findByRole("link", { name: "Open room Night Shift" });
    expect(listUrls(mockFetch)[0]).toContain("member=true");
  });

  test("the name filter goes to the server, debounced", async () => {
    const user = userEvent.setup();
    renderPage();
    await screen.findByRole("link", { name: "Open room Night Shift" });
    await user.click(screen.getByRole("button", { name: "Filters" }));
    await user.type(screen.getByRole("textbox", { name: "Name" }), "night");
    await waitFor(() => expect(listUrls(mockFetch).at(-1)).toContain("name=night"));
  });

  test("sorting by a column header sends the sort", async () => {
    const user = userEvent.setup();
    renderPage();
    await screen.findByRole("link", { name: "Open room Night Shift" });
    await user.click(screen.getByRole("columnheader", { name: /Last activity/ }).querySelector("button")!);
    await waitFor(() => expect(listUrls(mockFetch).at(-1)).toContain("sort=lastActivityAt"));
  });

  test("an empty list shows the empty state, a failed one an alert", async () => {
    mockFetch.mockImplementation(() => Promise.resolve(jsonResponse(200, { ...PAGE, items: [], total: 0 })));
    const first = renderPage();
    expect(await screen.findByText("No rooms yet")).toBeInTheDocument();
    first.unmount();
    mockFetch.mockImplementation(() => Promise.resolve(jsonResponse(500, { title: "Boom", status: 500 })));
    renderPage();
    expect(await screen.findByText("Could not load the rooms")).toBeInTheDocument();
  });
});
