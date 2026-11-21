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

  test("passes style props through to the anchor", () => {
    renderWithProviders(<PersonLink id={1} name="Bob" fw={600} />);
    expect(screen.getByRole("link", { name: "Bob" })).toHaveStyle({ "--text-fw": "600" });
  });
});
