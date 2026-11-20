import type { TFunction } from "i18next";
import type { RoomCreateBody, RoomResponse, RoomUpdateBody } from "../api/rooms";
import type { SupportedLanguage } from "../i18n";
import { descriptionRule, nameRule } from "./formRules";
import { PROBLEM_TYPES, isProblem } from "./problems";
import { saveErrorMessage } from "./saveError";

// Server limits (rooms/Room.kt) mirrored client-side.
export const MAX_ROOM_NAME_LENGTH = 80;
export const MAX_ROOM_DESCRIPTION_LENGTH = 500;

/** The room details: what the settings form edits and the create form starts with. */
export type RoomDetails = { name: string; description: string };

export type RoomFormValues = RoomDetails & {
  /** The picked workflow template's id (a string, the Select's value type); null until one is picked. */
  templateId: string | null;
};

export const EMPTY_ROOM_FORM: RoomFormValues = { name: "", description: "", templateId: null };

export function toRoomDetails(room: RoomResponse): RoomDetails {
  return { name: room.name, description: room.description ?? "" };
}

/** Validation rules for the name and description (mirrors the server's checks). */
export function roomDetailsValidation(t: TFunction) {
  const lengthRule = nameRule(t, "rooms.validation.nameLength", MAX_ROOM_NAME_LENGTH);
  return {
    // The server's `sanitizeSingleLine`: no ISO control characters (`\p{Cc}` is the same set as Java's `isISOControl`).
    name: (value: string) => (/\p{Cc}/u.test(value.trim()) ? t("rooms.validation.nameControl") : lengthRule(value)),
    description: descriptionRule(t, "rooms.validation.descriptionLength", MAX_ROOM_DESCRIPTION_LENGTH),
  };
}

/** The create form's rules: the details plus a required template. */
export function roomFormValidation(t: TFunction) {
  return {
    ...roomDetailsValidation(t),
    templateId: (value: string | null) => (value === null ? t("rooms.validation.templateRequired") : null),
  };
}

/** A blank description travels as null (the server stores NULL, never ""). */
export function toRoomUpdateBody(values: RoomDetails): RoomUpdateBody {
  const description = values.description.trim();
  return { name: values.name.trim(), description: description === "" ? null : description };
}

/** `language` is the UI language the room's labels are copied in (the server resolves every template label to it). */
export function toRoomCreateBody(values: RoomFormValues, templateId: number, language: SupportedLanguage): RoomCreateBody {
  return { ...toRoomUpdateBody(values), templateId, language };
}

/** Dirty = the trimmed details differ from what the room has (so edited back is clean). */
export function isRoomDetailsDirty(values: RoomDetails, room: RoomDetails): boolean {
  return values.name.trim() !== room.name.trim() || values.description.trim() !== room.description.trim();
}

/** The server's typed 400 for a `templateId` that is unknown or deleted (`RoomService.create`); any other 400 is something else. */
export function isTemplateGone(err: unknown): boolean {
  return isProblem(err, PROBLEM_TYPES.templateGone);
}

/** The create save vocabulary; a 409 (the name clash) is handled by the caller as a field error. */
export function roomCreateErrorMessage(err: unknown, t: TFunction): string {
  if (isTemplateGone(err)) return t("rooms.create.templateGone");
  return saveErrorMessage(err, t, {
    tooManyRequests: "rooms.create.tooManyRequests",
    invalid: "rooms.create.invalid",
    failedStatus: "common.error.createFailedStatus",
    failed: "common.error.createFailedNetwork",
  });
}

/** The details save vocabulary; the name clash and `room-archived` 409s are the caller's. */
export function roomSaveErrorMessage(err: unknown, t: TFunction): string {
  return saveErrorMessage(err, t, {
    forbidden: "rooms.settings.saveForbidden",
    notFound: "rooms.settings.saveGone",
    invalid: "rooms.settings.saveInvalid",
    failedStatus: "common.error.saveFailedStatus",
    failed: "common.error.saveFailedNetwork",
  });
}
