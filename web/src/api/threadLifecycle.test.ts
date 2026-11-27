import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { mergeThread } from "./threadLifecycle";

describe("thread lifecycle API", () => {
  let request: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    request = vi.fn().mockResolvedValue(new Response(null, { status: 204 }));
    vi.stubGlobal("fetch", request);
    localStorage.setItem("adder.auth.token", "fake-token");
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  test("merge sends optional same-origin correlation without changing its body", async () => {
    const mutationId = "14d7ed05-24c1-4374-b6eb-60b147595822";
    await mergeThread(5, 42, 7, mutationId);
    const [url, init] = request.mock.calls.at(-1) as [string, RequestInit];
    expect([url, init.method, JSON.parse(init.body as string)]).toEqual([
      "/api/v1/rooms/5/threads/42/merge", "PUT", { targetNumber: 7 },
    ]);
    expect(new Headers(init.headers).get("X-Mutation-Id")).toBe(mutationId);

    await mergeThread(5, 42, 7);
    const secondInit = (request.mock.calls.at(-1) as [string, RequestInit])[1];
    expect(new Headers(secondInit.headers).has("X-Mutation-Id")).toBe(false);
  });
});
