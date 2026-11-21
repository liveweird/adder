import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { act, renderHook } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import type { ReactNode } from "react";
import { useNotificationRetryAfter } from "./useNotificationRetryAfter";
import { ApiError } from "../api/http";

describe("useNotificationRetryAfter", () => {
  let queryClient: QueryClient;
  let invalidate: ReturnType<typeof vi.spyOn>;
  const wrapper = ({ children }: { children: ReactNode }) => <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>;
  const refused = (retryAfterMs: number | null = 1_000) => new ApiError(429, { status: 429 }, retryAfterMs);
  const reads = () => invalidate.mock.calls.length;

  beforeEach(() => {
    vi.useFakeTimers();
    queryClient = new QueryClient();
    invalidate = vi.spyOn(queryClient, "invalidateQueries").mockResolvedValue();
  });
  afterEach(() => {
    vi.useRealTimers();
    vi.restoreAllMocks();
  });

  test("a refusal re-reads once, after Retry-After plus a random spread of up to 2 s", () => {
    vi.spyOn(crypto, "getRandomValues").mockImplementation(((array: Uint32Array) => {
      array[0] = 0x80000000; // half of the range: a 1 s spread
      return array;
    }) as typeof crypto.getRandomValues);
    renderHook(() => useNotificationRetryAfter(refused(3_000)), { wrapper });
    act(() => vi.advanceTimersByTime(3_999));
    expect(reads()).toBe(0);
    act(() => vi.advanceTimersByTime(1));
    expect(reads()).toBe(1);
  });

  test("refusals in a row back off (doubling, up to x8) and a read that was not refused starts over", () => {
    vi.spyOn(crypto, "getRandomValues").mockImplementation(((array: Uint32Array) => {
      array[0] = 0;
      return array;
    }) as typeof crypto.getRandomValues);
    const { rerender } = renderHook(({ error }: { error: unknown }) => useNotificationRetryAfter(error), {
      wrapper,
      initialProps: { error: refused() as unknown },
    });
    const waits: number[] = [];
    for (let attempt = 0; attempt < 6; attempt += 1) {
      const before = reads();
      let waited = 0;
      while (reads() === before && waited < 20_000) {
        act(() => vi.advanceTimersByTime(500));
        waited += 500;
      }
      waits.push(waited);
      rerender({ error: refused() }); // refused again: a new error object schedules the next one
    }
    expect(waits).toEqual([1_000, 2_000, 4_000, 8_000, 8_000, 8_000]);
    act(() => vi.advanceTimersByTime(8_000)); // the sixth refusal's re-read fires
    rerender({ error: null });
    rerender({ error: refused() });
    const before = reads();
    act(() => vi.advanceTimersByTime(1_000));
    expect(reads()).toBe(before + 1);
  });

  test("an error that is not a refusal schedules nothing", () => {
    renderHook(() => useNotificationRetryAfter(new ApiError(500, {})), { wrapper });
    act(() => vi.advanceTimersByTime(60_000));
    expect(reads()).toBe(0);
  });
});
