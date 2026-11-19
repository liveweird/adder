import { describe, expect, test } from "vitest";
import {
  DEFAULT_THREAD_LIST_PARAMS,
  hasListKeys,
  listSearch,
  ORDER_SORT,
  ORDERS,
  parseThreadListParams,
  rememberedQuery,
  restoreRememberedQuery,
  SCOPE_FILTER,
  SCOPES,
  writeThreadListParams,
} from "./threadListParams";

const q = (query: string) => new URLSearchParams(query);

describe("threadListParams", () => {
  test("a bare URL reads as the plain list", () => {
    expect(parseThreadListParams(q(""))).toEqual(DEFAULT_THREAD_LIST_PARAMS);
    expect(DEFAULT_THREAD_LIST_PARAMS).toEqual({ scope: "open", order: "activity", page: 1 });
  });

  test("the round trip: what a write spells, a read gives back", () => {
    for (const scope of SCOPES) {
      for (const order of ORDERS) {
        for (const page of [1, 2, 37]) {
          const written = writeThreadListParams(q(""), { scope, order, page });
          expect(parseThreadListParams(written)).toEqual({ scope, order, page });
        }
      }
    }
  });

  test("defaults are omitted from what a write spells", () => {
    expect(writeThreadListParams(q(""), { scope: "open", order: "activity", page: 1 }).toString()).toBe("");
    expect(writeThreadListParams(q(""), { scope: "closed" }).toString()).toBe("scope=closed");
    expect(writeThreadListParams(q(""), { scope: "all", order: "number", page: 3 }).toString()).toBe("scope=all&order=number&page=3");
    expect(writeThreadListParams(q("scope=closed"), { scope: "open" }).toString()).toBe("");
  });

  test.each([
    ["scope=weird", "scope"],
    ["scope=OPEN", "scope"],
    ["scope=", "scope"],
    ["order=oldest", "order"],
    ["order=status", "order"],
    ["page=0", "page"],
    ["page=-2", "page"],
    ["page=2x", "page"],
    ["page=1.5", "page"],
    ["page=1e3", "page"],
    ["page=", "page"],
    ["page=99999999999", "page"],
  ])("junk (%s) reads as the default", (query, key) => {
    const parsed = parseThreadListParams(q(query));
    expect(parsed[key as keyof typeof parsed]).toBe(DEFAULT_THREAD_LIST_PARAMS[key as keyof typeof parsed]);
  });

  test("a repeated key reads its first value", () => {
    expect(parseThreadListParams(q("scope=closed&scope=all")).scope).toBe("closed");
  });

  test("a change resets the page in the same write; a page change keeps the rest", () => {
    expect(writeThreadListParams(q("scope=closed&page=4"), { order: "number" }).toString()).toBe("scope=closed&order=number");
    expect(writeThreadListParams(q("scope=closed&page=4"), { scope: "all" }).toString()).toBe("scope=all");
    expect(writeThreadListParams(q("scope=closed&order=newest"), { page: 5 }).toString()).toBe("scope=closed&order=newest&page=5");
    expect(writeThreadListParams(q("scope=closed&page=4"), { page: 1 }).toString()).toBe("scope=closed");
  });

  test("a write drops junk list values and keeps every key that is not the list's", () => {
    expect(writeThreadListParams(q("scope=junk&message=9&focus=1&page=oops"), { order: "newest" }).toString()).toBe("message=9&focus=1&order=newest");
  });

  test("hasListKeys sees a list key, valid or not, and nothing else", () => {
    expect(hasListKeys(q(""))).toBe(false);
    expect(hasListKeys(q("message=9&focus=1"))).toBe(false);
    expect(hasListKeys(q("scope=junk"))).toBe(true);
    expect(hasListKeys(q("page=2"))).toBe(true);
  });

  test("listSearch is the normalized list keys, page included, nothing else", () => {
    expect(listSearch(q("scope=closed&page=3&message=9&order=bad")).toString()).toBe("scope=closed&page=3");
    expect(listSearch(q("message=9")).toString()).toBe("");
  });

  test("the remembered query leaves the page out, is empty for the plain list, and restores onto other keys", () => {
    expect(rememberedQuery(q("scope=closed&order=number&page=4"))).toBe("scope=closed&order=number");
    expect(rememberedQuery(q("page=4"))).toBe("");
    expect(restoreRememberedQuery(q("message=9"), "scope=closed&order=number").toString()).toBe("message=9&scope=closed&order=number");
    expect(restoreRememberedQuery(q(""), "scope=garbage").toString()).toBe("");
    // The page is never remembered, even when a stored value was edited by hand.
    expect(restoreRememberedQuery(q("page=2"), "scope=closed&page=4").toString()).toBe("scope=closed");
  });

  test("every scope and order has its server side: the closed filter and a whitelisted sort", () => {
    expect(SCOPE_FILTER).toEqual({ open: false, closed: true, all: undefined });
    expect(ORDER_SORT).toEqual({ activity: "-lastActivityAt", newest: "-createdAt", number: "number" });
  });
});
