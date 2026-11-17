import { describe, expect, test } from "vitest";
import { MAX_KEY_LENGTH, slugify } from "./slug";

const GRAMMAR = /^[a-z][a-z0-9]*(-[a-z0-9]+)*$/;

describe("slugify", () => {
  test.each([
    ["In progress", "in-progress"],
    ["Won't do", "won-t-do"],
    ["  Triaż  ", "triaz"],
    ["Zażółć gęślą jaźń", "zazolc-gesla-jazn"],
    ["Łódź", "lodz"],
    ["S1 — critical", "s1-critical"],
    ["a---b___c", "a-b-c"],
    ["ETA", "eta"],
    ["Größe", "grosse"],
  ])("%s becomes %s", (label, key) => {
    expect(slugify(label)).toBe(key);
    expect(GRAMMAR.test(key)).toBe(true);
  });

  test("a key must start with a letter: leading digits and hyphens are dropped", () => {
    expect(slugify("2nd line")).toBe("nd-line");
    expect(slugify("-- 42 --")).toBe("");
  });

  test("a label without a Latin letter yields an empty key", () => {
    expect(slugify("")).toBe("");
    expect(slugify("🔥")).toBe("");
    expect(slugify("日本語")).toBe("");
  });

  test("the result is cut to the key limit and never ends in a hyphen", () => {
    const long = slugify("word ".repeat(30));
    expect(long.length).toBeLessThanOrEqual(MAX_KEY_LENGTH);
    expect(GRAMMAR.test(long)).toBe(true);
    // The cut falls exactly after a hyphen: "aaaa…" (39 letters) + " b" would cut to "aaaa…-".
    expect(slugify(`${"a".repeat(39)} b`)).toBe("a".repeat(39));
    expect(slugify("abcdef ghij", 7)).toBe("abcdef");
  });
});
