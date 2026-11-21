import { describe, expect, test } from "vitest";
import { threadRefSegments } from "./threadRefs";

/** The numbers `threadRefSegments` finds in `text`, in order (null when it finds none). */
const refs = (text: string, before = "") => threadRefSegments(text, before)?.flatMap((segment) => (segment.number === undefined ? [] : [segment.number])) ?? null;

// The thread reference grammar's own case table (1.2 A9; the SPA's alone, so there is no shared JSON): `(?<![\p{L}\p{N}#&/])#([1-9]\d{0,8})(?![\p{L}\p{N}_-])`.
const CASES: [text: string, numbers: number[] | null][] = [
  ["see #42", [42]],
  ["#42", [42]],
  ["(#42)", [42]],
  ["#42.", [42]],
  ["#42, and #7!", [42, 7]],
  ["#1", [1]],
  ["#999999999", [999999999]],
  // the left boundary: a letter, digit, hash, ampersand or slash glues the hash to something else
  ["foo#42", null],
  ["x1#42", null],
  ["##42", null],
  ["&#42;", null],
  ["https://x.example/#42", null],
  ["a/#42", null],
  ["Ł#42", null],
  // a number is plain: no zero first, no more than nine digits, nothing after it that makes it a word or a range
  ["#0", null],
  ["#007", null],
  ["#42a", null],
  ["#42_x", null],
  ["#42-7", null],
  ["#4١", null],
  ["#1234567890", null],
  ["# 42", null],
  ["#", null],
  ["no hash here", null],
  // a hashtag holds a letter: it is the hashtag plugin's, never a reference
  ["#outage", null],
  ["#a1", null],
];

describe("threadRefSegments", () => {
  test.each(CASES)("%j", (text, numbers) => {
    expect(refs(text)).toEqual(numbers);
  });

  test("cuts the text around each reference and keeps the words", () => {
    expect(threadRefSegments("Duplicate of #42, see also #7.")).toEqual([
      { text: "Duplicate of " },
      { text: "#42", number: 42 },
      { text: ", see also " },
      { text: "#7", number: 7 },
      { text: "." },
    ]);
  });

  test("a text with no reference is left as it is (null), and one with a reference is NFC-normalised", () => {
    expect(threadRefSegments("plain # text")).toBeNull();
    expect(threadRefSegments("é #42")).toEqual([{ text: "é " }, { text: "#42", number: 42 }]);
  });

  test("the character before the text (an e-mail autolink's last one) glues the hash like any other", () => {
    expect(refs("#42", "m")).toBeNull();
    expect(refs("#42", " ")).toEqual([42]);
    expect(refs("#42", "")).toEqual([42]);
  });

  test("hostile input is linear: a long run of hashes and digits answers at once (a quadratic scan of 400,000 characters would take minutes, so the margin is wide)", () => {
    const start = performance.now();
    expect(refs("#".repeat(200_000))).toBeNull();
    expect(refs("#1".repeat(100_000))).toEqual([1]); // only the first is not glued to a digit
    expect(refs("a" + "#4".repeat(100_000) + " #8")).toEqual([8]);
    expect(performance.now() - start).toBeLessThan(10_000);
  });
});
