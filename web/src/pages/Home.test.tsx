import { describe, expect, test } from "vitest";
import { renderWithProviders, screen } from "../test/render";
import { headingOutline } from "../test/headings";
import Home from "./Home";

describe("Home", () => {
  test("is a placeholder: the Adder title and a hint that rooms and threads are coming", () => {
    renderWithProviders(<Home />);
    expect(screen.getByRole("heading", { level: 2, name: "Adder" })).toBeInTheDocument();
    expect(screen.getByText("Welcome to Adder")).toBeInTheDocument();
    expect(screen.getByText(/Rooms and threads are coming/)).toBeInTheDocument();
    expect(headingOutline()).toEqual([[2, "Adder"]]);
  });
});
