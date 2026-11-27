import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { listThreadReferences } from "./threadReferences";

const json = (body: unknown) => new Response(JSON.stringify(body), { status: 200, headers: { "Content-Type": "application/json" } });

describe("thread references API", () => {
  let request: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    request = vi.fn();
    vi.stubGlobal("fetch", request);
    localStorage.setItem("adder.auth.token", "token");
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  test("repeats up to 100 numbers and consumes the caller's abort signal", async () => {
    request.mockResolvedValue(json({ items: [{ number: 7 }, { number: 42 }], page: 1, pageSize: 100, total: 2 }));
    const controller = new AbortController();
    await expect(listThreadReferences(5, [7, 42], controller.signal)).resolves.toEqual(new Set([7, 42]));
    const [url, init] = request.mock.calls[0] as [string, RequestInit];
    expect(url).toBe("/api/v1/rooms/5/thread-references?number=7&number=42&page=1&pageSize=100");
    expect(init.signal).not.toBe(controller.signal);
    controller.abort();
    expect(init.signal?.aborted).toBe(true);
  });

  test.each([
    null,
    {},
    { items: [], page: 1, pageSize: 100 },
    { items: [{ number: 0 }], page: 1, pageSize: 100, total: 1 },
    { items: [{ number: 8 }], page: 1, pageSize: 100, total: 2 },
    { items: [{ number: 99 }], page: 1, pageSize: 100, total: 1 },
    { items: [{ number: 7 }, { number: 7 }], page: 1, pageSize: 100, total: 2 },
  ])("rejects malformed, incomplete, inconsistent, or unrequested data %#", async (body) => {
    request.mockResolvedValue(json(body));
    await expect(listThreadReferences(5, [7, 8], new AbortController().signal)).rejects.toThrow("Invalid thread-reference response");
  });

  test.each([
    { numbers: [] },
    { numbers: [0] },
    { numbers: [Number.MAX_SAFE_INTEGER + 1] },
    { numbers: Array.from({ length: 101 }, (_, index) => index + 1) },
  ])("rejects an invalid request before transport %#", async ({ numbers }) => {
    await expect(listThreadReferences(5, numbers, new AbortController().signal)).rejects.toThrow("Invalid thread-reference request");
    expect(request).not.toHaveBeenCalled();
  });

  test("falls back without AbortSignal.any or a transport deadline and forwards an already-aborted caller", async () => {
    const anyDescriptor = Object.getOwnPropertyDescriptor(AbortSignal, "any");
    const timeoutDescriptor = Object.getOwnPropertyDescriptor(AbortSignal, "timeout");
    Object.defineProperty(AbortSignal, "any", { configurable: true, value: undefined });
    Object.defineProperty(AbortSignal, "timeout", { configurable: true, value: undefined });
    try {
      request.mockResolvedValue(json({ items: [{ number: 42 }], page: 1, pageSize: 100, total: 1 }));
      const controller = new AbortController();
      controller.abort(new Error("left"));
      await expect(listThreadReferences(5, [42], controller.signal)).resolves.toEqual(new Set([42]));
      const signal = (request.mock.calls[0][1] as RequestInit).signal;
      expect(signal?.aborted).toBe(true);
      expect(signal?.reason).toEqual(controller.signal.reason);
    } finally {
      if (anyDescriptor === undefined) delete (AbortSignal as { any?: unknown }).any;
      else Object.defineProperty(AbortSignal, "any", anyDescriptor);
      if (timeoutDescriptor === undefined) delete (AbortSignal as { timeout?: unknown }).timeout;
      else Object.defineProperty(AbortSignal, "timeout", timeoutDescriptor);
    }
  });

  test("the manual signal fallback forwards a later caller abort", async () => {
    const anyDescriptor = Object.getOwnPropertyDescriptor(AbortSignal, "any");
    Object.defineProperty(AbortSignal, "any", { configurable: true, value: undefined });
    try {
      request.mockResolvedValue(json({ items: [{ number: 42 }], page: 1, pageSize: 100, total: 1 }));
      const controller = new AbortController();
      await expect(listThreadReferences(5, [42], controller.signal)).resolves.toEqual(new Set([42]));
      const signal = (request.mock.calls[0][1] as RequestInit).signal;
      expect(signal?.aborted).toBe(false);
      controller.abort(new Error("left"));
      expect(signal?.aborted).toBe(true);
      expect(signal?.reason).toEqual(controller.signal.reason);
    } finally {
      if (anyDescriptor === undefined) delete (AbortSignal as { any?: unknown }).any;
      else Object.defineProperty(AbortSignal, "any", anyDescriptor);
    }
  });
});
