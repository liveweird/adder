import { describe, expect, test } from "vitest";
import { useState } from "react";
import userEvent from "@testing-library/user-event";
import { screen, within } from "@testing-library/react";
import { renderWithProviders } from "../test/render";
import FilterMultiSelect from "./FilterMultiSelect";

const DATA = [
  { value: "a", label: "Alpha" },
  { value: "z", label: "Żółw" },
  { value: "c", label: "Charlie" },
];

function Harness({ seen }: { seen?: string[][] }) {
  const [value, setValue] = useState<string[]>([]);
  return (
    <FilterMultiSelect
      label="Things"
      data={DATA}
      value={value}
      onChange={(next) => {
        seen?.push(next);
        setValue(next);
      }}
    />
  );
}

describe("FilterMultiSelect", () => {
  test("an empty selection reads Any; picks accumulate in order and the dropdown stays open", async () => {
    const user = userEvent.setup();
    const seen: string[][] = [];
    renderWithProviders(<Harness seen={seen} />);

    const field = screen.getByRole("combobox", { name: "Things" });
    expect(field).toHaveAttribute("placeholder", "Any");
    await user.click(field);
    await user.click(await screen.findByRole("option", { name: "Charlie" }));
    await user.click(screen.getByRole("option", { name: "Alpha" }));
    expect(seen).toEqual([["c"], ["c", "a"]]);
    // With picks the "Any" placeholder is gone.
    expect(screen.getByRole("combobox", { name: "Things" })).not.toHaveAttribute("placeholder", "Any");
  });

  test("each pill has a named, focusable remove button that removes only that value", async () => {
    const user = userEvent.setup();
    const seen: string[][] = [];
    renderWithProviders(<Harness seen={seen} />);

    await user.click(screen.getByRole("combobox", { name: "Things" }));
    await user.click(await screen.findByRole("option", { name: "Alpha" }));
    await user.click(screen.getByRole("option", { name: "Charlie" }));
    const remove = screen.getByRole("button", { name: "Remove Alpha" });
    expect(remove).toHaveAttribute("aria-hidden", "false");
    expect(remove).toHaveAttribute("tabindex", "0");
    await user.click(remove);
    expect(seen.at(-1)).toEqual(["c"]);
  });

  test("search folds accents (the theme-level foldedOptionsFilter)", async () => {
    const user = userEvent.setup();
    renderWithProviders(<Harness />);

    const field = screen.getByRole("combobox", { name: "Things" });
    await user.click(field);
    await user.type(field, "zolw");
    expect(await screen.findByRole("option", { name: "Żółw" })).toBeInTheDocument();
    expect(screen.queryByRole("option", { name: "Alpha", hidden: false })).toBeNull();
  });

  test("a selected value absent from data renders a pill with the raw value instead of crashing", () => {
    // Mantine hands renderPill `option: undefined` for such a value (a stored pick whose options load late, or were removed).
    renderWithProviders(<FilterMultiSelect label="Things" data={DATA} value={["ghost"]} onChange={() => undefined} />);
    expect(screen.getByRole("button", { name: "Remove ghost" })).toBeInTheDocument();
  });

  test("a pick whose option arrives later switches from the raw value to the option label", async () => {
    function Late() {
      const [data, setData] = useState<{ value: string; label: string }[]>([]);
      return (
        <>
          <button onClick={() => setData(DATA)}>load</button>
          <FilterMultiSelect label="Things" data={data} value={["a"]} onChange={() => undefined} />
        </>
      );
    }
    const user = userEvent.setup();
    renderWithProviders(<Late />);
    expect(screen.getByRole("button", { name: "Remove a" })).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "load" }));
    expect(screen.getByRole("button", { name: "Remove Alpha" })).toBeInTheDocument();
    expect(within(document.body).queryByRole("button", { name: "Remove a" })).toBeNull();
  });

  test("a caller's own empty label replaces Any, and only while nothing is picked", () => {
    const { unmount } = renderWithProviders(<FilterMultiSelect label="Things" emptyLabel="Wszystkie" data={DATA} value={[]} onChange={() => undefined} />);
    expect(screen.getByRole("combobox", { name: "Things" })).toHaveAttribute("placeholder", "Wszystkie");
    unmount();
    renderWithProviders(<FilterMultiSelect label="Things" emptyLabel="Wszystkie" data={DATA} value={["a"]} onChange={() => undefined} />);
    expect(screen.getByRole("combobox", { name: "Things" })).not.toHaveAttribute("placeholder");
  });

  test("a disabled select has pills without remove buttons", () => {
    renderWithProviders(<FilterMultiSelect label="Things" data={DATA} value={["a"]} onChange={() => undefined} disabled />);
    expect(screen.queryByRole("button", { name: "Remove Alpha" })).toBeNull();
  });
});
