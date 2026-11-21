import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { QueryClient } from "@tanstack/react-query";
import { screen, waitFor, within } from "@testing-library/react";
import { Route, Routes } from "react-router-dom";
import Person from "./Person";
import { headingOutline } from "../test/headings";
import { jsonResponse } from "../test/http";
import { renderWithProviders } from "../test/render";

const TOKEN_KEY = "adder.auth.token";
const ROLES_KEY = "adder.auth.roles";
const USER_ID_KEY = "adder.auth.userId";

type FetchMock = ReturnType<typeof vi.fn>;

const PERSON = { id: 7, name: "Ada Lovelace", email: "ada@example.test" };
const ROOMS = {
  items: [
    { id: 3, name: "Night Shift", description: null, archived: false, role: "MEMBER", memberCount: 2 },
    { id: 4, name: "Old Incident", description: null, archived: true, role: "MEMBER", memberCount: 5 },
  ],
  page: 1,
  pageSize: 100,
  total: 2,
};
const TEAMS = { items: [{ id: 9, name: "Platform" }], page: 1, pageSize: 100, total: 1 };
const NOT_FOUND = { title: "Not Found", status: 404 };

/** The API of one profile: the directory entry (or a status), the person's teams and the rooms the server scoped to the viewer. */
function serve(mockFetch: FetchMock, opts: { person?: number | typeof PERSON; rooms?: unknown; teams?: unknown } = {}) {
  mockFetch.mockImplementation((url: string) => {
    if (url === "/api/v1/users/directory/7") {
      const person = opts.person ?? PERSON;
      return Promise.resolve(typeof person === "number" ? jsonResponse(person, { title: "x", status: person }) : jsonResponse(200, person));
    }
    if (url.startsWith("/api/v1/teams?")) return Promise.resolve(jsonResponse(200, opts.teams ?? TEAMS));
    if (url.startsWith("/api/v1/rooms?")) return Promise.resolve(jsonResponse(200, opts.rooms ?? ROOMS));
    return Promise.resolve(jsonResponse(404, NOT_FOUND));
  });
}

function renderPage(route = "/people/7", queryClient?: QueryClient) {
  return renderWithProviders(
    <Routes>
      <Route path="/people/:id" element={<Person />} />
    </Routes>,
    { route, queryClient },
  );
}

describe("Person page", () => {
  let mockFetch: FetchMock;

  beforeEach(() => {
    mockFetch = vi.fn();
    vi.stubGlobal("fetch", mockFetch);
    localStorage.setItem(TOKEN_KEY, "fake-token");
    localStorage.setItem(ROLES_KEY, "[]");
    localStorage.setItem(USER_ID_KEY, "1");
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  test("a member sees the name, the email, the teams and the rooms they share, and no admin control", async () => {
    serve(mockFetch);
    renderPage();

    expect(await screen.findByRole("heading", { level: 2, name: "Ada Lovelace" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Email ada@example.test" })).toHaveAttribute("href", "mailto:ada@example.test");
    const teams = await screen.findByRole("list", { name: "Teams" });
    expect(within(teams).getByRole("link", { name: "Platform" })).toHaveAttribute("href", "/teams/9");
    const rooms = await screen.findByRole("list", { name: "Rooms you share" });
    expect(within(rooms).getByRole("link", { name: "Night Shift" })).toHaveAttribute("href", "/rooms/3");
    expect(within(rooms).getByRole("link", { name: "Old Incident" })).toHaveAttribute("href", "/rooms/4");
    expect(within(rooms).getByText("Archived")).toBeInTheDocument();
    expect(screen.queryByRole("link", { name: "Edit user" })).not.toBeInTheDocument();
    // Nothing of the account beyond the directory's entry is asked for or drawn.
    expect(document.body.textContent).not.toMatch(/admin|role|language|feature|mfa/i);
    expect(headingOutline()).toEqual([
      [2, "Ada Lovelace"],
      [3, "Teams"],
      [3, "Rooms you share"],
    ]);
  });

  test("it asks the server for this person's rooms and teams, name-sorted, and nothing more", async () => {
    serve(mockFetch);
    renderPage();
    await screen.findByRole("list", { name: "Rooms you share" });
    const urls = mockFetch.mock.calls.map(([url]) => url as string);
    expect(urls).toContain("/api/v1/users/directory/7");
    expect(urls).toContain("/api/v1/rooms?page=1&pageSize=100&sort=name&memberId=7");
    expect(urls).toContain("/api/v1/teams?page=1&pageSize=100&sort=name&memberId=7");
    expect(urls.some((url) => url.startsWith("/api/v1/users/7"))).toBe(false);
  });

  test("an ADMIN sees every room of the person and an Edit user link", async () => {
    localStorage.setItem(ROLES_KEY, JSON.stringify(["ADMIN"]));
    serve(mockFetch);
    renderPage();

    expect(await screen.findByRole("link", { name: "Edit user" })).toHaveAttribute("href", "/users/7/edit");
    expect(await screen.findByRole("list", { name: "Rooms" })).toBeInTheDocument();
    expect(screen.queryByRole("list", { name: "Rooms you share" })).not.toBeInTheDocument();
  });

  test("empty lists say so in words, and one's own profile calls the rooms theirs", async () => {
    serve(mockFetch, { rooms: { items: [], page: 1, pageSize: 100, total: 0 }, teams: { items: [], page: 1, pageSize: 100, total: 0 } });
    renderPage();
    expect(await screen.findByText("Not on any team.")).toBeInTheDocument();
    expect(await screen.findByText("You share no room with this person.")).toBeInTheDocument();
  });

  test("viewing oneself titles the rooms 'Your rooms'", async () => {
    localStorage.setItem(USER_ID_KEY, "7");
    serve(mockFetch);
    renderPage();
    expect(await screen.findByRole("list", { name: "Your rooms" })).toBeInTheDocument();
  });

  test("a list the server cut says how many are shown", async () => {
    serve(mockFetch, { rooms: { ...ROOMS, total: 130 } });
    renderPage();
    expect(await screen.findByText("Showing 2 of 130.")).toBeInTheDocument();
  });

  test("a failed list is a message in its own section, not a failed page", async () => {
    mockFetch.mockImplementation((url: string) => {
      if (url === "/api/v1/users/directory/7") return Promise.resolve(jsonResponse(200, PERSON));
      if (url.startsWith("/api/v1/teams?")) return Promise.resolve(jsonResponse(200, TEAMS));
      return Promise.resolve(jsonResponse(500, { title: "Boom", status: 500 }));
    });
    renderPage();
    expect(await screen.findByText("Could not load the rooms")).toBeInTheDocument();
    expect(screen.getByRole("heading", { level: 2, name: "Ada Lovelace" })).toBeInTheDocument();
    expect(await screen.findByRole("link", { name: "Platform" })).toBeInTheDocument();
  });

  test("a removed or unknown person is one 'not available' page that asks for no list", async () => {
    serve(mockFetch, { person: 404 });
    renderPage();

    expect(await screen.findByRole("heading", { level: 2, name: "This person is not available" })).toBeInTheDocument();
    expect(screen.getByText("The account may have been removed, or the link is wrong.")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Back to home" })).toHaveAttribute("href", "/");
    const urls = mockFetch.mock.calls.map(([url]) => url as string);
    expect(urls.filter((url) => !url.startsWith("/api/v1/users/directory/"))).toEqual([]);
  });

  test("a failed background refetch keeps the loaded profile on screen", async () => {
    serve(mockFetch);
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    renderPage("/people/7", queryClient);
    expect(await screen.findByRole("heading", { level: 2, name: "Ada Lovelace" })).toBeInTheDocument();
    await screen.findByRole("list", { name: "Rooms you share" });

    // The next read of the entry fails; the page keeps what it has (and the 429 is no "not available").
    serve(mockFetch, { person: 500 });
    await queryClient.refetchQueries({ queryKey: ["people", 7] });
    await waitFor(() => expect(queryClient.getQueryState(["people", 7])?.status).toBe("error"));
    expect(screen.getByRole("heading", { level: 2, name: "Ada Lovelace" })).toBeInTheDocument();
    expect(screen.getByRole("list", { name: "Rooms you share" })).toBeInTheDocument();
    expect(screen.queryByText(/load failed/i)).not.toBeInTheDocument();
  });

  test("a person who is gone on the refetch turns the page into 'not available'", async () => {
    serve(mockFetch);
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    renderPage("/people/7", queryClient);
    expect(await screen.findByRole("heading", { level: 2, name: "Ada Lovelace" })).toBeInTheDocument();

    serve(mockFetch, { person: 404 });
    await queryClient.refetchQueries({ queryKey: ["people", 7] });
    expect(await screen.findByRole("heading", { level: 2, name: "This person is not available" })).toBeInTheDocument();
  });

  test("the links are underlined at rest, so a name reads as a link and not as plain text", async () => {
    serve(mockFetch);
    renderPage();
    for (const name of ["Platform", "Night Shift"]) {
      expect(await screen.findByRole("link", { name })).toHaveAttribute("data-underline", "always");
    }
    expect(screen.getByRole("link", { name: "Email ada@example.test" })).toHaveAttribute("data-underline", "always");
  });

  test("an address with a leading zero or another script's digits is 'not available' and asks for nothing", async () => {
    renderPage("/people/007");
    expect(await screen.findByRole("heading", { level: 2, name: "This person is not available" })).toBeInTheDocument();
    expect(mockFetch).not.toHaveBeenCalled();
  });

  test("an unreadable id never reaches the server and reads as the same page", async () => {
    renderPage("/people/abc");
    expect(await screen.findByRole("heading", { level: 2, name: "This person is not available" })).toBeInTheDocument();
    expect(mockFetch).not.toHaveBeenCalled();
  });

  test("another failure is a load error with a way home, not 'not available'", async () => {
    serve(mockFetch, { person: 429 });
    renderPage();
    expect(await screen.findByText(/too many requests/i)).toBeInTheDocument();
    expect(screen.getByRole("heading", { level: 2, name: "Person" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Back to home" })).toBeInTheDocument();
    await waitFor(() => expect(screen.queryByText("This person is not available")).not.toBeInTheDocument());
  });
});
