import { NOTIFICATION_TOTAL_CAP } from "../api/notifications";
import { notificationsKey } from "./roomKeys";

/** The bell's query keys, under the live channel's `notificationsKey` prefix so a `notification.created` hint re-reads both. */
export const notificationKeys = {
  /** Everything of the caller's notices (the live hint's key, and the refresh after a write). */
  all: notificationsKey,
  /** The unseen notices (one page of 100): its `total` is the badge. */
  unseen: [...notificationsKey, "unseen"] as const,
  /** One page of the drawer's list, seen and unseen. */
  list: (page: number) => [...notificationsKey, "list", page] as const,
};

/** The badge's text: the unseen total, "999+" once it reaches the server's cap (the total is "at least 1000" there). */
export function badgeText(total: number): string {
  return total >= NOTIFICATION_TOTAL_CAP ? `${NOTIFICATION_TOTAL_CAP - 1}+` : String(total);
}
