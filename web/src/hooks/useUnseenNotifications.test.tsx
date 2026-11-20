import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { renderHook, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import type { ReactNode } from "react";
import { useUnseenNotifications } from "./useUnseenNotifications";
import { jsonResponse } from "../test/http";

describe("useUnseenNotifications", () => {
  beforeEach(() => {
    vi.stubGlobal("fetch", vi.fn(() => Promise.resolve(jsonResponse(200, { items: [{ id: 1, roomId: 12, threadNumber: 3 }], page: 1, pageSize: 100, total: 1 }))));
    localStorage.setItem("adder.auth.token", "fake-token");
  });
  afterEach(() => {
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  const setup = () => {
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const wrapper = ({ children }: { children: ReactNode }) => <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>;
    return renderHook(() => useUnseenNotifications(), { wrapper });
  };

  test("the rows and the total come from the one unseen query", async () => {
    const { result } = setup();
    await waitFor(() => expect(result.current.total).toBe(1));
    expect(result.current.items).toEqual([{ id: 1, roomId: 12, threadNumber: 3 }]);
  });

  test("before anything has loaded the rows are one stable empty list, so a memo keyed on them does not recompute on every render", async () => {
    const { result, rerender } = setup();
    const first = result.current.items;
    expect(first).toEqual([]);
    rerender();
    expect(result.current.items).toBe(first);
    await waitFor(() => expect(result.current.items).toHaveLength(1));
  });
});
