import { describe, expect, test, vi } from "vitest";
import { QueryClient } from "@tanstack/react-query";
import i18n from "../i18n";
import { ApiError } from "../api/http";
import { PIN_LIMIT } from "../api/pins";
import { canPin, pinErrorMessage, refreshAfterPinFailure } from "./pins";
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

describe("the pin limit in the words", () => {
  test("the number comes from PIN_LIMIT, in English and in Polish", async () => {
    expect(pinErrorMessage(new ApiError(409, { type: PROBLEM_TYPES.pinLimit }), t)).toContain(` ${PIN_LIMIT} pins`);
    await i18n.changeLanguage("pl");
    try {
      expect(pinErrorMessage(new ApiError(409, { type: PROBLEM_TYPES.pinLimit }), i18n.t.bind(i18n))).toBe(`Ten pokój ma już ${PIN_LIMIT} przypięć. Najpierw odepnij któreś.`);
    } finally {
      await i18n.changeLanguage("en");
    }
  });
});

describe("refreshAfterPinFailure", () => {
  const reread = async (err: unknown) => {
    const queryClient = new QueryClient();
    const invalidate = vi.spyOn(queryClient, "invalidateQueries");
    await refreshAfterPinFailure(queryClient, 12, err);
    return invalidate.mock.calls.map(([filters]) => filters?.queryKey);
  };

  test("the limit re-reads only the pins", async () => {
    expect(await reread(new ApiError(409, { type: PROBLEM_TYPES.pinLimit }))).toEqual([["rooms", "pins", 12]]);
  });

  test("a 403, a 404 and any other 409 re-read the rooms prefix", async () => {
    for (const err of [new ApiError(403, null), new ApiError(404, null), new ApiError(409, { type: PROBLEM_TYPES.roomArchived }), new ApiError(409, null)]) {
      expect(await reread(err)).toEqual([["rooms"]]);
    }
  });

  test("a busy room, a rate limit and a network failure re-read nothing", async () => {
    for (const err of [new ApiError(429, { type: PROBLEM_TYPES.roomBusy }), new ApiError(429, null), new ApiError(500, null), new Error("offline")]) {
      expect(await reread(err)).toEqual([]);
    }
  });
});
