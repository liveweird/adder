import { describe, expect, test } from "vitest";
import i18n from "../i18n";
import { ApiError } from "../api/http";
import { teamFormValidation, teamSaveErrorMessage, toTeamBody, toTeamFormValues } from "./teamForm";

const t = i18n.t;

describe("teamForm", () => {
  test("validation mirrors the server's length rules", () => {
    const rules = teamFormValidation(t);
    expect(rules.name("")).toBe("Name must be 1–100 characters");
    expect(rules.name("  ")).not.toBeNull();
    expect(rules.name("x".repeat(101))).not.toBeNull();
    expect(rules.name("Payments")).toBeNull();
    expect(rules.description("d".repeat(501))).not.toBeNull();
    expect(rules.description("")).toBeNull();
  });

  test("the handle rules mirror the server's grammar, length and reserved words", () => {
    const { handle } = teamFormValidation(t);
    for (const ok of ["", "  ", "ab", "a1", "on-call", "pay-2-go", "a" + "0".repeat(29), "  on-call "]) expect(handle(ok), `'${ok}'`).toBeNull();
    for (const bad of ["a", "a".repeat(31), "1abc", "-ab", "ab-", "a--b", "a_b", "a b", "żółw", "Payments", "ON-CALL"]) {
      expect(handle(bad), `'${bad}'`).toBe("Use 2–30 lowercase letters, digits and single hyphens, starting with a letter");
    }
    for (const reserved of ["everyone", "watchers", "observers", "here", "all", "channel", "room", "team"]) {
      expect(handle(reserved), reserved).toBe("This word is reserved — choose another handle");
    }
    expect(handle("teams")).toBeNull();
  });

  test("the body trims and sends a blank description as null; values round-trip from a response", () => {
    const none = { handle: "", managerId: null, managerLabel: null };
    expect(toTeamBody({ name: "  Payments ", description: "   ", ...none })).toEqual({
      name: "Payments",
      description: null,
      handle: null,
      managerId: null,
    });
    expect(toTeamBody({ name: "A", description: " movers ", ...none, handle: " on-call " })).toEqual({
      name: "A",
      description: "movers",
      handle: "on-call",
      managerId: null,
    });
    expect(toTeamBody({ name: "A", description: "", handle: "", managerId: "7", managerLabel: "Ann (a@x.io)" })).toEqual({
      name: "A",
      description: null,
      handle: null,
      managerId: 7,
    });
    expect(
      toTeamFormValues({
        id: 1,
        name: "A",
        description: null,
        handle: null,
        managerId: null,
        managerName: null,
        members: [],
        createdAt: 0,
        updatedAt: 0,
      }),
    ).toEqual({ name: "A", description: "", handle: "", managerId: null, managerLabel: null });
    expect(
      toTeamFormValues({
        id: 1,
        name: "A",
        description: null,
        handle: "on-call",
        managerId: 7,
        managerName: "Ann Manager",
        members: [],
        createdAt: 0,
        updatedAt: 0,
      }),
    ).toEqual({ name: "A", description: "", handle: "on-call", managerId: "7", managerLabel: "Ann Manager" });
  });

  test("save errors map to the fixed vocabulary", () => {
    expect(teamSaveErrorMessage(new ApiError(403, null), t)).toBe(t("teams.saveForbidden"));
    expect(teamSaveErrorMessage(new ApiError(404, null), t)).toBe(t("teams.saveGone"));
    expect(teamSaveErrorMessage(new ApiError(400, null), t)).toBe(t("teams.saveInvalid"));
    expect(teamSaveErrorMessage(new ApiError(500, null), t)).toBe("Save failed (500)");
    expect(teamSaveErrorMessage(new DOMException("t", "TimeoutError"), t)).toBe(t("common.error.timeout"));
    expect(teamSaveErrorMessage(new Error("net"), t)).toBe(t("common.error.saveFailedNetwork"));
  });
});
