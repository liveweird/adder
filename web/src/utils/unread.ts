import type { NotificationItem } from "../api/notifications";

/** The server counts at most this many unread messages in a thread; a count at the cap means "this many or more". */
export const UNREAD_COUNT_CAP = 100;

/** The visible count of a thread row: "99+" once the server's cap is reached (it never counts past 100). */
export function unreadBadge(count: number): string {
  return count >= UNREAD_COUNT_CAP ? `${UNREAD_COUNT_CAP - 1}+` : String(count);
}

/**
 * The unseen notices per room (the rail's number). They come from the bell's one query, which holds at most its first 100 rows: a person with
 * more unseen notices than that sees a lower number here until they read some (the badge itself carries the true total).
 */
export function unseenByRoom(items: readonly Pick<NotificationItem, "roomId">[]): ReadonlyMap<number, number> {
  const counts = new Map<number, number>();
  for (const item of items) counts.set(item.roomId, (counts.get(item.roomId) ?? 0) + 1);
  return counts;
}

/** The numbers of a room's threads that hold an unseen notice (the thread list's `@` mark). */
export function unseenThreadNumbers(items: readonly Pick<NotificationItem, "roomId" | "threadNumber">[], roomId: number): ReadonlySet<number> {
  return new Set(items.filter((item) => item.roomId === roomId).map((item) => item.threadNumber));
}
