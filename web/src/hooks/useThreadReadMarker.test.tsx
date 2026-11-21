import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { act, renderHook } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import type { ReactNode } from "react";
import { READ_MARKER_INTERVAL_MS, useThreadReadMarker } from "./useThreadReadMarker";
import { jsonResponse } from "../test/http";
import { resetTabVisibility, setTabVisible } from "../test/visibility";
import { lookingAt } from "../utils/lookingAt";
import { notificationKeys } from "../utils/notificationKeys";
import { roomKeys } from "../utils/roomKeys";

type Props = { newestId: number | undefined; atEnd: boolean; enabled: boolean; threadNumber?: number };
const START: Props = { newestId: 10, atEnd: true, enabled: true };

describe("useThreadReadMarker", () => {
  let mockFetch: ReturnType<typeof vi.fn>;
  let queryClient: QueryClient;
  const markerCalls = () => mockFetch.mock.calls.filter(([url]) => String(url).endsWith("/read-marker")) as [string, RequestInit][];
  const sentIds = () => markerCalls().map(([, init]) => (JSON.parse(init.body as string) as { messageId: number }).messageId);
  const sentTo = () =>
    markerCalls().map(([url, init]) => `${/threads\/(\d+)\//.exec(String(url))?.[1]}:${(JSON.parse(init.body as string) as { messageId: number }).messageId}`);

  beforeEach(() => {
    vi.useFakeTimers();
    mockFetch = vi.fn(() => Promise.resolve(new Response(null, { status: 204 })));
    vi.stubGlobal("fetch", mockFetch);
    localStorage.setItem("adder.auth.token", "fake-token");
    queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  });
  afterEach(() => {
    resetTabVisibility();
    vi.useRealTimers();
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  function setup(initial: Props = START) {
    const wrapper = ({ children }: { children: ReactNode }) => <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>;
    return renderHook((props: Props) => useThreadReadMarker({ roomId: 12, ...props, threadNumber: props.threadNumber ?? 42 }), { wrapper, initialProps: initial });
  }
  const settle = (ms = 0) => act(() => vi.advanceTimersByTimeAsync(ms));

  test("the newest message is acknowledged when the reader is at the end and the tab is visible", async () => {
    setup();
    await settle();
    expect(markerCalls().map(([url, init]) => [url, init.method])).toEqual([["/api/v1/rooms/12/threads/42/read-marker", "PUT"]]);
    expect(sentIds()).toEqual([10]);
  });

  test("nothing is sent while the reader is scrolled up, and the marker goes when they reach the end", async () => {
    const { rerender } = setup({ ...START, atEnd: false });
    await settle(READ_MARKER_INTERVAL_MS * 2);
    expect(markerCalls()).toHaveLength(0);
    rerender({ ...START, atEnd: true });
    await settle();
    expect(sentIds()).toEqual([10]);
  });

  test("nothing is sent while the tab is hidden, and the marker goes when it is shown", async () => {
    setTabVisible(false);
    setup();
    await settle(READ_MARKER_INTERVAL_MS * 2);
    expect(markerCalls()).toHaveLength(0);
    setTabVisible(true);
    await settle();
    expect(sentIds()).toEqual([10]);
  });

  test("a reader who is not a member (an administrator outside the room), or a conversation not loaded yet, sends nothing", async () => {
    const { rerender } = setup({ ...START, enabled: false });
    await settle(READ_MARKER_INTERVAL_MS);
    rerender({ newestId: undefined, atEnd: true, enabled: true });
    await settle(READ_MARKER_INTERVAL_MS);
    expect(markerCalls()).toHaveLength(0);
  });

  test("a message is acknowledged once, however often the list renders", async () => {
    const { rerender } = setup();
    await settle();
    rerender({ ...START });
    rerender({ ...START, atEnd: false });
    rerender({ ...START, atEnd: true });
    await settle(READ_MARKER_INTERVAL_MS * 3);
    expect(sentIds()).toEqual([10]);
  });

  test("a newer message waits for the 2 s interval, and an older id is never sent", async () => {
    const { rerender } = setup();
    await settle();
    rerender({ ...START, newestId: 11 });
    await settle(READ_MARKER_INTERVAL_MS - 1);
    expect(sentIds()).toEqual([10]);
    await settle(1);
    expect(sentIds()).toEqual([10, 11]);
    rerender({ ...START, newestId: 9 });
    await settle(READ_MARKER_INTERVAL_MS * 2);
    expect(sentIds()).toEqual([10, 11]);
  });

  test("a message that arrives while the write is in flight is acknowledged after it, still at most every 2 s", async () => {
    let answer!: (response: Response) => void;
    mockFetch.mockImplementationOnce(() => new Promise<Response>((resolve) => (answer = resolve)));
    const { rerender } = setup();
    await settle();
    rerender({ ...START, newestId: 12 });
    await settle();
    expect(sentIds()).toEqual([10]);
    answer(new Response(null, { status: 204 }));
    await settle();
    expect(sentIds()).toEqual([10]);
    await settle(READ_MARKER_INTERVAL_MS);
    expect(sentIds()).toEqual([10, 12]);
  });

  test("the cached list row and the thread turn read without a refetch", async () => {
    queryClient.setQueryData([...roomKeys.threadList(12), 1], { items: [{ number: 42, unreadCount: 3 }], page: 1, pageSize: 50, total: 1 });
    queryClient.setQueryData(roomKeys.threadDetail(12, 42), { number: 42, unreadCount: 3, firstUnreadMessageId: 8 });
    setup();
    await settle();
    expect(queryClient.getQueryData([...roomKeys.threadList(12), 1])).toMatchObject({ items: [{ number: 42, unreadCount: 0 }] });
    expect(queryClient.getQueryData(roomKeys.threadDetail(12, 42))).toMatchObject({ unreadCount: 0, firstUnreadMessageId: null });
    expect(mockFetch.mock.calls.every(([url]) => String(url).endsWith("/read-marker"))).toBe(true);
  });

  test("the bell is re-read when it held a notice of this thread", async () => {
    queryClient.setQueryData(notificationKeys.unseen, { items: [{ id: 1, roomId: 12, threadNumber: 42 }], page: 1, pageSize: 100, total: 1 });
    setup();
    await settle();
    expect(queryClient.getQueryState(notificationKeys.unseen)?.isInvalidated).toBe(true);
  });

  test("the bell is left alone when its notices are about other threads", async () => {
    queryClient.setQueryData(notificationKeys.unseen, { items: [{ id: 1, roomId: 12, threadNumber: 7 }], page: 1, pageSize: 100, total: 1 });
    setup();
    await settle();
    expect(sentIds()).toEqual([10]);
    expect(queryClient.getQueryState(notificationKeys.unseen)?.isInvalidated).toBe(false);
  });

  test("a row that a live re-read set while the write was in flight is not zeroed by its success", async () => {
    let answer!: (response: Response) => void;
    mockFetch.mockImplementationOnce(() => new Promise<Response>((resolve) => (answer = resolve)));
    queryClient.setQueryData([...roomKeys.threadList(12), 1], { items: [{ number: 42, unreadCount: 3 }], page: 1, pageSize: 50, total: 1 });
    setup();
    await settle();
    // Message 11 arrived and the re-read set the row from the server's newer answer.
    act(() => {
      queryClient.setQueryData(roomKeys.messages(12, 42), { pages: [{ items: [{ id: 11 }, { id: 10 }], page: 1, pageSize: 50, total: 2 }], pageParams: [undefined] });
      queryClient.setQueryData([...roomKeys.threadList(12), 1], { items: [{ number: 42, unreadCount: 1 }], page: 1, pageSize: 50, total: 1 });
    });
    answer(new Response(null, { status: 204 }));
    await settle();
    expect(queryClient.getQueryData([...roomKeys.threadList(12), 1])).toMatchObject({ items: [{ number: 42, unreadCount: 1 }] });
  });

  describe("leaving with a marker held back by the interval", () => {
    test("the last message the reader saw is sent on unmount, once", async () => {
      const { rerender, unmount } = setup();
      await settle();
      rerender({ ...START, newestId: 11 });
      await settle(READ_MARKER_INTERVAL_MS - 100);
      expect(sentIds()).toEqual([10]);
      unmount();
      await settle();
      expect(sentIds()).toEqual([10, 11]);
      await settle(READ_MARKER_INTERVAL_MS * 3);
      expect(sentIds()).toEqual([10, 11]);
    });

    test("another thread flushes the old one's held marker and starts clean: nothing carries over", async () => {
      const { rerender } = setup();
      await settle();
      rerender({ ...START, newestId: 11 });
      await settle(100);
      expect(sentTo()).toEqual(["42:10"]);
      // Thread 43 has only seen message 5: the old thread's progress (10) does not hold it back, nor does its interval.
      rerender({ ...START, threadNumber: 43, newestId: 5 });
      await settle();
      expect(sentTo()).toEqual(["42:10", "42:11", "43:5"]);
    });

    test("nothing is sent for what was acknowledged, for a reader who scrolled away, or for a hidden tab", async () => {
      const first = setup();
      await settle();
      first.unmount();
      const second = setup();
      await settle();
      second.rerender({ ...START, newestId: 11, atEnd: false });
      second.unmount();
      await settle();
      expect(sentIds()).toEqual([10, 10]);
      const third = setup({ ...START, newestId: 12 });
      await settle();
      third.rerender({ ...START, newestId: 13 });
      setTabVisible(false);
      third.unmount();
      await settle();
      expect(sentIds()).toEqual([10, 10, 12]);
    });
  });

  test("a failure is silent and does not retry by itself; the next arrival tries again", async () => {
    mockFetch.mockImplementationOnce(() => Promise.resolve(jsonResponse(500, { title: "Boom", status: 500 })));
    const { rerender } = setup();
    await settle(READ_MARKER_INTERVAL_MS * 3);
    expect(sentIds()).toEqual([10]);
    rerender({ ...START, newestId: 11 });
    await settle();
    expect(sentIds()).toEqual([10, 11]);
  });

  test("while the reader is looking, the thread is registered; scrolling up, hiding the tab or leaving unregisters it", async () => {
    const { rerender, unmount } = setup();
    expect(lookingAt(12, 42)).not.toBeNull();
    rerender({ ...START, atEnd: false });
    expect(lookingAt(12, 42)).toBeNull();
    rerender({ ...START, atEnd: true });
    expect(lookingAt(12, 42)).not.toBeNull();
    act(() => setTabVisible(false));
    expect(lookingAt(12, 42)).toBeNull();
    act(() => setTabVisible(true));
    expect(lookingAt(12, 42)).not.toBeNull();
    unmount();
    expect(lookingAt(12, 42)).toBeNull();
    await settle();
  });

  test("a reader who is not a member is not looking: there is no read state to acknowledge into", () => {
    setup({ ...START, enabled: false });
    expect(lookingAt(12, 42)).toBeNull();
  });

  test("acknowledging sends the newest id once more, for a notice that is no newer message, within the interval", async () => {
    setup();
    await settle();
    expect(sentIds()).toEqual([10]);

    // A hand-over notice: no newer message, yet the pane acknowledges it (the server marks thread-level notices seen with the marker).
    act(() => lookingAt(12, 42)?.acknowledge(undefined));
    await settle();
    expect(sentIds(), "held back by the interval, not sent twice at once").toEqual([10]);
    await settle(READ_MARKER_INTERVAL_MS);
    expect(sentIds()).toEqual([10, 10]);

    // Once only: nothing is sent again until the next acknowledgement.
    await settle(READ_MARKER_INTERVAL_MS * 3);
    expect(sentIds()).toEqual([10, 10]);
  });

  test("two acknowledgements in a burst are one marker", async () => {
    setup();
    await settle(READ_MARKER_INTERVAL_MS);
    act(() => {
      lookingAt(12, 42)?.acknowledge(undefined);
      lookingAt(12, 42)?.acknowledge(undefined);
    });
    await settle(READ_MARKER_INTERVAL_MS);
    expect(sentIds()).toEqual([10, 10]);
  });

  test("the registration covers a thread-level notice and a message newer than the newest loaded one, not an older message", () => {
    setup({ ...START, newestId: 10 });
    const looking = lookingAt(12, 42);
    expect(looking?.covers(undefined), "a hand-over or status notice names no message").toBe(true);
    expect(looking?.covers(11), "the reply that is arriving").toBe(true);
    expect(looking?.covers(10), "the newest message the reader already has").toBe(false);
    expect(looking?.covers(3), "an edit that mentions the reader in an older message").toBe(false);
  });

  test("an acknowledgement the reader abandons by scrolling up re-reads the bell, and one that does not land does too", async () => {
    const invalidate = vi.spyOn(queryClient, "invalidateQueries");
    const bellReads = () => invalidate.mock.calls.filter(([filters]) => JSON.stringify(filters?.queryKey) === JSON.stringify(notificationKeys.all)).length;
    const { rerender } = setup();
    await settle();
    expect(sentIds()).toEqual([10]);
    expect(bellReads()).toBe(0);

    // Abandoned: the acknowledgement is held by the interval and the reader scrolls up before it goes.
    act(() => lookingAt(12, 42)?.acknowledge(undefined));
    rerender({ ...START, atEnd: false });
    await settle(READ_MARKER_INTERVAL_MS * 2);
    expect(sentIds()).toEqual([10]);
    expect(bellReads()).toBe(1);

    // Failed: the acknowledgement is sent and refused.
    rerender({ ...START, atEnd: true });
    await settle(READ_MARKER_INTERVAL_MS);
    mockFetch.mockImplementation(() => Promise.resolve(new Response(null, { status: 500 })));
    act(() => lookingAt(12, 42)?.acknowledge(undefined));
    await settle(READ_MARKER_INTERVAL_MS);
    expect(markerCalls().length).toBeGreaterThanOrEqual(2);
    expect(bellReads()).toBe(2);
  });

  test("a reply that was left out of the bell is acknowledged by the marker that reaches it, and owes no re-read then", async () => {
    const invalidate = vi.spyOn(queryClient, "invalidateQueries");
    const bellReads = () => invalidate.mock.calls.filter(([filters]) => JSON.stringify(filters?.queryKey) === JSON.stringify(notificationKeys.all)).length;
    const { rerender } = setup();
    await settle();
    act(() => lookingAt(12, 42)?.acknowledge(11)); // the reply arrives; the conversation has not loaded it yet
    expect(sentIds(), "nothing is forced for a message-level notice").toEqual([10]);
    rerender({ ...START, newestId: 11 });
    await settle(READ_MARKER_INTERVAL_MS);
    expect(sentIds()).toEqual([10, 11]);
    rerender({ ...START, newestId: 11, atEnd: false }); // scrolling up afterwards owes nothing
    await settle(READ_MARKER_INTERVAL_MS);
    expect(bellReads()).toBe(0);
  });

  test("a reply left out of the bell is re-read when the reader scrolls up, or leaves, before the marker reached it", async () => {
    const invalidate = vi.spyOn(queryClient, "invalidateQueries");
    const bellReads = () => invalidate.mock.calls.filter(([filters]) => JSON.stringify(filters?.queryKey) === JSON.stringify(notificationKeys.all)).length;
    const first = setup();
    await settle();
    act(() => lookingAt(12, 42)?.acknowledge(11));
    first.rerender({ ...START, atEnd: false }); // the reply is in, the reader scrolled up before the marker landed
    await settle(READ_MARKER_INTERVAL_MS);
    expect(bellReads()).toBe(1);
    first.unmount();

    const second = setup();
    await settle();
    act(() => lookingAt(12, 42)?.acknowledge(11));
    second.unmount(); // leaving with it unmet
    await settle();
    expect(bellReads()).toBe(2);
  });

  test("a marker that reaches the reply and fails re-reads the bell", async () => {
    const invalidate = vi.spyOn(queryClient, "invalidateQueries");
    const bellReads = () => invalidate.mock.calls.filter(([filters]) => JSON.stringify(filters?.queryKey) === JSON.stringify(notificationKeys.all)).length;
    const { rerender } = setup();
    await settle();
    act(() => lookingAt(12, 42)?.acknowledge(11));
    mockFetch.mockImplementation(() => Promise.resolve(new Response(null, { status: 500 })));
    rerender({ ...START, newestId: 11 });
    await settle(READ_MARKER_INTERVAL_MS);
    expect(bellReads()).toBe(1);
  });
});
