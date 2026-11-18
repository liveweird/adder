import type { TFunction } from "i18next";
import type { ThreadFieldValues, ThreadResponse, ThreadUpdateBody } from "../api/threads";
import { PROBLEM_TYPES, isProblem } from "./problems";
import { saveErrorMessage, withServerDetail } from "./saveError";
import { draftValue, emptyFieldDraft, isUnsetDraft, setFieldDraftError, titleError, type FieldDraft, type WorkflowField } from "./threadForm";

/** The details editor's draft: the title and one draft per ACTIVE field of the room's workflow, keyed by the field's key. */
export type ThreadDetailsValues = { title: string; fields: Record<string, FieldDraft> };

/** The draft a stored value reads as (a USER's label is the display name the thread carries for it). A missing or `null` value is unset. */
function draftFromStored(field: WorkflowField, value: ThreadFieldValues[string] | undefined, userNames: ThreadResponse["fieldUserNames"]): FieldDraft {
  if (value === undefined || value === null) return emptyFieldDraft(field);
  if (field.type === "MULTI_SELECT") return { kind: "multi", value: Array.isArray(value) ? [...value] : [] };
  if (field.type === "USER") return { kind: "user", value: { value: String(value), label: userNames[String(value)] ?? `#${String(value)}` } };
  return { kind: "text", value: String(value) };
}

/** The editor's starting draft for a thread, over the fields of the workflow it was read with. */
export function detailsFromThread(thread: Pick<ThreadResponse, "title" | "fields" | "fieldUserNames">, fields: readonly WorkflowField[]): ThreadDetailsValues {
  return {
    title: thread.title,
    fields: Object.fromEntries(fields.map((field) => [field.key, draftFromStored(field, thread.fields[field.key], thread.fieldUserNames)])),
  };
}

/** Whether two drafts of one field say the same thing (what the server would call "unchanged"): both unset, the same text once trimmed, the same options (as a set), the same person. */
function sameDraft(a: FieldDraft, b: FieldDraft): boolean {
  if (isUnsetDraft(a) && isUnsetDraft(b)) return true;
  if (a.kind === "text" && b.kind === "text") return a.value.trim() === b.value.trim();
  // A set: the same options in another order are the same choice (the picker's order is the order they were ticked in).
  if (a.kind === "multi" && b.kind === "multi") return a.value.length === b.value.length && a.value.every((key) => b.value.includes(key));
  if (a.kind === "user" && b.kind === "user") return a.value?.value === b.value?.value;
  return false;
}

/** Whether the draft differs from what it was seeded with: the editor's dirty check (the title is compared trimmed, as the server stores it). */
export function isDetailsDirty(draft: ThreadDetailsValues, seed: ThreadDetailsValues): boolean {
  return draft.title.trim() !== seed.title.trim() || Object.keys(draft.fields).some((key) => !sameDraft(draft.fields[key], seed.fields[key] ?? draft.fields[key]));
}

/**
 * The update request. The server MERGES `fields` over the stored values (an omitted key keeps its value), so only the fields the person changed
 * are sent, and a field they CLEARED is sent as `null` — never omitted, which would silently keep the old value.
 */
export function toDetailsBody(draft: ThreadDetailsValues, seed: ThreadDetailsValues, fields: readonly WorkflowField[]): ThreadUpdateBody {
  const changed: ThreadFieldValues = {};
  for (const field of fields) {
    const next = draft.fields[field.key];
    if (sameDraft(next, seed.fields[field.key] ?? next)) continue;
    changed[field.key] = isUnsetDraft(next) ? null : draftValue(field, next);
  }
  return { title: draft.title.trim(), ...(Object.keys(changed).length > 0 ? { fields: changed } : {}) };
}

/**
 * The editor's rules, the server's: a good title; a CHANGED field that is set must satisfy its type; a required field that was set cannot be
 * cleared (one that was never set may stay unset). An unchanged value is not judged: a stale one (a removed option, a user who left) never
 * blocks a save, which is the server's own rule. Keyed `title` and `fields.<key>`.
 */
export function detailsErrors(draft: ThreadDetailsValues, seed: ThreadDetailsValues, fields: readonly WorkflowField[], t: TFunction): Record<string, string> {
  const errors: Record<string, string> = {};
  const title = titleError(draft.title, t);
  if (title) errors.title = title;
  for (const field of fields) {
    const next = draft.fields[field.key];
    const before = seed.fields[field.key] ?? next;
    if (sameDraft(next, before)) continue;
    const message = isUnsetDraft(next) ? (field.required && !isUnsetDraft(before) ? t("threads.validation.fieldRequired") : null) : setFieldDraftError(field, next, t);
    if (message) errors[`fields.${field.key}`] = message;
  }
  return errors;
}

/**
 * The details save vocabulary (a `revision-conflict` is the conflict alert's, not this). A 400 adds the server's own sentence, which names the
 * field key; the two 409s that are about the thread and the room are told apart by their problem type. The caller re-reads the room on a
 * 404 or a 409 (`isAccessRefusal`), so the controls follow the truth.
 */
export function threadDetailsErrorMessage(err: unknown, t: TFunction): string {
  const conflict = isProblem(err, PROBLEM_TYPES.threadClosed)
    ? "threads.details.error.closed"
    : isProblem(err, PROBLEM_TYPES.roomArchived)
      ? "threads.details.error.archived"
      : "threads.details.error.conflict";
  const message = saveErrorMessage(err, t, {
    notFound: "threads.details.error.gone",
    conflict,
    invalid: "threads.details.error.invalid",
    failedStatus: "common.error.saveFailedStatus",
    failed: "common.error.saveFailedNetwork",
  });
  return withServerDetail(message, err);
}
