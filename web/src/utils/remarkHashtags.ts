import { hashtagSegments } from "./hashtags";

/**
 * A remark plugin: every hashtag in the text of a Markdown tree becomes a `link` node (children: the `#Tag` text; `data-hashtag`: the tag
 * as typed, without its hash) that `MarkdownView`'s message variant renders as a router link. The tree is walked by hand (no
 * `unist-util-visit`: it is only transitive, and this is a dozen lines); the grammar is `hashtags.ts`.
 *
 * Nodes that are code or already a link are never entered, so a tag in them stays text: `code`, `inlineCode`, `link`, `linkReference`
 * (its text is a link's), `definition`, `html` (raw HTML is not rendered anyway) and `image`. A bare URL is a `link` through remark-gfm's
 * autolinks (which run before this plugin), so `https://example.com/#frag` is skipped with it. The server also indexes the text of a
 * `[link](…)`, which the SPA never links (a link inside a link is invalid HTML): a known, documented difference.
 */

export interface MdNode {
  type: string;
  value?: string;
  url?: string;
  children?: MdNode[];
  data?: { hProperties: Record<string, string> };
}

export const SKIPPED = new Set(["code", "inlineCode", "link", "linkReference", "definition", "html", "image"]);

/**
 * The character that sits before the next text node in the document when the node ahead of it carries it. gfm turns
 * `mail@example.com#frag` into an e-mail link followed by the text `#frag`, but the server sees the `m` glued to the hash (no tag): that
 * autolink's last character is passed on to the lookbehind. The same holds for a hashtag link (`#release#42`: `remarkThreadRefs` runs after this
 * plugin, so the `#42` follows the link `#release`, and the `e` before the hash glues it). Any other neighbour ends in markup the server sees too
 * (`)`, `*`, a space), which the lookbehind accepts.
 */
export function charBefore(previous: MdNode | undefined): string {
  if (previous?.type !== "link" || previous.children?.length !== 1) return "";
  const text = previous.children[0].value ?? "";
  if (previous.data?.hProperties["data-hashtag"] !== undefined) return text.slice(-1);
  return previous.url?.startsWith("mailto:") === true && text === previous.url.slice("mailto:".length) ? text.slice(-1) : "";
}

function link(text: string, tag: string): MdNode {
  return { type: "link", url: "", children: [{ type: "text", value: text }], data: { hProperties: { "data-hashtag": tag } } };
}

function transform(node: MdNode): void {
  if (node.children === undefined || SKIPPED.has(node.type)) return;
  const siblings = node.children;
  const next: MdNode[] = [];
  siblings.forEach((child, index) => {
    const segments = child.type === "text" && child.value !== undefined ? hashtagSegments(child.value, charBefore(siblings[index - 1])) : null;
    if (segments === null) {
      transform(child);
      next.push(child);
      return;
    }
    for (const segment of segments) next.push(segment.tag === undefined ? { type: "text", value: segment.text } : link(segment.text, segment.tag));
  });
  node.children = next;
}

export default function remarkHashtags() {
  return transform;
}
