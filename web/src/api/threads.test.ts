import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { createThread, getThread, listRoomHashtags, listThreadEvents, listThreads, setThreadOwner, setThreadReadMarker, setThreadStatus, setThreadTags, setThreadWatch, updateThreadDetails } from "./threads";

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

  test("listThreads repeats the tag key, URL-encoding each value as typed", async () => {
    mockFetch.mockImplementation(() => Promise.resolve(json(200, { items: [], page: 1, pageSize: 50, total: 0 })));
    await listThreads(5, { page: 1, pageSize: 50, tag: ["Wdro\u017cenie", "hawai'i", "a 1/2"] });
    expect(lastCall()[0]).toBe("/api/v1/rooms/5/threads?page=1&pageSize=50&tag=Wdro%C5%BCenie&tag=hawai%27i&tag=a+1%2F2");
  });

  test("listThreads repeats threadTag for the curated tags and leaves it out when unset", async () => {
    mockFetch.mockImplementation(() => Promise.resolve(json(200, { items: [], page: 1, pageSize: 50, total: 0 })));
    await listThreads(5, { page: 1, pageSize: 50, threadTag: ["security", "a b"], tag: ["outage"] });
    expect(lastCall()[0]).toBe("/api/v1/rooms/5/threads?page=1&pageSize=50&tag=outage&threadTag=security&threadTag=a+b");
    await listThreads(5, { page: 1, pageSize: 50 });
    expect(lastCall()[0]).toBe("/api/v1/rooms/5/threads?page=1&pageSize=50");
  });

  test("setThreadTags PUTs the whole set under X-Expected-Revision", async () => {
    mockFetch.mockImplementation(() => Promise.resolve(new Response(null, { status: 204 })));
    await setThreadTags(5, 42, ["data", "security"], 7);
    const [url, init] = lastCall();
    expect(url).toBe("/api/v1/rooms/5/threads/42/tags");
    expect(init.method).toBe("PUT");
    expect(new Headers(init.headers).get("X-Expected-Revision")).toBe("7");
    expect(JSON.parse(init.body as string)).toEqual({ tags: ["data", "security"] });
    await setThreadTags(5, 42, [], 8);
    expect(JSON.parse(lastCall()[1].body as string)).toEqual({ tags: [] });
  });

  test("listThreads sends the mentioned people as the repeated mentionedId key", async () => {
    mockFetch.mockImplementation(() => Promise.resolve(json(200, { items: [], page: 1, pageSize: 50, total: 0 })));
    await listThreads(5, { page: 1, pageSize: 50, mentionedId: [7, 9] });
    expect(lastCall()[0]).toBe("/api/v1/rooms/5/threads?page=1&pageSize=50&mentionedId=7&mentionedId=9");
  });

  test("listThreads sends unread=true and leaves it out when unset", async () => {
    mockFetch.mockImplementation(() => Promise.resolve(json(200, { items: [], page: 1, pageSize: 50, total: 0 })));
    await listThreads(5, { page: 1, pageSize: 50, unread: true });
    expect(lastCall()[0]).toBe("/api/v1/rooms/5/threads?page=1&pageSize=50&unread=true");
    await listThreads(5, { page: 1, pageSize: 50 });
    expect(lastCall()[0]).toBe("/api/v1/rooms/5/threads?page=1&pageSize=50");
  });

  test("listThreads sends watching=true and leaves it out when unset", async () => {
    mockFetch.mockImplementation(() => Promise.resolve(json(200, { items: [], page: 1, pageSize: 50, total: 0 })));
    await listThreads(5, { page: 1, pageSize: 50, watching: true });
    expect(lastCall()[0]).toBe("/api/v1/rooms/5/threads?page=1&pageSize=50&watching=true");
    await listThreads(5, { page: 1, pageSize: 50 });
    expect(lastCall()[0]).toBe("/api/v1/rooms/5/threads?page=1&pageSize=50");
  });

  test("listRoomHashtags reads a room's tags with paging, sort and the substring filter, and omits what is unset", async () => {
    mockFetch.mockImplementation(() => Promise.resolve(json(200, { items: [{ tag: "outage", threadCount: 3 }], page: 1, pageSize: 50, total: 1 })));
    await expect(listRoomHashtags(5, { page: 1, pageSize: 50 })).resolves.toMatchObject({ items: [{ tag: "outage", threadCount: 3 }] });
    expect(lastCall()[0]).toBe("/api/v1/rooms/5/tags?page=1&pageSize=50");
    await listRoomHashtags(5, { page: 2, pageSize: 20, sort: "tag", q: "wdro" });
    expect(lastCall()[0]).toBe("/api/v1/rooms/5/tags?page=2&pageSize=20&sort=tag&q=wdro");
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

  test("updateThreadDetails PUTs the body under X-Expected-Revision (a cleared field travels as null)", async () => {
    mockFetch.mockResolvedValue(new Response(null, { status: 204 }));
    await updateThreadDetails(5, 42, { title: "T", fields: { severity: null, note: "x" } }, 3);
    const [url, init] = lastCall();
    expect([url, init.method]).toEqual(["/api/v1/rooms/5/threads/42", "PUT"]);
    expect(new Headers(init.headers).get("X-Expected-Revision")).toBe("3");
    expect(JSON.parse(init.body as string)).toEqual({ title: "T", fields: { severity: null, note: "x" } });
  });

  test("setThreadStatus and setThreadOwner PUT their one value, with no revision header", async () => {
    mockFetch.mockResolvedValue(new Response(null, { status: 204 }));
    await setThreadStatus(5, 42, "done");
    let [url, init] = lastCall();
    expect([url, init.method, init.body]).toEqual(["/api/v1/rooms/5/threads/42/status", "PUT", JSON.stringify({ status: "done" })]);
    expect(new Headers(init.headers).has("X-Expected-Revision")).toBe(false);
    await setThreadOwner(5, 42, 9);
    [url, init] = lastCall();
    expect([url, init.method, init.body]).toEqual(["/api/v1/rooms/5/threads/42/owner", "PUT", JSON.stringify({ ownerId: 9 })]);
  });

  test("listThreadEvents asks for a page and leaves the order to the server (newest first)", async () => {
    mockFetch.mockImplementation(() => Promise.resolve(json(200, { items: [], page: 2, pageSize: 10, total: 12 })));
    await expect(listThreadEvents(5, 42, { page: 2, pageSize: 10 })).resolves.toMatchObject({ total: 12 });
    expect(lastCall()[0]).toBe("/api/v1/rooms/5/threads/42/events?page=2&pageSize=10");
  });

  test("setThreadWatch PUTs the state the caller wants", async () => {
    mockFetch.mockResolvedValue(new Response(null, { status: 204 }));
    await setThreadWatch(5, 42, true);
    expect([lastCall()[0], lastCall()[1].method, lastCall()[1].body]).toEqual(["/api/v1/rooms/5/threads/42/watch", "PUT", JSON.stringify({ watching: true })]);
    await setThreadWatch(5, 42, false);
    expect(lastCall()[1].body).toBe(JSON.stringify({ watching: false }));
  });

  test("setThreadReadMarker PUTs the message id the reader has reached", async () => {
    mockFetch.mockResolvedValue(new Response(null, { status: 204 }));
    await setThreadReadMarker(5, 42, 311);
    const [url, init] = lastCall();
    expect([url, init.method, init.body]).toEqual(["/api/v1/rooms/5/threads/42/read-marker", "PUT", JSON.stringify({ messageId: 311 })]);
  });
});
