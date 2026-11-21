import { useState } from "react";
import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { useLocation } from "react-router-dom";
import { QueryClient } from "@tanstack/react-query";
import { act, screen, waitFor, within } from "@testing-library/react";
import ThreadPinStrip from "./ThreadPinStrip";
import type { RoomPin } from "../api/pins";
import type { RoomResponse } from "../api/rooms";
import { jsonResponse } from "../test/http";
import { SOME_TIME, makeMessage, makeRoom } from "../test/messageFixtures";
import { renderWithProviders } from "../test/render";
import { browserTimeZone, formatDateTime } from "../utils/formatDate";
import { keysForFrame } from "../utils/liveKeys";

const WHEN = formatDateTime(SOME_TIME, browserTimeZone());
const pin = (id: number, over: Partial<RoomPin> & { body?: string; author?: string; messageId?: number; threadNumber?: number }): RoomPin => {
  const threadNumber = over.threadNumber ?? 42;
  return {
    id,
    kind: "MESSAGE",
    threadNumber,
    threadTitle: "Disk full on db-3",
    threadStatus: "triage",
    threadClosed: false,
    message: makeMessage({ id: over.messageId ?? 900 + id, threadNumber, authorName: over.author ?? "Cy Author", body: over.body ?? "Restart the **replica** first" }),
    pinnedById: 5,
    pinnedByName: "Ann Owner",
    pinnedAt: SOME_TIME,
    ...over,
  };
};
const THREAD_PIN: RoomPin = { ...pin(1, {}), kind: "THREAD", message: null };
const page = (items: RoomPin[]) => ({ items, page: 1, pageSize: 50, total: items.length });

function Where() {
  const { pathname, search } = useLocation();
  return <output aria-label="where">{pathname + search}</output>;
}

describe("ThreadPinStrip", () => {
  let mockFetch: ReturnType<typeof vi.fn>;
  let pins: RoomPin[];
  const puts = () => mockFetch.mock.calls.filter(([, init]) => (init as RequestInit | undefined)?.method === "PUT") as [string, RequestInit][];

  function serve(put?: () => Response) {
    mockFetch.mockImplementation((url: string, init?: RequestInit) => {
      if (init?.method === "PUT") return Promise.resolve(put ? put() : new Response(null, { status: 204 }));
      if (url.startsWith("/api/v1/rooms/12/pins?")) return Promise.resolve(jsonResponse(200, page(pins)));
      return Promise.resolve(jsonResponse(404, { title: "Not found", status: 404 }));
    });
  }
  /** The pane holds the expanded state; this stands in for it (`startOpen`), so the strip is tested as the controlled part it is. */
  function Harness({ room, onShow, focusPane, startOpen }: { room: RoomResponse; onShow: (id: number) => void; focusPane: () => void; startOpen: boolean }) {
    const [open, setOpen] = useState(startOpen);
    return <ThreadPinStrip room={room} threadNumber={42} open={open} onOpenChange={setOpen} onShow={onShow} focusPane={focusPane} />;
  }
  const renderStrip = (
    room: RoomResponse = makeRoom(),
    route = "/rooms/12/threads/42",
    queryClient?: QueryClient,
    { onShow = vi.fn(), focusPane = vi.fn(), startOpen = false }: { onShow?: (id: number) => void; focusPane?: () => void; startOpen?: boolean } = {},
  ) =>
    renderWithProviders(
      <>
        <Harness room={room} onShow={onShow} focusPane={focusPane} startOpen={startOpen} />
        <Where />
      </>,
      { route, queryClient },
    );

  beforeEach(() => {
    mockFetch = vi.fn();
    pins = [];
    vi.stubGlobal("fetch", mockFetch);
    localStorage.setItem("adder.auth.token", "fake-token");
  });
  afterEach(() => {
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  test("a thread with no pinned message has no strip: not while loading, not for a thread pin, not for another thread's message pin", async () => {
    pins = [THREAD_PIN, pin(2, { threadNumber: 7 })];
    serve();
    renderStrip();
    await waitFor(() => expect(mockFetch.mock.calls.some(([url]) => String(url).startsWith("/api/v1/rooms/12/pins?"))).toBe(true));
    await act(async () => {});
    expect(screen.queryByRole("region", { name: "Pins in this thread" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button")).not.toBeInTheDocument();
  });

  test("collapsed it is one line: the newest pin's first line, plain text, and how many more there are", async () => {
    pins = [pin(3, { body: "## Restart <@5> first\nthen the primary", author: "Newest" }), pin(2, { body: "older" }), pin(1, { body: "oldest" })];
    pins[0].message!.mentions = [{ userId: 5, name: "Ann Owner" }];
    serve();
    renderStrip();
    const button = await screen.findByRole("button", { name: /^Pinned/ });
    expect(button).toHaveAccessibleName("Pinned: Restart @Ann Owner first (+2)");
    expect(button).toHaveAttribute("aria-expanded", "false");
    expect(screen.getByRole("region", { name: "Pins in this thread" })).toContainElement(button);
    // Closed, the pins are not in the page (and no card, so no second "Unpin" or link per pin).
    expect(screen.queryByRole("link", { name: /^Show in thread/ })).not.toBeInTheDocument();
  });

  test("one pin has no '(+n)'", async () => {
    pins = [pin(1, { body: "Only one" })];
    serve();
    renderStrip();
    expect(await screen.findByRole("button", { name: "Pinned: Only one" })).toBeInTheDocument();
  });

  test("pressing it expands the pins in place, newest first, each with who, when, a two-line excerpt, who pinned it, and its two actions", async () => {
    const user = userEvent.setup();
    pins = [pin(2, { body: "Second\nline two", author: "Di Other" }), pin(1, { body: "First pin", author: "Cy Author" })];
    serve();
    renderStrip();
    await user.click(await screen.findByRole("button", { name: /^Pinned/ }));
    const expanded = screen.getByRole("button", { name: "Pinned in this thread (2)" });
    expect(expanded).toHaveAttribute("aria-expanded", "true");
    const items = screen.getAllByRole("listitem");
    expect(items).toHaveLength(2);
    expect(items[0]).toHaveTextContent(`Di Other · ${WHEN}`);
    expect(items[0]).toHaveTextContent("Second line two");
    expect(items[0]).toHaveTextContent(`Pinned by Ann Owner on ${WHEN}`);
    expect(items[1]).toHaveTextContent("First pin");
    // The pin shape is decoration; the words are the mark.
    expect(items[0].querySelector("svg")).toHaveAttribute("aria-hidden", "true");
    expect(within(items[0]).getByRole("link", { name: `Show in thread: message by Di Other on ${WHEN}` })).toBeInTheDocument();
    expect(within(items[0]).getByRole("button", { name: `Unpin message by Di Other on ${WHEN}` })).toBeInTheDocument();
    expect(expanded).toHaveAttribute("aria-controls", expect.stringMatching(/./));
    expect(document.getElementById(expanded.getAttribute("aria-controls")!)).toContainElement(items[0]);
    await user.click(expanded);
    expect(screen.queryByRole("listitem")).not.toBeInTheDocument();
  });

  test("Escape folds an expanded list away and keeps the focus on the strip's button", async () => {
    const user = userEvent.setup();
    pins = [pin(1, { body: "Only one" })];
    serve();
    renderStrip(makeRoom(), "/rooms/12/threads/42", undefined, { startOpen: true });
    const link = await screen.findByRole("link", { name: /^Show in thread/ });
    link.focus();
    await user.keyboard("{Escape}");
    const button = await screen.findByRole("button", { name: "Pinned: Only one" });
    expect(button).toHaveAttribute("aria-expanded", "false");
    expect(button).toHaveFocus();
    // Escape on a folded strip does nothing (it is not swallowed from the page).
    await user.keyboard("{Escape}");
    expect(button).toHaveAttribute("aria-expanded", "false");
  });

  test("'Show in thread' is the jump link (?message=) and tells the conversation; the focus view stays on", async () => {
    const user = userEvent.setup();
    pins = [pin(1, { body: "Only one", messageId: 991 })];
    serve();
    const onShow = vi.fn();
    renderStrip(makeRoom(), "/rooms/12/threads/42?scope=closed&focus=1&message=5", undefined, { onShow, startOpen: true });
    const link = await screen.findByRole("link", { name: /^Show in thread/ });
    expect(link).toHaveAttribute("href", "/rooms/12/threads/42?message=991&scope=closed&focus=1");
    await user.click(link);
    expect(onShow).toHaveBeenCalledExactlyOnceWith(991);
    expect(screen.getByLabelText("where")).toHaveTextContent("/rooms/12/threads/42?message=991&scope=closed&focus=1");
  });

  test("a member unpins the last pin from the strip: the message PUT, the pins are read again, the strip goes and the focus moves to the pane", async () => {
    const user = userEvent.setup();
    pins = [pin(1, { body: "Only one", messageId: 991 })];
    serve(() => {
      pins = [];
      return new Response(null, { status: 204 });
    });
    const focusPane = vi.fn();
    renderStrip(makeRoom(), "/rooms/12/threads/42", undefined, { focusPane, startOpen: true });
    await user.click(await screen.findByRole("button", { name: /^Unpin / }));
    await waitFor(() => expect(screen.queryByRole("region", { name: "Pins in this thread" })).not.toBeInTheDocument());
    expect([puts()[0][0], JSON.parse(puts()[0][1].body as string)]).toEqual(["/api/v1/rooms/12/messages/991/pin", { pinned: false }]);
    expect(focusPane).toHaveBeenCalledTimes(1);
  });

  test("unpinning one of several puts the focus on the strip's button, not on the page", async () => {
    const user = userEvent.setup();
    pins = [pin(2, { body: "Second", author: "Di Other" }), pin(1, { body: "First", author: "Cy Author" })];
    serve(() => {
      pins = pins.slice(1);
      return new Response(null, { status: 204 });
    });
    const focusPane = vi.fn();
    renderStrip(makeRoom(), "/rooms/12/threads/42", undefined, { focusPane, startOpen: true });
    await user.click(await screen.findByRole("button", { name: `Unpin message by Di Other on ${WHEN}` }));
    const button = await screen.findByRole("button", { name: "Pinned in this thread (1)" });
    await waitFor(() => expect(button).toHaveFocus());
    expect(focusPane).not.toHaveBeenCalled();
  });

  test("a refused unpin says so inside the strip, in words", async () => {
    const user = userEvent.setup();
    pins = [pin(1, { body: "Only one" })];
    serve(() => jsonResponse(429, { title: "Slow", status: 429 }));
    const focusPane = vi.fn();
    renderStrip(makeRoom(), "/rooms/12/threads/42", undefined, { focusPane, startOpen: true });
    await user.click(await screen.findByRole("button", { name: /^Unpin / }));
    expect(await screen.findByText(/changing things too quickly/i)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /^Unpin / })).toBeInTheDocument();
    // Nothing was removed: the focus is not moved anywhere.
    expect(focusPane).not.toHaveBeenCalled();
  });

  test("an ADMIN outside the room and an archived room read the pins and have no Unpin", async () => {
    pins = [pin(1, { body: "Only one" })];
    serve();
    const outside = renderStrip(makeRoom({ role: null }), "/rooms/12/threads/42", undefined, { startOpen: true });
    await screen.findByRole("link", { name: /^Show in thread/ });
    expect(screen.queryByRole("button", { name: /^Unpin / })).not.toBeInTheDocument();
    outside.unmount();
    renderStrip(makeRoom({ archived: true }), "/rooms/12/threads/42", undefined, { startOpen: true });
    await screen.findByRole("link", { name: /^Show in thread/ });
    expect(screen.queryByRole("button", { name: /^Unpin / })).not.toBeInTheDocument();
  });

  test("it follows the room's pins live: a pin hint appears and removes it with no reload", async () => {
    pins = [];
    serve();
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    renderStrip(makeRoom(), "/rooms/12/threads/42", queryClient);
    await waitFor(() => expect(mockFetch.mock.calls.some(([url]) => String(url).startsWith("/api/v1/rooms/12/pins?"))).toBe(true));
    expect(screen.queryByRole("button")).not.toBeInTheDocument();
    const hint = { type: "event", kind: "room.pin_changed", roomId: 12 } as const;
    pins = [pin(1, { body: "Pinned elsewhere" })];
    await act(async () => {
      for (const queryKey of keysForFrame(hint)) await queryClient.invalidateQueries({ queryKey });
    });
    expect(await screen.findByRole("button", { name: "Pinned: Pinned elsewhere" })).toBeInTheDocument();
    pins = [];
    await act(async () => {
      for (const queryKey of keysForFrame(hint)) await queryClient.invalidateQueries({ queryKey });
    });
    await waitFor(() => expect(screen.queryByRole("button")).not.toBeInTheDocument());
  });
});
