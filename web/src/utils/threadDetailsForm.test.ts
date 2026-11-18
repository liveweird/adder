import { afterEach, describe, expect, test } from "vitest";
import i18n from "../i18n";
import { ApiError } from "../api/http";
import { detailsErrors, detailsFromThread, isDetailsDirty, threadDetailsErrorMessage, toDetailsBody } from "./threadDetailsForm";
import type { WorkflowField } from "./threadForm";

const t = i18n.t;

const FIELDS: WorkflowField[] = [
  { key: "note", label: "Note", type: "TEXT", required: false, options: [] },
  { key: "sev", label: "Severity", type: "SELECT", required: true, options: [{ key: "s1", label: "S1" }, { key: "s2", label: "S2" }] },
  { key: "tags", label: "Tags", type: "MULTI_SELECT", required: false, options: [{ key: "a", label: "A" }, { key: "b", label: "B" }] },
  { key: "lead", label: "Lead", type: "USER", required: false, options: [] },
  { key: "eta", label: "ETA", type: "DATE", required: false, options: [] },
  { key: "cost", label: "Cost", type: "NUMBER", required: false, options: [] },
  { key: "link", label: "Link", type: "URL", required: false, options: [] },
];
const THREAD = {
  title: "Disk full",
  fields: { note: "db-3", sev: "s1", tags: ["a"], lead: 12, eta: "2026-06-01", cost: 5, link: "https://example.test/x" },
  fieldUserNames: { "12": "Ann" },
};

const seed = () => detailsFromThread(THREAD, FIELDS);

describe("detailsFromThread", () => {
  test("every stored value becomes the draft of its type; a USER carries the name the thread holds", () => {
    expect(seed()).toEqual({
      title: "Disk full",
      fields: {
        note: { kind: "text", value: "db-3" },
        sev: { kind: "text", value: "s1" },
        tags: { kind: "multi", value: ["a"] },
        lead: { kind: "user", value: { value: "12", label: "Ann" } },
        eta: { kind: "text", value: "2026-06-01" },
        cost: { kind: "text", value: "5" },
        link: { kind: "text", value: "https://example.test/x" },
      },
    });
  });

  test("a missing or null value is an empty draft of the field's kind; a USER with no known name reads as #id", () => {
    const empty = detailsFromThread({ title: "T", fields: { note: null, lead: 99 }, fieldUserNames: {} }, FIELDS);
    expect(empty.fields.note).toEqual({ kind: "text", value: "" });
    expect(empty.fields.tags).toEqual({ kind: "multi", value: [] });
    expect(empty.fields.lead).toEqual({ kind: "user", value: { value: "99", label: "#99" } });
    expect(detailsFromThread({ title: "T", fields: {}, fieldUserNames: {} }, FIELDS).fields.lead).toEqual({ kind: "user", value: null });
  });
});

describe("isDetailsDirty", () => {
  test("a fresh draft is clean; padding the title or a text value with spaces is not a change", () => {
    const draft = seed();
    expect(isDetailsDirty(draft, seed())).toBe(false);
    expect(isDetailsDirty({ ...draft, title: "  Disk full " }, seed())).toBe(false);
    expect(isDetailsDirty({ ...draft, fields: { ...draft.fields, note: { kind: "text", value: " db-3 " } } }, seed())).toBe(false);
  });

  test("a MULTI_SELECT is a set: the same options in another order are no change, and send nothing", () => {
    const draft = detailsFromThread({ ...THREAD, fields: { ...THREAD.fields, tags: ["a", "b"] } }, FIELDS);
    const seeded = detailsFromThread({ ...THREAD, fields: { ...THREAD.fields, tags: ["a", "b"] } }, FIELDS);
    draft.fields.tags = { kind: "multi", value: ["b", "a"] };
    expect(isDetailsDirty(draft, seeded)).toBe(false);
    expect(toDetailsBody(draft, seeded, FIELDS)).toEqual({ title: "Disk full" });
    draft.fields.tags = { kind: "multi", value: ["b"] };
    expect(isDetailsDirty(draft, seeded)).toBe(true);
  });

  test("a changed title, text, option list, person or a cleared field is dirty", () => {
    const draft = seed();
    expect(isDetailsDirty({ ...draft, title: "Disk almost full" }, seed())).toBe(true);
    expect(isDetailsDirty({ ...draft, fields: { ...draft.fields, note: { kind: "text", value: "db-4" } } }, seed())).toBe(true);
    expect(isDetailsDirty({ ...draft, fields: { ...draft.fields, tags: { kind: "multi", value: ["a", "b"] } } }, seed())).toBe(true);
    expect(isDetailsDirty({ ...draft, fields: { ...draft.fields, lead: { kind: "user", value: { value: "13", label: "Bob" } } } }, seed())).toBe(true);
    expect(isDetailsDirty({ ...draft, fields: { ...draft.fields, link: { kind: "text", value: "" } } }, seed())).toBe(true);
  });
});

describe("toDetailsBody", () => {
  test("sends the trimmed title and ONLY the changed fields; a cleared field is null, never omitted", () => {
    const draft = seed();
    draft.title = " Disk full on db-3 ";
    draft.fields.note = { kind: "text", value: "" };
    draft.fields.sev = { kind: "text", value: "s2" };
    draft.fields.tags = { kind: "multi", value: [] };
    draft.fields.lead = { kind: "user", value: { value: "13", label: "Bob" } };
    draft.fields.cost = { kind: "text", value: "7.5" };
    draft.fields.link = { kind: "text", value: "https://example.test/y z" };
    expect(toDetailsBody(draft, seed(), FIELDS)).toEqual({
      title: "Disk full on db-3",
      fields: { note: null, sev: "s2", tags: null, lead: 13, cost: 7.5, link: "https://example.test/y%20z" },
    });
  });

  test("a title-only edit carries no fields key at all", () => {
    expect(toDetailsBody({ ...seed(), title: "Renamed" }, seed(), FIELDS)).toEqual({ title: "Renamed" });
  });
});

describe("detailsErrors", () => {
  test("a clean draft has none, and an unchanged stale value (a removed option) is not judged", () => {
    expect(detailsErrors(seed(), seed(), FIELDS, t)).toEqual({});
    const stale = detailsFromThread({ ...THREAD, fields: { ...THREAD.fields, sev: "gone" } }, FIELDS);
    expect(detailsErrors(stale, stale, FIELDS, t)).toEqual({});
  });

  test("a bad title and a bad CHANGED value are named by their key", () => {
    const draft = seed();
    draft.title = " ";
    draft.fields.eta = { kind: "text", value: "2026-02-31" };
    draft.fields.cost = { kind: "text", value: "abc" };
    draft.fields.link = { kind: "text", value: "ftp://x" };
    expect(Object.keys(detailsErrors(draft, seed(), FIELDS, t)).sort()).toEqual(["fields.cost", "fields.eta", "fields.link", "title"]);
  });

  test("a required field that was set cannot be cleared; one that was never set may stay unset", () => {
    const draft = seed();
    draft.fields.sev = { kind: "text", value: "" };
    expect(detailsErrors(draft, seed(), FIELDS, t)).toEqual({ "fields.sev": "This field is required" });
    const never = detailsFromThread({ title: "T", fields: {}, fieldUserNames: {} }, FIELDS);
    expect(detailsErrors(never, never, FIELDS, t)).toEqual({});
  });
});

afterEach(async () => {
  await i18n.changeLanguage("en");
});

describe("threadDetailsErrorMessage", () => {
  test("tells the two 409s apart by problem type and adds the server's sentence to a 400", () => {
    expect(threadDetailsErrorMessage(new ApiError(409, { type: "urn:adder:problem:thread-closed" }), t)).toContain("was closed in the meantime");
    expect(threadDetailsErrorMessage(new ApiError(409, { type: "urn:adder:problem:room-archived" }), t)).toContain("was archived in the meantime");
    expect(threadDetailsErrorMessage(new ApiError(409, null), t)).toContain("changed in the meantime");
    expect(threadDetailsErrorMessage(new ApiError(400, { detail: "Field 'sev' is required" }), t)).toBe("The details could not be saved: one or more values are invalid (Field 'sev' is required)");
    expect(threadDetailsErrorMessage(new ApiError(404, null), t)).toContain("no longer exists");
    expect(threadDetailsErrorMessage(new ApiError(500, null), t)).toBe("Save failed (500)");
    expect(threadDetailsErrorMessage(new TypeError("x"), t)).toBe("Save failed. Check your connection and try again.");
  });

  test("the server's English sentence is appended in English only, never into a Polish message", async () => {
    await i18n.changeLanguage("pl");
    const message = threadDetailsErrorMessage(new ApiError(400, { detail: "Field 'sev' is required" }), i18n.t);
    expect(message).toBe("Nie udało się zapisać szczegółów: co najmniej jedna wartość jest nieprawidłowa");
  });
});
