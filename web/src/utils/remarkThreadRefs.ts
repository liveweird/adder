import { charBefore, SKIPPED, type MdNode } from "./remarkHashtags";
import { threadRefSegments } from "./threadRefs";

/**
 * A remark plugin: every `#42` in the text of a Markdown tree becomes a `link` node (children: the `#42` text; `data-thread-number`: the
 * number) that `MarkdownView`'s message variant renders as a router link to that thread of the same room (1.2 A9). It is `remarkHashtags`'s
 * twin, with the same walk and the same skipped nodes (`code`, `inlineCode`, `link`, `linkReference`, `definition`, `html`, `image`: a number in
 * code or in a link's text stays text, and a bare URL is a `link` through gfm's autolinks, which run first), and the same hand-over of an e-mail
 * autolink's last character to the left boundary. Nothing checks that the thread exists: a number with no thread opens the pane's "does not
 * exist" (`web-internals.md`). The grammar is `threadRefs.ts`.
 */

function link(text: string, number: number): MdNode {
  return { type: "link", url: "", children: [{ type: "text", value: text }], data: { hProperties: { "data-thread-number": String(number) } } };
}

function transform(node: MdNode): void {
  if (node.children === undefined || SKIPPED.has(node.type)) return;
  const siblings = node.children;
  const next: MdNode[] = [];
  siblings.forEach((child, index) => {
    const segments = child.type === "text" && child.value !== undefined ? threadRefSegments(child.value, charBefore(siblings[index - 1])) : null;
    if (segments === null) {
      transform(child);
      next.push(child);
      return;
    }
    for (const segment of segments) next.push(segment.number === undefined ? { type: "text", value: segment.text } : link(segment.text, segment.number));
  });
  node.children = next;
}

export default function remarkThreadRefs() {
  return transform;
}
