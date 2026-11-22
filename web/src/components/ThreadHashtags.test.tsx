import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { screen, waitFor, within } from "@testing-library/react";
import ThreadHashtags from "./ThreadHashtags";
import ThreadList from "./ThreadList";
import ThreadPane from "./ThreadPane";
import ThreadSidePanel from "./ThreadSidePanel";
import { headingOutline } from "../test/headings";
import { jsonResponse } from "../test/http";
import { makeMessage, makeRoom, makeThread, messagePage } from "../test/messageFixtures";
import { renderWithProviders } from "../test/render";
import { SIDE_WORKFLOW, eventPage, makeEvent } from "../test/sideFixtures";

// A thread's hashtags (1.2 A8): dimmed text on a list row (the row is one link), filter links in the pane header's third row and in the side
// panel's "Hashtags" section. The wire carries the folded keys, alphabetical, at most 20.
const AT = Date.UTC(2026, 5, 1, 9, 30);
const TEN = ["alpha", "beta", "gamma", "delta", "epsilon", "zeta", "eta", "theta", "iota", "kappa"];
const row = (number: number, title: string, hashtags: string[]) => ({
  id: number,
  number,
  title,
  status: "triage",
  closed: false,
  ownerId: 5,
  ownerName: "Ann Owner",
  messageCount: 2,
  lastActivityAt: AT,
  createdAt: AT,
  unreadCount: 0,
  watching: false,
  hashtags,
  tags: [],
});

describe("ThreadHashtags", () => {
  test("each hashtag is a link to the list filtered to it, across open and closed threads", () => {
    renderWithProviders(<ThreadHashtags roomId={12} number={42} hashtags={["outage", "db-3"]} />);
    const group = screen.getByRole("group", { name: "Hashtags" });
    expect(within(group).getByRole("link", { name: "#outage" })).toHaveAttribute("href", "/rooms/12/threads/42?scope=all&tag=outage");
    expect(within(group).getByRole("link", { name: "#db-3" })).toHaveAttribute("href", "/rooms/12/threads/42?scope=all&tag=db-3");
  });

  test("with a limit the rest is one button that asks for the details, named in words", async () => {
    const onShowAll = vi.fn();
    renderWithProviders(<ThreadHashtags roomId={12} number={42} hashtags={TEN} limit={6} onShowAll={onShowAll} />);
    expect(screen.getAllByRole("link")).toHaveLength(6);
    // The accessible name starts with the visible text.
    const more = screen.getByRole("button", { name: "+4: show 4 more hashtags in the details" });
    expect(more).toHaveTextContent("+4");
    await userEvent.click(more);
    expect(onShowAll).toHaveBeenCalledTimes(1);
  });

  test("no limit shows them all and no button, and a limit that holds them all shows no button either", () => {
    const { unmount } = renderWithProviders(<ThreadHashtags roomId={12} number={42} hashtags={TEN} onShowAll={vi.fn()} />);
    expect(screen.getAllByRole("link")).toHaveLength(10);
    expect(screen.queryByRole("button")).toBeNull();
    unmount();
    renderWithProviders(<ThreadHashtags roomId={12} number={42} hashtags={["a1"]} limit={6} onShowAll={vi.fn()} />);
    expect(screen.queryByRole("button")).toBeNull();
  });
});

describe("hashtags in the room view", () => {
  // The suite shares modules between files (`isolate: false`): a stub of a prototype method is put back exactly as it was.
  const originalScrollIntoView = Element.prototype.scrollIntoView;
  let mockFetch: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    mockFetch = vi.fn();
    vi.stubGlobal("fetch", mockFetch);
    localStorage.setItem("adder.auth.token", "fake-token");
  });
  afterEach(() => {
    vi.unstubAllGlobals();
    localStorage.clear();
    Element.prototype.scrollIntoView = originalScrollIntoView;
  });

  function serve(threadHashtags: string[], items = [row(2, "Disk full on db-3", ["outage", "db-3"]), row(1, "Old alert", [])]) {
    mockFetch.mockImplementation((url: string) => {
      if (url.startsWith("/api/v1/rooms/12/threads?")) return Promise.resolve(jsonResponse(200, { items, page: 1, pageSize: 50, total: items.length }));
      if (url === "/api/v1/rooms/12/threads/42") return Promise.resolve(jsonResponse(200, makeThread({ hashtags: threadHashtags })));
      if (url === "/api/v1/rooms/12/workflow") return Promise.resolve(jsonResponse(200, SIDE_WORKFLOW));
      if (url.startsWith("/api/v1/rooms/12/pins?")) return Promise.resolve(jsonResponse(200, { items: [], page: 1, pageSize: 50, total: 0 }));
      if (url.startsWith("/api/v1/rooms/12/threads/42/messages?")) return Promise.resolve(jsonResponse(200, messagePage([makeMessage({ id: 1 })])));
      if (url.startsWith("/api/v1/rooms/12/threads/42/events?")) return Promise.resolve(jsonResponse(200, eventPage([makeEvent()])));
      return Promise.resolve(jsonResponse(404, { title: "Not found", status: 404 }));
    });
  }

  test("a list row shows up to three hashtags as plain dimmed text and \"+N\" for the rest, and stays ONE link with no link inside", async () => {
    serve([], [row(2, "Disk full on db-3", ["a1", "b2", "c3", "d4", "e5"]), row(1, "Old alert", ["outage"]), row(3, "Quiet", [])]);
    renderWithProviders(<ThreadList room={makeRoom()} activeNumber={null} />);
    const many = await screen.findByRole("link", { name: /Disk full on db-3/ });
    expect(many).toHaveTextContent("#a1 #b2 #c3 +2");
    expect(many).not.toHaveTextContent("#d4");
    expect(many.querySelectorAll("a")).toHaveLength(0);
    expect(screen.getByRole("link", { name: /Old alert/ })).toHaveTextContent("#outage");
    expect(screen.getByRole("link", { name: /Old alert/ })).not.toHaveTextContent("+");
    const quiet = screen.getByRole("link", { name: /Quiet/ });
    expect(quiet.querySelector("[data-row-hashtags]")).toBeNull();
    expect(screen.queryByRole("link", { name: "#outage" })).toBeNull();
  });

  test("the pane header's third row links up to four hashtags, and \"+N\" opens the details with every one in its Hashtags section", async () => {
    serve(TEN);
    renderWithProviders(<ThreadPane room={makeRoom()} numberParam="42" />);
    // The header's row comes first in the DOM; the side panel's section (folded away until opened, but in the document) follows it.
    const [group] = await screen.findAllByRole("group", { name: "Hashtags" });
    expect(within(group).getAllByRole("link")).toHaveLength(4);
    expect(within(group).getByRole("link", { name: "#alpha" })).toHaveAttribute("href", "/rooms/12/threads/42?scope=all&tag=alpha");
    const toggle = screen.getByRole("button", { name: "Details" });
    expect(toggle).toHaveAttribute("aria-expanded", "false");
    const scrolled = vi.fn();
    Element.prototype.scrollIntoView = scrolled;
    await userEvent.click(within(group).getByRole("button", { name: "+6: show 6 more hashtags in the details" }));
    expect(toggle).toHaveAttribute("aria-expanded", "true");
    // Open already or not, the Hashtags section is brought into view.
    await waitFor(() => expect(scrolled).toHaveBeenCalledTimes(1));
    expect(scrolled.mock.instances[0]).toHaveAttribute("data-section", "hashtags");
    await userEvent.click(within(group).getByRole("button", { name: "+6: show 6 more hashtags in the details" }));
    await waitFor(() => expect(scrolled).toHaveBeenCalledTimes(2));
    const section = screen.getByRole("heading", { level: 4, name: "Hashtags" }).closest("[role=group]") as HTMLElement;
    expect(within(section).getAllByRole("link")).toHaveLength(10);
    expect(within(section).getByRole("link", { name: "#kappa" })).toBeInTheDocument();
  });

  test("a thread with no hashtag has no third row and no Hashtags section", async () => {
    serve([]);
    renderWithProviders(<ThreadPane room={makeRoom()} numberParam="42" />);
    await screen.findByRole("button", { name: "Details" });
    expect(screen.queryByRole("group", { name: "Hashtags" })).toBeNull();
    expect(headingOutline().some(([, name]) => name === "Hashtags")).toBe(false);
  });

  test("the side panel's Hashtags section sits between Details and History as an h4", () => {
    serve([]);
    renderWithProviders(<ThreadSidePanel room={makeRoom()} thread={makeThread({ number: 42, hashtags: ["outage", "db-3"] })} />);
    expect(headingOutline()).toEqual([
      [4, "Owner"],
      [4, "Details"],
      [4, "Tags"],
      [4, "Hashtags"],
      [4, "History"],
    ]);
    expect(screen.getByRole("link", { name: "#outage" })).toHaveAttribute("href", "/rooms/12/threads/42?scope=all&tag=outage");
  });
});
