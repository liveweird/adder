import { describe, expect, test } from "vitest";
import RoomTabs from "./RoomTabs";
import { renderWithProviders, screen } from "../test/render";

describe("RoomTabs", () => {
  test("links the room's pages and marks the open one with aria-current", () => {
    renderWithProviders(<RoomTabs id={12} active="settings" />);
    const nav = screen.getByRole("navigation", { name: "Room pages" });
    expect(nav).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Threads" })).toHaveAttribute("href", "/rooms/12");
    expect(screen.getByRole("link", { name: "Threads" })).not.toHaveAttribute("aria-current");
    expect(screen.getByRole("link", { name: "Search" })).toHaveAttribute("href", "/rooms/12/search");
    expect(screen.getByRole("link", { name: "Search" })).not.toHaveAttribute("aria-current");
    expect(screen.getByRole("link", { name: "Members" })).toHaveAttribute("href", "/rooms/12/members");
    expect(screen.getByRole("link", { name: "Members" })).not.toHaveAttribute("aria-current");
    expect(screen.getByRole("link", { name: "Workflow" })).toHaveAttribute("href", "/rooms/12/workflow");
    expect(screen.getByRole("link", { name: "Workflow" })).not.toHaveAttribute("aria-current");
    expect(screen.getByRole("link", { name: "Settings" })).toHaveAttribute("href", "/rooms/12/settings");
    expect(screen.getByRole("link", { name: "Settings" })).toHaveAttribute("aria-current", "page");
  });
});
