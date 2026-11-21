import { describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { renderWithProviders, screen } from "../test/render";
import TagPills from "./TagPills";

describe("TagPills", () => {
  test("shows a pill per tag and marks a retired one for assistive technology, not only by style", () => {
    renderWithProviders(<TagPills tags={[{ name: "data", retired: false }, { name: "legacy", retired: true }]} />);
    expect(screen.getByText("data")).toBeInTheDocument();
    expect(screen.getByText("legacy (retired)")).toBeInTheDocument();
  });

  test("with a limit the first pills show and the rest are one \"+N\" that says how many in words; with onShowAll it is a button", async () => {
    const tags = ["a", "b", "c", "d", "e"].map((name) => ({ name, retired: false }));
    const { unmount } = renderWithProviders(<TagPills tags={tags} limit={3} />);
    expect(screen.getByText("c")).toBeInTheDocument();
    expect(screen.queryByText("d")).toBeNull();
    expect(screen.getByText("+2")).toBeInTheDocument();
    expect(screen.getByText("2 more tags")).toBeInTheDocument();
    expect(screen.queryByRole("button")).toBeNull();
    unmount();
    const onShowAll = vi.fn();
    renderWithProviders(<TagPills tags={tags} limit={4} onShowAll={onShowAll} />);
    await userEvent.click(screen.getByRole("button", { name: "+1: show 1 more tag in the details" }));
    expect(onShowAll).toHaveBeenCalledTimes(1);
  });

  test("a limit that holds every tag shows no \"+N\"", () => {
    renderWithProviders(<TagPills tags={[{ name: "a", retired: false }]} limit={3} />);
    expect(screen.queryByText(/^\+/)).toBeNull();
  });

  test("no tags reads as the given words, or as nothing in a table cell", () => {
    const { unmount } = renderWithProviders(<TagPills tags={[]} none="No tags" />);
    expect(screen.getByText("No tags")).toBeInTheDocument();
    unmount();
    const bare = renderWithProviders(<TagPills tags={[]} />);
    expect(bare.container.querySelector(".mantine-Badge-root, .mantine-Text-root")).toBeNull();
  });
});
