import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { screen, waitFor, within } from "@testing-library/react";
import { Route, Routes, useLocation, useNavigate } from "react-router-dom";
import Search from "./Search";
import { headingOutline } from "../test/headings";
import { jsonResponse } from "../test/http";
import { renderWithProviders } from "../test/render";
import { searchPage, threadHit } from "../test/searchFixtures";

function Where() {
  const { search } = useLocation();
  return <p data-testid="search">{search}</p>;
}

function History() {
  const navigate = useNavigate();
  return (
    <>
      <button onClick={() => navigate(-1)}>history back</button>
      <button onClick={() => navigate(1)}>history forward</button>
      <button onClick={() => navigate("/search?q=disk")}>open a shared link</button>
    </>
  );
}

function renderSearch(route = "/search") {
  return renderWithProviders(
    <Routes>
      <Route
        path="/search"
        element={
          <>
            <Search />
            <Where />
            <History />
          </>
        }
      />
    </Routes>,
    { route },
  );
}

/** A fetch that answers every search as `answer` says and records the URLs asked. */
function serve(answer: (url: string) => Response): string[] {
  const searches: string[] = [];
  vi.stubGlobal(
    "fetch",
    vi.fn((input: RequestInfo | URL) => {
      const url = String(input);
      searches.push(url);
      return Promise.resolve(answer(url));
    }),
  );
  return searches;
}

const problem = (status: number, type: string) =>
  new Response(JSON.stringify({ type, title: "x", status }), { status, headers: { "Content-Type": "application/problem+json" } });

describe("Search page (all your rooms)", () => {
  beforeEach(() => {
    localStorage.setItem("adder.auth.token", "fake-token");
  });
  afterEach(() => {
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  test("opens on the box and the hint, with no request", () => {
    const searches = serve(() => jsonResponse(200, searchPage([])));
    renderSearch();
    expect(screen.getByRole("heading", { level: 2, name: "Search" })).toBeInTheDocument();
    expect(screen.getByRole("searchbox", { name: "Search all your rooms" })).toBeInTheDocument();
    expect(screen.getByText(/no stemming/)).toBeInTheDocument();
    expect(searches).toEqual([]);
  });

  test("submitting searches every room: hits carry the room, Open or Closed, Archived, and links into their own room", async () => {
    const searches = serve(() =>
      jsonResponse(
        200,
        searchPage([threadHit(5), threadHit(9, { roomId: 13, roomName: "Billing", roomArchived: true, closed: true, status: "wontdo", matchCount: 0, messages: [] })]),
      ),
    );
    const user = userEvent.setup();
    renderSearch();
    await user.type(screen.getByRole("searchbox", { name: "Search all your rooms" }), "outage");
    await user.click(screen.getByRole("button", { name: "Search" }));
    expect(await screen.findByRole("link", { name: "#9 Database outage" })).toHaveAttribute("href", "/rooms/13/threads/9");
    expect(searches).toEqual(["/api/v1/search?q=outage&page=1&pageSize=20"]);
    expect(screen.getByTestId("search")).toHaveTextContent("?q=outage");
    const [first, second] = screen.getAllByRole("article");
    expect(within(first).getByText("Night Shift")).toBeInTheDocument();
    expect(within(first).getByText("Open")).toBeInTheDocument();
    expect(within(second).getByText("Billing")).toBeInTheDocument();
    expect(within(second).getByText("Closed")).toBeInTheDocument();
    expect(within(second).getByText("Archived")).toBeInTheDocument();
    expect(within(first).getByRole("link", { name: /the outage started/ })).toHaveAttribute("href", "/rooms/12/threads/5?message=500");
    expect(screen.getByRole("status")).toHaveTextContent("2 threads found");
    expect(headingOutline()).toEqual([
      [2, "Search"],
      [3, "#5 Database outage"],
      [3, "#9 Database outage"],
    ]);
  });

  test("the Threads I watch switch lists the watched threads with no term, and the hits carry no messages", async () => {
    const searches = serve(() => jsonResponse(200, searchPage([threadHit(4, { matchCount: 0, messages: [], titleSnippet: [{ text: "Database outage", hit: false }] })])));
    const user = userEvent.setup();
    renderSearch();
    await user.click(screen.getByRole("switch", { name: "Threads I watch" }));
    expect(await screen.findByRole("link", { name: "#4 Database outage" })).toHaveAttribute("href", "/rooms/12/threads/4");
    expect(searches).toEqual(["/api/v1/search?page=1&pageSize=20&watching=true"]);
    expect(screen.getByTestId("search")).toHaveTextContent("?watching=1");
    expect(screen.getByRole("status")).toHaveTextContent("1 thread found");
    const [article] = screen.getAllByRole("article");
    expect(within(article).queryByText(/matching message/)).not.toBeInTheDocument();
    expect(article.querySelector("mark")).toBeNull();
    expect(screen.getByText(/leave the box empty/i)).toBeInTheDocument();
  });

  test("the palette link /search?watching=1 opens the list at once, and the switch turns it off again", async () => {
    const searches = serve(() => jsonResponse(200, searchPage([threadHit(4, { matchCount: 0, messages: [] })])));
    const user = userEvent.setup();
    renderSearch("/search?watching=1");
    expect(await screen.findByRole("link", { name: "#4 Database outage" })).toBeInTheDocument();
    expect(screen.getByRole("switch", { name: "Threads I watch" })).toBeChecked();
    await user.click(screen.getByRole("switch", { name: "Threads I watch" }));
    expect(screen.getByTestId("search")).toHaveTextContent(/^$/);
    expect(screen.queryByRole("link", { name: "#4 Database outage" })).not.toBeInTheDocument();
    expect(searches).toEqual(["/api/v1/search?page=1&pageSize=20&watching=true"]);
  });

  test("a term with the switch on narrows the watched list; an empty box is fine only while it is on", async () => {
    const searches = serve(() => jsonResponse(200, searchPage([threadHit(5)])));
    const user = userEvent.setup();
    renderSearch("/search?watching=1");
    await screen.findByRole("link", { name: "#5 Database outage" });
    const box = screen.getByRole("searchbox", { name: "Search all your rooms" });
    await user.type(box, "outage{Enter}");
    await waitFor(() => expect(searches.at(-1)).toBe("/api/v1/search?q=outage&page=1&pageSize=20&watching=true"));
    expect(screen.getByTestId("search")).toHaveTextContent("?q=outage&watching=1");
    await user.clear(box);
    await user.type(box, "a{Enter}");
    expect(await screen.findByText("Type at least two characters, with a letter or a digit.")).toBeInTheDocument();
    await user.clear(box);
    await user.keyboard("{Enter}");
    await waitFor(() => expect(screen.getByTestId("search")).toHaveTextContent("?watching=1"));
    expect(screen.queryByText("Type at least two characters, with a letter or a digit.")).not.toBeInTheDocument();
  });

  test("nothing watched says so, and with a term it names the term", async () => {
    serve(() => jsonResponse(200, searchPage([])));
    renderSearch("/search?watching=1");
    expect(await screen.findByText("You are not watching any threads.")).toBeInTheDocument();
  });

  test("nothing watched matches a term: the words name the term", async () => {
    serve(() => jsonResponse(200, searchPage([])));
    renderSearch("/search?q=outage&watching=1");
    expect(await screen.findByText("None of the threads you watch matches “outage”.")).toBeInTheDocument();
  });

  test("the short-query rule: one character, or no letter or digit, is not sent", async () => {
    const searches = serve(() => jsonResponse(200, searchPage([])));
    const user = userEvent.setup();
    renderSearch();
    const box = screen.getByRole("searchbox", { name: "Search all your rooms" });
    await user.type(box, "é{Enter}");
    expect(await screen.findByText("Type at least two characters, with a letter or a digit.")).toBeInTheDocument();
    await user.type(box, "{Backspace}?!{Enter}");
    expect(screen.getByText("Type at least two characters, with a letter or a digit.")).toBeInTheDocument();
    expect(searches).toEqual([]);
    // Two characters with a letter go through (a digit alone would too).
    await user.clear(box);
    await user.type(box, "é2{Enter}");
    expect(await screen.findByText("Nothing matches “é2”.")).toBeInTheDocument();
    expect(searches).toEqual(["/api/v1/search?q=%C3%A92&page=1&pageSize=20"]);
  });

  test("a control character in the term is refused with its own words, and never sent", async () => {
    const searches = serve(() => jsonResponse(200, searchPage([])));
    renderSearch("/search?q=ab%01cd");
    expect(await screen.findByText("The search cannot contain control characters.")).toBeInTheDocument();
    expect(searches).toEqual([]);
  });

  test.each([
    ["the rate limit", () => problem(429, "about:blank"), "Too many searches. Wait a moment and try again."],
    ["the search timeout", () => problem(429, "urn:adder:problem:search-timeout"), "The search took too long. Narrow it down with a more specific word."],
    ["a busy server", () => problem(429, "urn:adder:problem:search-busy"), "The server is busy. Try again in a moment."],
  ])("%s has its own words", async (_name, answer, words) => {
    serve(answer);
    renderSearch("/search?q=outage");
    expect(await screen.findByRole("alert")).toHaveTextContent(words);
  });

  test("a failed search can be retried by submitting again", async () => {
    let calls = 0;
    serve(() => (++calls === 1 ? problem(429, "urn:adder:problem:search-busy") : jsonResponse(200, searchPage([threadHit(5)]))));
    const user = userEvent.setup();
    renderSearch("/search?q=outage");
    await screen.findByRole("alert");
    await user.type(screen.getByRole("searchbox"), "{Enter}");
    // The same term is the same query key: a failed one is read again, not served from the failure.
    expect(await screen.findByRole("link", { name: "#5 Database outage" })).toBeInTheDocument();
  });

  test("paging keeps the term and the scope in the URL", async () => {
    const searches = serve((url) => jsonResponse(200, searchPage([threadHit(url.includes("page=2") ? 25 : 5)], 50, url.includes("page=2") ? 2 : 1)));
    const user = userEvent.setup();
    renderSearch("/search?q=outage&scope=open");
    await screen.findByRole("link", { name: "#5 Database outage" });
    await user.click(screen.getByRole("button", { name: "2" }));
    expect(await screen.findByRole("link", { name: "#25 Database outage" })).toBeInTheDocument();
    expect(searches).toEqual(["/api/v1/search?q=outage&page=1&pageSize=20&closed=false", "/api/v1/search?q=outage&page=2&pageSize=20&closed=false"]);
    expect(screen.getByTestId("search")).toHaveTextContent("?q=outage&scope=open&page=2");
  });

  /** Every term answers its own thread: "outage" is #1, "disk" is #2. */
  const byTerm = (url: string) => jsonResponse(200, searchPage([threadHit(new URL(url, "http://x").searchParams.get("q") === "disk" ? 2 : 1)]));

  test("Back and Forward return to the earlier searches, and the box shows each one's term", async () => {
    const searches = serve(byTerm);
    const user = userEvent.setup();
    renderSearch("/search?q=outage");
    await screen.findByRole("link", { name: "#1 Database outage" });
    const box = screen.getByRole("searchbox", { name: "Search all your rooms" });
    await user.clear(box);
    await user.type(box, "disk{Enter}");
    await screen.findByRole("link", { name: "#2 Database outage" });
    expect(screen.getByTestId("search")).toHaveTextContent("?q=disk");
    await user.click(screen.getByRole("button", { name: "history back" }));
    expect(await screen.findByRole("link", { name: "#1 Database outage" })).toBeInTheDocument();
    expect(screen.getByTestId("search")).toHaveTextContent("?q=outage");
    expect(box).toHaveValue("outage");
    await user.click(screen.getByRole("button", { name: "history forward" }));
    expect(await screen.findByRole("link", { name: "#2 Database outage" })).toBeInTheDocument();
    expect(box).toHaveValue("disk");
    // The term read before is still fresh (30 s): going back and forward asked the server for nothing new.
    expect(searches).toEqual(["/api/v1/search?q=outage&page=1&pageSize=20", "/api/v1/search?q=disk&page=1&pageSize=20"]);
  });

  test("the box follows a term that arrives in the URL (a shared link, the palette) and replaces what was typed", async () => {
    serve(byTerm);
    const user = userEvent.setup();
    renderSearch("/search?q=outage");
    await screen.findByRole("link", { name: "#1 Database outage" });
    const box = screen.getByRole("searchbox", { name: "Search all your rooms" });
    await user.type(box, " and a half-typed new thought");
    await user.click(screen.getByRole("button", { name: "open a shared link" }));
    expect(await screen.findByRole("link", { name: "#2 Database outage" })).toBeInTheDocument();
    expect(box).toHaveValue("disk");
  });

  test("a refused term is announced as an alert when the submit is refused", async () => {
    serve(byTerm);
    const user = userEvent.setup();
    renderSearch();
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    await user.type(screen.getByRole("searchbox", { name: "Search all your rooms" }), "a{Enter}");
    expect(await screen.findByRole("alert")).toHaveTextContent("Type at least two characters, with a letter or a digit.");
  });
});
