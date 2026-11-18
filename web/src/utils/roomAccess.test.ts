import { describe, expect, test } from "vitest";
import { canManageRoom } from "./roomAccess";

describe("canManageRoom", () => {
  test("the room's owner and an administrator manage it; a plain member or an outsider does not", () => {
    expect(canManageRoom({ role: "OWNER" }, false)).toBe(true);
    expect(canManageRoom({ role: "MEMBER" }, false)).toBe(false);
    expect(canManageRoom({ role: "MEMBER" }, true)).toBe(true);
    // An ADMIN outside the room has no role at all.
    expect(canManageRoom({ role: null }, true)).toBe(true);
    expect(canManageRoom({ role: null }, false)).toBe(false);
  });
});
