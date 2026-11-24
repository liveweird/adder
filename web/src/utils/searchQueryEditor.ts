const SEARCH_OPERATORS = [
  "in",
  "from",
  "with",
  "owner",
  "on",
  "before",
  "after",
  "during",
  "has",
] as const;

/** Kept outside `searchAllKey` so result invalidation never spends assistant lookup budgets. */
export const SEARCH_ASSISTANT_KEY = ["search-assistant"] as const;

export type SearchOperator = (typeof SEARCH_OPERATORS)[number];

export type SearchTokenContext = {
  start: number;
  end: number;
  text: string;
  operator: SearchOperator | null;
  value: string;
};

export type SearchOperatorSuggestion = {
  id: "in" | "from" | "with" | "owner" | "on" | "before" | "after" | "during" | "pin" | "link" | "reactionAny" | "reaction" | "emoji";
  insert: string;
};

const OPERATOR_SUGGESTIONS: readonly SearchOperatorSuggestion[] = [
  { id: "in", insert: "in:" },
  { id: "from", insert: "from:" },
  { id: "with", insert: "with:" },
  { id: "owner", insert: "owner:" },
  { id: "on", insert: "on:" },
  { id: "before", insert: "before:" },
  { id: "after", insert: "after:" },
  { id: "during", insert: "during:" },
  { id: "pin", insert: "has:pin" },
  { id: "link", insert: "has:link" },
  { id: "reactionAny", insert: "has:reaction" },
  { id: "reaction", insert: "has:reaction:" },
  { id: "emoji", insert: "has:emoji:" },
];

/** A bounded human-name lookup. Stable `#id` operands and over-limit drafts never spend a directory request. */
export function searchLookupTerm(value: string): string | null {
  const term = value.replace(/^"/u, "").replace(/"$/u, "").trim();
  const length = [...term].length;
  return length >= 2 && length <= 100 && !term.startsWith("#") ? term : null;
}

function tokenBounds(query: string, caret: number): { start: number; end: number } {
  const position = Math.max(0, Math.min(caret, query.length));
  let quoted = false;
  let start = 0;
  for (let index = 0; index < position; index += 1) {
    if (isUnescapedQuote(query, index)) quoted = !quoted;
    if (!quoted && /\s/u.test(query[index])) start = index + 1;
  }
  quoted = false;
  for (let index = start; index < position; index += 1) {
    if (isUnescapedQuote(query, index)) quoted = !quoted;
  }
  let end = position;
  while (end < query.length) {
    if (isUnescapedQuote(query, end)) quoted = !quoted;
    if (!quoted && /\s/u.test(query[end])) break;
    end += 1;
  }
  return { start, end };
}

function isUnescapedQuote(text: string, index: number): boolean {
  if (text[index] !== '"') return false;
  let slashes = 0;
  for (let before = index - 1; before >= 0 && text[before] === "\\"; before -= 1) slashes += 1;
  return slashes % 2 === 0;
}

export function contextualSearchToken(query: string, caret: number): SearchTokenContext {
  const { start, end } = tokenBounds(query, caret);
  const text = query.slice(start, end);
  const separator = text.indexOf(":");
  const candidate = separator === -1 ? "" : text.slice(0, separator).toLowerCase();
  const operator = SEARCH_OPERATORS.find((item) => item === candidate) ?? null;
  return {
    start,
    end,
    text,
    operator,
    value: separator === -1 ? "" : text.slice(separator + 1),
  };
}

export function insertSearchToken(query: string, start: number, end: number, replacement: string): { query: string; caret: number } {
  const prefix = query.slice(0, start);
  const suffix = query.slice(end);
  const leading = start === end && prefix !== "" && !/\s$/u.test(prefix) ? " " : "";
  const trailing = suffix !== "" && !/^\s/u.test(suffix) ? " " : "";
  const inserted = `${leading}${replacement}${trailing}`;
  return {
    query: `${prefix}${inserted}${suffix}`,
    caret: prefix.length + leading.length + replacement.length,
  };
}

export function searchOperatorSuggestions(fragment: string): SearchOperatorSuggestion[] {
  const normalized = fragment.toLowerCase();
  if (normalized.startsWith("has:")) {
    const value = normalized.slice(4);
    return OPERATOR_SUGGESTIONS.filter((item) => item.insert.startsWith(`has:${value}`));
  }
  if (normalized.includes(":")) return [];
  return OPERATOR_SUGGESTIONS.filter((item) => !item.insert.startsWith("has:") && item.insert.startsWith(normalized));
}

function editDistanceAtMostOne(left: string, right: string): boolean {
  if (left === right || Math.abs(left.length - right.length) > 1) return left === right;
  if (left.length === right.length) {
    const different = [...left].filter((character, index) => character !== right[index]);
    if (different.length === 1) return true;
    for (let index = 0; index < left.length - 1; index += 1) {
      if (left[index] === right[index + 1] && left[index + 1] === right[index]) {
        return `${left.slice(0, index)}${left[index + 1]}${left[index]}${left.slice(index + 2)}` === right;
      }
    }
    return false;
  }
  const [shorter, longer] = left.length < right.length ? [left, right] : [right, left];
  for (let index = 0; index < longer.length; index += 1) {
    if (`${longer.slice(0, index)}${longer.slice(index + 1)}` === shorter) return true;
  }
  return false;
}

export function operatorCorrection(query: string, caret: number): {
  start: number;
  end: number;
  replacement: string;
  operator: SearchOperator;
} | null {
  const token = contextualSearchToken(query, caret);
  const separator = token.text.indexOf(":");
  if (separator < 1 || token.operator !== null) return null;
  const candidate = token.text.slice(0, separator).toLowerCase();
  const matches = SEARCH_OPERATORS.filter((operator) => editDistanceAtMostOne(candidate, operator));
  if (matches.length !== 1) return null;
  const [operator] = matches;
  return {
    start: token.start,
    end: token.end,
    replacement: `${operator}${token.text.slice(separator)}`,
    operator,
  };
}
