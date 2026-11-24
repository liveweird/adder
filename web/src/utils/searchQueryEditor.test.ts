import { describe, expect, test } from "vitest";
import {
  contextualSearchToken,
  insertSearchToken,
  operatorCorrection,
  searchLookupTerm,
  searchOperatorSuggestions,
} from "./searchQueryEditor";

describe("structured search query editor", () => {
  test("finds the token at the caret without splitting a quoted value", () => {
    expect(contextualSearchToken('outage in:"Night Sh', 19)).toEqual({
      start: 7,
      end: 19,
      text: 'in:"Night Sh',
      operator: "in",
      value: '"Night Sh',
    });
    expect(contextualSearchToken("disk from:ada before:2026", 12)).toEqual({
      start: 5,
      end: 13,
      text: "from:ada",
      operator: "from",
      value: "ada",
    });
  });

  test("counts slash parity when deciding whether a quote closes a value", () => {
    const even = 'in:"C:\\\\" from:ada';
    expect(contextualSearchToken(even, even.length)).toMatchObject({ text: "from:ada", operator: "from" });
    const odd = 'in:"C:\\" from:ada';
    expect(contextualSearchToken(odd, odd.length)).toMatchObject({ start: 0, operator: "in" });
  });

  test("replaces only the active token and returns the next caret", () => {
    expect(insertSearchToken("disk fro:ada pinned", 5, 12, "from:ada@example.com")).toEqual({
      query: "disk from:ada@example.com pinned",
      caret: 25,
    });
    expect(insertSearchToken("disk", 4, 4, "has:pin")).toEqual({
      query: "disk has:pin",
      caret: 12,
    });
  });

  test("suggests operators by prefix and the has values in context", () => {
    expect(searchOperatorSuggestions("ow").map((item) => item.insert)).toEqual(["owner:"]);
    expect(searchOperatorSuggestions("has:r").map((item) => item.insert)).toEqual(["has:reaction", "has:reaction:"]);
    expect(searchOperatorSuggestions("has:").map((item) => item.insert)).toEqual([
      "has:pin",
      "has:link",
      "has:reaction",
      "has:reaction:",
      "has:emoji:",
    ]);
    expect(searchOperatorSuggestions("ordinary")).toEqual([]);
  });

  test("bounds directory lookups and skips stable ids", () => {
    expect(searchLookupTerm('"Night Shift"')).toBe("Night Shift");
    expect(searchLookupTerm("a")).toBeNull();
    expect(searchLookupTerm("#12")).toBeNull();
    expect(searchLookupTerm("a".repeat(101))).toBeNull();
  });

  test("offers only an unambiguous one-edit operator correction", () => {
    expect(operatorCorrection("disk form:ada", 13)).toEqual({
      start: 5,
      end: 13,
      replacement: "from:ada",
      operator: "from",
    });
    expect(operatorCorrection("disk befor:2026-01-01", 15)?.replacement).toBe("before:2026-01-01");
    expect(operatorCorrection("disk from:ada", 13)).toBeNull();
    expect(operatorCorrection("disk nonsense:ada", 13)).toBeNull();
  });
});
