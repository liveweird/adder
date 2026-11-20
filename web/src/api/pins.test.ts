import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { listRoomPins, setMessagePin, setThreadPin } from "./pins";

describe("pins API wrappers", () => {
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

  test("listRoomPins reads the whole set in one page of 50, in the server's own order", async () => {
    mockFetch.mockResolvedValue(new Response(JSON.stringify({ items: [], page: 1, pageSize: 50, total: 0 }), { status: 200, headers: { "Content-Type": "application/json" } }));
    await listRoomPins(5);
    expect(lastCall()[0]).toBe("/api/v1/rooms/5/pins?page=1&pageSize=50");
  });

  test("setThreadPin PUTs {pinned} to the thread's number", async () => {
    mockFetch.mockResolvedValue(new Response(null, { status: 204 }));
    await setThreadPin(5, 42, true);
    const [url, init] = lastCall();
    expect([url, init.method, JSON.parse(init.body as string)]).toEqual(["/api/v1/rooms/5/threads/42/pin", "PUT", { pinned: true }]);
  });

  test("setMessagePin PUTs {pinned} to the message id", async () => {
    mockFetch.mockResolvedValue(new Response(null, { status: 204 }));
    await setMessagePin(5, 991, false);
    const [url, init] = lastCall();
    expect([url, init.method, JSON.parse(init.body as string)]).toEqual(["/api/v1/rooms/5/messages/991/pin", "PUT", { pinned: false }]);
  });
});
