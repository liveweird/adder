import { afterEach, describe, expect, test } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import { MantineProvider } from "@mantine/core";
import { MemoryRouter } from "react-router-dom";
import MarkdownView, { type MarkdownVariant } from "./MarkdownView";

const tagHref = (tag: string) => `/rooms/7/threads/42?scope=all&tag=${encodeURIComponent(tag)}`;
const threadHref = (number: number) => `/rooms/7/threads/${number}?scope=all`;

function renderView(markdown: string, variant: MarkdownVariant = "message", refs: ((number: number) => string) | null = threadHref) {
  return render(
    <MantineProvider env="test">
      <MemoryRouter>
        <MarkdownView variant={variant} tagHref={tagHref} threadHref={refs ?? undefined}>
          {markdown}
        </MarkdownView>
      </MemoryRouter>
    </MantineProvider>,
  );
}

afterEach(cleanup);

describe("MarkdownView (message): #42 thread references (1.2 A9)", () => {
  test("a number is an in-app router link to that thread, without target or opener, its text the number", () => {
    renderView("Duplicate of #42, see also #7.");
    const link = screen.getByRole("link", { name: "#42" });
    expect(link).toHaveAttribute("href", "/rooms/7/threads/42?scope=all");
    expect(link).not.toHaveAttribute("target");
    expect(link).not.toHaveAttribute("rel");
    expect(screen.getByRole("link", { name: "#7" })).toHaveAttribute("href", "/rooms/7/threads/7?scope=all");
    expect(link.closest("p")).toHaveTextContent("Duplicate of #42, see also #7.");
  });

  test("a hashtag and a number side by side are each their own link", () => {
    renderView("#outage is #42");
    expect(screen.getByRole("link", { name: "#outage" })).toHaveAttribute("href", "/rooms/7/threads/42?scope=all&tag=outage");
    expect(screen.getByRole("link", { name: "#42" })).toHaveAttribute("href", "/rooms/7/threads/42?scope=all");
    expect(screen.getAllByRole("link")).toHaveLength(2);
  });

  test("a number glued to a hashtag is no reference: #release#42 is the hashtag alone", () => {
    renderView("see #release#42 and #release #7");
    expect(screen.getAllByRole("link", { name: "#release" })).toHaveLength(2);
    expect(screen.queryByRole("link", { name: "#42" })).toBeNull();
    expect(screen.getByRole("link", { name: "#7" })).toBeInTheDocument();
  });

  test("numbers in code and in links stay text, external links ask for confirmation, and glued numbers are no references", () => {
    renderView("`#1` and [docs #2](https://example.com/#3) and https://example.com/x#4 and foo#5 and #6");
    expect(screen.getByText("#1").tagName).toBe("CODE");
    expect(screen.getByRole("button", { name: "docs #2" })).not.toHaveAttribute("href");
    expect(screen.getByRole("button", { name: "https://example.com/x#4" })).not.toHaveAttribute("href");
    expect(screen.getByRole("link", { name: "#6" })).not.toHaveAttribute("target");
    expect(screen.getAllByRole("link")).toHaveLength(1);
    expect(screen.queryByRole("link", { name: "#5" })).toBeNull();
  });

  test("a number is text inside its link: markup around it is never interpreted", () => {
    renderView("#4<img src=x onerror=alert(1)> and **#5**");
    expect(document.querySelector("img")).toBeNull();
    expect(screen.getByRole("link", { name: "#5" }).closest("strong")).not.toBeNull();
    expect(screen.getByRole("link", { name: "#4" }).children).toHaveLength(0);
  });

  test("without threadHref a number stays plain text, and the document variant never links one", () => {
    renderView("see #42", "message", null);
    expect(screen.getByText("see #42")).toBeInTheDocument();
    expect(screen.queryByRole("link")).toBeNull();
    cleanup();
    renderView("see #42", "document");
    expect(screen.getByText("see #42")).toBeInTheDocument();
    expect(screen.queryByRole("link")).toBeNull();
  });

  test("a re-render with a new threadHref closure keeps the very same link element, so focus on it survives", () => {
    const view = (href: (number: number) => string) => (
      <MantineProvider env="test">
        <MemoryRouter>
          <MarkdownView variant="message" threadHref={href}>
            {"see #42"}
          </MarkdownView>
        </MemoryRouter>
      </MantineProvider>
    );
    const { rerender } = render(view((number) => `/a/${number}`));
    const link = screen.getByRole("link", { name: "#42" });
    link.focus();
    rerender(view((number) => `/b/${number}`));
    expect(screen.getByRole("link", { name: "#42" })).toBe(link);
    expect(link).toHaveAttribute("href", "/b/42");
    expect(link).toHaveFocus();
  });
});
