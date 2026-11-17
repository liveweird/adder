import { describe, expect, test } from "vitest";
import { STATUS_COLOURS, statusColourKeys, statusHue } from "./workflowColours";
import en from "../locales/en/workflows.json";
import pl from "../locales/pl/workflows.json";

describe("workflowColours", () => {
  test("the vocabulary is exactly the server's six colours", () => {
    expect([...STATUS_COLOURS].sort()).toEqual(["BLUE", "GRAPE", "GRAY", "ORANGE", "RED", "TEAL"]);
  });

  test("the hue is the lowercase colour, never shade-suffixed", () => {
    expect(STATUS_COLOURS.map(statusHue)).toEqual(["gray", "blue", "grape", "teal", "orange", "red"]);
  });

  test("every colour has a name and a meaning in both languages", () => {
    for (const colour of STATUS_COLOURS) {
      const keys = statusColourKeys(colour);
      expect(keys.name).toBe(`workflows.colour.${colour}.name`);
      expect(keys.meaning).toBe(`workflows.colour.${colour}.meaning`);
      for (const bundle of [en, pl]) {
        expect(bundle.colour[colour].name).not.toBe("");
        expect(bundle.colour[colour].meaning).not.toBe("");
      }
    }
  });
});
