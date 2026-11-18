import { describe, expect, test } from "vitest";
import { renderWithProviders, screen } from "../test/render";
import { headingOutline } from "../test/headings";
import Home from "./Home";

describe("Home", () => {
  test("is a placeholder: the Adder title and a hint to open or create a room, with threads still to come", () => {
    renderWithProviders(<Home />);
    expect(screen.getByRole("heading", { level: 2, name: "Adder" })).toBeInTheDocument();
    expect(screen.getByText("Welcome to Adder")).toBeInTheDocument();
    expect(screen.getByText(/Open a room from the sidebar or create a new one/)).toBeInTheDocument();
    expect(headingOutline()).toEqual([[2, "Adder"]]);
  });
});
