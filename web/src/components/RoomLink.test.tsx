import { describe, expect, test } from "vitest";
import { renderWithProviders, screen } from "../test/render";
import RoomLink from "./RoomLink";

describe("RoomLink", () => {
  test("is a link named after the room that opens it", () => {
    renderWithProviders(<RoomLink id={12} name="Night Shift" />);
    expect(screen.getByRole("link", { name: "Night Shift" })).toHaveAttribute("href", "/rooms/12");
  });

  test("is underlined at rest like a person's link, quiet or not", () => {
    renderWithProviders(
      <>
        <RoomLink id={1} name="Loud" />
        <RoomLink id={2} name="Soft" quiet />
      </>,
    );
    expect(screen.getByRole("link", { name: "Loud" })).toHaveAttribute("data-underline", "always");
    expect(screen.getByRole("link", { name: "Soft" })).toHaveAttribute("data-underline", "always");
    expect(screen.getByRole("link", { name: "Soft" }).className).toMatch(/anchorQuiet/);
  });

  test("inherit takes the surrounding colour, and a caller's className survives", () => {
    renderWithProviders(<RoomLink id={1} name="Dim" inherit quiet className="mine" />);
    const link = screen.getByRole("link", { name: "Dim" });
    expect(link.style.color).toBe("inherit");
    expect(link).toHaveClass("mine");
    expect(link.className).toMatch(/anchorQuiet/);
  });
});
