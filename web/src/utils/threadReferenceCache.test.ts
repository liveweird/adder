import { QueryClient } from "@tanstack/react-query";
import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { loadThreadReference, threadReferenceKey } from "./threadReferenceCache";

const pageFor = (url: string) => {
  const numbers = new URL(url, "https://adder.test").searchParams.getAll("number").map(Number);
  return new Response(JSON.stringify({ items: numbers.map((number) => ({ number })), page: 1, pageSize: 100, total: numbers.length }), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  });
};

describe("thread reference batch loader", () => {
  let request: ReturnType<typeof vi.fn>;
  let client: QueryClient;

  beforeEach(() => {
    request = vi.fn((url: string) => Promise.resolve(pageFor(url)));
    vi.stubGlobal("fetch", request);
    localStorage.setItem("adder.auth.token", "token");
    client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  });

  afterEach(() => {
    client.clear();
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  test("deduplicates a room/number and batches at most 100 unique numbers", async () => {
    const controller = new AbortController();
    const first = loadThreadReference(client, 5, 1, controller.signal);
    const duplicate = loadThreadReference(client, 5, 1, controller.signal);
    const rest = Array.from({ length: 100 }, (_, index) => loadThreadReference(client, 5, index + 2, controller.signal));
    await expect(Promise.all([first, duplicate, ...rest])).resolves.toEqual(Array(102).fill(true));
    expect(request).toHaveBeenCalledTimes(2);
    expect(new URL(request.mock.calls[0][0], "https://adder.test").searchParams.getAll("number")).toHaveLength(100);
    expect(new URL(request.mock.calls[1][0], "https://adder.test").searchParams.getAll("number")).toHaveLength(1);
  });

  test("keeps room boundaries and no more than two requests in flight", async () => {
    let active = 0;
    let maximum = 0;
    const releases: Array<() => void> = [];
    request.mockImplementation((url: string) => new Promise<Response>((resolve) => {
      active += 1;
      maximum = Math.max(maximum, active);
      releases.push(() => { active -= 1; resolve(pageFor(url)); });
    }));
    const signal = new AbortController().signal;
    const loads = [
      ...Array.from({ length: 201 }, (_, index) => loadThreadReference(client, 5, index + 1, signal)),
      loadThreadReference(client, 6, 1, signal),
    ];
    await vi.waitFor(() => expect(request).toHaveBeenCalledTimes(2));
    expect(maximum).toBe(2);
    releases.shift()!();
    await vi.waitFor(() => expect(request).toHaveBeenCalledTimes(3));
    expect(maximum).toBe(2);
    while (releases.length > 0) releases.shift()!();
    await vi.waitFor(() => expect(request).toHaveBeenCalledTimes(4));
    while (releases.length > 0) releases.shift()!();
    await expect(Promise.all(loads)).resolves.toHaveLength(202);
    const rooms = request.mock.calls.map(([url]) => new URL(url, "https://adder.test").pathname);
    expect(rooms.filter((path) => path.includes("/rooms/5/"))).toHaveLength(3);
    expect(rooms.filter((path) => path.includes("/rooms/6/"))).toHaveLength(1);
  });

  test("canceling one consumer keeps its batch alive, while canceling all aborts it and rejects late data", async () => {
    let release!: () => void;
    let requestSignal!: AbortSignal;
    request.mockImplementation((url: string, init: RequestInit) => new Promise<Response>((resolve) => {
      requestSignal = init.signal as AbortSignal;
      release = () => resolve(pageFor(url));
    }));
    const first = client.fetchQuery({
      queryKey: threadReferenceKey(5, 41),
      queryFn: ({ signal }) => loadThreadReference(client, 5, 41, signal),
    });
    const second = client.fetchQuery({
      queryKey: threadReferenceKey(5, 42),
      queryFn: ({ signal }) => loadThreadReference(client, 5, 42, signal),
    });
    await vi.waitFor(() => expect(request).toHaveBeenCalledOnce());
    await client.cancelQueries({ queryKey: threadReferenceKey(5, 41) });
    expect(requestSignal.aborted).toBe(false);
    await expect(first).rejects.toBeDefined();
    await client.cancelQueries({ queryKey: threadReferenceKey(5, 42) });
    expect(requestSignal.aborted).toBe(true);
    release();
    await expect(second).rejects.toBeDefined();
    expect(client.getQueryData(threadReferenceKey(5, 41))).toBeUndefined();
    expect(client.getQueryData(threadReferenceKey(5, 42))).toBeUndefined();
  });

  test("an already-aborted or synchronously canceled lookup never starts an HTTP batch", async () => {
    const already = new AbortController();
    already.abort(new Error("already gone"));
    await expect(loadThreadReference(client, 5, 41, already.signal)).rejects.toThrow("already gone");
    const queued = new AbortController();
    const pending = loadThreadReference(client, 5, 42, queued.signal);
    queued.abort(new Error("left before dispatch"));
    await expect(pending).rejects.toThrow("left before dispatch");
    await Promise.resolve();
    expect(request).not.toHaveBeenCalled();
  });
});
