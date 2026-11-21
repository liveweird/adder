// The conversation's jump to a message (`MessageList`'s `anchorId`): kept apart from `MessageList.test.tsx`, whose scroll tests fill one describe.
import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { act, screen, waitFor } from "@testing-library/react";
import { QueryClient } from "@tanstack/react-query";
import MessageList from "./MessageList";
import { jsonResponse } from "../test/http";
import { makeMessage, makeRoom, makeThread, messagePage } from "../test/messageFixtures";
import { renderWithProviders } from "../test/render";

const NEWEST_FIRST = [
  makeMessage({ id: 30, authorId: 7, authorName: "Cy Author", body: "third, mine" }),
  makeMessage({ id: 20, authorId: 8, authorName: "Di Other", body: "second" }),
];
const MESSAGES_KEY = ["rooms", "threads", 12, "messages", 42];
const SCROLL_HEIGHT = 5000;
const SCROLLER_TOP = 100;
/** Where the stubbed layout puts a message: id times ten below the scroller's top, so each id has its own centre. */
const centreOffsetOf = (id: number) => id * 10 - (200 - 60) / 2;

describe("MessageList: a jump to a message (anchorId)", () => {
  let mockFetch: ReturnType<typeof vi.fn>;
  const listUrls = () => mockFetch.mock.calls.map(([url]) => decodeURIComponent(url as string)).filter((url) => url.includes("/messages?"));
  const olderUrls = () => listUrls().filter((url) => url.includes("id[lt]="));

  function serve(handler: (url: string) => Response) {
    mockFetch.mockImplementation((url: string) => Promise.resolve(handler(decodeURIComponent(url))));
  }
  /** Serves a thread whose every page holds ONE message (30 first, then the id below the cursor) with more always behind it. */
  function serveLongThread() {
    serve((url) => {
      const cursor = /id\[lt\]=(\d+)/.exec(url)?.[1];
      const top = cursor === undefined ? 30 : Number(cursor) - 1;
      return jsonResponse(200, messagePage([makeMessage({ id: top, body: `message ${top}` })], 99));
    });
  }
  const item = (id: number) => document.querySelector<HTMLElement>(`[data-message-id="${id}"]`)!;
  const ui = (anchorId: number | null, scrollToId: number | null = null, onScrolled = vi.fn(), jumpSeq = 0) => (
    <MessageList room={makeRoom()} thread={makeThread()} scrollToId={scrollToId} onScrolled={onScrolled} anchorId={anchorId} jumpSeq={jumpSeq} />
  );

  beforeEach(() => {
    mockFetch = vi.fn();
    vi.stubGlobal("fetch", mockFetch);
    localStorage.setItem("adder.auth.token", "fake-token");
    localStorage.setItem("adder.auth.userId", "7");
    // happy-dom has no layout: a fixed one, so the jump's arithmetic is checkable (all restored by `restoreAllMocks`).
    vi.spyOn(HTMLElement.prototype, "scrollHeight", "get").mockReturnValue(SCROLL_HEIGHT);
    vi.spyOn(HTMLElement.prototype, "clientHeight", "get").mockReturnValue(200);
    vi.spyOn(HTMLElement.prototype, "offsetHeight", "get").mockReturnValue(60);
    vi.spyOn(Element.prototype, "getBoundingClientRect").mockImplementation(function (this: Element) {
      const id = this.getAttribute("data-message-id");
      const top = id === null ? SCROLLER_TOP : SCROLLER_TOP + Number(id) * 10;
      return { top, bottom: top + 60, left: 0, right: 0, width: 0, height: 60, x: 0, y: top, toJSON: () => ({}) };
    });
  });
  afterEach(() => {
    vi.useRealTimers();
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
    localStorage.clear();
  });

  test("a listed message is centred in the scroller, focused and highlighted, and the list does not go to the bottom", async () => {
    serve(() => jsonResponse(200, messagePage(NEWEST_FIRST)));
    renderWithProviders(ui(20));
    const region = await screen.findByRole("region", { name: "Messages" });
    await waitFor(() => expect(item(20)).toHaveFocus());
    expect(region.scrollTop).toBe(centreOffsetOf(20));
    expect(item(20)).toHaveAttribute("data-anchor", "true");
    expect(item(30)).not.toHaveAttribute("data-anchor");
    // Announced, not only coloured: a status line names the message's author.
    expect(screen.getByRole("status")).toHaveTextContent("Found the message by Di Other.");
    expect(screen.queryByRole("note")).not.toBeInTheDocument();
  });

  test("the announcing line exists while the list loads, so the text that appears in it is announced", async () => {
    let answer!: (response: Response) => void;
    mockFetch.mockImplementation(() => new Promise<Response>((resolve) => (answer = resolve)));
    renderWithProviders(ui(20));
    const status = screen.getAllByRole("status").find((element) => element.textContent === "")!;
    expect(status).toBeInTheDocument();
    answer(jsonResponse(200, messagePage(NEWEST_FIRST)));
    await waitFor(() => expect(status).toHaveTextContent("Found the message by Di Other."));
  });

  test("it loads older pages until the id shows, through the oldest id loaded as the cursor, then focuses it", async () => {
    serveLongThread();
    renderWithProviders(ui(27));
    await waitFor(() => expect(item(27)).toHaveFocus());
    expect(listUrls()).toEqual([
      "/api/v1/rooms/12/threads/42/messages?page=1&pageSize=50&sort=-id",
      "/api/v1/rooms/12/threads/42/messages?page=1&pageSize=50&sort=-id&id[lt]=30",
      "/api/v1/rooms/12/threads/42/messages?page=1&pageSize=50&sort=-id&id[lt]=29",
      "/api/v1/rooms/12/threads/42/messages?page=1&pageSize=50&sort=-id&id[lt]=28",
    ]);
    expect(screen.getByRole("region", { name: "Messages" }).scrollTop).toBe(centreOffsetOf(27));
    expect(screen.queryByRole("note")).not.toBeInTheDocument();
  });

  test("it stops after ten loads of older messages, says so above the list, shows the end of the conversation and leaves the button", async () => {
    serveLongThread();
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    renderWithProviders(ui(1), { queryClient });
    const note = await screen.findByRole("note");
    expect(note).toHaveTextContent("That message is too far back to jump to.");
    // A banner above the scroller, not inside it, so it stays in view when the list is at the bottom.
    expect(note.compareDocumentPosition(screen.getByRole("region", { name: "Messages" })) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect(screen.getByRole("region", { name: "Messages" })).not.toContainElement(note);
    expect(screen.getByRole("region", { name: "Messages" }).scrollTop).toBe(SCROLL_HEIGHT);
    // The first read and ten loads: the settled list asks for nothing more by itself.
    expect(listUrls()).toHaveLength(11);
    expect(queryClient.isFetching()).toBe(0);
    expect(screen.getByRole("button", { name: "Load older messages" })).toBeInTheDocument();
  });

  test("the ten loads are counted from the moment the anchor arrives: a reader's earlier presses of Load older do not spend them", async () => {
    const user = userEvent.setup();
    serveLongThread();
    const { rerender } = renderWithProviders(ui(null));
    for (let pressed = 1; pressed <= 3; pressed++) {
      await user.click(await screen.findByRole("button", { name: "Load older messages" }));
      await waitFor(() => expect(olderUrls()).toHaveLength(pressed));
    }
    rerender(ui(1));
    expect(await screen.findByRole("note")).toHaveTextContent("too far back");
    // The first read, three presses, then the jump's own ten.
    expect(listUrls()).toHaveLength(1 + 3 + 10);
  });

  test("a message the thread does not hold says so, and the list shows its end", async () => {
    serve(() => jsonResponse(200, messagePage(NEWEST_FIRST)));
    renderWithProviders(ui(999));
    expect(await screen.findByRole("note")).toHaveTextContent("That message no longer exists.");
    expect(screen.getByRole("region", { name: "Messages" }).scrollTop).toBe(SCROLL_HEIGHT);
    expect(document.querySelector("[data-anchor]")).toBeNull();
    expect(screen.getByRole("status")).toBeEmptyDOMElement();
  });

  test("a deleted message is listed as its tombstone, and the jump lands on that", async () => {
    serve(() => jsonResponse(200, messagePage([makeMessage({ id: 30, body: null, deleted: true }), NEWEST_FIRST[1]])));
    renderWithProviders(ui(30));
    await waitFor(() => expect(item(30)).toHaveFocus());
    expect(item(30)).toHaveTextContent("This message was deleted.");
    expect(screen.queryByRole("note")).not.toBeInTheDocument();
  });

  test("the jump happens once: a re-read of the list neither scrolls nor takes the focus back", async () => {
    serve(() => jsonResponse(200, messagePage(NEWEST_FIRST)));
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    renderWithProviders(ui(20), { queryClient });
    const region = await screen.findByRole("region", { name: "Messages" });
    await waitFor(() => expect(item(20)).toHaveFocus());
    expect(region.scrollTop).toBe(centreOffsetOf(20));
    item(30).focus();
    region.scrollTop = 7;
    await queryClient.invalidateQueries({ queryKey: MESSAGES_KEY });
    await waitFor(() => expect(listUrls()).toHaveLength(2));
    expect(region.scrollTop).toBe(7);
    expect(item(30)).toHaveFocus();
  });

  test("a refetch in flight is waited for, not cancelled: the older page is asked for after it", async () => {
    let held = false;
    let release: (() => void) | undefined;
    mockFetch.mockImplementation((url: string) => {
      if (decodeURIComponent(url).includes("id[lt]=20")) return Promise.resolve(jsonResponse(200, messagePage([makeMessage({ id: 10, body: "older" })], 1)));
      if (held) return new Promise<Response>((resolve) => (release = () => resolve(jsonResponse(200, messagePage(NEWEST_FIRST, 3)))));
      return Promise.resolve(jsonResponse(200, messagePage(NEWEST_FIRST, 3)));
    });
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const { rerender } = renderWithProviders(ui(null), { queryClient });
    await screen.findByRole("region", { name: "Messages" });
    held = true;
    void queryClient.invalidateQueries({ queryKey: MESSAGES_KEY });
    await waitFor(() => expect(release).toBeDefined());
    rerender(ui(10));
    // The anchor's effect has run with the refetch in flight, and asked for nothing.
    await act(async () => {});
    expect(olderUrls()).toHaveLength(0);
    held = false;
    release?.();
    await waitFor(() => expect(item(10)).toHaveFocus());
  });

  test("a live hint during a load of older messages is not lost: it re-reads the loaded pages and the search goes on", async () => {
    let olderCalls = 0;
    let arrived = false;
    mockFetch.mockImplementation((url: string) => {
      const decoded = decodeURIComponent(url);
      if (decoded.includes("id[lt]=20")) {
        olderCalls += 1;
        // The first ask never answers in time: the hint's refetch replaces it.
        return olderCalls === 1 ? new Promise<Response>(() => undefined) : Promise.resolve(jsonResponse(200, messagePage([makeMessage({ id: 10, body: "older" })], 1)));
      }
      return Promise.resolve(jsonResponse(200, messagePage(arrived ? [makeMessage({ id: 40, body: "just in" }), ...NEWEST_FIRST] : NEWEST_FIRST, arrived ? 4 : 3)));
    });
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    renderWithProviders(ui(10), { queryClient });
    await waitFor(() => expect(olderCalls).toBe(1));
    arrived = true;
    // What the live channel does: an ordinary invalidation.
    void queryClient.invalidateQueries({ queryKey: MESSAGES_KEY });
    await waitFor(() => expect(item(10)).toHaveFocus());
    expect(item(40)).toBeInTheDocument();
  });

  test("a new anchor on a list that is already open jumps to it", async () => {
    serve(() => jsonResponse(200, messagePage(NEWEST_FIRST)));
    const { rerender } = renderWithProviders(ui(null));
    const region = await screen.findByRole("region", { name: "Messages" });
    expect(region.scrollTop).toBe(SCROLL_HEIGHT);
    rerender(ui(20));
    await waitFor(() => expect(item(20)).toHaveFocus());
    // The scroll is relative to where the reader was (the stubbed rects do not move with it).
    expect(region.scrollTop).toBe(SCROLL_HEIGHT + centreOffsetOf(20));
  });

  test("asking again for the message the URL already names (the pin strip's \"Show in thread\") jumps again; re-reading the list does not", async () => {
    serve(() => jsonResponse(200, messagePage(NEWEST_FIRST)));
    const { rerender } = renderWithProviders(ui(20));
    const region = await screen.findByRole("region", { name: "Messages" });
    await waitFor(() => expect(item(20)).toHaveFocus());
    expect(region.scrollTop).toBe(centreOffsetOf(20));
    // The reader scrolls away and moves the focus; the same anchor with the same request is used up and moves nothing.
    region.scrollTop = 0;
    item(30).focus();
    rerender(ui(20));
    expect(region.scrollTop).toBe(0);
    expect(item(30)).toHaveFocus();
    rerender(ui(20, null, vi.fn(), 1));
    await waitFor(() => expect(item(20)).toHaveFocus());
    expect(region.scrollTop).toBe(centreOffsetOf(20));
  });

  test("the highlight goes after a few seconds; the focus stays", async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    serve(() => jsonResponse(200, messagePage(NEWEST_FIRST)));
    renderWithProviders(ui(20));
    await waitFor(() => expect(item(20)).toHaveAttribute("data-anchor", "true"));
    act(() => {
      vi.advanceTimersByTime(5000);
    });
    expect(item(20)).not.toHaveAttribute("data-anchor");
    expect(screen.getByRole("status")).toBeEmptyDOMElement();
    expect(item(20)).toHaveFocus();
  });

  test("the caller's own post after a jump still goes to the bottom", async () => {
    serve(() => jsonResponse(200, messagePage(NEWEST_FIRST)));
    const onScrolled = vi.fn();
    const { rerender } = renderWithProviders(ui(20, null, onScrolled));
    const region = await screen.findByRole("region", { name: "Messages" });
    await waitFor(() => expect(item(20)).toHaveFocus());
    expect(region.scrollTop).toBe(centreOffsetOf(20));
    rerender(ui(20, 30, onScrolled));
    expect(region.scrollTop).toBe(SCROLL_HEIGHT);
    expect(onScrolled).toHaveBeenCalledTimes(1);
  });

  test("a failed load of older messages ends the search: the alert and the button take over", async () => {
    serve((url) => (url.includes("id[lt]=") ? jsonResponse(500, { title: "Boom", status: 500 }) : jsonResponse(200, messagePage(NEWEST_FIRST, 3))));
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    renderWithProviders(ui(5), { queryClient });
    expect(await screen.findByText("Could not load the messages")).toBeInTheDocument();
    await act(async () => {});
    expect(queryClient.isFetching()).toBe(0);
    expect(olderUrls()).toHaveLength(1);
    expect(screen.queryByRole("note")).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Load older messages" })).toBeInTheDocument();
  });
});
