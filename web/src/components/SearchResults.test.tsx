import { describe, expect, test } from "vitest";
import { within } from "@testing-library/react";
import { headingOutline } from "../test/headings";
import { renderWithProviders, screen } from "../test/render";
import { messageHit, threadHit } from "../test/searchFixtures";
import SearchResults from "./SearchResults";

const STATUSES = [
  { key: "open", label: "Open", colour: "BLUE" as const, category: "OPEN" as const, initial: true, threadCount: 2 },
  { key: "done", label: "Done", colour: "TEAL" as const, category: "CLOSED" as const, initial: false, threadCount: 1 },
];

describe("SearchResults", () => {
  test("one article per thread; the title is a heading whose link is named by the plain title, to the thread", () => {
    renderWithProviders(<SearchResults label="Search results" hits={[threadHit(42), threadHit(43, { title: "Disk full", titleSnippet: [{ text: "Disk full", hit: false }] })]} statuses={STATUSES} showRoom={false} />);
    expect(screen.getByRole("list", { name: "Search results" })).toBeInTheDocument();
    expect(screen.getAllByRole("article")).toHaveLength(2);
    // <mark> splits the visible words: the link is still named by the whole title.
    const link = screen.getByRole("link", { name: "#42 Database outage" });
    expect(link).toHaveAttribute("href", "/rooms/12/threads/42");
    expect(link.querySelector("mark")?.textContent).toBe("outage");
    expect(screen.getByRole("heading", { level: 3, name: "#42 Database outage" })).toContainElement(link);
    expect(screen.getByRole("link", { name: "#43 Disk full" })).toHaveAttribute("href", "/rooms/12/threads/43");
    expect(headingOutline().map(([level]) => level)).toEqual([3, 3]);
  });

  test("a message snippet is a link to the thread that names the message, headed by its author and time", () => {
    renderWithProviders(<SearchResults label="Search results" hits={[threadHit(42, { matchCount: 5, messages: [messageHit(991), messageHit(990, { authorName: "Bo" })] })]} statuses={STATUSES} showRoom={false} />);
    expect(screen.getByText("5 matching messages")).toBeInTheDocument();
    const rows = screen.getAllByRole("link").filter((link) => link.getAttribute("href")?.includes("message="));
    expect(rows.map((row) => row.getAttribute("href"))).toEqual(["/rooms/12/threads/42?message=991", "/rooms/12/threads/42?message=990"]);
    expect(rows[0]).toHaveTextContent("Ada");
    expect(rows[0]).toHaveTextContent("the outage started");
    expect(rows[0].querySelector("mark")?.textContent).toBe("outage");
    expect(rows[1]).toHaveTextContent("Bo");
  });

  test("a title-only hit has no message list and no count", () => {
    renderWithProviders(<SearchResults label="Search results" hits={[threadHit(42, { matchCount: 0, messages: [] })]} statuses={STATUSES} showRoom={false} />);
    expect(screen.queryByText(/matching message/)).not.toBeInTheDocument();
    // The thread's heading link and the owner's profile: nothing else.
    expect(screen.getAllByRole("link").map((link) => link.getAttribute("href"))).toEqual(["/rooms/12/threads/42", "/people/7"]);
  });

  test("the owner is a link to their profile and, across rooms, the room is a link to the room; a message row stays one link, its author text", () => {
    renderWithProviders(<SearchResults label="Search results" showRoom hits={[threadHit(42, { matchCount: 1, messages: [messageHit(991)] })]} />);
    const article = screen.getByRole("article");
    expect(within(article).getByRole("link", { name: "Ada" })).toHaveAttribute("href", "/people/7");
    expect(within(article).getByRole("link", { name: "Night Shift" })).toHaveAttribute("href", "/rooms/12");
    // The matching message's author is inside the message link: no link in a link.
    const row = within(article).getAllByRole("link").find((link) => link.getAttribute("href")?.includes("message="));
    expect(row?.querySelector("a")).toBeNull();
  });

  test("inside a room the room name is not drawn, so it is not a link either", () => {
    renderWithProviders(<SearchResults label="Search results" hits={[threadHit(42)]} statuses={STATUSES} showRoom={false} />);
    expect(screen.queryByRole("link", { name: "Night Shift" })).not.toBeInTheDocument();
  });

  test("in a room the status is the workflow's pill, with no room name", () => {
    renderWithProviders(<SearchResults label="Search results" hits={[threadHit(42, { status: "done", closed: true })]} statuses={STATUSES} showRoom={false} />);
    const article = screen.getByRole("article");
    expect(within(article).getByText("Done")).toBeInTheDocument();
    expect(within(article).queryByText("Night Shift")).not.toBeInTheDocument();
  });

  test("across rooms there is no workflow: an Open or Closed badge, the room name, and Archived for an archived room", () => {
    renderWithProviders(
      <SearchResults
        label="Search results"
        showRoom
        hits={[threadHit(1), threadHit(2, { closed: true, roomId: 13, roomName: "Billing", roomArchived: true, status: "wontdo" })]}
      />,
    );
    const [first, second] = screen.getAllByRole("article");
    expect(within(first).getByText("Open")).toBeInTheDocument();
    expect(within(first).getByText("Night Shift")).toBeInTheDocument();
    expect(within(first).queryByText("Archived")).not.toBeInTheDocument();
    expect(within(second).getByText("Closed")).toBeInTheDocument();
    expect(within(second).getByText("Billing")).toBeInTheDocument();
    expect(within(second).getByText("Archived")).toBeInTheDocument();
    // The status key of another room's workflow is never shown as if it were a label.
    expect(within(second).queryByText("wontdo")).not.toBeInTheDocument();
    expect(within(second).getByRole("link", { name: "#2 Database outage" })).toHaveAttribute("href", "/rooms/13/threads/2");
  });

  test("markup in a title or a snippet is text", () => {
    const evil = '<img src=x onerror="alert(1)">';
    const { container } = renderWithProviders(
      <SearchResults
        label="Search results"
        showRoom={false}
        statuses={STATUSES}
        hits={[threadHit(42, { titleSnippet: [{ text: evil, hit: false }], messages: [messageHit(1, { snippet: [{ text: evil, hit: true }] })] })]}
      />,
    );
    expect(container.querySelector("img")).toBeNull();
    expect(screen.getAllByText(evil).length).toBeGreaterThan(0);
  });
});
