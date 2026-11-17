import { describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import StatusColourSelect from "./StatusColourSelect";
import { renderWithProviders, screen } from "../test/render";

describe("StatusColourSelect", () => {
  test("the input reads the colour's name and meaning", () => {
    renderWithProviders(<StatusColourSelect value="BLUE" onChange={() => {}} />);
    expect(screen.getByRole("combobox", { name: "Colour" })).toHaveValue("Blue — in progress");
  });

  test("offers exactly the six colours, each with a pill and its meaning, and picks one", async () => {
    const user = userEvent.setup();
    const onChange = vi.fn();
    renderWithProviders(<StatusColourSelect value="GRAY" onChange={onChange} />);
    await user.click(screen.getByRole("combobox", { name: "Colour" }));
    const options = await screen.findAllByRole("option");
    expect(options.map((o) => o.textContent)).toEqual([
      "Grayneutral",
      "Bluein progress",
      "Grapewaiting",
      "Tealdone",
      "Orangewarning",
      "Redblocking",
    ]);
    await user.click(screen.getByRole("option", { name: /Teal/ }));
    expect(onChange).toHaveBeenCalledWith("TEAL");
  });

  test("a custom label and the disabled state are honoured", () => {
    renderWithProviders(<StatusColourSelect value="RED" onChange={() => {}} label="Hue" disabled />);
    expect(screen.getByRole("combobox", { name: "Hue" })).toBeDisabled();
  });
});
