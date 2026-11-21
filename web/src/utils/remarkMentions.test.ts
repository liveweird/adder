import { describe, expect, test } from "vitest";
import remarkMentions from "./remarkMentions";

type Tree = Parameters<ReturnType<typeof remarkMentions>>[0];

const text = (value: string) => ({ type: "text", value });
const mention = (id: number) => ({
  type: "mention",
  children: [text(`<@${id}>`)],
  data: { hName: "span", hProperties: { "data-mention": String(id) } },
});
const team = (id: number) => ({
  type: "mention",
  children: [text(`<@team:${id}>`)],
  data: { hName: "span", hProperties: { "data-mention-team": String(id) } },
});
const keyword = (word: "everyone" | "watchers") => ({
  type: "mention",
  children: [text(`<@${word}>`)],
  data: { hName: "span", hProperties: { "data-mention-group": word } },
});
const run = (...children: Tree[]): Tree => {
  const tree: Tree = { type: "root", children: [{ type: "paragraph", children }] };
  remarkMentions()(tree);
  return tree.children?.[0].children as unknown as Tree;
};

describe("remarkMentions", () => {
  test("splits a text node into text and mention nodes and keeps the words around the token", () => {
    expect(run(text("Ping <@42> and <@7>, thanks.")) as unknown).toEqual([text("Ping "), mention(42), text(" and "), mention(7), text(", thanks.")]);
  });

  test("marks a team by id and the two keywords by word", () => {
    expect(run(text("a <@team:5> <@everyone> <@watchers> <@6>")) as unknown).toEqual([
      text("a "),
      team(5),
      text(" "),
      keyword("everyone"),
      text(" "),
      keyword("watchers"),
      text(" "),
      mention(6),
    ]);
  });

  test("a text node with no mention is left exactly as it is", () => {
    const plain = text("write to a@b.com, <@0> and <@x>");
    const [kept] = run(plain) as unknown as Tree[];
    expect(kept).toBe(plain);
  });

  test("never enters code, inline code, definitions, html or images", () => {
    for (const type of ["code", "inlineCode", "html", "definition"]) {
      const node = { type, value: "<@5>" };
      expect(run(node) as unknown).toEqual([node]);
    }
    const image = { type: "image", url: "x.png", alt: "<@5>" };
    expect(run(image) as unknown).toEqual([image]);
  });

  test("enters a link's text, where a hashtag would not be", () => {
    const [link] = run({ type: "link", url: "https://example.com", children: [text("see <@12>")] } as unknown as Tree) as unknown as Tree[];
    expect(link.children).toEqual([text("see "), mention(12)]);
  });

  test("reaches text nested in emphasis, strong, lists and quotes", () => {
    const [strong] = run({ type: "strong", children: [text("a <@3>")] }) as unknown as Tree[];
    expect(strong.children).toEqual([text("a "), mention(3)]);
    const tree: Tree = {
      type: "root",
      children: [{ type: "blockquote", children: [{ type: "list", children: [{ type: "listItem", children: [{ type: "paragraph", children: [text("<@9>")] }] }] }] }],
    };
    remarkMentions()(tree);
    expect(JSON.stringify(tree)).toContain('"data-mention":"9"');
  });
});
