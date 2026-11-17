import { describe, expect, test, vi } from "vitest";
import { useState } from "react";
import userEvent from "@testing-library/user-event";
import WorkflowStatusRow from "./WorkflowStatusRow";
import { renderWithProviders, screen, within } from "../test/render";
import { newStatusDraft, toRoomWorkflowDraft, type RowErrors, type StatusDraft } from "../utils/workflowForm";

const PERSISTED = toRoomWorkflowDraft({
  revision: 1,
  statuses: [
    { key: "doing", label: "Doing", colour: "BLUE", category: "OPEN", initial: false, threadCount: 3 },
    { key: "done", label: "Done", colour: "TEAL", category: "CLOSED", initial: false, threadCount: 0 },
  ],
  fields: [],
}).statuses;

function Harness({
  initial,
  labelColumns = 1,
  errors,
  index = 1,
  count = 3,
  spy,
}: {
  initial: StatusDraft;
  labelColumns?: 1 | 2;
  errors?: RowErrors;
  index?: number;
  count?: number;
  spy?: { onSetInitial?: () => void; onMove?: (d: -1 | 1) => void; onRemove?: () => void; onChange?: (s: StatusDraft) => void };
}) {
  const [status, setStatus] = useState(initial);
  return (
    <WorkflowStatusRow
      status={status}
      index={index}
      count={count}
      labelColumns={labelColumns}
      errors={errors}
      onChange={(next) => {
        spy?.onChange?.(next);
        setStatus(next);
      }}
      onSetInitial={spy?.onSetInitial ?? (() => {})}
      onMove={spy?.onMove ?? (() => {})}
      onRemove={spy?.onRemove ?? (() => {})}
    />
  );
}

const row = () => screen.getByRole("group", { name: "Status 2" });

describe("WorkflowStatusRow", () => {
  test("a new row derives its key from the label until the key is edited", async () => {
    const user = userEvent.setup();
    renderWithProviders(<Harness initial={newStatusDraft()} />);
    const label = within(row()).getByRole("textbox", { name: "Label" });
    const key = within(row()).getByRole("textbox", { name: "Key" });
    await user.type(label, "Won't do");
    expect(key).toHaveValue("won-t-do");
    await user.clear(key);
    await user.type(key, "nope");
    await user.type(label, "!");
    expect(key).toHaveValue("nope");
    expect(key).not.toHaveAttribute("readonly");
  });

  test("a persisted row's key is read-only and says why", () => {
    renderWithProviders(<Harness initial={PERSISTED[0]} />);
    expect(within(row()).getByRole("textbox", { name: "Key" })).toHaveAttribute("readonly");
    expect(within(row()).getByText("The key of a saved item cannot change.")).toBeInTheDocument();
  });

  test("a template row has an English and a Polish label column", async () => {
    const user = userEvent.setup();
    renderWithProviders(<Harness initial={newStatusDraft()} labelColumns={2} />);
    await user.type(within(row()).getByRole("textbox", { name: "Label (EN)" }), "Open");
    await user.type(within(row()).getByRole("textbox", { name: "Label (PL)" }), "Otwarte");
    expect(within(row()).getByRole("textbox", { name: "Key" })).toHaveValue("open");
    expect(within(row()).queryByRole("textbox", { name: "Label" })).not.toBeInTheDocument();
  });

  test("validation messages land on the inputs they are about", () => {
    renderWithProviders(
      <Harness
        initial={newStatusDraft()}
        labelColumns={2}
        errors={{
          key: { key: "workflows.validation.keyDuplicate" },
          labels: { en: { key: "workflows.validation.labelRequired" }, pl: { key: "workflows.validation.labelTooLong", params: { max: 60 } } },
        }}
      />,
    );
    expect(within(row()).getByText("This key is already used")).toBeInTheDocument();
    expect(within(row()).getByRole("textbox", { name: "Label (EN)" })).toHaveAccessibleDescription("The label is required");
    expect(within(row()).getByText("The label must be at most 60 characters")).toBeInTheDocument();
  });

  test("the colour and the category are editable", async () => {
    const user = userEvent.setup();
    const onChange = vi.fn();
    renderWithProviders(<Harness initial={PERSISTED[1]} spy={{ onChange }} />);
    await user.click(within(row()).getByRole("combobox", { name: "Colour" }));
    await user.click(await screen.findByRole("option", { name: /Orange/ }));
    expect(onChange).toHaveBeenLastCalledWith(expect.objectContaining({ colour: "ORANGE" }));
    await user.click(within(row()).getByText("Open"));
    expect(onChange).toHaveBeenLastCalledWith(expect.objectContaining({ category: "OPEN", colour: "ORANGE" }));
    expect(within(row()).queryByText("Threads use this status, so its category cannot change.")).not.toBeInTheDocument();
  });

  test("the category is locked while threads use the status", () => {
    renderWithProviders(<Harness initial={PERSISTED[0]} />);
    expect(within(row()).getByRole("radio", { name: "Closed" })).toBeDisabled();
    expect(within(row()).getByText("Threads use this status, so its category cannot change.")).toBeInTheDocument();
  });

  test("the initial radio works on an OPEN row and is disabled on a CLOSED one", async () => {
    const user = userEvent.setup();
    const onSetInitial = vi.fn();
    const { unmount } = renderWithProviders(<Harness initial={PERSISTED[0]} spy={{ onSetInitial }} />);
    await user.click(within(row()).getByRole("radio", { name: "Initial status" }));
    expect(onSetInitial).toHaveBeenCalledTimes(1);
    unmount();
    renderWithProviders(<Harness initial={PERSISTED[1]} />);
    expect(within(row()).getByRole("radio", { name: "Initial status" })).toBeDisabled();
  });

  test("move and remove carry the row's position in their labels", async () => {
    const user = userEvent.setup();
    const spy = { onMove: vi.fn(), onRemove: vi.fn() };
    renderWithProviders(<Harness initial={PERSISTED[0]} spy={spy} />);
    await user.click(screen.getByRole("button", { name: "Move status 2 up" }));
    await user.click(screen.getByRole("button", { name: "Move status 2 down" }));
    await user.click(screen.getByRole("button", { name: "Remove status 2" }));
    expect(spy.onMove.mock.calls).toEqual([[-1], [1]]);
    expect(spy.onRemove).toHaveBeenCalledTimes(1);
  });
});
