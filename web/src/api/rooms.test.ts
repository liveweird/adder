import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { addRoomMembers, archiveRoom, createRoom, deleteRoom, getRoom, getRoomWorkflow, listRoomMembers, listRooms, markRoomRead, markRoomSeen, removeRoomMember, setRoomMemberRole, unarchiveRoom, updateRoom } from "./rooms";

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
    await listRooms({ page: 1, pageSize: 100, sort: "name", memberId: 7 });
    expect(lastCall()[0]).toBe("/api/v1/rooms?page=1&pageSize=100&sort=name&memberId=7");
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
    await expect(createRoom({ name: "A", description: null, templateId: 2, language: "pl", accessType: "PUBLIC" })).resolves.toMatchObject({ id: 9 });
    const [url, init] = lastCall();
    expect([url, init.method]).toEqual(["/api/v1/rooms", "POST"]);
    expect(JSON.parse(init.body as string)).toEqual({ name: "A", description: null, templateId: 2, language: "pl", accessType: "PUBLIC" });
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

  test("listRoomMembers sends paging, sort and the filters, and omits what is unset", async () => {
    mockFetch.mockImplementation(() => Promise.resolve(json(200, { items: [], page: 1, pageSize: 20, total: 0 })));
    await listRoomMembers(5, { page: 2, pageSize: 40, sort: "-addedAt", name: "ann", role: "OWNER" });
    expect(lastCall()[0]).toBe("/api/v1/rooms/5/members?page=2&pageSize=40&sort=-addedAt&name=ann&role=OWNER");
    await listRoomMembers(5, { page: 1, pageSize: 20 });
    expect(lastCall()[0]).toBe("/api/v1/rooms/5/members?page=1&pageSize=20");
  });

  test("addRoomMembers POSTs the ids and/or the team with optional correlation and returns the counts", async () => {
    mockFetch.mockResolvedValue(json(200, { added: 2, alreadyMembers: 1 }));
    const mutationId = "14d7ed05-24c1-4374-b6eb-60b147595822";
    await expect(addRoomMembers(5, { userIds: [3, 4], teamId: 9 }, mutationId)).resolves.toEqual({ added: 2, alreadyMembers: 1 });
    const [url, init] = lastCall();
    expect([url, init.method]).toEqual(["/api/v1/rooms/5/members", "POST"]);
    expect(JSON.parse(init.body as string)).toEqual({ userIds: [3, 4], teamId: 9 });
    expect(new Headers(init.headers).get("X-Mutation-Id")).toBe(mutationId);
  });

  test("setRoomMemberRole PUTs the role and removeRoomMember DELETEs the member", async () => {
    mockFetch.mockImplementation(() => Promise.resolve(new Response(null, { status: 204 })));
    await setRoomMemberRole(5, 7, "OWNER");
    expect([lastCall()[0], lastCall()[1].method, JSON.parse(lastCall()[1].body as string)]).toEqual(["/api/v1/rooms/5/members/7", "PUT", { role: "OWNER" }]);
    const mutationId = "14d7ed05-24c1-4374-b6eb-60b147595822";
    await removeRoomMember(5, 7, mutationId);
    expect([lastCall()[0], lastCall()[1].method]).toEqual(["/api/v1/rooms/5/members/7", "DELETE"]);
    expect(new Headers(lastCall()[1].headers).get("X-Mutation-Id")).toBe(mutationId);
  });

  test("markRoomSeen POSTs with no body (204)", async () => {
    mockFetch.mockResolvedValue(new Response(null, { status: 204 }));
    await markRoomSeen(5);
    expect([lastCall()[0], lastCall()[1].method, lastCall()[1].body]).toEqual(["/api/v1/rooms/5/seen", "POST", undefined]);
  });

  test("markRoomRead POSTs read-all with no body (204)", async () => {
    mockFetch.mockResolvedValue(new Response(null, { status: 204 }));
    await markRoomRead(5);
    expect([lastCall()[0], lastCall()[1].method, lastCall()[1].body]).toEqual(["/api/v1/rooms/5/read-all", "POST", undefined]);
  });
});
