import { useCallback } from "react";
import { useLocation } from "react-router-dom";
import { listRouteSearch, threadPath, withListParams } from "../utils/roomLinks";

/**
 * Where a `#42` in a message of room `roomId` leads (`MarkdownView`'s `threadHref`, 1.2 A9): thread 42 of the SAME room, carrying the list's
 * URL keys of the Threads tab the reader is on (`listRouteSearch`: the search tab's own `scope` and `page` are never the list's), so following
 * it never loses the reader's place in the list. A number with no thread is not checked here: the pane says "does not exist".
 */
export function useThreadRefHref(roomId: number): (number: number) => string {
  const { pathname, search } = useLocation();
  return useCallback((number: number) => withListParams(threadPath(roomId, number), listRouteSearch(pathname, search)), [roomId, pathname, search]);
}
