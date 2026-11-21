import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { searchRoom, searchRooms } from "./search";

describe("search API wrappers", () => {
  let mockFetch: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    mockFetch = vi.fn(() => Promise.resolve(new Response(JSON.stringify({ items: [], page: 1, pageSize: 20, total: 0 }), { status: 200, headers: { "Content-Type": "application/json" } })));
    vi.stubGlobal("fetch", mockFetch);
    localStorage.setItem("adder.auth.token", "fake-token");
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  const lastUrl = () => (mockFetch.mock.calls.at(-1) as [string])[0];

  test("searchRoom asks one room, with the term, the page, the page size and the closed filter (false is sent)", async () => {
    await searchRoom(5, { q: "outage", page: 2, pageSize: 20, closed: false });
    expect(lastUrl()).toBe("/api/v1/rooms/5/search?q=outage&page=2&pageSize=20&closed=false");
    await searchRoom(5, { q: "outage", page: 1, pageSize: 20, closed: true });
    expect(lastUrl()).toBe("/api/v1/rooms/5/search?q=outage&page=1&pageSize=20&closed=true");
    await searchRoom(5, { q: "outage", page: 1, pageSize: 20 });
    expect(lastUrl()).toBe("/api/v1/rooms/5/search?q=outage&page=1&pageSize=20");
  });

  test("watching is sent as true, and q is left out of the watched-threads list", async () => {
    await searchRooms({ page: 1, pageSize: 20, watching: true });
    expect(lastUrl()).toBe("/api/v1/search?page=1&pageSize=20&watching=true");
    await searchRoom(5, { q: "outage", page: 1, pageSize: 20, closed: false, watching: true });
    expect(lastUrl()).toBe("/api/v1/rooms/5/search?q=outage&page=1&pageSize=20&closed=false&watching=true");
    await searchRoom(5, { q: "outage", page: 1, pageSize: 20, watching: false });
    expect(lastUrl()).toBe("/api/v1/rooms/5/search?q=outage&page=1&pageSize=20&watching=false");
  });

  test("searchRooms asks across the caller's rooms, and the term is encoded", async () => {
    await searchRooms({ q: "awaria bazy & co", page: 1, pageSize: 20 });
    expect(lastUrl()).toBe("/api/v1/search?q=awaria+bazy+%26+co&page=1&pageSize=20");
  });
});
