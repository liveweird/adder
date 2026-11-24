import { describe, expect, test } from "vitest";
import i18n from "../i18n";
import { ApiError } from "../api/http";
import { PROBLEM_TYPES } from "./problems";
import {
  detectStructuredSearch,
  isSearchable,
  parseSearchParams,
  queryProblem,
  scopeToClosed,
  searchErrorMessage,
  searchResultsPath,
  watchedThreadsPath,
  writeSearchParams,
} from "./searchParams";

describe("queryProblem: the server's rules, so a bad term never travels", () => {
  test("a searchable term has none", () => {
    for (const ok of ["ab", "  ab  ", "awaria bazy", "é2", "99", "日本", "x".repeat(100), "#db-outage", "a‍😀"]) expect(queryProblem(ok), ok).toBeNull();
  });

  test("under two characters after trimming, or with no letter or digit, is too short", () => {
    for (const bad of ["", " ", "a", "  a  ", "!!!", "<>", "- -", "😀😀"]) expect(queryProblem(bad), JSON.stringify(bad)).toBe("tooShort");
  });

  test("a code point counts once, however many UTF-16 units it takes", () => {
    expect(queryProblem("😀a")).toBeNull();
    expect(queryProblem("😀".repeat(100))).toBe("tooShort");
    expect(queryProblem("a" + "😀".repeat(99))).toBeNull();
    expect(queryProblem("a" + "😀".repeat(100))).toBe("tooLong");
  });

  test("over 100 characters is too long", () => {
    expect(queryProblem("x".repeat(101))).toBe("tooLong");
  });

  test("drafts with operators opt in to structured search", () => {
    for (const query of ['"server down"', "outage -test", "out*", "in:ops", "frmo:alex", "has:reaction:👍"]) {
      expect(detectStructuredSearch(query), query).toBe(true);
    }
    for (const query of ["outage", "alert-triage", "https://example.test", "a * b"]) {
      expect(detectStructuredSearch(query), query).toBe(false);
    }
  });

  test("structured queries have their own encoding and length checks", () => {
    expect(queryProblem("x".repeat(488) + " has:emoji:👩‍💻", true)).toBeNull();
    expect(queryProblem("😀".repeat(512), true)).toBeNull();
    expect(queryProblem("😀".repeat(513), true)).toBe("structuredTooLong");
    for (const forbidden of ["\u202e", "\u061c", "\u200e", "\u200f", "\u2028", "\u2029", "\t"]) {
      expect(queryProblem(`${forbidden}has:link`, true)).toBe("structuredInvalid");
    }
    expect(queryProblem(" ", true)).toBe("structuredInvalid");
  });

  test("a control character, or a hidden direction or separator, is invalid; an edge one is trimmed away first", () => {
    for (const bad of ["ab\u0001cd", "ab\u0000", "ab\tcd", "ab\ncd", "ab\u202ecd", "ab\u2066cd", "ab\u2028cd", "ab\u2029cd", "ab\u007fcd"]) expect(queryProblem(bad), JSON.stringify(bad)).toBe("invalid");
    expect(queryProblem("\tab\n")).toBeNull();
  });
});

describe("the search URL", () => {
  test("preserves a structured query and its zone through refinements and pagination", () => {
    const q = '"outage alert" from:#51 on:2026-10-25';
    const base = new URLSearchParams({ q, syntax: "structured", timeZone: "Europe/Warsaw", page: "3" });
    expect(parseSearchParams(base)).toMatchObject({ q, syntax: "structured", timeZone: "Europe/Warsaw", page: 3 });
    expect(parseSearchParams(writeSearchParams(base, { scope: "closed" }))).toMatchObject({
      q, syntax: "structured", timeZone: "Europe/Warsaw", scope: "closed", page: 1,
    });
    expect(writeSearchParams(base, { q: "", watching: true }).toString()).toBe("watching=1");
    expect(writeSearchParams(base, { q: "outage", syntax: undefined }).toString()).toBe("q=outage");
    expect(parseSearchParams(new URLSearchParams({ q, syntax: "invalid", timeZone: "UTC" }))).not.toHaveProperty("syntax");
    expect(parseSearchParams(new URLSearchParams({ q, syntax: "structured" }))).not.toHaveProperty("timeZone");
    expect(isSearchable(parseSearchParams(new URLSearchParams({ q: "x".repeat(120), syntax: "structured" })))).toBe(true);
  });
  test("parses the term (trimmed), the scope (default all) and the page (default 1); junk is the default", () => {
    expect(parseSearchParams(new URLSearchParams("q=%20outage%20&scope=closed&page=3"))).toEqual({ q: "outage", scope: "closed", watching: false, threadTag: [], page: 3 });
    expect(parseSearchParams(new URLSearchParams(""))).toEqual({ q: "", scope: "all", watching: false, threadTag: [], page: 1 });
    for (const page of ["0", "-1", "2x", "1e3", "9999999999", ""]) expect(parseSearchParams(new URLSearchParams({ page })).page, page).toBe(1);
    expect(parseSearchParams(new URLSearchParams("scope=nonsense")).scope).toBe("all");
  });

  test("a write omits the defaults, keeps other keys, and a new term or scope goes back to page 1", () => {
    const base = new URLSearchParams("q=old&scope=open&page=4&message=9");
    expect(writeSearchParams(base, { q: "new" }).toString()).toBe("message=9&q=new&scope=open");
    expect(writeSearchParams(base, { scope: "all" }).toString()).toBe("message=9&q=old");
    expect(writeSearchParams(base, { page: 5 }).toString()).toBe("message=9&q=old&scope=open&page=5");
    expect(writeSearchParams(base, { page: 1 }).toString()).toBe("message=9&q=old&scope=open");
    expect(writeSearchParams(new URLSearchParams("q=a"), { q: "" }).toString()).toBe("");
  });

  test("the watching key is `1` and nothing else; a write keeps it, omits it when off, and goes back to page 1", () => {
    expect(parseSearchParams(new URLSearchParams("watching=1")).watching).toBe(true);
    for (const junk of ["true", "0", "me", "", "2"]) expect(parseSearchParams(new URLSearchParams({ watching: junk })).watching, junk).toBe(false);
    const base = new URLSearchParams("q=old&scope=open&watching=1&page=4&message=9");
    expect(writeSearchParams(base, { q: "new" }).toString()).toBe("message=9&q=new&scope=open&watching=1");
    expect(writeSearchParams(base, { watching: false }).toString()).toBe("message=9&q=old&scope=open");
    expect(writeSearchParams(new URLSearchParams("q=old&page=3"), { watching: true }).toString()).toBe("q=old&watching=1");
    expect(writeSearchParams(base, { page: 5 }).toString()).toBe("message=9&q=old&scope=open&watching=1&page=5");
  });

  test("a state is searchable with a term the server accepts, or with no term while watching or tagged; a given bad term never is", () => {
    expect(isSearchable({ q: "outage", watching: false, threadTag: [] })).toBe(true);
    expect(isSearchable({ q: "outage", watching: true, threadTag: [] })).toBe(true);
    expect(isSearchable({ q: "", watching: false, threadTag: [] })).toBe(false);
    expect(isSearchable({ q: "", watching: true, threadTag: [] })).toBe(true);
    expect(isSearchable({ q: "", watching: false, threadTag: ["security"] })).toBe(true);
    expect(isSearchable({ q: "outage", watching: false, threadTag: ["security"] })).toBe(true);
    expect(isSearchable({ q: "a", watching: true, threadTag: [] })).toBe(false);
    expect(isSearchable({ q: "a", watching: false, threadTag: ["security"] })).toBe(false);
    expect(isSearchable({ q: "!!!", watching: false, threadTag: [] })).toBe(false);
  });

  test("the threadTag key repeats, is lower-cased, distinct and bounded like the thread list's; junk values are dropped", () => {
    expect(parseSearchParams(new URLSearchParams("threadTag=Security&threadTag=question&threadTag=SECURITY")).threadTag).toEqual(["security", "question"]);
    expect(parseSearchParams(new URLSearchParams("threadTag=-bad&threadTag=&threadTag=ok&threadTag=" + "x".repeat(41))).threadTag).toEqual(["ok"]);
    const many = Array.from({ length: 25 }, (_, index) => `threadTag=tag-${index}`).join("&");
    expect(parseSearchParams(new URLSearchParams(many)).threadTag).toHaveLength(20);
  });

  test("a write keeps the tags, replaces them as a set, omits them when none, and a change goes back to page 1", () => {
    const base = new URLSearchParams("q=old&threadTag=security&threadTag=question&page=4&message=9");
    expect(writeSearchParams(base, { q: "new" }).toString()).toBe("message=9&q=new&threadTag=security&threadTag=question");
    expect(writeSearchParams(base, { threadTag: ["decision"] }).toString()).toBe("message=9&q=old&threadTag=decision");
    expect(writeSearchParams(base, { threadTag: [] }).toString()).toBe("message=9&q=old");
    expect(writeSearchParams(base, { page: 5 }).toString()).toBe("message=9&q=old&threadTag=security&threadTag=question&page=5");
    expect(writeSearchParams(new URLSearchParams(), { watching: true, threadTag: ["security"] }).toString()).toBe("watching=1&threadTag=security");
  });

  test("the palette's watched-threads link is the search page with the toggle on and no term", () => {
    expect(watchedThreadsPath("/search")).toBe("/search?watching=1");
    expect(watchedThreadsPath("/rooms/7/search")).toBe("/rooms/7/search?watching=1");
  });

  test("the scope maps to the closed filter, and a palette link carries the trimmed, encoded term", () => {
    expect(scopeToClosed("open")).toBe(false);
    expect(scopeToClosed("closed")).toBe(true);
    expect(scopeToClosed("all")).toBeUndefined();
    expect(searchResultsPath("/search", "  awaria bazy ")).toBe("/search?q=awaria+bazy");
    expect(searchResultsPath("/rooms/7/search", "a&b=c")).toBe("/rooms/7/search?q=a%26b%3Dc");
  });
});

describe("searchErrorMessage", () => {
  const t = i18n.t.bind(i18n);
  const problem = (status: number, type?: string) => new ApiError(status, type === undefined ? null : { type, status });

  test("each 429 has its own words", () => {
    expect(searchErrorMessage(problem(429, PROBLEM_TYPES.searchTimeout), t)).toBe("The search took too long. Narrow it down with a more specific word.");
    expect(searchErrorMessage(problem(429, PROBLEM_TYPES.searchBusy), t)).toBe("The server is busy. Try again in a moment.");
    expect(searchErrorMessage(problem(429, "about:blank"), t)).toBe("Too many searches. Wait a moment and try again.");
    expect(searchErrorMessage(problem(429), t)).toBe("Too many searches. Wait a moment and try again.");
  });

  test("a 404 says the room is gone, other statuses name the status, and a network failure says so", () => {
    expect(searchErrorMessage(problem(404), t)).toBe("This room no longer exists, or you are no longer a member of it.");
    expect(searchErrorMessage(problem(500), t)).toBe("Search failed (500)");
    expect(searchErrorMessage(new TypeError("Failed to fetch"), t)).toBe("Network error. Check your connection and try again.");
    expect(searchErrorMessage(new DOMException("timeout", "TimeoutError"), t)).toBe("The request timed out. Try again.");
  });
});
