import { afterEach, describe, expect, test } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import { MantineProvider } from "@mantine/core";
import { MemoryRouter } from "react-router-dom";
import MarkdownView, { type MarkdownVariant } from "./MarkdownView";
import classes from "./MarkdownView.module.css";

const tagHref = (tag: string) => `/rooms/7/threads/42?scope=all&tag=${encodeURIComponent(tag)}`;

function renderView(markdown: string, variant?: MarkdownVariant, tags?: (tag: string) => string) {
  return render(
    <MantineProvider env="test">
      <MemoryRouter>
        <MarkdownView variant={variant} tagHref={tags}>
          {markdown}
        </MarkdownView>
      </MemoryRouter>
    </MantineProvider>,
  );
}

afterEach(cleanup);

describe("MarkdownView (document, the default)", () => {
  test("renders :shortcode: as the native emoji", () => {
    renderView("Great job :tada: keep it up :+1:");
    expect(screen.getByText("Great job 🎉 keep it up 👍")).toBeInTheDocument();
  });

  test("leaves unknown shortcodes literal", () => {
    renderView("stays :notarealshortcode: literal");
    expect(screen.getByText("stays :notarealshortcode: literal")).toBeInTheDocument();
  });

  test("GFM still renders alongside the emoji plugin", () => {
    renderView("| a | b |\n| - | - |\n| 1 | :tada: |");
    expect(screen.getByRole("table")).toBeInTheDocument();
    expect(screen.getByText("🎉")).toBeInTheDocument();
  });

  test("keeps real headings and plain links, and carries no message class", () => {
    renderView("# Title\n\n[docs](https://example.com/docs)");
    expect(screen.getByRole("heading", { level: 1, name: "Title" })).toBeInTheDocument();
    const link = screen.getByRole("link", { name: "docs" });
    expect(link).not.toHaveAttribute("target");
    expect(link.closest(`.${classes.message}`)).toBeNull();
  });

  test("leaves hashtags as plain text, even when asked for tag links (the changelog is a document)", () => {
    renderView("Fixed #outage handling", "document", tagHref);
    expect(screen.getByText("Fixed #outage handling")).toBeInTheDocument();
    expect(screen.queryByRole("link")).toBeNull();
  });

  test("renders no raw HTML", () => {
    renderView("<b>bold</b> and <script>alert(1)</script>");
    expect(document.querySelector("b")).toBeNull();
    expect(document.querySelector("script")).toBeNull();
  });
});

describe("MarkdownView (message)", () => {
  test("renders headings as bold paragraphs, never as heading elements", () => {
    renderView("# One\n\n### Three\n\n###### Six", "message");
    expect(screen.queryAllByRole("heading")).toHaveLength(0);
    for (const text of ["One", "Three", "Six"]) {
      const node = screen.getByText(text);
      expect(node.tagName).toBe("P");
      expect(node).toHaveClass(classes.heading);
    }
  });

  test("opens links in a new tab without opener or referrer", () => {
    renderView("[docs](https://example.com/docs)", "message");
    const link = screen.getByRole("link", { name: "docs" });
    expect(link).toHaveAttribute("href", "https://example.com/docs");
    expect(link).toHaveAttribute("target", "_blank");
    expect(link).toHaveAttribute("rel", "noopener noreferrer");
  });

  test("renders an image as a link labelled by its alt text (the CSP blocks remote images)", () => {
    renderView("![a cat](https://example.com/cat.png)", "message");
    expect(document.querySelector("img")).toBeNull();
    const link = screen.getByRole("link", { name: "a cat" });
    expect(link).toHaveAttribute("href", "https://example.com/cat.png");
    expect(link).toHaveAttribute("target", "_blank");
    expect(link).toHaveAttribute("rel", "noopener noreferrer");
  });

  test("an image without alt text is labelled by its address", () => {
    renderView("![](https://example.com/cat.png)", "message");
    expect(screen.getByRole("link", { name: "https://example.com/cat.png" })).toBeInTheDocument();
  });

  test("an image with an unsafe address stays as its alt text, not a link", () => {
    renderView("![nope](javascript:alert(1))", "message");
    expect(document.querySelector("img")).toBeNull();
    expect(screen.queryByRole("link")).toBeNull();
    expect(screen.getByText("nope")).toBeInTheDocument();
  });

  test("renders no raw HTML", () => {
    renderView("<img src=x onerror=alert(1)> <b>bold</b> <script>alert(1)</script>", "message");
    expect(document.querySelector("img")).toBeNull();
    expect(document.querySelector("b")).toBeNull();
    expect(document.querySelector("script")).toBeNull();
  });

  test("emoji shortcodes and GFM still render, inside the message class", () => {
    renderView("done :tada:\n\n| a |\n| - |\n| 1 |", "message");
    expect(screen.getByText("done 🎉")).toBeInTheDocument();
    expect(screen.getByRole("table")).toBeInTheDocument();
    expect(screen.getByText("done 🎉").parentElement).toHaveClass(classes.message);
  });

  test("a hashtag is plain text until the message is given a tag link", () => {
    renderView("see #outage", "message");
    expect(screen.getByText("see #outage")).toBeInTheDocument();
    expect(screen.queryByRole("link")).toBeNull();
  });

  test("a hashtag is an in-app router link without target or opener, its text the tag as typed", () => {
    renderView("Rolled back, see #Outage-3 for details.", "message", tagHref);
    const link = screen.getByRole("link", { name: "#Outage-3" });
    expect(link).toHaveAttribute("href", "/rooms/7/threads/42?scope=all&tag=Outage-3");
    expect(link).not.toHaveAttribute("target");
    expect(link).not.toHaveAttribute("rel");
    expect(link.closest("p")).toHaveTextContent("Rolled back, see #Outage-3 for details.");
  });

  test("the tag value is URL-encoded in the link", () => {
    renderView("#Wdro\u017cenie and #a_b", "message", tagHref);
    expect(screen.getByRole("link", { name: "#Wdro\u017cenie" })).toHaveAttribute("href", "/rooms/7/threads/42?scope=all&tag=Wdro%C5%BCenie");
    expect(screen.getByRole("link", { name: "#a_b" })).toHaveAttribute("href", "/rooms/7/threads/42?scope=all&tag=a_b");
  });

  test("tags in code and in links stay text, and an external link still opens a new tab", () => {
    renderView("`#a` and [docs #b](https://example.com/#c) and https://example.com/x#d and #real", "message", tagHref);
    expect(screen.getByRole("link", { name: "#real" })).not.toHaveAttribute("target");
    const external = screen.getByRole("link", { name: "docs #b" });
    expect(external).toHaveAttribute("href", "https://example.com/#c");
    expect(external).toHaveAttribute("target", "_blank");
    expect(external).toHaveAttribute("rel", "noopener noreferrer");
    expect(screen.getByRole("link", { name: "https://example.com/x#d" })).toHaveAttribute("target", "_blank");
    expect(screen.getByText("#a").tagName).toBe("CODE");
    expect(screen.getAllByRole("link")).toHaveLength(3);
  });

  test("a tag is text inside its link: markup around it is never interpreted", () => {
    renderView("#a<img src=x onerror=alert(1)> and **#b**", "message", tagHref);
    expect(document.querySelector("img")).toBeNull();
    expect(screen.getByRole("link", { name: "#b" }).closest("strong")).not.toBeNull();
    expect(screen.getByRole("link", { name: "#a" }).children).toHaveLength(0);
  });

  test("a re-render with a new tagHref closure keeps the very same link element, so focus on it survives", async () => {
    const view = (hrefs: (tag: string) => string) => (
      <MantineProvider env="test">
        <MemoryRouter>
          <MarkdownView variant="message" tagHref={hrefs}>
            {"see #outage and [docs](https://example.com)"}
          </MarkdownView>
        </MemoryRouter>
      </MantineProvider>
    );
    const { rerender } = render(view((tag) => `/a?tag=${tag}`));
    const link = screen.getByRole("link", { name: "#outage" });
    const docs = screen.getByRole("link", { name: "docs" });
    link.focus();
    rerender(view((tag) => `/b?tag=${tag}`));
    // The new closure is used (the href follows it) on the SAME nodes: nothing was remounted.
    expect(screen.getByRole("link", { name: "#outage" })).toBe(link);
    expect(screen.getByRole("link", { name: "docs" })).toBe(docs);
    expect(link).toHaveAttribute("href", "/b?tag=outage");
    expect(link).toHaveFocus();
  });
});
