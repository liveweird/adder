import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { screen, waitFor, within } from "@testing-library/react";
import { QueryClient } from "@tanstack/react-query";
import { Link, Route, Routes, useLocation } from "react-router-dom";
import RoomSearch from "./RoomSearch";
import { headingOutline } from "../test/headings";
import { jsonResponse } from "../test/http";
import { renderWithProviders } from "../test/render";
import { roomKeys } from "../utils/roomKeys";
import { messageHit, searchPage, threadHit } from "../test/searchFixtures";

const ROOM = { id: 12, name: "Night Shift", description: null, archived: false, archivedAt: null, role: "OWNER", memberCount: 3, workflowRevision: 1, lastActivityAt: 1, createdAt: 1, updatedAt: 1 };
const WORKFLOW = {
  revision: 1,
  statuses: [
    { key: "triage", label: "Triage", colour: "BLUE", category: "OPEN", initial: true, threadCount: 1 },
    { key: "done", label: "Done", colour: "TEAL", category: "CLOSED", initial: false, threadCount: 1 },
  ],
  fields: [],
};

function Where() {
  const { search } = useLocation();
  return <p data-testid="search">{search}</p>;
}

function renderSearch(route = "/rooms/12/search") {
  return renderWithProviders(
    <Routes>
      <Route
        path="/rooms/:id/search"
        element={
          <>
            <RoomSearch />
            <Where />
          </>
        }
      />
    </Routes>,
    { route },
  );
}

type SearchAnswer = (url: string) => Response;

/** The room, its workflow and a search that answers as `answer` says; the searches asked of it are in `searches`. */
function serve(answer: SearchAnswer): { searches: string[]; mock: ReturnType<typeof vi.fn> } {
  const searches: string[] = [];
  const mock = vi.fn((input: RequestInfo | URL) => {
    const url = String(input);
    if (url === "/api/v1/rooms/12") return Promise.resolve(jsonResponse(200, ROOM));
    if (url === "/api/v1/rooms/12/workflow") return Promise.resolve(jsonResponse(200, WORKFLOW));
    if (url.startsWith("/api/v1/tag-categories?")) return Promise.resolve(jsonResponse(200, { items: [{ id: 1, name: "Kind", description: null, revision: 1, tags: ["question"] }], page: 1, pageSize: 100, total: 1 }));
    if (url.startsWith("/api/v1/rooms/12/search?")) {
      searches.push(url);
      return Promise.resolve(answer(url));
    }
    return Promise.resolve(jsonResponse(404, { title: "Not found", status: 404 }));
  });
  vi.stubGlobal("fetch", mock);
  return { searches, mock };
}

const problem = (status: number, type: string) =>
  new Response(JSON.stringify({ type, title: "x", status }), { status, headers: { "Content-Type": "application/problem+json" } });

describe("RoomSearch page", () => {
  beforeEach(() => {
    localStorage.setItem("adder.auth.token", "fake-token");
  });
  afterEach(() => {
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  test("without a term there is the box, the rules of matching under it, and no request", async () => {
    const { searches } = serve(() => jsonResponse(200, searchPage([])));
    renderSearch();
    const box = await screen.findByRole("searchbox", { name: "Keywords" });
    expect(screen.getByRole("link", { name: "Search" })).toHaveAttribute("aria-current", "page");
    expect(screen.getByRole("heading", { level: 3, name: "Search" })).toBeInTheDocument();
    expect(screen.getByText("Find threads and messages in this room.")).toBeInTheDocument();
    expect(screen.getByRole("group", { name: "Filters" })).toBeInTheDocument();
    expect(screen.getByText("Enter keywords or use the filters to find a thread.")).toBeInTheDocument();
    // The hint is the box's description, one short line: prefixes from three characters, case and accents ignored.
    const hint = screen.getByText(/match the start of a word/);
    expect(box).toHaveAccessibleDescription(hint.textContent ?? "");
    expect(hint).toHaveTextContent(/^3 or more characters/);
    expect(hint).toHaveTextContent(/case and accents are ignored/);
    expect(screen.getByRole("radio", { name: "All" })).toBeChecked();
    expect(searches).toEqual([]);
  });

  test("submitting writes the term to the URL and searches the room; the hits show the room's status pill", async () => {
    const { searches } = serve(() => jsonResponse(200, searchPage([threadHit(42, { status: "done", closed: true })])));
    const user = userEvent.setup();
    renderSearch();
    await user.type(await screen.findByRole("searchbox", { name: "Keywords" }), "  outage  {Enter}");
    expect(await screen.findByRole("link", { name: "#42 Database outage" })).toHaveAttribute("href", "/rooms/12/threads/42");
    expect(searches).toEqual(["/api/v1/rooms/12/search?q=outage&page=1&pageSize=20"]);
    expect(screen.getByTestId("search")).toHaveTextContent("?q=outage");
    expect(screen.getByRole("status")).toHaveTextContent("1 thread found");
    const article = screen.getByRole("article");
    expect(within(article).getByText("Done")).toBeInTheDocument();
    expect(within(article).getByRole("link", { name: /the outage started/ })).toHaveAttribute("href", "/rooms/12/threads/42?message=4200");
    expect(headingOutline()).toEqual([
      [2, "Night Shift"],
      [3, "Search"],
      [4, "#42 Database outage"],
    ]);
  });

  test("a term from the URL is searched, with the scope and page it names", async () => {
    const { searches } = serve(() => jsonResponse(200, searchPage([threadHit(1)], 45, 2)));
    renderSearch("/rooms/12/search?q=disk%20full&scope=closed&page=2");
    expect(await screen.findByRole("link", { name: "#1 Database outage" })).toBeInTheDocument();
    expect(searches).toEqual(["/api/v1/rooms/12/search?q=disk+full&page=2&pageSize=20&closed=true"]);
    expect(screen.getByRole("searchbox", { name: "Keywords" })).toHaveValue("disk full");
    expect(screen.getByRole("radio", { name: "Closed" })).toBeChecked();
  });

  test("the Threads I watch switch asks the room for its watched threads with no term", async () => {
    const { searches } = serve(() => jsonResponse(200, searchPage([threadHit(3, { matchCount: 0, messages: [], status: "triage" })])));
    const user = userEvent.setup();
    renderSearch();
    await user.click(await screen.findByRole("switch", { name: "Threads I watch" }));
    expect(await screen.findByRole("link", { name: "#3 Database outage" })).toBeInTheDocument();
    expect(searches).toEqual(["/api/v1/rooms/12/search?page=1&pageSize=20&watching=true"]);
    expect(screen.getByTestId("search")).toHaveTextContent("?watching=1");
    expect(screen.getByText("Triage")).toBeInTheDocument();
  });

  test("a watching key in the URL searches the room with the scope it names and no term", async () => {
    const { searches } = serve(() => jsonResponse(200, searchPage([])));
    renderSearch("/rooms/12/search?watching=1&scope=open");
    expect(await screen.findByText("You are not watching any threads.")).toBeInTheDocument();
    expect(searches).toEqual(["/api/v1/rooms/12/search?page=1&pageSize=20&closed=false&watching=true"]);
  });

  test("a tag alone searches the room with no term, a pick writes threadTag, and the hits show their tags", async () => {
    const { searches } = serve(() => jsonResponse(200, searchPage([threadHit(42, { matchCount: 0, messages: [], tags: [{ name: "question", retired: false }] })])));
    const user = userEvent.setup();
    renderSearch();
    await user.click(await screen.findByRole("combobox", { name: "Tags" }));
    await user.click(await screen.findByRole("option", { name: "question" }));
    expect(await screen.findByRole("link", { name: "#42 Database outage" })).toBeInTheDocument();
    expect(searches).toEqual(["/api/v1/rooms/12/search?page=1&pageSize=20&threadTag=question"]);
    expect(screen.getByTestId("search")).toHaveTextContent("?threadTag=question");
    expect(within(screen.getByRole("article")).getByRole("group", { name: "Tags" })).toHaveTextContent("question");
  });

  test("the scope control searches Open, Closed or All, and goes back to page 1", async () => {
    const { searches } = serve(() => jsonResponse(200, searchPage([threadHit(1)])));
    const user = userEvent.setup();
    renderSearch("/rooms/12/search?q=outage&page=3");
    await screen.findByRole("link", { name: "#1 Database outage" });
    await user.click(screen.getByRole("radio", { name: "Open" }));
    await waitFor(() => expect(searches.at(-1)).toBe("/api/v1/rooms/12/search?q=outage&page=1&pageSize=20&closed=false"));
    expect(screen.getByTestId("search")).toHaveTextContent("?q=outage&scope=open");
    await user.click(screen.getByRole("radio", { name: "All" }));
    await waitFor(() => expect(searches.at(-1)).toBe("/api/v1/rooms/12/search?q=outage&page=1&pageSize=20"));
    expect(screen.getByTestId("search")).toHaveTextContent(/^\?q=outage$/);
  });

  test("a term the server would refuse is never sent: the box says why", async () => {
    const { searches } = serve(() => jsonResponse(200, searchPage([])));
    const user = userEvent.setup();
    renderSearch();
    const box = await screen.findByRole("searchbox", { name: "Keywords" });
    await user.type(box, "a{Enter}");
    expect(await screen.findByText("Type at least two characters, with a letter or a digit.")).toBeInTheDocument();
    expect(box).toBeInvalid();
    await user.clear(box);
    await user.type(box, "!!!{Enter}");
    expect(screen.getByText("Type at least two characters, with a letter or a digit.")).toBeInTheDocument();
    await user.clear(box);
    await user.type(box, "x".repeat(101));
    await user.keyboard("{Enter}");
    expect(screen.getByText("Use at most 100 characters.")).toBeInTheDocument();
    expect(searches).toEqual([]);
    expect(screen.getByTestId("search")).toHaveTextContent(/^$/);
  });

  test("a bad term in the URL (hand-edited) shows the problem and sends nothing", async () => {
    const { searches } = serve(() => jsonResponse(200, searchPage([])));
    renderSearch("/rooms/12/search?q=a");
    expect(await screen.findByText("Type at least two characters, with a letter or a digit.")).toBeInTheDocument();
    expect(searches).toEqual([]);
  });

  test("nothing found says so, naming the term", async () => {
    serve(() => jsonResponse(200, searchPage([])));
    renderSearch("/rooms/12/search?q=zzz");
    expect(await screen.findByText("Nothing matches “zzz”.")).toBeInTheDocument();
    expect(screen.getByRole("status")).toHaveTextContent("0 threads found");
  });

  test("the pager reads the next page of results into the URL", async () => {
    const { searches } = serve((url) => jsonResponse(200, searchPage([threadHit(url.includes("page=2") ? 21 : 1)], 45, url.includes("page=2") ? 2 : 1)));
    const user = userEvent.setup();
    renderSearch("/rooms/12/search?q=outage");
    await screen.findByRole("link", { name: "#1 Database outage" });
    expect(screen.queryByLabelText("Rows per page")).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "2" }));
    expect(await screen.findByRole("link", { name: "#21 Database outage" })).toBeInTheDocument();
    expect(searches.at(-1)).toBe("/api/v1/rooms/12/search?q=outage&page=2&pageSize=20");
    expect(screen.getByTestId("search")).toHaveTextContent("?q=outage&page=2");
  });

  test("a page the results no longer reach steps back to the first", async () => {
    const { searches } = serve((url) => jsonResponse(200, url.includes("page=9") ? { items: [], page: 9, pageSize: 20, total: 5 } : searchPage([threadHit(1)], 5)));
    renderSearch("/rooms/12/search?q=outage&page=9");
    expect(await screen.findByRole("link", { name: "#1 Database outage" })).toBeInTheDocument();
    expect(searches).toEqual(["/api/v1/rooms/12/search?q=outage&page=9&pageSize=20", "/api/v1/rooms/12/search?q=outage&page=1&pageSize=20"]);
    expect(screen.getByTestId("search")).toHaveTextContent(/^\?q=outage$/);
  });

  test.each([
    ["the rate limit", () => problem(429, "about:blank"), "Too many searches. Wait a moment and try again."],
    ["the search timeout", () => problem(429, "urn:adder:problem:search-timeout"), "The search took too long. Narrow it down with a more specific word."],
    ["a busy server", () => problem(429, "urn:adder:problem:search-busy"), "The server is busy. Try again in a moment."],
    ["a server error", () => problem(500, "about:blank"), "Search failed (500)"],
  ])("%s has its own words", async (_name, answer, words) => {
    serve(answer);
    renderSearch("/rooms/12/search?q=outage");
    expect(await screen.findByRole("alert")).toHaveTextContent(words);
    expect(screen.queryByRole("article")).not.toBeInTheDocument();
  });

  test("a 404 from the search re-reads the room, which then shows not-found", async () => {
    let roomReads = 0;
    vi.stubGlobal(
      "fetch",
      vi.fn((input: RequestInfo | URL) => {
        const url = String(input);
        if (url === "/api/v1/rooms/12") {
          roomReads += 1;
          return Promise.resolve(roomReads === 1 ? jsonResponse(200, ROOM) : jsonResponse(404, { title: "Not found", status: 404 }));
        }
        if (url === "/api/v1/rooms/12/workflow") return Promise.resolve(jsonResponse(200, WORKFLOW));
        return Promise.resolve(jsonResponse(404, { title: "Not found", status: 404 }));
      }),
    );
    renderSearch("/rooms/12/search?q=outage");
    expect(await screen.findByText("This room does not exist, or you are not a member of it.")).toBeInTheDocument();
    expect(roomReads).toBe(2);
  });

  test("an unknown room is the not-found state, without a search", async () => {
    const { searches } = serve(() => jsonResponse(200, searchPage([])));
    renderSearch("/rooms/12abc/search?q=outage");
    expect(await screen.findByText("This room does not exist, or you are not a member of it.")).toBeInTheDocument();
    expect(searches).toEqual([]);
  });

  test("a message hit's row keeps working beside a title-only hit", async () => {
    serve(() => jsonResponse(200, searchPage([threadHit(1, { matchCount: 0, messages: [] }), threadHit(2, { matchCount: 4, messages: [messageHit(7), messageHit(6), messageHit(5)] })])));
    renderSearch("/rooms/12/search?q=outage");
    expect(await screen.findByText("4 matching messages")).toBeInTheDocument();
    expect(screen.getAllByRole("article")).toHaveLength(2);
  });

  test("moving to another room (its record already cached) never shows the previous room's hits under it", async () => {
    const BILLING = { ...ROOM, id: 13, name: "Billing" };
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    queryClient.setQueryData(roomKeys.detail(13), BILLING);
    vi.stubGlobal(
      "fetch",
      vi.fn((input: RequestInfo | URL) => {
        const url = String(input);
        if (url === "/api/v1/rooms/12") return Promise.resolve(jsonResponse(200, ROOM));
        if (url === "/api/v1/rooms/13") return Promise.resolve(jsonResponse(200, BILLING));
        if (url.includes("/pins?")) return Promise.resolve(jsonResponse(200, { items: [], page: 1, pageSize: 50, total: 0 }));
        if (url.startsWith("/api/v1/rooms/12/search?")) return Promise.resolve(jsonResponse(200, searchPage([threadHit(42)])));
        // Room 13's search never answers: whatever shows meanwhile is the placeholder.
        if (url.startsWith("/api/v1/rooms/13/search?")) return new Promise<Response>(() => undefined);
        return Promise.resolve(jsonResponse(200, WORKFLOW));
      }),
    );
    const user = userEvent.setup();
    renderWithProviders(
      <Routes>
        <Route
          path="/rooms/:id/search"
          element={
            <>
              <Link to="/rooms/13/search?q=outage">to Billing</Link>
              <RoomSearch />
            </>
          }
        />
      </Routes>,
      { route: "/rooms/12/search?q=outage", queryClient },
    );
    expect(await screen.findByRole("link", { name: "#42 Database outage" })).toBeInTheDocument();
    await user.click(screen.getByRole("link", { name: "to Billing" }));
    expect(await screen.findByRole("heading", { level: 2, name: "Billing" })).toBeInTheDocument();
    expect(screen.queryByRole("link", { name: "#42 Database outage" })).not.toBeInTheDocument();
    expect(screen.queryByRole("article")).not.toBeInTheDocument();
  });
});
