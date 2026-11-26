import { QueryClient, type QueryKey } from "@tanstack/react-query";
import { describe, expect, test, vi } from "vitest";
import { beginThreadRevisionEcho, refreshThreadRevisionWrite } from "./threadRevisionEcho";

function clientWith(stateFor: (key: QueryKey) => { status: string; fetchStatus: string; isInvalidated: boolean }) {
  const cancelQueries = vi.fn().mockResolvedValue(undefined);
  const invalidateQueries = vi.fn().mockResolvedValue(undefined);
  const getQueryCache = () => ({ findAll: ({ queryKey }: { queryKey: QueryKey }) => [{ state: stateFor(queryKey) }] });
  return { client: { cancelQueries, invalidateQueries, getQueryCache } as unknown as QueryClient, cancelQueries, invalidateQueries };
}

describe("thread revision mutation refresh", () => {
  test("does not start correlation across origins", () => {
    expect(beginThreadRevisionEcho(new QueryClient(), 12, 42, "https://api.example", "https://app.example/rooms/12")).toBeUndefined();
  });

  test("confirms only after the thread tree, rail and Home are all fresh", async () => {
    const fresh = { status: "success", fetchStatus: "idle", isInvalidated: false };
    const { client, cancelQueries, invalidateQueries } = clientWith(() => fresh);
    const echo = { id: "id", confirm: vi.fn(), cancel: vi.fn() };

    await refreshThreadRevisionWrite(client, 12, echo);

    const expected = [["rooms", "threads", 12], ["rooms", "rail"], ["home"]];
    expect(cancelQueries.mock.calls.map(([arg]) => arg.queryKey)).toEqual(expected);
    expect(invalidateQueries.mock.calls.map(([arg]) => arg.queryKey)).toEqual(expected);
    expect(echo.confirm).toHaveBeenCalledOnce();
    expect(echo.cancel).toHaveBeenCalledOnce();
  });

  test.each([
    ["stale", { status: "success", fetchStatus: "idle", isInvalidated: true }],
    ["failed", { status: "error", fetchStatus: "idle", isInvalidated: false }],
    ["paused", { status: "success", fetchStatus: "paused", isInvalidated: false }],
  ])("cancels for a %s active read", async (_label, state) => {
    const { client } = clientWith((key) => key[0] === "home" ? state : { status: "success", fetchStatus: "idle", isInvalidated: false });
    const echo = { id: "id", confirm: vi.fn(), cancel: vi.fn() };

    await refreshThreadRevisionWrite(client, 12, echo);

    expect(echo.confirm).not.toHaveBeenCalled();
    expect(echo.cancel).toHaveBeenCalledOnce();
  });

  test("refreshes normally when correlation could not be started", async () => {
    const { client, invalidateQueries } = clientWith(() => ({
      status: "success", fetchStatus: "idle", isInvalidated: false,
    }));

    await refreshThreadRevisionWrite(client, 12);

    expect(invalidateQueries).toHaveBeenCalledTimes(3);
  });
});
