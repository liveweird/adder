// Shared fixtures for the thread side panel's tests (status, owner, details, history): the room's workflow and history pages.
import type { RoomWorkflow } from "../api/rooms";
import type { ThreadEvent } from "../api/threads";

/** Two OPEN statuses, two CLOSED ones, and one field of each kind the panel renders differently. */
export const SIDE_WORKFLOW: RoomWorkflow = {
  revision: 4,
  statuses: [
    { key: "triage", label: "Triage", colour: "BLUE", category: "OPEN", initial: true, threadCount: 1 },
    { key: "working", label: "In progress", colour: "GRAPE", category: "OPEN", initial: false, threadCount: 0 },
    { key: "done", label: "Done", colour: "TEAL", category: "CLOSED", initial: false, threadCount: 0 },
    { key: "wontdo", label: "Won't do", colour: "GRAY", category: "CLOSED", initial: false, threadCount: 0 },
  ],
  fields: [
    { key: "sev", label: "Severity", type: "SELECT", required: false, options: [{ key: "s1", label: "S1" }, { key: "s2", label: "S2" }] },
    { key: "note", label: "Note", type: "TEXT", required: false, options: [] },
    { key: "link", label: "Runbook", type: "URL", required: false, options: [] },
    { key: "lead", label: "Lead", type: "USER", required: false, options: [] },
  ],
};

export function makeEvent(overrides: Partial<ThreadEvent> = {}): ThreadEvent {
  return {
    id: 1,
    actorId: 5,
    actorName: "Ann Owner",
    actorDeleted: false,
    type: "CREATED",
    params: { title: "Disk full on db-3", status: "triage", statusLabel: "Triage" },
    createdAt: Date.UTC(2026, 5, 1, 9, 30),
    ...overrides,
  };
}

export function eventPage(items: ThreadEvent[], total = items.length, page = 1) {
  return { items, page, pageSize: 10, total };
}
