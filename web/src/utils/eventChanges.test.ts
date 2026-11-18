import { describe, expect, test } from "vitest";
import i18n from "../i18n";
import { changeLine } from "./eventChanges";

const t = i18n.t;

describe("changeLine", () => {
  test("both sides: from and to", () => {
    expect(changeLine("Severity", "S3", "S1", t)).toBe("Severity: S3 → S1");
  });

  test("an unset old value reads as set, an unset new value as cleared", () => {
    expect(changeLine("Severity", undefined, "S1", t)).toBe("Severity: set to S1");
    expect(changeLine("Severity", "S3", undefined, t)).toBe("Severity: cleared");
  });

  test("neither side carries a value: no line (the sentence above names the field)", () => {
    expect(changeLine("Severity", undefined, undefined, t)).toBeNull();
    expect(changeLine("Severity", "", "", t)).toBeNull();
  });
});
