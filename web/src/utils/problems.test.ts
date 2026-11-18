import { describe, expect, test } from "vitest";
import { ApiError } from "../api/http";
import { PROBLEM_TYPES, isProblem } from "./problems";

describe("problems", () => {
  test("the type URIs are the server's", () => {
    expect(PROBLEM_TYPES).toEqual({
      revisionConflict: "urn:adder:problem:revision-conflict",
      lastOwner: "urn:adder:problem:last-owner",
      roomArchived: "urn:adder:problem:room-archived",
      threadClosed: "urn:adder:problem:thread-closed",
      statusInUse: "urn:adder:problem:status-in-use",
      fieldTypeImmutable: "urn:adder:problem:field-type-immutable",
    });
  });

  test("isProblem matches an ApiError by its problem type only", () => {
    const stale = new ApiError(409, { type: PROBLEM_TYPES.revisionConflict });
    expect(isProblem(stale, PROBLEM_TYPES.revisionConflict)).toBe(true);
    expect(isProblem(stale, PROBLEM_TYPES.statusInUse)).toBe(false);
    expect(isProblem(new ApiError(409, null), PROBLEM_TYPES.revisionConflict)).toBe(false);
    expect(isProblem(new Error("x"), PROBLEM_TYPES.revisionConflict)).toBe(false);
    expect(isProblem(undefined, PROBLEM_TYPES.revisionConflict)).toBe(false);
  });
});
