import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { archiveRoom, createRoom, deleteRoom, getRoom, getRoomWorkflow, listRooms, unarchiveRoom, updateRoom } from "./rooms";

describe("rooms API wrappers", () => {
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

  test("listRooms sends paging, sort and the filters, and sends false but omits what is unset", async () => {
    mockFetch.mockImplementation(() => Promise.resolve(json(200, { items: [], page: 1, pageSize: 20, total: 0 })));
    await listRooms({ page: 2, pageSize: 100, sort: "-lastActivityAt", name: "night", archived: false, member: true });
    expect(lastCall()[0]).toBe("/api/v1/rooms?page=2&pageSize=100&sort=-lastActivityAt&name=night&archived=false&member=true");
    await listRooms({ page: 1, pageSize: 20 });
    expect(lastCall()[0]).toBe("/api/v1/rooms?page=1&pageSize=20");
  });

  test("getRoom and getRoomWorkflow read by id", async () => {
    mockFetch.mockImplementation(() => Promise.resolve(json(200, { id: 3 })));
    await expect(getRoom(3)).resolves.toMatchObject({ id: 3 });
    expect(lastCall()[0]).toBe("/api/v1/rooms/3");
    await getRoomWorkflow(3);
    expect(lastCall()[0]).toBe("/api/v1/rooms/3/workflow");
  });

  test("createRoom POSTs the body and returns the room", async () => {
    mockFetch.mockResolvedValue(json(201, { id: 9 }));
    await expect(createRoom({ name: "A", description: null, templateId: 2, language: "pl" })).resolves.toMatchObject({ id: 9 });
    const [url, init] = lastCall();
    expect([url, init.method]).toEqual(["/api/v1/rooms", "POST"]);
    expect(JSON.parse(init.body as string)).toEqual({ name: "A", description: null, templateId: 2, language: "pl" });
  });

  test("updateRoom PUTs, archive/unarchive POST without a body, and delete DELETEs", async () => {
    mockFetch.mockImplementation(() => Promise.resolve(new Response(null, { status: 204 })));
    await updateRoom(4, { name: "B", description: null });
    expect([lastCall()[0], lastCall()[1].method]).toEqual(["/api/v1/rooms/4", "PUT"]);
    await archiveRoom(4);
    expect([lastCall()[0], lastCall()[1].method, lastCall()[1].body]).toEqual(["/api/v1/rooms/4/archive", "POST", undefined]);
    await unarchiveRoom(4);
    expect([lastCall()[0], lastCall()[1].method]).toEqual(["/api/v1/rooms/4/unarchive", "POST"]);
    await deleteRoom(4);
    expect([lastCall()[0], lastCall()[1].method]).toEqual(["/api/v1/rooms/4", "DELETE"]);
  });
});
