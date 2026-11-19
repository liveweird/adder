import { Fragment } from "react";
import { Mark } from "@mantine/core";
import type { SnippetSegment } from "../api/search";

/**
 * A search snippet: the server's typed segments, each drawn as a TEXT node, the words that matched in a `<mark>`. Never HTML and never
 * Markdown: a segment is a fragment of a title or of a message's Markdown source and may hold anything a person typed (`<img onerror=...>`
 * included), so nothing here parses it. A snippet is a fragment: its edges may cut a sentence short.
 */
export default function Snippet({ segments }: { segments: readonly SnippetSegment[] }) {
  return (
    <>
      {segments.map((segment, index) =>
        segment.hit ? <Mark key={index}>{segment.text}</Mark> : <Fragment key={index}>{segment.text}</Fragment>,
      )}
    </>
  );
}
