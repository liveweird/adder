import { describe, expect, test } from "vitest";
import { useState } from "react";
import userEvent from "@testing-library/user-event";
import WorkflowEditor from "./WorkflowEditor";
import { renderWithProviders, screen, within } from "../test/render";
import {
  emptyWorkflowDraft,
  newFieldDraft,
  newStatusDraft,
  toRoomWorkflowBody,
  toRoomWorkflowDraft,
  validateWorkflowDraft,
  type RoomWorkflow,
  type WorkflowDraft,
} from "../utils/workflowForm";

const ROOM: RoomWorkflow = {
  revision: 1,
  statuses: [
    { key: "open", label: "Open", colour: "GRAY", category: "OPEN", initial: true, threadCount: 0 },
    { key: "doing", label: "Doing", colour: "BLUE", category: "OPEN", initial: false, threadCount: 5 },
    { key: "done", label: "Done", colour: "TEAL", category: "CLOSED", initial: false, threadCount: 0 },
    { key: "dropped", label: "Dropped", colour: "RED", category: "CLOSED", initial: false, threadCount: 1 },
  ],
  fields: [{ key: "note", label: "Note", type: "TEXT", required: false, options: [] }],
};

let latest: WorkflowDraft;

function Harness({
  initial,
  labelColumns = 1,
  showErrors = false,
  conflictKeys,
  freshKeys,
}: {
  initial: WorkflowDraft;
  labelColumns?: 1 | 2;
  showErrors?: boolean;
  conflictKeys?: readonly string[];
  /** Conflict keys as a NEW array on every render (what a page reading `error.keys` into a literal would do). */
  freshKeys?: string[];
}) {
  const [draft, setDraft] = useState(initial);
  return (
    <WorkflowEditor
      draft={draft}
      onChange={(next) => {
        latest = next;
        setDraft(next);
      }}
      labelColumns={labelColumns}
      errors={showErrors ? validateWorkflowDraft(draft) : undefined}
      conflictKeys={freshKeys ? [...freshKeys] : conflictKeys}
    />
  );
}

const statusRow = (n: number) => screen.getByRole("group", { name: `Status ${n}` });
const labelsOfStatuses = () => screen.getAllByRole("group", { name: /^Status \d+$/ }).map((g) => (within(g).getAllByRole("textbox")[0] as HTMLInputElement).value);

describe("WorkflowEditor", () => {
  test("lists the statuses and fields under h3 headings, with an empty hint for no fields", () => {
    renderWithProviders(<Harness initial={{ ...toRoomWorkflowDraft(ROOM), fields: [] }} />);
    expect(screen.getByRole("heading", { level: 3, name: "Statuses" })).toBeInTheDocument();
    expect(screen.getByRole("heading", { level: 3, name: "Fields" })).toBeInTheDocument();
    expect(labelsOfStatuses()).toEqual(["Open", "Doing", "Done", "Dropped"]);
    expect(screen.getByText("No fields yet")).toBeInTheDocument();
  });

  test("a status is added, filled in and made initial; a field likewise", async () => {
    const user = userEvent.setup();
    renderWithProviders(<Harness initial={emptyWorkflowDraft()} />);
    await user.click(screen.getByRole("button", { name: "Add status" }));
    await user.click(screen.getByRole("button", { name: "Add status" }));
    await user.type(within(statusRow(1)).getByRole("textbox", { name: "Label" }), "Open");
    await user.type(within(statusRow(2)).getByRole("textbox", { name: "Label" }), "Done");
    await user.click(within(statusRow(2)).getByText("Closed"));
    await user.click(within(statusRow(1)).getByRole("radio", { name: "Initial status" }));
    await user.click(screen.getByRole("button", { name: "Add field" }));
    await user.type(within(screen.getByRole("group", { name: "Field 1" })).getAllByRole("textbox", { name: "Label" })[0], "Severity");
    expect(toRoomWorkflowBody(latest)).toEqual({
      statuses: [
        { key: "open", label: "Open", colour: "GRAY", category: "OPEN", initial: true },
        { key: "done", label: "Done", colour: "GRAY", category: "CLOSED", initial: false },
      ],
      fields: [{ key: "severity", label: "Severity", type: "TEXT", required: false, options: [] }],
    });
    expect(screen.queryByText("No fields yet")).not.toBeInTheDocument();
  });

  test("statuses reorder, and a new field can be removed", async () => {
    const user = userEvent.setup();
    renderWithProviders(<Harness initial={toRoomWorkflowDraft(ROOM)} />);
    await user.click(screen.getByRole("button", { name: "Move status 3 up" }));
    expect(labelsOfStatuses()).toEqual(["Open", "Done", "Doing", "Dropped"]);
    await user.click(screen.getByRole("button", { name: "Move status 2 down" }));
    expect(labelsOfStatuses()).toEqual(["Open", "Doing", "Done", "Dropped"]);
    await user.click(screen.getByRole("button", { name: "Move field 1 down" }));
    await user.click(screen.getByRole("button", { name: "Remove field 1" }));
    expect(screen.getByText("No fields yet")).toBeInTheDocument();
  });

  test("moving a field reorders the fields", async () => {
    const user = userEvent.setup();
    const draft = toRoomWorkflowDraft(ROOM);
    renderWithProviders(<Harness initial={{ ...draft, fields: [draft.fields[0], newFieldDraft()] }} />);
    await user.click(screen.getByRole("button", { name: "Move field 1 down" }));
    expect(latest.fields.map((f) => f.key)).toEqual(["", "note"]);
  });

  test("removing a new status drops it with no notice", async () => {
    const user = userEvent.setup();
    const draft = toRoomWorkflowDraft(ROOM);
    renderWithProviders(<Harness initial={{ ...draft, statuses: [...draft.statuses, newStatusDraft()] }} />);
    await user.click(screen.getByRole("button", { name: "Remove status 5" }));
    expect(labelsOfStatuses()).toHaveLength(4);
    expect(screen.queryByRole("group", { name: "Statuses to be removed" })).not.toBeInTheDocument();
  });

  test("removing a persisted status without threads retires it at once, with an Undo notice", async () => {
    const user = userEvent.setup();
    renderWithProviders(<Harness initial={toRoomWorkflowDraft(ROOM)} />);
    await user.click(screen.getByRole("button", { name: "Remove status 3" }));
    expect(labelsOfStatuses()).toEqual(["Open", "Doing", "Dropped"]);
    const notices = screen.getByRole("group", { name: "Statuses to be removed" });
    expect(within(notices).getByText("“Done” will be removed.")).toBeInTheDocument();
    await user.click(within(notices).getByRole("button", { name: "Undo removing Done" }));
    expect(labelsOfStatuses()).toEqual(["Open", "Doing", "Done", "Dropped"]);
    expect(screen.queryByRole("group", { name: "Statuses to be removed" })).not.toBeInTheDocument();
  });

  test("removing a status that threads use asks for a replacement of the same category, then records it", async () => {
    const user = userEvent.setup();
    renderWithProviders(<Harness initial={toRoomWorkflowDraft(ROOM)} />);
    await user.click(screen.getByRole("button", { name: "Remove status 2" }));
    const dialog = screen.getByRole("dialog", { name: "Move threads before removing" });
    expect(within(dialog).getByText("5 threads are in “Doing”. Move them to:")).toBeInTheDocument();
    expect(labelsOfStatuses()).toHaveLength(4);
    await user.click(within(dialog).getByRole("combobox", { name: "Move to" }));
    // Only the remaining OPEN status is offered.
    expect(screen.getAllByRole("option").map((o) => o.textContent)).toEqual(["Open"]);
    await user.click(screen.getByRole("option", { name: "Open" }));
    await user.click(within(dialog).getByRole("button", { name: "Remove status" }));
    expect(labelsOfStatuses()).toEqual(["Open", "Done", "Dropped"]);
    expect(screen.getByText("“Doing” will be removed; its 5 threads move to “Open”.")).toBeInTheDocument();
    expect(toRoomWorkflowBody(latest).replacements).toEqual({ doing: "open" });
    await user.click(screen.getByRole("button", { name: "Undo removing Doing" }));
    expect(toRoomWorkflowBody(latest).replacements).toBeUndefined();
    expect(labelsOfStatuses()).toEqual(["Open", "Doing", "Done", "Dropped"]);
  });

  test("cancelling the replacement dialog keeps the status", async () => {
    const user = userEvent.setup();
    renderWithProviders(<Harness initial={toRoomWorkflowDraft(ROOM)} />);
    await user.click(screen.getByRole("button", { name: "Remove status 2" }));
    await user.click(within(screen.getByRole("dialog")).getByRole("button", { name: "Cancel" }));
    expect(labelsOfStatuses()).toEqual(["Open", "Doing", "Done", "Dropped"]);
    expect(screen.queryByRole("group", { name: "Statuses to be removed" })).not.toBeInTheDocument();
  });

  test("with no status of the same category left, the dialog says so", async () => {
    const user = userEvent.setup();
    const draft = toRoomWorkflowDraft(ROOM);
    renderWithProviders(<Harness initial={{ ...draft, statuses: draft.statuses.filter((s) => s.key !== "done") }} />);
    await user.click(screen.getByRole("button", { name: "Remove status 3" }));
    expect(screen.getByText("No other status of the same category is left to move them to.")).toBeInTheDocument();
  });

  test("a 409 status-in-use re-opens the dialog for each named removed status, one after another", async () => {
    const user = userEvent.setup();
    const draft = toRoomWorkflowDraft(ROOM);
    const keys = ["doing", "dropped"];
    // Both were removed without asking (their counts were stale at load time).
    const removed: WorkflowDraft = {
      ...draft,
      statuses: draft.statuses.filter((s) => s.key === "open" || s.key === "done"),
      retired: [
        { status: { ...draft.statuses[1], threadCount: 0 }, index: 1, replacementId: null },
        { status: { ...draft.statuses[3], threadCount: 0 }, index: 3, replacementId: null },
      ],
    };
    renderWithProviders(<Harness initial={removed} conflictKeys={keys} />);
    let dialog = screen.getByRole("dialog", { name: "Move threads before removing" });
    expect(within(dialog).getByText("Threads have joined “Doing” since you opened this page. Move them to:")).toBeInTheDocument();
    await user.click(within(dialog).getByRole("combobox", { name: "Move to" }));
    await user.click(screen.getByRole("option", { name: "Open" }));
    await user.click(within(dialog).getByRole("button", { name: "Remove status" }));
    expect(latest.retired[0].replacementId).toBe(draft.statuses[0].id);

    dialog = screen.getByRole("dialog", { name: "Move threads before removing" });
    expect(within(dialog).getByText(/“Dropped”/)).toBeInTheDocument();
    await user.click(within(dialog).getByRole("combobox", { name: "Move to" }));
    await user.click(screen.getByRole("option", { name: "Done" }));
    await user.click(within(dialog).getByRole("button", { name: "Remove status" }));
    expect(toRoomWorkflowBody(latest).replacements).toEqual({ doing: "open", dropped: "done" });
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });

  test("dismissing a re-opened dialog skips that status; a fresh conflict asks again", async () => {
    const user = userEvent.setup();
    const draft = toRoomWorkflowDraft(ROOM);
    const removed: WorkflowDraft = {
      ...draft,
      statuses: draft.statuses.filter((s) => s.key !== "doing"),
      retired: [{ status: { ...draft.statuses[1], threadCount: 0 }, index: 1, replacementId: null }],
    };
    const first = ["doing"];
    const { rerender } = renderWithProviders(<Harness initial={removed} conflictKeys={first} />);
    await user.click(within(screen.getByRole("dialog")).getByRole("button", { name: "Cancel" }));
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(screen.getByText("“Doing” will be removed.")).toBeInTheDocument();
    // The same keys stay dismissed (by content, whatever the array identity); a page asks again by clearing them first.
    rerender(<Harness initial={removed} conflictKeys={["doing"]} />);
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    rerender(<Harness initial={removed} conflictKeys={undefined} />);
    rerender(<Harness initial={removed} conflictKeys={first} />);
    expect(await screen.findByRole("dialog")).toBeInTheDocument();
  });

  test("Cancel sticks when the conflict keys are a fresh array on every render", async () => {
    const user = userEvent.setup();
    const draft = toRoomWorkflowDraft(ROOM);
    const removed: WorkflowDraft = {
      ...draft,
      statuses: draft.statuses.filter((s) => s.key !== "doing"),
      retired: [{ status: { ...draft.statuses[1], threadCount: 0 }, index: 1, replacementId: null }],
    };
    renderWithProviders(<Harness initial={removed} freshKeys={["doing"]} />);
    await user.click(within(screen.getByRole("dialog")).getByRole("button", { name: "Cancel" }));
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Add field" }));
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });

  test("a replacement whose target is removed afterwards goes stale, and a 409 asks again", async () => {
    const user = userEvent.setup();
    const base = toRoomWorkflowDraft(ROOM);
    // Triage is a persisted OPEN status without threads; Doing's threads were sent there, then Triage was removed too.
    const triage = { ...base.statuses[0], id: "triage", key: "triage", labels: { en: "Triage", pl: "" }, initial: false };
    const doing = base.statuses[1];
    const draft: WorkflowDraft = {
      ...base,
      statuses: [base.statuses[0], base.statuses[2], base.statuses[3]],
      retired: [
        { status: doing, index: 1, replacementId: "triage" },
        { status: triage, index: 2, replacementId: null },
      ],
    };
    expect(toRoomWorkflowBody(draft).replacements).toBeUndefined();
    renderWithProviders(<Harness initial={draft} conflictKeys={["doing"]} />);
    const dialog = screen.getByRole("dialog", { name: "Move threads before removing" });
    expect(within(dialog).getByText("5 threads are in “Doing”. Move them to:")).toBeInTheDocument();
    await user.click(within(dialog).getByRole("combobox", { name: "Move to" }));
    expect(screen.getAllByRole("option").map((o) => o.textContent)).toEqual(["Open"]);
    await user.click(screen.getByRole("option", { name: "Open" }));
    await user.click(within(dialog).getByRole("button", { name: "Remove status" }));
    expect(toRoomWorkflowBody(latest).replacements).toEqual({ doing: "open" });
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });

  test("new statuses with empty or duplicate keys are never offered as a replacement", async () => {
    const user = userEvent.setup();
    renderWithProviders(<Harness initial={toRoomWorkflowDraft(ROOM)} />);
    await user.click(screen.getByRole("button", { name: "Add status" }));
    await user.click(screen.getByRole("button", { name: "Add status" }));
    await user.click(screen.getByRole("button", { name: "Remove status 2" }));
    const dialog = screen.getByRole("dialog", { name: "Move threads before removing" });
    await user.click(within(dialog).getByRole("combobox", { name: "Move to" }));
    expect(screen.getAllByRole("option").map((o) => o.textContent)).toEqual(["Open"]);
  });

  test("the initial radios form one labelled radiogroup", () => {
    renderWithProviders(<Harness initial={toRoomWorkflowDraft(ROOM)} />);
    const group = screen.getByRole("radiogroup", { name: "Initial status" });
    expect(within(group).getAllByRole("radio", { name: "Initial status" })).toHaveLength(4);
  });

  test("conflict keys that are not removed or already have a replacement open nothing", () => {
    const draft = toRoomWorkflowDraft(ROOM);
    const answered: WorkflowDraft = {
      ...draft,
      retired: [{ status: draft.statuses[1], index: 1, replacementId: draft.statuses[0].id }],
    };
    renderWithProviders(<Harness initial={answered} conflictKeys={["doing", "done", "ghost"]} />);
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });

  test("validation errors show once passed in: list level and per row", () => {
    renderWithProviders(<Harness initial={{ ...emptyWorkflowDraft(), statuses: [newStatusDraft()], fields: [newFieldDraft()] }} showErrors />);
    expect(screen.getByText("A workflow needs 2–20 statuses")).toBeInTheDocument();
    expect(screen.getByText("Pick exactly one initial status")).toBeInTheDocument();
    expect(screen.getByText("At least one status must be Closed")).toBeInTheDocument();
    expect(within(statusRow(1)).getByText("The label is required")).toBeInTheDocument();
    expect(within(screen.getByRole("group", { name: "Field 1" })).getByText("The label is required")).toBeInTheDocument();
  });

  test("the field count error and the add limits", () => {
    const base = toRoomWorkflowDraft(ROOM);
    const statuses = Array.from({ length: 20 }, (_, i) => ({ ...base.statuses[0], id: `s${i}`, key: `s${i}` }));
    const fields = Array.from({ length: 21 }, (_, i) => ({ ...base.fields[0], id: `f${i}`, key: `f${i}` }));
    renderWithProviders(<Harness initial={{ ...base, statuses, fields }} showErrors />);
    expect(screen.getByRole("button", { name: "Add status" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "Add field" })).toBeDisabled();
    expect(screen.getByText("A workflow has at most 20 fields")).toBeInTheDocument();
  });

  test("no errors are shown until asked for", () => {
    renderWithProviders(<Harness initial={{ ...emptyWorkflowDraft(), statuses: [newStatusDraft()] }} />);
    expect(screen.queryByText("The label is required")).not.toBeInTheDocument();
  });

  test("a template editor has a label column per language", () => {
    renderWithProviders(<Harness initial={toRoomWorkflowDraft(ROOM)} labelColumns={2} />);
    expect(within(statusRow(1)).getByRole("textbox", { name: "Label (EN)" })).toHaveValue("Open");
    expect(within(statusRow(1)).getByRole("textbox", { name: "Label (PL)" })).toHaveValue("");
  });
});
