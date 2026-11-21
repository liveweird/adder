import { describe, expect, test } from "vitest";
import { renderWithProviders, screen } from "../test/render";
import TagPills from "./TagPills";

describe("TagPills", () => {
  test("shows a pill per tag and marks a retired one for assistive technology, not only by style", () => {
    renderWithProviders(<TagPills tags={[{ name: "data", retired: false }, { name: "legacy", retired: true }]} />);
    expect(screen.getByText("data")).toBeInTheDocument();
    expect(screen.getByText("legacy (retired)")).toBeInTheDocument();
  });

  test("no tags reads as the given words, or as nothing in a table cell", () => {
    const { unmount } = renderWithProviders(<TagPills tags={[]} none="No tags" />);
    expect(screen.getByText("No tags")).toBeInTheDocument();
    unmount();
    const bare = renderWithProviders(<TagPills tags={[]} />);
    expect(bare.container.querySelector(".mantine-Badge-root, .mantine-Text-root")).toBeNull();
  });
});
