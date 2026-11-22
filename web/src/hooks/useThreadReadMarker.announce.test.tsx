import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { act, renderHook } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import type { ReactNode } from "react";
import { READ_MARKER_INTERVAL_MS, useThreadReadMarker } from "./useThreadReadMarker";
import { ChannelStub } from "../test/broadcast";
import { lookingAt } from "../utils/lookingAt";
import { openSeenSync } from "../utils/seenSync";

// A notice the pane acknowledged (the reader was looking at the thread, so the bell never held it) still settles on the server: the person's
// other tabs must hear of it (1.2 D9), though no cached bell row names it.
describe("useThreadReadMarker: telling the other tabs", () => {
  let mockFetch: ReturnType<typeof vi.fn>;
  let close: () => void;
  const announcements = () => ChannelStub.open[0].posted;

  beforeEach(() => {
    vi.useFakeTimers();
    mockFetch = vi.fn(() => Promise.resolve(new Response(null, { status: 204 })));
    vi.stubGlobal("fetch", mockFetch);
    localStorage.setItem("adder.auth.token", "fake-token");
    close = openSeenSync(7, () => undefined, (name) => new ChannelStub(name));
  });
  afterEach(() => {
    close();
    ChannelStub.reset();
    vi.useRealTimers();
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  function setup(newestId = 10) {
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const wrapper = ({ children }: { children: ReactNode }) => <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>;
    return renderHook((props: { newestId: number }) => useThreadReadMarker({ roomId: 12, threadNumber: 42, atEnd: true, enabled: true, ...props }), {
      wrapper,
      initialProps: { newestId },
    });
  }
  const settle = (ms = 0) => act(() => vi.advanceTimersByTimeAsync(ms));

  test("an ordinary marker with no notice in the bell's cache announces nothing", async () => {
    setup();
    await settle();
    expect(announcements()).toEqual([]);
  });

  test("a thread-level notice the pane acknowledged announces once the marker landed", async () => {
    setup();
    await settle();
    act(() => lookingAt(12, 42)?.acknowledge(undefined));
    await settle(READ_MARKER_INTERVAL_MS);
    expect(announcements()).toEqual([{ v: 1, userId: 7 }]);
  });

  test("a reply the pane acknowledged announces when the marker reaches it, not before", async () => {
    const { rerender } = setup();
    await settle();
    act(() => lookingAt(12, 42)?.acknowledge(11));
    await settle(READ_MARKER_INTERVAL_MS);
    expect(announcements()).toEqual([]);
    rerender({ newestId: 11 });
    await settle(READ_MARKER_INTERVAL_MS);
    expect(announcements()).toEqual([{ v: 1, userId: 7 }]);
  });

  test("a marker that failed announces nothing", async () => {
    setup();
    await settle();
    mockFetch.mockImplementation(() => Promise.resolve(new Response(null, { status: 500 })));
    act(() => lookingAt(12, 42)?.acknowledge(undefined));
    await settle(READ_MARKER_INTERVAL_MS);
    expect(announcements()).toEqual([]);
  });
});
