import { describe, expect, test } from "vitest";
import { pinExcerpt } from "./pinExcerpt";

const plain = { mentions: [], groupMentions: [] };

describe("pinExcerpt", () => {
  test("the first line alone for the collapsed strip, every line joined for a card", () => {
    const message = { ...plain, body: "Restart the replica first\n\nThen the primary" };
    expect(pinExcerpt(message, true)).toBe("Restart the replica first");
    expect(pinExcerpt(message)).toBe("Restart the replica first Then the primary");
  });

  test("a leading heading, quote, bullet or list number is dropped, the rest of the line is kept as written", () => {
    expect(pinExcerpt({ ...plain, body: "## Runbook for db-3" })).toBe("Runbook for db-3");
    expect(pinExcerpt({ ...plain, body: "> quoted\n- item\n3. third\n4) fourth" })).toBe("quoted item third fourth");
    expect(pinExcerpt({ ...plain, body: "> - nested bullet" })).toBe("nested bullet");
  });

  test("emphasis, code and links read as their bare text, and the underscores inside a word stay", () => {
    expect(pinExcerpt({ ...plain, body: "**Bold** and __strong__ and ~~gone~~ and *em* and _also em_ and `code`" })).toBe("Bold and strong and gone and em and also em and code");
    expect(pinExcerpt({ ...plain, body: "See [the runbook](https://example.test/a_b) and ![a chart](x.png)" })).toBe("See the runbook and a chart");
    expect(pinExcerpt({ ...plain, body: "[a] and [b](u) and [broken](x" })).toBe("[a] and b and [broken](x");
    expect(pinExcerpt({ ...plain, body: "set snake_case_name and 2 * 3 * 4" })).toBe("set snake_case_name and 2 * 3 * 4");
  });

  test("a code fence at the start is skipped, with what is inside it; a body that is only code quotes the code", () => {
    expect(pinExcerpt({ ...plain, body: "```sh\nrm -rf /tmp/cache\n```\nThen restart the pod" }, true)).toBe("Then restart the pod");
    expect(pinExcerpt({ ...plain, body: "~~~\nkubectl get pods\n~~~" })).toBe("kubectl get pods");
  });

  test("the first line with a letter or a digit wins: a rule or a lone emoji is skipped, unless nothing else is there", () => {
    expect(pinExcerpt({ ...plain, body: "---\n\u{1F525}\nThe real first line" }, true)).toBe("The real first line");
    expect(pinExcerpt({ ...plain, body: "<@5>" , mentions: [{ userId: 5, name: "Ann" }] }, true)).toBe("@Ann");
    expect(pinExcerpt({ ...plain, body: "---\n\u{1F525}" }, true)).toBe("---");
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
    expect(pinExcerpt(message)).toBe("Ask @Ann Owner, <@6>, @oncall, <@team:10>, @watchers and <@everyone>");
  });

  test("a team without a handle stays the raw token", () => {
    const message = { body: "<@team:9>", mentions: [], groupMentions: [{ kind: "TEAM" as const, teamId: 9, teamHandle: null, teamName: "On call" }] };
    expect(pinExcerpt(message)).toBe("<@team:9>");
  });

  test("a long body is cut at 280 graphemes with an ellipsis, never inside an emoji sequence", () => {
    const family = "\u{1F468}\u200D\u{1F469}\u200D\u{1F467}";
    const out = pinExcerpt({ ...plain, body: family.repeat(300) });
    expect(out).toBe(`${family.repeat(280)}…`);
    expect(out.endsWith("…")).toBe(true);
    expect(pinExcerpt({ ...plain, body: "x".repeat(280) })).toBe("x".repeat(280));
    expect(pinExcerpt({ ...plain, body: "x".repeat(281) })).toBe(`${"x".repeat(280)}…`);
  });

  test("a link label reads as its text, an unclosed or unlinked bracket stays as written, and a `[` before the label stays in it", () => {
    expect(pinExcerpt({ ...plain, body: "see [the docs](https://x.y/z) now" })).toBe("see the docs now");
    expect(pinExcerpt({ ...plain, body: "a [b] c [d](e) f [g" })).toBe("a [b] c d f [g");
    expect(pinExcerpt({ ...plain, body: "[x [y](u)" })).toBe("x [y");
    expect(pinExcerpt({ ...plain, body: "[a](" })).toBe("[a](");
  });

  test("a tombstone and a blank body quote nothing", () => {
    expect(pinExcerpt({ ...plain, body: null })).toBe("");
    expect(pinExcerpt({ ...plain, body: " \n " })).toBe("");
  });

  test("an unclosed emphasis or link over a whole 8,000-character body is read in a few milliseconds, and the excerpt is the ordinary one", () => {
    const hostile = ["*a ", "_a ", "**a ", "~~a ", "[a", "[](", " _a", "[a]x", "[a](", "[a]("];
    for (const unit of hostile) {
      const body = unit.repeat(Math.ceil(8_000 / unit.length)).slice(0, 8_000);
      // The best of a few runs, on a fresh object each (the excerpt is memoized per object): a loaded runner may delay one, not all.
      const best = Math.min(
        ...Array.from({ length: 5 }, () => {
          const started = performance.now();
          pinExcerpt({ ...plain, body });
          return performance.now() - started;
        }),
      );
      expect(best, `${JSON.stringify(unit)} x ${body.length}`).toBeLessThan(10);
    }
    // Nothing closes, so nothing is unwrapped: the text reads as written, to 280 graphemes.
    expect(pinExcerpt({ ...plain, body: "*a ".repeat(2_666) })).toBe(`${"*a ".repeat(2_666).slice(0, 280).trimEnd()}…`);
    // A pair that closes is still unwrapped past a hostile start, and after a long line the next one is still read.
    expect(pinExcerpt({ ...plain, body: `*a ${"b ".repeat(10)}**bold** and _em_\nnext` })).toBe(`*a ${"b ".repeat(10)}bold and em next`);
  });

  test("the excerpt is memoized per message object, the collapsed line and the whole one apart", () => {
    const message = { ...plain, body: "First line\nSecond line" };
    expect(pinExcerpt(message, true)).toBe("First line");
    expect(pinExcerpt(message)).toBe("First line Second line");
    // The same object answers from memory (the query cache hands the same one back until a refetch replaces it); a new object is read afresh.
    message.body = "Changed";
    expect(pinExcerpt(message, true)).toBe("First line");
    expect(pinExcerpt(message)).toBe("First line Second line");
    expect(pinExcerpt({ ...message })).toBe("Changed");
  });
});
