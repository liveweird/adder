import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { act, cleanup, fireEvent, screen, waitFor } from "@testing-library/react";
import { QueryClient } from "@tanstack/react-query";
import MessageList from "./MessageList";
import { jsonResponse } from "../test/http";
import { makeMessage, makeRoom, makeThread, messagePage } from "../test/messageFixtures";
import { renderWithProviders } from "../test/render";
import { roomKeys } from "../utils/roomKeys";

const before = makeMessage({ id: 18, body: "Earlier context" });
const target = makeMessage({ id: 20, authorId: 8, authorName: "Di Other", body: "The old decision" });
const after = makeMessage({ id: 22, body: "Later context" });
const latest = makeMessage({ id: 900, body: "The newest reply" });
const conversation = { name: "Messages" };
const item = (id: number) => document.querySelector<HTMLElement>(`[data-message-id="${id}"]`)!;
const context = (messageId: number, items = [before, target, after], older = true, newer = true) =>
  jsonResponse(200, { messageId, items, older, newer });

describe("MessageList: direct message context", () => {
  let mockFetch: ReturnType<typeof vi.fn>;
  const paths = () => mockFetch.mock.calls.map(([url]) => decodeURIComponent(url as string));
  const messages = () => paths().filter((url) => url.includes("/threads/42/messages"));
  const readMarkers = () => paths().filter((url) => url.endsWith("/threads/42/read-marker"));
  const showLatest = vi.fn();
  const showReply = vi.fn();
  const onScrolled = vi.fn();
  const ui = (anchorId: number | null, jumpSeq = 0, scrollToId: number | null = null) => (
    <MessageList
      room={makeRoom()}
      thread={makeThread()}
      scrollToId={scrollToId}
      onScrolled={onScrolled}
      anchorId={anchorId}
      jumpSeq={jumpSeq}
      onReturnToLatest={showLatest}
    />
  );

  function serve(handler: (url: string) => Response | Promise<Response>) {
    mockFetch.mockImplementation((url: string) => {
      if (url.includes("/events?")) return Promise.resolve(jsonResponse(200, { items: [], total: 0 }));
      if (url.includes("/pins?")) return Promise.resolve(jsonResponse(200, { items: [], page: 1, pageSize: 50, total: 0 }));
      if (url.endsWith("/read-marker")) return Promise.resolve(new Response(null, { status: 204 }));
      return Promise.resolve(handler(decodeURIComponent(url)));
    });
  }

  beforeEach(() => {
    showLatest.mockClear();
    showReply.mockClear();
    onScrolled.mockClear();
    mockFetch = vi.fn();
    vi.stubGlobal("fetch", mockFetch);
    localStorage.setItem("adder.auth.token", "fake-token");
    localStorage.setItem("adder.auth.userId", "7");
    vi.spyOn(HTMLElement.prototype, "scrollHeight", "get").mockReturnValue(5000);
    vi.spyOn(HTMLElement.prototype, "clientHeight", "get").mockReturnValue(200);
    vi.spyOn(HTMLElement.prototype, "offsetHeight", "get").mockReturnValue(60);
    vi.spyOn(Element.prototype, "getBoundingClientRect").mockImplementation(function (this: Element) {
      const id = Number(this.getAttribute("data-message-id"));
      const top = 100 + id * 10;
      return { top, bottom: top + 60, left: 0, right: 0, width: 0, height: 60, x: 0, y: top, toJSON: () => ({}) };
    });
  });
  afterEach(() => {
    cleanup();
    vi.useRealTimers();
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
    localStorage.clear();
  });

  test("a deep link makes one request for an old target and focuses it between adjacent messages", async () => {
    serve(() => context(20));
    renderWithProviders(ui(20));
    const region = await screen.findByRole("region", conversation);
    await waitFor(() => expect(item(20)).toHaveFocus());
    expect(region.scrollTop).toBe(130);
    expect(item(20)).toHaveAttribute("data-anchor", "true");
    expect(region).toHaveTextContent(/Earlier context.*The old decision.*Later context/);
    expect(screen.getByRole("status")).toHaveTextContent("Found the message by Di Other.");
    expect(messages()).toEqual(["/api/v1/rooms/12/threads/42/messages/context?messageId=20"]);
  });

  test("the target highlight and announcement expire without moving focus", async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    serve(() => context(20));
    renderWithProviders(ui(20));
    await waitFor(() => expect(item(20)).toHaveAttribute("data-anchor", "true"));
    act(() => { vi.advanceTimersByTime(5000); });
    expect(item(20)).not.toHaveAttribute("data-anchor");
    expect(screen.getByRole("status")).toBeEmptyDOMElement();
    expect(item(20)).toHaveFocus();
  });

  test("resizing live conversation keeps its end in view, but resizing history preserves position", async () => {
    let resize: () => void = () => undefined;
    vi.stubGlobal("ResizeObserver", class {
      private readonly callback: () => void;
      constructor(callback: () => void) {
        this.callback = callback;
      }
      observe(node: Element) {
        if (node.getAttribute("aria-label") === "Messages") resize = this.callback;
      }
      disconnect() {}
    });
    serve((url) => url.includes("/context?") ? context(20) : jsonResponse(200, messagePage([latest])));
    const { rerender } = renderWithProviders(ui(null));
    const region = await screen.findByRole("region", conversation);
    region.scrollTop = 10;
    act(resize);
    expect(region.scrollTop).toBe(5000);
    rerender(ui(20));
    await waitFor(() => expect(item(20)).toHaveFocus());
    region.scrollTop = 20;
    act(resize);
    expect(region.scrollTop).toBe(20);
  });

  test("going earlier and later replaces the window using its boundaries, and returning fetches the latest", async () => {
    serve((url) => {
      if (url.includes("messageId=18")) return context(18, [before, target], false);
      if (url.includes("messageId=22")) return context(22, [target, after], true, false);
      return url.includes("/context?") ? context(20) : jsonResponse(200, messagePage([latest]));
    });
    renderWithProviders(ui(20));
    const user = userEvent.setup();
    await user.click(await screen.findByRole("button", { name: "Earlier messages" }));
    await waitFor(() => expect(item(18)).toHaveFocus());
    expect(screen.queryByText("Later context")).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Later messages" }));
    await waitFor(() => expect(item(20)).toHaveFocus());
    await user.click(screen.getByRole("button", { name: "Later messages" }));
    await waitFor(() => expect(item(22)).toHaveFocus());
    expect(screen.queryByRole("button", { name: "Later messages" })).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Back to latest" }));
    expect(await screen.findByText("The newest reply")).toBeInTheDocument();
    expect(screen.getByRole("region", conversation)).toHaveFocus();
    expect(showLatest).toHaveBeenCalledOnce();
  });

  test("a quote jump offers a compact path back to the exact reply", async () => {
    serve(() => context(20));
    renderWithProviders(
      <MessageList
        room={makeRoom()}
        thread={makeThread()}
        scrollToId={null}
        onScrolled={onScrolled}
        anchorId={20}
        returnMessageId={22}
        onReturnToReply={showReply}
        onReturnToLatest={showLatest}
      />,
    );
    await waitFor(() => expect(item(20)).toHaveFocus());
    await userEvent.setup().click(screen.getByRole("button", { name: "Back to reply" }));
    expect(showReply).toHaveBeenCalledOnce();
    expect(showLatest).not.toHaveBeenCalled();
  });

  test("the end of historical context leaves newer messages unread", async () => {
    serve(() => context(20));
    renderWithProviders(ui(20));
    const region = await screen.findByRole("region", conversation);
    await waitFor(() => expect(item(20)).toHaveFocus());
    region.scrollTop = 5000;
    fireEvent.scroll(region);
    expect(readMarkers()).toEqual([]);
  });

  test("a context window at the actual end acknowledges and follows newer replies", async () => {
    let items = [before, target, after];
    serve(() => context(22, items, true, false));
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    renderWithProviders(ui(22), { queryClient });
    const region = await screen.findByRole("region", conversation);
    await waitFor(() => expect(item(22)).toHaveFocus());
    region.scrollTop = 5000;
    fireEvent.scroll(region);
    await waitFor(() => expect(readMarkers()).toHaveLength(1));
    items = [...items, latest];
    await queryClient.invalidateQueries({ queryKey: roomKeys.messages(12, 42) });
    expect(await screen.findByText(latest.body!)).toBeInTheDocument();
    expect(region.scrollTop).toBe(5000);
    expect(item(22)).toHaveFocus();
  });

  test("a live refresh updates the selected window without repeating the focus jump", async () => {
    let body = target.body;
    serve(() => context(20, [before, { ...target, body }, after]));
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const { rerender } = renderWithProviders(ui(20), { queryClient });
    await waitFor(() => expect(item(20)).toHaveFocus());
    item(22).focus();
    body = "Edited decision";
    await queryClient.invalidateQueries({ queryKey: roomKeys.messages(12, 42) });
    expect(await screen.findByText(body)).toBeInTheDocument();
    expect(item(22)).toHaveFocus();
    rerender(ui(20, 1));
    await waitFor(() => expect(item(20)).toHaveFocus());
  });

  test("a missing anchor gets a missing-message state and a path back to the conversation", async () => {
    serve((url) => url.includes("/context?") ? jsonResponse(404, { status: 404, title: "Not found" }) : jsonResponse(200, messagePage([latest])));
    renderWithProviders(ui(99));
    expect(await screen.findByRole("note")).toHaveTextContent("That message no longer exists.");
    expect(messages()).toHaveLength(1);
    await userEvent.setup().click(screen.getByRole("button", { name: "Back to latest" }));
    expect(await screen.findByText("The newest reply")).toBeInTheDocument();
  });

  test("a deleted anchor is focused as a tombstone and retry can recover a temporary failure", async () => {
    let fail = true;
    serve(() => fail
      ? jsonResponse(500, { status: 500, title: "Unavailable" })
      : context(20, [before, { ...target, deleted: true, body: null }, after]));
    renderWithProviders(ui(20));
    expect(await screen.findByText("Could not load the messages")).toBeInTheDocument();
    fail = false;
    await userEvent.setup().click(screen.getByRole("button", { name: "Retry" }));
    expect(await screen.findByText("This message was deleted.")).toBeInTheDocument();
    await waitFor(() => expect(item(20)).toHaveFocus());
  });

  test("a reply posted while reading history brings the reader to the latest messages", async () => {
    serve((url) => url.includes("/context?") ? context(20) : jsonResponse(200, messagePage([latest])));
    const { rerender } = renderWithProviders(ui(20));
    await waitFor(() => expect(item(20)).toHaveFocus());
    rerender(ui(20, 0, 900));
    expect(await screen.findByText("The newest reply")).toBeInTheDocument();
    await waitFor(() => expect(onScrolled).toHaveBeenCalledOnce());
    expect(messages()).toContain("/api/v1/rooms/12/threads/42/messages?page=1&pageSize=50&sort=-id");
  });
});
