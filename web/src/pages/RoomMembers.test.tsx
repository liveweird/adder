import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { screen, waitFor, within } from "@testing-library/react";
import { Route, Routes } from "react-router-dom";
import { QueryClient } from "@tanstack/react-query";
import RoomMembers from "./RoomMembers";
import { headingOutline } from "../test/headings";
import { jsonResponse } from "../test/http";
import { renderWithProviders } from "../test/render";
import { pinBrowserTimeZone } from "../test/timeZone";
import { FakeClock } from "../test/socket";
import { createLiveInvalidator } from "../utils/liveKeys";

type FetchMock = ReturnType<typeof vi.fn>;

const ROOM = {
  id: 12,
  name: "Night Shift",
  description: null,
  archived: false,
  archivedAt: null,
  role: "OWNER",
  memberCount: 3,
  workflowRevision: 1,
  lastActivityAt: 1,
  createdAt: 1,
  updatedAt: 1,
};
const ROSTER = [
  { userId: 7, name: "Olga Owner", email: "olga@example.test", role: "OWNER", addedAt: Date.UTC(2026, 9, 31, 20) },
  { userId: 8, name: "Max Member", email: "max@example.test", role: "MEMBER", addedAt: Date.UTC(2026, 10, 2) },
  { userId: 9, name: "Otto Second", email: "otto@example.test", role: "OWNER", addedAt: Date.UTC(2026, 10, 3) },
];
const DIRECTORY = {
  items: [
    { id: 20, name: "Eli Newcomer", email: "eli@example.test" },
    { id: 8, name: "Max Member", email: "max@example.test" },
  ],
  page: 1,
  pageSize: 20,
  total: 2,
};
const TEAMS = {
  items: [
    { id: 5, name: "Backend", description: null, managerId: null, managerName: null, memberCount: 2, createdAt: 1, updatedAt: 2 },
    { id: 6, name: "Design", description: null, managerId: null, managerName: null, memberCount: 0, createdAt: 1, updatedAt: 2 },
  ],
  page: 1,
  pageSize: 50,
  total: 2,
};
const team = (id: number, name: string, count: number) => ({
  id,
  name,
  description: null,
  managerId: null,
  managerName: null,
  members: Array.from({ length: count }, (_, i) => ({ userId: 30 + i, name: `Dev ${i}`, email: `dev${i}@example.test` })),
  createdAt: 1,
  updatedAt: 2,
});

type ServeOptions = {
  room?: Record<string, unknown>;
  fail?: Record<string, Response>;
  add?: unknown;
  /** The members list claims this many members in total and answers an empty second page (a page emptied under the reader). */
  total?: number;
  /** Runs on every members-list read (a hook for a server that changes its answer once the page has asked). */
  onMembers?: () => void;
  /** A role change waits for this before it answers. */
  holdRole?: Promise<void>;
};

/**
 * The roster follows the writes (a role change, a removal) and the detail reads the roster's size; `fail` answers a request instead
 * (keyed `METHOD url`). The returned `setRoom` changes what the room's detail answers next (`null` = a 404: the caller lost access).
 */
function serve(mockFetch: FetchMock, opts: ServeOptions = {}) {
  let roster = ROSTER.map((member) => ({ ...member }));
  let room: Record<string, unknown> | null = opts.room ?? ROOM;
  mockFetch.mockImplementation((url: string, init?: RequestInit) => {
    const method = init?.method ?? "GET";
    const key = `${method} ${url}`;
    if (method === "GET" && url.startsWith("/api/v1/rooms/12/members?")) opts.onMembers?.();
    if (opts.fail?.[key]) return Promise.resolve(opts.fail[key]);
    if (key === "GET /api/v1/rooms/12") return Promise.resolve(room ? jsonResponse(200, { ...room, memberCount: roster.length }) : problem(404));
    if (method === "GET" && url.startsWith("/api/v1/rooms/12/members?")) {
      const params = new URL(url, "http://x").searchParams;
      if (opts.total !== undefined && params.get("page") === "2") return Promise.resolve(jsonResponse(200, { items: [], page: 2, pageSize: 20, total: opts.total }));
      const items = roster.filter((m) => (!params.get("role") || m.role === params.get("role")) && (!params.get("name") || m.name.toLowerCase().includes(params.get("name")!.toLowerCase())));
      return Promise.resolve(jsonResponse(200, { items, page: 1, pageSize: 20, total: opts.total ?? items.length }));
    }
    if (method === "GET" && url.startsWith("/api/v1/users/directory?")) return Promise.resolve(jsonResponse(200, DIRECTORY));
    if (method === "GET" && url.startsWith("/api/v1/teams?")) return Promise.resolve(jsonResponse(200, TEAMS));
    if (key === "GET /api/v1/teams/5") return Promise.resolve(jsonResponse(200, team(5, "Backend", 2)));
    if (key === "GET /api/v1/teams/6") return Promise.resolve(jsonResponse(200, team(6, "Design", 0)));
    if (key === "POST /api/v1/rooms/12/members") return Promise.resolve(jsonResponse(200, opts.add ?? { added: 1, alreadyMembers: 0 }));
    const role = /^PUT \/api\/v1\/rooms\/12\/members\/(\d+)$/.exec(key);
    if (role) {
      const body = JSON.parse(init!.body as string) as { role: string };
      roster = roster.map((m) => (String(m.userId) === role[1] ? { ...m, role: body.role } : m));
      return (opts.holdRole ?? Promise.resolve()).then(() => new Response(null, { status: 204 }));
    }
    const removal = /^DELETE \/api\/v1\/rooms\/12\/members\/(\d+)$/.exec(key);
    if (removal) {
      roster = roster.filter((m) => String(m.userId) !== removal[1]);
      return Promise.resolve(new Response(null, { status: 204 }));
    }
    return Promise.resolve(jsonResponse(404, { title: "Not found", status: 404 }));
  });
  return {
    setRoom: (next: Record<string, unknown> | null) => {
      room = next;
    },
  };
}

const problem = (status: number, type?: string) => jsonResponse(status, { title: "x", status, ...(type ? { type } : {}) });
// The room's own "seen" POST (opening a room is looking at it) is view state, not one of the writes these tests count.
const calls = (mockFetch: FetchMock, method: string) =>
  mockFetch.mock.calls.filter(([url, init]) => ((init as RequestInit | undefined)?.method ?? "GET") === method && !String(url).endsWith("/seen")).map(([url, init]) => ({ url: url as string, body: (init as RequestInit | undefined)?.body }));
const detailGets = (mockFetch: FetchMock) => mockFetch.mock.calls.filter(([url, init]) => url === "/api/v1/rooms/12" && ((init as RequestInit | undefined)?.method ?? "GET") === "GET").length;
const memberCalls = (mockFetch: FetchMock) => mockFetch.mock.calls.map(([url]) => url as string).filter((url) => url.startsWith("/api/v1/rooms/12/members?"));

function renderMembers(queryClient?: QueryClient) {
  return renderWithProviders(
    <Routes>
      <Route path="/rooms/:id/members" element={<RoomMembers />} />
      <Route path="/rooms" element={<p>the rooms list</p>} />
    </Routes>,
    { route: "/rooms/12/members", queryClient },
  );
}

/** Sign in as the given user id and roles (an ADMIN by default; the caller below is the room's OWNER `Olga`, id 7). */
function signIn(userId: number, roles: string[] = []) {
  localStorage.setItem("adder.auth.token", "fake-token");
  localStorage.setItem("adder.auth.roles", JSON.stringify(roles));
  localStorage.setItem("adder.auth.userId", String(userId));
}

async function openRowMenu(user: ReturnType<typeof userEvent.setup>, name: string) {
  await user.click(await screen.findByRole("button", { name: `Operations for ${name}` }));
}

describe("RoomMembers page", () => {
  let mockFetch: FetchMock;
  let zone: ReturnType<typeof pinBrowserTimeZone>;

  beforeEach(() => {
    mockFetch = vi.fn();
    vi.stubGlobal("fetch", mockFetch);
    signIn(7);
    zone = pinBrowserTimeZone("Pacific/Auckland");
  });

  afterEach(() => {
    zone.mockRestore();
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  test("an owner sees the add controls and the members, a section Leave button and a menu on the other rows", async () => {
    serve(mockFetch);
    renderMembers();
    const table = await screen.findByRole("table", { name: "Members" });
    expect(await within(table).findByText("max@example.test")).toBeInTheDocument();
    expect(within(table).getAllByRole("row")).toHaveLength(4);
    expect(screen.getByText("See who has access to this room and their role.")).toBeInTheDocument();
    expect(screen.getByRole("form", { name: "Add a person" })).toBeInTheDocument();
    expect(screen.getByRole("form", { name: "Add a whole team" })).toBeInTheDocument();
    // The date, role and a "You" marker on the caller's own row.
    const own = within(table).getByRole("row", { name: /Olga Owner/ });
    expect(within(own).getByText("You")).toBeInTheDocument();
    // A member's name is a link to their profile (1.2 D6), one tab stop in the row; the email stays text.
    expect(within(own).getByRole("link", { name: "Olga Owner" })).toHaveAttribute("href", "/people/7");
    expect(within(table).getByRole("link", { name: "Max Member" })).toHaveAttribute("href", "/people/8");
    expect(within(own).getByText("Owner")).toBeInTheDocument();
    // Added 2026-10-31T20:00Z: the 1st in the pinned zone (Auckland), the reader's day rather than UTC's.
    expect(within(own).getByText("2026-11-01")).toBeInTheDocument();
    // Leaving is the section's one button, not a row action: the own row carries neither it nor a menu.
    expect(within(own).queryByRole("button")).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Leave room" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Operations for Max Member" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Members" })).toHaveAttribute("aria-current", "page");
    // The tab names the room's page, not just the room (WCAG 2.4.2).
    expect(document.title).toBe("Members · Night Shift · Adder");
    expect(headingOutline()).toEqual([
      [2, "Night Shift"],
      [3, "Members"],
      [4, "Add people"],
    ]);
    expect(memberCalls(mockFetch)).toEqual(["/api/v1/rooms/12/members?page=1&pageSize=20&sort=name"]);
  });

  test("a plain member reads the list read-only and can still leave", async () => {
    signIn(8);
    serve(mockFetch, { room: { ...ROOM, role: "MEMBER" } });
    renderMembers();
    const table = await screen.findByRole("table", { name: "Members" });
    expect(await within(table).findByText("olga@example.test")).toBeInTheDocument();
    expect(screen.queryByRole("combobox", { name: "Add a person" })).not.toBeInTheDocument();
    expect(screen.queryByRole("combobox", { name: "Add a whole team" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /Operations for/ })).not.toBeInTheDocument();
    expect(screen.getAllByRole("button", { name: "Leave room" })).toHaveLength(1);
    expect(headingOutline()).toEqual([
      [2, "Night Shift"],
      [3, "Members"],
    ]);
  });

  test("an administrator who is not a member manages everyone and has nothing to leave", async () => {
    signIn(1, ["ADMIN"]);
    serve(mockFetch, { room: { ...ROOM, role: null } });
    renderMembers();
    expect(await screen.findByRole("combobox", { name: "Add a person" })).toBeInTheDocument();
    expect(await screen.findByRole("button", { name: "Operations for Olga Owner" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Operations for Max Member" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Leave room" })).not.toBeInTheDocument();
  });

  test("the controls stay on an archived room (members may still be managed)", async () => {
    serve(mockFetch, { room: { ...ROOM, archived: true } });
    renderMembers();
    expect(await screen.findByRole("combobox", { name: "Add a person" })).toBeInTheDocument();
    expect(await screen.findByRole("button", { name: "Operations for Max Member" })).toBeInTheDocument();
  });

  test("the name and role filters and the sort go to the server", async () => {
    serve(mockFetch);
    const user = userEvent.setup();
    renderMembers();
    await screen.findByText("max@example.test");
    await user.click(screen.getByRole("button", { name: "Filters" }));
    await user.type(screen.getByRole("textbox", { name: "Name" }), "otto");
    await waitFor(() => expect(memberCalls(mockFetch).at(-1)).toBe("/api/v1/rooms/12/members?page=1&pageSize=20&sort=name&name=otto"));
    await user.click(screen.getByRole("combobox", { name: "Role" }));
    await user.click(await screen.findByRole("option", { name: "Owner" }));
    await waitFor(() => expect(memberCalls(mockFetch).at(-1)).toBe("/api/v1/rooms/12/members?page=1&pageSize=20&sort=name&name=otto&role=OWNER"));
    await user.click(screen.getByRole("button", { name: "Added" }));
    await waitFor(() => expect(memberCalls(mockFetch).at(-1)).toContain("sort=addedAt"));
    expect(screen.getByRole("columnheader", { name: "Added" })).toHaveAttribute("aria-sort", "ascending");
  });

  test("a failed load is an inline alert", async () => {
    serve(mockFetch, { fail: { "GET /api/v1/rooms/12/members?page=1&pageSize=20&sort=name": problem(500) } });
    renderMembers();
    expect(await screen.findByText("Could not load the members")).toBeInTheDocument();
  });

  test("a filter with no match says so", async () => {
    serve(mockFetch);
    const user = userEvent.setup();
    renderMembers();
    await screen.findByText("max@example.test");
    await user.click(screen.getByRole("button", { name: "Filters" }));
    await user.type(screen.getByRole("textbox", { name: "Name" }), "zzz");
    expect(await screen.findByText("No members match")).toBeInTheDocument();
  });

  describe("adding a person", () => {
    test("searches the directory, leaves current members out, POSTs the id and refreshes", async () => {
      serve(mockFetch);
      const user = userEvent.setup();
      renderMembers();
      const picker = await screen.findByRole("combobox", { name: "Add a person" });
      await screen.findByText("max@example.test");
      const add = screen.getByRole("button", { name: "Add person" });
      expect(add).toBeDisabled();
      await user.type(picker, "el");
      // Max is on the current page: not offered. Eli is.
      await user.click(await screen.findByRole("option", { name: "Eli Newcomer (eli@example.test)" }));
      expect(screen.queryByRole("option", { name: "Max Member (max@example.test)" })).not.toBeInTheDocument();
      const before = memberCalls(mockFetch).length;
      const detailBefore = detailGets(mockFetch);
      await user.click(add);
      await waitFor(() => expect(calls(mockFetch, "POST")).toHaveLength(1));
      expect(calls(mockFetch, "POST")[0].url).toBe("/api/v1/rooms/12/members");
      expect(JSON.parse(calls(mockFetch, "POST")[0].body as string)).toEqual({ userIds: [20] });
      await waitFor(() => expect(memberCalls(mockFetch).length).toBeGreaterThan(before));
      // The room's own read (its member count) refreshed with the list.
      await waitFor(() => expect(detailGets(mockFetch)).toBeGreaterThan(detailBefore));
      await waitFor(() => expect(screen.getByRole("combobox", { name: "Add a person" })).toHaveValue(""));
      expect(screen.getByRole("button", { name: "Add person" })).toBeDisabled();
    });

    test("somebody who already belongs is still a success (the server counts them)", async () => {
      serve(mockFetch, { add: { added: 0, alreadyMembers: 1 } });
      const user = userEvent.setup();
      renderMembers();
      await user.type(await screen.findByRole("combobox", { name: "Add a person" }), "el");
      await user.click(await screen.findByRole("option", { name: "Eli Newcomer (eli@example.test)" }));
      await user.click(screen.getByRole("button", { name: "Add person" }));
      await waitFor(() => expect(calls(mockFetch, "POST")).toHaveLength(1));
      await waitFor(() => expect(screen.getByRole("combobox", { name: "Add a person" })).toHaveValue(""));
      expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    });

    test.each([
      [400, "That person or team no longer exists."],
      [403, "Only the room's owners and administrators can add members."],
      [404, "This room no longer exists, or you no longer have access to it — reload the page"],
      [409, "A room holds at most 500 members, so this would not fit. Nobody was added."],
      [429, "Too many requests. Wait a minute and try again."],
      [500, "Action failed (500)"],
    ])("a %i on the add shows its inline message", async (status, message) => {
      serve(mockFetch, { fail: { "POST /api/v1/rooms/12/members": problem(status) } });
      const user = userEvent.setup();
      renderMembers();
      await user.type(await screen.findByRole("combobox", { name: "Add a person" }), "el");
      await user.click(await screen.findByRole("option", { name: "Eli Newcomer (eli@example.test)" }));
      await user.click(screen.getByRole("button", { name: "Add person" }));
      const alert = await screen.findByRole("alert");
      expect(alert).toHaveTextContent("Could not add to the room");
      expect(alert).toHaveTextContent(message);
    });
  });

  describe("adding a team", () => {
    async function pickTeam(user: ReturnType<typeof userEvent.setup>, name: string) {
      await user.click(await screen.findByRole("combobox", { name: "Add a whole team" }));
      await user.click(await screen.findByRole("option", { name }));
    }

    test("the confirm states the count, and Add team POSTs the team id", async () => {
      serve(mockFetch, { add: { added: 1, alreadyMembers: 1 } });
      const user = userEvent.setup();
      renderMembers();
      expect(await screen.findByRole("button", { name: "Add team" })).toBeDisabled();
      await pickTeam(user, "Backend");
      await user.click(screen.getByRole("button", { name: "Add team" }));
      const dialog = await screen.findByRole("dialog", { name: "Add the team?" });
      expect(within(dialog).getByText(/“Backend” has 2 people\. Add them all to this room as members\?/)).toBeInTheDocument();
      expect(calls(mockFetch, "POST")).toEqual([]);
      await user.click(within(dialog).getByRole("button", { name: "Add team" }));
      await waitFor(() => expect(calls(mockFetch, "POST")).toHaveLength(1));
      expect(JSON.parse(calls(mockFetch, "POST")[0].body as string)).toEqual({ teamId: 5 });
      await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
      await waitFor(() => expect(screen.getByRole("combobox", { name: "Add a whole team" })).toHaveValue(""));
    });

    test("Cancel adds nothing", async () => {
      serve(mockFetch);
      const user = userEvent.setup();
      renderMembers();
      await pickTeam(user, "Backend");
      await user.click(screen.getByRole("button", { name: "Add team" }));
      await user.click(within(await screen.findByRole("dialog")).getByRole("button", { name: "Cancel" }));
      await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
      expect(calls(mockFetch, "POST")).toEqual([]);
    });

    test("a team with nobody on it opens no confirm", async () => {
      serve(mockFetch);
      const user = userEvent.setup();
      renderMembers();
      await pickTeam(user, "Design");
      await user.click(screen.getByRole("button", { name: "Add team" }));
      expect(await screen.findByRole("alert")).toHaveTextContent("This team has no members, so there is nobody to add.");
      expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
      expect(calls(mockFetch, "POST")).toEqual([]);
    });

    test("a team that cannot be read shows an inline message", async () => {
      serve(mockFetch, { fail: { "GET /api/v1/teams/5": problem(404) } });
      const user = userEvent.setup();
      renderMembers();
      await pickTeam(user, "Backend");
      await user.click(screen.getByRole("button", { name: "Add team" }));
      const alert = await screen.findByRole("alert");
      expect(alert).toHaveTextContent("Could not read the team");
      expect(alert).toHaveTextContent("That person or team no longer exists.");
    });

    test("a refused add (a full room) closes the dialog and says so inline", async () => {
      serve(mockFetch, { fail: { "POST /api/v1/rooms/12/members": problem(409) } });
      const user = userEvent.setup();
      renderMembers();
      await pickTeam(user, "Backend");
      await user.click(screen.getByRole("button", { name: "Add team" }));
      await user.click(within(await screen.findByRole("dialog")).getByRole("button", { name: "Add team" }));
      expect(await screen.findByText("A room holds at most 500 members, so this would not fit. Nobody was added.")).toBeInTheDocument();
      await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    });
  });

  describe("roles", () => {
    test("Make owner PUTs OWNER and the row follows; Make member sends it back", async () => {
      serve(mockFetch);
      const user = userEvent.setup();
      renderMembers();
      await openRowMenu(user, "Max Member");
      const detailBefore = detailGets(mockFetch);
      await user.click(await screen.findByRole("menuitem", { name: "Make owner" }));
      await waitFor(() => expect(calls(mockFetch, "PUT")).toHaveLength(1));
      await waitFor(() => expect(detailGets(mockFetch)).toBeGreaterThan(detailBefore));
      expect(calls(mockFetch, "PUT")[0].url).toBe("/api/v1/rooms/12/members/8");
      expect(JSON.parse(calls(mockFetch, "PUT")[0].body as string)).toEqual({ role: "OWNER" });
      const row = () => screen.getByRole("row", { name: /Max Member/ });
      await waitFor(() => expect(within(row()).getByText("Owner")).toBeInTheDocument());
      await openRowMenu(user, "Max Member");
      expect(screen.queryByRole("menuitem", { name: "Make owner" })).not.toBeInTheDocument();
      await user.click(await screen.findByRole("menuitem", { name: "Make member" }));
      await waitFor(() => expect(calls(mockFetch, "PUT")).toHaveLength(2));
      expect(JSON.parse(calls(mockFetch, "PUT")[1].body as string)).toEqual({ role: "MEMBER" });
      await waitFor(() => expect(within(row()).getByText("Member")).toBeInTheDocument());
    });

    test.each([
      [problem(409, "urn:adder:problem:last-owner"), "A room needs at least one owner. Make someone else an owner first."],
      [problem(403), "Only the room's owners and administrators can do that."],
      [problem(404), "This person is no longer a member of the room."],
      [problem(500), "Action failed (500)"],
    ])("a refused role change shows its inline message", async (response, message) => {
      serve(mockFetch, { fail: { "PUT /api/v1/rooms/12/members/9": response } });
      const user = userEvent.setup();
      renderMembers();
      await openRowMenu(user, "Otto Second");
      await user.click(await screen.findByRole("menuitem", { name: "Make member" }));
      const alert = await screen.findByRole("alert");
      expect(alert).toHaveTextContent("Could not change the role");
      expect(alert).toHaveTextContent(message);
    });
  });

  describe("removing and leaving", () => {
    test("Remove asks first, then DELETEs and the row is gone", async () => {
      serve(mockFetch);
      const user = userEvent.setup();
      renderMembers();
      await openRowMenu(user, "Max Member");
      await user.click(await screen.findByRole("menuitem", { name: "Remove" }));
      const dialog = await screen.findByRole("dialog", { name: "Remove from the room?" });
      expect(within(dialog).getByText("Max Member loses access to “Night Shift”. You can add them again later.")).toBeInTheDocument();
      expect(calls(mockFetch, "DELETE")).toEqual([]);
      const detailBefore = detailGets(mockFetch);
      await user.click(within(dialog).getByRole("button", { name: "Remove" }));
      await waitFor(() => expect(calls(mockFetch, "DELETE")).toHaveLength(1));
      expect(calls(mockFetch, "DELETE")[0].url).toBe("/api/v1/rooms/12/members/8");
      await waitFor(() => expect(screen.queryByText("max@example.test")).not.toBeInTheDocument());
      // The room's own read (its member count) refreshed with the list.
      await waitFor(() => expect(detailGets(mockFetch)).toBeGreaterThan(detailBefore));
      expect(screen.getByText("2 members")).toBeInTheDocument();
    });

    test("Cancel on the remove confirm sends nothing", async () => {
      serve(mockFetch);
      const user = userEvent.setup();
      renderMembers();
      await openRowMenu(user, "Max Member");
      await user.click(await screen.findByRole("menuitem", { name: "Remove" }));
      await user.click(within(await screen.findByRole("dialog")).getByRole("button", { name: "Cancel" }));
      await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
      expect(calls(mockFetch, "DELETE")).toEqual([]);
    });

    test.each([
      [problem(409, "urn:adder:problem:last-owner"), "A room needs at least one owner. Make someone else an owner first."],
      [problem(404), "This person is no longer a member of the room."],
    ])("a refused removal closes the dialog and says why inline", async (response, message) => {
      serve(mockFetch, { fail: { "DELETE /api/v1/rooms/12/members/8": response } });
      const user = userEvent.setup();
      renderMembers();
      await openRowMenu(user, "Max Member");
      await user.click(await screen.findByRole("menuitem", { name: "Remove" }));
      await user.click(within(await screen.findByRole("dialog")).getByRole("button", { name: "Remove" }));
      const alert = await screen.findByRole("alert");
      expect(alert).toHaveTextContent("Could not remove the member");
      expect(alert).toHaveTextContent(message);
      await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    });

    test("Leave room asks first, DELETEs the caller's own membership, goes to the rooms list, drops the room's queries and re-reads the rest", async () => {
      serve(mockFetch);
      const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
      // A rail query the room list (not mounted here) would hold: it must be marked stale by the leave.
      queryClient.setQueryData(["rooms", "rail", "active"], { items: [], page: 1, pageSize: 100, total: 0 });
      const user = userEvent.setup();
      renderMembers(queryClient);
      await user.click(await screen.findByRole("button", { name: "Leave room" }));
      const dialog = await screen.findByRole("dialog", { name: "Leave this room?" });
      expect(within(dialog).getByText("You lose access to “Night Shift” until an owner adds you again.")).toBeInTheDocument();
      expect(calls(mockFetch, "DELETE")).toEqual([]);
      const readsBefore = mockFetch.mock.calls.length;
      await user.click(within(dialog).getByRole("button", { name: "Leave room" }));
      await waitFor(() => expect(calls(mockFetch, "DELETE")).toHaveLength(1));
      expect(calls(mockFetch, "DELETE")[0].url).toBe("/api/v1/rooms/12/members/7");
      expect(await screen.findByText("the rooms list")).toBeInTheDocument();
      // The room's own queries are gone and nothing re-read them while the page was still there (they would only answer 404).
      await waitFor(() => expect(queryClient.getQueryCache().findAll({ queryKey: ["rooms", "detail", 12] })).toEqual([]));
      expect(queryClient.getQueryCache().findAll({ queryKey: ["rooms", "members", 12] })).toEqual([]);
      expect(queryClient.getQueryCache().findAll({ queryKey: ["rooms", "workflow", 12] })).toEqual([]);
      await waitFor(() => expect(queryClient.getQueryState(["rooms", "rail", "active"])?.isInvalidated).toBe(true));
      const after = mockFetch.mock.calls.slice(readsBefore).map(([url]) => url as string);
      expect(after.filter((url) => url.startsWith("/api/v1/rooms/12"))).toEqual(["/api/v1/rooms/12/members/7"]);
    });

    test("the last owner cannot leave: the dialog closes and the page tells them to hand over first", async () => {
      serve(mockFetch, { fail: { "DELETE /api/v1/rooms/12/members/7": problem(409, "urn:adder:problem:last-owner") } });
      const user = userEvent.setup();
      renderMembers();
      await user.click(await screen.findByRole("button", { name: "Leave room" }));
      await user.click(within(await screen.findByRole("dialog")).getByRole("button", { name: "Leave room" }));
      const alert = await screen.findByRole("alert");
      expect(alert).toHaveTextContent("Could not leave the room");
      expect(alert).toHaveTextContent("You are the only owner of this room. Make someone else an owner before you leave.");
      expect(screen.queryByText("the rooms list")).not.toBeInTheDocument();
      await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    });
  });
});

// A bucket's 429 on the three member writes (the chat-write bucket, with a Retry-After): the shared "too fast" words.
describe("RoomMembers page: past the chat-write bucket", () => {
  let mockFetch: FetchMock;
  const tooFast = (retryAfter?: string) => jsonResponse(429, { title: "Too Many Requests", status: 429 }, retryAfter ? { "Retry-After": retryAfter } : {});

  beforeEach(() => {
    mockFetch = vi.fn();
    vi.stubGlobal("fetch", mockFetch);
    signIn(7);
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  test("a role change says how long to wait", async () => {
    serve(mockFetch, { fail: { "PUT /api/v1/rooms/12/members/9": tooFast("5") } });
    const user = userEvent.setup();
    renderMembers();
    await openRowMenu(user, "Otto Second");
    await user.click(await screen.findByRole("menuitem", { name: "Make member" }));
    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent("Could not change the role");
    expect(alert).toHaveTextContent("You're doing that too fast. Try again in 5 seconds.");
  });

  test("a removal says it too", async () => {
    serve(mockFetch, { fail: { "DELETE /api/v1/rooms/12/members/8": tooFast("1") } });
    const user = userEvent.setup();
    renderMembers();
    await openRowMenu(user, "Max Member");
    await user.click(await screen.findByRole("menuitem", { name: "Remove" }));
    await user.click(within(await screen.findByRole("dialog")).getByRole("button", { name: "Remove" }));
    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent("Could not remove the member");
    expect(alert).toHaveTextContent("You're doing that too fast. Try again in 1 second.");
  });

  test("a leave says it too, in a moment when the answer names no wait", async () => {
    serve(mockFetch, { fail: { "DELETE /api/v1/rooms/12/members/7": tooFast() } });
    const user = userEvent.setup();
    renderMembers();
    await user.click(await screen.findByRole("button", { name: "Leave room" }));
    await user.click(within(await screen.findByRole("dialog")).getByRole("button", { name: "Leave room" }));
    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent("Could not leave the room");
    expect(alert).toHaveTextContent("You're doing that too fast. Try again in a moment.");
    expect(screen.queryByText("the rooms list")).not.toBeInTheDocument();
  });
});

// A further top-level block keeps each function within the size cap; the setup is the same.
describe("RoomMembers page: the roster moves under the reader", () => {
  let mockFetch: FetchMock;

  beforeEach(() => {
    mockFetch = vi.fn();
    vi.stubGlobal("fetch", mockFetch);
    signIn(7);
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  describe("losing access mid-session", () => {
    test("demoted behind the page: a 403 re-reads the room and the controls disappear", async () => {
      const server = serve(mockFetch, { fail: { "POST /api/v1/rooms/12/members": problem(403) } });
      const user = userEvent.setup();
      renderMembers();
      await user.type(await screen.findByRole("combobox", { name: "Add a person" }), "el");
      await user.click(await screen.findByRole("option", { name: "Eli Newcomer (eli@example.test)" }));
      // Somebody demotes the caller before they press Add.
      server.setRoom({ ...ROOM, role: "MEMBER" });
      await user.click(screen.getByRole("button", { name: "Add person" }));
      await waitFor(() => expect(screen.queryByRole("combobox", { name: "Add a person" })).not.toBeInTheDocument());
      expect(screen.queryByRole("combobox", { name: "Add a whole team" })).not.toBeInTheDocument();
      await waitFor(() => expect(screen.queryByRole("button", { name: /Operations for/ })).not.toBeInTheDocument());
      // The list stays, read-only, and leaving is still possible.
      expect(screen.getByText("max@example.test")).toBeInTheDocument();
      expect(screen.getByRole("button", { name: "Leave room" })).toBeInTheDocument();
      // The refusal's words outlive the controls it took away.
      expect(screen.getByRole("alert")).toHaveTextContent("Only the room's owners and administrators can add members.");
    });

    test("demoted behind the page: a 403 on a role change takes the row menus away", async () => {
      const server = serve(mockFetch, { fail: { "PUT /api/v1/rooms/12/members/8": problem(403) } });
      const user = userEvent.setup();
      renderMembers();
      await openRowMenu(user, "Max Member");
      server.setRoom({ ...ROOM, role: "MEMBER" });
      await user.click(await screen.findByRole("menuitem", { name: "Make owner" }));
      await waitFor(() => expect(screen.queryByRole("button", { name: /Operations for/ })).not.toBeInTheDocument());
      expect(screen.queryByRole("combobox", { name: "Add a person" })).not.toBeInTheDocument();
    });

    test("removed behind the page: a 404 on the add re-reads the room and the page turns to not-found", async () => {
      const server = serve(mockFetch, { fail: { "POST /api/v1/rooms/12/members": problem(404) } });
      const user = userEvent.setup();
      renderMembers();
      await user.type(await screen.findByRole("combobox", { name: "Add a person" }), "el");
      await user.click(await screen.findByRole("option", { name: "Eli Newcomer (eli@example.test)" }));
      server.setRoom(null);
      await user.click(screen.getByRole("button", { name: "Add person" }));
      expect(await screen.findByText("This room does not exist, or you are not a member of it.")).toBeInTheDocument();
      expect(screen.queryByRole("table", { name: "Members" })).not.toBeInTheDocument();
    });

    test("a 409 on a removal re-reads the room too (the roster moved)", async () => {
      serve(mockFetch, { fail: { "DELETE /api/v1/rooms/12/members/8": problem(409) } });
      const user = userEvent.setup();
      renderMembers();
      await openRowMenu(user, "Max Member");
      await user.click(await screen.findByRole("menuitem", { name: "Remove" }));
      const detailBefore = detailGets(mockFetch);
      await user.click(within(await screen.findByRole("dialog")).getByRole("button", { name: "Remove" }));
      await waitFor(() => expect(detailGets(mockFetch)).toBeGreaterThan(detailBefore));
    });

    test("the members list itself answering 404 re-reads the room, which turns the page to not-found", async () => {
      const server: { setRoom?: (next: null) => void } = {};
      Object.assign(
        server,
        serve(mockFetch, {
          fail: { "GET /api/v1/rooms/12/members?page=1&pageSize=20&sort=name": problem(404) },
          onMembers: () => server.setRoom?.(null),
        }),
      );
      renderMembers();
      expect(await screen.findByText("This room does not exist, or you are not a member of it.")).toBeInTheDocument();
    });
  });

  test("a page emptied under the reader steps back to the first", async () => {
    // 25 members claimed over two pages; the second turns out empty (they were removed meanwhile).
    serve(mockFetch, { total: 25 });
    const user = userEvent.setup();
    renderMembers();
    await screen.findByText("max@example.test");
    await user.click(screen.getByRole("button", { name: "Next page" }));
    await waitFor(() => expect(memberCalls(mockFetch)).toContain("/api/v1/rooms/12/members?page=2&pageSize=20&sort=name"));
    await waitFor(() => expect(memberCalls(mockFetch).at(-1)).toBe("/api/v1/rooms/12/members?page=1&pageSize=20&sort=name"));
    expect(await screen.findByText("max@example.test")).toBeInTheDocument();
  });

  test("while a role change is in flight the other rows' menus are shut", async () => {
    let release!: () => void;
    serve(mockFetch, { holdRole: new Promise<void>((resolve) => (release = resolve)) });
    const user = userEvent.setup();
    renderMembers();
    await openRowMenu(user, "Max Member");
    await user.click(await screen.findByRole("menuitem", { name: "Make owner" }));
    await waitFor(() => expect(screen.getByRole("button", { name: "Operations for Otto Second" })).toBeDisabled());
    release();
    await waitFor(() => expect(screen.getByRole("button", { name: "Operations for Otto Second" })).toBeEnabled());
  });

  test("the filters are kept per room", async () => {
    serve(mockFetch);
    const user = userEvent.setup();
    renderMembers();
    await screen.findByText("max@example.test");
    await user.click(screen.getByRole("button", { name: "Filters" }));
    await user.type(screen.getByRole("textbox", { name: "Name" }), "otto");
    await waitFor(() => expect(JSON.parse(localStorage.getItem("adder.viewSettings.roomMembers.12.filter.name") ?? "null")).toBe("otto"));
    expect(localStorage.getItem("adder.viewSettings.roomMembers.filter.name")).toBeNull();
  });
});

// The caller's own Leave is followed by the server's `revoked` frame for the same room. The frame goes through the invalidator's settle, so
// the page navigates away first and the room's detail is never re-read into a not-found flash.
describe("RoomMembers page: the caller's own leave and the live `revoked` frame", () => {
  let mockFetch: FetchMock;

  beforeEach(() => {
    mockFetch = vi.fn();
    vi.stubGlobal("fetch", mockFetch);
    signIn(7);
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  test("a revoked that arrives before the Leave DELETE resolves shows no not-found flash", async () => {
    serve(mockFetch);
    const base = mockFetch.getMockImplementation() as (url: string, init?: RequestInit) => Promise<Response>;
    let finishLeave: () => void = () => {};
    const leaveHeld = new Promise<void>((resolve) => (finishLeave = resolve));
    mockFetch.mockImplementation((url: string, init?: RequestInit) =>
      init?.method === "DELETE" ? leaveHeld.then(() => base(url, init)) : base(url, init),
    );
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const clock = new FakeClock();
    const invalidator = createLiveInvalidator(queryClient, clock);
    const user = userEvent.setup();
    renderMembers(queryClient);
    await user.click(await screen.findByRole("button", { name: "Leave room" }));
    await user.click(within(await screen.findByRole("dialog")).getByRole("button", { name: "Leave room" }));
    await waitFor(() => expect(calls(mockFetch, "DELETE")).toHaveLength(1));

    // The server has already removed the caller and says so; the invalidation waits out its settle.
    const readsBefore = detailGets(mockFetch);
    invalidator.handle({ type: "revoked", roomId: 12 });
    clock.advanceBy(299);
    expect(detailGets(mockFetch)).toBe(readsBefore);
    finishLeave();
    expect(await screen.findByText("the rooms list")).toBeInTheDocument();

    // The settle ends after the page is gone: its room queries were dropped, so nothing is re-read and nothing answers 404.
    clock.advanceBy(5000);
    await waitFor(() => expect(queryClient.getQueryCache().findAll({ queryKey: ["rooms", "detail", 12] })).toEqual([]));
    expect(detailGets(mockFetch)).toBe(readsBefore);
    expect(memberCalls(mockFetch)).toHaveLength(1);
    expect(screen.queryByText("This room does not exist, or you are not a member of it.")).not.toBeInTheDocument();
    invalidator.dispose();
  });
});
