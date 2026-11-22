import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { act, renderHook } from "@testing-library/react";
import { useTabTitleCount } from "./useTabTitleCount";
import { getTitleCount, setTitleCount } from "../utils/titleCount";
import { LIVE_DOWN_GRACE_MS, type LiveStatus } from "../utils/liveClient";

// The title carries a number only while it can be kept current (1.2 D8, A17).
describe("useTabTitleCount", () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => {
    vi.useRealTimers();
    setTitleCount(0);
  });

  test.each<[LiveStatus, number]>([
    ["live", 3],
    ["quiet", 3],
    ["connecting", 0],
    ["paused", 0],
    ["idle", 0],
    ["stopped", 0],
  ])("status %s holds %i", (status, expected) => {
    renderHook(() => useTabTitleCount(3, status));
    expect(getTitleCount()).toBe(expected);
  });

  test("it follows the total, and a socket that goes down keeps the number for the 5 s grace, then drops it, and back", () => {
    const { rerender } = renderHook(({ total, status }) => useTabTitleCount(total, status), { initialProps: { total: 2, status: "live" as LiveStatus } });
    expect(getTitleCount()).toBe(2);
    rerender({ total: 5, status: "live" });
    expect(getTitleCount()).toBe(5);
    rerender({ total: 5, status: "down" });
    act(() => vi.advanceTimersByTime(LIVE_DOWN_GRACE_MS - 1));
    expect(getTitleCount(), "a re-mint or a restart does not blink the title").toBe(5);
    act(() => vi.advanceTimersByTime(1));
    expect(getTitleCount()).toBe(0);
    rerender({ total: 5, status: "live" });
    expect(getTitleCount()).toBe(5);
  });

  test("back live inside the grace, the number never left; going to another status clears it at once", () => {
    const { rerender } = renderHook(({ status }) => useTabTitleCount(4, status), { initialProps: { status: "live" as LiveStatus } });
    rerender({ status: "down" });
    act(() => vi.advanceTimersByTime(LIVE_DOWN_GRACE_MS - 1));
    rerender({ status: "live" });
    act(() => vi.advanceTimersByTime(LIVE_DOWN_GRACE_MS * 2));
    expect(getTitleCount()).toBe(4);
    rerender({ status: "down" });
    rerender({ status: "idle" });
    expect(getTitleCount(), "the 8-hour close is no blink").toBe(0);
  });

  test("unmounting (sign-out) clears it", () => {
    const { unmount } = renderHook(() => useTabTitleCount(7, "live"));
    expect(getTitleCount()).toBe(7);
    unmount();
    expect(getTitleCount()).toBe(0);
  });
});
