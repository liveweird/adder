import { describe, expect, test } from "vitest";
import ValidationError from "./ValidationError";
import { renderWithProviders, screen } from "../test/render";

describe("ValidationError", () => {
  test("renders the translated message with its params", () => {
    renderWithProviders(<ValidationError message={{ key: "workflows.validation.fieldCount", params: { max: 20 } }} />);
    expect(screen.getByRole("alert")).toHaveTextContent("A workflow has at most 20 fields");
  });

  test("renders nothing without a message", () => {
    const { container } = renderWithProviders(<ValidationError />);
    expect(container.querySelector("p")).toBeNull();
  });
});
