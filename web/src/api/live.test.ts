import { afterEach, beforeEach, describe, expect, expectTypeOf, test, vi } from "vitest";
import { ApiError } from "./http";
import { LIVE_SOCKET_LIMIT_PROBLEM, LiveMintError, mintLiveTicket, type LiveTicket } from "./live";
import type { components } from "./schema";

describe("live API wrapper", () => {
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

  const json = (status: number, body: unknown, headers: Record<string, string> = {}) =>
    new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json", ...headers } });

  test("the ticket type is the generated one", () => {
    expectTypeOf<LiveTicket>().toEqualTypeOf<components["schemas"]["LiveTicketResponse"]>();
  });

  test("mintLiveTicket POSTs with the bearer token, a deadline and no body, and returns the ticket", async () => {
    mockFetch.mockResolvedValue(json(200, { ticket: "t".repeat(43), expiresAt: 1234 }));
    await expect(mintLiveTicket()).resolves.toEqual({ ticket: "t".repeat(43), expiresAt: 1234 });
    const [url, init] = mockFetch.mock.calls[0] as [string, RequestInit];
    expect([url, init.method, init.body]).toEqual(["/api/v1/live/tickets", "POST", undefined]);
    expect(new Headers(init.headers).get("Authorization")).toBe("Bearer fake-token");
    if (typeof AbortSignal.timeout === "function") expect(init.signal).toBeInstanceOf(AbortSignal);
  });

  test("a refusal keeps its status, its problem and the Retry-After in milliseconds", async () => {
    mockFetch.mockResolvedValue(json(429, { type: LIVE_SOCKET_LIMIT_PROBLEM, status: 429 }, { "Retry-After": "60" }));
    const error = await mintLiveTicket().catch((e: unknown) => e);
    expect(error).toBeInstanceOf(LiveMintError);
    expect(error).toBeInstanceOf(ApiError);
    expect(error).toMatchObject({ status: 429, type: LIVE_SOCKET_LIMIT_PROBLEM, retryAfterMs: 60_000 });
  });

  test("no Retry-After, or one that is not whole seconds, is none", async () => {
    mockFetch.mockResolvedValueOnce(json(503, { status: 503 }));
    await expect(mintLiveTicket()).rejects.toMatchObject({ status: 503, retryAfterMs: null });
    mockFetch.mockResolvedValueOnce(json(503, { status: 503 }, { "Retry-After": "Wed, 21 Oct 2026 07:28:00 GMT" }));
    await expect(mintLiveTicket()).rejects.toMatchObject({ retryAfterMs: null });
  });
});
