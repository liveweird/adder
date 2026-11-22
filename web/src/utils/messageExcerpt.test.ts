import { describe, expect, test } from "vitest";
import { messageExcerpt, quoteExcerpt } from "./messageExcerpt";

const plain = { mentions: [], groupMentions: [] };

describe("messageExcerpt", () => {
  test("the first line alone for the collapsed strip, every line joined for a card", () => {
    const message = { ...plain, body: "Restart the replica first\n\nThen the primary" };
    expect(messageExcerpt(message, true)).toBe("Restart the replica first");
    expect(messageExcerpt(message)).toBe("Restart the replica first Then the primary");
  });

  test("a leading heading, quote, bullet or list number is dropped, the rest of the line is kept as written", () => {
    expect(messageExcerpt({ ...plain, body: "## Runbook for db-3" })).toBe("Runbook for db-3");
    expect(messageExcerpt({ ...plain, body: "> quoted\n- item\n3. third\n4) fourth" })).toBe("quoted item third fourth");
    expect(messageExcerpt({ ...plain, body: "> - nested bullet" })).toBe("nested bullet");
  });

  test("emphasis, code and links read as their bare text, and the underscores inside a word stay", () => {
    expect(messageExcerpt({ ...plain, body: "**Bold** and __strong__ and ~~gone~~ and *em* and _also em_ and `code`" })).toBe("Bold and strong and gone and em and also em and code");
    expect(messageExcerpt({ ...plain, body: "See [the runbook](https://example.test/a_b) and ![a chart](x.png)" })).toBe("See the runbook and a chart");
    expect(messageExcerpt({ ...plain, body: "[a] and [b](u) and [broken](x" })).toBe("[a] and b and [broken](x");
    expect(messageExcerpt({ ...plain, body: "set snake_case_name and 2 * 3 * 4" })).toBe("set snake_case_name and 2 * 3 * 4");
  });

  test("a code fence at the start is skipped, with what is inside it; a body that is only code quotes the code", () => {
    expect(messageExcerpt({ ...plain, body: "```sh\nrm -rf /tmp/cache\n```\nThen restart the pod" }, true)).toBe("Then restart the pod");
    expect(messageExcerpt({ ...plain, body: "~~~\nkubectl get pods\n~~~" })).toBe("kubectl get pods");
  });

  test("the first line with a letter or a digit wins: a rule or a lone emoji is skipped, unless nothing else is there", () => {
    expect(messageExcerpt({ ...plain, body: "---\n\u{1F525}\nThe real first line" }, true)).toBe("The real first line");
    expect(messageExcerpt({ ...plain, body: "<@5>" , mentions: [{ userId: 5, name: "Ann" }] }, true)).toBe("@Ann");
    expect(messageExcerpt({ ...plain, body: "---\n\u{1F525}" }, true)).toBe("---");
  });

  test("a listed mention reads as its name, an unlisted one stays the raw token", () => {
    const message = {
      body: "Ask <@5>, <@6>, <@team:9>, <@team:10>, <@watchers> and <@everyone>",
      mentions: [{ userId: 5, name: "Ann Owner" }],
      groupMentions: [
        { kind: "TEAM" as const, teamId: 9, teamHandle: "oncall", teamName: "On call" },
        { kind: "WATCHERS" as const, teamId: null, teamHandle: null, teamName: null },
      ],
    };
    expect(messageExcerpt(message)).toBe("Ask @Ann Owner, <@6>, @oncall, <@team:10>, @watchers and <@everyone>");
  });

  test("a team without a handle stays the raw token", () => {
    const message = { body: "<@team:9>", mentions: [], groupMentions: [{ kind: "TEAM" as const, teamId: 9, teamHandle: null, teamName: "On call" }] };
    expect(messageExcerpt(message)).toBe("<@team:9>");
  });

  test("a long body is cut at 280 graphemes with an ellipsis, never inside an emoji sequence", () => {
    const family = "\u{1F468}\u200D\u{1F469}\u200D\u{1F467}";
    const out = messageExcerpt({ ...plain, body: family.repeat(300) });
    expect(out).toBe(`${family.repeat(280)}…`);
    expect(out.endsWith("…")).toBe(true);
    expect(messageExcerpt({ ...plain, body: "x".repeat(280) })).toBe("x".repeat(280));
    expect(messageExcerpt({ ...plain, body: "x".repeat(281) })).toBe(`${"x".repeat(280)}…`);
  });

  test("a link label reads as its text, an unclosed or unlinked bracket stays as written, and a `[` before the label stays in it", () => {
    expect(messageExcerpt({ ...plain, body: "see [the docs](https://x.y/z) now" })).toBe("see the docs now");
    expect(messageExcerpt({ ...plain, body: "a [b] c [d](e) f [g" })).toBe("a [b] c d f [g");
    expect(messageExcerpt({ ...plain, body: "[x [y](u)" })).toBe("x [y");
    expect(messageExcerpt({ ...plain, body: "[a](" })).toBe("[a](");
  });

  test("a tombstone and a blank body quote nothing", () => {
    expect(messageExcerpt({ ...plain, body: null })).toBe("");
    expect(messageExcerpt({ ...plain, body: " \n " })).toBe("");
  });

  test("an unclosed emphasis or link over a whole 8,000-character body is read in a few milliseconds, and the excerpt is the ordinary one", () => {
    const hostile = ["*a ", "_a ", "**a ", "~~a ", "[a", "[](", " _a", "[a]x", "[a](", "[a]("];
    for (const unit of hostile) {
      const body = unit.repeat(Math.ceil(8_000 / unit.length)).slice(0, 8_000);
      // The best of a few runs, on a fresh object each (the excerpt is memoized per object): a loaded runner may delay one, not all.
      const best = Math.min(
        ...Array.from({ length: 5 }, () => {
          const started = performance.now();
          messageExcerpt({ ...plain, body });
          return performance.now() - started;
        }),
      );
      expect(best, `${JSON.stringify(unit)} x ${body.length}`).toBeLessThan(10);
    }
    // Nothing closes, so nothing is unwrapped: the text reads as written, to 280 graphemes.
    expect(messageExcerpt({ ...plain, body: "*a ".repeat(2_666) })).toBe(`${"*a ".repeat(2_666).slice(0, 280).trimEnd()}…`);
    // A pair that closes is still unwrapped past a hostile start, and after a long line the next one is still read.
    expect(messageExcerpt({ ...plain, body: `*a ${"b ".repeat(10)}**bold** and _em_\nnext` })).toBe(`*a ${"b ".repeat(10)}bold and em next`);
  });

  test("the excerpt is memoized per message object, the collapsed line and the whole one apart", () => {
    const message = { ...plain, body: "First line\nSecond line" };
    expect(messageExcerpt(message, true)).toBe("First line");
    expect(messageExcerpt(message)).toBe("First line Second line");
    // The same object answers from memory (the query cache hands the same one back until a refetch replaces it); a new object is read afresh.
    message.body = "Changed";
    expect(messageExcerpt(message, true)).toBe("First line");
    expect(messageExcerpt(message)).toBe("First line Second line");
    expect(messageExcerpt({ ...message })).toBe("Changed");
  });
});

describe("quoteExcerpt (1.3)", () => {
  test("is the message excerpt of the quoted text, with the names its own lists give", () => {
    const quote = { body: "**Disk** is full, ask <@7>", truncated: false, mentions: [{ userId: 7, name: "Ann Owner" }], groupMentions: [] };
    expect(quoteExcerpt(quote)).toBe("Disk is full, ask @Ann Owner");
  });

  test("ends in an ellipsis when the server cut the body, unless the text already ends in one", () => {
    expect(quoteExcerpt({ ...plain, body: "A long body cut at the word", truncated: true })).toBe("A long body cut at the word…");
    expect(quoteExcerpt({ ...plain, body: "Already trails off…", truncated: true })).toBe("Already trails off…");
    expect(quoteExcerpt({ ...plain, body: "Whole body", truncated: false })).toBe("Whole body");
  });

  test("reads markup as text, never as HTML, and a deleted quote (no body) as nothing", () => {
    expect(quoteExcerpt({ ...plain, body: "<img src=x onerror=alert(1)> [link](https://example.com)", truncated: false })).toBe("<img src=x onerror=alert(1)> link");
    expect(quoteExcerpt({ ...plain, body: null, truncated: false })).toBe("");
  });
});
