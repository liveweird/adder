import { describe, expect, test } from "vitest";
import i18n from "../i18n";
import type { ThreadEvent } from "../api/threads";
import { describeEvent, eventLines } from "./threadEvents";

const t = i18n.t;

function event(type: string, params: Record<string, string> = {}): ThreadEvent {
  return { id: 1, userId: 5, userName: "Ann", userDeleted: false, type: type as ThreadEvent["type"], params, createdAt: 1 };
}

describe("describeEvent", () => {
  test("each persisted kind reads as one sentence built from the recorded labels and names", () => {
    expect(describeEvent(event("CREATED", { title: "T", status: "triage", statusLabel: "Triage" }), t)).toBe("Started the thread in Triage");
    expect(describeEvent(event("TITLE_CHANGED", { from: "A", to: "B" }), t)).toBe("Renamed the thread");
    expect(describeEvent(event("STATUS_CHANGED", { from: "a", to: "b", fromLabel: "Triage", toLabel: "Working" }), t)).toBe("Moved the thread from Triage to Working");
    expect(describeEvent(event("CLOSED", { from: "a", to: "b", fromLabel: "Working", toLabel: "Done" }), t)).toBe("Closed the thread: Working → Done");
    expect(describeEvent(event("REOPENED", { from: "b", to: "a", fromLabel: "Done", toLabel: "Triage" }), t)).toBe("Reopened the thread: Done → Triage");
    expect(describeEvent(event("STATUS_REPLACED", { from: "a", to: "b", fromLabel: "Old", toLabel: "New" }), t)).toBe("Old was retired; the thread moved to New");
    expect(describeEvent(event("OWNER_CHANGED", { fromId: "5", toId: "6", fromName: "Ann", toName: "Bob" }), t)).toBe("Passed the thread from Ann to Bob");
    expect(describeEvent(event("FIELD_CHANGED", { field: "sev", fieldLabel: "Severity", from: "S3", to: "S1" }), t)).toBe("Changed Severity");
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
  test("a renamed title and a changed field carry their old and new values", () => {
    expect(eventLines(event("TITLE_CHANGED", { from: "A", to: "B" }), t)).toEqual(["Title: A → B"]);
    expect(eventLines(event("FIELD_CHANGED", { field: "sev", fieldLabel: "Severity", from: "S3" }), t)).toEqual(["Severity: cleared"]);
    expect(eventLines(event("FIELD_CHANGED", { field: "sev", fieldLabel: "Severity", to: "S1" }), t)).toEqual(["Severity: set to S1"]);
  });

  test("the other kinds, and a field recorded by name only, have no body line", () => {
    expect(eventLines(event("CLOSED", { from: "a", to: "b" }), t)).toEqual([]);
    expect(eventLines(event("FIELD_CHANGED", { field: "sev", fieldLabel: "Severity" }), t)).toEqual([]);
  });
});
