import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import ReactMarkdown from "react-markdown";
import remarkGemoji from "remark-gemoji";
import remarkGfm from "remark-gfm";
import { describe, expect, test } from "vitest";
import cases from "../../../server/src/test/resources/mention-cases.json";
import { mentionSegments } from "./mentions";
import remarkMentions from "./remarkMentions";

/**
 * The names the SPA would show for `body` when the server listed `ids` (the real Markdown pipeline of a message: gfm, gemoji, then the
 * plugin; the renderer does what `MarkdownView` does, a name for a listed id and the raw token for any other), in order of appearance.
 */
function shownNames(body: string, ids: number[]): string[] {
  const listed = new Set(ids);
  const shown: string[] = [];
  renderToStaticMarkup(
    createElement(ReactMarkdown, {
      children: body,
      remarkPlugins: [remarkGfm, remarkGemoji, remarkMentions],
      components: {
        span: (props) => {
          const raw = (props as { "data-mention"?: string })["data-mention"];
          if (raw !== undefined && listed.has(Number(raw))) shown.push(`user-${raw}`);
          return null;
        },
      },
    }),
  );
  return shown;
}

describe("the shared mention case table (also read by the server's MentionsTest)", () => {
  test("the table is the one the server reads", () => {
    expect(cases.length).toBeGreaterThanOrEqual(30);
  });

  // The SPA follows the server: every id the server found is shown (the SPA must read at least what the server read) ...
  test.each(cases)("shows every mention the server found: $name", ({ body, ids }) => {
    expect([...new Set(shownNames(body, ids))]).toEqual(ids.map((id) => `user-${id}`));
  });

  // ... and a token the server did not list is never shown as a name, even where the SPA's tree reads it as a token (an escaped one,
  // one glued to a bare URL): nothing is shown for an id outside the list.
  test.each(cases)("shows nothing the server did not find: $name", ({ body }) => {
    expect(shownNames(body, [])).toEqual([]);
  });
});

describe("mentionSegments", () => {
  test("a text with no token, and a token that is no mention, are left alone", () => {
    expect(mentionSegments("nothing here")).toBeNull();
    expect(mentionSegments("<@0> <@042> <@abc> <@5 > <@2147483648> <@12345678901>")).toBeNull();
  });

  test("cuts the text around each token and keeps the token as written", () => {
    expect(mentionSegments("hi <@42>, and <@7>")).toEqual([
      { text: "hi " },
      { text: "<@42>", id: 42 },
      { text: ", and " },
      { text: "<@7>", id: 7 },
    ]);
    expect(mentionSegments("<@1><@2>")).toEqual([
      { text: "<@1>", id: 1 },
      { text: "<@2>", id: 2 },
    ]);
  });

  test("the largest id is a mention, one past it is not, and a refused token between two real ones stays text", () => {
    expect(mentionSegments("<@2147483647>")).toEqual([{ text: "<@2147483647>", id: 2147483647 }]);
    expect(mentionSegments("<@2147483648>")).toBeNull();
    expect(mentionSegments("<@4> <@3000000000> <@5>")).toEqual([
      { text: "<@4>", id: 4 },
      { text: " <@3000000000> " },
      { text: "<@5>", id: 5 },
    ]);
  });
});
