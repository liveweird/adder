import { describe, expect, test, vi } from "vitest";
import { useState } from "react";
import userEvent from "@testing-library/user-event";
import WorkflowFieldRow from "./WorkflowFieldRow";
import { renderWithProviders, screen, within } from "../test/render";
import {
  newFieldDraft,
  newOptionDraft,
  toRoomWorkflowDraft,
  withKey,
  withLabel,
  type FieldDraft,
  type WorkflowErrors,
} from "../utils/workflowForm";

const PERSISTED = toRoomWorkflowDraft({
  revision: 1,
  statuses: [],
  fields: [
    { key: "sev", label: "Severity", type: "SELECT", required: true, options: [{ key: "s1", label: "S1" }, { key: "s2", label: "S2" }] },
  ],
}).fields[0];

function Harness({
  initial,
  rowErrors = {},
  labelColumns = 1,
  spy,
}: {
  initial: FieldDraft;
  rowErrors?: WorkflowErrors["rows"];
  labelColumns?: 1 | 2;
  spy?: { onMove?: (d: -1 | 1) => void; onRemove?: () => void; onChange?: (f: FieldDraft) => void };
}) {
  const [field, setField] = useState(initial);
  return (
    <WorkflowFieldRow
      field={field}
      index={0}
      count={2}
      labelColumns={labelColumns}
      rowErrors={rowErrors}
      onChange={(next) => {
        spy?.onChange?.(next);
        setField(next);
      }}
      onMove={spy?.onMove ?? (() => {})}
      onRemove={spy?.onRemove ?? (() => {})}
    />
  );
}

const row = () => screen.getByRole("group", { name: "Field 1" });
const options = () => within(row()).getByRole("group", { name: "Options" });
const optionRow = (n: number) => within(options()).getAllByRole("textbox", { name: "Key" })[n - 1];

describe("WorkflowFieldRow", () => {
  test("a new field starts as plain text with no options section; its key follows the label", async () => {
    const user = userEvent.setup();
    renderWithProviders(<Harness initial={newFieldDraft()} />);
    expect(within(row()).getByRole("combobox", { name: "Type" })).toHaveValue("Text");
    expect(within(row()).queryByRole("group", { name: "Options" })).not.toBeInTheDocument();
    await user.type(within(row()).getAllByRole("textbox", { name: "Label" })[0], "Jira link");
    expect(within(row()).getAllByRole("textbox", { name: "Key" })[0]).toHaveValue("jira-link");
  });

  test("the type of a persisted field is fixed", () => {
    renderWithProviders(<Harness initial={PERSISTED} />);
    expect(within(row()).getByRole("combobox", { name: "Type" })).toBeDisabled();
    expect(within(row()).getByText("The type of a saved field cannot change.")).toBeInTheDocument();
    expect(within(row()).getByRole("switch", { name: "Required" })).toBeChecked();
  });

  test("choosing a select type reveals the options, which can be added, edited, reordered and removed", async () => {
    const user = userEvent.setup();
    renderWithProviders(<Harness initial={newFieldDraft()} />);
    await user.click(within(row()).getByRole("combobox", { name: "Type" }));
    await user.click(await screen.findByRole("option", { name: "Multi-select" }));
    expect(options()).toBeInTheDocument();

    await user.click(within(options()).getByRole("button", { name: "Add option" }));
    await user.click(within(options()).getByRole("button", { name: "Add option" }));
    await user.type(within(options()).getAllByRole("textbox", { name: "Label" })[0], "High");
    await user.type(within(options()).getAllByRole("textbox", { name: "Label" })[1], "Low");
    expect(optionRow(1)).toHaveValue("high");
    expect(optionRow(2)).toHaveValue("low");

    await user.click(within(options()).getByRole("button", { name: "Move option 2 up" }));
    expect(optionRow(1)).toHaveValue("low");
    await user.click(within(options()).getByRole("button", { name: "Move option 1 down" }));
    expect(optionRow(1)).toHaveValue("high");
    await user.click(within(options()).getByRole("button", { name: "Remove option 1" }));
    expect(within(options()).getAllByRole("textbox", { name: "Key" })).toHaveLength(1);
    expect(optionRow(1)).toHaveValue("low");
  });

  test("a persisted option's key is read-only", () => {
    renderWithProviders(<Harness initial={PERSISTED} />);
    expect(optionRow(1)).toHaveAttribute("readonly");
    expect(optionRow(1)).toHaveValue("s1");
  });

  test("a template field edits an English and a Polish label per option", () => {
    renderWithProviders(<Harness initial={PERSISTED} labelColumns={2} />);
    expect(within(options()).getAllByRole("textbox", { name: "Label (PL)" })).toHaveLength(2);
  });

  test("switching to a type without options hides them (the draft keeps them for a switch back)", async () => {
    const user = userEvent.setup();
    const onChange = vi.fn();
    renderWithProviders(<Harness initial={{ ...PERSISTED, persisted: false }} spy={{ onChange }} />);
    await user.click(within(row()).getByRole("combobox", { name: "Type" }));
    await user.click(await screen.findByRole("option", { name: "Number" }));
    expect(within(row()).queryByRole("group", { name: "Options" })).not.toBeInTheDocument();
    expect(onChange).toHaveBeenLastCalledWith(expect.objectContaining({ type: "NUMBER", options: PERSISTED.options }));
  });

  test("Required toggles", async () => {
    const user = userEvent.setup();
    renderWithProviders(<Harness initial={newFieldDraft()} />);
    await user.click(within(row()).getByRole("switch", { name: "Required" }));
    expect(within(row()).getByRole("switch", { name: "Required" })).toBeChecked();
  });

  test("move and remove carry the field's position", async () => {
    const user = userEvent.setup();
    const spy = { onMove: vi.fn(), onRemove: vi.fn() };
    renderWithProviders(<Harness initial={newFieldDraft()} spy={spy} />);
    await user.click(screen.getByRole("button", { name: "Move field 1 down" }));
    await user.click(screen.getByRole("button", { name: "Remove field 1" }));
    expect(spy.onMove).toHaveBeenCalledWith(1);
    expect(spy.onRemove).toHaveBeenCalledTimes(1);
  });

  test("errors for the field, its options and the option count are shown", () => {
    const option = withLabel(withKey(newOptionDraft(), "BAD"), "en", "");
    const field: FieldDraft = { ...newFieldDraft(), type: "SELECT", options: [{ ...option, key: "BAD" }] };
    renderWithProviders(
      <Harness
        initial={field}
        rowErrors={{
          [field.id]: { key: { key: "workflows.validation.keyDuplicate" }, options: { key: "workflows.validation.optionCount", params: { min: 1, max: 50 } } },
          [field.options[0].id]: { key: { key: "workflows.validation.keyInvalid", params: { max: 40 } } },
        }}
      />,
    );
    expect(within(row()).getByText("This key is already used")).toBeInTheDocument();
    expect(within(row()).getByText("A select field needs 1–50 options")).toBeInTheDocument();
    expect(within(options()).getByText(/The key must be 1–40 characters/)).toBeInTheDocument();
  });

  test("the option list is capped at 50", () => {
    const full: FieldDraft = {
      ...newFieldDraft(),
      type: "SELECT",
      options: Array.from({ length: 50 }, (_, i) => ({ ...newOptionDraft(), key: `o${i}` })),
    };
    renderWithProviders(<Harness initial={full} />);
    expect(within(options()).getByRole("button", { name: "Add option" })).toBeDisabled();
  });
});
