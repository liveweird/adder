import { describe, expect, test } from "vitest";
import { ApiError } from "../api/http";
import { canManageRoom, isAccessRefusal } from "./roomAccess";

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

describe("isAccessRefusal", () => {
  test("a 403, 404 or 409 means the caller's standing moved; other failures do not", () => {
    for (const status of [403, 404, 409]) expect(isAccessRefusal(new ApiError(status, null))).toBe(true);
    for (const status of [400, 429, 500]) expect(isAccessRefusal(new ApiError(status, null))).toBe(false);
    expect(isAccessRefusal(new Error("network"))).toBe(false);
  });
});
