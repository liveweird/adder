import { describe, expect, test } from "vitest";
import type { TFunction } from "i18next";
import { ApiError } from "../api/http";
import { PROBLEM_TYPES } from "./problems";
import { roomWorkflowSaveErrorMessage } from "./roomWorkflowForm";

// A translator that returns the key (and the interpolated keys), so the assertions name the vocabulary entry.
const t = ((key: string, values?: { keys?: string }) => (values?.keys === undefined ? key : `${key}:${values.keys}`)) as unknown as TFunction;
const problem = (status: number, type?: string, keys?: string[]) => new ApiError(status, { title: "x", status, ...(type ? { type } : {}), ...(keys ? { keys } : {}) });

describe("roomWorkflowSaveErrorMessage", () => {
  test("the typed 409s say what to change", () => {
    expect(roomWorkflowSaveErrorMessage(problem(409, PROBLEM_TYPES.roomArchived), t)).toBe("rooms.workflow.saveArchived");
    expect(roomWorkflowSaveErrorMessage(problem(409, PROBLEM_TYPES.statusInUse, ["triage"]), t)).toBe("rooms.workflow.saveStatusInUse");
    expect(roomWorkflowSaveErrorMessage(problem(409, PROBLEM_TYPES.fieldTypeImmutable, ["sev", "eta"]), t)).toBe("rooms.workflow.saveFieldType:sev, eta");
  });

  test("the untyped 409 is the key budget; 403, 404 and 400 have their own words", () => {
    expect(roomWorkflowSaveErrorMessage(problem(409), t)).toBe("rooms.workflow.saveKeyBudget");
    expect(roomWorkflowSaveErrorMessage(problem(403), t)).toBe("rooms.workflow.saveForbidden");
    expect(roomWorkflowSaveErrorMessage(problem(404), t)).toBe("rooms.workflow.saveGone");
    expect(roomWorkflowSaveErrorMessage(problem(400), t)).toBe("rooms.workflow.saveInvalid");
  });

  test("a 429 is the shared too-fast words", () => {
    expect(roomWorkflowSaveErrorMessage(new ApiError(429, { title: "x", status: 429 }, 2000), t)).toBe("common.error.tooFast");
    expect(roomWorkflowSaveErrorMessage(problem(429), t)).toBe("common.error.tooFastSoon");
  });

  test("anything else falls back per status or to the network", () => {
    expect(roomWorkflowSaveErrorMessage(problem(500), t)).toBe("common.error.saveFailedStatus");
    expect(roomWorkflowSaveErrorMessage(new TypeError("offline"), t)).toBe("common.error.saveFailedNetwork");
  });
});
