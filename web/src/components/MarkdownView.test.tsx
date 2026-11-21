import { afterEach, describe, expect, test } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import { MantineProvider } from "@mantine/core";
import { MemoryRouter } from "react-router-dom";
import { createKnownNames, KnownNamesContext } from "../utils/knownNames";
import MarkdownView, { type MarkdownVariant } from "./MarkdownView";
import classes from "./MarkdownView.module.css";

const tagHref = (tag: string) => `/rooms/7/threads/42?scope=all&tag=${encodeURIComponent(tag)}`;

type Mentioned = { userId: number; name: string }[];

type Grouped = { kind: "TEAM" | "WATCHERS" | "EVERYONE"; teamId: number | null; teamHandle: string | null }[];

function renderView(markdown: string, variant?: MarkdownVariant, tags?: (tag: string) => string, mentions?: Mentioned, groups?: Grouped) {
  return render(
    <MantineProvider env="test">
      <MemoryRouter>
        <MarkdownView variant={variant} tagHref={tags} mentions={mentions} groupMentions={groups}>
          {markdown}
        </MarkdownView>
      </MemoryRouter>
    </MantineProvider>,
  );
}

afterEach(() => {
  cleanup();
  localStorage.clear();
});

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

describe("MarkdownView (message): mentions", () => {
  const people: Mentioned = [
    { userId: 5, name: "Ada Lovelace" },
    { userId: 9, name: "Bob" },
  ];

  test("a listed id reads @Name and an unlisted id stays the raw token", () => {
    renderView("Ping <@5> and <@9>, not <@77>.", "message", undefined, people);
    expect(screen.getByText("@Ada Lovelace")).toHaveAttribute("data-mention", "5");
    expect(screen.getByText("@Bob")).toBeInTheDocument();
    expect(screen.getByText(/not <@77>\./)).toBeInTheDocument();
    expect(screen.queryByText(/<@5>/)).toBeNull();
  });

  test("without a list (or with an empty one) every token stays the raw token", () => {
    const { unmount } = renderView("Ping <@5>", "message");
    expect(screen.getByText("Ping <@5>")).toBeInTheDocument();
    unmount();
    renderView("Ping <@5>", "message", undefined, []);
    expect(screen.getByText("Ping <@5>")).toBeInTheDocument();
  });

  test("the reader's own mention is marked, someone else's is not", () => {
    localStorage.setItem("adder.auth.userId", "5");
    renderView("<@5> and <@9>", "message", undefined, people);
    expect(screen.getByText("@Ada Lovelace")).toHaveAttribute("data-self", "true");
    expect(screen.getByText("@Ada Lovelace")).toHaveClass(classes.mentionSelf);
    expect(screen.getByText("@Bob")).not.toHaveAttribute("data-self");
    expect(screen.getByText("@Bob")).not.toHaveClass(classes.mentionSelf);
  });

  test("a name with Markdown or markup in it is text, never interpreted", () => {
    renderView("<@5> and <@9>", "message", undefined, [
      { userId: 5, name: "**Ada** <img src=x onerror=alert(1)>" },
      { userId: 9, name: "[Bob](https://evil.example) #tag" },
    ]);
    expect(document.querySelector("img")).toBeNull();
    expect(document.querySelector("strong")).toBeNull();
    expect(screen.queryAllByRole("link")).toHaveLength(0);
    expect(screen.getByText("@**Ada** <img src=x onerror=alert(1)>")).toBeInTheDocument();
    expect(screen.getByText("@[Bob](https://evil.example) #tag")).toBeInTheDocument();
  });

  test("a mention in a link's text is shown inside the link, and code is left alone", () => {
    renderView("[see <@5>](https://example.com) `<@9>`", "message", undefined, people);
    expect(screen.getByRole("link", { name: "see @Ada Lovelace" })).toHaveAttribute("href", "https://example.com");
    expect(screen.getByText("<@9>").tagName).toBe("CODE");
  });

  // Known disagreement (web-internals.md "Mentions in messages"): the server's pre-pass does not blank an indented code block, a raw HTML
  // block or an image's alt text, so it lists the id even here; the SPA skips those nodes. What the reader sees is the raw token, no name.
  test("a listed id in indented code, a raw HTML block or image alt text stays raw, no name", () => {
    const { unmount } = renderView("para\n\n    <@5> indented\n\nafter", "message", undefined, people);
    expect(screen.getByText(/<@5> indented/).closest("pre")).not.toBeNull();
    expect(document.querySelector("[data-mention]")).toBeNull();
    expect(screen.queryByText("@Ada Lovelace")).toBeNull();
    unmount();

    renderView("<div><@5> in html</div>\n\nafter", "message", undefined, people);
    expect(document.querySelector("[data-mention]")).toBeNull();
    expect(screen.queryByText("@Ada Lovelace")).toBeNull();
    expect(document.body.textContent).not.toContain("Ada Lovelace");
    cleanup();

    renderView("![alt <@5>](https://example.com/a.png)", "message", undefined, people);
    expect(document.querySelector("[data-mention]")).toBeNull();
    expect(document.body.textContent).not.toContain("Ada Lovelace");
    expect(screen.getByRole("link", { name: "alt <@5>" })).toHaveAttribute("href", "https://example.com/a.png"); // an image is its alt, as a link
  });

  test("a mention beside a hashtag link: both are read", () => {
    renderView("#outage <@9>", "message", tagHref, people);
    expect(screen.getByRole("link", { name: "#outage" })).toHaveAttribute("href", tagHref("outage"));
    expect(screen.getByText("@Bob")).toBeInTheDocument();
  });

  test("the names a message lists are remembered for the session, the document variant's are not", () => {
    const known = createKnownNames();
    const view = (variant: MarkdownVariant) => (
      <KnownNamesContext.Provider value={known}>
        <MantineProvider env="test">
          <MemoryRouter>
            <MarkdownView variant={variant} mentions={people}>
              {"<@5>"}
            </MarkdownView>
          </MemoryRouter>
        </MantineProvider>
      </KnownNamesContext.Provider>
    );
    const { rerender } = render(view("document"));
    expect(known.get(5)).toBeUndefined();
    rerender(view("message"));
    expect(known.get(5)).toBe("Ada Lovelace");
    expect(known.get(9)).toBe("Bob");
  });

  test("the document variant is untouched: a token stays text, whatever the list says", () => {
    renderView("Ping <@5>", "document", undefined, people);
    expect(screen.getByText("Ping <@5>")).toBeInTheDocument();
    expect(document.querySelector("[data-mention]")).toBeNull();
  });
});

describe("MarkdownView (message): group mentions", () => {
  const groups: Grouped = [
    { kind: "TEAM", teamId: 12, teamHandle: "oncall" },
    { kind: "WATCHERS", teamId: null, teamHandle: null },
    { kind: "EVERYONE", teamId: null, teamHandle: null },
  ];

  test("a listed team reads @handle, a listed keyword reads itself, anything unlisted stays the raw token", () => {
    renderView("Hey <@team:12>, <@watchers> and <@everyone>; not <@team:99>.", "message", undefined, undefined, groups);
    expect(screen.getByText("@oncall")).toHaveAttribute("data-mention-team", "12");
    expect(screen.getByText("@watchers")).toHaveAttribute("data-mention-group", "watchers");
    expect(screen.getByText("@everyone")).toHaveAttribute("data-mention-group", "everyone");
    expect(screen.getByText(/not <@team:99>\./)).toBeInTheDocument();
    expect(screen.queryByText(/<@team:12>/)).toBeNull();
  });

  test("a keyword the server did not list stays the raw token, the other one is read", () => {
    renderView("<@watchers> <@everyone>", "message", undefined, undefined, [{ kind: "EVERYONE", teamId: null, teamHandle: null }]);
    expect(screen.getByText("@everyone")).toBeInTheDocument();
    expect(screen.getByText(/<@watchers>/)).toBeInTheDocument();
  });

  test("without a list every group token stays raw, people and groups read side by side, and the handle is text", () => {
    const { unmount } = renderView("<@team:12> <@everyone>", "message");
    expect(screen.getByText("<@team:12> <@everyone>")).toBeInTheDocument();
    unmount();
    renderView("<@5> <@team:12>", "message", undefined, [{ userId: 5, name: "Ada" }], [{ kind: "TEAM", teamId: 12, teamHandle: "<b>x</b>" }]);
    expect(screen.getByText("@Ada")).toBeInTheDocument();
    expect(screen.getByText("@<b>x</b>")).toBeInTheDocument();
    expect(document.querySelector("b")).toBeNull();
  });

  test("code is left alone, a link's text is read, and the document variant never reads a token", () => {
    const { unmount } = renderView("[see <@everyone>](https://example.com) `<@team:12>`", "message", undefined, undefined, groups);
    expect(screen.getByRole("link", { name: "see @everyone" })).toBeInTheDocument();
    expect(screen.getByText("<@team:12>").tagName).toBe("CODE");
    unmount();
    renderView("Ping <@everyone>", "document", undefined, undefined, groups);
    expect(screen.getByText("Ping <@everyone>")).toBeInTheDocument();
  });

  // Known disagreement (web-internals.md "Group mentions"): the server's pre-pass does not blank an indented code block or a raw HTML block,
  // so it lists the token even there; the SPA skips those nodes. What the reader sees is the raw token, no `@handle`.
  test("a listed group token in indented code or a raw HTML block stays raw", () => {
    const { unmount } = renderView("para\n\n    <@everyone> <@team:12> indented\n\nafter", "message", undefined, undefined, groups);
    expect(screen.getByText(/<@everyone> <@team:12> indented/).closest("pre")).not.toBeNull();
    expect(document.querySelector("[data-mention-group]")).toBeNull();
    unmount();
    renderView("<div><@everyone> in html</div>\n\nafter", "message", undefined, undefined, groups);
    expect(document.querySelector("[data-mention-group]")).toBeNull();
    expect(screen.queryByText("@everyone")).toBeNull();
  });

  test("the handles a message lists are remembered for the session", () => {
    const known = createKnownNames();
    render(
      <KnownNamesContext.Provider value={known}>
        <MantineProvider env="test">
          <MemoryRouter>
            <MarkdownView variant="message" groupMentions={groups}>
              {"<@team:12>"}
            </MarkdownView>
          </MemoryRouter>
        </MantineProvider>
      </KnownNamesContext.Provider>,
    );
    expect(known.getTeam(12)).toBe("oncall");
  });
});
