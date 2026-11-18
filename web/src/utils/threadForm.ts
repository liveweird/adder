import type { TFunction } from "i18next";
import type { FormErrors } from "@mantine/form";
import type { RoomWorkflow } from "../api/rooms";
import type { ThreadCreateBody, ThreadFieldValues } from "../api/threads";
import type { PickedOption } from "../components/SearchPicker";
import { ApiError } from "../api/http";
import { saveErrorMessage, withServerDetail } from "./saveError";
import { isValidIsoDate } from "./isoDate";

// Server limits (threads/Thread.kt, threads/ThreadFields.kt) mirrored client-side; the rules below are identical to the server's.
export const MAX_THREAD_TITLE_LENGTH = 200;
export const MAX_MESSAGE_BODY_LENGTH = 8000;
export const MAX_TEXT_FIELD_LENGTH = 500;
export const MAX_URL_FIELD_LENGTH = 2000;
const MAX_NUMBER_TOKEN_LENGTH = 32;
const MAX_NUMBER_MAGNITUDE = 1e15;
const NUMBER_GRAMMAR = /^-?(0|[1-9]\d*)(\.\d+)?([eE][+-]?\d+)?$/;

/** A field of the room's workflow, as the form renders it (`GET /rooms/{id}/workflow` lists the active ones). */
export type WorkflowField = RoomWorkflow["fields"][number];

/**
 * One field's draft. Its kind follows the field's type: a single string for TEXT, NUMBER, DATE, SELECT (the option key) and URL (`""` = unset),
 * the chosen option keys for MULTI_SELECT, and the picked member for USER (with the label the picker shows, so it survives a re-render).
 */
export type FieldDraft =
  | { kind: "text"; value: string }
  | { kind: "multi"; value: string[] }
  | { kind: "user"; value: PickedOption | null };

/** The new-thread form: a title, the opening message (Markdown) and a draft per workflow field, keyed by the field's key. */
export type ThreadFormValues = { title: string; body: string; fields: Record<string, FieldDraft> };

export function emptyFieldDraft(field: Pick<WorkflowField, "type">): FieldDraft {
  if (field.type === "MULTI_SELECT") return { kind: "multi", value: [] };
  if (field.type === "USER") return { kind: "user", value: null };
  return { kind: "text", value: "" };
}

export function emptyThreadForm(fields: readonly WorkflowField[]): ThreadFormValues {
  return { title: "", body: "", fields: Object.fromEntries(fields.map((field) => [field.key, emptyFieldDraft(field)])) };
}

/** Whether the draft holds nothing (a blank string, no option, nobody): such a field is left out of the request. */
export function isUnsetDraft(draft: FieldDraft): boolean {
  if (draft.kind === "text") return draft.value.trim() === "";
  if (draft.kind === "multi") return draft.value.length === 0;
  return draft.value === null;
}

/**
 * The server's `sanitizeMultiLine` view of a message body: CRLF and a lone CR become LF, the leading blank lines and the trailing
 * whitespace are dropped (the first line's indentation stays, so an indented code block survives).
 */
export function normalizeBody(text: string): string {
  return text.replace(/\r\n?/g, "\n").replace(/^(?:[ \t]*\n)+/, "").trimEnd();
}

// The hidden direction and separator characters the server refuses everywhere (U+202A-202E, U+2066-2069, U+2028/2029).
const HIDDEN_FORMATTING = /[\u202A-\u202E\u2066-\u2069\u2028\u2029]/;
const CONTROL = /\p{Cc}/u;

/** A control character (a body may keep its line feeds and tabs) or one of the hidden formatting characters. */
function hasControl(text: string, allowLayout: boolean): boolean {
  const stripped = allowLayout ? text.replace(/[\n\t]/g, "") : text;
  return CONTROL.test(stripped) || HIDDEN_FORMATTING.test(text);
}

// What `java.net.URI` refuses in any component and the WHATWG parser leaves raw (it encodes space, quotes, `<`, `>` and non-ASCII, but not
// these in every component); the host check below makes sure none of them is in the host, so encoding them everywhere is safe.
const JAVA_URI_ILLEGAL = /[|{}^`\\[\] ]/g;

/**
 * The URL the server will accept, in the form to send, or null. The server parses with `java.net.URI`, which refuses several raw spellings
 * a browser takes: so the WHATWG-normalised href is sent (it percent-encodes spaces and non-ASCII, punycodes `münchen.de` and completes
 * `http:example.com` to `http://example.com/`) with `|`, `{`, `}`, `^`, backtick, `\`, `[` and `]` encoded as well, which the WHATWG
 * parser leaves in places. Valid means absolute http(s) with a non-empty host of ASCII letters, digits, `-` and `.` (the server's rule,
 * so an underscore host is a client error here rather than a 400 there), no user name or password (`https://trusted.example@evil.example/`
 * opens evil.example) and no control or hidden direction character (the server refuses both).
 */
export function normalizedHttpUrl(text: string): string | null {
  if (hasControl(text, false)) return null;
  try {
    const url = new URL(text);
    const web = url.protocol === "http:" || url.protocol === "https:";
    if (!web || url.username !== "" || url.password !== "" || !/^[A-Za-z0-9.-]+$/.test(url.hostname)) return null;
    return url.href.replace(JAVA_URI_ILLEGAL, (char) => `%${char.charCodeAt(0).toString(16).toUpperCase().padStart(2, "0")}`);
  } catch {
    return null;
  }
}

function isNumberToken(text: string): boolean {
  if (text.length > MAX_NUMBER_TOKEN_LENGTH || !NUMBER_GRAMMAR.test(text)) return false;
  const value = Number(text);
  return Number.isFinite(value) && Math.abs(value) <= MAX_NUMBER_MAGNITUDE;
}

/** The error sentence for one text-kind value that is set, or null when it is valid for the field's type. */
function textFieldError(field: Pick<WorkflowField, "type" | "label">, value: string, t: TFunction): string | null {
  const text = value.trim();
  switch (field.type) {
    case "TEXT":
      if (hasControl(text, false)) return t("threads.validation.fieldControl");
      return text.length > MAX_TEXT_FIELD_LENGTH ? t("threads.validation.textLength", { max: MAX_TEXT_FIELD_LENGTH }) : null;
    case "NUMBER":
      return isNumberToken(text) ? null : t("threads.validation.number");
    case "DATE":
      return isValidIsoDate(text) ? null : t("threads.validation.date");
    case "URL": {
      const href = normalizedHttpUrl(text);
      return href !== null && href.length <= MAX_URL_FIELD_LENGTH ? null : t("threads.validation.url", { field: field.label, max: MAX_URL_FIELD_LENGTH });
    }
    default:
      return null;
  }
}

/** The sentence for a message body (an opening message or a reply) the server would refuse after its own normalisation, or null when it is valid. */
export function messageBodyError(text: string, t: TFunction): string | null {
  const body = normalizeBody(text);
  if (body.length < 1 || body.length > MAX_MESSAGE_BODY_LENGTH) return t("threads.validation.bodyLength", { max: MAX_MESSAGE_BODY_LENGTH });
  return hasControl(body, true) ? t("threads.validation.bodyControl") : null;
}

/** The sentence for a title the server would refuse (1 to 200 characters, a single line without control or hidden formatting characters), or null. */
export function titleError(title: string, t: TFunction): string | null {
  const trimmed = title.trim();
  if (trimmed.length < 1 || trimmed.length > MAX_THREAD_TITLE_LENGTH) return t("threads.validation.titleLength", { max: MAX_THREAD_TITLE_LENGTH });
  return hasControl(trimmed, false) ? t("threads.validation.titleControl") : null;
}

/** The sentence for a field draft that IS set and breaks its type's rules (only the text-kind drafts can), or null. */
export function setFieldDraftError(field: Pick<WorkflowField, "type" | "label">, draft: FieldDraft, t: TFunction): string | null {
  return draft.kind === "text" ? textFieldError(field, draft.value, t) : null;
}

/** The form's rules, one function over all values (the field drafts are keyed by the workflow's own keys). */
export function threadFormValidation(t: TFunction, fields: readonly WorkflowField[]) {
  return (values: ThreadFormValues): FormErrors => {
    const errors: FormErrors = {};
    const titleMessage = titleError(values.title, t);
    if (titleMessage) errors.title = titleMessage;
    const bodyError = messageBodyError(values.body, t);
    if (bodyError) errors.body = bodyError;
    for (const field of fields) {
      const draft = values.fields[field.key];
      if (isUnsetDraft(draft)) {
        if (field.required) errors[`fields.${field.key}`] = t("threads.validation.fieldRequired");
      } else {
        const message = setFieldDraftError(field, draft, t);
        if (message) errors[`fields.${field.key}`] = message;
      }
    }
    return errors;
  };
}

/** The wire value of a set draft (NUMBER and USER travel as JSON numbers, MULTI_SELECT as its keys). */
export function draftValue(field: WorkflowField, draft: FieldDraft): ThreadFieldValues[string] {
  if (draft.kind === "multi") return draft.value;
  if (draft.kind === "user") return Number(draft.value?.value);
  const text = draft.value.trim();
  if (field.type === "NUMBER") return Number(text);
  // The form's rules have passed, so a URL normalises (the fallback only keeps the type total).
  return field.type === "URL" ? (normalizedHttpUrl(text) ?? text) : text;
}

/** The create request: the trimmed title, the normalised body and only the fields that hold something. */
export function toThreadCreateBody(values: ThreadFormValues, fields: readonly WorkflowField[]): ThreadCreateBody {
  const set: ThreadFieldValues = {};
  for (const field of fields) {
    const draft = values.fields[field.key];
    if (!isUnsetDraft(draft)) set[field.key] = draftValue(field, draft);
  }
  return { title: values.title.trim(), body: normalizeBody(values.body), ...(Object.keys(set).length > 0 ? { fields: set } : {}) };
}

/** Whether a 400's problem `detail` is about a field of the room's workflow ("Field 'x' is required", "Unknown field 'x'", "Field 'x' is retired"). */
export function isFieldProblem(err: unknown): boolean {
  return err instanceof ApiError && err.status === 400 && /\bfield '/i.test(err.detail ?? "");
}

/**
 * The start-a-thread save vocabulary; a 403, 404 or 409 also re-reads the room, so the caller's standing and the archived state follow
 * the truth. A 400 adds the server's own sentence (it names the field key): to the generic words, or, when it concerns a field, to the
 * note that the room's fields may have changed since the form was opened.
 */
export function threadCreateErrorMessage(err: unknown, t: TFunction): string {
  const message = saveErrorMessage(err, t, {
    forbidden: "threads.new.forbidden",
    notFound: "threads.new.gone",
    conflict: "threads.new.archivedConflict",
    tooManyRequests: "threads.new.tooManyRequests",
    invalid: isFieldProblem(err) ? "threads.new.invalidFields" : "threads.new.invalid",
    failedStatus: "common.error.createFailedStatus",
    failed: "common.error.createFailedNetwork",
  });
  return withServerDetail(message, err);
}

/** Whether the draft holds nothing at all (no title, no message, no field): such a form is clean, and nothing is kept for it. */
export function isThreadDraftEmpty(values: ThreadFormValues): boolean {
  return values.title.trim() === "" && values.body.trim() === "" && Object.values(values.fields).every(isUnsetDraft);
}

/** A kept draft laid over the room's current fields: a field that is gone is dropped, a new one starts empty, and a changed type starts over. */
export function reconcileThreadDraft(kept: ThreadFormValues | null, fields: readonly WorkflowField[]): ThreadFormValues {
  const empty = emptyThreadForm(fields);
  if (kept === null) return empty;
  const merged = Object.fromEntries(fields.map((field) => {
    const earlier = kept.fields[field.key];
    return [field.key, earlier !== undefined && earlier.kind === empty.fields[field.key].kind ? earlier : empty.fields[field.key]];
  }));
  return { title: kept.title, body: kept.body, fields: merged };
}
