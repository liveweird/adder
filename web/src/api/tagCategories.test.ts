import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { jsonResponse } from "../test/http";
import { createTagCategory, deleteTagCategory, getTagCategories, updateTagCategory } from "./tagCategories";

describe("tag registry API wrappers", () => {
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

  const category = (id: number) => ({ id, name: `Category ${id}`, description: null, revision: 1, tags: [`tag-${id}`] });

  test("the registry is read whole in one page of the maximum size and unwrapped from its items", async () => {
    mockFetch.mockResolvedValue(jsonResponse(200, { items: [category(1)], page: 1, pageSize: 100, total: 1 }));
    expect(await getTagCategories()).toEqual([category(1)]);
    expect(mockFetch).toHaveBeenCalledTimes(1);
    expect(lastCall()[0]).toBe("/api/v1/tag-categories?page=1&pageSize=100");
  });

  test("the next page is fetched while total says more exist, and the pages concatenate in order", async () => {
    mockFetch
      .mockResolvedValueOnce(jsonResponse(200, { items: [category(1), category(2)], page: 1, pageSize: 100, total: 3 }))
      .mockResolvedValueOnce(jsonResponse(200, { items: [category(3)], page: 2, pageSize: 100, total: 3 }));
    expect((await getTagCategories()).map((c) => c.id)).toEqual([1, 2, 3]);
    expect(mockFetch.mock.calls.map(([url]) => url)).toEqual([
      "/api/v1/tag-categories?page=1&pageSize=100",
      "/api/v1/tag-categories?page=2&pageSize=100",
    ]);
  });

  test("an empty page ends the walk even when total claims more, so a shrinking registry never loops", async () => {
    mockFetch
      .mockResolvedValueOnce(jsonResponse(200, { items: [category(1)], page: 1, pageSize: 100, total: 2 }))
      .mockResolvedValueOnce(jsonResponse(200, { items: [], page: 2, pageSize: 100, total: 1 }));
    expect((await getTagCategories()).map((c) => c.id)).toEqual([1]);
    expect(mockFetch).toHaveBeenCalledTimes(2);
  });

  test("a category that shifts into the next page is kept once", async () => {
    mockFetch
      .mockResolvedValueOnce(jsonResponse(200, { items: [category(1), category(2)], page: 1, pageSize: 100, total: 3 }))
      .mockResolvedValueOnce(jsonResponse(200, { items: [category(2), category(3)], page: 2, pageSize: 100, total: 3 }));
    expect((await getTagCategories()).map((c) => c.id)).toEqual([1, 2, 3]);
  });

  test("create, replace and delete address the collection and the member", async () => {
    mockFetch.mockResolvedValue(jsonResponse(201, { id: 5, name: "N", description: null, tags: ["a"] }));
    await createTagCategory({ name: "N", description: null, tags: ["a"] });
    expect(lastCall()[0]).toBe("/api/v1/tag-categories");
    expect(lastCall()[1]).toMatchObject({ method: "POST", body: JSON.stringify({ name: "N", description: null, tags: ["a"] }) });
    mockFetch.mockResolvedValue(new Response(null, { status: 204 }));
    await updateTagCategory(5, 7, { name: "M", tags: ["b"] });
    expect(lastCall()[0]).toBe("/api/v1/tag-categories/5");
    expect(lastCall()[1].method).toBe("PUT");
    await deleteTagCategory(5, 8);
    expect(lastCall()[1].method).toBe("DELETE");
  });

  test("the replace and the delete carry the revision the admin loaded the category at", async () => {
    mockFetch.mockResolvedValue(new Response(null, { status: 204 }));
    await updateTagCategory(5, 7, { name: "M", tags: ["b"] });
    expect(new Headers(lastCall()[1].headers).get("X-Expected-Revision")).toBe("7");
    await deleteTagCategory(5, 8);
    expect(new Headers(lastCall()[1].headers).get("X-Expected-Revision")).toBe("8");
  });
});
