import { describe, expect, test } from "vitest";
import { resolvedLabels } from "./workflowLabels";

describe("resolvedLabels", () => {
  test("uses the language's label and falls back to EN per label", () => {
    expect(resolvedLabels([{ en: "Open", pl: "Otwarte" }, { en: "Done" }], "pl")).toEqual(["Otwarte", "Done"]);
    expect(resolvedLabels([{ en: "Open", pl: "Otwarte" }, { en: "Done" }], "en")).toEqual(["Open", "Done"]);
  });

  test("a blank translation counts as missing", () => {
    expect(resolvedLabels([{ en: "Open", pl: "   " }, { en: "Done", pl: "" }], "pl")).toEqual(["Open", "Done"]);
  });

  test("when the mix collides case-insensitively the whole list falls back to EN", () => {
    // "Review" keeps its English word in PL and another status is translated to the same word, differing in case only.
    expect(resolvedLabels([{ en: "Review" }, { en: "Check", pl: "review" }, { en: "Open", pl: "Otwarte" }], "pl")).toEqual(["Review", "Check", "Open"]);
  });

  test("an empty list stays empty", () => {
    expect(resolvedLabels([], "pl")).toEqual([]);
  });
});
