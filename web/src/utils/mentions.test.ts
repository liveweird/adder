import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import ReactMarkdown from "react-markdown";
import remarkGemoji from "remark-gemoji";
import remarkGfm from "remark-gfm";
import { describe, expect, test } from "vitest";
import cases from "../../../server/src/test/resources/mention-cases.json";
import { mentionSegments, mentionsEveryone } from "./mentions";
import remarkMentions from "./remarkMentions";

/** What the server listed for a body: the people, the teams and the two keywords. */
type Listed = { ids: number[]; teams?: number[]; everyone?: boolean; watchers?: boolean; here?: boolean };

/**
 * What the SPA would show for `body` when the server listed `listed` (the real Markdown pipeline of a message: gfm, gemoji, then the
 * plugin; the renderer does what `MarkdownView` does, a name for a listed person or team, a keyword when listed, the raw token for any
 * other), in order of appearance.
 */
function shownNames(body: string, listed: Listed): string[] {
  const people = new Set(listed.ids);
  const teams = new Set(listed.teams ?? []);
  const keywords = new Set([
    ...(listed.everyone === true ? ["everyone"] : []),
    ...(listed.watchers === true ? ["watchers"] : []),
    ...(listed.here === true ? ["here"] : []),
  ]);
  const shown: string[] = [];
  renderToStaticMarkup(
    createElement(ReactMarkdown, {
      children: body,
      remarkPlugins: [remarkGfm, remarkGemoji, remarkMentions],
      components: {
        span: (props) => {
          const marks = props as { "data-mention"?: string; "data-mention-team"?: string; "data-mention-group"?: string };
          if (marks["data-mention"] !== undefined && people.has(Number(marks["data-mention"]))) shown.push(`user-${marks["data-mention"]}`);
          if (marks["data-mention-team"] !== undefined && teams.has(Number(marks["data-mention-team"]))) shown.push(`team-${marks["data-mention-team"]}`);
          if (marks["data-mention-group"] !== undefined && keywords.has(marks["data-mention-group"])) shown.push(marks["data-mention-group"]);
          return null;
        },
      },
    }),
  );
  return shown;
}

/** The list the server's verdict makes for a case: people, then teams, then the keywords it found. */
const expectedShown = ({ ids, teams, everyone, watchers, here }: Listed) => [
  ...ids.map((id) => `user-${id}`),
  ...(teams ?? []).map((id) => `team-${id}`),
  ...(everyone === true ? ["everyone"] : []),
  ...(watchers === true ? ["watchers"] : []),
  ...(here === true ? ["here"] : []),
];

describe("the shared mention case table (also read by the server's MentionsTest)", () => {
  test("the table is the one the server reads", () => {
    expect(cases.length).toBeGreaterThanOrEqual(60);
  });

  // The SPA follows the server: every id the server found is shown (the SPA must read at least what the server read) ...
  test.each(cases)("shows every mention the server found: $name", (found) => {
    // Grouped by kind (people, teams, keywords) and in order of appearance within a kind: the same set as the server's verdict.
    const shown = [...new Set(shownNames(found.body, found))];
    expect(shown.filter((name) => name.startsWith("user-"))).toEqual(expectedShown(found).filter((name) => name.startsWith("user-")));
    expect(shown.filter((name) => name.startsWith("team-"))).toEqual(expectedShown(found).filter((name) => name.startsWith("team-")));
    expect(shown.filter((name) => !name.includes("-")).sort()).toEqual(expectedShown(found).filter((name) => !name.includes("-")).sort());
  });

  // ... and a token the server did not list is never shown as a name, even where the SPA's tree reads it as a token (an escaped one,
  // one glued to a bare URL): nothing is shown for an id outside the list.
  test.each(cases)("shows nothing the server did not find: $name", ({ body }) => {
    expect(shownNames(body, { ids: [] })).toEqual([]);
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
      { text: "<@42>", kind: "user", id: 42 },
      { text: ", and " },
      { text: "<@7>", kind: "user", id: 7 },
    ]);
    expect(mentionSegments("<@1><@2>")).toEqual([
      { text: "<@1>", kind: "user", id: 1 },
      { text: "<@2>", kind: "user", id: 2 },
    ]);
  });

  test("the largest id is a mention, one past it is not, and a refused token between two real ones stays text", () => {
    expect(mentionSegments("<@2147483647>")).toEqual([{ text: "<@2147483647>", kind: "user", id: 2147483647 }]);
    expect(mentionSegments("<@2147483648>")).toBeNull();
    expect(mentionSegments("<@4> <@3000000000> <@5>")).toEqual([
      { text: "<@4>", kind: "user", id: 4 },
      { text: " <@3000000000> " },
      { text: "<@5>", kind: "user", id: 5 },
    ]);
  });

  test("reads a team and the two keywords, and refuses the near misses", () => {
    expect(mentionSegments("a <@team:5> b <@watchers> c <@everyone>")).toEqual([
      { text: "a " },
      { text: "<@team:5>", kind: "team", id: 5 },
      { text: " b " },
      { text: "<@watchers>", kind: "watchers" },
      { text: " c " },
      { text: "<@everyone>", kind: "everyone" },
    ]);
    expect(mentionSegments("<@team:2147483647>")).toEqual([{ text: "<@team:2147483647>", kind: "team", id: 2147483647 }]);
    expect(
      mentionSegments("<@Everyone> <@heres> <@teams:4> <@team: 4> <@team:0> <@team:042> <@team:2147483648> <@team:12345678901> <@everyone > <@team>"),
    ).toBeNull();
  });
});

describe("mentionsEveryone", () => {
  test("is true only for the keyword token", () => {
    expect(mentionsEveryone("ping <@everyone> now")).toBe(true);
    expect(mentionsEveryone("<@watchers> <@team:4> <@5>")).toBe(false);
    expect(mentionsEveryone("@everyone")).toBe(false);
  });
});
