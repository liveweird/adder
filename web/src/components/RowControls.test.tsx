import { describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import RowControls from "./RowControls";
import { renderWithProviders, screen } from "../test/render";

function renderControls(props: { index: number; count: number; removeDisabled?: boolean }) {
  const handlers = { onMoveUp: vi.fn(), onMoveDown: vi.fn(), onRemove: vi.fn() };
  renderWithProviders(
    <RowControls
      {...props}
      {...handlers}
      moveUpLabel="Move up 2"
      moveDownLabel="Move down 2"
      removeLabel="Remove 2"
    />,
  );
  return handlers;
}

describe("RowControls", () => {
  test("each button reaches its handler under the translated label", async () => {
    const user = userEvent.setup();
    const handlers = renderControls({ index: 1, count: 3 });
    await user.click(screen.getByRole("button", { name: "Move up 2" }));
    await user.click(screen.getByRole("button", { name: "Move down 2" }));
    await user.click(screen.getByRole("button", { name: "Remove 2" }));
    expect(handlers.onMoveUp).toHaveBeenCalledTimes(1);
    expect(handlers.onMoveDown).toHaveBeenCalledTimes(1);
    expect(handlers.onRemove).toHaveBeenCalledTimes(1);
  });

  test("move up is disabled on the first row and move down on the last", () => {
    renderControls({ index: 0, count: 1 });
    expect(screen.getByRole("button", { name: "Move up 2" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "Move down 2" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "Remove 2" })).toBeEnabled();
  });

  test("a middle row can move both ways", () => {
    renderControls({ index: 1, count: 3 });
    expect(screen.getByRole("button", { name: "Move up 2" })).toBeEnabled();
    expect(screen.getByRole("button", { name: "Move down 2" })).toBeEnabled();
  });

  test("a locked row cannot be removed but can still move", async () => {
    const user = userEvent.setup();
    const handlers = renderControls({ index: 1, count: 3, removeDisabled: true });
    expect(screen.getByRole("button", { name: "Remove 2" })).toBeDisabled();
    await user.click(screen.getByRole("button", { name: "Move up 2" }));
    expect(handlers.onMoveUp).toHaveBeenCalledTimes(1);
  });
});
