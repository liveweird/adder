import { afterEach, beforeEach, describe, expect, expectTypeOf, test, vi } from "vitest";
import { deleteMessage, editMessage, listMessages, postMessage, type MessageBody, type MessageEditBody, type MessageResponse } from "./messages";
import type { components } from "./schema";

describe("messages API wrappers", () => {
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

  test("listMessages reads the newest page, and the next one through the id[lt] cursor", async () => {
    mockFetch.mockImplementation(() => Promise.resolve(json(200, { items: [], page: 1, pageSize: 50, total: 0 })));
    await listMessages(5, 42, { pageSize: 50 });
    expect(lastCall()[0]).toBe("/api/v1/rooms/5/threads/42/messages?page=1&pageSize=50&sort=-id");
    await listMessages(5, 42, { idLt: 120, pageSize: 50 });
    expect(decodeURIComponent(lastCall()[0])).toBe("/api/v1/rooms/5/threads/42/messages?page=1&pageSize=50&sort=-id&id[lt]=120");
  });

  test("postMessage POSTs the body and returns the message", async () => {
    mockFetch.mockResolvedValue(json(201, { id: 9 }));
    await expect(postMessage(5, 42, "hello")).resolves.toMatchObject({ id: 9 });
    const [url, init] = lastCall();
    expect([url, init.method]).toEqual(["/api/v1/rooms/5/threads/42/messages", "POST"]);
    expect(JSON.parse(init.body as string)).toEqual({ body: "hello" });
  });

  test("editMessage PUTs the body to the single message", async () => {
    mockFetch.mockResolvedValue(new Response(null, { status: 204 }));
    await expect(editMessage(5, 9, "edited")).resolves.toBeUndefined();
    const [url, init] = lastCall();
    expect([url, init.method]).toEqual(["/api/v1/rooms/5/messages/9", "PUT"]);
    expect(JSON.parse(init.body as string)).toEqual({ body: "edited" });
  });

  test("deleteMessage DELETEs the single message", async () => {
    mockFetch.mockResolvedValue(new Response(null, { status: 204 }));
    await expect(deleteMessage(5, 9)).resolves.toBeUndefined();
    expect(lastCall()[0]).toBe("/api/v1/rooms/5/messages/9");
    expect(lastCall()[1].method).toBe("DELETE");
  });

  test("a refusal is an ApiError carrying the status", async () => {
    mockFetch.mockResolvedValue(json(409, { type: "urn:adder:problem:thread-closed", status: 409 }));
    await expect(postMessage(5, 42, "x")).rejects.toMatchObject({ status: 409, type: "urn:adder:problem:thread-closed" });
  });

  test("the wire types are the generated schema's, so a spec change fails the compile", () => {
    // `expectTypeOf` is checked by `tsc -b` (the build and the web gate), not at run time.
    expectTypeOf<MessageResponse>().toEqualTypeOf<components["schemas"]["MessageResponse"]>();
    expectTypeOf<MessageBody>().toEqualTypeOf<components["schemas"]["MessageRequest"]>();
    expectTypeOf<MessageEditBody>().toEqualTypeOf<components["schemas"]["MessageRequest"]>();
    expectTypeOf(listMessages).returns.resolves.toEqualTypeOf<components["schemas"]["MessagePage"]>();
  });
});
