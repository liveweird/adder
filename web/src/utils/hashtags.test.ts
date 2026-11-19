import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import ReactMarkdown from "react-markdown";
import remarkGemoji from "remark-gemoji";
import remarkGfm from "remark-gfm";
import { describe, expect, test } from "vitest";
import cases from "../../../server/src/test/resources/hashtag-cases.json";
import { hashtagSegments } from "./hashtags";
import remarkHashtags from "./remarkHashtags";

/** The tags the SPA links in `body`: the real Markdown pipeline of a message (gfm, gemoji, then the plugin), each `data-hashtag` link in order. */
function linkedTags(body: string): string[] {
  const tags: string[] = [];
  renderToStaticMarkup(
    createElement(ReactMarkdown, {
      children: body,
      remarkPlugins: [remarkGfm, remarkGemoji, remarkHashtags],
      components: {
        a: (props) => {
          const tag = (props as { "data-hashtag"?: string })["data-hashtag"];
          if (tag !== undefined) tags.push(tag);
          return null;
        },
      },
    }),
  );
  return tags;
}

/** What the server stores for a message: distinct by lower case (the first spelling wins), NFC, the first 20, in order of appearance. */
function indexed(tags: string[]): string[] {
  const seen = new Set<string>();
  const out: string[] = [];
  for (const tag of tags.map((raw) => raw.normalize("NFC"))) {
    const key = tag.toLowerCase();
    if (seen.has(key)) continue;
    seen.add(key);
    out.push(tag);
  }
  return out.slice(0, 20);
}

describe("the shared hashtag case table (also read by the server's HashtagsTest)", () => {
  test("the table is the one the server reads", () => {
    expect(cases.length).toBeGreaterThanOrEqual(40);
  });

  test.each(cases)("$name", ({ body, tags }) => {
    expect(indexed(linkedTags(body))).toEqual(tags.map((tag) => tag.normalize("NFC")));
  });
});

describe("hashtagSegments", () => {
  test("a text with no hash is left alone, and so is one whose hashes are no tags", () => {
    expect(hashtagSegments("nothing here")).toBeNull();
    expect(hashtagSegments("see #42 and a#b")).toBeNull();
  });

  test("cuts the text around each tag and keeps the spelling as typed", () => {
    expect(hashtagSegments("a #Out-age, then #b")).toEqual([
      { text: "a " },
      { text: "#Out-age", tag: "Out-age" },
      { text: ", then " },
      { text: "#b", tag: "b" },
    ]);
    expect(hashtagSegments("#a")).toEqual([{ text: "#a", tag: "a" }]);
  });

  test("a decomposed letter is normalised so it joins its mark", () => {
    expect(hashtagSegments("#Wdrożenie")).toEqual([{ text: "#Wdrożenie", tag: "Wdrożenie" }]);
  });

  test("the character before the text takes part in the lookbehind", () => {
    expect(hashtagSegments("#frag")).toEqual([{ text: "#frag", tag: "frag" }]);
    expect(hashtagSegments("#frag", "m")).toBeNull();
    expect(hashtagSegments("#frag then #real", "m")).toEqual([{ text: "#frag then " }, { text: "#real", tag: "real" }]);
  });
});
