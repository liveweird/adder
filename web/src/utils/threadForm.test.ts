import { describe, expect, expectTypeOf, test } from "vitest";
import type { TFunction } from "i18next";
import i18n from "../i18n";
import { ApiError } from "../api/http";
import type { components } from "../api/schema";
import {
  MAX_MESSAGE_BODY_LENGTH,
  MAX_THREAD_TITLE_LENGTH,
  emptyFieldDraft,
  emptyThreadForm,
  isFieldProblem,
  isThreadDraftEmpty,
  isUnsetDraft,
  normalizeBody,
  normalizedHttpUrl,
  reconcileThreadDraft,
  threadCreateErrorMessage,
  threadFormValidation,
  toThreadCreateBody,
  type FieldDraft,
  type ThreadFormValues,
  type WorkflowField,
} from "./threadForm";

const t = i18n.t.bind(i18n) as TFunction;

const OPTIONS = [
  { key: "s1", label: "S1" },
  { key: "s2", label: "S2" },
];
const field = (key: string, type: WorkflowField["type"], required = false): WorkflowField => ({
  key,
  label: key,
  type,
  required,
  options: type === "SELECT" || type === "MULTI_SELECT" ? OPTIONS : [],
});
const FIELDS: WorkflowField[] = [
  field("severity", "SELECT", true),
  field("component", "TEXT"),
  field("count", "NUMBER"),
  field("eta", "DATE"),
  field("link", "URL"),
  field("tags", "MULTI_SELECT"),
  field("lead", "USER"),
];

function values(overrides: Partial<ThreadFormValues> = {}, fieldDrafts: Record<string, FieldDraft> = {}): ThreadFormValues {
  const base = emptyThreadForm(FIELDS);
  return { ...base, title: "Disk full", body: "It is.", ...overrides, fields: { ...base.fields, severity: { kind: "text", value: "s1" }, ...fieldDrafts } };
}
const errorsOf = (v: ThreadFormValues, fields = FIELDS) => threadFormValidation(t, fields)(v);
const text = (value: string): FieldDraft => ({ kind: "text", value });

describe("drafts", () => {
  test("an empty draft follows the field's type, and only an empty one is unset", () => {
    expect(emptyFieldDraft({ type: "TEXT" })).toEqual({ kind: "text", value: "" });
    expect(emptyFieldDraft({ type: "SELECT" })).toEqual({ kind: "text", value: "" });
    expect(emptyFieldDraft({ type: "MULTI_SELECT" })).toEqual({ kind: "multi", value: [] });
    expect(emptyFieldDraft({ type: "USER" })).toEqual({ kind: "user", value: null });
    expect(emptyThreadForm(FIELDS).fields.tags).toEqual({ kind: "multi", value: [] });
    expect(isUnsetDraft(text("  "))).toBe(true);
    expect(isUnsetDraft(text("x"))).toBe(false);
    expect(isUnsetDraft({ kind: "multi", value: [] })).toBe(true);
    expect(isUnsetDraft({ kind: "multi", value: ["s1"] })).toBe(false);
    expect(isUnsetDraft({ kind: "user", value: null })).toBe(true);
    expect(isUnsetDraft({ kind: "user", value: { value: "3", label: "Ann" } })).toBe(false);
  });
});

describe("normalizeBody", () => {
  test("turns CRLF and a lone CR into LF, drops leading blank lines and trailing whitespace, keeps the first line's indentation", () => {
    expect(normalizeBody("\r\n  \nfirst\r\nsecond\rthird  \n\n")).toBe("first\nsecond\nthird");
    expect(normalizeBody("    code\nmore")).toBe("    code\nmore");
    expect(normalizeBody(" \n\t")).toBe("");
  });
});

describe("threadFormValidation", () => {
  test("a title and a message with the required field set is valid", () => {
    expect(errorsOf(values())).toEqual({});
  });

  test("the title is 1 to 200 characters after trimming, without control or hidden formatting characters", () => {
    expect(errorsOf(values({ title: "   " })).title).toBe("Title must be 1–200 characters");
    expect(errorsOf(values({ title: "x".repeat(MAX_THREAD_TITLE_LENGTH + 1) })).title).toBe("Title must be 1–200 characters");
    expect(errorsOf(values({ title: "x".repeat(MAX_THREAD_TITLE_LENGTH) })).title).toBeUndefined();
    expect(errorsOf(values({ title: "a\nb" })).title).toMatch(/control or hidden/);
    expect(errorsOf(values({ title: "a‮b" })).title).toMatch(/control or hidden/);
  });

  test("the message is 1 to 8,000 characters after normalising; a line feed and a tab are fine, other control and hidden characters are not", () => {
    expect(errorsOf(values({ body: " \n " })).body).toBe("The message must be 1–8000 characters");
    expect(errorsOf(values({ body: "x".repeat(MAX_MESSAGE_BODY_LENGTH + 1) })).body).toBe("The message must be 1–8000 characters");
    expect(errorsOf(values({ body: "x".repeat(MAX_MESSAGE_BODY_LENGTH) })).body).toBeUndefined();
    expect(errorsOf(values({ body: "a\r\n\tb" })).body).toBeUndefined();
    expect(errorsOf(values({ body: "a\u0007b" })).body).toMatch(/control or hidden/);
    expect(errorsOf(values({ body: "a b" })).body).toMatch(/control or hidden/);
  });

  test("a required field must be set, an optional one may stay empty", () => {
    const empty = values();
    empty.fields.severity = text("");
    expect(errorsOf(empty)["fields.severity"]).toBe("This field is required");
    expect(errorsOf(empty)["fields.component"]).toBeUndefined();
    const noUser = values({}, { lead: { kind: "user", value: null } });
    expect(errorsOf(noUser)["fields.lead"]).toBeUndefined();
    const requiredUser = [field("lead", "USER", true)];
    expect(errorsOf({ title: "t", body: "b", tags: [], fields: { lead: { kind: "user", value: null } } }, requiredUser)["fields.lead"]).toBe("This field is required");
  });

  test.each([
    ["component", "TEXT", "a\nb", /control or hidden/],
    ["component", "TEXT", "x".repeat(501), "At most 500 characters"],
    ["count", "NUMBER", "abc", /Enter a number/],
    ["count", "NUMBER", "1.", /Enter a number/],
    ["count", "NUMBER", "01", /Enter a number/],
    ["count", "NUMBER", "1e16", /Enter a number/],
    ["count", "NUMBER", "9".repeat(33), /Enter a number/],
    ["eta", "DATE", "2026-02-30", /valid date/],
    ["eta", "DATE", "tomorrow", /valid date/],
  ])("%s (%s) refuses %j", (key, _type, value, message) => {
    const errors = errorsOf(values({}, { [key]: text(value) }));
    expect(errors[`fields.${key}`]).toEqual(expect.stringMatching(message instanceof RegExp ? message : new RegExp(`^${message}$`)));
  });

  // The server parses a URL with java.net.URI and wants a non-empty host of ASCII letters, digits, `-` and `.`: what the form can say
  // up front is said here, naming the field.
  test.each([
    ["a scheme that is not http(s)", "ftp://example.test"],
    ["no URL at all", "not a url"],
    ["an underscore host", "http://my_host.local/x"],
    ["an underscore in a bare domain", "https://exa_mple.com"],
    ["no host", "http://"],
    ["a user name in the link", "https://trusted.example@evil.example/"],
    ["a user name and password in the link", "https://user:secret@example.test/"],
    ["a bidirectional override", "https://example.test/\u202Efdp.exe"],
    ["a bidirectional isolate", "https://example.test/\u2066x"],
    ["a normalised form past 2,000 characters", `https://example.test/${"a".repeat(2000)}`],
  ])("a URL field refuses %s, naming the field", (_why, value) => {
    expect(errorsOf(values({}, { link: text(value) }))["fields.link"]).toBe("link must be a valid http(s) address (at most 2000 characters)");
  });

  test.each([
    ["component", "disk /dev/sda1 is full"],
    ["count", "-12.5"],
    ["count", "1e15"],
    ["count", " 7 "],
    ["eta", "2026-02-28"],
    ["link", "https://example.test/runbook?id=1"],
  ])("%s accepts %j", (key, value) => {
    expect(errorsOf(values({}, { [key]: text(value) }))[`fields.${key}`]).toBeUndefined();
  });

  // What the reviewer found the raw forms do on the server: each is accepted here AND sent in its normalised spelling.
  test.each([
    ["an IDN host is punycoded", "https://münchen.de/", "https://xn--mnchen-3ya.de/"],
    ["a scheme-relative-looking URL is completed", "http:example.com", "http://example.com/"],
    ["a space in the path is percent-encoded", "http://a.com/a b", "http://a.com/a%20b"],
    ["a pipe is percent-encoded", "https://example.com/a|b", "https://example.com/a%7Cb"],
    ["braces in the query are percent-encoded", "https://example.com/?q={x}", "https://example.com/?q=%7Bx%7D"],
    ["brackets in the query are percent-encoded", "https://example.com/?a[0]=1", "https://example.com/?a%5B0%5D=1"],
    ["a plain URL is only completed", "https://example.com", "https://example.com/"],
  ])("a URL field: %s", (_why, raw, href) => {
    expect(normalizedHttpUrl(raw)).toBe(href);
    expect(errorsOf(values({}, { link: text(raw) }))["fields.link"]).toBeUndefined();
    expect(toThreadCreateBody(values({}, { link: text(`  ${raw}  `) }), FIELDS).fields).toMatchObject({ link: href });
  });
});

describe("toThreadCreateBody", () => {
  test("trims the title, normalises the body and sends only the fields that hold something, typed for the wire", () => {
    const body = toThreadCreateBody(
      values(
        { title: "  Disk full  ", body: "\r\nIt is.\r\n" },
        {
          component: text("  db  "),
          count: text(" 7.5 "),
          eta: text("2026-03-01"),
          link: text(""),
          tags: { kind: "multi", value: ["s1", "s2"] },
          lead: { kind: "user", value: { value: "12", label: "Ann (ann@example.test)" } },
        },
      ),
      FIELDS,
    );
    expect(body).toEqual({
      title: "Disk full",
      body: "It is.",
      fields: { severity: "s1", component: "db", count: 7.5, eta: "2026-03-01", tags: ["s1", "s2"], lead: 12 },
    });
  });

  test("a thread with no field set carries no `fields` key at all", () => {
    const optionalOnly = [field("component", "TEXT")];
    expect(toThreadCreateBody({ title: "T", body: "B", tags: [], fields: { component: text("") } }, optionalOnly)).toEqual({ title: "T", body: "B" });
    expect(toThreadCreateBody({ title: "T", body: "B", tags: [], fields: {} }, [])).toEqual({ title: "T", body: "B" });
  });

  test("sends picked registry tags and omits an empty tag set", () => {
    expect(toThreadCreateBody(values({ tags: ["backend", "incident"] }), FIELDS)).toMatchObject({ tags: ["backend", "incident"] });
    expect(toThreadCreateBody(values({ tags: [] }), FIELDS)).not.toHaveProperty("tags");
  });

  test("the types come from the generated spec, so a spec change fails the compile", () => {
    // `expectTypeOf` is checked by `tsc -b` (the build and the web gate), not at run time.
    expectTypeOf(toThreadCreateBody).returns.toEqualTypeOf<components["schemas"]["ThreadCreateRequest"]>();
    expectTypeOf<WorkflowField>().toEqualTypeOf<components["schemas"]["RoomWorkflowField"]>();
  });
});

describe("threadCreateErrorMessage", () => {
  test("maps each status to the start-a-thread vocabulary", () => {
    expect(threadCreateErrorMessage(new ApiError(403, null), t)).toMatch(/Only members/);
    expect(threadCreateErrorMessage(new ApiError(404, null), t)).toMatch(/no longer exists/);
    expect(threadCreateErrorMessage(new ApiError(409, null), t)).toMatch(/archived/);
    expect(threadCreateErrorMessage(new ApiError(429, null), t)).toMatch(/too quickly/);
    expect(threadCreateErrorMessage(new ApiError(400, null), t)).toBe("The thread could not be started: one or more fields are invalid");
    expect(threadCreateErrorMessage(new ApiError(500, null), t)).toBe("Create failed (500)");
    expect(threadCreateErrorMessage(new Error("offline"), t)).toMatch(/Create failed/);
  });

  test("a 400 adds the server's own sentence, and one about a field says the room's fields may have changed", () => {
    const title = new ApiError(400, { detail: "Title must be 1-200 characters" });
    expect(threadCreateErrorMessage(title, t)).toBe("The thread could not be started: one or more fields are invalid (Title must be 1-200 characters)");
    expect(isFieldProblem(title)).toBe(false);
    const gone = new ApiError(400, { detail: "Unknown field 'severity'" });
    expect(isFieldProblem(gone)).toBe(true);
    expect(threadCreateErrorMessage(gone, t)).toMatch(/^The room's fields may have changed since you opened this form\..* \(Unknown field 'severity'\)$/);
    expect(isFieldProblem(new ApiError(400, { detail: "Field 'link' is required" }))).toBe(true);
    expect(isFieldProblem(new ApiError(409, { detail: "Field 'link' is required" }))).toBe(false);
    expect(isFieldProblem(new Error("x"))).toBe(false);
  });
});

describe("kept drafts", () => {
  test("a draft is empty only when it holds no title, message, tag or field", () => {
    expect(isThreadDraftEmpty(emptyThreadForm(FIELDS))).toBe(true);
    expect(isThreadDraftEmpty({ ...emptyThreadForm(FIELDS), title: "  " })).toBe(true);
    expect(isThreadDraftEmpty({ ...emptyThreadForm(FIELDS), title: "x" })).toBe(false);
    expect(isThreadDraftEmpty({ ...emptyThreadForm(FIELDS), body: "x" })).toBe(false);
    expect(isThreadDraftEmpty({ ...emptyThreadForm(FIELDS), tags: ["incident"] })).toBe(false);
    expect(isThreadDraftEmpty(values({ title: "", body: "" }))).toBe(false); // the required field is set
  });

  test("a kept draft is laid over the current fields: gone dropped, new empty, a changed type starts over", () => {
    expect(reconcileThreadDraft(null, FIELDS)).toEqual(emptyThreadForm(FIELDS));
    const kept = values({ title: "T", body: "B", tags: ["incident"] }, { component: text("db"), tags: { kind: "multi", value: ["s1"] } });
    const now = [field("severity", "SELECT", true), field("component", "NUMBER"), field("tags", "MULTI_SELECT"), field("added", "TEXT")];
    const merged = reconcileThreadDraft(kept, now);
    expect(merged.title).toBe("T");
    expect(merged.body).toBe("B");
    expect(merged.tags).toEqual(["incident"]);
    expect(Object.keys(merged.fields)).toEqual(["severity", "component", "tags", "added"]);
    expect(merged.fields.severity).toEqual(text("s1"));
    expect(merged.fields.tags).toEqual({ kind: "multi", value: ["s1"] });
    expect(merged.fields.added).toEqual(text(""));
    // "component" kept a text draft and is still a text-kind field (TEXT -> NUMBER): it carries over; a kind change would not.
    expect(merged.fields.component).toEqual(text("db"));
    const typed = reconcileThreadDraft(kept, [field("component", "USER")]);
    expect(typed.fields.component).toEqual({ kind: "user", value: null });
  });

  test("an old kept draft without tags is upgraded to an empty tag set", () => {
    const old = { title: "T", body: "B", fields: {} } as ThreadFormValues;
    expect(reconcileThreadDraft(old, []).tags).toEqual([]);
  });
});
