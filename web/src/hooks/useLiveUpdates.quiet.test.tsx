// The hidden tab through the hook (1.2 D9): the real `bindBrowserSignals` on a stubbed `document.visibilityState`, a fake clock and socket. The
// state machine itself is `liveClient.quiet.test.ts`; here it is the wiring: the status, the bell-only invalidator and the wake-up backfill.
import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { act, renderHook } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import type { ReactNode } from "react";
import { FakeClock, lastSocket, makeLive, SocketStub } from "../test/socket";
import { resetTabVisibility, setTabVisible } from "../test/visibility";
import { useLiveUpdates } from "./useLiveUpdates";

const MINUTE = 60_000;

async function settle() {
  for (let i = 0; i < 6; i++) await Promise.resolve();
}

function setup() {
  const clock = new FakeClock();
  clock.randomValue = 0;
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const invalidate = vi.spyOn(queryClient, "invalidateQueries").mockResolvedValue();
  vi.spyOn(queryClient, "isFetching").mockReturnValue(0);
  const overrides = { clock, mintTicket: () => Promise.resolve({ ticket: "t".repeat(43) }), socketFactory: (url: string) => new SocketStub(url) };
  const wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={["/rooms/12"]}>{children}</MemoryRouter>
    </QueryClientProvider>
  );
  const view = renderHook(() => useLiveUpdates([12], overrides), { wrapper });
  const flushed = () => invalidate.mock.calls.map(([filters]) => JSON.stringify(filters?.queryKey));
  return { ...view, clock, flushed };
}

/** Hidden for five minutes with every ping answered (a silent socket would be a failure at the next tick). */
function hideAndGoQuiet(clock: FakeClock) {
  setTabVisible(false);
  for (let i = 0; i < 5; i++) {
    act(() => clock.advanceBy(MINUTE));
    act(() => lastSocket().receive({ type: "pong" }));
  }
}

describe("useLiveUpdates: the hidden tab", () => {
  beforeEach(() => {
    SocketStub.reset();
    localStorage.setItem("adder.auth.token", "fake-token");
  });
  afterEach(() => {
    resetTabVisibility();
    SocketStub.reset();
    localStorage.clear();
  });

  test("five minutes hidden: the status is quiet, the room is dropped, and a room hint re-reads nothing but a notice re-reads the bell", async () => {
    const t = setup();
    await act(settle);
    act(() => makeLive(lastSocket()));
    expect(t.result.current).toBe("live");
    hideAndGoQuiet(t.clock);
    expect(t.result.current).toBe("quiet");
    expect(lastSocket().frames().filter((frame) => frame.type === "unsubscribe")).toEqual([{ type: "unsubscribe", roomIds: [12] }]);
    const before = t.flushed().length;
    act(() => lastSocket().receive({ type: "event", kind: "message.posted", roomId: 12, threadNumber: 3, messageId: 9 }));
    act(() => t.clock.advanceBy(5_000));
    expect(t.flushed()).toHaveLength(before);
    act(() => lastSocket().receive({ type: "event", kind: "notification.created", roomId: 12, threadNumber: 3, messageId: 9 }));
    act(() => t.clock.advanceBy(5_000));
    expect(t.flushed().slice(before)).toEqual([JSON.stringify(["notifications"])]);
  });

  test("shown again: live at once, the room subscribed back and every room query re-read (the backfill)", async () => {
    const t = setup();
    await act(settle);
    act(() => makeLive(lastSocket()));
    hideAndGoQuiet(t.clock);
    const before = t.flushed().length;
    setTabVisible(true);
    expect(t.result.current).toBe("live");
    expect(lastSocket().frames().filter((frame) => frame.type === "subscribe")).toHaveLength(2);
    act(() => t.clock.advanceBy(2_000));
    expect(t.flushed().slice(before)).toEqual(expect.arrayContaining([JSON.stringify(["rooms"]), JSON.stringify(["notifications"])]));
  });
});
