import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { addTeamMember, listTeams, removeTeamMember } from "./teams";

describe("teams API roster wrappers", () => {
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

  test("listTeams sends memberId for the profile's teams and omits what is unset", async () => {
    mockFetch.mockImplementation(() => Promise.resolve(new Response(JSON.stringify({ items: [], page: 1, pageSize: 100, total: 0 }), { status: 200, headers: { "Content-Type": "application/json" } })));
    await listTeams({ page: 1, pageSize: 100, sort: "name", memberId: 7 });
    expect(lastCall()[0]).toBe("/api/v1/teams?page=1&pageSize=100&sort=name&memberId=7");
    await listTeams({ page: 1, pageSize: 20 });
    expect(lastCall()[0]).toBe("/api/v1/teams?page=1&pageSize=20");
  });

  test("addTeamMember PUTs the member path without a body", async () => {
    mockFetch.mockResolvedValue(new Response(null, { status: 204 }));
    await addTeamMember(3, 12);
    const [url, init] = lastCall();
    expect(url).toBe("/api/v1/teams/3/members/12");
    expect(init.method).toBe("PUT");
    expect(init.body).toBeUndefined();
  });

  test("removeTeamMember DELETEs the member path", async () => {
    mockFetch.mockResolvedValue(new Response(null, { status: 204 }));
    await removeTeamMember(3, 12);
    const [url, init] = lastCall();
    expect(url).toBe("/api/v1/teams/3/members/12");
    expect(init.method).toBe("DELETE");
  });

  test("a failed roster write throws the ApiError", async () => {
    mockFetch.mockResolvedValue(new Response(JSON.stringify({ title: "Conflict", status: 409 }), { status: 409, headers: { "Content-Type": "application/json" } }));
    await expect(addTeamMember(3, 12)).rejects.toMatchObject({ status: 409 });
  });
});
