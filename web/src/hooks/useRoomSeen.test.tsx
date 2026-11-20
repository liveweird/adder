import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { act, renderHook } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import type { ReactNode } from "react";
import { ROOM_SEEN_INTERVAL_MS, useRoomSeen } from "./useRoomSeen";
import { jsonResponse } from "../test/http";
import { resetTabVisibility, setTabVisible } from "../test/visibility";
import { roomKeys } from "../utils/roomKeys";

const RAIL_KEY = [...roomKeys.rail, "active"];
const rail = (...rooms: { id: number; unread: boolean }[]) => ({ items: rooms, page: 1, pageSize: 100, total: rooms.length });

describe("useRoomSeen", () => {
  let mockFetch: ReturnType<typeof vi.fn>;
  let queryClient: QueryClient;
  const seenCalls = () => mockFetch.mock.calls.filter(([url]) => String(url).endsWith("/seen")) as [string, RequestInit][];
  const seenRooms = () => seenCalls().map(([url]) => /rooms\/(\d+)\/seen/.exec(String(url))?.[1]);
  const settle = (ms = 0) => act(() => vi.advanceTimersByTimeAsync(ms));

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

  function setup(roomId: number | null = 12, member = true) {
    const wrapper = ({ children }: { children: ReactNode }) => <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>;
    return renderHook((props: { roomId: number | null; member: boolean }) => useRoomSeen(props.roomId, props.member), { wrapper, initialProps: { roomId, member } });
  }

  test("a member's arrival posts the room as seen once, however often the page renders", async () => {
    const { rerender } = setup();
    await settle();
    expect(seenCalls().map(([url, init]) => [url, init.method])).toEqual([["/api/v1/rooms/12/seen", "POST"]]);
    rerender({ roomId: 12, member: true });
    await settle(ROOM_SEEN_INTERVAL_MS * 3);
    expect(seenCalls()).toHaveLength(1);
  });

  test("it asks nothing of the rail itself: the shell's rooms query is only watched", async () => {
    setup();
    await settle();
    expect(mockFetch.mock.calls.every(([url]) => String(url).endsWith("/seen"))).toBe(true);
  });

  test("it posts again when the rail says the room is new, but at most once per 5 s, and the success clears the flag", async () => {
    queryClient.setQueryData(RAIL_KEY, rail({ id: 12, unread: false }));
    setup();
    await settle();
    expect(seenCalls()).toHaveLength(1);
    // Somebody posted: the rail's next read flags the room again.
    act(() => queryClient.setQueryData(RAIL_KEY, rail({ id: 12, unread: true })));
    await settle(ROOM_SEEN_INTERVAL_MS - 1);
    expect(seenCalls()).toHaveLength(1);
    await settle(1);
    expect(seenCalls()).toHaveLength(2);
    expect(queryClient.getQueryData(RAIL_KEY)).toMatchObject({ items: [{ id: 12, unread: false }] });
    await settle(ROOM_SEEN_INTERVAL_MS * 3);
    expect(seenCalls()).toHaveLength(2);
  });

  test("a flag that clears while it waits cancels the post", async () => {
    queryClient.setQueryData(RAIL_KEY, rail({ id: 12, unread: false }));
    setup();
    await settle();
    act(() => queryClient.setQueryData(RAIL_KEY, rail({ id: 12, unread: true })));
    await settle(1000);
    act(() => queryClient.setQueryData(RAIL_KEY, rail({ id: 12, unread: false })));
    await settle(ROOM_SEEN_INTERVAL_MS * 2);
    expect(seenCalls()).toHaveLength(1);
  });

  test("a flag on another room is none of this room's business", async () => {
    queryClient.setQueryData(RAIL_KEY, rail({ id: 12, unread: false }, { id: 13, unread: true }));
    setup();
    await settle(ROOM_SEEN_INTERVAL_MS * 2);
    expect(seenRooms()).toEqual(["12"]);
  });

  test("nothing is posted while the tab is hidden; the arrival goes out when it is shown", async () => {
    setTabVisible(false);
    setup();
    await settle(ROOM_SEEN_INTERVAL_MS * 2);
    expect(seenCalls()).toHaveLength(0);
    setTabVisible(true);
    await settle();
    expect(seenRooms()).toEqual(["12"]);
  });

  test("an administrator who is not a member, and a page that names no room, post nothing", async () => {
    setup(12, false);
    setup(null, true);
    await settle(ROOM_SEEN_INTERVAL_MS * 2);
    expect(mockFetch).not.toHaveBeenCalled();
  });

  test("moving to another room is a new arrival", async () => {
    const { rerender } = setup();
    await settle();
    rerender({ roomId: 13, member: true });
    await settle();
    expect(seenRooms()).toEqual(["12", "13"]);
  });

  test("a failure is silent and leaves the next arrival to try again", async () => {
    mockFetch.mockImplementationOnce(() => Promise.resolve(jsonResponse(500, { title: "Boom", status: 500 })));
    const { rerender } = setup();
    await settle(ROOM_SEEN_INTERVAL_MS * 2);
    expect(seenCalls()).toHaveLength(1);
    rerender({ roomId: 13, member: true });
    await settle();
    expect(seenRooms()).toEqual(["12", "13"]);
  });
});
