/**
 * The hashtag grammar, the SPA's half of the server's (`threads/Hashtags.kt`; the shared case table is
 * `server/src/test/resources/hashtag-cases.json`, read by both test suites):
 *
 *     (?<![\p{L}\p{N}#&/])#([\p{L}\p{N}_-]{1,50})(?![\p{L}\p{N}_-])     and the capture holds a \p{L}
 *
 * A letter or digit, `#`, `&` or `/` before the hash glues it to something else (`foo#bar`, `&#39;`, `/#anchor`); a run of 51 or more
 * tag characters is no tag at all (the lookahead refuses every shorter prefix); `#42` is a thread number, not a tag. The `u` flag makes
 * the lookbehind read code points, so a supplementary-plane letter before the hash counts. The server NFC-normalises the body for
 * parsing, so a decomposed letter joins its mark; here the text node is normalised the same way before it is split.
 * Where the tags may be found (not inside code or links) is `remarkHashtags.ts`'s business, not this file's.
 */

const HASHTAG = /(?<![\p{L}\p{N}#&/])#([\p{L}\p{N}_-]{1,50})(?![\p{L}\p{N}_-])/gu;
const LETTER = /\p{L}/u;

/** A run of text: plain, or a hashtag (`text` is the whole `#Tag` as typed, NFC; `tag` the part after the hash). */
export type HashtagSegment = { text: string; tag?: undefined } | { text: string; tag: string };

/**
 * `value` cut into text and hashtag segments, in order, or null when it holds no hashtag (the text is left exactly as it is: only a node
 * that holds a tag is NFC-normalised). `before` is the character that sits just before `value` in the document when a neighbouring node
 * carries it (the lookbehind needs it), "" by default.
 */
export function hashtagSegments(value: string, before = ""): HashtagSegment[] | null {
  if (!value.includes("#")) return null;
  const text = value.normalize("NFC");
  const lead = before.length;
  const segments: HashtagSegment[] = [];
  let from = 0;
  for (const match of (before + text).matchAll(HASHTAG)) {
    const tag = match[1];
    if (!LETTER.test(tag)) continue;
    const start = match.index - lead;
    if (start > from) segments.push({ text: text.slice(from, start) });
    segments.push({ text: `#${tag}`, tag });
    from = start + tag.length + 1;
  }
  if (segments.length === 0) return null;
  if (from < text.length) segments.push({ text: text.slice(from) });
  return segments;
}
