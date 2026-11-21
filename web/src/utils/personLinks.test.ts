import { describe, expect, test } from "vitest";
import { parsePersonId, peoplePath, personPath } from "./personLinks";

describe("personLinks", () => {
  test("builds the profile path", () => {
    expect(peoplePath).toBe("/people");
    expect(personPath(7)).toBe("/people/7");
  });

  test("parsePersonId accepts a plain positive int32 and nothing else", () => {
    expect(parsePersonId("7")).toBe(7);
    expect(parsePersonId("2147483647")).toBe(2_147_483_647);
    for (const bad of [undefined, "", "0", "007", "01", "-1", "+5", "1e3", "12abc", "1.5", "2147483648", "٥"]) {
      expect(parsePersonId(bad), String(bad)).toBeNull();
    }
  });
});
