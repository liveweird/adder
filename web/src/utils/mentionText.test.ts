import { describe, expect, test } from "vitest";
import { editCanonical, mentionQuery, pickMention, toShown, toShownOffset } from "./mentionText";

const NAMES = new Map<number, string>([
  [5, "Ann Owner"],
  [6, "Bob"],
  [7, "Ann Owner"], // a namesake: the same name, another person
  [8, "<b>*x* @team"],
]);
const nameOf = (id: number) => NAMES.get(id);

/** The box after the user replaced shown `[from, to)` with `typed`, the caret right after it (as a browser reports it). */
function edit(canonical: string, from: number, to: number, typed: string, maxLength?: number) {
  const shown = toShown(canonical, nameOf);
  const next = shown.text.slice(0, from) + typed + shown.text.slice(to);
  return editCanonical(shown, next, from + typed.length, maxLength);
}

describe("toShown", () => {
  test.each([
    ["", ""],
    ["plain text", "plain text"],
    ["Hi <@5>, meet <@6>.", "Hi @Ann Owner, meet @Bob."],
    ["<@5><@6>", "@Ann Owner@Bob"],
    ["a token whose name is not known stays the token: <@99>", "a token whose name is not known stays the token: <@99>"],
    ["<@7> and <@5> are two people", "@Ann Owner and @Ann Owner are two people"],
    ["<@8>", "@<b>*x* @team"],
  ])("%j shows as %j", (canonical, text) => {
    expect(toShown(canonical, nameOf).text).toBe(text);
  });

  test("the pieces keep ids apart even for one name", () => {
    const shown = toShown("<@5> <@7>", nameOf);
    expect(shown.pieces.filter((piece) => piece.id !== undefined).map((piece) => piece.id)).toEqual([5, 7]);
  });

  test("offsets map from the canonical text to the shown one across a mention", () => {
    const shown = toShown("Hi <@5>!", nameOf); // "Hi @Ann Owner!"
    expect(toShownOffset(shown, 3)).toBe(3);
    expect(toShownOffset(shown, 5)).toBe(13); // inside the token: the end of the name
    expect(toShownOffset(shown, 7)).toBe(13);
    expect(toShownOffset(shown, 8)).toBe(14);
    expect(toShownOffset(shown, 99)).toBe(14);
  });
});

describe("editCanonical", () => {
  test("typing before, between and after mentions leaves the tokens alone", () => {
    // shown "@Ann Owner @Bob": the first name is 0-10, the space 10, the second 11-15
    expect(edit("<@5> <@6>", 0, 0, "x").canonical).toBe("x<@5> <@6>");
    expect(edit("<@5> <@6>", 10, 10, "x").canonical).toBe("<@5>x <@6>");
    expect(edit("<@5> <@6>", 11, 11, "x").canonical).toBe("<@5> x<@6>");
    expect(edit("<@5> <@6>", 15, 15, "x").canonical).toBe("<@5> <@6>x");
  });

  test("the canonical caret sits after what was typed", () => {
    expect(edit("ab <@5>", 1, 1, "XY").caret).toBe(3);
  });

  test("typing inside a name removes the whole token and keeps the typed text", () => {
    expect(edit("a <@5> b", 5, 5, "z").canonical).toBe("a z b");
  });

  test("deleting one character of a name removes the whole token", () => {
    // shown "a @Ann Owner b": the name is 2-12
    expect(edit("a <@5> b", 2, 3, "").canonical).toBe("a  b");
    expect(edit("a <@5> b", 11, 12, "").canonical).toBe("a  b");
  });

  test("deleting the plain text next to a mention leaves it", () => {
    expect(edit("a <@5> b", 1, 2, "").canonical).toBe("a<@5> b");
    expect(edit("a <@5> b", 12, 13, "").canonical).toBe("a <@5>b");
  });

  test("a paste over a selection that spans two mentions removes both", () => {
    // shown "x @Ann Owner and @Bob y": from inside the first name to inside the second
    const result = edit("x <@5> and <@6> y", 5, 19, "|");
    expect(result.canonical).toBe("x | y");
  });

  test("a paste that replaces a mention whole", () => {
    expect(edit("<@5>", 0, 10, "pasted").canonical).toBe("pasted");
  });

  test("two members with one name stay two ids", () => {
    expect(edit("<@5> <@7>", 11, 11, "!").canonical).toBe("<@5> !<@7>");
    // deleting the second namesake takes the second token, not the first
    expect(edit("<@5> <@7>", 11, 21, "").canonical).toBe("<@5> ");
  });

  test("a name that holds < or @ is an ordinary name", () => {
    expect(edit("a <@8> b", 0, 0, ">").canonical).toBe(">a <@8> b");
    expect(edit("a <@8> b", 2, 14, "").canonical).toBe("a  b");
  });

  test("a token whose name is not known is plain text and edits like it", () => {
    expect(edit("a <@99> b", 3, 4, "").canonical).toBe("a <99> b");
  });

  test("without a caret, the common prefix and suffix find the edit (the picker's splice)", () => {
    const shown = toShown("a <@5> b", nameOf); // "a @Ann Owner b"
    expect(editCanonical(shown, "a @Ann Owner😀 b", null).canonical).toBe("a <@5>😀 b");
    expect(editCanonical(shown, "😀a @Ann Owner b", null).canonical).toBe("😀a <@5> b");
  });

  test("an unusable caret falls back to the common prefix and suffix", () => {
    const shown = toShown("hello", nameOf);
    expect(editCanonical(shown, "heXllo", 1).canonical).toBe("heXllo");
    expect(editCanonical(shown, "hello", 99).canonical).toBe("hello");
  });

  test("a repeated character is placed by the caret, not guessed", () => {
    // shown "ab @Bob aa": delete the second "a" (index 9..10) - the first stays whichever the diff would pick
    expect(edit("ab <@6> aa", 9, 10, "").canonical).toBe("ab <@6> a");
  });

  test("the inserted text is cut to what still fits in the stored text", () => {
    expect(edit("abc", 3, 3, "defgh", 6).canonical).toBe("abcdef");
    // a token is 4 characters stored and 4 shown for "Bob"; for a long name the shown text is longer, the limit still counts the stored one
    expect(edit("<@5>", 10, 10, "xyz", 6).canonical).toBe("<@5>xy");
    // half an emoji is never left
    expect(edit("abc", 3, 3, "😀", 4).canonical).toBe("abc");
  });
});

describe("mentionQuery", () => {
  const query = (canonical: string, caret: number) => mentionQuery(toShown(canonical, nameOf), caret);

  test("an @ at the start of the text or of a word opens a query with what follows it", () => {
    expect(query("@na", 3)).toEqual({ from: 0, term: "na" });
    expect(query("hi @na", 6)).toEqual({ from: 3, term: "na" });
    expect(query("hi\n@", 4)).toEqual({ from: 3, term: "" });
    expect(query("@Ann O", 6)).toEqual({ from: 0, term: "Ann O" });
  });

  test("an address, a space after the @ and a line break are no query", () => {
    expect(query("me@host", 7)).toBeNull();
    expect(query("@ x", 3)).toBeNull();
    expect(query("@a\nb", 4)).toBeNull();
    expect(query("no at sign", 10)).toBeNull();
  });

  test("a term past 50 characters is no query", () => {
    expect(query(`@${"a".repeat(50)}`, 51)).toEqual({ from: 0, term: "a".repeat(50) });
    expect(query(`@${"a".repeat(51)}`, 52)).toBeNull();
  });

  test("the caret in or right after a mention placed earlier opens nothing", () => {
    expect(query("<@5>", 10)).toBeNull(); // "@Ann Owner|"
    expect(query("<@5>", 4)).toBeNull();
    expect(query("<@5> @b", 13)).toEqual({ from: 11, term: "b" });
  });
});

describe("pickMention", () => {
  const shown = (canonical: string) => toShown(canonical, nameOf);

  test("replaces the @term by the token and a space, the caret after the space", () => {
    expect(pickMention(shown("hi @an"), { from: 3, term: "an" }, 6, 5)).toEqual({ canonical: "hi <@5> ", caret: 8 });
  });

  test("keeps the text after the caret, and an existing space is not doubled", () => {
    expect(pickMention(shown("@a tail"), { from: 0, term: "a" }, 2, 6)).toEqual({ canonical: "<@6> tail", caret: 5 });
    expect(pickMention(shown("@a\nnext"), { from: 0, term: "a" }, 2, 6)).toEqual({ canonical: "<@6>\nnext", caret: 4 });
    expect(pickMention(shown("@ax"), { from: 0, term: "a" }, 2, 6)).toEqual({ canonical: "<@6> x", caret: 5 });
  });

  test("maps the offsets through mentions placed earlier", () => {
    expect(pickMention(shown("<@5> @b"), { from: 11, term: "b" }, 13, 6)).toEqual({ canonical: "<@5> <@6> ", caret: 10 });
  });

  test("says no when the token would not fit", () => {
    expect(pickMention(shown("@a"), { from: 0, term: "a" }, 2, 6, 3)).toBeNull();
  });
});
