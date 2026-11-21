import { describe, expect, test } from "vitest";
import { renderWithProviders, screen } from "../test/render";
import PersonLink from "./PersonLink";

describe("PersonLink", () => {
  test("is a link named after the person that opens their profile", () => {
    renderWithProviders(<PersonLink id={42} name="Ada Lovelace" />);
    expect(screen.getByRole("link", { name: "Ada Lovelace" })).toHaveAttribute("href", "/people/42");
  });

  test("is underlined at rest, a link by shape and not only by colour, unless the caller says otherwise", () => {
    renderWithProviders(
      <>
        <PersonLink id={1} name="Always" />
        <PersonLink id={2} name="Hover" underline="hover" />
      </>,
    );
    expect(screen.getByRole("link", { name: "Always" })).toHaveAttribute("data-underline", "always");
    expect(screen.getByRole("link", { name: "Hover" })).toHaveAttribute("data-underline", "hover");
  });

  test("a quiet link keeps the underline and mutes its ink; the profile still opens", () => {
    renderWithProviders(<PersonLink id={3} name="Quiet" quiet />);
    const link = screen.getByRole("link", { name: "Quiet" });
    expect(link).toHaveAttribute("data-underline", "always");
    expect(link).toHaveAttribute("href", "/people/3");
    expect(link.className).toMatch(/anchorQuiet/);
  });

  test("a caller's className is kept beside the quiet one", () => {
    renderWithProviders(<PersonLink id={3} name="Both" quiet className="mine" />);
    const link = screen.getByRole("link", { name: "Both" });
    expect(link).toHaveClass("mine");
    expect(link.className).toMatch(/anchorQuiet/);
  });

  test("inherit takes the surrounding colour as well as the font, unless a colour is given", () => {
    renderWithProviders(
      <>
        <PersonLink id={1} name="Inherits" inherit />
        <PersonLink id={2} name="Own" inherit c="red" />
        <PersonLink id={3} name="Plain" />
      </>,
    );
    const colour = (name: string) => screen.getByRole("link", { name }).style.color;
    expect(colour("Inherits")).toBe("inherit");
    expect(colour("Own")).not.toBe("inherit");
    expect(colour("Plain")).toBe("");
  });

  test("passes style props through to the anchor", () => {
    renderWithProviders(<PersonLink id={1} name="Bob" fw={600} />);
    expect(screen.getByRole("link", { name: "Bob" })).toHaveStyle({ "--text-fw": "600" });
  });
});
