import type { TFunction } from "i18next";
import type { components } from "../api/schema";
import { SUPPORTED_LANGUAGES, type SupportedLanguage } from "../i18n";
import { MAX_KEY_LENGTH, slugify } from "./slug";
import type { StatusColour } from "./workflowColours";

// ── Wire shapes ──────────────────────────────────────────────────────────────────────────────────────────────────────
// Both documents and both bodies ARE the generated `schema.ts` types, so the compiler checks them against the spec: the loaders
// below take the spec's statuses and fields, and `toTemplateBody` / `toRoomWorkflowBody` return the spec's requests.
// `workflowForm.test.ts` pins the vocabularies (colour, category, field type) equal to the spec's. A room's workflow carries ONE
// plain-text label per item, a template a language map `{en, pl?}`.

export type StatusCategory = "OPEN" | "CLOSED";

export const FIELD_TYPES = ["TEXT", "NUMBER", "DATE", "SELECT", "MULTI_SELECT", "USER", "URL"] as const;

export type FieldType = (typeof FIELD_TYPES)[number];

/** Only the select kinds carry options (the server's `FieldType.takesOptions`). */
export function takesOptions(type: FieldType): boolean {
  return type === "SELECT" || type === "MULTI_SELECT";
}

type Schemas = components["schemas"];

/** A template label: English required, every other language optional (and omitted when blank). */
type TemplateLabel = Schemas["WorkflowLabel"];

type StatusBody<L> = { key: string; label: L; colour: StatusColour; category: StatusCategory; initial: boolean };
type FieldBody<L> = {
  key: string;
  label: L;
  type: FieldType;
  required: boolean;
  options: { key: string; label: L }[];
};

/** `GET /rooms/{id}/workflow`: `threadCount` is how many threads sit in the status (always 0 until 0.3). */
export type RoomWorkflow = Schemas["RoomWorkflow"];

/** `PUT /rooms/{id}/workflow` (with `X-Expected-Revision`); `replacements` is `{retiredKey: replacementKey}`. */
export type RoomWorkflowBody = Schemas["RoomWorkflowUpdate"];

/** The document inside a workflow template (`statuses` and `fields` of `GET /workflow-templates/{id}`). */
export type WorkflowTemplateDocument = Pick<Schemas["WorkflowTemplateResponse"], "statuses" | "fields">;

/** `POST`/`PUT /workflow-templates`: a blank description travels as null. */
export type WorkflowTemplateBody = Schemas["WorkflowTemplateRequest"];

// ── Limits (mirror server/src/main/kotlin/workflows/Workflow.kt and WorkflowRules.kt) ────────────────────────────────

const MIN_STATUSES = 2;
export const MAX_STATUSES = 20;
export const MAX_FIELDS = 20;
const MIN_OPTIONS = 1;
export const MAX_OPTIONS = 50;
const MAX_LABEL_LENGTH = 60;

/** Lower-case slug: starts with a letter, then letters/digits, single hyphens between groups (`in-progress`, `s1`). */
const KEY_GRAMMAR = /^[a-z][a-z0-9]*(-[a-z0-9]+)*$/;

// ── Drafts ───────────────────────────────────────────────────────────────────────────────────────────────────────────

/**
 * The room's single-label slot: a room's one plain-text label (in whatever language its creator wrote) and a template's
 * English label both live here. Every draft has a slot per supported language; a room only ever fills (and sends) this one.
 */
export const PRIMARY_LANGUAGE: SupportedLanguage = "en";

type Labels = Record<SupportedLanguage, string>;

/**
 * What every editable row has. `id` is the local React key (stable across reorders, unlike the index; the idea of Lettuce's
 * `dictionaryForm`); `persisted` rows came from the server, so their key (and a field's type) is read-only; `keyTouched`
 * stops the key from following the first label any more.
 */
export type DraftRow = { id: string; key: string; keyTouched: boolean; persisted: boolean; labels: Labels };

export type StatusDraft = DraftRow & { colour: StatusColour; category: StatusCategory; initial: boolean; threadCount: number };
export type FieldDraft = DraftRow & { type: FieldType; required: boolean; options: DraftRow[] };

/**
 * A persisted status the user removed in this draft: the server retires it. `index` is where Undo puts it back;
 * `replacementId` is the DRAFT ID of the status its threads go to (null while none are asked about: no threads, or not asked
 * yet). By id, so a target that is later removed or re-categorised is noticed (`replacementTarget`), never silently kept
 * under a stale key.
 */
type RetiredStatus = { status: StatusDraft; index: number; replacementId: string | null };

export type WorkflowDraft = { statuses: StatusDraft[]; fields: FieldDraft[]; retired: RetiredStatus[] };

let idCounter = 0;
function newId(): string {
  idCounter += 1;
  return `wf-draft-${idCounter}`;
}

function blankLabels(): Labels {
  return Object.fromEntries(SUPPORTED_LANGUAGES.map((l) => [l, ""])) as Labels;
}

function newRow(): DraftRow {
  return { id: newId(), key: "", keyTouched: false, persisted: false, labels: blankLabels() };
}

export function newOptionDraft(): DraftRow {
  return newRow();
}

export function newStatusDraft(): StatusDraft {
  return { ...newRow(), colour: "GRAY", category: "OPEN", initial: false, threadCount: 0 };
}

export function newFieldDraft(): FieldDraft {
  return { ...newRow(), type: "TEXT", required: false, options: [] };
}

/** What a row is called in a sentence or an aria-label: its (primary) label, or its key while the label is blank. */
export function displayName(row: DraftRow): string {
  return row.labels[PRIMARY_LANGUAGE].trim() || row.key.trim();
}

export function emptyWorkflowDraft(): WorkflowDraft {
  return { statuses: [], fields: [], retired: [] };
}

function loadedRow(key: string, label: string | TemplateLabel): DraftRow {
  const given: Partial<Record<string, string | null>> = typeof label === "string" ? { [PRIMARY_LANGUAGE]: label } : label;
  const labels = Object.fromEntries(SUPPORTED_LANGUAGES.map((l) => [l, given[l] ?? ""])) as Labels;
  return { id: newId(), key, keyTouched: true, persisted: true, labels };
}

function loadWorkflow<L extends string | TemplateLabel>(
  statuses: (StatusBody<L> & { threadCount?: number })[],
  fields: FieldBody<L>[],
): WorkflowDraft {
  return {
    statuses: statuses.map((s) => ({
      ...loadedRow(s.key, s.label),
      colour: s.colour,
      category: s.category,
      initial: s.initial,
      threadCount: s.threadCount ?? 0,
    })),
    fields: fields.map((f) => ({
      ...loadedRow(f.key, f.label),
      type: f.type,
      required: f.required,
      options: f.options.map((o) => loadedRow(o.key, o.label)),
    })),
    retired: [],
  };
}

/** The loaded room workflow as editable drafts (every row persisted). */
export function toRoomWorkflowDraft(workflow: RoomWorkflow): WorkflowDraft {
  return loadWorkflow(workflow.statuses, workflow.fields);
}

/** A loaded template document as editable drafts (every row persisted; no thread counts). */
export function toTemplateWorkflowDraft(document: WorkflowTemplateDocument): WorkflowDraft {
  return loadWorkflow(document.statuses, document.fields);
}

// ── Row edits ────────────────────────────────────────────────────────────────────────────────────────────────────────

/** Sets one language's label. The key follows the first label while the row is new and its key untouched. */
export function withLabel<T extends DraftRow>(row: T, language: SupportedLanguage, value: string): T {
  const labels = { ...row.labels, [language]: value };
  const derive = language === PRIMARY_LANGUAGE && !row.persisted && !row.keyTouched;
  return derive ? { ...row, labels, key: slugify(value) } : { ...row, labels };
}

export function withKey<T extends DraftRow>(row: T, key: string): T {
  return { ...row, key, keyTouched: true };
}

/** `list` with the item at `index` moved by `delta` places; out-of-range moves are no-ops. */
export function moveItem<T>(list: T[], index: number, delta: number): T[] {
  const target = index + delta;
  if (target < 0 || target >= list.length) return list;
  const next = [...list];
  [next[index], next[target]] = [next[target], next[index]];
  return next;
}

export function replaceItem<T extends { id: string }>(list: T[], next: T): T[] {
  return list.map((item) => (item.id === next.id ? next : item));
}

export function removeItem<T extends { id: string }>(list: T[], id: string): T[] {
  return list.filter((item) => item.id !== id);
}

/** The one initial status across the list. */
export function setInitialStatus(statuses: StatusDraft[], id: string): StatusDraft[] {
  return statuses.map((s) => ({ ...s, initial: s.id === id }));
}

/**
 * The statuses a retired status's threads may move to: the remaining PERSISTED ones of the same category. Persisted rows have a
 * valid, unique, read-only key (a new row may have none yet, or a duplicate), and are the only targets the server knows.
 */
export function replacementCandidates(draft: WorkflowDraft, retired: StatusDraft): StatusDraft[] {
  return draft.statuses.filter((s) => s.persisted && s.id !== retired.id && s.category === retired.category);
}

/**
 * Where a retired status's threads really go: its chosen target, but only while that target is still in the document and
 * still has the retired status's category (a workflow edit never closes or reopens a thread behind its history). Undefined when
 * none was chosen or the choice went stale — the server would answer `status-in-use`, so the editor asks again.
 */
export function replacementTarget(draft: WorkflowDraft, retired: RetiredStatus): StatusDraft | undefined {
  const target = draft.statuses.find((s) => s.id === retired.replacementId);
  return target?.category === retired.status.category ? target : undefined;
}

/**
 * Removes a status from the draft. A new row just vanishes; a persisted one is retired by the server, so it is remembered
 * (with where its threads go, when asked) for the notice, Undo and the body's `replacements`.
 */
export function removeStatus(draft: WorkflowDraft, id: string, replacementId: string | null): WorkflowDraft {
  const index = draft.statuses.findIndex((s) => s.id === id);
  const status = draft.statuses[index];
  const retired = status.persisted ? [...draft.retired, { status, index, replacementId }] : draft.retired;
  return { ...draft, statuses: removeItem(draft.statuses, id), retired };
}

/** Undo of a removal: the status returns at its old position (clamped) and its replacement is forgotten. */
export function restoreStatus(draft: WorkflowDraft, id: string): WorkflowDraft {
  const entry = draft.retired.find((r) => r.status.id === id);
  if (!entry) return draft;
  const statuses = [...draft.statuses];
  statuses.splice(Math.min(entry.index, statuses.length), 0, entry.status);
  return { ...draft, statuses, retired: draft.retired.filter((r) => r.status.id !== id) };
}

/** Records where the threads of an already-removed status go (the 409 `status-in-use` follow-up). */
export function setReplacement(draft: WorkflowDraft, id: string, replacementId: string): WorkflowDraft {
  return { ...draft, retired: draft.retired.map((r) => (r.status.id === id ? { ...r, replacementId } : r)) };
}

// ── Wire bodies ──────────────────────────────────────────────────────────────────────────────────────────────────────

const roomLabel = (row: DraftRow): string => row.labels[PRIMARY_LANGUAGE].trim();

function templateLabel(row: DraftRow): TemplateLabel {
  const label: TemplateLabel = { en: roomLabel(row) };
  for (const language of SUPPORTED_LANGUAGES) {
    const value = row.labels[language].trim();
    if (language !== PRIMARY_LANGUAGE && value) label[language] = value;
  }
  return label;
}

/**
 * `{retiredKey: replacementKey}` for the retired statuses that have a live replacement (`replacementTarget`). An entry only
 * counts while the retired key is really absent from the document (the key may have been re-added). A stale target is
 * dropped — the server then answers `status-in-use` and the editor asks again.
 */
function replacementsOf(draft: WorkflowDraft): Record<string, string> | undefined {
  const present = new Set(draft.statuses.map((s) => s.key.trim()));
  const entries = draft.retired.flatMap((r) => {
    const target = replacementTarget(draft, r);
    return target && !present.has(r.status.key.trim()) ? [[r.status.key.trim(), target.key.trim()] as const] : [];
  });
  return entries.length > 0 ? Object.fromEntries(entries) : undefined;
}

function documentOf<L>(draft: WorkflowDraft, label: (row: DraftRow) => L) {
  return {
    statuses: draft.statuses.map((s): StatusBody<L> => ({
      key: s.key.trim(),
      label: label(s),
      colour: s.colour,
      category: s.category,
      initial: s.initial,
    })),
    fields: draft.fields.map((f): FieldBody<L> => ({
      key: f.key.trim(),
      label: label(f),
      type: f.type,
      required: f.required,
      options: takesOptions(f.type) ? f.options.map((o) => ({ key: o.key.trim(), label: label(o) })) : [],
    })),
  };
}

/** The room workflow PUT body: trimmed single labels, no local ids, `replacements` only when there are any. */
export function toRoomWorkflowBody(draft: WorkflowDraft): RoomWorkflowBody {
  const replacements = replacementsOf(draft);
  return { ...documentOf(draft, roomLabel), ...(replacements ? { replacements } : {}) };
}

/** The template body: `{en, pl?}` labels (a blank translation is omitted) and a blank description as null. */
export function toTemplateBody(meta: { name: string; description: string }, draft: WorkflowDraft): WorkflowTemplateBody {
  const description = meta.description.trim();
  return { name: meta.name.trim(), description: description === "" ? null : description, ...documentOf(draft, templateLabel) };
}

/**
 * The dirty check: what would be sent, in both languages, compared as text — so a draft edited back to its loaded state is
 * clean again (Lettuce's payload compare), whatever its local ids and key flags. A page's own metadata (a template's name)
 * is compared by the page.
 */
export function isWorkflowDirty(draft: WorkflowDraft, baseline: WorkflowDraft): boolean {
  const snapshot = (d: WorkflowDraft) => JSON.stringify([documentOf(d, templateLabel), replacementsOf(d)]);
  return snapshot(draft) !== snapshot(baseline);
}

// ── Validation (mirrors server/src/main/kotlin/workflows/WorkflowRules.kt) ───────────────────────────────────────────

type ValidationName =
  | "keyInvalid"
  | "keyDuplicate"
  | "labelRequired"
  | "labelTooLong"
  | "labelControl"
  | "labelDuplicate"
  | "statusCount"
  | "fieldCount"
  | "optionCount"
  | "initialCount"
  | "initialOpen"
  | "closedRequired";

/** An i18n key under `workflows.validation` plus its interpolation values; the component translates it. */
export type ValidationMessage = { key: `workflows.validation.${ValidationName}`; params?: Record<string, number> };

function message(name: ValidationName, params?: Record<string, number>): ValidationMessage {
  return { key: `workflows.validation.${name}`, params };
}

/** The translated text of a message, or undefined (what Mantine's `error` prop wants for "no error"). */
export function validationText(t: TFunction, m: ValidationMessage | undefined): string | undefined {
  return m ? t(m.key, m.params) : undefined;
}

export type RowErrors = {
  key?: ValidationMessage;
  labels?: Partial<Record<SupportedLanguage, ValidationMessage>>;
  /** A select field's option count. */
  options?: ValidationMessage;
};

export type WorkflowErrors = {
  statuses?: ValidationMessage;
  fields?: ValidationMessage;
  initial?: ValidationMessage;
  closed?: ValidationMessage;
  /** Row errors by draft id (statuses, fields and options share one id space); only rows with an error appear. */
  rows: Record<string, RowErrors>;
};

export function hasWorkflowErrors(errors: WorkflowErrors): boolean {
  return Boolean(errors.statuses ?? errors.fields ?? errors.initial ?? errors.closed) || Object.keys(errors.rows).length > 0;
}

/** Labels count code points (an emoji is one), like the server's `requireLabel`. */
const codePoints = (s: string): number => Array.from(s).length;

function keyError(row: DraftRow, seen: Set<string>): ValidationMessage | undefined {
  const key = row.key.trim();
  if (key.length < 1 || key.length > MAX_KEY_LENGTH || !KEY_GRAMMAR.test(key)) return message("keyInvalid", { max: MAX_KEY_LENGTH });
  if (seen.has(key)) return message("keyDuplicate");
  seen.add(key);
  return undefined;
}

/** The server's `sanitizeSingleLine` rule: no ISO control character (tab, newline, U+0000-001F, U+007F-009F) in a label. */
const hasControlCharacter = (s: string): boolean =>
  Array.from(s).some((ch) => {
    const c = ch.codePointAt(0) as number;
    return c < 0x20 || (c >= 0x7f && c <= 0x9f);
  });

/**
 * The first rule a label breaks, per language: required (English only), no control characters, at most 60 code points, and
 * unique within its list (`seen`, per language: the server's `requireUniqueLabels` — case-insensitive after trimming, blanks
 * skipped, so the same text under two languages is fine and the later row carries the flag).
 */
function labelErrors(row: DraftRow, seen: Record<string, Set<string>>): RowErrors["labels"] {
  const out: NonNullable<RowErrors["labels"]> = {};
  for (const language of SUPPORTED_LANGUAGES) {
    const value = row.labels[language].trim();
    if (value === "") {
      if (language === PRIMARY_LANGUAGE) out[language] = message("labelRequired");
    } else if (hasControlCharacter(value)) {
      out[language] = message("labelControl");
    } else if (codePoints(value) > MAX_LABEL_LENGTH) {
      out[language] = message("labelTooLong", { max: MAX_LABEL_LENGTH });
    } else if (seen[language].has(value.toLowerCase())) {
      out[language] = message("labelDuplicate");
    } else {
      seen[language].add(value.toLowerCase());
    }
  }
  return Object.keys(out).length > 0 ? out : undefined;
}

/** Key and label errors for each row of one list (keys and labels unique within the list; the later duplicate carries the flag). */
function validateRows(rows: DraftRow[], into: WorkflowErrors["rows"]): void {
  const seen = new Set<string>();
  const seenLabels = Object.fromEntries(SUPPORTED_LANGUAGES.map((l) => [l, new Set<string>()]));
  for (const row of rows) {
    const key = keyError(row, seen);
    const labels = labelErrors(row, seenLabels);
    if (key || labels) into[row.id] = { ...into[row.id], ...(key ? { key } : {}), ...(labels ? { labels } : {}) };
  }
}

/** Every rule the server's kernel would answer 400 for, as messages per row and per list. */
export function validateWorkflowDraft(draft: WorkflowDraft): WorkflowErrors {
  const rows: WorkflowErrors["rows"] = {};
  const errors: WorkflowErrors = { rows };
  const { statuses, fields } = draft;

  if (statuses.length < MIN_STATUSES || statuses.length > MAX_STATUSES) {
    errors.statuses = message("statusCount", { min: MIN_STATUSES, max: MAX_STATUSES });
  }
  validateRows(statuses, rows);
  const initial = statuses.filter((s) => s.initial);
  if (initial.length !== 1) errors.initial = message("initialCount");
  else if (initial[0].category !== "OPEN") errors.initial = message("initialOpen");
  if (!statuses.some((s) => s.category === "CLOSED")) errors.closed = message("closedRequired");

  if (fields.length > MAX_FIELDS) errors.fields = message("fieldCount", { max: MAX_FIELDS });
  validateRows(fields, rows);
  for (const field of fields.filter((f) => takesOptions(f.type))) {
    if (field.options.length < MIN_OPTIONS || field.options.length > MAX_OPTIONS) {
      rows[field.id] = { ...rows[field.id], options: message("optionCount", { min: MIN_OPTIONS, max: MAX_OPTIONS }) };
    }
    validateRows(field.options, rows);
  }
  return errors;
}
