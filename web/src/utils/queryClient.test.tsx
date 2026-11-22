import { afterEach, describe, expect, test, vi } from "vitest";
import { act, render, screen } from "@testing-library/react";
import { onlineManager, QueryClientProvider, useQuery } from "@tanstack/react-query";
import { ApiError } from "../api/http";
import { PROBLEM_TYPES } from "./problems";
import { createQueryClient, mayRefetchOnReconnect, reportLiveStatus } from "./queryClient";

import { resetTabVisibility, setTabVisible } from "../test/visibility";

afterEach(() => {
  resetTabVisibility();
  reportLiveStatus("connecting");
  onlineManager.setOnline(true);
});

/** A mounted query that counts its reads; the browser going offline and online again is the trigger under test. */
async function mountedQuery() {
  const queryFn = vi.fn(() => Promise.resolve("ok"));
  const queryClient = createQueryClient();
  function Probe() {
    const query = useQuery({ queryKey: ["probe"], queryFn });
    return <span>{query.data ?? "loading"}</span>;
  }
  render(
    <QueryClientProvider client={queryClient}>
      <Probe />
    </QueryClientProvider>,
  );
  await screen.findByText("ok");
  expect(queryFn).toHaveBeenCalledTimes(1);
  return queryFn;
}

async function reconnect() {
  await act(async () => {
    onlineManager.setOnline(false);
    onlineManager.setOnline(true);
    await Promise.resolve();
  });
}

describe("the reconnect refetch follows the tab", () => {
  test("a visible tab re-reads its mounted queries when the browser is back online", async () => {
    const queryFn = await mountedQuery();
    await reconnect();
    await vi.waitFor(() => expect(queryFn).toHaveBeenCalledTimes(2));
  });

  test("a hidden tab makes no API call on online", async () => {
    const queryFn = await mountedQuery();
    setTabVisible(false);
    await reconnect();
    await new Promise((resolve) => setTimeout(resolve, 20));
    expect(queryFn).toHaveBeenCalledTimes(1);
  });

  test("an idle tab makes no API call on online", async () => {
    const queryFn = await mountedQuery();
    reportLiveStatus("idle");
    await reconnect();
    await new Promise((resolve) => setTimeout(resolve, 20));
    expect(queryFn).toHaveBeenCalledTimes(1);
  });

  test("the rule is visibility and the live state, and a quiet but visible tab or a down socket still re-reads", () => {
    expect(mayRefetchOnReconnect({ visibilityState: "visible" })).toBe(true);
    expect(mayRefetchOnReconnect({ visibilityState: "hidden" })).toBe(false);
    reportLiveStatus("down");
    expect(mayRefetchOnReconnect({ visibilityState: "visible" })).toBe(true);
    reportLiveStatus("idle");
    expect(mayRefetchOnReconnect({ visibilityState: "visible" })).toBe(false);
  });
});

describe("a read refused for load is retried once, after Retry-After", () => {
  async function refusedOnce(error: unknown) {
    vi.useFakeTimers({ toFake: ["setTimeout", "clearTimeout"] });
    const queryFn = vi.fn().mockRejectedValueOnce(error).mockResolvedValue("ok");
    const queryClient = createQueryClient();
    function Probe() {
      const query = useQuery({ queryKey: ["thread"], queryFn });
      return <span>{query.isError ? "failed" : (query.data ?? "loading")}</span>;
    }
    render(
      <QueryClientProvider client={queryClient}>
        <Probe />
      </QueryClientProvider>,
    );
    await act(async () => {
      await vi.advanceTimersByTimeAsync(10);
    });
    return queryFn;
  }

  test("a read-busy 429 waits its Retry-After and then succeeds, never showing the failure", async () => {
    const queryFn = await refusedOnce(new ApiError(429, { type: PROBLEM_TYPES.readBusy }, 1_000));
    try {
      expect(queryFn).toHaveBeenCalledTimes(1);
      expect(screen.queryByText("failed")).toBeNull();
      await act(async () => {
        await vi.advanceTimersByTimeAsync(500);
      });
      expect(queryFn).toHaveBeenCalledTimes(1);
      await act(async () => {
        await vi.advanceTimersByTimeAsync(2_000);
      });
      expect(queryFn).toHaveBeenCalledTimes(2);
      expect(screen.getByText("ok")).toBeTruthy();
    } finally {
      vi.useRealTimers();
    }
  });

  test("a 403 is never retried: it fails at once", async () => {
    const queryFn = await refusedOnce(new ApiError(403, null));
    try {
      expect(screen.getByText("failed")).toBeTruthy();
      await act(async () => {
        await vi.advanceTimersByTimeAsync(10_000);
      });
      expect(queryFn).toHaveBeenCalledTimes(1);
    } finally {
      vi.useRealTimers();
    }
  });
});
