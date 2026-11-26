import { useCallback, useMemo } from "react";
import type { Scope } from "../utils/threadListParams";
import { browserTimeZone } from "../utils/formatDate";
import { parseSearchParams, writeSearchParams, type SearchSyntax } from "../utils/searchParams";
import { useLiveSearchParams } from "./useLiveSearchParams";

/**
 * A search page's state, kept in the URL (`utils/searchParams.ts` is the pure half), so a search survives a reload and
 * can be shared or reached with Back. Writes go through `useLiveSearchParams` (a handler captured by an older render still builds on the
 * latest parameters). A new term and a page are history entries (Back returns to the previous search or page); the scope, the "Threads I watch"
 * toggle and the tags are refinements, written by `replace`. Anything but the page goes back to page 1 in the same write.
 */
export function useSearchUrl() {
  const [search, setSearch] = useLiveSearchParams();
  const state = useMemo(() => parseSearchParams(search), [search]);
  const submit = useCallback((q: string, syntax?: SearchSyntax) => setSearch((prev) => {
    const current = parseSearchParams(prev);
    const retry = current.q === q && current.syntax === syntax;
    return writeSearchParams(prev, {
      q,
      syntax,
      timeZone: syntax === undefined ? undefined : retry ? current.timeZone : browserTimeZone(),
    });
  }), [setSearch]);
  const setScope = useCallback((scope: Scope) => setSearch((prev) => writeSearchParams(prev, { scope }), { replace: true }), [setSearch]);
  const setWatching = useCallback((watching: boolean) => setSearch((prev) => writeSearchParams(prev, { watching }), { replace: true }), [setSearch]);
  const setUnread = useCallback((unread: boolean) => setSearch((prev) => writeSearchParams(prev, { unread }), { replace: true }), [setSearch]);
  const setThreadTag = useCallback((threadTag: string[]) => setSearch((prev) => writeSearchParams(prev, { threadTag }), { replace: true }), [setSearch]);
  const setPage = useCallback((page: number) => setSearch((prev) => writeSearchParams(prev, { page })), [setSearch]);
  /** The page was emptied under the reader (the results shrank, or a hand-edited `page=99`): back to the first, by `replace`. */
  const resetPage = useCallback(() => setSearch((prev) => writeSearchParams(prev, { page: 1 }), { replace: true }), [setSearch]);
  return { state, submit, setScope, setWatching, setUnread, setThreadTag, setPage, resetPage };
}
