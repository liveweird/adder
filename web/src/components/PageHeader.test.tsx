import { describe, expect, test } from "vitest";
import PageHeader from "./PageHeader";
import { renderWithProviders, screen } from "../test/render";

describe("PageHeader", () => {
  test("renders the title as the page's level-2 heading with the optional parts", () => {
    renderWithProviders(
      <PageHeader
        title="Labels"
        description="The only labels files may carry."
        backTo={{ to: "/files", label: "Back to files" }}
        actions={<button type="button">New label</button>}
        toolbar={<div data-testid="toolbar" />}
      />,
    );
    expect(screen.getByRole("heading", { level: 2, name: "Labels" })).toBeInTheDocument();
    expect(screen.getByText("The only labels files may carry.")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Back to files" })).toHaveAttribute("href", "/files");
    expect(screen.getByRole("button", { name: "New label" })).toBeInTheDocument();
    expect(screen.getByTestId("toolbar")).toBeInTheDocument();
  });

  test("the title alone renders nothing else", () => {
    renderWithProviders(<PageHeader title="Users" />);
    expect(screen.getByRole("heading", { level: 2, name: "Users" })).toBeInTheDocument();
    expect(screen.queryByRole("link")).not.toBeInTheDocument();
  });

  test("titles the browser tab from the heading, or from an explicit documentTitle", () => {
    const { rerender } = renderWithProviders(<PageHeader title="Users" />);
    expect(document.title).toBe("Users · Adder");
    rerender(<PageHeader title="Night Shift" documentTitle="Members · Night Shift" />);
    expect(document.title).toBe("Members · Night Shift · Adder");
  });

  test("compact: one row with an icon-only back link that keeps its name, the title still the h2, the description, the toolbar and the actions", () => {
    renderWithProviders(
      <PageHeader
        compact
        title="Night Shift"
        description={<span>3 members</span>}
        backTo={{ to: "/rooms", label: "Back to rooms" }}
        actions={<button type="button">Pinned</button>}
        toolbar={<div data-testid="toolbar" />}
      />,
    );
    expect(screen.getByRole("heading", { level: 2, name: "Night Shift" })).toBeInTheDocument();
    const back = screen.getByRole("link", { name: "Back to rooms" });
    expect(back).toHaveAttribute("href", "/rooms");
    expect(back).toHaveTextContent("");
    expect(screen.getByText("3 members")).toBeInTheDocument();
    expect(screen.getByTestId("toolbar")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Pinned" })).toBeInTheDocument();
    expect(document.title).toBe("Night Shift · Adder");
  });

  test("compact with the title alone renders no link and no description", () => {
    renderWithProviders(<PageHeader compact title="Users" />);
    expect(screen.getByRole("heading", { level: 2, name: "Users" })).toBeInTheDocument();
    expect(screen.queryByRole("link")).not.toBeInTheDocument();
  });
});
