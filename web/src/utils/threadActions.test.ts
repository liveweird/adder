import { describe, expect, test } from "vitest";
import i18n from "../i18n";
import { ApiError } from "../api/http";
import { isStaleWrite, threadOwnerErrorMessage, threadStatusErrorMessage } from "./threadActions";

const t = i18n.t;

describe("threadStatusErrorMessage", () => {
  test("403 says who may close or reopen, 400 that the status is gone, 409 that the room was archived", () => {
    expect(threadStatusErrorMessage(new ApiError(403, null), t)).toContain("Only the thread owner, a room owner or an administrator");
    expect(threadStatusErrorMessage(new ApiError(400, null), t)).toBe("That status is no longer available in this room. Pick another one.");
    expect(threadStatusErrorMessage(new ApiError(409, null), t)).toContain("archived in the meantime");
    expect(threadStatusErrorMessage(new ApiError(404, null), t)).toContain("no longer exists");
    expect(threadStatusErrorMessage(new ApiError(500, null), t)).toBe("Action failed (500)");
    expect(threadStatusErrorMessage(new TypeError("x"), t)).toBe("Action failed. Check your connection and try again.");
  });
});

describe("threadOwnerErrorMessage", () => {
  test("a 400 is a person who is no longer a member; the two 409s are told apart by problem type", () => {
    expect(threadOwnerErrorMessage(new ApiError(400, null), t)).toContain("not an active member");
    expect(threadOwnerErrorMessage(new ApiError(409, { type: "urn:adder:problem:thread-closed" }), t)).toContain("was closed in the meantime");
    expect(threadOwnerErrorMessage(new ApiError(409, { type: "urn:adder:problem:room-archived" }), t)).toContain("was archived in the meantime");
    expect(threadOwnerErrorMessage(new ApiError(409, null), t)).toContain("changed in the meantime");
    expect(threadOwnerErrorMessage(new ApiError(403, null), t)).toContain("can pass the thread on");
  });
});

describe("isStaleWrite", () => {
  test("a 400, 403, 404 or 409 says the page's picture is stale; a 500 or a network failure does not", () => {
    for (const status of [400, 403, 404, 409]) expect(isStaleWrite(new ApiError(status, null))).toBe(true);
    expect(isStaleWrite(new ApiError(500, null))).toBe(false);
    expect(isStaleWrite(new TypeError("x"))).toBe(false);
  });
});
