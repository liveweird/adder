import { mentionSegments } from "./mentions";

/**
 * A remark plugin: every `<@id>` token in the text of a Markdown tree becomes a `mention` node (children: the token as text; `data.hName`
 * `span` and `data-mention`: the id) that `MarkdownView`'s message variant renders as the person's name when the message's `mentions` list
 * the id, and as the raw token otherwise. The tree is walked by hand, as `remarkHashtags` does (no `unist-util-visit`: only transitive).
 *
 * Every node is entered except the ones that are no prose: `code`, `inlineCode`, `definition`, `html` (raw HTML is not rendered) and
 * `image`. Unlike a hashtag, a mention inside a link's text is shown: a span inside a link is valid HTML.
 */

interface MdNode {
  type: string;
  value?: string;
  children?: MdNode[];
  data?: { hName: string; hProperties: Record<string, string> };
}

const SKIPPED = new Set(["code", "inlineCode", "definition", "html", "image"]);

function mention(token: string, id: number): MdNode {
  return { type: "mention", children: [{ type: "text", value: token }], data: { hName: "span", hProperties: { "data-mention": String(id) } } };
}

function transform(node: MdNode): void {
  if (node.children === undefined || SKIPPED.has(node.type)) return;
  const next: MdNode[] = [];
  for (const child of node.children) {
    const segments = child.type === "text" && child.value !== undefined ? mentionSegments(child.value) : null;
    if (segments === null) {
      transform(child);
      next.push(child);
      continue;
    }
    for (const segment of segments) next.push(segment.id === undefined ? { type: "text", value: segment.text } : mention(segment.text, segment.id));
  }
  node.children = next;
}

export default function remarkMentions() {
  return transform;
}
