/**
 * The thread reference grammar, the SPA's alone (1.2 A9: nothing is indexed and the server has no counterpart; `#42` is not a hashtag, whose
 * capture must hold a letter, `hashtags.ts`):
 *
 *     (?<![\p{L}\p{N}#&/])#([1-9]\d{0,8})(?![\p{L}\p{N}_-])
 *
 * The same left boundary as a hashtag (a letter, digit, `#`, `&` or `/` before the hash glues it to something else: `foo#42`, `&#39;`,
 * `/#42`), a plain positive number with no leading zero and at most nine digits (a thread number is an int32), and a right boundary that
 * refuses a letter, digit, `_` or `-` after it (`#42a` is a hashtag, `#42-7` and `#007` are neither). The `u` flag makes the lookbehind
 * read code points, and the text is NFC-normalised before it is split, as the hashtag parser's is. Where a reference may be found (not
 * inside code or links) is `remarkThreadRefs.ts`'s business.
 */

const THREAD_REF = /(?<![\p{L}\p{N}#&/])#([1-9]\d{0,8})(?![\p{L}\p{N}_-])/gu;

/** A run of text: plain, or a thread reference (`text` is the whole `#42`; `number` the number). */
export type ThreadRefSegment = { text: string; number?: undefined } | { text: string; number: number };

/**
 * `value` cut into text and reference segments, in order, or null when it holds no reference (the text is left exactly as it is: only a node
 * that holds one is NFC-normalised). `before` is the character that sits just before `value` in the document when a neighbouring node
 * carries it (the lookbehind needs it), "" by default. With a positive limit, the unsplit remainder stays text.
 */
export function threadRefSegments(value: string, before = "", limit = Infinity): ThreadRefSegment[] | null {
  if (!value.includes("#")) return null;
  const text = value.normalize("NFC");
  const lead = before.length;
  const segments: ThreadRefSegment[] = [];
  let from = 0;
  let remaining = limit;
  for (const match of (before + text).matchAll(THREAD_REF)) {
    const start = match.index - lead;
    if (start > from) segments.push({ text: text.slice(from, start) });
    segments.push({ text: `#${match[1]}`, number: Number(match[1]) });
    from = start + match[1].length + 1;
    if (--remaining === 0) break;
  }
  if (segments.length === 0) return null;
  if (from < text.length) segments.push({ text: text.slice(from) });
  return segments;
}
