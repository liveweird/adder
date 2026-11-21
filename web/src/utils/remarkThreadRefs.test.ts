import { describe, expect, test } from "vitest";
import remarkThreadRefs from "./remarkThreadRefs";

type Tree = Parameters<ReturnType<typeof remarkThreadRefs>>[0];

const text = (value: string) => ({ type: "text", value });
const ref = (number: number) => ({
  type: "link",
  url: "",
  children: [text(`#${number}`)],
  data: { hProperties: { "data-thread-number": String(number) } },
});
const run = (...children: Tree[]): Tree => {
  const tree: Tree = { type: "root", children: [{ type: "paragraph", children }] };
  remarkThreadRefs()(tree);
  return tree.children?.[0].children as unknown as Tree;
};

describe("remarkThreadRefs", () => {
  test("splits a text node into text and link nodes and keeps the words around the number", () => {
    expect(run(text("Duplicate of #42, see #7.")) as unknown).toEqual([text("Duplicate of "), ref(42), text(", see "), ref(7), text(".")]);
  });

  test("a text node with no reference, and a hashtag, are left exactly as they are", () => {
    const plain = text("see #outage and foo#42");
    const [kept] = run(plain) as unknown as Tree[];
    expect(kept).toBe(plain);
  });

  test("never enters code, links, link references, definitions, html or images", () => {
    for (const type of ["code", "inlineCode", "html", "definition"]) {
      const node = { type, value: "#42" };
      expect(run(node) as unknown).toEqual([node]);
    }
    for (const type of ["link", "linkReference"]) {
      const node = { type, url: "https://example.com", children: [text("#42")] };
      expect(run(node) as unknown).toEqual([{ type, url: "https://example.com", children: [text("#42")] }]);
    }
    const image = { type: "image", url: "x.png", alt: "#42" };
    expect(run(image) as unknown).toEqual([image]);
  });

  test("reaches text nested in emphasis, strong, lists and quotes", () => {
    const [strong] = run({ type: "strong", children: [text("a #42")] }) as unknown as Tree[];
    expect(strong.children).toEqual([text("a "), ref(42)]);
    const tree: Tree = {
      type: "root",
      children: [{ type: "blockquote", children: [{ type: "list", children: [{ type: "listItem", children: [{ type: "paragraph", children: [text("#9")] }] }] }] }],
    };
    remarkThreadRefs()(tree);
    expect(JSON.stringify(tree)).toContain('"data-thread-number":"9"');
  });

  test("a hashtag link glues the hash that follows it: #release#42 is a hashtag and no reference", () => {
    const hashtag = { type: "link", url: "", children: [text("#release")], data: { hProperties: { "data-hashtag": "release" } } };
    expect(run(hashtag, text("#42 and #43")) as unknown).toEqual([hashtag, text("#42 and "), ref(43)]);
    expect(run(hashtag, text(" #42")) as unknown).toEqual([hashtag, text(" "), ref(42)]);
  });

  test("an e-mail autolink glues the hash that follows it", () => {
    const email = { type: "link", url: "mailto:a@b.com", children: [text("a@b.com")] };
    expect(run(email, text("#42 then #43")) as unknown).toEqual([email, text("#42 then "), ref(43)]);
  });
});
