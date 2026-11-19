import { describe, expect, test } from "vitest";
import remarkHashtags from "./remarkHashtags";

type Tree = Parameters<ReturnType<typeof remarkHashtags>>[0];

const text = (value: string) => ({ type: "text", value });
const tag = (name: string) => ({
  type: "link",
  url: "",
  children: [text(`#${name}`)],
  data: { hProperties: { "data-hashtag": name } },
});
const run = (...children: Tree[]): Tree => {
  const tree: Tree = { type: "root", children: [{ type: "paragraph", children }] };
  remarkHashtags()(tree);
  return tree.children?.[0].children as unknown as Tree;
};

describe("remarkHashtags", () => {
  test("splits a text node into text and link nodes and keeps the words around the tag", () => {
    expect(run(text("Rollback of #Outage done, see #db-3.")) as unknown).toEqual([text("Rollback of "), tag("Outage"), text(" done, see "), tag("db-3"), text(".")]);
  });

  test("a text node with no tag, and a tag that is no tag, are left exactly as they are", () => {
    const plain = text("see #42 and foo#bar");
    const [kept] = run(plain) as unknown as Tree[];
    expect(kept).toBe(plain);
  });

  test("never enters code, links, link references, definitions, html or images", () => {
    for (const type of ["code", "inlineCode", "html", "definition"]) {
      const node = { type, value: "#a" };
      expect(run(node) as unknown).toEqual([node]);
    }
    for (const type of ["link", "linkReference"]) {
      const node = { type, url: "https://example.com", children: [text("#a")] };
      expect(run(node) as unknown).toEqual([{ type, url: "https://example.com", children: [text("#a")] }]);
    }
    const image = { type: "image", url: "x.png", alt: "#a" };
    expect(run(image) as unknown).toEqual([image]);
  });

  test("reaches text nested in emphasis, strong, lists and quotes", () => {
    const [strong] = run({ type: "strong", children: [text("a #b")] }) as unknown as Tree[];
    expect(strong.children).toEqual([text("a "), tag("b")]);
    const tree: Tree = {
      type: "root",
      children: [{ type: "blockquote", children: [{ type: "list", children: [{ type: "listItem", children: [{ type: "paragraph", children: [text("#c")] }] }] }] }],
    };
    remarkHashtags()(tree);
    expect(JSON.stringify(tree)).toContain('"data-hashtag":"c"');
  });

  test("the tag is the spelling as typed, normalised, and the link text carries the hash", () => {
    expect(run(text("#Wdrożenie")) as unknown).toEqual([tag("Wdrożenie")]);
  });

  test("an e-mail autolink glues the hash that follows it", () => {
    const email = { type: "link", url: "mailto:a@b.com", children: [text("a@b.com")] };
    expect(run(email, text("#frag then #real")) as unknown).toEqual([email, text("#frag then "), tag("real")]);
    // A link that only looks like one, or any other neighbour, ends in markup the server sees: the tag stands.
    const labelled = { type: "link", url: "mailto:a@b.com", children: [text("write to us")] };
    expect(run(labelled, text("#frag")) as unknown).toEqual([labelled, tag("frag")]);
    const web = { type: "link", url: "https://example.com", children: [text("docs")] };
    expect(run(web, text("#frag")) as unknown).toEqual([web, tag("frag")]);
  });
});
