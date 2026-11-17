import { describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import RetireStatusModal from "./RetireStatusModal";
import { renderWithProviders, screen } from "../test/render";
import { newStatusDraft, withKey, withLabel, type StatusDraft } from "../utils/workflowForm";

const status = (label: string, threadCount = 0): StatusDraft => ({
  ...withKey(withLabel(newStatusDraft(), "en", label), label.toLowerCase()),
  threadCount,
});

const CANDIDATES = [status("Open"), status("Review")];

function renderModal(props: Partial<Parameters<typeof RetireStatusModal>[0]> = {}) {
  const onConfirm = vi.fn();
  const onCancel = vi.fn();
  const view = renderWithProviders(
    <RetireStatusModal
      status={status("Doing", 4)}
      candidates={CANDIDATES}
      onConfirm={onConfirm}
      onCancel={onCancel}
      {...props}
    />,
  );
  return { onConfirm, onCancel, ...view };
}

describe("RetireStatusModal", () => {
  test("asks where the threads go and confirms the picked status", async () => {
    const user = userEvent.setup();
    const { onConfirm } = renderModal();
    expect(screen.getByText("4 threads are in “Doing”. Move them to:")).toBeInTheDocument();
    const confirm = screen.getByRole("button", { name: "Remove status" });
    expect(confirm).toBeDisabled();
    await user.click(screen.getByRole("combobox", { name: "Move to" }));
    await user.click(await screen.findByRole("option", { name: "Review" }));
    await user.click(confirm);
    expect(onConfirm).toHaveBeenCalledWith(CANDIDATES[1].id);
  });

  test("the message is singular for one thread", () => {
    renderModal({ status: status("Doing", 1) });
    expect(screen.getByText("1 thread is in “Doing”. Move it to:")).toBeInTheDocument();
  });

  test("a stale count (the server knows better) says threads have joined", () => {
    renderModal({ status: status("Doing", 0) });
    expect(screen.getByText("Threads have joined “Doing” since you opened this page. Move them to:")).toBeInTheDocument();
  });

  test("Cancel and the close button both cancel, and the pick is forgotten", async () => {
    const user = userEvent.setup();
    const { onCancel, onConfirm } = renderModal();
    await user.click(screen.getByRole("button", { name: "Cancel" }));
    await user.click(screen.getByRole("button", { name: "Close" }));
    expect(onCancel).toHaveBeenCalledTimes(2);
    expect(onConfirm).not.toHaveBeenCalled();
  });

  test("a pick does not survive a cancel", async () => {
    const user = userEvent.setup();
    renderModal();
    await user.click(screen.getByRole("combobox", { name: "Move to" }));
    await user.click(await screen.findByRole("option", { name: "Open" }));
    await user.click(screen.getByRole("button", { name: "Cancel" }));
    expect(screen.getByRole("combobox", { name: "Move to" })).toHaveValue("");
  });

  test("no candidate left: says so and cannot confirm", () => {
    renderModal({ candidates: [] });
    expect(screen.getByText("No other status of the same category is left to move them to.")).toBeInTheDocument();
    expect(screen.queryByRole("combobox", { name: "Move to" })).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Remove status" })).toBeDisabled();
  });

  test("closed while status is null", () => {
    renderModal({ status: null });
    expect(screen.queryByText("Move threads before removing")).not.toBeInTheDocument();
  });
});
