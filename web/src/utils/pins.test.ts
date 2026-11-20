import { describe, expect, test } from "vitest";
import i18n from "../i18n";
import { ApiError } from "../api/http";
import { canPin, pinErrorMessage } from "./pins";
import { PROBLEM_TYPES } from "./problems";

const t = i18n.t.bind(i18n);

describe("canPin", () => {
  test("any member of a live room; not an ADMIN outside it, not an archived room", () => {
    expect(canPin({ role: "MEMBER", archived: false })).toBe(true);
    expect(canPin({ role: "OWNER", archived: false })).toBe(true);
    expect(canPin({ role: null, archived: false })).toBe(false);
    expect(canPin({ role: "OWNER", archived: true })).toBe(false);
  });
});

describe("pinErrorMessage", () => {
  test("the room's 51st pin has its own words, apart from the other 409s", () => {
    expect(pinErrorMessage(new ApiError(409, { type: PROBLEM_TYPES.pinLimit }), t)).toBe("This room already has 50 pins. Unpin one first.");
    expect(pinErrorMessage(new ApiError(409, { type: PROBLEM_TYPES.roomArchived }), t)).toBe("This room was archived in the meantime, so its pins can no longer be changed.");
    expect(pinErrorMessage(new ApiError(409, null), t)).toBe("The room changed in the meantime. Reload the page and try again.");
  });

  test("403, 404 and 429 say what happened; the rest is the generic failure", () => {
    expect(pinErrorMessage(new ApiError(403, null), t)).toBe("Only members of the room can pin or unpin.");
    expect(pinErrorMessage(new ApiError(404, null), t)).toContain("no longer exists");
    expect(pinErrorMessage(new ApiError(429, null), t)).toContain("too quickly");
    expect(pinErrorMessage(new ApiError(500, null), t)).toBe("Action failed (500)");
    expect(pinErrorMessage(new Error("offline"), t)).toBe("Action failed. Check your connection and try again.");
  });

  test("a busy room is its own message, whatever the status vocabulary says", () => {
    expect(pinErrorMessage(new ApiError(429, { type: PROBLEM_TYPES.roomBusy }), t)).toBe("The room is busy right now — try again in a moment.");
  });
});
