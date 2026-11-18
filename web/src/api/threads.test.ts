import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { createThread, getThread, listThreads } from "./threads";

describe("threads API wrappers", () => {
  let mockFetch: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    mockFetch = vi.fn();
    vi.stubGlobal("fetch", mockFetch);
    localStorage.setItem("adder.auth.token", "fake-token");
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  const lastCall = () => mockFetch.mock.calls.at(-1) as [string, RequestInit];
  const json = (status: number, body: unknown) =>
    new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });

  test("listThreads sends paging, sort and the closed filter (false is sent), and omits what is unset", async () => {
    mockFetch.mockImplementation(() => Promise.resolve(json(200, { items: [], page: 1, pageSize: 50, total: 0 })));
    await listThreads(5, { page: 2, pageSize: 50, sort: "-lastActivityAt", closed: false });
    expect(lastCall()[0]).toBe("/api/v1/rooms/5/threads?page=2&pageSize=50&sort=-lastActivityAt&closed=false");
    await listThreads(5, { page: 1, pageSize: 50, closed: true });
    expect(lastCall()[0]).toBe("/api/v1/rooms/5/threads?page=1&pageSize=50&closed=true");
    await listThreads(5, { page: 1, pageSize: 50 });
    expect(lastCall()[0]).toBe("/api/v1/rooms/5/threads?page=1&pageSize=50");
  });

  test("getThread reads by the per-room number", async () => {
    mockFetch.mockImplementation(() => Promise.resolve(json(200, { number: 42 })));
    await expect(getThread(5, 42)).resolves.toMatchObject({ number: 42 });
    expect(lastCall()[0]).toBe("/api/v1/rooms/5/threads/42");
  });

  test("createThread POSTs the body and returns the thread", async () => {
    mockFetch.mockResolvedValue(json(201, { number: 7 }));
    await expect(createThread(5, { title: "T", body: "B", fields: { severity: "s1" } })).resolves.toMatchObject({ number: 7 });
    const [url, init] = lastCall();
    expect([url, init.method]).toEqual(["/api/v1/rooms/5/threads", "POST"]);
    expect(JSON.parse(init.body as string)).toEqual({ title: "T", body: "B", fields: { severity: "s1" } });
  });
});
