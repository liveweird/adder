import { describe, expect, test } from "vitest";
import i18n from "../i18n";
import type { ThreadEvent } from "../api/threads";
import { describeEvent, eventLines } from "./threadEvents";

const t = i18n.t;

function event(type: string, params: Record<string, string> = {}): ThreadEvent {
  return { id: 1, actorId: 5, actorName: "Ann", actorDeleted: false, type: type as ThreadEvent["type"], params, createdAt: 1 };
}

describe("describeEvent", () => {
  test("each persisted kind reads as one sentence built from the recorded labels and names", () => {
    expect(describeEvent(event("CREATED", { status: "triage", statusLabel: "Triage" }), t)).toBe("Started the thread in Triage");
    expect(describeEvent(event("TITLE_CHANGED"), t)).toBe("Renamed the thread");
    expect(describeEvent(event("STATUS_CHANGED", { from: "a", to: "b", fromLabel: "Triage", toLabel: "Working" }), t)).toBe("Moved the thread from Triage to Working");
    expect(describeEvent(event("CLOSED", { from: "a", to: "b", fromLabel: "Working", toLabel: "Done" }), t)).toBe("Closed the thread: Working → Done");
    expect(describeEvent(event("REOPENED", { from: "b", to: "a", fromLabel: "Done", toLabel: "Triage" }), t)).toBe("Reopened the thread: Done → Triage");
    expect(describeEvent(event("STATUS_REPLACED", { from: "a", to: "b", fromLabel: "Old", toLabel: "New" }), t)).toBe("Old was retired; the thread moved to New");
    expect(describeEvent(event("OWNER_CHANGED", { fromId: "5", toId: "6", fromName: "Ann", toName: "Bob" }), t)).toBe("Passed the thread from Ann to Bob");
    expect(describeEvent(event("FIELD_CHANGED", { field: "sev", fieldLabel: "Severity", from: "S3", to: "S1" }), t)).toBe("Changed Severity");
    expect(describeEvent(event("TAGS_CHANGED", { added: "data" }), t)).toBe("Changed the tags");
  });

  test("a label that was not recorded falls back to the key, and a missing param to a dash", () => {
    expect(describeEvent(event("STATUS_CHANGED", { from: "triage", to: "work" }), t)).toBe("Moved the thread from triage to work");
    expect(describeEvent(event("OWNER_CHANGED", { fromId: "5" }), t)).toBe("Passed the thread from 5 to —");
    expect(describeEvent(event("FIELD_CHANGED", {}), t)).toBe("Changed —");
  });

  test("a kind this build does not know shows its raw type (a newer server never blanks a line out)", () => {
    expect(describeEvent(event("MESSAGE_PINNED"), t)).toBe("MESSAGE_PINNED");
  });
});

describe("eventLines", () => {
  test("a tag change lists what was added and what was removed, one line each, and none for a side that did not change", () => {
    expect(eventLines(event("TAGS_CHANGED", { added: "data, security a", removed: "old tag" }), t)).toEqual(["Added: data, security a", "Removed: old tag"]);
    expect(eventLines(event("TAGS_CHANGED", { removed: "x" }), t)).toEqual(["Removed: x"]);
    expect(eventLines(event("TAGS_CHANGED", {}), t)).toEqual([]);
  });

  test("a changed SELECT, USER, DATE or NUMBER field carries its old and new values", () => {
    expect(eventLines(event("FIELD_CHANGED", { field: "sev", fieldLabel: "Severity", from: "S3", to: "S1" }), t)).toEqual(["Severity: S3 → S1"]);
    expect(eventLines(event("FIELD_CHANGED", { field: "sev", fieldLabel: "Severity", from: "S3" }), t)).toEqual(["Severity: cleared"]);
    expect(eventLines(event("FIELD_CHANGED", { field: "sev", fieldLabel: "Severity", to: "S1" }), t)).toEqual(["Severity: set to S1"]);
  });

  test("a TEXT or URL change, a rename and a retired field's entry read as the sentence alone (the history keeps no free text)", () => {
    const text = event("FIELD_CHANGED", { field: "note", fieldLabel: "Note" });
    expect(describeEvent(text, t)).toBe("Changed Note");
    expect(eventLines(text, t)).toEqual([]);
    expect(describeEvent(event("TITLE_CHANGED", {}), t)).toBe("Renamed the thread");
    expect(eventLines(event("TITLE_CHANGED", {}), t)).toEqual([]);
    expect(eventLines(event("CREATED", { status: "open", statusLabel: "Open" }), t)).toEqual([]);
  });

  test("an entry written before the scrub still shows no title lines (a rename never draws old and new titles)", () => {
    expect(eventLines(event("TITLE_CHANGED", { from: "A", to: "B" }), t)).toEqual([]);
  });

  test("the other kinds have no body line", () => {
    expect(eventLines(event("CLOSED", { from: "a", to: "b" }), t)).toEqual([]);
  });
});
