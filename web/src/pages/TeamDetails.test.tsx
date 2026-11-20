import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { screen, waitFor, within } from "@testing-library/react";
import { Route, Routes } from "react-router-dom";
import TeamDetails from "./TeamDetails";
import { jsonResponse } from "../test/http";
import { QueryClient } from "@tanstack/react-query";
import { renderWithProviders } from "../test/render";

const TOKEN_KEY = "adder.auth.token";
const ROLES_KEY = "adder.auth.roles";
const USER_ID_KEY = "adder.auth.userId";

type FetchMock = ReturnType<typeof vi.fn>;

const TEAM = {
  id: 1,
  name: "Payments",
  description: "Money movers",
  managerId: 12,
  managerName: "Cara New",
  members: [
    { userId: 12, name: "Cara New", email: "cara@example.test" },
    { userId: 13, name: "Dev Member", email: "dev@example.test" },
  ],
  createdAt: 1,
  updatedAt: 2,
};
const DIRECTORY = {
  items: [
    { id: 20, name: "Eli Newcomer", email: "eli@example.test" },
    { id: 13, name: "Dev Member", email: "dev@example.test" },
  ],
  page: 1,
  pageSize: 20,
  total: 2,
};
function serve(mockFetch: FetchMock, mutations: Record<string, { status: number; body?: unknown }> = {}, team: unknown = TEAM) {
  mockFetch.mockImplementation((url: string, init?: RequestInit) => {
    const method = init?.method ?? "GET";
    const m = mutations[`${method} ${url}`];
    if (m) return Promise.resolve(m.body === undefined ? new Response(null, { status: m.status }) : jsonResponse(m.status, m.body));
    if (method === "GET" && url === "/api/v1/teams/1") return Promise.resolve(jsonResponse(200, team));
    if (method === "GET" && url.startsWith("/api/v1/users/directory?")) return Promise.resolve(jsonResponse(200, DIRECTORY));
    return Promise.resolve(jsonResponse(404, { title: "Not Found", status: 404 }));
  });
}

function findCall(mockFetch: FetchMock, method: string, url: string) {
  return mockFetch.mock.calls.find(([u, init]) => ((init as RequestInit | undefined)?.method ?? "GET") === method && u === url);
}

function renderPage(route = "/teams/1") {
  return renderWithProviders(
    <Routes>
      <Route path="/teams/:id" element={<TeamDetails />} />
    </Routes>,
    { route },
  );
}

describe("TeamDetails page", () => {
  let mockFetch: FetchMock;

  beforeEach(() => {
    mockFetch = vi.fn();
    vi.stubGlobal("fetch", mockFetch);
    localStorage.setItem(TOKEN_KEY, "fake-token");
    localStorage.setItem(ROLES_KEY, JSON.stringify(["ADMIN"]));
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  test("a regular user sees the team, and its manager, and no admin controls", async () => {
    localStorage.setItem(ROLES_KEY, "[]");
    serve(mockFetch);
    renderPage();
    expect(await screen.findByRole("heading", { level: 2, name: "Payments" })).toBeInTheDocument();
    expect(screen.getByText("Money movers")).toBeInTheDocument();
    expect(screen.getByText("Manager: Cara New")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /^edit$/i })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /operations for/i })).not.toBeInTheDocument();
    // The roster is public; its controls are not.
    expect(await screen.findByRole("heading", { level: 3, name: "Members" })).toBeInTheDocument();
    expect(within(screen.getByRole("table", { name: "Members of Payments" })).getByText("dev@example.test")).toBeInTheDocument();
    expect(screen.queryByRole("combobox", { name: "Add a member" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /^remove /i })).not.toBeInTheDocument();
    expect(screen.getByRole("columnheader", { name: "Email" })).toBeInTheDocument();
    expect(screen.queryByRole("columnheader", { name: "Operations" })).not.toBeInTheDocument();
  });

  test("a team without a manager gives a regular user no roster controls either", async () => {
    localStorage.setItem(ROLES_KEY, "[]");
    serve(mockFetch, {}, { ...TEAM, managerId: null, managerName: null });
    renderPage();
    expect(await screen.findByRole("heading", { level: 3, name: "Members" })).toBeInTheDocument();
    expect(screen.queryByRole("combobox", { name: "Add a member" })).not.toBeInTheDocument();
  });

  test("another user's team gives a regular user no roster controls", async () => {
    localStorage.setItem(ROLES_KEY, "[]");
    localStorage.setItem(USER_ID_KEY, "99");
    serve(mockFetch);
    renderPage();
    expect(await screen.findByRole("heading", { level: 3, name: "Members" })).toBeInTheDocument();
    expect(screen.queryByRole("combobox", { name: "Add a member" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /^remove /i })).not.toBeInTheDocument();
  });

  test("the team's own manager, not an admin, gets the roster controls but not Edit", async () => {
    localStorage.setItem(ROLES_KEY, "[]");
    localStorage.setItem(USER_ID_KEY, "12");
    serve(mockFetch);
    renderPage();
    expect(await screen.findByRole("combobox", { name: "Add a member" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Remove Dev Member" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /^edit$/i })).not.toBeInTheDocument();
  });

  test("an admin searches the directory, adds the pick and the page refetches the roster", async () => {
    serve(mockFetch, { "PUT /api/v1/teams/1/members/20": { status: 204 } });
    const user = userEvent.setup();
    renderPage();
    const picker = await screen.findByRole("combobox", { name: "Add a member" });
    const add = screen.getByRole("button", { name: "Add" });
    expect(add).toBeDisabled();
    await user.type(picker, "eli");
    await user.click(await screen.findByRole("option", { name: "Eli Newcomer (eli@example.test)" }));
    expect(findCall(mockFetch, "GET", "/api/v1/users/directory?q=eli&page=1&pageSize=20&sort=name")).toBeDefined();
    const teamGets = () => mockFetch.mock.calls.filter(([u]) => u === "/api/v1/teams/1").length;
    const before = teamGets();
    await user.click(add);
    await waitFor(() => expect(findCall(mockFetch, "PUT", "/api/v1/teams/1/members/20")).toBeDefined());
    await waitFor(() => expect(teamGets()).toBeGreaterThan(before));
    // The picker is emptied for the next add.
    await waitFor(() => expect(screen.getByRole("combobox", { name: "Add a member" })).toHaveValue(""));
    expect(screen.getByRole("button", { name: "Add" })).toBeDisabled();
  });

  test.each([
    [409, "This team already has the maximum of 200 members."],
    [404, "The team or the user no longer exists — reload the page."],
    [403, "Only administrators and the team's manager can change the roster."],
    [500, "Action failed (500)"],
  ])("a %i on the add shows its inline message", async (status, message) => {
    serve(mockFetch, { "PUT /api/v1/teams/1/members/20": { status, body: { title: "x", status } } });
    const user = userEvent.setup();
    renderPage();
    await user.type(await screen.findByRole("combobox", { name: "Add a member" }), "eli");
    await user.click(await screen.findByRole("option", { name: "Eli Newcomer (eli@example.test)" }));
    await user.click(screen.getByRole("button", { name: "Add" }));
    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent("Could not add the member");
    expect(alert).toHaveTextContent(message);
  });

  test("removing a member asks first, then DELETEs and refetches", async () => {
    serve(mockFetch, { "DELETE /api/v1/teams/1/members/13": { status: 204 } });
    const user = userEvent.setup();
    renderPage();
    await user.click(await screen.findByRole("button", { name: "Remove Dev Member" }));
    const dialog = await screen.findByRole("dialog");
    expect(within(dialog).getByText('Dev Member will be removed from the team "Payments".')).toBeInTheDocument();
    expect(findCall(mockFetch, "DELETE", "/api/v1/teams/1/members/13")).toBeUndefined();
    const teamGets = () => mockFetch.mock.calls.filter(([u]) => u === "/api/v1/teams/1").length;
    const before = teamGets();
    await user.click(within(dialog).getByRole("button", { name: "Remove" }));
    await waitFor(() => expect(findCall(mockFetch, "DELETE", "/api/v1/teams/1/members/13")).toBeDefined());
    await waitFor(() => expect(teamGets()).toBeGreaterThan(before));
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
  });

  test("cancelling the remove dialog sends nothing", async () => {
    serve(mockFetch);
    const user = userEvent.setup();
    renderPage();
    await user.click(await screen.findByRole("button", { name: "Remove Dev Member" }));
    await user.click(within(await screen.findByRole("dialog")).getByRole("button", { name: "Cancel" }));
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    expect(mockFetch.mock.calls.some(([, init]) => (init as RequestInit | undefined)?.method === "DELETE")).toBe(false);
  });

  test("a failed remove closes the dialog and shows the failure under the heading", async () => {
    serve(mockFetch, { "DELETE /api/v1/teams/1/members/13": { status: 404, body: { title: "x", status: 404 } } });
    const user = userEvent.setup();
    renderPage();
    await user.click(await screen.findByRole("button", { name: "Remove Dev Member" }));
    await user.click(within(await screen.findByRole("dialog")).getByRole("button", { name: "Remove" }));
    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent("Could not remove the member");
    expect(alert).toHaveTextContent("This member was already removed from the team.");
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
  });

  test("a remove that answers 404 refetches the roster", async () => {
    serve(mockFetch, { "DELETE /api/v1/teams/1/members/13": { status: 404, body: { title: "x", status: 404 } } });
    const user = userEvent.setup();
    renderPage();
    await user.click(await screen.findByRole("button", { name: "Remove Dev Member" }));
    const teamGets = () => mockFetch.mock.calls.filter(([u]) => u === "/api/v1/teams/1").length;
    const before = teamGets();
    await user.click(within(await screen.findByRole("dialog")).getByRole("button", { name: "Remove" }));
    await screen.findByRole("alert");
    await waitFor(() => expect(teamGets()).toBeGreaterThan(before));
  });

  test("people already on the roster are not offered, and picking somebody new clears an earlier add failure", async () => {
    serve(mockFetch, { "PUT /api/v1/teams/1/members/20": { status: 500, body: { title: "x", status: 500 } } });
    const user = userEvent.setup();
    renderPage();
    const picker = await screen.findByRole("combobox", { name: "Add a member" });
    await user.type(picker, "eli");
    // The directory also answers Dev Member, who is already a member.
    expect(await screen.findByRole("option", { name: "Eli Newcomer (eli@example.test)" })).toBeInTheDocument();
    expect(screen.queryByRole("option", { name: "Dev Member (dev@example.test)" })).not.toBeInTheDocument();
    await user.click(screen.getByRole("option", { name: "Eli Newcomer (eli@example.test)" }));
    await user.click(screen.getByRole("button", { name: "Add" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("Could not add the member");
    await user.click(screen.getByLabelText("Clear the user"));
    await waitFor(() => expect(screen.queryByRole("alert")).not.toBeInTheDocument());
  });

  test.each([
    ["adding", "PUT", "/api/v1/teams/1/members/20"],
    ["removing", "DELETE", "/api/v1/teams/1/members/13"],
  ])("%s a member refreshes the teams list queries the page does not show", async (_what, method, url) => {
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const listKey = ["teams", "list", 1, 20, "name", ""];
    queryClient.setQueryData(listKey, { items: [], page: 1, pageSize: 20, total: 0 });
    serve(mockFetch, { [`${method} ${url}`]: { status: 204 } });
    const user = userEvent.setup();
    renderWithProviders(
      <Routes>
        <Route path="/teams/:id" element={<TeamDetails />} />
      </Routes>,
      { route: "/teams/1", queryClient },
    );
    expect(queryClient.getQueryState(listKey)?.isInvalidated).toBe(false);
    if (method === "PUT") {
      await user.type(await screen.findByRole("combobox", { name: "Add a member" }), "eli");
      await user.click(await screen.findByRole("option", { name: "Eli Newcomer (eli@example.test)" }));
      await user.click(screen.getByRole("button", { name: "Add" }));
    } else {
      await user.click(await screen.findByRole("button", { name: "Remove Dev Member" }));
      await user.click(within(await screen.findByRole("dialog")).getByRole("button", { name: "Remove" }));
    }
    await waitFor(() => expect(findCall(mockFetch, method, url)).toBeDefined());
    await waitFor(() => expect(queryClient.getQueryState(listKey)?.isInvalidated).toBe(true));
  });

  test("an empty roster says so and the heading outline stays h2, h3", async () => {
    serve(mockFetch, {}, { ...TEAM, members: [] });
    renderPage();
    expect(await screen.findByText("This team has no members yet")).toBeInTheDocument();
    expect(screen.queryByRole("table", { name: /Members of/ })).not.toBeInTheDocument();
    expect(screen.getAllByRole("heading").map((h) => h.tagName)).toEqual(["H2", "H3"]);
  });

  test("the Edit action opens the prefilled modal and PUTs", async () => {
    serve(mockFetch, { "PUT /api/v1/teams/1": { status: 204 } });
    const user = userEvent.setup();
    renderPage();
    await user.click(await screen.findByRole("button", { name: /^edit$/i }));
    const modal = await screen.findByRole("dialog");
    expect(within(modal).getByLabelText("Name")).toHaveValue("Payments");
    await user.type(within(modal).getByLabelText("Name"), " EU");
    await user.click(within(modal).getByRole("button", { name: /^save$/i }));
    await waitFor(() => expect(findCall(mockFetch, "PUT", "/api/v1/teams/1")).toBeDefined());
    const body = JSON.parse((findCall(mockFetch, "PUT", "/api/v1/teams/1")![1] as RequestInit).body as string);
    expect(body).toEqual({ name: "Payments EU", description: "Money movers", managerId: 12 });
  });

  test("cancelling the Edit modal closes it without a request", async () => {
    serve(mockFetch);
    const user = userEvent.setup();
    renderPage();
    await user.click(await screen.findByRole("button", { name: /^edit$/i }));
    await user.click(within(await screen.findByRole("dialog")).getByRole("button", { name: /^cancel$/i }));
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    expect(findCall(mockFetch, "PUT", "/api/v1/teams/1")).toBeUndefined();
  });

  test("a team without a manager says so", async () => {
    serve(mockFetch, {}, { ...TEAM, managerId: null, managerName: null, members: [] });
    renderPage();
    expect(await screen.findByText("No manager")).toBeInTheDocument();
    expect(screen.queryByText(/^Manager:/)).not.toBeInTheDocument();
  });

  test("a missing team shows the not-found message with a way back", async () => {
    mockFetch.mockImplementation(() => Promise.resolve(jsonResponse(404, { title: "Not Found", status: 404 })));
    renderPage();
    expect(await screen.findByText("This team does not exist (or was deleted).")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Back to teams" })).toHaveAttribute("href", "/teams");
  });
});
