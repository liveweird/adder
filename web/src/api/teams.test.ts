import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { addTeamMember, removeTeamMember } from "./teams";

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
