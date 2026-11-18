import { describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import RevisionConflictAlert from "./RevisionConflictAlert";
import { renderWithProviders, screen } from "../test/render";

describe("RevisionConflictAlert", () => {
  test("says the draft is kept and offers Reload", async () => {
    const onReload = vi.fn();
    renderWithProviders(<RevisionConflictAlert onReload={onReload} />);
    expect(screen.getByRole("alert")).toHaveTextContent("This workflow was changed elsewhere");
    expect(screen.getByText(/Your changes are kept here/)).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "Reload the latest version" }));
    expect(onReload).toHaveBeenCalledTimes(1);
  });

  test("a reload in flight disables the button", () => {
    renderWithProviders(<RevisionConflictAlert onReload={() => {}} reloading />);
    expect(screen.getByRole("button", { name: "Reload the latest version" })).toBeDisabled();
  });

  test("takes its own words (a thread's details editor)", () => {
    renderWithProviders(<RevisionConflictAlert onReload={() => {}} title="Elsewhere" message="Newer version saved." reloadLabel="Reload it" />);
    expect(screen.getByRole("alert")).toHaveTextContent("Elsewhere");
    expect(screen.getByText("Newer version saved.")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Reload it" })).toBeInTheDocument();
    expect(screen.queryByText("This workflow was changed elsewhere")).not.toBeInTheDocument();
  });
});
