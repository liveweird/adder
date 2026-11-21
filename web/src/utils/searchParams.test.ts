import { describe, expect, test } from "vitest";
import i18n from "../i18n";
import { ApiError } from "../api/http";
import { PROBLEM_TYPES } from "./problems";
import {
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

  test("a control character, or a hidden direction or separator, is invalid; an edge one is trimmed away first", () => {
    for (const bad of ["ab\u0001cd", "ab\u0000", "ab\tcd", "ab\ncd", "ab\u202ecd", "ab\u2066cd", "ab\u2028cd", "ab\u2029cd", "ab\u007fcd"]) expect(queryProblem(bad), JSON.stringify(bad)).toBe("invalid");
    expect(queryProblem("\tab\n")).toBeNull();
  });
});

describe("the search URL", () => {
  test("parses the term (trimmed), the scope (default all) and the page (default 1); junk is the default", () => {
    expect(parseSearchParams(new URLSearchParams("q=%20outage%20&scope=closed&page=3"))).toEqual({ q: "outage", scope: "closed", watching: false, page: 3 });
    expect(parseSearchParams(new URLSearchParams(""))).toEqual({ q: "", scope: "all", watching: false, page: 1 });
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

  test("a state is searchable with a term the server accepts, or with no term while watching; a given bad term never is", () => {
    expect(isSearchable({ q: "outage", watching: false })).toBe(true);
    expect(isSearchable({ q: "outage", watching: true })).toBe(true);
    expect(isSearchable({ q: "", watching: false })).toBe(false);
    expect(isSearchable({ q: "", watching: true })).toBe(true);
    expect(isSearchable({ q: "a", watching: true })).toBe(false);
    expect(isSearchable({ q: "!!!", watching: false })).toBe(false);
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
