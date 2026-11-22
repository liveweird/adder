import type { MessageResponse } from "../api/messages";
import { mentionSegments } from "./mentions";

/** The most a collapsed line or an expanded card quotes of a pinned message, in graphemes; CSS cuts the rest to one or two lines. */
const EXCERPT_LIMIT = 280;

/**
 * A line is cut to this many UTF-16 units BEFORE it is unwrapped (a guard on top of the linear unwrapping: the work per line is bounded
 * whatever an 8,000-character body holds). Generous on purpose: the excerpt is 280 GRAPHEMES, and one emoji sequence is up to ~16 units
 * (`family.repeat(300)` is 2,400), so a cut of a few hundred units would shorten ordinary emoji-heavy text. 1.2 branch audit: `*a *a *a ...`
 * took 35 to 60 ms per call before the unwrapping was made linear.
 */
const LINE_CUT = 16 * EXCERPT_LIMIT;

/** The excerpts already computed, per message object: `[first line only, whole]`. */
const excerpts = new WeakMap<object, [string | undefined, string | undefined]>();

const FENCE = /^\s*(?:```|~~~)/;
const HAS_WORD = /[\p{L}\p{N}]/u;
// ONE heading, quote, bullet or list-number marker at the start of a line (stripped repeatedly: a quote may hold a bullet, `> - x`).
const LEADING_MARKER = /^(?:#{1,6}\s+|>\s*|[-*+]\s+|\d+[.)]\s+)/;
const WORD_CHAR = /[\p{L}\p{N}_]/u;
const GRAPHEMES = new Intl.Segmenter(undefined, { granularity: "grapheme" });

/**
 * A pinned message as one plain line of text for the thread's pin strip (1.2 D5). Plain text on purpose: the strip's line sits inside a button and
 * the card clamps to two lines, so neither can hold a link or a block. What it does to the body, line by line:
 * - fenced code is left out (the fence lines and what is inside), unless the body is nothing else;
 * - only lines with a letter or a digit count ("---" and a lone emoji do not), unless no line has one;
 * - a leading heading, quote, bullet or list number is dropped, and emphasis is unwrapped: `**x**`, `__x__`, `~~x~~`, `*x*`, `_x_` (never the
 *   underscores inside `snake_case`), `code` and `[text](url)` read as the bare text;
 * - a mention token reads as the name the message lists (`@Name`, `@handle`, `@watchers`, `@everyone`) and stays the raw token when it does
 *   not, as `MarkdownView` does.
 * `firstLineOnly` is the collapsed line ("the first line of the newest pin"); otherwise the lines are joined by spaces. At most 280 graphemes
 * (an emoji sequence is never cut in the middle), then an ellipsis. A tombstone has no body and quotes nothing.
 */
export function messageExcerpt(message: Pick<MessageResponse, "body" | "mentions" | "groupMentions">, firstLineOnly = false): string {
  // Per message object (the query cache hands the same one back until a refetch): the strip and the cards re-render far more often than a body changes.
  const cached = excerpts.get(message) ?? [undefined, undefined];
  const slot = firstLineOnly ? 0 : 1;
  const known = cached[slot];
  if (known !== undefined) return known;
  const excerpt = computeExcerpt(message, firstLineOnly);
  cached[slot] = excerpt;
  excerpts.set(message, cached);
  return excerpt;
}

function computeExcerpt(message: Pick<MessageResponse, "body" | "mentions" | "groupMentions">, firstLineOnly: boolean): string {
  const { prose, code } = splitLines(message.body ?? "");
  const words = prose.filter((line) => HAS_WORD.test(line));
  const chosen = words.length > 0 ? words : prose.length > 0 ? prose : code;
  const text = (firstLineOnly ? chosen.slice(0, 1) : chosen).join(" ");
  const read = readMentions(text, message);
  const graphemes = Array.from(GRAPHEMES.segment(read), (part) => part.segment);
  return graphemes.length > EXCERPT_LIMIT ? `${graphemes.slice(0, EXCERPT_LIMIT).join("").trimEnd()}…` : read;
}

/** The body's non-blank lines, cleaned: the prose ones, and (kept only as a fallback) the ones inside a code fence. */
function splitLines(body: string): { prose: string[]; code: string[] } {
  const prose: string[] = [];
  const code: string[] = [];
  let fenced = false;
  for (const raw of body.split("\n")) {
    if (FENCE.test(raw)) {
      fenced = !fenced;
      continue;
    }
    const line = fenced ? raw.trim() : unwrap(stripMarkers(cutLine(raw.trim()))).trim();
    if (line !== "") (fenced ? code : prose).push(line);
  }
  return { prose, code };
}

/** [line] cut to [LINE_CUT] units, never between the two halves of a surrogate pair. */
function cutLine(line: string): string {
  if (line.length <= LINE_CUT) return line;
  const high = line.charCodeAt(LINE_CUT - 1);
  return line.slice(0, high >= 0xd800 && high <= 0xdbff ? LINE_CUT - 1 : LINE_CUT);
}

function stripMarkers(line: string): string {
  let out = line;
  for (let next = out.replace(LEADING_MARKER, ""); next !== out; next = out.replace(LEADING_MARKER, "")) out = next;
  return out;
}

/**
 * `[text](url)` reads as `text`: a plain scan (a label holds no `]`, a target no `)`), not a regex. LINEAR: the closing `]` of an
 * opening `[` and the `)` after it are found once and reused by every later `[` before that `]`, and with no `]` left the scan stops.
 */
function unwrapLinks(text: string): string {
  let out = "";
  let from = 0;
  let close = -1;
  let end = -1;
  for (let open = text.indexOf("["); open >= 0; open = text.indexOf("[", from)) {
    if (close < open) {
      close = text.indexOf("]", open);
      if (close < 0) break;
      end = text.startsWith("(", close + 1) ? text.indexOf(")", close + 2) : -1;
    }
    if (end < 0) {
      out += text.slice(from, open + 1);
      from = open + 1;
      continue;
    }
    out += text.slice(from, open) + text.slice(open + 1, close);
    from = end + 1;
  }
  return out + text.slice(from);
}

function unwrap(line: string): string {
  const linked = unwrapLinks(line.replaceAll("![", "[")).replace(/`([^`]+)`/g, "$1");
  return [
    ["**", false],
    ["__", false],
    ["~~", false],
    ["*", false],
    ["_", true],
  ].reduce((text, [delimiter, wordBounded]) => unwrapDelimited(text, delimiter as string, wordBounded as boolean), linked);
}

/**
 * Removes each pair of `delimiter` that wraps text (an opening one followed by a non-space, a closing one preceded by a non-space). With
 * `wordBounded` (underscores) the pair must also stand at word edges, so `snake_case_name` keeps its underscores. A plain scan, not a regex.
 */
function unwrapDelimited(text: string, delimiter: string, wordBounded: boolean): string {
  const size = delimiter.length;
  let out = "";
  let from = 0;
  while (from < text.length) {
    const open = text.indexOf(delimiter, from);
    const opens = open >= 0 && text.charAt(open + size).trim() !== "" && open + size < text.length && !(wordBounded && open > 0 && WORD_CHAR.test(text.charAt(open - 1)));
    const close = opens ? findClose(text, delimiter, open + size, wordBounded) : -1;
    if (close < 0) {
      // No pair from here: keep the text up to the delimiter and look again past it (or stop when there is none). An opening that WAS valid
      // and found no close means there is no valid close anywhere past it (the check depends on the text around the close alone), so a
      // later opening cannot find one either: stop instead of rescanning the rest for each of them (that was quadratic).
      if (open < 0 || opens) break;
      out += text.slice(from, open + size);
      from = open + size;
      continue;
    }
    out += text.slice(from, open) + text.slice(open + size, close);
    from = close + size;
  }
  return out + text.slice(from);
}

function findClose(text: string, delimiter: string, start: number, wordBounded: boolean): number {
  for (let at = text.indexOf(delimiter, start + 1); at >= 0; at = text.indexOf(delimiter, at + 1)) {
    const afterEdge = at + delimiter.length >= text.length || !(wordBounded && WORD_CHAR.test(text.charAt(at + delimiter.length)));
    if (text.charAt(at - 1).trim() !== "" && afterEdge) return at;
  }
  return -1;
}

function readMentions(text: string, message: Pick<MessageResponse, "mentions" | "groupMentions">): string {
  const segments = mentionSegments(text);
  if (segments === null) return text;
  return segments
    .map((segment) => {
      if (segment.kind === "user") {
        const person = message.mentions.find((m) => m.userId === segment.id);
        return person ? `@${person.name}` : segment.text;
      }
      if (segment.kind === "team") {
        const team = message.groupMentions.find((g) => g.kind === "TEAM" && g.teamId === segment.id);
        return team?.teamHandle ? `@${team.teamHandle}` : segment.text;
      }
      if (segment.kind === "watchers" || segment.kind === "everyone") {
        const listed = message.groupMentions.some((g) => g.kind === segment.kind.toUpperCase());
        return listed ? `@${segment.kind}` : segment.text;
      }
      return segment.text;
    })
    .join("");
}
