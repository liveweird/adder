import { afterEach, describe, expect, test } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
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

  test("exposes the external href only inside the destination confirmation", async () => {
    renderView("[docs](https://example.com/docs)", "message");
    const user = userEvent.setup();
    const trigger = screen.getByRole("button", { name: "docs" });
    expect(trigger).not.toHaveAttribute("href");
    expect(screen.queryByRole("link")).toBeNull();
    await user.click(trigger);
    const dialog = await screen.findByRole("dialog", { name: "Open external link" });
    expect(dialog).toHaveTextContent("https://example.com/docs");
    const link = screen.getByRole("link", { name: "Open link" });
    expect(link).toHaveAttribute("href", "https://example.com/docs");
    expect(link).toHaveAttribute("target", "_blank");
    expect(link).toHaveAttribute("rel", "noopener noreferrer");
    await user.click(screen.getByRole("button", { name: "Cancel" }));
    expect(screen.queryByRole("link")).toBeNull();
  });

  test("offers confirmation for a message image using its alt text", () => {
    renderView("![a cat](https://example.com/cat.png)", "message");
    expect(document.querySelector("img")).toBeNull();
    expect(screen.getByRole("button", { name: "a cat" })).not.toHaveAttribute("href");
  });

  test("an image without alt text is labelled by its address", () => {
    renderView("![](https://example.com/cat.png)", "message");
    expect(screen.getByRole("button", { name: "https://example.com/cat.png" })).toBeInTheDocument();
  });

  test("links to the app directly and gives a linked image one interactive destination", () => {
    renderView("[room](/rooms/7) [![preview](https://example.com/cat.png)](https://example.com/gallery)", "message");
    expect(screen.getByRole("link", { name: "room" })).toHaveAttribute("href", "/rooms/7");
    const gallery = screen.getByRole("button", { name: "preview" });
    expect(gallery.querySelector("button, a, img")).toBeNull();
  });

  test("keeps an absolute same-origin destination as a direct app link", () => {
    renderView(`[room](${window.location.origin}/rooms/7?scope=all#latest)`, "message");
    expect(screen.getByRole("link", { name: "room" })).toHaveAttribute("href", "/rooms/7?scope=all#latest");
    expect(screen.queryByRole("button")).toBeNull();
  });

  test("uses an external destination as the label when Markdown supplies an empty label", () => {
    renderView("[](https://example.com/empty-label)", "message");
    expect(screen.getByRole("button", { name: "https://example.com/empty-label" })).toBeInTheDocument();
  });

  test("uses an external destination as the label when Markdown supplies only whitespace", () => {
    renderView("[ ](https://example.com/whitespace-label)", "message");
    expect(screen.getByRole("button", { name: "https://example.com/whitespace-label" })).toBeInTheDocument();
  });

  test("leaves a malformed Markdown destination non-interactive", () => {
    renderView("[broken](https://[)", "message");
    expect(screen.queryByRole("button")).toBeNull();
    expect(screen.queryByRole("link")).toBeNull();
    expect(document.body).toHaveTextContent("broken");
  });

  test("a linked image with empty alt text uses the actual parent destination as its label", () => {
    renderView("[![](https://example.com/cat.png)](https://example.com/gallery) [![](https://example.com/cat.png)](/rooms/7)", "message");
    expect(screen.getByRole("button", { name: "https://example.com/gallery" })).not.toHaveAttribute("href");
    expect(screen.getByRole("link", { name: "/rooms/7" })).toHaveAttribute("href", "/rooms/7");
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

  test("tags in code and in links stay text, and external destinations get confirmation controls", () => {
    renderView("`#a` and [docs #b](https://example.com/#c) and https://example.com/x#d and #real", "message", tagHref);
    expect(screen.getByRole("link", { name: "#real" })).not.toHaveAttribute("target");
    expect(screen.getByRole("button", { name: "docs #b" })).not.toHaveAttribute("href");
    expect(screen.getByRole("button", { name: "https://example.com/x#d" })).not.toHaveAttribute("href");
    expect(screen.getByText("#a").tagName).toBe("CODE");
    expect(screen.getAllByRole("link")).toHaveLength(1);
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
    const docs = screen.getByRole("button", { name: "docs" });
    link.focus();
    rerender(view((tag) => `/b?tag=${tag}`));
    // The new closure is used (the href follows it) on the SAME nodes: nothing was remounted.
    expect(screen.getByRole("link", { name: "#outage" })).toBe(link);
    expect(screen.getByRole("button", { name: "docs" })).toBe(docs);
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
    // The only links are the two profile links: the name's Markdown is text, so no link of its own comes out of it.
    expect(screen.getAllByRole("link").map((link) => link.getAttribute("href"))).toEqual(["/people/5", "/people/9"]);
    expect(screen.getByText("@**Ada** <img src=x onerror=alert(1)>")).toBeInTheDocument();
    expect(screen.getByText("@[Bob](https://evil.example) #tag")).toBeInTheDocument();
  });

  test("a mention in a link's text is shown inside the link, and code is left alone", () => {
    localStorage.setItem("adder.auth.userId", "5");
    renderView("[see <@5> and <@9>](https://example.com) `<@9>`", "message", undefined, people);
    const link = screen.getByRole("button", { name: "see @Ada Lovelace and @Bob" });
    // The mention is a span inside the link label, so there is only one interactive control.
    expect(link.querySelector("a")).toBeNull();
    expect(screen.queryByRole("link")).toBeNull();
    expect(screen.getByText("@Ada Lovelace")).toHaveAttribute("data-self", "true");
    expect(screen.getByText("@Ada Lovelace").tagName).toBe("SPAN");
    expect(screen.getByText("@Bob")).not.toHaveAttribute("data-self");
    expect(screen.getByText("<@9>").tagName).toBe("CODE");
  });

  test("a listed person's mention is a link to their profile, the reader's own too", () => {
    localStorage.setItem("adder.auth.userId", "5");
    renderView("<@5> and <@9>", "message", undefined, people);
    expect(screen.getByRole("link", { name: "@Ada Lovelace" })).toHaveAttribute("href", "/people/5");
    expect(screen.getByRole("link", { name: "@Ada Lovelace" })).toHaveAttribute("data-self", "true");
    expect(screen.getByRole("link", { name: "@Bob" })).toHaveAttribute("href", "/people/9");
  });

  test("an unlisted id is a link to nobody: the raw token stays text", () => {
    renderView("Ping <@77>", "message", undefined, people);
    expect(screen.queryAllByRole("link")).toHaveLength(0);
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
    expect(screen.getByRole("button", { name: "alt <@5>" })).toBeInTheDocument();
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

  test("a listed team is a link to its page, the keywords never are, and a team in a link's text is a span", () => {
    renderView("Hey <@team:12>, <@watchers> and <@everyone>", "message", undefined, undefined, groups);
    expect(screen.getByRole("link", { name: "@oncall" })).toHaveAttribute("href", "/teams/12");
    expect(screen.getAllByRole("link")).toHaveLength(1);
    cleanup();
    renderView("[page <@team:12>](https://example.com)", "message", undefined, undefined, groups);
    expect(screen.getByRole("button", { name: "page @oncall" })).toBeInTheDocument();
    expect(screen.queryByRole("link")).toBeNull();
    expect(screen.getByText("@oncall").tagName).toBe("SPAN");
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
    expect(screen.getByRole("button", { name: "see @everyone" })).toBeInTheDocument();
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
