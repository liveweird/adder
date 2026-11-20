/**
 * The mention grammar, the SPA's half of the server's (`threads/Mentions.kt`; the shared case table is
 * `server/src/test/resources/mention-cases.json`, read by both test suites):
 *
 *     <@([1-9][0-9]{0,9})>        the id is 1 to 2147483647
 *
 * The server also refuses a token right after a backslash; the SPA reads the UNESCAPED text, where the backslash is gone, so it cannot see
 * that. It does not need to: a token becomes a name only when its id is in the message's `mentions` (the server's verdict), so where the
 * two parsers read differently the reader sees the raw token, never a wrong name. Where a mention may be found (not inside code, a
 * definition, raw HTML or an image) is `remarkMentions.ts`'s business, not this file's.
 */

// `\d` is ASCII 0-9 only in a JS regex (never the other scripts' digits), the same set as the server's `[0-9]`.
const MENTION = /<@([1-9]\d{0,9})>/g;
const MAX_ID = 2_147_483_647;

/** A run of text: plain, or a mention (`text` is the whole `<@42>` token as written; `id` the number inside it). */
export type MentionSegment = { text: string; id?: undefined } | { text: string; id: number };

/** `value` cut into text and mention segments, in order, or null when it holds no mention (the text is left exactly as it is). */
export function mentionSegments(value: string): MentionSegment[] | null {
  if (!value.includes("<@")) return null;
  const segments: MentionSegment[] = [];
  let from = 0;
  for (const match of value.matchAll(MENTION)) {
    const id = Number(match[1]);
    if (id > MAX_ID) continue;
    if (match.index > from) segments.push({ text: value.slice(from, match.index) });
    segments.push({ text: match[0], id });
    from = match.index + match[0].length;
  }
  if (segments.length === 0) return null;
  if (from < value.length) segments.push({ text: value.slice(from) });
  return segments;
}
