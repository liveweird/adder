/**
 * The mention grammar, the SPA's half of the server's (`threads/Mentions.kt`; the shared case table is
 * `server/src/test/resources/mention-cases.json`, read by both test suites):
 *
 *     <@([1-9][0-9]{0,9})>                   a person; the id is 1 to 2147483647
 *     <@team:([1-9][0-9]{0,9})>              a team (by id: a renamed handle never breaks a message)
 *     <@watchers>  <@everyone>               the thread's watchers, the room
 *
 * All three forms start with `<@` on purpose (`<!...>` is an HTML declaration and `<team:42>` an autolink to the Markdown parsers; `<@...>` is
 * literal text to both). Lowercase and exact.
 *
 * The server also refuses a token right after a backslash; the SPA reads the UNESCAPED text, where the backslash is gone, so it cannot see
 * that. It does not need to: a token becomes a name only when the server's verdict lists it (`mentions` for a person, `groupMentions` for a
 * team, `@watchers` and `@everyone`), so where the two parsers read differently the reader sees the raw token, never a wrong name. Where a
 * mention may be found (not inside code, a definition, raw HTML or an image) is `remarkMentions.ts`'s business, not this file's.
 */

// `\d` is ASCII 0-9 only in a JS regex (never the other scripts' digits), the same set as the server's `[0-9]`.
const MENTION = /<@(?:([1-9]\d{0,9})|team:([1-9]\d{0,9})|(watchers|everyone))>/g;
const MAX_ID = 2_147_483_647;

/** The two keywords a group mention can be. */
type MentionKeyword = "watchers" | "everyone";

/**
 * A run of text: plain, or a mention (`text` is the whole token as written; `id` the number inside a person's or a team's token, the keyword
 * for the other two kinds).
 */
export type MentionSegment =
  | { text: string; kind?: undefined; id?: undefined }
  | { text: string; kind: "user" | "team"; id: number }
  | { text: string; kind: MentionKeyword; id?: undefined };

/** `value` cut into text and mention segments, in order, or null when it holds no mention (the text is left exactly as it is). */
export function mentionSegments(value: string): MentionSegment[] | null {
  if (!value.includes("<@")) return null;
  const segments: MentionSegment[] = [];
  let from = 0;
  for (const match of value.matchAll(MENTION)) {
    const [token, person, team, keyword] = match;
    const id = Number(person ?? team);
    if (keyword === undefined && id > MAX_ID) continue;
    if (match.index > from) segments.push({ text: value.slice(from, match.index) });
    if (keyword !== undefined) segments.push({ text: token, kind: keyword as MentionKeyword });
    else segments.push({ text: token, kind: person !== undefined ? "user" : "team", id });
    from = match.index + token.length;
  }
  if (segments.length === 0) return null;
  if (from < value.length) segments.push({ text: value.slice(from) });
  return segments;
}

/** Whether `value` holds an `<@everyone>` token (the composer's note about how many people it reaches). */
export function mentionsEveryone(value: string): boolean {
  return mentionSegments(value)?.some((segment) => segment.kind === "everyone") ?? false;
}
