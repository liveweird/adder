import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { useState } from "react";
import userEvent from "@testing-library/user-event";
import { screen } from "@testing-library/react";
import ThreadFieldInput from "./ThreadFieldInput";
import { emptyFieldDraft, type FieldDraft, type WorkflowField } from "../utils/threadForm";
import { jsonResponse } from "../test/http";
import { renderWithProviders } from "../test/render";

const OPTIONS = [
  { key: "s1", label: "S1" },
  { key: "s2", label: "S2" },
];

function field(type: WorkflowField["type"], required = false): WorkflowField {
  return { key: "f", label: "Thing", type, required, options: type === "SELECT" || type === "MULTI_SELECT" ? OPTIONS : [] };
}

function Harness({ field: definition, initial, onDraft, error }: { field: WorkflowField; initial?: FieldDraft; onDraft: (draft: FieldDraft) => void; error?: string }) {
  const [draft, setDraft] = useState<FieldDraft>(initial ?? emptyFieldDraft(definition));
  return (
    <ThreadFieldInput
      roomId={9}
      field={definition}
      draft={draft}
      error={error}
      onChange={(next) => {
        setDraft(next);
        onDraft(next);
      }}
    />
  );
}

describe("ThreadFieldInput", () => {
  beforeEach(() => {
    vi.stubGlobal(
      "fetch",
      vi.fn(() =>
        Promise.resolve(jsonResponse(200, { items: [{ userId: 12, name: "Ann", email: "ann@example.test", role: "MEMBER", addedAt: 1 }], page: 1, pageSize: 50, total: 1 })),
      ),
    );
    localStorage.setItem("adder.auth.token", "fake-token");
  });
  afterEach(() => {
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  test("TEXT is a text box that reports each edit as a whole draft, capped at 500 characters", async () => {
    const onDraft = vi.fn();
    const user = userEvent.setup();
    renderWithProviders(<Harness field={field("TEXT")} onDraft={onDraft} />);
    const box = screen.getByRole("textbox", { name: "Thing" });
    expect(box).toHaveAttribute("maxlength", "500");
    await user.type(box, "db");
    expect(onDraft).toHaveBeenLastCalledWith({ kind: "text", value: "db" });
  });

  test("a required field carries the asterisk, and a form error shows under the input", () => {
    renderWithProviders(<Harness field={field("TEXT", true)} onDraft={vi.fn()} error="This field is required" />);
    expect(screen.getByRole("textbox", { name: /Thing/ })).toBeRequired();
    expect(screen.getByText("This field is required")).toBeInTheDocument();
  });

  test("NUMBER keeps what is typed as a string, including a half-typed value", async () => {
    const onDraft = vi.fn();
    const user = userEvent.setup();
    renderWithProviders(<Harness field={field("NUMBER")} onDraft={onDraft} />);
    await user.type(screen.getByRole("textbox", { name: "Thing" }), "-7.5");
    expect(onDraft).toHaveBeenLastCalledWith({ kind: "text", value: "-7.5" });
    await user.clear(screen.getByRole("textbox", { name: "Thing" }));
    expect(onDraft).toHaveBeenLastCalledWith({ kind: "text", value: "" });
  });

  test("DATE is a native date box in the wire format", async () => {
    const onDraft = vi.fn();
    const user = userEvent.setup();
    renderWithProviders(<Harness field={field("DATE")} onDraft={onDraft} />);
    const box = screen.getByLabelText("Thing");
    expect(box).toHaveAttribute("type", "date");
    await user.type(box, "2026-03-01");
    expect(onDraft).toHaveBeenLastCalledWith({ kind: "text", value: "2026-03-01" });
  });

  test("URL is a url box with the https hint", async () => {
    const onDraft = vi.fn();
    const user = userEvent.setup();
    renderWithProviders(<Harness field={field("URL")} onDraft={onDraft} />);
    const box = screen.getByLabelText("Thing");
    expect(box).toHaveAttribute("type", "url");
    expect(box).toHaveAttribute("placeholder", "https://");
    await user.type(box, "https://x.test");
    expect(onDraft).toHaveBeenLastCalledWith({ kind: "text", value: "https://x.test" });
  });

  test("SELECT picks an option key; an optional one can be cleared, a required one cannot", async () => {
    const onDraft = vi.fn();
    const user = userEvent.setup();
    const { unmount } = renderWithProviders(<Harness field={field("SELECT")} onDraft={onDraft} />);
    await user.click(screen.getByRole("combobox", { name: "Thing" }));
    await user.click(await screen.findByRole("option", { name: "S2" }));
    expect(onDraft).toHaveBeenLastCalledWith({ kind: "text", value: "s2" });
    await user.click(screen.getByLabelText("Clear Thing"));
    expect(onDraft).toHaveBeenLastCalledWith({ kind: "text", value: "" });
    unmount();
    renderWithProviders(<Harness field={field("SELECT", true)} initial={{ kind: "text", value: "s1" }} onDraft={onDraft} />);
    expect(screen.queryByLabelText("Clear Thing")).not.toBeInTheDocument();
  });

  test("MULTI_SELECT picks several option keys", async () => {
    const onDraft = vi.fn();
    const user = userEvent.setup();
    renderWithProviders(<Harness field={field("MULTI_SELECT")} onDraft={onDraft} />);
    await user.click(screen.getByRole("combobox", { name: "Thing" }));
    await user.click(await screen.findByRole("option", { name: "S1" }));
    await user.click(await screen.findByRole("option", { name: "S2" }));
    expect(onDraft).toHaveBeenLastCalledWith({ kind: "multi", value: ["s1", "s2"] });
  });

  test("USER searches the room's members and reports the pick with its label", async () => {
    const onDraft = vi.fn();
    const user = userEvent.setup();
    renderWithProviders(<Harness field={field("USER")} onDraft={onDraft} />);
    await user.click(screen.getByRole("combobox", { name: "Thing" }));
    await user.click(await screen.findByRole("option", { name: "Ann (ann@example.test)" }));
    expect(onDraft).toHaveBeenLastCalledWith({ kind: "user", value: { value: "12", label: "Ann (ann@example.test)" } });
  });
});
