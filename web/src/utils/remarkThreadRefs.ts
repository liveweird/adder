import { charBefore, SKIPPED, type MdNode } from "./remarkHashtags";
import { threadRefSegments } from "./threadRefs";

/**
 * Mark the first numeric references in a message for room-scoped validation. The cap bounds browser observers and API work.
 * The walk shares the hashtag plugin's Markdown exclusions and left-boundary handling. The grammar is in `threadRefs.ts`.
 */

const MAX_REFERENCES_PER_BODY = 20;

function link(text: string, number: number): MdNode {
  return { type: "link", url: "", children: [{ type: "text", value: text }], data: { hProperties: { "data-thread-number": String(number) } } };
}

function transform(node: MdNode, budget: { remaining: number }): void {
  if (node.children === undefined || SKIPPED.has(node.type)) return;
  const siblings = node.children;
  const next: MdNode[] = [];
  for (const [index, child] of siblings.entries()) {
    if (budget.remaining === 0) {
      next.push(...siblings.slice(index));
      break;
    }
    const segments = child.type === "text" && child.value !== undefined
      ? threadRefSegments(child.value, charBefore(siblings[index - 1]), budget.remaining)
      : null;
    if (segments === null) {
      transform(child, budget);
      next.push(child);
      continue;
    }
    for (const segment of segments) {
      if (segment.number === undefined) next.push({ type: "text", value: segment.text });
      else {
        next.push(link(segment.text, segment.number));
        budget.remaining -= 1;
      }
    }
  }
  node.children = next;
}

export default function remarkThreadRefs() {
  return (node: MdNode) => transform(node, { remaining: MAX_REFERENCES_PER_BODY });
}
