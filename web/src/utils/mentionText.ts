import { mentionSegments, type MentionSegment } from "./mentions";

/**
 * The composer's two texts. The CANONICAL text is what is stored and sent: a mention is the token `<@id>` (`utils/mentions.ts`). The SHOWN text is
 * what the box displays: a mention whose name the session knows reads `@Name`; a token whose name is not known stays the token itself (a plain
 * run, as far as editing goes). This file is the pure mapping between the two: canonical to shown, an edit of the shown text back to the canonical
 * one, the `@term` being typed at the caret, and a pick. A mention is atomic: an edit that touches any part of its shown name removes the whole
 * token (the typed text, if any, goes in its place).
 */

/** The name the session knows for a person's id, or undefined. */
export type NameOf = (id: number) => string | undefined;

/** The handle the session knows for a team's id, or undefined. */
export type HandleOf = (teamId: number) => string | undefined;

/**
 * One run: plain text (equal in both texts) or one known mention (`atomic`; its shown form is `@Name`, `@handle`, `@watchers` or
 * `@everyone`). Offsets are where the run starts.
 */
type Piece = { canonical: string; shown: string; atomic?: true; id?: number; cStart: number; sStart: number };

export type Shown = { canonical: string; text: string; pieces: readonly Piece[] };

/** The longest `@term` the list searches for (the server's cap on the member name filter is 100). */
const MAX_MENTION_TERM = 50;

/** What follows the `@` of a token the session can name: a known person or team, and the two keywords always; undefined for anything else. */
function shownLabel(segment: MentionSegment, nameOf: NameOf, handleOf: HandleOf): string | undefined {
  switch (segment.kind) {
    case "user":
      return nameOf(segment.id);
    case "team":
      return handleOf(segment.id);
    case "watchers":
    case "everyone":
      return segment.kind;
    default:
      return undefined;
  }
}

export function toShown(canonical: string, nameOf: NameOf, handleOf: HandleOf = () => undefined): Shown {
  const pieces: Piece[] = [];
  let text = "";
  let cStart = 0;
  const add = (piece: { canonical: string; shown: string; atomic?: true; id?: number }) => {
    const last = pieces[pieces.length - 1];
    if (piece.atomic === undefined && last !== undefined && last.atomic === undefined) {
      pieces[pieces.length - 1] = { ...last, canonical: last.canonical + piece.canonical, shown: last.shown + piece.shown };
    } else {
      pieces.push({ ...piece, cStart, sStart: text.length });
    }
    cStart += piece.canonical.length;
    text += piece.shown;
  };
  for (const segment of mentionSegments(canonical) ?? (canonical === "" ? [] : [{ text: canonical }])) {
    const label = shownLabel(segment, nameOf, handleOf);
    if (label !== undefined) add({ canonical: segment.text, shown: `@${label}`, atomic: true, id: segment.id });
    else add({ canonical: segment.text, shown: segment.text });
  }
  return { canonical, text, pieces };
}

const shownEnd = (piece: Piece) => piece.sStart + piece.shown.length;
const canonicalEnd = (piece: Piece) => piece.cStart + piece.canonical.length;

/** The canonical offset of a shown offset that is on a run boundary or inside plain text (inside a mention it answers the mention's start). */
export function toCanonicalOffset(shown: Shown, offset: number): number {
  for (const piece of shown.pieces) {
    if (offset > shownEnd(piece)) continue;
    if (piece.atomic === undefined) return piece.cStart + (offset - piece.sStart);
    return offset === shownEnd(piece) ? canonicalEnd(piece) : piece.cStart;
  }
  return shown.canonical.length;
}

/** The shown offset of a canonical offset (inside a token it answers the end of the mention's shown name). */
export function toShownOffset(shown: Shown, offset: number): number {
  for (const piece of shown.pieces) {
    if (offset > canonicalEnd(piece)) continue;
    if (piece.atomic === undefined) return piece.sStart + (offset - piece.cStart);
    return offset === piece.cStart ? piece.sStart : shownEnd(piece);
  }
  return shown.text.length;
}

type Change = { from: number; to: number; inserted: string };

/** A selection of the shown text (offsets, `start < end` when something is selected). */
export type Selection = { start: number; end: number };

/**
 * The edit when it replaced a non-empty selection the box reported BEFORE it: shown `[start, end)` by whatever sits between the unchanged head
 * and tail. The text alone cannot tell "selected `nn` and typed `n`" from "backspaced one `n`", and the caret reads a typed character equal to
 * its neighbour as a deletion; the selection can. Null when there is none or the text does not fit it (a stale selection).
 */
function selectedChange(previous: string, next: string, caret: number | null, selection: Selection | null | undefined): Change | null {
  if (selection == null || selection.start >= selection.end || selection.start < 0 || selection.end > previous.length) return null;
  const tail = previous.length - selection.end;
  if (next.length < selection.start + tail) return null;
  if (!next.startsWith(previous.slice(0, selection.start)) || !next.endsWith(previous.slice(selection.end))) return null;
  const inserted = next.slice(selection.start, next.length - tail);
  if (caret !== null && caret !== selection.start + inserted.length) return null;
  return { from: selection.start, to: selection.end, inserted };
}

/**
 * What the edit replaced: shown `[from, to)` by `inserted`. With the caret the box reports after the edit (it sits right after what was typed,
 * pasted or composed, or at the cut for a deletion) the range is exact even among repeated characters; without a usable caret (an edit that
 * did not move it, such as an emoji spliced in by the picker) it is the common prefix and suffix.
 */
function changedRange(previous: string, next: string, caret: number | null, selection?: Selection | null): Change {
  const selected = selectedChange(previous, next, caret, selection);
  if (selected !== null) return selected;
  if (caret !== null && caret >= 0 && caret <= next.length) {
    const tail = next.slice(caret);
    const to = previous.length - tail.length;
    if (to >= 0 && previous.endsWith(tail)) {
      let from = 0;
      const limit = Math.min(caret, to);
      while (from < limit && previous[from] === next[from]) from++;
      return { from, to, inserted: next.slice(from, caret) };
    }
  }
  const limit = Math.min(previous.length, next.length);
  let from = 0;
  while (from < limit && previous[from] === next[from]) from++;
  let tail = 0;
  while (tail < limit - from && previous[previous.length - 1 - tail] === next[next.length - 1 - tail]) tail++;
  return { from, to: previous.length - tail, inserted: next.slice(from, next.length - tail) };
}

/** Whether a high surrogate ends the text (cutting there would leave half an emoji). */
function endsInHighSurrogate(text: string): boolean {
  const last = text.charCodeAt(text.length - 1);
  return last >= 0xd800 && last <= 0xdbff;
}

/**
 * The canonical text after the box's text became `next` (`caret`: its selection end, or null). A mention the edit touches goes whole. With
 * `maxLength`, the inserted text is cut to what still fits in the CANONICAL text (the native attribute would count the shown one), as the native
 * attribute cuts a paste. `selection` is the box's selection before the edit (shown offsets), which tells typing over a selection from a
 * deletion. In the answer `caret` is the canonical offset right after the inserted text and `from`/`to` the canonical range of the PREVIOUS
 * text the edit replaced.
 */
export function editCanonical(
  shown: Shown,
  next: string,
  caret: number | null,
  maxLength?: number,
  selection?: Selection | null,
): { canonical: string; caret: number; from: number; to: number } {
  let { from, to, inserted } = changedRange(shown.text, next, caret, selection);
  for (const piece of shown.pieces) {
    if (piece.atomic === undefined) continue;
    const start = piece.sStart;
    const end = shownEnd(piece);
    if (from === to ? from > start && from < end : from < end && to > start) {
      from = Math.min(from, start);
      to = Math.max(to, end);
    }
  }
  const cFrom = toCanonicalOffset(shown, from);
  const cTo = toCanonicalOffset(shown, to);
  if (maxLength !== undefined) {
    const room = Math.max(0, maxLength - (shown.canonical.length - (cTo - cFrom)));
    if (inserted.length > room) {
      inserted = inserted.slice(0, room);
      if (endsInHighSurrogate(inserted)) inserted = inserted.slice(0, -1);
    }
  }
  return { canonical: shown.canonical.slice(0, cFrom) + inserted + shown.canonical.slice(cTo), caret: cFrom + inserted.length, from: cFrom, to: cTo };
}

/** The `@term` being typed: `from` is the shown offset of the `@`, `term` what follows it up to the caret. */
export type MentionQuery = { from: number; term: string };

function insideMention(shown: Shown, offset: number): boolean {
  return shown.pieces.some((piece) => piece.atomic !== undefined && offset >= piece.sStart && offset < shownEnd(piece));
}

/**
 * The `@term` the caret is in, or null. The `@` must start a word (the start of the text or after whitespace: an address is no mention), the
 * term does not start with a space, holds no line break and is at most `MAX_MENTION_TERM` characters (it may hold spaces: names do), and it
 * never reaches into a mention already placed.
 */
export function mentionQuery(shown: Shown, caret: number): MentionQuery | null {
  const text = shown.text;
  for (let i = caret - 1; i >= 0 && caret - i <= MAX_MENTION_TERM + 1; i--) {
    if (insideMention(shown, i)) return null;
    const ch = text[i];
    if (ch === "\n" || ch === "\r") return null;
    if (ch !== "@") continue;
    if (i > 0 && !/\s/.test(text[i - 1])) return null;
    const term = text.slice(i + 1, caret);
    return /^\s/.test(term) ? null : { from: i, term };
  }
  return null;
}

/**
 * The canonical text with the `@term` (shown `[query.from, caret)`) replaced by the token for `id`, then a space unless one already follows, and
 * the canonical caret after that space. Null when it would not fit in `maxLength`.
 */
export function pickMention(shown: Shown, query: MentionQuery, caret: number, id: number, maxLength?: number): { canonical: string; caret: number } | null {
  return pickToken(shown, query, caret, `<@${id}>`, maxLength);
}

/** [pickMention] for any token (`<@team:5>`, `<@everyone>`, `<@watchers>`, `<@42>`). */
export function pickToken(shown: Shown, query: MentionQuery, caret: number, token: string, maxLength?: number): { canonical: string; caret: number } | null {
  const cFrom = toCanonicalOffset(shown, query.from);
  const cTo = toCanonicalOffset(shown, caret);
  const following = shown.canonical[cTo];
  const insert = following === " " || following === "\n" ? token : `${token} `;
  const canonical = shown.canonical.slice(0, cFrom) + insert + shown.canonical.slice(cTo);
  if (maxLength !== undefined && canonical.length > maxLength) return null;
  return { canonical, caret: cFrom + token.length + (following === " " ? 1 : insert.length - token.length) };
}
