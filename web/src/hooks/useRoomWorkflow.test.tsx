import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { renderHook, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import type { ReactNode } from "react";
import { useRoomWorkflow } from "./useRoomWorkflow";
import { jsonResponse } from "../test/http";

describe("useRoomWorkflow", () => {
  beforeEach(() => {
    vi.stubGlobal("fetch", vi.fn(() => Promise.resolve(jsonResponse(200, { revision: 3, statuses: [], fields: [] }))));
    localStorage.setItem("adder.auth.token", "fake-token");
  });
  afterEach(() => {
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  test("reads the room's workflow under the shared key, once for every consumer", async () => {
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const wrapper = ({ children }: { children: ReactNode }) => <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>;
    const first = renderHook(() => useRoomWorkflow(9), { wrapper });
    const second = renderHook(() => useRoomWorkflow(9), { wrapper });
    await waitFor(() => expect(first.result.current.data?.revision).toBe(3));
    await waitFor(() => expect(second.result.current.data?.revision).toBe(3));
    expect(vi.mocked(fetch)).toHaveBeenCalledTimes(1);
    expect(vi.mocked(fetch).mock.calls[0][0]).toBe("/api/v1/rooms/9/workflow");
    expect(queryClient.getQueryData(["rooms", "workflow", 9])).toMatchObject({ revision: 3 });
  });

  test("a dependent observer reuses the authoritative room workflow but still follows live invalidation", async () => {
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const wrapper = ({ children }: { children: ReactNode }) => <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>;
    const primary = renderHook(() => useRoomWorkflow(9), { wrapper });
    await waitFor(() => expect(primary.result.current.data?.revision).toBe(3));

    const dependent = renderHook(() => useRoomWorkflow(9, { refetchOnMount: false }), { wrapper });
    await waitFor(() => expect(dependent.result.current.isFetching).toBe(false));
    expect(dependent.result.current.data?.revision).toBe(3);
    expect(vi.mocked(fetch)).toHaveBeenCalledTimes(1);

    await queryClient.invalidateQueries({ queryKey: ["rooms", "workflow", 9] });
    await waitFor(() => expect(vi.mocked(fetch)).toHaveBeenCalledTimes(2));
    expect(dependent.result.current.data?.revision).toBe(3);
  });
});
