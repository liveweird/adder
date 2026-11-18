import { describe, expect, test } from "vitest";
import type { TFunction } from "i18next";
import { ApiError } from "../api/http";
import {
  EMPTY_ROOM_FORM,
  MAX_ROOM_DESCRIPTION_LENGTH,
  MAX_ROOM_NAME_LENGTH,
  isRoomDetailsDirty,
  isTemplateGone,
  roomCreateErrorMessage,
  roomDetailsValidation,
  roomFormValidation,
  roomSaveErrorMessage,
  toRoomCreateBody,
  toRoomDetails,
  toRoomUpdateBody,
} from "./roomForm";

// A t() that echoes its key, so the assertions name the vocabulary key a rule or a status maps to.
const t = ((key: string) => key) as unknown as TFunction;
const problem = (status: number, detail?: string) => new ApiError(status, { title: "x", status, detail });
const GONE = "Unknown or deleted workflow template id: 7";

describe("room form rules", () => {
  test("the limits mirror the server's", () => {
    expect([MAX_ROOM_NAME_LENGTH, MAX_ROOM_DESCRIPTION_LENGTH]).toEqual([80, 500]);
  });

  test("a trimmed name of 1..80 characters and a description of at most 500 pass; anything else names its key", () => {
    const rules = roomDetailsValidation(t);
    expect(rules.name("  Night Shift ")).toBeNull();
    expect(rules.name("   ")).toBe("rooms.validation.nameLength");
    expect(rules.name("x".repeat(81))).toBe("rooms.validation.nameLength");
    expect(rules.description("x".repeat(500))).toBeNull();
    expect(rules.description("x".repeat(501))).toBe("rooms.validation.descriptionLength");
  });

  test("a name with a control character is refused, like the server's sanitizeSingleLine", () => {
    const rules = roomDetailsValidation(t);
    expect(rules.name("Night\u0007Shift")).toBe("rooms.validation.nameControl");
    expect(rules.name("Night\u0085Shift")).toBe("rooms.validation.nameControl");
    expect(rules.name("Night\u007fShift")).toBe("rooms.validation.nameControl");
    // Edge whitespace is trimmed away first (as the server does), and ordinary punctuation and accents pass.
    expect(rules.name("\tŻółta zmiana — 2!\n")).toBeNull();
  });

  test("only the unknown-or-deleted-template 400 reads as a gone template", () => {
    expect(isTemplateGone(problem(400, GONE))).toBe(true);
    expect(isTemplateGone(problem(400, "Other"))).toBe(false);
    expect(isTemplateGone(problem(404, GONE))).toBe(false);
    expect(isTemplateGone(new TypeError("offline"))).toBe(false);
  });

  test("the create form also needs a template", () => {
    const rules = roomFormValidation(t);
    expect(rules.templateId(null)).toBe("rooms.validation.templateRequired");
    expect(rules.templateId("3")).toBeNull();
    expect(rules.name("")).toBe("rooms.validation.nameLength");
  });
});

describe("room form mappers", () => {
  test("a room's details become form values (no description is an empty string)", () => {
    const room = { name: "Night Shift", description: null };
    expect(toRoomDetails(room as Parameters<typeof toRoomDetails>[0])).toEqual({ name: "Night Shift", description: "" });
    expect(toRoomDetails({ ...room, description: "Handover" } as Parameters<typeof toRoomDetails>[0]).description).toBe("Handover");
    expect(EMPTY_ROOM_FORM).toEqual({ name: "", description: "", templateId: null });
  });

  test("the update body trims, and a blank description travels as null", () => {
    expect(toRoomUpdateBody({ name: " Night Shift ", description: "  " })).toEqual({ name: "Night Shift", description: null });
    expect(toRoomUpdateBody({ name: "A", description: " Handover " })).toEqual({ name: "A", description: "Handover" });
  });

  test("the create body adds the template id and the UI language", () => {
    expect(toRoomCreateBody({ name: "A", description: "", templateId: "4" }, 4, "pl")).toEqual({
      name: "A",
      description: null,
      templateId: 4,
      language: "pl",
    });
  });

  test("dirty compares the trimmed details with the room's", () => {
    const room = { name: "Night Shift", description: "Handover" };
    expect(isRoomDetailsDirty({ name: " Night Shift", description: "Handover " }, room)).toBe(false);
    expect(isRoomDetailsDirty({ name: "Night", description: "Handover" }, room)).toBe(true);
    expect(isRoomDetailsDirty({ name: "Night Shift", description: "" }, room)).toBe(true);
  });
});

describe("room save vocabularies", () => {
  test("create maps 429 and 400, and falls back per status or to the network", () => {
    expect(roomCreateErrorMessage(problem(429), t)).toBe("rooms.create.tooManyRequests");
    expect(roomCreateErrorMessage(problem(400, GONE), t)).toBe("rooms.create.templateGone");
    expect(roomCreateErrorMessage(problem(400, "Description is too long"), t)).toBe("rooms.create.invalid");
    expect(roomCreateErrorMessage(problem(400), t)).toBe("rooms.create.invalid");
    expect(roomCreateErrorMessage(problem(500), t)).toBe("common.error.createFailedStatus");
    expect(roomCreateErrorMessage(new TypeError("offline"), t)).toBe("common.error.createFailedNetwork");
  });

  test("save maps 403, 404 and 400, and falls back per status or to the network", () => {
    expect(roomSaveErrorMessage(problem(403), t)).toBe("rooms.settings.saveForbidden");
    expect(roomSaveErrorMessage(problem(404), t)).toBe("rooms.settings.saveGone");
    expect(roomSaveErrorMessage(problem(400), t)).toBe("rooms.settings.saveInvalid");
    expect(roomSaveErrorMessage(problem(500), t)).toBe("common.error.saveFailedStatus");
    expect(roomSaveErrorMessage(new TypeError("offline"), t)).toBe("common.error.saveFailedNetwork");
  });
});
