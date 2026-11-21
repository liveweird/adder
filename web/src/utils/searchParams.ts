/**
 * The search pages' state as URL query parameters (`q`, `scope`, `watching`, `page`), the pure half; `hooks/useSearchUrl.ts` is the hook. Junk reads as
 * the default and a write omits defaults, so a shared link means the same search for everyone. `scope` is the thread list's vocabulary
 * (`threadListParams.ts`) but its default is every thread: a search looks across open and closed ones unless told otherwise.
 */

import type { TFunction } from "i18next";
import { ApiError, isTimeoutError } from "../api/http";
import { PROBLEM_TYPES, isProblem } from "./problems";
import { SCOPE_FILTER, SCOPES, type Scope } from "./threadListParams";

/** The server's page size for a search (a hit carries up to three snippets, so the shared 100 is not offered). */
export const SEARCH_PAGE_SIZE = 20;
/** The server's limits on `q`, in code points after trimming. */
const MIN_QUERY_LENGTH = 2;
const MAX_QUERY_LENGTH = 100;

const DEFAULT_SEARCH_SCOPE: Scope = "all";

export type QueryProblem = "tooShort" | "tooLong" | "invalid";

/**
 * Why `raw` cannot be searched, or null. The server's `validateSearchQuery`, mirrored so a bad term never travels (a 400 never echoes it, so the
 * client is the only place that can say what is wrong): no control or hidden direction character, 2 to 100 code points after trimming, and at least
 * one letter or digit (`!!!` has no word and could match nothing).
 */
export function queryProblem(raw: string): QueryProblem | null {
  const q = raw.trim();
  if (/[\p{Cc}\u202A-\u202E\u2066-\u2069\u2028\u2029]/u.test(q)) return "invalid";
  const length = [...q].length;
  if (length > MAX_QUERY_LENGTH) return "tooLong";
  if (length < MIN_QUERY_LENGTH || !/[\p{L}\p{Nd}]/u.test(q)) return "tooShort";
  return null;
}

export interface SearchState {
  /** The term as the URL spells it (trimmed; "" when there is none). It may still be unsearchable: see [queryProblem]. */
  q: string;
  scope: Scope;
  /** The "Threads I watch" toggle (`watching=1`): only the threads the caller watches; with no term it lists them all. */
  watching: boolean;
  /** 1-based. */
  page: number;
}

/** A positive integer of at most nine digits; anything else (`0`, `-1`, `2x`, `1e3`) is junk. */
const PAGE = /^[1-9]\d{0,8}$/;

export function parseSearchParams(search: URLSearchParams): SearchState {
  const scope = search.get("scope");
  const page = search.get("page");
  return {
    q: (search.get("q") ?? "").trim(),
    scope: SCOPES.find((value) => value === scope) ?? DEFAULT_SEARCH_SCOPE,
    watching: search.get("watching") === "1",
    page: page !== null && PAGE.test(page) ? Number(page) : 1,
  };
}

/**
 * `base` with the search's keys replaced by `patch` laid over what `base` already says. A change of the term or the scope goes back to
 * page 1 in the same write (the old page would be read for a different search); a patch that names the page keeps it.
 */
export function writeSearchParams(base: URLSearchParams, patch: Partial<SearchState>): URLSearchParams {
  const next = { ...parseSearchParams(base), ...patch };
  if (patch.page === undefined) next.page = 1;
  const out = new URLSearchParams(base);
  for (const key of ["q", "scope", "watching", "page"]) out.delete(key);
  if (next.q !== "") out.set("q", next.q);
  if (next.scope !== DEFAULT_SEARCH_SCOPE) out.set("scope", next.scope);
  if (next.watching) out.set("watching", "1");
  if (next.page !== 1) out.set("page", String(next.page));
  return out;
}

/** `true` / `false` for the Closed / Open scopes, undefined for every thread. */
export const scopeToClosed = (scope: Scope): boolean | undefined => SCOPE_FILTER[scope];

/** A link to a search page for `q` (the palette's "Search for ..." actions): `path` is `/search` or a room's search route. */
export const searchResultsPath = (path: string, q: string) => `${path}?${writeSearchParams(new URLSearchParams(), { q: q.trim() })}`;

/** The palette's "Threads I watch" link: the search page with the toggle on and no term (`/search?watching=1`). */
export const watchedThreadsPath = (path: string) => `${path}?${writeSearchParams(new URLSearchParams(), { watching: true })}`;

/**
 * Whether the state can be sent: a term the server accepts, or, with the "Threads I watch" toggle on, no term at all (the server makes `q`
 * optional ONLY with `watching=true`; a term that is given is validated as ever).
 */
export const isSearchable = ({ q, watching }: Pick<SearchState, "q" | "watching">): boolean =>
  q === "" ? watching : queryProblem(q) === null;

/**
 * The words for a failed search: three different 429s each say what to do (the bucket: wait; `search-timeout`: narrow the search;
 * `search-busy`: the server is loaded, retry shortly), a 404 means the room is gone. Never the raw `error.message`.
 */
export function searchErrorMessage(err: unknown, t: TFunction): string {
  if (isProblem(err, PROBLEM_TYPES.searchTimeout)) return t("search.error.timeout");
  if (isProblem(err, PROBLEM_TYPES.searchBusy)) return t("search.error.busy");
  if (err instanceof ApiError) {
    if (err.status === 429) return t("search.error.rate");
    if (err.status === 404) return t("search.error.gone");
    return t("search.error.failedStatus", { status: err.status });
  }
  if (isTimeoutError(err)) return t("common.error.timeout");
  return t("common.error.network");
}
