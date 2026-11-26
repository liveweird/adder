import { QueryClient, QueryObserver } from "@tanstack/react-query";
import { afterEach, describe, expect, test, vi } from "vitest";
import { beginEditEcho, refreshMessageEditWrite } from "./messageEditEcho";
import { clearReplyEchoes, type MutationEcho } from "./liveReplyEcho";
import { homeKey, roomKeys } from "./roomKeys";

describe("message edit write correlation", () => {
  const clients: QueryClient[] = [];

  afterEach(() => {
    for (const client of clients.splice(0)) {
      clearReplyEchoes(client);
      client.clear();
    }
    vi.restoreAllMocks();
  });

  test("starts correlation only when the edit API shares the page origin", () => {
    const client = new QueryClient();
    clients.push(client);
    vi.spyOn(crypto, "randomUUID").mockReturnValue("14d7ed05-24c1-4374-b6eb-60b147595822");

    expect(beginEditEcho(client, 5, 7, 99, "", "https://adder.test/rooms/5")).toBeDefined();
    expect(beginEditEcho(client, 5, 7, 99, "https://api.example.test", "https://adder.test/rooms/5")).toBeUndefined();
  });

  test("confirms only after the source room, pins, rail and Home are fresh", async () => {
    const client = new QueryClient();
    clients.push(client);
    const invalidate = vi.spyOn(client, "invalidateQueries").mockResolvedValue();
    const echo = { id: "edit", confirm: vi.fn(), cancel: vi.fn() } satisfies MutationEcho;

    await refreshMessageEditWrite(client, 5, echo);

    expect(invalidate.mock.calls.map(([filters]) => filters?.queryKey)).toEqual([
      roomKeys.threads(5), roomKeys.pins(5), roomKeys.rail, homeKey,
    ]);
    expect(echo.confirm).toHaveBeenCalledOnce();
    expect(echo.cancel).toHaveBeenCalledOnce();
  });

  test("refreshes a mounted revision page once even without a correlation id", async () => {
    const client = new QueryClient();
    clients.push(client);
    const key = [...roomKeys.messageRevisions(5, 7, 99), 1];
    client.setQueryData(key, { items: [{ body: "first body" }] });
    const queryFn = vi.fn().mockResolvedValue({ items: [{ body: "previous body" }, { body: "first body" }] });
    const observer = new QueryObserver(client, { queryKey: key, queryFn, staleTime: Infinity });
    const unsubscribe = observer.subscribe(() => undefined);
    try {
      await refreshMessageEditWrite(client, 5);
      expect(queryFn).toHaveBeenCalledOnce();
      expect(observer.getCurrentResult().data).toEqual({ items: [{ body: "previous body" }, { body: "first body" }] });
    } finally {
      unsubscribe();
    }
  });

  test("leaves the echo for ordinary replay when an active covered read fails", async () => {
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    clients.push(client);
    const key = roomKeys.messages(5, 7);
    client.setQueryData(key, "old");
    const observer = new QueryObserver(client, {
      queryKey: key,
      queryFn: () => Promise.reject(new Error("read failed")),
      staleTime: Infinity,
    });
    const unsubscribe = observer.subscribe(() => undefined);
    const echo = { id: "edit", confirm: vi.fn(), cancel: vi.fn() } satisfies MutationEcho;
    try {
      await refreshMessageEditWrite(client, 5, echo);
      expect(observer.getCurrentResult().status).toBe("error");
      expect(echo.confirm).not.toHaveBeenCalled();
      expect(echo.cancel).toHaveBeenCalledOnce();
    } finally {
      unsubscribe();
    }
  });
});
