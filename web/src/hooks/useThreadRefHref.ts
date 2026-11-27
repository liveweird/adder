import { useCallback } from "react";
import { useLocation } from "react-router-dom";
import { listRouteSearch, threadPath, withListParams } from "../utils/roomLinks";

/**
 * Where a server-validated `#42` in a message of room `roomId` leads: thread 42 of the SAME room, carrying the list's
 * URL keys of the Threads tab the reader is on (`listRouteSearch`: the search tab's own `scope` and `page` are never the list's), so following
 * it never loses the reader's place in the list. Validation belongs to MarkdownView's bounded reference read; this hook only builds the route.
 */
export function useThreadRefHref(roomId: number): (number: number) => string {
  const { pathname, search } = useLocation();
  return useCallback((number: number) => withListParams(threadPath(roomId, number), listRouteSearch(pathname, search)), [roomId, pathname, search]);
}
