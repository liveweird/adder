import { describe, expect, test } from "vitest";
import { liveTagNames, MAX_THREAD_TAGS, retiredTagNames, sameTagSet, tagOptionGroups } from "./threadTags";

describe("thread tag helpers", () => {
  test("the picker's options are one group per category with its live tags, empty categories left out", () => {
    const categories = [
      { id: 1, name: "Kind", description: null, revision: 1, tags: ["decision", "question"] },
      { id: 2, name: "Empty", description: null, revision: 1, tags: [] },
      { id: 3, name: "Area", description: null, revision: 1, tags: ["data"] },
    ];
    expect(tagOptionGroups(categories)).toEqual([
      { group: "Kind", items: [{ value: "decision", label: "decision" }, { value: "question", label: "question" }] },
      { group: "Area", items: [{ value: "data", label: "data" }] },
    ]);
  });

  test("the editor starts from the live tags: a retired one is dropped from what a save sends and listed apart", () => {
    const tags = [{ name: "old", retired: true }, { name: "data", retired: false }, { name: "gone", retired: true }];
    expect(liveTagNames(tags)).toEqual(["data"]);
    expect(retiredTagNames(tags)).toEqual(["old", "gone"]);
    expect(liveTagNames([])).toEqual([]);
  });

  test("a set is the same whatever its order, and a repeat is never the same", () => {
    expect(sameTagSet(["a", "b"], ["b", "a"])).toBe(true);
    expect(sameTagSet(["a"], ["a", "b"])).toBe(false);
    expect(sameTagSet(["a", "a"], ["a", "b"])).toBe(false);
    expect(sameTagSet([], [])).toBe(true);
  });

  test("the cap is the server's 20", () => {
    expect(MAX_THREAD_TAGS).toBe(20);
  });
});
