import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { act, renderHook } from "@testing-library/react";
import { MemoryRouter, useNavigate } from "react-router-dom";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { useEffect, type ReactNode } from "react";
import { clearSession, getSessionSnapshot } from "../api/session";
import { FakeClock, lastSocket, makeLive, SocketStub } from "../test/socket";
import { useLiveUpdates } from "./useLiveUpdates";

const TICKET = "t".repeat(43);
const IDENTITY_KEY = "adder.auth.sessionIdentity";

/** The router's `navigate`, handed out of the render tree so a test can move the route. */
const router = { navigate: (() => {}) as ReturnType<typeof useNavigate> };

function CaptureNavigate() {
  const navigate = useNavigate();
  useEffect(() => {
    router.navigate = navigate;
  }, [navigate]);
  return null;
}

async function settle() {
  for (let i = 0; i < 6; i++) await Promise.resolve();
}

function setup(route = "/", rail: number[] = [], firstMintFails = false) {
  const clock = new FakeClock();
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const invalidate = vi.spyOn(queryClient, "invalidateQueries").mockResolvedValue();
  const mintTicket = vi.fn(() => Promise.resolve({ ticket: TICKET }));
  if (firstMintFails) mintTicket.mockRejectedValueOnce(new TypeError("Failed to fetch"));
  const overrides = { clock, mintTicket, socketFactory: (url: string) => new SocketStub(url) };
  const wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[route]}>
        <CaptureNavigate />
        {children}
      </MemoryRouter>
    </QueryClientProvider>
  );
  const view = renderHook(({ rooms }: { rooms: number[] }) => useLiveUpdates(rooms, overrides), { wrapper, initialProps: { rooms: rail } });
  return { ...view, clock, queryClient, invalidate, mintTicket };
}

const subscribes = (socket: SocketStub) => socket.frames().filter((frame) => frame.type === "subscribe" || frame.type === "unsubscribe");

describe("useLiveUpdates", () => {
  beforeEach(() => {
    SocketStub.reset();
    localStorage.setItem("adder.auth.token", "fake-token");
  });
  afterEach(() => {
    SocketStub.reset();
    localStorage.clear();
    router.navigate = () => {};
  });

  test("opens one socket, reports its status and subscribes to the open room first, then the rail's", async () => {
    const t = setup("/rooms/12/threads/5", [3, 12, 4]);
    expect(t.result.current).toBe("connecting");
    await act(settle);
    const socket = lastSocket();
    expect(SocketStub.instances).toHaveLength(1);
    expect(socket.url).toBe("ws://localhost:3000/api/v1/live");
    act(() => makeLive(socket));
    expect(t.result.current).toBe("live");
    expect(subscribes(socket)).toEqual([{ type: "subscribe", roomIds: [12, 3, 4] }]);
  });

  test("a route that is not a room contributes nothing, and the set follows the rail and the route as they change", async () => {
    const t = setup("/rooms/new", [3]);
    await act(settle);
    const socket = lastSocket();
    act(() => makeLive(socket));
    expect(subscribes(socket)).toEqual([{ type: "subscribe", roomIds: [3] }]);
    t.rerender({ rooms: [3, 4] });
    act(() => t.clock.advanceBy(100));
    expect(subscribes(socket).slice(1)).toEqual([{ type: "subscribe", roomIds: [4] }]);
    act(() => router.navigate("/rooms/9/members"));
    act(() => t.clock.advanceBy(100));
    expect(subscribes(socket).slice(2)).toEqual([{ type: "subscribe", roomIds: [9] }]);
    act(() => router.navigate("/rooms/abc"));
    act(() => t.clock.advanceBy(100));
    expect(subscribes(socket).slice(3)).toEqual([{ type: "unsubscribe", roomIds: [9] }]);
  });

  test("a room the server refused is not asked for again until the route changes", async () => {
    const t = setup("/rooms/40", [3]);
    await act(settle);
    const socket = lastSocket();
    act(() => makeLive(socket));
    act(() => socket.receive({ type: "subscribed", roomIds: [3], refused: [40], capped: [] }));
    t.rerender({ rooms: [3, 5] });
    act(() => t.clock.advanceBy(100));
    expect(subscribes(socket).slice(1)).toEqual([{ type: "subscribe", roomIds: [5] }]);
    // Another thread of the same room keeps the refusal; another room forgets it.
    act(() => router.navigate("/rooms/40/threads/2"));
    act(() => t.clock.advanceBy(100));
    expect(subscribes(socket)).toHaveLength(2);
    act(() => router.navigate("/rooms/6"));
    act(() => router.navigate("/rooms/40"));
    act(() => t.clock.advanceBy(100));
    expect(subscribes(socket).filter((frame) => JSON.stringify(frame).includes("40"))).toHaveLength(2);
  });

  test("hints become query invalidations on the hook's own clock", async () => {
    const t = setup("/rooms/12", []);
    await act(settle);
    const socket = lastSocket();
    act(() => makeLive(socket));
    act(() => socket.receive({ type: "event", kind: "message.edited", roomId: 12, threadNumber: 7, messageId: 1 }));
    expect(t.invalidate).not.toHaveBeenCalled();
    act(() => t.clock.advanceBy(300));
    expect(t.invalidate).toHaveBeenCalledWith({ queryKey: ["rooms", "threads", 12, "messages", 7] });
  });

  test("a token rotation within one identity keeps the socket", async () => {
    const t = setup("/", []);
    await act(settle);
    const socket = lastSocket();
    act(() => makeLive(socket));
    localStorage.setItem("adder.auth.token", "rotated-token");
    act(() => {
      window.dispatchEvent(new StorageEvent("storage", { key: "adder.auth.token" }));
    });
    await act(settle);
    expect(socket.close).not.toHaveBeenCalled();
    expect(SocketStub.instances).toHaveLength(1);
    expect(t.result.current).toBe("live");
  });

  test("a different identity closes the socket and opens a new one for the new account", async () => {
    const t = setup("/", [3]);
    await act(settle);
    const first = lastSocket();
    act(() => makeLive(first));
    localStorage.setItem(IDENTITY_KEY, "another-account");
    act(() => {
      window.dispatchEvent(new StorageEvent("storage", { key: IDENTITY_KEY }));
    });
    expect(first.close).toHaveBeenCalledWith(1000);
    await act(settle);
    expect(SocketStub.instances).toHaveLength(2);
    expect(t.mintTicket).toHaveBeenCalledTimes(2);
    const second = lastSocket();
    act(() => makeLive(second));
    expect(subscribes(second)).toEqual([{ type: "subscribe", roomIds: [3] }]);
  });

  test("signing out closes the socket (1000) and ends the status", async () => {
    const t = setup("/", [3]);
    await act(settle);
    const socket = lastSocket();
    act(() => makeLive(socket));
    act(() => clearSession());
    expect(socket.close).toHaveBeenCalledWith(1000);
    expect(t.result.current).toBe("stopped");
    await act(settle);
    act(() => t.clock.advanceBy(10 * 60_000));
    expect(SocketStub.instances).toHaveLength(1);
    expect(getSessionSnapshot().identity).toBeNull();
  });

  test("unmounting stops the client: the socket closes and nothing is scheduled", async () => {
    const t = setup("/", []);
    await act(settle);
    const socket = lastSocket();
    act(() => makeLive(socket));
    t.unmount();
    expect(socket.close).toHaveBeenCalledWith(1000);
    expect(t.clock.pendingDelays()).toEqual([]);
  });

  test("a failed mint reports down and retries on the backoff", async () => {
    const t = setup("/", [], true);
    await act(settle);
    expect(t.result.current).toBe("down");
    expect(SocketStub.instances).toHaveLength(0);
    act(() => t.clock.advanceBy(1000));
    await act(settle);
    expect(SocketStub.instances).toHaveLength(1);
    act(() => makeLive(lastSocket()));
    expect(t.result.current).toBe("live");
  });
});
