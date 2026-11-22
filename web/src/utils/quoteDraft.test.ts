import { describe, expect, test } from "vitest";
import type { TFunction } from "i18next";
import type { QuoteDraft } from "../hooks/useThreadDrafts";
import { makeMessage } from "../test/messageFixtures";
import { blankQuote, quoteDraftExcerpt, reconcileQuote } from "./quoteDraft";

const quote: QuoteDraft = { id: 31, authorId: 4, authorName: "Ann Author", body: "Restart <@5>", mentions: [{ userId: 5, name: "Bob" }], groupMentions: [] };
const t = ((key: string) => key) as unknown as TFunction;

describe("quoteDraft", () => {
  test("blanking keeps the author and the id and drops every word of the text", () => {
    expect(blankQuote(quote)).toEqual({ id: 31, authorId: 4, authorName: "Ann Author", body: "", mentions: [], groupMentions: [], unavailable: true });
  });

  test("a deleted or body-less loaded message blanks the quote; a blank quote never comes back", () => {
    expect(reconcileQuote(quote, makeMessage({ id: 31, deleted: true, body: null })).unavailable).toBe(true);
    expect(reconcileQuote(quote, makeMessage({ id: 31, deleted: false, body: null })).unavailable).toBe(true);
    const blank = blankQuote(quote);
    expect(reconcileQuote(blank, makeMessage({ id: 31, body: "Back again" }))).toBe(blank);
  });

  test("an edited loaded message refreshes the text and its people; the same text, or no loaded message, is the same object", () => {
    const edited = makeMessage({ id: 31, authorName: "Ann A.", body: "Restart db-4", mentions: [], editedAt: 1767261600000 });
    expect(reconcileQuote(quote, edited)).toEqual({ ...quote, authorName: "Ann A.", body: "Restart db-4", mentions: [], groupMentions: [] });
    expect(reconcileQuote(quote, makeMessage({ id: 31, body: quote.body }))).toBe(quote);
    expect(reconcileQuote(quote, undefined)).toBe(quote);
  });

  test("the excerpt is the text's, or the unavailable words once blank", () => {
    expect(quoteDraftExcerpt(quote, t)).toBe("Restart @Bob");
    expect(quoteDraftExcerpt(blankQuote(quote), t)).toBe("threads.quote.unavailable");
  });
});
