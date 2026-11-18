import { describe, expect, expectTypeOf, test } from "vitest";
import type { components } from "../api/schema";
import i18n from "../i18n";
import type { StatusColour } from "./workflowColours";
import {
  displayName,
  emptyWorkflowDraft,
  hasWorkflowErrors,
  isWorkflowDirty,
  moveItem,
  newFieldDraft,
  newOptionDraft,
  newStatusDraft,
  removeItem,
  removeStatus,
  replaceItem,
  replacementCandidates,
  replacementTarget,
  restoreStatus,
  setInitialStatus,
  setReplacement,
  takesOptions,
  toRoomWorkflowBody,
  toRoomWorkflowDraft,
  toTemplateBody,
  toTemplateWorkflowDraft,
  validateWorkflowDraft,
  validationText,
  withKey,
  withLabel,
  type FieldDraft,
  type FieldType,
  type RoomWorkflow,
  type RoomWorkflowBody,
  type StatusCategory,
  type StatusDraft,
  type WorkflowDraft,
  type WorkflowTemplateDocument,
} from "./workflowForm";

const ROOM: RoomWorkflow = {
  revision: 3,
  statuses: [
    { key: "open", label: "Open", colour: "GRAY", category: "OPEN", initial: true, threadCount: 0 },
    { key: "doing", label: "Doing", colour: "BLUE", category: "OPEN", initial: false, threadCount: 4 },
    { key: "done", label: "Done", colour: "TEAL", category: "CLOSED", initial: false, threadCount: 2 },
    { key: "dropped", label: "Dropped", colour: "RED", category: "CLOSED", initial: false, threadCount: 0 },
  ],
  fields: [
    { key: "sev", label: "Severity", type: "SELECT", required: true, options: [{ key: "s1", label: "S1" }, { key: "s2", label: "S2" }] },
    { key: "note", label: "Note", type: "TEXT", required: false, options: [] },
  ],
};

const TEMPLATE: WorkflowTemplateDocument = {
  statuses: [
    { key: "open", label: { en: "Open", pl: "Otwarte" }, colour: "GRAY", category: "OPEN", initial: true },
    { key: "done", label: { en: "Done" }, colour: "TEAL", category: "CLOSED", initial: false },
    { key: "nope", label: { en: "Nope" }, colour: "RED", category: "CLOSED", initial: false },
  ],
  fields: [{ key: "sev", label: { en: "Severity", pl: "Ważność" }, type: "MULTI_SELECT", required: false, options: [{ key: "s1", label: { en: "S1", pl: "P1" } }] }],
};

const status = (d: WorkflowDraft, key: string): StatusDraft => d.statuses.find((s) => s.key === key)!;
const field = (d: WorkflowDraft, key: string): FieldDraft => d.fields.find((f) => f.key === key)!;
const withStatuses = (d: WorkflowDraft, statuses: StatusDraft[]): WorkflowDraft => ({ ...d, statuses });

describe("loading drafts", () => {
  test("a room workflow becomes persisted, key-touched rows with the thread counts", () => {
    const d = toRoomWorkflowDraft(ROOM);
    expect(d.statuses.map((s) => [s.key, s.labels.en, s.labels.pl, s.threadCount, s.persisted, s.keyTouched])).toEqual([
      ["open", "Open", "", 0, true, true],
      ["doing", "Doing", "", 4, true, true],
      ["done", "Done", "", 2, true, true],
      ["dropped", "Dropped", "", 0, true, true],
    ]);
    expect(field(d, "sev").options.map((o) => [o.key, o.labels.en, o.persisted])).toEqual([["s1", "S1", true], ["s2", "S2", true]]);
    expect(d.retired).toEqual([]);
    expect(new Set([...d.statuses, ...d.fields, ...field(d, "sev").options].map((r) => r.id)).size).toBe(8);
  });

  test("a template document keeps both languages; an absent or null translation is a blank slot", () => {
    const d = toTemplateWorkflowDraft(TEMPLATE);
    expect(d.statuses.map((s) => [s.labels.en, s.labels.pl])).toEqual([["Open", "Otwarte"], ["Done", ""], ["Nope", ""]]);
    expect(d.statuses.every((s) => s.threadCount === 0 && s.persisted)).toBe(true);
    expect(field(d, "sev").options[0].labels).toEqual({ en: "S1", pl: "P1" });
  });

  test("new rows have defaults and unique ids", () => {
    const [s1, s2] = [newStatusDraft(), newStatusDraft()];
    expect(s1).toMatchObject({ key: "", colour: "GRAY", category: "OPEN", initial: false, threadCount: 0, persisted: false, keyTouched: false });
    expect(newFieldDraft()).toMatchObject({ type: "TEXT", required: false, options: [], persisted: false });
    expect(new Set([s1.id, s2.id, newOptionDraft().id, newFieldDraft().id]).size).toBe(4);
    expect(emptyWorkflowDraft()).toEqual({ statuses: [], fields: [], retired: [] });
  });

  test("only the select kinds take options", () => {
    expect(["TEXT", "NUMBER", "DATE", "SELECT", "MULTI_SELECT", "USER", "URL"].filter((t) => takesOptions(t as never))).toEqual(["SELECT", "MULTI_SELECT"]);
  });

  test("displayName is the label, or the key while the label is blank", () => {
    expect(displayName({ ...newStatusDraft(), key: "k", labels: { en: " Hi ", pl: "" } })).toBe("Hi");
    expect(displayName({ ...newStatusDraft(), key: "k" })).toBe("k");
  });
});

describe("row edits", () => {
  test("the key follows the first label while the row is new and untouched", () => {
    let row = newStatusDraft();
    row = withLabel(row, "en", "In progress");
    expect(row.key).toBe("in-progress");
    row = withLabel(row, "en", "In progress now");
    expect(row.key).toBe("in-progress-now");
    expect(row.labels.en).toBe("In progress now");
  });

  test("a translation never moves the key", () => {
    const row = withLabel(withLabel(newStatusDraft(), "en", "Open"), "pl", "Otwarte");
    expect(row.key).toBe("open");
    expect(row.labels).toEqual({ en: "Open", pl: "Otwarte" });
  });

  test("an edited key stops following the label", () => {
    const row = withLabel(withKey(withLabel(newStatusDraft(), "en", "Open"), "mine"), "en", "Other");
    expect(row).toMatchObject({ key: "mine", keyTouched: true });
  });

  test("a persisted row's key never follows its label", () => {
    const row = status(toRoomWorkflowDraft(ROOM), "open");
    expect(withLabel(row, "en", "Brand new").key).toBe("open");
  });

  test("moveItem swaps neighbours and ignores out-of-range moves", () => {
    expect(moveItem([1, 2, 3], 1, -1)).toEqual([2, 1, 3]);
    expect(moveItem([1, 2, 3], 1, 1)).toEqual([1, 3, 2]);
    const list = [1, 2];
    expect(moveItem(list, 0, -1)).toBe(list);
    expect(moveItem(list, 1, 1)).toBe(list);
  });

  test("replaceItem and removeItem work by id", () => {
    const [a, b] = [newStatusDraft(), newStatusDraft()];
    expect(replaceItem([a, b], { ...b, key: "x" })).toEqual([a, { ...b, key: "x" }]);
    expect(removeItem([a, b], a.id)).toEqual([b]);
  });

  test("setInitialStatus makes exactly one status initial", () => {
    const d = toRoomWorkflowDraft(ROOM);
    const next = setInitialStatus(d.statuses, status(d, "doing").id);
    expect(next.filter((s) => s.initial).map((s) => s.key)).toEqual(["doing"]);
  });
});

describe("retiring statuses", () => {
  test("removing a new row just drops it", () => {
    const d = withStatuses(toRoomWorkflowDraft(ROOM), [newStatusDraft()]);
    const next = removeStatus(d, d.statuses[0].id, null);
    expect(next.statuses).toEqual([]);
    expect(next.retired).toEqual([]);
  });

  test("removing a persisted row remembers it, its position and its replacement", () => {
    const d = toRoomWorkflowDraft(ROOM);
    const next = removeStatus(d, status(d, "doing").id, status(d, "open").id);
    expect(next.statuses.map((s) => s.key)).toEqual(["open", "done", "dropped"]);
    expect(next.retired).toEqual([{ status: status(d, "doing"), index: 1, replacementId: status(d, "open").id }]);
  });

  test("candidates are the remaining persisted statuses of the same category — never a new row", () => {
    const d = toRoomWorkflowDraft(ROOM);
    const withNew = withStatuses(d, [...d.statuses, { ...newStatusDraft(), category: "CLOSED" }, { ...newStatusDraft(), category: "OPEN", key: "open" }]);
    expect(replacementCandidates(withNew, status(d, "done")).map((s) => s.key)).toEqual(["dropped"]);
    expect(replacementCandidates(withNew, status(d, "doing")).map((s) => s.key)).toEqual(["open"]);
    expect(replacementCandidates(d, status(d, "done")).map((s) => s.key)).toEqual(["dropped"]);
    expect(replacementCandidates(d, status(d, "doing")).map((s) => s.key)).toEqual(["open"]);
  });

  test("a replacement is live only while its target is in the document with the retired status's category", () => {
    const d = toRoomWorkflowDraft(ROOM);
    const doing = status(d, "doing");
    const open = status(d, "open");
    const retired = removeStatus(d, doing.id, open.id);
    expect(replacementTarget(retired, retired.retired[0])?.id).toBe(open.id);
    // The target is removed: stale. Undoing that removal makes the choice live again.
    const targetGone = removeStatus(retired, open.id, null);
    expect(replacementTarget(targetGone, targetGone.retired[0])).toBeUndefined();
    expect(replacementTarget(restoreStatus(targetGone, open.id), targetGone.retired[0])?.id).toBe(open.id);
    // The target is re-categorised: stale.
    const recategorised = withStatuses(retired, retired.statuses.map((s) => (s.id === open.id ? { ...s, category: "CLOSED" as const } : s)));
    expect(replacementTarget(recategorised, recategorised.retired[0])).toBeUndefined();
    expect(toRoomWorkflowBody(recategorised).replacements).toBeUndefined();
    // No choice at all.
    const unasked = removeStatus(d, doing.id, null);
    expect(replacementTarget(unasked, unasked.retired[0])).toBeUndefined();
  });

  test("restoreStatus puts the row back at its place and forgets the replacement", () => {
    const d = toRoomWorkflowDraft(ROOM);
    const removed = removeStatus(d, status(d, "doing").id, status(d, "open").id);
    const back = restoreStatus(removed, status(d, "doing").id);
    expect(back.statuses.map((s) => s.key)).toEqual(["open", "doing", "done", "dropped"]);
    expect(back.retired).toEqual([]);
  });

  test("restoreStatus clamps the position and ignores an unknown id", () => {
    const d = toRoomWorkflowDraft(ROOM);
    const removed = removeStatus(removeStatus(d, status(d, "dropped").id, null), status(d, "open").id, null);
    // "dropped" was at index 3, but only "doing" and "done" remain: it returns last.
    expect(restoreStatus(removed, status(d, "dropped").id).statuses.map((s) => s.key)).toEqual(["doing", "done", "dropped"]);
    expect(restoreStatus(removed, "nope")).toBe(removed);
  });

  test("setReplacement records where an already-removed status's threads go", () => {
    const d = toRoomWorkflowDraft(ROOM);
    const removed = removeStatus(d, status(d, "doing").id, null);
    const next = setReplacement(removed, status(d, "doing").id, status(d, "open").id);
    expect(next.retired[0].replacementId).toBe(status(d, "open").id);
    expect(toRoomWorkflowBody(next).replacements).toEqual({ doing: "open" });
  });
});

describe("wire bodies", () => {
  test("the room body trims labels, drops local state and sends no replacements when there are none", () => {
    const d = toRoomWorkflowDraft(ROOM);
    const edited = withStatuses(d, d.statuses.map((s) => withLabel(s, "en", ` ${s.labels.en} `)));
    const body = toRoomWorkflowBody(edited);
    expect(body).toEqual({
      statuses: [
        { key: "open", label: "Open", colour: "GRAY", category: "OPEN", initial: true },
        { key: "doing", label: "Doing", colour: "BLUE", category: "OPEN", initial: false },
        { key: "done", label: "Done", colour: "TEAL", category: "CLOSED", initial: false },
        { key: "dropped", label: "Dropped", colour: "RED", category: "CLOSED", initial: false },
      ],
      fields: [
        { key: "sev", label: "Severity", type: "SELECT", required: true, options: [{ key: "s1", label: "S1" }, { key: "s2", label: "S2" }] },
        { key: "note", label: "Note", type: "TEXT", required: false, options: [] },
      ],
    });
    expect("replacements" in body).toBe(false);
  });

  test("options travel only for the select kinds", () => {
    const d = toRoomWorkflowDraft(ROOM);
    const asText = { ...d, fields: d.fields.map((f) => (f.key === "sev" ? { ...f, type: "TEXT" as const } : f)) };
    expect(toRoomWorkflowBody(asText).fields[0].options).toEqual([]);
    // …and come back untouched when the type returns to a select kind.
    expect(toRoomWorkflowBody({ ...asText, fields: asText.fields.map((f) => ({ ...f, type: "MULTI_SELECT" as const })) }).fields[0].options).toHaveLength(2);
  });

  test("replacements are keyed by retired key and only count while they can be honoured", () => {
    const d = toRoomWorkflowDraft(ROOM);
    const retired = removeStatus(removeStatus(d, status(d, "doing").id, status(d, "open").id), status(d, "done").id, status(d, "dropped").id);
    expect(toRoomWorkflowBody(retired).replacements).toEqual({ doing: "open", done: "dropped" });
    // The target was removed too: the entry is dropped (the server then answers status-in-use and the editor asks again).
    const targetGone = removeStatus(retired, status(retired, "dropped").id, null);
    expect(toRoomWorkflowBody(targetGone).replacements).toEqual({ doing: "open" });
    // The retired key was re-added as a new row: it is not retired any more.
    const readded = withStatuses(retired, [...retired.statuses, { ...newStatusDraft(), key: "doing" }]);
    expect(toRoomWorkflowBody(readded).replacements).toEqual({ done: "dropped" });
    // A retired status without a replacement contributes nothing.
    expect(toRoomWorkflowBody(removeStatus(d, status(d, "dropped").id, null)).replacements).toBeUndefined();
  });

  test("the template body carries {en, pl?} labels, omits blank translations and nulls a blank description", () => {
    const d = toTemplateWorkflowDraft(TEMPLATE);
    const body = toTemplateBody({ name: "  Incident ", description: "   " }, d);
    expect(body.name).toBe("Incident");
    expect(body.description).toBeNull();
    expect(body.statuses.map((s) => s.label)).toEqual([{ en: "Open", pl: "Otwarte" }, { en: "Done" }, { en: "Nope" }]);
    expect(body.fields[0].options).toEqual([{ key: "s1", label: { en: "S1", pl: "P1" } }]);
    expect(toTemplateBody({ name: "X", description: " about " }, d).description).toBe("about");
  });
});

describe("the dirty check", () => {
  test("an untouched draft is clean, whatever its local ids", () => {
    expect(isWorkflowDirty(toRoomWorkflowDraft(ROOM), toRoomWorkflowDraft(ROOM))).toBe(false);
  });

  test("a change is dirty and changing it back is clean again", () => {
    const base = toRoomWorkflowDraft(ROOM);
    const edited = withStatuses(base, base.statuses.map((s) => (s.key === "open" ? withLabel(s, "en", "Opened") : s)));
    expect(isWorkflowDirty(edited, base)).toBe(true);
    const reverted = withStatuses(edited, edited.statuses.map((s) => (s.key === "open" ? withLabel(s, "en", "Open") : s)));
    expect(isWorkflowDirty(reverted, base)).toBe(false);
  });

  test("a translation, a reorder, a removal and a replacement each make it dirty", () => {
    const base = toTemplateWorkflowDraft(TEMPLATE);
    expect(isWorkflowDirty(withStatuses(base, base.statuses.map((s) => withLabel(s, "pl", "x"))), base)).toBe(true);
    expect(isWorkflowDirty(withStatuses(base, moveItem(base.statuses, 0, 1)), base)).toBe(true);
    const room = toRoomWorkflowDraft(ROOM);
    const removed = removeStatus(room, status(room, "doing").id, null);
    expect(isWorkflowDirty(removed, room)).toBe(true);
    expect(isWorkflowDirty(setReplacement(removed, status(room, "doing").id, status(room, "open").id), removed)).toBe(true);
  });

  test("whitespace around a label is not a change", () => {
    const base = toRoomWorkflowDraft(ROOM);
    const padded = withStatuses(base, base.statuses.map((s) => withLabel(s, "en", `${s.labels.en} `)));
    expect(isWorkflowDirty(padded, base)).toBe(false);
  });
});

describe("validation (mirrors WorkflowRules.kt)", () => {
  const valid = () => toRoomWorkflowDraft(ROOM);
  const keys = (d: WorkflowDraft) => {
    const e = validateWorkflowDraft(d);
    return {
      lists: [e.statuses?.key, e.fields?.key, e.initial?.key, e.closed?.key].filter(Boolean),
      rows: e.rows,
    };
  };

  test("a valid workflow has no errors", () => {
    const e = validateWorkflowDraft(valid());
    expect(hasWorkflowErrors(e)).toBe(false);
    expect(e.rows).toEqual({});
  });

  test("a template with a blank translation is valid, English is the required one", () => {
    expect(hasWorkflowErrors(validateWorkflowDraft(toTemplateWorkflowDraft(TEMPLATE)))).toBe(false);
    const d = toTemplateWorkflowDraft(TEMPLATE);
    const noEnglish = withStatuses(d, d.statuses.map((s, i) => (i === 0 ? withLabel(s, "en", " ") : s)));
    expect(validateWorkflowDraft(noEnglish).rows[noEnglish.statuses[0].id].labels?.en?.key).toBe("workflows.validation.labelRequired");
  });

  test("2-20 statuses", () => {
    const d = valid();
    const one = withStatuses(d, [status(d, "open")]);
    expect(validateWorkflowDraft(one).statuses).toEqual({ key: "workflows.validation.statusCount", params: { min: 2, max: 20 } });
    const many = withStatuses(
      d,
      Array.from({ length: 21 }, (_, i) => ({ ...status(d, i === 0 ? "open" : "done"), id: `s${i}`, key: `k${i}`, initial: i === 0 })),
    );
    expect(validateWorkflowDraft(many).statuses?.key).toBe("workflows.validation.statusCount");
    const twenty = withStatuses(many, many.statuses.slice(0, 20));
    expect(validateWorkflowDraft(twenty).statuses).toBeUndefined();
  });

  test("0-20 fields", () => {
    const d = valid();
    const twentyOne = { ...d, fields: Array.from({ length: 21 }, (_, i) => ({ ...field(d, "note"), id: `f${i}`, key: `f${i}` })) };
    expect(validateWorkflowDraft(twentyOne).fields).toEqual({ key: "workflows.validation.fieldCount", params: { max: 20 } });
    expect(validateWorkflowDraft({ ...d, fields: [] }).fields).toBeUndefined();
    expect(validateWorkflowDraft({ ...d, fields: twentyOne.fields.slice(0, 20) }).fields).toBeUndefined();
  });

  test.each([
    ["", false],
    ["Open", false],
    ["in_progress", false],
    ["1st", false],
    ["-a", false],
    ["a-", false],
    ["a--b", false],
    ["a".repeat(41), false],
    ["a".repeat(40), true],
    ["s1", true],
    ["in-progress", true],
    ["wont-do", true],
  ])("key %j is %s", (key, ok) => {
    const d = valid();
    const next = withStatuses(d, d.statuses.map((s) => (s.key === "dropped" ? withKey(s, key) : s)));
    const row = validateWorkflowDraft(next).rows[status(next, key).id];
    expect(row?.key?.key).toBe(ok ? undefined : "workflows.validation.keyInvalid");
  });

  test("keys are trimmed before they are checked", () => {
    const d = valid();
    const next = withStatuses(d, d.statuses.map((s) => (s.key === "dropped" ? withKey(s, " dropped ") : s)));
    expect(hasWorkflowErrors(validateWorkflowDraft(next))).toBe(false);
  });

  test("a duplicate key is flagged on the later row only, and statuses and fields do not clash", () => {
    const d = valid();
    const dup = withStatuses(d, d.statuses.map((s) => (s.key === "dropped" ? withKey(s, "open") : s)));
    const rows = validateWorkflowDraft(dup).rows;
    expect(rows[dup.statuses[0].id]).toBeUndefined();
    expect(rows[dup.statuses[3].id].key?.key).toBe("workflows.validation.keyDuplicate");
    const sameAsField = withStatuses(d, d.statuses.map((s) => (s.key === "dropped" ? withKey(s, "note") : s)));
    expect(hasWorkflowErrors(validateWorkflowDraft(sameAsField))).toBe(false);
  });

  test("labels are required in English and at most 60 code points", () => {
    const d = valid();
    const set = (en: string, pl = "") => withStatuses(d, d.statuses.map((s, i) => (i === 3 ? { ...s, labels: { en, pl } } : s)));
    const labelsOf = (next: WorkflowDraft) => validateWorkflowDraft(next).rows[next.statuses[3].id]?.labels;
    expect(labelsOf(set("   "))?.en?.key).toBe("workflows.validation.labelRequired");
    expect(labelsOf(set("x".repeat(60)))).toBeUndefined();
    expect(labelsOf(set("x".repeat(61)))?.en).toEqual({ key: "workflows.validation.labelTooLong", params: { max: 60 } });
    // An emoji is one code point, though two UTF-16 units.
    expect(labelsOf(set("😀".repeat(60)))).toBeUndefined();
    expect(labelsOf(set("😀".repeat(61)))?.en?.key).toBe("workflows.validation.labelTooLong");
    expect(labelsOf(set("Fine", "y".repeat(61)))?.pl?.key).toBe("workflows.validation.labelTooLong");
    expect(labelsOf(set("Fine", " "))).toBeUndefined();
  });

  test("labels carry no control characters", () => {
    const d = valid();
    const set = (en: string) => withStatuses(d, d.statuses.map((s, i) => (i === 3 ? { ...s, labels: { en, pl: "" } } : s)));
    const labelsOf = (next: WorkflowDraft) => validateWorkflowDraft(next).rows[next.statuses[3].id]?.labels;
    expect(labelsOf(set("two\nlines"))?.en?.key).toBe("workflows.validation.labelControl");
    expect(labelsOf(set("tab\there"))?.en?.key).toBe("workflows.validation.labelControl");
    expect(labelsOf(set("bell\u0007"))?.en?.key).toBe("workflows.validation.labelControl");
    expect(labelsOf(set("c1\u0085"))?.en?.key).toBe("workflows.validation.labelControl");
    expect(labelsOf(set("del\u007f"))?.en?.key).toBe("workflows.validation.labelControl");
    // Control characters at the edges are trimmed away (whitespace), exactly like the server's sanitiser.
    expect(labelsOf(set("\tPadded\n"))).toBeUndefined();
    expect(labelsOf(set("Zażółć 😀 ł"))).toBeUndefined();
  });

  test("labels are unique per language within statuses, fields and one field's options (case-insensitive, trimmed, blanks skipped)", () => {
    const d = valid();
    const relabel = <T extends { labels: { en: string; pl: string } }>(row: T, en: string, pl = ""): T => ({ ...row, labels: { en, pl } });
    const dup = withStatuses(d, d.statuses.map((s, i) => relabel(s, i === 0 ? "Open" : i === 3 ? "  oPEN " : `Other ${i}`)));
    const rows = validateWorkflowDraft(dup).rows;
    expect(rows[dup.statuses[0].id]).toBeUndefined();
    expect(rows[dup.statuses[3].id].labels?.en).toEqual({ key: "workflows.validation.labelDuplicate", params: undefined });

    // Per language: the same text under two languages is fine, two Polish duplicates are not; a blank translation never clashes.
    const t = toTemplateWorkflowDraft(TEMPLATE);
    const mixed = withStatuses(t, [relabel(t.statuses[0], "Open", "Otwarte"), relabel(t.statuses[1], "Done", "OTWARTE"), relabel(t.statuses[2], "Nope", "")]);
    const mixedRows = validateWorkflowDraft(mixed).rows;
    expect(mixedRows[mixed.statuses[1].id].labels).toEqual({ pl: { key: "workflows.validation.labelDuplicate", params: undefined } });
    const blanks = withStatuses(t, t.statuses.map((s) => relabel(s, s.labels.en, "")));
    expect(hasWorkflowErrors(validateWorkflowDraft(blanks))).toBe(false);
    const sameAcrossLanguages = withStatuses(t, [relabel(t.statuses[0], "ETA", "ETA"), relabel(t.statuses[1], "Done"), relabel(t.statuses[2], "Nope")]);
    expect(hasWorkflowErrors(validateWorkflowDraft(sameAcrossLanguages))).toBe(false);

    // Fields are their own list, and each field's options are another; a status may share a label with a field or an option.
    const [sev, note] = d.fields;
    const fields = { ...d, fields: [relabel(sev, "Open"), relabel(note, "open")] };
    expect(validateWorkflowDraft(fields).rows[note.id].labels?.en?.key).toBe("workflows.validation.labelDuplicate");
    expect(validateWorkflowDraft(fields).rows[sev.id]).toBeUndefined();
    const options = { ...d, fields: [{ ...sev, options: [relabel(sev.options[0], "S"), relabel(sev.options[1], "s")] }, { ...relabel(note, "Note"), type: "SELECT" as const, options: [relabel(sev.options[0], "S")] }] };
    const optionRows = validateWorkflowDraft(options).rows;
    expect(optionRows[sev.options[1].id].labels?.en?.key).toBe("workflows.validation.labelDuplicate");
    expect(optionRows[sev.options[0].id]).toBeUndefined();
    expect(Object.keys(optionRows)).toEqual([sev.options[1].id]);
  });

  test("a label that is also too long reports that, not a duplicate", () => {
    const d = valid();
    const long = "x".repeat(61);
    const next = withStatuses(d, d.statuses.map((s, i) => ({ ...s, labels: { en: i >= 2 ? long : `L${i}`, pl: "" } })));
    expect(validateWorkflowDraft(next).rows[next.statuses[3].id].labels?.en?.key).toBe("workflows.validation.labelTooLong");
  });

  test("exactly one initial status, and it is OPEN", () => {
    const d = valid();
    expect(keys(withStatuses(d, d.statuses.map((s) => ({ ...s, initial: false })))).lists).toEqual(["workflows.validation.initialCount"]);
    expect(keys(withStatuses(d, d.statuses.map((s) => ({ ...s, initial: s.category === "OPEN" })))).lists).toEqual(["workflows.validation.initialCount"]);
    expect(keys(withStatuses(d, d.statuses.map((s) => ({ ...s, initial: s.key === "done" })))).lists).toEqual(["workflows.validation.initialOpen"]);
  });

  test("at least one CLOSED status", () => {
    const d = valid();
    expect(keys(withStatuses(d, d.statuses.map((s) => ({ ...s, category: "OPEN" as const })))).lists).toEqual(["workflows.validation.closedRequired"]);
  });

  test("select fields need 1-50 unique, valid option keys and labels; other types ignore their options", () => {
    const d = valid();
    const sev = field(d, "sev");
    const withOptions = (options: FieldDraft["options"], type: FieldDraft["type"] = "SELECT") => ({
      ...d,
      fields: [{ ...sev, type, options }, field(d, "note")],
    });
    const rowsOf = (next: WorkflowDraft) => validateWorkflowDraft(next).rows;
    expect(rowsOf(withOptions([]))[sev.id].options).toEqual({ key: "workflows.validation.optionCount", params: { min: 1, max: 50 } });
    expect(rowsOf(withOptions([], "MULTI_SELECT"))[sev.id].options?.key).toBe("workflows.validation.optionCount");
    expect(rowsOf(withOptions([], "TEXT"))).toEqual({});
    const fifty = Array.from({ length: 50 }, (_, i) => ({ ...newOptionDraft(), key: `o${i}`, labels: { en: `O${i}`, pl: "" } }));
    expect(rowsOf(withOptions(fifty))).toEqual({});
    expect(rowsOf(withOptions([...fifty, { ...newOptionDraft(), key: "extra", labels: { en: "Extra", pl: "" } }]))[sev.id].options?.key).toBe("workflows.validation.optionCount");

    const [a, b] = [sev.options[0], sev.options[1]];
    const bad = rowsOf(withOptions([a, { ...b, key: a.key, labels: { en: "", pl: "" } }]));
    expect(bad[b.id].key?.key).toBe("workflows.validation.keyDuplicate");
    expect(bad[b.id].labels?.en?.key).toBe("workflows.validation.labelRequired");
    expect(bad[a.id]).toBeUndefined();
    // Option keys of different fields do not clash.
    const other = { ...field(d, "note"), type: "SELECT" as const, options: [{ ...a, id: "x1" }] };
    expect(hasWorkflowErrors(validateWorkflowDraft({ ...d, fields: [sev, other] }))).toBe(false);
  });

  test("one row can carry a key and a label error together", () => {
    const d = valid();
    const next = withStatuses(d, d.statuses.map((s, i) => (i === 3 ? { ...withKey(s, ""), labels: { en: "", pl: "" } } : s)));
    expect(Object.keys(validateWorkflowDraft(next).rows[next.statuses[3].id])).toEqual(["key", "labels"]);
  });

  test("a list-level error alone counts as an error", () => {
    expect(hasWorkflowErrors({ rows: {}, closed: { key: "workflows.validation.closedRequired" } })).toBe(true);
    expect(hasWorkflowErrors({ rows: {} })).toBe(false);
  });

  test("validationText translates a message with its params, or yields nothing", () => {
    expect(validationText(i18n.t.bind(i18n), { key: "workflows.validation.statusCount", params: { min: 2, max: 20 } })).toBe(
      "A workflow needs 2–20 statuses",
    );
    expect(validationText(i18n.t.bind(i18n), undefined)).toBeUndefined();
  });
});

describe("the wire types are the generated API types", () => {
  // `expectTypeOf` is checked by `tsc -b` (the build and the web gate), so a drift in the spec fails the compile, not a run.
  test("the vocabularies are equal to the spec's enums, so no value can be added or dropped on one side only", () => {
    expectTypeOf<StatusColour>().toEqualTypeOf<components["schemas"]["StatusColour"]>();
    expectTypeOf<StatusCategory>().toEqualTypeOf<components["schemas"]["StatusCategory"]>();
    expectTypeOf<FieldType>().toEqualTypeOf<components["schemas"]["FieldType"]>();
  });

  test("a spec response loads as is, and the body is the spec's request", () => {
    expectTypeOf(toTemplateBody).returns.toEqualTypeOf<components["schemas"]["WorkflowTemplateRequest"]>();
    const response: components["schemas"]["WorkflowTemplateResponse"] = { id: 1, name: "T", revision: 1, createdAt: 0, updatedAt: 0, ...TEMPLATE };
    expect(toTemplateBody({ name: "T", description: "" }, toTemplateWorkflowDraft(response)).statuses).toHaveLength(3);
  });

  test("a room's document is the spec's, and its PUT body is the spec's request", () => {
    expectTypeOf<RoomWorkflow>().toEqualTypeOf<components["schemas"]["RoomWorkflow"]>();
    expectTypeOf<RoomWorkflowBody>().toEqualTypeOf<components["schemas"]["RoomWorkflowUpdate"]>();
    expectTypeOf(toRoomWorkflowBody).returns.toEqualTypeOf<components["schemas"]["RoomWorkflowUpdate"]>();
    const body: components["schemas"]["RoomWorkflowUpdate"] = toRoomWorkflowBody(toRoomWorkflowDraft(ROOM));
    expect(body.statuses).toHaveLength(ROOM.statuses.length);
  });
});
