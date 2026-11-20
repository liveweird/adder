// The conversation's unread paths (0.6.0): the "New" separator, "Jump to the first new message", the arrivals button and the read marker. Kept apart from
// `MessageList.test.tsx` (one describe of scroll tests) like `MessageListJump.test.tsx`; every existing scroll path stays covered there.
import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { cleanup, fireEvent, screen, waitFor, within } from "@testing-library/react";
import { QueryClient } from "@tanstack/react-query";
import MessageList from "./MessageList";
import { jsonResponse } from "../test/http";
import { makeMessage, makeRoom, makeThread, messagePage } from "../test/messageFixtures";
import { renderWithProviders } from "../test/render";
import { resetTabVisibility, setTabVisible } from "../test/visibility";

const NEWEST_FIRST = [
  makeMessage({ id: 30, authorId: 7, authorName: "Cy Author", body: "third, mine" }),
  makeMessage({ id: 20, authorId: 8, authorName: "Di Other", body: "second" }),
];
const MESSAGES_KEY = ["rooms", "threads", 12, "messages", 42];
const SCROLL_HEIGHT = 5000;
const CLIENT_HEIGHT = 200;
const SCROLLER_TOP = 100;
/** Where the stubbed layout puts a message: id times ten below the scroller's top, 60 high; the scroller itself is 60 high too. */
const centreOffsetOf = (id: number) => id * 10 - (CLIENT_HEIGHT - 60) / 2;

describe("MessageList: unread", () => {
  let mockFetch: ReturnType<typeof vi.fn>;
  let queryClient: QueryClient;
  let newestFirst = NEWEST_FIRST;
  const markerIds = () =>
    mockFetch.mock.calls.filter(([url]) => String(url).endsWith("/read-marker")).map(([, init]) => (JSON.parse((init as RequestInit).body as string) as { messageId: number }).messageId);

  function serve(older: ReturnType<typeof makeMessage>[] = []) {
    mockFetch.mockImplementation((url: string, init?: RequestInit) => {
      const decoded = decodeURIComponent(url);
      if (init?.method === "PUT") return Promise.resolve(new Response(null, { status: 204 }));
      if (decoded.includes("id[lt]=")) return Promise.resolve(jsonResponse(200, messagePage(older, older.length)));
      return Promise.resolve(jsonResponse(200, messagePage(newestFirst, newestFirst.length + older.length)));
    });
  }
  const ui = (props: Partial<React.ComponentProps<typeof MessageList>> = {}) => (
    <MessageList room={makeRoom()} thread={makeThread()} scrollToId={null} onScrolled={vi.fn()} {...props} />
  );
  const renderList = (props: Partial<React.ComponentProps<typeof MessageList>> = {}) => renderWithProviders(ui(props), { queryClient });
  const item = (id: number) => document.querySelector<HTMLElement>(`[data-message-id="${id}"]`)!;
  const JUMP = { name: "Jump to the first new message" };

  /** Scrolls the stubbed scroller so the reader is `fromEnd` pixels from the end, and tells the list (a scroll event). */
  function scrollTo(region: HTMLElement, fromEnd: number) {
    region.scrollTop = SCROLL_HEIGHT - CLIENT_HEIGHT - fromEnd;
    fireEvent.scroll(region);
  }
  /** Lists a newer message by someone else (or the caller) through a refetch, as a live hint would. */
  async function arrive(...ids: { id: number; authorId: number }[]) {
    newestFirst = [...ids.map(({ id, authorId }) => makeMessage({ id, authorId, authorName: authorId === 7 ? "Cy Author" : "Di Other", body: `arrived ${id}` })).reverse(), ...NEWEST_FIRST];
    await queryClient.invalidateQueries({ queryKey: MESSAGES_KEY });
    await waitFor(() => expect(document.querySelectorAll("[data-message-id]")).toHaveLength(newestFirst.length));
  }

  beforeEach(() => {
    mockFetch = vi.fn();
    newestFirst = NEWEST_FIRST;
    queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    vi.stubGlobal("fetch", mockFetch);
    localStorage.setItem("adder.auth.token", "fake-token");
    localStorage.setItem("adder.auth.userId", "7");
    vi.spyOn(HTMLElement.prototype, "scrollHeight", "get").mockReturnValue(SCROLL_HEIGHT);
    vi.spyOn(HTMLElement.prototype, "clientHeight", "get").mockReturnValue(CLIENT_HEIGHT);
    vi.spyOn(HTMLElement.prototype, "offsetHeight", "get").mockReturnValue(60);
    vi.spyOn(Element.prototype, "getBoundingClientRect").mockImplementation(function (this: Element) {
      const id = this.getAttribute("data-message-id");
      const top = id === null ? SCROLLER_TOP : SCROLLER_TOP + Number(id) * 10;
      return { top, bottom: top + 60, left: 0, right: 0, width: 0, height: 60, x: 0, y: top, toJSON: () => ({}) };
    });
  });
  afterEach(() => {
    // Unmount while fetch is still stubbed: leaving a conversation sends the marker it was holding back.
    cleanup();
    resetTabVisibility();
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
    localStorage.clear();
  });

  describe("the New separator", () => {
    test("stands above the first message that was unread when the thread opened, and nowhere else", async () => {
      serve();
      renderList({ thread: makeThread({ firstUnreadMessageId: 20, unreadCount: 2 }) });
      await screen.findByRole("region", { name: "Messages" });
      const separator = within(item(20)).getByRole("separator", { name: "New messages" });
      expect(separator).toHaveTextContent("New");
      // The word is the shared blue unread cue, not a dimmed run.
      expect(within(separator).getByText("New").closest("[data-unread-cue=count]")).not.toBeNull();
      expect(screen.getAllByRole("separator")).toHaveLength(1);
      // Inside the message's own item, so the list stays a list of messages.
      expect(item(20).firstElementChild).toBe(separator);
      expect(screen.getAllByRole("listitem").filter((li) => li.tagName === "LI")).toHaveLength(2);
    });

    test("is taken once: a later read of the thread that names another message does not move it", async () => {
      serve();
      const { rerender } = renderList({ thread: makeThread({ firstUnreadMessageId: 20 }) });
      await screen.findByRole("region", { name: "Messages" });
      rerender(ui({ thread: makeThread({ firstUnreadMessageId: 30 }) }));
      expect(within(item(20)).getByRole("separator")).toBeInTheDocument();
      expect(within(item(30)).queryByRole("separator")).not.toBeInTheDocument();
      rerender(ui({ thread: makeThread({ firstUnreadMessageId: null }) }));
      expect(within(item(20)).getByRole("separator")).toBeInTheDocument();
    });

    test("waits for the thread read to settle, so a cached copy from an earlier visit does not decide it", async () => {
      serve();
      const { rerender } = renderList({ thread: makeThread({ firstUnreadMessageId: 30 }), threadSettled: false });
      await screen.findByRole("region", { name: "Messages" });
      expect(screen.queryByRole("separator")).not.toBeInTheDocument();
      rerender(ui({ thread: makeThread({ firstUnreadMessageId: 20 }), threadSettled: true }));
      expect(within(item(20)).getByRole("separator")).toBeInTheDocument();
    });

    test("a thread with nothing unread has no separator and no jump button", async () => {
      serve();
      renderList({ thread: makeThread({ firstUnreadMessageId: null }) });
      await screen.findByRole("region", { name: "Messages" });
      expect(screen.queryByRole("separator")).not.toBeInTheDocument();
      expect(screen.queryByRole("button", JUMP)).not.toBeInTheDocument();
    });
  });

  describe("Jump to the first new message", () => {
    test("shows while that message is off screen and takes the reader to it: centred, focused, announced", async () => {
      const user = userEvent.setup();
      serve();
      renderList({ thread: makeThread({ firstUnreadMessageId: 20 }) });
      const region = await screen.findByRole("region", { name: "Messages" });
      expect(region.scrollTop).toBe(SCROLL_HEIGHT);
      await user.click(await screen.findByRole("button", JUMP));
      expect(item(20)).toHaveFocus();
      // The list opened at the bottom; the jump moves the scroller by the distance to the message's centre.
      expect(region.scrollTop).toBe(SCROLL_HEIGHT + centreOffsetOf(20));
      expect(screen.getByRole("status")).toHaveTextContent("Found the message by Di Other.");
      // Reading from there is not "at the end": the reader moved away from it.
      expect(item(20)).toHaveAttribute("data-anchor", "true");
    });

    test("is hidden while that message is on screen, and returns when the reader scrolls away", async () => {
      serve();
      newestFirst = [makeMessage({ id: 6, authorId: 8, authorName: "Di Other" }), makeMessage({ id: 5, authorId: 8, authorName: "Di Other" })];
      renderList({ thread: makeThread({ firstUnreadMessageId: 5 }) });
      const region = await screen.findByRole("region", { name: "Messages" });
      expect(screen.queryByRole("button", JUMP)).not.toBeInTheDocument();
      // The stub puts message 5 at 150 to 210, the scroller at 100 to 160: move the scroller's top below it.
      vi.spyOn(Element.prototype, "getBoundingClientRect").mockImplementation(function (this: Element) {
        const id = this.getAttribute("data-message-id");
        const top = id === null ? 400 : 100 + Number(id) * 10;
        return { top, bottom: top + 60, left: 0, right: 0, width: 0, height: 60, x: 0, y: top, toJSON: () => ({}) };
      });
      fireEvent.scroll(region);
      expect(await screen.findByRole("button", JUMP)).toBeInTheDocument();
    });

    test("loads older messages until the first new one is listed, then lands on it", async () => {
      const user = userEvent.setup();
      serve([makeMessage({ id: 10, authorId: 8, authorName: "Eve Older", body: "the first new one" })]);
      renderList({ thread: makeThread({ firstUnreadMessageId: 10 }) });
      await screen.findByRole("region", { name: "Messages" });
      expect(item(10)).toBeNull();
      await user.click(await screen.findByRole("button", JUMP));
      await waitFor(() => expect(item(10)).toHaveFocus());
      expect(within(item(10)).getByRole("separator")).toBeInTheDocument();
      expect(screen.getByRole("region", { name: "Messages" }).scrollTop).toBe(SCROLL_HEIGHT + centreOffsetOf(10));
    });

    test("can be used again after the reader has moved on", async () => {
      const user = userEvent.setup();
      serve();
      renderList({ thread: makeThread({ firstUnreadMessageId: 20 }) });
      const region = await screen.findByRole("region", { name: "Messages" });
      await user.click(await screen.findByRole("button", JUMP));
      expect(region.scrollTop).toBe(SCROLL_HEIGHT + centreOffsetOf(20));
      region.scrollTop = 4000;
      await user.click(screen.getByRole("button", JUMP));
      expect(region.scrollTop).toBe(4000 + centreOffsetOf(20));
    });
  });

  describe("arrivals under a reader who scrolled up", () => {
    test("a reader at the end sees no button: the list follows", async () => {
      serve();
      renderList();
      await screen.findByRole("region", { name: "Messages" });
      await arrive({ id: 40, authorId: 8 });
      expect(screen.queryByRole("button", { name: /new message/ })).not.toBeInTheDocument();
      expect(screen.getByRole("status")).toHaveTextContent("");
    });

    test("a message from someone else under a reader who scrolled up shows its count and is announced; the button goes to the end", async () => {
      const user = userEvent.setup();
      serve();
      renderList();
      const region = await screen.findByRole("region", { name: "Messages" });
      scrollTo(region, 700);
      await arrive({ id: 40, authorId: 8 });
      const button = await screen.findByRole("button", { name: "1 new message below" });
      expect(screen.getByRole("status")).toHaveTextContent("1 new message arrived");
      expect(region.scrollTop).toBe(SCROLL_HEIGHT - CLIENT_HEIGHT - 700);
      await arrive({ id: 40, authorId: 8 }, { id: 41, authorId: 9 });
      expect(await screen.findByRole("button", { name: "2 new messages below" })).toBe(button);
      expect(screen.getByRole("status")).toHaveTextContent("2 new messages arrived");
      await user.click(button);
      expect(region.scrollTop).toBe(SCROLL_HEIGHT);
      expect(region).toHaveFocus();
      expect(screen.queryByRole("button", { name: /new messages? below/ })).not.toBeInTheDocument();
      expect(screen.getByRole("status")).toHaveTextContent("");
    });

    test("reaching the end by scrolling clears it", async () => {
      serve();
      renderList();
      const region = await screen.findByRole("region", { name: "Messages" });
      scrollTo(region, 700);
      await arrive({ id: 40, authorId: 8 });
      await screen.findByRole("button", { name: "1 new message below" });
      scrollTo(region, 48);
      await waitFor(() => expect(screen.queryByRole("button", { name: /new messages? below/ })).not.toBeInTheDocument());
    });

    test("the caller's own message never counts", async () => {
      serve();
      renderList();
      const region = await screen.findByRole("region", { name: "Messages" });
      scrollTo(region, 700);
      await arrive({ id: 40, authorId: 7 });
      expect(screen.queryByRole("button", { name: /new messages? below/ })).not.toBeInTheDocument();
    });
  });

  describe("the read marker", () => {
    test("a member at the end of a conversation, in a visible tab, acknowledges the newest message", async () => {
      serve();
      renderList();
      await screen.findByRole("region", { name: "Messages" });
      await waitFor(() => expect(markerIds()).toEqual([30]));
    });

    test("never from a stale cached copy: the newest id of an earlier visit was not seen, so only the re-read's newest is acknowledged", async () => {
      queryClient.setQueryData(MESSAGES_KEY, { pages: [messagePage([makeMessage({ id: 25, authorId: 8 })])], pageParams: [undefined] });
      serve();
      renderList();
      await screen.findByRole("region", { name: "Messages" });
      await waitFor(() => expect(markerIds()).toEqual([30]));
    });

    test("not while the thread read that holds the boundary is still being re-read", async () => {
      serve();
      const { rerender } = renderList({ threadSettled: false });
      await screen.findByRole("region", { name: "Messages" });
      expect(markerIds()).toEqual([]);
      rerender(ui({ threadSettled: true }));
      await waitFor(() => expect(markerIds()).toEqual([30]));
    });

    test("not while the reader is scrolled up (a list opened on a jump): it goes when they reach the end", async () => {
      serve();
      renderList({ anchorId: 20 });
      const region = await screen.findByRole("region", { name: "Messages" });
      await waitFor(() => expect(item(20)).toHaveFocus());
      expect(markerIds()).toEqual([]);
      scrollTo(region, 0);
      await waitFor(() => expect(markerIds()).toEqual([30]));
    });

    test("not while the tab is hidden", async () => {
      setTabVisible(false);
      serve();
      renderList();
      await screen.findByRole("region", { name: "Messages" });
      expect(markerIds()).toEqual([]);
      setTabVisible(true);
      await waitFor(() => expect(markerIds()).toEqual([30]));
    });

    test("never for an administrator who is not a member", async () => {
      serve();
      renderList({ room: makeRoom({ role: null }) });
      await screen.findByRole("region", { name: "Messages" });
      await arrive({ id: 40, authorId: 8 });
      expect(markerIds()).toEqual([]);
    });
  });
});
