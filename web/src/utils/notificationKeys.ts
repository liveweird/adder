import { NOTIFICATION_TOTAL_CAP } from "../api/notifications";
import { notificationsKey } from "./roomKeys";

/** The drawer's two views: every notice, or only those addressed to the reader (a mention of any kind, a hand-over). */
export type NoticeView = "all" | "mentions";

/** The bell's query keys, under the live channel's `notificationsKey` prefix so a `notification.created` hint re-reads both. */
export const notificationKeys = {
  /** Everything of the caller's notices (the live hint's key, and the refresh after a write). */
  all: notificationsKey,
  /** ALL the unseen notices (one page of 100): its `total` is the badge. */
  unseen: [...notificationsKey, "unseen"] as const,
  /** The unseen notices of the addressed kinds only (one page of 100): the rail's `@` count and the thread list's `@` mark. */
  addressed: [...notificationsKey, "addressed"] as const,
  /** One page of the drawer's list, seen and unseen, of every kind (`all`) or the addressed ones (`mentions`). */
  list: (page: number, view: NoticeView = "all") => [...notificationsKey, "list", view, page] as const,
};

/** The badge's text: the unseen total, "999+" once it reaches the server's cap (the total is "at least 1000" there). */
export function badgeText(total: number): string {
  return total >= NOTIFICATION_TOTAL_CAP ? `${NOTIFICATION_TOTAL_CAP - 1}+` : String(total);
}
