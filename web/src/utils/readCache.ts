import type { InfiniteData, QueryClient } from "@tanstack/react-query";
import type { MessagePage } from "../api/messages";
import type { NotificationPage } from "../api/notifications";
import { requestRefresh } from "./liveKeys";
import { notificationKeys } from "./notificationKeys";
import { roomKeys } from "./roomKeys";

type Row = Record<string, unknown>;

/** Maps the rows of a cached page (`{items: [...]}`); any other cached shape (a tag page, a bare record) is handed back untouched. */
function mapRows(page: unknown, update: (row: Row) => Row): unknown {
  if (typeof page !== "object" || page === null || !("items" in page) || !Array.isArray(page.items)) return page;
  return { ...page, items: page.items.map((row: Row) => update(row)) };
}

/** The room stops being new in the rail and the rooms list (no refetch: the server has just moved the same `seen_at`). */
export function markRoomSeenInCaches(queryClient: QueryClient, roomId: number): void {
  const clear = (row: Row) => (row.id === roomId ? { ...row, unread: false } : row);
  queryClient.setQueriesData<unknown>({ queryKey: roomKeys.rail }, (page: unknown) => mapRows(page, clear));
  queryClient.setQueriesData<unknown>({ queryKey: roomKeys.list }, (page: unknown) => mapRows(page, clear));
}

/**
 * After "Mark all as read" landed (`POST /rooms/{id}/read-all`): every cached list row and detail of the room reads as read at once (no
 * refetch: the server has just raised the caller's floor), the room is no longer new, and the lists, the rail and the bell are re-read, since
 * what they show now depends on the floor (the "Unread" filter's page is empty, the room's notices are seen). The conversation's own
 * pages are left alone: a re-read of the open thread would not change a word of it, and its "New" separator is taken once.
 */
export function markRoomReadInCaches(queryClient: QueryClient, roomId: number): void {
  queryClient.setQueriesData<unknown>({ queryKey: roomKeys.threadList(roomId) }, (page: unknown) =>
    mapRows(page, (row) => (typeof row.unreadCount === "number" ? { ...row, unreadCount: 0 } : row)),
  );
  queryClient.setQueriesData<unknown>({ queryKey: roomKeys.threadDetails(roomId) }, (thread: unknown) =>
    typeof thread === "object" && thread !== null && "unreadCount" in thread ? { ...thread, unreadCount: 0, firstUnreadMessageId: null } : thread,
  );
  markRoomSeenInCaches(queryClient, roomId);
  void queryClient.invalidateQueries({ queryKey: roomKeys.threadList(roomId) });
  void queryClient.invalidateQueries({ queryKey: roomKeys.rail });
  void queryClient.invalidateQueries({ queryKey: notificationKeys.all });
}

/** The newest message id the conversation's cache holds (its pages read newest first), or undefined while nothing is cached. */
function cachedNewestMessageId(queryClient: QueryClient, roomId: number, threadNumber: number): number | undefined {
  return queryClient.getQueryData<InfiniteData<MessagePage>>(roomKeys.messages(roomId, threadNumber))?.pages[0]?.items[0]?.id;
}

/**
 * After a read marker for `sentMessageId` landed: the thread's row in every cached list page and its detail read as read (no refetch), the room
 * is no longer new, and the bell is re-read only when its unseen rows held a notice of this thread (the server marked those seen in the same write).
 * Unless a newer message is already cached: a live re-read that listed it while the write was in flight has set the row (and the rail) from the
 * server's newer answer, which zeroing would undo, so those are re-read instead.
 */
export function markThreadReadInCaches(queryClient: QueryClient, roomId: number, threadNumber: number, sentMessageId: number): void {
  const newest = cachedNewestMessageId(queryClient, roomId, threadNumber);
  if (newest !== undefined && newest > sentMessageId) {
    void queryClient.invalidateQueries({ queryKey: roomKeys.threadList(roomId) });
    void queryClient.invalidateQueries({ queryKey: roomKeys.threadDetail(roomId, threadNumber) });
    void queryClient.invalidateQueries({ queryKey: roomKeys.rail });
  } else {
    queryClient.setQueriesData<unknown>({ queryKey: roomKeys.threadList(roomId) }, (page: unknown) =>
      mapRows(page, (row) => (row.number === threadNumber ? { ...row, unreadCount: 0 } : row)),
    );
    queryClient.setQueryData(roomKeys.threadDetail(roomId, threadNumber), (thread: object | undefined) =>
      thread === undefined ? undefined : { ...thread, unreadCount: 0, firstUnreadMessageId: null },
    );
    markRoomSeenInCaches(queryClient, roomId);
  }
  const holdsNotice = (page: NotificationPage | undefined) =>
    page?.items.some((item) => item.roomId === roomId && item.threadNumber === threadNumber) === true;
  // Either query (the badge's or the rail's) holding a notice of this thread means the server has just marked it seen: re-read both.
  const cached = [notificationKeys.unseen, notificationKeys.addressed].map((key) => queryClient.getQueryData<NotificationPage>(key));
  // Through the live invalidator's spaced queue (when the tab has one): the list is bucketed per user, and a marker drives this as often as
  // every two seconds while hints drive the same re-read, so the two coalesce into one flush per spacing window.
  if (cached.some(holdsNotice)) requestRefresh(queryClient, notificationKeys.all);
}
