import { describe, expect, test } from "vitest";
import userEvent from "@testing-library/user-event";
import RoomAbout from "./RoomAbout";
import { renderWithProviders, screen, waitFor } from "../test/render";

describe("RoomAbout", () => {
  test("the description sits behind an About this room button and shows as text when it is pressed", async () => {
    renderWithProviders(<RoomAbout description={"Handover notes <b>not html</b>"} />);
    expect(screen.queryByText(/Handover notes/)).not.toBeInTheDocument();
    const button = screen.getByRole("button", { name: "About this room" });
    expect(button).toHaveAttribute("aria-haspopup", "dialog");
    expect(button).toHaveAttribute("aria-expanded", "false");
    await userEvent.click(button);
    const text = await screen.findByText(/Handover notes/);
    // The popover is a named dialog and the button says it controls it, so a screen reader announces both.
    const dialog = await screen.findByRole("dialog", { name: "About this room" });
    expect(dialog).toContainElement(text);
    expect(button).toHaveAttribute("aria-expanded", "true");
    expect(button).toHaveAttribute("aria-controls", dialog.id);
    expect(text).toHaveTextContent("<b>not html</b>");
    expect(text.querySelector("b")).toBeNull();
  });

  test("Escape closes it and gives focus back to the button", async () => {
    renderWithProviders(<RoomAbout description="Handover notes" />);
    const button = screen.getByRole("button", { name: "About this room" });
    await userEvent.click(button);
    expect(await screen.findByText("Handover notes")).toBeInTheDocument();
    await userEvent.keyboard("{Escape}");
    await waitFor(() => expect(screen.queryByText("Handover notes")).not.toBeInTheDocument());
    expect(button).toHaveFocus();
  });

  test("a room with no description has no button", () => {
    renderWithProviders(<RoomAbout description={null} />);
    expect(screen.queryByRole("button")).not.toBeInTheDocument();
  });
});
